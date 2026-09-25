# 路线图与接口预留（Wi‑Fi 控制已交付 → 蓝牙补全）

> 本文记录 v1.x 的**能力现状**与后续主线（**蓝牙补全**：键盘 → 手柄），
> 以及**已预留在代码里的扩展点**。动手改这些接口前请先读本文。
> **本文是能力现状的真源**：`docs/ARCHITECTURE.md` 描述的是更广的目标形态，
> 其中副屏（已终止）与摄像头（未编译）都还按原始设计保留，勿据此判断可用性。

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

**形态**：**统一控制面（TCP `9511`，手机可服务端可客户端）**；被控时手机做服务端（广播 `APX1TV`），PC 主动连入。
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

> ⚠️ **帧长与 CRC 口径（同一处踩了两次，务必保留）**：APX1 的 `payloadLen` **含**扩展头
> 与尾部 CRC，**帧总长 = `16 + payloadLen`**（`apx::frameTotalSize`）；**CRC 只覆盖
> 16 字节帧头之后的字节，不含尾部那 4 字节**。`pc/display` 的 `validateFrame` 曾
> ①把扩展头算两次（在 `payloadLen` 之外又加 `headerExtWords*4`）、②按「整帧含帧头」
> 算 CRC，于是 `parseFrame` 把每一帧都判成坏帧：`apxdisp --self-test` 三项失败（拼回=0），
> 且挂在它上面的控制面收帧（`ctrl_channel.cpp`）与 pipeline 也全拒。
> **长期没暴露的原因**：接收侧普遍不校验 CRC —— Android `TcpControlChannel` 只在发送时算，
> 所以控制面能正常跑；直到 `--self-test` 直接调 `parseFrame` 才炸出来。
> 改这三个常量中任何一个都要两端同时改，并跑 `apxdisp --self-test`（应 **67 项全通过**）。
> 权威定义见 [`PROTOCOL.md`](./PROTOCOL.md) §3。

### 2.3 媒体通道（副屏 / 音箱 / 麦克风 / 摄像头）—— v1.11 新增

与控制面**并列的第二条连接**（手机 TCP 9502），承载大流量的四路流。端口与流号约定见
[`PROTOCOL.md`](./PROTOCOL.md) §3.4。

| 能力 | 落点 | 状态 |
|---|---|---|
| 地基：多路分发 + 分片重组 | 手机 `core/ApxStreams.kt`（`Consumer` + `FragmentJoiner`）、`core/MediaOut.kt`；PC `media/media_session.cpp` | ✅ |
| **副屏**（镜像主桌面） | 手机 `screen/`（`ScreenModule` + `ScreenRenderer` + `ScreenActivity`）；PC `media/screen_push.cpp`（复用 `pc/display` 的抓屏/编码/组帧/编排，**注入传输适配器**写到共用的媒体连接） | ✅ 已集成进桌面面板，真机**已解码 393 帧** |
| **音箱**（PC 声 → 手机扬声器） | PC `media/audio_capture.cpp`（**WASAPI loopback**，免驱，**可选采集端点 / 可跟随系统默认**）→ `streamId=1` → 手机 `audio/WirelessAudioModule.kt` 写 AudioTrack | ✅ 面板开关 + 采集设备下拉已接通，真机验证 |
| **麦克风**（手机 → PC） | 手机 AudioRecord → `MediaOut.mic()`（`streamId=5`） | ✅ PC 侧收到 1002 帧 PCM |
| **摄像头**（手机 → PC） | 待做：Camera2 JPEG → `MediaOut.camera()`（`streamId=6`）；PC 侧预览 | ❌ **未开始** |

**验证证据**（真机 2026-09-24，PC 与手机同一局域网）：

```
apxhost speaker <手机IP> 8 [设备序号]   # 音箱：采集本机系统声音送手机
  可选的播放设备（不指定序号 = 跟随系统默认播放设备）：
    [0] S2720H (英特尔(R) 显示器音频)   · 系统默认
    [1] Realtek Digital Output (Realtek High Definition Audio)
  将采集：跟随系统默认（当前 S2720H (英特尔(R) 显示器音频)）
  音箱采集已启动：S2720H (英特尔(R) 显示器音频)（48000Hz 2ch，每片 10ms）
  [   8s] 808 片 · 1515 KB · 丢 0 · 峰值 [##                  ] 0.08
  # 对照：不播放任何声音时 —— 0 片 · 0 KB · 峰值 0.00
  # （loopback 在系统静音时**根本不产生数据包**，不是送静音包，见下）
  # 对照：指定到没在播声音的那块（apxhost speaker <IP> 8 1）—— 同样 0 片；
  #       同一链路、同一个开关，只是**换了块设备**，听起来和"坏了"一样。
  #       这正是「采集设备」下拉存在的理由。
  # 序号越界会明确报错：设备序号 9 不存在（共 2 个）

apxhost media <手机IP> 12 tone        # 音箱下行 + 麦克风上行
  [12s] 在线 | 副屏 0帧 音箱 726帧 | 麦克风 1202帧 摄像头 0帧 | 丢 0 重同步 0

apxdisp --run --transport tcp --host <手机IP> --port 9502 --no-handshake
  29.1 fps，发送 312 帧，队列丢弃 0

手机端 logcat：ScreenRenderer: 解码器已启动 video/avc 1920x1080
副屏页状态字：副屏运行中 · 已解码 393 帧
```

> ⚠️ **同一条约定踩了两次（务必保留）**：
> ① `pc/display` 的 `exchangeToken` 在令牌为空时**提前 return**，不发那 4 字节长度头，
> 与手机端「永远先读 4 字节」不对称 → 手机把紧随其后的**视频帧头当成令牌长度**，
> 判非法后直接关连接（现象：PC 侧编码正常 29fps，发几帧就「对端关闭」）。
> ② 更隐蔽：`TcpTransport::sendBytes` 用 `opened_` 当门禁，而握手发生在 `opened_ = true`
> **之前** → 握手包被**静默拒发**（返回 false 且不写 `lastError_`，日志里一条错误都没有，
> 只在对端看到「读取令牌头失败」）。
> 两者合起来说明：`pc/display` 的**令牌握手路径此前从未真正跑通过**（非空令牌同样发不出去）。
> 教训：**失败路径必须留日志**，否则会把「发不出去」误判成「对端解析错」。
>
> ③ **Windows 头文件顺序**：`media_session.hpp` 自己 include `<winsock2.h>`（连锁拉进
> `<windows.h>`）。若在包含它**之前**还没定义 `UNICODE`/`_UNICODE`，`windows.h` 就按 ANSI
> 定型且 include guard 已生效 —— 之后本文件里的 `LoadIconW(nullptr, IDI_APPLICATION)`
> 会报「LPSTR 不能转 LPCWSTR」。**规则：那些宏必须在任何 apxpc 头之前定义。**
>
> ④ **`apxdisp_pipeline` 缺依赖声明**：它调 `createInjector` / `parseTouchFrame`
> （在 `apxdisp_inject` 里）却没声明该依赖，只因为 `apxdisp.exe` 自己又链了一遍才没暴露。
> 任何**单独**链接 `apxdisp_pipeline` 的目标（如桌面端复用副屏管线）都会在链接期报
> 未解析符号。已在 `pc/display/CMakeLists.txt` 补上。
>
> ⑤ **WASAPI loopback 在系统静音时不产生数据包**（不是送静音包）。所以"手机上没声音"
> 时，第一件事是看 `apxhost speaker` 的计数是否还在涨 —— 计数为 0 说明**系统本身没在
> 放声音**，链路没问题。采集侧要主动给静音补零（保持时间轴连续），否则手机端 AudioTrack
> 会反复断音。
>
> ⑥ **loopback 采的是「某一块渲染端点的数字流」，与"那块设备是否真的发声"无关**。
> 所以默认设备是没喇叭的 HDMI 显示器**不构成静音原因**；真正会踩的是**采错了端点**
> （用户在耳机上听、采集在显示器音频上）。这是最高频的误配，因此面板音箱卡片：
> - 「采集设备」做成下拉：第 0 项恒为**跟随系统默认**（标题里带上当前默认的设备名，
>   不展开就能看出会采哪块），其后是每一块实体端点（默认那块标「· 系统默认」）；
> - 唯一能区分"跟随"与"指定"的位置是那行端点名 —— **跟随**时写「实际采集：」、
>   **指定**时写「采集自：」，且**单独占一行不被截断**；
> - 指定了一块**已拔掉/被禁用**的设备时**明确报错，绝不悄悄回落到默认** ——
>   回落会让人以为在听耳机、其实采的是显示器。
>
> 「跟随系统默认」的变更检测**没有用 `IMMNotificationClient`**，而是每 2 秒比一次
> 设备指纹（默认端点 ID + 全部端点 ID）。理由：指纹比对同时覆盖了「默认易主」与
> 「插拔设备」两种情形，且不必持有 COM 回调对象、不必处理回调线程与 UI 线程的
> 跨线程投递。指纹变了就①重填下拉②若在跟随则按新默认重开采集。实测有效
> （改默认 → 下拉标题与「实际采集」同时跟上，见 `docs` 的验证记录）。
>
> ⑦ **格式归一优先交给引擎**：`AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
> AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY` 能让音频引擎直接按我们要的 48k/16bit/立体声
> 投递，一次转换都不用写；只有它被拒时才退回按混音格式采 + 自己转（`audio_capture.cpp`
> 两条路径都在）。
>
> ⑧ `functiondiscoverykeys_devpkey.h`（`PKEY_Device_FriendlyName`）**依赖 `propkeydef.h`
> 里的 `DEFINE_PROPERTYKEY`**，少 include 这一行会报一串"`DEFINE_PROPERTYKEY` 重定义"。

**桌面端面板集成（v1.11 完成）**：面板新增「副屏」卡片 —— 开关 + 实时
`fps / 已送帧 / 分辨率 / 编码耗时 / 丢帧`。要点：

- 面板**自动**随控制链路建立/收掉媒体通道（`tick()` 里做，不用用户额外操作）；
- **媒体建链放一次性工作线程**：`MediaSession::connect` 是 3 秒级阻塞操作，
  放 UI 线程会整窗卡死（与 `WirelessSession` 同一理由）；
- 退出时顺序固定：**先停推流 → 再 join 建链线程 → 最后断媒体**，否则工作线程可能
  访问已析构的 `MediaSession`；
- 副屏与音频/摄像头**共用同一条媒体连接**（手机侧单对端语义），因此 `Pipeline` 必须
  支持注入现成传输（`Pipeline::setTransport`），不能再自开第二条 TCP。

**仍未做**：镜像是「抓现有桌面」；要"扩展第二块屏"仍需 IddCx 虚拟显示器驱动。
`apxdisp.exe` 保留（CLI/自测仍用它），但日常使用已不必单独运行。

---

**尚未接入**：手柄（`SendInput` 无法模拟游戏手柄，需 ViGEmBus 等第三方驱动，
归入架构 §2.2「阶段二 借力第三方」）。

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

现状：鼠标 / 多媒体 / 键盘**三处各自在模块内择路**，判据与优先级重复了三遍
（`TouchpadModule.dispatch`、`HotkeyController.send`、`HidKeys.send`）：

```
蓝牙（isConnected） → USB HID（hid.isReady） → Wi‑Fi 控制（TcpCtrlBridge.ready） → 仅日志降级
```

只有**手柄**没有 Wi‑Fi 出口（`ui/GamepadController` → `rt.hid`，仅 USB）。

两者都有代价：① 三份重复的判据容易改漏一处；② 蓝牙键盘/手柄仍缺失
（`BtHidDevice` 无 `reportKeyboard` / `reportGamepad`）。

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
- `ui/GamepadController.send` → `InputHub.gamepad(...)`（蓝牙手柄能用的关键）；
- 新增 `WifiInputSink`（转发 `TcpCtrlBridge.mouse/consumer/keyboard`）——
  注意其 `gamepad` 必须保持不可用：`SendInput` 无法模拟游戏手柄。

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
