package com.allperiph.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知使用权监听（2026-11-11）：系统把**所有**应用的通知回调到这里，按
 * [NotificationSyncPref] 的开关转发给 [NotificationSyncBus]（→ 9511 0x40/0x42 → PC 托盘）。
 *
 * 服务在 Manifest 声明（`BIND_NOTIFICATION_LISTENER_SERVICE` + intent-filter），
 * 授权入口在设置页（系统「通知使用权」列表里勾选全能外设）。开关默认关 ——
 * 未打开时本服务即使被系统绑定也**立即返回**，通知一个字节都不出本机。
 */
class ApxNotificationListener : NotificationListenerService() {

    /** 应用展示名缓存（每个通知都查 PackageManager 太贵；超量整体清空防膨胀） */
    private val labelCache = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 开关第一道：默认关，未打开时一切静默（用户没授权的功能不该偷跑）
        if (!NotificationSyncPref.isEnabled(this)) return
        if (sbn == null || sbn.key.isNullOrEmpty()) return
        // 自己应用的通知不上报：前台服务通知常驻，每次刷新都推会把 PC 气泡刷屏
        if (sbn.packageName == packageName) return
        // 常驻通知（前台服务/进度条/媒体播放条）不推 —— PC 端只关心「有人给你发了消息」
        if (sbn.isOngoing) return
        val n = sbn.notification ?: return
        // 分组折叠头不是真实消息（一条真正的消息来了系统会单独再回调它）
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        // 标题/正文取法：优先长文本（EXTRA_BIG_TEXT，微信/QQ 常塞这里），退化到普通文本
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = if (big.isNotEmpty()) big else ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = appLabel(sbn.packageName)
        NotificationSyncBus.sendPush(sbn.key, title, text, app)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 消除帧跟随开关（推送关了消除也没意义）；key 由 PC 端用来对上哪条气泡
        if (!NotificationSyncPref.isEnabled(this)) return
        val key = sbn?.key ?: return
        NotificationSyncBus.sendDismiss(key)
    }

    /** 应用展示名（微信/QQ 这类直接可读）；查询失败回退包名，不阻塞通知流 */
    private fun appLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        if (labelCache.size > 64) labelCache.clear()
        labelCache[pkg] = label
        return label
    }
}
