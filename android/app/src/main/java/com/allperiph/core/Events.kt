package com.allperiph.core

/**
 * 事件定义（core/ 全局，UI 与模块共用）。
 * 数据面事件（传感器样本）**不**走总线，避免每帧分配；只有状态/控制事件走总线。
 */
data class LogEvent(val entry: Log.Entry)

/** M2 挂载状态变化 */
data class GadgetStateEvent(
    val state: ModuleState,
    val linkSpeed: LinkSpeed,
    val udc: String?,
    val detail: String,
)

/** UDC 速度门禁结果（架构 §6.1） */
data class LinkSpeedDegradedEvent(
    val linkSpeed: LinkSpeed,
    val message: String,
)

/** M1 传感器引擎状态 */
data class SensorStatusEvent(
    val state: ModuleState,
    val enabledMask: Long,
    val activeChannels: List<String>,
    val droppedSamples: Long,
    val notice: String?,
)

/** M3 GPS 状态 */
data class GpsStatusEvent(
    val state: ModuleState,
    val sentences: Long,
    val lastSentenceType: String?,
    val notice: String?,
)

/** §2.7 PC 下发的 Vendor 命令（由 M2 读取 /dev/hidg0 后广播） */
data class VendorCommandEvent(val command: VendorCommand)

/** M5 执行结果 */
data class VibeStatusEvent(
    val cmd: Int,
    val ok: Boolean,
    val detail: String,
)

/** 整代理状态汇总，供 UI 与 Report 5 状态上报共用（v1.1：位图 64 位 + lastSeq 回显） */
data class AgentStateEvent(
    val state: ModuleState,
    val status: Int,
    val linkSpeed: LinkSpeed,
    /** §2.9 位图：传感器位（bit0..25）+ 模块位（bit32..37） */
    val moduleMask: Long,
    val errorCode: Int,
    val uptimeMs: Long,
    /** §2.7 lastSeq：最后被执行的控制命令 seq（被拒绝的命令不回显） */
    val lastSeq: Int = 0,
)

/** PC 请求打开副屏页（控制通道 0x05，由面板开启副屏推流时下发） */
class ScreenOpenRequestEvent
