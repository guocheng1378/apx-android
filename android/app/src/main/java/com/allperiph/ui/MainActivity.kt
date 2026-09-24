package com.allperiph.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.ViewFlipper
import kotlin.math.abs
import kotlin.math.min
import com.allperiph.R
import com.allperiph.core.AgentStateEvent
import com.allperiph.core.EventBus
import com.allperiph.core.GadgetStateEvent
import com.allperiph.core.LinkSpeed
import com.allperiph.core.LinkSpeedDegradedEvent
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.hid.HotkeyTemplates

/**
 * 【M6】主控制面（v1.16 三页签底栏 + 横屏键盘，MIUIX / HyperOS 亮色）。
 *
 * 设计目标：手机掏出来就是外设，不再有"进入操控面"的中间页 ——
 *   ① 触控板页：整页手势面（事件兜底到 onTouchEvent 喂手势引擎）+ 常用快捷键条
 *   ② 键盘页  ：[KeyboardPanels] 三套键位（快捷 / 遥控 / 游戏），**只在横屏呈现**
 *   ③ 状态页  ：大号启动开关；**启动后**才展开运行摘要 / 链路诊断 / 延迟预算
 *   ④ 设置页  ：快捷键模板（办公·AI·编程·剪辑·放映）· 功能开关 · 环境诊断 ·
 *              主题方案（跟随系统 / 浅色 / 深色，经 [ThemePref]）· 维护
 *
 * 朝向即操控面：竖屏默认触控板，横屏默认全键盘。底栏只保留「触控板 / 状态 / 设置」
 * 三个页签（键盘页不占底栏位），旋屏时自动切换对应操控面。
 *
 * 所有 IO（su、sysfs、设备节点）都在后台线程，本 Activity 只做事件订阅与视图刷新。
 * 主题实现：覆写 [attachBaseContext] 注入 uiMode，深色取值来自 res/values-night/。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val disposables = ArrayList<EventBus.Disposable>()

    private lateinit var tvOverall: TextView
    private lateinit var tvOverallSub: TextView
    private lateinit var swWifi: Switch
    private lateinit var swBt: Switch
    private lateinit var swUsb: Switch
    private lateinit var rowsLink: LinearLayout
    private lateinit var tvLinkRtt: TextView
    private lateinit var badgeStatus: TextView
    private lateinit var groupWifi: LinearLayout
    private lateinit var groupBt: LinearLayout
    private lateinit var groupUsb: LinearLayout
    private lateinit var rowsWifi: LinearLayout
    private lateinit var rowsBt: LinearLayout
    private lateinit var rowsUsb: LinearLayout
    private lateinit var tvDiag: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnRefresh: Button

    // 启动后才显示的区域（状态页）
    private lateinit var boxRunning: LinearLayout
    private lateinit var tvRunningSummary: TextView
    private lateinit var tvIdleHint: TextView
    private lateinit var boxIdleFeatures: LinearLayout

    // 触控板页：手势提示 + 常用快捷键条
    private lateinit var touchHint: TextView
    private lateinit var chipRow: LinearLayout

    // 键盘页：三套键位面板
    private lateinit var keyboardPanel: KeyboardPanels

    // 设置页：模板 / 主题
    private lateinit var templatesBox: LinearLayout
    private lateinit var tvTemplateCurrent: TextView
    private lateinit var btnToggleTemplates: TextView
    private lateinit var themeBox: LinearLayout
    private lateinit var skinBox: LinearLayout
    private lateinit var fxBox: LinearLayout

    // 页签
    private lateinit var pager: ViewFlipper
    private lateinit var tvPageTitle: TextView
    private lateinit var tvPageSub: TextView
    private val tabViews = ArrayList<LinearLayout>()
    private val tabIcons = ArrayList<ImageView>()
    private val tabLabels = ArrayList<TextView>()
    private val tabPills = ArrayList<LinearLayout>()  // 选中态的圆角胶囊底

    // MIUIX 液态玻璃底栏：玻璃只做在选中胶囊上（见 NavBarDrawable）
    private lateinit var navGlass: NavBarDrawable
    private var indicatorAnimator: ValueAnimator? = null

    // 触控板液态光标
    private lateinit var touchArea: View
    private lateinit var touchCursor: TouchCursorView

    // 横屏沉浸模式：整条 chrome 隐藏，切页改用四指左右滑
    private lateinit var headerBar: View
    private lateinit var tabBarView: View
    private lateinit var pageTouchpadView: View
    private lateinit var pageKeyboardView: View
    private var landscape = false

    // 四指左右滑切页（横屏唯一的换页方式）
    private var fourFingerStartX = -1f
    private var fourFingerSwiped = false

    private lateinit var linkRows: StatusRows
    private val rowHandles = LinkedHashMap<String, StatusRows.Row>()
    private val moduleRows = LinkedHashMap<String, ModuleRow>()

    /** 触控板页的快捷键（HotkeyStore 的内存副本，编辑后回写） */
    // 快捷键网格：行为全在 ui/HotkeyBoard（主页与操控面共用一份实现）
    private lateinit var hotkeyBoard: HotkeyBoard

    private var frameCount = 0L
    private var lastShown = 0L

    private class ModuleRow(
        val root: View,
        val name: TextView,
        val detail: TextView,
        val dot: ImageView,
        val sw: Switch,
    )

    @Volatile
    private var lastEnv: EnvChecks.Env? = null

    // 长按拖动：DOWN 后 550ms 触发 armDrag（手指静止无 MOVE 也生效）
    private val dragTask = Runnable {
        (AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule)?.armDrag()
    }

    // 必须显式声明 Runnable 类型：在初始化表达式中引用自身会让 Kotlin 类型推断递归。
    private val ticker: Runnable = Runnable { refresh(); handler.postDelayed(ticker, TICK_MS) }

    /** 主题方案：把用户选择（跟随系统 / 浅色 / 深色）注入资源配置 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 防截屏 / 防录屏：内容不进截图，也不出现在最近任务的缩略图里。
        // 静默生效 —— 界面上不加任何"已开启防护"提示。
        // （用户主动按截图键时系统自己会提示"无法截图"，那是截图服务的行为，应用侧关不掉。）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        bindViews()
        buildLinkRows()
        buildModuleRows()
        buildTabs()
        bindActions()
        buildSettingsPanels()
        ensureNotificationPermission()
        subscribe()
        // 旋屏会重建 Activity（标准做法，用于切换 layout-land），恢复重建前的页签
        savedInstanceState?.getInt(KEY_PAGE)?.let { if (it in PAGE_TITLES.indices) showPage(it) }
        AgentController.refreshEnv(this) { env ->
            lastEnv = env
            renderEnv(env)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::pager.isInitialized) outState.putInt(KEY_PAGE, pager.displayedChild)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        // 物理朝向检测启动：竖持=触控板 横持=键盘（无视系统方向锁）
        setupOrientListener()
        orientListener?.enable()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        orientListener?.disable()
        super.onPause()
    }

    override fun onDestroy() {
        for (d in disposables) d.dispose()
        disposables.clear()
        super.onDestroy()
    }

    // ————————————————————————— 返回键 —————————————————————————

    /** 退出确认框（连按返回时不叠出多个） */
    private var backDialog: AlertDialog? = null

    /**
     * 返回键先确认再退出：外设服务在后台跑着，误触退出容易让人以为"断了"。
     * 覆盖 onBackPressed 而不是 OnBackPressedDispatcher —— 本 Activity 是原生 Activity，
     * 没有 androidx.activity 依赖。
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (backDialog?.isShowing == true) return
        val running = AgentForegroundService.running || AgentController.running
        backDialog = AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("退出全能外设？")
            .setMessage(
                if (running) "外设服务仍在后台运行，退出界面不会停止它（可在通知栏停止）。"
                else "确定退出应用？"
            )
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .create()
            .also { it.show() }
    }

    // ————————————————————————— 视图 —————————————————————————

    private fun bindViews() {
        badgeStatus = findViewById(R.id.badgeStatus)

        tvOverall = findViewById(R.id.tvOverall)
        tvOverallSub = findViewById(R.id.tvOverallSub)
        swWifi = findViewById(R.id.swWifi)
        swBt = findViewById(R.id.swBt)
        swUsb = findViewById(R.id.swUsb)
        rowsLink = findViewById(R.id.rowsLink)
        tvLinkRtt = findViewById(R.id.tvLinkRtt)
        groupWifi = findViewById(R.id.groupWifi)
        groupBt = findViewById(R.id.groupBt)
        groupUsb = findViewById(R.id.groupUsb)
        rowsWifi = findViewById(R.id.rowsWifi)
        rowsBt = findViewById(R.id.rowsBt)
        rowsUsb = findViewById(R.id.rowsUsb)
        tvDiag = findViewById(R.id.tvDiag)
        btnBattery = findViewById(R.id.btnBattery)
        btnRefresh = findViewById(R.id.btnRefresh)

        boxRunning = findViewById(R.id.boxRunning)
        tvRunningSummary = findViewById(R.id.tvRunningSummary)
        tvIdleHint = findViewById(R.id.tvIdleHint)
        boxIdleFeatures = findViewById(R.id.boxIdleFeatures)

        touchHint = findViewById(R.id.tvTouchHint)
        chipRow = findViewById(R.id.chipRow)
        // 快捷键网格：皮肤随模板色变化，变化后同步设置页那个自动排序开关
        hotkeyBoard = HotkeyBoard(this, chipRow, { boardStyle() }) { syncSortSwitch() }

        templatesBox = findViewById(R.id.templatesBox)
        tvTemplateCurrent = findViewById(R.id.tvTemplateCurrent)
        btnToggleTemplates = findViewById(R.id.btnToggleTemplates)
        themeBox = findViewById(R.id.themeBox)
        skinBox = findViewById(R.id.skinBox)
        fxBox = findViewById(R.id.fxBox)
        // 模板列表默认折叠（展开 11 行会占满整屏）；点整行或右侧按钮都能展开
        findViewById<View>(R.id.rowTemplateCurrent).setOnClickListener { toggleTemplates() }
        btnToggleTemplates.setOnClickListener { toggleTemplates() }

        keyboardPanel = KeyboardPanels(this)
        findViewById<LinearLayout>(R.id.keyboardBox).addView(
            keyboardPanel.build(), LinearLayout.LayoutParams(-1, -1)
        )

        pager = findViewById(R.id.pager)
        tvPageTitle = findViewById(R.id.tvPageTitle)
        tvPageSub = findViewById(R.id.tvPageSub)

        headerBar = findViewById(R.id.headerBar)
        tabBarView = findViewById(R.id.tabBar)
        pageTouchpadView = findViewById(R.id.pageTouchpad)
        pageKeyboardView = findViewById(R.id.pageKeyboard)

        // 触控板液态光标：叠在手势面上（不可点击，不抢手势事件）
        touchArea = findViewById(R.id.touchArea)
        touchCursor = TouchCursorView(this)
        (touchArea as FrameLayout).addView(touchCursor, FrameLayout.LayoutParams(-1, -1))

        // 切页动效：淡入上浮（液体流入 / 流出）
        pager.setInAnimation(this, R.anim.liquid_in)
        pager.setOutAnimation(this, R.anim.liquid_out)

        setupGlass()
        applyBackdrop() // 背景：预设底色 / 相册图
    }

    // ————————————————————————— 横竖屏 —————————————————————————

    /**
     * 横屏 = 沉浸操控面：**去掉标题栏与悬浮底栏**，整屏留给触控板 / 全键盘，
     * 只保留右下角一颗半透明切换点（否则没有页签就没法换页）。
     * 竖屏恢复完整 chrome（标题 + 玻璃底栏）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
        handler.post { snapIndicator() }
    }

    private fun applyOrientationLayout() {
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dm = resources.displayMetrics
        Log.i(
            TAG,
            "applyOrientationLayout: landscape=$landscape " +
                "config=${resources.configuration.orientation} ${dm.widthPixels}x${dm.heightPixels}"
        )

        // 结构与间距差异全部交给 res/layout-land/；
        // 但 include 标签的 android:visibility 会被 LayoutInflater 忽略（只认 layout_* 和 id），
        // 所以顶栏/底栏的显隐只能在这里设。
        headerBar.visibility = if (landscape) View.GONE else View.VISIBLE
        tabBarView.visibility = if (landscape) View.GONE else View.VISIBLE

        // 竖屏布局里 pageKeyboard / pageTouchpad 的 paddingBottom（104dp / 90dp）是给悬浮
        // 底栏留位的；横屏底栏隐藏、且旋屏不重建（configChanges 含 orientation → layout-land
        // 不生效），所以这里必须手动把底部留白去掉，否则横屏键盘下方会顶出一大片白
        // （真机复现：键盘内容只到约 2/3 屏高，下面 300px 空白）。
        if (::pageKeyboardView.isInitialized) {
            pageKeyboardView.setPadding(
                pageKeyboardView.paddingLeft, pageKeyboardView.paddingTop,
                pageKeyboardView.paddingRight, if (landscape) dp(2) else dp(104),
            )
        }
        if (::pageTouchpadView.isInitialized) {
            pageTouchpadView.setPadding(
                pageTouchpadView.paddingLeft, pageTouchpadView.paddingTop,
                pageTouchpadView.paddingRight, if (landscape) dp(16) else dp(90),
            )
        }

        // 朝向即操控面：竖屏=触控板，横屏=全键盘（键盘页已移出底栏，横屏自动呈现）。
        // 旋屏时 Activity 不重建（configChanges 含 orientation），这里是唯一的切页时机。
        if (::pager.isInitialized) {
            val target = if (landscape) PAGE_KEYBOARD else PAGE_TOUCHPAD
            if (pager.displayedChild != target) showPage(target)
        }

        if (landscape) navGlass.setIndicator(null)
        applyImmersive()
        applyNavBarWidth()
    }

    /**
     * 沉浸：横屏把**通知栏也盖掉**（整屏都是操控面）；
     * 竖屏保留状态栏（时间/电量有用），但盖掉底部导航手势条 —— 就是那条"白色横"。
     * 两种情况都留 BY_SWIPE：临时下拉/上滑能把系统栏唤出来，不会把自己锁死。
     */
    private fun applyImmersive() {
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape) {
            c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            c.show(WindowInsets.Type.statusBars())
            c.hide(WindowInsets.Type.navigationBars())
        }
    }

    /**
     * 四指左右滑切页（横屏唯一的换页方式，避开触控板自身的一~三指手势）。
     * 走 [dispatchTouchEvent] 而不是 onTouchEvent：键盘页按键会吃掉事件序列，
     * 只有分发的第一站能稳定看到四指。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (landscape && ::pager.isInitialized) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fourFingerStartX = -1f
                    fourFingerSwiped = false
                }
                MotionEvent.ACTION_POINTER_DOWN ->
                    if (ev.pointerCount >= FINGERS_TO_SWITCH && fourFingerStartX < 0f) {
                        fourFingerStartX = ev.x
                        fourFingerSwiped = false
                    }
                MotionEvent.ACTION_MOVE ->
                    if (fourFingerStartX >= 0f && !fourFingerSwiped &&
                        ev.pointerCount >= FINGERS_TO_SWITCH
                    ) {
                        val dx = ev.x - fourFingerStartX
                        if (abs(dx) > dp(56)) {
                            fourFingerSwiped = true
                            val next = if (dx < 0) PAGE_KEYBOARD else PAGE_TOUCHPAD
                            if (next != pager.displayedChild) {
                                cancelChildGestures(ev)
                                showPage(next)
                            }
                            return true
                        }
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fourFingerStartX = -1f
                    fourFingerSwiped = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 切页时给子视图补一个 CANCEL，避免键帽/页签停在"按下"高亮态 */
    private fun cancelChildGestures(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun applyNavBarWidth() {
        val lp = tabBarView.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = min(resources.displayMetrics.widthPixels - dp(32), dp(430))
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        tabBarView.layoutParams = lp
    }



    // ————————————————————————— 液态玻璃底栏 —————————————————————————

    /**
     * 底栏**不铺任何底板**：玻璃特效只做在选中胶囊上（见 [NavBarDrawable]），
     * 半透明白 + 顶部高光 + 极细亮边，切页时胶囊在几个页签之间流动。
     */
    private fun setupGlass() {
        navGlass = NavBarDrawable(this)
        tabBarView.background = navGlass
        tabBarView.elevation = 0f // 无底板 → 不要容器投影
        applyOrientationLayout() // 竖屏/横屏两套 chrome（横屏为沉浸操控面）
        findViewById<View>(R.id.contentStack).post { snapIndicator() }
        // 朝向兜底：个别 ROM（MIUI 某些省电/分屏场景）旋屏不派发 onConfigurationChanged，
        // 导致「横屏键盘没了」。每秒比对一次实际朝向，不一致就补切（幂等，代价可忽略）。
        handler.postDelayed(orientPoll, 1000)
    }

    private val orientPoll = object : Runnable {
        override fun run() {
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (land != landscape) {
                Log.i(TAG, "朝向兜底触发：config=$land vs applied=$landscape")
                applyOrientationLayout()
            }
            handler.postDelayed(this, 1000)
        }
    }

    // ——————————————————— 物理朝向检测（不依赖系统方向锁） ———————————————————

    /**
     * 拿机方向 = 操控面：**竖持=触控板、横持=键盘**，与系统的方向锁定完全无关。
     *
     * 原理：加速度计判断物理倾角 → 横持时 [Activity#setRequestedOrientation]
     * **由 App 主动请求横屏**（视频 App 的横屏按钮同原理，系统方向锁拦不住 App 自己的请求），
     * configuration 随之变化 → [applyOrientationLayout] 自动切键盘；竖持同理切回触控板。
     *
     * 细节：
     *  - 只在触控板/键盘两页之间自动切换，用户停在状态/设置页时不抢方向控制权；
     *  - 带滞回与 600ms 节流：斜角 45° 附近抖动不会来回跳；
     *  - [orientListener] 在 onResume/onPause 启停，后台不耗电。
     */
    private var orientListener: OrientationEventListener? = null
    private var forcedOrient = 0          // 0=尚未强制 1=已强制横屏 2=已强制竖屏
    private var lastOrientSwitchMs = 0L

    private fun setupOrientListener() {
        if (orientListener != null) return
        orientListener = object : OrientationEventListener(this, android.hardware.SensorManager.SENSOR_DELAY_GAME) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                if (!::pager.isInitialized) return
                // 只自动切换两个操控面；状态/设置页保持用户当前的方向
                val cur = pager.displayedChild
                if (cur != PAGE_TOUCHPAD && cur != PAGE_KEYBOARD) return
                // 滞回：明确横（60°..120° 或 240°..300°）才判横，明确竖（<15° 或 >345°）才判竖，
                // 中间斜角保持现状，避免边界抖动
                val want = when (deg) {
                    in 60..120, in 240..300 -> 1
                    in 0..15, in 345..359 -> 2
                    else -> return
                }
                if (want == forcedOrient) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastOrientSwitchMs < 1500) return   // 节流：旋转动画 ~300ms，切太快系统来不及执行
                lastOrientSwitchMs = now
                forcedOrient = want
                // 横持：App 请求横屏（视频 App 同原理，系统方向锁拦不住）；
                // 竖持：**交还系统**（UNSPECIFIED）而不是请求竖屏 ——
                // 实测 MIUI 在收到 PORTRAIT 请求时会关掉自动旋转并写死 user_rotation=0，
                // 导致之后横持请求被忽略（「键盘没了」的元凶）。交还则不会。
                requestedOrientation = if (want == 1)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                Log.i(TAG, "物理朝向切换：force=$want deg=$deg")
            }
        }
    }

    /** 底部页签：绑定点击与选中态（胶囊底 + 图标 / 文字同步染色） */
    private fun buildTabs() {
        // 键盘页不占底栏位：横屏沉浸模式自动呈现（底栏 3 页签：触控板 / 状态 / 设置）。
        // 朝向切换由 [applyOrientationLayout] + orientPoll 兜底轮询保证。
        listOf(
            intArrayOf(R.id.tabTouchpad, R.id.ivTabTouchpad, R.id.tvTabTouchpad, R.id.tabPillTouchpad),
            intArrayOf(R.id.tabStatus, R.id.ivTabStatus, R.id.tvTabStatus, R.id.tabPillStatus),
            intArrayOf(R.id.tabSettings, R.id.ivTabSettings, R.id.tvTabSettings, R.id.tabPillSettings),
        ).forEachIndexed { tabIndex, ids ->
            val page = TAB_PAGES[tabIndex]
            val tab = findViewById<LinearLayout>(ids[0])
            tab.setOnClickListener { showPage(page) }
            // 液态玻璃按压：落点处扩散出一圈玻璃波前 + 整体微缩，松手带 overshoot 弹回。
            // 震动与触摸音效由 [Feedback] 统一发出，和键盘键帽同一套手感。
            tab.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().cancel()
                        v.animate().scaleX(0.92f).scaleY(0.92f)
                            .setDuration(ThemeSkin.motionMs(this@MainActivity, 140L)).start()
                        Feedback.tap(v)
                        playGlassRipple(v, tabPills[tabIndex], e.x, e.y)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().cancel()
                        v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(ThemeSkin.motionMs(this@MainActivity, 420L))
                            .setInterpolator(OvershootInterpolator(2.2f)).start()
                    }
                }
                false // 交回给 click
            }
            tabViews += tab
            tabIcons += findViewById<ImageView>(ids[1])
            tabLabels += findViewById<TextView>(ids[2])
            tabPills += findViewById<LinearLayout>(ids[3])
        }
        // 初始页跟随朝向：竖屏=触控板，横屏=键盘（与 applyOrientationLayout 保持一致）
        showPage(if (landscape) PAGE_KEYBOARD else PAGE_TOUCHPAD)
    }

    /**
     * 玻璃扩散：从按下的**落点**向外铺开一圈波前（中心柔光 + 前缘亮环），慢慢变淡后自己收尾。
     * 落点坐标要从页签容器换算到内层胶囊（两者原点不同），否则光晕会长在页签外。
     */
    private fun playGlassRipple(tab: View, pill: View, x: Float, y: Float) {
        if (pill.width <= 0 || pill.height <= 0) return
        val tl = IntArray(2)
        val pl = IntArray(2)
        tab.getLocationInWindow(tl)
        pill.getLocationInWindow(pl)
        val maxR = maxOf(pill.width, pill.height) * 1.05f
        val dur = ThemeSkin.motionMs(this, 620L)
        if (dur <= 0L) return // 动效关闭：只留震动 / 音效
        val ripple = GlassRipple(
            x + tl[0] - pl[0],
            y + tl[1] - pl[1],
            resources.getColor(R.color.nav_pill_shine),
            resources.getColor(R.color.md_primary),
            maxR,
            dur,
        ).apply {
            onFinish = { if (pill.foreground === this) pill.foreground = null }
        }
        pill.foreground = ripple
        ripple.play()
    }

    /** 切页：标题栏、页签染色与选中胶囊同步（内容页各自独立滚动） */
    private fun showPage(i: Int) {
        if (!::pager.isInitialized) return
        pager.displayedChild = i
        tvPageTitle.text = PAGE_TITLES[i]
        tvPageSub.text = PAGE_SUBS[i]
        if (i == PAGE_TOUCHPAD) renderChips()
        tabViews.indices.forEach { k ->
            val on = TAB_PAGES[k] == i
            val color = resources.getColor(if (on) R.color.md_primary else R.color.state_idle)
            tabIcons[k].setColorFilter(color)
            tabLabels[k].setTextColor(color)
            tabLabels[k].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            tabPills[k].background = null // 选中底由底栏 Drawable 里的滑动胶囊承担
        }
        // 横屏沉浸模式：没有底栏，靠四指左右滑换页
        if (landscape) return
        // 新页布局完成后再滑动胶囊（否则取到的还是上一页的坐标）
        findViewById<View>(R.id.contentStack).post { animateIndicator() }
    }

    /** 选中胶囊在页签之间滑动（位移 + 宽度一起插值，像液体被推着走） */
    private fun animateIndicator() {
        val bar = tabBarView
        if (bar.visibility != View.VISIBLE || bar.width <= 0) {
            navGlass.setIndicator(null)
            return
        }
        val tabIndex = TAB_PAGES.indexOf(pager.displayedChild)
        if (tabIndex < 0) return
        val pill = tabPills[tabIndex]
        if (pill.width <= 0) return
        val bl = IntArray(2)
        val pl = IntArray(2)
        bar.getLocationInWindow(bl)
        pill.getLocationInWindow(pl)
        val target = RectF(
            (pl[0] - bl[0]).toFloat(),
            (pl[1] - bl[1]).toFloat(),
            (pl[0] - bl[0] + pill.width).toFloat(),
            (pl[1] - bl[1] + pill.height).toFloat(),
        )
        val from = navGlass.indicatorRect() ?: target
        indicatorAnimator?.cancel()
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ThemeSkin.motionMs(this@MainActivity, 560L)
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener { a ->
                val t = a.animatedFraction
                // 液态：中心沿路径平移（缓出），宽度在中段**膨胀**一下再收回
                // —— 玻璃被推着走时会先"胖"一圈，落定后恢复目标尺寸。
                val ease = 1f - (1f - t) * (1f - t)
                val cx = from.centerX() + (target.centerX() - from.centerX()) * ease
                val cy = from.centerY() + (target.centerY() - from.centerY()) * ease
                val bulge = 1f + 0.20f * kotlin.math.sin(Math.PI.toFloat() * t)
                val w = (from.width() + (target.width() - from.width()) * t) * bulge
                val h = from.height() + (target.height() - from.height()) * t
                navGlass.setIndicator(RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f))
            }
            start()
        }
    }

    /** 立即归位（横竖屏切换等无动画场景） */
    private fun snapIndicator() {
        indicatorAnimator?.cancel()
        navGlass.setIndicator(null)
        val bar = findViewById<View>(R.id.tabBar)
        val pill = tabPills.getOrNull(TAB_PAGES.indexOf(pager.displayedChild)) ?: return
        val bl = IntArray(2)
        val pl = IntArray(2)
        bar.getLocationInWindow(bl)
        pill.getLocationInWindow(pl)
        navGlass.setIndicator(
            RectF(
                (pl[0] - bl[0]).toFloat(),
                (pl[1] - bl[1]).toFloat(),
                (pl[0] - bl[0] + pill.width).toFloat(),
                (pl[1] - bl[1] + pill.height).toFloat(),
            )
        )
    }

    // ————————————————————————— 触控板页 —————————————————————————

    /**
     * 手势兜底：只有触控板页、且事件未被快捷键条等子视图消费时才喂手势引擎。
     * 整页手势面（含空白区）都能滑动控制光标。
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!::pager.isInitialized || pager.displayedChild != PAGE_TOUCHPAD) {
            return super.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> handler.postDelayed(dragTask, 550)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(dragTask)
        }
        (AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule)?.feed(ev)
        // 液态光标：把窗口坐标换算到手势面坐标后喂给光标视图
        if (::touchCursor.isInitialized && ::touchArea.isInitialized) {
            val loc = IntArray(2)
            touchArea.getLocationInWindow(loc)
            touchCursor.onGesture(ev, loc[0].toFloat(), loc[1].toFloat())
        }
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastShown > 250) { // 4Hz 刷新，避免 UI 抖动
            lastShown = now
            if (frameCount <= 3) touchHint.visibility = View.INVISIBLE // 手指落下后收起提示文字
        }
        return true
    }

    // ————————————————————————— 快捷键网格（实现见 ui/HotkeyBoard） —————————————————————————

    /**
     * 主页版皮肤：颜色跟当前模板的主题色（深色下自动提亮）。
     * 触控板页横竖屏共用竖屏模板：每行 4 个、不固定行数（横屏触控板已不做左右分栏）。
     */
    private fun boardStyle(): HotkeyBoard.Style {
        // 快捷键配色优先用用户选的皮肤；没选则沿用当前模板的主题色
        val tint = if (ThemeSkin.picked(this, ThemeSkin.HOTKEY).isNotBlank()) {
            ThemeSkin.current(this, ThemeSkin.HOTKEY).accent
        } else {
            HotkeyTemplates.tint(this, templateColor())
        }
        return HotkeyBoard.Style(
            chipText = tint,
            chipBg = softTint(tint),
            chipStroke = (tint and 0x00FFFFFF) or (0x40 shl 24),
            chipRadiusDp = 14,
            padVDp = 11,
            perRow = 4,
            fixedRows = 0,
            stretchRows = false,
            accent = resources.getColor(R.color.md_primary),
            textPrimary = resources.getColor(R.color.md_on_surface),
            textSecondary = resources.getColor(R.color.md_on_surface_variant),
        )
    }

    private fun renderChips() = hotkeyBoard.render()

    /** 设置页的自动排序开关跟随真实状态（拖拽/上下移会把它关掉，开关要跟着变） */
    private fun syncSortSwitch() {
        val sw = findViewById<Switch>(R.id.swHotkeySort) ?: return
        val on = HotkeyStore.isAutoSort(this)
        if (sw.isChecked != on) sw.isChecked = on // 值有变才赋值，listener 不会无限递归
    }

    /** 当前生效模板的主题色；逐条改过（自定义）时回落到主色 */
    private fun templateColor(): Int =
        HotkeyTemplates.byKey(HotkeyStore.loadTemplate(this))?.color
            ?: resources.getColor(R.color.md_primary)

    /** 同色调的浅底（12% 不透明度），给 chip / 模板行做底 */
    private fun softTint(color: Int): Int =
        (color and 0x00FFFFFF) or (0x1F shl 24)


    // ————————————————————————— 设置页 —————————————————————————

    /** 快捷键模板 + 主题方案（数量/内容由数据决定，故运行时构建） */
    private fun buildSettingsPanels() {
        hotkeyBoard.reload() // 含渲染；皮肤取自当前模板色
        renderTemplates()
        renderTheme()
        renderSkins()
        renderFx()
        // 快捷键自动排序开关：改完立刻按新规则重排快捷键条
        findViewById<Switch>(R.id.swHotkeySort).apply {
            isChecked = HotkeyStore.isAutoSort(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                HotkeyStore.setAutoSort(this@MainActivity, on)
                renderChips()
            }
        }
    }

    // ————————————————————————— 模板（预置 + 用户自建） —————————————————————————

    /** 自建模板的 key 前缀（与预置模板区分） */
    private val customPrefix = "custom:"

    /** 自建模板可选主题色（与预置模板同一套色系） */
    private val templatePalette = intArrayOf(
        0xFF3482FF.toInt(), 0xFF7C5CFF.toInt(), 0xFF00A870.toInt(), 0xFFFF7A00.toInt(),
        0xFFE0457B.toInt(), 0xFF12B0C8.toInt(), 0xFF5B6470.toInt(), 0xFF1E9E5A.toInt(),
    )

    /** 用户自建模板（每次渲染重新读盘，避免与其它入口状态不一致） */
    private var customTemplates: MutableList<HotkeyTemplates.Template> = mutableListOf()

    /** 模板列表折叠开关（默认收起，只显示当前挂的那一套） */
    private fun toggleTemplates() {
        val show = templatesBox.visibility != View.VISIBLE
        templatesBox.visibility = if (show) View.VISIBLE else View.GONE
        btnToggleTemplates.text = if (show) "收起" else "更换 / 展开"
    }

    private fun renderTemplates() {
        templatesBox.removeAllViews()
        customTemplates = HotkeyStore.loadCustom(this)
        val current = HotkeyStore.loadTemplate(this)
        // 折叠状态下也要一眼看见"现在挂的是哪一套、几条"
        tvTemplateCurrent.text = HotkeyTemplates.byKey(current)
            ?.let { "${it.name} · ${hotkeyBoard.shortcuts.size} 项" }
            ?: "自定义 · ${hotkeyBoard.shortcuts.size} 项"
        (HotkeyTemplates.ALL + customTemplates).forEach { t ->
            val on = t.key == current
            val custom = t.key.startsWith(customPrefix)
            val tc = HotkeyTemplates.tint(this, t.color) // 深色主题下自动提亮，否则墨绿/石墨类看不清
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(12), dp(8), dp(12))
                // 选中：模板色浅底；未选中：中性底（靠左侧色条与彩色标题区分）
                background = if (on) pill(softTint(tc))
                else pill(resources.getColor(R.color.md_surface_variant))
                setOnClickListener { applyTemplate(t) }
            }
            // 左侧主题色条
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(tc)
                    cornerRadius = dp(2).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(4), dp(30)).apply { rightMargin = dp(12) })

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = if (custom) "${t.name} · 自建" else t.name
                textSize = 15f
                setTextColor(tc)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this).apply {
                text = t.desc
                textSize = 12f
                setTextColor(resources.getColor(R.color.md_on_surface_variant))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            row.addView(TextView(this).apply {
                text = if (on) "已应用" else "${t.combos.size} 项"
                textSize = 12f
                setTextColor(if (on) tc else resources.getColor(R.color.md_on_surface_variant))
            })
            // 自建模板多一个删除入口（预置模板不给删）
            if (custom) {
                row.addView(TextView(this).apply {
                    text = "删"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(6), dp(6), dp(6))
                    setTextColor(resources.getColor(R.color.state_error))
                    isClickable = true
                    setOnClickListener { confirmDeleteTemplate(t) }
                })
            }

            templatesBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        // 底部入口：把当前快捷键条存成自己的模板
        templatesBox.addView(TextView(this).apply {
            text = "＋ 把当前快捷键条存为模板…"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.md_primary))
            background = strokeCard(
                resources.getColor(R.color.md_primary_container),
                dp(999),
                (resources.getColor(R.color.md_primary) and 0x00FFFFFF) or (0x40 shl 24),
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { createTemplateDialog() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    /** 新建模板：名称 + 主题色由用户给，内容取**当前快捷键条** */
    private fun createTemplateDialog() {
        if (hotkeyBoard.shortcuts.isEmpty()) {
            android.widget.Toast.makeText(
                this, "快捷键条还是空的，先在触控板页加几条", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        box.addView(fieldLabel("模板名称"))
        val nameEt = EditText(this).apply {
            hint = "例如：直播快捷键"
            textSize = 15f
            setSingleLine()
            setTextColor(resources.getColor(R.color.md_on_surface))
        }
        box.addView(nameEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        box.addView(fieldLabel("主题色"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        var pickedIndex = 0
        val dots = mutableListOf<TextView>()
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        templatePalette.forEachIndexed { idx, color ->
            val dot = TextView(this).apply {
                background = dotBg(color, idx == 0)
                isClickable = true
                setOnClickListener {
                    pickedIndex = idx
                    dots.forEachIndexed { k, d -> d.background = dotBg(templatePalette[k], k == idx) }
                }
            }
            dots += dot
            colorRow.addView(dot, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
        }
        box.addView(colorRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        box.addView(TextView(this).apply {
            text = "内容取当前快捷键条（共 ${hotkeyBoard.shortcuts.size} 项），建好后仍可在触控板页逐条改"
            textSize = 12f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("新建模板")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val t = HotkeyTemplates.Template(
                            key = customPrefix + System.currentTimeMillis(),
                            name = nameEt.text.toString().trim().ifEmpty { "我的模板" },
                            desc = "${hotkeyBoard.shortcuts.size} 项 · 自建",
                            color = templatePalette[pickedIndex],
                            combos = hotkeyBoard.shortcuts.toList(),
                        )
                        customTemplates.add(t)
                        HotkeyStore.saveCustom(this@MainActivity, customTemplates)
                        applyTemplate(t) // 存完直接套用，模板来源也指向它
                        dismiss()
                    }
                }
                show()
            }
    }

    /** 色点：选中时描深色粗边 */
    private fun dotBg(color: Int, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(if (selected) dp(3) else dp(1), if (selected) 0xFF191919.toInt() else 0x33000000)
    }

    /** 删除自建模板（快捷键条内容不受影响） */
    private fun confirmDeleteTemplate(t: HotkeyTemplates.Template) {
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("删除模板")
            .setMessage("删除「${t.name}」？快捷键条上的内容不受影响。")
            .setPositiveButton("删除") { _, _ ->
                customTemplates.removeAll { it.key == t.key }
                HotkeyStore.saveCustom(this, customTemplates)
                if (HotkeyStore.loadTemplate(this) == t.key) HotkeyStore.markCustom(this)
                renderTemplates()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyTemplate(t: HotkeyTemplates.Template) {
        HotkeyStore.applyTemplate(this, t)
        hotkeyBoard.reload()
        renderTemplates()
    }

    private fun renderTheme() {
        themeBox.removeAllViews()
        val current = ThemePref.get(this)
        ThemePref.LABELS.forEachIndexed { i, label ->
            val on = i == current
            val t = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                isClickable = true
                isFocusable = true
                background = pill(
                    resources.getColor(if (on) R.color.md_primary else R.color.md_primary_container)
                )
                setTextColor(resources.getColor(if (on) R.color.md_on_primary else R.color.md_primary))
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setOnClickListener {
                    if (i != ThemePref.get(this@MainActivity)) {
                        ThemePref.set(this@MainActivity, i)
                        recreate() // 重新走 attachBaseContext → 立刻换肤
                    }
                }
            }
            themeBox.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
    }

    private fun buildLinkRows() {
        rowsLink.removeAllViews()
        linkRows = StatusRows(rowsLink)
        rowHandles["root"] = linkRows.addRow(getString(R.string.label_root))
        rowHandles["speed"] = linkRows.addRow(getString(R.string.label_usb_speed))
        rowHandles["udc"] = linkRows.addRow(getString(R.string.label_udc))
        rowHandles["service"] = linkRows.addRow(getString(R.string.label_service))
        rowHandles["battery"] = linkRows.addRow(getString(R.string.label_battery))
    }

    private fun buildModuleRows() {
        rowsWifi.removeAllViews()
        rowsBt.removeAllViews()
        rowsUsb.removeAllViews()
        moduleRows.clear()
        val inflater = LayoutInflater.from(this)
        // 保证 runtime 与模块注册表已建立（不启动任何模块）
        val rt = AgentController.build(this)
        for (m in rt.registry.all()) {
            // 一个模块可能出现在多个分组（触控板：无线 + USB），每组一份独立视图，
            // 开关状态通过 moduleRows 互相同步
            val views = mutableListOf<ModuleRow>()
            for (g in AgentController.groupsOf(m.id)) {
                val v = inflater.inflate(R.layout.item_module_row, rowsWifi, false)
                val row = ModuleRow(
                    root = v,
                    name = v.findViewById(R.id.tvName),
                    detail = v.findViewById(R.id.tvDetail),
                    dot = v.findViewById(R.id.dot),
                    sw = v.findViewById(R.id.sw),
                )
                row.name.text = AgentController.label(m.id)
                row.detail.text = AgentController.detailOf(m)
                val enabled = AgentController.isEnabled(this, m.id)
                row.sw.isChecked = enabled
                row.sw.setOnCheckedChangeListener { _, checked ->
                    AgentController.setModuleEnabled(this, m.id, checked)
                    // 同组多份视图的状态联动
                    views.forEach { it.sw.isChecked = checked }
                    refresh()
                }
                when (g) {
                    "bt" -> rowsBt
                    "usb" -> rowsUsb
                    else -> rowsWifi
                }.addView(v)
                views += row
            }
            moduleRows[m.id] = views.first()

            // Wi‑Fi 音频行下挂两个方向子开关（音箱 / 麦克风分开控制）
            if (m.id == com.allperiph.core.ModuleId.WIFI_AUDIO) {
                val ctx = this
                fun subRow(label: String, initial: Boolean, onToggle: (Boolean) -> Unit): android.view.View {
                    val sub = inflater.inflate(R.layout.item_module_row, rowsWifi, false)
                    sub.findViewById<TextView>(R.id.tvName).text = label
                    sub.findViewById<TextView>(R.id.tvDetail).visibility = android.view.View.GONE
                    sub.findViewById<ImageView>(R.id.dot).visibility = android.view.View.GONE
                    val sw = sub.findViewById<Switch>(R.id.sw)
                    sw.isChecked = initial
                    sw.setOnCheckedChangeListener { _, c -> onToggle(c) }
                    rowsWifi.addView(sub)
                    return sub
                }
                subRow("　· 音箱（PC 声音 → 手机扬声器）",
                    com.allperiph.audio.WirelessAudioModule.isSpeakerOn(this)) { on ->
                    (AgentController.module(com.allperiph.core.ModuleId.WIFI_AUDIO)
                        as? com.allperiph.audio.WirelessAudioModule)?.applySpeaker(ctx, on)
                }
                subRow("　· 麦克风（手机麦克风 → PC）",
                    com.allperiph.audio.WirelessAudioModule.isMicOn(this)) { on ->
                    (AgentController.module(com.allperiph.core.ModuleId.WIFI_AUDIO)
                        as? com.allperiph.audio.WirelessAudioModule)?.applyMic(ctx, on)
                }
            }
        }
    }

    private fun bindActions() {
        // 三个传输开关取代单总开关：打开即整组启用该类下的功能并拉起服务；
        // 关闭则整组停用，仅当三类全关才停服务。
        swWifi.isChecked = AgentController.isTransportEnabled(this, "wifi")
        swBt.isChecked = AgentController.isTransportEnabled(this, "bt")
        swUsb.isChecked = AgentController.isTransportEnabled(this, "usb")
        swWifi.setOnCheckedChangeListener { _, c -> onTransportToggle("wifi", c) }
        swBt.setOnCheckedChangeListener { _, c -> onTransportToggle("bt", c) }
        swUsb.setOnCheckedChangeListener { _, c -> onTransportToggle("usb", c) }
        btnBattery.setOnClickListener { requestBatteryWhitelist() }
        // 快捷键「编辑」入口：一屏管理所有快捷键（上移 / 下移 / 改 / 删 / 新建）
        findViewById<TextView>(R.id.btnEditChips).setOnClickListener { hotkeyBoard.managerDialog() }
        // 游戏手柄入口：全屏虚拟摇杆 + 按键，经 USB HID 上报（§2.13 Report ID 22）
        findViewById<TextView>(R.id.btnGamepad).setOnClickListener {
            startActivity(Intent(this, GamepadActivity::class.java))
        }
        // 副屏入口：全屏显示 PC 推来的桌面画面（Wi‑Fi 媒体通道 streamId=0）
        findViewById<TextView>(R.id.btnScreen).setOnClickListener {
            startActivity(Intent(this, com.allperiph.screen.ScreenActivity::class.java))
        }
        // 环境摘要点开：为什么必须 root、没有 root 还能用什么
        tvDiag.setOnClickListener { showRootHelp() }
        // 模块配色：导出 / 导入 / 分享
        findViewById<Button>(R.id.btnExportTheme).setOnClickListener { exportTheme() }
        findViewById<Button>(R.id.btnImportTheme).setOnClickListener { importTheme() }
        findViewById<Button>(R.id.btnShareTheme).setOnClickListener { shareTheme() }
        btnRefresh.setOnClickListener {
            tvDiag.text = getString(R.string.common_unknown)
            AgentController.refreshEnv(this) { env ->
                lastEnv = env
                renderEnv(env)
            }
        }
    }

    // ————————————————————————— 模块配色（触控板 / 键盘 / 快捷键） —————————————————————————

    private val REQ_EXPORT_THEME = 4101
    private val REQ_IMPORT_THEME = 4102
    private val REQ_PICK_BG = 4103

    /** 一行设置项：左标题 + 右取值胶囊（模块配色与背景手感共用） */
    private fun settingRow(title: String, value: String, tint: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(9), dp(4), dp(9))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(resources.getColor(R.color.md_on_surface))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(tint)
            background = pill(ThemeSkin.softOf(tint))
            setPadding(dp(12), dp(5), dp(12), dp(5))
        })
        return row
    }

    /** 模块配色三行：作用域 → 当前皮肤名（点开选色） */
    private fun renderSkins() {
        skinBox.removeAllViews()
        ThemeSkin.SCOPES.forEach { (scope, title) ->
            val skin = ThemeSkin.current(this, scope)
            skinBox.addView(settingRow(title, skin.name, skin.accent) { pickSkin(scope, title) })
        }
    }

    /** 背景与手感四行 */
    private fun renderFx() {
        fxBox.removeAllViews()
        val accent = resources.getColor(R.color.md_primary)

        val bg = ThemeSkin.BG_PRESETS.firstOrNull { it.id == ThemeSkin.bgId(this) } ?: ThemeSkin.BG_PRESETS[0]
        val img = ThemeSkin.bgImage(this)
        fxBox.addView(
            settingRow("背景", if (!img.isNullOrBlank()) "自定义图片" else bg.name, accent) { pickBg() }
        )
        fxBox.addView(
            settingRow("动效", ThemeSkin.MOTION_LABELS[ThemeSkin.motionIndex(this)], accent) {
                pickChoice("动效快慢", ThemeSkin.MOTION_LABELS, ThemeSkin.motionIndex(this)) { i ->
                    ThemeSkin.setMotion(this, i)
                    renderFx()
                }
            }
        )
        fxBox.addView(
            settingRow("震动", ThemeSkin.HAPTIC_LABELS[ThemeSkin.hapticIndex(this)], accent) {
                pickChoice("震动强度", ThemeSkin.HAPTIC_LABELS, ThemeSkin.hapticIndex(this)) { i ->
                    ThemeSkin.setHaptic(this, i)
                    renderFx()
                }
            }
        )
        fxBox.addView(
            settingRow("音效", ThemeSkin.SOUND_LABELS[ThemeSkin.soundIndex(this)], accent) {
                pickChoice("按键音效", ThemeSkin.SOUND_LABELS, ThemeSkin.soundIndex(this)) { i ->
                    ThemeSkin.setSound(this, i)
                    renderFx()
                }
            }
        )
        fxBox.addView(
            settingRow("键位排列", KeyPref.LAYOUT_LABELS[KeyPref.layoutIndex(this)], accent) {
                pickChoice("键位排列", KeyPref.LAYOUT_LABELS, KeyPref.layoutIndex(this)) { i ->
                    KeyPref.setLayout(this, i)
                    recreate()
                }
            }
        )
        fxBox.addView(
            settingRow("键帽密度", KeyPref.DENSITY_LABELS[KeyPref.densityIndex(this)], accent) {
                pickChoice("键帽密度", KeyPref.DENSITY_LABELS, KeyPref.densityIndex(this)) { i ->
                    KeyPref.setDensity(this, i)
                    recreate()
                }
            }
        )
        fxBox.addView(
            settingRow(
                "自定义键盘", if (KeyPref.customRows(this) == null) "未配置" else "已配置", accent
            ) {
                CustomKeyboardDialog.show(this) { recreate() }
            }
        )
    }

    /** 通用单选弹窗 */
    private fun pickChoice(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i ->
                onPick(i)
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 背景：预设底色 / 相册图片 / 清除图片 */
    private fun pickBg() {
        val labels = ThemeSkin.BG_PRESETS.map { it.name } + listOf("从相册选图片…", "清除自定义图片")
        val current = ThemeSkin.BG_PRESETS.indexOfFirst { it.id == ThemeSkin.bgId(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("背景")
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i ->
                d.dismiss()
                when {
                    i < ThemeSkin.BG_PRESETS.size -> {
                        ThemeSkin.setBg(this, ThemeSkin.BG_PRESETS[i].id)
                        ThemeSkin.setBgImage(this, null) // 选了纯色就撤掉图片
                        applyBackdrop()
                        renderFx()
                    }
                    i == ThemeSkin.BG_PRESETS.size -> pickBgImage()
                    else -> {
                        ThemeSkin.setBgImage(this, null)
                        applyBackdrop()
                        renderFx()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选背景图：走相册（SAF），并申请持久读权限 —— 重启后仍能读到 */
    private fun pickBgImage() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQ_PICK_BG) }
            .onFailure { toast("没有可用的图库") }
    }

    /** 把当前背景铺到页面根视图（图片 > 预设色 > 资源底色） */
    private fun applyBackdrop() {
        val root = (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)
            ?: return
        Backdrop.apply(root, getColor(R.color.md_background))
    }

    /** 选配色：跟随主题 + 预设 + 导入进来的自定义；选完重建页面，三个模块一起生效 */
    private fun pickSkin(scope: String, title: String) {
        val skins = listOf(ThemeSkin.followTheme(this)) + ThemeSkin.PRESETS + ThemeSkin.customs(this)
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("$title 配色")
            .setItems(skins.map { it.name }.toTypedArray()) { _, i ->
                ThemeSkin.set(this, scope, skins[i].id.ifBlank { null })
                recreate() // 触控板光标 / 键盘键帽 / 快捷键芯片一次性换掉
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 导出主题文件（走系统文件选择器，可存到下载目录或任意位置） */
    private fun exportTheme() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TITLE, "allperiph-theme.json")
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT_THEME) }
            .onFailure { toast("没有可用的文件管理器") }
    }

    /** 导入主题文件 */
    private fun importTheme() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT_THEME) }
            .onFailure { toast("没有可用的文件管理器") }
    }

    /** 分享主题文本：可直接发给别人 / 传进聊天工具（不需要文件） */
    private fun shareTheme() {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "全能外设主题")
            putExtra(android.content.Intent.EXTRA_TEXT, ThemeSkin.exportJson(this@MainActivity))
        }
        runCatching { startActivity(android.content.Intent.createChooser(intent, "分享主题")) }
            .onFailure { toast("没有可分享的应用") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_THEME -> {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(ThemeSkin.exportJson(this).toByteArray())
                    }
                    true
                }.getOrDefault(false)
                toast(if (ok) "主题已导出" else "导出失败")
            }
            REQ_IMPORT_THEME -> {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
                val n = ThemeSkin.importJson(this, text.orEmpty())
                if (n < 0) {
                    toast("这不是主题文件")
                } else {
                    toast(if (n > 0) "已导入 $n 套配色" else "主题已应用")
                    recreate()
                }
            }
            REQ_PICK_BG -> {
                // 申请持久读权限：不申请的话重启后这张图就读不到了
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                ThemeSkin.setBgImage(this, uri.toString())
                applyBackdrop()
                renderFx()
                toast("背景已设置")
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Root 说明（点设置页「环境摘要」弹出） */
    private fun showRootHelp() {
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(R.string.hint_root_dialog_title)
            .setMessage(R.string.hint_root_dialog_body)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ————————————————————————— 事件 —————————————————————————

    private fun subscribe() {
        disposables += EventBus.on<GadgetStateEvent>(handler) { refresh() }
        disposables += EventBus.on<AgentStateEvent>(handler) { refresh() }
        disposables += EventBus.on<LinkSpeedDegradedEvent>(handler) { ev ->
            tvDiag.text = ev.message
            tvDiag.setTextColor(resources.getColor(R.color.state_warn))
        }
    }

    // ————————————————————————— 渲染 —————————————————————————

    /** 传输开关：整组启用 / 停用该类下全部模块，并维护前台服务生命周期 */
    private fun onTransportToggle(t: String, on: Boolean) {
        AgentController.setTransportEnabled(this, t, on)
        for (id in AgentController.groupModules(t)) {
            AgentController.setModuleEnabled(this, id, on)
        }
        if (on) {
            AgentForegroundService.start(this)
        } else if (!AgentController.isTransportEnabled(this, "wifi") &&
            !AgentController.isTransportEnabled(this, "bt") &&
            !AgentController.isTransportEnabled(this, "usb")
        ) {
            AgentForegroundService.stop(this)
        }
        // setModuleEnabled 的启停走后台线程，这里先刷新分组显隐与开关态
        handler.post { refresh() }
    }

    private fun anyTransportOn(): Boolean =
        AgentController.isTransportEnabled(this, "wifi") ||
            AgentController.isTransportEnabled(this, "bt") ||
            AgentController.isTransportEnabled(this, "usb")

    private fun refresh() {
        // 大号启动开关（原单总开关）→ 任一类传输开关打开即视为已启用
        val serviceRunning = AgentForegroundService.running || AgentController.running
        val anyOn = anyTransportOn()
        // 启动后才展开状态信息（未启动只有开关与一句提示）
        boxRunning.visibility = if (anyOn) View.VISIBLE else View.GONE
        tvIdleHint.visibility = if (anyOn) View.GONE else View.VISIBLE
        // 未启动时列出"开启后可用"，启动后让位给真实的链路 / 模块状态
        boxIdleFeatures.visibility = if (anyOn) View.GONE else View.VISIBLE
        // 未 root：ConfigFS 写不进去，开了也必然失败 —— 提示换成 root 引导。
        // lastEnv 未探测完时为 null，此时不置灰，避免刚进页面闪一下。
        val rootMissing = lastEnv?.rooted == false
        if (!anyOn) {
            tvIdleHint.text = getString(
                if (rootMissing) R.string.hint_root_missing else R.string.hint_idle
            )
        }
        // 设置页按传输类分组：某类开关打开才显示其模块行（"打开才出现对应功能"）
        groupWifi.visibility = if (AgentController.isTransportEnabled(this, "wifi")) View.VISIBLE else View.GONE
        groupBt.visibility = if (AgentController.isTransportEnabled(this, "bt")) View.VISIBLE else View.GONE
        groupUsb.visibility = if (AgentController.isTransportEnabled(this, "usb")) View.VISIBLE else View.GONE

        val st = AgentController.overallState()
        tvOverall.text = stateLabel(st)
        tvOverall.setTextColor(resources.getColor(stateColor(st)))
        // 顶部状态徽章与总状态同步（文本 + 语义色）
        if (::badgeStatus.isInitialized) {
            badgeStatus.text = stateLabel(st)
            badgeStatus.setTextColor(resources.getColor(stateColor(st)))
        }
        val envSummary = lastEnv?.summary ?: getString(R.string.common_unknown)
        val mask = AgentController.runtime?.registry?.mask() ?: 0
        val maskText = java.lang.Integer.bitCount(mask)
        tvOverallSub.text = "$envSummary · 启用模块 $maskText 个"
        // 运行摘要的链路口径按实际通道：有 USB 速度才显示 USB 档位；纯蓝牙时提示配对。
        // 原文案固定走 USB 分支，没插线也会显示"建议更换 USB 3.0 线缆"，误导。
        val btRunning = AgentController.module(ModuleId.BTHID)?.state?.isActive == true
        val usbSpeed = lastEnv?.linkSpeed ?: LinkSpeed.UNKNOWN
        val linkText = when {
            usbSpeed.isSuperSpeed -> getString(R.string.hint_usb_ok)
            usbSpeed != LinkSpeed.UNKNOWN -> getString(R.string.hint_usb2)
            btRunning -> "蓝牙 HID 已就绪，PC 端配对后即可使用"
            else -> getString(R.string.common_unknown)
        }
        tvRunningSummary.text = "$envSummary · 启用模块 $maskText 个 · $linkText"

        // 链路行
        val env = lastEnv
        rowHandles["root"]?.setValue(
            if (env?.rooted == true) getString(R.string.value_root_ok) else getString(R.string.value_root_missing),
            linkRows.color(ok = env?.rooted == true, error = env?.rooted == false)
        )
        val speed = env?.linkSpeed ?: LinkSpeed.UNKNOWN
        rowHandles["speed"]?.setValue(
            if (env?.rawSpeed.isNullOrBlank()) speed.label else "${env?.rawSpeed}（${speed.label}）",
            linkRows.color(ok = speed.isSuperSpeed, warn = !speed.isSuperSpeed)
        )
        rowHandles["udc"]?.setValue(
            env?.udc ?: getString(R.string.common_unknown),
            linkRows.color(ok = !env?.udc.isNullOrBlank())
        )
        rowHandles["service"]?.setValue(
            if (serviceRunning) getString(R.string.common_on) else getString(R.string.common_off),
            linkRows.color(ok = serviceRunning)
        )
        val batteryOk = env?.batteryOptimized == false
        rowHandles["battery"]?.setValue(
            if (batteryOk) getString(R.string.value_battery_ok) else getString(R.string.value_battery_limited),
            linkRows.color(ok = batteryOk, warn = !batteryOk)
        )

        // 模块行（只反映真实状态，不用状态回写开关，避免"启动失败 → 开关回弹 → 再启动"循环）
        for ((id, row) in moduleRows) {
            val m: Module? = AgentController.module(id)
            val state = m?.state ?: ModuleState.IDLE
            row.detail.text = m?.statusText() ?: getString(R.string.common_off)
            tintDot(row.dot, state)
        }
    }

    /**
     * 环境摘要（设置页）：只留一行结论 + 一句指引。
     * 逐项说明与延迟预算在「状态」页，设置页再铺一遍只会把页面拉长。
     */
    private fun renderEnv(env: EnvChecks.Env) {
        tvDiag.text = buildString {
            append(if (env.rooted) "Root ✓" else "Root ✗")
            append(" · ").append(if (env.superSpeed) "USB 3.0 ✓" else "USB 2.0")
            append(" · ").append(if (env.batteryOptimized) "电池未加白 ✗" else "电池已加白 ✓")
            if (!env.notificationGranted) append(" · 通知未授权 ✗")
            append("\n详细链路与延迟预算见「状态」页")
        }
        tvDiag.setTextColor(
            resources.getColor(
                if (!env.rooted) R.color.state_error
                else if (!env.superSpeed || env.batteryOptimized) R.color.state_warn
                else R.color.state_ok
            )
        )
        refresh()
    }

    private fun tintDot(dot: ImageView, state: ModuleState) {
        val color = resources.getColor(stateColor(state))
        val d = dot.drawable?.mutate()
        d?.setTint(color)
        dot.setImageDrawable(d)
    }

    private fun stateLabel(state: ModuleState): String = when (state) {
        ModuleState.RUNNING -> getString(R.string.common_on)
        ModuleState.DEGRADED -> getString(R.string.common_warn)
        ModuleState.STARTING -> "启动中"
        ModuleState.ERROR -> getString(R.string.common_error)
        ModuleState.STOPPING -> "停止中"
        ModuleState.STOPPED -> getString(R.string.common_off)
        ModuleState.IDLE -> "未启动"
    }

    private fun stateColor(state: ModuleState): Int = when (state) {
        ModuleState.RUNNING -> R.color.state_ok
        ModuleState.DEGRADED, ModuleState.STARTING, ModuleState.STOPPING -> R.color.state_warn
        ModuleState.ERROR -> R.color.state_error
        else -> R.color.state_idle
    }

    // ————————————————————————— 引导 —————————————————————————

    private fun ensureNotificationPermission() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
            // BtHidDevice（蓝牙 HID）在 API 31+ 需要 BLUETOOTH_CONNECT，缺失即 DEGRADED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
            // 麦克风（AudioModule mic→PC）需要 RECORD_AUDIO，缺失时 AudioRecord 初始化失败
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            // GPS（NmeaSource addNmeaListener）需要 FINE_LOCATION，缺失时 GPS 模块 ERROR
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), REQ_NOTIFICATION)
    }

    private fun requestBatteryWhitelist() {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure {
                Log.w(TAG, "无法直接申请白名单，改为打开设置页：${it.message}")
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        handler.postDelayed({
            AgentController.refreshEnv(this) { env ->
                lastEnv = env
                renderEnv(env)
            }
        }, 1_500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFICATION -> {
                AgentController.refreshEnv(this) { env ->
                    lastEnv = env
                    renderEnv(env)
                }
            }
        }
    }

    // ————————————————————————— 样式工具 —————————————————————————

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(resources.getColor(R.color.md_on_surface_variant))
    }

    private fun card(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun strokeCard(color: Int, radius: Int, stroke: Int): GradientDrawable =
        card(color, radius).apply { setStroke(1, stroke) }

    private fun pill(color: Int): GradientDrawable = card(color, dp(999))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MainActivity"
        private const val TICK_MS = 1_000L
        private const val REQ_NOTIFICATION = 1001

        /** 旋屏重建时保存/恢复当前页签 */
        private const val KEY_PAGE = "page"

        private const val PAGE_TOUCHPAD = 0
        private const val PAGE_KEYBOARD = 1
        private const val PAGE_STATUS = 2
        private const val PAGE_SETTINGS = 3

        /**
         * 底栏页签 → ViewFlipper 页面 index 的映射。
         * 键盘页不占底栏位（横屏自动呈现），页签：触控板(0) / 状态(2) / 设置(3)。
         */
        private val TAB_PAGES = intArrayOf(PAGE_TOUCHPAD, PAGE_STATUS, PAGE_SETTINGS)

        /** 横屏切页手势的手指数（四指，避开触控板自身的一~三指手势） */
        private const val FINGERS_TO_SWITCH = 4

        /** 页签标题与副说明（与 activity_main.xml 的四页顺序一致） */
        private val PAGE_TITLES = arrayOf("触控板", "键盘", "状态", "设置")
        private val PAGE_SUBS = arrayOf(
            "滑动控制光标 · 快捷键轻点发送 / 长按按住",
            "7 套布局：快捷 / 遥控 / 游戏 / 数字 / 九宫格 / 方向 / F 区",
            "启动开关 · 启动后显示链路与运行状态",
            "模板与排序 · 功能开关 · 主题 · 维护",
        )
    }
}
