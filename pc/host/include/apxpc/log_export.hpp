#pragma once
// 日志清单 + 按时间范围导出（issue #52 建议 5）。
//
// 只依赖标准库：mac / Linux / Windows 共用一套。日志行的时间戳由
// apxpc::Logger::write 统一打头（`[YYYY-MM-DD HH:MM:SS.mmm] LEVEL msg`），
// 这里按行首时间戳做窗口过滤 —— 不依赖文件名，也不依赖文件 mtime。

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace apxpc {
namespace logexport {

/// 日志目录：mac = ~/Library/Logs/AllPeriph；Linux = $XDG_STATE_HOME|~/.local/state/AllPeriph
/// （与 desktop_main.cpp 里的崩溃取证目录同一处；Windows 走 %LOCALAPPDATA%\AllPeriph）
std::string defaultLogDir();

/// 导出默认落点：~/Downloads/AllPeriphLogs（不存在则创建）
std::string defaultExportDir();

struct LogFile {
    std::string path;       ///< 绝对路径
    std::string name;       ///< 文件名
    std::string kind;       ///< 日志类型（文件名去掉 `-YYYYMMDD-HHMMSS` 与扩展名）
    uint64_t    size = 0;
    int64_t     mtimeSec = 0;  ///< 最后写入时间（epoch 秒）
};

struct Result {
    bool        ok = false;
    std::string outPath;   ///< 导出文件绝对路径
    std::size_t lines = 0;      ///< 实际写入的行数
    std::size_t scanned = 0;    ///< 扫过的行数（含被时间窗滤掉的）
    std::string error;     ///< ok=false 时的原因（给用户看的中文）
};

/// 列出 dir 下的 *.log，按最后写入时间新 → 旧
std::vector<LogFile> list(const std::string& dir);

/// 把 dir 里匹配 kindPrefix 的日志（空 = 全部类型）按 [sinceSec, untilSec] 过滤后
/// 合并写入 destDir。sinceSec / untilSec 传 0 表示该端不限。
/// 导出文件名：`apx-logs-<kind 或 all>-<YYYYMMDD-HHMMSS>.log`。
Result exportRange(const std::string& dir, const std::string& destDir,
                   const std::string& kindPrefix,
                   int64_t sinceSec, int64_t untilSec);

/// 本地时间戳：%Y%m%d-%H%M%S
std::string stampNow();

/// 本地时间戳（带秒的可读形式，用于导出文件头部注释）：%Y-%m-%d %H:%M:%S
std::string readableNow();

}  // namespace logexport
}  // namespace apxpc
