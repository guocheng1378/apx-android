#include "apxpc/app/service.hpp"
#include "apxpc/api/action_router.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/hotkey/hotkey.hpp"
#include "apxpc/log.hpp"
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

}  // namespace

/**
 * 命令行宿主（`apxhost serve`）。
 *
 * **Web 控制台已整体下线**（用户要求，v117）：这里不再起 HTTP 服务、不再读 web 静态资源、
 * 也不再自动开浏览器。命令行的职责收敛为两件事：
 *   ① 9511 受控端 —— 让本机可被手机 / TV 经 Wi‑Fi 控制（广播 APX1PC 信标供发现）；
 *   ② 全局热键 + 托盘常驻。
 * PC 上的图形界面归桌面端 `apxdesktop`（原生面板），与本程序无关。
 */
int runService(const ServiceOptions& opt) {
    // 配置
    std::string cfgPath = opt.configPath.empty() ? config::defaultConfigPath() : opt.configPath;
    auto cfg = config::loadConfig(cfgPath);

    // 日志级别
    if (cfg.logLevel == "debug") Logger::instance().setLevel(LogLevel::Debug);
    else if (cfg.logLevel == "warn") Logger::instance().setLevel(LogLevel::Warn);
    else if (cfg.logLevel == "error") Logger::instance().setLevel(LogLevel::Error);
    else Logger::instance().setLevel(LogLevel::Info);

    APX_LOGI("AllPeriph 宿主服务启动（模式={}；无 Web 界面）", cfg.mode.c_str());

    // 动作路由：**只**服务全局热键（Web 的 HTTP 入口已删除，见 v117）
    auto router = std::make_shared<api::ActionRouter>();
    router->setConfigPath(cfgPath);
    router->config() = cfg;

    // v1.11：无线配对/数据通道随无线功能移除

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
        // 「打开控制面板」不再是开浏览器（Web 已下线）：命令行宿主没有界面，
        // 这里如实告诉用户 PC 端的图形界面在 `apxdesktop`。
        tray::TrayIcon* trayRaw = tray.get();
        tray->setOpenCallback([trayRaw] {
            if (trayRaw)
                trayRaw->notify("全能外设", "命令行宿主无界面：PC 端请用桌面端「全能外设」（apxdesktop）");
        });
        tray->setQuitCallback([] { g_running = false; });
        tray->create("全能外设 · 控制中枢");
    }

    // 开机自启
    if (cfg.autostart) tray::setAutostart(true);

#if defined(_WIN32)
    SetConsoleCtrlHandler(consoleCtrl, TRUE);
#else
    std::signal(SIGINT, sigInt);
#endif

    APX_LOGI("已就绪：9511 受控端 + 全局热键 + 托盘（无 Web 界面）");

    // 常驻循环：只为持有 9511 受控端与热键（原先这里还按 1Hz 广播 SSE 状态，随 Web 一并去掉）
    while (g_running) std::this_thread::sleep_for(std::chrono::milliseconds(200));

    APX_LOGI("正在停止服务……");
    if (hk) hk->stop();
    if (tray) tray->quit();
    ctrlSrv.stop();
    // 退出时持久化最新配置
    config::saveConfig(cfgPath, router->config());
    APX_LOGI("服务已退出");
    return 0;
}

}  // namespace apxpc::app
