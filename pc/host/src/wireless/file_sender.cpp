#include "apxpc/wireless/file_sender.hpp"

#include "apxpc/log.hpp"
#include "apxpc/wireless/noise_session.hpp"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <string>
#include <vector>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32")
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace apxpc::wireless {
namespace {

#if defined(_WIN32)
using Sock = SOCKET;
const Sock kBadSock = INVALID_SOCKET;
void closeSock(Sock s) { ::closesocket(s); }
void ensureNet() {
    static const bool once = [] {
        WSADATA w{};
        ::WSAStartup(MAKEWORD(2, 2), &w);
        return true;
    }();
    (void)once;
}
#else
using Sock = int;
const Sock kBadSock = -1;
void closeSock(Sock s) { ::close(s); }
void ensureNet() {}
#endif

std::mutex g_errMu;
std::string g_lastError;

void setError(const std::string& e) {
    std::lock_guard<std::mutex> lk(g_errMu);
    g_lastError = e;
}

bool sendAll(Sock s, const uint8_t* data, size_t len) {
    size_t off = 0;
    while (off < len) {
        const int r = ::send(s, reinterpret_cast<const char*>(data + off),
                             static_cast<int>(len - off), 0);
        if (r <= 0) return false;
        off += static_cast<size_t>(r);
    }
    return true;
}

/// recv 指定字节数（读 msg2 用；调用前设置 SO_RCVTIMEO，超时返回 false）
bool recvAll(Sock s, uint8_t* dst, size_t len) {
    size_t off = 0;
    while (off < len) {
        const int r = ::recv(s, reinterpret_cast<char*>(dst + off),
                             static_cast<int>(len - off), 0);
        if (r <= 0) return false;
        off += static_cast<size_t>(r);
    }
    return true;
}

/// UTF-8 路径 → 文件名（同时兼容 / 与 \）
std::string baseName(const std::string& path) {
    const size_t k = path.find_last_of("/\\");
    return (k == std::string::npos) ? path : path.substr(k + 1);
}

/// u32 小端写（与 Android 端 intToBytes 对拍）
void putU32(uint8_t* dst, uint32_t v) {
    for (int i = 0; i < 4; ++i) dst[i] = static_cast<uint8_t>((v >> (8 * i)) & 0xFF);
}

/// R2 传输段明文上限（与 Android FileReceiver.SEG_PLAIN 同口径）
constexpr size_t kSegPlain = 32 * 1024;
/// Noise 握手消息长度上限（IK msg1/msg2 实际 ≤ 96B，放宽到 1KB 防畸形帧）
constexpr uint32_t kHsMax = 1024;

/**
 * R2 握手（9512 Initiator 侧，9512/9502 子批 2026-11-17）：发 [u32 0][u32 len1][msg1]、
 * 读 [u32 len2][msg2]（SO_RCVTIMEO 5s）。tok/peerPub/ownPriv 见 prepareR2。
 * 成功返回 split 完毕、可直接加密的传输会话（调用方 delete）；失败返回 nullptr
 * （调用方关连接、回退明文重连一次 —— fail-safe；会话内部自清理）。
 */
NoiseSession* r2Handshake(Sock s, const std::string& token,
                          const uint8_t peerPub[kNoiseStaticKeyLen],
                          const uint8_t ownPriv[kNoiseStaticKeyLen]) {
    NoiseSession* sess = new NoiseSession(kNoisePrologue9512);
    if (!sess->beginInitiator(reinterpret_cast<const uint8_t*>(token.data()), token.size(),
                              peerPub, ownPriv)) {
        delete sess;
        return nullptr;
    }
    uint8_t msg1[kHsMax];
    const int n1 = sess->writeHandshakeMsg(msg1, sizeof(msg1));
    if (n1 <= 0) { delete sess; return nullptr; }
    // 模式标记 0（占 legacy 名称长度位）+ msg1 封装
    uint8_t mark[8];
    putU32(mark, 0);
    putU32(mark + 4, static_cast<uint32_t>(n1));
    if (!sendAll(s, mark, 8) || !sendAll(s, msg1, static_cast<size_t>(n1))) { delete sess; return nullptr; }
    // 等 msg2（对端 Responder 生成后原路返回）：临时 5s 读超时，读完恢复默认。
    // SO_RCVTIMEO 参数类型分平台：Windows 收毫秒 DWORD，POSIX 收 timeval ——
    // 混用的话 Win 会把 tv_sec 的 5 当 5ms，握手必然超时回退明文。
#if defined(_WIN32)
    DWORD rto = 5000;
    ::setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rto), sizeof(rto));
#else
    struct timeval tv{5, 0};
    ::setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&tv), sizeof(tv));
#endif
    uint8_t hdr[4];
    if (!recvAll(s, hdr, 4)) { delete sess; return nullptr; }
    const uint32_t n2 = static_cast<uint32_t>(hdr[0]) | (static_cast<uint32_t>(hdr[1]) << 8) |
                        (static_cast<uint32_t>(hdr[2]) << 16) | (static_cast<uint32_t>(hdr[3]) << 24);
    if (n2 == 0 || n2 > kHsMax) { delete sess; return nullptr; }
    uint8_t msg2[kHsMax];
    if (!recvAll(s, msg2, n2)) { delete sess; return nullptr; }
#if defined(_WIN32)
    DWORD rto0 = 0;
    ::setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rto0), sizeof(rto0));
#else
    tv.tv_sec = 0;
    ::setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&tv), sizeof(tv));
#endif
    if (sess->readHandshakeMsg(msg2, n2) != 0 || !sess->split()) { delete sess; return nullptr; }
    return sess;
}

/**
 * R2 门控资料：token（9511 链路的被控端令牌）、对端静态公钥（TOFU，9511 握手成功时
 * 由 ctrl9511 挂进 R2 上下文）、本端私钥。任一缺失返回 false（直接走明文）。
 */
bool prepareR2(std::string& tokenOut, uint8_t peerPub[kNoiseStaticKeyLen],
               uint8_t ownPriv[kNoiseStaticKeyLen]) {
    tokenOut = r2Token();
    if (tokenOut.empty()) return false;
    if (!r2PeerStaticKey(peerPub)) return false;
    uint8_t pk[kNoiseStaticKeyLen];
    if (!ensureNoiseKeyPair(ownPriv, pk)) return false;
    return true;
}

}  // namespace

std::string lastSendError() {
    std::lock_guard<std::mutex> lk(g_errMu);
    return g_lastError;
}

bool sendFile(const std::string& host, uint16_t port, const std::string& filePath,
              const std::function<void(int)>& onProgress) {
    ensureNet();
    setError("");

    std::error_code ec;
    const std::filesystem::path p(std::u8string(filePath.begin(), filePath.end()));
    if (!std::filesystem::is_regular_file(p, ec)) {
        setError("不是常规文件：" + filePath);
        return false;
    }
    const uint64_t size = static_cast<uint64_t>(std::filesystem::file_size(p, ec));
    const std::string name = baseName(filePath);

    // 读文件（Windows 用宽字符路径，避免中文名按 ANSI 解析失败）
    std::FILE* fp = nullptr;
#if defined(_WIN32)
    fp = ::_wfopen(p.wstring().c_str(), L"rb");
#else
    fp = std::fopen(p.c_str(), "rb");
#endif
    if (!fp) {
        setError("打不开文件（权限？）：" + filePath);
        return false;
    }

    // R2 门控（先备材料再连）：token + 对端公钥（TOFU）+ 本端密钥对齐备才尝试加密
    std::string r2TokenStr;
    uint8_t r2Peer[kNoiseStaticKeyLen] = {};
    uint8_t r2Priv[kNoiseStaticKeyLen] = {};
    const bool wantR2 = prepareR2(r2TokenStr, r2Peer, r2Priv);

    Sock s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == kBadSock) {
        std::fclose(fp);
        setError("socket() 失败");
        return false;
    }
    int yes = 1;
    ::setsockopt(s, IPPROTO_TCP, 1 /*TCP_NODELAY*/, reinterpret_cast<const char*>(&yes), sizeof(yes));
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    if (::inet_pton(AF_INET, host.c_str(), &a.sin_addr) != 1) {
        closeSock(s);
        std::fclose(fp);
        setError("地址非法：" + host);
        return false;
    }
    if (::connect(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0) {
        closeSock(s);
        std::fclose(fp);
        // fix: 错误消息动态使用 port 参数，不再硬编码 :9512
        setError("连不上 " + host + ":" + std::to_string(port) + "（对端没在收？手机端要开着「无线」）");
        return false;
    }

    // R2 加密握手（wantR2 且握失败 → 关闭回退一次明文重连；此时尚未读文件，fp 可续用）
    NoiseSession* sess = nullptr;
    if (wantR2) {
        sess = r2Handshake(s, r2TokenStr, r2Peer, r2Priv);
        if (sess) {
            APX_LOGI("9512 R2 通道加密已启用（发送端）");
        } else {
            // 对端是旧版/明文：重连一次走 legacy。fp 未读过（握手只动 socket），直接续用。
            APX_LOGW("9512 R2 握手失败，回退明文重连");
            closeSock(s);
            s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
            if (s == kBadSock) {
                std::fclose(fp);
                setError("socket() 失败（回退）");
                return false;
            }
            ::setsockopt(s, IPPROTO_TCP, 1, reinterpret_cast<const char*>(&yes), sizeof(yes));
            if (::connect(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0) {
                closeSock(s);
                std::fclose(fp);
                setError("连不上 " + host + ":" + std::to_string(port) + "（R2 回退重连失败）");
                return false;
            }
            std::rewind(fp);
        }
    }

    bool ok = true;
    // head：u32 名称长度 + 名称 + u64 大小（R2 时整个 head 是首段明文）
    {
        const uint32_t nameLen = static_cast<uint32_t>(name.size());
        if (sess) {
            // R2 首段：head 明文（nameLen + name + size）整段 AEAD 后 [u32 ctLen][ct] 写出
            const size_t plainLen = 4 + nameLen + 8;
            std::vector<uint8_t> seg(plainLen + 16);   // 留出 AEAD 标签
            for (int i = 0; i < 4; ++i) seg[i] = static_cast<uint8_t>((nameLen >> (8 * i)) & 0xFF);
            memcpy(seg.data() + 4, name.data(), nameLen);
            for (int i = 0; i < 8; ++i) seg[4 + nameLen + i] = static_cast<uint8_t>((size >> (8 * i)) & 0xFF);
            const size_t ctLen = sess->encrypt(seg.data(), plainLen, plainLen + 16);
            if (ctLen == 0) {
                ok = false;
                setError("R2 首段加密失败");
            } else {
                uint8_t hdr[4];
                putU32(hdr, static_cast<uint32_t>(ctLen));
                ok = sendAll(s, hdr, 4) && sendAll(s, seg.data(), ctLen);
            }
        } else {
            uint8_t head[4];
            for (int i = 0; i < 4; ++i) head[i] = static_cast<uint8_t>((nameLen >> (8 * i)) & 0xFF);
            uint8_t szBuf[8];
            for (int i = 0; i < 8; ++i) szBuf[i] = static_cast<uint8_t>((size >> (8 * i)) & 0xFF);
            ok = sendAll(s, head, 4) && sendAll(s, reinterpret_cast<const uint8_t*>(name.data()), name.size()) &&
                 sendAll(s, szBuf, 8);
        }
    }

    std::vector<uint8_t> buf(kSegPlain);
    uint64_t sent = 0;
    while (ok) {
        const size_t n = std::fread(buf.data(), 1, kSegPlain, fp);
        if (n == 0) break;
        if (sess) {
            // R2 数据段：文件数据整段 AEAD（≤ 32KB/段，与 Android 端同口径）
            const size_t ctLen = sess->encrypt(buf.data(), n, kSegPlain + 16);
            if (ctLen == 0) {
                ok = false;
                setError("R2 段加密失败");
                break;
            }
            uint8_t hdr[4];
            putU32(hdr, static_cast<uint32_t>(ctLen));
            if (!sendAll(s, hdr, 4) || !sendAll(s, buf.data(), ctLen)) {
                ok = false;
                break;
            }
        } else {
            if (!sendAll(s, buf.data(), n)) {
                ok = false;
                break;
            }
        }
        sent += n;
        if (size > 0 && onProgress) {
            onProgress(static_cast<int>(std::min<uint64_t>(100, sent * 100 / size)));
        }
    }
    std::fclose(fp);
    closeSock(s);
    delete sess;

    if (!ok) {
        setError("发送中断（对端断开？）");
        return false;
    }
    if (sent != size) {
        setError("读文件长度与预期不符（文件被改动？）");
        return false;
    }
    if (onProgress) onProgress(100);
    APX_LOGI("文件已发送：{}（{} 字节）→ {}:{}", name.c_str(),
             static_cast<unsigned long long>(size), host.c_str(), port);
    return true;
}

}  // namespace apxpc::wireless
