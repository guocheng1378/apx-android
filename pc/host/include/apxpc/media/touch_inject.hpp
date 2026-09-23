#pragma once
// 副屏触摸上行（streamId=2）→ Windows 鼠标注入（SendInput，免驱动）。
//
// 帧载荷 8 字节小端（与 Android `MediaOut.touch` 逐字节一致）：
//   [0] u8  action   0=down 1=up 2=move 3=cancel
//   [1] u8  buttons  bit0=左键 bit1=右键 bit2=中键
//   [2] u16 x        归一化 0..65535（映射主显示器）
//   [4] u16 y        归一化 0..65535
//   [6] u16 pointerId（MVP 恒 0，多指暂未接入）
//   [7] u16 保留（0）
//
// 线程纪律：在**收流线程**上调用。SendInput 毫秒级返回，可直接用；
// 若将来做手势识别等重活，必须切线程，不要拖住媒体收流。

#include <cstddef>
#include <cstdint>

namespace apxpc::media {

/// 解析一帧触摸并注入鼠标。载荷非法返回 false（不注入，不报错）。
bool injectTouchFrame(const uint8_t* body, size_t len);

}  // namespace apxpc::media
