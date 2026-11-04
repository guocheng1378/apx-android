#pragma once
// 桌面悬浮球（v223.29.11 · 批 P-1）：屏幕边缘的可拖拽圆球，点击唤回面板。
//
// 为什么是原生窗口而不是 React：球要在**面板窗口之外**常驻（主窗 X 只是
// 隐藏到托盘，见 panel_win32 的 WM_CLOSE 语义），WebView 里画不出来 ——
// 只能是独立的置顶窗。与 file_panel / settings 同一套零依赖 Win32 范式；
// 非 Windows 为空桩（mac 后续走 NSPanel）。
//
// 行为（对齐 Android 悬浮球 v223.29.11）：
//   · 拖动跟手；松手贴最近的左右屏边滑入收条（160ms 缓动，非瞬间跳变）
//   · 按下即停掉进行中的贴边动画（位置以手指为准）
//   · 点击（未拖动）→ onOpen 回调（web_panel 里接 ShowWindow 唤回面板）
//   · 置顶、不进任务栏（WS_EX_TOOLWINDOW）、不抢焦点（WS_EX_NOACTIVATE）
//   · 椭圆 Region 裁形 + 纯色自绘，不依赖 GDI+ / 图片资源
#include <functional>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace apxpc::ui {

class FloatingBall {
public:
    using OpenCb = std::function<void()>;

    FloatingBall() = default;
    ~FloatingBall() { destroy(); }
    FloatingBall(const FloatingBall&) = delete;
    FloatingBall& operator=(const FloatingBall&) = delete;

#if defined(_WIN32)
    // 在**当前线程**创建球窗（web_panel 的消息循环线程，窗口消息才能被
    // 既有 GetMessage 循环泵到）。创建失败返回 false —— 球是增强功能，
    // 失败只记日志，不抛不弹框，不拖累面板。
    bool create(OpenCb onOpen);
    void destroy();

private:
    HWND hwnd_ = nullptr;
    OpenCb onOpen_;
#else
    // 非 Windows 空桩：不创建任何窗口。
    bool create(OpenCb) { return false; }
    void destroy() {}
#endif
};

} // namespace apxpc::ui
