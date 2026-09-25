#pragma once
// 电脑端「文件传输」窗口：**看得到收到的文件，也发得出去**。
//
// 为什么要有它：手机端 / TV 端早就有文件面板（能看到对端推来的文件并转发），
// 电脑端此前只有托盘菜单里一个「发送文件到手机…」—— 收到的文件只是静静躺在
// 「下载\AllPeriph」里，托盘气泡一闪而过，之后再也找不到（也不会发回去）。
// 现在与另外两端一致：列出收到的文件，双击即**发回对端**。
//
// 纯 Win32 + common controls（不引 Qt/wx），与 panel_win32 同一套零依赖做法。
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

class FilePanel {
public:
    /// 打开（已打开则提到前台并刷新）。
    /// @param owner   父窗口（须为 UI 线程；托盘回调请先 Post 到面板线程再调）
    /// @param peerIp  取当前对端 IP（空串 = 还没连上；发送前会据此判定）
    /// @param notify  气泡提示（标题, 正文），与托盘同一条提示通道
    static void show(HWND owner,
                     std::function<std::string()> peerIp,
                     std::function<void(const std::string&, const std::string&)> notify);

    /// 收到文件时刷新列表。**可在接收线程调用**（内部 PostMessage 到窗口）。
    /// 窗口没打开就是空操作 —— 收到文件时用户可能根本没开面板，不必强行弹窗。
    static void refresh();
};

}  // namespace apxpc::ui
