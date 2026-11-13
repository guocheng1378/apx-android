// 通知回复小窗（0x41，2026-11-14，REQ-通知回复.md 第一批）：PC 托盘气泡点开 →
// 轻量输入框 → 提交即发 0x41 让手机代发回复。选型按 REQ 预案「WebView2 面板或原生小窗
// 实施时按现有模式选」——回复是气泡的即时动作，独立原生小窗（与 RemoteInput 同款 Win32
// 单实例窗口）最贴合：不用等面板起来、不用过 React 状态，点完即走。
// 单文件含 Win32 真实现与其它平台空桩（同 screenshot_win.cpp 口径）：托盘气泡与
// NIN_BALLOONUSERCLICK 都是 Win 托盘特性，mac 侧本功能暂缺、不声明无定义的链接。
#pragma once

#include <functional>
#include <string>

namespace apxpc::ui {

/// 打开（或置前）「回复手机通知」小窗。
/// @param key    Android 通知键（0x40 带来的，原样随 0x41 发回）
/// @param app    应用展示名（窗标题用）
/// @param title  通知标题/正文预览（窗内提示行）
/// @param submit 提交回调：发 0x41（两链路择活由调用方封装）；false = 没发出去
///               （未连接/对端不支持），窗口不关，提示走 @p notify
/// @param notify 失败提示出口（托盘气泡；线程安全）
/// 可在托盘线程调用（气泡点击回调所在线程，自有消息泵）；重复调用 = 换一条通知重开。
void showNotifyReply(const std::string& key, const std::string& app, const std::string& title,
                     const std::function<bool(const std::string& key, const std::string& text)>& submit,
                     const std::function<void(const std::string& title, const std::string& text)>& notify);

}  // namespace apxpc::ui
