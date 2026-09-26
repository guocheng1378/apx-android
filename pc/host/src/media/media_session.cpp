#include "apxpc/media/media_session.hpp"

#include "apxpc/log.hpp"
#include "apxpc/media/touch_inject.hpp"

#include <apx/frame.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
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
#if !defined(_WIN32)
    st_.connected = false;
    st_.error = "媒体通道目前仅实现 Windows 宿主";
    return false;
#else
    ensureWsa();
    token_ = token;
    st_.peer = host + ":" + std::to_string(static_cast<unsigned>(port));

    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    char portStr[8]{};
    std::snprintf(portStr, sizeof(portStr), "%u", static_cast<unsigned>(port));
    if (getaddrinfo(host.c_str(), portStr, &hints, &res) != 0 || !res) {
        st_.connected = false;
        st_.error = "地址解析失败：" + host;
        APX_LOGW("媒体连接失败：{}", st_.error);
        return false;
    }

    SOCKET s = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (s == INVALID_SOCKET) {
        freeaddrinfo(res);
        st_.error = "socket 创建失败";
        return false;
    }

    // 非阻塞 connect + select 3s 超时
    u_long nb = 1;
    ioctlsocket(s, FIONBIO, &nb);
    const int rc = ::connect(s, res->ai_addr, static_cast<int>(res->ai_addrlen));
    freeaddrinfo(res);
    if (rc == SOCKET_ERROR) {
        fd_set w;
        FD_ZERO(&w);
        FD_SET(s, &w);
        timeval tv{3, 0};
        if (select(0, nullptr, &w, nullptr, &tv) <= 0) {
            closesocket(s);
            st_.connected = false;
            st_.error = "媒体连接超时（3s）：" + st_.peer;
            APX_LOGW("媒体连接失败：{}", st_.error);
            return false;
        }
    }
    nb = 0;
    ioctlsocket(s, FIONBIO, &nb);

    // 低延迟：大流量流最怕 Nagle 把小帧攒成 200ms 一发
    BOOL nodelay = TRUE;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay),
               sizeof(nodelay));
    // ★ 发送缓冲**不能开大**：512KB/1MB 这种"看起来更抗抖动"的缓冲，实际是
    //   **延迟蓄水池** —— 发送成功 ≠ 对方收到，堆在核缓冲里的数据会变成几秒的滞后，
    //   而且越积越多（表现就是"副屏越看越卡、越看越不同步"）。
    //   同仓库 tcp_transport_win.cpp 早就踩过并写了这条结论，副屏这条链路当时漏了。
    //   现在对齐那条策略：64KB 缓冲 + 50ms 发送超时（拥塞时立即失败丢帧，保住时效）。
    int sndBuf = 64 * 1024;
    setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sndBuf), sizeof(sndBuf));
    DWORD sndTimeout = 50;   // ms
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sndTimeout),
               sizeof(sndTimeout));

    // 令牌握手：u32 LE 长度 + UTF-8 令牌（**无回执字节**，与 Android 侧对称）
    const uint32_t len = static_cast<uint32_t>(token_.size());
    const uint8_t hdr[4] = {
        static_cast<uint8_t>(len & 0xFF), static_cast<uint8_t>((len >> 8) & 0xFF),
        static_cast<uint8_t>((len >> 16) & 0xFF), static_cast<uint8_t>((len >> 24) & 0xFF),
    };
    if (::send(s, reinterpret_cast<const char*>(hdr), 4, 0) != 4 ||
        (len > 0 && ::send(s, token_.data(), static_cast<int>(len), 0) !=
                        static_cast<int>(len))) {
        closesocket(s);
        st_.connected = false;
        st_.error = "媒体令牌握手发送失败";
        return false;
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
#endif
}

void MediaSession::disconnect() {
    // 与 connect 互斥：reader_/writer_ 的 join/赋值绝不能并发（见 hpp lifecycleMu_）
    std::lock_guard<std::recursive_mutex> lkLife(lifecycleMu_);
    const bool wasRunning = running_.exchange(false);
#if defined(_WIN32)
    const bool hadSock = (sock_ != INVALID_SOCKET);
#else
    const bool hadSock = (sock_ != -1);
#endif
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

    c.touchFrames = cTouchFrames_.load(std::memory_order_relaxed);
    c.touchBytes = cTouchBytes_.load(std::memory_order_relaxed);
    c.dropped = cDropped_.load(std::memory_order_relaxed);
    c.resync = cResync_.load(std::memory_order_relaxed);
    c.sendTimeouts = cSendTimeouts_.load(std::memory_order_relaxed);
    return c;
}

// ------------------------------------------------------------------ 收流 ----

void MediaSession::readerLoop() {
#if defined(_WIN32)
    std::vector<uint8_t> buf(64 * 1024);
    std::vector<uint8_t> acc;
    acc.reserve(1 << 20);
    while (running_.load()) {
        const int n = ::recv(sock_, reinterpret_cast<char*>(buf.data()),
                             static_cast<int>(buf.size()), 0);
        if (n <= 0) break;
        acc.insert(acc.end(), buf.data(), buf.data() + n);

        size_t off = 0;
        while (acc.size() - off >= apx::kFrameHeaderSize) {
            const uint8_t* p = acc.data() + off;
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
            const size_t total = apx::kFrameHeaderSize + h.payloadLen;
            if (acc.size() - off < total) break;   // 半帧，等下一次 recv

            const uint8_t* body = p + apx::kFrameHeaderSize;
            const size_t bodyLen = h.payloadLen - apx::kFrameCrcSize;
            switch (h.streamId) {
                case kStreamMic:    bump(bodyLen, cMicFrames_, cMicBytes_); break;

                case kStreamTouch:  // 副屏触摸：计数 + SendInput 注入（毫秒级，可留收流线程）
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
#endif
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
        outQ_.pop_front();   // 保新弃旧
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
#if defined(_WIN32)
    while (running_.load()) {
        std::vector<uint8_t> frame;
        {
            std::unique_lock<std::mutex> lk(qmu_);
            qcv_.wait_for(lk, std::chrono::milliseconds(200),
                          [this] { return !outQ_.empty() || !running_.load(); });
            if (!running_.load()) break;
            if (outQ_.empty()) continue;
            frame = std::move(outQ_.front());
            outQ_.pop_front();
        }
        if (!sendAllBlocking(frame.data(), frame.size())) {
            // 部分写出会永久打乱对端的帧对齐，无法局部恢复 —— 只能断链让上层重连。
            APX_LOGW("媒体帧写出失败，判定链路失效");
            break;
        }
    }
#endif
    teardown();
}

bool MediaSession::sendAllBlocking(const uint8_t* p, size_t n) {
#if defined(_WIN32)
    size_t sent = 0;
    while (sent < n) {
        const int r = ::send(sock_, reinterpret_cast<const char*>(p) + sent,
                             static_cast<int>(n - sent), 0);
        if (r <= 0) {
            // ★ 拥塞信号：我们给 socket 设了 50ms 发送超时，所以这里返回 -1 且 errno 是
            //   WSAEWOULDBLOCK/WSAETIMEDOUT，含义就是"内核发送缓冲已满、网络吃不下当前码率"。
            //   自适应码率（ABR）唯一的反馈就来自这个计数 —— 以前这里只是 return false，
            //   上层只能把它当成"链路失效"，无从区分"链路断了"和"只是码率给高了"。
            const int e = WSAGetLastError();
            if (e == WSAEWOULDBLOCK || e == WSAETIMEDOUT || e == WSAENOBUFS)
                cSendTimeouts_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        sent += static_cast<size_t>(r);
    }
    return true;
#else
    (void)p; (void)n;
    return false;
#endif
}

void MediaSession::teardown() {
    running_.store(false);
#if defined(_WIN32)
    if (sock_ != INVALID_SOCKET) {
        ::shutdown(sock_, SD_BOTH);
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
    }
#endif
    qcv_.notify_all();
    {
        std::lock_guard<std::mutex> lk(qmu_);
        outQ_.clear();
    }
    std::lock_guard<std::mutex> lk(mu_);
    st_.connected = false;
}

}  // namespace apxpc::media
