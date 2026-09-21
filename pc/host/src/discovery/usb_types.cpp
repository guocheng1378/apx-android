#include "apxpc/discovery/usb_types.hpp"

#include <cstdio>

namespace apxpc::usb {

std::string Device::vidPid() const {
    char buf[32];
    std::snprintf(buf, sizeof buf, "VID_%04X&PID_%04X", vid, pid);
    return buf;
}

// 是否可能是本工程的复合 Gadget：需要一个"复合设备"标志，且具备
// HID / Vendor Bulk / UVC / CDC 之一（对应我们的功能面）。
bool Device::looksLikeApxGadget() const {
    if (!isComposite) return false;
    return has(Role::Hid) || has(Role::HidSensor) || has(Role::BulkChannel) ||
           has(Role::Uvc) || has(Role::CdcAcm) || has(Role::Uac);
}

const char* transferName(Transfer t) {
    switch (t) {
        case Transfer::Control:     return "control";
        case Transfer::Isochronous: return "isochronous";
        case Transfer::Bulk:        return "bulk";
        case Transfer::Interrupt:   return "interrupt";
    }
    return "?";
}

std::string Endpoint::desc() const {
    char buf[96];
    std::snprintf(buf, sizeof buf, "0x%02X %s %s mps=%u",
                  address, input ? "IN" : "OUT", transferName(transfer), maxPacketSize);
    return buf;
}

std::string Interface::roleName() const {
    switch (static_cast<ClassCode>(classCode)) {
        case ClassCode::Audio:          return "audio";
        case ClassCode::CdcControl:     return "cdc-control";
        case ClassCode::CdcData:        return "cdc-data";
        case ClassCode::Hid:            return "hid";
        case ClassCode::Imaging:        return "imaging";
        case ClassCode::MassStorage:    return "mass-storage";
        case ClassCode::Video:          return "video";
        case ClassCode::AudioVideo:     return "av";
        case ClassCode::VendorSpecific: return "vendor";
        default:                        return "other";
    }
}

std::string HidCollection::usageName() const {
    switch (usagePage) {
        case 0x01: return "generic-desktop";
        case 0x0C: return "consumer";
        case 0x0D: return "digitizer";
        case 0x0E: return "haptics";
        case 0x20: return "sensor";
        case 0x85: return "battery";
        case 0xFF00: return "vendor";
        default: return "unknown";
    }
}

}  // namespace apxpc::usb
