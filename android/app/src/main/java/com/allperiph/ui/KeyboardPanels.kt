package com.allperiph.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.allperiph.R
import com.allperiph.hid.HidKeys

/**
 * 键盘面板（v1.16 从操控面抽出，供主页「键盘」页复用）。
 *
 * 7 套布局，顶部胶囊切换（一屏放不下，可横向滑动）：
 *   · 快捷键盘：sticky 修饰排（Ctrl/Alt/Shift/Win）+ 5 行 QWERTY
 *   · 遥控键盘：媒体与音量图块（音量键按住连发）
 *   · 游戏键盘：4×6 高频键位（技能数字 / 左手技能区 / 方向键）
 *
 * 颜色全部走 `@color/md_*` 令牌（读资源），因此亮/深色主题自动跟随。
 * 修饰键状态全局共享一份 sticky，快捷与游戏两套键盘上的同一位会一起点亮。
 */
class KeyboardPanels(private val act: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    /**
     * 键盘布局（v1.32）：前 3 套是原有键位，后 4 套是专用布局
     * （数字小键盘 / 九宫格 / 方向键 / F 区，数据见 [KeyLayouts]）。
     */
    private val names = listOf("快捷", "遥控", "游戏", "数字", "九宫格", "方向", "F 区", "自定义")
    private val modBtns = LinkedHashMap<Int, MutableList<TextView>>()

    private lateinit var pager: ViewFlipper
    private var tabs: List<TextView> = emptyList()
    private var volRepeat: Runnable? = null

    // 令牌（随主题变化，故每次读取而非缓存常量）
    private val cCard get() = act.getColor(R.color.md_surface)
    private val cText get() = act.getColor(R.color.md_on_surface)
    private val cText2 get() = act.getColor(R.color.md_on_surface_variant)
    private val cStroke get() = act.getColor(R.color.card_stroke)

    /** 键盘配色：设置页可单独选一套皮肤；不选时等价于原来的 md_primary / md_primary_container */
    private val skin get() = ThemeSkin.current(act, ThemeSkin.KEYBOARD)
    private val cAccent get() = skin.accent
    private val cAccentSoft get() = skin.accentSoft

    /** 键帽密度：圆角 / 间距 / 字号（设置页可换） */
    private val dens get() = KeyPref.density(act)

    /** 修饰键键帽描边：主色 35% 透明（浅蓝底在白页面上需要有轮廓） */
    private val cAccentSoftStroke get() = (cAccent and 0x00FFFFFF) or (0x59 shl 24)

    fun build(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val tabRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(10))
        }
        tabs = names.mapIndexed { i, name ->
            val t = TextView(act).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true
                isFocusable = true
                // 切键位也要有手感：震动 + 触摸音 + 从中心扩散的玻璃波前
                setOnClickListener {
                    Feedback.tap(it)
                    rippleOn(it, it.width / 2f, it.height / 2f)
                    show(i)
                }
                if (name == "自定义") {
                    setOnLongClickListener {
                        editCustomLayout()
                        true
                    }
                }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(dp(76), -2).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
            t
        }
        // 7 套布局一屏放不下 → 页签行改成可横向滑动
        col.addView(
            android.widget.HorizontalScrollView(act).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabRow)
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        pager = ViewFlipper(act).apply {
            addView(quickKeyboard())
            addView(remoteKeyboard())
            addView(gameKeyboard())
            addView(numpadKeyboard())
            addView(grid9Keyboard())
            addView(arrowKeyboard())
            addView(fnKeyboard())
            addView(customKeyboard())
        }
        col.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        show(0)
        return col
    }

    fun show(i: Int) {
        if (!::pager.isInitialized || i !in names.indices) return
        pager.displayedChild = i
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.background = pill(if (on) cAccent else cAccentSoft)
            t.setTextColor(if (on) 0xFFFFFFFF.toInt() else cAccent)
            t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // —————————————————————————— 键位与布局 ——————————————————————————

    /** 扩展布局（v1.32）：按 [KeyLayouts] 的行列渲染，行高等分、列宽按权重 */
    private fun keyGrid(rows: List<List<KeyLayouts.Key>>): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { row ->
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { k ->
                val g = dp(dens.gapDp)
                r.addView(keyCap(k.label, k.usage), LinearLayout.LayoutParams(0, -1, k.weight).apply {
                    setMargins(g, g, g, g)
                })
            }
            col.addView(r, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return col
    }

    private fun numpadKeyboard() = keyGrid(KeyLayouts.NUMPAD)

    private fun grid9Keyboard() = keyGrid(KeyLayouts.GRID9)

    private fun arrowKeyboard() = keyGrid(KeyLayouts.ARROWS)

    private fun fnKeyboard() = keyGrid(KeyLayouts.FN)

    /**
     * 自定义布局（v1.33）：用户在编辑器里排好的整套行表。
     * - 未配置：占位提示"长按「自定义」页签编辑"
     * - 已配置：按行渲染，键面文字 → usage 由 [HidKeys.usageByLabel] 反查
     */
    private fun customKeyboard(): View {
        val rows = KeyPref.customRows(act)
        if (rows.isNullOrEmpty()) {
            return TextView(act).apply {
                text = "长按「自定义」页签编辑布局"
                gravity = Gravity.CENTER
                setTextColor(cText2)
                textSize = 14f
            }
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val g = dp(dens.gapDp)
        rows.forEach { labels ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
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

    /** 调起自定义布局编辑器，保存后回到自定义页签 */
    private fun editCustomLayout() {
        CustomKeyboardDialog.show(act) {
            val i = names.indexOf("自定义")
            if (i in 0 until names.size) show(i)
        }
    }

    private fun quickKeyboard(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val modRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
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

        // v1.18 重排：按真实键盘的交错排布，逐行加半键宽缩进（梯形），
        // 盲打时手指不用横向找键位；权重之和仍为 12，比例不失真。
        // v1.33：字母排列表换成 KeyPref.letterRows —— QWERTY / AZERTY / Dvorak 可切换。
        val rows = KeyPref.letterRows(act)
        rows.forEachIndexed { rowIndex, labels ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val inset = when (rowIndex) {
                1 -> 0.5f   // 第一行字母（缩进半键）
                2 -> 1.0f   // 第二行字母
                3 -> 1.5f   // 第三行字母
                else -> 0f  // 数字行 / 功能行
            }
            if (inset > 0f) {
                row.addView(View(act), LinearLayout.LayoutParams(0, -1, inset))
            }
            // 功能行按真实键盘配重：Esc 1 / Tab 1 / Space 4 / Enter 2 / ⌫ 2 / Del 2
            val isFunctionRow = rowIndex == rows.lastIndex
            val fnWeights = listOf(1f, 1f, 4f, 2f, 2f, 2f)
            val g = dp(dens.gapDp)
            labels.forEachIndexed { k, text ->
                val b = keyCap(text, false) {
                    // letterRows 含符号行，按 label 反查更全（usageOf 不识别 Space/⌫ 等）
                    HidKeys.tap(HidKeys.usageByLabel(text))
                    refreshMods() // tap 会清 sticky，同步熄灭修饰键
                }
                val w = if (isFunctionRow) fnWeights.getOrElse(k) { 1f } else 1f
                row.addView(b, LinearLayout.LayoutParams(0, -1, w).apply {
                    setMargins(g, g, g, g)
                })
            }
            if (inset > 0f) {
                row.addView(View(act), LinearLayout.LayoutParams(0, -1, inset))
            }
            col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return col
    }

    private fun remoteKeyboard(): View {
        val col = LinearLayout(act).apply {
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
                // ★ 电源：原先遥控键盘上**没有这颗键**（真机反馈"关机键你没加"）。
                //   走 Consumer bit3；对端 CONSUMER_MAP 已映射成 KEYCODE_POWER。
                Triple("电源", R.drawable.ic_apx_power, HotkeyController.BIT_POWER),
            ),
        )
        rows.forEach { defs ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            defs.forEach { (text, icon, bit) ->
                row.addView(mediaTile(text, icon, bit), LinearLayout.LayoutParams(0, dp(110), 1f).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        // 第三行：电源（软，待机/唤醒） / 关机 / 重启。
        // 后两个是**真电源动作**（控制帧 opcode 0x22），要**被控端有 root** 才能真正关/重启；
        // 没有 root 时被控端会退回软电源键并如实说明，不会假装关掉 —— 所以这里也弹确认框。
        val pr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        pr.addView(
            mediaTile("电源", R.drawable.ic_apx_power, HotkeyController.BIT_POWER),
            LinearLayout.LayoutParams(0, dp(110), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) },
        )
        pr.addView(
            actionTile("关机", 0xFFD23F31.toInt()) { confirmPower(0, "关机") },
            LinearLayout.LayoutParams(0, dp(110), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) },
        )
        pr.addView(
            actionTile("重启", 0xFFF0A020.toInt()) { confirmPower(1, "重启") },
            LinearLayout.LayoutParams(0, dp(110), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) },
        )
        col.addView(pr, LinearLayout.LayoutParams(-1, -2))
        return col
    }

    /** 动作图块：没有 Consumer 位，点了直接执行（关机 / 重启） */
    private fun actionTile(text: String, accent: Int, onClick: () -> Unit): View = TextView(act).apply {
        this.text = text
        setTextColor(accent)
        typeface = Typeface.DEFAULT_BOLD
        textSize = 16f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = card(cCard, dp(18))
        setOnClickListener { Feedback.tap(this); onClick() }
    }

    /** 关机 / 重启确认：会直接关掉被控设备，别让误触生效 */
    private fun confirmPower(action: Int, label: String) {
        val c = com.allperiph.wireless.ControlTarget.controlClient
        if (c == null) {
            android.widget.Toast.makeText(act, "尚未连接受控设备", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("$label 被控设备？")
            .setMessage("将直接$label 对端（需要被控端有 root；没有 root 只会退回待机）。")
            .setPositiveButton(label) { _, _ ->
                c.power(action)
                android.widget.Toast.makeText(act, "已发送$label 指令", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun mediaTile(text: String, icon: Int, bit: Int): View {
        val tile = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = card(cCard, dp(18))
            isClickable = true
            isFocusable = true
        }
        tile.addView(ImageView(act).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(cAccent)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        tile.addView(TextView(act).apply {
            this.text = text; setTextColor(cText2); textSize = 12f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        if (bit == HotkeyController.BIT_VOLUME_UP || bit == HotkeyController.BIT_VOLUME_DOWN) {
            // 音量键：按住连发（PC 按位图边沿计数，须反复 按下→释放）
            tile.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = card(cAccentSoft, dp(18))
                        Feedback.tap(v)
                        rippleOn(v, ev.x, ev.y)
                        mediaTap(bit)
                        val task = object : Runnable {
                            override fun run() {
                                Feedback.haptic(v) // 连发只震动，避免声音刷屏
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
                Feedback.tap(it)
                rippleOn(it, tile.width / 2f, tile.height / 2f)
                mediaTap(bit)
            }
        }
        return tile
    }

    private fun mediaTap(bit: Int) {
        HotkeyController.press(bit)
        handler.postDelayed({ HotkeyController.release(bit) }, 60)
    }

    private fun gameKeyboard(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val layout = listOf(
            listOf("Esc", "1", "2", "3", "4", "Tab"),
            listOf("Q", "W", "E", "R", "F", "B"),
            listOf("A", "S", "D", "Shift", "Space", "Ctrl"),
            listOf("↑", "↓", "←", "→", "Enter", "Del"),
        )
        layout.forEach { labels ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
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

    /** MIUIX 键帽：白底细描边 + 9dp 圆角；修饰键浅蓝底蓝字，锁定后实蓝底白字 */
    private fun keyCap(text: String, modifier: Boolean, onTap: () -> Unit): TextView = TextView(act).apply {
        this.text = text
        textSize = dens.textSize
        gravity = Gravity.CENTER
        setTextColor(if (modifier) cAccent else cText)
        // 修饰键给一层淡蓝描边，否则浅蓝底在白页面上"看不见键帽"
        val corner = dp(dens.cornerDp)
        val restBg = {
            if (modifier) strokeCard(cAccentSoft, corner, cAccentSoftStroke)
            else strokeCard(cCard, corner, cStroke)
        }
        background = restBg()
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        // 液态按压：按下缩到 0.9 + 染蓝 + **震动 / 声音 / 玻璃扩散**，松手弹回原色。
        // 扩散画在 foreground 上，与底色（background）互不覆盖；节奏比原来慢一档，看得清。
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.90f).scaleY(0.90f)
                        .setDuration(ThemeSkin.motionMs(act, 120L)).start()
                    v.background = card(cAccentSoft, corner)
                    Feedback.tap(v)
                    rippleOn(v, e.x, e.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(ThemeSkin.motionMs(act, 360L))
                        .setInterpolator(OvershootInterpolator(2.2f)).start()
                    v.background = restBg()
                }
            }
            false // 交回给 click，发送逻辑不变
        }
    }

    /**
     * 玻璃扩散：从落点向外铺开一圈波前（[GlassRipple]）。
     * 画在 `foreground` 上 —— 键帽底色 / 高亮走 `background`，两者互不干扰。
     */
    private fun rippleOn(v: View, x: Float, y: Float) {
        if (v.width <= 0 || v.height <= 0) return
        val dur = ThemeSkin.motionMs(act, 620L)
        if (dur <= 0L) return // 动效设为"关闭"时只留震动 / 音效
        val ripple = GlassRipple(
            x, y,
            act.getColor(R.color.nav_pill_shine),
            cAccent,
            maxOf(v.width, v.height) * 1.05f,
            dur,
        ).apply {
            onFinish = { if (v.foreground === this) v.foreground = null }
        }
        v.foreground = ripple
        ripple.play()
    }

    /**
     * 显式 usage 的键帽（扩展布局用）。
     * 小键盘的 ⏎ / Num 等键面文字与 usage 不是一一对应，交给标签反查会落到 0。
     */
    private fun keyCap(text: String, usage: Int): TextView = keyCap(text, false) { HidKeys.tap(usage) }

    private fun refreshMods() {
        modBtns.forEach { (bit, list) ->
            val on = HidKeys.isSticky(bit)
            list.forEach { b ->
                b.background = card(if (on) cAccent else cAccentSoft, dp(9))
                b.setTextColor(if (on) 0xFFFFFFFF.toInt() else cAccent)
            }
        }
    }

    // —————————————————————————— 工具 ——————————————————————————

    private fun card(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun strokeCard(color: Int, radius: Int, stroke: Int): GradientDrawable =
        card(color, radius).apply { setStroke(1, stroke) }

    private fun pill(color: Int): GradientDrawable = card(color, dp(999))

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()
}


