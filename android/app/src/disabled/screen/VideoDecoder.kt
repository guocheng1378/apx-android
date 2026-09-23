package com.allperiph.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * H.264/H.265 硬解码器：接收 PC 编码帧 → MediaCodec 解码 → Surface 渲染。
 *
 * 使用 MediaCodec 同步模式（API 21+）。帧格式由 PC 端 IddCx + DDA 抓屏 + NVENC 硬编决定。
 * v1.34 从 v21 移植；v21 用异步模式，本地按同步模式实现（更简单、与单线程读循环契合）。
 */
class VideoDecoder(
    private var width: Int = 1280,
    private var height: Int = 720
) {
    private var codec: MediaCodec? = null
    private var started = false
    private var frameCount = 0L

    /**
     * 配置并启动解码器。
     * @param surface SurfaceView 的 Surface
     * @param mime MediaFormat.MIMETYPE_VIDEO_AVC (H.264) 或 VIDEO_HEVC (H.265)
     */
    fun configure(surface: Surface, mime: String = MediaFormat.MIMETYPE_VIDEO_AVC) {
        stop()

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
            start()
            started = true
        }

        frameCount = 0
        Log.i(TAG, "解码器已启动: $mime ${width}x${height}")
    }

    /**
     * 送入一帧编码数据。
     * @param data APX1 帧 payload（纯编码数据）
     * @param offset 起始偏移
     * @param length 数据长度
     */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val c = codec ?: return
        if (!started) return

        try {
            val inputIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = c.getInputBuffer(inputIndex) ?: return
                buffer.clear()
                buffer.put(data, offset, length)
                c.queueInputBuffer(inputIndex, 0, length, frameCount * 33333L, 0)
                frameCount++
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = c.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                c.releaseOutputBuffer(outputIndex, info.size != 0)
                outputIndex = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解码异常: ${e.message}")
        }
    }

    /** 重新配置分辨率 */
    fun reconfigure(newWidth: Int, newHeight: Int, surface: Surface) {
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        configure(surface)
        Log.i(TAG, "解码器重新配置: ${width}x${height}")
    }

    fun stop() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        started = false
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10_000L
    }
}
