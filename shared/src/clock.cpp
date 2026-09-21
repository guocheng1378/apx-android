// 时钟：本端单调时基 + 两端偏移/漂移估计（§1、§6.4）
#include "apx/clock.h"

#if defined(_WIN32)
#include <windows.h>
#else
#include <time.h>
#endif

namespace apx {

int64_t steadyNowNs() {
#if defined(_WIN32)
    LARGE_INTEGER counter;
    LARGE_INTEGER freq;
    if (!QueryPerformanceCounter(&counter) || !QueryPerformanceFrequency(&freq) ||
        freq.QuadPart == 0) {
        return 0;
    }
    const double ticksPerNs = static_cast<double>(freq.QuadPart) / 1000000000.0;
    return static_cast<int64_t>(static_cast<double>(counter.QuadPart) / ticksPerNs);
#else
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return static_cast<int64_t>(ts.tv_sec) * 1000000000ll + static_cast<int64_t>(ts.tv_nsec);
#endif
}

void ClockSync::reset() {
    have_ = false;
    offsetNs_ = 0;
    bestOffNs_ = 0;
    minRttNs_ = INT64_MAX_VALUE_SENTINEL();
    lastRttNs_ = 0;
    driftPpm_ = 0.0;
    samples_ = 0;
    firstPhoneNs_ = 0;
    firstOffNs_ = 0;
}

void ClockSync::addSample(int64_t pcSendNs, int64_t phoneTsNs, int64_t pcRecvNs) {
    const int64_t rtt = pcRecvNs - pcSendNs;
    if (rtt < 0) return;  // 时基异常，丢弃样本

    // pc_mid ≈ 手机打时间戳的那一刻（PC 时基）
    const int64_t off = (pcSendNs + rtt / 2) - phoneTsNs;

    if (!have_) {
        offsetNs_ = off;
        have_ = true;
    } else {
        const double smoothed = alpha_ * static_cast<double>(offsetNs_) +
                                (1.0 - alpha_) * static_cast<double>(off);
        offsetNs_ = static_cast<int64_t>(smoothed);
    }

    // 最小 RTT 样本最可信（§1：offset = min(RTT 样本)）
    if (rtt < minRttNs_) {
        minRttNs_ = rtt;
        bestOffNs_ = off;
    }
    lastRttNs_ = rtt;
    ++samples_;

    // 漂移：offset 相对手机时间的变化率
    if (samples_ == 1) {
        firstPhoneNs_ = phoneTsNs;
        firstOffNs_ = offsetNs_;
    } else {
        const int64_t dt = phoneTsNs - firstPhoneNs_;
        if (dt > 0) {
            driftPpm_ = static_cast<double>(offsetNs_ - firstOffNs_) * 1.0e6 /
                        static_cast<double>(dt);
        }
    }
}

ClockSync::Result ClockSync::result() const {
    Result r;
    r.valid = samples_ > 0;
    r.offsetNs = offsetNs_;
    r.driftPpm = driftPpm_;
    r.minRttNs = (minRttNs_ == INT64_MAX_VALUE_SENTINEL()) ? 0 : minRttNs_;
    r.lastRttNs = lastRttNs_;
    r.samples = samples_;
    return r;
}

}  // namespace apx
