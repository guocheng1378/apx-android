// 输入回声标记（issue#171 回环第二道防线）：
// 本进程注入的键鼠事件会流经本进程的捕获 tap（macOS kCGHIDEventTap），
// 捕获器若不识别就会「注入→捕获→回传手机」形成回声环路（鼠标漂移/回环）。
// 第一道过滤 kCGEventSourceUnixProcessID==getpid() 在部分系统版本上不可靠
// （223.41 实测镜像开启仍回环，疑似 HID tap 阶段该字段被系统改写），
// 这里补 kCGEventSourceUserData 魔数标记：注入器 post 前打标，捕获器见标即放过。
// 注：与剪贴板回环抑制（clipSuppressFor 600ms 时间窗）不同，这里是逐事件标记，
// 不依赖时间窗 —— 触控板连续注入时时间窗会把物理输入也整段吞掉，不可用。
#pragma once

namespace apx {

/// 注入事件魔数（"APXINJ"）：捕获器据此识别本进程注入的事件。
constexpr int64_t kSelfInjectTag = 0x415058494E4A;

}  // namespace apx
