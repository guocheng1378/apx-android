package com.allperiph.core

/**
 * §2.7 Vendor OUT 命令（PC → 手机）的 Kotlin 侧表示。
 * 字节解析由 shared 完成（[ApxNative.parseVendorOut]），这里只做语义封装。
 */
data class VendorCommand(
    val cmd: Int,
    val seq: Int,
    /** 原始 payload 字节，按 [VendorCmd] 语义解释 */
    val payload: ByteArray,
) {
    /** 小端读取 payload 中的 u16 */
    fun u16(off: Int): Int {
        if (off + 1 >= payload.size) return 0
        return (payload[off].toInt() and 0xFF) or ((payload[off + 1].toInt() and 0xFF) shl 8)
    }

    fun u8(off: Int): Int = if (off < payload.size) payload[off].toInt() and 0xFF else 0

    fun u32(off: Int): Long {
        if (off + 3 >= payload.size) return 0L
        var v = 0L
        for (i in 0..3) v = v or ((payload[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VendorCommand) return false
        return cmd == other.cmd && seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = (cmd * 31 + seq) * 31 + payload.contentHashCode()
}

/** Vendor 命令消费者（M5 vibe、M1 sensor、M2 gadget 各自实现） */
fun interface VendorCommandSink {
    /** 返回 true 表示已消费，不再继续分发 */
    fun onVendorCommand(cmd: VendorCommand): Boolean
}

/**
 * 把一条原始 OUT 报告解码为 [VendorCommand]。
 * 报告首字节必须是 Report ID 5，长度校验交给 shared。
 */
fun decodeVendorCommand(report: ByteArray): VendorCommand? {
    if (report.isEmpty() || report[0].toInt() and 0xFF != ReportId.VENDOR) return null
    val fields = runCatching { ApxNative.parseVendorOut(report) }.getOrNull() ?: return null
    if (fields.size < 3) return null
    val cmd = fields[0]
    val seq = fields[1]
    val len = fields[2].coerceIn(0, fields.size - 3)
    val payload = ByteArray(len) { fields[3 + it].toByte() }
    return VendorCommand(cmd, seq, payload)
}
