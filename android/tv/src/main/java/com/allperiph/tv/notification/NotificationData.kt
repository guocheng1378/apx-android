package com.allperiph.tv.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * 通知数据：从 StatusBarNotification 提取关键字段，用于 TCP 推送到对端。
 */
data class NotificationData(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
) {
    /** 格式化为 TCP 传输载荷：[appName\ntitle\ntext]，UTF-8 编码 */
    fun toPayload(): String = "$packageName\n$title\n$text"

    companion object {
        /** 从 StatusBarNotification 提取数据 */
        fun from(sbn: StatusBarNotification): NotificationData? {
            val pkg = sbn.packageName
            val extras = sbn.notification?.extras ?: return null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ""
            if (title.isEmpty() && text.isEmpty()) return null
            return NotificationData(pkg, title, text, sbn.postTime)
        }
    }
}
