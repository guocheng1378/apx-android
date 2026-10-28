package com.allperiph.ui.miuix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allperiph.R

/**
 * 界面形态 —— 「同一份界面按当前窗口宽度换骨架」的判据（Compose 重构线）。
 *
 * ## 断点从哪来
 * 数值取自 MiuixGuiExample（katiusu/MiuixGuiExample）的宽屏自适应：
 * · ≥ 840dp —— 宽屏（它的 `shouldShowSplitPane()`：宽度 ≥ 840dp，或 ≥ 600dp 且高宽比 < 1.2）；
 * · ≥ 1200dp —— 完整侧栏（它的 `shouldExpandNavigationRail()`）。
 * 这里另补 600dp 一档（平板竖屏 / 分屏），把「高宽比 < 1.2」那条折成显式档位。
 *
 * ## 为什么判据是「窗口宽度」而不是「设备类型」
 * 手机横过来、折叠屏展开、分屏、投屏到电视 —— 同一个 Activity 都会落到不同形态。
 * 所以形态是**运行时会变的量**，界面必须能自由切换，不能在编译期定死。
 *
 * ## 手动覆盖
 * [LocalMiuixFormOverride] 给一个强制值（null = 跟随窗口）：
 * 一来能在真机上立刻验证各形态布局（不用真去改窗口大小），
 * 二来「屏幕形态固定」的设备（TV 端）可以显式钉死形态，不受窗口测量抖动影响。
 */
@Immutable
enum class MiuixForm(val labelRes: Int, val columns: Int) {

    /** 手机：单列 */
    PHONE(R.string.form_phone, 1),

    /** 平板竖屏：两列 */
    TABLET(R.string.form_tablet, 2),

    /** 宽屏（横屏平板 / 小窗）：两列 */
    WIDE(R.string.form_wide, 2),

    /** 电视 / 桌面：三列 */
    TV(R.string.form_tv, 3);

    companion object {
        /** 平板断点 */
        val TabletMinWidth: Dp = 600.dp

        /** 宽屏断点（两列 + 更宽留白） */
        val WideMinWidth: Dp = 840.dp

        /** 电视断点（三列） */
        val TvMinWidth: Dp = 1200.dp

        /** 按窗口宽度判形态 */
        fun of(width: Dp): MiuixForm = when {
            width >= TvMinWidth -> TV
            width >= WideMinWidth -> WIDE
            width >= TabletMinWidth -> TABLET
            else -> PHONE
        }
    }
}

/**
 * 形态的手动覆盖：null = 跟随窗口宽度自动判定；非 null = **强制**用该形态。
 *
 * 放在 CompositionLocal 里而不是参数里，是为了「深层组件也能读到当前形态」——
 * 迁移后的组件内部可能要按形态给不同的内边距 / 列数。
 */
val LocalMiuixFormOverride = compositionLocalOf<MiuixForm?> { null }

/** 当前窗口宽度（dp）—— 形态判据的原始量 */
@Composable
fun currentWindowWidth(): Dp {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) { windowInfo.containerSize.width.toDp() }
}

/** 当前形态：手动覆盖优先，否则按窗口宽度自动判定 */
@Composable
fun currentMiuixForm(): MiuixForm {
    val override = LocalMiuixFormOverride.current
    if (override != null) return override
    return MiuixForm.of(currentWindowWidth())
}

/**
 * 下拉项 → 形态覆盖：0 = 跟随窗口，其余按 [MiuixForm] 声明顺序。
 * 放在这里是因为「形态选择器」在设置页与组件总览页都要用（组件级自定义的形态选择）。
 */
fun formIndexOf(override: MiuixForm?): Int =
    if (override == null) 0 else MiuixForm.entries.indexOf(override) + 1

/** 形态覆盖 → 形态：0 = 跟随窗口 */
fun formOverrideOf(index: Int): MiuixForm? =
    if (index <= 0) null else MiuixForm.entries.getOrNull(index - 1)
