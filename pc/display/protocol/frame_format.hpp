// 帧格式：严格按 docs/PROTOCOL.md §3 / §3.1 落地
//
// 统一帧头 16B（apx::ApxFrameHeader，来自 shared/ 或 compat 替身）
//   magic 'APX1' | streamId | flags | headerExtWords | payloadLen(含 CRC) | seq
//
// streamId=0（Video）扩展头，按 §3.1 字段顺序：
//   u64 ptsNs / u16 width / u16 height / u16 frameRateX100 / u16 dirtyRectCount / u16 codecId
//   = 18 字节；headerExtWords 以 32bit 字计，故补齐到 20 字节（5 words），末 2 字节 reserved=0。
// 扩展头之后紧跟 dirtyRectCount 个 DirtyRect（每个 8B），其后为码流分片；
// payloadLen 覆盖「扩展头 + dirtyRects + 码流分片 + u32 crc32」。
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

#include "apx/frame.h"
#include "common/crc32.hpp"
#include "common/types.hpp"

namespace apxdisp {

#pragma pack(push, 1)

// PROTOCOL §3.1：{u16 x, u16 y, u16 w, u16 h}
struct ApxDirtyRect {
    uint16_t x = 0;
    uint16_t y = 0;
    uint16_t w = 0;
    uint16_t h = 0;
};

// PROTOCOL §3.1 视频扩展头
struct VideoExtHeader {
    uint64_t ptsNs         = 0;  // 手机端时基（PROTOCOL §1）
    uint16_t width         = 0;
    uint16_t height        = 0;
    uint16_t frameRateX100 = 0;
    uint16_t dirtyRectCount = 0;
    uint16_t codecId       = 0;  // 0=H264 1=HEVC 2=AV1 3=MJPEG 4=RAW_LZ4
    uint16_t reserved      = 0;  // 补齐到 20 字节 = 5 个 32bit 字
};

#pragma pack(pop)

static_assert(sizeof(ApxDirtyRect) == 8, "DirtyRect 必须 8 字节");
static_assert(sizeof(VideoExtHeader) == 20, "视频扩展头必须 20 字节（5 个 32bit 字）");
static_assert(sizeof(apx::ApxFrameHeader) == 16, "帧头必须 16 字节");

// 扩展头字数（PROTOCOL §3 headerExtWords）
constexpr uint16_t kVideoExtWords = static_cast<uint16_t>(sizeof(VideoExtHeader) / 4);  // 5
constexpr uint32_t kFrameMagicLE  = 0x31585041u;   // 'APX1' 小端
constexpr size_t   kCrcLen        = 4;

// 单个分片内码流的最大字节数。USB 3.0 bulk 单次 1024B 的 MPS 由传输层再切成多包，
// 这里限制的是「一帧被切成几个 APX 帧」，默认 256KiB（4K 全 I 帧也基本一帧一片）。
constexpr size_t kDefaultMaxFragment = 256u * 1024u;
// dirtyRectCount 上限（协议未规定，取 64 由上层的矩形合并算法保证不超）
constexpr uint16_t kMaxDirtyRects = 64;
// streamId=3 控制面单帧载荷上限
constexpr size_t kMaxCtrlPayload = 1024;

// 小端写入辅助（所有平台统一按小端序列化）
inline void putU16(uint8_t* p, uint16_t v) { p[0] = static_cast<uint8_t>(v & 0xFF); p[1] = static_cast<uint8_t>(v >> 8); }
inline void putU32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>((v >> (8 * i)) & 0xFF);
}
inline void putU64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>((v >> (8 * i)) & 0xFF);
}
inline uint16_t getU16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
inline uint32_t getU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
inline uint64_t getU64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(p[i]) << (8 * i);
    return v;
}

// 单帧（含 CRC）总长度
inline size_t videoFrameSize(size_t bitstreamLen, uint16_t dirtyRectCount) {
    return sizeof(apx::ApxFrameHeader) + sizeof(VideoExtHeader) +
           sizeof(ApxDirtyRect) * dirtyRectCount + bitstreamLen + kCrcLen;
}

// 校验一帧：magic / payloadLen 自洽 / CRC
inline bool validateFrame(const uint8_t* frame, size_t len) {
    if (!frame || len < sizeof(apx::ApxFrameHeader) + kCrcLen) return false;
    if (std::memcmp(frame, "APX1", 4) != 0) return false;
    apx::ApxFrameHeader h{};
    std::memcpy(&h, frame, sizeof(h));
    if (sizeof(h) + h.headerExtWords * 4 + h.payloadLen != len) return false;
    const uint32_t crcStored = getU32(frame + len - kCrcLen);
    const uint32_t crcCalc   = crc32Of(frame, len - kCrcLen);
    return crcStored == crcCalc;
}

}  // namespace apxdisp
