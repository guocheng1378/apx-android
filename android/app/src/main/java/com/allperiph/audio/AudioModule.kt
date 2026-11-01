package com.allperiph.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import com.allperiph.core.AlsaPcm
import com.allperiph.core.AudioConst
import com.allperiph.shared.util.Log
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
 * 声卡号与 PCM 设备号都**运行时定位**（[locateCard] / [locateDevice]），
 * 绝不硬编码 `hw:0,0`：两者都随内核枚举顺序变化，换机型会取到别的设备。
 *
 * 前置条件：UAC2 已随 gadget 挂载（`f_uac2` 建卡），且 PCM 节点已被 root 放开权限。
 *
 * v1.13：采集侧挂硬件 AEC/降噪/自动增益（设备支持才启用），收发线程提实时优先级；
 * 播放侧启用低延迟通路（PERFORMANCE_MODE_LOW_LATENCY）并收紧缓冲。
 *
 * v218 修复（USB 模式锁 2 个大核全频满载）：ALSA 两个 fd 都是 O_NONBLOCK，
 * native 侧读到 EAGAIN 原本会零等待自旋 —— 现 native 立即返回 / 节流重试，
 * 下面两条搬运循环在无数据、采集异常、写入被限流时都必须**节流休眠**，
 * 否则一条线程就是一个被钉满的大核。
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

    // ---- 方向子开关（修复⑭：与 Wi‑Fi 音频同款）：音箱（PC→手机）/ 麦克风（手机→PC）
    // 可在设置页分别启停、持久化记忆；start 读初值，apply* 运行中热切换。 ----
    @Volatile
    private var spkOn = true
    @Volatile
    private var micOn = true
    @Volatile
    private var micRun = false
    @Volatile
    private var spkRun = false
    private var micOk = false
    private var spkOk = false

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
        micOn = isMicOn(app)
        spkOn = isSpeakerOn(app)

        val txOk = if (micOn) { micOk = startMicToPc(card); micOk } else { micOk = false; false }
        val rxOk = if (spkOn) { spkOk = startPcToSpeaker(card); spkOk } else { spkOk = false; false }
        recomputeState(txOk, rxOk)
        Log.i(TAG, "音频启动：mic→PC=$txOk  speaker←PC=$rxOk  state=$state")
    }

    override fun stop() {
        if (state == ModuleState.STOPPED || state == ModuleState.IDLE) {
            state = ModuleState.STOPPED
            return
        }
        state = ModuleState.STOPPING
        running = false
        micRun = false
        spkRun = false
        runCatching { micToPc?.join(800) }
        runCatching { pcToSpk?.join(800) }
        micToPc = null
        pcToSpk = null
        state = ModuleState.STOPPED
        Log.i(TAG, "音频模块已停止")
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> "音频运行中 · 双向 48kHz/16bit"
        ModuleState.DEGRADED -> if (!micOn && !spkOn) "音频待命 · 音箱/麦克风均已关闭"
        else "音频单向 · 音箱${if (spkOk) "开" else "关"} 麦克风${if (micOk) "开" else "关"}"
        ModuleState.ERROR -> lastError ?: "音频错误"
        else -> when (state) {
            ModuleState.STARTING -> "音频启动中"
            ModuleState.STOPPED, ModuleState.IDLE -> "音频已停止"
            else -> state.name
        }
    }

    // ————————————————————————————— 方向热切换（修复⑭） —————————————————————————————

    /** 运行中热切换：扬声器方向（PC → 手机）。模块未运行时只落盘，下次 start 生效。 */
    fun applySpeaker(ctx: Context, on: Boolean) {
        setSpeakerPref(ctx, on)
        spkOn = on
        if (!running) return
        if (on && pcToSpk?.isAlive != true) {
            val card = if (cardIndex >= 0) cardIndex else locateCard()?.also { cardIndex = it } ?: -1
            spkOk = card >= 0 && startPcToSpeaker(card)
            recomputeState(micOk, spkOk)
        } else if (!on && spkRun) {
            spkRun = false
            pcToSpk?.let { runCatching { it.join(800) } }
            pcToSpk = null
            spkOk = false
            recomputeState(micOk, false)
        }
    }

    /** 运行中热切换：麦克风方向（手机 → PC） */
    fun applyMic(ctx: Context, on: Boolean) {
        setMicPref(ctx, on)
        micOn = on
        if (!running) return
        if (on && micToPc?.isAlive != true) {
            val card = if (cardIndex >= 0) cardIndex else locateCard()?.also { cardIndex = it } ?: -1
            micOk = card >= 0 && startMicToPc(card)
            recomputeState(micOk, spkOk)
        } else if (!on && micRun) {
            micRun = false
            micToPc?.let { runCatching { it.join(800) } }
            micToPc = null
            micOk = false
            recomputeState(false, spkOk)
        }
    }

    private fun recomputeState(txOk: Boolean, rxOk: Boolean) {
        state = when {
            txOk && rxOk -> ModuleState.RUNNING
            txOk || rxOk -> ModuleState.DEGRADED
            !micOn && !spkOn -> ModuleState.DEGRADED   // 两个方向都被用户关闭：挂起待命
            else -> ModuleState.ERROR.also { lastError = "两条音频流都无法打开" }
        }
    }

    // ————————————————————————————— 内部 —————————————————————————————

    private fun locateCard(): Int? {
        val text = runCatching { File(AudioConst.ALSA_CARDS_PATH).readText() }.getOrNull() ?: return null
        val re = Regex("""^\s*(\d+)\s*\[([^\]]+)\]""", RegexOption.MULTILINE)
        for (m in re.findAll(text)) {
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            if (m.groupValues[2].contains(AudioConst.ALSA_CARD_KEYWORD, ignoreCase = true)) {
                return idx
            }
        }
        return null
    }

    /**
     * 解析 UAC2 gadget 的 PCM **设备号**（`pcmC<D*>` 里的 D）。
     *
     * `/proc/asound/pcm` 每行形如 `01-00: UAC2 PCM : UAC2 PCM : playback 1 : capture 1`，
     * 前缀是 `卡号-设备号`，后面标了该设备支持的方向。设备号同样随枚举顺序变化，
     * 写死 0 会在别的机型上取到别的设备。
     *
     * 解析不到时按设备号 **0** 降级（UAC2 gadget 通常只暴露一个 PCM 设备），
     * 并如实记日志 —— 不静默、也不假装成功。
     */
    private fun locateDevice(card: Int, playback: Boolean): Int {
        val want = if (playback) "playback" else "capture"
        val text = runCatching { File(AudioConst.ALSA_PCM_PATH).readText() }.getOrNull()
        if (text == null) {
            Log.w(TAG, "读不到 ${AudioConst.ALSA_PCM_PATH}：PCM 设备号按 0 降级（card=$card dir=$want）")
            return 0
        }
        val re = Regex("""^\s*(\d+)-(\d+):(.*)$""", RegexOption.MULTILINE)
        for (m in re.findAll(text)) {
            val c = m.groupValues[1].toIntOrNull() ?: continue
            if (c != card) continue
            if (!m.groupValues[3].contains(want)) continue
            val dev = m.groupValues[2].toIntOrNull() ?: continue
            return dev
        }
        Log.w(TAG, "${AudioConst.ALSA_PCM_PATH} 里找不到 card=$card dir=$want 的 PCM 设备号：按 0 降级")
        return 0
    }

    private fun startMicToPc(card: Int): Boolean {
        val path = AudioConst.pcmNode(card, locateDevice(card, playback = true), playback = true)
        val stream = AlsaPcm.open(path, playback = true) ?: return false

        val minBuf = AudioRecord.getMinBufferSize(RATE, IN_CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize 失败：$minBuf")
            stream.close()
            return false
        }

        micRun = true
        micToPc = thread(start = true, name = "apx-audio-tx") {
            var rec: AudioRecord? = null
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
                // 显式路由到内建麦克风：USB 声卡在场时系统可能把录音路由到 UAC2 capture（回环）
                runCatching {
                    val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        ?.let { rec.setPreferredDevice(it) }
                }
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
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
                var lastWriteErrLog = 0L
                while (micRun) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) {
                        // 采集侧异常/空读（-3 ERROR_INVALID_OPERATION 等）时同样必须节流，
                        // 否则错误态下 `continue` 会退化成自旋，把一个大核钉满。
                        if (micRun) sleepQuiet(TX_IDLE_SLEEP_MS)
                        continue
                    }
                    val rc = stream.write(if (n == buf.size) buf else buf.copyOf(n))
                    if (rc < 0) {
                        // 主机未取走 USB 麦克风流时 native 会节流 100ms 后返回 -EAGAIN（-11）丢本帧；
                        // 这里限频打日志，避免 10ms 一条刷屏。
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastWriteErrLog > 5000) {
                            lastWriteErrLog = now
                            Log.w(TAG, "ALSA playback 写入失败 rc=$rc")
                        }
                    }
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

    private fun startPcToSpeaker(card: Int): Boolean {
        val path = AudioConst.pcmNode(card, locateDevice(card, playback = false), playback = false)
        val stream = AlsaPcm.open(path, playback = false) ?: return false

        spkRun = true
        pcToSpk = thread(start = true, name = "apx-audio-rx") {
            var track: AudioTrack? = null
            try {
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
                    .setBufferSizeInBytes(maxOf(minBuf * 2, FRAME_BYTES * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                // 显式路由到内建扬声器（修复⑮）：gadget 自建的 UAC2 声卡在场时，
                // Android 会把 USAGE_MEDIA 自动路由到 USB 声卡，形成 PC→手机→USB 回环，
                // 扬声器永远不响。setPreferredDevice 把这条流钉死在内建扬声器上。
                runCatching {
                    val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val spk = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).let { ds ->
                        ds.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            ?: ds.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }
                    }
                    spk?.let { track.setPreferredDevice(it) }
                        ?: Log.w(TAG, "未找到内建扬声器设备，回环风险仍在")
                }
                track.play()
                val buf = ByteArray(FRAME_BYTES)
                var lastDiag = 0L
                var total = 0L
                while (spkRun) {
                    val n = stream.read(buf)
                    if (n > 0) {
                        total += n
                        track.write(buf, 0, n)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastDiag > 5000) {
                            lastDiag = now
                            Log.i(TAG, "PC→spk 收流中：累计 ${total / 1024}KB")
                        }
                    } else {
                        // capture 是 O_NONBLOCK：无数据时 native 立即返回 -EAGAIN，
                        // 这里必须节流休眠，否则本循环（连同原生读）就是一次零等待自旋，
                        // 一个大核被钉在全频 —— v217 实测「USB 模式锁 2 个大核满载」的主因。
                        // 2ms = 1/5 个 10ms 帧：延迟与 CPU 占用取平衡。
                        sleepQuiet(RX_IDLE_SLEEP_MS)
                    }
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

    /** 节流休眠：被中断时恢复中断标记并返回（由外层循环的 run 标志决定是否退出） */
    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "AudioModule"

        private const val PREFS = "apx_usb_audio"
        private const val K_SPK = "spk_on"
        private const val K_MIC = "mic_on"

        /** 方向子开关初值（设置页读取）；运行中由 applySpeaker/applyMic 热切换并落盘 */
        fun isSpeakerOn(c: Context): Boolean = prefs(c).getBoolean(K_SPK, true)

        fun isMicOn(c: Context): Boolean = prefs(c).getBoolean(K_MIC, true)

        /**
         * 设置页入口：**只落盘**，不触碰正在跑的流。
         *
         * 模块实例可能压根不存在（服务没起），此时没有 applySpeaker/applyMic 可调；
         * 开关本身仍必须记住用户的选择，否则下次启动又回到默认的「双向全开」，
         * 用户看到的就是「关了没用」。热切换由设置页在实例存在时另行调用。
         */
        fun setSpeakerPref(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean(K_SPK, on).apply()
        }

        fun setMicPref(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean(K_MIC, on).apply()
        }

        private fun prefs(c: Context) =
            c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private const val RATE = AudioConst.SAMPLE_RATE
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val IN_CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        private const val OUT_CHANNEL = AudioFormat.CHANNEL_OUT_STEREO

        /** 10ms 一帧：48000Hz × 2ch × 2B ÷ 100 */
        private const val FRAME_BYTES = 48000 * 2 * 2 / 100

        /** rx 侧无数据时的节流间隔（capture O_NONBLOCK → -EAGAIN 后休眠） */
        private const val RX_IDLE_SLEEP_MS = 2L

        /** tx 侧采集异常/空读时的节流间隔，防止错误态自旋 */
        private const val TX_IDLE_SLEEP_MS = 5L
    }
}
