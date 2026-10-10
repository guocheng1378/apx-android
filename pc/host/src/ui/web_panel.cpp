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
#endif

#include "apxpc/ui/web_panel.hpp"
#include "apxpc/log.hpp"
#include "apxpc/net/json.hpp"
#include "apxpc/wireless/wireless_session.hpp"
#include "apxpc/wireless/file_receiver.hpp"
#include "apxpc/media/media_session.hpp"
#include "apxpc/media/screen_push.hpp"
#include "apxpc/media/audio_capture.hpp"
#include "apxpc/media/mic_bridge.hpp"

#include <wil/com.h>
#include <WebView2.h>

#include <atomic>
#include <algorithm>
#include <cstdio>
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

// ———————————————— COM 回调适配器（WebView2 SDK 没有 Callback<T> 模板） ———————————————
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
};
Panel* g_panel = nullptr;

// ———————————————— 工具：JSON 辅助 ———————————————
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

// ———————————————— 状态构建（每 400ms 一次） ———————————————
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
        link["phase"] = std::to_string(static_cast<int>(s.phase));
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

    out["link"] = link;
    out["media"] = media;
    out["transports"] = transports;
    out["counts"] = counts;
    out["config"] = cfg;
}

// ———————————————— 把状态推给前端（400ms tick） ———————————————
void tickState(Panel* p) {
    Json state;
    buildState(p->bundle, state);
    std::string json = state.stringify();
    // 指纹：没变就不推（避免无谓渲染）
    std::wstring key;
    key.reserve(json.size());
    for (char c : json) key.push_back(static_cast<wchar_t>(static_cast<unsigned char>(c)));
    if (key == p->lastRepaintKey) return;
    p->lastRepaintKey = key;

    Json push = Json::makeObject();
    push["kind"] = std::string("state");
    push["t"] = static_cast<double>(GetTickCount64());
    push["data"] = state;
    std::string s = push.stringify();
    std::wstring w(s.begin(), s.end());
    p->webview->PostWebMessageAsJson(w.c_str());
}

// ———————————————— 回传前端请求的响应 ———————————————
void postResponse(Panel* p, const std::string& id, Json resp) {
    resp["id"] = id;
    std::string s = resp.stringify();
    std::wstring w(s.begin(), s.end());
    p->webview->PostWebMessageAsJson(w.c_str());
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
    std::wstring w(s.begin(), s.end());
    p->webview->PostWebMessageAsJson(w.c_str());
}

// ———————————————— 动作分发（前端 act 请求） ———————————————
void handleAct(Panel* p, const std::string& id, const std::string& name, const Json& payload) {
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
        // TODO(后期接入 apxpc::tray::setAutostart 真正实现); 这里暂静默返回 ok
        APX_LOGW("tray.setAutostart(%d) 暂未实现，跳过", enable);
        Json d3 = Json::makeObject(); d3["ok"] = true;
        return postResponse(p, id, okResp(d3));
    }
    if (name == "config.setLogLevel" && b.cfg) {
        auto* l = payload.find("level");
        if (l) b.cfg->logLevel = l->asString();
        if (!b.configPath.empty()) config::saveConfig(b.configPath, *b.cfg);
        return postResponse(p, id, okResp());
    }

    return postResponse(p, id, failResp("未知动作: " + name));
}

void handleQuery(Panel* p, const std::string& id, const std::string& name) {
    if (name == "state") {
        Json s; buildState(p->bundle, s);
        Json r; r["kind"] = std::string("query-response");
        return postResponse(p, id, okResp(s));
    }
    postResponse(p, id, failResp("未知查询: " + name));
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

// ———————————————— 处理 WebView2 的 WebMessageReceived ———————————————
void onWebMessageReceived(ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) {
    LPWSTR rawJson = nullptr;
    if (FAILED(args->TryGetWebMessageAsString(&rawJson)) || !rawJson) return;
    std::wstring w(rawJson);
    ::CoTaskMemFree(rawJson);
    std::string s(w.begin(), w.end());

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

// ———————————————— 窗口过程 ———————————————
LRESULT CALLBACK webWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == WM_CREATE) {
        CREATESTRUCT* cs = reinterpret_cast<CREATESTRUCT*>(lp);
        Panel* p = reinterpret_cast<Panel*>(cs->lpCreateParams);
        p->hwnd = hwnd;
        ::SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(p));

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
                    APX_LOGE("WebView2 环境创建失败: HRESULT=0x{:08X}", static_cast<unsigned>(hr));
                    MessageBoxW(p->hwnd,
                        L"WebView2 初始化失败。\n\n"
                        L"请安装 WebView2 Runtime（Win10 2004+ 与 Win11 已自带；"
                        L"Win10 旧版请访问 https://aka.ms/webviewruntime 下载）。",
                        L"全能外设", MB_OK | MB_ICONERROR);
                    p->running.store(false);
                    ::PostMessageW(p->hwnd, WM_CLOSE, 0, 0);
                    return S_OK;
                }
                p->env = env;
                auto* ctrlCb = mkCtrlCb(
                    [p, wdir](HRESULT hr3, ICoreWebView2Controller* controller) -> HRESULT {
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

                        // 加载前端 dist/index.html
                        APX_LOGI("WebView2 加载: {}", std::string(wdir.begin(), wdir.end()).c_str());
                        wv->Navigate(wdir.c_str());

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
        if (FAILED(hr)) {
            APX_LOGE("CreateCoreWebView2EnvironmentWithOptions 失败: 0x{:08X}", static_cast<unsigned>(hr));
            MessageBoxW(hwnd, L"无法初始化 WebView2（CreateCoreWebView2Environment 调用失败）",
                L"全能外设", MB_OK | MB_ICONERROR);
            ::PostMessageW(hwnd, WM_CLOSE, 0, 0);
        }
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
    if (msg == WM_DESTROY) {
        ::KillTimer(hwnd, kStateTimer);
        Panel* p = reinterpret_cast<Panel*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (p) p->running.store(false);
        return 0;
    }
    if (msg == WM_CLOSE) {
        ::DestroyWindow(hwnd);
        return 0;
    }
    return ::DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

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

    // 创建窗口
    HWND hwnd = CreateWindowExW(0, kClassName, kWindowTitle,
        WS_OVERLAPPEDWINDOW | WS_SIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT, 960, 808,
        nullptr, nullptr, GetModuleHandleW(nullptr), &panel);
    if (!hwnd) { APX_LOGE("Web 窗口创建失败: GetLastError={}", ::GetLastError()); return -1; }
    panel.hwnd = hwnd;
    ::ShowWindow(hwnd, SW_SHOW);
    ::UpdateWindow(hwnd);

    // 消息循环
    MSG msg;
    while (panel.running.load() && GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    g_panel = nullptr;
    return 0;
#endif
}

}  // namespace apxpc::ui
