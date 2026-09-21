// §3 Bulk 帧：CRC32 / 小端读写 / 分片 / 组装
#include "apx/frame.h"

#include <cstring>

namespace apx {
namespace {

const uint32_t* crcTable() {
    static uint32_t table[256];
    static bool built = false;
    if (!built) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) {
                c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            }
            table[i] = c;
        }
        built = true;
    }
    return table;
}

}  // namespace

uint32_t crc32Continue(uint32_t crc, const void* data, size_t len) {
    const uint32_t* t = crcTable();
    const uint8_t* p = static_cast<const uint8_t*>(data);
    uint32_t c = crc;
    for (size_t i = 0; i < len; ++i) {
        c = t[(c ^ p[i]) & 0xFFu] ^ (c >> 8);
    }
    return c;
}

uint32_t crc32(const void* data, size_t len) {
    if (data == nullptr) return 0;
    return ~crc32Continue(0xFFFFFFFFu, data, len);
}

bool writeHeader(uint8_t* dst, size_t cap, const ApxFrameHeader& h) {
    if (dst == nullptr || cap < kFrameHeaderSize) return false;
    dst[0] = 'A';
    dst[1] = 'P';
    dst[2] = 'X';
    dst[3] = '1';
    dst[4] = h.streamId;
    dst[5] = h.flags;
    putU16(dst + 6, h.headerExtWords);
    putU32(dst + 8, h.payloadLen);
    putU32(dst + 12, h.seq);
    return true;
}

bool readHeader(const uint8_t* src, size_t len, ApxFrameHeader& out) {
    if (src == nullptr || len < kFrameHeaderSize) return false;
    if (!(src[0] == 'A' && src[1] == 'P' && src[2] == 'X' && src[3] == '1')) return false;
    out.magic[0] = 'A'; out.magic[1] = 'P'; out.magic[2] = 'X'; out.magic[3] = '1';
    out.streamId = src[4];
    out.flags = src[5];
    out.headerExtWords = getU16(src + 6);
    out.payloadLen = getU32(src + 8);
    out.seq = getU32(src + 12);
    return true;
}

uint32_t appendPayloadCrc(uint8_t* payload, uint32_t bodyLen) {
    if (payload == nullptr) return 0;
    const uint32_t crc = crc32(payload, bodyLen);
    putU32(payload + bodyLen, crc);
    return bodyLen + kFrameCrcSize;
}

bool verifyPayload(const uint8_t* payload, uint32_t payloadLen) {
    if (payload == nullptr || payloadLen < kFrameCrcSize) return false;
    const uint32_t bodyLen = payloadLen - kFrameCrcSize;
    const uint32_t want = getU32(payload + bodyLen);
    return crc32(payload, bodyLen) == want;
}

// --------------------------------------------------------------- 分片 ----
size_t fragmentCapacity(size_t mtu) {
    if (mtu <= kFrameHeaderSize + kFrameCrcSize) return 0;
    return mtu - kFrameHeaderSize - kFrameCrcSize;
}

size_t fragmentCountFor(size_t bodyLen, size_t mtu) {
    const size_t cap = fragmentCapacity(mtu);
    if (cap == 0) return 0;
    return (bodyLen + cap - 1) / cap;
}

bool buildFragment(uint8_t* dst, size_t dstCap, size_t& outLen, bool& isLast,
                   uint8_t streamId, uint8_t baseFlags, uint32_t seq,
                   const uint8_t* body, size_t bodyLen, size_t fragIndex, size_t mtu) {
    const size_t cap = fragmentCapacity(mtu);
    if (cap == 0 || body == nullptr) return false;
    const size_t count = (bodyLen + cap - 1) / cap;
    if (fragIndex >= count) return false;

    const size_t offset = fragIndex * cap;
    size_t chunk = bodyLen - offset;
    if (chunk > cap) chunk = cap;
    isLast = (fragIndex + 1 == count);

    const size_t need = kFrameHeaderSize + chunk + kFrameCrcSize;
    if (dst == nullptr || dstCap < need) return false;

    ApxFrameHeader h;
    h.magic[0] = 'A'; h.magic[1] = 'P'; h.magic[2] = 'X'; h.magic[3] = '1';
    h.streamId = streamId;
    h.flags = static_cast<uint8_t>(baseFlags | (isLast ? kFlagLastFragment : 0));
    h.headerExtWords = 0;
    h.payloadLen = static_cast<uint32_t>(chunk + kFrameCrcSize);
    h.seq = seq;
    if (!writeHeader(dst, dstCap, h)) return false;

    uint8_t* payload = dst + kFrameHeaderSize;
    std::memcpy(payload, body + offset, chunk);
    appendPayloadCrc(payload, static_cast<uint32_t>(chunk));
    outLen = need;
    return true;
}

// --------------------------------------------------------------- 组装 ----
void FrameAssembler::reset() {
    active_ = false;
    buf_.clear();
    hdr_ = ApxFrameHeader{};
}

bool FrameAssembler::push(const uint8_t* frame, size_t len, ApxFrameHeader& headerOut,
                          std::vector<uint8_t>& bodyOut) {
    ApxFrameHeader h;
    if (!readHeader(frame, len, h)) {
        reset();
        return false;
    }
    if (h.payloadLen < kFrameCrcSize || len < frameTotalSize(h)) {
        reset();
        return false;
    }
    const uint8_t* payload = frame + kFrameHeaderSize;
    if (!verifyPayload(payload, h.payloadLen)) {
        reset();
        return false;
    }
    // 新的 seq：丢弃未完成的旧帧
    if (active_ && hdr_.seq != h.seq) reset();

    if (!active_) {
        active_ = true;
        hdr_ = h;
        buf_.clear();
    }
    const uint32_t bodyLen = h.payloadLen - kFrameCrcSize;
    buf_.insert(buf_.end(), payload, payload + bodyLen);

    if (h.flags & kFlagLastFragment) {
        headerOut = hdr_;
        headerOut.payloadLen = static_cast<uint32_t>(buf_.size());
        bodyOut.swap(buf_);
        reset();
        return true;
    }
    return false;
}

}  // namespace apx
