package com.allperiph.gadget

import android.content.Context
import com.allperiph.core.AgentRuntime
import com.allperiph.core.AgentStatus
import com.allperiph.core.ApxNative
import com.allperiph.core.GadgetConst
import com.allperiph.core.Log
import com.allperiph.core.ModuleId
import com.allperiph.core.SysPath
import com.allperiph.core.UsbId
import com.allperiph.core.UsbPath
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeoutException
import kotlin.math.min

/**
 * ConfigFS 复合设备组装（架构 §2.3）—— 驱动 App 的「心脏」。
 *
 * ### 职责边界（与 GadgetLifecycle 的分工，§2.4）
 * - 本类：ConfigFS 布局 / 创建 function / 挂载 / 解挂 / 清理
 * - GadgetLifecycle：用户态开关 / 重试 / 生命周期事件
 * - GadgetManager：本类与 HidDevice 的唯一调用方
 *
 * ### v2.0：复用 g1 模式
 * 不再创建独立 gadget（和 HAL 抢 UDC 会崩溃），改为往系统 g1 里添加 function。
 * 参考 android-hid-client 的 Device-Specific Workaround：
 *   unbind UDC → 添加 function → rebind UDC
 * HAL 不会抢——因为用的是 g1 本身。
 */
class GadgetManager {
    private var shell: RootShell? = null
    private var _state = AgentStatus.IDLE
    val state get() = _state
    private var opts = GadgetOptions()
    private val hal = UsbHalArbiter(RootShell())
    private var pollThread: Thread? = null
    private var lastUdcState = ""

    /**
     * 启动入口（GadgetLifecycle.start / 有线子模块 start 均可调用）：
     * - 优先 root 一键挂载（真机一步到位）
     * - 无 root 或超时降级到 adb 脚本
     * - 失败后自动进入降级重试流程（见 [startFallbackWithRetry]）
     *
     * v1.6：自动探测真实 ConfigFS 挂载点，消除「Out of memory」误判。
     */
    fun start(ctx: Context) {
        if (_state == AgentStatus.RUNNING) {
            Log.w(TAG, "重复 start()，跳过")
            return
        }
        _state = AgentStatus.STARTING
        val sh = RootShell()
        shell = sh

        val configRoot = resolveConfigfsRoot(sh)
        Log.i(TAG, "ConfigFS 挂载点：$configRoot")
        opts = opts.copy(configfsRoot = configRoot)

        val reportSrc = File(contextFilesDir(ctx), GadgetConst.DESCRIPTOR_FILENAME)
        val descriptor = ApxNative.hidReportDescriptorOrNull()
        if (descriptor != null) {
            reportSrc.writeBytes(descriptor)
            Log.i(TAG, "报告描述符已由 shared/ 生成：${descriptor.size}B → ${reportSrc.absolutePath}")
        } else {
            Log.w(TAG, "shared/ 未返回报告描述符，尝试从已有文件加载")
        }

        if (opts.reportDescSource == null && reportSrc.exists()) {
            opts = opts.copy(reportDescSource = reportSrc.absolutePath)
        }

        val primary = Runnable {
            try {
                if (!hal.acquire()) {
                    Log.w(TAG, "HAL acquire 超时，重试不保证成功，继续尝试挂载")
                }
                stageDescriptor(opts)
                // v2.0：复用 g1 模式
                // 步骤：解绑 UDC → 挂载 function → 重绑 UDC
                val savedUdc = hal.unbindUdc()
                val steps = ConfigFsLayout.mountReuse(opts)
                for ((i, step) in steps.withIndex()) {
                    Log.v(TAG, "[$i/${steps.size}] ${step.cmd}")
                    val r = sh.exec(step.cmd)
                    if (!r.ok && !step.optional) {
                        Log.e(TAG, "gadget mount step failed (${step.cmd}): code=${r.exitCode} out=[${r.out.take(120)}] err=[${r.err.take(120)}]")
                        throw IllegalStateException("gadget error: ${step.cmd}")
                    }
                }
                // 重绑 UDC
                if (savedUdc.isNotEmpty()) {
                    hal.rebindUdc(savedUdc)
                }
                // chmod
                sh.exec("chmod 666 '${SysPath.HIDG_DEVICE}'")
                sh.exec("chmod 666 '${SysPath.ACM_DEVICE}'")
                sh.exec("chmod 666 /dev/snd/pcmC* 2>/dev/null")
            } catch (e: Exception) {
                Log.e(TAG, "root 一键挂载失败: ${e.message}")
                val root = opts.root()
                sh.exec("umount '$root' 2>/dev/null")
                sh.exec("rmdir '$root' 2>/dev/null")
                throw e
            }
        }

        val fallback = Runnable {
            startFallbackWithRetry(opts, sh)
        }

        try {
            sh.open()
            primary.run()
            _state = AgentStatus.RUNNING
            startUdcPoll()
            Log.i(TAG, "gadget 已激活")
        } catch (e: Exception) {
            Log.w(TAG, "primary 路径失败（${e.javaClass.simpleName}: ${e.message}），进入降级重试")
            startFallbackWithRetry(opts, sh)
        }
    }

    private fun startFallbackWithRetry(opts: GadgetOptions, sh: RootShell) {
        val root = opts.root()
        val fallback = Runnable {
            GadgetLifecycle.retryUntilSuccess(sh, opts) {
                stageDescriptor(opts)
                ConfigFsLayout.mount(opts, UdcProbe.pickBest(sh))
            }
        }
        try {
            fallback.run()
            _state = AgentStatus.RUNNING
            startUdcPoll()
            Log.i(TAG, "gadget 已激活（降级路径）")
        } catch (e: Exception) {
            _state = AgentStatus.ERROR
            Log.e(TAG, "gadget 挂载失败（降级路径）: ${e.message}")
            sh.exec("umount '$root' 2>/dev/null")
            sh.exec("rmdir '$root' 2>/dev/null")
            AgentRuntime.reportFailure(e)
        }
    }

    fun stop() {
        stopUdcPoll()
        try {
            hal.release()
        } catch (e: Exception) {
            Log.w(TAG, "HAL release failed: ${e.message}")
        }
        shell?.close()
        shell = null
        _state = AgentStatus.IDLE
    }

    fun markError() {
        _state = AgentStatus.ERROR
    }

    private fun contextFilesDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun resolveConfigfsRoot(sh: RootShell): String {
        if (opts.configfsRoot != SysPath.CONFIGFS_USB_GADGET) return opts.configfsRoot
        val candidates = listOf(
            SysPath.CONFIGFS_USB_GADGET,
            "/config/usb_gadget",
            "/sys/kernel/config/usb_gadget",
        )
        val probe = sh.exec(
            candidates.joinToString(" || ") { "test -d '$it' && echo '$it'" },
        )
        val fromProbe = probe.out.trim().lines().firstOrNull { it.isNotBlank() }
        if (!fromProbe.isNullOrBlank()) return fromProbe

        val mounts = sh.exec("mount -t configfs")
        for (line in mounts.out.lines()) {
            val parts = line.split(" on ")
            if (parts.size < 2) continue
            val mountPoint = parts[1].substringBefore(" ").trimEnd()
            if (mountPoint.isNotBlank()) return mountPoint
        }

        val dmesg = sh.exec("dmesg | grep -i 'configfs: mounted' | tail -1")
        val m = Regex("on\s+([^ ]+)").find(dmesg.out)
        if (m != null) {
            val mp = m.groupValues[1]
            if (mp.isNotBlank()) return mp
        }

        Log.w(TAG, "未探测到 configfs 挂载点，回退默认值 ${SysPath.CONFIGFS_USB_GADGET}")
        return SysPath.CONFIGFS_USB_GADGET
    }

    /**
     * 阶段 B：把报告描述符写入 ConfigFS（根目录下的 report_desc）。
     * 写入前校验内容，若设备已写入相同内容则跳过（避免重复触发生效）。
     * reportDescSource 为空时跳过——调用方已处理缺失情况。
     */
    private fun stageDescriptor(o: GadgetOptions) {
        val src = o.reportDescSource ?: return
        val dest = "${o.functionDir(GadgetFeature.HID)}/report_desc"
        val srcFile = File(src)
        val srcBytes = srcFile.readBytes()
        val sh = shell ?: return

        val existingSize = sh.exec("wc -c < '$dest' 2>/dev/null")
        if (existingSize.ok && existingSize.out.trim() == srcBytes.size.toString()) {
            val cmp = sh.exec("cmp -s '$src' '$dest' 2>/dev/null; echo \$?")
            if (cmp.ok && cmp.out.trim() == "0") {
                Log.i(TAG, "报告描述符已相同，跳过写入")
                return
            }
        }

        val r = sh.exec("cp '$src' '$dest'")
        if (!r.ok) Log.w(TAG, "写入报告描述符失败: code=${r.exitCode} err=[${r.err.take(120)}]")
    }

    private fun startUdcPoll() {
        stopUdcPoll()
        pollThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
                val sh = shell ?: break
                val s = sh.exec("cat /sys/class/udc/a600000.dwc3/state 2>/dev/null").out.trim()
                if (s != lastUdcState) {
                    Log.w(TAG, "UDC 状态变化：$lastUdcState → $s")
                    lastUdcState = s
                    if (s == "not attached" || s.isEmpty()) {
                        _state = AgentStatus.ERROR
                    }
                }
            }
        }, "udc-poll").apply { isDaemon = true; start() }
    }

    private fun stopUdcPoll() {
        pollThread?.interrupt()
        pollThread = null
    }

    companion object {
        private const val TAG = "GadgetManager"
    }
}
