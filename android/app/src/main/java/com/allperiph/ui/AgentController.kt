package com.allperiph.ui

import android.content.Context
import com.allperiph.audio.AudioModule
import com.allperiph.audio.WirelessAudioModule
import com.allperiph.bt.BtHidDevice
import com.allperiph.core.AgentRuntime
import com.allperiph.core.LinkSpeed
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.gadget.GadgetManager
import com.allperiph.screen.ScreenModule
import com.allperiph.touchpad.TouchpadModule
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

    fun transportLabel(t: String): String = when (t) {
        "wifi" -> "无线"
        "bt" -> "蓝牙"
        "usb" -> "有线（USB）"
        else -> t
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

    /** 某传输类下的全部模块（开关 ON 整组启用、OFF 整组停用） */
    fun groupModules(t: String): List<String> = ORDER.filter { groupOf(it) == t }

    fun isTransportEnabled(context: Context, t: String): Boolean =
        prefs(context).getBoolean("transport.$t", false)

    fun setTransportEnabled(context: Context, t: String, on: Boolean) {
        prefs(context).edit().putBoolean("transport.$t", on).apply()
    }

    fun isEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("enable.$id", defaultEnabled(id))

    private fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("enable.$id", enabled).apply()
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
                        summary = "自检失败：${t.message}",
                    )
                }
            env = e
            linkSpeed = e.linkSpeed
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(e) }
        }
    }

    fun label(id: String): String = when (id) {
        ModuleId.GADGET -> "USB Gadget（复合设备）"
        ModuleId.AUDIO -> "音频（UAC2 麦克风 + 扬声器）"
        ModuleId.TOUCHPAD -> "触控板（相对鼠标）"
        ModuleId.BTHID -> "蓝牙 HID（鼠标 / 多媒体）"
        ModuleId.WIRELESS -> "Wi‑Fi 控制（局域网 TCP · 无蓝牙 PC 用）"
        ModuleId.WIFI_AUDIO -> "Wi‑Fi 音频（音箱 / 麦克风）"
        ModuleId.SCREEN -> "副屏（Wi‑Fi 镜像 PC 桌面）"
        else -> id
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