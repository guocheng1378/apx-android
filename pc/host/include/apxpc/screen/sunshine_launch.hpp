#ifndef APXPC_SCREEN_SUNSHINE_LAUNCH_HPP
#define APXPC_SCREEN_SUNSHINE_LAUNCH_HPP

// Sunshine **子进程托管** —— M6 推屏 spike 的执行面脚手架（可复用模块）。
//
// 与 sunshine_detect.hpp（只读检测，SunshineStatus / sunshineStatus() 在那边）分工：
// detect 回答「装没装 / 跑没跑」；本文件回答「怎么由我们拉起来、日志在哪、怎么停」。
// 不含协议对接 —— Moonlight 客户端 GPLv3 不内嵌，协议面属于 M6 后续阶段。
//
// 语义边界：只托管我们自己启动的子进程（pid 落 pidFilePath，stop 只按这条记录杀，
// 不按进程名扫）；日志与 pid 都落临时目录，不碰用户真实配置。
//
// 语言级别：pc/host 是 C++20（CMakeLists 的 CMAKE_CXX_STANDARD 20），
// MSVC /std:c++20 没有 <expected>，所以起子进程的返回统一用 LaunchResult，
// 不用 std::expected（那是 C++23 特性，会把 Windows 构建打红）。

#include <string>
#include <vector>

namespace apxpc::screen {

// ===== 起子进程的返回值 =====
// ok / pid；失败时 error 含原因（exe 找不到 / 日志打不开 / 立即退出 code N）
struct LaunchResult {
    bool ok = false;
    std::string error;  // ok=false 时的失败原因（中文，可直接打印）
    long pid = 0;       // 子进程 pid（Windows 为进程 ID）；失败时 0
};

// ===== 查找：显式路径 → PATH → 各平台常见安装位，找不到返回空串 =====
std::string findSunshineExecutable(const std::string& explicitPath = "");

// ===== 默认记录路径（pid / 日志 落临时目录）=====
std::string defaultSpikePidPath();
std::string defaultSpikeLogPath();

// ===== 子进程存活复核 =====
// 返回 true = 记录在案的子进程还在（pid 文件有效）；false = 已退出/无记录
bool spikeChildAlive(const std::string& pidFile);

// 子进程已自退时只清记录，**不拿可能已复用的 pid 去杀**
void forgetSpikeChild(const std::string& pidFile);

// ===== 起子进程 =====
// stdout/stderr 追加到 logPath，立即返回不阻塞；成功即把 pid 写进 pidFilePath。
// args 原样透传（不解析 Sunshine 各版本 flag，真机上先 `sunshine --help` 核对）。
LaunchResult startSunshineProcess(const std::string& exePath,
                                  const std::vector<std::string>& args,
                                  const std::string& logPath,
                                  const std::string& pidFilePath);

// ===== 停子进程 =====
// SIGTERM → 2s → SIGKILL（Windows 直接 Terminate）；只按 pid 记录杀，不按进程名扫
bool stopSunshineProcess(const std::string& pidFile);

}  // namespace apxpc::screen

#endif
