package com.allperiph.ui.miuix

import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
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
 * 2026-11-09 界面稿「08 质感层」动效收敛：
 *  · **禁弹簧、禁过冲**（进入只减速、退出只加速，位移上限 6px、元素只缩到 0.98）；
 *  · 两档曲线：进入 cubic-bezier(.05,.7,.1,1)、退出 cubic-bezier(.3,0,.8,.15)；
 *  · 时长只用 150 / 180 / 200ms 三档（调用方 base 也只能选这三档）。
 *  旧 `springSpec` 的弹簧口径与本稿冲突，已改为同签名的进入曲线 tween ——
 *  调用方不需要改，回弹消失。
 *
 * 之所以不直接用 `animateDpAsState` 的默认 spring：默认弹簧在"关闭动效"时无法表达
 * "瞬移"，必须显式换成 0ms tween。
 */
object ApMotion {

    /** 进入曲线：只减速（cubic-bezier(.05,.7,.1,1)） */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 退出曲线：只加速（cubic-bezier(.3,0,.8,.15)） */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 动效是否开启（「关闭」档位 = 0） */
    fun enabled(ctx: Context): Boolean = ApTiming.motionIndex(ctx) > 0

    /**
     * 进入动画规格：**不再回弹**（质层稿禁弹簧），统一走进入曲线 + 180ms 基础档。
     * 保留 dampingRatio / stiffness 形参只为不破坏既有调用点，实际不再参与计算。
     */
    fun <T> springSpec(
        ctx: Context,
        dampingRatio: Float = Spring.DampingRatioMediumBouncy,
        stiffness: Float = Spring.StiffnessMediumLow,
    ): FiniteAnimationSpec<T> =
        if (enabled(ctx)) {
            tween(durationMillis = durationMs(ctx, 180), easing = EnterEasing)
        } else {
            tween(durationMillis = 0)
        }

    /** 退出动画规格（同名曲线里的“退出”那一档，位移/淡出用它，150ms） */
    fun <T> exitSpec(ctx: Context, base: Long = 150): FiniteAnimationSpec<T> =
        if (enabled(ctx)) {
            tween(durationMillis = durationMs(ctx, base), easing = ExitEasing)
        } else {
            tween(durationMillis = 0)
        }

    /** 按倍率折算的时长（关闭 = 0ms） */
    fun durationMs(ctx: Context, base: Long): Int = ApTiming.motionMs(ctx, base).toInt()

    /**
     * 按下反馈：震动 + 可选缩放目标的瞬时值。
     * 返回目标缩放（关闭动效时恒为 1f，由调用方直接落位）。
     * 质感层口径：按下 scale(.97)。
     */
    fun pressScale(ctx: Context): Float = if (enabled(ctx)) 0.97f else 1f

    /** 一次轻反馈（尊重设置页震动档位） */
    fun tap(ctx: Context) = Feedback.haptic(ctx)

    /** 尺寸动画的默认目标：关闭动效时零位移 */
    fun animatedSize(ctx: Context, target: Dp, idle: Dp): Dp = if (enabled(ctx)) target else idle
}
