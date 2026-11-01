package com.allperiph.ui.miuix

import android.content.Context
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import com.allperiph.ui.Feedback
import com.allperiph.ui.ApTiming
/**
 * 动效与触感的**统一开关点**（迁移后所有 Compose 动画/震动都必须经过这里）。
 *
 * 口径（沿用 10-28 补丁里仍然成立的那条需求）：
 *  · 设置页「动效」档位为「关闭」时，动画不做过渡 —— 直接落到终态（tween 0ms），
 *    不是把弹簧调得很快（那仍会看到一帧位移）；
 *  · 「震动」档位为「关」时完全不震 —— 由 [Feedback.haptic] 内部判档，这里不再重复判断，
 *    避免出现"两处门禁不一致"的历史问题。
 *
 * 之所以不直接用 `animateDpAsState` 的默认 spring：默认弹簧在"关闭动效"时无法表达
 * "瞬移"，必须显式换成 0ms tween。
 */
object ApMotion {

    /** 动效是否开启（「关闭」档位 = 0） */
    fun enabled(ctx: Context): Boolean = ApTiming.motionIndex(ctx) > 0

    /** 弹簧参数：与 Miuix OS4 观感同档（中低刚度、轻微回弹） */
    fun <T> springSpec(
        ctx: Context,
        dampingRatio: Float = Spring.DampingRatioMediumBouncy,
        stiffness: Float = Spring.StiffnessMediumLow,
    ): FiniteAnimationSpec<T> =
        if (enabled(ctx)) {
            androidx.compose.animation.core.spring<T>(dampingRatio = dampingRatio, stiffness = stiffness)
        } else {
            tween<T>(durationMillis = 0)
        }

    /** 按倍率折算的时长（关闭 = 0ms） */
    fun durationMs(ctx: Context, base: Long): Int = ApTiming.motionMs(ctx, base).toInt()

    /**
     * 按下反馈：震动 + 可选缩放目标的瞬时值。
     * 返回目标缩放（关闭动效时恒为 1f，由调用方直接落位）。
     */
    fun pressScale(ctx: Context): Float = if (enabled(ctx)) 0.96f else 1f

    /** 一次轻反馈（尊重设置页震动档位） */
    fun tap(ctx: Context) = Feedback.haptic(ctx)

    /** 尺寸动画的默认目标：关闭动效时零位移 */
    fun animatedSize(ctx: Context, target: Dp, idle: Dp): Dp = if (enabled(ctx)) target else idle
}
