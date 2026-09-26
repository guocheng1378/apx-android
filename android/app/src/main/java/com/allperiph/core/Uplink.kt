package com.allperiph.core

/**
 * 上行出口：手机的光标 / 按键此刻**到底发去了哪里**。
 *
 * 为什么需要它（真机教训）：择路是**多级回落**的（已选无线目标 → USB HID → 蓝牙 HID → 无处可去），
 * 每一级失效都只写一行日志，于是用户看到的现象统一都是"点不动"，分不清是"没连上"、
 * "USB 没挂上"还是"目标只是断了"。更糟的是**各处口径还不一致**：
 * 通知栏按「USB → 蓝牙 → 无线」判，而触控板实际按「无线 → 蓝牙 → USB」发 —— 于是通知说
 * "USB 链路"、按键却走了无线，用户自然越看越糊涂。
 *
 * 所以这里做**唯一判据**：发送路径、通知栏、界面全部读这一处（[resolve] 决定发给谁，
 * [current] 记录实际发出去了没）。改择路只改这一个函数。
 */
object Uplink {

    const val WIRELESS = "wireless"     // 已选定的无线受控目标（9511）
    const val USB = "usb"               // 本机 USB HID gadget（无需对端装软件）
    const val BLUETOOTH = "bluetooth"   // 蓝牙 HID
    const val NONE = "none"             // 无出口：输入会被直接丢掉

    /**
     * 择路**唯一判据**（优先级自上而下）：
     * 1. 选了受控目标（TV / PC）→ 无线 9511（此时**覆盖**蓝牙与 USB，见 TouchpadModule 注释）
     * 2. USB HID 就绪 → USB
     * 3. 蓝牙 HID 已连 → 蓝牙
     * 4. 都没有 → [NONE]（输入无处可去，调用方必须如实提示而不是静默丢弃）
     *
     * @param hid 本机 HID 出口（[ModuleContext.hid] / [AgentRuntime.hid]）
     * @param btConnected 蓝牙 HID 是否已连接
     * @param wireless 是否处于"已选受控目标且链路可用"
     */
    fun resolve(hid: HidTransport?, btConnected: Boolean, wireless: Boolean): String = when {
        wireless -> WIRELESS
        hid != null && hid.isReady() -> USB
        btConnected -> BLUETOOTH
        else -> NONE
    }

    /** 当前出口（由发送路径在每次真实发送时写入，见 [set]） */
    @Volatile
    var current: String = NONE
        private set

    /** 补充说明：目标名、"为什么没有出口"等，给界面直接用 */
    @Volatile
    var reason: String = ""
        private set

    /**
     * 是否**真的发送过**至少一次输入。
     *
     * 界面必须靠它区分两种情况：① 还没发过东西（[current] 仍是 [NONE]，但出口其实可能是
     * 就绪的 USB HID）→ 显示 [resolve] 的**预计**出口；② 发过并且失败 → 显示真实的无出口。
     * 否则"刚进 App 还没碰屏幕"就会被徽章报成"无出口"，照样误导。
     */
    @Volatile
    var observed: Boolean = false
        private set

    /**
     * 记录一次真实发送所走的出口。
     *
     * 只在**变化时**打日志 + 发事件（避免每帧刷屏）—— 这个方法每帧都会被调用，
     * 必须保持"一次 volatile 读 + 一次比较"的开销。
     */
    fun set(path: String, reason: String = "") {
        observed = true
        if (path == current && reason == this.reason) return
        val changed = path != current
        current = path
        this.reason = reason
        val text = describe(path, reason)
        if (changed) Log.i(TAG, "上行出口：$text") else Log.i(TAG, "上行出口说明：$text")
        EventBus.post(UplinkEvent(path, reason))
    }

    fun label(path: String = current): String = when (path) {
        WIRELESS -> "无线 9511"
        USB -> "USB HID"
        BLUETOOTH -> "蓝牙 HID"
        else -> "无出口"
    }

    /** 给界面的一行文案：出口 + 原因；无出口时明确告警（不要再让用户猜"为什么点不动"） */
    fun hint(): String = describe(current, reason)

    /** 指定出口的一行文案（界面在"尚未发送过输入"时用 [resolve] 的结果显示预计出口） */
    fun describe(path: String, reason: String = ""): String {
        val base = "出口：${label(path)}"
        return when {
            path == NONE -> "$base（输入会被丢弃${if (reason.isNotEmpty()) "：$reason" else ""}）"
            reason.isNotEmpty() -> "$base（$reason）"
            else -> base
        }
    }

    private const val TAG = "Uplink"
}
