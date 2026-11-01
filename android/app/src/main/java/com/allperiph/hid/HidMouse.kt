package com.allperiph.hid

import com.allperiph.bt.BtHidDevice
import com.allperiph.core.ModuleId
import com.allperiph.core.Uplink
import com.allperiph.shared.util.Log
import com.allperiph.ui.AgentController
import com.allperiph.wireless.ControlTarget

/**
 * 轻量鼠标 / 多媒体键上行：**判据只有 [Uplink.resolve] 一处**。
 *
 * 为什么要有它（issue#52 反复出现的「有线 / 蓝牙模式下某个页面点了没反应」）：
 * 遥控器页（[com.allperiph.ui.RemoteActivity]）的虚拟触摸板过去**直接**读
 * `ControlTarget.controlClient?.mouse(...)` —— 只有无线受控目标（TV / PC，9511）那条路。
 * 而同一页的方向键 / 确认 / 返回走的是 [HidKeys] → [Uplink.resolve]，USB / 蓝牙都通。
 * 于是插线用遥控器时：按键有反应、光标一动不动，用户只能得出「触摸板坏了」。
 *
 * 这里把三条真实链路收拢成一处（与 [HidKeys] 同口径）：
 *  · 无线 9511 → [TvControllerClient.mouse]（对端 SendInput / evdev 注入）
 *  · USB HID  → rid 0x02 `[rid, buttons, dx, dy, wheel, pan]`（主机无需装软件）
 *  · 蓝牙 HID → [BtHidDevice.reportMouse]（rid 0x01，载荷同 USB 去掉 rid 字节）
 *  · 都没有   → 记 [Uplink.NONE] 并**返回 false**，让界面如实提示，不要静默丢帧
 *
 * 与触控板页（[com.allperiph.touchpad.TouchpadModule.dispatch]）的分工：那边是高频全量
 * 手势通路，自带 USB 背压合并（失败帧并入下一帧、16ms 后补发、1s 放弃）。本对象只服务
 * 「遥控器页触摸条」这类低频、单发、无需合并的场景，写失败就交回界面按失败处理 ——
 * **不复制那套背压状态机**，避免两处各养一份积压状态互相打架。
 */
object HidMouse {

    private const val TAG = "HidMouse"

    /** HID Mouse TLC Report ID（shared/src/hid_descriptor.cpp buildTlcMouse） */
    private const val RID_MOUSE: Byte = 0x02

    /** HID Consumer TLC Report ID（多媒体键） */
    private const val RID_CONSUMER: Byte = 0x04

    /**
     * 当前**预计**出口（尚未发送时也可用来判断「按键 / 光标有没有地方去」）。
     *
     * 注意它只是判据结果，不代表真的写出去了；写失败会在 [move] / [consumer] 里被记成
     * [Uplink.NONE] 并返回 false。
     */
    fun exit(): String {
        val rt = AgentController.runtime
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
        return Uplink.resolve(
            rt?.hid,
            btConnected = bt?.isConnected == true,
            wireless = ControlTarget.isControlling() && ControlTarget.controlClient != null,
        )
    }

    /** 有没有出口（无线 / USB / 蓝牙任一）。界面用它决定「按下就提示」还是静默发送。 */
    fun available(): Boolean = exit() != Uplink.NONE

    /**
     * 发一帧鼠标相对位移 / 按键。
     *
     * @param buttons 按键位图（bit0 左键、bit1 右键、bit2 中键），0 = 只移动
     * @param dx/dy 相对位移（-127..127）
     * @param wheel 垂直滚轮（-127..127）
     * @param pan 横向滚动（-127..127）
     * @return 是否**真的**写出去了；false 时界面应当提示（[Uplink.reason] 里有原因）
     */
    fun move(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): Boolean {
        val rt = AgentController.runtime
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
        val cx = dx.coerceIn(-127, 127)
        val cy = dy.coerceIn(-127, 127)
        val cw = wheel.coerceIn(-127, 127)
        val cp = pan.coerceIn(-127, 127)
        return when (val path = Uplink.resolve(
            rt?.hid,
            btConnected = bt?.isConnected == true,
            wireless = ControlTarget.isControlling() && ControlTarget.controlClient != null,
        )) {
            Uplink.WIRELESS -> {
                val c = ControlTarget.controlClient
                if (c == null) {
                    // 判据刚过、客户端就被清掉（切传输类 / 断开）：如实记无出口
                    Uplink.set(Uplink.NONE, "受控目标已断开")
                    false
                } else {
                    Uplink.set(path, ControlTarget.label)
                    c.mouse(buttons, cx, cy, cw, cp)
                    true
                }
            }
            Uplink.USB -> {
                val rep = byteArrayOf(
                    RID_MOUSE,
                    buttons.toByte(),
                    cx.toByte(),
                    cy.toByte(),
                    cw.toByte(),
                    cp.toByte(),
                )
                if (rt?.hid?.sendInputReport(rep) == true) {
                    Uplink.set(path)
                    true
                } else {
                    // 写不进去就**不能**还显示 USB（界面会骗人），与 HidKeys 同处理
                    val why = "USB HID 写入被拒（主机未取走上一报告或节点异常）"
                    if (Uplink.reason != why) Log.w(TAG, why)
                    Uplink.set(Uplink.NONE, why)
                    false
                }
            }
            Uplink.BLUETOOTH -> {
                Uplink.set(path)
                bt?.reportMouse(buttons, cx, cy, cw, cp)
                true
            }
            else -> {
                val first = Uplink.current != Uplink.NONE
                val why = if (ControlTarget.isControlling()) {
                    "受控目标已断开；USB HID 未就绪、蓝牙未连接"
                } else {
                    "USB HID 未就绪（/dev/hidg0 未挂载或 USB 口被调试占用）、蓝牙未连接"
                }
                Uplink.set(Uplink.NONE, why)
                if (first) Log.w(TAG, "鼠标无可用出口：$why")
                false
            }
        }
    }

    /** 多媒体键位图（[com.allperiph.ui.HotkeyController] 的位定义），出口同上。 */
    fun consumer(bitmap: Int): Boolean {
        if (bitmap == 0) return false
        val rt = AgentController.runtime
        val bt = AgentController.module(ModuleId.BTHID) as? BtHidDevice
        return when (val path = Uplink.resolve(
            rt?.hid,
            btConnected = bt?.isConnected == true,
            wireless = ControlTarget.isControlling() && ControlTarget.controlClient != null,
        )) {
            Uplink.WIRELESS -> {
                val c = ControlTarget.controlClient
                if (c == null) {
                    Uplink.set(Uplink.NONE, "受控目标已断开")
                    false
                } else {
                    Uplink.set(path, ControlTarget.label)
                    c.consumer(bitmap)
                    true
                }
            }
            Uplink.USB -> {
                val rep = byteArrayOf(
                    RID_CONSUMER,
                    (bitmap and 0xFF).toByte(),
                    ((bitmap shr 8) and 0xFF).toByte(),
                    0,
                )
                if (rt?.hid?.sendInputReport(rep) == true) {
                    Uplink.set(path)
                    true
                } else {
                    Uplink.set(Uplink.NONE, "USB HID 写入被拒")
                    false
                }
            }
            Uplink.BLUETOOTH -> {
                Uplink.set(path)
                bt?.reportConsumer(bitmap)
                true
            }
            else -> {
                Uplink.set(Uplink.NONE, "USB HID 未就绪、蓝牙未连接、未选受控设备")
                false
            }
        }
    }
}
