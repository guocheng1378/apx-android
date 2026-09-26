# 全能外设（AllPeriph）

把手机变成电脑的外设：**触控板、键盘、多媒体键、麦克风与音响，以及副屏（屏幕投射）**。
电脑端一个桌面控制面板（托盘常驻）统一管理，命令行宿主 `apxhost` 供调试与脚本用。
支持**有线（USB，性能最高且手机同时充电）**与**无线（蓝牙 + 局域网 Wi‑Fi，免 root 免线缆）**两条主线。

- 电脑端：C++20（Windows），**零第三方依赖**，自写 winsock HTTP/1.1 + SSE。
- 控制面板：**原生 Win32 桌面面板**（`apxdesktop`，托盘常驻）。v117 起**不再提供 Web 控制台**。
- 手机端：Kotlin（Android），蓝牙 HID + 局域网 TCP + USB Gadget（有线模式）。

> 项目首页文档：[`README.md`](./README.md) ·
> **最终用户使用说明：[`docs/使用说明.md`](./docs/使用说明.md)** ·
> 架构说明：[`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) ·
> 协议定义：[`docs/PROTOCOL.md`](./docs/PROTOCOL.md) ·
> 真机实测记录：[`docs/REALDEVICE-NOTES.md`](./docs/REALDEVICE-NOTES.md) ·
> 路线图与接口预留：[`docs/ROADMAP.md`](./docs/ROADMAP.md) ·
> 更新日志：[`docs/CHANGELOG.md`](./docs/CHANGELOG.md)

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

在仓库 `scripts/` 目录下执行：

```bat
:: 一键构建三个产物（apxhost / apxdesktop / apxsetup）；加 nobuild 参数则只构建不启动
scripts\build.bat

:: 或只要命令行宿主（产物：build_host\Release\apxhost.exe）
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
cmake --build build_host --config Release --target apxhost

:: 免安装直接跑桌面端面板
build_host\Release\apxdesktop.exe
```

PC 端的图形界面就是上面的**桌面端面板 `apxdesktop`**（原生 Win32 UI、托盘常驻）。

命令行宿主 `apxhost serve` 只负责两件事：**9511 受控端**（让手机 / TV 能控本机）+ **全局热键**：

```bat
build_host\Release\apxhost.exe serve
```

> **v117 起 Web 控制台已整体下线**（按需求移除）：不再起本地 HTTP 服务、不再有
> `pc\host\web` 前端、也不会自动打开浏览器。原先 `apxhost ui / pair / scene`
> 这三个"开浏览器"的子命令已删除（执行会明确提示改用 `apxdesktop` 或 `apxhost serve`）。

---

## 二、目录结构

```text
全能外设/
├─ README.md                         本文件
├─ LICENSE                           MIT 许可证
├─ .gitignore                        构建产物与临时文件忽略规则
├─ .gitattributes                    换行统一（.bat=CRLF / .sh=LF）
│
├─ docs/                             设计与协议文档
│   ├─ ARCHITECTURE.md               双主线架构、通道表、Report ID 复用、降级代价
│   ├─ PROTOCOL.md                   帧格式、Mouse TLC、触控板载荷、控制面命令
│   ├─ REALDEVICE-NOTES.md           蓝牙 HID / Wi‑Fi 传输的真机实测结论
│   ├─ ROADMAP.md                    能力现状、蓝牙/Wi‑Fi 两条线的扩展点与实施顺序
│   ├─ CHANGELOG.md                  版本更新日志（v0.3.0 ~ 当前）
│   ├─ 使用说明.md                    最终用户使用说明
│   ├─ 依赖与安装.md                  依赖与安装说明
│   ├─ REQ-PTP.md                    PTP 需求文档
│   ├─ REQ-五路回报.md               五路回报需求文档
│   └─ 安卓手机USB外设共享技术深度调研报告.pdf  USB 方案调研文档
│
├─ shared/                           两端共享契约（core-proto 协议库）
│   ├─ CMakeLists.txt
│   ├─ README.md
│   ├─ include/apx/                  头文件（frame / hid_layout / hid_descriptor / clock / units）
│   ├─ src/                          实现文件（*.cpp）
│   └─ tests/
│
├─ pc/                               电脑端（Windows）
│   ├─ host/                         常驻宿主服务（9511 受控端 + 热键）+ 桌面面板源码
│   │   ├─ include/apxpc/            公共头文件
│   │   ├─ src/
│   │   │   ├─ host_main.cpp         命令行入口
│   │   │   ├─ desktop_main.cpp      桌面端面板入口
│   │   │   ├─ setup_main.cpp        安装/卸载
│   │   │   ├─ wireless/             Wi‑Fi 控制通道
│   │   │   ├─ ui/panel_win32.cpp    桌面端面板
│   │   │   └─ ...                   服务编排 / 配置 / 日志 / 工具
│   │   └─ tests/
│   ├─ display/                      副屏推流与触控注入
│   │   ├─ capture/ encode/ transport/ inject/ pipeline/ protocol/
│   │   └─ tests/
│   └─ tools/
│
├─ android/                          手机端（Kotlin）
│   └─ app/src/main/java/com/allperiph/
│       ├─ screen/ core/ gadget/ hid/ touchpad/
│       ├─ audio/ bt/ wireless/
│       └─ ui/
│
├─ scripts/                          构建与辅助脚本
│   ├─ build.bat                     一键构建 PC 三件套
│   ├─ build_apx.bat                 快速构建 apxdesktop
│   ├─ run.bat                       仅启动面板
│   ├─ build_display.bat             构建副屏模块
│   ├─ build_android_release.bat     构建 Android Release
│   ├─ adb_install_release.bat       ADB 安装 Release APK
│   ├─ apx_gadget.sh                 Linux Gadget 配置
│   ├─ aggregate.py                  数据汇总
│   ├─ hid_sensor_probe.py           传感器探测
│   ├─ make_icon.ps1                 图标生成
│   └─ verify_ms1.ps1               电源验证
│
├─ release/                          发布产物与说明
│   └─ README.md
│
├─ reports/                          生成的报告（L1~L5 + 汇总 + 状态）
│   ├─ L1~L5.md / .json             分级报告
│   ├─ SUMMARY.md                    汇总报告
│   ├─ STATUS.json                   项目状态
│   ├─ MAIN-INTERVENTIONS.md         主要干预记录
│   ├─ PROJECT-MINDMAP.md            项目脑图
│   └─ history/                      历史报告
│
└─ .github/workflows/               CI/CD
    ├─ apx-ci.yml                    主流程
    ├─ apx-gate.yml                  门禁
    ├─ apx-nightly.yml               每日构建
    ├─ apx-android.yml               Android 构建
    └─ apx-report.yml                报告生成
```

---

## 三、电脑端宿主服务（apxhost）

### 子命令

| 命令 | 说明 |
| --- | --- |
| `apxhost serve` | 启动常驻服务：**9511 受控端 + 全局热键 + 托盘**（v117 起无 Web 界面） |
| ~~`apxhost ui` / `pair` / `scene`~~ | 已随 Web 控制台下线；执行会提示改用 `apxdesktop` 或 `apxhost serve` |
| `apxhost list` | 枚举设备后退出 |
| `apxhost ctrl9511-connect <手机IP>[:端口] [秒数]` | 连入手机/电视 9511 统一控制面并注入输入（默认端口 9511） |
| `apxhost ctrl9511-remote <手机IP>[:端口] [秒数]` | 远程桌面接管（连入后回传本机屏幕） |
| `apxhost ctrl9511-serve [端口] [名称] [秒数]` | 本机作为 9511 服务端并被控（广播 APX1TV 供手机自动发现） |
| （无参数）`apxhost` | 进入交互式命令循环 |

日常使用建议直接跑桌面端 `apxdesktop.exe`（装包后是开始菜单里的「全能外设」），
它把上面的发现 / 建链 / 注入 / 托盘常驻包成了一个窗口。

### 配置文件

```text
%LOCALAPPDATA%\AllPeriph\config.json
```

包含 `schemaVersion`（当前 1）、HTTP 端口、开机自启、连接模式、触控板参数、热键绑定与场景绑定。
字段缺失会自动回落默认值，不会因旧配置崩溃。

---

## 四、构建

### 1) 电脑端宿主服务（必需）

```bat
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
cmake --build build_host --config Release --target apxhost
:: 产物：build_host\Release\apxhost.exe
```

### 2) 电脑端副屏模块

```bat
cmake -S pc/display -B build_display
cmake --build build_display --config Release
```

### 3) 共享协议库（可选）

```bat
cmake -S shared -B shared/build
cmake --build shared/build --config Release
```

### 4) 手机端（需要 Android SDK）

```bat
cd android
gradlew.bat assembleDebug
```

> 一键构建 PC 三件套：执行 `scripts/build.bat`。

---

## 五、测试（离线可跑，无需真机）

```bat
:: 带宽仲裁
cmake --build build_host --config Release --target arbiter_test
build_host\Release\arbiter_test.exe

:: TCP 传输
 cmake --build build_display --config Release --target tcp_transport_test
build_display\Release\tcp_transport_test.exe
```

---

## 六、两种连接模式

### 有线模式（USB）

手机通过 USB 复合设备被电脑免驱识别，性能最高，且手机同时充电。
**需要 Gadget 权限（通常需 root）**；部分机型请走无线模式（详见 [`docs/ROADMAP.md`](./docs/ROADMAP.md)）。

### 无线模式（蓝牙 + 局域网 Wi‑Fi，免 root 免线缆）

- **Wi‑Fi 控制（已落地）**：统一控制面 TCP `9511` + UDP `9501` 信标，电脑主动连入。
- **蓝牙 HID（部分）**：触控板与多媒体键已可用；**键盘与手柄待补**。
- **面板**：桌面端自动发现（监听 APX1TV 信标）或填 IP:9511。

---

## 七、如实降级

本项目原则：**不可用的能力必须明确标注，绝不伪装成功。**

- 手机端模块状态 RUNNING / DEGRADED / ERROR / STOPPED 如实上报。
- Wi‑Fi 控制链路口径与真实择路完全一致。
- 带宽不足时按优先级降级（触控上行 > 视频 > 音频）。

---

## 八、已知限制

1. Android 端编译验证通过，真机结论见 [`docs/REALDEVICE-NOTES.md`](./docs/REALDEVICE-NOTES.md)。
2. 副屏模块可独立构建，不在 `scripts/build.bat` 范围内。
3. Windows 无原生虚拟麦克风 API，需第三方虚拟声卡（如 VB-Cable）。

---

## 九、许可

MIT，详见 [`LICENSE`](./LICENSE)。

---

## 十、开发约定

- **零第三方依赖**：离线可用。
- **面板交互**：pending / success / error 三态，失败就地展示。
- **传输统一**：APX1 帧格式，`shared/` 与两端一致。
- **共享契约先行**：`shared/include/apx/` 改动需两端同步。
