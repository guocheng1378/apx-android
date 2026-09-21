#include "common/clock.hpp"

#include <algorithm>
#include <cmath>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <ctime>
#endif

namespace apxdisp {

#ifdef _WIN32
namespace {
int64_t qpcFrequency() {
    LARGE_INTEGER f{};
    QueryPerformanceFrequency(&f);
    return f.QuadPart ? f.QuadPart : 1;
}
}  // namespace

int64_t nowNs() {
    static const int64_t freq = qpcFrequency();
    LARGE_INTEGER c{};
    QueryPerformanceCounter(&c);
    const int64_t whole  = c.QuadPart / freq;
    const int64_t remain = c.QuadPart % freq;
    return whole * 1000000000LL + (remain * 1000000000LL) / freq;
}

int64_t wallNowNs() {
    FILETIME ft{};
    GetSystemTimePreciseAsFileTime(&ft);
    ULARGE_INTEGER u{};
    u.LowPart  = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    // 1601-01-01 -> 1970-01-01 = 11644473600 秒
    const uint64_t since1601 = u.QuadPart;  // 100ns 单位
    const uint64_t unix100ns = since1601 - 116444736000000000ULL;
    return static_cast<int64_t>(unix100ns * 100ULL);
}
#else
int64_t nowNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

int64_t wallNowNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}
#endif

void ClockSync::reset() {
    hasEstimate_ = false;
    offsetNs_    = 0;
    minRttNs_    = 0;
    lastRttNs_   = 0;
    lastSampleLocalNs_ = 0;
    driftPpm_    = 0.0;
    sampleCount_ = 0;
}

void ClockSync::addSample(int64_t localSendNs, int64_t remoteRecvNs, int64_t localRecvNs) {
    const int64_t rtt = localRecvNs - localSendNs;
    if (rtt < 0) return;  // 时基异常，丢弃
    lastRttNs_ = rtt;

    // PROTOCOL §1：用 RTT 最小样本估计 offset
    const int64_t instantOffset = remoteRecvNs - (localSendNs + rtt / 2);

    if (!hasEstimate_) {
        offsetNs_    = instantOffset;
        minRttNs_    = rtt;
        hasEstimate_ = true;
    } else {
        minRttNs_ = std::min(minRttNs_, rtt);
        const double alpha = (rtt <= minRttNs_) ? 0.5 : 0.1;  // 好样本权重更高
        const double prev  = static_cast<double>(offsetNs_);
        offsetNs_ = static_cast<int64_t>(prev + alpha * (static_cast<double>(instantOffset) - prev));

        // drift：两次 offset 估计之差 / 时间差
        const int64_t dt = localRecvNs - lastSampleLocalNs_;
        if (dt > 1000000000LL && lastSampleLocalNs_ != 0) {
            const double dOffset = static_cast<double>(instantOffset) - prev;
            driftPpm_ = driftPpm_ * 0.8 + (dOffset / static_cast<double>(dt) * 1e6) * 0.2;
        }
    }
    lastSampleLocalNs_ = localRecvNs;
    ++sampleCount_;
}

}  // namespace apxdisp
