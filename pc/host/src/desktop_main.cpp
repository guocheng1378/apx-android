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
//   crash.txt      —— 未捕获 C++ 异常（std::terminate）的 what()，直接给出死因
//   crash.dmp      —— SEH 未处理异常的 minidump（可用 WinDbg/cdb 打开看调用栈）
//   crash_veh.txt  —— VEH 抓的 **可读** 现场：异常码人话 + 出错地址/目标地址 + 调用栈，
//                     地址一律翻成「模块名+0x偏移」（启动时先抓模块快照，见下方说明）
//   crash.stamp    —— 本次启动的时刻标记：下次启动据此判断"上次那次运行里有没有崩"
//
// 为什么要主动提示：面板是 GUI（没有控制台），崩溃时用户在手机上只会看到"突然控制不了了"。
// 报告写进 exe 目录里没人会去翻，所以 [reportPreviousCrash] 会在下次启动时弹一次说明。
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
#include <tlhelp32.h>   // 模块快照（崩溃报告里把裸地址翻成「模块+偏移」）
#include <exception>
#include <cstdlib>   // _set_invalid_parameter_handler
#include <cstdio>
#include <cstring>
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

// ———————————————— 模块快照：裸地址 → 「模块名+0x偏移」 ————————————————
//
// 崩溃处理器里**不能**再去枚举/加载模块：CRT 与堆可能已经坏了（正是崩掉的原因），而枚举
// 模块要拿加载器锁 —— 万一崩掉的线程正持有它，报告就永远写不出来（现场全丢）。所以改成
// **进程启动时先抓一份模块表**（安全时刻），处理器只查表 + 格式化 + WriteFile。
//
// 为什么非要模块名：crash_veh.txt 原先只写「相对主模块的偏移」，一旦崩在 ntdll / kernel32 /
// d3d11 / 运行期才加载的模块里，那个数字毫无意义 —— 真机那次 0xC0000005 恰好就落在主模块
// 之外，报告等于没写。
constexpr int kMaxMods = 160;
struct ModEntry {
    uintptr_t base = 0;
    uintptr_t end = 0;
    char name[48] = {0};   // UTF-8，只留文件名（路径太长反而看不清）
};
ModEntry g_mods[kMaxMods];
int g_modCount = 0;

void snapshotModules() {
    g_modCount = 0;
    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32,
                                             ::GetCurrentProcessId());
    if (snap == INVALID_HANDLE_VALUE) return;
    MODULEENTRY32W me{};
    me.dwSize = sizeof(me);
    for (BOOL ok = ::Module32FirstW(snap, &me); ok && g_modCount < kMaxMods;
         ok = ::Module32NextW(snap, &me)) {
        if (me.modBaseSize == 0) continue;
        ModEntry& e = g_mods[g_modCount];
        e.name[0] = '\0';
        ::WideCharToMultiByte(CP_UTF8, 0, me.szModule, -1, e.name, sizeof(e.name) - 1,
                              nullptr, nullptr);
        if (e.name[0] == '\0') continue;   // 名字没转出来就别占位
        e.base = reinterpret_cast<uintptr_t>(me.modBaseAddr);
        e.end = e.base + me.modBaseSize;
        ++g_modCount;
    }
    ::CloseHandle(snap);
}

/// 地址 → 「模块名+0x偏移」；不在启动快照内（运行期才加载的模块）就明确标出来
void formatAddr(char* dst, size_t cap, uintptr_t a) {
    for (int i = 0; i < g_modCount; ++i) {
        if (a >= g_mods[i].base && a < g_mods[i].end) {
            std::snprintf(dst, cap, "%s+0x%llX", g_mods[i].name,
                          static_cast<unsigned long long>(a - g_mods[i].base));
            return;
        }
    }
    std::snprintf(dst, cap, "0x%p（不在启动快照内：运行期加载的模块）",
                  reinterpret_cast<void*>(a));
}

/// 异常码 → 人话（报告第一眼就要能看出"怎么死的"）
const char* exceptionName(DWORD code) {
    switch (code) {
        case 0xC0000005u: return "ACCESS_VIOLATION 访问违例（解了空指针/野指针）";
        case 0xC0000409u: return "STACK_BUFFER_OVERRUN / __fastfail（栈保护、std::thread 自 join 等）";
        case 0xC000001Du: return "ILLEGAL_INSTRUCTION 非法指令";
        case 0xC0000374u: return "HEAP_CORRUPTION 堆已损坏";
        default:          return "未知异常码";
    }
}

/// 往报告缓冲追加一行（带 CRLF）。缓冲满就把 offset 顶到 cap，后续追加自动失效 ——
/// 这样调用方不必每处都判越界（崩溃处理器里越界就等于把现场也一起丢了）。
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
/// 调用，这里把现场写成 crash_veh.txt：异常码（附人话）、出错地址、访问类型与目标地址、
/// 以及调用栈 —— 地址一律经 [formatAddr] 翻成「模块名+0x偏移」，可直接配 map/pdb 定位。
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
            // static：崩溃线程的栈可能所剩无几（栈保护类异常就是栈上出事），别把 8KB 摊在栈上。
            // 崩溃是"一次性"事件，这里不做并发保护。
            static char report[8192];
            char line[320];
            char where[160];
            int off = 0;

            std::snprintf(line, sizeof(line), "异常：0x%08lX  %s",
                          static_cast<unsigned long>(code), exceptionName(code));
            off = appendLine(report, sizeof(report), off, line);

            formatAddr(where, sizeof(where),
                       reinterpret_cast<uintptr_t>(ep->ExceptionRecord->ExceptionAddress));
            std::snprintf(line, sizeof(line), "出错地址：%s", where);
            off = appendLine(report, sizeof(report), off, line);

            // 访问违例再补「读还是写 + 目标地址」：解空指针（目标 0x8 这类）、写已释放内存，
            // 一眼就能分辨 —— 原先这两条信息完全没记。
            if (code == 0xC0000005u && ep->ExceptionRecord->NumberParameters >= 2) {
                formatAddr(where, sizeof(where),
                           static_cast<uintptr_t>(ep->ExceptionRecord->ExceptionInformation[1]));
                std::snprintf(line, sizeof(line), "访问类型：%s  目标地址：%s",
                              ep->ExceptionRecord->ExceptionInformation[0] ? "写入" : "读取", where);
                off = appendLine(report, sizeof(report), off, line);
            }

            std::snprintf(line, sizeof(line), "进程 pid=%lu  线程 tid=%lu  模块快照=%d 个",
                          static_cast<unsigned long>(::GetCurrentProcessId()),
                          static_cast<unsigned long>(::GetCurrentThreadId()), g_modCount);
            off = appendLine(report, sizeof(report), off, line);
            off = appendLine(report, sizeof(report), off,
                             "调用栈（「模块+偏移」配 map/pdb 即可定位；crash.dmp 可用 cdb 打开）：");

            void* frames[40]{};
            const USHORT n = ::CaptureStackBackTrace(0, 40, frames, nullptr);
            for (USHORT i = 0; i < n; ++i) {
                const auto a = reinterpret_cast<uintptr_t>(frames[i]);
                formatAddr(where, sizeof(where), a);
                std::snprintf(line, sizeof(line), "  #%-2u %s  (0x%p)", i, where,
                              reinterpret_cast<void*>(a));
                off = appendLine(report, sizeof(report), off, line);
            }

            // UTF-8 + BOM 落盘：原来写的是 UTF-16，部分编辑器/工具里是乱码，读的人先要猜编码。
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
    // 写没写成都要让用户看见：面板没有控制台，悄悄死掉的话用户只会觉得"手机突然控不了本机"。
    wchar_t path[MAX_PATH]{};
    const bool havePath = crashPath(path, MAX_PATH, L"crash.txt");
    wchar_t msg[700]{};
    std::swprintf(msg, 700, L"程序遇到未处理的异常，已退出。\n\n原因：%hs\n\n报告：%s",
                  what, havePath ? path : L"（写入失败）");
    ::MessageBoxW(nullptr, msg, L"全能外设 · 异常退出", MB_OK | MB_ICONERROR);
}

/// 启动自检：把「本模块基址」翻成「模块名+0x0」留一行日志 —— 取证链路是否可用一眼可见
void crashForensicsSelfCheck() {
    char probe[160];
    formatAddr(probe, sizeof(probe), reinterpret_cast<uintptr_t>(::GetModuleHandleW(nullptr)));
    APX_LOGI("崩溃取证就绪：模块快照 {} 个；地址翻译自检 = {}", g_modCount, probe);
}

/// 上次运行是不是异常退出？是则在启动时**弹一次**说明。
///
/// 判据：崩溃报告文件的修改时间晚于 crash.stamp（每次启动都重写 = 本次运行的开始时刻）。
/// 面板是 GUI、没有控制台，崩了用户唯一感受就是"手机突然控制不了本机了" —— 报告躺在
/// exe 目录里没人会翻，所以这里主动说一声，并把文件路径直接给出来。
void reportPreviousCrash() {
    wchar_t veh[MAX_PATH]{}, txt[MAX_PATH]{}, stamp[MAX_PATH]{};
    if (!crashPath(veh, MAX_PATH, L"crash_veh.txt")) return;
    crashPath(txt, MAX_PATH, L"crash.txt");
    crashPath(stamp, MAX_PATH, L"crash.stamp");

    WIN32_FILE_ATTRIBUTE_DATA fs{};
    const bool hadStamp = ::GetFileAttributesExW(stamp, GetFileExInfoStandard, &fs) != 0;

    const wchar_t* report = nullptr;
    WIN32_FILE_ATTRIBUTE_DATA f{};
    auto crashedLastRun = [&](const wchar_t* p) {
        if (!hadStamp) return false;   // 首次运行（升级上来）：没有参照时刻，不提示
        if (!::GetFileAttributesExW(p, GetFileExInfoStandard, &f)) return false;
        return ::CompareFileTime(&f.ftLastWriteTime, &fs.ftLastWriteTime) > 0;
    };
    if (crashedLastRun(veh)) report = veh;
    else if (crashedLastRun(txt)) report = txt;

    if (report) {
        wchar_t first[300] = L"(读不出报告首行)";
        HANDLE h = ::CreateFileW(report, GENERIC_READ, FILE_SHARE_READ, nullptr,
                                 OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h != INVALID_HANDLE_VALUE) {
            char raw[512]{};
            DWORD got = 0;
            ::ReadFile(h, raw, sizeof(raw) - 1, &got, nullptr);
            ::CloseHandle(h);
            if (got > 0) {
                const size_t n = got < sizeof(raw) ? got : sizeof(raw) - 1;
                raw[n] = '\0';
                if (char* eol = std::strpbrk(raw, "\r\n")) *eol = '\0';
                const char* body = raw;
                if (static_cast<unsigned char>(body[0]) == 0xEF &&
                    static_cast<unsigned char>(body[1]) == 0xBB)
                    body += 3;   // 跳过 UTF-8 BOM
                ::MultiByteToWideChar(CP_UTF8, 0, body, -1, first, 300);
            }
        }
        wchar_t msg[900]{};
        std::swprintf(msg, 900,
                      L"上次运行异常退出 —— 那段时间手机是控制不了本机的。\n\n%s\n\n"
                      L"完整报告（含「模块+偏移」的调用栈）：\n%s",
                      first, report);
        ::MessageBoxW(nullptr, msg, L"全能外设 · 上次运行崩溃过", MB_OK | MB_ICONERROR);
    }

    // 记下本次启动时刻，供下次判断"这次运行里有没有崩"
    HANDLE s = ::CreateFileW(stamp, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                             FILE_ATTRIBUTE_NORMAL, nullptr);
    if (s != INVALID_HANDLE_VALUE) {
        DWORD w = 0;
        ::WriteFile(s, "run", 3, &w, nullptr);
        ::CloseHandle(s);
    }
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
    // 崩溃取证先做两件"趁程序还健康"的事（处理器里不能再枚举模块，见 snapshotModules 说明）：
    //   ① 抓模块表；② 自检 + 留日志（把本模块基址翻成「模块名+0x0」，一眼看出地址翻译可用）。
    snapshotModules();
    crashForensicsSelfCheck();
    {
        char host[64] = {0};
        DWORD hn = sizeof(host);
        if (!::GetComputerNameA(host, &hn) || host[0] == '\0') std::snprintf(host, sizeof(host), "PC");
        if (!g_ctrlServer.start(9511, "", host))
            APX_LOGW("9511 受控端启动失败（端口可能已被 apxhost 占用）；手机控不到本机时请只保留一个");
        else
            APX_LOGI("9511 受控端已启动（桌面端自带）：手机设备列表可选本机控制 PC");
    }
    // 上次崩溃的提示**必须放在受控端启动之后**：提示框是模态的，用户不关它就不会往下走 ——
    // 排在前面的话，"提示框一直开着"期间手机是控不了本机的（等于把被控功能关掉了）。
    reportPreviousCrash();
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
