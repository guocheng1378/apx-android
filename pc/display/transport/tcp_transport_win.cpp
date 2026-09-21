// 无线（局域网 WiFi）TCP 传输 —— 正式主通道之一。
//
// 与 USB 的差异：USB 用不同端点承载不同 streamId，天然分离；TCP 只有一条字节流，
// 因此本实现内置**读线程 + 帧解复用**：按 APX 帧头的 streamId 把字节流拆进
// video / control / touch 三条逻辑通道（IChannel），对上层完全透明。
//
// 设计取舍（对照 scrcpy 的「video/audio/control 三条独立 socket」）：
//   scrcpy 用多 socket、每 socket 单职责、无需解复用；代价是多次连接与顺序约定。
//   本工程沿用「单链路 + 帧头 streamId」——因为 APX 帧格式本就带 streamId，且手机端
//   VideoReceiver 已是「单 transport + 按 streamId 分发」，无需改动数据面即可上线。
//   已知代价：同一 TCP 连接上存在跨流队头阻塞（视频突发可能让触控上行等待）。
//   若将来触控延迟成为瓶颈，可保持接口不变、改为开两条 TCP 连接（media / control），
//   本类的通道门面与上层调用点无需变更。
//
// 角色：
//   - spec.host 非空 → 客户端：连接 host:port（推荐 PC 作为控制端主动连入手机服务端）
//   - spec.host 为空 → 服务端：监听 port 并接受一条连接
// 两角色可配置；局域网内默认手机做服务端。
//
// 可靠性：TCP 本身可靠有序；断线由上层 LinkMonitor 触发 open() 重连（指数退避）。
#ifdef _WIN32

#include "transport/i_transport.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "apx/frame.h"
#include "common/log.hpp"
#include "transport/frame_writer.hpp"

namespace apxdisp {
namespace {

// ------------------------------------------------------------ 字节流缓冲 ----
// 提供与 USB IChannel 一致的「按字节流读取 + 超时」语义
class ByteStream {
public:
    void push(const uint8_t* data, size_t len) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            buf_.insert(buf_.end(), data, data + len);
        }
        cv_.notify_all();
    }

    // 返回读取字节数；0 表示超时/关闭
    size_t read(uint8_t* dst, size_t cap, uint32_t timeoutMs) {
        std::unique_lock<std::mutex> lk(mu_);
        auto pred = [this] { return off_ < buf_.size() || closed_; };
        if (timeoutMs == 0) cv_.wait(lk, pred);
        else cv_.wait_for(lk, std::chrono::milliseconds(timeoutMs), pred);

        if (off_ >= buf_.size()) return 0;
        const size_t n = std::min(cap, buf_.size() - off_);
        std::memcpy(dst, buf_.data() + off_, n);
        off_ += n;
        if (off_ >= buf_.size()) { buf_.clear(); off_ = 0; }
        else if (off_ > (1u << 20)) { buf_.erase(buf_.begin(), buf_.begin() + static_cast<long>(off_)); off_ = 0; }
        return n;
    }

    void wake() { cv_.notify_all(); }

    void close() {
        { std::lock_guard<std::mutex> lk(mu_); closed_ = true; }
        cv_.notify_all();
    }

    void reset() {
        std::lock_guard<std::mutex> lk(mu_);
        buf_.clear();
        off_ = 0;
        closed_ = false;
    }

private:
    std::mutex mu_;
    std::condition_variable cv_;
    std::vector<uint8_t> buf_;
    size_t off_ = 0;
    bool closed_ = false;
};

class TcpTransport;

class TcpChannel : public IChannel {
public:
    TcpChannel(TcpTransport* owner, bool canWrite, ByteStream* rx, bool reliable)
        : owner_(owner), canWrite_(canWrite), rx_(rx), reliable_(reliable) {}

    size_t write(const uint8_t* data, size_t len, uint32_t /*timeoutMs*/) override;
    size_t read(uint8_t* buf, size_t cap, uint32_t timeoutMs) override {
        return rx_ ? rx_->read(buf, cap, timeoutMs) : 0;
    }
    bool reliable() const override { return reliable_; }
    void cancel() override { if (rx_) rx_->wake(); }
    std::string lastError() const override;

private:
    TcpTransport* owner_ = nullptr;
    bool canWrite_ = false;
    ByteStream* rx_ = nullptr;
    bool reliable_ = false;
};

class TcpTransport : public ITransport {
public:
    TcpTransport()
        : videoCh_(this, true,  nullptr,   false),
          ctrlCh_(this,  true,  &ctrlRx_,  true),
          touchCh_(this, false, &touchRx_, true) {}
    ~TcpTransport() override { close(); }

    bool open(const TransportSpec& spec) override;
    void close() override;
    bool isOpen() const override { return opened_.load(); }

    IChannel* videoChannel() override { return &videoCh_; }
    IChannel* controlChannel() override { return &ctrlCh_; }
    IChannel* touchChannel() override { return &touchCh_; }

    bool reset() override { return opened_.load(); }  // TCP 无 stall 语义
    std::string lastError() const override { return lastError_; }
    TransportKind kind() const override { return TransportKind::Tcp; }

    // 供通道写入
    bool sendBytes(const uint8_t* data, size_t len);

private:
    bool startWinsock();
    bool connectTo(const std::string& host, uint16_t port);
    bool listenAndAccept(uint16_t port);
    bool exchangeToken(bool asClient, const std::string& token);
    void readerLoop();
    void cleanupSocket();

    SOCKET sock_ = INVALID_SOCKET;
    SOCKET listen_ = INVALID_SOCKET;
    std::atomic<bool> opened_{false};
    std::atomic<bool> stopping_{false};
    std::thread reader_;
    mutable std::mutex writeMu_;
    std::string lastError_;

    ByteStream ctrlRx_;
    ByteStream touchRx_;
    TcpChannel videoCh_;
    TcpChannel ctrlCh_;
    TcpChannel touchCh_;
    FrameSplitter splitter_;
};

std::string TcpChannel::lastError() const { return owner_ ? owner_->lastError() : std::string(); }
size_t TcpChannel::write(const uint8_t* data, size_t len, uint32_t) {
    return (canWrite_ && owner_ && owner_->sendBytes(data, len)) ? len : 0;
}

// ------------------------------------------------------------ Winsock ----
bool TcpTransport::startWinsock() {
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        lastError_ = "WSAStartup 失败";
        return false;
    }
    return true;
}

void TcpTransport::cleanupSocket() {
    if (sock_ != INVALID_SOCKET) { closesocket(sock_); sock_ = INVALID_SOCKET; }
    if (listen_ != INVALID_SOCKET) { closesocket(listen_); listen_ = INVALID_SOCKET; }
    WSACleanup();
}

bool TcpTransport::connectTo(const std::string& host, uint16_t port) {
    sock_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock_ == INVALID_SOCKET) { lastError_ = "socket() 失败"; return false; }

    // 超时连接：非阻塞 connect + select
    u_long nb = 1;
    ioctlsocket(sock_, FIONBIO, &nb);

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
        // 允许主机名
        addrinfo hints{};
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* res = nullptr;
        if (getaddrinfo(host.c_str(), nullptr, &hints, &res) != 0 || !res) {
            lastError_ = "无法解析主机: " + host;
            return false;
        }
        addr.sin_addr = reinterpret_cast<sockaddr_in*>(res->ai_addr)->sin_addr;
        freeaddrinfo(res);
    }

    int rc = connect(sock_, reinterpret_cast<sockaddr*>(&addr), sizeof addr);
    if (rc == SOCKET_ERROR) {
        if (WSAGetLastError() != WSAEWOULDBLOCK) { lastError_ = "connect 失败"; return false; }
        fd_set wfds; FD_ZERO(&wfds); FD_SET(sock_, &wfds);
        timeval tv{3, 0};
        if (select(0, nullptr, &wfds, nullptr, &tv) <= 0) { lastError_ = "connect 超时"; return false; }
        int err = 0; int len = sizeof err;
        if (getsockopt(sock_, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&err), &len) != 0 || err != 0) {
            lastError_ = "connect 失败（err=" + std::to_string(err) + "）";
            return false;
        }
    }
    // 恢复阻塞
    nb = 0;
    ioctlsocket(sock_, FIONBIO, &nb);
    return true;
}

bool TcpTransport::listenAndAccept(uint16_t port) {
    listen_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listen_ == INVALID_SOCKET) { lastError_ = "socket() 失败"; return false; }
    int yes = 1;
    setsockopt(listen_, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof yes);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    if (bind(listen_, reinterpret_cast<sockaddr*>(&addr), sizeof addr) == SOCKET_ERROR) {
        lastError_ = "bind 失败（端口 " + std::to_string(port) + " 被占用？）";
        return false;
    }
    if (listen(listen_, 1) == SOCKET_ERROR) { lastError_ = "listen 失败"; return false; }

    APX_LOG_I("TCP 服务端监听 0.0.0.0:%u，等待手机连入…", port);
    // 循环 select 以便响应停止
    while (!stopping_.load()) {
        fd_set rfds; FD_ZERO(&rfds); FD_SET(listen_, &rfds);
        timeval tv{1, 0};
        int r = select(0, &rfds, nullptr, nullptr, &tv);
        if (r < 0) { lastError_ = "select 失败"; return false; }
        if (r == 0) continue;
        sock_ = accept(listen_, nullptr, nullptr);
        if (sock_ == INVALID_SOCKET) { lastError_ = "accept 失败"; return false; }
        APX_LOG_I("TCP 已接受连接");
        return true;
    }
    lastError_ = "已取消";
    return false;
}

bool TcpTransport::exchangeToken(bool asClient, const std::string& token) {
    if (token.empty()) return true;  // 未启用令牌
    if (asClient) {
        uint32_t len = static_cast<uint32_t>(token.size());
        uint8_t hdr[4] = {static_cast<uint8_t>(len), static_cast<uint8_t>(len >> 8),
                          static_cast<uint8_t>(len >> 16), static_cast<uint8_t>(len >> 24)};
        if (!sendBytes(hdr, 4)) return false;
        if (!sendBytes(reinterpret_cast<const uint8_t*>(token.data()), token.size())) return false;
        return true;
    }
    // 服务端：读取 4 字节长度 + 令牌
    uint8_t hdr[4];
    size_t got = 0;
    while (got < 4) {
        int n = recv(sock_, reinterpret_cast<char*>(hdr) + got, static_cast<int>(4 - got), 0);
        if (n <= 0) { lastError_ = "读取令牌头失败"; return false; }
        got += static_cast<size_t>(n);
    }
    uint32_t len = static_cast<uint32_t>(hdr[0]) | (static_cast<uint32_t>(hdr[1]) << 8) |
                   (static_cast<uint32_t>(hdr[2]) << 16) | (static_cast<uint32_t>(hdr[3]) << 24);
    if (len > 256) { lastError_ = "令牌过长"; return false; }
    std::string got2(len, '\0');
    size_t off = 0;
    while (off < len) {
        int n = recv(sock_, got2.data() + off, static_cast<int>(len - off), 0);
        if (n <= 0) { lastError_ = "读取令牌失败"; return false; }
        off += static_cast<size_t>(n);
    }
    if (got2 != token) { lastError_ = "令牌不匹配，拒绝连接"; return false; }
    return true;
}

bool TcpTransport::open(const TransportSpec& spec) {
    close();
    stopping_ = false;
    if (!startWinsock()) return false;

    const bool asClient = !spec.host.empty();
    if (asClient) {
        if (!connectTo(spec.host, spec.port)) { cleanupSocket(); return false; }
    } else {
        if (!listenAndAccept(spec.port)) { cleanupSocket(); return false; }
    }

    int yes = 1;
    setsockopt(sock_, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&yes), sizeof yes);
    // v1.10 低延迟关键：**发送缓冲必须小**——大缓冲（512KB）在网络抖动时变成
    // 延迟蓄水池：发送成功≠到达，堆积 512KB ≈ 3 秒延迟且持续增长（真机实测
    // 「延迟巨大→画面消失」）。降到 64KB + 50ms 发送超时，拥塞时立即失败丢帧，
    // 让 FrameWriter 丢旧保新（scrcpy 同策略），延迟有界自愈。
    int snd = 64 * 1024, rcv = 256 * 1024;
    setsockopt(sock_, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&snd), sizeof snd);
    setsockopt(sock_, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rcv), sizeof rcv);
    DWORD sndTimeout = 50;  // ms：send 阻塞超上限即失败丢帧
    setsockopt(sock_, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sndTimeout), sizeof sndTimeout);

    if (!exchangeToken(asClient, spec.token)) {
        APX_LOG_E("%s", lastError_.c_str());
        cleanupSocket();
        return false;
    }

    ctrlRx_.reset();
    touchRx_.reset();
    splitter_ = FrameSplitter{};
    opened_ = true;
    reader_ = std::thread([this] { readerLoop(); });
    APX_LOG_I("TCP 传输已就绪：%s:%u（%s）",
              asClient ? spec.host.c_str() : "0.0.0.0", spec.port, asClient ? "客户端" : "服务端");
    return true;
}

void TcpTransport::close() {
    if (!opened_.exchange(false) && sock_ == INVALID_SOCKET && listen_ == INVALID_SOCKET) return;
    stopping_ = true;
    // 关闭 socket 以解除阻塞的 recv/accept
    if (sock_ != INVALID_SOCKET) { shutdown(sock_, SD_BOTH); }
    if (listen_ != INVALID_SOCKET) { shutdown(listen_, SD_BOTH); }
    ctrlRx_.close();
    touchRx_.close();
    ctrlRx_.wake();
    touchRx_.wake();
    if (reader_.joinable()) reader_.join();
    cleanupSocket();
}

bool TcpTransport::sendBytes(const uint8_t* data, size_t len) {
    if (!opened_.load() || sock_ == INVALID_SOCKET) return false;
    std::lock_guard<std::mutex> lk(writeMu_);
    size_t sent = 0;
    while (sent < len) {
        int n = send(sock_, reinterpret_cast<const char*>(data) + sent, static_cast<int>(len - sent), 0);
        if (n <= 0) {
            int err = WSAGetLastError();
            // v1.10：SO_SNDTIMEO 下拥塞返回 WSAEWOULDBLOCK——可恢复（丢本帧
            // 保后续新帧），不置死连接；真断线（WSAECONNRESET 等）才失败。
            if (err == WSAEWOULDBLOCK || err == WSAETIMEDOUT) {
                lastError_ = "send 超时（拥塞，丢帧保延迟）";
                return false;
            }
            lastError_ = "send 失败 err=" + std::to_string(err);
            return false;
        }
        sent += static_cast<size_t>(n);
    }
    return true;
}

// 读线程：recv → 帧解复用 → 各逻辑通道
void TcpTransport::readerLoop() {
    std::vector<uint8_t> buf(256 * 1024);
    while (!stopping_.load()) {
        int n = recv(sock_, reinterpret_cast<char*>(buf.data()), static_cast<int>(buf.size()), 0);
        if (n <= 0) {
            if (!stopping_.load()) APX_LOG_W("TCP 对端关闭/读错误（n=%d）", n);
            break;
        }
        splitter_.feed(buf.data(), static_cast<size_t>(n));
        std::vector<uint8_t> frame;
        while (splitter_.next(frame)) {
            if (frame.size() < sizeof(apx::ApxFrameHeader)) continue;
            apx::ApxFrameHeader h{};
            std::memcpy(&h, frame.data(), sizeof h);
            switch (h.streamId) {
                case apx::kStreamTouch:   touchRx_.push(frame.data(), frame.size()); break;
                case apx::kStreamControl: ctrlRx_.push(frame.data(), frame.size());  break;
                case apx::kStreamVideo:   /* 下行为主，忽略上行视频 */ break;
                default:                  ctrlRx_.push(frame.data(), frame.size()); break;  // telemetry → 控制面
            }
        }
    }
    // 通知所有等待者：链路已断
    opened_ = false;
    ctrlRx_.close();
    touchRx_.close();
}

}  // namespace

std::unique_ptr<ITransport> createTcpTransport() { return std::make_unique<TcpTransport>(); }

}  // namespace apxdisp

#endif  // _WIN32
