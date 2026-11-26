#pragma once
// 统一控制面（9511）会话：把「发现 + 建链 + 断线回到等待」收成一处状态机。
//
// 这是原 9500 WirelessSession 的 9511 替换实现：底层受控端发现 / 连接 / 心跳全部
// 走 Ctrl9511Client（见 ctrl9511.hpp），不再依赖 9500 的 WirelessLink / BeaconListener。
// UI 线程只表达意图（startAuto / connectManual / disconnect），定时读 snapshot；
// 所有 socket I/O 在 worker 线程完成，绝不阻塞 UI。
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "apxpc/wireless/ctrl9511.hpp"
#include "apxpc/wireless/input_capture.hpp"

namespace apxpc::wireless {

enum class LinkPhase {
    Idle,          // 未启用（用户断开或还没点连接）
    Discovering,   // 自动模式：正在等受控端信标
    Connecting,    // 正在建链（≤3s）
    Connected,     // 已连上，输入已可注入
    Failed,        // 手动模式建链失败，等用户再点「连接」
};

const char* linkPhaseName(LinkPhase p) noexcept;

struct SessionSnapshot {
    struct Counters {
        uint64_t mouse = 0;
        uint64_t touch = 0;
        uint64_t keyboard = 0;
        uint64_t consumer = 0;
        uint64_t dropped = 0;
    };

    LinkPhase phase = LinkPhase::Idle;
    bool autoMode = true;
    std::string peer;          // host:port（已连上时）
    double rttMs = -1;         // 控制面心跳 RTT；-1 = 未测得
    long long upMs = 0;        // 已连接时长
    std::string error;         // 最近一次失败原因（空 = 无）
    Counters counters{};

    // 以下为 9500 副屏状态帧的上行字段；9511 控制面暂不携带，固定为「未知」，UI 如实显示。
    int phoneAudioState = -1;
    bool phoneAudioSpk = false;
    bool phoneAudioMic = false;
    uint32_t phoneAudioDropped = 0;
    bool phoneAudioKnown = false;
    int phoneModules[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
};

class WirelessSession {
public:
    WirelessSession();
    ~WirelessSession();

    WirelessSession(const WirelessSession&) = delete;
    WirelessSession& operator=(const WirelessSession&) = delete;

    /// 自动发现：监听 APX1TV UDP 信标（9501），发现即连；断线后自动回到等待。
    void startAuto();

    /// 手动：连指定地址（受控端 9511 服务端）。失败原因进 snapshot().error，不会自动重试。
    void connectManual(const std::string& host, uint16_t port);

    /// 主动断开，回到 Idle。
    void disconnect();

    /// 结束会话并回收后台线程（析构也会调）。
    void stop();

    /// 请求对端打开副屏页（9511 下无等价控制帧，发 0x05 由受控端忽略，no-op）。
    void requestOpenScreen();

    /// 模块开关命令（9511 下无等价；保留接口以兼容面板，no-op）。
    void requestModule(int idx, bool on);

    /// 副屏关键帧请求：9511 副屏走媒体通道，无需经控制面，返回 false（无待取请求）。
    bool takeKeyFrameRequest();

    /// 设置触摸映射目标矩形（9511 下无等价；保留接口以兼容面板，no-op）。
    void setTouchRect(int x, int y, int w, int h);

    /// 线程安全的状态快照。
    SessionSnapshot snapshot() const;

    /// 把文本作为剪贴板帧（0x20）发给**当前已连接**的受控端（手机 / TV）。
    /// 未连接、文本为空或超长（>64KB）返回 false —— 由调用方决定如何告知用户，
    /// 这里不弹窗也不静默假装成功。
    bool sendClipboard(const std::string& text);

    /// 模块开关（0x10，v184）：通知已连接的受控端挂起/恢复某模块（如 "wireless"）。
    /// 未连接返回 false，由调用方决定如何提示。
    bool sendModuleToggle(const std::string& id, bool on);

    /// 媒体档位配置（0x4E，issue#171 #2）：下发投屏/摄像头档位（分辨率/画质）。
    /// 未连接返回 false，由调用方决定如何提示；手机端落盘 prefs 并即时应用。
    bool sendMediaConfig(const std::string& id, const std::string& json);

    /// 键鼠镜像总开关（issue#171 #3）：on=false 立即停掉现有镜像且连接建立后不再自动启动；
    /// on=true 时若当前已 Connected 则立即启动镜像。线程安全（内部持 mu_，与 publish 同口径）。
    void setInputMirrorEnabled(bool on);

    /// 镜像开关的**实际生效状态**（issue#172 反馈「开关开着功能没启用」）：
    /// 镜像启动失败（macOS 无辅助功能等）时会话侧把开关位回退为 false，
    /// 面板状态读这里而不是配置文件，避免「假开」。线程安全（atomic 读）。
    bool inputMirrorEnabledActual() const { return inputMirrorEnabled_.load(std::memory_order_relaxed); }

    /// 键鼠镜像工作模式（issue#171 报告人「跨屏扩展」需求，2026-11-24）：
    /// on=false 镜像同步（默认，两端同时响应）；on=true 跨屏独占（鼠标推到屏幕右缘
    /// 切到手机，本机输入被吞掉独占发手机；往回移切回电脑）。对已建镜像即时生效；
    /// 之后新建的镜像（publish/setInputMirrorEnabled 路径）创建时带上该模式。
    /// 线程安全：模式位 atomic；对 inputMirror_ 的触碰持 mu_（与建停同口径）。
    void setInputCrossMode(bool on);

    /// 查找手机（0x38，2026-11-11，能力位 bit2）：让受控手机响铃（30s 自动停）。
    /// action：1=响铃 0=停，当前只有响铃一键。未连接返回 false（托盘菜单常驻，连着才有效）。
    bool sendFindPhone(uint8_t action);

    /// 手机文件浏览（0x44–0x47，2026-11-11，能力位 bit3）：向已连接的受控端请求目录列表 /
    /// 取走一个文件。未连接或对端不支持返回 false，由调用方提示。path 空 = 根入口列表。
    bool sendPhoneFileListReq(const std::string& path);
    bool sendPhoneFilePullReq(const std::string& path);

    /// 相册投影（0x48–0x4B，2026-11-12，能力位 bit4）：向已连接的受控端请求相册一页 /
    /// 开关新照片监听 / 按条目 id 请求缩略图（经 9512 推回 JPEG）。
    /// 未连接或对端不支持返回 false。page 从 0 起、按拍摄时间倒序。
    bool sendAlbumPageReq(uint32_t page);
    bool sendAlbumWatchReq(bool on);
    bool sendAlbumThumbReq(uint32_t id);

    /// 当前是否真的连着受控端。剪贴板这类“必须先有连接”的操作用它做前置判断。
    bool connected() const;

    /// 请求对端（手机 / TV）弹输入法输入文本（9511 帧 0x25）。
    /// hint 会显示在对方浮层上（告诉用户这段文字是给谁的）。未连接返回 false。
    /// 对方回传的文本（0x26）由本类在回调里直接注入本机光标处。
    bool requestInput(const std::string& hint);

    // ————— 反向：对端请求**本机**输入（三端对等） —————
    // 手机端有 RemoteInputActivity、TV 端有 RemoteInputOverlay 来接 0x25；
    // 电脑端此前**只有发没有收** —— client_.onRequestInput 从未被注册，
    // 手机/TV 请电脑打字时电脑毫无反应。这两个方法 + 下面的回调把这一半补上。

    /// 对端（手机 / TV）请求本机输入文本时触发（hint = 对方的提示语，如 "搜索"）。
    /// 线程：跑在 9511 reader 线程 —— UI 层**必须**自行 Post 到 UI 线程再建窗口。
    /// 用 setter 而非公开成员，是为了和 reader 线程之间有一把锁（避免赋值与调用竞争）。
    void setOnRequestInput(std::function<void(const std::string&)> cb);

    /// 把本机敲的字回传给请求方（0x26）。flags 见 PROTOCOL：
    /// 0x01=增量 0x02=退格 0x04=完整 0x08=取消。未连接返回 false。
    bool sendInputText(const std::string& text, uint8_t flags);

    /// 通知对端输入完成（0x27），对方据此收起浮层 / 落定文本。
    bool sendInputDone();

    // ————— 通知同步（0x40/0x42，2026-11-11） —————
    // PC 控手机时，手机是 9511 服务端，它的通知经**控制端 reader** 回传到这里；
    // 接线方（桌面壳/CLI 宿主）转成托盘气泡。回调跑在 9511 reader 线程。

    /// 受控手机推送通知（key=Android 通知键，title/text=标题/正文，app=应用展示名）。
    /// setOnNotifyPush 必须在**连接建立前**调用 —— Ctrl9511Client 的回调在 connect()
    /// 时被捕获；这里的转发 lambda 在构造时挂上，之后随时改回调都生效（带锁读取）。
    void setOnNotifyPush(std::function<void(const std::string& key, const std::string& title,
                                            const std::string& text, const std::string& app)> cb);
    /// 受控手机消除通知（key 与推送时一致；气泡无法撤回，当前仅转发记账）。
    void setOnNotifyDismiss(std::function<void(const std::string& key)> cb);

    /// 通知回复（0x41/0x43，2026-11-14，能力位 bit1）：向已连接的受控手机发 0x41
    /// 通知回复请求（PC 气泡点「回复」）。未连接 / 对端不支持返回 false；手机回 0x43
    /// 终态经 setOnNotifyReplyResult 回调（成功 err=0 也回帧——用户在等）。
    bool sendNotifyReply(const std::string& key, const std::string& text);
    /// 回执回调：err 与 Android ApxFrame.NOTIFY_REPLY_ERR_* 一致。setter 同款带锁转发。
    void setOnNotifyReplyResult(std::function<void(uint8_t err)> cb);

    // ————— 手机文件浏览（0x44–0x47，2026-11-11，能力位 bit3） —————
    // 受控端回目录列表（0x45）/ 取文件失败回执（0x47），经**控制端 reader** 到这里；
    // 接线方（桌面壳）转 web_panel 事件推给前端。回调跑在 9511 reader 线程，
    // 锁保护口径与通知同步同款 —— connect() 前挂转发 lambda，之后随时改回调都生效。

    /// 目录应答：err 与 Android ApxFrame.PHONEFILE_ERR_* 一致（0=成功），path 回显请求目录。
    void setOnPhoneFileList(std::function<void(uint8_t err, const std::string& path,
                                               const std::vector<PhoneFileEntry>& entries)> cb);
    /// 取文件失败回执（文件本体经 9512 成功推回时不发帧）。
    void setOnPhoneFileFail(std::function<void(uint8_t err, const std::string& path)> cb);

    // ————— 相册投影（0x48–0x4B，2026-11-12，能力位 bit4） —————
    // 受控端回一页相册（0x49）/ 缩略图失败回执（0x47，前缀 album:thumb:），经**控制端
    // reader** 到这里；接线方（桌面壳）转 web_panel 事件推给前端。锁保护口径同上。

    /// 相册分页应答：err 与 Android ApxFrame.PHONEFILE_ERR_* 一致（0=成功），page 回显请求页号。
    void setOnAlbumPage(std::function<void(uint8_t err, uint32_t page,
                                           const std::vector<AlbumEntry>& entries)> cb);
    /// 缩略图失败回执（缩略图经 9512 成功推回时不发帧；path 形如 "album:thumb:<id>.jpg"）。
    void setOnAlbumPageFail(std::function<void(uint8_t err, const std::string& path)> cb);

    // ————— 配对码交付令牌（0x36，2026-11-23，随「扫码/链接交付令牌」批加） —————
    // 信标隐藏令牌广播后，新设备凭被控端配对码页的 6 位一次性码完成首次握手；
    // 被控端随即经 0x36 把持久令牌发来，落盘后与信标路径同权。见 REQ-无线安全配对 §5。

    /// 设置一次性配对码（面板「配对码」输入框提交）：**下一次**连接尝试用它作握手令牌
    /// （优先于信标令牌与已存令牌），连上即清空；连不上保留，用户可重试或重填。
    /// 线程安全（内部持 mu_）。
    void setPairingCode(const std::string& code);

    /// 设置已落盘的对端持久令牌（启动时从配置读入）：信标令牌为空（隐藏广播/旧信标）
    /// 时用作握手令牌。0x36 收到新令牌时内部也会更新此值。线程安全（内部持 mu_）。
    void setStoredToken(const std::string& token);

    /// 令牌下发回调：被控端 0x36 发来持久令牌时触发（reader 线程）——接线方落盘配置。
    /// 会话内部已同步更新握手用令牌，这里只负责持久化。锁保护口径与通知回调同款。
    void setOnTokenGrant(std::function<void(const std::string& token)> cb);

    /// 投屏自动开回调（2026-11-24）：publish() 自动下发 0x10「screenmirror」on 成功后
    /// 触发（worker 线程）——接线方据此打开电脑端预览窗。此前只发开关不开预览窗，
    /// 帧入队无人消费，用户看到「点了立即开始面板却没画面」。回调在 mu_ 内同步执行，
    /// **不得**回调进 WirelessSession（会死锁）；只做轻量 UI 动作（如 setPreview）。
    void setOnScreenMirrorAuto(std::function<void()> cb);

private:
    enum class Mode { Idle, Auto, Manual };

    struct Desire {
        Mode mode = Mode::Idle;
        std::string host;
        uint16_t port = 0;
        bool fresh = false;
    };

    void worker();
    void publish(LinkPhase ph, Mode m);
    void onBeacon(const std::string& host, uint16_t port, bool phone,
                  const std::string& token);
    void beaconLoop();

    mutable std::mutex mu_;
    SessionSnapshot snap_;
    Desire desire_;
    bool haveBeacon_ = false;
    std::string beaconHost_;
    uint16_t beaconPort_ = 0;
    bool beaconPhone_ = false;   // 待连信标是否来自手机（APX1PH）—— 手机优先于 TV，见 onBeacon
    std::string beaconToken_;    // 待连信标的配对 token（信标第 4 段；旧信标可无 = 空串）
    bool manualFailed_ = false;

    std::atomic<bool> running_{false};
    std::thread worker_;
    Ctrl9511Client client_;

    // 本机键鼠镜像（2026-11-17，电脑→手机「遥控器」方向）：会话连上受控端即启动，
    // 把本机鼠标/键盘实时转发给对端；断开即停。此前只有 CLI（apxhost ctrl9511-remote）
    // 有这条链路，桌面面板从未接线 —— 面板文案写着「本机鼠标、键盘操作直接作用于手机」
    // 却是空头支票（用户反馈「电脑怎么控制手机，完全没界面」的直接根因）。
    // 生命周期完全跟随 Connected 相位翻转：在 publish() 里建/停，全程持 mu_。
    std::unique_ptr<LocalInputCapturer> inputMirror_;

    // 键鼠镜像总开关（issue#171 #3）：publish() 建镜像前检查；false = 不再自动启动。
    // atomic 读多写少，setter 里对「建/停镜像」的互斥由 mu_ 保证（与 publish 同口径）。
    // 兜底默认关（2026-11-26 作者拍板）：启动时由配置覆盖，未配置时不再默认开镜像。
    std::atomic<bool> inputMirrorEnabled_{false};

    // 键鼠镜像工作模式（2026-11-24 跨屏扩展）：false=镜像同步 true=跨屏独占。
    // 建镜像后立即下发到捕获器；面板改模式经 setInputCrossMode 同步到已建镜像。
    std::atomic<bool> inputCrossMode_{false};

    // 投屏自动开（2026-11-18）：首次进入 Connected 时下发 0x10「screenmirror」on，进程内只发一次
    //（手机端运行中会跳过重复授权页；用户手动关后本轮不再自动开）。仅 PC 控手机场景，见 publish()。
    bool autoScreenMirrorSent_ = false;

    // 重连退避（2026-11-24，学 Input Leap）：自动模式连接失败后按 2s→4s→…→15s 退避，
    // 冷却期内不消费信标不发起连接。worker 线程读写；UI 线程在用户明确动作（重提配对码、
    // 手动连接、startAuto）时清零，故用 atomic（int64 非 atomic 在 32 位下写会撕裂）。
    std::atomic<int64_t> connectBackoffMs_{0};       // 当前退避时长（0 = 无退避）
    std::atomic<int64_t> nextConnectAttemptMs_{0};   // 下次允许发起连接的时刻（steady ms）

    std::atomic<bool> beaconRun_{false};
    std::thread beaconThread_;
    int64_t connectStartMs_ = 0;
    std::string connectedPeer_;   // 已连上对端地址（host:port），供 snapshot 展示

    // 反向远程输入回调：UI 线程写（setter）、reader 线程读（转发），故用锁保护。
    std::function<void(const std::string&)> onRequestInput_;
    // 通知同步回调（0x40/0x42，2026-11-11）：与 onRequestInput_ 同款 —— 外层随时改，
    // 构造时挂在 client_ 上的转发 lambda 每次带锁取最新。
    std::function<void(const std::string&, const std::string&, const std::string&, const std::string&)> onNotifyPush_;
    std::function<void(const std::string&)> onNotifyDismiss_;
    std::function<void(uint8_t)> onNotifyReplyResult_;
    // 手机文件浏览回调（0x44–0x47，2026-11-11）：锁保护口径同上
    std::function<void(uint8_t, const std::string&, const std::vector<PhoneFileEntry>&)> onPhoneFileList_;
    std::function<void(uint8_t, const std::string&)> onPhoneFileFail_;
    // 相册投影回调（0x48–0x4B，2026-11-12）：锁保护口径同上
    std::function<void(uint8_t, uint32_t, const std::vector<AlbumEntry>&)> onAlbumPage_;
    std::function<void(uint8_t, const std::string&)> onAlbumPageFail_;
    // 令牌下发回调（0x36，2026-11-23）：锁保护口径同上 —— 外层落盘配置
    std::function<void(const std::string&)> onTokenGrant_;
    // 投屏自动开回调（2026-11-24）：publish() 自动发 0x10 成功后触发 —— 外层开预览窗。
    // 与其它回调同款：cbMu_ 保护读写，调用点在 publish()（worker 线程，持 mu_）。
    std::function<void()> onScreenMirrorAuto_;
    // 一次性配对码（面板提交，下一次连接尝试用掉）与已落盘的对端持久令牌：
    // worker 连接时在 mu_ 内取值（与信标 host/port/token 同一把锁，三样同源）
    std::string pendingPairingCode_;
    std::string grantedToken_;
    mutable std::mutex cbMu_;
};

}  // namespace apxpc::wireless
