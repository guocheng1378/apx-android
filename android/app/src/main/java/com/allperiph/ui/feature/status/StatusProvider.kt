package com.allperiph.ui.feature.status

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.allperiph.audio.AudioModule
import com.allperiph.audio.WirelessAudioModule
import com.allperiph.core.AgentStateEvent
import com.allperiph.core.GadgetStateEvent
import com.allperiph.core.LinkSpeed
import com.allperiph.core.LinkSpeedDegradedEvent
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.Uplink
import com.allperiph.core.UplinkEvent
import com.allperiph.shared.event.EventBus
import com.allperiph.ui.AgentController
import com.allperiph.ui.AgentForegroundService
import com.allperiph.ui.EnvHolder
import com.allperiph.wireless.ControlTarget

/**
 * 状态页的**数据层** —— 把旧 [com.allperiph.ui.StatusFragment] 的取数与开关动作搬过来，
 * 但不再依赖 Activity：数据全部来自单例（AgentController / EnvHolder / Uplink / EventBus），
 * 因此这一页可以在任何宿主里渲染（新外壳 ApShellActivity 后都不用改）。
 *
 * ## 刷新的三条路（与旧实现同款）
 *  1. 每秒轮询（USB 可能被外部拔掉、电池白名单可能被系统改）；
 *  2. 事件（GadgetState / AgentState / 链路降级 / 出口变化）；
 *  3. 用户刚改过开关 —— 立刻重算，不等下一秒。
 *
 * ## 开关语义照搬旧实现，别自己发明
 *  · 传输总开关（无线 / 蓝牙 / 有线）关掉时：属于该类的模块要停 —— 但**别的类还在用**的
 *    模块不能停（旧实现的 stillNeeded 判定），否则关蓝牙会把连着 USB 的音频一起停掉；
 *  · 一个模块属于多个传输时（双属模块）：开关是「退出这一类」，不是「停掉模块」。
 */
class StatusProvider(context: Context) {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _snapshot = mutableStateOf(StatusSnapshot())

    /** 界面读这个；每次重算产生新实例，Compose 自动重组。 */
    val snapshot: State<StatusSnapshot> = _snapshot

    private val disposables = ArrayList<EventBus.Disposable>()
    private var started = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** 开始取数（幂等）。宿主在 Composable 的 DisposableEffect 里调。 */
    fun start() {
        if (started) return
        started = true
        // 旧实现由宿主先 buildAgentController()，这里自取：保证模块注册表已建立
        AgentController.build(appContext)
        refresh()
        handler.post(ticker)
        subscribe()
    }

    /** 停止取数并退订（幂等）。 */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(ticker)
        for (d in disposables) d.dispose()
        disposables.clear()
    }

    /** 立刻重算一帧。 */
    fun refresh() {
        _snapshot.value = read()
    }

    // ==================== 取数 ====================

    private fun read(): StatusSnapshot {
        val env = EnvHolder.env
        return StatusSnapshot(
            overall = AgentController.overallState(),
            envSummary = env?.summary.orEmpty(),
            rooted = env?.rooted,
            linkSpeed = env?.linkSpeed ?: LinkSpeed.UNKNOWN,
            rawSpeed = env?.rawSpeed.orEmpty(),
            udc = env?.udc,
            serviceRunning = AgentForegroundService.running || AgentController.running,
            batteryOptimized = env?.batteryOptimized,
            uplinkPath = displayUplinkPath(),
            transports = TransportKind.entries.map {
                TransportState(it, AgentController.isTransportEnabled(appContext, it.key))
            },
            modules = readModules(),
        )
    }

    private fun readModules(): List<ModuleRow> =
        AgentController.allModules().map { m ->
            val groups = AgentController.groupsOf(m.id)
            ModuleRow(
                id = m.id,
                label = AgentController.label(m.id),
                detail = AgentController.detailOf(m),
                state = m.state,
                groups = groups,
                enabled = AgentController.isEnabled(appContext, m.id),
                exitEnabled = groups.associateWith { AgentController.isExitEnabled(m.id, it) },
            )
        }

    /**
     * 当前**显示用**的出口。
     *
     * 与旧 MainActivity.displayUplinkPath() 同一条判据：没真的发过输入时用**预计**出口
     * （Uplink.resolve），否则一进 App 就报「无出口」，是误导。
     */
    private fun displayUplinkPath(): String {
        if (Uplink.observed) return Uplink.current
        val bt = (AgentController.module(ModuleId.BTHID) as? com.allperiph.bt.BtHidDevice)?.isConnected == true
        return Uplink.resolve(
            AgentController.runtime?.hid,
            bt,
            ControlTarget.isControlling() && ControlTarget.controlClient != null,
        )
    }

    private fun subscribe() {
        // 防御：重复 start 时先解绑旧的，避免同一事件触发两次
        for (d in disposables) d.dispose()
        disposables.clear()
        disposables += EventBus.on<GadgetStateEvent>(handler) { refresh() }
        disposables += EventBus.on<AgentStateEvent>(handler) { refresh() }
        disposables += EventBus.on<LinkSpeedDegradedEvent>(handler) { refresh() }
        disposables += EventBus.on<UplinkEvent>(handler) { refresh() }
    }

    // ==================== 开关动作 ====================

    /**
     * 传输总开关（无线 / 蓝牙 / 有线）。
     *
     * 生效路径与旧 StatusFragment.onTransportToggle 完全一致：改总开关 → 连带处理该类的模块
     * → 需要时起停前台服务。新状态**回读**（不靠本地取反）：开 USB 可能失败（没 root / 口被
     * 调试占用），回读才能如实反映。
     */
    fun setTransport(kind: TransportKind, on: Boolean) {
        AgentController.setTransportEnabled(appContext, kind.key, on)
        for (id in AgentController.groupModules(kind.key)) {
            val groups = AgentController.groupsOf(id)
            if (groups.size > 1) {
                // 双属模块：开关是「退出这一类」
                AgentController.setExitEnabled(appContext, id, kind.key, on)
                if (on) AgentController.setModuleEnabled(appContext, id, true)
                continue
            }
            if (!on) {
                // 别的类还在用这个模块就别停
                val stillNeeded = TransportKind.entries.any { other ->
                    other != kind &&
                        AgentController.isTransportEnabled(appContext, other.key) &&
                        AgentController.groupModules(other.key).contains(id)
                }
                if (stillNeeded) continue
            }
            AgentController.setModuleEnabled(appContext, id, on)
        }
        if (on) {
            AgentForegroundService.start(appContext)
        } else if (TransportKind.entries.none { AgentController.isTransportEnabled(appContext, it.key) }) {
            AgentController.stopAll()
        }
        refresh()
    }

    /** 单属模块的启停。 */
    fun setModuleEnabled(id: String, on: Boolean) {
        AgentController.setModuleEnabled(appContext, id, on)
        refresh()
    }

    /** 双属模块：退出某一类。 */
    fun setModuleExitEnabled(id: String, group: String, on: Boolean) {
        AgentController.setExitEnabled(appContext, id, group, on)
        refresh()
    }

    /** 音频方向子开关：wireless = Wi-Fi 音频，否则 USB 音频（UAC2）。 */
    fun setSpeaker(on: Boolean, wireless: Boolean) {
        if (wireless) {
            (AgentController.module(ModuleId.WIFI_AUDIO) as? WirelessAudioModule)?.applySpeaker(appContext, on)
        } else {
            (AgentController.module(ModuleId.AUDIO) as? AudioModule)?.applySpeaker(appContext, on)
        }
        refresh()
    }

    fun setMic(on: Boolean, wireless: Boolean) {
        if (wireless) {
            (AgentController.module(ModuleId.WIFI_AUDIO) as? WirelessAudioModule)?.applyMic(appContext, on)
        } else {
            (AgentController.module(ModuleId.AUDIO) as? AudioModule)?.applyMic(appContext, on)
        }
        refresh()
    }

    /** 音频子开关的当前值（读盘上的值，与旧实现一致）。 */
    fun speakerOn(wireless: Boolean): Boolean =
        if (wireless) {
            WirelessAudioModule.isSpeakerOn(appContext)
        } else {
            AudioModule.isSpeakerOn(appContext)
        }

    fun micOn(wireless: Boolean): Boolean =
        if (wireless) {
            WirelessAudioModule.isMicOn(appContext)
        } else {
            AudioModule.isMicOn(appContext)
        }

    companion object {
        private const val TICK_MS = 1_000L
    }
}

/** 传输分类（key 与旧实现的 "wifi" / "bt" / "usb" 一致，别改） */
enum class TransportKind(val key: String) {
    WIFI("wifi"),
    BT("bt"),
    USB("usb"),
}

/** 一帧状态数据 */
data class StatusSnapshot(
    val overall: ModuleState = ModuleState.IDLE,
    val envSummary: String = "",
    val rooted: Boolean? = null,
    val linkSpeed: LinkSpeed = LinkSpeed.UNKNOWN,
    val rawSpeed: String = "",
    val udc: String? = null,
    val serviceRunning: Boolean = false,
    val batteryOptimized: Boolean? = null,
    val uplinkPath: String = Uplink.NONE,
    val transports: List<TransportState> = emptyList(),
    val modules: List<ModuleRow> = emptyList(),
) {
    /** 三个传输总开关有没有打开的 */
    val anyTransportOn: Boolean get() = transports.any { it.enabled }
}

data class TransportState(val kind: TransportKind, val enabled: Boolean)

/** 模块行 */
data class ModuleRow(
    val id: String,
    val label: String,
    val detail: String,
    val state: ModuleState,
    /** 属于哪些传输（多属 = 双属模块） */
    val groups: List<String>,
    /** 单属模块：是否启用；多属模块：是否至少启用 */
    val enabled: Boolean,
    /** 多属模块：按类记录「是否已退出该类」（键是传输 key） */
    val exitEnabled: Map<String, Boolean>,
)
