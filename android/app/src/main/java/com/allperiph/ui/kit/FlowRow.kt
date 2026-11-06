package com.allperiph.ui.kit

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 会**自动换行**的横排容器。
 *
 * ## 为什么需要它
 *
 * issue#166 第 1 条「弹窗和编辑页横向元素显示不全」的真身就是这类写法：
 * 自绘编辑页把 12 颗键塞进一条 `LinearLayout(HORIZONTAL)` —— 每颗键 28dp 内边距
 * 加文字，在弹窗（宽度由 AlertDialog 定，≈360dp）里必然溢出，右边几颗直接画到
 * 屏幕外，**既看不见也点不到**，而页面本身没有横向滚动。
 *
 * 框架自带的 ViewGroup 里没有「排满就换行」这种东西（FlexboxLayout / ConstraintLayout
 * 的 Flow 都要额外依赖，本模块刻意只有 Compose + Miuix），所以这里手写一个最小实现：
 *
 *  · 子项按加入顺序从左到右排，本行放不下就换行；
 *  · 行高取该行最高的子项，行与行间距 [vGap]、同行子项间距 [hGap]（都是 **px**，
 *    调用方自己 `dp()` 换算 —— 与本仓 ApKit 的「只收 px」约定一致）；
 *  · 单个子项比可用宽度还宽时按可用宽度封顶（AT_MOST），不会把父容器撑爆。
 *
 * 用法与 LinearLayout 同：`FlowRow(ctx).apply { hGap = dp(6); addView(chip) }`。
 */
class FlowRow(context: Context) : ViewGroup(context) {

    /** 同一行内子项的水平间距（px） */
    var hGap: Int = 0

    /** 行与行之间的垂直间距（px） */
    var vGap: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)

        var lineW = 0
        var lineH = 0
        var totalH = paddingTop + paddingBottom
        // 子项一律按「父可用宽度」作为上限测量：单颗过长时自己换行内折行，
        // 而不是把整块布局撑出屏幕（那正是 issue#166 的原始症状）。
        val childWidthSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)

        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, childWidthSpec, heightMeasureSpec)
            val w = c.measuredWidth
            val gap = if (lineW == 0) 0 else hGap
            if (lineW > 0 && lineW + gap + w > available) {
                totalH += lineH + vGap
                lineW = 0
                lineH = 0
            }
            lineW += (if (lineW == 0) 0 else hGap) + w
            lineH = maxOf(lineH, c.measuredHeight)
        }
        totalH += lineH

        setMeasuredDimension(
            resolveSize(widthSize, widthMeasureSpec),
            resolveSize(totalH, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val w = c.measuredWidth
            val h = c.measuredHeight
            if (x > paddingLeft && x + w > paddingLeft + available) {
                x = paddingLeft
                y += lineH + vGap
                lineH = 0
            }
            c.layout(x, y, x + w, y + h)
            x += w + hGap
            lineH = maxOf(lineH, h)
        }
    }
}
