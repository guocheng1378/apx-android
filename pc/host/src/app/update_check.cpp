// update_check.cpp —— PC 端更新检测（与 Android UpdateChecker.kt 同一套比对规则）
// 零依赖：
//   Windows：WinHTTP（系统组件，webview2_runtime.cpp / apxsetup 已有先例）；
//   macOS  ：NSURLSession（系统组件，ObjC++ 实现见 update_check_mac.mm）。
// 国内可用性：直连 api.github.com 失败（超时/被拦）自动回退 gh-proxy.com 镜像（实测可通）。
#include "apxpc/app/update_check.hpp"
#include "apxpc/version.hpp"

#include <fstream>
#include <string>
#include <vector>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <winhttp.h>
#pragma comment(lib, "winhttp")
#endif

namespace apxpc::app {

#if defined(__APPLE__) && !defined(_WIN32)
// macOS HTTPS GET：ObjC++（NSURLSession）实现，定义在 update_check_mac.mm。
// 放在匿名命名空间**之外**（必须外部链接才能跨 TU 匹配）；CMake 在 APPLE 下把该 .mm
// 编入 apxpc_service 并开 -fobjc-arc。
unsigned httpsGetMac(const std::string& host, const std::string& path,
                     std::string& body, std::string& err);
#endif

namespace {

std::string stripPrefixV(std::string s) {
    if (!s.empty() && (s[0] == 'v' || s[0] == 'V')) s.erase(0, 1);
    return s;
}

// 按非数字切出数字段（与 UpdateChecker.kt 的 split(非数字) 语义一致）
std::vector<long long> versionSegments(const std::string& s) {
    std::vector<long long> out;
    const std::string t = stripPrefixV(s);
    size_t i = 0;
    while (i < t.size()) {
        if (t[i] >= '0' && t[i] <= '9') {
            long long v = 0;
            while (i < t.size() && t[i] >= '0' && t[i] <= '9') {
                v = v * 10 + (t[i] - '0');
                ++i;
            }
            out.push_back(v);
        } else {
            ++i;
        }
    }
    return out;
}

// 最小化提取 "tag_name": "..."（releases/latest 响应结构固定，不引 JSON 库）。
// 纯字符串处理，跨平台（此前误锁在 _WIN32 内，mac 也需要）。
bool extractTagName(const std::string& body, std::string& out) {
    const size_t k = body.find("\"tag_name\"");
    if (k == std::string::npos) return false;
    const size_t colon = body.find(':', k);
    if (colon == std::string::npos) return false;
    const size_t q1 = body.find('"', colon);
    if (q1 == std::string::npos) return false;
    const size_t q2 = body.find('"', q1 + 1);
    if (q2 == std::string::npos) return false;
    out = body.substr(q1 + 1, q2 - q1 - 1);
    return !out.empty();
}

// 从 assets 数组里挑 Windows 安装包：找第一条含 "-Setup.exe" 的 browser_download_url。
// 不拼 URL（资产名带 tag，拼写规则一旦变了就静默 404），认浏览器给的原始直下地址最稳。
// 一个 Release 同时有 Setup 与其他资产（apk/zip），逐条扫、命中即返回。
bool extractSetupUrl(const std::string& body, std::string& out) {
    size_t pos = 0;
    while ((pos = body.find("\"browser_download_url\"", pos)) != std::string::npos) {
        const size_t colon = body.find(':', pos);
        if (colon == std::string::npos) return false;
        const size_t q1 = body.find('"', colon);
        if (q1 == std::string::npos) return false;
        const size_t q2 = body.find('"', q1 + 1);
        if (q2 == std::string::npos) return false;
        const std::string url = body.substr(q1 + 1, q2 - q1 - 1);
        if (url.find("-Setup.exe") != std::string::npos) {
            out = url;
            return true;
        }
        pos = q2;   // 这条不是安装包，继续扫下一条
    }
    return false;
}

#ifdef _WIN32

std::wstring toWide(const std::string& s) {
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, w.data(), n);
    w.resize(static_cast<size_t>(n) - 1);
    return w;
}

// WinHTTP GET。返回 HTTP 状态码；0 = 连接层失败（err 写原因）。
// 注意：内部 status 必须是 DWORD —— WinHttpQueryHeaders 的 LPDWORD 参数是
// unsigned long*，换成 unsigned（unsigned int*）在 MSVC 下指针类型不匹配会编译失败。
unsigned httpsGet(const std::string& host, const std::string& path, std::string& body,
                  std::string& err) {
    HINTERNET ses = ::WinHttpOpen(L"apxhost-check-update/1.0",
                                  WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!ses) { err = "WinHttpOpen 失败"; return 0; }
    ::WinHttpSetTimeouts(ses, 5000, 5000, 5000, 8000);
    const std::wstring whost = toWide(host);
    HINTERNET con = whost.empty() ? nullptr
        : ::WinHttpConnect(ses, whost.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!con) { err = "DNS/连接失败"; ::WinHttpCloseHandle(ses); return 0; }
    const std::wstring wpath = toWide(path);
    HINTERNET req = wpath.empty() ? nullptr
        : ::WinHttpOpenRequest(con, L"GET", wpath.c_str(), nullptr,
                               WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                               WINHTTP_FLAG_SECURE);
    if (!req) { err = "创建请求失败"; ::WinHttpCloseHandle(con); ::WinHttpCloseHandle(ses); return 0; }
    DWORD status = 0;
    BOOL ok = ::WinHttpSendRequest(req, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                   WINHTTP_NO_REQUEST_DATA, 0, 0, 0);
    if (ok) ok = ::WinHttpReceiveResponse(req, nullptr);
    if (!ok) {
        err = "HTTP 请求失败（网络不通或被拦截）";
        ::WinHttpCloseHandle(req); ::WinHttpCloseHandle(con); ::WinHttpCloseHandle(ses);
        return 0;
    }
    DWORD len = sizeof(status);
    ::WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                          WINHTTP_HEADER_NAME_BY_INDEX, &status, &len,
                          WINHTTP_NO_HEADER_INDEX);
    DWORD avail = 0;
    while (::WinHttpQueryDataAvailable(req, &avail) && avail > 0 && body.size() < 1048576) {
        char buf[4096];
        DWORD got = 0;
        if (!::WinHttpReadData(req, buf, sizeof(buf), &got) || got == 0) break;
        body.append(buf, got);
    }
    ::WinHttpCloseHandle(req); ::WinHttpCloseHandle(con); ::WinHttpCloseHandle(ses);
    return static_cast<unsigned>(status);
}

#elif defined(__APPLE__)

// macOS：转发到 update_check_mac.mm 的 NSURLSession 实现（签名保持一致）。
inline unsigned httpsGet(const std::string& host, const std::string& path,
                         std::string& body, std::string& err) {
    return httpsGetMac(host, path, body, err);
}

#else

// 其他平台：无系统 HTTPS 栈（零依赖约束下不引 TLS 库），连接层直接失败。
inline unsigned httpsGet(const std::string& host, const std::string& path,
                         std::string& body, std::string& err) {
    (void)host; (void)path; (void)body;
    err = "当前平台暂未实现更新检测";
    return 0;
}

#endif  // 平台 HTTP 分支

}  // namespace

int compareVersion(const std::string& a, const std::string& b) {
    const auto sa = versionSegments(a);
    const auto sb = versionSegments(b);
    const size_t n = sa.size() > sb.size() ? sa.size() : sb.size();
    for (size_t i = 0; i < n; ++i) {
        const long long va = i < sa.size() ? sa[i] : 0;
        const long long vb = i < sb.size() ? sb[i] : 0;
        if (va != vb) return va < vb ? -1 : 1;
    }
    return 0;
}

UpdateCheckResult checkForUpdate() {
    UpdateCheckResult r;
#if defined(_WIN32) || defined(__APPLE__)
    const char* kApiPath = "/repos/guocheng1378/apx-android/releases/latest";
    std::string body, err;
    unsigned status = httpsGet("api.github.com", kApiPath, body, err);
    // 直连不通（GFW 常见超时/重置，或 403/429 等非预期状态）→ gh-proxy 国内镜像复查一次。
    // 404 视为真实「无已发布 Release」，不再走镜像（避免把正常 404 当网络问题重试）。
    if (status != 200 && status != 404) {
        std::string body2, err2;
        const std::string path2 = std::string("/https://api.github.com") + kApiPath;
        const unsigned status2 = httpsGet("gh-proxy.com", path2, body2, err2);
        if (status2 == 200 || status2 == 404) {
            status = status2;
            body = std::move(body2);
        } else {
            r.error = "直连 GitHub 失败（" + err + "），镜像也失败（" + err2 + "）";
            return r;
        }
    }
    if (status == 404) {
        r.error = "GitHub 无已发布 Release（draft/prerelease 不参与检查更新）";
        return r;
    }
    if (status != 200) {
        r.error = "GitHub API 返回状态 " + std::to_string(status);
        return r;
    }
    std::string tag;
    if (!extractTagName(body, tag)) { r.error = "响应中未找到 tag_name"; return r; }
    r.ok = true;
    r.latestTag = tag;
    r.hasNewer = compareVersion(tag, APXPC_VERSION_STRING) > 0;
    extractSetupUrl(body, r.setupUrl);   // 找不到安装包资产时留空，调用方降级为跳转下载页
    return r;
#else
    r.error = "当前平台暂未实现更新检测";
    return r;
#endif
}

// ———————— 一键升级（2026-11-14，v223.29.103+）：下载 + 静默安装 ————————

#if defined(_WIN32)

// WinHTTP 下载到文件。与 httpsGet 的差异：无 1MB 上限（安装包几十 MB）、
// 边收边写盘（不整包进内存）、读 Content-Length 算百分比（拿不到回调 -1）。
// 注意：内部 status 必须是 DWORD —— 同 httpsGet 的注释（WinHttpQueryHeaders
// 的 LPDWORD 参数是 unsigned long*，换成 unsigned 会编译失败）。
unsigned httpsDownloadToFile(const std::string& url, const std::string& destFile,
                             std::string& err, const std::function<void(int)>& progress) {
    // 拆 scheme://host/path（gh-proxy 镜像回退要整条 URL 原样换前缀，所以收全 URL）
    std::string rest = url;
    const std::string scheme = "https://";
    if (rest.rfind(scheme, 0) == 0) rest = rest.substr(scheme.size());
    const size_t slash = rest.find('/');
    const std::string host = slash == std::string::npos ? rest : rest.substr(0, slash);
    const std::string path = slash == std::string::npos ? "/" : rest.substr(slash);

    HINTERNET ses = ::WinHttpOpen(L"apxhost-check-update/1.0",
                                  WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!ses) { err = "WinHttpOpen 失败"; return 0; }
    // 下载几十 MB，读超时放宽到 30s（5s 对大文件偶发够呛，与检查接口分开调）
    ::WinHttpSetTimeouts(ses, 5000, 5000, 5000, 30000);
    const std::wstring whost = toWide(host);
    HINTERNET con = whost.empty() ? nullptr
        : ::WinHttpConnect(ses, whost.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!con) { err = "DNS/连接失败"; ::WinHttpCloseHandle(ses); return 0; }
    const std::wstring wpath = toWide(path);
    HINTERNET req = wpath.empty() ? nullptr
        : ::WinHttpOpenRequest(con, L"GET", wpath.c_str(), nullptr,
                               WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                               WINHTTP_FLAG_SECURE);
    if (!req) { err = "创建请求失败"; ::WinHttpCloseHandle(con); ::WinHttpCloseHandle(ses); return 0; }
    DWORD status = 0;
    BOOL ok = ::WinHttpSendRequest(req, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                   WINHTTP_NO_REQUEST_DATA, 0, 0, 0);
    if (ok) ok = ::WinHttpReceiveResponse(req, nullptr);
    if (!ok) {
        err = "HTTP 请求失败（网络不通或被拦截）";
        ::WinHttpCloseHandle(req); ::WinHttpCloseHandle(con); ::WinHttpCloseHandle(ses);
        return 0;
    }
    DWORD len = sizeof(status);
    ::WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                          WINHTTP_HEADER_NAME_BY_INDEX, &status, &len,
                          WINHTTP_NO_HEADER_INDEX);
    if (status == 200) {
        std::ofstream f(destFile, std::ios::binary | std::ios::trunc);
        if (!f) {
            err = "本地文件创建失败：" + destFile;
            ::WinHttpCloseHandle(req); ::WinHttpCloseHandle(con); ::WinHttpCloseHandle(ses);
            return 0;
        }
        // 总量从 Content-Length 头来；拿不到就传 -1（前端显示"下载中…"）
        DWORD total = 0;
        DWORD tlen = sizeof(total);
        const bool hasTotal = ::WinHttpQueryHeaders(req,
            WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
            WINHTTP_HEADER_NAME_BY_INDEX, &total, &tlen, WINHTTP_NO_HEADER_INDEX)
            && total > 0;
        long long received = 0;
        bool unknownSignaled = false;
        DWORD avail = 0;
        while (::WinHttpQueryDataAvailable(req, &avail) && avail > 0) {
            char buf[65536];
            DWORD got = 0;
            if (!::WinHttpReadData(req, buf, sizeof(buf), &got) || got == 0) break;
            f.write(buf, static_cast<std::streamsize>(got));
            received += got;
            if (!hasTotal) {
                if (!unknownSignaled && progress) { progress(-1); unknownSignaled = true; }
            } else if (progress) {
                progress(static_cast<int>(received * 100 / static_cast<long long>(total)));
            }
        }
        f.close();
        if (progress && hasTotal) progress(100);
    }
    ::WinHttpCloseHandle(req); ::WinHttpCloseHandle(con); ::WinHttpCloseHandle(ses);
    return static_cast<unsigned>(status);
}

#endif  // _WIN32

bool downloadSetupInstaller(const std::string& setupUrl, const std::string& destDir,
                            std::string& outPath, std::string& err,
                            const std::function<void(int percent)>& progress) {
#if defined(_WIN32)
    if (setupUrl.empty()) { err = "未找到安装包下载地址"; return false; }
    // 落盘文件名取 URL 最后一段（…/download/<tag>/AllPeriph-<tag>-Setup.exe）
    const size_t slash = setupUrl.find_last_of('/');
    const std::string fname =
        (slash == std::string::npos || slash + 1 >= setupUrl.size()) ? std::string() : setupUrl.substr(slash + 1);
    if (fname.empty()) { err = "安装包地址不完整"; return false; }
    const std::string dir = destDir.empty() ? "." : destDir;
    const std::string finalPath = dir + (dir.back() == '\\' || dir.back() == '/' ? "" : "\\") + fname;
    const std::string partPath = finalPath + ".part";
    // 先直连 GitHub，失败回退 gh-proxy 镜像（与 checkForUpdate 同序同语义）
    unsigned status = httpsDownloadToFile(setupUrl, partPath, err, progress);
    if (status != 200) {
        std::string err2;
        const unsigned status2 =
            httpsDownloadToFile("https://gh-proxy.com/" + setupUrl, partPath, err2, progress);
        if (status2 == 200) {
            status = status2;
            err.clear();
        } else {
            err = "直连下载失败（" + err + "），镜像也失败（" + err2 + "）";
            ::DeleteFileA(partPath.c_str());   // 半包不留：.part 留着会被误当下载过
            return false;
        }
    }
    // 下载完才改名：.part 期间被点「立即安装」也拿不到完整包
    if (::MoveFileExA(partPath.c_str(), finalPath.c_str(), MOVEFILE_REPLACE_EXISTING) == 0) {
        err = "安装包重命名失败（" + finalPath + "）";
        ::DeleteFileA(partPath.c_str());
        return false;
    }
    outPath = finalPath;
    return true;
#else
    (void)setupUrl; (void)destDir; (void)outPath; (void)progress;
    err = "macOS 暂不支持一键下载安装，请到 Release 页手动下载";
    return false;
#endif
}

bool runSetupInstallerSilently(const std::string& installerPath, std::string& err) {
#if defined(_WIN32)
    // Inno 静默参数：VERYSILENT 不弹向导；SUPPRESSMSGBOXES 免确认框；
    // CLOSEAPPLICATIONS 让安装器关掉正在跑的旧版本进程；
    // RESTARTAPPLICATIONS 在装完后把新版拉回来 —— 升级全程只点一次。
    const std::string cmd = "\"" + installerPath
        + "\" /VERYSILENT /SUPPRESSMSGBOXES /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS";
    // CreateProcess 的 lpCommandLine 约定要求可写缓冲区（进程名会写回），不能直接传 .c_str()
    std::vector<char> cmdBuf(cmd.begin(), cmd.end());
    cmdBuf.push_back('\0');
    STARTUPINFOA si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    if (!::CreateProcessA(nullptr, cmdBuf.data(), nullptr, nullptr, FALSE, 0,
                          nullptr, nullptr, &si, &pi)) {
        err = "启动安装程序失败（错误 " + std::to_string(::GetLastError()) + "）";
        return false;
    }
    // 分离进程不等待：装多久是安装器的事，本进程随后走退出路径给它让路
    ::CloseHandle(pi.hThread);
    ::CloseHandle(pi.hProcess);
    return true;
#else
    (void)installerPath;
    err = "macOS 暂不支持一键安装，请到 Release 页下载";
    return false;
#endif
}

}  // namespace apxpc::app
