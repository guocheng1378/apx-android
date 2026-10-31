package com.allperiph.ui.kit

import android.content.Context
import android.view.View
import android.widget.ScrollView

/**
 * 对话框内容的「限高滚动」外壳（issue #52 问题 2：横屏弹窗显示不全）。
 *
 * 背景：`AlertDialog.Builder.setView(...)` 里塞的是自绘长列表（快捷键管理、快捷键编辑、
 * 快捷键模板新建）。竖屏时它们撑得下，**横屏可视高度只剩一半多一点**，对话框就把
 * 标题和底部按钮顶到屏幕外 —— 真机反馈「部分弹窗显示不全」。
 *
 * 平台不会自动给 setView 的内容加滚动，所以要自己套一层 ScrollView；但光套 ScrollView
 * 不够：ScrollView 在 wrap_content 下仍然先量内容真实高度，对话框照样变高。因此这里用
 * [CappedScrollView] 给它一个**上限**，超过就滚，不超过就贴合内容（不出现空白）。
 *
 * 用法：`setView(DialogKit.scrollable(act, box))`。
 */
object DialogKit {

    /**
     * 内容区最多占屏高的比例。剩下的留给标题、按钮、窗口外边距。
     *
     * 取 0.62 是照竖屏算的：800dp 屏 → 内容 ≈ 496dp，标题 + 两个按钮 ≈ 160dp，
     * 合计仍在屏内；横屏（≈360dp 高）时上限变成 ≈223dp，长列表改滚动，按钮照样看得见。
     */
    private const val MAX_HEIGHT_RATIO = 0.62f

    /** 下限：再矮的屏也别把内容压成一条缝（屏幕上还剩标题和按钮要占地方） */
    private const val MIN_HEIGHT_DP = 140

    /** 把 [content] 包进限高滚动壳；已经是 ScrollView 或已有父容器时原样返回 */
    fun scrollable(ctx: Context, content: View): View {
        if (content is ScrollView) return content
        if (content.parent != null) return content
        val dm = ctx.resources.displayMetrics
        val minPx = (MIN_HEIGHT_DP * dm.density).toInt()
        val maxPx = (dm.heightPixels * MAX_HEIGHT_RATIO).toInt().coerceAtLeast(minPx)
        return CappedScrollView(ctx, maxPx).apply {
            isFillViewport = false
            addView(content)
        }
    }
}

/** ScrollView + 一个硬上限：父级给的高度再大，自己最多也只量到 [maxPx] */
private class CappedScrollView(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val capped = when (mode) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(size.coerceAtMost(maxPx), mode)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}
