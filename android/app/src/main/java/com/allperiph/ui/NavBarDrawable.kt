package com.allperiph.ui

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.allperiph.R

/**
 * 底栏「液态玻璃」选中胶囊（v1.24）。
 *
 * 底栏本身**没有底板**，玻璃特效只作用在选中胶囊这一个元素上。材质按"一块有厚度的
 * 玻璃"分六层叠加，而不是一块半透明色片：
 *
 *   ① 外圈柔光 —— 玻璃浮在页面上方的光晕
 *   ② 主体     —— 半透明白
 *   ③ 顶部弧面 —— 上亮下透，做出凸面
 *   ④ 侧壁镜面 —— 左右两端各有一道反光（玻璃边缘的折射感）
 *   ⑤ 底部内阴影 —— 让玻璃有厚度，不是贴纸
 *   ⑥ 边缘亮线 —— 轮廓
 *
 * 位置 / 宽度由 MainActivity 插值（滑动中段会膨胀一下，像液体被推着走）。
 * 颜色读 `@color/nav_pill*` 令牌，亮 / 暗主题自动跟随。
 */
class NavBarDrawable(private val act: Activity) : Drawable() {

    private val density = act.resources.displayMetrics.density

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topShinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideShinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val haloRect = RectF()
    private var indicator: RectF? = null

    init {
        refreshColors()
    }

    /** 主题切换 / Activity 重建后调用，重新取色 */
    fun refreshColors() {
        fillPaint.color = act.getColor(R.color.nav_pill)
        haloPaint.color = act.getColor(R.color.nav_pill_halo)
        strokePaint.color = act.getColor(R.color.nav_pill_stroke)
        invalidateSelf()
    }

    fun indicatorRect(): RectF? = indicator?.let { RectF(it) }

    fun setIndicator(rect: RectF?) {
        indicator = rect
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        // 无底板：没有选中胶囊就什么都不画（底栏保持全透明）
        val it = indicator ?: return
        if (bounds.isEmpty) return
        val r = it.height() / 2f
        val shine = act.getColor(R.color.nav_pill_shine)

        // ① 外圈柔光
        val halo = 3f * density
        haloRect.set(it.left - halo, it.top - halo, it.right + halo, it.bottom + halo)
        canvas.drawRoundRect(haloRect, r + halo, r + halo, haloPaint)

        // ② 主体
        canvas.drawRoundRect(it, r, r, fillPaint)

        // ③ 顶部弧面：上亮下透
        topShinePaint.shader = LinearGradient(
            0f, it.top, 0f, it.bottom,
            shine, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(it, r, r, topShinePaint)

        // ④ 侧壁镜面：左右两端各一道反光，中间透
        sideShinePaint.shader = LinearGradient(
            it.left, 0f, it.right, 0f,
            intArrayOf(shine, 0x00FFFFFF, 0x00FFFFFF, shine),
            floatArrayOf(0f, 0.16f, 0.84f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(it, r, r, sideShinePaint)

        // ⑤ 底部内阴影：给玻璃一点厚度
        bottomShadePaint.shader = LinearGradient(
            0f, it.top + it.height() * 0.55f, 0f, it.bottom,
            0x00FFFFFF, act.getColor(R.color.nav_pill_shade), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(it, r, r, bottomShadePaint)

        // ⑥ 边缘亮线
        canvas.drawRoundRect(it, r, r, strokePaint)
    }

    /** 无底板 → 不参与 elevation 投影 */
    override fun getOutline(outline: Outline) {
        outline.setEmpty()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
