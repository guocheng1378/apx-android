// ============================================================================
// 统一控制面 9511 —— 受控服务端 + 控制客户端 + 发现信标（跨平台）
//
// 帧/握手/子命令与 android/tv/net/TcpControlServer.kt、TvControllerClient.kt 逐字节一致。
// 仅网络与帧解析是平台无关部分；系统注入见 input_injector_*，剪贴板监听见同文件。
// ============================================================================
#include "apxpc/wireless/ctrl9511.hpp"
// v184：断链/退出时抬起残留按键（副屏触摸可能停在"按下"帧上）
#include "apxpc/media/touch_inject.hpp"

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
    // v184：握手线程 —— 握手最长要等 5s 超时，不能占着 accept 线程（期间无法接受
    // 新连接，慢连接或恶意连接就能把监听卡住）。用"先 join 上一个"保证串行。
    std::thread hsThread;

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
    std::atomic<uint64_t> aRequestInput_{0}, aInputText_{0}, aInputDone_{0};

    // 远程输入回调（UI 层注册）
    std::function<void(const std::string&)> onRequestInput_;
    std::function<void(const std::string&, uint8_t)> onRemoteInputText_;
    std::function<void()> onRemoteInputDone_;

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
        // v184：原来在**栈上**开 (2+0xFFFF) 与 (16+3+0xFFFF) 两块 64KB 缓冲（合计 128KB），
        // 而本函数跑在剪贴板 watcher 线程上 —— 线程栈更浅时就是一次栈溢出。
        // 改为按实际长度堆分配（也顺便省掉每次调用的 128KB 栈占用）。
        std::vector<uint8_t> body(2 + bytes.size());
        body[0] = static_cast<uint8_t>(bytes.size() & 0xFF);
        body[1] = static_cast<uint8_t>((bytes.size() >> 8) & 0xFF);
        std::memcpy(body.data() + 2, bytes.data(), bytes.size());
        std::vector<uint8_t> buf(apx::kFrameHeaderSize + 3 + bytes.size());
        size_t len = 0;
        if (buildCtrlFrame(buf.data(), buf.size(), len, 0x21, body.data(),
                           static_cast<size_t>(bytes.size()) + 2, seq_.fetch_add(1))) {
            enqueue(buf.data(), len);
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
                    st.connected = false;
                    st.peer.clear();
                }
                clientFd = c;
            }
            // v184：握手移出 accept 线程（见 hsThread 注释）。先 join 上一个握手线程
            // 保证串行，避免并发握手同时改写 readerThread / writerThread 等成员。
            if (hsThread.joinable()) hsThread.join();
            hsThread = std::thread([this, c] { handshake(c); });
        }
    }

    void handshake(Sock s) {
        if (readerThread.joinable()) readerThread.join();
        if (writerThread.joinable()) writerThread.join();

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
#if defined(_WIN32)
        DWORD z = 0;
#else
        struct timeval z { 0, 0 };
#endif
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&z), sizeof(z));

        {
            std::lock_guard<std::mutex> lk(clientMu);
            st.connected = true;
            st.peer = "(peer)";
        }
        readerThread = std::thread([this, s] { readerLoop(s); });
        writerThread = std::thread([this, s] { writerLoop(s); });
    }

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
        // v184：连接断了先抬起残留按键（最后一帧可能是"按下"）——
        // 否则对端消失后 PC 会一直按着不放。
        releaseAllInputs();
        closeSock(s);
        resetClientFor(s);
    }

    /// 抬起所有可能残留的按键（鼠标 / 键盘 / 消费者 / 副屏触摸）。
    /// 断链与停止时调用：注入是"差量"语义，若最后一个收到的帧是"按下"，
    /// 对端随后消失，PC 就会一直按着不放（左键卡住、组合键卡住、拖拽停不下来）。
    void releaseAllInputs() {
        if (injector) {
            injector->injectMouse(0, 0, 0, 0);        // mouseButtons_ 置 0 → 差量抬起
            injector->injectKeyboard(0, nullptr, 0);  // 释放所有键盘键
            injector->injectConsumer(0);              // 释放所有消费者键
        }
        apxpc::media::releaseTouchButtons();          // 副屏触摸路径的鼠标键
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
        } else if (cmd == 0x25 && bodyLen >= 2) {
            // REQUEST_INPUT：手机请求本机输入文本
            const uint8_t hintLen = p[1];
            if (bodyLen >= static_cast<uint32_t>(2 + hintLen)) {
                const std::string hint(reinterpret_cast<const char*>(p + 2), hintLen);
                if (onRequestInput_) onRequestInput_(hint);
            }
            aRequestInput_.fetch_add(1);
        } else if (cmd == 0x26 && bodyLen >= 4) {
            // INPUT_TEXT：手机发来的输入文本
            const uint8_t flags = p[1];
            const uint16_t textLen = static_cast<uint16_t>(p[2]) | (static_cast<uint16_t>(p[3]) << 8);
            if (bodyLen >= static_cast<uint32_t>(4 + textLen)) {
                const std::string text(reinterpret_cast<const char*>(p + 4), textLen);
                if (onRemoteInputText_) onRemoteInputText_(text, flags);
            }
            aInputText_.fetch_add(1);
        } else if (cmd == 0x27) {
            // INPUT_DONE：手机输入完成
            if (onRemoteInputDone_) onRemoteInputDone_();
            aInputDone_.fetch_add(1);
        }
    }

    void writerLoop(Sock s) {
        std::unique_lock<std::mutex> lk(writeMu);
        while (running.load()) {
            writeCv.wait_for(lk, std::chrono::milliseconds(200),
                             [this] { return !outQueue.empty() || !running.load(); });
            if (!running.load()) break;
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
        bcasts.push_back(INADDR_BROADCAST);
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
    im->onRequestInput_ = std::move(onRequestInput_);
    im->onRemoteInputText_ = std::move(onRemoteInputText_);
    im->onRemoteInputDone_ = std::move(onRemoteInputDone_);

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
    // v184：accept 退出后再收握手线程（它由 accept 线程创建）
    if (impl_->hsThread.joinable()) impl_->hsThread.join();
    if (impl_->readerThread.joinable()) impl_->readerThread.join();
    if (impl_->writerThread.joinable()) impl_->writerThread.join();
    if (impl_->beaconThread.joinable()) impl_->beaconThread.join();
    // v184：所有注入相关线程都停了再抬键（此时不会再有新帧把键按下）
    impl_->releaseAllInputs();
    impl_.reset();
}

Ctrl9511ServerStatus Ctrl9511Server::status() const {
    if (!impl_) return Ctrl9511ServerStatus{};
    Ctrl9511ServerStatus s;
    {
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
    s.requestInput = impl_->aRequestInput_.load();
    s.inputText = impl_->aInputText_.load();
    s.inputDone = impl_->aInputDone_.load();
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
    // v184：reader 线程自增、status() 从调用方线程读 —— 裸 uint64_t 是数据竞争，
    // 改为原子（与 Ctrl9511Server 侧的计数方式一致）。
    std::atomic<uint64_t> dropped{0};

    // 注入计数（发送线程累加，status() 按值返回）
    std::atomic<uint64_t> aMouse_{0}, aTouch_{0}, aKeyboard_{0}, aConsumer_{0};
    std::atomic<uint64_t> aRequestInput_{0}, aInputText_{0}, aInputDone_{0};
    // 心跳 RTT：-1 表示未测得；ping 发出时刻由 ping 线程记录，pong 到达时算差
    std::atomic<int64_t> rttMs_{-1};
    std::atomic<int64_t> pingSentMs_{0};
    // fix: 防止双 ping 覆盖时间戳导致 RTT 不准
    std::atomic<bool> pingPending_{false};
    // v184：连续无 pong 的轮数 —— 达到阈值即判定链路失效（半开检测）
    std::atomic<int> missedPongs_{0};

    int64_t nowMs() const {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                   Clock::now().time_since_epoch())
            .count();
    }

    void sendCmd(uint8_t cmd, const uint8_t* body, size_t bodyLen) {
        switch (cmd) {
            case 0x01: aMouse_.fetch_add(1); break;
            case 0x02: aConsumer_.fetch_add(1); break;
            case 0x03: aKeyboard_.fetch_add(1); break;
            case 0x04: aTouch_.fetch_add(1); break;
            case 0x25: aRequestInput_.fetch_add(1); break;
            case 0x26: aInputText_.fetch_add(1); break;
            case 0x27: aInputDone_.fetch_add(1); break;
            default: break;
        }
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

    const uint32_t tokLen = static_cast<uint32_t>(token.size());
    uint8_t hdr[4] = { static_cast<uint8_t>(tokLen & 0xFF), static_cast<uint8_t>((tokLen >> 8) & 0xFF),
                       static_cast<uint8_t>((tokLen >> 16) & 0xFF), static_cast<uint8_t>((tokLen >> 24) & 0xFF) };
    if (!sendAll(s, hdr, 4) || (tokLen > 0 && !sendAll(s, reinterpret_cast<const uint8_t*>(token.data()), tokLen))) {
        closeSock(s); delete im; return false;
    }
    im->sock = s;
    im->running.store(true);
    impl_.reset(im);

    const auto cbClip = onReverseClipboard;
    const auto cbReqInput = onRequestInput;
    const auto cbInputText = onRemoteInputText;
    const auto cbInputDone = onRemoteInputDone;
    const auto cbSpecialKey = onSpecialKey;
    im->readerThread = std::thread([im, cbClip, cbReqInput, cbInputText, cbInputDone, cbSpecialKey] {
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
                        if (cbClip) cbClip(t);
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 1 && payload[0] == 0x25 && bodyLen >= 2) {
                    // 对端请求输入文本
                    const uint8_t hl = payload[1];
                    if (bodyLen >= static_cast<uint32_t>(2 + hl)) {
                        const std::string hint(reinterpret_cast<const char*>(payload + 2), hl);
                        if (cbReqInput) cbReqInput(hint);
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 1 && payload[0] == 0x26 && bodyLen >= 4) {
                    // 对端发回输入文本
                    const uint8_t flags = payload[1];
                    const uint16_t tl = static_cast<uint16_t>(payload[2]) | (static_cast<uint16_t>(payload[3]) << 8);
                    if (bodyLen >= static_cast<uint32_t>(4 + tl)) {
                        const std::string text(reinterpret_cast<const char*>(payload + 4), tl);
                        if (cbInputText) cbInputText(text, flags);
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 1 && payload[0] == 0x27) {
                    if (cbInputDone) cbInputDone();
                } else if (h.streamId == apx::kStreamControl && payload[0] == 0x28 && bodyLen >= 2) {
                    // v184：特殊键/组合键（手机编辑快捷键排）：[0x28, mod, vk]
                    if (cbSpecialKey) cbSpecialKey(payload[1], payload[2]);
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 1 && payload[0] == 'p') {
                    // pong：清除 pending 标志，记录 RTT
                    im->pingPending_.store(false);
                    im->missedPongs_.store(0);   // v184：收到 pong，失败计数清零
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
    // fix: 前一个 ping 的 pong 未到时跳过发送，防止双 ping 覆盖时间戳导致 RTT 不准
    im->pingThread = std::thread([im] {
        while (im->running.load()) {
            if (!im->pingPending_.load()) {
                im->pingSentMs_.store(im->nowMs());
                im->pingPending_.store(true);
                im->sendCmd('p', reinterpret_cast<const uint8_t*>("ing"), 3);
            }
            std::this_thread::sleep_for(std::chrono::seconds(1));
            // 超时兜底：2s 内没有 pong 说明这一轮失败。
            // v184：旧实现只清 pending 等下一轮重发，**从不判定链路失效** ——
            // 对端半开（拔网线 / 被强杀，没有 FIN）时 running 永远为 true、
            // 上层 ready() 恒真、不会重连，PC 发出去的输入全进黑洞。
            // 现在累计连续失败轮数，达 3 轮即判定链路已死：关掉 socket 让 reader
            // 退出并置 running=false，由上层（WirelessSession）重建连接。
            if (im->pingPending_.load() && im->nowMs() - im->pingSentMs_.load() > 2000) {
                im->pingPending_.store(false);
                const int missed = im->missedPongs_.fetch_add(1) + 1;
                if (missed >= 3) {
                    std::fprintf(stderr,
                                 "[apxctl] 心跳连续 %d 轮无 pong，判定链路失效（半开检测）\n", missed);
                    im->running.store(false);
                    Sock s = kBadSock;
                    { std::lock_guard<std::mutex> lk(im->mu); s = im->sock; }
                    if (s != kBadSock) shutdown(s, 0);   // 唤醒阻塞中的 reader，令其退出
                    break;
                }
            }
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
    // 负载格式（与接收端 dispatch 的 `keys = p + 3` 对齐）：
    //   body = [0x03, mod, 保留0, keys[0..5]]
    //                         ↑ body[3] 起是按键码
    // sendCmd 会把 cmd 放在 body[0]，所以这里的 b[] 是 **cmd 之后** 的内容：
    //   b[0]=mod、b[1]=保留 0、b[2..7]=keys
    // 旧实现写成 b[3+i]（等价 body[4] 起）+ 长度 3+count —— 每个键整体后移一位，
    // 接收端读到的是 [0, k0, k1, ...]，表现为"每帧少按最后一个键"（组合键尾键失效），
    // 6 键时还有一次越界读。已修正。
    uint8_t b[9] = { mod, 0, 0, 0, 0, 0, 0, 0, 0 };
    const size_t n = count > 6 ? 6 : count;
    for (size_t i = 0; i < n; ++i) b[2 + i] = keys[i];
    impl_->sendCmd(0x03, b, 2 + n);
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
    // v184：栈上 64KB 缓冲 → 按需堆分配（详见 onClipboardChanged 注释）
    std::vector<uint8_t> b(2 + bytes.size());
    b[0] = static_cast<uint8_t>(bytes.size() & 0xFF);
    b[1] = static_cast<uint8_t>((bytes.size() >> 8) & 0xFF);
    std::memcpy(b.data() + 2, bytes.data(), bytes.size());
    impl_->sendCmd(0x20, b.data(), static_cast<uint32_t>(bytes.size() + 2));
    return true;
}

bool Ctrl9511Client::sendModuleToggle(const std::string& id, bool on) {
    if (!impl_ || id.empty() || id.size() > 255) return false;
    uint8_t b[2 + 255];
    b[0] = static_cast<uint8_t>(id.size());
    std::memcpy(b + 1, id.data(), id.size());
    b[1 + id.size()] = on ? 1 : 0;
    impl_->sendCmd(0x10, b, id.size() + 2);
    return true;
}

bool Ctrl9511Client::sendRequestInput(const std::string& hint) {
    if (!impl_) return false;
    const auto bytes = std::vector<uint8_t>(hint.begin(), hint.end());
    if (bytes.size() > 255) return false;
    uint8_t b[1 + 255];
    b[0] = 0x25;
    b[1] = static_cast<uint8_t>(bytes.size());
    std::memcpy(b + 2, bytes.data(), bytes.size());
    impl_->sendCmd(0x25, b + 1, 1 + bytes.size());
    return true;
}

bool Ctrl9511Client::sendInputText(const std::string& text, uint8_t flags) {
    if (!impl_ || text.empty()) return false;
    const auto bytes = std::vector<uint8_t>(text.begin(), text.end());
    if (bytes.size() > 0xFFFF) return false;
    // v184：栈上 64KB 缓冲 → 按需堆分配
    std::vector<uint8_t> b(3 + bytes.size());
    b[0] = flags;
    b[1] = static_cast<uint8_t>(bytes.size() & 0xFF);
    b[2] = static_cast<uint8_t>((bytes.size() >> 8) & 0xFF);
    std::memcpy(b.data() + 3, bytes.data(), bytes.size());
    impl_->sendCmd(0x26, b.data(), static_cast<uint32_t>(3 + bytes.size()));
    return true;
}

bool Ctrl9511Client::sendInputDone() {
    if (!impl_) return false;
    impl_->sendCmd(0x27, nullptr, 0);
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
        s.dropped = impl_->dropped.load();
        s.rttMs = impl_->rttMs_.load();
        s.requestInput = impl_->aRequestInput_.load();
        s.inputText = impl_->aInputText_.load();
        s.inputDone = impl_->aInputDone_.load();
    }
    return s;
}

}  // namespace apxpc::wireless
