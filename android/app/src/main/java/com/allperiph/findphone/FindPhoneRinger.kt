package com.allperiph.findphone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.allperiph.shared.util.Log

/**
 * 查找手机响铃（0x38，2026-11-11，能力位 bit2）：PC 托盘点「查找手机」→ 控制面命令到本机
 * → 这里响铃找手机。三条收帧路径（[com.allperiph.shared.net.ControlServer] 手机被控、
 * [com.allperiph.controlled.TvControlServer] 手机控手机、
 * [com.allperiph.wireless.TvControllerClient] 手机控 PC 时 PC 反向找）都落本对象，
 * 所以放在 app 模块（依赖 [com.allperiph.App.context] 取应用上下文）。
 *
 * 行为口径（ROADMAP §5.1②）：
 *  · 走 `AudioManager` 的 **STREAM_ALARM**（闹钟流）——系统静音/勿扰不影响闹钟流，静音也响；
 *    再把闹钟流音量临时拉到最大（停了还回去），确保「铃要够大」。
 *  · 顺带点亮屏幕：WakeLock（ACQUIRE_CAUSES_WAKEUP）从灭屏直接拉亮，30s 后随铃一起松。
 *  · 30s 自动停 —— 防止手机真被找到前一直响到没电；用户在 PC 上再点一次或手机解锁
 *    操作后铃继续响满 30s 也无妨（音量随停恢复）。
 *  · action=0 立即停（协议预留的对端停止通道，当前 PC 端托盘只有响铃一键）。
 *
 * 幂等与并发：所有状态只在主线程碰（handle() 统一 post），重复响铃只是把 30s 计时重置，
 * 不会叠出一个新的 Ringtone。
 */
object FindPhoneRinger {

    private const val TAG = "查找手机"

    /** 响铃时长：PC 点一次最多响这么久（ROADMAP 验收：3 秒内出声、30s 自动停） */
    private const val RING_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())

    /** 当前在响的铃（null = 没在响）；Ringtone 必须在带 Looper 的线程 play，统一主线程 */
    private var ringtone: Ringtone? = null

    /** 亮屏锁：响铃期间持屏，stop 时释放（setReferenceCounted(false)，重复 release 安全） */
    private var wake: PowerManager.WakeLock? = null

    /** 响铃前的闹钟流音量，停铃时还原；-1 表示没有动过别人的音量 */
    private var savedAlarmVolume = -1

    /** 30s 自动停的计时器（重复响铃时先摘后挂，避免提前停铃） */
    private val autoStop = Runnable { stopInternal("30s 到时") }

    /**
     * 控制面帧入口：action 1=响铃 0=停。任意线程可调（内部切主线程），
     * App.context 未就绪（进程刚起、还没走 Application.onCreate）时只打日志。
     */
    fun handle(action: Int) {
        main.post {
            if (action == 0) stopInternal("对端请求") else startInternal()
        }
    }

    /** 起铃：已在对响中则只重置计时（不重播，避免铃声一卡一卡）；否则拉满音量、亮屏、开播 */
    private fun startInternal() {
        val ctx = com.allperiph.App.context
        if (ctx == null) {
            Log.w(TAG, "收到查找手机，但 App.context 未就绪（进程刚被拉起？）")
            return
        }
        // 换成 main Handler 的时间轴：先把旧的自动停摘掉，响铃期间重复收到 1 也只会续时
        main.removeCallbacks(autoStop)
        if (ringtone != null) {
            Log.i(TAG, "已在响铃 —— 只重置 ${RING_MS / 1000}s 计时")
            main.postDelayed(autoStop, RING_MS)
            return
        }
        // —— 音量：闹钟流临时拉满（静音也响的关键 —— 闹钟流不受系统铃声静音影响，
        //    但它自己的音量可能是 0，得兜底）。存原值，停铃还原。
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio != null) {
            savedAlarmVolume = try { audio.getStreamVolume(AudioManager.STREAM_ALARM) } catch (_: RuntimeException) { -1 }
            runCatching {
                audio.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
            }.onFailure { Log.w(TAG, "闹钟流音量拉满失败：${it.message}") }
        }
        // —— 亮屏：deprecated 常量是唯一能「灭屏直接拉亮」的组合
        //    （FLAG_KEEP_SCREEN_ON 只防息屏不点亮；PARTIAL 不亮屏）。
        //    API 13 起官方不再推荐，但 find-my-phone 场景没有替代 API，保留并显式压警告。
        @Suppress("DEPRECATION")
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            wake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "apx:findphone",
            ).apply {
                setReferenceCounted(false)
                // 比铃多 0.5s：铃停了屏多亮一瞬，用户来得及看见「就是这台」
                acquire(RING_MS + 500)
            }
        }
        // —— 铃声源：优先闹钟默认音（用户熟），没有闹钟音的系统（少见）退回通知音
        val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ring = RingtoneManager.getRingtone(ctx, alarmUri)
        if (ring == null) {
            Log.w(TAG, "取不到可用的铃声（alarmUri=$alarmUri），本次只亮屏不出声")
            wake = null
            savedAlarmVolume = -1
            return
        }
        // 声音走闹钟流的 AudioAttributes：即使某些 ROM 的 Ringtone 默认跟铃声流，
        // 也强制对齐「静音也响、音量归闹钟」的口径
        ring.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone = ring
        runCatching { ring.play() }
            .onFailure { Log.w(TAG, "铃声播放失败：${it.message}；释放状态") ; stopInternal(null) }
            .onSuccess { Log.i(TAG, "响铃开始（${RING_MS / 1000}s 自动停）") }
        if (ringtone != null) main.postDelayed(autoStop, RING_MS)
    }

    /** 停铃并还原音量/亮屏。reason 仅用于日志（null = 起铃失败的中途清理，不另记一笔） */
    private fun stopInternal(reason: String?) {
        main.removeCallbacks(autoStop)
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
        if (reason != null) Log.i(TAG, "响铃结束（$reason）")
        if (savedAlarmVolume >= 0) {
            com.allperiph.App.context?.let { ctx ->
                val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                runCatching {
                    audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                }.onFailure { Log.w(TAG, "还原闹钟音量失败：${it.message}") }
            }
            savedAlarmVolume = -1
        }
    }
}
