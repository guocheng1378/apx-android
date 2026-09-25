package com.allperiph.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.allperiph.wireless.TvControllerClient

/**
 * 手机端「TV 控制」入口（独立 launcher，不改动现有主控界面）：
 * 填 TV 的 IP（默认端口 9511）连入，用触屏板/按钮发送 APX1 控制帧遥控 TV。
 * 触屏板发 0x04 触摸（绝对坐标）；方向/确认发 0x03 键盘；音量等发 0x02 多媒体位图。
 */
class TvControllerActivity : Activity() {

    private var client: TvControllerClient? = null

    // HID usage(page 0x07) 映射（与 TV 端 HID_MAP 对应）
    private val KEY_LEFT = 0x50
    private val KEY_RIGHT = 0x4F
    private val KEY_UP = 0x52
    private val KEY_DOWN = 0x51
    private val KEY_ENTER = 0x28
    private val KEY_BACKSPACE = 0x2A

    // 多媒体位图 bit（与 HotkeyController / TV 端 CONSUMER_MAP 同一套位布局）
    private val MED_VOL_UP = 1 shl 0
    private val MED_VOL_DOWN = 1 shl 1
    private val MED_MUTE = 1 shl 2
    private val MED_PLAY_PAUSE = 1 shl 4
    private val MED_PREV = 1 shl 5
    private val MED_NEXT = 1 shl 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        client?.disconnect()
        client = null
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val ipEdit = EditText(this).apply {
            hint = "TV IP（如 192.168.1.20）"
            setText("192.168.1.")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(ipEdit)

        val status = TextView(this).apply {
            text = "未连接"
            setPadding(0, 8, 0, 8)
        }
        root.addView(status)

        val connectBtn = Button(this).apply {
            text = "连接 TV"
            setOnClickListener {
                val ip = ipEdit.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this@TvControllerActivity, "先填 TV IP", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                client?.disconnect()
                val c = TvControllerClient(ip)
                val ok = c.connect()
                client = c
                status.text = if (ok) "已连 $ip:${TvControllerClient.PORT}" else "连接失败（TV 是否在线/同网段？）"
            }
        }
        root.addView(connectBtn)

        // 触屏板
        val pad = View(this).apply {
            setBackgroundColor(0xFF161B22.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                320,
            ).apply { topMargin = 16 }
            setOnTouchListener { _, ev ->
                val w = width.toFloat().coerceAtLeast(1f)
                val h = height.toFloat().coerceAtLeast(1f)
                val nx = (ev.x / w * 65535).toInt().coerceIn(0, 65535)
                val ny = (ev.y / h * 65535).toInt().coerceIn(0, 65535)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> client?.touch(0, 0, nx, ny)
                    MotionEvent.ACTION_MOVE -> client?.touch(2, 0, nx, ny)
                    MotionEvent.ACTION_UP -> client?.touch(1, 0, nx, ny)
                }
                true
            }
        }
        val padLabel = TextView(this).apply { text = "触屏板（按下/移动/抬起 = 点击/移动）" }
        root.addView(padLabel)
        root.addView(pad)

        // 方向 + 确认 + 退格
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(btn("←") { key(KEY_LEFT) })
        nav.addView(btn("→") { key(KEY_RIGHT) })
        nav.addView(btn("↑") { key(KEY_UP) })
        nav.addView(btn("↓") { key(KEY_DOWN) })
        nav.addView(btn("确认") { key(KEY_ENTER) })
        nav.addView(btn("退格") { key(KEY_BACKSPACE) })
        root.addView(nav)

        // 多媒体
        val media = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        media.addView(btn("音量+") { client?.consumer(MED_VOL_UP) })
        media.addView(btn("音量-") { client?.consumer(MED_VOL_DOWN) })
        media.addView(btn("静音") { client?.consumer(MED_MUTE) })
        media.addView(btn("播放/暂停") { client?.consumer(MED_PLAY_PAUSE) })
        media.addView(btn("上一首") { client?.consumer(MED_PREV) })
        media.addView(btn("下一首") { client?.consumer(MED_NEXT) })
        root.addView(media)

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun key(usage: Int) {
        client?.keyboard(usage, true)
        client?.keyboard(usage, false)
    }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = 6; rightMargin = 6; topMargin = 8 }
        }
}
