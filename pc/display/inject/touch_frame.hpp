// 上行触控帧解析（PROTOCOL §2.5 / §3.2）
//
// bulk（streamId=2）载荷布局（本实现去掉了 §2.5 里的 reportId 字节，
// 因为 streamId 已经标识了这是触控流，节省 1 字节；如需与 HID 通道字节级一致，
// 由 L1 在 shared/ 中定稿，见 contractDeviations）：
//   u8  flags
//   u8  contactCount
//   u64 tsNs
//   contact[8B] × N : {u16 id, u16 x, u16 y, u16 pressure}
//   pen extra[10B]  : {i16 tiltX, i16 tiltY, u16 orientation, u32 reserved}（flags bit0 置位时存在）
#pragma once

#include <cstddef>
#include <cstdint>

#include "inject/i_inject.hpp"

namespace apxdisp {

bool parseTouchFrame(const uint8_t* payload, size_t len, TouchFrame& out);

// 把归一化坐标（0..65535）映射到虚拟屏像素坐标
struct MappedPoint { int32_t x = 0; int32_t y = 0; };

MappedPoint mapToVirtualScreen(uint16_t nx, uint16_t ny, const InjectTarget& target);

// 归一化到 SendInput 的绝对坐标系（0..65535，覆盖整个虚拟桌面）
void mapToAbsoluteDesktop(uint16_t nx, uint16_t ny, const InjectTarget& target,
                          int32_t desktopOriginX, int32_t desktopOriginY,
                          uint32_t desktopWidth, uint32_t desktopHeight,
                          int32_t& absX, int32_t& absY);

}  // namespace apxdisp
