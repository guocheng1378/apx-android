package com.allperiph.ui

import android.content.Context
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
 * 与 [ThemeSkin] 正交：主题色 / 背景 / 动效 / 手感在那里，这里是"键盘长什么样、按什么排"。
 */
object KeyPref {

    private const val PREF = "apx_kb"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_DENSITY = "density"
    private const val KEY_CUSTOM = "custom_rows"

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
