#pragma once
// 无线控制会话：把「发现手机 + 建链 + 断线后回到等待」收成一处状态机。
//
// 为什么单独抽一层：CLI（`apxhost wireless` / `wireless-listen`）与桌面端面板
// （`apxpc::ui::runPanel`）需要对同一条链路做同样的事 —— 面板不能自己在 UI 线程
// 上跑 3s 阻塞的 connect()，所以连接动作一律交给本类的后台线程。
//
// 用法：UI 线程只做两件事 —— 调 [startAuto]/[connectManual]/[disconnect] 表达**意图**；
// 定时调 [snapshot] 读状态。所有 socket I/O 都在 worker 线程里完成，**绝不阻塞 UI**。
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "apxpc/wireless/beacon_listener.hpp"
#include "apxpc/wireless/wireless_link.hpp"

namespace apxpc::wireless {

enum class LinkPhase {
    Idle,          // 未启用（用户断开或还没点连接）
    Discovering,   // 自动模式：正在等手机信标
    Connecting,    // 正在建链（≤3s）
    Connected,     // 已连上，输入已可注入
    Failed,        // 手动模式建链失败，等用户再点「连接」
};

const char* linkPhaseName(LinkPhase p) noexcept;

struct SessionSnapshot {
    LinkPhase phase = LinkPhase::Idle;
    bool autoMode = true;
    std::string peer;          // host:port（已连上时）
    double rttMs = -1;         // 控制面心跳 RTT；-1 = 未测得
    long long upMs = 0;        // 已连接时长
    std::string error;         // 最近一次失败原因（空 = 无）
    WirelessLink::Counters counters{};

    // 手机侧 Wi‑Fi 音频模块状态（由手机经控制面 'a' 状态帧周期上报）
    int phoneAudioState = -1;       // <0 = 未收到；否则 ModuleState 编码 0..6
    bool phoneAudioSpk = false;     // 音箱下行（AudioTrack）是否初始化成功
    bool phoneAudioMic = false;     // 麦克风上行是否可用
    uint32_t phoneAudioDropped = 0; // 手机侧丢弃的音频帧
    bool phoneAudioKnown = false;   // 是否收到过状态帧
};

class WirelessSession {
public:
    WirelessSession();
    ~WirelessSession();

    WirelessSession(const WirelessSession&) = delete;
    WirelessSession& operator=(const WirelessSession&) = delete;

    /// 自动发现：监听手机 UDP 信标，发现即连；断线后自动回到等待（无需用户干预）
    void startAuto();

    /// 手动：连指定地址。失败原因进 snapshot().error，**不会**自动重试
    void connectManual(const std::string& host, uint16_t port);

    /// 主动断开，回到 Idle（链路与信标一起收掉）
    void disconnect();

    /// 结束会话并回收后台线程（析构也会调）
    void stop();

    /// 请求手机打开副屏页（仅置位，由 WirelessLink 保活线程发送，线程安全）
    void requestOpenScreen();

    /// 手机端是否请求了关键帧（副屏页 onResume）。取走即清零。
    bool takeKeyFrameRequest();

    /// 设置触摸映射目标矩形（虚拟屏位置；w/h≤0 回主屏模式）。线程安全。
    void setTouchRect(int x, int y, int w, int h);

    /// 线程安全的状态快照
    SessionSnapshot snapshot() const;

private:
    enum class Mode { Idle, Auto, Manual };

    struct Desire {
        Mode mode = Mode::Idle;
        std::string host;
        uint16_t port = 0;
        bool fresh = false;   // true = 用户刚表达的意图，worker 需据此（重新）动作
    };

    void worker();
    void publish(LinkPhase ph, Mode m);
    void onBeacon(const PhoneBeacon& pb);

    mutable std::mutex mu_;
    SessionSnapshot snap_;
    Desire desire_;
    bool haveBeacon_ = false;
    std::string beaconHost_;
    uint16_t beaconPort_ = 0;

    std::atomic<bool> running_{false};
    std::thread worker_;
    BeaconListener beacon_;
    WirelessLink link_;
};

}  // namespace apxpc::wireless
