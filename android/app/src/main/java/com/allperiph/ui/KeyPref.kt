package com.allperiph.ui

import android.content.Context
import com.allperiph.R
import org.json.JSONArray

/**
 * 键盘布局偏好（v1.33）：
 *
 * · **键位排列**（[LAYOUT_LABELS]）：QWERTY / AZERTY / Dvorak 三套字母排法，
 *   换排后字母键依然能发对应 usage（A-Z 都在 KEY_CHOICES 内，符号行不动）；
 * · **键帽密度**（[DENSITY_LABELS]）：紧凑 / 标准 / 宽松 —— 圆角、间距、字号、键高一起变；
 * · **自定义布局**（[customRows] / [setCustomRows]）：整套行表，留作第 8 套页签
 *   "自定义"渲染；null = 没编辑过，自定义页签会引导去编辑。
 *
 * 与 [ApPalette] / [ApSkin] 正交：主题色 / 背景 / 动效 / 手感在那里，这里是"键盘长什么样、按什么排"。
 */
object KeyPref {

    private const val PREF = "apx_kb"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_DENSITY = "density"
    private const val KEY_CUSTOM = "custom_rows"
    private const val KEY_KEYCAP_STYLE = "keycap_style"
    private const val KEY_EFFECT = "fx"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // —————————————————————————— 键位排列 ——————————————————————————

    val LAYOUT_LABELS = listOf("QWERTY", "AZERTY", "Dvorak")

    fun layoutIndex(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LAYOUT, 0).coerceIn(0, LAYOUT_LABELS.size - 1)

    fun setLayout(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_LAYOUT, i.coerceIn(0, LAYOUT_LABELS.size - 1)).apply()
    }

    /**
     * 当前排列方案下的**完整键盘**行表（数字行 + 3 行字母 + 功能行，共 5 行）。
     * 数字行与功能行三套共用，只换字母行的顺序。
     */
    private val DIGIT_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=")
    private val FN_ROW = listOf("Esc", "Tab", "Space", "Enter", "⌫", "Del")

    fun letterRows(ctx: Context): List<List<String>> {
        val letters = when (layoutIndex(ctx)) {
            1 -> listOf(
                listOf("A", "Z", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]"),
                listOf("Q", "S", "D", "F", "G", "H", "J", "K", "L", "M", ";", "'"),
                listOf("W", "X", "C", "V", "B", "N", ",", ".", "/"),
            )
            2 -> listOf(
                listOf("'", ",", ".", "P", "Y", "F", "G", "C", "R", "L", "/", "="),
                listOf("A", "O", "E", "U", "I", "D", "H", "T", "N", "S", "-"),
                listOf(";", "Q", "J", "K", "X", "B", "M", "W", "V", "Z"),
            )
            else -> listOf(
                listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]"),
                listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'"),
                listOf("Z", "X", "C", "V", "B", "N", "M", ",", ".", "/"),
            )
        }
        return listOf(DIGIT_ROW) + letters + listOf(FN_ROW)
    }

    // —————————————————————————— 键帽密度 ——————————————————————————

    val DENSITY_LABELS = listOf("紧凑", "标准", "宽松")

    data class Density(
        val cornerDp: Int,
        val gapDp: Int,
        val textSize: Float,
        val keyScale: Float, // 行高倍率，宽松让键更高更易按
    )

    private val DENSITIES = listOf(
        Density(cornerDp = 6, gapDp = 2, textSize = 12f, keyScale = 0.92f),  // 紧凑
        Density(cornerDp = 9, gapDp = 3, textSize = 13f, keyScale = 1.0f),   // 标准
        Density(cornerDp = 12, gapDp = 5, textSize = 14f, keyScale = 1.12f), // 宽松
    )

    fun densityIndex(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DENSITY, 1).coerceIn(0, DENSITY_LABELS.size - 1)

    fun setDensity(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_DENSITY, i.coerceIn(0, DENSITY_LABELS.size - 1)).apply()
    }

    fun density(ctx: Context): Density = DENSITIES[densityIndex(ctx)]

    // —————————————————————————— 键帽材质 ——————————————————————————

    /**
     * 平面 = 现状（白底细描边）；机械 = 三层立体键帽 + 按压下沉 + 段落感震动；
     * 液态玻璃 = 半透明玻璃键帽 + 高光描边 + 按下泛白跟手（成宇 2026-11-04「液态玻璃按键的皮肤」）。
     */
    const val KEYCAP_STYLE_COUNT = 3

    /**
     * 三档的**显示文案**（顺序 = 档位下标）。取资源而不是 Kotlin 字面量：
     * 选项文案同属界面文案，写死在数据类里既过不了多语言、也会顶高中文棘轮
     * （`check_ui_tokens.py` 只许降不许升）。取串都要 Context，所以是函数不是常量。
     */
    fun keycapStyleLabels(ctx: Context): List<String> = listOf(
        ctx.getString(R.string.ui_keycap_flat),
        ctx.getString(R.string.ui_keycap_mechanical),
        ctx.getString(R.string.ui_keycap_glass),
    )

    /** 档位常量：调用方按名字分支，别在渲染处散写 0/1/2（改标签顺序时不会静默错档） */
    const val KEYCAP_FLAT = 0
    const val KEYCAP_MECHANICAL = 1
    const val KEYCAP_GLASS = 2

    fun keycapStyleIndex(ctx: Context): Int =
        prefs(ctx).getInt(KEY_KEYCAP_STYLE, 0).coerceIn(0, KEYCAP_STYLE_COUNT - 1)

    fun setKeycapStyle(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_KEYCAP_STYLE, i.coerceIn(0, KEYCAP_STYLE_COUNT - 1)).apply()
    }

    /** 机械键帽档启用（材质 + 下沉 + 波形震动一体）；液态玻璃档**不算**机械（键帽不再下沉） */
    fun isMechanical(ctx: Context): Boolean = keycapStyleIndex(ctx) == KEYCAP_MECHANICAL

    /** 液态玻璃键帽档（半透明 + 高光描边；与机械档互斥，同属「键帽材质」这一个下拉） */
    fun isLiquidGlass(ctx: Context): Boolean = keycapStyleIndex(ctx) == KEYCAP_GLASS

    // —————————————————————————— 按键特效 ——————————————————————————

    /**
     * 按键特效档位（成宇 2026-11-04「键盘的特效做几个」）。
     *
     * 与「键帽材质」正交：材质决定键帽**长什么样**，特效决定**按下去画什么**。
     * 关闭档不代表「没有反馈」—— 沿用 Compose 自带的水波纹与机械档的按压下沉，
     * 只是不再叠加额外图层（老机器上开销最低）。
     */
    const val FX_NONE = 0
    const val FX_RIPPLE = 1
    const val FX_HIGHLIGHT = 2
    const val FX_BOUNCE = 3

    const val EFFECT_COUNT = 4

    /** 四档的**显示文案**（顺序 = 档位下标）；理由同 [keycapStyleLabels]。 */
    fun effectLabels(ctx: Context): List<String> = listOf(
        ctx.getString(R.string.ui_keyfx_none),
        ctx.getString(R.string.ui_keyfx_ripple),
        ctx.getString(R.string.ui_keyfx_highlight),
        ctx.getString(R.string.ui_keyfx_bounce),
    )

    fun effectIndex(ctx: Context): Int =
        prefs(ctx).getInt(KEY_EFFECT, 0).coerceIn(0, EFFECT_COUNT - 1)

    fun setEffect(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_EFFECT, i.coerceIn(0, EFFECT_COUNT - 1)).apply()
    }

    // —————————————————————————— 自定义布局 ——————————————————————————

    /**
     * 用户自定义的整套行表（每行是键面文字列表）。
     * - null：尚未编辑，自定义页签应引导去编辑；
     * - 非空：渲染为第 8 套键盘（label → usage 由 [HidKeys.usageByLabel] 反查）。
     *
     * 注：未知键面文字会被反查为 0，发送时静默忽略；编辑器候选键列表（[HidKeys.KEY_GROUPS]）
     * 只给已知 usage 的键，规避这个问题。
     */
    fun customRows(ctx: Context): List<List<String>>? {
        val raw = prefs(ctx).getString(KEY_CUSTOM, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            val rows = mutableListOf<List<String>>()
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                rows += (0 until row.length()).map { row.getString(it) }
            }
            rows
        }.getOrElse { null }
    }

    fun setCustomRows(ctx: Context, rows: List<List<String>>?) {
        prefs(ctx).edit().apply {
            if (rows.isNullOrEmpty()) remove(KEY_CUSTOM)
            else {
                val arr = JSONArray()
                rows.forEach { row ->
                    val r = JSONArray()
                    row.forEach { r.put(it) }
                    arr.put(r)
                }
                putString(KEY_CUSTOM, arr.toString())
            }
        }.apply()
    }
}
