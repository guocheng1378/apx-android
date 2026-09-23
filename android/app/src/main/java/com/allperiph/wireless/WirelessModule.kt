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
 * 9502 媒体    副屏画面(下行) / 音箱(下行) / 麦克风(上行) / 摄像头(上行) [TcpMediaChannel]
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

    /** 媒体通道是否已连入（副屏 / 音频 / 摄像头依赖它；UI 可据此提示） */
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
        val rt = ctx
        audioBeacon = thread(start = true, name = "apx-audio-beacon") {
            while (state.isActive) {
                if (TcpCtrlBridge.ready()) {
                    val mod = rt.module(ModuleId.WIFI_AUDIO) as? WirelessAudioModule
                    mod?.let { TcpCtrlBridge.sendControl(it.statusReport()) }
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
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
    }
}
