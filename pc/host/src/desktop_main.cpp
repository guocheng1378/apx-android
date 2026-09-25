// 桌面端入口：GUI 子系统（双击无控制台窗口）。
//
// 入口点仍然是 main()，靠链接选项 /ENTRY:mainCRTStartup 抑制控制台 ——
// 这样不必为了 GUI 子系统把主逻辑改成 WinMain（见 CMakeLists 的 apxdesktop 目标）。
//
// 与 CLI（apxhost.exe）的分工：
//   apxhost.exe   命令行 / 常驻服务（Web 面板 · 热键），保留给调试与脚本
//   apxdesktop.exe 桌面端「无线控制中枢」，给日常双击使用
//
// 崩溃取证：GUI 进程的日志/崩溃全被吞（无控制台、无 WER 事件），排查"莫名退出"
// 只能两眼一抹黑 —— 这里在进程级装两道钩子，临终现场写到 exe 同目录：
//   crash.txt   —— 未捕获 C++ 异常（std::terminate）的 what()，直接给出死因
//   crash.dmp   —— SEH 未处理异常的 minidump（可用 WinDbg/cdb 打开看调用栈）
#include "apxpc/ui/panel.hpp"
#include "apxpc/log.hpp"
#include "apxpc/wireless/ctrl9511.hpp"

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <shellapi.h>
#include <dbghelp.h>
#include <exception>
#include <cstdlib>   // _set_invalid_parameter_handler
#include <cstdio>
#include <string>
#pragma comment(lib, "dbghelp")
#pragma comment(lib, "shell32")

namespace {

// —— 入站防火墙放行（仅 Windows，与 host_service.cpp 同款做法）——
// apxdesktop 会 bind UDP 9501 收手机信标；本机网卡若为 Public(公用)，Windows 默认拦入站，
// 没有这条规则状态会永远停在「正在发现」。首次启动提权(netsh runas)放行本 exe 的入站；
// 规则已存在则跳过，提权被拒仅静默、不阻断面板。
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

void ensurePanelFirewallRule() {
    const char* ruleName = "AllPeriph apxdesktop ctrl";
    if (firewallRuleExists(ruleName)) return;
    char exePath[MAX_PATH] = {0};
    if (::GetModuleFileNameA(nullptr, exePath, MAX_PATH) == 0) return;
    std::string params = "advfirewall firewall add rule name=\"";
    params += ruleName;
    params += "\" dir=in action=allow program=\"";
    params += exePath;
    params += "\" profile=private,public";
    ::ShellExecuteA(nullptr, "runas", "netsh", params.c_str(), nullptr, SW_HIDE);
}

/// exe 同目录路径（自启动时工作目录可能是 system32，必须用绝对路径）
bool crashPath(wchar_t* dst, size_t cap, const wchar_t* name) {
    DWORD n = ::GetModuleFileNameW(nullptr, dst, static_cast<DWORD>(cap));
    if (n == 0 || n >= cap) return false;
    wchar_t* slash = wcsrchr(dst, L'\\');
    if (!slash) return false;
    *slash = L'\0';
    if (wcslen(dst) + 1 + wcslen(name) >= cap) return false;
    wcscat_s(dst, cap, L"\\");
    wcscat_s(dst, cap, name);
    return true;
}

void writeCrashText(const char* what) {
    wchar_t path[MAX_PATH]{};
    if (!crashPath(path, MAX_PATH, L"crash.txt")) return;
    // 用纯 Win32 API 写：terminate 时 CRT/堆可能已损坏，fopen/fprintf 会二次崩溃
    HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr,
                             CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    char buf[1024];
    const int n = std::snprintf(buf, sizeof(buf), "terminate: %s\r\n", what ? what : "(no message)");
    if (n > 0) {
        DWORD written = 0;
        ::WriteFile(f, buf, static_cast<DWORD>(n), &written, nullptr);
    }
    ::CloseHandle(f);
}

LONG WINAPI apxCrashFilter(EXCEPTION_POINTERS* ep) {
    wchar_t path[MAX_PATH]{};
    if (crashPath(path, MAX_PATH, L"crash.dmp")) {
        HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr,
                                 CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f != INVALID_HANDLE_VALUE) {
            MINIDUMP_EXCEPTION_INFORMATION mei{::GetCurrentThreadId(), ep, FALSE};
            ::MiniDumpWriteDump(::GetCurrentProcess(), ::GetCurrentProcessId(), f,
                                MiniDumpNormal, &mei, nullptr, nullptr);
            ::CloseHandle(f);
        }
    }
    return EXCEPTION_EXECUTE_HANDLER;
}

/// fail-fast 专用取证：0xC0000409（栈保护 / __fastfail / abort）这类异常**绕过**
/// SetUnhandledExceptionFilter（所以 crash.dmp / crash.txt 都不会生成，只在退出码里留一个
/// -1073740791，什么都查不到）。向量化异常处理（VEH）在 fail-fast 终止进程**之前**一定被
/// 调用，这里把异常码、出错地址、以及栈回溯（附相对主模块的偏移，便于用 map/pdb 定位）
/// 写成 crash_veh.txt。
LONG WINAPI apxVehHandler(EXCEPTION_POINTERS* ep) {
    if (!ep || !ep->ExceptionRecord) return EXCEPTION_CONTINUE_SEARCH;
    const DWORD code = ep->ExceptionRecord->ExceptionCode;
    //                fail-fast  访问违例    非法指令    堆损坏
    if (code != 0xC0000409u && code != 0xC0000005u && code != 0xC000001Du &&
        code != 0xC0000374u)
        return EXCEPTION_CONTINUE_SEARCH;

    wchar_t path[MAX_PATH]{};
    if (crashPath(path, MAX_PATH, L"crash_veh.txt")) {
        HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                 FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f != INVALID_HANDLE_VALUE) {
            const auto base = reinterpret_cast<uintptr_t>(::GetModuleHandleW(nullptr));
            void* frames[40]{};
            const USHORT n = ::CaptureStackBackTrace(0, 40, frames, nullptr);
            wchar_t buf[4096]{};
            int off = std::swprintf(buf, 4096, L"code=0x%08lX addr=%p base=%p frames=%u\r\n",
                                    static_cast<unsigned long>(code),
                                    ep->ExceptionRecord->ExceptionAddress,
                                    reinterpret_cast<void*>(base), n);
            for (USHORT i = 0; i < n && off > 0 && off < 3600; ++i) {
                const auto a = reinterpret_cast<uintptr_t>(frames[i]);
                off += std::swprintf(buf + off, 4096 - off, L"  #%u %p (+0x%llX)\r\n", i,
                                     frames[i],
                                     static_cast<unsigned long long>(a > base ? a - base : 0));
            }
            // swprintf 返回「本来要写多少个」：截断时可能 > 4096，必须夹住再写
            DWORD chars = static_cast<DWORD>(off > 4096 ? 4096 : (off > 0 ? off : 0));
            DWORD written = 0;
            if (chars > 0) ::WriteFile(f, buf, chars * sizeof(wchar_t), &written, nullptr);
            ::CloseHandle(f);
        }
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

/// CRT 非法参数处理器（补取证）：
/// std::thread 自我 join、格式化越界等会走「无效参数 → __fastfail(0xC0000409)」，
/// 而 **fail-fast 绕过 SetUnhandledExceptionFilter**（所以 crash.txt / crash.dmp 都不会生成，
/// WER 也没有记录 —— 只看到一个 0xC0000409 的退出码，无从下手）。这里把
/// 「哪个函数 / 哪个文件哪一行」写到 crash_param.txt。
void apxInvalidParamHandler(const wchar_t* expr, const wchar_t* func, const wchar_t* file,
                            unsigned line, uintptr_t /*reserved*/) {
    wchar_t path[MAX_PATH]{};
    if (crashPath(path, MAX_PATH, L"crash_param.txt")) {
        HANDLE f = ::CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                 FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f != INVALID_HANDLE_VALUE) {
            wchar_t buf[1024]{};
            const int n = std::swprintf(buf, 1024,
                                        L"invalid parameter: expr=%s func=%s file=%s line=%u\r\n",
                                        expr ? expr : L"?", func ? func : L"?",
                                        file ? file : L"?", line);
            if (n > 0) {
                DWORD written = 0;
                ::WriteFile(f, buf, static_cast<DWORD>(n * sizeof(wchar_t)), &written, nullptr);
            }
            ::CloseHandle(f);
        }
    }
}

void apxTerminateHandler() {
    // 取证是否被调用：写文件失败时至少给个肉眼可见的证据
    ::MessageBoxW(nullptr, L"apxTerminateHandler 被调用", L"全能外设·调试", MB_OK | MB_ICONWARNING);
    const char* what = "(unknown exception)";
    if (std::exception_ptr cur = std::current_exception()) {
        try {
            std::rethrow_exception(cur);
        } catch (const std::exception& e) {
            what = e.what();
        } catch (...) {
            what = "(non-std exception)";
        }
    }
    writeCrashText(what);
}

}  // namespace
#endif

// 桌面端自带的 9511 受控服务端：只开 apxdesktop 一个进程，手机也能发现并控制本机
// （不必再另跑 apxhost 常驻）。若 apxhost 已在跑（9511 被占），这里优雅降级、仅告警。
namespace {
apxpc::wireless::Ctrl9511Server g_ctrlServer;
}

int main(int /*argc*/, char** /*argv*/) {
#if defined(_WIN32)
    ::AddVectoredExceptionHandler(1, apxVehHandler);          // fail-fast 唯一能留下现场的地方
    ::SetUnhandledExceptionFilter(apxCrashFilter);
    std::set_terminate(apxTerminateHandler);
    _set_invalid_parameter_handler(apxInvalidParamHandler);   // CRT 参数路径的取证
    ensurePanelFirewallRule();   // 放行入站（收手机信标 + 手机连入 9511）
    {
        char host[64] = {0};
        DWORD hn = sizeof(host);
        if (!::GetComputerNameA(host, &hn) || host[0] == '\0') std::snprintf(host, sizeof(host), "PC");
        if (!g_ctrlServer.start(9511, "", host))
            APX_LOGW("9511 受控端启动失败（端口可能已被 apxhost 占用）；手机控不到本机时请只保留一个");
        else
            APX_LOGI("9511 受控端已启动（桌面端自带）：手机设备列表可选本机控制 PC");
    }
#endif
    // 开机自启注册表项由托盘写入，命令行不带参数；就算手工带参（例如旧版 CLI 的
    // "serve"）也一律忽略 —— 桌面端永远进面板。
    const int rc = apxpc::ui::runPanel();
    g_ctrlServer.stop();
    if (rc < 0) {
#if defined(_WIN32)
        MessageBoxW(nullptr, L"控制面板初始化失败（当前平台不支持或窗口创建失败）。",
                    L"全能外设", MB_OK | MB_ICONERROR);
#endif
        return 1;
    }
    return 0;
}
