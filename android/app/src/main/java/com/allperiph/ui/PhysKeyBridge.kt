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
 * 原样透传），在 [dispatchKeyEvent] 抢在视图树之前拦截 —— 不改 MainActivity
 * （与 TouchpadSplit 同一思路：旋屏不重建 + 96K 大文件改不动就从 Fragment 接线）。
 *
 * 转发规则（按序，任一条不满足就放行给手机本地）：
 *  ① 当前聚焦的是 EditText → 全部放行（手机本地能正常打字，弹窗是独立 window 天然隔离）
 *  ② [Uplink.resolve] 无可用出口 → 放行（没连电脑时蓝牙键盘就是普通手机键盘）
 *  ③ 修饰键（Shift/Ctrl/Alt/Win）→ 消费并自记 heldMods（位图随后续按键发）
 *  ④ 认不出的键（音量/媒体/Back/Home…）→ 放行，手机系统功能不受影响
 *  ⑤ 其余映射 USB HID usage → 按下用 [HidKeys.hold] **按住不放**，抬起才 [HidKeys.releaseHold]
 *     （旧实现一律走 [HidKeys.combo]，60ms 后自动全释放；系统的自动重复事件再把这个窗口
 *     续期，于是长按只出首按 + 首个重复两个字符 —— issue #52 第三条）
 *     系统自动重复事件：USB / 蓝牙 HID 的对端是真键盘设备，重复由对端系统自己做，这里忽略；
 *     无线 9511 注入的是合成事件、对端不会重复 —— 由本机按重复节奏补一对 抬起 → 按下。
 *
 * 出口判据不自写：一律 [Uplink.resolve]（与 HidKeys.send / 通知栏 / 界面同口径）。
 */
internal class PhysKeyBridge(private val act: Activity) {

    private var wrapper: Window.Callback? = null
    private var original: Window.Callback? = null

    /** 当前按住的修饰位（事件被我们消费后，靠 heldMods + metaState 双保险拼位图） */
    private var heldMods = 0

    fun attach(window: Window) {
        if (wrapper != null) return
        val orig = window.callback ?: return
        original = orig
        val w = Wrapper(orig)
        wrapper = w
        window.callback = w
    }

    /**
     * 幂等（重）挂载：已在链上就什么都不做；**不在链上就重新挂上去**。
     *
     * 为什么需要它（issue #52 第 6 条「退后台收不到按键」与第 11 条的真机断点）：
     * 转发桥靠**包装 `Window.Callback`** 抢先拦按键，而「窗口回调还是不是自己」这件事
     * 没有任何保证 —— 宿主换壳、`setContentView` 之后有人重设回调、Fragment 重建、
     * 系统回收 Activity 视图，都会把包装悄悄摘掉。原来的 [attach] 只在挂载那一刻判
     * `wrapper != null`，而**外层调用点**（TouchpadFragment.ensureViews 的一次性守卫）
     * 更狠：只要那次调用时 Activity 取不到，就连 attach 都没发生，且此后所有
     * ensureViews() 都会被守卫挡回，桥**永远**不会挂上 —— 真机表现正是
     * 「2477 条按键全到了 MainActivity 的 ViewRootImpl，转发桥一条都没收到」。
     * 所以把「挂没挂」交给本方法自己判断，并在每次界面回到前台时复核一遍。
     *
     * @return true = 本次调用真的（重）挂上了；false = 原本就在链上，无需动作
     */
    fun ensureAttached(window: Window): Boolean {
        val orig = original
        val w = wrapper
        if (w != null && orig != null && window.callback === w) return false
        if (w != null && orig != null && window.callback !== w) {
            // 包装已被别的东西换掉：这里**不还原**（当前回调不是我们，硬塞回 original
            // 会踩掉别人的包装），只重新包一层，把转发重新接回链上。
            android.util.Log.w("ApxBridge", "窗口回调已不是转发桥，重新包装（实体键盘转发自愈）")
        }
        wrapper = null
        original = null
        attach(window)
        return wrapper != null
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

    /**
     * 放掉「按住的键」：清修饰位图 + 立即全释放（幂等）。
     *
     * 与 [GlobalKeyForwarder.resetKeys] 同一目的：转发是**有状态**链路，
     * 按下才发、抬起才放，而抬起并不保证送达（退后台、切应用、系统丢帧都可能丢
     * ACTION_UP）。抬起一旦丢了，电脑侧就一直按着那个 Ctrl / Shift，此后每个字母
     * 都带修饰发出 —— 正是 issue #52 第 11 条「打快了概率性触发系统功能键」。
     * 在界面离开前台时调用，让状态回到确定的零点。
     */
    fun releaseStuckKeys() {
        heldMods = 0
        HidKeys.releaseNow()
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
    private fun forward(event: KeyEvent): Boolean {
        val ret = forwardImpl(event)
        android.util.Log.d("ApxBridge", "KEY kc=${event.keyCode} act=${event.action} rep=${event.repeatCount} usage=${usageOf(event.keyCode)} etFocus=${act.currentFocus is EditText} ret=$ret")
        return ret
    }

    private fun forwardImpl(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        // ① 手机本地输入中：放行（否则本地打字会被转发走，Shift+方向选字也会坏）
        if (act.currentFocus is EditText) return false
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

        // ⑤ 发送
        val combo = HidKeys.Combo("", heldMods or metaMod(event.metaState), usage)
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                // 首次按下：按住不放，直到 ACTION_UP 才释放（长按/按住 Shift 连续选字都对）
                HidKeys.hold(combo)
            } else if (Uplink.current == Uplink.WIRELESS) {
                // 无线：对端收到的是合成注入事件，不会自己重复 —— 每来一次自动重复，
                // 先清一次再按下，造出真实的边沿 = 对端出一个字符。
                HidKeys.releaseNow()
                HidKeys.hold(combo)
            }
            // USB / 蓝牙 HID：对端是真实键盘设备，按住不放它自己就会重复，这里什么都不做
        } else {
            HidKeys.releaseHold()
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
        // root 直读桥接管时让位：那条读的是真正的一手 EV_REL，而本函数拿的是
        // 绝对 hover 坐标、靠相邻帧差求位移。两条同时跑会把位移算两遍
        // （光标跑得比手快一倍），按钮状态也会互相覆盖 —— 见 RootPointerBridge.active。
        if (com.allperiph.touchpad.RootPointerBridge.active) return false
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

    // 键映射与 metaState 换算搬到 [HidKeyMap]（单一真源）—— issue #52 建议 6 加了
    // 第二条转发路径（无障碍 onKeyEvent，无界面也能转发），两处各写一份码表必然
    // 分叉（问题 3「标点键无反应」就是码表漏行导致的）。
    private fun metaMod(meta: Int): Int = HidKeyMap.metaMod(meta)
    private fun usageOf(kc: Int): Int = HidKeyMap.usageOf(kc)
}