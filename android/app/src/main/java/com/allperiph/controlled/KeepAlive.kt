package com.allperiph.controlled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.allperiph.core.Log

/**
 * **进程级保活（手机被控端）**：与 TV 端 `com.allperiph.tv.KeepAlive` 同一套做法。
 *
 * 为什么需要它：`START_STICKY` 与 `onTaskRemoved` 都**不保证**被控服务能回来 ——
 *  - Android 12+ 禁止应用在后台启动前台服务，`onTaskRemoved` 里直接
 *    `startForegroundService` 会抛 `ForegroundServiceStartNotAllowedException`（原先只吞掉打日志）；
 *  - MIUI / HyperOS 从最近任务划掉 = 连进程一起杀，被控端随即"消失"。
 *
 * 而**闹钟触发时系统会给应用一小段"允许启动前台服务"的窗口**，所以用一个定时闹钟做最后一道自检：
 * 服务不在就拉起来，然后再排下一次，形成自愈闭环。
 *
 * 只在用户**主动开过被控**（[ControlledService.KEY_ENABLED]）时才拉起，不替用户做决定。
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    /** 常规自检间隔 */
    private const val INTERVAL_MS = 60_000L

    private const val RC = 0x7A52

    /**
     * 排下一次自检。[delayMs] 用于"刚被拒 → 尽快重试"。
     * 精确闹钟在 Android 12+ 需要「闹钟与提醒」权限，未授予会抛 SecurityException，逐级降级。
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
        if (!ok) Log.w(TAG, "保活闹钟设置失败 → 进程级自检不可用")
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, KeepAliveReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, RC, i, flags)
    }

    /** 闹钟到点：服务不在就拉起来，然后无条件重排（自愈闭环） */
    fun onAlarm(ctx: Context) {
        val c = ctx.applicationContext
        // running 是**进程内**静态量：进程被杀后重启的新进程里它必然为 false，正好当"服务已死"的判据
        if (ControlledService.enabled(c) && !ControlledService.isRunning()) {
            Log.i(TAG, "保活自检：被控服务不在 → 重新拉起")
            ControlledService.start(c)
        }
        schedule(c)
    }
}

/**
 * 保活闹钟的接收者。**exported=false**：只由本应用自己的 PendingIntent 触发；
 * 在清单里注册成显式组件，因此 App 进程已死也能被闹钟唤起。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = KeepAlive.onAlarm(context)
}
