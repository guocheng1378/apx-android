package com.allperiph.core

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 运行时容器：实现 [ModuleContext]，持有模块注册表与可热替换的传输口。
 *
 * 优化 #10：snapshotStatus() 增加脏标记缓存，
 * 只在模块状态变化时重算 snapshot，避免每帧（8ms）都 map + fold。
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

    @Volatile
    var lastSeq: Int = 0
        private set

    private var pendingSeq: Int = -1

    @Volatile
    private var pendingRejected: Boolean = false

    fun beginCommand(seq: Int) {
        pendingSeq = seq
        pendingRejected = false
    }

    fun rejectCommand(code: Int) {
        lastErrorCode = code
        pendingRejected = true
    }

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

    // ———— 优化 #10：snapshotStatus 缓存 ————
    // watchdog 每 HEARTBEAT_INTERVAL_MS（约 1s）调一次，但 8ms PTP 帧线程
    // 也会间接触发（通过 EventBus post 后重算）。用脏标记避免重复 fold。
    @Volatile
    private var cachedStates: List<ModuleState>? = null

    /** 标记模块状态有变化，下次 snapshotStatus 重算 */
    fun invalidateSnapshot() {
        cachedStates = null
    }

    /**
     * 汇总状态，供 UI 与 Report 5 上报共用。
     * 优化：只在模块状态变化时重新遍历注册表。
     */
    fun snapshotStatus(linkSpeed: LinkSpeed): AgentStateEvent {
        val states = cachedStates ?: registry.all().map { it.state }.also { cachedStates = it }
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