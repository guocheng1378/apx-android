#include "apxpc/log.hpp"

#include <chrono>
#include <cstdio>
#include <ctime>

namespace apxpc {

Logger& Logger::instance() {
    static Logger g;
    return g;
}

void Logger::setLevel(LogLevel l) {
    std::lock_guard<std::mutex> g(mu_);
    level_ = l;
}

LogLevel Logger::level() const {
    std::lock_guard<std::mutex> g(mu_);
    return level_;
}

void Logger::setSink(std::function<void(LogLevel, const std::string&)> sink) {
    std::unique_lock<std::mutex> g(mu_);
    sink_ = std::move(sink);
    if (sink_) {
        g.unlock();
        sink_(LogLevel::Debug, "[log] sink 已挂载");
    }
}

void Logger::clearSink() {
    std::function<void(LogLevel, const std::string&)> tmp;
    {
        std::lock_guard<std::mutex> g(mu_);
        tmp.swap(sink_);
    }
}

void Logger::write(LogLevel l, std::string_view msg) {
    LogLevel cur;
    std::function<void(LogLevel, const std::string&)> sink;
    {
        std::lock_guard<std::mutex> g(mu_);
        cur = level_;
        if (l >= cur && sink_) sink = sink_;  // 拷贝一次，避免持锁回调
    }
    if (l < cur) return;

    // 组装前缀：时分秒.毫秒 + 等级
    auto now = std::chrono::system_clock::now();
    auto ms  = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()) % 1000;
    std::time_t t = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
#if defined(_WIN32)
    localtime_s(&tm, &t);
#else
    localtime_r(&t, &tm);
#endif
    char buf[256];
    std::snprintf(buf, sizeof(buf), "[%02d:%02d:%02d.%03lld] %s %.*s",
                  tm.tm_hour, tm.tm_min, tm.tm_sec, (long long)ms.count(),
                  logLevelName(l), (int)msg.size(), msg.data());
    std::string line(buf);

    if (sink) {
        sink(l, line);
    } else {
        FILE* fp = (l >= LogLevel::Warn) ? stderr : stdout;
        std::fprintf(fp, "%s\n", line.c_str());
        std::fflush(fp);
    }
}

}  // namespace apxpc
