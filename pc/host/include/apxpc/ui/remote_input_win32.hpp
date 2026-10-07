#pragma once
// 电脑端「对端请你输入」窗口：远程输入三端对等的**另一半**。
//
// 为什么要有它：手机端有 RemoteInputActivity、TV 端有 RemoteInputOverlay 两个界面来接
// 0x25（对端请求本机输入）—— 电脑端此前**只发不收**：托盘「让手机帮我输入…」发得出去，
// 但手机 / TV 反过来喊电脑打字时电脑毫无反应，因为 client_.onRequestInput 从来没被注册。
// 现在与另外两端一致：收到 0x25 弹输入框，边打字边回传，点「发送」落定。
//
// 纯 Win32 + common controls（不引 Qt/wx），与 panel_win32 / file_panel 同一套零依赖做法。
#include <cstdint>
#include <functional>
#include <string>

#if defined(_WIN32)
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace apxpc::ui {

class RemoteInput {
public:
    /// 弹出输入窗口（已打开则提到前台并换成新的提示语）。**须在 UI 线程调用**。
    /// @param owner    父窗口
    /// @param hint     对端给的提示语（如 "搜索"）；空则只显示默认文案
    /// @param sendText 回传文本（0x26）。flags 见 PROTOCOL：0x04=完整 0x08=取消。
    ///                 返回 false = 没连上，由本窗口提示，不静默假装成功。
    /// @param sendDone 通知对端输入完成（0x27）
    /// @param notify   气泡提示（标题, 正文），与托盘同一条提示通道
    static void show(HWND owner,
                     const std::string& hint,
                     std::function<bool(const std::string&, uint8_t)> sendText,
                     std::function<bool()> sendDone,
                     std::function<void(const std::string&, const std::string&)> notify);
};

}  // namespace apxpc::ui
