package com.allperiph.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import com.allperiph.shared.util.Log
import com.allperiph.touchpad.TouchpadModule
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

        /**
         * 球直径（dp）。窗口尺寸直接用这个值给死，不走 WRAP_CONTENT ——
         * 球底 `bg_floating_ball` 是 layer-list，而 shape 基元**没有** intrinsic size，
         * `WRAP_CONTENT` 会一路塌到子 View（那个 22dp 图标），实测球窗口只有 66px
         * （22dp，@3x），比设计小一半，顺带把扇形的圆心也带偏。
         */
        private const val BALL_DP = 48

        /** 长按关闭的判定时长（ms）。旧值是 400，比系统长按阈值还短，拖动时极易误触 */
        private const val LONG_PRESS_MS = 800L

        /** 扇形里快捷项之间的最小间隙（dp）：球贴边时全靠它撑开相邻两项 */
        private const val FAN_GAP_DP = 10

        /** 扇形张角的搜索下限（°）。再小就挤成一条线，不如让邻项略叠一点 */
        private const val FAN_SPAN_MIN = 10f

        /** 张角搜索步进（°）：从上限往下退，退到能放下为止 */
        private const val FAN_SPAN_STEP = 2.5f

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

    /**
     * 服务侧的换肤上下文（见 [ThemePref.wrapService]）。
     *
     * 菜单／面板／连接模式按钮必须用**它**去 inflate 与取色：`Service` 自身的
     * `getTheme()` 是 framework 的 `Theme.DeviceDefault.Light.DarkActionBar`，
     * 解析不到 `?attr/apPrimary` 一族的属性 —— v223.13 起「单击悬浮球」
     * 就闪退正是栽在这里（InflateException：Failed to resolve attribute）。
     */
    private val themed by lazy { ThemePref.wrapService(this) }

    private var ball: View? = null
    private var ballAttached = false
    private var panel: View? = null

    /** 扇形菜单浮层（v223.15）：单击悬浮球展开的那一圈快捷项 */
    private var menu: View? = null

    /** 展开中的快捷项（id → 那一格 View）：连接模式切换后按它就地刷新选中态 */
    private val fanItems = mutableListOf<Pair<String, View>>()

    /** 已停靠在侧边（半透明、点击恢复）—— 轻拨手势的落点状态 */
    private var docked = false

    /** 停靠判定：抬手时球心距左右边缘小于这个值就吸附过去 */
    private val dockSnapPx get() = dp(48)

    /** 拖动阈值：超过它才算「拖」，滤掉手指按上去时的抖动 */
    private val touchSlop get() = dp(6)

    // 球拖动/点击判定
    private var downRawX = 0f
    private var downRawY = 0f

    /** 按下瞬间的窗口坐标（拖动全程以它为基准，避免与世界坐标混算） */
    private var downWindowX = 0
    private var downWindowY = 0
    private var downAt = 0L
    private var moved = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ————————————————————————— 生命周期 —————————————————————————

    override fun onCreate() {
        super.onCreate()
        // 菜单／面板的 ?attr 强调色由 [themed]（ThemePref.wrapService）负责，
        // 不能直接叠在 Service 自身主题上 —— 那是 framework 默认主题。
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
        val v = ball ?: LayoutInflater.from(themed).inflate(R.layout.view_floating_ball, null).also { ball = it }
        wm.addView(v, ballLayoutParams())
        ballAttached = true
        setupBallTouch(v)
    }

    private fun ballLayoutParams() = WindowManager.LayoutParams(
        // ⚠️ 必须给死尺寸，别用 WRAP_CONTENT：球底是 layer-list，shape 没有 intrinsic
        //    size，wrap_content 会塌到子 View（那个 22dp 图标）→ 实测球只有 66px。
        dp(BALL_DP),
        dp(BALL_DP),
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
            val lp = view.layoutParams as? WindowManager.LayoutParams
            if (lp == null) {
                return@setOnTouchListener false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // ⚠️ 记录「按下瞬间手指相对窗口左上角」而不是窗口的世界坐标：
                    //    overlay 窗口的 x/y 不带状态栏偏移，rawX/rawY 却带 ——
                    //    直接拿 rawY - y 当偏移，球会一路往屏幕外飘。
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWindowX = lp.x
                    downWindowY = lp.y
                    downAt = System.currentTimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    // 先判位移、后动球：阈值内不更新位置，球的视觉与「是拖是点」的判定
                    // 才不会各说各话 —— 旧版动 1px 就挪球、却要 12px 才算拖动，
                    // 于是「按住想挪一下」常常落进长按分支，把服务直接关掉。
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        moveBall(view, downWindowX + dx, downWindowY + dy)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - downAt
                    when {
                        // 拖动结束：落位 + 判定是否轻拨到了侧边（建议 2）
                        moved -> settleAfterDrag(view)
                        // 停靠态单击 = 恢复悬浮球正常显示（建议 2）
                        docked -> undock(view)
                        // 长按关闭：只在「真的没动过」时算数，拖动不背这个锅
                        held >= LONG_PRESS_MS -> shutdown()
                        else -> toggleMenu(view)           // 单击展开/收起扇形菜单
                    }
                }
            }
            true
        }
    }

    /** 把球挪到窗口坐标 (x, y) 并夹进屏内（拖动期间的每一帧都走这里） */
    private fun moveBall(v: View, x: Float, y: Float) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = resources.displayMetrics
        lp.x = x.toInt().coerceIn(0, (dm.widthPixels - v.width).coerceAtLeast(0))
        lp.y = y.toInt().coerceIn(0, (dm.heightPixels - v.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // ————————————————————————— 侧边停靠（建议 2） —————————————————————————

    /**
     * 拖动结束后判定「轻拨」：球心贴近左/右边缘就吸附过去并半透明，减少遮挡；
     * 落在中间就保持普通显示（顺带把 y 夹回屏内，免得出屏幕找不回来）。
     */
    private fun settleAfterDrag(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = resources.displayMetrics
        val cx = lp.x + v.width / 2f
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - v.height).coerceAtLeast(0))

        if (v.width > 0 && cx < dockSnapPx) {
            lp.x = 0
            docked = true
        } else if (v.width > 0 && cx > dm.widthPixels - dockSnapPx) {
            lp.x = (dm.widthPixels - v.width).coerceAtLeast(0)
            docked = true
        } else {
            docked = false
        }
        v.alpha = if (docked) DOCK_ALPHA else 1f
        publishBallPosition(v)
        // 拖动/停靠后菜单与球的相对位置失效，直接收掉，下次点球再按新位置摆
        closeMenu()
    }

    /** 停靠态点击 → 恢复正常显示：回到不透明，并往屏内挪出一个球身 */
    private fun undock(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = resources.displayMetrics
        docked = false
        v.alpha = 1f
        lp.x = if (lp.x <= 0) min(v.width, dm.widthPixels)
               else (dm.widthPixels - v.width * 2).coerceAtLeast(0)
        publishBallPosition(v)
    }

    /**
     * 把球当前的窗口坐标落回 WindowManager。
     *
     * 坐标的唯一真相是 [WindowManager.LayoutParams] 的 x / y —— 旧版把 `View.x` 当
     * 真身（overlay 里它恒为 0），于是拖动「不跟手」、抬手也「不动」，还得靠额外的
     * 回写去补，补不齐就飘。现在只在 lp 上改，这里只是统一收口 + 失败日志。
     */
    private fun publishBallPosition(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "悬浮球站位回写失败：${it.message}") }
    }

    // ————————————————————— 扇形快捷菜单（v223.15，MIUI / HyperOS 快捷球观感） —————————————————————

    /**
     * 单击悬浮球：展开／收起扇形快捷菜单。
     *
     * 为什么是扇形，而不是旧版那一列卡片：
     * - HyperOS 快捷球的交互就是「球 → 周围一圈快捷项」，用户要的是这个观感；
     * - 悬浮球住在 overlay 层，任何矩形卡片都会压住球旁边的应用内容；扇形只在球周围
     *   占一圈，遮挡与视觉重量都最小；
     * - 圈里有哪些项由**用户勾选**决定（[BallConfig]），不再是写死的四颗按钮。
     *
     * ⚠️ 菜单窗口的尺寸 = 那一圈的包围盒，不是整屏：窗口内被子 View 之外的区域由根容器
     * 接住并收起菜单（点空白处即关），窗口外的触摸照常落给下面的应用（FLAG_NOT_TOUCH_MODAL），
     * 所以展开菜单时别处还能滑。
     */
    private fun toggleMenu(v: View) {
        if (menu != null) {
            closeMenu()
            return
        }
        collapse()   // 菜单与触控板面板互斥：面板铺满底部，菜单要占球旁边的位置
        // 兜底：即便主题／资源出问题，也只让菜单展开失败，不能把整个进程带走
        val fan = runCatching { buildFan(v) }.getOrElse {
            Log.w(TAG, "扇形菜单构建失败：${it.message}")
            return
        }
        if (!attachFan(fan)) return
        menu = fan.root
    }

    /** 扇形菜单的「一屏」：包围盒尺寸的根容器 + 它的窗口参数 */
    private class Fan(val root: View, val lp: WindowManager.LayoutParams)

    /**
     * 按 [BallConfig.enabled] 生成扇形：球心为圆心、朝**屏幕内侧**铺开。
     *
     * 上一版真机表现是「图标散着、还叠在一起」，三个错都在这儿纠：
     * 1. **张角不能拍脑袋取 44°×n**：球停在边缘／角落时，朝外那半边根本没空间。
     *    这里改成从上限往下退（[FAN_SPAN_STEP] 一档），退到「每项都完整落在屏内」为止；
     * 2. **圆心角不能硬取「朝屏心」那个角**：球在角上时它并不是可行区间的中点，
     *    弧的一半会甩出屏。改成在 0.5° 粒度上搜遍一周，取**离朝屏心方向最近**的可行角；
     * 3. **半径按弦长定**：相邻两项的弦长（2R·sin(step/2)）必须容得下「图标 + 间隙」，
     *    不能按「球到屏幕边缘的余量」缩 —— 球贴边时后者会把半径压到比图标还小
     *    （实测 102px < 156px），相邻两项必然互相盖住。
     *
     * 窗口取**所有快捷项外扩一圈的矩形**，而不是固定的正方形包围盒：球贴边时正方形
     * 有一角在屏外，被裁的那块虽是空白，却会让「点空白收起菜单」的手感莫名其妙。
     */
    private fun buildFan(ballView: View): Fan {
        val items = BallConfig.enabled(this)
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val itemPx = dp(BallConfig.ITEM_DP)
        val gapPx = dp(FAN_GAP_DP)
        val half = itemPx / 2f
        val designR = dp(BallConfig.ARC_RADIUS_DP).toFloat()

        // ⚠️ 必须取 lp.x / lp.y，不能取 ballView.x / .y —— overlay 窗口里 View.x 恒为 0，
        //    拿它当圆心等于把扇形摆在屏幕左上角，这就是「图标不在一个地方」的直接来源。
        val ballLp = ballView.layoutParams as? WindowManager.LayoutParams
        val cx = (ballLp?.x ?: 0) + ballView.width / 2f
        val cy = (ballLp?.y ?: 0) + ballView.height / 2f
        val n = items.size
        // 朝屏幕中心（球停在角落时自然变成对角方向）
        val dirDeg = Math.toDegrees(
            atan2((screenH / 2f - cy).toDouble(), (screenW / 2f - cx).toDouble()),
        ).toFloat()

        val centres = ArrayList<Pair<Float, Float>>(n)
        if (n <= 1) {
            val a = Math.toRadians(dirDeg.toDouble())
            centres.add(
                (cx + designR * cos(a).toFloat()).coerceIn(half, screenW - half) to
                    (cy + designR * sin(a).toFloat()).coerceIn(half, screenH - half),
            )
        } else {
            var span = min(180f, 44f * (n - 1))
            while (span >= FAN_SPAN_MIN) {
                val step = span / (n - 1)
                // 不重叠所需半径：相邻弦长 2R·sin(step/2) ≥ 图标 + 间隙
                val r = maxOf(
                    designR,
                    (itemPx + gapPx) / (2f * sin(Math.toRadians((step / 2f).toDouble())).toFloat()),
                )
                var best: List<Pair<Float, Float>>? = null
                var bestDev = Float.MAX_VALUE
                var k = 0
                while (k < 720) {                       // 0.5° 粒度扫一周
                    val c = dirDeg - 180f + k * 0.5f
                    val pts = ArrayList<Pair<Float, Float>>(n)
                    var ok = true
                    var i = 0
                    while (i < n) {
                        val a = Math.toRadians((c - span / 2f + step * i).toDouble())
                        val x = cx + r * cos(a).toFloat()
                        val y = cy + r * sin(a).toFloat()
                        if (x < half || x > screenW - half || y < half || y > screenH - half) {
                            ok = false
                            break
                        }
                        pts.add(x to y)
                        i++
                    }
                    if (ok) {
                        var dev = ((c - dirDeg) % 360f + 360f) % 360f
                        if (dev > 180f) dev = 360f - dev
                        if (dev < bestDev) {
                            bestDev = dev
                            best = pts
                        }
                    }
                    k++
                }
                if (best != null) {
                    centres.addAll(best)
                    break
                }
                span -= FAN_SPAN_STEP
            }
            if (centres.isEmpty()) {
                // 极端兜底（球被挤在角落、项又多）：保持最小张角，逐项夹进屏内。
                //   宁可邻项略叠一点，也不摆到看不见的地方 —— 叠一点至少每个都点得到。
                val step = 24f / (n - 1)
                val r = maxOf(
                    designR,
                    (itemPx + gapPx) / (2f * sin(Math.toRadians((step / 2f).toDouble())).toFloat()),
                )
                for (i in 0 until n) {
                    val a = Math.toRadians((dirDeg - 12f + step * i).toDouble())
                    centres.add(
                        (cx + r * cos(a).toFloat()).coerceIn(half, screenW - half) to
                            (cy + r * sin(a).toFloat()).coerceIn(half, screenH - half),
                    )
                }
            }
        }

        // 窗口 = 所有快捷项外扩一圈的矩形，紧贴扇形，不做无谓的遮挡
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((x, y) in centres) {
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        val pad = half + gapPx
        minX -= pad
        minY -= pad
        maxX += pad
        maxY += pad
        val boxW = (maxX - minX).toInt().coerceAtLeast(itemPx)
        val boxH = (maxY - minY).toInt().coerceAtLeast(itemPx)
        val wx = minX.toInt()
        val wy = minY.toInt()

        val root = FrameLayout(themed).apply {
            // 空白处点一下 = 收起菜单（快捷项各吃各的，不会传上来）
            isClickable = true
            setOnClickListener { closeMenu() }
        }
        fanItems.clear()
        centres.forEachIndexed { i, (x, y) ->
            val cell = buildFanItem(items[i])
            val lpItem = FrameLayout.LayoutParams(itemPx, itemPx).apply {
                leftMargin = (x - wx - half).toInt()
                topMargin = (y - wy - half).toInt()
            }
            root.addView(cell, lpItem)
            fanItems.add(items[i] to cell)
        }

        val lp = WindowManager.LayoutParams(
            boxW,
            boxH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = wx
            y = wy
        }
        return Fan(root, lp)
    }

    /** 一颗快捷项：52dp 圆 + 居中单色图标（HyperOS 那边也是只给图标，不给文字） */
    private fun buildFanItem(id: String): View {
        val cell = FrameLayout(themed).apply {
            isClickable = true
            isFocusable = true
            contentDescription = getString(BallConfig.labelRes(id))
            setOnClickListener { onFanItem(id) }
        }
        cell.addView(
            ImageView(themed).apply {
                setImageResource(BallConfig.iconRes(id))
                // 图标是单色遮罩，颜色在这儿定（见 applyFanState）
                setImageTintList(ColorStateList.valueOf(themed.resources.getColor(R.color.ball_text)))
            },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )
        applyFanState(cell, id)
        return cell
    }

    /** 快捷项外观：连接模式类按开关态走主色实底，其余走和球同款半透明白／黑 */
    private fun applyFanState(cell: View, id: String) {
        val kind = BallConfig.transportOf(id)
        val on = kind != null && AgentController.isTransportEnabled(this, kind)
        cell.setBackgroundResource(if (on) R.drawable.bg_ball_item_on else R.drawable.bg_ball_item)
        val tint = if (on) ThemeSkin.onPrimary(themed) else themed.resources.getColor(R.color.ball_text)
        (cell as? FrameLayout)?.getChildAt(0)?.let { (it as? ImageView)?.setImageTintList(ColorStateList.valueOf(tint)) }
    }

    /** 连接模式切完就地刷新那几格，不重建整个扇形（重建会闪一下，且丢按下态） */
    private fun renderFanStates() {
        for ((id, cell) in fanItems) applyFanState(cell, id)
    }

    private fun attachFan(fan: Fan): Boolean {
        val added = runCatching {
            wm.addView(fan.root, fan.lp)
            true
        }.getOrElse {
            Log.w(TAG, "扇形菜单贴窗失败：${it.message}")
            false
        }
        // ⚠️ 置顶必须与 addView 分开兜底：若它失败让整个 attachFan 返回 false，
        //    菜单窗口已经 add 进去了却没人记着 —— 之后既收不掉也再开不了（变成孤儿窗口）。
        if (added) raiseBall()
        return added
    }

    /**
     * 把球窗口抬到最上层。
     *
     * 菜单窗口是「以球心为中心的矩形」，新 add 的窗口又在最上层 —— 不抬球的话，
     * 展开期间球被自己的菜单盖住，既点不到也拖不动（用户报的「移动还会退出」里
     * 就有这一层：以为在拖球，实际点在了菜单上）。
     * 球与外面那圈项在视觉上并不重叠，抬它只是把触摸权还回来。
     *
     * 必须用 removeViewImmediate：普通 removeView 是异步的，窗口记录要等
     * ViewRootImpl 回收完才清，紧接着 addView 同一 View 会撞
     * 「View has already been added to the window manager」，而那时球已经被摘掉了 ——
     * 表现就是球直接消失。
     */
    private fun raiseBall() {
        val v = ball ?: return
        if (!ballAttached) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching {
            wm.removeViewImmediate(v)
            wm.addView(v, lp)
        }.onFailure { Log.w(TAG, "悬浮球置顶失败：${it.message}") }
    }

    private fun closeMenu() {
        detach(menu)
        menu = null
        fanItems.clear()
    }

    /** 快捷项分发：触控板面板 / 设备选择 / 文件互传 / 全屏触控板 / 退出 / 连接模式切换 */
    private fun onFanItem(id: String) {
        when (id) {
            BallConfig.TOUCHPAD -> {
                closeMenu()
                expand()
            }
            BallConfig.DEVICES -> {
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
            BallConfig.FILES -> {
                closeMenu()
                runCatching {
                    startActivity(
                        Intent(this, FilePanelActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BallConfig.CONSOLE -> {
                closeMenu()
                runCatching {
                    startActivity(
                        Intent(this, com.allperiph.touchpad.TouchpadActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BallConfig.QUIT -> {
                closeMenu()
                quitApp()
            }
            else -> BallConfig.transportOf(id)?.let { onMenuTransport(it) }
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
        renderFanStates()
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
        val v = runCatching {
            LayoutInflater.from(themed).inflate(R.layout.view_touchpad_panel, null)
        }.getOrElse {
            Log.w(TAG, "触控板面板加载失败：${it.message}")
            return
        }
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
