package com.allperiph.core

/**
 * PROTOCOL.md 中出现的**所有枚举与魔数**集中在此。
 * 业务代码禁止出现裸数字（报告 ID / 命令号 / 状态码一律取这里）。
 */

/** §2 HID Report ID */
object ReportId {
    const val IMU_BATCH = 1
    const val LOW_FREQ = 2
    const val DIGITIZER = 3
    const val CONSUMER = 4
    const val VENDOR = 5
    const val BATTERY = 6
}

/** §2.3 / §2.4 传感器 ID（HID 侧编号，与 Android Sensor.TYPE 解耦） */
object SensorId {
    // —— Report 1：批量三轴 ——
    const val ACCEL = 0
    const val GYRO = 1
    const val MAG = 2
    const val UNCAL_ACCEL = 3
    const val UNCAL_GYRO = 4
    const val UNCAL_MAG = 5

    // —— Report 2：低频 ——
    const val LIGHT = 0
    const val PROXIMITY = 1
    const val PRESSURE = 2
    const val ORIENTATION = 3
    const val INCLINOMETER = 4
    const val DEVICE_TEMP = 5
    const val BATTERY_TEMP = 6
    const val HUMIDITY = 7
    const val STEP_COUNTER = 8
    const val HEART_RATE = 9

    const val COUNT_BATCH = 6
    const val COUNT_LOW = 10
}

/** §2.3 flags 位 */
object ImuFlag {
    const val NEED_RESYNC = 0x01
    const val SAMPLE_LOST = 0x02
}

/** §2.4 state / event */
object SensorState {
    const val UNKNOWN = 0
    const val READY = 1
    const val NOT_AVAILABLE = 2
    const val ERROR = 3
}

object SensorEvent {
    const val UNKNOWN = 0
    const val THRESHOLD_HIGH = 1
    const val THRESHOLD_LOW = 2
    const val PERIOD_EXCEEDED = 3
    const val CHANGE = 4
}

/** §2.7 Vendor OUT 命令 */
object VendorCmd {
    const val VIBRATE = 0x01
    const val VIBRATE_STOP = 0x02
    const val TORCH = 0x03
    const val IR_SEND = 0x04
    const val SET_SENSOR_MASK = 0x10
    const val SET_SAMPLE_RATE = 0x11
    const val SET_DISPLAY_MODE = 0x12
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

/**
 * §2.7 / §2.9 moduleMask 位（模块启用位图，64 位宽）。
 *
 * v1.1 起模块级位固定为 bit32..37，与传感器掩码（bit0..5 批量 / bit16..25 低频）
 * 共用 64 位但互不重叠，可直接 OR 后上报。
 */
object ModuleMask {
    /** §2.9 bit32 触控 / 笔（Report 3） */
    const val TOUCH_PEN: Long = 1L shl 32

    /** §2.9 bit33 Consumer 按键（Report 4） */
    const val CONSUMER_KEYS: Long = 1L shl 33

    /** §2.9 bit34 电池状态（Report 6） */
    const val BATTERY: Long = 1L shl 34

    /** §2.9 bit35 GPS（CDC ACM） */
    const val GPS: Long = 1L shl 35

    /** §2.9 bit36 振动 / 手电 / 红外（Report 5 控制） */
    const val VIBE: Long = 1L shl 36

    /** §2.9 bit37 副屏视频（bulk streamId 0） */
    const val SCREEN_VIDEO: Long = 1L shl 37

    /** §2.9 bit39 摄像头（UVC 复合设备 / 系统摄像头） */
    const val CAMERA: Long = 1L shl 39
}

/**
 * 错误码（写入 §2.7 的 `errorCode`）。
 *
 * PROTOCOL 目前只给字段没给取值表，这里先按「0x01xx = 控制面」自建，
 * 已作为 contractDeviations 请 main 在 PROTOCOL 中固化，避免 PC 侧自行猜测。
 */
object ErrorCode {
    const val NONE = 0
    /** 严格模式下请求的采样率无法达到 */
    const val RATE_UNSUPPORTED = 0x0101
    /** 该传感器在本机不存在（或合成量缺少源） */
    const val SENSOR_UNAVAILABLE = 0x0102
    /** §2.9 位号无对应传感器 */
    const val UNKNOWN_SENSOR_BIT = 0x0103
    /** 低频通道不采样率控制（保留） */
    const val NOT_SUPPORTED = 0x0104
}

/** §2.3 批量上限：N ≤ 16 */
const val IMU_MAX_BATCH = 16

/** §2.1 单报告不得超过 1024 字节 */
const val HID_MAX_REPORT_BYTES = 1024

/** §4 心跳：1s 一次，3 次无响应判定断线 */
const val HEARTBEAT_INTERVAL_MS = 1_000L
const val HEARTBEAT_MISS_LIMIT = 3
const val HEARTBEAT_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * HEARTBEAT_MISS_LIMIT

/** 架构 §6.3：Android 12+ registerListener 硬上限 */
const val SENSOR_RATE_LIMIT_HZ = 200

/** §5 协议版本 1.1 → u16 高位为主版本、低位为次版本 */
const val PROTOCOL_VERSION = 0x0110

/** §2.7 OUT 报告中 `sensorId` 的 bit7：1=尽力而为，0=严格模式 */
const val SENSOR_ID_BEST_EFFORT = 0x80
const val SENSOR_ID_MASK = 0x7F
