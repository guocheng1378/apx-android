// 手柄截图抓屏模块（issue#170 #7，2026-11-14）：手机手柄页中央 📷 键发 0x4C，
// 受控 PC 在这里抓整个虚拟桌面（覆盖全部显示器）存 PNG，路径经托盘气泡回执给用户。
// 依赖仅 Win32 GDI（BitBlt 抓屏）+ GDI+（PNG 编码，gdiplus 已在 service 链接）；
// 非 Windows 平台返回空串 —— 手机无线手柄主打 Windows 受控端，mac 端本功能暂缺，
// 与「mac 走 panel_http 未接 9511 受控分发」的既有口径一致，不声明无定义的链接。
#pragma once

// 自足头文件：本头唯一对外类型是 std::string，自己带 <string>（CI 实锤：
// mac 上 windows.h 不存在，先前漏 include 编译红）。
#include <string>

namespace apxpc::screen {

/// 抓取整个虚拟桌面存为 PNG。成功返回绝对路径（保存到「图片\AllPeriph\手柄截图-时间戳.png」），
/// 失败返回空串。可在任意线程调用（9511 reader 线程直接调）；GDI+ 按进程只需初始化一次，
/// 内部已做一次性启动与线程安全处理。
std::string captureScreenToPng();

}  // namespace apxpc::screen
