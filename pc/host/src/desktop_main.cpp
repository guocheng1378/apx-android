// 桌面端入口：GUI 子系统（双击无控制台窗口）。
//
// 架构 v2（WebView2 面板）：
//   desktop_main 负责三件事：
//     ① 崩溃取证（VEH + UnhandledExceptionFilter + terminate handler —— 面板是 GUI，
//        崩溃时用户只会觉得"手机突然控不了本机"，现场必须落盘到 exe 同目录）
//     ② 基础设施：防火墙放行、9511 受控端注册、TrayIcon、HotkeyManager
//     ③ 创建完整宿主栈（WirelessSession / MediaSession / ScreenPush /
//        AudioCapture / MicBridge / FileReceiver）→ HostBundle → runWebPanel()
//
//   UI 层（pc/web/）通过 IPC postMessage 调宿主方法、400ms tick 推状态 JSON。
//   与 CLI apxhost.exe 的分工：apxhost 只做常驻服务（无 GUI）；apxdesktop 做 GUI + 常驻。
//
// 回退：APXPC_BUILD_UI_WEBVIEW2=OFF 时编译旧 Win32 GDI+ 面板（panel_win32.cpp），
// 入口仍是 runPanel()。旧面板自身在 Panel 构造函数里创建/持有完整宿主栈生命周期。

#include "apxpc/log.hpp"
#include "apxpc/ui/web_panel.hpp"
#include "apxpc/wireless/wireless_session.hpp"
#include "apxpc/wireless/ctrl9511.hpp"
#include "apxpc/wireless/file_receiver.hpp"
#include "apxpc/media/media_session.hpp"
#include "apxpc/media/screen_push.hpp"
#include "apxpc/media/audio_capture.hpp"
#include "apxpc/media/mic_bridge.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/hotkey/hotkey.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/api/action_router.hpp"

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <shellapi.h>
#include <dbghelp.h>
#include <tlhelp32.h>
#include <exception>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#pragma comment(lib, "dbghelp")
#pragma comment(lib, "shell32")
#endif

#include <atomic>
#include <chrono>
#include <filesystem>
#include <memory>
#include <string>
#include <thread>

namespace {

// —————— 防火墙放行（与 host_service.cpp 同款做法）——
// apxdesktop 会 bind UDP 9501 收手机信标；Public 网络 Windows 默认拦入站，
// 没有这条规则状态会永远停在"正在发现"。首次启动提权(netsh runas)放行本 exe。
bool firewallRuleExists(const char* name) {
    std::string cmd = std::string("netsh advfirewall firewall show rule name=\"") + name + "\"";
    FILE* f = _popen(cmd.c_str(), "r");
    if (!f) return false;
    char buf[512]; bool found = false;
    while (std::fgets(buf, sizeof(buf), f)) {
        if (std::string(buf).find(name) != std::string::npos) { found = true; break; }
    }
    _pclose(f);
    return found;
}
void ensurePanelFirewallRule() {
    const char* ruleName = "AllPeriph apxdesktop ctrl";
    if (firewallRuleExists(ruleName)) return;
    char exePath[MAX_PATH] = {0};
    if (::GetModuleFileNameA(nullptr, exePath, MAX_PATH) == 0) return;
    std::string params = "advfirewall firewall add rule name=\"";
    params += ruleName; params += "\" dir=in action=allow program=\"";
    params += exePath; params += "\" profile=private,public";
    ::ShellExecuteA(nullptr, "runas", "netsh", params.c_str(), nullptr, SW_HIDE);
}

// —————— 崩溃取证（与 host_service.cpp 同款做法，见 v1.33 注释）——
bool crashPath(wchar_t* dst, size_t cap, const wchar_t* name) {
    DWORD n = ::GetModuleFileNameW(nullptr, dst, static_cast<DWORD>(cap));
    if (n == 0 || n >= cap) return false;
    wchar_t* slash = wcsrchr(dst, L'\\');
    if (!slash) return false;
    *slash = L'\0';
    if (wcslen(dst) + 1 + wcslen(name) >= cap) return false;
    wcscat_s(dst, cap, L"\\"); wcscat_s(dst, cap, name);
    return true;
}

constexpr int kMaxMods = 160;
struct ModEntry { uintptr_t base = 0; uintptr_t end = 0; char name[48] = {0}; };
ModEntry g_mods[kMaxMods]; int g_modCount = 0;

void snapshotModules() {
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32,
                                             ::GetCurrentProcessId());
    if (snap == INVALID_HANDLE_VALUE) return;
    MODULEENTRY32W me{}; me.dwSize = sizeof(me);
    for (BOOL ok = ::Module32FirstW(snap, &me); ok && g_modCount < kMaxMods;
         ok = ::Module32NextW(snap, &me)) {
        if (me.modBaseSize == 0) continue;
        ModEntry& e = g_mods[g_modCount]; e.name[0] = '\0';
        ::WideCharToMultiByte(CP_UTF8, 0, me.szModule, -1, e.name, sizeof(e.name) - 1, nullptr, nullptr);
        if (e.name[0] == '\0') continue;
        e.base = reinterpret_cast<uintptr_t>(me.modBaseAddr);
        e.end = e.base + me.modBaseSize; ++g_modCount;
    }
    ::CloseHandle(snap);
}

void formatAddr(char* dst, size_t cap, uintptr_t a) {
    for (int i = 0; i < g_modCount; ++i) {
        if (a >= g_mods[i].base && a < g_mods[i].end) {
            std::snprintf(dst, cap, "%s+0x%llX", g_mods[i].name,
                          static_cast<unsigned long long>(a - g_mods[i].base));
            return;
        }
    }
    std::snprintf(dst, cap, "0x%p（运行期加载）", reinterpret_cast<void*>(a));
}

int appendLine(char* buf, size_t cap, int off, const char* line) {
    const int capI = static_cast<int>(cap);
    if (off < 0 || off >= capI - 1) return capI;
    const int n = std::snprintf(buf + off, cap - static_cast<size_t>(off), "%s\r\n", line);
    if (n < 0) return capI;
    return (off + n < capI) ? off + n : capI;
}

void writeCrashText(const char* what) {
    wchar_t path[MAX_PATH]{};
    if (!crashPath(path, MAX_PATH, L"crash.txt")) return;
    HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    char buf[1024];
    const int n = std::snprintf(buf, sizeof(buf), "terminate: %s\r\n", what ? what : "(no message)");
    if (n > 0) { DWORD written = 0; ::WriteFile(f, buf, static_cast<DWORD>(n), &written, nullptr); }
    ::CloseHandle(f);
}

LONG WINAPI apxCrashFilter(EXCEPTION_POINTERS* ep) {
    wchar_t path[MAX_PATH]{};
    if (crashPath(path, MAX_PATH, L"crash.dmp")) {
        HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f != INVALID_HANDLE_VALUE) {
            MINIDUMP_EXCEPTION_INFORMATION mei{::GetCurrentThreadId(), ep, FALSE};
            ::MiniDumpWriteDump(::GetCurrentProcess(), ::GetCurrentProcessId(), f, MiniDumpNormal, &mei, nullptr, nullptr);
            ::CloseHandle(f);
        }
    }
    return EXCEPTION_EXECUTE_HANDLER;
}

LONG WINAPI apxVehHandler(EXCEPTION_POINTERS* ep) {
    if (!ep || !ep->ExceptionRecord) return EXCEPTION_CONTINUE_SEARCH;
    const DWORD code = ep->ExceptionRecord->ExceptionCode;
    if (code != 0xC0000409u && code != 0xC0000005u && code != 0xC000001Du && code != 0xC0000374u)
        return EXCEPTION_CONTINUE_SEARCH;
    wchar_t path[MAX_PATH]{};
    if (crashPath(path, MAX_PATH, L"crash_veh.txt")) {
        HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f != INVALID_HANDLE_VALUE) {
            static char report[8192];
            char line[320], where[160];
            int off = 0;
            const char* en = (code == 0xC0000005u) ? "ACCESS_VIOLATION"
                            : (code == 0xC0000409u) ? "STACK_BUFFER_OVERRUN/__fastfail"
                            : (code == 0xC000001Du) ? "ILLEGAL_INSTRUCTION"
                            : "HEAP_CORRUPTION";
            std::snprintf(line, sizeof(line), "异常：0x%08lX  %s", static_cast<unsigned long>(code), en);
            off = appendLine(report, sizeof(report), off, line);
            formatAddr(where, sizeof(where), reinterpret_cast<uintptr_t>(ep->ExceptionRecord->ExceptionAddress));
            std::snprintf(line, sizeof(line), "出错地址：%s", where);
            off = appendLine(report, sizeof(report), off, line);
            off = appendLine(report, sizeof(report), off, "调用栈（「模块+偏移」配 PDB 定位；crash.dmp 可用 cdb 打开）：");
            void* frames[40]{};
            const USHORT n = ::CaptureStackBackTrace(0, 40, frames, nullptr);
            for (USHORT i = 0; i < n; ++i) {
                formatAddr(where, sizeof(where), reinterpret_cast<uintptr_t>(frames[i]));
                std::snprintf(line, sizeof(line), "  #%-2u %s", i, where);
                off = appendLine(report, sizeof(report), off, line);
            }
            const char bom[3] = {'\xEF', '\xBB', '\xBF'};
            DWORD written = 0;
            ::WriteFile(f, bom, sizeof(bom), &written, nullptr);
            const int len = (off > 0 && off < static_cast<int>(sizeof(report))) ? off : 0;
            if (len > 0) ::WriteFile(f, report, static_cast<DWORD>(len), &written, nullptr);
            ::CloseHandle(f);
        }
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

void apxInvalidParamHandler(const wchar_t* expr, const wchar_t* func, const wchar_t* file, unsigned line, uintptr_t) {
    wchar_t path[MAX_PATH]{};
    if (crashPath(path, MAX_PATH, L"crash_param.txt")) {
        HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f != INVALID_HANDLE_VALUE) {
            wchar_t buf[1024]{};
            const int n = std::swprintf(buf, 1024, L"invalid parameter: expr=%s func=%s file=%s line=%u\r\n",
                                        expr ? expr : L"?", func ? func : L"?", file ? file : L"?", line);
            DWORD written = 0;
            ::WriteFile(f, buf, static_cast<DWORD>(n * sizeof(wchar_t)), &written, nullptr);
            ::CloseHandle(f);
        }
    }
}

void apxTerminateHandler() {
    const char* what = "(unknown exception)";
    if (std::exception_ptr cur = std::current_exception()) {
        try { std::rethrow_exception(cur); }
        catch (const std::exception& e) { what = e.what(); }
        catch (...) { what = "(non-std exception)"; }
    }
    writeCrashText(what);
    wchar_t path[MAX_PATH]{}, txt[MAX_PATH]{};
    crashPath(path, MAX_PATH, L"crash.txt"); crashPath(txt, MAX_PATH, L"crash_stamp");
    wchar_t msg[700]{};
    std::swprintf(msg, 700, L"程序遇到未处理的异常，已退出。\n\n原因：%hs\n\n报告：%s", what, path);
    ::MessageBoxW(nullptr, msg, L"全能外设 · 异常退出", MB_OK | MB_ICONERROR);
}

void crashForensicsSelfCheck() {
    char probe[160];
    formatAddr(probe, sizeof(probe), reinterpret_cast<uintptr_t>(::GetModuleHandleW(nullptr)));
    APX_LOGI("崩溃取证就绪：模块快照 {} 个；地址翻译自检 = {}", g_modCount, probe);
}

}  // namespace

int main(int /*argc*/, char** /*argv*/) {
#if defined(_WIN32)
    ::AddVectoredExceptionHandler(1, apxVehHandler);
    ::SetUnhandledExceptionFilter(apxCrashFilter);
    std::set_terminate(apxTerminateHandler);
    _set_invalid_parameter_handler(apxInvalidParamHandler);
    ensurePanelFirewallRule();
    snapshotModules();
    crashForensicsSelfCheck();
#endif

    // —————— 宿主栈创建 & 注册顺序 ——————
    // 销毁顺序**必须**反过来，以下所有对象都是 unique_ptr：
    //   9511 受控端 → WirelessSession（9511 客户端）→ MediaSession（媒体通道）
    //   → AudioCapture/ScreenPush/MicBridge → FileReceiver → TrayIcon
    // 析构时反向运行，保证"托盘退出 → 所有子系统已停"。

    // 9511 受控端（广播 APX1PC 信标，手机/TV 可选本机控制）
#if defined(_WIN32)
    wchar_t hostW[64] = {};
    DWORD hn = sizeof(hostW);
    if (!::GetComputerNameW(hostW, &hn) || hostW[0] == L'\0') std::wcscpy_s(hostW, L"PC");
    std::string hostUtf8;
    {
        const int n = ::WideCharToMultiByte(CP_UTF8, 0, hostW, -1, nullptr, 0, nullptr, nullptr);
        if (n > 0) { hostUtf8.resize(static_cast<size_t>(n));
                     ::WideCharToMultiByte(CP_UTF8, 0, hostW, -1, hostUtf8.data(), n, nullptr, nullptr); }
    }
    apxpc::wireless::Ctrl9511Server ctrlSrv;
    if (!ctrlSrv.start(9511, "", hostUtf8))
        APX_LOGW("9511 受控端启动失败（端口可能已被 apxhost 占用）");
    else
        APX_LOGI("9511 受控端已启动（桌面端自带）");
#endif

    // 配置
    std::string cfgPath = apxpc::config::defaultConfigPath();
    auto cfg = apxpc::config::loadConfig(cfgPath);
    if (cfg.logLevel == "debug") apxpc::Logger::instance().setLevel(apxpc::LogLevel::Debug);
    else if (cfg.logLevel == "warn") apxpc::Logger::instance().setLevel(apxpc::LogLevel::Warn);
    else if (cfg.logLevel == "error") apxpc::Logger::instance().setLevel(apxpc::LogLevel::Error);
    APX_LOGI("全能外设宿主启动（WebView2 面板）");

    // —— 宿主栈 ——
    auto session     = std::make_unique<apxpc::wireless::WirelessSession>();
    auto media       = std::make_unique<apxpc::media::MediaSession>();
    auto screenPush  = std::make_unique<apxpc::media::ScreenPush>();
    auto audio       = std::make_unique<apxpc::media::AudioCapture>();
    auto micBridge   = std::make_unique<apxpc::media::MicBridge>();
    auto fileRecv    = std::make_unique<apxpc::wireless::FileReceiver>();
    auto tray        = std::make_unique<apxpc::tray::TrayIcon>();
    auto hk          = std::make_shared<apxpc::hotkey::HotkeyManager>();
    auto router      = std::make_shared<apxpc::api::ActionRouter>();
    router->setConfigPath(cfgPath);
    router->config() = cfg;
    router->setHotkeyManager(hk);

    // 托盘：「打开面板」暂不注册（WebView2 窗口句柄在 runWebPanel 内部）
    // runWebPanel 会在退出前把窗口句柄传回给 tray 的 setOpenCallback。
    tray->setQuitCallback([] { ::PostQuitMessage(0); });
    if (!tray->create("全能外设 · WebView2"))
        APX_LOGW("托盘图标创建失败");

    if (cfg.autostart) apxpc::tray::setAutostart(true);

    // FileReceiver 后台监听（9512 端口）—— 收到文件托盘气泡提示
#if defined(_WIN32)
    fileRecv->start(nullptr);  // 桌面端无接收回调，仅记录 + 写日志
#endif

    // 打包进 HostBundle
    apxpc::ui::HostBundle bundle;
    bundle.session      = session.get();
    bundle.fileReceiver = fileRecv.get();
    bundle.media        = media.get();
    bundle.screenPush   = screenPush.get();
    bundle.audio        = audio.get();
    bundle.micBridge    = micBridge.get();
    bundle.tray         = tray.get();
    bundle.hotkey       = hk.get();
    bundle.cfg          = &cfg;
    bundle.configPath   = cfgPath;

    // —— 启动面板 ——
    const int rc = apxpc::ui::runWebPanel(bundle);

    // —— 退出清理（反向顺序）——
    APX_LOGI("宿主栈关闭中…");
    fileRecv->stop();
    audio->stop();
    micBridge->stop();
    screenPush->stop();
    media->disconnect();
    session->stop();
#if defined(_WIN32)
    ctrlSrv.stop();
#endif
    hk->stop();
    tray->quit();
    apxpc::config::saveConfig(cfgPath, cfg);
    return rc;
}
