// panel_http.cpp —— 浏览器面板后端（方案 B：本地 HTTP + React 前端）。
//
// 与 web_panel.cpp（WebView2 壳）同构 —— 协议、状态结构、动作分发完全一致：
//   GET  /api/state           → {"kind":"state","data":{…}}（前端 400ms 轮询；每次请求前触发媒体建链泵/USB 节流）
//   GET  /api/events?since=N  → {"events":[{…event…,"seq":N}]}（事件增量轮询，环形缓冲最近 256 条）
//   POST /api/act|query       → web_panel 同款 handleAct/query（响应体即 act-response/query-response 协议）
//   POST /api/upload?ip&port  → 浏览器面板的「互传发送」：body=文件原始字节，文件名走 X-File-Name 头，
//                               流式落盘到互传目录后进 LocalSend 发送队列（浏览器拿不到系统文件对话框，
//                               只能这么送；响应是 upload-response，发送结果仍走 on-localsend-sent 事件）
//   POST /api/open-file       → {"kind":"open-file-response","ok":false}（浏览器沙箱拿不到系统路径；前端 http 模式已自行拒绝，此处兕底）
//   GET  /*                   → 静态 serve 前端 dist（exe 旁 web/，防目录穿越）
//
// 设计取舍：
//   · 单线程 select 模型（1s 超时 tick 跑节流任务；请求顺序处理 —— 本地 JSON 毫秒级，无并发问题）
//   · 不做 SSE/长连接：事件与状态同用轮询，避免长连接占死 accept 循环（与 bridge.ts http 模式对应）
//   · 仅绑定 127.0.0.1（零外网暴露）；响应 Connection: close + Cache-Control: no-store
//   · Windows 上本文件编译为返回 -1 的 stub —— Web 面板走 web_panel.cpp，保持零交叉
//
// ScreenPush 边界（重要）：本入口的宿主链路（apxhost panel）**不创建、不链接**
// ScreenPush（apxpc_screen 库未链给 apxhost；虚拟显示器仅 Windows 可用）—— 因此本文件
// 刻意**不 include screen_push.hpp、不访问其成员**（否则编译/链接必缺符号）：
// buildState 填「未初始化形态」的 screen Json（避免前端未做 undefined 防护时崩溃，
// error 语义如实），handleAct 的 screen.toggle 无条件拒绝。若未来把 ScreenPush 挂进
// 本入口，需同步链接 apxpc_screen 并恢复真实读取。
//
// 互传（LocalSend）接线：本入口启动 bundle.localSend —— 落盘目录显式取
// FileReceiver::defaultDir()，与 file.list / file.delete 是同一个目录，于是互传来的文件
// 在「文件传输」面板里直接可见、也删得掉（file.delete 的白名单就是那个目录）。
// 动作：localsend.toggle（开关）/ localsend.status / localsend.openDir。
//
// 同步响应槽说明：file.send 在本实现里也走后台队列（9512 发送），但 HTTP 是一问一答，
// 连接不能挂着等大文件传完 —— act-response 先回「已入队」，结果走事件 on-file-send-done
// 推回前端（FilePanelModal 已在听）。FilePanelModal 的两条发送路（file.list 给出的接收目录
// 路径 / 上传转发）都通；其余 act 全部同步完成，响应槽仅在请求线程写入，无锁安全。

#include "apxpc/ui/panel_http.hpp"

#include "apxpc/autostart.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/discovery/discovery.hpp"
#include "apxpc/discovery/enumerator.hpp"
#include "apxpc/log.hpp"
#include "apxpc/log_export.hpp"
#include "apxpc/localsend/local_send_receiver.hpp"
#include "apxpc/localsend/local_send_sender.hpp"
#include "apxpc/net/json.hpp"
#include "apxpc/net/tcp_probe.hpp"
#include "apxpc/screen/sunshine_detect.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/wireless/ctrl9511.hpp"
// 手机上报的三种接入模式状态（0x31）：位布局与解码抽在头文件里，和 Android 侧对拍。
#include "apxpc/wireless/phone_mode.hpp"
#include "apxpc/wireless/file_receiver.hpp"
// file.send / 上传转发：9512 私有通道发送（sendFile / lastSendError）。
#include "apxpc/wireless/file_sender.hpp"
#include "apxpc/wireless/wireless_session.hpp"
#include "apxpc/media/media_session.hpp"
#include "apxpc/media/audio_capture.hpp"
#include "apxpc/media/mic_bridge.hpp"

#if !defined(_WIN32)

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <spawn.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// macOS libc 不经 <unistd.h> 导出 environ（requestRelaunch 的 posix_spawn 要用）；
// 必须在**全局作用域**声明 —— 写进 namespace 会变成 ui::environ，链接期抓到过。
extern char** environ;

namespace apxpc::ui {
namespace {

using apxpc::net::Json;
using apxpc::wireless::LinkPhase;

namespace fs = std::filesystem;

// ——— 时钟 ———
uint64_t steadyMs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

/* 托盘「退出」→ 面板线程：只跨线程传一个标志（托盘在自己的线程上，不能碰面板状态）。
   volatile sig_atomic_t 不够 —— 面板线程用 select 的 200ms 超时轮询，这里只要
   可见性，std::atomic 最省心。 */
std::atomic<bool> g_quitRequested{false};
// 面板地址：端口是 bind(0) 后由内核分配的，托盘（另一线程）在点击时读这里。
std::mutex g_urlMu;
std::string g_panelUrl;

// ——— 事件队列（轮询消费；push 可能来自接收线程/会话回调线程，需锁）———
struct EventEntry { long long seq = 0; std::string json; };
std::mutex g_evMu;
std::deque<EventEntry> g_events;
long long g_evSeq = 0;
constexpr size_t kEvCap = 256;   // 环形上限：轮询断开重连最多补看最近 256 条

void pushEvent(const std::string& name, Json data = Json::makeObject()) {
    Json e = Json::makeObject();
    std::lock_guard<std::mutex> lk(g_evMu);
    e["seq"] = static_cast<double>(++g_evSeq);
    e["kind"] = std::string("event");
    e["name"] = name;
    e["data"] = data;
    g_events.push_back({g_evSeq, e.stringify()});
    while (g_events.size() > kEvCap) g_events.pop_front();
}

// ——— HTTP 面板状态（与 web_panel.cpp 的 Panel 同构，去掉窗口/COM）———
// 9512 发送队列的一项：host = 手机 IP（connectedPhoneHost 给出），path = 本机文件路径。
struct FileSendItem { std::string host; std::string path; };

struct HttpPanel {
    HostBundle bundle;
    std::atomic<bool> running{false};
    std::string webDir;
    uint16_t port = 0;

    // 9511 受控端（对齐 Windows 桌面端 desktop_main）：广播 APX1PC 信标 —— 手机端
    // 「TV 控制」设备列表才能发现本机（标为 (PC)）。缺这条时浏览器面板只能
    // **主动连手机**（session 客户端方向），手机反过来发现不了本机，受控场景缺失。
    apxpc::wireless::Ctrl9511Server ctrlSrv;

    /* 9511 受控端是否**由本机另一个实例**提供（探测得名为「占用」，不改写状态）。
       面板启动前若探测到端口已被监听 → 不再重复起受控端，也不再打告警级日志。 */
    bool ctrlShared = false;

    // USB 传输探测节流（枚举较重，~10s 一次；macOS 无 /sys → enumerate 空 → 三态全 false，诚实）
    bool usbScanned = false;
    bool usbDevice = false, usbNcm = false, usbHid = false;
    std::string usbName, usbSpeed;
    uint64_t usbNextMs = 0;

    // 媒体建链泵（对齐 web_panel tickState）：控制连接就绪后节流发起媒体连接（9502），
    // 后台线程执行（connect 3s 级阻塞，不能卡 HTTP 循环）。
    std::thread mediaThread;
    std::atomic<bool> mediaBusy{false};
    uint64_t mediaNextMs = 0;

    // 互传发送线程（PC → 对端）：一次只跑一批，避免用户连点把几百 MB 同时压出去；
    // 结果走 on-localsend-sent 事件，act-response 先回「已开始」。
    std::thread sendThread;
    std::atomic<bool> sendBusy{false};

    // 9512 私有通道发送（file.send / 上传转发给手机）：队列 + 单 worker。
    // 大文件要传几十秒，不能卡住单线程的 accept 循环；结果走事件 on-file-send-done。
    // 线程持 HttpPanel*（栈对象）—— 清理段先 join 再出栈，与 sendThread 同规矩。
    std::thread fileSendThread;
    std::mutex fileSendMu;
    std::condition_variable fileSendCv;
    std::deque<FileSendItem> fileSendQueue;   // 待发文件，依次发（多文件不互相挤掉）
    bool fileSendWorkerUp = false;
    std::atomic<bool> fileSendQuit{false};

    // 同步响应槽（单请求线程读写；见文件头说明）
    Json resp;
    bool hasResp = false;

    void resetResp() { resp = Json(); hasResp = false; }
};
HttpPanel* g_hp = nullptr;

// ——— JSON 辅助（与 web_panel 同款）———
Json okResp(const Json& data = Json::makeObject()) {
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

// 响应必带 kind（bridge 只认 *-response；web_panel postResponse 同款大坑注释）
void postResponse(HttpPanel* p, const std::string& id, Json r, const char* kind = "act-response") {
    if (!r.find("kind")) r["kind"] = std::string(kind);
    r["id"] = id;
    p->resp = r;
    p->hasResp = true;
}

// ——— USB 传输探测（gadget 挂载诊断）———
void refreshUsbStatus(HttpPanel* p) {
    p->usbDevice = p->usbNcm = p->usbHid = false;
    p->usbName.clear();
    p->usbSpeed.clear();
    try {
        for (const auto& d : apxpc::usb::enumerateDevices()) {
            if (!p->usbDevice) {
                p->usbDevice = true;
                p->usbName = d.displayName.empty() ? d.vidPid() : d.displayName;
                p->usbSpeed = apxpc::discovery::speedName(d.speed);
            }
            if (d.has(apxpc::usb::Role::Ncm)) p->usbNcm = true;
            if (d.has(apxpc::usb::Role::Hid)) p->usbHid = true;
        }
        p->usbScanned = true;
    } catch (...) {
        APX_LOGW("USB 枚举失败，按未检测到设备呈现");
        p->usbScanned = true;
    }
}

// ——— 状态构建（与 web_panel.cpp buildState 逐字段一致；GetTickCount64 → steadyMs）———
void buildState(const HostBundle& b, Json& out, HttpPanel* p) {
    out = Json::makeObject();
    Json link = Json::makeObject();
    Json media = Json::makeObject();
    Json transports = Json::makeObject();
    Json counts = Json::makeObject();
    Json cfg = Json::makeObject();

    if (b.session) {
        const auto s = b.session->snapshot();
        // ★ phase 必须是数字：前端 phaseSentence `case 3:` 严格比较，字符串 "3" 永远落 default
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
        link["phase"] = static_cast<double>(LinkPhase::Idle);
        link["phaseName"] = std::string("idle");
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

    // screen：本入口不挂 ScreenPush（见文件头边界说明）——**刻意不读 b.screenPush**，
    // 否则要 include screen_push.hpp 且链接 apxpc_screen（apxhost 没链，符号必缺）。
    // 填「未初始化形态」而非缺字段：前端若未做 undefined 防护也不会崩，error 如实。
    {
        Json v = Json::makeObject();
        v["running"] = false;
        v["fps"] = 0;
        v["deviceName"] = std::string("");
        v["width"] = 0;
        v["height"] = 0;
        v["framesSent"] = 0;
        v["encodeMs"] = 0;
        v["dropped"] = 0;
        v["error"] = std::string("此平台不支持副屏（虚拟显示器仅 Windows）");
        // mac 的副屏出路 = 用户自装 Sunshine + Moonlight（ROADMAP §2.5）。
        // 检测是只读的：装没装、跑没跑、https 端口；面板据此给指引，不假装能开。
        const auto sun = apxpc::screen::sunshineStatus();
        v["sunshineInstalled"] = sun.installed;
        v["sunshineRunning"]   = sun.running;
        v["sunshineHttpsPort"] = static_cast<double>(sun.httpsPort);
        v["sunshineUrl"] = apxpc::screen::sunshineUrl(sun);
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

    if (b.cfg) {
        cfg["autostart"] = b.cfg->autostart;
        cfg["mode"] = b.cfg->mode;
        cfg["logLevel"] = b.cfg->logLevel;
        cfg["adaptiveBitrate"] = b.cfg->displayAdaptive;
        cfg["bitrateMbps"] = static_cast<double>(b.cfg->displayBitrateMbps);
        cfg["screenMode"] = static_cast<double>(b.cfg->displayEnabled ? 1 : 0);
        transports["btSupported"] = true;
    }

    if (p) {
        /* 手机上报的「无线 / 蓝牙 / USB 三模式可用性 + 当前在用哪条」（0x31，issue#47 面板侧诉求）。
           面板原来那三个开关里只有无线是真的（蓝牙与 USB 的真开关在手机端），拨了没反应
           只能被当成坏了 —— 现在照实显示手机报上来的状态；没上报就如实说没上报。 */
        const auto cs = p->ctrlSrv.status();
        const auto pm = apxpc::wireless::decodePhoneMode(cs.modeFlags);
        Json ph = Json::makeObject();
        ph["reported"] = cs.modeStatus > 0;
        ph["connected"] = cs.connected;
        ph["frames"] = static_cast<double>(cs.modeStatus);
        ph["wirelessAvailable"] = pm.wirelessAvailable;
        ph["bluetoothAvailable"] = pm.bluetoothAvailable;
        ph["usbAvailable"] = pm.usbAvailable;
        ph["active"] = pm.active;
        // ctrlShared：9511 由本机另一个实例提供 —— 本面板收不到 0x31 帧，phone 全零。
        // 如实标记，前端据此显示「经另一实例」，别误读成「手机未上报/坏了」。
        ph["shared"] = p->ctrlShared;
        transports["phone"] = ph;
        /* 注入权限（macOS 的「辅助功能」）：false 时手机发来的键鼠会被系统静默丢弃 ——
           面板必须把这件事说出来，否则用户只会认定「手机连上了但按什么都没反应」。 */
        transports["inputTrusted"] = cs.inputTrusted;
        /* 权限面板的显示名与进程身份由后端下发：macOS 26 起「辅助功能」改叫
           「设备控制与数据访问」（issue #52 第 1 条）—— 前端不写死，再改名也不用动前端。 */
        transports["inputTrustLabel"] = apxpc::wireless::inputTrustSettingsLabel();
        transports["inputProcKind"] =
            apxpc::wireless::runningFromAppBundle() ? "app-bundle" : "bare-binary";
        /* 注入进程身份：TCC 按进程/签名归属授权 —— 包里有 AllPeriph.app / 裸 apxdesktop /
           apxhost 三个各自 ad-hoc 签名的副本，用户在系统设置勾的条目很容易和实际注入的
           进程对不上。把 exe 路径亮出来，用户能对上「该勾哪一个」。 */
        transports["inputProc"] = apxpc::autostart::getExePath();
        /* 受控端归属（9511）：listening=本面板自己的受控端在跑；shared=端口被本机另一个
           AllPeriph 实例占着（受控端由它提供）。两者都 false 才是「本机没有受控端」。 */
        Json csj = Json::makeObject();
        csj["listening"] = cs.listening;
        csj["shared"] = p->ctrlShared;
        csj["connected"] = cs.connected;
        csj["error"] = cs.error;
        transports["ctrlServer"] = csj;
    }

    // 托盘状态（created/active/失败原因）：创建失败以前只有日志一行 LOGW —— 用户看面板
    // 只会觉得「app 没图标、连退出入口都没有」。回传面板后失败原因直接可见（真机报障
    // 「托盘不显示」时，这里就是唯一不用翻日志的诊断出口）。跨线程只读指针与原子量，安全。
    if (b.tray) {
        const auto t = b.tray->status();
        Json tj = Json::makeObject();
        tj["created"] = t.created;
        tj["active"] = t.active;
        tj["reason"] = t.reason;
        out["tray"] = tj;
    }

    if (p && p->usbScanned) {
        transports["usbDevice"] = p->usbDevice;
        transports["usbNcm"] = p->usbNcm;
        transports["usbHid"] = p->usbHid;
        transports["usbName"] = p->usbName;
        transports["usbSpeed"] = p->usbSpeed;
    }

    // 互传（LocalSend）：字段口径由 apxpc::localsend::statusJson 统一给出（单测钉住的那份），
    // 这里只负责塞进 state —— 两个面板不各写一遍，免得字段名漂移弄坏前端。
    if (b.localSend) {
        std::string lerr;
        out["localsend"] = Json::parse(apxpc::localsend::statusJson(b.localSend->snapshot()), &lerr);
    } else {
        // 未接线时也给「已关闭」的完整形态：前端按类型读，缺字段会显示成 undefined
        Json ls = Json::makeObject();
        ls["running"] = false;
        ls["port"] = 0;
        ls["dir"] = std::string("");
        ls["received"] = 0;
        ls["error"] = std::string("互传接收端未初始化");
        ls["warn"] = std::string("");
        ls["devices"] = Json::makeArray();
        out["localsend"] = ls;
    }

    out["link"] = link;
    out["media"] = media;
    out["transports"] = transports;
    out["counts"] = counts;
    out["config"] = cfg;
}

// ——— 媒体建链泵（与 web_panel tickState 内同款；connect 在后台线程，不卡 HTTP 循环）———
void mediaPumpTick(HttpPanel* p) {
    const HostBundle& b = p->bundle;
    if (!b.media || !b.session || b.media->status().connected) return;
    const auto snap = b.session->snapshot();
    const std::string peer = snap.peer;
    const size_t colon = peer.rfind(':');
    const std::string host = (colon == std::string::npos) ? peer : peer.substr(0, colon);
    if (snap.phase != LinkPhase::Connected || host.empty()) return;
    if (p->mediaBusy.load()) return;
    const uint64_t now = steadyMs();
    if (now < p->mediaNextMs) return;
    p->mediaNextMs = now + 4000;   // 失败 4s 后再试（同 web_panel）
    if (p->mediaThread.joinable()) p->mediaThread.join();
    p->mediaBusy.store(true);
    apxpc::media::MediaSession* media = b.media;   // 宿主栈对象，生命周期覆盖面板
    p->mediaThread = std::thread([p, media, host] {
        if (media->connect(host, apxpc::media::kMediaPort)) {
            APX_LOGI("媒体通道已连接 {}:{}", host.c_str(),
                     static_cast<unsigned>(apxpc::media::kMediaPort));
        }
        p->mediaBusy.store(false);
    });
}

// ——— 互传（LocalSend）———

/// 用系统文件管理器打开一个目录。fork + execlp，**不经 shell**：目录名来自宿主自己的
/// 默认目录，但拼接进命令行总是坏习惯，这里从根上避开注入与引号问题。
/// 子进程 exec 失败只 _exit(127)（异步信号安全），绝不把面板进程带下去。
void openDirInFileManager(const std::string& dir) {
#if defined(__APPLE__)
    const char* tool = "open";
#else
    const char* tool = "xdg-open";
#endif
    const pid_t pid = ::fork();
    if (pid < 0) {
        APX_LOGW("打开目录失败（fork 失败）: {}", dir.c_str());
        return;
    }
    if (pid == 0) {
        ::execlp(tool, tool, dir.c_str(), static_cast<char*>(nullptr));
        ::_exit(127);
    }
    APX_LOGI("已请求打开互传目录: {}", dir.c_str());
}

/// 启动互传接收端。落盘目录显式取 FileReceiver::defaultDir() —— 与 file.list / file.delete
/// 同一目录，互传来的文件在「文件传输」面板里直接可见、也删得掉；不各写一份默认值。
bool startLocalSend(const HostBundle& b) {
    if (!b.localSend) return false;
    if (b.localSend->running()) return true;   // 幂等：重复点开关不会撞端口

    apxpc::localsend::Options opt;
    opt.dir = apxpc::wireless::FileReceiver::defaultDir();
    const bool ok = b.localSend->start(
        opt, [](const std::string& path, uint64_t size, const std::string& from) {
            if (g_hp && g_hp->bundle.tray) {
                const size_t slash = path.find_last_of('/');
                const std::string name =
                    (slash == std::string::npos) ? path : path.substr(slash + 1);
                g_hp->bundle.tray->notify("收到文件", from + " 发来 " + name);
            }
            Json d = Json::makeObject();
            d["path"] = path;
            d["size"] = static_cast<double>(size);
            d["from"] = from;
            d["via"] = std::string("localsend");
            pushEvent("on-localsend-received", d);
        });
    if (ok)
        APX_LOGI("互传接收端已启动：端口 {}", std::to_string(b.localSend->listenPort()).c_str());
    else
        APX_LOGW("互传接收端启动失败：{}", b.localSend->lastError().c_str());
    return ok;
}

/// 互传状态 → 响应体（toggle / status 共用；返回的就是 state 里那一段）
Json localSendStatusJson(const HostBundle& b) {
    std::string err;
    return Json::parse(apxpc::localsend::statusJson(b.localSend->snapshot()), &err);
}

// ——— 动作分发（与 web_panel.cpp handleAct 逐分支一致；Win 专属分支按平台能力改写）———

/// 把一批本机文件投进 LocalSend 发送队列。
/// 在后台线程发（大文件要传几十秒，不能卡住单线程的 accept 循环），结果通过
/// on-localsend-sent 事件推回前端，调用方先回「已开始」。
/// 返回 false = 上一批还在发送中（调用方据此回 failResp）。
/// 两个入口共用：动作 localsend.send（桌面壳选了路径）与 POST /api/upload（浏览器上传落盘后）。
bool startLocalSendToPeer(HttpPanel* p, const std::string& ip, uint16_t port,
                          const std::vector<std::string>& paths) {
    if (p->sendBusy.exchange(true)) return false;
    apxpc::localsend::Target target;
    target.ip = ip;
    target.port = port;
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
        pushEvent("on-localsend-sent", d);
    });
    return true;
}

// ——— 9512 私有通道发送（与 web_panel 的 startFileSend 同链路，响应语义改成事件）———

// 当前连接的手机：先看会话（PC 主动连手机那条方向，与 web_panel 的 startFileSend 同源）；
// 会话没连再看受控端（手机连进本机 9511 的方向）—— 发 9512 文件过来的就是它，IP 从受控端状态拿。
// 两个方向都没有 → 返回空串，调用方如实回「未连接手机」。
std::string connectedPhoneHost(HttpPanel* p) {
    if (p->bundle.session) {
        const auto s = p->bundle.session->snapshot();
        if (s.phase == LinkPhase::Connected) {
            const size_t colon = s.peer.rfind(':');
            return (colon == std::string::npos) ? s.peer : s.peer.substr(0, colon);
        }
    }
    const auto cs = p->ctrlSrv.status();
    if (cs.connected && !cs.peer.empty()) {
        const size_t colon = cs.peer.rfind(':');
        return (colon == std::string::npos) ? cs.peer : cs.peer.substr(0, colon);
    }
    return {};
}

// worker：逐个发队列里的文件（进度/结果都走事件，前端 400ms 轮询 /api/events 收）。
// sendFile 的进度回调在调用线程执行 —— 就是本 worker 线程，lastPct 局部量安全。
void fileSendWorker(HttpPanel* p) {
    for (;;) {
        FileSendItem it;
        {
            std::unique_lock<std::mutex> lk(p->fileSendMu);
            p->fileSendCv.wait(lk, [&] {
                return !p->fileSendQueue.empty() || p->fileSendQuit.load();
            });
            if (p->fileSendQueue.empty()) return;   // quit 且队列空：收工
            it = std::move(p->fileSendQueue.front());
            p->fileSendQueue.pop_front();
        }
        int lastPct = -1;
        const bool ok = apxpc::wireless::sendFile(it.host, 9512, it.path,
            [p, &it, &lastPct](int pct) {
                if (pct >= 100 || pct - lastPct >= 10) {
                    lastPct = pct;
                    Json d = Json::makeObject();
                    d["path"] = it.path;
                    d["percent"] = static_cast<double>(pct);
                    pushEvent("on-file-send-progress", d);
                }
            });
        const std::string err = ok ? std::string() : apxpc::wireless::lastSendError();
        if (ok) APX_LOGI("文件已发送到手机 {}: {}", it.host.c_str(), it.path.c_str());
        else    APX_LOGW("发送文件失败: {}（{}）", err.c_str(), it.path.c_str());
        Json d = Json::makeObject();
        d["ok"] = ok;
        d["path"] = it.path;
        d["peer"] = it.host;
        d["via"] = std::string("9512");
        if (!ok) d["error"] = err.empty() ? std::string("发送文件失败") : err;
        pushEvent("on-file-send-done", d);
    }
}

// 入队（懒启动 worker；多文件依次发，不互相挤掉 —— 与互传发送的语义对齐）。
void enqueueFileSend(HttpPanel* p, const std::string& host, const std::string& path) {
    {
        std::lock_guard<std::mutex> lk(p->fileSendMu);
        p->fileSendQueue.push_back({host, path});
        if (!p->fileSendWorkerUp) {
            p->fileSendWorkerUp = true;
            p->fileSendThread = std::thread([p] { fileSendWorker(p); });
        }
    }
    p->fileSendCv.notify_all();
}

void handleAct(HttpPanel* p, const std::string& id, const std::string& name, const Json& payload) {
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

    // —— 副屏（本入口不挂 ScreenPush，见文件头边界说明：无条件如实拒绝，
    //     刻意不访问 ScreenPush 成员 —— apxhost 未链接 apxpc_screen，成员访问会缺符号）——
    if (name == "screen.toggle") {
        return postResponse(p, id, failResp("此平台不支持副屏（虚拟显示器仅 Windows）"));
    }
    // —— 副屏：mac 的出路是用户自装 Sunshine + Moonlight（结论见 ROADMAP §2.5）——
    //    我们不内嵌（Moonlight 客户端 GPLv3 与 MIT 冲突），只在面板做**检测 + 指引**。
    if (name == "screen.sunshine") {
        auto d = apxpc::screen::sunshineStatus();
        Json v = Json::makeObject();
        v["installed"] = d.installed;
        v["running"]   = d.running;
        v["httpsPort"] = static_cast<double>(d.httpsPort);
        v["url"] = apxpc::screen::sunshineUrl(d);
        return postResponse(p, id, okResp(v));
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
        return postResponse(p, id, ok ? okResp()
                                      : failResp(err.empty() ? "音箱启动失败" : err));
    }

    // —— 麦克风桥（手机麦 → 本机播放设备）——
    if (name == "mic.toggle" && b.micBridge) {
        if (b.micBridge->running()) { b.micBridge->stop(); return postResponse(p, id, okResp()); }
        std::string devId = payload.find("deviceId") ? payload.find("deviceId")->asString() : "";
        std::string err;
        const bool ok = b.micBridge->start(devId, &err);
        return postResponse(p, id, ok ? okResp()
                                      : failResp(err.empty() ? "麦克风桥启动失败" : err));
    }

    // —— 设置项 ——
    if (name == "config.setAdaptive" && b.cfg) {
        auto* v = payload.find("on");
        b.cfg->displayAdaptive = v ? v->asBool(b.cfg->displayAdaptive) : !b.cfg->displayAdaptive;
        if (!b.configPath.empty()) apxpc::config::saveConfig(b.configPath, *b.cfg);
        Json d1 = Json::makeObject(); d1["adaptive"] = b.cfg->displayAdaptive;
        return postResponse(p, id, okResp(d1));
    }
    if (name == "config.setBitrate" && b.cfg) {
        auto* v = payload.find("mbps");
        if (v) b.cfg->displayBitrateMbps = static_cast<uint32_t>(v->asInt(b.cfg->displayBitrateMbps));
        if (!b.configPath.empty()) apxpc::config::saveConfig(b.configPath, *b.cfg);
        Json d2 = Json::makeObject(); d2["bitrateMbps"] = static_cast<double>(b.cfg->displayBitrateMbps);
        return postResponse(p, id, okResp(d2));
    }
    if (name == "config.setLogLevel" && b.cfg) {
        auto* l = payload.find("level");
        if (l) b.cfg->logLevel = l->asString();
        if (!b.configPath.empty()) apxpc::config::saveConfig(b.configPath, *b.cfg);
        return postResponse(p, id, okResp());
    }

    // —— 日志：清单 + 按时间范围导出（issue #52 建议 5）——
    // mac 端只有一个 apxdesktop 日志，报障者要的是「能选类型、能选文件夹、能按
    // 日期时间范围（精确到秒）挑一段导出」。时间戳解析与窗口过滤都在
    // apxpc::logexport 里（三端共用），这里只做信封转换。
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
        data["files"] = std::move(arr);
        return postResponse(p, id, okResp(data));
    }
    if (name == "log.export") {
        auto sval = [&](const char* k) -> std::string {
            const Json* n = payload.find(k);
            return n ? n->asString() : std::string();
        };
        auto ival = [&](const char* k) -> int64_t {
            const Json* n = payload.find(k);
            return n ? static_cast<int64_t>(n->asNumber(0)) : 0;
        };
        const std::string dir = sval("dir").empty() ? apxpc::logexport::defaultLogDir()
                                                    : sval("dir");
        const apxpc::logexport::Result r = apxpc::logexport::exportRange(
            dir, sval("dest"), sval("kind"), ival("since"), ival("until"));
        if (!r.ok) return postResponse(p, id, failResp(r.error));
        Json d = Json::makeObject();
        d["path"] = r.outPath;
        d["lines"] = static_cast<double>(r.lines);
        d["scanned"] = static_cast<double>(r.scanned);
        return postResponse(p, id, okResp(d));
    }

    // —— 窗口语义（浏览器无宿主窗口：hide=no-op；quit=停服务并退出）——
    if (name == "window.hide") {
        return postResponse(p, id, okResp());
    }
    if (name == "window.quit") {
        p->running.store(false);
        return postResponse(p, id, okResp());
    }

    // —— 开机自启（mac=LaunchAgent plist；Windows=注册表 —— 各自平台 autostart 实现）——
    if (name == "tray.setAutostart") {
        bool enable = payload.find("enable") ? payload.find("enable")->asBool(false) : false;
        const bool ok = apxpc::autostart::setAutostart(enable);
        if (ok && b.cfg) {
            b.cfg->autostart = enable;
            if (!b.configPath.empty()) apxpc::config::saveConfig(b.configPath, *b.cfg);
        }
        APX_LOGI("tray.setAutostart({}) → {}", enable ? "on" : "off", ok ? "success" : "failed");
        Json d3 = Json::makeObject(); d3["ok"] = ok; d3["autostart"] = enable;
        return postResponse(p, id, okResp(d3));
    }

    // input.recheck：主动再检测一次输入注入权限。如实回状态、顺带把面板显示名带回；
    // TCC 对「已拒绝」的客户端不再弹窗 —— requestPlatformInputTrust 只在「未决定」时
    // 才能弹出来，真正的自救入口是 input.openSettings + app.relaunch（issue #52 第 1 条）。
    if (name == "input.recheck") {
        const bool trusted = apxpc::wireless::requestPlatformInputTrust();
        APX_LOGI("input.recheck → {}（{}）", trusted ? "trusted" : "not trusted",
                 trusted ? "已授权" : "若此前拒绝过，系统不会再次弹窗");
        Json dr = Json::makeObject(); dr["trusted"] = trusted;
        dr["label"] = apxpc::wireless::inputTrustSettingsLabel();
        return postResponse(p, id, okResp(dr));
    }

    // input.openSettings：替用户打开系统设置里对应的权限面板 —— TCC 拒绝过一次后
    // CGRequestPostEventAccess 不再弹窗，系统设置是唯一能改回决定的地方。
    if (name == "input.openSettings") {
        const bool opened = apxpc::wireless::openPlatformInputSettings();
        Json d = Json::makeObject(); d["opened"] = opened;
        if (opened) return postResponse(p, id, okResp(d));
        return postResponse(p, id, failResp(std::string("无法自动打开系统设置，请手动打开：系统设置 → 隐私与安全性 → ") +
                                            apxpc::wireless::inputTrustSettingsLabel()));
    }

    // app.relaunch：Accessibility 授权在进程启动时读取 —— 勾完必须重启进程才生效。
    if (name == "app.relaunch") {
        requestRelaunch();
        return postResponse(p, id, okResp());
    }

    // —— 文件传输 ——
    // file.send：把本机文件发给当前连接的手机（9512 私有通道，与 web_panel 的 file.send 同链路）。
    // 合法路径只有 file.list 给出的那份（接收目录）—— 白名单与 file.delete 同一目录，
    // 收口在这防止别的本地页面借道把任意路径的文件推给手机。
    // 响应语义与 web_panel 不同：HTTP 是一问一答，连接不能挂着等大文件传完 ——
    // act-response 先回「已入队」，结果走事件 on-file-send-done（FilePanelModal 已在听）。
    if (name == "file.send") {
        const std::string path = payload.find("path") ? payload.find("path")->asString() : "";
        if (path.empty()) return postResponse(p, id, failResp("未指定文件"));
        std::error_code ec;
        fs::path fp = fs::weakly_canonical(fs::path(path), ec);
        if (ec) fp = fs::path(path);
        if (!fs::exists(fp, ec) || !fs::is_regular_file(fp, ec))
            return postResponse(p, id, failResp("文件不存在: " + path));
        {
            std::error_code ec1, ec2;
            const fs::path root = fs::weakly_canonical(
                fs::path(apxpc::wireless::FileReceiver::defaultDir()), ec1);
            fs::path target = fs::weakly_canonical(fs::path(path), ec2);
            if (ec1 || ec2) return postResponse(p, id, failResp("路径无效"));
            auto norm = [](std::string s) {
                std::replace(s.begin(), s.end(), '\\', '/');
                for (auto& c : s) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
                return s;
            };
            const std::string r8 = norm(root.generic_string());
            const std::string t8 = norm(target.generic_string());
            if (t8.size() <= r8.size() + 1 || t8.compare(0, r8.size(), r8) != 0 ||
                t8[r8.size()] != '/') {
                return postResponse(p, id, failResp("只允许发送收到的文件（接收目录内）"));
            }
        }
        const std::string host = connectedPhoneHost(p);
        if (host.empty())
            return postResponse(p, id, failResp("未连接手机（先建立控制连接，或让手机连入本机 9511 再发送）"));
        enqueueFileSend(p, host, fp.string());
        Json ds = Json::makeObject();
        ds["queued"] = true;
        ds["path"] = fp.string();
        APX_LOGI("file.send 已入队：{} → 手机 {}", fp.string().c_str(), host.c_str());
        return postResponse(p, id, okResp(ds));
    }
    if (name == "file.list") {
        // 列出收到的文件（与接收端同一 defaultDir），按修改时间新→旧
        struct Ent { std::string name; std::string path; long long size; long long ftick; };
        std::vector<Ent> ents;
        std::error_code ec;
        const fs::path dir = fs::path(apxpc::wireless::FileReceiver::defaultDir());
        if (!dir.empty() && fs::exists(dir, ec)) {
            fs::directory_iterator it(dir, ec), end;
            for (; !ec && it != end; it.increment(ec)) {
                std::error_code fec;
                if (!it->is_regular_file(fec)) continue;
                Ent e;
                // macOS 原生路径就是 UTF-8 字节串，直接 string() 无损（不走 Win 的 wide 转换）
                e.path  = it->path().string();
                e.name  = it->path().filename().string();
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
        // 白名单：只允许删接收目录内的文件 —— weakly_canonical 解析 ../ 与符号链接后比前缀，防穿越
        const std::string path = payload.find("path") ? payload.find("path")->asString() : "";
        if (path.empty()) return postResponse(p, id, failResp("未指定文件"));
        std::error_code ec1, ec2, ec3;
        const fs::path root = fs::weakly_canonical(
            fs::path(apxpc::wireless::FileReceiver::defaultDir()), ec1);
        fs::path target = fs::weakly_canonical(fs::path(path), ec2);
        if (ec1 || ec2) return postResponse(p, id, failResp("路径无效"));
        auto norm = [](std::string s) {
            std::replace(s.begin(), s.end(), '\\', '/');
            for (auto& c : s) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            return s;
        };
        const std::string r8 = norm(root.generic_string());
        const std::string t8 = norm(target.generic_string());
        if (t8.size() <= r8.size() + 1 || t8.compare(0, r8.size(), r8) != 0 ||
            t8[r8.size()] != '/') {
            return postResponse(p, id, failResp("只允许删除收到的文件（接收目录内）"));
        }
        if (!fs::remove(target, ec3) || ec3)
            return postResponse(p, id, failResp("删除失败: " + ec3.message()));
        APX_LOGI("已删除收到的文件: {}", path.c_str());
        return postResponse(p, id, okResp());
    }

    // —— 互传（LocalSend）：手机端「互传（LocalSend）」发现本机并直接发文件 ——
    // 与旧 9512 私有通道并存：9512 走「手机 ↔ 本工程」，这里是协议开放的那一半，
    // 官方 LocalSend 桌面版/macOS/iOS 也能发过来。
    if (name == "localsend.status") {
        if (!b.localSend) return postResponse(p, id, failResp("互传接收端未初始化"));
        return postResponse(p, id, okResp(localSendStatusJson(b)));
    }
    if (name == "localsend.toggle") {
        if (!b.localSend) return postResponse(p, id, failResp("互传接收端未初始化"));
        // 不带 enable 就按当前状态取反（前端开关自己算也行，这里做兜底）
        const Json* en = payload.find("enable");
        const bool enable = (en && en->isBool()) ? en->asBool() : !b.localSend->running();
        if (enable) {
            if (!startLocalSend(b))
                return postResponse(p, id, failResp("互传接收端启动失败：" + b.localSend->lastError()));
        } else {
            b.localSend->stop();
            APX_LOGI("互传接收端已停止");
        }
        return postResponse(p, id, okResp(localSendStatusJson(b)));
    }
    // localsend.send：把本机文件发给某台设备（PC → 手机 / 官方客户端）。
    // 在后台线程发（大文件要传几十秒，不能卡住单线程的 accept 循环），
    // 结果通过事件 on-localsend-sent 推回前端，act-response 先回「已开始」。
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
        if (!startLocalSendToPeer(p, ip, static_cast<uint16_t>(port), paths))
            return postResponse(p, id, failResp("上一批文件还在发送中"));
        return postResponse(p, id, okResp());
    }
    if (name == "localsend.openDir") {
        if (!b.localSend) return postResponse(p, id, failResp("互传接收端未初始化"));
        const std::string dir = b.localSend->snapshot().dir;
        openDirInFileManager(dir);
        Json d = Json::makeObject();
        d["dir"] = dir;
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

void handleQuery(HttpPanel* p, const std::string& id, const std::string& name) {
    if (name == "state") {
        Json s;
        buildState(p->bundle, s, p);
        return postResponse(p, id, okResp(s), "query-response");
    }
    // 播放设备列表（前端设备下拉）：Windows 有实体列表，macOS/Linux 返回空表
    if (name == "audioDevices") {
        return postResponse(p, id, okResp(audioDevicesJson()), "query-response");
    }
    postResponse(p, id, failResp("未知查询: " + name), "query-response");
}

// ——————————————————— HTTP 层 ———————————————————

std::string guessMime(const std::string& path) {
    auto ends = [&](const char* ext) {
        const size_t n = std::strlen(ext);
        return path.size() >= n && path.compare(path.size() - n, n, ext) == 0;
    };
    if (ends(".html")) return "text/html; charset=utf-8";
    if (ends(".js"))   return "text/javascript; charset=utf-8";
    if (ends(".css"))  return "text/css; charset=utf-8";
    if (ends(".json") || ends(".map")) return "application/json; charset=utf-8";
    if (ends(".svg"))  return "image/svg+xml";
    if (ends(".png"))  return "image/png";
    if (ends(".jpg") || ends(".jpeg")) return "image/jpeg";
    if (ends(".webp")) return "image/webp";
    if (ends(".ico"))  return "image/x-icon";
    if (ends(".woff2")) return "font/woff2";
    if (ends(".txt"))  return "text/plain; charset=utf-8";
    return "application/octet-stream";
}

int hexVal(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string percentDecode(const std::string& s) {
    std::string o;
    o.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '%' && i + 2 < s.size()) {
            const int hi = hexVal(s[i + 1]), lo = hexVal(s[i + 2]);
            if (hi >= 0 && lo >= 0) {
                o.push_back(static_cast<char>(hi * 16 + lo));
                i += 2;
                continue;
            }
        }
        o.push_back(s[i]);
    }
    return o;
}

struct Req {
    std::string method, path, query, body;
    bool ok = false;
    // —— 浏览器上传（POST /api/upload）用 ——
    // 这条路径的 body 可能是几百 MB：整段读进内存既浪费又会被下面的 512KB 闸门拒掉，
    // 所以 body 走「流式」——readRequest 只把头部之后顺手收到的那点字节放进 body，
    // 剩下的由 handleUpload 从 fd 继续读到文件里。
    bool streamBody = false;
    size_t contentLength = 0;   // 声明长度（流式路径用它判断剩余量）
    std::string fileName;       // X-File-Name 头（percent-encoded UTF-8）
};

/// 单个上传的大小上限。超了直接 413 —— 免得一颗没上限的 body 把磁盘写满。
constexpr size_t kMaxUploadBytes = 4ull * 1024 * 1024 * 1024;   // 4 GiB

Req readRequest(int fd) {
    Req r;
    std::string buf;
    char tmp[4096];
    size_t headerEnd = std::string::npos;
    while (headerEnd == std::string::npos) {
        const ssize_t n = ::recv(fd, tmp, sizeof(tmp), 0);
        if (n <= 0) return r;
        buf.append(tmp, static_cast<size_t>(n));
        if (buf.size() > 64 * 1024) return r;   // header 过大拒绝
        headerEnd = buf.find("\r\n\r\n");
    }
    const std::string head = buf.substr(0, headerEnd);
    std::string rest = buf.substr(headerEnd + 4);

    const size_t sp1 = head.find(' ');
    const size_t sp2 = head.find(' ', sp1 + 1);
    if (sp1 == std::string::npos || sp2 == std::string::npos) return r;
    r.method = head.substr(0, sp1);
    std::string uri = head.substr(sp1 + 1, sp2 - sp1 - 1);
    const size_t q = uri.find('?');
    if (q != std::string::npos) {
        r.query = uri.substr(q + 1);
        uri = uri.substr(0, q);
    }
    r.path = percentDecode(uri);

    std::string lowerHead = head;
    std::transform(lowerHead.begin(), lowerHead.end(), lowerHead.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    size_t contentLength = 0;
    const size_t cl = lowerHead.find("content-length:");
    if (cl != std::string::npos) {
        contentLength = static_cast<size_t>(
            std::strtoull(lowerHead.c_str() + cl + 15, nullptr, 10));
    }

    // 文件名头（浏览器上传：页面上的 <input type=file> 拿不到真实路径，只能把名字带过来）
    const size_t fn = lowerHead.find("x-file-name:");
    if (fn != std::string::npos) {
        const size_t lineEnd = head.find("\r\n", fn);
        std::string v = head.substr(fn + 12,
            (lineEnd == std::string::npos ? head.size() : lineEnd) - (fn + 12));
        const size_t b = v.find_first_not_of(" \t");
        const size_t e = v.find_last_not_of(" \t");
        v = (b == std::string::npos) ? std::string() : v.substr(b, e - b + 1);
        r.fileName = percentDecode(v);
    }

    // —— 上传：body 不在这里读 ——
    // 它可能有几百 MB，整段读进内存既浪费又会撞上面那道 512KB 闸门；
    // 这里只把「读完头顺手多收到的那点字节」交出去（body），其余由 handleUpload 从 fd 续读。
    if (r.method == "POST" && r.path == "/api/upload") {
        if (contentLength == 0 || contentLength > kMaxUploadBytes) return r;
        r.contentLength = contentLength;
        r.streamBody = true;
        r.body = rest;
        r.ok = true;
        return r;
    }

    if (contentLength > 512 * 1024) return r;   // body 过大拒绝
    while (rest.size() < contentLength) {
        const ssize_t n = ::recv(fd, tmp, sizeof(tmp), 0);
        if (n <= 0) return r;
        rest.append(tmp, static_cast<size_t>(n));
    }
    r.body = rest.substr(0, contentLength);
    r.ok = true;
    return r;
}

void writeAll(int fd, const std::string& data) {
    size_t off = 0;
    while (off < data.size()) {
        const ssize_t n = ::send(fd, data.data() + off, data.size() - off, MSG_NOSIGNAL);
        if (n <= 0) return;
        off += static_cast<size_t>(n);
    }
}

void respond(int fd, int code, const char* status, const std::string& contentType,
             const std::string& body) {
    char hdr[512];
    std::snprintf(hdr, sizeof(hdr),
                  "HTTP/1.1 %d %s\r\n"
                  "Content-Type: %s\r\n"
                  "Content-Length: %zu\r\n"
                  "Connection: close\r\n"
                  "Cache-Control: no-store\r\n"
                  "X-Content-Type-Options: nosniff\r\n"
                  "\r\n",
                  code, status, contentType.c_str(), body.size());
    std::string out = hdr;
    out += body;
    writeAll(fd, out);
}

void serveStatic(HttpPanel* p, int fd, const std::string& uriPath) {
    const fs::path root = fs::weakly_canonical(fs::path(p->webDir));
    std::string rel = uriPath;
    if (!rel.empty() && rel.front() == '/') rel.erase(0, 1);
    if (rel.empty()) rel = "index.html";
    if (rel.find("..") != std::string::npos)
        return respond(fd, 403, "Forbidden", "text/plain; charset=utf-8", "forbidden");

    fs::path full = root / rel;
    std::error_code dEc;
    if (fs::is_directory(full, dEc) && !dEc) full /= "index.html";
    std::error_code ec;
    const fs::path canon = fs::weakly_canonical(full, ec);
    if (ec)
        return respond(fd, 404, "Not Found", "text/plain; charset=utf-8", "not found");
    // 前缀防护（weakly_canonical 已解析符号链接，防目录穿越）
    const std::string rootS = root.generic_string();
    const std::string canonS = canon.generic_string();
    if (canonS != rootS &&
        (canonS.size() <= rootS.size() ||
         canonS.compare(0, rootS.size(), rootS) != 0 ||
         canonS[rootS.size()] != '/'))
        return respond(fd, 403, "Forbidden", "text/plain; charset=utf-8", "forbidden");

    if (!fs::is_regular_file(canon, ec) || ec) {
        // 首页缺失 → 前端未打包：给获取指引（诚实降级，不白屏）
        if (uriPath == "/" || uriPath == "/index.html") {
            const std::string tip =
                "<!doctype html><meta charset=utf-8><title>全能外设</title>"
                "<body style=\"font:16px system-ui;padding:40px;line-height:1.8\">"
                "<h2>前端未打包</h2>"
                "<p>此构建未附带 <code>web/</code> 目录。请使用 CI 打包的完整产物，"
                "或在 pc/web/ 执行 <code>npm install &amp;&amp; npm run build</code> "
                "后把 dist/ 内容拷到可执行文件旁的 web/ 目录。</p></body>";
            return respond(fd, 200, "OK", "text/html; charset=utf-8", tip);
        }
        return respond(fd, 404, "Not Found", "text/plain; charset=utf-8", "not found");
    }
    std::ifstream f(canon, std::ios::binary);
    if (!f)
        return respond(fd, 404, "Not Found", "text/plain; charset=utf-8", "not found");
    const std::string body((std::istreambuf_iterator<char>(f)),
                           std::istreambuf_iterator<char>());
    respond(fd, 200, "OK", guessMime(canon.generic_string()), body);
}

// ——— 协议信封分发（POST body → handleAct/handleQuery → 响应槽）———
void dispatchEnvelope(HttpPanel* p, const std::string& body, bool isAct) {
    p->resetResp();
    std::string err;
    auto j = Json::parse(body, &err);
    std::string idStr;
    if (!j.isNull()) {
        auto* id = j.find("id");
        idStr = id ? id->asString() : "";
    }
    if (j.isNull()) {
        Json r = failResp("请求 JSON 解析失败");
        r["kind"] = std::string(isAct ? "act-response" : "query-response");
        r["id"] = idStr;
        p->resp = r;
        p->hasResp = true;
        return;
    }
    auto* name = j.find("name");
    if (isAct) {
        auto* pl = j.find("payload");
        handleAct(p, idStr, name ? name->asString() : "",
                  pl ? *pl : Json::makeObject());
    } else {
        handleQuery(p, idStr, name ? name->asString() : "");
    }
    if (!p->hasResp) {   // 兑底：路径没设 resp（不应发生，保险让 bridge 正常 reject）
        Json r = failResp("请求处理失败（空响应）");
        r["kind"] = std::string(isAct ? "act-response" : "query-response");
        r["id"] = idStr;
        p->resp = r;
        p->hasResp = true;
    }
}

/// 取 URL 查询参数（`?ip=1.2.3.4&port=53317`）。找不到返回空串。
std::string queryValue(const std::string& query, const std::string& key) {
    const std::string pat = key + "=";
    size_t i = 0;
    while ((i = query.find(pat, i)) != std::string::npos) {
        // 只在参数边界上匹配，避免 "xip=" 命中 "ip="
        const bool atStart = (i == 0) || query[i - 1] == '&';
        if (atStart) {
            const size_t b = i + pat.size();
            const size_t e = query.find('&', b);
            return percentDecode(query.substr(b, (e == std::string::npos ? query.size() : e) - b));
        }
        i += pat.size();
    }
    return std::string();
}

/// 同名不覆盖：加 " (2)"、" (3)"…（与接收端 / 私有通道同策略，别把用户已有文件冲掉）。
fs::path dedupePath(const fs::path& dir, const std::string& name) {
    const fs::path first = dir / name;
    std::error_code ec;
    if (!fs::exists(first, ec)) return first;
    const std::string stem = first.stem().string();
    const std::string ext = first.extension().string();
    for (int i = 2; i < 1000; ++i) {
        const fs::path cand = dir / (stem + " (" + std::to_string(i) + ")" + ext);
        if (!fs::exists(cand, ec)) return cand;
    }
    return first;
}

/// 浏览器面板上传：`POST /api/upload?ip=..&port=..`（互传卡，LocalSend 定向）或
/// `POST /api/upload?target=file`（文件传输面板，发给当前连接的手机，走 9512 通道）。
/// body 是文件原始字节，文件名走 `X-File-Name` 头（percent-encoded UTF-8，
/// 页面上的 <input type=file> 拿不到真实路径）。
///
/// 为什么要有这条路：桌面壳（WebView2）的「选文件」是系统对话框，浏览器页面拿不到；
/// 于是 mac / 浏览器面板里「互传发送」以前只能报错。浏览器自带 <input type=file>，
/// 把选中的字节直接 POST 上来即可 —— 文件落进互传默认目录（与 file.list / 接收端同一目录，
/// 发出去的文件在这里也看得见），落完就进 LocalSend 发送队列。
///
/// 落盘用「先写 .part → 收齐再改名」：中途断线只留一个临时文件，不会出现
/// 半截文件顶着正式名字被别的程序读到。临时文件会删掉。
void handleUpload(HttpPanel* p, int fd, const Req& req) {
    const std::string ip = queryValue(req.query, "ip");
    const long portL = std::strtol(queryValue(req.query, "port").c_str(), nullptr, 10);
    std::string rawName = req.fileName.empty() ? queryValue(req.query, "name") : req.fileName;
    if (rawName.empty()) rawName = "upload.bin";

    auto fail = [&](int code, const std::string& msg) {
        Json r = Json::makeObject();
        r["kind"] = std::string("upload-response");
        r["ok"] = false;
        r["error"] = msg;
        respond(fd, code, code == 200 ? "OK" : "Bad Request",
                "application/json; charset=utf-8", r.stringify());
    };

    // target=file：发给当前连接的手机（FilePanelModal 浏览器模式的「发送文件到手机」）——
    // 不带 ip&port，目标由宿主的会话/受控端状态决定；ip&port 是互传卡那条路（LocalSend 定向）。
    const bool toPhone = queryValue(req.query, "target") == "file";
    if (!toPhone && (ip.empty() || portL <= 0 || portL > 65535)) {
        fail(400, "目标设备不合法（需要 ip 与 port，或 target=file）");
        return;
    }

    // 上传可能持续几十秒（大文件 + 慢 Wi-Fi），把接收超时放宽；连接默认 5s 是给一问一答用的。
    timeval tv{60, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    const fs::path dir = fs::path(apxpc::wireless::FileReceiver::defaultDir());
    std::error_code ec;
    fs::create_directories(dir, ec);
    const std::string safe = apxpc::localsend::LocalSendReceiver::sanitizeName(rawName);
    const fs::path finalPath = dedupePath(dir, safe.empty() ? std::string("upload.bin") : safe);
    const fs::path tmpPath = finalPath.string() + ".part";

    std::ofstream out(tmpPath, std::ios::binary | std::ios::trunc);
    if (!out) {
        fail(500, "无法写入互传目录：" + dir.string());
        return;
    }

    size_t written = 0;
    std::string chunk = req.body;    // readRequest 读完头之后顺手收到的那点字节
    if (chunk.size() > req.contentLength) chunk.resize(req.contentLength);
    if (!chunk.empty()) {
        out.write(chunk.data(), static_cast<std::streamsize>(chunk.size()));
        written = chunk.size();
    }
    char buf[64 * 1024];
    while (written < req.contentLength) {
        const size_t want = std::min(sizeof(buf), req.contentLength - written);
        const ssize_t n = ::recv(fd, buf, want, 0);
        if (n <= 0) break;   // 断线 / 超时：下面是「收齐了吗」的统一判断
        out.write(buf, static_cast<std::streamsize>(n));
        written += static_cast<size_t>(n);
    }
    out.flush();
    const bool streamOk = out.good();
    out.close();

    if (written != req.contentLength || !streamOk) {
        std::error_code rmEc;
        fs::remove(tmpPath, rmEc);
        APX_LOGW("上传中断：收到 {} / {} 字节，已丢弃临时文件", written, req.contentLength);
        fail(400, "上传中断（收到 " + std::to_string(written) + " / " +
                  std::to_string(req.contentLength) + " 字节）");
        return;
    }

    fs::rename(tmpPath, finalPath, ec);
    if (ec) {
        std::error_code rmEc;
        fs::remove(tmpPath, rmEc);
        fail(500, "落盘失败：" + ec.message());
        return;
    }
    if (toPhone) {
        const std::string host = connectedPhoneHost(p);
        if (host.empty()) {
            // 文件已落盘，不丢：如实说「没连手机」，用户连接后还能从面板发回去
            APX_LOGW("上传已落盘但未连接手机：{}（{} 字节）", finalPath.string().c_str(), written);
            Json r = Json::makeObject();
            r["kind"] = std::string("upload-response");
            r["ok"] = true;
            r["queued"] = false;
            r["name"] = finalPath.filename().string();
            r["path"] = finalPath.string();
            r["size"] = static_cast<double>(written);
            r["warn"] = std::string("未连接手机：文件已保存在互传目录，连接后可从「文件传输」面板发回手机");
            respond(fd, 200, "OK", "application/json; charset=utf-8", r.stringify());
            return;
        }
        enqueueFileSend(p, host, finalPath.string());
        APX_LOGI("浏览器上传已落盘：{}（{} 字节）→ 发送给手机 {}",
                 finalPath.string().c_str(), written, host.c_str());
        Json r = Json::makeObject();
        r["kind"] = std::string("upload-response");
        r["ok"] = true;
        r["queued"] = true;
        r["name"] = finalPath.filename().string();
        r["path"] = finalPath.string();
        r["size"] = static_cast<double>(written);
        respond(fd, 200, "OK", "application/json; charset=utf-8", r.stringify());
        return;
    }

    APX_LOGI("浏览器上传已落盘：{}（{} 字节）→ 发送给 {}:{}",
             finalPath.string().c_str(), written, ip.c_str(), static_cast<int>(portL));

    const bool queued = startLocalSendToPeer(p, ip, static_cast<uint16_t>(portL),
                                             {finalPath.string()});
    Json r = Json::makeObject();
    r["kind"] = std::string("upload-response");
    r["ok"] = true;
    r["queued"] = queued;
    r["name"] = finalPath.filename().string();
    r["path"] = finalPath.string();
    r["size"] = static_cast<double>(written);
    if (!queued) r["warn"] = std::string("上一批文件还在发送中，本次未入队（文件已保存在互传目录）");
    respond(fd, 200, "OK", "application/json; charset=utf-8", r.stringify());
}

void handleConnection(HttpPanel* p, int fd) {
    timeval tv{5, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    const Req req = readRequest(fd);
    if (!req.ok) { ::close(fd); return; }

    if (req.method == "GET" && req.path == "/api/state") {
        // 轮询触发节流任务（与 web_panel 的 400ms tick 等价；select tick 另有一份兑底）
        const uint64_t now = steadyMs();
        if (now >= p->usbNextMs) {
            refreshUsbStatus(p);
            p->usbNextMs = now + 10000;
        }
        mediaPumpTick(p);
        Json state;
        buildState(p->bundle, state, p);
        Json push = Json::makeObject();
        push["kind"] = std::string("state");
        push["t"] = static_cast<double>(steadyMs());
        push["data"] = state;
        respond(fd, 200, "OK", "application/json; charset=utf-8", push.stringify());
    } else if (req.method == "GET" && req.path == "/api/events") {
        long long since = 0;
        const size_t k = req.query.find("since=");
        if (k != std::string::npos)
            since = std::strtoll(req.query.c_str() + k + 6, nullptr, 10);
        Json arr = Json::makeArray();
        {
            std::lock_guard<std::mutex> lk(g_evMu);
            for (const auto& e : g_events) {
                if (e.seq <= since) continue;
                std::string perr;
                Json ev = Json::parse(e.json, &perr);
                if (!ev.isNull()) arr.push(ev);
            }
        }
        Json box = Json::makeObject();
        box["events"] = arr;
        respond(fd, 200, "OK", "application/json; charset=utf-8", box.stringify());
    } else if (req.method == "POST" && req.path == "/api/act") {
        dispatchEnvelope(p, req.body, true);
        respond(fd, 200, "OK", "application/json; charset=utf-8", p->resp.stringify());
    } else if (req.method == "POST" && req.path == "/api/query") {
        dispatchEnvelope(p, req.body, false);
        respond(fd, 200, "OK", "application/json; charset=utf-8", p->resp.stringify());
    } else if (req.method == "POST" && req.path == "/api/upload" && req.streamBody) {
        // 浏览器上传（互传发送）。body 由 handleUpload 从 fd 流式读，不经过上面的 512KB 闸门。
        handleUpload(p, fd, req);
    } else if (req.method == "POST" && req.path == "/api/open-file") {
        // 兑底（前端 http 模式已自行拒绝；协议内回应让漏网请求不悬挂）
        std::string err;
        auto j = Json::parse(req.body, &err);
        std::string idStr;
        if (!j.isNull()) {
            auto* id = j.find("id");
            idStr = id ? id->asString() : "";
        }
        Json r = Json::makeObject();
        r["kind"] = std::string("open-file-response");
        r["id"] = idStr;
        r["ok"] = false;
        respond(fd, 200, "OK", "application/json; charset=utf-8", r.stringify());
    } else if (req.method == "GET") {
        serveStatic(p, fd, req.path);
    } else {
        respond(fd, 405, "Method Not Allowed", "text/plain; charset=utf-8",
                "method not allowed");
    }
    ::close(fd);
}

}  // namespace

int runWebPanelHttp(const HostBundle& bundle, const std::string& webDir) {
    HttpPanel panel;
    panel.bundle = bundle;
    if (!webDir.empty()) {
        panel.webDir = webDir;
    } else {
        // exe 旁 web/（裸二进制约定）；没有时回退 bundle 内 Contents/Resources/web。
        // AllPeriph.app 里 exe 在 Contents/MacOS/，../Resources 即 Contents/Resources。
        // mac 包双击秒退修复的运行时另一半：web/ 不能进 Contents/MacOS/ —— codesign 把
        // MacOS/ 下一切当嵌套代码，.js 不可签（CI 实锤）；签名后再放又打破 CodeResources
        // seal → Apple Silicon 直接 SIGKILL。改放 Resources/ 随 seal 封印，这里补上查找。
        const std::string exe = apxpc::autostart::getExePath();
        const size_t slash = exe.find_last_of('/');
        const std::string exeDir =
            exe.empty() ? std::string(".")
                        : (slash == std::string::npos ? std::string(".") : exe.substr(0, slash));
        panel.webDir = exeDir + "/web";
        std::error_code ec;
        if (!fs::exists(panel.webDir, ec)) {
            const std::string bundled = exeDir + "/../Resources/web";
            std::error_code ec2;
            if (fs::exists(bundled, ec2)) {
                APX_LOGI("web/ 不在 exe 旁，改用 bundle 内 {}", bundled.c_str());
                panel.webDir = bundled;
            }
        }
    }
    g_hp = &panel;
    panel.running.store(true);

    // 启动时同步 autostart（runWebPanel 同款；mac=LaunchAgent plist 补写）
    if (panel.bundle.cfg && panel.bundle.cfg->autostart) {
        if (!apxpc::autostart::isAutostartEnabled()) {
            APX_LOGI("启动同步: config autostart=true 但自启项缺失，补写");
            apxpc::autostart::setAutostart(true);
        }
    }

    // 自动发现（runWebPanel 关键修复同款：缺这步永远 Idle 不收 APX1PH 信标）
    if (panel.bundle.session) {
        panel.bundle.session->startAuto();
        APX_LOGI("无线自动发现已启动（等待手机 APX1PH 信标）");
    }

    // 9511 受控端（对齐 Windows 桌面端 desktop_main 的既有语义）：广播 APX1PC 信标 ——
    // 手机端「TV 控制」的设备列表才能发现本机（标为 (PC)）。缺这条时浏览器面板只能
    // **主动连手机**（session 客户端方向），手机反过来发现不了本机 —— 受控场景缺失。
    // 主机名：POSIX gethostname（Win 版 desktop_main 用 GetComputerNameW 同义）。
    {
        /* 先探测：本机 9511 已经有人监听时，**不重复起**、也不打告警 ——
           那是「另一个 AllPeriph 实例正在提供受控端」，不是故障（v223.7 已交付：
           apxpc::net::tcp_probe 消除了双起告警）。仍然如实告诉用户受控端由谁提供，
           免得他以为是这个面板起的。 */
        if (apxpc::net::tcpPortRespondsLocal(9511, 200)) {
            panel.ctrlShared = true;
            APX_LOGI("9511 已有实例在跑（127.0.0.1:9511 可连）：本面板不再重复启动受控端，"
                     "受控端由那个实例提供；手机「TV 控制」里看到的仍是它");
        } else {
            char hname[256] = {};
            if (gethostname(hname, sizeof(hname) - 1) == 0 && hname[0] != '\0') {
                hname[sizeof(hname) - 1] = '\0';
                if (!panel.ctrlSrv.start(9511, "", hname)) {
                    /* 探测说没人、绑定却失败：这才是真冲突（抢跑 / 权限 / 端口保留）。
                       此时再探一次，把「已被占」与「绑定被拒」分开写清楚。 */
                    panel.ctrlShared = apxpc::net::tcpPortRespondsLocal(9511, 200);
                    const std::string cerr = panel.ctrlSrv.status().error;
                    if (panel.ctrlShared)
                        APX_LOGI("9511 在探测到绑定失败之间被别人占上了：受控端由该实例提供");
                    else
                        APX_LOGW("9511 受控端启动失败（{}）：手机将无法发现本机", cerr.c_str());
                } else {
                    APX_LOGI("9511 受控端已启动（面板自带，主机名 {}）", hname);
                }
            }
        }
    }

    // 麦克风收流接线：对齐老 Win32 面板（panel_win32）的既有语义 ——
    // streamId==kStreamMic 且麦克风桥在跑 → feed。回调在媒体收流线程上执行，
    // feed 自身加锁不阻塞 ✓。注：Win 的 web_panel 模式此前未接这条线（既有缺口，
    // 不在本 PR 范围），HTTP 面板补上。
    if (panel.bundle.media && panel.bundle.micBridge) {
        apxpc::media::MediaSession* media = panel.bundle.media;
        apxpc::media::MicBridge* mic = panel.bundle.micBridge;
        media->setHandler([mic](uint8_t streamId, uint8_t /*flags*/, uint32_t /*seq*/,
                                const uint8_t* body, size_t len) {
            if (streamId == apxpc::media::kStreamMic && mic->running()) {
                mic->feed(body, len);
            }
        });
    }

    // 文件接收（TCP 9512）：收到 → 托盘气泡（mac no-op）+ 推事件给前端文件面板
    if (panel.bundle.fileReceiver) {
        const bool ok = panel.bundle.fileReceiver->start(
            9512, "",
            [](const std::string& path, uint64_t) {
                if (g_hp && g_hp->bundle.tray) {
                    const size_t slash = path.find_last_of("/");
                    const std::string name =
                        slash == std::string::npos ? path : path.substr(slash + 1);
                    g_hp->bundle.tray->notify("收到文件", name);
                }
                Json d = Json::makeObject();
                d["path"] = path;
                pushEvent("on-file-received", d);
            });
        if (!ok)
            APX_LOGW("文件接收端启动失败：{}",
                     panel.bundle.fileReceiver->lastError().c_str());
    }

    // 互传（LocalSend）随面板一起起来（面板开了就该能被手机发现；用户也能从卡片关掉）
    if (panel.bundle.localSend) {
        if (!startLocalSend(panel.bundle))
            APX_LOGW("互传接收端未启动：{}", panel.bundle.localSend->lastError().c_str());
    } else {
        APX_LOGI("互传接收端未接线（bundle.localSend 为空）");
    }

    // 远程输入请求（0x25）→ 事件（前端弹浮层；回调可能来自 9511 reader 线程，pushEvent 有锁）
    if (panel.bundle.session) {
        panel.bundle.session->setOnRequestInput([](const std::string& hint) {
            Json d = Json::makeObject();
            d["hint"] = hint;
            pushEvent("on-remote-input-request", d);
        });
    }

    // —— 监听 127.0.0.1:0（临时端口，零外网暴露）——
    const int lfd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (lfd < 0) {
        APX_LOGE("socket 创建失败");
        g_hp = nullptr;
        return -1;
    }
    int reuse = 1;
    ::setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (::bind(lfd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
        ::listen(lfd, 16) != 0) {
        APX_LOGE("bind/listen 127.0.0.1 失败: errno={}", errno);
        ::close(lfd);
        g_hp = nullptr;
        return -1;
    }
    socklen_t alen = sizeof(addr);
    ::getsockname(lfd, reinterpret_cast<sockaddr*>(&addr), &alen);
    panel.port = ntohs(addr.sin_port);

    const std::string url = "http://127.0.0.1:" + std::to_string(panel.port) + "/?host=1";
    std::printf("\n[面板] 浏览器打开: %s\n", url.c_str());
    std::fflush(stdout);
    APX_LOGI("浏览器面板就绪: {}（webDir={}）", url.c_str(), panel.webDir.c_str());
    {
        std::lock_guard<std::mutex> g(g_urlMu);
        g_panelUrl = url;
    }

    // 自动打开浏览器（macOS `open`；其他 POSIX 仅打印地址）
#if defined(__APPLE__)
    (void)std::system(("open '" + url + "'").c_str());
#endif

    // 信号：SIGINT/SIGTERM → 优雅退出；SIGPIPE 忽略（写到断开的连接不崩）
    ::signal(SIGPIPE, SIG_IGN);
    struct sigaction sa {};
    sa.sa_handler = [](int) {
        if (g_hp) g_hp->running.store(false);
    };
    ::sigaction(SIGINT, &sa, nullptr);
    ::sigaction(SIGTERM, &sa, nullptr);

    // —— select 主循环（1s 超时 tick：USB 10s / 媒体泵 4s 节流任务的兑底时机）——
    while (panel.running.load() && !g_quitRequested.load()) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(lfd, &rfds);
        timeval tv{1, 0};
        const int r = ::select(lfd + 1, &rfds, nullptr, nullptr, &tv);
        if (r < 0) {
            if (errno == EINTR) continue;   // 信号打断（退出标志由 handler 置）
            break;
        }
        if (r == 0) {
            const uint64_t now = steadyMs();
            if (now >= panel.usbNextMs) {
                refreshUsbStatus(&panel);
                panel.usbNextMs = now + 10000;
            }
            mediaPumpTick(&panel);
            continue;
        }
        const int fd = ::accept(lfd, nullptr, nullptr);
        if (fd < 0) continue;
        handleConnection(&panel, fd);   // 单请求同步处理完即关（本地毫秒级）
    }

    // —— 清理：只 join 本文件创建的线程；宿主对象生命周期在调用方 ——
    ::close(lfd);
    panel.ctrlSrv.stop();   // 9511 受控端 + APX1PC 信标广播停止
    // 互传接收端也停掉：它的回调会 pushEvent，面板都撤了就不该再往里写
    if (panel.bundle.localSend) panel.bundle.localSend->stop();
    // 发送线程持 HttpPanel*（栈对象）：先 join 再出栈，否则发送中退出会悬空崩溃
    if (panel.sendThread.joinable()) panel.sendThread.join();
    if (panel.mediaThread.joinable()) panel.mediaThread.join();
    // 9512 发送 worker：置退出标志 → 手头文件发完（队列清空）就收工，再 join
    panel.fileSendQuit.store(true);
    panel.fileSendCv.notify_all();
    if (panel.fileSendThread.joinable()) panel.fileSendThread.join();
    g_hp = nullptr;
    {
        std::lock_guard<std::mutex> g(g_urlMu);
        g_panelUrl.clear();          // 端口已关，旧地址就是死链 —— 托盘别再往那儿 open
    }
    g_quitRequested.store(false);   // 清标志：同一进程内再次 runWebPanelHttp 不被上次带走
    APX_LOGI("浏览器面板已退出");
    return 0;
}

/* 面板上的「重启应用」（macOS，issue #52 第 1 条）：Accessibility 授权在进程启动时
   读取一次 —— 用户去系统设置勾完必须重启进程才生效。.app 内用 `open -n <bundle>` 重开
   （保活 LaunchServices 语义）；裸二进制 re-exec 自己。延时 1.2s 让本进程先把
   9511/9512/互传端口放掉，否则新实例 bind 必然失败（与「两处都起 9511」同款坑）。 */
void requestRelaunch() {
    const std::string exe = apxpc::autostart::getExePath();
    std::thread([exe] {
        std::this_thread::sleep_for(std::chrono::milliseconds(1200));
        const size_t pos = exe.find(".app/Contents/MacOS/");
        pid_t pid = 0;
        if (pos != std::string::npos) {
            const std::string bundle = exe.substr(0, pos + 4);
            char* const argv[] = { const_cast<char*>("/usr/bin/open"), const_cast<char*>("-n"),
                                   const_cast<char*>(bundle.c_str()), nullptr };
            if (::posix_spawn(&pid, "/usr/bin/open", nullptr, nullptr, argv, environ) != 0)
                APX_LOGW("重启失败：open -n {} 失败", bundle);
        } else {
            char* const argv[] = { const_cast<char*>(exe.c_str()), nullptr };
            if (::posix_spawn(&pid, exe.c_str(), nullptr, nullptr, argv, environ) != 0)
                APX_LOGW("重启失败：re-exec {} 失败", exe);
        }
    }).detach();
    g_quitRequested.store(true);
}

/* 托盘「退出」入口（本文件导出）：只置标志，真正的收尾在面板线程里按既有顺序做
   （停 9511 → 停互传 → join 工作线程 → 关监听）。托盘在自己的线程上，因此**不能**
   直接去 stop 这些对象 —— 那会与面板线程的收尾并发。 */
void requestPanelQuit() {
    g_quitRequested.store(true);
}

bool ctrlPortOccupied(uint16_t port, int timeoutMs) {
    return apxpc::net::tcpPortRespondsLocal(port, timeoutMs);
}

std::string panelUrl() {
    std::lock_guard<std::mutex> g(g_urlMu);
    return g_panelUrl;
}

}  // namespace apxpc::ui

#else  // _WIN32

namespace apxpc::ui {
int runWebPanelHttp(const HostBundle&, const std::string&) {
    return -1;   // Windows 走 web_panel.cpp（WebView2 壳），本实现不启用（零交叉）
}
// Win 侧同名符号的诚实桩：头文件里声明了它们，而 Windows 的托盘/受控端由
// desktop_main + web_panel 负责。给桩是为了「别处误引用时链接期报错而不是运行期崩」——
// requestPanelQuit 无面板可退，ctrlPortOccupied 在 Win 上没有调用方。
void requestPanelQuit() {}
void requestRelaunch() {}
bool ctrlPortOccupied(uint16_t, int) { return false; }
std::string panelUrl() { return {}; }   // Win 面板是 WebView2 窗口，没有「浏览器地址」
}  // namespace apxpc::ui

#endif  // !_WIN32
