package com.allperiph.ui

import android.content.Context
import com.allperiph.R
import com.allperiph.audio.AudioModule
import com.allperiph.audio.WirelessAudioModule
import com.allperiph.bt.BtHidDevice
import com.allperiph.core.AgentRuntime
import com.allperiph.core.LinkSpeed
import com.allperiph.shared.util.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.gadget.GadgetManager
import com.allperiph.screen.ScreenModule
import com.allperiph.touchpad.TouchpadModule
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.WirelessModule
import java.util.concurrent.Executors

/**
 * 服务编排：进程内唯一的模块注册表与能力开关中枢。
 */
object AgentController {

    private const val TAG = "AgentController"
    private const val PREFS = "apx_ui"

    val ORDER: List<String> = listOf(
        ModuleId.GADGET,
        ModuleId.AUDIO,
        ModuleId.TOUCHPAD,
        ModuleId.BTHID,
        // 无蓝牙 PC 的输入承载（局域网 TCP）。放最后启动、最先停止：
        // 它是「输入出口」，不依赖也不阻塞前面的设备类模块。
        ModuleId.WIRELESS,
        // Wi‑Fi 音频同样挂最后：它只依赖「媒体连接已连入」，不需要 USB device 节点。
        // 放在 WIRELESS 之后，保证承载层先就位再挂订阅。
        ModuleId.WIFI_AUDIO,
        // 副屏同样是承载层的消费者：订阅 streamId=0，连入前收到的帧会被丢弃并计数。
        ModuleId.SCREEN,
    )

    @Volatile var runtime: AgentRuntime? = null
        private set
    @Volatile var running: Boolean = false
        private set
    @Volatile var linkSpeed: LinkSpeed = LinkSpeed.UNKNOWN
        private set
    @Volatile var env: EnvChecks.Env? = null
        private set

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "apx-ui-io").apply { isDaemon = true }
    }

    fun build(context: Context): AgentRuntime {
        val app = context.applicationContext
        appContext = app
        val rt = runtime ?: AgentRuntime(app).also { runtime = it }
        if (rt.registry.all().isEmpty()) {
            rt.register(GadgetManager(app, rt))
            rt.register(AudioModule(app))
            rt.register(TouchpadModule())
            rt.register(BtHidDevice(app))
            rt.register(WirelessModule)
            rt.register(WirelessAudioModule())
            rt.register(ScreenModule())
            Log.i(TAG, "模块注册完成：${rt.registry.all().joinToString { it.id }}")
        }
        return rt
    }

    fun module(id: String): Module? = runtime?.registry?.get(id)
    fun allModules(): List<Module> = runtime?.registry?.all() ?: emptyList()

    fun startEnabled(context: Context) {
        val rt = runtime ?: build(context)
        running = true
        // 模块启动含 root/su、configfs 写入、HAL 重绑等长时间阻塞操作，**必须放后台线程**。
        // 同步在主线程跑会卡死 >5s → ANR → 系统杀进程 → 前台服务反复 onCreate/onDestroy，
        // 最终 UI 的「运行中」状态刷新不出来、横屏布局也来不及应用（真机已复现）。
        io.execute {
            for (id in ORDER) {
                if (!isEnabled(context, id)) continue
                val m = rt.registry.get(id) ?: continue
                if (m.state.isActive) continue
                runCatching { m.start(rt) }.onFailure { Log.e(TAG, "启动 $id 失败", it) }
            }
        }
    }

    fun stopAll() {
        val rt = runtime ?: return
        running = false
        // 同 startEnabled：stop 含 configfs 卸载、UDC 归还等阻塞操作，走 io 线程避免主线程 ANR。
        io.execute {
            for (id in ORDER.reversed()) {
                val m = rt.registry.get(id) ?: continue
                runCatching { m.stop() }.onFailure { Log.e(TAG, "停止 $id 失败", it) }
            }
        }
    }

    fun setModuleEnabled(context: Context, id: String, enabled: Boolean) {
        setEnabled(context, id, enabled)
        val rt = runtime ?: return
        val m = rt.registry.get(id) ?: return
        // 单模块热启/热停同样含阻塞操作，走 io 线程避免主线程 ANR。
        if (enabled) {
            if (!m.state.isActive) io.execute {
                runCatching { m.start(rt) }.onFailure { Log.e(TAG, "启动 $id 失败", it) }
            }
        } else {
            io.execute {
                runCatching { m.stop() }.onFailure { Log.e(TAG, "停止 $id 失败", it) }
            }
        }
    }

    fun defaultEnabled(id: String): Boolean = true

    // —— 传输分类（有线 / 无线）：状态页 3 个开关，设置页按类分组 ——
    /** 传输类顺序：无线 → 蓝牙 → 有线(USB) */
    val TRANSPORTS: List<String> = listOf("wifi", "bt", "usb")

    /**
     * 传输类 → 文案**资源 id**（2026-11-04 资源化）：原 `transportLabel()` 直接返回中文，
     * 状态页标题与悬浮球 Toast 都直通它 —— 数据层写死文案，与 snapshot() 同款病灶。
     * 现在存资源 id，调用方 `ctx.getString(transportRes(t))` 解析（两处调用方已同步）。
     */
    fun transportRes(t: String): Int = when (t) {
        "wifi" -> R.string.ui_transport_wifi
        "bt" -> R.string.ui_transport_bt
        "usb" -> R.string.ui_transport_usb
        else -> R.string.ui_transport_wifi
    }

    /** 模块 → 所属传输类（决定它出现在设置页哪个分组、由哪个开关统一启停） */
    fun groupOf(id: String): String = groupsOf(id).first()

    /**
     * 模块 → 出现的分组列表。**触控板在无线和 USB 两组都出现**：
     * 它是多出口模块（USB HID / 蓝牙 / Wi‑Fi 控制通道择优），两种连接下都可用。
     */
    fun groupsOf(id: String): List<String> = when (id) {
        ModuleId.TOUCHPAD -> listOf("wifi", "usb")
        ModuleId.BTHID -> listOf("bt")
        ModuleId.GADGET, ModuleId.AUDIO -> listOf("usb")
        else -> listOf("wifi")
    }

    /**
     * 某传输类下的全部模块（开关 ON 整组启用、OFF 整组停用）。
     *
     * ⚠️ 必须用 `t in groupsOf(it)`（**包含**语义）而不是 `groupOf(it) == t`：
     * 触控板挂在「无线 + USB」两组（groupsOf = ["wifi","usb"]），而 groupOf 只取
     * 第一个（"wifi"）—— 旧写法导致「有线（USB）」开关**永远带不起触控板**，
     * 只开 USB 时模块 IDLE、手势全被丢弃，USB 控制形同虚设（真机踩过）。
     */
    fun groupModules(t: String): List<String> = ORDER.filter { t in groupsOf(it) }

    fun isTransportEnabled(context: Context, t: String): Boolean =
        prefs(context).getBoolean("transport.$t", false)

    fun setTransportEnabled(context: Context, t: String, on: Boolean) {
        prefs(context).edit().putBoolean("transport.$t", on).apply()
        /* 关掉「无线」这一整组时必须一并断开 9511 控制目标（issue#52 第 8 条）。
           [Uplink.resolve] 判「无线可用」的依据是
           `ControlTarget.isControlling() && controlClient != null` —— 只置
           transport.wifi=false、不断开客户端的话，无线出口**依旧成立**：
           触控板与键盘继续走无线、PC 面板继续显示「无线在用」，用户看到的就是
           「切到 USB 了，模式还停在无线 / 两个模式来回跳」。
           断开 -> resolve 自然回落到 USB HID / 蓝牙 HID；ControlTarget.clear()
           内部同时会作废已观测出口（Uplink.reset），两类残留一起清掉。
           收在这里而不是三个切换入口：这是三处唯一的公共写点（见本文件顶部
           传输分类说明），漏掉任一入口都会让其中一条路的无线出口残留。 */
        if (t == "wifi" && !on) ControlTarget.clear()
    }

    fun isEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("enable.$id", defaultEnabled(id))

    private fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("enable.$id", enabled).apply()
    }

    // —— 出口级独立开关（v184）——
    // 触摸板是多出口模块（无线 9511 / USB HID / 蓝牙），此前在「无线」「有线(USB)」
    // 两个分组里的行开关写同一个 enable.touchpad，表现为"开一起开关一起关"。
    // 现在组内行开关写 exit.<id>.<group>：只控制"手势是否走该出口"，模块启停仍由
    // enable.<id>（状态页/传输组开关）决定。运行中即时生效（dispatch 每帧读，SharedPreferences 有内存缓存）。

    @Volatile private var appContext: Context? = null

    fun isExitEnabled(id: String, g: String): Boolean =
        appContext?.let { prefs(it).getBoolean("exit.$id.$g", true) } ?: true

    fun setExitEnabled(context: Context, id: String, g: String, on: Boolean) {
        prefs(context).edit().putBoolean("exit.$id.$g", on).apply()
    }

    /**
     * 传输开关的**统一写点**：拨一个传输开关 = 切偏好 + 热启停该组模块 + 管前台服务。
     *
     * 成宇 2026-11-04「不要总开关，只要无线 / 蓝牙 / 有线开关，对应各自的功能」：
     * 状态页的大号总闸整块去掉之后，这三个开关就是模块启停的**唯一入口** —— 原来那枚总闸
     * 负责的 `startEnabled` / `stopAll` / 前台服务三件事必须挪到开关自己身上，
     * 否则「拨了开关外设还是不动」。
     *
     * 语义与悬浮球「连接模式」菜单完全一致（那里此前有一份私有实现，现已改为调这里）：
     * 多出口模块（触控板在无线 + USB 两组）只切 `exit.<id>.<group>`、不动模块启停；
     * 单出口模块跟着传输开关启停；任一路打开就先拉起前台服务保活，全关了就停掉。
     *
     * ⚠️「关无线时一并断开 9511 控制目标」由 [setTransportEnabled] 内部处理
     * （ControlTarget.clear），这里再作废已观测出口，否则各端显示会滞后一拍。
     *
     * ⚠️ 关掉一个出口时，多出口模块（触控板 wifi + usb）要判「别的出口还开着吗」，
     * 全关才停模块；单出口模块判「别的传输组还需要它吗」。两条判据缺一条就会留下
     * 「开关全关、模块照跑」的残留。
     */
    fun applyTransportSwitch(context: Context, t: String, on: Boolean) {
        setTransportEnabled(context, t, on)
        com.allperiph.core.Uplink.reset()
        for (id in groupModules(t)) {
            if (groupsOf(id).size > 1) {
                setExitEnabled(context, id, t, on)
                if (on) {
                    setModuleEnabled(context, id, true)
                } else {
                    /* ⚠️ 关掉一个出口时必须问一句「这个模块还有别的出口开着吗」：
                       触控板挂在 wifi + usb 两组，只切 exit 位的话，「先关无线、再关有线」
                       之后 enable.touchpad 仍是 true、模块照跑 —— 用户看到的就是
                       「三个开关全关了，外设还在」。这与下面单出口分支的 stillNeeded
                       是同一条判据，只是这里问的是「同模块的其它出口」而非「同模块」。 */
                    val otherExitOpen = groupsOf(id).any { g ->
                        g != t && isTransportEnabled(context, g)
                    }
                    if (!otherExitOpen) setModuleEnabled(context, id, false)
                }
                continue
            }
            if (!on) {
                val stillNeeded = TRANSPORTS.any { other ->
                    other != t &&
                        isTransportEnabled(context, other) &&
                        groupModules(other).contains(id)
                }
                if (stillNeeded) continue
            }
            setModuleEnabled(context, id, on)
        }
        if (on) {
            AgentForegroundService.start(context)
        } else if (!TRANSPORTS.any { isTransportEnabled(context, it) }) {
            stopAll()
            AgentForegroundService.stop(context)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refreshEnv(context: Context, onDone: (EnvChecks.Env) -> Unit = {}) {
        io.execute {
            val e = runCatching { EnvChecks.probe(context.applicationContext) }
                .getOrElse { t ->
                    Log.e(TAG, "环境自检失败", t)
                    EnvChecks.Env(
                        rooted = false,
                        linkSpeed = LinkSpeed.UNKNOWN,
                        udc = null,
                        rawSpeed = "",
                        batteryOptimized = true,
                        notificationGranted = false,
                        // 2026-11-04 资源化：这句「自检失败：…」此前是字面量拼接 ——
                        // 数据层硬编码的第 11 处（与 transportRes / labelRes 同批）。
                        // io 线程里 applicationContext.getString 是安全的（Resources 无主线程约束）。
                        summary = context.applicationContext.getString(
                            R.string.ui_env_probe_failed,
                            t.message,
                        ),
                    )
                }
            env = e
            linkSpeed = e.linkSpeed
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(e) }
        }
    }

    /**
     * 模块名 → **资源 id**（2026-11-04 资源化 + 词汇表清理）。
     *
     * 原 `label(id)` 直接返回 7 句中文（状态页模块行标题直通它），且其中 4 句**撞词汇表禁词**：
     * `USB Gadget`（禁 Gadget）、`UAC2 麦克风`（禁 UAC2）、`蓝牙 HID`（禁 HID）、
     * `Wi‑Fi 镜像 PC 桌面`（副屏不写镜像/投屏/推流）—— 词汇表写明这些词只许出现在
     * 「连接诊断」「运行日志」里。资源化时一并按表改写，实现词从用户可见的模块名里清掉。
     *
     * 调用方仅 StatusPage.snapshot（存 stateRes 同款模式：数据层持有 id，UI 层解析）。
     */
    fun labelRes(id: String): Int = when (id) {
        ModuleId.GADGET -> R.string.ui_module_gadget
        ModuleId.AUDIO -> R.string.ui_module_audio
        ModuleId.TOUCHPAD -> R.string.ui_module_touchpad
        ModuleId.BTHID -> R.string.ui_module_bthid
        ModuleId.WIRELESS -> R.string.ui_module_wireless
        ModuleId.WIFI_AUDIO -> R.string.ui_module_wifi_audio
        ModuleId.SCREEN -> R.string.ui_module_screen
        else -> R.string.ui_module_gadget
    }

    fun detailOf(m: Module): String = runCatching { m.statusText() }.getOrDefault("--")

    fun overallState(): ModuleState {
        val states = allModules().map { it.state }
        return when {
            states.any { it == ModuleState.ERROR } -> ModuleState.ERROR
            states.any { it == ModuleState.DEGRADED } -> ModuleState.DEGRADED
            states.any { it == ModuleState.RUNNING } -> ModuleState.RUNNING
            states.any { it == ModuleState.STARTING } -> ModuleState.STARTING
            else -> ModuleState.IDLE
        }
    }
}