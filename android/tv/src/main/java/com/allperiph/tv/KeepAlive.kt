package com.allperiph.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.allperiph.tv.core.Log

/**
 * **进程级保活**：看门狗（[TvServerService.startWatchdog]）只能救「同一个进程里 9511 挂了」，
 * 它救不了「整个进程被系统杀掉」—— 进程都没了，谁去跑看门狗？
 *
 * 电视 / 盒子对后台下手很狠，Android 12+ 又禁止应用在后台启动前台服务，
 * 于是 `START_STICKY` 和 `onTaskRemoved` 都不保证能回来。这里补上最后一道：
 * **定时闹钟自检** —— 闹钟触发时系统会临时给应用一段「允许启动前台服务」的窗口，
 * 正好用来把 [TvServerService] 拉回来。
 *
 * 触发条件（[TvServerService.KEY_ENABLED]）仍是"用户至少开过一次 App"，
 * 所以不会替没开过的设备做决定。
 */
object KeepAlive {

    /** 常规自检间隔。电视常年插电，1 分钟足够快，也不会明显耗电。 */
    private const val INTERVAL_MS = 60_000L

    private const val RC = 0x7A51

    /**
     * 安排下一次自检。[delayMs] 用于「刚被拒 → 尽快重试」的场景。
     *
     * 精确闹钟在 Android 12+ 需要「闹钟与提醒」权限，未授予会抛 `SecurityException`，
     * 因此逐级降级到不精确闹钟；都失败也只记日志（不影响控制面本身）。
     */
    fun schedule(ctx: Context, delayMs: Long = INTERVAL_MS) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(ctx)
        val at = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
        val ok = runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.recoverCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.recoverCatching {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.isSuccess
        if (!ok) Log.w("保活闹钟设置失败 → 进程级自检不可用（有 root / 白名单时影响不大）")
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, KeepAliveReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, RC, i, flags)
    }

    /**
     * 取消自检闹钟 —— 用户**主动关掉被控**时必须调，否则闹钟下一秒又把服务拉回来，
     * 变成"关不掉"。见 [TvServerService.stopAll]。
     */
    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(ctx)) }
            .onFailure { Log.w("取消保活闹钟失败：${it.message}") }
    }

    /** 闹钟到点：服务不在就拉起来，然后无条件重排下一次（形成自愈闭环） */
    fun onAlarm(ctx: Context) {
        val enabled = ctx.getSharedPreferences(TvServerService.PREF, Context.MODE_PRIVATE)
            .getBoolean(TvServerService.KEY_ENABLED, false)
        // current 是进程内静态：进程被杀后重启的进程里它必然为 null —— 正好当作"服务已死"的判据
        if (enabled && TvServerService.current == null) {
            runCatching {
                val i = Intent(ctx, TvServerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                Log.i("保活自检：服务不在 → 已重新拉起")
            }.onFailure { Log.w("保活自检拉起失败：${it.message}") }
        }
        schedule(ctx)
    }
}

/**
 * 保活闹钟的接收者。**exported=false**：只由本应用自己的 PendingIntent 触发。
 * 在清单里注册成显式组件，因此 App 进程已死也能被闹钟唤起。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = KeepAlive.onAlarm(context)
}
