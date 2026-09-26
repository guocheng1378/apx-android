package com.allperiph.ui

import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ApxNative
import com.allperiph.core.Log
import com.allperiph.core.ModuleId
import com.allperiph.core.Uplink

/**
 * 快捷键（多媒体键）发送中枢（M6 UI 侧按键采集，架构 §4 Report ID 4）。
 *
 * 位定义与 `shared/include/apx/hid_layout.h` 的 ConsumerKeyBit 严格一致：
 *   bit0=音量+ bit1=音量- bit2=静音 bit3=电源 bit4=播放/暂停 bit5=上一曲 bit6=下一曲
 *
 * 发送语义：**按下与释放均发全量位图**，PC 按位比对得边沿事件（v1.2 usage 位图协议）。
 * 路径择优**只有一处**：`Uplink.resolve`（无线目标 → USB HID Report 4 → 蓝牙 Consumer TLC → 无出口）。
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
        // 择路判据统一在 [Uplink.resolve]（无线目标 → USB → 蓝牙 → 无出口），并顺手把实际
        // 出口记进 [Uplink] 供界面/通知显示 —— 原先这里和通知栏各写一套顺序，互相矛盾。
        val rt = AgentController.runtime
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
        val target = com.allperiph.wireless.ControlTarget
        val btOn = bt != null && bt.isConnected
        when (Uplink.resolve(rt?.hid, btOn, target.isControlling() && target.controlClient != null)) {
            Uplink.WIRELESS -> {
                // 选了受控设备（TV / PC）：bitmap 语义与受控端一致，直接短路过去
                Uplink.set(Uplink.WIRELESS, target.label)
                target.controlClient?.consumer(bitmap)
            }
            Uplink.USB -> {
                // Report ID 4，sendInputReport 首字节须为 Report ID
                val rep = ApxNative.packConsumerBitmapOrNull(bitmap)
                if (rep.isNotEmpty() && rt?.hid?.sendInputReport(rep) == true) {
                    Uplink.set(Uplink.USB)
                } else if (btOn) {
                    // 打包失败/发送失败都不中断：继续尝试蓝牙，最后如实降级
                    Uplink.set(Uplink.BLUETOOTH, "USB 出口写入失败，已降级蓝牙")
                    bt?.reportConsumer(bitmap)
                } else {
                    Uplink.set(Uplink.NONE, "USB HID 写入失败且蓝牙未连接")
                    Log.w(TAG, "多媒体键无可用出口（USB 未挂载 / 蓝牙未连接）")
                }
            }
            Uplink.BLUETOOTH -> {
                Uplink.set(Uplink.BLUETOOTH)
                bt?.reportConsumer(bitmap)
            }
            else -> {
                val first = Uplink.current != Uplink.NONE
                Uplink.set(Uplink.NONE, "USB HID 未就绪（/dev/hidg0 未挂载）、蓝牙未连接")
                if (first) Log.w(TAG, "多媒体键无可用出口（USB 未挂载 / 蓝牙未连接）")
            }
        }
    }
}
