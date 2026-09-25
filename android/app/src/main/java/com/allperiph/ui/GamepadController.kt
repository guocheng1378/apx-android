package com.allperiph.ui

import com.allperiph.core.ApxNative
import com.allperiph.core.Log
import com.allperiph.wireless.ControlTarget

/**
 * USB 游戏手柄发送中枢（§2.13 Report ID 22）。
 *
 * 报告 = [0x16, buttons_lo, buttons_hi, x, y, rx, ry]（7 字节），
 * 打包由 shared/ 的 `packGamepad` 完成（轴限幅 [-127,127]）。这里只负责把
 * 手柄物理量（16 键位图 + 双摇杆 4 轴）送上 USB HID。
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

    private fun send() {
        val rep = ApxNative.packGamepadOrNull(buttons, axisX, axisY, axisRx, axisRy)
        if (rep.isEmpty()) {
            Log.w(TAG, "手柄报告打包失败（libapx 不可用）")
            return
        }
        // 正在控制 TV/PC：改走网络 GAME 帧（远端注入），不再发本地 USB HID
        val cc = ControlTarget.controlClient
        if (cc != null && cc.ready) {
            cc.gamepad(buttons, axisX, axisY, axisRx, axisRy)
            return
        }
        // 否则走本地 USB HID（本机 / USB 被控）
        val rt = AgentController.runtime
        if (rt == null || !rt.hid.isReady()) return
        if (!rt.hid.sendInputReport(rep)) Log.w(TAG, "手柄报告发送失败")
    }
}
