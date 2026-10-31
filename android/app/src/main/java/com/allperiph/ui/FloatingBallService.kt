package com.allperiph.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import com.allperiph.shared.util.Log
import com.allperiph.touchpad.TouchpadModule
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球（反馈#7）：独立系统 overlay 触控板窗口。
 *
 * 为什么需要：连接/HID 已能后台保活（AgentForegroundService + KeepAlive），但**输入面**
 * 一直挂在前台 Activity —— HyperOS 小窗失焦时系统不给它派发输入事件（freeform 未聚焦
 * 窗口不收 input），实测小窗后台无法输入、必须展开小窗，Pad 上很影响显示。
 * overlay 窗口与前台/焦点无关，其他 App 照常可点，球/面板照常收触摸。
 *
 * 形态：
 *  - 小球（48dp）：单击展开/收起，拖动移位，长按关闭服务；
 *  - 面板（底部 300dp 半透卡）：整块即触控板，touch 直接喂 [TouchpadModule.feed]
 *    —— 与触控板页完全同一条手势链路（GestureEngine 的 bounds 由首个事件自适应，
 *    不需要配置 surface）；面板外屏幕区域不受影响（FLAG_NOT_TOUCH_MODAL）。
 *
 * 权限：复用已有 SYSTEM_ALERT_WINDOW（副屏同款）；未授权时先跳系统授权页。
 * 入口：设置页「工具」小节 → 悬浮球。
 */
class FloatingBallService : Service() {

    companion object {
        private const val TAG = "FloatingBall"
        private const val NOTIFICATION_ID = 700
        private const val PREFS = "apx_floating_ball"
        private const val KEY_ON = "on"
        const val ACTION_TOGGLE = "com.allperiph.ui.floating_ball.toggle"
        const val ACTION_STOP = "com.allperiph.ui.floating_ball.stop"

        /** 三指手势遮蔽的持有者标识（与主页 / 独立操控面区分开） */
        private const val HOLDER_BALL = "ball"

        /** 停靠态的透明度（建议 2）：够看得见、又不挡下面的内容 */
        private const val DOCK_ALPHA = 0.42f

        /** 服务实际存活状态（设置行显示用，比持久化的开关位更真实） */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 设置页入口：无悬浮窗权限 → 跳系统授权页（HyperOS：设置→隐私保护→显示悬浮窗）；
         * 有权限 → 开/关切换（服务未活 = 开，已活 = 停）。
         */
        fun requestToggle(c: Context) {
            if (!Settings.canDrawOverlays(c)) {
                runCatching {
                    c.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${c.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Log.i(TAG, "悬浮窗未授权，已跳系统授权页")
                return
            }
            val intent = Intent(c, FloatingBallService::class.java).apply {
                action = if (isRunning) ACTION_STOP else ACTION_TOGGLE
            }
            c.startService(intent)
        }
    }

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private var ball: View? = null
    private var ballAttached = false
    private var panel: View? = null

    /** 功能菜单浮层（issue #52 建议 2）：单击悬浮球展开的那一列 */
    private var menu: View? = null

    /** 已停靠在侧边（半透明、点击恢复）—— 轻拨手势的落点状态 */
    private var docked = false

    /** 停靠判定：抬手时球心距左右边缘小于这个值就吸附过去 */
    private val dockSnapPx get() = dp(48)

    // 球拖动/点击判定
    private var dragDX = 0f
    private var dragDY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L
    private var moved = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ————————————————————————— 生命周期 —————————————————————————

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        NotificationChannels.ensure(this)
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, NotificationChannels.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_launcher_app)
                .setContentTitle(getString(R.string.ui_main_label_floating_ball))
                .setContentText(getString(R.string.ui_main_floating_ball_note))
                .setOngoing(true)
                .setContentIntent(
                    PendingIntent.getService(
                        this, 0,
                        Intent(this, FloatingBallService::class.java).apply { action = ACTION_STOP },
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        isRunning = true
        addBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        shutdown()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, false).apply()
        // 服务被杀/关闭时面板可能还开着：遮蔽必须一起还，否则系统三指截屏一直被关着
        com.allperiph.touchpad.GestureShield.release(this, HOLDER_BALL)
        detach(ball)
        detach(panel)
        detach(menu)
        ball = null
        panel = null
        menu = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun detach(v: View?) {
        v?.let { runCatching { wm.removeView(it) } }
    }

    // ————————————————————————— 小球 —————————————————————————

    private fun addBall() {
        if (ballAttached) return
        // issue#44：悬浮球此前用 Service 自带（亮色）上下文 inflate，深色下取色永远是亮的
        val v = ball ?: LayoutInflater.from(ThemePref.wrap(this)).inflate(R.layout.view_floating_ball, null).also { ball = it }
        wm.addView(v, ballLayoutParams())
        ballAttached = true
        setupBallTouch(v)
    }

    private fun ballLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = resources.displayMetrics.widthPixels - dp(64)
        y = resources.displayMetrics.heightPixels / 3
    }

    private fun setupBallTouch(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDX = view.x - ev.rawX
                    dragDY = view.y - ev.rawY
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downAt = System.currentTimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - downRawX) > 12 || abs(ev.rawY - downRawY) > 12) moved = true
                    view.x = ev.rawX + dragDX
                    view.y = ev.rawY + dragDY
                }
                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - downAt
                    when {
                        // 拖动结束：落位 + 判定是否轻拨到了侧边（建议 2）
                        moved -> settleAfterDrag(view)
                        // 停靠态单击 = 恢复悬浮球正常显示（建议 2）
                        docked -> undock(view)
                        held >= 400 -> shutdown()          // 长按关闭悬浮球
                        else -> toggleMenu(view)           // 单击展开/收起功能菜单（建议 2）
                    }
                }
            }
            true
        }
    }

    // ————————————————————————— 侧边停靠（建议 2） —————————————————————————

    /**
     * 拖动结束后判定「轻拨」：球心贴近左/右边缘就吸附过去并半透明，减少遮挡；
     * 落在中间就保持普通显示（顺带把 y 夹回屏内，免得出屏幕找不回来）。
     */
    private fun settleAfterDrag(v: View) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val cx = v.x + v.width / 2f
        v.y = v.y.coerceIn(0f, (screenH - v.height).toFloat().coerceAtLeast(0f))

        if (v.width > 0 && cx < dockSnapPx) {
            v.x = 0f
            docked = true
        } else if (v.width > 0 && cx > screenW - dockSnapPx) {
            v.x = (screenW - v.width).toFloat()
            docked = true
        } else {
            docked = false
        }
        v.alpha = if (docked) DOCK_ALPHA else 1f
        // ⚠️ x/y 是在触摸回调里直接赋的，必须显式回写 WindowManager：拖动的那些帧
        //    靠后续 MOVE 持续吐值掩盖了这个缺口，抬手这一下没有后续帧，不写就不动。
        publishBallPosition(v)
        // 拖动/停靠后菜单与球的相对位置失效，直接收掉，下次点球再按新位置摆
        closeMenu()
    }

    /** 停靠态点击 → 恢复正常显示：回到不透明，并往屏内挪出一个球身 */
    private fun undock(v: View) {
        val screenW = resources.displayMetrics.widthPixels
        docked = false
        v.alpha = 1f
        v.x = if (v.x <= 0f) (v.width.toFloat().coerceAtMost(screenW.toFloat()))
              else (screenW - v.width * 2).toFloat().coerceAtLeast(0f)
        publishBallPosition(v)
    }

    /**
     * 把球当前的 x/y 真正落回 WindowManager。
     *
     * 球窗口的坐标来源是 [WindowManager.LayoutParams] 的 x/y；触摸回调里赋的是
     * `View.x/View.y`（视图内部偏移），只在后续 `updateViewLayout` 时才会被采纳。
     * 拖动过程中每帧都在走，抬手与停靠这两处没有后续帧，所以必须显式写一次。
     */
    private fun publishBallPosition(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = v.x.toInt()
        lp.y = v.y.toInt()
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "悬浮球站位回写失败：${it.message}") }
    }

    // ————————————————————————— 功能菜单（建议 2） —————————————————————————

    /**
     * 单击悬浮球：展开/收起功能菜单。
     *
     * 菜单是**独立 overlay 窗口**（不是把面板撑大）—— 球本身只有 60dp，塞不进这些项；
     * 独立窗口还能让菜单出现在球的哪一侧就贴哪一侧，不会顶到屏幕外。
     */
    private fun toggleMenu(v: View) {
        if (menu != null) {
            closeMenu()
            return
        }
        collapse()   // 菜单与触控板面板互斥：面板铺满底部，菜单要占球旁边的位置
        val m = LayoutInflater.from(ThemePref.wrap(this))
            .inflate(R.layout.view_floating_menu, null)
        bindMenu(m)
        if (!attachMenu(m, v)) return
        menu = m
    }

    /** 把菜单贴到球旁边：球在右半屏 → 菜单摆左边，反之摆右边；纵向与球对齐并夹进屏内 */
    private fun attachMenu(m: View, ballView: View): Boolean {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        return runCatching {
            wm.addView(m, lp)
            // 尺寸要等一次测量：刚 addView 时 width/height 还是 0，算不出贴边的坐标
            m.post {
                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels
                val ballRight = ballView.x + ballView.width
                val rightSide = ballView.x + ballView.width / 2f >= screenW / 2f
                lp.x = if (rightSide) {
                    (ballView.x - m.width - dp(8)).toInt().coerceAtLeast(0)
                } else {
                    (ballRight + dp(8)).toInt()
                        .coerceAtMost((screenW - m.width).coerceAtLeast(0))
                }
                lp.y = ballView.y.toInt()
                    .coerceIn(0, (screenH - m.height).coerceAtLeast(0))
                wm.updateViewLayout(m, lp)
            }
            true
        }.getOrElse {
            Log.w(TAG, "菜单创建失败：${it.message}")
            false
        }
    }

    private fun closeMenu() {
        detach(menu)
        menu = null
    }

    /** 菜单项接线。连接模式三颗按当前开关态渲染，点一下即切（与状态页同一套语义） */
    private fun bindMenu(m: View) {
        m.findViewById<TextView>(R.id.btnMenuTouchpad)?.setOnClickListener {
            closeMenu()
            expand()
        }
        m.findViewById<TextView>(R.id.btnMenuDevices)?.setOnClickListener {
            closeMenu()
            // 「扫描 / 切换设备」直接复用主界面的选择器（MainActivity 已支持带
            // EXTRA_PICK_DEVICE 冷启动即弹）—— 与主页长按触控板的入口同一条路径
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_PICK_DEVICE, true),
                )
            }
        }
        m.findViewById<TextView>(R.id.btnMenuFiles)?.setOnClickListener {
            closeMenu()
            runCatching {
                startActivity(
                    Intent(this, FilePanelActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        m.findViewById<TextView>(R.id.btnMenuQuit)?.setOnClickListener {
            closeMenu()
            quitApp()
        }
        for ((id, kind) in listOf(
            R.id.btnModeWifi to "wifi",
            R.id.btnModeBt to "bt",
            R.id.btnModeUsb to "usb",
        )) {
            m.findViewById<TextView>(id)?.setOnClickListener { onMenuTransport(kind) }
        }
        renderModeButtons(m)
    }

    /** 连接模式三颗按钮的选中态：开 = 主色实底 + onPrimary 字；关 = 中性描边胶囊 */
    private fun renderModeButtons(m: View) {
        for ((id, kind) in listOf(
            R.id.btnModeWifi to "wifi",
            R.id.btnModeBt to "bt",
            R.id.btnModeUsb to "usb",
        )) {
            val btn = m.findViewById<TextView>(id) ?: continue
            val on = AgentController.isTransportEnabled(this, kind)
            btn.setTextColor(getColor(if (on) R.color.md_on_primary else R.color.md_primary))
            btn.setBackgroundResource(
                if (on) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost
            )
        }
    }

    /**
     * 切换连接模式。与状态页 `StatusFragment.onTransportToggle` 同一套语义：
     * 多出口模块只切「该出口」、不动模块启停；单出口模块跟着传输开关启停；
     * 打开任一路就先拉起前台服务保活，全关了就停掉。
     */
    private fun onMenuTransport(kind: String) {
        val next = !AgentController.isTransportEnabled(this, kind)
        AgentController.setTransportEnabled(this, kind, next)
        for (id in AgentController.groupModules(kind)) {
            if (AgentController.groupsOf(id).size > 1) {
                AgentController.setExitEnabled(this, id, kind, next)
                if (next) AgentController.setModuleEnabled(this, id, true)
                continue
            }
            if (!next) {
                val stillNeeded = listOf("wifi", "bt", "usb").any { other ->
                    other != kind &&
                        AgentController.isTransportEnabled(this, other) &&
                        AgentController.groupModules(other).contains(id)
                }
                if (stillNeeded) continue
            }
            AgentController.setModuleEnabled(this, id, next)
        }
        if (next) {
            AgentForegroundService.start(this)
        } else if (!listOf("wifi", "bt", "usb").any {
                AgentController.isTransportEnabled(this, it)
            }
        ) {
            AgentController.stopAll()
            stopService(Intent(this, AgentForegroundService::class.java))
        }
        val label = AgentController.transportLabel(kind)
        Toast.makeText(
            this,
            getString(if (next) R.string.ui_ball_menu_mode_on else R.string.ui_ball_menu_mode_off, label),
            Toast.LENGTH_SHORT,
        ).show()
        menu?.let { renderModeButtons(it) }
    }

    /**
     * 退出：把所有外设服务停掉，最后收悬浮球自己。
     *
     * 顺序不能反 —— 悬浮球是前台服务，先关它进程就可能被回收，后面的清理就跑不完。
     * 与主界面返回键的退出确认同义（那里是 finish + 服务自停，这里是后台直接清）。
     */
    private fun quitApp() {
        runCatching {
            ControlTarget.clear()
            TvDiscovery.stop()
        }
        AgentController.stopAll()
        stopService(Intent(this, AgentForegroundService::class.java))
        Toast.makeText(this, getString(R.string.ui_ball_menu_quit_done), Toast.LENGTH_SHORT).show()
        shutdown()
    }

    // ————————————————————————— 展开面板（底部触控板） —————————————————————————

    private fun expand() {
        if (panel != null) return
        val v = LayoutInflater.from(ThemePref.wrap(this)).inflate(R.layout.view_touchpad_panel, null)
        v.findViewById<TextView>(R.id.tvPanelClose)?.setOnClickListener { collapse() }
        v.findViewById<View>(R.id.touchSurface).setOnTouchListener { _, ev ->
            // 与触控板页同一条链路：feed → GestureEngine → 三出口（USB 鼠标帧/蓝牙/无线）
            (AgentController.module(com.allperiph.core.ModuleId.TOUCHPAD) as? TouchpadModule)?.feed(ev)
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(300),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        closeMenu()   // 菜单与面板互斥
        wm.addView(v, lp)
        panel = v
        // 悬浮球的触控板同样是三指滑动面：展开期间一并遮蔽系统三指截屏，
        // 收起就还（这个面板压在别的应用上，不还的话用户在桌面想三指截屏会按不动）
        com.allperiph.touchpad.GestureShield.acquireWithHint(this, HOLDER_BALL)
        // 展开时收起球（避免叠在面板上）
        ball?.let { if (ballAttached) { detach(it); ballAttached = false } }
    }

    private fun collapse() {
        detach(panel)
        panel = null
        // 收起就走延迟还原（1.2s 内又展开就不用白跑两次 su）
        com.allperiph.touchpad.GestureShield.releaseSoon(this, HOLDER_BALL)
        addBall()
    }
}
