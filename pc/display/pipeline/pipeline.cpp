#include "pipeline/pipeline.hpp"

#include <chrono>
#include <cstring>
#include <thread>

#include "capture/i_capture.hpp"
#include "common/log.hpp"
#include "encode/i_encoder.hpp"
#include "inject/i_inject.hpp"
#include "inject/touch_frame.hpp"
#include "protocol/frame_format.hpp"
#include "transport/frame_writer.hpp"
#include "transport/i_transport.hpp"

namespace apxdisp {

Pipeline::Pipeline() = default;
Pipeline::~Pipeline() { stop(); }

std::string Pipeline::lastError() const { return lastError_; }

bool Pipeline::handshake(std::string* err) {
    // PROTOCOL §4：HELLO → HELLO_ACK → CONFIG → CONFIG_ACK → RUNNING
    if (!ctrl_) return true;
    if (!ctrl_->sendHello(static_cast<uint64_t>(nowNs()),
                          kCapHevc | kCapH264 | kCapRawLz4 | kCapTouchBulk)) {
        lastError_ = ctrl_->lastError();
        if (err) *err = lastError_;
        return false;
    }
    HelloAckPayload ack{};
    if (!ctrl_->waitHelloAck(ack, 3000)) {
        lastError_ = ctrl_->lastError();
        if (err) *err = lastError_;
        return false;
    }
    // ARCHITECTURE §6.1 速度门禁：非 super-speed 必须降级并告警
    if (ack.udcSpeed != kSpeedSuper && ack.udcSpeed != kSpeedSuperPlus) {
        APX_LOG_W("链路非 USB 3.0 SuperSpeed（speed=%u）：必须降级运行（降帧率/降分辨率/降码率）",
                  ack.udcSpeed);
    }
    // 用手机端时基建立 offset：pc_time ≈ phone_time + offset
    clock_.addSample(static_cast<int64_t>(nowNs()), static_cast<int64_t>(ack.phoneClockNs), nowNs());

    ConfigPayload cfg{};
    cfg.displayWidth = cfg_.video.width;
    cfg.displayHeight = cfg_.video.height;
    cfg.refreshRateX100 = cfg_.video.frameRateX100;
    cfg.codecId = static_cast<uint16_t>(cfg_.video.codec);
    cfg.bitrateKbps = cfg_.video.bitrateKbps;
    cfg.frameRateX100 = cfg_.video.frameRateX100;
    cfg.displayMode = 0;
    if (!ctrl_->sendConfig(cfg) || !ctrl_->waitConfigAck(3000)) {
        lastError_ = ctrl_->lastError();
        if (err) *err = lastError_;
        return false;
    }
    return true;
}

bool Pipeline::start(const PipelineConfig& cfg, std::string* err) {
    cfg_ = cfg;

    capture_ = createCapture(cfg_.captureKind, cfg_.captureTarget);
    if (!capture_ || !capture_->open(cfg_.captureTarget)) {
        lastError_ = capture_ ? capture_->lastError() : "无法创建抓屏源";
        if (err) *err = lastError_;
        return false;
    }

    VideoParams vp = cfg_.video;
    // v1.10：**抓屏实际尺寸优先**——DDA 回退主屏时帧是主屏分辨率，若编码器
    // 按 --mode 配置（1080x2400）而输入帧是 1920x1080，NV12 尺寸/码流元数据
    // 全部错位，手机解码器必失败。--mode 尺寸仅对 IddCx 虚拟屏有意义。
    const auto& cinfo = capture_->info();
    if (cinfo.width > 0 && cinfo.height > 0) {
        vp.width = cinfo.width;
        vp.height = cinfo.height;
    }
    usedVp_ = vp;   // v1.10：保存实际编码参数（供长跑衰减时的编码器重建）
    std::string encErr;
    encoder_ = EncoderFactory::create(cfg_.backend, vp, &encErr);
    if (!encoder_) {
        lastError_ = "无可用编码器：" + encErr;
        if (err) *err = lastError_;
        return false;
    }

    // v1.10：TCP 传输接入——transportSpec 非默认（kind != Auto）时优先使用，
    // 支持副屏无线 / NCM 网络 / adb reverse 等全部 TCP 承载；USB 主线走旧路径。
    if (cfg_.transportSpec.kind != TransportKind::Auto) {
        spec_ = cfg_.transportSpec;
        transport_ = createTransport(spec_);
    } else {
        spec_ = TransportSpec::usbSpec(cfg_.usb);
        spec_.kind = cfg_.transportKind;
        transport_ = createTransport(cfg_.transportKind, cfg_.usb);
    }
    if (!transport_) {
        lastError_ = "无可用传输通道";
        if (err) *err = lastError_;
        return false;
    }
    frameWriter_ = std::make_unique<FrameWriter>(transport_->videoChannel());
    frameWriter_->setMaxFragment(cfg_.maxFragmentBytes);
    ctrl_ = std::make_unique<CtrlSession>(transport_->controlChannel());

    // v1.10：enableHandshake=false 时跳过 §4 握手（手机端应答未实现，先直连推流）
    if (cfg_.enableHandshake && !handshake(err)) return false;

    if (cfg_.enableHeartbeat) {
        monitor_ = std::make_unique<LinkMonitor>(transport_->controlChannel(), ctrl_.get(), &clock_);
        monitor_->onReset = [this] { return transport_ && transport_->reset(); };
        monitor_->onReconnect = [this] {
            if (!transport_) return false;
            transport_->close();
            const bool ok = transport_->open(spec_);
            if (ok && !handshake(nullptr)) return false;
            return ok;
        };
        monitor_->start();
    }

    if (cfg_.enableInject) {
        injector_ = createInjector(cfg_.injectTier);
        if (injector_ && !injector_->init(cfg_.injectTarget)) {
            APX_LOG_W("档2 注入不可用（%s），降级到档1", injector_->lastError().c_str());
            injector_ = createInjector(InjectTier::SendInput);
            if (injector_) injector_->init(cfg_.injectTarget);
        }
        if (!injector_) APX_LOG_W("触控注入不可用");
    }

    running_.store(true);
    captureThread_ = std::thread([this] { captureThread(); });
    encodeThread_ = std::thread([this] { encodeThread(); });
    if (injector_) touchThread_ = std::thread([this] { touchThread(); });
    return true;
}

void Pipeline::stop() {
    if (!running_.exchange(false)) return;
    if (monitor_) monitor_->stop();
    if (captureThread_.joinable()) captureThread_.join();
    if (encodeThread_.joinable()) encodeThread_.join();
    if (touchThread_.joinable()) touchThread_.join();
    if (ctrl_) ctrl_->sendBye();          // §4：任意端 BYE → 手机恢复原 USB 配置
    if (injector_) injector_->shutdown();
    if (encoder_) encoder_->shutdown();
    if (capture_) capture_->close();
    if (transport_) transport_->close();
}

// ------------------------------------------------------------ 抓屏线程 ----
void Pipeline::captureThread() {
    const uint32_t frameBudgetUs = cfg_.maxFps ? (1000000u / cfg_.maxFps) : 0;
    while (running_.load()) {
        const int64_t t0 = nowNs();
        RawFrame frame{};
        if (!capture_->acquireFrame(frame, 16)) {
            if (capture_->needsReopen()) {
                APX_LOG_W("抓屏会话失效，尝试重建");
                capture_->close();
                if (!capture_->open(cfg_.captureTarget)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(200));
                    continue;
                }
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }

        // ptsNs 必须换到手机端时基（PROTOCOL §1）
        frame.ptsNs = static_cast<uint64_t>(clock_.toRemoteNs(nowNs()));

        {
            std::lock_guard<std::mutex> lk(frameMutex_);
            // 深拷贝 CPU 像素；D3D 纹理无法跨帧持有，暂不用于异步路径
            if (frame.cpuData && frame.stride && frame.height) {
                pendingPixels_.assign(frame.cpuData,
                                      frame.cpuData + static_cast<size_t>(frame.stride) * frame.height);
                frame.cpuData = pendingPixels_.data();
            }
            pendingFrame_ = frame;
            pendingValid_ = true;
            pendingSeq_++;
            ++stats_.framesCaptured;
        }

        stats_.captureMsAvg = ema(stats_.captureMsAvg, (nowNs() - t0) / 1e6);
        if (frameBudgetUs) {
            const int64_t elapsedUs = (nowNs() - t0) / 1000;
            if (elapsedUs < static_cast<int64_t>(frameBudgetUs)) {
                std::this_thread::sleep_for(
                    std::chrono::microseconds(static_cast<int64_t>(frameBudgetUs) - elapsedUs));
            }
        }
    }
}

// ------------------------------------------------------------ 编码线程 ----
void Pipeline::encodeThread() {
    while (running_.load()) {
        RawFrame frame{};
        bool have = false;
        {
            std::lock_guard<std::mutex> lk(frameMutex_);
            if (pendingValid_) {
                frame = pendingFrame_;
                pendingValid_ = false;
                consumedSeq_ = pendingSeq_;
                have = true;
            }
        }
        if (!have) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }

        const int64_t t0 = nowNs();
        EncodedPacket pkt{};
        if (!encoder_->encode(frame, pkt)) {
            APX_LOG_W("编码失败：%s", encoder_->lastError().c_str());
            ++stats_.framesDropped;
            continue;
        }
        // v1.10：首帧输入后 MFT 需要 1-2 帧才产出输出（NEED_MORE_INPUT），
        // 此时 encode 返回 true 但 bytes 为空——不算失败也不发送。
        if (pkt.bytes.empty()) continue;
        ++stats_.framesEncoded;
        stats_.encodeMsAvg = ema(stats_.encodeMsAvg, (nowNs() - t0) / 1e6);

        // v1.10：软编 MFT 长跑性能衰减（真机实测编码 11ms→32ms 持续恶化）——
        // 编码耗时连续 5 秒超 40ms 即重建编码器自愈（重建耗时约 200ms 一次卡顿，
        // 换取此后恢复 10ms 级速度）。
        if (stats_.encodeMsAvg > 40.0) {
            if (++slowEncodeStreak_ > 150) {
                slowEncodeStreak_ = 0;
                APX_LOG_W("编码持续缓慢（%.1fms），重建编码器自愈", stats_.encodeMsAvg);
                encoder_->shutdown();
                if (!encoder_->configure(usedVp_) || !encoder_->encode(frame, pkt) || pkt.bytes.empty()) {
                    APX_LOG_E("编码器重建失败：%s", encoder_->lastError().c_str());
                    continue;
                }
            }
        } else {
            slowEncodeStreak_ = 0;
        }

        const int64_t t1 = nowNs();
        const size_t written = frameWriter_->writeVideo(pkt, 20 /*视频通道超时 20ms 即丢*/);
        if (written == 0) {
            ++stats_.framesDropped;
            // v1.10：no-handshake 模式（心跳禁用）下发送连续失败 = 链路真断
            // （WiFi 抖动/手机端断开）——自动重连，否则永久黑屏（真机「推一会
            // 就断」根因）。手机端 EOF 后会重新 accept，两端天然握手。
            if (++sendFailStreak_ >= 30) {
                sendFailStreak_ = 0;
                APX_LOG_W("连续 30 帧发送失败，尝试重连传输…");
                transport_->close();
                if (transport_->open(spec_)) {
                    APX_LOG_I("传输重连成功，恢复推流");
                    frameWriter_->setMaxFragment(cfg_.maxFragmentBytes);
                } else {
                    APX_LOG_E("重连失败：%s（继续尝试）", transport_->lastError().c_str());
                }
            }
        } else {
            sendFailStreak_ = 0;
            ++stats_.framesSent;
            stats_.bytesSent += pkt.bytes.size();
            stats_.dirtyRectsSent += pkt.dirty.rects.size();
        }
        // v1.10：500ms 滑窗换算真实发送帧率（此前 fpsActual 恒 0.0）
        {
            const int64_t now = nowNs();
            if (fpsWinStartNs_ == 0) fpsWinStartNs_ = now;
            ++fpsWinFrames_;
            const double dtMs = (now - fpsWinStartNs_) / 1e6;
            if (dtMs >= 500.0) {
                stats_.fpsActual = fpsWinFrames_ * 1000.0 / dtMs;
                fpsWinStartNs_ = now;
                fpsWinFrames_ = 0;
            }
        }
        stats_.transportMsAvg = ema(stats_.transportMsAvg, (nowNs() - t1) / 1e6);

        const double e2e = (nowNs() - static_cast<int64_t>(frame.ptsNs)) / 1e6;
        stats_.endToEndMsAvg = ema(stats_.endToEndMsAvg, e2e);
    }
}

// ------------------------------------------------------------ 触控线程 ----
bool Pipeline::pollTouchOnce(uint32_t timeoutMs) {
    if (!injector_ || !transport_) return false;
    IChannel* ch = transport_->touchChannel();
    if (!ch) return false;
    std::vector<uint8_t> buf(4096);
    const size_t n = ch->read(buf.data(), buf.size(), timeoutMs);
    if (n < sizeof(apx::ApxFrameHeader)) return false;

    ParsedFrame parsed{};
    if (!parseFrame(buf.data(), n, parsed)) return false;
    if (parsed.header.streamId != apx::kStreamTouch) return false;

    TouchFrame tf{};
    if (!parseTouchFrame(parsed.payload, parsed.payloadLen, tf)) return false;
    return injector_->inject(tf);
}

void Pipeline::touchThread() {
    while (running_.load()) {
        if (!pollTouchOnce(5)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
}

// ------------------------------------------------------------ 单步自测 ----
bool Pipeline::stepOnce() {
    if (!capture_ || !encoder_ || !frameWriter_) return false;
    RawFrame frame{};
    if (!capture_->acquireFrame(frame, 100)) return false;
    frame.ptsNs = static_cast<uint64_t>(clock_.toRemoteNs(nowNs()));
    EncodedPacket pkt{};
    if (!encoder_->encode(frame, pkt)) return false;
    ++stats_.framesCaptured;
    ++stats_.framesEncoded;
    const size_t written = frameWriter_->writeVideo(pkt, 0);
    if (written) {
        ++stats_.framesSent;
        stats_.bytesSent += pkt.bytes.size();
        stats_.dirtyRectsSent += pkt.dirty.rects.size();
    }
    return written > 0;
}

}  // namespace apxdisp
