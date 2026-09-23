#pragma once
// Wi‑Fi **媒体通道**（PC = 客户端，手机 = 服务端 9502）。
//
// 与控制面（`wireless_link.cpp`，9500）**并列的第二条连接**，不是同一条多路复用：
// 控制面是单对端语义且已真机验证，把 10Mbps 级视频混进同一条连接会①让视频的拥塞
// 直接卡住输入延迟（输入是 60 次/秒的小帧）②迫使重构那条已验证的链路。
// 媒体单独占连接与端口，两者可独立启停 —— 关副屏不影响键盘鼠标。
//
// 端口约定（需与 Android `wireless/TcpMediaChannel.kt` 一致）：
//   9500 TCP 控制面 / 9501 UDP 信标 / 9502 TCP 媒体
//
// 通道号（`shared/include/apx/frame.h`，v1.11 起带方向；本端是发出还是接收见注释）：
//   0 video    本端**发出** —— 副屏画面（H264/HEVC/AV1/RAW_LZ4，分片）
//   1 audio    本端**发出** —— 音箱：PC 系统声（PCM s16le / 48k / 立体声）
//   5 mic      本端**接收** —— 麦克风：手机录音（同上格式）
//   6 camera   本端**接收** —— 摄像头：JPEG 帧
//
// 握手与 Android 侧逐字节一致：PC → 手机 `u32 LE 长度 + UTF-8 令牌`，**无回执字节**。
//
// 线程模型：`connect`/`disconnect` 幂等；`status()`/`counters()` 线程安全；
// 收帧回调在**收流线程**上执行，实现方不要阻塞（会拖住整条连接）。
// 发送走「队列 + 单写者线程」—— 抓屏/AudioCapture 回调都不允许直接 socket write。
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#endif

namespace apxpc::media {

/// 通道号（与 `shared/include/apx/frame.h` 的 kStream* 一一对应）
enum : uint8_t {
    kStreamVideo   = 0,
    kStreamAudio   = 1,
    kStreamTouch   = 2,
    kStreamControl = 3,
    kStreamMic     = 5,
    kStreamCamera  = 6,
};

/// 手机侧媒体端口（Android `TcpMediaChannel.MEDIA_PORT`）
constexpr uint16_t kMediaPort = 9502;

/// 音频统一格式（两端必须一致）：48kHz / 16bit / 立体声
constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannels   = 2;
constexpr int kAudioBytesPerMs = kAudioSampleRate * kAudioChannels * 2 / 1000;  // 192

class MediaSession {
public:
    struct Status {
        bool connected = false;
        std::string peer;      // host:port
        std::string error;     // 最近一次失败原因（空 = 无）
        int64_t upMs = 0;      // 已连接时长
    };

    /// 分类计数：既供面板展示，也是「哪条流真的在走」的客观证据
    struct Counters {
        uint64_t videoFrames = 0, videoBytes = 0;    // 副屏：本端发出
        uint64_t audioFrames = 0, audioBytes = 0;    // 音箱：本端发出
        uint64_t micFrames = 0, micBytes = 0;        // 麦克风：本端收到
        uint64_t cameraFrames = 0, cameraBytes = 0;  // 摄像头：本端收到
        uint64_t touchFrames = 0, touchBytes = 0;    // 副屏触摸：本端收到（已注入）
        uint64_t dropped = 0;                        // 队列满被丢弃的帧（保新弃旧）
        uint64_t resync = 0;                         // 收流里对不齐帧头而重新同步的次数
    };

    /// 收到一帧（在**收流线程**上调用）。
    /// `body` 已剥掉 16 字节帧头与尾部 u32 CRC，**仅在回调期间有效**，需要留存请自行拷贝。
    using FrameHandler = std::function<void(uint8_t streamId, uint8_t flags, uint32_t seq,
                                            const uint8_t* body, size_t len)>;

    MediaSession() = default;
    ~MediaSession();
    MediaSession(const MediaSession&) = delete;
    MediaSession& operator=(const MediaSession&) = delete;

    /// 阻塞建链（3s 超时 + 令牌握手）。成功返回 true。
    /// `handler` 必须在 connect() 之前用 setHandler() 设好。
    bool connect(const std::string& host, uint16_t port = kMediaPort,
                 const std::string& token = std::string());
    void disconnect();

    Status status() const;
    Counters counters() const;

    void setHandler(FrameHandler h) { handler_ = std::move(h); }

    /// 上行（本端 → 手机）：组帧 + 入队。线程安全，可在音频/抓屏回调里调用。
    /// @return 是否成功入队（true ≠ 已送达）
    bool sendFrame(uint8_t streamId, const uint8_t* body, size_t len, uint8_t flags = 0);

    /// 把大载荷切成多个分片帧发送（seq 相同，末片置 kFlagLastFragment）。
    /// 副屏以外的流用不到；`sendFrame` 已够。
    bool sendFragmented(uint8_t streamId, const uint8_t* body, size_t len,
                        uint8_t baseFlags = 0, size_t maxBody = 256u * 1024u);

    /// 发送**已组好的完整 APX1 帧**（原样入队，**不再二次封装**）。
    ///
    /// 给副屏用：`pc/display` 的 `FrameWriter` 自己会组帧（含视频扩展头、脏矩形、
    /// 尾部 CRC 与分片），我们只负责把它送出去 —— 再包一层会得到「帧中帧」。
    /// @param policyStreamId 仅用于**背压策略**（视频=可丢），不写入帧内容
    bool sendRawFrame(const uint8_t* frame, size_t len,
                      uint8_t policyStreamId = kStreamVideo);

private:
    void readerLoop();
    void writerLoop();
    bool sendAllBlocking(const uint8_t* p, size_t n);
    void teardown();
    bool enqueue(std::vector<uint8_t>&& frame, uint8_t streamId);

    mutable std::mutex mu_;
    Status st_;
    FrameHandler handler_;

    /// 生命周期互斥：connect（后台重试线程）与 disconnect（UI 线程）可能并发，
    /// 而 reader_/writer_ 这对 std::thread 成员**不是线程安全的** —— 并发赋值/join
    /// 会触发 STL 内部断言 → std::terminate → abort（0xC0000409，"莫名退出"真凶）。
    /// recursive：connect 开头会先调自身的 disconnect() 做清理。
    std::recursive_mutex lifecycleMu_;

#if defined(_WIN32)
    SOCKET sock_ = INVALID_SOCKET;
#else
    int sock_ = -1;
#endif
    std::atomic<bool> running_{false};
    std::thread reader_;
    std::thread writer_;
    std::string token_;
    int64_t upSinceMs_ = 0;

    std::mutex qmu_;
    std::condition_variable qcv_;
    std::deque<std::vector<uint8_t>> outQ_;
    /// 队列上限。约 2 秒音频（100 帧/s）——足够吸收突发，又不至于积压出可感延迟
    static constexpr size_t kQueueCap = 256;
    uint32_t seq_ = 0;

    std::atomic<uint64_t> cVideoFrames_{0}, cVideoBytes_{0};
    std::atomic<uint64_t> cAudioFrames_{0}, cAudioBytes_{0};
    std::atomic<uint64_t> cMicFrames_{0}, cMicBytes_{0};
    std::atomic<uint64_t> cCameraFrames_{0}, cCameraBytes_{0};
    std::atomic<uint64_t> cTouchFrames_{0}, cTouchBytes_{0};
    std::atomic<uint64_t> cDropped_{0}, cResync_{0};
};

}  // namespace apxpc::media
