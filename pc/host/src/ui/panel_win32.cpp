// 桌面端控制面板（Windows）：纯 Win32 + common controls，不引入 Qt/wx 等第三方依赖。
//
// 定位：**无线控制中枢**。手机端打开「Wi‑Fi 控制」模块后，这里负责
//   发现（UDP 信标）→ 建链（TCP 9500）→ 状态可视化 → 断线自动回到等待。
//
// 线程纪律（关键）：
//   - 所有 socket I/O 都在 `WirelessSession` 的 worker 线程里，UI 线程只表达意图、读快照；
//   - 界面刷新靠 `SetTimer` 轮询快照（400ms），不走跨线程回调 —— 避免"回调里刷 UI"这类
//     经典竞态（本项目在 Android 侧已经栽过一次同类问题）。
#include "apxpc/ui/panel.hpp"

#if defined(_WIN32)

#include "apxpc/log.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/wireless/wireless_session.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <commctrl.h>

#include <cstdio>
#include <memory>
#include <string>

using apxpc::tray::TrayIcon;
using apxpc::wireless::LinkPhase;
using apxpc::wireless::SessionSnapshot;
using apxpc::wireless::WirelessSession;

namespace apxpc::ui {
namespace {

enum : int {
    IDC_LABEL_STATE = 1001,
    IDC_STATUS,
    IDC_DETAIL,
    IDC_GROUP_MODE,
    IDC_RADIO_AUTO,
    IDC_RADIO_MANUAL,
    IDC_LABEL_HOST,
    IDC_EDIT_HOST,
    IDC_EDIT_PORT,
    IDC_BTN_CONNECT,
    IDC_BTN_DISCONNECT,
    IDC_GROUP_DATA,
    IDC_DATA,
    IDC_CHK_AUTOSTART,
    IDC_BTN_HIDE,
    IDC_BTN_QUIT,
};

constexpr UINT_PTR kRefreshTimer = 1;
constexpr UINT kMsgTrayQuit = WM_APP + 1;

// 客户区尺寸：宽度固定，高度足够放下四组控件
constexpr int kClientW = 524;
constexpr int kClientH = 372;

struct Panel {
    HWND hwnd = nullptr;
    HFONT fontNormal = nullptr;
    HFONT fontBig = nullptr;
    std::unique_ptr<WirelessSession> session;
    std::unique_ptr<TrayIcon> tray;
    bool autoMode = true;   // 与单选按钮一致（UI 侧镜像，避免每帧回读控件）
};

std::wstring toWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                                      nullptr, 0);
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::wstring toWide(const char* s) { return s ? toWide(std::string(s)) : std::wstring(); }

/// UTF-16 → UTF-8（控件里读出来的地址要交给只认 UTF-8 的会话层）
std::string toUtf8(const wchar_t* w) {
    if (!w || !*w) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), n, nullptr, nullptr);
    s.pop_back();   // 去掉转换结果里的末尾 NUL
    return s;
}

HWND child(Panel* p, const wchar_t* cls, const wchar_t* text, DWORD style,
           int x, int y, int w, int h, int id, HFONT font) {
    HWND c = CreateWindowExW(
        0, cls, text, WS_CHILD | WS_VISIBLE | style, x, y, w, h, p->hwnd,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), nullptr, nullptr);
    if (c && font) SendMessageW(c, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
    return c;
}

void makeFonts(Panel* p) {
    LOGFONTW lf{};
    lf.lfCharSet = DEFAULT_CHARSET;
    lf.lfQuality = CLEARTYPE_QUALITY;
    lstrcpynW(lf.lfFaceName, L"Microsoft YaHei UI", LF_FACESIZE);
    lf.lfHeight = -15;
    p->fontNormal = CreateFontIndirectW(&lf);
    lf.lfHeight = -30;
    lf.lfWeight = FW_BOLD;
    p->fontBig = CreateFontIndirectW(&lf);
}

void createChildren(Panel* p) {
    child(p, L"STATIC", L"连接状态", SS_LEFT, 22, 16, 200, 18, IDC_LABEL_STATE, p->fontNormal);
    child(p, L"STATIC", L"—", SS_LEFT, 22, 36, 480, 40, IDC_STATUS, p->fontBig);
    child(p, L"STATIC", L"—", SS_LEFT | SS_ENDELLIPSIS, 22, 78, 480, 20, IDC_DETAIL, p->fontNormal);

    child(p, L"BUTTON", L"连接方式", BS_GROUPBOX, 16, 106, 492, 118, IDC_GROUP_MODE, p->fontNormal);
    child(p, L"BUTTON", L"自动发现手机（监听信标，推荐）", BS_AUTORADIOBUTTON | WS_TABSTOP,
          32, 130, 440, 22, IDC_RADIO_AUTO, p->fontNormal);
    child(p, L"BUTTON", L"手动指定地址", BS_AUTORADIOBUTTON,
          32, 156, 200, 22, IDC_RADIO_MANUAL, p->fontNormal);
    child(p, L"STATIC", L"地址", SS_LEFT, 32, 190, 40, 20, IDC_LABEL_HOST, p->fontNormal);
    child(p, L"EDIT", L"192.168.2.182", WS_BORDER | ES_AUTOHSCROLL | WS_TABSTOP,
          74, 186, 170, 24, IDC_EDIT_HOST, p->fontNormal);
    child(p, L"EDIT", L"9500", WS_BORDER | ES_NUMBER | WS_TABSTOP,
          250, 186, 60, 24, IDC_EDIT_PORT, p->fontNormal);
    child(p, L"BUTTON", L"连接", BS_PUSHBUTTON | WS_TABSTOP,
          322, 185, 84, 26, IDC_BTN_CONNECT, p->fontNormal);
    child(p, L"BUTTON", L"断开", BS_PUSHBUTTON | WS_TABSTOP,
          412, 185, 84, 26, IDC_BTN_DISCONNECT, p->fontNormal);

    child(p, L"BUTTON", L"数据", BS_GROUPBOX, 16, 232, 492, 74, IDC_GROUP_DATA, p->fontNormal);
    child(p, L"STATIC", L"—", SS_LEFT, 32, 256, 460, 40, IDC_DATA, p->fontNormal);

    child(p, L"BUTTON", L"开机自启", BS_AUTOCHECKBOX | WS_TABSTOP,
          16, 322, 110, 24, IDC_CHK_AUTOSTART, p->fontNormal);
    child(p, L"BUTTON", L"隐藏到托盘", BS_PUSHBUTTON | WS_TABSTOP,
          300, 318, 110, 28, IDC_BTN_HIDE, p->fontNormal);
    child(p, L"BUTTON", L"退出", BS_PUSHBUTTON | WS_TABSTOP,
          418, 318, 90, 28, IDC_BTN_QUIT, p->fontNormal);
}

COLORREF phaseColor(LinkPhase ph) {
    switch (ph) {
        case LinkPhase::Connected:   return RGB(0, 140, 70);
        case LinkPhase::Connecting:
        case LinkPhase::Discovering: return RGB(196, 118, 0);
        case LinkPhase::Failed:      return RGB(196, 40, 40);
        default:                     return RGB(96, 96, 96);
    }
}

std::wstring phaseText(const SessionSnapshot& s) {
    switch (s.phase) {
        case LinkPhase::Connected:   return L"已连接";
        case LinkPhase::Connecting:  return L"正在连接…";
        case LinkPhase::Discovering: return L"正在发现手机…";
        case LinkPhase::Failed:      return L"连接失败";
        default:                     return L"未启用";
    }
}

std::wstring detailText(const SessionSnapshot& s) {
    wchar_t buf[256];
    if (s.phase == LinkPhase::Connected) {
        const long long sec = s.upMs / 1000;
        std::swprintf(buf, 256, L"%s   ·   RTT %.0f ms   ·   已连 %lld:%02lld:%02lld",
                      toWide(s.peer).c_str(), s.rttMs,
                      sec / 3600, (sec / 60) % 60, sec % 60);
        return buf;
    }
    if (!s.error.empty()) return toWide(s.error);
    if (s.phase == LinkPhase::Discovering)
        return L"手机端打开「Wi‑Fi 控制」模块即可自动连入（同一局域网）";
    if (s.phase == LinkPhase::Connecting) return L"正在建立 TCP 连接（最多 3 秒）…";
    if (s.phase == LinkPhase::Failed) return L"请检查手机 IP / 是否在同一局域网，然后重新点「连接」";
    return L"选「自动发现」并点连接；或填好地址后点连接";
}

std::wstring dataText(const SessionSnapshot& s) {
    wchar_t buf[256];
    std::swprintf(buf, 256, L"鼠标 %llu   ·   键盘 %llu   ·   多媒体 %llu   ·   丢帧 %llu",
                  static_cast<unsigned long long>(s.counters.mouse),
                  static_cast<unsigned long long>(s.counters.keyboard),
                  static_cast<unsigned long long>(s.counters.consumer),
                  static_cast<unsigned long long>(s.counters.dropped));
    return buf;
}

void refresh(Panel* p) {
    if (!p->session) return;
    const auto s = p->session->snapshot();

    SetWindowTextW(GetDlgItem(p->hwnd, IDC_STATUS), phaseText(s).c_str());
    SetWindowTextW(GetDlgItem(p->hwnd, IDC_DETAIL), detailText(s).c_str());
    SetWindowTextW(GetDlgItem(p->hwnd, IDC_DATA), dataText(s).c_str());

    const bool connected = (s.phase == LinkPhase::Connected);
    EnableWindow(GetDlgItem(p->hwnd, IDC_BTN_CONNECT), !connected);
    EnableWindow(GetDlgItem(p->hwnd, IDC_BTN_DISCONNECT), s.phase != LinkPhase::Idle);

    // 地址框只在手动模式可编辑；自动模式把它置灰，避免"填了却不生效"的困惑
    const bool manual = !p->autoMode;
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_HOST), manual);
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_PORT), manual);

    InvalidateRect(GetDlgItem(p->hwnd, IDC_STATUS), nullptr, TRUE);
}

void applyConnect(Panel* p) {
    if (!p->session) return;
    if (p->autoMode) {
        p->session->startAuto();
        return;
    }
    wchar_t host[128] = {0};
    wchar_t port[16] = {0};
    GetWindowTextW(GetDlgItem(p->hwnd, IDC_EDIT_HOST), host, 128);
    GetWindowTextW(GetDlgItem(p->hwnd, IDC_EDIT_PORT), port, 16);
    const int pnum = _wtoi(port);
    if (host[0] == L'\0' || pnum <= 0 || pnum > 65535) {
        MessageBoxW(p->hwnd, L"请填写有效的地址与端口（1–65535）", L"全能外设",
                    MB_OK | MB_ICONWARNING);
        return;
    }
    p->session->connectManual(toUtf8(host), static_cast<uint16_t>(pnum));
}

void requestQuit(Panel* p) {
    if (p->tray) {
        // 先摘掉回调，免得托盘析构时再往正在销毁的窗口投消息
        p->tray->setQuitCallback(nullptr);
        p->tray->setOpenCallback(nullptr);
    }
    DestroyWindow(p->hwnd);
}

LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    auto* p = reinterpret_cast<Panel*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));

    switch (msg) {
        case WM_CREATE: {
            auto* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
            p = static_cast<Panel*>(cs->lpCreateParams);
            p->hwnd = hwnd;
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(p));
            makeFonts(p);
            createChildren(p);
            refresh(p);
            SetTimer(hwnd, kRefreshTimer, 400, nullptr);
            return 0;
        }

        case WM_TIMER:
            if (p && wp == kRefreshTimer) refresh(p);
            return 0;

        case WM_CTLCOLORSTATIC: {
            if (!p || !p->session) break;
            if (reinterpret_cast<HWND>(lp) == GetDlgItem(hwnd, IDC_STATUS)) {
                const auto s = p->session->snapshot();
                HDC dc = reinterpret_cast<HDC>(wp);
                SetTextColor(dc, phaseColor(s.phase));
                SetBkMode(dc, TRANSPARENT);
                return reinterpret_cast<LRESULT>(GetSysColorBrush(COLOR_3DFACE));
            }
            break;
        }

        case WM_COMMAND: {
            if (!p) break;
            switch (LOWORD(wp)) {
                case IDC_RADIO_AUTO:
                    p->autoMode = true;
                    if (p->session) p->session->startAuto();
                    refresh(p);
                    return 0;
                case IDC_RADIO_MANUAL:
                    p->autoMode = false;
                    refresh(p);   // 只切模式，等用户点「连接」再动链路
                    return 0;
                case IDC_BTN_CONNECT:
                    applyConnect(p);
                    refresh(p);
                    return 0;
                case IDC_BTN_DISCONNECT:
                    if (p->session) p->session->disconnect();
                    refresh(p);
                    return 0;
                case IDC_CHK_AUTOSTART:
                    apxpc::tray::setAutostart(
                        SendMessageW(GetDlgItem(hwnd, IDC_CHK_AUTOSTART), BM_GETCHECK, 0, 0) ==
                            BST_CHECKED,
                        {}, "");
                    return 0;
                case IDC_BTN_HIDE:
                    ShowWindow(hwnd, SW_HIDE);
                    return 0;
                case IDC_BTN_QUIT:
                    requestQuit(p);
                    return 0;
                default:
                    break;
            }
            break;
        }

        case WM_SYSCOMMAND:
            // 最小化 = 收进托盘（既然后台还要持续接收输入注入）
            if ((wp & 0xFFF0) == SC_MINIMIZE) {
                ShowWindow(hwnd, SW_HIDE);
                return 0;
            }
            break;

        case WM_CLOSE:
            // 关闭窗口 ≠ 退出程序：输入注入要持续工作。退出走托盘菜单或窗口里的「退出」
            ShowWindow(hwnd, SW_HIDE);
            return 0;

        case kMsgTrayQuit:
            if (p) requestQuit(p);
            return 0;

        case WM_DESTROY:
            KillTimer(hwnd, kRefreshTimer);
            if (p) {
                if (p->session) p->session->stop();
                if (p->tray) {
                    p->tray->setQuitCallback(nullptr);
                    p->tray->setOpenCallback(nullptr);
                    p->tray->quit();
                    p->tray.reset();
                }
                if (p->fontNormal) { DeleteObject(p->fontNormal); p->fontNormal = nullptr; }
                if (p->fontBig) { DeleteObject(p->fontBig); p->fontBig = nullptr; }
            }
            PostQuitMessage(0);
            return 0;

        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

bool panelAvailable() { return true; }

int runPanel(const std::string& /*preferInstanceId*/) {
    INITCOMMONCONTROLSEX icc{};
    icc.dwSize = sizeof(icc);
    icc.dwICC = ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&icc);

    Panel panel;
    panel.session = std::make_unique<WirelessSession>();

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = wndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    wc.hbrBackground = GetSysColorBrush(COLOR_3DFACE);
    wc.lpszClassName = L"AllPeriphPanel";
    if (!RegisterClassExW(&wc)) {
        APX_LOGE("控制面板：注册窗口类失败（GetLastError={}）", GetLastError());
        return -1;
    }

    RECT rc{0, 0, kClientW, kClientH};
    AdjustWindowRectEx(&rc, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                       FALSE, 0);
    HWND hwnd = CreateWindowExW(
        0, L"AllPeriphPanel", L"全能外设 · 无线控制中枢",
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT, rc.right - rc.left, rc.bottom - rc.top,
        nullptr, nullptr, wc.hInstance, &panel);
    if (!hwnd) {
        APX_LOGE("控制面板：创建窗口失败（GetLastError={}）", GetLastError());
        return -1;
    }

    // 托盘：双击显隐窗口，「退出」真正退出
    panel.tray = std::make_unique<TrayIcon>();
    panel.tray->setOpenCallback([hwnd] {
        ShowWindow(hwnd, SW_SHOW);
        SetForegroundWindow(hwnd);
    });
    panel.tray->setQuitCallback([hwnd] { PostMessageW(hwnd, kMsgTrayQuit, 0, 0); });
    panel.tray->create("全能外设 · 无线控制中枢");

    // 初始：勾选自动发现并立刻进入等待
    SendMessageW(GetDlgItem(hwnd, IDC_RADIO_AUTO), BM_SETCHECK, BST_CHECKED, 0);
    CheckDlgButton(hwnd, IDC_CHK_AUTOSTART,
                   apxpc::tray::autostartEnabled() ? BST_CHECKED : BST_UNCHECKED);
    panel.session->startAuto();

    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    APX_LOGI("控制面板已启动（无线控制中枢）");

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        if (!IsDialogMessageW(hwnd, &msg)) {   // 让 Tab / 方向键在控件间正常走
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    return 0;
}

}  // namespace apxpc::ui

#else  // 非 Windows：仓库的其余平台本来就不提供 GUI

namespace apxpc::ui {
bool panelAvailable() { return false; }
int runPanel(const std::string&) { return -1; }
}  // namespace apxpc::ui

#endif
