package com.allperiph.ui

import android.content.Context
import android.util.TypedValue
import com.allperiph.R

/**
 * **强调色的唯一出口**（Views 侧；Compose 侧再经 `ui.miuix.ApAccent` 换算成 Compose 颜色）。
 *
 * ## 为什么不能直接 `getColor(R.color.md_primary)`
 * `R.color.md_primary` 是**编译期常量**：它只跟 `values-night` 变，看不见运行时叠在主题上的
 * 两件事 ——
 *  · 「跟随系统取色（莫奈）」：`APMonetOverlay` 把 `?attr/apPrimary` 四个属性指到
 *    `system_accent1_*`；
 *  · 模块皮肤（触控板 / 键盘 / 快捷键各一套强调色）。
 * 直接取常量在这两种情况下不会报错、也不会崩，只是**颜色默默不对**。所以取强调色一律走
 * 本对象：它 resolve 的是主题属性，三条来源一次全中。
 *
 * 中性色（背景 / 表面 / 描边 / 文字三级）不参与运行时叠加，仍可直接用 `md_*` 令牌。
 *
 * ## 取色来源（[COLOR_BRAND] / [COLOR_SYSTEM]）
 * 莫奈开关也在这里，因为它决定上面那层叠加要不要盖。叠加必须在**正在用的那个主题对象**上
 * 做（[applyRuntimeTheme]）：Activity 初始化时会 `resources.newTheme()` 新建主题，
 * 父上下文上做过的一律丢。
 */
object ApPalette {

    // —————————————————————————— 取色来源 ——————————————————————————

    private const val KEY_COLOR_SOURCE = ApAppearancePrefs.KEY_COLOR_SOURCE

    /** 品牌蓝：沿用 `md_*` 令牌里写死的强调色（默认，与改动前完全一致） */
    const val COLOR_BRAND = 0

    /** 系统取色：强调色跟随系统壁纸取色（Android 12+ 的莫奈），中性色仍走 `md_*` */
    const val COLOR_SYSTEM = 1

    val COLOR_SOURCE_LABELS = listOf("品牌蓝", "跟随系统取色")

    fun colorSource(ctx: Context): Int =
        ApAppearancePrefs.of(ctx).getInt(KEY_COLOR_SOURCE, COLOR_BRAND)
            .coerceIn(COLOR_BRAND, COLOR_SYSTEM)

    fun setColorSource(ctx: Context, i: Int) {
        val v = i.coerceIn(COLOR_BRAND, COLOR_SYSTEM)
        ApAppearancePrefs.of(ctx).edit().putInt(KEY_COLOR_SOURCE, v).apply()
        monetCache = v == COLOR_SYSTEM
    }

    /**
     * 当前是否「跟随系统取色」。
     *
     * 顺手把结果缓存到 [monetCache]：对话框样式要在**没有 Context 可查**的地方
     * （`AlertDialog.Builder(ctx, themeRes)` 的第二个参数）也能挑对，见 [dialogStyle]。
     * 缓存由 Activity onCreate / `ThemePref.wrap` 每次进入界面时刷新，不会长期失真。
     */
    fun monet(ctx: Context): Boolean {
        val on = colorSource(ctx) == COLOR_SYSTEM
        monetCache = on
        return on
    }

    @Volatile
    private var monetCache = false

    /**
     * 把当前选择叠加到 [ctx] 的主题上（只叠加，不替换）。
     *
     * 为什么必须在 Activity.onCreate 里显式调：`android:theme` 是清单里写死的，
     * Activity 初始化时用 `resources.newTheme()` **新建**一个主题再套清单样式，
     * 所以父上下文上做过的 applyStyle 一律丢 —— 只有叠到正在用的那个主题对象上才算数。
     */
    fun applyRuntimeTheme(ctx: Context) {
        if (!monet(ctx)) return
        runCatching { ctx.theme.applyStyle(R.style.APMonetOverlay, true) }
            .onFailure { android.util.Log.w("ApPalette", "莫奈叠加失败：${it.message}") }
    }

    /** 对话框主题：开着莫奈就用带系统取色的变体（对话框主题不继承 Activity 的叠加层） */
    fun dialogStyle(): Int = if (monetCache) R.style.Theme_AllPeriph_Miuix_Dialog_Monet
                             else R.style.Theme_AllPeriph_Miuix_Dialog

    /**
     * 读一个「强调色主题属性」，读不到就退回 [fallback] 颜色资源。
     *
     * `?attr/apPrimary` 这类属性由主题提供：默认主题指到 `md_*` 令牌，莫奈叠加层指到
     * `system_accent1_*`。这是本文件的核心原语，[primary] / [onPrimary] 等都经它。
     */
    fun tok(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        val ok = runCatching { ctx.theme.resolveAttribute(attr, tv, true) }.getOrDefault(false)
        val isColor = tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        return if (ok && isColor) tv.data else ctx.getColor(fallback)
    }

    fun primary(ctx: Context): Int = tok(ctx, R.attr.apPrimary, R.color.md_primary)

    fun primaryPressed(ctx: Context): Int =
        tok(ctx, R.attr.apPrimaryPressed, R.color.md_primary_pressed)

    fun primaryContainer(ctx: Context): Int =
        tok(ctx, R.attr.apPrimaryContainer, R.color.md_primary_container)

    fun onPrimary(ctx: Context): Int = tok(ctx, R.attr.apOnPrimary, R.color.md_on_primary)

    /** 同色调浅底（12% 不透明度） */
    fun softOf(color: Int): Int = (color and 0x00FFFFFF) or (0x1F shl 24)

    /** 当前皮肤（选了就取它，否则跟随主题）—— 只有强调色相关的部分 */
    fun accentOf(ctx: Context, scope: String): Int {
        val picked = ApSkin.picked(ctx, scope).isNotBlank()
        return if (picked) ApSkin.current(ctx, scope).accent else primary(ctx)
    }
}
