package com.allperiph.tv.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.view.Surface
import com.allperiph.tv.core.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * TV 副屏渲染器：MediaCodec 硬解 → Surface（由 [TvScreenActivity] 提供）。
 *
 * 移植自手机端 `ScreenRenderer`，并保留它那条最重要的纪律：
 * **收流线程只入队，绝不喂解码器** —— `queueInputBuffer` 在输入缓冲用尽时会阻塞，
 * 在收流线程上做会把同一条连接上的音频一起拖住。喂码由专用解码线程完成。
 *
 * 为什么是单例：帧来自媒体收流线程，而 Surface 由 Activity 生命周期提供，
 * 两者不同步；单例让「PC 先连上、用户后打开副屏页」也能工作（打开前的帧直接丢）。
 */
object TvRenderer {

    private const val TAG = "TvRenderer"
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

    /** 当前视频流尺寸；0 = 尚未知。Activity 据此计算「等比适配屏幕」的目标矩形 */
    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    /** 分辨率/编码变化时回调（Activity 重新计算 Surface 尺寸） */
    @Volatile
    var onFormatChanged: ((Int, Int) -> Unit)? = null

    private var decodeThread: Thread? = null
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)

    val droppedNoSurface = AtomicLong(0)
    val skippedCodec = AtomicLong(0)
    private val decodedFrames = AtomicLong(0)

    val attached: Boolean get() = surface != null

    fun attachSurface(s: Surface) {
        detachSurface()
        surface = s
        running = true
        decodeThread = Thread({ decodeLoop() }, "apxtv-screen-dec").apply {
            isDaemon = true
            start()
        }
        Log.i("$TAG Surface 已接入，等待首帧确定编码与分辨率")
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
        val f = TvVideoFrame.parse(body) ?: return
        if (!f.decodable) {
            skippedCodec.incrementAndGet()
            return
        }
        val stream = body.copyOfRange(f.bitstreamOffset, f.bitstreamOffset + f.bitstreamLength)
        // 编码/分辨率变化需要重建解码器 —— 用首帧或变化帧的参数驱动
        if (codec == null || f.width != width || f.height != height ||
            mimeFor(f.codecId) != mime
        ) {
            ensureCodec(f.width, f.height, mimeFor(f.codecId), stream)
            onFormatChanged?.invoke(f.width, f.height)
            return
        }
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
            if (videoWidth > 0) append(" · ${videoWidth}x$videoHeight")
            if (s > 0) append(" · 编码不支持 $s")
            if (n > 0) append(" · 无画面丢弃 $n")
        }
    }

    // ————————————————————————————— 内部 —————————————————————————————

    private fun mimeFor(codecId: Int): String = when (codecId) {
        TvVideoFrame.CODEC_HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        TvVideoFrame.CODEC_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        else -> MediaFormat.MIMETYPE_VIDEO_AVC
    }

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
            videoWidth = w
            videoHeight = h
            decodedFrames.set(0)
            Log.i("$TAG 解码器已启动：$newMime ${w}x$h")
            // 重建时的这一帧不能丢，否则要等到下一个关键帧才有画面
            feed(c, firstFrame)
        } catch (t: Throwable) {
            Log.e("$TAG 解码器创建失败（$newMime ${w}x$h）：${t.message}", t)
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
                    c.queueInputBuffer(inIndex, 0, data.size, decodedFrames.get() * 16_666L, 0)
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
            // 单帧解码失败按帧丢弃，不拆掉整个渲染器（下一帧可能是新关键帧）
            Log.w("$TAG 解码失败：${t.message}")
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
        videoWidth = 0
        videoHeight = 0
    }
}
