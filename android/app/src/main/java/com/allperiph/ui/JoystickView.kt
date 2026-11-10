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

    /**
     * 当前认领摇杆的那根手指（issue#168 第 5 条 多指）。
     *
     * 旧版直接取 `event.x / y` —— 那是「最后一根手指」的坐标，任何第二根手指
     * 落下（哪怕按的是按钮），摇杆头就会跳过去。改为认领 ACTION_DOWN 的那根
     * pointerId，之后只跟这一根；认领的手指抬离才回中。
     */
    private var trackedId = MotionEvent.INVALID_POINTER_ID

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
            MotionEvent.ACTION_DOWN -> {
                // 认领第一根落进摇杆的手指；已经有手指在操作时新手指不接管
                if (trackedId == MotionEvent.INVALID_POINTER_ID) {
                    trackedId = event.getPointerId(event.actionIndex)
                    updateKnob(event.x, event.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // 只跟认领的那一根：其余手指的移动（按按钮 / 划屏）不影响摇杆
                val idx = event.findPointerIndex(trackedId)
                if (trackedId != MotionEvent.INVALID_POINTER_ID && idx >= 0) {
                    updateKnob(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 认领的手指抬离：回中；其余手指继续按原状（不接管）
                if (event.getPointerId(event.actionIndex) == trackedId) {
                    recenter()
                    trackedId = MotionEvent.INVALID_POINTER_ID
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                recenter()
                trackedId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    /** 摇杆回中：视觉与轴值都归零（多指批抽出公共路径，认领手指抬离 / 全部抬离共用） */
    private fun recenter() {
        knobX = cx
        knobY = cy
        invalidate()
        onAxis?.invoke(0, 0)
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
