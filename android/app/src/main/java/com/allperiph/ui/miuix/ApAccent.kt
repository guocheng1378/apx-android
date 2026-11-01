package com.allperiph.ui.miuix

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.allperiph.ui.ApPalette
import com.allperiph.ui.ApSkin
/**
 * 强调色与模板色的**唯一换算处**（Compose 侧）。
 *
 * ## 为什么需要"色相拉齐"
 * 快捷键模板各有自己的品牌色（`res/values/colors.xml` 里的 `hotkey_tpl_*` 令牌），直接铺到 Miuix 组件上会和
 * 用户选的主题肤色打架；而全量替换成主题色又会丢掉"这套模板是哪个"的可辨性。
 * 折中口径（2026-10-28 补丁验证过、本次沿用）：
 *
 *   **只把色相拉到系统强调色（system_accent*_600）上，饱和度与明度保持模板自己的值。**
 *
 * 这样同一模板在任何人机器上仍是同一个"性格"（浓淡不变、明暗不变），但整机色相一致。
 * 关掉「跟随系统取色」时不做任何换算 —— 用户选的就是品牌蓝。
 */
object ApAccent {

    /** 当前明暗（跟随 ThemePref 写进 configuration 的结果，与 Views 侧同一判据） */
    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 系统强调色：浅色取 600，深色取 200（深色底下 600 对比不足） */
    fun systemAccent(ctx: Context, night: Boolean = isNight(ctx)): Int = ctx.resources.getColor(
        if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600,
        ctx.theme,
    )

    /**
     * 保饱和度、保明度，只换色相。
     *
     * 色相取短弧：HSV 的 H 是 0..360 环，直接赋目标值在跨 0/360 时会产生"反着转"的观感，
     * 但这里只做**瞬时换色**、没有插值动画，所以不需要处理弧向。
     */
    fun huePull(base: Int, target: Int): Int {
        if (base == 0 || target == 0) return base
        val b = FloatArray(3)
        Color.colorToHSV(base, b)
        val t = FloatArray(3)
        Color.colorToHSV(target, t)
        b[0] = t[0]
        return Color.HSVToColor(Color.alpha(base), b)
    }

    /** 模板色换算：跟随系统取色时拉色相，否则原样返回 */
    fun templateTint(ctx: Context, templateColor: Int): Int =
        if (ApPalette.monet(ctx)) huePull(templateColor, systemAccent(ctx)) else templateColor

    /** 当前强调色：皮肤 > 主题主色（`apPrimary`，跟随系统取色时已经是系统色） */
    fun resolved(ctx: Context): Int =
        MiuixTokens.skinAccent?.toArgb() ?: ApPalette.primary(ctx)

    /** 同色调浅底（12% 不透明度），与 [ApPalette.softOf] 同一条口径 */
    fun soft(color: Int): Int = ApPalette.softOf(color)

    /** 皮肤切换后调用：把 ApSkin 当前选择同步给 Miuix 主题，避免两套颜色并存 */
    fun syncSkin(ctx: Context, scope: String = ApSkin.TOUCHPAD) {
        val picked = ApSkin.picked(ctx, scope).isNotBlank()
        MiuixTokens.skinAccent = if (picked) {
            androidx.compose.ui.graphics.Color(ApSkin.current(ctx, scope).accent)
        } else {
            null
        }
    }
}
