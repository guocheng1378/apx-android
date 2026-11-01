package com.allperiph.ui

import android.content.Context

/**
 * 手感档位：震动强度与按键音（Views 与 Compose 共用）。
 *
 * 只是**读档位**；真正去震/去响的是 [Feedback]，它必须经这里判档，否则会出现
 * 「设置里选了『关』照样震」——本仓库踩过（绕过档位直接 `VibrationEffect`）。
 */
object ApFeel {

    /** 震动档位：0 关 / 1 轻 / 2 标准 / 3 强 */
    val HAPTIC_LABELS = listOf("关", "轻", "标准", "强")

    /** 音效：0 关 / 1 点击 / 2 键盘敲击 / 3 机械轴 */
    val SOUND_LABELS = listOf("关", "点击", "键盘", "机械轴")

    fun hapticIndex(ctx: Context): Int =
        ApAppearancePrefs.of(ctx).getInt(ApAppearancePrefs.KEY_HAPTIC, 2).coerceIn(0, 3)

    fun setHaptic(ctx: Context, i: Int) {
        ApAppearancePrefs.of(ctx).edit().putInt(ApAppearancePrefs.KEY_HAPTIC, i).apply()
    }

    fun soundIndex(ctx: Context): Int =
        ApAppearancePrefs.of(ctx).getInt(ApAppearancePrefs.KEY_SOUND, 1)
            .coerceIn(0, SOUND_LABELS.size - 1)

    fun setSound(ctx: Context, i: Int) {
        ApAppearancePrefs.of(ctx).edit().putInt(ApAppearancePrefs.KEY_SOUND, i).apply()
    }
}
