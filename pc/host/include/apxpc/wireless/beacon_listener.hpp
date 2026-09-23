#pragma once
// 手机反向信标监听（零配置发现）。
//
// 手机侧每 1.5s 向 `255.255.255.255:9501` 广播一条：
//   `APX1PHONE <name> <tcpPort> <token>`（UTF-8）
// （见 android/.../wireless/WirelessBeacon.kt，改格式必须两边同步）
//
// 为什么需要它：手机 IP 随 DHCP 漂移（实测 192.168.2.220 → .182 只隔了一次重连），
// 让用户手输 IP 必然天天失联。有了信标，PC 收到即自动连入，IP 变化无感。
//
// 广播被路由器隔离/防火墙拦住时只是「发现不了」，用户仍可用
// `apxhost wireless <ip>:9500` 手工接入 —— 不把发现失败伪装成链路失败。
#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <thread>

namespace apxpc::wireless {

struct PhoneBeacon {
    std::string name;    // 手机型号（空格已由手机侧替换为 '_'）
    std::string host;    // 信标来源 IPv4（取自 UDP 源地址，比载荷里的自述更可信）
    uint16_t    port = 0;
    std::string token;
};

class BeaconListener {
public:
    using Callback = std::function<void(const PhoneBeacon&)>;

    BeaconListener() = default;
    ~BeaconListener() { stop(); }
    BeaconListener(const BeaconListener&) = delete;
    BeaconListener& operator=(const BeaconListener&) = delete;

    /// 开始监听；端口被占/无网络时返回 false（调用方如实上报，不静默吞掉）
    bool start(Callback onFound, uint16_t port = kDefaultPort);

    void stop();

    bool running() const { return running_.load(); }

    static constexpr uint16_t kDefaultPort = 9501;

private:
    void loop();

    Callback cb_;
    std::atomic<bool> running_{false};
    std::thread thread_;
#if defined(_WIN32)
    std::uintptr_t sock_ = static_cast<std::uintptr_t>(~0ull);   // INVALID_SOCKET
#endif
};

}  // namespace apxpc::wireless
