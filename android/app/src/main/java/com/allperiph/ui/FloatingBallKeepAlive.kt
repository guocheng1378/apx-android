package com.allperiph.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.allperiph.shared.util.Log

/**
 * 悬浮球自愈闹钟 + 开机自愈（v223.29）—— 修成宇 2026-11-02「悬浮窗不会自动拉起」。
 *
 * ## 为什么光有 [FloatingBallService.ensureRunningIfWanted] 不够
 * 那个入口只挂在 `Application.onCreate` 上：它只能覆盖「进程因为别的原因起来了」的场景
 * （用户点开应用、保活闹钟把进程唤起）。剩下两条最常见的丢失路径它都够不着：
 *
 *  1. **手机重启**：闹钟是 `ELAPSED_REALTIME_WAKEUP`，重启即失效，进程也没有任何理由
 *     自己起来 —— 球永远不会回来（用户开过一次就该一直有），需要 `BOOT_COMPLETED`；
 *  2. **进程被系统回收后没人再唤起它**：`START_STICKY` 不保证回来，MIUI / HyperOS 从最近
 *     任务划掉更是连进程一起杀。这里补一条本应用自己的定时自检（闹钟触发时系统会给应用
 *     一小段「允许启动前台服务」的窗口，正是这种场合唯一可靠的拉起路径）。
 *
 * ## 为什么不复用 `shared.inject.KeepAlive`
 * 那条链路是给**被控服务**做的，`cancel` 语义绑在「用户主动关掉被控」上 —— 一旦复用，
 * 用户关掉被控就会顺带把悬浮球的自检也停掉，而这两件事彼此无关（球可以开着、被控关着）。
 * 所以自检闭环单独一份，只有「用户意愿位」[FloatingBallService.isOn] 这一个开关。
 */
object FloatingBallKeepAlive {

    private const val TAG = "BallKeepAlive"

    /**
     * 自检间隔。
     *
     * 比被控服务那条（60s）长：球丢了只是「少一个入口」，不像被控端丢了会直接断功能，
     * 没必要每分钟唤醒一次 CPU；但也不能太长，否则用户划掉应用后要等很久才回来。
     */
    private const val INTERVAL_MS = 120_000L

    /** 与共享 KeepAlive 的 RC 错开，避免两个 PendingIntent 互相覆盖 */
    private const val RC = 0x7A51

    /** 排下一次自检。[delayMs] 用于「刚被系统拒绝启动 → 尽快重试」。 */
    fun schedule(ctx: Context, delayMs: Long = INTERVAL_MS) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(ctx)
        val at = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
        // 与 KeepAlive 同一套降级：精确闹钟在 Android 12+ 需要「闹钟与提醒」权限
        val ok = runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.recoverCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.recoverCatching {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.isSuccess
        if (!ok) Log.w(TAG, "悬浮球自检闹钟设置失败 → 进程自愈不可用")
    }

    /** 用户自己关掉悬浮球时必须调，否则闹钟下一秒又把球拉回来（变成「关不掉」）。 */
    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(ctx)) }
            .onFailure { Log.w(TAG, "取消悬浮球自检闹钟失败：${it.message}") }
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, FloatingBallKeepAliveReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, RC, i, flags)
    }

    /** 闹钟到点：球不在就拉回来，然后重排下一次（自愈闭环）。 */
    fun onAlarm(ctx: Context) {
        // 意愿位为 false（用户主动关过）→ 就此打住，连闹钟也不再排：绝不强行拉起。
        if (!FloatingBallService.isOn(ctx)) {
            Log.i(TAG, "用户意愿位为关 → 自检停止")
            return
        }
        // 悬浮窗权限被撤掉时也不再重排：拉起必然失败（addView 拿不到 overlay），
        // 排下去只会变成「每 2 分钟白醒一次 CPU」。用户重新授权时会打开界面，那次
        // 走 [FloatingBallService.requestToggle] / onCreate，会自动把闹钟排回来。
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            Log.i(TAG, "没有悬浮窗权限 → 自检暂停（重新授权后自动恢复）")
            return
        }
        if (!FloatingBallService.isRunning) {
            Log.i(TAG, "球不在 → 重新拉起")
            runCatching { com.allperiph.ui.FloatingBallService.ensureRunningIfWanted(ctx, true) }
                .onFailure { Log.w(TAG, "自检拉起失败：${it.message}") }
        }
        // 无条件重排下一次。这里与 onCreate 里的排程可能撞上（服务刚被拉起、还没来得及
        // 自己排）—— 同一个 PendingIntent + FLAG_UPDATE_CURRENT 只是**替换**同一个闹钟，
        // 不会排成两条，因此宁可多排一次也不能漏排。
        schedule(ctx)
    }
}

/**
 * 自检闹钟的接收者。`exported=false`：只由本应用自己的 PendingIntent 触发；
 * 在清单里注册成显式组件，因此 App 进程已死也能被闹钟唤起。
 */
class FloatingBallKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) =
        FloatingBallKeepAlive.onAlarm(context.applicationContext)
}

/**
 * 开机 / 应用更新后把悬浮球放回屏幕。
 *
 * 只认「用户意愿位」：关过悬浮球的用户不会被这里强行拉起（[FloatingBallService.isOn]）。
 * 直接 startForegroundService 在 Android 12+ 的后台限制下不一定获批 —— 所以无论成败都
 * 补排一次自检闹钟（闹钟触发时系统会给应用一段允许启动前台服务的窗口）作为兜底。
 */
class FloatingBallBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val c = context.applicationContext
        if (!FloatingBallService.isOn(c)) return
        Log.i("BallKeepAlive", "开机/更新广播（$action）→ 按意愿位放回悬浮球")
        runCatching { FloatingBallService.ensureRunningIfWanted(c, true) }
            .onFailure { Log.w("BallKeepAlive", "开机拉起失败：${it.message}") }
        // 立刻起不来（后台启动前台服务被拒）时靠这一次闹钟兜底
        FloatingBallKeepAlive.schedule(c, 15_000L)
    }
}