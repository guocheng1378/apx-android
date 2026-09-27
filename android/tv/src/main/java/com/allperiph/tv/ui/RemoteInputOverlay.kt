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
import com.allperiph.tv.core.Log

/**
 * 远程输入覆盖层（docs/REMOTE-INPUT.md）。
 *
 * 收到 REQUEST_INPUT 时叠加在主界面上层，显示：
 * - 来源设备名 + hint
 * - EditText（自动获焦，系统键盘弹出）
 * - 发送 / 取消按钮
 * - 半透明遮罩
 *
 * 用户打字时，TextWatcher 实时回调 [onTextChanged]，
 * 调用方负责将文本通过 TCP 发回请求方。
 */
class RemoteInputOverlay(
    private val activity: Activity,
    private val rootLayout: FrameLayout,
    private val onTextChanged: (String, Int) -> Unit,  // (text, flags)
    private val onSend: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private var overlay: View? = null
    private var editText: EditText? = null
    private var isShowing = false

    /**
     * 显示远程输入界面。
     * @param fromDevice 来源设备名
     * @param hint 输入框提示文字
     */
    fun show(fromDevice: String, hint: String) {
        if (isShowing) return
        isShowing = true

        val dp = { v: Float -> (v * activity.resources.displayMetrics.density + 0.5f).toInt() }

        // 半透明遮罩
        val mask = View(activity).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
        }

        // 输入面板
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A2E"))
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }

        // 标题行：来源设备名
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = "🔵 输入中"
            setTextColor(Color.parseColor("#3482FF"))
            TvUi.applyTextSize(this, 14f)
        })
        titleRow.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        titleRow.addView(TextView(activity).apply {
            text = "来自「$fromDevice」"
            setTextColor(Color.parseColor("#99FFFFFF"))
            TvUi.applyTextSize(this, 13f)
        })
        panel.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8f)
        })

        // EditText
        val input = EditText(activity).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            this.hint = hint.ifEmpty { "输入内容" }
            setBackgroundColor(Color.parseColor("#FF2A2A3E"))
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            textSize = 18f
            typeface = Typeface.DEFAULT
            isFocusable = true
            isFocusableInTouchMode = true
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                private var lastLen = 0
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    lastLen = count
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val text = s?.toString() ?: ""
                    if (count > before) {
                        // 新增字符 → 增量
                        val newText = text.substring(start, start + count)
                        onTextChanged(newText, 0x01)  // INCREMENTAL
                    } else if (count < before) {
                        // 删除字符 → 退格
                        onTextChanged("", 0x02)  // BACKSPACE
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        editText = input
        panel.addView(input, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12f)
        })

        // 按钮行
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(makeButton("取消", Color.parseColor("#FF666666")) {
            onTextChanged("", 0x08)  // CANCEL
            onCancel()
            hide()
        })
        btnRow.addView(makeButton("发送", Color.parseColor("#3482FF")) {
            val text = editText?.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                onTextChanged(text, 0x04)  // COMMIT
            }
            onSend()
            hide()
        })
        panel.addView(btnRow)

        // 组装覆盖层
        val container = FrameLayout(activity)
        container.addView(mask, FrameLayout.LayoutParams(-1, -1))
        container.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            leftMargin = dp(48f)
            rightMargin = dp(48f)
        })

        overlay = container
        rootLayout.addView(container, FrameLayout.LayoutParams(-1, -1))

        // 自动弹出键盘
        input.requestFocus()
        input.post {
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        overlay?.let { rootLayout.removeView(it) }
        overlay = null
        editText = null
        // 收起键盘
        val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootLayout.windowToken, 0)
    }

    fun isActive(): Boolean = isShowing

    private fun makeButton(text: String, color: Int, onClick: () -> Unit): TextView {
        val dp = { v: Float -> (v * activity.resources.displayMetrics.density + 0.5f).toInt() }
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            TvUi.applyTextSize(this, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            setBackgroundColor(color)
            setOnClickListener { onClick() }
        }.also {
            val lp = LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(12f)
            }
            it.layoutParams = lp
        }
    }

    companion object {
        private const val TAG = "RemoteInputOverlay"
    }
}