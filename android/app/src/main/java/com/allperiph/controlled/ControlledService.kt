package com.allperiph.controlled

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.allperiph.R
import com.allperiph.core.Log
import com.allperiph.touchpad.TouchpadActivity

/**
 * 被控模式前台服务：持有 [TvControlServer] + [TvFileReceiver] + 发现信标，使本机在退到后台后仍可被对方手机连入控制。
 * 同时注册剪贴板监听，实现反向剪贴板（本机复制 → 回传对方手机）。
 */
class ControlledService : Service() {

    private var server: TvControlServer? = null
    private var beacon: WirelessBeacon? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = currentClipboardText()
        if (!text.isNullOrEmpty()) server?.sendReverseClipboard(text)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "被控模式", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TvInjector.init(this)
        if (server == null) server = TvControlServer()
        server?.onClipboardChange = { text -> server?.sendReverseClipboard(text) }
        if (server?.start() != true) Log.e(TAG, "被控控制面启动失败")
        TvFileReceiver.start(applicationContext)
        // 广播 APX1TV 信标（9511 统一控制面），使 PC / 另一台手机经自动发现连入本机被控。
        beacon = WirelessBeacon("APX1TV", TvControlServer.PORT, "").also { it.start() }

        // 注册剪贴板监听（反向剪贴板）
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.addPrimaryClipChangedListener(clipListener)

        startForegroundGuarded()
        // 主动上报一次当前剪贴板（被控 App 在前台时本机可读到自己的剪贴板）
        val init = currentClipboardText()
        if (!init.isNullOrEmpty()) server?.sendReverseClipboard(init)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.removePrimaryClipChangedListener(clipListener) }
        server?.stop()
        server = null
        beacon?.stop()
        beacon = null
        TvFileReceiver.stop()
        TvInjector.onDestroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return try {
            cm?.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun startForegroundGuarded() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFY_ID, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败（权限？）：${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ready = server?.ready == true
        val sysReady = TvInjector.systemReady()
        val text = if (!ready) "等待手机连入 :${TvControlServer.PORT}"
        else if (sysReady) "已连接 · 系统注入已启用"
        else "已连接 · 未启用无障碍（仅可视化）"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("全能外设 · 被控模式")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_app)
            .setContentIntent(openPi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ControlledService"
        private const val CHANNEL_ID = "controlled_status"
        private const val NOTIFY_ID = 0x9A3

        @Volatile
        var running: Boolean = false
            private set

        fun start(c: Context) {
            running = true
            val i = Intent(c, ControlledService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            }.onFailure {
                running = false
                Log.e(TAG, "启动被控服务失败", it)
            }
        }

        fun stop(c: Context) {
            running = false
            runCatching { c.stopService(Intent(c, ControlledService::class.java)) }
        }

        fun isRunning() = running
    }
}
