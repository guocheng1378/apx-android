package com.allperiph.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.wireless.ControlTarget

/**
 * 「遥控器」—— 一块真正像遥控器的界面（真机反馈"找不到遥控器的界面"）。
 *
 * ## 为什么单独做一页
 * 遥控能力原先散在两处，都不像遥控器：
 *  · 键盘页的「遥控」布局 —— 只有媒体与音量图块，没有方向键；
 *  · 主页快捷键条的「TV 遥控」预设 —— 只有 8 个快捷键块，且要选中 TV 目标才会自动切换。
 * 于是用户的直观感受是"App 里没有遥控器"。这里给一块固定的方向盘。
 *
 * ## 全部复用现有发送通道（零重复实现）
 *  · 方向 / OK / 返回 / 主页 / 菜单 → [HidKeys.tap]（发 HID usage；**被控端**的 `HID_MAP`
 *    再翻成 DPAD / ENTER / BACK / HOME / MENU —— 与「TV 遥控」快捷键条走的是同一条路）；
 *  · 音量 / 静音 / 上一曲 / 播放 / 下一曲 → [HotkeyController]（多媒体位图：选中的目标走 0x02
 *    控制帧，否则走 USB HID / 蓝牙）；
 *  · 关机 / 重启 → [com.allperiph.wireless.TvControllerClient.power]（0x22；需要被控端有 root，
 *    没有 root 时被控端会如实退回软电源键，不会假装关掉）。
 */
class RemoteActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var targetView: TextView? = null

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(ThemePref.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // —— 顶部：标题 + 选择设备 / 关闭 ——
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "遥控器"
            setTextColor(getColor(R.color.md_on_surface))
            textSize = 20f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(chip("选择设备") { pickTarget() })
        head.addView(chip("关闭") { finish() })
        col.addView(head)

        targetView = TextView(this).apply {
            setTextColor(getColor(R.color.md_on_surface_variant))
            textSize = 12f
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        col.addView(targetView)

        // —— 方向盘（3×3，空位用 View 占位）——
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        padRow(pad, null, key("▲") { HidKeys.tap(U_UP) }, null)
        padRow(pad, key("◀") { HidKeys.tap(U_LEFT) }, key("OK", bold = true) { HidKeys.tap(U_ENTER) },
            key("▶") { HidKeys.tap(U_RIGHT) })
        padRow(pad, null, key("▼") { HidKeys.tap(U_DOWN) }, null)
        col.addView(pad, LinearLayout.LayoutParams(-1, -2))

        // —— 返回 / 主页 / 菜单 ——
        col.addView(keyRow(
            "返回" to { HidKeys.tap(U_ESC) },
            "主页" to { HidKeys.tap(U_HOME) },
            "菜单" to { HidKeys.tap(U_MENU) },
        ))

        // —— 音量 / 静音 ——
        col.addView(keyRow(
            "音量 −" to { media(HotkeyController.BIT_VOLUME_DOWN) },
            "静音" to { media(HotkeyController.BIT_MUTE) },
            "音量 +" to { media(HotkeyController.BIT_VOLUME_UP) },
        ))

        // —— 媒体 ——
        col.addView(keyRow(
            "上一曲" to { media(HotkeyController.BIT_PREV_TRACK) },
            "播放 / 暂停" to { media(HotkeyController.BIT_PLAY_PAUSE) },
            "下一曲" to { media(HotkeyController.BIT_NEXT_TRACK) },
        ))

        // —— 电源：软电源（待机/唤醒）与真关机/重启分开，后者需要 root 且要确认 ——
        col.addView(keyRow(
            "电源" to { HidKeys.tap(U_POWER) },
            "关机" to { confirmPower(0, "关机") },
            "重启" to { confirmPower(1, "重启") },
        ))

        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(TextView(this).apply {
            text = "方向 / OK / 返回 / 主页 / 菜单走 HID 键码（被控端翻成遥控键）；音量与媒体走多媒体位图；" +
                    "关机 / 重启需要被控端有 root（没有 root 只会退回待机）。"
            setTextColor(getColor(R.color.md_on_surface_variant))
            textSize = 11f
            setPadding(dp(2), dp(4), dp(2), dp(2))
        })

        setContentView(root)
        Backdrop.apply(root, getColor(R.color.md_background))
        refreshTarget()
    }

    override fun onResume() {
        super.onResume()
        refreshTarget()
    }

    // ————————————————————————————— 内部 —————————————————————————————

    /** 目标设备状态：没有目标时按键会退化成本机键盘，必须说清楚 */
    private fun refreshTarget() {
        val t = targetView ?: return
        t.text = if (ControlTarget.isControlling()) {
            "正在控制：${ControlTarget.label}（${ControlTarget.type.uppercase()}）"
        } else {
            "未连接受控设备 —— 按键会走 USB HID（等于本机键盘）；点「选择设备」挑 TV / PC"
        }
    }

    /** 选设备：交给主页的选择器（发现 / 连接逻辑只保留一处实现） */
    private fun pickTarget() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    /** 多媒体键：按下 + 60ms 释放（被控端按位图边沿识别，见 HotkeyController） */
    private fun media(bit: Int) {
        HotkeyController.press(bit)
        handler.postDelayed({ HotkeyController.release(bit) }, 60)
    }

    /** 关机 / 重启：会直接关掉被控设备，必须确认（与键盘页同一套语义） */
    private fun confirmPower(action: Int, label: String) {
        val c = ControlTarget.controlClient
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

    /** 方向盘的一行：(0,-2,1f) 之外用等分高度，空位放一个不可见的占位 View */
    private fun padRow(parent: LinearLayout, vararg cells: View?) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cells.forEach { v ->
            val lp = LinearLayout.LayoutParams(0, dp(60), 1f)
                .apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
            r.addView(v ?: View(this), lp)
        }
        parent.addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun keyRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        items.forEach { (label, action) ->
            r.addView(
                key(label) { action() },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(4), 0, dp(4), 0) },
            )
        }
        return r
    }

    private fun key(label: String, bold: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = if (label.length > 2) 14f else 18f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.md_on_surface))
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(getColor(R.color.md_surface))
                setStroke(dp(1), getColor(R.color.card_stroke))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Feedback.tap(this)
                onClick()
            }
        }

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.md_primary))
        textSize = 12f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(getColor(R.color.md_surface))
            setStroke(dp(1), getColor(R.color.card_stroke))
        }
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            Feedback.tap(this)
            onClick()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        // HID usage（page 0x07）—— 与「TV 遥控」快捷键套完全一致，被控端 HID_MAP 负责翻译
        const val U_UP = 0x52
        const val U_DOWN = 0x51
        const val U_LEFT = 0x50
        const val U_RIGHT = 0x4F
        const val U_ENTER = 0x28
        const val U_ESC = 0x29      // 遥控器「返回」
        const val U_HOME = 0x4A     // 遥控器「主页」
        const val U_MENU = 0x65     // 遥控器「菜单」（Application）
        const val U_POWER = 0x66    // 键盘 Power（软电源：锁屏 / 亮屏）
    }
}
