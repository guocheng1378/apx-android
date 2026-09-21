#pragma once
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <string_view>

#include "apxpc/format.hpp"

namespace apxpc {

enum class LogLevel : uint8_t { Trace = 0, Debug, Info, Warn, Error, Off };

inline const char* logLevelName(LogLevel l) noexcept {
    switch (l) {
        case LogLevel::Trace: return "TRACE";
        case LogLevel::Debug: return "DEBUG";
        case LogLevel::Info:  return "INFO ";
        case LogLevel::Warn:  return "WARN ";
        case LogLevel::Error: return "ERROR";
        default:              return "OFF  ";
    }
}

// 轻量日志：默认输出到 stderr，可注册 sink 转发到 UI/文件。
class Logger {
public:
    static Logger& instance();

    void setLevel(LogLevel l);
    LogLevel level() const;

    // 回调在锁外调用，注意不要在回调里再打日志（会死锁）
    void setSink(std::function<void(LogLevel, const std::string&)> sink);
    void clearSink();

    void write(LogLevel l, std::string_view msg);

private:
    Logger() = default;
    mutable std::mutex mu_;
    LogLevel level_{LogLevel::Info};
    std::function<void(LogLevel, const std::string&)> sink_;
};

template <class... Args>
void logFormat(LogLevel l, std::string_view fmt, const Args&... args) {
    Logger::instance().write(l, format(fmt, args...));
}

}  // namespace apxpc

#define APX_LOG(level, fmt, ...) ::apxpc::logFormat((level), (fmt), ##__VA_ARGS__)
#define APX_LOGI(fmt, ...) APX_LOG(::apxpc::LogLevel::Info,  (fmt), ##__VA_ARGS__)
#define APX_LOGW(fmt, ...) APX_LOG(::apxpc::LogLevel::Warn,  (fmt), ##__VA_ARGS__)
#define APX_LOGE(fmt, ...) APX_LOG(::apxpc::LogLevel::Error, (fmt), ##__VA_ARGS__)
#define APX_LOGD(fmt, ...) APX_LOG(::apxpc::LogLevel::Debug, (fmt), ##__VA_ARGS__)
#define APX_LOGT(fmt, ...) APX_LOG(::apxpc::LogLevel::Trace, (fmt), ##__VA_ARGS__)
