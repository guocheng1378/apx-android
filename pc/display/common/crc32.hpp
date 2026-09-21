// CRC32（IEEE 802.3，多项式 0xEDB88320）
// PROTOCOL §3：payload 尾部追加 u32 crc32，且 payloadLen 含 CRC 长度。
#pragma once

#include <cstddef>
#include <cstdint>

namespace apxdisp {

uint32_t crc32Init();
uint32_t crc32Update(uint32_t crc, const void* data, size_t len);
uint32_t crc32Final(uint32_t crc);
uint32_t crc32Of(const void* data, size_t len);

}  // namespace apxdisp
