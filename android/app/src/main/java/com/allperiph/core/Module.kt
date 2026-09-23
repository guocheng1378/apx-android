package com.allperiph.core

import android.os.Handler

/**
 * 模块生命周期契约（core/ 提供的骨架）。
 * 约定：start() 幂等；stop() 可重复调用；任何抛出的异常由调用方记录而非向上扩散。
 */
enum class ModuleState {
    IDLE,
    STARTING,
    RUNNING,
    /** 功能可用但能力受限（如 USB 2.0 降级） */
    DEGRADED,
    ERROR,
    STOPPING,
    STOPPED,
    ;

    val isActive: Boolean get() = this == STARTING || this == RUNNING || this == DEGRADED
}

interface Module {
    /** 稳定 ID，见 [ModuleId] */
    val id: String

    /** 当前状态；由实现自行维护并置为 volatile */
    val state: ModuleState

    /** 一行人类可读状态，直接给 UI/诊断用 */
    fun statusText(): String

    fun start(ctx: ModuleContext)

    fun stop()

    /**
     * 本模块在 §2.9 位域中占用的位（64 位宽，可多位；模块自报，避免跨模块互相引用）。
     * 默认 0 = 不占位（如 gadget 只是链路本身，不算功能模块）。
     * 约定：仅当模块处于活动状态时返回非零，便于状态上报反映「已启用模块」。
     */
    fun maskBits(): Long = 0
}

/** 模块 ID 常量，避免字符串散落 */
object ModuleId {
    const val GADGET = "gadget"

    /** UAC2 声卡搬运（手机麦克风 ↔ PC 扬声器） */
    const val AUDIO = "audio"

    /** 手机当触控板（相对鼠标语义，手势在手机端识别） */
    const val TOUCHPAD = "touchpad"

    /** 蓝牙 HID 设备（PC 零驱动识别为鼠标/键盘/多媒体键） */
    const val BTHID = "bthid"

    /** Wi‑Fi 控制（局域网 TCP）：无蓝牙适配器的 PC 上承载触控板 / 键盘 / 多媒体 */
    const val WIRELESS = "wireless"
}

/**
 * 模块运行时上下文：由 core 的服务编排层注入。
 * 模块之间**不直接互相引用**，一律通过 [bus] 与传输接口解耦。
 */
interface ModuleContext {
    val appContext: android.content.Context
    val bus: EventBus
    val mainHandler: Handler

    /** HID IN 报告出口（由 M2 gadget 提供；未挂载时降级为空实现） */
    val hid: HidTransport

    /** CDC ACM 出口（由 M2 gadget 提供） */
    val serial: SerialSink

    fun module(id: String): Module?

    /** §2.9 协议位图（64 位）：传感器位 + 模块位，互不重叠可直接 OR */
    fun maskBits(): Long

    /** 兼容旧调用方：模块位（bit32..37 右移 32 后的低字节），用于计数与展示 */
    fun mask(): Int
}

/** 模块注册表：按注册顺序启动、逆序停止（依赖天然满足：gadget 先启后停） */
class ModuleRegistry {
    private val ordered = LinkedHashMap<String, Module>()

    fun register(m: Module) {
        ordered[m.id] = m
    }

    fun get(id: String): Module? = ordered[id]

    fun all(): List<Module> = ordered.values.toList()

    /** §2.9 协议位图：各模块自报位之或（传感器位 + 模块位） */
    fun maskBits(): Long = ordered.values.fold(0L) { acc, m -> acc or m.maskBits() }

    /** 兼容：模块位（bit32..37）下移 32 位，使旧调用方 `Integer.bitCount(mask)` 语义不变 */
    fun mask(): Int = (maskBits() ushr 32).toInt()

    fun startAll(ctx: ModuleContext) {
        for (m in ordered.values) {
            runCatching { m.start(ctx) }
                .onFailure { Log.e("ModuleRegistry", "start ${m.id} failed", it) }
        }
    }

    /** 逆序停止，保证下游先释放设备节点 */
    fun stopAll() {
        for (m in ordered.values.reversed()) {
            runCatching { m.stop() }
                .onFailure { Log.e("ModuleRegistry", "stop ${m.id} failed", it) }
        }
    }
}