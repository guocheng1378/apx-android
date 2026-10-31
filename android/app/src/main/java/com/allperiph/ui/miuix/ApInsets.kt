package com.allperiph.ui.miuix

import android.view.View
import android.view.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一条边上的四向留白（dp）。用**框架** WindowInsets 算出，不引 androidx.core。
 *
 * 迁移口径（2026-10-28 三条补丁里唯一仍然成立的那条）：edge-to-edge 后用量身留白，
 * 而不是依赖系统自动垫边 —— targetSdk 35 起 `windowOptOutEdgeToEdgeEnforcement` 不再生效。
 */
data class ApEdgeInsets(
    val left: Dp = 0.dp,
    val top: Dp = 0.dp,
    val right: Dp = 0.dp,
    val bottom: Dp = 0.dp,
) {
    val horizontal: Dp get() = maxOf(left, right)
    val vertical: Dp get() = maxOf(top, bottom)
    operator fun plus(other: ApEdgeInsets) = ApEdgeInsets(
        maxOf(left, other.left),
        maxOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )
}

/** 三类 inset 的合并结果：系统栏 + 刘海/挖孔 + 输入法。 */
data class ApInsets(
    val bars: ApEdgeInsets = ApEdgeInsets(),
    val cutout: ApEdgeInsets = ApEdgeInsets(),
    val imeBottom: Dp = 0.dp,
) {
    /** 内容安全区：系统栏与刘海取较大者 */
    val top: Dp get() = maxOf(bars.top, cutout.top)
    val bottom: Dp get() = bars.bottom
    val left: Dp get() = maxOf(bars.left, cutout.left)
    val right: Dp get() = maxOf(bars.right, cutout.right)

    /** 输入法抬起时的底部留白（键盘页用；无 IME 时等于系统栏） */
    val bottomWithIme: Dp get() = maxOf(bars.bottom, imeBottom)
}

/**
 * 读当前窗口的 framework insets 并转成 Compose 状态。
 *
 * 实现要点：
 *  · 监听器挂在 [LocalView] 上，**返回值原样透传** —— 不消费 insets，
 *    否则同一棵树里的子 View（如 ComposeView 之外的遗留控件）就收不到；
 *  · 只在 insets 真的变化时更新状态，避免每帧重组；
 *  · 依赖只到 `android.view.WindowInsets`（API 30+，本工程 minSdk 34，无需兼容分支）。
 */
@Composable
fun rememberApInsets(): ApInsets {
    val view = LocalView.current
    var state by remember { mutableStateOf(ApInsets()) }

    DisposableEffect(view) {
        val listener = View.OnApplyWindowInsetsListener { v, insets ->
            val density = v.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
            fun pxToDp(px: Int): Dp = (px / density).dp

            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val cut = insets.getInsets(WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())

            val next = ApInsets(
                bars = ApEdgeInsets(
                    pxToDp(bars.left), pxToDp(bars.top), pxToDp(bars.right), pxToDp(bars.bottom),
                ),
                cutout = ApEdgeInsets(
                    pxToDp(cut.left), pxToDp(cut.top), pxToDp(cut.right), pxToDp(cut.bottom),
                ),
                imeBottom = pxToDp(ime.bottom),
            )
            if (next != state) state = next
            insets
        }
        view.setOnApplyWindowInsetsListener(listener)
        view.requestApplyInsets()
        onDispose { view.setOnApplyWindowInsetsListener(null) }
    }
    return state
}
