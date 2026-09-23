package com.allperiph.core

/**
 * APX1 帧构造（`shared/include/apx/frame.h` 的 Kotlin 侧实现，docs/PROTOCOL.md §3）。
 *
 * 帧结构（**16 字节固定头**，多字节字段显式小端，不依赖主机字节序）：
 * ```
 * off  size  field
 *  0    4    magic 'A' 'P' 'X' '1'
 *  4    1    streamId    0=video 1=audio 2=touch 3=ctrl 4=telemetry
 *  5    1    flags       bit0=keyframe bit1=last_fragment bit2=dropable
 *  6    2    headerExtWords
 *  8    4    payloadLen  = body 字节数 + 4（尾部 u32 CRC32）
 * 12    4    seq
 * 16   ...  body
 *      4    CRC32(body)，IEEE 802.3 反射多项式 0xEDB88320
 * ```
 *
 * 本文件只负责**发送方向**的组帧；接收解析在 [TcpControlChannel]，
 * 对端解码在 `pc/host/src/wireless/wireless_link.cpp`。两侧 CRC 必须逐字节一致。
 */
object ApxFrame {
    const val HEADER_SIZE = 16
    const val CRC_SIZE = 4

    /**
     * streamId 取值（与 `shared/include/apx/frame.h` 的 kStream* 一一对应）。
     * v1.11 起**带方向语义**：下行 = PC → 手机，上行 = 手机 → PC。
     * 音频之所以分成两个号：「音箱」（PC 声 → 手机扬声器）与「麦克风»
     * （手机录音 → PC）方向相反，共用 STREAM_AUDIO 会互相污染。
     */
    const val STREAM_VIDEO = 0      // 下行：副屏画面
    const val STREAM_AUDIO = 1      // 下行：音箱（PC 系统声，PCM s16le/48k/立体声）
    const val STREAM_TOUCH = 2      // 预留
    const val STREAM_CONTROL = 3    // 双向：控制面（鼠标/键盘/多媒体/心跳）
    const val STREAM_TELEMETRY = 4  // 预留
    const val STREAM_MIC = 5        // 上行：麦克风（手机录音，PCM s16le/48k/立体声）
    const val STREAM_CAMERA = 6     // 上行：摄像头（JPEG 帧）

    /** flags 位（与 frame.h 的 kFlag* 对应） */
    const val FLAG_KEY_FRAME = 1 shl 0
    const val FLAG_LAST_FRAGMENT = 1 shl 1
    const val FLAG_DROPABLE = 1 shl 2

    /** 单帧载荷上限（含 CRC），与 kMaxFramePayload 一致；超出的帧直接判为非法 */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    private val crcTable = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            t[i] = c
        }
    }

    /** zlib / IEEE 802.3 CRC32（初值全 1、结果取反），与 `shared/src/frame.cpp` 的 crc32 等价 */
    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var c = -1
        for (i in offset until offset + length) {
            c = (c ushr 8) xor crcTable[(c xor data[i].toInt()) and 0xFF]
        }
        return c.inv()
    }

    /**
     * 组一帧（帧头 + body + 尾部 CRC32）。
     * @param streamId 通道号；控制面用 [STREAM_CONTROL]
     * @param body     业务载荷（不含 CRC）
     * @param seq      帧序号（同一逻辑帧的各分片相同）
     */
    fun build(streamId: Int, body: ByteArray, seq: Int, flags: Int = 0): ByteArray {
        val payloadLen = body.size + CRC_SIZE
        val out = ByteArray(HEADER_SIZE + payloadLen)
        out[0] = 'A'.code.toByte()
        out[1] = 'P'.code.toByte()
        out[2] = 'X'.code.toByte()
        out[3] = '1'.code.toByte()
        out[4] = streamId.toByte()
        out[5] = flags.toByte()
        putU16(out, 6, 0)                 // headerExtWords
        putU32(out, 8, payloadLen)
        putU32(out, 12, seq)
        body.copyInto(out, HEADER_SIZE)
        putU32(out, HEADER_SIZE + body.size, crc32(body))
        return out
    }

    /** 校验帧头 magic；不是 'APX1' 则返回 false（用于收流对齐） */
    fun isMagic(buf: ByteArray, off: Int, len: Int): Boolean =
        len - off >= 4 &&
            buf[off] == 'A'.code.toByte() && buf[off + 1] == 'P'.code.toByte() &&
            buf[off + 2] == 'X'.code.toByte() && buf[off + 3] == '1'.code.toByte()

    /** 读帧头里的 payloadLen（小端 u32） */
    fun payloadLenAt(buf: ByteArray, off: Int): Int = getU32(buf, off + 8)

    /** 读帧头里的 streamId */
    fun streamIdAt(buf: ByteArray, off: Int): Int = buf[off + 4].toInt() and 0xFF

    /** 读帧头里的 flags */
    fun flagsAt(buf: ByteArray, off: Int): Int = buf[off + 5].toInt() and 0xFF

    /** 读帧头里的 seq */
    fun seqAt(buf: ByteArray, off: Int): Int = getU32(buf, off + 12)

    /** 载荷剔除尾部 u32 CRC 后的 body 长度（协议：payloadLen 含那 4 字节）；非法返回 -1 */
    fun bodyLenOf(payloadLen: Int): Int = if (payloadLen < CRC_SIZE) -1 else payloadLen - CRC_SIZE

    /**
     * 取出 body（**不含**尾部 CRC）。接收侧普遍不校验 CRC，但消费方（解码器 /
     * AudioTrack / JPEG 预览）只想要干净载荷，所以统一在这里剥掉。
     * @return body 副本；越界或长度非法返回 null
     */
    fun bodyAt(buf: ByteArray, off: Int, payloadLen: Int): ByteArray? {
        val n = bodyLenOf(payloadLen)
        if (n < 0 || off + HEADER_SIZE < 0) return null
        val end = off + HEADER_SIZE + n
        if (end > buf.size) return null
        return buf.copyOfRange(off + HEADER_SIZE, end)
    }

    /** 整个帧的字节数（帧头 + 载荷） */
    fun totalSize(payloadLen: Int): Int = HEADER_SIZE + payloadLen

    private fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(dst: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) dst[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun getU32(src: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or (((src[off + i].toInt() and 0xFF)) shl (8 * i))
        return v
    }
}
