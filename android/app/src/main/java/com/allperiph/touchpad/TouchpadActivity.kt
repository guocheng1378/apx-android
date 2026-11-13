package com.allperiph.touchpad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.controlled.ControlledService
import com.allperiph.core.ModuleId
import com.allperiph.hid.HidKeys
import com.allperiph.shared.net.FileSender
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.ui.AgentController
import com.allperiph.ui.ApPalette
import com.allperiph.ui.HotkeyBoard
import com.allperiph.ui.HotkeyController
import com.allperiph.ui.KeyPref
import com.allperiph.ui.compose.ApTouchpadSurface
import com.allperiph.ui.compose.rememberApBackdrop
import com.allperiph.ui.miuix.ApComponents
import top.yukonga.miuix.kmp.basic.Text
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.ui.miuix.rememberApInsets
import com.allperiph.ui.miuix.applyEdgeToEdge
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvControllerClient
import com.allperiph.wireless.TvDiscovery
import com.allperiph.wireless.WirelessModule
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 触控板操控面（三页签：触控板 / 键盘 / 副屏）—— v223.29.55 起整体迁 Compose + Miuix，
 * 与主壳（ApShellActivity 四页签）同一套设计体系。
 *
 * ## 迁移说明
 *  · **展示**全部换成 Compose（[ConsoleRoot] 起）；类名 / 清单入口 / 页签结构 /
 *    全部行为（三页签、全屏、方向键叠层、8 套键位、长按连发、修饰键 sticky、
 *    音量连发、发文件 / 剪贴板、设备选择、被控开关、自检弹窗、边缘翻页、
 *    全屏上沿下滑退出）逐条保留；
 *  · **事件**仍走同一条 `TouchpadModule.feed(MotionEvent)` —— 手势识别是模块层逻辑
 *    （touchpad/ 包），迁移不改它；手势面复用主壳的 [ApTouchpadSurface]（唯一实现），
 *    长按 550ms 拖拽 / 双指长按右键拖拽由它统一调度；
 *  · 快捷键网格沿用 View 版 [HotkeyBoard]（轻点发送 / 长按编辑 / 拖拽排序全在里面），
 *    经 AndroidView 挂进 Compose —— 行为与主页同一份实现，不另起炉灶；
 *  · 全屏模式沿用 v223.2 的界面方案（系统栏收起 + 只留手势面 + 右下角退出胶囊 +
 *    一次性说明），系统栏控制逻辑原样保留；
 *  · edge-to-edge 常态按主壳新口径：系统栏显示且透明，内容经 [rememberApInsets] 自行垫边
 *    （旧版常态隐藏状态栏的口径已随 2026-11-07 全仓反转）。
 *
 * 触摸兜底（v1.7b 架构保留）：仅第 0 页且非边缘手势才把事件喂给触控引擎；
 * 左右边缘 28dp 起滑 80dp 翻页。与副屏互斥（架构 §4）。
 * 发送通道：无线走蓝牙 HID，有线走 HID TLC（Report ID 21 键盘 / 4 媒体）。
 */
class TouchpadActivity : ComponentActivity() {

    /** 文件选择请求码（传统 startActivityForResult：要兼容 suspend 前的生命周期语义） */
    private val REQ_PICK_FILE = 9001

    /** 跨重组共享的 UI 状态：Activity 的命令式逻辑直接改，Compose 侧观察展示 */
    internal class ConsoleUi {
        var page by mutableIntStateOf(0)
        var fullscreen by mutableStateOf(false)
        // 审计批4（2026-11-12）：原 kbTab 随退役键盘组件（ConsoleKeyboardPage 等）一并删除
        // —— 键盘页现直接渲染主壳 ApFullKeyboard，页签由其自管。
        var hidReady by mutableStateOf(false)
        /** 顶部状态胶囊：HID 状态 / 触摸帧数 / 发文件进度共用一行 */
        var statusText by mutableStateOf("")
        var deviceReady by mutableStateOf(false)
        var deviceText by mutableStateOf("")
        var controlledOn by mutableStateOf(false)
        /** 副屏页状态行（进入该页 / 旋转后刷新一次） */
        var displayStatus by mutableStateOf("")
        /** sticky 修饰键重绘触发器：tap / toggleSticky 后自增 */
        var modVersion by mutableIntStateOf(0)
    }

    private val ui = ConsoleUi()
    private val handler = Handler(Looper.getMainLooper())

    /** 实体键盘/指针转发桥（v223.13：全屏操控面此前从没接过 —— issue #52 第 6 条）。 */
    private var physKeyBridge: com.allperiph.ui.PhysKeyBridge? = null

    /**
     * 出口变化订阅（issue#168 第 8 条）：顶部设备胶囊此前只在进页与连接动作时刷新，
     * USB 模式下出口中途变成「无」（gadget 掉线 / 写入持续被拒）时界面一直停在旧文案，
     * 用户只看到「点不动」却没有任何提示。订阅 [com.allperiph.core.UplinkEvent] 让
     * 胶囊跟着出口实时变，掉出无出口时当场能看到原因（Uplink.hint 的具体文案）。
     */
    private var uplinkSub: com.allperiph.shared.event.EventBus.Disposable? = null

    /** 快捷键网格容器（View 版 HotkeyBoard 渲染进这里，经 AndroidView 挂上屏幕） */
    internal val hotkeyContainerView by lazy { android.widget.LinearLayout(this) }

    /** 快捷键网格：行为统一在 ui/HotkeyBoard（与主页共用同一份实现） */
    internal val hotkeyBoard: HotkeyBoard by lazy {
        HotkeyBoard(this, hotkeyContainerView, ::boardStyle)
    }

    /** 操控面版皮肤：中性色（与主页的模板色区分开）。每行个数随横竖屏走（issue#168 第 1 条；
     *  issue#169 #10「横屏所有快捷键区域统一每行 3 键、每键占 2 字符」）：
     *  竖屏 4 个/行；横屏热键栏变成右侧窄列，与主壳/键盘页/悬浮面板同口径改 3 个/行
     *  （1 位 ≈ 2 个中文字符，即 packBySlots 的「每 2 字占 1 位」），长文案照旧末尾省略。
     *  渲染随整页重建走。 */
    internal fun boardStyle(): HotkeyBoard.Style {
        val land = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return HotkeyBoard.Style(
            chipText = getColor(R.color.miuix_text),
            chipBg = getColor(R.color.miuix_card),
            chipStroke = getColor(R.color.miuix_stroke),
            chipRadiusDp = 14,
            padVDp = 13,
            perRow = if (land) 3 else 4,
            accent = kbAccent(this),
            textPrimary = getColor(R.color.miuix_text),
            textSecondary = getColor(R.color.miuix_text_secondary),
        )
    }

    // —————————————————————————— 生命周期 ——————————————————————————

    /** 主题方案：把用户在设置页选的主题注入资源配置（深色取值在 values-night） */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.allperiph.ui.ThemePref.wrap(newBase))
    }

    /**
     * v184：清掉挂在全局 ControlTarget 上的回调。
     * 那个闭包捕获了本 Activity（runOnUiThread { recvClipboard(...) }），
     * 而本页此前**没有任何生命周期清理** —— Activity 销毁后仍被静态对象引用
     * （Activity 与整棵视图树一起泄漏），回调还会继续往已销毁的界面推字。
     * 只清回调，**不断开控制连接**（切页面不该掉链路）。
     */
    override fun onDestroy() {
        physKeyBridge?.detach(); physKeyBridge = null
        uplinkSub?.dispose(); uplinkSub = null
        ControlTarget.controlClient?.onReverseClipboard = null
        // 退出操控面立刻还原系统三指手势（进程被杀的情况交给 App 启动时的自愈）
        GestureShield.release(this, HOLDER_CONSOLE)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
        // 强调色要叠在「正在用的这个主题」上才算数（View 弹窗：自检 / 关机确认 仍走主题）
        ApPalette.applyRuntimeTheme(this)
        // 截屏已放开（与主页口径一致）：主控制面此前挂 FLAG_SECURE，用户没法截图反馈界面问题
        ui.hidReady = AgentController.runtime?.hid?.isReady() == true
        ui.statusText = getString(
            if (ui.hidReady) R.string.ui_touchpad_text_no_frames else R.string.ui_touchpad_text_not,
        )
        updateControlledChip()
        updateDeviceChip()
        // 出口变化 → 设备胶囊实时刷新（onMain 保证在主线程；注释见 uplinkSub 字段）
        uplinkSub = com.allperiph.shared.event.EventBus.onMain<com.allperiph.core.UplinkEvent> {
            updateDeviceChip()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            /** 返回键先确认：操控面退出后外设照常运行，但误触退出会打断手上的操作 */
            private var backDialogShown = false

            @Deprecated("Deprecated in Java")
            override fun handleOnBackPressed() {
                // 全屏时返回键先退出全屏（这里连确认框都不该弹：用户只是想回到控制面）
                if (ui.fullscreen) {
                    setFullscreen(false)
                    return
                }
                if (backDialogShown) return
                backDialogShown = true
                com.allperiph.ui.ApDialog.builder(this@TouchpadActivity)
                    .setTitle(getString(R.string.ui_touchpad_dialog_exit_console))
                    .setMessage(getString(R.string.ui_touchpad_dialog_home))
                    .setPositiveButton(getString(R.string.ui_common_exit)) { _, _ -> finish() }
                    .setNegativeButton(getString(R.string.ui_common_cancel), null)
                    .setOnDismissListener { backDialogShown = false }
                    .show()
            }
        })

        setContentView(
            androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        ConsoleRoot(ui = ui, host = this@TouchpadActivity)
                    }
                }
            },
        )
        // 实体键盘 / 实体触控板转发桥：全屏操控面是独立 Activity，主页触控板页的
        // PhysKeyBridge（TouchpadFragment 里 attach）管不到这里 —— 不接的话全屏里
        // 实体键盘按键、实体触控板 hover/指针事件全部落进系统默认路径
        // （issue #52 第 6 条）。
        physKeyBridge = com.allperiph.ui.PhysKeyBridge(this).also { it.attach(window) }
        // 主页「设置 → 管理快捷键」用 EXTRA_PAGE 直达键盘页；launchMode=singleTask，
        // 已在栈中时走 onNewIntent
        showPage(intent?.getIntExtra(EXTRA_PAGE, PAGE_TOUCHPAD) ?: PAGE_TOUCHPAD)
        // 预启动 TV 发现（监听 APX1TV 信标），让设备列表在打开选择器时已就绪
        TvDiscovery.start()
    }

    /** 只在前台时遮蔽三指手势：退到后台（Home / 切应用 / 锁屏）立刻还原 ——
     *  否则用户在桌面想三指下拉截屏时会发现按不动。 */
    override fun onResume() {
        super.onResume()
        // 与主页同口径：每次回前台复核转发桥（窗口回调被换掉就重新包上）
        physKeyBridge?.ensureAttached(window)
        GestureShield.acquireWithHint(this, HOLDER_CONSOLE)
        // 注：服务层悬浮栏已随整合拆除（FloatingBallService 顶注释）——
        // 此前进本页球+栏双窗同屏的问题从根上消失，桌面只悬浮球一个控件。
    }

    override fun onPause() {
        GestureShield.release(this, HOLDER_CONSOLE)
        // 退后台会丢掉后续的「按键抬起」：放掉仍按住的键，避免电脑侧 Ctrl / Shift 粘住
        physKeyBridge?.releaseStuckKeys()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showPage(intent.getIntExtra(EXTRA_PAGE, PAGE_TOUCHPAD))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { sendFile(it) }
        }
    }

    // —————————————————————————— 页面切换 ——————————————————————————

    internal fun showPage(i: Int) {
        if (i !in 0..PAGE_DISPLAY) return
        // 全屏只服务于触控板页：切页前先退出全屏（页签栏在全屏里是隐藏的，
        // 只有「从别处回本页」这类路径会撞上，兜一手免得停在半截状态）
        if (ui.fullscreen && i != PAGE_TOUCHPAD) setFullscreen(false)
        ui.page = i
        if (i == PAGE_TOUCHPAD) hotkeyBoard.render()
        if (i == PAGE_DISPLAY) updateDisplayStatus()
    }

    // —————————————————————————— 触摸兜底（v1.7b 架构保留） ——————————————————————————

    // 边缘滑动翻页状态
    private var edgeStartX = -1f
    private var edgeSwiped = false

    // 全屏上沿下滑退出：这一整段手势都吞掉，不喂手势引擎（否则退出动作会顺手甩光标）
    private var fsSwallow = false
    /** 已经因这条手势退出全屏了（但手势还没抬，仍要继续吞到 UP —— 不能半路开始喂引擎） */
    private var fsExiting = false
    private var fsStartY = 0f

    private var frameCount = 0L
    private var lastShown = 0L

    /**
     * 触控板页手势入口（挂在 [ApTouchpadSurface] 的 onGesture 上）：
     * 全屏上沿下滑退出 → 边缘翻页 → 剩余事件原样喂触控引擎。
     */
    fun onSurfaceGesture(ev: MotionEvent) {
        // 全屏下「从屏幕最上沿往下拉」= 退出全屏（附加路径，系统手势导航可能先吃掉这一步）。
        // 这段手势整段吞掉、绝不喂手势引擎 —— 中途开始喂引擎会送出一个没有 DOWN 的 MOVE。
        if (ui.fullscreen) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (ev.y < dp(24)) {
                    fsSwallow = true
                    fsStartY = ev.y
                }
                MotionEvent.ACTION_MOVE -> if (fsSwallow && !fsExiting && ev.y - fsStartY > dp(56)) {
                    fsExiting = true
                    setFullscreen(false)
                }
            }
            if (fsSwallow) {
                if (ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    fsSwallow = false
                    fsExiting = false
                }
                return
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwiped = false
                val w = resources.displayMetrics.widthPixels
                // 全屏里不做边缘翻页：不动的触控板上，左右边缘跟别处没有区别，
                // 留着只会变成「滑到边上页面跑了」
                edgeStartX = if (!ui.fullscreen && ui.page == PAGE_TOUCHPAD &&
                    (ev.x < dp(28) || ev.x > w - dp(28))
                ) {
                    ev.x
                } else {
                    -1f
                }
            }
            MotionEvent.ACTION_MOVE -> if (edgeStartX >= 0f && !edgeSwiped) {
                val dx = ev.x - edgeStartX
                if (abs(dx) > dp(80)) {
                    edgeSwiped = true
                    showPage(
                        if (dx < 0) (ui.page + 1) % 3 else (ui.page + 3 - 1) % 3,
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeStartX = -1f
        }
        // 仅触控板页且非边缘手势才喂手势引擎
        if (ui.page == PAGE_TOUCHPAD && edgeStartX < 0f && !edgeSwiped) {
            (AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule)?.feed(ev)
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastShown > 250) { // 4Hz 刷新，避免 UI 抖动
                lastShown = now
                if (ui.hidReady) ui.statusText = getString(R.string.ui_touchpad_text_done, frameCount)
            }
        }
    }

    /** 长按 550ms → 拖拽锁定（[ApTouchpadSurface] 调度，喂给触控模块） */
    fun armDragNow() {
        (AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule)?.armDrag()
    }

    /** 双指长按 550ms → 右键拖拽锁定 */
    fun armRightDragNow() {
        (AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule)?.armRightDrag()
    }

    /**
     * 绝对指针出口（2026-11-13「指哪打哪」第 2 步，小地图叠层专用）：
     * 0x06 帧只走无线控制链路 [TvControllerClient]，其余控制客户端没有这条通道。
     * action/buttons/x/y 语义与 0x04 相同（0 按下 / 1 抬起 / 2 移动，x/y 归一化 0..65535）。
     * 发送失败（未在控 / 链路断 / 对端明确不支持）时：移动帧静默丢弃——拖动路径
     * 一秒几十帧，逐帧弹 Toast 只会刷屏；点击类帧（down/up）弹提示，让用户知道
     * 「点了没反应」是连接问题而不是地图坏了。
     */
    fun sendAbsoluteTouch(action: Int, buttons: Int, x: Int, y: Int) {
        val c = ControlTarget.controlClient as? TvControllerClient
        val ok = if (c != null && ControlTarget.isControlling()) {
            c.absoluteTouch(action, buttons, x, y)
        } else {
            false
        }
        if (!ok && action != 2) {
            Toast.makeText(this, R.string.ui_touchpad_minimap_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 小地图展开前的一次性可用性检查：没在控、或对端报过 0x33 能力位图但没带
     * CAP_ABSOLUTE_POINTER（bit5）时返回 false —— 这两种情况点了地图也白发。
     * peerCaps==0（老版本没报过能力）按协议口径视为「照发兜底」，不算不支持。
     */
    fun absolutePointerReady(): Boolean {
        if (!ControlTarget.isControlling()) return false
        val c = ControlTarget.controlClient as? TvControllerClient ?: return false
        return c.peerCaps == 0L || c.peerCaps and ApxFrame.CAP_ABSOLUTE_POINTER != 0L
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // —————————————————————————— 全屏触控板 ——————————————————————————

    /**
     * 切换全屏触控板（v223.2，#47 第 1 条）。
     *
     * 为什么是界面方案而不是真锁光标：Android 没给第三方 App「把系统光标限制在某区域」的
     * API —— 应用层只能把本窗口光标图标设成 TYPE_NULL。所以让光标即使存在也没有可误触的
     * 控件，是唯一不依赖 root 的做法。
     *
     * 进出只改可见性状态（Compose 按状态重组）；退出的**可靠**路径两条：返回键、
     * 右下角小胶囊（3s 后自动降到 25% 不透明度）。上沿下滑是顺手留的附加路径 ——
     * 手势导航的设备上会被系统拿去拉通知栏，**不写进提示文案**。
     */
    fun setFullscreen(on: Boolean) {
        if (ui.fullscreen == on) return
        ui.fullscreen = on
        // 系统栏：全屏连导航栏一起收（否则底部那条常年占着 40dp，还容易被误滑出来）。
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 保留「边缘滑动临时唤出」，用户随时能切走。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ic = window.insetsController
            if (ic != null) {
                if (on) {
                    ic.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ic.hide(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                } else {
                    ic.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_DEFAULT
                    ic.show(android.view.WindowInsets.Type.navigationBars())
                    ic.hide(android.view.WindowInsets.Type.statusBars())
                }
            }
        }
    }

    // —————————————————————————— 设备选择（控制目标） ——————————————————————————

    private fun updateDeviceChip() {
        ui.deviceReady = ControlTarget.isControlling()
        // 出口一并显示：点不动时先看这里到底"发去哪了"。这里展示的是**刷新那一刻**的出口；
        // 实时显示在主界面顶栏徽章与触控板提示行。
        ui.deviceText =
            (if (ui.deviceReady) getString(R.string.ui_touchpad_text_device_2, ControlTarget.label) else ControlTarget.label) +
                " · ${com.allperiph.core.Uplink.hint()}"
    }

    // —————————————————————————— 被控模式（本机可被另一台手机控制） ——————————————————————————

    private fun updateControlledChip() {
        ui.controlledOn = ControlledService.isRunning()
    }

    /** 切换被控模式：开启→请求权限并启动前台服务；关闭→停止服务 */
    internal fun toggleControlled() {
        if (ControlledService.isRunning()) {
            ControlledService.stop(this)
            updateControlledChip()
            Toast.makeText(this, getString(R.string.ui_touchpad_toast_close_done), Toast.LENGTH_SHORT).show()
            return
        }
        ControlledService.start(this)
        updateControlledChip()
        openControlledPerms()
    }

    /** 引导用户开启「无障碍」与「显示在其他应用上层」——系统级注入的前置权限 */
    private fun openControlledPerms() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: Throwable) {
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Toast.makeText(this, R.string.controlled_enable_hint, Toast.LENGTH_LONG).show()
    }

    /** 收到被控手机回传的剪贴板：写入本机系统剪贴板（反向剪贴板，实现「互用」） */
    private fun recvClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("APX", text))
        // 内容去重（2026-11-14 真机「粘贴后无限循环」）：本机监听看到这段内容不能当
        // 「用户又复制了」再发回去 —— 写入本来就是回传的落地，不记去重就 A↔B 无限弹
        com.allperiph.ui.ClipboardSync.markRemoteWritten(text)
        val preview = if (text.length > 24) text.take(24) + "…" else text
        Toast.makeText(this, getString(R.string.ui_touchpad_toast_clipboard, preview), Toast.LENGTH_LONG).show()
    }

    /** 设备选择弹窗：本机/PC（默认）+ 已发现 TV + 手动输入 IP */
    internal fun showDevicePicker() {
        TvDiscovery.start()
        val items = ArrayList<CharSequence>()
        items.add(getString(R.string.ui_main_item_local))
        val tvs = TvDiscovery.list()
        tvs.forEach { items.add("${it.name}  ${it.ip}  ${it.typeLabel}") }
        items.add(getString(R.string.ui_main_item_input_manual))
        com.allperiph.ui.ApDialog.builder(this)
            .setTitle(getString(R.string.ui_main_dialog_device_pick))
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        ControlTarget.clear()
                        updateDeviceChip()
                    }
                    which == items.size - 1 -> promptTvIp()
                    else -> {
                        val tv = tvs.getOrNull(which - 1) ?: return@setItems
                        connectTv(tv.ip, tv.port, tv.name, tv.type)
                    }
                }
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    private fun promptTvIp() {
        val edit = android.widget.EditText(this).apply {
            hint = getString(R.string.ui_touchpad_hint_manual_ip)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        com.allperiph.ui.ApDialog.builder(this)
            .setTitle(getString(R.string.ui_touchpad_dialog_input))
            .setView(edit)
            .setPositiveButton(getString(R.string.ui_common_connect)) { _, _ ->
                val ip = edit.text.toString().trim()
                if (ip.isNotEmpty()) connectTv(ip, TvControllerClient.PORT, ip, "tv")
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /** 后台连入 TV/PC 并设为当前控制目标（不阻塞 UI）
     * @param type 设备类型 "tv" 或 "pc"：仅 TV 切到 TV 专属快捷键套，PC 保持鼠标 + 完整键鼠 */
    private fun connectTv(ip: String, port: Int, name: String, type: String = "tv") {
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
                    // 先断开旧连接：否则旧实例仍在 running，readerLoop 会每 2s 无限重连旧 IP，
                    // 并留下 socket + tx/ping/reconn 线程（与主页同源的一处修复）
                    ControlTarget.controlClient?.takeIf { it !== c }?.disconnect()
                    ControlTarget.controlClient = c
                    ControlTarget.host = ip
                    ControlTarget.label = name
                    ControlTarget.type = type
                    c.onReverseClipboard = { text -> runOnUiThread { recvClipboard(text) } }
                    // 仅 TV 目标进 TV 专属快捷键布局；PC 退出 TV 模式（保持鼠标 + 完整键鼠）
                    if (type == "tv") hotkeyBoard.enterTvMode() else hotkeyBoard.exitTvMode()
                    updateDeviceChip()
                    Toast.makeText(this, getString(R.string.ui_main_toast_done, name), Toast.LENGTH_SHORT).show()
                } else {
                    ControlTarget.clear()
                    hotkeyBoard.exitTvMode()
                    updateDeviceChip()
                    // HIG 落地批次二：失败给可操作的出路 —— 就地弹「重试」，一次点击原样重连，
                    // 文案与设备选择器（批次一）同一口径；不用转瞬即逝的 toast 单独扛失败反馈。
                    com.allperiph.ui.ApDialog.confirm(
                        this,
                        getString(R.string.ui_pick_device_connect_failed, ip),
                        null,
                        getString(R.string.ui_pick_device_retry),
                        getString(R.string.ui_common_cancel),
                    ) { connectTv(ip, port, name, type) }
                }
            }
        }, "tv-connect").start()
    }

    /** 发文件到对端设备文件接收通道（独立端口 9512） */
    private fun sendFile(uri: Uri) {
        val host = ControlTarget.host
        if (host.isEmpty() || !ControlTarget.isControlling()) {
            Toast.makeText(this, getString(R.string.ui_main_toast_device_not), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.ui_main_toast_file_send), Toast.LENGTH_SHORT).show()
        Thread({
            val ok = FileSender.send(this, host, uri) { p ->
                runOnUiThread { ui.statusText = getString(R.string.ui_touchpad_send_progress, p) }
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) getString(R.string.ui_main_text_file_send_done) else getString(R.string.ui_touchpad_text_send_failed), Toast.LENGTH_SHORT).show()
                ui.statusText = getString(
                    if (ui.hidReady) R.string.ui_touchpad_text_no_frames else R.string.ui_touchpad_text_not,
                )
            }
        }, "tv-file").start()
    }

    /** 读本机剪贴板并发送到对端设备（写入对方系统剪贴板 + 当前聚焦输入框） */
    internal fun sendClipboard() {
        val c = ControlTarget.controlClient
        if (c == null || !ControlTarget.isControlling()) {
            Toast.makeText(this, getString(R.string.ui_main_toast_device_not), Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.ui_touchpad_toast_clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val ok = c.sendClipboard(text)
        Toast.makeText(this, if (ok) getString(R.string.ui_touchpad_text_clipboard_send_done) else getString(R.string.ui_touchpad_text_send_failed), Toast.LENGTH_SHORT).show()
    }

    /** 顶栏「发文件」入口：拉起系统文件选择器，选中后走 [sendFile] */
    internal fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    /** Consumer 键点按：PC 按位图边沿识别，故 按下 60ms → 释放 */
    fun mediaTap(bit: Int) {
        HotkeyController.press(bit)
        handler.postDelayed({ HotkeyController.release(bit) }, 60)
    }

    /** 关机 / 重启确认：会直接关掉被控设备，别让误触生效 */
    internal fun confirmPower(action: Int, label: String) {
        val c = ControlTarget.controlClient
        if (c == null) {
            Toast.makeText(this, getString(R.string.ui_touchpad_toast_device_connect_not), Toast.LENGTH_SHORT).show()
            return
        }
        com.allperiph.ui.ApDialog.builder(this)
            .setTitle(getString(R.string.ui_touchpad_dialog_device, label))
            .setMessage(getString(R.string.ui_touchpad_dialog_no_only_need, label))
            .setPositiveButton(label) { _, _ ->
                c.power(action)
                Toast.makeText(this, getString(R.string.ui_touchpad_toast_send_done, label), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /** 注入通道自检：被控端"缺哪一项"在手机上原先只有通知栏一行字（被控开关长按触发） */
    internal fun showInjectCaps() {
        val inj = com.allperiph.shared.inject.TvInjector
        val lines = inj.capabilities().joinToString("\n") { (name, ok, how) ->
            if (ok) "✓  $name" else "✗  $name\n      → $how"
        }
        com.allperiph.ui.ApDialog.builder(this)
            .setTitle(getString(R.string.ui_touchpad_dialog_selftest_channel_inject))
            .setMessage(getString(R.string.ui_touchpad_dialog_control_state, lines, inj.channelText()))
            .setPositiveButton(getString(R.string.ui_common_close), null)
            .show()
    }

    internal fun updateDisplayStatus() {
        ui.displayStatus = WirelessModule.statusText()
    }

    internal companion object {
        /** 启动参数：直接落到指定页签（0 触控板 / 1 键盘 / 2 副屏） */
        const val EXTRA_PAGE = "page"

        const val PAGE_TOUCHPAD = 0
        const val PAGE_KEYBOARD = 1
        const val PAGE_DISPLAY = 2

        /** 三指手势遮蔽的持有者标识（与主页的 main 区分开） */
        const val HOLDER_CONSOLE = "console"
    }
}

// —————————————————————————— Compose 面板共用取色 ————————————————————————————

/** 键盘强调色：设置页可单独选一套皮肤；不选时保持主色系（与旧 TouchpadActivity 同口径） */
private fun kbAccent(ctx: Context): Int =
    if (com.allperiph.ui.ApSkin.picked(ctx, com.allperiph.ui.ApSkin.KEYBOARD).isNotBlank()) {
        com.allperiph.ui.ApSkin.current(ctx, com.allperiph.ui.ApSkin.KEYBOARD).accent
    } else {
        ApPalette.primary(ctx)
    }

/** 键盘强调色的浅底：同上随皮肤走 */
private fun kbAccentSoft(ctx: Context): Int =
    if (com.allperiph.ui.ApSkin.picked(ctx, com.allperiph.ui.ApSkin.KEYBOARD).isNotBlank()) {
        com.allperiph.ui.ApSkin.current(ctx, com.allperiph.ui.ApSkin.KEYBOARD).accentSoft
    } else {
        ApPalette.primaryContainer(ctx)
    }

/** 操控面页表：标题 + 图标（副行使用说明已按 2026-11-14 反馈移除） */
private data class ConsolePage(val titleRes: Int, val iconRes: Int)

private val CONSOLE_PAGES = listOf(
    ConsolePage(R.string.ui_main_item_touchpad, R.drawable.ic_apx_touchpad),
    ConsolePage(R.string.ui_touchpad_label_keyboard, R.drawable.ic_apx_keyboard),
    ConsolePage(R.string.notify_action_screen, R.drawable.ic_apx_power),
)

@Composable
private fun ConsoleRoot(ui: TouchpadActivity.ConsoleUi, host: TouchpadActivity) {
    val insets = rememberApInsets()
    val ctx = LocalContext.current
    // 背景走主壳同一条偏好（背景图 / 预设底色 / 动态），操控面默认底色 miuix_bg
    val backdrop = rememberApBackdrop(ctx, colorResource(R.color.miuix_bg))
    Box(modifier = Modifier.fillMaxSize().then(backdrop)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = insets.left,
                    top = insets.top,
                    end = insets.right,
                    bottom = insets.bottom,
                ),
        ) {
            if (!ui.fullscreen) ConsoleHeader(ui, host)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (ui.page) {
                    TouchpadActivity.PAGE_TOUCHPAD -> ConsoleTouchpadPage(ui, host)
                    // 键盘页同步主键盘页样式（issue#168 2026-11-11 第 3 条）：直接渲染主壳 KeyboardPage
                    // 的 ApFullKeyboard —— 8 页签同名同序、KeyLayouts / KeyPref 数据源共用，
                    // 按键同走 HidKeys 静态链；旧 ConsoleKeyboardPage 与其八套自建组件已随
                    // 审计批4（2026-11-12）整体删除 —— 两套操控面的键盘自此只有一份实现。
                    TouchpadActivity.PAGE_KEYBOARD -> com.allperiph.ui.compose.ApFullKeyboard()
                    else -> ConsoleDisplayPage(ui, host)
                }
            }
            // 低栏整改成可拖悬浮球（issue#168 2026-11-11 第 3 条，与主壳低栏同批口径）：
            // 点球弹三页切换菜单；全屏态不渲染（球会被触控手势层误触，退出全屏即回）。
            // ⚠️ 球层**不能**放在本 Column 里（issue#168 2026-11-11 15:12 反馈「进操控面
            // 触摸板/键盘/副屏页全没内容，点全屏才显示」的根因）：Column 的无 weight 子项
            // 先量、weight 子项后量 —— fillMaxSize 的球层作为无 weight 子项会把整列高度
            // 吃光，weight(1f) 的内容区被压成**零高**，三页全部「空白」，只有切全屏
            //（球层不渲染）内容才回来。挪到根 Box 与 Column 平级做纯悬浮层，
            // 不再参与 Column 分高度（主壳 ApShellActivity 的球本来就是同级兄弟）。
        }
        if (ui.fullscreen) FullscreenOverlay(host)
        if (!ui.fullscreen) ConsoleNavBall(ui, host)
    }
}

// —————————————————————————— 大标题头 ——————————————————————————

/** 大标题头：左对齐标题栈（非居中），右侧状态胶囊与退出（v184 起 chips 横滑） */
@Composable
private fun ConsoleHeader(ui: TouchpadActivity.ConsoleUi, host: TouchpadActivity) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ctx.getString(CONSOLE_PAGES[ui.page].titleRes),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态胶囊：HID 就绪主色、未就绪红；触摸后变成帧数
                val ready = ui.hidReady
                ChipPill(
                    label = ui.statusText,
                    tint = if (ready) MiuixTheme.colorScheme.primary else colorResource(R.color.miuix_danger),
                    bg = if (ready) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else colorResource(R.color.md_error_container),
                    compact = true,
                )
                // 被控模式开关：让本机也能被另一台手机控制（无需单独装 TV APK）；
                // 长按 = 看注入通道自检（哪一档能力、缺什么、怎么办）
                ChipPill(
                    label = ctx.getString(
                        if (ui.controlledOn) R.string.ui_touchpad_text_controlled_ok else R.string.ui_touchpad_text_controlled,
                    ),
                    tint = if (ui.controlledOn) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                    bg = colorResource(R.color.miuix_card),
                    onClick = { host.toggleControlled() },
                    onLongClick = { host.showInjectCaps() },
                )
                // 设备选择器：切换要控制的设备（本机/PC 或局域网内的 TV）
                ChipPill(
                    label = ui.deviceText,
                    tint = if (ui.deviceReady) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                    bg = colorResource(R.color.miuix_card),
                    onClick = { host.showDevicePicker() },
                )
                // 遥控器入口：真机反馈"找不到遥控器的界面" —— 在操控面顶部也放一个，随手可达
                ChipPill(
                    label = ctx.getString(R.string.ui_touchpad_text_remote),
                    tint = MiuixTheme.colorScheme.primary,
                    bg = colorResource(R.color.miuix_card),
                    onClick = {
                        host.startActivity(
                            Intent(ctx, com.allperiph.ui.RemoteActivity::class.java),
                        )
                    },
                )
                // 仅当选中受控设备（手机 / PC / TV）时展示：发文件 / 发剪贴板
                if (ui.deviceReady) {
                    ChipPill(
                        label = ctx.getString(R.string.ui_touchpad_text_file),
                        tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                        bg = colorResource(R.color.miuix_card),
                        onClick = { host.pickFile() },
                    )
                    ChipPill(
                        label = ctx.getString(R.string.ui_touchpad_text_clipboard),
                        tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                        bg = colorResource(R.color.miuix_card),
                        onClick = { host.sendClipboard() },
                    )
                }
                // 全屏入口：按用户原话「虚拟触控板的页能不能加个全屏模式，把页内其它控件都隐藏」
                ChipPill(
                    label = ctx.getString(R.string.ui_touchpad_fullscreen_enter),
                    tint = MiuixTheme.colorScheme.primary,
                    bg = colorResource(R.color.miuix_card),
                    onClick = { host.setFullscreen(true) },
                )
                ChipPill(
                    label = ctx.getString(R.string.ui_common_exit),
                    tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                    bg = colorResource(R.color.miuix_card),
                    onClick = { host.finish() },
                )
            }
        }
        // 2026-11-14 成宇「所有被控模式页面标题下面的使用说明文字可以去除」：
        // 标题下的副行说明（subRes）整段移除 —— 长按编辑快捷键等用法长按即见，
        // 常驻说明行只会占掉一截标题区高度。CONSOLE_PAGES 的 subRes 字段一并删除。
    }
}

/** 顶部胶囊：描边圆片 + 短文字（状态 / 入口 / 开关通用；可点，被控开关带长按自检） */
@Composable
private fun ChipPill(
    label: String,
    tint: Color,
    bg: Color,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .padding(start = if (compact) 0.dp else 8.dp)
            .clip(shape)
            .background(bg)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = onClick?.let { cb -> { FeedbackTap(view); cb() } },
                    onLongPress = onLongClick?.let { cb ->
                        { com.allperiph.ui.Feedback.haptic(view); cb() }
                    },
                )
            }
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = 6.dp,
            ),
    ) {
        Text(text = label, fontSize = if (compact) 11.sp else 12.sp, color = tint, maxLines = 1)
    }
}

/** 行内小工具：把 [com.allperiph.ui.Feedback.tap] 的调用集中在一处，避免到处写全限定名 */
private fun FeedbackTap(view: android.view.View) = com.allperiph.ui.Feedback.tap(view)

// —————————————————————————— 第 0 页：触控板 ——————————————————————————

/**
 * 手势面占满剩余空间；布局与 app 首页触控板页同一套口径（issue#168 第 7 条
 * 「横竖屏布局和 app 首页的触控板统一」）：
 * · 竖屏：手势卡 weight(2f) + 快捷键卡 weight(1f)，各自包 surfaceContainer 圆角背景框，
 *   触控只在手势卡内生效（ApTouchpadSurface 只消费自身 bounds）；快捷键标题行固定卡顶，
 *   网格超出滚动、滚动条置左（与首页 HotkeyGrid 同款）。
 * · 横屏：手势面撑满不加框（e7c917d 用户拍板「手势区撑满」口径），右列快捷键卡
 *   宽度走 ApComponents.touchpadSideWidth 共用口径、整列包框、标题行固定 + 网格滚动。
 * 旋转由窗口实测宽高（rememberApAdaptiveLayout）驱动重组生效，不等 Configuration 回写。
 * 全屏（v223.2）：整块屏幕只剩手势面，方向键叠层、快捷键网格一起收起。
 */
@Composable
private fun ConsoleTouchpadPage(ui: TouchpadActivity.ConsoleUi, host: TouchpadActivity) {
    // 快捷键网格已并轨主壳 Compose HotkeyGrid（见 HotkeyCard），旋转自适应网格自己处理，
    // 不再需要旧 View 版按方向重画（hotkeyBoard 仅剩 TV 键位集状态与编辑弹窗用途）。
    // 方向判定与主壳同口径（issue#170 第 8 条：全屏操控面转屏后布局不跟随）：
    // LocalConfiguration 的回写滞后一拍（本 Activity 声明了 configChanges，旋转不重建，
    // 老坑见 rememberApAdaptiveLayout 注释），改用窗口实测宽高比 —— 与布局测量同源。
    val adaptive = rememberApAdaptiveLayout()
    val land = adaptive.landscape
    // 背景框分栏的卡底色与圆角：与首页 TouchpadPage 同值（surfaceContainer + 16dp），
    // 两处快捷键区/手势卡看起来是同一个设计。
    val cardColor = MiuixTheme.colorScheme.surfaceContainer
    val cardShape = RoundedCornerShape(16.dp)
    // 右列宽度共用口径（见 ApComponents.touchpadSideWidth 注释），本页横屏竖屏各算一次；
    // 顺带与 land 同源（adaptive.width = 窗口实测宽，同一次快照）
    val screenWidth = adaptive.width.value
    val surface: @Composable (Modifier) -> Unit = { m ->
        ApTouchpadSurface(
            onGesture = { host.onSurfaceGesture(it) },
            onLongPressArmDrag = { host.armDragNow() },
            onLongPressRightDrag = { host.armRightDragNow() },
            modifier = m,
        )
    }
    if (ui.fullscreen) {
        // 全屏：去掉页边距，整块屏幕就是一整片触控板
        surface(Modifier.fillMaxSize())
        return
    }
    if (land) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                surface(Modifier.fillMaxSize())
                // v208：方向键居中覆盖手势面（带中心 OK），四周与键间空隙仍可滑动 ——
                // 键帽只消费自身区域，键外照常走触控引擎，滑动与方向同屏。
                DpadOverlay(Modifier.align(Alignment.Center))
                // 绝对指针小地图（0x06 第二模式）：入口在右下角，与方向键同口径只吃自身区域
                MiniMapOverlay(Modifier.align(Alignment.BottomEnd), host)
            }
            // 横屏右列（issue#168 第 7 条）：与首页横屏右列同款 —— 整列包背景框，
            // 宽度走共用口径（此前固定 190dp，平板上太窄、快捷键挤成一竖条）。
            Box(
                Modifier
                    .width(ApComponents.touchpadSideWidth(screenWidth))
                    .fillMaxHeight()
                    .padding(start = 10.dp)
                    .clip(cardShape)
                    .background(cardColor),
            ) {
                HotkeyCard(host)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            // 竖屏手势卡（issue#168 第 7 条）：占上方 2/3 并包框，与首页触控板页同口径 ——
            // 此前手势面吃满、快捷键区无框无滚动，热键一多就顶出屏幕（「显示不全」）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(2f)
                    .clip(cardShape)
                    .background(cardColor),
            ) {
                surface(Modifier.fillMaxSize())
                DpadOverlay(Modifier.align(Alignment.Center))
                // 绝对指针小地图（0x06 第二模式）：入口在右下角，与方向键同口径只吃自身区域
                MiniMapOverlay(Modifier.align(Alignment.BottomEnd), host)
            }
            // 快捷键卡：占下方 1/3（首页同比例），标题行固定卡顶、网格超出滚动。
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 10.dp)
                    .clip(cardShape)
                    .background(cardColor),
            ) {
                HotkeyCard(host)
            }
        }
    }
}

/**
 * 快捷键卡（2026-11-14 成宇「被控模式里触摸页常用快捷键布局没有同步主页」）：
 * 旧实现是 View 版 HotkeyBoard 塞 AndroidView —— 主页 Compose 网格两轮自适应
 * （11-12 同步样式、窄列降密度）它全没跟上。现在直接渲染主壳 [com.allperiph.ui.compose.HotkeyGrid]
 * 同一份实现：标题行 / 芯片样式 / 每行位数 / 拖拽排序全与主页同源；被控目标为 TV 时
 * 走 TV 键位集（useTvStore，与原 View 版 tvMode 数据源语义一致）。
 */
@Composable
private fun HotkeyCard(host: TouchpadActivity) {
    com.allperiph.ui.compose.HotkeyGrid(
        ctx = host,
        topBar = 0.dp,
        bottomGap = 0.dp,
        showHead = true,
        showEdit = true,
        useTvStore = host.hotkeyBoard.isInTvMode(),
    )
}


/**
 * v208：方向键叠层——十字方向键 + 中心 OK，居中覆盖手势面；
 * 箭头 usage 与 KeyLayouts 方向布局一致（USB HID Keyboard Page 0x07），
 * 中心 OK = Enter（0x28），与方向布局的确认键同 usage。
 * 仅键帽区域消费触摸，键间空隙和手势面其余区域仍走触控引擎。
 */
@Composable
private fun DpadOverlay(modifier: Modifier = Modifier) {
    val cell = Modifier.size(36.dp)
    Column(
        modifier
            .background(colorResource(R.color.miuix_bg), RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        Row { Spacer(cell); DpadKey("↑", 0x52); Spacer(cell) }
        Row { DpadKey("←", 0x50); DpadKey("OK", 0x28); DpadKey("→", 0x4F) }
        Row { Spacer(cell); DpadKey("↓", 0x51); Spacer(cell) }
    }
}

/** 方向键叠层里的一枚键帽（显式 usage，长按连发与普通键帽同口径） */
@Composable
private fun DpadKey(label: String, usage: Int) {
    val ctx = LocalContext.current
    KeyCap(
        label = label,
        usage = usage,
        modVersion = 0,
        onTap = {},
        modifier = Modifier.size(36.dp),
        accent = Color(kbAccent(ctx)),
        accentSoft = Color(kbAccentSoft(ctx)),
    )
}

/**
 * 地图内坐标 → 0x06 载荷的归一化 0..65535（与 0x04 同布局，u16 小端由发送端打包）。
 */
private fun mapNorm(px: Float, py: Float, w: Int, h: Int): Pair<Int, Int> =
    ((px / w * 65535f).toInt().coerceIn(0, 65535)) to ((py / h * 65535f).toInt().coerceIn(0, 65535))

/**
 * 绝对指针小地图叠层（2026-11-13「指哪打哪」第 2 步，随 0x06 帧与能力位 bit5 落地）：
 * 手势卡右下角一枚入口钮 → 展开一块 16:9 被控端屏幕等比小地图。与原触控板操作逻辑并存 ——
 * 手势面照旧相对位移，小地图是「指哪打哪」的第二模式：
 * · 单指点按：先 move（对端光标跟到指下）再 down/up，一次点击直接跳到该点；
 * · 按住拖动：越过触控 slop 后逐帧 move 跟手平移，不补 down/up（不产生点击）；
 * · 双指点按：全程未越 slop 且出现过两根手指 → buttons=2 的 down/up（= 右键）。
 * 地图只消费自身区域，与 DpadOverlay 同口径（其余区域照常走触控引擎）；
 * 未连接 / 对端明确不支持时点入口只 Toast 说明，不弹空地图。
 */
@Composable
private fun MiniMapOverlay(modifier: Modifier = Modifier, host: TouchpadActivity) {
    val expanded = remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    if (!expanded.value) {
        // 收起态：一枚小圆钮，底色描边与 DpadOverlay 同套，不抢手势面的视觉焦点
        Box(
            modifier
                .padding(10.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(colorResource(R.color.miuix_bg))
                .border(1.dp, colorResource(R.color.miuix_stroke), CircleShape)
                .clickable {
                    // 展开前先做一次性可用性检查：没在控 / 对端明确不支持就不弹空地图
                    if (host.absolutePointerReady()) {
                        expanded.value = true
                    } else {
                        Toast.makeText(
                            ctx,
                            R.string.ui_touchpad_minimap_unavailable,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.ui_touchpad_minimap_toggle),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    } else {
        MiniMapPanel(host) { expanded.value = false }
    }
}

/** 展开态：标题行 + 16:9 小地图 + 操作提示（220dp 宽收口，竖屏横屏都不顶卡） */
@Composable
private fun MiniMapPanel(host: TouchpadActivity, onCollapse: () -> Unit) {
    Column(
        Modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colorResource(R.color.miuix_bg))
            .border(1.dp, colorResource(R.color.miuix_stroke), RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ui_touchpad_minimap_toggle),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.ui_touchpad_minimap_collapse),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onCollapse),
            )
        }
        // 小地图本体：16:9 等比深色面板 = 被控端屏幕的示意，坐标即屏幕归一化坐标，点哪发哪。
        // 手势判定对齐触控板口径：越过 touchSlop 才算拖动，轻抖不改判点击语义。
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(220.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF141414))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = down.position
                        var dragMode = false
                        var maxPointers = 1
                        // 首触先发 hover：对端光标先跟到指下，点按/拖动都从这帧起
                        val (hx, hy) = mapNorm(last.x, last.y, size.width, size.height)
                        host.sendAbsoluteTouch(2, 0, hx, hy)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressedCount = ev.changes.count { it.pressed }
                            if (pressedCount > maxPointers) maxPointers = pressedCount
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break // 主指抬起 → 收尾判定
                            ch.consume()
                            last = ch.position
                            if (!dragMode &&
                                (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                dragMode = true
                            }
                            if (dragMode) {
                                // 拖动 = 连续 move，跟手平移对端光标（不产生点击）
                                val (mx, my) = mapNorm(last.x, last.y, size.width, size.height)
                                host.sendAbsoluteTouch(2, 0, mx, my)
                            }
                        }
                        if (!dragMode) {
                            val (x, y) = mapNorm(last.x, last.y, size.width, size.height)
                            if (maxPointers >= 2) {
                                // 双指点按 = 右键：down/up 成对，buttons bit1（右键位）
                                host.sendAbsoluteTouch(0, 2, x, y)
                                host.sendAbsoluteTouch(1, 2, x, y)
                            } else {
                                // 单指点按 = move → down → up：一次点击直接跳到该点
                                host.sendAbsoluteTouch(0, 0, x, y)
                                host.sendAbsoluteTouch(1, 0, x, y)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { }
        Text(
            stringResource(R.string.ui_touchpad_minimap_hint),
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * MIUIX 键帽：白底细描边 + 密度自适应圆角；修饰键用浅主色底/主色字，锁定后实主色底白字。
 *
 * 长按连发（issue #52 建议 7）：按住不放 → 400ms 后每 55ms 重复发送同一个键，
 * 退格 / 方向键 / 字母可以一口气连打。松开时**吞掉多出来的那一次 click** ——
 * 否则长按退格会多删一个字符。修饰键不参与：Shift / Ctrl / Caps 连发只会把
 * 修饰状态反复开关，没有任何意义。
 */
@Composable
private fun KeyCap(
    label: String,
    accent: Color,
    accentSoft: Color,
    modifier: Modifier = Modifier,
    usage: Int = 0,
    isModifier: Boolean = false,
    modBit: Int = 0,
    modVersion: Int = 0,
    onTap: () -> Unit = {},
) {
    val view = LocalView.current
    val ctx = LocalContext.current
    val dens = KeyPref.density(ctx)
    val corner = dens.cornerDp.dp
    // sticky 状态：修饰键按 HidKeys 实时值取（modVersion 变化会带着本参数重组）
    val stickyOn = isModifier && HidKeys.isSticky(modBit)
    val pressed = remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    stickyOn -> accent
                    isModifier || pressed.value -> accentSoft
                    else -> colorResource(R.color.miuix_card)
                },
            )
            .then(
                if (stickyOn || isModifier || pressed.value) Modifier else Modifier.border(
                    1.dp,
                    colorResource(R.color.miuix_stroke),
                    shape,
                ),
            )
            .pointerInput(isModifier, onTap, usage) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    com.allperiph.ui.Feedback.tap(view)
                    pressed.value = true
                    var repeatFired = false
                    var sawRelease = false
                    try {
                        if (!isModifier) {
                            // 首发延迟：400ms 内抬起 = 普通点击（finally 里统一发）
                            val pressStart = System.currentTimeMillis()
                            while (true) {
                                val remain = 400L - (System.currentTimeMillis() - pressStart)
                                if (remain <= 0) break
                                val ev = withTimeoutOrNull(remain) { awaitPointerEvent() } ?: break
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    sawRelease = true
                                    break
                                }
                                ch.consume()
                            }
                            // 连发阶段：仅当 400ms 内没有抬起（指针仍按住）才进入。
                            // 抬起事件已在上一段被消费，若不加这道闸，这里会永远等不到
                            // 抬手 —— 每 55ms 重发同一键、pressed 高亮永不回弹，正是
                            // 用户报修「外设模式点了一直灰、没反应」的根因（v223.29.56 起）。
                            if (!sawRelease) {
                                while (true) {
                                    if (usage != 0) HidKeys.tap(usage) else onTap()
                                    repeatFired = true
                                    val ev = withTimeoutOrNull(55L) { awaitPointerEvent() } ?: continue
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) {
                                        sawRelease = true
                                        break
                                    }
                                    ch.consume()
                                }
                            }
                        } else {
                            // 修饰键（Ctrl/Alt/Shift/Win）没有连发语义：等抬起后由 finally
                            // 统一触发 onTap（toggleSticky）。v223.29.56 连发改造把「点击发送」
                            // 收进普通键分支后，修饰键在 try 里没有任何动作、sawRelease 也永远
                            // 置不上 —— toggleSticky 点不亮，操控面的 Shift 从此失效：双标键
                            //（;: 这类）只剩默认下档符号，正是 issue#168 第 8 条「无法输入
                            // 按键上方标点」的根因。刻意不加超时：长按修饰键是合法操作，
                            // 按住期间没有指针事件就挂起等抬起（手势取消由 awaitEachGesture
                            // 收尾，finally 里 sawRelease=false 自然不发）。
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    sawRelease = true
                                    break
                                }
                                ch.consume()
                            }
                        }
                    } finally {
                        pressed.value = false
                        // 普通点击在抬手那一刻发送；连发阶段已发过的不补（否则长按退格
                        // 会多删一个字符）；手势被取消（弹窗接管 / 离开组合）不发。
                        if (!repeatFired && sawRelease) {
                            if (usage != 0) HidKeys.tap(usage) else onTap()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = dens.textSize.sp,
            textAlign = TextAlign.Center,
            color = when {
                stickyOn -> MiuixTheme.colorScheme.onPrimary
                isModifier -> accent
                else -> MiuixTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

// —————————————————————————— 第 2 页：副屏（显示）+ 链接 TV/PC ——————————————————————————

/**
 * 副屏页。横屏（issue#168 第 1 条）：竖排两张卡会把「链接设备」两个按钮挤到屏外，
 * 改成左右两卡各占一半宽；竖屏保持上下排。
 * 副屏状态随进入页面刷新一次（与旧 updateDisplayStatus 的时点一致）。
 */
@Composable
private fun ConsoleDisplayPage(ui: TouchpadActivity.ConsoleUi, host: TouchpadActivity) {
    DisposableEffect(Unit) {
        host.updateDisplayStatus()
        onDispose { }
    }
    // 方向判定与操控面页同口径（issue#170 第 8 条）：窗口实测宽高比，不等
    // LocalConfiguration 的滞后回写（本 Activity 旋转不重建，老坑见
    // rememberApAdaptiveLayout 注释），转屏瞬间布局即时跟随。
    val land = rememberApAdaptiveLayout().landscape
    if (land) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(end = 4.dp)) { StatusCard(ui) }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp)) { LinkCard(host) }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
            StatusCard(ui)
            LinkCard(host, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** 副屏（显示）状态卡：当前收屏状态 + 全屏说明 */
@Composable
private fun StatusCard(ui: TouchpadActivity.ConsoleUi) {
    val ctx = LocalContext.current
    ApComponents.Card {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                text = ctx.getString(R.string.ui_touchpad_text_screen),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = ui.displayStatus,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = ctx.getString(R.string.ui_touchpad_text_screen_recv_fullscreen_local),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 链接设备卡：链接 TV（设备选择器）/ 链接 PC（副屏投屏页） */
@Composable
private fun LinkCard(host: TouchpadActivity, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    ApComponents.Section(
        title = ctx.getString(R.string.ui_touchpad_text_device),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            ApComponents.ActionButton(
                text = ctx.getString(R.string.ui_main_item_link),
                onClick = { host.showDevicePicker() },
            )
            ApComponents.ActionButton(
                text = ctx.getString(R.string.ui_main_item_screen),
                onClick = {
                    host.startActivity(
                        Intent(ctx, com.allperiph.screen.ScreenActivity::class.java),
                    )
                },
            )
        }
    }
}

// —————————————————————————— 导航球 ——————————————————————————

/**
 * 操控面导航球（issue#168 2026-11-11 第 3 条）：与主壳低栏同批整改 —— 底部页签条
 * 整条退场，换成一枚 48dp 可拖玻璃球；点按弹三页切换菜单（触控板 / 键盘 / 副屏）。
 * 落点走 [com.allperiph.ui.BallPos] 共享存档：操控面、主壳、桌面三处悬浮球同位衔接。
 */
@Composable
private fun ConsoleNavBall(ui: TouchpadActivity.ConsoleUi, host: TouchpadActivity) {
    val view = LocalView.current
    val density = LocalDensity.current
    val navBallPx = with(density) { 48.dp.toPx() }
    var navBallX by remember { mutableFloatStateOf(-1f) }
    var navBallY by remember { mutableFloatStateOf(-1f) }
    var menuOpen by remember { mutableStateOf(false) }
    val dm = host.resources.displayMetrics
    // issue#168 2026-11-12 #9「禁止移动到状态栏和小白条区域」（与主壳导航球同规矩）：
    // 拖拽纵界 = 顶部 ApInsets.top（状态栏/挖孔）、底部 ApInsets.bottom（手势条）。
    val ballInsets = com.allperiph.ui.miuix.rememberApInsets()
    val ballTopLimitPx = with(density) { ballInsets.top.toPx() }
    val ballBottomGapPx = with(density) { ballInsets.bottom.toPx() }
    // 纵向钳进系统栏带（极矮屏兜底 max≥min）；落位与拖拽共用。
    fun clampBallY(y: Float, screenH: Int): Float {
        val maxY = (screenH - navBallPx - ballBottomGapPx).coerceAtLeast(ballTopLimitPx)
        return y.coerceIn(ballTopLimitPx, maxY)
    }
    // 主题明暗判（与主壳 navDarkBall 同一算式）：反色球用 —— 深色主题白球 / 浅色主题黑球
    val navDarkBall = run {
        val bg = MiuixTheme.colorScheme.background
        0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue < 0.5f
    }
    // 首次组合 / 转屏：读共享落点（球左上角比例）；无档落右上角（操控面大标题头在
    // 左上，右上既有退出胶囊……避开标题行，落**右下**与拇指区一致）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (navBallX < 0f) {
            val loaded = com.allperiph.ui.BallPos.load(host, dm.widthPixels, dm.heightPixels, navBallPx.toInt())
            if (loaded != null) {
                navBallX = loaded.first.toFloat()
                navBallY = clampBallY(loaded.second.toFloat(), dm.heightPixels)
            } else {
                navBallX = dm.widthPixels - navBallPx - with(density) { 20.dp.toPx() }
                navBallY = clampBallY(dm.heightPixels - navBallPx - with(density) { 32.dp.toPx() }, dm.heightPixels)
            }
        }
    }
    // 记上一次观测到的内容区尺寸：转屏 / 分屏变化时按它判定「该重读落点了」
    // （类型是 Compose 的 IntSize——onSizeChanged 回调的入参就是它，别换成 android.util.Size）
    var lastBounds by remember { mutableStateOf<IntSize?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 转屏重读落点（2026-11-12「很多界面都有这样的问题」系统性排查）：主壳导航球
            // （ApShellActivity）有 LaunchedEffect(landscape) 兜转屏，这里此前只落一次位，
            // 旋转后球停在旧方向的绝对像素上（可能悬在半空或出屏）。挂在 onSizeChanged
            // 而不是 LaunchedEffect(orientation)：Compose 测量与布局同源、不等
            // Configuration 回写，转屏瞬间必然触发（老坑见 rememberApAdaptiveLayout 注释）。
            .onSizeChanged { size ->
                if (size.width <= 0 || size.height <= 0) return@onSizeChanged
                val prev = lastBounds
                lastBounds = size
                // 首次测量交给下方 LaunchedEffect 落位；此后尺寸一变就按共享比例重读
                if (prev == null || navBallX < 0f || (size.width == prev.width && size.height == prev.height)) {
                    return@onSizeChanged
                }
                com.allperiph.ui.BallPos.load(host, size.width, size.height, navBallPx.toInt())?.let {
                    navBallX = it.first.toFloat()
                    navBallY = it.second.toFloat()
                } ?: run {
                    // 没有存档（异常清除等）：至少把球夹回当前屏内，别悬在屏外
                    navBallX = navBallX.coerceIn(0f, (size.width - navBallPx).coerceAtLeast(0f))
                    navBallY = navBallY.coerceIn(0f, (size.height - navBallPx).coerceAtLeast(0f))
                }
            }
    ) {
        // 页签切换菜单（issue#168 2026-11-12 #9「展开样式对齐 app 首页横排胶囊」）：
        // 原先是居中卡片（标题 + 纵排三项），改为与主壳 pageSwitchOpen 胶囊同款的
        // **横排胶囊** —— 页签名一行排开、当前页主色点亮，落点挂在球旁（上方优先，
        // 贴顶放不下换下方），横向钳在屏内；点胶囊外任意处收起（透明接手势层画在
        // 球之前，球的点按/拖动不受影响），再点球同样收起（开关语义）。
        if (menuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { menuOpen = false }) },
            )
            var pillW by remember { mutableIntStateOf(0) }
            val pillGapPx = with(density) { 10.dp.toPx() }
            val pillHPx = with(density) { 44.dp.toPx() }
            val pillMarginPx = with(density) { 12.dp.toPx() }
            // 纵向：优先球上方，贴顶放不下改球下方（与主壳同口径）
            val pillYPx = if (navBallY - pillGapPx - pillHPx >= 0f) {
                navBallY - pillGapPx - pillHPx
            } else {
                navBallY + navBallPx + pillGapPx
            }
            // 横向：胶囊中线对齐球心，左右钳进屏边（pillW 由 onSizeChanged 实测回填）
            val pillXPx = (navBallX + navBallPx / 2f - pillW / 2f)
                .coerceIn(pillMarginPx, (dm.widthPixels - pillW - pillMarginPx).coerceAtLeast(pillMarginPx))
            Box(
                modifier = Modifier
                    .offset { IntOffset(pillXPx.roundToInt(), pillYPx.roundToInt()) }
                    .onSizeChanged { pillW = it.width }
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.98f))
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (navDarkBall) 0.30f else 0.66f),
                                Color.White.copy(alpha = if (navDarkBall) 0.10f else 0.22f),
                            ),
                        ),
                        shape = CircleShape,
                    )
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CONSOLE_PAGES.forEachIndexed { i, p ->
                        Text(
                            text = host.getString(p.titleRes),
                            fontSize = 14.sp,
                            // 当前页主色点亮（与主壳胶囊同款）
                            color = if (ui.page == i) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    com.allperiph.ui.Feedback.tap(view)
                                    menuOpen = false
                                    host.showPage(i)
                                }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(navBallX.roundToInt(), navBallY.roundToInt()) }
                .size(48.dp)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            // 2026-11-12 反馈「旋转后悬浮栏不能往右移动，只能到中间」在壳内球
                            //（ApShellActivity 97408a3）同病根的操控面残留：拖拽边界此前用的
                            // 是本组合首帧捕获的 dm —— 本页转屏不重建（configChanges），转屏后
                            // 旧对象仍停在旧朝向尺寸，横向被夹在旧屏宽。d5d700d 只修了落位重读
                            //（onSizeChanged），拖拽夹边与存档两处漏网；改为每次事件现读，
                            // 转屏后边界与存档口径立刻换新。
                            val dm = host.resources.displayMetrics
                            navBallX = (navBallX + drag.x)
                                .coerceIn(0f, dm.widthPixels - navBallPx)
                            // 纵向钳进系统栏带：上不过状态栏/挖孔、下不过小白条（#9 同规矩）
                            navBallY = clampBallY(navBallY + drag.y, dm.heightPixels)
                        },
                        onDragEnd = {
                            val dm = host.resources.displayMetrics
                            com.allperiph.ui.BallPos.save(
                                host, navBallX, navBallY, dm.widthPixels, dm.heightPixels,
                            )
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { menuOpen = !menuOpen })
                }
                .background(
                    // 与主壳导航球同款反色高对比（issue#168 2026-11-11 15:12 反馈「深色和浅色
                    // 主题对比度都低」）：surfaceContainer 与背景同族怎么调透明度都糊在底色里，
                    // 改为深色主题给近白球、浅色主题给近黑球，恒与背景拉开。
                    // 主题明暗按背景色亮度判（与主壳 navDarkBall 同一算式）。
                    Brush.verticalGradient(
                        if (navDarkBall) {
                            listOf(Color.White.copy(alpha = 0.94f), Color.White.copy(alpha = 0.86f))
                        } else {
                            listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.80f))
                        },
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        if (navDarkBall) {
                            listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.06f))
                        } else {
                            listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.12f))
                        },
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 字色随球色翻转（球反色后 onSurface 必有一侧看不清）。
            // 菜单把手（issue#166 第 3 条）：「≡」字符改 ic_apx_menu 矢量，与主壳导航球
            // 同一份图标、同一 20dp 边长——两处此前各写一份字符，观感随 ROM 字体漂移。
            ApComponents.Icon(
                resId = R.drawable.ic_apx_menu,
                size = 20.dp,
                tint = if (navDarkBall) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.95f),
            )
        }
    }
}

// —————————————————————————— 全屏覆盖层 ——————————————————————————

/**
 * 全屏覆盖层：右下角「退出全屏」小胶囊（进入 3s 后降到 25% 不透明度，别在视野里抢
 * 注意力）+ 一次性说明（第一次进全屏必须让人知道怎么出去，否则就是「进去了出不来」
 * 的坑；2.6s 后自己淡出）。
 */
@Composable
private fun FullscreenOverlay(host: TouchpadActivity) {
    val ctx = LocalContext.current
    val view = LocalView.current
    Box(Modifier.fillMaxSize()) {
        // 退出胶囊：3s 后降到 25% 不透明度
        var pillAlpha by remember { mutableStateOf(0.6f) }
        LaunchedEffect(Unit) {
            delay(3000)
            pillAlpha = 0.25f
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .alpha(pillAlpha)
                .clip(RoundedCornerShape(50))
                .background(colorResource(R.color.miuix_card))
                .clickable {
                    com.allperiph.ui.Feedback.tap(view)
                    host.setFullscreen(false)
                }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = ctx.getString(R.string.ui_touchpad_fullscreen_exit),
                fontSize = 11.sp,
                // 成宇 2026-11-13 05:0x「全屏触控板右下角退出按钮文字没有反色，看不清」：
                // 原取 onSurfaceSecondary（次级灰），叠在全屏深色手势面上再降到 25% 不
                // 透明度，字基本没影。对齐键帽 fg 走 onSurface（亮=黑字/暗=白字），
                // 不透明度节奏不变，只让字在两种日夜下都有足够对比。
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        // 一次性说明：进全屏弹一次，2.6s 后自己淡出（中途退出全屏则随覆盖层一起消失）
        var tipVisible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(2600)
            tipVisible = false
        }
        if (tipVisible) {
            Text(
                text = ctx.getString(R.string.ui_touchpad_fullscreen_hint),
                fontSize = 12.sp,
                color = colorResource(R.color.miuix_bg),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colorResource(R.color.miuix_text))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}
