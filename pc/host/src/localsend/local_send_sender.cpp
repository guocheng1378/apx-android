#include "apxpc/localsend/local_send_sender.hpp"

#include "apxpc/localsend/sha256.hpp"
#include "apxpc/log.hpp"
#include "apxpc/net/json.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <sys/time.h>
#include <unistd.h>
#endif

namespace apxpc::localsend {
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

// 路径常量：与 Android 端 LocalSendProtocol 逐字符一致（那边是 Kotlin 字符串常量，
// 这里再抄一遍而不是引用接收端的 —— 发送端不该依赖接收端的头文件，两个方向各留一份常量
// 才符合「一处改动不影响另一方向」的原则；真值仍以官方 API 路径为准）。
constexpr const char* kPathInfo = "/api/localsend/v2/info";
constexpr const char* kPathRegister = "/api/localsend/v2/register";
constexpr const char* kPathPrepare = "/api/localsend/v2/prepare-upload";
constexpr const char* kPathUpload = "/api/localsend/v2/upload";
constexpr const char* kPathCancel = "/api/localsend/v2/cancel";

std::string lowerImpl(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string hostNameImpl() {
    char buf[256] = {0};
    if (::gethostname(buf, sizeof(buf) - 1) == 0 && buf[0] != '\0') return std::string(buf);
    return "APX-PC";
}

std::string jsonStr(const apxpc::net::Json& j, const char* key, const std::string& def) {
    const apxpc::net::Json* v = j.find(key);
    return (v && v->isString()) ? v->asString() : def;
}

// ---------------------------------------------------------------- 极简 HTTP 客户端
// 为什么自己写：官方端点只有五个、全是请求-响应、无 chunked 响应，用 libcurl 会把
// 一个静态库依赖塞进三端构建（Windows 还要额外分发 DLL）。这里 200 行足够，且与
// 接收端的读缓冲策略同源（先读到 \r\n\r\n 再按 Content-Length 取 body）。

struct HttpResponse {
    bool ok = false;       // 连接 / 收发层面是否走完（不含状态码判断）
    int status = 0;
    std::string body;
    std::string error;
};

struct HttpRequest {
    std::string method;
    std::string host;
    uint16_t port = 0;
    std::string path;                       // 含 query
    std::string contentType;                // 空 = 无 body
    const std::string* body = nullptr;      // JSON 请求体（内存）
    std::FILE* stream = nullptr;            // 流式请求体（upload）；与 body 二选一
    uint64_t streamLen = 0;
    int timeoutMs = 10'000;                 // 连接 / 单次读写的等待上限

    // 进度回调：每写出一块调一次；返回 false 表示调用方要求中止
    std::function<bool(uint64_t)> onWrite;
};

int waitWritable(Sock s, int timeoutMs) {
    fd_set wfds;
    FD_ZERO(&wfds);
    FD_SET(s, &wfds);
    timeval tv{};
    tv.tv_sec = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;
#if defined(_WIN32)
    return ::select(0, nullptr, &wfds, nullptr, &tv);
#else
    return ::select(s + 1, nullptr, &wfds, nullptr, &tv);
#endif
}

int waitReadable(Sock s, int timeoutMs) {
    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(s, &rfds);
    timeval tv{};
    tv.tv_sec = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;
#if defined(_WIN32)
    return ::select(0, &rfds, nullptr, nullptr, &tv);
#else
    return ::select(s + 1, &rfds, nullptr, nullptr, &tv);
#endif
}

bool sendAll(Sock s, const char* p, size_t n, int timeoutMs, std::string* err) {
    size_t off = 0;
    while (off < n) {
        // 不直接靠阻塞 send：对端网络差时 send 会长时间挂住，超时由 select 负责
        const int wr = waitWritable(s, timeoutMs);
        if (wr == 0) { if (err) *err = "发送超时"; return false; }
        if (wr < 0) { if (err) *err = "发送失败"; return false; }
        const int r = ::send(s, p + off, static_cast<int>(std::min<size_t>(n - off, 1u << 20)), 0);
        if (r <= 0) { if (err) *err = "连接被对端关闭"; return false; }
        off += static_cast<size_t>(r);
    }
    return true;
}

bool connectTo(const std::string& host, uint16_t port, int timeoutMs, Sock* out, std::string* err) {
#ifdef _WIN32
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    const std::string portStr = std::to_string(port);
    if (::getaddrinfo(host.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
        if (err) *err = "无法解析地址：" + host;
        return false;
    }
    Sock s = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (s == kBadSock) { ::freeaddrinfo(res); if (err) *err = "创建 socket 失败"; return false; }
    u_long nb = 1;
    ::ioctlsocket(s, FIONBIO, &nb);
    const int rc = ::connect(s, res->ai_addr, static_cast<int>(res->ai_addrlen));
    ::freeaddrinfo(res);
    if (rc != 0 && ::WSAGetLastError() != WSAEWOULDBLOCK) {
        closeSock(s); if (err) *err = "连接对端失败"; return false;
    }
#else
    // 直连 IPv4 地址时不必走 getaddrinfo；填 ip_mreq 同理，避免 DNS 依赖
    sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port);
    if (::inet_pton(AF_INET, host.c_str(), &sa.sin_addr) != 1) {
        // 主机名（如 mDNS 名）时才解析
        addrinfo hints{};
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* res = nullptr;
        const std::string portStr = std::to_string(port);
        if (::getaddrinfo(host.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
            if (err) *err = "无法解析地址：" + host;
            return false;
        }
        std::memcpy(&sa, res->ai_addr, sizeof(sockaddr_in));
        ::freeaddrinfo(res);
    }
    Sock s = ::socket(AF_INET, SOCK_STREAM, 0);
    if (s == kBadSock) { if (err) *err = "创建 socket 失败"; return false; }
    // 非阻塞 connect + select：给「连不上」一个确定的时限，而不是靠系统默认 2 分钟
    const int flags = ::fcntl(s, F_GETFL, 0);
    ::fcntl(s, F_SETFL, flags | O_NONBLOCK);
    const int rc = ::connect(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa));
    if (rc != 0 && errno != EINPROGRESS) {
        closeSock(s); if (err) *err = "连接对端失败"; return false;
    }
#endif
    const int wr = waitWritable(s, timeoutMs);
    if (wr <= 0) { closeSock(s); if (err) *err = "连接对端超时"; return false; }
    int soErr = 0;
#if defined(_WIN32)
    int len = sizeof(soErr);
    ::getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&soErr), &len);
    u_long blk = 0;
    ::ioctlsocket(s, FIONBIO, &blk);
#else
    socklen_t len = sizeof(soErr);
    ::getsockopt(s, SOL_SOCKET, SO_ERROR, &soErr, &len);
    ::fcntl(s, F_SETFL, flags);
#endif
    if (soErr != 0) { closeSock(s); if (err) *err = "连接对端失败"; return false; }
    *out = s;
    return true;
}

HttpResponse httpDo(const HttpRequest& req) {
    HttpResponse resp;
    ensureNet();
    Sock s = kBadSock;
    std::string err;
    if (!connectTo(req.host, req.port, req.timeoutMs, &s, &err)) {
        resp.error = err.empty() ? "连接对端失败" : err;
        return resp;
    }

    std::string head = req.method + " " + req.path + " HTTP/1.1\r\nHost: " + req.host + ":" +
                       std::to_string(req.port) + "\r\nConnection: close\r\n";
    if (req.stream) {
        head += "Content-Type: " + (req.contentType.empty()
                                        ? std::string("application/octet-stream")
                                        : req.contentType) +
                "\r\nContent-Length: " + std::to_string(req.streamLen) + "\r\n\r\n";
    } else if (req.body) {
        head += "Content-Type: " + (req.contentType.empty() ? std::string("application/json")
                                                            : req.contentType) +
                "\r\nContent-Length: " + std::to_string(req.body->size()) + "\r\n\r\n";
    } else {
        head += "\r\n";
    }
    if (!sendAll(s, head.data(), head.size(), req.timeoutMs, &err)) {
        closeSock(s);
        resp.error = err;
        return resp;
    }

    if (req.stream) {
        char buf[64 * 1024];
        uint64_t sent = 0;
        while (sent < req.streamLen) {
            const size_t want = static_cast<size_t>(
                std::min<uint64_t>(sizeof buf, req.streamLen - sent));
            const size_t got = std::fread(buf, 1, want, req.stream);
            if (got == 0) { closeSock(s); resp.error = "读取本地文件失败"; return resp; }
            if (!sendAll(s, buf, got, req.timeoutMs, &err)) {
                closeSock(s);
                resp.error = err;
                return resp;
            }
            sent += got;
            if (req.onWrite && !req.onWrite(sent)) {
                closeSock(s);
                resp.error = "已取消";
                return resp;
            }
        }
    } else if (req.body && !req.body->empty()) {
        if (!sendAll(s, req.body->data(), req.body->size(), req.timeoutMs, &err)) {
            closeSock(s);
            resp.error = err;
            return resp;
        }
    }

    // 读响应：先攒到 \r\n\r\n，再按 Content-Length 补齐 body。
    // 没有 Content-Length（204 常见的写法、或对端用 chunked）时按「读到对端关闭」收尾 ——
    // 一开始写成「读一包就收」，结果 204 的响应头与 body 分两次到达时会漏读整个 body；
    // 而长度解析时把行尾的 \r 一起喂给 stoull 也会失败，所以这里按位只取数字。
    std::string raw;
    size_t hdrEnd = std::string::npos;
    size_t contentLen = std::string::npos;
    bool bodyDone = false;
    bool graceUsed = false;
    char buf[16 * 1024];
    while (!bodyDone) {
        int rd = waitReadable(s, req.timeoutMs);
        if (rd == 0 && req.stream && !graceUsed) {
            // upload 的字节已全部写出：对端要收全才落盘回 204，慢机 / 息屏会晚到 ——
            // 首超不判死，再宽限 30s 重读一次（根治「手机已完整收到、PC 报等待响应超时」）
            graceUsed = true;
            rd = waitReadable(s, 30'000);
        }
        if (rd == 0) { resp.error = "等待响应超时"; break; }
        if (rd < 0) { resp.error = "读取响应失败"; break; }
        const int r = ::recv(s, buf, static_cast<int>(sizeof buf), 0);
        if (r <= 0) { bodyDone = true; break; }   // 对端关闭：无长度的响应到此为止
        raw.append(buf, static_cast<size_t>(r));
        if (hdrEnd == std::string::npos) {
            hdrEnd = raw.find("\r\n\r\n");
            if (hdrEnd == std::string::npos && raw.size() > 64 * 1024) {
                resp.error = "响应头过长";
                break;
            }
            if (hdrEnd == std::string::npos) continue;
            const std::string hlow = lowerImpl(raw.substr(0, hdrEnd));
            const std::string key = "content-length:";
            const size_t p = hlow.find(key);
            if (p != std::string::npos) {
                size_t v = p + key.size();
                while (v < hlow.size() && (hlow[v] == ' ' || hlow[v] == '\t')) ++v;
                size_t e = v;
                while (e < hlow.size() && std::isdigit(static_cast<unsigned char>(hlow[e]))) ++e;
                if (e > v) {
                    try { contentLen = static_cast<size_t>(std::stoull(hlow.substr(v, e - v))); }
                    catch (...) { contentLen = std::string::npos; }
                }
            }
        }
        if (hdrEnd != std::string::npos && contentLen != std::string::npos &&
            raw.size() >= hdrEnd + 4 + contentLen) {
            bodyDone = true;
        }
    }
    closeSock(s);

    if (resp.error.empty()) {
        const size_t he = raw.find("\r\n\r\n");
        if (he == std::string::npos) {
            resp.error = "响应格式不合法";
            return resp;
        }
        const std::string statusLine = raw.substr(0, raw.find("\r\n"));
        const size_t sp = statusLine.find(' ');
        if (sp != std::string::npos) {
            try { resp.status = std::stoi(statusLine.substr(sp + 1, 3)); } catch (...) { resp.status = 0; }
        }
        resp.body = raw.substr(he + 4);
        resp.ok = true;
    }
    return resp;
}

}  // namespace

// ============================================================================
// 纯函数
// ============================================================================

std::string LocalSendSender::guessFileType(const std::string& name) {
    // 与接收端 LocalSendReceiver::guessFileType 同表 —— 两个方向对同一文件必须报同一个类型，
    // 否则官方客户端的图标/分类会在两个方向上不一致。
    const size_t dot = name.find_last_of('.');
    const std::string ext = dot == std::string::npos ? std::string() : lowerImpl(name.substr(dot + 1));
    static const char* kImage[] = {"jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg"};
    static const char* kVideo[] = {"mp4", "mkv", "avi", "mov", "webm", "flv", "3gp"};
    static const char* kAudio[] = {"mp3", "flac", "wav", "aac", "ogg", "m4a", "opus"};
    static const char* kText[] = {"txt", "md", "log", "json", "xml", "csv", "html", "pdf"};
    const auto in = [&ext](const char** arr, size_t n) {
        for (size_t i = 0; i < n; ++i)
            if (ext == arr[i]) return true;
        return false;
    };
    if (in(kImage, sizeof(kImage) / sizeof(kImage[0]))) return "image";
    if (in(kVideo, sizeof(kVideo) / sizeof(kVideo[0]))) return "video";
    if (in(kAudio, sizeof(kAudio) / sizeof(kAudio[0]))) return "audio";
    if (in(kText, sizeof(kText) / sizeof(kText[0]))) return "text";
    return "application";
}

std::string LocalSendSender::randomId() {
    static std::mutex m;
    static std::mt19937_64 rng{std::random_device{}()};
    uint64_t a = 0, b = 0;
    {
        std::lock_guard<std::mutex> lk(m);
        a = rng();
        b = rng();
    }
    char buf[48];
    std::snprintf(buf, sizeof buf, "%08x-%04x-%04x-%04x-%012llx",
                  static_cast<unsigned>(a >> 32),
                  static_cast<unsigned>((a >> 16) & 0xffffu),
                  static_cast<unsigned>(a & 0xffffu),
                  static_cast<unsigned>((b >> 48) & 0xffffu),
                  static_cast<unsigned long long>(b & 0xffffffffffffULL));
    return std::string(buf);
}

std::string LocalSendSender::buildPrepareBody(const SenderIdentity& self, const std::string& fingerprint,
                                              const std::vector<std::string>& ids,
                                              const std::vector<OutgoingFile>& files,
                                              const std::vector<std::string>& sha256s,
                                              const std::vector<uint64_t>& sizes) {
    apxpc::net::Json root = apxpc::net::Json::makeObject();
    apxpc::net::Json info = apxpc::net::Json::makeObject();
    const std::string alias = self.alias.empty() ? hostNameImpl() : self.alias;
    info["alias"] = alias;
    info["version"] = std::string("2.2");
    info["deviceModel"] = self.deviceModel.empty() ? alias : self.deviceModel;
    info["deviceType"] = self.deviceType.empty() ? std::string("desktop") : self.deviceType;
    info["fingerprint"] = fingerprint;
    info["download"] = true;
    root["info"] = info;

    apxpc::net::Json fs = apxpc::net::Json::makeObject();
    const size_t n = std::min({ids.size(), files.size(), sha256s.size(), sizes.size()});
    for (size_t i = 0; i < n; ++i) {
        apxpc::net::Json f = apxpc::net::Json::makeObject();
        const std::string name = files[i].name.empty()
                                     ? std::filesystem::path(files[i].path).filename().string()
                                     : files[i].name;
        f["fileName"] = name;
        // size 用 double 承载：Json 内部只有 double（协议里的文件大小远小于 2^53，精度无损）
        f["size"] = static_cast<double>(sizes[i]);
        f["sha256"] = sha256s[i];
        f["fileType"] = guessFileType(name);
        fs[ids[i]] = f;
    }
    root["files"] = fs;
    return root.stringify();
}

void LocalSendSender::parsePrepareResponse(const std::string& body, std::string* sessionId,
                                           std::vector<std::pair<std::string, std::string>>* tokens) {
    if (sessionId) sessionId->clear();
    if (tokens) tokens->clear();
    std::string err;
    const apxpc::net::Json root = apxpc::net::Json::parse(body, &err);
    if (!root.isObject()) return;
    if (sessionId) *sessionId = jsonStr(root, "sessionId", "");
    const apxpc::net::Json* files = root.find("files");
    if (!files || !files->isObject()) return;
    if (!tokens) return;
    for (const auto& kv : files->asObject()) {
        if (!kv.second.isString()) continue;
        tokens->emplace_back(kv.first, kv.second.asString());
    }
}

std::string LocalSendSender::parseRegisterAlias(const std::string& body) {
    std::string err;
    const apxpc::net::Json root = apxpc::net::Json::parse(body, &err);
    return root.isObject() ? jsonStr(root, "alias", "") : std::string();
}

// ============================================================================
// 发送
// ============================================================================

LocalSendSender::LocalSendSender() = default;
LocalSendSender::~LocalSendSender() = default;

SendResult LocalSendSender::send(const Target& target, const SenderIdentity& self,
                                 const std::vector<OutgoingFile>& files,
                                 const ProgressCallback& progress) {
    SendResult res;
    if (target.ip.empty() || target.port == 0) {
        res.error = "目标地址不合法";
        return res;
    }
    if (files.empty()) {
        res.error = "没有要发送的文件";
        return res;
    }
    ensureNet();

    const std::string alias = self.alias.empty() ? hostNameImpl() : self.alias;
    const std::string fp = self.fingerprint.empty() ? randomId() : self.fingerprint;
    const std::string base = "http://" + target.ip + ":" + std::to_string(target.port);

    // ① register：确认对端可达 + 拿它报的 alias
    apxpc::net::Json selfInfo = apxpc::net::Json::makeObject();
    selfInfo["alias"] = alias;
    selfInfo["version"] = std::string("2.2");
    selfInfo["deviceModel"] = self.deviceModel.empty() ? alias : self.deviceModel;
    selfInfo["deviceType"] = self.deviceType.empty() ? std::string("desktop") : self.deviceType;
    selfInfo["fingerprint"] = fp;
    selfInfo["download"] = true;
    const std::string regBody = selfInfo.stringify();

    HttpRequest reg;
    reg.method = "POST";
    reg.host = target.ip;
    reg.port = target.port;
    reg.path = kPathRegister;
    reg.body = &regBody;
    // 10s 默认值在慢机（息屏首包 / 高负载）上过紧 —— register 超时误报在这里拦过一次
    reg.timeoutMs = 30'000;
    const HttpResponse regResp = httpDo(reg);
    if (!regResp.ok) {
        res.error = "连接对端失败：" + regResp.error;
        return res;
    }
    if (regResp.status < 200 || regResp.status > 299) {
        res.error = "对端拒绝登记（HTTP " + std::to_string(regResp.status) + "）";
        return res;
    }
    res.peerAlias = parseRegisterAlias(regResp.body);
    if (res.peerAlias.empty()) res.peerAlias = target.alias;

    // ② 每个文件先算 sha256 与大小：对端可能校验摘要，而 Content-Length 必须准 ——
    //    两件事都要求先把文件完整读一遍。这一步没有进度（大文件会有几秒静默），
    //    但比「先上传再被对端判摘要不符」便宜得多。
    std::vector<std::string> ids, shas;
    std::vector<uint64_t> sizes;
    ids.reserve(files.size());
    shas.reserve(files.size());
    sizes.reserve(files.size());
    uint64_t grandTotal = 0;
    for (const auto& f : files) {
        std::FILE* fp = std::fopen(f.path.c_str(), "rb");
        if (!fp) {
            res.error = "读取失败（文件不存在或没权限）：" + f.path;
            return res;
        }
        Sha256 sha;
        uint64_t total = 0;
        char buf[64 * 1024];
        while (true) {
            const size_t got = std::fread(buf, 1, sizeof buf, fp);
            if (got == 0) break;
            sha.update(buf, got);
            total += got;
        }
        const bool readErr = std::ferror(fp) != 0;
        std::fclose(fp);
        if (readErr) {
            res.error = "读取失败：" + f.path;
            return res;
        }
        ids.push_back(randomId());
        shas.push_back(sha.hex());
        sizes.push_back(total);
        grandTotal += total;
    }

    // ③ prepare-upload
    const std::string prepBody = buildPrepareBody(self, fp, ids, files, shas, sizes);
    HttpRequest prep;
    prep.method = "POST";
    prep.host = target.ip;
    prep.port = target.port;
    prep.path = kPathPrepare;
    prep.body = &prepBody;
    // 同 register：对端可能弹「是否接收」等人工点，10s 必超 —— 放宽到 30s
    prep.timeoutMs = 30'000;
    const HttpResponse prepResp = httpDo(prep);
    if (!prepResp.ok) {
        res.error = "prepare 失败：" + prepResp.error;
        return res;
    }
    if (prepResp.status < 200 || prepResp.status > 299) {
        // 对端可能弹了「是否接收」被人拒了 —— 说清楚是它拒的，不是我们没发
        res.error = "对端拒绝了本次发送（HTTP " + std::to_string(prepResp.status) + "）";
        return res;
    }
    std::string sessionId;
    std::vector<std::pair<std::string, std::string>> tokens;
    parsePrepareResponse(prepResp.body, &sessionId, &tokens);
    if (sessionId.empty()) {
        res.error = "对端未返回 sessionId";
        return res;
    }
    std::map<std::string, std::string> tokenBy;
    for (auto& kv : tokens) tokenBy[kv.first] = kv.second;

    // ④ 逐文件 upload
    uint64_t totalSent = 0;
    for (size_t i = 0; i < files.size(); ++i) {
        const OutgoingFile& f = files[i];
        const std::string name = f.name.empty() ? f.path : f.name;
        const auto tit = tokenBy.find(ids[i]);
        if (tit == tokenBy.end() || tit->second.empty()) {
            res.error = "对端拒绝了文件：" + name;
            return res;
        }
        std::FILE* fp = std::fopen(f.path.c_str(), "rb");
        if (!fp) {
            res.error = "读取失败：" + f.path;
            return res;
        }
        HttpRequest up;
        up.method = "POST";
        up.host = target.ip;
        up.port = target.port;
        up.path = std::string(kPathUpload) + "?sessionId=" + sessionId + "&fileId=" + tit->second;
        up.stream = fp;
        up.streamLen = sizes[i];
        // 大文件慢链路：单次等待放宽，否则一慢就误判超时
        up.timeoutMs = 120'000;
        up.onWrite = [&](uint64_t fileSent) -> bool {
            if (!progress) return true;
            SendProgress pr;
            pr.fileName = name;
            pr.fileBytes = fileSent;
            pr.fileTotal = sizes[i];
            pr.totalBytes = totalSent + fileSent;
            pr.grandTotal = grandTotal;
            pr.filesDone = static_cast<int>(i);
            pr.filesTotal = static_cast<int>(files.size());
            return progress(pr);
        };
        const HttpResponse upResp = httpDo(up);
        std::fclose(fp);

        if (!upResp.ok) {
            // 取消要额外通知对端：不然它会把半截文件当完整文件留着
            if (upResp.error == "已取消") {
                HttpRequest cancel;
                cancel.method = "POST";
                cancel.host = target.ip;
                cancel.port = target.port;
                cancel.path = std::string(kPathCancel) + "?sessionId=" + sessionId + "&fileId=" +
                              tit->second;
                const HttpResponse cr = httpDo(cancel);
                if (!cr.ok) APX_LOGW("通知对端取消失败：{}", cr.error);
                res.error = "已取消";
            } else {
                res.error = "上传中断（" + name + "）：" + upResp.error;
            }
            return res;
        }
        if (upResp.status < 200 || upResp.status > 299) {
            res.error = "上传失败（HTTP " + std::to_string(upResp.status) + "）：" + name;
            return res;
        }
        totalSent += sizes[i];
        res.deliveredNames.push_back(name);
        APX_LOGI("互传已发送 {} → {}（{} 字节）", name,
                 res.peerAlias.empty() ? target.ip : res.peerAlias,
                 static_cast<unsigned long long>(sizes[i]));
    }

    res.ok = true;
    res.bytesSent = totalSent;
    return res;
}

}  // namespace apxpc::localsend
