#pragma once
// 设备发现门面：把 usb::Device 收敛成 UI/控制面使用的轻量 DeviceInfo。
// 预存在的 host 代码调用 apxpc::discovery::enumerate() 与 speedName()，
// 此处补齐该包装层（usb 层提供真实枚举实现）。
#include <cstdint>
#include <string>
#include <vector>

#include <apxpc/discovery/usb_types.hpp>

namespace apxpc::discovery {

struct DeviceInfo {
    std::string path;          // 打开设备用的路径
    std::string serial;        // 序列号
    uint16_t    vid = 0;
    uint16_t    pid = 0;
    usb::Speed  speed = usb::Speed::Unknown;
};

inline const char* speedName(usb::Speed s) noexcept { return usb::speedName(s); }

// 枚举已连接设备（Windows 走 SetupAPI，Linux 走 sysfs/libusb）。
// 无设备时返回空向量，不抛异常。
std::vector<DeviceInfo> enumerate();

}  // namespace apxpc::discovery
