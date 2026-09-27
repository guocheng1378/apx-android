package com.allperiph.ui.kit

import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * UI 构造约定（v1.33）——手机端「画形状」这件事的**唯一实现处**。
 *
 * 背景：改造前 `GradientDrawable` 的手写在 7 个文件里散了 36 处，写法各不相同
 * （有的 `cornerRadius = dp(999)`、有的自己的 private fun card()、有的直接 new）。
 * 结果就是「卡片圆角是 18 还是 12」这类问题要在多处分别改，暗色/换肤也容易漏某一处。
 *
 * 本类只做一件事：**给定 px 参数，产出一个 drawable**。dp 换算交给调用方的 `dp()`，
 * 这样本类不持有 Context，TV 端（另一套 dp 缩放）也能直接复用。
 *
 * 约定：
 * - 颜色一律来自 tokens.xml 的 color 令牌（亮/暗主题自动跟随），不在此写死 hex；
 * - 需要描边时 `strokePx > 0` 才生效（0 会画出 1px 黑边的坑已规避）；
 * - 触控目标下限见 [TOUCH_MIN_DP]，凡是「看起来像按钮」的 View 都应 [asButton]。
 */
object ApKit {

    /** 触控目标下限：Material / WCAG 均推荐 48dp；小于它的可点元素对 TalkBack 与遥控器都不友好 */
    const val TOUCH_MIN_DP = 48

    // 形状 / 圆角 / 描边的常用档位（px 由调用方按 density 换算后传入）
    const val RADIUS_CARD_DP = 18
    const val STROKE_HAIRLINE_PX = 1

    /**
     * 通用形状。[radiusPx] 传 ≥ 边长一半即得到胶囊；[form] 用 [GradientDrawable.OVAL] 得到圆。
     */
    fun shape(
        fill: Int,
        radiusPx: Float = 0f,
        form: Int = GradientDrawable.RECTANGLE,
        strokeColor: Int = 0,
        strokePx: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = form
        setColor(fill)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    /** 圆角矩形（卡片 / 键帽 / 面板） */
    fun rect(fill: Int, radiusPx: Float = 0f) = shape(fill, radiusPx)

    /** 胶囊（药丸按钮 / 标签） */
    fun pill(fill: Int, radiusPx: Float) = shape(fill, radiusPx)

    /** 圆／椭圆（色点、方向键底盘、浮层锚点） */
    fun oval(fill: Int, strokeColor: Int = 0, strokePx: Int = 0) =
        shape(fill, 0f, GradientDrawable.OVAL, strokeColor, strokePx)

    /**
     * 把「看起来像按钮、实际不是 Button」的 View 补齐可点性：
     * 最小触控尺寸 + 可聚焦（遥控器/键盘可达）+ 无障碍描述。
     *
     * 按压涟漪由 XML 侧 `@style/APTextButton` 提供（自绘 View 可自行设 background）。
     */
    fun asButton(view: View, contentDescription: String? = null): View = view.apply {
        isClickable = true
        isFocusable = true
        minimumHeight = minPx
        minimumWidth = minPx
        contentDescription?.let { this.contentDescription = it }
    }

    /** [TOUCH_MIN_DP] 对应的 px（由 UI 线程首次使用时按当前 density 计算） */
    var minPx: Int = 0
        private set

    /** 由 Application/Activity 在资源就绪后调用一次，用于把 48dp 落到 px */
    fun initTouchMetrics(density: Float) {
        minPx = (TOUCH_MIN_DP * density).toInt().coerceAtLeast(1)
    }
}
