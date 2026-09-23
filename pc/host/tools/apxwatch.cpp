// apxwatch：极简调试监视器 —— attach 到 apxdesktop，打印崩溃异常与调用栈（带 PDB 符号）。
// 用法：apxwatch.exe <pid> [秒数]
#ifndef PSAPI_VERSION
#define PSAPI_VERSION 1
#endif
#include <windows.h>
#include <psapi.h>
#include <dbghelp.h>
#include <cstdio>
#include <cstring>

#pragma comment(lib, "dbghelp")
#pragma comment(lib, "psapi.lib")

static void printStack(HANDLE hProc, HANDLE hThread, CONTEXT ctx) {
    STACKFRAME64 sf{};
#ifdef _WIN64
    const DWORD machine = IMAGE_FILE_MACHINE_AMD64;
    sf.AddrPC.Offset = ctx.Rip; sf.AddrPC.Mode = AddrModeFlat;
    sf.AddrFrame.Offset = ctx.Rbp; sf.AddrFrame.Mode = AddrModeFlat;
    sf.AddrStack.Offset = ctx.Rsp; sf.AddrStack.Mode = AddrModeFlat;
#else
    const DWORD machine = IMAGE_FILE_MACHINE_I386;
    sf.AddrPC.Offset = ctx.Eip; sf.AddrPC.Mode = AddrModeFlat;
    sf.AddrFrame.Offset = ctx.Ebp; sf.AddrFrame.Mode = AddrModeFlat;
    sf.AddrStack.Offset = ctx.Esp; sf.AddrStack.Mode = AddrModeFlat;
#endif
    for (int i = 0; i < 32; ++i) {
        if (!StackWalk64(machine, hProc, hThread, &sf, &ctx, nullptr,
                         SymFunctionTableAccess64, SymGetModuleBase64, nullptr))
            break;
        const DWORD64 pc = sf.AddrPC.Offset;
        if (pc == 0) break;

        wchar_t symBuf[sizeof(SYMBOL_INFOW) + 256 * sizeof(wchar_t)]{};
        auto* si = reinterpret_cast<SYMBOL_INFOW*>(symBuf);
        si->SizeOfStruct = sizeof(SYMBOL_INFOW);
        si->MaxNameLen = 255;
        DWORD64 disp = 0;
        const bool hasSym = SymFromAddrW(hProc, pc, &disp, si) != FALSE;

        IMAGEHLP_LINEW64 line{};
        line.SizeOfStruct = sizeof(line);
        DWORD lineDisp = 0;
        if (hasSym && SymGetLineFromAddrW64(hProc, pc, &lineDisp, &line))
            wprintf(L"      %s:%lu\n", line.FileName, line.LineNumber);

        IMAGEHLP_MODULEW64 mod{};
        mod.SizeOfStruct = sizeof(mod);
        const DWORD64 base = SymGetModuleBase64(hProc, pc);
        wchar_t modName[MAX_PATH] = L"?";
        if (base && SymGetModuleInfoW64(hProc, base, &mod))
            lstrcpynW(modName, mod.ImageName, MAX_PATH);
        const wchar_t* shortName = wcsrchr(modName, L'\\');
        shortName = shortName ? shortName + 1 : modName;

        wprintf(L"  #%02d %08llX %s!%s+0x%llX\n",
                i, pc, shortName, hasSym ? si->Name : L"?", hasSym ? disp : 0);
    }
}

int main(int argc, char** argv) {
    if (argc < 2) { printf("usage: apxwatch <pid> [seconds]\n"); return 1; }
    const DWORD pid = static_cast<DWORD>(atoi(argv[1]));
    const int secs = argc >= 3 ? atoi(argv[2]) : 120;

    if (!DebugActiveProcess(pid)) {
        printf("attach failed: %u\n", ::GetLastError());
        return 1;
    }
    printf("attached to %u, watching %ds...\n", pid, secs);

    DEBUG_EVENT ev{};
    bool sawCreateProcess = false;
    HANDLE hProc = nullptr;
    const DWORD deadline = ::GetTickCount() + static_cast<DWORD>(secs) * 1000;

    while (true) {
        if (!::WaitForDebugEvent(&ev, 500)) {
            const DWORD err = ::GetLastError();
            if (err == ERROR_SEM_TIMEOUT) {   // 500ms 无事件：继续等
                if (::GetTickCount() > deadline) {
                    printf("timeout (no crash in %ds) - detaching\n", secs);
                    ::DebugActiveProcessStop(pid);
                    return 0;
                }
                continue;
            }
            printf("WaitForDebugEvent failed: %u\n", err);
            break;
        }
        DWORD cont = DBG_CONTINUE;
        bool stop = false;

        switch (ev.dwDebugEventCode) {
            case CREATE_PROCESS_DEBUG_EVENT:
                hProc = ev.u.CreateProcessInfo.hProcess;
                if (!sawCreateProcess) {
                    SymSetOptions(SYMOPT_LOAD_LINES | SYMOPT_UNDNAME);
                    if (!SymInitializeW(hProc, nullptr, TRUE))
                        printf("SymInitializeW failed %u\n", ::GetLastError());
                    sawCreateProcess = true;
                }
                break;

            case EXIT_PROCESS_DEBUG_EVENT:
                printf("process exited, code=%08X\n", ev.u.ExitProcess.dwExitCode);
                stop = true;
                break;

            case EXCEPTION_DEBUG_EVENT: {
                const auto& r = ev.u.Exception.ExceptionRecord;
                // 断点/单步是正常事件，放行
                if (r.ExceptionCode == EXCEPTION_BREAKPOINT && sawCreateProcess && !stop) {
                    if (r.ExceptionCode == EXCEPTION_BREAKPOINT && ev.u.Exception.dwFirstChance) {
                        cont = DBG_CONTINUE;
                        break;
                    }
                }
                printf("EXCEPTION %08X addr=%p tid=%u firstChance=%d\n",
                       r.ExceptionCode, r.ExceptionAddress, ev.dwThreadId,
                       ev.u.Exception.dwFirstChance);
                HANDLE hThread = ::OpenThread(THREAD_ALL_ACCESS, FALSE, ev.dwThreadId);
                if (hThread) {
                    CONTEXT ctx{};
                    ctx.ContextFlags = CONTEXT_FULL;
                    if (::GetThreadContext(hThread, &ctx)) printStack(hProc, hThread, ctx);
                    ::CloseHandle(hThread);
                }
                if (!ev.u.Exception.dwFirstChance) {
                    printf("=== second chance (fatal) ===\n");
                    // fastfail(0xC0000409) 绕过进程内取证，这里由调试器补刀写 minidump
                    HANDLE hThread2 = ::OpenThread(THREAD_ALL_ACCESS, FALSE, ev.dwThreadId);
                    if (hThread2) {
                        CONTEXT ctx{};
                        ctx.ContextFlags = CONTEXT_FULL;
                        if (::GetThreadContext(hThread2, &ctx)) {
                            EXCEPTION_RECORD rec = ev.u.Exception.ExceptionRecord;
                            EXCEPTION_POINTERS eptr{ &rec, &ctx };
                            MINIDUMP_EXCEPTION_INFORMATION mei{ ev.dwThreadId, &eptr, FALSE };
                            HANDLE f = ::CreateFileA("C:\\apx_crash.dmp", GENERIC_WRITE, 0,
                                                     nullptr, CREATE_ALWAYS,
                                                     FILE_ATTRIBUTE_NORMAL, nullptr);
                            if (f != INVALID_HANDLE_VALUE) {
                                ::MiniDumpWriteDump(hProc, pid, f, MiniDumpNormal, &mei,
                                                    nullptr, nullptr);
                                ::CloseHandle(f);
                                printf("dump written: C:\\apx_crash.dmp\n");
                            }
                        }
                        ::CloseHandle(hThread2);
                    }
                    stop = true;
                }
                cont = DBG_EXCEPTION_NOT_HANDLED;
                break;
            }
            case EXIT_THREAD_DEBUG_EVENT:
            case LOAD_DLL_DEBUG_EVENT:
            case UNLOAD_DLL_DEBUG_EVENT:
            default:
                break;
        }

        if (!::ContinueDebugEvent(ev.dwProcessId, ev.dwThreadId, cont)) break;
        if (stop) break;
        if (::GetTickCount() > deadline) {
            printf("timeout (no crash in %ds) - detaching\n", secs);
            ::DebugActiveProcessStop(pid);
            return 0;
        }
    }
    printf("debug loop end\n");
    return 0;
}
