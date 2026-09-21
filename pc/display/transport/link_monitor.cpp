#include "transport/link_monitor.hpp"

#include <algorithm>

#include "common/log.hpp"

namespace apxdisp {

LinkMonitor::LinkMonitor(IChannel* ctrl, CtrlSession* session, ClockSync* sync, Config cfg)
    : ctrl_(ctrl), session_(session), sync_(sync), cfg_(cfg) {}

LinkMonitor::~LinkMonitor() { stop(); }

void LinkMonitor::start() {
    if (running_.exchange(true)) return;
    thread_ = std::thread([this] { threadFunc(); });
}

void LinkMonitor::stop() {
    if (!running_.exchange(false)) return;
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void LinkMonitor::sleepInterruptible(uint32_t ms) {
    std::unique_lock<std::mutex> lk(mtx_);
    cv_.wait_for(lk, std::chrono::milliseconds(ms), [this] { return !running_.load(); });
}

void LinkMonitor::threadFunc() {
    uint32_t backoffMs = 1000;
    while (running_.load()) {
        sleepInterruptible(cfg_.heartbeatMs);
        if (!running_.load()) break;

        const int64_t t1 = nowNs();
        const int64_t pongMark = lastPongLocalNs_.load();
        lastPingLocalNs_.store(static_cast<uint64_t>(t1));
        if (session_ && !session_->sendPing(static_cast<uint64_t>(t1))) {
            APX_LOG_W("心跳发送失败");
        }

        // 等 PONG：notifyPong 由读取线程调用，会更新 lastPongLocalNs_
        bool gotPong = false;
        for (uint32_t waited = 0; waited < cfg_.pongWaitMs && running_.load(); waited += 10) {
            if (lastPongLocalNs_.load() != pongMark) { gotPong = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }

        if (gotPong) {
            missed_.store(0);
            backoffMs = 1000;  // 链路健康，重置退避
            if (down_.exchange(false)) APX_LOG_I("链路恢复");
            continue;
        }

        const uint32_t miss = missed_.fetch_add(1) + 1;
        if (miss < cfg_.missThreshold) continue;

        // ---- 断线自愈（PROTOCOL §4：3 次无响应判定断线）----
        down_.store(true);
        APX_LOG_W("连续 %u 次心跳无响应，判定断线，开始自愈", miss);

        bool recovered = false;
        if (onReset) recovered = onReset();
        if (!recovered && onReconnect) recovered = onReconnect();
        if (!recovered && session_) {
            // 最后一招：请手机端重新挂载 Gadget（§4：任意端 BYE 后手机必须恢复原 USB 配置）
            session_->sendBye();
            session_->requestRemount();
        }
        reconnects_.fetch_add(1);
        missed_.store(0);
        if (recovered) {
            down_.store(false);
            backoffMs = 1000;
            APX_LOG_I("自愈成功（第 %u 次重连）", reconnects_.load());
        } else {
            APX_LOG_E("自愈失败，%u ms 后重试", backoffMs);
            sleepInterruptible(backoffMs);
            backoffMs = std::min<uint32_t>(backoffMs * 2, cfg_.maxBackoffMs);
        }
    }
}

void LinkMonitor::notifyPong(uint64_t t1Ns, uint64_t t2Ns, uint64_t t3Ns) {
    const int64_t localRecv = nowNs();
    const int64_t rtt = localRecv - static_cast<int64_t>(t1Ns);
    if (rtt < 0 || rtt > 2000000000LL) return;  // 异常样本丢弃

    lastRttNs_.store(rtt);
    const int64_t prevMin = minRttNs_.load();
    if (prevMin == 0 || rtt < prevMin) minRttNs_.store(rtt);

    // PROTOCOL §1：offset 用 RTT 最小样本估计；ClockSync 内部按 RTT 做加权平滑
    if (sync_) {
        sync_->addSample(static_cast<int64_t>(t1Ns), static_cast<int64_t>(t2Ns), localRecv);
    }
    (void)t3Ns;
    lastPongLocalNs_.store(localRecv);
}

int64_t LinkMonitor::minRttNs() const { return minRttNs_.load(); }

}  // namespace apxdisp
