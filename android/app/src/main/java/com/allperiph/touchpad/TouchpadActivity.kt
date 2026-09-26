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
import com.allperiph.wireless.TvFileSender
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
        else getColor(R.color.miuix_accent)
    private val cAccentSoft get() =
        if (kbSkinPicked) com.allperiph.ui.ThemeSkin.current(this, com.allperiph.ui.ThemeSkin.KEYBOARD).accentSoft
        else getColor(R.color.miuix_accent_soft)

    /** 键帽密度（设置页可换） */
    private val dens get() = com.allperiph.ui.KeyPref.density(this)
    private val cDangerSoft get() = getColor(R.color.md_error_container)
    private val cDanger get() = getColor(R.color.miuix_danger)
    private val cTabIdle get() = getColor(R.color.state_idle)

    /** 页签定义 */
    private class Page(val title: String, val sub: String, val icon: Int)

    /** 页签视图对：图标按选中态染色 */
    private class Tab(val icon: ImageView, val label: TextView)

    private val pages = listOf(
        Page("触控板", "手势操控 · 常用快捷键长按可编辑", R.drawable.ic_apx_touchpad),
        Page("键盘", "8 套布局：快捷 / 遥控 / 游戏 / 数字 / 九宫格 / 方向 / F 区 / 自定义", R.drawable.ic_apx_keyboard),
        Page("副屏", "手机作副屏 / 链接 TV · PC", R.drawable.ic_apx_power),
    )

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

    // 媒体音量按住连发
    private var volRepeat: Runnable? = null

    // 边缘滑动翻页状态
    private var edgeStartX = -1f
    private var edgeSwiped = false

    // —————————————————————————— 装配 ——————————————————————————

    /** 主题方案：把用户在设置页选的主题注入资源配置（深色取值在 values-night） */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.allperiph.ui.ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.7：触控板模式防截屏/防投屏窥视（架构 §4 触控板模式屏幕为手势采集面）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
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
        // v1.7 修复：显式 MATCH_PARENT（root 高度塌缩曾致触摸区只剩一小条）
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        refreshTabs()
        updateControlledChip()
        // 主页「设置 → 管理快捷键」用 EXTRA_PAGE 直达键盘页；launchMode=singleTask，
        // 已在栈中时走 onNewIntent
        showPage(intent?.getIntExtra(EXTRA_PAGE, PAGE_TOUCHPAD) ?: PAGE_TOUCHPAD)
        // 预启动 TV 发现（监听 APX1TV 信标），让设备列表在打开选择器时已就绪
        TvDiscovery.start()
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
            text = if (hidReady) "0 帧" else "HID 未就绪"
            setTextColor(if (hidReady) cAccent else cDanger)
            textSize = 11f
            gravity = Gravity.CENTER
            background = pill(if (hidReady) cAccentSoft else cDangerSoft)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val exit = TextView(this).apply {
            text = "退出"
            setTextColor(cText2)
            textSize = 13f
            gravity = Gravity.CENTER
            background = pill(cCard)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(st)
        // 被控模式开关：让本机也能被另一台手机控制（无需单独装 TV APK）
        val ctl = TextView(this).apply {
            text = "被控"
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
        row.addView(ctl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

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
        row.addView(dev, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        // 遥控器入口：真机反馈"找不到遥控器的界面" —— 在操控面顶部也放一个，随手可达
        val remote = TextView(this).apply {
            text = "遥控"
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
        row.addView(remote, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        // 仅当选中受控设备（手机 / PC / TV）时展示：发文件 / 发剪贴板
        val file = TextView(this).apply {
            text = "文件"
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
            text = "剪贴板"
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
        row.addView(file, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        row.addView(clip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        row.addView(exit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
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
        return wrap
    }

    // —————————————————————————— 第 0 页：触控板 + 常用快捷键 ——————————————————————————

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
        area.addView(TextView(this).apply {
            text = "在此区域滑动控制光标"
            setTextColor(cText3)
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = false
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        col.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))

        // 常用快捷键：网格 + 拖拽排序（与主页同一套手势），抬手即用，不占整页
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
        head.addView(TextView(this).apply {
            text = "常用快捷键"; setTextColor(cText); textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = "编辑"; setTextColor(cAccent); textSize = 12f
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { hotkeyBoard.managerDialog() }
        })
        col.addView(head)

        // 网格容器：每行 4 个（8 个正好两行），块比原来的横滚小胶囊更大
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipRow = chips
        col.addView(chips, LinearLayout.LayoutParams(-1, -2))
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
                Triple("上一曲", R.drawable.ic_apx_prev, HotkeyController.BIT_PREV_TRACK),
                Triple("播放 / 暂停", R.drawable.ic_apx_play, HotkeyController.BIT_PLAY_PAUSE),
                Triple("下一曲", R.drawable.ic_apx_next, HotkeyController.BIT_NEXT_TRACK),
            ),
            listOf(
                Triple("音量 −", R.drawable.ic_apx_vol_down, HotkeyController.BIT_VOLUME_DOWN),
                Triple("静音", R.drawable.ic_apx_mute, HotkeyController.BIT_MUTE),
                Triple("音量 +", R.drawable.ic_apx_vol_up, HotkeyController.BIT_VOLUME_UP),
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
        pr.addView(mediaTile("电源", R.drawable.ic_apx_power, HotkeyController.BIT_POWER), tileLp())
        pr.addView(actionTile("关机", 0xFFD23F31.toInt()) { confirmPower(0, "关机") }, tileLp())
        pr.addView(actionTile("重启", 0xFFF0A020.toInt()) { confirmPower(1, "重启") }, tileLp())
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
            Toast.makeText(this, "尚未连接受控设备", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("$label 被控设备？")
            .setMessage("将直接$label 对端（需要被控端有 root；没有 root 只会退回待机）。")
            .setPositiveButton(label) { _, _ ->
                c.power(action)
                Toast.makeText(this, "已发送$label 指令", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 注入通道自检：被控端"缺哪一项"在手机上原先只有通知栏一行字 */
    private fun showInjectCaps() {
        val inj = com.allperiph.controlled.TvInjector
        val lines = inj.capabilities().joinToString("\n") { (name, ok, how) ->
            if (ok) "✓  $name" else "✗  $name\n      → $how"
        }
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("注入通道自检")
            .setMessage("当前：${inj.channelText()}\n\n$lines")
            .setPositiveButton("关闭", null)
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
        setOnClickListener { onTap() }
        // 与主页键盘同一套手感：震动 + 触摸音 + 从落点扩散的玻璃波前 + 微缩回弹
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.90f).scaleY(0.90f)
                        .setDuration(com.allperiph.ui.ThemeSkin.motionMs(this@TouchpadActivity, 120L)).start()
                    v.background = card(cAccentSoft, corner)
                    com.allperiph.ui.Feedback.tap(v)
                    rippleOn(v, e.x, e.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(com.allperiph.ui.ThemeSkin.motionMs(this@TouchpadActivity, 360L))
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                    v.background = restBg()
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
                text = "长按「自定义」页签编辑布局"
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
        return wrap
    }

    private fun showPage(i: Int) {
        if (i !in pages.indices) return
        handler.removeCallbacks(dragTask)
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
            text = "副屏（显示）"
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
            text = "手机作为 PC 副屏：PC 端推流到 9502，本机在「副屏」全屏接收显示。"
            setTextColor(cText2); textSize = 12f
        })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 链接设备
        col.addView(TextView(this).apply {
            text = "链接设备"
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
            text = "链接 TV / PC…"
            setBackgroundResource(R.drawable.bg_btn_primary)
            setTextColor(getColor(R.color.md_on_primary))
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { showDevicePicker() }
        }
        linkCard.addView(tvBtn)
        val pcBtn = Button(this).apply {
            text = "加入副屏"
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
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwiped = false
                val w = resources.displayMetrics.widthPixels
                edgeStartX = if (flipper.displayedChild == PAGE_TOUCHPAD &&
                    (ev.x < dp(28) || ev.x > w - dp(28))
                ) ev.x else -1f
                if (edgeStartX < 0f) handler.postDelayed(dragTask, 550)
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
                if (hidReady) statusView?.text = "已上行 $frameCount 帧"
            }
        }
        return true
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
        if (backDialog?.isShowing == true) return
        backDialog = AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("退出操控面？")
            .setMessage("外设服务继续运行，随时可以从主页再进来。")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .create()
            .also { it.show() }
    }

    // —————————————————————————— 设备选择（控制目标） ——————————————————————————

    private fun updateDeviceChip() {
        val c = deviceChip ?: return
        val ready = ControlTarget.isControlling()
        c.text = if (ready) "受控设备 · ${ControlTarget.label}" else ControlTarget.label
        c.setTextColor(if (ready) cAccent else cText2)
        fileBtn?.visibility = if (ready) View.VISIBLE else View.GONE
        clipBtn?.visibility = if (ready) View.VISIBLE else View.GONE
    }

    // —————————————————————————— 被控模式（本机可被另一台手机控制） ——————————————————————————

    private fun updateControlledChip() {
        val c = controlledChip ?: return
        val on = ControlledService.isRunning()
        c.text = if (on) "被控 ✓" else "被控"
        c.setTextColor(if (on) cAccent else cText2)
    }

    /** 切换被控模式：开启→请求权限并启动前台服务；关闭→停止服务 */
    private fun toggleControlled() {
        if (ControlledService.isRunning()) {
            ControlledService.stop(this)
            updateControlledChip()
            Toast.makeText(this, "已关闭被控模式", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "收到对方剪贴板：$preview", Toast.LENGTH_LONG).show()
    }

    /** 设备选择弹窗：本机/PC（默认）+ 已发现 TV + 手动输入 IP */
    private fun showDevicePicker() {
        TvDiscovery.start()
        val items = ArrayList<CharSequence>()
        items.add("本机 / PC（默认）")
        val tvs = TvDiscovery.list()
        tvs.forEach { items.add("${it.name}  ${it.ip}  ${it.typeLabel}") }
        items.add("手动输入 TV IP…")
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("选择控制设备")
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
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptTvIp() {
        val edit = EditText(this).apply {
            hint = "TV IP，如 192.168.1.20"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("输入 TV IP")
            .setView(edit)
            .setPositiveButton("连接") { _, _ ->
                val ip = edit.text.toString().trim()
                if (ip.isNotEmpty()) connectTv(ip, TvControllerClient.PORT, ip, "tv")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 后台连入 TV/PC 并设为当前控制目标（不阻塞 UI）
     * @param type 设备类型 "tv" 或 "pc"：仅 TV 切到 TV 专属快捷键套，PC 保持鼠标 + 完整键鼠 */
    private fun connectTv(ip: String, port: Int, name: String, type: String = "tv") {
        Thread({
            val c = TvControllerClient(ip, port)
            val ok = c.connect()
            runOnUiThread {
                if (ok) {
                    ControlTarget.controlClient = c
                    ControlTarget.host = ip
                    ControlTarget.label = name
                    ControlTarget.type = type
                    c.onReverseClipboard = { text -> runOnUiThread { recvClipboard(text) } }
                    // 仅 TV 目标进 TV 专属快捷键布局；PC 退出 TV 模式（保持鼠标 + 完整键鼠）
                    if (type == "tv") hotkeyBoard.enterTvMode() else hotkeyBoard.exitTvMode()
                    updateDeviceChip()
                    Toast.makeText(this, "已连 $name", Toast.LENGTH_SHORT).show()
                } else {
                    ControlTarget.clear()
                    hotkeyBoard.exitTvMode()
                    updateDeviceChip()
                    Toast.makeText(this, "连不上 $ip", Toast.LENGTH_SHORT).show()
                }
            }
        }, "tv-connect").start()
    }

    /** 发文件到对端设备文件接收通道（独立端口 9512） */
    private fun sendFile(uri: Uri) {
        val host = ControlTarget.host
        if (host.isEmpty() || !ControlTarget.isControlling()) {
            Toast.makeText(this, "未选受控设备", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在发送文件…", Toast.LENGTH_SHORT).show()
        Thread({
            val ok = TvFileSender.send(this, host, uri) { p ->
                runOnUiThread { statusView?.text = "发送 $p%" }
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "文件已发送" else "发送失败", Toast.LENGTH_SHORT).show()
                statusView?.text = if (hidReady) "0 帧" else "HID 未就绪"
            }
        }, "tv-file").start()
    }

    /** 读本机剪贴板并发送到对端设备（写入对方系统剪贴板 + 当前聚焦输入框） */
    private fun sendClipboard() {
        val c = ControlTarget.controlClient
        if (c == null || !ControlTarget.isControlling()) {
            Toast.makeText(this, "未选受控设备", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = c.sendClipboard(text)
        Toast.makeText(this, if (ok) "已发送剪贴板" else "发送失败", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 启动参数：直接落到指定页签（0 触控板 / 1 键盘） */
        const val EXTRA_PAGE = "page"

        private const val PAGE_TOUCHPAD = 0
        private const val PAGE_KEYBOARD = 1
        private const val PAGE_DISPLAY = 2
    }
}
