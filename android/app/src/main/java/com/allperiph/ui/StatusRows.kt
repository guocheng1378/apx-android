package com.allperiph.ui

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.allperiph.R

/**
 * 主界面「状态行」构建器（纯代码生成，避免为每行都写 XML）。
 *
 * 每行：`[标签]  ●  [值]`，值为语义色（ok / warn / error / idle）。
 */
class StatusRows(private val container: LinearLayout) {

    class Row(
        val root: LinearLayout,
        val label: TextView,
        val value: TextView,
        val dot: ImageView,
    ) {
        fun setValue(text: CharSequence, color: Int) {
            value.text = text
            value.setTextColor(color)
            val d: Drawable? = dot.drawable?.mutate()
            d?.setTint(color)
            dot.setImageDrawable(d)
        }
    }

    private val density: Float = container.resources.displayMetrics.density
    private val padV = (6 * density).toInt()
    private val padH = (2 * density).toInt()
    private val dotSize = (10 * density).toInt()

    fun addRow(labelText: String, initial: String = "--"): Row {
        val ctx = container.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(padH, padV, padH, padV)
        }

        val label = TextView(ctx).apply {
            text = labelText
            setTextAppearanceSafely(this, R.style.APTextBody)
            setTextColor(resources.getColor(R.color.md_on_surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dot = ImageView(ctx).apply {
            setImageResource(R.drawable.bg_status_dot)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val value = TextView(ctx).apply {
            text = initial
            setTextAppearanceSafely(this, R.style.APTextBody)
            setTextColor(resources.getColor(R.color.state_idle))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(label)
        row.addView(dot)
        row.addView(value)
        container.addView(row)
        return Row(row, label, value, dot)
    }

    fun clear() = container.removeAllViews()

    /** 语义色：ok=绿 / warn=橙 / error=红 / idle=灰 */
    fun color(ok: Boolean, warn: Boolean = false, error: Boolean = false): Int {
        val res = container.resources
        return when {
            error -> res.getColor(R.color.state_error)
            warn -> res.getColor(R.color.state_warn)
            ok -> res.getColor(R.color.state_ok)
            else -> res.getColor(R.color.state_idle)
        }
    }

    private fun setTextAppearanceSafely(tv: TextView, styleRes: Int) {
        runCatching { tv.setTextAppearance(tv.context, styleRes) }
            .onFailure {
                tv.textSize = 14f
                tv.setTextColor(tv.resources.getColor(R.color.md_on_surface))
            }
    }
}
