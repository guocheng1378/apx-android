# 全能外设 · 总体架构

> 目标：一部 Android 手机通过一根 **USB 3.0（SuperSpeed 5Gbps）** 线缆，把机身内的硬件元器件虚拟化成 PC 的**本机设备**。
> 设计信条：**凡是能映射为 USB 标准 Class / HID 标准 Usage 的，一律走标准路径，PC 端零自研驱动。**

---

## 1. 架构总览

```
┌──────────────────────── Android 端（root + ConfigFS）────────────────────────┐
│  Hardware Agent（前台 Service，常驻）                                          │
│  ┌──────────┬──────────┬──────────┬──────────┬──────────┬────────────────┐  │
│  │ Sensor   │ Location │ Camera   │ Audio    │ Vibrator │ ScreenClient   │  │
│  │ Engine   │ (NMEA)   │ (UVC)    │ (UAC2)   │ LED/IR   │ (解码副屏视频)  │  │
│  └────┬─────┴────┬─────┴────┬─────┴────┬─────┴────┬─────┴───────┬────────┘  │
│       │          │          │          │          │             │           │
│  ┌────▼──────────▼──────────▼──────────▼──────────▼─────┐  ┌─────▼────────┐ │
│  │  HID Bridge（多 TLC，写 /dev/hidg0）                   │  │ Bulk Channel │ │
│  │  传感器/触控/按键/LED/振动/电池/Vendor                 │  │ (AOA/NCM)    │ │
│  └──────────────────────┬───────────────────────────────┘  │ 副屏视频下行  │ │
│                         │                                  │ 触控上行      │ │
│  ┌──────────────────────▼───────────────────────────────┐  └─────┬────────┘ │
│  │  Gadget Manager（ConfigFS 生命周期：挂载/卸载/自愈）   │        │           │
│  └──────────────────────┬───────────────────────────────┘        │           │
└─────────────────────────┼────────────────────────────────────────┼───────────┘
                          │          USB 3.0 SuperSpeed             │
┌─────────────────────────▼────────────────────────────────────────▼───────────┐
│  PC 端（Windows 优先 / Linux 次之）                                            │
│  ┌────────────────────────────────────────────┐  ┌────────────────────────┐  │
│  │ OS 原生集成层（零自研驱动）                  │  │ 自研模块                 │  │
│  │ SensorsHIDClassDriver → Windows.Devices.   │  │ IddCx 虚拟显示器        │  │
│  │   Sensors（加/陀/磁/光/压/接近/方向）        │  │ DDA 抓屏 + 硬编 + 推流  │  │
│  │ HID Digitizer → 原生触摸/压感笔             │  │ 触控注入（SendInput→   │  │
│  │ HID Consumer → 多媒体键                     │  │  PointerInject→HID驱） │  │
│  │ HID Battery(0x85) → 电源状态                │  ├────────────────────────┤  │
│  │ CDC ACM → COM 口 → Location API / gpsd      │  │ Host Service + 控制面板 │  │
│  │ UVC → 系统相机（多摄）  UAC2 → 系统声卡      │  │ 设备发现/开关/状态/诊断 │  │
│  │ NCM → 网卡              MTP → 便携设备       │  │ SDK / CLI              │  │
│  └────────────────────────────────────────────┘  └────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 双主线：有线与无线

系统有**两条并列主线**，PC 面板提供「有线模式 / 无线模式」切换，两条都完整跑通全部功能。
无线模式**无需 root**，是正式交付形态之一，**不是降级兜底**。

| | **有线模式**（USB Gadget） | **无线模式**（蓝牙 + 局域网） |
|---|---|---|
| 手机端前提 | **root + ConfigFS 复合设备** | **免 root**，普通应用即可 |
| 传输 | `f_hid` / `f_acm` / `f_uac2` / `f_uvc` / `f_ncm` + bulk | 蓝牙 HID（输入类）+ WiFi TCP（高带宽流与控制面） |
| PC 端 | **零驱动**，OS 原生枚举全部设备 | 输入类走蓝牙（系统原生免驱）；高带宽流走 TCP；**传感器/音频/摄像头需虚拟设备** |
| 优势 | 性能最高、延迟最低，且**手机同时充电** | 免 root、免线缆；绕开 UDC 抢占 / gadget 名额 / FFS 限制 / 重启等全部 USB 坑 |
| 代价 | 需 root；受内核 gadget 名额与 UDC 抢占限制 | 延迟高于 USB；**手机耗电明显**（有线时在充电，无线时纯耗电） |

### 2.1 无线模式的三个通道与分工

1. **蓝牙 HID**（`BluetoothHidDevice`，API 28+）—— 承载**全部输入类**：触控板、键盘、多媒体键。
   PC 端**零驱动**（蓝牙 HID 是标准协议）。使用独立的裁剪版描述符（仅 3 个 TLC，实测 181 字节，
   见 `PROTOCOL.md` §2.11）。**可与 USB 复合设备并存** —— 手机可同时做蓝牙触控板 + USB 副屏。
2. **WiFi 局域网 TCP** —— 承载**高带宽流与控制面**：副屏视频、触控注入、状态上报、传感器读数。
   项目已有 `TcpBulkTransport` 与 `tcp://` 基础。建议**手机做服务端**、PC 主动连入。
3. **无线 ADB** —— 开发期联调与兜底（`adb tcpip` + `adb forward`，即现有 `tcp://127.0.0.1:9500` 模式）。
   需开 USB 调试，定位为开发通道而非产品形态。

### 2.2 ⚠️ 无线模式无法回避的驱动代价

**有线模式「免驱」的前提是系统里真实枚举了一个 USB 设备。** 改走无线后 PC 侧不再有真实设备，
部分能力必须自造虚拟设备：

| 功能 | 无线实现 | 是否需新驱动 |
|---|---|---|
| 触控板 / 键盘 / 多媒体键 | 蓝牙 HID | **否** |
| 副屏视频 | TCP + 已有 IddCx 虚拟显示器 | **否** |
| 副屏触控注入 | TCP + 已有 `SendInput` | **否** |
| 控制面 / 状态 | TCP | **否** |
| **传感器（供系统其它程序）** | TCP + 虚拟 HID 传感器 | **是** |
| **摄像头（供系统其它程序）** | TCP + 虚拟摄像头 | **是** |
| **音频** | TCP + 虚拟声卡 | **是**（可借第三方如 VB-Cable） |

**落地节奏**：

- **阶段一（免驱闭环，优先交付）**：蓝牙 HID 覆盖输入类 + TCP 覆盖副屏与控制面
  → 无线模式即可用，**零新驱动**
- **阶段二（借力第三方）**：传感器 / 音频 / 摄像头优先对接现成第三方虚拟设备，
  面板做可用性探测与引导
- **阶段三（可选自研）**：自研虚拟传感器（VHF）与虚拟摄像头，涉及签名流程，
  仅在前两阶段无法满足时启动

**任一阶段，不可用能力必须在面板如实标注，禁止静默失败**（与传感器 Code 10 的处理原则一致）。

### 2.3 历史决策记录

原此处为「路线 A 为主线，路线 B 仅保留传输抽象、不投入实现」。**v1.4 起改为双主线**，
理由是无线模式能绕开 USB 侧一连串内核级硬限制（UDC 抢占失败、gadget 名额一次性、
FunctionFS 上下文耗尽、改 gadget 需重启手机 —— 见 `REALDEVICE-NOTES.md` §3.1/§3.5/§3.8），
且**免 root** 显著扩大可用人群。AOA bulk 仍作为后续可选降级路径保留（可参考 `dagaza/InkBridge`）。

---

## 3. 仓库结构（严格按目录分权，agent 不得越界）

```
全能外设/
├─ docs/
│   ├─ ARCHITECTURE.md          ← 本文件（唯一架构真源）
│   └─ PROTOCOL.md              ← 协议真源（帧格式 / HID 报告布局 / 时钟同步）
├─ shared/                      ← C++17 无依赖公共代码（Android NDK 与 PC MSVC 共用）
│   ├─ include/apx/{frame,hid_layout,clock,sensors_id}.h
│   └─ src/*.cpp
├─ android/
│   ├─ app/src/main/java/com/allperiph/
│   │   ├─ core/         协议绑定、时钟、日志、事件总线
│   │   ├─ sensor/       【M1】SensorManager 采集引擎
│   │   ├─ gadget/       【M2】ConfigFS 复合设备管理器
│   │   ├─ gps/          【M3】NMEA → CDC ACM
│   │   ├─ screen/       【M4】副屏视频解码 + 触控/笔采集上行
│   │   ├─ vibe/         【M5】振动 / 闪光灯 / 红外 / LED 控制
│   │   └─ ui/           【M6】App UI 与服务编排
│   └─ app/src/main/cpp/ NDK 桥（复用 shared/）
├─ pc/
│   ├─ display/          【M7】IddCx 虚拟显示器驱动 + DDA 抓屏 + 硬编 + USB 推流
│   ├─ host/             【M8】Host Service：设备发现、触控注入、控制面板、CLI/SDK
│   └─ tools/            【M9】验证工具（传感器读取、GPS 串口、链路测速）
└─ scripts/              ConfigFS 挂载脚本、构建脚本
```

---

## 4. HID 复合设备设计（核心）

`f_hid` 只能实例化一个，因此**所有 HID 类功能塞进一个报告描述符的多个 Top-Level Collection（TLC）**，PC 端枚举为多个独立设备。

| Report ID | TLC / Usage Page | 内容 | 方向 |
|---|---|---|---|
| 1 | `0x20` Sensor — IMU 批量 | 加速度 / 陀螺 / 磁力，多样本批量 | IN |
| 7 | `0x20` Sensor — Ambient Light | 环境光 | IN |
| 8 | `0x20` Sensor — Proximity | 接近 | IN |
| 9 | `0x20` Sensor — Atmospheric Pressure | 气压 | IN |
| 10 | `0x20` Sensor — Device Orientation | 设备方向 | IN |
| 11 | `0x20` Sensor — Inclinometer 3D | 倾角计 | IN |
| 12 | `0x20` Sensor — Ambient Temperature | 环境温度 | IN |
| 13 | `0x20` Sensor — Humidity | 湿度 | IN |
| 14 | `0x20` Sensor — Custom | 计步器 | IN |
| 15 | `0x20` Sensor — Custom | 心率 | IN |
| 3 | `0x0D` Digitizer | 多点触控 + 笔（标准 Usage：Tip Pressure / X-Y Tilt / Eraser），**绝对坐标** | IN |
| 4 | `0x0C` Consumer | 媒体键 **Usage 位图** | IN |
| 5 | `0xFF00` Vendor | 控制（振动、闪光、红外、模式切换）+ 状态上行 | OUT / IN / FEATURE |
| 6 | `0x85` Battery System | 电量（标准 usage）+ 温度 / 充放电（自研宿主解析） | IN |

> **v1.2 调整**：低频传感器由「共用 Report ID 2」改为**每个传感器一个独立 TLC + 独立 Report ID**（7..15）。原因是 Windows `SensorsHIDClassDriver` 按 TLC 枚举传感器，且需逐个下发 report interval；共用 Report ID 会导致低频传感器全部无法原生识别。详见 `PROTOCOL.md` §2.4。

> **v1.4 调整（触控板）**：新增 Report ID 2 —— **Mouse TLC，相对位移语义**，复用 v1.2 废弃的编号。
> 它与 Report ID 3（Digitizer）是两种不同的东西：Digitizer 用于「手机显示 PC 画面并直接点该画面」
> （**绝对坐标**），Mouse 用于「手机当触摸板、只驱动光标移动」（**相对位移**）。混用会让光标
> 在「跳转到某点」与「移动 N 像素」之间反复横跳。手势（双指滚动、双指右键、三指中键）
> 在**手机端**识别完成，协议只承载位移增量与按键。详见 `PROTOCOL.md` §2.10。
>
> **无线模式的蓝牙版描述符是另一份**（`buildBtReportDescriptor`，仅含 Mouse / Keyboard / Consumer
> 三个 TLC，实测 181 字节），Report ID 独立编号（1 / 2 / 3），与上述 USB 版编号空间**互不相干** ——
> 它们是系统里两个独立设备。详见 `PROTOCOL.md` §2.11。

强制要求：
- 每个 Input Report 首字节为 Report ID，长度 ≤ **1024** 字节（USB HS 中断端点上限）
- **描述符总字节数必须 < 4096**（`f_hid` 经 ConfigFS 写 `report_desc` 的内核侧上限）。
  **实测 v1.4 为 1727 字节（余量 2369）** —— `apx_selftest` 会打印 `INFO descriptor=... headroom=...`，
  新增 TLC 前先看这个数字，不要凭感觉估；v1.4 新增的 Mouse TLC 仅占 61 字节
- 所有物理量的单位/指数用 `UNIT` + `UNIT_EXPONENT` 声明，**Android 原始单位须换算**：加速度 m/s²→g，陀螺 rad/s→°/s
- 轴序对齐 Android 坐标系（X 右 / Y 上 / Z 出屏），转换放在 `shared/` 里统一实现
- 描述符字节流由 `shared/` 生成，禁止各模块手写魔数

---

## 5. 副屏闭环（唯一无法免驱的模块）

下行（PC → 手机）：IddCx 虚拟显示器 → Desktop Duplication 抓屏 → **只编码 DirtyRects** → H.265/AV1 硬编（关 B 帧、GOP=1）→ bulk 推流 → 手机 MediaCodec 低延迟解码 → SurfaceView 全屏渲染。

上行（手机 → PC）：`MotionEvent`（含压力/倾斜/朝向）→ 绝对坐标归一化到虚拟屏分辨率 → bulk 高优先通道 → PC 注入。

触控注入三档（按阶段递进）：
1. `SendInput` + `MOUSEEVENTF_ABSOLUTE`（免驱）
2. `InjectSyntheticPointerInput`（真触摸，需 `uiAccess` + 签名 + 系统目录）
3. KMDF HID minidriver（完整数位板，需 EV 签名）

---

## 6. 关键约束（所有模块必须遵守）

1. **速度门禁**：启动即读 `/sys/class/udc/*/current_speed`，非 `super-speed` 时降级运行并在 UI 明确告警（大量手机 Type-C 仅 USB 2.0）。
2. **UDC 抢占**：Android USB HAL 会占用 UDC。写 `UDC` 前必须 `setprop sys.usb.config none` / 停 USB HAL，退出时恢复原配置，异常退出也要恢复（注册 `onDestroy` + 信号兜底）。
3. **采样率**：Android 12+ `registerListener` 上限 **200Hz**；>200Hz 需在 Manifest 声明 `android.permission.HIGH_SAMPLING_RATE_SENSORS` 并实现降级提示。`SensorDirectChannel` 在普通权限下反被限到 ~50Hz，**不使用**。
4. **时钟同步**：手机端用 `SystemClock.elapsedRealtimeNanos()`，PC 端 QPC；握手交换并周期性 ping/pong 估计偏移（指数平滑），所有时间戳统一转换后才能做融合。
5. **批量优先**：IMU 攒批上报（N ≤ 16 样本/报告），禁止单样本单包。
6. **USB 3.0 干扰**：SuperSpeed 会干扰 2.4GHz WiFi/蓝牙，副屏场景强制 5GHz，文档中明示。
7. **零自研驱动原则**：任何模块若发现需要新写 Windows 驱动，先回到本文件检查是否有标准 Usage 可映射。

---

## 7. 模块划分与依赖

| 模块 | 负责 | 依赖 | 可并行 |
|---|---|---|---|
| **P0** 协议与公共库 | `shared/` 全部 + `docs/PROTOCOL.md` | — | 先行 |
| **M1** 传感器引擎 | `android/.../sensor/` | P0 | ✅ |
| **M2** Gadget 管理器 | `android/.../gadget/` + `scripts/` | P0 | ✅ |
| **M3** GPS → ACM | `android/.../gps/` | P0, M2 | ✅ |
| **M4** 副屏手机端 | `android/.../screen/` | P0 | ✅ |
| **M5** 振动/闪光/红外 | `android/.../vibe/` | P0, M2 | ✅ |
| **M6** App UI 编排 | `android/.../ui/` + `core/` | M1–M5 | 后 |
| **M7** PC 副屏驱动与推流 | `pc/display/` | P0 | ✅ |
| **M8** PC Host Service | `pc/host/` | P0 | ✅ |
| **M9** PC 验证工具 | `pc/tools/` | P0 | ✅ |

---

## 8. 里程碑

- **MS1 全链路 Hello World**：加速度计 → HID TLC → Windows「传感器」面板出现数据。验证 root / ConfigFS / UDC 抢占 / HID 描述符全链路。
- **MS2 传感器全家桶**：IMU + 光/接近/气压/方向 + 电池 + 按键，PC 端免驱全部可见。
- **MS3 GPS**：NMEA over CDC ACM → Windows 位置 API / `gpsd` 可读。
- **MS4 副屏闭环**：虚拟显示器 + 推流 + 触控上行注入，端到端延迟 ≤ 30ms。
- **MS5 相机/音频**：优先复用 Android 14+ `DeviceAsWebcam`；UAC2 仅在厂商内核启用时使用。
- **MS6 产品化**：控制面板、开机自连、断线自愈、降级策略、SDK/CLI。

---

## 9. 明确不做

WiFi 射频 / 蓝牙射频 / NFC / UWB 透传、指纹与人脸原始数据、基带 AT 指令、FM 收音机、ToF 与激光对焦（无公开 API）。这些在架构上无标准 Class、无 Gadget Function 或受 TEE 保护，直接止损。
