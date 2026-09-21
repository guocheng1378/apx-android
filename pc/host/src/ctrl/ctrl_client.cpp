#include "apxpc/ctrl/ctrl_client.hpp"

#include <algorithm>

#include "apxpc/log.hpp"
#include "apxpc/version.hpp"
#include "apx/hid_layout.h"  // apx::kCmdVibrate/kCmdTorch/kCmdIrSend（§2.7 命令集）

namespace apxpc::ctrl {

CtrlClient::CtrlClient(std::unique_ptr<ICtrlTransport> transport, CtrlClientOptions opt)
    : transport_(std::move(transport)), opt_(opt) {}

CtrlClient::~CtrlClient() {
    stopHeartbeat();
    close();
}

std::string CtrlClient::transportName() const {
    return transport_ ? transport_->name() : std::string("(none)");
}

StatusEx CtrlClient::open() {
    if (!transport_) return err(Status::NotConnected, "no transport");
    StatusEx s = transport_->open();
    if (!s.ok()) return s;
    {
        std::lock_guard<std::mutex> g(mu_);
        st_.connected = true;
        st_.consecutiveLost = 0;
    }
    return ok();
}

void CtrlClient::close() {
    stopHeartbeat();
    if (transport_) transport_->close();
    std::lock_guard<std::mutex> g(mu_);
    st_.connected = false;
    st_.running   = false;
}

bool CtrlClient::isOpen() const { return transport_ && transport_->isOpen(); }

// ------------------------------------------------------------ 内部收发
StatusEx CtrlClient::sendFrame(const std::vector<uint8_t>& frame) {
    if (!transport_ || !transport_->isOpen()) return err(Status::NotConnected, "transport closed");
    return transport_->send(frame.data(), frame.size());
}

StatusEx CtrlClient::recvMessage(MsgType expect, CtrlMessage& out, unsigned timeoutMs) {
    if (!transport_ || !transport_->isOpen()) return err(Status::NotConnected, "transport closed");

    const uint64_t deadline = monotonicNs() + uint64_t(timeoutMs) * 1000000ull;
    for (;;) {
        std::vector<uint8_t> raw;
        unsigned slice = 50;
        const uint64_t now = monotonicNs();
        if (now >= deadline) return err(Status::Timeout, "no " + std::string(msgTypeName(expect)));
        if (deadline - now < uint64_t(slice) * 1000000ull)
            slice = static_cast<unsigned>((deadline - now) / 1000000ull);

        StatusEx s = transport_->recv(raw, slice);
        if (s.code == Status::Timeout) {
            if (monotonicNs() >= deadline) return err(Status::Timeout, "no " + std::string(msgTypeName(expect)));
            continue;
        }
        if (!s.ok()) return s;
        if (raw.empty()) continue;

        CtrlMessage m;
        StatusEx d = decodeFrame(raw.data(), raw.size(), m);
        if (!d.ok()) {
            APX_LOGW("丢弃无法解析的控制帧: {}", d.message);
            continue;
        }
        if (expect != MsgType::Unknown && m.type != expect) {
            APX_LOGD("跳过非预期消息: {} (期望 {})", msgTypeName(m.type), msgTypeName(expect));
            continue;
        }
        out = std::move(m);
        return ok();
    }
}

// ------------------------------------------------------------ §4 握手
StatusEx CtrlClient::handshake() {
    if (!isOpen()) {
        StatusEx s = open();
        if (!s.ok()) return s;
    }

    const uint32_t seq = seq_++;
    const uint64_t pcNow = monotonicNs();
    StatusEx s = sendFrame(buildHello(seq, kProtocolVersion, pcNow, opt_.capabilities));
    if (!s.ok()) return s;

    CtrlMessage m;
    s = recvMessage(MsgType::HelloAck, m, opt_.handshakeTimeoutMs);
    if (!s.ok()) return s;

    HelloAckInfo ack;
    s = parseHelloAck(m, ack);
    if (!s.ok()) return s;
    if ((ack.protocolVersion >> 8) != kProtocolVersionMajor)
        APX_LOGW("协议主版本不一致: PC={}.{} 手机={}.{}, 按低版本字段集兼容运行",
                 kProtocolVersionMajor, kProtocolVersionMinor,
                 (ack.protocolVersion >> 8), (ack.protocolVersion & 0xFF));

    {
        std::lock_guard<std::mutex> g(mu_);
        helloAck_ = ack;
        st_.protocolVersion = ack.protocolVersion;
        st_.udcSpeed        = ack.udcSpeed;
        st_.moduleMask      = ack.enabledModules;
        st_.capabilities    = ack.capabilities;
        st_.connected       = true;
    }

    // 握手后立刻做一次 ping/pong，拿到首个 RTT 与时钟偏移（§1）
    (void)heartbeatOnce();
    APX_LOGI("握手完成: 协议=0x{} 链路速度={} 传感器数={} 传输={}",
             (int)ack.protocolVersion, (int)ack.udcSpeed, (int)ack.sensorList.size(),
             transportName());
    return ok();
}

StatusEx CtrlClient::configure(uint64_t sensorMask, const std::vector<SampleRate>& rates,
                               uint8_t displayMode, const VideoParams& video) {
    if (!isOpen()) return err(Status::NotConnected, "not connected");

    lastRates_       = rates;
    lastDisplayMode_ = displayMode;
    lastVideo_       = video;

    const uint32_t seq = seq_++;
    StatusEx s = sendFrame(buildConfig(seq, sensorMask, rates, displayMode, video));
    if (!s.ok()) return s;

    CtrlMessage m;
    s = recvMessage(MsgType::ConfigAck, m, opt_.handshakeTimeoutMs);
    if (!s.ok()) return s;

    ConfigAckInfo ack;
    s = parseConfigAck(m, ack);
    if (!s.ok()) return s;

    {
        std::lock_guard<std::mutex> g(mu_);
        configAck_        = ack;
        st_.running       = true;
        st_.sensorMask    = ack.appliedSensorMask ? ack.appliedSensorMask : sensorMask;
        st_.runStatus     = ack.runStatus;
        st_.errorCode     = ack.errorCode;
    }
    if (ack.errorCode != 0)
        APX_LOGW("CONFIG_ACK 携带错误码 {}", (int)ack.errorCode);
    return ok();
}

StatusEx CtrlClient::replayConfig() {
    uint64_t mask = 0;
    {
        std::lock_guard<std::mutex> g(mu_);
        mask = st_.sensorMask;
    }
    return configure(mask, lastRates_, lastDisplayMode_, lastVideo_);
}

StatusEx CtrlClient::bye(const std::string& reason) {
    if (!isOpen()) return ok();
    StatusEx s = sendFrame(buildBye(seq_++, reason));
    std::lock_guard<std::mutex> g(mu_);
    st_.running   = false;
    st_.connected = false;
    return s;
}

// ------------------------------------------------------------ 运行期下发
StatusEx CtrlClient::setSensorMask(uint64_t mask) {
    return configure(mask, lastRates_, lastDisplayMode_, lastVideo_);
}

StatusEx CtrlClient::setSampleRate(uint8_t sensorId, uint32_t rateHz) {
    std::vector<SampleRate> rates = lastRates_;
    bool replaced = false;
    for (auto& r : rates) {
        if (r.sensorId == sensorId) {
            r.rateMilliHz = rateHz * 1000u;
            replaced = true;
        }
    }
    if (!replaced) rates.push_back(SampleRate{sensorId, rateHz * 1000u});
    uint64_t mask = 0;
    {
        std::lock_guard<std::mutex> g(mu_);
        mask = st_.sensorMask;
    }
    return configure(mask, rates, lastDisplayMode_, lastVideo_);
}

StatusEx CtrlClient::setDisplayMode(uint8_t mode) {
    uint64_t mask = 0;
    {
        std::lock_guard<std::mutex> g(mu_);
        mask = st_.sensorMask;
    }
    return configure(mask, lastRates_, mode, lastVideo_);
}

// ------------------------------------------------------------ Vendor Report 5
StatusEx CtrlClient::sendVendorRaw(uint8_t cmd, const std::vector<uint8_t>& payload) {
    if (!isOpen()) return err(Status::NotConnected, "not connected");
    auto frame = buildVendorCommand(cmd, static_cast<uint8_t>(seq_++ & 0xFF), payload);
    return transport_->send(frame.data(), frame.size());
}

StatusEx CtrlClient::vibrate(uint16_t durationMs, uint8_t amplitude) {
    std::vector<uint8_t> p;
    putU16(p, durationMs);
    putU8(p, amplitude);
    return sendVendorRaw(apx::kCmdVibrate, p);
}

StatusEx CtrlClient::stopVibrate() { return sendVendorRaw(apx::kCmdVibrateStop, {}); }

StatusEx CtrlClient::torch(bool on, uint8_t level) {
    std::vector<uint8_t> p;
    putU8(p, on ? 1 : 0);
    putU8(p, level);
    return sendVendorRaw(apx::kCmdTorch, p);
}

StatusEx CtrlClient::irSend(uint32_t freqHz, const std::vector<uint16_t>& pattern) {
    std::vector<uint8_t> p;
    putU32(p, freqHz);
    putU16(p, static_cast<uint16_t>(pattern.size()));
    for (uint16_t v : pattern) putU16(p, v);
    return sendVendorRaw(apx::kCmdIrSend, p);
}

// ------------------------------------------------------------ 心跳 / 自愈
StatusEx CtrlClient::heartbeatOnce() {
    if (!isOpen()) return err(Status::NotConnected, "not connected");

    const uint32_t seq = seq_++;
    const int64_t  t0  = static_cast<int64_t>(monotonicNs());
    StatusEx s = sendFrame(buildPing(seq, static_cast<uint64_t>(t0)));
    if (!s.ok()) return s;

    CtrlMessage m;
    s = recvMessage(MsgType::Pong, m, opt_.heartbeatTimeoutMs);
    const int64_t t1 = static_cast<int64_t>(monotonicNs());

    std::lock_guard<std::mutex> g(mu_);
    ++st_.pingsSent;
    if (!s.ok()) {
        ++st_.pingsLost;
        ++st_.consecutiveLost;
        return s;
    }
    uint32_t  pongSeq = 0;
    uint64_t  phoneTs = 0, uptime = 0;
    StatusEx p = parsePong(m, pongSeq, phoneTs, uptime);
    if (!p.ok()) {
        ++st_.pingsLost;
        ++st_.consecutiveLost;
        return p;
    }
    clockSync_.addSample(t0, static_cast<int64_t>(phoneTs), t1);
    st_.rttNs      = t1 - t0;
    st_.minRttNs   = clockSync_.minRttNs();
    st_.offsetNs   = clockSync_.offsetNs();
    st_.uptimeMs   = uptime;
    st_.lastPongNs = static_cast<uint64_t>(t1);
    st_.consecutiveLost = 0;
    return ok();
}

void CtrlClient::heartbeatThread() {
    while (hbRunning_.load()) {
        for (unsigned i = 0; i < opt_.heartbeatIntervalMs && hbRunning_.load(); i += 20)
            sleepMs(20);

        DisconnectHandler h;
        bool tooManyLost = false;
        {
            std::lock_guard<std::mutex> g(mu_);
            tooManyLost = st_.consecutiveLost >= opt_.maxMissedHeartbeats;
            h = onDisconnect_;
        }
        if (tooManyLost) {
            APX_LOGW("连续 {} 次心跳无响应，判定断线", (int)opt_.maxMissedHeartbeats);
            {
                std::lock_guard<std::mutex> g(mu_);
                st_.connected = false;
                st_.running   = false;
            }
            if (h) h(Status::Timeout);
            if (!opt_.autoReconnect) break;
            StatusEx r = selfHeal();
            APX_LOGI("自愈结果: {}", r.ok() ? std::string("已恢复") : statusToString(r));
            if (!r.ok() && h) h(Status::NotConnected);
            continue;
        }

        StatusEx s = heartbeatOnce();
        if (!s.ok()) APX_LOGD("心跳失败: {}", statusToString(s));
    }
}

StatusEx CtrlClient::startHeartbeat() {
    if (hbRunning_.load()) return ok();
    hbRunning_.store(true);
    hbThread_ = std::thread([this] { heartbeatThread(); });
    return ok();
}

void CtrlClient::stopHeartbeat() {
    if (!hbRunning_.load()) return;
    hbRunning_.store(false);
    if (hbThread_.joinable()) hbThread_.join();
}

StatusEx CtrlClient::selfHeal() {
    APX_LOGI("开始自愈：关闭传输并重新握手");
    if (transport_) transport_->close();
    sleepMs(opt_.reconnectDelayMs);

    for (unsigned attempt = 1; attempt <= std::max(1u, opt_.maxReconnectAttempts); ++attempt) {
        StatusEx s = open();
        if (s.ok()) s = handshake();
        if (s.ok()) {
            clockSync_.reset();
            StatusEx c = replayConfig();
            APX_LOGI("自愈成功（第 {} 次尝试），CONFIG 重放 {}", (int)attempt,
                     c.ok() ? std::string("成功") : statusToString(c));
            return ok();
        }
        APX_LOGW("自愈第 {} 次尝试失败: {}", (int)attempt, statusToString(s));
        sleepMs(opt_.reconnectDelayMs * attempt);
    }
    return err(Status::NotConnected, "自愈失败：多次重连均未成功");
}

StatusEx CtrlClient::pollVendorStatus(VendorStatusInfo& out) {
    if (!isOpen()) return err(Status::NotConnected, "not connected");
    std::vector<uint8_t> raw;
    StatusEx s = transport_->recv(raw, 20);
    if (!s.ok()) return s;
    StatusEx p = parseVendorStatus(raw.data(), raw.size(), out);
    if (p.ok()) {
        std::lock_guard<std::mutex> g(mu_);
        st_.runStatus  = out.status;
        st_.udcSpeed   = out.linkSpeed;
        st_.moduleMask = out.moduleMask;
        st_.errorCode  = out.errorCode;
        st_.uptimeMs   = out.uptimeMs;
    }
    return p;
}

// ------------------------------------------------------------ 观测
LinkStatus CtrlClient::status() const {
    std::lock_guard<std::mutex> g(mu_);
    return st_;
}

HelloAckInfo CtrlClient::helloAck() const {
    std::lock_guard<std::mutex> g(mu_);
    return helloAck_;
}

void CtrlClient::setDisconnectHandler(DisconnectHandler h) {
    std::lock_guard<std::mutex> g(mu_);
    onDisconnect_ = std::move(h);
}

}  // namespace apxpc::ctrl
