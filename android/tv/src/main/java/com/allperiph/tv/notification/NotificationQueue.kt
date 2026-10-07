package com.allperiph.tv.notification

import com.allperiph.shared.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知队列：TV 端通知监听服务写入，消费端（当前还没有）读取。
 *
 * ⚠️ 目前**只有写入、没有消费端**（转发给手机/电脑需要新增协议帧，属新特性）。
 * 既然没人取，就必须给出清空的出口：否则队列会稳定占满 [MAX_SIZE] 条含标题/正文的通知
 * 常驻内存 —— 那既是内存占用，也是隐私面（本机所有 App 的通知文本被无期限缓存）。
 * 服务停止时由 `TvServerService.onDestroy` 调 [clear]。
 */
object NotificationQueue {
    private const val TAG = "NotifQueue"

    /** 没有消费端时不要缓存太多：200 条通知文本常驻内存没必要 */
    private const val MAX_SIZE = 50

    private val queue = ConcurrentLinkedQueue<NotificationData>()

    /**
     * 独立计数：`ConcurrentLinkedQueue.size()` 是 **O(n) 遍历**，而 offer 在每条通知
     * （含高频的下载/聊天通知）上都会走一次 —— 用原子计数把它降为 O(1)。
     */
    private val count = AtomicInteger(0)

    /** 监听服务写入 */
    fun offer(data: NotificationData) {
        if (count.get() >= MAX_SIZE) {
            queue.poll()  // 丢弃最旧的
            count.decrementAndGet()
            Log.w(TAG, "通知队列已满，丢弃最旧通知")
        }
        queue.offer(data)
        count.incrementAndGet()
    }

    /** 消费端读取并批量发送 */
    fun drainAll(): List<NotificationData> {
        val result = ArrayList<NotificationData>()
        while (true) {
            val item = queue.poll() ?: break
            count.decrementAndGet()
            result.add(item)
        }
        return result
    }

    /** 当前队列长度 */
    fun size(): Int = count.get()

    /** 清空（服务停止 / 用户关闭通知同步时调用） */
    fun clear() {
        queue.clear()
        count.set(0)
    }
}
