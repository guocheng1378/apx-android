// 传感器编号名称表
#include "apx/sensors_id.h"

namespace apx {

const char* sensorName(uint8_t id) {
    switch (id) {
        case kSensorAccel:        return "accelerometer";
        case kSensorGyro:         return "gyroscope";
        case kSensorMag:          return "magnetometer";
        case kSensorAccelUncal:   return "accelerometer_uncal";
        case kSensorGyroUncal:    return "gyroscope_uncal";
        case kSensorMagUncal:     return "magnetometer_uncal";
        case kSensorLight:        return "light";
        case kSensorProximity:    return "proximity";
        case kSensorPressure:     return "pressure";
        case kSensorOrientation:  return "orientation";
        case kSensorInclinometer: return "inclinometer";
        case kSensorDeviceTemp:   return "device_temp";
        case kSensorBatteryTemp:  return "battery_temp";
        case kSensorHumidity:     return "humidity";
        case kSensorStepCounter:  return "step_counter";
        case kSensorHeartRate:    return "heart_rate";
        default:                  return "unknown";
    }
}

const char* moduleName(uint64_t bit) {
    switch (bit) {
        case kModuleTouch:    return "touch";
        case kModuleKey:      return "consumer-key";
        case kModuleBattery:  return "battery";
        case kModuleGps:      return "gps";
        case kModuleVibrate:  return "vibrate-torch-ir";
        case kModuleDisplay:  return "display-video";
        case kModuleTouchpad: return "touchpad";
        case kModuleCamera:   return "camera";
        default:              return "?";
    }
}

}  // namespace apx
