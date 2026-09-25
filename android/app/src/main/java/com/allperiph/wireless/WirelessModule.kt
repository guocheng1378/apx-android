package com.allperiph.wireless

import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState

/**
 * 副屏媒体接入层（手机作为副屏的服务端）。
 *
 * 旧实现里这里还起了 9500 控制服务端（已删除）+ 一条 9500 控制通道发 'a'/'M' 状态帧；
 * 统一控制面切到 9511 后，手机「被控」由
 * [com.allperiph.controlled.ControlledService]（运行 9511 的 [com.allperiph.tv.net.TvControlServer]
 * 并广播 APX1TV 信标）承载，与媒体通道解耦。本模块只保留副屏拉流服务端，
 * 控制上行一律走 9511（见 [ControlTarget] / [com.allperiph.wireless.TvControllerClient]）。
 *
 * 端口约定：
 *   9502  TCP  副屏媒体（[TcpMediaChannel]）
 */
object WirelessModule : Module {
    private var media: TcpMediaChannel? = null

    @Volatile override var state: ModuleState = ModuleState.IDLE
    override val id: String = ModuleId.WIRELESS

    @Volatile private var statusTextValue: String = "未启动"
    override fun statusText(): String = statusTextValue

    override fun start(ctx: ModuleContext) {
        // 副屏媒体通道：手机作为副屏的服务端，PC 经 9511 控制面连入后从这里拉流。
        media = TcpMediaChannel()
        media?.start()
        statusTextValue = "副屏媒体已启动（端口 ${TcpMediaChannel.MEDIA_PORT}；控制走 9511）"
        state = ModuleState.RUNNING
    }

    override fun stop() {
        runCatching { media?.stop() }
        media = null
        statusTextValue = "未启动"
        state = ModuleState.STOPPED
    }

    /** 供 ScreenActivity 取副屏服务端（PC 连入后写入纹理） */
    fun mediaChannel(): TcpMediaChannel? = media
}
