#include "apxpc/media/touch_inject.hpp"
#include "apxpc/media/touch_target.hpp"

#include <apx/frame.h>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <windows.h>
#include <cstdio>
#include <cstdarg>
#include <string>
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

// ——— 触摸映射诊断日志 ———
// 用户报「触摸落错屏 / 手指滑动光标不动」时，这个文件是唯一现场：
//   %LOCALAPPDATA%\AllPeriph\touch_debug.log
void touchLog(const char* fmt, ...) {
    char dir[MAX_PATH]{};
    if (::GetEnvironmentVariableA("LOCALAPPDATA", dir, MAX_PATH) == 0) return;
    const std::string path = std::string(dir) + "\\AllPeriph\\touch_debug.log";
    FILE* f = nullptr;
    if (fopen_s(&f, path.c_str(), "a") != 0 || !f) return;
    SYSTEMTIME st{};
    ::GetLocalTime(&st);
    std::fprintf(f, "[%02d:%02d:%02d.%03d] ", st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    va_list ap; va_start(ap, fmt);
    std::vfprintf(f, fmt, ap);
    va_end(ap);
    std::fputc('\n', f);
    std::fclose(f);
}

// 查找「非主屏显示器」的矩形。
// ★ 必须用 found 标志判断有无结果，不能看 EnumDisplayMonitors 的返回值：
//   回调返回 FALSE（找到即停）时该 API 同样返回 FALSE，与「一个都没找到」无法区分。
//   旧实现据此判断 → 双屏时反而被判成「没有扩展屏」，回退主屏映射。
struct FindMonCtx { RECT rect{}; bool found = false; };

BOOL CALLBACK enumMonCb(HMONITOR hm, HDC, LPRECT, LPARAM lp) {
    auto* ctx = reinterpret_cast<FindMonCtx*>(lp);
    MONITORINFO mi{ sizeof(MONITORINFO) };
    if (!::GetMonitorInfoW(hm, &mi)) {
        touchLog("enum monitor: GetMonitorInfo failed, skip");
        return TRUE;
    }
    const bool primary = (mi.dwFlags & MONITORINFOF_PRIMARY) != 0;
    touchLog("enum monitor: primary=%d rect=(%ld,%ld)-(%ld,%ld)",
             primary ? 1 : 0, mi.rcMonitor.left, mi.rcMonitor.top,
             mi.rcMonitor.right, mi.rcMonitor.bottom);
    if (primary) return TRUE;      // 跳过主屏
    ctx->rect = mi.rcMonitor;
    ctx->found = true;
    return FALSE;                  // 找到第一个非主屏即停
}

bool resolveTargetRect(RECT& out) {
    const DWORD now = ::GetTickCount();
    if (g_targetRect.ok && now - g_targetRect.tick < 5000) {   // 5s 缓存，显示器布局变化自适应
        out.left = g_targetRect.x; out.top = g_targetRect.y;
        out.right = g_targetRect.x + g_targetRect.w; out.bottom = g_targetRect.y + g_targetRect.h;
        return true;
    }
    // 优先用 DDA 采集侧写入的矩形（touch_target.hpp 共享状态）——
    // 它精确等于推流抓取的那块屏幕，与手机看到的画面严格一致。
    int tx, ty, tw, th;
    if (apxpc::media::touchtarget::get(tx, ty, tw, th)) {
        // ★ 以「系统显示器矩形」为准，而不是 DDA 的纹理尺寸：
        //   SetCursorPos 的坐标空间由系统显示器决定，DDA 报的是它实际抓取的纹理尺寸。
        //   虚拟显示器的分辨率档没真正生效时两者会不一致 —— 真机实测
        //   纹理 1920x1080 vs 显示器 1280x720，触摸整体被缩放到 2/3（点右下角落中间）。
        LONG l = tx, tp = ty, r = tx + tw, b = ty + th;
        POINT c{ (l + r) / 2, (tp + b) / 2 };
        MONITORINFO mi{ sizeof(MONITORINFO) };
        HMONITOR hm = ::MonitorFromPoint(c, MONITOR_DEFAULTTONEAREST);
        if (hm && ::GetMonitorInfoW(hm, &mi)) {
            const LONG sw = mi.rcMonitor.right - mi.rcMonitor.left;
            const LONG sh = mi.rcMonitor.bottom - mi.rcMonitor.top;
            if (sw != tw || sh != th) {
                touchLog("target rect: DDA %dx%d != system %ldx%ld -> use system display",
                         tw, th, sw, sh);
            } else {
                touchLog("target rect: DDA %dx%d matches system display", tw, th);
            }
            if (sw > 0 && sh > 0) {
                l = mi.rcMonitor.left; tp = mi.rcMonitor.top;
                r = mi.rcMonitor.right; b = mi.rcMonitor.bottom;
            }
        } else {
            touchLog("target rect: from DDA capture (%d,%d) %dx%d", tx, ty, tw, th);
        }
        g_targetRect.x = l; g_targetRect.y = tp;
        g_targetRect.w = r - l; g_targetRect.h = b - tp;
        g_targetRect.tick = now; g_targetRect.ok = true;
        out.left = l; out.top = tp; out.right = r; out.bottom = b;
        return true;
    }
    FindMonCtx ctx{};
    ::EnumDisplayMonitors(nullptr, nullptr, enumMonCb, reinterpret_cast<LPARAM>(&ctx));
    if (!ctx.found) {
        // 没有非主屏（单屏 / 复制模式）：回退主屏映射（行为与旧版一致）
        g_targetRect = {};
        touchLog("target rect: no secondary monitor -> fallback primary mapping");
        return false;
    }
    const RECT mon = ctx.rect;
    g_targetRect.x = mon.left; g_targetRect.y = mon.top;
    g_targetRect.w = mon.right - mon.left; g_targetRect.h = mon.bottom - mon.top;
    g_targetRect.tick = now; g_targetRect.ok = true;
    out = mon;
    touchLog("target rect: secondary monitor (%ld,%ld)-(%ld,%ld) %ldx%ld",
             mon.left, mon.top, mon.right, mon.bottom,
             mon.right - mon.left, mon.bottom - mon.top);
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

    // 目标物理坐标（像素）。SetCursorPos 需要真像素坐标，不能再走 0..65535 归一化。
    LONG px = 0;
    LONG py = 0;
    RECT t{};
    const bool mapped = resolveTargetRect(t);
    if (mapped) {
        px = t.left + (nx * (t.right - t.left)) / 65535;
        py = t.top  + (ny * (t.bottom - t.top)) / 65535;
    } else {
        // 单屏 / 复制模式：归一化坐标直接映射主屏像素
        px = (nx * ::GetSystemMetrics(SM_CXSCREEN)) / 65535;
        py = (ny * ::GetSystemMetrics(SM_CYSCREEN)) / 65535;
    }
    // 只记按下/抬起：移动帧高频，全记会把日志刷爆
    if (action != kActMove) {
        touchLog("touch: action=%u buttons=%u norm=(%ld,%ld) mapped=%s phys=(%ld,%ld)",
                 static_cast<unsigned>(action), static_cast<unsigned>(buttons), nx, ny,
                 mapped ? "target" : "primary", px, py);
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

    // v184：定位改用 SetCursorPos（真实像素坐标），按键单独 SendInput。
    // 原因（真机实测）：MOUSEEVENTF_ABSOLUTE 的纯移动帧（不带任何按键变化）
    // 在部分环境下被系统静默丢弃 —— 表现为「手指滑动光标不动」，
    // 而带按键的帧却生效。SetCursorPos 定位可靠，且天然支持多屏/负坐标。
    ::SetCursorPos(px, py);
    if (click != 0) {
        INPUT in{};
        in.type = INPUT_MOUSE;
        in.mi.dwFlags = click;
        return ::SendInput(1, &in, sizeof(INPUT)) == 1;
    }
    return true;
#else
    (void)body; (void)len;
    return false;
#endif
}

}  // namespace apxpc::media
