package com.allperiph.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.Window
import android.widget.EditText
import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ModuleId
import com.allperiph.core.Uplink
import com.allperiph.hid.HidKeys
import com.allperiph.wireless.ControlTarget

/**
 * 物理键盘 → 电脑 转发桥（蓝牙键盘连手机的场景）。
 *
 * 拓扑：蓝牙键盘（外设）→ 手机 → [com.allperiph] 这套软件 → 电脑。
 * 此前全仓库没有任何 KeyEvent 捕获（dispatchKeyEvent / onKeyDown / IME 全零），
 * 物理按键根本进不了软件，这就是「蓝牙键盘在电脑上打不出字」的根因。
 *
 * 实现：包装 [Window.Callback]（Activity 本身就是原回调，所有其余方法 `by delegate`
 * 原样透传），在 [dispatchKeyEvent] 抢在视图树之前拦截 —— 不改 ApShellActivity
 * （与 TouchpadSplit 同一思路：旋屏不重建 + 96K 大文件改不动就从 Fragment 接线）。
 *
 * 转发规则（按序，任一条不满足就放行给手机本地）：
 *  ① 当前聚焦的是 EditText → 全部放行（手机本地能正常打字，弹窗是独立 window 天然隔离）
 *  ② [Uplink.resolve] 无可用出口 → 放行（没连电脑时蓝牙键盘就是普通手机键盘）
 *  ③ 修饰键（Shift/Ctrl/Alt/Win）→ 消费并自记 heldMods（位图随后续按键发）
 *  ④ 认不出的键（音量/媒体/Back/Home…）→ 放行，手机系统功能不受影响
 *  ⑤ 其余映射 USB HID usage → [HidKeys.combo]（与屏上快捷键同一条链路，
 *     60ms 自动释放；重复事件会续期释放定时器 = 电脑侧自然长按/自动重复）
 *
 * 出口判据不自写：一律 [Uplink.resolve]（与 HidKeys.send / 通知栏 / 界面同口径）。
 */
internal class PhysKeyBridge(private val act: Activity) {

    companion object {
        /** 批S：IME 全局入口（attach 时登记，ApxImeService 复用键盘转发状态与 heldMods） */
        var current: PhysKeyBridge? = null
    }

    private var wrapper: Window.Callback? = null
    private var original: Window.Callback? = null

    /** 当前按住的修饰位（事件被我们消费后，靠 heldMods + metaState 双保险拼位图） */
    private var heldMods = 0

    fun attach(window: Window) {
        current = this
        if (wrapper != null) return
        val orig = window.callback ?: return
        original = orig
        val w = Wrapper(orig)
        wrapper = w
        window.callback = w
    }

    /** 只在窗口回调仍是自己时还原，避免 Fragment 重建后旧桥拆掉新桥的包装 */
    fun detach() {
        val w = wrapper ?: return
        val win = act.window
        val orig = original
        if (win.callback === w && orig != null) win.callback = orig
        wrapper = null
        original = null
        heldMods = 0
    }

    private inner class Wrapper(private val delegate: Window.Callback) : Window.Callback by delegate {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            if (forward(event)) true else delegate.dispatchKeyEvent(event)

        // 反馈#8：物理触摸板也桥接 —— hover=移动、click=按键、滚轮轴=滚动（Q1）
        // 批F：光标锁定（issue#44）——已转发（连上+在管）时隐藏本窗系统光标，放行即恢复
        override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
            val handled = forwardPointer(event)
            val isPointer = event.source and android.view.InputDevice.SOURCE_MOUSE != 0 ||
                event.source and android.view.InputDevice.SOURCE_TOUCHPAD != 0
            if (isPointer) applyCursorLock(handled)
            android.util.Log.d("ApxBridge", "MOTION isPointer=$isPointer handled=$handled src=${event.source} act=${event.actionMasked}")
            return if (handled) true else delegate.dispatchGenericMotionEvent(event)
        }
    }

    /** @return true = 已转发/已消费；false = 放行给手机本地 */
    private fun forward(event: KeyEvent, imeBypass: Boolean = false): Boolean {
        val ret = forwardImpl(event, imeBypass)
        android.util.Log.d("ApxBridge", "KEY kc=${event.keyCode} act=${event.action} rep=${event.repeatCount} usage=${usageOf(event.keyCode)} etFocus=${act.currentFocus is EditText} ret=$ret")
        return ret
    }

    /** 批S：IME 转发入口——焦点在输入框也转发（IME 就是要接管打字）；状态与窗口转发共用 */
    fun forwardFromIme(event: KeyEvent): Boolean = forward(event, imeBypass = true)

    private fun forwardImpl(event: KeyEvent, imeBypass: Boolean): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        // ① 手机本地输入中：修饰键也放行（否则本地 Shift+方向选字会坏）；IME 场景强制转发
        if (!imeBypass && act.currentFocus is EditText) return false
        // ② 没连电脑：键盘继续当手机键盘用
        if (!ready()) return false

        // ③ 修饰键记账（不发单独帧：协议里 mod 必须跟着按键走，空 keys 即全释放）
        val modBit = when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> HidKeys.MOD_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> HidKeys.MOD_CTRL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> HidKeys.MOD_ALT
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> HidKeys.MOD_WIN
            else -> 0
        }
        if (modBit != 0) {
            heldMods = if (action == KeyEvent.ACTION_DOWN) heldMods or modBit else heldMods and modBit.inv()
            return true
        }

        // ④ 认不出的键放行（音量/媒体/Back/Home…留给手机）
        val usage = usageOf(event.keyCode)
        if (usage == 0) return false

        // ⑤ 发送：与屏上快捷键同链路（60ms 自动释放；重复事件续期 = 电脑侧自然长按）
        if (action == KeyEvent.ACTION_DOWN) {
            HidKeys.combo(HidKeys.Combo("", heldMods or metaMod(event.metaState), usage))
        }
        return true
    }

    // ———— 反馈#8：物理触摸板指针状态 ————
    private var lastGX = Float.NaN
    private var lastGY = Float.NaN

    /** 批F：光标锁定（issue#44）——已转发（连上+在管）→ 本窗隐藏系统光标；状态边沿才 setter */
    private var lastLockApplied: Boolean? = null
    private fun applyCursorLock(lock: Boolean) {
        val on = lock && act.getSharedPreferences("apx", android.content.Context.MODE_PRIVATE)
            .getBoolean("cursor_lock", true)
        if (lastLockApplied == on) return
        lastLockApplied = on
        act.window.decorView.pointerIcon =
            if (on) android.view.PointerIcon.getSystemIcon(act, android.view.PointerIcon.TYPE_NULL)
            else null
    }

    /**
     * Q1 触摸板映射：Android 把 BT 指针设备读成系统光标，窗口收到的是
     * **绝对 hover 坐标 + buttonState + 滚轮轴**；这里换算相对位移直通
     * [com.allperiph.touchpad.TouchpadModule.feedPointer]（三出口与手势同链路）。
     * 注：光标离开本窗口（分屏/悬浮球未开）时收不到 hover，回到窗口再续上，
     * 800px 跳变按「重入」处理不转发，避免甩屏误动。
     * @return true = 已消费（系统光标照常移动，App 内不再做本地 hover 响应）
     */
    private fun forwardPointer(event: android.view.MotionEvent): Boolean {
        val ret = forwardPointerImpl(event)
        android.util.Log.d("ApxBridge", "PTR src=${event.source} act=${event.actionMasked} x=${event.x} y=${event.y} v=${event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)} h=${event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)} etFocus=${act.currentFocus is EditText} ret=$ret")
        return ret
    }

    private fun forwardPointerImpl(event: android.view.MotionEvent): Boolean {
        val src = event.source
        val isPointer = src and android.view.InputDevice.SOURCE_MOUSE != 0 ||
            src and android.view.InputDevice.SOURCE_TOUCHPAD != 0
        if (!isPointer) return false
        if (act.currentFocus is EditText) return false   // 本地输入态：触摸板先伺候手机
        if (!ready()) return false                        // 没连电脑：就是普通触摸板/鼠标
        val mod = AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule
            ?: return false
        val x = event.x
        val y = event.y
        var dx = 0f
        var dy = 0f
        if (!lastGX.isNaN()) {
            dx = x - lastGX; dy = y - lastGY
            if (kotlin.math.abs(dx) > 800f || kotlin.math.abs(dy) > 800f) { dx = 0f; dy = 0f } // 重入不甩
        }
        lastGX = x; lastGY = y
        // buttonState 位与 TouchpadFrame 约定天然一致：primary=1 secondary=2 tertiary=4
        val btn = event.buttonState and 0x07
        val v = event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
        val h = event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)
        val moving = dx != 0f || dy != 0f || v != 0f || h != 0f
        val clicked = event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
            event.actionMasked == android.view.MotionEvent.ACTION_UP
        if (!moving && !clicked) return true
        mod.feedPointer(dx, dy, btn, v, h, event.eventTime * 1_000_000L)
        return true
    }

    /** 出口就绪：判据统一走 Uplink.resolve（与 HidKeys.send 完全同口径） */
    private fun ready(): Boolean {
        val rt = AgentController.runtime
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
        return Uplink.resolve(
            rt?.hid,
            btConnected = bt?.isConnected == true,
            wireless = ControlTarget.isControlling() && ControlTarget.controlClient != null,
        ) != Uplink.NONE
    }

    private fun metaMod(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or HidKeys.MOD_CTRL
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or HidKeys.MOD_SHIFT
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or HidKeys.MOD_ALT
        if (meta and KeyEvent.META_META_ON != 0) m = m or HidKeys.MOD_WIN
        return m
    }

    /** Android keyCode → USB HID Keyboard Page (0x07) usage；认不出返回 0（放行本地） */
    private fun usageOf(kc: Int): Int = when (kc) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (kc - KeyEvent.KEYCODE_A)
        KeyEvent.KEYCODE_0 -> 0x27
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (kc - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x28
        KeyEvent.KEYCODE_ESCAPE -> 0x29
        KeyEvent.KEYCODE_DEL -> 0x2A                        // 退格
        KeyEvent.KEYCODE_TAB -> 0x2B
        KeyEvent.KEYCODE_SPACE -> 0x2C
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + (kc - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SYSRQ -> 0x46                      // PrtSc
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47
        KeyEvent.KEYCODE_BREAK -> 0x48
        KeyEvent.KEYCODE_INSERT -> 0x49
        KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C                // Del
        KeyEvent.KEYCODE_MOVE_END -> 0x4D
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
        KeyEvent.KEYCODE_DPAD_UP -> 0x52
        KeyEvent.KEYCODE_DPAD_CENTER -> 0x28                // 方向确认 = Enter
        KeyEvent.KEYCODE_NUM_LOCK -> 0x53
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56
        KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57
        in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> 0x59 + (kc - KeyEvent.KEYCODE_NUMPAD_1)
        KeyEvent.KEYCODE_NUMPAD_0 -> 0x62
        KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63
        else -> 0
    }
}