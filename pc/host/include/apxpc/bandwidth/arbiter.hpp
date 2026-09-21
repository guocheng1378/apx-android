#pragma once
// 统一带宽预算仲裁：有线(USB)/无线(WiFi)/蓝牙三条通道收敛到同一抽象。
// 模块按优先级申报需求；超预算时降级低优先项，并记录"为谁降了什么"。
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace apxpc::bandwidth {

// 优先级：数值越小越重要（越先保）
enum class Prio : int {
    TouchUp = 0,   // 触控上行（最高）
    HidSensor = 1, // HID 传感器
    DisplayVideo = 3, // 副屏视频
    Audio = 4,     // 音频
    UvcCamera = 5, // UVC 摄像头（最低）
};

struct Demand {
    std::string name;
    Prio       prio = Prio::DisplayVideo;
    double     mbps = 0;
    bool       active = false;
};

struct UsageItem {
    std::string name;
    int        prio = 3;
    double     mbps = 0;
    bool       degraded = false;
};

struct BandwidthReport {
    double   totalMbps = 0;   // 当前模式预算
    double   usedMbps = 0;   // 实际分配（降级后）
    double   requestedMbps = 0;
    std::vector<UsageItem> items;
};

class BandwidthArbiter {
public:
    // 设置链路预算（由服务按模式/实测带宽调用）
    void setBudget(double wiredMbps, double wirelessMbps);
    void setMode(const std::string& mode);          // wired | wireless
    void setWirelessBudget(double mbps);            // WiFi 有效带宽（动态）

    // 申报/撤销某模块需求
    void setDemand(const std::string& name, Prio prio, double mbps, bool active);

    BandwidthReport compute();                      // 立即重算并写日志（降级时）

    std::string mode() const;

private:
    double currentBudget() const;

    mutable std::mutex mu_;
    std::string mode_ = "wired";
    double wiredBudget_ = 300.0;       // USB 2.0 余量（480Mbps 减去协议开销）
    double wirelessBudget_ = 80.0;     // WiFi 局域网默认预算，可被 setWirelessBudget 覆盖
    std::vector<Demand> demands_;
    std::vector<std::string> lastDegraded_;         // 上一次降级日志，避免刷屏
};

}  // namespace apxpc::bandwidth
