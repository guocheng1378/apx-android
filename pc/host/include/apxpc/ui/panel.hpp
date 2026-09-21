#pragma once
// 控制面板。Windows 用纯 Win32 + common controls（不引入 Qt/wx 等第三方依赖）。
// 其它平台当前不提供 GUI（面板函数返回 -1），CLI/SDK 完全可用。
#include <string>

namespace apxpc::ui {

bool panelAvailable();

// 阻塞运行直到窗口关闭。返回 0 表示正常退出，负数表示不可用/失败。
// preferInstanceId 为空时自动挑第一个“像本工程 Gadget”的设备。
int runPanel(const std::string& preferInstanceId = {});

}  // namespace apxpc::ui
