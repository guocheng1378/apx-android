#include "apxpc/app/service.hpp"
#include "apxpc/api/action_router.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/hotkey/hotkey.hpp"
#include "apxpc/log.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/wireless/ctrl9511.hpp"
#include "apxpc/wireless/verify_code.hpp"

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
// 加一条放行 apxhost.exe 的入站规则（program 级全端口：9511/9502/9512 TCP、9501 与互传 53317 的 UDP 入站），免去手动配置。
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
    // v223.29.32 规则名升版（同 desktop：路径老化按名查重失效 → 强制重建，口径同）
    const char* ruleName = "AllPeriph apxhost ctrl v2";
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
        if (!hk->start(amap, hkBindings, &err)) {
            // 此前返回值与 err 都被丢弃：注册失败（非 Windows 未实现 / 组合键被占用 /
            // 线程创建失败）时用户只看到「按了没反应」，日志里一片空白。改为明确报出原因。
            APX_LOGW("全局热键未启用：{}", err);
        } else {
            APX_LOGI("全局热键已注册 {} 项绑定", hkBindings.size());
        }
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

        // 通知同步接线（0x40/0x42，2026-11-11）：手机控本机时，手机的通知经 9511
        // 受控端 dispatch 到这里 → 托盘气泡。回调跑在 reader 线程，TrayIcon::notify
        // 线程安全；tray 对象活到进程退出（ctrlSrv.stop() 之后才析构），raw 指针无悬挂。
        apxpc::tray::TrayIcon* trayRaw = tray.get();
        ctrlSrv.setOnNotifyPush(
            [trayRaw](const std::string&, const std::string& title,
                      const std::string& text, const std::string& app) {
                if (!trayRaw) return;
                const std::string body = title.empty() ? text
                                         : (text.empty() || title == text ? title : title + "\n" + text);
                if (body.empty()) return;
                // 验证码一键复制（2026-11-11，与桌面壳同口径）：命中即写本机剪贴板并回显；
                // 复制失败不追加提示，气泡只显示原文（不假装复制成功）。
                std::string code;
                std::string bodyShown = body;
                if (apxpc::wireless::copyIfVerifyCode(title, text, &code))
                    bodyShown += "（验证码 " + code + " 已复制）";
                trayRaw->notify(app.empty() ? "手机通知" : app, bodyShown);
            });
        // 消除（0x42）：气泡无法撤回，不接（reader 侧计数记账即可）
        ctrlSrv.setOnNotifyDismiss(nullptr);

        // 查找手机接线（0x38，2026-11-11，能力位 bit2）：托盘菜单点击 → 让已连入的
        // 控制端手机响铃（30s 自动停）。菜单常驻；未连接/对端不支持时 sendFindPhone
        // 返回 false，点了没反应（与桌面壳同口径，不为此动态显隐菜单）。
        apxpc::wireless::Ctrl9511Server* srvRaw = &ctrlSrv;
        tray->setFindPhoneCallback([srvRaw] { if (srvRaw) srvRaw->sendFindPhone(1); });
    }

    // 开机自启
    if (cfg.autostart) tray::setAutostart(true);

#if defined(_WIN32)
    SetConsoleCtrlHandler(consoleCtrl, TRUE);
#else
    std::signal(SIGINT, sigInt);
#endif

    APX_LOGI("已就绪：9511 受控端 + 全局热键 + 托盘（无 Web 界面）");

#if defined(__APPLE__)
    // mac：AppKit 必须占**主线程**（issue#47）——常驻循环由托盘事件泵承担：
    // 菜单「退出」与 SIGINT 都把 g_running 置 false，泵下一拍退出。
    // run() 返回 false = 没有可泵的托盘（未启用或无 GUI 会话下初始化失败），
    // 退回 sleep 循环，服务继续（9511 + 热键不受影响）。
    if (tray) {
        if (!tray->run([] { return g_running.load(); })) {
            while (g_running) std::this_thread::sleep_for(std::chrono::milliseconds(200));
        }
    } else {
        while (g_running) std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
#else
    // 常驻循环：只为持有 9511 受控端与热键（原先这里还按 1Hz 广播 SSE 状态，随 Web 一并去掉）
    while (g_running) std::this_thread::sleep_for(std::chrono::milliseconds(200));
#endif

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
