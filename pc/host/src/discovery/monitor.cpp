#include "apxpc/discovery/monitor.hpp"

#include <algorithm>
#include <stdexcept>
#include <unordered_map>

#include "apxpc/platform/clock.hpp"

#include "apxpc/log.hpp"

namespace apxpc::usb {

DeviceMonitor::DeviceMonitor(MonitorOptions opt) : opt_(opt) {}

DeviceMonitor::~DeviceMonitor() { stop(); }

void DeviceMonitor::setCallback(DeviceEventCallback cb) {
    std::lock_guard<std::mutex> g(mu_);
    cb_ = std::move(cb);
}

StatusEx DeviceMonitor::start() {
    if (running_.load()) return ok();

    // 首帧：建立基线快照
    auto list = enumerateDevices(opt_.enumOpt);
    std::vector<DeviceEvent> initial;
    if (opt_.emitInitialAsArrived) {
        for (const auto& d : list) initial.push_back(DeviceEvent{DeviceEventKind::Arrived, d, "initial"});
    }
    {
        std::lock_guard<std::mutex> g(mu_);
        snapshot_ = std::move(list);
    }
    DeviceEventCallback cb;
    {
        std::lock_guard<std::mutex> g(mu_);
        cb = cb_;
    }
    for (const auto& e : initial)
        if (cb) cb(e);

    running_.store(true);
    th_ = std::thread([this] { threadMain(); });
    return ok();
}

void DeviceMonitor::stop() {
    if (!running_.load()) return;
    running_.store(false);
    if (th_.joinable()) th_.join();
}

bool DeviceMonitor::running() const { return running_.load(); }

std::vector<Device> DeviceMonitor::snapshot() const {
    std::lock_guard<std::mutex> g(mu_);
    return snapshot_;
}

bool DeviceMonitor::keyFieldsEqual(const Device& a, const Device& b) {
    return a.speed == b.speed &&
           a.operatingAtSuperSpeedPlus == b.operatingAtSuperSpeedPlus &&
           a.driverName == b.driverName &&
           a.interfaces.size() == b.interfaces.size() &&
           a.hidCollections.size() == b.hidCollections.size() &&
           a.serialPorts.size() == b.serialPorts.size() &&
           a.roles == b.roles;
}

std::string DeviceMonitor::diffDetail(const Device& a, const Device& b) {
    std::string s;
    if (a.speed != b.speed)
        s += "speed " + std::string(speedName(a.speed)) + " -> " + std::string(speedName(b.speed));
    if (a.driverName != b.driverName) {
        if (!s.empty()) s += "; ";
        s += "driver " + a.driverName + " -> " + b.driverName;
    }
    if (a.interfaces.size() != b.interfaces.size()) {
        if (!s.empty()) s += "; ";
        s += "interfaces " + std::to_string(a.interfaces.size()) + " -> " +
             std::to_string(b.interfaces.size());
    }
    if (a.hidCollections.size() != b.hidCollections.size()) {
        if (!s.empty()) s += "; ";
        s += "hid collections " + std::to_string(a.hidCollections.size()) + " -> " +
             std::to_string(b.hidCollections.size());
    }
    return s.empty() ? "attributes changed" : s;
}

std::vector<DeviceEvent> DeviceMonitor::rescan() {
    auto list = enumerateDevices(opt_.enumOpt);
    std::vector<DeviceEvent> events;

    std::unordered_map<std::string, Device> before;
    {
        std::lock_guard<std::mutex> g(mu_);
        for (const auto& d : snapshot_) before.emplace(d.instanceId, d);
    }

    std::unordered_map<std::string, Device> after;
    for (const auto& d : list) after.emplace(d.instanceId, d);

    for (const auto& kv : after) {
        auto it = before.find(kv.first);
        if (it == before.end()) {
            events.push_back(DeviceEvent{DeviceEventKind::Arrived, kv.second, "new device"});
        } else if (!keyFieldsEqual(it->second, kv.second)) {
            events.push_back(DeviceEvent{DeviceEventKind::Changed, kv.second,
                                         diffDetail(it->second, kv.second)});
        }
    }
    for (const auto& kv : before) {
        if (after.find(kv.first) == after.end())
            events.push_back(DeviceEvent{DeviceEventKind::Removed, kv.second, "device removed"});
    }

    {
        std::lock_guard<std::mutex> g(mu_);
        snapshot_ = std::move(list);
    }
    return events;
}

void DeviceMonitor::threadMain() {
    while (running_.load()) {
        const unsigned step = 20;
        for (unsigned t = 0; t < opt_.pollIntervalMs && running_.load(); t += step) sleepMs(step);
        if (!running_.load()) break;

        std::vector<DeviceEvent> events;
        try {
            events = rescan();
        } catch (const std::exception& e) {
            APX_LOGW("设备扫描异常: {}", e.what());
            continue;
        }

        DeviceEventCallback cb;
        {
            std::lock_guard<std::mutex> g(mu_);
            cb = cb_;
        }
        for (const auto& e : events) {
            const char* kind = (e.kind == DeviceEventKind::Arrived) ? "arrived"
                               : (e.kind == DeviceEventKind::Removed) ? "removed" : "changed";
            APX_LOGI("设备事件 {}: {} ({})", kind, e.device.displayName, e.detail);
            if (cb) cb(e);
        }
    }
}

StatusEx DeviceMonitor::waitFor(std::function<bool(const Device&)> predicate,
                                unsigned timeoutMs, Device* outDevice) {
    const uint64_t deadline = monotonicNs() + uint64_t(timeoutMs) * 1000000ull;
    while (monotonicNs() < deadline) {
        std::vector<Device> cur;
        {
            std::lock_guard<std::mutex> g(mu_);
            cur = snapshot_;
        }
        if (cur.empty()) cur = enumerateDevices(opt_.enumOpt);
        for (const auto& d : cur) {
            if (!predicate || predicate(d)) {
                if (outDevice) *outDevice = d;
                return ok();
            }
        }
        sleepMs(50);
    }
    return err(Status::Timeout, "waitFor 超时，未出现匹配设备");
}

}  // namespace apxpc::usb
