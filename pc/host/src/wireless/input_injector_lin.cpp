// ============================================================================
// Linux 输入注入 + 剪贴板（9511 受控端）
// 注入走 /dev/uinput（内核 uinput），需本进程对 /dev/uinput 有写权限（通常属 input 组）。
// 剪贴板走 xclip / wl-clipboard（最佳努力，未链接 X11）。
// ============================================================================
#include "apxpc/wireless/ctrl9511.hpp"

#if defined(__linux__)

#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <thread>
#include <atomic>
#include <array>

namespace apxpc::wireless {
namespace {

int hidToLinuxKey(uint8_t u) {
    if (u >= 0x04 && u <= 0x1D) return KEY_A + (u - 0x04);      // A..Z
    if (u >= 0x1E && u <= 0x27) return KEY_1 + (u - 0x1E);      // 1..0
    if (u >= 0x3A && u <= 0x45) return KEY_F1 + (u - 0x3A);     // F1..F12
    switch (u) {
        case 0x28: return KEY_ENTER;    case 0x29: return KEY_ESC;
        case 0x2A: return KEY_BACKSPACE;case 0x2B: return KEY_TAB;
        case 0x2C: return KEY_SPACE;    case 0x2D: return KEY_MINUS;
        case 0x2E: return KEY_EQUAL;    case 0x2F: return KEY_LEFTBRACE;
        case 0x30: return KEY_RIGHTBRACE;case 0x31: return KEY_BACKSLASH;
        case 0x33: return KEY_SEMICOLON;case 0x34: return KEY_APOSTROPHE;
        case 0x35: return KEY_GRAVE;    case 0x36: return KEY_COMMA;
        case 0x37: return KEY_DOT;      case 0x38: return KEY_SLASH;
        case 0x39: return KEY_CAPSLOCK; case 0x49: return KEY_INSERT;
        case 0x4A: return KEY_HOME;     case 0x4B: return KEY_PAGEUP;
        case 0x4C: return KEY_DELETE;   case 0x4D: return KEY_END;
        case 0x4E: return KEY_PAGEDOWN; case 0x4F: return KEY_RIGHT;
        case 0x50: return KEY_LEFT;     case 0x51: return KEY_DOWN;
        case 0x52: return KEY_UP;       case 0x53: return KEY_NUMLOCK;
        case 0x54: return KEY_KPSLASH;  case 0x55: return KEY_KPASTERISK;
        case 0x56: return KEY_KPMINUS;  case 0x57: return KEY_KPPLUS;
        case 0x58: return KEY_KPENTER;  case 0x62: return KEY_KP0;
        case 0x63: return KEY_KPDOT;
        default: return 0;
    }
}
int consumerToLinuxKey(uint8_t bit) {
    switch (bit) {
        case 0: return KEY_VOLUMEUP;   case 1: return KEY_VOLUMEDOWN;
        case 2: return KEY_MUTE;       case 4: return KEY_PLAYPAUSE;
        case 5: return KEY_PREVIOUSSONG; case 6: return KEY_NEXTSONG;
        default: return 0;
    }
}

int openUinput() {
    int fd = ::open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) fd = ::open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    return fd;
}

class LinInjector : public InputInjector {
public:
    LinInjector() {
        fd_ = openUinput();
        if (fd_ < 0) return;
        ioctl(fd_, UI_SET_EVBIT, EV_KEY);
        ioctl(fd_, UI_SET_EVBIT, EV_REL);
        ioctl(fd_, UI_SET_EVBIT, EV_ABS);
        ioctl(fd_, UI_SET_KEYBIT, BTN_LEFT);
        ioctl(fd_, UI_SET_KEYBIT, BTN_RIGHT);
        ioctl(fd_, UI_SET_KEYBIT, BTN_MIDDLE);
        ioctl(fd_, UI_SET_KEYBIT, BTN_TOUCH);
        ioctl(fd_, UI_SET_RELBIT, REL_X);
        ioctl(fd_, UI_SET_RELBIT, REL_Y);
        ioctl(fd_, UI_SET_RELBIT, REL_WHEEL);
        for (int k = KEY_A; k <= KEY_Z; ++k) ioctl(fd_, UI_SET_KEYBIT, k);
        for (int k = KEY_1; k <= KEY_0; ++k) ioctl(fd_, UI_SET_KEYBIT, k);
        for (int k = KEY_F1; k <= KEY_F12; ++k) ioctl(fd_, UI_SET_KEYBIT, k);
        ioctl(fd_, UI_SET_KEYBIT, KEY_ENTER); ioctl(fd_, UI_SET_KEYBIT, KEY_SPACE);
        ioctl(fd_, UI_SET_KEYBIT, KEY_BACKSPACE); ioctl(fd_, UI_SET_KEYBIT, KEY_TAB);
        ioctl(fd_, UI_SET_KEYBIT, KEY_ESC); ioctl(fd_, UI_SET_KEYBIT, KEY_LEFT);
        ioctl(fd_, UI_SET_KEYBIT, KEY_RIGHT); ioctl(fd_, UI_SET_KEYBIT, KEY_UP);
        ioctl(fd_, UI_SET_KEYBIT, KEY_DOWN); ioctl(fd_, UI_SET_KEYBIT, KEY_VOLUMEUP);
        ioctl(fd_, UI_SET_KEYBIT, KEY_VOLUMEDOWN); ioctl(fd_, UI_SET_KEYBIT, KEY_MUTE);
        ioctl(fd_, UI_SET_KEYBIT, KEY_PLAYPAUSE); ioctl(fd_, UI_SET_KEYBIT, KEY_PREVIOUSSONG);
        ioctl(fd_, UI_SET_KEYBIT, KEY_NEXTSONG);
        ioctl(fd_, UI_SET_EVBIT, EV_ABS);
        ioctl(fd_, UI_SET_KEYBIT, BTN_A); ioctl(fd_, UI_SET_KEYBIT, BTN_B);
        ioctl(fd_, UI_SET_KEYBIT, BTN_X); ioctl(fd_, UI_SET_KEYBIT, BTN_Y);
        ioctl(fd_, UI_SET_KEYBIT, BTN_TL); ioctl(fd_, UI_SET_KEYBIT, BTN_TR);
        ioctl(fd_, UI_SET_KEYBIT, BTN_TL2); ioctl(fd_, UI_SET_KEYBIT, BTN_TR2);
        ioctl(fd_, UI_SET_KEYBIT, BTN_SELECT); ioctl(fd_, UI_SET_KEYBIT, BTN_START);
        ioctl(fd_, UI_SET_KEYBIT, BTN_MODE);
        ioctl(fd_, UI_SET_ABSBIT, ABS_RX);
        ioctl(fd_, UI_SET_ABSBIT, ABS_RY);
        ioctl(fd_, UI_SET_ABSBIT, ABS_Z);
        ioctl(fd_, UI_SET_ABSBIT, ABS_RZ);
        ioctl(fd_, UI_SET_ABSBIT, ABS_X);
        ioctl(fd_, UI_SET_ABSBIT, ABS_Y);

        struct uinput_setup us {};
        std::strncpy(us.name, "AllPeriph Remote", UINPUT_MAX_NAME_SIZE - 1);
        us.id.bustype = BUS_VIRTUAL; us.id.vendor = 0x4150; us.id.product = 0x3931;
        us.absmin[ABS_X] = 0; us.absmax[ABS_X] = 65535;
        us.absmin[ABS_Y] = 0; us.absmax[ABS_Y] = 65535;
        us.absmin[ABS_RX] = -127; us.absmax[ABS_RX] = 127;
        us.absmin[ABS_RY] = -127; us.absmax[ABS_RY] = 127;
        us.absmin[ABS_Z] = -127; us.absmax[ABS_Z] = 127;
        us.absmin[ABS_RZ] = -127; us.absmax[ABS_RZ] = 127;
        if (ioctl(fd_, UI_DEV_SETUP, &us) < 0) { ::close(fd_); fd_ = -1; return; }
        if (ioctl(fd_, UI_DEV_CREATE, 0) < 0) { ::close(fd_); fd_ = -1; return; }
    }
    ~LinInjector() override {
        if (fd_ >= 0) { ioctl(fd_, UI_DEV_DESTROY, 0); ::close(fd_); }
    }
    bool ok() const { return fd_ >= 0; }

    void emit(uint16_t type, uint16_t code, int32_t value) {
        struct input_event ev {};
        ev.type = type; ev.code = code; ev.value = value;
        ::write(fd_, &ev, sizeof(ev));
    }
    void syn() { emit(EV_SYN, SYN_REPORT, 0); }

    void injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel) override {
        if (fd_ < 0) return;
        if (dx || dy) { emit(EV_REL, REL_X, dx); emit(EV_REL, REL_Y, dy); }
        if (wheel) emit(EV_REL, REL_WHEEL, wheel);
        const struct { uint8_t bit; int code; } table[] = {
            {0x01, BTN_LEFT}, {0x02, BTN_RIGHT}, {0x04, BTN_MIDDLE}};
        for (const auto& t : table) {
            const bool was = (mouseButtons_ & t.bit) != 0, now = (buttons & t.bit) != 0;
            if (was != now) emit(EV_KEY, t.code, now ? 1 : 0);
        }
        mouseButtons_ = buttons;
        syn();
    }

    void injectTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) override {
        if (fd_ < 0) return;
        constexpr uint8_t kDown = 0, kUp = 1;
        if (action == kDown) emit(EV_KEY, BTN_TOUCH, 1);
        emit(EV_ABS, ABS_X, x);
        emit(EV_ABS, ABS_Y, y);
        if (action == kUp || action == 3) emit(EV_KEY, BTN_TOUCH, 0);
        syn();
    }

    void injectKeyboard(uint8_t mod, const uint8_t* keys, size_t count) override {
        if (fd_ < 0) return;
        const size_t n = count > 6 ? 6 : count;
        // 修饰键（左 Ctrl/Shift/Alt/Win 与右半镜像；Linux 不区分左右就用左键码）
        const int modMap[8] = {KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_LEFTALT, KEY_LEFTMETA,
                               KEY_RIGHTCTRL, KEY_RIGHTSHIFT, KEY_RIGHTALT, KEY_RIGHTMETA};
        for (int bit = 0; bit < 8; ++bit) {
            const uint8_t m = static_cast<uint8_t>(1u << bit);
            const bool was = (kbMod_ & m) != 0, now = (mod & m) != 0;
            if (was != now) emit(EV_KEY, modMap[bit], now ? 1 : 0);
        }
        for (uint8_t i = 0; i < 6; ++i) {
            const uint8_t k = kbKeys_[i]; if (!k) continue;
            bool still = false; for (size_t j = 0; j < n; ++j) if (keys[j] == k) { still = true; break; }
            if (!still) { const int lc = hidToLinuxKey(k); if (lc) emit(EV_KEY, lc, 0); }
        }
        for (size_t j = 0; j < n; ++j) {
            const uint8_t k = keys[j]; if (!k) continue;
            bool existed = false; for (uint8_t i = 0; i < 6; ++i) if (kbKeys_[i] == k) { existed = true; break; }
            if (!existed) { const int lc = hidToLinuxKey(k); if (lc) emit(EV_KEY, lc, 1); }
        }
        kbMod_ = mod;
        for (uint8_t i = 0; i < 6; ++i) kbKeys_[i] = (i < n) ? keys[i] : 0;
        syn();
    }

    void injectConsumer(uint16_t bitmap) override {
        if (fd_ < 0) return;
        const uint16_t pressed = static_cast<uint16_t>(bitmap & ~consumerBm_);
        const uint16_t released = static_cast<uint16_t>(consumerBm_ & ~bitmap);
        for (uint8_t bit = 0; bit < 8; ++bit) {
            const uint16_t m = static_cast<uint16_t>(1u << bit);
            const int lc = consumerToLinuxKey(bit);
            if (!lc) continue;
            if (pressed & m) emit(EV_KEY, lc, 1);
            if (released & m) emit(EV_KEY, lc, 0);
        }
        consumerBm_ = bitmap;
        syn();
    }

    void injectGamepad(uint16_t buttons, int8_t x, int8_t y, int8_t rx, int8_t ry) override {
        if (fd_ < 0) return;
        const int btnMap[16] = { BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
                                 BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START,
                                 BTN_C, BTN_Z, BTN_MODE, BTN_THUMBL, BTN_THUMBR, 0 };
        for (int bit = 0; bit < 16; ++bit) {
            const int code = btnMap[bit];
            if (code == 0) continue;
            const uint16_t m = static_cast<uint16_t>(1u << bit);
            const bool was = (gamepadButtons_ & m) != 0, now = (buttons & m) != 0;
            if (was != now) emit(EV_KEY, code, now ? 1 : 0);
        }
        gamepadButtons_ = buttons;
        emit(EV_ABS, ABS_RX, x);
        emit(EV_ABS, ABS_RY, y);
        emit(EV_ABS, ABS_Z, rx);
        emit(EV_ABS, ABS_RZ, ry);
        syn();
    }

    void setClipboard(const std::string& text) override {
        FILE* p = popen("xclip -selection clipboard -in 2>/dev/null || wl-copy 2>/dev/null", "w");
        if (!p) return;
        fwrite(text.data(), 1, text.size(), p);
        pclose(p);
    }

    int fd_ = -1;
    uint8_t mouseButtons_ = 0;
    uint16_t consumerBm_ = 0;
    uint8_t kbMod_ = 0;
    uint8_t kbKeys_[6] = {0, 0, 0, 0, 0, 0};
    uint16_t gamepadButtons_ = 0;
};

class LinClipboardWatcher : public ClipboardWatcher {
public:
    explicit LinClipboardWatcher(std::function<void(const std::string&)> cb) : cb_(std::move(cb)) {}
    bool start() override {
        running_ = true;
        thread_ = std::thread([this] {
            std::string last;
            while (running_) {
                std::this_thread::sleep_for(std::chrono::milliseconds(800));
                std::string cur = readClip();
                if (!cur.empty() && cur != last) { last = cur; cb_(cur); }
            }
        });
        return true;
    }
    void stop() override { running_ = false; if (thread_.joinable()) thread_.join(); }
private:
    static std::string readClip() {
        FILE* p = popen("xclip -selection clipboard -o 2>/dev/null || wl-paste 2>/dev/null", "r");
        if (!p) return {};
        std::string s; char buf[512]; size_t r;
        while ((r = fread(buf, 1, sizeof(buf), p)) > 0) s.append(buf, r);
        pclose(p);
        return s;
    }
    std::function<void(const std::string&)> cb_;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

}  // namespace

std::unique_ptr<InputInjector> createPlatformInjector() {
    auto in = std::make_unique<LinInjector>();
    return in->ok() ? std::unique_ptr<InputInjector>(in.release()) : nullptr;
}
std::unique_ptr<ClipboardWatcher> createPlatformClipboardWatcher(
    std::function<void(const std::string&)> cb) {
    return std::make_unique<LinClipboardWatcher>(std::move(cb));
}

}  // namespace apxpc::wireless

#endif  // __linux__
