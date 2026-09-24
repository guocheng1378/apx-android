#pragma once
#include <cstdint>

// 人类可读版本串（--version / 面板关于页使用）
#ifndef APXPC_VERSION_STRING
#define APXPC_VERSION_STRING "0.3.1"
#endif

namespace apxpc {

// PROTOCOL §5：协议版本字段 u16，当前 1.0。
// 编码约定：major<<8 | minor —— 0x0100 表示 1.0。
inline constexpr uint16_t kProtocolVersionMajor = 1;
inline constexpr uint16_t kProtocolVersionMinor = 0;
inline constexpr uint16_t kProtocolVersion =
    static_cast<uint16_t>((kProtocolVersionMajor << 8) | kProtocolVersionMinor);

inline void packVersion(uint16_t v, uint8_t& outMajor, uint8_t& outMinor) {
    outMajor = static_cast<uint8_t>(v >> 8);
    outMinor = static_cast<uint8_t>(v & 0xFF);
}

inline uint16_t makeVersion(uint8_t major, uint8_t minor) {
    return static_cast<uint16_t>((static_cast<uint16_t>(major) << 8) | minor);
}

}  // namespace apxpc
