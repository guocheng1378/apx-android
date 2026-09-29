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
| 🔊 **音箱 + 麦克风** | 电脑音频从手机播放；手机麦克风当电脑麦克风 |
| 🖥️ **副屏（扩展屏）** | 手机当电脑第二块屏，触摸直接操作 |
| 📱 **手机互控** | 两台手机双向实时控制 + 互传文件 |
| 📺 **TV 被控** | 手机/电脑控制 Android TV |
| 🔌 **USB HID 外设** | 通过 USB 枚举为键盘、鼠标、声卡（需 root） |

---

## ⚡ 5 分钟上手

### 方式一：无线（推荐，免 root）

1. **电脑**：下载 `apxsetup.exe`，双击安装 → 开始菜单打开「全能外设」
2. **手机**：安装 `app-release.apk` → 打开 App → 进入「触控板」页
3. **连接**：手机自动发现同一局域网内的电脑，点设备列表里的电脑名即可连接
4. **开搞**：滑动 = 移动鼠标，轻点 = 左键，双指 = 滚动，点「键盘」= 打字

### 方式二：USB 有线（需 root，性能最高）

1. 手机开启 USB 调试 → 插线连电脑
2. 手机端 App 开启「有线模式」
3. 电脑端 `apxdesktop.exe` 自动发现并连接

### 方式三：手机被电脑控制

1. 手机 App → 触控板页 → 打开「被控模式」开关
2. **开启系统「无障碍服务」**（否则只能看不能操作）
3. 电脑端选设备列表里的手机名 → 开始控制

---

## 📦 产物一览

| 平台 | 文件 | 说明 |
|------|------|------|
| 📱 手机 | `AllPeriph-v186-app-release.apk` | 手机端 App（遥控器 / USB 外设 / 被控 / 副屏） |
| 📺 TV | `AllPeriph-v186-tv-release.apk` | Android TV 被控端 |
| 💻 电脑 | `AllPeriph-v186-apxdesktop.exe` | 桌面面板（设备发现 / 控制 / 副屏） |
| 💻 电脑 | `AllPeriph-v186-apxhost.exe` | 常驻服务（9511 受控端 + 热键） |
| 💻 电脑 | `AllPeriph-v186-apxsetup.exe` | 安装包（一键装到开始菜单） |

> 所有 PC exe **未做 Windows Authenticode 代码签名**，外发会被 SmartScreen 拦截。
> 所有 APK 使用自签证书，**不能上架 Google Play**。

---

## 🔧 源码构建

### 电脑端（Windows）

> 前提：Windows 10/11 + Visual Studio 2022（C++ 桌面开发）+ CMake ≥ 3.20

```bat
:: 一键构建三件套（apxhost / apxdesktop / apxsetup）
scripts\build.bat

:: 或手动
cmake -S pc/host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
cmake --build build_host --config Release --target apxhost apxdesktop apxsetup
```

### 手机端（Android）

> 前提：Android SDK + JDK 17

```bat
cd android
gradlew assembleRelease
```

### 副屏模块（可选）

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
| 9501 | UDP | 局域网发现信标（广播 `APX1TV`） |
| 9512 | TCP | 文件传输 |

---

## 📖 完整文档

| 文档 | 内容 |
|------|------|
| [docs/使用说明.md](docs/使用说明.md) | **最终用户使用说明** |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 双主线架构、通道表、降级策略 |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | APX1 帧格式、控制面命令 |
| [docs/REALDEVICE-NOTES.md](docs/REALDEVICE-NOTES.md) | 真机实测结论 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 能力现状与扩展点 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 版本更新日志 |

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

1. **蓝牙键盘/手柄**尚未补全（见 [ROADMAP.md](docs/ROADMAP.md)）
2. **Windows 无原生虚拟麦克风 API**，需第三方虚拟声卡（如 VB-Cable）
3. **副屏握手**仅代码层确认，建议首次使用真机验证
4. **被控手机显示名**为 `APX1TV`，不够直观（可后续优化为真实机型名）

---

## 📋 开发约定

- **零第三方依赖**：离线可用，只依赖 framework API
- **如实降级**：不可用的能力明确标注，绝不伪装成功
- **共享契约先行**：`shared/` 改动需两端同步
- **协议一致性**：CI 自动校验跨端常量（`scripts/check_protocol.py`）

---

## 📜 许可

[MIT](LICENSE)