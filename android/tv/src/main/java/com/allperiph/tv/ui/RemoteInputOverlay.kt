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
import com.allperiph.tv.core.Log

class RemoteInputOverlay(
    private val activity: Activity,
    private val rootLayout: FrameLayout,
    private val onTextChanged: (String, Int) -> Unit,
    private val onSend: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private var overlay: View? = null
    private var editText: EditText? = null
    private var isShowing = false
    fun show(fromDevice: String, hint: String) {
        if (isShowing) return
        isShowing = true
        val dp = { v: Float -> (v * activity.resources.displayMetrics.density + 0.5f).toInt() }
        val mask = View(activity).apply { setBackgroundColor(Color.parseColor("#99000000")) }
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#FF1A1A2E")); setPadding(dp(24f), dp(16f), dp(24f), dp(16f)) }
        val titleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(activity).apply { text = "\uD83D\uDD35 输入中"; setTextColor(Color.parseColor("#3482FF")); TvUi.applyTextSize(this, 14f) })
        titleRow.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        titleRow.addView(TextView(activity).apply { text = "来自「$fromDevice」"; setTextColor(Color.parseColor("#99FFFFFF")); TvUi.applyTextSize(this, 13f) })
        panel.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        val input = EditText(activity).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#66FFFFFF")); hint = hint.ifEmpty { "输入内容" }
            setBackgroundColor(Color.parseColor("#FF2A2A3E")); setPadding(dp(16f), dp(12f), dp(16f), dp(12f)); textSize = 18f
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
        this.editText = input
        panel.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btnRow.addView(makeButton("取消", Color.parseColor("#FF666666")) { onTextChanged("", 0x08); onCancel(); hide() })
        btnRow.addView(makeButton("发送", Color.parseColor("#3482FF")) { val t = editText?.text?.toString() ?: ""; if (t.isNotEmpty()) onTextChanged(t, 0x04); onSend(); hide() })
        panel.addView(btnRow)
        val container = FrameLayout(activity)
        container.addView(mask, FrameLayout.LayoutParams(-1, -1))
        container.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = dp(48f); rightMargin = dp(48f) })
        overlay = container
        rootLayout.addView(container, FrameLayout.LayoutParams(-1, -1))
        input.requestFocus()
        input.post { val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager; imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun hide() { if (!isShowing) return; isShowing = false; overlay?.let { rootLayout.removeView(it) }; overlay = null; editText = null; val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager; imm.hideSoftInputFromWindow(rootLayout.windowToken, 0) }
    fun isActive(): Boolean = isShowing
    private fun makeButton(text: String, color: Int, onClick: () -> Unit): TextView {
        val dp = { v: Float -> (v * activity.resources.displayMetrics.density + 0.5f).toInt() }
        return TextView(activity).apply { this.text = text; setTextColor(Color.WHITE); TvUi.applyTextSize(this, 14f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(24f), dp(10f), dp(24f), dp(10f)); setBackgroundColor(color); setOnClickListener { onClick() } }
            .also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) } }
    }
}