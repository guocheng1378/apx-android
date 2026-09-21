package com.allperiph.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

/**
 * 玻璃扩散涟漪（v1.26）。
 *
 * 按下时从**落点**向外铺开的液态波前，四层叠出"玻璃被压开"的层次：
 *
 *   ① 中心柔光 —— 亮区，随扩散淡出
 *   ② 前缘亮环 —— 主波前，最亮、最粗
 *   ③ 滞后环   —— 相位落后 0.22，形成拖尾（只有一圈主环会显得像"描边动画"）
 *   ④ 中心高光点 —— 液滴感
 *
 * 自管动画：调用 [play] 即可；结束时回调 [onFinish]，调用方据此清除背景。
 */
class GlassRipple(
    private val cx: Float,
    private val cy: Float,
    private val shine: Int,
    private val edge: Int,
    private val maxRadius: Float,
    private val durationMs: Long = 620L,
) : Drawable() {

    var progress = 0f
        private set

    /** 扩散结束（用于清除背景，避免残留一层已透明的 drawable） */
    var onFinish: (() -> Unit)? = null

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var animator: ValueAnimator? = null

    fun play() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinish?.invoke()
                }
            })
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (progress <= 0f) return
        val p = progress.coerceIn(0f, 1f)
        val fade = 1f - p
        val r = maxRadius * p

        // ① 中心柔光
        glowPaint.color = withAlpha(edge, 0.22f * fade)
        canvas.drawCircle(cx, cy, r * 0.94f, glowPaint)

        // ③ 滞后环：跟在主环后面一点的拖尾
        val p2 = (p - 0.22f).coerceAtLeast(0f)
        if (p2 > 0f) {
            val r2 = maxRadius * p2
            val f2 = 1f - p2
            ringPaint.strokeWidth = (maxRadius * 0.05f * f2).coerceAtLeast(1f)
            ringPaint.color = withAlpha(shine, 0.32f * f2)
            canvas.drawCircle(cx, cy, r2, ringPaint)
        }

        // ② 前缘亮环（主波前）
        ringPaint.strokeWidth = (maxRadius * 0.09f * fade).coerceAtLeast(1f)
        ringPaint.color = withAlpha(shine, 0.9f * fade)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // ④ 中心高光点：扩散初期最亮，随后散掉
        corePaint.color = withAlpha(shine, 0.5f * fade * fade)
        canvas.drawCircle(cx, cy, maxRadius * 0.12f * fade, corePaint)
    }

    private fun withAlpha(color: Int, f: Float): Int =
        (color and 0x00FFFFFF) or (((f.coerceIn(0f, 1f) * 255).toInt() and 0xFF) shl 24)

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
