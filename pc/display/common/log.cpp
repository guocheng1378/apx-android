#include "common/log.hpp"

#include <cstdio>
#include <mutex>

#ifdef _WIN32
#include <windows.h>
#endif

namespace apxdisp {
namespace {

LogSink  g_sink     = nullptr;
void*    g_user     = nullptr;
LogLevel g_minLevel = LogLevel::Info;
std::mutex g_mutex;

const char* levelTag(LogLevel lv) {
    switch (lv) {
        case LogLevel::Debug: return "D";
        case LogLevel::Info:  return "I";
        case LogLevel::Warn:  return "W";
        case LogLevel::Error: return "E";
    }
    return "?";
}

// v1.10：控制台中文乱码修复——日志文本为 UTF-8，中文 Windows 控制台默认
// GBK 代码页。转 GBK 后走 **stdout printf**（与 main.cpp 的 gprintf 相同的
// 已验证路径；此前 WriteFile(stderr) 版本真机不生效，输出仍是 UTF-8 乱码）。
void consoleWrite(const char* tag, const char* text) {
    char line[1280];
    snprintf(line, sizeof(line), "[apxdisp][%s] %s\n", tag, text);
#ifdef _WIN32
    wchar_t wbuf[2048];
    const int wlen = MultiByteToWideChar(CP_UTF8, 0, line, -1, wbuf, 2048);
    if (wlen > 0) {
        char gbk[4096];
        const int glen = WideCharToMultiByte(936, 0, wbuf, wlen, gbk, sizeof(gbk), nullptr, nullptr);
        if (glen > 0) {
            printf("%s", gbk);
            return;
        }
    }
#endif
    fprintf(stderr, "[apxdisp][%s] %s\n", tag, text);
}

}  // namespace

void logInit(LogSink sink, void* user) {
    std::lock_guard<std::mutex> lk(g_mutex);
    g_sink = sink;
    g_user = user;
}

void logSetLevel(LogLevel minLevel) {
    std::lock_guard<std::mutex> lk(g_mutex);
    g_minLevel = minLevel;
}

void logWrite(LogLevel level, const char* fmt, ...) {
    if (level < g_minLevel) return;

    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_sink) {
        g_sink(g_user, level, buf);
        return;
    }
    consoleWrite(levelTag(level), buf);
    fflush(stdout);
}

}  // namespace apxdisp
