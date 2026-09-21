// 档 1 触控注入：SendInput + MOUSEEVENTF_ABSOLUTE（免驱，必须可用）
//
// 语义：把主触点（contacts[0]，若无则用「最近一次按下仍在活动」的触点）映射成鼠标事件：
//   - 新按下  -> MOUSEEVENTF_MOVE|ABSOLUTE + LEFTDOWN
//   - 移动    -> MOUSEEVENTF_MOVE|ABSOLUTE
//   - 抬起    -> LEFTUP
//   - 笔桶按钮 -> RIGHTDOWN / RIGHTUP
// 已知限制：单点、无压感、无悬停 —— 需要这些能力必须升到档 2/档 3。
#ifdef _WIN32

#include "inject/i_inject.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include <algorithm>
#include <cstdio>

#include "common/log.hpp"
#include "inject/touch_frame.hpp"

namespace apxdisp {

class SendInputInjector : public IInjector {
public:
    InjectTier tier() const override { return InjectTier::SendInput; }

    InjectCaps caps() const override {
        InjectCaps c{};
        c.tier = InjectTier::SendInput;
        c.multiTouch = false;
        c.pressure = false;
        c.tilt = false;
        c.hover = false;
        c.mouse = true;   // 支持相对鼠标语义（触控板）
        c.detail = "免驱；仅单点鼠标语义";
        return c;
    }

    bool init(const InjectTarget& target) override {
        target_ = target;
        // SendInput 归一化坐标覆盖整个虚拟桌面（含虚拟显示器）
        desktopOriginX_ = GetSystemMetrics(SM_XVIRTUALSCREEN);
        desktopOriginY_ = GetSystemMetrics(SM_YVIRTUALSCREEN);
        desktopWidth_   = static_cast<uint32_t>(GetSystemMetrics(SM_CXVIRTUALSCREEN));
        desktopHeight_  = static_cast<uint32_t>(GetSystemMetrics(SM_CYVIRTUALSCREEN));
        if (desktopWidth_ == 0 || desktopHeight_ == 0) {
            desktopWidth_ = static_cast<uint32_t>(GetSystemMetrics(SM_CXSCREEN));
            desktopHeight_ = static_cast<uint32_t>(GetSystemMetrics(SM_CYSCREEN));
        }
        ready_ = true;
        APX_LOG_I("档1 注入就绪：虚拟桌面 %d,%d %ux%u；目标屏 %d,%d %ux%u",
                  desktopOriginX_, desktopOriginY_, desktopWidth_, desktopHeight_,
                  target.originX, target.originY, target.width, target.height);
        return true;
    }

    bool inject(const TouchFrame& frame) override {
        if (!ready_) { lastError_ = "未 init"; return false; }

        if (frame.contacts.empty()) {
            // 全部抬起
            if (down_) {
                sendButton(false, false);
                down_ = false;
            }
            if (barrelDown_) { sendButton(false, true); barrelDown_ = false; }
            return true;
        }

        // 取 id 最小的触点作为主触点（多指时其余触点在档 1 下无法表达）
        const TouchContact* primary = &frame.contacts[0];
        for (const auto& c : frame.contacts) {
            if (c.id < primary->id) primary = &c;
        }

        int32_t ax = 0, ay = 0;
        mapToAbsoluteDesktop(primary->x, primary->y, target_, desktopOriginX_, desktopOriginY_,
                             desktopWidth_, desktopHeight_, ax, ay);
        moveTo(ax, ay);

        const bool wantDown = frame.penTipDown() || !frame.penInRange();  // 手指视为按下
        if (wantDown && !down_) { sendButton(true, false); down_ = true; }
        else if (!wantDown && down_) { sendButton(false, false); down_ = false; }

        const bool wantBarrel = frame.penBarrel();
        if (wantBarrel && !barrelDown_) { sendButton(true, true); barrelDown_ = true; }
        else if (!wantBarrel && barrelDown_) { sendButton(false, true); barrelDown_ = false; }
        return true;
    }

    void shutdown() override {
        if (down_) { sendButton(false, false); down_ = false; }
        ready_ = false;
    }

    // 相对鼠标注入（触控板）：单指移动/点按、滚轮、水平滚动、三键。
    bool injectMouse(const MouseFrame& f) override {
        if (!ready_) { lastError_ = "未 init"; return false; }
        if (f.dx != 0 || f.dy != 0) {
            INPUT in{};
            in.type = INPUT_MOUSE;
            in.mi.dx = f.dx;
            in.mi.dy = f.dy;
            in.mi.dwFlags = MOUSEEVENTF_MOVE;   // 相对移动
            if (SendInput(1, &in, sizeof(INPUT)) != 1) lastError_ = "SendInput(MOVE_REL) 失败";
        }
        emitButtonDiff(prevMouseButtons_, f.buttons);
        if (f.wheel != 0) {
            INPUT in{};
            in.type = INPUT_MOUSE;
            in.mi.mouseData = static_cast<DWORD>(static_cast<int>(f.wheel) * WHEEL_DELTA);
            in.mi.dwFlags = MOUSEEVENTF_WHEEL;
            if (SendInput(1, &in, sizeof(INPUT)) != 1) lastError_ = "SendInput(WHEEL) 失败";
        }
        if (f.pan != 0) {
            INPUT in{};
            in.type = INPUT_MOUSE;
            in.mi.mouseData = static_cast<DWORD>(static_cast<int>(f.pan) * WHEEL_DELTA);
            in.mi.dwFlags = MOUSEEVENTF_HWHEEL;
            if (SendInput(1, &in, sizeof(INPUT)) != 1) lastError_ = "SendInput(HWHEEL) 失败";
        }
        prevMouseButtons_ = f.buttons;
        return true;
    }

    std::string lastError() const override { return lastError_; }

private:
    // 依据按键位变化发出 down/up（避免重复发送）
    void emitButtonDiff(uint8_t prev, uint8_t cur) {
        const struct { uint8_t bit; DWORD downF; DWORD upF; } table[] = {
            {0x01, MOUSEEVENTF_LEFTDOWN,  MOUSEEVENTF_LEFTUP},
            {0x02, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP},
            {0x04, MOUSEEVENTF_MIDDLEDOWN,MOUSEEVENTF_MIDDLEUP},
        };
        for (const auto& t : table) {
            bool was = (prev & t.bit) != 0;
            bool now = (cur & t.bit) != 0;
            if (was == now) continue;
            INPUT in{};
            in.type = INPUT_MOUSE;
            in.mi.dwFlags = now ? t.downF : t.upF;
            if (SendInput(1, &in, sizeof(INPUT)) != 1) lastError_ = "SendInput(MOUSE_BUTTON) 失败";
        }
    }

    void moveTo(int32_t absX, int32_t absY) {
        INPUT in{};
        in.type = INPUT_MOUSE;
        in.mi.dx = static_cast<LONG>(absX);
        in.mi.dy = static_cast<LONG>(absY);
        in.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE;
        in.mi.time = 0;
        in.mi.dwExtraInfo = 0;
        if (SendInput(1, &in, sizeof(INPUT)) != 1) {
            lastError_ = "SendInput(MOVE) 失败";
        }
    }

    void sendButton(bool down, bool right) {
        INPUT in{};
        in.type = INPUT_MOUSE;
        in.mi.dwFlags = right ? (down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP)
                              : (down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP);
        if (SendInput(1, &in, sizeof(INPUT)) != 1) {
            lastError_ = "SendInput(BUTTON) 失败";
        }
    }

    InjectTarget target_{};
    int32_t desktopOriginX_ = 0;
    int32_t desktopOriginY_ = 0;
    uint32_t desktopWidth_ = 0;
    uint32_t desktopHeight_ = 0;
    bool ready_ = false;
    bool down_ = false;
    bool barrelDown_ = false;
    uint8_t prevMouseButtons_ = 0;
    std::string lastError_;
};

std::unique_ptr<IInjector> createSendInputInjector() { return std::make_unique<SendInputInjector>(); }

}  // namespace apxdisp

#endif  // _WIN32
