#pragma once
#include <cstdint>
#include <vector>

#include "apxpc/discovery/usb_types.hpp"

namespace apxpc::usb {

// 解析标准 USB 配置描述符（含接口/端点/IAD 与 USB3 SS Endpoint Companion）。
// 用途：不打开设备也能拿到每个接口的 class/subclass/protocol 与端点信息。
// 源数据来自 Windows 的 IOCTL_USB_GET_DESCRIPTOR_FROM_NODE_CONNECTION，
// 或 Linux 的 /sys/bus/usb/devices/<n>/descriptors。
bool parseConfigurationDescriptor(const std::vector<uint8_t>& desc,
                                  std::vector<Interface>& outInterfaces,
                                  uint16_t* outBcdUsb = nullptr);

// 描述符类型
enum DescriptorType : uint8_t {
    kDescDevice       = 0x01,
    kDescConfiguration= 0x02,
    kDescString       = 0x03,
    kDescInterface    = 0x04,
    kDescEndpoint     = 0x05,
    kDescHid          = 0x21,
    kDescCsInterface  = 0x24,
    kDescSsEpCompanion= 0x30,
    kDescSsPlusIsoch  = 0x31,
};

}  // namespace apxpc::usb
