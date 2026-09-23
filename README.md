# 全能外设（AllPeriph）

把手机变成电脑的外设：**副屏、触控板、摄像头、音频**，电脑端一个本地 Web 控制面板统一管理。
支持**有线（USB，性能最高且手机同时充电）**与**无线（蓝牙 + 局域网 Wi‑Fi，免 root 免线缆）**两条主线。

- 电脑端：C++20（Windows），**零第三方依赖**，自写 winsock HTTP/1.1 + SSE。
- 控制面板：原生 HTML/CJS/JS 单页应用，**零构建、零 CDN、离线可用**，浏览器打开即用。
- 手机端：Kotlin（Android），蓝牙 HID + 局域网 TCP + USB Gadget（有线模式）。

> 项目首页文档：[`README.md`](./README.md) ·
> 架构说明：[`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) ·
> 协议定义：[`docs/PROTOCOL.md`](./docs/PROTOCOL.md) ·
> 真机实测记录：[`docs/REALDEVICE-NOTES.md`](./docs/REALDEVICE-NOTES.md) ·
> 路线图与接口预留：[`docs/ROADMAP.md`](./docs/ROADMAP.md)

> **当前阶段**：有线（USB）能力已收尾（鼠标/键盘/多媒体/游戏手柄/串口/音频均可在
> Windows 免驱枚举）。后续主线是 **蓝牙补全** 与 **Wi‑Fi 控制**，两者的扩展点已在
> [`docs/ROADMAP.md`](./docs/ROADMAP.md) 中说明。

---

## 一、最简单的用法（3 步）

> 前提：Windows 10/11 + Visual Studio 2022（含「使用 C++ 的桌面开发」）+ CMake ≥ 3.20。

在仓库根目录执行：

```bat
:: 1) 编译电脑端宿主服务（产物：build_host\Release\apxhost.exe）
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
cmake --build build_host --config Release --target apxhost

:: 2) 启动并自动打开控制面板（会自动定位 pc\host\web，无需任何配置）
build_host\Release\apxhost.exe ui
```

宿主会自动在「exe 同级 `web\`」以及逐级向上的 `pc\host\web\` 中寻找前端目录（零配置）。
如需显式指定，可设置环境变量：`set "APXPC_WEB_DEV_DIR=%CD%\pc\host\web"`。

面板默认地址：**http://127.0.0.1:47990**（端口被占用时会自动 +1，并在日志/托盘提示）。

只想后台常驻、不自动开浏览器：

```bat
build_host\Release\apxhost.exe serve
```

---

## 二、目录结构（完整路径）

```text
全能外设/
├─ README.md                         本文件
├─ LICENSE                           MIT 许可证
├─ .gitignore                        构建产物与临时文件忽略规则
├─ .gitattributes                    换行统一（.bat=CRLF / .sh=LF）
├─ build.bat                         一键构建并打开面板（Windows，双击即用）
├─ run.bat                           仅启动面板（需先构建）
│
├─ docs/                             设计与协议文档
│   ├─ ARCHITECTURE.md               双主线架构、通道表、Report ID 复用、降级代价
│   ├─ PROTOCOL.md                   帧格式、Mouse TLC、触控板载荷、控制面命令
│   ├─ REALDEVICE-NOTES.md           蓝牙 HID / Wi‑Fi 传输的真机实测结论
│   └─ REQ-五路回报.md               需求记录
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
│   │   ├─ src/
│   │   │   ├─ host_main.cpp         命令行入口（serve / ui / pair / scene / list）
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
│   ├─ display/                      副屏推流与触控注入（有线模式核心）
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
│   └─ app/src/main/java/com/allperiph/
│       ├─ core/                     模块契约与传输接口（Module / Transport / ApxNative）
│       ├─ gadget/                   ConfigFS 布局与 Gadget 管理（USB 有线）
│       ├─ hid/                      USB HID 键盘与键码映射
│       ├─ touchpad/                 触控板（相对鼠标手势）
│       ├─ audio/                    UAC2 双向音频
│       ├─ bt/                       蓝牙 HID 设备（鼠标 / 多媒体）
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
| （无参数）`apxhost` | 进入交互式命令循环 |

### 环境变量

| 变量 | 作用 |
| --- | --- |
| `APXPC_WEB_DEV_DIR` | 显式指定前端静态资源目录（例如 `pc\host\web`）。**通常无需设置**：宿主会自动定位 |

### 配置文件（完整路径）

```text
%LOCALAPPDATA%\AllPeriph\config.json
```

包含 `schemaVersion`（当前 1）、HTTP 端口、开机自启、连接模式、副屏/触控板/摄像头/音频参数、热键绑定与场景绑定。
字段缺失会自动回落默认值，不会因旧配置崩溃。

---

## 四、构建

### 1) 电脑端宿主服务（必需）

```bat
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
cmake --build build_host --config Release --target apxhost
:: 产物：build_host\Release\apxhost.exe
```

### 2) 电脑端副屏模块（有线模式，可选）

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

- **蓝牙 HID** 承载全部输入类（触控板 / 键盘 / 多媒体键），免驱。
- **局域网 TCP** 承载副屏视频、触控注入、控制面与状态：
  - 推荐形态：**手机做服务端**（监听固定端口 `9500`），电脑主动连入；
  - 也支持 `adb forward` 回环：`tcp://127.0.0.1:9500`。
- 面板「连接与配对」视图提供二维码 / 手动地址 / 令牌与链路质量。

> **后续路线**：本文描述的是目标形态。当前已落地的是**有线（USB）主线**；
> **蓝牙键盘/手柄**与 **Wi‑Fi 控制面**的扩展点、实施顺序见
> [`docs/ROADMAP.md`](./docs/ROADMAP.md)。

---

## 七、如实降级（重要）

本项目的硬性原则：**不可用的能力必须明确标注，绝不伪装成功。**

- 副屏若当前无「支持运行时插拔的虚拟显示器驱动」，会走**后端 C 降级**：关屏只停推流，
  显示器仍留在系统中，面板以琥珀色横幅明确标注。
- 带宽不足时按优先级降级（触控上行 > 副屏视频 > 音频 > 摄像头），
  并以占用条可视化，日志记录「为谁降了什么」。

---

## 八、已知限制（发布前须知）

1. **Android 端未在本机编译验证**：需在有 Android SDK 的机器上执行 `gradlew.bat assembleDebug`。
2. **`apxdisp`（副屏）整体链接存在既有构建问题**：`pc/display/encode/mf_encoder.cpp` 在本机
   Windows SDK（10.0.22621）的 WRL `ComPtr<IMFSample>` 处报错，与本次无线传输改动无关。
   如需单独验证传输层，可只构建子目标：
   ```bat
   cmake --build build_display --config Release --target apxdisp_transport tcp_transport_test
   ```
3. **无线虚拟设备（声卡 / 摄像头）**仍处规划阶段，无线模式下这两类能力需要对接第三方
   虚拟设备驱动；无法满足时面板会如实标注，不做静默失败。

---

## 九、许可

本项目采用 **MIT** 许可，详见 [`LICENSE`](./LICENSE)。

---

## 十、开发约定

- **零第三方依赖 / 零 CDN**：电脑端与面板均不引入外部库，保证离线可用。
- **面板交互**：动作按钮统一 pending / success / error 三态；失败**就地**在卡片内展示原因，不弹窗打断。
- **传输统一**：USB / TCP / 无线调试统一收敛到 `pc/display/transport/i_transport.hpp` 的 `ITransport`。
- **共享契约先行**：`shared/include/apx/` 同时影响手机端与电脑端，改动需两端同步。
