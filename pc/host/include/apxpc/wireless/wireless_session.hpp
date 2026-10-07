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
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "apxpc/wireless/ctrl9511.hpp"

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
    void onBeacon(const std::string& host, uint16_t port);
    void beaconLoop();

    mutable std::mutex mu_;
    SessionSnapshot snap_;
    Desire desire_;
    bool haveBeacon_ = false;
    std::string beaconHost_;
    uint16_t beaconPort_ = 0;
    bool manualFailed_ = false;

    std::atomic<bool> running_{false};
    std::thread worker_;
    Ctrl9511Client client_;

    std::atomic<bool> beaconRun_{false};
    std::thread beaconThread_;
    int64_t connectStartMs_ = 0;
    std::string connectedPeer_;   // 已连上对端地址（host:port），供 snapshot 展示

    // 反向远程输入回调：UI 线程写（setter）、reader 线程读（转发），故用锁保护。
    std::function<void(const std::string&)> onRequestInput_;
    mutable std::mutex cbMu_;
};

}  // namespace apxpc::wireless
