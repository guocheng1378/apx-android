package com.allperiph.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import com.allperiph.ui.Feedback

/**
 * 通用按压手感（成宇 2026-11-04「键盘一共有三处，触摸板也好几处，你确定都有特效和震动了，做成主题最好」）。
 *
 * ## 补的是什么
 * 键帽（`KeyBox` / `ModKey`）上一轮已经接上「特效 + 震动」，但**同一页里的其它可点面**
 * 还是各写一套：
 *   · 键盘页的页签 `Pill` —— 只有 `clickable { tab = i }`，既不震也不画；
 *   · 键盘页的媒体 \/ 电源图块 `MediaTile` —— 只接了 `Feedback.tap`，没有特效；
 *   · 触摸板页空列表那枚「＋」—— 只有 `clickable`；
 *   · 各页的 `ActionButton` \/ `NarrowButton` —— Miuix `TextButton` 直通，全无反馈。
 * 用户的诉求是「同一套手感铺满整页」，不是「键帽有、旁边没有」。
 *
 * ## 为什么收口成一个 Modifier
 * 特效档位是**全局偏好**（`KeyPref.effectIndex`，设置页「主题与外观 → 动效」），
 * 震动档位同理（[com.allperiph.ui.ApFeel.hapticIndex]）。散着写必然出现
 * 「这处读缓存、那处读偏好」的分叉，也会出现「选了涟漪却只看到水波纹」的历史问题。
 * 这里只做一件事：把 `clickable` 的按压进度交给 [rememberKeyFx]，再把
 * [Feedback.tap]（内部判震动档位、也管音效）接在同一次点击上。
 *
 * ## 用法
 * ```
 * Box(Modifier.apPress(fx, accent) { tab = i }) { ... }
 * ```
 * `fx` 一律由调用方从偏好读好后传下来（一页只读一次，几十个格子不各读一遍）。
 *
 * ⚠️ 不要和 Miuix `TextButton` 内部自带的 clickable 叠用：两个 clickable 会各收一次
 * 按下、各发一次震动。给 `TextButton` 用请走 `ApComponents` 里的封装。
 */
@Composable
internal fun Modifier.apPress(
    fx: Int,
    accent: Color,
    onClick: () -> Unit,
): Modifier {
    val host = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val values = rememberKeyFx(pressed, fx)
    return this
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                Feedback.tap(host)
                onClick()
            },
        )
        .then(Modifier.keyFx(values, accent))
}
