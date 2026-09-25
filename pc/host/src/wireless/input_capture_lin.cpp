// ============================================================================
// Linux 本地输入捕获（9511 遥控器）：读 /dev/input/event*（evdev）
// 被动只读，不拦截本机输入；需 root（或 input 组 + 设备权限）。自动挑选键鼠设备。
// ============================================================================
#include "apxpc/wireless/input_capture.hpp"

#if defined(__linux__)

#include <linux/input.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <string>
#include <thread>
#include <atomic>
#include <cstdint>

namespace apxpc::wireless {
namespace {

int8_t clamp8(int v) { return static_cast<int8_t>(v < -128 ? -128 : v > 127 ? 127 : v); }

// Linux 键码 → HID usage(0x07)。修饰键送 0xE0..0xE7。
int linuxKeyToUsage(int k) {
    if (k >= KEY_A && k <= KEY_Z) return 0x04 + (k - KEY_A);
    if (k >= KEY_1 && k <= KEY_0) return 0x1E + (k - KEY_1);
    if (k >= KEY_F1 && k <= KEY_F12) return 0x3A + (k - KEY_F1);
    switch (k) {
        case KEY_ENTER: return 0x28; case KEY_ESC: return 0x29; case KEY_BACKSPACE: return 0x2A;
        case KEY_TAB: return 0x2B; case KEY_SPACE: return 0x2C; case KEY_MINUS: return 0x2D;
        case KEY_EQUAL: return 0x2E; case KEY_LEFTBRACE: return 0x2F; case KEY_RIGHTBRACE: return 0x30;
        case KEY_BACKSLASH: return 0x31; case KEY_SEMICOLON: return 0x33; case KEY_APOSTROPHE: return 0x34;
        case KEY_GRAVE: return 0x35; case KEY_COMMA: return 0x36; case KEY_DOT: return 0x37;
        case KEY_SLASH: return 0x38; case KEY_CAPSLOCK: return 0x39;
        case KEY_LEFT: return 0x50; case KEY_RIGHT: return 0x4F; case KEY_UP: return 0x52;
        case KEY_DOWN: return 0x51; case KEY_INSERT: return 0x49; case KEY_HOME: return 0x4A;
        case KEY_PAGEUP: return 0x4B; case KEY_DELETE: return 0x4C; case KEY_END: return 0x4D;
        case KEY_PAGEDOWN: return 0x4E; case KEY_NUMLOCK: return 0x53;
        case KEY_LEFTCTRL: return 0xE0; case KEY_LEFTSHIFT: return 0xE1; case KEY_LEFTALT: return 0xE2;
        case KEY_LEFTMETA: return 0xE3; case KEY_RIGHTCTRL: return 0xE4; case KEY_RIGHTSHIFT: return 0xE5;
        case KEY_RIGHTALT: return 0xE6; case KEY_RIGHTMETA: return 0xE7;
        default: return 0;
    }
}

int openDevice(bool wantMouse) {
    for (int i = 0; i < 32; ++i) {
        char path[32];
        std::snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = ::open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        unsigned long evbits[EV_MAX / 64 + 1] = {0};
        if (ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), evbits) < 0) { ::close(fd); continue; }
        const bool hasKey = evbits[EV_KEY / 64] & (1UL << (EV_KEY % 64));
        const bool hasRel = evbits[EV_REL / 64] & (1UL << (EV_REL % 64));
        if (wantMouse) {
            if (hasRel) { ::close(fd); return ::open(path, O_RDONLY); }
        } else if (hasKey) {
            unsigned long keybits[KEY_MAX / 64 + 1] = {0};
            if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) >= 0 &&
                (keybits[KEY_A / 64] & (1UL << (KEY_A % 64)))) {
                ::close(fd); return ::open(path, O_RDONLY);
            }
        }
        ::close(fd);
    }
    return -1;
}

class LinCapturer : public LocalInputCapturer {
public:
    explicit LinCapturer(Ctrl9511Client& c) : client_(&c) {}

    bool start() override {
        if (active_) return true;
        mouseFd_ = openDevice(true);
        keyFd_ = openDevice(false);
        if (mouseFd_ < 0 && keyFd_ < 0) return false;
        active_ = true;
        if (mouseFd_ >= 0) mouseThread_ = std::thread([this] { readLoop(mouseFd_, true); });
        if (keyFd_ >= 0) keyThread_ = std::thread([this] { readLoop(keyFd_, false); });
        return true;
    }

    void stop() override {
        active_ = false;
        if (mouseThread_.joinable()) mouseThread_.join();
        if (keyThread_.joinable()) keyThread_.join();
        if (mouseFd_ >= 0) { ::close(mouseFd_); mouseFd_ = -1; }
        if (keyFd_ >= 0) { ::close(keyFd_); keyFd_ = -1; }
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
    int mouseFd_ = -1, keyFd_ = -1;
    uint8_t mouseButtons_ = 0;
    uint8_t modMask_ = 0;
    uint8_t keys_[6] = {0, 0, 0, 0, 0, 0};
    int count_ = 0;
    int dx_ = 0, dy_ = 0, wheel_ = 0;
    std::thread mouseThread_, keyThread_;

    void readLoop(int fd, bool isMouse) {
        struct input_event ev;
        while (active_) {
            const int n = static_cast<int>(::read(fd, &ev, sizeof(ev)));
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) { ::usleep(1000); continue; }
                break;
            }
            if (static_cast<size_t>(n) < sizeof(ev)) continue;
            if (ev.type == EV_REL) {
                if (ev.code == REL_X) dx_ += ev.value;
                else if (ev.code == REL_Y) dy_ += ev.value;
                else if (ev.code == REL_WHEEL) wheel_ += ev.value;
                client_->sendMouse(mouseButtons_, clamp8(dx_), clamp8(dy_), clamp8(wheel_));
                dx_ = dy_ = wheel_ = 0;
            } else if (ev.type == EV_KEY) {
                if (ev.code >= BTN_LEFT && ev.code <= BTN_TASK) {
                    const uint8_t bit = (ev.code == BTN_RIGHT) ? 0x02
                                        : (ev.code == BTN_MIDDLE) ? 0x04 : 0x01;
                    const bool down = ev.value != 0;
                    if (down) mouseButtons_ |= bit; else mouseButtons_ &= ~bit;
                    client_->sendMouse(mouseButtons_, 0, 0, 0);
                } else {
                    const int usage = linuxKeyToUsage(ev.code);
                    if (usage && isMouse == false) onKey(usage, ev.value != 0);
                    else if (usage) onKey(usage, ev.value != 0);  // 合并设备也走同一路径
                }
            }
        }
    }
};

}  // namespace

std::unique_ptr<LocalInputCapturer> createPlatformCapturer(Ctrl9511Client& client) {
    return std::make_unique<LinCapturer>(client);
}

}  // namespace apxpc::wireless

#endif  // __linux__
