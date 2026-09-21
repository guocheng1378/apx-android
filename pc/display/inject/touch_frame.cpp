#include "inject/touch_frame.hpp"

#include <algorithm>
#include <cstring>

#include "protocol/frame_format.hpp"

namespace apxdisp {

bool parseTouchFrame(const uint8_t* payload, size_t len, TouchFrame& out) {
    out = TouchFrame{};
    if (!payload || len < 10) return false;

    size_t off = 0;
    out.flags = payload[off++];
    const uint8_t count = payload[off++];
    out.tsNs = getU64(payload + off);
    off += 8;

    const size_t need = static_cast<size_t>(count) * 8;
    if (off + need > len) return false;

    out.contacts.reserve(count);
    for (uint8_t i = 0; i < count; ++i) {
        TouchContact c{};
        c.id       = getU16(payload + off + 0);
        c.x        = getU16(payload + off + 2);
        c.y        = getU16(payload + off + 4);
        c.pressure = getU16(payload + off + 6);
        out.contacts.push_back(c);
        off += 8;
    }

    if (out.penInRange() && off + 10 <= len) {
        out.hasPen = true;
        out.pen.tiltX = static_cast<int16_t>(getU16(payload + off + 0));
        out.pen.tiltY = static_cast<int16_t>(getU16(payload + off + 2));
        out.pen.orientation = getU16(payload + off + 4);
        // off + 6..10 为保留字段，忽略
    }
    return true;
}

MappedPoint mapToVirtualScreen(uint16_t nx, uint16_t ny, const InjectTarget& target) {
    MappedPoint p{};
    if (target.width == 0 || target.height == 0) return p;
    const int32_t lx = (static_cast<int32_t>(nx) * static_cast<int32_t>(target.width)) / 65535;
    const int32_t ly = (static_cast<int32_t>(ny) * static_cast<int32_t>(target.height)) / 65535;
    p.x = target.originX + std::clamp<int32_t>(lx, 0, static_cast<int32_t>(target.width) - 1);
    p.y = target.originY + std::clamp<int32_t>(ly, 0, static_cast<int32_t>(target.height) - 1);
    return p;
}

void mapToAbsoluteDesktop(uint16_t nx, uint16_t ny, const InjectTarget& target,
                          int32_t desktopOriginX, int32_t desktopOriginY,
                          uint32_t desktopWidth, uint32_t desktopHeight,
                          int32_t& absX, int32_t& absY) {
    // SendInput 的 MOUSEEVENTF_ABSOLUTE 把 0..65535 映射到「显示面」。
    // 多显示器下按整个虚拟桌面归一化（Windows 10+ 实测行为），并做钳位。
    auto p = mapToVirtualScreen(nx, ny, target);
    if (desktopWidth == 0 || desktopHeight == 0) { absX = 0; absY = 0; return; }
    int32_t rx = p.x - desktopOriginX;
    int32_t ry = p.y - desktopOriginY;
    rx = std::clamp<int32_t>(rx, 0, static_cast<int32_t>(desktopWidth) - 1);
    ry = std::clamp<int32_t>(ry, 0, static_cast<int32_t>(desktopHeight) - 1);
    absX = (rx * 65535) / static_cast<int32_t>(desktopWidth);
    absY = (ry * 65535) / static_cast<int32_t>(desktopHeight);
}

}  // namespace apxdisp
