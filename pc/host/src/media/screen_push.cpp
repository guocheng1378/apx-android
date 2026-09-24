#include "apxpc/media/screen_push.hpp"

#include "apxpc/log.hpp"
#include "apxpc/media/media_session.hpp"

// apxdisp（pc/display）的公开接口：build 时通过 add_subdirectory 引入，
// 只启用静态库目标（APP / TESTS 关闭）。头文件路径由 target 的 PUBLIC include 带出。
#include "capture/i_capture.hpp"
#include "common/types.hpp"
#include "pipeline/pipeline.hpp"
#include "transport/i_transport.hpp"

#include <mutex>

namespace apxpc::media {
namespace {

using apxpc::media::kStreamVideo;

/// 视频通道：把 Pipeline/FrameWriter 组好的**整帧**原样交给 MediaSession。
/// 注意 FrameWriter 已经写好了帧头、视频扩展头、脏矩形与 CRC —— 这里**不能再封装**。
class VideoChannelAdapter : public apxdisp::IChannel {
public:
    explicit VideoChannelAdapter(MediaSession* s) : session_(s) {}

    size_t write(const uint8_t* data, size_t len, uint32_t) override {
        if (!session_) return 0;
        return session_->sendRawFrame(data, len, kStreamVideo) ? len : 0;
    }
    size_t read(uint8_t*, size_t, uint32_t) override { return 0; }  // 视频是下行
    bool reliable() const override { return false; }                // 可丢包
    void cancel() override {}
    std::string lastError() const override {
        return session_ ? session_->status().error : std::string("媒体会话不存在");
    }

private:
    MediaSession* session_;
};

/// 控制面通道：**故意丢弃写入**。
/// 控制面（鼠标/键盘/心跳）走 9500 那条连接，这条媒体连接上不该出现 streamId=3。
/// 若这里真的被写了，说明有代码走错了链路 —— 返回 len 让 Pipeline 的统计不为 0，
/// 便于在日志里显形，而不是静默吞掉。
class DiscardChannel : public apxdisp::IChannel {
public:
    size_t write(const uint8_t*, size_t len, uint32_t) override {
        APX_LOGW("媒体连接上收到了控制面写入（{} 字节）——控制面应走 9500", len);
        return len;
    }
    size_t read(uint8_t*, size_t, uint32_t) override { return 0; }
    bool reliable() const override { return true; }
    void cancel() override {}
    std::string lastError() const override { return {}; }
};

/// 把 MediaSession 伪装成 apxdisp 的 ITransport。
class TransportAdapter : public apxdisp::ITransport {
public:
    explicit TransportAdapter(MediaSession* s) : video_(s), session_(s) {}

    /// 连接由上层（面板）建立，这里**不主动连**；open 只用于如实汇报当前状态。
    /// Pipeline 的发送连续失败自愈路径会调它，所以必须反映真实连接状态。
    bool open(const apxdisp::TransportSpec&) override {
        return session_ && session_->status().connected;
    }
    void close() override {}   // 连接生命周期归上层，这里不关
    bool isOpen() const override { return session_ && session_->status().connected; }

    apxdisp::IChannel* videoChannel() override { return &video_; }
    apxdisp::IChannel* controlChannel() override { return &discard_; }
    apxdisp::IChannel* touchChannel() override { return &discard_; }

    bool reset() override { return isOpen(); }
    std::string lastError() const override {
        return session_ ? session_->status().error : std::string("媒体会话不存在");
    }
    apxdisp::TransportKind kind() const override { return apxdisp::TransportKind::Tcp; }

private:
    VideoChannelAdapter video_;
    DiscardChannel discard_;
    MediaSession* session_;
};

}  // namespace

struct ScreenPush::Impl {
    std::mutex mu;
    std::unique_ptr<apxdisp::Pipeline> pipe;
    ScreenPushOptions opt{};
    std::string error;
};

ScreenPush::ScreenPush() : impl_(std::make_unique<Impl>()) {}

ScreenPush::~ScreenPush() { stop(); }

bool ScreenPush::start(MediaSession* session, const ScreenPushOptions& opt, std::string* err) {
    stop();   // 幂等：重复点开关不该起两条管线
    std::lock_guard<std::mutex> lk(impl_->mu);

    auto fail = [&](const std::string& msg) {
        impl_->error = msg;
        if (err) *err = msg;
        APX_LOGW("副屏推流启动失败：{}", msg);
        return false;
    };

    if (!session) return fail("媒体会话不存在");
    if (!session->status().connected) return fail("媒体连接未建立（先让面板连上手机）");

    auto pipe = std::make_unique<apxdisp::Pipeline>();
    // 关键：注入共用传输，让 Pipeline 不自开第二条 TCP
    pipe->setTransport(std::make_unique<TransportAdapter>(session));

    apxdisp::PipelineConfig cfg;
    cfg.captureKind = apxdisp::CaptureKind::Auto;
    // **扩展屏模式**：优先抓 IddCx 虚拟屏（VirtualDisplayDriver 已装）——手机显示的是
    // 独立的第二块屏内容，不是主屏镜像。虚拟屏不存在时 DDA 自动回落主屏（兼容镜像）。
    cfg.captureTarget.preferVirtual = true;
    cfg.video.codec = apxdisp::CodecId::H264;   // H.264 兼容性最好；手机侧 MediaCodec 已验 H264
    cfg.video.bitrateKbps = opt.bitrateKbps;
    cfg.video.frameRateX100 = opt.maxFps * 100;
    // 0 表示"跟随抓屏尺寸"。仍给个非零兜底：编码器不接受 0 尺寸，
    // 而抓屏尺寸确定后会覆盖它（pipeline 内部以抓屏尺寸优先）。
    cfg.video.width = opt.width ? opt.width : 1920;
    cfg.video.height = opt.height ? opt.height : 1080;
    cfg.maxFps = opt.maxFps;

    // 这三个必须关：
    //   握手 —— 手机端未实现 CtrlSession 的 HELLO 应答（PC 会一直等）
    //   注入 —— 触控上行走 9500 控制通道（0x04），不经管线
    //   心跳 —— LinkMonitor 会在这条媒体连接上发控制帧，而控制面归 9500
    cfg.enableHandshake = false;
    cfg.enableInject = false;
    cfg.enableHeartbeat = false;

    std::string e;
    if (!pipe->start(cfg, &e)) {
        return fail(e.empty() ? "管线启动失败（抓屏或编码器不可用）" : e);
    }

    impl_->pipe = std::move(pipe);
    impl_->opt = opt;
    impl_->error.clear();
    APX_LOGI("副屏推流已启动（{} fps 上限，{} Kbps，H.264）", opt.maxFps, opt.bitrateKbps);
    return true;
}

void ScreenPush::stop() {
    std::lock_guard<std::mutex> lk(impl_->mu);
    if (!impl_->pipe) return;
    impl_->pipe->stop();
    impl_->pipe.reset();
    APX_LOGI("副屏推流已停止");
}

bool ScreenPush::requestKeyFrame() {
    std::lock_guard<std::mutex> lk(impl_->mu);
    if (!impl_->pipe || !impl_->pipe->running()) return false;
    impl_->pipe->requestKeyFrame();
    return true;
}

bool ScreenPush::running() const {
    std::lock_guard<std::mutex> lk(impl_->mu);
    return impl_->pipe && impl_->pipe->running();
}

ScreenPush::Status ScreenPush::status() const {
    std::lock_guard<std::mutex> lk(impl_->mu);
    Status s;
    s.error = impl_->error;
    if (!impl_->pipe) return s;
    s.running = impl_->pipe->running();
    const auto& st = impl_->pipe->stats();
    s.framesCaptured = st.framesCaptured;
    s.framesSent = st.framesSent;
    s.framesDropped = st.framesDropped;
    s.fps = st.fpsActual;
    s.encodeMs = st.encodeMsAvg;
    // 分辨率必须读实际生效值：抓屏尺寸优先，配置里的宽高可能被覆盖
    const auto vp = impl_->pipe->usedVideo();
    s.width = vp.width;
    s.height = vp.height;
    if (s.error.empty()) s.error = impl_->pipe->lastError();
    return s;
}

}  // namespace apxpc::media
