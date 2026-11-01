package com.allperiph.ui.miuix

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 形态自适应（2026-11-01 起）：**按窗口宽度分级**，不判断机型、不判断平台字段。
 *
 * 为什么不用 `Build.MODEL` / `platform == "..."` 那一套：仓库里 pc/web 与 host 侧的
 * platform 分支只用于**能力差异**（能否枚举播放端点这类），不是排版依据；同一台设备
 * 分屏、折叠、投屏后窗口尺寸都会变，按机型写死必然错。窗口宽度是唯一稳定的输入。
 *
 * Miuix 0.9.4 自身没有 WindowSizeClass 之类的公开 API（模块只有 ui / preference /
 * icons / blur / squircle / shader / nav / window），所以这里落成两层：
 *  · 分级：本文件按 Material 的宽度断点把窗口分成 Compact / Medium / Expanded；
 *  · 形态：各级用**Miuix 自带的组件**表达（Compact 走底部 FloatingNavigationBar +
 *    单栏内容；Medium/Expanded 开始用双栏与更大的 Miuix 卡片，栏位仍是 Miuix 组件），
 *    而不是自己画壳。
 */
enum class ApWidthClass { Compact, Medium, Expanded }

@Immutable
data class ApAdaptiveLayout(
    /** 窗口宽度分级 */
    val widthClass: ApWidthClass,
    /** 窗口宽度（dp） */
    val width: Dp,
    /** 高度（dp） */
    val height: Dp,
    /** 是否横屏（宽 > 高）。横屏策略见 [ApShellActivity]：内容绘制到状态栏之下 */
    val landscape: Boolean,
    /** 内容是否分两栏（Medium 起；设置页把它当"列表 + 详情"） */
    val twoPane: Boolean,
    /** 侧边留白是否要避开挖孔（横屏时刘海在左右两侧，必须垫） */
    val padCutout: Boolean,
)

/**
 * 读当前**窗口**尺寸并给出排版建议。
 *
 * 断点取 Material 3 的宽度分级（600 / 840dp），与 Miuix 组件的尺寸语义对齐：
 *  · Compact  (<600dp)：手机竖屏、分屏一半、折叠外屏；
 *  · Medium   (600–839dp)：手机横屏、小平板竖屏、大折叠内屏；
 *  · Expanded (≥840dp)：平板横屏、电视。
 */
@Composable
fun rememberApAdaptiveLayout(): ApAdaptiveLayout {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp.dp
    val h = cfg.screenHeightDp.dp
    val cls = when {
        cfg.screenWidthDp >= 840 -> ApWidthClass.Expanded
        cfg.screenWidthDp >= 600 -> ApWidthClass.Medium
        else -> ApWidthClass.Compact
    }
    return ApAdaptiveLayout(
        widthClass = cls,
        width = w,
        height = h,
        landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE,
        twoPane = cfg.screenWidthDp >= 600,
        padCutout = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
}
