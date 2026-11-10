#pragma once
// WebView2 桌面控制面板（v2 架构）。
//
// 设计：
//   · UI 层全部交给 React + Tailwind（pc/web/），C++ 只做三件事：
//     ① 创建 WebView2 窗口并加载 dist/index.html（或 exe 内嵌资源）
//     ② 每 400ms 把宿主状态快照 PostWebMessageAsJson 推给前端
//     ③ 接收前端 postMessage（动作请求 / 查询 / 文件选择对话框等），分发到
//        既有 WirelessSession / MediaSession / ActionRouter / TrayIcon
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
//     {"kind":"open-file-response","id":"req-3", "ok":true,  "path":"C:\\…\\foo.png"}
//     {"kind":"state",            "t":12345,   "data":{…}}   ← 400ms 推
//     {"kind":"event",            "name":"on-remote-input", "hint":"搜索框"}
// ---------------------------------------------------------------------------
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "apxpc/config/app_config.hpp"
// PhoneFileEntry（0x45 目录应答条目）完整类型 —— notifyPhoneFileList 的入参要用；
// ctrl9511.hpp 在 Windows 上先带 winsock2.h，必须早于下面的 windows.h（LEAN_AND_MEAN 已设）。
#include "apxpc/wireless/ctrl9511.hpp"

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace apxpc::wireless {
class WirelessSession;
class FileReceiver;
class Ctrl9511Server;
}

namespace apxpc::media {
class MediaSession;
class ScreenPush;
class AudioCapture;
class MicBridge;
}

namespace apxpc::localsend { class LocalSendReceiver; }
namespace apxpc::api { class ActionRouter; }
namespace apxpc::tray { class TrayIcon; }
namespace apxpc::hotkey { class HotkeyManager; }

namespace apxpc::ui {

// ———————————————— 宿主对象捆绑（给 web_panel 访问） ————————————————
// 把 desktop_main.cpp / 原 panel_win32.cpp 里散落在 Panel struct 里的所有宿主对象
// 收进一个可共享的 bundle —— web_panel 只通过它访问，不直接 include 具体实现。
// 这些对象的所有权属于调用方（desktop_main.cpp）；bundle 里放裸指针/引用。
struct HostBundle {
    // 核心会话
    wireless::WirelessSession*   session      = nullptr;
    wireless::FileReceiver*      fileReceiver = nullptr;

    // 9511 受控端（Win 上由 desktop_main 创建）：web_panel 的 buildState 读它回填
    // phone（0x31 解码）/ inputTrusted / ctrlServer —— 以前不传，Win 壳的连接卡
    // 三行模式显示永远「未上报」，即使手机实际连着并报了 0x31。
    wireless::Ctrl9511Server*    ctrlSrv      = nullptr;

    // 互传（LocalSend 协议 v2.2）：PC 侧接收端。落盘目录由调用方决定（面板里取
    // FileReceiver::defaultDir()，与「文件传输」面板同一目录）；为空时前端显示「未初始化」。
    localsend::LocalSendReceiver* localSend    = nullptr;

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

// ———————————————— 运行（阻塞直到退出） ————————————————
// webDir：前端 dist/ 目录（或 exe 内嵌资源解压后的临时目录）。空则用 exe 目录下的 ./web。
// bundle：宿主对象捆绑（上面这些裸指针都可以空 —— 空的时候前端只显示"功能未初始化"）。
int runWebPanel(const HostBundle& bundle, const std::string& webDir = {});

// ———————————————— 文件接收通知（desktop_main 在 FileReceiver 接收线程调用） ————————————————
// 托盘气泡提示 + 推 on-file-received 事件给前端文件面板；面板未就绪时仅记日志。
// PostWebMessageAsJson 线程安全，接收线程可直接调（同 web_panel.cpp postEvent 注释的约定）。
void notifyFileReceived(const std::string& path);

// ———————————————— 手机文件浏览（0x44–0x47，2026-11-11，能力位 bit3） ————————————————
// desktop_main 在 9511 回调线程调用：把目录应答 / 取文件失败回执推给前端
//「浏览手机文件」区（on-phone-files / on-phone-files-fail 事件）。线程安全同上。
// 仅 Windows（mac 走 panel_http，本轮未接，如实降级）。
void notifyPhoneFileList(uint8_t err, const std::string& path,
                         const std::vector<apxpc::wireless::PhoneFileEntry>& entries);
void notifyPhoneFileFail(uint8_t err, const std::string& path);

// ———————————————— 相册投影（0x48–0x4B，2026-11-12，能力位 bit4） ————————————————
// desktop_main 在 9511 回调线程调用：把相册分页应答 / 缩略图失败回执推给前端
// 相册网格（on-album-page / on-album-page-fail 事件）。线程安全同上。
// 仅 Windows（mac 走 panel_http，本轮未接，如实降级）。
void notifyAlbumPage(uint8_t err, uint32_t page,
                     const std::vector<apxpc::wireless::AlbumEntry>& entries);
void notifyAlbumPageFail(uint8_t err, const std::string& path);

// ———————————————— 更新通道（v223.29.16，desktop_main 更新检查线程调用） ————————————————
// 查到 GitHub releases 有新版 → 推 on-update-available 事件，前端顶部横幅展示；
// 点「查看」回 app.openUrl act（web_panel 白名单放行本仓库 release 页）。
// 面板未就绪时仅记日志（检查线程启动 +10s 才跑，正常不会走到这）。
// 仅 Windows（定义在 web_panel.cpp）；mac 走 panel_http，本轮未接。
void notifyUpdateAvailable(const std::string& latestTag, const std::string& currentTag,
                           const std::string& url);

// ———————————————— 平台探测 ————————————————
bool webPanelAvailable();

}  // namespace apxpc::ui
