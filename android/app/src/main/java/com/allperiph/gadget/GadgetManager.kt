package com.allperiph.gadget

import android.content.Context
import com.allperiph.core.AgentRuntime
import com.allperiph.core.AgentStatus
import com.allperiph.core.ApxNative
import com.allperiph.core.GadgetConst
import com.allperiph.core.Log
import com.allperiph.core.SysPath
import java.io.File

/**
 * ConfigFS 复合设备组装（架构 §2.3）—— 驱动 App 的「心脏」。
 *
 * v2.0：复用 g1 模式
 * 不再创建独立 gadget（和 HAL 抢 UDC 会崩溃），改为往系统 g1 里添加 function。
 * 参考 android-hid-client 的 Device-Specific Workaround：
 *   unbind UDC → 添加 function + 写描述符 → rebind UDC
 */
class GadgetManager {
    private var shell: RootShell? = null
    private var _state = AgentStatus.IDLE
    val state get() = _state
    private var opts = GadgetOptions(
        features = GadgetFeature.g1ReuseDefaults(),
    )
    private val hal = UsbHalArbiter(RootShell())
    private var pollThread: Thread? = null
    private var lastUdcState = ""

    fun start(ctx: Context) {
        if (_state == AgentStatus.RUNNING) {
            Log.w(TAG, "重复 start()，跳过")
            return
        }
        _state = AgentStatus.STARTING
        val sh = RootShell()
        shell = sh

        // 1. 生成报告描述符到本地文件
        val reportSrc = File(contextFilesDir(ctx), GadgetConst.DESCRIPTOR_FILENAME)
        val descriptor = ApxNative.hidReportDescriptorOrNull()
        if (descriptor != null) {
            reportSrc.writeBytes(descriptor)
            Log.i(TAG, "报告描述符已由 shared/ 生成：${descriptor.size}B")
        } else {
            Log.w(TAG, "shared/ 未返回报告描述符，尝试从已有文件加载")
        }
        if (opts.reportDescSource == null && reportSrc.exists()) {
            opts = opts.copy(reportDescSource = reportSrc.absolutePath)
        }

        try {
            sh.open()
            if (!hal.acquire()) {
                Log.w(TAG, "HAL acquire 超时，继续尝试")
            }

            // 2. 解绑 UDC（必须在添加 function 之前）
            val savedUdc = hal.unbindUdc()

            // 3. 创建 function 目录 + 链接到 configs/b.1
            val steps = ConfigFsLayout.mountReuse(opts)
            for ((i, step) in steps.withIndex()) {
                Log.v(TAG, "[$i/${steps.size}] ${step.cmd}")
                val r = sh.exec(step.cmd)
                if (!r.ok && !step.optional) {
                    Log.e(TAG, "mount failed (${step.cmd}): code=${r.exitCode} err=[${r.err.take(120)}]")
                    throw IllegalStateException("gadget error: ${step.cmd}")
                }
            }

            // 4. 写入报告描述符（必须在 hid.usb0 目录创建之后）
            stageDescriptor(opts)

            // 5. 重绑 UDC
            if (savedUdc.isNotEmpty()) {
                hal.rebindUdc(savedUdc)
            }
            sh.exec("chmod 666 '${SysPath.HIDG_DEVICE}' 2>/dev/null")
            sh.exec("chmod 666 '${SysPath.ACM_DEVICE}' 2>/dev/null")

            _state = AgentStatus.RUNNING
            startUdcPoll()
            Log.i(TAG, "gadget 已激活（g1 复用模式）")
        } catch (e: Exception) {
            _state = AgentStatus.ERROR
            Log.e(TAG, "gadget 挂载失败: ${e.message}")
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

    /** 写入报告描述符到 hid.usb0/report_desc（必须在目录创建之后调用） */
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
        if (!r.ok) Log.w(TAG, "写入报告描述符失败: code=${r.exitCode}")
    }

    private fun startUdcPoll() {
        stopUdcPoll()
        pollThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
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
