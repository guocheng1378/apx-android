package com.allperiph.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 背景图解码（**全仓唯一一份**）。
 *
 * ## 为什么收口
 * 这条逻辑此前有两份实现：Views 侧 `Backdrop.decode` 与 Compose 侧
 * `ApBackdropLayer.decode`。注释里写着「口径一致，避免分叉」，实际是两份代码各改各的。
 * 收成一处之后，采样策略只需要在一个地方调。
 *
 * ## 2026-11-04 修「加载图片卡顿」（成宇反馈）
 * 旧的采样条件是 `w / 2 >= target`，意思是「折半之后**仍然**不小于目标」才继续折 ——
 * 结果一张 4000px 宽的手机照片在 1200px 屏上连折一次的资格都没有，**sample 停在 1**，
 * 整张原图直接进内存：4000×3000×4 ≈ 48MB。后果是选完图那一下解码要几百毫秒（期间背景
 * 空着），并且开「动态背景」后每一帧都在拿这张巨图做缩放采样，观感就是卡。
 *
 * 现在的口径是 [BitmapFactory.Options.inSampleSize] 的本义 ——「宽高各取 1/sample」，
 * 于是：折到**宽高都不大于**目标框为止（2 倍屏宽 × 2 倍屏高，留出「动态背景」放大
 * 1.09 倍 + 平移的余量）。取 2 倍是因为放大会糊，2 倍是这个放大量下的安全值。
 *
 * 再加一层「按 URI 记忆最后一张」的小缓存：切页 / 重组时不会重复解码，换图时旧的
 * 随之释放（只留一张，不涨内存）。
 */
internal object BackdropDecode {

    /** 只留最近一张：背景同时只显示一张，多留没有意义 */
    private var cachedUri: String? = null
    private var cached: Bitmap? = null

    /**
     * @param scale 目标相对屏幕的倍数（默认 2：留出动态背景的放大余量）
     */
    fun decode(ctx: Context, uri: String, scale: Float = 2f): Bitmap? {
        if (uri.isBlank()) return null
        if (uri == cachedUri) cached?.let { return it }
        val bmp = runCatching { decodeUncached(ctx, uri, scale) }.getOrNull()
        if (bmp != null) {
            cachedUri = uri
            cached = bmp
        }
        return bmp
    }

    /** 换图 / 清空背景时调用，释放上一张 */
    fun invalidate() {
        cached = null
        cachedUri = null
    }

    private fun decodeUncached(ctx: Context, uri: String, scale: Float): Bitmap? {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val dm = ctx.resources.displayMetrics
        val targetW = (dm.widthPixels * scale).toInt().coerceAtLeast(1)
        val targetH = (dm.heightPixels * scale).toInt().coerceAtLeast(1)
        var sample = 1
        // 折到宽高都不大于目标框为止；上限 16，防极端尺寸反复开流
        while (sample < 16 &&
            (bounds.outWidth / sample > targetW || bounds.outHeight / sample > targetH)
        ) {
            sample *= 2
        }
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opt) }
    }
}