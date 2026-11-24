#pragma once
// ============================================================================
// PC 本地输入捕获（9511 遥控器方向）
//
// 把本机鼠标/键盘事件捕获并转发给已连入的受控端（Ctrl9511Client），从而让 PC 真正
// 充当「遥控器」控手机/TV。与受控端注入对称：这里做的是 VK/MacVK/Linux键码 → HID
// usage(0x07) 的映射，再调 client.sendMouse/sendKeyboard，受控端按 usage 注入系统。
//
// 各平台实现（createPlatformCapturer 在对应 TU 定义）：
//   Windows : 低层钩子 WH_KEYBOARD_LL / WH_MOUSE_LL（无需 DLL，需消息泵）
//   macOS   : CGEventTap（kCGHIDEventTap，需辅助功能权限；事件原样回传不拦截）
//   Linux   : 读 /dev/input/event*（evdev，需 root；被动只读不拦截）
// ============================================================================
#include "apxpc/wireless/ctrl9511.hpp"
#include <memory>

namespace apxpc::wireless {

class LocalInputCapturer {
public:
    virtual ~LocalInputCapturer() = default;
    /// 开始捕获并转发到 client_。成功返回 true。
    virtual bool start() = 0;
    virtual void stop() {}
    bool active() const { return active_; }

    /// 键鼠镜像工作模式（issue#171 报告人「跨屏扩展」需求，2026-11-24）：
    ///   false = 镜像同步（默认）：本机事件转发给手机，同时本机照常响应（现状）。
    ///   true  = 跨屏独占：鼠标推到屏幕右缘把焦点切到手机 —— 此后本机事件被吞掉
    ///           （电脑不再响应），独占转发给手机；往回移过同一边缘切回电脑。
    /// 平台实现：Windows/macOS 支持吞事件；Linux evdev 只读吞不掉，默认空实现
    /// 保持镜像行为（跨屏模式在 Linux 上不生效，面板不隐藏开关但行为不变）。
    virtual void setCrossMode(bool on) {
        (void)on;
    }

protected:
    bool active_ = false;
};

/// 按编译目标创建对应平台的捕获器（失败返回 nullptr）。
/// client 必须在捕获器生命周期内保持有效（转发目标）。
std::unique_ptr<LocalInputCapturer> createPlatformCapturer(Ctrl9511Client& client);

}  // namespace apxpc::wireless
