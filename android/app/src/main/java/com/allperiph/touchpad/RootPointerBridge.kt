package com.allperiph.touchpad

import android.content.Context
import com.allperiph.core.ModuleId
import com.allperiph.core.Uplink
import com.allperiph.shared.util.Log
import com.allperiph.ui.AgentController
import com.allperiph.wireless.ControlTarget

/**
 * 外接触控板的 **root 直读桥**（issue #52 建议 6 第二条腿）。
 *
 * 职责：把 [EvdevPointerReader] 读到的相对位移/按键/滚轮喂给
 * `TouchpadModule.feedPointer` —— 与屏上触控板、窗口 hover 通路**同一条出口链路**。
 *
 * 与窗口 hover 通路的取舍（重要）：
 *   - 窗口通路（[com.allperiph.ui.PhysKeyBridge.forwardPointer]）：只在本 App 前台时有效，
 *     且拿到的是**绝对 hover 坐标**，靠相邻帧差求位移，光标重入窗口时会跳变。
 *   - 本桥：一手 EV_REL 相对位移，任何时候都有效（App 不在前台也行）。
 *   两条同时开会**双计**（位移翻倍、按钮状态互相覆盖），所以本桥接管时令窗口通路让位：
 *   [active] 为 true 时 `PhysKeyBridge.forwardPointer` 直接放行，不再自己算位移。
 *
 * 门槛：**需要 root**（/dev/input/event* 只有 root 能读）。因此：
 *   - 默认关闭，设置页显式开启；
 *   - 拿不到 root / 找不到设备时如实返回「不可用」，不假装成功
 *     （issue #52 问题 5 的教训：界面状态必须与实际一致）；
 *   - 与键盘转发各自独立开关（键盘不需要 root，不该被连坐）。
 */
object RootPointerBridge {

    private const val TAG = "RootPointer"
    private const val PREF = "apx_root_pointer"
    private const val KEY_ENABLED = "enabled"

    /** 单例守卫：窗口 hover 通路据此让位，避免位移双计 */
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var enabled: Boolean = false
        private set

    private var reader: EvdevPointerReader? = null

    /**
     * 当前按下的按钮位图（bit0 左 / bit1 右 / bit2 中）。
     * ⚠️ 必须**持久保留**：`feedPointer` 用「buttons == ptrButtons」判「无需发送」，
     * 若每次移动都传 0 会把「按住左键拖动」当成松键 —— 拖动会断在第二帧。
     */
    private var buttons = 0

    fun loadPref(store: android.content.SharedPreferences) {
        enabled = store.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(ctx: Context, store: android.content.SharedPreferences, on: Boolean) {
        enabled = on
        store.edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start() else stop()
    }

    /** 由设置页 / 模块启动时调用；未开启或没 root 时安全返回（page 上会显示不可用） */
    fun start() {
        if (!enabled || active) return
        val mod = AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule ?: return
        val r = EvdevPointerReader(
            onMove = { dx, dy -> feed(mod, dx.toFloat(), dy.toFloat(), 0f, 0f) },
            onButton = { b -> buttons = b; feed(mod, 0f, 0f, 0f, 0f, force = true) },
            onWheel = { v, h -> feed(mod, 0f, 0f, v.toFloat(), h.toFloat()) },
        )
        if (r.start()) {
            reader = r
            active = true
            Log.i(TAG, "root 直读接管外接触控板（窗口 hover 通路让位）")
        } else {
            active = false
            Log.w(TAG, "root 直读启动失败（无指针设备或没 root）—— 退回窗口 hover 通路")
        }
    }

    fun stop() {
        reader?.stop()
        reader = null
        active = false
        buttons = 0
        Log.i(TAG, "root 直读已停止")
    }

    /** 出口就绪判据与其余输入通路同口径（Uplink.resolve） */
    private fun ready(): Boolean {
        val rt = AgentController.runtime
        val bt = AgentController.module(ModuleId.BTHID) as? com.allperiph.bt.BtHidDevice
        return Uplink.resolve(
            rt?.hid,
            btConnected = bt?.isConnected == true,
            wireless = ControlTarget.isControlling() && ControlTarget.controlClient != null,
        ) != Uplink.NONE
    }

    private fun feed(
        mod: TouchpadModule,
        dx: Float,
        dy: Float,
        wheel: Float,
        pan: Float,
        force: Boolean = false,
    ) {
        if (!ready()) return
        // force：按钮状态变化必须送达，哪怕位移为 0 —— 否则「原地按一下左键」会丢
        if (!force && dx == 0f && dy == 0f && wheel == 0f && pan == 0f) return
        mod.feedPointer(dx, dy, buttons, wheel, pan, System.nanoTime())
    }
}