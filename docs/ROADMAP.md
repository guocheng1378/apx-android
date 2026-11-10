# 路线图与接口预留（Wi‑Fi 控制已交付 → 蓝牙补全）

> 本文记录 v1.x 的**能力现状**与后续主线（**蓝牙补全**：键盘 → 手柄），
> 以及**已预留在代码里的扩展点**。动手改这些接口前请先读本文。
> **本文是能力现状的真源**：`docs/ARCHITECTURE.md` 描述的是更广的目标形态，
> 两份文档的状态说明已于 2026-10-23 按源码同步：**副屏镜像已交付**（本文 §2.3，真机验证；
> 终止的仅是 IddCx 扩展屏）、**摄像头代码已随 v0.3.2 全部移除**。各处历史留档勿据以判断可用性。
> **2026-10-24 更新**：macOS（arm64）测试版随 v211 发布，平台能力总表见 §2.4；
> 原有各节的落点/状态为 Windows 与 Android 视角（均属实），平台差异以 §2.4 为准。

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

**2026-11-06 源码复核**：四类报告（鼠标 rid1 / 多媒体 rid3 / 键盘 rid2 / 手柄 rid4）与
四点发送接入全部落地，择路判据唯一 = `core/Uplink.resolve`（无线 → USB → 蓝牙 → NONE）。
下表保留 2026-10-23 的核对明细。**剩余欠账两条**：① 真机配对实测（按
[REALDEVICE-NOTES §9](./REALDEVICE-NOTES.md) 清单执行）；② 手柄 TCP 出口（ViGEmBus
第三方驱动，归入架构 §2.2「阶段二 借力第三方」，非本轮）。

| 能力 | 报告 | 状态（2026-10-23 源码核对） |
|---|---|---|
| `reportKeyboard(mod, keys)` | rid 2 | ✅ 已实现（v184）：8B `[mod, reserved, k1..k6]`，`sendReport(0x02, …)`（Report ID 不进 data，v186 修复）；**键盘出口已接入**（`HidKeys.send` → `Uplink.BLUETOOTH` 分支，2026-10-23 起，见 §4 第 2 条） |
| `reportGamepad(btn, x,y,rx,ry)` | rid 4 | ✅ 已实现（v184）：6B `[btn_lo, btn_hi, x, y, rx, ry]`，`GamepadController` 已走蓝牙优先 |

> ✅ 已完成（2026-10-23 核对）：`registerApp` 优先取 `ApxNative.btReportDescriptor()`（native，与 `shared` 同源），
> `BtHidDescriptor.bytes` 降为 libapx.so 未加载时的 fallback，且 v186 已给全部 TLC 补上 Report ID 声明。

### 2.2 Wi‑Fi 控制（局域网 TCP）—— **已实现，真机验证通过**

**形态**：**统一控制面（TCP `9511`，手机可服务端可客户端）**；被控时手机做服务端（广播 `APX1TV`），PC 主动连入。
选这个方向是因为 PC 发起的是**出站**连接，Windows 防火墙默认放行，用户不必开入站规则。

**无蓝牙 PC 的完整闭环**（本机实测无任何蓝牙设备，纯 WiFi 跑通）：

| 环节 | 落点 |
|---|---|
| Android 承载 | ⚠️ `wireless/TcpControlChannel.kt` 与 `core/TcpCtrlBridge.kt` **已在 v1.11 移入 `.attic/wireless_removed_v1.11/`，源码树中不存在**（2026-10-23 核对：全库搜不到源码）；当前 9511 上行走 `wireless/ControlTarget` + `TvControllerClient`（受控目标 TV/PC） |
| Android 出口 | 择路唯一判据 = `core/Uplink.kt` 的 `resolve(hid, btConnected, wireless)`：无线目标 → USB → 蓝牙 → NONE，触控板/键盘/多媒体/手柄四点共用 |
| Android 发现 | ⚠️ `WirelessBeacon.kt` 已随 v1.11 移除（源码树不存在，2026-10-23 核对）；现行发现/连接以 `wireless/` 现存文件为准 |
| PC 客户端 | `pc/host/src/wireless/wireless_link.cpp`（握手 + 解帧 + SendInput 注入） |
| PC 发现 | `pc/host/src/wireless/beacon_listener.cpp`（收信标自动连入） |
| CLI | `apxhost wireless <ip>:port [秒数]` / `apxhost wireless-listen [秒数]` |

> ⚠️ 2026-10-23 核对：本表 PC 侧三行（`wireless_link.cpp` / `beacon_listener.cpp` / CLI）
> 均不在 `pc/host/CMakeLists.txt` 的 `APXPC_WIRELESS_SRC` 构建清单里（现行清单 =
> wireless_session / ctrl9511 / file 收发 / input 注入），以构建脚本为准、勿按本表找代码。

**控制面子命令**（streamId=3，改一边必须改另一边：

> `0x01` 第 6 字节 `pan` 是 v223.1 加的**横向滚动**（右滚为正，四端都已接：macOS
> `CGEventCreateScrollWheelEvent` 双轴 / Windows `MOUSEEVENTF_HWHEEL` / Linux `REL_HWHEEL`）。
> 老控制端只发 5 字节时按 0 处理 —— 接收侧判 `bodyLen >= 6` 才读第 6 字节，双向兼容。

```
0x01 鼠标   [1]=buttons [2]=dx(i8) [3]=dy(i8) [4]=wheel(i8) [5]=pan(i8，可选)
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
> `SelectObject` 还原的话读到的是一张 1×1 单色位图 —— 现象是整窗一片空白，
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
| **摄像头**（手机 → PC） | 手机 `camera/CameraPushActivity.kt`（CameraX → JPEG → `MediaOut.camera()`，`streamId=6`）→ PC `media/media_session.cpp` 收流 → `media/camera_view.cpp`（GDI+ 解码 → 预览窗 + 虚拟摄像头）；面板 `camera.toggle` 经 0x10「camera」拉起/关闭手机采集页（M7-b + M7-c，2026-11-06） | ✅ 代码完成，**真机未实测**（清单见 [spike §4](./spikes/m7-media-inbound-spike.md)） |

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

### 2.4 macOS 平台现状（v211 起，测试版）

2026-10-24 随 v211 发布首版；**验证级别 = CI 编译/链接双平台全绿**（macOS 26 runner +
MSVC 回归），**真机实测尚未执行**（用户声明跳过）—— 下表「可用」指代码链路完整、
诚实降级项均已标注，不代表实机验证已通过。

| 能力 | 状态 | 落点 / 说明 |
|---|---|---|
| 浏览器控制面板 | ✅ | `apxhost panel` / `./apxdesktop` → `src/ui/panel_http.cpp`（方案 B：仅 127.0.0.1，`?host=1`），协议与 Windows WebView2 面板同构；前端 `bridge.ts` 第三态 `http`（400ms 轮询 state/events，POST act/query） |
| 桌面壳 apxdesktop | ✅ | `desktop_main.cpp` mac 分支（GUI 基础设施 Win 条件化；`share.h` 归位），CI 已纳入编译（macos.yml `--target apxhost apxdesktop`）；zip 内含 **`AllPeriph.app`**（ad-hoc 签名 + Info.plist 授权宿主，双击起壳自动开面板），终端 `./apxdesktop` 等价；**托盘 v223.7 起已交付**（`src/tray/tray_mac.mm`：菜单栏图标 + 「打开面板」「退出全能外设」，事件循环语义仍需实机确认） |
| 9511 受控端 + 信标 | ✅ | `Ctrl9511Server` + `gethostname`（双入口自带），手机「TV 控制」列表可发现（标 `(PC)`）；与 `apxhost serve` 并存时端口冲突会警告降级 |
| 控制面客户端（连手机） | ✅ | `wireless_session` 跨平台（此前已可编），`startAuto` 自动发现 |
| 文件接收 9512 | ✅ | `file.list`/`file.delete` 可用（浏览器文件卡）；**发送本机文件 ❌**（浏览器无系统文件对话框，明确报错） |
| 媒体通道 9502 | ✅ | `media_session.cpp` 跨平台（connect/收发流/5s 拥塞重试，6 处等价变换保留 Win 语义）；面板媒体泵自动建链 |
| 麦克风桥 | ✅ | `mic_bridge.cpp` `__APPLE__` 分支：CoreAudio **AudioQueue**（回调驱动、缺数据补静音、400ms 环形上限）；接线 = panel_http `setHandler`（`kStreamMic → feed`） |
| 音箱（系统声音） | ✅ | `audio_capture_mac.mm`：**CoreAudio Process Tap**（`CATapDescription` 全局立体声 + 聚合设备 + IOProc），**macOS 14.2+**，需「系统音频录制」TCC 授权；DRM 内容可能静音（平台限制）；部署目标压 12.0 使新 API 弱链接可降级 |
| 副屏 | ❌ | 虚拟显示器仅 Windows；`screenPush=nullptr`，面板显示未初始化形态 + `screen.toggle` 明确拒绝 |
| 浏览器面板摄像头 | ✅ | `panel_http.cpp` + `bridge.ts`：0x10「camera」拉起手机采集（跨平台，与 §2.3 同通道）；收流计数/尺寸/停滞实时上卡片；**无原生预览窗与虚拟摄像头**（camera_view 仅 Windows，CMake `if(WIN32)`，诚实标注） |
| 音箱设备下拉 | ❌（无此概念） | Process Tap 采全局进程组混音，不挂具体端点（`listRenderDevices` 返回空、面板下拉为空，自洽） |
| 全局热键 | ❌ | 热键 Win-only（mac 侧 `HotkeyManager` 是空实现，面板如实显示不可用） |
| USB 有线（枚举） | ◑ | v223.7 起 mac 走 **IOKit**（`usb_enum_iokit.cpp`）：VID/PID/名称/序列号/bcdUSB/接口类码齐（速度留 unknown，理由见文件头），面板 USB 状态不再恒 false；**端点/HID 集合/CDC 串口名未做**（见文件头注释），且只有编译级验证 |
| 开机自启 | ✅ | `~/Library/LaunchAgents/` plist（`autostart.cpp` 非 Win 分支） |
| 检查更新 | ✅ | `update_check_mac.mm`（NSURLSession + gh-proxy 镜像兑底，与 Windows 同比对算法） |
| 发版产物 | ✅ | macos.yml `tags: ['v*']` → `AllPeriph-<tag>-macos-arm64.zip` 上传 Release（与 build.yml release job 双向幂等） |

**mac 构建/发版**：`cmake -S pc/host -B build_host -DCMAKE_BUILD_TYPE=Release -DAPXPC_BUILD_UI=OFF`
（部署目标自动压 12.0）；前端 `pc/web` npm build 后 dist 拷到 exe 旁 `web/`；日常直接用
CI artifact 或 Release 资产。详见 [`使用说明.md`](./使用说明.md) 二/七/九章与 README。

**待办（按价值排序）**：① 真机实测（面板/连接/媒体/系统授权弹窗）② USB/IOKit 枚举的**真机核对**
（枚举后端 v223.7 已交付，当前只有编译级验证；端点/HID 集合/CDC 串口名待真机比对）。

**v223.7 已清掉的三条**：NSStatusItem 托盘 + `.app` 内退出入口（`src/tray/tray_mac.mm`，
菜单栏图标 + 「打开面板」「退出全能外设」；托盘跑在独立 NSThread 上跑 `[NSApp run]`，
事件循环语义仍需实机确认）· USB/IOKit 枚举（`src/discovery/usb_enum_iokit.cpp`）·
`panel_http` 的 9511 「已在运行」检测（`apxpc::net::tcp_probe`：已在运行的实例不再重复起
受控端、不打告警，面板显示归属）。

### 2.5 副屏与 Sunshine / Moonlight 的关系（结论，2026-10-27）

背景：#47 反馈建议「把私有实现移出，用成熟开源方案 —— Sunshine 对 macOS 有原生支持，
正好补 Mac 副屏」。核实后给的结论如下（事实已查证，见文末链接）。

**核实到的事实**

| 事实 | 影响 |
|---|---|
| Sunshine（LizardByte）是 Moonlight 协议的**宿主端**，有 Windows / Linux / macOS 二进制（macOS 分 arm64 与 x86_64 两个 dmg） | 它能替我们解决「mac 上没有虚拟显示器驱动」这件事 |
| Sunshine 官方把 **macOS 标为 experimental**，并明确写 **Gamepads do not work**；系统音频在 macOS 14.0+ 才走 Apple Audio Tap，更早要靠 BlackHole / Soundflower | 手柄这条**不能指望它** —— 正好是 #47 里最痛的那条 |
| 客户端是 Moonlight，**Moonlight 全部客户端（含 `moonlight-android`）是 GPLv3** | 本项目是 MIT，**不能把它的代码嵌进来** |
| Sunshine 系统要求表写 macOS 14.2+ | 用户的 mac 版本需要先满足 |

**结论**

1. **副屏 Windows 侧维持自研**：私有链路已交付（虚拟显示器 + 硬件编码，真机解码 393 帧），没有替换理由。
2. **mac / Linux 副屏不再自研**：这两端缺的恰恰是虚拟显示器驱动（Windows 有 IddCx，mac 没有对等物），
   自研成本高、且与成熟方案重叠 —— 改为**用户在 mac 上自装 Sunshine + Moonlight**，
   我们只在面板里做**检测 + 指引**（不内嵌客户端）。
3. **不内嵌 Moonlight 客户端**：GPLv3 与 MIT 不兼容（唯一合规的内置路径是照协议自研
   「RTSP 握手 + RTP 解密 + 硬件解码 + 输入回传」，工作量按一整个模块算，排在现有待办之后）。
4. **手柄在 mac 上的正解仍然是蓝牙 / USB HID**（手机自己就是 HID 设备、免驱），
   不是无线出口、也不是 Sunshine —— Sunshine 在 mac 上同样没有手柄。

**依据**：Sunshine 官方文档（macOS 安装 / 系统要求 / 「Gamepads do not work」）
<https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2getting__started.html>；
`moonlight-android` 仓库 `LICENSE.txt` = GNU GPL v3
<https://github.com/moonlight-stream/moonlight-android>。


---

**手柄真注入已接入（2026-11-06 核对源码，PR #24 落地）**：0x07 手柄帧 →
Windows `input_injector_win.cpp` 运行时探测 `ViGEmClient.dll`（2026-11-06 起随包分发）
+ ViGEmBus 驱动注入虚拟 X360，缺任一则降级「按钮→键盘 + 摇杆→鼠标/滚动」并如实记日志；
Linux 走 `/dev/uinput` 真手柄；macOS 无 CGEvent 手柄通道仅降级（AMFI 限制见 `gamepad_fallback.hpp`）。

---

### 2.6 界面层迁移到 Compose + Miuix（2026-11-01 口径）

**决定**：手机端 `android/app` 与电视端 `android/tv` 的界面层**全面**改用 Jetpack Compose +
Miuix 重写，替换旧的 Views/XML 实现。此前「暂不迁移、只在 TV 试点」的草稿口径作废；
README 里的「零第三方依赖」不再是界面层的否决理由，改为「逻辑层零依赖 + 界面层依赖白名单」。

**依赖基线**（已在 master）：`androidx.activity:activity-compose:1.10.1`、
`top.yukonga.miuix.kmp:miuix-ui/blur/preference/icons-android:0.9.4`、
`dev.chrisbanes.haze:haze:1.7.3`；`compileSdk 37`（Miuix 0.9.4 的 AAR 要求）、
`minSdk 34` / `targetSdk 35`、AGP 9.4.1、Kotlin 2.4.20。

**迁移顺序**（每阶段一个可独立 revert 的提交）：
1. 设计系统与主题骨架（`ui/miuix/ApTheme|ApAccent|ApInsets|ApMotion|ApComponents`）；
2. 手机端主壳 + 状态页；
3. 手机端触控板页；
4. 手机端设置页（含「关于」卡从 PoC 转正）；
5. 电视端主屏 + 文件页；
6. 切换默认入口、删除遗留 Views/XML、同步文档。

**进度（2026-11-01，master `417cd7f` 之后的实测口径）**：

| 阶段 | 状态 | 说明 |
|---|---|---|
| 1 设计系统与主题骨架 | 已完成 | `ui/miuix/ApInsets|ApMotion|ApAccent|ApComponents|ApEffects|ApAdaptive`（`ApOverlayHost` 交付后从未被调用，已于 `refactor/ui-kit-unify` 删除） |
| 2 手机端主壳 + 状态页 | 已完成 | `ApShellActivity` 接管 LAUNCHER；四页签、四指切页、设备选择器、横屏手势盾 |
| 3 手机端触控板页 | 已完成 | `TouchpadPage`，手势面用 `pointerInteropFilter` 保原始 MotionEvent |
| 4 手机端设置页 | 已完成 | 模板/模板管理/环境诊断/文件与传输/传输分类/遥控与键盘/主题数据/键映射/背景图/运行日志（筛选·查看·导出） |
| 5 电视端主屏 + 文件页 | 已完成 | `TvHome`、`TvFileScreen`、`TvOverlayHost` + 通知浮窗；`TvOverlay`/`RemoteInputOverlay` 有书面理由留 View |
| 6 切换默认入口、删除遗留 Views/XML | 进行中 | 已删 `MainActivity.kt`/三个 Fragment/`KeyboardPanels`/`TouchpadSplit`/`activity_main`/`activity_screen` 等；`SendActivity`、`GamepadActivity`、`ScreenActivity`、`RemoteInputActivity`、`FilePanelActivity` 已迁 Compose 并脱钩旧主题栈；**旧主题栈剩余调用方**：`TouchpadActivity`、`RemoteActivity`、`ApShellActivity`（仅 `ThemePref.wrap` 提供明暗，`ApPalette.applyRuntimeTheme` 已去（皮肤/取色已拆成 ApPalette / ApSkin））、`FloatingBallService`（`wrapService` 还要给 Service 挂 `Theme.AllPeriph`）、`SettingsPage` 切皮肤后的同步调用 |

**剩余一处口径**：`RemoteInputActivity`（被控端输入面板）仍是 View 实现，冻结在
`EditText` + `TextWatcher` + 平台 IME 这条链路上——逐字增量上屏是文字到对端的**唯一**通道，
把输入法搬进 Compose 的 `BasicTextField` 属于「拿能不能打出字换一致性」，因此判定为
**用 `AndroidView` 包住输入框、只迁外壳**，而不是整页重写。这一页迁完，旧外观总控（已拆成 ApPalette / ApSkin）/ `ThemePref`
的取色职责即可正式下线（导出/导入/预设管理属于功能，保留）。

**必须保留的行为**（来自 2026-10-28 三条未落地补丁中仍然成立的部分）：
edge-to-edge 用框架 `WindowInsets` 手动垫边（systemBars + displayCutout + ime，不引 androidx）；
模板色只把色相拉向 `system_accent*_600`、饱和度与明度不动；动效与震动档位可关，关时直接落位、不震。

**范围外**：`core/` `hid/` `audio/` `bt/` `gadget/` `wireless/` `localsend/` 逻辑层、
`shared/` 的 C++、`pc/` 全部。

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

**Wi‑Fi 的落地方式**：⚠️ 2026-10-23 核对——`wireless/TcpControlChannel.kt` 与 `core/TcpCtrlBridge.kt`
已于 v1.11 移入 `.attic/wireless_removed_v1.11/`，"输入模块经 TcpCtrlBridge 出口"的形态已不存在；
当前无线出口为 `wireless/ControlTarget` + `TvControllerClient`（受控目标），择路唯一判据见
`core/Uplink.kt`（无线目标 → USB → 蓝牙 → NONE）。
历史教训仍有效：**不要在调用线程直接写 socket** —— 手势帧来自 UI 线程会触发
`NetworkOnMainThreadException`（§2.2 真机教训）。

### 3.2 输入出口：✅ 判据已由 `core/Uplink.kt` 统一（原计划的 `InputSink` 不再需要）

> **2026-10-23 源码核对**：判据唯一化早已落地为 `Uplink.resolve(hid, btConnected, wireless)`，
> 触控板 / 多媒体 / 键盘 / 手柄四个发送点与通知栏、状态页都读它（`Uplink.current`）。
> 下文的 `InputSink` 接口草图是**历史方案**，不必实现 —— 现存的重复只是
> “各发送点自己取模块实例”，不构成第二套判据。本轮补齐的两处缺口：
> 键盘蓝牙分支（`HidKeys` 写死 `btConnected=false`）与主页 `isBtConnected()` 写死 `false`。

<details><summary>历史方案草图（保留备查）</summary>

现状：鼠标 / 多媒体 / 键盘**三处各自在模块内择路**，判据与优先级重复了三遍
（`TouchpadModule.dispatch`、`HotkeyController.send`、`HidKeys.send`）：

```
蓝牙（isConnected） → USB HID（hid.isReady） → Wi‑Fi 控制（TcpCtrlBridge.ready） → 仅日志降级
```

只有**手柄**没有 Wi‑Fi 出口（`ui/GamepadController` → `rt.hid`，仅 USB）。

> ⚠️ 上面这句「仅 USB」是当时的快照，已被后续实现推翻：`GamepadController.send()`
> 现在蓝牙优先（`BtHidDevice.reportGamepad`）→ 再走 `Uplink.resolve`（无线 9511 → USB），
> 三条出口都在。以 `ui/GamepadController.kt` 的 `send()` 为准。

两者都有代价：① 三份重复的判据容易改漏一处；② 蓝牙键盘/手柄缺的是**真机配对与实测**
（`BtHidDevice` 自 v184 起已有 `reportKeyboard` / `reportGamepad`，出口也已接入）。

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

1. ~~**输入统一**：新增 `InputHub`/`InputSink`~~ ✅ 已由 `core/Uplink.resolve` 满足（判据唯一，四点共用）；
   2026-10-23 补齐的是键盘蓝牙分支与 `MainActivity.isBtConnected()` 真值，见 §3.2 顶部核对注。
2. ~~**蓝牙键盘**~~ ✅ 已完成（2026-10-23）：native 描述符 + `reportKeyboard` 早已在，
   `HidKeys.send` 已接 `Uplink.BLUETOOTH` 分支（剩真机配对打字验证）。
3. ~~**蓝牙手柄**~~ ✅ 描述符与 `reportGamepad`（rid 4）均已实现（v184），`GamepadController` 已走蓝牙优先；
   剩真机配对验证；TCP 出口已接入（ViGEmBus 运行时探测 + 降级，见 §2.2 手柄条）。
4. ~~**Wi‑Fi 控制**~~：✅ 曾完成并真机验证；v1.11 起 Android 侧（服务端/出口/信标）移入 `.attic`，
   现行无线出口 = 受控目标链路（§2.2 核对注）。PC 侧是否仍在构建以 `pc/host/CMakeLists.txt` 为准（§2.2 末核对注）。

> 2026-10-23 更新：**输入出口已统一在 `Uplink.resolve`**（§3.2）；`reportKeyboard`/`reportGamepad` 与
> native 蓝牙描述符均已存在，本轮把键盘接进蓝牙分支。手柄 TCP 出口后于 PR #24 落地
> （2026-11-06 核对）：`ui/GamepadController` → 受控目标 0x07 → Windows ViGEmBus 虚拟
> X360（驱动缺失则键盘降级并如实记日志，DLL 随包分发，见 §2.2 手柄条）。

> 每一步都遵循项目硬性原则：**不可用则如实标注（`ModuleState.DEGRADED/ERROR`），绝不伪装成功。**

---

## 五、借鉴 NectarLink 的功能分批规划（2026-11-11 规划，未开工）

> 来源调研见 [REF-PROJECTS.md](./REF-PROJECTS.md) NectarLink 节：该项目为 GPL-3.0 / MPL-2.0 且核心是 Rust，
> **只借思路与交互，任何代码不得进入本仓**。以下每批按「两端改动面 → 协议命令字 → 权限 → 验收口径 → 真机验证项」展开。

### 5.0 前置基建：握手能力协商（第一批之前必须落地）

- **动机**：NectarLink 用握手交换能力表，旧版安静降级。我们的协议目前没有能力字段，
  新增任何 PC↔手机功能（通知、相册、响铃）都会撞上"旧端收到不认识的帧"。
- **做法**：握手帧（APX1）追加 `capabilityMask`（64 位位图）+ `protocolRev` 字段；
  `shared/` 为唯一真源（`scripts/check_protocol.py` 纳入校验）。旧端忽略未知字段，
  新端发现对端能力缺失时**不发送对应帧族**，UI 上如实显示"对端版本不支持"。
- **验收**：新手机 + 旧 PC（或反过来）连接不报错、不误发帧；`check_protocol.py` 全绿。

### 5.1 第一批：通知同步 + 查找手机

> **状态（2026-11-11）**：两项代码均已落地并推送 master —— 能力协商 0x33（152b016）、
> 通知同步 0x40/0x42（d58f326）、查找手机 0x38（e30e47f）。范围按「先小后大」裁剪：
> 通知同步先做 推送/消除 双帧（回复 0x41、点击动作未做，见 PROTOCOL §3.3 表），
> PC 端托盘气泡仅 Windows 接线；原验收项「验证码一键复制」已补齐——PC 端展示层命中
> 即自动复制进本机剪贴板并在气泡回显（见 PROTOCOL 0x40 行）；查找手机先做「响铃 +
> 亮屏 + 30s 自动停」，「PC 再点停止 / 手机上点掉即停」的交互留待后续。均未发版，待真机验证。

**① 通知同步（日常最高频，先做）**

| 项 | 内容 |
|---|---|
| 手机端改动面 | 新增 `NotificationListenerService` 实现类（`core/` 旁新 `notify/` 包），收到通知后按 **通知 key（包名 + tag + id + when）** 记账进内存表；`AgentController` 注册为独立模块（沿用模块注册制，设置页独立开关，**默认关**） |
| PC 端改动面 | `pc/host` 注入系统通知：Windows 走 WinRT `ToastNotification`，macOS 走 `UNUserNotificationCenter`；面板新「通知」开关页（过滤规则在此配） |
| 协议命令字 | 控制面新增帧族：`NOTIFY_PUSH`（新通知）/ `NOTIFY_DISMISS`（手机划掉 → PC 同步消除）/ `NOTIFY_REPLY`（PC 点回复 → 手机经 RemoteInput action 代发）/ `NOTIFY_ACTION`（点击动作转发） |
| 特性 | ① 验证码类通知（正则匹配）单独通道，PC 通知上一键复制；② 密码管理器内容过滤（正则表，PC 端可编辑）——NectarLink 同款；③ 直接回复只走 `RemoteInput` 通道，不支持回复的 App 只展示 |
| 权限 | Android「通知使用权」（系统设置授权，App 内给引导页，如悬浮球权限的先例）；PC 侧通知权限 |
| 验收口径 | 手机收通知 → PC 弹；PC 点回复 → 手机 App 内上屏；手机划掉 → PC 消失；验证码可一键复制；密码类通知不出现在 PC |
| 真机验证项 | 微信（需亮屏后台）/ 短信验证码 / 无 RemoteInput 的 App 各一条；通知风暴（连发 20 条）PC 端不卡 |

**② 查找手机（小活，快活）**

- PC 面板一个按钮 → 控制面命令 → 手机响铃：走 `AudioManager` 高音量流（`STREAM_ALARM`），
  **静音也响**；顺带点亮屏幕。独立开关，默认开。
- 验收：手机静音、锁屏，PC 点响铃 3 秒内出声；再点停止或手机上点掉即停。

### 5.2 第二批：相册投影 + 悬浮球「最新照片卡片」

**① 悬浮球「最新照片卡片」（先做小步，借鉴 Shelf 交互）**

- 手机端悬浮球菜单顶部常驻"最近一张截图/照片"卡片，按住可直接拖出 → 即发往 PC（走 9512 文件传输）。
- 不依赖相册投影全量，`MediaStore` 取最新一张即可；这一步是相册投影的先行验证。

**② 相册投影（全量）**

| 项 | 内容 |
|---|---|
| 手机端改动面 | 新增相册模块：`MediaStore` 按天分组（`DATE_ADDED` bucket），缩略图经 `ContentResolver.loadThumbnail` 流式懒加载（分页查询，不一次性全表）；原图按需取（PC 端滚动到哪取到哪）；`ContentObserver` 监听新照片 → 推 PC 通知 |
| PC 端改动面 | 面板新「相册」页：瀑布流缩略图、按天分组、拖拽导出到本地、原图查看 |
| 权限 | `READ_MEDIA_IMAGES`（Android 13+ 分媒体权限，引导授权页复用通知的先例） |
| 验收口径 | 2000+ 张图的首屏加载 < 2s（缩略图流式）；拖拽导出无损原文件；新拍照片 PC 端 5s 内出通知 |
| 真机验证项 | 大图库（万级）滚动不掉帧；安卓 13/14 权限弹窗实测 |

### 5.3 明确不做

- **App 窗口化**：依赖无线调试（开发者选项），与本项目「免开发者选项」的定位冲突；
- **QUIC + 中继体系**：NectarLink 的加密中继面向公网场景，我们是局域网工具，差距是定位不是实现力；
  安全化按既有次序（鉴权）推进，不引入 Rust 核心。

---

## 六、平板适配 UI 规划（2026-11-11 规划；6.2 首批已落地，未发版）

> 现有基础：`ui/miuix/ApAdaptive.kt` 的 `rememberApAdaptiveLayout()` 按窗口实测宽度
> （`LocalWindowInfo.containerSize` 优先，Configuration 滞后只作冷启动回退）分
> Compact / Medium / Expanded 三档，`twoPane = ≥600dp`；`DeviceFormPref` 管设备语义。
> 规划要点是**把这套体系从"设置页专属"推广成全 App 约定**。
>
> **进度（2026-11-11）**：6.2 两栏脚手架 `ApTwoPaneScaffold` 已落地——设置页双栏迁移到
> 统一壳（观感不变），状态页接入双栏（左=开关与操作，右=所选模块详情 + 链路诊断 + 帮助，
> 模块行可点选，窄屏保持原单栏）。6.3 键盘键高上限、手柄双摇杆分置，以及 6.1 的
> Expanded「低栏收侧边竖条」（待设计确认）尚未开工；平板真机验证待补。

### 6.1 统一断点与档位行为

| 档位 | 宽度 | 典型场景 | 行为约定 |
|---|---|---|---|
| Compact | <600dp | 手机竖屏、分屏一半 | 单栏 + 底部悬浮低栏（现状） |
| Medium | 600–839dp | 手机横屏、小平板竖屏、折叠内屏 | 允许 twoPane；操控面横屏复用规则（§6.3） |
| Expanded | ≥840dp | 平板横屏、电视 | twoPane 优先；卡片留白加大；低栏收成侧边竖条（待设计确认） |

- **唯一排版输入 = 窗口实测宽度**：任何页面不得自判机型或抄 `cfg.screenWidthDp`（历史坑：旋转滞后一拍，见 `ApAdaptive.kt` 头注）。新页面一律 `rememberApAdaptiveLayout()` 取档位。

### 6.2 列表-详情双栏推广（twoPane 复用）

设置页已落 twoPane（左分组列表右详情）。规划推广到信息面页面：

- **状态页**：左栏模块开关列表，右栏所选模块的链路详情/诊断；
- **文件传输页**：左栏传输记录，右栏详情（进度、失败原因、重试）——**已落地**（文件面板 v2，≥600dp 走 `ApTwoPaneScaffold`，左=列表点选、右=详情+操作+传输记录）；同批落地「电脑浏览手机文件」（跨端协议 0x44–0x47，PC 面板里逛手机目录、取回单个文件，能力位 bit3）；
- **日志/诊断**：左栏会话列表，右栏日志流；
- **相册投影（第二批功能）**：天生列表-详情（相册列表 ↔ 图片大图）。

实现方式：抽一个 `ApTwoPaneScaffold(left, right)`（Miuix 组件拼装，不自绘壳），
设置页现有实现迁移过去，新增页面直接用。

### 6.3 操控面横屏复用规则（信息面与操控面分流）

- **信息面**（状态/设置/文件/日志/相册）：走 twoPane 列表-详情，随断点自动分栏；
- **操控面**（触控板/键盘/手柄/副屏）：目标永远是"工作区越大越好"，横屏规则 = **操控区撑满 + 工具条侧置**：
  - 触控板：左右分栏（已交付）规划为"左触控区 / 右快捷区"固定语义；
  - 键盘：6 行横排（已交付）保持；Expanded 时键高不无限拉伸，超出部分给工具键（剪贴板/语音/灯效）；
  - 手柄：Expanded 双摇杆区分置两侧，中间放按键区（现状单面板，需改造）。
- **四指切页、一指横滑切分组**在平板同样生效（手势判定按窗口宽度，与断点无关，无需改）。

### 6.4 触达与安全区

- **48dp 触控下限 + contentDescription**：沿用 ui README 硬规则 3，平板不小、反而更要点名执行——
  分栏中间缝隙的拖拽把手、twoPane 右栏返回键是新增的可点元素，逐一过 `UI-CHECKLIST.md`；
- **挖孔垫边**：`padCutout` 已内置（横屏必垫），平板横屏同样生效，无需新逻辑；
- **悬浮球与低栏在平板**：悬浮窗不受宽度影响，保持现状；低栏在 Expanded 收成侧边竖条属新交互，
  排期时与 twoPane 推广同批真机验证。

### 6.5 验收口径

每档宽度（手机竖屏 / 手机横屏 / 平板竖屏 / 平板横屏）`uiautomator dump` 对照
[UI-CHECKLIST.md](./UI-CHECKLIST.md) 截图留档；旋转后布局即时切换（依赖
`LocalWindowInfo` 同源测量，回归用例固定加入）。平板真机待补——目前仅有手机横屏验证基础。

### 6.6 实施顺序建议

1. 先落 **5.0 能力协商**（所有新功能的共同前置，改动小、风险低）；
2. **第一批：通知同步**（用户确认优先级后开工）→ **查找手机**（顺手的小活）；
3. 平板适配的 **6.2 两栏脚手架**可与第一批并行（纯 UI，互不依赖）；
4. **第二批：悬浮球照片卡片 → 相册投影**；
5. 每批发版后检查 Release 正文含「改了什么」（历史坑：macos.yml/build.yml 竞速占位正文）。
