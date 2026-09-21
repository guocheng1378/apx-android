#pragma once
#include <memory>
#include <string>
#include <vector>

#include "apxpc/discovery/usb_types.hpp"
#include "apxpc/status.hpp"

namespace apxpc::usb {

struct EnumerateOptions {
    // VID 白名单；为空表示扫描全部 USB 设备（含 HID 子设备判定，速度略慢）
    std::vector<uint16_t> vidFilter;
    bool includeAllUsb     = false;  // true：不做筛选，返回全部设备
    bool scanHidCollections= true;   // 枚举各个 TLC（判定“已被 OS 变成传感器”的关键）
    bool scanSerialPorts   = true;   // 枚举 CDC ACM 生成的 COM 口
    bool readConfigDescriptor = true;// 通过父集线器读取配置描述符，补全接口/端点
};

class IUsbEnumerator {
public:
    virtual ~IUsbEnumerator() = default;
    virtual std::vector<Device> enumerate(const EnumerateOptions& opt) = 0;
    virtual std::string backendName() const = 0;
};

// 工厂：Windows->SetupAPI(+hub IOCTL)，Linux->libusb 或 sysfs
std::unique_ptr<IUsbEnumerator> createUsbEnumerator();

// 便捷接口
std::vector<Device> enumerateDevices(const EnumerateOptions& opt = {});

// 默认 VID 白名单（常见手机厂商 USB 供应商 ID，可用 --vid 覆盖）
const std::vector<uint16_t>& defaultVidAllowlist();

}  // namespace apxpc::usb
