#include "apxpc/screen/sunshine_detect.hpp"

#include <sys/stat.h>

#include <chrono>
#include <cstdlib>
#include <mutex>

#include "apxpc/net/tcp_probe.hpp"

namespace apxpc::screen {
namespace {

bool pathExists(const std::string& p) {
    struct stat st {};
    return ::stat(p.c_str(), &st) == 0;
}

std::string homeDir() {
    const char* h = std::getenv("HOME");
    return (h && *h) ? std::string(h) : std::string(".");
}

/// 结果缓存窗口（毫秒）。
///
/// 为什么必须缓存（issue#170 第三轮 #1 Mac 面板高占用的服务端半边）：前端状态轮询
/// 每 400ms 一次 /api/state，每次 buildState 都调本函数 —— 而 Sunshine 状态探测
/// 走一次本地 TCP connect（250ms 超时），等于每秒 2.5 次连接尝试挂在 HTTP 线程上，
/// 端口无人监听时（大多数用户没装 Sunshine）纯属空转。装没装 / 跑没跑是秒级不变量，
/// 2s 缓存对「刚装好/刚退出」的感知延迟没有可察觉影响；跨轮询重复探测就此归零。
///
/// 线程安全：panel_http 的 HTTP 线程与 web_panel 调用方共用这份缓存，mutex 保护
/// 「读时刻 → 命中判定 → 探测 → 回填」整段；探测期间持锁意味着其他调用方会等一次
/// （至多 250ms）探测，但调用方本就来自同一条 HTTP 线程，串行等价于原行为。
constexpr int64_t kSunshineCacheMs = 2000;

/// (缓存时刻 steady 毫秒, 缓存结果)；时刻 <0 表示尚未探测过。
int64_t g_cachedAtMs = -1;
SunshineStatus g_cachedStatus;
std::mutex g_cacheMutex;

int64_t steadyMsNow() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

}  // namespace

SunshineStatus sunshineStatus() {
    std::lock_guard<std::mutex> lock(g_cacheMutex);
    if (g_cachedAtMs >= 0 && steadyMsNow() - g_cachedAtMs < kSunshineCacheMs) {
        return g_cachedStatus;
    }
    SunshineStatus s;
    const std::string home = homeDir();

    // 证据一：Sunshine 的家目录配置（mac 与 Linux 同一约定：~/.config/sunshine/）
    const bool conf = pathExists(home + "/.config/sunshine/sunshine.conf") ||
                      pathExists(home + "/.config/sunshine");

    // 证据二：macOS 的 app bundle（/Applications/Sunshine.app）。
    // Linux 上包管理器的安装位置太散（/usr/bin、flatpak…），不猜 —— Linux 只认配置目录，
    // 面板文案里会写清楚「检测不到就按没装处理」。
#if defined(__APPLE__)
    const bool app = pathExists("/Applications/Sunshine.app");
#else
    const bool app = false;
#endif

    // 证据三：默认 https 端口有人监听（Sunshine 默认 47990）。
    // 注意本工程托盘的默认端口恰好也是 47990 —— 所以「有人在听」**单独不构成**
    // 已安装证据（可能是本工程另一个实例），必须与配置/应用存在同真才算「已装且在跑」。
    s.running = apxpc::net::tcpPortRespondsLocal(s.httpsPort, 250);

    s.installed = conf || app;

    // 探测在锁内完成：本函数调用方（HTTP 线程）本就串行，250ms 超时的 connect
    // 也只发生在缓存未命中的第一次；这里不释放锁重入，避免两个线程同时探测。
    g_cachedAtMs = steadyMsNow();
    g_cachedStatus = s;
    return s;
}

std::string sunshineUrl(const SunshineStatus& s) {
    if (!s.running) return {};
    return "https://localhost:" + std::to_string(s.httpsPort);
}

}  // namespace apxpc::screen
