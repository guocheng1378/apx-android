package com.allperiph.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
    /** 调用方已经用 [apKeyFeel] 接过「按下即震」，这里只出声，避免一次按键震两下 */
    downFeel: Boolean = false,
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
                if (downFeel) Feedback.release(host) else Feedback.tap(host)
                onClick()
            },
        )
        .then(Modifier.keyFx(values, accent))
}

/**
 * 键帽／图块的**按下即震 + 长按确认**（成宇 2026-11-04「长按按键没震动，是否有做用」）。
 *
 * ## 修的是什么
 * 此前键帽（键盘页 `KeyBox` / 修饰键 `ModKey` / 遥控图块 `MediaTile`）的反馈全挂在
 * `clickable` 的 onClick 上 —— 震动发生在**抬手**那一刻：
 *   · 指尖从压下去到抬起来这段时间，一点触觉都没有，手感就是「按了没反应」；
 *   · 长按更是完全没有承载，按住不放再松手，只得到一次普通点击的震动，
 *     用户直接问「长按按键没震动，是否有做用」。
 *
 * ## 补的两段反馈（档位仍只走 [Feedback]，设置页选「关」时一声不震）
 *   1. **按下即震**：`awaitFirstDown` 一成立就 `Feedback.down` —— 真实键盘「一碰就有段落」；
 *   2. **长按确认**：按住超过 [apKeyLongPressMs] 补一次震动，让指尖确知已进入长按。
 *
 * ⚠️ 两处设计约束，改之前先看：
 *   · **只在 Initial pass 观察、从不 consume**：点击仍归 `clickable`，特效仍由同一个
 *     interactionSource 驱动。不能用 `combinedClickable` 替代 —— 那会换掉 interactionSource，
 *     键帽特效（v223.29.4 刚修好）会一起失效；
 *   · **震动不能两头都给**：所以键帽的 onClick 用 [Feedback.release]（只出声），
 *     不是 `tap`（震 + 声）。否则一次按键会震两下（按下 + 抬手），用户只会觉得发麻。
 *     若以后给别的组件接这个 Modifier，必须同步把它 onClick 里的 `Feedback.tap` 改成
 *     `Feedback.release`。
 *
 * ## 长按照例不做额外动作
 * 长按第二下震动**只是确认**，不改发送行为、不重复发键。长按松手时 clickable 照常触发
 * 一次 onClick —— 与真实键盘「按住不放、松开才发」一致。
 */
internal const val apKeyLongPressMs = 400L

@Composable
internal fun Modifier.apKeyFeel(
    /**
     * 按下那一刻是否震。
     *
     * 键帽要（真实键盘一碰就有段落）；触摸板快捷键芯片**不要** —— 它的 onClick 里已经有
     * `ApMotion.tap` 负责抬手那一下，再按下震一次就成了「一次点击震两下」。
     * 关掉之后它仍保留「长按 400ms 确认」这一半，正好对上用户「长按按键没震动」的诉求。
     */
    downFeel: Boolean = true,
    /**
     * 长按 400ms 是否补一次确认震动。
     *
     * 低栏自己已经有一层长按逻辑（650ms 弹快捷入口 + 一次 `Feedback.haptic`），
     * 这里再补一次就变成「按住低栏震两下」，所以低栏传 false。
     */
    longPressFeel: Boolean = true,
): Modifier {
    val host = LocalView.current
    return this.pointerInput(downFeel, longPressFeel) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (downFeel) Feedback.down(host)
            // 长按确认改走「View.postDelayed 墙钟」（真机 2026-11-04 复核）：
            // 旧版在事件循环里包 withTimeoutOrNull 计 400ms，真机 vib 记录显示这条
            // 路径不可靠 —— 抬手 break 与超时取消谁先到不定，出现过「短按抬手后
            // 400ms 补一震、按住 500ms 反而不震」的错乱。改为一枚 400ms 回调：
            // 按下即挂，抬手 / 第二指落下在 finally 里撤销 —— 短按必撤、
            // 静止长按必震（不依赖 move 事件驱动计时）。
            val confirm = Runnable { if (longPressFeel) Feedback.haptic(host) }
            host.postDelayed(confirm, apKeyLongPressMs)
            try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    // 只判「抬手 / 第二指落下」：一旦成立立即撤销确认回调。
                    if (ev.changes.none { it.pressed } || ev.changes.count { it.pressed } > 1) break
                }
            } finally {
                // 手势结束、或 pointerInput 因离开组合被取消，都要撤掉回调，
                // 免得短按在抬手后又多震一下。
                host.removeCallbacks(confirm)
            }
        }
    }
}
