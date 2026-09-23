package com.allperiph.ui

import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ApxNative
import com.allperiph.core.Log
import com.allperiph.core.ModuleId
import com.allperiph.core.TcpCtrlBridge

/**
 * 快捷键（多媒体键）发送中枢（M6 UI 侧按键采集，架构 §4 Report ID 4）。
 *
 * 位定义与 `shared/include/apx/hid_layout.h` 的 ConsumerKeyBit 严格一致：
 *   bit0=音量+ bit1=音量- bit2=静音 bit3=电源 bit4=播放/暂停 bit5=上一曲 bit6=下一曲
 *
 * 发送语义：**按下与释放均发全量位图**，PC 按位比对得边沿事件（v1.2 usage 位图协议）。
 * 路径择优：有线走 HID TLC（`ctx.hid.sendInputReport`，Report ID 4）；
 * 无线走蓝牙 HID（`BtHidDevice.reportConsumer`，蓝牙 Consumer TLC）。
 */
object HotkeyController {
    private const val TAG = "HotkeyController"

    const val BIT_VOLUME_UP = 0
    const val BIT_VOLUME_DOWN = 1
    const val BIT_MUTE = 2
    const val BIT_POWER = 3
    const val BIT_PLAY_PAUSE = 4
    const val BIT_PREV_TRACK = 5
    const val BIT_NEXT_TRACK = 6

    @Volatile
    private var bitmap = 0

    fun press(bit: Int) {
        bitmap = bitmap or (1 shl bit)
        send()
    }

    fun release(bit: Int) {
        bitmap = bitmap and (1 shl bit).inv()
        send()
    }

    fun current(): Int = bitmap

    private fun send() {
        // 1) 有线：HID TLC（Report ID 4，sendInputReport 首字节须为 Report ID）
        val rt = AgentController.runtime
        if (rt != null && rt.hid.isReady()) {
            val rep = ApxNative.packConsumerBitmapOrNull(bitmap)
            if (rep.isNotEmpty() && rt.hid.sendInputReport(rep)) return
            // 打包失败/发送失败都不中断：继续尝试后面的出口，最后如实降级
        }
        // 2) 无线蓝牙：Consumer TLC（未连接时内部直接返回）
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
        if (bt != null && bt.isConnected) {
            bt.reportConsumer(bitmap)
            return
        }
        // 3) Wi‑Fi 控制面：无蓝牙适配器的 PC（局域网 TCP → PC 端 SendInput）
        if (TcpCtrlBridge.consumer(bitmap)) return
        Log.w(TAG, "多媒体键无可用出口（USB 未挂载 / 蓝牙未连接 / Wi‑Fi 未连入）")
    }
}
