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

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <dbghelp.h>
#include <exception>
#include <cstdio>
#pragma comment(lib, "dbghelp")

namespace {

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

int main(int /*argc*/, char** /*argv*/) {
#if defined(_WIN32)
    ::SetUnhandledExceptionFilter(apxCrashFilter);
    std::set_terminate(apxTerminateHandler);
#endif
    // 开机自启注册表项由托盘写入，命令行不带参数；就算手工带参（例如旧版 CLI 的
    // "serve"）也一律忽略 —— 桌面端永远进面板。
    const int rc = apxpc::ui::runPanel();
    if (rc < 0) {
#if defined(_WIN32)
        MessageBoxW(nullptr, L"控制面板初始化失败（当前平台不支持或窗口创建失败）。",
                    L"全能外设", MB_OK | MB_ICONERROR);
#endif
        return 1;
    }
    return 0;
}
