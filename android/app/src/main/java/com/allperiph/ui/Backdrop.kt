package com.allperiph.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.View

/**
 * 背景落地（v1.31）：把用户在设置页选的背景铺到根视图上。
 *
 * 优先级：**自定义图片 > 预设底色 > 布局自带的资源底色**（[ThemeSkin.Bg] 里的 `follow`）。
 * 图片按屏幕宽度采样解码（最多折到 1/8），避免相册里的大图整张进内存。
 */
object Backdrop {

    /**
     * @param defaultColor `follow` 档位用的资源底色（主页是 md_background，操控面是 miuix_bg）
     */
    fun apply(root: View, defaultColor: Int) {
        val ctx = root.context
        val uri = ThemeSkin.bgImage(ctx)
        if (!uri.isNullOrBlank()) {
            decode(ctx, uri)?.let {
                root.background = BitmapDrawable(ctx.resources, it)
                return
            }
        }
        val preset = ThemeSkin.BG_PRESETS.firstOrNull { it.id == ThemeSkin.bgId(ctx) }
        root.setBackgroundColor(if (preset != null && preset.color != 0) preset.color else defaultColor)
    }

    /** 采样解码到"两倍屏宽"以内 */
    private fun decode(ctx: Context, uri: String): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = ctx.resources.displayMetrics.widthPixels * 2
        var sample = 1
        var w = bounds.outWidth
        while (w > 0 && w / 2 >= target && sample < 8) {
            sample *= 2
            w /= 2
        }
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opt) }
    }.getOrNull()
}
