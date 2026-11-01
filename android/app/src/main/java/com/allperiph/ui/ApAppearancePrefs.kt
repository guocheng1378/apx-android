package com.allperiph.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * 外观偏好的**唯一落盘处**（v1.30 起的 `ThemeSkin` 里那几个 private key 搬到这里）。
 *
 * ## 为什么单独一个对象
 * 拆 [ApSkin] / [ApPalette] / [ApBackdrop] / [ApTiming] / [ApFeel] 的时候，五个门面读写的是
 * **同一份** SharedPreferences。如果每处各写一遍 `getSharedPreferences("apx_theme", ...)` 与
 * 键名，就又回到「一个功能两套真源」的老路上：改键名时漏改一处，只在真机上表现为
 * 「设置里选了，重进又变回去了」（本仓库在 issue#52 里踩过同一类问题）。
 *
 * 键名是**磁盘协议**：`PREF` 或任一 `KEY_*` 改动都会让用户已选的皮肤 / 背景图 / 动效档位
 * 全部丢失，改之前先想清楚要不要迁移。
 */
internal object ApAppearancePrefs {

    /** 偏好文件名（磁盘协议，别改） */
    const val PREF = "apx_theme"

    const val KEY_CUSTOM = "custom_skins"
    const val KEY_BG = "bg_id"
    const val KEY_BG_IMAGE = "bg_image"
    const val KEY_MOTION = "motion"
    const val KEY_HAPTIC = "haptic"
    const val KEY_SOUND = "sound"
    const val KEY_COLOR_SOURCE = "color_source"

    /** 亮 / 暗档位（ThemePref 用；与皮肤同文件，但键独立） */
    const val KEY_MODE = "mode"

    /** 每个模块（触控板 / 键盘 / 快捷键）各一个键 */
    fun pickKey(scope: String) = "pick_$scope"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
