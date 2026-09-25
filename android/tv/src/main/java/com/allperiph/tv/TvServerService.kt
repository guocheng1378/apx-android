package com.allperiph.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.allperiph.tv.core.Log
import com.allperiph.tv.net.TcpControlServer

/**
 * 前台服务：持有 [TcpControlServer] 与发现信标，使 TV 服务端在 Activity 退到后台后仍可连。
 * 老版本（API 23）用 2 参 startForeground 即可；API 34 需显式带 connectedDevice 类型。
 */
class TvServerService : Service() {

    private var server: TcpControlServer? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "APX TV 状态",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) server = TcpControlServer()
        if (!server!!.start()) {
            Log.e("TV 控制面启动失败")
        }
        val notif = buildNotification(server?.statusText() ?: "服务启动中")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFY_ID, notif)
            }
        } catch (t: Throwable) {
            Log.w("startForeground 失败（权限？）：${t.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder.setContentTitle(getString(R.string.tv_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_tv)
            .setOngoing(true)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "apxtv_status"
        private const val NOTIFY_ID = 1
    }
}
