#include "apxpc/app/service.hpp"
#include "apxpc/api/action_router.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/hotkey/hotkey.hpp"
#include "apxpc/log.hpp"
#include "apxpc/net/http_server.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/wireless/ctrl9511.hpp"

#if defined(_WIN32)
#include <shellapi.h>
#else
#include <unistd.h>
#endif

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>

namespace apxpc::app {
namespace {

std::atomic<bool> g_running{true};

#if defined(_WIN32)
BOOL WINAPI consoleCtrl(DWORD) { g_running = false; return TRUE; }
#else
void sigInt(int) { g_running = false; }
#endif

// 本机名，用作 9511 信标名称（手机端设备列表据此识别本机）
std::string localHostName() {
    char buf[256]{};
#if defined(_WIN32)
    DWORD n = sizeof(buf);
    if (GetComputerNameA(buf, &n) && n > 0) return std::string(buf, n);
#else
    if (::gethostname(buf, sizeof(buf)) == 0 && buf[0] != '\0') return buf;
#endif
    return "PC";
}

// —— 入站防火墙放行（仅 Windows）——
// 手机连入本机 9511 是「入站」流量；本机网卡若为 Public(公用)网络，Windows 默认拦截入站，
// 导致手机列表能看到本机却「连不上」。这里在首次启动、且尚未有规则时，提权(netsh runas)
// 加一条放行 apxhost.exe 的入站规则（TCP 9511 + UDP 9501），免去手动配置。
// 规则已存在则跳过；提权被拒仅告警、不阻断面板。
#if defined(_WIN32)
bool firewallRuleExists(const char* name) {
    std::string cmd = std::string("netsh advfirewall firewall show rule name=\"") + name + "\"";
    FILE* f = _popen(cmd.c_str(), "r");
    if (!f) return false;
    char buf[512];
    bool found = false;
    while (std::fgets(buf, sizeof(buf), f)) {
        if (std::string(buf).find(name) != std::string::npos) { found = true; break; }
    }
    _pclose(f);
    return found;
}
void ensureCtrlFirewallRule() {
    const char* ruleName = "AllPeriph apxhost ctrl";
    if (firewallRuleExists(ruleName)) return;
    char exePath[MAX_PATH] = {0};
    if (GetModuleFileNameA(nullptr, exePath, MAX_PATH) == 0) return;
    std::string params = "advfirewall firewall add rule name=\"";
    params += ruleName;
    params += "\" dir=in action=allow program=\"";
    params += exePath;
    params += "\" profile=private,public";
    HINSTANCE r = ShellExecuteA(nullptr, "runas", "netsh", params.c_str(), nullptr, SW_HIDE);
    if ((INT_PTR)r <= 32)
        APX_LOGW("防火墙入站规则添加失败（需管理员授权）；若手机连不上本机 9511，请手动放行 apxhost.exe（TCP 9511 / UDP 9501）");
    else
        APX_LOGI("已为 apxhost.exe 添加防火墙入站放行规则（手机可控本机）");
}
#endif

// 静态资源提供器：从磁盘读取 web 目录（开发期 APXPC_WEB_DEV_DIR）
net::StaticProvider makeFileProvider(const std::string& root) {
    if (root.empty()) return nullptr;
    return [root](const std::string& rel, std::string& out, std::string& ctype) -> bool {
        std::filesystem::path p = std::filesystem::path(root) / rel;
        // 防目录穿越
        if (p.string().find("..") != std::string::npos) return false;
        std::error_code ec;
        if (!std::filesystem::is_regular_file(p, ec)) return false;
        std::ifstream f(p, std::ios::binary);
        if (!f) return false;
        out.assign((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        // content type
        auto ext = p.extension().string();
        if (ext == ".html") ctype = "text/html; charset=utf-8";
        else if (ext == ".css") ctype = "text/css; charset=utf-8";
        else if (ext == ".js") ctype = "application/javascript; charset=utf-8";
        else if (ext == ".json") ctype = "application/json";
        else if (ext == ".svg") ctype = "image/svg+xml";
        else if (ext == ".png") ctype = "image/png";
        else ctype = "application/octet-stream";
        return true;
    };
}

void openBrowser(const std::string& url) {
#if defined(_WIN32)
    ShellExecuteA(nullptr, "open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
#else
    (void)url;
#endif
}

// 零配置定位前端目录：优先 exe 同级的 web/，再逐级向上寻找 pc/host/web 或 web。
// 目的是让用户直接运行 apxhost.exe ui 即可，无需设置环境变量。
std::string findWebRoot() {
    auto hasIndex = [](const std::filesystem::path& dir) {
        std::error_code ec;
        return std::filesystem::is_regular_file(dir / "index.html", ec);
    };

    std::filesystem::path exeDir;
#if defined(_WIN32)
    char buf[MAX_PATH] = {0};
    if (GetModuleFileNameA(nullptr, buf, MAX_PATH) > 0)
        exeDir = std::filesystem::path(buf).parent_path();
#endif
    if (exeDir.empty()) return {};

    // 1) exe 同级 web/
    if (hasIndex(exeDir / "web")) return (exeDir / "web").string();

    // 2) 逐级向上寻找 <ancestor>/pc/host/web 或 <ancestor>/web
    std::filesystem::path up = exeDir;
    for (int i = 0; i < 6 && !up.empty(); ++i) {
        if (hasIndex(up / "pc" / "host" / "web")) return (up / "pc" / "host" / "web").string();
        if (hasIndex(up / "web")) return (up / "web").string();
        up = up.parent_path();
    }
    return {};
}

}  // namespace

int runService(const ServiceOptions& opt) {
    // 配置
    std::string cfgPath = opt.configPath.empty() ? config::defaultConfigPath() : opt.configPath;
    auto cfg = config::loadConfig(cfgPath);
    if (opt.httpPort != 0) cfg.httpPort = opt.httpPort;

    // 日志级别
    if (cfg.logLevel == "debug") Logger::instance().setLevel(LogLevel::Debug);
    else if (cfg.logLevel == "warn") Logger::instance().setLevel(LogLevel::Warn);
    else if (cfg.logLevel == "error") Logger::instance().setLevel(LogLevel::Error);
    else Logger::instance().setLevel(LogLevel::Info);

    APX_LOGI("AllPeriph 宿主服务启动（模式={} 端口={}）", cfg.mode.c_str(), cfg.httpPort);

    // 动作路由
    auto router = std::make_shared<api::ActionRouter>();
    router->setConfigPath(cfgPath);
    router->config() = cfg;

    // v1.11：无线配对/数据通道随无线功能移除

    // HTTP 服务
    net::HttpServer server;
    net::HttpServer::Options sopt;
    sopt.port = cfg.httpPort;
    std::string webRoot = opt.webRoot.empty() ? findWebRoot() : opt.webRoot;
    if (webRoot.empty())
        APX_LOGW("未找到前端静态资源目录（用 APXPC_WEB_DEV_DIR 可显式指定），面板将仅提供 API");
    else
        APX_LOGI("前端静态资源目录：{}", webRoot.c_str());
    sopt.staticProvider = makeFileProvider(webRoot);
    server.setHandler([&](const net::HttpRequest& req, net::HttpResponse* res) {
        router->handle(req, res);
    });
    if (!server.start(sopt)) {
        APX_LOGE("HTTP 服务启动失败");
        return 2;
    }
    uint16_t port = server.actualPort();

    // 9511 受控端：让本机可被手机/TV 经 Wi‑Fi 控制（同时广播 APX1PC 信标供发现）。
    // 与独立 `apxhost ctrl9511-serve` 同源；端口被占用（如另开 serve）时优雅降级，不阻断面板。
#if defined(_WIN32)
    ensureCtrlFirewallRule();
#endif
    const std::string ctrlName = localHostName();
    apxpc::wireless::Ctrl9511Server ctrlSrv;
    if (!ctrlSrv.start(9511, "", ctrlName))
        APX_LOGW("9511 受控端启动失败（端口 9511 可能被其他 apxhost 占用）：{}",
                 ctrlSrv.status().error.c_str());
    else
        APX_LOGI("9511 受控端已启动（名称 {}）：手机/TV 选本机即可控 PC", ctrlName.c_str());

    // 全局热键
    std::shared_ptr<hotkey::HotkeyManager> hk;
    if (opt.enableHotkey) {
        hk = std::make_shared<hotkey::HotkeyManager>();
        router->setHotkeyManager(hk);
        hotkey::ActionMap amap;
        auto fire = [&](const char* name) { net::Json dummy; net::Json out; router->act(name, dummy, out); };
        amap[hotkey::ActionId::ScreenToggle]   = [&] { fire("screen.toggle"); };
        amap[hotkey::ActionId::ScreenCycle]    = [&] { fire("screen.cycle"); };
        amap[hotkey::ActionId::TouchpadToggle] = [&] { fire("touchpad.toggle"); };
        amap[hotkey::ActionId::SensorToggle]   = [&] { fire("sensor.toggle"); };

        amap[hotkey::ActionId::AudioRoute]     = [&] { fire("audio.route"); };
        amap[hotkey::ActionId::DeviceToggle]   = [&] { fire("device.toggle"); };
        std::string err;
        std::map<hotkey::ActionId, hotkey::Binding> hkBindings;
        for (const auto& [id, b] : cfg.hotkeys) {
            hotkey::ActionId aid = hotkey::actionFromName(id);
            hkBindings[aid] = {b.mods, b.key, b.enabled};
        }
        hk->start(amap, hkBindings, &err);
    }

    // 托盘
    std::shared_ptr<tray::TrayIcon> tray;
    if (opt.enableTray) {
        tray = std::make_shared<tray::TrayIcon>();
        tray->setPort(port);
        tray->setOpenCallback([port] { openBrowser("http://127.0.0.1:" + std::to_string(port)); });
        tray->setQuitCallback([] { g_running = false; });
        tray->create("全能外设 · 控制中枢");
    }

    // 开机自启
    if (cfg.autostart) tray::setAutostart(true);

    // 打开浏览器
    if (opt.autoOpenBrowser) openBrowser("http://127.0.0.1:" + std::to_string(port));

#if defined(_WIN32)
    SetConsoleCtrlHandler(consoleCtrl, TRUE);
#else
    std::signal(SIGINT, sigInt);
#endif

    APX_LOGI("控制面板：http://127.0.0.1:{}", port);

    // 状态广播循环（1Hz）
    auto last = std::chrono::steady_clock::now();
    while (g_running) {
        auto now = std::chrono::steady_clock::now();
        auto dt = std::chrono::duration_cast<std::chrono::milliseconds>(now - last).count();
        if (dt >= 1000) {
            last = now;
            auto state = router->buildState();
            server.broadcastEvent("state", state.stringify());
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    APX_LOGI("正在停止服务……");
    if (hk) hk->stop();
    if (tray) tray->quit();
    server.stop();
    // 退出时持久化最新配置
    config::saveConfig(cfgPath, router->config());
    APX_LOGI("服务已退出");
    return 0;
}

}  // namespace apxpc::app
