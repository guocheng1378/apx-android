#include "apxpc/wireless/wireless_session.hpp"

#include "apxpc/log.hpp"

#include <algorithm>
#include <chrono>
#include <string_view>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#endif

namespace apxpc::wireless {
namespace {

constexpr auto kTick = std::chrono::milliseconds(250);
constexpr uint16_t kBeaconPort = 9501;
// 重连退避（2026-11-24，学 Input Leap）：连接失败后从 2s 起指数翻倍、15s 封顶；
// 连上即清零。冷却期内不消费信标不发起连接 —— 信标持续刷新，冷却一过即用最新地址。
constexpr int64_t kReconnectBackoffMinMs = 2000;
constexpr int64_t kReconnectBackoffMaxMs = 15000;

#if defined(_WIN32)
using Sock = SOCKET;   // Windows 套接字句柄（原 wireless_link.hpp 里的别名，迁移后此处自带）

// 进程级 WSA 初始化（只做一次）。apxdesktop 单独运行（不跑 HttpServer / ctrl9511）时，
// 若此处不初始化，beaconLoop 里的 socket() 会返回 INVALID_SOCKET，发现线程直接退出，
// 而 beaconRun_ 仍为 true 不会重试 —— 表现为状态永远「正在发现」、连不上。
// 与 ctrl9511.cpp / media_session.cpp 各自持有一份 static，无副作用。
static void ensureWsa() {
    static bool once = [] { WSADATA d{}; WSAStartup(MAKEWORD(2, 2), &d); return true; }();
    (void)once;
}
#else
using Sock = int;      // POSIX 文件描述符
#endif

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

/// 解析 "APX1TV|APX1PC|APX1PH <name> <port> <token>"，返回 (host-ip, port)。
/// phone 出参：是否手机信标（APX1PH）—— 自动发现手机优先，见 onBeacon。
/// token 出参：信标第 4 段配对 token（docs/REQ-无线安全配对.md）；旧信标可无该段 = 空串。
bool parseBeacon(const char* buf, size_t n, std::string& host, uint16_t& port,
                 bool* phone = nullptr, std::string* token = nullptr) {
    // 简单按空格切分，取第 2、3 段（name、port）。host 由调用方从来源地址填入。
    std::string_view s(buf, n);
    // 必须以 "APX1TV"(TV) / "APX1PC"(PC) / "APX1PH"(手机被控) 开头 —— 三种前缀同为 6 字符。
    // 少认一种，那一类设备就会永远「正在发现」（手机改发 APX1PH 后就踩过这个坑）。
    if (s.size() < 6) return false;
    const std::string_view head = s.substr(0, 6);
    if (head != "APX1TV" && head != "APX1PC" && head != "APX1PH") return false;
    if (phone) *phone = (head == "APX1PH");
    // 找 name 与 port 两个 token
    size_t i = 6;
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t')) ++i;
    size_t name0 = i;
    while (i < s.size() && s[i] != ' ' && s[i] != '\t') ++i;
    size_t name1 = i;
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t')) ++i;
    size_t port0 = i;
    while (i < s.size() && s[i] != ' ' && s[i] != '\t') ++i;
    size_t port1 = i;
    if (name1 == name0 || port1 == port0) return false;
    std::string portStr(s.substr(port0, port1 - port0));
    try {
        port = static_cast<uint16_t>(std::stoi(portStr));
    } catch (...) {
        return false;
    }
    (void)name0;
    // 第 4 段 = 配对 token（可选段）：段存在才取，缺失保持空串（旧信标兼容）。
    if (token) {
        token->clear();
        size_t t0 = port1;
        while (t0 < s.size() && (s[t0] == ' ' || s[t0] == '\t')) ++t0;
        size_t t1 = t0;
        while (t1 < s.size() && s[t1] != ' ' && s[t1] != '\t') ++t1;
        if (t1 > t0) token->assign(s.substr(t0, t1 - t0));
    }
    return port != 0;
}

}  // namespace

const char* linkPhaseName(LinkPhase p) noexcept {
    switch (p) {
        case LinkPhase::Idle:        return "未启用";
        case LinkPhase::Discovering: return "正在发现设备";
        case LinkPhase::Connecting:  return "正在连接";
        case LinkPhase::Connected:   return "已连接";
        case LinkPhase::Failed:      return "连接失败";
        default:                     return "?";
    }
}

namespace {
// 远程输入注入净字符数：0x04 全量对账按它退格清场（只在 9511 reader 线程读写，无需锁）。
// DONE(0x27)/CANCEL(0x08)/新会话(0x25) 清零 —— 上一句定格，遥控页 0x04 不受残留影响。
size_t g_injectedLen = 0;
size_t utf8Chars(const std::string& s) {
    size_t n = 0;
    for (unsigned char c : s) if ((c & 0xC0) != 0x80) ++n;
    return n;
}
}  // namespace

WirelessSession::WirelessSession() {
    // 远程输入回传（0x26）：对端用户在手机/TV 上敲的字，就地注入**本机当前光标处**。
    // 回调跑在 9511 的 reader 线程；injectSystemText 内部是 SendInput / 剪贴板 + Ctrl+V，
    // 都是线程安全的，不需要绕回 UI 线程。
    client_.onRemoteInputText = [](const std::string& text, uint8_t flags) {
        // v184：BACKSPACE（0x02）此前被当作空文本直接吞掉 —— 手机端删除键失灵的根因
        if (flags & 0x02) {   // INPUT_FLAG_BACKSPACE（ApxFrame.kt 定义，PC 侧无此常量）
            // 0x08 = Windows VK_BACK：协议两侧按同一 VK 码空间解释（见 input_injector_*）
            apxpc::wireless::injectSpecialKey(0, 0x08);
            if (g_injectedLen > 0) --g_injectedLen;
            return;
        }
        if (flags & 0x08) { g_injectedLen = 0; return; }   // CANCEL：会话作废，计数归零
        if (flags & 0x04) {
            // 0x04 = 全量对账（替换语义）：手机端 TextWatcher 逐字增量是纯单向流，
            // 丢一帧对端永久漂移（用户报的「手机打的字和电脑上照不齐」）。手机发送时
            // 把整段快照发来，这里按已注入计数退格清场再重打，一次对账归零。
            // 此前 0x04 未实现、整段被当追加注入（所以历史注释禁发 COMMIT）——现在禁令解除。
            while (g_injectedLen > 0) { apxpc::wireless::injectSpecialKey(0, 0x08); --g_injectedLen; }
            // v223.29.33：退格排空后稍候再注入 —— 目标窗口消化上百个 VK_BACK 需要时间，
            // 紧接着的粘贴/键入抢在前面会与残留退格交错，表现为重打的字被吃掉一半（发
            // 送拍「先全删再粘」的乱感有一半来自这里）。
            std::this_thread::sleep_for(std::chrono::milliseconds(80));
        }
        if (!apxpc::wireless::injectSystemText(text)) {
            APX_LOGW("远程输入文本注入失败（{} 字节）", static_cast<unsigned>(text.size()));
            return;   // 注入失败不计数 —— 下一拍 0x04 对账会重打这段
        }
        g_injectedLen += utf8Chars(text);
    };
    // v184：0x28 特殊键/组合键（手机编辑快捷键排：方向键/Home/End/Del/Ctrl+A…）
    client_.onSpecialKey = [](uint8_t mod, uint8_t vk) {
        apxpc::wireless::injectSpecialKey(mod, vk);
    };
    client_.onRemoteInputDone = [] { g_injectedLen = 0; APX_LOGI("远程输入完成"); };
    // v184：反向剪贴板（0x21）—— 对端（手机 / TV）上复制的内容写进**本机剪贴板**。
    // 此前只有 CLI（apxhost ctrl-connect / ctrl-remote）挂了这条回调，
    // **桌面面板从不注册** —— 于是"在手机上复制、回 PC 粘贴"永远是空的，
    // 用户可见的症状是"剪贴板功能不对"。现在面板这条链路补齐。
    client_.onReverseClipboard = [](const std::string& text) {
        const bool ok = apxpc::wireless::setSystemClipboard(text);
        if (ok) APX_LOGI("反向剪贴板：已写入本机剪贴板（{} 字节）",
                         static_cast<unsigned>(text.size()));
        else APX_LOGW("反向剪贴板：写入本机剪贴板失败（{} 字节）",
                      static_cast<unsigned>(text.size()));
    };

    // 反向远程输入（0x25）：对端（手机 / TV）请**本机**输入 —— 三端对等的另一半。
    // 此前 client_.onRequestInput 从未注册：手机 / TV 喊电脑打字时，电脑完全没反应。
    // 回调同样跑在 reader 线程，这里只做一次带锁转发；**建窗口必须由 UI 层自己 Post**
    // （跨线程 CreateWindow 会消息泵错乱）。
    client_.onRequestInput = [this](const std::string& hint) {
        g_injectedLen = 0;   // 新会话开窗：清掉上一轮可能残留的注入计数（DONE 丢失时的保险）
        std::function<void(const std::string&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onRequestInput_;
        }
        if (cb) cb(hint);
    };

    // 通知同步转发（0x40/0x42，2026-11-11）：受控手机推送/消除通知 → 外层回调（托盘气泡）。
    // 这两条 lambda 必须在构造时挂上：Ctrl9511Client 在 connect() 时按值捕获 reader 回调，
    // 之后直接改 client_.onNotifyPush 不会生效；转发 lambda 每次带锁取最新回调就没这个坑。
    client_.onNotifyPush = [this](const std::string& key, const std::string& title,
                                  const std::string& text, const std::string& app) {
        std::function<void(const std::string&, const std::string&, const std::string&, const std::string&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onNotifyPush_;
        }
        if (cb) cb(key, title, text, app);
    };
    client_.onNotifyDismiss = [this](const std::string& key) {
        std::function<void(const std::string&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onNotifyDismiss_;
        }
        if (cb) cb(key);
    };

    // 通知回复回执转发（0x43，2026-11-14，能力位 bit1）：手机回 0x41 的终态 → 外层回调
    //（托盘气泡）。锁保护口径与通知同步同款 —— connect() 前挂转发 lambda，之后随时改。
    client_.onNotifyReplyResult = [this](uint8_t err) {
        std::function<void(uint8_t)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onNotifyReplyResult_;
        }
        if (cb) cb(err);
    };

    // 手机文件浏览转发（0x44–0x47，2026-11-11）：受控端回目录列表 / 取文件失败回执
    // → 外层回调（web_panel 事件）。锁保护口径与通知同步同款。
    client_.onPhoneFileList = [this](uint8_t err, const std::string& path,
                                     const std::vector<PhoneFileEntry>& entries) {
        std::function<void(uint8_t, const std::string&, const std::vector<PhoneFileEntry>&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onPhoneFileList_;
        }
        if (cb) cb(err, path, entries);
    };
    client_.onPhoneFileFail = [this](uint8_t err, const std::string& path) {
        std::function<void(uint8_t, const std::string&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onPhoneFileFail_;
        }
        if (cb) cb(err, path);
    };

    // 相册投影转发（0x48–0x4B，2026-11-12）：受控端回一页相册 / 缩略图失败回执
    // → 外层回调（web_panel 事件）。锁保护口径与手机文件同款。
    client_.onAlbumPage = [this](uint8_t err, uint32_t page,
                                 const std::vector<AlbumEntry>& entries) {
        std::function<void(uint8_t, uint32_t, const std::vector<AlbumEntry>&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onAlbumPage_;
        }
        if (cb) cb(err, page, entries);
    };
    client_.onAlbumPageFail = [this](uint8_t err, const std::string& path) {
        std::function<void(uint8_t, const std::string&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onAlbumPageFail_;
        }
        if (cb) cb(err, path);
    };

    // 令牌下发转发（0x36，2026-11-23）：被控端凭配对码首连后发来持久令牌 →
    // 先更新本会话握手用令牌（mu_），再转外层回调落盘配置。锁保护口径与相册同款。
    client_.onTokenGrant = [this](const std::string& token) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            grantedToken_ = token;
        }
        std::function<void(const std::string&)> cb;
        {
            std::lock_guard<std::mutex> lk(cbMu_);
            cb = onTokenGrant_;
        }
        if (cb) cb(token);
    };

    running_.store(true);
    worker_ = std::thread(&WirelessSession::worker, this);
}

WirelessSession::~WirelessSession() { stop(); }

void WirelessSession::stop() {
    if (!running_.exchange(false)) return;
    beaconRun_.store(false);
    {
        // 键鼠镜像要先于 client_.disconnect() 收掉：镜像的转发目标是 client_，
        // 连接先关会让正在飞的输入事件打在已断开的客户端上（虽不致命，但没意义）。
        // 持 mu_ 与 publish() 里的翻转互斥 —— worker 此刻最多在等锁，不会并发 stop。
        std::lock_guard<std::mutex> lk(mu_);
        if (inputMirror_) {
            inputMirror_->stop();
            inputMirror_.reset();
        }
    }
    client_.disconnect();
    // ★ 顺序不能反：worker 线程内部也会 join / 重建 beaconThread_（Idle、Manual、Auto
    // 三个分支都有），若这里先 join(beacon)，就会与 worker **并发 join 同一个
    // std::thread**（未定义行为，实践中表现为退出时偶发 terminate / 卡死）；而 worker
    // 重建时对仍 joinable 的 thread 赋值同样会 terminate。现在只 join worker（由它收
    // beacon），这里仅兜底，避免 beaconThread_ 析构时仍 joinable 触发 terminate。
    if (worker_.joinable()) worker_.join();
    if (beaconThread_.joinable()) beaconThread_.join();
}

void WirelessSession::startAuto() {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Auto, {}, 0, true};
    snap_.error.clear();
    manualFailed_ = false;
    haveBeacon_ = false;
    // 用户明确切自动发现 = 要立刻开始找：清退避（2026-11-24）
    connectBackoffMs_.store(0, std::memory_order_relaxed);
    nextConnectAttemptMs_.store(0, std::memory_order_relaxed);
    APX_LOGI("9511 会话：切到自动发现模式");
}

void WirelessSession::connectManual(const std::string& host, uint16_t port) {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Manual, host, port, true};
    snap_.error.clear();
    manualFailed_ = false;
    // 手动连接不走自动重连循环（attempt 一次性），退避位顺手清干净（2026-11-24）
    connectBackoffMs_.store(0, std::memory_order_relaxed);
    nextConnectAttemptMs_.store(0, std::memory_order_relaxed);
    APX_LOGI("9511 会话：手动连接 {}:{}", host.c_str(), static_cast<unsigned>(port));
}

void WirelessSession::disconnect() {
    std::lock_guard<std::mutex> lk(mu_);
    desire_ = Desire{Mode::Idle, {}, 0, true};
    snap_.error.clear();
    manualFailed_ = false;
}

void WirelessSession::onBeacon(const std::string& host, uint16_t port, bool phone,
                               const std::string& token) {
    std::lock_guard<std::mutex> lk(mu_);
    // 手机信标优先（修复⑫）：TV 端 app 常驻自启、持续广播 APX1TV，此前「先到先连」
    // 会在手机断链重发现时把会话绑到 TV 上 —— TV 连接稳定不掉线，自愈泵永不触发，
    // 媒体通道（含音频流）从此全推给电视，手机端饿死无声，且两端 UI 都显示「已连接」。
    // 规则：已有手机信标待连时不替换；已有非手机信标时允许手机信标插队顶替。
    if (haveBeacon_ && (beaconPhone_ || !phone)) return;
    beaconHost_ = host;
    beaconPort_ = port;
    beaconPhone_ = phone;
    beaconToken_ = token;   // 握手回带用：控制端 connect 时从信标 token 原样带回
    haveBeacon_ = true;
}

void WirelessSession::requestOpenScreen() {
    // 9511 受控端忽略 0x05；保留接口以兼容面板。
    client_.sendControl({0x05});
}

void WirelessSession::requestModule(int /*idx*/, bool /*on*/) {
    // 9511 控制面无模块开关子命令；保留接口以兼容面板。
}

bool WirelessSession::takeKeyFrameRequest() {
    // 9511 副屏走媒体通道，无需控制面关键帧请求。
    return false;
}

void WirelessSession::setTouchRect(int /*x*/, int /*y*/, int /*w*/, int /*h*/) {
    // 9511 控制面无触摸矩形概念；保留接口以兼容面板。
}

void WirelessSession::worker() {
    Mode mode = Mode::Idle;
    std::string host;
    uint16_t port = 0;
    bool attempt = false;

    while (running_.load()) {
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (desire_.fresh) {
                desire_.fresh = false;
                mode = desire_.mode;
                host = desire_.host;
                port = desire_.port;
                attempt = (mode != Mode::Idle);
                haveBeacon_ = false;
                beaconToken_.clear();   // 换目标时清残留：token 只随当次信标有效
                manualFailed_ = false;
                connectStartMs_ = 0;
            }
        }

        if (mode == Mode::Idle) {
            if (client_.ready()) client_.disconnect();
            if (beaconRun_.load()) {
                beaconRun_.store(false);
                if (beaconThread_.joinable()) beaconThread_.join();
            }
            publish(LinkPhase::Idle, mode);
        } else if (mode == Mode::Auto) {
            if (client_.ready()) {
                publish(LinkPhase::Connected, mode);
                // 连上即清退避：下次断开后第一拍就能重试（2026-11-24）
                connectBackoffMs_.store(0, std::memory_order_relaxed);
                nextConnectAttemptMs_.store(0, std::memory_order_relaxed);
            } else {
                if (!beaconRun_.load()) {
                    if (beaconThread_.joinable()) beaconThread_.join();
                    // ★ shutdown 期间不能再起新 beacon：stop() 把 beaconRun_ 置 false、
                    //   旧线程退出后再 join 完，理论上本分支应该收摊；但若 worker 此时正
                    //   在这一步（stop 与 worker 在「beaconRun_ 拉 false → worker 进入
                    //   内层块」这条临界区交汇），下面 store(true) 会把 beaconRun_ 再拉起、
                    //   新建出来的 beacon 线程在 recvfrom 上挂着，stop() 末尾的
                    //   beaconThread_.join() 永远收不回 —— 进程无法正常退出（必须 kill）。
                    //   收完旧线程后再核一次 running_：已停就不重建，让本次迭代走完即退出。
                    if (!running_.load()) {
                        publish(LinkPhase::Idle, mode);
                    } else {
                        beaconRun_.store(true);
                        beaconThread_ = std::thread(&WirelessSession::beaconLoop, this);
                    }
                }
                // 重连退避（2026-11-24，学 Input Leap）：冷却期内不消费信标、不发起连接，
                // 只维持 Discovering 展示 —— 信标线程持续刷新最新地址，冷却一过即用。
                if (nowMs() < nextConnectAttemptMs_.load(std::memory_order_relaxed)) {
                    publish(LinkPhase::Discovering, mode);
                } else {
                    std::string bhost;
                    uint16_t bport = 0;
                    std::string btoken;
                    bool usedPairingCode = false;
                    {
                        std::lock_guard<std::mutex> lk(mu_);
                        // token 与 host/port 一起在锁内取出：待连信标是快照，三样必须同源
                        if (haveBeacon_) {
                            bhost = beaconHost_; bport = beaconPort_;
                            btoken = beaconToken_;
                            haveBeacon_ = false;
                        }
                        // 配对码交付令牌（2026-11-23）：面板刚提交的一次性码**优先**于信标
                        // 令牌与已落盘令牌 —— 用户明确要（重新）配对；信标令牌为空（隐藏
                        // 广播）时退回已落盘的对端持久令牌（0x36 下发过的）。
                        if (!pendingPairingCode_.empty()) {
                            btoken = pendingPairingCode_;
                            usedPairingCode = true;
                        } else if (btoken.empty() && !grantedToken_.empty()) {
                            btoken = grantedToken_;
                        }
                    }
                    if (!bhost.empty()) {
                        publish(LinkPhase::Connecting, mode);
                        // 回带信标 token：被控端启用校验时握手必经（docs/REQ-无线安全配对.md）
                        if (client_.connect(bhost, bport, btoken)) {
                            if (usedPairingCode) {
                                // 配对码连上即清空：一次性凭据不作重试储备（对端已作废该码）
                                std::lock_guard<std::mutex> lk(mu_);
                                pendingPairingCode_.clear();
                            }
                            connectedPeer_ = bhost + ":" + std::to_string(bport);
                            connectStartMs_ = nowMs();
                            publish(LinkPhase::Connected, mode);
                            // 连上即清退避（2026-11-24）
                            connectBackoffMs_.store(0, std::memory_order_relaxed);
                            nextConnectAttemptMs_.store(0, std::memory_order_relaxed);
                        } else {
                            publish(LinkPhase::Discovering, mode);
                            // 连接失败进退避：2s 起指数翻倍、15s 封顶（2026-11-24）
                            const int64_t prev =
                                connectBackoffMs_.load(std::memory_order_relaxed);
                            const int64_t next = std::min(
                                prev > 0 ? prev * 2 : kReconnectBackoffMinMs,
                                kReconnectBackoffMaxMs);
                            connectBackoffMs_.store(next, std::memory_order_relaxed);
                            nextConnectAttemptMs_.store(nowMs() + next,
                                                        std::memory_order_relaxed);
                            APX_LOGW("连接 {}:{} 失败，{}ms 后再试（重连退避）",
                                     bhost, static_cast<unsigned>(bport), next);
                        }
                    } else {
                        publish(LinkPhase::Discovering, mode);
                    }
                }
            }
        } else {  // Manual
            if (beaconRun_.load()) {
                beaconRun_.store(false);
                if (beaconThread_.joinable()) beaconThread_.join();
            }
            if (client_.ready()) {
                publish(LinkPhase::Connected, mode);
            } else if (attempt) {
                attempt = false;
                publish(LinkPhase::Connecting, mode);
                // 配对码交付令牌（2026-11-23）：手动连接同样优先用一次性配对码，其次
                // 已落盘的对端持久令牌；都没有才发空令牌（对端不鉴权时的老路径）。
                std::string mtok;
                bool usedPairingCode = false;
                {
                    std::lock_guard<std::mutex> lk(mu_);
                    if (!pendingPairingCode_.empty()) {
                        mtok = pendingPairingCode_;
                        usedPairingCode = true;
                    } else if (!grantedToken_.empty()) {
                        mtok = grantedToken_;
                    }
                }
                if (client_.connect(host, port, mtok)) {
                    if (usedPairingCode) {
                        std::lock_guard<std::mutex> lk(mu_);
                        pendingPairingCode_.clear();
                    }
                    connectedPeer_ = host + ":" + std::to_string(port);
                    connectStartMs_ = nowMs();
                    publish(LinkPhase::Connected, mode);
                } else {
                    manualFailed_ = true;
                    publish(LinkPhase::Failed, mode);
                }
            } else {
                publish(manualFailed_ ? LinkPhase::Failed : LinkPhase::Idle, mode);
            }
        }

        std::this_thread::sleep_for(kTick);
    }
}

void WirelessSession::publish(LinkPhase ph, Mode m) {
    const auto st = client_.status();
    std::lock_guard<std::mutex> lk(mu_);
    const bool changed = snap_.phase != ph;
    snap_.phase = ph;
    snap_.autoMode = (m == Mode::Auto);
    snap_.peer = st.connected ? connectedPeer_ : (m == Mode::Manual ? desire_.host : "");
    snap_.rttMs = st.rttMs;
    snap_.upMs = (connectStartMs_ > 0 && st.connected) ? (nowMs() - connectStartMs_) : 0;
    if (ph == LinkPhase::Failed) snap_.error = "连不上 " + desire_.host;
    else if (ph == LinkPhase::Connected) snap_.error.clear();
    snap_.counters.mouse = st.mouse;
    snap_.counters.touch = st.touch;
    snap_.counters.keyboard = st.keyboard;
    snap_.counters.consumer = st.consumer;
    snap_.counters.dropped = st.dropped;
    if (changed) APX_LOGI("9511 会话状态：{}", linkPhaseName(ph));

    // 键鼠镜像随相位翻转（2026-11-17）：连上受控端即把本机鼠标/键盘镜像过去，断开即停。
    // 只在相位变化时动作，避免每 250ms 一次的 publish 重复建停；全程持 mu_，与 stop()
    // 的收尾互斥，不会并发 stop 同一实例。
    // issue#171 #3：建镜像前先看总开关 —— 用户在面板关掉「连上即镜像键鼠」后，
    // 连接建立不再自动启动镜像（pad 不再被电脑端抢鼠标）。
    if (changed && ph == LinkPhase::Connected && !inputMirror_ && inputMirrorEnabled_.load()) {
        inputMirror_ = createPlatformCapturer(client_);
        if (inputMirror_ && inputMirror_->start()) {
            APX_LOGI("键鼠镜像已启动：本机鼠标/键盘 → 受控端（断开即停）");
        } else {
            // Windows 钩子失败 / Linux 无 root / macOS 无辅助功能授权：如实记日志后退回
            // 无镜像状态（与旧行为一致），不影响连接与其它功能。
            APX_LOGW("键鼠镜像启动失败：Windows 需消息钩子；macOS 需辅助功能；Linux 需 root");
            inputMirror_.reset();
        }
    } else if (changed && ph != LinkPhase::Connected && inputMirror_) {
        inputMirror_->stop();
        inputMirror_.reset();
        APX_LOGI("键鼠镜像已停止（会话离开 Connected）");
    }

    // 投屏到电脑自动开（2026-11-18）：电脑控手机连上的那一刻，自动下发 0x10「screenmirror」on
    // —— 此前电脑控手机默认没有手机画面（盲控），要自己去面板里翻「投屏到电脑」开关，
    // 而手机那头根本不知道该动它。现在连上即弹手机端系统授权页，点一下「立即开始」
    // 画面就上电脑面板。每次进程只自动发一次：用户手动关掉后不再自动开；已授权运行中
    // 手机端会按运行态跳过重复授权页，重连不打扰。
    if (changed && ph == LinkPhase::Connected && !autoScreenMirrorSent_) {
        autoScreenMirrorSent_ = true;
        if (client_.sendModuleToggle("screenmirror", true)) {
            APX_LOGI("已自动下发投屏开关：等手机端授权后画面上屏");
            // 修「点了立即开始面板没画面」（2026-11-24）：自动路径此前只发 0x10，
            // 没打开电脑端预览窗 —— 帧到 PC 后入队无人消费（消费线程只在预览窗/虚拟
            // 摄像头开着时才跑），队列满即丢。手动点面板开关之所以有画面，是因为
            // web_panel 的 toggle 里同步 setPreview(true)。这里补上同款动作：回调由
            // 接线方（面板）注册，Windows 开预览窗、mac 只同步面板开关态。
            // 注意：此刻持 mu_，回调不得再进 WirelessSession（见头文件声明）。
            std::function<void()> cb;
            {
                std::lock_guard<std::mutex> cbLk(cbMu_);
                cb = onScreenMirrorAuto_;
            }
            if (cb) cb();
        }
    }
}

SessionSnapshot WirelessSession::snapshot() const {
    std::lock_guard<std::mutex> lk(mu_);
    return snap_;
}

bool WirelessSession::sendClipboard(const std::string& text) {
    // client_ 自带线程安全（内部入队 + writer 线程），UI 线程可直接调，不必绕后台。
    if (!client_.ready()) return false;
    return client_.sendClipboard(text);
}

bool WirelessSession::sendModuleToggle(const std::string& id, bool on) {
    if (!client_.ready()) return false;
    return client_.sendModuleToggle(id, on);
}

// 键鼠镜像总开关（issue#171 #3）：关 = 立即停掉现有镜像，且连接建立后不再自动启动；
// 开 = 若当前已 Connected 且镜像未建，则立即补建（与 publish() 的建镜像路径同款）。
// 全程持 mu_，与 publish()/stop() 的镜像建停互斥；开关位本身是 atomic，读侧无锁。
void WirelessSession::setInputMirrorEnabled(bool on) {
    std::lock_guard<std::mutex> lk(mu_);
    const bool was = inputMirrorEnabled_.exchange(on);
    if (was == on) return;  // 状态没变，不动镜像
    if (!on) {
        if (inputMirror_) {
            inputMirror_->stop();
            inputMirror_.reset();
            APX_LOGI("键鼠镜像已停止（面板关闭总开关）");
        }
        return;
    }
    // on=true：仅当前确实连着才补建镜像（相位以 snap_ 为准，publish 每轮刷新）。
    if (snap_.phase == LinkPhase::Connected && !inputMirror_) {
        inputMirror_ = createPlatformCapturer(client_);
        if (inputMirror_ && inputMirror_->start()) {
            APX_LOGI("键鼠镜像已启动（面板开启总开关）");
        } else {
            APX_LOGW("键鼠镜像启动失败：Windows 需消息钩子；macOS 需辅助功能；Linux 需 root");
            inputMirror_.reset();
        }
    }
}

// 查找手机（0x38，2026-11-11，能力位 bit2）：转发给 9511 客户端（能力门控在其内部）。
bool WirelessSession::sendFindPhone(uint8_t action) {
    if (!client_.ready()) return false;
    return client_.sendFindPhone(action);
}

// 手机文件浏览发送（0x44/0x46，2026-11-11，能力位 bit3）：转发给 9511 客户端
//（未连接 / 对端不支持都返回 false，由调用方提示）。
bool WirelessSession::sendPhoneFileListReq(const std::string& path) {
    if (!client_.ready()) return false;
    return client_.sendPhoneFileListReq(path);
}

bool WirelessSession::sendPhoneFilePullReq(const std::string& path) {
    if (!client_.ready()) return false;
    return client_.sendPhoneFilePullReq(path);
}

// 相册投影发送（0x48/0x4A/0x4B，2026-11-12，能力位 bit4）：转发给 9511 客户端
//（未连接 / 对端不支持都返回 false，由调用方提示）。
bool WirelessSession::sendAlbumPageReq(uint32_t page) {
    if (!client_.ready()) return false;
    return client_.sendAlbumPageReq(page);
}

bool WirelessSession::sendAlbumWatchReq(bool on) {
    if (!client_.ready()) return false;
    return client_.sendAlbumWatchReq(on);
}

bool WirelessSession::sendAlbumThumbReq(uint32_t id) {
    if (!client_.ready()) return false;
    return client_.sendAlbumThumbReq(id);
}

bool WirelessSession::connected() const { return client_.ready(); }

bool WirelessSession::requestInput(const std::string& hint) {
    if (!client_.ready()) return false;
    return client_.sendRequestInput(hint);
}

void WirelessSession::setOnRequestInput(std::function<void(const std::string&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onRequestInput_ = std::move(cb);
}

// 通知同步（0x40/0x42，2026-11-11）：外层 setter 只改本类回调存储 —— 转发 lambda
// 早已挂在 client_ 上（见构造函数），这里晚赋值也能被下一次通知读到。
void WirelessSession::setOnNotifyPush(
    std::function<void(const std::string&, const std::string&, const std::string&, const std::string&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onNotifyPush_ = std::move(cb);
}

void WirelessSession::setOnNotifyDismiss(std::function<void(const std::string&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onNotifyDismiss_ = std::move(cb);
}

// 通知回复（0x41/0x43，2026-11-14，能力位 bit1）：转发给 9511 客户端（能力门控在其内部；
// 未连接 / 对端不支持都返回 false，由调用方提示）。回执 setter 锁保护口径同通知同步。
bool WirelessSession::sendNotifyReply(const std::string& key, const std::string& text) {
    if (!client_.ready()) return false;
    return client_.sendNotifyReply(key, text);
}

void WirelessSession::setOnNotifyReplyResult(std::function<void(uint8_t)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onNotifyReplyResult_ = std::move(cb);
}

// 手机文件浏览回调（0x45/0x47，2026-11-11）：外层 setter 只改本类回调存储 ——
// 转发 lambda 早已挂在 client_ 上（见构造函数），这里晚赋值也能被下一次应答读到。
void WirelessSession::setOnPhoneFileList(
    std::function<void(uint8_t, const std::string&, const std::vector<PhoneFileEntry>&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onPhoneFileList_ = std::move(cb);
}

void WirelessSession::setOnPhoneFileFail(std::function<void(uint8_t, const std::string&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onPhoneFileFail_ = std::move(cb);
}

// 相册投影回调（0x49/0x47，2026-11-12）：外层 setter 只改本类回调存储 ——
// 转发 lambda 早已挂在 client_ 上（见构造函数），这里晚赋值也能被下一次应答读到。
void WirelessSession::setOnAlbumPage(
    std::function<void(uint8_t, uint32_t, const std::vector<AlbumEntry>&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onAlbumPage_ = std::move(cb);
}

void WirelessSession::setOnAlbumPageFail(std::function<void(uint8_t, const std::string&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onAlbumPageFail_ = std::move(cb);
}

void WirelessSession::setOnTokenGrant(std::function<void(const std::string&)> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onTokenGrant_ = std::move(cb);
}

// 投屏自动开回调（2026-11-24）：setter 锁保护口径与令牌回调同款；
// 调用点在 publish() 的自动发 0x10 成功分支（worker 线程，持 mu_）。
void WirelessSession::setOnScreenMirrorAuto(std::function<void()> cb) {
    std::lock_guard<std::mutex> lk(cbMu_);
    onScreenMirrorAuto_ = std::move(cb);
}

void WirelessSession::setPairingCode(const std::string& code) {
    // 持 mu_：worker 连接时与信标 host/port/token 同锁取值，三样同源
    std::lock_guard<std::mutex> lk(mu_);
    pendingPairingCode_ = code;
    // 用户重提配对码 = 明确要立刻试：清退避，下一拍就发起连接（2026-11-24）
    connectBackoffMs_.store(0, std::memory_order_relaxed);
    nextConnectAttemptMs_.store(0, std::memory_order_relaxed);
    APX_LOGI("9511 会话：已记录一次性配对码，下一次连接尝试优先使用");
}

void WirelessSession::setStoredToken(const std::string& token) {
    std::lock_guard<std::mutex> lk(mu_);
    grantedToken_ = token;
}

bool WirelessSession::sendInputText(const std::string& text, uint8_t flags) {
    if (!client_.ready()) return false;
    return client_.sendInputText(text, flags);
}

bool WirelessSession::sendInputDone() {
    if (!client_.ready()) return false;
    return client_.sendInputDone();
}

#if defined(_WIN32)
/// 来源地址是否就是本机（网络字节序）。
/// 为什么必须有：本机的定向广播会被自己收到，不过滤就会把**自己的信标**当成对端，
/// 于是面板「已连接」到自己 —— 看着连上了，实际一开始就没连到手机。
bool isSelfAddress(uint32_t saddrNetOrder) {
    static uint32_t addrs[32] = {0};
    static int n = -1;
    static long long ms = 0;
    const long long now = nowMs();
    if (n < 0 || now - ms > 5000) {   // 5s 刷新：插拔网卡 / 换网随时可能变
        ms = now;
        n = 0;
        SOCKET s = socket(AF_INET, SOCK_DGRAM, 0);
        if (s != INVALID_SOCKET) {
            INTERFACE_INFO infos[32]{};
            DWORD bytes = 0;
            if (WSAIoctl(s, SIO_GET_INTERFACE_LIST, nullptr, 0, infos, sizeof(infos),
                         &bytes, nullptr, nullptr) == 0) {
                const size_t cnt = bytes / sizeof(INTERFACE_INFO);
                // INTERFACE_INFO::iiAddress 是 Windows 的 sockaddr_gen 联合体：
                //   union { struct sockaddr Address; struct sockaddr_in AddressIn;
                //           struct sockaddr_in6 AddressIn6; };
                // 取 IPv4 地址要读 AddressIn（Address 只是通用 sockaddr，没有 sin_addr），
                // 先用 sa_family 挡掉 IPv6 条目。
                for (size_t i = 0; i < cnt && n < 32; ++i) {
                    if (infos[i].iiAddress.Address.sa_family == AF_INET)
                        addrs[n++] = infos[i].iiAddress.AddressIn.sin_addr.s_addr;
                }
            }
            closesocket(s);
        }
    }
    for (int i = 0; i < n; ++i) if (addrs[i] == saddrNetOrder) return true;
    return false;
}
#endif

void WirelessSession::beaconLoop() {
#if defined(_WIN32)
    ensureWsa();   // 必须先初始化 Winsock，否则 socket() 失败导致发现永久失效
    Sock b = socket(AF_INET, SOCK_DGRAM, 0);
    if (b == INVALID_SOCKET) return;
    BOOL br = TRUE;
    setsockopt(b, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&br), sizeof(br));
#else
    int b = socket(AF_INET, SOCK_DGRAM, 0);
    if (b < 0) return;
    int br = 1; setsockopt(b, SOL_SOCKET, SO_BROADCAST, &br, sizeof(br));
    int reuse = 1;
    setsockopt(b, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
#endif
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kBeaconPort);
    addr.sin_addr.s_addr = INADDR_ANY;
    if (::bind(b, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
#if defined(_WIN32)
        closesocket(b);
#else
        close(b);
#endif
        return;
    }
    // ★ 接收必须有超时，否则 stop() 收不回 beacon 线程：
    //   本线程阻塞在 recvfrom 上时，stop() 只能置 beaconRun_ = false，没法打断系统调用；
    //   没有 SO_RCVTIMEO 时它会一直等到「有信标进来」才醒 —— 局域网里没别人广播就是永远。
    //   真机表现是退出程序卡死（必须 kill），CI 表现是 ctest 把
    //   wireless_session_lifecycle_test 判成 300s 超时。200ms 让循环能定期看 beaconRun_。
#if defined(_WIN32)
    DWORD rcvToMs = 200;
    setsockopt(b, SOL_SOCKET, SO_RCVTIMEO,
               reinterpret_cast<const char*>(&rcvToMs), sizeof(rcvToMs));
#else
    timeval rcvTo{};
    rcvTo.tv_sec = 0;
    rcvTo.tv_usec = 200 * 1000;
    setsockopt(b, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&rcvTo), sizeof(rcvTo));
#endif
    char buf[1024];
    while (beaconRun_.load()) {
        sockaddr_in from{};
#if defined(_WIN32)
        int fromLen = sizeof(from);
#else
        socklen_t fromLen = sizeof(from);
#endif
        const int n = recvfrom(b, buf, sizeof(buf) - 1, 0,
                               reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n <= 0) { std::this_thread::sleep_for(std::chrono::milliseconds(50)); continue; }
        buf[n] = '\0';
        std::string hostIp;
#if defined(_WIN32)
        char ipStr[INET_ADDRSTRLEN];
        if (inet_ntop(AF_INET, &from.sin_addr, ipStr, sizeof(ipStr))) hostIp = ipStr;
#else
        char ipStr[INET_ADDRSTRLEN];
        if (inet_ntop(AF_INET, &from.sin_addr, ipStr, sizeof(ipStr))) hostIp = ipStr;
#endif
        uint16_t bport = 0;
        std::string dummy;
        std::string btoken;
        bool phone = false;
        // 过滤自身信标：本机广播会回环回来，不过滤就会「连自己」（面板假装已连接，
        // 而手机永远连不上）。见 isSelfAddress 注释。
        if (!hostIp.empty() && parseBeacon(buf, static_cast<size_t>(n), dummy, bport, &phone, &btoken)
#if defined(_WIN32)
            && !isSelfAddress(from.sin_addr.s_addr)
#endif
        ) {
            onBeacon(hostIp, bport, phone, btoken);
        }
    }
#if defined(_WIN32)
    closesocket(b);
#else
    close(b);
#endif
}

}  // namespace apxpc::wireless
