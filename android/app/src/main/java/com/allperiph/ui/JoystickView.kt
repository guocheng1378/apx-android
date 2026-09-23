package com.allperiph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * 虚拟摇杆（v1.x 游戏手柄）：圆底 + 可拖动的摇杆头。
 *
 * 把触摸偏移线性映射到 [-127, 127]（超出最大行程时锁定在圆周上），
 * 松手回中并回调 (0,0)。轴值经 [GamepadController] 送上 USB HID。
 */
class JoystickView(context: Context) : View(context) {

    /** 轴值回调：参数为 (x, y)，范围 [-127, 127] */
    var onAxis: ((Int, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        style = Paint.Style.FILL
    }
    private val baseStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var knobX = 0f
    private var knobY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = minOf(w, h) / 2f - 8f * density
        knobX = cx
        knobY = cy
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, baseStroke)
        canvas.drawCircle(knobX, knobY, radius * 0.42f, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> updateKnob(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = cx
                knobY = cy
                invalidate()
                onAxis?.invoke(0, 0)
            }
        }
        return true
    }

    private fun updateKnob(x: Float, y: Float) {
        var dx = x - cx
        var dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        val maxR = radius * 0.58f
        if (dist > maxR && dist > 0f) {
            dx = dx / dist * maxR
            dy = dy / dist * maxR
        }
        knobX = cx + dx
        knobY = cy + dy
        invalidate()
        onAxis?.invoke(((dx / maxR) * 127f).toInt(), ((dy / maxR) * 127f).toInt())
    }
}
