#pragma once
// 手机麦克风 → PC 播放设备 的**音频桥**。
//
// 手机上行 PCM（48k/16bit/立体声，streamId=5）到达后 feed() 进来，这里用
// WASAPI shared-mode **渲染**到指定播放设备：
//   - 选真实声卡（Realtek）＝ 把手机声音外放（听手机）；
//   - 选虚拟声卡的输入端（如 VB-Cable 的 CABLE Input）＝ 微信/会议软件选
//     CABLE Output 当麦克风，即可用手机麦通话 —— Windows 没有免驱虚拟麦克风，
//     借"渲染→虚拟声卡→采集"这条桥是标准做法。
//
// 线程模型：feed() 任意线程（媒体收流线程）；渲染在自建线程，缺数据填静音保时钟。
#include <cstdint>
#include <string>

namespace apxpc::media {

class MicBridge {
public:
    struct Status {
        bool running = false;
        std::string device;      // 正在渲染到的设备名
        uint64_t fed = 0;        // 收到的样本数（int16 单声道计）
        uint64_t played = 0;     // 已送渲染的样本数
        uint64_t dropped = 0;    // 缓冲溢出丢弃
        std::string error;
    };

    MicBridge() = default;
    ~MicBridge() { stop(); }
    MicBridge(const MicBridge&) = delete;
    MicBridge& operator=(const MicBridge&) = delete;

    /// @param renderDeviceId WASAPI endpoint id（空 = 系统默认播放设备）。
    ///        列表来自 AudioCapture::listRenderDevices（与面板下拉同源）。
    bool start(const std::string& renderDeviceId, std::string* err = nullptr);
    void stop();
    bool running() const;

    /// 喂入手机上行 PCM。格式必须 48kHz / 16bit / 立体声（interleaved）。
    /// @param samples int16 样本数（含两个声道）；@param bytes 字节数
    void feed(const void* data, size_t bytes);

    Status status() const;

private:
    void renderLoop();
    void shutdownLocked();

    struct Impl;
    Impl* impl_ = nullptr;
};

}  // namespace apxpc::media
