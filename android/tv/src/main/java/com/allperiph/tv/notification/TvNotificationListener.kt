package com.allperiph.tv.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.allperiph.shared.util.Log

/**
 * TV 端通知监听服务：监听手机通知并通过 TCP 通道推送到 PC 端。
 *
 * 用户在手机上授予「通知访问权限」后，本服务自动接收所有通知，
 * 经 [NotificationData.from] 提取关键信息后存入 [NotificationQueue]，
 * 由 [com.allperiph.tv.net.TcpControlServer] 读取并推送。
 *
 * 注册：AndroidManifest.xml 中声明，用户手动授予通知访问权限。
 */
class TvNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 过滤自身通知，避免循环
        if (sbn.packageName == packageName) return
        // 过滤低优先级通知（如 ongoing 系统通知）
        if (sbn.isOngoing) return

        val data = NotificationData.from(sbn) ?: return
        NotificationQueue.offer(data)
        Log.i(TAG, "通知已入队: ${data.packageName} - ${data.title}（${data.text.take(30)}）")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 通知被清除时不推送，仅记录
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听已断开")
    }

    companion object {
        private const val TAG = "TvNotifListener"
    }
}
