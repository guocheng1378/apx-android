package com.allperiph.ui

import android.content.Context

/**
 * 动效档位（Views 侧）。Compose 侧的统一入口是 `ui.miuix.ApMotion`（它调这里）。
 *
 * 档位含义：**0 关闭 / 1 更快 / 2 流畅 / 3 更慢**。「关闭」的语义是**不做过渡**——
 * 调用方拿到 0ms 应当直接落到终态，而不是把弹簧调得很快（那仍会看到一帧位移）。
 */
object ApTiming {

    val MOTION_LABELS = listOf("关闭", "更快", "流畅", "更慢")

    private val MOTION_SCALES = floatArrayOf(0f, 0.7f, 1f, 1.4f)

    fun motionIndex(ctx: Context): Int =
        ApAppearancePrefs.of(ctx).getInt(ApAppearancePrefs.KEY_MOTION, 2)
            .coerceIn(0, MOTION_LABELS.size - 1)

    fun setMotion(ctx: Context, i: Int) {
        ApAppearancePrefs.of(ctx).edit().putInt(ApAppearancePrefs.KEY_MOTION, i).apply()
    }

    /** 动效时长倍率；**0 = 关闭**，调用方据此直接跳过动画 */
    fun motionScale(ctx: Context): Float = MOTION_SCALES[motionIndex(ctx)]

    /** 按倍率折算动画时长（关闭时返回 0） */
    fun motionMs(ctx: Context, base: Long): Long {
        val scale = motionScale(ctx)
        return if (scale <= 0f) 0L else (base * scale).toLong().coerceAtLeast(1L)
    }
}
