# 全能外设（AllPeriph）

> **把手机变成电脑的外设** —— 触控板、键盘、多媒体键、麦克风与音响、副屏，以及双向互控互传。

<div align="center">

```
 📱 手机  ──── USB / Wi-Fi / 蓝牙 ────▶  💻 电脑
                    ◀─────────────────
 📱 手机  ◀──── Wi-Fi (9511) ──────▶  📱 另一台手机
 📱 手机  ◀──── Wi-Fi (9511) ──────▶  📺 TV
```

</div>

**有线（USB）** 性能最高且手机同时充电 · **无线（蓝牙 + Wi-Fi）** 免 root 免线缆

---

## ✨ 能做什么

| 场景 | 说明 |
|------|------|
| 🖱️ **触控板 + 键盘** | 手机当电脑的鼠标和键盘，双指滚动、手势右键 |
| ⌨️ **远程输入** | 手机打字，电脑即时上屏；支持增量输入和整段粘贴 |
| 🎵 **多媒体控制** | 音量、播放、暂停、切歌 |
| 🔊 **音箱 + 麦克风** | 电脑音频从手机播放（可选输出设备）；手机麦克风当电脑麦克风 |
| 🖥️ **副屏（扩展屏）** | 手机当电脑第二块屏，触摸直接操作（Windows 自研；mac 出路 = 自装 Sunshine + Moonlight，面板内检测 + 指引） |
| 📱 **手机互控** | 两台手机双向实时控制（被控端光标浮层可关）+ 互传文件 |
| 📺 **TV 被控** | 手机/电脑控制 Android TV（遥控器 + 触摸控制条 + 实时反馈） |
| 🎮 **游戏手柄** | 手机当游戏手柄：蓝牙/USB 免驱真手柄；PC 无驱动时自动降级为键鼠 |
| 🙋 **被控（手机 ← 电脑）** | 手机被电脑的鼠标键盘直接控制，需系统「无障碍」授权 |
| 🌐 **浏览器控制面板** | Windows / macOS 网页面板：连接状态、互传、设备选择、Sunshine 检测 |
| 🔌 **USB HID 外设** | 通过 USB 枚举为键盘、鼠标、声卡（需 root；仅 Windows） |

---

## ⚡ 5 分钟上手

### 方式一：无线（推荐，免 root）

1. **电脑**：
   - **Windows**：下载 `AllPeriph-vXXX-Setup.exe`，双击安装 → 开始菜单打开「全能外设」
   - **macOS（arm64）**：下载 `AllPeriph-vXXX-macos-arm64.zip` 解压，双击 **`AllPeriph.app`**
     （菜单栏有托盘图标：打开面板 / 退出全能外设），或终端 `./apxdesktop` / `./apxhost panel`
     → 自动打开浏览器控制面板
2. **手机**：安装 `AllPeriph-vXXX-app.apk` → 打开 App → 进入「触控板」页
3. **连接**：手机自动发现同一局域网内的电脑，点设备列表里的电脑名即可连接
4. **开搞**：滑动 = 移动鼠标，轻点 = 左键，双指 = 滚动，点「键盘」= 打字

### 方式二：USB 有线（需 root，性能最高）

1. 手机开启 USB 调试 → 插线连电脑
2. 手机端 App 开启「有线模式」
3. 电脑端 `apxdesktop.exe`（Windows）自动发现并连接（USB 有线仅 Windows）

### 方式三：手机被电脑控制

1. 手机 App → 触控板页 → 打开「被控模式」开关
2. **开启系统「无障碍服务」**（否则只能看不能操作）
3. 电脑端选设备列表里的手机名 → 开始控制

> **macOS 首次打开（未签名应用）**：系统设置 → 隐私与安全性 → 底部「仍要打开」，
> 或执行 `xattr -dr com.apple.quarantine ./apxhost ./apxdesktop`。
> **音箱**首次使用需授权「系统音频录制」（需 macOS 14.2+）。

---

## 📦 产物一览

| 平台 | 文件 | 说明 |
|------|------|------|
| 📱 手机 | `AllPeriph-vXXX-app.apk` | 手机端 App（遥控器 / USB 外设 / 被控 / 副屏） |
| 📺 TV | `AllPeriph-vXXX-tv.apk` | Android TV 被控端 |
| 💻 Windows | `AllPeriph-vXXX-Setup.exe` | 安装包（桌面面板 + 宿主 + 前端资源，一键装） |
| 💻 Windows | `AllPeriph-vXXX-portable.zip` | 便携版（解压即用，双击 apxdesktop.exe） |
| 🍎 macOS (arm64) | `AllPeriph-vXXX-macos-arm64.zip` | **测试版**：`apxdesktop` 桌面壳 + `apxhost` + `web/` 浏览器面板（未签名，首次打开需放行；包内附 README） |

> `vXXX` 为发版版本号（见 [Releases](../../releases)）。
> 所有 PC exe **未做代码签名**：Windows 会被 SmartScreen 拦（更多信息 → 仍要运行）；macOS 按上文放行。
> 所有 APK 使用自签证书，**不能上架 Google Play**。

---

## 🆕 最近更新

每个 Release 的正文顶部都有「改了什么」（由 CI 从 [CHANGELOG](docs/CHANGELOG.md) 对应版本段自动提取）。最近几版：

| 版本 | 主要内容 |
|------|------|
| v223.10 | 遥控器页**光标浮层开关**（手机控手机/TV 时被控端蓝点可关，新协议 0x32） |
| v223.9 | 外部 patch 落地：PCM 设备号运行时解析、面板音箱/麦克风设备下拉接真枚举、快捷键模板色进颜色令牌 |
| v223.8 | mac 副屏出路：面板内 Sunshine 检测 + 指引（不内嵌 Moonlight，GPLv3 与 MIT 冲突） |
| v223.7 | mac 托盘（菜单栏图标 + 退出）、USB 走 IOKit 枚举、9511「已在运行」检测、Edge 互传文件选择修复 |
| v223.6 | 手柄免驱降级、mac 输入授权横幅、面板三开关改真实状态（0x31）、EventBus 主线程修复 |

---

## 🔧 源码构建

### 电脑端（Windows）

> 前提：Windows 10/11 + Visual Studio（C++ 桌面开发）+ CMake ≥ 3.20

```bat
:: 一键构建三件套（apxhost / apxdesktop / apxsetup）
scripts\build.bat

:: 或手动
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=ON
cmake --build build_host --config Release --target apxhost apxdesktop apxsetup
```

### 电脑端（macOS，arm64）

> 前提：Xcode Command Line Tools + CMake ≥ 3.20 + Node 20（前端）

```bash
cmake -S pc/host -B build_host -DCMAKE_BUILD_TYPE=Release -DAPXPC_BUILD_UI=OFF
cmake --build build_host --target apxhost apxdesktop

# 前端 dist（放到可执行文件旁 web/ 目录，浏览器面板依赖）
cd pc/web && npm install && npm run build
```

> 也可直接用 CI（macos.yml）的 artifact / Release 资产，无需本地构建。

### 手机端（Android）

> 前提：Android SDK + JDK 17

```bat
cd android
gradlew assembleRelease
```

### 副屏模块（可选，仅 Windows）

```bat
cmake -S pc/display -B build_display
cmake --build build_display --config Release
```

---

## 📁 项目结构

```
全能外设/
├─ shared/              两端共享协议库（APX1 帧格式）
├─ pc/                  电脑端（C++20，零第三方依赖）
│  ├─ host/             常驻宿主 + 桌面面板源码
│  └─ display/          副屏推流与触控注入
├─ android/             手机端 + TV 端（Kotlin）
│  ├─ app/              手机端 App
│  └─ tv/               TV 端 App
├─ scripts/             构建与辅助脚本
├─ docs/                设计与协议文档
├─ release/             发布产物
├─ reports/             测试报告
└─ .github/workflows/   CI/CD
```

---

## 🔌 端口速览

| 端口 | 协议 | 用途 |
|------|------|------|
| 9511 | TCP | 统一控制面（手机/TV/PC 互控） |
| 9502 | TCP | 媒体通道（副屏视频下行 + 触控上行 + 音频） |
| 9501 | UDP | 局域网发现信标（广播 `APX1TV` / `APX1PC` / `APX1PH`） |
| 9512 | TCP | 文件传输 |

---

## 📖 完整文档

| 文档 | 内容 |
|------|------|
| [docs/使用说明.md](docs/使用说明.md) | **最终用户使用说明**（Windows / macOS / Android 三端） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 双主线架构、通道表、降级策略 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | APX1 帧格式、控制面命令 |
| [docs/REALDEVICE-NOTES.md](docs/REALDEVICE-NOTES.md) | 真机实测结论 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 能力现状与扩展点 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 版本更新日志 |
| [docs/CLIENTS.md](docs/CLIENTS.md) | 各端平台客户端与功能总表 |

---

## 🧪 测试

```bat
:: 协议层单元测试（CI 自动运行）
gradlew :app:testReleaseUnitTest

:: 带宽仲裁测试
cmake --build build_host --config Release --target arbiter_test
build_host\Release\arbiter_test.exe
```

---

## ⚠️ 已知限制

1. **蓝牙键盘/手柄**已交付（蓝牙优先真手柄；PC 无驱动时免驱降级为键鼠），
   剩下的是**真机配对与实测**（见 [ROADMAP.md](docs/ROADMAP.md)）
2. **Windows 无原生虚拟麦克风 API**，需第三方虚拟声卡（如 VB-Cable）
3. **副屏握手**仅代码层确认，建议首次使用真机验证
4. **被控设备显示名**已用真实机型名；发现信标前缀仍为 `APX1TV`/`APX1PC`/`APX1PH`
   （刻意不改 —— 旧版 PC 端只认 `APX1TV`，换前缀要两端同时升级）
5. **macOS 平台边界**：副屏不支持（虚拟显示器仅 Windows）—— 出路是自装
   [Sunshine](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2getting__started.html)
   + Moonlight（Moonlight 客户端 GPLv3 与本项目 MIT 冲突，不内嵌；面板「副屏」卡有检测与指引）；
   音箱走 CoreAudio Process Tap（需 macOS 14.2+ 与「系统音频录制」授权，DRM 保护内容可能采集为静音）；
   USB 有线模式与全局热键暂不可用（USB 枚举自 v223.7 走 IOKit，面板可看到设备）；
   浏览器面板发送本机文件需**从面板入口打开**（地址带 `?host=1`，v223.7 起 Safari/Edge 已支持）

---

## 📋 开发约定

- **界面层：Compose + Miuix**（2026-11-01 起）。手机端 `android/app` 与电视端 `android/tv` 的界面
  统一用 Jetpack Compose 渲染，组件与观感来自 Miuix（`top.yukonga.miuix.kmp` 0.9.4：
  `miuix-ui` / `miuix-blur` / `miuix-preference` / `miuix-icons`），顶栏渐进模糊用 Haze。
  逻辑层（`core/`、`gadget/`、`shared/` 的 C++、`pc/` 全部）仍**零第三方依赖**，
  界面层新增的依赖已收敛在上一节列出的 6 个坐标里，不得再扩。
- **可离线构建**：所有依赖都走 Maven 本地/代理缓存，构建过程不需要联网授权；
  新增依赖必须同时说明「离线可用性」与许可证（现有一律 Apache-2.0）。
- **如实降级**：不可用的能力明确标注，绝不伪装成功
- **共享契约先行**：`shared/` 改动需两端同步
- **协议一致性**：CI 自动校验跨端常量（`scripts/check_protocol.py`）

---

## 📜 许可

[MIT](LICENSE)
