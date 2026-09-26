#include "apxpc/media/screen_push.hpp"

#include "apxpc/log.hpp"
#include "apxpc/media/media_session.hpp"

// apxdisp（pc/display）的公开接口：build 时通过 add_subdirectory 引入，
// 只启用静态库目标（APP / TESTS 关闭）。头文件路径由 target 的 PUBLIC include 带出。
#include "capture/i_capture.hpp"
#include "common/types.hpp"
#include "pipeline/pipeline.hpp"
#include "transport/i_transport.hpp"

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <thread>

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
/// 控制面（鼠标/键盘/心跳）走 9511 那条连接，这条媒体连接上不该出现 streamId=3。
/// 若这里真的被写了，说明有代码走错了链路 —— 返回 len 让 Pipeline 的统计不为 0，
/// 便于在日志里显形，而不是静默吞掉。
class DiscardChannel : public apxdisp::IChannel {
public:
    size_t write(const uint8_t*, size_t len, uint32_t) override {
        APX_LOGW("媒体连接上收到了控制面写入（{} 字节）——控制面应走 9511", len);
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

    // —————————————————————— 自适应码率（ABR） ——————————————————————
    // 为什么放在这一层：只有 ScreenPush 同时握有 Pipeline（能改码率）与 MediaSession（能看到拥塞）。
    MediaSession* session = nullptr;      // 只借用，不持有所有权
    std::thread abr;
    std::atomic<bool> abrRun{false};
    std::atomic<bool> abrActive{false};
    std::atomic<bool> abrUnsupported{false};
    std::atomic<uint32_t> curKbps{0};
    std::string abrNote;                   // 未生效原因（读时持 mu）

    void abrLoop() {
        const uint32_t maxKbps = opt.bitrateKbps;
        // 下限取初始值的 1/4：再低画面就没法看了，宁可卡也不糊成马赛克
        const uint32_t minKbps = std::max<uint32_t>(1000, opt.bitrateKbps / 4);
        uint32_t cur = opt.bitrateKbps;
        uint64_t lastTimeouts = 0, lastDropped = 0;
        int calm = 0;
        APX_LOGI("副屏自适应码率已启用：{}–{} Kbps，每 2 秒按发送拥塞调整", minKbps, maxKbps);

        while (abrRun.load()) {
            // 分片睡眠：退出时不必等满 2 秒
            for (int i = 0; i < 20 && abrRun.load(); ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            if (!abrRun.load()) break;

            auto* p = pipe.get();
            auto* s = session;
            if (!p || !s) break;

            if (!p->supportsRuntimeBitrate()) {
                // 如实停用：不要每轮都刷日志，也不要假装在调码率
                if (!abrUnsupported.exchange(true)) {
                    std::lock_guard<std::mutex> lk(mu);
                    abrNote = "当前编码器不支持运行期改码率，自适应已停用（码率固定 "
                              + std::to_string(maxKbps) + " Kbps）";
                    APX_LOGW("副屏自适应码率停用：{}", abrNote.c_str());
                }
                abrActive.store(false);
                break;
            }

            const auto c = s->counters();
            const size_t q = s->queuedFrames();
            const bool congested =
                (c.sendTimeouts > lastTimeouts) || (c.dropped > lastDropped) || q > 8;
            lastTimeouts = c.sendTimeouts;
            lastDropped = c.dropped;

            if (congested) {
                calm = 0;
                if (cur > minKbps) {
                    cur = std::max(minKbps, cur * 3 / 4);
                    p->setBitrate(cur);
                    curKbps.store(cur);
                    APX_LOGW("副屏自适应：网络拥塞（发送超时 {} · 丢弃 {} · 队列 {}）→ 码率降到 {} Kbps",
                             c.sendTimeouts, c.dropped, q, cur);
                }
            } else if (++calm >= 3 && cur < maxKbps) {
                // 连续 3 轮（6 秒）无压力才回升，避免在临界点来回抖
                cur = std::min(maxKbps, cur * 5 / 4);
                p->setBitrate(cur);
                curKbps.store(cur);
                calm = 0;
                APX_LOGI("副屏自适应：链路平稳 → 码率回升到 {} Kbps", cur);
            }
        }
        APX_LOGI("副屏自适应码率已退出");
    }

    void startAbr(MediaSession* s) {
        if (!opt.adaptive || !s) return;
        session = s;
        curKbps.store(opt.bitrateKbps);
        abrUnsupported.store(false);
        abrActive.store(true);
        abrRun.store(true);
        abr = std::thread([this] { abrLoop(); });
    }

    /// 必须在 `pipe.reset()` **之前**调用：ABR 线程要读 pipe.get()
    void stopAbr() {
        abrRun.store(false);
        if (abr.joinable()) abr.join();
        abrActive.store(false);
        session = nullptr;
    }
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
    // 抓屏目标由面板选择：镜像 = 主屏；扩展屏 = 优先 IddCx 虚拟屏（无虚拟屏时报错不回落）
    cfg.captureTarget.preferVirtual = !opt.mirrorMode;
    // ★ 固定 H.264（**故意不用 HEVC**）：接收端可能是老电视盒子（例如斐讯 q201 / Android 7.1.2），
    //   它的 MediaCodec 未必有 HEVC 硬解，而接收侧目前没有"上报我支持什么编码"的能力，
    //   一旦发出它解不了的码流就是黑屏/极卡。H.264 是各家都有的最低公共分母。
    cfg.video.codec = apxdisp::CodecId::H264;
    cfg.video.bitrateKbps = opt.bitrateKbps;
    cfg.video.frameRateX100 = opt.maxFps * 100;
    // 0 表示"跟随抓屏尺寸"。仍给个非零兜底：编码器不接受 0 尺寸，
    // 而抓屏尺寸确定后会覆盖它（pipeline 内部以抓屏尺寸优先）。
    cfg.video.width = opt.width ? opt.width : 1920;
    cfg.video.height = opt.height ? opt.height : 1080;
    cfg.maxFps = opt.maxFps;

    // 这三个必须关：
    //   握手 —— 手机端未实现 CtrlSession 的 HELLO 应答（PC 会一直等）
    //   注入 —— 触控上行走 9511 控制面（0x04），不经管线
    //   心跳 —— LinkMonitor 会在这条媒体连接上发控制帧，而控制面归 9511
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
    APX_LOGI("副屏推流已启动（{} fps 上限，{} Kbps，H.264{}）", opt.maxFps, opt.bitrateKbps,
             opt.adaptive ? "，自适应码率开" : "");
    // ABR 必须在 pipe / session 都就位之后再起线程
    impl_->startAbr(session);
    return true;
}

void ScreenPush::stop() {
    std::lock_guard<std::mutex> lk(impl_->mu);
    if (!impl_->pipe) return;
    impl_->stopAbr();   // 必须在 pipe.reset() 之前：ABR 线程要读 pipe.get()
    impl_->pipe->stop();
    impl_->pipe.reset();
    APX_LOGI("副屏推流已停止");
}

bool ScreenPush::setBitrate(uint32_t kbps) {
    std::lock_guard<std::mutex> lk(impl_->mu);
    if (!impl_->pipe || kbps == 0) return false;
    impl_->curKbps.store(kbps);
    return impl_->pipe->setBitrate(kbps);
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
    s.deviceName = impl_->pipe->captureDeviceName();
    s.encoderName = impl_->pipe->encoderName();
    s.runtimeBitrateOk = impl_->pipe->supportsRuntimeBitrate();
    if (s.error.empty()) s.error = impl_->pipe->lastError();
    // —— 自适应码率：把"开没开、真在跑没、现在多少、为什么没生效"如实报出来 ——
    s.adaptive = impl_->opt.adaptive;
    s.adaptiveActive = impl_->abrActive.load();
    s.abrNote = impl_->abrNote;
    const uint32_t abrCur = impl_->curKbps.load();
    s.bitrateKbps = abrCur ? abrCur : impl_->opt.bitrateKbps;
    // 支持与否还能从管线直接问（ABR 线程可能还没轮到第一次检查）
    if (s.adaptive && s.abrNote.empty() && !impl_->pipe->supportsRuntimeBitrate()) {
        s.abrNote = "当前编码器不支持运行期改码率，自适应无法生效（码率固定）";
    }
    return s;
}

}  // namespace apxpc::media
