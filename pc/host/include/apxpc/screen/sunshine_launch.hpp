#pragma once
// Sunshine **子进程托管** —— M6 推屏 spike 第一阶段的执行面脚手架。
//
// 与 sunshine_detect.hpp（只读检测）的分工：detect 回答「装没装 / 跑没跑」；
// 本文件回答「怎么由我们拉起来、日志在哪、怎么停」。**不含任何协议对接** ——
// Moonlight 客户端是 GPLv3、不内嵌（ROADMAP §2.5），协议面属于 M6 后续阶段。
//
// 语义边界（务必先读）：
//   · 只托管**我们自己启动**的子进程：pid 写在 pidFilePath，stop 只按这条记录杀，
//     不按进程名扫 —— 用户自己开的 Sunshine 不受影响。
//   · stop/alive 走 pid 存在性探测（POSIX kill(pid,0) / Windows OpenProcess），
//     理论上存在 pid 复用误判窗口；窗口极小，spike 阶段接受，量产前要换成
//     句柄 / 进程创建时间校验。
//   · 不解析 Sunshine 的命令行（各版本 flag 不稳）：args 原样透传，
//     真机上先 `sunshine --help` 核对再填进实测步骤（docs/spikes/m6-sunshine-spike.md）。
//   · 日志与 pid 都落**临时目录**（defaultSpike*），不碰用户真实配置
//     （~/.config/sunshine/）—— spike 要能反复跑、跑挂了不留残局。
#include <string>
#include <vector>

namespace apxpc::screen {

struct LaunchResult {
    bool ok = false;
    std::string error;  // ok=false 时的失败原因（中文，可直接打印）
    long pid = 0;       // 子进程 pid（Windows 为进程 ID）；失败时 0
};

/// 定位 Sunshine 可执行文件：explicitPath 非空则只验它；否则 PATH → 常见安装位。
/// 找不到返回空串（不猜、不报错，交给调用方给指引）。
std::string findSunshineExecutable(const std::string& explicitPath);

/// 以子进程启动 Sunshine：stdout/stderr 追加到 logPath，立即返回（不阻塞）。
/// 成功即把 pid 写进 pidFilePath（stop / 跨进程收尸靠它）。
LaunchResult startSunshineProcess(const std::string& exePath,
                                  const std::vector<std::string>& args,
                                  const std::string& logPath,
                                  const std::string& pidFilePath);

/// 终止 pidFilePath 记录的子进程（SIGTERM → 2s → SIGKILL；Windows 直接 Terminate）。
/// 无记录 / 已退出 → false（如实报告，不假装杀了）；真杀掉才 true。
bool stopSunshineProcess(const std::string& pidFilePath);

/// pidFilePath 记录的子进程是否仍在跑（我们是父进程时顺手回收僵尸）。
bool spikeChildAlive(const std::string& pidFilePath);

/// 放弃跟踪（不杀进程、只删 pid 文件）：子进程已自行退出时用，
/// 避免 stop 拿可能已被系统复用的旧 pid 误伤无辜进程。
void forgetSpikeChild(const std::string& pidFilePath);

/// spike 默认落点（临时目录）：pid 文件 / 日志文件。
std::string defaultSpikePidPath();
std::string defaultSpikeLogPath();

}  // namespace apxpc::screen
