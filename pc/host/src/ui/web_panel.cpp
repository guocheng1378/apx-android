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

constexpr UINT_PTR kStateTimer = 1;     // 状态推流定时器（400ms）
constexpr UINT kMsgPostQuitCustom = WM_APP + 10;

LPCWSTR kClassName = L"AllPeriphWebPanel";
LPCWSTR kWindowTitle = L"全能外设";

struct Panel {
    HWND hwnd = nullptr;
    HostBundle bundle;
    wil::com_ptr<ICoreWebView2> webview;
    wil::com_ptr<ICoreWebView2Environment> env;
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

std::wstring phaseName(LinkPhase p) {
    switch (p) {
        case LinkPhase::Connected:   return L"connected";
        case LinkPhase::Connecting:  return L"connecting";
        case LinkPhase::Discovering: return L"discovering";
        case LinkPhase::Failed:      return L"failed";
        default:                     return L"idle";
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
        a["sampleRate"] = s.sampleRate;
        a["channels"] = s.channels;
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
    std::string json = state.dump();
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
    std::string s = push.dump();
    std::wstring w(s.begin(), s.end());
    p->webview->PostWebMessageAsJson(w.c_str());
}

// ———————————————— 回传前端请求的响应 ———————————————
void postResponse(Panel* p, const std::string& id, Json resp) {
    resp["id"] = id;
    std::string s = resp.dump();
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
    std::string s = push.dump();
    std::wstring w(s.begin(), s.end());
    p->webview->PostWebMessageAsJson(w.c_str());
}

// ———————————————— 动作分发（前端 act 请求） ———————————————
void handleAct(Panel* p, const std::string& id, const std::string& name, Json& payload) {
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
        apxpc::display::IDisplayController* disp = nullptr;
        // bundle 里没有 display controller；从 ActionRouter 取 —— web_panel 暂不持有
        // 简化：用默认 ScreenPushOptions；v2 重构里会把 display controller 放进 bundle
        (void)disp;
        apxpc::media::ScreenPushOptions opt;
        opt.mirrorMode = false; opt.bitrateKbps = 8000;
        std::string err;
        const bool ok = b.screenPush->start(b.media.get(), opt, &err);
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
        const bool ok = b.audio->start(b.media.get(), opt, &err);
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
        return postResponse(p, id, okResp({{"adaptive", b.cfg->displayAdaptive}}));
    }
    if (name == "config.setBitrate" && b.cfg) {
        auto* v = payload.find("mbps");
        if (v) b.cfg->displayBitrateMbps = static_cast<uint32_t>(v->asInt(b.cfg->displayBitrateMbps));
        if (!b.configPath.empty()) config::saveConfig(b.configPath, *b.cfg);
        return postResponse(p, id, okResp({{"bitrateMbps", static_cast<double>(b.cfg->displayBitrateMbps)}}));
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
        bool ok = apxpc::tray::setAutostart(enable);
        return postResponse(p, id, okResp({{"ok", ok}}));
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
    wil::unique_cotaskmem jsonPtr;
    args->TryGetWebMessageAsString(&jsonPtr);
    if (!jsonPtr) return;
    std::wstring w(jsonPtr.get());
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

        // 创建 WebView2 —— 用 CreateCoreWebView2EnvironmentWithOptions 支持指定 WebView2 Runtime 路径
        //（便携场景：WebView2Loader.dll 在 exe 同目录，Runtime 也一起打包）。
        std::wstring dataDir;
        wchar_t exeDir[MAX_PATH] = {};
        if (::GetModuleFileNameW(nullptr, exeDir, MAX_PATH) > 0) {
            wchar_t* slash = wcsrchr(exeDir, L'\\');
            if (slash) *slash = L'\0';
            dataDir = std::wstring(exeDir) + L"\\apxwebview2-data";
        }

        auto envCallback = [p, exeDir, dataDir](HRESULT hr, ICoreWebView2Environment* env) {
            if (FAILED(hr)) {
                APX_LOGE("WebView2 环境创建失败: HRESULT=0x{:08X}", static_cast<unsigned>(hr));
                MessageBoxW(p->hwnd,
                    L"WebView2 初始化失败。\n\n"
                    L"请安装 WebView2 Runtime（Win10 2004+ 与 Win11 已自带；"
                    L"Win10 旧版请访问 https://aka.ms/webviewruntime 下载）。",
                    L"全能外设", MB_OK | MB_ICONERROR);
                p->running.store(false);
                ::PostMessageW(p->hwnd, WM_CLOSE, 0, 0);
                return;
            }
            p->env = env;
            auto createCb = [p](HRESULT hr2, ICoreWebView2* wv) {
                if (FAILED(hr2)) {
                    APX_LOGE("WebView2 创建失败: HRESULT=0x{:08X}", static_cast<unsigned>(hr2));
                    return;
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
                    Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                        [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                            onWebMessageReceived(nullptr, args);
                            return S_OK;
                        }).Get(), nullptr);

                // 加载前端 dist/index.html
                std::wstring wdir;
                if (GetModuleFileNameW(nullptr, exeDir, MAX_PATH) > 0) {
                    wchar_t* sl = wcsrchr(exeDir, L'\\');
                    if (sl) *sl = L'\0';
                    wdir = std::wstring(exeDir) + L"\\web\\index.html";
                } else {
                    wdir = L".\\web\\index.html";
                }
                APX_LOGI("WebView2 加载: {}", std::string(wdir.begin(), wdir.end()).c_str());
                wv->Navigate(wdir.c_str());

                // 事件回调：对端（手机/TV）请求本机输入 → 前端弹远程输入浮层
                // 回调跑在 9511 reader 线程；PostWebMessageAsJson 线程安全，直接调即可
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
            };
            env->CreateCoreWebView2Controller(p->hwnd, Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                [p, createCb](HRESULT hr3, ICoreWebView2Controller* controller) -> HRESULT {
                    if (FAILED(hr3) || !controller) {
                        APX_LOGE("CreateCoreWebView2Controller 失败: 0x{:08X}", static_cast<unsigned>(hr3));
                        return S_OK;
                    }
                    wil::com_ptr<ICoreWebView2> wv;
                    if (SUCCEEDED(controller->get_CoreWebView2(&wv)))
                        createCb(S_OK, wv.get());
                    return S_OK;
                }).Get(), nullptr);
        };
        // nullptr → 使用系统默认 WebView2 Runtime 路径
        HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
            nullptr, dataDir.empty() ? nullptr : dataDir.c_str(), nullptr,
            Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
                [p, envCallback](HRESULT h, ICoreWebView2Environment* e) -> HRESULT {
                    envCallback(h, e);
                    return S_OK;
                }).Get());
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
        if (p && p->webview) {
            wil::com_ptr<ICoreWebView2Controller> ctrl;
            if (SUCCEEDED(p->webview->get_Controller(&ctrl))) {
                RECT rc; ::GetClientRect(hwnd, &rc);
                ctrl->put_Bounds({0, 0, rc.right, rc.bottom});
            }
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
    // CreateCoreWebView2EnvironmentWithOptions 在无 WebView2 Runtime 时会同步失败
    // —— 简单探测：用系统默认路径创建临时环境，成功就算可用
    wil::com_ptr<ICoreWebView2Environment> env;
    const HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
        nullptr, nullptr, nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [&env](HRESULT h, ICoreWebView2Environment* e) -> HRESULT {
                if (SUCCEEDED(h) && e) env = e;
                return S_OK;
            }).Get());
    return SUCCEEDED(hr) && env;
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
