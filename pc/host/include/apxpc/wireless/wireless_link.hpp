#pragma once
// Wi‑Fi 控制通道（PC = **客户端**，手机 = **服务端** 9500）。
//
// 为什么 PC 做客户端：PC 侧发起的是出站连接，Windows 防火墙默认放行，
// 不需要用户开入站规则；手机侧只监听一个端口。
//
// 建链与收发（与 Android `wireless/TcpControlChannel.kt` 逐字节一致）：
//   PC  → 手机 : u32 LE 长度 + UTF-8 令牌（握手，**无回执字节**）
//   手机 → PC : APX1 帧（streamId=3）；载荷首字节 'p' 为心跳回显
//   PC  → 手机 : APX1 帧 body="ping"（1s 心跳，用于测 RTT）
//
// 控制面子命令（**改这里必须同步改 Android `core/TcpCtrlBridge.kt`**）：
//   0x01 鼠标   [1]=buttons [2]=dx(i8) [3]=dy(i8) [4]=wheel(i8)
//   0x02 多媒体 [1..2]=u16 位图（LE）——按位边沿注入
//   0x03 键盘   [1]=mod [2]=0 [3..8]=k1..k6（HID usage，页 0x07）
//   0x05 打开副屏（**PC → 手机**：面板开副屏推流时请求手机弹出副屏页；
//        手机端 Android 10+ 后台弹页会被系统拦，靠常驻通知「副屏」动作兜底）
//
// 注入免驱动：Windows 标准 SendInput（架构 §2.2「无线模式零新驱动」）。
// 本文件不做任何伪装：注入失败/无对应 VK 时只记日志并计入 dropped。
//
// 线程模型：connect/disconnect 幂等；status()/counters() 线程安全。
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#endif

namespace apxpc::wireless {

class WirelessLink {
public:
    struct Status {
        bool connected = false;
        std::string peer;      // host:port
        double rttMs = -1;     // 实测控制面 RTT；-1 = 未测得
        std::string error;     // 最近一次失败原因（空 = 无）
        int64_t upMs = 0;      // 已连接时长（毫秒）

        // 手机侧 Wi‑Fi 音频模块状态：由手机经控制面 'a' 状态帧周期上报。
        // phoneAudioState < 0 = 尚未收到任何状态帧；否则为 ModuleState 编码（0..6，见 android）。
        int phoneAudioState = -1;
        bool phoneAudioSpk = false;     // 音箱下行（AudioTrack）是否初始化成功
        bool phoneAudioMic = false;     // 麦克风上行是否可用
        uint32_t phoneAudioDropped = 0; // 手机侧丢弃的音频帧
        bool phoneAudioKnown = false;   // 是否收到过状态帧
    };

    /// 注入计数：既用于面板展示，也是「链路真的通了」的客观证据
    struct Counters {
        uint64_t mouse = 0;
        uint64_t consumer = 0;
        uint64_t keyboard = 0;
        uint64_t touch = 0;
        uint64_t pong = 0;
        uint64_t dropped = 0;   // 认不出的控制帧/无法注入的键
    };

    WirelessLink() = default;
    ~WirelessLink() { disconnect(); }
    WirelessLink(const WirelessLink&) = delete;
    WirelessLink& operator=(const WirelessLink&) = delete;

    /// 阻塞建链（3s 超时 + 令牌握手）。成功返回 true 并启动保活线程。
    bool connect(const std::string& host, uint16_t port, const std::string& token);

    void disconnect();

    Status status() const;
    Counters counters() const;

    /// 请求手机打开副屏页（面板开副屏推流时调用）。
    /// 只置标志，实际发送由保活线程在下个循环完成（≤500ms），线程安全。
    void requestOpenScreen() { pendingCmd_.fetch_or(1); }

private:
    void keepaliveLoop();
    bool sendAll(const uint8_t* p, size_t n);
    bool buildPing(uint8_t* buf, size_t cap, size_t& len, uint32_t seq);

    /// 组一条 control 命令帧（body=[cmd]）。buildPing 的通用版
    bool buildCmd(uint8_t* buf, size_t cap, size_t& len, uint8_t cmd, uint32_t seq);

    // ---- 注入（仅在保活线程调用，状态无需加锁）----
    void injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel);
    void injectTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y);
    void injectConsumer(uint16_t bitmap);
    void injectKeyboard(uint8_t mod, const uint8_t* keys, size_t count);

    uint8_t  mouseButtons_ = 0;
    uint16_t consumerBm_ = 0;
    uint8_t  kbMod_ = 0;
    uint8_t  kbKeys_[6] = {0, 0, 0, 0, 0, 0};

    mutable std::mutex mu_;
    Status st_;                 // 受 mu_ 保护
#if defined(_WIN32)
    SOCKET sock_ = INVALID_SOCKET;
#else
    int sock_ = -1;
#endif
    std::atomic<bool> running_{false};
    std::thread thread_;
    std::string token_;
    std::string peer_;
    int64_t upSinceMs_ = 0;

    std::atomic<uint64_t> cMouse_{0};
    std::atomic<uint64_t> cConsumer_{0};
    std::atomic<uint64_t> cKeyboard_{0};
    std::atomic<uint64_t> cTouch_{0};
    std::atomic<uint64_t> cPong_{0};
    /// 待发命令位图（bit1 = 0x05 打开副屏）；由任意线程置位，保活线程取走发送
    std::atomic<int> pendingCmd_{0};
    std::atomic<uint64_t> cDropped_{0};
};

}  // namespace apxpc::wireless
