// ============================================================================
// macOS 本地输入捕获（9511 遥控器）：CGEventTap(kCGHIDEventTap)
// 事件原样 return（不拦截），本机照常可用；需辅助功能权限（系统设置→隐私与安全）。
// ============================================================================
#include "apxpc/wireless/input_capture.hpp"

#if defined(__APPLE__)

#include <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

#include <thread>
#include <atomic>
#include <cstdint>

namespace apxpc::wireless {
namespace {

int8_t clamp8(int v) { return static_cast<int8_t>(v < -128 ? -128 : v > 127 ? 127 : v); }

// macOS 虚拟键码 → HID usage(0x07)。修饰键统一按左键（0xE0..0xE3）。
int macVkToUsage(int vk) {
    switch (vk) {
        case 0x00: return 0x04; case 0x0B: return 0x05; case 0x08: return 0x06;
        case 0x02: return 0x07; case 0x0E: return 0x08; case 0x03: return 0x09;
        case 0x05: return 0x0A; case 0x04: return 0x0B; case 0x22: return 0x0C;
        case 0x26: return 0x0D; case 0x28: return 0x0E; case 0x25: return 0x0F;
        case 0x2E: return 0x10; case 0x2D: return 0x11; case 0x1F: return 0x12;
        case 0x23: return 0x13; case 0x0C: return 0x14; case 0x0F: return 0x15;
        case 0x01: return 0x16; case 0x11: return 0x17; case 0x20: return 0x18;
        case 0x09: return 0x19; case 0x0D: return 0x1A; case 0x07: return 0x1B;
        case 0x10: return 0x1C; case 0x06: return 0x1D;
        case 0x12: return 0x1E; case 0x13: return 0x1F; case 0x14: return 0x20;
        case 0x15: return 0x21; case 0x17: return 0x22; case 0x16: return 0x23;
        case 0x1A: return 0x24; case 0x1C: return 0x25; case 0x19: return 0x26;
        case 0x1D: return 0x27;
        case 0x24: return 0x28; case 0x35: return 0x29; case 0x33: return 0x2A;
        case 0x30: return 0x2B; case 0x31: return 0x2C; case 0x75: return 0x4C;
        case 0x7B: return 0x50; case 0x7C: return 0x4F; case 0x7D: return 0x51;
        case 0x7E: return 0x52;
        case 0x3B: return 0xE0; case 0x38: return 0xE1; case 0x3A: return 0xE2;
        case 0x37: return 0xE3;
        default: return 0;
    }
}

class MacCapturer : public LocalInputCapturer {
public:
    explicit MacCapturer(Ctrl9511Client& c) : client_(&c) {}

    bool start() override {
        if (active_) return true;
        active_ = true;
        thread_ = std::thread([this] {
            const CGEventMask mask =
                CGEventMaskBit(kCGEventKeyDown) | CGEventMaskBit(kCGEventKeyUp) |
                CGEventMaskBit(kCGEventMouseMoved) | CGEventMaskBit(kCGEventLeftMouseDown) |
                CGEventMaskBit(kCGEventLeftMouseUp) | CGEventMaskBit(kCGEventRightMouseDown) |
                CGEventMaskBit(kCGEventRightMouseUp) | CGEventMaskBit(kCGEventOtherMouseDown) |
                CGEventMaskBit(kCGEventOtherMouseUp) | CGEventMaskBit(kCGEventLeftMouseDragged) |
                CGEventMaskBit(kCGEventRightMouseDragged) | CGEventMaskBit(kCGEventOtherMouseDragged) |
                CGEventMaskBit(kCGEventScrollWheel);
            tap_ = CGEventTapCreate(kCGHIDEventTap, kCGHeadInsertEventTap,
                                    kCGEventTapOptionDefault, mask, tapCb, this);
            if (!tap_) { active_ = false; return; }
            src_ = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap_, 0);
            if (!src_) { CFRelease(tap_); tap_ = nullptr; active_ = false; return; }
            CFRunLoopRef rl = CFRunLoopGetCurrent();
            CFRunLoopAddSource(rl, src_, kCFRunLoopCommonModes);
            CGEventTapEnable(tap_, true);
            // 用带超时的 mode 跑，便于 stop() 设 active_=false 后及时退出
            while (active_) CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.5, false);
        });
        return true;
    }

    void stop() override {
        active_ = false;
        if (tap_) CGEventTapEnable(tap_, false);
        if (thread_.joinable()) thread_.join();
        if (src_) { CFRelease(src_); src_ = nullptr; }
        if (tap_) { CFRelease(tap_); tap_ = nullptr; }
    }

    void onMove(int dx, int dy) { client_->sendMouse(mouseButtons_, clamp8(dx), clamp8(dy), 0); }
    void onWheel(int delta) { client_->sendMouse(mouseButtons_, 0, 0, clamp8(delta)); }
    void onButton(uint8_t bit, bool down) {
        if (down) mouseButtons_ |= bit; else mouseButtons_ &= ~bit;
        client_->sendMouse(mouseButtons_, 0, 0, 0);
    }
    void onKey(int usage, bool down) {
        if (usage >= 0xE0 && usage <= 0xE7) {
            const uint8_t m = static_cast<uint8_t>(1u << (usage - 0xE0));
            if (down) modMask_ |= m; else modMask_ &= ~m;
        } else if (down) {
            if (count_ < 6) keys_[count_++] = static_cast<uint8_t>(usage);
        } else {
            for (int i = 0; i < count_; ++i)
                if (keys_[i] == static_cast<uint8_t>(usage)) {
                    keys_[i] = keys_[--count_]; keys_[count_] = 0; break;
                }
        }
        client_->sendKeyboard(modMask_, keys_, static_cast<size_t>(count_));
    }

private:
    Ctrl9511Client* client_ = nullptr;
    CFMachPortRef tap_ = nullptr;
    CFRunLoopSourceRef src_ = nullptr;
    uint8_t mouseButtons_ = 0;
    uint8_t modMask_ = 0;
    uint8_t keys_[6] = {0, 0, 0, 0, 0, 0};
    int count_ = 0;
    std::thread thread_;

    static CGEventRef tapCb(CGEventTapProxy, CGEventType type, CGEventRef e, void* info) {
        auto* self = static_cast<MacCapturer*>(info);
        if (type == kCGEventKeyDown || type == kCGEventKeyUp) {
            const bool down = (type == kCGEventKeyDown);
            const CGKeyCode vk = static_cast<CGKeyCode>(
                CGEventGetIntegerValueField(e, kCGKeyboardEventKeycode));
            const int usage = macVkToUsage(static_cast<int>(vk));
            if (usage) self->onKey(usage, down);
        } else if (type == kCGEventMouseMoved || type == kCGEventLeftMouseDragged ||
                   type == kCGEventRightMouseDragged || type == kCGEventOtherMouseDragged) {
            const int64_t dx = CGEventGetIntegerValueField(e, kCGMouseEventDeltaX);
            const int64_t dy = CGEventGetIntegerValueField(e, kCGMouseEventDeltaY);
            self->onMove(static_cast<int>(dx), static_cast<int>(dy));
        } else if (type == kCGEventLeftMouseDown || type == kCGEventRightMouseDown ||
                   type == kCGEventOtherMouseDown) {
            const uint8_t bit = (type == kCGEventRightMouseDown) ? 0x02
                                : (type == kCGEventOtherMouseDown) ? 0x04 : 0x01;
            self->onButton(bit, true);
        } else if (type == kCGEventLeftMouseUp || type == kCGEventRightMouseUp ||
                   type == kCGEventOtherMouseUp) {
            const uint8_t bit = (type == kCGEventRightMouseUp) ? 0x02
                                : (type == kCGEventOtherMouseUp) ? 0x04 : 0x01;
            self->onButton(bit, false);
        } else if (type == kCGEventScrollWheel) {
            const int64_t dy = CGEventGetIntegerValueField(e, kCGScrollWheelEventDeltaAxis1);
            self->onWheel(static_cast<int>(dy));
        }
        return e;  // 透传，本机照常接收
    }
};

}  // namespace

std::unique_ptr<LocalInputCapturer> createPlatformCapturer(Ctrl9511Client& client) {
    return std::make_unique<MacCapturer>(client);
}

}  // namespace apxpc::wireless

#endif  // __APPLE__
