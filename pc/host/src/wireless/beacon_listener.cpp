#include "apxpc/wireless/beacon_listener.hpp"

#include "apxpc/log.hpp"

#include <cstdlib>
#include <cstring>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#endif

namespace apxpc::wireless {
namespace {

/// 进程级 WSAStartup（与 wireless_link.cpp 各自持有一份静态，重复调用无害）
void ensureWsa() {
#if defined(_WIN32)
    static bool once = [] {
        WSADATA d{};
        WSAStartup(MAKEWORD(2, 2), &d);
        return true;
    }();
    (void)once;
#endif
}

#if defined(_WIN32)
/// 解析 `APX1PHONE <name> <port> <token>`；name 由手机侧保证不含空格
bool parseBeacon(const std::string& msg, const sockaddr_in& from, PhoneBeacon& out) {
    constexpr const char* kPrefix = "APX1PHONE ";
    const size_t prefixLen = std::strlen(kPrefix);
    if (msg.rfind(kPrefix, 0) != 0) return false;

    const std::string rest = msg.substr(prefixLen);
    const size_t i1 = rest.find(' ');
    if (i1 == std::string::npos) return false;
    const size_t i2 = rest.find(' ', i1 + 1);
    if (i2 == std::string::npos) return false;

    const std::string name = rest.substr(0, i1);
    const std::string portStr = rest.substr(i1 + 1, i2 - i1 - 1);
    const std::string token = rest.substr(i2 + 1);   // 允许为空（v1 不做令牌鉴权）

    const long port = std::strtol(portStr.c_str(), nullptr, 10);
    if (port <= 0 || port > 65535) return false;

    out.name = name.empty() ? "Android" : name;
    out.port = static_cast<uint16_t>(port);
    out.token = token;

    // 源地址比载荷自述可信：载荷里的 ip 可能是手机自认为的地址，源地址才是真的
    char ip[INET_ADDRSTRLEN] = {0};
    if (::inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip)) == nullptr) return false;
    out.host = ip;
    return true;
}
#endif

}  // namespace

bool BeaconListener::start(Callback onFound, uint16_t port) {
    if (running_.exchange(true)) return true;
    cb_ = std::move(onFound);

#if !defined(_WIN32)
    APX_LOGW("信标监听目前仅实现 Windows 宿主");
    running_.store(false);
    return false;
#else
    ensureWsa();
    const SOCKET s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == INVALID_SOCKET) {
        APX_LOGE("信标监听：socket 创建失败");
        running_.store(false);
        return false;
    }

    BOOL yes = TRUE;
    ::setsockopt(s, SOL_SOCKET, SO_REUSEADDR,
                 reinterpret_cast<const char*>(&yes), sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    if (::bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SOCKET_ERROR) {
        APX_LOGE("信标监听：绑定 UDP {} 失败（端口被占？）", port);
        ::closesocket(s);
        running_.store(false);
        return false;
    }

    // 500ms 读超时：既能及时响应 stop()，也不忙轮询
    DWORD tmo = 500;
    ::setsockopt(s, SOL_SOCKET, SO_RCVTIMEO,
                 reinterpret_cast<const char*>(&tmo), sizeof(tmo));

    sock_ = static_cast<std::uintptr_t>(s);
    thread_ = std::thread(&BeaconListener::loop, this);
    APX_LOGI("正在监听手机信标：UDP 0.0.0.0:{}", port);
    return true;
#endif
}

void BeaconListener::stop() {
    if (!running_.exchange(false)) return;
#if defined(_WIN32)
    const std::uintptr_t s = sock_;
    sock_ = static_cast<std::uintptr_t>(~0ull);
    if (s != static_cast<std::uintptr_t>(~0ull)) {
        ::closesocket(static_cast<SOCKET>(s));   // 令阻塞中的 recvfrom 立即返回
    }
#endif
    if (thread_.joinable()) thread_.join();
}

void BeaconListener::loop() {
#if defined(_WIN32)
    const SOCKET s = static_cast<SOCKET>(sock_);
    while (running_.load()) {
        char buf[512];
        sockaddr_in from{};
        int fromLen = static_cast<int>(sizeof(from));
        const int n = ::recvfrom(s, buf, static_cast<int>(sizeof(buf) - 1), 0,
                                 reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n <= 0) {
            if (!running_.load()) break;
            continue;   // 超时/干扰包：继续等
        }
        buf[n] = '\0';
        PhoneBeacon pb;
        if (!parseBeacon(std::string(buf, static_cast<size_t>(n)), from, pb)) continue;
        APX_LOGI("发现手机信标：{} @ {}:{}", pb.name, pb.host, pb.port);
        if (cb_) cb_(pb);
    }
#endif
}

}  // namespace apxpc::wireless
