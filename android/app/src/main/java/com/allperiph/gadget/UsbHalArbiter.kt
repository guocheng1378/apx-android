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

        // ⚠️ 这里**绝不能**用 setprop 去改写 sys.usb.config（设 none / 设 adb / 恢复原值都不行）：
        // 一加13 ColorOS16 实测会让 init 在 USB 属性处理路径上收到 SIGABRT，
        // PID 1 崩溃 = **整机重启**（uptime 归零，crash buffer 可见 init Fatal signal 6）。
        // 抢占只做 configfs 的 UDC 清空（见下），属性一律只读不写。
        savedConfig = current.takeIf { it.isNotBlank() && it != "none" }
        Log.i(TAG, "acquire UDC：只读属性不改写，原值='${savedConfig ?: ""}'")

        val enforce = shell.exec("getenforce").out.trim()
        if (enforce.startsWith("Enforcing")) {
            val r = shell.exec("setenforce 0")
            Log.w(TAG, "SELinux Enforcing -> permissive（挂载期间）：code=${r.exitCode}")
        } else {
            Log.i(TAG, "SELinux 状态=$enforce")
        }

        // 不再 setprop none（会让 init 崩溃、整机重启，见上）。
        // UDC 的释放完全靠下面 glob 写空所有 gadget 的 UDC 完成。

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
        // 同样**绝不 setprop**。归还 = 解绑我们的 gadget + 把 UDC 交还系统 g1。
        // UDC 控制器名只读获取（sys.usb.controller，回退 ro.boot.usbcontroller）。
        val udcName = shell.getprop("sys.usb.controller").ifBlank {
            shell.getprop("ro.boot.usbcontroller")
        }
        shell.exec("for x in /config/usb_gadget/*/UDC; do echo '' > \"\$x\" 2>/dev/null; done")
        if (udcName.isNotBlank()) {
            val r = shell.exec("echo '$udcName' > '/config/usb_gadget/g1/UDC' 2>&1; echo rc=\$?")
            Log.i(TAG, "UDC 已归还 g1：${r.out.trim()}")
        } else {
            Log.w(TAG, "未知 UDC 控制器名，仅解绑未回绑（重插 USB 可恢复）")
        }
        savedConfig = null
    }

    companion object {
        private const val TAG = "UsbHalArbiter"
        private const val RECOVER_WAIT_MS = 2000L
        private const val MIN_RELEASE_WAIT_MS = 1500L
    }
}
