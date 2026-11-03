package com.allperiph.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.Transformation

/**
 * 背景落地（v1.31）：把用户在设置页选的背景铺到根视图上。
 *
 * 优先级：**自定义图片 > 预设底色 > 布局自带的资源底色**（[ApBackdrop.Bg] 里的 `follow`）。
 * 图片按屏幕宽度采样解码（最多折到 1/8），避免相册里的大图整张进内存。
 *
 * 动态背景（2026-11-03）：开了 [ApBackdrop.bgAnim] 之后，`follow` 与预设底色这两条
 * **纯色**路径走一个极缓慢的缩放（1.0 → 1.06 往返，40s 一程），观感上像呼吸而非「动图」。
 * 相册图那条路不做 —— `BitmapDrawable` 的缩放会重新采样，一张 2 倍屏宽的大图每帧重采样
 * 开销明显；纯色与预置色只是一个 solid 色块，缩放由 GPU 合成，代价可忽略。
 * 关掉开关时立刻 `clearAnimation()` + 复位，不会停在半路上。
 */
object Backdrop {

    /**
     * @param defaultColor `follow` 档位用的资源底色（主页是 md_background，操控面是 miuix_bg）
     */
    fun apply(root: View, defaultColor: Int) {
        val ctx = root.context
        val uri = ApBackdrop.bgImage(ctx)
        if (!uri.isNullOrBlank()) {
            decode(ctx, uri)?.let {
                stop(root)
                root.background = BitmapDrawable(ctx.resources, it)
                return
            }
        }
        val preset = ApBackdrop.BG_PRESETS.firstOrNull { it.id == ApBackdrop.bgId(ctx) }
        root.setBackgroundColor(if (preset != null && preset.color != 0) preset.color else defaultColor)
        if (ApBackdrop.bgAnim(ctx)) start(root) else stop(root)
    }

    /** 动态背景：用 Animation 驱动 scale，不新增线程也不改布局参数 */
    private class Breathe : Animation() {
        init {
            duration = 40_000L
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            fillAfter = true
        }

        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            val s = 1f + 0.06f * interpolatedTime
            t.matrix.setScale(s, s, 0.5f, 0.5f)
        }
    }

    private fun start(root: View) {
        if (root.animation is Breathe) return
        root.startAnimation(Breathe())
    }

    /** 关掉动态背景时复位：清动画并把缩放矩阵还原（只清动画会留下最后一帧的缩放） */
    fun stop(root: View) {
        val a = root.animation
        if (a is Breathe) {
            a.cancel()
            root.clearAnimation()
            root.scaleX = 1f
            root.scaleY = 1f
        }
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
