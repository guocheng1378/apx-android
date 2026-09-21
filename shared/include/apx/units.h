// ============================================================================
// 单位换算与轴向对齐（docs/PROTOCOL.md §2.2、ARCHITECTURE.md §4）
//
// Android 原始单位 -> HID 声明单位 的换算集中在本文件，手机端与 PC 端共用，
// 保证「手机怎么编码，PC 就怎么解码」。
// ============================================================================
#pragma once
#include <cstdint>

#include "apx/sensors_id.h"

namespace apx {

// ---- §2.2 换算常量 ----
constexpr double kGravityMps2 = 9.80665;                 // g = m/s² / 9.80665
constexpr double kRadToDeg    = 57.29577951308232;       // 180 / pi
constexpr double kKelvin0C    = 273.15;                  // K = °C + 273.15
constexpr double kHpaToPa     = 100.0;                   // 1 hPa = 100 Pa
constexpr double kMicroTToG   = 0.01;                    // 1 µT = 0.01 G

// ---- HID UNIT 取值（HID Sensor Usage Tables，与 Linux hid-sensor-ids.h 同源）----
enum HidUnit : uint32_t {
    kUnitNone       = 0x00000000u,  // 未指定（无量纲）
    kUnitLux        = 0x00000001u,
    kUnitG          = 0x0000001Au,
    kUnitDegrees    = 0x00000014u,
    kUnitDegPerSec  = 0x00000015u,
    kUnitPercent    = 0x00000017u,
    kUnitCentiMeter = 0x00000011u,
    kUnitSecond     = 0x00000110u,
    kUnitMillisecond = 0x00000019u,  // HID Sensor Usage Tables（Report Interval 用）
    kUnitPascal     = 0x0000F1E1u,
    kUnitKelvin     = 0x01000100u,
    kUnitGauss      = 0x01E1F000u,
};

// ---- 轴向旋转（手机安装方向与 Android 坐标系不一致时使用）----
// Android 坐标系：X 右 / Y 上 / Z 出屏（ARCHITECTURE §4），默认不做旋转。
enum class AxisRotation : uint8_t {
    kNone  = 0,
    kCw90  = 1,  // 绕 Z 轴顺时针 90°
    kCw180 = 2,
    kCw270 = 3,
};

// 单个传感器的单位规格
struct UnitSpec {
    const char* name;       // HID 声明单位名（调试用）
    uint32_t    hidUnit;    // HID UNIT 项取值
    int8_t      exponent;   // UNIT_EXPONENT（10^exponent）
    double      scale;      // Android 值 -> HID 值 的乘数
    double      offset;     // 先加偏置再乘（温度 °C->K 用）
};

const UnitSpec& unitSpec(uint8_t sensorId);

// Android 原始值 -> HID 声明单位下的物理值
double androidToHid(uint8_t sensorId, double androidValue);
// HID 声明单位下的物理值 -> Android 原始值（PC 侧解码用）
double hidToAndroid(uint8_t sensorId, double hidValue);

// 物理值 -> 定标整数（i32 用于 §2.4，i16 用于 §2.3），越界饱和而非回绕
int32_t quantizeI32(uint8_t sensorId, double androidValue);
int16_t quantizeI16(uint8_t sensorId, double androidValue);
// 定标整数 -> Android 原始值（指数由 unitSpec 给出）
double dequantize(uint8_t sensorId, int32_t raw);

// 轴序对齐：把 Android 坐标系下的向量旋转到设备朝向
void alignAxes(const float in[3], AxisRotation rot, float out[3]);

}  // namespace apx
