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

// 剪贴板回环抑制：我们自己刚写过剪贴板（0x21 反向剪贴板回写 / Ctrl+V 回传）时，
// watcher 在 600ms 内不回传 —— 否则「写本机剪贴板 → watcher 发现变化 → 又发回对端」
// 会来回刷，对端剪贴板被我们搞乱。与 input_injector_win.cpp / mac 版同一套语义。
std::atomic<int64_t> g_clipSuppressMs{0};

int64_t steadyMsNow() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

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
        case 0x39: return KEY_CAPSLOCK;
        case 0x32: return 0;                    // 非美 #~：宿主键位无独立键，有意不映射（同 win）
        // —— 以下整段原先缺失，落 default → 0 被静默丢弃（与 Windows 侧同一批）——
        case 0x46: return KEY_SYSRQ;            // PrtSc
        case 0x47: return KEY_SCROLLLOCK;       // ScrLk
        case 0x48: return KEY_PAUSE;            // Pause/Break
        case 0x65: return KEY_COMPOSE;          // 「菜单」= Application/Menu 键
        case 0x66: return KEY_POWER;            // 键盘 Power（软电源）
        case 0x64: return KEY_102ND;            // 非美键盘 \|（ISO 102 键）
        case 0x67: return KEY_KPEQUAL;          // 小键盘 =
        // 0x5A 是**小键盘 2**（下面小键盘数字段已映射 KEY_KP2），不是 Clear。
        // 原来这里还留了一条 case 0x5A: KEY_CLEAR —— 与下面的 KP2 构成重复 case 标签，
        // C++ 直接编译失败，已删。
        case 0x49: return KEY_INSERT;
        case 0x4A: return KEY_HOME;     case 0x4B: return KEY_PAGEUP;
        case 0x4C: return KEY_DELETE;   case 0x4D: return KEY_END;
        case 0x4E: return KEY_PAGEDOWN; case 0x4F: return KEY_RIGHT;
        case 0x50: return KEY_LEFT;     case 0x51: return KEY_DOWN;
        case 0x52: return KEY_UP;       case 0x53: return KEY_NUMLOCK;
        case 0x54: return KEY_KPSLASH;  case 0x55: return KEY_KPASTERISK;
        case 0x56: return KEY_KPMINUS;  case 0x57: return KEY_KPPLUS;
        case 0x58: return KEY_KPENTER;
        // 小键盘数字 1..9 原先整段缺失（只有 KP0/KPDOT）→ 数字小键盘在 Linux 上按了没反应。
        case 0x59: return KEY_KP1;      case 0x5A: return KEY_KP2;
        case 0x5B: return KEY_KP3;      case 0x5C: return KEY_KP4;
        case 0x5D: return KEY_KP5;      case 0x5E: return KEY_KP6;
        case 0x5F: return KEY_KP7;      case 0x60: return KEY_KP8;
        case 0x61: return KEY_KP9;      case 0x62: return KEY_KP0;
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
        ioctl(fd_, UI_SET_RELBIT, REL_HWHEEL);   // 横向滚动（0x01 帧第 6 字节 pan）
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

        // 轴范围必须逐轴走 UI_ABS_SETUP：`us.absmin[ABS_X] = …` 是 uinput_user_dev 时代的
        // 写法，现代 struct uinput_setup（内核 4.5 起，<linux/uinput.h>）里根本没有这两个
        // 数组 —— g++ 直接报 “has no member named 'absmin'”，Linux 目标因此长期编不过
        // （12 处错误，Windows/macOS 不受影响，因为那两个平台的文件各自有实现）。
        // 顺序有讲究：EV/KEY/REL/ABS 位（上面的 UI_SET_*BIT）→ 逐轴 UI_ABS_SETUP
        // → UI_DEV_SETUP → UI_DEV_CREATE。
        const struct { int code; int lo; int hi; } absAxes[] = {
            {ABS_X, 0, 65535}, {ABS_Y, 0, 65535},
            {ABS_RX, -127, 127}, {ABS_RY, -127, 127},
            {ABS_Z, -127, 127}, {ABS_RZ, -127, 127},
        };
        for (const auto& ax : absAxes) {
            struct uinput_abs_setup as {};
            as.code = static_cast<uint16_t>(ax.code);
            as.absinfo.minimum = ax.lo;
            as.absinfo.maximum = ax.hi;
            ioctl(fd_, UI_ABS_SETUP, &as);
        }

        struct uinput_setup us {};
        std::strncpy(us.name, "AllPeriph Remote", UINPUT_MAX_NAME_SIZE - 1);
        us.id.bustype = BUS_VIRTUAL; us.id.vendor = 0x4150; us.id.product = 0x3931;
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

    void injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel, int8_t pan = 0) override {
        if (fd_ < 0) return;
        if (dx || dy) { emit(EV_REL, REL_X, dx); emit(EV_REL, REL_Y, dy); }
        if (wheel) emit(EV_REL, REL_WHEEL, wheel);
        if (pan) emit(EV_REL, REL_HWHEEL, pan);   // 横向滚动（右为正，与 0x01 帧第 6 字节同号）
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
    // 析构必须 stop：start() 起的线程若没停，析构里那个未 join 的 std::thread 直接
    // std::terminate（macOS 侧 v223 崩溃 issue#47 就是这块的同型 bug —— 那份当时改了，
    // Linux 这一份还是老形态，属同一类隐患，一并对齐）。
    ~LinClipboardWatcher() override { stop(); }
    bool start() override {
        if (thread_.joinable()) return false;   // 重复 start：拒绝，不覆盖线程句柄
        running_ = true;
        auto alive = alive_;
        auto cb = cb_;                          // 副本：回调归线程自己所有，不碰 this->cb_
        thread_ = std::thread([alive, cb] {
            std::string last;
            while (alive->load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(800));
                if (!alive->load()) break;
                // 回环抑制：我们自己刚写过剪贴板时别回传（见 g_clipSuppressMs 注释）
                if (steadyMsNow() - g_clipSuppressMs.load() < 600) continue;
                std::string cur = readClip();
                if (!cur.empty() && cur != last) { last = cur; cb(cur); }
            }
        });
        return true;
    }
    void stop() override {
        running_ = false;
        if (alive_) alive_->store(false);
        if (thread_.joinable()) thread_.join();
    }
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
    std::shared_ptr<std::atomic<bool>> alive_ = std::make_shared<std::atomic<bool>>(true);
    std::thread thread_;
};

}  // namespace

// ============================================================================
// 自由函数（ctrl9511.hpp 声明）用的共享注入器与助手
// ============================================================================
namespace {

// 共享注入器：自由函数没有接收线程那个实例的句柄，这里懒创建一个自己用。
// 故意不 delete（进程退出时内核自动回收 uinput 设备），避免静态析构期再碰设备文件。
// 拿不到 /dev/uinput（权限不足）时返回 nullptr —— 调用方如实返回 false，不假装成功。
LinInjector* sharedInjector() {
    static LinInjector* inst = [] {
        auto* p = new LinInjector();
        return p->ok() ? p : nullptr;
    }();
    return inst;
}
std::mutex g_sharedInjectorMutex;   // injectKeyboard 内部有按键状态，串行调用

// 读命令 stdout（命令是常量串，不接受外部输入；返回 0 表示 sh 退出码 0）
int popenRead(const char* cmd, std::string* out) {
    FILE* p = popen(cmd, "r");
    if (!p) return -1;
    std::string s; char buf[512]; size_t r;
    while ((r = fread(buf, 1, sizeof(buf), p)) > 0) s.append(buf, r);
    const int rc = pclose(p);
    if (out) *out = std::move(s);
    return rc;
}

// 把 text 交给命令 stdin（用于写剪贴板）
int popenWrite(const char* cmd, const std::string& text) {
    FILE* p = popen(cmd, "w");
    if (!p) return -1;
    if (!text.empty()) fwrite(text.data(), 1, text.size(), p);
    return pclose(p);
}

// 协议里的 vk 是 Windows VK 码（与 Win/mac 两版同源，见 ctrl9511.hpp 0x28 注释），
// 这里先映射到 HID usage（页 0x07），再交给同一张 hidToLinuxKey 表落地。
uint8_t vkToHidUsage(uint8_t vk) {
    if (vk >= 0x41 && vk <= 0x5A) return static_cast<uint8_t>(0x04 + (vk - 0x41));  // A..Z
    if (vk >= 0x30 && vk <= 0x39) {                                                 // 0..9
        return vk == 0x30 ? 0x27 : static_cast<uint8_t>(0x1E + (vk - 0x31));
    }
    if (vk >= 0x70 && vk <= 0x7B) return static_cast<uint8_t>(0x3A + (vk - 0x70));  // F1..F12
    switch (vk) {
        case 0x08: return 0x2A;  // Backspace
        case 0x09: return 0x2B;  // Tab
        case 0x0D: return 0x28;  // Enter
        case 0x1B: return 0x29;  // Esc
        case 0x20: return 0x2C;  // Space
        case 0x21: return 0x4B;  // PageUp
        case 0x22: return 0x4E;  // PageDown
        case 0x23: return 0x4D;  // End
        case 0x24: return 0x4A;  // Home
        case 0x25: return 0x50;  // Left
        case 0x26: return 0x52;  // Up
        case 0x27: return 0x4F;  // Right
        case 0x28: return 0x51;  // Down
        case 0x2E: return 0x4C;  // Delete
        default: return 0;
    }
}

// ASCII → HID usage + 是否 Shift（US 布局）。uinput 是内核级注入，没有 X11/Wayland 的
// Unicode 通道可走，只能按布局表逐键敲；映射不到就返回 false（调用方转走剪贴板或如实失败）。
bool asciiToHid(char c, uint8_t* usage, bool* shift) {
    *shift = false;
    if (c >= 'a' && c <= 'z') { *usage = static_cast<uint8_t>(0x04 + (c - 'a')); return true; }
    if (c >= 'A' && c <= 'Z') { *usage = static_cast<uint8_t>(0x04 + (c - 'A')); *shift = true; return true; }
    if (c >= '1' && c <= '9') { *usage = static_cast<uint8_t>(0x1E + (c - '1')); return true; }
    if (c == '0') { *usage = 0x27; return true; }
    switch (c) {
        case ' ':  *usage = 0x2C; return true;
        case '\n': *usage = 0x28; return true;
        case '\t': *usage = 0x2B; return true;
        case '-':  *usage = 0x2D; return true;
        case '=':  *usage = 0x2E; return true;
        case '[':  *usage = 0x2F; return true;
        case ']':  *usage = 0x30; return true;
        case '\\': *usage = 0x31; return true;
        case ';':  *usage = 0x33; return true;
        case '\'': *usage = 0x34; return true;
        case '`':  *usage = 0x35; return true;
        case ',':  *usage = 0x36; return true;
        case '.':  *usage = 0x37; return true;
        case '/':  *usage = 0x38; return true;
        // 上档符号（同一物理键 + Shift，位序与 US 布局一致）
        case '!':  *usage = 0x1E; *shift = true; return true;
        case '@':  *usage = 0x1F; *shift = true; return true;
        case '#':  *usage = 0x20; *shift = true; return true;
        case '$':  *usage = 0x21; *shift = true; return true;
        case '%':  *usage = 0x22; *shift = true; return true;
        case '^':  *usage = 0x23; *shift = true; return true;
        case '&':  *usage = 0x24; *shift = true; return true;
        case '*':  *usage = 0x25; *shift = true; return true;
        case '(':  *usage = 0x26; *shift = true; return true;
        case ')':  *usage = 0x27; *shift = true; return true;
        case '_':  *usage = 0x2D; *shift = true; return true;
        case '+':  *usage = 0x2E; *shift = true; return true;
        case '{':  *usage = 0x2F; *shift = true; return true;
        case '}':  *usage = 0x30; *shift = true; return true;
        case '|':  *usage = 0x31; *shift = true; return true;
        case ':':  *usage = 0x33; *shift = true; return true;
        case '"':  *usage = 0x34; *shift = true; return true;
        case '~':  *usage = 0x35; *shift = true; return true;
        case '<':  *usage = 0x36; *shift = true; return true;
        case '>':  *usage = 0x37; *shift = true; return true;
        case '?':  *usage = 0x38; *shift = true; return true;
        default: return false;
    }
}

}  // namespace

std::unique_ptr<InputInjector> createPlatformInjector() {
    auto in = std::make_unique<LinInjector>();
    return in->ok() ? std::unique_ptr<InputInjector>(in.release()) : nullptr;
}

/* Linux 没有「辅助功能授权」这一层：注入走 /dev/uinput（内核级），设备节点可写就能注入，
   不存在「连上了、帧也到了、注入被系统吞掉」的静默失败模式。所以恒 true。 */
bool platformInputTrusted() { return true; }
bool requestPlatformInputTrust() { return true; }
std::string inputTrustSettingsLabel() { return "辅助功能"; }
bool openPlatformInputSettings() { return false; }
bool runningFromAppBundle() { return false; }
std::unique_ptr<ClipboardWatcher> createPlatformClipboardWatcher(
    std::function<void(const std::string&)> cb) {
    return std::make_unique<LinClipboardWatcher>(std::move(cb));
}

// ============================================================================
// 自由函数（与 input_injector_win.cpp / input_injector_mac.mm 对等）
// Linux 没有跨会话的统一输入 API，剪贴板走 xclip / wl-clipboard（最佳努力），
// 键盘走 /dev/uinput（内核级，需对设备有写权限，通常属 input 组）。
// ============================================================================

// 反向剪贴板（0x21）：写本机剪贴板；写完打抑制时间戳，watcher 600ms 内不回传（防回环）
bool setSystemClipboard(const std::string& text) {
    g_clipSuppressMs.store(steadyMsNow());
    if (popenWrite("xclip -selection clipboard -in 2>/dev/null", text) == 0) return true;
    if (popenWrite("wl-copy 2>/dev/null", text) == 0) return true;
    return false;   // 两个工具都没有（或没会话）—— 如实返回 false，不假装成功
}

// 读本机剪贴板（「把电脑上复制的内容主动发给手机」这条方向）
std::string readSystemClipboard() {
    std::string out;
    if (popenRead("xclip -selection clipboard -o 2>/dev/null", &out) == 0) return out;
    if (popenRead("wl-paste --no-newline 2>/dev/null", &out) == 0) return out;
    return {};
}

// 特殊键 / 组合键（0x28）：mod 位图 1=Ctrl 2=Shift 4=Alt（与 Win/mac 同源），vk = Windows VK 码
bool injectSpecialKey(uint8_t mod, uint8_t vk) {
    LinInjector* inj = sharedInjector();
    if (!inj) return false;
    const uint8_t usage = vkToHidUsage(vk);
    if (!usage || !hidToLinuxKey(usage)) return false;   // 映射不到就不注入，别乱敲
    const uint8_t hidMod = static_cast<uint8_t>(mod & 0x07);   // HID 修饰位与协议位序一致
    std::lock_guard<std::mutex> lk(g_sharedInjectorMutex);
    inj->injectKeyboard(hidMod, &usage, 1);
    inj->injectKeyboard(0, nullptr, 0);
    return true;
}

// 文本注入当前光标处。策略（与 Win/mac 一致，理由同）：纯 ASCII 短文本逐键键入，
// 不碰剪贴板；含非 ASCII / 换行 / 长文本改「写剪贴板 + Ctrl+V」，用完把用户原来的
// 剪贴板还回去 —— 不还原等于顺手毁掉用户复制的东西，最招骂。
bool injectSystemText(const std::string& text) {
    if (text.empty()) return false;
    bool ascii = true;
    for (unsigned char c : text) if (c >= 0x80) { ascii = false; break; }
    const bool simple = ascii && text.size() <= 64 &&
                        text.find('\n') == std::string::npos &&
                        text.find('\r') == std::string::npos &&
                        text.find('\t') == std::string::npos;
    LinInjector* inj = sharedInjector();
    if (inj && simple) {
        std::lock_guard<std::mutex> lk(g_sharedInjectorMutex);
        for (char c : text) {
            uint8_t usage = 0; bool shift = false;
            if (!asciiToHid(c, &usage, &shift)) return false;   // 映射不到就停（已敲的不回滚）
            const uint8_t mod = shift ? 0x02 : 0x00;
            inj->injectKeyboard(mod, &usage, 1);
            inj->injectKeyboard(0, nullptr, 0);
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        return true;
    }
    const std::string backup = readSystemClipboard();
    if (!setSystemClipboard(text)) return false;
    if (!injectSpecialKey(0x01, 'V')) { setSystemClipboard(backup); return false; }
    std::this_thread::sleep_for(std::chrono::milliseconds(80));
    setSystemClipboard(backup);
    return true;
}

}  // namespace apxpc::wireless

#endif  // __linux__
