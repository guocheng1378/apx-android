#include "apxpc/discovery/discovery.hpp"
#include "apxpc/discovery/enumerator.hpp"
#include "apxpc/log.hpp"

namespace apxpc::discovery {

std::vector<DeviceInfo> enumerate() {
    std::vector<DeviceInfo> out;
    try {
        auto devices = usb::enumerateDevices();
        out.reserve(devices.size());
        for (const auto& d : devices) {
            DeviceInfo info;
            info.path   = d.devicePath;
            info.serial = d.serialNumber;
            info.vid    = d.vid;
            info.pid    = d.pid;
            info.speed  = d.speed;
            out.push_back(std::move(info));
        }
    } catch (const std::exception& e) {
        APX_LOGW("设备枚举异常：{}", e.what());
    }
    return out;
}

}  // namespace apxpc::discovery
