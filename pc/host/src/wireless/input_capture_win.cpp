// ============================================================================
// Windows 本地输入捕获（9511 遥控器）：WH_KEYBOARD_LL / WH_MOUSE_LL + 消息泵
// 钩子只读取并转发，事件仍 CallNextHookEx 透传给本机，PC 自身照常可用。
// ============================================================================
#include "apxpc/wireless/input_capture.hpp"

#if defined(_WIN32)

#include <windows.h>
#include <thread>
#include <chrono>
#include <atomic>
#include <cstdint>

namespace apxpc::wireless {
namespace {

int8_t clamp8(int v) { return static_cast<int8_t>(v < -128 ? -128 : v > 127 ? 127 : v); }

// Windows VK → HID usage(0x07)。修饰键送 0xE0..0xE7（受控端按位映射）。
int vkToUsage(UINT vk, bool extended) {
    if (vk >= 'A' && vk <= 'Z') return 0x04 + (vk - 'A');
    if (vk >= '0' && vk <= '9') return 0x1E + (vk - '0');
    if (vk >= VK_F1 && vk <= VK_F12) return 0x3A + (vk - VK_F1);
    if (extended) {
        switch (vk) {
            case VK_LEFT: return 0x50; case VK_RIGHT: return 0x4F;
            case VK_UP: return 0x52;   case VK_DOWN: return 0x51;
            case VK_INSERT: return 0x49; case VK_DELETE: return 0x4C;
            case VK_HOME: return 0x4A; case VK_END: return 0x4D;
            case VK_PRIOR: return 0x4B; case VK_NEXT: return 0x4E;
            case VK_RCONTROL: return 0xE4; case VK_RMENU: return 0xE6;
            case VK_DIVIDE: return 0x54;
            default: return 0;
        }
    }
    switch (vk) {
        case VK_RETURN: return 0x28;   case VK_ESCAPE: return 0x29;
        case VK_BACK: return 0x2A;     case VK_TAB: return 0x2B;
        case VK_SPACE: return 0x2C;    case VK_OEM_MINUS: return 0x2D;
        case VK_OEM_PLUS: return 0x2E; case VK_OEM_4: return 0x2F;
        case VK_OEM_6: return 0x30;    case VK_OEM_5: return 0x31;
        case VK_OEM_1: return 0x33;    case VK_OEM_7: return 0x34;
        case VK_OEM_3: return 0x35;    case VK_OEM_COMMA: return 0x36;
        case VK_OEM_PERIOD: return 0x37; case VK_OEM_2: return 0x38;
        case VK_CAPITAL: return 0x39; case VK_LCONTROL: return 0xE0;
        case VK_LSHIFT: return 0xE1;   case VK_LMENU: return 0xE2;
        case VK_LWIN: return 0xE3;     case VK_RSHIFT: return 0xE5;
        case VK_NUMLOCK: return 0x53;  case VK_NUMPAD0: return 0x62;
        case VK_NUMPAD1: return 0x59;  case VK_NUMPAD2: return 0x5A;
        case VK_NUMPAD3: return 0x5B;  case VK_NUMPAD4: return 0x5C;
        case VK_NUMPAD5: return 0x5D;  case VK_NUMPAD6: return 0x5E;
        case VK_NUMPAD7: return 0x5F;  case VK_NUMPAD8: return 0x60;
        case VK_NUMPAD9: return 0x61;  case VK_DECIMAL: return 0x63;
        case VK_MULTIPLY: return 0x55; case VK_SUBTRACT: return 0x56;
        case VK_ADD: return 0x57;
        default: return 0;
    }
}

class WinCapturer : public LocalInputCapturer {
public:
    explicit WinCapturer(Ctrl9511Client& c) : client_(&c) {}

    bool start() override {
        if (active_) return true;
        g_self = this;
        hhMouse_ = SetWindowsHookEx(WH_MOUSE_LL, msProc, GetModuleHandle(nullptr), 0);
        hhKey_ = SetWindowsHookEx(WH_KEYBOARD_LL, kbProc, GetModuleHandle(nullptr), 0);
        if (!hhMouse_ || !hhKey_) { stop(); return false; }
        active_ = true;
        thread_ = std::thread([this] {
            MSG msg{};
            while (active_) {
                if (PeekMessage(&msg, nullptr, 0, 0, PM_REMOVE)) {
                    TranslateMessage(&msg);
                    DispatchMessage(&msg);
                } else {
                    std::this_thread::sleep_for(std::chrono::milliseconds(5));
                }
            }
        });
        return true;
    }

    void stop() override {
        active_ = false;
        if (hhMouse_) { UnhookWindowsHookEx(hhMouse_); hhMouse_ = nullptr; }
        if (hhKey_) { UnhookWindowsHookEx(hhKey_); hhKey_ = nullptr; }
        if (thread_.joinable()) thread_.join();
        g_self = nullptr;
    }

    void onMove(int x, int y) {
        if (lastX_ < 0) { lastX_ = x; lastY_ = y; return; }
        const int dx = x - lastX_, dy = y - lastY_;
        lastX_ = x; lastY_ = y;
        client_->sendMouse(mouseButtons_, clamp8(dx), clamp8(dy), 0);
    }
    void onWheel(int delta) { client_->sendMouse(mouseButtons_, 0, 0, clamp8(delta / WHEEL_DELTA)); }
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
    HHOOK hhMouse_ = nullptr, hhKey_ = nullptr;
    int lastX_ = -1, lastY_ = -1;
    uint8_t mouseButtons_ = 0;
    uint8_t modMask_ = 0;
    uint8_t keys_[6] = {0, 0, 0, 0, 0, 0};
    int count_ = 0;
    std::thread thread_;
    static WinCapturer* g_self;

    static LRESULT CALLBACK msProc(int n, WPARAM w, LPARAM l) {
        if (n >= 0 && g_self) {
            const auto* m = reinterpret_cast<MSLLHOOKSTRUCT*>(l);
            switch (w) {
                case WM_MOUSEMOVE: g_self->onMove(m->pt.x, m->pt.y); break;
                case WM_LBUTTONDOWN: g_self->onButton(0x01, true); break;
                case WM_LBUTTONUP: g_self->onButton(0x01, false); break;
                case WM_RBUTTONDOWN: g_self->onButton(0x02, true); break;
                case WM_RBUTTONUP: g_self->onButton(0x02, false); break;
                case WM_MBUTTONDOWN: g_self->onButton(0x04, true); break;
                case WM_MBUTTONUP: g_self->onButton(0x04, false); break;
                case WM_MOUSEWHEEL: g_self->onWheel(GET_WHEEL_DELTA_WPARAM(m->mouseData)); break;
                default: break;
            }
        }
        return CallNextHookEx(nullptr, n, w, l);
    }
    static LRESULT CALLBACK kbProc(int n, WPARAM w, LPARAM l) {
        if (n >= 0 && g_self) {
            const auto* k = reinterpret_cast<KBDLLHOOKSTRUCT*>(l);
            const bool down = (w == WM_KEYDOWN || w == WM_SYSKEYDOWN);
            const bool ext = (k->flags & LLKHF_EXTENDED) != 0;
            const int usage = vkToUsage(k->vkCode, ext);
            if (usage) g_self->onKey(usage, down);
        }
        return CallNextHookEx(nullptr, n, w, l);
    }
};

WinCapturer* WinCapturer::g_self = nullptr;

}  // namespace

std::unique_ptr<LocalInputCapturer> createPlatformCapturer(Ctrl9511Client& client) {
    return std::make_unique<WinCapturer>(client);
}

}  // namespace apxpc::wireless

#endif  // _WIN32
