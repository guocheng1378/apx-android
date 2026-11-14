// ============================================================================
// 统一控制面 9511 —— 受控服务端 + 控制客户端 + 发现信标（跨平台）
//
// 帧/握手/子命令与 android/tv/net/TcpControlServer.kt、TvControllerClient.kt 逐字节一致。
// 仅网络与帧解析是平台无关部分；系统注入见 input_injector_*，剪贴板监听见同文件。
// ============================================================================
#include "apxpc/wireless/ctrl9511.hpp"
#include "apxpc/log.hpp"   // 启动失败/异常要留痕（mac 端此前在这里 terminate，日志是唯一现场）
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
/// 关**监听**套接字：先 shutdown 再 close，这是「唤醒另一个线程里阻塞的 accept()」的
/// 唯一可靠办法（只 close 不行，见非 Win 分支的同名注释）。
static void closeListenSock(Sock s) {
    if (s == INVALID_SOCKET) return;
    shutdown(s, SD_BOTH);
    closesocket(s);
}
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
/// 关**监听**套接字：必须先 `shutdown(SHUT_RDWR)` 再 `close`。
/// 只 close 唤不醒另一个线程里阻塞的 accept()：那边持着该 socket 的引用，
/// close 之后它继续睡，于是 `join(acceptThread)` 永久卡住 —— 实测（Linux/BSD 同）：
///   只 close     → accept 线程仍在阻塞，join 卡死
///   shutdown 在先 → accept 立即返回 EINVAL，线程退出
/// 这是 issue#47 顺带查出来的**既有**缺陷：mac 上「apxdesktop 退出要 pkill」就是它。
/// 返回值忽略：监听套接字上 shutdown 本就可能报 EINVAL（已关机/未连接），无妨。
static void closeListenSock(Sock s) {
    if (s < 0) return;
    shutdown(s, SHUT_RDWR);
    close(s);
}
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

/// 手机文件路径的 UTF-8 截断（0x44/0x46 发送侧）：协议上限 1024 字节
///（与 Android ApxFrame.PHONEFILE_PATH_MAX / utf8Clamp 对拍），不在多字节字符中间断开。
std::string clampPhoneFilePath(const std::string& path) {
    constexpr size_t kPathMax = 1024;
    if (path.size() <= kPathMax) return path;
    size_t end = kPathMax;
    while (end > 0 && (static_cast<uint8_t>(path[end]) & 0xC0) == 0x80) --end;  // 回退 UTF-8 续字节
    return path.substr(0, end);
}

/// 通用 UTF-8 截断（0x41 通知回复发送侧）：与 Android ApxFrame.utf8Clamp 同算法，
/// 超长只取前 max 字节且不在多字节字符中间断开（key ≤512B、text ≤1KB 均走它）。
std::string clampUtf8Reply(const std::string& s, size_t maxBytes) {
    if (s.size() <= maxBytes) return s;
    size_t end = maxBytes;
    while (end > 0 && (static_cast<uint8_t>(s[end]) & 0xC0) == 0x80) --end;  // 回退 UTF-8 续字节
    return s.substr(0, end);
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
    /// 手机模式状态（0x31）：计数 + 最近一次位图（位布局见 phone_mode.hpp）
    std::atomic<uint64_t> aModeStatus_{0};
    std::atomic<uint8_t> modeFlags_{0};
    /// 本次会话是否收到过模式帧（见 Ctrl9511ServerStatus::modeReported）
    std::atomic<bool> modeReported_{false};
    /// 对端能力位图（0x33 能力协商，2026-11-11；位定义见 kCaps9511*，头文件）。
    /// 0 = 本会话未收到 —— 旧版手机没有该机制，能力帧族一律不发（前向兼容）。
    std::atomic<uint64_t> peerCaps_{0};

    /**
     * 发 0x33 能力协商（2026-11-11）：把本机（PC 服务端）的能力位图报给连入的手机。
     * 命令号按字面量写（0x33），与 kCaps9511* / Android ApxFrame.CMD_CAPS_SYNC /
     * scripts/check_protocol.py 三处对拍 —— 改这里必须同步。
     */
    void sendCapsSync() {
        // 手机控 PC（服务端）：本端接 0x4C 手柄截图，报 bit6；PC 控手机方向的
        // sendCapsSync（下方 Client::Impl 同名函数）**不加** —— 那条链路没有 0x4C 接收端。
        // bit7 副屏远程开关（0x4D）同理只在服务端报 —— 0x4D 的接收端（ActionRouter
        // 接线）只在手机控 PC 方向存在。
        const uint64_t own = kCaps9511NotifySync | kCaps9511NotifyReply | kCaps9511FindPhone
                           | kCaps9511PhoneFiles | kCaps9511Album | kCaps9511AbsolutePtr
                           | kCaps9511GamepadShot | kCaps9511ScreenToggle;
        uint8_t body[9] = {0x33, 0, 0, 0, 0, 0, 0, 0, 0};
        for (int i = 0; i < 8; ++i) body[1 + i] = static_cast<uint8_t>((own >> (8 * i)) & 0xFF);
        sendCmd(0x33, body + 1, 8);  // sendCmd 自身会把 cmd 放帧头第 1 字节，这里只传位图 8 字节
    }

    /** 收 0x33 能力协商：解析对端能力位图（u64 小端，8 字节）存 peerCaps_（pump 线程，原子写）。 */
    void onCapsSync(const uint8_t* p, uint32_t bodyLen) {
        (void)bodyLen;  // 调用点已保证 >= 9
        uint64_t caps = 0;
        for (int i = 0; i < 8; ++i) caps |= static_cast<uint64_t>(p[1 + i]) << (8 * i);
        peerCaps_.store(caps);
    }

    // 远程输入回调（UI 层注册）
    std::function<void(const std::string&)> onRequestInput_;
    std::function<void(const std::string&, uint8_t)> onRemoteInputText_;
    std::function<void()> onRemoteInputDone_;
    // 通知同步回调（0x40/0x42，2026-11-11）：外层 setter 写入、start() 时搬进来
    std::function<void(const std::string&, const std::string&, const std::string&, const std::string&)> onNotifyPush_;
    std::function<void(const std::string&)> onNotifyDismiss_;
    std::atomic<uint64_t> aNotifyPush_{0}, aNotifyDismiss_{0};
    // 手机文件浏览回调（0x45/0x47，2026-11-11）：搬运口径同上（外层 setter 写、start() 搬运）
    std::function<void(uint8_t, const std::string&, const std::vector<PhoneFileEntry>&)> onPhoneFileList_;
    std::function<void(uint8_t, const std::string&)> onPhoneFileFail_;
    std::atomic<uint64_t> aPhoneFileList_{0}, aPhoneFileFail_{0};
    // 相册投影回调（0x49/0x47，2026-11-12）：搬运口径同上
    std::function<void(uint8_t, uint32_t, const std::vector<AlbumEntry>&)> onAlbumPage_;
    std::function<void(uint8_t, const std::string&)> onAlbumPageFail_;
    std::atomic<uint64_t> aAlbumPage_{0}, aAlbumPageFail_{0};
    // 手柄截图回调（0x4C，2026-11-14，能力位 bit6）：外层 setter 写、start() 搬运
    std::function<void()> onGamepadShot_;
    std::atomic<uint64_t> aGamepadShot_{0};
    // 副屏远程开关回调（0x4D，2026-11-14，能力位 bit7）：搬运口径同手柄截图回调
    std::function<void(uint8_t)> onScreenToggle_;
    std::atomic<uint64_t> aScreenToggle_{0};
    // 通知回复回执回调（0x43，2026-11-14，能力位 bit1）：外层 setter 写、start() 搬运
    std::function<void(uint8_t)> onNotifyReplyResult_;

    void enqueue(const uint8_t* frame, size_t len) {
        {
            std::lock_guard<std::mutex> lk(writeMu);
            outQueue.emplace_back(frame, frame + len);
        }
        writeCv.notify_one();
    }

    /**
     * 发一帧控制命令（0x38 / 0x44 / 0x46 / 0x48 / 0x4A / 0x4B 等，2026-11-11）：构帧后进
     * 发送队列，由 writer 线程按序写出。与 Client::Impl::sendCmd 同名同签名，但服务端
     * 没有活动统计需求（那些计数只服务 PC 控制手机方向的状态面板），保持精简。
     * cmd 由本函数写入帧头第 1 字节，body 只传命令号之后的内容。
     */
    void sendCmd(uint8_t cmd, const uint8_t* body, size_t bodyLen) {
        std::vector<uint8_t> buf(apx::kFrameHeaderSize + 1 + bodyLen + apx::kFrameCrcSize);
        size_t len = 0;
        if (buildCtrlFrame(buf.data(), buf.size(), len, cmd, body, bodyLen, seq_.fetch_add(1)))
            enqueue(buf.data(), len);
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
            // v223.29.12 热修：writerLoop 的 sendAll 没有发送超时 —— 对端半死（WiFi 漫游 /
            // 手机息屏收流暂停）时 send 缓冲一满就永久阻塞，handshake() 里
            // join(writerThread) 卡死、accept 线程随后在 join(hsThread) 上停摆；此时**监听
            // 套接字仍然开着**，对端 connect 照样成功却永远没人握手/读帧/回 pong ——
            // 手机侧「连上后 24s 零入向判死 → 重连」死循环、面板恒显「手机未上报」的服务端
            // 路径就是它。接受即挂 SO_SNDTIMEO(3s)，让每一代会话的收尾 join 全部有上界
            // （reader 侧 SO_RCVTIMEO=500ms、握手头 5s 超时本就有界）。
#if defined(_WIN32)
            DWORD sndTo = 3000;
            setsockopt(c, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sndTo), sizeof(sndTo));
#else
            struct timeval sndTv { 3, 0 };
            setsockopt(c, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sndTv), sizeof(sndTv));
#endif
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
        /* 新会话从「未上报」开始：上一会话的手机模式快照（可用性 + 在用哪条）必须作废，
           否则面板会把上一模式当成当前状态一直显示（issue#52：切了模式却看到旧状态）。 */
        modeReported_.store(false);
        modeFlags_.store(0);
        // 能力协商（0x33，2026-11-11）：新会话清掉上一会话的对端能力，并把本机能力报给对端 ——
        // 不发的话手机学到的永远是 0，能力帧族全被门掉（通知同步/查找手机整条断）。
        peerCaps_.store(0);
        sendCapsSync();
        // ★ 新会话必须清空发送队列：没有对端时 onClipboardChanged() 每变一次剪贴板就
        //   enqueue 一帧反向剪贴板(0x21)，只增不减；不清的话手机一连上就被灌一串历史
        //   旧内容、剪贴板被刷乱。（媒体层 media_session.cpp 同场景已有 outQ_.clear()）
        {
            std::lock_guard<std::mutex> lk(writeMu);
            outQueue.clear();
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
        /* 断链即作废手机模式快照：留着的话面板会继续显示上一模式的「可用 / 在用」，
           用户看到的就是「都断了还写着无线在用」。——「不知道」就说不知道。 */
        modeReported_.store(false);
        modeFlags_.store(0);
        peerCaps_.store(0);   // 能力协商（0x33）：断链即作废，重连后由新一轮 0x33 重建
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
            const int8_t wheel = static_cast<int8_t>(p[4]);
            // 横向滚动 = 0x01 帧第 6 字节（可选）。老控制端只发 5 字节 → pan=0，
            // 行为与本字段加入前完全一致；新控制端发 6 字节时老 PC 端读前 5 字节、
            // 忽略多出来的字节（接收侧一律按 bodyLen>= 判，不做等长断言）。
            const int8_t pan = (bodyLen >= 6) ? static_cast<int8_t>(p[5]) : 0;
            if (injector) injector->injectMouse(buttons, dx, dy, wheel, pan);
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
        } else if (cmd == 0x06 && bodyLen >= 9) {
            // 0x06 绝对指针（2026-11-13，能力位 bit5）：手机小地图「屏幕定位」——
            // 归一化 0..65535 映射整个虚拟桌面（injectAbsolute），与 0x04 副屏触摸
            // 的「映射副屏」语义不同，故单独命令号；计数与触摸同族（aTouch_）。
            const uint8_t action = p[1];
            const uint8_t buttons = p[2];
            const uint16_t x = apx::getU16(p + 3);
            const uint16_t y = apx::getU16(p + 5);
            if (injector) injector->injectAbsolute(action, buttons, x, y);
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
        } else if (cmd == 0x22 && bodyLen >= 2) {
            // POWER_ACTION（手机遥控页的「关机 / 重启」）。
            // ★ PC 端**刻意不执行**：远程关机/重启需要提权（SE_SHUTDOWN_NAME），而且一个
            //   手机按钮直接关掉用户的电脑风险过高 —— 本机能力边界就定在这里。
            //   此前这条命令没有分支：用户点了「关机」，手机提示"已发送指令"，
            //   而 PC 侧既没动作也没日志，属典型的"静默无操作"（协议一致性校验脚本
            //   正是靠"有人发没人收"把它挑出来的）。
            //   现在显式记一条，导出的诊断包里能直接看到；发送侧应据此在目标是
            //   PC 时禁用这两个按钮（见手机端 ControlTarget.type）。
            std::fprintf(stderr, "[apxctl] 收到电源动作请求 action=%u，PC 端不执行远程关机/重启\n",
                         static_cast<unsigned>(p[1]));
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
        } else if (cmd == 0x4C) {
            // 手柄截图（手机手柄页中央 📷 键 → 让 PC 抓自己屏幕存 PNG，issue#170 #7）。
            // 载荷空；抓屏 + 存图 + 气泡回执都在接线回调里（desktop_main），这里只认领
            // 帧号 —— 命令零参数，不做 bodyLen 门控（旧控制端/新控制端发的一字节相同）。
            if (onGamepadShot_) onGamepadShot_();
            aGamepadShot_.fetch_add(1);
        } else if (cmd == 0x4D && bodyLen >= 2) {
            // 副屏远程开关（手机副屏页右下角入口 → 一键插/拔虚拟显示器，2026-11-14，
            // 能力位 bit7）。载荷 [0x4D][on u8]：1=开 0=关；动作与配置落盘都在接线回调
            // 里（desktop_main 接 ActionRouter），托盘气泡回执结果，这里只认领转发。
            if (onScreenToggle_) onScreenToggle_(p[1]);
            aScreenToggle_.fetch_add(1);
        } else if (cmd == 0x31 && bodyLen >= 2) {
            /* MODE_STATUS：手机上报「三种接入模式的可用性 + 当前在用哪个」（issue#47 面板侧诉求）。
               面板原来那三个开关里只有无线是真的，蓝牙与 USB 的真开关在手机端 ——
               拨了没反应只能被当成坏了；改成照实显示手机报上来的状态。
               位布局见 include/apxpc/wireless/phone_mode.hpp（用例钉住，与 Android 侧对拍）。 */
            modeFlags_.store(p[1]);
            modeReported_.store(true);
            aModeStatus_.fetch_add(1);
        } else if (cmd == 0x33 && bodyLen >= 9) {
            // CAPS_SYNC 能力协商（2026-11-11）：对端报能力位图（u64 小端 8 字节）。
            // 元数据落原子变量，不动注入路径。
            onCapsSync(p, bodyLen);
        } else if (cmd == 0x40 && bodyLen >= 7) {
            // NOTIFY_PUSH 通知推送（2026-11-11，能力位 bit0）：手机 → PC 托盘气泡。
            // 载荷（与 Android ApxFrame.packNotifyPush 对拍）：
            //   [0x40][keyLen u16 LE][key][titleLen u16 LE][title][textLen u16 LE][text][appLen u8][app]
            // 解析失败整帧丢弃（记 dropped 不计成功），不回调 —— 坏载荷宁可少一条气泡。
            const uint16_t keyLen = static_cast<uint16_t>(p[1]) | (static_cast<uint16_t>(p[2]) << 8);
            uint32_t off = 3;
            if (off + keyLen + 2 <= bodyLen) {
                const std::string key(reinterpret_cast<const char*>(p + off), keyLen);
                off += keyLen;
                const uint16_t titleLen = static_cast<uint16_t>(p[off]) | (static_cast<uint16_t>(p[off + 1]) << 8);
                off += 2;
                if (off + titleLen + 2 <= bodyLen) {
                    const std::string title(reinterpret_cast<const char*>(p + off), titleLen);
                    off += titleLen;
                    const uint16_t textLen = static_cast<uint16_t>(p[off]) | (static_cast<uint16_t>(p[off + 1]) << 8);
                    off += 2;
                    if (off + textLen + 1 <= bodyLen) {
                        const std::string text(reinterpret_cast<const char*>(p + off), textLen);
                        off += textLen;
                        const uint8_t appLen = p[off];
                        off += 1;
                        if (off + appLen <= bodyLen) {
                            const std::string app(reinterpret_cast<const char*>(p + off), appLen);
                            if (onNotifyPush_) onNotifyPush_(key, title, text, app);
                            aNotifyPush_.fetch_add(1);
                        }
                    }
                }
            }
        } else if (cmd == 0x42 && bodyLen >= 3) {
            // NOTIFY_DISMISS 通知消除（与 0x40 配对）：用户在手机上划掉/点开通知。
            // 当前气泡无法撤回，仅回调记账 —— 为「PC 端通知中心/回跳」等后续功能留状态。
            const uint16_t keyLen = static_cast<uint16_t>(p[1]) | (static_cast<uint16_t>(p[2]) << 8);
            if (3 + keyLen <= bodyLen) {
                const std::string key(reinterpret_cast<const char*>(p + 3), keyLen);
                if (onNotifyDismiss_) onNotifyDismiss_(key);
                aNotifyDismiss_.fetch_add(1);
            }
        } else if (cmd == 0x43 && bodyLen >= 2) {
            // NOTIFY_REPLY_RESULT 通知回复回执（2026-11-14，能力位 bit1）：手机回 0x41 的
            // 终态——**成功（err=0）也回帧**（与 0x47 不同：回复成功与否用户在等）。
            // err 取值与 Android ApxFrame.NOTIFY_REPLY_ERR_* 一致，由接线方转成气泡文案。
            const uint8_t err = p[1];
            if (onNotifyReplyResult_) onNotifyReplyResult_(err);
        } else if (cmd == 0x45 && bodyLen >= 4) {
            // PHONE_FILE_LIST_REPLY 目录应答（2026-11-11，能力位 bit3）：对端回报目录内容。
            // 载荷（与 Android ApxFrame.packPhoneFileListReply 对拍）：
            //   [0x45][err u8][pathLen u16 LE][path][count u16 LE]
            //   + count × [nameLen u16 LE][name][dir u8][size u64 LE]
            // 解析失败整帧丢弃不回调（坏载荷宁可少一次刷新，与 0x40 同口径）。
            const uint8_t err = p[1];
            const uint16_t pathLen = static_cast<uint16_t>(p[2]) | (static_cast<uint16_t>(p[3]) << 8);
            uint32_t off = 4;
            if (off + pathLen + 2 <= bodyLen) {
                const std::string path(reinterpret_cast<const char*>(p + off), pathLen);
                off += pathLen;
                const uint16_t count = static_cast<uint16_t>(p[off]) | (static_cast<uint16_t>(p[off + 1]) << 8);
                off += 2;
                std::vector<PhoneFileEntry> entries;
                entries.reserve(count < 200 ? count : 200);   // 协议上限 200（PHONEFILE_MAX_ENTRIES）
                for (int i = 0; i < count && off + 2 <= bodyLen; ++i) {
                    const uint16_t nLen = static_cast<uint16_t>(p[off]) | (static_cast<uint16_t>(p[off + 1]) << 8);
                    off += 2;
                    if (off + nLen + 9 > bodyLen) break;       // 条目残缺：到手的先交，整帧不硬解
                    PhoneFileEntry e;
                    e.name.assign(reinterpret_cast<const char*>(p + off), nLen);
                    off += nLen;
                    e.isDir = p[off] != 0;
                    off += 1;
                    for (int b = 0; b < 8; ++b)
                        e.size |= static_cast<uint64_t>(p[off + b]) << (8 * b);
                    off += 8;
                    entries.push_back(std::move(e));
                }
                if (onPhoneFileList_) onPhoneFileList_(err, path, entries);
                aPhoneFileList_.fetch_add(1);
            }
        } else if (cmd == 0x49 && bodyLen >= 8) {
            // ALBUM_PAGE_REPLY 相册分页应答（2026-11-12，能力位 bit4，与 0x48 配对）。
            // 载荷（与 Android ApxFrame.packAlbumPageReply 对拍）：
            //   [0x49][err u8][page u32 LE][count u16 LE]
            //   + count × [id u32 LE][added u32 LE][size u64 LE]
            //             [pathLen u16 LE][path][nameLen u16 LE][name]
            // 解析失败整帧丢弃不回调（与 0x45 同口径，坏载荷宁可少一次刷新）。
            const uint8_t err = p[1];
            uint32_t page = 0;
            for (int b = 0; b < 4; ++b)
                page |= static_cast<uint32_t>(p[2 + b]) << (8 * b);
            const uint16_t count = static_cast<uint16_t>(p[6]) | (static_cast<uint16_t>(p[7]) << 8);
            uint32_t off = 8;
            std::vector<AlbumEntry> entries;
            entries.reserve(count < 60 ? count : 60);      // 协议上限 60（ALBUM_PAGE_SIZE）
            for (int i = 0; i < count && off + 12 <= bodyLen; ++i) {
                AlbumEntry e;
                for (int b = 0; b < 4; ++b)
                    e.id |= static_cast<uint32_t>(p[off + b]) << (8 * b);
                off += 4;
                for (int b = 0; b < 4; ++b)
                    e.added |= static_cast<uint32_t>(p[off + b]) << (8 * b);
                off += 4;
                for (int b = 0; b < 8; ++b)
                    e.size |= static_cast<uint64_t>(p[off + b]) << (8 * b);
                off += 8;
                const uint16_t pLen = static_cast<uint16_t>(p[off]) | (static_cast<uint16_t>(p[off + 1]) << 8);
                off += 2;
                if (off + pLen > bodyLen) break;           // 条目残缺：到手的先交，整帧不硬解
                e.path.assign(reinterpret_cast<const char*>(p + off), pLen);
                off += pLen;
                if (off + 2 > bodyLen) break;
                const uint16_t nLen = static_cast<uint16_t>(p[off]) | (static_cast<uint16_t>(p[off + 1]) << 8);
                off += 2;
                if (off + nLen > bodyLen) break;
                e.name.assign(reinterpret_cast<const char*>(p + off), nLen);
                off += nLen;
                entries.push_back(std::move(e));
            }
            if (onAlbumPage_) onAlbumPage_(err, page, entries);
            aAlbumPage_.fetch_add(1);
        } else if (cmd == 0x47 && bodyLen >= 4) {
            // PHONE_FILE_FAIL 取文件失败回执（与 0x46/0x4B 配对）：文件/缩略图本体没推回来
            // 时的替代信号。path 以 "album:thumb:" 开头的是相册缩略图失败（0x4B 配对），
            // 其余是手机文件失败（0x46 配对）—— 回执同帧复用，按前缀分流。
            const uint8_t err = p[1];
            const uint16_t pathLen = static_cast<uint16_t>(p[2]) | (static_cast<uint16_t>(p[3]) << 8);
            if (4 + pathLen <= bodyLen) {
                const std::string path(reinterpret_cast<const char*>(p + 4), pathLen);
                static const char kAlbumThumbPrefix[] = "album:thumb:";
                if (path.rfind(kAlbumThumbPrefix, 0) == 0) {
                    if (onAlbumPageFail_) onAlbumPageFail_(err, path);
                    aAlbumPageFail_.fetch_add(1);
                } else {
                    if (onPhoneFileFail_) onPhoneFileFail_(err, path);
                    aPhoneFileFail_.fetch_add(1);
                }
            }
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

    /* 失败即整体回收（issue#47 硬修）：三种失败路径原先各自 delete/hand-rolled，
       漏掉谁就是一次「未 join 的 std::thread 析构 → std::terminate」。
       这里一个出口统一收口，且跨过「线程起了一半」的情形。 */
    auto cleanup = [&]() {
        im->running.store(false);
        closeListenSock(im->listenFd);
        im->listenFd = kBadSock;
        if (im->clip) im->clip->stop();
        if (im->acceptThread.joinable()) im->acceptThread.join();
        if (im->hsThread.joinable()) im->hsThread.join();      // accept 退出后再收握手线程（它由 accept 线程创建）
        if (im->readerThread.joinable()) im->readerThread.join();
        if (im->writerThread.joinable()) im->writerThread.join();
        if (im->beaconThread.joinable()) im->beaconThread.join();
        delete im;
    };

    /* 整函数兜底：mac 上没有 set_terminate（v223 崩溃即 terminate → abort）。
       任何 std::thread 构造失败、分配失败都在这里转成「启动失败」而不是进程死亡。 */
    try {
        im->injector = createPlatformInjector();
        if (!im->injector) {
            im->st.error = "平台输入注入器创建失败";
            cleanup(); return false;
        }
        /* 注入权限（macOS）：只告警不拦截 —— 服务照常起、帧照收，但 CGEventPost 会被
           系统丢弃。真机报障里「手机按了全无反应」最难查的就是这种「连上了、帧也到了、
           注入被吞」；面板横幅 + 首次输入时的系统授权引导兜住它。 */
        if (!platformInputTrusted()) {
            APX_LOGW("本机未获得输入注入权限（macOS：系统设置 → 隐私与安全性 → {} → 勾选全能外设；改完须重启应用）"
                     "：手机发来的键鼠此刻不会生效", inputTrustSettingsLabel());
        }

        /* 顺序很关键（issue#47 根因）：先把监听端口占上，成功之后再起剪贴板监视线程。
           历史顺序是「先起剪贴板线程 → 再 bind」，于是 bind 失败时清理要负责停线程；
           而 deepseek 之外还有一处对照：apxdesktop 的 main 先起 9511、runWebPanelHttp
           又起一次 → 第二次必然 bind 失败 → 走到这条清理路径 → 线程未 join 而 terminate。 */
        im->onRequestInput_ = std::move(onRequestInput_);
        im->onRemoteInputText_ = std::move(onRemoteInputText_);
        im->onRemoteInputDone_ = std::move(onRemoteInputDone_);
        // 通知同步回调（0x40/0x42，2026-11-11）：同款搬运 —— start() 之后外层 setter
        // 再赋值也不会丢（回调成员已归 Impl 所有，外层只是个中转）。
        im->onNotifyPush_ = std::move(onNotifyPush_);
        im->onNotifyDismiss_ = std::move(onNotifyDismiss_);
        // 手机文件浏览回调（0x45/0x47，2026-11-11）：同款搬运
        im->onPhoneFileList_ = std::move(onPhoneFileList_);
        im->onPhoneFileFail_ = std::move(onPhoneFileFail_);
        // 相册投影回调（0x49/0x47，2026-11-12）：同款搬运
        im->onAlbumPage_ = std::move(onAlbumPage_);
        im->onAlbumPageFail_ = std::move(onAlbumPageFail_);
        // 通知回复回执回调（0x43，2026-11-14，能力位 bit1）：同款搬运
        im->onNotifyReplyResult_ = std::move(onNotifyReplyResult_);
        // 手柄截图回调（0x4C，2026-11-14）：同款搬运
        im->onGamepadShot_ = std::move(onGamepadShot_);
        // 副屏远程开关回调（0x4D，2026-11-14）：同款搬运
        im->onScreenToggle_ = std::move(onScreenToggle_);

        im->listenFd = ::socket(AF_INET, SOCK_STREAM, 0);
        if (im->listenFd == kBadSock) { im->st.error = "socket 创建失败"; cleanup(); return false; }
        int yes = 1;
        setsockopt(im->listenFd, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof(yes));
        sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_addr.s_addr = INADDR_ANY; addr.sin_port = htons(port);
        if (::bind(im->listenFd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
            im->st.error = "bind 失败（端口被占？）"; cleanup(); return false;
        }
        if (::listen(im->listenFd, 1) != 0) { im->st.error = "listen 失败"; cleanup(); return false; }

        /* 监听已就绪：此后任何一步失败都不再影响端口归属判断，可以安全起后台线程。 */
        im->running.store(true);
        impl_.reset(im);                                  // 所有权移交后，析构保证调用 stop()

        im->acceptThread = std::thread([im] { im->acceptLoop(); });
        im->beaconThread = std::thread([im] { im->beaconLoop(); });

        if (reverseClipboard_) {
            im->clip = createPlatformClipboardWatcher(
                [im](const std::string& t) { im->onClipboardChanged(t); });
            if (im->clip && !im->clip->start()) {
                APX_LOGW("剪贴板反向监听启动失败（不影响受控）");
                im->clip.reset();
            }
        }

        im->st.listening = true;
        return true;
    } catch (const std::exception& e) {
        APX_LOGE("9511 受控端启动异常：{}", e.what());
        const bool owned = (impl_.get() == im);
        if (owned) {
            stop();                                        // 走正常停机路径（含 join 全部线程）
        } else {
            cleanup();
        }
        return false;
    } catch (...) {
        APX_LOGE("9511 受控端启动异常（非 std 异常）");
        if (impl_.get() == im) stop(); else cleanup();
        return false;
    }
}

void Ctrl9511Server::stop() {
    if (!impl_) return;
    impl_->running.store(false);
    // 先 shutdown 再 close：唤醒阻塞中的 accept（只 close 会 join 卡死，见 closeListenSock）
    closeListenSock(impl_->listenFd);
    impl_->listenFd = kBadSock;
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

// 查找手机（0x38，2026-11-11，能力位 bit2）：手机控 PC 的链路上，托盘反向让手机响铃。
// 能力门控与服务端同口径：手机连入握手后报 0x33（存 peerCaps_）；报过且不含该位才跳过，
// 没报（peerCaps_=0，旧版手机）时照发 —— 旧版对未知 cmd 静默忽略，行为无害。
bool Ctrl9511Server::sendFindPhone(uint8_t action) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511FindPhone) == 0) return false;
    impl_->sendCmd(0x38, &action, 1);
    return true;
}

// 手机文件浏览发送（0x44/0x46，2026-11-11，能力位 bit3）：门控与 sendFindPhone 同口径 ——
// 对端报过 0x33 且缺 kCaps9511PhoneFiles 才跳过；没报（旧版）照发，对端静默忽略无害。
// body=[pathLen u16 LE][path]，sendCmd 会把 cmd 放帧头第 1 字节，这里只给 cmd 之后的内容。
bool Ctrl9511Server::sendPhoneFileListReq(const std::string& path) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511PhoneFiles) == 0) return false;
    const auto p = clampPhoneFilePath(path);
    uint8_t hdr[2] = { static_cast<uint8_t>(p.size() & 0xFF),
                       static_cast<uint8_t>((p.size() >> 8) & 0xFF) };
    std::vector<uint8_t> body(hdr, hdr + 2);
    body.insert(body.end(), p.begin(), p.end());
    impl_->sendCmd(0x44, body.data(), body.size());
    return true;
}

bool Ctrl9511Server::sendPhoneFilePullReq(const std::string& path) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511PhoneFiles) == 0) return false;
    const auto p = clampPhoneFilePath(path);
    uint8_t hdr[2] = { static_cast<uint8_t>(p.size() & 0xFF),
                       static_cast<uint8_t>((p.size() >> 8) & 0xFF) };
    std::vector<uint8_t> body(hdr, hdr + 2);
    body.insert(body.end(), p.begin(), p.end());
    impl_->sendCmd(0x46, body.data(), body.size());
    return true;
}

// 相册投影发送（0x48/0x4A/0x4B，2026-11-12，能力位 bit4）：门控与文件浏览同口径 ——
// 对端报过 0x33 且缺 kCaps9511Album 才跳过；没报（旧版）照发，对端静默忽略无害。
// 载荷与 Android ApxFrame.packAlbumPageReq/WatchReq/ThumbReq 对拍（小端逐字节）。
bool Ctrl9511Server::sendAlbumPageReq(uint32_t page) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511Album) == 0) return false;
    uint8_t body[4] = { static_cast<uint8_t>(page & 0xFF),
                        static_cast<uint8_t>((page >> 8) & 0xFF),
                        static_cast<uint8_t>((page >> 16) & 0xFF),
                        static_cast<uint8_t>((page >> 24) & 0xFF) };
    impl_->sendCmd(0x48, body, sizeof(body));
    return true;
}

bool Ctrl9511Server::sendAlbumWatchReq(bool on) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511Album) == 0) return false;
    const uint8_t body = on ? 1 : 0;
    impl_->sendCmd(0x4A, &body, 1);
    return true;
}

bool Ctrl9511Server::sendAlbumThumbReq(uint32_t id) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511Album) == 0) return false;
    uint8_t body[4] = { static_cast<uint8_t>(id & 0xFF),
                        static_cast<uint8_t>((id >> 8) & 0xFF),
                        static_cast<uint8_t>((id >> 16) & 0xFF),
                        static_cast<uint8_t>((id >> 24) & 0xFF) };
    impl_->sendCmd(0x4B, body, sizeof(body));
    return true;
}

// 通知回复发送（0x41，2026-11-14，能力位 bit1）：手机控 PC 的链路上，PC 托盘点气泡
// 「回复」反向让手机代发消息。载荷（与 Android ApxFrame.parseNotifyReply 对拍）：
//   [keyLen u16 LE][key][textLen u16 LE][text]（sendCmd 会把 cmd 放帧头第 1 字节）。
// 门控与 sendFindPhone 同口径：手机没报过 0x33（旧版）照发、报过且缺 kCaps9511NotifyReply
// 才明确跳过 —— 手机端 bit1 随通知使用权开关上报，缺位即无 action 缓存，发了也是白等。
// key 为空直接拒绝（0x40 发送侧保证 key 非空，这里是防御），回执走 0x43 回调。
bool Ctrl9511Server::sendNotifyReply(const std::string& key, const std::string& text) {
    if (!impl_ || key.empty()) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511NotifyReply) == 0) return false;
    const auto k = clampUtf8Reply(key, 512);
    const auto t = clampUtf8Reply(text, 1024);
    std::vector<uint8_t> body(2 + k.size() + 2 + t.size());
    size_t off = 0;
    body[off++] = static_cast<uint8_t>(k.size() & 0xFF);
    body[off++] = static_cast<uint8_t>((k.size() >> 8) & 0xFF);
    std::memcpy(body.data() + off, k.data(), k.size()); off += k.size();
    body[off++] = static_cast<uint8_t>(t.size() & 0xFF);
    body[off++] = static_cast<uint8_t>((t.size() >> 8) & 0xFF);
    std::memcpy(body.data() + off, t.data(), t.size());
    impl_->sendCmd(0x41, body.data(), body.size());
    return true;
}

Ctrl9511ServerStatus Ctrl9511Server::status() const {
    if (!impl_) {
        // 受控端没在本进程跑（9511 被另一个实例占用）：其余字段保持默认，但注入权限是
        // 进程级 TCC 检测 —— 面板自己的 preflight 照实问一次。别让「本面板与另一实例
        // 都没被授权」的丢输入场景因为默认 true 而伪装成横幅消失。
        Ctrl9511ServerStatus s0;
        s0.inputTrusted = platformInputTrusted();
        return s0;
    }
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
    s.modeStatus = impl_->aModeStatus_.load();
    s.modeFlags = impl_->modeFlags_.load();
    s.modeReported = impl_->modeReported_.load();
    s.peerCaps = impl_->peerCaps_.load();
    s.notifyPush = impl_->aNotifyPush_.load();
    s.notifyDismiss = impl_->aNotifyDismiss_.load();
    /* 注入权限：macOS 无「辅助功能」授权时 CGEventPost 会被系统静默丢弃。
       每次读状态实时问一次（一次 TCC 查询），面板据此打横幅 —— 不让「连上了、
       帧也到了、注入被吞」这种最难查的组合继续伪装成「没反应」。 */
    s.inputTrusted = platformInputTrusted();
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

    // v184：客户端 reader 也要能注入 —— 手机输入面板的内嵌触控板把 0x01 鼠标帧
    // 发到这条连接上，旧 reader 没有对应分支，帧到了直接丢弃（用户看到的就是
    // "触控板划了没反应"）。服务端 Ctrl9511Server 一直有完整 dispatch，这里补齐同一套。
    std::unique_ptr<InputInjector> injector;

    // 注入计数（发送线程累加，status() 按值返回）
    std::atomic<uint64_t> aMouse_{0}, aTouch_{0}, aKeyboard_{0}, aConsumer_{0};
    std::atomic<uint64_t> aRequestInput_{0}, aInputText_{0}, aInputDone_{0};
    // 通知同步（0x40/0x42，2026-11-11）：推送/消除计数（PC 控手机时手机是服务端，从这里收）
    std::atomic<uint64_t> aNotifyPush_{0}, aNotifyDismiss_{0};
    // 心跳 RTT：-1 表示未测得；ping 发出时刻由 ping 线程记录，pong 到达时算差
    std::atomic<int64_t> rttMs_{-1};
    std::atomic<int64_t> pingSentMs_{0};
    // fix: 防止双 ping 覆盖时间戳导致 RTT 不准
    std::atomic<bool> pingPending_{false};
    // v184：连续无 pong 的轮数 —— 达到阈值即判定链路失效（半开检测）
    std::atomic<int> missedPongs_{0};
    /// 对端能力位图（0x33 能力协商，2026-11-11；位定义见 kCaps9511*，头文件）。
    /// 0 = 本会话未收到 —— 旧版对端没有该机制，能力帧族一律不发（前向兼容）。
    std::atomic<uint64_t> peerCaps_{0};

    /**
     * 发 0x33 能力协商（2026-11-11）：把本机（PC 控制端）的能力位图报给对端。
     * 命令号按字面量写（0x33），与 kCaps9511* / Android ApxFrame.CMD_CAPS_SYNC /
     * scripts/check_protocol.py 三处对拍 —— 改这里必须同步。
     */
    void sendCapsSync() {
        const uint64_t own = kCaps9511NotifySync | kCaps9511NotifyReply | kCaps9511FindPhone
                           | kCaps9511PhoneFiles | kCaps9511Album | kCaps9511AbsolutePtr;
        uint8_t body[9] = {0x33, 0, 0, 0, 0, 0, 0, 0, 0};
        for (int i = 0; i < 8; ++i) body[1 + i] = static_cast<uint8_t>((own >> (8 * i)) & 0xFF);
        sendCmd(0x33, body + 1, 8);  // sendCmd 自身会把 cmd 放 body[0]，这里只传位图 8 字节
    }

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
    im->injector = createPlatformInjector();   // 注入器建不起来时各分支会跳过注入，不影响连接
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

    // 能力协商（0x33，2026-11-11）：连接建立即把本机能力报给对端 —— 不发的话手机学到的
    // 永远是 0，能力帧族全被门掉（通知同步/查找手机整条断）。对端回的位图存 peerCaps_。
    im->sendCapsSync();

    const auto cbClip = onReverseClipboard;
    const auto cbReqInput = onRequestInput;
    const auto cbInputText = onRemoteInputText;
    const auto cbInputDone = onRemoteInputDone;
    const auto cbSpecialKey = onSpecialKey;
    const auto cbNotifyPush = onNotifyPush;
    const auto cbNotifyDismiss = onNotifyDismiss;
    const auto cbNotifyReplyResult = onNotifyReplyResult;
    const auto cbPhoneFileList = onPhoneFileList;
    const auto cbPhoneFileFail = onPhoneFileFail;
    const auto cbAlbumPage = onAlbumPage;
    const auto cbAlbumPageFail = onAlbumPageFail;
    im->readerThread = std::thread([im, cbClip, cbReqInput, cbInputText, cbInputDone, cbSpecialKey,
                                    cbNotifyPush, cbNotifyDismiss, cbNotifyReplyResult,
                                    cbPhoneFileList, cbPhoneFileFail,
                                    cbAlbumPage, cbAlbumPageFail] {
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
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 5 && payload[0] == 0x01) {
                    // v184：鼠标相对移动/按键 —— 手机输入面板内嵌触控板走这条。
                    // 旧 reader 只认 0x21/0x25/0x26/0x27/0x28，0x01/02/03/04 全部落空被丢。
                    // 第 6 字节 pan（横向滚动）可选：老对端不发 → 0，行为不变。
                    if (im->injector)
                        im->injector->injectMouse(payload[1],
                                                  static_cast<int8_t>(payload[2]),
                                                  static_cast<int8_t>(payload[3]),
                                                  static_cast<int8_t>(payload[4]),
                                                  bodyLen >= 6 ? static_cast<int8_t>(payload[5]) : 0);
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 3 && payload[0] == 0x02) {
                    if (im->injector)
                        im->injector->injectConsumer(
                            static_cast<uint16_t>(payload[1]) |
                            (static_cast<uint16_t>(payload[2]) << 8));
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 3 && payload[0] == 0x03) {
                    if (im->injector) {
                        const size_t cnt = bodyLen - 3;
                        im->injector->injectKeyboard(payload[1],
                                                     cnt > 0 ? payload + 3 : nullptr,
                                                     cnt > 6 ? 6 : cnt);
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 9 && payload[0] == 0x04) {
                    // 副屏触摸（0..65535 归一化）：injectTouch 内部按推流实际抓取的屏映射
                    if (im->injector)
                        im->injector->injectTouch(payload[1], payload[2],
                            static_cast<uint16_t>(payload[3]) | (static_cast<uint16_t>(payload[4]) << 8),
                            static_cast<uint16_t>(payload[5]) | (static_cast<uint16_t>(payload[6]) << 8));
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
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 9 && payload[0] == 0x33) {
                    // CAPS_SYNC 能力协商（2026-11-11）：对端报能力位图（u64 小端 8 字节）。
                    // 元数据落原子变量，不动注入路径；新会话由 connect() 清零重收。
                    {
                        uint64_t caps = 0;
                        for (int i = 0; i < 8; ++i)
                            caps |= static_cast<uint64_t>(payload[1 + i]) << (8 * i);
                        im->peerCaps_.store(caps);
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 7 && payload[0] == 0x40) {
                    // NOTIFY_PUSH 通知推送（2026-11-11，能力位 bit0）：受控手机推送通知 →
                    // 托盘气泡。载荷布局与 Android ApxFrame.packNotifyPush 对拍（同服务端 dispatch）：
                    //   [0x40][keyLen u16 LE][key][titleLen u16 LE][title][textLen u16 LE][text][appLen u8][app]
                    {
                        const uint16_t keyLen = static_cast<uint16_t>(payload[1]) | (static_cast<uint16_t>(payload[2]) << 8);
                        uint32_t off = 3;
                        if (off + keyLen + 2 <= bodyLen) {
                            const std::string key(reinterpret_cast<const char*>(payload + off), keyLen);
                            off += keyLen;
                            const uint16_t titleLen = static_cast<uint16_t>(payload[off]) | (static_cast<uint16_t>(payload[off + 1]) << 8);
                            off += 2;
                            if (off + titleLen + 2 <= bodyLen) {
                                const std::string title(reinterpret_cast<const char*>(payload + off), titleLen);
                                off += titleLen;
                                const uint16_t textLen = static_cast<uint16_t>(payload[off]) | (static_cast<uint16_t>(payload[off + 1]) << 8);
                                off += 2;
                                if (off + textLen + 1 <= bodyLen) {
                                    const std::string text(reinterpret_cast<const char*>(payload + off), textLen);
                                    off += textLen;
                                    const uint8_t appLen = payload[off];
                                    off += 1;
                                    if (off + appLen <= bodyLen) {
                                        const std::string app(reinterpret_cast<const char*>(payload + off), appLen);
                                        if (cbNotifyPush) cbNotifyPush(key, title, text, app);
                                        im->aNotifyPush_.fetch_add(1);
                                    }
                                }
                            }
                        }
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 3 && payload[0] == 0x42) {
                    // NOTIFY_DISMISS 通知消除（与 0x40 配对）：用户在手机上划掉/点开通知。
                    // 当前仅回调记账（气泡无法撤回），为后续 PC 端通知中心留状态。
                    {
                        const uint16_t keyLen = static_cast<uint16_t>(payload[1]) | (static_cast<uint16_t>(payload[2]) << 8);
                        if (3 + keyLen <= bodyLen) {
                            const std::string key(reinterpret_cast<const char*>(payload + 3), keyLen);
                            if (cbNotifyDismiss) cbNotifyDismiss(key);
                            im->aNotifyDismiss_.fetch_add(1);
                        }
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 2 && payload[0] == 0x43) {
                    // NOTIFY_REPLY_RESULT 通知回复回执（2026-11-14，能力位 bit1）：受控手机
                    // 回 0x41 的终态——成功（err=0）也回帧，用户在等。err 语义与 Android
                    // ApxFrame.NOTIFY_REPLY_ERR_* 一致，接线方转气泡文案。
                    if (cbNotifyReplyResult) cbNotifyReplyResult(payload[1]);
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 4 && payload[0] == 0x45) {
                    // PHONE_FILE_LIST_REPLY 目录应答（2026-11-11，能力位 bit3）：受控手机回报
                    // 目录内容。载荷与服务端 dispatch 同款（与 ApxFrame.packPhoneFileListReply
                    // 对拍）；解析失败整帧丢弃不回调，与 0x40 同口径。
                    {
                        const uint8_t err = payload[1];
                        const uint16_t pathLen = static_cast<uint16_t>(payload[2]) | (static_cast<uint16_t>(payload[3]) << 8);
                        uint32_t off = 4;
                        if (off + pathLen + 2 <= bodyLen) {
                            const std::string path(reinterpret_cast<const char*>(payload + off), pathLen);
                            off += pathLen;
                            const uint16_t count = static_cast<uint16_t>(payload[off]) | (static_cast<uint16_t>(payload[off + 1]) << 8);
                            off += 2;
                            std::vector<PhoneFileEntry> entries;
                            entries.reserve(count < 200 ? count : 200);   // 协议上限 200
                            for (int i = 0; i < count && off + 2 <= bodyLen; ++i) {
                                const uint16_t nLen = static_cast<uint16_t>(payload[off]) | (static_cast<uint16_t>(payload[off + 1]) << 8);
                                off += 2;
                                if (off + nLen + 9 > bodyLen) break;      // 条目残缺：到手的先交
                                PhoneFileEntry e;
                                e.name.assign(reinterpret_cast<const char*>(payload + off), nLen);
                                off += nLen;
                                e.isDir = payload[off] != 0;
                                off += 1;
                                for (int b = 0; b < 8; ++b)
                                    e.size |= static_cast<uint64_t>(payload[off + b]) << (8 * b);
                                off += 8;
                                entries.push_back(std::move(e));
                            }
                            if (cbPhoneFileList) cbPhoneFileList(err, path, entries);
                        }
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 8 && payload[0] == 0x49) {
                    // ALBUM_PAGE_REPLY 相册分页应答（2026-11-12，能力位 bit4，与 0x48 配对）：
                    // 受控手机回报一页相册条目。载荷与服务端 dispatch 同款（与
                    // ApxFrame.packAlbumPageReply 对拍）；解析失败整帧丢弃不回调，与 0x45 同口径。
                    {
                        const uint8_t err = payload[1];
                        uint32_t page = 0;
                        for (int b = 0; b < 4; ++b)
                            page |= static_cast<uint32_t>(payload[2 + b]) << (8 * b);
                        const uint16_t count = static_cast<uint16_t>(payload[6]) | (static_cast<uint16_t>(payload[7]) << 8);
                        uint32_t off = 8;
                        std::vector<AlbumEntry> entries;
                        entries.reserve(count < 60 ? count : 60);   // 协议上限 60（ALBUM_PAGE_SIZE）
                        for (int i = 0; i < count && off + 12 <= bodyLen; ++i) {
                            AlbumEntry e;
                            for (int b = 0; b < 4; ++b)
                                e.id |= static_cast<uint32_t>(payload[off + b]) << (8 * b);
                            off += 4;
                            for (int b = 0; b < 4; ++b)
                                e.added |= static_cast<uint32_t>(payload[off + b]) << (8 * b);
                            off += 4;
                            for (int b = 0; b < 8; ++b)
                                e.size |= static_cast<uint64_t>(payload[off + b]) << (8 * b);
                            off += 8;
                            const uint16_t pLen = static_cast<uint16_t>(payload[off]) | (static_cast<uint16_t>(payload[off + 1]) << 8);
                            off += 2;
                            if (off + pLen > bodyLen) break;        // 条目残缺：到手的先交
                            e.path.assign(reinterpret_cast<const char*>(payload + off), pLen);
                            off += pLen;
                            if (off + 2 > bodyLen) break;
                            const uint16_t nLen = static_cast<uint16_t>(payload[off]) | (static_cast<uint16_t>(payload[off + 1]) << 8);
                            off += 2;
                            if (off + nLen > bodyLen) break;
                            e.name.assign(reinterpret_cast<const char*>(payload + off), nLen);
                            off += nLen;
                            entries.push_back(std::move(e));
                        }
                        if (cbAlbumPage) cbAlbumPage(err, page, entries);
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 4 && payload[0] == 0x47) {
                    // PHONE_FILE_FAIL 取文件失败回执（与 0x46/0x4B 配对）：文件/缩略图本体没推
                    // 回来时的替代信号。path 以 "album:thumb:" 开头的是相册缩略图失败（0x4B 配对），
                    // 其余是手机文件失败（0x46 配对）—— 回执同帧复用，按前缀分流。
                    {
                        const uint8_t err = payload[1];
                        const uint16_t pathLen = static_cast<uint16_t>(payload[2]) | (static_cast<uint16_t>(payload[3]) << 8);
                        if (4 + pathLen <= bodyLen) {
                            const std::string path(reinterpret_cast<const char*>(payload + 4), pathLen);
                            static const char kAlbumThumbPrefix[] = "album:thumb:";
                            if (path.rfind(kAlbumThumbPrefix, 0) == 0) {
                                if (cbAlbumPageFail) cbAlbumPageFail(err, path);
                            } else {
                                if (cbPhoneFileFail) cbPhoneFileFail(err, path);
                            }
                        }
                    }
                } else if (h.streamId == apx::kStreamControl && bodyLen >= 1 && payload[0] == 'p') {
                    // pong：清除 pending 标志，记录 RTT
                    im->pingPending_.store(false);
                    const int missedBefore = im->missedPongs_.exchange(0);   // v184：连续无 pong 计数清零
                    // 2026-11-12 体积/日志优化轮：pong 每秒一发，旧实现无条件记 INFO ——
                    // 测试者 11-11 一天的日志导出里这一条就有 6.4 万行（9.4MB 大头），
                    // 全是重复噪声。改为**状态边沿才记**：健康链路全程静默；
                    // RTT 采集不变，仍然每发都算。
                    // 2026-11-14 再收紧（issue#170 实测：一天 4.7 万条「心跳恢复」）——
                    // 断档 1 轮是常规网络抖动（2s 超时贴着抖动量级），补一条恢复日志
                    // 反而把 INFO 刷成每秒一条的另一种噪声。只记**连断 ≥2 轮**后的
                    // 恢复：既有排障价值（真断线必连丢多轮），又不掺抖动。
                    if (missedBefore >= 2) APX_LOGI("9511 收到 pong（心跳恢复，此前断档 {} 轮）", missedBefore);
                    const auto sent = im->pingSentMs_.load();
                    if (sent > 0) im->rttMs_.store(im->nowMs() - sent);
                }
                off += total;
            }
            if (off > 0) acc.erase(acc.begin(), acc.begin() + static_cast<long>(off));
        }
        // ★ 以前这里**无锁关闭且不置 kBadSock**：心跳判定链路失效后上层会立刻重连，
        //   而旧 reader 线程延迟执行 closeSock(sock) —— Windows 的 SOCKET 值已被新建
        //   连接复用，于是"刚连上就被旧线程关掉"，表现为反复重连 / 刚连即断；
        //   disconnect() 随后还会对同一个句柄再关一次（double close，同样可能误伤
        //   被复用的新句柄）。现在两处收尾统一在锁内关闭并置空，不会互相踩。
        {
            std::lock_guard<std::mutex> lk(im->mu);
            if (im->sock != kBadSock) { closeSock(im->sock); im->sock = kBadSock; }
        }
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
        // 断开时释放对端按住的键，避免"鼠标左键卡死"（与服务端 releaseAllInputs 同口径）
        if (raw->injector) {
            raw->injector->injectMouse(0, 0, 0, 0);
            raw->injector->injectConsumer(0);
            raw->injector->injectKeyboard(0, nullptr, 0);
        }
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
    // ★ 原来是 uint8_t b[1 + 255]（256 字节），而 memcpy(b + 2, ..., 255) 会写到 b[256]
    //   —— 越界整整一个字节（hint 被对端截断到 255 字节时必然踩中），sendCmd 也从 b[256]
    //   越界读。b[0] = 0x25 那行同样是无效写入（sendCmd 从 b + 1 取数据）。
    uint8_t b[2 + 255] = {0};
    b[1] = static_cast<uint8_t>(bytes.size());
    std::memcpy(b + 2, bytes.data(), bytes.size());
    impl_->sendCmd(0x25, b + 1, 1 + bytes.size());
    return true;
}

bool Ctrl9511Client::sendInputText(const std::string& text, uint8_t flags) {
    if (!impl_) return false;
    // ★ 空文本**不能一律拒**：0x02（退格）帧的语义就是「文本为空，删掉一个字」——
    //   remote_input_win32.cpp 正是用 sendFrame(std::string(), 0x02) 表达退格，
    //   接收侧 wireless_session.cpp 也明确支持 flags & 0x02 的空文本帧。
    //   以前这里把退格帧整条吞掉：PC 上每按一次退格都返回 false 并弹"没发出去"，
    //   对端一个字也删不掉。
    if (text.empty() && !(flags & 0x02)) return false;
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

// 查找手机（0x38，2026-11-11，能力位 bit2）：让受控手机响铃。
// 能力门控：手机没报过 0x33（peerCaps_=0，旧版对端）时不拦 —— 旧版对 0x38 静默忽略，
// 行为无害；报过且不含该位才明确跳过（对端明确说了不支持，发了也是白响不响）。
bool Ctrl9511Client::sendFindPhone(uint8_t action) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511FindPhone) == 0) return false;
    impl_->sendCmd(0x38, &action, 1);
    return true;
}

// 手机文件浏览发送（0x44/0x46，2026-11-11，能力位 bit3）：门控与 sendFindPhone 同口径 ——
// 对端报过 0x33 且缺 kCaps9511PhoneFiles 才明确跳过；没报（旧版）照发，静默忽略无害。
// body=[pathLen u16 LE][path]（与 ApxFrame.packPhoneFileListReq/PullReq 对拍）；
// sendCmd 会把 cmd 放帧头第 1 字节，这里只传 cmd 之后的内容。
bool Ctrl9511Client::sendPhoneFileListReq(const std::string& path) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511PhoneFiles) == 0) return false;
    const auto p = clampPhoneFilePath(path);
    std::vector<uint8_t> body(2 + p.size());
    body[0] = static_cast<uint8_t>(p.size() & 0xFF);
    body[1] = static_cast<uint8_t>((p.size() >> 8) & 0xFF);
    std::memcpy(body.data() + 2, p.data(), p.size());
    impl_->sendCmd(0x44, body.data(), body.size());
    return true;
}

bool Ctrl9511Client::sendPhoneFilePullReq(const std::string& path) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511PhoneFiles) == 0) return false;
    const auto p = clampPhoneFilePath(path);
    std::vector<uint8_t> body(2 + p.size());
    body[0] = static_cast<uint8_t>(p.size() & 0xFF);
    body[1] = static_cast<uint8_t>((p.size() >> 8) & 0xFF);
    std::memcpy(body.data() + 2, p.data(), p.size());
    impl_->sendCmd(0x46, body.data(), body.size());
    return true;
}

// 相册投影发送（0x48/0x4A/0x4B，2026-11-12，能力位 bit4）：门控与文件浏览同口径 ——
// 对端报过 0x33 且缺 kCaps9511Album 才明确跳过；没报（旧版）照发，静默忽略无害。
// 载荷与 Android ApxFrame.packAlbumPageReq/WatchReq/ThumbReq 对拍（小端逐字节）。
bool Ctrl9511Client::sendAlbumPageReq(uint32_t page) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511Album) == 0) return false;
    uint8_t body[4] = { static_cast<uint8_t>(page & 0xFF),
                        static_cast<uint8_t>((page >> 8) & 0xFF),
                        static_cast<uint8_t>((page >> 16) & 0xFF),
                        static_cast<uint8_t>((page >> 24) & 0xFF) };
    impl_->sendCmd(0x48, body, sizeof(body));
    return true;
}

bool Ctrl9511Client::sendAlbumWatchReq(bool on) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511Album) == 0) return false;
    const uint8_t body = on ? 1 : 0;
    impl_->sendCmd(0x4A, &body, 1);
    return true;
}

bool Ctrl9511Client::sendAlbumThumbReq(uint32_t id) {
    if (!impl_) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511Album) == 0) return false;
    uint8_t body[4] = { static_cast<uint8_t>(id & 0xFF),
                        static_cast<uint8_t>((id >> 8) & 0xFF),
                        static_cast<uint8_t>((id >> 16) & 0xFF),
                        static_cast<uint8_t>((id >> 24) & 0xFF) };
    impl_->sendCmd(0x4B, body, sizeof(body));
    return true;
}

// 通知回复发送（0x41，2026-11-14，能力位 bit1）：PC 控手机的链路上，托盘点气泡
// 「回复」让受控手机代发消息。载荷与门控同 Ctrl9511Server::sendNotifyReply（对拍
// Android ApxFrame.parseNotifyReply；key ≤512B / text ≤1KB 超长按字符边界截断）。
// 手机回 0x43 终态走 onNotifyReplyResult 回调（成功也回帧）。
bool Ctrl9511Client::sendNotifyReply(const std::string& key, const std::string& text) {
    if (!impl_ || key.empty()) return false;
    const uint64_t caps = impl_->peerCaps_.load();
    if (caps != 0 && (caps & kCaps9511NotifyReply) == 0) return false;
    const auto k = clampUtf8Reply(key, 512);
    const auto t = clampUtf8Reply(text, 1024);
    std::vector<uint8_t> body(2 + k.size() + 2 + t.size());
    size_t off = 0;
    body[off++] = static_cast<uint8_t>(k.size() & 0xFF);
    body[off++] = static_cast<uint8_t>((k.size() >> 8) & 0xFF);
    std::memcpy(body.data() + off, k.data(), k.size()); off += k.size();
    body[off++] = static_cast<uint8_t>(t.size() & 0xFF);
    body[off++] = static_cast<uint8_t>((t.size() >> 8) & 0xFF);
    std::memcpy(body.data() + off, t.data(), t.size());
    impl_->sendCmd(0x41, body.data(), body.size());
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
        s.peerCaps = impl_->peerCaps_.load();   // 能力协商（0x33）：对端能力位图
        s.notifyPush = impl_->aNotifyPush_.load();       // 通知同步（0x40/0x42）计数
        s.notifyDismiss = impl_->aNotifyDismiss_.load();
    }
    return s;
}

}  // namespace apxpc::wireless
