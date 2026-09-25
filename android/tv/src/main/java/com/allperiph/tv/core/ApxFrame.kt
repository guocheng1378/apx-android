package com.allperiph.tv.core

/**
 * APX1 帧构造（与 `shared/include/apx/frame.h` 及手机端 `ApxFrame` 逐字节一致）。
 * 帧结构（16 字节固定头，多字节字段显式小端）：
 * ```
 * off  size  field
 *  0    4    magic 'A' 'P' 'X' '1'
 *  4    1    streamId    0=video 1=audio 2=touch 3=ctrl 4=telemetry 5=mic
 *  5    1    flags       bit0=keyframe bit1=last_fragment bit2=dropable
 *  6    2    headerExtWords
 *  8    4    payloadLen  = body 字节数 + 4（尾部 u32 CRC32）
 * 12    4    seq
 * 16   ...  body
 *      4    CRC32(body)，IEEE 802.3 反射多项式 0xEDB88320
 * ```
 * 本对象只负责**发送方向**组帧；接收解析在 [TcpControlServer]。
 */
object ApxFrame {
    const val HEADER_SIZE = 16
    const val CRC_SIZE = 4

    const val STREAM_VIDEO = 0
    const val STREAM_AUDIO = 1
    const val STREAM_TOUCH = 2
    const val STREAM_CONTROL = 3
    const val STREAM_TELEMETRY = 4
    const val STREAM_MIC = 5

    const val FLAG_KEY_FRAME = 1 shl 0
    const val FLAG_LAST_FRAGMENT = 1 shl 1
    const val FLAG_DROPABLE = 1 shl 2

    /** 单帧载荷上限（含 CRC），与 kMaxFramePayload 一致 */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    private val crcTable = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            t[i] = c
        }
    }

    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var c = -1
        for (i in offset until offset + length) {
            c = (c ushr 8) xor crcTable[(c xor data[i].toInt()) and 0xFF]
        }
        return c.inv()
    }

    fun build(streamId: Int, body: ByteArray, seq: Int, flags: Int = 0): ByteArray {
        val payloadLen = body.size + CRC_SIZE
        val out = ByteArray(HEADER_SIZE + payloadLen)
        out[0] = 'A'.code.toByte()
        out[1] = 'P'.code.toByte()
        out[2] = 'X'.code.toByte()
        out[3] = '1'.code.toByte()
        out[4] = streamId.toByte()
        out[5] = flags.toByte()
        putU16(out, 6, 0)
        putU32(out, 8, payloadLen)
        putU32(out, 12, seq)
        body.copyInto(out, HEADER_SIZE)
        putU32(out, HEADER_SIZE + body.size, crc32(body))
        return out
    }

    fun isMagic(buf: ByteArray, off: Int, len: Int): Boolean =
        len - off >= 4 &&
            buf[off] == 'A'.code.toByte() && buf[off + 1] == 'P'.code.toByte() &&
            buf[off + 2] == 'X'.code.toByte() && buf[off + 3] == '1'.code.toByte()

    fun payloadLenAt(buf: ByteArray, off: Int): Int = getU32(buf, off + 8)
    fun streamIdAt(buf: ByteArray, off: Int): Int = buf[off + 4].toInt() and 0xFF
    fun flagsAt(buf: ByteArray, off: Int): Int = buf[off + 5].toInt() and 0xFF
    fun seqAt(buf: ByteArray, off: Int): Int = getU32(buf, off + 12)
    fun bodyLenOf(payloadLen: Int): Int = if (payloadLen < CRC_SIZE) -1 else payloadLen - CRC_SIZE

    fun bodyAt(buf: ByteArray, off: Int, payloadLen: Int): ByteArray? {
        val n = bodyLenOf(payloadLen)
        if (n < 0 || off + HEADER_SIZE < 0) return null
        val end = off + HEADER_SIZE + n
        if (end > buf.size) return null
        return buf.copyOfRange(off + HEADER_SIZE, end)
    }

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
