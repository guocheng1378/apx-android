package com.allperiph.ui

import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ApxNative
import com.allperiph.core.Log
import com.allperiph.core.ModuleId
import com.allperiph.core.Uplink
import com.allperiph.wireless.ControlTarget

/**
 * 游戏手柄发送中枢（§2.13 Report ID 22 / BT rid=4）。
 *
 * USB 版报告由 shared/ 的 `packGamepad` 打包；蓝牙版由 BtHidDevice.reportGamepad 直接拼。
 * 这里负责把手柄物理量（16 键位图 + 双摇杆 4 轴）送上 USB HID / 蓝牙 HID / 无线。
 *
 * 发送语义：状态变化即发全量报告，PC 按位比对得边沿事件（与 Consumer 一致）。
 */
object GamepadController {
    private const val TAG = "GamepadController"

    // 按钮位（bit0..15 → HID 按钮 1..16）
    const val BTN_A = 0
    const val BTN_B = 1
    const val BTN_X = 2
    const val BTN_Y = 3
    const val BTN_L1 = 4
    const val BTN_R1 = 5
    const val BTN_L2 = 6
    const val BTN_R2 = 7
    const val BTN_SELECT = 8
    const val BTN_START = 9

    @Volatile private var buttons = 0
    @Volatile private var axisX = 0
    @Volatile private var axisY = 0
    @Volatile private var axisRx = 0
    @Volatile private var axisRy = 0

    /** 按下/抬起某个按钮 */
    fun setButton(bit: Int, down: Boolean) {
        buttons = if (down) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
        send()
    }

    /** 左摇杆（X/Y） */
    fun setLeftAxis(x: Int, y: Int) {
        axisX = x; axisY = y
        send()
    }

    /** 右摇杆（Rx/Ry） */
    fun setRightAxis(rx: Int, ry: Int) {
        axisRx = rx; axisRy = ry
        send()
    }

    /** 全部复位（离开界面 / onPause 时调用，避免按键卡住） */
    fun releaseAll() {
        buttons = 0
        axisX = 0; axisY = 0; axisRx = 0; axisRy = 0
        send()
    }

    /** 获取蓝牙 HID 连接状态 */
    private fun btConnected(): Boolean {
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice ?: return false
        return bt.isConnected
    }

    private fun send() {
        // 蓝牙优先：BT HID 已连接时直接走蓝牙（与 USB / 无线互斥）
        if (btConnected()) {
            val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
            if (bt != null) {
                bt.reportGamepad(buttons, axisX, axisY, axisRx, axisRy)
                return
            }
        }
        // USB / 无线路径
        val rep = ApxNative.packGamepadOrNull(buttons, axisX, axisY, axisRx, axisRy)
        if (rep.isEmpty()) {
            Log.w(TAG, "手柄报告打包失败（libapx 不可用）")
            return
        }
        val cc = ControlTarget.controlClient
        val rt = AgentController.runtime
        val path = Uplink.resolve(rt?.hid, btConnected = btConnected(), wireless = cc != null && cc.ready)
        when (path) {
            Uplink.WIRELESS -> {
                Uplink.set(path, ControlTarget.label)
                val c = cc
                if (c != null) c.gamepad(buttons, axisX, axisY, axisRx, axisRy)
            }
            Uplink.USB -> {
                Uplink.set(path)
                if (rt?.hid?.sendInputReport(rep) != true) Log.w(TAG, "手柄报告发送失败")
            }
            else -> {
                val first = Uplink.current != Uplink.NONE
                Uplink.set(Uplink.NONE, "手柄无可用的上行出口")
                if (first) Log.w(TAG, "手柄无可用出口：未选受控设备且 USB HID 未就绪")
            }
        }
    }
}