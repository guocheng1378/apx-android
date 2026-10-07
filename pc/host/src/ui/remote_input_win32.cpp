#include "apxpc/ui/remote_input_win32.hpp"

#if defined(_WIN32)

#include <windowsx.h>

#include <string>

#pragma comment(lib, "user32")
#pragma comment(lib, "gdi32")

namespace apxpc::ui {
namespace {

constexpr wchar_t kCls[] = L"AllPeriphRemoteInput";
constexpr int kIdEdit = 2201;
constexpr int kIdSend = 2202;
constexpr int kIdCancel = 2203;

// 单实例窗口：状态放静态（进程级）。发送回调来自 UI 层持有的会话对象，
// 窗口销毁即一并清空，避免晚到的 EN_CHANGE 打到已失效的会话上。
HWND gHwnd = nullptr;
HWND gEdit = nullptr;
HWND gTip = nullptr;
int gScale = 1;
std::wstring gLastText;              // 已同步给对端的文本（增量 diff 的基线）
std::function<bool(const std::string&, uint8_t)> gSendText;
std::function<bool()> gSendDone;
std::function<void(const std::string&, const std::string&)> gNotify;

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
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

std::wstring editText() {
    if (!gEdit) return {};
    const int n = ::GetWindowTextLengthW(gEdit);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n) + 1, L'\0');
    ::GetWindowTextW(gEdit, w.data(), n + 1);
    w.resize(static_cast<size_t>(n));
    return w;
}

std::wstring tipText(const std::string& hint) {
    std::wstring s = L"对端设备请你在电脑上输入";
    if (!hint.empty()) s += L"（" + utf8ToWide(hint) + L"）";
    return s;
}

/// 发一帧 0x26；发不动就当场提示一次，不静默假装成功（丢字是没法事后补救的）。
bool sendFrame(const std::string& text, uint8_t flags) {
    if (!gSendText) return false;
    if (gSendText(text, flags)) return true;
    if (gNotify) gNotify("没发出去", "连接断了？恢复后这段要重新打");
    return false;
}

/// 把编辑框内容**按增量**同步给对端。
///
/// ★ 只能发增量（0x01）/ 退格（0x02），**绝不能再补一帧 COMMIT（0x04 完整文本）**：
/// COMMIT 是"整段再粘一遍"，增量 + COMMIT 两种帧都注入，就是手机端 340856f 修的
/// 那个「发送后文字重复两次」。所以每次编辑只把**差异**发过去。
///
/// 差异算法：先退到公共前缀（逐字符删），再把新增的尾巴增量追加 ——
/// 末尾追加、退格、中间改写三种编辑都能正确落到对端。
/// 在宽字符层做 diff：按字节 diff 会把一个汉字拆成 3 次退格，对端就多删了两个字。
void syncText() {
    const std::wstring cur = editText();
    size_t common = 0;
    const size_t n = (cur.size() < gLastText.size()) ? cur.size() : gLastText.size();
    while (common < n && cur[common] == gLastText[common]) ++common;
    for (size_t i = common; i < gLastText.size(); ++i) {
        if (!sendFrame(std::string(), 0x02)) break;      // INPUT_FLAG_BACKSPACE
    }
    if (common < cur.size()) sendFrame(wideToUtf8(cur.substr(common)), 0x01);
    gLastText = cur;
}

/// 收尾。取消要**逐字退格**把已上屏的字撤掉 —— 三端都没有处理 0x08（CANCEL）的地方，
/// 只发 CANCEL 等于在对方输入框里留下一堆废字。两种情形都要发 0x27，
/// 否则对方浮层会一直挂在那里等。
void finish(bool cancelled) {
    if (cancelled) {
        for (size_t i = 0; i < gLastText.size(); ++i) {
            if (!sendFrame(std::string(), 0x02)) break;
        }
    } else {
        syncText();
    }
    gLastText.clear();
    if (gSendDone) gSendDone();
    if (gHwnd) ::DestroyWindow(gHwnd);
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
                                          ES_MULTILINE | ES_AUTOVSCROLL | ES_WANTRETURN,
                                      0, 0, 10, 10, hwnd, reinterpret_cast<HMENU>(kIdEdit),
                                      inst, nullptr);
            ::CreateWindowExW(0, L"BUTTON", L"发送", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
                              0, 0, 10, 10, hwnd, reinterpret_cast<HMENU>(kIdSend), inst, nullptr);
            ::CreateWindowExW(0, L"BUTTON", L"取消", WS_CHILD | WS_VISIBLE,
                              0, 0, 10, 10, hwnd, reinterpret_cast<HMENU>(kIdCancel), inst, nullptr);
            return 0;
        }

        case WM_COMMAND:
            // 边打字边回传（增量）：对端输入框实时显示，与手机 / TV 端同一套语义
            if (HIWORD(wp) == EN_CHANGE && LOWORD(wp) == kIdEdit) { syncText(); return 0; }
            if (HIWORD(wp) == BN_CLICKED) {
                if (LOWORD(wp) == kIdSend) { finish(false); return 0; }
                if (LOWORD(wp) == kIdCancel) { finish(true); return 0; }
            }
            break;

        case WM_SIZE:
            layout(LOWORD(lp), HIWORD(lp));
            return 0;

        case WM_KEYDOWN:
            if (wp == VK_ESCAPE) { finish(true); return 0; }
            break;

        case WM_CLOSE:
            finish(true);          // 关窗视为取消：对端要撤掉预览文本，不能悬着
            return 0;

        case WM_DESTROY:
            gHwnd = nullptr; gEdit = nullptr; gTip = nullptr;
            gLastText.clear();
            gSendText = nullptr; gSendDone = nullptr; gNotify = nullptr;
            return 0;
    }
    return ::DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

void RemoteInput::show(HWND owner,
                       const std::string& hint,
                       std::function<bool(const std::string&, uint8_t)> sendText,
                       std::function<bool()> sendDone,
                       std::function<void(const std::string&, const std::string&)> notify) {
    const HINSTANCE inst = ::GetModuleHandleW(nullptr);
    // 失败一律**出声**：面板是 GUI，静默 return 的表现就是"对端喊了、本机毫无反应"，
    // 没人知道是注册失败还是建窗失败。托盘气泡是这里唯一能被看见的出口。
    if (!registerCls(inst)) {
        if (notify) notify("弹不出输入框", "窗口类注册失败");
        return;
    }

    if (HDC hdc = ::GetDC(nullptr)) {
        const int dpi = ::GetDeviceCaps(hdc, LOGPIXELSY);
        ::ReleaseDC(nullptr, hdc);
        gScale = (dpi >= 144) ? 2 : 1;
    }

    gSendText = std::move(sendText);
    gSendDone = std::move(sendDone);
    gNotify = std::move(notify);

    if (gHwnd) {                                    // 已打开：新一轮请求，清空上次的文本
        if (gTip) ::SetWindowTextW(gTip, tipText(hint).c_str());
        if (gEdit) ::SetWindowTextW(gEdit, L"");
        gLastText.clear();
        ::ShowWindow(gHwnd, SW_SHOW);
        ::SetForegroundWindow(gHwnd);
        if (gEdit) ::SetFocus(gEdit);
        return;
    }

    const int w = 520 * gScale, h = 240 * gScale;
    gHwnd = ::CreateWindowExW(0, kCls, L"全能外设 · 对端请你输入",
                              WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_VISIBLE,
                              CW_USEDEFAULT, CW_USEDEFAULT, w, h,
                              owner, nullptr, inst, nullptr);
    if (!gHwnd) {
        if (gNotify) gNotify("弹不出输入框", "窗口创建失败");
        return;
    }
    if (gTip) ::SetWindowTextW(gTip, tipText(hint).c_str());
    layout(w, h);
    if (gEdit) ::SetFocus(gEdit);
}

}  // namespace apxpc::ui

#endif  // defined(_WIN32)
