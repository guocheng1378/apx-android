package com.allperiph.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知使用权监听（2026-11-11）：系统把**所有**应用的通知回调到这里，按
 * [NotificationSyncPref] 的开关转发给 [NotificationSyncBus]（→ 9511 0x40/0x42 → PC 托盘）；
 * 2026-11-14 起兼管**通知回复的 action 缓存**（0x41/0x43，REQ-通知回复.md）：
 * 收到带 RemoteInput action 的通知时把 `key → action` 记下来，PC 发 0x41 时由
 * [NotificationReply] 找回并程序性提交。
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
        // 通知回复的 action 缓存（0x41，2026-11-14）：推送之后顺手记下「这条通知怎么回」。
        // 只记带 RemoteInput 的通知（纯展示通知记了也回不了，徒占 LRU 名额）；
        // 缓存失败不影响推送 —— 回复能力是推送的搭头，不该反向拖垮主链路。
        cacheReplyAction(sbn.key, n)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 消除帧跟随开关（推送关了消除也没意义）；key 由 PC 端用来对上哪条气泡
        if (!NotificationSyncPref.isEnabled(this)) return
        val key = sbn?.key ?: return
        NotificationSyncBus.sendDismiss(key)
        // 通知没了，回复通道同步作废：PC 再发 0x41 会拿到 err=2（key 失效），
        // 比对着旧 action 提交出一个「幽灵回复」体面得多。
        removeReplyAction(key)
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

    // ———————————— 通知回复：action 缓存（0x41，2026-11-14）————————————

    companion object {
        /**
         * key → 可回复的 Notification.Action（会话级：监听重启即丢，与 0x40 同命——
         * PC 重连后旧气泡的 key 大概率失效，回 err=2 而不是提交出幽灵回复）。
         * LRU 上限 64：accessOrder=true 的 LinkedHashMap，取用即刷新新鲜度，
         * 满了淘汰最久没用的——会话窗口里的活跃会话远小于 64，够了。
         * 读写都走 [replyLock]（监听回调线程与 0x41 处理线程并发访问）。
         */
        private val replyActions = object : LinkedHashMap<String, Notification.Action>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Notification.Action>): Boolean =
                size > REPLY_CACHE_MAX
        }
        private val replyLock = Any()
        private const val REPLY_CACHE_MAX = 64

        /** 取回某条通知的可回复 action；没有（key 失效/通知不支持回复）返回 null */
        fun replyAction(key: String): Notification.Action? = synchronized(replyLock) { replyActions[key] }

        /** 通知被划掉/点开：缓存同步作废，PC 端再回复会拿到 err=2（key 失效） */
        fun removeReplyAction(key: String) = synchronized(replyLock) { replyActions.remove(key) }

        /**
         * 记下一条通知的可回复 action：取 `notification.actions` 里第一个带
         * 非空 RemoteInput 的（多数 App 只有一个「回复」action，取第一个即是）。
         * 没有 → 连旧记录一起清掉（通知更新后不再可回的，别留死条目）。
         */
        fun cacheReplyAction(key: String, n: Notification) {
            val action = n.actions?.firstOrNull { a -> !a.remoteInputs.isNullOrEmpty() }
            synchronized(replyLock) {
                if (action == null) replyActions.remove(key) else replyActions[key] = action
            }
        }
    }
}
