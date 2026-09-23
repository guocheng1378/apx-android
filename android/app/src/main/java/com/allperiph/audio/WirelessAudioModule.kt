package com.allperiph.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.allperiph.core.ApxFrame
import com.allperiph.core.ApxStreams
import com.allperiph.core.Log
import com.allperiph.core.MediaOut
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Wi‑Fi 音频模块（**免 root、免 USB**）：走局域网 TCP 双向传 PCM。
 *
 * ```
 * 音箱：PC 系统声（WASAPI loopback）→ 下行 streamId=1 → 本模块 AudioTrack 播放
 * 麦克风：AudioRecord → 上行 streamId=5 → PC 侧渲染/写虚拟声卡
 * ```
 *
 * 格式两端必须一致：**48kHz / 16bit / 立体声**（见 `pc/.../media/media_session.hpp`
 * 的 `kAudioSampleRate` 等常量）。
 *
 * 与 [AudioModule]（USB UAC2）的分工：那个靠 ConfigFS gadget + 裸 ALSA，需要 root
 * 且必须插线；本模块走 Wi‑Fi，是「电脑没有蓝牙/不方便插线」时的音频路径。
 * 两者可并存，各自独立开关。
 *
 * ## 线程纪律（关键）
 * [ApxStreams] 的回调跑在**媒体收流线程**上。`AudioTrack.write` 会阻塞（缓冲区满时
 * 正是它做背压的方式），直接在里面写就会把整条媒体连接的收流拖住 —— 副屏和摄像头
 * 一起遭殃。因此这里只做「入队」，由专用播放线程写 AudioTrack。
 */
class WirelessAudioModule : Module {

    override val id: String = ModuleId.WIFI_AUDIO

    @Volatile
    override var state: ModuleState = ModuleState.IDLE
        private set

    private var lastError: String? = null

    @Volatile
    private var running = false

    // ---- 音箱（PC → 手机扬声器）----
    private var track: AudioTrack? = null
    private var writerThread: Thread? = null
    private val playQueue = ArrayBlockingQueue<ByteArray>(PLAY_QUEUE_CAP)
    private val droppedFrames = AtomicLong(0)

    // ---- 麦克风（手机 → PC）----
    private var micThread: Thread? = null
    private var micOk = false

    /** 音箱下行（AudioTrack）是否初始化成功 —— 供状态上报 PC 端展示 */
    @Volatile var speakerOk = false

    private val audioConsumer = object : ApxStreams.Consumer {
        override fun onFrame(streamId: Int, flags: Int, seq: Int, body: ByteArray) {
            if (streamId != ApxFrame.STREAM_AUDIO) return
            // 只入队，绝不在这里阻塞（见类注释）
            if (!playQueue.offer(body)) {
                playQueue.poll()          // 保新弃旧：落后了宁可丢一小片，也不无限堆积
                droppedFrames.incrementAndGet()
                playQueue.offer(body)
            }
        }
    }

    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING
        lastError = null
        running = true

        // 1) 订阅音箱流（PC 何时连入由承载层决定，这里先挂上）
        ApxStreams.register(ApxFrame.STREAM_AUDIO, audioConsumer)
        val spkOk = startSpeaker()
        speakerOk = spkOk
        if (!spkOk) Log.w(TAG, "音箱播放初始化失败，本次仅尝试麦克风方向")

        // 2) 启动麦克风上行
        micOk = startMic()
        if (!micOk) Log.w(TAG, "麦克风采集初始化失败（缺 RECORD_AUDIO 权限？）")

        state = when {
            spkOk && micOk -> ModuleState.RUNNING
            spkOk || micOk -> ModuleState.DEGRADED     // 单向可用，如实标注
            else -> {
                lastError = "音箱与麦克风都初始化失败"
                ModuleState.ERROR
            }
        }
        Log.i(TAG, "Wi‑Fi 音频已启动：音箱=${if (spkOk) "开" else "关"} 麦克风=${if (micOk) "开" else "关"}")
    }

    override fun stop() {
        state = ModuleState.STOPPING
        running = false
        ApxStreams.unregister(ApxFrame.STREAM_AUDIO, audioConsumer)
        playQueue.clear()

        // 先中断 join，再释放 AudioTrack/AudioRecord —— 反过来会出现"写已释放对象"
        joinAll()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        micOk = false
        state = ModuleState.STOPPED
        droppedFrames.set(0)
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> {
            val d = droppedFrames.get()
            if (d > 0) "Wi‑Fi 音频运行中 · 丢片 $d" else "Wi‑Fi 音频运行中 · 双向 48kHz/16bit"
        }
        ModuleState.DEGRADED -> "Wi‑Fi 音频降级 · ${if (micOk) "仅麦克风上行" else "仅有音箱下行"}"
        ModuleState.ERROR -> lastError ?: "Wi‑Fi 音频错误"
        ModuleState.STARTING -> "Wi‑Fi 音频启动中"
        ModuleState.STOPPING -> "Wi‑Fi 音频停止中"
        else -> "Wi‑Fi 音频已停止"
    }

    /** 不占协议功能位（与 Gadget / Wi‑Fi 控制同），能力由实际方向体现 */
    override fun maskBits(): Long = 0L

    /**
     * 手机侧 Wi‑Fi 音频模块状态上报帧（tag 'a'），经控制面发回 PC，
     * 让 PC 面板能「看到手机播放端」—— 区分「没送到手机」与「送到了但手机没播」。
     * 帧体：`[0]='a' [1]=ModuleState 编码(0..6) [2]=spkOk [3]=micOk [4..7]=droppedFrames(u32 LE)`
     */
    fun statusReport(): ByteArray {
        val st = when (state) {
            ModuleState.IDLE -> 0
            ModuleState.STARTING -> 1
            ModuleState.RUNNING -> 2
            ModuleState.DEGRADED -> 3
            ModuleState.ERROR -> 4
            ModuleState.STOPPING -> 5
            else -> 6   // STOPPED
        }
        val drop = droppedFrames.get()
        return byteArrayOf(
            'a'.code.toByte(),
            st.toByte(),
            if (speakerOk) 1 else 0,
            if (micOk) 1 else 0,
            (drop and 0xFF).toByte(),
            ((drop ushr 8) and 0xFF).toByte(),
            ((drop ushr 16) and 0xFF).toByte(),
            ((drop ushr 24) and 0xFF).toByte(),
        )
    }

    // ————————————————————————————— 音箱（下行） —————————————————————————————

    private fun startSpeaker(): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(RATE, OUT_CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize 失败：$minBuf")
            return false
        }
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(RATE)
                        .setChannelMask(OUT_CHANNEL)
                        .build(),
                )
                // 4 倍最小缓冲 ≈ 80ms：TCP + 无线有抖动，缓冲太小会频繁断音；
                // 太大又会让"手机出声"明显滞后。这个值是延迟与稳定的折中。
                .setBufferSizeInBytes(maxOf(minBuf * 4, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t.release() }
                return false
            }
            track = t
            t.play()
            writerThread = thread(start = true, name = "apx-wifi-audio-tx") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val tt = track ?: return@thread
                while (running) {
                    val chunk = try {
                        playQueue.poll(200, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        break
                    } ?: continue
                    // 阻塞式写：AudioTrack 缓冲满时正是它做背压的手段
                    runCatching { tt.write(chunk, 0, chunk.size) }
                        .onFailure { Log.w(TAG, "AudioTrack 写入失败：${it.message}") }
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "音箱初始化异常：${t.message}")
            runCatching { track?.release() }
            track = null
            false
        }
    }

    // ————————————————————————————— 麦克风（上行） —————————————————————————————

    private fun startMic(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(RATE, IN_CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize 失败：$minBuf")
            return false
        }
        // 先同步探测一次能否初始化：失败要能在 start() 里立刻如实降级，
        // 而不是起个线程后静默失败（那样 UI 会显示"运行中"在骗人）。
        val probe = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, IN_CHANNEL, ENCODING,
                maxOf(minBuf, FRAME_BYTES * 8),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord 构造异常：${t.message}")
            null
        }
        val usable = probe != null && probe.state == AudioRecord.STATE_INITIALIZED
        runCatching { probe?.release() }
        if (!usable) return false

        micThread = thread(start = true, name = "apx-wifi-audio-rx") {
            var rec: AudioRecord? = null
            val fx = mutableListOf<android.media.audiofx.AudioEffect>()
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, RATE, IN_CHANNEL, ENCODING,
                    maxOf(minBuf, FRAME_BYTES * 8),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@thread
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val sid = rec.audioSessionId
                // 与 USB 路径同样开三件套：否则手机自己放出来的声音会被麦克风回采
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    runCatching {
                        android.media.audiofx.AcousticEchoCanceler.create(sid)
                            ?.let { it.enabled = true; fx.add(it) }
                    }
                }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    runCatching {
                        android.media.audiofx.NoiseSuppressor.create(sid)
                            ?.let { it.enabled = true; fx.add(it) }
                    }
                }
                if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    runCatching {
                        android.media.audiofx.AutomaticGainControl.create(sid)
                            ?.let { it.enabled = true; fx.add(it) }
                    }
                }
                rec.startRecording()
                val buf = ByteArray(FRAME_BYTES)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // MediaOut 只入队不碰 socket（见 core/MediaOut.kt）；未连入时返回 false，如实丢弃
                    MediaOut.mic(buf, n)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "麦克风线程异常：${t.message}")
            } finally {
                fx.forEach { e -> runCatching { e.release() } }
                runCatching { rec?.stop() }
                runCatching { rec?.release() }
            }
        }
        return true
    }

    private fun joinAll() {
        val self = Thread.currentThread()
        for (t in listOf(writerThread, micThread)) {
            if (t != null && t.isAlive && t !== self) {
                runCatching { t.join(500) }
            }
        }
        writerThread = null
        micThread = null
    }

    companion object {
        private const val TAG = "WirelessAudio"

        /** 与 PC 侧 `media_session.hpp` 的 kAudioSampleRate/kAudioChannels 必须一致 */
        private const val RATE = 48000
        private val IN_CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        private val OUT_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 10ms 一片：48000 × 2ch × 2B / 100 = 1920 字节 */
        private const val FRAME_BYTES = RATE * 2 * 2 / 100

        /** 播放队列容量：约 0.5 秒，够吸收无线抖动又不至于积出可感延迟 */
        private const val PLAY_QUEUE_CAP = 50
    }
}
