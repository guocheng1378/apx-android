#include "apxpc/log_export.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <utility>
#include <vector>

#if !defined(_WIN32)
#include <unistd.h>   // gethostname（导出文件名里的本机标识）
#endif

namespace fs = std::filesystem;

namespace apxpc {
namespace logexport {
namespace {

std::string homeDir() {
    const char* h = std::getenv("HOME");
    if (h && *h) return h;
    const char* u = std::getenv("USERPROFILE");   // Windows
    return (u && *u) ? std::string(u) : std::string(".");
}

void localTime(std::time_t t, std::tm& out) {
    std::tm tm{};
#if defined(_WIN32)
    localtime_s(&tm, &t);
#else
    localtime_r(&t, &tm);
#endif
    out = tm;
}

/// `apxdesktop-20261031-005912.log` → `apxdesktop`。
/// 尾部不是 `-YYYYMMDD-HHMMSS` 时只去扩展名（老文件 `apxdesktop.log` 也归到 apxdesktop）。
std::string kindOf(const std::string& name) {
    std::string base = name;
    const auto dot = base.rfind('.');
    if (dot != std::string::npos && dot > 0) base = base.substr(0, dot);
    if (base.size() > 16) {
        const std::string tail = base.substr(base.size() - 16);   // -YYYYMMDD-HHMMSS
        bool shape = tail[0] == '-';
        for (std::size_t i = 1; shape && i < tail.size(); ++i) {
            shape = (i == 9) ? tail[i] == '-'
                             : std::isdigit(static_cast<unsigned char>(tail[i])) != 0;
        }
        if (shape) base = base.substr(0, base.size() - 16);
    }
    return base.empty() ? std::string("log") : base;
}

struct Stamp {
    int  y = 0, mo = 0, d = 0, h = 0, mi = 0, s = 0;
    bool hasDate = false;
};

/// 解析行首时间戳。两种形态都认：
///   新：`[2026-10-31 00:59:12.123] ...`（log.cpp 打的全格式）
///   旧：`[00:59:12.123] ...`（v223.13 之前的日志，只有时分秒）
bool parseStamp(const std::string& line, Stamp& st) {
    if (line.size() < 11 || line[0] != '[') return false;
    int y, mo, d, h, mi, s;
    // ⚠️ 下标要算上开头的 '['：[YYYY-MM-DD HH:MM:SS → 分隔符在 5 / 8 / 11
    if (line.size() >= 20 && line[5] == '-' && line[8] == '-' && line[11] == ' ' &&
        std::sscanf(line.c_str(), "[%4d-%2d-%2d %2d:%2d:%2d", &y, &mo, &d, &h, &mi, &s) == 6) {
        st.y = y; st.mo = mo; st.d = d; st.h = h; st.mi = mi; st.s = s;
        st.hasDate = true;
        return true;
    }
    if (line[3] == ':' && line[6] == ':' &&
        std::sscanf(line.c_str(), "[%2d:%2d:%2d", &h, &mi, &s) == 3) {
        st.y = st.mo = st.d = 0;
        st.h = h; st.mi = mi; st.s = s;
        st.hasDate = false;
        return true;
    }
    return false;
}

/// 时间戳 → epoch 秒（本地时区，与写入端 localtime 对称）。
/// 旧格式没有日期：用该文件的最后写入日期补齐。
int64_t toEpoch(Stamp st, int64_t fallbackMtimeSec) {
    if (!st.hasDate) {
        std::tm ref{};
        localTime(static_cast<std::time_t>(fallbackMtimeSec), ref);
        st.y = ref.tm_year + 1900;
        st.mo = ref.tm_mon + 1;
        st.d = ref.tm_mday;
    }
    std::tm tm{};
    tm.tm_year = st.y - 1900;
    tm.tm_mon = st.mo - 1;
    tm.tm_mday = st.d;
    tm.tm_hour = st.h;
    tm.tm_min = st.mi;
    tm.tm_sec = st.s;
    tm.tm_isdst = -1;
    const std::time_t t = std::mktime(&tm);
    return t == static_cast<std::time_t>(-1) ? 0 : static_cast<int64_t>(t);
}

std::string fmtLocal(const char* fmt) {
    const std::time_t t = std::time(nullptr);
    std::tm tm{};
    localTime(t, tm);
    char buf[64];
    const std::size_t n = std::strftime(buf, sizeof(buf), fmt, &tm);
    return std::string(buf, n);
}

/// 本机名（导出文件名用）：Windows 上 gethostname 走 Winsock 需要额外初始化，
/// 这里用环境变量 COMPUTERNAME 更省事；其余平台 gethostname。取不到给 "PC"。
std::string localHostName() {
#if defined(_WIN32)
    const char* cn = std::getenv("COMPUTERNAME");
    if (cn && *cn) return cn;
    return "PC";
#else
    char buf[256] = {};
    if (::gethostname(buf, sizeof(buf) - 1) == 0 && buf[0] != '\0') return buf;
    return "PC";
#endif
}

/// 把 nameNote 洗成文件名安全的一段（用户可自定义，见 exportRange 参数说明）：
/// 去掉路径分隔符与控制字符、去掉会跟文件名格式打架的 `-` 与空格，其余保留中文
/// （macOS / Windows / Linux 的文件名都支持 UTF-8 中文）。
std::string sanitizeNote(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (unsigned char c : in) {
        if (c < 0x20 || c == 0x7F) continue;                       // 控制字符
        if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|') continue; // 非法/危险字符
        if (c == ' ' || c == '-') continue;                         // 撞文件名格式
        if (out.size() >= 48) break;                                // 别让文件名失控
        out.push_back(static_cast<char>(c));
    }
    return out;
}

}  // namespace

std::string defaultLogDir() {
    const std::string h = homeDir();
#if defined(_WIN32)
    const char* lad = std::getenv("LOCALAPPDATA");
    if (lad && *lad) return std::string(lad) + "\\AllPeriph";
    return h + "\\AllPeriph";
#elif defined(__APPLE__)
    return h + "/Library/Logs/AllPeriph";
#else
    const char* state = std::getenv("XDG_STATE_HOME");
    if (state && *state) return std::string(state) + "/AllPeriph";
    return h + "/.local/state/AllPeriph";
#endif
}

std::string defaultExportDir() {
    const std::string h = homeDir();
#if defined(_WIN32)
    return h + "\\Downloads\\AllPeriphLogs";
#else
    return h + "/Downloads/AllPeriphLogs";
#endif
}

std::string stampNow() { return fmtLocal("%Y%m%d-%H%M%S"); }

std::vector<std::pair<std::string, std::string>> dirPresets() {
    /* 「选择目录」的原生系统对话框只有 Windows 弹得出来（macOS 的主线程被面板的
       accept 循环占着，AppKit 的模态文件对话框必须在主线程 —— 把主线程让给 AppKit
       是结构性改动，见 tray_mac.mm 顶部那段，不能为了这个功能贸然改）。
       所以三端统一给一组**常用目录候选**：任何端都能用「点一下换目录」的体验，
       Windows 上还额外提供真正的系统目录选择对话框（见 panel_http 的 log.pickDir）。 */
    const std::string h = homeDir();
    std::vector<std::pair<std::string, std::string>> out;
    std::error_code ec;
#if defined(_WIN32)
    out.emplace_back("默认（下载\\AllPeriphLogs）", defaultExportDir());
    const char* up = std::getenv("USERPROFILE");
    if (up && *up) {
        out.emplace_back("桌面", std::string(up) + "\\Desktop");
        out.emplace_back("文档", std::string(up) + "\\Documents");
        out.emplace_back("下载", std::string(up) + "\\Downloads");
    }
#else
    out.emplace_back("默认（下载/AllPeriphLogs）", defaultExportDir());
    out.emplace_back("桌面", h + "/Desktop");
    out.emplace_back("文稿", h + "/Documents");
    out.emplace_back("下载", h + "/Downloads");
#endif
    // 去掉不存在的候选（目录都没建出来的话，列出来只会误导）
    out.erase(std::remove_if(out.begin(), out.end(),
                             [&](const auto& p) {
                                 std::error_code e2;
                                 return !fs::is_directory(fs::path(p.second), e2);
                             }),
              out.end());
    if (out.empty()) out.emplace_back("默认", defaultExportDir());
    return out;
}

std::string readableNow() { return fmtLocal("%Y-%m-%d %H:%M:%S"); }

std::vector<LogFile> list(const std::string& dir) {
    std::vector<LogFile> out;
    std::error_code ec;
    const fs::path root(dir);
    if (dir.empty() || !fs::exists(root, ec)) return out;
    for (fs::directory_iterator it(root, ec), end; !ec && it != end; it.increment(ec)) {
        std::error_code fec;
        if (!it->is_regular_file(fec)) continue;
        const std::string name = it->path().filename().string();
        if (name.size() < 5 || name.compare(name.size() - 4, 4, ".log") != 0) continue;
        LogFile f;
        f.path = it->path().string();
        f.name = name;
        f.kind = kindOf(name);
        f.size = static_cast<uint64_t>(it->file_size(fec));
        const auto ft = it->last_write_time(fec);
        if (!fec) {
            // file_time → system_clock：只用于排序与「旧格式行」的日期补齐。
            // 不用 std::chrono::clock_cast —— 那是 C++20 且 libc++ 16 / libstdc++ 13
            // 才给，mac 上 Xcode 版本一旧就编不过；这里用「两边 now() 求差」的可移植写法。
            const auto sys = std::chrono::time_point_cast<std::chrono::system_clock::duration>(
                ft - decltype(ft)::clock::now() + std::chrono::system_clock::now());
            f.mtimeSec = std::chrono::duration_cast<std::chrono::seconds>(
                             sys.time_since_epoch()).count();
        }
        out.push_back(std::move(f));
    }
    std::sort(out.begin(), out.end(),
              [](const LogFile& a, const LogFile& b) { return a.mtimeSec > b.mtimeSec; });
    return out;
}

Result exportRange(const std::string& dir, const std::string& destDirIn,
                   const std::string& kindPrefix,
                   int64_t sinceSec, int64_t untilSec,
                   const std::string& nameNote) {
    Result r;
    const std::string destDir = destDirIn.empty() ? defaultExportDir() : destDirIn;
    std::error_code ec;
    fs::create_directories(fs::path(destDir), ec);
    if (ec) {
        r.error = "无法创建导出目录：" + destDir + "（" + ec.message() + "）";
        return r;
    }

    std::vector<LogFile> files = list(dir);
    if (!kindPrefix.empty()) {
        files.erase(std::remove_if(files.begin(), files.end(),
                                   [&](const LogFile& f) { return f.kind != kindPrefix; }),
                    files.end());
    }
    if (files.empty()) {
        r.error = kindPrefix.empty() ? "日志目录里没有 .log 文件" :
                                       ("没有类型为 " + kindPrefix + " 的日志文件");
        return r;
    }
    // 老 → 新读入，导出文件里保持时间顺序
    std::sort(files.begin(), files.end(),
              [](const LogFile& a, const LogFile& b) { return a.mtimeSec < b.mtimeSec; });

    const std::string kindTag = kindPrefix.empty() ? "all" : kindPrefix;
    /* 设备标识（issue #52 第 3 条）：默认本机主机名，用户可在导出弹窗里自定义。
       拼在 kindTag 后面、以 @ 分隔 → `apx-logs-apxdesktop@TurnsMacBookAir-20261031-172855.log`，
       同一目录下多台机器的日志一眼分得清，也不会与类型名混淆。 */
    const std::string note = sanitizeNote(nameNote);
    const std::string nameTag = note.empty() ? sanitizeNote(localHostName()) : note;
    const std::string base =
        "apx-logs-" + kindTag + "@" + nameTag + "-" + stampNow();
    // 不覆盖已有导出：同一秒内连点两次、或补导出第二遍时，追加 -2 / -3 ……
    std::string outPath = (fs::path(destDir) / (base + ".log")).string();
    for (int n = 2; n < 100; ++n) {
        std::error_code ee;
        if (!fs::exists(fs::path(outPath), ee)) break;
        outPath = (fs::path(destDir) / (base + "-" + std::to_string(n) + ".log")).string();
    }

    std::ofstream out(outPath, std::ios::binary | std::ios::trunc);
    if (!out) {
        r.error = "无法写入导出文件：" + outPath;
        return r;
    }

    // 头部：说明范围与来源，方便对方直接看懂这份文件是什么
    out << "# 全能外设 日志导出\n";
    out << "# 导出时间：" << readableNow() << "\n";
    out << "# 类型：" << (kindPrefix.empty() ? "全部" : kindPrefix) << "\n";
    out << "# 时间范围：";
    if (sinceSec == 0 && untilSec == 0) out << "全部\n";
    else {
        auto show = [](int64_t sec) {
            std::tm tm{};
            localTime(static_cast<std::time_t>(sec), tm);
            char b[32];
            std::strftime(b, sizeof(b), "%Y-%m-%d %H:%M:%S", &tm);
            return std::string(b);
        };
        out << (sinceSec == 0 ? std::string("最早") : show(sinceSec)) << " ~ "
            << (untilSec == 0 ? std::string("最新") : show(untilSec)) << "\n";
    }
    out << "# 来源文件：" << files.size() << " 个\n";
    for (const auto& f : files) out << "#   " << f.name << "\n";
    out << "\n";

    for (const auto& f : files) {
        std::ifstream in(f.path, std::ios::binary);
        if (!in) continue;
        std::string line;
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            ++r.scanned;
            Stamp st;
            if (!parseStamp(line, st)) {
                // 无时间戳的行（多行日志的续行）：跟着上一行判断，收在窗口内就保留
                if (sinceSec == 0 && untilSec == 0) {
                    out << line << "\n";
                    ++r.lines;
                }
                continue;
            }
            const int64_t t = toEpoch(st, f.mtimeSec);
            if (sinceSec != 0 && t < sinceSec) continue;
            if (untilSec != 0 && t > untilSec) continue;
            out << line << "\n";
            ++r.lines;
        }
    }
    out.flush();
    if (!out) {
        r.error = "写入导出文件失败（磁盘已满或无权限）：" + outPath;
        return r;
    }
    r.ok = true;
    r.outPath = outPath;
    return r;
}

}  // namespace logexport
}  // namespace apxpc
