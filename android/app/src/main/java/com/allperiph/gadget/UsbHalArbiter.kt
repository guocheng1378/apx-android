package com.allperiph.gadget

import com.allperiph.core.Log
import com.allperiph.core.SysPath
import com.allperiph.core.SysProp

/**
 * UDC 抢占与归还（架构 §6.2，最大坑）。
 *
 * Android USB HAL（adbd / mtp / accessory）会占用 UDC。写 `UDC` 前必须先把
 * `sys.usb.config` 置为 `none` 让 HAL 解绑，否则写入会 EBUSY；退出时必须恢复原配置，
 * 否则手机整根 USB 功能失效（连 adb 都断）。
 *
 * v1.35 修复：HAL 解绑后会监听 UDC 空闲事件并自动重绑 g1，与 apx 竞争同一个
 * UDC。解决：在 acquire() 阶段主动 kill HAL 进程（init 会在绑定完成后自动重启）。
 */
class UsbHalArbiter(private val shell: RootShell) {

    /** 抢占前的原始 sys.usb.config，用于还原 */
    var savedConfig: String? = null
        private set

    @Volatile
    var acquired: Boolean = false
        private set

    fun acquire(): Boolean {
        if (acquired) return true
        var current = shell.getprop(SysProp.USB_CONFIG)

        // 自愈：上一次挂载失败可能把 sys.usb.config 留在 none（失败路径没有归还），
        // 此时内核侧 gadget 子系统处于卸载状态，随后 mkdir /config/usb_gadget/<name>
        // 会失败并返回**误导性的 ENOMEM**（实测 Xiaomi HyperOS 复现：
        // "mkdir: '/config/usb_gadget/apx': Out of memory"）。
        // 先恢复一个正常配置让 HAL/gadget 重新初始化，再走正常抢占流程。
        if (current.isBlank() || current == "none") {
            Log.w(TAG, "sys.usb.config='$current' 属异常残留，先恢复 adb 以重建 gadget 子系统")
            shell.setprop(SysProp.USB_CONFIG, "adb")
            Thread.sleep(RECOVER_WAIT_MS)
            current = shell.getprop(SysProp.USB_CONFIG)
            Log.i(TAG, "恢复后 sys.usb.config='$current'")
        }

        savedConfig = current.takeIf { it.isNotBlank() && it != "none" }
        Log.i(TAG, "acquire UDC: original sys.usb.config='${savedConfig ?: ""}'")

        // SELinux 兜底：Android 的 USB gadget 由 init（u:r:init:s0）管理，
        // 第三方进程在 su 域下访问 configfs 会被策略拦截（dmesg 可见 avc denied），
        // 表现为 symlink 等操作返回无法解释的错误码。挂载期间临时 permissive。
        val enforce = shell.exec("getenforce").out.trim()
        if (enforce.startsWith("Enforcing")) {
            val r = shell.exec("setenforce 0")
            Log.w(TAG, "SELinux Enforcing -> permissive（挂载期间）：code=${r.exitCode}")
        } else {
            Log.i(TAG, "SELinux 状态=$enforce")
        }

        // 关键：先 none，再等 HAL 真正解绑
        if (!shell.setprop(SysProp.USB_CONFIG, "none")) {
            Log.w(TAG, "setprop none failed (可能被厂商 HAL 拦截)")
        }
        // HAL 处理 none 是异步的：它要销毁内核侧的 gadget 对象再释放 UDC。
        // 抢在它前面操作会撞内核竞态（现象是 configfs 目录读挂起 + 创建返回
        // 误导性的 ENOMEM）。先固定等一个下限，再轮询确认。
        Thread.sleep(MIN_RELEASE_WAIT_MS)

        // 厂商 HAL 兜底：实测 Xiaomi HyperOS 收到 sys.usb.config=none 之后
        // **并不会真正释放 UDC**（我们写自己的 UDC 时报 EBUSY）。这里把所有
        // gadget 的 UDC 一并清空，逼内核完成解绑。
        val unbind = shell.exec(
            "for u in /config/usb_gadget/*/UDC; do " +
                "e=\$(echo '' > \"\$u\" 2>&1); rc=\$?; " +
                "echo \"\$u rc=\$rc err=[\$e]\"; done",
        )
        Log.i(TAG, "清空系统 gadget UDC：code=${unbind.exitCode} out=[${unbind.out.take(300)}]")

        // v1.35：HAL 进程在 UDC 空闲后会自动重绑 g1（监听 uevent）。
        // 在解绑完成后、绑定 apx 之前，杀掉 HAL 进程。
        // Android init 会在 apx 绑定完成后自动重启 HAL，但此时 UDC 已被占用，
        // HAL 重启后检测到 UDC 非空闲就不会再抢。
        killUsbHal()

        val free = waitUdcFree()
        acquired = true
        if (!free) Log.w(TAG, "UDC still busy after ${SysProp.RELEASE_WAIT_MS}ms, mount may fail")
        return free
    }

    /**
     * 杀掉 USB HAL 进程，阻止它在 UDC 空闲后自动重绑 g1。
     * 
     * Android init 的逻辑：进程被杀后按 .rc 文件里的 restart 策略重启。
     * USB HAL 通常是 oneshot 或 restart 策略。关键在于：HAL 重启时会读取
     * sys.usb.config 决定绑定哪个 gadget——如果此时 apx 已绑定 UDC，
     * HAL 会发现 UDC 非空闲而跳过绑定。
     *
     * 候选进程名（按常见 Android 版本排列）：
     *   - android.hardware.usb@1.0-service
     *   - android.hardware.usb@1.1-service
     *   - android.hardware.usb.service
     *   - android.hardware.usb.configstore@1.0-service
     */
    private fun killUsbHal() {
        val candidates = listOf(
            "android.hardware.usb.service",
            "android.hardware.usb@1.1-service",
            "android.hardware.usb@1.0-service",
        )
        var killed = false
        for (name in candidates) {
            val r = shell.exec("killall '$name' 2>/dev/null")
            if (r.ok && r.exitCode == 0) {
                Log.i(TAG, "USB HAL 已终止：$name")
                killed = true
                break
            }
        }
        if (!killed) {
            // 兜底：按包名模糊匹配
            val r = shell.exec("pkill -f 'android.hardware.usb' 2>/dev/null")
            Log.w(TAG, "USB HAL killall 未命中，pkill 结果：code=${r.exitCode}")
        }
        // 给 init 时间完成清理，但不能太长——HAL 重启后可能再次抢占
        Thread.sleep(300)
    }

    /** 等待所有 gadget 的 UDC 文件被清空，即 HAL 已解绑 */
    fun waitUdcFree(): Boolean {
        val deadline = System.currentTimeMillis() + SysProp.RELEASE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isUdcFree()) return true
            try {
                Thread.sleep(SysProp.RELEASE_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return isUdcFree()
    }

    /**
     * UDC 是否空闲。
     *
     * **绝不能读 /config/usb_gadget**：实测该目录的读操作会在内核里挂起，
     * 吃满 RootShell 超时后断链，之后所有命令静默返回空 —— 会把真实错误彻底
     * 掩盖（曾表现为 mkdir 报 code=-1、诊断全空）。
     * 改用 sysfs 的 UDC state，读取不阻塞，语义也更直接：
     * 未被任何 gadget 绑定时为 "not attached"。
     */
    fun isUdcFree(): Boolean {
        val r = shell.exec("for u in /sys/class/udc/*/state; do cat \"\$u\" 2>/dev/null; done")
        if (!r.ok) return true
        val states = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (states.isEmpty()) return true
        return states.all { it == "not attached" }
    }

    /**
     * 归还：恢复原配置。原值为空时退回 adb，保证调试通道不丢。
     */
    fun release() {
        if (!acquired) return
        acquired = false
        val target = savedConfig?.takeIf { it.isNotBlank() } ?: "adb"
        Log.i(TAG, "release UDC: restore sys.usb.config='$target'")
        if (!shell.setprop(SysProp.USB_CONFIG, target)) {
            Log.w(TAG, "restore failed, try fallback adb")
            shell.setprop(SysProp.USB_CONFIG, "adb")
        }
        savedConfig = null
    }

    companion object {
        private const val TAG = "UsbHalArbiter"

        /** 恢复 adb 后等待 gadget 子系统重建的时间 */
        private const val RECOVER_WAIT_MS = 2000L

        /** 设 none 后等待 HAL 销毁内核侧 gadget 的最小时间 */
        private const val MIN_RELEASE_WAIT_MS = 1500L
    }
}
