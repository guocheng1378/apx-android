#pragma once
// PC 侧“读取已经集成进 OS 的手机传感器”。
//
// 关键前提（ARCHITECTURE §1/§7）：PC 端零自研驱动——
//   Windows：手机 Gadget 的 HID Sensor TLC 由内置 SensorsHIDClassDriver 接管，
//            在“设置 > 系统 > 关于/传感器”与 Windows.Devices.Sensors 中可见；
//   Linux  ：hid-sensors 驱动把它们变成 /sys/bus/iio/devices/iio:deviceX（IIO）。
// 因此本模块只读 OS 已经抽象好的传感器，不解析私有 HID 报告。
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "apxpc/status.hpp"

namespace apxpc::sensors {

enum class SensorKind : uint8_t {
    Unknown = 0,
    Accel, Gyro, Mag,
    AccelUncal, GyroUncal, MagUncal,
    Light, Proximity, Pressure,
    Orientation, Inclinometer,
    Temperature, Humidity, StepCounter, HeartRate,
    Battery, Custom,
};

struct SensorInfo {
    uint8_t     apxId{0};        // apx::SensorId 全局编号
    std::string name;            // 展示名（已本地化为英文标识）
    std::string osName;          // OS 侧名字 / sysfs 设备名
    SensorKind  kind{SensorKind::Unknown};
    bool        available{false};
    double      minIntervalMs{0};
};

struct SensorValue {
    uint8_t  apxId{0};
    uint64_t tsNs{0};            // PC 单调时基（PROTOCOL §1）
    double   v[4]{0, 0, 0, 0};
    int      count{0};
    bool     valid{false};
};

class ISensorReader {
public:
    virtual ~ISensorReader() = default;
    virtual std::string           backendName() const = 0;
    virtual std::vector<SensorInfo> list() = 0;
    virtual SensorValue           read(uint8_t apxId) = 0;
    virtual std::vector<SensorValue> readAll() = 0;
};

// 编号 <-> 种类 映射（与 apx::SensorId 对齐）
SensorKind kindOfApxId(uint8_t apxId) noexcept;
uint8_t    apxIdOfKind(SensorKind kind) noexcept;
const char* kindName(SensorKind k) noexcept;

// backend: "auto" | "winrt" | "sensorapi" | "iio" | "null"
std::unique_ptr<ISensorReader> createSensorReader(const std::string& backend = "auto");
std::unique_ptr<ISensorReader> createSensorReaderByKind(SensorKind k);

// 平台后端工厂（仅供 createSensorReader 调用，勿直接使用）
#if defined(_WIN32)
std::unique_ptr<ISensorReader> createWinRtReader();      // C++/WinRT Windows.Devices.Sensors
std::unique_ptr<ISensorReader> createSensorApiReader();  // 经典 ISensorManager（COM）
#else
std::unique_ptr<ISensorReader> createIioReader();        // hid-sensors -> IIO sysfs
#endif

}  // namespace apxpc::sensors
