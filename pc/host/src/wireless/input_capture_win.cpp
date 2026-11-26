// ============================================================================
// Windows 本地输入捕获（9511 遥控器）：WH_KEYBOARD_LL / WH_MOUSE_LL + 消息泵
// 钩子只读取并转发，事件仍 CallNextHookEx 透传给本机，PC 自身照常可用。
// ============================================================================
#include "apxpc/wireless/input_capture.hpp"

#if defined(_WIN32)

#include "apxpc/log.hpp"

#include <windows.h>
#include <thread>
#include <chrono>
#include <atomic>
#include <cstdint>

namespace apxpc::wireless {
namespace {

int8_t clamp8(int v) { return static_cast<int8_t>(v < -128 ? -128 : v > 127 ? 127 : v); }

// 跨屏独占的抵缘驻留计时用：steady 时钟毫秒（只在本文件内比较差值，不落盘）。
int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

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
        active_ = true;
        // ★ 钩子的安装与消息泵必须在**同一线程**：低级钩子（WH_*_LL）的回调只在安装线程
        //   取消息时投递。旧实现把安装放在调用线程、泵在子线程 —— 调用线程不泵消息时
        //   回调永远不会触发（系统超时后还会把钩子摘掉），捕获器形同虚设。
        //   面板接线（wireless_session 键鼠镜像）后这条路径首次被真机流量踩到，一并修正。
        thread_ = std::thread([this] {
            hhMouse_ = SetWindowsHookEx(WH_MOUSE_LL, msProc, GetModuleHandle(nullptr), 0);
            hhKey_ = SetWindowsHookEx(WH_KEYBOARD_LL, kbProc, GetModuleHandle(nullptr), 0);
            if (!hhMouse_ || !hhKey_) {
                APX_LOGW("本地输入钩子安装失败（mouse={} key={}）——键鼠镜像不可用",
                         hhMouse_ != nullptr, hhKey_ != nullptr);
                active_ = false;   // 泵循环条件即 active_，置 false 让线程自然退出
            }
            MSG msg{};
            while (active_) {
                if (PeekMessage(&msg, nullptr, 0, 0, PM_REMOVE)) {
                    TranslateMessage(&msg);
                    DispatchMessage(&msg);
                } else {
                    std::this_thread::sleep_for(std::chrono::milliseconds(5));
                }
            }
            // 泵退出后在本线程卸钩（同一安装线程，Unhook 语义最干净）
            if (hhMouse_) { UnhookWindowsHookEx(hhMouse_); hhMouse_ = nullptr; }
            if (hhKey_) { UnhookWindowsHookEx(hhKey_); hhKey_ = nullptr; }
        });
        return true;
    }

    void stop() override {
        active_ = false;
        if (thread_.joinable()) thread_.join();
        g_self = nullptr;
        // 跨屏独占：若停在「手机焦点」里，退出焦点即可。光标全程停在进入点（屏幕
        // 右缘，本就在屏内），无需归位动作。
        if (focused_.exchange(false)) {
            releaseForwardedState();   // 手机端悬挂的按键/鼠标按下随退焦点一并松开
            APX_LOGI("跨屏扩展：停止捕获，焦点交回电脑");
        }
    }

    // 键鼠镜像工作模式（issue#171，2026-11-24 独占 / 2026-11-26 共享）：钩子线程按事件读
    // workMode_。切回镜像时若正聚焦，退出焦点即可 —— 光标停在进入点（右缘内侧），无需归位。
    void setWorkMode(int mode) override {
        workMode_.store(mode, std::memory_order_relaxed);
        if (mode == kWorkMirror && focused_.exchange(false)) {
            releaseForwardedState();   // 手机端悬挂的按键/鼠标按下随退焦点一并松开
            APX_LOGI("跨屏扩展：切回镜像同步，焦点交回电脑");
        }
        APX_LOGI("键鼠镜像工作模式：{}", mode == kWorkShared ? "共享（右缘滑过即切）"
                                     : mode == kWorkCross ? "跨屏独占（右缘进入）" : "镜像同步");
    }

    // —— 鼠标移动：返回 true 表示吞掉该事件（不透传给本机）——
    bool onMove(int x, int y) {
        const int mode = workMode_.load(std::memory_order_relaxed);
        if (mode != kWorkMirror) {
            const RECT virt = virtualRect();
            if (!focused_.load(std::memory_order_relaxed)) {
                // 未聚焦：电脑输入留在电脑（不转发），盯右缘手势。
                const bool atEdge = x >= virt.right - 1;
                if (mode == kWorkShared) {
                    // 共享模式：滑过边缘即切（无需驻留计时）—— 像扩展屏一样，把鼠标
                    // 推出右缘的意图本身就是切换。仍要求无按住的鼠标键（否则抬起事件
                    // 会被吞掉转发到手机，电脑端卡在拖拽态，与独占同款门控）。
                    if (atEdge && x > lastX_ && physButtons_ == 0) {
                        focused_.store(true, std::memory_order_relaxed);
                        entryX_ = x; entryY_ = y;
                        focusedEnterMs_ = nowMs();
                        APX_LOGI("跨屏扩展：焦点切到手机（右缘滑过），本机输入已独占拦截");
                        if (lastX_ >= 0)
                            client_->sendMouse(mouseButtons_, clamp8(x - lastX_), clamp8(y - lastY_), 0);
                        lastX_ = x; lastY_ = y;
                        return true;   // 进入事件吞掉
                    }
                } else {
                    // 独占模式：「抵边后仍连续收到事件 ≥250ms」= 用户在往屏外推，
                    // 而非路过点一下。按住鼠标键时不进入也不重置驻留。
                    if (!atEdge || (lastX_ >= 0 && x < lastX_)) {
                        edgeFirstMs_ = 0;   // 离开边缘或往回走：重计驻留
                    } else if (x > lastX_ || edgeFirstMs_ == 0) {
                        edgeFirstMs_ = nowMs();   // 首次抵边：开始计时
                    }
                    if (atEdge && edgeFirstMs_ != 0 && nowMs() - edgeFirstMs_ >= kEdgeDwellMs &&
                        physButtons_ == 0) {
                        edgeFirstMs_ = 0;
                        focused_.store(true, std::memory_order_relaxed);
                        entryX_ = x; entryY_ = y;
                        focusedEnterMs_ = nowMs();
                        APX_LOGI("跨屏扩展：焦点切到手机（右缘推入），本机输入已独占拦截");
                        if (lastX_ >= 0)
                            client_->sendMouse(mouseButtons_, clamp8(x - lastX_), clamp8(y - lastY_), 0);
                        lastX_ = x; lastY_ = y;
                        return true;   // 进入事件吞掉
                    }
                }
                lastX_ = x; lastY_ = y;
                return false;   // 未聚焦：透传，本机照常
            }
            // 聚焦中：移动事件全部吞掉 → 光标停在进入点不动，系统后续上报的 pt
            // 始终从进入点起算（pt = 进入点 + 本次原始位移），pt-进入点即真实位移，
            // 不依赖任何「泊点」假设。
            const int dx = x - entryX_, dy = y - entryY_;
            // 进入后的短宽限：手仍在向右推、回拉抖动不应立刻退焦点（issue#171 Mac 端
            // 实测 46–143ms 即被切回）；宽限期内照常向手机转发位移，但不做回切判定。
            if (nowMs() - focusedEnterMs_ >= kExitGraceMs && dx <= -kExitPx) {
                // 往回拉过阈值 = 切回电脑：本事件放行，光标顺势落到屏内 48px 处。
                focused_.store(false, std::memory_order_relaxed);
                APX_LOGI("跨屏扩展：焦点切回电脑");
                releaseForwardedState();   // 手机端悬挂的按键/鼠标按下随退焦点一并松开
                lastX_ = x; lastY_ = y;
                return false;
            }
            client_->sendMouse(mouseButtons_, clamp8(dx), clamp8(dy), 0);
            lastX_ = x; lastY_ = y;
            return true;   // 吞掉：电脑不响应
        }
        // 镜像同步（原行为）：转发相对位移，事件照常透传给本机。
        if (lastX_ < 0) { lastX_ = x; lastY_ = y; return false; }
        const int dx = x - lastX_, dy = y - lastY_;
        lastX_ = x; lastY_ = y;
        client_->sendMouse(mouseButtons_, clamp8(dx), clamp8(dy), 0);
        return false;
    }
    bool onWheel(int delta) {
        if (workMode_.load(std::memory_order_relaxed) != kWorkMirror)
            return forwardIfFocused([&] { client_->sendMouse(mouseButtons_, 0, 0, clamp8(delta / WHEEL_DELTA)); });
        client_->sendMouse(mouseButtons_, 0, 0, clamp8(delta / WHEEL_DELTA));
        return false;
    }
    bool onButton(uint8_t bit, bool down) {
        // 物理鼠标键状态跨模式跟踪（钩子线程专用）：进入跨屏焦点前据此确认没有
        // 按住的键。注入事件已在钩子层按 LLMHF_INJECTED 过滤，到达这里的都是物理事件。
        if (down) physButtons_ |= bit; else physButtons_ &= (uint8_t)~bit;
        if (workMode_.load(std::memory_order_relaxed) != kWorkMirror)
            return forwardIfFocused([&] {
                if (down) mouseButtons_ |= bit; else mouseButtons_ &= ~bit;
                client_->sendMouse(mouseButtons_, 0, 0, 0);
            });
        if (down) mouseButtons_ |= bit; else mouseButtons_ &= ~bit;
        client_->sendMouse(mouseButtons_, 0, 0, 0);
        return false;
    }
    bool onKey(int usage, bool down) {
        if (workMode_.load(std::memory_order_relaxed) != kWorkMirror)
            return forwardIfFocused([&] { sendKey(usage, down); });
        sendKey(usage, down);
        return false;
    }

private:
    // 跨屏独占的公共路径：未聚焦 = 透传不转发（电脑输入留在电脑）；
    // 聚焦 = 转发并吞掉（电脑不响应）。fn 里做原有的按键状态维护与转发。
    template <typename F>
    bool forwardIfFocused(F&& fn) {
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
    void sendKey(int usage, bool down) {
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

    // —— 跨屏独占：屏幕矩形（多显示器时右缘 = 最右那块屏的外缘）——
    static RECT virtualRect() {
        const RECT r{GetSystemMetrics(SM_XVIRTUALSCREEN),
                     GetSystemMetrics(SM_YVIRTUALSCREEN),
                     GetSystemMetrics(SM_XVIRTUALSCREEN) + GetSystemMetrics(SM_CXVIRTUALSCREEN),
                     GetSystemMetrics(SM_YVIRTUALSCREEN) + GetSystemMetrics(SM_CYVIRTUALSCREEN)};
        return r;
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

    // —— 跨屏独占/共享状态 ——
    // workMode_/focused_ 原子：setWorkMode 在调用线程改模式，钩子线程按事件读取；
    // entryX_/entryY_/edgeFirstMs_ 只在钩子线程触碰。聚焦期间移动事件被吞、光标
    // 停在进入点，pt 以进入点为原点换算真实位移（见 onMove 聚焦分支注释）。
    std::atomic<int> workMode_{kWorkMirror};
    std::atomic<bool> focused_{false};
    int entryX_ = 0, entryY_ = 0;    // 进入焦点时的光标位置（位移原点）
    int64_t edgeFirstMs_ = 0;        // 首次抵右缘的时刻（驻留判定，0=不在边缘）
    int64_t focusedEnterMs_ = 0;     // 进入焦点的时刻（回切宽限判定，钩子线程专用）
    uint8_t physButtons_ = 0;        // 物理鼠标键按下位图（跨模式跟踪，钩子线程专用）
    static constexpr int kExitPx = 48;      // 向回拉 48px 视为切回电脑
    static constexpr int64_t kEdgeDwellMs = 250;  // 抵缘持续 250ms 才进入，防误触
    static constexpr int64_t kExitGraceMs = 300;  // 进入后 300ms 内不做回切判定，防回拉抖动误退

    static LRESULT CALLBACK msProc(int n, WPARAM w, LPARAM l) {
        if (n >= 0 && g_self) {
            const auto* m = reinterpret_cast<MSLLHOOKSTRUCT*>(l);
            // 跳过系统注入事件（LLMHF_INJECTED）：面板同会话可能双向都开着 —— 手机控电脑
            // 时本机会经 SendInput 注入手机发来的点击/键入，若这些注入事件再被钩子捕获
            // 回传给手机，会形成「注入→捕获→再注入」的输入回声。物理输入不带该位，照常转发。
            if (m->flags & LLMHF_INJECTED) return CallNextHookEx(nullptr, n, w, l);
            switch (w) {
                case WM_MOUSEMOVE: if (g_self->onMove(m->pt.x, m->pt.y)) return 1; break;
                case WM_LBUTTONDOWN: if (g_self->onButton(0x01, true)) return 1; break;
                case WM_LBUTTONUP: if (g_self->onButton(0x01, false)) return 1; break;
                case WM_RBUTTONDOWN: if (g_self->onButton(0x02, true)) return 1; break;
                case WM_RBUTTONUP: if (g_self->onButton(0x02, false)) return 1; break;
                case WM_MBUTTONDOWN: if (g_self->onButton(0x04, true)) return 1; break;
                case WM_MBUTTONUP: if (g_self->onButton(0x04, false)) return 1; break;
                case WM_MOUSEWHEEL: if (g_self->onWheel(GET_WHEEL_DELTA_WPARAM(m->mouseData))) return 1; break;
                default: break;
            }
        }
        return CallNextHookEx(nullptr, n, w, l);
    }
    static LRESULT CALLBACK kbProc(int n, WPARAM w, LPARAM l) {
        if (n >= 0 && g_self) {
            const auto* k = reinterpret_cast<KBDLLHOOKSTRUCT*>(l);
            // 同 msProc：注入事件（LLKHF_INJECTED，含本机注入器转发的手机输入）不回传，
            // 只镜像物理键盘（注释见 msProc）。
            if (k->flags & LLKHF_INJECTED) return CallNextHookEx(nullptr, n, w, l);
            const bool down = (w == WM_KEYDOWN || w == WM_SYSKEYDOWN);
            const bool ext = (k->flags & LLKHF_EXTENDED) != 0;
            const int usage = vkToUsage(k->vkCode, ext);
            if (usage && g_self->onKey(usage, down)) return 1;
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
