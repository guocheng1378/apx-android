// 下行链路编排：抓屏 → 编码 → 组帧 → 传输（+ 上行触控注入 + 链路监控）
//
// 线程模型（三线程 + 一个监控线程）：
//   capture 线程：AcquireNextFrame（VSync 节奏）→ 放入待编码队列（容量 1，旧帧直接丢）
//   encode  线程：取帧 → 编码 → FrameWriter 组帧 → 写 video channel
//   inject  线程：读 touch channel → 解析 → 注入
//   monitor 线程：心跳 / RTT / 断线自愈
//
// 「可丢包」的落地位置：待编码队列容量为 1，若上一帧还没被取走就用新帧覆盖它 ——
// 这是副屏场景最关键的一条：宁可丢帧，也不能让编码/传输的排队延迟累积。
#pragma once

#include <atomic>
#include <mutex>
#include <thread>

#include "capture/i_capture.hpp"
#include "common/clock.hpp"
#include "common/types.hpp"
#include "encode/i_encoder.hpp"
#include "inject/i_inject.hpp"
#include "transport/ctrl_channel.hpp"
#include "transport/frame_writer.hpp"
#include "transport/i_transport.hpp"
#include "transport/link_monitor.hpp"

namespace apxdisp {

struct PipelineConfig {
    // 抓屏
    CaptureKind   captureKind = CaptureKind::Auto;
    CaptureTarget captureTarget{};
    // 编码
    EncoderBackend backend = EncoderBackend::None;  // None = 自动探测
    VideoParams    video{};
    // 传输
    TransportKind transportKind = TransportKind::Auto;
    UsbFilter     usb{};
    // 泛化传输描述（kind != Auto 时优先于上面的 transportKind/usb，用于 TCP / 无线调试）
    TransportSpec transportSpec{};
    // 注入
    InjectTier    injectTier = InjectTier::Auto;
    InjectTarget  injectTarget{};
    // 运行
    uint32_t      maxFps = 60;                 // 0 = 不限
    size_t        maxFragmentBytes = kDefaultMaxFragment;
    // v1.10：跳过 §4 控制面握手（HELLO/CONFIG）——手机端尚未实现应答，
    // 直接推流（手机 VideoReceiver 按 streamId 分流，ctrl 帧仅计数，安全）。
    bool          enableHandshake = true;
    bool          enableInject = true;
    bool          enableHeartbeat = true;
};

class Pipeline {
public:
    Pipeline();
    ~Pipeline();

    bool start(const PipelineConfig& cfg, std::string* err = nullptr);
    void stop();
    bool running() const { return running_.load(); }

    /// 请求下一编码帧为 IDR（手机端切回副屏页时调用，立刻出画不用等 GOP）。
    /// 只是置标志，由编码线程在下一次 encode 前消费。
    void requestKeyFrame() { keyReq_.store(true); }

    const PipelineStats& stats() const { return stats_; }

    /// 实际生效的编码参数。**抓屏尺寸优先于配置**，所以它常与传入的 cfg 不同 ——
    /// 面板要显示"真实推的是多少分辨率"就得读这里，不能拿自己填的值当结论。
    VideoParams usedVideo() const { return usedVp_; }
    /// 实际抓屏目标设备名（如 \\.\DISPLAY3）。空 = 抓屏源未提供。
    std::string captureDeviceName() const;
    std::string lastError() const;

    // 单步执行（供自测/离线：抓一帧 → 编码 → 组帧 → 写通道）
    bool stepOnce();

    // 上行：读一帧触控并注入（返回 false 表示无数据）
    bool pollTouchOnce(uint32_t timeoutMs = 5);

    // 复用**外部已建立**的传输实例。设置后 start() 不再自行 createTransport。
    // 为什么需要：手机侧的媒体通道是单对端语义（一份连接同时跑副屏/音频/摄像头），
    // 副屏不能再自开第二条 TCP —— 必须借用上层已经连好的那条。
    void setTransport(std::unique_ptr<ITransport> t) { injectedTransport_ = std::move(t); }

    // 供自测使用的内部句柄
    ITransport* transport() { return transport_.get(); }
    FrameWriter* frameWriter() { return frameWriter_.get(); }
    ICapture* capture() { return capture_.get(); }

private:
    void captureThread();
    void encodeThread();
    void touchThread();

    bool handshake(std::string* err);

    PipelineConfig cfg_{};
    TransportSpec  spec_{};   // start() 解析出的实际传输描述（供重连复用）
    std::atomic<bool> running_{false};
    std::atomic<bool> keyReq_{false};   // 手机端请求下一帧为 IDR

    std::unique_ptr<ICapture>   capture_;
    std::unique_ptr<IEncoder>   encoder_;
    std::unique_ptr<ITransport> injectedTransport_;   // setTransport() 注入；start() 优先用它
    std::unique_ptr<ITransport> transport_;
    std::unique_ptr<IInjector>  injector_;
    std::unique_ptr<FrameWriter> frameWriter_;
    std::unique_ptr<CtrlSession> ctrl_;
    std::unique_ptr<LinkMonitor> monitor_;
    ClockSync clock_{};
    uint32_t sendFailStreak_ = 0;   // v1.10：连续发送失败计数（触发自动重连）
    uint32_t slowEncodeStreak_ = 0; // v1.10：编码缓慢连续计数（触发编码器重建自愈）
    VideoParams usedVp_{};          // v1.10：实际生效的编码参数（抓屏尺寸优先）
    // v1.10：真实帧率统计——fpsActual 此前从未被赋值（统计行恒显示 0.0 fps，
    // 真机 2026-09-21 21:05）。按 500ms 滑窗统计发送帧数换算 fps。
    int64_t  fpsWinStartNs_ = 0;
    uint32_t fpsWinFrames_  = 0;

    // 单槽待编码队列（旧帧覆盖 = 丢帧）
    std::mutex frameMutex_;
    RawFrame   pendingFrame_{};
    std::vector<uint8_t> pendingPixels_;   // 深拷贝像素，避免抓屏缓冲被复用
    bool pendingValid_ = false;
    uint64_t pendingSeq_ = 0;
    uint64_t consumedSeq_ = 0;

    // 上一帧副本（虚拟屏静态时 DWM 不持续 present，按节拍重发保持流连续）
    RawFrame   lastFrame_{};
    std::vector<uint8_t> lastPixels_;
    bool haveLastFrame_ = false;
    int64_t lastFrameNs_ = 0;

    PipelineStats stats_{};
    std::string lastError_;

    std::thread captureThread_;
    std::thread encodeThread_;
    std::thread touchThread_;
};

}  // namespace apxdisp
