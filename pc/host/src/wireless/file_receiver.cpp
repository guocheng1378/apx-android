#include "apxpc/wireless/file_receiver.hpp"

#include "apxpc/log.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32")
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace apxpc::wireless {
namespace {

#if defined(_WIN32)
using Sock = SOCKET;
const Sock kBadSock = INVALID_SOCKET;
void closeSock(Sock s) { ::closesocket(s); }
void shutdownSock(Sock s) { ::shutdown(s, SD_BOTH); }
void ensureNet() {
    static const bool once = [] {
        WSADATA w{};
        ::WSAStartup(MAKEWORD(2, 2), &w);
        return true;
    }();
    (void)once;
}
#else
using Sock = int;
const Sock kBadSock = -1;
void closeSock(Sock s) { ::close(s); }
void shutdownSock(Sock s) { ::shutdown(s, SHUT_RDWR); }
void ensureNet() {}
#endif

// std::filesystem 的 u8string() 在 C++20 返回 char8_t 串；这里统一转成「UTF-8 字节的
// std::string」（日志 / 界面 / 气泡都按 UTF-8 处理）。反过来由 UTF-8 构造 path 也不能用
// 普通 narrow 构造（Windows 会按 ANSI 解析，中文路径乱码），必须经 u8string。
std::string toUtf8(const std::filesystem::path& p) {
    const std::u8string u8 = p.u8string();
    return std::string(u8.begin(), u8.end());
}

std::filesystem::path fromUtf8(const std::string& s) {
    return std::filesystem::path(std::u8string(s.begin(), s.end()));
}

/// 读满 n 字节；连接中断返回 false（不做短超时：大文件传输时数据是连续来的）
bool readFully(Sock s, uint8_t* dst, size_t n) {
    size_t got = 0;
    while (got < n) {
        const int r = ::recv(s, reinterpret_cast<char*>(dst + got),
                             static_cast<int>(n - got), 0);
        if (r <= 0) return false;
        got += static_cast<size_t>(r);
    }
    return true;
}

bool readU32(Sock s, uint32_t& v) {
    uint8_t b[4];
    if (!readFully(s, b, 4)) return false;
    v = static_cast<uint32_t>(b[0]) | (static_cast<uint32_t>(b[1]) << 8) |
        (static_cast<uint32_t>(b[2]) << 16) | (static_cast<uint32_t>(b[3]) << 24);
    return true;
}

bool readU64(Sock s, uint64_t& v) {
    uint8_t b[8];
    if (!readFully(s, b, 8)) return false;
    v = 0;
    for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(b[i]) << (8 * i);
    return true;
}

/// 文件名清洗（与手机/TV 端同规则）：路径分隔与 Windows 非法字符一律替换，
/// 去掉首尾空白与开头连续的点（".." 这类会被当成目录），限长 200；洗不出来用 file.bin。
std::string sanitizeName(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    for (char c : raw) {
        const auto u = static_cast<unsigned char>(c);
        if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' ||
            c == '<' || c == '>' || c == '|' || u < 0x20) {
            out.push_back('_');
        } else {
            out.push_back(c);
        }
    }
    const auto notSpace = [](char c) { return c != ' ' && c != '\t'; };
    out.erase(out.begin(), std::find_if(out.begin(), out.end(), notSpace));
    out.erase(std::find_if(out.rbegin(), out.rend(), notSpace).base(), out.end());
    while (!out.empty() && out.front() == '.') out.erase(out.begin());
    if (out.size() > 200) out.resize(200);
    if (out.empty()) out = "file.bin";
    return out;
}

/// 重名不覆盖：加 " (2)"、" (3)"…（被控手机端是直接覆盖，电脑端保留用户已有文件更稳妥）
std::filesystem::path uniquePath(const std::filesystem::path& dir, const std::string& name) {
    namespace fs = std::filesystem;
    const fs::path base = fromUtf8(name);
    std::error_code ec;
    fs::path p = dir / base;
    if (!fs::exists(p, ec)) return p;
    const std::string stem = toUtf8(base.stem());
    const std::string ext = toUtf8(base.extension());
    for (int i = 2; i < 10000; ++i) {
        p = dir / fromUtf8(stem + " (" + std::to_string(i) + ")" + ext);
        if (!fs::exists(p, ec)) return p;
    }
    return dir / base;
}

std::string defaultDirImpl() {
#if defined(_WIN32)
    const char* home = std::getenv("USERPROFILE");
#else
    const char* home = std::getenv("HOME");
#endif
    const std::string base = (home && *home) ? home : ".";
    return toUtf8(std::filesystem::path(fromUtf8(base)) / "Downloads" / "AllPeriph");
}

}  // namespace

struct FileReceiver::Impl {
    std::atomic<bool> running{false};
    Sock listen = kBadSock;

    std::thread acceptThread;
    std::thread workerThread;

    std::mutex qmu;
    std::condition_variable qcv;
    std::queue<Sock> pend;

    std::mutex mu;
    std::string dir;
    std::string lastPath;
    std::string lastError;
    Sock cur = kBadSock;   // 正在处理的连接（stop 时用它立即中断，避免退出被拖住）
    FileCallback onFile;

    void clearCur(Sock c) {
        std::lock_guard<std::mutex> lk(mu);
        if (cur == c) cur = kBadSock;
    }

    void acceptLoop() {
        while (running.load()) {
            sockaddr_in addr{};
#if defined(_WIN32)
            int alen = sizeof(addr);
#else
            socklen_t alen = sizeof(addr);
#endif
            const Sock c = ::accept(listen, reinterpret_cast<sockaddr*>(&addr), &alen);
            if (c == kBadSock) {
                if (!running.load()) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(200));
                continue;
            }
            {
                std::lock_guard<std::mutex> lk(qmu);
                if (pend.size() >= 8) {   // 防积压：同一时刻只服务少量连接
                    closeSock(c);
                    continue;
                }
                pend.push(c);
            }
            qcv.notify_one();
        }
    }

    void workerLoop() {
        while (true) {
            Sock c = kBadSock;
            {
                std::unique_lock<std::mutex> lk(qmu);
                qcv.wait_for(lk, std::chrono::milliseconds(400),
                             [this] { return !pend.empty() || !running.load(); });
                if (!pend.empty()) {
                    c = pend.front();
                    pend.pop();
                }
            }
            if (c == kBadSock) {
                if (!running.load()) break;
                continue;
            }
            handle(c);
        }
    }

    void handle(Sock c) {
        namespace fs = std::filesystem;
        {
            std::lock_guard<std::mutex> lk(mu);
            cur = c;
        }
        // 收流超时：万一对方卡住，最多 15s 就放弃这一条，不会把进程拖死
#if defined(_WIN32)
        DWORD rto = 15000;
        ::setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rto), sizeof(rto));
#else
        timeval tv{15, 0};
        ::setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif

        uint32_t nameLen = 0;
        if (!readU32(c, nameLen) || nameLen == 0 || nameLen > 4096) {
            closeSock(c);
            clearCur(c);
            return;
        }
        std::string name(nameLen, '\0');
        if (!readFully(c, reinterpret_cast<uint8_t*>(name.data()), nameLen)) {
            closeSock(c);
            clearCur(c);
            return;
        }
        uint64_t size = 0;
        if (!readU64(c, size)) {
            closeSock(c);
            clearCur(c);
            return;
        }

        std::string outDir;
        {
            std::lock_guard<std::mutex> lk(mu);
            outDir = dir;
        }
        const std::string safe = sanitizeName(name);
        std::error_code ec;
        fs::create_directories(fromUtf8(outDir), ec);
        const fs::path target = uniquePath(fromUtf8(outDir), safe);
        APX_LOGI("文件接收：{}（{} 字节）→ {}", safe.c_str(),
                 static_cast<unsigned long long>(size), toUtf8(target).c_str());

        FILE* fp = nullptr;
#if defined(_WIN32)
        fp = ::_wfopen(target.wstring().c_str(), L"wb");
#else
        fp = std::fopen(target.c_str(), "wb");
#endif
        std::vector<uint8_t> buf(32 * 1024);
        uint64_t left = size;
        bool ok = (fp != nullptr);
        while (ok && left > 0) {
            const auto want = static_cast<int>(std::min<uint64_t>(buf.size(), left));
            const int r = ::recv(c, reinterpret_cast<char*>(buf.data()), want, 0);
            if (r <= 0) { ok = false; break; }
            if (std::fwrite(buf.data(), 1, static_cast<size_t>(r), fp) !=
                static_cast<size_t>(r)) {
                ok = false;
                break;
            }
            left -= static_cast<uint64_t>(r);
        }
        if (fp) std::fclose(fp);

        if (!ok || left > 0) {
            // 半截文件不留：删掉，免得用户以为收到了完整文件
            std::error_code re;
            fs::remove(target, re);
            APX_LOGW("文件接收中断，已丢弃半截文件：{}", toUtf8(target).c_str());
        } else {
            APX_LOGI("文件已保存：{}", toUtf8(target).c_str());
            FileCallback cb;
            {
                std::lock_guard<std::mutex> lk(mu);
                lastPath = toUtf8(target);
                cb = onFile;
            }
            if (cb) cb(toUtf8(target), size);
        }

        closeSock(c);
        clearCur(c);
    }
};

FileReceiver::FileReceiver() = default;
FileReceiver::~FileReceiver() { stop(); }

bool FileReceiver::start(uint16_t port, const std::string& dir, FileCallback onFile) {
    if (impl_ && impl_->running.load()) return true;
    ensureNet();

    auto* im = new Impl();
    im->dir = dir.empty() ? defaultDirImpl() : dir;
    im->onFile = std::move(onFile);

    Sock s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == kBadSock) {
        im->lastError = "socket() 失败";
        delete im;
        return false;
    }
    int yes = 1;
    ::setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof(yes));
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons(port);
    if (::bind(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0 || ::listen(s, 8) != 0) {
        im->lastError = "9512 端口被占用（已有面板/apxhost 在收文件？）";
        closeSock(s);
        delete im;
        return false;
    }
    im->listen = s;
    im->running.store(true);
    impl_.reset(im);
    im->acceptThread = std::thread([im] { im->acceptLoop(); });
    im->workerThread = std::thread([im] { im->workerLoop(); });
    APX_LOGI("9512 文件接收已就绪，落盘目录：{}", im->dir.c_str());
    return true;
}

void FileReceiver::stop() {
    if (!impl_) return;
    impl_->running.store(false);
    if (impl_->listen != kBadSock) {
        closeSock(impl_->listen);
        impl_->listen = kBadSock;
    }
    {
        // 正在传的那条立即中断，避免退出时被 15s 超时拖住
        std::lock_guard<std::mutex> lk(impl_->mu);
        if (impl_->cur != kBadSock) shutdownSock(impl_->cur);
    }
    impl_->qcv.notify_all();
    if (impl_->acceptThread.joinable()) impl_->acceptThread.join();
    if (impl_->workerThread.joinable()) impl_->workerThread.join();
    {
        std::lock_guard<std::mutex> lk(impl_->qmu);
        while (!impl_->pend.empty()) {
            closeSock(impl_->pend.front());
            impl_->pend.pop();
        }
    }
    impl_.reset();
}

bool FileReceiver::running() const { return impl_ && impl_->running.load(); }

std::string FileReceiver::lastError() const {
    if (!impl_) return std::string();
    std::lock_guard<std::mutex> lk(impl_->mu);
    return impl_->lastError;
}

std::string FileReceiver::lastFilePath() const {
    if (!impl_) return std::string();
    std::lock_guard<std::mutex> lk(impl_->mu);
    return impl_->lastPath;
}

std::string FileReceiver::defaultDir() { return defaultDirImpl(); }

}  // namespace apxpc::wireless
