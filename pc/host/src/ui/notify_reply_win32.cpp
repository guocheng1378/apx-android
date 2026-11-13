// 通知回复小窗实现（0x41，2026-11-14，REQ-通知回复.md 第一批）。单文件内含 Win32 真实现
// 与其它平台空桩（同 screenshot_win.cpp 口径，不按平台挑文件，跨端构建零配置）。
// 窗口形态与 ui/remote_input_win32.cpp 同款：单实例 Win32 窗 + 编辑框 + 发送/取消。
// 差异：回复是「一条消息一发」不是增量同步 —— 发送即提交（0x41），成功关窗等 0x43 气泡，
// 失败（未连接/对端不支持）留窗让用户重试，不静默装死。
#include "apxpc/ui/notify_reply.hpp"

#if defined(_WIN32)

#include <windows.h>
#include <windowsx.h>

#include <string>

#pragma comment(lib, "user32")

namespace apxpc::ui {
namespace {

constexpr wchar_t kCls[] = L"AllPeriphNotifyReply";
constexpr int kIdEdit = 2301;
constexpr int kIdSend = 2302;
constexpr int kIdCancel = 2303;

// 单实例窗口：状态放静态（进程级）。submit/notify 回调来自 UI 层持有的会话对象，
// 窗口销毁即一并清空，避免晚到的消息打到已失效的会话上。
HWND gHwnd = nullptr;
HWND gEdit = nullptr;
HWND gTip = nullptr;
int gScale = 1;
std::string gKey;                    // 当前回复绑定的通知键（0x41 发送时原样带回）
std::function<bool(const std::string&, const std::string&)> gSubmit;
std::function<void(const std::string&, const std::string&)> gNotify;

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                                        nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::string wideToUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                                        nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<size_t>(n), '\0');
    ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), s.data(), n,
                          nullptr, nullptr);
    return s;
}

std::string editText() {
    if (!gEdit) return {};
    const int n = ::GetWindowTextLengthW(gEdit);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n) + 1, L'\0');
    ::GetWindowTextW(gEdit, w.data(), n + 1);
    w.resize(static_cast<size_t>(n));
    return wideToUtf8(w);
}

/// 提交：0x41 发出即关窗 —— 终态由手机回 0x43、托盘气泡另行告知（成功/失败各有文案）；
/// 发不动（未连接/对端不支持）留窗重试，托盘气泡给原因，不静默假装成功。
void submitReply() {
    const std::string text = editText();
    if (text.empty() || !gSubmit) return;          // 空回复不发（也省得白占一次 0x43）
    if (gSubmit(gKey, text)) {
        if (gHwnd) ::DestroyWindow(gHwnd);
    } else if (gNotify) {
        gNotify("没发出去", "手机没连上或不支持回复，恢复后再试");
    }
}

void layout(int w, int h) {
    const int m = 12 * gScale;
    const int btnW = 120 * gScale, btnH = 30 * gScale;
    const int tipH = 20 * gScale;
    if (gTip) ::MoveWindow(gTip, m, m, w - 2 * m, tipH, TRUE);
    const int editY = m + tipH + 6 * gScale;
    const int yBtn = h - m - btnH;
    const int editH = yBtn - 8 * gScale - editY;
    if (gEdit) ::MoveWindow(gEdit, m, editY, w - 2 * m, (editH > 40 * gScale) ? editH : 40 * gScale, TRUE);
    int x = w - m - btnW;
    if (HWND b = ::GetDlgItem(gHwnd, kIdCancel)) { ::MoveWindow(b, x, yBtn, btnW, btnH, TRUE); x -= btnW + 8 * gScale; }
    if (HWND b = ::GetDlgItem(gHwnd, kIdSend)) ::MoveWindow(b, x, yBtn, btnW, btnH, TRUE);
}

LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);

bool registerCls(HINSTANCE inst) {
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = wndProc;
    wc.hInstance = inst;
    wc.hCursor = ::LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.lpszClassName = kCls;
    wc.hIcon = ::LoadIconW(nullptr, IDI_APPLICATION);
    wc.hIconSm = wc.hIcon;
    return ::RegisterClassExW(&wc) != 0 || ::GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
}

LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CREATE: {
            gHwnd = hwnd;
            const HINSTANCE inst = reinterpret_cast<HINSTANCE>(
                ::GetWindowLongPtrW(hwnd, GWLP_HINSTANCE));
            gTip = ::CreateWindowExW(0, L"STATIC", L"", WS_CHILD | WS_VISIBLE,
                                     0, 0, 10, 10, hwnd, nullptr, inst, nullptr);
            gEdit = ::CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
                                      WS_CHILD | WS_VISIBLE | WS_BORDER | WS_VSCROLL |
                                          WS_HSCROLL | ES_MULTILINE | ES_AUTOVSCROLL |
                                          ES_AUTOHSCROLL | ES_WANTRETURN,
                                      0, 0, 10, 10, hwnd, reinterpret_cast<HMENU>(kIdEdit),
                                      inst, nullptr);
            ::CreateWindowExW(0, L"BUTTON", L"发送", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
                              0, 0, 10, 10, hwnd, reinterpret_cast<HMENU>(kIdSend), inst, nullptr);
            ::CreateWindowExW(0, L"BUTTON", L"取消", WS_CHILD | WS_VISIBLE,
                              0, 0, 10, 10, hwnd, reinterpret_cast<HMENU>(kIdCancel), inst, nullptr);
            return 0;
        }

        case WM_COMMAND:
            if (HIWORD(wp) == BN_CLICKED) {
                if (LOWORD(wp) == kIdSend) { submitReply(); return 0; }
                if (LOWORD(wp) == kIdCancel) { ::DestroyWindow(hwnd); return 0; }
            }
            break;

        case WM_SIZE:
            layout(LOWORD(lp), HIWORD(lp));
            return 0;

        case WM_KEYDOWN:
            if (wp == VK_ESCAPE) { ::DestroyWindow(hwnd); return 0; }
            break;

        case WM_CLOSE:
            ::DestroyWindow(hwnd);
            return 0;

        case WM_DESTROY:
            gHwnd = nullptr; gEdit = nullptr; gTip = nullptr;
            gKey.clear();
            gSubmit = nullptr; gNotify = nullptr;
            return 0;
    }
    return ::DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

void showNotifyReply(const std::string& key, const std::string& app, const std::string& title,
                     const std::function<bool(const std::string&, const std::string&)>& submit,
                     const std::function<void(const std::string&, const std::string&)>& notify) {
    const HINSTANCE inst = ::GetModuleHandleW(nullptr);
    // 失败一律**出声**：托盘气泡是这里唯一能被看见的出口（与 RemoteInput 同口径）。
    if (!registerCls(inst)) {
        if (notify) notify("弹不出回复框", "窗口类注册失败");
        return;
    }

    if (HDC hdc = ::GetDC(nullptr)) {
        const int dpi = ::GetDeviceCaps(hdc, LOGPIXELSY);
        ::ReleaseDC(nullptr, hdc);
        gScale = (dpi >= 144) ? 2 : 1;
    }

    gKey = key;
    gSubmit = submit;
    gNotify = notify;

    // 提示行 = 通知原文预览（标题 + 正文可能已折行，这里原样展示，帮助对上在回谁）
    const std::wstring tip = utf8ToWide(title.empty() ? app : title);
    const std::wstring caption = L"全能外设 · 回复" +
        (app.empty() ? L"手机通知" : utf8ToWide(app));

    if (gHwnd) {                                    // 已打开：新一轮回复，清空上次的文本
        ::SetWindowTextW(gHwnd, caption.c_str());
        if (gTip) ::SetWindowTextW(gTip, tip.c_str());
        if (gEdit) ::SetWindowTextW(gEdit, L"");
        ::ShowWindow(gHwnd, SW_SHOW);
        ::SetForegroundWindow(gHwnd);
        if (gEdit) ::SetFocus(gEdit);
        return;
    }

    const int w = 520 * gScale, h = 220 * gScale;
    gHwnd = ::CreateWindowExW(0, kCls, caption.c_str(),
                              WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_VISIBLE,
                              CW_USEDEFAULT, CW_USEDEFAULT, w, h,
                              nullptr, nullptr, inst, nullptr);
    if (!gHwnd) {
        if (notify) notify("弹不出回复框", "窗口创建失败");
        return;
    }
    if (gTip) ::SetWindowTextW(gTip, tip.c_str());
    layout(w, h);
    if (gEdit) ::SetFocus(gEdit);
}

}  // namespace apxpc::ui

#else  // !defined(_WIN32) —— 非 Windows 空桩：托盘气泡与 NIN_BALLOONUSERCLICK 均为 Win 特性

#include "apxpc/ui/notify_reply.hpp"

#include <functional>
#include <string>

namespace apxpc::ui {

void showNotifyReply(const std::string&, const std::string&, const std::string&,
                     const std::function<bool(const std::string&, const std::string&)>&,
                     const std::function<void(const std::string&, const std::string&)>&) {}

}  // namespace apxpc::ui

#endif  // defined(_WIN32)
