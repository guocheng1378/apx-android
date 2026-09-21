package com.allperiph.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import kotlin.math.abs

/**
 * 快捷键网格（v1.31）：主页与操控面**共用同一份实现**。
 *
 * 之前两边各写一套（render / reorder / 管理弹窗 / 编辑弹窗），改一处就得同步另一处，
 * 已经出现过只改一边的返工。现在行为全收在这里，宿主只提供皮肤与容器：
 *
 * · 轻点发送 / 长按按住 / 长按后拖动排序（手势见 [ComboChip]）
 * · 网格形状：每行 [Style.perRow] 个；[Style.fixedRows] > 0 时固定行数（不足补空格占位）
 * · **行数兜底**：条目超过固定行数能装下的量时，自动放弃"行高均分"，改固定行高交给外层滚动，
 *   避免把每行压瘪（横屏）或把触摸区挤没（竖屏）
 * · 「编辑」一屏管理：上移 / 下移 / 改 / 删 / 新建 / 恢复默认
 */
class HotkeyBoard(
    private val act: Activity,
    private val container: LinearLayout,
    private val styleProvider: () -> Style,
    private var onChanged: (() -> Unit)? = null,
) {

    /** 皮肤：颜色、圆角、间距、网格形状（主页用模板色，操控面用中性色） */
    data class Style(
        val chipText: Int,
        val chipBg: Int,
        val chipStroke: Int,
        val chipRadiusDp: Int,
        val padVDp: Int,
        val perRow: Int,
        val fixedRows: Int = 0,
        val stretchRows: Boolean = false,
        val accent: Int,
        val textPrimary: Int,
        val textSecondary: Int,
    )

    var shortcuts: MutableList<HidKeys.Combo> = HotkeyStore.load(act)
        private set

    /** 从盘上重新读取并重画（套用模板 / 外部改动后调用） */
    fun reload() {
        shortcuts = HotkeyStore.load(act)
        render()
    }

    // —————————————————————————— 网格 ——————————————————————————

    fun render() {
        val s = styleProvider()
        container.removeAllViews()
        val usage = if (HotkeyStore.isAutoSort(act)) HotkeyStore.loadUsage(act) else emptyMap()
        val ordered = shortcuts.withIndex().sortedByDescending {
            usage[HotkeyStore.comboKey(it.value)] ?: 0
        }

        // 条目超出固定网格能装下的量时不再均分行高，否则每行会被压得越来越矮
        val capacity = s.fixedRows * s.perRow
        val stretch = s.stretchRows && (s.fixedRows <= 0 || ordered.size <= capacity)

        val rows: List<List<IndexedValue<HidKeys.Combo>>> =
            if (s.fixedRows > 0) {
                ordered.chunked(s.perRow).toMutableList().apply {
                    while (size < s.fixedRows) add(emptyList()) // 不足也把格子留出来
                }
            } else {
                ordered.chunked(s.perRow)
            }

        val cellH = if (stretch) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        rows.forEach { chunk ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (i, c) ->
                row.addView(buildChip(i, c, s), LinearLayout.LayoutParams(0, cellH, 1f).cellMargins())
            }
            repeat(s.perRow - chunk.size) { // 补空格：同尺寸占位，网格形状稳定
                row.addView(
                    TextView(act).apply {
                        text = ""
                        textSize = 14f
                        setPadding(dp(8), dp(s.padVDp), dp(8), dp(s.padVDp))
                    },
                    LinearLayout.LayoutParams(0, cellH, 1f).cellMargins(),
                )
            }
            container.addView(
                row,
                if (stretch) LinearLayout.LayoutParams(-1, 0, 1f)
                else LinearLayout.LayoutParams(-1, -2),
            )
        }
    }

    private fun buildChip(index: Int, c: HidKeys.Combo, s: Style): View = TextView(act).apply {
        text = c.label
        setTextColor(s.chipText)
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), dp(s.padVDp), dp(8), dp(s.padVDp))
        background = rounded(s.chipBg, dp(s.chipRadiusDp), s.chipStroke)
        isClickable = true
        isFocusable = true
        tag = index // 真实下标：显示顺序受自动排序影响，编辑与拖拽都靠它定位
        contentDescription = "${c.label}，${HidKeys.comboText(c)}"
        ComboChip.attach(
            this, c,
            onSent = { HotkeyStore.bump(act, c) },
            onReorder = { from, dropCenterX -> reorder(from, dropCenterX) },
        )
    }

    private fun LinearLayout.LayoutParams.cellMargins(): LinearLayout.LayoutParams = apply {
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    /** 拖拽落位：按所有块的中心 X 找最近的一块，把条目插到那个位置并落盘 */
    private fun reorder(from: Int, dropCenterX: Float) {
        val views = chipViews()
        if (views.size < 2 || from !in shortcuts.indices) return
        val displayOrder = views.mapNotNull { it.tag as? Int }
        val movingAt = displayOrder.indexOf(from)
        if (movingAt < 0) return
        val loc = IntArray(2)
        var nearest = 0
        var best = Float.MAX_VALUE
        views.forEachIndexed { k, v ->
            v.getLocationInWindow(loc)
            val d = abs(loc[0] + v.width / 2f - dropCenterX)
            if (d < best) {
                best = d
                nearest = k
            }
        }
        val rest = displayOrder.toMutableList().apply { removeAt(movingAt) }
        val insertAt = if (nearest > movingAt) nearest - 1 else nearest
        rest.add(insertAt.coerceIn(0, rest.size), from)
        shortcuts = rest.map { shortcuts[it] }.toMutableList()
        preferManualOrder()
        HotkeyStore.save(act, shortcuts)
        render()
        onChanged?.invoke()
    }

    private fun chipViews(): List<View> {
        val out = ArrayList<View>()
        for (r in 0 until container.childCount) {
            val row = container.getChildAt(r) as? ViewGroup ?: continue
            for (k in 0 until row.childCount) {
                val v = row.getChildAt(k)
                if (v.tag is Int) out.add(v)
            }
        }
        return out
    }

    /** 手动排序（拖拽 / 上下移）会关掉自动排序，否则刚排好的顺序会被常用度顶回去 */
    private fun preferManualOrder() {
        if (!HotkeyStore.isAutoSort(act)) return
        HotkeyStore.setAutoSort(act, false)
    }

    // —————————————————————————— 管理列表 ——————————————————————————

    /** 「编辑」入口：上移 / 下移 / 改 / 删 / 新建 / 恢复默认 */
    fun managerDialog() {
        val s = styleProvider()
        val list = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

        fun rebuild() {
            list.removeAllViews()
            shortcuts.forEachIndexed { i, c ->
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(act).apply {
                    text = c.label
                    textSize = 15f
                    setTextColor(s.textPrimary)
                })
                col.addView(TextView(act).apply {
                    text = HidKeys.comboText(c)
                    textSize = 12f
                    setTextColor(s.textSecondary)
                })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(action("↑", "上移 ${c.label}") { move(i, -1); rebuild() })
                row.addView(action("↓", "下移 ${c.label}") { move(i, 1); rebuild() })
                // 编辑保存后让列表跟着刷新（之前要关掉重开才看到新名字）
                row.addView(action("改", "编辑 ${c.label}") { editDialog(i) { rebuild() } })
                row.addView(action("删", "删除 ${c.label}") {
                    shortcuts.removeAt(i)
                    HotkeyStore.save(act, shortcuts)
                    HotkeyStore.markCustom(act)
                    render()
                    rebuild()
                    onChanged?.invoke()
                })
                list.addView(row)
            }
            if (shortcuts.isEmpty()) {
                list.addView(TextView(act).apply {
                    text = "还没有快捷键，点下面「新建」加一条"
                    textSize = 13f
                    setTextColor(s.textSecondary)
                    setPadding(0, dp(10), 0, dp(10))
                })
            }
        }
        rebuild()

        val dlg = AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("快捷键管理")
            .setView(box)
            .setPositiveButton("新建", null)
            .setNeutralButton("恢复默认", null)
            .setNegativeButton("完成", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                editDialog(null) { rebuild() }
            }
            // 恢复默认是"整条替换"，确认后列表已无意义 → 直接收起
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                confirmReset()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun action(text: String, desc: String, onTap: () -> Unit): TextView = TextView(act).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(6), dp(6))
        setTextColor(styleProvider().accent)
        isClickable = true
        isFocusable = true
        contentDescription = desc
        setOnClickListener { onTap() }
    }

    private fun move(index: Int, delta: Int) {
        val to = index + delta
        if (index !in shortcuts.indices || to !in shortcuts.indices) return
        val item = shortcuts.removeAt(index)
        shortcuts.add(to, item)
        preferManualOrder()
        HotkeyStore.save(act, shortcuts)
        HotkeyStore.markCustom(act)
        render()
        onChanged?.invoke()
    }

    private fun confirmReset() {
        AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("恢复默认")
            .setMessage("把快捷方式恢复为出厂预置？自建项会丢失。")
            .setPositiveButton("恢复") { _, _ ->
                shortcuts = HidKeys.DEFAULTS.toMutableList()
                HotkeyStore.save(act, shortcuts)
                HotkeyStore.markCustom(act)
                render()
                onChanged?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // —————————————————————————— 编辑弹窗 ——————————————————————————

    /**
     * 新建（index = null）或编辑（index >= 0）；改完视为自定义。
     * @param onDone 保存 / 删除后回调（管理列表据此重建）
     */
    fun editDialog(index: Int?, onDone: (() -> Unit)? = null) {
        val s = styleProvider()
        val editing = index != null
        val cur = if (editing) shortcuts[index!!] else HidKeys.Combo("", HidKeys.MOD_CTRL, 0x06)
        var picked = HidKeys.KEY_CHOICES.firstOrNull { it.second == cur.usage } ?: HidKeys.KEY_CHOICES[0]

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        box.addView(label("名称", s))
        val nameEt = EditText(act).apply {
            setText(cur.label)
            hint = "例如：复制"
            textSize = 15f
            setSingleLine()
            setTextColor(s.textPrimary)
        }
        box.addView(nameEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        box.addView(label("修饰键", s), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        val modRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val boxes = LinkedHashMap<Int, CheckBox>()
        HidKeys.MOD_LABELS.forEach { (text, bit) ->
            val cb = CheckBox(act).apply {
                this.text = text
                textSize = 14f
                setTextColor(s.textPrimary)
                isChecked = cur.mod and bit != 0
            }
            boxes[bit] = cb
            modRow.addView(cb, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        box.addView(modRow)

        // 按键：先选组再选键（79 项平铺的 Spinner 太难滑）
        box.addView(label("按键", s), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        val keyBtn = TextView(act).apply {
            text = picked.first
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(s.accent)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(s.chipBg, dp(12), s.chipStroke)
            isClickable = true
            isFocusable = true
            contentDescription = "选择按键，当前 ${picked.first}"
            setOnClickListener {
                pickKey { k ->
                    picked = k
                    text = k.first
                    contentDescription = "选择按键，当前 ${k.first}"
                }
            }
        }
        box.addView(keyBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val dlg = AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle(if (editing) "编辑快捷键" else "新建快捷键")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .apply { if (editing) setNeutralButton("删除", null) }
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEt.text.toString().trim().ifEmpty { picked.first }
                var mod = 0
                boxes.forEach { (bit, cb) -> if (cb.isChecked) mod = mod or bit }
                val item = HidKeys.Combo(name, mod, picked.second)
                if (index != null) shortcuts[index] = item else shortcuts.add(item)
                HotkeyStore.save(act, shortcuts)
                HotkeyStore.markCustom(act) // 逐条改过 → 不再是模板
                render()
                onChanged?.invoke()
                onDone?.invoke()
                dlg.dismiss()
            }
            if (index != null) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    shortcuts.removeAt(index)
                    HotkeyStore.save(act, shortcuts)
                    HotkeyStore.markCustom(act)
                    render()
                    onChanged?.invoke()
                    onDone?.invoke()
                    dlg.dismiss()
                }
            }
        }
        dlg.show()
    }

    /** 按键选择：一级选组、二级选键 */
    private fun pickKey(onPicked: (Pair<String, Int>) -> Unit) {
        val groups = HidKeys.KEY_GROUPS.map { it.first }.toTypedArray()
        AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
            .setTitle("选择按键 · 分组")
            .setItems(groups) { _, gi ->
                val (groupName, keys) = HidKeys.KEY_GROUPS[gi]
                AlertDialog.Builder(act, R.style.Theme_AllPeriph_Miuix_Dialog)
                    .setTitle("选择按键 · $groupName")
                    .setItems(keys.map { it.first }.toTypedArray()) { _, ki -> onPicked(keys[ki]) }
                    .setNegativeButton("返回", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun label(text: String, s: Style): TextView = TextView(act).apply {
        this.text = text
        textSize = 12f
        setTextColor(s.textSecondary)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(1, stroke)
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()
}
