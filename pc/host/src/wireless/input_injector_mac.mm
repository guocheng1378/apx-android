// ============================================================================
// macOS 输入注入 + 剪贴板（9511 受控端）
// 注入走 CoreGraphics CGEvent（C API，免驱）；剪贴板走 NSPasteboard（Objective-C）。
// 编译为 Objective-C++（.mm）。多媒体键在 macOS 需 NXSystemDefined 事件，本实现暂忽略。
// ============================================================================
#include "apxpc/wireless/ctrl9511.hpp"
// 相对位移 → 目标坐标 / 拖动类型：纯算术抽出来给用例盯着（issue #47 第 6 条）。
#include "apxpc/wireless/relative_move.hpp"
// 手柄降级的算术（边沿/死区/缩放/滚轮限速）：与 Windows 共用，用例在 host 跑（第 5 条）。
#include "apxpc/wireless/gamepad_fallback.hpp"
// 权限自检的提示走 APX_LOG 落盘 —— .app 双击启动时 stderr 不可见（见 ensureTrustedOnce）。
#include "apxpc/log.hpp"
// 授权身份判断用 exe 路径（.app 内 vs 裸二进制）。
#include "apxpc/autostart.hpp"

#if defined(__APPLE__)

#include <CoreGraphics/CoreGraphics.h>
#include <ApplicationServices/ApplicationServices.h>   // AXIsProcessTrustedWithOptions（授权面板兜底）
#import <AppKit/AppKit.h>

#include <string>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <vector>

namespace apxpc::wireless {
namespace {

// HID usage (0x07) → macOS 虚拟键码（CGKeyCode）。仅覆盖常用键。
int hidToMacKey(uint8_t u) {
    static const int A = 0x00, B = 0x0B, C = 0x08, D = 0x02, E = 0x0E, F = 0x03, G = 0x05,
                     H = 0x04, I = 0x22, J = 0x26, K = 0x28, L = 0x25, M = 0x2E, N = 0x2D,
                     O = 0x1F, P = 0x23, Q = 0x0C, R = 0x0F, S = 0x01, T = 0x11, U = 0x20,
                     V = 0x09, W = 0x0D, X = 0x07, Y = 0x10, Z = 0x06;
    switch (u) {
        case 0x04: return A; case 0x05: return B; case 0x06: return C; case 0x07: return D;
        case 0x08: return E; case 0x09: return F; case 0x0A: return G; case 0x0B: return H;
        case 0x0C: return I; case 0x0D: return J; case 0x0E: return K; case 0x0F: return L;
        case 0x10: return M; case 0x11: return N; case 0x12: return O; case 0x13: return P;
        case 0x14: return Q; case 0x15: return R; case 0x16: return S; case 0x17: return T;
        case 0x18: return U; case 0x19: return V; case 0x1A: return W; case 0x1B: return X;
        case 0x1C: return Y; case 0x1D: return Z;
        case 0x1E: return 0x12; case 0x1F: return 0x13; case 0x20: return 0x14; case 0x21: return 0x15;
        case 0x22: return 0x17; case 0x23: return 0x16; case 0x24: return 0x1A; case 0x25: return 0x1C;
        case 0x26: return 0x19; case 0x27: return 0x1D;
        case 0x28: return 0x24;   // Enter
        case 0x29: return 0x35;   // Esc
        case 0x2A: return 0x33;   // Backspace
        case 0x2B: return 0x30;   // Tab
        case 0x2C: return 0x31;   // Space
        case 0x4F: return 0x7C;   // Right（kVK_RightArrow；此前写成 0x7B=Left，issue #52「左右反」）
        case 0x50: return 0x7B;   // Left（kVK_LeftArrow）
        case 0x51: return 0x7D;   // Down
        case 0x52: return 0x7E;   // Up
        case 0x4C: return 0x75;   // Delete (forward)
        // —— 标点/符号行（此前全部落 default → -1 被静默丢弃：issue #52「标点键无反应」）——
        case 0x2D: return 0x1B;   // -
        case 0x2E: return 0x18;   // =
        case 0x2F: return 0x21;   // [
        case 0x30: return 0x1E;   // ]
        case 0x31: return 0x2A;   // \（backslash）
        case 0x32: return 0x2A;   // non-US #（按反斜杠兜底）
        case 0x33: return 0x29;   // ;
        case 0x34: return 0x27;   // '
        case 0x35: return 0x32;   // `
        case 0x36: return 0x2B;   // ,
        case 0x37: return 0x2F;   // .
        case 0x38: return 0x2C;   // /
        case 0x39: return 0x39;   // Caps Lock
        // —— F1–F12 ——
        case 0x3A: return 0x7A; case 0x3B: return 0x78; case 0x3C: return 0x63;
        case 0x3D: return 0x76; case 0x3E: return 0x60; case 0x3F: return 0x61;
        case 0x40: return 0x62; case 0x41: return 0x64; case 0x42: return 0x65;
        case 0x43: return 0x6D; case 0x44: return 0x67; case 0x45: return 0x6F;
        // —— PrSc/ScrLk/Pause → F13–F15（Mac 键盘没有这三颗独立键）——
        case 0x46: return 0x69; case 0x47: return 0x6B; case 0x48: return 0x71;
        // —— 编辑键 ——
        case 0x49: return 0x72;   // Insert → Help
        case 0x4A: return 0x73;   // Home
        case 0x4B: return 0x74;   // PageUp
        case 0x4D: return 0x77;   // End
        case 0x4E: return 0x79;   // PageDown
        // —— 小键盘（kVK 值对着 Apple HIToolbox/Events.h 逐个核对过）——
        // ⚠️ 此前整段**错位一格**：0x53 起每一行都被挪到相邻键上
        //   （KP1→Keypad2、KP Enter→Keypad1、KP *→KeypadMinus…），末尾还漏了 KP = 与 KP 2。
        //   用户现象就是「小键盘按下去出来的是别的键」（issue #52 第 13 条）。
        case 0x53: return 0x4A;   // NumLock → KeypadClear
        case 0x54: return 0x4B;   // KP /
        case 0x55: return 0x43;   // KP *
        case 0x56: return 0x4E;   // KP -
        case 0x57: return 0x45;   // KP +
        case 0x58: return 0x4C;   // KP Enter
        case 0x59: return 0x53;   // KP 1
        case 0x5A: return 0x54;   // KP 2
        case 0x5B: return 0x55;   // KP 3
        case 0x5C: return 0x56;   // KP 4
        case 0x5D: return 0x57;   // KP 5
        case 0x5E: return 0x58;   // KP 6
        case 0x5F: return 0x59;   // KP 7
        case 0x60: return 0x5B;   // KP 8
        case 0x61: return 0x5C;   // KP 9
        case 0x62: return 0x52;   // KP 0
        case 0x63: return 0x41;   // KP .
        case 0x64: return 0x2A;   // 非美键盘 \|（按反斜杠兜底）
        case 0x67: return 0x51;   // KP =（此前整条缺失）
        default: return -1;
    }
}

/* 手柄按钮位 → macOS 虚拟键码（与 Windows 的 kGamepadVk 同语义：A→Enter、B→Esc、
   X→E、Y→Q、L1/R1→1/2…）。macOS 没有 CGEvent 手柄通道，虚拟 HID（IOHIDUserDevice）
   需要 Apple 受限 entitlement `com.apple.developer.hid.virtual.device`：ad-hoc 签名下
   一注入就被 AMFI 直接 SIGKILL，所以这条链路只能降级成键盘 —— 至少游戏能收到确认键。 */
const int kGamepadMacKey[apxpc::wireless::kGamepadButtonCount] = {
    0x24,   // 0  A       → Return
    0x35,   // 1  B       → Escape
    0x0E,   // 2  X       → E
    0x0C,   // 3  Y       → Q
    0x12,   // 4  L1      → 1
    0x13,   // 5  R1      → 2
    0x14,   // 6  L2      → 3
    0x15,   // 7  R2      → 4
    0x30,   // 8  SELECT  → Tab
    0x31,   // 9  START   → Space
    0x0F,   // 10 C       → R
    0x03,   // 11 Z       → F
    0x3A,   // 12 MODE    → Option
    0x38,   // 13 THUMBL  → Shift
    0x3B,   // 14 THUMBR  → Control
    -1,     // 15 未使用
};

// ---------- 自由函数共用：稳态时钟 + 剪贴板回环抑制（对齐 win 版 clipSuppressFor(600)）---
long long steadyMsNow() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
std::atomic<long long> g_clipSuppressMs{0};

// ---------- 光标定位 / 显示器并集 / 拖动事件类型（issue #47 第 6 条）----------
// 这里三个都是 injectMouse 的支撑：读当前光标、算出夹取范围、决定投递哪种事件。

/// 读当前光标位置。`CGEventCreate(nullptr)` 造的是「无源事件」，只用来取坐标，
/// 读完必须 CFRelease —— 旧实现写成 `CGEventGetLocation(CGEventCreate(nullptr))`，
/// 每帧漏一个事件对象（连续滑动时是无上限的泄漏）。
CGPoint currentCursorPos() {
    CGEventRef e = CGEventCreate(nullptr);
    const CGPoint p = e ? CGEventGetLocation(e) : CGPointMake(0, 0);
    if (e) CFRelease(e);
    return p;
}

/// 全部活动显示器的并集（副屏在主屏左侧时 origin 为负，所以要并集而不是主屏 bounds）。
/// 取不到时返回**无效框**：clampToBox 对无效框不夹，宁可交给系统，也不要把光标吸到 (0, 0)。
BoxD activeDisplayBox() {
    uint32_t n = 0;
    if (CGGetActiveDisplayList(0, nullptr, &n) != kCGErrorSuccess || n == 0) return BoxD{};
    std::vector<CGDirectDisplayID> ids(n);
    uint32_t got = 0;
    if (CGGetActiveDisplayList(n, ids.data(), &got) != kCGErrorSuccess || got == 0) return BoxD{};
    CGRect u = CGRectNull;
    for (uint32_t i = 0; i < got; ++i) u = CGRectUnion(u, CGDisplayBounds(ids[i]));
    if (CGRectIsNull(u)) return BoxD{};
    return BoxD{u.origin.x, u.origin.y,
                u.origin.x + u.size.width - 1.0, u.origin.y + u.size.height - 1.0};
}

/// 按住键时的移动必须换成「拖动」事件，否则部分应用收不到拖拽（选区、拖文件、拖窗口）。
CGEventType moveEventType(MoveKind k) {
    switch (k) {
        case MoveKind::LeftDrag:  return kCGEventLeftMouseDragged;
        case MoveKind::RightDrag: return kCGEventRightMouseDragged;
        case MoveKind::OtherDrag: return kCGEventOtherMouseDragged;
        case MoveKind::Move:
        default:                  return kCGEventMouseMoved;
    }
}

/// 移动事件的按键参数要与拖动类型配套，不能一律 kCGMouseButtonLeft。
CGMouseButton moveButton(MoveKind k) {
    switch (k) {
        case MoveKind::RightDrag: return kCGMouseButtonRight;
        case MoveKind::OtherDrag: return kCGMouseButtonCenter;
        default:                  return kCGMouseButtonLeft;
    }
}

}  // namespace（匿名）

class MacInjector : public InputInjector {
public:
    /* 权限自检（只提示一次）：macOS 没给「辅助功能」授权时 CGEventPost 会被系统**静默丢弃** ——
       用户那边就是「手机连上了、按什么都没反应」，日志里也一片干净（issue#47 无线全失效
       的最可能真因）。所以第一帧输入到达时弹一次系统授权引导：此刻用户正在操作，
       看到弹窗才明白为什么要授权。 */
    void ensureTrustedOnce() {
        if (trustChecked_) return;
        trustChecked_ = true;
        if (platformInputTrusted()) return;
        // 走 APX_LOG 落盘而不是 stderr：.app 双击启动时 stderr 不可见，fprintf 那版在真机上
        // 等于什么都没写 —— 报障「开了录屏和录音、重启还是提示」时，日志里唯一能证明
        // preflight=false 的痕迹就是这条。
        // 权限名按系统版本取（macOS 26+ = 「设备控制与数据访问」），日志里用户要照着去
        // 系统设置找面板 —— 写死旧名就会让人在设置里找不到那道开关（issue #52 第 1 条）。
        const std::string lbl = inputTrustSettingsLabel();
        APX_LOGW("未获得「{}」权限：注入事件会被系统丢弃，正在弹出授权引导"
                 "（授权路径：系统设置 → 隐私与安全性 → {} → 勾选全能外设）", lbl, lbl);
        if (requestPlatformInputTrust())
            APX_LOGI("已获得「{}」权限", lbl);
        else
            APX_LOGW("仍未授权：手机发来的键鼠在授权前不会生效（面板可「打开系统设置」改授权，改完须重启应用）");
    }

    void injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel, int8_t pan = 0) override {
        ensureTrustedOnce();
        // 顺序：先在**当前位置**结算按键状态变化（按下 / 抬起），再移动，最后滚轮。
        // 反过来的话「按下 + 同帧位移」会在键还没按下时先发拖动事件（部分应用直接丢），
        // 「抬起 + 同帧位移」则会先移动再抬手，拖拽尾巴被判成一次普通移动。
        const CGPoint cur = currentCursorPos();
        applyButtonTransitions(buttons, cur);
        if (dx || dy) {
            // ★ 落点必须是**算出来的目标坐标**（当前坐标 + 增量）。
            //   旧实现把增量塞进 kCGMouseEventDeltaX/Y、落点却仍用当前坐标 ——
            //   投递事件的落点由 location 决定，增量字段不驱动光标，于是每帧都等价于
            //   「把光标移到它现在所在的位置」：光标纹丝不动，用户看到的就是
            //   「触控板划了没反应」。键盘走 CGEventCreateKeyboardEvent，不经过这条路，
            //   所以现象正好是「键盘能用、触控板不能用」（issue #47 第 6 条）。
            const PointD target = clampToBox(
                relativeMoveTarget(PointD{cur.x, cur.y}, dx, dy), activeDisplayBox());
            const MoveKind kind = moveKindForButtons(mouseButtons_);
            CGEventRef e = CGEventCreateMouseEvent(nullptr, moveEventType(kind),
                                                   CGPointMake(target.x, target.y),
                                                   moveButton(kind));
            CGEventSetIntegerValueField(e, kCGMouseEventDeltaX, dx);
            CGEventSetIntegerValueField(e, kCGMouseEventDeltaY, dy);
            CGEventPost(kCGHIDEventTap, e); CFRelease(e);
        }
        if (wheel || pan) {
            // 轴数 2 = (垂直, 水平)。CGEventCreateScrollWheelEvent 是变参函数：
            // axisCount 给几个，后面就按顺序读几个 int32 —— 常量 1 的老写法只能带垂直。
            CGEventRef e = CGEventCreateScrollWheelEvent(nullptr, kCGScrollEventUnitLine,
                                                         2, wheel, pan);
            CGEventPost(kCGHIDEventTap, e); CFRelease(e);
        }
    }

    /// 结算按键状态变化。每个键位用**对应**的 CGMouseButton：旧实现一律传
    /// kCGMouseButtonLeft，右键 / 中键在部分应用里会被认成左键事件。
    void applyButtonTransitions(uint8_t buttons, CGPoint at) {
        const struct { uint8_t bit; CGEventType down; CGEventType up; CGMouseButton btn; } table[] = {
            {0x01, kCGEventLeftMouseDown, kCGEventLeftMouseUp, kCGMouseButtonLeft},
            {0x02, kCGEventRightMouseDown, kCGEventRightMouseUp, kCGMouseButtonRight},
            {0x04, kCGEventOtherMouseDown, kCGEventOtherMouseUp, kCGMouseButtonCenter},
        };
        for (const auto& t : table) {
            const bool was = (mouseButtons_ & t.bit) != 0, now = (buttons & t.bit) != 0;
            if (was == now) continue;
            CGEventRef e = CGEventCreateMouseEvent(nullptr, now ? t.down : t.up, at, t.btn);
            CGEventPost(kCGHIDEventTap, e); CFRelease(e);
        }
        mouseButtons_ = buttons;
    }

    void injectTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) override {
        ensureTrustedOnce();
        (void)buttons;
        CGRect pb = CGDisplayBounds(CGMainDisplayID());
        const CGFloat px = static_cast<CGFloat>(x) / 65535.0 * (pb.origin.x + pb.size.width);
        const CGFloat py = static_cast<CGFloat>(y) / 65535.0 * (pb.origin.y + pb.size.height);
        CGPoint pos = CGPointMake(px, py);
        constexpr uint8_t kDown = 0, kUp = 1;
        CGEventType type = (action == kDown) ? kCGEventLeftMouseDown
                        : (action == kUp) ? kCGEventLeftMouseUp : kCGEventMouseMoved;
        CGEventRef e = CGEventCreateMouseEvent(nullptr, type, pos, kCGMouseButtonLeft);
        CGEventPost(kCGHIDEventTap, e); CFRelease(e);
    }

    void injectKeyboard(uint8_t mod, const uint8_t* keys, size_t count) override {
        ensureTrustedOnce();
        const size_t n = count > 6 ? 6 : count;
        // 修饰键：Mac 不区分左右，统一用左侧键码（控制=0x3B/0x38? 用 0x3B=Control,0x38=Shift,0x3A=Option,0x37=Cmd）
        const int modMap[8] = {0x3B, 0x38, 0x3A, 0x37, 0x3B, 0x38, 0x3A, 0x37};
        for (int bit = 0; bit < 8; ++bit) {
            const uint8_t m = static_cast<uint8_t>(1u << bit);
            const bool was = (kbMod_ & m) != 0, now = (mod & m) != 0;
            if (was == now) continue;
            CGKeyCode kc = static_cast<CGKeyCode>(modMap[bit]);
            CGEventRef e = CGEventCreateKeyboardEvent(nullptr, kc, now);
            CGEventPost(kCGHIDEventTap, e); CFRelease(e);
        }
        for (uint8_t i = 0; i < 6; ++i) {
            const uint8_t k = kbKeys_[i]; if (!k) continue;
            bool still = false; for (size_t j = 0; j < n; ++j) if (keys[j] == k) { still = true; break; }
            if (!still) postKey(k, false);
        }
        for (size_t j = 0; j < n; ++j) {
            const uint8_t k = keys[j]; if (!k) continue;
            bool existed = false; for (uint8_t i = 0; i < 6; ++i) if (kbKeys_[i] == k) { existed = true; break; }
            if (!existed) postKey(k, true);
        }
        kbMod_ = mod;
        for (uint8_t i = 0; i < 6; ++i) kbKeys_[i] = (i < n) ? keys[i] : 0;
    }
    /// 「菜单」键（HID usage 0x65 / Application）在 macOS 上没有可靠的 CGKeyCode：
    /// `kVK_ContextualMenu` 只在第三方清单里出现，Apple 公开的 HIToolbox/Events.h 里
    /// 查不到它（已核对 MacOSX10.6/11/12/13/14 SDK 的 Events.h，均无该常量）——
    /// 所以**不猜键码**，改用有文档的右键事件表达「打开上下文菜单」这个语义。
    /// 只发一次右键按下+抬起（不移动光标），等价于宿主键盘上的菜单键。
    void postContextMenu() {
        const CGPoint p = currentCursorPos();
        for (const auto type : {kCGEventRightMouseDown, kCGEventRightMouseUp}) {
            CGEventRef e = CGEventCreateMouseEvent(nullptr, type, p, kCGMouseButtonRight);
            if (!e) continue;
            CGEventPost(kCGHIDEventTap, e); CFRelease(e);
        }
    }

    void postKey(uint8_t usage, bool down) {
        if (usage == 0x65) {
            // 「菜单」：只在「按下」那一拍发右键，抬起不再发第二次
            if (down) postContextMenu();
            return;
        }
        const int cg = hidToMacKey(usage);
        if (cg < 0) {
            // 原先这里只有 DEBUG 一行，Release 构建下全被关掉 → 用户「按了没反应」时无从查起
            APX_LOGW("按键未映射：HID usage=0x{:02x} 已跳过（misc/小键盘等冷门键）", usage);
            return;
        }
        postCode(cg, down);
    }

    /// 按键码直投（手柄降级用；-1 = 该位没有对应键，静默跳过）
    void postCode(int cgKey, bool down) {
        if (cgKey < 0) return;
        CGEventRef e = CGEventCreateKeyboardEvent(nullptr, static_cast<CGKeyCode>(cgKey), down);
        CGEventPost(kCGHIDEventTap, e); CFRelease(e);
    }

    void injectConsumer(uint16_t) override {
        // macOS 多媒体键需 NXSystemDefined，跨版本不稳定，暂忽略（不影响鼠标/键盘/触摸）。
    }

    /* 手柄（网络帧 0x07）。
       ⚠️ 这里曾经是**空实现**，只打一行告警 —— 用户看到的就只是「手柄接上完全没反应」。
       macOS 确实没有 CGEvent 手柄通道（虚拟 HID 要 Apple 受限 entitlement，见 kGamepadMacKey
       注释），但「什么都不做」等于功能不存在。现在做**与 Windows 同口径**的降级：
         按钮   → 键盘等价键（A→Enter、B→Esc、L1/R1→1/2 …）
         左摇杆 → 鼠标相对移动（死区 12、位移 1/4 缩放）
         右摇杆 → 滚轮 / 水平滚轮（限速 20Hz，摇杆帧率下不限速会疯狂滚动）
       边沿、死区、缩放、限速全部来自 gamepad_fallback.hpp（host 用例钉住那份算术）。 */
    void injectGamepad(uint16_t buttons, int8_t x, int8_t y, int8_t rx, int8_t ry) override {
        ensureTrustedOnce();
        const GamepadEdges e = gamepadEdges(gpButtons_, buttons);
        for (int bit = 0; bit < kGamepadButtonCount; ++bit) {
            const int kc = kGamepadMacKey[bit];
            if (kc < 0) continue;
            const uint16_t m = static_cast<uint16_t>(1u << bit);
            if (e.pressed & m) postCode(kc, true);
            if (e.released & m) postCode(kc, false);
        }
        gpButtons_ = buttons;

        if (gamepadStickActive(x) || gamepadStickActive(y))
            injectMouse(mouseButtons_, gamepadStickToMouse(x), gamepadStickToMouse(y), 0);

        if (gamepadStickActive(rx) || gamepadStickActive(ry)) {
            const uint64_t now = static_cast<uint64_t>(steadyMsNow());
            if (gamepadWheelAllowed(lastWheelMs_, now)) {
                lastWheelMs_ = now;
                // 垂直：摇杆前推（ry 为负）→ 内容上滚（正滚轮），与 Windows 同号
                const int wheel = (ry == 0) ? 0 : -gamepadStickStep(ry);
                // 水平：右推为正，与 0x01 帧的 pan 同号
                const int pan = (rx == 0) ? 0 : gamepadStickStep(rx);
                injectMouse(mouseButtons_, 0, 0, static_cast<int8_t>(wheel),
                            static_cast<int8_t>(pan));
            }
        }

        if (!gpWarned_) {
            gpWarned_ = true;
            std::fprintf(stderr, "[AllPeriph] 手柄经网络接入：macOS 无 CGEvent 手柄通道"
                                 "（虚拟 HID 需 Apple 受限 entitlement），已降级为"
                                 "「按钮→键盘 + 左摇杆→鼠标 + 右摇杆→滚动」\n");
            std::fflush(stderr);
        }
    }

    void setClipboard(const std::string& text) override {
        NSString* s = [NSString stringWithUTF8String:text.c_str()];
        NSPasteboard* pb = [NSPasteboard generalPasteboard];
        [pb clearContents];
        [pb setString:s forType:NSPasteboardTypeString];
    }

    uint8_t mouseButtons_ = 0;
    uint8_t kbMod_ = 0;
    uint8_t kbKeys_[6] = {0, 0, 0, 0, 0, 0};
    uint16_t gpButtons_ = 0;      ///< 手柄按钮上一帧位图（降级注入要算边沿）
    uint64_t lastWheelMs_ = 0;    ///< 右摇杆滚轮限速时间戳
    bool gpWarned_ = false;       ///< 手柄降级告警只打一次
    bool trustChecked_ = false;   ///< 注入权限自检只做一次（别每帧查一次 TCC）
};

class MacClipboardWatcher : public ClipboardWatcher {
public:
    explicit MacClipboardWatcher(std::function<void(const std::string&)> cb) : cb_(std::move(cb)) {}
    /* 析构必须 stop：start() 会起一个跑在大局外的线程，detach 版本的实现（历史）会
       让线程活过对象生存期（use-after-free）；不 stop 则未 join 的 std::thread 析构
       直接 std::terminate。v223 崩溃 issue#47 的两处根因就在这里——
       实测崩溃栈：#7 线程析构 → #6/#5/#4 terminate → abort，帧 #7 所在函数 = 本类析构。
       修法两条一起上：① 析构停线程；② 线程内用 shared_ptr 持有「活着」标志，
       join 失败（极少见）时线程自行退出、不再碰已析构的 this。 */
    ~MacClipboardWatcher() override { stop(); }
    bool start() override {
        if (thread_.joinable()) return false;      // 重复 start：直接拒绝，不覆盖线程句柄
        running_ = true;
        auto alive = alive_;
        auto cb = cb_;
        thread_ = std::thread([alive, cb] {
            std::string last;
            while (alive->load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(800));
                if (!alive->load()) break;
                // 回环抑制：setSystemClipboard 写入后 600ms 内不回传（与 win 版一致，
                // 否则反向剪贴板 0x21 → 写本机 → watcher → 又发回对端 = 刷乱对端剪贴板）
                if (steadyMsNow() - g_clipSuppressMs.load() < 600) continue;
                NSPasteboard* pb = [NSPasteboard generalPasteboard];
                NSString* s = [pb stringForType:NSPasteboardTypeString];
                if (s) {
                    std::string cur([s UTF8String]);
                    if (!cur.empty() && cur != last) { last = cur; cb(cur); }
                }
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
    std::function<void(const std::string&)> cb_;
    std::atomic<bool> running_{false};
    std::shared_ptr<std::atomic<bool>> alive_ = std::make_shared<std::atomic<bool>>(true);
    std::thread thread_;
};

std::unique_ptr<InputInjector> createPlatformInjector() { return std::make_unique<MacInjector>(); }
std::unique_ptr<ClipboardWatcher> createPlatformClipboardWatcher(
    std::function<void(const std::string&)> cb) {
    return std::make_unique<MacClipboardWatcher>(std::move(cb));
}

/* 注入权限：CGPreflightPostEventAccess = 此刻有没有权限投递事件（TCC「辅助功能」），
   CGRequestPostEventAccess = 没有就弹一次系统引导。两者都是 10.15+ API，老 SDK 上
   编不出来 —— 用宏兜住，那种情况下按「有权限」处理（10.14 及更早没有这道门槛）。 */
bool platformInputTrusted() {
#if defined(__MAC_OS_X_VERSION_MAX_ALLOWED) && __MAC_OS_X_VERSION_MAX_ALLOWED >= 101500
    return CGPreflightPostEventAccess();
#else
    return true;
#endif
}

bool requestPlatformInputTrust() {
#if defined(__MAC_OS_X_VERSION_MAX_ALLOWED) && __MAC_OS_X_VERSION_MAX_ALLOWED >= 101500
    if (CGPreflightPostEventAccess()) return true;
    CGRequestPostEventAccess();          // 只在 TCC「未决定」时弹窗；用户拒绝过一次后
                                         // 本函数静默返回 false，必须引导去系统设置（input.openSettings）
    return CGPreflightPostEventAccess();
#else
    return true;
#endif
}

/* 权限面板在系统设置里的显示名：macOS 26 起「辅助功能」改叫「设备控制与数据访问」。
   只用于文案，不参与任何判断 —— TCC 服务名仍是 kTCCServiceAccessibility。 */
std::string inputTrustSettingsLabel() {
    NSOperatingSystemVersion v = [[NSProcessInfo processInfo] operatingSystemVersion];
    if (v.majorVersion >= 26) return "设备控制与数据访问";
    return "辅助功能";
}

/* 打开系统设置里对应的权限面板（TCC 拒绝过一次后 CGRequestPostEventAccess 不再弹窗，
   这里是用户唯一的自救入口）。URL 打不开则退回官方 AX prompt —— 系统自己导航，
   面板改名不影响它。返回是否成功把用户带到设置界面。 */
bool openPlatformInputSettings() {
    const NSOperatingSystemVersion v = [[NSProcessInfo processInfo] operatingSystemVersion];
    const char* urls[] = {
        v.majorVersion >= 13
            ? "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_Accessibility"
            : "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
        "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
        "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension",
    };
    for (const char* u : urls) {
        NSURL* url = [NSURL URLWithString:[NSString stringWithUTF8String:u]];
        if (url && [[NSWorkspace sharedWorkspace] openURL:url]) {
            APX_LOGI("已打开权限设置面板：{}", u);
            return true;
        }
    }
    NSDictionary* opt = @{ (__bridge NSString*)kAXTrustedCheckOptionPrompt : @YES };
    (void)AXIsProcessTrustedWithOptions((__bridge CFDictionaryRef)opt);
    APX_LOGW("深链打开失败，已改用 AXIsProcessTrustedWithOptions 提示");
    return false;
}

/* 本进程是否跑在 .app 里：.app 与裸二进制是两条独立 TCC 记录（ad-hoc 签名按 cdhash 记账），
   面板必须说清该勾哪一项，否则用户永远在系统设置里勾错对象。 */
bool runningFromAppBundle() {
    const std::string exe = apxpc::autostart::getExePath();
    return exe.find(".app/Contents/MacOS/") != std::string::npos;
}

// ============================================================================
// 与 input_injector_mac.mm 对等的自由函数（ctrl9511.hpp 声明，无线会话/宿主直接调用）
// ============================================================================

// Windows VK 码 → macOS 虚拟键码（协议两侧共用 VK 码空间，见 wireless_session 注释）
static int vkToMacKey(uint8_t vk) {
    switch (vk) {
        case 0x08: return 0x33;  // Backspace（VK_BACK）
        case 0x09: return 0x30;  // Tab
        case 0x0D: return 0x24;  // Enter
        case 0x1B: return 0x35;  // Esc
        case 0x20: return 0x31;  // Space
        case 0x21: return 0x74;  // PageUp
        case 0x22: return 0x79;  // PageDown
        case 0x23: return 0x77;  // End
        case 0x24: return 0x73;  // Home
        case 0x25: return 0x7B;  // Left
        case 0x26: return 0x7E;  // Up
        case 0x27: return 0x7C;  // Right
        case 0x28: return 0x7D;  // Down
        case 0x2E: return 0x75;  // Delete（forward delete）
        case 0x70: return 0x7A;  // F1
        case 0x71: return 0x78;  // F2
        case 0x72: return 0x63;  // F3
        case 0x73: return 0x76;  // F4
        case 0x74: return 0x60;  // F5
        case 0x75: return 0x61;  // F6
        case 0x76: return 0x62;  // F7
        case 0x77: return 0x64;  // F8
        case 0x78: return 0x65;  // F9
        case 0x79: return 0x6D;  // F10
        case 0x7A: return 0x67;  // F11
        case 0x7B: return 0x6F;  // F12
        default:   return -1;
    }
}

static void postVk(int cgKey, bool down) {
    if (cgKey < 0) return;
    CGEventRef e = CGEventCreateKeyboardEvent(nullptr, static_cast<CGKeyCode>(cgKey), down);
    CGEventPost(kCGHIDEventTap, e);
    CFRelease(e);
}

// 特殊键/组合键（0x28）：mod 位图 1=Ctrl 2=Shift 4=Alt（及高位镜像），vk = Windows VK 码
bool injectSpecialKey(uint8_t mod, uint8_t vk) {
    const int target = vkToMacKey(vk);
    if (target < 0) return false;
    const int modKeys[3] = {0x3B, 0x38, 0x3A};   // Ctrl / Shift / Option（左侧）
    const uint8_t bits[3] = {0x01, 0x02, 0x04};
    for (int i = 0; i < 3; ++i) if (mod & bits[i]) postVk(modKeys[i], true);
    postVk(target, true);
    postVk(target, false);
    for (int i = 2; i >= 0; --i) if (mod & bits[i]) postVk(modKeys[i], false);
    return true;
}

std::string readSystemClipboard() {
    @autoreleasepool {
        NSPasteboard* pb = [NSPasteboard generalPasteboard];
        NSString* s = [pb stringForType:NSPasteboardTypeString];
        if (!s) return {};
        const char* u = [s UTF8String];
        return u ? std::string(u) : std::string{};
    }
}

// 反向剪贴板（0x21）：写本机 NSPasteboard；写入后 600ms 内 watcher 不回传（防回环）
bool setSystemClipboard(const std::string& text) {
    @autoreleasepool {
        NSString* s = [NSString stringWithUTF8String:text.c_str()];
        if (!s) return false;
        g_clipSuppressMs.store(steadyMsNow());
        NSPasteboard* pb = [NSPasteboard generalPasteboard];
        [pb clearContents];
        return [pb setString:s forType:NSPasteboardTypeString] ? true : false;
    }
}

// 文本注入当前光标处：ASCII 逐键（CGEventKeyboardSetUnicodeString，不走布局表）；
// 非 ASCII 剪贴板 + Cmd+V 后还原（与 Windows 版同策略，避免逐键丢字）。
bool injectSystemText(const std::string& text) {
    if (text.empty()) return true;
    bool ascii = true;
    for (unsigned char c : text) { if (c >= 0x80) { ascii = false; break; } }
    @autoreleasepool {
        if (ascii) {
            for (unsigned char c : text) {
                if (c == '\n') { postVk(0x24, true); postVk(0x24, false); continue; }
                if (c == '\r') continue;
                if (c == '\t') { postVk(0x30, true); postVk(0x30, false); continue; }
                const UniChar u = c;
                // 注意签名：(event, UniCharCount 长度, const UniChar* 字符串) —— 长度在第 2 参
                CGEventRef e = CGEventCreateKeyboardEvent(nullptr, 0, true);
                CGEventKeyboardSetUnicodeString(e, 1, &u);
                CGEventPost(kCGHIDEventTap, e); CFRelease(e);
                e = CGEventCreateKeyboardEvent(nullptr, 0, false);
                CGEventKeyboardSetUnicodeString(e, 1, &u);
                CGEventPost(kCGHIDEventTap, e); CFRelease(e);
            }
            return true;
        }
        const std::string old = readSystemClipboard();
        NSPasteboard* pb = [NSPasteboard generalPasteboard];
        NSString* s = [NSString stringWithUTF8String:text.c_str()];
        if (!s) return false;
        g_clipSuppressMs.store(steadyMsNow());
        [pb clearContents];
        [pb setString:s forType:NSPasteboardTypeString];
        postVk(0x37, true);    // Cmd down
        postVk(0x09, true);    // V
        postVk(0x09, false);
        postVk(0x37, false);   // Cmd up
        std::this_thread::sleep_for(std::chrono::milliseconds(80));
        g_clipSuppressMs.store(steadyMsNow());
        NSString* o = [NSString stringWithUTF8String:old.c_str()];
        [pb clearContents];
        if (o) [pb setString:o forType:NSPasteboardTypeString];
        else   [pb setString:@"" forType:NSPasteboardTypeString];
        return true;
    }
}

}  // namespace apxpc::wireless

#endif  // __APPLE__
