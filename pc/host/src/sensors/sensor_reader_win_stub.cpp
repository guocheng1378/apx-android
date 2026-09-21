// Win32 传感器后端桩：本机 Windows SDK（10.0.22621）未导出 SENSOR_TYPE_* /
// SENSOR_DATA_TYPE_* 等 PROPERTYKEY 常量，经典 ISensorManager 后端与
// C++/WinRT 后端在此 SDK 上无法编译。此处提供可链接的占位实现，使宿主始终
// 能构建；真实传感器读取在具备对应 SDK / 运行时的环境启用 APXPC_BUILD_WIN_BACKENDS 后接入。
#include "apxpc/sensors/sensor_reader.hpp"

namespace apxpc::sensors {

std::unique_ptr<ISensorReader> createWinRtReader() {
    return nullptr;  // 需要 C++/WinRT 与 Windows.Devices.Sensors
}
std::unique_ptr<ISensorReader> createSensorApiReader() {
    return nullptr;  // 需要本机 SDK 的 SENSOR_TYPE_* 常量
}

}  // namespace apxpc::sensors
