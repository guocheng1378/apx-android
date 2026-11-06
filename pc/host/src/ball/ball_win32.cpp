// 球 v2 实现 —— 设计与取舍见 ball_win32.hpp 顶部注释。
//
// 实现取舍：
//   · per-pixel layered（UpdateLayeredWindow + GDI+ PARGB）：扇形 = 圆弧 + 文字 + 角标，
//     纯 Region 裁不出异形，alpha 渐隐也依赖按像素；legacy 壳只画正圆，所以只用 Region。
//   · Region 仍然要（与 layered 叠加）：窗口区域 = 球圆 ∪ 各项圆 —— 区域外的鼠标事件由
//     系统直接投给下层窗口（跨进程也对），避开 WM_NCHITTEST 返回 HTTRANSPARENT 的
//     「只透同线程」限制。
//   · 同窗展开：改窗口尺寸 → 重绘整面 → 重设区域；不另开顶层窗（无 z-order/焦点竞争）。
//   · 动效算术全在 fx.cpp（可单测），本文件只负责到点重画。
//   · GetHBITMAP 出的位图若边缘发黑，把内部像素改手动预乘（M3 视检微调，先证链路）。
#include "ball_win32.hpp"

#if defined(_WIN32)

#include "apxpc/log.hpp"
#include "fx.hpp"

// GDI+ 官方顺序：windows.h（已在 ball_win32.hpp 内，带 WIN32_LEAN_AND_MEAN）→ objidl.h → gdiplus.h。
// LEAN_AND_MEAN 裁掉了 ole2 链，objidl 不随 windows.h 进来 —— 少了它，GdiplusBitmap/GdiplusMetafile
// 里的 IStream / BOOL 参数直接崩（C2065 IStream → 级联 100+ 错停编，diag run #5 实测）。
#include <objidl.h>
#include <gdiplus.h>
#include <windowsx.h>

#include <string>
#include <vector>

namespace apxpc {
namespace ball {

HWND g_ballWnd = nullptr;

namespace {

// 必须是数组/指针型：值型 `wchar_t` 装不下字符串字面量（diag run #6: C2440 + C2664 + C2131
// 三条全出自这里 —— lpszClassName 赋值、CreateWindowExW 第 2 参、初始化三处同时炸）。
constexpr wchar_t kClass[] = L"APXBallV2";
constexpr int     kBallDip       = 44;    // 与 legacy 壳同直径
constexpr int     kItemDip       = 52;    // 扇形项直径
constexpr int     kFanRadiusDip  = 128;   // 扇形半径（项心轨迹）
constexpr double  kFanSpan       = 1.7;   // 展开弧度 ≈ 97°（单手拇指弧）
constexpr int     kTickMs        = 16;
constexpr int     kBadgeMs       = 500;   // 状态角标轮询
constexpr int     kSnapMs        = 160;   // 贴边时长（对齐 Android dock / legacy 壳）
constexpr int     kDragSlop      = 3;     // 位移 > 3px 才算拖动
constexpr UINT_PTR kTimerAnim    = 1;
constexpr UINT_PTR kTimerBadge   = 2;
constexpr UINT     kFanHotkeyMsg = WM_APP + 41;  // 热键线程投递：切换扇形

int dip(int v, UINT dpi) { return ::MulDiv(v, (int)dpi, 96); }

std::wstring wide(const std::string& s) {
    if (s.empty()) return std::wstring();
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (n <= 0) return std::wstring();
    std::wstring out((size_t)n, L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), out.data(), n);
    return out;
}

struct ItemHit { RECT rc{}; std::string actionId; bool enabled = true; };

struct BallState {
    UINT dpi = 96;
    int ballPx = kBallDip;
    int itemPx = kItemDip;
    int fanR   = kFanRadiusDip;

    int side = kBallDip;   // 当前窗口边长（闭 = 球径；开 = 2*(fanR+itemPx/2+pad)）
    int cx = 0, cy = 0;    // 球心（客户区坐标，两态都取窗口中心）

    bool   fanOpen = false;
    double fanDir  = fx::kPi;   // 扇形基准朝向：球在工作区右半 → 朝左（π），否则朝右（0）
    std::vector<MenuItem> items;
    std::vector<ItemHit>  hits;
    std::vector<int>      badges;   // 与 items 平行：0/1（开扇时填充，角标定时器刷新）
    bool lastRing = false;          // 闭态球环：上一轮 anyHighBadge

    fx::Pulse pulse;

    bool dragging = false, moved = false, wasOpen = false, anim = false;
    POINT dragPt{0, 0};
    RECT  startRc{0, 0, 0, 0}, fromRc{0, 0, 0, 0}, toRc{0, 0, 0, 0};
    ULONGLONG snapT0 = 0;

    ULONG_PTR gdip = 0;
    bool gdipOk = false;
};

BallState* g = nullptr;

// —— 布局 ——
void layoutClosed(BallState* s) { s->side = s->ballPx; s->cx = s->side / 2; s->cy = s->side / 2; }
void layoutOpen(BallState* s) {
    s->side = 2 * (s->fanR + s->itemPx / 2 + 4);
    s->cx = s->side / 2;
    s->cy = s->side / 2;
}

// 扇形项中心（每帧重算，同时喂渲染与命中）
fx::Pt itemCenter(const BallState* s, size_t i) {
    const double a = fx::itemAngle((int)i, (int)s->items.size(), s->fanDir - kFanSpan / 2, kFanSpan);
    return fx::polar(s->cx, s->cy, s->fanR, a);
}

// —— 状态查询（badge 走 registry 钩子）——
std::vector<int> queryBadges(const std::vector<MenuItem>& items) {
    std::vector<int> out;
    out.reserve(items.size());
    for (const auto& m : items) {
        const Action* a = registry().find(m.actionId);
        out.push_back(a && a->badge_fn && a->badge_fn() ? 1 : 0);
    }
    return out;
}

// 任一动作有角标即亮球缘（用户自定义后档位失去意义 → 扫全部动作）
bool anyHighBadge() {
    for (const Action* a : registry().all())
        if (a->badge_fn && a->badge_fn()) return true;
    return false;
}

// —— 区域：球圆 ∪ 项圆（区域外穿透；含脉冲缩放，与绘制同参）——
void applyRegion(HWND w) {
    const auto* s = g;
    if (!s) return;
    const double sc = s->pulse.scale();
    const int br = (int)(s->ballPx * 0.5 * sc + 0.5);
    HRGN rgn = ::CreateEllipticRgn(s->cx - br, s->cy - br, s->cx + br, s->cy + br);
    if (!rgn) return;
    if (s->fanOpen) {
        for (size_t i = 0; i < s->items.size(); ++i) {
            const fx::Pt p = itemCenter(s, i);
            const int ir = s->itemPx / 2;
            HRGN c = ::CreateEllipticRgn((int)p.x - ir, (int)p.y - ir, (int)p.x + ir, (int)p.y + ir);
            HRGN n = c ? ::CreateRectRgn(0, 0, 0, 0) : nullptr;
            if (c && n) {
                ::CombineRgn(n, rgn, c, RGN_OR);
                ::DeleteObject(rgn);
                rgn = n;
                ::DeleteObject(c);
            } else {
                if (c) ::DeleteObject(c);
                if (n) ::DeleteObject(n);
            }
        }
    }
    if (!::SetWindowRgn(w, rgn, TRUE)) ::DeleteObject(rgn);   // 成功则区域归窗口管
}

// —— 渲染：GDI+ 画进 PARGB 位图 → UpdateLayeredWindow（同时重建命中矩形）——
bool present(HWND w) {
    auto* s = g;
    if (!s || !s->gdipOk) return false;

    Gdiplus::Bitmap bmp(s->side, s->side, PixelFormat32bppPARGB);
    if (bmp.GetLastStatus() != Gdiplus::Ok) return false;
    Gdiplus::Graphics gr(&bmp);
    if (gr.GetLastStatus() != Gdiplus::Ok) return false;
    gr.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    gr.SetTextRenderingHint(Gdiplus::TextRenderingHintAntiAliasGridFit);

    Gdiplus::StringFormat sf;
    sf.SetAlignment(Gdiplus::StringAlignmentCenter);
    sf.SetLineAlignment(Gdiplus::StringAlignmentCenter);

    const double sc = s->pulse.scale();
    const float br = s->ballPx * 0.5f * (float)sc;
    const Gdiplus::RectF ballRect(s->cx - br, s->cy - br, br * 2, br * 2);

    // 球本体（legacy 同色 #266BE5）
    Gdiplus::SolidBrush ballBr(Gdiplus::Color(255, 38, 107, 229));
    gr.FillEllipse(&ballBr, ballRect);

    // 球缘亮环：闭态看全局 anyBadge，开态由 badges 驱动项角标
    if (!s->fanOpen && anyHighBadge()) {
        Gdiplus::Pen pen(Gdiplus::Color(255, 90, 220, 130), 2.f);
        gr.DrawEllipse(&pen, s->cx - br - 3, s->cy - br - 3, (br + 3) * 2, (br + 3) * 2);
    }

    // 球字（与 legacy 同为 "A"）
    Gdiplus::Font fBall(L"Segoe UI", br * 0.8f, Gdiplus::FontStyleBold, Gdiplus::UnitPixel);
    Gdiplus::SolidBrush white(Gdiplus::Color(255, 255, 255, 255));
    gr.DrawString(L"A", -1, &fBall, ballRect, &sf, &white);

    // 扇形项
    s->hits.clear();
    if (s->fanOpen) {
        for (size_t i = 0; i < s->items.size(); ++i) {
            const auto& it = s->items[i];
            const fx::Pt p = itemCenter(s, i);
            const float ir = s->itemPx * 0.5f;
            const Gdiplus::RectF rc((float)p.x - ir, (float)p.y - ir, ir * 2, ir * 2);

            Gdiplus::SolidBrush fill(it.enabled ? Gdiplus::Color(255, 38, 107, 229)
                                                : Gdiplus::Color(170, 150, 158, 170));
            gr.FillEllipse(&fill, rc);

            // label：UTF-8 → 宽字符；超宽则 75% 字号重画
            const std::wstring lb = wide(it.label);
            Gdiplus::Font f1(L"Segoe UI", ir * 0.52f, Gdiplus::FontStyleRegular, Gdiplus::UnitPixel);
            Gdiplus::Font f2(L"Segoe UI", ir * 0.52f * 0.75f, Gdiplus::FontStyleRegular, Gdiplus::UnitPixel);
            Gdiplus::RectF m;
            gr.MeasureString(lb.c_str(), -1, &f1, rc, &sf, &m);
            const Gdiplus::Font* fp = (m.Width > ir * 1.6f) ? &f2 : &f1;
            Gdiplus::SolidBrush txt(Gdiplus::Color(255, 255, 255, 255));
            gr.DrawString(lb.c_str(), -1, fp, rc, &sf, &txt);

            // 角标点（badge = 1 → 右上绿点）
            if (i < s->badges.size() && s->badges[i] == 1) {
                Gdiplus::SolidBrush dot(Gdiplus::Color(255, 60, 200, 90));
                gr.FillEllipse(&dot, (float)p.x + ir * 0.50f, (float)p.y - ir * 1.05f,
                               ir * 0.5f, ir * 0.5f);
            }

            s->hits.push_back(ItemHit{
                RECT{ (LONG)p.x - (LONG)ir, (LONG)p.y - (LONG)ir,
                      (LONG)p.x + (LONG)ir + 1, (LONG)p.y + (LONG)ir + 1 },
                it.actionId, it.enabled });
        }
    }

    // 提交：DIB → UpdateLayeredWindow
    HDC screenDc = ::GetDC(nullptr);
    HDC memDc = screenDc ? ::CreateCompatibleDC(screenDc) : nullptr;
    HBITMAP hbm = nullptr;
    if (screenDc && memDc && bmp.GetHBITMAP(Gdiplus::Color(0, 0, 0, 0), &hbm) == Gdiplus::Ok) {
        HGDIOBJ old = ::SelectObject(memDc, hbm);
        RECT rc{};
        ::GetWindowRect(w, &rc);
        SIZE sz{ s->side, s->side };
        POINT src{ 0, 0 };
        POINT dst{ rc.left, rc.top };
        BLENDFUNCTION bf{ AC_SRC_OVER, 0, 255, AC_SRC_ALPHA };
        const BOOL ulw = ::UpdateLayeredWindow(w, screenDc, &dst, &sz, memDc, &src, 0, &bf, ULW_ALPHA);
        if (!ulw) {
            // v223.29.15：失败必须留痕 —— 此前静默失败，表现为「日志已创建、屏幕上没有」，
            // 最常见根因就是窗口缺 WS_EX_LAYERED（UpdateLayeredWindow 前置硬条件）。
            // 只记首次错误，避免 16ms 动效刷屏。
            static bool ulwErrLogged = false;
            if (!ulwErrLogged) {
                ulwErrLogged = true;
                APX_LOGW("球 v2 UpdateLayeredWindow 失败（err={}，球将不可见）", ::GetLastError());
            }
        }
        ::SelectObject(memDc, old);
        ::DeleteObject(hbm);
    }
    if (memDc) ::DeleteDC(memDc);
    if (screenDc) ::ReleaseDC(nullptr, screenDc);

    applyRegion(w);
    return true;
}

// —— 展开 / 收起（同窗改尺寸；球心屏幕位置保持不动）——
void expandLocked(HWND w) {   // 调用前 s->items / s->badges 已就位
    auto* s = g;
    RECT rc{};
    ::GetWindowRect(w, &rc);
    const POINT ballBefore{ (rc.left + rc.right) / 2, (rc.top + rc.bottom) / 2 };
    s->fanOpen = true;
    layoutOpen(s);
    ::SetWindowPos(w, HWND_TOPMOST, ballBefore.x - s->side / 2, ballBefore.y - s->side / 2,
                   s->side, s->side, SWP_NOACTIVATE);
    present(w);
}

void collapseLocked(HWND w) {
    auto* s = g;
    RECT rc{};
    ::GetWindowRect(w, &rc);
    const POINT ballBefore{ rc.left + s->cx, rc.top + s->cy };   // 开态球心 = 窗口中心
    s->fanOpen = false;
    s->items.clear();
    s->hits.clear();
    s->badges.clear();
    layoutClosed(s);
    ::SetWindowPos(w, HWND_TOPMOST, ballBefore.x - s->side / 2, ballBefore.y - s->side / 2,
                   s->side, s->side, SWP_NOACTIVATE);
    present(w);
}

// 最近的左右屏边 + y 夹进工作区（与 legacy 壳 snapTarget 同构）
RECT snapTarget(HWND w) {
    RECT rc{};
    ::GetWindowRect(w, &rc);
    MONITORINFO mi{ sizeof(mi) };
    const HMONITOR mon = ::MonitorFromWindow(w, MONITOR_DEFAULTTONEAREST);
    if (!mon || !::GetMonitorInfoW(mon, &mi)) return rc;
    const RECT& wa = mi.rcWork;
    const int bw = rc.right - rc.left;
    const int bh = rc.bottom - rc.top;
    const int cx = (rc.left + rc.right) / 2;
    RECT t = rc;
    t.left  = (cx * 2 < wa.left + wa.right) ? wa.left : wa.right - bw;
    t.right = t.left + bw;
    if (t.top < wa.top) t.top = wa.top;
    if (t.top + bh > wa.bottom) t.top = wa.bottom - bh;
    return t;
}

// 从注册表开扇（全部动作，按注册序；方向按球在工作区的位置）
void openFanFromRegistry(HWND w) {
    std::vector<MenuItem> items;
    // 全部动作（按注册序）—— 用户自定义的顺序即扇形顺序
    for (const Action* a : registry().all()) {
        MenuItem m;
        m.actionId = a->id;
        m.label    = a->label;
        m.enabled  = a->enabled_fn ? a->enabled_fn() : true;
        items.push_back(std::move(m));
    }
    if (items.empty()) {
        APX_LOGI("球扇形为空（installDefaultActions 未调？）");
        return;
    }
    RECT rc{};
    ::GetWindowRect(w, &rc);
    RECT wa{};
    ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    const int ballX = (rc.left + rc.right) / 2;
    g->fanDir = (ballX * 2 >= wa.left + wa.right) ? fx::kPi : 0.0;
    drawFan(w, items);
}

} // namespace

// ============ 公开接口（顺序按头文件） ============

void drawFan(HWND w, const std::vector<MenuItem>& items) {
    auto* s = g;
    if (!s) return;
    s->items = items;
    s->badges = queryBadges(items);
    expandLocked(w);
}

void drawBall(HWND w, const Registry& reg) {
    (void)reg;   // 亮环取值在 present() 内实时走 registry()，参数留作 API 稳定
    auto* s = g;
    if (!s) return;
    if (s->fanOpen) collapseLocked(w);
    else present(w);
}

void bringToFront(HWND w) {
    if (!w) return;
    ::SetWindowPos(w, HWND_TOPMOST, 0, 0, 0, 0,
                   SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
}

// 热键入口（M4-c(b)）：球窗口有自己的线程、热键回调在热键线程 —— 直接调
// expand/collapse 会跨线程改 BallState 并提交 GDI+ 位图，所以只投消息。
void toggleBallFan() {
    if (!g_ballWnd) return;   // 球未创建：静默无操作
    ::PostMessageW(g_ballWnd, kFanHotkeyMsg, 0, 0);
}

// —— 可见性（托盘「显示/隐藏悬浮球」+ 面板设置开关）——
// 球窗口活在面板 UI 线程上，但托盘回调在托盘线程 → 统一只投消息，
// ShowWindow/绘制回到球所在线程做（不跨线程碰 GDI+ 位图）。
constexpr UINT kBallShowMsg = WM_APP + 42;
constexpr UINT kBallHideMsg = WM_APP + 43;

void showBall() {
    if (!g_ballWnd) return;
    ::PostMessageW(g_ballWnd, kBallShowMsg, 0, 0);
}

void hideBall() {
    if (!g_ballWnd) return;
    ::PostMessageW(g_ballWnd, kBallHideMsg, 0, 0);
}

void toggleBallVisible() {
    if (!g_ballWnd) return;
    if (::IsWindowVisible(g_ballWnd)) hideBall(); else showBall();
}

bool ballVisible() { return g_ballWnd && ::IsWindowVisible(g_ballWnd); }
bool ballCreated() { return g_ballWnd != nullptr; }

LRESULT CALLBACK ballWndProc(HWND w, UINT m, WPARAM wp, LPARAM lp) {
    switch (m) {
    case WM_ERASEBKGND:
        return 1;                          // layered 表面自绘，不给系统擦

    case WM_SETCURSOR:
        ::SetCursor(::LoadCursorW(nullptr, IDC_HAND));
        return TRUE;

    case WM_LBUTTONDOWN: {
        auto* s = g;
        if (!s) return 0;
        const POINT pt{ GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };

        // 扇形项上：执行并收扇（enabled=false 只收不执行）
        if (s->fanOpen) {
            for (const auto& h : s->hits) {
                if (::PtInRect(&h.rc, pt)) {
                    const Action* a = registry().find(h.actionId);
                    if (h.enabled && a && (!a->enabled_fn || a->enabled_fn()) && a->run_fn)
                        a->run_fn();
                    collapseLocked(w);
                    return 0;
                }
            }
        }

        // 球上：按压脉冲 + 抓球。开着扇时先收（本次 UP 不再翻转开合）
        s->wasOpen = s->fanOpen;
        if (s->fanOpen) collapseLocked(w);
        s->pulse.kick();
        ::SetTimer(w, kTimerAnim, kTickMs, nullptr);
        ::SetCapture(w);
        s->dragging = true;
        s->moved    = false;
        s->anim     = false;               // 按下即停贴边（legacy 同款）
        ::GetCursorPos(&s->dragPt);
        ::GetWindowRect(w, &s->startRc);
        present(w);                        // 立刻给出按压反馈
        return 0;
    }

    case WM_MOUSEMOVE: {
        auto* s = g;
        if (!s || !s->dragging) return 0;
        POINT cur{};
        ::GetCursorPos(&cur);
        const int dx = cur.x - s->dragPt.x;
        const int dy = cur.y - s->dragPt.y;
        if (!s->moved && (dx > kDragSlop || dx < -kDragSlop || dy > kDragSlop || dy < -kDragSlop))
            s->moved = true;
        if (s->moved)
            ::SetWindowPos(w, HWND_TOPMOST, s->startRc.left + dx, s->startRc.top + dy,
                           0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        return 0;
    }

    case WM_LBUTTONUP: {
        auto* s = g;
        if (!s) return 0;
        if (::GetCapture() == w) ::ReleaseCapture();
        s->dragging = false;
        if (!s->moved) {
            // 未移动 = 点击：开扇；「按下时已开、被先收掉」的那次不再翻回去
            if (!s->wasOpen) openFanFromRegistry(w);
            return 0;
        }
        ::GetWindowRect(w, &s->fromRc);
        s->toRc = snapTarget(w);
        s->anim = true;
        s->snapT0 = ::GetTickCount64();
        ::SetTimer(w, kTimerAnim, kTickMs, nullptr);
        return 0;
    }

    case WM_CAPTURECHANGED:             // 拖动中失焦（alt-tab 等）：静默复位，不吞事件
        if (g) g->dragging = false;
        return 0;

    case WM_TIMER: {
        auto* s = g;
        if (!s) return 0;
        if (wp == kTimerBadge) {
            bool changed = false;
            if (s->fanOpen) {
                auto nb = queryBadges(s->items);
                if (nb != s->badges) { s->badges = std::move(nb); changed = true; }
            } else {
                const bool ring = anyHighBadge();
                if (ring != s->lastRing) { s->lastRing = ring; changed = true; }
            }
            if (changed) present(w);
            return 0;
        }
        if (wp != kTimerAnim) return 0;

        bool busy = false;
        if (s->pulse.active) {
            (void)s->pulse.advance(kTickMs);   // 收敛那一帧也 present，回落到 1.0
            present(w);
            busy = s->pulse.active;
        }
        if (s->anim) {
            double t = (double)(::GetTickCount64() - s->snapT0) / kSnapMs;
            if (t > 1) t = 1;
            const double e = fx::easeInOut(t);
            const int x = (int)(s->fromRc.left + (s->toRc.left - s->fromRc.left) * e);
            const int y = (int)(s->fromRc.top + (s->toRc.top - s->fromRc.top) * e);
            ::SetWindowPos(w, HWND_TOPMOST, x, y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
            if (t >= 1) s->anim = false;
            else busy = true;
        }
        if (!busy) ::KillTimer(w, kTimerAnim);
        return 0;
    }

    case WM_DISPLAYCHANGE: {            // 屏幕拓扑变化：立即回工作区（不做动画，避免满屏飞）
        auto* s = g;
        if (s && !s->anim && !s->fanOpen && !s->dragging) {
            const RECT t = snapTarget(w);
            ::SetWindowPos(w, HWND_TOPMOST, t.left, t.top, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        }
        return 0;
    }

    case WM_DPICHANGED: {              // 拖到 DPI 不同的副屏：按新 DPI 重算三径
        auto* s = g;
        if (!s) return 0;
        s->dpi = HIWORD(wp);
        s->ballPx = dip(kBallDip, s->dpi);
        s->itemPx = dip(kItemDip, s->dpi);
        s->fanR   = dip(kFanRadiusDip, s->dpi);
        RECT rc{};
        ::GetWindowRect(w, &rc);
        const POINT c{ rc.left + s->cx, rc.top + s->cy };
        if (s->fanOpen) layoutOpen(s); else layoutClosed(s);
        ::SetWindowPos(w, HWND_TOPMOST, c.x - s->side / 2, c.y - s->side / 2,
                       s->side, s->side, SWP_NOACTIVATE);
        present(w);
        return 0;
    }

    // 显隐（托盘 / 面板设置投消息过来）：只动可见性，不碰 GDI+ 位图
    case kBallShowMsg: {
        if (g) present(w);
        ::ShowWindow(w, SW_SHOWNOACTIVATE);
        return 0;
    }
    case kBallHideMsg: {
        if (g && g->fanOpen) collapseLocked(w);
        ::ShowWindow(w, SW_HIDE);
        return 0;
    }
    case kFanHotkeyMsg: {              // 热键：开→收扇；收→按注册表开扇
        auto* s = g;
        if (!s) return 0;
        if (s->fanOpen) collapseLocked(w);
        else openFanFromRegistry(w);
        return 0;
    }

    case WM_PAINT: {                   // 表面归 UpdateLayeredWindow；这里只确认无效区
        PAINTSTRUCT ps;
        ::BeginPaint(w, &ps);
        ::EndPaint(w, &ps);
        return 0;
    }

    case WM_NCDESTROY:
        g_ballWnd = nullptr;
        return ::DefWindowProcW(w, m, wp, lp);

    default:
        return ::DefWindowProcW(w, m, wp, lp);
    }
}

bool createBall() {
    if (g_ballWnd) return true;
    const HINSTANCE inst = ::GetModuleHandleW(nullptr);

    WNDCLASSEXW wc{ sizeof(wc) };
    wc.lpfnWndProc   = ballWndProc;
    wc.hInstance     = inst;
    wc.hCursor       = ::LoadCursorW(nullptr, IDC_HAND);
    wc.hbrBackground = nullptr;
    wc.lpszClassName = kClass;
    if (!::RegisterClassExW(&wc) && ::GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        APX_LOGI("球 v2 窗口类注册失败: {}", ::GetLastError());
        return false;
    }

    auto* s = new BallState();
    s->dpi = ::GetDpiForSystem();
    s->ballPx = dip(kBallDip, s->dpi);
    s->itemPx = dip(kItemDip, s->dpi);
    s->fanR   = dip(kFanRadiusDip, s->dpi);
    layoutClosed(s);

    Gdiplus::GdiplusStartupInput gsi;
    if (Gdiplus::GdiplusStartup(&s->gdip, &gsi, nullptr) == Gdiplus::Ok) s->gdipOk = true;
    if (!s->gdipOk) {
        APX_LOGI("球 v2 GDI+ 启动失败");
        delete s;
        return false;
    }

    /* v223.29.15 修复（issue：「日志已创建、屏幕上始终没有球」）：
       per-pixel 分层窗口的渲染**全部**走 UpdateLayeredWindow，而它的硬前置条件是
       窗口带 WS_EX_LAYERED —— 旧代码漏了该样式，UpdateLayeredWindow 每次都失败且
       无人检查返回值：ShowWindow 照跑、"球 v2 已创建"照打，但窗口无任何像素
       （WM_PAINT 空走、背景 brush 为空）→ 2026-11-06 用户实测「一直看不到悬浮球」
       与日志「已创建」并存的根因。legacy 壳只用 Region 裁圆不走 layered，所以历史
       legacy 球可见、v2 球自诞生起没人见过。 */
    HWND w = ::CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_LAYERED,
        kClass, L"", WS_POPUP,
        0, 0, s->side, s->side, nullptr, nullptr, inst, nullptr);
    if (!w) {
        APX_LOGI("球 v2 窗口创建失败: {}", ::GetLastError());
        Gdiplus::GdiplusShutdown(s->gdip);
        delete s;
        return false;
    }

    g = s;
    g_ballWnd = w;

    // 初始位：主屏右缘垂直居中（与 legacy 壳一致 → 扇形朝左开）
    RECT wa{};
    ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    const int y = wa.top + ((wa.bottom - wa.top) - s->side) / 2;
    ::SetWindowPos(w, HWND_TOPMOST, wa.right - s->side, y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);

    present(w);
    s->lastRing = anyHighBadge();
    ::ShowWindow(w, SW_SHOWNOACTIVATE);
    ::SetTimer(w, kTimerBadge, kBadgeMs, nullptr);
    APX_LOGI("球 v2 已创建（{}px，动作 {} 个）", s->ballPx,
             (int)registry().all().size());
    return true;
}

void destroyBall() {
    if (!g_ballWnd) return;
    const HWND w = g_ballWnd;
    auto* s = g;
    ::KillTimer(w, kTimerAnim);
    ::KillTimer(w, kTimerBadge);
    g = nullptr;             // 先摘状态：销毁过程中的消息不再碰它
    g_ballWnd = nullptr;
    ::DestroyWindow(w);
    if (s) {
        if (s->gdipOk) Gdiplus::GdiplusShutdown(s->gdip);
        delete s;
    }
    APX_LOGI("球 v2 已销毁");
}

} // namespace ball
} // namespace apxpc

#endif // _WIN32
