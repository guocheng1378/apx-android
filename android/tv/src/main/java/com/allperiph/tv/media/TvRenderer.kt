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

    /** 因超过输入缓冲被跳过的帧数（配合 KEY_MAX_INPUT_SIZE，正常应恒为 0） */
    val oversizedFrames = AtomicLong(0)

    /**
     * 已判定"本机解不了"的 (mime,宽,高)。命中后**不再尝试重建解码器** ——
     * 以前每帧都会 createDecoderByType + 打异常堆栈，全部发生在**收流线程**上，
     * 一条解不了的流能把整条媒体链路（含音频）拖死。
     */
    private var failedKey: String? = null

    /** 实际选中的解码器名（仅日志用） */
    private var codecName: String = ""

    /** 请求对端立刻出关键帧（控制帧 0x06）；未接时只记日志 */
    @Volatile
    var requestKeyFrame: (() -> Unit)? = null

    // 周期统计：TV 端原先**只有事件日志、没有 fps/丢帧**，真机"卡"根本没法量
    private var statAtMs = 0L
    private var statFrames = 0L

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
        // 已判定这个流在本机解不了：直接丢，**不再每帧试图重建解码器**
        if (codec == null && failedKey == "${mimeFor(f.codecId)}/${f.width}x${f.height}") {
            skippedCodec.incrementAndGet()
            return
        }
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
            val o = oversizedFrames.get()
            if (o > 0) append(" · 过大跳过 $o")
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
        val key = "$newMime/${w}x$h"
        // 已经判定过这个流解不了 → 直接返回，别每帧重试（见 failedKey 注释）
        if (failedKey == key) return
        try {
            val format = MediaFormat.createVideoFormat(newMime, w, h).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                // ★ 关键：不设它时输入缓冲默认很小，一到关键帧就 BufferOverflow →
                //   被当成"解码失败"整帧丢弃 → 观感是"周期性花屏/顿一下"。
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            }
            val c = createPreferredDecoder(newMime).apply {
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
            failedKey = null
            statAtMs = 0L
            Log.i("$TAG 解码器已启动：$newMime ${w}x$h（$codecName）")
            // 重建时的这一帧不能丢，否则要等到下一个关键帧才有画面
            feed(c, firstFrame)
        } catch (t: Throwable) {
            Log.e("$TAG 解码器创建失败（$newMime ${w}x$h）：${t.message}", t)
            failedKey = key
            releaseCodec()
        }
    }

    /**
     * 优先挑**硬件**解码器。
     *
     * `MediaCodec.createDecoderByType()` 只保证"第一个匹配" —— 有些 ROM（尤其老盒子）
     * 会把软解排在前面，拿到软解去解 1080p 就是 CPU 打满、画面跟着卡。
     * API 29+ 可用 `isHardwareAccelerated`；API 25 这类老系统只能按名字启发式判断
     * （`OMX.google.*` / `c2.android.*` / 带 software 字样的都是软解）。
     */
    private fun createPreferredDecoder(mimeType: String): MediaCodec {
        val all = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        } catch (_: Throwable) {
            emptyList()
        }
        fun isHw(ci: android.media.MediaCodecInfo): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= 29) return ci.isHardwareAccelerated
            val n = ci.name.lowercase()
            return !(n.startsWith("omx.google") || n.startsWith("c2.android") ||
                    n.contains("software") || n.contains(".sw."))
        }
        val pick = all.firstOrNull { isHw(it) } ?: all.firstOrNull()
        return if (pick != null) {
            codecName = pick.name + if (isHw(pick)) "（硬解）" else "（软解回退）"
            if (!isHw(pick)) Log.w("$TAG 没有可用的硬解解码器，只能软解：${pick.name}")
            MediaCodec.createByCodecName(pick.name)
        } else {
            codecName = "系统默认"
            MediaCodec.createDecoderByType(mimeType)
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
                    // ★ 容量先校验，别让它抛异常：原来是 `buf.put(data)` 一把塞，
                    //   某个大 I 帧超过输入缓冲容量时抛 BufferOverflowException，
                    //   被外层 catch 当成"解码失败"整帧丢弃 —— 表现是**每个关键帧周期性地花屏/顿一下**。
                    //   正确做法是"缓冲要足够大"（见 ensureCodec 的 KEY_MAX_INPUT_SIZE）；
                    //   真装不下时**如实跳过这帧并请求关键帧**，绝不把一段码流拆到两个输入缓冲
                    //   （那需要 PARTIAL_FRAME 语义，拆错比丢一帧更糟）。
                    if (data.size > buf.remaining()) {
                        oversizedFrames.incrementAndGet()
                        c.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        Log.w("$TAG 帧过大（${data.size}B > 输入缓冲 ${buf.remaining()}B），已跳过并请求关键帧")
                        requestKeyFrame?.invoke()
                        return
                    }
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
            logStatsIfDue()
        } catch (t: Throwable) {
            // 单帧解码失败按帧丢弃，不拆掉整个渲染器（下一帧可能是新关键帧）
            Log.w("$TAG 解码失败：${t.message}")
        }
    }

    /**
     * 每 5 秒打一行 fps / 丢帧统计。
     *
     * 加它的原因：TV 端原先**只有事件日志**（"解码器已启动 / 创建失败"），
     * 真机"副屏很卡"这件事在日志里完全量不出来 —— 只能盯着屏幕上的状态行看。
     * 现在 `adb logcat -s ApxTv` 就能直接读 fps 与各类丢弃计数。
     */
    private fun logStatsIfDue() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (statAtMs == 0L) {
            statAtMs = now; statFrames = decodedFrames.get(); return
        }
        if (now - statAtMs < 5_000) return
        val secs = (now - statAtMs) / 1000.0
        val fps = (decodedFrames.get() - statFrames) / secs
        Log.i(
            "$TAG 副屏统计：${"%.1f".format(fps)} fps · 队列积压 ${queue.size} · " +
                    "已解码 ${decodedFrames.get()} · 过大跳过 ${oversizedFrames.get()} · " +
                    "编码不支持 ${skippedCodec.get()} · 无画面丢弃 ${droppedNoSurface.get()}"
        )
        statAtMs = now
        statFrames = decodedFrames.get()
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
