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
import com.allperiph.tv.core.UinputGamepad
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
    private lateinit var inject: TextView
    private lateinit var ipText: TextView
    private lateinit var hint: TextView
    private lateinit var console: TextView
    private lateinit var cursor: View
    /** 「开启 / 停止被控」按钮：文字随状态变（原先 TV 端**没有任何关闭入口**） */
    private lateinit var toggleService: TextView

    /** 可点项（卡片 + 功能按钮）：光标命中测试与焦点同步都用它 */
    private val clickables = ArrayList<View>()
    private val cards = ArrayList<View>()
    private var selected = 0

    private var cursorX = 0f
    private var cursorY = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 注入通道状态**轮询**。
     *
     * 三条通道全是异步就绪的：evdev 要读 `/dev/input` 与键位表（还要跨 4 个候选节点试），
     * root 要起 `su` 并探测（最坏 3 秒），uinput 要建虚拟手柄 —— 慢盒子上 5 秒以上很正常。
     * 原先只在 0 / 1.5s / 4s 刷三次，于是状态行会长期停在「未开启」，把用户误导成"没开成功"。
     * 这里一直轮询到**全键与手柄都就绪**为止（上限约 18 秒）。
     */
    private val channelPoll = object : Runnable {
        private var n = 0
        override fun run() {
            refreshStatus()
            n++
            if (n < 15 && !(TvInjector.fullKeyReady() && UinputGamepad.ready)) {
                mainHandler.postDelayed(this, 1_200)
            }
        }
    }

    private val pad get() = TvUi.safeInset(this)
    private val gap get() = TvUi.dp(this, 10f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        setContentView(buildUi())

        ipText.text = "本机 ${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}"

        // 这两项现在**由 TvServerService 负责**（见其 onCreate / ensureFileReceiver），
        // 因为开机自启与"被杀后自启"都没有 Activity。这里再调一次只为「进 App 立刻生效」，
        // 两者都是幂等的 —— 但绝不能再假设"Activity 跑过就等于注入通道已初始化"。
        TvInjector.init(this)
        TvFileReceiver.start(applicationContext)

        // 前台服务（保活服务端 + 发现信标）；API 26+ 用 startForegroundService。
        // ★ 走 startIfNeeded：**用户手动停过就不再自动开**，否则"停止被控"按钮等于摆设
        //   （下次打开 App 又被打开）。
        TvServerService.startIfNeeded(this)

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
            // 注入通道是**异步**就绪的 → 轮询刷新到就绪为止（见 channelPoll）。
            // 原先只刷 0/1.5s/4s 三次，慢盒子上会长期停在"未开启"误导用户。
            mainHandler.removeCallbacks(channelPoll)
            channelPoll.run()
        }
    }

    override fun onPause() {
        if (TvInputDispatcher.listener === this) TvInputDispatcher.listener = null
        mainHandler.removeCallbacks(channelPoll)
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

        // 「注入通道」：本机现在到底有多少控制能力。
        // 电视上没有状态栏、用户也看不到日志，这一行就是唯一的答案 ——
        // "连上了点不动 / 方向键只动光标"十有八九是它显示的那一档造成的。
        inject = TextView(this).apply {
            TvUi.applyTextSize(this, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, gap / 2, 0, 0)
        }
        col.addView(inject)

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
        actions.addView(actionButton("控制能力（自检）") { showCapabilities() })
        // ★ 「开启 / 停止被控」：TV 端原先**只能开不能关**（手机端有「无线」总开关），
        //   服务 / 信标 / 开机自启 / 保活闹钟会永久常开，用户只能去系统设置强行停止。
        toggleService = actionButton("停止被控") { confirmToggleService() }
        actions.addView(toggleService)
        col.addView(actions)

        col.addView(TextView(this).apply {
            text = "方向键移动焦点，确认键触发；被手机控制时可直接用光标点。" +
                    "本端只接受控制（被控），不主动控制别人。"
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 12f)
            setPadding(0, gap, 0, gap / 2)
        })

        // 卡片舞台：保留（焦点/光标演示 + 选中高亮）。
        // ★ 明说这是演示区：原先 5 张卡片里 4 张点下去只弹「已打开：xxx」，纯属假入口，
        //   用户会以为能进"启动器/媒体播放/终端"。
        col.addView(TextView(this).apply {
            text = "以下为演示区：验证方向键焦点与手机光标命中，不是功能入口"
            setTextColor(TvUi.TEXT_DIM)
            TvUi.applyTextSize(this, 11f)
            setPadding(0, 0, 0, gap / 3)
        })
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

    /**
     * 手柄事件回显。
     *
     * 手柄原先**没有任何可见反馈**（[TvInputDispatcher.Listener.onGamepad] 是空默认实现，
     * 而这个 Activity 也没覆写它）—— 用户按了按钮完全不知道有没有送到电视。
     * 这里把按钮位图与摇杆值落到回显行上，一眼就能确认链路通不通。
     */
    override fun onGamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {
        val pressed = (0..15).filter { (buttons ushr it) and 1 == 1 }
        console.text = "手柄：按钮=$pressed · 左摇杆=($x,$y) · 右摇杆=($rx,$ry)"
    }

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

        val full = TvInjector.fullKeyReady()       // evdev / root：任意按键
        val sys = TvInjector.systemReady()          // 仅无障碍：能点能打字，但按键是空的
        inject.text = "注入通道：" + TvInjector.channelText()
        inject.setTextColor(
            when {
                full -> TvUi.OK                      // 全键可用
                sys -> TvUi.ACCENT                   // 半吊子：能力有限，别让用户以为是全的
                else -> 0xFFE5A50A.toInt()           // 仅可视化：明显的告警黄
            }
        )
        hint.text = when {
            full -> "完整键鼠已就绪：手机的方向键 = 电视焦点移动，按键 / 手柄 / 打字都能用"
            sys -> "只有无障碍：手机能点击 / 滑动 / 打字，但按键（方向键等）不会生效"
            else -> "未开启任何注入通道：手机只能看到光标、点不动 —— 点「控制能力（自检）」逐项开启"
        }
    }

    /** 悬浮窗授权页（部分电视没有这一页，失败就提示） */
    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }.onFailure {
            Toast.makeText(this, "本机没有「显示在其他应用上层」设置页", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 控制能力自检：逐项 ✓ / ✗，并给出"没就绪该怎么办"。
     * 电视上用户看不见日志、通知也常被挡，"缺哪一项"只能靠这一屏讲清楚。
     */
    private fun showCapabilities() {
        val lines = TvInjector.capabilities().joinToString("\n") { (name, ok, how) ->
            if (ok) "✓  $name" else "✗  $name\n      → $how"
        }
        // 三个动作按钮正好用完 AlertDialog 的 正 / 中 / 负 位；关闭用遥控器返回键。
        android.app.AlertDialog.Builder(this)
            .setTitle("控制能力自检（按返回键关闭）")
            .setMessage(lines)
            .setPositiveButton("无障碍设置") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            // ★ 输入法原先**一点引导都没有** —— 而中文打字最稳的通道就是它，
            //   用户只能自己翻系统设置，极难发现。
            .setNeutralButton("输入法设置") { _, _ -> openImeSettings() }
            .setNegativeButton("悬浮窗") { _, _ -> openOverlaySettings() }
            .show()
    }

    /** 输入法设置页（各 ROM 的 action 不完全一致，逐个兜底） */
    private fun openImeSettings() {
        val actions = listOf(
            Settings.ACTION_INPUT_METHOD_SETTINGS,
            "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS",
        )
        for (a in actions) {
            if (runCatching { startActivity(Intent(a)) }.isSuccess) return
        }
        Toast.makeText(this, "本机没有输入法设置页，请到系统设置里找「输入法 / 键盘」", Toast.LENGTH_LONG).show()
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
            // 「设置」是唯一真有功能的卡片
            2 -> runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "打不开系统设置", Toast.LENGTH_SHORT).show() }
            // ★ 其余四张是**演示卡片**：原先弹「已打开：xxx」是假承诺（其实什么都不会发生），
            //   用户会以为能进"启动器 / 媒体播放 / 终端"。
            else -> Toast.makeText(
                this,
                "「$name」是演示卡片：只用于验证方向键焦点与手机光标命中，没有实际功能",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** 开启 / 停止被控（TV 端原先**完全没有关闭入口**） */
    private fun confirmToggleService() {
        val enabled = TvServerService.isEnabled(this) && !TvServerService.isUserStopped(this)
        if (enabled) {
            android.app.AlertDialog.Builder(this)
                .setTitle("停止被控？")
                .setMessage("将关闭 9511 控制面 / 发现信标 / 9512 文件接收与保活，手机将无法再控制本机；" +
                        "开机也不会自动拉起。下次打开本 App 可重新开启。")
                .setPositiveButton("停止") { _, _ ->
                    TvServerService.stopAll(this)
                    Toast.makeText(this, "已停止被控", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({ refreshStatus() }, 500)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            TvServerService.start(this)
            Toast.makeText(this, "已开启被控", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ refreshStatus() }, 800)
            mainHandler.postDelayed({ channelPoll.run() }, 1_200)
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
