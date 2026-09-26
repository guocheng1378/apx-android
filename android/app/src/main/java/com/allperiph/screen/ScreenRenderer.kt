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
 * （会把同一连接上的音频拖住），所以这里只把位流**入队**，
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

    /** 当前视频流尺寸（触摸坐标按画面比例映射必读；0 = 尚未知） */
    @Volatile
    var videoWidth = 0
        private set
    @Volatile
    var videoHeight = 0
        private set
    private var decodeThread: Thread? = null

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)

    /** 没有 Surface 时被丢弃的帧数（用户还没打开副屏页） */
    val droppedNoSurface = AtomicLong(0)

    /** 解码器不认的编码（RAW_LZ4 / MJPEG）被跳过的帧数 */
    val skippedCodec = AtomicLong(0)

    private val decodedFrames = AtomicLong(0)

    /** 因超过输入缓冲被跳过的帧数（配合 KEY_MAX_INPUT_SIZE，正常应恒为 0） */
    val oversizedFrames = AtomicLong(0)

    /**
     * 已判定"本机解不了"的 (mime,宽,高)。命中后**不再尝试重建解码器** ——
     * 以前每帧都会 createDecoderByType + 打异常，全部发生在**收流线程**上，
     * 一条解不了的流能把整条媒体链路拖死（TV 端同问题，已同改）。
     */
    private var failedKey: String? = null

    /** 实际选中的解码器名（仅日志用） */
    private var codecName: String = ""

    /** 请求对端立刻出关键帧（控制帧 0x06）；未接时只记日志 */
    @Volatile
    var requestKeyFrame: (() -> Unit)? = null

    // 周期统计：原来只有"已解码 N 帧"进 UI，没有周期日志，真机卡顿没法量
    private var statAtMs = 0L
    private var statFrames = 0L

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
        // 已判定这个流在本机解不了：直接丢，**不再每帧试图重建解码器**
        if (codec == null && failedKey == "${mimeFor(f.codecId)}/${f.width}x${f.height}") {
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
            val o = oversizedFrames.get()
            if (o > 0) append(" · 过大跳过 $o")
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
        val key = "$newMime/${w}x$h"
        // 已经判定过这个流解不了 → 直接返回，别每帧重试（见 failedKey 注释）
        if (failedKey == key) return
        try {
            val format = MediaFormat.createVideoFormat(newMime, w, h).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                // ★ 不设它时输入缓冲默认偏小，一到关键帧就 BufferOverflow →
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
            Log.i(TAG, "解码器已启动：$newMime ${w}x$h（$codecName）")
            // 重建时的这一帧不能丢，否则要等到下一个关键帧才有画面
            feed(c, firstFrame)
        } catch (t: Throwable) {
            Log.e(TAG, "解码器创建失败（$newMime ${w}x$h）：${t.message}")
            failedKey = key
            releaseCodec()
        }
    }

    /**
     * 优先挑**硬件**解码器。
     *
     * `MediaCodec.createDecoderByType()` 只保证"第一个匹配"，有些 ROM 会把软解排在前面 ——
     * 拿软解去解 1080p 就是 CPU 打满、画面跟着卡。API 29+ 用 `isHardwareAccelerated`，
     * 更老的系统按名字启发式判断（`OMX.google.*` / `c2.android.*` 都是软解）。
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
            if (!isHw(pick)) Log.w(TAG, "没有可用的硬解解码器，只能软解：${pick.name}")
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
                    // ★ 容量先校验：`buf.put(data)` 一把塞时，大关键帧会抛 BufferOverflowException，
                    //   被外层 catch 当成"解码失败"整帧丢 —— 观感是"周期性花屏/顿一下"。
                    //   缓冲本身已在 ensureCodec 里放大（KEY_MAX_INPUT_SIZE）；真装不下就如实跳过，
                    //   绝不像素级把一段码流拆到两个输入缓冲（那需要 PARTIAL_FRAME 语义）。
                    if (data.size > buf.remaining()) {
                        oversizedFrames.incrementAndGet()
                        c.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        Log.w(TAG, "帧过大（${data.size}B > 输入缓冲 ${buf.remaining()}B），已跳过并请求关键帧")
                        requestKeyFrame?.invoke()
                        return
                    }
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
            logStatsIfDue()
        } catch (t: Throwable) {
            // 解码失败按帧丢弃，不把整个渲染器拆掉（下一帧可能是新关键帧）
            Log.w(TAG, "解码失败：${t.message}")
        }
    }

    /**
     * 每 5 秒一行 fps / 丢帧统计 —— `adb logcat -s ScreenActivity` 里能直接读。
     * （原来只有"已解码 N 帧"进 UI，真机卡顿没有可量的日志。）
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
            TAG,
            "副屏统计：${"%.1f".format(fps)} fps · 队列积压 ${queue.size} · " +
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
