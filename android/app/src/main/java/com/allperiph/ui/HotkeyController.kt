package com.allperiph.ui

import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ApxNative
import com.allperiph.core.Log
import com.allperiph.core.ModuleId

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
        val rep = ApxNative.packConsumerBitmapOrNull(bitmap)
        if (rep.isEmpty()) {
            Log.w(TAG, "Consumer 报告打包失败（libapx 不可用）")
            return
        }
        // 1) 有线：HID TLC（Report ID 4，sendInputReport 首字节须为 Report ID）
        val rt = AgentController.runtime
        if (rt != null && rt.hid.isReady() && rt.hid.sendInputReport(rep)) return
        // 2) 无线：蓝牙 HID Consumer TLC
        // v1.11：TCP 控制面第三路随无线功能移除
        (AgentController.module(ModuleId.BTHID) as? BtHidDevice)?.reportConsumer(bitmap)
    }
}
