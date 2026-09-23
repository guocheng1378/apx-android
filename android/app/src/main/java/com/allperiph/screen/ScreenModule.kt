package com.allperiph.screen

import android.content.Context
import com.allperiph.core.AgentRuntime
import com.allperiph.core.Log
import com.allperiph.core.Module
import com.allperiph.core.ModuleContext
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleMask
import com.allperiph.core.ModuleState

/**
 * M4 副屏模块：PC 视频帧通过 USB bulk 下行 → 解码显示。
 *
 * v1.34（从 v21 移植，去掉触控回送）：仅 PC → 手机单向显示。
 *
 * 传输层：复用 [com.allperiph.core.UsbBulkChannel]（FunctionFS bulk 端点）。
 *   ep1 OUT = PC→Phone（视频帧，APX1 帧协议 streamId=0）
 *   ep2 IN  = 不再使用（v21 的触控上行已剥离）
 *
 * 前置条件：
 *   - [com.allperiph.gadget.GadgetManager] 挂载时 GadgetFeature.FFS 启用（或 NCM 降级）
 *   - [com.allperiph.core.FfsChannel.writeDescriptors] 已在独立线程完成（阻塞到 UDC 绑定）
 *   - PC 端 Host Service 运行中（IddCx 虚拟显示器 + DDA + 编码推流）
 *   - 用户授予 SYSTEM_ALERT_WINDOW（overlay 窗口显示副屏画面）
 */
class ScreenModule(private val app: Context) : Module {

    override val id = ModuleId.SCREEN
    override var state: ModuleState = ModuleState.IDLE
        private set

    private var screenThread: ScreenThread? = null
    private var runtime: AgentRuntime? = null

    override fun start(ctx: ModuleContext) {
        if (state.isActive) return
        state = ModuleState.STARTING
        runtime = ctx as? AgentRuntime

        try {
            screenThread = ScreenThread(app, ctx)
            screenThread!!.start()
            state = ModuleState.RUNNING
            Log.i(TAG, "副屏模块已启动")
        } catch (t: Throwable) {
            fail(t.message ?: "unknown")
        }
    }

    override fun stop() {
        state = ModuleState.STOPPING
        screenThread?.quit()
        screenThread = null
        state = ModuleState.STOPPED
    }

    private fun fail(reason: String) {
        state = ModuleState.ERROR
        Log.e(TAG, "error: $reason")
        runCatching { stop() }
    }

    override fun statusText(): String = when (state) {
        ModuleState.RUNNING -> screenThread?.statusText() ?: "副屏运行中"
        else -> when (state) {
            ModuleState.STARTING -> "副屏启动中"
            ModuleState.STOPPED, ModuleState.IDLE -> "副屏已停止"
            ModuleState.ERROR -> "副屏错误"
            else -> state.name
        }
    }

    /** §2.9 bit37 副屏视频（bulk streamId 0） */
    override fun maskBits(): Long = if (state.isActive) ModuleMask.SCREEN_VIDEO else 0L

    companion object {
        private const val TAG = "ScreenModule"
    }
}