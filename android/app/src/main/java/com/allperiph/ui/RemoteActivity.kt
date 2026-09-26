package com.allperiph.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
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
 * 「遥控器」—— 仿小米遥控器的**拨盘式**布局（真机反馈"找不到遥控器界面"，之后又要求更像遥控器）。
 *
 * ## 版式
 * ```
 *            遥控器          [选择设备] [关闭]
 *   正在控制：q201（TV）
 *        ╭───────────────╮
 *        │       ▲       │          ← 大正圆方向环（拨盘）
 *        │   ◀  (OK)  ▶  │            中心是主色圆钮 OK
 *        │       ▼       │
 *        ╰───────────────╯
 *          ⭕   ⭕   ⭕              ← 一行三个圆钮
 *          ⭕   ⭕   ⭕                 功能 / 音量 / 媒体 / 电源 共四行
 *          ⭕   ⭕   ⭕
 *          ⭕   ⭕   ⭕
 * ```
 * 与初版（3×3 圆角方块）的区别：拨盘是**正圆**、按键是**真圆**、按可用空间铺满。
 *
 * ## 两个实现上的坑
 * 1. **正圆**：普通布局里一个"圆"会被父容器拉成椭圆（宽高不等），所以方向环用一个
 *    强制正方形的 [DialLayout]（`onMeasure` 取 min），圆钮则固定直径 + 居中；
 * 2. **不用字符图标**：⏻ ⏮ ⏯ 这类符号在很多 ROM 的字体里是缺字的（会显示成方框），
 *    所以圆钮里直接用文字。要图标的话得配一套 drawable（见文件末尾说明）。
 *
 * ## 发送通道（全部复用，无重复实现）
 *  · 方向 / OK / 返回 / 主页 / 菜单 → [HidKeys.tap]（HID usage；**被控端**的 `HID_MAP`
 *    翻成 DPAD / ENTER / BACK / HOME / MENU —— 与「TV 遥控」快捷键条同一条路）；
 *  · 音量 / 静音 / 上一曲 / 播放 / 下一曲 → [HotkeyController]（多媒体位图）；
 *  · 关机 / 重启 → [com.allperiph.wireless.TvControllerClient.power]（0x22，需被控端 root）。
 */
class RemoteActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var targetView: TextView? = null
    private var landscape = false

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(ThemePref.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val root = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 横屏上下紧张、左右富裕；竖屏反过来
            if (landscape) setPadding(dp(18), dp(6), dp(18), dp(6))
            else setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // —— 顶栏：标题 + 选择设备 / 关闭（对齐小米遥控的"设备名 + 右上角操作"）——
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "遥控器"
            setTextColor(getColor(R.color.md_on_surface))
            textSize = if (landscape) 17f else 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(chip("选择设备") { pickTarget() })
        head.addView(chip("关闭") { finish() })
        col.addView(head)

        targetView = TextView(this).apply {
            setTextColor(getColor(R.color.md_on_surface_variant))
            textSize = 12f
            setPadding(dp(2), dp(4), dp(2), dp(4))
            maxLines = 2
        }
        col.addView(targetView)

        // ★ 电源行提到**拨盘上方**：贴顶、离拇指最近，也符合"先开关机、再操作"的顺序；
        //   拨盘下方依次是 功能 / 音量 / 媒体 三行。
        val rows = rows()
        addRow(col, rows[0], topMarginDp = 4, rowWeight = if (landscape) null else 0.78f)

        if (landscape) {
            // 横屏：左拨盘 / 右三行圆钮（各半宽、整高）——竖排会被压成一条缝
            val dual = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            dual.addView(wrapDial(), LinearLayout.LayoutParams(0, -1, 1f))
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            addKeyRows(right, rows, rowWeight = 1f, firstTopMarginDp = 6)
            dual.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(14) })
            col.addView(dual, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            // 竖屏：拨盘比原先**小一档**。原先给 3.2 权重时它的高度超过屏宽，
            //   被 DialLayout 收敛成"几乎占满宽度"的正圆；现在降到 1.7（约 250dp），
            //   剩下的高度分给三行圆钮。
            col.addView(wrapDial(), LinearLayout.LayoutParams(-1, 0, 1.7f))
            addKeyRows(col, rows, rowWeight = 0.85f, firstTopMarginDp = 12)
        }

        col.addView(TextView(this).apply {
            text = "方向/OK/返回/主页/菜单走 HID 键码；音量与媒体走多媒体位图；关机/重启需被控端 root。"
            setTextColor(getColor(R.color.md_on_surface_variant))
            textSize = 11f
            setPadding(dp(2), dp(6), dp(2), 0)
        })

        setContentView(root)
        Backdrop.apply(root, getColor(R.color.md_background))
        refreshTarget()
    }

    override fun onResume() {
        super.onResume()
        refreshTarget()
    }

    // ————————————————————————————— 拨盘 —————————————————————————————

    /** 把正圆拨盘居中放进一个可伸缩的容器（避免它在非方形区域里被拉成椭圆） */
    private fun wrapDial(): FrameLayout {
        val box = FrameLayout(this)
        val dial = DialLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.md_surface))
                setStroke(dp(1), getColor(R.color.card_stroke))
            }
            // 四向箭头：贴着环内缘，背景透明（圆环本身就是"按键"的形状）
            addView(arrow("▲", Gravity.TOP or Gravity.CENTER_HORIZONTAL) { HidKeys.tap(U_UP) })
            addView(arrow("▼", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) { HidKeys.tap(U_DOWN) })
            addView(arrow("◀", Gravity.START or Gravity.CENTER_VERTICAL) { HidKeys.tap(U_LEFT) })
            addView(arrow("▶", Gravity.END or Gravity.CENTER_VERTICAL) { HidKeys.tap(U_RIGHT) })
            // 中心 OK：主色圆钮，尺寸在 onSizeChanged 里按拨盘直径的 34% 设定
            val ok = TextView(this@RemoteActivity).apply {
                text = "OK"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.md_primary))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Feedback.tap(this)
                    HidKeys.tap(U_ENTER)
                }
            }
            // 显式写 FrameLayout.LayoutParams：继承来的嵌套类不能通过子类名（DialLayout.LayoutParams）解析
            addView(ok, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            centerChild = ok
        }
        // 撑满容器（-1,-1）再靠 DialLayout 自己收敛成正方形 —— 用 wrap_content 的话
        // 它会缩成"刚好包住几个箭头"的小圈，拨盘就做不大了。
        box.addView(dial, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        return box
    }

    /**
     * 环上的一个方向键：透明底 + 大字号箭头（点击区固定 64dp，够手指戳）。
     *
     * 参数名用 `place` 而不是 `gravity`：后者会**遮蔽** TextView 自己的 gravity 属性，
     * 于是 `gravity = Gravity.CENTER` 变成给 val 参数赋值（编译报 "val cannot be reassigned"）。
     */
    private fun arrow(glyph: String, place: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 26f
            this.gravity = Gravity.CENTER
            setTextColor(getColor(R.color.md_on_surface))
            isClickable = true
            isFocusable = true
            // 注意 arrowSize() 自己已经乘过 density（返回 px），这里不能再套 dp()
            layoutParams = FrameLayout.LayoutParams(arrowSize(), arrowSize(), place).apply {
                when (place and Gravity.HORIZONTAL_GRAVITY_MASK) {
                    Gravity.START -> leftMargin = dp(6)
                    Gravity.END -> rightMargin = dp(6)
                }
                when (place and Gravity.VERTICAL_GRAVITY_MASK) {
                    Gravity.TOP -> topMargin = dp(6)
                    Gravity.BOTTOM -> bottomMargin = dp(6)
                }
            }
            setOnClickListener {
                Feedback.tap(this)
                onClick()
            }
        }

    /**
     * 强制正方形的容器 —— 圆环的关键。
     * 普通 FrameLayout 会把子视图撑满父容器（宽 ≠ 高 → 椭圆），
     * 这里 `onMeasure` 取 min(宽,高) 保证是正圆；顺带把中心 OK 按直径比例放大。
     */
    private class DialLayout @JvmOverloads constructor(
        ctx: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(ctx, attrs) {

        var centerChild: View? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val side = minOf(measuredWidth, measuredHeight)
            setMeasuredDimension(side, side)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val c = centerChild ?: return
            val size = (minOf(w, h) * 0.34f).toInt().coerceAtLeast(48)
            val lp = c.layoutParams as? LayoutParams ?: return
            if (lp.width != size || lp.height != size) {
                lp.width = size
                lp.height = size
                c.layoutParams = lp
            }
        }
    }

    // ————————————————————————————— 按键行 —————————————————————————————

    /**
     * 四行圆钮清单，**顺序即屏幕顺序**：电源（拨盘上方）· 功能 · 音量 · 媒体。
     *
     * 竖屏：电源行 0.78 权重、拨盘 1.7、其余三行各 0.85；
     * 横屏：电源行贴顶用满宽度，下方左拨盘 / 右三行各 1 权重均分。
     */
    private fun rows(): List<Array<Pair<String, () -> Unit>>> = listOf(
        // 显式写出元素类型：`HidKeys.tap()` 返回 Int，不标注 lambda 会被推断成 `() -> Int`，
        // 与 `() -> Unit` 不兼容（编译期 Argument type mismatch）。
        // 行序即屏幕顺序（电源行在拨盘上方）：电源是遥控器上最需要「盲按」到的一行，
        // 放最上面离拇指最近；其余按使用频率依次是 功能 / 音量 / 媒体。
        arrayOf(
            "电源" to { HidKeys.tap(U_POWER) },
            "关机" to { confirmPower(0, "关机") },
            "重启" to { confirmPower(1, "重启") },
        ),
        arrayOf(
            "返回" to { HidKeys.tap(U_ESC) },
            "主页" to { HidKeys.tap(U_HOME) },
            "菜单" to { HidKeys.tap(U_MENU) },
        ),
        arrayOf(
            "音量 −" to { media(HotkeyController.BIT_VOLUME_DOWN) },
            "静音" to { media(HotkeyController.BIT_MUTE) },
            "音量 ＋" to { media(HotkeyController.BIT_VOLUME_UP) },
        ),
        arrayOf(
            "上一曲" to { media(HotkeyController.BIT_PREV_TRACK) },
            "播放" to { media(HotkeyController.BIT_PLAY_PAUSE) },
            "下一曲" to { media(HotkeyController.BIT_NEXT_TRACK) },
        ),
    )
    /** 拨盘下方的三行：功能 / 音量 / 媒体（[rows] 的 1..3 项） */
    private fun addKeyRows(
        parent: LinearLayout,
        rows: List<Array<Pair<String, () -> Unit>>>,
        rowWeight: Float,
        firstTopMarginDp: Int,
    ) {
        for (i in 1..3) addRow(parent, rows[i], if (i == 1) firstTopMarginDp else 4, rowWeight)
    }

    /**
     * 加一行圆钮。
     *
     * @param rowWeight null → 行高按内容（`wrap_content`；横屏顶部那条用满宽度的电源行，
     *                  不需要靠权重去挤高度）；
     *                  非 null → 按该权重平分父容器的剩余高度（竖屏整屏排布）。
     */
    private fun addRow(
        parent: LinearLayout,
        row: Array<Pair<String, () -> Unit>>,
        topMarginDp: Int,
        rowWeight: Float?,
    ) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 每格是一个占位容器，圆钮在其中**居中且固定直径** —— 这样才是正圆，
        // 否则跟着格子宽高走就又变成椭圆了。
        val d = circleSize()
        for ((label, action) in row) {
            val cell = FrameLayout(this)
            cell.addView(
                circle(label, d) { action() },
                FrameLayout.LayoutParams(d, d, Gravity.CENTER),
            )
            line.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        // 有权重时行高交给权重决定；无权重时**显式给行高 = 圆钮直径**（子视图是
        // match_parent，父行若用 wrap_content 会退化成 0）。
        val lp = if (rowWeight == null) LinearLayout.LayoutParams(-1, d)
        else LinearLayout.LayoutParams(-1, 0, rowWeight)
        lp.topMargin = dp(topMarginDp)
        parent.addView(line, lp)
    }

    /// 圆钮直径：横屏上下空间小，用小一号
    private fun circleSize(): Int = if (landscape) dp(58) else dp(78)

    /// 环上箭头的点击区：拨盘收小后跟着收一档（仍 ≥ 48dp，满足最小触控目标）
    private fun arrowSize(): Int = if (landscape) dp(50) else dp(56)

    /** 一个圆钮：正圆底 + 文字（不用字符图标，见类注释） */
    private fun circle(label: String, size: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = if (label.length > 2) 13f else 15f
            gravity = Gravity.CENTER
            val fg = when (label) {
                "关机" -> 0xFFD23F31.toInt()
                "重启" -> 0xFFF0A020.toInt()
                "电源" -> getColor(R.color.md_primary)
                else -> getColor(R.color.md_on_surface)
            }
            setTextColor(fg)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.md_surface))
                setStroke(dp(1), getColor(R.color.card_stroke))
            }
            isClickable = true
            isFocusable = true
            // 尺寸由调用方通过 addView(child, params) 传入（父容器是 FrameLayout 格子）
            setOnClickListener {
                Feedback.tap(this)
                onClick()
            }
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

    /**
     * 选设备：交给主页的选择器（发现 / 连接逻辑只保留一处实现），
     * 但**必须带 [MainActivity.EXTRA_PICK_DEVICE]** ——
     * 否则 MainActivity 只会停在默认的触控板页，用户看到的就是"点选择设备跳回触控页"。
     * 带上之后它会直接弹出选择器，选完/取消自动 finish 回到本页，本页 onResume 再刷新目标。
     */
    private fun pickTarget() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PICK_DEVICE, true),
        )
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
        // HID usage（page 0x07）—— 与「TV 遥控」快捷键套一致，被控端 HID_MAP 负责翻译
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
