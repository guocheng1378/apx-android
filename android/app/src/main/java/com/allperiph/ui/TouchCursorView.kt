package com.allperiph.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * 触控板液态光标（v1.26）。
 *
 * 手势面上跟随手指的"液滴"，只做视觉反馈（事件仍由 MainActivity 喂给 TouchpadModule）：
 *
 * · **单指**：按下内点放大 + 一圈涟漪，移动带轻微拖尾，抬起淡出扩散；
 * · **多指**：液滴放成大号，并在外面**持续向外扩散波纹**（两圈相位错开循环），
 *   第二指落下的瞬间还会从那一指的位置补一圈涟漪 ——
 *   早期版本多指期间只有一圈很淡的静态细环，看起来"没特效"。
 */
class TouchCursorView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var targetX = 0f
    private var targetY = 0f
    private var curX = 0f
    private var curY = 0f
    private var pressed = false
    private var visible = false

    /** 按下能量的 0→1→0 曲线，驱动内点缩放与涟漪扩散 */
    private var energy = 0f
    private var animator: ValueAnimator? = null

    /** 涟漪：按下 / 第二指落下时扩散一次 */
    private var ripple = -1f
    private var rippleX = 0f
    private var rippleY = 0f

    /** 手指数（1 = 单指拖拽，≥2 = 滚动/多指） */
    private var pointers = 1

    /** 多指：持续外扩的波纹相位（0→1 循环，两圈错开 0.5 同时画） */
    private var wavePhase = 0f
    private var waveAnimator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        curX = w / 2f
        curY = h / 2f
        targetX = curX
        targetY = curY
    }

    /**
     * 由 MainActivity 转发原始事件（坐标由本 View 自己换算，多指时还要取第二指落点）。
     *
     * @param ox 手势面左上角在窗口中的 x（用于把窗口坐标换算到本 View 坐标）
     * @param oy 同上，y
     */
    fun onGesture(ev: MotionEvent, ox: Float, oy: Float) {
        val x = ev.x - ox
        val y = ev.y - oy
        targetX = x
        targetY = y
        pointers = ev.pointerCount

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                curX = x
                curY = y
                visible = true
                pressed = true
                rippleX = x
                rippleY = y
                startAnim(0.35f, 1f)
                startRipple()
            }
            // 第二指落下：从那一指的位置扩散一圈，表示"进入多指滚动"
            MotionEvent.ACTION_POINTER_DOWN -> {
                pressed = true
                if (pointers > 1) {
                    rippleX = ev.getX(1) - ox
                    rippleY = ev.getY(1) - oy
                } else {
                    rippleX = x
                    rippleY = y
                }
                startRipple()
                startAnim(energy, 1f)
            }
            MotionEvent.ACTION_MOVE -> pressed = true
            // 抬起其中一指（ACTION_POINTER_UP 仍带 pointerCount>1）：不淡出
            MotionEvent.ACTION_POINTER_UP -> pressed = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                startAnim(energy, 0f)
            }
        }

        // 多指期间：液滴放大 + 持续向外扩散波纹；回到单指就收起
        if (pointers > 1) startWave() else stopWave()

        // 液滴跟随：每次事件插值靠近目标，形成轻微拖尾
        curX += (targetX - curX) * 0.45f
        curY += (targetY - curY) * 0.45f
        invalidate()
    }

    private fun startAnim(from: Float, to: Float) {
        animator?.cancel()
        val dur = ThemeSkin.motionMs(context, if (to > from) 220L else 320L)
        if (dur <= 0L) { // 动效关闭：直接到位，不做插值
            energy = to
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                energy = it.animatedValue as Float
                if (energy <= 0.01f) visible = pressed
                invalidate()
            }
            start()
        }
    }

    private fun startRipple() {
        val dur = ThemeSkin.motionMs(context, 520L)
        if (dur <= 0L) { // 动效关闭：不铺涟漪
            ripple = -1f
            return
        }
        ripple = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                ripple = it.animatedValue as Float
                if (ripple >= 1f) ripple = -1f
                invalidate()
            }
            start()
        }
    }

    /** 多指波纹：0→1 线性循环，配合两圈相位错开形成连续外扩 */
    private fun startWave() {
        if (waveAnimator?.isRunning == true) return
        val dur = ThemeSkin.motionMs(context, 700L)
        if (dur <= 0L) return // 动效关闭：多指不再循环扩散
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                wavePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopWave() {
        if (waveAnimator == null) return
        waveAnimator?.cancel()
        waveAnimator = null
        wavePhase = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible && ripple < 0f) return
        // 触控板配色：设置页可单独选一套皮肤（不选 = 跟随主题）
        val accent = ThemeSkin.current(context, ThemeSkin.TOUCHPAD).cursor
        val multi = pointers > 1
        // 多指时液滴本身也放大一圈，一眼能看出"现在不是单指"
        val coreR = (7f + 7f * energy) * density * (if (multi) 1.25f else 1f)
        val haloCoeff = 0.10f + 0.18f * energy

        // 外圈柔光
        glowPaint.color = (accent and 0x00FFFFFF) or (((haloCoeff * 255).toInt() and 0xFF) shl 24)
        canvas.drawCircle(curX, curY, coreR * 2.4f, glowPaint)

        // 多指：两圈相位错开的波纹，持续从液滴向外铺开
        if (multi) {
            drawWave(canvas, accent, coreR, wavePhase)
            drawWave(canvas, accent, coreR, (wavePhase + 0.5f) % 1f)
        }

        // 单指按下 / 第二指落下的那一下涟漪
        if (ripple >= 0f) {
            ringPaint.color = (accent and 0x00FFFFFF) or (((255 * (1f - ripple) * 0.6f).toInt() and 0xFF) shl 24)
            ringPaint.strokeWidth = 1.6f * density
            canvas.drawCircle(rippleX, rippleY, coreR + ripple * 46f * density, ringPaint)
        }

        // 液滴本体
        corePaint.color = accent
        canvas.drawCircle(curX, curY, coreR, corePaint)

        // 高光点（让液滴看起来是"液态"而非纯色圆）
        canvas.drawCircle(
            curX - coreR * 0.32f, curY - coreR * 0.32f, coreR * 0.30f,
            glowPaint.apply { color = 0x66FFFFFF }
        )
    }

    /** 一圈外扩波纹：半径随相位增大、亮度与线宽递减（液态被推开的波前） */
    private fun drawWave(canvas: Canvas, accent: Int, coreR: Float, p: Float) {
        val radius = coreR * 1.5f + p * 74f * density
        val fade = 1f - p
        ringPaint.color = (accent and 0x00FFFFFF) or (((0.55f * fade * 255).toInt() and 0xFF) shl 24)
        ringPaint.strokeWidth = (3.2f * fade).coerceAtLeast(1f) * density
        canvas.drawCircle(curX, curY, radius, ringPaint)
    }
}
