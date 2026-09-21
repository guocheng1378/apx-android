#pragma once
// 控制面客户端：PROTOCOL §4 状态机
//   HELLO -> HELLO_ACK -> CONFIG -> CONFIG_ACK -> RUNNING -> (心跳 1s) -> BYE
//   连续 3 次心跳无响应 => 判定断线 => 触发自愈回调（PC 侧能做的是重新打开/重握手；
//   重新挂载 Gadget 由手机端完成，PC 侧通过 BYE+重连促使对端复位）
#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "apxpc/ctrl/ctrl_frame.hpp"
#include "apxpc/ctrl/transport.hpp"
#include "apxpc/platform/clock.hpp"
#include "apxpc/status.hpp"

namespace apxpc::ctrl {

struct LinkStatus {
    bool     connected{false};
    bool     running{false};
    uint16_t protocolVersion{0};
    uint8_t  udcSpeed{0};         // §2.7 linkSpeed
    uint8_t  moduleMask{0};
    uint8_t  runStatus{0};
    uint32_t errorCode{0};
    uint64_t uptimeMs{0};
    uint64_t sensorMask{0};
    uint64_t capabilities{0};
    int64_t  rttNs{0};
    int64_t  minRttNs{0};
    int64_t  offsetNs{0};         // pcNs = phoneNs + offsetNs（§1）
    uint32_t pingsSent{0};
    uint32_t pingsLost{0};
    uint32_t consecutiveLost{0};
    uint64_t lastPongNs{0};
};

struct CtrlClientOptions {
    unsigned heartbeatIntervalMs{1000};  // §4：1s
    unsigned heartbeatTimeoutMs{400};
    unsigned maxMissedHeartbeats{3};     // §4：3 次无响应判断线
    unsigned handshakeTimeoutMs{1200};
    bool     autoReconnect{true};
    unsigned reconnectDelayMs{400};
    unsigned maxReconnectAttempts{5};
    uint64_t capabilities{kCapSensor | kCapTouch | kCapKey | kCapBattery | kCapGps};
};

class CtrlClient {
public:
    explicit CtrlClient(std::unique_ptr<ICtrlTransport> transport,
                        CtrlClientOptions opt = {});
    ~CtrlClient();

    CtrlClient(const CtrlClient&)            = delete;
    CtrlClient& operator=(const CtrlClient&) = delete;

    // ---- 生命周期 ----
    StatusEx open();
    void     close();
    bool     isOpen() const;

    // ---- §4 握手 ----
    StatusEx handshake();
    StatusEx configure(uint64_t sensorMask,
                       const std::vector<SampleRate>& rates = {},
                       uint8_t displayMode = 0,
                       const VideoParams& video = {});
    StatusEx bye(const std::string& reason = "pc shutdown");

    // ---- 运行时下发 ----
    StatusEx setSensorMask(uint64_t mask);        // 走 CONFIG（手机以 CONFIG_ACK 确认）
    StatusEx setSampleRate(uint8_t sensorId, uint32_t rateHz);
    StatusEx setDisplayMode(uint8_t mode);

    // ---- Vendor Report 5（§2.7）----
    StatusEx sendVendorRaw(uint8_t cmd, const std::vector<uint8_t>& payload);
    StatusEx vibrate(uint16_t durationMs, uint8_t amplitude);
    StatusEx stopVibrate();
    StatusEx torch(bool on, uint8_t level);
    StatusEx irSend(uint32_t freqHz, const std::vector<uint16_t>& pattern);

    // ---- 心跳 ----
    StatusEx heartbeatOnce();   // 一次 ping/pong，更新 RTT 与时钟偏移
    StatusEx startHeartbeat();  // 后台线程，按 §4 间隔发送
    void     stopHeartbeat();

    // 读取手机主动上报的状态（FEATURE report 5）
    StatusEx pollVendorStatus(VendorStatusInfo& out);

    // ---- 观测 ----
    LinkStatus   status() const;
    HelloAckInfo helloAck() const;
    std::string  transportName() const;

    // 断线回调（连续 maxMissedHeartbeats 次无响应）
    using DisconnectHandler = std::function<void(Status)>;
    void setDisconnectHandler(DisconnectHandler h);
    // 自愈：重新 open + handshake（含 configure 重放）
    StatusEx selfHeal();

private:
    StatusEx sendFrame(const std::vector<uint8_t>& frame);
    StatusEx recvMessage(MsgType expect, CtrlMessage& out, unsigned timeoutMs);
    StatusEx replayConfig();
    void     heartbeatThread();

    std::unique_ptr<ICtrlTransport> transport_;
    CtrlClientOptions opt_;

    mutable std::mutex mu_;
    ClockSync          clockSync_;
    LinkStatus         st_;
    HelloAckInfo       helloAck_;
    ConfigAckInfo      configAck_;
    std::vector<SampleRate> lastRates_;
    uint8_t            lastDisplayMode_{0};
    VideoParams        lastVideo_;
    uint32_t           seq_{1};

    std::thread        hbThread_;
    std::atomic<bool>  hbRunning_{false};
    DisconnectHandler  onDisconnect_;
};

}  // namespace apxpc::ctrl
