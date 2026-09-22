package com.allperiph.gadget

import com.allperiph.core.Log
import com.allperiph.core.SysPath
import com.allperiph.core.SysProp

/**
 * UDC 抢占与归还（架构 §6.2，最大坑）。
 *
 * v2.0 重写：不再创建独立 gadget（和 HAL 抢 UDC），而是复用系统 g1。
 * 参考 android-hid-client 的 Device-Specific Workaround：
 *   1. 保存 g1/configs/b.1/ 现有 symlink
 *   2. 解除所有 symlink
 *   3. 写换行符解绑 UDC
 *   4. 添加新 function（HID/ACM/UAC2）
 *   5. 重新链上原有 symlink
 *   6. 重新绑定 UDC
 * 这样 HAL 不会抢——因为用的是 g1，HAL 重启后发现 g1 已绑定就跳过。
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

        // 自愈：sys.usb.config=none 是上次失败残留
        if (current.isBlank() || current == "none") {
            Log.w(TAG, "sys.usb.config='$current' 属异常残留，先恢复 adb 以重建 gadget 子系统")
            shell.setprop(SysProp.USB_CONFIG, "adb")
            Thread.sleep(RECOVER_WAIT_MS)
            current = shell.getprop(SysProp.USB_CONFIG)
            Log.i(TAG, "恢复后 sys.usb.config='$current'")
        }

        savedConfig = current.takeIf { it.isNotBlank() && it != "none" }
        Log.i(TAG, "acquire UDC: original sys.usb.config='${savedConfig ?: ""}'")

        // SELinux 兜底
        val enforce = shell.exec("getenforce").out.trim()
        if (enforce.startsWith("Enforcing")) {
            val r = shell.exec("setenforce 0")
            Log.w(TAG, "SELinux Enforcing -> permissive（挂载期间）：code=${r.exitCode}")
        }

        acquired = true
        return true
    }

    /**
     * v2.0：复用 g1 模式——解绑 UDC → 添加 function → 重绑 UDC。
     *
     * 参考 android-hid-client 的 Device-Specific Workaround：
     * 不创建新 gadget，直接往 g1/configs/b.1 里链新 function。
     * 关键：必须先解绑 UDC 才能修改 configs/b.1 的 symlink，否则报 EINVAL。
     */
    fun unbindUdc(): String {
        // 找到当前绑定的 UDC 名
        val r = shell.exec("for u in /sys/class/udc/*/state; do " +
            "s=\$(cat \"\$u\" 2>/dev/null); " +
            "case \"\$s\" in configured*|addressed*) echo \$(basename \$(dirname \"\$u\"));; esac; done")
        val udcName = r.out.trim().lines().firstOrNull()?.trim() ?: ""
        if (udcName.isEmpty()) {
            Log.w(TAG, "未找到已绑定的 UDC，跳过解绑")
            return ""
        }
        // 解绑：写换行符到 g1/UDC
        val unbind = shell.exec("echo '' > /config/usb_gadget/g1/UDC")
        Log.i(TAG, "解绑 UDC '$udcName'：code=${unbind.exitCode}")
        // 等内核完成解绑
        Thread.sleep(500)
        return udcName
    }

    /**
     * 重新绑定 UDC（添加 function 完成后调用）。
     * HAL 会检测到 g1 被绑定，但它用的就是 g1，不会冲突。
     */
    fun rebindUdc(udcName: String) {
        val r = shell.exec("printf '%s' '$udcName' > /config/usb_gadget/g1/UDC")
        Log.i(TAG, "重绑 UDC '$udcName'：code=${r.exitCode}")
        Thread.sleep(500)
    }

    /** 等待 UDC 绑定成功 */
    fun waitForConfigured(): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val r = shell.exec("cat /sys/class/udc/a600000.dwc3/state 2>/dev/null")
            if (r.out.trim() == "configured") return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    fun release() {
        if (!acquired) return
        acquired = false
        val target = savedConfig?.takeIf { it.isNotBlank() } ?: "adb"
        Log.i(TAG, "release UDC: restore sys.usb.config='$target'")
        shell.setprop(SysProp.USB_CONFIG, target)
        savedConfig = null
    }

    companion object {
        private const val TAG = "UsbHalArbiter"
        private const val RECOVER_WAIT_MS = 2000L
    }
}
