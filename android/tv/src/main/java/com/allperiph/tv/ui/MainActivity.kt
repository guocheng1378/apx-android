package com.allperiph.tv.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.tv.core.TvInjector
import com.allperiph.tv.net.TcpControlServer
import com.allperiph.tv.net.TvFileReceiver
import com.allperiph.tv.net.WirelessBeacon
import com.allperiph.tv.TvServerService
import com.allperiph.tv.R

/**
 * APX TV 主界面：信息流 + 横向卡片舞台 + 网络光标。
 * 同时作为 [TvInputDispatcher.Listener] 接收手机发来的控制事件并落到界面；
 * 自身也处理原生 DPAD/触摸，使无手机时也能用遥控器操作。
 */
class MainActivity : Activity(), TvInputDispatcher.Listener {

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var ipText: TextView
    private lateinit var hint: TextView
    private lateinit var console: TextView
    private lateinit var cursor: View
    private lateinit var cards: List<TextView>

    private var cursorX = 0f
    private var cursorY = 0f
    private var selected = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        root = findViewById(R.id.tvRoot)
        status = findViewById(R.id.tvStatus)
        ipText = findViewById(R.id.tvIp)
        hint = findViewById(R.id.tvHint)
        console = findViewById(R.id.tvConsole)
        cursor = findViewById(R.id.tvCursor)
        cards = listOf(
            findViewById(R.id.tvCard0),
            findViewById(R.id.tvCard1),
            findViewById(R.id.tvCard2),
            findViewById(R.id.tvCard3),
            findViewById(R.id.tvCard4),
        )

        ipText.text = "本机 ${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}"

        // 卡片原生点击（无手机时也能用）
        cards.forEachIndexed { i, v ->
            v.setOnClickListener { selectCard(i); activateSelected() }
        }

        // 系统级输入注入 + 文件接收通道
        TvInjector.init(this)
        TvFileReceiver.start(applicationContext)
        findViewById<TextView>(R.id.tvEnableSystem).setOnClickListener { openSystemControlSettings() }

        // 启动前台服务（保活服务端 + 发现信标）；API 26+ 用 startForegroundService，老版本用 startService
        val svc = Intent(this, TvServerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)

        // 记住「用户开过一次」：开机后由 BootReceiver 自动拉起，不必每次翻出遥控器点 App
        getSharedPreferences(TvServerService.PREF, MODE_PRIVATE)
            .edit().putBoolean(TvServerService.KEY_ENABLED, true).apply()

        // 注意：发现信标**不在这里起** —— 它挂在 TvServerService 上。
        // 以前放 Activity 里，用户一按返回/切走信标就停发，手机与 PC 立刻发现不到本机（只能手填 IP）。

        askNotificationPermission()
        offerBatteryWhitelist()
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
     * 把服务杀掉 —— 现象就是「手机明明连过，过一会儿就发现不到 / 连不上本机」。
     * 用系统弹窗引导用户加白，比让他自己去设置里翻好找得多。
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

    /** 引导用户开启「无障碍」与「显示在其他应用上层」——系统级注入的前置权限 */
    private fun openSystemControlSettings() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
            }
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Toast.makeText(this, R.string.tv_enable_system_hint, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        TvInputDispatcher.listener = this
        // 等布局完成再居中光标
        root.post { centerCursor() }
        refreshStatus()
    }

    /**
     * 刷新状态行：链路状态 + 「系统注入是否真的可用」。
     *
     * 后者没开时，手机只能看到光标、点不动 —— 这是被控端最常见的困惑点，所以必须直接写在
     * 界面上；而且 Activity 重建后也要立刻显示（不能只依赖"连上的那一刻"那次回调）。
     */
    private fun refreshStatus() {
        status.text = TvServerService.current?.status() ?: getString(R.string.tv_status_waiting)
        hint.text = if (TvInjector.systemReady()) {
            "系统注入已就绪：手机可点击 / 输入 / 返回 / 主页"
        } else {
            "未开启「无障碍 + 悬浮窗」：手机只能看到光标、点不动 —— 先点「启用系统控制」授权"
        }
    }

    override fun onPause() {
        if (TvInputDispatcher.listener === this) TvInputDispatcher.listener = null
        super.onPause()
    }

    // ————————————————————————————— 原生遥控器/键盘 —————————————————————————————

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { selectCard(selected - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { selectCard(selected + 1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { selectCard(selected - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { selectCard(selected + 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { activateSelected(); return true }
            KeyEvent.KEYCODE_BACK -> { console.text = ""; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ————————————————————————————— TvInputDispatcher.Listener —————————————————————————————

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
        // 命中测试：光标下方的卡片优先；否则激活当前选中卡片
        val hit = cards.firstOrNull { cardRect(it).contains(cursorX.toInt(), cursorY.toInt()) }
        if (hit != null) {
            val idx = cards.indexOf(hit)
            selectCard(idx)
        }
        activateSelected()
    }

    override fun onKey(keyCode: Int, down: Boolean) {
        if (!down) return
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> selectCard(selected - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> selectCard(selected + 1)
            KeyEvent.KEYCODE_DPAD_UP -> selectCard(selected - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> selectCard(selected + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activateSelected()
            KeyEvent.KEYCODE_DEL -> console.text = console.text.dropLast(1)
            KeyEvent.KEYCODE_SPACE -> appendConsole(' ')
            else -> {
                // 媒体键等：仅提示（TV 端不注入系统媒体）
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

    private fun appendConsole(ch: Char) {
        val s = console.text.toString()
        console.text = if (s.length >= 64) s.drop(1) + ch else s + ch
    }

    private fun selectCard(i: Int) {
        selected = i.coerceIn(0, cards.lastIndex)
        cards.forEachIndexed { idx, v ->
            if (idx == selected) {
                v.setBackgroundColor(0xFF1F6FEB.toInt())
            } else {
                v.setBackgroundResource(R.drawable.card_bg)
            }
        }
        // DPAD / 网络方向键移动时，把光标同步到该卡片中心
        val r = cardRect(cards[selected])
        cursorX = r.centerX().toFloat()
        cursorY = r.centerY().toFloat()
        placeCursor()
        cards[selected].requestFocus()
    }

    private fun activateSelected() {
        val name = cards[selected].text.toString()
        when (selected) {
            2 -> try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
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

    private fun placeCursor() {
        cursor.visibility = View.VISIBLE
        val lp = cursor.layoutParams as ViewGroup.MarginLayoutParams
        lp.leftMargin = cursorX.toInt() - cursor.width / 2
        lp.topMargin = cursorY.toInt() - cursor.height / 2
        cursor.layoutParams = lp
    }

    private fun cardRect(v: View): Rect {
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
}
