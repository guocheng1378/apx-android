// 「设置…」二级窗口 —— 见 settings_win32.hpp 顶部注释（为什么单独开窗、怎么与面板连接）。
//
// 用**原生控件**而不是自绘：主面板本来就已经混用了原生 EDIT / COMBOBOX，风格一致；
// 而且原生控件不需要自己处理键盘焦点、IME、无障碍，出错面小得多。
#if defined(_WIN32)

// 必须在 include <windows.h> 之前定义：否则 IDC_ARROW / IDCANCEL 这些宏展开成 ANSI 版本，
// LoadCursorW 会报"无法把 LPSTR 转成 LPCWSTR"（panel_win32.cpp 里也是这么处理的）。
#if !defined(_UNICODE)
#define _UNICODE
#endif
#if !defined(UNICODE)
#define UNICODE
#endif

#include "apxpc/ui/settings_win32.hpp"

#include "apxpc/log.hpp"

#include <cstdio>
#include <string>

namespace apxpc::ui {
namespace {

constexpr const wchar_t* kClassName = L"AllPeriphSettings";

// 控件 ID
constexpr int kIdAdaptive = 1001;
constexpr int kIdLogLevel = 1002;
constexpr int kIdExport   = 1003;
constexpr int kIdRescan   = 1004;
constexpr int kIdResetHk  = 1005;
constexpr int kIdEncoder  = 1006;   // 只读静态文本
constexpr int kIdHotkey   = 1007;   // 只读静态文本
constexpr int kIdClose    = IDCANCEL;

const char* const kLogLevels[] = {"debug", "info", "warn", "error"};

struct Ctx {
    HWND hwnd = nullptr;
    SettingsCallbacks cb;
    HFONT font = nullptr;      // 正文字体（系统 GUI 字体，与面板一致性够用）
    UINT  dpi = 96;
};

Ctx g_ctx;   // 单例窗口状态（同一时刻只允许一个设置窗口）

int S(int v, UINT dpi) { return MulDiv(v, static_cast<int>(dpi), 96); }

std::wstring toWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

HWND mk(Ctx& c, const wchar_t* cls, const wchar_t* text, DWORD style,
        int x, int y, int w, int h, int id = 0) {
    HWND child = ::CreateWindowExW(
        0, cls, text, WS_CHILD | WS_VISIBLE | style,
        S(x, c.dpi), S(y, c.dpi), S(w, c.dpi), S(h, c.dpi),
        c.hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),
        ::GetModuleHandleW(nullptr), nullptr);
    if (child && c.font) ::SendMessageW(child, WM_SETFONT, reinterpret_cast<WPARAM>(c.font), TRUE);
    return child;
}

void setText(HWND h, const std::string& utf8) {
    if (h) ::SetWindowTextW(h, toWide(utf8).c_str());
}

void buildChildren(Ctx& c) {
    mk(c, L"STATIC", L"副屏", SS_LEFT, 16, 14, 200, 20);
    HWND adaptive = mk(c, L"BUTTON", L"自适应码率（按网络拥塞实时升降，变化会写日志）",
                       BS_AUTOCHECKBOX | WS_TABSTOP, 16, 38, 430, 20, kIdAdaptive);
    ::SendMessageW(adaptive, BM_SETCHECK, c.cb.adaptive ? BST_CHECKED : BST_UNCHECKED, 0);

    mk(c, L"STATIC", L"日志级别", SS_LEFT, 16, 72, 80, 20);
    HWND combo = mk(c, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_VSCROLL | WS_TABSTOP,
                    100, 68, 140, 200, kIdLogLevel);
    for (const char* lv : kLogLevels) {
        ::SendMessageW(combo, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(toWide(lv).c_str()));
    }
    int sel = 1;
    for (int i = 0; i < 4; ++i)
        if (c.cb.logLevel == kLogLevels[i]) sel = i;
    ::SendMessageW(combo, CB_SETCURSEL, sel, 0);

    mk(c, L"STATIC", L"编码器与链路（实时，只读）", SS_LEFT, 16, 102, 300, 20);
    mk(c, L"STATIC", L"", SS_LEFT, 16, 126, 432, 92, kIdEncoder);

    mk(c, L"BUTTON", L"导出诊断包", BS_PUSHBUTTON | WS_TABSTOP, 16, 226, 130, 26, kIdExport);
    mk(c, L"BUTTON", L"重新扫描设备", BS_PUSHBUTTON | WS_TABSTOP, 156, 226, 130, 26, kIdRescan);

    mk(c, L"STATIC", L"全局热键", SS_LEFT, 16, 266, 200, 20);
    mk(c, L"STATIC", L"", SS_LEFT, 16, 288, 432, 34, kIdHotkey);
    mk(c, L"BUTTON", L"重置默认热键", BS_PUSHBUTTON | WS_TABSTOP, 16, 326, 130, 26, kIdResetHk);

    mk(c, L"BUTTON", L"关闭", BS_DEFPUSHBUTTON | WS_TABSTOP, 348, 360, 96, 28, kIdClose);
}

/// 把面板报上来的编码器/链路状态刷到只读文本上
void refreshInfo(Ctx& c) {
    if (c.cb.encoderInfo) setText(::GetDlgItem(c.hwnd, kIdEncoder), c.cb.encoderInfo());
    if (c.cb.hotkeyNote) setText(::GetDlgItem(c.hwnd, kIdHotkey), c.cb.hotkeyNote());
}

LRESULT CALLBACK settingsProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    Ctx& c = g_ctx;
    switch (msg) {
        case WM_CREATE: {
            c.hwnd = hwnd;
            HDC dc = ::GetDC(hwnd);
            c.dpi = static_cast<UINT>(::GetDeviceCaps(dc, LOGPIXELSX));
            ::ReleaseDC(hwnd, dc);
            if (!c.font) c.font = static_cast<HFONT>(::GetStockObject(DEFAULT_GUI_FONT));
            buildChildren(c);
            refreshInfo(c);
            ::SetTimer(hwnd, 1, 1000, nullptr);
            return 0;
        }
        case WM_TIMER:
            refreshInfo(c);   // 每秒刷新：码率会被 ABR 改，必须实时可见
            return 0;
        case WM_COMMAND: {
            const int id = LOWORD(wp);
            const int code = HIWORD(wp);
            switch (id) {
                case kIdAdaptive:
                    if (c.cb.onAdaptive) {
                        const bool on = ::SendMessageW(::GetDlgItem(hwnd, kIdAdaptive),
                                                       BM_GETCHECK, 0, 0) == BST_CHECKED;
                        c.cb.onAdaptive(on);
                    }
                    return 0;
                case kIdLogLevel:
                    if (code == CBN_SELCHANGE && c.cb.onLogLevel) {
                        const int sel = static_cast<int>(
                            ::SendMessageW(::GetDlgItem(hwnd, kIdLogLevel), CB_GETCURSEL, 0, 0));
                        if (sel >= 0 && sel < 4) c.cb.onLogLevel(kLogLevels[sel]);
                    }
                    return 0;
                case kIdExport:
                    if (c.cb.onExportDiag) c.cb.onExportDiag();
                    return 0;
                case kIdRescan:
                    if (c.cb.onRescan) c.cb.onRescan();
                    return 0;
                case kIdResetHk:
                    if (c.cb.onResetHotkeys) c.cb.onResetHotkeys();
                    return 0;
                case kIdClose:
                    ::PostMessageW(hwnd, WM_CLOSE, 0, 0);
                    return 0;
                default:
                    return 0;
            }
        }
        case WM_CLOSE:
            ::DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            ::KillTimer(hwnd, 1);
            c.hwnd = nullptr;
            return 0;
        default:
            return ::DefWindowProcW(hwnd, msg, wp, lp);
    }
}

void ensureClass() {
    static bool done = false;
    if (done) return;
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = settingsProc;
    wc.hInstance = ::GetModuleHandleW(nullptr);
    wc.hCursor = ::LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_BTNFACE + 1);
    wc.lpszClassName = kClassName;
    ::RegisterClassExW(&wc);
    done = true;
}

}  // namespace

void showSettingsWindow(HWND owner, const SettingsCallbacks& cb) {
    ensureClass();
    if (g_ctx.hwnd) {          // 已开：置前（回调也会刷新，避免用旧状态）
        g_ctx.cb = cb;
        ::SetForegroundWindow(g_ctx.hwnd);
        refreshInfo(g_ctx);
        return;
    }
    g_ctx.cb = cb;

    // 客户区约 460x404（逻辑像素）→ 乘 DPI 换算成窗口尺寸。
    // DPI 必须在**建窗口之前**取（窗口第一次 WM_CREATE 里也要按它摆子控件）。
    UINT dpi = 96;
    if (HDC screen = ::GetDC(nullptr)) {
        dpi = static_cast<UINT>(::GetDeviceCaps(screen, LOGPIXELSX));
        ::ReleaseDC(nullptr, screen);
    }
    g_ctx.dpi = dpi;
    RECT rc{0, 0, S(460, static_cast<int>(dpi)), S(404, static_cast<int>(dpi))};
    const DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU;
    ::AdjustWindowRectEx(&rc, style, FALSE, 0);
    HWND hwnd = ::CreateWindowExW(
        0, kClassName, L"全能外设 · 设置", style, CW_USEDEFAULT, CW_USEDEFAULT,
        rc.right - rc.left, rc.bottom - rc.top, owner, nullptr, ::GetModuleHandleW(nullptr), nullptr);
    if (!hwnd) {
        APX_LOGE("设置窗口创建失败（GetLastError={}）", ::GetLastError());
        return;
    }
    ::ShowWindow(hwnd, SW_SHOW);
    ::UpdateWindow(hwnd);
}

}  // namespace apxpc::ui

#endif  // _WIN32
