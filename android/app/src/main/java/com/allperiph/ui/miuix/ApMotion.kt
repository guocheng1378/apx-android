package com.allperiph.ui.miuix

import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
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
 * 2026-11-09 界面稿「08 质感层」动效收敛：
 *  · 禁过冲、位移上限 6px、元素只缩到 0.98；按下 `scale(.97)` —— 仍然成立；
 *  · 2026-11-14 质感对齐批换轨：进入动画从「进入曲线 tween」改回**临界阻尼弹簧**
 *    （无过冲，物理减速贴位）——miuix/HyperOS 的手感就是弹簧给的，此前禁弹簧的
 *    「不专业」顾虑通过 NoBouncy 解决，不回弹但保留物理速度曲线；退出仍走曲线 tween。
 *  · 时长口径 150 / 180 / 200ms 保留给退出/淡出类动画（弹簧由物理参数自计时）。
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
     * 进入动画规格（2026-11-14 质感对齐批换轨 HyperOS 口径）：临界阻尼弹簧，
     * **无过冲**（DampingRatioNoBouncy）——物理手感（速度先快后缓地贴到位）是
     * miuix/HyperOS 质感的核心来源，但回弹仍然克制：装饰件如需轻回弹另行显式传参。
     * 「关闭动效」档位仍走 0ms tween 瞬移——弹簧无法表达瞬移，开关判断保持原样。
     */
    fun <T> springSpec(
        ctx: Context,
        dampingRatio: Float = Spring.DampingRatioNoBouncy,
        stiffness: Float = Spring.StiffnessMediumLow,
    ): FiniteAnimationSpec<T> =
        if (enabled(ctx)) {
            spring(dampingRatio = dampingRatio, stiffness = stiffness)
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
