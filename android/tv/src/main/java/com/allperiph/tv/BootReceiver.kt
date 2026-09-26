package com.allperiph.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.allperiph.tv.core.Log

/**
 * 开机 / 应用更新后自动拉起被控服务。
 *
 * 电视与盒子常年插电挂在墙上，用户不该每次都翻出遥控器点开 App 才能被手机发现。
 * 只要**用户开过一次**（[MainActivity] 会写 `enabled=true`），之后就自动恢复；
 * 从没开过的设备不会自启 —— 不替用户做决定。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (act != Intent.ACTION_BOOT_COMPLETED && act != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // 只有「已启用 **且** 用户没主动停过」才自启 —— 否则主界面的「停止被控」
        // 会在下次开机时被悄悄推翻（见 TvServerService.stopAll / startIfNeeded）。
        if (!TvServerService.isEnabled(context) || TvServerService.isUserStopped(context)) return

        val svc = Intent(context, TvServerService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc)
            else context.startService(svc)
            Log.i("开机自启：已拉起 TV 被控服务")
        }.onFailure { Log.w("开机自启失败：${it.message}") }
    }
}
