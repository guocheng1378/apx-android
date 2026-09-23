# 全能外设 · 总体架构

> 目标：一部 Android 手机通过一根 **USB 3.0（SuperSpeed 5Gbps）** 线缆，把机身内的硬件元器件虚拟化成 PC 的**本机设备**。
> 设计信条：**凡是能映射为 USB 标准 Class / HID 标准 Usage 的，一律走标准路径，PC 端零自研驱动。**

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

1. **蓝牙 HID**（`BluetoothHidDevice`，API 28+）—— 承载**全部输入类**：触控板、键盘、多媒体键。
   PC 端**零驱动**（蓝牙 HID 是标准协议）。使用独立的裁剪版描述符（仅 3 个 TLC，实测 181 字节，
   见 `PROTOCOL.md` §2.11）。**可与 USB 复合设备并存** —— 手机可同时做蓝牙触控板 + USB 副屏。
2. **WiFi 局域网 TCP** —— 承载**高带宽流与控制面**：副屏视频、触控注入、状态上报。
   项目已有 `TcpBulkTransport` 与 `tcp://` 基础。建议**手机做服务端**、PC 主动连入。
3. **无线 ADB** —— 开发期联调与兜底（`adb tcpip` + `adb forward`，即现有 `tcp://127.0.0.1:9500` 模式）。
   需开 USB 调试，定位为开发通道而非产品形态。

### 2.2 ⚠️ 无线模式的驱动代价

| 功能 | 无线实现 | 是否需新驱动 |
|---|---|---|
| 触控板 / 键盘 / 多媒体键 | 蓝牙 HID | **否** |
| 副屏视频 | TCP + 已有 IddCx 虚拟显示器 | **否** |
| 副屏触控注入 | TCP + 已有 `SendInput` | **否** |
| 控制面 / 状态 | TCP | **否** |
| **摄像头（供系统其它程序）** | TCP + 虚拟摄像头 | **是** |
| **音频** | TCP + 虚拟声卡 | **是**（可借第三方如 VB-Cable） |

**落地节奏**：

- **阶段一（免驱闭环，优先交付）**：蓝牙 HID 覆盖输入类 + TCP 覆盖副屏与控制面
  → 无线模式即可用，**零新驱动**
- **阶段二（借力第三方）**：音频 / 摄像头优先对接现成第三方虚拟设备，
  面板做可用性探测与引导
- **阶段三（可选自研）**：自研虚拟摄像头，涉及签名流程，
  仅在前两阶段无法满足时启动

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
│   │   ├─ core/         协议绑定、时钟、日志、事件总线
│   │   ├─ gadget/       【M2】ConfigFS 复合设备管理器
│   │   ├─ screen/       【M4】副屏视频解码 + 触控/笔采集上行
│   │   ├─ camera/       【M7】UVC 摄像头
│   │   ├─ audio/        【M8】UAC2 双向音频
│   │   ├─ bt/           蓝牙 HID 设备
│   │   ├─ hid/          HID 报告描述符 + 键码映射
│   │   └─ ui/           【M6】App UI 与服务编排
│   └─ app/src/main/cpp/ NDK 桥（复用 shared/）
├─ pc/
│   ├─ display/          【M7】IddCx 虚拟显示器驱动 + DDA 抓屏 + 硬编 + USB 推流
│   ├─ host/             【M8】Host Service：设备发现、触控注入、控制面板、CLI/SDK
│   └─ tools/            【M9】验证工具（链路测速等）
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

## 5. 副屏闭环（唯一无法免驱的模块）

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
3. **USB 3.0 干扰**：SuperSpeed 会干扰 2.4GHz WiFi/蓝牙，副屏场景强制 5GHz，文档中明示。
4. **零自研驱动原则**：任何模块若发现需要新写 Windows 驱动，先检查是否有标准 Usage 可映射。

---

## 7. 模块划分与依赖

| 模块 | 负责 | 依赖 | 可并行 |
|---|---|---|---|
| **P0** 协议与公共库 | `shared/` 全部 + `docs/PROTOCOL.md` | — | 先行 |
| **M2** Gadget 管理器 | `android/.../gadget/` + `scripts/` | P0 | ✅ |
| **M4** 副屏手机端 | `android/.../screen/` | P0 | ✅ |
| **M6** App UI 编排 | `android/.../ui/` + `core/` | M2, M4 | 后 |
| **M7** 摄像头 | `android/.../camera/` | P0, M2 | ✅ |
| **M8** 音频 | `android/.../audio/` | P0, M2 | ✅ |
| **M9** PC 副屏驱动与推流 | `pc/display/` | P0 | ✅ |
| **M10** PC Host Service | `pc/host/` | P0 | ✅ |
| **M11** PC 验证工具 | `pc/tools/` | P0 | ✅ |

---

## 8. 里程碑

- **MS1 全链路 Hello World**：USB 复合设备挂载 + 副屏/触控板/摄像头/音频全链路跑通。
- **MS2 副屏闭环**：虚拟显示器 + 推流 + 触控上行注入，端到端延迟 ≤ 30ms。
- **MS3 无线闭环**：蓝牙 HID 覆盖输入类 + TCP 覆盖副屏与控制面，免 root 跑通。
- **MS4 产品化**：控制面板、开机自连、断线自愈、降级策略、SDK/CLI。

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