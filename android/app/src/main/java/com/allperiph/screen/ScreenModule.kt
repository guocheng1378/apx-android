package com.allperiph.screen

import com.allperiph.core.ApxFrame
import com.allperiph.core.ApxStreams
import com.allperiph.core.FragmentJoiner
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import java.util.concurrent.atomic.AtomicLong

/**
 * 副屏模块（**Wi‑Fi 路线**）：接收 PC 推来的画面并交给 [ScreenRenderer] 解码显示。
 *
 * ## 与旧实现（`app/src/disabled/screen/`）的区别
 * 旧版走 **USB bulk**（FunctionFS 下的 `/dev/usb-ffs/apx/ep*` 端点），且依赖一套已被删除的
 * `UsbBulkChannel` 与 `screen/transport/` 下那层抽象。本版改走 Wi‑Fi 媒体通道（TCP 9502），
 * 数据入口统一到 [ApxStreams] 的 `streamId=0`，因此不再需要 FunctionFS。
 *
 * ## 形态：镜像，不是扩展屏
 * PC 侧用 Desktop Duplication 抓**现有桌面**推过来 —— 这**不需要任何驱动**。
 * 若要做"手机当第二块屏"（扩展屏），Windows 侧必须装 IddCx 虚拟显示器驱动
 * （见 `pc/display/idd/README.md`：扩展屏没有免驱路径）。当前实现是前者。
 *
 * ## 分片
 * 一帧画面会被 PC 切成多个 APX1 分片（同 seq，末片带 `last_fragment`），
 * 这里用 [FragmentJoiner] 拼回完整载荷再解析。
 */
class ScreenModule : Module {

    override val id: String = ModuleId.SCREEN

    @Volatile
    override var state: ModuleState = ModuleState.IDLE
        private set

    private val joiner = FragmentJoiner()
    private val frames = AtomicLong(0)
    private val fragments = AtomicLong(0)

    private val consumer = object : ApxStreams.Consumer {
        override fun onFrame(streamId: Int, flags: Int, seq: Int, body: ByteArray) {
            if (streamId != ApxFrame.STREAM_VIDEO) return
            fragments.incrementAndGet()
            val full = joiner.push(flags, seq, body) ?: return
            frames.incrementAndGet()
            // submit 内部只入队（见 ScreenRenderer 的线程纪律说明），不会阻塞收流线程
            ScreenRenderer.submit(full)
        }
    }

    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING
        ApxStreams.register(ApxFrame.STREAM_VIDEO, consumer)
        joiner.reset()
        frames.set(0)
        fragments.set(0)
        state = ModuleState.RUNNING
        Log.i(TAG, "副屏模块已启动（Wi‑Fi 收流 · 打开副屏页前收到的帧会丢弃）")
    }

    override fun stop() {
        state = ModuleState.STOPPING
        ApxStreams.unregister(ApxFrame.STREAM_VIDEO, consumer)
        joiner.reset()
        ScreenRenderer.detachSurface()
        state = ModuleState.STOPPED
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> {
            val f = frames.get()
            when {
                f == 0L -> "副屏等待画面（PC 端需开启推流）"
                ScreenRenderer.attached -> "副屏运行中 · ${ScreenRenderer.stats()}"
                else -> "副屏已收到 $f 帧 · 点「副屏」进入全屏"
            }
        }
        ModuleState.STARTING -> "副屏启动中"
        ModuleState.STOPPING -> "副屏停止中"
        else -> "副屏已停止"
    }

    /** 不占协议功能位（与 Gadget / Wi‑Fi 控制同）：能力由实际视频流体现 */
    override fun maskBits(): Long = 0L

    private companion object {
        const val TAG = "ScreenModule"
    }
}
