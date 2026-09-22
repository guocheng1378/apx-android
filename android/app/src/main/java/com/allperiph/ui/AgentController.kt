package com.allperiph.ui

import android.content.Context
import com.allperiph.audio.AudioModule
import com.allperiph.bt.BtHidDevice
import com.allperiph.camera.CameraModule
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
 * 【M6】服务编排：进程内唯一的模块注册表与能力开关中枢。
 *
 * 职责：
 * 1. 组装 [AgentRuntime] 并按**依赖顺序**注册模块（gadget → sensor → gps → vibe → screen，
 *    停止时逆序，设备节点先释放，见 `ARCHITECTURE.md` §1）；
 * 2. 持久化每个模块的启用开关（SharedPreferences），支持单模块热启停；
 * 3. 环境自检（root / USB 速度 / 电池白名单）结果缓存，并把链路速度注入副屏模块。
 *
 * 只做**编排**，不含业务逻辑；模块内部实现各自在 `sensor/ gadget/ gps/ vibe/ screen/`。
 */
object AgentController {

    private const val TAG = "AgentController"
    private const val PREFS = "apx_ui"

    /** 注册顺序即启动顺序；停止时逆序。
     *  v1.10：传感器移除（hidparse 除零蓝屏）。
     *  v1.13：重新加入摄像头（UVC 免驱），保留五件套 + 摄像头
     *  （触控板/键盘/麦克风/音箱/蓝牙 HID/UVC 摄像头）。*/
    val ORDER: List<String> = listOf(
        ModuleId.GADGET,
        ModuleId.AUDIO,
        ModuleId.TOUCHPAD,
        ModuleId.BTHID,
        ModuleId.CAMERA,
    )

    @Volatile
    var runtime: AgentRuntime? = null
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var linkSpeed: LinkSpeed = LinkSpeed.UNKNOWN
        private set

    @Volatile
    var env: EnvChecks.Env? = null
        private set

    /** 后台执行器：root/su、sysfs 读取等一律走这里，禁止主线程 IO */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "apx-ui-io").apply { isDaemon = true }
    }

    /** 构建运行时尚未启动的模块集合 */
    fun build(context: Context): AgentRuntime {
        val app = context.applicationContext
        val rt = runtime ?: AgentRuntime(app).also { runtime = it }
        if (rt.registry.all().isEmpty()) {
            rt.register(GadgetManager(app, rt))   // 提供 HID / ACM 出口，必须先注册
            rt.register(AudioModule(app))         // UAC2 声卡搬运（手机麦克风 ↔ PC）
            rt.register(TouchpadModule())          // 触控板：手势 + 相对鼠标上行
            rt.register(BtHidDevice(app))          // 蓝牙 HID：PC 零驱动识别
            rt.register(CameraModule(app))         // UVC 摄像头：Camera2 → V4L2 → PC 免驱
            Log.i(TAG, "模块注册完成：${rt.registry.all().joinToString { it.id }}")
        }
        return rt
    }

    fun module(id: String): Module? = runtime?.registry?.get(id)

    fun allModules(): List<Module> = runtime?.registry?.all() ?: emptyList()

    // ————————————————————————— 启停 —————————————————————————

    /** 按开关状态启动全部启用的模块（顺序启动） */
    fun startEnabled(context: Context) {
        val rt = runtime ?: build(context)
        running = true
        for (id in ORDER) {
            if (!isEnabled(context, id)) continue
            val m = rt.registry.get(id) ?: continue
            if (m.state.isActive) continue
            runCatching { m.start(rt) }
                .onFailure { Log.e(TAG, "启动 $id 失败", it) }
        }
    }

    /** 逆序停止全部模块 */
    fun stopAll() {
        val rt = runtime ?: return
        for (id in ORDER.reversed()) {
            val m = rt.registry.get(id) ?: continue
            runCatching { m.stop() }
                .onFailure { Log.e(TAG, "停止 $id 失败", it) }
        }
        running = false
    }

    /** 单模块热启停 */
    fun setModuleEnabled(context: Context, id: String, enabled: Boolean) {
        setEnabled(context, id, enabled)
        val rt = runtime ?: return
        val m = rt.registry.get(id) ?: return
        if (enabled) {
            if (!m.state.isActive) runCatching { m.start(rt) }
                .onFailure { Log.e(TAG, "启动 $id 失败", it) }
        } else {
            runCatching { m.stop() }
                .onFailure { Log.e(TAG, "停止 $id 失败", it) }
        }
    }

    // ————————————————————————— 开关持久化 —————————————————————————

    fun defaultEnabled(id: String): Boolean = true

    fun isEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("enable.$id", defaultEnabled(id))

    private fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("enable.$id", enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ————————————————————————— 环境自检 —————————————————————————

    /** 异步自检；结果写入 [env] 并回调主线程 */
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

    // ————————————————————————— 展示用 —————————————————————————

    fun label(id: String): String = when (id) {
        ModuleId.GADGET -> "USB Gadget（复合设备）"
        ModuleId.AUDIO -> "音频（UAC2 麦克风 + 扬声器）"
        ModuleId.TOUCHPAD -> "触控板（相对鼠标）"
        ModuleId.BTHID -> "蓝牙 HID（鼠标/键盘/多媒体）"
        ModuleId.CAMERA -> "摄像头（UVC 免驱）"
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
