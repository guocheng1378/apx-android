// Linux/跨平台后端（libusb 可用时优先）：比 sysfs 更精确（直接读配置描述符），
// 且能拿到 USB3 SuperSpeed Endpoint Companion 的 bMaxBurst / wBytesPerInterval。
#include <libusb-1.0/libusb.h>

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

#include "apxpc/discovery/enumerator.hpp"
#include "apxpc/discovery/usb_types.hpp"
#include "apxpc/log.hpp"

namespace apxpc::usb {
namespace {

struct LibusbContext {
    libusb_context* ctx{nullptr};
    bool ok{false};
    LibusbContext() { ok = libusb_init(&ctx) == 0; }
    ~LibusbContext() { if (ok && ctx) libusb_exit(ctx); }
};

std::string stringDescOrEmpty(libusb_device_handle* h, uint8_t idx) {
    if (!h || idx == 0) return {};
    unsigned char buf[256] = {0};
    const int n = libusb_get_string_descriptor_ascii(h, idx, buf, sizeof(buf) - 1);
    if (n <= 0) return {};
    return std::string(reinterpret_cast<char*>(buf), (size_t)n);
}

Speed fromLibusbSpeed(int s) {
    switch (s) {
        case LIBUSB_SPEED_LOW:        return Speed::Low;
        case LIBUSB_SPEED_FULL:       return Speed::Full;
        case LIBUSB_SPEED_HIGH:       return Speed::High;
        case LIBUSB_SPEED_SUPER:      return Speed::Super;
        case LIBUSB_SPEED_SUPER_PLUS: return Speed::SuperPlus;
        default:                      return Speed::Unknown;
    }
}

void applyRoles(Device& d) {
    Role r = Role::None;
    for (const auto& ifc : d.interfaces) {
        switch (ifc.classCode) {
            case 0x03: r |= Role::Hid; break;
            case 0x02:
                if (ifc.subClass == 0x02 && ifc.protocol == 0x01) r |= Role::CdcAcm;
                if (ifc.subClass == 0x0D) r |= Role::Ncm;
                break;
            case 0x0E: r |= Role::Uvc; break;
            case 0x01: r |= Role::Uac; break;
            case 0x06: if (ifc.subClass == 0x01) r |= Role::Mtp; break;
            case 0xFF: r |= Role::BulkChannel; break;
            default: break;
        }
    }
    for (const auto& h : d.hidCollections) {
        switch (h.usagePage) {
            case 0x20:   r |= Role::HidSensor;    break;
            case 0x0D:   r |= Role::HidDigitizer; break;
            case 0x0C:   r |= Role::HidConsumer;  break;
            case 0x85:   r |= Role::HidBattery;   break;
            case 0xFF00: r |= Role::HidVendor;    break;
            default: break;
        }
    }
    d.roles = r;
}

class LibusbEnumerator : public IUsbEnumerator {
public:
    std::string backendName() const override { return "libusb"; }

    std::vector<Device> enumerate(const EnumerateOptions& opt) override {
        std::vector<Device> devices;
        LibusbContext lib;
        if (!lib.ok) {
            APX_LOGE("libusb 初始化失败");
            return devices;
        }

        libusb_device** list = nullptr;
        const ssize_t cnt = libusb_get_device_list(lib.ctx, &list);
        if (cnt < 0) return devices;

        const std::vector<uint16_t> allow =
            opt.vidFilter.empty() ? defaultVidAllowlist() : opt.vidFilter;

        for (ssize_t i = 0; i < cnt; ++i) {
            libusb_device* dev = list[i];
            libusb_device_descriptor dd{};
            if (libusb_get_device_descriptor(dev, &dd) != 0) continue;

            Device d;
            d.vid    = dd.idVendor;
            d.pid    = dd.idProduct;
            d.bcdUsb = dd.bcdUSB;
            d.speed  = fromLibusbSpeed(libusb_get_device_speed(dev));
            d.operatingAtSuperSpeedPlus = (d.speed == Speed::SuperPlus);

            const uint8_t bus = libusb_get_bus_number(dev);
            const uint8_t addr = libusb_get_device_address(dev);
            char path[64];
            std::snprintf(path, sizeof(path), "/dev/bus/usb/%03u/%03u", (unsigned)bus, (unsigned)addr);
            d.devicePath = path;
            char inst[64];
            std::snprintf(inst, sizeof(inst), "USB\\VID_%04X&PID_%04X\\%u-%u",
                          d.vid, d.pid, (unsigned)bus, (unsigned)addr);
            d.instanceId = inst;
            d.portNumber = (uint8_t)libusb_get_port_number(dev);
            d.isComposite = dd.bDeviceClass == 0xEF || dd.bDeviceClass == 0x00;

            libusb_device_handle* h = nullptr;
            if (libusb_open(dev, &h) == 0 && h) {
                d.manufacturer = stringDescOrEmpty(h, dd.iManufacturer);
                d.displayName  = stringDescOrEmpty(h, dd.iProduct);
                d.serialNumber = stringDescOrEmpty(h, dd.iSerialNumber);
                libusb_close(h);
            }
            if (d.displayName.empty()) {
                char fb[64];
                std::snprintf(fb, sizeof(fb), "USB %04X:%04X", d.vid, d.pid);
                d.displayName = fb;
            }

            libusb_config_descriptor* cfg = nullptr;
            if (libusb_get_active_config_descriptor(dev, &cfg) == 0 && cfg) {
                for (uint8_t k = 0; k < cfg->bNumInterfaces; ++k) {
                    const libusb_interface& li = cfg->interface[k];
                    for (int a = 0; a < li.num_altsetting; ++a) {
                        const libusb_interface_descriptor& id = li.altsetting[a];
                        Interface ifc;
                        ifc.number    = id.bInterfaceNumber;
                        ifc.altSetting= id.bAlternateSetting;
                        ifc.classCode = id.bInterfaceClass;
                        ifc.subClass  = id.bInterfaceSubClass;
                        ifc.protocol  = id.bInterfaceProtocol;
                        for (uint8_t e = 0; e < id.bNumEndpoints; ++e) {
                            const libusb_endpoint_descriptor& ed = id.endpoint[e];
                            Endpoint ep;
                            ep.address       = ed.bEndpointAddress;
                            ep.input         = (ed.bEndpointAddress & 0x80) != 0;
                            ep.transfer      = static_cast<Transfer>(ed.bmAttributes & 0x03);
                            ep.usageType     = (uint8_t)((ed.bmAttributes >> 4) & 0x03);
                            ep.maxPacketSize = ed.wMaxPacketSize;
                            ep.interval      = ed.bInterval;
                            if (ed.ss_ep_companion) {
                                ep.maxBurst         = ed.ss_ep_companion->bMaxBurst;
                                ep.bytesPerInterval = ed.ss_ep_companion->wBytesPerInterval;
                            }
                            ifc.endpoints.push_back(ep);
                        }
                        d.interfaces.push_back(ifc);
                    }
                }
                libusb_free_config_descriptor(cfg);
            }
            if (d.interfaces.size() > 1) d.isComposite = true;

            applyRoles(d);
            if (!opt.includeAllUsb) {
                const bool inAllow = std::find(allow.begin(), allow.end(), d.vid) != allow.end();
                if (!inAllow && !d.looksLikeApxGadget()) continue;
            }
            devices.push_back(std::move(d));
        }
        libusb_free_device_list(list, 1);
        return devices;
    }
};

}  // namespace

std::unique_ptr<IUsbEnumerator> createUsbEnumerator() {
    return std::unique_ptr<IUsbEnumerator>(new LibusbEnumerator());
}

std::vector<Device> enumerateDevices(const EnumerateOptions& opt) {
    auto e = createUsbEnumerator();
    return e->enumerate(opt);
}

const std::vector<uint16_t>& defaultVidAllowlist() {
    static const std::vector<uint16_t> kList = {
        0x1D6B, 0x18D1, 0x04E8, 0x2717, 0x2A70, 0x12D1, 0x19D2,
        0x0BB4, 0x22D9, 0x2B4C, 0x05C6, 0x0E8D, 0x04DA, 0x2A45, 0x1949,
    };
    return kList;
}

}  // namespace apxpc::usb
