package com.allperiph.wireless

import android.content.Context
import com.allperiph.controlled.ControlledService
import com.allperiph.core.ApxStreams
import com.allperiph.core.MediaOut
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.shared.media.MediaChannel

/**
 * 副屏媒体接入层（手机作为副屏的服务端）。
 *
 * 旧实现里这里还起了 9500 控制服务端（已删除）+ 一条 9500 控制通道发 'a'/'M' 状态帧；
 * 统一控制面切到 9511 后，手机「被控」由
 * [com.allperiph.controlled.ControlledService]（运行 9511 的 [com.allperiph.shared.net.ControlServer]
 * 并广播 APX1PH 信标）承载，与媒体通道解耦。本模块保留副屏拉流服务端，并在启动时
 * **一并把被控服务起起来**（见下），控制上行一律走 9511（见 [ControlTarget] /
 * [com.allperiph.wireless.TvControllerClient]）。
 *
 * 端口约定：
 *   9502  TCP  副屏媒体（[MediaChannel]）
 */
object WirelessModule : Module {
    private var media: MediaChannel? = null

    /** 供 stop() 停止被控服务用（模块停止时没有 ctx 参数） */
    @Volatile private var appCtx: Context? = null

    @Volatile override var state: ModuleState = ModuleState.IDLE
    override val id: String = ModuleId.WIRELESS

    @Volatile private var statusTextValue: String = "未启动"
    override fun statusText(): String = statusTextValue

    override fun start(ctx: ModuleContext) {
        appCtx = ctx.appContext
        // 副屏媒体通道：手机作为副屏的服务端，PC 经 9511 控制面连入后从这里拉流。
        // 收到的帧交给 ApxStreams 分发（screen / audio 模块各自注册消费者）；
        // 连接建立时挂上行出口（MediaOut → 麦克风/触摸），断开时摘掉。
        media = MediaChannel(
            onFrame = { streamId, flags, seq, body ->
                ApxStreams.dispatch(streamId, flags, seq, body)
            },
            onReady = { ch -> MediaOut.attach(MediaSink(ch)) },
            onDisconnect = { MediaOut.detach() },
            enableUpLink = true,
        )
        media?.start()
        // 只起 9502 是不够的：PC 面板是「控制面先连上手机」才会去连媒体口
        // （pc/host/src/ui/panel_win32.cpp 的 tick：媒体连接跟着控制链路走），
        // 而 PC 只能靠 APX1TV 信标发现手机 —— 信标与被控服务端都在 ControlledService 里。
        // 少了这一步，PC 面板永远停在「正在发现」，副屏 / 音箱 / 麦克风 一个都用不了。
        runCatching { ControlledService.start(ctx.appContext) }
        statusTextValue = "副屏媒体已启动（端口 ${MediaChannel.MEDIA_PORT}；控制走 9511）"
        state = ModuleState.RUNNING
    }

    override fun stop() {
        runCatching { media?.stop() }
        ApxStreams.clear()
        media = null
        // 一起收掉被控（信标 + 9511 服务端），否则关了「无线」手机还在对外广播
        appCtx?.let { c -> runCatching { ControlledService.stop(c) } }
        statusTextValue = "未启动"
        state = ModuleState.STOPPED
    }

    /** 供 ScreenActivity 取副屏服务端（PC 连入后写入纹理） */
    fun mediaChannel(): MediaChannel? = media

    /** 适配 shared MediaChannel 的上行出口到 MediaOut.Sink（手机端麦克风/触摸上行） */
    private class MediaSink(val ch: MediaChannel) : MediaOut.Sink {
        override val ready get() = ch.ready
        override fun send(streamId: Int, body: ByteArray, flags: Int) = ch.send(streamId, body, flags)
    }
}
