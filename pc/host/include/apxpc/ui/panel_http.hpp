#pragma once
// 浏览器面板后端（方案 B）：本地 HTTP 服务 + React 前端 dist（exe 旁 web/）。
// 协议与 web_panel.cpp（WebView2 壳）同构 —— 同一套状态/动作/事件消息，前端仅换传输层。
// 详见 panel_http.cpp 顶部说明。本实现为 macOS 首选入口；Windows 继续走 web_panel（零交叉）。
#include <string>

#include "apxpc/ui/web_panel.hpp"   // HostBundle

namespace apxpc::ui {

/// 在 127.0.0.1 起本地面板服务，阻塞运行至 SIGINT/SIGTERM（或前端 window.quit 动作）。
/// webDir：前端 dist 目录（exe 旁 web/）；目录不存在时服务仍启动，首页给出获取指引。
/// 就绪后自动用系统默认浏览器打开 http://127.0.0.1:<port>/?host=1
/// （macOS 走 `open`；其他平台仅打印地址）。返回 0 正常退出；-1 服务创建失败。
int runWebPanelHttp(const HostBundle& bundle, const std::string& webDir = {});

/// 请求面板退出（线程安全；等价于前端 window.quit）。
///
/// 给**托盘菜单的「退出」**用：托盘跑在自己的线程上，不能直接碰面板状态。
/// 只置一个原子标志，面板线程在下一轮循环（≤200ms）自行收尾（停 9511、join 工作线程）。
/// 面板未运行时是 no-op。
void requestPanelQuit();

/// 请求退出本进程并延时重启（macOS「重启应用」按钮）：授权后必须重启才生效。
/// Win 侧是空桩（Windows 没有这道授权门槛）。
void requestRelaunch();

/// 9511「已有实例在跑」探测：本机 127.0.0.1:port 是否有人监听。
/// 面板启动前用它区分「端口被别人占着（另有 apxhost serve / apxdesktop）」与
/// 「真起不来」—— 前者不该打告警，也不该重复起一个受控端。
bool ctrlPortOccupied(uint16_t port, int timeoutMs = 200);

/// 当前面板地址（`http://127.0.0.1:<port>/?host=1`）；服务尚未就绪或已退出时返回空串。
///
/// 给**托盘菜单的「打开面板」**用：面板端口是 bind(0) 由内核分配的，托盘创建时还未知，
/// 只能在点击那一刻取。任意线程可调（内部加锁）。
std::string panelUrl();

}  // namespace apxpc::ui
