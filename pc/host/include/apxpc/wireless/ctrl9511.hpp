#pragma once
// ============================================================================
// 统一控制面（9511）—— 三端互通的单一真源
//
// 这是「手机控 PC / PC 控手机 / PC 控 TV / 手机控手机 / 手机控 TV」共用的控制
// 协议，替代原先手机做服务端、PC 做客户端的 9500 旧路径（wireless_link.cpp 已删除）。
//
// 设计原则：
//   * 受控端 = 服务端（监听端口），控制端 = 客户端（主动连入）。谁控制谁，谁是客户端。
//   * 帧格式与 android/tv/net/TcpControlServer.kt、android/app/.../TvControllerClient.kt
//     逐字节一致：APX1 16 字节头（streamId=3）+ body + u32 CRC32。
//   * 握手：客户端先发 u32 LE 令牌长度 + UTF-8 令牌（空令牌也发那 4 字节长度）。
//   * 控制子命令（body[0]）：
//       0x01 鼠标   [1]=buttons [2]=dx(i8) [3]=dy(i8) [4]=wheel(i8) [5]=pan(i8，可选)
//                   pan 右滚为正；旧控制端只发 5 字节，此时按 0 处理（协议向后兼容）
//       0x02 多媒体 [1..2]=u16 位图（LE），位序见 android CONSUMER_MAP
//       0x03 键盘   [1]=mod [2]=0 [3..8]=k1..k6（HID usage 页 0x07）
//       0x04 触摸   [1]=action(0 down/1 up/2 move) [2]=0
//                     [3..4]=x u16 LE  [5..6]=y u16 LE（归一化 0..65535）[7..8]=0
//       0x20 剪贴板 [1..2]=u16 len LE [3..]=UTF-8（控制端 → 受控端）
//       0x21 反向剪贴板（受控端 → 控制端，受控端系统剪贴板变化时回传）
//       0x07 手柄   [1..2]=u16 按钮位图(LE) [3]=左摇杆X(i8) [4]=Y [5]=右摇杆RX [6]=RY
//       0x25 请求输入 [1]=hintLen [2..]=UTF-8 提示文本
//            ★ 方向是「本机有输入框获焦 → 请对端弹输入法」，**谁有输入框谁发**，
//              所以受控端（TV/手机被控端）与控制端（PC 面板）都会发，别按
//              "控制端→受控端"记 —— 早先这里方向写反，导致 0x26 在受控端被当成
//              "不可能收到的帧"而没注册回调（手机打字 PC 毫无反应）。
//       0x26 输入文本 [1]=flags [2..3]=textLen LE [4..]=UTF-8 文本
//            ★ 由敲字的一方发给被注入的一方（同上，双向）；flags bit1(0x02)=退格，
//              此时文本为空、接收侧应发一次退格键而非注入空串。
//       0x27 输入完成（同 0x26 方向）无 payload
//       'p'  ping（控制端 1s 心跳）；受控端回 'pong'
//   * 发现信标：受控端每 1.5s 向 9501 广播
//       "APX1TV|APX1PC|APX1PH <name> <port> <token>"（UTF-8；前缀 = 电视 / PC / 手机被控），
//       手机端 TvDiscovery 与 PC 端 WirelessSession 据此列出可控设备。
//     除 255.255.255.255 受限广播外，还向各网卡子网定向广播 —— MIUI / Android 15 会丢前者。
//
// 注入与剪贴板是**平台相关**部分，由 createPlatformInjector()/createPlatformClipboardWatcher()
// 按编译目标返回对应实现（Windows=SendInput、Linux=uinput、macOS=CGEvent）。
// ============================================================================
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#else
#include <sys/socket.h>
#endif

namespace apxpc::wireless {

// ---------------------------------------------------------- 跨平台输入注入 ----
/// 受控端把收到的控制帧注入到本机系统。所有方法在接收线程调用，实现需线程安全。
class InputInjector {
public:
    virtual ~InputInjector() = default;

    /// 鼠标相对位移（i8 范围）。wheel 上滚为正；buttons bit0=左 bit1=右 bit2=中。
    /// pan 右滚为正（9511 的 0x01 帧第 6 字节）—— 旧控制端只发 5 字节，此时调用方
    /// 传 0，进行为与老版本完全一致（协议向后兼容，见 docs/PROTOCOL.md §3.3）。
    virtual void injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel,
                             int8_t pan = 0) = 0;

    /// 绝对坐标触摸（归一化 0..65535）。action: 0=down 1=up 2=move；buttons 决定双指=右键。
    virtual void injectTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) = 0;

    /// 绝对指针（0x06，2026-11-13「指哪打哪」第二模式）：归一化坐标 0..65535 映射到
    /// **整个虚拟桌面**（多屏覆盖全部显示器），与 injectTouch 的「映射到副屏」语义不同
    /// —— 这是手机小地图「屏幕定位」专用的注入路径。默认回落 injectTouch
    ///（Linux uinput 无桌面概念，触摸式坐标注入即足够）；Windows 覆盖为
    /// SetCursorPos（v184 实测：MOUSEEVENTF_ABSOLUTE 纯移动帧会被部分环境静默丢弃）。
    virtual void injectAbsolute(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) {
        injectTouch(action, buttons, x, y);
    }

    /// HID 键盘（页 0x07）：mod 修饰位图 + 最多 6 个 usage；实现按边沿注入（down/up）。
    virtual void injectKeyboard(uint8_t mod, const uint8_t* keys, size_t count) = 0;

    /// Consumer 多媒体位图（位序见 android TvControlServer CONSUMER_MAP）。
    virtual void injectConsumer(uint16_t bitmap) = 0;

    /// 手柄：16 位按钮位图 + 左/右摇杆 4 轴（i8，约 -127..127）。
    /// 受控端按边沿注入按键、按值注入轴；具体能力见各平台实现
    ///（Windows/macOS 的 SendInput/CGEvent 不支持手柄，需虚拟 HID 驱动）。
    virtual void injectGamepad(uint16_t buttons, int8_t x, int8_t y, int8_t rx, int8_t ry) = 0;

    /// 把文本写入本机剪贴板（受控端收到 0x20 帧时调用）。
    virtual void setClipboard(const std::string& text) = 0;
};

/// 按编译目标创建对应平台的注入器（失败返回 nullptr）。
std::unique_ptr<InputInjector> createPlatformInjector();

/// 本机是否具备「把输入注入到系统」所需的权限。**只有 macOS 会返回 false**：
/// CGEventPost 需要一个「辅助功能」授权，没授权时事件被系统静默丢弃 ——
/// 手机按了半天没反应、日志里也什么都看不到（issue#47「无线模式全都不能用」的最可能真因）。
/// Windows / Linux 无此门槛，恒 true。
bool platformInputTrusted();

/// 无权限时发起一次系统授权引导（macOS：CGRequestPostEventAccess 弹窗），返回引导后的状态。
/// 其他平台直接返回 platformInputTrusted()。调用点只在「真的收到了一帧输入」时才用 ——
/// 用户正在操作的那一刻弹窗，他才明白为什么要授权。
bool requestPlatformInputTrust();

/// 权限面板在系统设置里的显示名（macOS 26 起「辅助功能」改叫「设备控制与数据访问」）。
/// 只用于文案，不参与任何判断。
std::string inputTrustSettingsLabel();

/// 打开系统设置里对应的权限面板 —— TCC 拒绝过一次后 CGRequestPostEventAccess 不再弹窗，
/// 这是用户唯一能改回决定的地方。返回是否成功把用户带到设置界面。
bool openPlatformInputSettings();

/// 本进程是否跑在 .app 内（.app 与裸二进制是两条独立 TCC 身份，面板必须说清该勾哪一项）。
bool runningFromAppBundle();

// ---------------------------------------------------------- 剪贴板反向监听 ----
/// 监听本机剪贴板变化；变化时回调最新文本。受控端据此发送 0x21 反向剪贴板帧。
class ClipboardWatcher {
public:
    virtual ~ClipboardWatcher() = default;
    /// 开始监听（可能起后台线程）。成功返回 true。
    virtual bool start() = 0;
    virtual void stop() = 0;
};

/// 创建平台相关的剪贴板监听器。cb 在变化时回调（可能来自其它线程）。
std::unique_ptr<ClipboardWatcher> createPlatformClipboardWatcher(
    std::function<void(const std::string&)> cb);

/// 把文本写入**本机**剪贴板（控制端收到对端 0x21 反向剪贴板时调用）。
/// 与 [InputInjector::setClipboard] 的区别只在触发方向：后者是受控端收到 0x20 时调用，
/// 两者最终落到同一个系统剪贴板。
/// 成功返回 true；平台未接入时返回 false（如实降级，不假装成功）。
bool setSystemClipboard(const std::string& text);

/// 把文本**注入当前光标处**（远程输入回传文本落地用）。
/// 策略是分级的，原因很实在：非 ASCII 文本逐字符 SendInput 会走 IME 组合，中文极易丢字/乱码。
///   · 纯 ASCII 且较短 → 逐字符键入，不碰剪贴板；
///   · 含中文 / 换行 / 较长 → 写剪贴板 + 模拟 Ctrl+V（保真），随后还原用户原来的剪贴板。
/// 成功返回 true。
bool injectSystemText(const std::string& text);

/// 读**本机**剪贴板文本（UTF-8）。失败或空剪贴板返回空串。
/// 对应「把电脑上复制的内容主动发给手机」这条方向（与 0x21 自动回传不同：那是被动监听）。
std::string readSystemClipboard();

// 9511 控制面**能力位**（0x33 能力协商，2026-11-11；与 Android 侧 ApxFrame.CAP_* 镜像）。
// 连接建立后两端各发一帧 0x33（body = 自身位图 u64 小端 8 字节），接收方存下对端位图；
// 发送能力帧前先核对**对端**位图，缺位的一端不发送对应帧族，旧版安静忽略未知帧号。
// 位号改动必须三处同步：本文件、Android shared/proto/ApxFrame.kt、docs/PROTOCOL.md §3.3。
static constexpr uint64_t kCaps9511NotifySync  = 1ull << 0;  // 手机→PC 推送/消除通知（0x40/0x42）
static constexpr uint64_t kCaps9511NotifyReply = 1ull << 1;  // PC→手机 RemoteInput 代发回复（0x41/0x43，2026-11-14）
static constexpr uint64_t kCaps9511FindPhone   = 1ull << 2;  // PC→手机 查找手机响铃（0x38）
static constexpr uint64_t kCaps9511PhoneFiles  = 1ull << 3;  // PC→手机 浏览/取走手机文件（0x44–0x47）
static constexpr uint64_t kCaps9511Album       = 1ull << 4;  // PC→手机 相册投影（0x48–0x4B）
static constexpr uint64_t kCaps9511AbsolutePtr = 1ull << 5;  // 控制端→被控 绝对指针 0x06（手机小地图指哪打哪）
static constexpr uint64_t kCaps9511GamepadShot = 1ull << 6;  // 手机→PC 手柄截图 0x4C（手柄页中央 📷 键，issue#170 #7）
static constexpr uint64_t kCaps9511ScreenToggle = 1ull << 7; // 手机→PC 副屏远程开关 0x4D（副屏页右下角入口，2026-11-14）

/// 相册条目（0x49 分页应答，2026-11-12，与 Android ApxFrame.AlbumEntry 对拍）。
/// added 为秒（MediaStore DATE_ADDED 口径，PC 端按它折算本地日期分组）；
/// id 是 MediaStore 图片 _id，缩略图/原图请求都按它寻址。
struct AlbumEntry {
    uint32_t id = 0;
    uint32_t added = 0;
    uint64_t size = 0;
    std::string path;
    std::string name;
};

/// 手机文件条目（0x45 目录应答，2026-11-11，与 Android ApxFrame.PhoneFileEntry 对拍）。
/// isDir=true 时 size 无意义（对端恒填 0）；name 为 UTF-8 文件/目录名。
struct PhoneFileEntry {
    std::string name;
    bool isDir = false;
    uint64_t size = 0;
};

// ---------------------------------------------------------- 9511 受控服务端 ----
struct Ctrl9511ServerStatus {
    bool listening = false;
    bool connected = false;
    std::string peer;        // 对端 host:port
    std::string error;
    uint64_t mouse = 0;
    uint64_t touch = 0;
    uint64_t keyboard = 0;
    uint64_t consumer = 0;
    uint64_t clipboard = 0;
    uint64_t reverseClipboard = 0;
    uint64_t dropped = 0;
    uint64_t requestInput = 0;
    uint64_t inputText = 0;
    uint64_t inputDone = 0;
    /// 手机上报的模式状态帧（0x31）计数与原始位图；位布局见 phone_mode.hpp
    uint64_t modeStatus = 0;
    uint8_t modeFlags = 0;
    /// 通知同步（0x40/0x42，2026-11-11）：推送/消除计数（手机 → PC）
    uint64_t notifyPush = 0;
    uint64_t notifyDismiss = 0;
    /**
     * 本**次**会话里手机是否上报过模式状态。
     *
     * 为什么不看 modeStatus > 0：那是历史累计计数，跨会话不清 —— 手机断线后
     * modeFlags 还留着上一会话的位图，面板就会把「上一个模式」当成当前状态
     * 一直显示（issue#52 反复反馈的那条）。新会话握手与断链都把它清掉。
     */
    bool modeReported = false;
    /// 本机是否具备注入权限（macOS 的「辅助功能」授权）；false 时收到的输入不会生效
    bool inputTrusted = true;
    /**
     * 对端能力位图（0x33 能力协商，2026-11-11）：手机连入后报上来的支持能力。
     * 位定义与 Android 侧 ApxFrame.CAP_* 镜像（kCaps9511*，本文件底部）；
     * 0 = 未收到（旧版手机没有该机制，能力帧族一律不发）。
     */
    uint64_t peerCaps = 0;
};

class Ctrl9511Server {
public:
    Ctrl9511Server();
    ~Ctrl9511Server();

    /// 启动监听 + 信标广播。name 用于信标与展示；token 为空表示不鉴权。
    bool start(uint16_t port, const std::string& token, const std::string& name);
    void stop();
    Ctrl9511ServerStatus status() const;

    /// 注册反向剪贴板回调：本机剪贴板变化时，自动回传给控制端。
    void setReverseClipboardEnabled(bool on) { reverseClipboard_ = on; }

    /// 查找手机（0x38，2026-11-11，能力位 bit2）：让已连入的控制端手机响铃
    /// （STREAM_ALARM 最大音量 + 亮屏，30s 自动停）。action：1=响铃 0=停，当前只有响铃一键。
    /// 未连接时为 no-op（返回 false），调用方无需提示 —— 托盘菜单常驻，连着才有效。
    bool sendFindPhone(uint8_t action);

    /// 手机文件浏览（0x44–0x47，2026-11-11，能力位 bit3）：向已连入的对端请求目录列表 /
    /// 取走一个文件。与服务端 sendFindPhone 同款门控：对端报过 0x33 且缺
    /// kCaps9511PhoneFiles 位时跳过（旧版对未知 cmd 静默忽略，提前挡一道省得空等）。
    /// path 空 = 请求对端的根入口列表。文件本体经 9512 推回，失败走 0x47 回执。
    bool sendPhoneFileListReq(const std::string& path);
    bool sendPhoneFilePullReq(const std::string& path);

    /// 相册投影（0x48–0x4B，2026-11-12，能力位 bit4）：向已连入的对端请求相册一页 /
    /// 开关新照片监听 / 按条目 id 请求一张缩略图（经 9512 推回 JPEG）。
    /// 门控同手机文件：对端报过 0x33 且缺 kCaps9511Album 位时跳过。
    /// page 从 0 起、按拍摄时间倒序；分页应答走 0x49 回调，失败走 0x47 回执。
    bool sendAlbumPageReq(uint32_t page);
    bool sendAlbumWatchReq(bool on);
    bool sendAlbumThumbReq(uint32_t id);

    /// 远程输入回调：受控端收到手机发来的远程输入请求/文本/完成信号
    void setOnRequestInput(std::function<void(const std::string&)> cb) { onRequestInput_ = std::move(cb); }
    void setOnRemoteInputText(std::function<void(const std::string&, uint8_t)> cb) { onRemoteInputText_ = std::move(cb); }
    void setOnRemoteInputDone(std::function<void()> cb) { onRemoteInputDone_ = std::move(cb); }

    /// 通知同步回调（0x40/0x42，2026-11-11）：手机（被控或控 PC 的一端）推送/消除通知，
    /// 由接线方转成托盘气泡（`TrayIcon::notify`）。回调跑在 reader 线程 —— TrayIcon::notify
    /// 双平台线程安全，可直接调用；传空 function = 取消接线（停止期防悬挂用）。
    /// app=应用展示名（微信/QQ…），key=Android 通知键（0x42 原样带回，当前仅记账）。
    void setOnNotifyPush(std::function<void(const std::string& key, const std::string& title,
                                            const std::string& text, const std::string& app)> cb) {
        onNotifyPush_ = std::move(cb);
    }
    void setOnNotifyDismiss(std::function<void(const std::string& key)> cb) {
        onNotifyDismiss_ = std::move(cb);
    }

    /// 手机文件浏览回调（0x45/0x47，2026-11-11，能力位 bit3）：对端回目录列表 /
    /// 取文件失败回执时触发（与远程输入回调同款搬运：start() 时归 Impl 所有）。
    /// 回调跑在 reader 线程；err 取值与 Android ApxFrame.PHONEFILE_ERR_* 一致（0=成功）。
    void setOnPhoneFileList(std::function<void(uint8_t err, const std::string& path,
                                               const std::vector<PhoneFileEntry>& entries)> cb) {
        onPhoneFileList_ = std::move(cb);
    }
    void setOnPhoneFileFail(std::function<void(uint8_t err, const std::string& path)> cb) {
        onPhoneFileFail_ = std::move(cb);
    }

    /// 相册投影回调（0x49/0x47，2026-11-12，能力位 bit4）：对端回一页相册 /
    /// 缩略图失败回执时触发（搬运口径同手机文件回调）。回调跑在 reader 线程；
    /// err 取值与 Android ApxFrame.PHONEFILE_ERR_* 一致（0=成功）。
    /// 失败回执按 path 前缀 "album:thumb:" 区分是缩略图失败还是文件失败。
    void setOnAlbumPage(std::function<void(uint8_t err, uint32_t page,
                                           const std::vector<AlbumEntry>& entries)> cb) {
        onAlbumPage_ = std::move(cb);
    }
    void setOnAlbumPageFail(std::function<void(uint8_t err, const std::string& path)> cb) {
        onAlbumPageFail_ = std::move(cb);
    }

    /// 手柄截图回调（0x4C，2026-11-14，能力位 bit6）：手机手柄页中央 📷 键按下时触发，
    /// 由接线方抓屏存图并托盘气泡回执（`screen::captureScreenToPng` + `TrayIcon::notify`）。
    /// 回调跑在 reader 线程；抓屏是 GDI 调用，线程安全。
    void setOnGamepadShot(std::function<void()> cb) { onGamepadShot_ = std::move(cb); }

    /// 副屏远程开关回调（0x4D，2026-11-14，能力位 bit7）：手机副屏页入口发来开/关请求
    /// （on=1 插虚拟显示器 / 0 拔出），由接线方走 ActionRouter 同款动作（含配置落盘），
    /// 托盘气泡回执结果。回调跑在 reader 线程；plug/unplug 是 IDD/驱动调用，与热键回调
    /// 同线程模型（ActionRouter 内部有锁，接线方直接调即可）。
    void setOnScreenToggle(std::function<void(uint8_t on)> cb) { onScreenToggle_ = std::move(cb); }

    /// 通知回复（0x41/0x43，2026-11-14，能力位 bit1）：PC 气泡点回复 → 本端发 0x41，
    /// 手机（连入的控制端）处理后回 0x43 终态回执。**成功（err=0）也回帧**——用户在等。
    /// err 取值与 Android ApxFrame.NOTIFY_REPLY_ERR_* 一致（0=已提交 1=未开启通知回复
    /// 2=key 失效 3=该通知不支持回复 4=发送失败）。回调跑在 reader 线程。
    void setOnNotifyReplyResult(std::function<void(uint8_t err)> cb) { onNotifyReplyResult_ = std::move(cb); }

    /// 发送通知回复请求（0x41）：key=0x40 带来的 Android 通知键，text=用户要回复的正文
    ///（UTF-8，协议上限 1KB，发送侧超长按字符边界截断）。门控同 sendFindPhone：
    /// 对端报过 0x33 且缺 kCaps9511NotifyReply 时跳过（旧版手机不报该位，即无缓存）。
    bool sendNotifyReply(const std::string& key, const std::string& text);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    bool reverseClipboard_ = true;
    std::function<void(const std::string&)> onRequestInput_;
    std::function<void(const std::string&, uint8_t)> onRemoteInputText_;
    std::function<void()> onRemoteInputDone_;
    // 通知同步回调（成员由外层 setter 写、Impl 在 start() 时搬走 —— 与远程输入回调同款）
    std::function<void(const std::string&, const std::string&, const std::string&, const std::string&)> onNotifyPush_;
    std::function<void(const std::string&)> onNotifyDismiss_;
    // 手机文件浏览回调（0x45/0x47，2026-11-11）：搬运口径同上
    std::function<void(uint8_t, const std::string&, const std::vector<PhoneFileEntry>&)> onPhoneFileList_;
    std::function<void(uint8_t, const std::string&)> onPhoneFileFail_;
    // 相册投影回调（0x49/0x47，2026-11-12）：搬运口径同上
    std::function<void(uint8_t, uint32_t, const std::vector<AlbumEntry>&)> onAlbumPage_;
    std::function<void(uint8_t, const std::string&)> onAlbumPageFail_;
    std::function<void()> onGamepadShot_;
    // 副屏远程开关回调（0x4D，2026-11-14，能力位 bit7）：搬运口径同手柄截图回调
    std::function<void(uint8_t)> onScreenToggle_;
    // 通知回复回执回调（0x43，2026-11-14，能力位 bit1）：搬运口径同通知同步回调
    std::function<void(uint8_t)> onNotifyReplyResult_;
};

// ---------------------------------------------------------- 9511 控制客户端 ----
/// 客户端状态快照（供 UI 展示链路质量与注入计数）
struct Ctrl9511ClientStatus {
    bool connected = false;
    std::string peer;          // 对端 host:port
    double rttMs = -1;         // 心跳 RTT；-1 = 未测得
    uint64_t mouse = 0;
    uint64_t touch = 0;
    uint64_t keyboard = 0;
    uint64_t consumer = 0;
    uint64_t dropped = 0;
    uint64_t requestInput = 0;
    uint64_t inputText = 0;
    uint64_t inputDone = 0;
    /// 通知同步（0x40/0x42，2026-11-11）：推送/消除计数（手机 → PC 控制端）
    uint64_t notifyPush = 0;
    uint64_t notifyDismiss = 0;
    /**
     * 对端能力位图（0x33 能力协商，2026-11-11）：手机连入后报上来的支持能力。
     * 位定义与 Android 侧 ApxFrame.CAP_* 镜像（kCaps9511*，本文件底部）；
     * 0 = 未收到（旧版手机没有该机制，能力帧族一律不发）。
     */
    uint64_t peerCaps = 0;
};

class Ctrl9511Client {
public:
    Ctrl9511Client();
    ~Ctrl9511Client();

    /// 主动连入受控端（手机/TV/PC 的 9511 服务端）。握手同手机 TvControllerClient。
    bool connect(const std::string& host, uint16_t port, const std::string& token);
    void disconnect();

    bool ready() const;

    // —— 输入发送（供本地采集/UI 调用）；与手机 TvControllerClient 镜像 ——
    void sendMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel = 0);
    void sendKeyboard(uint8_t mod, const uint8_t* keys, size_t count);
    void sendConsumer(uint16_t bitmap);
    void sendTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y);
    /// 发送剪贴板文本到受控端（body=[0x20,lenLo,lenHi,utf8]）。空文本忽略。
    bool sendClipboard(const std::string& text);

    /// 模块开关（v184 新增协议 0x10）：通知受控端挂起/恢复某模块（如 "wireless"）。
    /// body=[0x10,idLen,id...,on(0/1)]。仅控制面已连接时可发。
    bool sendModuleToggle(const std::string& id, bool on);

    /// 远程输入：请求对端设备输入文本。hint 为提示信息（如"请输入密码"）。
    bool sendRequestInput(const std::string& hint);
    /// 远程输入：向对端发送输入的文本。flags 见 PROTOCOL（0x01=增量 0x04=完整 0x08=取消）。
    bool sendInputText(const std::string& text, uint8_t flags = 0x04);
    /// 远程输入：通知对端输入完成。
    bool sendInputDone();

    /// 发送任意控制帧本体（含 cmd 字节）；用于扩展帧（如副屏 0x06 关键帧请求）。
    /// 受控端不识别的 cmd 会忽略，调用方无需关心。
    bool sendControl(const std::vector<uint8_t>& body);

    /// 查找手机（0x38，2026-11-11，能力位 bit2）：让受控手机响铃找它
    /// （STREAM_ALARM 最大音量 + 亮屏，30s 自动停）。action：1=响铃 0=停。
    /// 未连接返回 false；对端报过 0x33 且不含 kCaps9511FindPhone 时同样跳过
    /// （旧版手机对 0x38 静默忽略，这里提前挡一道省得空等）。
    bool sendFindPhone(uint8_t action);

    /// 手机文件浏览（0x44–0x47，2026-11-11，能力位 bit3）：向受控端请求目录列表 /
    /// 取走一个文件。门控同 sendFindPhone（对端报过 0x33 且缺 kCaps9511PhoneFiles 时跳过）。
    /// path 空 = 请求对端根入口列表；文件本体经 9512 推回，失败走 0x47 回执。
    /// 返回 false = 未连接或对端不支持，调用方据此提示。
    bool sendPhoneFileListReq(const std::string& path);
    bool sendPhoneFilePullReq(const std::string& path);

    /// 相册投影（0x48–0x4B，2026-11-12，能力位 bit4）：向受控端请求相册一页 /
    /// 开关新照片监听 / 按条目 id 请求缩略图。门控同 sendFindPhone（对端报过 0x33
    /// 且缺 kCaps9511Album 时跳过）。page 从 0 起、按拍摄时间倒序；分页应答走
    /// onAlbumPage 回调，缩略图经 9512 推回、失败走 0x47 回执（onAlbumPageFail）。
    bool sendAlbumPageReq(uint32_t page);
    bool sendAlbumWatchReq(bool on);
    bool sendAlbumThumbReq(uint32_t id);

    /// 通知回复（0x41/0x43，2026-11-14，能力位 bit1）：PC 控手机的链路上向受控手机
    /// 发 0x41 通知回复请求；手机回 0x43 终态回执走 onNotifyReplyResult 回调
    ///（**成功 err=0 也回帧**，用户在等）。门控同 sendFindPhone：对端报过 0x33 且
    /// 缺 kCaps9511NotifyReply 时跳过。key=0x40 带来的通知键；text 超长按字符边界截断。
    bool sendNotifyReply(const std::string& key, const std::string& text);

    /// 线程安全状态快照（计数 / RTT / 连接）
    Ctrl9511ClientStatus status() const;

    /// 反向剪贴板回调：受控端剪贴板变化时回传（写入本机剪贴板由调用方决定）。
    std::function<void(const std::string&)> onReverseClipboard;

    /// v184：0x28 特殊键/组合键（手机编辑快捷键排）→ mod 位图 1=Ctrl 2=Shift 4=Alt
    std::function<void(uint8_t, uint8_t)> onSpecialKey;

    /// 远程输入回调：对端请求我输入文本时触发（hint=提示语）
    std::function<void(const std::string&)> onRequestInput;
    /// 远程输入回调：对端发回的输入文本（text=文本, flags=标志位）
    std::function<void(const std::string&, uint8_t)> onRemoteInputText;
    /// 远程输入回调：对端输入完成
    std::function<void()> onRemoteInputDone;

    /// 通知同步回调（0x40/0x42，2026-11-11）：受控手机推送/消除通知（PC 控手机时走这条
    /// —— 手机是 9511 服务端，通知经控制端 reader 到这里），接线方转托盘气泡。
    /// 回调跑在 reader 线程（TrayIcon::notify 线程安全）；⚠️ 与既有回调一样在
    /// connect() 时被捕获，必须在**连接前**赋值（WirelessSession 的转发 lambda 就是为此）。
    std::function<void(const std::string& key, const std::string& title,
                       const std::string& text, const std::string& app)> onNotifyPush;
    std::function<void(const std::string& key)> onNotifyDismiss;

    /// 手机文件浏览回调（0x45/0x47，2026-11-11，能力位 bit3）：受控端回目录列表 /
    /// 取文件失败回执时触发。回调跑在 reader 线程；⚠️ 与既有回调一样在 connect()
    /// 时被捕获，必须在**连接前**赋值（WirelessSession 的转发 lambda 同款）。
    /// err 取值与 Android ApxFrame.PHONEFILE_ERR_* 一致（0=成功）。
    std::function<void(uint8_t err, const std::string& path,
                       const std::vector<PhoneFileEntry>& entries)> onPhoneFileList;
    std::function<void(uint8_t err, const std::string& path)> onPhoneFileFail;

    /// 相册投影回调（0x49/0x47，2026-11-12，能力位 bit4）：受控端回一页相册 /
    /// 缩略图失败回执时触发。回调跑在 reader 线程；⚠️ 与既有回调一样在 connect()
    /// 时被捕获，必须在**连接前**赋值。err 取值与 Android ApxFrame.PHONEFILE_ERR_*
    /// 一致（0=成功）；失败回执按 path 前缀 "album:thumb:" 与文件失败区分。
    std::function<void(uint8_t err, uint32_t page,
                       const std::vector<AlbumEntry>& entries)> onAlbumPage;
    std::function<void(uint8_t err, const std::string& path)> onAlbumPageFail;

    /// 通知回复回执回调（0x43，2026-11-14，能力位 bit1）：手机回终态时触发。
    /// 回调跑在 reader 线程；⚠️ 与既有回调一样在 connect() 时被捕获，必须在
    /// **连接前**赋值（WirelessSession 的转发 lambda 同款）。err 取值与 Android
    /// ApxFrame.NOTIFY_REPLY_ERR_* 一致（0=已提交 1=未开启 2=key 失效 3=不支持回复 4=发送失败）。
    std::function<void(uint8_t err)> onNotifyReplyResult;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

/// v184：特殊键/组合键注入（UTF 文本之外的方向键/删除/编辑快捷键）。
/// mod 位图：1=Ctrl 2=Shift 4=Alt。三端各有实现：input_injector_win.cpp /
/// input_injector_mac.mm / input_injector_lin.cpp（Linux 侧经 /dev/uinput）。
bool injectSpecialKey(uint8_t mod, uint8_t vk);

}  // namespace apxpc::wireless
