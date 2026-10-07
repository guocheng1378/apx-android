package com.allperiph.tv.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.allperiph.shared.util.Log

class RemoteInputOverlay(
    private val activity: Activity,
    private val rootLayout: FrameLayout,
    private val onTextChanged: (String, Int) -> Unit,
    private val onSend: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private var overlay: View? = null
    private var mEditText: EditText? = null
    private var isShowing = false

    fun show(fromDevice: String, hint: String) {
        if (isShowing) return
        isShowing = true
        // 本机正在替对端输入：此时本机输入框获焦不能再反向请求对端（否则两边来回弹输入法）
        com.allperiph.shared.accessibility.ApxAccessibilityService.remoteInputMode = true
        // 浮层可能由任意 Activity / Service 拉起，这里补一次色板绑定，确保所有入口都取到资源色
        TvUi.bindColors(activity)
        val dp = { v: Float -> (v * activity.resources.displayMetrics.density + 0.5f).toInt() }
        // 遮罩必须可点击：否则触摸事件会穿透到下层主界面，遥控器焦点也能跑到后面的按钮上
        val mask = View(activity).apply {
            setBackgroundColor(TvUi.Pal.mask)
            isClickable = true
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.Pal.card)
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        val titleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(activity).apply { text = "正在接收打字"; setTextColor(TvUi.Pal.accent); TvUi.applyTextSize(this, TvUi.Type.BODY) })
        titleRow.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        titleRow.addView(TextView(activity).apply { text = "来自「$fromDevice」"; setTextColor(TvUi.Pal.textDim); TvUi.applyTextSize(this, TvUi.Type.CAPTION) })
        panel.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        val input = EditText(activity).apply {
            setTextColor(TvUi.Pal.text); setHintTextColor(TvUi.Pal.textDim); this.hint = hint.ifEmpty { "在这里看到对方的输入" }
            setBackgroundColor(TvUi.Pal.field); setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            // 字号走命名档位（同时也拿到分辨率自适应；原先写 textSize = 18f 在 4K 上偏小）
            TvUi.applyTextSize(this, TvUi.Type.TITLE)
            typeface = Typeface.DEFAULT; isFocusable = true; isFocusableInTouchMode = true; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val text = s?.toString() ?: return
                    if (count > before && start + count <= text.length) onTextChanged(text.substring(start, start + count), 0x01)
                    else if (count < before) onTextChanged("", 0x02)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        mEditText = input
        panel.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btnRow.addView(makeButton("取消", TvUi.Pal.neutral) { undoOnPeer(); onCancel(); hide() })
        // 发送**不再整段重发**（0x04 COMMIT）：打字时上面的 TextWatcher 已把每个字符
        // 以 0x01 增量送到 PC 并即时上屏，再发 0x04 会被 PC 整段再粘一遍 ——
        // 用户报的“发送后文字重复两次”（与手机端 RemoteInputActivity 同一根因）。
        // 发送只负责收尾：DONE 帧 + 收起浮层（整个面板每次 show() 都重建，无残留文本）。
        btnRow.addView(makeButton("发送", TvUi.Pal.accent) { onSend(); hide() })
        panel.addView(btnRow)
        val container = FrameLayout(activity)
        container.addView(mask, FrameLayout.LayoutParams(-1, -1))
        container.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = dp(48f); rightMargin = dp(48f) })
        overlay = container
        rootLayout.addView(container, FrameLayout.LayoutParams(-1, -1))
        input.requestFocus()
        input.post { val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager; imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun hide() {
        com.allperiph.shared.accessibility.ApxAccessibilityService.remoteInputMode = false
        if (!isShowing) return; isShowing = false; overlay?.let { rootLayout.removeView(it) }; overlay = null; mEditText = null; val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(rootLayout.windowToken, 0)
    }
    fun isActive(): Boolean = isShowing

    /**
     * 遥控器按「返回」时的收尾（由 Activity 的 onBackPressed 转过来）。
     *
     * 以前浮层没有接管返回键：电视上按返回会直接 finish 掉 Activity，浮层被连带销毁，
     * [onCancel]（也就是服务端的 `sendInputDone`）**永远发不出去** —— 手机/PC 那边的
     * 输入态一直挂着。这里走与「取消」按钮完全相同的收尾。
     */
    fun cancelFromBack() {
        if (!isShowing) return
        undoOnPeer()
        onCancel()
        hide()
    }

    /**
     * 撤销对端已上屏的字。
     *
     * ★ INPUT_FLAG_CANCEL(0x08) **三端接收侧全是空实现**（常量定义了、TV 与手机也都在发，
     *   但收到后什么都不做）—— 只发一帧 0x08，对端输入框里的字一个都不会消失，用户点
     *   「取消」/按返回键等于白点。这里与 PC 端 remote_input_win32 同口径：按当前净上屏
     *   字数逐个发 0x02（退格）。TextWatcher 已把用户自己的删除也发成 0x02 了，所以
     *   输入框里剩下的长度恰好就是对端还留着的字数。
     */
    private fun undoOnPeer() {
        val n = mEditText?.text?.length ?: 0
        repeat(n) { onTextChanged("", 0x02) }
    }

    /**
     * 取消 / 发送按钮（v1.33）：补齐最小触控尺寸、可聚焦与无障碍描述。
     * 原实现只有 dp(10) 的纵向 padding 且未设 focusable，遥控器 DPAD 走不到这两个按钮。
     */
    private fun makeButton(text: String, color: Int, onClick: () -> Unit): TextView {
        val dp = { v: Float -> (v * activity.resources.displayMetrics.density + 0.5f).toInt() }
        return TextView(activity).apply {
            this.text = text
            setTextColor(TvUi.Pal.text)
            TvUi.applyTextSize(this, TvUi.Type.BODY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            minimumWidth = TvUi.dp(activity, 64f)
            minimumHeight = TvUi.dp(activity, 48f)
            isFocusable = true
            contentDescription = text
            background = TvUi.focusBg(color, TvUi.Pal.cardFocus, TvUi.dp(activity, TvUi.Radius.CONTROL), dp(2f))
            setOnClickListener { onClick() }
        }
            .also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) } }
    }
}