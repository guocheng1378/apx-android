#include "apxpc/localsend/local_send_sender.hpp"

#include "apxpc/localsend/sha256.hpp"
#include "apxpc/log.hpp"
#include "apxpc/net/json.hpp"
#include "httplib.h"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <vector>

namespace apxpc::localsend {
namespace {

// 路径常量：与 Android 端 LocalSendProtocol 逐字符一致（那边是 Kotlin 字符串常量，
// 这里再抄一遍而不是引用接收端的 —— 发送端不该依赖接收端的头文件，两个方向各留一份常量
// 才符合「一处改动不影响另一方向」的原则；真值仍以官方 API 路径为准）。
constexpr const char* kPathInfo = "/api/localsend/v2/info";
constexpr const char* kPathRegister = "/api/localsend/v2/register";
constexpr const char* kPathPrepare = "/api/localsend/v2/prepare-upload";
constexpr const char* kPathUpload = "/api/localsend/v2/upload";
constexpr const char* kPathCancel = "/api/localsend/v2/cancel";

std::string lowerImpl(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string hostNameImpl() {
    char buf[256] = {0};
    if (::gethostname(buf, sizeof(buf) - 1) == 0 && buf[0] != '\0') return std::string(buf);
    return "APX-PC";
}

std::string jsonStr(const apxpc::net::Json& j, const char* key, const std::string& def) {
    const apxpc::net::Json* v = j.find(key);
    return (v && v->isString()) ? v->asString() : def;
}

// ---------------------------------------------------------------- HTTP 客户端（cpp-httplib）
// 去轮子第三步：手搓客户端退役。它修过三轮——204 无长度响应漏读、Content-Length
// 行尾 \r 喂给 stoull 失败、upload 首超误判——全是 HTTP 边界语义，cpp-httplib
// （third_party/cpp-httplib，MIT，单头文件 v0.67.0）有海量项目背书。对外行为保持：
// 错误文案口径、取消语义（send() 按字符串匹配「已取消」）、超时档位
// （register/prepare 30s、upload 120s）都不变。

// httplib 错误 → 用户可读文案（与手搓版口径一致；「已取消」是 send() 的匹配锚点，勿改）
std::string httpErrorText(httplib::Error e) {
    switch (e) {
        case httplib::Error::Connection:        return "无法解析地址或网络不可达";
        case httplib::Error::ConnectionTimeout: return "连接对端超时";
        case httplib::Error::Canceled:          return "已取消";
        case httplib::Error::Write:             return "发送中断";
        case httplib::Error::Read:
        case httplib::Error::Timeout:           return "等待响应超时";
        default:                                return "请求未完成";
    }
}

struct HttpResult {
    bool ok = false;       // 连接 / 收发层面是否走完（不含状态码判断）
    int status = 0;
    std::string body;
    std::string error;
};

// JSON 请求（register/prepare/cancel）：一次连接一次请求，超时三档统一
HttpResult httpJson(const std::string& host, uint16_t port, const std::string& path,
                    const std::string& body, int timeoutSec) {
    HttpResult out;
    httplib::Client cli(host, port);
    cli.set_connection_timeout(timeoutSec);
    cli.set_read_timeout(timeoutSec);
    cli.set_write_timeout(timeoutSec);
    const httplib::Result r = cli.Post(path, body, "application/json");
    if (!r) {
        out.error = httpErrorText(r.error());
        return out;
    }
    out.ok = true;
    out.status = r->status;
    out.body = r->body;
    return out;
}

// 流式上传（upload）：文件从 FILE* 边读边发，进度回调每块一次，返回 false 即中止。
// httplib 把「提供者返回 false」统一归为 Canceled，但「用户取消」「读文件失败」
// 「连接断了」对调用方是三回事——取消要通知对端、读失败要如实说——所以分别记旗标。
HttpResult httpUpload(const std::string& host, uint16_t port, const std::string& path,
                      std::FILE* fp, uint64_t streamLen, int timeoutSec,
                      const std::function<bool(uint64_t)>& onWrite) {
    HttpResult out;
    httplib::Client cli(host, port);
    cli.set_connection_timeout(timeoutSec);
    cli.set_read_timeout(timeoutSec);
    cli.set_write_timeout(timeoutSec);

    bool userCanceled = false, readFailed = false, sinkBroken = false;
    uint64_t sent = 0;
    auto provider = [&](size_t /*offset*/, size_t /*length*/, httplib::DataSink& sink) -> bool {
        char buf[64 * 1024];
        const size_t got = std::fread(buf, 1, sizeof buf, fp);
        if (got == 0) { readFailed = true; return false; }
        if (!sink.write(buf, got)) { sinkBroken = true; return false; }
        sent += got;
        if (onWrite && !onWrite(sent)) { userCanceled = true; return false; }
        return true;
    };
    const httplib::Result r = cli.Post(path, httplib::Headers{},
                                       static_cast<size_t>(streamLen), provider,
                                       "application/octet-stream");
    if (!r) {
        if (userCanceled) out.error = "已取消";
        else if (readFailed) out.error = "读取本地文件失败";
        else if (sinkBroken) out.error = "连接被对端关闭";
        else out.error = httpErrorText(r.error());
        return out;
    }
    out.ok = true;
    out.status = r->status;
    out.body = r->body;
    return out;
}

}  // namespace

// ============================================================================
// 纯函数
// ============================================================================

std::string LocalSendSender::guessFileType(const std::string& name) {
    // 与接收端 LocalSendReceiver::guessFileType 同表 —— 两个方向对同一文件必须报同一个类型，
    // 否则官方客户端的图标/分类会在两个方向上不一致。
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

std::string LocalSendSender::randomId() {
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

std::string LocalSendSender::buildPrepareBody(const SenderIdentity& self, const std::string& fingerprint,
                                              const std::vector<std::string>& ids,
                                              const std::vector<OutgoingFile>& files,
                                              const std::vector<std::string>& sha256s,
                                              const std::vector<uint64_t>& sizes) {
    apxpc::net::Json root = apxpc::net::Json::makeObject();
    apxpc::net::Json info = apxpc::net::Json::makeObject();
    const std::string alias = self.alias.empty() ? hostNameImpl() : self.alias;
    info["alias"] = alias;
    info["version"] = std::string("2.2");
    info["deviceModel"] = self.deviceModel.empty() ? alias : self.deviceModel;
    info["deviceType"] = self.deviceType.empty() ? std::string("desktop") : self.deviceType;
    info["fingerprint"] = fingerprint;
    info["download"] = true;
    root["info"] = info;

    apxpc::net::Json fs = apxpc::net::Json::makeObject();
    const size_t n = std::min({ids.size(), files.size(), sha256s.size(), sizes.size()});
    for (size_t i = 0; i < n; ++i) {
        apxpc::net::Json f = apxpc::net::Json::makeObject();
        const std::string name = files[i].name.empty()
                                     ? std::filesystem::path(files[i].path).filename().string()
                                     : files[i].name;
        f["fileName"] = name;
        // size 用 double 承载：Json 内部只有 double（协议里的文件大小远小于 2^53，精度无损）
        f["size"] = static_cast<double>(sizes[i]);
        f["sha256"] = sha256s[i];
        f["fileType"] = guessFileType(name);
        fs[ids[i]] = f;
    }
    root["files"] = fs;
    return root.stringify();
}

void LocalSendSender::parsePrepareResponse(const std::string& body, std::string* sessionId,
                                           std::vector<std::pair<std::string, std::string>>* tokens) {
    if (sessionId) sessionId->clear();
    if (tokens) tokens->clear();
    std::string err;
    const apxpc::net::Json root = apxpc::net::Json::parse(body, &err);
    if (!root.isObject()) return;
    if (sessionId) *sessionId = jsonStr(root, "sessionId", "");
    const apxpc::net::Json* files = root.find("files");
    if (!files || !files->isObject()) return;
    if (!tokens) return;
    for (const auto& kv : files->asObject()) {
        if (!kv.second.isString()) continue;
        tokens->emplace_back(kv.first, kv.second.asString());
    }
}

std::string LocalSendSender::parseRegisterAlias(const std::string& body) {
    std::string err;
    const apxpc::net::Json root = apxpc::net::Json::parse(body, &err);
    return root.isObject() ? jsonStr(root, "alias", "") : std::string();
}

// ============================================================================
// 发送
// ============================================================================

LocalSendSender::LocalSendSender() = default;
LocalSendSender::~LocalSendSender() = default;

SendResult LocalSendSender::send(const Target& target, const SenderIdentity& self,
                                 const std::vector<OutgoingFile>& files,
                                 const ProgressCallback& progress) {
    SendResult res;
    if (target.ip.empty() || target.port == 0) {
        res.error = "目标地址不合法";
        return res;
    }
    if (files.empty()) {
        res.error = "没有要发送的文件";
        return res;
    }

    const std::string alias = self.alias.empty() ? hostNameImpl() : self.alias;
    const std::string fp = self.fingerprint.empty() ? randomId() : self.fingerprint;

    // ① register：确认对端可达 + 拿它报的 alias
    apxpc::net::Json selfInfo = apxpc::net::Json::makeObject();
    selfInfo["alias"] = alias;
    selfInfo["version"] = std::string("2.2");
    selfInfo["deviceModel"] = self.deviceModel.empty() ? alias : self.deviceModel;
    selfInfo["deviceType"] = self.deviceType.empty() ? std::string("desktop") : self.deviceType;
    selfInfo["fingerprint"] = fp;
    selfInfo["download"] = true;
    const std::string regBody = selfInfo.stringify();

    // 10s 默认值在慢机（息屏首包 / 高负载）上过紧 —— register 超时误报在这里拦过一次
    const HttpResult regResp = httpJson(target.ip, target.port, kPathRegister, regBody, 30);
    if (!regResp.ok) {
        res.error = "连接对端失败：" + regResp.error;
        return res;
    }
    if (regResp.status < 200 || regResp.status > 299) {
        res.error = "对端拒绝登记（HTTP " + std::to_string(regResp.status) + "）";
        return res;
    }
    res.peerAlias = parseRegisterAlias(regResp.body);
    if (res.peerAlias.empty()) res.peerAlias = target.alias;

    // ② 每个文件先算 sha256 与大小：对端可能校验摘要，而 Content-Length 必须准 ——
    //    两件事都要求先把文件完整读一遍。这一步没有进度（大文件会有几秒静默），
    //    但比「先上传再被对端判摘要不符」便宜得多。
    std::vector<std::string> ids, shas;
    std::vector<uint64_t> sizes;
    ids.reserve(files.size());
    shas.reserve(files.size());
    sizes.reserve(files.size());
    uint64_t grandTotal = 0;
    for (const auto& f : files) {
        std::FILE* fp = std::fopen(f.path.c_str(), "rb");
        if (!fp) {
            res.error = "读取失败（文件不存在或没权限）：" + f.path;
            return res;
        }
        Sha256 sha;
        uint64_t total = 0;
        char buf[64 * 1024];
        while (true) {
            const size_t got = std::fread(buf, 1, sizeof buf, fp);
            if (got == 0) break;
            sha.update(buf, got);
            total += got;
        }
        const bool readErr = std::ferror(fp) != 0;
        std::fclose(fp);
        if (readErr) {
            res.error = "读取失败：" + f.path;
            return res;
        }
        ids.push_back(randomId());
        shas.push_back(sha.hex());
        sizes.push_back(total);
        grandTotal += total;
    }

    // ③ prepare-upload
    const std::string prepBody = buildPrepareBody(self, fp, ids, files, shas, sizes);
    // 同 register：对端可能弹「是否接收」等人工点，10s 必超 —— 放宽到 30s
    const HttpResult prepResp = httpJson(target.ip, target.port, kPathPrepare, prepBody, 30);
    if (!prepResp.ok) {
        res.error = "prepare 失败：" + prepResp.error;
        return res;
    }
    if (prepResp.status < 200 || prepResp.status > 299) {
        // 对端可能弹了「是否接收」被人拒了 —— 说清楚是它拒的，不是我们没发
        res.error = "对端拒绝了本次发送（HTTP " + std::to_string(prepResp.status) + "）";
        return res;
    }
    std::string sessionId;
    std::vector<std::pair<std::string, std::string>> tokens;
    parsePrepareResponse(prepResp.body, &sessionId, &tokens);
    if (sessionId.empty()) {
        res.error = "对端未返回 sessionId";
        return res;
    }
    std::map<std::string, std::string> tokenBy;
    for (auto& kv : tokens) tokenBy[kv.first] = kv.second;

    // ④ 逐文件 upload
    uint64_t totalSent = 0;
    for (size_t i = 0; i < files.size(); ++i) {
        const OutgoingFile& f = files[i];
        const std::string name = f.name.empty() ? f.path : f.name;
        const auto tit = tokenBy.find(ids[i]);
        if (tit == tokenBy.end() || tit->second.empty()) {
            res.error = "对端拒绝了文件：" + name;
            return res;
        }
        std::FILE* fp = std::fopen(f.path.c_str(), "rb");
        if (!fp) {
            res.error = "读取失败：" + f.path;
            return res;
        }
        // 大文件慢链路：单次等待放宽，否则一慢就误判超时（对端收全后落盘回 204 的
        // 慢机宽限也在这 120s 读超时里，不再需要手搓版的首超+30s 二段逻辑）
        const HttpResult upResp = httpUpload(
            target.ip, target.port,
            std::string(kPathUpload) + "?sessionId=" + sessionId + "&fileId=" + tit->second,
            fp, sizes[i], 120, [&](uint64_t fileSent) -> bool {
                if (!progress) return true;
                SendProgress pr;
                pr.fileName = name;
                pr.fileBytes = fileSent;
                pr.fileTotal = sizes[i];
                pr.totalBytes = totalSent + fileSent;
                pr.grandTotal = grandTotal;
                pr.filesDone = static_cast<int>(i);
                pr.filesTotal = static_cast<int>(files.size());
                return progress(pr);
            });
        std::fclose(fp);

        if (!upResp.ok) {
            // 取消要额外通知对端：不然它会把半截文件当完整文件留着
            if (upResp.error == "已取消") {
                const HttpResult cr = httpJson(
                    target.ip, target.port,
                    std::string(kPathCancel) + "?sessionId=" + sessionId + "&fileId=" + tit->second,
                    "", 30);
                if (!cr.ok) APX_LOGW("通知对端取消失败：{}", cr.error);
                res.error = "已取消";
            } else {
                res.error = "上传中断（" + name + "）：" + upResp.error;
            }
            return res;
        }
        if (upResp.status < 200 || upResp.status > 299) {
            res.error = "上传失败（HTTP " + std::to_string(upResp.status) + "）：" + name;
            return res;
        }
        totalSent += sizes[i];
        res.deliveredNames.push_back(name);
        APX_LOGI("互传已发送 {} → {}（{} 字节）", name,
                 res.peerAlias.empty() ? target.ip : res.peerAlias,
                 static_cast<unsigned long long>(sizes[i]));
    }

    res.ok = true;
    res.bytesSent = totalSent;
    return res;
}

}  // namespace apxpc::localsend
