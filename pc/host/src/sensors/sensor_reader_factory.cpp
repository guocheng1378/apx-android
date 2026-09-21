#include "apxpc/sensors/sensor_reader.hpp"

#include <algorithm>
#include <cctype>
#include <map>
#include <mutex>
#include <string>

#include "apxpc/log.hpp"
#include "apxpc/platform/clock.hpp"

namespace apxpc::sensors {

// ---------------------------------------------------------------- 映射表
SensorKind kindOfApxId(uint8_t apxId) noexcept {
    switch (apxId) {
        case 0x00: return SensorKind::Accel;
        case 0x01: return SensorKind::Gyro;
        case 0x02: return SensorKind::Mag;
        case 0x03: return SensorKind::AccelUncal;
        case 0x04: return SensorKind::GyroUncal;
        case 0x05: return SensorKind::MagUncal;
        case 0x10: return SensorKind::Light;
        case 0x11: return SensorKind::Proximity;
        case 0x12: return SensorKind::Pressure;
        case 0x13: return SensorKind::Orientation;
        case 0x14: return SensorKind::Inclinometer;
        case 0x15: return SensorKind::Temperature;
        case 0x16: return SensorKind::Temperature;
        case 0x17: return SensorKind::Humidity;
        case 0x18: return SensorKind::StepCounter;
        case 0x19: return SensorKind::HeartRate;
        default:   return SensorKind::Unknown;
    }
}

uint8_t apxIdOfKind(SensorKind k) noexcept {
    switch (k) {
        case SensorKind::Accel:        return 0x00;
        case SensorKind::Gyro:         return 0x01;
        case SensorKind::Mag:          return 0x02;
        case SensorKind::AccelUncal:   return 0x03;
        case SensorKind::GyroUncal:    return 0x04;
        case SensorKind::MagUncal:     return 0x05;
        case SensorKind::Light:        return 0x10;
        case SensorKind::Proximity:    return 0x11;
        case SensorKind::Pressure:     return 0x12;
        case SensorKind::Orientation:  return 0x13;
        case SensorKind::Inclinometer: return 0x14;
        case SensorKind::Temperature:  return 0x15;
        case SensorKind::Humidity:     return 0x17;
        case SensorKind::StepCounter:  return 0x18;
        case SensorKind::HeartRate:    return 0x19;
        default:                       return 0xFF;
    }
}

const char* kindName(SensorKind k) noexcept {
    switch (k) {
        case SensorKind::Accel:        return "accelerometer";
        case SensorKind::Gyro:         return "gyroscope";
        case SensorKind::Mag:          return "magnetometer";
        case SensorKind::AccelUncal:   return "accelerometer_uncal";
        case SensorKind::GyroUncal:    return "gyroscope_uncal";
        case SensorKind::MagUncal:     return "magnetometer_uncal";
        case SensorKind::Light:        return "light";
        case SensorKind::Proximity:    return "proximity";
        case SensorKind::Pressure:     return "pressure";
        case SensorKind::Orientation:  return "orientation";
        case SensorKind::Inclinometer: return "inclinometer";
        case SensorKind::Temperature:  return "temperature";
        case SensorKind::Humidity:     return "humidity";
        case SensorKind::StepCounter:  return "step_counter";
        case SensorKind::HeartRate:    return "heart_rate";
        case SensorKind::Battery:      return "battery";
        case SensorKind::Custom:       return "custom";
        default:                       return "unknown";
    }
}

namespace {

// 空实现：任何平台都可用，便于无设备时启动 UI/CLI 而不崩溃
class NullReader : public ISensorReader {
public:
    std::string backendName() const override { return "null"; }
    std::vector<SensorInfo> list() override { return {}; }
    SensorValue read(uint8_t) override { return {}; }
    std::vector<SensorValue> readAll() override { return {}; }
};

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return (char)::tolower(c); });
    return s;
}

}  // namespace

std::unique_ptr<ISensorReader> createSensorReader(const std::string& backend) {
    const std::string b = lower(backend);

    if (b == "null") return std::unique_ptr<ISensorReader>(new NullReader());

#if defined(_WIN32)
    if (b == "winrt") {
        auto r = createWinRtReader();
        return r ? std::move(r) : std::unique_ptr<ISensorReader>(new NullReader());
    }
    if (b == "sensorapi") return createSensorApiReader();
    // auto：优先 WinRT（能拿到事件时间戳与报告间隔），退化到经典 Sensor API
    if (auto r = createWinRtReader()) {
        if (!r->list().empty()) return r;
        APX_LOGI("WinRT 后端未发现传感器，退回经典 Sensor API");
    }
    return createSensorApiReader();
#else
    if (b == "auto" || b == "iio") return createIioReader();
    return std::unique_ptr<ISensorReader>(new NullReader());
#endif
}

std::unique_ptr<ISensorReader> createSensorReaderByKind(SensorKind k) {
    (void)k;  // 当前所有后端都一次性枚举全部传感器，kind 仅用于调用方筛选
    return createSensorReader("auto");
}

}  // namespace apxpc::sensors
