#include "apxpc/wireless/wireless_session.hpp"

#include "apxpc/log.hpp"

#include <chrono>

namespace apxpc::wireless {
namespace {

/// worker 轮询周期：够快让按钮手感"跟手"，又不至于空转烧 CPU
constexpr auto kTick = std::chrono::milliseconds(250);

}  // namespace

const char* linkPhaseName(LinkPhase p) noexcept {
    switch (p) {
        case LinkPhase::Idle:        return "未启用";
        case LinkPhase::Discovering: return "正在发现手机";
        case LinkPhase::Connecting:  return "正在连接";
        case LinkPhase::Connected:   return "已连接";
        case LinkPhase::Failed:      return "连接失败";
        default:                     return "?";
    }
}

WirelessSession::WirelessSession() {
    running_.store(true);
    worker_ = std::thread(&WirelessSession::worker, this);
}

WirelessSession::~WirelessSession() { stop(); }

void WirelessSession::stop() {
    if (!running_.exchange(false)) return;
    if (worker_.joinable()) worker_.join();
    beacon_.stop();
    link_.disconnect();   // 幂等
}

// ————————————————————————————— 意图（UI 线程调用） —————————————————————————————

void WirelessSession::startAuto() {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Auto, {}, 0, true};
    snap_.error.clear();
    APX_LOGI("无线会话：切到自动发现模式");
}

void WirelessSession::connectManual(const std::string& host, uint16_t port) {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Manual, host, port, true};
    snap_.error.clear();
    APX_LOGI("无线会话：手动连接 {}:{}", host.c_str(), static_cast<unsigned>(port));
}

void WirelessSession::disconnect() {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Idle, {}, 0, true};
    snap_.error.clear();
    APX_LOGI("无线会话：断开");
}

SessionSnapshot WirelessSession::snapshot() const {
    std::lock_guard<std::mutex> lk(mu_);
    return snap_;
}

void WirelessSession::requestOpenScreen() {
    link_.requestOpenScreen();   // 只置原子标志，未连接时标志会被下次建链前的循环带过（无害）
}

void WirelessSession::requestModule(int idx, bool on) {
    link_.requestModule(idx, on);
}

bool WirelessSession::takeKeyFrameRequest() {
    return link_.takeKeyFrameRequest();
}

void WirelessSession::setTouchRect(int x, int y, int w, int h) {
    link_.setTouchRect(x, y, w, h);
}

// ————————————————————————————— 发现回调（信标线程） —————————————————————————————

void WirelessSession::onBeacon(const PhoneBeacon& pb) {
    std::lock_guard<std::mutex> lk(mu_);
    if (haveBeacon_) return;   // 已有一个待连目标就不再刷新，避免抖动
    beaconHost_ = pb.host;
    beaconPort_ = pb.port;
    haveBeacon_ = true;
}

// ————————————————————————————— worker（唯一做 I/O 的线程） —————————————————————————————

void WirelessSession::worker() {
    Mode mode = Mode::Idle;
    std::string host;
    uint16_t port = 0;
    bool attempt = false;

    while (running_.load()) {
        // 1) 取用户意图（有 fresh 才改本地状态）
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (desire_.fresh) {
                desire_.fresh = false;
                mode = desire_.mode;
                host = desire_.host;
                port = desire_.port;
                attempt = (mode != Mode::Idle);
                haveBeacon_ = false;   // 换模式就丢弃旧信标
            }
        }

        if (mode == Mode::Idle) {
            if (beacon_.running()) beacon_.stop();
            if (link_.status().connected) link_.disconnect();
            publish(LinkPhase::Idle, mode);
        } else if (mode == Mode::Auto) {
            if (link_.status().connected) {
                publish(LinkPhase::Connected, mode);
            } else {
                // 断线后自动回到等待：不需要用户做任何事
                if (!beacon_.running()) {
                    beacon_.start([this](const PhoneBeacon& pb) { onBeacon(pb); });
                }
                PhoneBeacon pb;
                bool go = false;
                {
                    std::lock_guard<std::mutex> lk(mu_);
                    if (haveBeacon_) {
                        pb.host = beaconHost_;
                        pb.port = beaconPort_;
                        haveBeacon_ = false;
                        go = true;
                    }
                }
                if (!go) {
                    publish(LinkPhase::Discovering, mode);
                } else {
                    publish(LinkPhase::Connecting, mode);
                    if (link_.connect(pb.host, pb.port, "")) {
                        publish(LinkPhase::Connected, mode);
                    } else {
                        // 连不上就丢回等待信标，下一个信标再试
                        publish(LinkPhase::Discovering, mode);
                    }
                }
            }
        } else {  // Manual
            if (beacon_.running()) beacon_.stop();
            if (link_.status().connected) {
                publish(LinkPhase::Connected, mode);
            } else if (attempt) {
                attempt = false;
                publish(LinkPhase::Connecting, mode);
                if (link_.connect(host, port, "")) publish(LinkPhase::Connected, mode);
                else publish(LinkPhase::Failed, mode);
            } else {
                // 手动模式失败后**不自动重试**：反复重连只会让用户以为卡住
                publish(LinkPhase::Failed, mode);
            }
        }

        std::this_thread::sleep_for(kTick);
    }
}

void WirelessSession::publish(LinkPhase ph, Mode m) {
    const auto ls = link_.status();          // 先取，避免持 mu_ 时再进 link_ 的锁
    const auto cs = link_.counters();
    std::lock_guard<std::mutex> lk(mu_);
    const bool changed = snap_.phase != ph;
    snap_.phase = ph;
    snap_.autoMode = (m == Mode::Auto);
    snap_.peer = ls.peer;
    snap_.rttMs = ls.rttMs;
    snap_.upMs = ls.upMs;
    snap_.error = ls.error;
    snap_.counters = cs;
    // 手机侧 Wi‑Fi 音频模块状态（随控制面 'a' 状态帧上报，透传到面板）
    snap_.phoneAudioKnown = ls.phoneAudioKnown;
    snap_.phoneAudioState = ls.phoneAudioState;
    snap_.phoneAudioSpk = ls.phoneAudioSpk;
    snap_.phoneAudioMic = ls.phoneAudioMic;
    snap_.phoneAudioDropped = ls.phoneAudioDropped;
    // 手机端全模块状态（'M' 状态帧）
    for (int i = 0; i < 8; ++i) snap_.phoneModules[i] = link_.phoneModuleState(i);
    if (changed) APX_LOGI("无线会话状态：{}", linkPhaseName(ph));
}

}  // namespace apxpc::wireless
