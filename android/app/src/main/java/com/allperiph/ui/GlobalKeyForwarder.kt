package com.allperiph.ui

import android.view.KeyEvent
import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ModuleId
import com.allperiph.core.Uplink
import com.allperiph.hid.HidKeys
import com.allperiph.shared.util.Log
import com.allperiph.wireless.ControlTarget

/**
 * **无界面**全局键盘转发器（issue #52 建议 6）。
 *
 * 与 [PhysKeyBridge] 的区别：那个挂在某个 Activity 的 `Window.Callback` 上，
 * 只有那个窗口在前台时收得到按键；本类不持有任何 Activity/Context，
 * 由**无障碍服务的 `onKeyEvent`** 喂事件 —— 于是「App 不在前台、没有界面」时
 * 物理键盘也能转发到电脑，这正是报障者的诉求（不用把 app 挂到前台）。
 *
 * 转发规则与 [PhysKeyBridge] 逐条对齐（同一套判据，避免两条路径行为分叉）：
 *  ① 无可用出口 → 放行（没连电脑时键盘就是普通手机键盘）
 *  ② 修饰键（Shift/Ctrl/Alt/Win）→ 消费并自记 heldMods（位图随后续按键发）
 *  ③ 认不出的键（音量/媒体/电源…）→ 放行，系统功能不受影响
 *  ④ 其余映射 USB HID usage → [HidKeys.hold] **按住不放**，抬起才 [HidKeys.releaseHold]；
 *     无线 9511 下对端收到的是合成注入事件、不会自己重复，由本机在每次自动重复时
 *     补一对「释放 → 按下」造出真实边沿；USB / 蓝牙 HID 下对端是真键盘设备，
 *     按住不放它自己就会重复，本机忽略重复事件。
 *     （与 [PhysKeyBridge] ⑤ 逐条对齐 —— 这条路径原先写的是 [HidKeys.combo]，
 *     60ms 自动释放被重复事件反复续期，于是「按下」只发了一次、又始终没有抬起，
 *     而 Windows / macOS 对**注入**按键不做自动重复，无线模式下长按恒只出一个字符。）
 *
 * **隐私边界**（用户已确认）：只转发**物理键盘按键**；不读输入框文本、不看剪贴板、
 * 不落盘；由无障碍服务的开启状态 + 本类的 [enabled] 开关双重把关。
 *
 * ⚠️ 不缓存 Activity：本类可能被系统随时重建的服务调用，任何对界面的引用都会泄漏。
 */
internal object GlobalKeyForwarder {

    private const val TAG = "GlobalKeyFwd"

    /**
     * 总开关。默认 **false**：无障碍服务被系统连接不代表用户同意转发，
     * 必须由设置页显式打开（见 SettingsFragment 的「输入法/无障碍转发」行）。
     * 用 SharedPreferences 持久化，进程重启后由 [loadPref] 恢复。
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 已就「开关未开」这条提示过一次（避免每颗键都刷日志） */
    private var warnedDisabled = false

    private const val PREF = "apx_ime_bridge"
    private const val KEY_ENABLED = "kb_forward_enabled"

    /** 当前按住的修饰位（事件被我们消费后，靠 heldMods + metaState 双保险拼位图） */
    private var heldMods = 0

    /** 从持久化读取开关（App.onCreate 调一次；读不到就是 false） */
    fun loadPref(store: android.content.SharedPreferences) {
        enabled = store.getBoolean(KEY_ENABLED, false)
    }

    /** 设置页切换入口 */
    fun setEnabled(store: android.content.SharedPreferences, on: Boolean) {
        enabled = on
        store.edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) resetKeys()
        Log.i(TAG, "全局键盘转发 enabled=$on")
    }

    /**
     * 复位「按住的键」：清修饰位图，并把仍被按住不放的按键放掉。
     *
     * 为什么必须有它（issue #52 第 11 条「打快了概率性触发系统功能键」的机制之一）：
     * 转发是**有状态**链路 —— 按下才发、抬起才放，而「抬起」并不保证一定送达
     * （无障碍通道被系统回收、按住键时切走应用、系统丢帧都会让 ACTION_UP 丢掉）。
     * 只要抬起丢了，电脑侧就会一直按着那个 Ctrl / Shift：此后用户敲的每一个字母
     * 都带着修饰键发出去，表现正是「输入过快时触发系统快捷键 / 输入错乱」。
     * 所以链路只要中断过，先把残留按键放掉，让状态回到确定的零点。
     */
    fun resetKeys() {
        heldMods = 0
        if (ready()) HidKeys.releaseNow()
    }

    /**
     * 无障碍服务在 [android.accessibilityservice.AccessibilityService.onKeyEvent] 里调用。
     * @return true = 已消费（系统不再往下分发，手机本地不响应这颗键）；
     *         false = 放行（本地正常使用）
     */
    fun forward(event: KeyEvent): Boolean {
        if (!enabled) {
            // 诊断用（只需一次）：能走到这里说明无障碍的按键过滤通道**确实通**，
            // 只是总开关没开。真机日志里「有物理按键、没 GLOBAL kc」时，这一行就能
            // 把「通道没通」和「开关没开」区分开 —— 否则只能靠猜（见 issue #52 第 6 条）。
            if (!warnedDisabled) {
                warnedDisabled = true
                Log.i(TAG, "无障碍已收到物理按键，但全局转发开关未打开（按键已放行给手机）")
            }
            return false
        }
        val ret = forwardImpl(event)
        Log.d("ApxBridge", "GLOBAL kc=${event.keyCode} act=${event.action} ret=$ret")
        return ret
    }

    private fun forwardImpl(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        // ① 没连电脑：键盘继续当手机键盘用
        if (!ready()) return false

        // ② 修饰键记账（不发单独帧：协议里 mod 必须跟着按键走，空 keys 即全释放）
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

        // ③ 认不出的键放行（音量/媒体/电源/返回…留给手机）
        // ③' 用户键映射覆盖：设置页可把某颗键改绑到别的 HID usage（sparse prefs，缺省落回真源）
        val usage = HidKeyOverrides.resolve(event.keyCode, HidKeyMap.usageOf(event.keyCode))
        if (usage == 0) return false

        // ④ 发送：与 PhysKeyBridge ⑤ 逐条对齐 —— 首次按下「按住不放」，抬起才释放；
        //    无线 9511 下对端不会自己重复，每次自动重复补一对「释放 → 按下」造边沿；
        //    USB / 蓝牙 HID 下对端是真键盘设备，重复由对端系统负责，这里忽略。
        //    （此前这里写的是 HidKeys.combo：60ms 自动释放被重复事件续期，
        //     只能出第一个字符，且永远不发抬起 —— issue #52「无线长按只出一个字符」。）
        val combo = HidKeys.Combo("", heldMods or HidKeyMap.metaMod(event.metaState), usage)
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                HidKeys.hold(combo)
            } else if (Uplink.current == Uplink.WIRELESS) {
                HidKeys.releaseNow()
                HidKeys.hold(combo)
            }
        } else {
            HidKeys.releaseHold()
        }
        return true
    }

    /** 出口就绪：判据统一走 [Uplink.resolve]（与 HidKeys.send / PhysKeyBridge 完全同口径） */
    private fun ready(): Boolean {
        val rt = AgentController.runtime
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
        return Uplink.resolve(
            rt?.hid,
            btConnected = bt?.isConnected == true,
            wireless = ControlTarget.isControlling() && ControlTarget.controlClient != null,
        ) != Uplink.NONE
    }
}