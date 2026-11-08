#include "apxpc/localsend/local_send_receiver.hpp"

#include "apxpc/localsend/sha256.hpp"
#include "apxpc/log.hpp"
#include "apxpc/net/json.hpp"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <map>
#include <mutex>
#include <queue>
#include <random>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#endif

namespace fs = std::filesystem;

namespace apxpc::localsend {
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

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

/// std::filesystem 的 u8string() 在 C++20 是 char8_t 串；统一转成 UTF-8 字节的 std::string。
/// 反向构造也必须经 u8string —— 普通 narrow 构造在 Windows 上按 ANSI 解析，中文路径会乱码。
std::string toUtf8(const fs::path& p) {
    const std::u8string u8 = p.u8string();
    return std::string(u8.begin(), u8.end());
}

fs::path fromUtf8(const std::string& s) {
    return fs::path(std::u8string(s.begin(), s.end()));
}

std::string hostNameImpl() {
    char buf[256] = {0};
    if (::gethostname(buf, sizeof(buf) - 1) == 0 && buf[0] != '\0') return std::string(buf);
    return "APX-PC";
}

std::string randomIdImpl() {
    static std::mutex m;
    static std::mt19937_64 rng{std::random_device{}()};
    uint64_t a = 0, b = 0;
    {
        std::lock_guard<std::mutex> lk(m);
        a = rng();
        b = rng();
    }
    char buf[48];
    std::snprintf(buf, sizeof buf, "%08x-%04x-%04x-%04x-%012llx",
                  static_cast<unsigned>(a >> 32),
                  static_cast<unsigned>((a >> 16) & 0xffffu),
                  static_cast<unsigned>(a & 0xffffu),
                  static_cast<unsigned>((b >> 48) & 0xffffu),
                  static_cast<unsigned long long>(b & 0xffffffffffffULL));
    return std::string(buf);
}

std::string trimImpl(const std::string& s) {
    size_t b = 0, e = s.size();
    while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
    while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
    return s.substr(b, e - b);
}

std::string lowerImpl(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

/// 重名不覆盖：加 " (2)"、" (3)"…（与 file_receiver 同策略，别把用户已有文件冲掉）
fs::path uniquePath(const fs::path& dir, const std::string& name) {
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
    return toUtf8(fs::path(fromUtf8(base)) / "Downloads" / "AllPeriph");
}

/// 从 query 里取一个参数（LocalSend 的 sessionId / fileId 都是 UUID，无需百分号解码）
std::string queryParamImpl(const std::string& query, const std::string& key) {
    const std::string needle = key + "=";
    size_t pos = 0;
    while (pos <= query.size()) {
        const size_t amp = query.find('&', pos);
        const std::string part = query.substr(pos, amp == std::string::npos ? std::string::npos : amp - pos);
        if (part.rfind(needle, 0) == 0) return part.substr(needle.size());
        if (amp == std::string::npos) break;
        pos = amp + 1;
    }
    return {};
}

const char* statusText(int code) {
    switch (code) {
        case 200: return "OK";
        case 204: return "No Content";
        case 400: return "Bad Request";
        case 404: return "Not Found";
        case 500: return "Server Error";
        default:  return "Error";
    }
}

std::string jsonStr(const apxpc::net::Json& j, const char* key, const std::string& def) {
    const apxpc::net::Json* v = j.find(key);
    return (v && v->isString()) ? v->asString() : def;
}

}  // namespace

// ============================================================================
// 纯函数
// ============================================================================

std::string LocalSendReceiver::sanitizeName(const std::string& raw) {
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
    out = trimImpl(out);
    // 开头的点会让文件被当成隐藏文件、甚至构造出 ".." 这类危险名
    while (!out.empty() && out.front() == '.') out.erase(out.begin());
    if (out.size() > 200) out.resize(200);
    if (out.empty()) out = "file.bin";
    return out;
}

std::string LocalSendReceiver::guessFileType(const std::string& name) {
    const size_t dot = name.find_last_of('.');
    const std::string ext = dot == std::string::npos ? std::string() : lowerImpl(name.substr(dot + 1));
    static const char* kImage[] = {"jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg"};
    static const char* kVideo[] = {"mp4", "mkv", "avi", "mov", "webm", "flv", "3gp"};
    static const char* kAudio[] = {"mp3", "flac", "wav", "aac", "ogg", "m4a", "opus"};
    static const char* kText[] = {"txt", "md", "log", "json", "xml", "csv", "html", "pdf"};
    const auto in = [&ext](const char** arr, size_t n) {
        for (size_t i = 0; i < n; ++i)
            if (ext == arr[i]) return true;
        return false;
    };
    if (in(kImage, sizeof(kImage) / sizeof(kImage[0]))) return "image";
    if (in(kVideo, sizeof(kVideo) / sizeof(kVideo[0]))) return "video";
    if (in(kAudio, sizeof(kAudio) / sizeof(kAudio[0]))) return "audio";
    if (in(kText, sizeof(kText) / sizeof(kText[0]))) return "text";
    return "application";
}

std::string LocalSendReceiver::deviceInfoJson(const std::string& alias, const std::string& deviceModel,
                                             const std::string& deviceType, const std::string& fingerprint,
                                             const std::string& version, bool download) {
    apxpc::net::Json o = apxpc::net::Json::makeObject();
    o["alias"] = alias;
    o["version"] = version;
    o["deviceModel"] = deviceModel;
    o["deviceType"] = deviceType;
    o["fingerprint"] = fingerprint;
    o["download"] = download;
    return o.stringify();
}

std::string LocalSendReceiver::announceJson(const std::string& alias, const std::string& deviceModel,
                                           const std::string& deviceType, const std::string& fingerprint,
                                           const std::string& version, bool download, uint16_t port) {
    apxpc::net::Json o = apxpc::net::Json::makeObject();
    o["alias"] = alias;
    o["version"] = version;
    o["deviceModel"] = deviceModel;
    o["deviceType"] = deviceType;
    o["fingerprint"] = fingerprint;
    o["download"] = download;
    o["port"] = static_cast<int>(port);
    o["protocol"] = std::string("http");
    o["announce"] = true;
    return o.stringify();
}

std::vector<PreparedFile> LocalSendReceiver::parsePrepareRequest(std::string_view body, std::string* err,
                                                                std::string* senderAlias) {
    std::vector<PreparedFile> out;
    std::string e;
    const apxpc::net::Json root = apxpc::net::Json::parse(body, &e);
    if (!root.isObject()) {
        if (err) *err = e.empty() ? "根不是 JSON 对象" : e;
        return out;
    }
    if (const apxpc::net::Json* info = root.find("info"); info && info->isObject()) {
        if (senderAlias) *senderAlias = jsonStr(*info, "alias", "");
    }
    const apxpc::net::Json* files = root.find("files");
    if (!files || !files->isObject()) {
        if (err) *err = "缺少 files 对象";
        return out;
    }
    for (const auto& kv : files->asObject()) {
        const apxpc::net::Json& f = kv.second;
        if (!f.isObject()) continue;
        PreparedFile pf;
        pf.id = kv.first;
        pf.fileName = jsonStr(f, "fileName", "file_" + kv.first);
        pf.sha256 = jsonStr(f, "sha256", "");
        if (const apxpc::net::Json* s = f.find("size"); s && s->isNumber() && s->asNumber() > 0) {
            pf.size = static_cast<uint64_t>(s->asNumber());
        }
        out.push_back(std::move(pf));
    }
    if (err) err->clear();
    return out;
}

std::string LocalSendReceiver::buildPrepareResponse(const std::string& sessionId,
                                                   const std::vector<PreparedFile>& files) {
    apxpc::net::Json root = apxpc::net::Json::makeObject();
    root["sessionId"] = sessionId;
    apxpc::net::Json filesJson = apxpc::net::Json::makeObject();
    for (const auto& f : files) {
        // token 直接用文件 id —— 与 Android 端实现一致（它把 respFiles.put(key, key)），
        // 官方端点只要求「回给对端的 token 能被 upload 的 fileId 命中」，不要求不可猜。
        filesJson[f.id] = f.id;
    }
    root["files"] = filesJson;
    return root.stringify();
}

std::string statusJson(const StatusSnapshot& s) {
    apxpc::net::Json o = apxpc::net::Json::makeObject();
    o["running"] = s.running;
    o["port"] = static_cast<int>(s.port);
    o["dir"] = s.dir;
    o["received"] = s.received;
    o["error"] = s.error;
    o["warn"] = s.warn;
    apxpc::net::Json arr = apxpc::net::Json::makeArray();
    for (const Device& d : s.devices) {
        apxpc::net::Json e = apxpc::net::Json::makeObject();
        e["alias"] = d.alias;
        e["deviceType"] = d.deviceType;
        e["ip"] = d.ip;
        e["port"] = static_cast<int>(d.port);
        e["lastSeenMs"] = static_cast<double>(d.lastSeenMs);
        arr.push(e);
    }
    o["devices"] = arr;
    return o.stringify();
}

std::string LocalSendReceiver::defaultDir() { return defaultDirImpl(); }

// ============================================================================
// 实现
// ============================================================================

struct LocalSendReceiver::Impl {
    Options opt;
    std::string dir;
    std::string alias;
    std::string deviceModel;
    std::string version{kProtocolVersion};
    std::string fingerprint;

    std::atomic<bool> running{false};
    std::atomic<int> received{0};

    mutable std::mutex mu;
    std::string lastError;
    std::string warn;   // 起来了但能力受限（多播被占等）——面板要如实说出来，别让「附近没有设备」背锅
    std::string lastPath;
    std::map<std::string, Device> devices;

    struct Slot {
        std::string fileName;   // 清洗后的落盘名
        std::string path;       // 目标全路径（prepare 时定好，upload 阶段不再改名）
        std::string sha256;     // 对端声明值，可能为空
        uint64_t declared = 0;
        uint64_t written = 0;
        bool complete = false;
    };
    struct Session {
        std::string id;
        std::string sender;
        int64_t touchedMs = 0;
        std::map<std::string, Slot> files;   // key = fileId，同时也是 upload 的 token
    };
    std::map<std::string, Session> sessions;

    FileCallback onFile;
    DeviceCallback onDevice;

    Sock udp = kBadSock;
    Sock listen = kBadSock;
    uint16_t listenPort = kDefaultPort;
    std::thread udpThread;
    std::thread acceptThread;
    std::vector<std::thread> workers;
    std::mutex qmu;
    std::condition_variable qcv;
    std::queue<Sock> pend;
    std::mutex smu;
    std::set<Sock> active;

    // ---------------------------------------------------------------- 网络原语
    /// 等可读。超时返回 false；select 出错（例如 fd 已被关）也返回 false 但不空转。
    bool waitReadable(Sock s, int timeoutMs) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(s, &rfds);
        timeval tv{};
        tv.tv_sec = timeoutMs / 1000;
        tv.tv_usec = (timeoutMs % 1000) * 1000;
#if defined(_WIN32)
        const int r = ::select(0, &rfds, nullptr, nullptr, &tv);
#else
        const int r = ::select(s + 1, &rfds, nullptr, nullptr, &tv);
#endif
        if (r < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            return false;
        }
        return r > 0;
    }

    /// 带超时读：>0 收到字节；-1 对端关闭/出错；0 已请求停止（调用方应当收摊）
    int readSome(Sock s, char* dst, int n, int timeoutMs = 200) {
        while (running.load()) {
            if (!waitReadable(s, timeoutMs)) continue;
            const int r = ::recv(s, dst, n, 0);
            if (r > 0) return r;
            return -1;   // 0 = 对端有序关闭；<0 = 出错，对上层都是「读不到了」
        }
        return 0;
    }

    void sendAll(Sock s, const std::string& data) {
        size_t off = 0;
        while (off < data.size() && running.load()) {
            const int r = ::send(s, data.data() + off,
                                 static_cast<int>(std::min<size_t>(data.size() - off, 1u << 20)), 0);
            if (r <= 0) return;
            off += static_cast<size_t>(r);
        }
    }

    void respondJson(Sock s, int code, const std::string& body) {
        sendAll(s, std::string("HTTP/1.1 ") + std::to_string(code) + " " + statusText(code) +
                        "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " +
                        std::to_string(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
    }

    void respondEmpty(Sock s, int code) {
        sendAll(s, std::string("HTTP/1.1 ") + std::to_string(code) + " " + statusText(code) +
                        "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    }

    // ---------------------------------------------------------------- 连接读写
    /// 连接级读缓冲。HTTP 头和 body 走同一份缓冲：
    /// 若用「带字符缓冲的流」去读头，会预读吞掉 body 开头字节导致文件损坏
    /// —— Android 端为此专门避开了 BufferedReader，这里同理，只是把缓冲显式摊开。
    struct Reader {
        Impl* self = nullptr;
        Sock s = kBadSock;
        std::vector<char> buf;
        size_t pos = 0;

        bool fill() {
            char tmp[16 * 1024];
            const int r = self->readSome(s, tmp, static_cast<int>(sizeof tmp));
            if (r <= 0) return false;
            buf.insert(buf.end(), tmp, tmp + r);
            return true;
        }

        void compact() {
            if (pos > 0) {
                buf.erase(buf.begin(), buf.begin() + static_cast<std::ptrdiff_t>(pos));
                pos = 0;
            }
        }

        bool readLine(std::string& out) {
            while (true) {
                for (size_t i = pos; i < buf.size(); ++i) {
                    if (buf[i] == '\n') {
                        size_t end = i;
                        if (end > pos && buf[end - 1] == '\r') --end;
                        out.assign(buf.data() + pos, end - pos);
                        pos = i + 1;
                        compact();
                        return true;
                    }
                }
                if (buf.size() - pos > 16 * 1024) return false;   // 头过长，拒掉
                if (!fill()) return false;
            }
        }

        /// 精确消费 n 字节。大文件不整块进内存：缓冲吃完就直接从 socket 读。
        bool readExact(uint64_t n, FILE* fp, Sha256& sha, uint64_t& written) {
            char tmp[64 * 1024];
            while (n > 0) {
                const size_t avail = buf.size() - pos;
                if (avail > 0) {
                    const size_t take = static_cast<size_t>(std::min<uint64_t>(avail, n));
                    if (!sink(buf.data() + pos, take, fp, sha, written)) return false;
                    pos += take;
                    n -= take;
                    compact();
                    continue;
                }
                const int want = static_cast<int>(std::min<uint64_t>(sizeof tmp, n));
                const int r = self->readSome(s, tmp, want);
                if (r <= 0) return false;
                if (!sink(tmp, static_cast<size_t>(r), fp, sha, written)) return false;
                n -= static_cast<uint64_t>(r);
            }
            return true;
        }

        /// 丢弃 n 字节（不关心内容的请求体，例如 register / cancel）
        bool discard(uint64_t n) {
            FILE* none = nullptr;
            Sha256 sha;
            uint64_t dummy = 0;
            return readExact(n, none, sha, dummy);
        }

        /// 读 n 字节到内存（请求体是 JSON 时用，不落盘）
        bool readInto(std::string& out, uint64_t n) {
            out.assign(static_cast<size_t>(n), '\0');
            size_t got = 0;
            char tmp[16 * 1024];
            while (got < out.size()) {
                const size_t avail = buf.size() - pos;
                if (avail > 0) {
                    const size_t take = std::min(avail, out.size() - got);
                    std::memcpy(out.data() + got, buf.data() + pos, take);
                    pos += take;
                    got += take;
                    compact();
                    continue;
                }
                const int want = static_cast<int>(std::min<size_t>(sizeof tmp, out.size() - got));
                const int r = self->readSome(s, tmp, want);
                if (r <= 0) return false;
                std::memcpy(out.data() + got, tmp, static_cast<size_t>(r));
                got += static_cast<size_t>(r);
            }
            return true;
        }

        bool sink(const char* p, size_t n, FILE* fp, Sha256& sha, uint64_t& written) {
            if (fp) {
                if (std::fwrite(p, 1, n, fp) != n) return false;
                sha.update(p, n);
            }
            written += n;
            return true;
        }
    };

    // ---------------------------------------------------------------- 设备发现
    void sendAnnounce() {
        if (udp == kBadSock) return;
        const std::string payload = LocalSendReceiver::announceJson(
            alias, deviceModel, opt.deviceType, fingerprint, version, true, listenPort);
        sockaddr_in to{};
        to.sin_family = AF_INET;
        to.sin_port = htons(kDefaultPort);
        to.sin_addr.s_addr = ::inet_addr(opt.multicastGroup.c_str());
        ::sendto(udp, payload.data(), static_cast<int>(payload.size()), 0,
                 reinterpret_cast<sockaddr*>(&to), sizeof(to));
    }

    void onAnnouncePacket(const char* data, size_t n, const std::string& ip) {
        std::string err;
        const apxpc::net::Json j = apxpc::net::Json::parse(std::string_view(data, n), &err);
        if (!j.isObject()) return;
        const std::string fp = jsonStr(j, "fingerprint", ip);
        if (fp.empty() || fp == fingerprint) return;   // 自己广播的回环包
        const std::string proto = jsonStr(j, "protocol", "http");
        if (proto != "http") return;
        const apxpc::net::Json* p = j.find("port");
        const int port = (p && p->isNumber()) ? p->asInt(0) : 0;
        if (port <= 0 || port > 65535) return;

        Device d;
        d.alias = jsonStr(j, "alias", ip);
        d.deviceType = jsonStr(j, "deviceType", "desktop");
        d.fingerprint = fp;
        d.ip = ip;
        d.port = static_cast<uint16_t>(port);
        d.lastSeenMs = nowMs();

        bool isNew = false;
        {
            std::lock_guard<std::mutex> lk(mu);
            isNew = devices.find(fp) == devices.end();
            devices[fp] = d;
        }
        if (isNew) {
            APX_LOGI("LocalSend 发现设备：{}（{}:{}）", d.alias.c_str(), d.ip.c_str(),
                     static_cast<unsigned>(d.port));
            if (onDevice) onDevice(d);
        }
    }

    // 单播 register 入表：与 onAnnouncePacket 同一入表路径（60s 窗口、onDevice 回调）。
    // body 是对端 selfInfo 顶层 JSON，或官方 {info:{...}} 包装，两种都吃。
    void onRegisterFromPeer(Sock c, const std::string& body) {
        if (body.empty()) return;
        std::string err;
        const apxpc::net::Json j = apxpc::net::Json::parse(body, &err);
        if (!j.isObject()) return;
        const apxpc::net::Json* info = &j;
        if (const apxpc::net::Json* nested = j.find("info"); nested && nested->isObject()) info = nested;
        const std::string proto = jsonStr(*info, "protocol", "http");
        if (proto != "http") return;
        const apxpc::net::Json* p = info->find("port");
        const int port = (p && p->isNumber()) ? p->asInt(0) : 0;
        if (port <= 0 || port > 65535) return;
        char ipStr[INET_ADDRSTRLEN] = {0};
        sockaddr_in peer{};
#if defined(_WIN32)
        int peerLen = sizeof(peer);
#else
        socklen_t peerLen = sizeof(peer);
#endif
        if (::getpeername(c, reinterpret_cast<sockaddr*>(&peer), &peerLen) != 0) return;
        if (!::inet_ntop(AF_INET, &peer.sin_addr, ipStr, sizeof ipStr)) return;
        const std::string peerIp(ipStr);
        const std::string fp = jsonStr(*info, "fingerprint", peerIp);
        const std::string key = fp.empty() ? peerIp : fp;
        if (key == fingerprint) return;
        Device d;
        d.alias = jsonStr(*info, "alias", peerIp);
        d.deviceType = jsonStr(*info, "deviceType", "phone");
        d.fingerprint = key;
        d.ip = peerIp;
        d.port = static_cast<uint16_t>(port);
        d.lastSeenMs = nowMs();
        bool isNew = false;
        {
            std::lock_guard lk(mu);
            isNew = devices.find(key) == devices.end();
            devices[key] = d;
        }
        if (isNew) {
            APX_LOGI("LocalSend register 入表：{}（{}:{}）", d.alias.c_str(), d.ip.c_str(),
                     static_cast<int>(d.port));
            if (onDevice) onDevice(d);
        }
    }

    void udpLoop() {
        if (udp == kBadSock) return;
        int ttl = 1;
        ::setsockopt(udp, IPPROTO_IP, IP_MULTICAST_TTL,
                     reinterpret_cast<const char*>(&ttl), sizeof(ttl));
        ip_mreq mreq{};
        mreq.imr_multiaddr.s_addr = ::inet_addr(opt.multicastGroup.c_str());
        mreq.imr_interface.s_addr = htonl(INADDR_ANY);
        if (::setsockopt(udp, IPPROTO_IP, IP_ADD_MEMBERSHIP,
                         reinterpret_cast<const char*>(&mreq), sizeof(mreq)) != 0) {
            // 某些多网卡／受限网络下加入组播会失败：不影响收单播来的 register，
            // 但发现能力下降，必须说出来而不是装作没事。
            if (warn.empty())
                warn = "加入多播组 " + opt.multicastGroup +
                       " 失败（受限网络/多网卡）：只能被动等待对端直连，「附近设备」看不到别的机器";
            APX_LOGW("LocalSend 加入多播组 {} 失败，只能被动等待对端直连", opt.multicastGroup.c_str());
        } else {
            APX_LOGI("LocalSend 已加入多播组 {}:{}", opt.multicastGroup.c_str(),
                     static_cast<unsigned>(kDefaultPort));
        }

        // 启动突发 100/500/2000ms（与 Android 端一致），让对方一上来就能看见本机
        const int64_t burstMs[3] = {100, 500, 2000};
        const int64_t t0 = nowMs();
        size_t burstIdx = 0;
        int64_t lastAnnounce = nowMs();

        char buf[64 * 1024];
        while (running.load()) {
            const int64_t now = nowMs();
            if (burstIdx < 3 && now - t0 >= burstMs[burstIdx]) {
                sendAnnounce();
                ++burstIdx;
                lastAnnounce = now;
            }
            if (burstIdx >= 3 && now - lastAnnounce >= static_cast<int64_t>(opt.announcePeriodMs)) {
                sendAnnounce();
                lastAnnounce = now;
            }
            if (!waitReadable(udp, 200)) continue;

            sockaddr_in from{};
#if defined(_WIN32)
            int fromLen = sizeof(from);
#else
            socklen_t fromLen = sizeof(from);
#endif
            const int r = ::recvfrom(udp, buf, static_cast<int>(sizeof buf), 0,
                                     reinterpret_cast<sockaddr*>(&from), &fromLen);
            if (r <= 0) continue;
            char ipStr[INET_ADDRSTRLEN] = {0};
            if (!::inet_ntop(AF_INET, &from.sin_addr, ipStr, sizeof ipStr)) continue;
            onAnnouncePacket(buf, static_cast<size_t>(r), std::string(ipStr));
        }
    }

    // ---------------------------------------------------------------- 请求处理
    void pruneSessionsLocked() {
        const int64_t cutoff = nowMs() - 30 * 60 * 1000;
        for (auto it = sessions.begin(); it != sessions.end();) {
            if (it->second.touchedMs < cutoff) {
                for (const auto& kv : it->second.files) {
                    if (!kv.second.complete) {
                        std::error_code ec;
                        fs::remove(fromUtf8(kv.second.path), ec);
                    }
                }
                APX_LOGW("LocalSend 会话超时清理：{}", it->first.c_str());
                it = sessions.erase(it);
            } else {
                ++it;
            }
        }
    }

    std::string handlePrepare(const std::string& body) {
        std::string err;
        std::string sender;
        const std::vector<PreparedFile> files =
            LocalSendReceiver::parsePrepareRequest(body, &err, &sender);
        if (!err.empty() || files.empty()) {
            // 以前这里只打一行日志、照样建空会话并回 200：对端以为清单已受理，
            // 接着 upload 却 404，问题被藏到下一步才以「找不到会话」的形式冒出来。
            // 现在直接 400，且不留下空会话（返回空串 = 调用方回 400）。
            APX_LOGW("LocalSend prepare-upload 已拒绝：{}",
                     err.empty() ? "清单里一个文件都没有" : err.c_str());
            return std::string();
        }

        const std::string sid = randomIdImpl();
        Session s;
        s.id = sid;
        s.sender = sender.empty() ? std::string("未知设备") : sender;
        s.touchedMs = nowMs();

        std::error_code ec;
        fs::create_directories(fromUtf8(dir), ec);
        for (const auto& f : files) {
            Slot slot;
            slot.fileName = LocalSendReceiver::sanitizeName(f.fileName);
            slot.sha256 = f.sha256;
            slot.declared = f.size;
            slot.path = toUtf8(uniquePath(fromUtf8(dir), slot.fileName));
            s.files[f.id] = std::move(slot);
        }
        APX_LOGI("LocalSend 收到清单：{} 个文件，来自 {}，会话 {}",
                 std::to_string(files.size()).c_str(), s.sender.c_str(), sid.c_str());
        {
            std::lock_guard<std::mutex> lk(mu);
            pruneSessionsLocked();
            sessions[sid] = std::move(s);
        }
        return LocalSendReceiver::buildPrepareResponse(sid, files);
    }

    void handleUpload(Sock c, Reader& rd, const std::string& query, uint64_t contentLength) {
        const std::string sid = queryParamImpl(query, "sessionId");
        const std::string token = queryParamImpl(query, "fileId");
        std::string path;
        std::string declaredSha;
        uint64_t declaredSize = 0;
        bool found = false;
        {
            std::lock_guard<std::mutex> lk(mu);
            const auto sit = sessions.find(sid);
            if (sit != sessions.end()) {
                const auto fit = sit->second.files.find(token);
                if (fit != sit->second.files.end()) {
                    path = fit->second.path;
                    declaredSha = fit->second.sha256;
                    declaredSize = fit->second.declared;
                    sit->second.touchedMs = nowMs();
                    found = true;
                }
            }
        }
        if (!found) {
            // 未知会话/未知 token：可能超时清理过，也可能对端拿错 token
            respondEmpty(c, 404);
            return;
        }
        if (contentLength == 0) {
            // 没有 Content-Length 就不知道边界，不能猜着读（会把后续请求当文件内容）
            APX_LOGW("LocalSend upload 缺少 Content-Length，已拒绝：{}", path.c_str());
            respondEmpty(c, 400);
            return;
        }
        if (declaredSize != 0 && declaredSize != contentLength) {
            APX_LOGW("LocalSend upload 长度与清单不符（清单 {}，实到 {}）：{}",
                     static_cast<unsigned long long>(declaredSize),
                     static_cast<unsigned long long>(contentLength), path.c_str());
        }

        FILE* fp = nullptr;
#if defined(_WIN32)
        fp = ::_wfopen(fromUtf8(path).wstring().c_str(), L"wb");
#else
        fp = std::fopen(path.c_str(), "wb");
#endif
        if (!fp) {
            APX_LOGW("LocalSend 无法创建目标文件：{}", path.c_str());
            respondEmpty(c, 500);
            return;
        }

        Sha256 sha;
        uint64_t written = 0;
        const bool readOk = rd.readExact(contentLength, fp, sha, written);
        std::fclose(fp);
        const std::string diskSha = sha.hex();

        if (!readOk || written != contentLength) {
            // 半截文件不留：中断时删掉，免得用户以为收到了完整文件
            std::error_code ec;
            fs::remove(fromUtf8(path), ec);
            APX_LOGW("LocalSend 接收中断，已丢弃半截文件：{}（{}/{} 字节）", path.c_str(),
                     static_cast<unsigned long long>(written),
                     static_cast<unsigned long long>(contentLength));
            {
                std::lock_guard<std::mutex> lk(mu);
                sessions.erase(sid);
            }
            respondEmpty(c, 500);
            return;
        }

        const bool shaChecked = !declaredSha.empty();
        const bool shaOk = !shaChecked || lowerImpl(declaredSha) == diskSha;
        if (shaChecked && !shaOk) {
            // 不删文件：对方声明的摘要可能是它自己算错/用了别的算法，删掉反而丢数据。
            // 但必须明说，不能让「校验没过」这件事悄悄过去。
            APX_LOGW("LocalSend 校验不一致：{}（声明 {}，实际 {}）", path.c_str(),
                     declaredSha.c_str(), diskSha.c_str());
        }

        bool allDone = false;
        std::string fromAlias;   // 面板提示文案要「谁发来的」，在会话被 erase 之前取出来
        {
            std::lock_guard<std::mutex> lk(mu);
            const auto sit = sessions.find(sid);
            if (sit != sessions.end()) {
                fromAlias = sit->second.sender;
                auto fit = sit->second.files.find(token);
                if (fit != sit->second.files.end()) {
                    fit->second.written = written;
                    fit->second.complete = true;
                }
                sit->second.touchedMs = nowMs();
                allDone = std::all_of(sit->second.files.begin(), sit->second.files.end(),
                                      [](const auto& kv) { return kv.second.complete; });
                if (allDone) {
                    APX_LOGI("LocalSend 会话 {} 全部接收完成（{} 个文件，来自 {}）", sid.c_str(),
                             std::to_string(sit->second.files.size()).c_str(),
                             sit->second.sender.c_str());
                    sessions.erase(sit);
                }
            }
            lastPath = path;
        }
        received.fetch_add(1);
        APX_LOGI("LocalSend 已接收：{}（{} 字节，sha256 {}）", path.c_str(),
                 static_cast<unsigned long long>(written), shaOk ? "一致" : "不一致");
        if (onFile) onFile(path, written, fromAlias);
        respondEmpty(c, 204);
    }

    void handleCancel(const std::string& query) {
        const std::string sid = queryParamImpl(query, "sessionId");
        std::lock_guard<std::mutex> lk(mu);
        const auto it = sessions.find(sid);
        if (it == sessions.end()) return;
        for (const auto& kv : it->second.files) {
            if (!kv.second.complete) {
                std::error_code ec;
                fs::remove(fromUtf8(kv.second.path), ec);
            }
        }
        sessions.erase(it);
        APX_LOGI("LocalSend 会话已取消：{}", sid.c_str());
    }

    void registerActive(Sock c) {
        std::lock_guard<std::mutex> lk(smu);
        active.insert(c);
    }

    void unregisterActive(Sock c) {
        std::lock_guard<std::mutex> lk(smu);
        active.erase(c);
    }

    void handleConn(Sock c) {
        registerActive(c);
        Reader rd;
        rd.self = this;
        rd.s = c;

        std::string line;
        if (!rd.readLine(line)) {
            unregisterActive(c);
            closeSock(c);
            return;
        }
        std::istringstream ls(line);
        std::string method, target, httpVer;
        ls >> method >> target >> httpVer;
        method = lowerImpl(method);

        uint64_t contentLength = 0;
        bool expect100 = false;
        std::string h;
        while (rd.readLine(h)) {
            if (h.empty()) break;
            const size_t colon = h.find(':');
            if (colon == std::string::npos) continue;
            const std::string k = lowerImpl(trimImpl(h.substr(0, colon)));
            const std::string v = trimImpl(h.substr(colon + 1));
            if (k == "content-length") {
                contentLength = std::strtoull(v.c_str(), nullptr, 10);
            } else if (k == "expect" && lowerImpl(v).find("100-continue") != std::string::npos) {
                expect100 = true;
            }
        }
        // 对端在等 100 Continue 才发 body：不回就一直卡到它自己超时
        if (expect100) sendAll(c, "HTTP/1.1 100 Continue\r\n\r\n");

        std::string path = target;
        std::string query;
        if (const size_t q = target.find('?'); q != std::string::npos) {
            path = target.substr(0, q);
            query = target.substr(q + 1);
        }

        if (method == "get" && path == kPathInfo) {
            respondJson(c, 200, deviceInfoJson(alias, deviceModel, opt.deviceType, fingerprint, version, true));
        } else if (method == "post" && path == kPathRegister) {
            // v223.29.32 register 入表兕底（官方协议语义）：手机收到本机 announce 后会
            // 单播 register 应答 —— 多播（Wi-Fi→有线）被路由器丢时，这是让『附近设备』
            // 看到手机的唯一通路。此前只回 200 不记设备，列表对手机永远空着。
            std::string regBody;
            if (contentLength <= (64u << 10)) {
                if (!rd.readInto(regBody, contentLength)) regBody.clear();
            } else {
                rd.discard(contentLength);
            }
            onRegisterFromPeer(c, regBody);
            respondJson(c, 200, deviceInfoJson(alias, deviceModel, opt.deviceType, fingerprint, version, true));
        } else if (method == "post" && path == kPathPrepare) {
            // 清单是 JSON，读到内存即可；顺带上限防止有人拿它当上传口
            if (contentLength > (8u << 20)) {
                rd.discard(contentLength);
                respondEmpty(c, 400);
            } else {
                std::string body;
                if (!rd.readInto(body, contentLength)) {
                    respondEmpty(c, 400);
                } else {
                    const std::string resp = handlePrepare(body);
                    if (resp.empty()) respondEmpty(c, 400);   // 清单不合法：明确拒绝，别回 200
                    else respondJson(c, 200, resp);
                }
            }
        } else if ((method == "post" || method == "put") && path == kPathUpload) {
            handleUpload(c, rd, query, contentLength);
        } else if (method == "post" && path == kPathCancel) {
            rd.discard(contentLength);
            handleCancel(query);
            respondEmpty(c, 204);
        } else {
            rd.discard(contentLength);
            respondEmpty(c, 404);
        }

        unregisterActive(c);
        closeSock(c);
    }

    // ---------------------------------------------------------------- 线程
    void acceptLoop() {
        while (running.load()) {
            if (!waitReadable(listen, 200)) continue;
            sockaddr_in addr{};
#if defined(_WIN32)
            int alen = sizeof(addr);
#else
            socklen_t alen = sizeof(addr);
#endif
            const Sock c = ::accept(listen, reinterpret_cast<sockaddr*>(&addr), &alen);
            if (c == kBadSock) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                continue;
            }
            {
                std::lock_guard<std::mutex> lk(qmu);
                if (pend.size() >= 16) {   // 防积压
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
                if (!running.load() && pend.empty()) return;
                if (pend.empty()) continue;
                c = pend.front();
                pend.pop();
            }
            if (c == kBadSock) continue;
            handleConn(c);
        }
    }

    bool openSockets() {
        // HTTP 监听：53317 被占依次回退 53318/53319（与 Android 端同策略），
        // 实际端口由 announce 带出去，对端不需要猜。
        for (uint16_t p = opt.port; p < static_cast<uint16_t>(opt.port + 3); ++p) {
            const Sock s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
            if (s == kBadSock) {
                lastError = "创建 TCP socket 失败";
                return false;
            }
            int yes = 1;
            ::setsockopt(s, SOL_SOCKET, SO_REUSEADDR,
                         reinterpret_cast<const char*>(&yes), sizeof(yes));
            sockaddr_in a{};
            a.sin_family = AF_INET;
            a.sin_addr.s_addr = htonl(INADDR_ANY);
            a.sin_port = htons(p);
            if (::bind(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) == 0 && ::listen(s, 16) == 0) {
                listen = s;
                listenPort = p;
                break;
            }
            closeSock(s);
        }
        if (listen == kBadSock) {
            lastError = "53317/53318/53319 都被占用（已有 LocalSend 在跑？）";
            return false;
        }

        // 发现用的 UDP socket：绑在多播端口上，让对方的 announce / register 都能到这里。
        // 不给 fd 加超时——读取统一走 select + 短超时，停止时不会卡在 recvfrom 上。
        udp = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (udp != kBadSock) {
            int yes = 1;
            ::setsockopt(udp, SOL_SOCKET, SO_REUSEADDR,
                         reinterpret_cast<const char*>(&yes), sizeof(yes));
            sockaddr_in a{};
            a.sin_family = AF_INET;
            a.sin_addr.s_addr = htonl(INADDR_ANY);
            a.sin_port = htons(kDefaultPort);
            if (::bind(udp, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0) {
                warn = "多播端口 " + std::to_string(kDefaultPort) +
                       " 绑定失败（可能已有 LocalSend 在跑）：本机仍能收对方直连发来的文件，"
                       "但「附近设备」列表会一直空着";
                APX_LOGW("LocalSend 多播端口 {} 绑定失败，发现能力受限（仍可被直连发送）",
                         std::to_string(kDefaultPort).c_str());
                closeSock(udp);
                udp = kBadSock;
            }
        }
        return true;
    }

    void closeAllLockedFree() {
        if (listen != kBadSock) {
            closeSock(listen);
            listen = kBadSock;
        }
        if (udp != kBadSock) {
            closeSock(udp);
            udp = kBadSock;
        }
        std::lock_guard<std::mutex> lk(qmu);
        while (!pend.empty()) {
            closeSock(pend.front());
            pend.pop();
        }
    }
};

// ============================================================================

LocalSendReceiver::LocalSendReceiver() = default;
LocalSendReceiver::~LocalSendReceiver() { stop(); }

bool LocalSendReceiver::start(const Options& opt, FileCallback onFile, DeviceCallback onDevice) {
    if (impl_ && impl_->running.load()) return true;
    ensureNet();

    auto* im = new Impl();
    im->opt = opt;
    im->dir = opt.dir.empty() ? defaultDirImpl() : opt.dir;
    im->alias = opt.alias.empty() ? hostNameImpl() : opt.alias;
    im->deviceModel = opt.deviceModel.empty() ? im->alias : opt.deviceModel;
    im->fingerprint = randomIdImpl();
    im->onFile = std::move(onFile);
    im->onDevice = std::move(onDevice);

    if (!im->openSockets()) {
        // 保留这个 Impl 只为让 lastError() 能回答「为什么没起来」；线程一个都没起。
        const std::string err = im->lastError;
        im->running.store(false);
        impl_.reset(im);
        APX_LOGW("LocalSend 启动失败：{}", err.c_str());
        return false;
    }

    impl_.reset(im);
    im->running.store(true);
    im->udpThread = std::thread([im] { im->udpLoop(); });
    im->acceptThread = std::thread([im] { im->acceptLoop(); });
    for (int i = 0; i < 4; ++i) im->workers.emplace_back([im] { im->workerLoop(); });

    APX_LOGI("LocalSend 接收端已就绪：HTTP {}，落盘 {}", std::to_string(im->listenPort).c_str(),
             im->dir.c_str());
    return true;
}

void LocalSendReceiver::stop() {
    if (!impl_) return;
    if (!impl_->running.exchange(false)) return;

    // 不靠「关 fd 打断阻塞的系统调用」：所有 socket 读写都在 select + 200ms 超时里轮询，
    // 看到 running=false 就自己收摊。这样停止路径不会卡住（beacon 线程那个教训）。
    impl_->qcv.notify_all();
    {
        std::lock_guard<std::mutex> lk(impl_->smu);
        for (Sock s : impl_->active) shutdownSock(s);
    }
    if (impl_->udpThread.joinable()) impl_->udpThread.join();
    if (impl_->acceptThread.joinable()) impl_->acceptThread.join();
    for (auto& t : impl_->workers) {
        if (t.joinable()) t.join();
    }
    impl_->workers.clear();
    impl_->closeAllLockedFree();
    impl_.reset();
}

bool LocalSendReceiver::running() const { return impl_ && impl_->running.load(); }

uint16_t LocalSendReceiver::listenPort() const {
    return impl_ ? impl_->listenPort : kDefaultPort;
}

std::string LocalSendReceiver::fingerprint() const {
    return impl_ ? impl_->fingerprint : std::string();
}

std::string LocalSendReceiver::lastError() const {
    if (!impl_) return std::string();
    std::lock_guard<std::mutex> lk(impl_->mu);
    return impl_->lastError;
}

std::string LocalSendReceiver::lastFilePath() const {
    if (!impl_) return std::string();
    std::lock_guard<std::mutex> lk(impl_->mu);
    return impl_->lastPath;
}

int LocalSendReceiver::receivedCount() const { return impl_ ? impl_->received.load() : 0; }

std::vector<Device> LocalSendReceiver::devices() const {
    std::vector<Device> out;
    if (!impl_) return out;
    const int64_t cutoff = nowMs() - 60'000;
    {
        std::lock_guard<std::mutex> lk(impl_->mu);
        for (const auto& kv : impl_->devices) {
            if (kv.second.lastSeenMs >= cutoff) out.push_back(kv.second);
        }
    }
    std::sort(out.begin(), out.end(),
              [](const Device& a, const Device& b) { return a.lastSeenMs > b.lastSeenMs; });
    return out;
}

StatusSnapshot LocalSendReceiver::snapshot() const {
    StatusSnapshot s;
    // 没起来也要答得出话：running=false + 默认端口 + 落盘目录（面板据此显示「已关闭」）
    s.dir = impl_ ? impl_->dir : defaultDirImpl();
    s.port = impl_ ? impl_->listenPort : kDefaultPort;
    if (!impl_) {
        s.error = "未启动";
        return s;
    }
    s.running = impl_->running.load();
    s.error = impl_->lastError;
    s.warn = impl_->warn;
    s.received = impl_->received.load();
    s.devices = devices();
    return s;
}

}  // namespace apxpc::localsend
