// 触控注入抽象（ARCHITECTURE §5 上行链路，三档递进）
//
// 档 1 SendInput + MOUSEEVENTF_ABSOLUTE：免驱、任何进程可用；缺点是只有单点、无压感。
// 档 2 InjectSyntheticPointerInput：真触摸/笔（多点、压感、倾斜），
//      要求 uiAccess=true 的已签名二进制且位于系统目录（C:\Windows\System32）。
// 档 3 KMDF HID minidriver：完整数位板语义（免应用改造、支持 hover），需 EV 签名；
//      本轮只给设计与骨架，代码在 pc/display/inject/hid/。
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace apxdisp {

enum class InjectTier { Auto = 0, SendInput = 1, SyntheticPointer = 2, KmdfHid = 3 };

// PROTOCOL §2.5 归一化的触点（x/y 已归一化到虚拟屏宽高，0..65535）
struct TouchContact {
    uint16_t id       = 0;
    uint16_t x        = 0;
    uint16_t y        = 0;
    uint16_t pressure = 0;   // 笔 0..65535；手指固定 0x7FFF
};

// PROTOCOL §2.5 pen extra：tiltX i16, tiltY i16, orientation u16, 保留 u32
struct PenExtra {
    int16_t  tiltX       = 0;
    int16_t  tiltY       = 0;
    uint16_t orientation = 0;
};

struct TouchFrame {
    uint8_t  flags = 0;           // bit0=笔在量程内 bit1=笔尖接触 bit2=橡皮擦 bit3=桶按钮
    uint64_t tsNs  = 0;           // 手机端时基
    std::vector<TouchContact> contacts;
    bool     hasPen = false;
    PenExtra pen{};

    bool penInRange() const  { return (flags & 0x01) != 0; }
    bool penTipDown() const  { return (flags & 0x02) != 0; }
    bool penEraser() const   { return (flags & 0x04) != 0; }
    bool penBarrel() const   { return (flags & 0x08) != 0; }
};

// 注入目标：虚拟显示器在 Windows 虚拟桌面中的位置（由 IddCx 显示器枚举得到）
struct InjectTarget {
    int32_t  originX = 0;
    int32_t  originY = 0;
    uint32_t width   = 0;
    uint32_t height  = 0;
};

// 触控板语义：相对位移 + 按键 + 滚轮（与绝对坐标的 TouchFrame 语义不同，不可复用）
struct MouseFrame {
    uint8_t  buttons = 0;   // bit0 左 bit1 右 bit2 中
    int8_t   dx      = 0;   // 相对位移（已按灵敏度/加速度处理）
    int8_t   dy      = 0;
    int8_t   wheel   = 0;   // 垂直滚动（正=向上）
    int8_t   pan     = 0;   // 水平滚动
    uint64_t tsNs    = 0;

    bool left()   const { return (buttons & 0x01) != 0; }
    bool right()  const { return (buttons & 0x02) != 0; }
    bool middle() const { return (buttons & 0x04) != 0; }
};

struct InjectCaps {
    InjectTier tier = InjectTier::SendInput;
    bool multiTouch     = false;
    bool pressure       = false;
    bool tilt           = false;
    bool hover          = false;   // 悬停（笔在量程内但未接触）
    bool mouse          = false;   // 支持相对鼠标语义（触控板）
    bool needsUiAccess  = false;
    bool needsDriver    = false;
    std::string detail;
};

class IInjector {
public:
    virtual ~IInjector() = default;

    virtual InjectTier tier() const = 0;
    virtual InjectCaps caps() const = 0;
    virtual bool init(const InjectTarget& target) = 0;
    virtual bool inject(const TouchFrame& frame) = 0;
    // 相对鼠标注入（触控板）。默认不支持，具体后端按需实现。
    virtual bool injectMouse(const MouseFrame& frame) { (void)frame; return false; }
    virtual void shutdown() = 0;
    virtual std::string lastError() const = 0;
};

// prefer=Auto：档 2 → 档 1（档 3 需驱动，未安装时直接跳过）
std::unique_ptr<IInjector> createInjector(InjectTier prefer = InjectTier::Auto);

std::unique_ptr<IInjector> createSendInputInjector();
std::unique_ptr<IInjector> createSyntheticPointerInjector();

// 探测某档注入器是否支持相对鼠标语义（触控板），供面板据此启用/置灰调参项
bool injectorSupportsMouse(InjectTier prefer = InjectTier::Auto);

}  // namespace apxdisp
