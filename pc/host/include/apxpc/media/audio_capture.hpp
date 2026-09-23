#pragma once
// 「音箱」：把 **PC 的系统声音**送到手机扬声器。
//
// 采集走 **WASAPI loopback**（`AUDCLNT_STREAMFLAGS_LOOPBACK`）—— 这是 Windows 自带的
// 能力，**不需要任何驱动**：loopback 就是"把某个渲染端点正在播放的数据再取一份"。
//
// ## 采哪一路，是这块最容易踩的坑
// 注意 loopback 取的是**数字流**，与"那块设备有没有真的发出声音"无关 —— 所以哪怕默认
// 播放设备是没喇叭的 HDMI 显示器，手机上照样能听到。真正会踩的是**采错了设备**：
// 用户戴着耳机听、而默认设备是显示器音频，手机上就会是另一路（或者干脆静音）。
// 因此这里允许**指定设备**；`deviceId` 留空则跟随「系统默认播放设备」。
//
// ## 格式
// 两端必须一致：**48000 Hz / 16bit / 立体声 / 小端**（见 `media_session.hpp` 的
// `kAudioSampleRate` 等常量）。优先用 `AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM` 让 WASAPI
// 直接把设备混音格式转成这个格式；失败才退回"按混音格式采 + 自己转"。
//
// ## 线程与生命周期
// 采集循环跑在**自己的工作线程**上（`IAudioClient` 事件回调模式），
// 面板在 UI 线程只调 start/stop/status。`session` 由调用方持有，本类不负责连接与断开。
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace apxpc::media {

class MediaSession;

/// 一个**渲染（播放）端点**。`id` 是 `IMMDevice::GetId()`，跨进程稳定，适合持久化。
struct AudioDeviceInfo {
    std::string id;
    std::string name;          // 友好名，如 "扬声器 (Realtek(R) Audio)"
    bool isDefault = false;    // 是否为当前系统默认播放设备
};

struct AudioCaptureOptions {
    /// 每片时长（毫秒）。10ms = 1920 字节，与手机侧 AudioTrack 的分片粒度一致；
    /// 调大能抗抖动但增加延迟，调小反之。
    uint32_t frameMs = 10;

    /// 要采集的端点 ID（取 `AudioDeviceInfo::id`）。
    /// **留空 = 跟随系统默认播放设备**（默认变更时会由持有方重新 start 来跟随）。
    std::string deviceId;
};

class AudioCapture {
public:
    struct Status {
        bool running = false;
        std::string error;        // 最近一次失败原因（空 = 无）
        std::string device;       // 实际采集的端点名（用户可据此确认采的是哪块声卡）
        std::string deviceId;     // 实际采集的端点 ID（用于比对"默认是否换了"）
        bool followingDefault = false;  // true = 跟系统默认；false = 钉死在 deviceId
        uint32_t sampleRate = 0;  // 实际送给手机的采样率（应恒为 48000）
        uint32_t channels = 0;
        uint64_t framesSent = 0;  // 已送出的分片数
        uint64_t bytesSent = 0;
        uint64_t dropped = 0;     // 发送失败/队列满被丢弃的分片
        double peak = 0.0;        // 最近一片的峰值（0..1）—— 用来区分「有声音」与「系统本身静音」
    };

    AudioCapture();
    ~AudioCapture();
    AudioCapture(const AudioCapture&) = delete;
    AudioCapture& operator=(const AudioCapture&) = delete;

    /// 开始采集。`session` 必须**已经连接**；不持有其所有权。
    /// 若 `opt.deviceId` 非空但该设备已不存在（拔了/换过），会明确失败而不是悄悄回落到默认。
    bool start(MediaSession* session, const AudioCaptureOptions& opt = {},
               std::string* err = nullptr);

    /// 停止采集（幂等）。不影响 session 的连接。
    void stop();

    bool running() const;
    Status status() const;

    /// 枚举所有**已启用**的渲染（播放）端点，默认设备排在第一位。
    /// 失败（或本机没有任何播放设备）返回空表，不抛异常。
    static std::vector<AudioDeviceInfo> listRenderDevices();

    /// 当前默认渲染端点的 ID（空 = 取不到）。
    static std::string defaultRenderDeviceId();

    /// 当前默认渲染端点的友好名（未开始采集时也能显示"将会采哪块"）。
    static std::string defaultRenderDeviceName();

    /// 合成一段测试音（默认 1kHz 正弦，48k/16bit/立体声）直接经 `session` 推到手机，
    /// **不依赖系统此刻是否在放声音**——用于一键验证下行链路是否真的通到手机。
    /// `seconds` 为试听时长（限 1–10s）；`outSent` 若非空，结束后写入实际送出的分片数，
    /// 便于 UI 区分「没送到手机」与「送到了但手机没播」。要求 `session` 已连接。
    static void playTestTone(MediaSession* session, int seconds = 2,
                             std::atomic<uint64_t>* outSent = nullptr);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace apxpc::media
