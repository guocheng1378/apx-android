# 路线图与接口预留（USB 收尾 → 蓝牙 + Wi‑Fi 控制）

> 本文记录 v1.x **有线（USB）能力现状**、后续 **蓝牙** 与 **Wi‑Fi 控制** 两条线的目标，
> 以及为它们**已预留在代码里的扩展点**。动手改这些接口前请先读本文。

---

## 一、USB 能力现状（本阶段收尾）

### 1.1 已验证可用（Windows 免驱枚举）

App「状态」页打开总开关后，手机作为 USB 复合设备被 Windows 识别为多个子设备
（真机实测，设备管理器可见）：

| 子设备 | Report ID | 代码落点 | 状态 |
|---|---|---|---|
| HID 鼠标（触控板） | 2 | `hid/TouchpadModule` + `shared` `packMouse` | ✅ |
| HID 键盘 | 21 | `hid/HidKeys` + `shared` `buildTlcKeyboard` | ✅ |
| HID 多媒体键 | 4 | `ui/HotkeyController` + `shared` `packConsumerBitmap` | ✅ |
| HID 游戏手柄 | 22 | `ui/GamepadController` + `shared` `packGamepad` | ✅ |
| USB 串口（CDC ACM） | — | `gadget/SerialDevice` | ✅ |
| 音频（UAC2） | — | `audio/AudioModule` | ✅ |

对应描述符由 `shared/src/hid_descriptor.cpp` 的 `buildReportDescriptor()` 生成，
桌面端会逐子设备枚举（鼠标 / 键盘 / 多媒体 / 游戏控制器 / COM / 音频）。

### 1.2 已知限制（ROM 相关，本阶段不再投入）

- 部分机型 `init` 以**亚秒级频率**把系统 gadget 绑回 UDC，第三方进程抢不到；
- `setprop sys.usb.config` 会触发 `init` 崩溃（**整机重启**）——代码已**彻底禁用**该属性操作
  （见 `gadget/UsbHalArbiter.kt` 头部注释的真机证据）；
- 停 `vendor.usb-hal` 会让 dwc3→PC 链路瘫痪（`DEVICE_DESCRIPTOR_FAILURE`）。

**结论**：USB 这条线在本阶段收尾——能挂载的机型即插即用，抢不到 UDC 的机型走无线
（蓝牙 + Wi‑Fi，见下）。

---

## 二、后续两条线

### 2.1 蓝牙（免 root、免线缆的输入通道）

`bt/BtHidDevice` 目前已有 **鼠标**（`reportMouse`，rid 1）与 **多媒体键**（`reportConsumer`，rid 3）。
待补：

| 待补 | 报告 | 说明 |
|---|---|---|
| `reportKeyboard(mod, keys)` | rid 2 | 8B：`[0x02, mod, k1..k6]`（无 reserved，见 `buildBtTlcKeyboard`） |
| `reportGamepad(btn, x,y,rx,ry)` | rid 4 | 需扩展蓝牙描述符加 gamepad TLC |

> 蓝牙描述符**应改用 `shared` 的 `buildBtReportDescriptor()`**（已含 Mouse/Keyboard/Consumer，
> rid 1/2/3），替换 `BtHidDevice.kt` 里硬编码的 `BtHidDescriptor.bytes`（那份缺 Report ID、键盘结构也不符）。
> 需要补一个 JNI：`ApxNative.hidBtReportDescriptor()` → `apx::buildBtReportDescriptor()`。

### 2.2 Wi‑Fi 控制（局域网 TCP）

- 推荐形态：**手机做服务端**（`ServerSocket(9500)`），PC 主动连入；也支持 `adb forward` 回环；
- 承载：副屏视频、触控注入、控制命令、状态上报；
- 免 root、免线缆，补齐 USB 挂载不了的机型。

PC 端**已具备**完整 TCP 传输：`pc/display/transport/tcp_transport_win.cpp` +
`frame_writer.cpp`（帧复用/解复用）+ `ctrl_channel.cpp`（控制面会话）+ `i_transport.hpp`
（`ITransport`/`IChannel`）。**缺口只在 Android 侧的 TCP 实现**。

---

## 三、已预留的扩展点（改代码前先看这里）

设计原则：**上层业务模块不感知底层是 USB、蓝牙还是 TCP**。所有链路差异收敛到下面两个抽象。

### 3.1 数据通道：`core/Transport.kt`（已就绪，可直接复用）

```kotlin
interface HidTransport { fun sendInputReport(report: ByteArray): Boolean; fun isReady(): Boolean }
interface SerialSink   { fun write(bytes: ByteArray): Boolean;           fun isReady(): Boolean }
```

`AgentRuntime` 持有 **热替换代理**（`DelegatingHidTransport` / `DelegatingSerialSink`），
链路切换时业务模块持有的引用无需重建：

```kotlin
runtime.attachHid(usbHid)     // USB 挂载后
runtime.attachHid(btHid)      // 或蓝牙 HID（同接口）
runtime.attachSerial(tcpChan) // Wi‑Fi 挂载后
```

**Wi‑Fi 的落地方式**：新增 `core/TcpChannel.kt`，实现 `SerialSink` 语义
（`write` 把 `shared/frame.h` 的 `APX1` 帧写入 socket；`isReady` = 已连接），
在连接建立后 `runtime.attachSerial(tcpChannel)`。上层（副屏/控制/状态）零改动。

### 3.2 输入出口：统一到 `InputSink`（**建议新增，当前是分散判断**）

现状：鼠标/多媒体各自在模块内判断走蓝牙还是 USB，而**键盘/手柄只走 USB**
（`hid/HidKeys` → `rt.hid`；`ui/GamepadController` → `rt.hid`），导致蓝牙键盘/手柄缺失。

建议新增 `core/InputHub.kt`（或 `ui` 层），把四类输入统一：

```kotlin
/** 统一输入出口：鼠标 / 键盘 / 多媒体 / 手柄。实现按链路优劣自动择路。 */
interface InputSink {
    val ready: Boolean
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int)
    fun keyboard(modifier: Int, keys: IntArray)          // 6-key 数组，usage 0x07 页
    fun consumer(bitmap: Int)
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int)
    fun releaseAll()
}

/** USB 实现：pack 交给 shared/，写 rt.hid */
class UsbInputSink(private val rt: AgentRuntime) : InputSink { /* packMouse/packGamepad/... */ }

/** 蓝牙实现：写 BtHidDevice 的 report* */
class BtInputSink(private val bt: BtHidDevice) : InputSink { /* reportMouse/reportKeyboard/... */ }

/** 择优路由：USB 就绪优先，回退蓝牙；都不可用时 ready=false（上层如实降级） */
object InputHub : InputSink {
    @Volatile var usb: InputSink? = null
    @Volatile var bt: InputSink? = null
    private fun pick(): InputSink? = usb?.takeIf { it.ready } ?: bt?.takeIf { it.ready }
    // ... 各方法转发到 pick()
}
```

**落点**（后续做蓝牙时一并改，改动可控）：
- `touchpad/TouchpadModule.dispatch` 的择路逻辑 → 改调 `InputHub.mouse(...)`；
- `ui/HotkeyController.send` → `InputHub.consumer(...)`；
- `hid/HidKeys.send` → `InputHub.keyboard(...)`（**这是蓝牙键盘能用的关键**）；
- `ui/GamepadController.send` → `InputHub.gamepad(...)`。

### 3.3 控制面命令（PC → 手机）

现有：USB OUT 报告 `shared` `VendorCommandHeader`（`core/VendorCommand.kt` 解码，
如震动/手电/红外/传感器开关）。
Wi‑Fi 侧：**复用同一套命令 TLV**，改为在 TCP 帧的 `streamId=ctrl` 通道上传输
（PC 端 `ctrl_channel.cpp` 已就绪），`VendorCommand.kt` 的解码逻辑不变。

---

## 四、建议的实施顺序

1. **输入统一**：新增 `InputHub`/`InputSink`，把鼠标/键盘/手柄/多媒体的择路收敛（纯重构，行为不变）。
2. **蓝牙键盘**：JNI 暴露 `buildBtReportDescriptor`，`BtHidDevice` 换 native 描述符 +
   补 `reportKeyboard`，接进 `BtInputSink`。真机配对验证键盘可打字。
3. **蓝牙手柄**：扩展 `buildBtReportDescriptor` 加 gamepad TLC（rid 4），补 `reportGamepad`。
4. **Wi‑Fi 控制**：新增 `core/TcpChannel.kt`（服务端 9500 + `frame.h` 组帧），
   `attachSerial` 挂载；PC 端复用 `tcp_transport_win.cpp` 对接，跑通「副屏 + 控制面」。

> 每一步都遵循项目硬性原则：**不可用则如实标注（`ModuleState.DEGRADED/ERROR`），绝不伪装成功。**
