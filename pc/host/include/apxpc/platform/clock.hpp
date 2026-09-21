#pragma once
// PROTOCOL §1：PC 端时基 = QueryPerformanceCounter / clock_gettime(CLOCK_MONOTONIC)
#include <chrono>
#include <cstdint>

namespace apxpc {

// 单调时间（纳秒），用于时间戳与 RTT
uint64_t monotonicNs() noexcept;

// 相对某起点经过的纳秒
uint64_t elapsedNs(uint64_t start, uint64_t now) noexcept;

// Unix 纪元纳秒（打日志用，不参与协议时间戳）
uint64_t unixEpochNs() noexcept;

// 让当前线程睡 ms
void sleepMs(unsigned ms) noexcept;

// 便捷计时器
class StopWatch {
public:
    void   reset() { start_ = monotonicNs(); }
    double elapsedMs() const { return double(monotonicNs() - start_) / 1e6; }
    uint64_t elapsedNs() const { return apxpc::elapsedNs(start_, monotonicNs()); }

private:
    uint64_t start_{monotonicNs()};
};

// 时钟同步：PROTOCOL §1 要求取“RTT 最小样本”估计偏移
//   pcNs ≈ phoneNs + offsetNs_
class ClockSync {
public:
    struct Result {
        int64_t offsetNs{0};   // pc - phone
        int64_t rttNs{0};      // 当前样本 RTT
        int64_t minRttNs{0};   // 历史最小 RTT
        int     validSamples{0};
    };

    // pcSendNs：PC 发出 ping 的本地单调时间
    // phoneTsNs：手机在回应里填写的“收到 ping 时的手机时基”
    // pcRecvNs：PC 收到 pong 的本地单调时间
    void addSample(int64_t pcSendNs, int64_t phoneTsNs, int64_t pcRecvNs);

    void   reset();
    Result result() const;

    int64_t offsetNs() const { return offsetNs_; }
    int64_t minRttNs() const { return minRttNs_; }
    // 手机时间戳 -> PC 单调时间戳
    int64_t phoneToPcNs(int64_t phoneTsNs) const { return phoneTsNs + offsetNs_; }

private:
    int64_t offsetNs_{0};
    int64_t minRttNs_{INT64_MAX};
    int     validSamples_{0};
};

}  // namespace apxpc
