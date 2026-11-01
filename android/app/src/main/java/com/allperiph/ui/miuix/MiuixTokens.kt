package com.allperiph.ui.miuix

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.allperiph.R
import com.allperiph.ui.ApPalette
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Miuix 主题 ↔ 本工程设计令牌的**唯一对接处**（miuix-ui 分支）。
 *
 * ## 为什么要这一层
 * 本工程的配色有两套来源，Miuix 必须接在它们上面而不是平行另起一套：
 *  · `res/values/colors.xml` + `values-night/colors.xml`：md_* 语义令牌
 *    （背景 / 表面 / 主色 / 文字三级 / 描边 / 错误），明暗两套由资源系统切换；
 *  · [com.allperiph.ui.ApSkin]：触控板 / 键盘 / 快捷键三个模块的**强调色皮肤**，
 *    运行时可换 —— 这是 md_* 之外的动态层（见 [skinAccent]，迁移那三页时接线）。
 *
 * 规则：**这里读令牌，不写色值**。色值的唯一真源仍是 colors.xml（Views 与 Compose
 * 共用同一份）；将来要动颜色，只改资源文件，Compose 侧自动跟随。
 *
 * ## 解析方式
 * Compose 里取资源色走 `colorResource(id)`：它按当前 configuration（ThemePref.wrap 已把
 * 用户选择写进去）解析 values / values-night，与 Views 同一条链 —— 亮暗切换不用两套代码。
 *
 * ## Miuix 0.5.0 的 API 口径（对着 v0.5.0 源码核对过，别凭新版文档猜）
 *  · 主题：`MiuixTheme(colors = Colors)`（参数名 colors）；读取走 `MiuixTheme.colorScheme`；
 *  · `Colors` 的语义角色以本文件实际使用的为准（onSurfaceSecondary / onSurfaceVariantSummary /
 *    outline / dividerLine 等，46 个字段，copy() 可用）—— 没列出的角色保持官方默认，
 *    视觉差异最小化；
 *  · `lightColorScheme()` 默认主色 0xFF3482FF，与 md_primary 同色系。
 */
object MiuixTokens {

    /** 皮肤强调色（ApSkin.Skin.accent）覆盖；null = 用 [ApPalette] 解析出的当前强调色。
     *  逐页迁移（触控板 / 键盘 / 快捷键）时由调用方按该模块的皮肤写入。 */
    @Volatile
    var skinAccent: Color? = null

    /**
     * 组装 Miuix 配色：以官方明/暗 scheme 为底，逐个替换成本工程令牌解析出的值。
     *
     * ## 强调色为什么走 ApPalette 而不是 colorResource
     * `colorResource(R.color.md_primary)` 拿到的是**资源常量**：它能跟 `values-night` 变，
     * 但**看不见 `?attr/apPrimary` 的运行时叠加** —— 也就是「跟随系统取色（莫奈）」那条
     * （`APMonetOverlay` 把四个 ap* 属性指到 system_accent1_*）。同理每个模块的皮肤
     * （触控板 / 键盘 / 快捷键的 accent）也走同一个属性。
     *
     * [ApPalette.primary] 走的是 `ctx.theme.resolveAttribute(?attr/apPrimary)`，三条来源
     * （默认 md_primary → 莫奈叠加 → 模块皮肤）一次全中，与 Views 侧**同一口径**，
     * 不在 Compose 里另立一套。中性色（背景 / 表面 / 描边）仍是 md_* 令牌，
     * 它们不参与运行时叠加，保持 `colorResource` 即可。
     *
     * `remember(dark)`：取属性要 resolveAttribute，别每次重组都做。换肤/换取色方案时
     * Activity 会 recreate（见 MainActivity.pickSkin），所以 key 只需 dark。
     */
    @Composable
    fun scheme(dark: Boolean): Colors {
        val ctx = LocalContext.current
        val base = if (dark) darkColorScheme() else lightColorScheme()
        // 三个属性色先**无条件**解析，再按 skinAccent 取舍：`a ?: remember{}` 这种写法里
        // remember 是「有条件调用」，skinAccent 非空时槽位对不上，后续 remember 会读到
        // 别人的缓存。分开写就没有这个问题。
        val themePrimary = remember(dark) { Color(ApPalette.primary(ctx)) }
        val themeOnPrimary = remember(dark) { Color(ApPalette.onPrimary(ctx)) }
        val themePrimaryVariant = remember(dark) { Color(ApPalette.primaryContainer(ctx)) }
        return base.copy(
            primary = skinAccent ?: themePrimary,
            onPrimary = themeOnPrimary,
            primaryVariant = themePrimaryVariant,
            background = colorResource(R.color.md_background),
            onBackground = colorResource(R.color.md_on_background),
            surface = colorResource(R.color.md_surface),
            onSurface = colorResource(R.color.md_on_surface),
            surfaceVariant = colorResource(R.color.md_surface_variant),
            onSurfaceSecondary = colorResource(R.color.md_on_surface_variant),
            onSurfaceVariantSummary = colorResource(R.color.md_on_surface_variant),
            outline = colorResource(R.color.md_outline),
            dividerLine = colorResource(R.color.md_outline),
        )
    }
}

/**
 * 根主题：**所有 Compose 内容都包在这里**。
 *
 * @param dark 强制指定明暗；缺省时读当前 configuration（ThemePref.wrap 已注入用户选择，
 *             AUTO 档则等于系统日夜）—— 与 Views 侧同一判断，不另挂 isSystemInDarkTheme。
 */
@Composable
fun ApMiuixTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = dark ?: (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    MiuixTheme(colors = MiuixTokens.scheme(isDark), content = content)
}
