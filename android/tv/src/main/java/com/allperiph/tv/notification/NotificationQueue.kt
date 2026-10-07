package com.allperiph.tv.notification

import com.allperiph.shared.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 通知队列：TV 端通知监听服务写入，TCP 控制服务读取。
 * 使用 ConcurrentLinkedQueue 保证线程安全，无锁高效。
 */
object NotificationQueue {
    private const val TAG = "NotifQueue"
    private const val MAX_SIZE = 200

    private val queue = ConcurrentLinkedQueue<NotificationData>()

    /** 监听服务写入 */
    fun offer(data: NotificationData) {
        if (queue.size >= MAX_SIZE) {
            queue.poll()  // 丢弃最旧的
            Log.w(TAG, "通知队列已满，丢弃最旧通知")
        }
        queue.offer(data)
    }

    /** TCP 服务读取并批量发送 */
    fun drainAll(): List<NotificationData> {
        val result = ArrayList<NotificationData>()
        while (true) {
            val item = queue.poll() ?: break
            result.add(item)
        }
        return result
    }

    /** 当前队列长度 */
    fun size(): Int = queue.size
}
