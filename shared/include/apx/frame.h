// ============================================================================
// Bulk 通道统一帧头（docs/PROTOCOL.md §3）
//   - ApxFrameHeader 固定 16 字节，magic 'APX1'
//   - 多字节字段显式小端读写（不依赖主机字节序）
//   - payloadLen 含尾部 u32 crc32
//   - 提供分片（发送侧）与组装（接收侧）
// ============================================================================
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace apx {

#pragma pack(push, 1)
struct ApxFrameHeader {
    char     magic[4];        // 'A' 'P' 'X' '1'
    uint8_t  streamId;        // 0=video 1=audio 2=touch 3=ctrl 4=telemetry
    uint8_t  flags;           // bit0=keyframe bit1=last_fragment bit2=dropable
    uint16_t headerExtWords;  // 扩展头字数（32bit 字）
    uint32_t payloadLen;      // 载荷字节数，含尾部 u32 crc32
    uint32_t seq;             // 帧序号（同一帧的各分片相同）
};
#pragma pack(pop)
static_assert(sizeof(ApxFrameHeader) == 16, "ApxFrameHeader 必须 16 字节");

enum : uint8_t {
    kStreamVideo     = 0,
    kStreamAudio     = 1,
    kStreamTouch     = 2,
    kStreamControl   = 3,
    kStreamTelemetry = 4,
};

enum : uint8_t {
    kFlagKeyFrame     = 1u << 0,
    kFlagLastFragment = 1u << 1,
    kFlagDropable     = 1u << 2,
};

constexpr uint32_t kFrameMagicLE   = 0x31585041u;  // 'A' 'P' 'X' '1' 按小端读成 u32
constexpr size_t   kFrameHeaderSize = 16;
constexpr size_t   kFrameCrcSize    = 4;
constexpr size_t   kMaxFramePayload = 4u * 1024u * 1024u;  // 单帧载荷上限（含 CRC）

// ---------------------------------------------------------------- CRC32 ----
// IEEE 802.3：反射多项式 0xEDB88320，初值 0xFFFFFFFF，结果取反
uint32_t crc32(const void* data, size_t len);
uint32_t crc32Continue(uint32_t crc, const void* data, size_t len);

// ------------------------------------------------------- 显式小端读写 ----
inline void putU16(uint8_t* p, uint16_t v) noexcept {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}
inline void putU32(uint8_t* p, uint32_t v) noexcept {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
inline void putU64(uint8_t* p, uint64_t v) noexcept {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
inline uint16_t getU16(const uint8_t* p) noexcept {
    return static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
}
inline uint32_t getU32(const uint8_t* p) noexcept {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(p[i]) << (8 * i);
    return v;
}
inline uint64_t getU64(const uint8_t* p) noexcept {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(p[i]) << (8 * i);
    return v;
}

// --------------------------------------------------------------- 帧读写 ----
// 写帧头（含 magic 与显式小端字段）；cap 不足返回 false
bool writeHeader(uint8_t* dst, size_t cap, const ApxFrameHeader& h);
// 读帧头并校验 magic；len 不足或 magic 错误返回 false
bool readHeader(const uint8_t* src, size_t len, ApxFrameHeader& out);
// 帧总长（帧头 + 载荷，载荷含 CRC）
inline size_t frameTotalSize(const ApxFrameHeader& h) {
    return kFrameHeaderSize + static_cast<size_t>(h.payloadLen);
}

// 载荷尾部追加 CRC32（bodyLen 为追加前长度），返回追加后的总长度（= payloadLen）
uint32_t appendPayloadCrc(uint8_t* payload, uint32_t bodyLen);
// 校验载荷（payloadLen 含 CRC）：比对尾部 u32 与前面字节的 CRC32
bool verifyPayload(const uint8_t* payload, uint32_t payloadLen);

// --------------------------------------------------------------- 分片 ----
// 单个分片可承载的【载荷】字节数（不含帧头、含该分片自己的 CRC）
size_t fragmentCapacity(size_t mtu);
// 按 mtu 计算 bodyLen 需要几个分片（mtu 非法返回 0）
size_t fragmentCountFor(size_t bodyLen, size_t mtu);

// 生成第 fragIndex 个分片：帧头 + chunk + crc32；最后一个分片置 kFlagLastFragment
// 成功返回 true，outLen 为该分片总字节数，isLast 指示是否为末片
bool buildFragment(uint8_t* dst, size_t dstCap, size_t& outLen, bool& isLast,
                   uint8_t streamId, uint8_t baseFlags, uint32_t seq,
                   const uint8_t* body, size_t bodyLen, size_t fragIndex, size_t mtu);

// --------------------------------------------------------------- 组装 ----
// 按到达顺序拼接同一 seq 的分片；逐片校验 CRC，遇错立即丢弃当前帧。
// 依赖顺序可靠通道（§3.3），不处理乱序与重传。
class FrameAssembler {
public:
    void reset();
    bool inProgress() const noexcept { return active_; }
    uint32_t seq() const noexcept { return hdr_.seq; }
    size_t buffered() const noexcept { return buf_.size(); }

    // 收到一个完整分片帧；组装完成时返回 true，headerOut/bodyOut 给出整帧
    bool push(const uint8_t* frame, size_t len, ApxFrameHeader& headerOut,
              std::vector<uint8_t>& bodyOut);

private:
    bool            active_ = false;
    ApxFrameHeader  hdr_{};
    std::vector<uint8_t> buf_;
};

}  // namespace apx
