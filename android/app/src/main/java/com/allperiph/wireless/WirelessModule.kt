package com.allperiph.wireless

import android.os.Build
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * Wi‑Fi 控制模块：手机作为**局域网服务端**，把触控板 / 键盘 / 多媒体输入
 * 经 APX1 控制帧（streamId=3）上行给 PC，由 `apxhost` 用 SendInput 注入系统。
 *
 * 定位：**没有蓝牙适配器的 PC**（本机实测无任何蓝牙设备）无法走蓝牙 HID，
 * USB Gadget 又受 UDC 抢占限制；局域网 TCP 是这台机器唯一稳的输入通道。
 *
 * 承载细节见 [TcpControlChannel]；零配置发现在 [WirelessBeacon]。
 * 本模块只负责生命周期与出口注册，链路不可用时状态**如实报 ERROR**，不伪装成功。
 */
class WirelessModule : Module {

    override val id: String = ModuleId.WIRELESS

    @Volatile
    override var state: ModuleState = ModuleState.IDLE
        private set

    private var channel: TcpControlChannel? = null
    private var beacon: WirelessBeacon? = null

    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING

        // v1 不启用令牌鉴权（局域网工具；对上位机而言等价于「免密」）。
        // 通道与信标必须携带**同一个** token，留空即两侧都不校验。
        val token = ""
        val ch = TcpControlChannel(port = TcpControlChannel.PORT, token = token)
        if (!ch.start()) {
            state = ModuleState.ERROR
            Log.e(TAG, "TCP 控制通道启动失败（端口 ${TcpControlChannel.PORT} 是否被占？）")
            return
        }
        channel = ch

        val bc = WirelessBeacon(
            phoneName = Build.MODEL ?: "Android",
            tcpPort = TcpControlChannel.PORT,
            token = token,
        )
        bc.start()
        beacon = bc

        state = ModuleState.RUNNING
        Log.i(TAG, "Wi‑Fi 控制模块已启动：${ch.statusText()}")
    }

    override fun stop() {
        state = ModuleState.STOPPING
        beacon?.stop()
        beacon = null
        channel?.stop()
        channel = null
        state = ModuleState.STOPPED
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> {
            val ch = channel?.statusText() ?: "运行中"
            if (channel?.ready == true) ch
            else "$ch · 本机 ${TcpControlChannel.localIpv4() ?: "无网络"}"
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
