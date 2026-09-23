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

### 2.2 Wi‑Fi 控制（局域网 TCP）—— **已实现，真机验证通过**

**形态**：**手机做服务端**（`ServerSocket(9500)`），PC 主动连入。
选这个方向是因为 PC 发起的是**出站**连接，Windows 防火墙默认放行，用户不必开入站规则。

**无蓝牙 PC 的完整闭环**（本机实测无任何蓝牙设备，纯 WiFi 跑通）：

| 环节 | 落点 |
|---|---|
| Android 承载 | `core/ApxFrame.kt`（APX1 组帧）+ `wireless/TcpControlChannel.kt`（服务端 + writer 线程） |
| Android 出口 | `core/TcpCtrlBridge.kt`；触控板/键盘/多媒体三处以「蓝牙 → USB HID → TCP → 日志」择优 |
| Android 发现 | `wireless/WirelessBeacon.kt`：UDP 9501 广播 `APX1PHONE <name> <port> <token>` |
| PC 客户端 | `pc/host/src/wireless/wireless_link.cpp`（握手 + 解帧 + SendInput 注入） |
| PC 发现 | `pc/host/src/wireless/beacon_listener.cpp`（收信标自动连入） |
| CLI | `apxhost wireless <ip>:port [秒数]` / `apxhost wireless-listen [秒数]` |

**控制面子命令**（streamId=3，改一边必须改另一边：

```
0x01 鼠标   [1]=buttons [2]=dx(i8) [3]=dy(i8) [4]=wheel(i8)
0x02 多媒体 [1..2]=u16 位图（LE）
0x03 键盘   [1]=mod [2]=0 [3..8]=k1..k6（HID usage 页 0x07，PC 侧 usage→VK）
```

**验证证据**（真机 + 本机 PC）：控制面 RTT 4–6ms、丢弃 0；手机滑动 176 帧使 PC 光标
位移 (459,590) 像素；手机长按「复制」芯片期间 PC `GetAsyncKeyState` 探到 VK_CONTROL / VK_C 按下。

### 2.2.1 桌面端（apxdesktop.exe）

命令行对日常使用不友好，故按仓库既定路线（`ui/panel.hpp`：**纯 Win32 + common controls，
不引 Qt/wx**）补上桌面窗口，同时兑现了此前只声明未实现的 `runPanel()`。

| 项 | 落点 |
|---|---|
| 会话状态机 | `include/apxpc/wireless/wireless_session.hpp` + `src/wireless/wireless_session.cpp` |
| 面板窗口 | `src/ui/panel_win32.cpp`（状态大字 + 自动/手动连接 + 实时计数 + 开机自启 + 托盘） |
| 入口 | `src/desktop_main.cpp`（GUI 子系统，`/ENTRY:mainCRTStartup` 保留 `main()`） |
| 构建 | `cmake --build build_host --target apxdesktop`（`build.bat` 已一并构建） |

**分层理由**：连接是 3 秒级阻塞操作，**绝不能放 UI 线程**。`WirelessSession` 把
「发现 → 建链 → 断线自动回到等待」收在后台线程；UI 只表达意图（`startAuto` /
`connectManual` / `disconnect`）并按 400ms 定时器读快照，不做任何跨线程回调 ——
避免"回调里刷 UI"这类竞态（Android 侧已经栽过一次同类问题）。

**行为约定**：关闭窗口 / 最小化 = 收进托盘（输入注入要继续工作）；退出走托盘右键
「退出」或窗口里的「退出」按钮。`runPanel` 在非 Windows 返回 -1（CLI/SDK 不受影响）。

顺带修掉两处旧瑕疵：`TrayIcon` 托盘提示按字节加宽 UTF-8 导致**中文乱码**（改走
`MultiByteToWideChar`）；`WirelessLink::connect()` 持 `mu_` 调 `disconnect()` 的
**自死锁**（重连时才会触发，改成取锁前先收旧连接）。

**视觉与手机端统一**：面板的色值 / 圆角 / 字级全部取自
`android/.../values/{colors,styles}.xml` —— 底 `#F2F3F5`、白卡 18px 圆角 +
`#EDEDED` 描边、主色 `#3482FF`、分区标题 13px 粗体蓝字、状态语义色
ok/warn/error/idle。除两个输入框外**全部 GDI+ 自绘**（圆角胶囊按钮 / 圆形单选 /
MIUI 开关），不做原生控件的 owner-draw —— 后者拿不到手机端那种观感。

应用图标同样来自手机端：`scripts/make_icon.ps1` 按
`drawable/ic_launcher_app.xml` 的几何与配色生成 `pc/host/res/apx.ico`
（16/24/32/48/64/128 用标准 BMP 条目，256 用 PNG 条目），经 `res/apx.rc` 打进
exe，窗口与托盘共用同一枚。**手机端图标一改，重跑脚本即同步**。

> GDI+ 两个坑（都已踩过）：① `GraphicsPath` 拷贝构造是 protected，不能按值返回，
> 圆角路径要用出参构造；② 双缓冲 `BitBlt` 必须**在位图仍选中时**执行，先
> `SelectObject` 还原的话读到的是那张 1×1 单色位图 —— 现象是整窗一片空白，
> 只有原生子控件可见。

### 2.2.2 安装包（apxsetup.exe）

目标机器上 NSIS / Inno / WiX 都没有，而仓库坚持不引第三方依赖 —— 于是按同一思路
自己产一个**自包含安装包**：CMake 在**配置阶段**用 `res/setup_payload.rc.in`
生成 `.rc`，把 `apxdesktop.exe` 整个作为 `RCDATA` 打进 `apxsetup.exe`，
**单文件即可分发**，无需任何打包工具链。

| 项 | 落点 |
|---|---|
| 程序目录 | `%LOCALAPPDATA%\Programs\AllPeriph`（免 UAC；Windows 免管理员安装的惯例位置） |
| 用户配置 | `%LOCALAPPDATA%\AllPeriph\config.json`（与程序目录**分离**，卸载不清设置） |
| 开始菜单 | `%APPDATA%\...\Start Menu\Programs\全能外设.lnk` |
| 卸载项 | `HKCU\...\Uninstall\AllPeriph`（DisplayName / 版本 / 图标 / UninstallString / QuietUninstallString） |
| 自启迁移 | 原本开着 → 改指到安装位置；原本没开 → **不擅自打开**（尊重用户现状） |

命令：`--install`（默认）/ `--uninstall` / `--silent-install` / `--silent-uninstall`。

> 为什么 `.rc` 放在**配置阶段**生成、而不是 `file(GENERATE)` + `$<CONFIG>`：
> 多配置生成器会为每个配置各写一遍同名文件，内容互相覆盖，结果不确定。

> **踩坑**：卸载器 `uninstall.exe` 就住在安装目录里，`killProcessesUnder(安装目录)`
> 会把**自己**杀掉 —— 现象是卸载退出码 0、但一件事都没做（真机踩过）。必须先排除
> 当前进程。此外正在运行的程序删不掉自己，末尾交回
> `cmd /c timeout /t 2 & rmdir /s /q "<目录>"` 收尾。

> ⚠️ **真机教训（务必保留）**：手势帧的生产者是 **UI 线程**，在 UI 线程直接 `socket.write`
> 会抛 `NetworkOnMainThreadException` 被 catch 吞掉，表现为「链路在线、光标纹丝不动」，
> 只有 60ms 后子线程发的「释放帧」能漏过去。所有出站帧一律走**队列 + 专用 writer 线程**。

> 令牌（token）字段保留但 v1 默认留空（局域网工具，不做鉴权）；两侧行为必须对称。
> 令牌不匹配时服务端**直接关连接、不回执** —— 回执字节会与紧随其后的帧混淆。

**尚未接入**：手柄（`SendInput` 无法模拟游戏手柄，需 ViGEmBus 等第三方驱动，
归入架构 §2.2「阶段二 借力第三方」）；副屏视频（用户已决策终止）。

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

**Wi‑Fi 的落地方式（已实现）**：`wireless/TcpControlChannel.kt` 实现 `TcpCtrlBridge.Sink`
（`sendControl` 组 `APX1` 帧后**入队**，由专用 writer 线程写出；`ready` = 已连接）。
输入模块经 `TcpCtrlBridge`（`mouse` / `consumer` / `keyboard`）出口，不感知承载。
注意：**不要在调用线程直接写 socket** —— 手势帧来自 UI 线程，会触发
`NetworkOnMainThreadException`（§2.2 真机教训）。

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
4. ~~**Wi‑Fi 控制**~~：✅ 已完成（§2.2）：Android 服务端 + `TcpCtrlBridge` 出口 +
   UDP 信标发现；PC 端 `WirelessLink` 注入（鼠标 / 键盘 / 多媒体）。真机端到端验证通过。

> 仍未做：**输入出口统一到 `InputHub`**（§3.2）。当前鼠标/多媒体/键盘各自择路，
> 手柄（`ui/GamepadController`）尚无 TCP 出口 —— 因 `SendInput` 无法模拟手柄，
> 需第三方虚拟手柄驱动，见 §2.2「尚未接入」。

> 每一步都遵循项目硬性原则：**不可用则如实标注（`ModuleState.DEGRADED/ERROR`），绝不伪装成功。**
