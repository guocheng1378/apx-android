# 全能外设 · 总体架构

> 目标：一部 Android 手机通过一根 **USB 3.0（SuperSpeed 5Gbps）** 线缆，把机身内的硬件元器件虚拟化成 PC 的**本机设备**。
> 设计信条：**凡是能映射为 USB 标准 Class / HID 标准 Usage 的，一律走标准路径，PC 端零自研驱动。**

> ⚠️ **文档状态（先读这条，2026-10-23 按源码同步）**：本文是**设计真源**，包含比实现更广的目标形态。
> - **副屏**：镜像形态**已交付并真机验证**（`ROADMAP.md` §2.3，`pc/display` 在 CI 构建）；
>   本文所称**终止**的仅是「IddCx 虚拟显示器扩展屏」方案（§5）。
> - **摄像头**：代码已随 v0.3.2 **全部移除**（`app/src/disabled/camera/` 已不存在）；
>   §2.2 阶段三与 §7 的摄像头行仅作历史设计留档。
> - **蓝牙**：`reportKeyboard`（rid 2）/ `reportGamepad`（rid 4）与 native 描述符均已实现，
>   键盘 2026-10-23 接入出口。
> 当前真实能力与实施顺序以 [`ROADMAP.md`](./ROADMAP.md) 为准。

---

## 1. 架构总览

```
┌──────────────────────── Android 端（root + ConfigFS）────────────────────────┐
│  Hardware Agent（前台 Service，常驻）                                          │
│  ┌──────────┬──────────┬──────────┬──────────┬────────────────┐  ┌────────┐ │
│  │ Camera   │ Audio    │ ScreenClient                      │  │ HID    │ │
│  │ (UVC)    │ (UAC2)   │ (解码副屏视频)                    │  │ Device │ │
│  └────┬─────┴────┬─────┴────┬─────┴─────────────┘           │  └───┬────┘ │
│       │          │          │                               │      │      │
│  ┌────▼──────────▼──────────▼───────────────────────────────┐  ┌────▼────┐ │
│  │  HID Bridge（多 TLC，写 /dev/hidg0）                   │  │ Bulk    │ │
│  │  鼠标/键盘/多媒体键/触控板/数位屏/状态               │  │ Channel │ │
│  └──────────────────────┬──────────────────────────────────┘  │ (AOA/   │ │
│                         │                                     │  NCM)   │ │
│  ┌──────────────────────▼──────────────────────────────────┐  └────┬────┘ │
│  │  Gadget Manager（ConfigFS 生命周期：挂载/卸载/自愈）   │       │      │
│  └──────────────────────┬──────────────────────────────────┘       │      │
└─────────────────────────┼──────────────────────────────────────────┼──────┘
                          │          USB 3.0 SuperSpeed               │
┌─────────────────────────▼──────────────────────────────────────────▼──────┐
│  PC 端（Windows 优先 / Linux 次之）                                        │
│  ┌──────────────────────────────────────────┐  ┌────────────────────────┐ │
│  │ OS 原生集成层（零自研驱动）              │  │ 自研模块               │ │
│  │ HID Digitizer → 原生触摸/压感笔          │  │ IddCx 虚拟显示器      │ │
│  │ HID Consumer → 多媒体键                   │  │ DDA 抓屏 + 硬编 + 推流│ │
│  │ HID Battery(0x85) → 电源状态              │  │ 触控注入 (SendInput)  │ │
│  │ UVC → 系统相机   UAC2 → 系统声卡         │  ├────────────────────────┤ │
│  │ NCM → 网卡       MTP → 便携设备          │  │ Host Service + 控制面板│ │
│  └──────────────────────────────────────────┘  └────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 双主线：有线与无线

系统有**两条并列主线**，PC 面板提供「有线模式 / 无线模式」切换，两条都完整跑通全部功能。
无线模式**无需 root**，是正式交付形态之一，**不是降级兜底**。

| | **有线模式**（USB Gadget） | **无线模式**（蓝牙 + 局域网） |
|---|---|---|
| 手机端前提 | **root + ConfigFS 复合设备** | **免 root**，普通应用即可 |
| 传输 | `f_hid` / `f_uac2` / `f_uvc` / `f_ncm` + bulk | 蓝牙 HID（输入类）+ WiFi TCP（高带宽流与控制面） |
| PC 端 | **零驱动**，OS 原生枚举全部设备 | 输入类走蓝牙（系统原生免驱）；高带宽流走 TCP；**摄像头/音频需虚拟设备** |
| 优势 | 性能最高、延迟最低，且**手机同时充电** | 免 root、免线缆；绕开 UDC 抢占 / gadget 名额 / FFS 限制 / 重启等全部 USB 坑 |
| 代价 | 需 root；受内核 gadget 名额与 UDC 抢占限制 | 延迟高于 USB；**手机耗电明显**（有线时在充电，无线时纯耗电） |

### 2.1 无线模式的三个通道与分工

1. **WiFi 局域网 TCP**（**已实现，真机验证通过**）—— 承载**输入类与控制面**：
   触控板 / 键盘 / 多媒体键（`streamId=3` 控制帧，PC 端 `SendInput` 注入）与状态上报。
   历史实现为手机服务端 + UDP 信标（`TcpControlChannel`/`WirelessBeacon`，已随 v1.11 移入
   `.attic/wireless_removed_v1.11/`）；现行上行链路 = 受控目标 `wireless/ControlTarget` +
   `core/Uplink.kt` 择路，落点与验证证据见 `docs/ROADMAP.md` §2.2 核对注。
2. **蓝牙 HID**（`BluetoothHidDevice`，API 28+）—— 已承载**触控板与多媒体键**，PC 端零驱动
   （标准协议）。使用独立的裁剪版描述符（仅 3 个 TLC，实测 181 字节，见 `PROTOCOL.md` §2.11）。
   ~~键盘与手柄待补~~ **已补全**：`reportKeyboard`（rid 2）与 `reportGamepad`（rid 4）均在（v184），键盘 2026-10-23 接入出口。**可与 USB 复合设备并存**。
   注意：描述符一变，PC 端**旧配对记录必须删除重连**，否则 Windows 会拿缓存描述符解析导致错位。
3. **无线 ADB** —— 开发期联调与兜底（`adb tcpip` + `adb forward`，即现有 `tcp://127.0.0.1:9500` 模式）。
   需开 USB 调试，定位为开发通道而非产品形态。

### 2.2 ⚠️ 无线模式的驱动代价

| 功能 | 无线实现 | 是否需新驱动 | 现状 |
|---|---|---|---|
| 触控板 / 多媒体键 | 蓝牙 HID 或 WiFi TCP | **否** | ✅ 双路可用 |
| 键盘 | WiFi TCP（PC 端 `SendInput`） / 蓝牙 HID | **否** | ✅ 双路可用（蓝牙 2026-10-23 接入） |
| 控制面 / 状态 | WiFi TCP | **否** | ✅ |
| 副屏（镜像）+ 触摸回传 | TCP 媒体通道 + `SendInput` | **否** | ✅ 已交付并真机验证（ROADMAP §2.3，v0.3.0 起） |
| 扩展屏（IddCx 虚拟显示器） | IddCx 驱动 | 需驱动 | ❌ 已决策终止（仅此方案，镜像不受影响） |
| **摄像头（供系统其它程序）** | TCP + 虚拟摄像头 | **否**（用户态 Media Source DLL） | ❌ 代码已随 v0.3.2 全部移除；原路线留档见下方阶段三 |
| **音频（无线）** | TCP + 虚拟声卡 | **是**（可借第三方如 VB-Cable） | 规划中；**有线 UAC2 已可用** |

**落地节奏**：

- **阶段一（免驱闭环，优先交付）**：TCP 覆盖输入类与控制面（**已交付并真机验证**）+
  蓝牙 HID 补全键盘/手柄 → 无线模式即可用，**零新驱动**
- **阶段二（借力第三方）**：**音频**优先对接现成第三方虚拟声卡
  （Windows 无原生虚拟麦克风 API）
- **阶段三（自研驱动）**：**摄像头不必等第三方** —— Windows 11（22000+）的
  `MFCreateVirtualCamera` 走**用户态 Media Source DLL**，**不需要内核驱动、也不需要
  驱动签名**（微软官方 VirtualCamera 示例即此路线）；代价是需要 Win11 与写 `HKLM`
  的 COM 注册，即需要管理员权限

**任一阶段，不可用能力必须在面板如实标注，禁止静默失败**。

---

## 3. 仓库结构（严格按目录分权）

```
全能外设/
├─ docs/
│   ├─ ARCHITECTURE.md          ← 本文件（唯一架构真源）
│   └─ PROTOCOL.md              ← 协议真源（帧格式 / HID 报告布局 / 时钟同步）
├─ shared/                      ← C++17 无依赖公共代码（Android NDK 与 PC MSVC 共用）
│   ├─ include/apx/{frame,hid_layout,clock}.h
│   └─ src/*.cpp
├─ android/
│   ├─ app/src/main/java/com/allperiph/
│   │   ├─ core/         协议绑定、时钟、日志、事件总线、APX1 组帧、TCP 控制面出口
│   │   ├─ gadget/       【M2】ConfigFS 复合设备管理器
│   │   ├─ audio/        【M8】UAC2 双向音频
│   │   ├─ bt/           蓝牙 HID 设备（鼠标 / 多媒体）
│   │   ├─ hid/          HID 报告描述符 + 键码映射
│   │   ├─ touchpad/     触控板（相对鼠标手势）
│   │   ├─ wireless/     受控目标/TV 控制（ControlTarget·TvControllerClient·TvDiscovery；旧9500服务端已移 .attic）
│   │   └─ ui/           【M6】App UI 与服务编排
│   ├─ app/src/disabled/ 历史停用区（**不参与编译**；camera/ 已随 v0.3.2 整体移除，screen/ 现位于主源码树）
│   └─ app/src/main/cpp/ NDK 桥（复用 shared/）
├─ pc/
│   ├─ display/          【M9】副屏推流与注入（镜像副屏已交付；CI 构建并跑 apxdisp --self-test）
│   ├─ host/             【M8/M10】Host Service：设备发现、Wi‑Fi 控制、桌面面板、CLI/SDK、安装包
│   └─ tools/            【M11】验证工具（链路测速等）
└─ scripts/              ConfigFS 挂载脚本、构建脚本
```

---

## 4. HID 复合设备设计（核心）

`f_hid` 只能实例化一个，因此**所有 HID 类功能塞进一个报告描述符的多个 Top-Level Collection（TLC）**，PC 端枚举为多个独立设备。

| Report ID | TLC / Usage Page | 内容 | 方向 |
|---|---|---|---|
| 2 | `0x01` Mouse | 鼠标相对位移（触控板模式） | IN |
| 3 | `0x0D` Digitizer | 多点触控 + 笔（标准 Usage：Tip Pressure / X-Y Tilt / Eraser），**绝对坐标** | IN |
| 4 | `0x0C` Consumer | 媒体键 **Usage 位图** | IN |
| 5 | `0xFF00` Vendor | 控制 + 状态上行 | OUT / IN / FEATURE |
| 6 | `0x85` Battery System | 电量（标准 usage）+ 温度 / 充放电（自研宿主解析） | IN |
| 16 | PTP (Precision Touchpad) | Windows PTP 5-finger 手势（仅 USB 有线模式） | IN |

> **v1.4 调整（触控板）**：Report ID 2 —— Mouse TLC，相对位移语义。
> Report ID 3（Digitizer）是绝对坐标，用于「手机显示 PC 画面并直接点该画面」。
> Report ID 2 是相对位移，用于「手机当触摸板、只驱动光标移动」。
> 混用会让光标在「跳转到某点」与「移动 N 像素」之间反复横跳。
>
> **无线模式的蓝牙版描述符是另一份**（`buildBtReportDescriptor`，仅含 Mouse / Keyboard / Consumer
> 三个 TLC，实测 181 字节），Report ID 独立编号（1 / 2 / 3），与上述 USB 版编号空间**互不相干**。

强制要求：
- 每个 Input Report 首字节为 Report ID，长度 ≤ **1024** 字节（USB HS 中断端点上限）
- **描述符总字节数必须 < 4096**（`f_hid` 经 ConfigFS 写 `report_desc` 的内核侧上限）
- 所有物理量的单位/指数用 `UNIT` + `UNIT_EXPONENT` 声明，**Android 原始单位须换算**
- 描述符字节流由 `shared/` 生成，禁止各模块手写魔数

---

## 5. 副屏闭环（镜像已交付；❌ 终止的仅是本节 IddCx 扩展屏方案）

> **2026-10-23 更正**：镜像副屏（抓屏→推流→手机渲染→触摸回传）**已交付并真机验证**
> （`ROADMAP.md` §2.3；`pc/display` 在 CI 构建）。本节**未交付**的部分仅 IddCx 虚拟显示器扩展屏；
> “screen 停在 disabled/ 且不在构建范围”说法已过时。`REQ-五路回报.md` 的 L3/L4 仍属历史记录。
> 以下保留原始设计，供将来若重启这条线时参考。

下行（PC → 手机）：IddCx 虚拟显示器 → Desktop Duplication 抓屏 → **只编码 DirtyRects** → H.265/AV1 硬编（关 B 帧、GOP=1）→ bulk 推流 → 手机 MediaCodec 低延迟解码 → SurfaceView 全屏渲染。

上行（手机 → PC）：`MotionEvent`（含压力/倾斜/朝向）→ 绝对坐标归一化到虚拟屏分辨率 → bulk 高优先通道 → PC 注入。

触控注入三档（按阶段递进）：
1. `SendInput` + `MOUSEEVENTF_ABSOLUTE`（免驱）
2. `InjectSyntheticPointerInput`（真触摸，需 `uiAccess` + 签名 + 系统目录）
3. KMDF HID minidriver（完整数位板，需 EV 签名）

---

## 6. 关键约束（所有模块必须遵守）

1. **速度门禁**：启动即读 `/sys/class/udc/*/current_speed`，非 `super-speed` 时降级运行并在 UI 明确告警。
2. **UDC 抢占**：Android USB HAL 会占用 UDC。写 `UDC` 前必须 `setprop sys.usb.config none` / 停 USB HAL，退出时恢复原配置，异常退出也要恢复。
3. **USB 3.0 干扰**：SuperSpeed 会干扰 2.4GHz WiFi/蓝牙；将来若有持续推流场景（当前无），应强制 5GHz。
4. **零自研驱动原则**：任何模块若发现需要新写 Windows 驱动，先检查是否有标准 Usage 可映射。

---

## 7. 模块划分与依赖

| 模块 | 负责 | 依赖 | 可并行 |
|---|---|---|---|
| **P0** 协议与公共库 | `shared/` 全部 + `docs/PROTOCOL.md` | — | 先行 |
| **M2** Gadget 管理器 | `android/.../gadget/` + `scripts/` | P0 | ✅ |
| **M4** 副屏手机端 | `android/.../screen/`（ScreenModule/Renderer/Activity，主源码树） | P0 | ✅ 已交付 |
| **M6** App UI 编排 | `android/.../ui/` + `core/` | M2 | 后 |
| **M7** 摄像头 | ~~`app/src/disabled/camera/`~~（已随 v0.3.2 移除） | P0, M2 | ❌ 已移除（路线留档 §2.2 阶段三） |
| **M8** 音频 | `android/.../audio/` | P0, M2 | ✅ |
| **M9** PC 副屏推流 | `pc/display/` | P0 | ✅ 镜像已交付（扩展屏 IddCx 已终止） |
| **M10** PC Host Service | `pc/host/` | P0 | ✅ |
| **M11** PC 验证工具 | `pc/tools/` | P0 | ✅ |
| **M12** Wi‑Fi 控制通道 | `android/.../wireless/` + `pc/host/src/wireless/` | P0, M10 | ✅ |
| **M13** 桌面端与安装包 | `pc/host/src/{ui,setup_main.cpp}` | M12 | ✅ |

---

## 8. 里程碑

- **MS1 全链路 Hello World**：USB 复合设备挂载 + 触控板 / 键盘 / 多媒体 / 音频全部跑通。✅
- ~~**MS2 副屏闭环**~~：虚拟显示器 + 推流 + 触控上行注入，端到端延迟 ≤ 30ms ——
  **用户已决策终止**，不再推进。
- **MS3 无线闭环**：免 root 跑通输入类与控制面。✅ **最终以 Wi‑Fi TCP 达成**（蓝牙四类输入
  已全：触控板/多媒体/键盘/手柄，键盘 2026-10-23 接入）；「TCP 覆盖副屏」已由媒体通道兑现
  （镜像副屏，ROADMAP §2.3），扩展屏部分随决策取消。
- **MS4 产品化**：桌面面板、开机自连、断线自愈、降级策略、SDK/CLI。✅ 安装包
  `apxsetup.exe` 已就绪。

---

## 9. 明确不做

- 传感器（IMU/光/接近/气压/方向等）—— 无标准 HID Class 支持 Windows 原生识别，需自研虚拟设备驱动，投入产出比不成立。
- GPS（NMEA over CDC ACM）—— Windows Location API 不接受 COM 口输入，需自研虚拟位置驱动。
- 振动/闪光灯/红外/LED 远程控制 —— 无标准 HID Usage 映射，需 Vendor 自定义，且实用性有限。
- WiFi 射频 / 蓝牙射频 / NFC / UWB 透传、指纹与人脸原始数据、基带 AT 指令、FM 收音机、ToF 与激光对焦（无公开 API）。

---

## 10. 技术决策记录

- **v1.4 起改为双主线**：无线模式能绕开 USB 侧一连串内核级硬限制（UDC 抢占失败、gadget 名额一次性、FunctionFS 上下文耗尽、改 gadget 需重启手机），且免 root 显著扩大可用人群。
- **传感器/GPS/振动裁撤**：这些功能在架构上无标准 Class、无 Gadget Function 或需 TEE 保护，自研虚拟设备驱动投入过高（需 EV 签名 + Windows 驱动开发），与「零自研驱动」原则矛盾，故止损。
- **PTP 精准触控板**：USB 有线模式下启用，Windows 通过 PTP 5-finger 手势识别系统手势（三指截屏/四指任务视图），无需额外驱动。