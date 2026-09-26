// ============================================================================
// Windows 输入注入 + 剪贴板监听（9511 受控端）
// 免驱：全部走 SendInput / Win32 剪贴板 API（与 wireless_link.cpp 同源思路）。
// ============================================================================
#include "apxpc/wireless/ctrl9511.hpp"

#if defined(_WIN32)

#include <windows.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>
#include <cstdio>

namespace apxpc::wireless {
namespace {

int usageToVk(uint8_t u) {
    if (u >= 0x04 && u <= 0x1D) return 'A' + (u - 0x04);
    if (u >= 0x1E && u <= 0x26) return '1' + (u - 0x1E);
    if (u >= 0x3A && u <= 0x45) return VK_F1 + (u - 0x3A);
    if (u >= 0x59 && u <= 0x61) return VK_NUMPAD1 + (u - 0x59);
    switch (u) {
        case 0x27: return '0';
        case 0x28: return VK_RETURN;
        case 0x29: return VK_ESCAPE;
        case 0x2A: return VK_BACK;
        case 0x2B: return VK_TAB;
        case 0x2C: return VK_SPACE;
        case 0x2D: return VK_OEM_MINUS;
        case 0x2E: return VK_OEM_PLUS;
        case 0x2F: return VK_OEM_4;
        case 0x30: return VK_OEM_6;
        case 0x31: return VK_OEM_5;
        case 0x32: return 0;
        case 0x33: return VK_OEM_1;
        case 0x34: return VK_OEM_7;
        case 0x35: return VK_OEM_3;
        case 0x36: return VK_OEM_COMMA;
        case 0x37: return VK_OEM_PERIOD;
        case 0x38: return VK_OEM_2;
        case 0x39: return VK_CAPITAL;
        case 0x49: return VK_INSERT;
        case 0x4A: return VK_HOME;
        case 0x4B: return VK_PRIOR;
        case 0x4C: return VK_DELETE;
        case 0x4D: return VK_END;
        case 0x4E: return VK_NEXT;
        case 0x4F: return VK_RIGHT;
        case 0x50: return VK_LEFT;
        case 0x51: return VK_DOWN;
        case 0x52: return VK_UP;
        case 0x53: return VK_NUMLOCK;
        case 0x54: return VK_DIVIDE;
        case 0x55: return VK_MULTIPLY;
        case 0x56: return VK_SUBTRACT;
        case 0x57: return VK_ADD;
        case 0x58: return VK_RETURN;
        case 0x62: return VK_NUMPAD0;
        case 0x63: return VK_DECIMAL;
        default: return 0;
    }
}
int modBitToVk(int bit) {
    switch (bit) {
        case 0: return VK_LCONTROL; case 1: return VK_LSHIFT;
        case 2: return VK_LMENU; case 3: return VK_LWIN;
        case 4: return VK_RCONTROL; case 5: return VK_RSHIFT;
        case 6: return VK_RMENU; case 7: return VK_RWIN;
        default: return 0;
    }
}
bool isExtendedVk(int vk) {
    switch (vk) {
        case VK_RCONTROL: case VK_RMENU: case VK_INSERT: case VK_DELETE:
        case VK_HOME: case VK_END: case VK_PRIOR: case VK_NEXT:
        case VK_LEFT: case VK_RIGHT: case VK_UP: case VK_DOWN:
        case VK_NUMLOCK: case VK_DIVIDE: case VK_LWIN: case VK_RWIN: case VK_APPS:
            return true;
        default: return false;
    }
}
bool sendVk(int vk, bool down) {
    if (vk == 0) return false;
    INPUT in{};
    in.type = INPUT_KEYBOARD;
    in.ki.wVk = static_cast<WORD>(vk);
    in.ki.dwFlags = (down ? 0 : KEYEVENTF_KEYUP) | (isExtendedVk(vk) ? KEYEVENTF_EXTENDEDKEY : 0);
    return ::SendInput(1, &in, sizeof(INPUT)) == 1;
}
int consumerBitToVk(uint8_t bit) {
    switch (bit) {
        case 0: return VK_VOLUME_UP; case 1: return VK_VOLUME_DOWN;
        case 2: return VK_VOLUME_MUTE; case 4: return VK_MEDIA_PLAY_PAUSE;
        case 5: return VK_MEDIA_PREV_TRACK; case 6: return VK_MEDIA_NEXT_TRACK;
        default: return 0;
    }
}

/// 手柄位（与手机端 GAMEPAD_KEYCODES 位序一致：A B X Y L1 R1 L2 R2 SELECT START C Z MODE THUMBL THUMBR）
/// → 键盘等价键。PC 无法注入真手柄（见 injectGamepad 注释），这是可用的降级映射。
static const WORD kGamepadVk[16] = {
    VK_RETURN,   // 0  A
    VK_ESCAPE,   // 1  B
    'E',         // 2  X
    'Q',         // 3  Y
    '1',         // 4  L1
    '2',         // 5  R1
    '3',         // 6  L2
    '4',         // 7  R2
    VK_TAB,      // 8  SELECT
    VK_SPACE,    // 9  START
    'R',         // 10 C
    'F',         // 11 Z
    VK_MENU,     // 12 MODE
    VK_LSHIFT,   // 13 THUMBL
    VK_LCONTROL, // 14 THUMBR
    0,           // 15 未使用
};

class WinInjector : public InputInjector {
public:
    void injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel) override {
        std::vector<INPUT> in;
        if (dx != 0 || dy != 0) {
            INPUT m{}; m.type = INPUT_MOUSE; m.mi.dwFlags = MOUSEEVENTF_MOVE;
            m.mi.dx = dx; m.mi.dy = dy; in.push_back(m);
        }
        if (wheel != 0) {
            INPUT w{}; w.type = INPUT_MOUSE; w.mi.dwFlags = MOUSEEVENTF_WHEEL;
            w.mi.mouseData = static_cast<DWORD>(static_cast<int>(wheel) * WHEEL_DELTA); in.push_back(w);
        }
        const struct { uint8_t bit; DWORD down; DWORD up; } table[] = {
            {0x01, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP},
            {0x02, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP},
            {0x04, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP},
        };
        for (const auto& t : table) {
            const bool was = (mouseButtons_ & t.bit) != 0;
            const bool now = (buttons & t.bit) != 0;
            if (was == now) continue;
            INPUT b{}; b.type = INPUT_MOUSE; b.mi.dwFlags = now ? t.down : t.up; in.push_back(b);
        }
        mouseButtons_ = buttons;
        if (!in.empty()) ::SendInput(static_cast<UINT>(in.size()), in.data(), sizeof(INPUT));
    }

    void injectTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) override {
        constexpr uint8_t kDown = 0, kUp = 1;
        DWORD click = 0;
        if (action == kDown) click = (buttons & 0x02) ? MOUSEEVENTF_RIGHTDOWN : (buttons & 0x04) ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_LEFTDOWN;
        else if (action == kUp || action == 3) click = (buttons & 0x02) ? MOUSEEVENTF_RIGHTUP : (buttons & 0x04) ? MOUSEEVENTF_MIDDLEUP : MOUSEEVENTF_LEFTUP;

        INPUT m{}; m.type = INPUT_MOUSE;
        m.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | click;
        m.mi.dx = static_cast<LONG>(x); m.mi.dy = static_cast<LONG>(y);
        ::SendInput(1, &m, sizeof(INPUT));
    }

    void injectKeyboard(uint8_t mod, const uint8_t* keys, size_t count) override {
        const size_t n = count > 6 ? 6 : count;
        for (int bit = 0; bit < 8; ++bit) {
            const uint8_t mask = static_cast<uint8_t>(1u << bit);
            const bool was = (kbMod_ & mask) != 0, now = (mod & mask) != 0;
            if (was != now) sendVk(modBitToVk(bit), now);
        }
        for (uint8_t i = 0; i < 6; ++i) {
            const uint8_t k = kbKeys_[i]; if (k == 0) continue;
            bool still = false;
            for (size_t j = 0; j < n; ++j) if (keys[j] == k) { still = true; break; }
            if (!still) sendVk(usageToVk(k), false);
        }
        for (size_t j = 0; j < n; ++j) {
            const uint8_t k = keys[j]; if (k == 0) continue;
            bool existed = false;
            for (uint8_t i = 0; i < 6; ++i) if (kbKeys_[i] == k) { existed = true; break; }
            if (!existed) sendVk(usageToVk(k), true);
        }
        kbMod_ = mod;
        for (uint8_t i = 0; i < 6; ++i) kbKeys_[i] = (i < n) ? keys[i] : 0;
    }

    void injectConsumer(uint16_t bitmap) override {
        const uint16_t pressed = static_cast<uint16_t>(bitmap & ~consumerBm_);
        const uint16_t released = static_cast<uint16_t>(consumerBm_ & ~bitmap);
        for (uint8_t bit = 0; bit < 8; ++bit) {
            const uint16_t m = static_cast<uint16_t>(1u << bit);
            if (pressed & m) sendVk(consumerBitToVk(bit), true);
            if (released & m) sendVk(consumerBitToVk(bit), false);
        }
        consumerBm_ = bitmap;
    }

    void setClipboard(const std::string& text) override {
        if (!::OpenClipboard(nullptr)) return;
        ::EmptyClipboard();
        const int wn = static_cast<int>(text.size()) + 1;
        HGLOBAL h = ::GlobalAlloc(GMEM_MOVEABLE, static_cast<SIZE_T>(wn) * sizeof(wchar_t));
        if (h) {
            auto* p = static_cast<wchar_t*>(::GlobalLock(h));
            if (p) {
                ::MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, p, wn);
                ::GlobalUnlock(h);
                ::SetClipboardData(CF_UNICODETEXT, h);
            }
        }
        ::CloseClipboard();
    }

    /**
     * 手柄（网络帧 0x07）。
     *
     * Windows 的 `SendInput` **没有**游戏手柄/摇杆概念，免驱架构下变不出真手柄
     * （那需要 ViGEmBus 之类的虚拟 HID 驱动，属第三方，不在本仓库零依赖范围内）。
     * 但"什么都不做"等于功能不存在 —— 这里做**键盘降级**，多数 PC 游戏与软件都能接受：
     *   按钮 → 键盘等价键（A→Enter、B→Esc、L1/R1→1/2 …，见 kGamepadVk）
     *   左摇杆 → 鼠标相对移动（复用 injectMouse，死区 12，位移按 1/4 缩放）
     * 右摇杆仍不注入（PC 上没有可类比的"视角"通道），调用方在文档里已如实说明。
     */
    void injectGamepad(uint16_t buttons, int8_t x, int8_t y, int8_t rx, int8_t ry) override {
        (void)rx; (void)ry;
        const uint16_t pressed = static_cast<uint16_t>(buttons & ~gpButtons_);
        const uint16_t released = static_cast<uint16_t>(gpButtons_ & ~buttons);
        for (uint8_t bit = 0; bit < 16; ++bit) {
            const WORD vk = kGamepadVk[bit];
            if (vk == 0) continue;
            const uint16_t m = static_cast<uint16_t>(1u << bit);
            if (pressed & m) sendVk(vk, true);
            if (released & m) sendVk(vk, false);
        }
        gpButtons_ = buttons;

        constexpr int kDead = 12;
        if (x > kDead || x < -kDead || y > kDead || y < -kDead) {
            injectMouse(mouseButtons_, static_cast<int8_t>(x / 4), static_cast<int8_t>(y / 4), 0);
        }

        static bool warned = false;
        if (!warned) {
            warned = true;
            // 本文件不引日志头（与其它注入路径一致，用 stderr 直接输出）
            std::fprintf(stderr, "[AllPeriph] 手柄经网络接入：本平台无手柄注入能力，已降级为"
                                 "「按钮→键盘 + 左摇杆→鼠标」；要真手柄需 ViGEmBus 之类的虚拟 HID 驱动\n");
        }
    }

    uint8_t mouseButtons_ = 0;
    uint16_t gpButtons_ = 0;   ///< 手柄按钮上一帧位图（降级注入要算边沿）
    uint16_t consumerBm_ = 0;
    uint8_t kbMod_ = 0;
    uint8_t kbKeys_[6] = {0, 0, 0, 0, 0, 0};
};

// 剪贴板轮询监听：每 500ms 比对一次 Unicode 文本，变化则回调。
class WinClipboardWatcher : public ClipboardWatcher {
public:
    explicit WinClipboardWatcher(std::function<void(const std::string&)> cb) : cb_(std::move(cb)) {}
    bool start() override {
        running_ = true;
        thread_ = std::thread([this] {
            std::wstring last;
            while (running_) {
                std::this_thread::sleep_for(std::chrono::milliseconds(500));
                std::wstring cur = readClipboard();
                if (!cur.empty() && cur != last) { last = cur; cb_(toUtf8(cur)); }
            }
        });
        return true;
    }
    void stop() override { running_ = false; if (thread_.joinable()) thread_.join(); }
private:
    static std::wstring readClipboard() {
        std::wstring out;
        if (!::OpenClipboard(nullptr)) return out;
        HANDLE h = ::GetClipboardData(CF_UNICODETEXT);
        if (h) {
            const auto* p = static_cast<const wchar_t*>(::GlobalLock(h));
            if (p) { out = p; ::GlobalUnlock(h); }
        }
        ::CloseClipboard();
        return out;
    }
    static std::string toUtf8(const std::wstring& w) {
        int n = ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, nullptr, 0, nullptr, nullptr);
        if (n <= 0) return {};
        std::string s(static_cast<size_t>(n), '\0');
        ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, &s[0], n, nullptr, nullptr);
        s.pop_back();
        return s;
    }
    std::function<void(const std::string&)> cb_;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

}  // namespace

std::unique_ptr<InputInjector> createPlatformInjector() { return std::make_unique<WinInjector>(); }
std::unique_ptr<ClipboardWatcher> createPlatformClipboardWatcher(
    std::function<void(const std::string&)> cb) {
    return std::make_unique<WinClipboardWatcher>(std::move(cb));
}

}  // namespace apxpc::wireless

#endif  // _WIN32
