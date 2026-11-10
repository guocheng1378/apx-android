#pragma once
// 验证码提取与一键复制（2026-11-11，补齐 ROADMAP §5.1①「验证码类通知在 PC 上一键复制」）。
//
// 为什么做成「自动复制」而不是气泡按钮：Windows 的 NIF_INFO 托盘气泡没有按钮交互，
// 用户看到验证码还得切到手机抄一遍。这里在通知上屏前拦截：命中验证码即写入 PC 本机
// 剪贴板，并把码回显到气泡正文——收到即已复制，等价于「一键」，只是没有那个键。
//
// 纯通知侧能力，不进 9511 协议：0x40 推送的 payload 不变，只在 PC 展示层加工。
#include <string>

namespace apxpc::wireless {

/// 判断通知是否为验证码类：命中则把验证码写入本机剪贴板，并经 codeOut（可空）返回码值。
///   · 返回 true = 命中且已成功写入剪贴板（调用方可放心在气泡里加「已复制」提示）；
///   · 返回 false = 未命中，或命中但剪贴板写入失败（此时不假装成功，调用方只展示原文）。
/// title/text 分别为通知标题与正文，内部拼接后统一扫描，调用方无需预处理。
bool copyIfVerifyCode(const std::string& title, const std::string& text,
                      std::string* codeOut = nullptr);

} // namespace apxpc::wireless
