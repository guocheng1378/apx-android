#pragma once
// WebView2 Runtime 自检测 + 自动安装（仅 Windows）。
//
// 背景：apxdesktop 的界面跑在 WebView2 里，老版 Win10（<2004）没有内置
// Runtime，用户手动去官网下载安装经常失败。这里提供两件事：
//   ① isWebView2RuntimeInstalled —— 查 EdgeUpdate 注册表判断是否已安装
//      （不依赖 WebView2 SDK，安装器 apxsetup 也能用）
//   ② ensureWebView2Runtime —— 缺失时从微软官方源下载安装包并装好（阻塞）
//
// 使用方：apxsetup（安装期预装）、web_panel（启动失败时自动补装并重试）。
#include <string>

namespace apxpc::ui {

/// 本机是否已有 WebView2 Runtime（查 HKCU/HKLM 的 EdgeUpdate Clients 注册表）
bool isWebView2RuntimeInstalled();

/// 确保 WebView2 Runtime 可用：已装直接返回 true；缺失则下载官方安装包并安装。
/// silent=true 时安装器以 /silent /install 无界面运行。失败时 err 返回原因。
bool ensureWebView2Runtime(std::wstring& err, bool silent = false);

}  // namespace apxpc::ui
