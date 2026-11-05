# M7 媒体入站 spike（M7-a：调研 + 选型）

> 2026-11-05。范围：侦察 + 选型方案；**不含代码**（M7-b 手机采集、M7-c PC 收流与
> 虚拟摄像头出口是后续批次）。
> 真机实测**未执行**（云端无摄像头、无微信/会议端）——步骤在 §4，需真机按清单跑；
> 没跑过的一律标「未实测」，不假装完成。
> 红线（沿 M6 spike）：不发版不打 tag、不内嵌 Moonlight 客户端；第三方件只准落
> `pc/`（GPL-3.0），`app/` 保持 MIT（LICENSES.md 目录级双许可，#156 已落）。

## 1. 侦察结论（仓库现状）

| 证据 | 位置 | 说明 |
|---|---|---|
| 协议槽位已预留 | `docs/PROTOCOL.md` | `streamId=6 camera`，手机 → PC，载荷 = **单帧 JPEG（不分片）** |
| PC 接收槽已预留 | `pc/host/include/apxpc/media/media_session.hpp` | `6 camera 本端接收 —— JPEG 帧`；`FrameHandler` 回调已含该流 |
| 手机上行已有同构实现 | `android/app/src/main/java/com/allperiph/core/MediaOut.kt` + `audio/WirelessAudioModule.kt` | `MediaOut.mic()`（只入队不碰 socket，网络线程由承载层管）；`MediaOut.camera()` **待加**，同队列换 streamId 即可 |
| 媒体通道本体 | `shared/media/MediaChannel.kt`（两端字节级同构）+ `wireless/TcpMediaChannel` | 9502 TCP 收流/分发/组帧现成，收流零新增协议 |
| 手机摄像头代码 | v0.3.2 已**全部移除**（`app/src/disabled/camera/` 不存在，`MAIN-INTERVENTIONS.md` 记了整包删除清单） | 从零重做；用户意向「用别人的代码」→ CameraX（Apache-2.0，宽松许可，落 `app/` 不破 MIT） |
| PC 解码管线 | `pc/host/src/display/display_wgc.cpp` + `media/screen_push.cpp` | 这是 **PC→手机** 抓屏方向（WGC），**不可直接复用为收流**；但 GDI+/WIC 解 JPEG 仓内已有先例（`ball/`、`file_panel`），显示窗解码走同一栈 |
| 摄像头能力总表 | `docs/ROADMAP.md` §2.x | 摄像头行：`待做：Camera2 JPEG → MediaOut.camera()；PC 侧预览 ❌ 未开始`（台账与本文一致） |

**缺口一句话**：三端协议槽位全在、收流通道现成，缺的只有①手机 CameraX 采集推 JPEG、
②PC 收帧解码显示窗、③PC 虚拟摄像头出口。

## 2. 选型方案

### 2.1 PC 虚拟摄像头（本 spike 的核心决策）

| 方案 | 依据 | 优点 | 代价 | 结论 |
|---|---|---|---|---| 
| **A. `MFCreateVirtualCamera`（主选）** | 官方 Win32 API，`mfvirtualcamera.h`，**最低 Windows Build 22000（Win11）**；`MFVirtualCameraAccess_CurrentUser` 免管理员；自定义 Media Source（CLSID 注册进 `sourceId`）由本进程喂帧，**无第三方驱动分发** | 系统自带、无许可问题、不碰驱动签名、面板开关即注册/`Remove` | 仅 Win11+；Win10 不可用 | ✅ 首版走它 |
| B. OBS Virtual Camera 运行时 | OBS 独立分发的 VCAM 模块 | Win7+ 全覆盖 | 要随包分发/要求用户装 OBS 运行时；许可与版本耦合重 | 二期 Win10 降级再评 |
| C. DirectShow 源过滤器（smourier/VCamSample 一类） | 老 COM 架构，注册 DLL | 兼容老系统 | 需 COM 注册（多为管理员）、架构老、维护差 | ❌ 不选 |

**决策：首版 A**。理由：官方 API 零分发零驱动，`pc/` 里纯代码接入；Win10 用户的降级
（B 或预览窗替代）留二期，**不阻塞首版交付**。`MFCreateVirtualCamera` 要点（文档已读）：
- `MFVirtualCameraLifetime_Session`：进程退出即消失（首版选它，不留残留）；`System` 才跨重启
- `categories` 传 nullptr = 注册进 `KSCATEGORY_VIDEO_CAMERA/VIDEO/CAPTURE`
- 受摄像头隐私开关管控，隐私拒绝时返回 `E_ACCESSDENIED`
- 喂帧：实现自定义 Media Source + `IMFVirtualCamera`，相机 App 打开时由本进程供 sample（帧从收流队列取 JPEG → 解码 → YUV/NV12 sample）

### 2.2 收流与显示窗

- **收流**：`media_session` 注册 `streamId=6` 的 `FrameHandler`（PC 收 JPEG 单帧，不分片、无重组），改动 ≈ 一个回调
- **解码**：GDI+ `Image`（仓内既有栈，`ball/`/`file_panel` 先例）首版够用；后续若 30fps 压力大再换 WIC/MF 解码（预留，不首版做）
- **显示窗**：新建独立顶层窗口（置顶 + 可拖 + 关闭即停），复用 `file_panel` 的窗口骨架；球/托盘不接（重交互按 D1 原则不上球）
- **降级**：帧间隔 > 3s 显示「等待手机画面」占位，不黑屏假装有流

### 2.3 手机采集

- **CameraX**（`androidx.camera:camera-camera2` + `camera-lifecycle` + `camera-view`，Apache-2.0，落 `app/` 不破 MIT）：JPEG ImageCapture / ImageAnalysis 走 YUV→JPEG
- 输出 → `MediaOut.camera()`（新增，仿 `mic()`：入队 + streamId=6）
- 采样策略首版 **10fps / 720p / JPEG q≈80**（协议就是单帧 JPEG，不做 H.264——流控/降级都简单）；分辨率/帧率进 `config.json`
- 权限：`CAMERA` 六项权限 L3 已确认生效声明；UVC configfs 树 v0.3.2 已删、枚举保留默认关（不碰）

### 2.4 虚拟摄像头消费端（验证目标）

微信 PC 版 / 腾讯会议选到「AllPeriph Virtual Camera」并出画 = 通过。演示链路：
手机 CameraX → 9502 → PC 收流 → 显示窗 + 虚拟摄像头 → 微信视频。

## 3. 批次切法（默认，照 M6 惯例）

| 批 | 内容 | 依赖 |
|---|---|---|
| **M7-a** | 本文档（侦察 + 选型，不过度承诺） | — |
| **M7-b** | 手机侧：CameraX 采集 UI（独立 Activity）+ `MediaOut.camera()` + config 档位 | M7-a |
| **M7-c** | PC 侧：`streamId=6` 回调 → GDI+ 解码 → 显示窗；`MFCreateVirtualCamera` 注册/喂帧/移除 + 面板开关 | M7-b |
| M7-d（二期） | Win10 降级方案（OBS VCAM / 纯预览窗）+ 10fps→可调帧率压测 | M7-c |

## 4. 真机实测步骤（未执行）

- [ ] Win11 PC 装包 → 面板出现「摄像头」开关
- [ ] 手机授权 CameraX → PC 显示窗出画，帧率与 `config.json` 一致
- [ ] 断开手机 → 显示窗 3s 内转占位文案（降级生效）
- [ ] 微信 PC 版 / 腾讯会议设备列表选到虚拟摄像头 → 对端出画
- [ ] 进程退出 → 虚拟摄像头从设备列表消失（Session 生命周期）
- [ ] 隐私开关拒绝时面板报错文案清晰（`E_ACCESSDENIED` 路径）

## 5. 未决 / 风险

- `MFCreateVirtualCamera` 的 Media Source 供帧是本进程 COM 激活，需验证**微信激活时进程
  是否被要求提升**（CurrentUser 模式理论上不用，实测定论）
- 10fps JPEG 720p 在 9502 链路上的实测带宽与延迟（局域网预计 <2Mbps，待测）
- Win10 覆盖率影响（二期），首版文档如实标注「虚拟摄像头需 Win11」
