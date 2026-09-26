#pragma once
// 副屏推流：把「抓屏 → 编码 → 组帧」接到**已建立**的媒体连接上。
//
// ## 复用而非重写
// `pc/display/` 里抓屏（Desktop Duplication）、编码（Media Foundation）、组帧
// （FrameWriter，含视频扩展头 / 脏矩形 / 分片 / CRC）与三线程编排（Pipeline）
// 都已是完整实现。本类只在**传输层做替换**：给 Pipeline 注入一个把写操作转发到
// `MediaSession` 的适配器 —— 于是副屏与音箱/麦克风共用同一条 TCP 连接。
//
// ## 为什么必须共用（不能自开第二条）
// 手机侧 `wireless/TcpMediaChannel.kt` 是**单对端语义**（防多个上位机抢同一出口），
// 第二条连接会被直接拒掉。
//
// ## 形态：镜像，不是扩展屏
// 抓的是 PC **现有桌面**（`preferVirtual = false`）→ **不需要任何驱动**。
// 要做「手机当第二块屏」（扩展屏），Windows 侧必须装 IddCx 虚拟显示器驱动
// （见 `pc/display/idd/README.md`：扩展屏没有免驱路径）。
//
// 线程纪律：Pipeline 自带抓屏/编码/触控三个线程；本类只做启停与状态读取，
// 面板在 UI 线程调用安全。
#include <cstdint>
#include <memory>
#include <string>

namespace apxpc::media {

class MediaSession;

/// 推流参数（面板只暴露最常用的几个）
struct ScreenPushOptions {
    /// 上限帧率；实测软件编码（MF）在 1080p 下大约只能跑 25–30fps
    uint32_t maxFps = 30;
    /// 目标码率（Kbps）
    uint32_t bitrateKbps = 8000;
    /// 0 = 跟随抓屏尺寸（推荐：少一次缩放，也避免与抓屏尺寸打架）
    uint32_t width = 0;
    uint32_t height = 0;
    /// true = 桌面镜像（抓主屏）；false = 扩展屏优先（有虚拟屏抓虚拟屏，无则报错不静默回落）
    bool mirrorMode = false;
    /**
     * 自适应码率（ABR）：按**发送侧拥塞信号**实时升降码率。
     *
     * 信号来自 MediaSession 的发送超时计数（socket 设了 50ms 发送超时 →
     * 超时即"内核发送缓冲已满、网络吃不下当前码率"）。这是唯一不依赖接收端配合的客观信号。
     * 升降规则见 `screen_push.cpp` 的 `Impl::abrLoop`。后端不支持运行期改码率时，
     * 会**明确记录未生效原因**（[Status::abrNote]）而不是假装在调。
     */
    bool adaptive = false;
};

class ScreenPush {
public:
    struct Status {
        bool running = false;
        std::string error;          // 最近一次失败原因（空 = 无）
        double fps = 0.0;           // 实测发送帧率
        uint64_t framesCaptured = 0;
        uint64_t framesSent = 0;
        uint64_t framesDropped = 0; // 单槽队列丢帧（宁可丢帧也不累积延迟）
        double encodeMs = 0.0;      // 编码耗时均值
        uint32_t width = 0;
        uint32_t height = 0;
        std::string deviceName;     // 实际抓屏目标（如 \\.\DISPLAY3 = 扩展屏；主屏为 \\.\DISPLAY1）

        /// 实际编码器后端（nvenc / qsv / amf / mf / raw_lz4）；空 = 未启动
        /// 面板据此显示"在用哪个编码器"（mf 在本实现里是 CPU 软编，见 mf_encoder.cpp 注释）
        std::string encoderName;
        /// 运行期改码率是否被支持（false → 自适应码率无法生效，要如实告知）
        bool runtimeBitrateOk = true;

        // —— 自适应码率（ABR）——
        bool     adaptive = false;        // 用户是否开了自适应
        bool     adaptiveActive = false;  // 是否真的在跑（后端支持 且 管线在推）
        uint32_t bitrateKbps = 0;         // **当前**码率（ABR 会改它，不一定等于设定的目标值）
        std::string abrNote;              // 未生效的原因（例如"该编码器不支持运行期改码率"）
    };

    ScreenPush();
    ~ScreenPush();
    ScreenPush(const ScreenPush&) = delete;
    ScreenPush& operator=(const ScreenPush&) = delete;

    /// 开始推流。`session` 必须**已经连接**，本类不持有它的所有权、也不负责断开。
    /// 失败时 `*err`（若给出）为原因；失败后状态回到未运行。
    bool start(MediaSession* session, const ScreenPushOptions& opt, std::string* err = nullptr);

    /// 停止推流（幂等）。不影响 session 的连接。
    void stop();

    /// 请求下一编码帧为 IDR（手机切回副屏页时调用，秒出画）
    bool requestKeyFrame();

    /// 手动指定码率（Kbps）—— ABR 关闭时的直接控制入口；不影响 ABR 的上限记忆
    bool setBitrate(uint32_t kbps);

    bool running() const;
    Status status() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace apxpc::media
