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
 * 本类只负责「抢占/归还」，不负责挂载；所有状态变更都有日志，便于现场排查。
 */
class UsbHalArbiter(private val shell: RootShell) {

    var savedConfig: String? = null
        private set

    @Volatile
    var acquired: Boolean = false
        private set

    fun acquire(): Boolean {
        if (acquired) return true
        var current = shell.getprop(SysProp.USB_CONFIG)

        if (current.isBlank() || current == "none") {
            Log.w(TAG, "sys.usb.config='$current' 属异常残留，先恢复 adb 以重建 gadget 子系统")
            shell.setprop(SysProp.USB_CONFIG, "adb")
            Thread.sleep(RECOVER_WAIT_MS)
            current = shell.getprop(SysProp.USB_CONFIG)
            Log.i(TAG, "恢复后 sys.usb.config='$current'")
        }

        savedConfig = current.takeIf { it.isNotBlank() && it != "none" }
        Log.i(TAG, "acquire UDC: original sys.usb.config='${savedConfig ?: ""}'")

        val enforce = shell.exec("getenforce").out.trim()
        if (enforce.startsWith("Enforcing")) {
            val r = shell.exec("setenforce 0")
            Log.w(TAG, "SELinux Enforcing -> permissive（挂载期间）：code=${r.exitCode}")
        } else {
            Log.i(TAG, "SELinux 状态=$enforce")
        }

        if (!shell.setprop(SysProp.USB_CONFIG, "none")) {
            Log.w(TAG, "setprop none failed (可能被厂商 HAL 拦截)")
        }
        Thread.sleep(MIN_RELEASE_WAIT_MS)

        val unbind = shell.exec(
            "for u in /config/usb_gadget/*/UDC; do " +
                "e=\$(echo '' > \"\$u\" 2>&1); rc=\$?; " +
                "echo \"\$u rc=\$rc err=[\$e]\"; done",
        )
        Log.i(TAG, "清空系统 gadget UDC：code=${unbind.exitCode} out=[${unbind.out.take(300)}]")
        val free = waitUdcFree()
        acquired = true
        if (!free) Log.w(TAG, "UDC still busy after ${SysProp.RELEASE_WAIT_MS}ms, mount may fail")
        return free
    }

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

    fun isUdcFree(): Boolean {
        val r = shell.exec("for u in /sys/class/udc/*/state; do cat \"\$u\" 2>/dev/null; done")
        if (!r.ok) return true
        val states = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (states.isEmpty()) return true
        return states.all { it == "not attached" }
    }

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
        private const val RECOVER_WAIT_MS = 2000L
        private const val MIN_RELEASE_WAIT_MS = 1500L
    }
}
