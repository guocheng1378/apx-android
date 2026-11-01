package com.allperiph.shared.ui

import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * UI 构造约定 —— 「画形状」这件事的**唯一实现处**。
 *
 * 背景：改造前 `GradientDrawable` 手写在 7 个文件里散了 36 处，写法各不相同
 * （有的 `cornerRadius = dp(999)`、有的自带 private fun card()、有的直接 new）。结果就是
 * 「卡片圆角是 18 还是 12」这类问题要在多处分别改，暗色 / 换肤也容易漏某一处。
 *
 * ## 为什么放在 `:shared`
 * 手机端（`:app`）与电视端（`:tv`）**各存了一份同构实现** —— 两个 `ui/kit/ApKit.kt`
 * 与 `tv/ui/TvUi.kt` 里的 `focusBg` / `solidBg` 做的是同一件事，却因为模块各自独立而复制。
 * 两端都依赖 `:shared`，于是把工厂提到这里：一套圆角 / 描边口径，两端共用，
 * 也不会因为改了一边忘了另一边而分叉（本仓踩过「TV 与手机两套实现分头长歪」）。
 *
 * ## 换肤与尺寸
 * - 本类**只做形状，不选颜色**：颜色由调用方给（tokens.xml 令牌 / ThemeSkin / 皮肤色），
 *   所以换肤时不用改这里；
 * - 只做形状，也不做 dp 换算：px 参数由调用方的 `dp()` 传进来。各端 density 口径不同
 *   （TV 端 `TvUi.dp` 内含 1080p 基准缩放），在这里换算会吃掉那层语义；
 * - 触控目标下限见 [TOUCH_MIN_DP]，「看起来像按钮」的 View 用 [asButton] 补齐可点性。
 */
object ApKit {

    /** 触控目标下限：Material / WCAG 均推荐 48dp；小于它的可点元素对 TalkBack 与遥控器都不友好 */
    const val TOUCH_MIN_DP = 48

    /** 卡片 / 面板的圆角档位（px 由调用方按 density 换算后传入） */
    const val RADIUS_CARD_DP = 18

    /** 细描边宽度。注意 `strokePx = 0` 时 [shape] 不画描边（0 会画出 1px 黑边的坑已规避） */
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

    /** 胶囊（药丸按钮 / 标签）。[radiusPx] 传 `dp(999)` 即可撑满半高 */
    fun pill(fill: Int, radiusPx: Float = 0f) = shape(fill, radiusPx)

    /** 圆／椭圆（色点、方向键底盘、浮层锚点） */
    fun oval(fill: Int, strokeColor: Int = 0, strokePx: Int = 0) =
        shape(fill, 0f, GradientDrawable.OVAL, strokeColor, strokePx)

    /**
     * 把「看起来像按钮、实际不是 Button」的 View 补齐可点性：
     * 最小触控尺寸 + 可聚焦（遥控器 / 键盘可达）+ 无障碍描述。
     *
     * 手机的按压涟漪由 XML 侧 `@style/APTextButton` 提供，TV 端由 `TvUi.focusBg` 提供；
     * 自绘 View 可自行设 background。调用前需先 [initTouchMetrics] 一次。
     */
    fun asButton(view: View, contentDescription: String? = null): View = view.apply {
        isClickable = true
        isFocusable = true
        minimumHeight = minPx
        minimumWidth = minPx
        contentDescription?.let { this.contentDescription = it }
    }

    /** [TOUCH_MIN_DP] 对应的 px（由各端在资源就绪后调用一次 [initTouchMetrics] 落地） */
    var minPx: Int = 0
        private set

    /** 由 Application / Activity 在资源就绪后调用一次，用于把 48dp 落到 px */
    fun initTouchMetrics(density: Float) {
        minPx = (TOUCH_MIN_DP * density).toInt().coerceAtLeast(1)
    }
}