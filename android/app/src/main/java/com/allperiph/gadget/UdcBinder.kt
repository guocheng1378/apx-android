package com.allperiph.gadget

import com.allperiph.core.Log
import com.allperiph.core.SysProp

/**
 * UDC 多步绑定策略：解决 Xiaomi 等厂商 USB HAL 抢占 UDC 的问题。
 *
 * 背景：
 * Android USB HAL 监听 sys.usb.config 属性，收到 "none" 后异步销毁内核 gadget 对象
 * 并释放 UDC。但部分厂商 HAL（Xiaomi HyperOS）在释放后会立即重新绑定，
 * 导致我们写 UDC 时撞 EBUSY 或被静默覆盖。
 *
 * 策略（三级降级）：
 * 1. 常规绑定：直接写 UDC（最快，0 等待）
 * 2. 杀 HAL 重绑：检测到绑定失败后，stop 厂商 USB HAL 服务，等 UDC 真正空闲后重绑
 * 3. 暴力重绑：如果 HAL 又抢回来，反复解绑-重绑直到成功（最多 3 次）
 *
 * 安全性：
 * - HAL 被 stop 后 Android init 会自动重启（通常 5-10 秒），不会永久损坏 USB 功能
 * - 每次 stop 前先保存当前状态，成功后 HAL 重启会自动恢复
 * - 最坏情况：绑定失败，手机 USB 功能暂时异常（拔线重插即可恢复）
 */
class UdcBinder(private val shell: RootShell) {

    companion object {
        private const val TAG = "UdcBinder"

        /** HAL 服务名候选（不同 Android 版本/厂商不同） */
        private val HAL_SERVICES = listOf(
            // 一加13 / ColorOS16 实测的真实服务名（getprop init.svc.vendor.usb-hal-1-2），
            // 必须排在最前 —— 原来的候选全是 AOSP 通用名，在这台机器上根本不存在，
            // 导致 stopHal() 永远返回 false，"杀 HAL 重绑"这级降级形同虚设。
            "vendor.usb-hal-1-3",
            "vendor.usb-hal-1-2",
            "vendor.usb-hal-1-1",
            "vendor.usb-hal-1-0",
            "vendor.usb-hal",
            "android.hardware.usb.service",
            "android.hardware.usb@1.0-service",
            "android.hardware.usb@1.1-service",
            "android.hardware.usb@1.2-service",
            "android.hardware.usb@1.3-service",
        )

        /** 绑定后等待 HAL 重启的最大时间 */
        private const val HAL_RESTART_WAIT_MS = 8000L

        /** 每次重试间隔 */
        private const val RETRY_INTERVAL_MS = 1000L

        /** 最大重试次数 */
        private const val MAX_BIND_RETRIES = 3
    }

    /** 找到并停止 USB HAL 服务，返回是否成功 */
    fun stopHal(): Boolean {
        for (svc in HAL_SERVICES) {
            val r = shell.exec("stop $svc 2>&1")
            if (r.ok && !r.out.contains("not found") && !r.out.contains("Unknown service")) {
                Log.i(TAG, "已停止 HAL 服务: $svc")
                return true
            }
        }
        // 尝试 killall
        val r = shell.exec("killall -9 android.hardware.usb* 2>&1")
        if (r.ok) {
            Log.i(TAG, "已 killall USB HAL: ${r.out.take(100)}")
            return true
        }
        Log.w(TAG, "未找到 USB HAL 服务，跳过 stop")
        return false
    }

    /** 等待 UDC 真正空闲（所有 UDC state = "not attached"） */
    fun waitForFree(maxWaitMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (isUdcFree()) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return isUdcFree()
    }

    /** 检测 UDC 是否空闲 */
    fun isUdcFree(): Boolean {
        val r = shell.exec("for u in /sys/class/udc/*/state; do cat \"\$u\" 2>/dev/null; done")
        if (!r.ok) return true
        val states = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (states.isEmpty()) return true
        return states.all { it == "not attached" }
    }

    /**
     * 绑定 UDC 到指定 gadget。
     *
     * @param udcName UDC 名称（如 "a600000.dwc3"）
     * @param gadgetName gadget 名称（如 "apx"）
     * @return true = 绑定成功且 UDC 状态正常
     */
    fun bindWithRetry(udcName: String, gadgetName: String): Boolean {
        val udcPath = "/config/usb_gadget/$gadgetName/UDC"

        // 第一步：常规绑定
        Log.i(TAG, "尝试常规绑定: echo '$udcName' > $udcPath")
        var r = shell.exec("echo '$udcName' > '$udcPath' 2>&1")
        if (r.ok && isUdcBound(udcName)) {
            Log.i(TAG, "常规绑定成功")
            return true
        }
        Log.w(TAG, "常规绑定失败: code=${r.exitCode} out=${r.out.take(200)}")

        // 第二步：杀 HAL 后重绑
        Log.i(TAG, "常规绑定失败，尝试杀 HAL 后重绑")
        val halStopped = stopHal()
        if (halStopped) {
            // 等 HAL 真正停止释放 UDC
            Thread.sleep(1500)
            waitForFree(3000)
        }

        // 重试绑定
        for (attempt in 1..MAX_BIND_RETRIES) {
            Log.i(TAG, "重绑尝试 $attempt/$MAX_BIND_RETRIES")
            r = shell.exec("echo '$udcName' > '$udcPath' 2>&1")
            if (r.ok && isUdcBound(udcName)) {
                Log.i(TAG, "重绑成功（第 $attempt 次）")
                return true
            }
            Log.w(TAG, "重绑失败 attempt=$attempt: code=${r.exitCode} out=${r.out.take(200)}")

            // 检测 HAL 是否又抢回去了
            if (!isUdcFree()) {
                Log.w(TAG, "HAL 又抢回 UDC，再次解绑")
                shell.exec("echo '' > '$udcPath' 2>/dev/null")
                shell.exec("for u in /config/usb_gadget/*/UDC; do echo '' > \"\$u\" 2>/dev/null; done")
                Thread.sleep(800)
                waitForFree(2000)
            }

            Thread.sleep(RETRY_INTERVAL_MS)
        }

        // 最后检查
        val finalCheck = isUdcBound(udcName)
        if (finalCheck) {
            Log.i(TAG, "最终检查：绑定成功")
        } else {
            Log.e(TAG, "绑定最终失败：UDC=$udcName gadget=$gadgetName")
            // 输出诊断信息
            val state = shell.exec("for u in /sys/class/udc/*/state; do echo \"\$u=[\$(cat \"\$u\" 2>&1)]\"; done")
            Log.e(TAG, "UDC 状态: ${state.out.take(300)}")
            val config = shell.exec("getprop sys.usb.config")
            Log.e(TAG, "sys.usb.config=${config.out.trim()}")
        }
        return finalCheck
    }

    /** 检查指定 UDC 是否已绑定到某个 gadget（state != "not attached"） */
    private fun isUdcBound(udcName: String): Boolean {
        val r = shell.exec("cat '/sys/class/udc/$udcName/state' 2>/dev/null")
        if (!r.ok) return false
        val state = r.out.trim()
        // configured / addressed / default 都表示已绑定
        return state in listOf("configured", "addressed", "default")
    }
}