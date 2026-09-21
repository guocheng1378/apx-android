#include "apxpc/platform/clock.hpp"

#include <algorithm>
#include <thread>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#else
#  include <time.h>
#endif

namespace apxpc {

#if defined(_WIN32)
static double qpcTicksPerNs() {
    LARGE_INTEGER freq;
    QueryPerformanceFrequency(&freq);
    return double(freq.QuadPart) / 1.0e9;  // ticks/s -> ticks/ns
}
#endif

uint64_t monotonicNs() noexcept {
#if defined(_WIN32)
    static const double tpn = qpcTicksPerNs();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return static_cast<uint64_t>(double(c.QuadPart) / tpn);
#else
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return uint64_t(ts.tv_sec) * 1000000000ull + uint64_t(ts.tv_nsec);
#endif
}

uint64_t elapsedNs(uint64_t start, uint64_t now) noexcept {
    return (now >= start) ? (now - start) : 0;
}

uint64_t unixEpochNs() noexcept {
    return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::system_clock::now().time_since_epoch())
                        .count());
}

void sleepMs(unsigned ms) noexcept { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

// ---------------------------------------------------------------- ClockSync
void ClockSync::reset() {
    offsetNs_ = 0;
    minRttNs_ = INT64_MAX;
    validSamples_ = 0;
}

void ClockSync::addSample(int64_t pcSendNs, int64_t phoneTsNs, int64_t pcRecvNs) {
    const int64_t rtt = pcRecvNs - pcSendNs;
    if (rtt < 0) return;  // 时基异常，丢弃

    // PROTOCOL §1：offset = min(RTT 样本)
    if (rtt < minRttNs_) {
        minRttNs_ = rtt;
        // 以单向延迟 = rtt/2 估计发出时刻的手机时基
        const int64_t pcAtPhoneMid = pcSendNs + rtt / 2;
        offsetNs_ = pcAtPhoneMid - phoneTsNs;  // pcNs = phoneNs + offset
    }
    ++validSamples_;
}

ClockSync::Result ClockSync::result() const {
    Result r;
    r.offsetNs      = (validSamples_ > 0) ? offsetNs_ : 0;
    r.minRttNs      = (validSamples_ > 0) ? minRttNs_ : 0;
    r.rttNs         = r.minRttNs;
    r.validSamples  = validSamples_;
    return r;
}

}  // namespace apxpc
