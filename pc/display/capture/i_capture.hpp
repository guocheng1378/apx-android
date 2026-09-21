// 抓屏抽象（ARCHITECTURE §5 下行链路第 2 环）
//
// 副屏场景抓的是「IddCx 虚拟显示器」那一屏，因此走 Desktop Duplication（DDA）：
// 每次 AcquireNextFrame 同时拿到 DirtyRects（脏矩形）与 MoveRects（滚动/窗口位移），
// 只把这两类区域送给编码器 —— 静态桌面下码率可降一个数量级。
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "common/types.hpp"

namespace apxdisp {

// 抓屏目标：优先按「设备名/适配器索引」定位虚拟显示器
struct CaptureTarget {
    std::wstring deviceName;  // 如 \\.\DISPLAY5（空则自动挑最后一个非主显示器）
    int adapterIndex = -1;    // DXGI 适配器索引（-1 自动）
    int outputIndex  = -1;    // 适配器内的输出索引（-1 自动）
    bool preferVirtual = true; // 自动模式下优先选 IddCx 虚拟显示器
};

enum class CaptureKind { Auto = 0, DesktopDuplication = 1, Null = 2 };

struct CaptureInfo {
    uint32_t width  = 0;
    uint32_t height = 0;
    uint32_t stride = 0;
    bool     zeroCopyD3D11 = false;  // 是否可提供 ID3D11Texture2D 给硬编
    std::wstring deviceName;
};

class ICapture {
public:
    virtual ~ICapture() = default;

    virtual bool open(const CaptureTarget& target) = 0;
    virtual void close() = 0;
    virtual bool isOpen() const = 0;

    // 取一帧；timeoutMs 为等待桌面更新的超时（VSync 节奏）
    // 返回 false 表示超时（无新帧，可跳过）或失败（用 lastError() 区分）
    virtual bool acquireFrame(RawFrame& out, uint32_t timeoutMs) = 0;

    // DDA 会话可能因模式切换/锁屏/权限失效而断开，上层需能重建
    virtual bool needsReopen() const = 0;
    virtual const CaptureInfo& info() const = 0;
    virtual std::string lastError() const = 0;
};

// 工厂：Auto 在 Windows 上优先 DDA，失败/无显示器时退回 Null（合成画面，仅自测用）
std::unique_ptr<ICapture> createCapture(CaptureKind kind = CaptureKind::Auto,
                                        const CaptureTarget& target = CaptureTarget{});

// 矩形工具：合并/裁剪脏矩形，保证数量不超过 kMaxDirtyRects
std::vector<Rect> mergeDirtyRects(const std::vector<Rect>& in, uint32_t frameW, uint32_t frameH,
                                  uint16_t maxRects, uint32_t gridSize = 64);

}  // namespace apxdisp
