package com.allperiph.controlled

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.core.ApxFrame
import com.allperiph.core.Log

class RemoteInputActivity : Activity() {
    private var editText: EditText? = null
    /** 程序性改写输入框时置 true：TextWatcher 会把“删字”当成退格发往电脑，
     *  发送后清空输入框绝不能触发它（否则刚发出去的字会被逐个删掉）。 */
    private var suppressInputWatcher = false
    /** 增量发送失败的节流提示（2.5s 一条，别刷屏） */
    private var lastFailHintAt = 0L
    private var sourceDevice: String = ""
    private var hintText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceDevice = intent.getStringExtra(EXTRA_SOURCE) ?: ""
        hintText = intent.getStringExtra(EXTRA_HINT) ?: ""
        setContentView(buildUi())
        editText?.requestFocus()
        editText?.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun buildUi(): View {
        val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A2E"))
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply { text = "\uD83D\uDD35 输入中"; setTextColor(Color.parseColor("#3482FF")); textSize = 14f })
        titleRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        titleRow.addView(TextView(this).apply { text = "来自「$sourceDevice」"; setTextColor(Color.parseColor("#99FFFFFF")); textSize = 13f })
        root.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        val input = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#66FFFFFF"))
            hint = hintText.ifEmpty { "输入内容" }; setBackgroundColor(Color.parseColor("#FF2A2A3E"))
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f)); textSize = 18f; typeface = Typeface.DEFAULT
            isFocusable = true; isFocusableInTouchMode = true; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressInputWatcher) return
                    val text = s?.toString() ?: return
                    if (count > before && start + count <= text.length) {
                        // 逐字增量上屏：这是文字到电脑的**唯一**通道 ——
                        // 发送按钮不再整段重发（COMMIT=整段再粘一遍，两种帧都注入就是
                        // 用户报的“发送后文字重复两次”）。所以失败必须当场提示，不能悄悄丢字。
                        // sendInputText 返回 Unit（不报发送结果），这里只能判断
                        // “被控服务在不在”：不在 = 这一帧根本没发出去，必须提示用户。
                        val ok = ControlledService.server?.let {
                            it.sendInputText(
                                text.substring(start, start + count), ApxFrame.INPUT_FLAG_INCREMENTAL
                            )
                            true
                        } ?: false
                        if (!ok) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastFailHintAt > 2500) {
                                lastFailHintAt = now
                                Toast.makeText(this@RemoteInputActivity,
                                    "没发出去 —— 连接断了？恢复后这段要重新打",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    } else if (count < before) {
                        ControlledService.server?.sendInputText("", ApxFrame.INPUT_FLAG_BACKSPACE)
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        editText = input
        root.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })

        // v184：内嵌触控板 —— 输入的同时直接移动 PC 光标，不用退出输入面板。
        // 手势 → 0x01 鼠标帧经被控控制面上行（与触摸板模块同一链路）：
        //   滑动 = 移动光标（相对位移，分帧限幅 ±127）
        //   轻点 = 左键点击（按下/抬起两帧）
        //   长按 400ms 后拖动 = 左键拖拽
        var lastTouchLogAt = 0L
        fun mouseFrame(buttons: Int, dx: Int, dy: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            val ok = ControlledService.server?.sendControl(
                byteArrayOf(0x01.toByte(), buttons.toByte(), dx.toByte(), dy.toByte(), 0)
            ) ?: false
            // 节流 500ms：现场诊断"触控板到底发没发帧 / 被控链路通不通"
            if (now - lastTouchLogAt > 500) {
                lastTouchLogAt = now
                if (ok) Log.i("RemoteInput", "触控板已发鼠标帧 buttons=$buttons dx=$dx dy=$dy")
                else Log.w("RemoteInput", "触控板发送失败（被控链路未就绪？）")
            }
        }
        var tpDown = false; var tpDrag = false
        var tpLastX = 0f; var tpLastY = 0f
        var tpDownAt = 0L
        val touchpad = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FF2A2A3E"))
            addView(TextView(this@RemoteInputActivity).apply {
                text = "触控板 · 滑动移动光标 / 轻点左键 / 长按拖拽"
                setTextColor(Color.parseColor("#66FFFFFF")); textSize = 12f
                gravity = Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val dragArm = Runnable {
            if (tpDown && !tpDrag) { tpDrag = true; mouseFrame(0x01, 0, 0) }
        }
        touchpad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    tpDown = true; tpDrag = false; tpDownAt = android.os.SystemClock.uptimeMillis()
                    tpLastX = e.x; tpLastY = e.y
                    v.removeCallbacks(dragArm); v.postDelayed(dragArm, 400)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> if (tpDown) {
                    var dx = (e.x - tpLastX).toInt(); var dy = (e.y - tpLastY).toInt()
                    tpLastX = e.x; tpLastY = e.y
                    // 位移限幅 ±127（i8 帧格式），大幅移动分帧发
                    while (dx != 0 || dy != 0) {
                        val sx = dx.coerceIn(-127, 127); val sy = dy.coerceIn(-127, 127)
                        mouseFrame(if (tpDrag) 0x01 else 0x00, sx, sy)
                        dx -= sx; dy -= sy
                    }
                    true
                } else false
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val cancelled = e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    v.removeCallbacks(dragArm)
                    if (tpDown) {
                        val dur = android.os.SystemClock.uptimeMillis() - tpDownAt
                        val moved = kotlin.math.hypot((e.x - tpLastX).toDouble(), (e.y - tpLastY).toDouble()) < dp(12f)
                        if (!tpDrag && !cancelled && moved && dur < 300) {
                            mouseFrame(0x01, 0, 0); mouseFrame(0x00, 0, 0)   // 轻点 = 左键点击
                        } else {
                            mouseFrame(0x00, 0, 0)                            // 抬起/取消 = 松开左键
                        }
                    }
                    tpDown = false; tpDrag = false
                    true
                }
                else -> false
            }
        }
        // 触控板不再插在输入框和编辑键排之间：挪到编辑键排之后、占满剩余高度（见下方 addView），
        // 这样它落在屏幕中部、拇指最好按的位置，键盘弹出时也处在可视区中央。
        // v184：文字编辑快捷键排 —— 退格/方向/跳转/组合键经 0x28 特殊键帧直达 PC
        // （手机输入框只负责新增文本；对 PC 上已有内容的修改全走这里）
        fun editKey(label: String, mod: Int, vk: Int) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setBackgroundColor(Color.parseColor("#FF2A2A3E"))
            isFocusable = true; contentDescription = label
            setOnClickListener { ControlledService.server?.sendSpecialKey(mod, vk) }
        }
        val editKeys = listOf(
            "⌫" to (0 to 0x08), "Del" to (0 to 0x2E),
            "←" to (0 to 0x25), "↑" to (0 to 0x26), "↓" to (0 to 0x28), "→" to (0 to 0x27),
            "Home" to (0 to 0x24), "End" to (0 to 0x23),
            "Tab" to (0 to 0x09), "Enter" to (0 to 0x0D),
            "全选" to (1 to 0x41), "复制" to (1 to 0x43), "粘贴" to (1 to 0x56), "剪切" to (1 to 0x58),
        )
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, mk) in editKeys) keyRow.addView(editKey(label, mk.first, mk.second))
        val keyScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(keyRow)
        }
        root.addView(keyScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f); bottomMargin = dp(8f) })
        // 触控板 = 本页中部的主区域：weight 占满输入区与按钮排之间的剩余空间
        root.addView(touchpad, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(4f); bottomMargin = dp(10f)
            touchpad.minimumHeight = dp(150f)
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btnRow.addView(TextView(this).apply {
            text = "取消"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            setBackgroundColor(Color.parseColor("#FF666666"))
            setOnClickListener { ControlledService.server?.sendInputText("", ApxFrame.INPUT_FLAG_CANCEL); finish() }
        }.also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) } })
        btnRow.addView(TextView(this).apply {
            text = "发送"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            setBackgroundColor(Color.parseColor("#3482FF"))
            // 发送后**留在面板**（清空输入框接着打下一句）；收起整个面板走「取消」或系统返回。
            // 以前发完就 finish()，想连着打就得重新唤起 —— 手机端反馈“一点发送就跳出界面”。
            //
            // 这里**绝不发 COMMIT 整段帧**：打字时 TextWatcher 已把每个字符以 INCREMENTAL
            // 逐字送到电脑并即时上屏；COMMIT 的语义是“整段再粘一遍”，PC 端两种帧都注入，
            // 结果就是用户报的“发送后文字重复两次”。发送只负责收尾：DONE 帧 + 清空继续。
            setOnClickListener {
                val t = editText?.text?.toString() ?: ""
                if (t.isEmpty()) {
                    Toast.makeText(this@RemoteInputActivity, "先输入要发送的内容", Toast.LENGTH_SHORT).show()
                } else {
                    ControlledService.server?.sendInputDone()
                    suppressInputWatcher = true          // 清空动作绝不能被当成退格发给电脑
                    editText?.setText("")
                    suppressInputWatcher = false
                    editText?.requestFocus()             // 焦点回到输入框，键盘保持弹出
                    Toast.makeText(this@RemoteInputActivity, "已发送到电脑", Toast.LENGTH_SHORT).show()
                }
            }
        }.also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) } })
        root.addView(btnRow)
        return root
    }

    override fun onDestroy() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window?.decorView?.windowToken, 0)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE = "source_device"
        const val EXTRA_HINT = "hint"
        fun start(context: Context, sourceDevice: String, hint: String) {
            context.startActivity(Intent(context, RemoteInputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SOURCE, sourceDevice); putExtra(EXTRA_HINT, hint)
            })
        }
    }
}