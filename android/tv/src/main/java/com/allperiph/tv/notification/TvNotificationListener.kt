package com.allperiph.tv.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.allperiph.shared.util.Log

/**
 * TV 端通知监听服务：捕获本机（电视）通知，供后续转发给手机 / 电脑。
 *
 * 用户在系统设置里授予「通知访问权限」后，本服务自动接收所有通知，
 * 经 [NotificationData.from] 提取关键信息后存入 [NotificationQueue]。
 *
 * ⚠️ **当前状态：只有「捕获 → 入队」，没有消费端**（v196）。
 *   - [NotificationQueue.drainAll] 全仓库零调用；
 *   - [com.allperiph.shared.net.ControlServer] 没有任何发送通知的 API，
 *     协议里也没有对应帧（`scripts/check_protocol.py` 无此命令号）。
 *   所以通知目前只进队列、到 200 条上限后丢最旧的，**不会出现在任何地方**。
 *   要落地需要：① 定义协议帧（app/tv/pc 三端收发）② TV 本机浮窗或转发二选一
 *   —— 属于新特性，不是修 bug，故不在 v196 收尾范围内。
 *
 * 注册：AndroidManifest.xml 中已声明，用户需手动授予通知访问权限。
 */
class TvNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 过滤自身通知，避免循环
        if (sbn.packageName == packageName) return
        // 过滤低优先级通知（如 ongoing 系统通知）
        if (sbn.isOngoing) return

        // 解析失败绝不能让 binder 回调崩掉：通知监听服务一崩，用户得重新去系统里授权
        val data = try {
            NotificationData.from(sbn)
        } catch (t: Throwable) {
            Log.w(TAG, "通知解析失败：${t.message}")
            null
        } ?: return
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
