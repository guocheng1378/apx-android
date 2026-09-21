#pragma once
// 插拔监控：跨平台实现为“定时快照比对”，避免引入 udev/libusb 事件依赖。
// Windows 上另有 CM_Register_Notification 可用，但轮询快照在两种平台上行为一致、
// 且能同时捕获“速度降级（super-speed -> high-speed）”这类属性变化。
#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "apxpc/discovery/enumerator.hpp"
#include "apxpc/discovery/usb_types.hpp"
#include "apxpc/status.hpp"

namespace apxpc::usb {

enum class DeviceEventKind : uint8_t {
    Arrived = 0,   // 新设备出现
    Removed = 1,   // 设备消失
    Changed = 2,   // 仍在但关键属性变了（速度、驱动、接口集合）
};

struct DeviceEvent {
    DeviceEventKind kind{DeviceEventKind::Arrived};
    Device          device;
    std::string     detail;   // 人类可读的变化说明
};

using DeviceEventCallback = std::function<void(const DeviceEvent&)>;

struct MonitorOptions {
    unsigned          pollIntervalMs{400};
    EnumerateOptions  enumOpt{};
    bool              emitInitialAsArrived{false};  // 启动时把已有设备当作 Arrived 上报一次
};

class DeviceMonitor {
public:
    explicit DeviceMonitor(MonitorOptions opt = {});
    ~DeviceMonitor();

    DeviceMonitor(const DeviceMonitor&)            = delete;
    DeviceMonitor& operator=(const DeviceMonitor&) = delete;

    void setCallback(DeviceEventCallback cb);

    StatusEx start();
    void     stop();
    bool     running() const;

    // 最近一次快照（不触发重新枚举）
    std::vector<Device> snapshot() const;

    // 手动强制重新扫描并同步快照（返回本次事件）
    std::vector<DeviceEvent> rescan();

    // 便捷：阻塞等待首个匹配设备出现
    // predicate 为空表示任意设备
    StatusEx waitFor(std::function<bool(const Device&)> predicate,
                     unsigned timeoutMs,
                     Device* outDevice = nullptr);

private:
    void threadMain();
    static std::string diffDetail(const Device& oldDev, const Device& newDev);
    static bool        keyFieldsEqual(const Device& a, const Device& b);

    MonitorOptions       opt_;
    DeviceEventCallback  cb_;
    mutable std::mutex   mu_;
    std::vector<Device>  snapshot_;
    std::thread          th_;
    std::atomic<bool>    running_{false};
};

}  // namespace apxpc::usb
