package com.allperiph.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.allperiph.core.Log
import com.allperiph.R

/**
 * 通知渠道（Android 8+ 必须先建渠道，否则通知不显示）。
 *
 * 两个渠道：
 * - `apx_status`：常驻前台服务状态（IMPORTANCE_LOW，不打扰）
 * - `apx_alert`：降级/错误告警（IMPORTANCE_DEFAULT，会出声提示一次）
 */
object NotificationChannels {

    const val CHANNEL_STATUS = "apx_status"
    const val CHANNEL_ALERT = "apx_alert"

    /** Manifest 中 meta-data 声明的渠道名，保持一致便于 PC 端/脚本检索 */
    const val META_CHANNEL_STATUS = "allperiph_status"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS,
                    context.getString(R.string.notify_channel_status_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notify_channel_status_desc)
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    context.getString(R.string.notify_channel_alert_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notify_channel_alert_desc)
                }
            )
            Log.i("NotificationChannels", "渠道已建立：$CHANNEL_STATUS / $CHANNEL_ALERT")
        }
    }
}
