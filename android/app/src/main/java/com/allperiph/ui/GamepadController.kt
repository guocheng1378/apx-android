package com.allperiph.ui

import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ApxNative
import com.allperiph.shared.util.Log
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
    // 2026-11-12 手柄页重画（用户「手柄ui 重画大小，ps5布局」）：补十字键。
    // 报告仍是一个 uint16 键位图 + 4 轴（packGamepad 格式不动），位 10/11 原留空，
    // issue#169 #17 起用作 L3/R3（摇杆按下）——「点击摇杆中心=LS/RS 键」；
    // 十字键四向占位 12..15 —— 描述符里是普通按钮 13..16，没有 HAT/POV；
    // 走 DirectInput/XInput 按钮路径的游戏都认，只读 HAT 的游戏收不到（协议侧
    // 加 HAT 要动 f_hid 描述符与报告长度，风险大，此处不做）。
    const val BTN_L3 = 10
    const val BTN_R3 = 11
    const val BTN_DUP = 12
    const val BTN_DDOWN = 13
    const val BTN_DLEFT = 14
    const val BTN_DRIGHT = 15

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

    /**
     * 此刻**预计**会走的出口（还没发过输入时给界面显示用）。
     *
     * 与 [send] 里同一条判据（[Uplink.resolve]）：发过之后界面改读 `Uplink.current`
     * —— 那里才是真的发出去了没。两者分开是因为「刚进界面还没碰摇杆」不该被报成无出口。
     */
    fun predictedPath(): String {
        val rt = AgentController.runtime
        val cc = ControlTarget.controlClient
        return Uplink.resolve(rt?.hid, btConnected(), cc != null && cc.ready)
    }

    /** 手柄页状态行：发过输入看真实出口，没发过看预计出口。 */
    fun hint(): String =
        if (Uplink.observed) Uplink.hint() else Uplink.describe(predictedPath())

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
