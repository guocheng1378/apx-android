#include "apxpc/wireless/file_sender.hpp"

#include "apxpc/log.hpp"

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

/// UTF-8 路径 → 文件名（同时兼容 / 与 \）
std::string baseName(const std::string& path) {
    const size_t k = path.find_last_of("/\\");
    return (k == std::string::npos) ? path : path.substr(k + 1);
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
        setError("连不上 " + host + ":9512（对端没在收？手机端要开着「无线」）");
        return false;
    }

    bool ok = true;
    // head：u32 名称长度 + 名称 + u64 大小
    {
        const uint32_t nameLen = static_cast<uint32_t>(name.size());
        uint8_t head[12];
        for (int i = 0; i < 4; ++i) head[i] = static_cast<uint8_t>((nameLen >> (8 * i)) & 0xFF);
        for (int i = 0; i < 8; ++i) head[4 + i] = static_cast<uint8_t>((size >> (8 * i)) & 0xFF);
        ok = sendAll(s, head, 4) && sendAll(s, reinterpret_cast<const uint8_t*>(name.data()), name.size()) &&
             sendAll(s, head + 4, 8);
    }

    std::vector<uint8_t> buf(32 * 1024);
    uint64_t sent = 0;
    while (ok) {
        const size_t n = std::fread(buf.data(), 1, buf.size(), fp);
        if (n == 0) break;
        if (!sendAll(s, buf.data(), n)) {
            ok = false;
            break;
        }
        sent += n;
        if (size > 0 && onProgress) {
            onProgress(static_cast<int>(std::min<uint64_t>(100, sent * 100 / size)));
        }
    }
    std::fclose(fp);
    closeSock(s);

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
