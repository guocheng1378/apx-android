#include "apxpc/wireless/wireless_link.hpp"

#include "apxpc/log.hpp"

#include <apx/frame.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <vector>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#endif

namespace apxpc::wireless {
namespace {

using Clock = std::chrono::steady_clock;

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               Clock::now().time_since_epoch())
        .count();
}

#if defined(_WIN32)
// ---------------------------------------------------------------- 注入 ----
// 免驱动：全部走 Windows 标准 SendInput（架构 §2.2 无线模式零新驱动）。

/// 控制面子命令（与 android/.../core/TcpCtrlBridge.kt 一一对应）
enum : uint8_t {
    kCmdMouse    = 0x01,
    kCmdConsumer = 0x02,
    kCmdKeyboard = 0x03,
    kCmdTouch    = 0x04,   // 副屏触摸（绝对坐标），见 Android TcpCtrlBridge.touch
};

/// HID Keyboard Page (0x07) usage → Windows 虚拟键码；无对应返回 0（如实跳过）
int usageToVk(uint8_t u) {
    if (u >= 0x04 && u <= 0x1D) return 'A' + (u - 0x04);    // A..Z
    if (u >= 0x1E && u <= 0x26) return '1' + (u - 0x1E);    // 1..9
    if (u >= 0x3A && u <= 0x45) return VK_F1 + (u - 0x3A);  // F1..F12
    if (u >= 0x59 && u <= 0x61) return VK_NUMPAD1 + (u - 0x59);  // 小键盘 1..9
    switch (u) {
        case 0x27: return '0';
        case 0x28: return VK_RETURN;
        case 0x29: return VK_ESCAPE;
        case 0x2A: return VK_BACK;
        case 0x2B: return VK_TAB;
        case 0x2C: return VK_SPACE;
        case 0x2D: return VK_OEM_MINUS;
        case 0x2E: return VK_OEM_PLUS;
        case 0x2F: return VK_OEM_4;      // [
        case 0x30: return VK_OEM_6;      // ]
        case 0x31: return VK_OEM_5;      // backslash
        case 0x32: return 0;             // 非美式 ISO 额外键：无对应，跳过
        case 0x33: return VK_OEM_1;      // ;
        case 0x34: return VK_OEM_7;      // '
        case 0x35: return VK_OEM_3;      // `
        case 0x36: return VK_OEM_COMMA;
        case 0x37: return VK_OEM_PERIOD;
        case 0x38: return VK_OEM_2;      // /
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
        case 0x58: return VK_RETURN;     // 小键盘回车
        case 0x62: return VK_NUMPAD0;
        case 0x63: return VK_DECIMAL;
        default:   return 0;
    }
}

/// 修饰位图 bit → VK（位序 per HID Keyboard modifier byte）
int modBitToVk(int bit) {
    switch (bit) {
        case 0: return VK_LCONTROL;
        case 1: return VK_LSHIFT;
        case 2: return VK_LMENU;
        case 3: return VK_LWIN;
        case 4: return VK_RCONTROL;
        case 5: return VK_RSHIFT;
        case 6: return VK_RMENU;
        case 7: return VK_RWIN;
        default: return 0;
    }
}

/// 需要 KEYEVENTF_EXTENDEDKEY 的键（右侧修饰键、方向键、导航簇、小键盘除号等）
bool isExtendedVk(int vk) {
    switch (vk) {
        case VK_RCONTROL: case VK_RMENU: case VK_INSERT: case VK_DELETE:
        case VK_HOME: case VK_END: case VK_PRIOR: case VK_NEXT:
        case VK_LEFT: case VK_RIGHT: case VK_UP: case VK_DOWN:
        case VK_NUMLOCK: case VK_DIVIDE: case VK_LWIN: case VK_RWIN: case VK_APPS:
            return true;
        default:
            return false;
    }
}

/// @return 是否真的发出了事件（vk==0 表示该键无对应，未发出）
bool sendVk(int vk, bool down) {
    if (vk == 0) return false;
    INPUT in{};
    in.type = INPUT_KEYBOARD;
    in.ki.wVk = static_cast<WORD>(vk);
    in.ki.dwFlags = (down ? 0 : KEYEVENTF_KEYUP) |
                    (isExtendedVk(vk) ? KEYEVENTF_EXTENDEDKEY : 0);
    return ::SendInput(1, &in, sizeof(INPUT)) == 1;
}

/// Consumer 位 bit → VK（位序 per shared/include/apx/hid_layout.h ConsumerKeyBit）
int consumerBitToVk(uint8_t bit) {
    switch (bit) {
        case 0: return VK_VOLUME_UP;
        case 1: return VK_VOLUME_DOWN;
        case 2: return VK_VOLUME_MUTE;
        case 4: return VK_MEDIA_PLAY_PAUSE;
        case 5: return VK_MEDIA_PREV_TRACK;
        case 6: return VK_MEDIA_NEXT_TRACK;
        default: return 0;   // bit3 Power：无标准 VK，跳过（不伪装）
    }
}
#endif  // _WIN32

// 进程级 WSAStartup（只做一次）
void ensureWsa() {
#if defined(_WIN32)
    static bool once = [] {
        WSADATA d{};
        WSAStartup(MAKEWORD(2, 2), &d);
        return true;
    }();
    (void)once;
#endif
}

}  // namespace

// ---------------------------------------------------------------- 建链 ----

bool WirelessLink::connect(const std::string& host, uint16_t port,
                           const std::string& token) {
    // 重连前先收掉旧连接：必须在**取锁之前**调用 —— disconnect() 自己要锁 mu_，
    // 持锁调用会直接死锁（std::mutex 不可重入）。
    if (running_.load()) disconnect();

    std::lock_guard<std::mutex> lk(mu_);

#if !defined(_WIN32)
    st_.connected = false;
    st_.error = "Wi‑Fi 控制通道目前仅实现 Windows 宿主";
    return false;
#else
    ensureWsa();
    token_ = token;
    peer_ = host + ":" + std::to_string(port);

    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    char portStr[8]{};
    std::snprintf(portStr, sizeof(portStr), "%u", static_cast<unsigned>(port));
    if (getaddrinfo(host.c_str(), portStr, &hints, &res) != 0 || !res) {
        st_.connected = false;
        st_.error = "地址解析失败：" + host;
        APX_LOGW("Wi‑Fi 连接失败：{}", st_.error);
        return false;
    }

    SOCKET s = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (s == INVALID_SOCKET) {
        freeaddrinfo(res);
        st_.connected = false;
        st_.error = "socket 创建失败";
        return false;
    }

    // 非阻塞 connect + select 3s 超时
    u_long nb = 1;
    ioctlsocket(s, FIONBIO, &nb);
    const int rc = ::connect(s, res->ai_addr, static_cast<int>(res->ai_addrlen));
    freeaddrinfo(res);
    if (rc == SOCKET_ERROR) {
        fd_set w;
        FD_ZERO(&w);
        FD_SET(s, &w);
        timeval tv{3, 0};
        if (select(0, nullptr, &w, nullptr, &tv) <= 0) {
            closesocket(s);
            st_.connected = false;
            st_.error = "连接超时（3s）：" + peer_;
            APX_LOGW("Wi‑Fi 连接失败：{}", st_.error);
            return false;
        }
    }
    nb = 0;
    ioctlsocket(s, FIONBIO, &nb);

    // 令牌握手：u32 LE 长度 + UTF-8 令牌。
    // **没有回执字节** —— 服务端令牌不匹配时直接关连接，本端由首次 recv==0 得知。
    // （早先版本用 1 字节回执，会与紧随其后的帧首字节混淆，已弃用。）
    const uint32_t len = static_cast<uint32_t>(token_.size());
    const uint8_t hdr[4] = {
        static_cast<uint8_t>(len & 0xFF),
        static_cast<uint8_t>((len >> 8) & 0xFF),
        static_cast<uint8_t>((len >> 16) & 0xFF),
        static_cast<uint8_t>((len >> 24) & 0xFF),
    };
    if (::send(s, reinterpret_cast<const char*>(hdr), 4, 0) != 4 ||
        (len > 0 &&
         ::send(s, token_.data(), static_cast<int>(len), 0) != static_cast<int>(len))) {
        closesocket(s);
        st_.connected = false;
        st_.error = "令牌握手发送失败";
        return false;
    }

    sock_ = s;
    upSinceMs_ = nowMs();
    running_.store(true);
    st_.connected = true;
    st_.peer = peer_;
    st_.rttMs = -1;
    st_.error.clear();
    st_.upMs = 0;

    mouseButtons_ = 0;
    consumerBm_ = 0;
    kbMod_ = 0;
    std::memset(kbKeys_, 0, sizeof(kbKeys_));

    thread_ = std::thread(&WirelessLink::keepaliveLoop, this);
    APX_LOGI("Wi‑Fi 控制通道已连接：{}", peer_);
    return true;
#endif
}

void WirelessLink::disconnect() {
    if (!running_.exchange(false)) return;
#if defined(_WIN32)
    SOCKET s;
    {
        std::lock_guard<std::mutex> lk(mu_);
        s = sock_;
        sock_ = INVALID_SOCKET;
    }
    if (s != INVALID_SOCKET) {
        shutdown(s, SD_BOTH);
        closesocket(s);   // 令阻塞中的 recv/send 立刻返回
    }
#endif
    if (thread_.joinable()) thread_.join();
    std::lock_guard<std::mutex> lk(mu_);
    st_.connected = false;
    APX_LOGI("Wi‑Fi 控制通道已断开");
}

WirelessLink::Status WirelessLink::status() const {
    std::lock_guard<std::mutex> lk(mu_);
    Status s = st_;
    if (s.connected) s.upMs = nowMs() - upSinceMs_;
    return s;
}

WirelessLink::Counters WirelessLink::counters() const {
    Counters c{};
    c.mouse = cMouse_.load();
    c.consumer = cConsumer_.load();
    c.keyboard = cKeyboard_.load();
    c.touch = cTouch_.load();
    c.pong = cPong_.load();
    c.dropped = cDropped_.load();
    return c;
}

// ---------------------------------------------------------------- 注入 ----

void WirelessLink::injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel) {
#if defined(_WIN32)
    std::vector<INPUT> in;
    if (dx != 0 || dy != 0) {
        INPUT m{};
        m.type = INPUT_MOUSE;
        m.mi.dwFlags = MOUSEEVENTF_MOVE;
        m.mi.dx = dx;
        m.mi.dy = dy;
        in.push_back(m);
    }
    if (wheel != 0) {
        INPUT w{};
        w.type = INPUT_MOUSE;
        w.mi.dwFlags = MOUSEEVENTF_WHEEL;
        w.mi.mouseData = static_cast<DWORD>(static_cast<int>(wheel) * WHEEL_DELTA);
        in.push_back(w);
    }
    // 按键按**边沿**注入：手机端每帧发全量按钮位，这里只发变化的那一位
    const struct { uint8_t bit; DWORD down; DWORD up; } table[] = {
        {0x01, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP},
        {0x02, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP},
        {0x04, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP},
    };
    for (const auto& t : table) {
        const bool was = (mouseButtons_ & t.bit) != 0;
        const bool now = (buttons & t.bit) != 0;
        if (was == now) continue;
        INPUT b{};
        b.type = INPUT_MOUSE;
        b.mi.dwFlags = now ? t.down : t.up;
        in.push_back(b);
    }
    mouseButtons_ = buttons;
    if (!in.empty()) {
        ::SendInput(static_cast<UINT>(in.size()), in.data(), sizeof(INPUT));
    }
#else
    (void)buttons; (void)dx; (void)dy; (void)wheel;
#endif
}

/// 副屏触摸：绝对坐标（归一化 0..65535）注入。
/// 扩展屏模式（setTouchRect 设置过目标矩形）：映射到**虚拟屏在虚拟桌面中的位置**
/// （MOUSEEVENTF_VIRTUALDESK）；未设置 = 旧行为，映射主显示器。
/// action：0=down 1=up 2=move 3=cancel；down/up 的按键由 buttons 决定（双指=右键）。
void WirelessLink::injectTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) {
#if defined(_WIN32)
    constexpr uint8_t kDown = 0, kUp = 1, kCancel = 3;
    DWORD click = 0;
    if (action == kDown) {
        click = (buttons & 0x02) ? MOUSEEVENTF_RIGHTDOWN
              : (buttons & 0x04) ? MOUSEEVENTF_MIDDLEDOWN
                                 : MOUSEEVENTF_LEFTDOWN;
    } else if (action == kUp || action == kCancel) {
        // 取消也按释放处理：绝不能把键留在按下状态
        click = (buttons & 0x02) ? MOUSEEVENTF_RIGHTUP
              : (buttons & 0x04) ? MOUSEEVENTF_MIDDLEUP
                                 : MOUSEEVENTF_LEFTUP;
    }

    LONG dx, dy;
    DWORD extra = 0;
    {
        std::lock_guard<std::mutex> lk(touchRectMu_);
        if (trValid_) {
            // 虚拟桌面绝对坐标：目标像素 = 矩形原点 + 归一化 × 尺寸，
            // 再转成虚拟桌面 0..65535 归一化（VIRTUALDESK 语义）。
            const LONG vx = ::GetSystemMetrics(SM_XVIRTUALSCREEN);
            const LONG vy = ::GetSystemMetrics(SM_YVIRTUALSCREEN);
            const LONG vw = ::GetSystemMetrics(SM_CXVIRTUALSCREEN);
            const LONG vh = ::GetSystemMetrics(SM_CYVIRTUALSCREEN);
            if (vw <= 0 || vh <= 0) return;
            const LONG px = trX_ + (static_cast<LONG>(x) * trW_) / 65535;
            const LONG py = trY_ + (static_cast<LONG>(y) * trH_) / 65535;
            dx = ((px - vx) * 65535L) / vw;
            dy = ((py - vy) * 65535L) / vh;
            extra = MOUSEEVENTF_VIRTUALDESK;
        } else {
            dx = static_cast<LONG>(x);
            dy = static_cast<LONG>(y);
        }
    }

    INPUT m{};
    m.type = INPUT_MOUSE;
    m.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | extra | click;
    m.mi.dx = dx;
    m.mi.dy = dy;
    ::SendInput(1, &m, sizeof(INPUT));
#else
    (void)action; (void)buttons; (void)x; (void)y;
#endif
}

void WirelessLink::injectConsumer(uint16_t bitmap) {
#if defined(_WIN32)
    const uint16_t pressed = static_cast<uint16_t>(bitmap & ~consumerBm_);
    const uint16_t released = static_cast<uint16_t>(consumerBm_ & ~bitmap);
    int sent = 0;
    for (uint8_t bit = 0; bit < 8; ++bit) {
        const uint16_t mask = static_cast<uint16_t>(1u << bit);
        if (pressed & mask) sent += sendVk(consumerBitToVk(bit), true) ? 1 : 0;
        if (released & mask) sent += sendVk(consumerBitToVk(bit), false) ? 1 : 0;
    }
    consumerBm_ = bitmap;
    if (sent == 0 && (pressed || released)) cDropped_.fetch_add(1);
#else
    (void)bitmap;
#endif
}

void WirelessLink::injectKeyboard(uint8_t mod, const uint8_t* keys, size_t count) {
#if defined(_WIN32)
    const size_t n = count > 6 ? 6 : count;

    // 1) 修饰键边沿
    for (int bit = 0; bit < 8; ++bit) {
        const uint8_t mask = static_cast<uint8_t>(1u << bit);
        const bool was = (kbMod_ & mask) != 0;
        const bool now = (mod & mask) != 0;
        if (was != now) sendVk(modBitToVk(bit), now);
    }

    // 2) 普通键边沿：上一状态有、本次没有 → up；本次新出现 → down
    for (uint8_t i = 0; i < 6; ++i) {
        const uint8_t k = kbKeys_[i];
        if (k == 0) continue;
        bool still = false;
        for (size_t j = 0; j < n; ++j) {
            if (keys[j] == k) { still = true; break; }
        }
        if (!still) sendVk(usageToVk(k), false);
    }
    for (size_t j = 0; j < n; ++j) {
        const uint8_t k = keys[j];
        if (k == 0) continue;
        bool existed = false;
        for (uint8_t i = 0; i < 6; ++i) {
            if (kbKeys_[i] == k) { existed = true; break; }
        }
        if (!existed) sendVk(usageToVk(k), true);
    }

    kbMod_ = mod;
    for (uint8_t i = 0; i < 6; ++i) kbKeys_[i] = (i < n) ? keys[i] : 0;
#else
    (void)mod; (void)keys; (void)count;
#endif
}

// ---------------------------------------------------------------- 保活 ----

bool WirelessLink::sendAll(const uint8_t* p, size_t n) {
#if defined(_WIN32)
    size_t off = 0;
    while (off < n) {
        const int w = ::send(sock_, reinterpret_cast<const char*>(p + off),
                             static_cast<int>(n - off), 0);
        if (w <= 0) return false;
        off += static_cast<size_t>(w);
    }
    return true;
#else
    (void)p; (void)n;
    return false;
#endif
}

bool WirelessLink::buildPing(uint8_t* buf, size_t cap, size_t& len, uint32_t seq) {
    apx::ApxFrameHeader h{};
    std::memcpy(h.magic, "APX1", 4);
    h.streamId = apx::kStreamControl;
    h.flags = 0;
    h.headerExtWords = 0;
    h.payloadLen = 8;   // body 4 + CRC 4
    h.seq = seq;
    if (cap < apx::kFrameHeaderSize + h.payloadLen) return false;
    if (!apx::writeHeader(buf, cap, h)) return false;
    uint8_t body[4] = {'p', 'i', 'n', 'g'};
    std::memcpy(buf + apx::kFrameHeaderSize, body, 4);
    apx::putU32(buf + apx::kFrameHeaderSize + 4, apx::crc32(body, 4));
    len = apx::kFrameHeaderSize + h.payloadLen;
    return true;
}

bool WirelessLink::buildCmd(uint8_t* buf, size_t cap, size_t& len, uint8_t cmd, uint32_t seq) {
    apx::ApxFrameHeader h{};
    std::memcpy(h.magic, "APX1", 4);
    h.streamId = apx::kStreamControl;
    h.flags = 0;
    h.headerExtWords = 0;
    h.payloadLen = 1 + apx::kFrameCrcSize;   // body=[cmd] + CRC
    h.seq = seq;
    if (cap < apx::kFrameHeaderSize + h.payloadLen) return false;
    if (!apx::writeHeader(buf, cap, h)) return false;
    buf[apx::kFrameHeaderSize] = cmd;
    apx::putU32(buf + apx::kFrameHeaderSize + 1, apx::crc32(&cmd, 1));
    len = apx::kFrameHeaderSize + h.payloadLen;
    return true;
}

/// 带两字节参数的命令帧：body=[cmd, a, b] + CRC（如 0x10 模块开关）
bool WirelessLink::buildCmd2(uint8_t* buf, size_t cap, size_t& len, uint8_t cmd,
                             uint8_t a, uint8_t b, uint32_t seq) {
    apx::ApxFrameHeader h{};
    std::memcpy(h.magic, "APX1", 4);
    h.streamId = apx::kStreamControl;
    h.flags = 0;
    h.headerExtWords = 0;
    h.payloadLen = 3 + apx::kFrameCrcSize;
    h.seq = seq;
    if (cap < apx::kFrameHeaderSize + h.payloadLen) return false;
    if (!apx::writeHeader(buf, cap, h)) return false;
    uint8_t body[3] = {cmd, a, b};
    std::memcpy(buf + apx::kFrameHeaderSize, body, 3);
    apx::putU32(buf + apx::kFrameHeaderSize + 3, apx::crc32(body, 3));
    len = apx::kFrameHeaderSize + h.payloadLen;
    return true;
}

void WirelessLink::keepaliveLoop() {
    uint32_t seq = 1;
    int64_t lastPingAt = 0;
    int64_t pingSentAt = 0;

    std::vector<uint8_t> acc;
    acc.reserve(4096);
    uint8_t rx[2048];

#if defined(_WIN32)
    const DWORD rcvtmo = 500;   // 500ms 读超时：既不阻塞心跳，也不忙轮询
#endif

    while (running_.load()) {
#if !defined(_WIN32)
        break;
#else
        SOCKET s;
        {
            std::lock_guard<std::mutex> lk(mu_);
            s = sock_;
        }
        if (s == INVALID_SOCKET) break;

        // 1) 心跳：每 1s 一条 control ping
        const int64_t now = nowMs();
        if (now - lastPingAt >= 1000) {
            uint8_t buf[64];
            size_t len = 0;
            if (buildPing(buf, sizeof(buf), len, seq++)) {
                if (!sendAll(buf, len)) {
                    APX_LOGW("Wi‑Fi 心跳发送失败，视为链路断开");
                    break;
                }
                pingSentAt = now;
            }
            lastPingAt = now;
        }

        // 1.5) 待发命令（PC → 手机）：打开副屏等控制命令，紧随心跳之后送出
        if (pendingCmd_.exchange(0) & 1) {
            uint8_t buf[64];
            size_t len = 0;
            if (buildCmd(buf, sizeof(buf), len, 0x05, seq++)) {
                if (!sendAll(buf, len)) {
                    APX_LOGW("打开副屏命令发送失败，视为链路断开");
                    break;
                }
            }
        }

        // 1.6) 模块开关命令（0x10，PC → 手机）：位图 bit(idx*2+on)，一次发一条
        if (const int mc = pendingModCmd_.exchange(0)) {
            for (int idx = 0; idx < 8; ++idx) {
                const int mask = 1 << (idx * 2);
                const int maskOff = mask << 1;
                uint8_t on;
                if (mc & mask) on = 1;
                else if (mc & maskOff) on = 0;
                else continue;
                uint8_t buf[64];
                size_t len = 0;
                if (buildCmd2(buf, sizeof(buf), len, 0x10,
                              static_cast<uint8_t>(idx), on, seq++)) {
                    if (!sendAll(buf, len)) {
                        APX_LOGW("模块命令发送失败，视为链路断开");
                        break;
                    }
                }
            }
        }

        // 2) 读（500ms 超时）
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO,
                   reinterpret_cast<const char*>(&rcvtmo), sizeof(rcvtmo));
        const int n = ::recv(s, reinterpret_cast<char*>(rx), sizeof(rx), 0);
        if (n == 0) {
            APX_LOGW("Wi‑Fi 对端关闭连接");
            break;
        }
        if (n > 0) acc.insert(acc.end(), rx, rx + n);

        // 3) 从累积缓冲里剥离完整 APX 帧
        size_t off = 0;
        while (acc.size() - off >= apx::kFrameHeaderSize) {
            apx::ApxFrameHeader h{};
            if (!apx::readHeader(acc.data() + off, acc.size() - off, h)) {
                off = acc.size();   // 失步：整体丢弃重新对齐
                cDropped_.fetch_add(1);
                break;
            }
            const size_t total = apx::frameTotalSize(h);
            if (acc.size() - off < total) break;   // 半帧，等下次
            const uint8_t* payload = acc.data() + off + apx::kFrameHeaderSize;
            const uint32_t bodyLen =
                h.payloadLen >= apx::kFrameCrcSize ? h.payloadLen - apx::kFrameCrcSize : 0;

            if (h.streamId == apx::kStreamControl && bodyLen >= 1) {
                const uint8_t cmd = payload[0];
                if (cmd == 'p') {
                    // 心跳回显：算 RTT
                    if (pingSentAt > 0) {
                        std::lock_guard<std::mutex> lk(mu_);
                        st_.rttMs = static_cast<double>(nowMs() - pingSentAt);
                    }
                    cPong_.fetch_add(1);
                } else if (cmd == 'a' && bodyLen >= 8) {
                    // 手机侧 Wi‑Fi 音频模块状态上报（tag 'a'）：让 PC 也能看到手机播放端
                    std::lock_guard<std::mutex> lk(mu_);
                    st_.phoneAudioKnown = true;
                    st_.phoneAudioState = static_cast<int>(payload[1]);
                    st_.phoneAudioSpk = (payload[2] != 0);
                    st_.phoneAudioMic = (payload[3] != 0);
                    st_.phoneAudioDropped =
                        static_cast<uint32_t>(payload[4]) |
                        (static_cast<uint32_t>(payload[5]) << 8) |
                        (static_cast<uint32_t>(payload[6]) << 16) |
                        (static_cast<uint32_t>(payload[7]) << 24);
                } else if (cmd == 'M' && bodyLen >= 2) {
                    // 手机端全模块状态帧（tag 'M'）：[0]='M' [1]=count {idx,state}×n
                    // 面板据此显示手机端各功能的真实开关状态（两端状态同步的数据源）
                    const int count = payload[1];
                    for (int i = 0; i < count && 2 + i * 2 + 1 < bodyLen; ++i) {
                        const uint8_t idx = payload[2 + i * 2];
                        if (idx < 8) phoneStates_[idx].store(payload[3 + i * 2]);
                    }
                    modStateVersion_.fetch_add(1);
                } else if (cmd == kCmdMouse && bodyLen >= 5) {
                    injectMouse(payload[1],
                                static_cast<int8_t>(payload[2]),
                                static_cast<int8_t>(payload[3]),
                                static_cast<int8_t>(payload[4]));
                    cMouse_.fetch_add(1);
                } else if (cmd == kCmdConsumer && bodyLen >= 3) {
                    injectConsumer(static_cast<uint16_t>(
                        static_cast<uint16_t>(payload[1]) |
                        (static_cast<uint16_t>(payload[2]) << 8)));
                    cConsumer_.fetch_add(1);
                } else if (cmd == kCmdKeyboard && bodyLen >= 3) {
                    injectKeyboard(payload[1], payload + 3, bodyLen - 3);
                    cKeyboard_.fetch_add(1);
                } else if (cmd == 0x06) {
                    // 手机副屏页 onResume：请求下一编码帧为 IDR（切回秒出画）
                    keyFrameReq_.store(true);
                } else if (cmd == kCmdTouch && bodyLen >= 9) {
                    injectTouch(payload[1], payload[2],
                                apx::getU16(payload + 3), apx::getU16(payload + 5));
                    cTouch_.fetch_add(1);
                } else {
                    cDropped_.fetch_add(1);
                }
            } else {
                cDropped_.fetch_add(1);
            }
            off += total;
        }
        if (off > 0) acc.erase(acc.begin(), acc.begin() + static_cast<long>(off));
#endif
    }

    std::lock_guard<std::mutex> lk(mu_);
    st_.connected = false;
}

}  // namespace apxpc::wireless
