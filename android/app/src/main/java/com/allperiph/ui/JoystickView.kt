package com.allperiph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 虚拟摇杆（v1.x 游戏手柄）：圆底 + 可拖动的摇杆头。
 *
 * 把触摸偏移线性映射到 [-127, 127]（超出最大行程时锁定在圆周上），
 * 松手回中并回调 (0,0)。轴值经 [GamepadController] 送上 USB HID。
 *
 * issue#169 #17：点住未拖 = 摇杆键（L3/R3）。认领手指落下即报按下；
 * 手指拖出触摸 slop 则视为「拖动操控摇杆」，先松按键再进入轴值模式；
 * 抬手 / 取消兜底复位。两条语义互斥，不会一边拖一边误发按键。
 */
class JoystickView(context: Context) : View(context) {

    /** 轴值回调：参数为 (x, y)，范围 [-127, 127] */
    var onAxis: ((Int, Int) -> Unit)? = null

    /** 摇杆键（L3/R3）回调：true = 按下，false = 抬起（issue#169 #17） */
    var onButton: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    // 触摸 slop：手指位移超过它算「拖动」，否则算「点住」
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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

    // issue#169 #17：认领手指当前是否处于「点住未拖」状态（= L3/R3 按着）
    private var buttonDown = false
    // 认领手指的落点，用于判断是否拖出 slop
    private var downX = 0f
    private var downY = 0f

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
        // 编辑模式（2026-11-12 手柄页重画）：isEnabled=false 时交出触摸，由外层
        // 拖拽面接管挪位手势；不加这行，挪摇杆会同时扳动摇杆轴值。
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 认领第一根落进摇杆的手指；已经有手指在操作时新手指不接管。
                // 落下即按住 L3/R3（issue#169 #17）：没拖之前这根手指就是摇杆键。
                if (trackedId == MotionEvent.INVALID_POINTER_ID) {
                    trackedId = event.getPointerId(event.actionIndex)
                    downX = event.getX(event.actionIndex)
                    downY = event.getY(event.actionIndex)
                    buttonDown = true
                    onButton?.invoke(true)
                    updateKnob(downX, downY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // 只跟认领的那一根：其余手指的移动（按按钮 / 划屏）不影响摇杆
                val idx = event.findPointerIndex(trackedId)
                if (trackedId != MotionEvent.INVALID_POINTER_ID && idx >= 0) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    // 拖出 slop = 转入轴值操控：先松开摇杆键（两种语义互斥）
                    if (buttonDown && (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop)) {
                        buttonDown = false
                        onButton?.invoke(false)
                    }
                    updateKnob(x, y)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 认领的手指抬离：回中；其余手指继续按原状（不接管）
                if (event.getPointerId(event.actionIndex) == trackedId) {
                    endTouch()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endTouch()
            }
        }
        return true
    }

    /**
     * 认领手指抬离 / 取消 / 全部结束的公共出口（issue#169 #17 抽出）：
     * 摇杆键先复位、再轴值回中，两条状态线各自兜底，不会把 L3/R3 卡住。
     */
    private fun endTouch() {
        if (buttonDown) {
            buttonDown = false
            onButton?.invoke(false)
        }
        recenter()
        trackedId = MotionEvent.INVALID_POINTER_ID
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
