#include "apxpc/wireless/wireless_session.hpp"

#include "apxpc/log.hpp"

#include <chrono>
#include <string_view>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace apxpc::wireless {
namespace {

constexpr auto kTick = std::chrono::milliseconds(250);
constexpr uint16_t kBeaconPort = 9501;

#if defined(_WIN32)
using Sock = SOCKET;   // Windows 套接字句柄（原 wireless_link.hpp 里的别名，迁移后此处自带）
#else
using Sock = int;      // POSIX 文件描述符
#endif

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

/// 解析 "APX1TV <name> <port> <token>"，返回 (host-ip, port)。name/token 忽略。
bool parseBeacon(const char* buf, size_t n, std::string& host, uint16_t& port) {
    // 简单按空格切分，取第 2、3 段（name、port）。host 由调用方从来源地址填入。
    std::string_view s(buf, n);
    // 必须以 "APX1TV" 开头
    if (s.size() < 6 || s.substr(0, 6) != "APX1TV") return false;
    // 找 name 与 port 两个 token
    size_t i = 6;
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t')) ++i;
    size_t name0 = i;
    while (i < s.size() && s[i] != ' ' && s[i] != '\t') ++i;
    size_t name1 = i;
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t')) ++i;
    size_t port0 = i;
    while (i < s.size() && s[i] != ' ' && s[i] != '\t') ++i;
    size_t port1 = i;
    if (name1 == name0 || port1 == port0) return false;
    std::string portStr(s.substr(port0, port1 - port0));
    try {
        port = static_cast<uint16_t>(std::stoi(portStr));
    } catch (...) {
        return false;
    }
    (void)name0;
    return port != 0;
}

}  // namespace

const char* linkPhaseName(LinkPhase p) noexcept {
    switch (p) {
        case LinkPhase::Idle:        return "未启用";
        case LinkPhase::Discovering: return "正在发现设备";
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
    beaconRun_.store(false);
    client_.disconnect();
    if (beaconThread_.joinable()) beaconThread_.join();
    if (worker_.joinable()) worker_.join();
}

void WirelessSession::startAuto() {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Auto, {}, 0, true};
    snap_.error.clear();
    manualFailed_ = false;
    haveBeacon_ = false;
    APX_LOGI("9511 会话：切到自动发现模式");
}

void WirelessSession::connectManual(const std::string& host, uint16_t port) {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Manual, host, port, true};
    snap_.error.clear();
    manualFailed_ = false;
    APX_LOGI("9511 会话：手动连接 {}:{}", host.c_str(), static_cast<unsigned>(port));
}

void WirelessSession::disconnect() {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Idle, {}, 0, true};
    snap_.error.clear();
    manualFailed_ = false;
}

void WirelessSession::onBeacon(const std::string& host, uint16_t port) {
    std::lock_guard<std::mutex> lk(mu_);
    if (haveBeacon_) return;
    beaconHost_ = host;
    beaconPort_ = port;
    haveBeacon_ = true;
}

void WirelessSession::requestOpenScreen() {
    // 9511 受控端忽略 0x05；保留接口以兼容面板。
    client_.sendControl({0x05});
}

void WirelessSession::requestModule(int /*idx*/, bool /*on*/) {
    // 9511 控制面无模块开关子命令；保留接口以兼容面板。
}

bool WirelessSession::takeKeyFrameRequest() {
    // 9511 副屏走媒体通道，无需控制面关键帧请求。
    return false;
}

void WirelessSession::setTouchRect(int /*x*/, int /*y*/, int /*w*/, int /*h*/) {
    // 9511 控制面无触摸矩形概念；保留接口以兼容面板。
}

void WirelessSession::worker() {
    Mode mode = Mode::Idle;
    std::string host;
    uint16_t port = 0;
    bool attempt = false;

    while (running_.load()) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (desire_.fresh) {
                desire_.fresh = false;
                mode = desire_.mode;
                host = desire_.host;
                port = desire_.port;
                attempt = (mode != Mode::Idle);
                haveBeacon_ = false;
                manualFailed_ = false;
                connectStartMs_ = 0;
            }
        }

        if (mode == Mode::Idle) {
            if (client_.ready()) client_.disconnect();
            if (beaconRun_.load()) {
                beaconRun_.store(false);
                if (beaconThread_.joinable()) beaconThread_.join();
            }
            publish(LinkPhase::Idle, mode);
        } else if (mode == Mode::Auto) {
            if (client_.ready()) {
                publish(LinkPhase::Connected, mode);
            } else {
                if (!beaconRun_.load()) {
                    if (beaconThread_.joinable()) beaconThread_.join();
                    beaconRun_.store(true);
                    beaconThread_ = std::thread(&WirelessSession::beaconLoop, this);
                }
                std::string bhost;
                uint16_t bport = 0;
                {
                    std::lock_guard<std::mutex> lk(mu_);
                    if (haveBeacon_) { bhost = beaconHost_; bport = beaconPort_; haveBeacon_ = false; }
                }
                if (!bhost.empty()) {
                    publish(LinkPhase::Connecting, mode);
                    if (client_.connect(bhost, bport, "")) {
                        connectedPeer_ = bhost + ":" + std::to_string(bport);
                        connectStartMs_ = nowMs();
                        publish(LinkPhase::Connected, mode);
                    } else {
                        publish(LinkPhase::Discovering, mode);
                    }
                } else {
                    publish(LinkPhase::Discovering, mode);
                }
            }
        } else {  // Manual
            if (beaconRun_.load()) {
                beaconRun_.store(false);
                if (beaconThread_.joinable()) beaconThread_.join();
            }
            if (client_.ready()) {
                publish(LinkPhase::Connected, mode);
            } else if (attempt) {
                attempt = false;
                publish(LinkPhase::Connecting, mode);
                if (client_.connect(host, port, "")) {
                    connectedPeer_ = host + ":" + std::to_string(port);
                    connectStartMs_ = nowMs();
                    publish(LinkPhase::Connected, mode);
                } else {
                    manualFailed_ = true;
                    publish(LinkPhase::Failed, mode);
                }
            } else {
                publish(manualFailed_ ? LinkPhase::Failed : LinkPhase::Idle, mode);
            }
        }

        std::this_thread::sleep_for(kTick);
    }
}

void WirelessSession::publish(LinkPhase ph, Mode m) {
    const auto st = client_.status();
    std::lock_guard<std::mutex> lk(mu_);
    const bool changed = snap_.phase != ph;
    snap_.phase = ph;
    snap_.autoMode = (m == Mode::Auto);
    snap_.peer = st.connected ? connectedPeer_ : (m == Mode::Manual ? desire_.host : "");
    snap_.rttMs = st.rttMs;
    snap_.upMs = (connectStartMs_ > 0 && st.connected) ? (nowMs() - connectStartMs_) : 0;
    if (ph == LinkPhase::Failed) snap_.error = "连不上 " + desire_.host;
    else if (ph == LinkPhase::Connected) snap_.error.clear();
    snap_.counters.mouse = st.mouse;
    snap_.counters.touch = st.touch;
    snap_.counters.keyboard = st.keyboard;
    snap_.counters.consumer = st.consumer;
    snap_.counters.dropped = st.dropped;
    if (changed) APX_LOGI("9511 会话状态：{}", linkPhaseName(ph));
}

SessionSnapshot WirelessSession::snapshot() const {
    std::lock_guard<std::mutex> lk(mu_);
    return snap_;
}

void WirelessSession::beaconLoop() {
#if defined(_WIN32)
    Sock b = socket(AF_INET, SOCK_DGRAM, 0);
    if (b == INVALID_SOCKET) return;
    BOOL br = TRUE;
    setsockopt(b, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&br), sizeof(br));
#else
    int b = socket(AF_INET, SOCK_DGRAM, 0);
    if (b < 0) return;
    int br = 1;
    setsockopt(b, SOL_SOCKET, SO_BROADCAST, &br, sizeof(br));
    int reuse = 1;
    setsockopt(b, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
#endif
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(kBeaconPort);
    if (bind(b, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
#if defined(_WIN32)
        closesocket(b);
#else
        close(b);
#endif
        return;
    }
    char buf[1024];
    while (beaconRun_.load()) {
        sockaddr_in from{};
#if defined(_WIN32)
        int fromLen = sizeof(from);
#else
        socklen_t fromLen = sizeof(from);
#endif
        const int n = recvfrom(b, buf, sizeof(buf) - 1, 0,
                                reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n <= 0) { std::this_thread::sleep_for(std::chrono::milliseconds(50)); continue; }
        buf[n] = '\0';
        std::string hostIp;
#if defined(_WIN32)
        char ipStr[INET_ADDRSTRLEN];
        if (inet_ntop(AF_INET, &from.sin_addr, ipStr, sizeof(ipStr)))
            hostIp = ipStr;
#else
        char ipStr[INET_ADDRSTRLEN];
        if (inet_ntop(AF_INET, &from.sin_addr, ipStr, sizeof(ipStr)))
            hostIp = ipStr;
#endif
        uint16_t bport = 0;
        std::string dummy;
        if (!hostIp.empty() && parseBeacon(buf, static_cast<size_t>(n), dummy, bport)) {
            onBeacon(hostIp, bport);
        }
    }
#if defined(_WIN32)
    closesocket(b);
#else
    close(b);
#endif
}

}  // namespace apxpc::wireless
