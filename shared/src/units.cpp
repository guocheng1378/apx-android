// 单位换算与轴向对齐实现（§2.2）
#include "apx/units.h"

#include <cmath>

namespace apx {
namespace {

// 索引 = 全局传感器编号（0..0x19），未使用的槽位给恒等换算
const UnitSpec kSpecs[kSensorIdEnd] = {
    /* 00 accel        */ {"g",     kUnitG,          -3, 1.0 / kGravityMps2, 0.0},
    /* 01 gyro         */ {"deg/s", kUnitDegPerSec,  -2, kRadToDeg,          0.0},
    /* 02 mag          */ {"G",     kUnitGauss,      -4, kMicroTToG,         0.0},
    /* 03 accel_uncal  */ {"g",     kUnitG,          -3, 1.0 / kGravityMps2, 0.0},
    /* 04 gyro_uncal   */ {"deg/s", kUnitDegPerSec,  -2, kRadToDeg,          0.0},
    /* 05 mag_uncal    */ {"G",     kUnitGauss,      -4, kMicroTToG,         0.0},
    /* 06 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 07 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 08 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 09 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 0A 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 0B 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 0C 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 0D 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 0E 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 0F 未定义       */ {"",      kUnitNone,        0, 1.0,                0.0},
    /* 10 light        */ {"lux",   kUnitLux,        -2, 1.0,                0.0},
    /* 11 proximity    */ {"cm",    kUnitCentiMeter,  0, 1.0,                0.0},
    /* 12 pressure     */ {"Pa",    kUnitPascal,      2, kHpaToPa,           0.0},
    /* 13 orientation  */ {"deg",   kUnitDegrees,    -2, 1.0,                0.0},
    /* 14 inclinometer */ {"deg",   kUnitDegrees,    -2, 1.0,                0.0},
    /* 15 device_temp  */ {"K",     kUnitKelvin,     -2, 1.0,                kKelvin0C},
    /* 16 battery_temp */ {"K",     kUnitKelvin,     -2, 1.0,                kKelvin0C},
    /* 17 humidity     */ {"%",     kUnitPercent,    -2, 1.0,                0.0},
    /* 18 step_counter */ {"count", kUnitNone,        0, 1.0,                0.0},
    /* 19 heart_rate   */ {"bpm",   kUnitNone,        0, 1.0,                0.0},
};

const UnitSpec kFallback{"", kUnitNone, 0, 1.0, 0.0};

double pow10i(int8_t e) {
    double r = 1.0;
    for (int i = 0; i < e; ++i) r *= 10.0;
    for (int i = 0; i > e; --i) r /= 10.0;
    return r;
}

}  // namespace

const UnitSpec& unitSpec(uint8_t sensorId) {
    if (sensorId < kSensorIdEnd) return kSpecs[sensorId];
    return kFallback;
}

double androidToHid(uint8_t sensorId, double androidValue) {
    const UnitSpec& s = unitSpec(sensorId);
    return (androidValue + s.offset) * s.scale;
}

double hidToAndroid(uint8_t sensorId, double hidValue) {
    const UnitSpec& s = unitSpec(sensorId);
    return hidValue / s.scale - s.offset;
}

int32_t quantizeI32(uint8_t sensorId, double androidValue) {
    const UnitSpec& s = unitSpec(sensorId);
    const double raw = androidToHid(sensorId, androidValue) / pow10i(s.exponent);
    double q = std::llround(raw);
    if (q > 2147483647.0) return 2147483647;
    if (q < -2147483648.0) return -2147483648;
    return static_cast<int32_t>(q);
}

int16_t quantizeI16(uint8_t sensorId, double androidValue) {
    const int32_t v = quantizeI32(sensorId, androidValue);
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return static_cast<int16_t>(v);
}

double dequantize(uint8_t sensorId, int32_t raw) {
    const UnitSpec& s = unitSpec(sensorId);
    return hidToAndroid(sensorId, static_cast<double>(raw) * pow10i(s.exponent));
}

void alignAxes(const float in[3], AxisRotation rot, float out[3]) {
    const float x = in[0], y = in[1], z = in[2];
    switch (rot) {
        case AxisRotation::kCw90:  out[0] = y;  out[1] = -x; out[2] = z; break;
        case AxisRotation::kCw180: out[0] = -x; out[1] = -y; out[2] = z; break;
        case AxisRotation::kCw270: out[0] = -y; out[1] = x;  out[2] = z; break;
        case AxisRotation::kNone:
        default:                   out[0] = x;  out[1] = y;  out[2] = z; break;
    }
}

}  // namespace apx
