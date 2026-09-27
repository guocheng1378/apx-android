package com.allperiph.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.TextView
import com.allperiph.tv.R

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

    // ————————————————————————— 色板 —————————————————————————

    /**
     * 色板（v1.33）：**取值全部来自 `res/values/colors.xml`**，此处不再出现 hex。
     *
     * 之所以用「一次性绑定的可变 Pal」而不是每个色都传 Context：TV 的 UI 全是代码自绘，
     * 调用点遍布 View 构造 lambda，逐个传 Context 会把接口污染得很碎。
     * 各 Activity 在 `onCreate` 调一次 [bindColors] 即可；万一某个入口漏调，
     * 下面的默认值（与原常量同值）仍是正确的深色，只是取不到用户改过的资源。
     */
    object Pal {
        // 默认值保持与原常量一致，保证「未 bind 也能正常显示」
        var bg: Int = 0xFF0B0F14.toInt(); private set
        var card: Int = 0xFF161B22.toInt(); private set
        var cardFocus: Int = 0xFF1F6FEB.toInt(); private set
        var field: Int = 0xFF21262D.toInt(); private set
        var text: Int = 0xFFE6EDF3.toInt(); private set
        var textDim: Int = 0xFF8B949E.toInt(); private set
        var accent: Int = 0xFF58A6FF.toInt(); private set
        var ok: Int = 0xFF3FB950.toInt(); private set
        var warn: Int = 0xFFD29922.toInt(); private set
        var error: Int = 0xFFF85149.toInt(); private set
        var mask: Int = 0x99000000.toInt(); private set
        var neutral: Int = 0xFF666666.toInt(); private set

        internal fun bind(ctx: Context) {
            bg = ctx.getColor(R.color.tv_bg)
            card = ctx.getColor(R.color.tv_card)
            cardFocus = ctx.getColor(R.color.tv_card_focus)
            field = ctx.getColor(R.color.tv_field)
            text = ctx.getColor(R.color.tv_text)
            textDim = ctx.getColor(R.color.tv_text_dim)
            accent = ctx.getColor(R.color.tv_accent)
            ok = ctx.getColor(R.color.tv_ok)
            warn = ctx.getColor(R.color.tv_warn)
            error = ctx.getColor(R.color.tv_error)
            mask = ctx.getColor(R.color.tv_mask)
            neutral = ctx.getColor(R.color.tv_neutral)
        }
    }

    /** 由各 Activity 在 `onCreate` 调用一次，把资源色板灌进 [Pal] */
    fun bindColors(ctx: Context) = Pal.bind(ctx)
}
