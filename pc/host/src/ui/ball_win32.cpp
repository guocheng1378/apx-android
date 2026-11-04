// 桌面悬浮球实现 —— 设计说明见 ball_win32.hpp 顶部注释。
//
// 实现取舍：
//   · 裁形用 SetWindowRgn(CreateEllipticRgn) 而非 per-pixel layered：圆球
//     只需纯色填充，Region 裁边足够，且省掉 UpdateLayeredWindow 的 DIB
//     生命周期管理（仓库里 file_panel/settings 也都是最朴素的 Win32 做法）。
//   · 贴边动画 SetTimer 16ms 推进 + easeInOutQuad，时长 160ms，与 Android
//     侧 dock 滑入动效同参数（v223.29.11 对齐观感）。
//   · 拖动用 SetCapture + 屏幕坐标，跨显示器（含负坐标）天然正确。
#include "apxpc/ui/ball_win32.hpp"

#if defined(_WIN32)

#include "apxpc/log.hpp"

namespace apxpc::ui {
namespace {

constexpr wchar_t kClass[]   = L"APXFloatingBall";
constexpr int     kBallDip   = 44;    // 直径（DIP，96DPI 下 44px）
constexpr int     kSnapMs    = 160;   // 贴边滑入时长（对齐 Android dock 动效）
constexpr int     kTickMs    = 16;    // 动画帧间隔
constexpr UINT_PTR kTimerAnim = 1;
constexpr int     kDragSlop  = 3;     // 位移超过 3px 才算拖动，否则算点击

struct BallData {
    FloatingBall::OpenCb onOpen;

    bool   dragging = false;
    bool   moved    = false;          // 本次按下是否发生了位移（否则为点击）
    POINT  dragPt   {0, 0};           // 按下时的光标（屏幕坐标）
    RECT   startRc  {0, 0, 0, 0};     // 按下时的窗口矩形

    bool      anim = false;           // 贴边动画进行中
    RECT      from {0, 0, 0, 0};
    RECT      to   {0, 0, 0, 0};
    ULONGLONG t0   = 0;

    HFONT font = nullptr;
};

int dip(int v, UINT dpi) { return ::MulDiv(v, (int)dpi, 96); }

// easeInOutQuad：近似 Android AccelerateDecelerate 插值器
double ease(double t) {
    if (t < 0.5) return 2 * t * t;
    const double u = -2 * t + 2;
    return 1 - u * u / 2;
}

// 目标：最近的左右屏边 + y 夹进工作区（与 Android settleAfterDrag 同构）
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

// 椭圆裁形（region 由窗口持有，SetWindowRgn 成功后不用手动删）
void applyRegion(HWND w, int sizePx) {
    HRGN r = ::CreateEllipticRgn(0, 0, sizePx, sizePx);
    if (r) ::SetWindowRgn(w, r, TRUE);
}

void invalidate(HWND w) { ::InvalidateRect(w, nullptr, TRUE); }

LRESULT CALLBACK wndProc(HWND w, UINT m, WPARAM wp, LPARAM lp) {
    auto* d = reinterpret_cast<BallData*>(::GetWindowLongPtrW(w, GWLP_USERDATA));
    switch (m) {
    case WM_ERASEBKGND:
        return 1;                       // 全部交给 WM_PAINT，避免闪烁

    case WM_SETCURSOR:
        ::SetCursor(::LoadCursorW(nullptr, IDC_HAND));
        return TRUE;

    case WM_LBUTTONDOWN: {              // 抓球：停掉贴边动画（Android 同款“按下即停”）
        if (!d) return 0;
        ::SetCapture(w);
        d->dragging = true;
        d->moved    = false;
        if (d->anim) { ::KillTimer(w, kTimerAnim); d->anim = false; }
        ::GetCursorPos(&d->dragPt);
        ::GetWindowRect(w, &d->startRc);
        return 0;
    }

    case WM_MOUSEMOVE: {
        if (!d || !d->dragging) return 0;
        POINT cur{};
        ::GetCursorPos(&cur);
        const int dx = cur.x - d->dragPt.x;
        const int dy = cur.y - d->dragPt.y;
        if (!d->moved && (dx > kDragSlop || dx < -kDragSlop ||
                          dy > kDragSlop || dy < -kDragSlop))
            d->moved = true;
        if (d->moved)
            ::SetWindowPos(w, HWND_TOPMOST, d->startRc.left + dx,
                           d->startRc.top + dy, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        return 0;
    }

    case WM_LBUTTONUP: {
        if (!d) return 0;
        if (::GetCapture() == w) ::ReleaseCapture();
        d->dragging = false;
        if (!d->moved) {
            if (d->onOpen) d->onOpen();     // 点击 → 唤回面板
            return 0;
        }
        RECT rc{};
        ::GetWindowRect(w, &rc);
        d->from = rc;
        d->to   = snapTarget(w);
        d->anim = true;
        d->t0   = ::GetTickCount64();
        ::SetTimer(w, kTimerAnim, kTickMs, nullptr);
        return 0;
    }

    case WM_CAPTURECHANGED:             // 拖动中失焦（alt-tab 等）：静默复位，不吞事件
        if (d) d->dragging = false;
        return 0;

    case WM_TIMER: {
        if (!d || wp != kTimerAnim || !d->anim) return 0;
        double t = (double)(::GetTickCount64() - d->t0) / kSnapMs;
        if (t > 1) t = 1;
        const double e = ease(t);
        const int x = (int)(d->from.left  + (d->to.left  - d->from.left)  * e);
        const int y = (int)(d->from.top   + (d->to.top   - d->from.top)   * e);
        ::SetWindowPos(w, HWND_TOPMOST, x, y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        if (t >= 1) { d->anim = false; ::KillTimer(w, kTimerAnim); }
        return 0;
    }

    case WM_DISPLAYCHANGE: {           // 屏幕拓扑变化：立即回工作区（不做动画，避免满屏飞）
        if (d && !d->anim) {
            const RECT t = snapTarget(w);
            ::SetWindowPos(w, HWND_TOPMOST, t.left, t.top, 0, 0,
                           SWP_NOSIZE | SWP_NOACTIVATE);
        }
        return 0;
    }

    case WM_DPICHANGED: {              // 拖到 DPI 不同的副屏：按新 DPI 重算直径与裁形
        const int s = dip(kBallDip, HIWORD(wp));
        ::SetWindowPos(w, nullptr, 0, 0, s, s,
                       SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
        applyRegion(w, s);
        invalidate(w);
        return 0;
    }

    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = ::BeginPaint(w, &ps);
        RECT rc{};
        ::GetClientRect(w, &rc);
        const int cw = rc.right - rc.left;
        const int ch = rc.bottom - rc.top;
        HBRUSH br   = ::CreateSolidBrush(RGB(38, 107, 229));
        HBRUSH oldb = (HBRUSH)::SelectObject(hdc, br);
        HPEN pen    = ::CreatePen(PS_SOLID, 0, RGB(38, 107, 229));
        HPEN oldp   = (HPEN)::SelectObject(hdc, pen);
        ::Ellipse(hdc, 0, 0, cw, ch);
        ::SelectObject(hdc, oldp);
        ::DeleteObject(pen);
        ::SelectObject(hdc, oldb);
        DeleteObject(br);
        ::SetBkMode(hdc, TRANSPARENT);
        ::SetTextColor(hdc, RGB(255, 255, 255));
        HFONT oldf = nullptr;
        if (d && d->font) oldf = (HFONT)::SelectObject(hdc, d->font);
        ::DrawTextW(hdc, L"A", -1, &rc, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        if (oldf) ::SelectObject(hdc, oldf);
        ::EndPaint(w, &ps);
        return 0;
    }

    case WM_NCDESTROY:                 // 窗口销毁前摘掉数据指针，destroy() 统一回收
        ::SetWindowLongPtrW(w, GWLP_USERDATA, 0);
        return ::DefWindowProcW(w, m, wp, lp);

    default:
        return ::DefWindowProcW(w, m, wp, lp);
    }
}

} // namespace

bool FloatingBall::create(OpenCb onOpen) {
    if (hwnd_) return true;
    const HINSTANCE inst = ::GetModuleHandleW(nullptr);

    WNDCLASSEXW wc{ sizeof(wc) };
    wc.lpfnWndProc   = wndProc;
    wc.hInstance     = inst;
    wc.hCursor       = ::LoadCursorW(nullptr, IDC_HAND);
    wc.hbrBackground = nullptr;
    wc.lpszClassName = kClass;
    if (!::RegisterClassExW(&wc) && ::GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        APX_LOGE("悬浮球窗口类注册失败: {}", ::GetLastError());
        return false;
    }

    const UINT dpi = ::GetDpiForSystem();
    const int s    = dip(kBallDip, dpi);

    auto* d = new BallData();
    d->onOpen = std::move(onOpen);
    d->font   = ::CreateFontW(-s * 40 / 100, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
                              DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                              CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");

    HWND w = ::CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
        kClass, L"", WS_POPUP,
        0, 0, s, s, nullptr, nullptr, inst, nullptr);
    if (!w) {
        APX_LOGE("悬浮球窗口创建失败: {}", ::GetLastError());
        if (d->font) ::DeleteObject(d->font);
        delete d;
        return false;
    }
    ::SetWindowLongPtrW(w, GWLP_USERDATA, reinterpret_cast<LPARAM>(d));
    hwnd_ = w;
    applyRegion(w, s);

    // 初始位：主屏右缘垂直居中（与 Android 默认一致）
    RECT wa{};
    ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    const int y = wa.top + ((wa.bottom - wa.top) - s) / 2;
    ::SetWindowPos(w, HWND_TOPMOST, wa.right - s, y, 0, 0,
                   SWP_NOSIZE | SWP_NOACTIVATE);
    ::ShowWindow(w, SW_SHOWNOACTIVATE);
    APX_LOGI("桌面悬浮球已创建（{}px，主屏右缘居中）", s);
    return true;
}

void FloatingBall::destroy() {
    if (!hwnd_) return;
    auto* d = reinterpret_cast<BallData*>(::GetWindowLongPtrW(hwnd_, GWLP_USERDATA));
    ::SetWindowLongPtrW(hwnd_, GWLP_USERDATA, 0);   // 先摘指针再销毁：无人再用它
    const HWND w = hwnd_;
    hwnd_ = nullptr;
    ::DestroyWindow(w);
    if (d) {
        if (d->font) ::DeleteObject(d->font);
        delete d;
    }
    APX_LOGI("桌面悬浮球已销毁");
}

} // namespace apxpc::ui

#endif // _WIN32
