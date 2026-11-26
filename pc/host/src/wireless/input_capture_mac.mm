// ============================================================================
// macOS 本地输入捕获（9511 遥控器）：CGEventTap(kCGHIDEventTap)
// 事件原样 return（不拦截），本机照常可用；需辅助功能权限（系统设置→隐私与安全）。
// ============================================================================
#include "apxpc/wireless/input_capture.hpp"

#if defined(__APPLE__)

#include "apxpc/log.hpp"

#include <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

#include <unistd.h>   // getpid：回声过滤见 tapCb

#include <thread>
#include <atomic>
#include <chrono>
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
            // kCGEventTapOptionDefault（活动过滤器）：跨屏独占模式靠它吞事件 ——
            // 回调返回 NULL 即拦截，本机收不到；镜像同步模式照常返回原事件透传。
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
        // 跨屏独占：若停在「手机焦点」里，退出焦点即可。光标全程停在进入点
        //（屏幕右缘，本就在屏内），无需归位动作。
        if (focused_.exchange(false)) {
            releaseForwardedState();   // 手机端悬挂的按键/鼠标按下随退焦点一并松开
            APX_LOGI("跨屏扩展：停止捕获，焦点交回电脑");
        }
    }

    // 跨屏独占开关（issue#171 报告人需求，2026-11-24）：tap 回调按事件读 crossMode_。
    // 关闭时若正聚焦，退出焦点即可 —— 光标停在进入点（右缘内侧），无需归位。
    void setCrossMode(bool on) override {
        crossMode_.store(on, std::memory_order_relaxed);
        if (!on && focused_.exchange(false)) {
            releaseForwardedState();   // 手机端悬挂的按键/鼠标按下随退焦点一并松开
            APX_LOGI("跨屏扩展：切回镜像同步，焦点交回电脑");
        }
        APX_LOGI("键鼠镜像工作模式：{}", on ? "跨屏独占（右缘进入）" : "镜像同步");
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
    // —— 跨屏独占（issue#171 报告人「跨屏扩展」需求，2026-11-24）——
    // 全部屏幕的并集矩形：多显示器时右缘 = 最右那块屏的外缘。
    static CGRect virtualBounds() {
        CGRect u = CGRectZero;
        // CGGetOnlineDisplayList：一次取在线显示器列表（上限 8 块足够覆盖常规多屏）
        CGDirectDisplayID ids[8];
        uint32_t got = 0;
        if (CGGetOnlineDisplayList(8, ids, &got) == kCGErrorSuccess && got > 0) {
            for (uint32_t i = 0; i < got; ++i) {
                const CGRect b = CGDisplayBounds(ids[i]);
                u = CGRectUnion(u, b);
            }
        }
        return u;
    }
    // 抵缘驻留计时：steady 毫秒（只在本类内比较差值）。
    static int64_t nowMs() {
        return (int64_t)(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
    }
    // 鼠标移动的跨屏独占路径：返回 true = 吞掉事件（tap 回调返回 NULL）。
    // macOS 的 kCGMouseEventDeltaX 是原始位移，与光标是否被屏幕钳住无关，
    // 不需要 Windows 那样的「进入点原点」换算，直接累计即可。
    bool crossMove(CGEventRef e) {
        // 光标绝对位置用 CGEventGetLocation（全局坐标），事件字段里没有可靠的
        // 位置键；位移仍取 kCGMouseEventDeltaX/DeltaY 原始值。
        const int64_t locX = (int64_t)CGEventGetLocation(e).x;
        const int64_t dx = CGEventGetIntegerValueField(e, kCGMouseEventDeltaX);
        const int64_t dy = CGEventGetIntegerValueField(e, kCGMouseEventDeltaY);
        if (!focused_.load(std::memory_order_relaxed)) {
            const CGRect virt = virtualBounds();
            const bool atEdge = locX >= (int64_t)(virt.origin.x + virt.size.width) - 1;
            if (!atEdge || dx < 0) {
                edgeFirstMs_ = 0;   // 离开边缘或往回走：重计驻留
            } else if (dx > 0 || edgeFirstMs_ == 0) {
                edgeFirstMs_ = nowMs();   // 首次抵边：开始计时
            }
            // 「抵边后仍连续收到移动事件 ≥250ms」= 用户在往屏外推，而非路过点一下。
            if (atEdge && edgeFirstMs_ != 0 && nowMs() - edgeFirstMs_ >= kEdgeDwellMs) {
                // 有鼠标键按住时不进入焦点：否则其抬起事件会被吞掉转发到手机，
                // Mac 端会卡在拖拽/窗口编辑状态（issue#171「卡在窗口编辑状态」）。
                // 按住期间不进入也不重置驻留，松开后下一个抵缘事件即可进入。
                if (physButtons_ == 0) {
                    edgeFirstMs_ = 0;
                    focused_.store(true, std::memory_order_relaxed);
                    accumDx_ = 0;
                    focusedEnterMs_ = nowMs();
                    APX_LOGI("跨屏扩展：焦点切到手机（右缘推入），本机输入已独占拦截");
                    onMove((int)dx, (int)dy);   // 进入位移照发（手感连续）
                    return true;   // 吞掉
                }
            }
            return false;   // 未聚焦：透传，本机照常（不转发）
        }
        // 聚焦中：移动事件全部吞掉，光标停在进入点（屏幕右缘）不动；原始位移
        // 直接累计，向左越过阈值即切回电脑 —— 本事件放行，光标顺势往回走。
        // issue#171 真机复盘（2026-11-26）：右向位移原先也累计进 accumDx_，向右推得
        // 越多回切门槛被垫得越高，用户在 Mac 上怎么动都出不去（日志 68s 无回切，
        // 只能关 Pad 被控解锁）；改为右向只清零不累计 —— 回切只认「从 0 起向左 48px」。
        accumDx_ += (int)dx;
        if (accumDx_ > 0) accumDx_ = 0;
        // 进入后的短宽限：手仍在向右推、回拉抖动（实测 46–143ms 即被切回）不应
        // 立刻退焦点；宽限期内照常向手机转发位移，但不做回切判定。
        if (nowMs() - focusedEnterMs_ >= kExitGraceMs && accumDx_ <= -kExitPx) {
            focused_.store(false, std::memory_order_relaxed);
            accumDx_ = 0;
            APX_LOGI("跨屏扩展：焦点切回电脑");
            releaseForwardedState();   // 手机端悬挂的按键/鼠标按下随退焦点一并松开
            return false;   // 放行：光标随真实位移往回走
        }
        onMove((int)dx, (int)dy);
        return true;   // 吞掉：电脑不响应
    }
    // 其余鼠标/键盘事件的跨屏独占路径：未聚焦透传不转发；聚焦转发并吞掉。
    template <typename F>
    bool crossSwallow(F&& fn) {
        if (!focused_.load(std::memory_order_relaxed)) return false;
        fn();
        return true;
    }
    // 退焦点/停止/切模式时，把已转发到手机的鼠标按下与键盘按键全部松开，
    // 避免手机端按键悬挂（对应的物理抬起事件在退焦点后走透传，不会再转发）。
    void releaseForwardedState() {
        if (mouseButtons_) {
            mouseButtons_ = 0;
            client_->sendMouse(0, 0, 0, 0);
        }
        if (modMask_ || count_ > 0) {
            modMask_ = 0;
            count_ = 0;
            for (auto& k : keys_) k = 0;
            client_->sendKeyboard(0, keys_, 0);
        }
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

    // —— 跨屏独占状态 ——
    // crossMode_/focused_ 原子：setCrossMode 在调用线程改模式并退焦点，tap 线程
    // 按事件读取；accumDx_/edgeFirstMs_ 只在 tap 线程触碰，
    // 竞态窗口最多丢一次位移，不影响正确性。
    std::atomic<bool> crossMode_{false};
    std::atomic<bool> focused_{false};
    int accumDx_ = 0;           // 聚焦期间向左累计位移（退出判定，右向清零不累计）
    int64_t edgeFirstMs_ = 0;   // 首次抵右缘的时刻（驻留判定，0=不在边缘）
    int64_t focusedEnterMs_ = 0;  // 进入焦点的时刻（回切宽限判定，tap 线程专用）
    uint8_t physButtons_ = 0;   // 物理鼠标键按下位图（跨模式跟踪，tap 线程专用）
    static constexpr int kExitPx = 48;            // 向回拉 48px 视为切回电脑
    static constexpr int64_t kEdgeDwellMs = 250;  // 抵缘持续 250ms 才进入，防误触
    static constexpr int64_t kExitGraceMs = 300;  // 进入后 300ms 内不做回切判定，防回拉抖动误退

    static CGEventRef tapCb(CGEventTapProxy, CGEventType type, CGEventRef e, void* info) {
        auto* self = static_cast<MacCapturer*>(info);
        // 跳过本进程注入的事件：手机控 Mac 时 input_injector_mac 在**本进程内**经
        // CGEventPost 注入，这些事件会流经本 tap，若不滤掉会「注入→捕获→回传手机」
        // 形成输入回声（与 Windows 端 LLMHF_INJECTED 过滤同口径）。物理事件的来源
        // 进程是 HID 系统态，不会等于本进程 PID，照常转发。
        if (CGEventGetIntegerValueField(e, kCGEventSourceUnixProcessID) == getpid()) {
            return e;
        }
        // 物理鼠标键状态跨模式跟踪（tap 线程专用）：进入跨屏焦点前据此确认没有
        // 按住的键。注入事件已在上方按 PID 过滤，到达这里的都是物理事件。
        switch (type) {
            case kCGEventLeftMouseDown:  self->physButtons_ |= 0x01; break;
            case kCGEventLeftMouseUp:    self->physButtons_ &= (uint8_t)~0x01; break;
            case kCGEventRightMouseDown: self->physButtons_ |= 0x02; break;
            case kCGEventRightMouseUp:   self->physButtons_ &= (uint8_t)~0x02; break;
            case kCGEventOtherMouseDown: self->physButtons_ |= 0x04; break;
            case kCGEventOtherMouseUp:   self->physButtons_ &= (uint8_t)~0x04; break;
            default: break;
        }
        // 跨屏独占：聚焦时吞掉一切已捕获事件（返回 NULL，本机收不到）；
        // 未聚焦时透传且不转发（电脑输入留在电脑）。镜像同步模式走原透传+转发路径。
        if (self->crossMode_.load(std::memory_order_relaxed)) {
            if (type == kCGEventMouseMoved || type == kCGEventLeftMouseDragged ||
                type == kCGEventRightMouseDragged || type == kCGEventOtherMouseDragged) {
                return self->crossMove(e) ? nullptr : e;
            }
            if (type == kCGEventKeyDown || type == kCGEventKeyUp) {
                const bool down = (type == kCGEventKeyDown);
                const CGKeyCode vk = static_cast<CGKeyCode>(
                    CGEventGetIntegerValueField(e, kCGKeyboardEventKeycode));
                const int usage = macVkToUsage(static_cast<int>(vk));
                return self->crossSwallow([&] { if (usage) self->onKey(usage, down); }) ? nullptr : e;
            }
            if (type == kCGEventLeftMouseDown || type == kCGEventRightMouseDown ||
                type == kCGEventOtherMouseDown) {
                const uint8_t bit = (type == kCGEventRightMouseDown) ? 0x02
                                    : (type == kCGEventOtherMouseDown) ? 0x04 : 0x01;
                return self->crossSwallow([&] { self->onButton(bit, true); }) ? nullptr : e;
            }
            if (type == kCGEventLeftMouseUp || type == kCGEventRightMouseUp ||
                type == kCGEventOtherMouseUp) {
                const uint8_t bit = (type == kCGEventRightMouseUp) ? 0x02
                                    : (type == kCGEventOtherMouseUp) ? 0x04 : 0x01;
                return self->crossSwallow([&] { self->onButton(bit, false); }) ? nullptr : e;
            }
            if (type == kCGEventScrollWheel) {
                const int64_t dy = CGEventGetIntegerValueField(e, kCGScrollWheelEventDeltaAxis1);
                return self->crossSwallow([&] { self->onWheel((int)dy); }) ? nullptr : e;
            }
            return e;
        }
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
