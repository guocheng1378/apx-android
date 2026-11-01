package com.allperiph.touchpad

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import android.widget.ViewFlipper
import com.allperiph.R
import com.allperiph.core.ModuleId
import com.allperiph.shared.net.FileSender
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.ui.AgentController
import com.allperiph.ui.HotkeyController

import com.allperiph.controlled.ControlledService
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvControllerClient
import com.allperiph.wireless.TvDiscovery
import com.allperiph.wireless.WirelessModule
import kotlin.math.abs

/**
 * 触控板操控面（v1.15 两页签版）。
 *
 * 信息架构（MIUIX / HyperOS 亮色）：
 *   ┌ 大标题头 —— 页名（24sp Bold）+ 副说明 + 状态胶囊 + 退出
 *   ├ 内容页 —— ViewFlipper 两页
 *   │    0 触控板：手势面（大圆角卡，不消费触摸）+ 常用快捷键 chips（点击发送 / 长按编辑）
 *   │    1 键盘  ：顶部三套键位切换 —— 快捷键盘（QWERTY）/ 遥控键盘（媒体）/ 游戏键盘（WASD 向）
 *   └ 底部页签栏（矢量图标 + 文字，选中染蓝）
 *
 * 与 v1.14 的差异：原「快捷键」「媒体」两个独立页签取消 —— 快捷键回到触控板页做常驻
 * chips（抬手即用），媒体并入键盘页作为「遥控键盘」一套键位。
 *
 * 触摸兜底（v1.7b 架构保留）：仅第 0 页且非边缘手势才把事件喂给触控引擎；
 * 左右边缘 28dp 起滑 80dp 翻页。与副屏互斥（架构 §4）。
 * 发送通道：无线走蓝牙 HID，有线走 HID TLC（Report ID 21 键盘 / 4 媒体）。
 */
class TouchpadActivity : Activity() {

    // —————————————————————————— MIUIX 设计令牌（读资源，随主题明暗自适应） ——————————————————————————

    private val cBg get() = getColor(R.color.miuix_bg)
    private val cCard get() = getColor(R.color.miuix_card)
    private val cText get() = getColor(R.color.miuix_text)
    private val cText2 get() = getColor(R.color.miuix_text_secondary)
    private val cText3 get() = getColor(R.color.miuix_text_tertiary)
    private val cDivider get() = getColor(R.color.miuix_divider)
    private val cStroke get() = getColor(R.color.miuix_stroke)
    /** 键盘配色：设置页可单独选一套皮肤；不选时保持原来的 miuix_accent 系列 */
    private val kbSkinPicked get() =
        com.allperiph.ui.ThemeSkin.picked(this, com.allperiph.ui.ThemeSkin.KEYBOARD).isNotBlank()
    private val cAccent get() =
        if (kbSkinPicked) com.allperiph.ui.ThemeSkin.current(this, com.allperiph.ui.ThemeSkin.KEYBOARD).accent
        else com.allperiph.ui.ThemeSkin.primary(this)
    private val cAccentSoft get() =
        if (kbSkinPicked) com.allperiph.ui.ThemeSkin.current(this, com.allperiph.ui.ThemeSkin.KEYBOARD).accentSoft
        else com.allperiph.ui.ThemeSkin.primaryContainer(this)

    /** 键帽密度（设置页可换） */
    private val dens get() = com.allperiph.ui.KeyPref.density(this)
    private val cDangerSoft get() = getColor(R.color.md_error_container)
    private val cDanger get() = getColor(R.color.miuix_danger)
    private val cTabIdle get() = getColor(R.color.state_idle)

    /** 页签定义 */
    private class Page(val title: String, val sub: String, val icon: Int)

    /** 页签视图对：图标按选中态染色 */
    private class Tab(val icon: ImageView, val label: TextView)

    // **必须 lazy**：属性初始化器在 Activity 构造阶段执行，那时 Context 还没 attach，
    // getString 会直接 NPE —— 症状就是"全屏操控面点不进去"（v186 回归，v187 修）。
    private val pages by lazy {
        listOf(
            Page(getString(R.string.ui_main_item_touchpad), getString(R.string.ui_touchpad_label_hotkey_edit_longpress), R.drawable.ic_apx_touchpad),
            Page(getString(R.string.ui_touchpad_label_keyboard), getString(R.string.ui_touchpad_label_remote), R.drawable.ic_apx_keyboard),
            Page(getString(R.string.notify_action_screen), getString(R.string.ui_touchpad_label_screen), R.drawable.ic_apx_power),
        )
    }

    /** 键盘页的 7 套布局（对应 kbPager 的 7 个 child，数据见 ui/KeyLayouts） */
    private val kbNames = listOf("快捷", "遥控", "游戏", "数字", "九宫格", "方向", "F 区", "自定义")

    // —————————————————————————— 状态 ——————————————————————————

    private var titleView: TextView? = null
    private var subView: TextView? = null
    private var statusView: TextView? = null
    private var deviceChip: TextView? = null
    private var fileBtn: TextView? = null
    private var clipBtn: TextView? = null
    private var controlledChip: TextView? = null
    private var displayStatusView: TextView? = null
    private lateinit var flipper: ViewFlipper

    /** 文件选择请求码（用传统 startActivityForResult，因本 Activity 非 AndroidX ComponentActivity） */
    private val REQ_PICK_FILE = 9001
    private var tabs: List<Tab> = emptyList()
    private var chipRow: LinearLayout? = null
    private lateinit var kbPager: ViewFlipper
    private var kbTabs: List<TextView> = emptyList()
    // 快捷键网格：行为统一在 ui/HotkeyBoard（与主页共用同一份实现）
    private lateinit var hotkeyBoard: com.allperiph.ui.HotkeyBoard

    /** 修饰键位 → 该位的所有按钮（快捷键盘与游戏键盘各有一套，需一起点亮） */
    private val modBtns = LinkedHashMap<Int, MutableList<TextView>>()

    private val handler = Handler(Looper.getMainLooper())
    private var hidReady = false
    private var frameCount = 0L
    private var lastShown = 0L

    // v1.7d：长按拖动定时器——DOWN 后 550ms 触发 armDrag（手指静止无 MOVE 也生效）
    private val dragTask = Runnable {
        (AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule)?.armDrag()
    }

    // 右键拖拽：**此前 armRightDrag() 全仓库零调用**，双指长按没有任何入口 ——
    // 与上面的左键拖拽对称：双指落下后 550ms 锁定右键拖拽。
    private val rightDragTask = Runnable {
        (AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule)?.armRightDrag()
    }

    // 媒体音量按住连发
    private var volRepeat: Runnable? = null

    // 边缘滑动翻页状态
    private var edgeStartX = -1f
    private var edgeSwiped = false

    // ——— v223.2：全屏触控板（issue#47 第 1 条） ———
    // 起因是「光标锁定」在 Android 上做不到（应用层没有限制光标区域的 API，只能把本窗口
    // 光标图标设成 TYPE_NULL），所以唯一能消掉误触的路径就是界面：全屏后页面上只剩手势面，
    // 标题头、页签栏、快捷键网格、方向键叠层、提示行一起收起，系统导航栏也收掉。
    private var headerView: View? = null
    private var tabBarView: View? = null
    private var tpPageCol: LinearLayout? = null
    /** 实体键盘/指针转发桥（v223.13：全屏操控面此前从没接过 —— issue #52 第 6 条）。 */
    private var physKeyBridge: com.allperiph.ui.PhysKeyBridge? = null
    private var tpArea: FrameLayout? = null
    private var tpHint: TextView? = null
    private var tpHotkeySection: View? = null
    private var tpDpad: View? = null
    private var fullscreenChip: TextView? = null
    private var fullscreenExitPill: TextView? = null
    @Volatile private var fullscreen = false
    /** 全屏上沿下滑退出：这一整段手势都吞掉，不喂手势引擎（否则退出动作会顺手甩光标） */
    private var fsSwallow = false
    /** 已经因这条手势退出全屏了（但手势还没抬，仍要继续吞到 UP —— 不能半路开始喂引擎） */
    private var fsExiting = false
    private var fsStartY = 0f
    /** 退出小胶囊的淡出任务（进入 3s 后降到 25% 不透明度，别在视野里抢注意力） */
    private var fsPillFade: Runnable? = null

    // —————————————————————————— 装配 ——————————————————————————

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
        ControlTarget.controlClient?.onReverseClipboard = null
        // 退出操控面立刻还原系统三指手势（进程被杀的情况交给 App 启动时的自愈）
        com.allperiph.touchpad.GestureShield.release(this, HOLDER_CONSOLE)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强调色要叠在「正在用的这个主题」上才算数（清单主题是新建的，父上下文叠加会丢）
        com.allperiph.ui.ThemeSkin.applyRuntimeTheme(this)
        // 截屏已放开（与 MainActivity 口径一致）：主控制面此前挂 FLAG_SECURE，
        // 截图/录屏一片黑，用户没法截图反馈界面问题，排查只能靠口述。
        // 快捷键列表由 HotkeyBoard 自己从盘上读（见 buildChipsPanel）
        hidReady = AgentController.runtime?.hid?.isReady() == true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        com.allperiph.ui.Backdrop.apply(root, cBg) // 背景：预设底色 / 相册图（设置页可选）
        root.addView(buildHeader())
        flipper = ViewFlipper(this).apply {
            addView(touchpadPage())
            addView(keyboardPage())
            addView(displayPage())
        }
        root.addView(flipper, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildTabBar())
        // v223.2：外层再套一层 FrameLayout，只为了把「退出全屏」小胶囊浮在手势面之上。
        // 它非全屏时 GONE —— 不在布局里，也不吃触摸；全屏时是页面上**唯一**可点控件，
        // 且压在右下角 10dp 处，两指划动手势基本不会落在那里。
        val shell = FrameLayout(this)
        shell.addView(root, ViewGroup.LayoutParams(-1, -1))
        fullscreenExitPill = TextView(this).apply {
            text = getString(R.string.ui_touchpad_fullscreen_exit)
            setTextColor(cText2)
            textSize = 11f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            alpha = 0.6f
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            setOnClickListener { setFullscreen(false) }
        }
        shell.addView(fullscreenExitPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(10); bottomMargin = dp(10)
        })
        // v1.7 修复：显式 MATCH_PARENT（root 高度塌缩曾致触摸区只剩一小条）
        setContentView(shell, ViewGroup.LayoutParams(-1, -1))
        // 实体键盘 / 实体触控板转发桥：全屏操控面是独立 Activity，主页触控板页的
        // PhysKeyBridge（TouchpadFragment 里 attach）管不到这里 —— 不接的话全屏里
        // 实体键盘按键、实体触控板 hover/指针事件全部落进系统默认路径，
        // 用户看到的就是「实体键盘无法使用、实体触控板单指失效」（issue #52 第 6 条）。
        physKeyBridge = com.allperiph.ui.PhysKeyBridge(this).also { it.attach(window) }
        // Android 15+（targetSdk 35）强制 edge-to-edge，主题里的 windowFullscreen 不再保证内容避开系统栏 ——
        // R+ 显式接管：关掉 decor 贴合、隐藏状态栏、按实际 insets（状态栏/挖孔/导航栏/输入法）给内容垫边：
        // 系统认为栏还在（insets>0）就垫开，已隐藏/已贴合时 inset=0 不垫，任何一态都不遮住标题头。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
            root.setOnApplyWindowInsetsListener { v, insets ->
                val t = insets.getInsets(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars() or
                        android.view.WindowInsets.Type.displayCutout() or
                        android.view.WindowInsets.Type.ime()
                )
                v.setPadding(t.left, t.top, t.right, t.bottom)
                insets
            }
            root.requestApplyInsets()
        }
        refreshTabs()
        updateControlledChip()
        // 主页「设置 → 管理快捷键」用 EXTRA_PAGE 直达键盘页；launchMode=singleTask，
        // 已在栈中时走 onNewIntent
        showPage(intent?.getIntExtra(EXTRA_PAGE, PAGE_TOUCHPAD) ?: PAGE_TOUCHPAD)
        // 预启动 TV 发现（监听 APX1TV 信标），让设备列表在打开选择器时已就绪
        TvDiscovery.start()
    }

    /**
     * 只在前台时遮蔽三指手势：退到后台（Home / 切应用 / 锁屏）立刻还原 ——
     * 否则用户在桌面想三指下拉截屏时会发现按不动。
     *
     * 说明：本操控面前台期间，MIUI 的「三指下滑截屏 / 三指长按区域截屏」被临时
     * 改成无动作（root），否则三指滑动还没做完，截图就已经存进相册了。
     * 开关在 设置 → 交互开关 → 三指手势遮蔽；无 root 时只提示一次，走手动兜底。
     */
    override fun onResume() {
        super.onResume()
        // 与主页同口径：每次回前台复核转发桥（窗口回调被换掉就重新包上）
        physKeyBridge?.ensureAttached(window)
        com.allperiph.touchpad.GestureShield.acquireWithHint(this, HOLDER_CONSOLE)
    }

    override fun onPause() {
        com.allperiph.touchpad.GestureShield.release(this, HOLDER_CONSOLE)
        // 退后台会丢掉后续的「按键抬起」：放掉仍按住的键，避免电脑侧 Ctrl / Shift 粘住
        physKeyBridge?.releaseStuckKeys()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        showPage(intent?.getIntExtra(EXTRA_PAGE, PAGE_TOUCHPAD) ?: PAGE_TOUCHPAD)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { sendFile(it) }
        }
    }

    /** 大标题头：左对齐标题栈（非居中），右侧状态胶囊与退出 */
    private fun buildHeader(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            text = pages[0].title
            setTextColor(cText)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        val st = TextView(this).apply {
            text = if (hidReady) getString(R.string.ui_touchpad_text_no_frames) else getString(R.string.ui_touchpad_text_not)
            setTextColor(if (hidReady) cAccent else cDanger)
            textSize = 11f
            gravity = Gravity.CENTER
            background = pill(if (hidReady) cAccentSoft else cDangerSoft)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val exit = TextView(this).apply {
            text = getString(R.string.ui_common_exit)
            setTextColor(cText2)
            textSize = 13f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        row.addView(tv)
        // v184：右侧 chips（状态/被控/设备/遥控/文件/剪贴板/退出）收进可横向滑动的容器 ——
        // chip 一多就顶出屏幕，横滑解决。
        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val chipsScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }
        row.addView(chipsScroll, LinearLayout.LayoutParams(0, -2, 1f))
        chipsRow.addView(st)
        // 被控模式开关：让本机也能被另一台手机控制（无需单独装 TV APK）
        val ctl = TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_controlled)
            setTextColor(cText2)
            textSize = 12f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleControlled() }
            // 长按 = 看注入通道自检（哪一档能力、缺什么、怎么办）
            setOnLongClickListener { showInjectCaps(); true }
        }
        controlledChip = ctl
        chipsRow.addView(ctl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        // 右上角设备选择器：切换要控制的设备（本机/PC 或局域网内的 TV）
        val dev = TextView(this).apply {
            text = ControlTarget.label
            setTextColor(cText2)
            textSize = 12f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { showDevicePicker() }
        }
        deviceChip = dev
        chipsRow.addView(dev, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        // 遥控器入口：真机反馈"找不到遥控器的界面" —— 在操控面顶部也放一个，随手可达
        val remote = TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_remote)
            setTextColor(cAccent)
            textSize = 12f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@TouchpadActivity, com.allperiph.ui.RemoteActivity::class.java)
                )
            }
        }

        // 仅当选中受控设备（手机 / PC / TV）时展示：发文件 / 发剪贴板
        val file = TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_file)
            setTextColor(cText2)
            textSize = 12f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, REQ_PICK_FILE)
            }
        }
        val clip = TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_clipboard)
            setTextColor(cText2)
            textSize = 12f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { sendClipboard() }
        }
        fileBtn = file
        clipBtn = clip
        chipsRow.addView(remote, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        chipsRow.addView(file, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        chipsRow.addView(clip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        // 全屏入口：按用户原话「虚拟触控板的页能不能加个全屏模式，把页内其它控件都隐藏」
        val fs = TextView(this).apply {
            text = getString(R.string.ui_touchpad_fullscreen_enter)
            setTextColor(cAccent)
            textSize = 12f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { setFullscreen(true) }
        }
        fullscreenChip = fs
        chipsRow.addView(fs, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        chipsRow.addView(exit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        wrap.addView(row)

        val sub = TextView(this).apply {
            text = pages[0].sub
            setTextColor(cText2)
            textSize = 13f
        }
        wrap.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        titleView = tv
        statusView = st
        subView = sub
        headerView = wrap
        return wrap
    }

    // —————————————————————————— 第 0 页：触控板 + 常用快捷键 ——————————————————————————
    /**
     * v208：方向键叠层——十字方向键 + 中心 OK，居中覆盖手势面；
     * 箭头 usage 与 [com.allperiph.ui.KeyLayouts] 方向布局一致（USB HID Keyboard Page 0x07），
     * 中心 OK = ⏎/Enter（KeyLayouts.ENTER = 0x28），与方向布局的确认键同 usage。
     * 仅键帽区域消费触摸，键间空隙和手势面其余区域仍走 Activity.onTouchEvent → 触控引擎。
     */
    private fun dpadOverlay(): View {
        fun cell() = LinearLayout.LayoutParams(dp(36), dp(36))
        fun key(label: String, usage: Int) = keyCap(label, usage)
        fun gap() = View(this)
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(cBg, dp(14))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val up = row()
        up.addView(gap(), cell())
        up.addView(key("↑", 0x52), cell())
        up.addView(gap(), cell())
        val mid = row()
        mid.addView(key("←", 0x50), cell())
        mid.addView(key("OK", 0x28), cell())
        mid.addView(key("→", 0x4F), cell())
        val down = row()
        down.addView(gap(), cell())
        down.addView(key("↓", 0x51), cell())
        down.addView(gap(), cell())
        col.addView(up)
        col.addView(mid)
        col.addView(down)
        return col
    }

    /** 手势面占满剩余空间；页面内子视图除 chips 外均不可点击 → 事件兜底到 Activity.onTouchEvent */
    private fun touchpadPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        val area = FrameLayout(this).apply {
            background = card(cCard, dp(22))
            isClickable = false
            isFocusable = false
        }
        val hint = TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_cursor_swipe)
            setTextColor(cText3)
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = false
        }
        area.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(14)
        })
        col.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        tpPageCol = col
        tpArea = area
        tpHint = hint
        // v208：方向键居中覆盖手势面（带中心 OK），四周与键间空隙仍可滑动 ——
        // 键帽只消费自身区域，键外照常走触控引擎，滑动与方向同屏。
        tpDpad = dpadOverlay().also { area.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }

        // 常用快捷键：网格 + 拖拽排序（与主页同一套手势），抬手即用，不占整页
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
        head.addView(TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_hotkey); setTextColor(cText); textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = getString(R.string.ui_common_edit); setTextColor(cAccent); textSize = 12f
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { hotkeyBoard.managerDialog() }
        })
        // 网格容器：每行 4 个（8 个正好两行），块比原来的横滚小胶囊更大
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipRow = chips
        // 全屏时「常用快捷键」整块收起（标题行 + 网格一起），所以给它们一个共同容器
        val hotkeySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hotkeySection.addView(head)
        hotkeySection.addView(chips, LinearLayout.LayoutParams(-1, -2))
        col.addView(hotkeySection, LinearLayout.LayoutParams(-1, -2))
        tpHotkeySection = hotkeySection
        // 快捷键网格：行为与主页完全同一份实现（ui/HotkeyBoard），这里只给中性色皮肤
        hotkeyBoard = com.allperiph.ui.HotkeyBoard(this, chips, { boardStyle() })
        hotkeyBoard.render()
        return col
    }

    /** 操控面版皮肤：中性色（与主页的模板色区分开），每行 4 个 */
    private fun boardStyle(): com.allperiph.ui.HotkeyBoard.Style = com.allperiph.ui.HotkeyBoard.Style(
        chipText = cText,
        chipBg = cCard,
        chipStroke = cStroke,
        chipRadiusDp = 14,
        padVDp = 13,
        perRow = 4,
        accent = cAccent,
        textPrimary = cText,
        textSecondary = cText2,
    )


    // —————————————————————————— 第 1 页：键盘（三套键位） ——————————————————————————

    private fun keyboardPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(12))
        }
        // 键位切换：分段胶囊（快捷 / 遥控 / 游戏）
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(10))
        }
        kbTabs = kbNames.mapIndexed { i, name ->
            val t = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener { showKeyboard(i) }
                if (name == "自定义") {
                    setOnLongClickListener {
                        com.allperiph.ui.CustomKeyboardDialog.show(this@TouchpadActivity) {
                            val idx = kbNames.indexOf("自定义")
                            if (idx in 0 until kbNames.size) showKeyboard(idx)
                        }
                        true
                    }
                }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(dp(76), -2).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
            t
        }
        // 7 套布局一屏放不下 → 页签行横向可滑
        col.addView(
            android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabRow)
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        kbPager = ViewFlipper(this).apply {
            addView(quickKeyboard())
            addView(remoteKeyboard())
            addView(gameKeyboard())
            addView(numpadKeyboard())
            addView(grid9Keyboard())
            addView(arrowKeyboard())
            addView(fnKeyboard())
            addView(customKeyboard())
        }
        col.addView(kbPager, LinearLayout.LayoutParams(-1, 0, 1f))
        showKeyboard(0)
        return col
    }

    private fun showKeyboard(i: Int) {
        if (!::kbPager.isInitialized || i !in kbNames.indices) return
        kbPager.displayedChild = i
        kbTabs.forEachIndexed { k, t ->
            val on = k == i
            t.background = pill(if (on) cAccent else cAccentSoft)
            t.setTextColor(if (on) cCard else cAccent)
            t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /** 快捷键盘：sticky 修饰排 + 5 行 QWERTY */
    private fun quickKeyboard(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val modRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        HidKeys.MOD_LABELS.forEach { (text, bit) ->
            val b = keyCap(text, true) {
                HidKeys.toggleSticky(bit)
                refreshMods()
            }
            modBtns.getOrPut(bit) { mutableListOf() }.add(b)
            modRow.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(6))
            })
        }
        col.addView(modRow, LinearLayout.LayoutParams(-1, 0, 1f))
        com.allperiph.ui.KeyPref.letterRows(this).forEach { labels ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val g = dp(dens.gapDp)
            labels.forEach { text ->
                val b = keyCap(text, false) {
                    HidKeys.tap(HidKeys.usageByLabel(text)) // letterRows 含符号行，按 label 反查更全
                    refreshMods() // tap 会清 sticky，同步熄灭修饰键
                }
                row.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(g, g, g, g)
                })
            }
            col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return col
    }

    /** 遥控键盘：媒体 / 音量（音量键按住连发） */
    private fun remoteKeyboard(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rows = listOf(
            listOf(
                Triple(getString(R.string.ui_touchpad_media_prev), R.drawable.ic_apx_prev, HotkeyController.BIT_PREV_TRACK),
                Triple(getString(R.string.ui_touchpad_label_play_pause), R.drawable.ic_apx_play, HotkeyController.BIT_PLAY_PAUSE),
                Triple(getString(R.string.ui_touchpad_media_next), R.drawable.ic_apx_next, HotkeyController.BIT_NEXT_TRACK),
            ),
            listOf(
                Triple(getString(R.string.ui_touchpad_label_volume), R.drawable.ic_apx_vol_down, HotkeyController.BIT_VOLUME_DOWN),
                Triple(getString(R.string.ui_touchpad_media_mute), R.drawable.ic_apx_mute, HotkeyController.BIT_MUTE),
                Triple(getString(R.string.ui_touchpad_label_volume_2), R.drawable.ic_apx_vol_up, HotkeyController.BIT_VOLUME_UP),
            ),
        )
        rows.forEach { defs ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            defs.forEach { (text, icon, bit) ->
                row.addView(mediaTile(text, icon, bit), LinearLayout.LayoutParams(0, dp(110), 1f).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        // 第三行：电源（软，待机/唤醒）/ 关机 / 重启。
        // ★ 原先这里的"遥控键盘"只有两行（媒体 + 音量），而主页横屏键盘的同一个布局**已经有**
        //   电源三键 —— 两个入口能力不一致，用户在全屏操控面里根本找不到关机键。
        //   关机 / 重启走控制帧 opcode 0x22，需要被控端有 root，所以弹确认框。
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tileLp() = LinearLayout.LayoutParams(0, dp(110), 1f)
            .apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
        pr.addView(mediaTile(getString(R.string.ui_touchpad_label_power), R.drawable.ic_apx_power, HotkeyController.BIT_POWER), tileLp())
        pr.addView(actionTile(getString(R.string.ui_touchpad_label_poweroff), 0xFFD23F31.toInt()) { confirmPower(0, getString(R.string.ui_touchpad_label_poweroff)) }, tileLp())
        pr.addView(actionTile(getString(R.string.ui_touchpad_label_reboot), 0xFFF0A020.toInt()) { confirmPower(1, getString(R.string.ui_touchpad_label_reboot)) }, tileLp())
        col.addView(pr, LinearLayout.LayoutParams(-1, -2))
        return col
    }

    /** 动作图块：没有 Consumer 位，点了直接执行（关机 / 重启） */
    private fun actionTile(text: String, accent: Int, onClick: () -> Unit): View = TextView(this).apply {
        this.text = text
        setTextColor(accent)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = 16f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = card(cCard, dp(18))
        setOnClickListener { com.allperiph.ui.Feedback.tap(this); onClick() }
    }

    /** 关机 / 重启确认：会直接关掉被控设备，别让误触生效 */
    private fun confirmPower(action: Int, label: String) {
        val c = com.allperiph.wireless.ControlTarget.controlClient
        if (c == null) {
            Toast.makeText(this, getString(R.string.ui_touchpad_toast_device_connect_not), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_touchpad_dialog_device, label))
            .setMessage(getString(R.string.ui_touchpad_dialog_no_only_need, label))
            .setPositiveButton(label) { _, _ ->
                c.power(action)
                Toast.makeText(this, getString(R.string.ui_touchpad_toast_send_done, label), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .show()
    }

    /** 注入通道自检：被控端"缺哪一项"在手机上原先只有通知栏一行字 */
    private fun showInjectCaps() {
        val inj = com.allperiph.shared.inject.TvInjector
        val lines = inj.capabilities().joinToString("\n") { (name, ok, how) ->
            if (ok) "✓  $name" else "✗  $name\n      → $how"
        }
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_touchpad_dialog_selftest_channel_inject))
            .setMessage(getString(R.string.ui_touchpad_dialog_control_state, lines, inj.channelText()))
            .setPositiveButton(getString(R.string.ui_common_close), null)
            .show()
    }

    private fun mediaTile(text: String, icon: Int, bit: Int): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = card(cCard, dp(18))
            isClickable = true
            isFocusable = true
        }
        tile.addView(ImageView(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(cAccent)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        tile.addView(TextView(this).apply {
            this.text = text; setTextColor(cText2); textSize = 12f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        if (bit == HotkeyController.BIT_VOLUME_UP || bit == HotkeyController.BIT_VOLUME_DOWN) {
            // 音量键：按住连发（PC 按位图边沿计数，须反复 按下→释放）
            tile.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = card(cAccentSoft, dp(18))
                        com.allperiph.ui.Feedback.tap(v)
                        rippleOn(v, ev.x, ev.y)
                        mediaTap(bit)
                        val task = object : Runnable {
                            override fun run() {
                                com.allperiph.ui.Feedback.haptic(v) // 连发只震动
                                mediaTap(bit)
                                handler.postDelayed(this, 250)
                            }
                        }
                        volRepeat = task
                        handler.postDelayed(task, 400)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.background = card(cCard, dp(18))
                        volRepeat?.let { handler.removeCallbacks(it) }
                        volRepeat = null
                        HotkeyController.release(bit) // 兜底确保释放
                    }
                }
                true
            }
        } else {
            tile.setOnClickListener {
                com.allperiph.ui.Feedback.tap(it)
                rippleOn(it, tile.width / 2f, tile.height / 2f)
                mediaTap(bit)
            }
        }
        return tile
    }

    /** Consumer 键点按：PC 按位图边沿识别，故 按下 60ms → 释放 */
    private fun mediaTap(bit: Int) {
        HotkeyController.press(bit)
        handler.postDelayed({ HotkeyController.release(bit) }, 60)
    }

    /**
     * 游戏键盘：4×6 高频键位。
     * 第 1 行技能数字、第 2/3 行左手技能区（Q W E R F B / A S D + 修饰）、
     * 第 4 行方向键与确认键 —— 覆盖 MOBA / FPS 的默认手位。
     */
    private fun gameKeyboard(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val layout = listOf(
            listOf("Esc", "1", "2", "3", "4", "Tab"),
            listOf("Q", "W", "E", "R", "F", "B"),
            listOf("A", "S", "D", "Shift", "Space", "Ctrl"),
            listOf("↑", "↓", "←", "→", "Enter", "Del"),
        )
        layout.forEach { labels ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            labels.forEach { text ->
                val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == text }?.second
                val b = keyCap(text, bit != null) {
                    if (bit != null) {
                        HidKeys.toggleSticky(bit)
                    } else {
                        HidKeys.tap(HidKeys.usageByLabel(text))
                    }
                    refreshMods()
                }
                if (bit != null) modBtns.getOrPut(bit) { mutableListOf() }.add(b)
                row.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return col
    }

    /** MIUIX 键帽：白底细描边 + 9dp 圆角；修饰键用浅蓝底/蓝字，锁定后实蓝底白字 */
    private fun keyCap(text: String, modifier: Boolean, onTap: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = dens.textSize
        gravity = Gravity.CENTER
        setTextColor(if (modifier) cAccent else cText)
        val corner = dp(dens.cornerDp)
        val restBg = { if (modifier) card(cAccentSoft, corner) else strokeCard(cCard, corner, cStroke) }
        background = restBg()
        isClickable = true
        isFocusable = true
        // ---- 长按连发（issue #52 建议 7）----
        // 按住不放 → 400ms 后每 55ms 重复发送同一个键，退格 / 方向键 / 字母可以一口气
        // 连打，不用一下一下戳。松开时**吞掉多出来的那一次 click** —— 否则长按退格会
        // 多删一个字符（连发 N 次 + 抬手 1 次）。修饰键不参与：Shift / Ctrl / Caps 之类
        // 连发只会把修饰状态反复开关，没有任何意义。
        var repeatOn = false
        var repeatFired = false
        val tick = object : Runnable {
            override fun run() {
                if (!repeatOn) return
                repeatFired = true
                onTap()
                this@apply.postDelayed(this, 55L)
            }
        }
        setOnClickListener {
            if (repeatFired) {
                repeatFired = false
            } else {
                onTap()
            }
        }
        // M3 Expressive 弹性反馈（Issue #15）：Spring 物理动画替代固定 duration
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ExpressiveFeedback.press(v)
                    v.background = card(cAccentSoft, corner)
                    com.allperiph.ui.Feedback.tap(v)
                    rippleOn(v, e.x, e.y)
                    if (!modifier) {
                        repeatOn = true
                        repeatFired = false
                        v.postDelayed(tick, 400L)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ExpressiveFeedback.release(v)
                    v.background = restBg()
                    if (repeatOn) {
                        repeatOn = false
                        v.removeCallbacks(tick)
                    }
                    // CANCEL 不会再来一次 click，标志要当场清掉，否则下一次短按被吞
                    if (e.actionMasked == MotionEvent.ACTION_CANCEL) repeatFired = false
                }
            }
            false // 交回给 click，发送逻辑不变
        }
    }

    /**
     * 玻璃扩散：从落点向外铺开一圈波前（[com.allperiph.ui.GlassRipple]），画在 `foreground` 上，
     * 因此不会与键帽底色 / 修饰键高亮（走 `background`）互相覆盖。
     */
    /** 显式 usage 的键帽（扩展布局用：小键盘的键面文字与 usage 不是一一对应） */
    private fun keyCap(text: String, usage: Int): TextView = keyCap(text, false) { HidKeys.tap(usage) }

    /**
     * 自定义布局（v1.33）：用户在 [CustomKeyboardDialog] 编辑的整套行表；
     * 未配置时给占位提示，配置后按行渲染。
     */
    private fun customKeyboard(): View {
        val rows = com.allperiph.ui.KeyPref.customRows(this)
        if (rows.isNullOrEmpty()) {
            return TextView(this).apply {
                text = getString(R.string.ui_touchpad_text_edit_longpress)
                gravity = Gravity.CENTER
                setTextColor(cText2)
                textSize = 14f
            }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val g = dp(dens.gapDp)
        rows.forEach { labels ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            labels.forEach { text ->
                val b = keyCap(text, false) {
                    HidKeys.tap(HidKeys.usageByLabel(text))
                    refreshMods()
                }
                row.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(g, g, g, g)
                })
            }
            col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return col
    }

    private fun rippleOn(v: View, x: Float, y: Float) {
        if (v.width <= 0 || v.height <= 0) return
        val dur = com.allperiph.ui.ThemeSkin.motionMs(this, 620L)
        if (dur <= 0L) return // 动效关闭时只留震动 / 音效
        val ripple = com.allperiph.ui.GlassRipple(
            x, y,
            getColor(R.color.nav_pill_shine),
            cAccent,
            maxOf(v.width, v.height) * 1.05f,
            dur,
        ).apply {
            onFinish = { if (v.foreground === this) v.foreground = null }
        }
        v.foreground = ripple
        ripple.play()
    }

    private fun refreshMods() {
        modBtns.forEach { (bit, list) ->
            val on = HidKeys.isSticky(bit)
            list.forEach { b ->
                b.background = card(if (on) cAccent else cAccentSoft, dp(9))
                b.setTextColor(if (on) cCard else cAccent)
            }
        }
    }

    // —————————————————————————— 扩展布局（v1.32）：数字 / 九宫格 / 方向 / F 区 ——————————————————————————

    /** 按 [com.allperiph.ui.KeyLayouts] 的行列渲染：行高等分、列宽按权重 */
    private fun keyGrid(rows: List<List<com.allperiph.ui.KeyLayouts.Key>>): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { row ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { k ->
                r.addView(keyCap(k.label, k.usage), LinearLayout.LayoutParams(0, -1, k.weight).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            col.addView(r, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return col
    }

    private fun numpadKeyboard() = keyGrid(com.allperiph.ui.KeyLayouts.NUMPAD)

    private fun grid9Keyboard() = keyGrid(com.allperiph.ui.KeyLayouts.GRID9)

    private fun arrowKeyboard() = keyGrid(com.allperiph.ui.KeyLayouts.ARROWS)

    private fun fnKeyboard() = keyGrid(com.allperiph.ui.KeyLayouts.FN)

    // —————————————————————————— 页签栏与翻页 ——————————————————————————

    private fun buildTabBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cCard)
        }
        wrap.addView(View(this).apply { setBackgroundColor(cDivider) },
            LinearLayout.LayoutParams(-1, dp(1)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs = pages.mapIndexed { i, p ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener { showPage(i) }
            }
            val icon = ImageView(this).apply {
                setImageResource(p.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val labelView = TextView(this).apply {
                text = p.title; textSize = 10f; gravity = Gravity.CENTER
            }
            col.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            col.addView(labelView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            Tab(icon, labelView)
        }
        wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
        tabBarView = wrap
        return wrap
    }

    private fun showPage(i: Int) {
        if (i !in pages.indices) return
        // 全屏只服务于触控板页：切页前先退出全屏（页签栏在全屏里是隐藏的，
        // 只有「从别处回本页」这类路径会撞上，兜一手免得停在半截状态）
        if (fullscreen && i != PAGE_TOUCHPAD) setFullscreen(false)
        handler.removeCallbacks(dragTask)
        handler.removeCallbacks(rightDragTask)
        flipper.displayedChild = i
        titleView?.text = pages[i].title
        subView?.text = pages[i].sub
        if (i == PAGE_TOUCHPAD) hotkeyBoard.render()
        if (i == PAGE_DISPLAY) updateDisplayStatus()
        refreshTabs()
    }

    // —————————————————————————— 第 2 页：副屏（显示）+ 链接 TV/PC ——————————————————————————
    private fun displayPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        // 副屏（显示）状态卡
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_screen)
            setTextColor(cText); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        })
        val status = TextView(this).apply {
            text = WirelessModule.statusText()
            setTextColor(cText2); textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        displayStatusView = status
        card.addView(status)
        card.addView(TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_screen_recv_fullscreen_local)
            setTextColor(cText2); textSize = 12f
        })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 链接设备
        col.addView(TextView(this).apply {
            text = getString(R.string.ui_touchpad_text_device)
            setTextColor(cAccent); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setPadding(dp(4), 0, 0, dp(8))
        })
        val linkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val tvBtn = Button(this).apply {
            text = getString(R.string.ui_main_item_link)
            setBackgroundResource(R.drawable.bg_btn_primary)
            setTextColor(com.allperiph.ui.ThemeSkin.onPrimary(this@TouchpadActivity))
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { showDevicePicker() }
        }
        linkCard.addView(tvBtn)
        val pcBtn = Button(this).apply {
            text = getString(R.string.ui_main_item_screen)
            setBackgroundResource(R.drawable.bg_btn_ghost)
            setTextColor(cAccent)
            textSize = 15f
            setPadding(0, dp(13), 0, dp(13))
            setOnClickListener {
                startActivity(Intent(this@TouchpadActivity, com.allperiph.screen.ScreenActivity::class.java))
            }
        }
        linkCard.addView(pcBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        col.addView(linkCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return col
    }

    private fun updateDisplayStatus() {
        displayStatusView?.text = WirelessModule.statusText()
    }

    private fun refreshTabs() {
        tabs.forEachIndexed { i, t ->
            val on = i == flipper.displayedChild
            val color = if (on) cAccent else cTabIdle
            t.icon.setColorFilter(color)
            t.label.setTextColor(color)
            t.label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // —————————————————————————— 触摸兜底（v1.7b 架构保留） ——————————————————————————

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // 全屏下「从屏幕最上沿往下拉」= 退出全屏（附加路径，系统手势导航可能先吃掉这一步）。
        // 这段手势整段吞掉、绝不喂手势引擎，否则退出动作会顺手把光标也甩走一段；
        // 注意要吞到 UP 为止 —— 中途开始喂引擎会送出一个没有 DOWN 的 MOVE。
        if (fullscreen) {
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
        }
        if (fsSwallow) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                fsSwallow = false
                fsExiting = false
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwiped = false
                val w = resources.displayMetrics.widthPixels
                // 全屏里不做边缘翻页：不动的触控板上，左右边缘跟别处没有区别，
                // 留着只会变成「滑到边上页面跑了」
                edgeStartX = if (!fullscreen && flipper.displayedChild == PAGE_TOUCHPAD &&
                    (ev.x < dp(28) || ev.x > w - dp(28))
                ) ev.x else -1f
                if (edgeStartX < 0f) handler.postDelayed(dragTask, 550)
            }
            // 第二指落下 = 双指长按的起点（右键拖拽）；边缘手势期间不抢
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2 && edgeStartX < 0f) {
                handler.postDelayed(rightDragTask, 550)
            }
            MotionEvent.ACTION_MOVE -> if (edgeStartX >= 0f && !edgeSwiped) {
                val dx = ev.x - edgeStartX
                if (abs(dx) > dp(80)) {
                    edgeSwiped = true
                    val n = flipper.childCount
                    showPage(if (dx < 0) (flipper.displayedChild + 1) % n else (flipper.displayedChild + n - 1) % n)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(dragTask)
                handler.removeCallbacks(rightDragTask)
                edgeStartX = -1f
            }
        }
        // 仅触控板页且非边缘手势才喂手势引擎
        if (flipper.displayedChild == PAGE_TOUCHPAD && edgeStartX < 0f && !edgeSwiped) {
            (AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule)?.feed(ev)
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastShown > 250) { // 4Hz 刷新，避免 UI 抖动
                lastShown = now
                if (hidReady) statusView?.text = getString(R.string.ui_touchpad_text_done, frameCount)
            }
        }
        return true
    }

    // —————————————————————————— 全屏触控板 ——————————————————————————

    /**
     * 切换全屏触控板（v223.2，#47 第 1 条）。
     *
     * 为什么是界面方案而不是真锁光标：Android 没给第三方 App「把系统光标限制在某区域」的
     * API —— 光标由 InputReader + SurfaceFlinger 管，应用层只能把本窗口光标图标设成
     * TYPE_NULL（就是用户看到的「只有隐藏光标生效」）。所以让光标即使存在也没有可误触的
     * 控件，是唯一不依赖 root 的做法。
     *
     * 进出都只改可见性与内边距，不动视图树（避免重建后手势引擎与 ViewFlipper 状态错位）。
     * 退出的**可靠**路径就两条：返回键、右下角小胶囊（3s 后自动降到 25% 不透明度）。
     * 上沿下滑那条（onTouchEvent 里的 fsSwallow）是顺手留的附加路径 —— 手势导航的设备上
     * 上沿下滑会被系统拿去拉通知栏，所以它只在部分机型上生效，**不写进提示文案**，
     * 免得给用户一个按了没反应的操作。
     */
    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        fsPillFade?.let { handler.removeCallbacks(it) }
        dropFsTip()

        headerView?.visibility = if (on) View.GONE else View.VISIBLE
        // 全屏只可能停在触控板页（showPage 有守卫），页签栏一律收起
        tabBarView?.visibility = if (on) View.GONE else View.VISIBLE
        tpHotkeySection?.visibility = if (on) View.GONE else View.VISIBLE
        tpDpad?.visibility = if (on) View.GONE else View.VISIBLE
        tpHint?.visibility = if (on) View.GONE else View.VISIBLE
        fullscreenChip?.visibility = if (on) View.GONE else View.VISIBLE

        // 手势面撑满：去掉页边距与卡片圆角，整块屏幕就是一整片触控板
        tpPageCol?.setPadding(
            if (on) 0 else dp(16), 0,
            if (on) 0 else dp(16),
            if (on) 0 else dp(10)
        )
        tpArea?.background = if (on) card(cBg, 0) else card(cCard, dp(22))

        val pill = fullscreenExitPill
        if (pill != null) {
            pill.visibility = if (on) View.VISIBLE else View.GONE
            pill.alpha = if (on) 0.6f else 0f
            if (on) {
                val fade = Runnable { pill.animate().alpha(0.25f).setDuration(400).start() }
                fsPillFade = fade
                handler.postDelayed(fade, 3000)
            }
        }

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

        if (on) {
            // 一次性说明：第一次进全屏必须让人知道怎么出去，否则就是「进去了出不来」的坑
            val tip = TextView(this).apply {
                text = getString(R.string.ui_touchpad_fullscreen_hint)
                setTextColor(cBg)
                textSize = 12f
                gravity = Gravity.CENTER
                background = pill(cText)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                alpha = 0f
            }
            (fullscreenExitPill?.parent as? FrameLayout)?.addView(
                tip, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = dp(48) }
            )
            fsTip = tip
            tip.animate().alpha(0.92f).setDuration(180).start()
            val hide = Runnable { dropFsTip() }
            fsTipHide = hide
            handler.postDelayed(hide, 2600)
        }
    }

    /** 全屏说明条：进入时弹一次，2.6s 后自己淡出移除（中途退出全屏则立刻收掉） */
    private var fsTip: TextView? = null
    private var fsTipHide: Runnable? = null

    private fun dropFsTip() {
        fsTipHide?.let { handler.removeCallbacks(it) }
        fsTipHide = null
        val tip = fsTip ?: return
        fsTip = null
        tip.animate().alpha(0f).setDuration(200).withEndAction {
            (tip.parent as? ViewGroup)?.removeView(tip)
        }.start()
    }

    // —————————————————————————— 样式与尺寸工具 ——————————————————————————

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cText2)
        textSize = 12f
    }

    private fun card(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun strokeCard(color: Int, radius: Int, stroke: Int): GradientDrawable =
        card(color, radius).apply { setStroke(1, stroke) }

    private fun pill(color: Int): GradientDrawable = card(color, dp(999))

    // ————————————————————————— 返回键 —————————————————————————

    /** 退出确认框（连按返回时不叠出多个） */
    private var backDialog: AlertDialog? = null

    /** 返回键先确认：操控面退出后外设照常运行，但误触退出会打断手上的操作 */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 全屏时返回键先退出全屏（这里连确认框都不该弹：用户只是想回到控制面）
        if (fullscreen) { setFullscreen(false); return }
        if (backDialog?.isShowing == true) return
        backDialog = AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
            .setTitle(getString(R.string.ui_touchpad_dialog_exit_console))
            .setMessage(getString(R.string.ui_touchpad_dialog_home))
            .setPositiveButton(getString(R.string.ui_common_exit)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.ui_common_cancel), null)
            .create()
            .also { it.show() }
    }

    // —————————————————————————— 设备选择（控制目标） ——————————————————————————

    private fun updateDeviceChip() {
        val c = deviceChip ?: return
        val ready = ControlTarget.isControlling()
        // 出口一并显示：点不动时先看这里到底"发去哪了"。注意本页不订阅事件（无 disposables），
        // 所以这里展示的是**刷新那一刻**的出口；实时显示在主界面顶栏徽章与触控板提示行。
        c.text = (if (ready) getString(R.string.ui_touchpad_text_device_2, ControlTarget.label) else ControlTarget.label) +
            " · ${com.allperiph.core.Uplink.hint()}"
        c.setTextColor(if (ready) cAccent else cText2)
        fileBtn?.visibility = if (ready) View.VISIBLE else View.GONE
        clipBtn?.visibility = if (ready) View.VISIBLE else View.GONE
    }

    // —————————————————————————— 被控模式（本机可被另一台手机控制） ——————————————————————————

    private fun updateControlledChip() {
        val c = controlledChip ?: return
        val on = ControlledService.isRunning()
        c.text = if (on) getString(R.string.ui_touchpad_text_controlled_ok) else getString(R.string.ui_touchpad_text_controlled)
        c.setTextColor(if (on) cAccent else cText2)
    }

    /** 切换被控模式：开启→请求权限并启动前台服务；关闭→停止服务 */
    private fun toggleControlled() {
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
        val preview = if (text.length > 24) text.take(24) + "…" else text
        Toast.makeText(this, getString(R.string.ui_touchpad_toast_clipboard, preview), Toast.LENGTH_LONG).show()
    }

    /** 设备选择弹窗：本机/PC（默认）+ 已发现 TV + 手动输入 IP */
    private fun showDevicePicker() {
        TvDiscovery.start()
        val items = ArrayList<CharSequence>()
        items.add(getString(R.string.ui_main_item_local))
        val tvs = TvDiscovery.list()
        tvs.forEach { items.add("${it.name}  ${it.ip}  ${it.typeLabel}") }
        items.add(getString(R.string.ui_main_item_input_manual))
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
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
        val edit = EditText(this).apply {
            hint = getString(R.string.ui_touchpad_hint_manual_ip)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this, com.allperiph.ui.ThemeSkin.dialogStyle())
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
                    // 并留下 socket + tx/ping/reconn 线程（与 MainActivity 同一处修复）
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
                    Toast.makeText(this, getString(R.string.ui_touchpad_toast_connect_failed, ip), Toast.LENGTH_SHORT).show()
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
                runOnUiThread { statusView?.text = getString(R.string.ui_touchpad_send_progress, p) }
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) getString(R.string.ui_main_text_file_send_done) else getString(R.string.ui_touchpad_text_send_failed), Toast.LENGTH_SHORT).show()
                statusView?.text = if (hidReady) getString(R.string.ui_touchpad_text_no_frames) else getString(R.string.ui_touchpad_text_not)
            }
        }, "tv-file").start()
    }

    /** 读本机剪贴板并发送到对端设备（写入对方系统剪贴板 + 当前聚焦输入框） */
    private fun sendClipboard() {
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 启动参数：直接落到指定页签（0 触控板 / 1 键盘） */
        const val EXTRA_PAGE = "page"

        private const val PAGE_TOUCHPAD = 0
        private const val PAGE_KEYBOARD = 1
        private const val PAGE_DISPLAY = 2

        /** 三指手势遮蔽的持有者标识（与 MainActivity 的 main 区分开） */
        private const val HOLDER_CONSOLE = "console"
    }
}
