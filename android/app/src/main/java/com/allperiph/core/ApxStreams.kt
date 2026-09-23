package com.allperiph.core

import java.io.ByteArrayOutputStream

/**
 * APX1 **收侧多路分发**：把一条媒体连接上按 streamId 混流的帧投递给各自订阅者。
 *
 * 为什么单独一层：v1.11 起同一条媒体连接（TCP 9502）上同时跑
 * 副屏视频（下行 0）、音箱（下行 1）、麦克风（上行 5）、摄像头（上行 6）。
 * 承载层只做「剥帧头 + 按 streamId 分发」，各流怎么处理（解码 / 播放 / 预览）
 * 由订阅者自己决定 —— 上层业务不感知承载，符合架构的传输抽象原则。
 *
 * 线程纪律：回调在**收流线程**上执行。实现方若要碰 UI/解码器，必须自己切线程；
 * 也**不要**在回调里做长耗时阻塞，否则会拖住整条连接的收流。
 * 发送方向见 [MediaOut]。
 */
object ApxStreams {

    /** 帧订阅者 */
    interface Consumer {
        /**
         * @param streamId 通道号（[ApxFrame.STREAM_*]）
         * @param flags    帧头 flags（[ApxFrame.FLAG_*]）
         * @param seq      帧序号；同一逻辑帧的各分片相同
         * @param body     已剥掉 16 字节帧头与尾部 u32 CRC 的载荷
         */
        fun onFrame(streamId: Int, flags: Int, seq: Int, body: ByteArray)
    }

    private val lock = Any()
    private val subs = HashMap<Int, MutableList<Consumer>>()

    /** 订阅某条流。同一 (streamId, consumer) 重复注册会被去重。 */
    fun register(streamId: Int, c: Consumer) {
        synchronized(lock) {
            val list = subs.getOrPut(streamId) { ArrayList(2) }
            if (!list.contains(c)) list.add(c)
        }
    }

    fun unregister(streamId: Int, c: Consumer) {
        synchronized(lock) {
            val list = subs[streamId] ?: return
            list.remove(c)
            if (list.isEmpty()) subs.remove(streamId)
        }
    }

    /** 模块 stop() 时一把清干净，避免持有已销毁对象的引用 */
    fun clear() {
        synchronized(lock) { subs.clear() }
    }

    fun hasSubscriber(streamId: Int): Boolean =
        synchronized(lock) { subs[streamId]?.isNotEmpty() == true }

    /**
     * 由承载层调用。**无订阅者时静默丢弃** —— 这是设计意图：手机侧没开副屏
     * 就不该因为收到视频帧而报错（PC 端可能先推了流）。
     */
    fun dispatch(streamId: Int, flags: Int, seq: Int, body: ByteArray) {
        val targets = synchronized(lock) { subs[streamId]?.toTypedArray() } ?: return
        for (t in targets) {
            // 单个订阅者抛异常不能影响其它流，也不能把收流线程带走
            runCatching { t.onFrame(streamId, flags, seq, body) }
                .onFailure { Log.w(TAG, "流 $streamId 的订阅者处理失败：${it.message}") }
        }
    }

    private const val TAG = "ApxStreams"
}

/**
 * 按 seq 拼接分片（Kotlin 侧等价于 `shared` 的 `FrameAssembler`）。
 *
 * 发送侧约定（见 `pc/display/transport/frame_writer.cpp::writeVideo`）：
 * 一帧被切成多个 **完整 APX1 帧**，**seq 相同**，且**只有末片**带
 * [ApxFrame.FLAG_LAST_FRAGMENT]。链路是可靠有序的（TCP），因此不处理乱序与重传。
 *
 * 中间丢片的表现是 seq 跳变 —— 此时丢弃已攒的部分，从新 seq 重新开始（宁可这一帧
 * 花屏，也不能把两帧拼成一帧）。
 */
class FragmentJoiner(private val maxBytes: Int = 8 * 1024 * 1024) {

    private var seq = Int.MIN_VALUE
    private val buf = ByteArrayOutputStream(256 * 1024)

    /**
     * @return 收齐一整帧时返回完整载荷；否则 null
     */
    fun push(flags: Int, frameSeq: Int, body: ByteArray): ByteArray? {
        if (seq != frameSeq) {
            buf.reset()
            seq = frameSeq
        }
        if (buf.size() + body.size > maxBytes) {
            Log.w(TAG, "分片累计超上限（${buf.size() + body.size} > $maxBytes），丢弃当前帧")
            reset()
            return null
        }
        buf.write(body, 0, body.size)
        if (flags and ApxFrame.FLAG_LAST_FRAGMENT == 0) return null
        val out = buf.toByteArray()
        reset()
        return out
    }

    fun reset() {
        buf.reset()
        seq = Int.MIN_VALUE
    }

    private companion object {
        const val TAG = "FragmentJoiner"
    }
}
