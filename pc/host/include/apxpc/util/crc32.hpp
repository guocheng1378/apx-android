#pragma once
#include <cstddef>
#include <cstdint>

namespace apxpc {

// 标准 CRC-32（IEEE 802.3，多项式 0xEDB88320 反射）
// PROTOCOL §3：payload 尾部追加 u32 crc32，且计入 payloadLen
uint32_t crc32(const void* data, size_t len) noexcept;
uint32_t crc32Combine(uint32_t crc, const void* data, size_t len) noexcept;

}  // namespace apxpc
