// ============================================================================
// 统一控制面 9511 —— 受控服务端 + 控制客户端 + 发现信标（跨平台）
//
// 帧/握手/子命令与 android/tv/net/TcpControlServer.kt、TvControllerClient.kt 逐字节一致。
// 仅网络与帧解析是平台无关部分；系统注入见 input_injector_*，剪贴板监听见同文件。
// ============================================================================
#include "apxpc/wireless/ctrl9511.hpp"

#include <apx/frame.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <ws2tcpip.h>
#include <iphlpapi.h>
using Sock = SOCKET;
const Sock kBadSock = INVALID_SOCKET;
static void closeSock(Sock s) { if (s != INVALID_SOCKET) closesocket(s); }
static void ensureWsa() {
    static bool once = [] { WSADATA d{}; WSAStartup(MAKEWORD(2, 2), &d); return true; }();
    (void)once;
}
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netdb.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <unistd.h>
using Sock = int;
const Sock kBadSock = -1;
static void closeSock(Sock s) { if (s >= 0) close(s); }
static void ensureWsa() {
    // 非 Windows：忽略 SIGPIPE，避免对端断开时 send 直接杀进程
    static bool once = [] { signal(SIGPIPE, SIG_IGN); return true; }();
    (void)once;
}
#endif

namespace apxpc::wireless {
namespace {

using Clock = std::chrono::steady_clock;
int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               Clock::now().time_since_epoch())
        .count();
}

// 组一条控制帧：完整 body = [cmd] + body。CRC 覆盖 [cmd]+body。
bool buildCtrlFrame(uint8_t* buf, size_t cap, size_t& outLen,
                    uint8_t cmd, const uint8_t* body, size_t bodyLen, uint32_t seq) {
    apx::ApxFrameHeader h{};
    std::memcpy(h.magic, "APX1", 4);
    h.streamId = apx::kStreamControl;
    h.flags = 0;
    h.headerExtWords = 0;
    h.payloadLen = static_cast<uint32_t>(1 + bodyLen + apx::kFrameCrcSize);
    h.seq = seq;
    if (cap < apx::kFrameHeaderSize + h.payloadLen) return false;
    if (!apx::writeHeader(buf, cap, h)) return false;
    uint8_t* p = buf + apx::kFrameHeaderSize;
    p[0] = cmd;
    if (bodyLen) std::memcpy(p + 1, body, bodyLen);
    apx::putU32(p + 1 + bodyLen, apx::crc32(p, 1 + bodyLen));
    outLen = apx::kFrameHeaderSize + h.payloadLen;
    return true;
}

bool sendAll(Sock s, const uint8_t* p, size_t n) {
    size_t off = 0;
    while (off < n) {
#if defined(_WIN32)
        const int w = ::send(s, reinterpret_cast<const char*>(p + off),
                             static_cast<int>(n - off), 0);
#elif defined(__linux__)
        const ssize_t w = ::send(s, p + off, n - off, MSG_NOSIGNAL);
#else
        const ssize_t w = ::send(s, p + off, n - off, 0);
#endif
        if (w <= 0) return false;
        off += static_cast<size_t>(w);
    }
    return true;
}

// 读满 n 字节（用于握手令牌）。返回 false 表示对端关闭/出错。
bool readFull(Sock s, uint8_t* dst, size_t n) {
    size_t got = 0;
    while (got < n) {
#if defined(_WIN32)
        const int r = ::recv(s, reinterpret_cast<char*>(dst + got),
                             static_cast<int>(n - got), 0);
#else
        const ssize_t r = ::recv(s, dst + got, n - got, 0);
#endif
        if (r <= 0) return false;
        got += static_cast<size_t>(r);
    }
    return true;
}

uint32_t readU32Le(const uint8_t* b) {
    return static_cast<uint32_t>(b[0]) | (static_cast<uint32_t>(b[1]) << 8) |
           (static_cast<uint32_t>(b[2]) << 16) | (static_cast<uint32_t>(b[3]) << 24);
}

}  // namespace

// ============================================================================
// 受控服务端
// ============================================================================
struct Ctrl9511Server::Impl {
    uint16_t port = 0;
    std::string token;
    std::string name;
    std::atomic<bool> running{false};

    Sock listenFd = kBadSock;
    Sock clientFd = kBadSock;
    std::mutex clientMu;

    std::unique_ptr<InputInjector> injector;
    std::unique_ptr<ClipboardWatcher> clip;

    std::thread acceptThread;
    std::thread readerThread;
    std::thread writerThread;
    std::thread beaconThread;

    std::mutex writeMu;
    std::condition_variable writeCv;
    std::deque<std::vector<uint8_t>> outQueue;
    std::atomic<uint32_t> seq_{1};

    // 注入状态（接收线程写，无需加锁）
    uint8_t mouseButtons_ = 0;
    uint16_t consumerBm_ = 0;
    uint8_t kbMod_ = 0;
    uint8_t kbKeys_[6] = {0, 0, 0, 0, 0, 0};

    Ctrl9511ServerStatus st{};
    // 计数用独立原子（Ctrl9511ServerStatus 成员是普通类型，便于按值返回）
    std::atomic<uint64_t> aMouse_{0}, aTouch_{0}, aKeyboard_{0}, aConsumer_{0};
    std::atomic<uint64_t> aClipboard_{0}, aReverse_{0}, aDropped_{0};

    void enqueue(const uint8_t* frame, size_t len) {
        {
            std::lock_guard<std::mutex> lk(writeMu);
            outQueue.emplace_back(frame, frame + len);
        }
        writeCv.notify_one();
    }

    void onClipboardChanged(const std::string& text) {
        if (text.empty()) return;
        const auto bytes = std::vector<uint8_t>(text.begin(), text.end());
        if (bytes.size() > 0xFFFF) return;
        uint8_t body[2 + 0xFFFF];
        body[0] = static_cast<uint8_t>(bytes.size() & 0xFF);
        body[1] = static_cast<uint8_t>((bytes.size() >> 8) & 0xFF);
        std::memcpy(body + 2, bytes.data(), bytes.size());
        uint8_t buf[apx::kFrameHeaderSize + 3 + 0xFFFF];
        size_t len = 0;
        if (buildCtrlFrame(buf, sizeof(buf), len, 0x21, body, bytes.size() + 2, seq_.fetch_add(1))) {
            enqueue(buf, len);
            aReverse_.fetch_add(1);
        }
    }

    void acceptLoop() {
        while (running.load()) {
            Sock c = ::accept(listenFd, nullptr, nullptr);
            if (c == kBadSock) {
                if (!running.load()) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }
            {
                std::lock_guard<std::mutex> lk(clientMu);
                if (clientFd != kBadSock) {
                    // 已有连接：**让后来者接管**，不要拒绝。旧客户端可能已是僵尸（对端被强杀 /
                    // 网段切换时收不到 FIN，收流线程一直阻塞在 recv，clientFd 被永久占住）；
                    // 此时若拒绝新连接，手机端就是「TCP 连上、却一行都进不来」。
                    // 注意：**不要在这里关旧 socket** —— 旧收流线程还阻塞在 recv 上，让它自己
                    // 醒来后关（它有 500ms 超时，看到 clientFd 已换就 break 并关闭）。在这里关
                    // 会关同一个句柄两次；而新 accept 拿到的 socket 很可能复用同一个数值，
                    // 于是旧线程的 closeSock 会把**刚接管的新连接**一起关掉。
                    st.connected = false;
                    st.peer.clear();
                }
                clientFd = c;
            }
            handshake(c);
        }
    }

    void handshake(Sock s) {
        // ★★ 「第二次连接必崩」的真因在这里：readerThread / writerThread 是**成员**。
        //   上一条连接的线程退出后没人 join，对象仍是 joinable；此时再赋值一个新的
        //   std::thread 会调用 **std::terminate → __fastfail(0xC0000409)**。
        //   而 fail-fast 绕过 SEH/VEH、不生成 crash.dmp/crash.txt、WER 也无记录，
        //   只留一个 -1073740791 的退出码 —— 现场干干净净，极难定位。
        //   （原先第二个连接被 acceptLoop 直接拒绝，所以这坑一直没被踩到。）
        //   此刻 clientFd 已指向新连接：旧收流线程看到就 break、旧发出线程最迟 200ms
        //   醒来 break，因此 join 不会久等。用 join 而非 detach，保证 stop() 时
        //   Impl 不会被仍在运行的线程悬空访问。
        if (readerThread.joinable()) readerThread.join();
        if (writerThread.joinable()) writerThread.join();

        // 握手超时：避免半开连接卡死接收线程
#if defined(_WIN32)
        DWORD t = 5000;
#else
        struct timeval t { 5, 0 };
#endif
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&t), sizeof(t));
        uint8_t hdr[4];
        if (!readFull(s, hdr, 4)) { closeSock(s); resetClientFor(s); return; }
        const uint32_t tokLen = readU32Le(hdr);
        if (tokLen > 256) { closeSock(s); resetClientFor(s); return; }
        bool ok = true;
        if (tokLen > 0) {
            std::vector<uint8_t> buf(tokLen);
            if (!readFull(s, buf.data(), tokLen)) { ok = false; }
            else if (!token.empty() && std::string(buf.begin(), buf.end()) != token) ok = false;
        }
        if (!ok) { closeSock(s); resetClientFor(s); return; }
        // 关掉读超时（后续一直接收控制帧），启动收发线程
#if defined(_WIN32)
        DWORD z = 0;
#else
        struct timeval z { 0, 0 };
#endif
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&z), sizeof(z));

        {
            std::lock_guard<std::mutex> lk(clientMu);
            st.connected = true;
            st.peer = "(peer)";  // 可扩展为取对端 IP
        }
        readerThread = std::thread([this, s] { readerLoop(s); });
        writerThread = std::thread([this, s] { writerLoop(s); });
    }

    /// 清掉「自己这条」连接的状态。**必须带上 socket 校验**：新连接接管后，旧连接的
    /// 收流/发出线程退出时也会走到这里；若无条件清，会把刚接管进来的新客户端一起清掉
    /// （新连接随即被判为「已不是当前 client」而断开）—— 等价于永远用不了新连接。
    void resetClientFor(Sock s) {
        std::lock_guard<std::mutex> lk(clientMu);
        if (clientFd != s) return;
        clientFd = kBadSock;
        st.connected = false;
        st.peer.clear();
    }

    void readerLoop(Sock s) {
        std::vector<uint8_t> acc;
        acc.reserve(8192);
        uint8_t rx[2048];
#if defined(_WIN32)
        DWORD rto = 500;
#else
        struct timeval rto { 0, 500'000 };
#endif
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rto), sizeof(rto));

        while (running.load()) {
            Sock cur;
            { std::lock_guard<std::mutex> lk(clientMu); cur = clientFd; }
            if (cur != s) break;
#if defined(_WIN32)
            const int n = ::recv(s, reinterpret_cast<char*>(rx), sizeof(rx), 0);
#else
            const ssize_t n = ::recv(s, rx, sizeof(rx), 0);
#endif
            if (n == 0) break;
            if (n < 0) { std::this_thread::sleep_for(std::chrono::milliseconds(50)); continue; }
            if (acc.size() > apx::kMaxFramePayload + apx::kFrameHeaderSize + (1u << 16)) {
                acc.clear(); aDropped_.fetch_add(1);
            } else {
                acc.insert(acc.end(), rx, rx + n);
            }

            size_t off = 0;
            while (acc.size() - off >= apx::kFrameHeaderSize) {
                apx::ApxFrameHeader h{};
                if (!apx::readHeader(acc.data() + off, acc.size() - off, h)) {
                    acc.clear(); aDropped_.fetch_add(1); break;
                }
                if (!apx::isValidPayloadLen(h.payloadLen)) { acc.clear(); aDropped_.fetch_add(1); break; }
                const size_t total = apx::frameTotalSize(h);
                if (acc.size() - off < total) break;
                const uint8_t* payload = acc.data() + off + apx::kFrameHeaderSize;
                if (h.streamId == apx::kStreamControl && !apx::verifyPayload(payload, h.payloadLen)) {
                    aDropped_.fetch_add(1); off += total; continue;
                }
                if (h.streamId == apx::kStreamControl) {
                    const uint32_t bodyLen = h.payloadLen >= apx::kFrameCrcSize
                                                  ? h.payloadLen - apx::kFrameCrcSize : 0;
                    dispatch(payload, bodyLen);
                } else {
                    aDropped_.fetch_add(1);
                }
                off += total;
            }
            if (off > 0) acc.erase(acc.begin(), acc.begin() + static_cast<long>(off));
        }
        closeSock(s);
        resetClientFor(s);
    }

    void dispatch(const uint8_t* p, uint32_t bodyLen) {
        if (bodyLen < 1) return;
        const uint8_t cmd = p[0];
        if (cmd == 'p') {  // ping → pong
            uint8_t buf[64]; size_t len = 0;
            if (buildCtrlFrame(buf, sizeof(buf), len, 'p',
                               reinterpret_cast<const uint8_t*>("ong"), 3, seq_.fetch_add(1)))
                enqueue(buf, len);
            return;
        }
        if (cmd == 0x01 && bodyLen >= 5) {
            const uint8_t buttons = p[1];
            const int8_t dx = static_cast<int8_t>(p[2]);
            const int8_t dy = static_cast<int8_t>(p[3]);
            // 手机端 body 是 5 字节 [0x01, buttons, dx, dy, wheel]：滚轮在第 5 字节。
            // 原来写成 bodyLen >= 6，5 字节时恒取 0 —— 滚轮一直失效。
            const int8_t wheel = (bodyLen >= 5) ? static_cast<int8_t>(p[4]) : 0;
            if (injector) injector->injectMouse(buttons, dx, dy, wheel);
            aMouse_.fetch_add(1);
        } else if (cmd == 0x02 && bodyLen >= 3) {
            const uint16_t bm = static_cast<uint16_t>(p[1]) |
                                (static_cast<uint16_t>(p[2]) << 8);
            if (injector) injector->injectConsumer(bm);
            aConsumer_.fetch_add(1);
        } else if (cmd == 0x03 && bodyLen >= 3) {
            const uint8_t mod = p[1];
            const size_t cnt = bodyLen - 3;
            const uint8_t* keys = (cnt > 0) ? (p + 3) : nullptr;
            if (injector) injector->injectKeyboard(mod, keys, cnt > 6 ? 6 : cnt);
            aKeyboard_.fetch_add(1);
        } else if (cmd == 0x04 && bodyLen >= 9) {
            const uint8_t action = p[1];
            const uint8_t buttons = p[2];
            const uint16_t x = apx::getU16(p + 3);
            const uint16_t y = apx::getU16(p + 5);
            if (injector) injector->injectTouch(action, buttons, x, y);
            aTouch_.fetch_add(1);
        } else if (cmd == 0x20 && bodyLen >= 3) {
            const uint32_t len = static_cast<uint32_t>(p[1]) |
                                 (static_cast<uint32_t>(p[2]) << 8);
            if (bodyLen - 2 >= len && len > 0) {
                std::string text(reinterpret_cast<const char*>(p + 3),
                                 static_cast<size_t>(len));
                if (injector) injector->setClipboard(text);
                aClipboard_.fetch_add(1);
            }
        } else if (cmd == 0x07 && bodyLen >= 7) {
            const uint16_t buttons = static_cast<uint16_t>(p[1]) |
                                      (static_cast<uint16_t>(p[2]) << 8);
            const int8_t x = static_cast<int8_t>(p[3]);
            const int8_t y = static_cast<int8_t>(p[4]);
            const int8_t rx = static_cast<int8_t>(p[5]);
            const int8_t ry = static_cast<int8_t>(p[6]);
            if (injector) injector->injectGamepad(buttons, x, y, rx, ry);
        }
        // 0x21 / 0x05 / 0x10 等：受控端忽略
    }

    void writerLoop(Sock s) {
        std::unique_lock<std::mutex> lk(writeMu);
        while (running.load()) {
            writeCv.wait_for(lk, std::chrono::milliseconds(200),
                             [this] { return !outQueue.empty() || !running.load(); });
            if (!running.load()) break;
            // 已被新连接接管：这条 writer 退休，别再往旧 socket 写（否则会误清接管者的状态）
            {
                std::lock_guard<std::mutex> lkC(clientMu);
                if (clientFd != s) break;
            }
            if (outQueue.empty()) continue;
            auto frame = std::move(outQueue.front());
            outQueue.pop_front();
            lk.unlock();
            if (!sendAll(s, frame.data(), frame.size())) { closeSock(s); resetClientFor(s); break; }
            lk.lock();
        }
    }

    void beaconLoop() {
        // 受控端广播 "APX1PC <name> <port> <token>" 到 9501，
        // 手机端 TvDiscovery 据此把本机列为「可控制的 PC」（与 TV 的 APX1TV 区分类型，
        // 避免手机把 PC 误判成 TV 而切到 TV 专属快捷键布局）。
        // 重要：Xiaomi MIUI / Android 15 会丢弃 255.255.255.255 受限广播，
        // 故除受限广播外，还要向各 IPv4 网卡的「子网定向广播」发送，手机才能收到。
        std::string safe = name;
        for (auto& c : safe) if (c == ' ') c = '_';
        const std::string payload = "APX1PC " + safe + " " + std::to_string(port) + " " + token;
        auto sendTo = [](Sock b, const std::string& pl, uint32_t netAddr) {
            sockaddr_in dst{};
            dst.sin_family = AF_INET;
            dst.sin_port = htons(9501);
            dst.sin_addr.s_addr = netAddr;
            ::sendto(b, pl.data(), static_cast<int>(pl.size()), 0,
                     reinterpret_cast<sockaddr*>(&dst), sizeof(dst));
        };
#if defined(_WIN32)
        Sock b = socket(AF_INET, SOCK_DGRAM, 0);
        if (b == INVALID_SOCKET) return;
        BOOL br = TRUE; setsockopt(b, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&br), sizeof(br));
        std::vector<uint32_t> bcasts;
        bcasts.push_back(INADDR_BROADCAST);  // 受限广播兜底
        {
            ULONG size = 0;
            GetAdaptersAddresses(AF_INET, 0, nullptr, nullptr, &size);
            if (size == 0) size = 16384;
            std::vector<uint8_t> buf(size);
            PIP_ADAPTER_ADDRESSES addrs = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data());
            if (GetAdaptersAddresses(AF_INET, 0, nullptr, addrs, &size) == NO_ERROR) {
                for (auto* a = addrs; a; a = a->Next) {
                    if (a->OperStatus != IfOperStatusUp) continue;
                    for (auto* ua = a->FirstUnicastAddress; ua; ua = ua->Next) {
                        if (ua->Address.lpSockaddr->sa_family != AF_INET) continue;
                        auto* sin = reinterpret_cast<sockaddr_in*>(ua->Address.lpSockaddr);
                        uint32_t ip = ntohl(sin->sin_addr.s_addr);
                        ULONG prefix = ua->OnLinkPrefixLength;
                        if (prefix == 0 || prefix > 32) continue;
                        uint32_t mask = (prefix == 32) ? 0xFFFFFFFFu
                                                       : ~( (1u << (32u - prefix)) - 1u );
                        bcasts.push_back(htonl(ip | (~mask)));
                    }
                }
            }
        }
        while (running.load()) {
            for (uint32_t bc : bcasts) sendTo(b, payload, bc);
            std::this_thread::sleep_for(std::chrono::milliseconds(1500));
        }
        closeSock(b);
#else
        Sock b = socket(AF_INET, SOCK_DGRAM, 0);
        if (b < 0) return;
        int br = 1; setsockopt(b, SOL_SOCKET, SO_BROADCAST, &br, sizeof(br));
        sockaddr_in dst{}; dst.sin_family = AF_INET; dst.sin_port = htons(9501);
        dst.sin_addr.s_addr = INADDR_BROADCAST;
        while (running.load()) {
            sendTo(b, payload, dst.sin_addr.s_addr);
            std::this_thread::sleep_for(std::chrono::milliseconds(1500));
        }
        closeSock(b);
#endif
    }
};

Ctrl9511Server::Ctrl9511Server() = default;
Ctrl9511Server::~Ctrl9511Server() { stop(); }

bool Ctrl9511Server::start(uint16_t port, const std::string& token, const std::string& name) {
    if (impl_) return false;
    ensureWsa();
    auto* im = new Impl();
    im->port = port; im->token = token; im->name = name;
    im->injector = createPlatformInjector();
    if (!im->injector) {
        im->st.error = "平台输入注入器创建失败";
        delete im; return false;
    }
    if (reverseClipboard_) {
        im->clip = createPlatformClipboardWatcher(
            [im](const std::string& t) { im->onClipboardChanged(t); });
        if (im->clip) im->clip->start();
    }

    im->listenFd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (im->listenFd == kBadSock) { delete im; return false; }
#if defined(_WIN32)
    int yes = 1;
#else
    int yes = 1;
#endif
    setsockopt(im->listenFd, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof(yes));
    sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_addr.s_addr = INADDR_ANY; addr.sin_port = htons(port);
    if (::bind(im->listenFd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        im->st.error = "bind 失败（端口被占？）"; closeSock(im->listenFd); delete im; return false;
    }
    if (::listen(im->listenFd, 1) != 0) { closeSock(im->listenFd); delete im; return false; }

    im->running.store(true);
    impl_.reset(im);
    im->acceptThread = std::thread([im] { im->acceptLoop(); });
    im->beaconThread = std::thread([im] { im->beaconLoop(); });
    im->st.listening = true;
    return true;
}

void Ctrl9511Server::stop() {
    if (!impl_) return;
    impl_->running.store(false);
    closeSock(impl_->listenFd);
    { std::lock_guard<std::mutex> lk(impl_->clientMu);
      if (impl_->clientFd != kBadSock) { shutdown(impl_->clientFd, 0); closeSock(impl_->clientFd); } }
    if (impl_->clip) impl_->clip->stop();
    if (impl_->acceptThread.joinable()) impl_->acceptThread.join();
    if (impl_->readerThread.joinable()) impl_->readerThread.join();
    if (impl_->writerThread.joinable()) impl_->writerThread.join();
    if (impl_->beaconThread.joinable()) impl_->beaconThread.join();
    impl_.reset();
}

Ctrl9511ServerStatus Ctrl9511Server::status() const {
    if (!impl_) return Ctrl9511ServerStatus{};
    Ctrl9511ServerStatus s;
    {
        // st.peer 是 std::string，握手线程与「新连接接管」都会写它；这里必须持同一把锁再拷。
        // 否则就是跨线程读写同一个 std::string 的数据竞争（可能踩坏堆 → fail-fast 崩溃，
        // 且崩溃点与触发点相隔很远，极难定位）。
        std::lock_guard<std::mutex> lk(impl_->clientMu);
        s = impl_->st;
    }
    s.mouse = impl_->aMouse_.load();
    s.touch = impl_->aTouch_.load();
    s.keyboard = impl_->aKeyboard_.load();
    s.consumer = impl_->aConsumer_.load();
    s.clipboard = impl_->aClipboard_.load();
    s.reverseClipboard = impl_->aReverse_.load();
    s.dropped = impl_->aDropped_.load();
    return s;
}

// ============================================================================
// 控制客户端
// ============================================================================
struct Ctrl9511Client::Impl {
    std::atomic<bool> running{false};
    Sock sock = kBadSock;
    std::mutex mu;
    std::atomic<uint32_t> seq_{1};
    std::thread readerThread;
    std::thread pingThread;
    uint64_t dropped = 0;

    // 注入计数（发送线程累加，status() 按值返回）
    std::atomic<uint64_t> aMouse_{0}, aTouch_{0}, aKeyboard_{0}, aConsumer_{0};
    // 心跳 RTT：-1 表示未测得；ping 发出时刻由 ping 线程记录，pong 到达时算差
    std::atomic<int64_t> rttMs_{-1};
    std::atomic<int64_t> pingSentMs_{0};

    int64_t nowMs() const {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                   Clock::now().time_since_epoch())
            .count();
    }

    void sendCmd(uint8_t cmd, const uint8_t* body, size_t bodyLen) {
        // 计数（用于面板展示注入量）
        switch (cmd) {
            case 0x01: aMouse_.fetch_add(1); break;
            case 0x02: aConsumer_.fetch_add(1); break;
            case 0x03: aKeyboard_.fetch_add(1); break;
            case 0x04: aTouch_.fetch_add(1); break;
            default: break;
        }
        // 剪贴板帧可达 64KB，必须用堆缓冲（不能用栈定长小缓冲）
        std::vector<uint8_t> buf(apx::kFrameHeaderSize + 1 + bodyLen + apx::kFrameCrcSize);
        size_t len = 0;
        if (!buildCtrlFrame(buf.data(), buf.size(), len, cmd, body, bodyLen, seq_.fetch_add(1))) return;
        std::lock_guard<std::mutex> lk(mu);
        if (sock == kBadSock) return;
        sendAll(sock, buf.data(), len);
    }
};

Ctrl9511Client::Ctrl9511Client() = default;
Ctrl9511Client::~Ctrl9511Client() { disconnect(); }

bool Ctrl9511Client::connect(const std::string& host, uint16_t port, const std::string& token) {
    // 旧连接若已死（收流线程只关了 socket，impl_ 仍留着），这里必须先收掉再连：
    // 否则 impl_ 会一直挡着，connect() **永远立即返回 false** —— 一次瞬断之后就再也连不上，
    // 表现为面板每 3s 重试却毫无动静、对端日志里连一个连接请求都看不到（真机踩过）。
    // 调用方只在 client_.ready() 为 false 时才进来，所以这里不会掐掉健康连接。
    if (impl_) disconnect();
    ensureWsa();
    auto* im = new Impl();
    addrinfo hints{}, *res = nullptr;
    hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM;
    char portStr[8]; std::snprintf(portStr, sizeof(portStr), "%u", static_cast<unsigned>(port));
    if (getaddrinfo(host.c_str(), portStr, &hints, &res) != 0 || !res) { delete im; return false; }
    Sock s = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (s == kBadSock) { freeaddrinfo(res); delete im; return false; }
#if defined(_WIN32)
    u_long nb = 1; ioctlsocket(s, FIONBIO, &nb);
#else
    fcntl(s, F_SETFL, fcntl(s, F_GETFL, 0) | O_NONBLOCK);
#endif
    const int rc = ::connect(s, res->ai_addr, static_cast<int>(res->ai_addrlen));
    freeaddrinfo(res);
    if (rc != 0) {
#if defined(_WIN32)
        if (WSAGetLastError() != WSAEWOULDBLOCK) { closeSock(s); delete im; return false; }
#else
        if (errno != EINPROGRESS) { closeSock(s); delete im; return false; }
#endif
    }
    fd_set w; FD_ZERO(&w); FD_SET(s, &w);
    timeval tv{3, 0};
    if (select(static_cast<int>(s) + 1, nullptr, &w, nullptr, &tv) <= 0) { closeSock(s); delete im; return false; }
#if defined(_WIN32)
    u_long z = 0; ioctlsocket(s, FIONBIO, &z);
#else
    fcntl(s, F_SETFL, fcntl(s, F_GETFL, 0) & ~O_NONBLOCK);
#endif

    // 握手：u32 LE 令牌长度 + 令牌
    const uint32_t tokLen = static_cast<uint32_t>(token.size());
    uint8_t hdr[4] = { static_cast<uint8_t>(tokLen & 0xFF), static_cast<uint8_t>((tokLen >> 8) & 0xFF),
                       static_cast<uint8_t>((tokLen >> 16) & 0xFF), static_cast<uint8_t>((tokLen >> 24) & 0xFF) };
    if (!sendAll(s, hdr, 4) || (tokLen > 0 && !sendAll(s, reinterpret_cast<const uint8_t*>(token.data()), tokLen))) {
        closeSock(s); delete im; return false;
    }
    im->sock = s;
    im->running.store(true);
    impl_.reset(im);

    // 收流线程：忽略 pong，处理 0x21 反向剪贴板
    const auto cb = onReverseClipboard;
    im->readerThread = std::thread([im, cb] {
        std::vector<uint8_t> acc; acc.reserve(8192); uint8_t rx[2048];
#if defined(_WIN32)
        DWORD rto = 500;
#else
        struct timeval rto { 0, 500'000 };
#endif
        setsockopt(im->sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rto), sizeof(rto));
        while (im->running.load()) {
#if defined(_WIN32)
            const int n = ::recv(im->sock, reinterpret_cast<char*>(rx), sizeof(rx), 0);
#else
            const ssize_t n = ::recv(im->sock, rx, sizeof(rx), 0);
#endif
            if (n == 0) break;
            if (n < 0) { std::this_thread::sleep_for(std::chrono::milliseconds(50)); continue; }
            acc.insert(acc.end(), rx, rx + n);
            size_t off = 0;
            while (acc.size() - off >= apx::kFrameHeaderSize) {
                apx::ApxFrameHeader h{};
                if (!apx::readHeader(acc.data() + off, acc.size() - off, h)) { acc.clear(); im->dropped++; break; }
                if (!apx::isValidPayloadLen(h.payloadLen)) { acc.clear(); im->dropped++; break; }
                const size_t total = apx::frameTotalSize(h);
                if (acc.size() - off < total) break;
                const uint8_t* payload = acc.data() + off + apx::kFrameHeaderSize;
                const uint32_t bodyLen = h.payloadLen >= apx::kFrameCrcSize ? h.payloadLen - apx::kFrameCrcSize : 0;
                if (h.streamId == apx::kStreamControl && bodyLen >= 1 && payload[0] == 0x21 && bodyLen >= 3) {
                    const uint32_t len = static_cast<uint32_t>(payload[1]) | (static_cast<uint32_t>(payload[2]) << 8);
                    if (bodyLen - 2 >= len && len > 0) {
                        std::string t(reinterpret_cast<const char*>(payload + 3), static_cast<size_t>(len));
                        if (cb) cb(t);
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 1 && payload[0] == 'p') {
                    // pong：估算 RTT（ping 发出到 pong 到达的单向往返）
                    const auto sent = im->pingSentMs_.load();
                    if (sent > 0) im->rttMs_.store(im->nowMs() - sent);
                }
                off += total;
            }
            if (off > 0) acc.erase(acc.begin(), acc.begin() + static_cast<long>(off));
        }
        closeSock(im->sock);
        im->running.store(false);
    });

    // 心跳线程：每 1s 发 ping（记录发出时刻用于 RTT 估算）
    im->pingThread = std::thread([im] {
        while (im->running.load()) {
            im->pingSentMs_.store(im->nowMs());
            im->sendCmd('p', reinterpret_cast<const uint8_t*>("ing"), 3);
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    });
    return true;
}

void Ctrl9511Client::disconnect() {
    std::unique_ptr<Impl> im(impl_.release());
    if (!im) return;
    im->running.store(false);
    { std::lock_guard<std::mutex> lk(im->mu);
      if (im->sock != kBadSock) { shutdown(im->sock, 0); closeSock(im->sock); im->sock = kBadSock; } }
    // **绝不在调用者线程里 join**：connect() 会先调本函数（清掉死掉的旧连接），而 connect()
    // 可能被 UI 线程与工作线程交叉调到；一旦 join 到自己所在线程就抛 std::system_error →
    // std::terminate → 0xC0000409(fail-fast)，而 fail-fast **绕过**崩溃过滤器，现场不留任何
    // 痕迹（真机症状：连第二次就连不上、面板直接消失、日志里只有一个 -1073740791）。
    // 改成交给独立线程 join + delete：两个收发线程都带 200~500ms 超时循环，最多一秒收工。
    std::thread([raw = im.release()] {
        if (raw->readerThread.joinable()) raw->readerThread.join();
        if (raw->pingThread.joinable()) raw->pingThread.join();
        delete raw;
    }).detach();
}

bool Ctrl9511Client::ready() const { return impl_ && impl_->running.load(); }

void Ctrl9511Client::sendMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel) {
    if (!impl_) return;
    uint8_t b[4] = { buttons, static_cast<uint8_t>(dx), static_cast<uint8_t>(dy), static_cast<uint8_t>(wheel) };
    impl_->sendCmd(0x01, b, 4);
}
void Ctrl9511Client::sendKeyboard(uint8_t mod, const uint8_t* keys, size_t count) {
    if (!impl_) return;
    uint8_t b[8] = { mod, 0, 0, 0, 0, 0, 0, 0 };
    for (size_t i = 0; i < count && i < 6; ++i) b[3 + i] = keys[i];
    impl_->sendCmd(0x03, b, 3 + (count > 6 ? 6 : count));
}
void Ctrl9511Client::sendConsumer(uint16_t bitmap) {
    if (!impl_) return;
    uint8_t b[2] = { static_cast<uint8_t>(bitmap & 0xFF), static_cast<uint8_t>((bitmap >> 8) & 0xFF) };
    impl_->sendCmd(0x02, b, 2);
}
void Ctrl9511Client::sendTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) {
    if (!impl_) return;
    uint8_t b[8] = { action, buttons, static_cast<uint8_t>(x & 0xFF), static_cast<uint8_t>(x >> 8),
                     static_cast<uint8_t>(y & 0xFF), static_cast<uint8_t>(y >> 8), 0, 0 };
    impl_->sendCmd(0x04, b, 8);
}
bool Ctrl9511Client::sendClipboard(const std::string& text) {
    if (!impl_ || text.empty()) return false;
    const auto bytes = std::vector<uint8_t>(text.begin(), text.end());
    if (bytes.size() > 0xFFFF) return false;
    uint8_t b[2 + 0xFFFF];
    b[0] = static_cast<uint8_t>(bytes.size() & 0xFF);
    b[1] = static_cast<uint8_t>((bytes.size() >> 8) & 0xFF);
    std::memcpy(b + 2, bytes.data(), bytes.size());
    impl_->sendCmd(0x20, b, bytes.size() + 2);
    return true;
}

bool Ctrl9511Client::sendControl(const std::vector<uint8_t>& body) {
    if (!impl_ || body.empty()) return false;
    impl_->sendCmd(body[0], body.data() + 1, body.size() - 1);
    return true;
}

Ctrl9511ClientStatus Ctrl9511Client::status() const {
    Ctrl9511ClientStatus s;
    if (impl_) {
        s.connected = impl_->running.load();
        s.mouse = impl_->aMouse_.load();
        s.touch = impl_->aTouch_.load();
        s.keyboard = impl_->aKeyboard_.load();
        s.consumer = impl_->aConsumer_.load();
        s.dropped = impl_->dropped;
        s.rttMs = impl_->rttMs_.load();
    }
    return s;
}

}  // namespace apxpc::wireless
