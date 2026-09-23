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

    // 创建托盘图标并启动消息循环线程。tip 为悬浮提示。
    bool create(const std::string& tip);
    void setQuitCallback(std::function<void()> cb);
    // 打开面板回调（默认浏览 http://127.0.0.1:port）
    void setOpenCallback(std::function<void()> cb);
    void setPort(unsigned port) { port_ = port; }

    void quit();

// 以下成员供本翻译单元内的线程/窗口过程自由函数访问（内部工具类，直接公开）
public:
    std::function<void()> onQuit_;
    std::function<void()> onOpen_;
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
