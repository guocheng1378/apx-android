#include "apxpc/discovery/descriptor_parse.hpp"

#include <algorithm>
#include <cstring>

#include "apxpc/log.hpp"

namespace apxpc::usb {
namespace {

#pragma pack(push, 1)
struct StdConfig {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint16_t wTotalLength;
    uint8_t  bNumInterfaces;
    uint8_t  bConfigurationValue;
    uint8_t  iConfiguration;
    uint8_t  bmAttributes;
    uint8_t  bMaxPower;
};
struct StdInterface {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bInterfaceNumber;
    uint8_t bAlternateSetting;
    uint8_t bNumEndpoints;
    uint8_t bInterfaceClass;
    uint8_t bInterfaceSubClass;
    uint8_t bInterfaceProtocol;
    uint8_t iInterface;
};
struct StdEndpoint {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint8_t  bEndpointAddress;
    uint8_t  bmAttributes;
    uint16_t wMaxPacketSize;
    uint8_t  bInterval;
};
struct StdDevice {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint16_t bcdUSB;
    uint8_t  bDeviceClass;
    uint8_t  bDeviceSubClass;
    uint8_t  bDeviceProtocol;
    uint8_t  bMaxPacketSize0;
    uint16_t idVendor;
    uint16_t idProduct;
    uint16_t bcdDevice;
    uint8_t  iManufacturer;
    uint8_t  iProduct;
    uint8_t  iSerialNumber;
    uint8_t  bNumConfigurations;
};
struct SsEpCompanion {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint8_t  bMaxBurst;
    uint8_t  bmAttributes;
    uint16_t wBytesPerInterval;
};
#pragma pack(pop)

}  // namespace

bool parseConfigurationDescriptor(const std::vector<uint8_t>& d,
                                  std::vector<Interface>& out,
                                  uint16_t* outBcdUsb) {
    out.clear();
    size_t pos = 0;
    const bool startsWithDevice = d.size() > 2 && d[1] == kDescDevice;
    if (startsWithDevice) {
        // 允许把设备描述符拼在前面（某些接口返回两者）
        if (d[0] >= sizeof(StdDevice)) {
            StdDevice dev{};
            std::memcpy(&dev, d.data(), sizeof(StdDevice));
            if (outBcdUsb) *outBcdUsb = dev.bcdUSB;
        }
        pos = d[0];
    }

    while (pos + 2 <= d.size()) {
        const uint8_t len  = d[pos];
        const uint8_t type = d[pos + 1];
        if (len < 2) break;
        if (pos + len > d.size()) break;

        switch (type) {
            case kDescConfiguration: {
                if (len >= sizeof(StdConfig)) {
                    StdConfig c{};
                    std::memcpy(&c, d.data() + pos, sizeof(StdConfig));
                    // 复合设备：多个接口，且 bNumInterfaces > 1
                    (void)c;
                }
                break;
            }
            case kDescInterface: {
                if (len >= sizeof(StdInterface)) {
                    StdInterface si{};
                    std::memcpy(&si, d.data() + pos, sizeof(StdInterface));
                    Interface ifc;
                    ifc.number     = si.bInterfaceNumber;
                    ifc.altSetting = si.bAlternateSetting;
                    ifc.classCode  = si.bInterfaceClass;
                    ifc.subClass   = si.bInterfaceSubClass;
                    ifc.protocol   = si.bInterfaceProtocol;
                    out.push_back(ifc);
                }
                break;
            }
            case kDescEndpoint: {
                if (len >= sizeof(StdEndpoint) && !out.empty()) {
                    StdEndpoint se{};
                    std::memcpy(&se, d.data() + pos, sizeof(StdEndpoint));
                    Endpoint ep;
                    ep.address       = se.bEndpointAddress;
                    ep.input         = (se.bEndpointAddress & 0x80) != 0;
                    ep.transfer      = static_cast<Transfer>(se.bmAttributes & 0x03);
                    ep.usageType     = static_cast<uint8_t>((se.bmAttributes >> 4) & 0x03);
                    ep.maxPacketSize = se.wMaxPacketSize & 0x07FF;
                    ep.interval      = se.bInterval;
                    out.back().endpoints.push_back(ep);
                }
                break;
            }
            case kDescSsEpCompanion: {
                if (len >= sizeof(SsEpCompanion) && !out.empty() && !out.back().endpoints.empty()) {
                    SsEpCompanion sc{};
                    std::memcpy(&sc, d.data() + pos, sizeof(SsEpCompanion));
                    out.back().endpoints.back().maxBurst = sc.bMaxBurst;
                    out.back().endpoints.back().bytesPerInterval = sc.wBytesPerInterval;
                }
                break;
            }
            default:
                break;  // IAD / CDC / UVC 类特殊描述符按需跳过
        }
        pos += len;
    }
    return !out.empty();
}

}  // namespace apxpc::usb
