#pragma once
// 桌面端控制面板。Windows 用纯 Win32 + common controls（不引入 Qt/wx 等第三方依赖）。
//
// 内容为**无线控制中枢**：手机信标发现 / TCP 建链 / 实时状态与注入计数 /
// 开机自启 / 托盘常驻（关闭窗口不退出，见 panel_win32.cpp）。
// 其它平台不提供 GUI（runPanel 返回 -1），CLI/SDK 完全可用。
#include <string>

namespace apxpc::ui {

bool panelAvailable();

// 阻塞运行直到窗口关闭或托盘退出。返回 0 表示正常退出，负数表示不可用/失败。
// preferInstanceId 预留给 USB 设备面板定位目标设备；无线模式不依赖它，传空即可。
int runPanel(const std::string& preferInstanceId = {});

}  // namespace apxpc::ui
