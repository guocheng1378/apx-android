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

// 键鼠镜像工作模式（配置/面板/捕获器三处共用；落盘为 int，向后兼容见 app_config）。
enum WorkMode : int {
    kWorkMirror = 0,   // 镜像同步（默认）：转发 + 本机照常响应
    kWorkCross = 1,    // 跨屏独占：右缘抵住推入切换，聚焦期间电脑不响应
    kWorkShared = 2,   // 共享：右缘滑过即切（无需驻留），像扩展屏一样自然
};

class LocalInputCapturer {
public:
    virtual ~LocalInputCapturer() = default;
    /// 开始捕获并转发到 client_。成功返回 true。
    virtual bool start() = 0;
    virtual void stop() {}
    bool active() const { return active_; }

    /// 键鼠镜像工作模式（issue#171 报告人「跨屏扩展」需求，2026-11-24；共享模式
    /// 2026-11-26 随报告人「共享模式移动不到 pad」反馈补齐）：
    ///   kWorkMirror  = 镜像同步（默认）：本机事件转发给手机，同时本机照常响应（现状）。
    ///   kWorkCross   = 跨屏独占：鼠标抵住屏幕右缘推入约 1/4 秒把焦点切到手机 —— 此后
    ///           本机事件被吞掉（电脑不再响应），独占转发给手机；往回拉切回电脑。
    ///   kWorkShared  = 共享：与独占同一套聚焦转发状态机，但切换手势更自然 ——
    ///           鼠标滑过屏幕右缘即切到手机（无需驻留），向左拉切回，像扩展屏一样。
    /// 平台实现：Windows/macOS 支持吞事件；Linux evdev 只读吞不掉，默认空实现
    /// 保持镜像行为（跨屏/共享模式在 Linux 上不生效，面板不隐藏开关但行为不变）。
    virtual void setWorkMode(int mode) {
        (void)mode;
    }

protected:
    bool active_ = false;
};

/// 按编译目标创建对应平台的捕获器（失败返回 nullptr）。
/// client 必须在捕获器生命周期内保持有效（转发目标）。
std::unique_ptr<LocalInputCapturer> createPlatformCapturer(Ctrl9511Client& client);

}  // namespace apxpc::wireless
