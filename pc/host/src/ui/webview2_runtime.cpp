// WebView2 Runtime 自检测 + 自动安装实现（仅 Windows）。
//
// 下载源按顺序兜底（见 kSources）：
//   1. 官方 fwlink 引导器（~2MB，装完前自带微软进度窗，用户看得见在装什么）
//   2. 离线完整包（~200MB 单次下载到位，引导器二段下载失败时兜底）
// 下载走 WinHTTP（系统自带，无需第三方库）；安装完成与否一律以注册表为准。
#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <winhttp.h>

#include "apxpc/ui/webview2_runtime.hpp"

#include <cwchar>
#include <vector>

#pragma comment(lib, "winhttp")

namespace apxpc::ui {
namespace {

// WebView2 Runtime 在 EdgeUpdate 里的固定客户端 GUID（微软官方文档值）
constexpr wchar_t kWv2Guid[] = L"{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";

struct Wv2Source {
    const wchar_t* host;
    const wchar_t* path;   // path 可带 query
    bool https;
};

const Wv2Source kSources[] = {
    // 引导器：go.microsoft.com/fwlink 会 302 到 msedge CDN 的
    // MicrosoftEdgeWebView2Setup.exe（WinHTTP 自动跟随重定向）
    { L"go.microsoft.com", L"/fwlink/p/?LinkId=2124703", true },
    // 离线完整包（winget 官方清单同款直链，随版本可能失效 → 失败就靠上面的引导器）
    { L"msedge.sf.dl.delivery.mp.microsoft.com",
      L"/filestreamingservice/files/7c7c0e6f-8cb5-406a-8e51-df0c62011e55/"
      L"MicrosoftEdgeWebView2RuntimeInstallerX64.exe", true },
};

/// 下载 src 到 dest。成功返回 true；失败时 err 描述原因。
bool download(const Wv2Source& src, const std::wstring& dest, std::wstring& err) {
    HINTERNET ses = WinHttpOpen(L"AllPeriph/1.0", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!ses) {
        err = L"网络初始化失败(" + std::to_wstring(GetLastError()) + L")";
        return false;
    }
    // 大文件下载：单次读超时给 60s（默认 30s 对慢网不够）
    WinHttpSetTimeouts(ses, 30000, 30000, 60000, 60000);

    bool ok = false;
    HINTERNET con = WinHttpConnect(ses, src.host,
                                   src.https ? INTERNET_DEFAULT_HTTPS_PORT
                                             : INTERNET_DEFAULT_HTTP_PORT, 0);
    if (con) {
        HINTERNET req = WinHttpOpenRequest(con, L"GET", src.path, nullptr,
                                           WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                           src.https ? WINHTTP_FLAG_SECURE : 0);
        if (req) {
            if (WinHttpSendRequest(req, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                   WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
                WinHttpReceiveResponse(req, nullptr)) {
                DWORD status = 0;
                DWORD statusLen = sizeof(status);
                WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                    WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusLen,
                                    WINHTTP_NO_HEADER_INDEX);
                if (status >= 200 && status < 300) {
                    HANDLE file = CreateFileW(dest.c_str(), GENERIC_WRITE, 0, nullptr,
                                              CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
                    if (file != INVALID_HANDLE_VALUE) {
                        ok = true;
                        for (;;) {
                            char buf[65536];
                            DWORD got = 0;
                            if (!WinHttpReadData(req, buf, sizeof(buf), &got)) { ok = false; break; }
                            if (got == 0) break;   // EOF
                            DWORD written = 0;
                            if (!WriteFile(file, buf, got, &written, nullptr) || written != got) {
                                ok = false;
                                break;
                            }
                        }
                        CloseHandle(file);
                        if (!ok) {
                            DeleteFileW(dest.c_str());
                            err = L"下载中断(" + std::to_wstring(GetLastError()) + L")";
                        }
                    } else {
                        err = L"无法写入临时目录";
                    }
                } else {
                    err = L"服务器返回 HTTP " + std::to_wstring(status);
                }
            } else {
                err = L"网络请求失败(" + std::to_wstring(GetLastError()) + L")";
            }
            WinHttpCloseHandle(req);
        } else {
            err = L"网络请求失败(" + std::to_wstring(GetLastError()) + L")";
        }
        WinHttpCloseHandle(con);
    } else {
        err = L"无法连接下载服务器(" + std::to_wstring(GetLastError()) + L")";
    }
    WinHttpCloseHandle(ses);
    if (!ok && err.empty()) err = L"下载失败";
    return ok;
}

/// 运行安装包并等待结束（interactive：带微软自带进度窗；silent：无界面）
void runInstaller(const std::wstring& exe, bool silent) {
    std::wstring cmd = L"\"" + exe + L"\"";
    if (silent) cmd += L" /silent /install";   // 每用户静默安装，无需管理员
    std::vector<wchar_t> buf(cmd.begin(), cmd.end());
    buf.push_back(L'\0');
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(nullptr, buf.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &si, &pi)) {
        return;   // 由调用方以注册表结果判定成败
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
}

}  // namespace

bool isWebView2RuntimeInstalled() {
    static const wchar_t* kClientPaths[] = {
        L"SOFTWARE\\Microsoft\\EdgeUpdate\\Clients",
        L"SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients",
    };
    const HKEY roots[] = { HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE };
    for (HKEY root : roots) {
        for (const wchar_t* base : kClientPaths) {
            const std::wstring path = std::wstring(base) + L"\\" + kWv2Guid;
            wchar_t buf[64] = {};
            DWORD sz = sizeof(buf);
            if (RegGetValueW(root, path.c_str(), L"pv", RRF_RT_REG_SZ, nullptr,
                             buf, &sz) == ERROR_SUCCESS &&
                buf[0] != L'\0' && std::wcscmp(buf, L"0.0.0.0") != 0) {
                return true;
            }
        }
    }
    return false;
}

bool ensureWebView2Runtime(std::wstring& err, bool silent) {
    if (isWebView2RuntimeInstalled()) return true;

    wchar_t tmp[MAX_PATH] = {};
    const DWORD n = GetTempPathW(MAX_PATH, tmp);
    const std::wstring dir = (n > 0 && n < MAX_PATH) ? std::wstring(tmp, n)
                                                     : std::wstring(L".\\");
    const std::wstring dest = dir + L"MicrosoftEdgeWebView2RuntimeInstaller.exe";

    err.clear();
    for (const Wv2Source& src : kSources) {
        if (!download(src, dest, err)) continue;   // 换下一个源
        runInstaller(dest, silent);
        DeleteFileW(dest.c_str());
        if (isWebView2RuntimeInstalled()) return true;
        Sleep(1500);   // 注册表写入可能略滞后于进程退出
        if (isWebView2RuntimeInstalled()) return true;
        if (err.empty()) err = L"安装程序已运行，但未检测到 WebView2 Runtime（可能被系统策略拦截）";
    }
    return false;
}

}  // namespace apxpc::ui
#endif  // _WIN32
