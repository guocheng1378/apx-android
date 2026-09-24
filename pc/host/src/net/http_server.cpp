#include "apxpc/net/http_server.hpp"
#include "apxpc/log.hpp"

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#endif

#include <cstring>
#include <sstream>

namespace apxpc::net {

namespace {

constexpr size_t kMaxHeaderBuf  = 1u << 16;   // 请求头整体上限 64KB
constexpr size_t kMaxHeaderLine = 8u << 10;   // 单行上限 8KB
constexpr int    kMaxSseClients = 64;         // SSE 并发连接上限（D：防资源耗尽）

std::string contentTypeFor(const std::string& path) {
    auto ext = [&](const char* e) { return path.size() > std::strlen(e) &&
        path.compare(path.size() - std::strlen(e), std::strlen(e), e) == 0; };
    if (ext(".html")) return "text/html; charset=utf-8";
    if (ext(".css"))  return "text/css; charset=utf-8";
    if (ext(".js"))   return "application/javascript; charset=utf-8";
    if (ext(".json")) return "application/json; charset=utf-8";
    if (ext(".svg"))  return "image/svg+xml";
    if (ext(".png"))  return "image/png";
    if (ext(".ico"))  return "image/x-icon";
    if (ext(".woff2"))return "font/woff2";
    return "application/octet-stream";
}

bool isLocalOrigin(const std::string& hdr) {
    if (hdr.empty()) return false;
    // 接受 http://127.0.0.1:<port> 或 http://localhost[:port]
    std::string h = hdr;
    // 去掉协议
    auto pos = h.find("://");
    if (pos != std::string::npos) h = h.substr(pos + 3);
    // 去掉 path
    auto slash = h.find('/');
    if (slash != std::string::npos) h = h.substr(0, slash);
    // 去掉端口
    auto colon = h.find(':');
    if (colon != std::string::npos) h = h.substr(0, colon);
    return h == "127.0.0.1" || h == "localhost" || h == "[::1]" || h == "::1";
}

bool recvUntilHeaders(SOCKET s, std::string& buf) {
    char tmp[4096];
    buf.clear();
    while (true) {
        int n = recv(s, tmp, sizeof tmp, 0);
        if (n <= 0) return false;
        buf.append(tmp, static_cast<size_t>(n));
        // B/C 附加：超整体上限或单行过长均视为畸形，直接拒绝（防止慢速/超长头耗尽内存）
        if (buf.size() > kMaxHeaderBuf) return false;
        const auto nl = buf.rfind('\n');
        const size_t curLine = (nl == std::string::npos) ? buf.size() : (buf.size() - nl - 1);
        if (curLine > kMaxHeaderLine) return false;
        if (buf.find("\r\n\r\n") != std::string::npos) return true;
    }
}

bool parseRequest(const std::string& raw, HttpRequest& req) {
    auto lineEnd = raw.find("\r\n");
    if (lineEnd == std::string::npos) return false;
    std::string line = raw.substr(0, lineEnd);
    // METHOD PATH HTTP/1.1
    std::istringstream iss(line);
    std::string ver;
    if (!(iss >> req.method >> req.path >> ver)) return false;
    auto q = req.path.find('?');
    if (q != std::string::npos) { req.query = req.path.substr(q + 1); req.path = req.path.substr(0, q); }
    // headers
    size_t p = lineEnd + 2;
    while (p < raw.size()) {
        auto e = raw.find("\r\n", p);
        if (e == std::string::npos) break;
        std::string hl = raw.substr(p, e - p);
        if (hl.empty()) break;
        auto c = hl.find(':');
        if (c != std::string::npos) {
            std::string k = hl.substr(0, c);
            std::string v = hl.substr(c + 1);
            while (!v.empty() && (v[0] == ' ' || v[0] == '\t')) v.erase(0, 1);
            while (!v.empty() && (v.back() == ' ' || v.back() == '\t')) v.pop_back();
            req.headers[k] = v;
        }
        p = e + 2;
    }
    // body
    auto he = raw.find("\r\n\r\n");
    if (he != std::string::npos) req.body = raw.substr(he + 4);
    return true;
}

void sendAll(SOCKET s, const std::string& data) {
    size_t sent = 0;
    while (sent < data.size()) {
        int n = send(s, data.data() + sent, static_cast<int>(data.size() - sent), 0);
        if (n <= 0) break;
        sent += static_cast<size_t>(n);
    }
}

}  // namespace

HttpServer::HttpServer() = default;
HttpServer::~HttpServer() { stop(); }

bool HttpServer::tryBind(uint16_t port) {
    listen_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listen_ == INVALID_SOCKET) return false;
    int yes = 1;
    setsockopt(listen_, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof yes);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    // E: 必须校验 bind 地址；非法地址（空串/格式错/IPv6）应直接失败，绝不能静默回退到
    // 0.0.0.0，否则会把可注入键鼠的控制面暴露到整个局域网。
    if (inet_pton(AF_INET, opt_.bind.c_str(), &addr.sin_addr) != 1) {
        APX_LOGE("非法 bind 地址 '{}'，绑定中止", opt_.bind.c_str());
        closesocket(listen_); listen_ = INVALID_SOCKET; return false;
    }
    addr.sin_port = htons(port);
    if (bind(listen_, reinterpret_cast<sockaddr*>(&addr), sizeof addr) == SOCKET_ERROR) {
        closesocket(listen_); listen_ = INVALID_SOCKET; return false;
    }
    if (listen(listen_, SOMAXCONN) == SOCKET_ERROR) {
        closesocket(listen_); listen_ = INVALID_SOCKET; return false;
    }
    actualPort_ = port;
    return true;
}

bool HttpServer::start(const Options& opt) {
    opt_ = opt;
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) { APX_LOGE("WSAStartup 失败"); return false; }

    uint16_t p = opt_.port == 0 ? 47990 : opt_.port;
    if (!tryBind(p)) {
        if (!opt_.allowPortIncrement) { APX_LOGE("端口 {} 绑定失败", p); WSACleanup(); return false; }
        uint16_t tries = 0;
        while (!tryBind(p) && tries < 200) { p = static_cast<uint16_t>(p + 1); ++tries; }
        if (listen_ == INVALID_SOCKET) { APX_LOGE("端口递增后仍无法绑定"); WSACleanup(); return false; }
        APX_LOGW("默认端口被占用，改用 {}", actualPort_);
    }
    running_ = true;
    acceptThread_ = std::thread([this]() {
        while (running_) {
            fd_set rfds; FD_ZERO(&rfds); FD_SET(listen_, &rfds);
            timeval tv{1, 0};
            int r = select(0, &rfds, nullptr, nullptr, &tv);
            if (r <= 0) continue;
            SOCKET c = accept(listen_, nullptr, nullptr);
            if (c == INVALID_SOCKET) continue;
            std::thread([this, c]() { worker(c); }).detach();
        }
    });
    APX_LOGI("HTTP 服务已启动 http://{}:{}", opt_.bind.c_str(), actualPort_);
    return true;
}

void HttpServer::stop() {
    if (!running_) return;
    running_ = false;
    if (listen_ != INVALID_SOCKET) { closesocket(listen_); listen_ = INVALID_SOCKET; }
    if (acceptThread_.joinable()) acceptThread_.join();
    // 关闭所有 SSE：用 shutdown 唤醒阻塞在 recv 的 worker，真正的 closesocket 由 worker
    // 自身负责（D2：避免 stop() 与 worker 对同一 fd 双重关闭，否则被内核复用后会误关别人的连接）。
    // worker 退出时会自行从 sseSockets_ 移除并 closesocket。
    {
        std::lock_guard<std::mutex> lk(sseMu_);
        for (auto s : sseSockets_) shutdown(s, SD_BOTH);
    }
    WSACleanup();
}

void HttpServer::worker(SOCKET client) {
    std::string raw;
    if (!recvUntilHeaders(client, raw)) { closesocket(client); return; }
    HttpRequest req;
    if (!parseRequest(raw, req)) { closesocket(client); return; }

    // 同源加固：若带 Origin / Referer，则必须是本机来源；两者都无（如本机 curl）放行
    if (req.method == "POST") {
        std::string origin = req.headers.count("Origin") ? req.headers.at("Origin") : "";
        std::string referer = req.headers.count("Referer") ? req.headers.at("Referer") : "";
        bool originOk = !origin.empty() ? isLocalOrigin(origin)
                      : (!referer.empty() ? isLocalOrigin(referer) : true);
        if (!originOk) {
            APX_LOGW("拒绝非本机来源的 POST: Origin={} Referer={}", origin.c_str(), referer.c_str());
            std::string body = "{\"ok\":false,\"code\":403,\"message\":\"forbidden\"}";
            std::string r = "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: "
                + std::to_string(body.size()) + "\r\nConnection: close\r\n\r\n" + body;
            sendAll(client, r); closesocket(client); return;
        }
    }

    if (req.method == "GET" && req.path == "/api/events") {
        // ---- SSE ----
        std::string origin = req.headers.count("Origin") ? req.headers.at("Origin") : "*";
        std::string hdr = "HTTP/1.1 200 OK\r\n"
            "Content-Type: text/event-stream; charset=utf-8\r\n"
            "Cache-Control: no-cache\r\nConnection: keep-alive\r\n"
            "Access-Control-Allow-Origin: " + (isLocalOrigin(origin) ? origin : "null") + "\r\n\r\n";
        sendAll(client, hdr);
        sendAll(client, ": connected\n\n");
        // D: 连接数上限，防止每连接一个 detached 线程被耗尽线程/句柄
        {
            std::lock_guard<std::mutex> lk(sseMu_);
            if (sseSockets_.size() >= kMaxSseClients) {
                std::string r = "HTTP/1.1 503 Service Unavailable\r\n"
                    "Content-Length: 0\r\nConnection: close\r\n\r\n";
                sendAll(client, r);
                closesocket(client);
                return;
            }
            sseSockets_.push_back(client);
        }
        // D: 给 SSE 连接加收超时，避免本线程永久阻塞在 recv（无数据时也能周期性检查 running_）
        {
            int rcvTmo = 2000;
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO,
                       reinterpret_cast<const char*>(&rcvTmo), sizeof rcvTmo);
        }
        // 阻塞直到客户端断开
        char tmp[1];
        while (running_) {
            int n = recv(client, tmp, 1, 0);
            if (n > 0) continue;             // SSE 单向，客户端发来的数据忽略
            if (n == 0) break;              // 对端正常关闭
            if (WSAGetLastError() == WSAETIMEDOUT) continue;  // 超时，继续轮询 running_
            break;                          // 其它错误
        }
        {
            std::lock_guard<std::mutex> lk(sseMu_);
            for (auto it = sseSockets_.begin(); it != sseSockets_.end(); ++it)
                if (*it == client) { sseSockets_.erase(it); break; }
        }
        closesocket(client);
        return;
    }

    HttpResponse res;
    if (req.path.rfind("/api/", 0) == 0) {
        if (handler_) handler_(req, &res);
        else { res.status = 404; res.body = "{\"ok\":false}"; }
    } else if (opt_.staticProvider) {
        std::string rel = req.path == "/" ? "index.html" : req.path.substr(1);
        std::string out, ctype;
        if (opt_.staticProvider(rel, out, ctype)) {
            res.status = 200; res.body = std::move(out); res.contentType = ctype;
        } else {
            res.status = 404; res.body = "not found"; res.contentType = "text/plain";
        }
    } else {
        res.status = 404; res.body = "not found"; res.contentType = "text/plain";
    }

    std::ostringstream os;
    os << "HTTP/1.1 " << res.status << " " << res.statusText << "\r\n";
    os << "Content-Type: " << res.contentType << "\r\n";
    os << "Content-Length: " << res.body.size() << "\r\n";
    os << "Connection: close\r\n\r\n";
    os << res.body;
    sendAll(client, os.str());
    closesocket(client);
}

void HttpServer::broadcastEvent(const std::string& event, const std::string& data) {
    std::string msg = "event: " + event + "\ndata: " + data + "\n\n";
    std::lock_guard<std::mutex> lk(sseMu_);
    for (auto it = sseSockets_.begin(); it != sseSockets_.end();) {
        int n = send(*it, msg.data(), static_cast<int>(msg.size()), 0);
        if (n <= 0) { closesocket(*it); it = sseSockets_.erase(it); }
        else ++it;
    }
}

}  // namespace apxpc::net
