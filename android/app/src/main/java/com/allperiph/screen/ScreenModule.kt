package com.allperiph.screen

import android.content.Context
import com.allperiph.core.*

/**
 * M4 副屏模块：PC 视频帧通过 USB bulk 下行 → 解码显示 → 触控事件上行。
 *
 * 传输层：复用 UsbBulkChannel（FunctionFS bulk 端点）。
 *   ep1 OUT = PC→Phone（视频帧，APX1 帧协议 streamId=0）
 *   ep2 IN  = Phone→PC（触控报告，APX1 帧协议 streamId=2）
 *
 * 前置条件：
 *   - ConfigFsLayout 挂载时 GadgetFeature.FFS 启用（或 NCM 降级）
 *   - FfsChannel.writeDescriptors() 已在独立线程完成（阻塞到 UDC 绑定）
 *   - PC 端 Host Service 运行中（IddCx 虚拟显示器 + DDA + 编码推流）
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
        ModuleState.RUNNING -> screenThread?.statusText() ?: "running"
        else -> state.name.lowercase()
    }

    // bit 6 = screen（§2.9 ModuleBit）
    override fun maskBits(): Long = if (state.isActive) (1L shl 6) else 0L

    companion object {
        private const val TAG = "ScreenModule"
    }
}
