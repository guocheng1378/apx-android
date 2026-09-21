// 组帧 / 解帧：严格按 docs/PROTOCOL.md §3 与 §3.1
//
// 一帧布局：[ApxFrameHeader 16B][VideoExtHeader 20B][DirtyRect 8B × N][码流分片][crc32 u32]
// payloadLen 覆盖「扩展头 + dirtyRects + 分片 + CRC」。
// 码流大于 maxFragment 时切成多片，除最后一片外均不带 kFlagLastFragment。
#pragma once

#include <cstdint>
#include <vector>

#include "common/types.hpp"
#include "protocol/frame_format.hpp"
#include "transport/i_transport.hpp"

namespace apxdisp {

class FrameWriter {
public:
    explicit FrameWriter(IChannel* channel) : channel_(channel) {}

    void setMaxFragment(size_t bytes) { maxFragment_ = bytes; }
    size_t maxFragment() const { return maxFragment_; }
    uint32_t seq() const { return seq_; }

    // 写一帧视频（必要时分片）。返回写入的 APX 帧数，0 表示失败/被丢弃
    size_t writeVideo(const EncodedPacket& pkt, uint32_t timeoutMs = 0);

    // 写控制面帧（streamId=3），payload 为上层已编码好的 TLV/JSON 字节
    bool writeControl(const uint8_t* payload, size_t len, uint32_t timeoutMs = 0);

    // 直接写一段已组好的完整帧（供测试）
    bool writeRawFrame(const uint8_t* frame, size_t len, uint32_t timeoutMs = 0);

    // 组帧到内存（不发送），供自测与单帧 dump
    static std::vector<uint8_t> buildVideoFrame(const EncodedPacket& pkt, uint32_t seq,
                                                size_t fragmentOffset, size_t fragmentLen,
                                                bool lastFragment, bool dropable);
    static std::vector<uint8_t> buildControlFrame(const uint8_t* payload, size_t len, uint32_t seq);

private:
    IChannel* channel_ = nullptr;
    size_t maxFragment_ = kDefaultMaxFragment;
    uint32_t seq_ = 0;
    bool diagPrinted_ = false;
};

// -------- 解析（用于自测、离线工具与手机端回环校验） --------
struct ParsedFrame {
    apx::ApxFrameHeader header{};
    VideoExtHeader      video{};        // 仅 streamId=0 有效
    std::vector<ApxDirtyRect> rects;    // 仅 streamId=0 有效
    const uint8_t* payload = nullptr;   // 指向输入缓冲内的码流分片起始
    size_t         payloadLen = 0;      // 不含 CRC
    uint32_t       crc = 0;
    bool           valid = false;
};

bool parseFrame(const uint8_t* data, size_t len, ParsedFrame& out);

// 从字节流里逐个切出完整帧（处理粘包）
class FrameSplitter {
public:
    void feed(const uint8_t* data, size_t len);
    bool next(std::vector<uint8_t>& frameOut);

private:
    std::vector<uint8_t> buf_;
    size_t consumed_ = 0;
};

}  // namespace apxdisp
