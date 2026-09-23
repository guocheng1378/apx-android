// HID 报告打包与解析（§2）
#include "apx/hid_layout.h"

// consumerKeyBitUsage() 需要 Consumer Page 的 Usage 常量，它们定义在 hid_descriptor.h。
// 该头文件自身会 include hid_layout.h，因此这里不构成循环依赖。
#include "apx/hid_descriptor.h"
#include "apx/units.h"

namespace apx {
namespace {

// 复用 frame.h 的显式小端读写，避免本文件再写一份
inline void put16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}
inline void put32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
inline void put64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
inline uint32_t get32(const uint8_t* p) {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(p[i]) << (8 * i);
    return v;
}

}  // namespace

const char* linkSpeedName(uint8_t s) {
    switch (s) {
        case kSpeedFull:      return "full-speed(12Mbps)";
        case kSpeedHigh:      return "high-speed(480Mbps)";
        case kSpeedSuper:     return "super-speed(5Gbps)";
        case kSpeedSuperPlus: return "super-speed-plus(10Gbps)";
        default:              return "unknown";
    }
}

const char* reportIdName(uint8_t id) {
    switch (id) {
        case kReportImuBatch:     return "imu";
        case kReportMouse:        return "mouse";
        case kReportDigitizer:    return "digitizer";
        case kReportConsumer:     return "consumer";
        case kReportVendor:       return "vendor";
        case kReportBattery:      return "battery";
        case kReportAls:          return "light";
        case kReportProximity:    return "proximity";
        case kReportPressure:     return "pressure";
        case kReportOrientation:  return "orientation";
        case kReportInclinometer: return "inclinometer";
        case kReportAmbientTemp:  return "ambient-temp";
        case kReportHumidity:     return "humidity";
        case kReportStepCounter:  return "step-counter";
        case kReportHeartRate:    return "heart-rate";
        case kReportKeyboard:     return "keyboard";
        case kReportGamepad:      return "gamepad";
        default:                  return "unknown";
    }
}

uint32_t reportSizeById(uint8_t reportId) {
    switch (reportId) {
        case kReportImuBatch:  return kSizeImuBatchReport;
        case kReportMouse:     return kSizeMouseReport;
        case kReportDigitizer: return kSizeDigitizerReport;
        case kReportConsumer:  return kSizeConsumerReport;
        case kReportVendor:    return kSizeVendorOutReport;  // OUT 为最长的一支
        case kReportBattery:   return kSizeBatteryReport;
        // v1.2：低频传感器各自独立 Report ID，但共用同一份 24 字节布局
        case kReportAls:
        case kReportProximity:
        case kReportPressure:
        case kReportOrientation:
        case kReportInclinometer:
        case kReportAmbientTemp:
        case kReportHumidity:
        case kReportStepCounter:
        case kReportHeartRate: return kSizeLowFreqReport;
        case kReportKeyboard:  return 9;                 // 1 + 修饰1 + reserved1 + 6
        case kReportGamepad:   return kSizeGamepadReport;
        default:               return 0;
    }
}

// ------------------------------------------------------- §2.4 v1.2 映射 ----
// 显式列表而非算术偏移：kSensorBatteryTemp(0x16) 不占独立 Report，映射在这里断开。
uint8_t sensorIdOfLowFreqReport(uint8_t reportId) {
    switch (reportId) {
        case kReportAls:          return kSensorLight;
        case kReportProximity:    return kSensorProximity;
        case kReportPressure:     return kSensorPressure;
        case kReportOrientation:  return kSensorOrientation;
        case kReportInclinometer: return kSensorInclinometer;
        case kReportAmbientTemp:  return kSensorDeviceTemp;
        case kReportHumidity:     return kSensorHumidity;
        case kReportStepCounter:  return kSensorStepCounter;
        case kReportHeartRate:    return kSensorHeartRate;
        default:                  return 0xFF;
    }
}

uint8_t lowFreqReportOfSensorId(uint8_t sensorId) {
    switch (sensorId) {
        case kSensorLight:        return kReportAls;
        case kSensorProximity:    return kReportProximity;
        case kSensorPressure:     return kReportPressure;
        case kSensorOrientation:  return kReportOrientation;
        case kSensorInclinometer: return kReportInclinometer;
        case kSensorDeviceTemp:   return kReportAmbientTemp;
        case kSensorHumidity:     return kReportHumidity;
        case kSensorStepCounter:  return kReportStepCounter;
        case kSensorHeartRate:    return kReportHeartRate;
        default:                  return 0;
    }
}

// ------------------------------------------------------------ §2.3 IMU ----
// v1.3：函数签名保持不变（Kotlin / JNI 侧零改动），内部改为输出 Windows 原生传感器
// 所要求的「单样本」报告：State + Event + X/Y/Z。批量语义退化为「取本批最新样本」，
// 实时性优先且与驱动期望一致。
// periodNs / baseTsNs / flags 在当前格式下无承载位置，保留形参以兼容调用方并显式忽略。
size_t packImuBatch(uint8_t* dst, size_t cap, uint8_t sensorId, uint8_t flags,
                    uint32_t periodNs, uint64_t baseTsNs,
                    const float* xyz, size_t sampleCount, int32_t accuracy) {
    (void)periodNs; (void)baseTsNs; (void)flags;
    if (dst == nullptr || cap < kSizeImuBatchReport) return 0;
    if (xyz == nullptr || sampleCount == 0 || sampleCount > kMaxImuSamples) return 0;
    if (!isImuSensor(sensorId)) return 0;

    const size_t last = sampleCount - 1;  // 取最新样本
    const int16_t qx = quantizeI16(sensorId, xyz[last * 3 + 0]);
    const int16_t qy = quantizeI16(sensorId, xyz[last * 3 + 1]);
    const int16_t qz = quantizeI16(sensorId, xyz[last * 3 + 2]);

    // Android accuracy: 0=unreliable 1=low 2=medium 3=high → Sensor State
    const uint8_t state = (accuracy == 0) ? 2u : 1u;  // 2=not_available, 1=ready

    dst[0] = kReportImuBatch;
    dst[1] = state;
    dst[2] = 0;  // event = unknown（无阈值事件语义时固定 0）
    put16(dst + 3, static_cast<uint16_t>(qx));
    put16(dst + 5, static_cast<uint16_t>(qy));
    put16(dst + 7, static_cast<uint16_t>(qz));
    return kSizeImuBatchReport;
}

// ------------------------------------------------------------ §2.4 低频 ----
// v1.2：入参由 sensorId 改为 reportId（7..15）。布局不变（仍 24B），
// 只是类别信息从载荷里的 sensorId 字段移到了 Report ID 本身 —— 这样每个传感器
// 都是独立 TLC，Windows 才能逐个枚举并下发 report interval。
size_t packLowFreqById(uint8_t* dst, size_t cap, uint8_t reportId, uint8_t state,
                       uint8_t event, uint64_t tsNs, float v0, float v1, float v2) {
    if (dst == nullptr || cap < kSizeLowFreqReport) return 0;
    const uint8_t sensorId = sensorIdOfLowFreqReport(reportId);
    if (sensorId == 0xFF) return 0;  // 非低频 Report ID

    dst[0] = reportId;
    dst[1] = state;
    dst[2] = event;
    dst[3] = 0;  // reserved：Report ID 已区分类别，不再携带 sensorId
    put64(dst + 4, tsNs);
    put32(dst + 12, static_cast<uint32_t>(quantizeI32(sensorId, v0)));
    put32(dst + 16, static_cast<uint32_t>(quantizeI32(sensorId, v1)));
    put32(dst + 20, static_cast<uint32_t>(quantizeI32(sensorId, v2)));
    return kSizeLowFreqReport;
}

// ------------------------------------------------------------ §2.7 状态 ----
size_t packVendorStatus(uint8_t* dst, size_t cap, uint8_t status, uint8_t linkSpeed,
                        uint64_t moduleMask, uint32_t errorCode, uint64_t uptimeMs,
                        uint8_t lastSeq) {
    if (dst == nullptr || cap < kSizeVendorStatusRep) return 0;
    dst[0] = kReportVendor;
    dst[1] = status;
    dst[2] = linkSpeed;
    put64(dst + 3, moduleMask);
    put32(dst + 11, errorCode);
    put64(dst + 15, uptimeMs);
    dst[23] = lastSeq;
    return kSizeVendorStatusRep;
}

// ---------------------------------------------------------- §2.7 OUT 解析 --
bool parseVendorOut(const uint8_t* report, size_t len, uint8_t& cmd, uint8_t& seq,
                    uint8_t* payload, size_t cap, size_t& payloadLen) {
    if (report == nullptr || len < sizeof(VendorCommandHeader)) return false;
    if (report[0] != kReportVendor) return false;

    const uint32_t declared = get32(report + 4);
    size_t n = declared;
    if (n > kMaxVendorPayload) return false;
    const size_t available = len - sizeof(VendorCommandHeader);
    if (n > available) return false;
    if (n > cap) return false;

    cmd = report[1];
    seq = report[2];
    if (payload != nullptr && n > 0) {
        for (size_t i = 0; i < n; ++i) payload[i] = report[sizeof(VendorCommandHeader) + i];
    }
    payloadLen = n;
    return true;
}

// ------------------------------------------------------------ §2.8 电池 ----
size_t packBattery(uint8_t* dst, size_t cap, uint8_t chargePct, uint8_t state,
                   uint16_t voltageMv, int16_t currentMa, int16_t tempCx10,
                   uint32_t remainingMin) {
    if (dst == nullptr || cap < kSizeBatteryReport) return 0;
    dst[0] = kReportBattery;
    dst[1] = chargePct;
    dst[2] = state;
    put16(dst + 3, voltageMv);
    put16(dst + 5, static_cast<uint16_t>(currentMa));
    put16(dst + 7, static_cast<uint16_t>(tempCx10));
    put32(dst + 9, remainingMin);
    return kSizeBatteryReport;
}

// ------------------------------------------------------------ §2.6 按键 ----
// v1.2：改为 usage 位图。原「键编号 + 状态」无法被 Windows 识别为多媒体键。
size_t packConsumerBitmap(uint8_t* dst, size_t cap, uint16_t keyBitmap) {
    if (dst == nullptr || cap < kSizeConsumerReport) return 0;
    dst[0] = kReportConsumer;
    put16(dst + 1, keyBitmap);
    dst[3] = 0;  // reserved
    return kSizeConsumerReport;
}

// ---------------------------------------------------------- §2.10 触控板 ----
// 相对位移鼠标。端侧已按灵敏度曲线与加速度处理完毕，这里只做限幅与打包 ——
// 把「手感」留在端上（可调），把「限幅」固定在协议里（两端一致）。
size_t packMouse(uint8_t* dst, size_t cap, uint8_t buttons,
                 int dx, int dy, int wheel, int pan) {
    if (dst == nullptr || cap < kSizeMouseReport) return 0;

    const auto clampI8 = [](int v) -> int8_t {
        if (v > kMaxMouseDelta) return static_cast<int8_t>(kMaxMouseDelta);
        if (v < -kMaxMouseDelta) return static_cast<int8_t>(-kMaxMouseDelta);
        return static_cast<int8_t>(v);
    };

    dst[0] = kReportMouse;
    dst[1] = buttons;
    dst[2] = static_cast<uint8_t>(clampI8(dx));
    dst[3] = static_cast<uint8_t>(clampI8(dy));
    dst[4] = static_cast<uint8_t>(clampI8(wheel));
    dst[5] = static_cast<uint8_t>(clampI8(pan));
    return kSizeMouseReport;
}

// ---------------------------------------------------------- §2.13 手柄 ----
// 16 键位图 + 4 轴（左摇杆 X/Y、右摇杆 Rx/Ry）。轴限幅到 [-127, 127]。
size_t packGamepad(uint8_t* dst, size_t cap, uint16_t buttons,
                   int x, int y, int rx, int ry) {
    if (dst == nullptr || cap < kSizeGamepadReport) return 0;

    const auto clampI8 = [](int v) -> int8_t {
        if (v > 127) return 127;
        if (v < -127) return static_cast<int8_t>(-127);
        return static_cast<int8_t>(v);
    };

    dst[0] = kReportGamepad;
    put16(dst + 1, buttons);
    dst[3] = static_cast<uint8_t>(clampI8(x));
    dst[4] = static_cast<uint8_t>(clampI8(y));
    dst[5] = static_cast<uint8_t>(clampI8(rx));
    dst[6] = static_cast<uint8_t>(clampI8(ry));
    return kSizeGamepadReport;
}

uint16_t consumerKeyBitUsage(uint8_t bit) {
    switch (bit) {
        case kBitVolumeUp:   return kUsageVolumeUp;
        case kBitVolumeDown: return kUsageVolumeDown;
        case kBitMute:       return kUsageMute;
        case kBitPower:      return kUsagePower;
        case kBitPlayPause:  return kUsagePlayPause;
        case kBitPrevTrack:  return kUsageScanPrevTrack;
        case kBitNextTrack:  return kUsageScanNextTrack;
        case kBitZoomIn:     return kUsageZoomIn;    // v1.7c
        case kBitZoomOut:    return kUsageZoomOut;   // v1.7c
        default:             return 0;
    }
}

}  // namespace apx
