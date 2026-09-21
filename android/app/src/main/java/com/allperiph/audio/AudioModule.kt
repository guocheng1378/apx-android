package com.allperiph.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.allperiph.core.AlsaPcm
import com.allperiph.core.AudioConst
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import java.io.File
import kotlin.concurrent.thread

/**
 * 【音频】UAC2 声卡的搬运：把手机麦克风接到 PC，把 PC 的声音放到手机扬声器。
 *
 * 依据 `docs/PROTOCOL.md §6`：音频**不走自定义协议**，而是直接用 USB Audio Class 2
 * 标准类 —— PC 端免驱（Windows 原生认成声卡）。App 只做 **ALSA ↔ AudioRecord/AudioTrack**
 * 的字节搬运，不参与编解码。
 *
 * 数据方向（依据实测的 `/proc/asound/pcm`：`01-00: UAC2 PCM : UAC2 PCM : playback 1 : capture 1`）：
 * - 手机麦克风 → PC：`AudioRecord` 采集 → 写 ALSA **playback**（`pcmC?D0p`）
 * - PC → 手机扬声器：读 ALSA **capture**（`pcmC?D0c`）→ `AudioTrack` 播放
 *
 * 声卡号**运行时定位**（[locateCard]），绝不硬编码 `hw:0,0`：卡号随内核枚举顺序变化。
 *
 * 前置条件：UAC2 已随 gadget 挂载（`f_uac2` 建卡），且 PCM 节点已被 root 放开权限。
 *
 * v1.13：采集侧挂硬件 AEC/降噪/自动增益（设备支持才启用），收发线程提实时优先级；
 * 播放侧启用低延迟通路（PERFORMANCE_MODE_LOW_LATENCY）并收紧缓冲。
 */
class AudioModule(private val app: Context) : Module {

    override val id: String = ModuleId.AUDIO

    @Volatile
    override var state: ModuleState = ModuleState.IDLE
        private set

    @Volatile
    private var running = false

    private var micToPc: Thread? = null
    private var pcToSpk: Thread? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var cardIndex: Int = -1

    /** 音频走 USB 标准类，不占 §2.9 的模块位 */
    override fun maskBits(): Long = 0L

    // ————————————————————————————— 生命周期 —————————————————————————————

    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING
        lastError = null

        val card = locateCard()
        if (card == null) {
            state = ModuleState.ERROR
            lastError = "未找到 ${AudioConst.ALSA_CARD_KEYWORD} 声卡（UAC2 是否已挂载？）"
            Log.w(TAG, lastError!!)
            return
        }
        cardIndex = card
        Log.i(TAG, "定位到 UAC2 声卡：card=$card")

        running = true
        val txOk = startMicToPc(card)
        val rxOk = startPcToSpeaker(card)

        state = when {
            txOk && rxOk -> ModuleState.RUNNING
            txOk || rxOk -> ModuleState.DEGRADED
            else -> ModuleState.ERROR
        }
        if (state == ModuleState.ERROR) lastError = "两条音频流都无法打开"
        Log.i(TAG, "音频启动：mic→PC=$txOk  speaker←PC=$rxOk  state=$state")
    }

    override fun stop() {
        if (state == ModuleState.STOPPED || state == ModuleState.IDLE) {
            state = ModuleState.STOPPED
            return
        }
        state = ModuleState.STOPPING
        running = false
        // 线程内是阻塞 read/write，join 带超时避免卡住逆序停止
        runCatching { micToPc?.join(800) }
        runCatching { pcToSpk?.join(800) }
        micToPc = null
        pcToSpk = null
        state = ModuleState.STOPPED
        Log.i(TAG, "音频模块已停止")
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> "音频 双向 48k/16bit/立体声（card $cardIndex）"
        ModuleState.DEGRADED -> "音频 单向（另一方向未打开）"
        ModuleState.ERROR -> lastError ?: "音频错误"
        else -> "音频未启动"
    }

    // ————————————————————————————— 内部 —————————————————————————————

    /**
     * 在 `/proc/asound/cards` 中按关键字定位声卡号。
     *
     * 该文件行形如：` 1 [UAC2Gadget     ]: UAC2_Gadget - UAC2_Gadget`，
     * 取方括号前的数字为 card 号。
     */
    private fun locateCard(): Int? {
        val text = runCatching { File(CARDS).readText() }.getOrNull() ?: return null
        val re = Regex("""^\s*(\d+)\s*\[([^\]]+)\]""", RegexOption.MULTILINE)
        for (m in re.findAll(text)) {
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            if (m.groupValues[2].contains(AudioConst.ALSA_CARD_KEYWORD, ignoreCase = true)) {
                return idx
            }
        }
        return null
    }

    /** 手机麦克风 → PC：`AudioRecord` 采集，写入 ALSA playback 流 */
    private fun startMicToPc(card: Int): Boolean {
        val path = "/dev/snd/pcmC${card}D0p"
        val stream = AlsaPcm.open(path, playback = true) ?: return false

        val minBuf = AudioRecord.getMinBufferSize(RATE, IN_CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize 失败：$minBuf")
            stream.close()
            return false
        }

        micToPc = thread(start = true, name = "apx-audio-tx") {
            var rec: AudioRecord? = null
            // v1.13：硬件音效（AEC/NS/AGC）句柄，finally 中统一释放
            val fx = mutableListOf<android.media.audiofx.AudioEffect>()
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    IN_CHANNEL,
                    ENCODING,
                    maxOf(minBuf, FRAME_BYTES * 8),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord 初始化失败（缺 RECORD_AUDIO 权限？）")
                    return@thread
                }
                // v1.13：音频线程提实时优先级，降低调度抖动
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                // v1.13：回声消除 / 降噪 / 自动增益（设备支持才启用，失败静默降级）
                val sid = rec.audioSessionId
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
                    val rc = stream.write(if (n == buf.size) buf else buf.copyOf(n))
                    if (rc < 0) Log.w(TAG, "ALSA playback 写入失败 rc=$rc")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "mic→PC 线程异常：${t.message}")
            } finally {
                fx.forEach { e -> runCatching { e.release() } }
                runCatching { rec?.stop() }
                runCatching { rec?.release() }
                stream.close()
            }
        }
        return true
    }

    /** PC → 手机扬声器：读 ALSA capture 流，经 `AudioTrack` 播放 */
    private fun startPcToSpeaker(card: Int): Boolean {
        val path = "/dev/snd/pcmC${card}D0c"
        val stream = AlsaPcm.open(path, playback = false) ?: return false

        pcToSpk = thread(start = true, name = "apx-audio-rx") {
            var track: AudioTrack? = null
            try {
                // v1.13：播放线程同提实时优先级
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val minBuf = AudioTrack.getMinBufferSize(RATE, OUT_CHANNEL, ENCODING)
                track = AudioTrack.Builder()
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
                    // v1.13：缓冲收紧至 minBuf×2 / 40ms 帧×4，配合低延迟通路
                    .setBufferSizeInBytes(maxOf(minBuf * 2, FRAME_BYTES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    // v1.13：低延迟输出通路（fast mixer，设备支持时生效）
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                track.play()
                val buf = ByteArray(FRAME_BYTES)
                while (running) {
                    val n = stream.read(buf)
                    if (n > 0) track.write(buf, 0, n)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "PC→speaker 线程异常：${t.message}")
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                stream.close()
            }
        }
        return true
    }

    companion object {
        private const val TAG = "AudioModule"

        private const val CARDS = "/proc/asound/cards"

        private const val RATE = AudioConst.SAMPLE_RATE
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val IN_CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        private const val OUT_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO

        /** 10ms 一帧：48000Hz × 2ch × 2B ÷ 100 */
        private const val FRAME_BYTES = 48000 * 2 * 2 / 100
    }
}
