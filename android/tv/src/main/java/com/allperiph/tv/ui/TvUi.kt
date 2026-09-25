package com.allperiph.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.TextView

/**
 * TV 界面自适应（v1.2）。
 *
 * 电视上必须同时解决两件事，否则界面不是「小得像邮票」就是「四周被切掉」：
 *  1) **分辨率差三倍**：720p / 1080p / 4K 并存，写死的 dp/sp 在 4K 上不可看；
 *     这里按「相对 1080p 的缩放」统一放大 —— 基准尺寸仍按 1080p 设计，改一处即可。
 *  2) **过扫描（overscan）**：不少电视/机顶盒把四周 3~5% 裁掉，贴边内容会消失；
 *     所以界面统一留 [safeInset] 安全边距，而不是贴边排版。
 *
 * 另外电视是**远距离观看 + 遥控器操作**：字号再额外放大一档，焦点必须一眼可见
 * （[focusBg] 给出「默认/聚焦」两态背景，DPAD 移动时能看出落在了哪一项）。
 */
object TvUi {

    /** 基准设计分辨率（1080p；平板/电视都以横向为准） */
    private const val BASE_W = 1920f
    private const val BASE_H = 1080f

    /** 远距离观看：在分辨率缩放之外，字号再放大这一档 */
    private const val TEXT_BOOST = 1.12f

    /**
     * 相对 1080p 的缩放系数。限定 0.7~2.5：
     * 太小的（如 480p 机顶盒）不缩成看不见，太大的（4K）不撑爆。
     */
    fun scale(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val shortSide = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val s = minOf(longSide / BASE_W, shortSide / BASE_H)
        return s.coerceIn(0.7f, 2.5f)
    }

    /** 基准 dp → 实际像素（含分辨率自适应） */
    fun dp(ctx: Context, base: Float): Int =
        (base * scale(ctx) * ctx.resources.displayMetrics.density).toInt()

    /** 基准 sp → 实际字号（含分辨率自适应 + 远距离放大） */
    fun applyTextSize(tv: TextView, base: Float) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * TEXT_BOOST * scale(tv.context))
    }

    /**
     * 过扫描安全边距：≥24dp，且不小于屏幕短边的 3.5%。
     * 电视边距被裁掉时，界面元素仍完整可见。
     */
    fun safeInset(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        return maxOf(dp(ctx, 24f), (shortSide * 0.035f).toInt())
    }

    /** 可聚焦控件的两态背景：默认色 / 聚焦色（聚焦加白描边 + 提亮，远距离也看得清） */
    fun focusBg(normal: Int, focused: Int, radiusPx: Int, strokePx: Int): Drawable {
        val a = GradientDrawable().apply {
            setColor(normal)
            cornerRadius = radiusPx.toFloat()
        }
        val b = GradientDrawable().apply {
            setColor(focused)
            cornerRadius = radiusPx.toFloat()
            setStroke(strokePx, Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), b)
            addState(intArrayOf(android.R.attr.state_pressed), b)
            addState(intArrayOf(), a)
        }
    }

    /** 纯色圆角背景（不可聚焦的容器/标签用） */
    fun solidBg(color: Int, radiusPx: Int): Drawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

    /** 主题色（与手机端 MIUIX 亮色风格保持同一族，但电视用深底高对比） */
    const val BG = 0xFF0B0F14.toInt()
    const val CARD = 0xFF161B22.toInt()
    const val CARD_FOCUS = 0xFF1F6FEB.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val TEXT_DIM = 0xFF8B949E.toInt()
    const val ACCENT = 0xFF58A6FF.toInt()
    const val OK = 0xFF3FB950.toInt()
    const val WARN = 0xFFD29922.toInt()
}
