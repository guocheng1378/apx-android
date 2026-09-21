// 时钟与跨端时基同步（PROTOCOL §1）
//
// PC 端时基：QueryPerformanceCounter()（Windows）/ CLOCK_MONOTONIC（Linux）
// 手机端时基：SystemClock.elapsedRealtimeNanos()
// 转换约定（本模块内部统一）：
//     remoteNs(手机) = localNs(PC) + offsetNs()
// 其中 offset 取「RTT 最小样本」估计：offset = remoteRecv - (localSend + localRecv) / 2
// 同一个 offset 再做指数平滑，并用相邻两次估计的斜率得到 drift（ppm）。
#pragma once

#include <cstdint>

namespace apxdisp {

// 本机单调时间（纳秒）
int64_t nowNs();

// 墙钟时间（UTC 纳秒，仅用于日志）
int64_t wallNowNs();

class ClockSync {
public:
    void reset();

    // 一次 ping/pong 样本：本机发送 / 对端接收 / 本机收到应答
    void addSample(int64_t localSendNs, int64_t remoteRecvNs, int64_t localRecvNs);

    bool     valid() const   { return hasEstimate_; }
    int64_t  offsetNs() const { return offsetNs_; }
    int64_t  minRttNs() const { return minRttNs_; }
    int64_t  lastRttNs() const { return lastRttNs_; }
    double   driftPpm() const { return driftPpm_; }
    uint32_t sampleCount() const { return sampleCount_; }

    // 本端时间 -> 对端（手机）时间，供 ptsNs 使用
    int64_t toRemoteNs(int64_t localNs) const { return localNs + offsetNs_; }
    // 对端时间 -> 本端时间
    int64_t toLocalNs(int64_t remoteNs) const { return remoteNs - offsetNs_; }

private:
    bool     hasEstimate_ = false;
    int64_t  offsetNs_    = 0;
    int64_t  minRttNs_    = 0;
    int64_t  lastRttNs_   = 0;
    int64_t  lastSampleLocalNs_ = 0;
    double   driftPpm_    = 0.0;
    uint32_t sampleCount_ = 0;
};

}  // namespace apxdisp
