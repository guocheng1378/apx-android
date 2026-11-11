package com.allperiph.ui

import android.content.Context

/**
 * 悬浮球位置共享存储 —— 服务层球（[FloatingBallService]）与壳内导航球（ApShellActivity 低栏）
 * 共用同一份「最后落点」，app 内外球位置无缝衔接（issue#168 2026-11-11：底部悬浮栏改
 * 悬浮球样式，「出了 app 悬浮球同步位置」）。
 *
 * 存的是**球左上角相对全屏的比例**（0–1），而不是像素：转屏、换机、分辨率变化后
 * 直接按比例还原即可，无需再做缩放迁移。读写都是 prefs 单键，开销可忽略。
 */
object BallPos {

    private const val PREFS = "apx_floating_ball"
    private const val KEY_X = "last_pos_ratio_x"
    private const val KEY_Y = "last_pos_ratio_y"

    /** 记下球左上角的屏幕比例位置（[FloatingBallService.publishBallPosition] 与壳内球拖动落点都走这里） */
    fun save(ctx: Context, screenLeft: Float, screenTop: Float, screenW: Int, screenH: Int) {
        if (screenW <= 0 || screenH <= 0) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_X, (screenLeft / screenW).coerceIn(0f, 1f))
            .putFloat(KEY_Y, (screenTop / screenH).coerceIn(0f, 1f))
            .apply()
    }

    /**
     * 取上次的球位置（球左上角像素，已按当前屏幕尺寸换算并夹回屏内）；
     * 没有记录或屏幕尺寸异常时返回 null，由调用方走各自的原生默认位。
     */
    fun load(ctx: Context, screenW: Int, screenH: Int, ballPx: Int): Pair<Int, Int>? {
        if (screenW <= 0 || screenH <= 0) return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = p.getFloat(KEY_X, -1f)
        val y = p.getFloat(KEY_Y, -1f)
        if (x < 0f || y < 0f) return null
        val px = (x * screenW).toInt().coerceIn(0, (screenW - ballPx).coerceAtLeast(0))
        val py = (y * screenH).toInt().coerceIn(0, (screenH - ballPx).coerceAtLeast(0))
        return px to py
    }
}
