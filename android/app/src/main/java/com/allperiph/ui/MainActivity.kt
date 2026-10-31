package com.allperiph.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import com.allperiph.ui.kit.ApKit
import com.allperiph.core.AgentStateEvent
import com.allperiph.shared.event.EventBus
import com.allperiph.core.GadgetStateEvent
import com.allperiph.core.LinkSpeed
import com.allperiph.core.LinkSpeedDegradedEvent
import com.allperiph.shared.util.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.Uplink
import com.allperiph.core.UplinkEvent
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.hid.HotkeyTemplates
import android.widget.Toast
import com.allperiph.screen.ScreenActivity
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvControllerClient
import com.allperiph.wireless.TvDiscovery
import com.allperiph.shared.net.FileSender

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
class MainActivity : Activity(), TouchpadFragment.Host, SettingsFragment.Host, StatusFragment.Host {

    private val handler = Handler(Looper.getMainLooper())
    private val disposables = ArrayList<EventBus.Disposable>()

    private lateinit var tvOverall: TextView
    private lateinit var tvOverallSub: TextView
    private lateinit var badgeStatus: TextView
    // 传输三组（groupWifi / groupBt / groupUsb）与它们的模块行容器（rowsWifi / rowsBt /
    // rowsUsb）**只在 StatusFragment 里持有**：显隐由设置页导航 + 该 Fragment 的总开关回读
    // 决定，Activity 侧原先那份按月轮询的 visibility 赋值与这里的绑定一起删掉了。
    private lateinit var tvDiag: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnRefresh: Button

    /**
     * 「只来选设备」模式：由 [EXTRA_PICK_DEVICE] 置位。
     * 从「遥控器」页点「选择设备」进来时，选完（或取消）都**自动回到遥控页** ——
     * 否则会停在默认的触控板页，用户以为"点错了、跳回触控页了"（真机反馈）。
     */
    private var pickDeviceThenReturn = false

    // 启动后才显示的区域（状态页）
    private lateinit var tvIdleHint: TextView
    private lateinit var boxRunning: LinearLayout
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

    // TV / PC 控制区块（设置页）
    private lateinit var tvBox: LinearLayout
    private lateinit var fileBox: LinearLayout
    private lateinit var keymapBox: LinearLayout
    private var tvStatusView: TextView? = null
    private val REQ_PICK_FILE = 9002

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

    // 触控板液态光标（v196：光标本体已移交 TouchpadFragment 创建与驱动）
    private lateinit var touchArea: View
    /** 触控板页 Fragment（v196 接线）：手势面、液态光标、长按拖拽都由它接管。
     *  平台 Fragment（android.app.Fragment），宿主无需 FragmentActivity。 */
    private var touchpadFragment: TouchpadFragment? = null
    /** 设置页 Fragment（v196 第 3 步）：模板 / 主题 / 配色 / TV 控制 / 文件 / 键位映射 */
    private var settingsFragment: SettingsFragment? = null
    /** 状态页 Fragment（v196 第 4 步） */
    private var statusFragment: StatusFragment? = null

    // 横屏沉浸模式：整条 chrome 隐藏，切页改用四指左右滑
    private lateinit var headerBar: View
    private lateinit var tabBarView: View
    private lateinit var pageTouchpadView: View
    private lateinit var pageKeyboardView: View
    private var landscape = false

    // 四指左右滑切页（横屏唯一的换页方式）
    private var fourFingerStartX = -1f
    private var fourFingerSwiped = false

    private val moduleRows = LinkedHashMap<String, ModuleRow>()

    /** 触控板页的快捷键（HotkeyStore 的内存副本，编辑后回写） */
    // 快捷键网格：行为全在 ui/HotkeyBoard（主页与操控面共用一份实现）
    private lateinit var hotkeyBoard: HotkeyBoard

    // frameCount / lastShown / dragTask 已随触控板页一起搬进 TouchpadFragment（v196）

    private class ModuleRow(
        val root: View,
        val name: TextView,
        val detail: TextView,
        val dot: ImageView,
        val sw: Switch,
    )

    @Volatile
    private var lastEnv: EnvChecks.Env? = null

    // 长按拖动（armDrag）与手势分发均已搬进 TouchpadFragment（v196）

    // 必须显式声明 Runnable 类型：在初始化表达式中引用自身会让 Kotlin 类型推断递归。
    private val ticker: Runnable = Runnable { refresh(); handler.postDelayed(ticker, TICK_MS) }

    /** 主题方案：把用户选择（跟随系统 / 浅色 / 深色）注入资源配置 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强调色要叠在「正在用的这个主题」上才算数（清单主题是新建的，父上下文叠加会丢）
        ThemeSkin.applyRuntimeTheme(this)
        // ⚠️ 截屏已放开（用户反馈：不能截屏 = 没法截图反馈界面问题）。
        // 此前这里挂 FLAG_SECURE，截图/录屏/最近任务缩略图全黑，排查 UI 只能靠口述；
        // TouchpadActivity 同步移除，两处口径一致。
        setContentView(R.layout.activity_main)
        // 启动即开始发现：信标周期广播，早开早能看到设备（不再等到打开选择框才开）
        TvDiscovery.start()
        // 已开启的传输类要跟着 App 起来：否则冷启动后开关看着是 ON、实际却是「未启动」
        // （前台服务/模块/被控信标都没跑），用户只能反复关了再开 —— 副屏/音箱/麦克风、
        // 以及 PC 端发现本机都依赖这些服务真的在跑。
        if (anyTransportOn()) AgentForegroundService.start(this)
        bindViews()
        // buildLinkRows()/buildModuleRows() 不在这里再调：bindViews() 末尾挂载
        // statusFragment 时 ensureBooted() 已把链路行/模块行/开关监听完整建好。
        // 修复⑦把挂载从 buildSettingsPanels 挪进 bindViews 后，下面这两行转发
        // 从"挂载前 statusFragment==null 落空"变成了"实跑第二遍"——
        // populateModuleRows 此前不清容器，导致设置页「功能开关」每个模块行
        // 出现两个开关（v200 真机踩坑）。Fragment 侧已幂等化，这里直接删掉。
        buildTabs()
        bindActions()
        buildSettingsPanels()
        ensureNotificationPermission()
        subscribe()
        // ⚠️ 事实与旧注释相反：AndroidManifest 里本 Activity 的 configChanges **含 orientation**，
        // 所以旋屏**不会**重建 Activity（也就不会去加载 layout-land），横屏走的是
        // applyOrientationLayout() 那套运行时切换。这里只在真的被重建时（如内存不足回收）
        // 恢复页签。另：Bundle.getInt() 返回非空 Int（缺省 0）——
        // 所以必须再判一次范围，且只接受确实存过的值，避免把 buildTabs() 选好的
        // 初始页（固定触控板）强行切走。
        if (savedInstanceState?.containsKey(KEY_PAGE) == true) {
            val page = savedInstanceState.getInt(KEY_PAGE)
            if (page in PAGE_TITLES.indices) showPage(page)
        }
        AgentController.refreshEnv(this) { env ->
            lastEnv = env
            renderEnv(env)
        }
        // 从「遥控器」页带 EXTRA_PICK_DEVICE 过来的：立刻把设备选择器弹出来
        maybePickDevice(intent)
    }

    /**
     * `singleTop` 下若 MainActivity 已在栈顶，重复启动只会走 [onNewIntent]（不重建）——
     * 参数必须两边都接，否则"点选择设备没反应"。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybePickDevice(intent)
    }

    /** 处理「带 [EXTRA_PICK_DEVICE] 启动」：立刻弹设备选择器，并记住"选完就回去" */
    private fun maybePickDevice(from: Intent?) {
        if (from?.getBooleanExtra(EXTRA_PICK_DEVICE, false) != true) return
        // 用完即清：旋屏重建（本 Activity 会因 layout-land 重建）时不再重复弹
        from.removeExtra(EXTRA_PICK_DEVICE)
        pickDeviceThenReturn = true
        handler.post { runCatching { showDevicePicker() } }
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
        // 锁定中不使能传感器（反馈#2：锁定后仍绑陀螺仪 → 从源头停掉）
        if (!orientLocked) orientListener?.enable()
        // 剪贴板同步：前台才监听（v196 接线 —— 此前 ClipboardSync 从未被注册，
        // 「手机复制 → 电脑粘贴」功能等于不存在）。onPause 注销。
        ClipboardSync.register(this)
        // 三指手势遮蔽：只在触控板页在前台时上（切到键盘/设置页立刻还原，
        // 免得用户在这两页想用系统三指截屏时发现按不动）
        if (::pager.isInitialized && pager.displayedChild == PAGE_TOUCHPAD) {
            com.allperiph.touchpad.GestureShield.acquireWithHint(this, HOLDER_MAIN)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        orientListener?.disable()
        ClipboardSync.unregister(this)
        // 退到后台立刻还原系统手势（遮蔽只服务于前台操控面）
        com.allperiph.touchpad.GestureShield.release(this, HOLDER_MAIN)
        super.onPause()
    }

    override fun onDestroy() {
        for (d in disposables) d.dispose()
        disposables.clear()
        // v184：清掉挂在**全局** ControlTarget 上的回调 —— 那个闭包捕获了本 Activity
        // （runOnUiThread 里调用本实例方法），不清就会随静态对象一起泄漏，
        // 且回调还会继续往已销毁的界面推字。注意只清回调，**不断开连接**
        // （用户可能只是切页面，控制链路要保持）。
        ControlTarget.controlClient?.onReverseClipboard = null
        // ⚠️ orientPoll 每秒自我重排，而此前**全文件没有任何取消点**：Activity 销毁后
        // 它仍会每秒读 resources.configuration、写 requestedOrientation —— 整棵视图树
        // 与所有 Fragment 都无法回收。其余延时回调（如电池白名单那 1.5s 复核）同理。
        handler.removeCallbacks(orientPoll)
        handler.removeCallbacksAndMessages(null)
        // v184：发现服务此前只 start 不 stop —— 退出 App 后它仍占着 9501 端口与
        // 发现线程（且会持续收到广播）。TvDiscovery.stop() 早就实现了，只是没人调用。
        runCatching { TvDiscovery.stop() }
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
        backDialog = AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_exit))
            .setMessage(
                if (running) getString(R.string.ui_main_text_exit)
                else getString(R.string.ui_main_text_exit_2)
            )
            .setPositiveButton(getString(R.string.ui_common_exit)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .create()
            .also { it.show() }
    }

    // ————————————————————————— 视图 —————————————————————————

    private fun bindViews() {
        badgeStatus = findViewById(R.id.badgeStatus)

        tvOverall = findViewById(R.id.tvOverall)
        tvOverallSub = findViewById(R.id.tvOverallSub)
        tvDiag = findViewById(R.id.tvDiag)
        btnBattery = findViewById(R.id.btnBattery)
        btnRefresh = findViewById(R.id.btnRefresh)

        tvIdleHint = findViewById(R.id.tvIdleHint)
        boxRunning = findViewById(R.id.boxRunning)
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
        tvBox = findViewById(R.id.tvBox)
        fileBox = findViewById(R.id.fileBox)
        keymapBox = findViewById(R.id.keymapBox)
        setupSettingsTabs()
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

        // 触控板液态光标：改由 TouchpadFragment 创建并叠加在手势面上
        // （v196：原先这里 addView 了一份，Fragment 又会add 一份 —— 不删就会出现两个光标）
        touchArea = findViewById(R.id.touchArea)

        // 切页动效：淡入上浮（液体流入 / 流出）
        pager.setInAnimation(this, R.anim.liquid_in)
        pager.setOutAnimation(this, R.anim.liquid_out)

        setupGlass()
        applyBackdrop() // 背景：预设底色 / 相册图

        // 触控板页 Fragment（v196 接线第2 步）：手势面/ 液态光标 / 长按拖拽交给它。
        // 用 android.R.id.content 作容器 —— Fragment 的 onCreateView 返回 null（寄生式，
        // 直接抓宿主视图），只要挂进 FragmentManager 即可。
        // 「先按 tag 找、找不到才新建」而不是判savedInstanceState：进程被回收后重建时
        // FragmentManager 会自动恢复旧 Fragment，再 add 一次就会出现两个触控板页。
        // commitNow 而非 commit：同步执行完事务，保证随后的 onTouchEvent 转发时已就绪。
        touchpadFragment = (fragmentManager.findFragmentByTag("touchpad") as? TouchpadFragment)
            ?: TouchpadFragment().also { fp ->
                fragmentManager.beginTransaction()
                    .add(android.R.id.content, fp, "touchpad")
                    .commitNow()
            }
        // onCreateView 返回 null → onViewCreated 永不回调，液态光标/拖拽提示的
        // 绑定必须在挂载后显式触发；进程回收恢复的旧实例同样要补（v200 修复，
        // 此前液态光标自 v196 起从未渲染过）。视图树一套不重建（旋屏只调显隐），
        // 幂等守卫即可，无需实例比较重绑。
        touchpadFragment?.ensureViews()

        // 设置页 Fragment（v196 第 3 步）：模板/主题/配色/TV控制/文件/键位映射都由它渲染。
        // 注意与 FileFragment 的分工：**只接SettingsFragment**，FileFragment 不接 ——
        // 两者都渲染 fileBox/keymapBox，同时接会互相 removeAllViews 清空对方。
        settingsFragment = (fragmentManager.findFragmentByTag("settings") as? SettingsFragment)
            ?: SettingsFragment().also { fp ->
                fragmentManager.beginTransaction()
                    .add(android.R.id.content, fp, "settings")
                    .commitNow()
            }

        // 状态页 Fragment（v196 第 4 步）：启动开关 / 传输开关 / 模块行 / 链路诊断。
        // 放在最后挂：它初始化里会读 Activity 的 hostState/env，这些要在前两步之后。
        // ⚠️ onViewCreated 不可依赖（onCreateView 返回 null → 平台 Fragment 不回调），
        // 挂载后必须显式 ensureBooted()：否则三个传输开关的监听永不绑定，「有线（USB）」
        // 开关拨了没反应、分组/模块行全空白（v199 真机踩坑）。恢复的旧实例同样要补。
        statusFragment = (fragmentManager.findFragmentByTag("status") as? StatusFragment)
            ?.also { it.ensureBooted() }
            ?: StatusFragment().also { fp ->
                fragmentManager.beginTransaction()
                    .add(android.R.id.content, fp, "status")
                    .commitNow()
                fp.ensureBooted()
            }
    }

    // ————————————————————————— 横竖屏 —————————————————————————

    /**
     * 横屏 = 沉浸操控面：**去掉标题栏与悬浮底栏**，整屏留给触控板 / 键盘 / 状态 / 设置，
     * 页间用四指左右滑循环切换。竖屏恢复完整 chrome（标题 + 玻璃底栏 + 页签底栏）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (orientLocked) {
            // 锁定中（反馈#2，Pad 实测）：布局钉在锁定方向，不再跟随系统配置翻面 ——
            // 部分 ROM/Pad 仍会派发旋转配置，布局跟着转 = 设置页被翻走、没法操作。
            val want = lockedOrient == 1
            if (landscape != want) {
                landscape = want
                applyOrientationLayout()
            }
            handler.post { snapIndicator() }
            return
        }
        // 配置变化时用新配置校准 landscape（与 orientListener 的物理朝向判断互为兜底）
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationLayout()
        handler.post { snapIndicator() }
    }

    private fun applyOrientationLayout() {
        // landscape 由调用方设置（orientListener / onConfigurationChanged），
        // 这里不再从 resources.configuration 重读 —— 避免 requestedOrientation 未生效时
        // 配置方向滞后于物理朝向，导致布局朝向与实际手持不一致。
        val dm = resources.displayMetrics
        Log.i(
            TAG,
            "applyOrientationLayout: landscape=$landscape " +
                "config=${resources.configuration.orientation} ${dm.widthPixels}x${dm.heightPixels}"
        )

        // 顶栏 / 底栏的显隐只能在这里设 —— include 标签的 android:visibility 会被
        // LayoutInflater 忽略（只认 layout_* 和 id），布局文件里写 view=gone 不生效。
        // v223.13（issue #52 建议 4）：横屏**保持竖屏的顶栏 + 底栏**，不再走沉浸式。
        // 报障者原话：「横屏建议保持竖屏顶栏和底栏样式，不要改成右上角的按钮组，不要隐藏
        // 底栏，避免 UI 遮挡主区域内容、内容拉伸变形」。切页与设置都回到常驻入口，
        // 四指滑作为附加路径保留（它不再承担唯一入口的职责）。
        headerBar.visibility = View.VISIBLE
        tabBarView.visibility = View.VISIBLE

        val bottomPad = resources.getDimensionPixelSize(R.dimen.inset_bottom_nav)
        if (::pageKeyboardView.isInitialized) {
            pageKeyboardView.setPadding(
                pageKeyboardView.paddingLeft, pageKeyboardView.paddingTop,
                pageKeyboardView.paddingRight, bottomPad,
            )
        }
        if (::pageTouchpadView.isInitialized) {
            pageTouchpadView.setPadding(
                pageTouchpadView.paddingLeft, pageTouchpadView.paddingTop,
                pageTouchpadView.paddingRight, bottomPad,
            )
        }
        // 设置页没有 layout-land 版本（同一份布局两种朝向都适用），底部留白同样在这里调
        findViewById<View>(R.id.pageSettings)?.let {
            it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, bottomPad)
        }
        // ---- v223.13（issue #52 建议 4）：右上角 pill 按钮组整体下线 ----
        // pill（触控板/键盘/锁定/设置四颗）从「横屏 chrome 全隐」那套里来，它靠 elevation
        // 浮在页面右上角，压住键盘页页签行、触控板页「常用快捷键」标题、设置页分类列表 ——
        // 此前为它做了三处让位（顶距 / 右内边距 / 顶距），现在顶栏底栏常驻，pill 没有任何
        // 独占职能，整条撤掉，三处让位也一并写回各自布局里的原值。
        // （旋屏不重建 Activity，改过的 padding 会留在同一棵视图树上，必须显式写回。）
        findViewById<View>(R.id.pillLandActions)?.visibility = View.GONE
        findViewById<View>(R.id.chipSection)?.let {
            it.setPadding(it.paddingLeft, dp(8), it.paddingRight, it.paddingBottom)
        }
        if (::keyboardPanel.isInitialized) {
            keyboardPanel.tabScroll.setPadding(
                keyboardPanel.tabScroll.paddingLeft, keyboardPanel.tabScroll.paddingTop,
                dp(6), keyboardPanel.tabScroll.paddingBottom,
            )
        }
        findViewById<View>(R.id.paneGroupsScroll)?.let {
            it.setPadding(it.paddingLeft, 0, it.paddingRight, it.paddingBottom)
        }

        // 旋转只换布局、不切页：横竖屏各自铺各自的布局，当前页面保持不动
        //（键盘页已转正进底栏，不再“横过来就自动跳键盘”）。
        if (::pager.isInitialized) {

            // 设置页两栏（不分朝向：左 1 右 2、两栏各自全高独立滚动）已**写进布局** ——
            // settingsTwoPane 恒为横排，权重 1:2 写在 XML 里。运行期不再改 orientation
            // 与 LayoutParams，少一处「代码以为改了、实际没生效」的状态。
            // 需要按朝向调的只有上面那段底部留白。
        }

        // 反馈#9：横屏底栏常驻 → 旋屏后重算胶囊位置（原横屏 setIndicator(null) 清除）
        if (landscape) findViewById<View>(R.id.contentStack).post { animateIndicator() }
        applyImmersive()
        applyNavBarWidth()
        updateSettingsEntry()
    }

    /**
     * 沉浸：横屏把**通知栏也盖掉**（整屏都是操控面）；
     * 竖屏保留状态栏（时间/电量有用），但盖掉底部导航手势条 —— 就是那条"白色横"。
     * 两种情况都留 BY_SWIPE：临时下拉/上滑能把系统栏唤出来，不会把自己锁死。
     */
    private fun applyImmersive() {
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // v223.13（issue #52 建议 4）：横屏不再是「整屏操控面」—— 顶栏回来了，状态栏就
        // 必须显示，否则顶栏标题会压到时间/电量上。两种朝向同一套：显示状态栏、
        // 只盖掉底部手势条（那条白横对操控面没用，还会误触）。
        c.show(WindowInsets.Type.statusBars())
        c.hide(WindowInsets.Type.navigationBars())
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
                            // 横屏四指循环换页（触控板→键盘→设置；状态页已并入设置，反馈#10）
                            val order = intArrayOf(
                                PAGE_TOUCHPAD, PAGE_KEYBOARD, PAGE_SETTINGS,
                            )
                            val cur = order.indexOf(pager.displayedChild).coerceAtLeast(0)
                            val next = if (dx < 0) {
                                order[(cur + 1) % order.size]
                            } else {
                                order[(cur + order.size - 1) % order.size]
                            }
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
        // 初始朝向由当前配置决定（冷启动时物理朝向 = 配置方向）
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationLayout() // 竖屏/横屏两套 chrome（横屏为沉浸操控面）
        findViewById<View>(R.id.contentStack).post { snapIndicator() }
        // 方向锁定恢复：重建后 requestedOrientation 已复位为 manifest 默认，必须重新上锁
        restoreOrientLock()
        // 朝向兜底：个别 ROM（MIUI 某些省电/分屏场景）旋屏不派发 onConfigurationChanged，
        // 导致「横竖屏布局没跟上」。每秒比对一次实际朝向，不一致就补请求（幂等，代价可忽略）。
        handler.postDelayed(orientPoll, 1000)
    }

    /** 从 SharedPreferences 恢复用户方向锁定（Activity 重建后 requestedOrientation 已复位，必须重新锁） */
    private fun restoreOrientLock() {
        if (!getSharedPreferences(PREF_ORIENT, MODE_PRIVATE).getBoolean(KEY_LOCK, false)) return
        orientLocked = true
        lockedOrient = if (landscape) 1 else 2
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        Log.i(TAG, "恢复方向锁定：orient=$lockedOrient")
        renderOrientLock()
    }

    /**
     * 方向锁定开关（顶栏按钮 + 设置页开关双入口，幂等：传入目标状态而不是翻转）。
     *  - 锁定：SCREEN_ORIENTATION_LOCKED 冻结当前朝向，orientListener（物理检测）与
     *    orientPoll（兜底轮询）全部暂停 —— 与朝向循环止血补丁正交互补：补丁让自动切换
     *    不发疯，锁定给用户一键彻底停。
     *  - 解锁：交还系统（UNSPECIFIED；不用 PORTRAIT —— MIUI 收到竖屏请求会写死
     *    user_rotation=0，之后横持请求全被忽略，见 orientListener 注释），并把 forcedOrient
     *    归零，让下一个物理事件立即重新接管方向（保留旧值会让 listener 误判"已到位"直接 return）。
     */
    private fun setOrientLock(lock: Boolean) {
        if (lock == orientLocked) { renderOrientLock(); return }
        orientLocked = lock
        if (lock) {
            lockedOrient = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 2
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            orientListener?.disable() // 锁定即停物理检测（反馈#2：锁定≠只冻 requestedOrientation）
            Log.i(TAG, "方向锁定：orient=$lockedOrient")
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            forcedOrient = 0
            lockedOrient = 0
            orientListener?.enable()  // 解锁恢复检测（onResume 同款幂等）
            Log.i(TAG, "方向解锁：交还系统，物理检测恢复")
        }
        getSharedPreferences(PREF_ORIENT, MODE_PRIVATE).edit().putBoolean(KEY_LOCK, lock).apply()
        renderOrientLock()
    }

    private fun toggleOrientLock() = setOrientLock(!orientLocked)

    /** 双入口状态同步：顶栏按钮三态文案（锁定中显示锁定方向）+ 设置开关勾选态（程序化 setChecked 不回调 listener） */
    private fun renderOrientLock() {
        findViewById<TextView>(R.id.btnOrientLock)?.apply {
            text = when {
                !orientLocked -> context.getString(R.string.ui_main_btn_orient_lock)
                lockedOrient == 1 -> context.getString(R.string.ui_main_btn_orient_locked_land)
                else -> context.getString(R.string.ui_main_btn_orient_locked_port)
            }
        }
        findViewById<Switch>(R.id.swOrientLock)?.isChecked = orientLocked
    }

    /**
     * 右上角设置入口显隐（反馈#2）。
     *  - 竖屏：顶栏按钮常驻（设置页自身隐藏，避免与顶栏分段/底栏页签重复）
     *  - 横屏：顶栏与底栏整条隐藏 → 悬浮按钮常驻；设置页上文案改「返回」
     */
    private fun updateSettingsEntry() {
        val onSettings = ::pager.isInitialized && pager.displayedChild == PAGE_SETTINGS
        findViewById<TextView>(R.id.btnSettingsEntry)?.visibility =
            if (!landscape && !onSettings) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnSettingsFloat)?.apply {
            visibility = View.GONE // 批P：横屏操作条 pill（btnLandSettings）接管
            text = getString(
                if (onSettings) R.string.ui_main_btn_settings_back
                else R.string.ui_main_btn_settings_entry,
            )
        }
    }

    /** orientPoll 上次 re-request 方向的时刻（3s 冷却，防每秒请求风暴） */
    private var lastPollFixMs = 0L

    private val orientPoll = object : Runnable {
        override fun run() {
            if (orientLocked) {
                // 锁定中：只保持心跳，不比对不 re-request（避免解锁瞬间积压的方向请求）
                handler.postDelayed(this, 1000)
                return
            }
            val configLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (configLand != landscape) {
                // 配置方向与期望方向（物理朝向）不一致：说明 requestedOrientation 尚未生效
                // （某些 ROM 延迟 / 节流冲突 / configChanges 拦截）。重新请求一次，
                // 但不覆盖 landscape —— 页面已经按物理朝向切好了，这里只催 Activity 跟上。
                // ⚠️ 3s 冷却：无冷却时每秒都 re-request —— 与系统方向锁/回弹叠加会形成
                // 每秒方向请求风暴，是「切横→被掰回+重建→再切横」循环的可疑触发器
                // （真机两核满频 / 10W 事故）。冷却期内只观察不请求。
                val nowFix = android.os.SystemClock.elapsedRealtime()
                if (nowFix - lastPollFixMs >= POLL_FIX_COOLDOWN_MS) {
                    lastPollFixMs = nowFix
                    Log.i(TAG, "朝向兜底：期望=$landscape 配置=$configLand，重新请求方向")
                    requestedOrientation = if (landscape)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    forcedOrient = if (landscape) 1 else 2
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    // ——————————————————— 物理朝向检测（不依赖系统方向锁） ———————————————————

    /**
     * 拿机方向 = 操控面：**横持铺开横屏布局、竖持铺开竖屏布局**，与系统的方向锁定完全无关。
     *
     * 原理：加速度计判断物理倾角 → 横持时 [Activity#setRequestedOrientation]
     * **由 App 主动请求横屏**（视频 App 的横屏按钮同原理，系统方向锁拦不住 App 自己的请求），
     * configuration 随之变化 → [applyOrientationLayout] 只换布局、不切页。
     *
     * 细节：
     *  - 只在触控板/键盘两页之间自动切换，用户停在状态/设置页时不抢方向控制权；
     *  - 带滞回与 600ms 节流：斜角 45° 附近抖动不会来回跳；
     *  - [orientListener] 在 onResume/onPause 启停，后台不耗电。
     *  - forcedOrient / lastOrientSwitchMs 已移入 companion 跨实例保持：Activity 被重建时
     *    归零会让物理检测重新发起切换，与系统回弹构成无限循环（真机两核满频元凶）。
     */
    private var orientListener: OrientationEventListener? = null

    private fun setupOrientListener() {
        if (orientListener != null) return
        orientListener = object : OrientationEventListener(this, android.hardware.SensorManager.SENSOR_DELAY_GAME) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                if (orientLocked) return   // 锁定中：物理旋转不再接管方向
                if (!::pager.isInitialized) return
                // 只自动切换两个操控面；状态/设置页保持用户当前的方向控制权
                val cur = pager.displayedChild
                if (cur != PAGE_TOUCHPAD && cur != PAGE_KEYBOARD) return
                // 滞回：明确横（60°..120° 或 240°..300°）才判横，明确竖（<15° 或 >345°）才判竖，
                // 中间斜角保持现状，避免边界抖动
                val want = when (deg) {
                    in 60..120, in 240..300 -> 1
                    in 0..15, in 345..359 -> 2
                    else -> return
                }
                if (want == forcedOrient) {
                    // 已是期望方向：配置也一致 = 真稳，直接回；
                    // 配置不一致（被系统掰回）时每 30s 放行一次重试 —— 静态 forcedOrient
                    // 若永不放行会卡死在被掰回的方向，30s 一次成本可忽略、不构成循环。
                    val configLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    if ((if (configLand) 1 else 2) == want) return
                    if (android.os.SystemClock.elapsedRealtime() - lastOrientSwitchMs < RETRY_STUCK_MS) return
                    // 放行到下方节流与切换（lastOrientSwitchMs 会刷新）
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastOrientSwitchMs < 1500) return   // 节流：旋转动画 ~300ms，切太快系统来不及执行
                lastOrientSwitchMs = now
                forcedOrient = want
                val toLandscape = want == 1
                // 横持：App 请求横屏（视频 App 同原理，系统方向锁拦不住）；
                // 竖持：**交还系统**（UNSPECIFIED）而不是请求竖屏 ——
                // 实测 MIUI 在收到 PORTRAIT 请求时会关掉自动旋转并写死 user_rotation=0，
                // 导致之后横持请求被忽略（「键盘没了」的元凶）。交还则不会。
                requestedOrientation = if (toLandscape)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                // 立即按物理朝向刷新布局，不等待 onConfigurationChanged ——
                // 某些 ROM 下 requestedOrientation 未即时生效，配置方向滞后。
                // 只换布局不切页：当前页面保持不动。
                if (landscape != toLandscape) {
                    landscape = toLandscape
                    applyOrientationLayout()
                }
                Log.i(TAG, "物理朝向切换：force=$want deg=$deg landscape=$landscape")
            }
        }
    }

    /** 底部页签：绑定点击与选中态（胶囊底 + 图标 / 文字同步染色） */
    private fun buildTabs() {
        // 底栏 3 页签：触控板 / 键盘 / 设置（状态页并入设置页，顶栏分页互切）。
        // 旋转只换布局不切页，页面选择权始终在用户手里。
        listOf(
            intArrayOf(R.id.tabTouchpad, R.id.ivTabTouchpad, R.id.tvTabTouchpad, R.id.tabPillTouchpad),
            intArrayOf(R.id.tabKeyboard, R.id.ivTabKeyboard, R.id.tvTabKeyboard, R.id.tabPillKeyboard),
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
            if (tabIndex == 0) {
                tab.isLongClickable = true
                tab.setOnLongClickListener {
                    showTargetMenu()
                    true
                }
            }
        }
        // 初始页固定触控板（横竖屏一致；旋转只换布局，不再自动跳键盘页）
        showPage(PAGE_TOUCHPAD)
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
            ThemeSkin.primary(this),
            maxR,
            dur,
        ).apply {
            onFinish = { if (pill.foreground === this) pill.foreground = null }
        }
        pill.foreground = ripple
        ripple.play()
    }

    /** 页 → 底栏页签下标（未映射返回 -1；状态页已并入设置，反馈#10） */
    private fun tabIndexFor(page: Int): Int =
        TAB_PAGES.indexOf(page)

    /** 切页：标题栏、页签染色与选中胶囊同步（内容页各自独立滚动） */
    private fun showPage(i: Int) {
        if (!::pager.isInitialized) return
        pager.displayedChild = i
        tvPageTitle.text = getString(PAGE_TITLES[i])
        tvPageSub.text = getString(PAGE_SUBS[i])
        if (i == PAGE_TOUCHPAD) {
            renderChips()
            // 切回触控板页 = 重新上遮蔽；切走 = 放掉（引用计数，另一操控面同时在时仍生效）
            com.allperiph.touchpad.GestureShield.acquireWithHint(this, HOLDER_MAIN)
        } else {
            com.allperiph.touchpad.GestureShield.release(this, HOLDER_MAIN)
        }
        // 反馈#10 批 B：进设置页一律落回分类首页（setupSettingsTabs 内部 showHome）
        if (i == PAGE_SETTINGS) settingsFragment?.setupSettingsTabs()
        val activeTab = tabIndexFor(i)
        tabViews.indices.forEach { k ->
            val on = k == activeTab
            val color = if (on) ThemeSkin.primary(this) else resources.getColor(R.color.state_idle)
            tabIcons[k].setColorFilter(color)
            tabLabels[k].setTextColor(color)
            tabLabels[k].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            tabPills[k].background = null // 选中底由底栏 Drawable 里的滑动胶囊承担
        }
        // 状态已并入设置页（反馈#10）：顶栏「状态↔设置」分段及其着色一并移除
        updateSettingsEntry()
        // 反馈#9：横屏底栏常驻 → 不再提前返回，选中胶囊也跟着滑（四指滑切页保留，只是不再唯一）
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
        val tabIndex = tabIndexFor(pager.displayedChild)
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
                val h = (from.height() + (target.height() - from.height()) * t)
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
        val pill = tabPills.getOrNull(tabIndexFor(pager.displayedChild)) ?: return
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
        // 触控板页的手势分发 / 液态光标 / 长按拖拽已由 TouchpadFragment 接管（v196）。
        // 这里只做"当前是不是触控板页"的判定与转发。
        return touchpadFragment?.onTouchEvent(ev) ?: true
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
            accent = ThemeSkin.primary(this),
            textPrimary = resources.getColor(R.color.md_on_surface),
            textSecondary = resources.getColor(R.color.md_on_surface_variant),
        )
    }

    // ————————————————————————— TouchpadFragment.Host —————————————————————————
    // 触控板页拆成 Fragment 后由它驱动，宿主只提供数据与转发（v196 接线第 1 步：
    // 先把接口实现齐、编译通过，下一步再 add Fragment 并删除下方重复实现）。
    // 本项目用的是平台 android.app.Fragment（非 androidx），所以宿主**不需要** FragmentActivity。

    override fun getTouchpadHandler(): Handler = handler

    override fun feedGesture(ev: MotionEvent) {
        (AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule)?.feed(ev)
    }

    override fun isTouchpadPage(): Boolean =
        ::pager.isInitialized && pager.displayedChild == PAGE_TOUCHPAD

    override fun onLongPressArmDrag() {
        (AgentController.module(ModuleId.TOUCHPAD) as? com.allperiph.touchpad.TouchpadModule)?.armDrag()
    }

    override fun getHotkeyBoard(): HotkeyBoard = hotkeyBoard

    // 触控板页三个视图交给 Fragment（Activity 侧已 findViewById 完毕，字段必然非空）
    override fun hostTouchHint(): TextView = touchHint
    override fun hostChipRow(): LinearLayout = chipRow
    override fun hostTouchArea(): FrameLayout = touchArea as FrameLayout

    // ———— SettingsFragment.Host（v196 第 3 步）————
    // 设置页拆成 Fragment 后由它渲染；宿主提供环境与副作用（主题/背景/文件选择器/重建）。

    override fun getSettingsHandler(): Handler = handler

    override fun recreateActivity() = recreate()

    override fun isControlling(): Boolean = ControlTarget.isControlling()

    override fun getControlTargetLabel(): String = ControlTarget.label

    /** 主题导入的结果处理（从 onActivityResult 的 REQ_IMPORT_THEME 分支抽出，
     *  Fragment 拿到 uri 后调过来 —— Activity 级回调与 Fragment 共用同一份逻辑。 */
    override fun handleThemeResult(uri: android.net.Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        val n = ThemeSkin.importJson(this, text.orEmpty())
        if (n < 0) {
            toast(getString(R.string.ui_main_toast_theme_file_export))
        } else {
            toast(if (n > 0) getString(R.string.ui_main_text_color_import_done, n) else getString(R.string.ui_main_text_theme_done))
            recreate()
        }
    }

    /** 背景图选择的结果处理（同上，从 REQ_PICK_BG 分支抽出） */
    override fun handleBgResult(uri: android.net.Uri) {
        // 申请持久读权限：不申请的话重启后这张图就读不到了
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        ThemeSkin.setBgImage(this, uri.toString())
        applyBackdrop()
        renderFx()
        toast(getString(R.string.ui_main_toast_background_done_settings))
    }

    // ———— StatusFragment.Host（v196 第 4 步）————
    // 状态页（启动开关/传输开关/模块行/链路诊断）拆给 StatusFragment，宿主只提供
    // 环境与服务控制。AgentController 全是静态 API，所以这里都是薄封装。

    override fun getStatusHandler(): Handler = handler

    override fun buildAgentController() {
        AgentController.build(this)
    }

    override fun isTransportEnabled(transport: String): Boolean =
        AgentController.isTransportEnabled(this, transport)

    override fun setTransportEnabled(transport: String, enabled: Boolean) {
        AgentController.setTransportEnabled(this, transport, enabled)
        // 与 Activity 原逻辑一致：传输开关变化后模块行与运行摘要都要重算
        refresh()
    }

    override fun groupModules(transport: String): List<String> =
        AgentController.groupModules(transport)

    override fun groupsOf(moduleId: String): List<String> = AgentController.groupsOf(moduleId)

    override fun isModuleEnabled(moduleId: String): Boolean =
        AgentController.isEnabled(this, moduleId)

    override fun setModuleEnabled(moduleId: String, enabled: Boolean) {
        AgentController.setModuleEnabled(this, moduleId, enabled)
        refresh()
    }

    override fun isExitEnabled(moduleId: String, group: String): Boolean =
        AgentController.isExitEnabled(moduleId, group)

    override fun setExitEnabled(moduleId: String, group: String, enabled: Boolean) {
        AgentController.setExitEnabled(this, moduleId, group, enabled)
    }

    override fun getModuleState(moduleId: String): ModuleState =
        AgentController.module(moduleId)?.state ?: ModuleState.STOPPED

    override fun getModuleStatusLabel(moduleId: String): String =
        AgentController.module(moduleId)?.let { AgentController.detailOf(it) } ?: "—"

    override fun startForegroundService() {
        AgentForegroundService.start(this)
    }

    override fun stopForegroundService() {
        AgentController.stopAll()
        stopService(Intent(this, AgentForegroundService::class.java))
    }

    override fun getEnvSummary(): String =
        lastEnv?.summary ?: getString(R.string.common_unknown)

    override fun getLastEnv(): EnvChecks.Env? = lastEnv

    /** 顶栏徽章：状态文案 + 颜色 + 出口提示（v196 由 Fragment 统一驱动，避免两处各写一份） */
    override fun onStatusBadgeUpdate(stateLabel: String, stateColor: Int, uplinkPath: String) {
        tvPageSub?.text = stateLabel
        runCatching { tvPageSub?.setTextColor(stateColor) }
        renderUplink()
    }

    override fun isBtConnected(): Boolean =
        (AgentController.module(com.allperiph.core.ModuleId.BTHID) as? com.allperiph.bt.BtHidDevice)?.isConnected == true

    /**
     * 状态页左侧三个传输按钮 → 设置页右栏切到对应分类（"wifi" / "bt" / "usb"）。
     *
     * 真机反馈「设置页左边三个传输按钮点了应该弹出对应分类」：按钮不再就地开关，只把右栏
     * 切到那一类的分类页（页内第一张卡就是总开关）——开关的生效路径仍在
     * StatusFragment.onTransportToggle，没有第二套。
     */
    override fun showCategory(category: String) { settingsFragment?.showCategory(category) }

    override fun renderChips() = hotkeyBoard.render()

    /** 设置页的自动排序开关跟随真实状态（拖拽/上下移会把它关掉，开关要跟着变） */
    override fun syncSortSwitch() {
        val sw = findViewById<Switch>(R.id.swHotkeySort) ?: return
        val on = HotkeyStore.isAutoSort(this)
        if (sw.isChecked != on) sw.isChecked = on // 值有变才赋值，listener 不会无限递归
    }

    /** 当前生效模板的主题色；逐条改过（自定义）时回落到主色 */
    override fun templateColor(): Int =
        HotkeyTemplates.byKey(HotkeyStore.loadTemplate(this))
            ?.let { HotkeyTemplates.resolveColor(this, it) }
            ?: ThemeSkin.primary(this)

    /** 同色调的浅底（12% 不透明度），给 chip / 模板行做底 */
    override fun softTint(color: Int): Int =
        (color and 0x00FFFFFF) or (0x1F shl 24)


    // ————————————————————————— TV / PC 控制（长按底栏「触控板」进入） —————————————————————————

    /** 连入 TV/PC（9511）并设为当前控制目标：主页触摸板与快捷键会自动改发到对端
     * @param type 设备类型 "tv" 或 "pc"，决定快捷键条是否进 TV 专属布局 */
    override fun connectTarget(ip: String, port: Int, name: String, type: String) =
        connectTargetInternal(ip, port, name, type)

    private fun connectTargetInternal(ip: String, port: Int, name: String, type: String = "tv") {
        Thread({
            val c = TvControllerClient(ip, port)
            val ok = c.connect()
            runOnUiThread {
                // connect() 最长阻塞 5s，期间用户可能已经退出页面
                if (isFinishing || isDestroyed) {
                    c.disconnect()
                    return@runOnUiThread
                }
                if (ok) {
                    // ⚠️ 必须先断开旧连接：旧实例仍是 running，它的 readerLoop 会
                    // 每 2s 无限重连**旧 IP**，并一直留着 socket + tx/ping 三条线程。
                    // 以前这里直接覆盖，于是每换一次受控设备就永久多留一套。
                    ControlTarget.controlClient?.takeIf { it !== c }?.disconnect()
                    ControlTarget.controlClient = c
                    ControlTarget.host = ip
                    ControlTarget.label = name
                    ControlTarget.type = type
                    // 连接历史：连上就记一笔（v196 接线 —— ConnectHistory 此前零调用点，
                    // 「最近连过哪些设备」持久化功能等于不存在）。
                    ConnectHistory.add(this, name, ip, port)
                    c.onReverseClipboard = { text -> runOnUiThread { recvReverseClipboard(text) } }
                    onTargetChanged()
                    Toast.makeText(this, getString(R.string.ui_main_toast_done, name), Toast.LENGTH_SHORT).show()
                } else {
                    ControlTarget.clear()
                    onTargetChanged()
                    // 失败句给"为什么 + 下一步"，别只说一句连不上
                    Toast.makeText(
                        this,
                        "连不上 $ip —— 确认两台设备在同一个 Wi‑Fi，或改用「手动输入 IP」直连",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, "apx-target").start()
    }

    /** 设备选择：本机/PC + 已发现设备 + 手动输入 IP（TV 与 PC 都跑 9511）
     *  信标是周期广播的，弹窗打开瞬间往往还没收到 —— 打开期间每秒刷新列表。 */
    override fun showDevicePicker() {
        TvDiscovery.start()
        val items = ArrayList<CharSequence>()
        val adapter = android.widget.ArrayAdapter<CharSequence>(
            this, android.R.layout.simple_list_item_1, items
        )
        fun rebuild() {
            items.clear()
            items.add(getString(R.string.ui_main_item_local))
            TvDiscovery.list().forEach {
                items.add("${it.name}  ${it.ip}  ${it.typeLabel}")
            }
            if (items.size == 1) items.add(getString(R.string.ui_main_item_device))
            items.add(getString(R.string.ui_main_item_input_manual))
            adapter.notifyDataSetChanged()
        }
        rebuild()
        val dlg = AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_device_pick))
            .setAdapter(adapter) { _, which ->
                val tvs = TvDiscovery.list()
                when {
                    which == 0 -> { ControlTarget.clear(); onTargetChanged() }
                    which == items.size - 1 -> promptTargetIp()
                    tvs.isEmpty() -> { /* 占位「搜索中」，忽略 */ }
                    else -> {
                        val tv = tvs.getOrNull(which - 1) ?: return@setAdapter
                        connectTarget(tv.ip, tv.port, tv.name, tv.type)
                    }
                }
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .create()
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                rebuild()
                handler.postDelayed(this, 1000)
            }
        }
        dlg.setOnShowListener { handler.postDelayed(tick, 1000) }
        dlg.setOnDismissListener {
            handler.removeCallbacks(tick)
            // 从遥控页过来的：**选完或取消都自动回去**（点条目/取消/返回键都会 dismiss，
            // 所以放这里能覆盖全部出口）。不移除的话用户会停在触控板页，就是反馈里的"跳回触控页"。
            if (pickDeviceThenReturn) {
                pickDeviceThenReturn = false
                if (!isFinishing) finish()
            }
        }
        dlg.show()
    }

    private fun promptTargetIp() {
        val edit = EditText(this).apply {
            hint = getString(R.string.ui_main_hint_manual_ip)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_input))
            .setView(edit)
            .setPositiveButton(getString(R.string.ui_common_connect)) { _, _ ->
                val ip = edit.text.toString().trim()
                if (ip.isNotEmpty()) connectTarget(ip, TvControllerClient.PORT, ip, "tv")
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /** 长按底栏「触控板」：选控制目标 / 副屏 / 断开 */
    private fun showTargetMenu() {
        val items = ArrayList<CharSequence>()
        items.add(getString(R.string.ui_main_item_link))
        items.add(getString(R.string.ui_main_item_screen))
        // 全屏操控面（触控板 / 键盘 / 副屏三页）的**第二个入口**：
        // 主页面本身够用，但全屏操控面原先只能从"被控通知"进，这里补上随手可达的一处。
        items.add(getString(R.string.ui_main_item_fullscreen_console))
        if (ControlTarget.isControlling()) items.add(getString(R.string.ui_main_item_disconnect_to_local_local))
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_touchpad))
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showDevicePicker()
                    1 -> startActivity(Intent(this, ScreenActivity::class.java))
                    2 -> startActivity(Intent(this, com.allperiph.touchpad.TouchpadActivity::class.java))
                    3 -> { ControlTarget.clear(); onTargetChanged(); Toast.makeText(this, getString(R.string.ui_main_toast_done_to_local_local), Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /**
     * 出口提示：把"输入此刻到底发去哪了"写到触控板页提示行 + 顶栏徽章 + 设置页状态行。
     *
     * 真机教训：出口是**多级回落**的（无线目标 → USB HID → 蓝牙 → 无出口），任何一级失效
     * 原先都只是日志里一行字，用户看到的现象统一是"点不动"—— 提示必须放在随手可见处。
     */
    override fun renderUplink() {
        if (!::touchHint.isInitialized) return
        // 提示行在滑动开始后会被收起（见 onTouchEvent），这里顺手恢复可见
        touchHint.visibility = android.view.View.VISIBLE
        val base = if (ControlTarget.isControlling()) getString(R.string.ui_main_text_controlling, ControlTarget.label) else getString(R.string.ui_main_text_local)
        val desc = displayUplinkHint()
        touchHint.text = if (displayUplinkPath() == Uplink.NONE) "⚠ $base · $desc" else "$base · $desc"
        tvStatusView?.text = if (ControlTarget.isControlling()) {
            "当前控制：${ControlTarget.label} · $desc"
        } else {
            "未连接（触摸板控本机）· $desc"
        }
    }

    /**
     * 当前**显示用**的出口。
     *
     * 没发送过输入时 [Uplink.current] 还是 `NONE`（它只记录"真实发出去的结果"），此时必须用
     * [Uplink.resolve] 的**预计**出口 —— 否则一进 App 徽章就写"无出口"，又是一处误导。
     */
    override fun displayUplinkPath(): String {
        if (Uplink.observed) return Uplink.current
        val bt = (AgentController.module(ModuleId.BTHID) as? com.allperiph.bt.BtHidDevice)?.isConnected == true
        return Uplink.resolve(
            AgentController.runtime?.hid,
            bt,
            ControlTarget.isControlling() && ControlTarget.controlClient != null,
        )
    }

    /** 出口的一行文案（未发送过输入时按预计出口描述） */
    override fun displayUplinkHint(): String =
        if (Uplink.observed) Uplink.hint() else Uplink.describe(displayUplinkPath())

    /** 目标变化：更新触摸板提示 + 快捷键条（TV 目标时显示 TV 套） */
    override fun onTargetChanged() {
        val controlling = ControlTarget.isControlling()
        renderUplink()
        // 仅 TV 类目标进 TV 专属快捷键布局；控 PC 保持鼠标 + 完整键鼠（不切遥控/媒体套）
        if (controlling && ControlTarget.type == "tv") hotkeyBoard.enterTvMode() else hotkeyBoard.exitTvMode()
        // 连上目标后确保触摸板模块已启动：模块 IDLE 时 feed() 会把手势直接丢掉（连上也控不了）
        if (controlling) AgentController.setModuleEnabled(this, ModuleId.TOUCHPAD, true)
        renderTv()
    }

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun setupSettingsTabs() { settingsFragment?.setupSettingsTabs() }

    // ————————————————————————— 设置页：TV / PC 控制 —————————————————————————

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun renderTv() { settingsFragment?.renderTv() }

    /** 被控端注入通道自检（与 TV 端首页自检对话框同一份数据源） */
    private fun showControlledCaps() {
        val inj = com.allperiph.shared.inject.TvInjector
        val lines = inj.capabilities().joinToString("\n") { (name, ok, how) ->
            if (ok) "✓  $name" else "✗  $name\n      → $how"
        }
        val running = com.allperiph.controlled.ControlledService.isRunning()
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_label_state_channel_inject))
            .setMessage(
                "当前通道：${inj.channelText()}\n" +
                        "被控服务：${if (running) "运行中" else "未运行（在「全屏操控面」里点「被控」开启）"}\n\n" +
                        lines
            )
            .setPositiveButton(getString(R.string.ui_main_btn_a11y_settings)) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .setNegativeButton(getString(R.string.ui_common_close), null)
            .show()
    }

    /** 运行日志（[com.allperiph.shared.util.Log] 的环形缓冲，此前没有 UI 消费者） */
    private fun showLogs() {
        val snap = com.allperiph.shared.util.Log.snapshot()
        val text = if (snap.isEmpty()) {
            "暂无日志 —— 只有达到「日志级别」的记录才会留下"
        } else {
            snap.takeLast(120).joinToString("\n") { e -> "${e.level} ${e.tag}: ${e.msg}" }
        }
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_log, minOf(snap.size, 120)))
            .setMessage(text)
            .setNeutralButton(getString(R.string.ui_common_copy)) { _, _ ->
                // v208：一键复制整段日志（此前只能对着屏幕抄）。写系统剪贴板；
                // 「手机复制 → 电脑粘贴」的剪贴板同步会顺带把它带去电脑（用户主动复制，非回传）。
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("APX-log", text))
                Toast.makeText(this, getString(R.string.ui_common_copied), Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(getString(R.string.ui_common_close), null)
            .show()
    }

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun renderFile() { settingsFragment?.renderFile() }

    private fun pickFileToSend() {
        if (ControlTarget.host.isEmpty() || !ControlTarget.isControlling()) {
            Toast.makeText(this, getString(R.string.ui_main_toast_device_not), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    private fun sendFile(uri: Uri) {
        val host = ControlTarget.host
        Toast.makeText(this, getString(R.string.ui_main_toast_file_send), Toast.LENGTH_SHORT).show()
        Thread({
            val ok = FileSender.send(this, host, uri)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.ui_main_text_file_send_done) else getString(R.string.ui_main_text_device_send_failed_confirm),
                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }, "apx-file").start()
    }

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun renderKeymap() { settingsFragment?.renderKeymap() }

    // ————————————————————————— 设置页 —————————————————————————

    /** 快捷键模板 + 主题方案（数量/内容由数据决定，故运行时构建） */
    private fun buildSettingsPanels() {
        hotkeyBoard.reload() // 含渲染；皮肤取自当前模板色
        renderTemplates()
        renderTheme()
        renderSkins()
        renderFx()
        renderFile()
        renderKeymap()
        onTargetChanged()  // 含 renderTv + 触摸板提示 / 快捷键切换
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
    /** 自建模板色板：与内置模板同一套颜色令牌（`R.color.hotkey_tpl_*`），深浅色自动跟随 */
    private val templatePaletteRes = intArrayOf(
        R.color.hotkey_tpl_office, R.color.hotkey_tpl_ai, R.color.hotkey_tpl_code,
        R.color.hotkey_tpl_video, R.color.hotkey_tpl_slide, R.color.hotkey_tpl_design,
        R.color.hotkey_tpl_term, R.color.hotkey_tpl_sheet,
    )

    /** 色板取当前主题下的实际色值（自定义模板存的是色值，不是资源 id） */
    private fun templatePalette(): IntArray =
        templatePaletteRes.map { resources.getColor(it) }.toIntArray()

    /** 用户自建模板（每次渲染重新读盘，避免与其它入口状态不一致） */
    private var customTemplates: MutableList<HotkeyTemplates.Template> = mutableListOf()

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun toggleTemplates() { settingsFragment?.toggleTemplates() }

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun renderTemplates() { settingsFragment?.renderTemplates() }

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
        box.addView(fieldLabel(getString(R.string.ui_main_label_template)))
        val nameEt = EditText(this).apply {
            hint = getString(R.string.ui_main_hint_hotkey)
            textSize = 15f
            setSingleLine()
            setTextColor(resources.getColor(R.color.md_on_surface))
        }
        box.addView(nameEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        box.addView(fieldLabel(getString(R.string.ui_main_label_theme)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        var pickedIndex = 0
        val dots = mutableListOf<TextView>()
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val palette = templatePalette()
        palette.forEachIndexed { idx, color ->
            val dot = TextView(this).apply {
                background = dotBg(color, idx == 0)
                isClickable = true
                setOnClickListener {
                    pickedIndex = idx
                    dots.forEachIndexed { k, d -> d.background = dotBg(palette[k], k == idx) }
                }
            }
            dots += dot
            colorRow.addView(dot, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
        }
        box.addView(colorRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        box.addView(TextView(this).apply {
            text = getString(R.string.ui_main_text_hotkey_touchpad, hotkeyBoard.shortcuts.size)
            textSize = 12f
            setTextColor(resources.getColor(R.color.md_on_surface_variant))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_template_new))
            .setView(com.allperiph.ui.kit.DialogKit.scrollable(this, box))
            .setPositiveButton(getString(R.string.ui_common_save), null)
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val t = HotkeyTemplates.Template(
                            key = customPrefix + System.currentTimeMillis(),
                            name = nameEt.text.toString().trim().ifEmpty { getString(R.string.ui_main_text_template) },
                            desc = "${hotkeyBoard.shortcuts.size} 项 · 自建",
                            color = palette[pickedIndex],
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

    /** 色点：选中时描深色粗边（形状实现见 [ApKit.oval]） */
    private fun dotBg(color: Int, selected: Boolean): GradientDrawable = ApKit.oval(
        color,
        strokeColor = getColor(if (selected) R.color.dot_stroke_selected else R.color.dot_stroke_normal),
        strokePx = if (selected) dp(3) else dp(1),
    )

    /** 删除自建模板（快捷键条内容不受影响） */
    private fun confirmDeleteTemplate(t: HotkeyTemplates.Template) {
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_template_delete))
            .setMessage(getString(R.string.ui_main_dialog_hotkey_delete, t.name))
            .setPositiveButton(getString(R.string.ui_common_delete)) { _, _ ->
                customTemplates.removeAll { it.key == t.key }
                HotkeyStore.saveCustom(this, customTemplates)
                if (HotkeyStore.loadTemplate(this) == t.key) HotkeyStore.markCustom(this)
                renderTemplates()
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    private fun applyTemplate(t: HotkeyTemplates.Template) {
        HotkeyStore.applyTemplate(this, t)
        hotkeyBoard.reload()
        renderTemplates()
    }

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun renderTheme() { settingsFragment?.renderTheme() }

    /** 状态页渲染已移交 StatusFragment（v196 第 4 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun buildLinkRows() { statusFragment?.buildLinkRows() }

    /** 状态页渲染已移交 StatusFragment（v196 第 4 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun buildModuleRows() { statusFragment?.buildModuleRows() }

    private fun bindActions() {
        // 三个传输开关取代单总开关：打开即整组启用该类下的所有功能并拉起服务；
        // 关闭则整组停用，仅当三类全关才停服务。
        // v196 第 4 步：三个传输开关的 isChecked 与 setOnCheckedChangeListener **移交 StatusFragment**。
        // 两侧都绑定会导致「点一次开关触发两个动作」（Activity 切一次、Fragment 又切一次），
        // 表现为开关闪回、模块行状态错乱。下方 btnBattery / 快捷键编辑 / 手柄 / 遥控 / 副屏
        // 这些入口仍由 Activity 绑定 —— StatusFragment 不管它们。
        // 初始状态直接问自己（Host 实现）；StatusFragment.bindActions() 里也会设一次，
        // 两处同源不会打架 —— 传输开关的**监听**只在 Fragment 侧绑，避免双触发。
        btnBattery.setOnClickListener { requestBatteryWhitelist() }
        // 快捷键「编辑」入口：一屏管理所有快捷键（上移 / 下移 / 改 / 删 / 新建）
        findViewById<TextView>(R.id.btnEditChips).setOnClickListener { hotkeyBoard.managerDialog() }
        // 游戏手柄入口：全屏虚拟摇杆 + 按键，经 USB HID 上报（§2.13 Report ID 22）
        findViewById<TextView>(R.id.btnGamepad).setOnClickListener {
            startActivity(Intent(this, GamepadActivity::class.java))
        }
        // 遥控器入口（真机反馈"找不到遥控器的界面"）：方向盘 + OK/返回/主页/菜单 + 音量/媒体 + 电源。
        // 此前遥控能力只藏在键盘页的「遥控」布局与"选中 TV 才自动切换"的快捷键条里，不易发现。
        // 横屏布局曾漏放此按钮 → findViewById 返回 null → setOnClickListener 抛 NPE → 横屏启动闪退。
        // 这里做空安全兜底，避免布局差异再次导致崩溃。
        findViewById<TextView>(R.id.btnRemote)?.setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        // 副屏入口：全屏显示 PC 推来的桌面画面（Wi‑Fi 媒体通道 streamId=0）
        findViewById<TextView>(R.id.btnScreen).setOnClickListener {
            startActivity(Intent(this, com.allperiph.screen.ScreenActivity::class.java))
        }
        // 环境摘要点开：为什么必须 root、没有 root 还能用什么
        tvDiag.setOnClickListener { showRootHelp() }
        // 模块配色：导出 / 导入 / 分享
        // 方向锁定：顶栏按钮（竖屏快捷）+ 设置页开关（横屏经四指滑可达），两入口由 renderOrientLock 同步
        findViewById<TextView>(R.id.btnOrientLock)?.setOnClickListener {
            Feedback.tap(it)
            toggleOrientLock()
        }
        findViewById<Switch>(R.id.swOrientLock)?.apply {
            isChecked = orientLocked
            setOnCheckedChangeListener { _, on -> setOrientLock(on) }
        }
        // 批P：横屏操作条 pill——触控板/键盘切换 + 方向锁 + 设置（issue 实测：底栏碍事、四指滑不直观）
        // ⚠️ 显隐**不在这里设**：本方法只在 onCreate 跑一次，而 MainActivity 的
        // configChanges 含 orientation —— 旋屏不重建 Activity，于是「竖屏启动 → 转横屏」
        // 这条 pill 永远是 GONE（真机反馈「横屏键盘没有方向锁定和切换触摸板/键盘按键」）。
        // 统一由 applyOrientationLayout() 按朝向设，冷启动与旋屏两条路径同源。
        // 触控目标垫到 48dp：横屏这条 pill 压在键盘上方，太矮会点不中（UI 清单第 10 条）。
        findViewById<TextView>(R.id.btnLandTouchpad)?.minHeight = dp(48)
        findViewById<TextView>(R.id.btnLandKeyboard)?.minHeight = dp(48)
        findViewById<TextView>(R.id.btnLandLock)?.minHeight = dp(48)
        findViewById<TextView>(R.id.btnLandSettings)?.minHeight = dp(48)
        findViewById<TextView>(R.id.btnLandTouchpad)?.setOnClickListener { Feedback.tap(it); showPage(PAGE_TOUCHPAD) }
        findViewById<TextView>(R.id.btnLandKeyboard)?.setOnClickListener { Feedback.tap(it); showPage(PAGE_KEYBOARD) }
        findViewById<TextView>(R.id.btnLandLock)?.setOnClickListener { Feedback.tap(it); toggleOrientLock() }
        findViewById<TextView>(R.id.btnLandSettings)?.setOnClickListener { Feedback.tap(it); showPage(PAGE_SETTINGS) }
        // 右上角设置入口常驻（反馈#2）：竖屏顶栏 btnSettingsEntry、横屏悬浮 btnSettingsFloat，
        // 不再依赖「四指滑 / 底栏页签」才能进设置页。
        findViewById<TextView>(R.id.btnSettingsEntry)?.setOnClickListener {
            Feedback.tap(it)
            showPage(PAGE_SETTINGS)
        }
        findViewById<TextView>(R.id.btnSettingsFloat)?.setOnClickListener {
            Feedback.tap(it)
            // 横屏设置页上它当「返回」用（四指滑之外再给一条退路），其余页面进设置
            showPage(
                if (::pager.isInitialized && pager.displayedChild == PAGE_SETTINGS)
                    PAGE_TOUCHPAD
                else PAGE_SETTINGS
            )
        }
        // 状态已并入设置页（反馈#10）：分段控件与点击绑定移除（视图已在 header_bar 删除）
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

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun renderSkins() { settingsFragment?.renderSkins() }

    /** 设置页渲染已移交 SettingsFragment（v196 第 3 步）：保留同名方法作为转发点，
     *  Activity 内部原有调用点无需改动。 */
    private fun renderFx() { settingsFragment?.renderFx() }

    /** 通用单选弹窗 */
    override fun pickChoice(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i ->
                onPick(i)
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /** 背景：预设底色 / 相册图片 / 清除图片 */
    override fun pickBg() {
        val labels = ThemeSkin.BG_PRESETS.map { it.name } + listOf("从相册选图片…", "清除自定义图片")
        val current = ThemeSkin.BG_PRESETS.indexOfFirst { it.id == ThemeSkin.bgId(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_label_background))
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
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
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
            .onFailure { toast(getString(R.string.ui_main_toast_no_gallery)) }
    }

    /** 把当前背景铺到页面根视图（图片 > 预设色 > 资源底色） */
    override fun applyBackdrop() {
        val root = (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)
            ?: return
        Backdrop.apply(root, getColor(R.color.md_background))
    }

    /** 选配色：跟随主题 + 预设 + 导入进来的自定义；选完重建页面，三个模块一起生效 */
    override fun pickSkin(scope: String, title: String) {
        val skins = listOf(ThemeSkin.followTheme(this)) + ThemeSkin.PRESETS + ThemeSkin.customs(this)
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_main_dialog_color, title))
            .setItems(skins.map { it.name }.toTypedArray()) { _, i ->
                ThemeSkin.set(this, scope, skins[i].id.ifBlank { null })
                recreate() // 触控板光标 / 键盘键帽 / 快捷键芯片一次性换掉
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /** 导出主题文件（走系统文件选择器，可存到下载目录或任意位置） */
    override fun exportTheme() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TITLE, "allperiph-theme.json")
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT_THEME) }
            .onFailure { toast(getString(R.string.ui_main_toast_file_no)) }
    }

    /** 导入主题文件 */
    override fun importTheme() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT_THEME) }
            .onFailure { toast(getString(R.string.ui_main_toast_file_no)) }
    }

    /** 分享主题文本：可直接发给别人 / 传进聊天工具（不需要文件） */
    override fun shareTheme() {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.ui_main_text_theme))
            putExtra(android.content.Intent.EXTRA_TEXT, ThemeSkin.exportJson(this@MainActivity))
        }
        runCatching { startActivity(android.content.Intent.createChooser(intent, getString(R.string.ui_main_dialog_theme_share))) }
            .onFailure { toast(getString(R.string.ui_main_toast_share_no)) }
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
                toast(if (ok) getString(R.string.ui_main_text_theme_export_done) else getString(R.string.ui_main_text_export_failed_empty))
            }
            REQ_IMPORT_THEME -> handleThemeResult(uri)
            REQ_PICK_BG -> handleBgResult(uri)
            REQ_PICK_FILE -> sendFile(uri)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Root 说明（点设置页「环境摘要」弹出） */
    private fun showRootHelp() {
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(R.string.hint_root_dialog_title)
            .setMessage(R.string.hint_root_dialog_body)
            .setPositiveButton(getString(R.string.ui_common_got_it), null)
            .show()
    }

    // ————————————————————————— 事件 —————————————————————————

    private fun subscribe() {
        disposables += EventBus.on<GadgetStateEvent>(handler) { ev ->
            // 挂载失败 / UDC 被占用正在退避重试的**原因**原先被整个丢掉，用户只看到
            // "Gadget 错误"，无从知道是 USB 口被调试占用（真实踩过的坑）。这里如实显示。
            if (ev.state == ModuleState.ERROR || ev.detail.startsWith("retry")) {
                tvDiag.text = if (ev.state == ModuleState.ERROR) ev.detail else "Gadget：${ev.detail}"
                tvDiag.setTextColor(
                    resources.getColor(
                        if (ev.state == ModuleState.ERROR) R.color.state_error else R.color.state_warn
                    )
                )
            }
            refresh()
        }
        disposables += EventBus.on<AgentStateEvent>(handler) { refresh() }
        disposables += EventBus.on<LinkSpeedDegradedEvent>(handler) { ev ->
            tvDiag.text = ev.message
            tvDiag.setTextColor(resources.getColor(R.color.state_warn))
        }
        // 上行出口变化（目标断开 / USB 挂载成功 / 变成无出口…）：立刻刷新提示，
        // 否则用户只能靠"点一下试试"才发现出口早就变了
        disposables += EventBus.on<UplinkEvent>(handler) {
            renderUplink()
            refresh()
        }
    }

    // ————————————————————————— 渲染 —————————————————————————

    /** 传输开关：整组启用 / 停用该类下全部模块，并维护前台服务生命周期 */
    private fun onTransportToggle(t: String, on: Boolean) {
        AgentController.setTransportEnabled(this, t, on)
        for (id in AgentController.groupModules(t)) {
            // v184：多出口模块（触摸板同时挂在「无线」与「USB」两组）只有一个
            // enable.<id>，而两个组的开关都会写它 —— 用户看到的就是
            // "拨一个另一个跟着变"（点开一起开、关一起关）。
            // 现在组开关对多出口模块**只切换本组出口**（exit.<id>.<组>），
            // 模块本身只要任一组开着就保持启用。
            if (AgentController.groupsOf(id).size > 1) {
                AgentController.setExitEnabled(this, id, t, on)
                if (on) AgentController.setModuleEnabled(this, id, true)
                continue
            }
            if (!on) {
                // 关开关时，共享模块（触控板同时挂在无线和 USB 两组）若还被其它**已开启**
                // 的传输用到，就不能停 —— 否则关 USB 会把无线正在用的触控板一起停掉。
                val stillNeeded = AgentController.TRANSPORTS.any { other ->
                    other != t &&
                        AgentController.isTransportEnabled(this, other) &&
                        AgentController.groupModules(other).contains(id)
                }
                if (stillNeeded) continue
            }
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

    /** 桌面小组件上次刷新的状态指纹（"是否连接|设备名"）。
     *  refresh() 是秒级轮询，无脑刷小组件会浪费 IPC —— 只在状态真变了才刷。 */
    private var lastWidgetSig: String? = null

    private fun refresh() {
        // 桌面小组件：连接状态变化时主动刷新（v196 接线 —— 此前 refreshAll 零调用点，
        // 小组件即使被添加到桌面也永远停留在添加那一刻的快照）。
        val widgetSig = "${ControlTarget.isControlling()}|${ControlTarget.label}"
        if (widgetSig != lastWidgetSig) {
            lastWidgetSig = widgetSig
            AllPeriphWidget.refreshAll(this)
        }
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
        // 传输分类页（无线 / 蓝牙 / 有线）的显隐**不在这里**：它跟其它分类一样由
        // SettingsFragment 的导航控制。原先这里按 isTransportEnabled 直接置 group* 可见 ——
        // 那正是「点左侧传输按钮进分类」要反过来用的一处（刚切过去就被下一秒刷回 GONE）。
        // 该类是否显示模块行，由 StatusFragment.refresh() 按同一个开关每秒回读。

        val st = AgentController.overallState()
        tvOverall.text = stateLabel(st)
        tvOverall.setTextColor(resources.getColor(stateColor(st)))
        // 顶部状态徽章与总状态同步（文本 + 语义色），并**带上当前上行出口** ——
        // 徽章是一直可见的那一处，"点不动"时用户第一眼就看这里。出口只剩"无出口"时用错误色。
        if (::badgeStatus.isInitialized) {
            val path = displayUplinkPath()
            badgeStatus.text = "${stateLabel(st)} · ${Uplink.label(path)}"
            badgeStatus.setTextColor(
                resources.getColor(
                    if (path == Uplink.NONE) R.color.state_error else stateColor(st)
                )
            )
        }
        val envSummary = lastEnv?.summary ?: getString(R.string.common_unknown)
        // 不再报「启用模块 N 个」：那是内部模块位图计数，用户读不出任何信息
        // （想细看有哪些功能，去「设置 → 功能开关」）
        tvOverallSub.text = envSummary
        // 运行摘要的链路口径按实际通道：有 USB 速度才显示 USB 档位；纯蓝牙时提示配对。
        // 原文案固定走 USB 分支，没插线也会显示"建议更换 USB 3.0 线缆"，误导。
        val btRunning = AgentController.module(ModuleId.BTHID)?.state?.isActive == true
        val usbSpeed = lastEnv?.linkSpeed ?: LinkSpeed.UNKNOWN
        val linkText = when {
            usbSpeed.isSuperSpeed -> getString(R.string.hint_usb_ok)
            usbSpeed != LinkSpeed.UNKNOWN -> getString(R.string.hint_usb2)
            btRunning -> "蓝牙已就绪，在电脑上配对后就能用"
            else -> getString(R.string.common_unknown)
        }

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
            append(" · ").append(if (env.superSpeed) "USB 3.0" else "USB 2.0")
            append(" · ").append(if (env.batteryOptimized) getString(R.string.ui_main_text_not) else getString(R.string.ui_main_text_done_2))
            if (!env.notificationGranted) append(" · 通知未授权 ✗")
            append("\n详细的连接状态见「状态」页")
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

    /**
     * 收到受控端（PC / TV）回传的剪贴板（0x21 反向剪贴板）。
     *
     * v1.34：此前只弹一句「对端复制：xxx」——**手机系统剪贴板并没有变**，用户以为同步了，
     * 切到别的 App 粘贴还是旧内容。现在与操控面 [TouchpadActivity] 的行为一致：真正写进
     * 系统剪贴板；提示语只给前 24 字预览，避免长文本/敏感内容整段糊在屏幕上。
     */
    private fun recvReverseClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // ⚠️ 必须先打抑制标记：本函数写的是「来自 PC 的剪贴板」，而 ClipboardSync 监听的是
        // 「本机剪贴板变化 → 发给 PC」。不打标记就会立刻原样回传，形成死循环
        // （剪贴板同步 v196 接线时补上）。
        ClipboardSync.markProgrammaticWrite()
        cm?.setPrimaryClip(ClipData.newPlainText("APX", text))
        val preview = if (text.length > 24) text.take(24) + "…" else text
        Toast.makeText(this, getString(R.string.ui_main_toast_clipboard_done, preview), Toast.LENGTH_SHORT).show()
    }

    // ————————————————————————— 样式工具 —————————————————————————

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(resources.getColor(R.color.md_on_surface_variant))
    }

    /**
     * 以下样式工具的实现统一收在 [ApKit] —— 此前每个 Activity 各写一份 GradientDrawable，
     * 「圆角多少、描边几像素」这类样式改动要在多处分别落地。这里保留签名只为不动调用点。
     */
    override fun card(color: Int, radius: Int): GradientDrawable =
        ApKit.shape(color, radius.toFloat())

    override fun strokeCard(color: Int, radius: Int, stroke: Int): GradientDrawable =
        ApKit.shape(color, radius.toFloat(), strokeColor = stroke, strokePx = ApKit.STROKE_HAIRLINE_PX)

    override fun pill(color: Int): GradientDrawable = card(color, dp(999))

    override fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MainActivity"
        private const val TICK_MS = 1_000L
        private const val REQ_NOTIFICATION = 1001

        /** orientPoll re-request 方向的冷却窗口（防每秒请求风暴，见 orientPoll 注释） */
        private const val POLL_FIX_COOLDOWN_MS = 3_000L

        /** 被系统掰回方向后，物理检测放行重试的最短间隔（防卡死、防循环，见物理检测守卫） */
        private const val RETRY_STUCK_MS = 30_000L

        /**
         * 方向切换状态**跨 Activity 实例保持**：它们曾是实例字段，Activity 因重建
         * 重置后物理检测会重新发起方向切换，与系统回弹/重建叠加成
         * 「切横 → 被掰回 + 重建 → forcedOrient 归零 → 再切横」无限循环 ——
         * 真机表现为两核满频 / 10W / 只能杀进程（2026-10-24 事故，日志见
         * applyOrientationLayout 与物理朝向切换交替刷屏）。
         */
        @Volatile private var forcedOrient = 0          // 0=尚未强制 1=已强制横屏 2=已强制竖屏
        @Volatile private var lastOrientSwitchMs = 0L

        /**
         * 用户方向锁定（顶栏按钮 / 设置页开关）：true = 冻结当前朝向 ——
         * 物理检测与兜底轮询全部暂停。放 companion 跨重建保持（与 forcedOrient 同理，
         * 实例字段会随重建归零 → 重建后锁失效），并由 SharedPreferences 持久化到重启后。
         */
        @Volatile private var orientLocked = false
        /** 锁定时刻锁定的朝向（1=横 2=竖），用于顶栏按钮文案与恢复 */
        @Volatile private var lockedOrient = 0

        /** 方向锁定持久化 */
        private const val PREF_ORIENT = "apx_orient_lock"
        private const val KEY_LOCK = "locked"

        /** 旋屏重建时保存/恢复当前页签 */
        private const val KEY_PAGE = "page"

        private const val PAGE_TOUCHPAD = 0
        private const val PAGE_KEYBOARD = 1
        // 状态页已并入设置页（反馈#10）：设置成为第 3 个也是最后一个孩子
        private const val PAGE_SETTINGS = 2

        /** 三指手势遮蔽的持有者标识（与 TouchpadActivity 的 console 区分开） */
        private const val HOLDER_MAIN = "main"

        /**
         * 底栏页签 → ViewFlipper 页面 index 的映射。
         * 页签：触控板(0) / 键盘(1) / 设置(2)。
         */
        private val TAB_PAGES = intArrayOf(PAGE_TOUCHPAD, PAGE_KEYBOARD, PAGE_SETTINGS)

        /** 横屏切页手势的手指数（四指，避开触控板自身的一~三指手势） */
        private const val FINGERS_TO_SWITCH = 4

        /** 页签标题与副说明（与 activity_main.xml 的四页顺序一致） */
        /** 启动 MainActivity 时带上它 = **只来选控制设备**（选完/取消自动 finish）。
         *  由「遥控器」页的「选择设备」使用 —— 见 [maybePickDevice]。 */
        const val EXTRA_PICK_DEVICE = "apx_pick_device"

        /**
         * 页面标题 / 副标题：存**资源 ID** 而不是字符串。
         * companion object 里没有 Context，在这儿直接 getString 编译不过（这里翻过一次车）；
         * 真正的取字符串放在有 Context 的 [showPage] 里做。
         */
        private val PAGE_TITLES = intArrayOf(
            R.string.ui_main_item_touchpad,        // 触控板
            R.string.ui_touchpad_label_keyboard,   // 键盘
            R.string.section_settings,             // 设置
        )
        private val PAGE_SUBS = intArrayOf(
            R.string.ui_main_item_hotkey_send_cursor_swipe,
            R.string.ui_touchpad_label_remote,
            R.string.ui_main_item_template_theme,
        )
    }
}

