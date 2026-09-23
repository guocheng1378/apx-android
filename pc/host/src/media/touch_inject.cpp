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

}  // namespace

bool injectTouchFrame(const uint8_t* body, size_t len) {
#if defined(_WIN32)
    if (!body || len < 8) return false;
    const uint8_t action  = body[0];
    const uint8_t buttons = body[1];
    // 归一化坐标 0..65535：MOUSEEVENTF_ABSOLUTE（不带 VIRTUALDESK）本就映射
    // **主显示器** —— 副屏推的是 DDA 抓的主桌面，两者坐标系正好一致。
    const LONG x = static_cast<LONG>(apx::getU16(body + 2));
    const LONG y = static_cast<LONG>(apx::getU16(body + 4));
    if (action > kActCancel) return false;

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
    in.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | click;
    in.mi.dx = x;
    in.mi.dy = y;
    return ::SendInput(1, &in, sizeof(INPUT)) == 1;
#else
    (void)body; (void)len;
    return false;
#endif
}

}  // namespace apxpc::media
