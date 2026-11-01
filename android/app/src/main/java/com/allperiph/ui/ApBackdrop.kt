package com.allperiph.ui

import android.content.Context

/**
 * 全局背景（v1.31）：预设底色 / 自定义背景图的选择与读写。
 *
 * 只负责**存什么**；怎么铺到视图上是 [Backdrop] 的事（那里做采样解码与优先级判断）。
 * 优先级：**自定义图片 > 预设底色 > 布局自带的资源底色**（[Bg.color] = 0 的 `follow` 档）。
 */
object ApBackdrop {

    /** 背景预设；[Bg.color] = 0 表示「跟随主题」（用布局自带的资源底色） */
    data class Bg(val id: String, val name: String, val color: Int)

    val BG_PRESETS: List<Bg> = listOf(
        Bg("follow", "跟随主题", 0),
        Bg("cloud", "云白", 0xFFF2F3F5.toInt()),
        Bg("warm", "暖沙", 0xFFF6F0E6.toInt()),
        Bg("mist", "雾青", 0xFFEAF1F4.toInt()),
        Bg("graphite", "石墨", 0xFF17181A.toInt()),
        Bg("deep", "深空", 0xFF0C0F14.toInt()),
    )

    fun bgId(ctx: Context): String =
        ApAppearancePrefs.of(ctx).getString(ApAppearancePrefs.KEY_BG, "follow").orEmpty()

    fun setBg(ctx: Context, id: String) {
        ApAppearancePrefs.of(ctx).edit().putString(ApAppearancePrefs.KEY_BG, id).apply()
    }

    /** 自定义背景图（相册选来的 URI）；设了图就优先于纯色底 */
    fun bgImage(ctx: Context): String? =
        ApAppearancePrefs.of(ctx).getString(ApAppearancePrefs.KEY_BG_IMAGE, null)

    fun setBgImage(ctx: Context, uri: String?) {
        ApAppearancePrefs.of(ctx).edit().apply {
            if (uri.isNullOrBlank()) remove(ApAppearancePrefs.KEY_BG_IMAGE)
            else putString(ApAppearancePrefs.KEY_BG_IMAGE, uri)
        }.apply()
    }
}
