#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/log.hpp"

#if defined(_WIN32)
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#pragma comment(lib, "shell32")
#pragma comment(lib, "user32")
#endif

namespace apxpc::tray {

#if defined(_WIN32)
namespace {
const UINT WM_TRAY = WM_APP + 1;
const UINT IDM_OPEN = 1001;
const UINT IDM_QUIT = 1002;

// UTF-8 → UTF-16（托盘提示等文字走这里，别用逐字节加宽）
std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                                      nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

LRESULT CALLBACK trayWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == WM_TRAY) {
        if (lp == WM_RBUTTONUP) {
            POINT p; GetCursorPos(&p);
            HMENU m = CreatePopupMenu();
            AppendMenu(m, MF_STRING, IDM_OPEN, L"打开控制面板");
            AppendMenu(m, MF_STRING, IDM_QUIT, L"退出");
            SetForegroundWindow(hwnd);
            TrackPopupMenu(m, TPM_RIGHTBUTTON, p.x, p.y, 0, hwnd, nullptr);
            DestroyMenu(m);
        } else if (lp == WM_LBUTTONDBLCLK) {
            // 双击打开面板
            auto* self = reinterpret_cast<TrayIcon*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
            if (self && self->onOpen_) self->onOpen_();
        }
        return 0;
    }
    if (msg == WM_COMMAND) {
        auto* self = reinterpret_cast<TrayIcon*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
        int id = LOWORD(wp);
        if (id == IDM_QUIT && self) { self->quit(); }
        else if (id == IDM_OPEN && self && self->onOpen_) { self->onOpen_(); }
        return 0;
    }
    if (msg == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProc(hwnd, msg, wp, lp);
}

DWORD WINAPI trayThread(LPVOID lp) {
    auto* self = reinterpret_cast<TrayIcon*>(lp);
    MSG msg;
    while (self->running_) {
        if (GetMessage(&msg, nullptr, 0, 0) <= 0) break;
        TranslateMessage(&msg); DispatchMessage(&msg);
    }
    return 0;
}
}  // namespace
#endif

TrayIcon::TrayIcon() = default;
TrayIcon::~TrayIcon() { quit(); }

#if defined(_WIN32)
bool TrayIcon::create(const std::string& tip) {
    WNDCLASS wc{};
    wc.lpfnWndProc = trayWndProc;
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = L"AllPeriphTray";
    RegisterClass(&wc);
    window_ = CreateWindowEx(0, L"AllPeriphTray", L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, nullptr, nullptr);
    if (!window_) return false;
    SetWindowLongPtr(window_, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));

    NOTIFYICONDATA nid{};
    nid.cbSize = sizeof nid;
    nid.hWnd = static_cast<HWND>(window_);
    nid.uID = 1;
    nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    nid.uCallbackMessage = WM_TRAY;
    nid.hIcon = LoadIcon(nullptr, IDI_APPLICATION);
    // tip 是 UTF-8；逐字节"加宽"会把中文变成乱码，必须走 MultiByteToWideChar
    lstrcpyn(nid.szTip, utf8ToWide(tip).c_str(), ARRAYSIZE(nid.szTip));
    Shell_NotifyIcon(NIM_ADD, &nid);

    running_ = true;
    thread_ = CreateThread(nullptr, 0, trayThread, this, 0, &tid_);
    APX_LOGI("托盘图标已创建");
    return true;
}

void TrayIcon::quit() {
    if (!running_) return;
    running_ = false;
    if (window_) {
        NOTIFYICONDATA nid{};
        nid.cbSize = sizeof nid; nid.hWnd = static_cast<HWND>(window_); nid.uID = 1;
        Shell_NotifyIcon(NIM_DELETE, &nid);
        PostMessage(static_cast<HWND>(window_), WM_QUIT, 0, 0);
        DestroyWindow(static_cast<HWND>(window_));
        window_ = nullptr;
    }
    if (thread_) { WaitForSingleObject(thread_, 1000); CloseHandle(thread_); thread_ = nullptr; }
    if (onQuit_) onQuit_();
}

void TrayIcon::setQuitCallback(std::function<void()> cb) { onQuit_ = std::move(cb); }
void TrayIcon::setOpenCallback(std::function<void()> cb) { onOpen_ = std::move(cb); }

#else
bool TrayIcon::create(const std::string&) { return true; }
void TrayIcon::quit() { if (onQuit_) onQuit_(); }
void TrayIcon::setQuitCallback(std::function<void()> cb) { onQuit_ = std::move(cb); }
void TrayIcon::setOpenCallback(std::function<void()> cb) { onOpen_ = std::move(cb); }
#endif

// ---------------------------------------------------------------- 开机自启
bool setAutostart(bool enable, const std::string& exePath, const std::string& args) {
#if defined(_WIN32)
    HKEY hk; std::string path = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    if (RegOpenKeyExA(HKEY_CURRENT_USER, path.c_str(), 0, KEY_SET_VALUE, &hk) != ERROR_SUCCESS) return false;
    if (enable) {
        std::string exe = exePath.empty() ? "" : exePath;
        if (exe.empty()) {
            char buf[MAX_PATH] = {0}; GetModuleFileNameA(nullptr, buf, MAX_PATH);
            exe = buf;
        }
        exe = "\"" + exe + "\"";
        if (!args.empty()) exe += " " + args;
        RegSetValueExA(hk, "AllPeriph", 0, REG_SZ, reinterpret_cast<const BYTE*>(exe.c_str()), static_cast<DWORD>(exe.size() + 1));
    } else {
        RegDeleteValueA(hk, "AllPeriph");
    }
    RegCloseKey(hk);
    return true;
#else
    (void)enable; (void)exePath; return true;
#endif
}

bool autostartEnabled() {
#if defined(_WIN32)
    HKEY hk; std::string path = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    if (RegOpenKeyExA(HKEY_CURRENT_USER, path.c_str(), 0, KEY_QUERY_VALUE, &hk) != ERROR_SUCCESS) return false;
    char buf[256] = {0}; DWORD sz = sizeof buf;
    LONG r = RegQueryValueExA(hk, "AllPeriph", nullptr, nullptr, reinterpret_cast<BYTE*>(buf), &sz);
    RegCloseKey(hk);
    return r == ERROR_SUCCESS;
#else
    return false;
#endif
}

}  // namespace apxpc::tray
