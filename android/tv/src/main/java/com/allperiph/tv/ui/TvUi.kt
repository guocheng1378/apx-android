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

    // ————————————————————————— 字号 / 圆角档位 —————————————————————————

    /**
     * 字号档位（三端统一视觉语言）。
     *
     * 取值与手机端 `APText*` 系列同比例（20 / 15 / 14 / 12），只是 TV 要坐远看，
     * 每一档再乘 [TEXT_BOOST]。以前各 Activity 直接写 `17f` / `15f` / `13f` / `12f`，
     * 同一层级在不同页面会差一两号 —— 现在只认这里的名字。
     */
    object Type {
        /** 屏幕主标题 / 最需要一眼看到的那句话 */
        const val DISPLAY = 24f
        /** 小节标题 / 主要状态 */
        const val TITLE = 17f
        /** 卡片按钮上的动作文字 */
        const val ACTION = 16f
        /** 正文 */
        const val BODY = 15f
        /** 次要说明 */
        const val CAPTION = 13f
        /** 列表项 / 脚注 */
        const val MICRO = 12f
    }

    /** 圆角档位（与手机 18dp、PC 18px 保持一致）。 */
    object Radius {
        /** 卡片 / 面板 */
        const val CARD = 18f
        /** 卡片内部的小控件 */
        const val CONTROL = 12f
    }

    // ————————————————————————— 色板 —————————————————————————

    /**
     * 色板：**取值全部来自 `res/values/colors.xml`**，此处不再出现 hex。
     *
     * 该色板是**手机端色板的暗色变体**（主色同为 #3482FF）：TV 只是把表面换成暗档，
     * 不是另一套配色。旧取值（GitHub Dark 一族 #0B0F14 / #58A6FF）已废弃。
     *
     * 之所以用「一次性绑定的可变 Pal」而不是每个色都传 Context：TV 的 UI 全是代码自绘，
     * 调用点遍布 View 构造 lambda，逐个传 Context 会把接口污染得很碎。
     * 各 Activity 在 `onCreate` 调一次 [bindColors] 即可；万一某个入口漏调，
     * 下面的默认值（与 colors.xml 同值）仍是正确配色，只是取不到资源里改过的值。
     */
    object Pal {
        // 默认值保持与 colors.xml 同值，保证「未 bind 也能正常显示」
        var bg: Int = 0xFF16181D.toInt(); private set
        var card: Int = 0xFF1F2228.toInt(); private set
        var cardFocus: Int = 0xFF3482FF.toInt(); private set
        var field: Int = 0xFF272B33.toInt(); private set
        var stroke: Int = 0xFF2E333B.toInt(); private set
        var text: Int = 0xFFF2F3F5.toInt(); private set
        var textDim: Int = 0xFF9BA1AA.toInt(); private set
        var textWeak: Int = 0xFF6E747C.toInt(); private set
        var accent: Int = 0xFF5B9CFF.toInt(); private set
        var accentSoft: Int = 0xFF1E3A66.toInt(); private set
        var ok: Int = 0xFF34D399.toInt(); private set
        var warn: Int = 0xFFFBBF24.toInt(); private set
        var error: Int = 0xFFF87171.toInt(); private set
        var idle: Int = 0xFF8A9199.toInt(); private set
        var mask: Int = 0x99000000.toInt(); private set
        var neutral: Int = 0xFF5A5F66.toInt(); private set

        internal fun bind(ctx: Context) {
            bg = ctx.getColor(R.color.tv_bg)
            card = ctx.getColor(R.color.tv_card)
            cardFocus = ctx.getColor(R.color.tv_card_focus)
            field = ctx.getColor(R.color.tv_field)
            stroke = ctx.getColor(R.color.tv_stroke)
            text = ctx.getColor(R.color.tv_text)
            textDim = ctx.getColor(R.color.tv_text_dim)
            textWeak = ctx.getColor(R.color.tv_text_weak)
            accent = ctx.getColor(R.color.tv_accent)
            accentSoft = ctx.getColor(R.color.tv_accent_soft)
            ok = ctx.getColor(R.color.tv_ok)
            warn = ctx.getColor(R.color.tv_warn)
            error = ctx.getColor(R.color.tv_error)
            idle = ctx.getColor(R.color.tv_idle)
            mask = ctx.getColor(R.color.tv_mask)
            neutral = ctx.getColor(R.color.tv_neutral)
        }
    }

    /** 由各 Activity 在 `onCreate` 调用一次，把资源色板灌进 [Pal] */
    fun bindColors(ctx: Context) = Pal.bind(ctx)
}
