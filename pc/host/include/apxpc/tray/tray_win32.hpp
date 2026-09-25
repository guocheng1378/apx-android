#pragma once
// 托盘常驻：图标 + 右键菜单（打开面板 / 退出）+ 开机自启。
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

namespace apxpc::tray {

// 开机自启：写入/删除 HKCU\...\Run 下的 AllPeriph 项。
// args 默认 "serve"（CLI 宿主以常驻服务方式自启）；桌面端传空串，直接拉起 GUI。
bool setAutostart(bool enable, const std::string& exePath = {},
                  const std::string& args = "serve");
bool autostartEnabled();

class TrayIcon {
public:
    TrayIcon();
    ~TrayIcon();

    // 创建托盘图标并启动消息循环线程。tip 为悬浮提示（UTF-8）。
    // icon 为空时退回系统默认图标；桌面端会传入 exe 内嵌的应用图标。
    bool create(const std::string& tip, HICON icon = nullptr);
    void setQuitCallback(std::function<void()> cb);
    // 打开面板回调（默认浏览 http://127.0.0.1:port）
    void setOpenCallback(std::function<void()> cb);
    // 「发送文件到手机…」回调（右键托盘菜单里那一项）；未设置则菜单不出该项
    void setSendFileCallback(std::function<void()> cb);
    void setPort(unsigned port) { port_ = port; }

    /// 气泡通知（托盘消息）：标题 + 正文（UTF-8）。
    /// 用于「收到手机传来的文件」这类不该打断用户、但必须让用户知道的提示。
    /// 非 Windows 平台为空实现；窗口/托盘未创建时静默忽略。
    void notify(const std::string& title, const std::string& text);

    void quit();

// 以下成员供本翻译单元内的线程/窗口过程自由函数访问（内部工具类，直接公开）
public:
    std::function<void()> onQuit_;
    std::function<void()> onOpen_;
    std::function<void()> onSendFile_;
    unsigned port_ = 47990;
#if defined(_WIN32)
    HWND window_ = nullptr;
    HANDLE thread_ = nullptr;
    DWORD tid_ = 0;
    bool running_ = false;
#else
    void* window_ = nullptr;
    void* thread_ = nullptr;
    unsigned tid_ = 0;
    bool running_ = false;
#endif
};

}  // namespace apxpc::tray
