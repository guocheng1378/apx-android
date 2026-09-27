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

enum : uint8_t {
    kDataVar  = 0x02,
    kConstVar = 0x03,
    kDataVarRel = 0x06,
    kDataArr  = 0x00,
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
    void usageMin(uint16_t u)  { item(kTypeLocal,  kTagUsageMin,  u, bytesForU32(u)); }
    void usageMax(uint16_t u)  { item(kTypeLocal,  kTagUsageMax,  u, bytesForU32(u)); }
    void logicalMin(int32_t v) { item(kTypeGlobal, kTagLogicalMin, static_cast<uint32_t>(v), bytesForI32(v)); }
    void logicalMax(int32_t v) { item(kTypeGlobal, kTagLogicalMax, static_cast<uint32_t>(v), bytesForI32(v)); }
    void physicalMin(int32_t v){ item(kTypeGlobal, kTagPhysicalMin, static_cast<uint32_t>(v), bytesForI32(v)); }
    void physicalMax(int32_t v){ item(kTypeGlobal, kTagPhysicalMax, static_cast<uint32_t>(v), bytesForI32(v)); }
    void unit(uint32_t u)      { item(kTypeGlobal, kTagUnit, u, bytesForU32(u)); }
    void unitExp(int8_t e)     { item(kTypeGlobal, kTagUnitExp, static_cast<uint32_t>(e & 0x0F), 1); }
    void reportSize(uint32_t n){ item(kTypeGlobal, kTagReportSize, n, bytesForU32(n)); }
    void reportCount(uint32_t n){item(kTypeGlobal, kTagReportCount, n, bytesForU32(n)); }
    void reportId(uint8_t id)  { item(kTypeGlobal, kTagReportId, id, 1); }
    void input(uint8_t flags)  { item(kTypeMain, kTagInput, flags, 1); }
    void output(uint8_t flags) { item(kTypeMain, kTagOutput, flags, 1); }
    void feature(uint8_t flags){ item(kTypeMain, kTagFeature, flags, 1); }
    void collection(uint8_t c) { item(kTypeMain, kTagCollection, c, 1); }
    void endCollection()       { item(kTypeMain, kTagEndCollection, 0, 0); }

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

// ---- USB 版 TLC 构建函数（供 buildReportDescriptor 使用）----

void buildTlcImu(Builder& b) {
    const UnitSpec& spec = unitSpec(kSensorAccel);
    b.usagePage(kPageSensor);
    b.usage(kUsageAccel3D);
    b.collection(0x01);
    b.reportId(kReportImuBatch);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(1);
    b.usage(kUsagePropReportState); b.feature(kDataVar);
    b.usage(kUsagePropSensorStatus); b.feature(kDataVar);
    b.logicalMax(65535); b.reportSize(16); b.unit(spec.hidUnit); b.unitExp(spec.exponent);
    b.usage(kUsagePropSensitivityAbs); b.feature(kDataVar);
    b.reportSize(32); b.unit(kUnitMillisecond); b.unitExp(0); b.logicalMax(-1);
    b.usage(kUsagePropReportInterval); b.feature(kDataVar);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(1);
    b.usage(kUsagePropPowerState); b.feature(kDataVar);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(2);
    b.usage(kUsageSensorState); b.usage(kUsageSensorEvent); b.input(kDataVar);
    b.logicalMin(-32768); b.logicalMax(32767); b.reportSize(16); b.reportCount(1);
    b.unit(spec.hidUnit); b.unitExp(spec.exponent);
    b.usage(kUsageAccelAxisX); b.input(kDataVar);
    b.usage(kUsageAccelAxisY); b.input(kDataVar);
    b.usage(kUsageAccelAxisZ); b.input(kDataVar);
    b.unit(kUnitNone); b.unitExp(0);
    b.endCollection();
}

struct LowFreqTlcSpec {
    uint8_t  reportId;
    uint16_t tlcUsage;
    uint8_t  unitSource;
    uint16_t dataUsages[3];
    uint8_t  dataCount;
};

const LowFreqTlcSpec kLowFreqTlcs[] = {
    { kReportAls,          kUsageAls,               kSensorLight,      { kUsageLightIllum, 0, 0 }, 1 },
    { kReportProximity,    kUsageProximity,         kSensorProximity,  { kUsageHumanPresence, 0, 0 }, 1 },
    { kReportPressure,     kUsagePressure,          kSensorPressure,   { kUsageAtmPressure, 0, 0 }, 1 },
    { kReportOrientation,  kUsageDeviceOrientation, kSensorOrientation,{ kUsageMagnFluxX, kUsageMagnFluxY, kUsageMagnFluxZ }, 3 },
    { kReportInclinometer, kUsageInclinometer3D,    kSensorInclinometer,{ kUsageTiltX, kUsageTiltY, kUsageTiltZ }, 3 },
    { kReportAmbientTemp,  kUsageTemperature,       kSensorDeviceTemp, { kUsageEnvTemperature, 0, 0 }, 1 },
    { kReportHumidity,     kUsageHumidity,          kSensorHumidity,   { kUsageAtmHumidity, 0, 0 }, 1 },
    { kReportStepCounter,  kUsageSensor,            kSensorStepCounter,{ kUsageCustomValue0, 0, 0 }, 1 },
    { kReportHeartRate,    kUsageSensor,            kSensorHeartRate,  { kUsageCustomValue1, 0, 0 }, 1 },
};
constexpr size_t kLowFreqTlcCount = sizeof(kLowFreqTlcs) / sizeof(kLowFreqTlcs[0]);

void buildTlcLowFreqOne(Builder& b, const LowFreqTlcSpec& s, const UnitSpec& spec) {
    b.usagePage(kPageSensor); b.usage(s.tlcUsage); b.collection(0x01); b.reportId(s.reportId);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(1);
    b.usage(kUsagePropReportState); b.feature(kDataVar);
    b.usage(kUsagePropSensorStatus); b.feature(kDataVar);
    b.logicalMax(65535); b.reportSize(16); b.unit(spec.hidUnit); b.unitExp(spec.exponent);
    b.usage(kUsagePropSensitivityAbs); b.feature(kDataVar);
    b.reportSize(32); b.unit(kUnitMillisecond); b.unitExp(0); b.logicalMax(-1);
    b.usage(kUsagePropReportInterval); b.feature(kDataVar);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(1);
    b.usage(kUsagePropPowerState); b.feature(kDataVar);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(2);
    b.usage(kUsageSensorState); b.usage(kUsageSensorEvent); b.input(kDataVar);
    b.padBytes(1);
    b.usage(kUsageSensorTimestamp); b.logicalMin(0); b.logicalMax(0x7FFFFFFF);
    b.reportSize(64); b.reportCount(1); b.unit(kUnitSecond); b.unitExp(-4); b.input(kDataVar);
    b.unit(kUnitNone); b.unitExp(0);
    b.logicalMin(INT32_MIN); b.logicalMax(INT32_MAX);
    for (int i = 0; i < 3; ++i) {
        b.reportSize(32); b.reportCount(1);
        if (i < s.dataCount) { b.usage(s.dataUsages[i]); b.input(kDataVar); }
        else { b.padBytes(4); }
    }
    b.endCollection();
}

void buildTlcConsumer(Builder& b) {
    b.usagePage(kPageConsumer); b.usage(kUsageConsumerControl); b.collection(0x01);
    b.reportId(kReportConsumer);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(1);
    b.usage(kUsageVolumeUp); b.input(kDataVar);
    b.usage(kUsageVolumeDown); b.input(kDataVar);
    b.usage(kUsageMute); b.input(kDataVar);
    b.usage(kUsagePower); b.input(kDataVar);
    b.usage(kUsagePlayPause); b.input(kDataVar);
    b.usage(kUsageScanPrevTrack); b.input(kDataVar);
    b.usage(kUsageScanNextTrack); b.input(kDataVar);
    b.padBits(9); b.padBytes(1);
    b.endCollection();
}

void buildTlcKeyboard(Builder& b) {
    b.usagePage(kPageGenericDesktop); b.usage(kUsageKeyboard); b.collection(0x01);
    b.reportId(kReportKeyboard);
    b.usagePage(kPageKeyboard); b.usageMin(0xE0); b.usageMax(0xE7);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(8); b.input(kDataVar);
    b.reportSize(8); b.reportCount(1); b.input(kConstVar);
    b.usageMin(0x00); b.usageMax(0x65); b.logicalMin(0); b.logicalMax(0x65);
    b.reportSize(8); b.reportCount(6); b.input(kDataArr);
    b.endCollection();
}

void buildTlcGamepad(Builder& b) {
    b.usagePage(kPageGenericDesktop); b.usage(kUsageGamepad); b.collection(0x01);
    b.reportId(kReportGamepad);
    b.usagePage(kPageButton); b.usageMin(kUsageButton1); b.usageMax(kUsageButton1 + 15);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(16); b.input(kDataVar);
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageAxisX); b.usage(kUsageAxisY); b.usage(kUsageAxisRx); b.usage(kUsageAxisRy);
    b.logicalMin(-127); b.logicalMax(127); b.reportSize(8); b.reportCount(4); b.input(kDataVar);
    b.endCollection();
}

void buildTlcMouse(Builder& b) {
    b.usagePage(kPageGenericDesktop); b.usage(kUsageMouse); b.collection(0x01);
    b.reportId(kReportMouse); b.usage(kUsagePointer); b.collection(0x00);
    b.usagePage(kPageButton); b.usageMin(kUsageButton1); b.usageMax(kUsageButton1 + 2);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(3); b.input(kDataVar);
    b.reportCount(5); b.input(kConstVar);
    b.usagePage(kPageGenericDesktop); b.logicalMin(-kMaxMouseDelta); b.logicalMax(kMaxMouseDelta);
    b.reportSize(8); b.reportCount(2); b.usage(kUsageAxisX); b.usage(kUsageAxisY); b.input(kDataVarRel);
    b.reportCount(1); b.usage(kUsageWheel); b.input(kDataVarRel);
    b.usage(kUsageAcPan); b.input(kDataVarRel);
    b.endCollection(); b.endCollection();
}

void buildTlcVendor(Builder& b) {
    b.usagePage(kPageVendor); b.usage(0x0001); b.collection(0x01); b.reportId(kReportVendor);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(2);
    b.usage(0x0010); b.usage(0x0011); b.input(kDataVar);
    b.reportSize(64); b.reportCount(1); b.usage(0x0012); b.input(kDataVar);
    b.reportSize(32); b.reportCount(1); b.logicalMin(0); b.logicalMax(0x7FFFFFFF);
    b.usage(0x0013); b.input(kDataVar);
    b.reportSize(64); b.reportCount(1); b.usage(0x0014); b.input(kDataVar);
    b.reportSize(8); b.reportCount(1); b.logicalMin(0); b.logicalMax(255);
    b.usage(0x0015); b.input(kDataVar);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(7);
    b.usage(0x0020); b.output(kDataVar);
    b.reportSize(8); b.reportCount(kMaxVendorPayload); b.usage(0x0021); b.output(kDataVar);
    b.reportSize(8); b.reportCount(2); b.usage(0x0010); b.usage(0x0011); b.feature(kDataVar);
    b.reportSize(64); b.reportCount(1); b.usage(0x0012); b.feature(kDataVar);
    b.reportSize(32); b.reportCount(1); b.usage(0x0013); b.feature(kDataVar);
    b.reportSize(64); b.reportCount(1); b.usage(0x0014); b.feature(kDataVar);
    b.reportSize(8); b.reportCount(1); b.usage(0x0015); b.feature(kDataVar);
    b.endCollection();
}

void buildTlcBattery(Builder& b) {
    b.usagePage(kPageBattery); b.usage(0x0001); b.collection(0x01); b.reportId(kReportBattery);
    b.logicalMin(0); b.logicalMax(100); b.reportSize(8); b.reportCount(1);
    b.unit(kUnitPercent); b.unitExp(0); b.usage(0x0002); b.input(kDataVar);
    b.unit(kUnitNone); b.unitExp(0);
    b.padBytes(1); b.padBytes(2); b.padBytes(2); b.padBytes(2); b.padBytes(4);
    b.endCollection();
}

}  // namespace

std::vector<uint8_t> buildReportDescriptor() {
    Builder b;
    buildTlcMouse(b);
    buildTlcConsumer(b);
    buildTlcKeyboard(b);
    buildTlcGamepad(b);
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
// 无线模式用。Mouse / Keyboard / Consumer / Gamepad 四个 TLC，不含传感器。
// Report ID 从 1 重新开始（1=Mouse 2=Keyboard 3=Consumer 4=Gamepad）。
namespace {

enum : uint8_t {
    kBtReportMouse    = 1,
    kBtReportKeyboard = 2,
    kBtReportConsumer = 3,
    kBtReportGamepad  = 4,
};

void buildBtTlcMouse(Builder& b) {
    b.usagePage(kPageGenericDesktop); b.usage(kUsageMouse); b.collection(0x01);
    b.reportId(kBtReportMouse); b.usage(kUsagePointer); b.collection(0x00);
    b.usagePage(kPageButton); b.usageMin(kUsageButton1); b.usageMax(kUsageButton1 + 2);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(3); b.input(kDataVar);
    b.reportCount(5); b.input(kConstVar);
    b.usagePage(kPageGenericDesktop); b.logicalMin(-kMaxMouseDelta); b.logicalMax(kMaxMouseDelta);
    b.reportSize(8); b.reportCount(2); b.usage(kUsageAxisX); b.usage(kUsageAxisY); b.input(kDataVarRel);
    b.reportCount(1); b.usage(kUsageWheel); b.input(kDataVarRel);
    b.usage(kUsageAcPan); b.input(kDataVarRel);
    b.endCollection(); b.endCollection();
}

void buildBtTlcKeyboard(Builder& b) {
    b.usagePage(kPageGenericDesktop); b.usage(kUsageKeyboard); b.collection(0x01);
    b.reportId(kBtReportKeyboard);
    b.usagePage(kPageKeyboard); b.usageMin(kUsageKbLeftCtrl); b.usageMax(kUsageKbLeftCtrl + 7);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(8); b.input(kDataVar);
    b.logicalMin(0); b.logicalMax(255); b.reportSize(8); b.reportCount(6);
    b.usageMin(0); b.usageMax(255); b.input(kDataArr);
    b.usagePage(kPageLed); b.usageMin(0x01); b.usageMax(0x05);
    b.reportSize(1); b.reportCount(5); b.output(kDataVar);
    b.reportCount(3); b.output(kConstVar);
    b.endCollection();
}

void buildBtTlcConsumer(Builder& b) {
    b.usagePage(kPageConsumer); b.usage(kUsageConsumerControl); b.collection(0x01);
    b.reportId(kBtReportConsumer);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(1);
    b.usage(kUsageVolumeUp); b.input(kDataVar);
    b.usage(kUsageVolumeDown); b.input(kDataVar);
    b.usage(kUsageMute); b.input(kDataVar);
    b.usage(kUsagePower); b.input(kDataVar);
    b.usage(kUsagePlayPause); b.input(kDataVar);
    b.usage(kUsageScanPrevTrack); b.input(kDataVar);
    b.usage(kUsageScanNextTrack); b.input(kDataVar);
    b.padBits(9); b.padBytes(1);
    b.endCollection();
}

// 蓝牙版游戏手柄：与 USB 版同构，Report ID = 4。
// 16 按钮位图 + 4 轴（X/Y/Rx/Ry），8bit 有符号（-127..127），共 1+2+4=7B。
void buildBtTlcGamepad(Builder& b) {
    b.usagePage(kPageGenericDesktop); b.usage(kUsageGamepad); b.collection(0x01);
    b.reportId(kBtReportGamepad);
    b.usagePage(kPageButton); b.usageMin(kUsageButton1); b.usageMax(kUsageButton1 + 15);
    b.logicalMin(0); b.logicalMax(1); b.reportSize(1); b.reportCount(16); b.input(kDataVar);
    b.usagePage(kPageGenericDesktop);
    b.usage(kUsageAxisX); b.usage(kUsageAxisY); b.usage(kUsageAxisRx); b.usage(kUsageAxisRy);
    b.logicalMin(-127); b.logicalMax(127); b.reportSize(8); b.reportCount(4); b.input(kDataVar);
    b.endCollection();
}

}  // namespace

std::vector<uint8_t> buildBtReportDescriptor() {
    Builder b;
    buildBtTlcMouse(b);
    buildBtTlcKeyboard(b);
    buildBtTlcConsumer(b);
    buildBtTlcGamepad(b);
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
        kReportKeyboard,     kReportGamepad,
    };
    count = sizeof(kIds) / sizeof(kIds[0]);
    return kIds;
}

}  // namespace apx