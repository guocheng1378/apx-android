package com.allperiph.notify

import com.allperiph.shared.util.Log

/**
 * 通知同步总线（2026-11-11）：`ApxNotificationListener` 收到系统通知后从这里出去，
 * 按「当前谁是 PC 会话」尝试每条通道 —— 手机同时处于被控（ControlledService 的
 * ControlServer）与控设备（ControlTarget.controlClient 连着 PC/TV）两种状态都合法，
 * 哪条链路活着就从哪条发，互不干扰。
 *
 * 通道实现**拉取式**取连接对象（发送时读单例，而不是注册时抓引用）：
 * 服务/客户端随时断开重连，注册表存旧实例只会把帧发进死连接。
 */
object NotificationSyncBus {
    private const val TAG = "NotifySync"

    /** 一条发送通道：push/dismiss 任一成功（帧入发送队列）即视为该通道已同步 */
    private class Lane(
        val name: String,
        val push: (key: String, title: String, text: String, app: String) -> Boolean,
        val dismiss: (key: String) -> Boolean,
    )

    // 通道一：被控 —— PC 面板连入本机（ControlledService 持有 ControlServer）
    private val laneControlled = Lane("controlled",
        { key, title, text, app ->
            val s = com.allperiph.controlled.ControlledService.server
            s?.sendNotificationPush(key, title, text, app) ?: false
        },
        { key ->
            val s = com.allperiph.controlled.ControlledService.server
            s?.sendNotificationDismiss(key) ?: false
        })

    // 通道二：控设备 —— 本机作为控制端连着 PC / TV（触控板右上角选中的受控设备）
    private val laneController = Lane("controller",
        { key, title, text, app ->
            val c = com.allperiph.wireless.ControlTarget.controlClient
            c?.sendNotificationPush(key, title, text, app) ?: false
        },
        { key ->
            val c = com.allperiph.wireless.ControlTarget.controlClient
            c?.sendNotificationDismiss(key) ?: false
        })

    private val lanes = listOf(laneControlled, laneController)

    /** 推送一条通知；返回 true = 至少一条链路接受（发送能力门控在链路方法内） */
    fun sendPush(key: String, title: String, text: String, app: String): Boolean {
        var ok = false
        for (l in lanes) {
            if (runCatching { l.push(key, title, text, app) }.getOrDefault(false)) ok = true
        }
        if (!ok) Log.d(TAG, "通知没有活着的同步通道（未连接或对端不支持）")
        return ok
    }

    /** 消除一条通知（key 与推送时一致）；返回值含义同 [sendPush] */
    fun sendDismiss(key: String): Boolean {
        var ok = false
        for (l in lanes) {
            if (runCatching { l.dismiss(key) }.getOrDefault(false)) ok = true
        }
        return ok
    }
}
