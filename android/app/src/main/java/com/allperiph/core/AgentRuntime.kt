package com.allperiph.core

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 运行时容器：实现 [ModuleContext]，持有模块注册表与可热替换的传输口。
 *
 * 依赖方向：模块只依赖 core 的接口；gadget 挂载成功后通过 [attachHid] / [attachSerial]
 * 把真实实现注入，业务模块引用的代理对象无需重建（链路自愈时重新 attach 即可）。
 */
class AgentRuntime(private val app: Context) : ModuleContext {

    override val appContext: Context get() = app
    override val bus: EventBus get() = EventBus
    override val mainHandler: Handler = Handler(Looper.getMainLooper())

    val hidPort = DelegatingHidTransport()
    val serialPort = DelegatingSerialSink()

    override val hid: HidTransport get() = hidPort
    override val serial: SerialSink get() = serialPort

    val registry = ModuleRegistry()

    @Volatile
    var lastErrorCode: Int = 0
        set(value) {
            field = value
            Log.w("AgentRuntime", "errorCode=$value")
        }

    fun register(module: Module) = registry.register(module)

    override fun module(id: String): Module? = registry.get(id)

    override fun maskBits(): Long = registry.maskBits()

    override fun mask(): Int = registry.mask()

    // ————————— §2.7 lastSeq / errorCode：命令执行回显（v1.1）—————————

    /** 最后**执行成功**的命令 seq；被拒绝的命令不更新，PC 侧据此判断命令是否生效 */
    @Volatile
    var lastSeq: Int = 0
        private set

    private var pendingSeq: Int = -1

    @Volatile
    private var pendingRejected: Boolean = false

    /** 收到一条 OUT 命令时调用（HID 读线程内，命令分发前） */
    fun beginCommand(seq: Int) {
        pendingSeq = seq
        pendingRejected = false
    }

    /** 消费者判定无法执行时调用：置错误码，并阻止本条命令回显 seq */
    fun rejectCommand(code: Int) {
        lastErrorCode = code
        pendingRejected = true
    }

    /**
     * 命令分发完成后调用（EventBus 为同步派发，返回即代表同步消费者已处理完）。
     * 未被拒绝 → 回显 seq 并清除错误码；被拒绝 → 保留错误码，不回显。
     */
    fun endCommand() {
        val seq = pendingSeq
        pendingSeq = -1
        if (seq < 0) return
        if (pendingRejected) {
            Log.w("AgentRuntime", "command seq=$seq rejected, errorCode=$lastErrorCode")
        } else {
            lastSeq = seq
            if (lastErrorCode != 0) lastErrorCode = 0
        }
        pendingRejected = false
    }

    fun attachHid(t: HidTransport) = hidPort.attach(t)
    fun detachHid() = hidPort.detach()
    fun attachSerial(t: SerialSink) = serialPort.attach(t)
    fun detachSerial() = serialPort.detach()

    /** 汇总状态，供 UI 与 Report 5 上报共用 */
    fun snapshotStatus(linkSpeed: LinkSpeed): AgentStateEvent {
        val states = registry.all().map { it.state }
        val overall = when {
            states.any { it == ModuleState.ERROR } -> ModuleState.ERROR
            states.any { it == ModuleState.DEGRADED } -> ModuleState.DEGRADED
            states.any { it == ModuleState.RUNNING } -> ModuleState.RUNNING
            else -> ModuleState.IDLE
        }
        val status = when (overall) {
            ModuleState.ERROR -> AgentStatus.ERROR
            ModuleState.DEGRADED -> AgentStatus.DEGRADED_USB2
            ModuleState.RUNNING -> AgentStatus.RUNNING
            else -> AgentStatus.IDLE
        }
        return AgentStateEvent(
            state = overall,
            status = status,
            linkSpeed = linkSpeed,
            moduleMask = registry.maskBits(),
            errorCode = lastErrorCode,
            uptimeMs = android.os.SystemClock.elapsedRealtime(),
            lastSeq = lastSeq,
        )
    }
}
