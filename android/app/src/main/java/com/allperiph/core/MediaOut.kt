package com.allperiph.core

/**
 * 媒体流**发侧出口**：手机 → PC 的上行流（麦克风 5、摄像头 6）。
 *
 * 与 [TcpCtrlBridge] 同一套路：业务模块只调这里，不感知承载是 TCP 还是别的。
 * 未连入时 [ready] 为 false，各方法返回 false —— **调用方必须如实降级，不得假装已送达**。
 *
 * 线程安全：`send` 只做「组帧 + 入队」，不碰 socket（麦克风/AudioRecord 回调、
 * Camera2 的 ImageReader 回调都可能在高优先级线程上，直接 write 会踩
 * NetworkOnMainThreadException，与 ROADMAP 记的那次真机教训同源）。
 */
object MediaOut {

    /** 由承载层（`wireless/TcpMediaChannel`）实现的出口 */
    interface Sink {
        val ready: Boolean
        /** @return 是否成功入队（true ≠ 已送达） */
        fun send(streamId: Int, body: ByteArray, flags: Int): Boolean
    }

    @Volatile
    private var sink: Sink? = null

    fun attach(s: Sink) {
        sink = s
    }

    fun detach() {
        sink = null
    }

    fun ready(): Boolean = sink?.ready == true

    /**
     * 麦克风 PCM 上行（s16le / 48k / 立体声）。
     * 建议按 **10ms = 1920 字节**一片，与 `AudioModule` 的 FRAME_BYTES 一致。
     */
    fun mic(pcm: ByteArray, len: Int = pcm.size): Boolean {
        val s = sink ?: return false
        if (!s.ready || len <= 0) return false
        val body = if (len == pcm.size) pcm else pcm.copyOf(len)
        return s.send(ApxFrame.STREAM_MIC, body, 0)
    }

    /** 摄像头上行。JPEG 旧版或 H264（一 buffer 一帧）通用；flags 传关键帧标记。 */
    fun camera(body: ByteArray, flags: Int = ApxFrame.FLAG_KEY_FRAME): Boolean {
        val s = sink ?: return false
        if (!s.ready || body.isEmpty()) return false
        return s.send(ApxFrame.STREAM_CAMERA, body, flags)
    }

    // —— 副屏触摸（streamId=2，8 字节小帧，格式与 PC touch_inject.hpp 逐字节一致）——
    const val TOUCH_DOWN = 0
    const val TOUCH_UP = 1
    const val TOUCH_MOVE = 2
    const val TOUCH_CANCEL = 3
    const val BTN_LEFT = 1
    const val BTN_RIGHT = 2
    const val BTN_MIDDLE = 4

    /**
     * 副屏触摸上行：坐标按**手机画面区域**归一化到 0..65535，
     * PC 侧映射主显示器后 SendInput。每手势约 60 帧/秒，帧极小（8 字节）。
     */
    fun touch(action: Int, buttons: Int, x: Int, y: Int, pointerId: Int = 0): Boolean {
        val s = sink ?: return false
        if (!s.ready) return false
        val b = ByteArray(8)
        b[0] = action.toByte()
        b[1] = buttons.toByte()
        b[2] = (x and 0xFF).toByte()
        b[3] = ((x shr 8) and 0xFF).toByte()
        b[4] = (y and 0xFF).toByte()
        b[5] = ((y shr 8) and 0xFF).toByte()
        b[6] = (pointerId and 0xFF).toByte()
        b[7] = 0
        return s.send(ApxFrame.STREAM_TOUCH, b, 0)
    }
}
