package com.allperiph.ui

import android.app.Fragment
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.allperiph.R
import com.allperiph.core.AgentStateEvent
import com.allperiph.shared.event.EventBus
import com.allperiph.core.GadgetStateEvent
import com.allperiph.core.LinkSpeed
import com.allperiph.core.LinkSpeedDegradedEvent
import com.allperiph.core.Module
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.Uplink
import com.allperiph.core.UplinkEvent
import com.allperiph.shared.util.Log

/**
 * 状态页 Fragment：大号启动开关、传输开关、运行摘要、链路诊断。
 *
 * 从 [MainActivity] 拆出，通过 [Host] 接口与宿主通信，
 * 访问宿主的共享状态（AgentController、EventBus、View 引用等）。
 */
class StatusFragment : Fragment() {

    /** 宿主 Activity 必须实现此接口 */
    interface Host {
        fun getStatusHandler(): Handler
        fun isTransportEnabled(transport: String): Boolean
        fun setTransportEnabled(transport: String, enabled: Boolean)
        fun getModuleState(moduleId: String): ModuleState
        fun getModuleStatusLabel(moduleId: String): String
        fun isModuleEnabled(moduleId: String): Boolean
        fun setModuleEnabled(moduleId: String, enabled: Boolean)
        fun isExitEnabled(moduleId: String, group: String): Boolean
        fun setExitEnabled(moduleId: String, group: String, enabled: Boolean)
        fun groupModules(transport: String): List<String>
        fun groupsOf(moduleId: String): List<String>
        fun buildAgentController()
        fun startForegroundService()
        fun stopForegroundService()
        fun getEnvSummary(): String
        fun getLastEnv(): EnvChecks.Env?
        fun displayUplinkPath(): String
        fun displayUplinkHint(): String
        fun onStatusBadgeUpdate(stateLabel: String, stateColor: Int, uplinkPath: String)
        fun renderUplink()
        fun isBtConnected(): Boolean
    }

    private var host: Host? = null
    private val handler by lazy { host?.getStatusHandler() ?: Handler(Looper.getMainLooper()) }
    private val disposables = ArrayList<EventBus.Disposable>()

    // --- Status page views (in page_status.xml) ---
    private lateinit var tvOverall: TextView
    private lateinit var tvOverallSub: TextView
    private lateinit var swWifi: Switch
    private lateinit var swBt: Switch
    private lateinit var swUsb: Switch
    private lateinit var rowsLink: LinearLayout
    private lateinit var tvLinkRtt: TextView
    private lateinit var boxRunning: LinearLayout
    private lateinit var tvRunningSummary: TextView
    private lateinit var tvIdleHint: TextView
    private lateinit var boxIdleFeatures: LinearLayout

    // --- Module rows (in page_settings.xml, controlled by transport toggles) ---
    private lateinit var groupWifi: LinearLayout
    private lateinit var groupBt: LinearLayout
    private lateinit var groupUsb: LinearLayout
    private lateinit var rowsWifi: LinearLayout
    private lateinit var rowsBt: LinearLayout
    private lateinit var rowsUsb: LinearLayout

    // --- Link status rows ---
    private lateinit var linkRows: StatusRows
    private val rowHandles = LinkedHashMap<String, StatusRows.Row>()
    private val moduleRows = LinkedHashMap<String, ModuleRow>()

    private class ModuleRow(
        val root: View,
        val name: TextView,
        val detail: TextView,
        val dot: ImageView,
        val sw: Switch,
    )

    @Volatile private var lastEnv: EnvChecks.Env? = null

    /** 定时刷新（1 秒间隔） */
    private val ticker: Runnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    companion object {
        const val TAG = "StatusFragment"
        private const val TICK_MS = 1_000L
    }

    // ==================== Lifecycle ====================

    override fun onAttach(activity: android.app.Activity?) {
        super.onAttach(activity)
        host = activity as? Host
            ?: throw IllegalStateException("${activity?.javaClass?.simpleName} must implement StatusFragment.Host")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // 使用宿主 Activity 已有的视图层级，不独立 inflate 布局
        return null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val h = host ?: return

        // 页面状态视图
        val activity = activity ?: return
 tvOverall = activity.findViewById(R.id.tvOverall)
        tvOverallSub = activity.findViewById(R.id.tvOverallSub)
        swWifi = activity.findViewById(R.id.swWifi)
        swBt = activity.findViewById(R.id.swBt)
        swUsb = activity.findViewById(R.id.swUsb)
        rowsLink = activity.findViewById(R.id.rowsLink)
        tvLinkRtt = activity.findViewById(R.id.tvLinkRtt)
        boxRunning = activity.findViewById(R.id.boxRunning)
        tvRunningSummary = activity.findViewById(R.id.tvRunningSummary)
        tvIdleHint = activity.findViewById(R.id.tvIdleHint)
        boxIdleFeatures = activity.findViewById(R.id.boxIdleFeatures)

        // 模块行视图（在设置页布局中）
        groupWifi = activity.findViewById(R.id.groupWifi)
        groupBt = activity.findViewById(R.id.groupBt)
        groupUsb = activity.findViewById(R.id.groupUsb)
        rowsWifi = activity.findViewById(R.id.rowsWifi)
        rowsBt = activity.findViewById(R.id.rowsBt)
        rowsUsb = activity.findViewById(R.id.rowsUsb)

        h.buildAgentController()
        buildLinkRows()
        buildModuleRows()
        bindActions()
        subscribe()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDetach() {
        for (d in disposables) d.dispose()
        disposables.clear()
        host = null
        super.onDetach()
    }

    // ==================== Link rows ====================

    private fun buildLinkRows() {
        rowsLink.removeAllViews()
        linkRows = StatusRows(rowsLink)
        rowHandles["root"] = linkRows.addRow(getString(R.string.label_root))
        rowHandles["speed"] = linkRows.addRow(getString(R.string.label_usb_speed))
        rowHandles["udc"] = linkRows.addRow(getString(R.string.label_usb_gadget))
        rowHandles["service"] = linkRows.addRow(getString(R.string.label_service))
        rowHandles["battery"] = linkRows.addRow(getString(R.string.label_battery))
    }

    // ==================== Module rows ====================

    private fun buildModuleRows() {
        rowsWifi.removeAllViews()
        rowsBt.removeAllViews()
        rowsUsb.removeAllViews()
        moduleRows.clear()
        val inflater = LayoutInflater.from(activity)
        val rt = host?.buildAgentController()
        // AgentController.registry.all() 通过 host 访问
        // 此处简化：具体实现由 host 的 buildAgentController 完成构建，
        // 模块行由 Activity 端调用 buildModuleRowsForFragment 填充
        // （因为 AgentController.runtime 在 Activity 中持有）
    }

    /**
     * 由 Activity 调用，填充模块行。
     * 避免 Fragment 直接访问 AgentController.runtime（它在 Activity 中初始化）。
     */
    fun populateModuleRows(
        modules: List<Pair<String, String>>, // (moduleId, label)
        groupsOf: (String) -> List<String>,
        isExitEnabled: (String, String) -> Boolean,
        isEnabled: (String) -> Boolean,
    ) {
        val inflater = LayoutInflater.from(activity) ?: return
        moduleRows.clear()
        for ((moduleId, label) in modules) {
            val groups = groupsOf(moduleId)
            if (groups.isEmpty()) continue
            val views = mutableListOf<ModuleRow>()
            for (g in groups) {
                val v = inflater.inflate(R.layout.item_module_row, rowsWifi, false)
                val row = ModuleRow(
                    root = v,
                    name = v.findViewById(R.id.tvName),
                    detail = v.findViewById(R.id.tvDetail),
                    dot = v.findViewById(R.id.dot),
                    sw = v.findViewById(R.id.sw),
                )
                row.name.text = label
                row.sw.contentDescription = label
                if (groups.size > 1) {
                    row.sw.isChecked = isExitEnabled(moduleId, g)
                    row.sw.setOnCheckedChangeListener { _, checked ->
                        host?.setExitEnabled(moduleId, g, checked)
                        refresh()
                    }
                } else {
                    row.sw.isChecked = isEnabled(moduleId)
                    row.sw.setOnCheckedChangeListener { _, checked ->
                        host?.setModuleEnabled(moduleId, checked)
                        views.forEach { it.sw.isChecked = checked }
                        refresh()
                    }
                }
                when (g) {
                    "bt" -> rowsBt
                    "usb" -> rowsUsb
                    else -> rowsWifi
                }.addView(v)
                views += row
            }
            moduleRows[moduleId] = views.first()

            // Wi-Fi 音频子开关（音箱/麦克风）
            if (moduleId == ModuleId.WIFI_AUDIO) {
                addAudioSubRows(inflater)
            }
        }
    }

    private fun addAudioSubRows(inflater: LayoutInflater) {
        val ctx = activity ?: return
        fun subRow(label: String, initial: Boolean, onToggle: (Boolean) -> Unit): View {
            val sub = inflater.inflate(R.layout.item_module_row, rowsWifi, false)
            sub.findViewById<TextView>(R.id.tvName).text = label
            sub.findViewById<TextView>(R.id.tvDetail).visibility = View.GONE
            sub.findViewById<ImageView>(R.id.dot).visibility = View.GONE
            val sw = sub.findViewById<Switch>(R.id.sw)
            sw.isChecked = initial
            sw.setOnCheckedChangeListener { _, c -> onToggle(c) }
            rowsWifi.addView(sub)
            return sub
        }
        subRow(
            getString(R.string.ui_main_label_speaker),
            com.allperiph.audio.WirelessAudioModule.isSpeakerOn(ctx)
        ) { on ->
            (AgentController.module(ModuleId.WIFI_AUDIO)
                as? com.allperiph.audio.WirelessAudioModule)?.applySpeaker(ctx, on)
        }
        subRow(
            getString(R.string.ui_main_label_mic),
            com.allperiph.audio.WirelessAudioModule.isMicOn(ctx)
        ) { on ->
            (AgentController.module(ModuleId.WIFI_AUDIO)
                as? com.allperiph.audio.WirelessAudioModule)?.applyMic(ctx, on)
        }
    }

    // ==================== Actions ====================

    private fun bindActions() {
        val h = host ?: return
        swWifi.isChecked = h.isTransportEnabled("wifi")
        swBt.isChecked = h.isTransportEnabled("bt")
        swUsb.isChecked = h.isTransportEnabled("usb")
        swWifi.setOnCheckedChangeListener { _, c -> onTransportToggle("wifi", c) }
        swBt.setOnCheckedChangeListener { _, c -> onTransportToggle("bt", c) }
        swUsb.setOnCheckedChangeListener { _, c -> onTransportToggle("usb", c) }
    }

    private fun onTransportToggle(t: String, on: Boolean) {
        val h = host ?: return
        h.setTransportEnabled(t, on)
        for (id in h.groupModules(t)) {
            if (h.groupsOf(id).size > 1) {
                h.setExitEnabled(id, t, on)
                if (on) h.setModuleEnabled(id, true)
                continue
            }
            if (!on) {
                val stillNeeded = listOf("wifi", "bt", "usb").any { other ->
                    other != t && h.isTransportEnabled(other) && h.groupModules(other).contains(id)
                }
                if (stillNeeded) continue
            }
            h.setModuleEnabled(id, on)
        }
        if (on) {
            h.startForegroundService()
        } else if (!h.isTransportEnabled("wifi") &&
            !h.isTransportEnabled("bt") &&
            !h.isTransportEnabled("usb")
        ) {
            h.stopForegroundService()
        }
        handler.post { refresh() }
    }

    private fun anyTransportOn(): Boolean {
        val h = host ?: return false
        return h.isTransportEnabled("wifi") || h.isTransportEnabled("bt") || h.isTransportEnabled("usb")
    }

    // ==================== Events ====================

    private fun subscribe() {
        val h = host ?: return
        val actHandler = h.getStatusHandler()
        disposables += EventBus.on<GadgetStateEvent>(actHandler) { ev ->
            if (ev.state == ModuleState.ERROR || ev.detail.startsWith("retry")) {
                // 诊断文本由 Activity 渲染（tvDiag 在设置页）
            }
            refresh()
        }
        disposables += EventBus.on<AgentStateEvent>(actHandler) { refresh() }
        disposables += EventBus.on<LinkSpeedDegradedEvent>(actHandler) {
            // 警告文本由 Activity 渲染
        }
        disposables += EventBus.on<UplinkEvent>(actHandler) {
            h.renderUplink()
            refresh()
        }
    }

    // ==================== Refresh ====================

    /** 每秒刷新状态页所有动态内容 */
    fun refresh() {
        val h = host ?: return
        val anyOn = anyTransportOn()

        // 启动后才展开状态信息
        boxRunning.visibility = if (anyOn) View.VISIBLE else View.GONE
        tvIdleHint.visibility = if (anyOn) View.GONE else View.VISIBLE
        boxIdleFeatures.visibility = if (anyOn) View.GONE else View.VISIBLE

        val rootMissing = h.getLastEnv()?.rooted == false
        if (!anyOn) {
            tvIdleHint.text = getString(
                if (rootMissing) R.string.hint_root_missing else R.string.hint_idle
            )
        }

        // 设置页按传输类分组显示
        groupWifi.visibility = if (h.isTransportEnabled("wifi")) View.VISIBLE else View.GONE
        groupBt.visibility = if (h.isTransportEnabled("bt")) View.VISIBLE else View.GONE
        groupUsb.visibility = if (h.isTransportEnabled("usb")) View.VISIBLE else View.GONE

        // 总状态
        val st = AgentController.overallState()
        tvOverall.text = stateLabel(st)
        tvOverall.setTextColor(resources.getColor(stateColor(st)))

        // 顶部状态徽章（通过 host 更新）
        val path = h.displayUplinkPath()
        h.onStatusBadgeUpdate(
            "${stateLabel(st)} · ${Uplink.label(path)}",
            if (path == Uplink.NONE) R.color.state_error else stateColor(st),
            path,
        )

        tvOverallSub.text = h.getEnvSummary()

        // 运行摘要
        val btRunning = AgentController.module(ModuleId.BTHID)?.state?.isActive == true
        val env = h.getLastEnv()
        val usbSpeed = env?.linkSpeed ?: LinkSpeed.UNKNOWN
        val linkText = when {
            usbSpeed.isSuperSpeed -> getString(R.string.hint_usb_ok)
            usbSpeed != LinkSpeed.UNKNOWN -> getString(R.string.hint_usb2)
            btRunning -> "蓝牙已就绪，在电脑上配对后就能用"
            else -> getString(R.string.common_unknown)
        }
        tvRunningSummary.text = "${h.getEnvSummary()} · $linkText"

        // 链路行
        rowHandles["root"]?.setValue(
            if (env?.rooted == true) getString(R.string.value_root_ok) else getString(R.string.value_root_missing),
            linkRows.color(ok = env?.rooted == true, error = env?.rooted == false)
        )
        val speed = env?.linkSpeed ?: LinkSpeed.UNKNOWN
        rowHandles["speed"]?.setValue(
            if (env?.rawSpeed.isNullOrBlank()) speed.label else "${env?.rawSpeed}（${speed.label}）",
            linkRows.color(ok = speed.isSuperSpeed, warn = !speed.isSuperSpeed)
        )
        rowHandles["udc"]?.setValue(
            env?.udc ?: getString(R.string.common_unknown),
            linkRows.color(ok = !env?.udc.isNullOrBlank())
        )
        val serviceRunning = AgentForegroundService.running || AgentController.running
        rowHandles["service"]?.setValue(
            if (serviceRunning) getString(R.string.common_on) else getString(R.string.common_off),
            linkRows.color(ok = serviceRunning)
        )
        val batteryOk = env?.batteryOptimized == false
        rowHandles["battery"]?.setValue(
            if (batteryOk) getString(R.string.value_battery_ok) else getString(R.string.value_battery_limited),
            linkRows.color(ok = batteryOk, warn = !batteryOk)
        )

        // 模块行
        for ((id, row) in moduleRows) {
            val m: Module? = AgentController.module(id)
            val state = m?.state ?: ModuleState.IDLE
            row.detail.text = m?.statusText() ?: getString(R.string.common_off)
            tintDot(row.dot, state)
        }
    }

    /** 渲染环境摘要（由 Activity 调用） */
    fun onEnvReady(env: EnvChecks.Env) {
        lastEnv = env
        refresh()
    }

    private fun tintDot(dot: ImageView, state: ModuleState) {
        val color = resources.getColor(stateColor(state))
        val d = dot.drawable?.mutate()
        d?.setTint(color)
        dot.setImageDrawable(d)
    }

    private fun stateLabel(state: ModuleState): String = when (state) {
        ModuleState.RUNNING -> getString(R.string.common_on)
        ModuleState.DEGRADED -> getString(R.string.common_warn)
        ModuleState.STARTING -> "启动中"
        ModuleState.ERROR -> getString(R.string.common_error)
        ModuleState.STOPPING -> "停止中"
        ModuleState.STOPPED -> getString(R.string.common_off)
        ModuleState.IDLE -> "未启动"
    }

    private fun stateColor(state: ModuleState): Int = when (state) {
        ModuleState.RUNNING -> R.color.state_ok
        ModuleState.DEGRADED, ModuleState.STARTING, ModuleState.STOPPING -> R.color.state_warn
        ModuleState.ERROR -> R.color.state_error
        else -> R.color.state_idle
    }
}