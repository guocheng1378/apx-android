package com.allperiph.core

/**
 * PROTOCOL.md 中出现的**所有枚举与魔数**集中在此。
 * 业务代码禁止出现裸数字（报告 ID / 命令号 / 状态码一律取这里）。
 */

/** §2 HID Report ID */
object ReportId {
    const val LOW_FREQ = 2
    const val DIGITIZER = 3
    const val CONSUMER = 4
    const val VENDOR = 5
    const val BATTERY = 6
}

/** §2.7 Vendor OUT 命令 */
object VendorCmd {
    const val HEARTBEAT = 0x7F
}

/** §2.7 状态上报 status */
object AgentStatus {
    const val IDLE = 0
    const val RUNNING = 1
    const val ERROR = 2
    const val DEGRADED_USB2 = 3
}

/** §2.7 linkSpeed（同时用于 current_speed 解析） */
enum class LinkSpeed(val code: Int, val label: String) {
    UNKNOWN(0, "unknown"),
    FULL(1, "full-speed"),
    HIGH(2, "high-speed"),
    SUPER(3, "super-speed"),
    SUPER_PLUS(4, "super-speed-plus"),
    ;

    val isSuperSpeed: Boolean get() = this == SUPER || this == SUPER_PLUS

    companion object {
        fun of(code: Int): LinkSpeed = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**\ * §2.7 / §2.9 moduleMask 位（模块启用位图，64 位宽）。
 */
object ModuleMask {
    /** §2.9 bit32 触控 / 笔（Report 3） */
    const val TOUCH_PEN: Long = 1L shl 32

    /** §2.9 bit33 Consumer 按键（Report 4） */
    const val CONSUMER_KEYS: Long = 1L shl 33

    /** §2.9 bit34 电池状态（Report 6） */
    const val BATTERY: Long = 1L shl 34
}

/** §2.1 单报告不得超过 1024 字节 */
const val HID_MAX_REPORT_BYTES = 1024

/** §4 心跳：1s 一次，3 次无响应判定断线 */
const val HEARTBEAT_INTERVAL_MS = 1_000L
const val HEARTBEAT_MISS_LIMIT = 3
const val HEARTBEAT_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * HEARTBEAT_MISS_LIMIT

/** §5 协议版本 1.1 → u16 高位为主版本、低位为次版本 */
const val PROTOCOL_VERSION = 0x0110