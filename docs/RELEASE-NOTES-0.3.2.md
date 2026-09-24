# 全能外设 v0.3.2 发布说明

> 维护性版本：移除「摄像头（UVC 免驱 webcam / Wi‑Fi JPEG 上行）」模块的**全部残留**代码、
> UI 引用、权限与文档，并修正此前夸大该能力的说明。
> 详细用法见 [`../README.md`](../README.md) 与 [`使用说明.md`](./使用说明.md)。

---

## 一、为什么要发这个版本

摄像头模块（手机相机 → PC 预览 / 虚拟摄像头）长期处于**未实现 / 禁用**状态：

- 代码层面仅以 `android/app/src/disabled/camera/` 死代码与文档中的"计划"形态存在；
- [`docs/ROADMAP.md`](./ROADMAP.md) 已明确将其标注为「未开始」；
- 但 `README.md` 与 `RELEASE-NOTES-0.3.1` 曾把它描述为"已落地、面板四张媒体卡可用、实时预览"，
  与真实代码状态不符。

本项目的原则是"**不可用的能力必须明确标注，绝不伪装成功**"。本版本据此把摄像头相关的
代码与文案一并清除，并修正文档，使代码库与文档如实反映当前能力。

---

## 二、移除了什么

### 安卓端（`android/app`）

| 类别 | 文件 | 改动 |
| --- | --- | --- |
| 权限 | `AndroidManifest.xml` | 删除 `CAMERA` 权限声明 |
| 权限 | `ui/MainActivity.kt` | 移除摄像头权限申请逻辑与 `REQ_CAMERA` 常量 |
| JNI | `cpp/apx_jni.cpp` | 删除整段 UVC gadget 实现（V4L2 头引用、`#if/#endif` 包裹、`UvcOutput` 4 个导出函数及 `gUvcFd` / `kUvcWidth` / `kUvcHeight` / `uvcToLower` / `isUvcOutputNode` 等） |
| 上行通道 | `wireless/TcpMediaChannel.kt` | 删除摄像头背压 / 最新帧覆盖逻辑、状态文本"摄像头丢帧"、死常量 `CAMERA_DROP_THRESHOLD` |
| 状态文案 | `ui/AgentController.kt` | 移除 `ModuleId.CAMERA` 状态文案 |
| 注释 | `core/MediaOut.kt` | 修正过时注释（去"摄像头 6 / Camera2"） |
| 布局 | `res/layout/page_status.xml` | 移除摄像头描述 TextView |
| 协议常量 | `core/ApxFrame.kt` / `core/ApxStreams.kt` | 移除摄像头流相关定义（`STREAM_CAMERA` 等） |
| 模块标识 | `core/Module.kt` / `wireless/WirelessModule.kt` | 移除摄像头模块位与索引映射 |
| Gadget | `gadget/ConfigFsLayout.kt` / `gadget/GadgetManager.kt` | 移除 UVC configfs 树与枚举 |
| 死代码 | `src/disabled/camera/*` 与 `src/main/.../camera/*` | 删除 `UvcOutput` / `CameraPrefs` / `CameraModule` / `CameraActivity` |

> 保留项：`VirtualDeviceProbe` 的"虚拟摄像头"检测（探测 `com.example.virtualcamera` 等模拟器特征）
> 属于独立的环境探针功能，与已删除的 UVC webcam 模块无关，予以保留。

### PC 端（`pc/host` 与 `pc/display`）

- **Web 前端**（`pc/host/web`）：摄像头视图此前已不存在；清理 `devices.js` / `api.js` 两处过时中文注释。
- **原生面板 / 媒体层**：`panel_win32.cpp`、`host_main.cpp`、`media_session.hpp`、`screen_push.hpp`、
  `CMakeLists.txt`、`pipeline.{cpp,hpp}` 清理摄像头卡片 / 预览 / 通道的注释残留。
- **协议 / 信令**：经核查，`shared/include/apx/sensors_id.h` 的 `ModuleBit` 枚举与
  `shared/include/apx/hid_layout.h` 的 `VendorCommand` 枚举**均不含任何摄像头开关 / 分辨率 / 码率命令字段**
  （无 `kModuleCamera`、无摄像头 opcode）；安卓 `ModuleId` 亦无 `CAMERA`。即摄像头从未进入活动协议层。

### 文档

- `README.md`：「当前阶段」去除摄像头"已落地 / 四张媒体卡 / 实时预览"等不实描述（媒体卡更正为
  三张：副屏 / 音箱 / 麦克风）；目录树移除已删除的 `camera/`；config 与带宽降级段落移除摄像头引用；
  「无线虚拟设备」段落移除摄像头子项。
- `RELEASE-NOTES-0.3.1` 中"摄像头全面升级"等段落现已失效（历史记录保留，但当前版本能力以本说明为准）。

---

## 三、构建与验证

| 目标 | 命令 | 结果 |
| --- | --- | --- |
| PC 桌面端 | `build_apx.bat` | `BUILD_OK`（仅预存 warning：`C4819` 代码页、`C4018` 有符号/无符号比较，均无编译错误） |
| 安卓端 | `gradle assembleDebug` | `BUILD SUCCESSFUL`（JNI/CMake 多 ABI 通过，仅预存 warning） |

两项构建均确认摄像头代码移除后**无悬空引用、无编译错误**。

---

## 四、兼容性

- **无协议版本变更**：摄像头从未定义活动协议字段，故协议版本号不变。
- **无配置 / breaking change**：配置 schema 中本就无 `camera*` 字段；覆盖安装即可，旧配置自动回落默认值。
- **虚拟设备探测保留**：`VirtualDeviceProbe` 的模拟器摄像头检测属环境探针，不在本次移除范围。

---

*MIT 开源 · 零第三方依赖 · 所有通信仅限局域网*
