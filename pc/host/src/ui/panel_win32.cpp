// 桌面端控制面板（Windows）：纯 Win32 + GDI+ 自绘。
//
// 视觉**严格对齐手机端** —— 色值、圆角、字级全部取自
// android/app/src/main/res/values/{colors,styles}.xml，两端口径一致：
//   底 #F2F3F5 · 白卡 18px 圆角 + #EDEDED 描边 · 主色 #3482FF · 正文 #191919
//   次要字 #8C8C8C · 分区标题 13px 粗体蓝字 · 状态语义色 ok/warn/error/idle
// GDI+ 是 Windows 自带组件，不违反「不引第三方依赖」。
//
// 控件策略：**除两个输入框外全部自绘** —— 要拿到手机端那种圆角胶囊按钮、圆形
// 单选、MIUI 开关，用原生控件 + owner-draw 反而更绕。输入框保留原生 EDIT
// （中文输入法 / 光标 / 选中这些系统行为不好自己实现），只把底色与边框改掉。
//
// 线程纪律（关键）：所有 socket I/O 都在 WirelessSession 的 worker 线程里；
// UI 只表达意图、按定时器读快照，**不做跨线程回调** —— 避免"回调里刷 UI"竞态。
#include "apxpc/ui/panel.hpp"

#if defined(_WIN32)

#include "apxpc/log.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/wireless/wireless_session.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <windowsx.h>   // GET_X_LPARAM / GET_Y_LPARAM

#include <objidl.h>
#include <gdiplus.h>

#include <cstdio>
#include <memory>
#include <string>

#pragma comment(lib, "gdiplus")

using apxpc::tray::TrayIcon;
using apxpc::wireless::LinkPhase;
using apxpc::wireless::SessionSnapshot;
using apxpc::wireless::WirelessSession;

namespace apxpc::ui {
namespace {

// ——————————————————— 设计令牌（与 Android colors.xml 同值） ———————————————————
namespace tok {
constexpr Gdiplus::ARGB bg             = 0xFFF2F3F5;   // md_background
constexpr Gdiplus::ARGB surface        = 0xFFFFFFFF;   // md_surface
constexpr Gdiplus::ARGB stroke         = 0xFFEDEDED;   // card_stroke
constexpr Gdiplus::ARGB variant        = 0xFFEDEFF2;   // md_surface_variant
constexpr Gdiplus::ARGB primary        = 0xFF3482FF;   // md_primary
constexpr Gdiplus::ARGB primaryHover   = 0xFF4C90FF;
constexpr Gdiplus::ARGB primaryPressed = 0xFF2B6BE0;   // bg_btn_primary:state_pressed
constexpr Gdiplus::ARGB primarySoft    = 0xFFE8F0FF;   // badge_bg
constexpr Gdiplus::ARGB onSurface      = 0xFF191919;   // md_on_surface
constexpr Gdiplus::ARGB onVariant      = 0xFF8C8C8C;   // md_on_surface_variant
constexpr Gdiplus::ARGB tertiary       = 0xFFBDBDBD;   // miuix_text_tertiary
constexpr Gdiplus::ARGB stateOk        = 0xFF12B76A;   // state_ok
constexpr Gdiplus::ARGB stateWarn      = 0xFFF79009;   // state_warn
constexpr Gdiplus::ARGB stateError     = 0xFFF04438;   // state_error
constexpr Gdiplus::ARGB stateIdle      = 0xFF98A2B3;   // state_idle
constexpr Gdiplus::ARGB fieldBg        = 0xFFF2F3F5;   // 输入框底（同背景色）
}  // namespace tok

constexpr int kAppIconId = 101;   // 对应 pc/host/res/apx.rc 的 IDI_APPICON

constexpr int kClientW = 560;
constexpr int kClientH = 512;
constexpr int kMargin = 16;
constexpr int kCardX = kMargin;
constexpr int kCardW = kClientW - 2 * kMargin;   // 528
constexpr int kCardPad = 18;                     // APCcard 内边距
constexpr int kContentX = kCardX + kCardPad;     // 34

enum : int {
    IDC_EDIT_HOST = 1008,
    IDC_EDIT_PORT = 1009,
};

constexpr UINT_PTR kRefreshTimer = 1;
constexpr UINT kMsgTrayQuit = WM_APP + 1;

struct Rect {
    int x = 0, y = 0, w = 0, h = 0;
    bool has(int px, int py) const {
        return px >= x && py >= y && px < x + w && py < y + h;
    }
    Gdiplus::RectF f() const {
        return Gdiplus::RectF(static_cast<float>(x), static_cast<float>(y),
                              static_cast<float>(w), static_cast<float>(h));
    }
};

// ——————————————————— 布局（一处定义，绘制与命中检测共用） ———————————————————
struct Layout {
    Rect badge;
    Rect cardState, cardMode, cardData;
    Rect statusBig, statusDetail;
    Rect radioAuto, radioManual;
    Rect fieldHost, fieldPort, editHost, editPort;
    Rect btnConnect, btnDisconnect;
    Rect switchAuto, labelAuto;
    Rect btnHide, btnQuit;
};

const Layout& layout() {
    static const Layout L = [] {
        Layout l;
        l.badge = {kClientW - kMargin - 104, 26, 104, 26};

        l.cardState = {kCardX, 80, kCardW, 118};      // 80 .. 198
        l.cardMode = {kCardX, 210, kCardW, 148};      // 210 .. 358
        l.cardData = {kCardX, 370, kCardW, 80};       // 370 .. 450

        l.statusBig = {kContentX, 122, kCardW - 2 * kCardPad, 36};
        l.statusDetail = {kContentX, 162, kCardW - 2 * kCardPad, 18};

        l.radioAuto = {kContentX, 252, kCardW - 2 * kCardPad, 24};
        l.radioManual = {kContentX, 278, kCardW - 2 * kCardPad, 24};

        l.fieldHost = {kContentX + 44, 310, 176, 30};
        l.editHost = {l.fieldHost.x + 9, l.fieldHost.y, l.fieldHost.w - 18, l.fieldHost.h};
        l.fieldPort = {l.fieldHost.x + l.fieldHost.w + 8, 310, 60, 30};
        l.editPort = {l.fieldPort.x + 9, l.fieldPort.y, l.fieldPort.w - 18, l.fieldPort.h};
        l.btnConnect = {l.fieldPort.x + l.fieldPort.w + 12, 309, 92, 32};
        l.btnDisconnect = {l.btnConnect.x + l.btnConnect.w + 8, 309, 92, 32};

        l.switchAuto = {kMargin + 4, 464, 46, 24};
        l.labelAuto = {l.switchAuto.x + l.switchAuto.w + 10, 464, 140, 24};
        l.btnQuit = {kClientW - kMargin - 4 - 92, 460, 92, 32};
        l.btnHide = {l.btnQuit.x - 8 - 104, 460, 104, 32};
        return l;
    }();
    return L;
}

// ——————————————————— 绘制小工具 ———————————————————
// GraphicsPath 的拷贝构造是 protected，不能按值返回 —— 用出参构造
void buildRoundRect(const Rect& r, float rad, Gdiplus::GraphicsPath& p) {
    const float d = rad * 2.0f;
    if (d <= 0.5f || static_cast<float>(r.w) < d || static_cast<float>(r.h) < d) {
        p.AddRectangle(r.f());
        return;
    }
    const Gdiplus::RectF f = r.f();
    p.AddArc(f.X, f.Y, d, d, 180.0f, 90.0f);
    p.AddArc(f.X + f.Width - d, f.Y, d, d, 270.0f, 90.0f);
    p.AddArc(f.X + f.Width - d, f.Y + f.Height - d, d, d, 0.0f, 90.0f);
    p.AddArc(f.X, f.Y + f.Height - d, d, d, 90.0f, 90.0f);
    p.CloseFigure();
}

void fillRound(Gdiplus::Graphics& g, const Rect& r, float rad, Gdiplus::ARGB color) {
    Gdiplus::GraphicsPath p;
    buildRoundRect(r, rad, p);
    Gdiplus::SolidBrush b(color);
    g.FillPath(&b, &p);
}

/// align: 0=左 1=居中 2=右
void text(Gdiplus::Graphics& g, const std::wstring& s, const Rect& r, Gdiplus::Font& f,
          Gdiplus::ARGB color, int align = 0) {
    Gdiplus::SolidBrush b(color);
    Gdiplus::StringFormat fmt;
    fmt.SetAlignment(align == 1   ? Gdiplus::StringAlignmentCenter
                     : align == 2 ? Gdiplus::StringAlignmentFar
                                  : Gdiplus::StringAlignmentNear);
    fmt.SetLineAlignment(Gdiplus::StringAlignmentCenter);
    fmt.SetTrimming(Gdiplus::StringTrimmingEllipsisCharacter);
    fmt.SetFormatFlags(Gdiplus::StringFormatFlagsNoWrap);
    g.DrawString(s.c_str(), -1, &f, r.f(), &fmt, &b);
}

std::wstring toWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                                      nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::string toUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                                      nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), s.data(), n,
                        nullptr, nullptr);
    return s;
}

// ——————————————————— 面板 ———————————————————
enum class Hit { None, RadioAuto, RadioManual, Connect, Disconnect, Autostart, Hide, Quit };

struct Panel {
    HWND hwnd = nullptr;

    // 字级对齐手机端 styles.xml
    std::unique_ptr<Gdiplus::Font> fTitle;    // APTextTitle     20sp bold
    std::unique_ptr<Gdiplus::Font> fCaption;  // APTextCaption   12sp
    std::unique_ptr<Gdiplus::Font> fSection;  // APSectionTitle  13sp bold（蓝）
    std::unique_ptr<Gdiplus::Font> fBody;     // APTextBody      14sp
    std::unique_ptr<Gdiplus::Font> fStatus;   // 状态大字（对应状态页 30sp bold）
    std::unique_ptr<Gdiplus::Font> fBtn;
    std::unique_ptr<Gdiplus::Font> fBadge;    // APBadge         12sp bold

    HFONT hEditFont = nullptr;   // 原生 EDIT 只能用 GDI 字体
    HBRUSH hFieldBrush = nullptr;

    std::unique_ptr<WirelessSession> session;
    std::unique_ptr<TrayIcon> tray;

    bool autoMode = true;
    bool autostart = false;
    Hit hot = Hit::None;
    Hit pressed = Hit::None;
    bool tracking = false;      // 已注册 WM_MOUSELEAVE
    std::wstring repaintKey;    // 状态指纹：没变就不重绘
};

Gdiplus::ARGB phaseColor(LinkPhase ph) {
    switch (ph) {
        case LinkPhase::Connected:   return tok::stateOk;
        case LinkPhase::Connecting:
        case LinkPhase::Discovering: return tok::stateWarn;
        case LinkPhase::Failed:      return tok::stateError;
        default:                     return tok::stateIdle;
    }
}

std::wstring phaseText(const SessionSnapshot& s) {
    switch (s.phase) {
        case LinkPhase::Connected:   return L"已连接";
        case LinkPhase::Connecting:  return L"正在连接";
        case LinkPhase::Discovering: return L"正在发现";
        case LinkPhase::Failed:      return L"连接失败";
        default:                     return L"未启用";
    }
}

std::wstring detailText(const SessionSnapshot& s) {
    if (s.phase == LinkPhase::Connected) {
        const long long sec = s.upMs / 1000;
        wchar_t buf[256];
        std::swprintf(buf, 256, L"%s   ·   RTT %.0f ms   ·   已连 %lld:%02lld:%02lld",
                      toWide(s.peer).c_str(), s.rttMs, sec / 3600, (sec / 60) % 60,
                      sec % 60);
        return buf;
    }
    if (!s.error.empty()) return toWide(s.error);
    switch (s.phase) {
        case LinkPhase::Discovering:
            return L"手机端打开「Wi‑Fi 控制」模块即可自动连入（同一局域网）";
        case LinkPhase::Connecting:
            return L"正在建立 TCP 连接（最多 3 秒）…";
        case LinkPhase::Failed:
            return L"请检查手机 IP / 是否在同一局域网，然后重新点「连接」";
        default:
            return L"选「自动发现」并点连接；或填好地址后点连接";
    }
}

/// 状态指纹：只有真正变化时才重绘（避免每 400ms 无谓整窗重画）
std::wstring statusKey(const SessionSnapshot& s) {
    wchar_t buf[320];
    std::swprintf(buf, 320, L"%d|%s|%.0f|%lld|%llu|%llu|%llu|%llu|%s",
                  static_cast<int>(s.phase), toWide(s.peer).c_str(), s.rttMs,
                  s.upMs / 1000,
                  static_cast<unsigned long long>(s.counters.mouse),
                  static_cast<unsigned long long>(s.counters.keyboard),
                  static_cast<unsigned long long>(s.counters.consumer),
                  static_cast<unsigned long long>(s.counters.dropped),
                  toWide(s.error).c_str());
    return buf;
}

bool hitEnabled(Hit h, const SessionSnapshot& s) {
    if (h == Hit::Connect) return s.phase != LinkPhase::Connected;
    if (h == Hit::Disconnect) return s.phase != LinkPhase::Idle;
    if (h == Hit::None) return false;
    return true;
}

Hit hitTest(Panel* p, int x, int y) {
    const Layout& L = layout();
    const auto s = p->session->snapshot();
    Hit h = Hit::None;
    if (L.radioAuto.has(x, y)) h = Hit::RadioAuto;
    else if (L.radioManual.has(x, y)) h = Hit::RadioManual;
    else if (L.btnConnect.has(x, y)) h = Hit::Connect;
    else if (L.btnDisconnect.has(x, y)) h = Hit::Disconnect;
    else if (L.switchAuto.has(x, y) || L.labelAuto.has(x, y)) h = Hit::Autostart;
    else if (L.btnHide.has(x, y)) h = Hit::Hide;
    else if (L.btnQuit.has(x, y)) h = Hit::Quit;
    return hitEnabled(h, s) ? h : Hit::None;
}

// ——————————————————— 绘制 ———————————————————
void paintButton(Gdiplus::Graphics& g, const Rect& r, const std::wstring& label, bool primary,
                 bool enabled, bool hot, bool pressed, Gdiplus::Font& f) {
    Gdiplus::ARGB fill;
    Gdiplus::ARGB fg;
    if (!enabled) {
        fill = tok::variant;
        fg = tok::tertiary;
    } else if (primary) {
        fill = pressed ? tok::primaryPressed : (hot ? tok::primaryHover : tok::primary);
        fg = 0xFFFFFFFF;
    } else {
        fill = pressed ? 0xFFE0E3E8 : (hot ? 0xFFE6E9EE : tok::variant);
        fg = tok::onSurface;
    }
    fillRound(g, r, static_cast<float>(r.h) / 2.0f, fill);   // MIUIX 胶囊
    text(g, label, r, f, fg, 1);
}

void paintRadio(Gdiplus::Graphics& g, const Rect& row, const std::wstring& label, bool on,
                bool hot, Gdiplus::Font& f) {
    const float cx = static_cast<float>(row.x) + 9.0f;
    const float cy = static_cast<float>(row.y + row.h / 2);
    const Gdiplus::ARGB ring = on ? tok::primary : (hot ? tok::onVariant : 0xFFC4C9D0);
    Gdiplus::Pen pen(ring, 2.0f);
    g.DrawEllipse(&pen, cx - 8.0f, cy - 8.0f, 16.0f, 16.0f);
    if (on) {
        Gdiplus::SolidBrush dot(tok::primary);
        g.FillEllipse(&dot, cx - 4.0f, cy - 4.0f, 8.0f, 8.0f);
    }
    text(g, label, Rect{row.x + 26, row.y, row.w - 26, row.h}, f,
         on ? tok::onSurface : tok::onVariant);
}

void paintSwitch(Gdiplus::Graphics& g, const Rect& r, bool on, bool hot) {
    const float rad = static_cast<float>(r.h) / 2.0f;
    const Gdiplus::ARGB track =
        on ? (hot ? tok::primaryHover : tok::primary) : (hot ? 0xFFDCE0E6 : 0xFFE4E7EC);
    fillRound(g, r, rad, track);
    const float knob = static_cast<float>(r.h) - 6.0f;
    const float kx = on ? static_cast<float>(r.x + r.w) - 3.0f - knob
                        : static_cast<float>(r.x) + 3.0f;
    Gdiplus::SolidBrush w(0xFFFFFFFF);
    g.FillEllipse(&w, kx, static_cast<float>(r.y) + 3.0f, knob, knob);
}

void paint(HWND hwnd, Panel* p) {
    const Layout& L = layout();
    const auto s = p->session->snapshot();

    RECT crc{};
    GetClientRect(hwnd, &crc);
    const int cw = crc.right, ch = crc.bottom;

    HDC dc = GetDC(hwnd);
    HDC mem = CreateCompatibleDC(dc);
    HBITMAP bmp = CreateCompatibleBitmap(dc, cw, ch);
    HGDIOBJ old = SelectObject(mem, bmp);
    {
        Gdiplus::Graphics g(mem);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        g.SetTextRenderingHint(Gdiplus::TextRenderingHintClearTypeGridFit);
        g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHalf);

        Gdiplus::SolidBrush bg(tok::bg);
        g.FillRectangle(&bg, 0, 0, cw, ch);

        // —— 顶栏：应用名 + 副标题 + 状态徽章（对齐手机 header_bar）——
        text(g, L"全能外设", Rect{kMargin + 4, 18, 260, 32}, *p->fTitle, tok::onSurface);
        text(g, L"无线控制中枢", Rect{kMargin + 5, 50, 260, 18}, *p->fCaption, tok::onVariant);

        fillRound(g, L.badge, static_cast<float>(L.badge.h) / 2.0f, tok::primarySoft);
        text(g, phaseText(s), L.badge, *p->fBadge, phaseColor(s.phase), 1);

        // —— 卡 1：连接状态 ——
        fillRound(g, L.cardState, 18.0f, tok::surface);
        text(g, L"连接状态", Rect{kContentX, 98, 200, 18}, *p->fSection, tok::primary);
        text(g, phaseText(s), L.statusBig, *p->fStatus, phaseColor(s.phase));
        text(g, detailText(s), L.statusDetail, *p->fCaption, tok::onVariant);

        // —— 卡 2：连接方式 ——
        fillRound(g, L.cardMode, 18.0f, tok::surface);
        text(g, L"连接方式", Rect{kContentX, 228, 200, 18}, *p->fSection, tok::primary);
        paintRadio(g, L.radioAuto, L"自动发现手机（监听信标，推荐）", p->autoMode,
                   p->hot == Hit::RadioAuto, *p->fBody);
        paintRadio(g, L.radioManual, L"手动指定地址", !p->autoMode,
                   p->hot == Hit::RadioManual, *p->fBody);

        text(g, L"地址", Rect{kContentX, L.fieldHost.y, 44, L.fieldHost.h}, *p->fBody,
             tok::onVariant);
        // 输入框：自绘浅底胶囊，原生 EDIT 叠在上面（底色同值，视觉上是一体）
        fillRound(g, L.fieldHost, 8.0f, tok::fieldBg);
        fillRound(g, L.fieldPort, 8.0f, tok::fieldBg);

        paintButton(g, L.btnConnect, L"连接", true, s.phase != LinkPhase::Connected,
                    p->hot == Hit::Connect, p->pressed == Hit::Connect, *p->fBtn);
        paintButton(g, L.btnDisconnect, L"断开", false, s.phase != LinkPhase::Idle,
                    p->hot == Hit::Disconnect, p->pressed == Hit::Disconnect, *p->fBtn);

        // —— 卡 3：数据 ——
        fillRound(g, L.cardData, 18.0f, tok::surface);
        text(g, L"数据", Rect{kContentX, 388, 200, 18}, *p->fSection, tok::primary);
        {
            wchar_t buf[256];
            std::swprintf(buf, 256,
                          L"鼠标 %llu    ·    键盘 %llu    ·    多媒体 %llu    ·    丢帧 %llu",
                          static_cast<unsigned long long>(s.counters.mouse),
                          static_cast<unsigned long long>(s.counters.keyboard),
                          static_cast<unsigned long long>(s.counters.consumer),
                          static_cast<unsigned long long>(s.counters.dropped));
            text(g, buf, Rect{kContentX, 412, kCardW - 2 * kCardPad, 20}, *p->fBody,
                 tok::onSurface);
        }

        // —— 底部：开机自启开关 + 次要按钮 ——
        paintSwitch(g, L.switchAuto, p->autostart, p->hot == Hit::Autostart);
        text(g, L"开机自启", L.labelAuto, *p->fBody, tok::onSurface);
        paintButton(g, L.btnHide, L"隐藏到托盘", false, true, p->hot == Hit::Hide,
                    p->pressed == Hit::Hide, *p->fBtn);
        paintButton(g, L.btnQuit, L"退出", false, true, p->hot == Hit::Quit,
                    p->pressed == Hit::Quit, *p->fBtn);
    }
    // 必须**在位图仍选中时**拷贝：先 SelectObject 还原的话，BitBlt 读到的就是
    // 原来那张 1x1 单色位图，整窗等于什么都没画（真机踩过）
    BitBlt(dc, 0, 0, cw, ch, mem, 0, 0, SRCCOPY);
    SelectObject(mem, old);
    DeleteObject(bmp);
    DeleteDC(mem);
    ReleaseDC(hwnd, dc);
}

// ——————————————————— 行为 ———————————————————
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
    const int portNum = _wtoi(port);
    if (host[0] == L'\0' || portNum <= 0 || portNum > 65535) {
        MessageBoxW(p->hwnd, L"请填写有效的地址与端口（1–65535）", L"全能外设",
                    MB_OK | MB_ICONWARNING);
        return;
    }
    p->session->connectManual(toUtf8(host), static_cast<uint16_t>(portNum));
}

void performHit(Panel* p, Hit h) {
    switch (h) {
        case Hit::RadioAuto:
            p->autoMode = true;
            p->session->startAuto();
            break;
        case Hit::RadioManual:
            p->autoMode = false;   // 只切模式，等用户点「连接」
            break;
        case Hit::Connect:
            applyConnect(p);
            break;
        case Hit::Disconnect:
            p->session->disconnect();
            break;
        case Hit::Autostart:
            p->autostart = !p->autostart;
            // 写的是**当前运行 exe** 的路径；桌面端自启不带 "serve"
            apxpc::tray::setAutostart(p->autostart, {}, "");
            break;
        case Hit::Hide:
            ShowWindow(p->hwnd, SW_HIDE);
            break;
        case Hit::Quit:
            if (p->tray) {
                p->tray->setQuitCallback(nullptr);
                p->tray->setOpenCallback(nullptr);
            }
            DestroyWindow(p->hwnd);
            break;
        default:
            break;
    }
    // 地址框只在手动模式可编辑
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_HOST), !p->autoMode);
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_PORT), !p->autoMode);
}

// ——————————————————— 窗口 ———————————————————
void makeFonts(Panel* p) {
    using Gdiplus::Font;
    using Gdiplus::FontStyleBold;
    using Gdiplus::FontStyleRegular;
    using Gdiplus::UnitPixel;
    const wchar_t* face = L"Microsoft YaHei UI";
    p->fTitle.reset(new Font(face, 20.0f, FontStyleBold, UnitPixel));
    p->fCaption.reset(new Font(face, 12.0f, FontStyleRegular, UnitPixel));
    p->fSection.reset(new Font(face, 13.0f, FontStyleBold, UnitPixel));
    p->fBody.reset(new Font(face, 13.5f, FontStyleRegular, UnitPixel));
    p->fStatus.reset(new Font(face, 30.0f, FontStyleBold, UnitPixel));
    p->fBtn.reset(new Font(face, 13.0f, FontStyleRegular, UnitPixel));
    p->fBadge.reset(new Font(face, 12.0f, FontStyleBold, UnitPixel));

    p->hEditFont = CreateFontW(-15, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                               DEFAULT_CHARSET, OUT_TT_PRECIS, CLIP_DEFAULT_PRECIS,
                               CLEARTYPE_QUALITY, DEFAULT_PITCH, face);
    p->hFieldBrush = CreateSolidBrush(RGB(0xF2, 0xF3, 0xF5));
}

void createChildren(Panel* p) {
    const Layout& L = layout();
    auto mkEdit = [&](int id, const Rect& r, const wchar_t* text) {
        HWND e = CreateWindowExW(
            0, L"EDIT", text,
            WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL | WS_TABSTOP,
            r.x, r.y, r.w, r.h, p->hwnd,
            reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), nullptr, nullptr);
        SendMessageW(e, WM_SETFONT, reinterpret_cast<WPARAM>(p->hEditFont), TRUE);
        return e;
    };
    mkEdit(IDC_EDIT_HOST, L.editHost, L"192.168.2.182");
    mkEdit(IDC_EDIT_PORT, L.editPort, L"9500");
}

void refreshNow(Panel* p) {
    // 输入框可用性跟随模式（首帧也要正确）
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_HOST), !p->autoMode);
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_PORT), !p->autoMode);
}

void tick(Panel* p) {
    if (!p->session) return;
    const auto s = p->session->snapshot();
    const std::wstring key = statusKey(s);
    if (key != p->repaintKey) {
        p->repaintKey = key;
        InvalidateRect(p->hwnd, nullptr, FALSE);
    }
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
            refreshNow(p);
            SetTimer(hwnd, kRefreshTimer, 400, nullptr);
            return 0;
        }

        case WM_ERASEBKGND:
            return 1;   // 自绘 + 双缓冲，禁用系统擦背景以免闪

        case WM_PAINT: {
            PAINTSTRUCT ps{};
            BeginPaint(hwnd, &ps);
            if (p && p->session) paint(hwnd, p);
            EndPaint(hwnd, &ps);
            return 0;
        }

        case WM_TIMER:
            if (p && wp == kRefreshTimer) tick(p);
            return 0;

        case WM_CTLCOLOREDIT: {
            if (!p) break;
            HDC dc = reinterpret_cast<HDC>(wp);
            SetTextColor(dc, RGB(0x19, 0x19, 0x19));
            SetBkColor(dc, RGB(0xF2, 0xF3, 0xF5));
            return reinterpret_cast<LRESULT>(p->hFieldBrush);
        }

        case WM_MOUSEMOVE: {
            if (!p) break;
            if (!p->tracking) {
                TRACKMOUSEEVENT tme{sizeof(tme), TME_LEAVE, hwnd, 0};
                TrackMouseEvent(&tme);
                p->tracking = true;
            }
            const Hit h = hitTest(p, GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            if (h != p->hot) {
                p->hot = h;
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }

        case WM_MOUSELEAVE:
            if (p) {
                p->tracking = false;
                if (p->hot != Hit::None) {
                    p->hot = Hit::None;
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            return 0;

        case WM_LBUTTONDOWN: {
            if (!p) break;
            SetFocus(hwnd);   // 点空白处收回输入框焦点
            const Hit h = hitTest(p, GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            if (h != Hit::None) {
                p->pressed = h;
                SetCapture(hwnd);
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }

        case WM_LBUTTONUP: {
            if (!p) break;
            const Hit h = hitTest(p, GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            const Hit was = p->pressed;
            if (was != Hit::None) {
                ReleaseCapture();
                p->pressed = Hit::None;
                if (h == was) performHit(p, was);
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }

        case WM_SYSCOMMAND:
            // 最小化 = 收进托盘（后台还要持续接收输入注入）
            if ((wp & 0xFFF0) == SC_MINIMIZE) {
                ShowWindow(hwnd, SW_HIDE);
                return 0;
            }
            break;

        case WM_CLOSE:
            // 关窗口 ≠ 退出：输入注入要继续工作。退出走托盘菜单或窗口里的「退出」
            ShowWindow(hwnd, SW_HIDE);
            return 0;

        case kMsgTrayQuit:
            if (p) performHit(p, Hit::Quit);
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
                if (p->hEditFont) { DeleteObject(p->hEditFont); p->hEditFont = nullptr; }
                if (p->hFieldBrush) { DeleteObject(p->hFieldBrush); p->hFieldBrush = nullptr; }
                p->fTitle.reset(); p->fCaption.reset(); p->fSection.reset();
                p->fBody.reset(); p->fStatus.reset(); p->fBtn.reset(); p->fBadge.reset();
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
    Gdiplus::GdiplusStartupInput gdiIn{};
    ULONG_PTR gdiToken = 0;
    if (Gdiplus::GdiplusStartup(&gdiToken, &gdiIn, nullptr) != Gdiplus::Ok) {
        APX_LOGE("控制面板：GDI+ 初始化失败");
        return -1;
    }
    struct GdiGuard {
        ULONG_PTR token;
        ~GdiGuard() { Gdiplus::GdiplusShutdown(token); }
    } gdiGuard{gdiToken};

    Panel panel;
    panel.session = std::make_unique<WirelessSession>();
    panel.autostart = apxpc::tray::autostartEnabled();

    HINSTANCE inst = GetModuleHandleW(nullptr);
    HICON icon = LoadIconW(inst, MAKEINTRESOURCEW(kAppIconId));
    if (!icon) icon = LoadIconW(nullptr, IDI_APPLICATION);

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.style = CS_DBLCLKS;
    wc.lpfnWndProc = wndProc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hIcon = icon;
    wc.hIconSm = icon;
    wc.hbrBackground = nullptr;   // 全部自绘
    wc.lpszClassName = L"AllPeriphPanel";
    if (!RegisterClassExW(&wc)) {
        APX_LOGE("控制面板：注册窗口类失败（GetLastError={}）", GetLastError());
        return -1;
    }

    RECT rc{0, 0, kClientW, kClientH};
    const DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    AdjustWindowRectEx(&rc, style, FALSE, 0);
    HWND hwnd = CreateWindowExW(0, L"AllPeriphPanel", L"全能外设", style, CW_USEDEFAULT,
                                CW_USEDEFAULT, rc.right - rc.left, rc.bottom - rc.top,
                                nullptr, nullptr, inst, &panel);
    if (!hwnd) {
        APX_LOGE("控制面板：创建窗口失败（GetLastError={}）", GetLastError());
        return -1;
    }
    SendMessageW(hwnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(icon));
    SendMessageW(hwnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(icon));

    // 托盘：双击显隐窗口，「退出」真正退出（图标与窗口同一枚）
    panel.tray = std::make_unique<TrayIcon>();
    panel.tray->setOpenCallback([hwnd] {
        ShowWindow(hwnd, SW_SHOW);
        SetForegroundWindow(hwnd);
    });
    panel.tray->setQuitCallback([hwnd] { PostMessageW(hwnd, kMsgTrayQuit, 0, 0); });
    panel.tray->create("全能外设 · 无线控制中枢", icon);

    // 初始：自动发现并立刻进入等待
    panel.session->startAuto();

    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    APX_LOGI("控制面板已启动（无线控制中枢）");

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return 0;
}

}  // namespace apxpc::ui

#else  // 非 Windows：仓库其余平台本来就不提供 GUI

namespace apxpc::ui {
bool panelAvailable() { return false; }
int runPanel(const std::string&) { return -1; }
}  // namespace apxpc::ui

#endif
