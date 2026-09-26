// 公共类型定义（L4 用户态各模块共用）
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "apx/frame.h"

namespace apxdisp {

// ---------------------------------------------------------------------------
// PROTOCOL §3.1：codecId
// ---------------------------------------------------------------------------
enum class CodecId : uint16_t {
    H264   = 0,
    HEVC   = 1,
    AV1    = 2,
    MJPEG  = 3,
    RawLz4 = 4,
    Unknown = 0xFFFF,
};

inline const char* codecName(CodecId id) {
    switch (id) {
        case CodecId::H264:   return "H264";
        case CodecId::HEVC:   return "HEVC";
        case CodecId::AV1:    return "AV1";
        case CodecId::MJPEG:  return "MJPEG";
        case CodecId::RawLz4: return "RAW_LZ4";
        default:              return "UNKNOWN";
    }
}

// ---------------------------------------------------------------------------
// 区域：PROTOCOL §3.1 的 DirtyRect {u16 x, u16 y, u16 w, u16 h}
// ---------------------------------------------------------------------------
struct Rect {
    uint16_t x = 0;
    uint16_t y = 0;
    uint16_t w = 0;
    uint16_t h = 0;
};

struct DirtyRegion {
    std::vector<Rect> rects;
    bool full = false;  // true 表示整帧（dirtyRectCount 仍可写 1 个全屏矩形）

    void setFull(uint32_t w, uint32_t h) {
        rects.clear();
        Rect r{};
        r.x = 0;
        r.y = 0;
        r.w = static_cast<uint16_t>(w);
        r.h = static_cast<uint16_t>(h);
        rects.push_back(r);
        full = true;
    }
    size_t count() const { return rects.size(); }
};

// ---------------------------------------------------------------------------
// 虚拟显示器模式
// ---------------------------------------------------------------------------
struct DisplayMode {
    uint32_t width          = 1080;
    uint32_t height         = 2400;  // 手机竖屏原生分辨率
    uint32_t refreshRateX100 = 6000; // 60.00 Hz
};

// ---------------------------------------------------------------------------
// 编码参数：关 B 帧 + 低延迟（GOP 见下，**不再是 1**）
// ---------------------------------------------------------------------------
struct VideoParams {
    uint32_t width          = 1080;
    uint32_t height         = 2400;
    uint32_t frameRateX100  = 6000;
    uint32_t bitrateKbps    = 12000;
    CodecId  codec          = CodecId::HEVC;
    // ★ GOP 从 1 改回 30（≈1 秒一个关键帧 @30fps）。
    //
    // 原值「GOP=1 = 全 I 帧」是对"低延迟"的误用：它确实省掉了帧间依赖，
    // 但代价是**每一帧都按帧内（intra）重编** —— 码率与编码耗时成倍上涨，
    // 而同样的 8Mbps 花在全 I 帧上，画面质量会明显差于有 P 帧的常规 GOP。
    // 真机症状就是"副屏看视频很卡、还糊"：软编 CPU 被全 I 帧吃满（作者注释里
    // 也记了"软编长跑 11ms→32ms 持续恶化"）。
    //
    // 改成 30 之后：P 帧承担绝大部分画面，码率花在真正的变化上；
    // 丢包时最多 1 秒花屏，接收端可用控制帧 0x06 主动要求 IDR
    // （见 Pipeline::requestKeyFrame，那边本来就有这条通路）。
    uint32_t gop            = 30;
    bool     bFrames        = false;  // 关 B 帧（保持低延迟，不改）
    bool     lowLatency     = true;
    uint32_t maxFrameWidth  = 4096;
    uint32_t maxFrameHeight = 4096;
};

// ---------------------------------------------------------------------------
// 抓屏输出
// ---------------------------------------------------------------------------
struct RawFrame {
    uint32_t width      = 0;
    uint32_t height     = 0;
    uint32_t stride     = 0;          // 字节/行（BGRA = width*4）
    uint64_t ptsNs      = 0;          // 已换算到手机时基（PROTOCOL §1）
    uint64_t frameNumber = 0;
    const uint8_t* cpuData = nullptr; // CPU 可访问像素（BGRA，自顶向下）；可为 null
    void*    d3d11Texture = nullptr;  // ID3D11Texture2D*，硬编零拷贝路径；可为 null
    DirtyRegion dirty;
};

// ---------------------------------------------------------------------------
// 编码输出
// ---------------------------------------------------------------------------
struct EncodedPacket {
    std::vector<uint8_t> bytes;       // 裸码流（H.264/H.265 为 AnnexB 或 AVCC，见编码器说明）
    uint64_t ptsNs        = 0;
    bool     keyFrame     = true;     // GOP=1 时恒为 true
    CodecId  codec        = CodecId::HEVC;
    uint32_t width        = 0;
    uint32_t height       = 0;
    uint32_t frameRateX100 = 6000;
    DirtyRegion dirty;
};

// ---------------------------------------------------------------------------
// 统计（供控制面板/诊断使用）
// ---------------------------------------------------------------------------
struct PipelineStats {
    uint64_t framesCaptured = 0;
    uint64_t framesEncoded  = 0;
    uint64_t framesSent     = 0;
    uint64_t framesDropped  = 0;
    uint64_t bytesSent      = 0;
    uint64_t dirtyRectsSent = 0;
    // 各阶段耗时（毫秒，滑动平均）
    double   captureMsAvg   = 0.0;
    double   encodeMsAvg    = 0.0;
    double   transportMsAvg = 0.0;
    double   endToEndMsAvg  = 0.0;
    double   fpsActual      = 0.0;
};

inline double ema(double prev, double sample, double alpha = 0.1) {
    return prev == 0.0 ? sample : prev + alpha * (sample - prev);
}

}  // namespace apxdisp
