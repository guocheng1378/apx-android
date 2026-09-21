// 轻量日志（不引入第三方依赖；宿主服务可注册回调接管输出）
#pragma once

#include <cstdarg>
#include <cstdint>
#include <string>

namespace apxdisp {

enum class LogLevel { Debug = 0, Info = 1, Warn = 2, Error = 3 };

using LogSink = void (*)(void* user, LogLevel level, const char* message);

void logInit(LogSink sink = nullptr, void* user = nullptr);
void logSetLevel(LogLevel minLevel);
void logWrite(LogLevel level, const char* fmt, ...);

#define APX_LOG_D(...) ::apxdisp::logWrite(::apxdisp::LogLevel::Debug, __VA_ARGS__)
#define APX_LOG_I(...) ::apxdisp::logWrite(::apxdisp::LogLevel::Info,  __VA_ARGS__)
#define APX_LOG_W(...) ::apxdisp::logWrite(::apxdisp::LogLevel::Warn,  __VA_ARGS__)
#define APX_LOG_E(...) ::apxdisp::logWrite(::apxdisp::LogLevel::Error, __VA_ARGS__)

}  // namespace apxdisp
