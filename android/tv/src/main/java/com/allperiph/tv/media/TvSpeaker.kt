package com.allperiph.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.allperiph.shared.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * TV 端「音箱」：把电脑的声音放到电视/音响上播放。
 *
 * 格式与 PC 侧 `media_session.hpp` 的常量必须一致：**48kHz / 16bit / 立体声**
 * （与手机端 `WirelessAudioModule` 相同）。
 *
 * ## 线程纪律（关键，别删）
 * 帧来自**媒体收流线程**；`AudioTrack.write` 在缓冲满时会阻塞（那正是它的背压方式），
 * 直接在收流线程里写会把整条媒体连接拖住 —— 副屏画面也一起卡。
 * 所以这里只入队，由专用播放线程写 AudioTrack。
 *
 * ## 生命周期（v198 修）
 * 旧实现只有 `TvScreenActivity.surfaceCreated` 一处调 [start]，于是**不开副屏页时
 * 每一帧音频都被静默丢弃**（用户只看到"音箱开了没声音"）。现在改为收到音频帧时懒启动。
 */
object TvSpeaker {

    private const val TAG = "TvSpeaker"

    private const val RATE = 48000
    private const val OUT_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** 10ms 一片：48000 × 2ch × 2B / 100 = 1920 字节 */
    private const val FRAME_BYTES = RATE * 2 * 2 / 100

    /** 播放队列容量：约 0.5s，够吸收无线抖动又不积出可感延迟 */
    private const val PLAY_QUEUE_CAP = 50

    /** 启动失败后的冷却：别每来一帧就去建一次 AudioTrack */
    private const val RETRY_COOLDOWN_MS = 2_000L

    private val lock = Any()

    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val playQueue = ArrayBlockingQueue<ByteArray>(PLAY_QUEUE_CAP)
    private val dropped = AtomicLong(0)
    private val played = AtomicLong(0)

    @Volatile
    private var running = false

    @Volatile
    var ok = false
        private set

    /**
     * 代际号：每次 [start] 递增。播放线程凭它判断"自己是不是还属于当前这一代" ——
     * 没有它时，`stop()` 超时放弃的旧线程会一直活着，并往已 `release()` 的 track 里写
     * （靠 runCatching 吞 `IllegalStateException`），或把新 start 的 running 又置回 true。
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var lastFailMs = 0L

    /**
     * 是否允许出声。默认开 —— 「音箱」是独立功能，不依赖副屏页；
     * 但副屏页退到后台时（[com.allperiph.tv.ui.TvScreenActivity.onPause]）要关掉，
     * 否则用户已经看不到画面了，电脑的声音还在电视上响。
     */
    @Volatile
    private var enabled = true

    /** 副屏页可见性变化时调用：false = 立刻停播并拒收新帧 */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) stop()
    }

    fun start(): Boolean = synchronized(lock) { startLocked() }

    private fun startLocked(): Boolean {
        if (running && track != null) return true
        // 先把上一代收干净：否则两条播放线程会同时写同一个 track
        stopLocked()
        val minBuf = AudioTrack.getMinBufferSize(RATE, OUT_CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w("$TAG AudioTrack.getMinBufferSize 失败：$minBuf")
            return false
        }
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(OUT_CHANNEL)
                        .setEncoding(ENCODING)
                        .build()
                )
                // 4× 最小缓冲 ≈ 80ms：太小会因无线抖动频繁断音，太大会明显滞后
                .setBufferSizeInBytes(maxOf(minBuf * 4, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            Log.w("$TAG 音箱初始化异常：${e.message}")
            ok = false
            return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { t.release() }
            ok = false
            return false
        }
        track = t
        running = true
        ok = true
        val g = ++generation
        t.play()
        playThread = Thread({ playLoop(t, g) }, "apxtv-spk-$g").apply {
            isDaemon = true
            start()
        }
        Log.i("$TAG 音箱已启动：${RATE}Hz 立体声 16bit")
        return true
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        generation++ // 作废当前这一代：旧线程据此退出，不会复活
        playQueue.clear()
        val self = Thread.currentThread()
        val pt = playThread
        playThread = null
        if (pt != null && pt.isAlive && pt !== self) {
            // interrupt 让阻塞在 poll() 的线程立刻醒；join 只是等它收尾，超时不放弃线程
            pt.interrupt()
            runCatching { pt.join(500) }
        }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        ok = false
    }

    /** 收流线程调用：只入队；未启动时按需懒启动 */
    fun submit(body: ByteArray) {
        if (body.isEmpty()) return
        if (!enabled) { dropped.incrementAndGet(); return }
        if (!running) {
            // 「音箱」是用户主动打开的功能，收到音频帧就该出声 —— 不能依赖"先打开副屏页"。
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFailMs < RETRY_COOLDOWN_MS) {
                dropped.incrementAndGet()
                return
            }
            if (!start()) {
                lastFailMs = now
                val d = dropped.incrementAndGet()
                if (d % 200 == 1L) Log.w("$TAG 音箱未启动，音频帧丢弃中（累计 $d）")
                return
            }
        }
        if (!playQueue.offer(body)) {
            playQueue.poll()          // 保新弃旧：声音宁可跳过也不要积出延迟
            dropped.incrementAndGet()
            playQueue.offer(body)
        }
    }

    private fun playLoop(t: AudioTrack, g: Int) {
        while (running && g == generation) {
            val chunk = try {
                playQueue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            writeFully(t, chunk)
        }
    }

    /**
     * `AudioTrack.write` 返回的是**实际写入字节数**（可能小于请求量）或负的错误码。
     * 旧实现只看"没抛异常"就 `played++` —— 短写时剩余数据被直接丢掉（断续爆音），
     * `ERROR_DEAD_OBJECT` 之类的错误码也被当成成功统计。
     */
    private fun writeFully(t: AudioTrack, chunk: ByteArray) {
        var off = 0
        while (off < chunk.size) {
            val n = try {
                t.write(chunk, off, chunk.size - off)
            } catch (e: Throwable) {
                Log.w("$TAG AudioTrack 写入失败：${e.message}")
                return
            }
            if (n <= 0) {
                val why = when (n) {
                    AudioTrack.ERROR_DEAD_OBJECT -> "track 已失效"
                    AudioTrack.ERROR_INVALID_OPERATION -> "状态不对"
                    AudioTrack.ERROR_BAD_VALUE -> "参数不对"
                    else -> "错误码 $n"
                }
                Log.w("$TAG AudioTrack.write 返回 $n（$why）")
                return
            }
            off += n
        }
        played.incrementAndGet()
    }

    fun stats(): String {
        val p = played.get()
        val d = dropped.get()
        return buildString {
            append(if (ok) "音箱：已播放 $p 片" else "音箱：未启动")
            if (d > 0) append(" · 丢弃 $d")
        }
    }
}
