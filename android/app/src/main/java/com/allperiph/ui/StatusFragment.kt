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

        /** 左侧传输按钮 → 设置页右栏切到对应分类（"wifi" / "bt" / "usb"）。 */
        fun showCategory(category: String)
    }

    private var host: Host? = null

    /**
     * 状态页视图是否已绑定成功。
     *
     * v196 真机教训：Fragment 的 onViewCreated 与 Activity 的 onCreate 存在时序耦合 ——
     * Activity 在 onCreate 里调 statusFragment.buildLinkRows() 时，Fragment 的视图绑定
     * 可能还没执行（activity 为 null 或布局缺 id），一旦直接访问 lateinit 字段就是
     * `UninitializedPropertyAccessException` 闪退（与 TouchpadFragment 同款）。
     * 现在所有入口都先确认绑定状态，失败就安静跳过，绝不碰未初始化的字段。
     */
    private var viewsBound = false

    /** [ensureBooted] 是否已成功执行（幂等守卫：开关监听/行构建/事件订阅只做一次） */
    private var booted = false

    /** 绑定状态页全部视图；任一缺失即返回 false（布局差异、横竖屏差异都会命中）。 */
    private fun bindViews(): Boolean {
        val act = activity ?: return false
        tvOverall = act.findViewById(R.id.tvOverall) ?: return false
        tvOverallSub = act.findViewById(R.id.tvOverallSub) ?: return false
        // 链路诊断行挂在设置页「通用」分组里（原状态块那张卡已撤），id 见 page_settings.xml
        rowsLink = act.findViewById(R.id.rowsLinkHost) ?: return false
        btnTransportWifi = act.findViewById(R.id.btnTransportWifi) ?: return false
        btnTransportBt = act.findViewById(R.id.btnTransportBt) ?: return false
        btnTransportUsb = act.findViewById(R.id.btnTransportUsb) ?: return false
        tvIdleHint = act.findViewById(R.id.tvIdleHint) ?: return false
        boxRunning = act.findViewById(R.id.boxRunning) ?: return false
        boxIdleFeatures = act.findViewById(R.id.boxIdleFeatures) ?: return false
        swTransportWifi = act.findViewById(R.id.swTransportWifi) ?: return false
        swTransportBt = act.findViewById(R.id.swTransportBt) ?: return false
        swTransportUsb = act.findViewById(R.id.swTransportUsb) ?: return false
        cardWifiModules = act.findViewById(R.id.cardWifiModules) ?: return false
        cardBtModules = act.findViewById(R.id.cardBtModules) ?: return false
        cardUsbModules = act.findViewById(R.id.cardUsbModules) ?: return false
        rowsWifi = act.findViewById(R.id.rowsWifi) ?: return false
        rowsBt = act.findViewById(R.id.rowsBt) ?: return false
        rowsUsb = act.findViewById(R.id.rowsUsb) ?: return false
        return true
    }

    /** 入口守卫：确保视图可用；不可用时安静返回 false（调用方直接 return）。 */
    private fun ensureViews(): Boolean {
        if (viewsBound) return true
        viewsBound = bindViews()
        if (!viewsBound) Log.w(TAG, "宿主布局缺少状态页视图，跳过状态页渲染")
        return viewsBound
    }
    private val handler by lazy { host?.getStatusHandler() ?: Handler(Looper.getMainLooper()) }
    private val disposables = ArrayList<EventBus.Disposable>()

    // --- Status page views (in page_status.xml) ---
    private lateinit var tvOverall: TextView
    private lateinit var tvOverallSub: TextView
    private lateinit var tvIdleHint: TextView
    private lateinit var boxRunning: LinearLayout
    private lateinit var btnTransportWifi: TextView
    private lateinit var btnTransportBt: TextView
    private lateinit var btnTransportUsb: TextView
    private lateinit var boxIdleFeatures: LinearLayout

    // --- 传输分类页（in page_settings.xml，由左侧三个传输按钮导航进入）---
    private lateinit var swTransportWifi: Switch
    private lateinit var swTransportBt: Switch
    private lateinit var swTransportUsb: Switch
    private lateinit var cardWifiModules: LinearLayout
    private lateinit var cardBtModules: LinearLayout
    private lateinit var cardUsbModules: LinearLayout

    /** 刷新时回读开关状态会触发监听 → 用这个旗标挡住，免得每秒把自己又切一次。 */
    private var syncingTransport = false

    // --- Module rows (in page_settings.xml，落在上面三张模块卡里) ---
    private lateinit var rowsWifi: LinearLayout
    private lateinit var rowsBt: LinearLayout
    private lateinit var rowsUsb: LinearLayout

    // --- Link status rows（挂在设置页「通用」分组的 rowsLinkHost 里） ---
    private lateinit var rowsLink: LinearLayout
    private lateinit var linkRows: StatusRows
    private val rowHandles = LinkedHashMap<String, StatusRows.Row>()

    // --- Module rows ---
    // 值改为「该模块所有分组的行」：双组模块（触控板 = wifi+usb）此前只把第一组
    // （wifi 卡）放进刷新表，USB 卡那行是构建时快照、永不更新 —— 用户看到
    // 「触控板已停止」而实际可用（issue #52 第 5 条）。
    private val moduleRows = LinkedHashMap<String, List<ModuleRow>>()

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
        // 防御性调用：本 Fragment 的 onCreateView 返回 null（寄生宿主视图层级），平台
        // android.app.Fragment 在 view == null 时**不会回调 onViewCreated**，正常路径
        // 走 ensureBooted()（宿主挂载点显式调用 + refresh 每秒兜底重试）。
        ensureBooted()
    }

    /**
     * 幂等初始化：绑视图 → 建 AgentController → 建链路/模块行 → **绑传输开关监听** → 订阅事件。
     *
     * ⚠️ 不能只放在 [onViewCreated]：本 Fragment 的 [onCreateView] 返回 null（复用宿主
     * Activity 的视图层级，不独立 inflate），而平台 android.app.Fragment 在 view == null 时
     * **根本不会回调 onViewCreated**（FragmentManagerImpl 只在 `f.mView != null` 时才调）。
     * 真机后果（v199 踩坑）：三个传输开关的监听从未绑定 —— 拨「有线（USB）」开关毫无反应
     * （transport.usb 没写、模块没热启、USB 分组不出现），模块行/链路行也从未构建（分组空白）。
     * 与 SettingsFragment.ensureViews 同款修法：幂等 + 失败可重试，由 [refresh]（每秒
     * ticker）与宿主挂载点显式调用兜底，不再依赖任何生命周期时机。
     */
    fun ensureBooted(): Boolean {
        if (booted) return true
        val h = host ?: return false
        if (!ensureViews()) return false
        h.buildAgentController()
        buildLinkRows()
        buildModuleRows()
        bindActions()
        subscribe()
        booted = true
        Log.i(TAG, "状态页初始化完成：传输开关监听 / 模块行 / 事件订阅已就绪")
        return true
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

    fun buildLinkRows() {
        if (!ensureViews()) return
        rowsLink.removeAllViews()
        linkRows = StatusRows(rowsLink)
        rowHandles["root"] = linkRows.addRow(getString(R.string.label_root))
        rowHandles["speed"] = linkRows.addRow(getString(R.string.label_usb_speed))
        rowHandles["udc"] = linkRows.addRow(getString(R.string.label_usb_gadget))
        rowHandles["service"] = linkRows.addRow(getString(R.string.label_service))
        rowHandles["battery"] = linkRows.addRow(getString(R.string.label_battery))
    }

    // ==================== Module rows ====================

    fun buildModuleRows() {
        if (!ensureViews()) return
        val h = host ?: return
        val act = activity ?: return
        h.buildAgentController()   // 由宿主确保 runtime 与模块注册表已建立
        // AgentController 全是静态 API（v196 核实）—— 此前那段"必须由 Activity 填充"的注释
        // 是误解：Fragment 自己就能取清单。数据通路在此接上，populateModuleRows 一直空转。
        val rt = AgentController.build(act)
        populateModuleRows(
            modules = rt.registry.all().map { it.id to AgentController.label(it.id) },
            detailOf = { id -> rt.module(id)?.let { AgentController.detailOf(it) } ?: "—" },
            groupsOf = { id -> AgentController.groupsOf(id) },
            isExitEnabled = { id, g -> AgentController.isExitEnabled(id, g) },
            isEnabled = { id -> AgentController.isEnabled(act, id) },
        )
    }

    /**
     * 填充模块行：模块清单与开关状态由本方法自行向 AgentController 取（静态 API），
     * 开关动作回调宿主（保持与 Activity 侧同一套生效路径）。
     */
    fun populateModuleRows(
        modules: List<Pair<String, String>>, // (moduleId, label)
        detailOf: (String) -> String,
        groupsOf: (String) -> List<String>,
        isExitEnabled: (String, String) -> Boolean,
        isEnabled: (String) -> Boolean,
    ) {
        val inflater = LayoutInflater.from(activity) ?: return
        moduleRows.clear()
        // 幂等重建：先把三个分组容器清空。populateModuleRows 可能被调用多次
        // （挂载点 ensureBooted 一遍、Activity 的历史转发点再一遍）——此前只清
        // moduleRows map 不清容器，旧行仍挂在 rowsWifi/rowsBt/rowsUsb 上 →
        // 设置页「功能开关」每个模块行出现两个开关，且只有 map 里的那套
        // detail/dot 会刷新，另一套永远静止（拨了"没反应"）。
        // 与 buildLinkRows 的 rowsLink.removeAllViews() 同款策略。
        rowsWifi.removeAllViews()
        rowsBt.removeAllViews()
        rowsUsb.removeAllViews()
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
                row.detail.text = detailOf(moduleId)
                row.sw.contentDescription = label
                if (groups.size > 1) {
                    row.sw.isChecked = isExitEnabled(moduleId, g)
                    row.sw.setOnCheckedChangeListener { _, checked ->
                        Feedback.tap(row.sw)
                        host?.setExitEnabled(moduleId, g, checked)
                        refresh()
                    }
                } else {
                    row.sw.isChecked = isEnabled(moduleId)
                    row.sw.setOnCheckedChangeListener { _, checked ->
                        Feedback.tap(row.sw)
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
            moduleRows[moduleId] = views

            // Wi-Fi 音频子开关（音箱/麦克风）
            if (moduleId == ModuleId.WIFI_AUDIO) {
                addAudioSubRows(inflater)
            }
            // USB 音频（UAC2）方向子开关（修复⑭）：AudioModule 一启动双向全开，
            // 开关层拆开 —— 与 Wi‑Fi 音频同款，落在「有线（USB）」分组下。
            if (moduleId == ModuleId.AUDIO) {
                addUsbAudioSubRows(inflater)
            }
        }
    }

    private fun addAudioSubRows(inflater: LayoutInflater) {
        val ctx = activity ?: return
        makeSubRow(inflater, getString(R.string.ui_main_label_speaker),
            com.allperiph.audio.WirelessAudioModule.isSpeakerOn(ctx), rowsWifi) { on ->
            (AgentController.module(ModuleId.WIFI_AUDIO)
                as? com.allperiph.audio.WirelessAudioModule)?.applySpeaker(ctx, on)
        }
        makeSubRow(inflater, getString(R.string.ui_main_label_mic),
            com.allperiph.audio.WirelessAudioModule.isMicOn(ctx), rowsWifi) { on ->
            (AgentController.module(ModuleId.WIFI_AUDIO)
                as? com.allperiph.audio.WirelessAudioModule)?.applyMic(ctx, on)
        }
    }

    /**
     * USB 音频（UAC2）方向子开关（修复⑭）：模块层一启动就是双向（麦克风→PC +
     * PC→扬声器两条线程），此前没有任何分开开关。镜像 Wi‑Fi 音频的音箱/麦克风
     * 子开关，落在「有线（USB）」分组下；未运行时切换只落盘、下次 start 生效。
     */
    private fun addUsbAudioSubRows(inflater: LayoutInflater) {
        val ctx = activity ?: return
        makeSubRow(inflater, getString(R.string.ui_main_label_speaker),
            com.allperiph.audio.AudioModule.isSpeakerOn(ctx), rowsUsb) { on ->
            (AgentController.module(ModuleId.AUDIO)
                as? com.allperiph.audio.AudioModule)?.applySpeaker(ctx, on)
        }
        makeSubRow(inflater, getString(R.string.ui_main_label_mic),
            com.allperiph.audio.AudioModule.isMicOn(ctx), rowsUsb) { on ->
            (AgentController.module(ModuleId.AUDIO)
                as? com.allperiph.audio.AudioModule)?.applyMic(ctx, on)
        }
    }

    /** 造一行方向子开关（无 detail/状态点，仅名称+Switch）；target 指定所属分组容器 */
    private fun makeSubRow(
        inflater: LayoutInflater,
        label: String,
        initial: Boolean,
        target: android.view.ViewGroup,
        onToggle: (Boolean) -> Unit,
    ): View {
        val sub = inflater.inflate(R.layout.item_module_row, target, false)
        sub.findViewById<TextView>(R.id.tvName).text = label
        sub.findViewById<TextView>(R.id.tvDetail).visibility = View.GONE
        sub.findViewById<ImageView>(R.id.dot).visibility = View.GONE
        val sw = sub.findViewById<Switch>(R.id.sw)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, c ->
            Feedback.tap(sw)
            onToggle(c)
        }
        target.addView(sub)
        return sub
    }

    // ==================== Actions ====================

    fun bindActions() {
        if (!ensureViews()) return
        val h = host ?: return
        renderTransportButtons()
        // 左侧三个传输按钮 = **进入对应分类**（真机反馈原话：「设置页左边三个传输按钮点了
        // 应该弹出对应分类」）。开/关搬到那一页的总开关上做，按钮只负责导航 —— 同时仍用
        // 选中色显示该类的当前状态（renderTransportButtons），点之前就知道开没开。
        for ((btn, kind) in transportButtons()) {
            btn.setOnClickListener {
                Feedback.tap(it)
                h.showCategory(kind)
            }
        }
        // 分类页里的总开关：勾选即开/关该类传输，沿用 onTransportToggle 那条原生效路径
        // （模块热启 / 前台服务 / 双属模块的退出开关都在那里）。新状态仍从
        // h.isTransportEnabled **回读**，不靠本地取反 —— 开 USB 可能失败（没 root / 口被
        // 调试占用），回读才能如实反映。refresh() 每秒回读会触发本监听，故用
        // syncingTransport 挡住（否则会每秒把自己又切一次）。
        for ((sw, kind) in transportSwitches()) {
            sw.setOnCheckedChangeListener { _, checked ->
                Feedback.tap(sw)
                if (syncingTransport) return@setOnCheckedChangeListener
                onTransportToggle(kind, checked)
            }
        }
    }

    private fun transportButtons(): List<Pair<TextView, String>> = listOf(
        btnTransportWifi to "wifi",
        btnTransportBt to "bt",
        btnTransportUsb to "usb",
    )

    /** 传输分类页里的三个总开关（与 transportButtons() 同一套 kind 取值）。 */
    private fun transportSwitches(): List<Pair<Switch, String>> = listOf(
        swTransportWifi to "wifi",
        swTransportBt to "bt",
        swTransportUsb to "usb",
    )

    /** 把总开关的状态回读成真机状态（回读期间挡掉监听，见 [syncingTransport]）。 */
    private fun syncTransportSwitches() {
        val h = host ?: return
        syncingTransport = true
        for ((sw, kind) in transportSwitches()) sw.isChecked = h.isTransportEnabled(kind)
        syncingTransport = false
    }

    /**
     * 传输按钮选中态：开启 = 主色实底 + onPrimary 文字；关闭 = 中性描边胶囊。
     * 三处（bindActions / refresh / 点按后）都走这里，避免各写一份色值。
     */
    private fun renderTransportButtons() {
        val h = host ?: return
        val act = activity ?: return
        for ((btn, kind) in transportButtons()) {
            val on = h.isTransportEnabled(kind)
            btn.setTextColor(act.getColor(if (on) R.color.md_on_primary else R.color.md_primary))
            btn.setBackgroundResource(
                if (on) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost
            )
            btn.isSelected = on
        }
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
        // 防御：ensureBooted 若被重置重跑（宿主变更等），先解绑旧订阅，
        // 避免同一事件触发两次 refresh。
        for (d in disposables) d.dispose()
        disposables.clear()
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
        // 兜底重试点：ensureBooted 失败（宿主未就绪/布局暂缺）时，每秒都有机会补上 ——
        // 传输开关监听、模块行全在这里才真正接上（见 ensureBooted 注释）。
        if (!ensureBooted()) return
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

        // 传输按钮选中态（USB 可能在外部被拔掉/被系统关，每秒跟一次）
        renderTransportButtons()
        // 传输分类页：分类页本身由 SettingsFragment 的导航控制显隐（同其它分类），
        // 这里只回读总开关状态，并在该类未开启时收起模块卡（与旧行为一致：没开的传输不列模块，
        // 免得用户在这里点了模块却在等一条没开的链路）。
        syncTransportSwitches()
        cardWifiModules.visibility = if (h.isTransportEnabled("wifi")) View.VISIBLE else View.GONE
        cardBtModules.visibility = if (h.isTransportEnabled("bt")) View.VISIBLE else View.GONE
        cardUsbModules.visibility = if (h.isTransportEnabled("usb")) View.VISIBLE else View.GONE

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

        // 运行摘要卡已撤（那行信息并进顶部 tvOverallSub）；这里只需拿到 env 给下面的
        // 链路行刷新用。
        val env = h.getLastEnv()

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
        for ((id, rows) in moduleRows) {
            val m: Module? = AgentController.module(id)
            val state = m?.state ?: ModuleState.IDLE
            val text = m?.statusText() ?: getString(R.string.common_off)
            rows.forEach { row ->
                row.detail.text = text
                tintDot(row.dot, state)
            }
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