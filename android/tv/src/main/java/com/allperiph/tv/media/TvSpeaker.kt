package com.allperiph.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.allperiph.tv.core.Log
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

    fun start(): Boolean {
        if (running) return true
        return try {
            val minBuf = AudioTrack.getMinBufferSize(RATE, OUT_CHANNEL, ENCODING)
            if (minBuf <= 0) {
                Log.w("$TAG AudioTrack.getMinBufferSize 失败：$minBuf")
                return false
            }
            val t = AudioTrack.Builder()
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
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t.release() }
                return false
            }
            track = t
            running = true
            ok = true
            t.play()
            playThread = Thread({ playLoop(t) }, "apxtv-spk").apply {
                isDaemon = true
                start()
            }
            Log.i("$TAG 音箱已启动：${RATE}Hz 立体声 16bit")
            true
        } catch (t: Throwable) {
            Log.w("$TAG 音箱初始化异常：${t.message}")
            ok = false
            false
        }
    }

    fun stop() {
        running = false
        playQueue.clear()
        val self = Thread.currentThread()
        playThread?.takeIf { it.isAlive && it !== self }?.let { runCatching { it.join(500) } }
        playThread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        ok = false
    }

    /** 收流线程调用：只入队 */
    fun submit(body: ByteArray) {
        if (!running || body.isEmpty()) return
        if (!playQueue.offer(body)) {
            playQueue.poll()          // 保新弃旧：声音宁可跳过也不要积出延迟
            dropped.incrementAndGet()
            playQueue.offer(body)
        }
    }

    private fun playLoop(t: AudioTrack) {
        while (running) {
            val chunk = try {
                playQueue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            // 阻塞式写：缓冲满时正是它做背压的手段
            runCatching { t.write(chunk, 0, chunk.size) }
                .onSuccess { played.incrementAndGet() }
                .onFailure { Log.w("$TAG AudioTrack 写入失败：${it.message}") }
        }
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
