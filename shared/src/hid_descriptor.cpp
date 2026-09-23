// HID 报告描述符生成实现。见 hid_descriptor.h 与 docs/PROTOCOL.md §2。
//
// v1.2：低频传感器由「共用一个 Report ID 2」拆成**各自独立 TLC + 独立 Report ID 7..15**，
// 共 14 个 TLC。原因是 Windows SensorsHIDClassDriver 按 TLC 枚举传感器、并需逐个下发
// report interval —— 共用一个 Report ID 会让它们全部无法被原生识别。
#include "apx/hid_descriptor.h"

#include "apx/sensors_id.h"
#include "apx/units.h"

namespace apx {
namespace {

// ---- HID item 编码 --------------------------------------------------------
enum ItemType : uint8_t { kTypeMain = 0, kTypeGlobal = 1, kTypeLocal = 2 };

enum MainTag : uint8_t {
    kTagInput = 0x08, kTagOutput = 0x09, kTagCollection = 0x0A,
    kTagFeature = 0x0B, kTagEndCollection = 0x0C,
};
enum GlobalTag : uint8_t {
    kTagUsagePage = 0x00, kTagLogicalMin = 0x01, kTagLogicalMax = 0x02,
    kTagPhysicalMin = 0x03, kTagPhysicalMax = 0x04, kTagUnitExp = 0x05,
    kTagUnit = 0x06, kTagReportSize = 0x07, kTagReportId = 0x08, kTagReportCount = 0x09,
};
enum LocalTag : uint8_t { kTagUsage = 0x00, kTagUsageMin = 0x01, kTagUsageMax = 0x02 };

// Main item 数据字节（Input/Output/Feature 的标志位）
//
// 位含义（HID 1.11 §6.2.2.5，注意 bit0 是**反的**）：
//   bit0: 0 = Data，1 = Constant
//   bit1: 0 = Array，1 = Variable
//   bit2: 0 = Absolute，1 = Relative
enum : uint8_t {
    kDataVar  = 0x02,  // Data, Variable, Absolute（变量字段）
    kConstVar = 0x03,  // Constant（填充，OS 忽略，端侧按偏移解析）
    kDataVarRel = 0x06, // Data, Variable, **Relative** —— 触控板的相对位移必须用它
    kDataArr  = 0x00,  // Data, Array, Absolute —— 标准键盘的按键数组用
};

class Builder {
public:
    std::vector<uint8_t> out;

    void item(uint8_t type, uint8_t tag, uint32_t data, uint8_t bytes) {
        const uint8_t sizeCode = (bytes == 4) ? 3 : static_cast<uint8_t>(bytes);
        out.push_back(static_cast<uint8_t>((tag << 4) | (type << 2) | sizeCode));
        for (uint8_t i = 0; i < bytes; ++i) {
            out.push_back(static_cast<uint8_t>((data >> (8 * i)) & 0xFFu));
        }
    }
    static uint8_t bytesForU32(uint32_t v) {
        if (v <= 0xFFu) return 1;
        if (v <= 0xFFFFu) return 2;
        return 4;
    }
    static uint8_t bytesForI32(int32_t v) {
        if (v >= 0) return bytesForU32(static_cast<uint32_t>(v));
        if (v >= -128) return 1;
        if (v >= -32768) return 2;
        return 4;
    }

    void usagePage(uint16_t p) { item(kTypeGlobal, kTagUsagePage, p, bytesForU32(p)); }
    void usage(uint16_t u)     { item(kTypeLocal,  kTagUsage,     u, bytesForU32(u)); }
    // 连续 usage 区间（如鼠标按键 1..3）。比逐个 usage + ReportCount(1) 更紧凑，
    // 且是 Windows 识别标准鼠标/键盘的惯用写法。
    void usageMin(uint16_t u)  { item(kTypeLocal,  kTagUsageMin,  u, bytesForU32(u)); }
    void usageMax(uint16_t u)  { item(kTypeLocal,  kTagUsageMax,  u, bytesForU32(u)); }
    void logicalMin(int32_t v) { item(kTypeGlobal, kTagLogicalMin, static_cast<uint32_t>(v), bytesForI32(v)); }
    void logicalMax(int32_t v) { item(kTypeGlobal, kTagLogicalMax, static_cast<uint32_t>(v), bytesForI32(v)); }
    void physicalMin(int32_t v){ item(kTypeGlobal, kTagPhysicalMin, static_cast<uint32_t>(v), bytesForI32(v)); }
    void physicalMax(int32_t v){ item(kTypeGlobal, kTagPhysicalMax, static_cast<uint32_t>(v), bytesForI32(v)); }
    void unit(uint32_t u)      { item(kTypeGlobal, kTagUnit, u, bytesForU32(u)); }
    // v1.8 修复：UNIT_EXPONENT 是 4 bit 补码（合法域 -8..+7），单字节里有效数据在
    // 低 nibble——原实现 push 0xFE（-2 的 8bit 补码）导致 unit 声明非法
    //（微软 sample：UNIT_EXPONENT(-2) → 0x55,0x0E）。同时也是传感器 FAILED_START
    // 的头号嫌疑（tsNs 的 -9 越界问题见 hid_sensor_probe 后续验证）。
    void unitExp(int8_t e)     { item(kTypeGlobal, kTagUnitExp, static_cast<uint32_t>(e & 0x0F), 1); }
    void reportSize(uint32_t n){ item(kTypeGlobal, kTagReportSize, n, bytesForU32(n)); }
    void reportCount(uint32_t n){item(kTypeGlobal, kTagReportCount, n, bytesForU32(n)); }
    void reportId(uint8_t id)  { item(kTypeGlobal, kTagReportId, id, 1); }
    void input(uint8_t flags)  { item(kTypeMain, kTagInput, flags, 1); }
    void output(uint8_t flags) { item(kTypeMain, kTagOutput, flags, 1); }
    void feature(uint8_t flags){ item(kTypeMain, kTagFeature, flags, 1); }
    void collection(uint8_t c) { item(kTypeMain, kTagCollection, c, 1); }
    void endCollection()       { item(kTypeMain, kTagEndCollection, 0, 0); }

    // 声明 n 字节填充（元数据/复用载荷）
    void padBytes(uint32_t n) {
        reportSize(8);
        reportCount(n);
        logicalMin(0);
        logicalMax(255);
        input(kConstVar);
    }
    void padBits(uint32_t n) {
        reportSize(1);
        reportCount(n);
        logicalMin(0);
        logicalMax(1);
        input(kConstVar);
    }
};

// ============================ TLC 1：IMU（Windows 原生传感器格式）==========
// v1.3：为被 Windows SensorsHIDClassDriver 原生识别，本 TLC 必须提供：
//   Feature Report —— Report State / Change Sensitivity / Report Interval（驱动用于启停与配置设备）
//   Input   Report —— Sensor State + Sensor Event + 三轴数据
// 缺失这些属性会导致驱动加载失败（真机实测 Code 10 = CM_PROB_FAILED_START）。
// 因此原「16 样本批量」格式改为单样本，详见 docs/PROTOCOL.md §2.3。
// v1.5：对照微软《Sensor HID Class Driver》文档，Feature Report 必含**四项**
//   Reporting State + **Sensor Status** + Change Sensitivity + Report Interval。
//   缺 Sensor Status 实测全部传感器 TLC Code 10（STATUS_INVALID_PARAMETER），
//   且 GET_REPORT 登记链路（GADGET_HID_WRITE_GET_REPORT）正常 —— 排除通信问题。
// 长度：Feature = 1 + (8+8+16+32)/8 = 9B；Input = 1 + 1 + 1 + 6 = 9B
void buildTlcImu(Builder& b) {
    const UnitSpec& spec = unitSpec(kSensorAccel);

    b.usagePage(kPageSensor);
    b.usage(kUsageAccel3D);
    b.collection(0x01);  // Application
    b.reportId(kReportImuBatch);

    // ---- Feature：属性（驱动据此启停传感器、设置灵敏度与上报间隔）----
    // v1.5 字段顺序对齐微软文档：Reporting State → Sensor Status →
    // Change Sensitivity → Report Interval。
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(1);
    b.usage(kUsagePropReportState);
    b.feature(kDataVar);

    b.usage(kUsagePropSensorStatus);
    b.feature(kDataVar);

    b.logicalMax(65535);
    b.reportSize(16);
    b.unit(spec.hidUnit);
    b.unitExp(spec.exponent);
    b.usage(kUsagePropSensitivityAbs);
    b.feature(kDataVar);

    b.reportSize(32);
    b.unit(kUnitMillisecond);
    b.unitExp(0);
    // v1.6：微软模板 Report Interval 的 Logical Max 为 32 位 0xFFFFFFFF
    //（有符号即 -1；HID 符号压缩后单字节 0xFF 表达同一语义）
    b.logicalMax(-1);
    b.usage(kUsagePropReportInterval);
    b.feature(kDataVar);

    // v1.6：Power State（0=D0 full power）—— 实测固件普遍声明
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(1);
    b.usage(kUsagePropPowerState);
    b.feature(kDataVar);
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(2);
    b.usage(kUsageSensorState);
    b.usage(kUsageSensorEvent);
    b.input(kDataVar);

    b.logicalMin(-32768);
    b.logicalMax(32767);
    b.reportSize(16);
    b.reportCount(1);
    b.unit(spec.hidUnit);
    b.unitExp(spec.exponent);
    b.usage(kUsageAccelAxisX);
    b.input(kDataVar);
    b.usage(kUsageAccelAxisY);
    b.input(kDataVar);
    b.usage(kUsageAccelAxisZ);
    b.input(kDataVar);
    b.unit(kUnitNone);
    b.unitExp(0);

    b.endCollection();
}

// 低频传感器 TLC 注册表：Report ID + TLC Usage + 单位来源传感器编号
// v1.5：dataUsages/dataCount —— Input 数据字段改用 Sensor Page 标准 Data Field
// usage（微软文档要求；vendor-page 承载实测 Code 10）。标量 1 个字段，三轴 3 个。
struct LowFreqTlcSpec {
    uint8_t  reportId;
    uint16_t tlcUsage;
    uint8_t  unitSource;  // 供 unitSpec() 查单位，取值见 sensors_id.h
    uint16_t dataUsages[3];
    uint8_t  dataCount;
};

const LowFreqTlcSpec kLowFreqTlcs[] = {
    { kReportAls,          kUsageAls,               kSensorLight,
      { kUsageLightIllum, 0, 0 }, 1 },
    { kReportProximity,    kUsageProximity,         kSensorProximity,
      { kUsageHumanPresence, 0, 0 }, 1 },
    { kReportPressure,     kUsagePressure,          kSensorPressure,
      { kUsageAtmPressure, 0, 0 }, 1 },
    { kReportOrientation,  kUsageDeviceOrientation, kSensorOrientation,
      { kUsageMagnFluxX, kUsageMagnFluxY, kUsageMagnFluxZ }, 3 },
    { kReportInclinometer, kUsageInclinometer3D,    kSensorInclinometer,
      { kUsageTiltX, kUsageTiltY, kUsageTiltZ }, 3 },
    { kReportAmbientTemp,  kUsageTemperature,       kSensorDeviceTemp,
      { kUsageEnvTemperature, 0, 0 }, 1 },
    { kReportHumidity,     kUsageHumidity,          kSensorHumidity,
      { kUsageAtmHumidity, 0, 0 }, 1 },
    // 计步器 / 心率在 Sensors Page 没有专用 Usage，按规范走 Custom（仍用 Sensor 页根 Usage）
    { kReportStepCounter,  kUsageSensor,            kSensorStepCounter,
      { kUsageCustomValue0, 0, 0 }, 1 },
    { kReportHeartRate,    kUsageSensor,            kSensorHeartRate,
      { kUsageCustomValue1, 0, 0 }, 1 },
};
constexpr size_t kLowFreqTlcCount = sizeof(kLowFreqTlcs) / sizeof(kLowFreqTlcs[0]);

// ============ TLC 2..10：低频传感器（v1.2：各自独立 TLC，Report ID 7..15）====
// 每个传感器一个 TLC：TLC Usage 取 Sensors Page 的标准值（Windows 据此判定类型），
// 单位由该 TLC 自身的 UNIT/UNIT_EXPONENT 静态声明。
// 布局统一 24 字节（见 hid_layout.h 的 LowFreqReport）：
//   1(reportId) + 1(state) + 1(event) + 1(reserved) + 8(tsNs) + 12(value0..2)
// v1.5：
//   - Feature 补 Sensor Status（微软文档四项必需之一，真机缺失即 Code 10）
//   - Input 数据字段从 Vendor Page 改为 **Sensor Page 标准 Data Field usage**
//     （微软文档：数据字段必须声明在 Sensor Page；低频全灭的第二个根因）
void buildTlcLowFreqOne(Builder& b, const LowFreqTlcSpec& s,
                        const UnitSpec& spec) {
    b.usagePage(kPageSensor);
    b.usage(s.tlcUsage);
    b.collection(0x01);
    b.reportId(s.reportId);

    // ---- Feature：驱动据此启停该传感器并配置参数 ----
    // v1.5：字段顺序与四项组成对齐微软文档（同 buildTlcImu）。
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(1);
    b.usage(kUsagePropReportState);
    b.feature(kDataVar);

    b.usage(kUsagePropSensorStatus);
    b.feature(kDataVar);

    b.logicalMax(65535);
    b.reportSize(16);
    b.unit(spec.hidUnit);
    b.unitExp(spec.exponent);
    b.usage(kUsagePropSensitivityAbs);
    b.feature(kDataVar);

    b.reportSize(32);
    b.unit(kUnitMillisecond);
    b.unitExp(0);
    // v1.6：同 IMU —— Logical Max 32 位语义 + Power State
    b.logicalMax(-1);
    b.usage(kUsagePropReportInterval);
    b.feature(kDataVar);

    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(1);
    b.usage(kUsagePropPowerState);
    b.feature(kDataVar);

    // ---- Input：state / event / reserved ----
    // v1.7：State 与 Event 必须合并为一个 reportCount(2) 字段声明——
    // 分开声明时 hidsensor 启动报 0xC0000495（CM_PROB_FAILED_START），
    // 与 buildTlcImu 的已验证写法保持一致（真机 2026-09-21 全灭根因）。
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(2);
    b.usage(kUsageSensorState);
    b.usage(kUsageSensorEvent);
    b.input(kDataVar);
    b.padBytes(1);  // reserved

    // ---- Input：时间戳（秒 × 10^-4 = 100µs，微软 HIDSensor 模板语义）----
    // v1.9 修正：HID Unit Exponent 是 4bit nibble，合法域 -8..+7，**-9 越界**
    // （-9 & 0x0F = 7 会被 Windows 解码成 10^+7，语义完全错乱）。改用 -4。
    b.usage(kUsageSensorTimestamp);
    b.logicalMin(0);
    b.logicalMax(0x7FFFFFFF);
    b.reportSize(64);
    b.reportCount(1);
    b.unit(kUnitSecond);
    b.unitExp(-4);
    b.input(kDataVar);
    b.unit(kUnitNone);
    b.unitExp(0);

    // ---- Input：value0..2（Sensor Page 标准 Data Field）----
    // v1.5：不再用 Vendor Page 承载。每个传感器使用其标准 Data Field usage：
    //   标量 1 个字段（v1/v2 两槽填充）；三轴 3 个字段。单位沿用 spec。
    // 注意 padBytes 会重置 reportSize，因此每轮都重新声明。
    b.logicalMin(INT32_MIN);
    b.logicalMax(INT32_MAX);
    for (int i = 0; i < 3; ++i) {
        b.reportSize(32);
        b.reportCount(1);
        if (i < s.dataCount) {
            b.usage(s.dataUsages[i]);
            b.input(kDataVar);
        } else {
            b.padBytes(4);  // 未用槽填充
        }
    }
    b.endCollection();
}

// ============================ TLC 3：Digitizer =============================
// 1 + 1(flags) + 1(count) + 1(reserved) + 8(tsNs) + 80(触点) + 10(笔) = 102 字节
void buildTlcDigitizer(Builder& b) {
    b.usagePage(kPageDigitizer);
    b.usage(kUsageTouchScreen);
    b.collection(0x01);
    b.reportId(kReportDigitizer);

    // flags 位图：bit0=InRange bit1=TipSwitch bit2=Eraser bit3=Barrel，bit4..7 填充
    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(1);
    b.usage(kUsageInRange);
    b.input(kDataVar);
    b.usage(kUsageTipSwitch);
    b.input(kDataVar);
    b.usage(kUsageEraser);
    b.input(kDataVar);
    b.usage(kUsageBarrelSwitch);
    b.input(kDataVar);
    b.padBits(4);

    // contactCount
    b.logicalMin(0);
    b.logicalMax(static_cast<int32_t>(kMaxContacts));
    b.reportSize(8);
    b.reportCount(1);
    b.usage(kUsageContactCount);
    b.input(kDataVar);

    // reserved + tsNs
    b.padBytes(1);
    b.padBytes(8);

    // 触点数组：contactId / X / Y / Pressure，各 10 项
    b.logicalMin(0);
    b.logicalMax(65535);
    b.reportSize(16);
    b.reportCount(kMaxContacts);
    b.usage(kUsageContactId);
    b.input(kDataVar);

    // X / Y：归一化 0..65535，声明在 Generic Desktop 页（绝对坐标）
    b.usagePage(kPageGenericDesktop);
    b.unit(kUnitCentiMeter);
    b.unitExp(0);
    b.physicalMin(0);
    b.physicalMax(65535);
    b.usage(kUsageAxisX);
    b.input(kDataVar);
    b.usage(kUsageAxisY);
    b.input(kDataVar);
    b.unit(kUnitNone);
    b.unitExp(0);
    b.physicalMin(0);
    b.physicalMax(0);

    // 压力：v1.2 改用标准 Usage。Logical Max 直接声明 65535 就满足笔的高精度
    // 需求，没有必要为了精度退回厂商自定义（那会让 Windows 无法免驱识别为笔）。
    // **reportCount 必须与上面的 X/Y 保持一致**（每个触点各有一个压力值），
    // 否则报告长度会少 kMaxContacts×2 字节，与 hid_layout.h 的常量对不上。
    b.usagePage(kPageDigitizer);
    b.logicalMin(0);
    b.logicalMax(65535);
    b.reportSize(16);
    b.reportCount(kMaxContacts);
    b.usage(kUsageTipPressure);
    b.input(kDataVar);

    // 笔附加：tiltX / tiltY / orientation
    b.usagePage(kPageDigitizer);
    b.logicalMin(-32768);
    b.logicalMax(32767);
    b.reportSize(16);
    // 每个倾斜字段单独声明：count=1 + 两次 input()，共 2×16 位 = 4B。
    // 若写成 count=2 则必须合并为一次 input()，否则两个字段会被声明两遍（多 4B）。
    b.reportCount(1);
    b.usage(kUsageXTilt);
    b.input(kDataVar);
    b.usage(kUsageYTilt);
    b.input(kDataVar);
    b.logicalMin(0);
    b.logicalMax(65535);
    b.reportCount(1);
    b.usage(kUsageAzimuth);
    b.input(kDataVar);
    // reserved u32
    b.reportSize(32);
    b.reportCount(1);
    b.input(kConstVar);

    b.endCollection();
}

// ============================ TLC 11：Consumer =============================
// v1.2：改为 usage 位图（1+2+1 = 4 字节）。
// 原「键编号 + 状态」编码无法被 Windows 识别为多媒体键，只能由自研宿主翻译后注入；
// 位图让每个按键直接对应一个标准 Consumer Usage，系统原生识别。
void buildTlcConsumer(Builder& b) {
    b.usagePage(kPageConsumer);
    b.usage(kUsageConsumerControl);
    b.collection(0x01);
    b.reportId(kReportConsumer);

    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(1);
    // 位序必须与 hid_layout.h 的 ConsumerKeyBit 一致
    b.usage(kUsageVolumeUp);
    b.input(kDataVar);
    b.usage(kUsageVolumeDown);
    b.input(kDataVar);
    b.usage(kUsageMute);
    b.input(kDataVar);
    b.usage(kUsagePower);
    b.input(kDataVar);
    b.usage(kUsagePlayPause);
    b.input(kDataVar);
    b.usage(kUsageScanPrevTrack);
    b.input(kDataVar);
    b.usage(kUsageScanNextTrack);
    b.input(kDataVar);
    b.padBits(9);   // 7 位已用，补齐到 16 位 —— 即 ConsumerKey.keyBitmap 占 2 字节
    b.padBytes(1);  // reserved，对应 ConsumerKey.reserved，使报告总长为 4
    b.endCollection();
}

// ======================== TLC 21：USB 键盘（§2.12）=========================
// 标准 Boot 风格键盘：修饰键位图 1B（左Ctrl..右GUI，每位一键）+ reserved 1B +
// 按键数组 6B（HID Keyboard usage code，0=空槽），含 Report ID 共 9B。
// v1.11：用户点名的「快捷键键盘」——App 端按键网格把 Ctrl+C 等组合键翻译成
// 修饰键位图 + usage code 一次上报。Boot 形态没有 Unit/Physical Range 语义，
// 不会踩传感器/数位屏那类 hidparse 除零坑；Windows 100% 原生支持。
// usage code 常量在 Android 侧 KeyboardActivity 内定义（发送方职责）。
void buildTlcKeyboard(Builder& b) {
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageKeyboard);
    b.collection(0x01);              // Application
    b.reportId(kReportKeyboard);

    // 修饰键位图：Keyboard Page 0xE0..0xE7（左Ctrl/Shift/Alt/GUI + 右侧）
    b.usagePage(kPageKeyboard);
    b.usageMin(0xE0);
    b.usageMax(0xE7);
    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(8);
    b.input(kDataVar);

    // reserved 1 字节（boot 键盘规范要求，恒 0）
    b.reportSize(8);
    b.reportCount(1);
    b.input(kConstVar);

    // 按键数组：6 个槽位，Keyboard Page 0x00..0x65（0 = 无按键）
    b.usageMin(0x00);
    b.usageMax(0x65);
    b.logicalMin(0);
    b.logicalMax(0x65);
    b.reportSize(8);
    b.reportCount(6);
    b.input(kDataArr);
    b.endCollection();
}

// ==================== TLC 7：游戏手柄（§2.13）============================
// 标准 HID Gamepad（Generic Desktop / Game Pad 0x05），Windows 免驱识别为
// 「USB 游戏控制器」。16 按钮位图 + 4 轴（左摇杆 X/Y、右摇杆 Rx/Ry），
// 轴为 8bit 有符号（-127..127，0=回中）。报告长度 1+2+4=7B。
void buildTlcGamepad(Builder& b) {
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageGamepad);
    b.collection(0x01);              // Application
    b.reportId(kReportGamepad);

    // 16 按钮位图：Button Page 1..16，各 1 bit
    b.usagePage(kPageButton);
    b.usageMin(kUsageButton1);
    b.usageMax(kUsageButton1 + 15);
    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(16);
    b.input(kDataVar);

    // 4 轴：X/Y/Rx/Ry，8bit 有符号（usage 不连续，逐个声明）
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageAxisX);
    b.usage(kUsageAxisY);
    b.usage(kUsageAxisRx);
    b.usage(kUsageAxisRy);
    b.logicalMin(-127);
    b.logicalMax(127);
    b.reportSize(8);
    b.reportCount(4);
    b.input(kDataVar);

    b.endCollection();
}

// ======================== TLC 2：触控板鼠标（§2.10）========================
// 相对位移鼠标，复用 v1.2 废弃的 Report ID 2。
//
// **为什么必须独立成 TLC、不能并入 Digitizer**：两者语义相反 ——
// Digitizer 是**绝对坐标**（归一化到虚拟屏），这里要的是**相对位移**。
// Windows 对「绝对」与「相对」走的是完全不同的处理路径，混在同一集合里
// 会让光标在「跳转到某点」与「移动 N 像素」之间反复横跳。
//
// 与标准鼠标描述符一致的部分：按键用 Usage 区间 1..3 + 5 位填充；
// 不同之处是 X/Y/Wheel/Pan **都是 Relative**，且位移幅度由手机端手势引擎
// （灵敏度 + 加速度）算好后限幅 —— 协议只承载增量，要更大位移就拆多次发送。
// 报告长度：按键 1 + X/Y 2 + 滚轮 1 + 水平滚动 1 = 5B，加 Report ID 共 6B，
// 与 hid_layout.h 的 kSizeMouseReport 对应。
void buildTlcMouse(Builder& b) {
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageMouse);
    b.collection(0x01);              // Application
    b.reportId(kReportMouse);
    b.usage(kUsagePointer);
    b.collection(0x00);              // Physical

    // ---- 按键：3 位（左/右/中）+ 5 位填充 = 1 字节 ----
    // 位序必须与 hid_layout.h 的 kMouseButton* 一致：bit0 左 / bit1 右 / bit2 中
    b.usagePage(kPageButton);
    b.usageMin(kUsageButton1);
    b.usageMax(kUsageButton1 + 2);
    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(3);
    b.input(kDataVar);
    b.reportCount(5);
    b.input(kConstVar);              // 填充到整字节

    // ---- 相对位移 X / Y（各 1 字节，有符号）----
    b.usagePage(kPageGenericDesktop);
    b.logicalMin(-kMaxMouseDelta);
    b.logicalMax(kMaxMouseDelta);
    b.reportSize(8);
    b.reportCount(2);
    b.usage(kUsageAxisX);
    b.usage(kUsageAxisY);
    b.input(kDataVarRel);            // ★ 相对，不是绝对

    // ---- 垂直滚动 ----
    b.reportCount(1);
    b.usage(kUsageWheel);
    b.input(kDataVarRel);

    // ---- 水平滚动 ----
    b.usage(kUsageAcPan);
    b.input(kDataVarRel);

    b.endCollection();               // Physical
    b.endCollection();               // Application
}

// ============================ TLC 5：Vendor 控制与状态 =====================
// OUT（PC→手机）264 字节；状态 Input/Feature 各 16 字节
void buildTlcVendor(Builder& b) {
    b.usagePage(kPageVendor);
    b.usage(0x0001);
    b.collection(0x01);
    b.reportId(kReportVendor);

    // Input：状态上报（手机 → PC，走中断 IN 端点）
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(2);  // status / linkSpeed
    b.usage(0x0010);
    b.usage(0x0011);
    b.input(kDataVar);
    b.reportSize(64);
    b.reportCount(1);
    b.usage(0x0012);   // moduleMask（§2.9，64 位）
    b.input(kDataVar);
    b.reportSize(32);
    b.reportCount(1);
    b.logicalMin(0);
    b.logicalMax(0x7FFFFFFF);
    b.usage(0x0013);   // errorCode
    b.input(kDataVar);
    b.reportSize(64);
    b.reportCount(1);
    b.usage(0x0014);   // uptimeMs
    b.input(kDataVar);
    b.reportSize(8);
    b.reportCount(1);
    b.logicalMin(0);
    b.logicalMax(255);
    b.usage(0x0015);   // lastSeq
    b.input(kDataVar);

    // Output：命令（PC → 手机）：cmd / seq / reserved / payloadLen(u32) 共 7 字节
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(7);
    b.usage(0x0020);
    b.output(kDataVar);
    b.reportSize(8);
    b.reportCount(kMaxVendorPayload);
    b.usage(0x0021);   // payload
    b.output(kDataVar);

    // Feature：状态（Get/Set，与 Input 同布局）
    b.reportSize(8);
    b.reportCount(2);
    b.usage(0x0010);
    b.usage(0x0011);
    b.feature(kDataVar);
    b.reportSize(64);
    b.reportCount(1);
    b.usage(0x0012);
    b.feature(kDataVar);
    b.reportSize(32);
    b.reportCount(1);
    b.usage(0x0013);
    b.feature(kDataVar);
    b.reportSize(64);
    b.reportCount(1);
    b.usage(0x0014);
    b.feature(kDataVar);
    b.reportSize(8);
    b.reportCount(1);
    b.usage(0x0015);
    b.feature(kDataVar);

    b.endCollection();
}

// ============================ TLC 6：Battery ===============================
// 1 + 12 = 13 字节。电量声明为标准百分比，其余字段由 host 按 §2.8 解析。
void buildTlcBattery(Builder& b) {
    b.usagePage(kPageBattery);
    b.usage(0x0001);
    b.collection(0x01);
    b.reportId(kReportBattery);

    // chargePct：0..100 %
    b.logicalMin(0);
    b.logicalMax(100);
    b.reportSize(8);
    b.reportCount(1);
    b.unit(kUnitPercent);
    b.unitExp(0);
    b.usage(0x0002);
    b.input(kDataVar);
    b.unit(kUnitNone);
    b.unitExp(0);

    // state / voltageMv / currentMa / tempCx10 / remainingMin：由 host 按偏移解析
    b.padBytes(1);   // state
    b.padBytes(2);   // voltageMv
    b.padBytes(2);   // currentMa
    b.padBytes(2);   // tempCx10
    b.padBytes(4);   // remainingMin
    b.endCollection();
}

// ============================ TLC：Windows Precision Touchpad ==============
// v1.8：逐字节采用 imbushuo/mac-precision-touchpad 的 PTP 描述符（WellspringT2.h，
// Apple Magic Trackpad 2 → Windows 精确式触控板，海量真机验证），仅替换 Report ID：
//   MULTITOUCH 0x05→16, REPORTMODE 0x04→17, FUNCSWITCH 0x06→18,
//   DEVICE_CAPS 0x07→19, PTPHQA 0x08→20（1..15 已被现有 TLC 占用）。
// 输入报告 50B：rid + 5×finger(9B) + scanTime(2B) + contactCount(1B) + buttons(1B)。
// 手势/惯性/掌压全部由 Windows 系统合成；参考 PeronGH/BLE-PTP-PoC（同源翻译）。
static const uint8_t kPtpDescriptor[] = {
    // TLC 1: Digitizer / Touch Pad (rid 16)
    0x05, 0x0d, 0x09, 0x05, 0xa1, 0x01,
    0x85, 16,
    // Finger 1
    0x09, 0x22, 0xa1, 0x02,
    0x25, 0x01, 0x09, 0x47, 0x09, 0x42,
    0x95, 0x02, 0x75, 0x01, 0x81, 0x02,
    0x75, 0x01, 0x95, 0x06, 0x81, 0x03,
    0x95, 0x01, 0x75, 0x20,
    0x27, 0xff, 0xff, 0xff, 0xff,
    0x09, 0x51, 0x81, 0x02,
    0x05, 0x01,
    0x26, 0x20, 0x4e, 0x75, 0x10,
    0x55, 0x0e, 0x65, 0x11,
    0x09, 0x30, 0x46, 0x14, 0x05, 0x95, 0x01, 0x81, 0x02,
    0x46, 0x52, 0x03, 0x26, 0xe0, 0x2e, 0x09, 0x31, 0x81, 0x02,
    0x45, 0x00, 0x55, 0x00, 0x65, 0x00,
    0xc0,
    // Finger 2
    0x05, 0x0d, 0x09, 0x22, 0xa1, 0x02,
    0x25, 0x01, 0x09, 0x47, 0x09, 0x42,
    0x95, 0x02, 0x75, 0x01, 0x81, 0x02,
    0x75, 0x01, 0x95, 0x06, 0x81, 0x03,
    0x95, 0x01, 0x75, 0x20, 0x27, 0xff, 0xff, 0xff, 0xff,
    0x09, 0x51, 0x81, 0x02,
    0x05, 0x01, 0x26, 0x20, 0x4e, 0x75, 0x10,
    0x55, 0x0e, 0x65, 0x11,
    0x09, 0x30, 0x46, 0x14, 0x05, 0x95, 0x01, 0x81, 0x02,
    0x46, 0x52, 0x03, 0x26, 0xe0, 0x2e, 0x09, 0x31, 0x81, 0x02,
    0x45, 0x00, 0x55, 0x00, 0x65, 0x00,
    0xc0,
    // Finger 3（无 unit reset——与原 WellspringT2.h collection 2 一致）
    0x05, 0x0d, 0x09, 0x22, 0xa1, 0x02,
    0x25, 0x01, 0x09, 0x47, 0x09, 0x42,
    0x95, 0x02, 0x75, 0x01, 0x81, 0x02,
    0x75, 0x01, 0x95, 0x06, 0x81, 0x03,
    0x95, 0x01, 0x75, 0x20, 0x27, 0xff, 0xff, 0xff, 0xff,
    0x09, 0x51, 0x81, 0x02,
    0x05, 0x01, 0x26, 0x20, 0x4e, 0x75, 0x10,
    0x55, 0x0e, 0x65, 0x11,
    0x09, 0x30, 0x46, 0x14, 0x05, 0x95, 0x01, 0x81, 0x02,
    0x46, 0x52, 0x03, 0x26, 0xe0, 0x2e, 0x09, 0x31, 0x81, 0x02,
    0xc0,
    // Finger 4
    0x05, 0x0d, 0x09, 0x22, 0xa1, 0x02,
    0x25, 0x01, 0x09, 0x47, 0x09, 0x42,
    0x95, 0x02, 0x75, 0x01, 0x81, 0x02,
    0x75, 0x01, 0x95, 0x06, 0x81, 0x03,
    0x95, 0x01, 0x75, 0x20, 0x27, 0xff, 0xff, 0xff, 0xff,
    0x09, 0x51, 0x81, 0x02,
    0x05, 0x01, 0x26, 0x20, 0x4e, 0x75, 0x10,
    0x55, 0x0e, 0x65, 0x11,
    0x09, 0x30, 0x46, 0x14, 0x05, 0x95, 0x01, 0x81, 0x02,
    0x46, 0x52, 0x03, 0x26, 0xe0, 0x2e, 0x09, 0x31, 0x81, 0x02,
    0x45, 0x00, 0x55, 0x00, 0x65, 0x00,
    0xc0,
    // Finger 5（无 unit reset）
    0x05, 0x0d, 0x09, 0x22, 0xa1, 0x02,
    0x25, 0x01, 0x09, 0x47, 0x09, 0x42,
    0x95, 0x02, 0x75, 0x01, 0x81, 0x02,
    0x75, 0x01, 0x95, 0x06, 0x81, 0x03,
    0x95, 0x01, 0x75, 0x20, 0x27, 0xff, 0xff, 0xff, 0xff,
    0x09, 0x51, 0x81, 0x02,
    0x05, 0x01, 0x26, 0x20, 0x4e, 0x75, 0x10,
    0x55, 0x0e, 0x65, 0x11,
    0x09, 0x30, 0x46, 0x14, 0x05, 0x95, 0x01, 0x81, 0x02,
    0x46, 0x52, 0x03, 0x26, 0xe0, 0x2e, 0x09, 0x31, 0x81, 0x02,
    0xc0,
    // Scan Time
    0x05, 0x0d, 0x55, 0x0c, 0x66, 0x01, 0x10,
    0x47, 0xff, 0xff, 0x00, 0x00,
    0x27, 0xff, 0xff, 0x00, 0x00,
    0x09, 0x56, 0x81, 0x02,
    // Contact Count
    0x09, 0x54, 0x25, 0x7f, 0x75, 0x08, 0x81, 0x02,
    // Button 1 + padding
    0x05, 0x09, 0x09, 0x01, 0x25, 0x01, 0x75, 0x01, 0x81, 0x02,
    0x95, 0x07, 0x81, 0x03,
    // Feature: Device Capabilities (rid 19)：MaxContacts + PadType
    0x05, 0x0d, 0x85, 19,
    0x09, 0x55, 0x09, 0x59,
    0x15, 0x00, 0x26, 0xff, 0x00,
    0x75, 0x08, 0x95, 0x02, 0xb1, 0x02,
    // v1.8：PTPHQA 认证 blob（rid 20）已移除——内核 GADGET_HID_WRITE_GET_REPORT
    // 的 data 上限 64B 无法登记 257B blob；缺认证 Windows 会标「非认证」但功能可用
    0xc0,
    // TLC 2: Digitizer / Configuration
    0x05, 0x0d, 0x09, 0x0e, 0xa1, 0x01,
    // Input Mode (rid 17)
    0x85, 17, 0x09, 0x22, 0xa1, 0x02,
    0x09, 0x52, 0x15, 0x00, 0x25, 0x05,
    0x75, 0x08, 0x95, 0x01, 0xb1, 0x02,
    0xc0,
    // Function Switch (rid 18)
    0xa1, 0x00, 0x85, 18,
    0x09, 0x57, 0x09, 0x58,
    0x75, 0x01, 0x95, 0x02, 0x25, 0x01, 0xb1, 0x02,
    0x95, 0x06, 0xb1, 0x03,
    0xc0,
    0xc0,
};

}  // namespace

// ---------------------------------------------------------------- 对外 API --
//
// v1.9 重大教训（2026-09-21 15:53/15:56/16:00 三连蓝屏）：
//   BugCheck 0x3B，Param1=0xC0000094（DIVIDE_BY_ZERO），出错地址三次同偏移。
//   根因：传感器 TLC 的数据字段声明了 Unit 但**未声明 Physical Range**
//   （默认 0..0），hidparse 计算 resolution=(logicalMax-logicalMin)/
//   (physicalMax-physicalMin) → 除零。此前传感器 PDO 全 FAILED_START 时
//   驱动不消费输入报告所以不崩；描述符修好后（rid 冲突消除）传感器真正
//   启动，Windows 一消费传感器报告即内核除零。
//   处置：传感器 TLC（IMU + 低频 7..15）**整体移出 USB 描述符**，待补齐
//   physical range 并逐 TLC 联调后再回归。传感器功能本就未达成，不影响
//   现有可用功能（触控板/数位屏/多媒体键/串口/电池）。
std::vector<uint8_t> buildReportDescriptor() {
    Builder b;
    // v1.11：Digitizer TLC 移除——与传感器 TLC 同类缺陷（声明 Unit 但无
    // Physical Range，hidparse 计算 resolution 除零），用户点「触屏」模式
    // 一发报告即 PC 重启（2026-09-21 22:1x 真机）。与传感器同规则：补齐
    // Physical Range 并逐 TLC 联调之前不允许回归。
    // v1.8/v1.9：Mouse TLC 全描述符**只允许出现一次**（Report ID 2 冲突会让
    // Windows hidparser 拒收整个描述符 → MI_00 Code 10 全灭，真机 2026-09-21
    // 15:33 稳定复现；此前「恢复了 Mouse TLC」的编辑把 buildTlcMouse 留了两份）。
    // PTP 段同时移除：257B 认证 blob 超内核 64B GET_REPORT 上限，本来就无法
    // 工作（详见 reports/MAIN-INTERVENTIONS.md §13），且是 desc 膨胀诱因。
    buildTlcMouse(b);   // v1.4：触控板相对位移鼠标（Report ID 2）
    buildTlcConsumer(b);
    buildTlcKeyboard(b);   // v1.11：USB 快捷键键盘（Report ID 21）
    buildTlcGamepad(b);    // v1.x：USB 游戏手柄（Report ID 22）
    buildTlcVendor(b);
    buildTlcBattery(b);
    return b.out;
}

size_t writeReportDescriptor(uint8_t* dst, size_t cap) {
    const std::vector<uint8_t> desc = buildReportDescriptor();
    if (dst != nullptr && cap >= desc.size()) {
        for (size_t i = 0; i < desc.size(); ++i) dst[i] = desc[i];
    }
    return desc.size();
}

// ============================ 蓝牙 HID 版描述符 =============================
// 无线模式用。只含 Mouse / Keyboard / Consumer 三个 TLC，**不含传感器**，
// 因此体积远小于 USB 版（后者实测 1666B），完全不受 f_hid 4096 上限困扰。
//
// **Report ID 与 USB 版无关**：蓝牙 HID 是系统里的独立设备，编号从 1 重新开始。
// 这里刻意不复用 USB 版的 kReportMouse/kReportConsumer —— 两者是不同设备上的
// 不同编号空间，混用会导致协议解析错位。
namespace {

enum : uint8_t {
    kBtReportMouse    = 1,
    kBtReportKeyboard = 2,
    kBtReportConsumer = 3,
};

// 蓝牙版鼠标：与 buildTlcMouse 同构，仅 Report ID 不同
void buildBtTlcMouse(Builder& b) {
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageMouse);
    b.collection(0x01);
    b.reportId(kBtReportMouse);
    b.usage(kUsagePointer);
    b.collection(0x00);

    b.usagePage(kPageButton);
    b.usageMin(kUsageButton1);
    b.usageMax(kUsageButton1 + 2);
    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(3);
    b.input(kDataVar);
    b.reportCount(5);
    b.input(kConstVar);

    b.usagePage(kPageGenericDesktop);
    b.logicalMin(-kMaxMouseDelta);
    b.logicalMax(kMaxMouseDelta);
    b.reportSize(8);
    b.reportCount(2);
    b.usage(kUsageAxisX);
    b.usage(kUsageAxisY);
    b.input(kDataVarRel);

    b.reportCount(1);
    b.usage(kUsageWheel);
    b.input(kDataVarRel);
    b.usage(kUsageAcPan);
    b.input(kDataVarRel);

    b.endCollection();
    b.endCollection();
}

// 蓝牙版键盘：标准 Boot 风格 —— 修饰键 1 字节 + 按键数组 6 字节 = 7 字节
// （Report ID 另计 1 字节，共 8）。这是 Windows/macOS/Linux 通吃的布局，
// 手机上做软键盘映射时只需填修饰键位图与 6 个 HID usage code。
void buildBtTlcKeyboard(Builder& b) {
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageKeyboard);
    b.collection(0x01);
    b.reportId(kBtReportKeyboard);

    // 修饰键：8 位（左Ctrl..右GUI），每位一个键
    b.usagePage(kPageKeyboard);
    b.usageMin(kUsageKbLeftCtrl);
    b.usageMax(kUsageKbLeftCtrl + 7);
    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(8);
    b.input(kDataVar);

    // 按键数组：最多同时 6 个
    b.logicalMin(0);
    b.logicalMax(255);
    b.reportSize(8);
    b.reportCount(6);
    b.usageMin(0);
    b.usageMax(255);
    b.input(kDataArr);

    // LED 输出（NumLock/CapsLock 等）：部分宿主会下发，声明了更兼容
    b.usagePage(kPageLed);
    b.usageMin(0x01);
    b.usageMax(0x05);
    b.reportSize(1);
    b.reportCount(5);
    b.output(kDataVar);
    b.reportCount(3);
    b.output(kConstVar);

    b.endCollection();
}

// 蓝牙版多媒体键：与 USB 版同为 usage 位图，仅 Report ID 不同
void buildBtTlcConsumer(Builder& b) {
    b.usagePage(kPageConsumer);
    b.usage(kUsageConsumerControl);
    b.collection(0x01);
    b.reportId(kBtReportConsumer);

    b.logicalMin(0);
    b.logicalMax(1);
    b.reportSize(1);
    b.reportCount(1);
    // 位序必须与 hid_layout.h 的 ConsumerKeyBit 一致
    b.usage(kUsageVolumeUp);
    b.input(kDataVar);
    b.usage(kUsageVolumeDown);
    b.input(kDataVar);
    b.usage(kUsageMute);
    b.input(kDataVar);
    b.usage(kUsagePower);
    b.input(kDataVar);
    b.usage(kUsagePlayPause);
    b.input(kDataVar);
    b.usage(kUsageScanPrevTrack);
    b.input(kDataVar);
    b.usage(kUsageScanNextTrack);
    b.input(kDataVar);
    b.padBits(9);
    b.padBytes(1);
    b.endCollection();
}

}  // namespace

std::vector<uint8_t> buildBtReportDescriptor() {
    Builder b;
    buildBtTlcMouse(b);
    buildBtTlcKeyboard(b);
    buildBtTlcConsumer(b);
    return b.out;
}

size_t writeBtReportDescriptor(uint8_t* dst, size_t cap) {
    const std::vector<uint8_t> desc = buildBtReportDescriptor();
    if (dst != nullptr && cap >= desc.size()) {
        for (size_t i = 0; i < desc.size(); ++i) dst[i] = desc[i];
    }
    return desc.size();
}

uint32_t maxReportLength() {
    uint32_t m = 0;
    size_t n = 0;
    const uint8_t* ids = tlcReportIds(n);
    for (size_t i = 0; i < n; ++i) {
        const uint32_t s = reportSizeById(ids[i]);
        if (s > m) m = s;
    }
    return m;
}

// v1.2：TLC 由 6 个增至 14 个（IMU + 9 个低频 + Digitizer + Consumer + Vendor + Battery）。
// v1.4：增至 **15 个**（新增触控板 Mouse TLC，Report ID 2 —— 复用 v1.2 废弃的编号）。
// 集中成一张表，避免四处 switch 各写一份映射导致漂移。
namespace {
struct TlcMeta {
    uint8_t     id;
    uint16_t    page;
    uint16_t    usage;
    const char* name;
};

const TlcMeta kTlcMeta[] = {
    { kReportImuBatch,     kPageSensor,    kUsageAccel3D,           "sensor-imu"          },
    { kReportMouse,        kPageGenericDesktop, kUsageMouse,        "touchpad-mouse"      },
    { kReportAls,          kPageSensor,    kUsageAls,               "sensor-light"        },
    { kReportProximity,    kPageSensor,    kUsageProximity,         "sensor-proximity"    },
    { kReportPressure,     kPageSensor,    kUsagePressure,          "sensor-pressure"     },
    { kReportOrientation,  kPageSensor,    kUsageDeviceOrientation, "sensor-orientation"  },
    { kReportInclinometer, kPageSensor,    kUsageInclinometer3D,    "sensor-inclinometer" },
    { kReportAmbientTemp,  kPageSensor,    kUsageTemperature,       "sensor-ambient-temp" },
    { kReportHumidity,     kPageSensor,    kUsageHumidity,          "sensor-humidity"     },
    { kReportStepCounter,  kPageSensor,    kUsageSensor,            "sensor-step"         },
    { kReportHeartRate,    kPageSensor,    kUsageSensor,            "sensor-heart-rate"   },
    { kReportDigitizer,    kPageDigitizer, kUsageTouchScreen,       "digitizer"           },
    { kReportConsumer,     kPageConsumer,  kUsageConsumerControl,   "consumer"            },
    { kReportKeyboard,     kPageGenericDesktop, kUsageKeyboard,     "usb-keyboard"        },
    { kReportGamepad,      kPageGenericDesktop, kUsageGamepad,      "usb-gamepad"         },
    { kReportVendor,       kPageVendor,    0x0001,                  "vendor-ctrl"         },
    { kReportBattery,      kPageBattery,   0x0001,                  "battery"             },
};
constexpr size_t kTlcMetaCount = sizeof(kTlcMeta) / sizeof(kTlcMeta[0]);

const TlcMeta* findTlc(uint8_t reportId) {
    for (size_t i = 0; i < kTlcMetaCount; ++i) {
        if (kTlcMeta[i].id == reportId) return &kTlcMeta[i];
    }
    return nullptr;
}
}  // namespace

uint16_t usagePageOf(uint8_t reportId) {
    const TlcMeta* m = findTlc(reportId);
    return m != nullptr ? m->page : 0;
}

uint16_t usageOf(uint8_t reportId) {
    const TlcMeta* m = findTlc(reportId);
    return m != nullptr ? m->usage : 0;
}

const char* tlcName(uint8_t reportId) {
    const TlcMeta* m = findTlc(reportId);
    return m != nullptr ? m->name : "unknown";
}

const uint8_t* tlcReportIds(size_t& count) {
    static const uint8_t kIds[] = {
        kReportImuBatch,     kReportMouse,
        kReportAls,          kReportProximity,      kReportPressure,
        kReportOrientation,  kReportInclinometer,   kReportAmbientTemp,
        kReportHumidity,     kReportStepCounter,    kReportHeartRate,
        kReportDigitizer,    kReportConsumer,       kReportVendor, kReportBattery,
        kReportKeyboard,     // v1.11：USB 快捷键键盘（rid 21）
        kReportGamepad,      // v1.x：USB 游戏手柄（rid 22）
    };
    count = sizeof(kIds) / sizeof(kIds[0]);
    return kIds;
}

}  // namespace apx
