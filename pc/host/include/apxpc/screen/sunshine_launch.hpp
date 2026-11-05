#ifndef APXPC_SCREEN_SUNSHINE_LAUNCH_HPP
#define APXPC_SCREEN_SUNSHINE_LAUNCH_HPP

#include <string>
#include <expected>
#include <chrono>

namespace apxpc::screen {

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
// 返回 ok / pid；失败时 error 含原因（exe 找不到 / 日志打不开 / 立即退出 code N）
std::expected<std::pair<std::string, long>, std::string> startSunshineProcess(
    const std::string& exe, const std::string& env, const std::string& logFile, const std::string& pidFile);

// ===== 停子进程 =====
// SIGTERM → 2s → SIGKILL（Windows 直接 Terminate）；只按 pid 记录杀，不按进程名扫
bool stopSunshineProcess(const std::string& pidFile);

// ===== 只读检测（mac/Linux）=====
// Windows 自研副屏已交付（ROADMAP §2.5），此处只给占位
struct sunshineStatus { bool installed; bool running; unsigned httpsPort; };
sunshineStatus sunshineStatus();

}  // namespace apxpc::screen

#endif
