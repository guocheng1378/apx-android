#include "apxpc/media/media_session.hpp"

#include "apxpc/log.hpp"
#include "apxpc/media/touch_inject.hpp"
// R2 通道加密（9502 子批）：会话封装与 R2 链路上下文在 wireless 命名空间
#include "apxpc/wireless/noise_session.hpp"

#include <apx/frame.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <memory>
#include <string>
#include <thread>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <cerrno>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#endif

namespace apxpc::media {
namespace {

using Clock = std::chrono::steady_clock;

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               Clock::now().time_since_epoch())
        .count();
}

#if defined(_WIN32)
// 进程级 WSAStartup（只做一次；与 wireless_link.cpp 各自持有一份 static，无副作用）
void ensureWsa() {
    static bool once = [] {
        WSADATA d{};
        return WSAStartup(MAKEWORD(2, 2), &d) == 0;
    }();
    (void)once;
}

constexpr SOCKET kInvalidSock = INVALID_SOCKET;
inline void closeSock(SOCKET s) { closesocket(s); }
/// socket 层错误码：connect/reader/sendAllBlocking 的日志与重试判定统一走这里
inline int sockErr() { return WSAGetLastError(); }
#else
constexpr int kInvalidSock = -1;
inline void closeSock(int s) { ::close(s); }
inline int sockErr() { return errno; }
#endif

/// 组一个 APX1 帧。
/// **CRC 覆盖范围 = 16 字节帧头之后的字节，不含尾部那 4 字节** —— 这是协议权威定义
/// （docs/PROTOCOL.md §3）。此处踩过坑：pc/display 的 validateFrame 曾按「整帧含帧头」
/// 校验且把扩展头算两次，导致每一帧都被判坏帧，详见 ROADMAP 的踩坑记录。
std::vector<uint8_t> buildFrame(uint8_t streamId, const uint8_t* body, size_t len,
                                uint32_t seq, uint8_t flags) {
    apx::ApxFrameHeader h{};
    std::memcpy(h.magic, "APX1", 4);
    h.streamId = streamId;
    h.flags = flags;
    h.headerExtWords = 0;
    h.payloadLen = static_cast<uint32_t>(len + apx::kFrameCrcSize);
    h.seq = seq;

    std::vector<uint8_t> f(apx::kFrameHeaderSize + h.payloadLen);
    if (!apx::writeHeader(f.data(), f.size(), h)) return {};
    if (len) std::memcpy(f.data() + apx::kFrameHeaderSize, body, len);
    apx::putU32(f.data() + apx::kFrameHeaderSize + len, apx::crc32(body, len));
    return f;
}

void bump(size_t len, std::atomic<uint64_t>& frames, std::atomic<uint64_t>& bytes) {
    frames.fetch_add(1, std::memory_order_relaxed);
    bytes.fetch_add(len, std::memory_order_relaxed);
}

// ----------------------- R2 通道加密工具（9502 子批） -----------------------

void putU32Le(uint8_t* dst, uint32_t v) {
    dst[0] = static_cast<uint8_t>(v & 0xFF);
    dst[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    dst[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    dst[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

/// 读满 n 字节（R2 握手用；socket 已设临时收流超时，卡住会超时返回 false）
bool readFully(int s, uint8_t* dst, size_t n) {
    size_t got = 0;
    while (got < n) {
        const int r = ::recv(s, reinterpret_cast<char*>(dst + got),
                             static_cast<int>(n - got), 0);
        if (r <= 0) return false;
        got += static_cast<size_t>(r);
    }
    return true;
}

bool readU32(int s, uint32_t& v) {
    uint8_t b[4];
    if (!readFully(s, b, 4)) return false;
    v = static_cast<uint32_t>(b[0]) | (static_cast<uint32_t>(b[1]) << 8) |
        (static_cast<uint32_t>(b[2]) << 16) | (static_cast<uint32_t>(b[3]) << 24);
    return true;
}

/// R2 加密一帧（writerLoop 线程，仅 noiseActive 时进入）：payload 整段（body + CRC 尾）
/// AEAD，帧头 16 字节保持明文、payloadLen（偏移 8）重写为密文长。
/// 失败返回 false（调用方断链 —— AEAD nonce 已前进，明文帧补发对不上对端解密序）。
bool encryptFrame(std::vector<uint8_t>& f, wireless::NoiseSession& sess) {
    if (f.size() <= apx::kFrameHeaderSize) return false;
    const size_t payloadLen = f.size() - apx::kFrameHeaderSize;   // 含 CRC 尾
    if (payloadLen > wireless::kNoiseMaxPlain) return false;
    std::vector<uint8_t> ct(f.begin() + static_cast<std::ptrdiff_t>(apx::kFrameHeaderSize),
                            f.end());
    const size_t ctLen = sess.encrypt(ct.data(), payloadLen, ct.size());
    if (ctLen != payloadLen + 16) return false;
    f.resize(apx::kFrameHeaderSize);
    f.insert(f.end(), ct.begin(),
             ct.begin() + static_cast<std::ptrdiff_t>(ctLen));
    putU32Le(f.data() + 8, static_cast<uint32_t>(ctLen));
    return true;
}

}  // namespace

MediaSession::~MediaSession() { disconnect(); }

// ------------------------------------------------------------------ 建链 ----

bool MediaSession::connect(const std::string& host, uint16_t port, const std::string& token) {
    // 生命周期串行化：见 hpp lifecycleMu_ 注释。disconnect() 会递归取同一把锁。
    std::lock_guard<std::recursive_mutex> lkLife(lifecycleMu_);
    // 重连前先收掉旧连接：必须在**取锁之前**调用 —— disconnect() 自己要锁 mu_，
    // 持锁调用会直接死锁（与 wireless_link.cpp 同一处坑）。
    disconnect();
    std::lock_guard<std::mutex> lk(mu_);
    // mac-port：原非 Win 分支在这里返回「媒体通道目前仅实现 Windows 宿主」。
    // 现跨平台化 —— 差异点只剩 5 处小 #if（WSA/非阻塞/超时结构/错误码/关闭），
    // 骨架与 Win 版同一份代码，行为语义逐行对齐（握手、缓冲策略、失败文案均不变）。
#if defined(_WIN32)
    ensureWsa();
#endif
    token_ = token;
    st_.peer = host + ":" + std::to_string(static_cast<unsigned>(port));

    // —— R2 门控（9502 子批，2026-11-17）：链路令牌（r2Token，9511 握手成功时挂入）
    //    与对端静态公钥（TOFU）都齐才尝试加密；任一缺失 → 纯明文
    //    （与 Android MediaChannel.probePending 同口径）。
    uint8_t r2Peer[wireless::kNoiseStaticKeyLen];
    const bool r2Capable = !wireless::r2Token().empty() &&
                           wireless::r2PeerStaticKey(r2Peer);

    // 建链 + 令牌握手抽成可复用步骤：R2 握手未成（legacy 对端不会回应）时要关掉
    // 当前 socket 重连一次明文，避免把建链代码复制两份。失败时已写好 st_.error。
    const auto establish = [&](Sock& sOut) -> bool {
        addrinfo hints{};
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* res = nullptr;
        char portStr[8]{};
        std::snprintf(portStr, sizeof(portStr), "%u", static_cast<unsigned>(port));
        if (getaddrinfo(host.c_str(), portStr, &hints, &res) != 0 || !res) {
            st_.error = "地址解析失败：" + host;
            APX_LOGW("媒体连接失败：{}", st_.error);
            return false;
        }

        auto s = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
        if (s == kInvalidSock) {
            freeaddrinfo(res);
            st_.error = "socket 创建失败";
            return false;
        }

        // 非阻塞 connect + select 3s 超时
#if defined(_WIN32)
        u_long nb = 1;
        ioctlsocket(s, FIONBIO, &nb);
#else
        const int fl = ::fcntl(s, F_GETFL, 0);
        ::fcntl(s, F_SETFL, fl | O_NONBLOCK);
#endif
        const int rc = ::connect(s, res->ai_addr, static_cast<int>(res->ai_addrlen));
        freeaddrinfo(res);
        if (rc != 0) {
            fd_set w;
            FD_ZERO(&w);
            FD_SET(s, &w);
            timeval tv{3, 0};
            // nfds：Win 上被忽略、POSIX 必需 —— 统一传 s+1 两边合法（原 Win 版传 0，等价）
            if (select(static_cast<int>(s + 1), nullptr, &w, nullptr, &tv) <= 0) {
                closeSock(s);
                st_.error = "媒体连接超时（3s）：" + st_.peer;
                APX_LOGW("媒体连接失败：{}", st_.error);
                return false;
            }
        }
#if defined(_WIN32)
        nb = 0;
        ioctlsocket(s, FIONBIO, &nb);
#else
        ::fcntl(s, F_SETFL, fl & ~O_NONBLOCK);
#endif

        // 低延迟：大流量流最怕 Nagle 把小帧攒成 200ms 一发（两边同为 int 1）
        int nodelay = 1;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay),
                   sizeof(nodelay));
        // ★ 发送缓冲**不能开大**：512KB/1MB 这种"看起来更抗抖动"的缓冲，实际是
        //   **延迟蓄水池** —— 发送成功 ≠ 对方收到，堆在核缓冲里的数据会变成几秒的滞后，
        //   而且越积越多（表现就是"副屏越看越卡、越看越不同步"）。
        //   同仓库 tcp_transport_win.cpp 早就踩过并写了这条结论，副屏这条链路当时漏了。
        //   现在对齐那条策略：64KB 缓冲 + 50ms 发送超时（拥塞时立即失败丢帧，保住时效）。
        int sndBuf = 64 * 1024;
        setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sndBuf),
                   sizeof(sndBuf));
#if defined(_WIN32)
        DWORD sndTimeout = 50;   // ms
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sndTimeout),
                   sizeof(sndTimeout));
#else
        // macOS/BSD：SO_SNDTIMEO 收 timeval（Win 收毫秒 DWORD）。不支持时此调用失败可忽略 ——
        // 发送侧还有 sendAllBlocking 的 5s 拥塞重试兕底，语义不丢。
        timeval sndTimeout{0, 50 * 1000};   // 50ms
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sndTimeout),
                   sizeof(sndTimeout));
#endif

        // 令牌握手：u32 LE 长度 + UTF-8 令牌（**无回执字节**，与 Android 侧对称）
        const uint32_t len = static_cast<uint32_t>(token_.size());
        const uint8_t hdr[4] = {
            static_cast<uint8_t>(len & 0xFF), static_cast<uint8_t>((len >> 8) & 0xFF),
            static_cast<uint8_t>((len >> 16) & 0xFF), static_cast<uint8_t>((len >> 24) & 0xFF),
        };
        if (::send(s, reinterpret_cast<const char*>(hdr), 4, 0) != 4 ||
            (len > 0 && ::send(s, token_.data(), static_cast<int>(len), 0) !=
                            static_cast<int>(len))) {
            closeSock(s);
            st_.connected = false;
            st_.error = "媒体令牌握手发送失败";
            return false;
        }

        sOut = s;
        return true;
    };

    Sock s = kInvalidSock;
    for (int attempt = 0; attempt < 2; ++attempt) {
        if (!establish(s)) return false;
        // R2 尝试（仅首轮，9502 子批）：令牌握手后紧接加密握手
        // [u32 0][u32 len1][msg1]。legacy 对端（旧版手机/TV）不认识首 u32=0，
        // 不会回应 —— 靠 5s 收流超时判定后关掉重连一次明文，老版本两端不受影响。
        if (r2Capable && attempt == 0 && !r2Handshake(s)) {
            closeSock(s);
            APX_LOGI("9502 R2 握手未完成，回退明文重连");
            continue;
        }
        break;
    }

    sock_ = s;
    upSinceMs_ = nowMs();
    {
        std::lock_guard<std::mutex> qlk(qmu_);
        outQ_.clear();
    }
    // 每次新连接都从 0 起计数：面板上的数字要能对应当前这次会话
    cVideoFrames_ = 0; cVideoBytes_ = 0;
    cAudioFrames_ = 0; cAudioBytes_ = 0;
    cMicFrames_ = 0;   cMicBytes_ = 0;
    cCameraFrames_ = 0; cCameraBytes_ = 0;

    cTouchFrames_ = 0;  cTouchBytes_ = 0;
    cDropped_ = 0;     cResync_ = 0;

    running_.store(true);
    st_.connected = true;
    st_.error.clear();
    st_.upMs = 0;
    seq_ = 0;

    reader_ = std::thread([this] { readerLoop(); });
    writer_ = std::thread([this] { writerLoop(); });

    APX_LOGI("媒体通道已连接 {}", st_.peer);
    return true;
}

// -------------------------------------------------------------- R2 握手 ----

/// R2 握手（9502 Initiator 侧，9512/9502 子批 2026-11-17）：令牌握手后紧接
/// [u32 0][u32 len1][msg1]，读对端 [u32 len2][msg2]，split 成功后写入 noiseSess_
/// 并置 noiseActive_。收流用临时 5s 超时 —— legacy 对端不会回应 R2，靠超时判定。
/// 令牌取 r2Token()：9502 是 PC 连入手机服务端的出站通道，PSK 源即 9511 链路的
/// 被控端令牌（PC 自身配对令牌只在入站通道 file_receiver 的候选里）。
/// 失败返回 false（调用方关 socket 重连一次明文）。
bool MediaSession::r2Handshake(MediaSock s) {
    const std::string tok = wireless::r2Token();
    if (tok.empty()) return false;
    uint8_t ownPriv[wireless::kNoiseStaticKeyLen];
    uint8_t ownPub[wireless::kNoiseStaticKeyLen];
    if (!wireless::ensureNoiseKeyPair(ownPriv, ownPub)) return false;
    uint8_t peerPub[wireless::kNoiseStaticKeyLen];
    if (!wireless::r2PeerStaticKey(peerPub)) return false;

    auto sess = std::make_shared<wireless::NoiseSession>(wireless::kNoisePrologue9502);
    if (!sess->beginInitiator(reinterpret_cast<const uint8_t*>(tok.data()), tok.size(),
                              peerPub, ownPriv))
        return false;
    uint8_t msg1[256];   // IK msg1 实际 ≤ 96B，留余量
    const int n1 = sess->writeHandshakeMsg(msg1, sizeof(msg1));
    if (n1 <= 0) return false;

    // [u32 0][u32 n1][msg1]：首 u32=0 是 R2 标记（APX1 帧首 u32 恒非 0，两端无歧义）
    const uint8_t zero[4] = {0, 0, 0, 0};
    uint8_t l1[4];
    putU32Le(l1, static_cast<uint32_t>(n1));
    if (::send(s, reinterpret_cast<const char*>(zero), 4, 0) != 4 ||
        ::send(s, reinterpret_cast<const char*>(l1), 4, 0) != 4 ||
        ::send(s, reinterpret_cast<const char*>(msg1), n1, 0) != n1)
        return false;

    // 临时 5s 收流超时等 msg2（legacy 对端不回应 → 超时 → 明文重连）
#if defined(_WIN32)
    DWORD rto = 5000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rto),
               sizeof(rto));
#else
    timeval rto{5, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &rto, sizeof(rto));
#endif
    uint32_t len2 = 0;
    if (!readU32(s, len2) || len2 == 0 || len2 > 1024) return false;
    std::vector<uint8_t> msg2(len2);
    if (!readFully(s, msg2.data(), len2)) return false;
    if (sess->readHandshakeMsg(msg2.data(), len2) != 0) return false;
    if (!sess->split()) return false;

    // 成功：恢复无收流超时（reader 靠 running_ + shutdown 退出，残留 5s 超时会把
    // 空闲链路误杀），再置加密态
#if defined(_WIN32)
    DWORD rto0 = 0;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rto0),
               sizeof(rto0));
#else
    timeval rto0{0, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &rto0, sizeof(rto0));
#endif
    noiseSess_ = sess;
    noiseActive_.store(true, std::memory_order_release);
    APX_LOGI("9502 R2 通道加密已启用");
    return true;
}

void MediaSession::disconnect() {
    // 与 connect 互斥：reader_/writer_ 的 join/赋值绝不能并发（见 hpp lifecycleMu_）
    std::lock_guard<std::recursive_mutex> lkLife(lifecycleMu_);
    const bool wasRunning = running_.exchange(false);
    const bool hadSock = (sock_ != kInvalidSock);
    if (!wasRunning && !hadSock) {
        // 收流线程自己退出过（TCP 断 → readerLoop 调 teardown）：这里**必须**回收
        // reader_/writer_ 对象 —— 线程虽死但对象仍 joinable，漏了 join 的话，
        // 下次 connect 对其赋值会触发 std::terminate（"莫名退出"的真凶）。
        // 线程已死，join 立即返回，不会阻塞。
        if (reader_.joinable()) reader_.join();
        if (writer_.joinable()) writer_.join();
        std::lock_guard<std::mutex> lk(mu_);
        st_.connected = false;
        return;
    }
    // 先关 socket / 置 running_ 并唤醒 writer，收流线程才会从 recv 上退出来
    teardown();
    // 关键：只有**非本线程**才 join。reader/writer 自身走的是 teardown()，
    // 不会走到这里；但留一道保险，避免将来有人从回调里调 disconnect() 时自死锁。
    if (reader_.joinable() && reader_.get_id() != std::this_thread::get_id()) reader_.join();
    if (writer_.joinable() && writer_.get_id() != std::this_thread::get_id()) writer_.join();
    std::lock_guard<std::mutex> lk(mu_);
    st_.connected = false;
}

// ------------------------------------------------------------------ 状态 ----

MediaSession::Status MediaSession::status() const {
    std::lock_guard<std::mutex> lk(mu_);
    Status s = st_;
    if (s.connected && upSinceMs_ > 0) s.upMs = nowMs() - upSinceMs_;
    return s;
}

MediaSession::Counters MediaSession::counters() const {
    Counters c;
    c.videoFrames = cVideoFrames_.load(std::memory_order_relaxed);
    c.videoBytes = cVideoBytes_.load(std::memory_order_relaxed);
    c.audioFrames = cAudioFrames_.load(std::memory_order_relaxed);
    c.audioBytes = cAudioBytes_.load(std::memory_order_relaxed);
    c.micFrames = cMicFrames_.load(std::memory_order_relaxed);
    c.micBytes = cMicBytes_.load(std::memory_order_relaxed);
    c.cameraFrames = cCameraFrames_.load(std::memory_order_relaxed);
    c.cameraBytes = cCameraBytes_.load(std::memory_order_relaxed);

    c.touchFrames = cTouchFrames_.load(std::memory_order_relaxed);
    c.touchBytes = cTouchBytes_.load(std::memory_order_relaxed);
    c.dropped = cDropped_.load(std::memory_order_relaxed);
    c.resync = cResync_.load(std::memory_order_relaxed);
    c.sendTimeouts = cSendTimeouts_.load(std::memory_order_relaxed);
    return c;
}

// ------------------------------------------------------------------ 收流 ----

// v184 debug：断链原因落盘（复现定位后可移除）
static void mediaLog(const char* why) {
#if defined(_WIN32)
    char path[MAX_PATH]; path[0] = 0;
    const char* lad = std::getenv("LOCALAPPDATA");
    if (lad) _snprintf_s(path, sizeof(path), _TRUNCATE, "%s\\AllPeriph\\media_debug.log", lad);
    else     _snprintf_s(path, sizeof(path), _TRUNCATE, "media_debug.log");
    FILE* f = nullptr;
    if (0 == fopen_s(&f, path, "a") && f) {
        SYSTEMTIME st; GetLocalTime(&st);
        fprintf(f, "[%02d:%02d:%02d.%03d T%lu] %s\n",
                st.wHour, st.wMinute, st.wSecond, st.wMilliseconds,
                GetCurrentThreadId(), why);
        fclose(f);
    }
#else
    // POSIX（macOS/Linux）：同名日志落到 HOME 下，格式与 Windows 版对齐
    char path[600];
    const char* home = std::getenv("HOME");
    std::snprintf(path, sizeof(path), "%s/media_debug.log", home ? home : ".");
    FILE* f = std::fopen(path, "a");
    if (f) {
        const auto now = std::chrono::system_clock::now();
        const std::time_t tt = std::chrono::system_clock::to_time_t(now);
        std::tm tmv{};
        ::localtime_r(&tt, &tmv);
        const int ms = static_cast<int>(
            std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch())
                .count() % 1000);
        std::fprintf(f, "[%02d:%02d:%02d.%03d] %s\n",
                     tmv.tm_hour, tmv.tm_min, tmv.tm_sec, ms, why);
        std::fclose(f);
    }
#endif
}


void MediaSession::readerLoop() {
    // mac-port：原循环体整个圈在 #if _WIN32 内（非 Win 直接跳过收流）。循环体本身
    // 差异只有 recv 签名与错误码 —— 统一后跨平台跑（recv 返回值两边都用 int 接，
    // 64KB 缓冲内无截断风险；-Wall/-Wextra 不含 -Wconversion，无收窄告警）。
    std::vector<uint8_t> buf(64 * 1024);
    std::vector<uint8_t> acc;
    acc.reserve(1 << 20);
    while (running_.load()) {
        // R2 加密开关快照（9502 子批）：单 reader 线程内一轮循环内不会翻转
        const bool decActive = noiseActive_.load(std::memory_order_acquire);
        const int n = static_cast<int>(::recv(sock_, reinterpret_cast<char*>(buf.data()),
                                              static_cast<int>(buf.size()), 0));
        if (n <= 0) {
            mediaLog((std::string("reader exit n=") + std::to_string(n) +
                      " err=" + std::to_string(sockErr())).c_str());
            break;
        }
        acc.insert(acc.end(), buf.data(), buf.data() + n);

        size_t off = 0;
        while (acc.size() - off >= apx::kFrameHeaderSize) {
            uint8_t* p = acc.data() + off;
            if (std::memcmp(p, "APX1", 4) != 0) {
                ++off;   // 逐字节重同步（TCP 可靠有序，正常情况下不该发生）
                cResync_.fetch_add(1, std::memory_order_relaxed);
                continue;
            }
            apx::ApxFrameHeader h{};
            std::memcpy(&h, p, sizeof h);
            if (h.payloadLen < apx::kFrameCrcSize || h.payloadLen > apx::kMaxFramePayload) {
                ++off;
                cResync_.fetch_add(1, std::memory_order_relaxed);
                continue;
            }

            // R2 收流解密（9502 子批）：noiseActive 时 payload（含 CRC 尾）是密文
            //（比明文长 16 字节 AEAD 标签），按帧头声明的密文长先攒齐，verify 前
            // 原位解密、帧头 payloadLen（偏移 8）重写为明文长。解密失败 = 标签不过
            //（密钥不符/被改），计数丢弃 —— 媒体流允许丢，不断链（与 Android
            // MediaChannel.pump 同口径）。
            if (decActive) {
                const size_t ctotal = apx::kFrameHeaderSize + h.payloadLen;
                if (acc.size() - off < ctotal) break;   // 密文未攒齐，等下一次 recv
                // qmu_ 短暂持锁拷贝会话（teardown 并发 reset 的竞争窗口内安全）
                std::shared_ptr<wireless::NoiseSession> sess;
                {
                    std::lock_guard<std::mutex> qlk(qmu_);
                    sess = noiseSess_;
                }
                const size_t plainLen =
                    sess ? sess->decrypt(p + apx::kFrameHeaderSize, h.payloadLen) : 0;
                if (plainLen == 0) {
                    off += ctotal;
                    cResync_.fetch_add(1, std::memory_order_relaxed);
                    continue;
                }
                putU32Le(acc.data() + off + 8, static_cast<uint32_t>(plainLen));
                h.payloadLen = static_cast<uint32_t>(plainLen);
            }

            const size_t total = apx::kFrameHeaderSize + h.payloadLen;
            if (acc.size() - off < total) break;   // 半帧，等下一次 recv

            // v184：补上尾部 CRC32 校验 —— 媒体面此前**完全不校验**，缓冲错位或链路
            // 脏数据会把坏载荷直接喂给解码器与触摸注入（花屏 / 爆音 / 乱点光标）。
            // 控制面（ctrl9511.cpp）早有这一步，这里对齐。
            if (!apx::verifyPayload(p + apx::kFrameHeaderSize, h.payloadLen)) {
                off += total;
                cResync_.fetch_add(1, std::memory_order_relaxed);
                continue;
            }

            const uint8_t* body = p + apx::kFrameHeaderSize;
            const size_t bodyLen = h.payloadLen - apx::kFrameCrcSize;
            switch (h.streamId) {
                case kStreamMic:    bump(bodyLen, cMicFrames_, cMicBytes_); break;
                // M7-c 批 3：摄像头 JPEG 上行也计数 —— 与 handler 投递解耦，
                // 即便面板没注册回调，「链路到底走没走」也有客观证据可看。
                case kStreamCamera: bump(bodyLen, cCameraFrames_, cCameraBytes_); break;

                case kStreamTouch:  // 副屏触摸：计数 + 注入（毫秒级，可留收流线程；
                                    // mac 注入为诚实降级，不影响其余流）
                    bump(bodyLen, cTouchFrames_, cTouchBytes_);
                    injectTouchFrame(body, bodyLen);
                    break;
                default: break;
            }
            // 回调在收流线程上执行：实现方必须自己切线程、且不要阻塞
            if (handler_) handler_(h.streamId, h.flags, h.seq, body, bodyLen);
            off += total;
        }
        if (off == acc.size()) acc.clear();
        else if (off > 0) acc.erase(acc.begin(), acc.begin() + static_cast<long>(off));
    }
    APX_LOGI("媒体通道收流结束");
    teardown();
}

// ------------------------------------------------------------------ 发流 ----

bool MediaSession::enqueue(std::vector<uint8_t>&& frame, uint8_t streamId) {
    std::lock_guard<std::mutex> lk(qmu_);
    // 视频是「可丢」的大块载荷（副屏丢一帧只是画面顿一下），音频丢了就是断音。
    // 队列一旦积压就先丢视频，保住音频的时效性。
    //
    // ★ 阈值从 kQueueCap/2(=128，30fps 下≈4 秒) 收到 16：原值等于允许积压 4 秒画面，
    //   一旦网络抖动就进入"延迟滚雪球"，用户感知就是**越看越卡、操作越不同步**。
    //   16 帧（≈0.5 秒）是"能吃掉一次瞬时抖动"与"不累积成秒级延迟"之间的折中。
    if (streamId == kStreamVideo && outQ_.size() > kVideoBacklogCap) {
        cDropped_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    if (outQ_.size() >= kQueueCap) {
        // v184：弃旧时**跳过音频帧** —— 音频包小、断一截就是用户听到的"音箱断了"；
        // 优先丢弃队首的视频帧，实在全是音频时才丢最老的音频帧。
        size_t drop = 0;
        while (drop < outQ_.size()) {
            const auto& f = outQ_[drop];
            if (f.size() >= 5 && f[4] == kStreamAudio) { ++drop; continue; }
            break;
        }
        if (drop >= outQ_.size()) drop = outQ_.size() - 1;
        outQ_.erase(outQ_.begin() + static_cast<std::ptrdiff_t>(drop));
        cDropped_.fetch_add(1, std::memory_order_relaxed);
    }
    outQ_.emplace_back(std::move(frame));
    qcv_.notify_one();
    return true;
}

bool MediaSession::sendFrame(uint8_t streamId, const uint8_t* body, size_t len, uint8_t flags) {
    if (!running_.load() || !body || len == 0) return false;
    uint32_t mySeq;
    {
        // seq 只在发送侧递增，用 mu_ 保护即可（不与队列锁嵌套，避免死锁）
        std::lock_guard<std::mutex> lk(mu_);
        if (!st_.connected) return false;
        mySeq = ++seq_;
    }
    auto f = buildFrame(streamId, body, len, mySeq, flags);
    if (f.empty()) return false;
    switch (streamId) {
        case kStreamVideo: bump(len, cVideoFrames_, cVideoBytes_); break;
        case kStreamAudio: bump(len, cAudioFrames_, cAudioBytes_); break;
        default: break;
    }
    return enqueue(std::move(f), streamId);
}

bool MediaSession::sendRawFrame(const uint8_t* frame, size_t len, uint8_t policyStreamId) {
    if (!running_.load() || !frame || len == 0) return false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (!st_.connected) return false;
    }
    // 分片计数与 FrameWriter 的「一帧多片」对应：这里数的是**分片**，
    // 面板展示时按「已送 N 片」表述，不假装是逻辑帧数。
    if (policyStreamId == kStreamVideo) {
        cVideoFrames_.fetch_add(1, std::memory_order_relaxed);
        cVideoBytes_.fetch_add(len, std::memory_order_relaxed);
    }
    return enqueue(std::vector<uint8_t>(frame, frame + len), policyStreamId);
}

bool MediaSession::sendFragmented(uint8_t streamId, const uint8_t* body, size_t len,
                                  uint8_t baseFlags, size_t maxBody) {
    if (!running_.load() || !body || len == 0 || maxBody == 0) return false;
    uint32_t mySeq;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (!st_.connected) return false;
        mySeq = ++seq_;
    }
    bool allOk = true;
    for (size_t off = 0; off < len; off += maxBody) {
        const size_t n = std::min(maxBody, len - off);
        const bool last = (off + n >= len);
        // 同属一帧的各分片 seq 相同，只有末片带 last_fragment（与 frame_writer 一致）
        const uint8_t flags = last ? static_cast<uint8_t>(baseFlags | 0x02)
                                   : static_cast<uint8_t>(baseFlags & ~0x02);
        auto f = buildFrame(streamId, body + off, n, mySeq, flags);
        if (f.empty() || !enqueue(std::move(f), streamId)) allOk = false;
    }
    if (allOk) {
        switch (streamId) {
            case kStreamVideo: bump(len, cVideoFrames_, cVideoBytes_); break;
            case kStreamAudio: bump(len, cAudioFrames_, cAudioBytes_); break;
            default: break;
        }
    }
    return allOk;
}

void MediaSession::writerLoop() {
    // mac-port：原循环体整个圈在 #if _WIN32 内 —— 体本身只依赖队列 + sendAllBlocking，
    // 跨平台直接跑。
    while (running_.load()) {
        std::vector<uint8_t> frame;
        {
            std::unique_lock<std::mutex> lk(qmu_);
            qcv_.wait_for(lk, std::chrono::milliseconds(200),
                          [this] { return !outQ_.empty() || !running_.load(); });
            if (!running_.load()) break;
            if (outQ_.empty()) continue;
            // v184：音频优先 —— 视频丢几帧只是画面顿一下，音频断续就是用户听到的
            // "音箱断了"。从队列里摘出最早的音频帧优先发出（视频帧 FIFO 顺延）。
            // APX1 头 off+4 = streamId；kStreamAudio=1。
            size_t pick = 0;
            for (; pick < outQ_.size(); ++pick) {
                const auto& f = outQ_[pick];
                if (f.size() >= 5 && f[4] == kStreamAudio) break;
            }
            if (pick >= outQ_.size()) pick = 0;   // 没有音频帧 → 普通 FIFO
            frame = std::move(outQ_[pick]);
            outQ_.erase(outQ_.begin() + static_cast<std::ptrdiff_t>(pick));
        }
        // R2 加密（9502 子批）：**出队后**加密 —— 入队的是明文完整帧，出队时 payload
        // 整段（含 CRC 尾）AEAD、帧头 payloadLen 重写。不在入队时做：音频优先重排
        // 语义以明文为准，nonce 顺序 = 实际发送顺序。加密失败（会话缺失/超限）= 断链
        // —— AEAD nonce 已前进，明文补发对不上对端解密序。
        if (noiseActive_.load(std::memory_order_acquire)) {
            // qmu_ 短暂持锁拷贝会话（teardown 并发 reset 的竞争窗口内安全）
            std::shared_ptr<wireless::NoiseSession> sess;
            {
                std::lock_guard<std::mutex> qlk(qmu_);
                sess = noiseSess_;
            }
            if (!sess || !encryptFrame(frame, *sess)) {
                mediaLog("R2 encryptFrame failed");
                APX_LOGW("媒体帧 R2 加密失败，判定链路失效");
                break;
            }
        }
        if (!sendAllBlocking(frame.data(), frame.size())) {
            // 部分写出会永久打乱对端的帧对齐，无法局部恢复 —— 只能断链让上层重连。
            mediaLog("writer break (sendAllBlocking false)");
            APX_LOGW("媒体帧写出失败，判定链路失效");
            break;
        }
    }
    teardown();
}

bool MediaSession::sendAllBlocking(const uint8_t* p, size_t n) {
    size_t sent = 0;
    // v184 fix("副屏老是断"): the 50ms SO_SNDTIMEO is only the yield granularity of a
    // single send() call, NOT a link-down verdict. Previously one congestion timeout
    // returned false and writerLoop tore the session down, so every WiFi hiccup
    // caused a reconnect cycle every ~3s. Now congestion retries **within the same
    // frame** for up to 5s: cSendTimeouts_ still feeds ABR to lower the bitrate,
    // and frame alignment is preserved (peer clears rx buffer on reconnect).
    // mac-port：deadline 用跨平台 nowMs()（原 GetTickCount64）、yield 用
    // std::this_thread::sleep_for（原 Sleep）、错误码分平台判定（WSAE* vs errno E*）。
    const int64_t deadline = nowMs() + 5000;
    while (sent < n) {
        const auto r = ::send(sock_, reinterpret_cast<const char*>(p) + sent,
                              static_cast<int>(n - sent), 0);
        if (r > 0) {
            sent += static_cast<size_t>(r);
            continue;
        }
        const int e = sockErr();
#if defined(_WIN32)
        const bool retryable = (e == WSAEWOULDBLOCK || e == WSAETIMEDOUT ||
                                e == WSAENOBUFS);
#else
        const bool retryable = (e == EAGAIN || e == EWOULDBLOCK ||
                                e == ETIMEDOUT || e == ENOBUFS);
#endif
        if (retryable) {
            cSendTimeouts_.fetch_add(1, std::memory_order_relaxed);
            if (nowMs() > deadline) {
                mediaLog("send congest 5s giveup");
                return false;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));  // yield to kernel buffer
            continue;
        }
        mediaLog((std::string("send real err=") + std::to_string(e)).c_str());
        return false;                                        // real error: reset/unreachable
    }
    return true;
}

void MediaSession::teardown() {
    mediaLog("teardown");
    running_.store(false);
    // R2 状态随连接一起收掉（9502 子批）：会话不跨连接复用（shared_ptr 由残留
    // 使用者安全持完最后一程；qmu_ 保护与 reader/writer 拷贝互斥）
    noiseActive_.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> qlk(qmu_);
        noiseSess_.reset();
    }
    if (sock_ != kInvalidSock) {
#if defined(_WIN32)
        ::shutdown(sock_, SD_BOTH);
#else
        ::shutdown(sock_, SHUT_RDWR);
#endif
        closeSock(sock_);
        sock_ = kInvalidSock;
    }
    qcv_.notify_all();
    {
        std::lock_guard<std::mutex> lk(qmu_);
        outQ_.clear();
    }
    std::lock_guard<std::mutex> lk(mu_);
    st_.connected = false;
}

}  // namespace apxpc::media
