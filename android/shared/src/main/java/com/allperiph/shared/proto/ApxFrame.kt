package com.allperiph.shared.proto

/**
 * APX1 帧构造（`shared/include/apx/frame.h` 的 Kotlin 侧实现，docs/PROTOCOL.md §3）。
 *
 * **v1.8：从手机端 `com.allperiph.core.ApxFrame` 和 TV 端 `com.allperiph.tv.core.ApxFrame`
 * 提取到共享模块**，消除两端协议帧代码的重复维护问题。
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
 * 本文件只负责**发送方向**的组帧；接收解析在 9511 控制面（手机侧 TvControllerClient /
 * TV 侧 ControlServer / `pc/host/src/wireless/ctrl9511.cpp`），两侧 CRC 必须逐字节一致。
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
     *
     * ★ 线上布局必须是 `[0x25, hintLen(u8), hint(UTF-8)]` —— 另三处实现都是这个：
     *   · PC 发送 `ctrl9511.cpp::Ctrl9511Client::sendRequestInput`
     *   · PC 解析 `ctrl9511.cpp` 的 `Ctrl9511Server::dispatch`（`hintLen = p[1]`、文本从 `p+2`）
     *   · 手机 / TV 解析 `onRequestInput`（`hintLen = body[1]`、文本从 `body[2]`）
     *
     * **v1.7 修正**：本函数此前在 cmd 后多塞了 4 字节 `sourceId`，与上述四处**都不兼容** ——
     * 对端会把 `sourceId` 的最低字节当 hintLen、把后续字节当提示文字，表现为
     * "输入面板弹出来了但提示是乱码"。此前无调用方，属**潜在陷阱**（第一个调用它的人就会踩），
     * 故直接对齐线上布局。
     *
     * @param hint 输入框提示文字（如 "搜索"、"输入网址"），超 255 字节按 u8 上限截断
     * @param sourceId 保留入参（防环设计预留）：当前**不上线** —— 没有任何解析方读取它，
     *                 真要做防环需先改全部四处解析
     */
    fun packRequestInput(hint: String, sourceId: Int): ByteArray {
        val all = hint.toByteArray(Charsets.UTF_8)
        val hintBytes = if (all.size > 255) all.copyOf(255) else all
        val body = ByteArray(2 + hintBytes.size)
        body[0] = INPUT_REQUEST.toByte()
        body[1] = hintBytes.size.toByte()   // hintLen：u8（与 PC 侧 `b[1] = bytes.size()` 一致）
        hintBytes.copyInto(body, 2)
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
     * 校验一帧尾部的 u32 CRC32（覆盖 body，即 payload 去掉最后 4 字节）。
     *
     * v184：此前接收侧**完全不校验** —— 一旦缓冲错位或链路进了脏数据，
     * 坏载荷会被直接喂给解码器 / AudioTrack / 注入器（花屏、爆音、乱点）。
     * 与 PC 侧 `apx::verifyPayload` 行为一致。
     *
     * @return true = 校验通过；false = 该帧应丢弃（缓冲位数不足也返回 false）
     */
    fun verify(buf: ByteArray, off: Int, payloadLen: Int): Boolean {
        if (payloadLen < CRC_SIZE) return false
        val bodyLen = payloadLen - CRC_SIZE
        val crcAt = off + HEADER_SIZE + bodyLen
        if (crcAt + 4 > buf.size) return false
        return crc32(buf, off + HEADER_SIZE, bodyLen) == getU32(buf, crcAt)
    }

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
