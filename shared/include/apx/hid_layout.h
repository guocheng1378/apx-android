// ============================================================================
// HID 报告二进制布局（docs/PROTOCOL.md §2、ARCHITECTURE.md §4）
//
// 全部 packed：字段自然对齐由显式小端读写保证，结构体只描述字节顺序。
// 每个 Input Report 首字节为 Report ID，单报告 ≤ 1024 字节（§2.1）。
// 打包/解析函数见本文件末尾，实现在 src/hid_layout.cpp。
// ============================================================================
#pragma once
#include <cstddef>
#include <cstdint>

namespace apx {

// ---- Report ID（§4 表格）--------------------------------------------------
enum : uint8_t {
    kReportImuBatch   = 1,   // §2.3 IN  IMU（Windows 原生单样本格式）
    kReportMouse      = 2,   // §2.10 IN 触控板（相对位移鼠标；复用 v1.2 废弃的 ID 2）
    kReportDigitizer  = 3,   // §2.5 IN  触控/笔
    kReportConsumer   = 4,   // §2.6 IN  多媒体按键（usage 位图）
    kReportVendor     = 5,   // §2.7 OUT / IN / FEATURE 控制与状态
    kReportBattery    = 6,   // §2.8 IN  电池
    // v1.2：低频传感器改为**各自独立 TLC + 独立 Report ID**（原共用的 ID 2 已废弃）。
    // 原因是 Windows SensorsHIDClassDriver 按 TLC 枚举传感器，并且需要逐传感器
    // 下发 report interval —— 共用一个 Report ID 会导致它们全部无法被原生识别。
    kReportAls          = 7,   // 环境光      Ambient Light
    kReportProximity    = 8,   // 接近        Proximity
    kReportPressure     = 9,   // 气压        Atmospheric Pressure
    kReportOrientation  = 10,  // 设备方向    Device Orientation
    kReportInclinometer = 11,  // 倾角计 3D   Inclinometer 3D
    kReportAmbientTemp  = 12,  // 环境温度    Ambient Temperature
    kReportHumidity     = 13,  // 湿度        Humidity
    kReportStepCounter  = 14,  // 计步器      Custom
    kReportHeartRate    = 15,  // 心率        Custom
    // v1.8：Windows Precision Touchpad（PTP）——ID 避开 1..15（已占用），语义对应
    // imbushuo/mac-precision-touchpad 的 MULTITOUCH/REPORTMODE/FUNCSWITCH/DEVICE_CAPS/PTPHQA
    kReportTouchPad     = 16,  // PTP 多指输入（50B = rid + 5×9B + 2 + 1 + 1）
    kReportTouchPadMode = 17,  // PTP CONFIG feature：Input Mode
    kReportTouchPadFunc = 18,  // PTP CONFIG feature：Surface/Button switch
    kReportTouchPadMax  = 19,  // PTP feature：Contact Count Maximum + Pad Type
    kReportTouchPadHQA  = 20,  // PTP feature：认证 blob（256B）
    // v1.11：USB 快捷键键盘——ID 避开 1..20 全部历史编号（Windows 缓存驱动
    // 可能还记着旧布局，复用 3/16 等废弃 ID 有解析错位风险）。
    kReportKeyboard     = 21,  // §2.12 IN 键盘（修饰1 + reserved1 + 按键数组6）
};

// 低频传感器 Report ID 列表（§2.4，顺序即描述符内的声明顺序）
inline constexpr uint8_t kLowFreqReportIds[] = {
    kReportAls,       kReportProximity,   kReportPressure, kReportOrientation,
    kReportInclinometer, kReportAmbientTemp, kReportHumidity,
    kReportStepCounter,  kReportHeartRate,
};
inline constexpr size_t kLowFreqReportCount =
    sizeof(kLowFreqReportIds) / sizeof(kLowFreqReportIds[0]);

// Report ID(7..15) ↔ 传感器编号（sensors_id.h）双向映射。
//
// 注意 kSensorBatteryTemp(0x16) **不占**独立 Report —— 它含在 §2.8 的电池报告里，
// 因此映射表在这里断开，**不能用「Report ID 减 7」之类的偏移算出来**。
uint8_t sensorIdOfLowFreqReport(uint8_t reportId);  // 非低频 Report ID 返回 0xFF
uint8_t lowFreqReportOfSensorId(uint8_t sensorId);  // 非低频传感器返回 0

// 单报告上限（USB HS 中断端点），§2.1
inline constexpr size_t kMaxReportSize = 1024;

// §2.3 批量样本数上限 N<=16
inline constexpr size_t kMaxImuSamples = 16;
// §2.5 同时上报触点数上限
inline constexpr size_t kMaxContacts = 10;
// §2.7 vendor 命令载荷上限
inline constexpr size_t kMaxVendorPayload = 256;

#pragma pack(push, 1)

// §2.3 Report ID 1 —— IMU（v1.3：Windows 原生传感器「单样本」格式）
// 原 16 样本批量格式（113B）已废弃：Windows SensorsHIDClassDriver 只识别单样本，
// 且要求 State/Event 位于数据之前，否则驱动加载失败（真机实测 Code 10）。
struct ImuSampleReport {
    uint8_t reportId;  // =1
    uint8_t state;     // Sensor State：0=unknown 1=ready 2=not_available 3=error
    uint8_t event;     // Sensor Event：0=unknown 1=high 2=low 3=period_exceeded 4=change
    int16_t x;         // 已换算为 G，按 units.h 指数 -3 定标（raw × 10^-3 g）
    int16_t y;
    int16_t z;
};
static_assert(sizeof(ImuSampleReport) == 9);

// Feature 报告（属性）：由 PC 侧驱动读写，用于启停传感器与配置参数
// v1.5：补 sensorStatus（usage 0x0304）—— 微软《Sensor HID Class Driver》要求
// Feature Report 必含 Reporting State + Sensor Status + Change Sensitivity +
// Report Interval 四项；缺 Sensor Status 真机实测全传感器 TLC Code 10。
struct ImuFeatureReport {
    uint8_t  reportId;          // =1
    uint8_t  reportState;       // 0=no events 1=all events（usage 0x0316）
    uint8_t  sensorStatus;      // 0x0304：data-ready/error 状态位图
    uint16_t sensitivityAbs;    // G，指数 -3（usage 0x030F）
    uint32_t reportIntervalMs;  // 毫秒（usage 0x030E）
    uint8_t  powerState;        // 0=D0 full power（usage 0x0319，v1.6）
};
static_assert(sizeof(ImuFeatureReport) == 10);

// §2.4 Report ID 7..15 —— 低频传感器（v1.2：每个传感器一个独立 Report ID，布局一致）
struct LowFreqReport {
    uint8_t  reportId;  // 7..15，取值即表明传感器类别
    uint8_t  state;     // 0=unknown 1=ready 2=not_available 3=error
    uint8_t  event;     // 0=unknown 1=threshold_high 2=threshold_low 3=period_exceeded 4=change
    uint8_t  reserved;  // 恒 0（原 sensorId 位；Report ID 已区分类别，无需再带）
    uint64_t tsNs;
    int32_t  v0;        // 定标整数，指数由该 TLC 的 UNIT/UNIT_EXPONENT 声明
    int32_t  v1;        // 方向/倾角用；标量传感器填 0
    int32_t  v2;
};
static_assert(sizeof(LowFreqReport) == 24);

// §2.5 Report ID 3 —— Digitizer 触控/笔
struct DigitizerHeader {
    uint8_t  reportId;      // =3
    uint8_t  flags;         // bit0=笔在量程 bit1=笔尖接触 bit2=橡皮擦 bit3=桶按钮
    uint8_t  contactCount;  // 0..10
    uint8_t  reserved;      // 补齐到 4 字节，使 tsNs 4 字节对齐
    uint64_t tsNs;
};
static_assert(sizeof(DigitizerHeader) == 12);

struct DigitizerContact {   // 8 字节
    uint16_t contactId;
    uint16_t x;             // 归一化 0..65535
    uint16_t y;
    uint16_t pressure;      // 0..65535（笔）；手指固定 0x7FFF
};
static_assert(sizeof(DigitizerContact) == 8);

struct PenExtra {           // 10 字节
    int16_t  tiltX;
    int16_t  tiltY;
    uint16_t orientation;   // 方位角 0..65535
    uint32_t reserved;
};
static_assert(sizeof(PenExtra) == 10);

// §2.6 Report ID 4 —— Consumer 按键（v1.2：usage 位图）
//
// 原「键编号 + 状态」编码无法被 Windows 识别为多媒体键（只能由自研宿主翻译后注入），
// 故改为位图：每一位对应一个 Consumer Page(0x0C) Usage，按下与释放**均发全量位图**，
// PC 侧按位比对得到边沿事件。
struct ConsumerKey {
    uint8_t  reportId;   // =4
    uint16_t keyBitmap;  // 位定义见 ConsumerKeyBit
    uint8_t  reserved;   // 恒 0
};
static_assert(sizeof(ConsumerKey) == 4);

// §2.10 Report ID 2 —— 触控板（**相对位移**鼠标，复用 v1.2 废弃的 ID 2）
//
// 与 §2.5 Digitizer 的本质区别：这里是 Δx/Δy **相对位移**，Digitizer 是绝对坐标。
// 触控板场景下手机不显示 PC 画面、触摸只驱动光标，**绝不能复用 Digitizer 的
// Report** —— 两种语义混用会让 PC 光标在「绝对跳转」与「相对移动」之间反复横跳。
//
// 手势（单指移动/点按、双指滚动、双指右键、三指中键）在**手机端**识别完成，
// 本报告只承载最终结果，用以把上行带宽与延迟压到最低（鼠标本就该是
// 「多次小增量」语义，而非一次大跳）。
struct MouseReport {
    uint8_t reportId;   // =2
    uint8_t buttons;    // 位定义见 MouseButtonBit
    int8_t  dx;         // 水平相对位移（右为正）
    int8_t  dy;         // 垂直相对位移（下为正）
    int8_t  wheel;      // 垂直滚动（上滚为正）
    int8_t  pan;        // 水平滚动（右滚为正）
};
static_assert(sizeof(MouseReport) == 6);

// §2.10 触控板按键位（MouseReport.buttons）
enum : uint8_t {
    kMouseButtonLeft   = 1u << 0,
    kMouseButtonRight  = 1u << 1,
    kMouseButtonMiddle = 1u << 2,
};

// 单次位移的限幅：int8 有符号范围，超出时由端侧拆成多次报告发送
inline constexpr int kMaxMouseDelta = 127;

// §2.6 位图定义（位序号 → Consumer Page Usage）
// v1.7c：新增 Zoom In / Zoom Out（位 7/8）—— 触控板双指捏合缩放映射。
// 位图 u16 原有 0..6 位；扩到 8 位仍 < u16 上限，报告长度不变（非 breaking）。
enum ConsumerKeyBit : uint8_t {
    kBitVolumeUp   = 0,  // 0x0C:0xE9
    kBitVolumeDown = 1,  // 0x0C:0xEA
    kBitMute       = 2,  // 0x0C:0xE2
    kBitPower      = 3,  // 0x0C:0x30
    kBitPlayPause  = 4,  // 0x0C:0xCD
    kBitPrevTrack  = 5,  // 0x0C:0xB6
    kBitNextTrack  = 6,  // 0x0C:0xB5
    kBitZoomIn     = 7,  // 0x0C:0x0227（v1.7c 捏合放大）
    kBitZoomOut    = 8,  // 0x0C:0x0228（v1.7c 捏合缩小）
};

// §2.7 Report ID 5 —— Vendor 控制 OUT（PC -> 手机）
struct VendorCommandHeader {
    uint8_t  reportId;    // =5
    uint8_t  cmd;
    uint8_t  seq;
    uint8_t  reserved;
    uint32_t payloadLen;  // 0..256
};
static_assert(sizeof(VendorCommandHeader) == 8);

enum : uint8_t {
    kCmdVibrate        = 0x01,  // payload {u16 durationMs, u8 amplitude}
    kCmdVibrateStop    = 0x02,  // payload 空
    kCmdTorch          = 0x03,  // payload {u8 on, u8 level}
    kCmdIrSend         = 0x04,  // payload {u32 freqHz, u16 patternLen, u16 pattern[]}
    kCmdSetSensorMask  = 0x10,  // payload {u64 mask}
    kCmdSetSampleRate  = 0x11,  // payload {u8 sensorId, u32 rateHz}
    kCmdSetDisplayMode = 0x12,  // payload {u8 mode}
    kCmdHeartbeat      = 0x7F,  // payload 可空
};

// §2.7 Report ID 5 —— 状态上报（手机 -> PC，Input / Feature 双用途）
// v1.1：moduleMask 为 64 位（§2.9，传感器 bit0..31 + 模块 bit32..37），尾部新增 lastSeq
struct VendorStatus {
    uint8_t  reportId;    // =5
    uint8_t  status;      // 0=idle 1=running 2=error 3=降级(USB2.0)
    uint8_t  linkSpeed;   // 0=unknown 1=full 2=high 3=super 4=super_plus
    uint64_t moduleMask;  // 已启用模块位图（§2.9，见 sensors_id.h ModuleBit）
    uint32_t errorCode;
    uint64_t uptimeMs;
    uint8_t  lastSeq;     // 最后执行的控制命令 seq 回显
};
static_assert(sizeof(VendorStatus) == 24);

// §2.8 Report ID 6 —— Battery System
struct BatteryReport {
    uint8_t  reportId;    // =6
    uint8_t  chargePct;   // 0..100
    uint8_t  state;       // 0=unknown 1=charging 2=discharging 3=full 4=not_charging
    uint16_t voltageMv;
    int16_t  currentMa;   // 负 = 放电
    int16_t  tempCx10;    // 0.1℃ 定标
    uint32_t remainingMin;  // 0xFFFFFFFF = 未知
};
static_assert(sizeof(BatteryReport) == 13);

#pragma pack(pop)

// linkSpeed 取值（§2.7）
enum LinkSpeed : uint8_t {
    kSpeedUnknown   = 0,
    kSpeedFull      = 1,
    kSpeedHigh      = 2,
    kSpeedSuper     = 3,
    kSpeedSuperPlus = 4,
};

// 运行状态（§2.7）
enum RunStatus : uint8_t {
    kStatusIdle     = 0,
    kStatusRunning  = 1,
    kStatusError    = 2,
    kStatusDegraded = 3,  // 降级运行在 USB 2.0
};

// 低功耗/低频 state 取值（§2.4）
enum SampleState : uint8_t {
    kStateUnknown      = 0,
    kStateReady        = 1,
    kStateNotAvailable = 2,
    kStateError        = 3,
};

// §2.4 event 取值
enum SampleEvent : uint8_t {
    kEventUnknown        = 0,
    kEventThresholdHigh  = 1,
    kEventThresholdLow   = 2,
    kEventPeriodExceeded = 3,
    kEventChange         = 4,
};

// §2.6 Consumer 键值
enum ConsumerKeyCode : uint8_t {
    kKeyVolumeUp   = 0,
    kKeyVolumeDown = 1,
    kKeyMute       = 2,
    kKeyPower      = 3,
    kKeyPlayPause  = 4,
    kKeyPrevTrack  = 5,
    kKeyNextTrack  = 6,
};

// §2.4 剩余时间未知
inline constexpr uint32_t kRemainingMinUnknown = 0xFFFFFFFFu;
// §2.5 手指压力固定值
inline constexpr uint16_t kFingerPressureFixed = 0x7FFFu;

const char* linkSpeedName(uint8_t s);
const char* reportIdName(uint8_t id);

// ------------------------------------------------------------ 报告长度 ----
// 各报告完整长度（含首字节 Report ID）；IMU 按定长 N=16 发送（HID 报告必须定长），
// 实际有效样本数由 sampleCount 声明，多余位填 0。
enum : uint32_t {
    kSizeImuBatchReport  = 9u,   // v1.3：单样本（1+1+1+2+2+2）；原 113B 批量格式已废弃
    kSizeMouseReport     = 6u,   // v1.4：触控板（1+1+1+1+1+1）
    kSizeLowFreqReport   = 24u,                                       // 24
    kSizeDigitizerReport = 12u + 10u * 8u + 10u,                      // 102
    kSizeConsumerReport  = 4u,                                        // v1.2：位图（1+2+1）
    // v1.8：PTP（imbushuo/mac-precision-touchpad 布局）
    kPtpMaxContacts      = 5u,
    kPtpFingerBytes      = 9u,   // 1B(conf|tip|pad) + 4B contactId(u32) + 2B X + 2B Y
    kSizePtpReport       = 50u,  // 1(rid) + 5*9(fingers) + 2(scanTime) + 1(count) + 1(buttons)
    kPtpLogicalMaxX      = 20000u,
    kPtpLogicalMaxY      = 12000u,
    kSizeVendorOutReport = 8u + 256u,                                 // 264（最大）
    kSizeVendorStatusRep = 24u,                                       // 24（v1.1：u64 掩码 + lastSeq）
    kSizeBatteryReport   = 13u,                                       // 13
};

// 指定 Report ID 的完整报告长度（含 Report ID 字节）；未知 ID 返回 0
uint32_t reportSizeById(uint8_t reportId);

// ------------------------------------------------------------ 打包/解析 ----
// §2.3：xyz 为 [x0,y0,z0, x1,y1,z1, ...]，Android 原始单位；内部按 units.h 换算+定标。
// 返回写入字节数（= kSizeImuBatchReport），失败返回 0。
size_t packImuBatch(uint8_t* dst, size_t cap, uint8_t sensorId, uint8_t flags,
                    uint32_t periodNs, uint64_t baseTsNs,
                    const float* xyz, size_t sampleCount, int32_t accuracy);

// §2.4：v0..v2 为 Android 原始单位浮点值（未使用的轴传 0）。
// v1.2：reportId 取 kReportAls..kReportHeartRate（7..15），函数内部据此选定单位与定标。
size_t packLowFreqById(uint8_t* dst, size_t cap, uint8_t reportId, uint8_t state,
                       uint8_t event, uint64_t tsNs, float v0, float v1, float v2);

// §2.7 状态上报
size_t packVendorStatus(uint8_t* dst, size_t cap, uint8_t status, uint8_t linkSpeed,
                        uint64_t moduleMask, uint32_t errorCode, uint64_t uptimeMs,
                        uint8_t lastSeq);

// §2.8 电池
size_t packBattery(uint8_t* dst, size_t cap, uint8_t chargePct, uint8_t state,
                   uint16_t voltageMv, int16_t currentMa, int16_t tempCx10,
                   uint32_t remainingMin);

// §2.6 按键位图（v1.2）：keyBitmap 的位定义见 ConsumerKeyBit
size_t packConsumerBitmap(uint8_t* dst, size_t cap, uint16_t keyBitmap);

// §2.10 触控板：打包相对位移鼠标报告。
// 入参用 int 便于端侧直接传计算结果，内部限幅到 [-127, 127]（见 kMaxMouseDelta）；
// 需要更大位移时由端侧拆成多次调用 —— 鼠标本就是「多次小增量」语义。
// 返回写入字节数（= kSizeMouseReport），失败返回 0。
size_t packMouse(uint8_t* dst, size_t cap, uint8_t buttons,
                 int dx, int dy, int wheel, int pan);

// §2.6 位序号 → Consumer Page Usage（供描述符生成，避免两处各写一份映射）
uint16_t consumerKeyBitUsage(uint8_t bit);

// §2.7 OUT 报告解析：cmd/seq/payload 输出到 payload(最多 cap 字节)，返回 payload 长度；
// 非法报告返回 false
bool parseVendorOut(const uint8_t* report, size_t len, uint8_t& cmd, uint8_t& seq,
                    uint8_t* payload, size_t cap, size_t& payloadLen);

}  // namespace apx
