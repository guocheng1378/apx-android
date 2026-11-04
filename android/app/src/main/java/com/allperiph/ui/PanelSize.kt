package com.allperiph.ui

import android.content.Context
import kotlin.math.roundToInt

/**
 * 悬浮面板高度偏好 —— 成宇 2026-11-05「悬浮窗出来的窗口应该可以调节大小」。
 *
 * ## 为什么需要它
 *
 * 悬浮球的几个 Compose 面板里，触控板与键盘的高度此前是**写死的屏幕比例**：
 *   · 触控板：竖屏 30%、横屏 34%，再夹在 220~360dp；
 *   · 键盘：竖屏 52%、横屏 66%。
 * 这套比例是照「底部留一点、上面还能看见原应用」定的，但它对每台机器、每种用法
 * 都只能算一个折中：大屏手机上 30% 只到手腕一半，放着晃；小屏机上 52% 的键盘
 * 又把原应用挤成一条缝。用户能把球挪来挪去，却唯独改不了这块面板的大小。
 *
 * 现在两条路都能改，并且**共用同一份偏好**（改一次，下次展开就用新的）：
 *   · 面板顶部新增拖拽把手（见 [FloatingBallService] 的 expandComposePanel），
 *     手指上下拖，窗口高度实时跟随，松手落盘；
 *   · 设置页「悬浮栏」里给两枚滑块，20%~90% 直接给数值。
 *
 * ## 存什么
 *
 * 存**屏幕高度的百分比整数**（20~90），不是 px、也不是 dp：
 *   · px 会随横竖屏、分屏、密度变化失效（存 900px，一转横屏就顶到屏幕外）；
 *   · dp 同样没解决横竖屏（同一块屏两个方向的高度差着几百 dp）。
 * 百分比是唯一跟「当时那块可用高度」绑定的量。
 *
 * 0 = **没有自定义**，各面板继续用旧版写死的比例 —— 老用户升级上来观感不变，
 * 也不必为了用一次悬浮球先去调一个自己没概念的数字。
 */
object PanelSize {

    private const val PREF = "apx_ball_panel"

    /** 触控板面板（[FloatingBallService.expand]） */
    const val TOUCHPAD = "touchpad_pct"

    /** 全键盘面板（[FloatingBallService.expandKeyboard]） */
    const val KEYBOARD = "keyboard_pct"

    /** 滑块与拖拽的共同下限：再矮就不够放下触控面／一排键帽 */
    const val MIN_PCT = 20

    /** 上限 90%：留一条缝让用户还能点到原应用与系统手势 */
    const val MAX_PCT = 90

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 当前比例（0.2f~0.9f）。返回 **0f 表示「没自定义」**，调用方按旧版写死比例算。
     */
    fun fraction(ctx: Context, key: String): Float {
        val pct = prefs(ctx).getInt(key, 0)
        if (pct <= 0) return 0f
        return pct.coerceIn(MIN_PCT, MAX_PCT) / 100f
    }

    /** 百分比整数（0 = 未自定义），设置页滑块直接显示它 */
    fun percent(ctx: Context, key: String): Int = prefs(ctx).getInt(key, 0)

    fun setPercent(ctx: Context, key: String, pct: Int) {
        prefs(ctx).edit().putInt(key, pct.coerceIn(MIN_PCT, MAX_PCT)).apply()
    }

    fun setFraction(ctx: Context, key: String, fraction: Float) {
        setPercent(ctx, key, (fraction * 100f).roundToInt())
    }

    /** 恢复「跟随屏幕比例」（清掉偏好 = 回到旧版写死比例） */
    fun clear(ctx: Context, key: String) {
        prefs(ctx).edit().remove(key).apply()
    }
}