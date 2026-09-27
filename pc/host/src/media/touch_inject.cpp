#include "apxpc/media/touch_inject.hpp"

#include <apx/frame.h>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <windows.h>
#endif

namespace apxpc::media {
namespace {

constexpr uint8_t kActDown   = 0;
constexpr uint8_t kActUp     = 1;
constexpr uint8_t kActMove   = 2;
constexpr uint8_t kActCancel = 3;

constexpr uint8_t kBtnRight = 1u << 1;
constexpr uint8_t kBtnMid   = 1u << 2;

// ——————————————————————————————————————————————— v184：触摸目标屏映射 ——————
// 副屏是**扩展屏**：DDA 抓的是副屏画面，手机归一化坐标语义 = 副屏内相对位置。
// 旧实现 MOUSEEVENTF_ABSOLUTE（不带 VIRTUALDESK）按**主显示器** 0..65535 注入，
// 触摸区整体错位到 1 号屏。现在动态解析"非主屏显示器"的虚拟桌面矩形，
// 把坐标映射过去；单屏（无扩展屏）时回退旧的主屏映射，行为兼容。
struct TargetRect { LONG x = 0, y = 0, w = 0, h = 0; DWORD tick = 0; bool ok = false; };
TargetRect g_targetRect;

BOOL CALLBACK enumMonCb(HMONITOR hm, HDC, LPRECT, LPARAM lp) {
    MONITORINFO mi{ sizeof(MONITORINFO) };
    if (!::GetMonitorInfoW(hm, &mi)) return TRUE;
    // 跳过主屏：副屏 = 非主屏显示器（单副屏场景下唯一）
    if (mi.dwFlags & MONITORINFOF_PRIMARY) return TRUE;
    auto* out = reinterpret_cast<RECT*>(lp);
    *out = mi.rcMonitor;
    return FALSE;   // 找到第一个非主屏就停
}

bool resolveTargetRect(RECT& out) {
    const DWORD now = ::GetTickCount();
    if (g_targetRect.ok && now - g_targetRect.tick < 5000) {   // 5s 缓存，显示器布局变化自适应
        out.left = g_targetRect.x; out.top = g_targetRect.y;
        out.right = g_targetRect.x + g_targetRect.w; out.bottom = g_targetRect.y + g_targetRect.h;
        return true;
    }
    RECT mon{};
    if (!::EnumDisplayMonitors(nullptr, nullptr, enumMonCb, reinterpret_cast<LPARAM>(&mon))) {
        g_targetRect = {};   // 没有非主屏（单屏/复制模式）：回退主屏映射
        return false;
    }
    g_targetRect.x = mon.left; g_targetRect.y = mon.top;
    g_targetRect.w = mon.right - mon.left; g_targetRect.h = mon.bottom - mon.top;
    g_targetRect.tick = now; g_targetRect.ok = true;
    out = mon;
    return true;
}

}  // namespace

bool injectTouchFrame(const uint8_t* body, size_t len) {
#if defined(_WIN32)
    if (!body || len < 8) return false;
    const uint8_t action  = body[0];
    const uint8_t buttons = body[1];
    // v184：归一化坐标 0..65535 语义 = **副屏内相对位置**。旧实现直接按主屏
    // 0..65535 注入，扩展屏模式下触摸区整体错位到 1 号屏。
    // 现在映射到副屏（非主屏显示器）的虚拟桌面矩形：
    //   物理坐标 = 副屏原点 + 归一化 × 副屏尺寸
    //   VIRTUALDESK 绝对坐标 = (物理 - 虚拟桌面原点) × 65535 / 虚拟桌面尺寸
    // 单屏（无扩展屏）时回退主屏 0..65535 直映射，行为兼容。
    const LONG nx = static_cast<LONG>(apx::getU16(body + 2));
    const LONG ny = static_cast<LONG>(apx::getU16(body + 4));
    if (action > kActCancel) return false;

    LONG dx = nx;
    LONG dy = ny;
    DWORD extra = 0;
    RECT t{};
    if (resolveTargetRect(t)) {
        const LONG px = t.left + (nx * (t.right - t.left)) / 65535;
        const LONG py = t.top  + (ny * (t.bottom - t.top)) / 65535;
        const LONG vx = ::GetSystemMetrics(SM_XVIRTUALSCREEN);
        const LONG vy = ::GetSystemMetrics(SM_YVIRTUALSCREEN);
        const LONG vw = ::GetSystemMetrics(SM_CXVIRTUALSCREEN);
        const LONG vh = ::GetSystemMetrics(SM_CYVIRTUALSCREEN);
        dx = ((px - vx) * 65535L) / (vw > 0 ? vw : 1);
        dy = ((py - vy) * 65535L) / (vh > 0 ? vh : 1);
        extra = MOUSEEVENTF_VIRTUALDESK;
    }

    DWORD click = 0;
    switch (action) {
        case kActDown:   // 按哪个键由 buttons 决定（双指点按 = 右键，见 Android 侧）
            click = (buttons & kBtnRight) ? MOUSEEVENTF_RIGHTDOWN
                  : (buttons & kBtnMid)   ? MOUSEEVENTF_MIDDLEDOWN
                                          : MOUSEEVENTF_LEFTDOWN;
            break;
        case kActUp:
        case kActCancel:  // 取消按释放处理：绝对不能留下"键卡住"
            click = (buttons & kBtnRight) ? MOUSEEVENTF_RIGHTUP
                  : (buttons & kBtnMid)   ? MOUSEEVENTF_MIDDLEUP
                                          : MOUSEEVENTF_LEFTUP;
            break;
        default:
            break;  // move：只挪光标
    }

    INPUT in{};
    in.type = INPUT_MOUSE;
    in.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK | click;
    in.mi.dx = dx;
    in.mi.dy = dy;
    return ::SendInput(1, &in, sizeof(INPUT)) == 1;
#else
    (void)body; (void)len;
    return false;
#endif
}

}  // namespace apxpc::media
