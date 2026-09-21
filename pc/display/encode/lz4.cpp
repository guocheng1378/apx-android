#include "encode/lz4.hpp"

#include <cstring>
#include <vector>

namespace apxdisp {
namespace {

constexpr size_t kMinMatch    = 4;
constexpr size_t kHashBits    = 12;                       // 4096 槽
constexpr size_t kHashSize    = 1u << kHashBits;
constexpr size_t kWindowSize  = 65535;                    // offset 为 u16
constexpr size_t kLastLiterals = 5;                       // 标准：末尾 5 字节必须是字面量

inline uint32_t read32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

inline size_t hash4(uint32_t v) {
    return (v * 2654435761u) >> (32 - kHashBits);
}

// 写可变长度长度字段（15 表示需要后续字节，连续 255 表示继续）
inline size_t emitLength(uint8_t* out, size_t op, size_t len) {
    while (len >= 255) {
        out[op++] = 255;
        len -= 255;
    }
    out[op++] = static_cast<uint8_t>(len);
    return op;
}

}  // namespace

size_t lz4CompressBound(size_t srcSize) { return srcSize + srcSize / 255 + 16; }

size_t lz4Compress(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCap) {
    if (!src || !dst || srcSize == 0) return 0;
    if (dstCap < lz4CompressBound(srcSize)) return 0;

    std::vector<uint32_t> table(kHashSize, 0xFFFFFFFFu);
    size_t anchor = 0;
    size_t op = 0;
    size_t i = 0;

    // 只在前 (srcSize - kLastLiterals - kMinMatch) 区间尝试匹配，保证末尾 5 字节是字面量
    const size_t matchLimit = (srcSize > kLastLiterals + kMinMatch) ? (srcSize - kLastLiterals - kMinMatch) : 0;

    while (i < matchLimit) {
        const uint32_t v = read32(src + i);
        const size_t h = hash4(v);
        const uint32_t ref = table[h];
        table[h] = static_cast<uint32_t>(i);

        if (ref != 0xFFFFFFFFu && i > ref && (i - ref) <= kWindowSize &&
            std::memcmp(src + ref, src + i, kMinMatch) == 0) {
            size_t matchLen = kMinMatch;
            const size_t maxLen = (srcSize - kLastLiterals > i) ? (srcSize - kLastLiterals - i) : 0;
            while (matchLen < maxLen && src[ref + matchLen] == src[i + matchLen]) ++matchLen;
            if (matchLen < kMinMatch) { ++i; continue; }

            const size_t litLen = i - anchor;
            const size_t matchCode = matchLen - kMinMatch;
            const uint8_t token = static_cast<uint8_t>(
                ((litLen < 15 ? litLen : 15) << 4) | (matchCode < 15 ? matchCode : 15));

            if (op + 1 + litLen + 2 + (matchCode >= 15 ? 1 : 0) > dstCap) return 0;
            dst[op++] = token;
            if (litLen >= 15) op = emitLength(dst, op, litLen - 15);
            if (litLen) {
                std::memcpy(dst + op, src + anchor, litLen);
                op += litLen;
            }
            const uint16_t offset = static_cast<uint16_t>(i - ref);
            dst[op++] = static_cast<uint8_t>(offset & 0xFF);
            dst[op++] = static_cast<uint8_t>(offset >> 8);
            if (matchCode >= 15) op = emitLength(dst, op, matchCode - 15);

            i += matchLen;
            anchor = i;
        } else {
            ++i;
        }
    }

    // 末尾字面量（无 offset）
    const size_t litLen = srcSize - anchor;
    if (op + 1 + litLen + (litLen >= 15 ? 1 : 0) > dstCap) return 0;
    dst[op++] = static_cast<uint8_t>((litLen < 15 ? litLen : 15) << 4);
    if (litLen >= 15) op = emitLength(dst, op, litLen - 15);
    if (litLen) {
        std::memcpy(dst + op, src + anchor, litLen);
        op += litLen;
    }
    return op;
}

size_t lz4Decompress(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCap) {
    if (!src || !dst || srcSize == 0) return 0;
    size_t ip = 0;
    size_t op = 0;

    while (ip < srcSize) {
        const uint8_t token = src[ip++];
        size_t litLen = token >> 4;
        if (litLen == 15) {
            uint8_t b = 0;
            do {
                if (ip >= srcSize) return 0;
                b = src[ip++];
                litLen += b;
            } while (b == 255);
        }
        if (ip + litLen > srcSize || op + litLen > dstCap) return 0;
        std::memcpy(dst + op, src + ip, litLen);
        ip += litLen;
        op += litLen;

        if (ip >= srcSize) break;  // 末尾字面量序列，正常结束

        if (ip + 2 > srcSize) return 0;
        const uint16_t offset = static_cast<uint16_t>(src[ip] | (src[ip + 1] << 8));
        ip += 2;
        if (offset == 0 || offset > op) return 0;

        size_t matchLen = (token & 0x0F);
        if (matchLen == 15) {
            uint8_t b = 0;
            do {
                if (ip >= srcSize) return 0;
                b = src[ip++];
                matchLen += b;
            } while (b == 255);
        }
        matchLen += kMinMatch;
        if (op + matchLen > dstCap) return 0;

        const size_t ref = op - offset;
        for (size_t k = 0; k < matchLen; ++k) dst[op + k] = dst[ref + k];
        op += matchLen;
    }
    return op;
}

}  // namespace apxdisp
