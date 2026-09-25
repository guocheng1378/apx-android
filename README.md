# 全能外设（AllPeriph）

把手机变成电脑的外设：**触控板、键盘、多媒体键、麦克风与音响，以及副屏（屏幕投射）**。
电脑端一个桌面控制面板（托盘常驻）统一管理，命令行宿主 `apxhost` 供调试与脚本用。
支持**有线（USB，性能最高且手机同时充电）**与**无线（蓝牙 + 局域网 Wi‑Fi，免 root 免线缆）**两条主线。

- 电脑端：C++20（Windows），**零第三方依赖**，自写 winsock HTTP/1.1 + SSE。
- 控制面板：原生 HTML/CSS/JS 单页应用，**零构建、零 CDN、离线可用**，浏览器打开即用。
- 手机端：Kotlin（Android），蓝牙 HID + 局域网 TCP + USB Gadget（有线模式）。

> 项目首页文档：[`README.md`](./README.md) ·
> **最终用户使用说明：[`docs/使用说明.md`](./docs/使用说明.md)** ·
> 架构说明：[`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) ·
> 协议定义：[`docs/PROTOCOL.md`](./docs/PROTOCOL.md) ·
> 真机实测记录：[`docs/REALDEVICE-NOTES.md`](./docs/REALDEVICE-NOTES.md) ·
> 路线图与接口预留：[`docs/ROADMAP.md`](./docs/ROADMAP.md)

> **当前阶段**：有线（USB）能力已收尾；**Wi‑Fi 控制**已真机验证；
> **Wi‑Fi 媒体**（副屏镜像/扩展屏 + 触摸、音箱、麦克风）已落地，电脑面板三张媒体卡可用；
> **统一控制面 9511**（手机 / TV / PC 三端同一套协议，可互相发现与互控，含文件传输 9512）已落地，
> **TV 端**可作为长期在线的被控端（信标随服务常驻 + 开机自启 + 保活引导）。
> 蓝牙键盘/手柄仍在补全（见 [`docs/ROADMAP.md`](./docs/ROADMAP.md)）。

---

## 一、最简单的用法

### A. 最终用户：装包即用（推荐）

拿到 `apxsetup.exe`（单文件，不依赖任何打包工具链）双击安装：

```bat
apxsetup.exe                    :: 装到 %LOCALAPPDATA%\Programs\AllPeriph，并建开始菜单快捷方式
apxsetup.exe --uninstall
apxsetup.exe --silent-install :: 静默安装（另有 --silent-uninstall）
```

从开始菜单打开「全能外设」后，面板即开始监听手机信标；**手机侧打开「Wi‑Fi 控制」模块
便会自动连入**，无需填写任何地址。关闭窗口 = 收进托盘（输入注入照常工作）。

### B. 开发：源码构建

> 前提：Windows 10/11 + Visual Studio 2022（含「使用 C++ 的桌面开发」）+ CMake ≥ 3.20。

在仓库根目录执行：

```bat
:: 一键构建三个产物（apxhost / apxdesktop / apxsetup）；加 nobuild 参数则只构建不启动
build.bat

:: 或只要命令行宿主（产物：build_host\Release\apxhost.exe）
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
cmake --build build_host --config Release --target apxhost

:: 免安装直接跑桌面端面板
build_host\Release\apxdesktop.exe
```

`apxhost` 附带一个零构建的本地 Web 控制面板（前端在 `pc\host\web`），会自动定位目录并
打开 **http://127.0.0.1:47990**（端口被占用时自动 +1，并在日志/托盘提示）。
只想后台常驻、不自动开浏览器：

```bat
build_host\Release\apxhost.exe serve
```

如需显式指定前端目录：`set "APXPC_WEB_DEV_DIR=%CD%\pc\host\web"`。

---

## 二、目录结构（完整路径）

```text
全能外设/
├─ README.md                         本文件
├─ LICENSE                           MIT 许可证
├─ .gitignore                        构建产物与临时文件忽略规则
├─ .gitattributes                    换行统一（.bat=CRLF / .sh=LF）
├─ build.bat                         一键构建 apxhost / apxdesktop / apxsetup，随后打开 Web 面板
├─ run.bat                           仅启动面板（需先构建）
│
├─ docs/                             设计与协议文档
│   ├─ ARCHITECTURE.md               双主线架构、通道表、Report ID 复用、降级代价
│   ├─ PROTOCOL.md                   帧格式、Mouse TLC、触控板载荷、控制面命令
│   ├─ REALDEVICE-NOTES.md           蓝牙 HID / Wi‑Fi 传输的真机实测结论
│   ├─ ROADMAP.md                    能力现状、蓝牙/Wi‑Fi 两条线的扩展点与实施顺序
│   ├─ RELEASE-NOTES-0.3.2.md        本版本发布说明（摄像头模块清理）
│   └─ REQ-五路回报.md               需求记录（历史）
│
├─ shared/                           两端共享契约（core-proto 协议库）
│   ├─ CMakeLists.txt
│   ├─ README.md
│   ├─ include/apx/
│   │   ├─ frame.h                   统一帧头（magic 'APX1' + streamId）
│   │   ├─ hid_layout.h              HID 报告布局（含触控板 MouseReport，复用 Report ID 2）
│   │   ├─ hid_descriptor.h          HID 描述符构建入口
│   │   ├─ clock.h / units.h         时基与单位
│   ├─ src/                          上述头文件的实现（*.cpp）
│   └─ tests/
│
├─ pc/                               电脑端（Windows）
│   ├─ host/                         常驻宿主服务 + Web 控制面板
│   │   ├─ CMakeLists.txt
│   │   ├─ include/apxpc/            公共头：app / api / bandwidth / config / ctrl /
│   │   │                            discovery / display / hotkey / log / net /
│   │   │                            platform / sensors / tray / ui / version / ...
│   │   ├─ res/                      图标（apx.ico）+ 安装包载荷模板（setup_payload.rc.in）
│   │   ├─ src/
│   │   │   ├─ host_main.cpp         命令行入口（serve / ui / pair / scene / list /
│   │   │   │                        wireless / wireless-listen）
│   │   │   ├─ desktop_main.cpp      桌面端面板入口（apxdesktop.exe，GUI 子系统）
│   │   │   ├─ setup_main.cpp        安装/卸载（apxsetup.exe，把自己体内的载荷落盘）
│   │   │   ├─ wireless/             Wi‑Fi 控制通道：WirelessLink（TCP 客户端 +
│   │   │   │                        SendInput 注入）+ BeaconListener（UDP 信标发现）
│   │   │   ├─ ui/panel_win32.cpp    桌面端面板（纯 Win32 + GDI+ 自绘，无第三方依赖）
│   │   │   ├─ app/host_service.cpp  常驻服务编排：HTTP + 热键 + 托盘 + 配置 + 状态广播
│   │   │   ├─ api/action_router.cpp 动作路由 + 状态聚合（面板唯一控制面）
│   │   │   ├─ net/http_server.cpp   winsock HTTP/1.1 + SSE + Origin 校验 + 静态资源
│   │   │   ├─ net/json.cpp          极简 JSON（零依赖）
│   │   │   ├─ config/app_config.cpp config.json 读写与 schemaVersion 迁移
│   │   │   ├─ bandwidth/arbiter.cpp 统一带宽预算仲裁与降级
│   │   │   ├─ display/display_control.cpp  IDisplayController 三后端（A/B/C）
│   │   │   ├─ hotkey/hotkey_manager.cpp    RegisterHotKey 全局热键 + 改键 + 冲突检测
│   │   │   ├─ tray/tray_win32.cpp    托盘图标 + 开机自启
│   │   │   ├─ discovery/             设备发现（Windows SetupAPI / usb_types / discovery 门面）
│   │   │   ├─ sensors/               系统传感器读取（工厂 + 平台后端）
│   │   │   ├─ ctrl/                  控制面传输（HID / bulk）
│   │   │   ├─ log/ platform/ util/   日志、平台、工具
│   │   ├─ web/                       控制面板前端（零构建、零 CDN、离线可用）
│   │   │   ├─ index.html             单页骨架：侧边导航 + 顶部状态条 + 视图容器
│   │   │   ├─ styles.css             设计系统：令牌、玻璃卡片、动效、响应式
│   │   │   └─ js/
│   │   │       ├─ app.js             视图路由 + 全局状态分发 + 顶栏/横幅
│   │   │       ├─ api.js             动作调用封装（pending/success/error 三态）
│   │   │       ├─ sse.js             SSE 订阅 + 演示模式 + 连接健康
│   │   │       ├─ ui.js              通用组件（卡片/指标/开关/分段/折线/横幅…）
│   │   │       └─ views/             七个视图：overview / screen / devices /
│   │   │                             touchpad / connection / hotkeys / settings
│   │   └─ tests/
│   │       └─ arbiter_test.cpp       带宽仲裁离线单测
│   │
│   ├─ display/                      副屏推流与触控注入（抓屏 DDA / H264 编码 / TCP 传输 /
│   │                                SendInput 注入；支持虚拟屏扩展屏与光标合成）
│   │   ├─ CMakeLists.txt
│   │   ├─ app/main.cpp              apxdisp 命令行（--self-test 等）
│   │   ├─ capture/                  抓屏（Desktop Duplication / null）
│   │   ├─ encode/                   编码（Media Foundation / NVENC / QSV / AMF / RAW-LZ4）
│   │   ├─ transport/                传输抽象与实现
│   │   │   ├─ i_transport.hpp       ITransport / IChannel / TransportSpec（含 Tcp）
│   │   │   ├─ usb_transport_win.cpp WinUSB USB bulk 传输
│   │   │   ├─ tcp_transport_win.cpp 无线局域网 TCP 传输（服务端/客户端 + 帧解复用）
│   │   │   ├─ loopback_transport.cpp 回环（离线自测）
│   │   │   ├─ transport_factory.cpp 传输工厂
│   │   │   ├─ frame_writer.cpp      组帧/解帧/FrameSplitter
│   │   │   ├─ ctrl_channel.cpp      控制面会话（TLV 握手/心跳）
│   │   │   └─ link_monitor.cpp      链路监控与断线自愈
│   │   ├─ inject/                   触控注入（SendInput / SyntheticPointer + 触控板 MouseFrame）
│   │   ├─ pipeline/                 三线程编排（抓屏→编码→组帧→传输 + 触控注入）
│   │   ├─ protocol/frame_format.hpp 帧格式常量与校验
│   │   └─ tests/tcp_transport_test.cpp   TCP 传输离线单测（解复用）
│   │
│   └─ tools/                        辅助工具
│
├─ android/                          手机端（Kotlin）
│   ├─ app/src/main/AndroidManifest.xml
│   ├─ app/src/main/cpp/             JNI 桥（apx_jni.cpp，仅参数搬运 + 调 shared/）
│   ├─ app/src/main/java/com/allperiph/
│       ├─ screen/                   副屏收流渲染 + 触摸回传（控制通道 0x04）
│       ├─ core/                     模块契约与传输接口（Module / Transport / ApxNative /
│       │                            ApxFrame 组帧 / TcpCtrlBridge 出口）
│       ├─ gadget/                   ConfigFS 布局与 Gadget 管理（USB 有线）
│       ├─ hid/                      USB HID 键盘与键码映射
│       ├─ touchpad/                 触控板（相对鼠标手势）
│       ├─ audio/                    UAC2 双向音频
│       ├─ bt/                       蓝牙 HID 设备（鼠标 / 多媒体）
│       ├─ wireless/                 统一控制面（TCP 9511）+ UDP 信标广播（APX1TV）
│       └─ ui/                       主界面、游戏手柄、服务编排（AgentController）
│
├─ scripts/                          辅助脚本（apx_gadget.sh / aggregate.py / verify_ms1.ps1）
└─ reports/                          生成的报告（.md / .json）
```

---

## 三、电脑端宿主服务（apxhost）

### 子命令

| 命令 | 说明 |
| --- | --- |
| `apxhost ui` | 启动常驻服务并**自动打开**控制面板 |
| `apxhost serve` | 启动常驻服务，**不**自动打开浏览器 |
| `apxhost pair` | 启动服务并进入无线配对引导 |
| `apxhost scene` | 启动服务并应用场景编排 |
| `apxhost list` | 枚举设备后退出 |
| `apxhost ctrl9511-connect <手机IP>[:端口] [秒数]` | 连入手机/电视 9511 统一控制面并注入输入（默认端口 9511） |
| `apxhost ctrl9511-remote <手机IP>[:端口] [秒数]` | 远程桌面接管（连入后回传本机屏幕） |
| `apxhost ctrl9511-serve [端口] [名称] [秒数]` | 本机作为 9511 服务端并被控（广播 APX1TV 供手机自动发现） |
| （无参数）`apxhost` | 进入交互式命令循环 |

日常使用建议直接跑桌面端 `apxdesktop.exe`（装包后是开始菜单里的「全能外设」），
它把上面的发现 / 建链 / 注入 / 托盘常驻包成了一个窗口。

### 环境变量

| 变量 | 作用 |
| --- | --- |
| `APXPC_WEB_DEV_DIR` | 显式指定前端静态资源目录（例如 `pc\host\web`）。**通常无需设置**：宿主会自动定位 |

### 配置文件（完整路径）

```text
%LOCALAPPDATA%\AllPeriph\config.json
```

包含 `schemaVersion`（当前 1）、HTTP 端口、开机自启、连接模式、触控板参数、热键绑定与
场景绑定；另保留副屏（`display*`）的参数位。
字段缺失会自动回落默认值，不会因旧配置崩溃。

---

## 四、构建

### 1) 电脑端宿主服务（必需）

```bat
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
cmake --build build_host --config Release --target apxhost
:: 产物：build_host\Release\apxhost.exe
```

### 2) 电脑端副屏模块（包含在 build.bat 主构建中；也可单独构建）

```bat
cmake -S pc/display -B build_display
cmake --build build_display --config Release
```

### 3) 共享协议库（可选）

```bat
cmake -S shared -B shared/build
cmake --build shared/build --config Release
```

### 4) 手机端（可选，需要 Android SDK）

```bat
cd android
gradlew.bat assembleDebug
```

> 一键构建 PC 三件套（含桌面面板与安装包）：仓库根目录 `build.bat`。

---

## 五、测试（离线可跑，无需真机）

```bat
:: 带宽仲裁决策
cmake --build build_host --config Release --target arbiter_test
build_host\Release\arbiter_test.exe

:: 无线 TCP 传输（服务端/客户端 + streamId 解复用）
cmake --build build_display --config Release --target tcp_transport_test
build_display\Release\tcp_transport_test.exe
```

---

## 六、两种连接模式

### 有线模式（USB）

手机通过 USB 复合设备被电脑免驱识别，性能最高，且手机同时充电。
Windows 设备管理器可见的子设备：**HID 鼠标 / 键盘 / 多媒体键 / 游戏手柄、CDC 串口、UAC2 音频**。
**需要 Gadget 权限（通常需 root）**；部分机型 `init` 会以亚秒级频率抢占 UDC 导致挂不上，
这类机型请走无线模式（详见 [`docs/ROADMAP.md`](./docs/ROADMAP.md) §1.2）。

### 无线模式（蓝牙 + 局域网 Wi‑Fi，免 root 免线缆）

- **Wi‑Fi 控制（已落地，真机验证通过）**：统一控制面（TCP `9511`）+ UDP `9501` 广播信标
  `APX1TV <name> <port=9511> <token>`，电脑**主动连入**（出站连接，Windows 防火墙默认放行），
  收到 `streamId=3` 控制帧后用 `SendInput` 注入。**无蓝牙适配器的 PC 也完整可用。**
  触控板 / 键盘 / 多媒体键在手机侧统一按「蓝牙 → USB HID → Wi‑Fi → 如实降级」择优。
- **蓝牙 HID（部分）**：已承载触控板与多媒体键，免驱；**键盘与手柄待补**。
  注意：描述符一变，PC 端**旧配对记录必须删除重连**，否则 Windows 会拿缓存描述符解析导致错位。
- **面板**：桌面端可用「自动发现」（监听 APX1TV 信标）或填 IP:9511；`adb forward` 回环（`tcp://127.0.0.1:9511`）
  仅开发期联调，不是产品形态。

> **后续路线**：主线是**蓝牙补全**（键盘 → 手柄）。现状是鼠标/多媒体/键盘各自在模块内择路，
> 尚未收敛到统一的 `InputHub`；手柄无 Wi‑Fi 出口（`SendInput` 无法模拟游戏手柄）。
> 扩展点与实施顺序见 [`docs/ROADMAP.md`](./docs/ROADMAP.md)。

---

## 七、如实降级（重要）

本项目的硬性原则：**不可用的能力必须明确标注，绝不伪装成功。**

- 手机端各模块用 `ModuleState`（RUNNING / DEGRADED / ERROR / STOPPED）如实上报，状态页与
  设置页照原样展示 —— 抢不到 UDC、蓝牙权限不足、内核不支持 UVC，都直接标出来而非静默。
- Wi‑Fi 控制链路的**表盘口径与真实择路完全一致**：USB HID 就绪显示 USB 档位，否则蓝牙
  已连接显示蓝牙，否则 Wi‑Fi 控制，否则「未连接」—— 不含糊，也不把没连上的链路报成当前通道。
- 带宽不足时按优先级降级（触控上行 > 视频 > 音频）并记录「为谁降了什么」，
  相关降级逻辑保留在 `pc/display/` 内。

---

## 八、已知限制（发布前须知）

1. **Android 端已在本机编译验证**：`gradle assembleDebug` 通过（JDK 17 + Android SDK 35 +
   Gradle 8.9），产物 `android/app/build/outputs/apk/debug/app-debug.apk`。
   真机结论见 [`docs/REALDEVICE-NOTES.md`](./docs/REALDEVICE-NOTES.md)。
2. **`apxdisp`（副屏）可正常构建**。早前在 Windows SDK
   10.0.22621 的 WRL `ComPtr<IMFSample>` 处报错，**已修复**（见
   `reports/MAIN-INTERVENTIONS.md`）。本机实测：
   ```bat
   cmake -S pc/display -B build_display
   cmake --build build_display --config Release              :: apxdisp.exe 构建成功
   build_display\Release\apxdisp.exe --self-test             :: 67 项全部通过
   build_display\Release\tcp_transport_test.exe              :: ALL PASS
   ```
   注意 `pc/display/` **不在 `build.bat` 的构建范围内**（它属副屏模块）。
3. **无线虚拟设备**：
   - **麦克风 / 声卡**：Windows **没有**原生虚拟麦克风 API，必须用第三方已签名虚拟声卡
     （如 VB-Cable）或自研驱动（需 EV 签名 + 微软认证）。
   - 无法满足时面板会如实标注，不做静默失败。

---

## 九、许可

本项目采用 **MIT** 许可，详见 [`LICENSE`](./LICENSE)。

---

## 十、开发约定

- **零第三方依赖 / 零 CDN**：电脑端与面板均不引入外部库，保证离线可用。
- **面板交互**：动作按钮统一 pending / success / error 三态；失败**就地**在卡片内展示原因，不弹窗打断。
- **传输统一**：Wi‑Fi 控制通道的 TCP 客户端与组帧收敛在 `pc/host/src/wireless/wireless_link.cpp`
  与 `android/.../core/ApxFrame.kt`（APX1 帧，与 `shared/` 的 CRC 算法一致）。
  `pc/display/transport/i_transport.hpp` 是副屏时代的传输抽象，随副屏一并停用。
- **共享契约先行**：`shared/include/apx/` 同时影响手机端与电脑端，改动需两端同步。
