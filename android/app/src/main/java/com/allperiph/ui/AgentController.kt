package com.allperiph.ui

import android.content.Context
import com.allperiph.audio.AudioModule
import com.allperiph.bt.BtHidDevice
import com.allperiph.core.AgentRuntime
import com.allperiph.core.LinkSpeed
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.gadget.GadgetManager
import com.allperiph.touchpad.TouchpadModule
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
        ModuleId.BTHID -> "蓝牙 HID（鼠标/键盘/多媒体）"
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