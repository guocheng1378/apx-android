package com.allperiph.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.allperiph.core.Uplink
import com.allperiph.core.UplinkEvent
import com.allperiph.shared.event.EventBus

/**
 * 全屏游戏手柄（v1.x）：双虚拟摇杆 + 常用按键，经 [GamepadController]
 * 送上 USB HID（Report ID 22）／ 蓝牙 HID（rid=4）／ 无线 0x07。
 *
 * v223.6 起补两件此前缺的东西 —— 用户报「手柄功能实测不可用」，而按下去
 * 手机端没反馈、PC 端没动静、界面也不解释，只能得出「坏了」：
 *   1) **出口状态行 + 指引**：实时显示这帧会发去哪（[Uplink] 的唯一判据），
 *      无出口时直接写清怎么给它一个出口；无线出口额外说明对端平台差异
 *      （见 [GamepadExit] 的注释：macOS 注入不了真手柄是 Apple 的权限限制）。
 *   2) **按下反馈**：按钮此前是个没有按下态的 TextView，按下去外观不变。
 *      现在按下换底色/描边，手指不动也能确认「触摸被识别了」。
 */
class GamepadActivity : Activity() {

    /**
     * 主题方案（issue#44）：这些页面此前**没接** ThemePref，用户选「深色」也只按系统走 ——
     * 于是深色系统下页面还是亮色底、亮色字，或者反过来，看着"字体取色异常"。
     * 与 ApShellActivity / TouchpadActivity / RemoteActivity 同一套实现。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.allperiph.ui.ThemePref.wrap(newBase))
    }

    /** 出口状态行（每 500ms 兜底刷新一次；出口变化不一定会发输入）。 */
    private val ui = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 500L)
        }
    }

    private var statusView: TextView? = null
    private var adviceView: TextView? = null
    private var uplinkSub: EventBus.Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // 出口变化（蓝牙配对成功 / USB 挂上 / 无线目标连上）多数不发输入，
        // 所以除了事件订阅，再加一个低频轮询兜底。
        uplinkSub?.dispose()
        uplinkSub = EventBus.onMain<UplinkEvent> { refreshStatus() }
        ui.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refreshTask)
        uplinkSub?.dispose()
        uplinkSub = null
        GamepadController.releaseAll() // 离开时复位，避免按键卡在按下态
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 状态行 + 指引行：按当前出口刷新（发过输入看真实出口，否则看预计出口）。 */
    private fun refreshStatus() {
        val s = statusView ?: return
        val a = adviceView ?: return
        val path = if (Uplink.observed) Uplink.current else GamepadController.predictedPath()
        val info = GamepadExit.describe(path)
        s.text = info.headline
        // 无出口 / 只有降级映射时用告警色，明确「现在按下去没用」或「要看对端」
        s.setTextColor(if (info.driverlessRealPad) 0xFF8BD450.toInt() else 0xFFFFB44C.toInt())
        a.text = info.advice
        a.visibility = if (info.advice.isEmpty()) View.GONE else View.VISIBLE
    }

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

        // 出口状态 + 指引（顶部居中；左右各让 100dp 避开 L1 / R1，也避开左上角 ✕）
        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFB44C.toInt())
        }
        val advice = TextView(this).apply {
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(0xFFB9BDC7.toInt())
            setPadding(0, dp(3), 0, 0)
            visibility = View.GONE
        }
        statusView = status
        adviceView = advice
        bar.addView(status)
        bar.addView(advice)
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setMargins(dp(100), dp(12), dp(100), 0)
        })

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

    /** 一个圆形按钮，按下 setButton(true)、抬起 setButton(false)；按下时换底色作反馈。 */
    private fun pad(root: FrameLayout, label: String, bit: Int, size: Int = dp(58)): FrameLayout.LayoutParams {
        val tv = addLabel(root, label, size)
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    applyPadFace(tv, pressed = true)
                    GamepadController.setButton(bit, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    applyPadFace(tv, pressed = false)
                    GamepadController.setButton(bit, false)
                }
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
            applyPadFace(this, pressed = false)
            configure(this)
        }
        root.addView(tv, FrameLayout.LayoutParams(size, size))
        return tv
    }

    /**
     * 按钮外观（未按下 / 按下两态）。
     *
     * 此前按钮是个**没有按下态**的 TextView —— 按下去外观完全不变，用户无法判断
     * 到底是「没识别到触摸」还是「识别了但没发出去」，这正是「手柄不可用」体感的来源之一。
     */
    private fun applyPadFace(tv: TextView, pressed: Boolean) {
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (pressed) 0x66FFFFFF.toInt() else 0x2EFFFFFF.toInt())
            setStroke(dp(if (pressed) 2 else 1), if (pressed) 0xDDFFFFFF.toInt() else 0x66FFFFFF.toInt())
        }
    }
}
