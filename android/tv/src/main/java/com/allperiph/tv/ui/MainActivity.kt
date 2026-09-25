package com.allperiph.tv.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.tv.R
import com.allperiph.tv.TvServerService
import com.allperiph.tv.core.TvInjector
import com.allperiph.tv.net.TcpControlServer
import com.allperiph.tv.net.TvFileReceiver

/**
 * APX TV 主界面（v1.2：**尺寸自适应 + 遥控器焦点可见**，纯代码构建）。
 *
 * 为什么不再用 XML 布局：电视上写死的 dp/sp 在 720p 与 4K 之间差三倍、还会被过扫描裁边。
 * 现在所有尺寸都过 [TvUi]（按屏幕相对 1080p 缩放 + 至少 3.5% 安全边距 + 远距离字号放大），
 * 换任何分辨率的电视/盒子都不用改代码。
 *
 * 导航有两套，互不冲突：
 *  · **遥控器/键盘**：走系统焦点（所有可点项都是 focusable，用两态背景显示焦点在哪），
 *    方向键 `focusSearch` 移动、确认键 `performClick`；
 *  · **手机发来的网络光标**：命中测试直接点中光标下的控件（卡片或功能按钮都行）。
 * 焦点变化会把网络光标同步过去，所以两种操作看到的「当前位置」是同一个。
 */
class MainActivity : Activity(), TvInputDispatcher.Listener {

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var ipText: TextView
    private lateinit var hint: TextView
    private lateinit var console: TextView
    private lateinit var cursor: View

    /** 可点项（卡片 + 功能按钮）：光标命中测试与焦点同步都用它 */
    private val clickables = ArrayList<View>()
    private val cards = ArrayList<View>()
    private var selected = 0

    private var cursorX = 0f
    private var cursorY = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pad get() = TvUi.safeInset(this)
    private val gap get() = TvUi.dp(this, 10f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        setContentView(buildUi())

        ipText.text = "本机 ${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}"

        TvInjector.init(this)
        TvFileReceiver.start(applicationContext)

        // 前台服务（保活服务端 + 发现信标）；API 26+ 用 startForegroundService
        val svc = Intent(this, TvServerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        // 记住「用户开过一次」：开机后由 BootReceiver 自动拉起
        getSharedPreferences(TvServerService.PREF, MODE_PRIVATE)
            .edit().putBoolean(TvServerService.KEY_ENABLED, true).apply()

        askNotificationPermission()
        offerBatteryWhitelist()
    }

    override fun onResume() {
        super.onResume()
        TvInputDispatcher.listener = this
        root.post {
            centerCursor()
            // 进页面就给个焦点：遥控器一按方向键就有反应（不依赖触摸）
            clickables.firstOrNull()?.requestFocus()
            refreshStatus()
        }
    }

    override fun onPause() {
        if (TvInputDispatcher.listener === this) TvInputDispatcher.listener = null
        super.onPause()
    }

    // ————————————————————————————— 界面（全部自适应） —————————————————————————————

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(TvUi.BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        col.addView(TextView(this).apply {
            text = "APX TV"
            setTextColor(TvUi.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            TvUi.applyTextSize(this, 24f)
        })

        status = TextView(this).apply {
            setTextColor(TvUi.TEXT)
            TvUi.applyTextSize(this, 17f)
            setPadding(0, gap / 2, 0, 0)
        }
        col.addView(status)

        ipText = TextView(this).apply {
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 13f)
            setPadding(0, gap / 3, 0, 0)
        }
        col.addView(ipText)

        // 功能按钮排（遥控器可达、焦点可见；手机光标也能直接点）
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap, 0, 0)
        }
        actions.addView(actionButton("文件传输") {
            startActivity(Intent(this@MainActivity, TvFileActivity::class.java))
        })
        actions.addView(actionButton("副屏（看电脑画面）") {
            startActivity(Intent(this@MainActivity, TvScreenActivity::class.java))
        })
        actions.addView(actionButton("启用系统控制") { openSystemControlSettings() })
        col.addView(actions)

        col.addView(TextView(this).apply {
            text = "方向键移动焦点，确认键触发；被手机控制时可直接用光标点"
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 12f)
            setPadding(0, gap, 0, gap / 2)
        })

        // 卡片舞台：保留（焦点/光标演示 + 选中高亮）
        val stage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            getString(R.string.tv_card_launcher),
            getString(R.string.tv_card_player),
            getString(R.string.tv_card_settings),
            getString(R.string.tv_card_console),
            getString(R.string.tv_card_about),
        ).forEachIndexed { i, name -> stage.addView(card(name, i)) }
        val stageScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(stage)
        }
        col.addView(stageScroll, LinearLayout.LayoutParams(-1, -2))

        console = TextView(this).apply {
            setTextColor(TvUi.OK)
            TvUi.applyTextSize(this, 16f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, gap, 0, 0)
            text = ""
        }
        col.addView(console)

        col.addView(android.widget.Space(this), LinearLayout.LayoutParams(-1, 0, 1f))

        hint = TextView(this).apply {
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 13f)
        }
        col.addView(hint)

        // 网络光标：默认隐藏，收到鼠标/触摸时显示并跟随
        cursor = View(this).apply {
            val s = TvUi.dp(this@MainActivity, 26f)
            layoutParams = FrameLayout.LayoutParams(s, s)
            background = getDrawable(R.drawable.cursor_dot)
            visibility = View.INVISIBLE
        }
        root.addView(cursor)
        return root
    }

    /** 功能按钮：大点击区 + 聚焦高亮（远距离看得出来） */
    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TvUi.TEXT)
        TvUi.applyTextSize(this, 16f)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = TvUi.focusBg(TvUi.CARD, TvUi.CARD_FOCUS, TvUi.dp(this@MainActivity, 12f), TvUi.dp(this@MainActivity, 3f))
        setPadding(gap * 3, gap * 2, gap * 3, gap * 2)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap * 2 }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, has -> if (has) syncCursorTo(this) }
        clickables.add(this)
    }

    /** 卡片：尺寸随屏幕缩放，聚焦/选中高亮 */
    private fun card(name: String, index: Int): View = TextView(this).apply {
        text = name
        setTextColor(TvUi.TEXT)
        TvUi.applyTextSize(this, 18f)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = TvUi.focusBg(TvUi.CARD, TvUi.CARD_FOCUS, TvUi.dp(this@MainActivity, 14f), TvUi.dp(this@MainActivity, 3f))
        layoutParams = LinearLayout.LayoutParams(TvUi.dp(this@MainActivity, 210f), TvUi.dp(this@MainActivity, 130f))
            .apply { rightMargin = gap * 2 }
        setOnClickListener { selectCard(index); activateSelected() }
        setOnFocusChangeListener { _, has -> if (has) { selected = index; syncCursorTo(this) } }
        cards.add(this)
        clickables.add(this)
    }

    // ————————————————————————————— 系统控制授权 —————————————————————————————

    private fun openSystemControlSettings() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Toast.makeText(this, R.string.tv_enable_system_hint, Toast.LENGTH_LONG).show()
    }

    /** 通知权限（API 33+）：前台服务的常驻通知没它就看不见，进程也更容易被系统回收 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
    }

    /**
     * 电池优化白名单：被控端必须「一直在线」，否则国产 ROM（MIUI/HyperOS 等）会在后台几十秒内
     * 把服务杀掉 —— 现象是「手机明明连过，过一会儿就发现不到 / 连不上本机」。
     */
    private fun offerBatteryWhitelist() {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle("让被控服务长期在线")
                .setMessage(
                    "系统会在后台限制长时间不用的服务。加入电池优化白名单后，手机才能随时发现并控制" +
                        "本机；不需要常驻时可在系统设置里移除。"
                )
                .setPositiveButton("加入白名单") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    }
                }
                .setNegativeButton("以后再说", null)
                .show()
        }
    }

    // ————————————————————————————— TvInputDispatcher 监听 —————————————————————————————

    override fun onCursorMove(x: Float, y: Float, absolute: Boolean) {
        if (absolute) {
            cursorX = x * root.width
            cursorY = y * root.height
        } else {
            cursorX = (cursorX + x).coerceIn(0f, root.width.toFloat())
            cursorY = (cursorY + y).coerceIn(0f, root.height.toFloat())
        }
        placeCursor()
    }

    override fun onCursorClick() {
        // 命中测试：光标正下方的可点项优先（卡片 / 功能按钮都算）
        val hit = clickables.firstOrNull { rectOf(it).contains(cursorX.toInt(), cursorY.toInt()) }
        if (hit != null) {
            hit.requestFocus()
            hit.performClick()
        } else {
            (cards.getOrNull(selected))?.let { it.requestFocus(); it.performClick() }
        }
    }

    override fun onKey(keyCode: Int, down: Boolean) {
        if (!down) return
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 走系统焦点：卡片与功能按钮都能到达（不再写死只选卡片）
                val dir = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    else -> View.FOCUS_DOWN
                }
                val cur = currentFocus ?: clickables.firstOrNull()
                cur?.focusSearch(dir)?.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> currentFocus?.performClick()
            KeyEvent.KEYCODE_DEL -> console.text = console.text.dropLast(1)
            KeyEvent.KEYCODE_SPACE -> appendConsole(' ')
            else -> {
                val name = mediaName(keyCode)
                if (name != null) Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onText(ch: Char) = appendConsole(ch)

    override fun onPeer(connected: Boolean, peer: String) {
        status.text = if (connected) getString(R.string.tv_status_connected, peer)
        else getString(R.string.tv_status_waiting)
        TvInjector.setConnected(connected)
    }

    // ————————————————————————————— 内部 —————————————————————————————

    /**
     * 刷新状态行：链路 + 「系统注入是否真的可用」。
     * 后者没开时，手机只能看到光标、点不动 —— 被控端最常见的困惑点，必须直接写在界面上；
     * Activity 重建后也要立刻显示（不能只依赖"连上的那一刻"那次回调）。
     */
    private fun refreshStatus() {
        status.text = TvServerService.current?.status() ?: getString(R.string.tv_status_waiting)
        hint.text = if (TvInjector.systemReady()) {
            "系统注入已就绪：手机可点击 / 输入 / 返回 / 主页"
        } else {
            "未开启「无障碍 + 悬浮窗」：手机只能看到光标、点不动 —— 先点「启用系统控制」授权"
        }
    }

    private fun appendConsole(ch: Char) {
        val s = console.text.toString()
        console.text = if (s.length >= 64) s.drop(1) + ch else s + ch
    }

    private fun selectCard(i: Int) {
        selected = i.coerceIn(0, cards.lastIndex)
        syncCursorTo(cards[selected])
    }

    private fun activateSelected() {
        val name = (cards.getOrNull(selected) as? TextView)?.text?.toString() ?: return
        when (selected) {
            2 -> try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "已打开：$name", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(this, "已打开：$name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun centerCursor() {
        cursorX = root.width / 2f
        cursorY = root.height / 2f
        placeCursor()
    }

    /** 焦点落到哪，网络光标就跟到哪：遥控器与手机光标看到的是同一个「当前位置」 */
    private fun syncCursorTo(v: View) {
        val r = rectOf(v)
        cursorX = r.centerX().toFloat()
        cursorY = r.centerY().toFloat()
        placeCursor()
    }

    private fun placeCursor() {
        cursor.visibility = View.VISIBLE
        val lp = cursor.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = cursorX.toInt() - cursor.width / 2
        lp.topMargin = cursorY.toInt() - cursor.height / 2
        cursor.layoutParams = lp
    }

    private fun rectOf(v: View): Rect {
        val r = Rect()
        v.getDrawingRect(r)
        root.offsetDescendantRectToMyCoords(v, r)
        return r
    }

    private fun mediaName(kc: Int): String? = when (kc) {
        KeyEvent.KEYCODE_VOLUME_UP -> "音量 +"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "音量 -"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "静音"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放/暂停"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "下一首"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一首"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "播放"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "暂停"
        KeyEvent.KEYCODE_MEDIA_STOP -> "停止"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "快进"
        else -> null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
