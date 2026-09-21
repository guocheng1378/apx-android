#include "apxpc/bandwidth/arbiter.hpp"
#include "apxpc/log.hpp"

#include <algorithm>

namespace apxpc::bandwidth {

void BandwidthArbiter::setBudget(double wiredMbps, double wirelessMbps) {
    std::lock_guard<std::mutex> lk(mu_);
    wiredBudget_ = wiredMbps > 0 ? wiredMbps : 300.0;
    wirelessBudget_ = wirelessMbps > 0 ? wirelessMbps : 80.0;
}

void BandwidthArbiter::setMode(const std::string& mode) {
    std::lock_guard<std::mutex> lk(mu_);
    mode_ = mode;
}

void BandwidthArbiter::setWirelessBudget(double mbps) {
    std::lock_guard<std::mutex> lk(mu_);
    if (mbps > 0) wirelessBudget_ = mbps;
}

std::string BandwidthArbiter::mode() const {
    std::lock_guard<std::mutex> lk(mu_);
    return mode_;
}

double BandwidthArbiter::currentBudget() const {
    return mode_ == "wireless" ? wirelessBudget_ : wiredBudget_;
}

void BandwidthArbiter::setDemand(const std::string& name, Prio prio, double mbps, bool active) {
    std::lock_guard<std::mutex> lk(mu_);
    for (auto& d : demands_) {
        if (d.name == name) {
            d.prio = prio; d.mbps = mbps; d.active = active;
            return;
        }
    }
    demands_.push_back({name, prio, mbps, active});
}

BandwidthReport BandwidthArbiter::compute() {
    std::lock_guard<std::mutex> lk(mu_);
    BandwidthReport rep;
    rep.totalMbps = currentBudget();

    // 收集活跃需求，按优先级升序（重要在前）
    std::vector<Demand> act;
    double requested = 0;
    for (const auto& d : demands_) {
        if (d.active && d.mbps > 0) { act.push_back(d); requested += d.mbps; }
    }
    std::sort(act.begin(), act.end(), [](const Demand& a, const Demand& b) {
        return static_cast<int>(a.prio) < static_cast<int>(b.prio);
    });

    double budget = rep.totalMbps;
    double used = 0;
    rep.requestedMbps = requested;

    std::vector<std::string> degradedNow;
    for (const auto& d : act) {
        UsageItem it;
        it.name = d.name;
        it.prio = static_cast<int>(d.prio);
        double grant = d.mbps;
        if (used + grant > budget) {
            // 降级：先尝试按比例压缩，再不行则完全关停
            double remain = budget - used;
            if (remain > 0.5) grant = remain;       // 压缩到剩余预算
            else grant = 0;                          // 完全降级
            it.degraded = (grant < d.mbps);
            if (it.degraded) degradedNow.push_back(
                d.name + (grant == 0 ? "(关停)" : "(压缩到 " + std::to_string((int)grant) + "Mbps)"));
        }
        it.mbps = grant;
        used += grant;
        rep.items.push_back(std::move(it));
    }
    rep.usedMbps = used;

    // 降级日志：仅当降级集合变化时记录，避免刷屏
    if (degradedNow != lastDegraded_) {
        std::string list;
        for (size_t i = 0; i < degradedNow.size(); ++i) { if (i) list += ", "; list += degradedNow[i]; }
        if (degradedNow.empty())
            APX_LOGI("带宽仲裁：预算 {}Mbps 满足全部需求（{}Mbps）",
                     static_cast<int>(budget), static_cast<int>(requested));
        else
            APX_LOGW("带宽仲裁：预算 {}Mbps < 需求 {}Mbps，降级 {}",
                     static_cast<int>(budget), static_cast<int>(requested), list.c_str());
        lastDegraded_ = degradedNow;
    }
    return rep;
}

}  // namespace apxpc::bandwidth
