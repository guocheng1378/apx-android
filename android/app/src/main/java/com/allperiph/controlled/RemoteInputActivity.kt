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
                    val text = s?.toString() ?: return
                    if (count > before && start + count <= text.length) {
                        ControlledService.server?.sendInputText(text.substring(start, start + count), ApxFrame.INPUT_FLAG_INCREMENTAL)
                    } else if (count < before) {
                        ControlledService.server?.sendInputText("", ApxFrame.INPUT_FLAG_BACKSPACE)
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        editText = input
        root.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
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
            setOnClickListener {
                val t = editText?.text?.toString() ?: ""
                if (t.isNotEmpty()) ControlledService.server?.sendInputText(t, ApxFrame.INPUT_FLAG_COMMIT)
                ControlledService.server?.sendInputDone(); finish()
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