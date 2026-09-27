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
 * 本文件只负责**发送方向**的组帧；接收解析在 9511 控制面（[com.allperiph.wireless.TvControllerClient] /
 * `pc/host/src/wireless/ctrl9511.cpp`），两侧 CRC 必须逐字节一致。
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

    /** flags 位（与 frame.h 的 kFlag* 对应） */
    const val FLAG_KEY_FRAME = 1 shl 0
    const val FLAG_LAST_FRAGMENT = 1 shl 1
    const val FLAG_DROPABLE = 1 shl 2

    /** 单帧载荷上限（含 CRC），与 kMaxFramePayload 一致；超出的帧直接判为非法 */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    // ———————————————————— 远程输入帧类型（docs/REMOTE-INPUT.md）———————————————————
    /**
     * 远程输入帧子类型（payload[0]）。
     * 帧本身走 STREAM_CONTROL 通道，子类型在 payload 首字节区分。
     * 注意：0x20=剪贴板, 0x21=反向剪贴板, 0x22=电源动作，故远程输入从 0x25 开始。
     */
    const val INPUT_REQUEST = 0x25   // A→B：请求 B 设备输入
    const val INPUT_TEXT = 0x26      // B→A：实时输入文本
    const val INPUT_DONE = 0x27      // B→A：输入完成

    /** INPUT_TEXT flags */
    const val INPUT_FLAG_INCREMENTAL = 0x01  // 增量字符（默认）
    const val INPUT_FLAG_BACKSPACE = 0x02    // 退格
    const val INPUT_FLAG_COMMIT = 0x04       // 完整文本（粘贴整段）
    const val INPUT_FLAG_CANCEL = 0x08       // 用户取消

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

    // ———————————————————— 远程输入组帧 ————————————————————

    /**
     * 组 REQUEST_INPUT 帧（A→B）：请求对端设备输入。
     * @param hint 输入框提示文字（如 "搜索"、"输入网址"）
     * @param sourceId 发送方标识（本机 MAC 前 4 字节），用于防环
     */
    fun packRequestInput(hint: String, sourceId: Int): ByteArray {
        val hintBytes = hint.toByteArray(Charsets.UTF_8)
        val body = ByteArray(1 + 4 + 1 + hintBytes.size)
        body[0] = INPUT_REQUEST.toByte()
        putU32(body, 1, sourceId)
        body[5] = hintBytes.size.toByte()
        hintBytes.copyInto(body, 6)
        return body
    }

    /**
     * 组 INPUT_TEXT 帧（B→A）：实时输入文本。
     * @param flags INPUT_FLAG_* 位组合
     * @param text 输入的文本（增量/完整）
     */
    fun packInputText(flags: Int, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val body = ByteArray(1 + 1 + 2 + textBytes.size)
        body[0] = INPUT_TEXT.toByte()
        body[1] = flags.toByte()
        putU16(body, 2, textBytes.size)
        textBytes.copyInto(body, 4)
        return body
    }

    /**
     * 组 INPUT_DONE 帧（B→A）：输入完成。
     */
    fun packInputDone(): ByteArray {
        return byteArrayOf(INPUT_DONE.toByte())
    }

    // ———————————————————— 帧解析工具 ————————————————————

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