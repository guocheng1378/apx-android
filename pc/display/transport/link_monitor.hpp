// 链路监控：心跳 / RTT / 断线判定 / 自愈重连（PROTOCOL §4）
//
// 心跳间隔 1s；连续 3 次无响应判定断线：
//   1) 先 transport->reset()（清 stall / 复位管道）
//   2) 仍失败则调用 onReconnect 回调（重开设备）
//   3) 再失败则通过控制面 Remount 请求手机端重新挂载 Gadget，重跑握手
// 重连退避：1s → 2s → 4s → 8s（封顶）。
#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>

#include "common/clock.hpp"
#include "transport/ctrl_channel.hpp"
#include "transport/i_transport.hpp"

namespace apxdisp {

/**
 * 链路监控配置。
 *
 * **必须定义在 LinkMonitor 之外**：若作为嵌套类型，它的**默认成员初始化器**会被
 * 外层类的默认实参（`Config cfg = Config{}`）与成员初始化器（`Config cfg_{}`）
 * 要求求值，而那时代外层类定义尚未完成 —— Clang/GCC 会直接报
 * "default member initializer ... needed within definition of enclosing class"。
 * MSVC 对此宽容，所以在 MSVC 下看不出问题，但换编译器就编不过。
 */
struct LinkMonitorConfig {
    uint32_t heartbeatMs   = 1000;
    uint32_t missThreshold = 3;
    uint32_t maxBackoffMs  = 8000;
    uint32_t pongWaitMs    = 300;
};

class LinkMonitor {
public:
    // 保持既有调用写法不变（LinkMonitor::Config 仍可用）
    using Config = LinkMonitorConfig;

    LinkMonitor(IChannel* ctrl, CtrlSession* session, ClockSync* sync, Config cfg = Config{});
    ~LinkMonitor();

    void start();
    void stop();

    bool isDown() const { return down_.load(); }
    int64_t lastRttNs() const { return lastRttNs_.load(); }
    int64_t minRttNs() const;
    uint32_t missedBeats() const { return missed_.load(); }
    uint32_t reconnectCount() const { return reconnects_.load(); }

    // 由上层注入的自愈动作：复位传输、重连设备。返回 true 表示成功
    std::function<bool()> onReset;
    std::function<bool()> onReconnect;

    // 读取线程收到 PONG 后调用
    void notifyPong(uint64_t t1Ns, uint64_t t2Ns, uint64_t t3Ns);

private:
    void threadFunc();
    void sleepInterruptible(uint32_t ms);

    IChannel*   ctrl_ = nullptr;
    CtrlSession* session_ = nullptr;
    ClockSync*  sync_ = nullptr;
    Config      cfg_{};

    std::atomic<bool> running_{false};
    std::atomic<bool> down_{false};
    std::atomic<int64_t> lastRttNs_{0};
    std::atomic<int64_t> minRttNs_{0};
    std::atomic<uint32_t> missed_{0};
    std::atomic<uint32_t> reconnects_{0};
    std::atomic<uint64_t> lastPingLocalNs_{0};
    std::atomic<int64_t>  lastPongLocalNs_{0};
    std::mutex  mtx_;
    std::condition_variable cv_;
    std::thread thread_;
};

}  // namespace apxdisp
