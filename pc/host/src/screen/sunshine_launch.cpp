#include "apxpc/screen/sunshine_launch.hpp"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#if defined(_WIN32)
#include <windows.h>
#else
#include <csignal>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

namespace fs = std::filesystem;

namespace apxpc::screen {
namespace {

const char* kExeName =
#if defined(_WIN32)
    "sunshine.exe";
#else
    "sunshine";
#endif

bool fileExists(const std::string& p) {
    if (p.empty()) return false;
    std::error_code ec;
    return fs::exists(p, ec);
}

long readPidFile(const std::string& path) {
    std::ifstream in(path);
    if (!in) return 0;
    long pid = 0;
    in >> pid;
    return pid > 0 ? pid : 0;
}

void writePidFile(const std::string& path, long pid) {
    std::ofstream out(path, std::ios::trunc);
    if (out) out << pid << "\n";
}

void removePidFile(const std::string& path) {
    std::error_code ec;
    fs::remove(path, ec);
}

// spike 落点：临时目录优先，拿不到就退回当前目录 —— 反复跑不留残局，
// 也绝不写用户真实配置（~/.config/sunshine/）。
std::string spikeTempDir() {
    std::error_code ec;
    fs::path d = fs::temp_directory_path(ec);
    if (ec || d.empty()) {
        ec.clear();
        d = fs::current_path(ec);
    }
    if (d.empty()) d = ".";
    return d.string();
}

}  // namespace

std::string defaultSpikePidPath() {
    return (fs::path(spikeTempDir()) / "apx_sunshine_spike.pid").string();
}

std::string defaultSpikeLogPath() {
    return (fs::path(spikeTempDir()) / "apx_sunshine_spike.log").string();
}

std::string findSunshineExecutable(const std::string& explicitPath) {
    if (!explicitPath.empty()) return fileExists(explicitPath) ? explicitPath : std::string();

    // 1) PATH 逐目录找
    if (const char* pathEnv = std::getenv("PATH")) {
        std::stringstream ss(pathEnv);
        std::string dir;
#if defined(_WIN32)
        const char kSep = ';';
#else
        const char kSep = ':';
#endif
        while (std::getline(ss, dir, kSep)) {
            if (dir.empty()) continue;
            const std::string cand = (fs::path(dir) / kExeName).string();
            if (fileExists(cand)) return cand;
        }
    }

    // 2) 各平台常见安装位（只列已知约定，不扫全盘）
    std::vector<std::string> candidates;
#if defined(_WIN32)
    if (const char* pf = std::getenv("ProgramFiles"))
        candidates.emplace_back((fs::path(pf) / "Sunshine" / "sunshine.exe").string());
    if (const char* pf86 = std::getenv("ProgramFiles(x86)"))
        candidates.emplace_back((fs::path(pf86) / "Sunshine" / "sunshine.exe").string());
#elif defined(__APPLE__)
    candidates = {"/Applications/Sunshine.app/Contents/MacOS/Sunshine",
                  "/usr/local/bin/sunshine", "/opt/homebrew/bin/sunshine"};
#else
    candidates = {"/usr/bin/sunshine", "/usr/local/bin/sunshine", "/snap/bin/sunshine"};
#endif
    for (const auto& c : candidates)
        if (fileExists(c)) return c;
    return {};
}

void forgetSpikeChild(const std::string& pidFilePath) { removePidFile(pidFilePath); }

#if defined(_WIN32)

LaunchResult startSunshineProcess(const std::string& exePath,
                                  const std::vector<std::string>& args,
                                  const std::string& logPath,
                                  const std::string& pidFilePath) {
    LaunchResult r;
    if (!fileExists(exePath)) {
        r.error = "Sunshine 可执行文件不存在：" + exePath;
        return r;
    }

    // 日志：追加写 + 可继承 —— 子进程的 stdout/stderr 全进这个文件，
    // 不给它弹自己的控制台窗（CREATE_NO_WINDOW）。
    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;
    HANDLE logh = ::CreateFileW(fs::path(logPath).c_str(), FILE_APPEND_DATA,
                                FILE_SHARE_READ | FILE_SHARE_WRITE, &sa, OPEN_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (logh == INVALID_HANDLE_VALUE) {
        r.error = "无法打开日志文件：" + logPath;
        return r;
    }

    // 参数原样透传（flag 各版本不稳，本脚手架不解析）；带空格加引号。
    std::wstring cmd = L"\"" + fs::path(exePath).wstring() + L"\"";
    for (const auto& a : args) {
        const std::wstring w = fs::path(a).wstring();
        cmd += L" ";
        if (w.find(L' ') != std::wstring::npos) {
            cmd += L"\"" + w + L"\"";
        } else {
            cmd += w;
        }
    }

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    const HANDLE hin = ::GetStdHandle(STD_INPUT_HANDLE);
    si.hStdInput = (hin == INVALID_HANDLE_VALUE) ? nullptr : hin;
    si.hStdOutput = logh;
    si.hStdError = logh;
    PROCESS_INFORMATION pi{};
    const BOOL ok = ::CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE,
                                     CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
    ::CloseHandle(logh);   // 子进程已拿到继承副本
    if (!ok) {
        r.error = "CreateProcess 失败（错误码 " + std::to_string(::GetLastError()) + "）";
        return r;
    }
    ::CloseHandle(pi.hThread);
    ::CloseHandle(pi.hProcess);   // 不长期持有句柄：stop 走 pid 文件重新 OpenProcess
    writePidFile(pidFilePath, static_cast<long>(pi.dwProcessId));
    r.ok = true;
    r.pid = static_cast<long>(pi.dwProcessId);
    return r;
}

bool stopSunshineProcess(const std::string& pidFilePath) {
    const long pid = readPidFile(pidFilePath);
    if (pid <= 0) return false;
    HANDLE proc = ::OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE,
                                static_cast<DWORD>(pid));
    if (!proc) {
        removePidFile(pidFilePath);   // 打不开 = 进程已不存在，顺手清记录
        return false;
    }
    bool killed = false;
    if (::WaitForSingleObject(proc, 0) == WAIT_TIMEOUT) {   // 仍活着才杀
        killed = ::TerminateProcess(proc, 1) != 0;
        ::WaitForSingleObject(proc, 2000);
    }
    ::CloseHandle(proc);
    removePidFile(pidFilePath);
    return killed;
}

bool spikeChildAlive(const std::string& pidFilePath) {
    const long pid = readPidFile(pidFilePath);
    if (pid <= 0) return false;
    HANDLE proc = ::OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(pid));
    if (!proc) return false;
    const bool alive = ::WaitForSingleObject(proc, 0) == WAIT_TIMEOUT;
    ::CloseHandle(proc);
    return alive;
}

#else  // ---------------- POSIX（macOS / Linux）----------------

LaunchResult startSunshineProcess(const std::string& exePath,
                                  const std::vector<std::string>& args,
                                  const std::string& logPath,
                                  const std::string& pidFilePath) {
    LaunchResult r;
    if (!fileExists(exePath)) {
        r.error = "Sunshine 可执行文件不存在：" + exePath;
        return r;
    }
    const int logfd = ::open(logPath.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (logfd < 0) {
        r.error = "无法打开日志文件：" + logPath;
        return r;
    }

    std::vector<std::string> storage;
    storage.reserve(1 + args.size());
    storage.push_back(exePath);
    for (const auto& a : args) storage.push_back(a);
    std::vector<char*> argv;
    argv.reserve(storage.size() + 1);
    for (auto& s : storage) argv.push_back(s.data());
    argv.push_back(nullptr);

    const pid_t pid = ::fork();
    if (pid < 0) {
        ::close(logfd);
        r.error = std::string("fork 失败：") + std::strerror(errno);
        return r;
    }
    if (pid == 0) {
        // 子进程：脱离控制终端（父进程 Ctrl+C 不牵连它），输出全进日志。
        (void)::setsid();
        (void)::dup2(logfd, STDOUT_FILENO);
        (void)::dup2(logfd, STDERR_FILENO);
        if (logfd > STDERR_FILENO) ::close(logfd);
        ::execv(exePath.c_str(), argv.data());
        ::_exit(127);   // exec 失败：父进程下面的 200ms 复核会看到 127
    }
    ::close(logfd);

    // 200ms 复核：exec 失败或秒退（配置坏 / 端口占用）早发现，
    // 不让用户对着一个「拉起了但其实已死」的 pid 等轮询。
    ::usleep(200 * 1000);
    int status = 0;
    if (::waitpid(pid, &status, WNOHANG) == pid) {
        const int code = WIFEXITED(status) ? WEXITSTATUS(status) : -WTERMSIG(status);
        removePidFile(pidFilePath);
        r.error = "子进程启动后立即退出（code " + std::to_string(code) +
                  "），日志：" + logPath;
        return r;
    }
    writePidFile(pidFilePath, static_cast<long>(pid));
    r.ok = true;
    r.pid = static_cast<long>(pid);
    return r;
}

bool stopSunshineProcess(const std::string& pidFilePath) {
    const long pid = readPidFile(pidFilePath);
    if (pid <= 0) return false;
    const pid_t p = static_cast<pid_t>(pid);
    if (::kill(p, 0) != 0) {              // pid 已不存在
        removePidFile(pidFilePath);
        return false;
    }
    (void)::kill(p, SIGTERM);
    bool gone = false;
    for (int i = 0; i < 40 && !gone; ++i) {   // 最多等 2s 自愿退出
        if (::kill(p, 0) != 0) {
            gone = true;
            break;
        }
        (void)::waitpid(p, nullptr, WNOHANG);   // 我们是父进程时收尸，别留僵尸
        ::usleep(50 * 1000);
    }
    if (!gone) {
        (void)::kill(p, SIGKILL);
        for (int i = 0; i < 20 && !gone; ++i) {
            if (::kill(p, 0) != 0) {
                gone = true;
                break;
            }
            (void)::waitpid(p, nullptr, WNOHANG);
            ::usleep(50 * 1000);
        }
    }
    removePidFile(pidFilePath);
    return gone;
}

bool spikeChildAlive(const std::string& pidFilePath) {
    const long pid = readPidFile(pidFilePath);
    if (pid <= 0) return false;
    const pid_t p = static_cast<pid_t>(pid);
    (void)::waitpid(p, nullptr, WNOHANG);   // 我们是父进程时先收尸（ECHILD 无害）
    return ::kill(p, 0) == 0;
}

#endif

}  // namespace apxpc::screen
