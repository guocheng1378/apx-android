package com.allperiph.wireless

import android.os.Build
import com.allperiph.audio.WirelessAudioModule
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.TcpCtrlBridge
import kotlin.concurrent.thread

/**
 * Wi‑Fi 模块：手机作为**局域网服务端**，一条连接跑输入、一条连接跑媒体。
 *
 * ```
 * 9500 控制面  触控板 / 键盘 / 多媒体 → PC 端 SendInput 注入      [TcpControlChannel]
 * 9502 媒体    副屏画面(下行) / 音箱(下行) / 麦克风(上行) [TcpMediaChannel]
 * 9501 信标    零配置发现                                       [WirelessBeacon]
 * ```
 *
 * 定位：**没有蓝牙适配器的 PC**（本机实测无任何蓝牙设备）无法走蓝牙 HID，
 * USB Gadget 又受 UDC 抢占限制；局域网 TCP 是这台机器唯一稳的通道。
 *
 * 失效策略：**控制面失败 = 模块 ERROR**（输入是核心能力）；**媒体失败不阻塞控制面**，
 * 只在状态文本里如实标注 —— 副屏/音频是增强项，不该把键盘鼠标一起拖死。
 */
class WirelessModule : Module {

    override val id: String = ModuleId.WIRELESS

    @Volatile
    override var state: ModuleState = ModuleState.IDLE
        private set

    private var channel: TcpControlChannel? = null
    private var media: TcpMediaChannel? = null
    private var beacon: WirelessBeacon? = null

    /** 音频状态信标线程：周期把手机侧 Wi‑Fi 音频模块状态发回 PC */
    private var audioBeacon: Thread? = null

    /** 媒体通道是否已连入（副屏 / 音频依赖它；UI 可据此提示） */
    @Volatile
    var mediaReady: Boolean = false
        private set

    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING

        // v1 不启用令牌鉴权（局域网工具；对上位机而言等价于「免密」）。
        // 三条通道必须携带**同一个** token，留空即两侧都不校验。
        val token = ""

        val ch = TcpControlChannel(port = TcpControlChannel.PORT, token = token)
        if (!ch.start()) {
            state = ModuleState.ERROR
            Log.e(TAG, "TCP 控制通道启动失败（端口 ${TcpControlChannel.PORT} 是否被占？）")
            return
        }
        channel = ch

        // PC → 手机 模块开关命令（0x10）：面板等开关直接控制手机端模块
        ch.moduleCommandListener = { idx, on ->
            val id = moduleIdxToId(idx)
            if (id != null) {
                Log.i(TAG, "PC 命令：模块 $id → ${if (on) "开" else "关"}")
                com.allperiph.ui.AgentController.setModuleEnabled(ctx.appContext, id, on)
            }
        }

        val md = TcpMediaChannel(port = TcpMediaChannel.MEDIA_PORT, token = token)
        if (md.start()) {
            media = md
            Log.i(TAG, "Wi‑Fi 媒体通道已启动（端口 ${TcpMediaChannel.MEDIA_PORT}）")
        } else {
            // 端口被占或权限问题：控制面照常工作，媒体能力如实标缺
            Log.w(TAG, "媒体通道启动失败（端口 ${TcpMediaChannel.MEDIA_PORT} 被占？），副屏/音频不可用")
        }

        val bc = WirelessBeacon(
            phoneName = Build.MODEL ?: "Android",
            tcpPort = TcpControlChannel.PORT,
            token = token,
        )
        bc.start()
        beacon = bc

        state = ModuleState.RUNNING
        Log.i(TAG, "Wi‑Fi 模块已启动：${ch.statusText()}")

        // 音频状态信标：周期把 Wi‑Fi 音频模块状态发回 PC，让 PC 面板能「看到手机播放端」
        // 同时上报**全模块**状态帧（tag 'M'）：PC 面板据此显示手机端各功能的真实开关状态
        val rt = ctx
        audioBeacon = thread(start = true, name = "apx-audio-beacon") {
            while (state.isActive) {
                if (TcpCtrlBridge.ready()) {
                    val mod = rt.module(ModuleId.WIFI_AUDIO) as? WirelessAudioModule
                    mod?.let { TcpCtrlBridge.sendControl(it.statusReport()) }
                    TcpCtrlBridge.sendControl(moduleStatesReport(rt))
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /**
     * 全模块状态帧（tag 'M'）：`[0]='M' [1]=count [2..]{模块索引, 状态码}×count`。
     * 模块索引两端硬编码一致（与 AgentController.ORDER 对齐）：
     * 0=GADGET 1=AUDIO 2=TOUCHPAD 3=BTHID 4=WIRELESS 5=WIFI_AUDIO 6=SCREEN
     * 状态码 = ModuleState.ordinal（0=IDLE 1=STARTING 2=RUNNING 3=DEGRADED 4=ERROR 5=STOPPING 6=STOPPED）
     */
    private fun moduleStatesReport(rt: ModuleContext): ByteArray {
        val ids = arrayOf(
            ModuleId.GADGET, ModuleId.AUDIO, ModuleId.TOUCHPAD, ModuleId.BTHID,
            ModuleId.WIRELESS, ModuleId.WIFI_AUDIO, ModuleId.SCREEN,
        )
        val body = ByteArray(2 + ids.size * 2)
        body[0] = 'M'.code.toByte()
        body[1] = ids.size.toByte()
        ids.forEachIndexed { i, id ->
            body[2 + i * 2] = i.toByte()
            body[3 + i * 2] = (rt.module(id)?.state?.ordinal ?: 0).toByte()
        }
        return body
    }

    override fun stop() {
        state = ModuleState.STOPPING
        audioBeacon?.interrupt()
        audioBeacon = null
        beacon?.stop()
        beacon = null
        media?.stop()
        media = null
        mediaReady = false
        channel?.stop()
        channel = null
        state = ModuleState.STOPPED
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> {
            val ctrl = channel?.statusText() ?: "运行中"
            val mediaText = when {
                media == null -> "媒体不可用（端口被占）"
                media?.ready == true -> "媒体 ${media?.statusText()}"
                else -> "媒体待连"
            }
            // 控制面未连入时补上本机 IP —— 手动填地址时用户需要它
            if (channel?.ready == true) "$ctrl · $mediaText"
            else "$ctrl · $mediaText · 本机 ${TcpControlChannel.localIpv4() ?: "无网络"}"
        }
        ModuleState.STARTING -> "启动中"
        ModuleState.ERROR -> "监听 ${TcpControlChannel.PORT} 失败"
        ModuleState.STOPPING -> "停止中"
        else -> "已停止"
    }

    /** 链路本身不占协议功能位（与 Gadget 同），功能位由实际输入能力体现 */
    override fun maskBits(): Long = 0L

    companion object {
        private const val TAG = "WirelessModule"

        /** PC 命令里的模块索引 → ModuleId（两端硬编码一致，与状态帧的索引同表） */
        fun moduleIdxToId(idx: Int): String? = when (idx) {
            0 -> ModuleId.GADGET
            1 -> ModuleId.AUDIO
            2 -> ModuleId.TOUCHPAD
            3 -> ModuleId.BTHID
            4 -> ModuleId.WIRELESS
            5 -> ModuleId.WIFI_AUDIO
            6 -> ModuleId.SCREEN
            else -> null
        }
    }
}
