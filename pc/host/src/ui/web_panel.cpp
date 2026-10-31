// WebView2 桌面控制面板实现 —— UI 层全部交给 React + Tailwind（pc/web/）。
//
// 分工：
//   ① CreateCoreWebView2Environment → ICoreWebView2 → Navigate(dist/index.html)
//   ② 400ms WM_TIMER → 构建状态 JSON → PostWebMessageAsJson → 前端渲染
//   ③ 前端 postMessage → parse JSON → act/query/open-file 分发 → 宿主对象 → 回传
//
// 所有 COM 指针用 wil::com_ptr 管理（自动 Release），避免手动 AddRef/Release 漏写。
// 构建期：CMake FetchContent 拉 Microsoft.Web.WebView2 nuget 包，WebView2Loader.dll
// 在 build 目录，post-build 拷贝到 apxdesktop.exe 同目录。
//
// 崩溃取证、防火墙放行、9511 受控端注册 仍由 desktop_main.cpp 负责 —— 本文件不越界。

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <objbase.h>
#include <windowsx.h>
#include <commdlg.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>        // CLSID_FileOpenDialog（log.pickDir 的原生目录选择框）
#include <shobjidl_core.h>   // IFileOpenDialog / SIGDN_FILESYSPATH
#endif

#include "apxpc/ui/web_panel.hpp"
#include "apxpc/ui/webview2_runtime.hpp"
#include "apxpc/log.hpp"
#include "apxpc/net/json.hpp"
#include "apxpc/wireless/wireless_session.hpp"
#include "apxpc/wireless/file_receiver.hpp"
#include "apxpc/wireless/file_sender.hpp"
// 9511 受控端状态回填（phone 0x31 解码 / inputTrusted / ctrlServer）—— 对齐 panel_http.cpp。
#include "apxpc/wireless/ctrl9511.hpp"
#include "apxpc/wireless/phone_mode.hpp"
#include "apxpc/localsend/local_send_receiver.hpp"
// 日志清单与按时间范围导出（issue #52 第 3 条）——与 panel_http.cpp 共用同一套实现。
#include "apxpc/log_export.hpp"
#include "apxpc/localsend/local_send_sender.hpp"
#include "apxpc/media/media_session.hpp"
#include "apxpc/media/screen_push.hpp"
#include "apxpc/media/audio_capture.hpp"
#include "apxpc/media/mic_bridge.hpp"
#include "apxpc/autostart.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/discovery/discovery.hpp"
#include "apxpc/discovery/enumerator.hpp"

#include <wil/com.h>
#include <WebView2.h>

#include <atomic>
#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace apxpc::ui {
namespace {

using apxpc::net::Json;
using apxpc::wireless::LinkPhase;
using apxpc::wireless::SessionSnapshot;

// ———————————————— COM 回调适配器（WebView2 SDK 没有 Callback<T> 模板） ————————————————
// 每个 WebView2 回调接口的 Invoke 签名不同，需要各自实现。
// 下面 3 个类共享 IUnknown 样板（宏展开），仅 Invoke 签名不同。
#define COM_CALLBACK_BASE(IFACE, UUID) \
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&m_refs); } \
    ULONG STDMETHODCALLTYPE Release() override { ULONG r = InterlockedDecrement(&m_refs); if (r == 0) delete this; return r; } \
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override { \
        if (riid == __uuidof(IFACE) || riid == IID_IUnknown) { *ppv = static_cast<IFACE*>(this); AddRef(); return S_OK; } \
        return E_NOINTERFACE; \
    }

template <typename Fn>
class EnvCompletedHandler : public ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler {
public:
    explicit EnvCompletedHandler(Fn fn) : m_fn(std::move(fn)) {}
    COM_CALLBACK_BASE(ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler, 0)
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT hr, ICoreWebView2Environment* env) override { return m_fn(hr, env); }
protected: Fn m_fn; long m_refs{1};
};

template <typename Fn>
class CtrlCompletedHandler : public ICoreWebView2CreateCoreWebView2ControllerCompletedHandler {
public:
    explicit CtrlCompletedHandler(Fn fn) : m_fn(std::move(fn)) {}
    COM_CALLBACK_BASE(ICoreWebView2CreateCoreWebView2ControllerCompletedHandler, 0)
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT hr, ICoreWebView2Controller* ctrl) override { return m_fn(hr, ctrl); }
protected: Fn m_fn; long m_refs{1};
};

template <typename Fn>
class WebMsgReceivedHandler : public ICoreWebView2WebMessageReceivedEventHandler {
public:
    explicit WebMsgReceivedHandler(Fn fn) : m_fn(std::move(fn)) {}
    COM_CALLBACK_BASE(ICoreWebView2WebMessageReceivedEventHandler, 0)
    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2* sender, ICoreWebView2WebMessageReceivedEventArgs* args) override { return m_fn(sender, args); }
protected: Fn m_fn; long m_refs{1};
};

// 工厂：创建各类型 handler（注意返回类型是对应的 IFACE*，COM 会接管 AddRef/Release）
template <typename Fn> ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler* mkEnvCb(Fn fn) { return new EnvCompletedHandler<Fn>(std::move(fn)); }
template <typename Fn> ICoreWebView2CreateCoreWebView2ControllerCompletedHandler*    mkCtrlCb(Fn fn) { return new CtrlCompletedHandler<Fn>(std::move(fn)); }
template <typename Fn> ICoreWebView2WebMessageReceivedEventHandler*                 mkMsgCb(Fn fn) { return new WebMsgReceivedHandler<Fn>(std::move(fn)); }

constexpr UINT_PTR kStateTimer = 1;     // 状态推流定时器（400ms）
constexpr UINT kMsgPostQuitCustom = WM_APP + 10;
constexpr UINT kMsgWv2Ready = WM_APP + 11;   // WebView2 自动安装完成 → 重试创建环境
constexpr UINT kMsgWv2Failed = WM_APP + 12;  // WebView2 自动安装失败（后台线程只持 HWND）→ 真退出

LPCWSTR kClassName = L"AllPeriphWebPanel";
LPCWSTR kWindowTitle = L"全能外设";

struct Panel {
    HWND hwnd = nullptr;
    HostBundle bundle;
    wil::com_ptr<ICoreWebView2Environment> env;
    wil::com_ptr<ICoreWebView2Controller> controller;
    wil::com_ptr<ICoreWebView2> webview;
    std::atomic<bool> running{false};
    std::wstring lastRepaintKey;    // 状态指纹：没变就不推
    unsigned forceTick = 0;         // tick 计数：定期强制重推（覆盖前端监听器晚挂）

    // USB 传输探测缓存（SetupAPI 全量枚举较重，节流 ~10s 一次）
    bool usbScanned = false;
    bool usbDevice = false, usbNcm = false, usbHid = false;
    std::string usbName, usbSpeed;
    uint64_t usbNextMs = 0;

    // 媒体建链泵（对齐老面板 pumpMediaConnect）：控制连接就绪后，后台节流
    // 发起媒体连接（手机 9502）。副屏/音箱/麦克风共用这条通道——它不通，
    // 三张功能卡全停在「等待媒体连接」、开关 disabled 点不开。
    std::thread mediaThread;
    std::atomic<bool> mediaBusy{false};
    uint64_t mediaNextMs = 0;

    // 互传发送线程（PC → 对端）：一次一批，结果走 on-localsend-sent 事件
    std::thread sendThread;
    std::atomic<bool> sendBusy{false};
};
Panel* g_panel = nullptr;

// ———————————————— 工具：JSON 辅助 ————————————————
Json okResp(const Json& data = {}) {
    Json r = Json::makeObject(); r["ok"] = true; r["data"] = data; return r;
}
Json failResp(const std::string& msg, int code = 1) {
    Json r = Json::makeObject(); r["ok"] = false; r["code"] = code; r["message"] = msg; return r;
}

std::string phaseName(LinkPhase p) {
    switch (p) {
        case LinkPhase::Connected:   return "connected";
        case LinkPhase::Connecting:  return "connecting";
        case LinkPhase::Discovering: return "discovering";
        case LinkPhase::Failed:      return "failed";
        default:                     return "idle";
    }
}

// ———————————————— USB 传输探测（gadget 挂载诊断） ————————————————
// 全量枚举要打开父集线器读配置描述符（几十 ms 级），不能进 400ms tick，
// 缓存结果 ~10s 刷一次。三态：未检测到设备（线没插/手机侧没起来）→
// 设备在线但 NCM 未挂载（手机端 USB 传输开关没生效）→ NCM 已挂载（可用）。
void refreshUsbStatus(Panel* p) {
    p->usbDevice = p->usbNcm = p->usbHid = false;
    p->usbName.clear();
    p->usbSpeed.clear();
    try {
        for (const auto& d : usb::enumerateDevices()) {
            if (!p->usbDevice) {
                p->usbDevice = true;
                p->usbName = d.displayName.empty() ? d.vidPid() : d.displayName;
                p->usbSpeed = discovery::speedName(d.speed);
            }
            if (d.has(usb::Role::Ncm)) p->usbNcm = true;
            if (d.has(usb::Role::Hid)) p->usbHid = true;
        }
    } catch (...) {
        APX_LOGW("USB 枚举失败，按未检测到设备呈现");
    }
}

// ———————————————— 状态构建（每 400ms 一次） ————————————————
void buildState(const HostBundle& b, Json& out) {
    out = Json::makeObject();

    // link
    Json link = Json::makeObject();
    Json media = Json::makeObject();
    Json transports = Json::makeObject();
    Json counts = Json::makeObject();
    Json cfg = Json::makeObject();

    if (b.session) {
        const auto s = b.session->snapshot();
        // ★ 数字而非字符串：前端 phaseSentence 用 `case 3:` 数字严格比较，
        //   曾写成 std::to_string → "3" !== 3 永远落 default「尚未连接」，
        //   控制链路明明已连上（RTT/时长都显示），句子却一直说尚未连接。
        link["phase"] = static_cast<double>(s.phase);
        link["phaseName"] = phaseName(s.phase);
        link["peer"] = s.peer;
        link["rttMs"] = s.rttMs;
        link["upMs"] = static_cast<double>(s.upMs);
        link["error"] = s.error;
        link["autoMode"] = s.autoMode;
        const auto& c = s.counters;
        counts["mouse"]    = static_cast<double>(c.mouse);
        counts["keyboard"] = static_cast<double>(c.keyboard);
        counts["consumer"] = static_cast<double>(c.consumer);
        counts["dropped"]  = static_cast<double>(c.dropped);
    } else {
        link["phase"] = static_cast<int>(LinkPhase::Idle);
        link["phaseName"] = L"idle";
    }

    if (b.media) {
        const auto st = b.media->status();
        const auto cc = b.media->counters();
        media["connected"] = st.connected;
        media["peer"] = st.peer;
        media["error"] = st.error;
        media["videoFramesSent"] = static_cast<double>(cc.videoFrames);
        media["audioFramesSent"] = static_cast<double>(cc.audioFrames);
        media["micFrames"] = static_cast<double>(cc.micFrames);
        media["dropped"] = static_cast<double>(cc.dropped);
    }

    if (b.screenPush) {
        auto s = b.screenPush->status();
        Json v = Json::makeObject();
        v["running"] = s.running;
        v["fps"] = s.fps;
        v["deviceName"] = s.deviceName;
        v["width"] = static_cast<double>(s.width);
        v["height"] = static_cast<double>(s.height);
        v["framesSent"] = static_cast<double>(s.framesSent);
        v["encodeMs"] = s.encodeMs;
        v["dropped"] = static_cast<double>(s.framesDropped);
        v["error"] = s.error;
        media["screen"] = v;
    }

    if (b.audio) {
        auto s = b.audio->status();
        Json a = Json::makeObject();
        a["running"] = s.running;
        a["peak"] = s.peak;
        a["device"] = s.device;
        a["sampleRate"] = static_cast<double>(s.sampleRate);
        a["channels"] = static_cast<double>(s.channels);
        a["framesSent"] = static_cast<double>(s.framesSent);
        a["error"] = s.error;
        media["audio"] = a;
    }

    if (b.micBridge) {
        auto s = b.micBridge->status();
        Json m = Json::makeObject();
        m["running"] = s.running;
        m["device"] = s.device;
        media["mic"] = m;
    }

    // 配置（传给前端，允许改动后持久化）
    if (b.cfg) {
        cfg["autostart"] = b.cfg->autostart;
        cfg["mode"] = b.cfg->mode;
        cfg["logLevel"] = b.cfg->logLevel;
        cfg["adaptiveBitrate"] = b.cfg->displayAdaptive;
        cfg["bitrateMbps"] = static_cast<double>(b.cfg->displayBitrateMbps);
        cfg["screenMode"] = static_cast<double>(b.cfg->displayEnabled ? 1 : 0);
        transports["btSupported"] = true;
    }

    // 9511 受控端状态（对齐 panel_http.cpp buildState 逐字段）：phone（0x31 解码）/
    // inputTrusted / ctrlServer。以前不回填 —— Win 壳的连接卡三行模式显示永远「未上报」，
    // 即使手机实际连着并报了 0x31（前端 ConnectionCard 依赖这些字段）。
    if (b.ctrlSrv) {
        const auto cs = b.ctrlSrv->status();
        const auto pm = apxpc::wireless::decodePhoneMode(cs.modeFlags);
        Json ph = Json::makeObject();
        ph["reported"] = cs.modeReported;
        ph["connected"] = cs.connected;
        ph["frames"] = static_cast<double>(cs.modeStatus);
        ph["wirelessAvailable"] = pm.wirelessAvailable;
        ph["bluetoothAvailable"] = pm.bluetoothAvailable;
        ph["usbAvailable"] = pm.usbAvailable;
        ph["active"] = pm.active;
        transports["phone"] = ph;
        // 注入权限（macOS 的「辅助功能」）：Win 上 platformInputTrusted() 恒 true，
        // 横幅永不显示 —— 字段照样给，前端不用分平台判断。
        transports["inputTrusted"] = cs.inputTrusted;
        Json csj = Json::makeObject();
        csj["listening"] = cs.listening;
        csj["shared"] = false;   // Win 侧 9511 由本进程创建，不存在另一实例提供的场景
        csj["connected"] = cs.connected;
        csj["error"] = cs.error;
        transports["ctrlServer"] = csj;
    }

    // USB 传输状态（设置页「传输状态」区块消费）
    if (Panel* gp = g_panel; gp && gp->usbScanned) {
        transports["usbDevice"] = gp->usbDevice;
        transports["usbNcm"] = gp->usbNcm;
        transports["usbHid"] = gp->usbHid;
        transports["usbName"] = gp->usbName;
        transports["usbSpeed"] = gp->usbSpeed;
    }

    // 托盘状态（created/active/失败原因）：对齐 panel_http.cpp —— 创建失败以前只有日志
    // 一行 LOGW，用户看面板只会觉得「没图标、连退出入口都没有」。回传后失败原因直接可见。
    if (b.tray) {
        const auto t = b.tray->status();
        Json tj = Json::makeObject();
        tj["created"] = t.created;
        tj["active"] = t.active;
        tj["reason"] = t.reason;
        out["tray"] = tj;
    }

    // 互传（LocalSend）：字段口径由 apxpc::localsend::statusJson 统一给出（单测钉住的那份），
    // 两个面板不各写一遍，免得字段名漂移弄坏前端。
    if (b.localSend) {
        std::string lerr;
        out["localsend"] = Json::parse(apxpc::localsend::statusJson(b.localSend->snapshot()), &lerr);
    } else {
        // 未接线时也给完整「已关闭」形态：前端按类型读，缺字段会渲染成 undefined
        Json ls = Json::makeObject();
        ls["running"] = false;
        ls["port"] = 0.0;
        ls["dir"] = std::string("");
        ls["received"] = 0.0;
        ls["error"] = std::string("互传接收端未初始化");
        ls["warn"] = std::string("");
        ls["devices"] = Json::makeArray();
        out["localsend"] = ls;
    }

    // 宿主平台（与 panel_http.cpp 同口径）：Windows 是唯一能枚举真实播放端点的平台，
    // 前端据此决定「设备下拉」还是「说明态」，别再让空列表被误读成功能坏了。
    out["platform"] = std::string("windows");

    out["link"] = link;
    out["media"] = media;
    out["transports"] = transports;
    out["counts"] = counts;
    out["config"] = cfg;
}

// ———————————————— UTF-8 ↔ UTF-16 转换（Win32 原生 API） ————————————————
// PostWebMessageAsJson 要求 UTF-16。std::wstring(s.begin(), s.end()) 是逐字节
// 拷贝，多字节 UTF-8（中文）会变成 mojibake；必须走 MultiByteToWideChar。
static std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<size_t>(n), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}
static std::string wideToUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = ::WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    ::WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

// ———————————————— 把状态推给前端（400ms tick） ————————————————
void tickState(Panel* p) {
    // USB 探测节流：首拍立即做，此后 ~10s 一次（状态变化会让指纹变化 → 自动推送）
    const uint64_t nowMs = ::GetTickCount64();
    if (nowMs >= p->usbNextMs) {
        refreshUsbStatus(p);
        p->usbNextMs = nowMs + 10000;
    }
    // 媒体建链泵：控制连接已就绪而媒体未连时，后台节流重试连手机 9502
    //（副屏/音箱/麦克风共用；建链是 3s 级阻塞操作，绝不能在 UI 线程直接调）。
    // 新面板此前缺失这段 —— 老面板 panel_win32.cpp 的 tick 里一直有 pumpMediaConnect。
    {
        const HostBundle& b = p->bundle;
        if (b.media && b.session && !b.media->status().connected) {
            const auto snap = b.session->snapshot();
            const std::string peer = snap.peer;                 // host:port（已连上时）
            const size_t colon = peer.rfind(':');
            const std::string host = (colon == std::string::npos) ? peer : peer.substr(0, colon);
            if (snap.phase == LinkPhase::Connected && !host.empty() &&
                !p->mediaBusy.load() && nowMs >= p->mediaNextMs) {
                p->mediaNextMs = nowMs + 4000;                  // 失败 4s 后再试
                if (p->mediaThread.joinable()) p->mediaThread.join();
                p->mediaBusy.store(true);
                apxpc::media::MediaSession* media = b.media;    // 宿主栈对象，生命周期覆盖面板
                p->mediaThread = std::thread([p, media, host] {
                    if (media->connect(host, apxpc::media::kMediaPort)) {
                        APX_LOGI("媒体通道已连接 {}:{}", host,
                                 static_cast<unsigned>(apxpc::media::kMediaPort));
                    }
                    p->mediaBusy.store(false);
                });
            }
        }
    }
    Json state;
    buildState(p->bundle, state);
    std::string json = state.stringify();
    // 指纹：没变就不推（避免无谓渲染）。
    // ★ 例外：每 25 tick（约 10s）强制重推一次。前端 React 挂载晚于首次推送时，
    //   消息会在监听器挂上之前被丢弃；若状态此后恰无变化，前端将永远停在
    //   「等待宿主状态…」。首次 tick 必推，此后周期性兜底重推。
    const bool force = (++p->forceTick % 25) == 1;
    std::wstring key;
    key.reserve(json.size());
    for (char c : json) key.push_back(static_cast<wchar_t>(static_cast<unsigned char>(c)));
    if (!force && key == p->lastRepaintKey) return;
    p->lastRepaintKey = key;

    Json push = Json::makeObject();
    push["kind"] = std::string("state");
    push["t"] = static_cast<double>(GetTickCount64());
    push["data"] = state;
    std::string s = push.stringify();
    p->webview->PostWebMessageAsJson(utf8ToWide(s).c_str());
}

// ———————————————— 回传前端请求的响应 ————————————————
// ★ kind 字段必须带上：前端 bridge.ts 只认 msg.kind === 'act-response' /
//   'query-response' / 'open-file-response' 的消息。此前 handleAct 全部响应
//   （okResp/failResp）都没设 kind —— 前端永远匹配不上，act 请求只能等 10s
//   超时 reject，且卡片层 await 没 catch：表现为「开关点了没任何反应」。
//   （handleQuery/handleOpenFile 的作者记得设 kind，恰好漏了 handleAct 一族。）
void postResponse(Panel* p, const std::string& id, Json resp, const char* kind = "act-response") {
    if (!resp.find("kind")) resp["kind"] = std::string(kind);
    resp["id"] = id;
    std::string s = resp.stringify();
    p->webview->PostWebMessageAsJson(utf8ToWide(s).c_str());
    APX_LOGI("postResponse: kind={} id={} len={}", kind, id.c_str(), s.size());
}

// ———————————————— 推送事件给前端（非请求-响应型，如对端请求输入 / 收到文件）——
// WebView2 的 PostWebMessageAsJson 线程安全 —— 9511 reader 线程 / FileReceiver 线程
// 直接调即可，不需要 Post 回 UI 线程。
void postEvent(Panel* p, const std::string& name, Json data = Json::makeObject()) {
    Json push = Json::makeObject();
    push["kind"] = std::string("event");
    push["name"] = name;
    push["data"] = data;
    std::string s = push.stringify();
    p->webview->PostWebMessageAsJson(utf8ToWide(s).c_str());
}

// ———————————————— 文件发送（PC → 手机，TCP 9512） ————————————————
// 对端地址取会话快照（与媒体建链泵同口径：peer = "host:port"，只取 host）。
std::string peerHost(Panel* p) {
    if (!p || !p->bundle.session) return {};
    const auto s = p->bundle.session->snapshot();
    if (s.phase != LinkPhase::Connected) return {};
    const size_t colon = s.peer.rfind(':');
    return (colon == std::string::npos) ? s.peer : s.peer.substr(0, colon);
}

// 文件发送线程：退出前必须 join —— 线程持 Panel*（栈对象）指针，不 join 会悬空。
std::mutex g_sendMu;
std::vector<std::thread> g_sendThreads;

// 异步发文件：绝不能在 UI 线程 / 托盘线程里同步 sendFile（大文件会卡死消息循环，
// 老面板 file_panel_win32 也是开 std::thread 发）。respId 非空 → 完成后回
// act-response（前端 file.send await 收尾）；为空 → 推 on-file-send-done 事件（托盘入口）。
void startFileSend(Panel* p, const std::string& path, const std::string& respId) {
    const std::string host = peerHost(p);
    if (host.empty()) {
        const std::string msg = "未连接手机（先建立控制连接再发送）";
        if (respId.empty()) {
            Json d = Json::makeObject(); d["ok"] = false; d["error"] = msg;
            postEvent(p, std::string("on-file-send-done"), d);
        } else {
            postResponse(p, respId, failResp(msg));
        }
        return;
    }
    std::thread th([p, path, respId, host] {
        int lastPct = -1;
        const bool ok = apxpc::wireless::sendFile(host, 9512, path,
            [p, path, &lastPct](int pct) {
                // 进度节流：每 10% 推一拍（postEvent 线程安全）
                if (pct >= 100 || pct - lastPct >= 10) {
                    lastPct = pct;
                    Json d = Json::makeObject();
                    d["path"] = path;
                    d["percent"] = pct;
                    postEvent(p, std::string("on-file-send-progress"), d);
                }
            });
        const std::string err = ok ? std::string() : apxpc::wireless::lastSendError();
        if (ok) {
            APX_LOGI("文件已发送到手机 {}: {}", host.c_str(), path.c_str());
        } else {
            APX_LOGW("发送文件失败: {}", err.c_str());
        }
        if (!respId.empty()) {
            Json d = Json::makeObject();
            d["ok"] = ok; d["path"] = path; d["peer"] = host;
            if (!ok) d["error"] = err.empty() ? std::string("发送文件失败") : err;
            postResponse(p, respId, ok ? okResp(d)
                                      : failResp(err.empty() ? "发送文件失败" : err));
        } else {
            Json d = Json::makeObject();
            d["ok"] = ok; d["path"] = path; d["peer"] = host;
            if (!ok) d["error"] = err.empty() ? std::string("发送文件失败") : err;
            postEvent(p, std::string("on-file-send-done"), d);
        }
    });
    std::lock_guard<std::mutex> lk(g_sendMu);
    g_sendThreads.push_back(std::move(th));
}

// ———————————————— 动作分发（前端 act 请求） ———————————————
// 互传（LocalSend 协议 v2.2）
// 起停与 panel_http.cpp 同口径：落盘目录固定取 FileReceiver::defaultDir()（下载\AllPeriph），
// 与「文件传输」面板同一目录 —— 互传来的文件在那里直接可见、也删得掉，不各写一份默认值。
bool startLocalSend(const HostBundle& b) {
    if (!b.localSend) return false;
    if (b.localSend->running()) return true;   // 幂等：重复点开关不会撞端口
    apxpc::localsend::Options opt;
    opt.dir = apxpc::wireless::FileReceiver::defaultDir();
    const bool ok = b.localSend->start(
        opt, [](const std::string& path, uint64_t size, const std::string& from) {
            Panel* p = g_panel;
            if (!p) return;
            const size_t slash = path.find_last_of("\\/");
            const std::string name =
                (slash == std::string::npos) ? path : path.substr(slash + 1);
            if (p->bundle.tray) p->bundle.tray->notify("收到文件", from + " 发来 " + name);
            // 回调跑在接收线程；PostWebMessageAsJson 线程安全（同 postEvent 的约定），
            // 但面板刚启动、WebView2 还没建好时 webview 为空 —— 判空后再推。
            if (!p->webview) return;
            Json d = Json::makeObject();
            d["path"] = path;
            d["size"] = static_cast<double>(size);
            d["from"] = from;
            d["via"] = std::string("localsend");
            postEvent(p, std::string("on-localsend-received"), d);
        });
    if (ok)
        APX_LOGI("互传接收端已启动：端口 %u",
                 static_cast<unsigned>(b.localSend->listenPort()));
    else
        APX_LOGW("互传接收端启动失败：%s", b.localSend->lastError().c_str());
    return ok;
}

// 用资源管理器打开落盘目录。路径来自宿主默认目录（不是前端自由输入），但仍走
// ShellExecuteW 的 argv 形式、不经 cmd —— 不给命令行拼接留任何余地。
void openDirInExplorer(const std::string& dir) {
    if (dir.empty()) return;
    const std::wstring w = utf8ToWide(dir);
    const HINSTANCE r = ::ShellExecuteW(nullptr, L"open", w.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    if (reinterpret_cast<INT_PTR>(r) <= 32)
        APX_LOGW("打开互传目录失败（ShellExecuteW=%lld）: %s",
                 static_cast<long long>(reinterpret_cast<INT_PTR>(r)), dir.c_str());
}

void handleAct(Panel* p, const std::string& id, const std::string& name, const Json& payload) {
    APX_LOGI("handleAct: name={} id={}", name.c_str(), id.c_str());
    const HostBundle& b = p->bundle;

    // —— 连接控制 ——
    if (name == "session.startAuto" && b.session) {
        b.session->startAuto();
        return postResponse(p, id, okResp());
    }
    if (name == "session.connectManual" && b.session) {
        std::string host = payload.find("host") ? payload.find("host")->asString() : "";
        uint16_t port = static_cast<uint16_t>(payload.find("port") ? payload.find("port")->asInt(9511) : 9511);
        b.session->connectManual(host, port);
        return postResponse(p, id, okResp());
    }
    if (name == "session.disconnect" && b.session) {
        b.session->disconnect();
        return postResponse(p, id, okResp());
    }

    // —— 远程输入：对端请求本机代输 → 前端 RemoteInputModal 回传至此 ——
    if (name == "session.sendInputText" && b.session) {
        std::string text = payload.find("text") ? payload.find("text")->asString() : "";
        uint8_t flags = static_cast<uint8_t>(payload.find("flags") ? payload.find("flags")->asInt(0x04) : 0x04);
        const bool ok = b.session->sendInputText(text, flags);
        return postResponse(p, id, ok ? okResp() : failResp("发送输入失败（未连接）"));
    }
    if (name == "session.sendInputDone" && b.session) {
        const bool ok = b.session->sendInputDone();
        return postResponse(p, id, ok ? okResp() : failResp("发送完成通知失败"));
    }

    // —— 副屏 ——
    if (name == "screen.toggle" && b.screenPush) {
        if (b.screenPush->running()) { b.screenPush->stop(); return postResponse(p, id, okResp()); }
        if (!b.media || !b.media->status().connected)
            return postResponse(p, id, failResp("媒体通道未连接（需与手机同一 Wi‑Fi）"));
        apxpc::media::ScreenPushOptions opt;
        opt.mirrorMode = false; opt.bitrateKbps = 8000;
        std::string err;
        const bool ok = b.screenPush->start(b.media, opt, &err);
        if (ok) {
            // 唤起手机副屏页（控制面 0x05）：手机端 ControlledService 收到后广播
            // ScreenOpenRequestEvent → 前台直接弹 ScreenActivity；后台被拦时走通知兜底。
            // 老面板 panel_win32.cpp 一直这么干；新面板此前漏了这一步，PC 在推流而
            // 手机副屏页没开 → 帧全进 Renderer.droppedNoSurface，手机毫无反应。
            if (b.session) p->bundle.session->requestOpenScreen();
        }
        return postResponse(p, id, ok ? okResp() : failResp(err));
    }

    // —— 音箱 ——
    if (name == "speaker.toggle" && b.audio) {
        if (b.audio->running()) { b.audio->stop(); return postResponse(p, id, okResp()); }
        if (!b.media || !b.media->status().connected)
            return postResponse(p, id, failResp("媒体通道未连接"));
        apxpc::media::AudioCaptureOptions opt;
        auto* devId = payload.find("deviceId");
        if (devId) opt.deviceId = devId->asString();
        std::string err;
        const bool ok = b.audio->start(b.media, opt, &err);
        return postResponse(p, id, ok ? okResp() : failResp(err));
    }

    // —— 麦克风桥（手机麦 → 本机播放设备）——
    if (name == "mic.toggle" && b.micBridge) {
        if (b.micBridge->running()) { b.micBridge->stop(); return postResponse(p, id, okResp()); }
        std::string devId = payload.find("deviceId") ? payload.find("deviceId")->asString() : "";
        std::string err;
        const bool ok = b.micBridge->start(devId, &err);
        return postResponse(p, id, ok ? okResp() : failResp(err));
    }

    // —— 设置项 ——
    if (name == "config.setAdaptive" && b.cfg) {
        auto* v = payload.find("on");
        b.cfg->displayAdaptive = v ? v->asBool(b.cfg->displayAdaptive) : !b.cfg->displayAdaptive;
        if (!b.configPath.empty()) config::saveConfig(b.configPath, *b.cfg);
        Json d1 = Json::makeObject(); d1["adaptive"] = b.cfg->displayAdaptive;
        return postResponse(p, id, okResp(d1));
    }
    if (name == "config.setBitrate" && b.cfg) {
        auto* v = payload.find("mbps");
        if (v) b.cfg->displayBitrateMbps = static_cast<uint32_t>(v->asInt(b.cfg->displayBitrateMbps));
        if (!b.configPath.empty()) config::saveConfig(b.configPath, *b.cfg);
        Json d2 = Json::makeObject(); d2["bitrateMbps"] = static_cast<double>(b.cfg->displayBitrateMbps);
        return postResponse(p, id, okResp(d2));
    }

    // —— 退出 / 隐藏 ——
    if (name == "window.hide") {
        ::ShowWindow(p->hwnd, SW_HIDE);
        return postResponse(p, id, okResp());
    }
    if (name == "window.quit") {
        p->running.store(false);
        ::PostMessageW(p->hwnd, WM_CLOSE, 0, 0);
        return postResponse(p, id, okResp());
    }
    if (name == "tray.setAutostart") {
        bool enable = payload.find("enable") ? payload.find("enable")->asBool(false) : false;
        // 已实现：通过注册表写入/删除开机自启项（HKCU\...\Run）
        const bool ok = apxpc::autostart::setAutostart(enable);
        if (ok && b.cfg) {
            b.cfg->autostart = enable;
            if (!b.configPath.empty()) config::saveConfig(b.configPath, *b.cfg);
        }
        APX_LOGI("tray.setAutostart(%d) → %s", enable, ok ? "success" : "failed");
        Json d3 = Json::makeObject(); d3["ok"] = ok; d3["autostart"] = enable;
        return postResponse(p, id, okResp(d3));
    }
    if (name == "config.setLogLevel" && b.cfg) {
        auto* l = payload.find("level");
        if (l) b.cfg->logLevel = l->asString();
        if (!b.configPath.empty()) config::saveConfig(b.configPath, *b.cfg);
        return postResponse(p, id, okResp());
    }

    // —— 文件传输（PC ↔ 手机，TCP 9512）——
    // —— 互传（LocalSend）：toggle / status / openDir ——
    if (name == "localsend.toggle") {
        if (!b.localSend) return postResponse(p, id, failResp("互传接收端未初始化"));
        auto* en = payload.find("enable");
        const bool enable = en ? en->asBool(false) : !b.localSend->running();
        if (enable && !startLocalSend(b))
            return postResponse(p, id, failResp("互传接收端启动失败：" + b.localSend->lastError()));
        if (!enable) b.localSend->stop();
        std::string lerr;
        return postResponse(p, id, okResp(Json::parse(apxpc::localsend::statusJson(b.localSend->snapshot()), &lerr)));
    }
    if (name == "localsend.status") {
        if (!b.localSend) return postResponse(p, id, failResp("互传接收端未初始化"));
        std::string lerr;
        return postResponse(p, id, okResp(Json::parse(apxpc::localsend::statusJson(b.localSend->snapshot()), &lerr)));
    }
    // localsend.send：本机 → 某台设备（PC → 手机 / 官方客户端）。后台线程发，
    // 结果用 on-localsend-sent 事件回推（大文件要传几十秒，不能让前端干等）
    if (name == "localsend.send") {
        const std::string ip = payload.find("ip") ? payload.find("ip")->asString() : "";
        const int port = payload.find("port") ? payload.find("port")->asInt(0) : 0;
        std::vector<std::string> paths;
        if (const Json* arr = payload.find("paths"); arr && arr->isArray()) {
            for (const auto& v : arr->asArray())
                if (v.isString() && !v.asString().empty()) paths.push_back(v.asString());
        }
        if (ip.empty() || port <= 0 || port > 65535)
            return postResponse(p, id, failResp("目标设备不合法（需要 ip 与 port）"));
        if (paths.empty()) return postResponse(p, id, failResp("未选择文件"));
        if (p->sendBusy.exchange(true))
            return postResponse(p, id, failResp("上一批文件还在发送中"));

        apxpc::localsend::Target target;
        target.ip = ip;
        target.port = static_cast<uint16_t>(port);
        std::vector<apxpc::localsend::OutgoingFile> files;
        files.reserve(paths.size());
        for (const auto& f : paths) files.push_back({f, {}});

        if (p->sendThread.joinable()) p->sendThread.join();
        p->sendThread = std::thread([p, target, files] {
            apxpc::localsend::LocalSendSender tx;
            apxpc::localsend::SenderIdentity self;
            const auto res = tx.send(target, self, files);
            p->sendBusy.store(false);
            Json d = Json::makeObject();
            d["ok"] = res.ok;
            d["ip"] = target.ip;
            d["port"] = static_cast<double>(target.port);
            d["peerAlias"] = res.peerAlias;
            d["error"] = res.error;
            d["count"] = static_cast<double>(res.deliveredNames.size());
            d["bytes"] = static_cast<double>(res.bytesSent);
            postEvent(p, std::string("on-localsend-sent"), d);
        });
        return postResponse(p, id, okResp());
    }
    if (name == "localsend.openDir") {
        if (!b.localSend) return postResponse(p, id, failResp("互传接收端未初始化"));
        const std::string dir = b.localSend->snapshot().dir;
        if (dir.empty()) return postResponse(p, id, failResp("落盘目录未知"));
        openDirInExplorer(dir);
        return postResponse(p, id, okResp());
    }

    if (name == "file.send") {
        const std::string path = payload.find("path") ? payload.find("path")->asString() : "";
        if (path.empty()) return postResponse(p, id, failResp("未选择文件"));
        WIN32_FILE_ATTRIBUTE_DATA fa{};
        const std::wstring wpath = utf8ToWide(path);
        if (!::GetFileAttributesExW(wpath.c_str(), ::GetFileExInfoStandard, &fa))
            return postResponse(p, id, failResp("文件不存在: " + path));
        if (fa.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
            return postResponse(p, id, failResp("不支持发送整个文件夹"));
        // 后台线程发送（startFileSend 内注释）；act-response 在发完后回
        startFileSend(p, path, id);
        return;
    }
    if (name == "file.list") {
        // 列出收到的文件（落盘目录：下载\AllPeriph），按修改时间新→旧
        namespace fs = std::filesystem;
        struct Ent { std::string name; std::string path; long long size; long long ftick; };
        std::vector<Ent> ents;
        std::error_code ec;
        const fs::path dir = fs::path(utf8ToWide(apxpc::wireless::FileReceiver::defaultDir()));
        if (fs::exists(dir, ec)) {
            fs::directory_iterator it(dir, ec), end;
            for (; !ec && it != end; it.increment(ec)) {
                std::error_code fec;
                if (!it->is_regular_file(fec)) continue;
                Ent e;
                e.path  = wideToUtf8(it->path().wstring());
                e.name  = wideToUtf8(it->path().filename().wstring());
                e.size  = static_cast<long long>(it->file_size(fec));
                e.ftick = static_cast<long long>(
                    it->last_write_time(fec).time_since_epoch().count());
                ents.push_back(std::move(e));
            }
        }
        std::sort(ents.begin(), ents.end(),
                  [](const Ent& a, const Ent& b) { return a.ftick > b.ftick; });
        Json arr = Json::makeArray();
        for (const auto& e : ents) {
            Json o = Json::makeObject();
            o["name"] = e.name;
            o["path"] = e.path;
            o["size"] = static_cast<double>(e.size);
            arr.push(o);
        }
        return postResponse(p, id, okResp(arr));
    }
    if (name == "file.delete") {
        // 白名单：只允许删「下载\AllPeriph」落盘目录内的文件 —— 不给前端任意删盘能力。
        // weakly_canonical 解析 ../ 与符号链接后再比前缀，防目录穿越。
        const std::string path = payload.find("path") ? payload.find("path")->asString() : "";
        if (path.empty()) return postResponse(p, id, failResp("未指定文件"));
        namespace fs = std::filesystem;
        std::error_code ec1, ec2, ec3;
        const fs::path root = fs::weakly_canonical(
            fs::path(utf8ToWide(apxpc::wireless::FileReceiver::defaultDir())), ec1);
        fs::path target = fs::weakly_canonical(fs::path(utf8ToWide(path)), ec2);
        if (ec1 || ec2) return postResponse(p, id, failResp("路径无效"));
        auto norm = [](std::string s) {
            std::replace(s.begin(), s.end(), '\\', '/');
            for (auto& c : s) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            return s;
        };
        const std::string r8 = norm(wideToUtf8(root.wstring()));
        const std::string t8 = norm(wideToUtf8(target.wstring()));
        if (t8.size() <= r8.size() + 1 || t8.compare(0, r8.size(), r8) != 0 ||
            t8[r8.size()] != '/') {
            return postResponse(p, id, failResp("只允许删除收到的文件（下载\\AllPeriph 内）"));
        }
        if (!fs::remove(target, ec3) || ec3)
            return postResponse(p, id, failResp("删除失败: " + ec3.message()));
        APX_LOGI("已删除收到的文件: {}", path.c_str());
        return postResponse(p, id, okResp());
    }

    // —— 日志：清单 + 按时间范围导出（issue #52 第 3 条）——
    // 与 panel_http.cpp 同口径（那边跑 macOS/Linux）。Windows 这一份**还多一个
    // log.pickDir**：这里的模态对话框跑在 UI 线程（WebView2 消息循环 + STA COM），
    // 能安全弹 IFileOpenDialog；macOS 的面板主线程被 accept 循环占着，弹不了，
    // 所以那边如实回 false 让前端退回「常用目录」候选。
    if (name == "log.list") {
        const std::string dir = apxpc::logexport::defaultLogDir();
        Json arr = Json::makeArray();
        for (const auto& f : apxpc::logexport::list(dir)) {
            Json o = Json::makeObject();
            o["name"] = f.name;
            o["kind"] = f.kind;
            o["size"] = static_cast<double>(f.size);
            o["mtime"] = static_cast<double>(f.mtimeSec);
            arr.push(std::move(o));
        }
        Json data = Json::makeObject();
        data["dir"] = dir;
        data["exportDir"] = apxpc::logexport::defaultExportDir();
        // 常用目录候选：即使能弹对话框也给一份「一键到的」快捷项。
        {
            Json presets = Json::makeArray();
            for (const auto& pr : apxpc::logexport::dirPresets()) {
                Json o = Json::makeObject();
                o["name"] = pr.first;
                o["path"] = pr.second;
                presets.push(std::move(o));
            }
            data["dirPresets"] = std::move(presets);
        }
        data["canPickDir"] = true;
        data["files"] = std::move(arr);
        return postResponse(p, id, okResp(data));
    }

    // 原生「选择文件夹」对话框。FOS_PICKFOLDERS 只让选目录、FOS_FORCEFILESYSTEM
    // 排除「网络位置/库」这类取不到盘符的虚拟位置（导出要的是能直接写的本地路径）。
    if (name == "log.pickDir") {
        wil::com_ptr<IFileOpenDialog> dlg;
        HRESULT hr = ::CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER,
                                        IID_PPV_ARGS(dlg.put()));
        if (FAILED(hr) || !dlg) {
            APX_LOGW("log.pickDir: 创建文件对话框失败 hr=0x{:08X}", static_cast<unsigned>(hr));
            return postResponse(p, id, failResp("无法打开系统目录选择对话框"));
        }
        DWORD opts = 0;
        if (SUCCEEDED(dlg->GetOptions(&opts)))
            dlg->SetOptions(opts | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM);
        dlg->SetTitle(L"选择日志导出目录");
        hr = dlg->Show(p->hwnd);
        if (hr == HRESULT_FROM_WIN32(ERROR_CANCELLED)) {
            // 取消不是错误：回空 dir，前端据此显示「已取消」而不是红字报错。
            Json d = Json::makeObject();
            d["dir"] = std::string();
            return postResponse(p, id, okResp(d));
        }
        if (FAILED(hr)) return postResponse(p, id, failResp("系统目录选择对话框打开失败"));
        wil::com_ptr<IShellItem> item;
        if (FAILED(dlg->GetResult(item.put())) || !item)
            return postResponse(p, id, failResp("未能取得所选目录"));
        PWSTR wpath = nullptr;
        if (FAILED(item->GetDisplayName(SIGDN_FILESYSPATH, &wpath)) || !wpath) {
            if (wpath) ::CoTaskMemFree(wpath);
            return postResponse(p, id, failResp("所选位置没有本地路径（请选本机磁盘上的目录）"));
        }
        Json d = Json::makeObject();
        d["dir"] = wideToUtf8(std::wstring(wpath));
        ::CoTaskMemFree(wpath);
        return postResponse(p, id, okResp(d));
    }

    if (name == "log.export") {
        auto sval = [&](const char* k) -> std::string {
            const Json* n = payload.find(k);
            return n ? n->asString() : std::string();
        };
        auto ival = [&](const char* k) -> long long {
            const Json* n = payload.find(k);
            return n ? static_cast<long long>(n->asInt(0)) : 0LL;
        };
        const std::string dir = sval("dir").empty() ? apxpc::logexport::defaultLogDir()
                                                    : sval("dir");
        // note：拼进文件名里的设备标识（用户可自定义，留空回落本机主机名）。
        const apxpc::logexport::Result r = apxpc::logexport::exportRange(
            dir, sval("dest"), sval("kind"), ival("since"), ival("until"), sval("note"));
        if (!r.ok) return postResponse(p, id, failResp(r.error));
        Json d = Json::makeObject();
        d["path"] = r.outPath;
        d["lines"] = static_cast<double>(r.lines);
        d["scanned"] = static_cast<double>(r.scanned);
        return postResponse(p, id, okResp(d));
    }

    return postResponse(p, id, failResp("未知动作: " + name));
}

/// 播放（渲染）设备列表 —— 前端「音箱 / 麦克风」设备下拉的数据源。
/// 与 Win32 原生面板（panel_win32.cpp 的 refreshSpeakerCombo）同源：
///   Windows 用 IMMDeviceEnumerator 枚举（AudioCapture::listRenderDevices）；
///   macOS 的 Process Tap 采的是全局进程组混音、不挂具体端点，Linux 未实现 —— 两者都返回空表，
///   前端据此如实显示「本机不支持枚举播放设备」，不编造列表。
Json audioDevicesJson() {
    Json arr = Json::makeArray();
    for (const auto& d : apxpc::media::AudioCapture::listRenderDevices()) {
        Json o = Json::makeObject();
        o["id"] = d.id;
        o["name"] = d.name;
        o["isDefault"] = d.isDefault;
        arr.push(std::move(o));
    }
    Json data = Json::makeObject();
    data["devices"] = std::move(arr);
    data["defaultName"] = apxpc::media::AudioCapture::defaultRenderDeviceName();
    return data;
}

void handleQuery(Panel* p, const std::string& id, const std::string& name) {
    if (name == "state") {
        Json s; buildState(p->bundle, s);
        Json r; r["kind"] = std::string("query-response");
        return postResponse(p, id, okResp(s), "query-response");
    }
    // 播放设备列表（前端设备下拉）：与 Win32 原生面板同源（IMMDeviceEnumerator）
    if (name == "audioDevices") {
        return postResponse(p, id, okResp(audioDevicesJson()), "query-response");
    }
    postResponse(p, id, failResp("未知查询: " + name), "query-response");
}

void handleOpenFile(Panel* p, const std::string& id, const std::string& filter) {
    OPENFILENAMEW of{};
    wchar_t file[MAX_PATH] = {};
    wchar_t wfilter[MAX_PATH] = {};
    // filter 用 | 分隔 → Win32 用 \0 分隔
    std::wstring wf = filter.empty() ? L"全部文件\0*.*\0" : std::wstring(filter.begin(), filter.end());
    std::replace(wf.begin(), wf.end(), L'|', L'\0');
    std::wcscpy(wfilter, wf.c_str());

    of.lStructSize = sizeof(of);
    of.hwndOwner = p->hwnd;
    of.nMaxFile = MAX_PATH;
    of.lpstrFile = file;
    of.lpstrFilter = wfilter;
    of.Flags = OFN_FILEMUSTEXIST | OFN_NOCHANGEDIR;
    const bool ok = ::GetOpenFileNameW(&of) != FALSE;
    Json r; r["kind"] = std::string("open-file-response");
    if (ok) {
        std::string s;
        s.assign(file, file + wcslen(file));
        r["ok"] = true; r["path"] = s;
    } else {
        r["ok"] = false;
    }
    postResponse(p, id, r);
}

// ———————————————— 处理 WebView2 的 WebMessageReceived ————————————————
void onWebMessageReceived(ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) {
    // ★ 必须用 get_WebMessageAsJson：页面 postMessage 发的是 **对象**（{kind:'act',...}），
    //   WebView2 的 TryGetWebMessageAsString 只对「字符串消息」成功，对对象消息直接返回
    //   失败 —— 此前一直用它，前端所有 act/query/open-file 全部石沉大海（表现：开关
    //   点击后后端毫无反应、10s 超时、UI 无任何提示）。get_WebMessageAsJson 对两类
    //   消息都能取（对象=原 JSON 文本；字符串=带引号的 JSON 字面量）。
    LPWSTR rawJson = nullptr;
    if (FAILED(args->get_WebMessageAsJson(&rawJson)) || !rawJson) {
        APX_LOGW("WebView2: 消息取出失败（get_WebMessageAsJson）");
        return;
    }
    std::wstring w(rawJson);
    ::CoTaskMemFree(rawJson);
    std::string s = wideToUtf8(w); // 原生 UTF-16→UTF-8，前端发来的中文不乱码
    APX_LOGI("WebView2 收到页面消息: {}", s.substr(0, 120).c_str());

    std::string err;
    auto j = Json::parse(s, &err);
    if (j.isNull()) { APX_LOGW("WebView2: JSON 解析失败: {}", err.c_str()); return; }

    auto* kind = j.find("kind");
    if (!kind) return;
    const std::string k = kind->asString();
    auto* id = j.find("id");
    const std::string idStr = id ? id->asString() : "";

    Panel* p = g_panel;
    if (!p) return;

    if (k == "act") {
        auto* name = j.find("name");
        auto* payload = j.find("payload");
        handleAct(p, idStr, name ? name->asString() : "", payload ? *payload : Json::makeObject());
    } else if (k == "query") {
        auto* name = j.find("name");
        handleQuery(p, idStr, name ? name->asString() : "");
    } else if (k == "open-file") {
        auto* filter = j.find("filter");
        handleOpenFile(p, idStr, filter ? filter->asString() : "");
    }
}

// ———————————————— WebView2 环境创建（Runtime 缺失时自动安装并重试） ————————————————
// 环境创建失败分两种：
//   · Runtime 没装（老版 Win10）→ 提示后自动下载安装，装完 Post 消息回来重试
//   · Runtime 已装仍失败        → 如实报错退出（问题在别处，如 WebView2Loader.dll 缺失）
void onWebView2EnvFailed(Panel* p, unsigned hresult) {
    APX_LOGE("WebView2 环境创建失败: HRESULT=0x{:08X}", hresult);
    if (isWebView2RuntimeInstalled()) {
        wchar_t code[32]{};
        std::swprintf(code, 32, L"0x%08X", hresult);
        MessageBoxW(p->hwnd,
            (std::wstring(L"WebView2 初始化失败（运行组件已安装仍无法创建环境）。\n错误码：") +
             code + L"\n\n详细日志：%LOCALAPPDATA%\\AllPeriph\\apxdesktop-<时间戳>.log").c_str(),
            L"全能外设", MB_OK | MB_ICONERROR);
        p->running.store(false);
        ::PostMessageW(p->hwnd, WM_CLOSE, 0, 0);
        return;
    }
    const INT r = MessageBoxW(p->hwnd,
        L"未检测到 WebView2 运行组件（本界面必需）。\n\n"
        L"确定后将自动下载安装（来自微软官方源），\n"
        L"安装完成后本窗口会自动打开界面。",
        L"全能外设", MB_OKCANCEL | MB_ICONINFORMATION);
    if (r != IDOK) {
        p->running.store(false);
        ::PostMessageW(p->hwnd, WM_CLOSE, 0, 0);
        return;
    }
    ::SetWindowTextW(p->hwnd, L"全能外设 — 正在下载安装 WebView2 运行组件…");
    // 下载+安装放后台线程（可能要几分钟）；线程只持 HWND 副本，不持有 Panel*，
    // 主窗口提前关闭也不会悬空。
    const HWND hw = p->hwnd;
    std::thread([hw] {
        std::wstring werr;
        if (ensureWebView2Runtime(werr, /*silent=*/false)) {
            ::PostMessageW(hw, kMsgWv2Ready, 0, 0);
        } else {
            MessageBoxW(nullptr,
                (L"WebView2 运行组件自动安装失败：\n" + werr +
                 L"\n\n可手动下载安装：https://aka.ms/webviewruntime").c_str(),
                L"全能外设", MB_OK | MB_ICONERROR);
            // 后台线程只持 HWND（不持 Panel*），无法置 running；经窗口过程置退出标志。
            // 不能直接发 WM_CLOSE —— X 键现在语义是「最小化」而不是退出（见 WM_CLOSE）。
            ::PostMessageW(hw, kMsgWv2Failed, 0, 0);
        }
    }).detach();
}

// 创建 WebView2 环境。WM_CREATE 与「自动安装完成」重试（kMsgWv2Ready）共用此入口。
void startWebView2Env(Panel* p) {
    // —— 定位 exe 目录（后续 web/index.html 导航、dataDir 都要用）——
    wchar_t exeDir[MAX_PATH] = {};
    if (::GetModuleFileNameW(nullptr, exeDir, MAX_PATH) > 0) {
        wchar_t* slash = wcsrchr(exeDir, L'\\');
        if (slash) *slash = L'\0';
    }
    std::wstring dataDir(exeDir); dataDir += L"\\apxwebview2-data";
    std::wstring wdir(exeDir);    wdir += L"\\web\\index.html";
    if (*exeDir == L'\0') { wdir = L".\\web\\index.html"; }

        auto* envCb = mkEnvCb(
            [p, exeDir, dataDir, wdir](HRESULT hr, ICoreWebView2Environment* env) -> HRESULT {
                if (FAILED(hr)) {
                    onWebView2EnvFailed(p, static_cast<unsigned>(hr));
                    return S_OK;
                }
                p->env = env;
                auto* ctrlCb = mkCtrlCb(
                    [p, exeDir, wdir](HRESULT hr3, ICoreWebView2Controller* controller) -> HRESULT {
                        if (FAILED(hr3) || !controller) {
                            APX_LOGE("CreateCoreWebView2Controller 失败: 0x{:08X}", static_cast<unsigned>(hr3));
                            return S_OK;
                        }
                        p->controller = controller;
                        wil::com_ptr<ICoreWebView2> wv;
                        if (!SUCCEEDED(controller->get_CoreWebView2(&wv))) {
                            APX_LOGE("controller->get_CoreWebView2 失败");
                            return S_OK;
                        }
                        p->webview = wv;

                        // ★ 初始 bounds：Controller 创建后必须立即设置，否则渲染区
                        //   为 0×0，窗口表现为空白（WM_SIZE 只在尺寸变化时触发）。
                        {
                            RECT rc; ::GetClientRect(p->hwnd, &rc);
                            controller->put_Bounds({0, 0, rc.right, rc.bottom});
                        }
                        // 新 webview 就绪（含 WebView2 自动安装后重启环境的路径）：
                        // 清空指纹，确保首个 tick 必推。
                        p->lastRepaintKey.clear();
                        p->forceTick = 0;

                        // 设置：启用 dev tools（开发期方便调试；release 可关）
                        wil::com_ptr<ICoreWebView2Settings> settings;
                        if (SUCCEEDED(wv->get_Settings(&settings))) {
                            settings->put_AreDevToolsEnabled(TRUE);
                            settings->put_AreDefaultContextMenusEnabled(FALSE);
                        }
                        // 注册消息接收
                        wv->add_WebMessageReceived(
                            mkMsgCb(
                                [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                                    onWebMessageReceived(nullptr, args);
                                    return S_OK;
                                }), nullptr);

                        // 加载前端 dist/index.html。
                        // ★ 必须用虚拟主机映射（ICoreWebView2_3）而不是 file://：
                        //   ① Navigate() 只接受完整 URI，裸 Windows 路径无效；
                        //   ② file:// 页面加载 type=module 的 ES 模块会被 CORS 拦截
                        //     （origin 为 opaque，crossorigin 必失败）→ 前端白屏。
                        //   映射后以 https://app.allperiph/ 访问本地 web 目录，两类问题同时解决。
                        const wchar_t* kWebHost = L"app.allperiph";
                        bool navigated = false;
                        wil::com_ptr<ICoreWebView2_3> wv3;
                        if (*exeDir != L'\0' && SUCCEEDED(wv->QueryInterface(wv3.put()))) {
                            std::wstring webDir(exeDir); webDir += L"\\web";
                            if (SUCCEEDED(wv3->SetVirtualHostNameToFolderMapping(
                                    kWebHost, webDir.c_str(),
                                    COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_ALLOW))) {
                                APX_LOGI("WebView2 加载: https://app.allperiph/index.html（虚拟主机映射已启用）");
                                wv->Navigate((std::wstring(L"https://") + kWebHost + L"/index.html").c_str());
                                navigated = true;
                            }
                        }
                        if (!navigated) {
                            // 兜底：极老系统没有 _3 接口 → 退回 file:// URI（前端若有非 module 资源仍可部分工作）
                            std::wstring url = L"file:///" + wdir;
                            for (auto& c : url) if (c == L'\\') c = L'/';
                            APX_LOGW("虚拟主机映射不可用，退回 file:// 导航（ES module 可能被 CORS 拦截）");
                            wv->Navigate(url.c_str());
                        }

                        // 事件回调：对端（手机/TV）请求本机输入 → 前端弹远程输入浮层
                        if (p->bundle.session) {
                            p->bundle.session->setOnRequestInput([p](const std::string& hint) {
                                Json d = Json::makeObject();
                                d["hint"] = hint;
                                postEvent(p, std::string("on-remote-input-request"), d);
                            });
                        }

                        // 启动状态推流定时器（400ms）
                        ::SetTimer(p->hwnd, kStateTimer, 400, nullptr);
                        APX_LOGI("WebView2 面板就绪");
                        return S_OK;
                    });
                env->CreateCoreWebView2Controller(p->hwnd, ctrlCb);
                return S_OK;
            });
        // nullptr → 使用系统默认 WebView2 Runtime 路径
        HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
            nullptr, dataDir.empty() ? nullptr : dataDir.c_str(), nullptr, envCb);
        if (FAILED(hr)) onWebView2EnvFailed(p, static_cast<unsigned>(hr));
    }

// ———————————————— 窗口过程 ————————————————
LRESULT CALLBACK webWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == WM_CREATE) {
        CREATESTRUCT* cs = reinterpret_cast<CREATESTRUCT*>(lp);
        Panel* p = reinterpret_cast<Panel*>(cs->lpCreateParams);
        p->hwnd = hwnd;
        ::SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(p));
        startWebView2Env(p);
        return 0;
    }
    if (msg == kMsgWv2Ready) {
        // WebView2 自动安装完成 → 恢复标题并重新创建环境
        ::SetWindowTextW(hwnd, kWindowTitle);
        Panel* p = reinterpret_cast<Panel*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (p) startWebView2Env(p);
        return 0;
    }
    if (msg == WM_TIMER && wp == kStateTimer) {
        Panel* p = reinterpret_cast<Panel*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (p && p->webview) tickState(p);
        return 0;
    }
    if (msg == WM_SIZE) {
        Panel* p = reinterpret_cast<Panel*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (p && p->controller) {
            RECT rc; ::GetClientRect(hwnd, &rc);
            p->controller->put_Bounds({0, 0, rc.right, rc.bottom});
        }
        return 0;
    }
    if (msg == kMsgWv2Failed) {
        // WebView2 自动安装失败（后台线程转发）：没有界面就没有继续跑的意义，真退出
        Panel* p = reinterpret_cast<Panel*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (p) p->running.store(false);
        ::DestroyWindow(hwnd);
        return 0;
    }
    if (msg == WM_DESTROY) {
        ::KillTimer(hwnd, kStateTimer);
        Panel* p = reinterpret_cast<Panel*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (p) p->running.store(false);
        return 0;
    }
    if (msg == WM_CLOSE) {
        // 点 X ≠ 退出（与老面板 panel_win32 的 WM_CLOSE 语义一致）：最小化到任务栏，
        // 后台服务（9511 控制 / 9502 媒体 / 推流）继续跑。真退出只走前端「退出」按钮
        // （window.quit，先 running=false 再发 WM_CLOSE）与 WebView2 初始化失败路径
        // —— 这两处的 WM_CLOSE 都带着 running=false，据此区分，不能反过来判。
        Panel* p = reinterpret_cast<Panel*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (p && p->running.load()) {
            ::ShowWindow(hwnd, SW_MINIMIZE);
            return 0;
        }
        ::DestroyWindow(hwnd);
        return 0;
    }
    return ::DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

// ———————— FileReceiver 接收回调（desktop_main 在接收线程调用） ————————
// ① 托盘气泡：老面板 file_panel 同款提示；新面板此前回调传 nullptr → 收了文件毫无反应
// ② 推 on-file-received 事件：前端文件面板实时刷新列表
void notifyFileReceived(const std::string& path) {
    Panel* p = g_panel;
    if (!p) {
        APX_LOGW("收到文件但面板未就绪: {}", path.c_str());
        return;
    }
    if (p->bundle.tray) {
        const size_t slash = path.find_last_of("\\/");
        const std::string name = (slash == std::string::npos) ? path : path.substr(slash + 1);
        p->bundle.tray->notify("收到文件", name + " — 已保存到 下载\\AllPeriph");
    }
    Json d = Json::makeObject();
    d["path"] = path;
    postEvent(p, std::string("on-file-received"), d);
}

bool webPanelAvailable() {
#if defined(_WIN32)
    // CreateCoreWebView2EnvironmentWithOptions 是异步的：用事件对象等待回调，超时就算不可用
    wil::com_ptr<ICoreWebView2Environment> env;
    HANDLE doneEvent = ::CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!doneEvent) return false;
    HRESULT waitHResult = E_FAIL;
    auto* cb = mkEnvCb(
        [&](HRESULT h, ICoreWebView2Environment* e) -> HRESULT {
            waitHResult = h;
            if (SUCCEEDED(h) && e) env = e;
            ::SetEvent(doneEvent);
            return S_OK;
        });
    HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(nullptr, nullptr, nullptr, cb);
    bool ok = false;
    if (SUCCEEDED(hr)) {
        ok = (::WaitForSingleObject(doneEvent, 15000) == WAIT_OBJECT_0)
          && SUCCEEDED(waitHResult) && env;
    }
    ::CloseHandle(doneEvent);
    return ok;
#else
    return false;
#endif
}

int runWebPanel(const HostBundle& bundle, const std::string& /*webDir*/) {
#if !defined(_WIN32)
    (void)bundle; return -1;
#else
    Panel panel;
    panel.bundle = bundle;
    g_panel = &panel;
    panel.running.store(true);
    // 互传接收端：面板一开就起（与 9511 受控端 / 9512 收文件同口径 —— 面板在跑就能互传）。
    // 失败（53317 被官方 localsend 占用等）只告警、不阻断面板：状态里能点开关重试。
    startLocalSend(panel.bundle);

    // ★ WebView2 依赖 COM：UI 线程必须先初始化 STA，否则 CreateCoreWebView2Environment
    //   必然返回 0x800401F0（CO_E_NOTINITIALIZED）。
    //   S_FALSE（已初始化过）不算失败；RPC_E_CHANGED_MODE 时沿用现有套间继续跑。
    const HRESULT hrCom = ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    const bool comOwned = SUCCEEDED(hrCom) && hrCom != S_FALSE;
    if (FAILED(hrCom)) {
        APX_LOGW("CoInitializeEx(STA) 返回 0x{:08X}（继续，沿用当前套间）", static_cast<unsigned>(hrCom));
    }

    // ★ 启动时同步 autostart 状态：如果 config 里 autostart=true 但注册表没有，补写
    if (bundle.cfg && bundle.cfg->autostart) {
        if (!apxpc::autostart::isAutostartEnabled()) {
            APX_LOGI("启动同步: config autostart=true 但注册表无，补写");
            apxpc::autostart::setAutostart(true);
        }
    }

    // ★ 自动发现（对齐老面板 panel_win32.cpp「初始：自动发现并立刻进入等待」）：
    //   新面板此前只有前端发 session.startAuto 动作才会启动发现，而前端从未发过 ——
    //   WirelessSession 永远停在 Idle：不收 APX1PH 信标、不连手机。表现为手机端
    //   「Wi-Fi 控制」开着、信标正常广播，面板却永远「尚未连接」、副屏/音箱/麦克风
    //   全部不可用，且日志里一条「切到自动发现模式」都没有。
    if (bundle.session) {
        bundle.session->startAuto();
        APX_LOGI("无线自动发现已启动（等待手机 APX1PH 信标）");
    }

    // 注册窗口类
    WNDCLASSEXW wc = {};
    wc.cbSize = sizeof(wc);
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = webWndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = kClassName;
    RegisterClassExW(&wc);

    // —— 按当前显示器 DPI 计算物理像素尺寸（CSS 基准 960×808）——
    // DPI-aware 进程下 CreateWindowExW 的 cx/cy 被解释为物理像素；
    // 再用 AdjustWindowRectEx 把客户区尺寸（WebView2 大小）换算成含标题栏/边框的外框。
    const int kDefaultCssW = 960;
    const int kDefaultCssH = 808;
    const int sysDpi = ::GetDpiForSystem();
    const int cx = sysDpi * kDefaultCssW / 96;
    const int cy = sysDpi * kDefaultCssH / 96;
    RECT frame{0, 0, cx, cy};
    ::AdjustWindowRectEx(&frame, WS_OVERLAPPEDWINDOW | WS_SIZEBOX, FALSE, 0);

    HWND hwnd = ::CreateWindowExW(0, kClassName, kWindowTitle,
        WS_OVERLAPPEDWINDOW | WS_SIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT,
        frame.right - frame.left, frame.bottom - frame.top,
        nullptr, nullptr, GetModuleHandleW(nullptr), &panel);
    if (!hwnd) { APX_LOGE("Web 窗口创建失败: GetLastError={}", ::GetLastError()); return -1; }
    panel.hwnd = hwnd;
    ::ShowWindow(hwnd, SW_SHOW);
    ::UpdateWindow(hwnd);

    // 托盘接线（desktop_main 只建了图标，「打开面板 / 退出」的回调一直没注册 ——
    // 窗口句柄只有这里才有）。回调跑在托盘线程：ShowWindow/PostMessage 跨线程安全，
    // 与老面板 panel_win32 同款。
    if (panel.bundle.tray) {
        panel.bundle.tray->setOpenCallback([hwnd] {
            // X/最小化/隐藏后从托盘唤回面板
            ::ShowWindow(hwnd, SW_RESTORE);
            ::SetForegroundWindow(hwnd);
        });
        panel.bundle.tray->setQuitCallback([hwnd] {
            // 托盘「退出」= 真退出整个进程。此前 desktop_main 的回调是 PostQuitMessage(0)
            // —— 投到托盘线程自己的消息循环，主面板与全部服务根本收不到（假退）。
            // 先置 running=false 再发 WM_CLOSE：与 window.quit 走同一条真退出路径
            // （见 WM_CLOSE 处理：running=false 才销毁窗口）。
            if (Panel* pp = g_panel) pp->running.store(false);
            ::PostMessageW(hwnd, WM_CLOSE, 0, 0);
        });

        // —— 以下三项为桌面入口恢复的托盘能力（老面板 panel_win32 一直有，WebView2 面板
        //    接线时漏掉）：托盘菜单项的规则是「回调未设置则不显示」，缺失即菜单里没这一项。——

        // 「让手机帮我输入…」：发 0x25 请求对端弹输入法；回传文本（0x26）由
        // WirelessSession 直接注入本机光标处（与老面板同口径，不绕 UI 线程）。
        panel.bundle.tray->setInputCallback([] {
            Panel* pp = g_panel;   // 退出后 g_panel 已置空，托盘线程晚触发不悬空
            if (!pp || !pp->bundle.session || !pp->bundle.tray) return;
            if (!pp->bundle.session->connected()) {
                pp->bundle.tray->notify("还没连上手机", "「让手机帮我输入」需要先连上手机 / 电视");
                return;
            }
            const bool ok = pp->bundle.session->requestInput("电脑请求输入文本");
            pp->bundle.tray->notify(ok ? "已请手机输入" : "发送失败",
                                    ok ? "请在手机上打字，文字会落到电脑光标处"
                                       : "发送请求失败（连接已断开？）");
        });

        // 「发送文件到手机…」：选文件 → 与前端 file.send 同一条链路后台发送
        panel.bundle.tray->setSendFileCallback([] {
            Panel* pp = g_panel;
            if (!pp) return;
            OPENFILENAMEW of{};
            wchar_t file[MAX_PATH] = {};
            of.lStructSize = sizeof(of);
            of.hwndOwner = pp->hwnd;
            of.nMaxFile = MAX_PATH;
            of.lpstrFile = file;
            of.lpstrFilter = L"全部文件\0*.*\0";
            of.Flags = OFN_FILEMUSTEXIST | OFN_NOCHANGEDIR;
            if (!::GetOpenFileNameW(&of)) return;
            const std::string path = wideToUtf8(std::wstring(file));
            const size_t slash = path.find_last_of("\\/");
            const std::string name = (slash == std::string::npos) ? path : path.substr(slash + 1);
            pp->bundle.tray->notify("正在发送文件", name);
            startFileSend(pp, path, "");   // 后台线程：托盘线程不能被大文件卡住
        });

        // 「文件传输…」：唤回面板 + 推事件打开文件传输弹窗（前端 App.tsx 监听 open-file-panel）
        panel.bundle.tray->setFilePanelCallback([hwnd] {
            ::ShowWindow(hwnd, SW_RESTORE);
            ::SetForegroundWindow(hwnd);
            if (Panel* pp = g_panel) postEvent(pp, std::string("open-file-panel"));
        });
    }

    // 消息循环
    MSG msg;
    while (panel.running.load() && GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    // 文件发送线程持 Panel*（栈对象）：必须先 join 再出栈，否则发送中退出会悬空崩溃
    {
        std::lock_guard<std::mutex> lk(g_sendMu);
        for (auto& t : g_sendThreads) if (t.joinable()) t.join();
        g_sendThreads.clear();
    }
    // 媒体建链线程：面板退出前 join（Panel 是栈对象，析构时若线程仍 joinable 会 terminate）
    // 互传接收端：先停再清 g_panel —— 接收线程的回调以 g_panel 为准，顺序反了会漏推事件
    if (panel.bundle.localSend) panel.bundle.localSend->stop();
    // 发送线程持 Panel*（栈对象）：先 join 再出栈
    if (panel.sendThread.joinable()) panel.sendThread.join();
    if (panel.mediaThread.joinable()) panel.mediaThread.join();
    g_panel = nullptr;
    if (comOwned) ::CoUninitialize();
    return 0;
#endif
}

}  // namespace apxpc::ui