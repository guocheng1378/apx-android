// 档 2 触控注入：InjectSyntheticPointerInput（真触摸 / 笔，多点 + 压感 + 倾斜）
//
// 前置条件（ARCHITECTURE §5）：
//   1) 进程具备 uiAccess=true（清单里 <requestedExecutionLevel uiAccess="true">）；
//   2) 二进制必须签名，且位于受保护目录（C:\Windows\System32 等），否则
//      CreateSyntheticPointerDevice 返回 ERROR_ACCESS_DENIED；
//   3) Windows 10 1809+。
// 因此本实现**动态加载**这族 API（旧系统直接降级到档 1），init() 失败时
// 由 createInjector(Auto) 自动回退档 1，不中断服务。
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
#include <unordered_map>
#include <vector>

#include "common/log.hpp"
#include "encode/dynlib.hpp"
#include "inject/touch_frame.hpp"

// ---- 未安装最新 SDK 时的类型补齐（与 winuser.h 保持一致，字段顺序不得改动）----
#ifndef PT_TOUCH
#define PT_TOUCH 0x00000002
#define PT_PEN   0x00000003
#endif

namespace apxdisp {
namespace {

using CreateSyntheticPointerDeviceFn = HSYNTHETICPOINTERDEVICE(WINAPI*)(POINTER_INPUT_TYPE, ULONG, POINTER_FEEDBACK_MODE);
using InjectSyntheticPointerInputFn  = BOOL(WINAPI*)(HSYNTHETICPOINTERDEVICE, CONST POINTER_TYPE_INFO*, UINT32);
using DestroySyntheticPointerDeviceFn = VOID(WINAPI*)(HSYNTHETICPOINTERDEVICE);

constexpr uint32_t kMaxContacts = 10;

uint32_t toPointerId(uint16_t contactId) { return static_cast<uint32_t>(contactId) + 1; }

}  // namespace

class SyntheticPointerInjector : public IInjector {
public:
    ~SyntheticPointerInjector() override { shutdown(); }

    InjectTier tier() const override { return InjectTier::SyntheticPointer; }

    InjectCaps caps() const override {
        InjectCaps c{};
        c.tier = InjectTier::SyntheticPointer;
        c.multiTouch = true;
        c.pressure = true;
        c.tilt = true;
        c.hover = true;
        c.needsUiAccess = true;
        c.detail = "真触摸/笔；需 uiAccess + 签名的系统目录二进制";
        return c;
    }

    bool init(const InjectTarget& target) override {
        target_ = target;

        if (!user32_.load("user32.dll")) { lastError_ = "无法加载 user32.dll"; return false; }
        create_    = user32_.symbol<CreateSyntheticPointerDeviceFn>("CreateSyntheticPointerDevice");
        inject_    = user32_.symbol<InjectSyntheticPointerInputFn>("InjectSyntheticPointerInput");
        destroy_   = user32_.symbol<DestroySyntheticPointerDeviceFn>("DestroySyntheticPointerDevice");
        if (!create_ || !inject_) {
            lastError_ = "系统不支持 InjectSyntheticPointerInput（需 Windows 10 1809+）";
            return false;
        }

        touchDevice_ = create_(PT_TOUCH, kMaxContacts, POINTER_FEEDBACK_DEFAULT);
        if (!touchDevice_) {
            lastError_ = "CreateSyntheticPointerDevice(PT_TOUCH) 失败 GetLastError=" +
                         std::to_string(GetLastError()) +
                         "（需 uiAccess=true 且二进制签名并位于系统目录）";
            return false;
        }
        penDevice_ = create_(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT);
        if (!penDevice_) {
            APX_LOG_W("CreateSyntheticPointerDevice(PT_PEN) 失败，笔输入不可用（GetLastError=%lu）",
                      static_cast<unsigned long>(GetLastError()));
        }

        ready_ = true;
        APX_LOG_I("档2 注入就绪：触摸设备=%p 笔设备=%p", static_cast<void*>(touchDevice_),
                  static_cast<void*>(penDevice_));
        return true;
    }

    bool inject(const TouchFrame& frame) override {
        if (!ready_) { lastError_ = "未 init"; return false; }

        std::vector<POINTER_TYPE_INFO> infos;
        infos.reserve(frame.contacts.size() + 1);
        const uint32_t frameId = ++frameIdCounter_;

        // ---- 触点 ----
        std::unordered_map<uint32_t, bool> seen;
        for (const auto& c : frame.contacts) {
            const uint32_t pid = toPointerId(c.id);
            seen[pid] = true;
            auto p = mapToVirtualScreen(c.x, c.y, target_);

            POINTER_TYPE_INFO info{};
            info.type = PT_TOUCH;
            info.touchInfo.pointerInfo.pointerType = PT_TOUCH;
            info.touchInfo.pointerInfo.pointerId = pid;
            info.touchInfo.pointerInfo.frameId = frameId;
            info.touchInfo.pointerInfo.ptPixelLocation.x = p.x;
            info.touchInfo.pointerInfo.ptPixelLocation.y = p.y;

            const bool wasDown = active_[pid];
            // 常量名以 winuser.h 为准：是 POINTER_FLAG_INRANGE / POINTER_FLAG_INCONTACT，
            // 中间**没有下划线**。写成 IN_RANGE 会直接编译不过。
            uint32_t flags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT;
            if (!wasDown) flags |= POINTER_FLAG_DOWN;
            else          flags |= POINTER_FLAG_UPDATE;
            info.touchInfo.pointerInfo.pointerFlags = flags;

            info.touchInfo.touchMask = TOUCH_MASK_CONTACTAREA | TOUCH_MASK_ORIENTATION | TOUCH_MASK_PRESSURE;
            constexpr int kContactRadius = 4;
            info.touchInfo.rcContact.left   = p.x - kContactRadius;
            info.touchInfo.rcContact.top    = p.y - kContactRadius;
            info.touchInfo.rcContact.right  = p.x + kContactRadius;
            info.touchInfo.rcContact.bottom = p.y + kContactRadius;
            info.touchInfo.orientation = 0;
            info.touchInfo.pressure = static_cast<UINT32>(
                std::min<uint32_t>(c.pressure, 65535u) * 1024u / 65535u);  // 0..1024

            active_[pid] = true;
            infos.push_back(info);
        }

        // ---- 已抬起（上一帧在、这一帧不在）的触点补 UP ----
        for (auto it = active_.begin(); it != active_.end();) {
            const uint32_t pid = it->first;
            if (seen[pid]) { ++it; continue; }
            POINTER_TYPE_INFO info{};
            info.type = PT_TOUCH;
            info.touchInfo.pointerInfo.pointerType = PT_TOUCH;
            info.touchInfo.pointerInfo.pointerId = pid;
            info.touchInfo.pointerInfo.frameId = frameId;
            info.touchInfo.pointerInfo.pointerFlags = POINTER_FLAG_UP;
            auto last = lastPos_[pid];
            info.touchInfo.pointerInfo.ptPixelLocation.x = last.x;
            info.touchInfo.pointerInfo.ptPixelLocation.y = last.y;
            infos.push_back(info);
            it = active_.erase(it);
        }

        for (const auto& c : frame.contacts) {
            lastPos_[toPointerId(c.id)] = mapToVirtualScreen(c.x, c.y, target_);
        }

        if (!infos.empty() && !inject_(touchDevice_, infos.data(), static_cast<UINT32>(infos.size()))) {
            lastError_ = "InjectSyntheticPointerInput(TOUCH) 失败 GetLastError=" + std::to_string(GetLastError());
            return false;
        }

        // ---- 笔 ----
        if (frame.hasPen && penDevice_) {
            const uint16_t nx = frame.contacts.empty() ? 0 : frame.contacts[0].x;
            const uint16_t ny = frame.contacts.empty() ? 0 : frame.contacts[0].y;
            const uint16_t np = frame.contacts.empty() ? 0 : frame.contacts[0].pressure;
            auto p = mapToVirtualScreen(nx, ny, target_);

            POINTER_TYPE_INFO pen{};
            pen.type = PT_PEN;
            pen.penInfo.pointerInfo.pointerType = PT_PEN;
            pen.penInfo.pointerInfo.pointerId = 0;
            pen.penInfo.pointerInfo.frameId = frameId;
            pen.penInfo.pointerInfo.ptPixelLocation.x = p.x;
            pen.penInfo.pointerInfo.ptPixelLocation.y = p.y;

            uint32_t flags = 0;
            if (frame.penInRange()) flags |= POINTER_FLAG_INRANGE;
            if (frame.penTipDown()) {
                flags |= POINTER_FLAG_INCONTACT;
                flags |= penDown_ ? POINTER_FLAG_UPDATE : POINTER_FLAG_DOWN;
                penDown_ = true;
            } else if (penDown_) {
                flags |= POINTER_FLAG_UP;
                penDown_ = false;
            } else {
                flags |= POINTER_FLAG_UPDATE;
            }
            // Windows 的 POINTER_FLAG 里**没有 ERASER**（USB Digitizer 的橡皮擦语义
            // 在 Win32 合成指针层没有直接对应项）。折中用 SECONDBUTTON 承载，
            // 由应用侧按笔按键自行映射；若要真橡皮擦语义，必须走 HID minidriver
            // 注入（见 pc/display/README.md 的"档 3"）。
            if (frame.penEraser()) flags |= POINTER_FLAG_SECONDBUTTON;
            if (frame.penBarrel()) flags |= POINTER_FLAG_FIRSTBUTTON;
            pen.penInfo.pointerInfo.pointerFlags = flags;

            pen.penInfo.penMask = PEN_MASK_PRESSURE | PEN_MASK_ROTATION | PEN_MASK_TILT_X | PEN_MASK_TILT_Y;
            pen.penInfo.pressure = np;
            pen.penInfo.rotation = frame.pen.orientation;
            pen.penInfo.tiltX = frame.pen.tiltX;
            pen.penInfo.tiltY = frame.pen.tiltY;

            if (!inject_(penDevice_, &pen, 1)) {
                lastError_ = "InjectSyntheticPointerInput(PEN) 失败 GetLastError=" + std::to_string(GetLastError());
                return false;
            }
        }
        return true;
    }

    void shutdown() override {
        if (destroy_) {
            if (touchDevice_) { destroy_(touchDevice_); touchDevice_ = nullptr; }
            if (penDevice_)   { destroy_(penDevice_); penDevice_ = nullptr; }
        }
        active_.clear();
        lastPos_.clear();
        ready_ = false;
    }

    std::string lastError() const override { return lastError_; }

private:
    DynLib user32_;
    CreateSyntheticPointerDeviceFn create_ = nullptr;
    InjectSyntheticPointerInputFn inject_ = nullptr;
    DestroySyntheticPointerDeviceFn destroy_ = nullptr;

    HSYNTHETICPOINTERDEVICE touchDevice_ = nullptr;
    HSYNTHETICPOINTERDEVICE penDevice_ = nullptr;

    InjectTarget target_{};
    std::unordered_map<uint32_t, bool> active_;
    // 必须用 MappedPoint（mapToVirtualScreen 的返回类型），不能用 Windows 的 POINT ——
    // 两者不通用，写成 POINT 会得到 "no viable overloaded '='"。
    std::unordered_map<uint32_t, MappedPoint> lastPos_;
    uint32_t frameIdCounter_ = 0;
    bool penDown_ = false;
    bool ready_ = false;
    std::string lastError_;
};

std::unique_ptr<IInjector> createSyntheticPointerInjector() {
    return std::make_unique<SyntheticPointerInjector>();
}

}  // namespace apxdisp

#endif  // _WIN32
