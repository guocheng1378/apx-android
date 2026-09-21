// 抓屏工厂 + 脏矩形合并算法
#include "capture/i_capture.hpp"

#include <algorithm>
#include <unordered_set>
#include <vector>

#include "common/log.hpp"
#include "protocol/frame_format.hpp"

namespace apxdisp {

#ifdef _WIN32
std::unique_ptr<ICapture> createDdaCapture();
#endif
std::unique_ptr<ICapture> createNullCapture();

std::unique_ptr<ICapture> createCapture(CaptureKind kind, const CaptureTarget& target) {
    switch (kind) {
        case CaptureKind::Null:
            return createNullCapture();
        case CaptureKind::DesktopDuplication:
#ifdef _WIN32
            return createDdaCapture();
#else
            APX_LOG_W("Desktop Duplication 仅 Windows 可用，退回 NullCapture");
            return createNullCapture();
#endif
        case CaptureKind::Auto:
        default:
#ifdef _WIN32
        {
            auto cap = createDdaCapture();
            if (cap && cap->open(target)) return cap;
            APX_LOG_W("DDA 不可用（%s），退回 NullCapture",
                      cap ? cap->lastError().c_str() : "无法创建");
        }
#endif
            return createNullCapture();
    }
}

// 合并策略：先把矩形对齐到 gridSize 网格（降低碎片化），再按网格 cell 去重，
// 最后合并水平相邻的同 y 同 h 矩形，直到数量 <= maxRects。
// 万一仍超限（极端碎片化），直接退化为整帧 —— 宁可多传字节，也不让解码端丢内容。
std::vector<Rect> mergeDirtyRects(const std::vector<Rect>& in, uint32_t frameW, uint32_t frameH,
                                  uint16_t maxRects, uint32_t gridSize) {
    if (frameW == 0 || frameH == 0 || maxRects == 0) return {};

    auto clampU16 = [](uint32_t v) -> uint16_t {
        return static_cast<uint16_t>(std::min<uint32_t>(v, 0xFFFFu));
    };

    // 1) 对齐到网格并裁剪到画面内
    std::vector<Rect> aligned;
    aligned.reserve(in.size());
    for (const Rect& r : in) {
        if (r.w == 0 || r.h == 0) continue;
        uint32_t x0 = r.x, y0 = r.y;
        uint32_t x1 = static_cast<uint32_t>(r.x) + r.w;
        uint32_t y1 = static_cast<uint32_t>(r.y) + r.h;
        x0 = (x0 / gridSize) * gridSize;
        y0 = (y0 / gridSize) * gridSize;
        x1 = std::min<uint32_t>(frameW, ((x1 + gridSize - 1) / gridSize) * gridSize);
        y1 = std::min<uint32_t>(frameH, ((y1 + gridSize - 1) / gridSize) * gridSize);
        if (x1 <= x0 || y1 <= y0) continue;
        Rect a{};
        a.x = clampU16(x0);
        a.y = clampU16(y0);
        a.w = clampU16(x1 - x0);
        a.h = clampU16(y1 - y0);
        aligned.push_back(a);
    }
    if (aligned.empty()) {
        Rect full{};
        full.w = clampU16(frameW);
        full.h = clampU16(frameH);
        return {full};
    }

    // 2) 网格 cell 去重（用无序集合记录已覆盖的 cell）
    const uint32_t cellsX = (frameW + gridSize - 1) / gridSize;
    std::unordered_set<uint64_t> covered;
    std::vector<uint64_t> cells;
    for (const Rect& r : aligned) {
        const uint32_t cx0 = r.x / gridSize, cx1 = (static_cast<uint32_t>(r.x) + r.w + gridSize - 1) / gridSize;
        const uint32_t cy0 = r.y / gridSize, cy1 = (static_cast<uint32_t>(r.y) + r.h + gridSize - 1) / gridSize;
        for (uint32_t cy = cy0; cy < cy1; ++cy) {
            for (uint32_t cx = cx0; cx < cx1; ++cx) {
                const uint64_t key = static_cast<uint64_t>(cy) * cellsX + cx;
                if (covered.insert(key).second) cells.push_back(key);
            }
        }
    }
    std::sort(cells.begin(), cells.end());

    // 3) 同行连续 cell 合成条带，再纵向合并相同 x/w 的相邻条带
    std::vector<Rect> bands;
    size_t i = 0;
    while (i < cells.size()) {
        const uint32_t cy = static_cast<uint32_t>(cells[i] / cellsX);
        uint32_t cx0 = static_cast<uint32_t>(cells[i] % cellsX);
        uint32_t cx1 = cx0 + 1;
        size_t j = i + 1;
        while (j < cells.size() && static_cast<uint32_t>(cells[j] / cellsX) == cy &&
               static_cast<uint32_t>(cells[j] % cellsX) == cx1) {
            ++cx1;
            ++j;
        }
        Rect b{};
        b.x = clampU16(cx0 * gridSize);
        b.y = clampU16(cy * gridSize);
        b.w = clampU16(std::min<uint32_t>((cx1 - cx0) * gridSize, frameW - cx0 * gridSize));
        b.h = clampU16(std::min<uint32_t>(gridSize, frameH - cy * gridSize));
        bands.push_back(b);
        i = j;
    }

    std::vector<Rect> merged;
    for (const Rect& b : bands) {
        if (!merged.empty() && merged.back().x == b.x && merged.back().w == b.w &&
            static_cast<uint32_t>(merged.back().y) + merged.back().h == b.y) {
            merged.back().h = static_cast<uint16_t>(merged.back().h + b.h);
        } else {
            merged.push_back(b);
        }
    }

    if (merged.size() > maxRects) {
        Rect full{};
        full.w = clampU16(frameW);
        full.h = clampU16(frameH);
        return {full};
    }
    return merged;
}

}  // namespace apxdisp
