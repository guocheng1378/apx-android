# 全能外设 · 协议规范（唯一真源）

> 所有字段默认 **小端（little-endian）**。所有多字节字段自然对齐（`packed`）。
> 二进制布局由 `shared/include/apx/` 中的结构体定义，两者冲突时以本文件为准。

---

## 1. 时钟与时间戳

| 项 | 约定 |
|---|---|
| 手机端时基 | `SystemClock.elapsedRealtimeNanos()`（单调，含深睡） |
| PC 端时基 | `QueryPerformanceCounter()`（Windows） / `CLOCK_MONOTONIC`（Linux） |
| 传输时间戳 | `u64 tsNs`，手机端时基下的纳秒 |
| 同步 | 握手交换两端时基；每 1s ping/pong 一次，用指数平滑估计 `offset` 与 `drift`：<br>`offset = min(RTT 样本) → pc_time ≈ phone_time + offset` |
| 报告间隔 | 传感器报告统一按 `periodNs` 声明，批量报告中给出 `baseTsNs` 与 `periodNs`，第 i 个样本时间 = `baseTsNs + i * periodNs` |

---

## 2. HID 报告

### 2.1 描述符总原则

- 单一报告描述符包含 **15 个 Top-Level Collection**（Report ID 1、2、3、4、5、6、7–15），见 `ARCHITECTURE.md` §4
- 描述符总字节数须 **< 4096**（`f_hid` 经 ConfigFS 写入 `report_desc` 的内核侧上限）；超限时优先精简字段的范围声明，而不是删减 TLC
  - **实测（v1.4，`apx_selftest` 输出）：1727 字节，余量 2369**。新增 TLC 前先看这个数字（`INFO descriptor=... headroom=...`），别凭感觉估
  - v1.4 新增触控板 Mouse TLC 仅占 **61 字节**，远未逼近上限
- 每个 Input Report 首字节为 **Report ID**
- 单报告长度 ≤ **1024** 字节
- 物理量必须声明 `UNIT` + `UNIT_EXPONENT`

### 2.2 单位换算（Android → HID）

| 传感器 | Android 单位 | HID 声明单位 | 换算 |
|---|---|---|---|
| 加速度 | m/s² | g（`UNIT_EXPONENT` 见描述符） | `g = m/s² / 9.80665` |
| 陀螺仪 | rad/s | °/s | `°/s = rad/s * 57.2957795` |
| 磁力计 | µT | µT | 1:1 |
| 环境光 | lux | lux | 1:1 |
| 气压 | hPa | kPa 或 hPa（按描述符指数） | 按指数缩放 |
| 接近 | cm / boolean | boolean + 距离 | 手机多为 boolean，按描述符 |
| 方向 | 四元数 / 旋转矩阵 | Device Orientation（四元数或欧拉） | 由 `SensorManager.getRotationMatrix` + `getOrientation` 预融合 |

### 2.3 Report ID 1 — IMU 单样本（IN / FEATURE）

> **v1.3 裁决（源自真机验证）**：原「16 样本批量」格式（113 字节）**废弃**。
> 真机实测：手机 Gadget 挂载成功、6 个 TLC 全部被 Windows 枚举（触摸屏 / Consumer / Vendor / 传感器集合 / 串口均 `OK`），
> 但**加速度传感器报 `Code 10 (CM_PROB_FAILED_START)`**。
> 根因：Windows `SensorsHIDClassDriver` 要求每个传感器 TLC 必须具备
> **属性 Feature Report**（Report State / Change Sensitivity / Report Interval）且 Input Report 必须以
> **Sensor State + Sensor Event** 开头。原实现的元数据用 `padBytes` 声明为常量填充，驱动无法配置设备，故启动失败。
> 参考：MS《Sensor HID Class Driver》样例 + Linux `include/linux/hid-sensor-ids.h`（usage 数值来源）。

**Input Report（9 字节）**

| 偏移 | 类型 | 字段 | 说明 |
|---|---|---|---|
| [0] | u8 | reportId | = 1 |
| [1] | u8 | state | Sensor State：0=unknown 1=ready 2=not_available 3=error |
| [2] | u8 | event | Sensor Event：0=unknown 1=high 2=low 3=period_exceeded 4=change |
| [3..4] | i16 | x | 已换算为 **G**，指数 **-3**（raw × 10⁻³ g） |
| [5..6] | i16 | y | 同上 |
| [7..8] | i16 | z | 同上 |

**Feature Report（8 字节）** —— 由 PC 侧驱动读写，用于启停传感器与配置

| 偏移 | 类型 | 字段 | Usage (page 0x20) |
|---|---|---|---|
| [0] | u8 | reportId | = 1 |
| [1] | u8 | reportState | 0=no events / 1=all events（0x0316） |
| [2..3] | u16 | sensitivityAbs | 单位 G，指数 -3（0x030F） |
| [4..7] | u32 | reportIntervalMs | 毫秒（0x030E） |

**关键约束**：`packImuBatch()` 的 C++ 与 JNI 签名保持不变（Kotlin 侧零改动），内部改为取「本批最新样本」输出单样本；
`periodNs` / `baseTsNs` / `flags` 在该格式下无承载位置，保留形参并显式忽略。

### 2.4 低频传感器 —— 每传感器独立 Report ID 7..15（IN）

> **v1.2 裁决（源自 L1 core-proto 的实现反馈）**：原方案「一个 Report ID 2 复用 10 种低频传感器」**废弃**。
> 原因：Windows `SensorsHIDClassDriver` 的模型是**每个传感器一个独立 Top-Level Collection**，每个 TLC 需要自己的 Feature Report（reporting state / report interval / change sensitivity）才能被系统枚举和配置；同一 Report ID 内混装不同单位的字段，无法被原生识别。
> 新方案：**每个低频传感器一个独立 TLC + 独立 Report ID**，各自带标准 Usage 与 UNIT。报告布局统一为 24 字节，故 `hid_layout.h` 的现有结构无需重写，只改 Report ID 与描述符声明。

| Report ID | 传感器 | Sensor Page (0x20) Usage | Windows 原生识别 |
|---|---|---|---|
| 7 | 环境光 | Ambient Light | ✅ |
| 8 | 接近 | Proximity | ✅ |
| 9 | 气压 | Atmospheric Pressure | ✅ |
| 10 | 设备方向 | Device Orientation | ✅ |
| 11 | 倾角计 3D | Inclinometer 3D | ✅ |
| 12 | 环境温度 | Ambient Temperature | ✅ |
| 13 | 湿度 | Humidity | ✅ |
| 14 | 计步器 | Custom Sensor | ⚠️ Custom class |
| 15 | 心率 | Custom Sensor | ⚠️ Custom class |

> Windows 内置类驱动原生支持 11 种：Accelerometer 3D、Ambient Light、Ambient Temperature、Atmospheric Pressure、Compass 3D、Device Orientation、Gyroscope 3D、Humidity、Inclinometer 3D、Presence、Proximity，其余走 Custom class。
> **具体 Usage ID 编号以 HID Usage Tables 4.0 的 Sensors Page (0x20) 与 Linux 内核 `include/linux/hid-sensor-ids.h` 为权威来源查证后写死**，不得凭记忆填写。电池温度不占用独立 Report，已含在 §2.8 的 `tempCx10`。

统一报告布局（每个 Report ID 相同，共 24 字节）：

```
u8    reportId   7..15（各自独立）
u8    state      0=unknown 1=ready 2=not_available 3=error
u8    event      0=unknown 1=threshold_high 2=threshold_low 3=period_exceeded 4=change
u8    reserved   = 0
u64   tsNs
i32   value0     定标整数，指数由该 TLC 的 UNIT/UNIT_EXPONENT 声明；标量传感器只用 value0
i32   value1     方向/倾角用；标量传感器填 0
i32   value2     同上
```

### 2.5 Report ID 3 — Digitizer 触控/笔（IN）

> **v1.2 裁决（源自 L1 core-proto 的实现反馈）**：**改用 Digitizer Page 标准 Usage，不用厂商自定义**。
> L1 原提议「压力暂声明为 0xFF00:0x0004，待真机确认后改回」，现直接按标准声明——这样 Windows 免驱即可识别为笔输入：
> `In Range`(0x0D:0x32)、`Tip Switch`(0x0D:0x42)、`Barrel Switch`(0x0D:0x44)、`Eraser`(0x0D:0x45)、
> `Tip Pressure`(0x0D:0x30，Logical Min 0 / Max 65535，无需为高精度而改用自定义)、
> `X Tilt`(0x0D:0x3D)、`Y Tilt`(0x0D:0x3E)。
> 坐标 X/Y **保留在 Generic Desktop 页** `0x01:0x30` / `0x01:0x31`（Digitizer TLC 下的惯例做法），与 L1 现有实现一致。
> 仍保留 `0xFF00` 自定义字段承载 `orientation`（方位角），因其无对应标准 Usage。

```
u8    reportId  = 3
u8    flags     bit0=笔在量程内 bit1=笔尖接触 bit2=橡皮擦 bit3=桶按钮
u8    contactCount  0..10
u64   tsNs
per contact (8B):
  u16  contactId
  u16  x        归一化到虚拟屏宽，0..65535
  u16  y        归一化到虚拟屏高，0..65535
  u16  pressure 0..65535（笔）；手指固定 0x7FFF
pen extra (10B): tiltX i16, tiltY i16, orientation u16, 保留 u32
```

### 2.6 Report ID 4 — Consumer 按键（IN）

> **v1.2 裁决（源自 L1 core-proto 的实现反馈）**：**改用 Usage 位图**，废弃「键编号 + 状态」编码。
> 原因：编号编码无法被 Windows 原生识别为多媒体键，只能由自研宿主翻译后注入。

```
u8  reportId = 4
u16 keyBitmap   每位对应一个 Consumer Page (0x0C) Usage：
                bit0=0xE9 音量+  bit1=0xEA 音量-  bit2=0xE2 静音  bit3=0x30 电源
                bit4=0xCD 播放/暂停  bit5=0xB6 上一曲  bit6=0xB5 下一曲
                bit7..15 预留
u8  reserved = 0
```
按下与释放均发全量位图（1=按下，0=释放），PC 侧按位比对得到边沿事件。

### 2.7 Report ID 5 — Vendor 控制与状态（OUT / IN / FEATURE 三用途）

> **v1.1 裁决（源自 L2 android-devices 的实现反馈）**：该 TLC 的 HID 描述符**必须同时声明 Input、Output、Feature 三种报告**。
> 早期版本只写了 OUT + FEATURE，导致手机端状态（status / linkSpeed / errorCode）**无法上行**。
> Input = 状态上行（手机→PC）；Output = 命令下行（PC→手机）；Feature = 能力与属性查询。

```
OUT（PC → 手机）
u8  reportId = 5
u8  cmd      0x01=振动 0x02=停止振动 0x03=手电筒 0x04=红外发送
             0x10=设置传感器启用掩码 0x11=设置采样率 0x12=设置副屏模式（已废弃）0x7F=心跳
u8  seq
u8  reserved
u32 payloadLen    0..256
u8  payload[payloadLen]   振动: {u16 durationMs, u8 amplitude}
                          手电: {u8 on, u8 level}
                          红外: {u32 freqHz, u16 patternLen, u16 pattern[]}
                          传感器掩码: {u64 mask}                      位定义见 §2.9
                          采样率: {u8 sensorId, u32 rateHz}           sensorId bit7 = best-effort 标志
```

**采样率 `sensorId` 的 bit7（v1.1 裁决）**：`bit7=1` 表示**尽力而为**——当请求速率超过硬件或系统上限（如 Android 12+ 未授予 `HIGH_SAMPLING_RATE_SENSORS` 时的 200Hz）时，手机端**自动降到最接近的可达速率并继续执行**，不返回错误；`bit7=0` 表示**严格模式**，达不到则拒绝并返回错误码。低 7 位为 `§2.4` 的 sensorId。

```
IN / 状态上报（手机 → PC）   ← 必须是真正的 Input Report，共 24 字节
偏移  类型  字段        说明
[0]   u8  reportId   = 5
[1]   u8  status     0=idle 1=running 2=error 3=降级(USB2.0)
[2]   u8  linkSpeed  0=unknown 1=full 2=high 3=super 4=super_plus
[3..10]  u64 moduleMask 已启用模块位图（bit32..37，见 §2.9）
[11..14] u32 errorCode
[15..22] u64 uptimeMs
[23]  u8  lastSeq    最后执行的控制命令 seq 回显（PC 侧据此确认命令已被执行）
```

FEATURE / 能力查询（双向）与 OUT 共用 `reportId=5` 的 TLC，承载支持的能力列表、描述符版本、协议版本等查询。

### 2.8 Report ID 6 — Battery System（IN）

> **v1.2 裁决（源自 L1 core-proto 的实现反馈）**：**不追求 Windows 原生电池集成**。
> 理由：PC 自身有完整电源栈，Windows 的内置 HID 电池支持主要面向 UPS / 蓝牙设备；把手机电量塞进系统电源 UI 没有产品意义，且其 Usage 组合未经真机验证，投入产出不划算。
> 决策：仅电量用标准 `Relative State of Charge`(0x85:0x66，百分比) 声明，其余字段保持厂商自定义布局，**由自研宿主（L5 控制面板）按偏移解析并展示**。L1 无需再为此查证 Battery System 页的 Usage 组合。

```
u8  reportId  = 6
u8  chargePct     0..100
u8  state         0=unknown 1=charging 2=discharging 3=full 4=not_charging
u16 voltageMv
i16 currentMa     负=放电
i16 tempCx10      0.1℃ 定标
u32 remainingMin  0xFFFFFFFF=未知
```

### 2.9 位域定义（v1.1 新增）

**传感器/一次性传感器掩码（`u64 mask`，用于 Report 5 的 `cmd=0x10` 与 `moduleMask`）**

| 位 | 含义 | 对应 Report |
|---|---|---|
| 0 | 加速度计 | 1（批量） |
| 1 | 陀螺仪 | 1 |
| 2 | 磁力计 | 1 |
| 3 | 未校准加速度 | 1 |
| 4 | 未校准陀螺 | 1 |
| 5 | 未校准磁力 | 1 |
| 6–15 | 预留 | 1 |
| 16 | 环境光 | 7 |
| 17 | 接近 | 8 |
| 18 | 气压 | 9 |
| 19 | 设备方向 | 10 |
| 20 | 倾角计 3D | 11 |
| 21 | 环境温度 | 12 |
| 22 | 湿度 | 13 |
| 23 | 计步器 | 14 |
| 24 | 心率 | 15 |
| 25–31 | 预留 | — |
| 32 | 触控 / 笔 | 3 |
| 33 | Consumer 按键 | 4 |
| 34 | 电池状态 | 6 |
| 35 | GPS（CDC ACM） | — |
| 36 | 振动 / 手电 / 红外 | 5 |
| 37 | ~~副屏视频~~ | **已终止**（原 bulk streamId 0，保留未用） |
| 38 | 触控板（相对位移） | 2 |
| 39 | 摄像头 | UVC / 系统方案（代码在 `app/src/disabled/`，**未编译**） |

**`moduleMask`** 使用上表的 bit32–bit39 语域表示模块级启用状态，与传感器掩码共用 64 位宽度但互不重叠。

> **bit32 与 bit38 的区别**：bit32 是**触控/笔**（Report 3，**绝对坐标**，映射到虚拟屏）；
> bit38 是**触控板**（Report 2，**相对位移鼠标**，手机不显示画面）。两者语义不同、可同时成立，
> 故各占一位 —— 详见 §2.10。

### 2.10 Report ID 2 — 触控板（相对位移鼠标，IN）

> **v1.4 新增**，复用 v1.2 废弃的 Report ID 2。

**与 §2.5 Digitizer 的本质区别（这是本节存在的全部理由）**：

| | §2.5 Digitizer（Report 3） | §2.10 触控板（Report 2） |
|---|---|---|
| 坐标语义 | **绝对**（归一化 0..65535，映射到虚拟屏） | **相对位移**（Δx/Δy） |
| 手机屏幕 | 显示 PC 画面 | **不显示**（触摸板 UI） |
| 多点 | 支持（最多 10 触点） | 不需要（手势已在端侧识别） |
| 延迟要求 | 高（跟手） | **极高**（超过约 10ms 即明显不适） |
| 报告长度 | 102 字节 | **6 字节** |

**两者绝不能混用一个 Report**：Windows 对「绝对」与「相对」走的是完全不同的处理路径，
混用会让光标在「跳转到某点」与「移动 N 像素」之间反复横跳。

**布局（6 字节）**：

```
偏移  长度  字段      说明
0     1     reportId  = 2
1     1     buttons   bit0=左键 bit1=右键 bit2=中键（其余位保留为 0）
2     1     dx        水平相对位移，i8，右为正
3     1     dy        垂直相对位移，i8，下为正
4     1     wheel     垂直滚动，i8，上滚为正
5     1     pan       水平滚动，i8，右滚为正
```

**位移限幅**：四个位移量均为 `i8`，范围 **[-127, 127]**（`kMaxMouseDelta`）。
超出时**必须夹紧（clamp）而非回绕** —— 回绕会让光标瞬间反向跳，观感极差
（`apx_selftest` 已覆盖该边界）。需要更大位移时由端侧**拆成多次报告**发送，
鼠标本就是「多次小增量」语义。

**手势归属：手机端**。手机持有原始多点数据，单指移动/点按、双指滚动、双指右键、
三指中键**全部在端侧识别完成**，本报告只承载最终结果（位移量 + 按键 + 滚动量）。
好处有三：上行带宽最小、延迟最低；「手感」（灵敏度曲线、加速度、惯性）留在端侧可调；
协议保持稳定，改手感不需要动两端。

**描述符要点**（见 `shared/src/hid_descriptor.cpp` 的 `buildTlcMouse`）：

- Usage Page `0x01`（Generic Desktop）/ Usage `0x02`（Mouse），Application Collection
- 内含 `Pointer`（`0x01`）Physical Collection
- 按键用 `Usage Minimum(1)` / `Usage Maximum(3)` + `ReportCount(3)`，随后 5 位填充
- **X/Y/Wheel/AC Pan 一律为 `Data,Var,Relative`（`0x06`）** —— 这是与 Digitizer 最关键的差异。
  若误写成 Absolute，PC 会按绝对坐标处理，表现为「点哪跳哪」而非「拖动」

---

### 2.11 蓝牙 HID 版描述符（无线模式）

无线模式下手机通过 Android `BluetoothHidDevice` 注册为蓝牙 HID 设备，
PC 端**零驱动**直接识别。它使用**另一份独立的报告描述符**
（`shared/src/hid_descriptor.cpp` 的 `buildBtReportDescriptor`）：

| | USB 版 | 蓝牙版 |
|---|---|---|
| TLC 数量 | **15**（含 9 个低频传感器） | **3**（Mouse / Keyboard / Consumer） |
| 实测总长 | 1727 字节 | **181 字节** |
| Report ID | 1、2、3..15（USB 设备内编号） | **1=Mouse、2=Keyboard、3=Consumer** |
| 4096 上限 | 受限于 `f_hid` | **不受限**（蓝牙无此约束） |

**两套 Report ID 空间互不相干**，绝不能混用常量 —— 它们是系统里两个独立设备的编号。
蓝牙版不含传感器 TLC，因为传感器在无线模式下走 WiFi/TCP 而非 HID。

---

## 3. Bulk 通道（APX1 帧）

> **现状**：`streamId=3` 是当前唯一真机在跑的通道 —— **Wi‑Fi 控制的输入上行**（见 §3.3）。
> `streamId=0`（视频）随副屏终止而**保留未用**，§3.1 为历史定义。
> `streamId=1/2/4` 同样未接入。

`shared/include/apx/frame.h` 定义统一帧头：

```
struct ApxFrameHeader {          // 16 字节
    char     magic[4];           // 'APX1'
    u8       streamId;           // 0=video 1=audio 2=touch 3=ctrl 4=telemetry
    u8       flags;              // bit0=keyframe bit1=last_fragment bit2=dropable
    u16      headerExtWords;     // 扩展头字数（32bit 字）
    u32      payloadLen;
    u32      seq;
};
```
**长度与校验（权威定义，实现必须逐条对齐）**：

- `payloadLen` **含**尾部 `u32 crc32`，**也含**扩展头（`headerExtWords * 4` 字节）。
- **帧总长 = 16 + payloadLen**（`apx::frameTotalSize`）。校验长度自洽时**不得**再额外
  加 `headerExtWords * 4` —— 那等于把扩展头算两次（视频帧恒差 20 字节）。
- **CRC32 覆盖范围 = 16 字节帧头之后的全部字节，不含尾部那 4 字节 CRC。**
  即等价于 `apx::verifyPayload(payload, payloadLen)`。
- 两侧统一（`shared/src/frame.cpp`、`pc/display/transport/frame_writer.cpp`、
  `android/.../core/ApxFrame.kt`、`pc/host/src/wireless/wireless_link.cpp`）。

> ⚠️ 这对定义是本协议的**历史踩坑点**：CRC 覆盖范围与长度自洽两处都曾出现「含帧头 /
> 不含帧头」「扩展头算一次还是两次」的分歧，且因为**接收侧普遍不校验 CRC**（如 Android
> `TcpControlChannel` 只在发送时算），错误会静默存活到某一端开始校验才爆发。
> 改这三个常量中任何一个，都要两端同时改并跑 `apxdisp --self-test`。

### 3.1 streamId 0 — Video（PC → 手机）· ❌ 副屏已终止，保留未用

扩展头：
```
u64 ptsNs
u16 width, height
u16 frameRateX100
u16 dirtyRectCount
u16 codecId        0=H264 1=HEVC 2=AV1 3=MJPEG 4=RAW_LZ4
```
DirtyRect：`{u16 x, u16 y, u16 w, u16 h}` 紧跟扩展头；其后为码流分片。

### 3.2 streamId 2 — Touch（手机 → PC，低延迟优先）

载荷直接复用 §2.5 的触点结构，另附 `u64 tsNs`。此通道与 HID Report ID 3 **二选一**启用：
需要把绝对坐标映射到虚拟屏的场景（原副屏方案，**已终止**）走 bulk；独立数位板场景走
HID（免驱，**当前交付的实际路径**）。streamId 2 与 Report ID 3 目前均未接入。

### 3.3 streamId 3 — Control

**v1 实际实现（真机在跑）**：`streamId=3` 即 **统一控制面（9511）**，载荷为「**单字节子命令 +
定长参数**」。两端必须同步修改：PC 端 `pc/host/src/wireless/ctrl9511.cpp`，手机端
`android/.../wireless/TvControllerClient.kt`（控设备）/ `android/.../controlled/TvControlServer.kt`（被控）。

```
0x01 鼠标   [1]=buttons [2]=dx(i8) [3]=dy(i8) [4]=wheel(i8)
0x02 多媒体 [1..2]=u16 位图（LE）
0x03 键盘   [1]=mod [2]=0 [3..8]=k1..k6（HID usage 页 0x07，PC 侧查表转 VK）
```

承载约定：**统一控制面 TCP `9511`**（手机可服务端可客户端：被控模式手机做服务端、控设备模式手机做客户端），PC 主动连入；UDP `9501` 广播
`APX1TV <name> <port> <token>`（port=9511）供 PC / 另一台手机自动发现。令牌（token）字段保留但 v1 默认留空
（局域网工具，不做鉴权）；**不匹配时服务端直接关连接、不回执** —— 回执字节会与紧随其后的
帧混淆。

> 下文原设计的「JSON / TLV 控制面 + 分辨率切换 + 编解码参数 + 心跳 RTT」**未实现**，
> 且 `streamId=3` 已被上述 v1 布局占用；若将来要恢复该设计，需另选 streamId 或做版本协商。

**控制面永远走可靠顺序通道**（AOA bulk 或 NCM TCP），视频走可丢包通道。

### 3.4 媒体通道（TCP 9502，v1.11 新增）

**为什么与控制面分成两条连接**：控制面（9511）是单对端语义，且承担 60 次/秒的输入小帧；
把 10Mbps 级视频混进同一条连接，视频的拥塞会直接卡住输入延迟。分开后两条链路可独立启停
（关副屏不影响键盘鼠标）。落点：手机 `wireless/TcpMediaChannel.kt`、
PC `pc/host/src/media/media_session.cpp`。

| 端口 | 传输 | 角色 | 用途 |
|---|---|---|---|
| 9511 | TCP | 手机可服务端/客户端 | 统一控制面（§3.3）：鼠标/键盘/触摸/多媒体/剪贴板 |
| 9501 | UDP | 手机广播 | 信标 `APX1TV <name> <port> <token>`（port=9511） |
| 9502 | TCP | 手机做服务端 | **媒体**：副屏 / 音箱 / 麦克风 / 摄像头 |

握手与 9511 **逐字节相同**：`u32 LE 长度 + UTF-8 令牌`。
⚠️ **令牌为空也必须发/收那 4 字节长度** —— 接收侧永远先读 4 字节；省掉它会让对端把
随后的帧头当成长度而直接关连接（真机踩过两次，见 `pc/display/transport/tcp_transport_win.cpp`
的 `exchangeToken` / `sendBytes` 注释）。

**流号与方向**（v1.11 起带方向，同一连接上按 streamId 解复用）：

| streamId | 名称 | 方向 | 载荷 |
|---|---|---|---|
| 0 | `video` | PC → 手机 | 副屏画面：`[VideoExtHeader 20B][DirtyRect×N][码流]`，见 §3.1 |
| 1 | `audio` | PC → 手机 | 音箱：PCM **s16le / 48000Hz / 立体声**（192 字节/ms，10ms 一片 = 1920B） |
| 5 | `mic` | 手机 → PC | 麦克风：与 `audio` 同格式 |
| 6 | `camera` | 手机 → PC | 摄像头：单帧 JPEG（不分片） |
| 2 / 4 | `touch` / `telemetry` | — | 预留未接入 |

> 音频刻意分成 1（下行）与 5（上行）：音箱与麦克风方向相反，共用一个号会互相污染。

**分片与重组**：视频帧超过 256KiB 会切成多个分片帧，**同一逻辑帧的各分片 `seq` 相同**，
**只有末片**带 `flags` bit1（`last_fragment`）。接收侧按 seq 顺序拼接
（C++ 侧 `apx::FrameAssembler`，Kotlin 侧 `core/ApxStreams.kt` 的 `FragmentJoiner`）。
中间丢片表现为 **seq 跳变** —— 此时丢弃已攒部分、从新 seq 重来（宁可花屏一帧，也不能把两帧拼成一帧）。

**背压策略**：发送侧对「可丢」的大块载荷（视频 / 摄像头）单独设闸 ——
队列积压过半即丢弃新帧、优先保住音频的时效性；丢弃计数**如实上报**，不静默。

---

## 4. 控制面握手

1. PC 发送 `HELLO {protocolVersion, pcClock, capabilities}`
2. 手机回 `HELLO_ACK {protocolVersion, phoneClock, udcSpeed, sensorList[], enabledModules}`
3. PC 发送 `CONFIG {sensorMask, sampleRates[], displayMode, videoParams}`
4. 手机 `CONFIG_ACK` 后进入 `RUNNING`
5. 任意端 `BYE` → 手机端必须恢复原 USB 配置

心跳间隔 1s，3 次无响应判定断线并触发自愈（重新挂载 Gadget）。

---

## 5. 版本与兼容

- 协议版本字段 `u16`，当前 **1.6**

**变更记录**

| 版本 | 变更内容 | 影响范围 | 受影响 lane | 原因 |
|---|---|---|---|---|
| 1.0 | 初版 | — | 全部 | — |
| 1.1 | Report 5 改为 OUT/IN/FEATURE 三用途，新增 `lastSeq` 回显 | HID 描述符、手机端状态上行 | L1（描述符）、L2（状态上报）、L5（读取） | 仅 OUT+FEATURE 导致状态无法上行 |
| 1.1 | 采样率 `sensorId` bit7 定义为 best-effort / 严格模式 | 控制面语义 | L2、L5 | Android 12+ 200Hz 限制需要明确降级语义 |
| 1.1 | 新增 §2.9：传感器与模块位域定义 | `cmd=0x10`、`moduleMask` | L1、L2、L3、L5 | 消除各方对掩码位的自行约定 |
| **1.2** | §2.4 废弃 Report ID 2 复用，改为低频传感器各自独立 Report ID **7..15** + 独立 TLC | HID 描述符结构、Report ID 分配 | L1（描述符/布局）、L2（打包）、L5（解析） | 一个 Report ID 混装不同单位，Windows 无法按独立传感器枚举，也无法逐个下发 report interval |
| **1.2** | §2.6 Consumer 改为 **Usage 位图**（0x0C 各 usage 按位） | 描述符、按键编码 | L1、L2、L3（采集）、L5 | 原「键编号+状态」无法被 Windows 识别为多媒体键 |
| **1.2** | §2.5 Digitizer 改用**标准 Usage**（Tip Pressure 0x0D:0x30、X/Y Tilt 0x0D:0x3D/0x3E、Eraser 0x0D:0x45 等），坐标保留 0x01:0x30/0x31 | 描述符 | L1、L3、L4 | 免驱识别为笔输入，避免为高精度而退化为厂商自定义 |
| **1.2** | §2.8 Battery **不做 Windows 原生集成**，仅电量用标准 usage，其余由自研宿主解析 | 描述符、L5 展示 | L1、L5 | PC 自身电源栈已有职责，投入产出不划算 |
| **1.2** | §2.9 掩码位 bit16–24 重新映射到 Report 7..15 | `cmd=0x10`、`moduleMask` | L1、L2、L3、L5 | 与 §2.4 的拆分保持一致 |
| **1.2** | §2.7 状态包 `moduleMask` 由 `u8` 改为 **`u64`**，状态包定长 **24 字节**并标注逐字段偏移 | Report 5 IN 报告布局 | L1（已按此实现）、L2、L5（解析必须同步） | v1.1 引入 §2.9 时遗漏回写 §2.7，`u8` 装不下 bit32–37 的模块掩码，属协议自相矛盾；L1 已按 u64 实现并标 breaking |
| **1.3** | §2.3 IMU 由「16 样本批量（113B）」改为 **单样本（9B）+ 属性 Feature Report（8B）** | Report 1 的 Input 与 Feature 布局 | L1（描述符/布局）、L2（打包）、L5（如需读取） | **真机验证发现**：批量格式缺 Windows 传感器类驱动必需的 Report State / Change Sensitivity / Report Interval 属性，且 Input 未以 State+Event 开头 → 加速度计报 `Code 10 (CM_PROB_FAILED_START)`。单样本是 Windows 原生识别的必要条件 |
| **1.4** | §2.10 新增 **Report ID 2 触控板（Mouse TLC）**：6 字节相对位移鼠标报告，复用 v1.2 废弃的 Report ID 2 | HID 描述符结构（USB 1727B / 蓝牙 181B）、§2.10 新报告布局、Report ID 复用 | L1（描述符构造器 `buildTlcMouse`）、L3（端侧手势识别+采集）、L4（PC 端相对位移注入）、L5（控制面板配置项） | §2.5 Digitizer 是「绝对坐标」语义、跟手延迟预算高；触控板需要**极高延迟预算（≤10ms）+ 相对位移**，两套语义走 Windows 不同处理路径，混用会让光标在「跳转」与「拖动」之间反复横跳。独立 Mouse TLC（X/Y/Wheel/AC Pan 均为 `Data,Var,Relative`）是免驱识别为鼠标的唯一正确写法 |
| **1.5** | 传感器 TLC（Report 1 与 7..15）的 Feature Report 补 **Sensor Status（usage 0x0304，8b）**，布局变为 `reportId(1)+state(1)+status(1)+sensitivity(2)+interval(4)` = 9B；低频 TLC 的 Input 数据字段从 Vendor Page 迁移至 **Sensor Page 标准 Data Field usage**（ILLUM 0x04D1、HUMAN_PRESENCE 0x04B1、ATM_PRESSURE 0x0430、ENV_TEMP 0x0434、ATM_HUMIDITY 0x0433、MAGN_FLUX 0x0485-87、TILT 0x047F-81、CUSTOM 0x0543/44） | Feature 布局（8B→9B，`ImuFeatureReport`）、描述符、GET_REPORT 登记载荷 | L1（描述符/布局）、L2（登记载荷） | **真机实测**：缺 Sensor Status 与 Vendor 页数据字段时，Windows `SensorsHIDClassDriver` 以 `STATUS_INVALID_PARAMETER` 拒绝全部传感器 TLC（Code 10）；依据微软《Sensor HID Class Driver》文档四项必需属性 + 数据字段必须在 Sensor Page |
| **1.6** | 传感器 TLC 的 Feature Report 追加 **Power State（usage 0x0319，8b，D0=0）**，布局变为 `reportId(1)+state(1)+status(1)+sensitivity(2)+interval(4)+power(1)` = 10B；Report Interval 的 Logical Max 改 32 位语义 | Feature 布局（9B→10B，`ImuFeatureReport`）、GET_REPORT 登记载荷 | L1、L2（登记载荷） | 对齐微软《Sensor HID Class Driver》模板字段集（实测固件普遍声明 Power State）；Linux 头 `PROY_POWER_STATE 0x200319` 为权威 |
- 手机端与 PC 端版本不一致时：高版本端必须兼容低版本字段集，未知扩展头按 `headerExtWords` 跳过

---

## 6. 音频（UAC2）

音频**不走本协议的自定义通道**，而是直接用 **USB Audio Class 2 标准类** ——
这样 PC 端免驱（Windows / macOS / Linux 全原生识别为声卡），符合"PC 端零自研驱动"前提。
手机端由内核 `f_uac2` 提供 ALSA 设备，App 只做 `ALSA ↔ AudioRecord/AudioTrack` 的搬运。

### 6.1 格式约定

**两端必须完全一致**。常量集中定义在 `android/.../core/AppPaths.kt` 的 `AudioConst`：

| 项 | 值 | 说明 |
|---|---|---|
| 采样率 | 48000 Hz | UAC2 与 Android 音频框架的重合点，兼容性最好 |
| 采样位宽 | 16-bit PCM | |
| 声道 | 2（立体声） | 通道掩码 `3` |

这些值**同时**用于两处：ConfigFS 的 f_uac2 属性（决定 USB 侧协商格式）与 App 侧音频
采集/播放（决定 Android 侧格式）。**只改一边会导致「声卡认到了但没声音」**，
而且两端各自看起来都正常，极难定位。

### 6.2 方向语义（最容易搞混）

USB 的 capture/playback 是**站在 PC 角度**命名的：

| USB 术语 | 数据流 | PC 侧表现 | 手机侧实现 |
|---|---|---|---|
| **capture** | 手机 → PC | 多出一个**麦克风** | `AudioRecord` → 写 ALSA capture 设备 |
| **playback** | PC → 手机 | 多出一个**扬声器** | 读 ALSA playback 设备 → `AudioTrack` |

### 6.3 ALSA 设备定位

**禁止硬编码 `hw:0,0`** —— 设备号随内核枚举顺序变化。正确做法：

1. 用 `AudioConst.ALSA_CARD_KEYWORD`（`UAC2Gadget`）在 `/proc/asound/cards` 定位 card 号
2. 再从 `/proc/asound/pcm` 解析出对应的 `pcmC*D*` 节点

### 6.4 降级行为

内核未编译 `usb_f_uac2` 时（**实测 Xiaomi HyperOS 就没有**），该 function 被标记为
`optional`，挂载时自动跳过，**其余设备不受影响**。

此时若要音频，只能改走用户态方案（复用 bulk 通道 + Opus），但**代价是 PC 端必须自建
虚拟声卡**（VB-CABLE 或自研 WDM 驱动），会破坏"零驱动"前提 —— 因此优先争取内核 UAC2 支持。
可用性探测方法见 `docs/REALDEVICE-NOTES.md`。
