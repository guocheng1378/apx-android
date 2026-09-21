#pragma once
// 极简 HTTP/1.1 服务：仅绑定 127.0.0.1，零第三方依赖（winsock）。
// 提供：静态资源(GET) + REST(POST) + 状态流(GET /api/events, text/event-stream)。
// 加固：校验 Origin/Referer 必须为本机来源；POST 一律走同源策略。
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <atomic>
#include <thread>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#endif

namespace apxpc::net {

struct HttpRequest {
    std::string method;
    std::string path;        // 不含 query
    std::string query;       // 不含 '?'
    std::map<std::string, std::string, std::less<>> headers;
    std::string body;
};

struct HttpResponse {
    int status = 200;
    std::string statusText = "OK";
    std::map<std::string, std::string, std::less<>> headers;
    std::string body;
    std::string contentType = "application/json";
};

// 静态资源提供者：返回文件内容；找不到返回 false
using StaticProvider = std::function<bool(const std::string& relPath,
                                           std::string& out, std::string& contentType)>;

class HttpServer {
public:
    struct Options {
        std::string bind = "127.0.0.1";
        uint16_t   port = 47990;   // 0 = 随机，事后由 actualPort() 取
        StaticProvider staticProvider;  // 可选；提供 / 下的静态资源
        bool allowPortIncrement = true; // 端口被占用时自动 +1 直到成功
    };

    HttpServer();
    ~HttpServer();

    // handler 处理非 SSE 请求，负责填充 HttpResponse。
    // SSE 请求 (/api/events) 由本服务内部处理。
    using Handler = std::function<void(const HttpRequest&, HttpResponse*)>;
    void setHandler(Handler h) { handler_ = std::move(h); }

    // 返回 false 表示端口全部被占用
    bool start(const Options& opt);
    void stop();
    uint16_t actualPort() const { return actualPort_; }

    // 向所有已连接的 SSE 客户端推送一条命名事件（data 为 JSON 文本）
    void broadcastEvent(const std::string& event, const std::string& data);

private:
    void worker(SOCKET client);
    bool tryBind(uint16_t port);

    Handler handler_;
    Options opt_;
    SOCKET  listen_ = INVALID_SOCKET;
    uint16_t actualPort_ = 0;
    std::atomic<bool> running_{false};
    std::thread acceptThread_;

    std::mutex sseMu_;
    std::vector<SOCKET> sseSockets_;
};

}  // namespace apxpc::net
