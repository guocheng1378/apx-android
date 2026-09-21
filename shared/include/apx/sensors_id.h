// ============================================================================
// 统一传感器编号（唯一真源：docs/PROTOCOL.md §2.3 / §2.4）
//
// 编号规则：
//   Report 1（IMU）里的 sensorId 原值 0..5   -> 全局编号直接使用 0..5
//   Report 2（低频）里的 sensorId 原值 0..9  -> 全局编号 = 原值 | 0x10
// 于是 u64 掩码 bit0..5 = IMU，bit16..25 = 低频，互不重叠，一套掩码即可下发。
//
// 所有常量集中在本文件，其它模块禁止写魔法数字。
// ============================================================================
#pragma once
#include <cstdint>

namespace apx {

enum SensorId : uint8_t {
    // ---- Report 1：IMU（§2.3，sensorId 原值即全局编号）----
    kSensorAccel       = 0x00,
    kSensorGyro        = 0x01,
    kSensorMag         = 0x02,
    kSensorAccelUncal  = 0x03,
    kSensorGyroUncal   = 0x04,
    kSensorMagUncal    = 0x05,

    // ---- Report 2：低频（§2.4，全局编号 = 原值 | 0x10）----
    kSensorLight       = 0x10,  // raw 0
    kSensorProximity   = 0x11,  // raw 1
    kSensorPressure    = 0x12,  // raw 2
    kSensorOrientation = 0x13,  // raw 3
    kSensorInclinometer= 0x14,  // raw 4
    kSensorDeviceTemp  = 0x15,  // raw 5
    kSensorBatteryTemp = 0x16,  // raw 6
    kSensorHumidity    = 0x17,  // raw 7
    kSensorStepCounter = 0x18,  // raw 8
    kSensorHeartRate   = 0x19,  // raw 9
};

// 编号上界（不含），用于数组尺寸与范围校验
enum : uint8_t {
    kSensorIdBegin = kSensorAccel,
    kSensorIdEnd   = 0x1A,
};

// Report2 原始 sensorId <-> 全局编号
inline constexpr uint8_t lowFreqToGlobal(uint8_t raw) noexcept { return static_cast<uint8_t>(raw | 0x10u); }
inline constexpr uint8_t globalToLowFreqRaw(uint8_t id) noexcept { return static_cast<uint8_t>(id & 0x0Fu); }

inline constexpr bool isImuSensor(uint8_t id) noexcept { return id <= kSensorMagUncal; }
inline constexpr bool isLowFreqSensor(uint8_t id) noexcept {
    return id >= kSensorLight && id <= kSensorHeartRate;
}
inline constexpr bool isValidSensorId(uint8_t id) noexcept {
    return isImuSensor(id) || isLowFreqSensor(id);
}

inline constexpr uint64_t sensorBit(uint8_t id) noexcept { return 1ull << id; }

// 常用掩码
inline constexpr uint64_t kMaskImuAll =
    sensorBit(kSensorAccel) | sensorBit(kSensorGyro) | sensorBit(kSensorMag) |
    sensorBit(kSensorAccelUncal) | sensorBit(kSensorGyroUncal) | sensorBit(kSensorMagUncal);

inline constexpr uint64_t kMaskLowFreqAll =
    sensorBit(kSensorLight) | sensorBit(kSensorProximity) | sensorBit(kSensorPressure) |
    sensorBit(kSensorOrientation) | sensorBit(kSensorInclinometer) | sensorBit(kSensorDeviceTemp) |
    sensorBit(kSensorBatteryTemp) | sensorBit(kSensorHumidity) | sensorBit(kSensorStepCounter) |
    sensorBit(kSensorHeartRate);

inline constexpr uint64_t kMaskAll = kMaskImuAll | kMaskLowFreqAll;

// 模块位图（§2.9）：位于 64 位掩码的 bit32..39，与传感器位（bit0..31）互不重叠
enum ModuleBit : uint64_t {
    kModuleTouch    = 1ull << 32,  // 触控 / 笔（Report 3，绝对坐标）
    kModuleKey      = 1ull << 33,  // Consumer 按键（Report 4）
    kModuleBattery  = 1ull << 34,  // 电池状态（Report 6）
    kModuleGps      = 1ull << 35,  // GPS（CDC ACM）
    kModuleVibrate  = 1ull << 36,  // 振动 / 手电 / 红外（Report 5）
    kModuleDisplay  = 1ull << 37,  // 副屏视频（bulk streamId 0）
    // v1.4 新增：与 kModuleTouch 的区别是语义——触控是**绝对坐标**（副屏触控），
    // 触控板是**相对位移鼠标**（Report 2）。二者可同时成立，故各自占一位。
    kModuleTouchpad = 1ull << 38,  // 触控板（Report 2，相对位移）
    kModuleCamera   = 1ull << 39,  // 摄像头（UVC / 系统方案）
};

// 模块位掩码（仅模块位，不含传感器位）
inline constexpr uint64_t kMaskModules = kModuleTouch | kModuleKey | kModuleBattery |
                                         kModuleGps | kModuleVibrate | kModuleDisplay |
                                         kModuleTouchpad | kModuleCamera;

const char* sensorName(uint8_t id);
const char* moduleName(uint64_t bit);

}  // namespace apx
