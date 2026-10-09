#pragma once
// WebView2 桌面控制面板（v2 架构）。
//
// 设计：
//   · UI 层全部交给 React + Tailwind（pc/web/），C++ 只做三件事：
//     ① 创建 WebView2 窗口并加载 dist/index.html（或 exe 内嵌资源）
//     ② 每 400ms 把宿主状态快照 PostWebMessageAsJson 推给前端
//     ③ 接收前端 postMessage（动作请求 / 查询 / 文件选择对话框等），分发到
//        既有的 WirelessSession / MediaSession / ActionRouter / TrayIcon
//
//   · 托盘保留（WebView2 本身做不了托盘），右键菜单回调改为 ShowWindow 而
//     不是开浏览器 —— 与原 Win32 面板的托盘行为一致。
//
//   · 崩溃取证、防火墙放行、9511 受控端注册 这三件事仍由 desktop_main.cpp
//     负责 —— 本文件只负责"UI 生命周期 + IPC 桥"，不越界。
//
// ---------------------------------------------------------------------------
// IPC 协议（前端 ↔ 后端，单向 PostWebMessageAsJson）
//   前端 → 后端：
//     {"kind":"act",     "id":"req-1", "name":"screen.toggle", "payload":{}}
//     {"kind":"query",   "id":"req-2", "name":"state"}
//     {"kind":"open-file","id":"req-3", "filter":"全部文件|*.*|图片|*.png;*.jpg"}
//   后端 → 前端：
//     {"kind":"act-response",     "id":"req-1", "ok":true,  "data":{…}}
//     {"kind":"query-response",   "id":"req-2", "ok":true,  "data":{…}}
//     {"kind":"open-file-response","id":"req-3","ok":true,  "path":"C:\\…\\foo.png"}
//     {"kind":"state",            "t":12345,   "data":{…}}   ← 400ms 推
//     {"kind":"event",            "name":"on-remote-input", "hint":"搜索框"}
// ---------------------------------------------------------------------------
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "apxpc/config/app_config.hpp"

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace apxpc::wireless {
class WirelessSession;
class FileReceiver;
}

namespace apxpc::media {
class MediaSession;
class ScreenPush;
class AudioCapture;
class MicBridge;
}

namespace apxpc::api { class ActionRouter; }
namespace apxpc::tray { class TrayIcon; }
namespace apxpc::hotkey { class HotkeyManager; }

namespace apxpc::ui {

// ———————————————— 宿主对象捆绑（给 web_panel 访问） ———————————————
// 把 desktop_main.cpp / 原 panel_win32.cpp 里散落在 Panel struct 里的所有宿主对象
// 收进一个可共享的 bundle —— web_panel 只通过它访问，不直接 include 具体实现。
// 这些对象的所有权属于调用方（desktop_main.cpp）；bundle 里放裸指针/引用。
struct HostBundle {
    // 核心会话
    wireless::WirelessSession*   session      = nullptr;
    wireless::FileReceiver*      fileReceiver = nullptr;

    // 媒体
    media::MediaSession*         media        = nullptr;
    media::ScreenPush*           screenPush   = nullptr;
    media::AudioCapture*         audio        = nullptr;
    media::MicBridge*            micBridge    = nullptr;

    // 托盘 / 热键
    tray::TrayIcon*              tray         = nullptr;
    hotkey::HotkeyManager*       hotkey       = nullptr;

    // 配置（读写同一份 config.json）
    config::AppConfig*           cfg          = nullptr;
    std::string                  configPath;
};

// ———————————————— 运行（阻塞直到退出） ———————————————
// webDir：前端 dist/ 目录（或 exe 内嵌资源解压后的临时目录）。空则用 exe 目录下的 ./web。
// bundle：宿主对象捆绑（上面这些裸指针都可以空 —— 空的时候前端只显示"功能未初始化"）。
int runWebPanel(const HostBundle& bundle, const std::string& webDir = {});

// ———————————————— 平台探测 ———————————————
bool webPanelAvailable();

}  // namespace apxpc::ui
