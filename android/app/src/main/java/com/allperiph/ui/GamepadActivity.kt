package com.allperiph.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 全屏游戏手柄（v1.x）：双虚拟摇杆 + 常用按键，经 [GamepadController]
 * 送上 USB HID（Report ID 22）。横屏沉浸，松手自动回中。
 */
class GamepadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onPause() {
        super.onPause()
        GamepadController.releaseAll() // 离开时复位，避免按键卡在按下态
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF14161B.toInt()) }
        setContentView(root)

        // 退出
        val exit = addLabel(root, "✕", dp(44)) {
            it.textSize = 20f
            it.setOnClickListener { finish() }
        }
        (exit.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(16), dp(10), 0, 0)
        }

        // 左摇杆（左下）
        val left = JoystickView(this)
        left.onAxis = { x, y -> GamepadController.setLeftAxis(x, y) }
        root.addView(left, FrameLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(28), 0, 0, dp(24))
        })

        // 右摇杆（右下）
        val right = JoystickView(this)
        right.onAxis = { rx, ry -> GamepadController.setRightAxis(rx, ry) }
        root.addView(right, FrameLayout.LayoutParams(dp(150), dp(150)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(22), dp(22))
        })

        // A/B/X/Y：右摇杆左侧菱形排布（任天堂式）。坐标按 800×360dp 横屏核算，
        // 与右摇杆（end 24..174 / bottom 24..174）无重叠。
        pad(root, "A", GamepadController.BTN_A).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.setMargins(0, 0, dp(180), dp(102))
        }
        pad(root, "B", GamepadController.BTN_B).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.setMargins(0, 0, dp(250), dp(60))
        }
        pad(root, "X", GamepadController.BTN_X).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.setMargins(0, 0, dp(250), dp(144))
        }
        pad(root, "Y", GamepadController.BTN_Y).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.setMargins(0, 0, dp(320), dp(102))
        }

        // 肩键 L1 / R1（顶部两角；L1 右移避开左上角的 ✕）
        pad(root, "L1", GamepadController.BTN_L1, dp(78)).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.setMargins(dp(72), dp(14), 0, 0)
        }
        pad(root, "R1", GamepadController.BTN_R1, dp(78)).also {
            it.gravity = Gravity.TOP or Gravity.END
            it.setMargins(0, dp(14), dp(24), 0)
        }

        // Select / Start（底部中央）
        pad(root, "Sel", GamepadController.BTN_SELECT, dp(64)).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.setMargins(dp(64), 0, 0, dp(18))
        }
        pad(root, "Start", GamepadController.BTN_START, dp(64)).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.setMargins(0, 0, dp(64), dp(18))
        }
    }

    /** 一个圆形按钮，按下 setButton(true)、抬起 setButton(false) */
    private fun pad(root: FrameLayout, label: String, bit: Int, size: Int = dp(58)): FrameLayout.LayoutParams {
        val tv = addLabel(root, label, size)
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> GamepadController.setButton(bit, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> GamepadController.setButton(bit, false)
            }
            true
        }
        return tv.layoutParams as FrameLayout.LayoutParams
    }

    /** 圆形标签按钮 */
    private fun addLabel(root: FrameLayout, label: String, size: Int, configure: (TextView) -> Unit = {}): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = if (label.length > 2) 13f else 17f
            gravity = Gravity.CENTER
            setTextColor(0xFFEAEAEA.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2EFFFFFF)
                setStroke(dp(1), 0x66FFFFFF)
            }
            configure(this)
        }
        root.addView(tv, FrameLayout.LayoutParams(size, size))
        return tv
    }
}
