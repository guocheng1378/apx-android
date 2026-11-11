package com.allperiph.touchpad

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import com.allperiph.R
import com.allperiph.core.ModuleId
import com.allperiph.core.Uplink
import com.allperiph.shared.util.Log
import com.allperiph.ui.AgentController
import com.allperiph.wireless.ControlTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
 *
 * v223.29.63 补「实测未生效」的第三块（issue #52 二轮反馈）：
 *   **热插拔重试**。旧实现只在开关打开 / 模块启动那一刻试一次 —— 外接设备
 *   要是晚一点才插上（或蓝牙才连上），扫描扑空、开关白白退回窗口通路，
 *   用户只能反复开开关。现在注册 InputManager 设备监听：外接指针设备
 *   插入 / 拔出时自动重扫重试（在后台线程做，su 扫描不能上主线程）。
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

    /** 最近一次启动尝试的用户可读结果（null = 尚未尝试）；设置页 / 悬浮球如实展示 */
    var lastStatus: String? = null
        private set

    /** 热插拔重扫在后台做：su 扫描最坏 3–5 秒，上主线程会卡界面 */
    private val rescanExecutor = Executors.newSingleThreadExecutor()

    /** 起停完成后回主线程通知调用方（设置页弹 toast / 悬浮球回退渲染） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 全审查条 5：热插拔监听里登记过的 deviceId → 是否指针源。
     * removed 事件只给 deviceId（拿不到设备了），据此判断是否值得 su 重扫。
     */
    private val pointerDevices = ConcurrentHashMap<Int, Boolean>()

    private var hotplugRegistered = false
    private var appCtx: Context? = null

    /**
     * 偏好文件出口：`apx_root_pointer` 只由本对象打开。
     *
     * 为什么不许调用方自己 `getSharedPreferences("apx_root_pointer")`：偏好文件名与键名
     * 写错**不会**编译报错，只会静默失效 —— 设置页读的是另一份文件，开关就永远「开了没用」
     * （同一个坑 `GlobalKeyForwarder` 已经踩过一次）。读值统一走 [isEnabled]。
     */
    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isEnabled(store: android.content.SharedPreferences): Boolean =
        store.getBoolean(KEY_ENABLED, false)

    fun loadPref(store: android.content.SharedPreferences) {
        enabled = isEnabled(store)
    }

    /**
     * 设置页开关的唯一写入口：落盘 + 立刻起停；拿不到 root 时 [start] 会如实降级。
     *
     * 全审查条 1：su 探测 / 扫描 / 逐节点独占（每节点最坏 3–5 秒）不再占用调用线程
     * —— 从设置页 / 悬浮球调来时都是主线程，同步等最长 15s+ 必 ANR。起停整体挪到
     * rescanExecutor 串行执行，完成后经主线程回调 [onDone](active)：调用方据此
     * 弹结果提示 / 按失败回弹开关（全审查条 6），不再同步读 active（异步下必然竞态）。
     */
    fun setEnabled(
        ctx: Context,
        store: android.content.SharedPreferences,
        on: Boolean,
        onDone: ((ok: Boolean) -> Unit)? = null,
    ) {
        enabled = on
        store.edit().putBoolean(KEY_ENABLED, on).apply()
        val app = ctx.applicationContext
        rescanExecutor.execute {
            if (on) {
                ensureHotplug(app)
                start()
            } else {
                stop()
            }
            onDone?.let { cb -> mainHandler.post { cb(active) } }
        }
    }

    /** 由设置页 / 模块启动时调用；未开启或没 root 时安全返回（page 上会显示不可用） */
    fun start(appContext: Context? = null) {
        if (!enabled || active) return
        appContext?.let { ensureHotplug(it) }
        val c = appContext ?: appCtx
        val mod = AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule ?: return
        val r = EvdevPointerReader(
            onMove = { dx, dy -> feed(mod, dx.toFloat(), dy.toFloat(), 0f, 0f) },
            onButton = { b -> buttons = b; feed(mod, 0f, 0f, 0f, 0f, force = true) },
            onWheel = { v, h -> feed(mod, 0f, 0f, v.toFloat(), h.toFloat()) },
        )
        if (r.start()) {
            reader = r
            active = true
            // 独占拦截与否在 start() 内已同步见分晓：全部节点拿到 EVIOCGRAB 才算
            // 「拦截转发」；有节点落到只转发就如实说明（安卓侧仍会收到移动）。
            lastStatus = c?.getString(
                if (r.interceptedAll) R.string.ui_rootpointer_status_on
                else R.string.ui_rootpointer_status_forward_only,
                r.startedNodes.joinToString("、"),
            )
            Log.i(TAG, "root 直读接管外接触控板（窗口 hover 通路让位；拦截=${r.interceptedAll}）")
        } else {
            active = false
            lastStatus = when (r.lastReason) {
                EvdevPointerReader.REASON_NO_ROOT ->
                    c?.getString(R.string.ui_rootpointer_status_no_root)
                EvdevPointerReader.REASON_NO_DEVICE ->
                    c?.getString(R.string.ui_rootpointer_status_no_device)
                else -> null
            }
            if (lastStatus == null) {
                Log.w(TAG, "root 直读启动失败（无指针设备或没 root）—— 退回窗口 hover 通路")
            } else {
                Log.w(TAG, "root 直读启动失败：$lastStatus")
            }
        }
    }

    fun stop() {
        reader?.stop()
        reader = null
        active = false
        buttons = 0
        Log.i(TAG, "root 直读已停止")
    }

    /**
     * 外接指针设备插入 / 拔出 → 重扫重试（只在已开启时）。
     * 重扫含 su 子进程，**必须后台线程**；起停都有幂等守卫，重复触发无副作用。
     */
    private fun ensureHotplug(appContext: Context) {
        appCtx = appContext.applicationContext ?: appContext
        if (hotplugRegistered) return
        hotplugRegistered = true
        val im = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        im.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                // 全审查条 5：added 只对指针源（鼠标 / 触控板 / 轨迹球）设备重扫并登记
                // —— 键盘、耳机、手柄等插拔不该触发 3–5 秒的 su 扫描。
                override fun onInputDeviceAdded(deviceId: Int) {
                    val d = im.getInputDevice(deviceId) ?: return
                    val isPtr = (d.sources and (
                        InputDevice.SOURCE_MOUSE or
                            InputDevice.SOURCE_TOUCHPAD or
                            InputDevice.SOURCE_TRACKBALL
                        )) != 0
                    pointerDevices[deviceId] = isPtr
                    if (isPtr) rescan()
                }

                // 全审查条 5：removed 只剩 deviceId，用登记表判断 —— 拔掉的若不是
                // 指针设备（或监听注册前就在的老设备），不值得 su 重扫。
                override fun onInputDeviceRemoved(deviceId: Int) {
                    if (pointerDevices.remove(deviceId) == true) rescan()
                }

                override fun onInputDeviceChanged(deviceId: Int) = Unit
            },
            null,
        )
    }

    private fun rescan() {
        if (!enabled) return
        rescanExecutor.execute {
            if (!enabled) return@execute
            reader?.stop()
            reader = null
            active = false
            buttons = 0
            start()
        }
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
