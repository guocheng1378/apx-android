package com.allperiph.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.view.Surface
import com.allperiph.core.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 副屏渲染器：MediaCodec 硬解 → Surface（由 [ScreenActivity] 提供）。
 *
 * 为什么是单例：产生帧的是媒体收流线程（[com.allperiph.core.ApxStreams] 回调），
 * 而 Surface 由 Activity 的 `onSurfaceTextureAvailable` 提供 —— 两者生命周期不同步。
 * 单例让「模块先启动、用户后打开副屏页」也能工作（打开前的帧直接丢，见 [submit]）。
 *
 * ## 线程纪律
 * `MediaCodec.queueInputBuffer` 在输入缓冲用尽时会阻塞。收流线程上绝对不能做这件事
 * （会把同一连接上的音频与摄像头一起拖住），所以这里只把位流**入队**，
 * 由专用解码线程喂给解码器。
 */
object ScreenRenderer {

    private const val TAG = "ScreenRenderer"
    private const val QUEUE_CAP = 8
    private const val TIMEOUT_US = 10_000L

    @Volatile
    private var surface: Surface? = null

    private var codec: MediaCodec? = null
    private var mime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private var width = 0
    private var height = 0

    @Volatile
    private var running = false
    private var decodeThread: Thread? = null

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)

    /** 没有 Surface 时被丢弃的帧数（用户还没打开副屏页） */
    val droppedNoSurface = AtomicLong(0)

    /** 解码器不认的编码（RAW_LZ4 / MJPEG）被跳过的帧数 */
    val skippedCodec = AtomicLong(0)

    private val decodedFrames = AtomicLong(0)

    val attached: Boolean get() = surface != null

    /** 由 ScreenActivity 在 Surface 就绪时调用；宽高用首帧的真实值，未知时传 0 由帧内信息决定 */
    fun attachSurface(s: Surface) {
        detachSurface()
        surface = s
        running = true
        decodeThread = Thread({ decodeLoop() }, "apx-screen-dec").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Surface 已接入，等待首帧以确定编码与分辨率")
    }

    fun detachSurface() {
        running = false
        queue.clear()
        val self = Thread.currentThread()
        decodeThread?.takeIf { it.isAlive && it !== self }?.let { runCatching { it.join(500) } }
        decodeThread = null
        releaseCodec()
        surface = null
        decodedFrames.set(0)
    }

    /** 收流线程调用：只入队，绝不阻塞 */
    fun submit(body: ByteArray) {
        if (!running || surface == null) {
            droppedNoSurface.incrementAndGet()
            return
        }
        val f = VideoFrame.parse(body) ?: return
        if (!f.decodable) {
            skippedCodec.incrementAndGet()
            return
        }
        // 编码/分辨率变化需要重建解码器 —— 用首帧或变化帧的参数驱动
        if (codec == null || f.width != width || f.height != height ||
            mimeFor(f.codecId) != mime
        ) {
            val stream = body.copyOfRange(f.bitstreamOffset, f.bitstreamOffset + f.bitstreamLength)
            ensureCodec(f.width, f.height, mimeFor(f.codecId), stream)
            return
        }
        val stream = body.copyOfRange(f.bitstreamOffset, f.bitstreamOffset + f.bitstreamLength)
        if (!queue.offer(stream)) {
            queue.poll()               // 保新弃旧：副屏宁可跳帧也不要积出延迟
            queue.offer(stream)
        }
    }

    fun stats(): String {
        val d = decodedFrames.get()
        val s = skippedCodec.get()
        val n = droppedNoSurface.get()
        return buildString {
            append("已解码 $d 帧")
            if (s > 0) append(" · 编码不支持 $s")
            if (n > 0) append(" · 无画面丢弃 $n")
        }
    }

    // ————————————————————————————— 内部 —————————————————————————————

    private fun mimeFor(codecId: Int): String = when (codecId) {
        VideoFrame.CODEC_HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        VideoFrame.CODEC_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        else -> MediaFormat.MIMETYPE_VIDEO_AVC
    }

    /**
     * 建/重建解码器。必须**带上首帧的 CSD**（H.264 的 SPS/PPS 在 MediaCodec 的
     * `csd-0` 里；PC 侧编出来的流若把 SPS/PPS 内联在码流里，直接喂也能解，
     * 但显式设置更稳）。这里把触发重建的那一帧作为首帧立即投喂。
     */
    private fun ensureCodec(w: Int, h: Int, newMime: String, firstFrame: ByteArray) {
        releaseCodec()
        val s = surface ?: return
        try {
            val format = MediaFormat.createVideoFormat(newMime, w, h).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = MediaCodec.createDecoderByType(newMime).apply {
                configure(format, s, null, 0)
                start()
            }
            codec = c
            mime = newMime
            width = w
            height = h
            decodedFrames.set(0)
            Log.i(TAG, "解码器已启动：$newMime ${w}x$h")
            // 重建时的这一帧不能丢，否则要等到下一个关键帧才有画面
            feed(c, firstFrame)
        } catch (t: Throwable) {
            Log.e(TAG, "解码器创建失败（$newMime ${w}x$h）：${t.message}")
            releaseCodec()
        }
    }

    private fun decodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            val chunk = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            val c = codec ?: continue
            feed(c, chunk)
        }
    }

    private fun feed(c: MediaCodec, data: ByteArray) {
        try {
            val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex)
                if (buf != null) {
                    buf.clear()
                    buf.put(data)
                    c.queueInputBuffer(inIndex, 0, data.size,
                        decodedFrames.get() * 16_666L, 0)
                } else {
                    c.queueInputBuffer(inIndex, 0, 0, 0, 0)
                }
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                c.releaseOutputBuffer(outIndex, info.size != 0)
                decodedFrames.incrementAndGet()
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
        } catch (t: Throwable) {
            // 解码失败按帧丢弃，不把整个渲染器拆掉（下一帧可能是新关键帧）
            Log.w(TAG, "解码失败：${t.message}")
        }
    }

    private fun releaseCodec() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        width = 0
        height = 0
    }
}
