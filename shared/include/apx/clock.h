// ============================================================================
// 时钟与时间戳同步（docs/PROTOCOL.md §1、ARCHITECTURE.md §6.4）
//
// 手机端时基：SystemClock.elapsedRealtimeNanos()（单调，含深睡）
// PC 端时基 ：QueryPerformanceCounter()（Windows） / CLOCK_MONOTONIC（Linux）
// 传输时间戳统一用手机端时基；PC 侧用本文件把两端时基对齐：
//     pc_time ≈ phone_time + offset
// offset 取 RTT 最小样本（最不受排队延迟污染），再用指数平滑抑制抖动；
// drift 由 offset 随时间的变化率估计（ppm）。
// ============================================================================
#pragma once
#include <cstdint>

namespace apx {

// 本端单调时钟（纳秒）。起点无意义，只用于测间隔。
int64_t steadyNowNs();

class ClockSync {
public:
    struct Result {
        bool     valid      = false;  // 至少 1 个样本
        int64_t  offsetNs   = 0;      // pc ≈ phone + offsetNs
        double   driftPpm   = 0.0;    // 手机时钟相对 PC 的漂移（百万分比）
        int64_t  minRttNs   = 0;      // 历史最小 RTT
        int64_t  lastRttNs  = 0;      // 最近一次 RTT
        uint32_t samples    = 0;
    };

    // alpha ∈ [0,1)：offset 的指数平滑系数，越大越平滑
    explicit ClockSync(double alpha = 0.8) noexcept : alpha_(alpha) {}

    void reset();

    // 一次 ping/pong：PC 发送时刻、手机在报告里回的时间戳、PC 收到时刻
    void addSample(int64_t pcSendNs, int64_t phoneTsNs, int64_t pcRecvNs);

    Result result() const;
    int64_t offsetNs() const { return offsetNs_; }
    double  driftPpm() const { return driftPpm_; }
    bool    valid() const { return samples_ > 0; }

    int64_t phoneToPc(int64_t phoneNs) const { return phoneNs + offsetNs_; }
    int64_t pcToPhone(int64_t pcNs) const { return pcNs - offsetNs_; }

private:
    double   alpha_;
    bool     have_      = false;
    int64_t  offsetNs_  = 0;     // 平滑后的偏移
    int64_t  bestOffNs_ = 0;     // 最小 RTT 样本的偏移
    int64_t  minRttNs_  = INT64_MAX_VALUE_SENTINEL();
    int64_t  lastRttNs_ = 0;
    double   driftPpm_  = 0.0;
    uint32_t samples_   = 0;
    int64_t  firstPhoneNs_ = 0;  // 漂移估计基准
    int64_t  firstOffNs_   = 0;

    static int64_t INT64_MAX_VALUE_SENTINEL() { return static_cast<int64_t>(0x7FFFFFFFFFFFFFFFll); }
};

}  // namespace apx
