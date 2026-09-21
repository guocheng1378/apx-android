#pragma once
// 七类全局热键：RegisterHotKey 独立消息循环；冲突检测（实际试探注册）；
// 面板内"按下即录制"的可视化改键与配置持久化。无需钩子、无需管理员。
#include <functional>
#include <map>
#include <string>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace apxpc::hotkey {

enum class ActionId {
    ScreenToggle,   // 副屏开关 / 全屏
    ScreenCycle,    // 分辨率与方向切换
    TouchpadToggle, // 触控板开关
    SensorToggle,   // 传感器启停
    CameraToggle,   // 摄像头开关
    AudioRoute,     // 音频路由
    DeviceToggle,   // 设备连接 / 断开
};

struct Binding {
    std::vector<std::string> mods; // ctrl alt shift win
    std::string key;               // F1.. F24 / 单字符 / 方向键名
    bool enabled = true;
};

struct Conflict {
    ActionId a;
    ActionId b;
    std::string combo; // 冲突的组合键文本
};

using ActionMap = std::map<ActionId, std::function<void()>>;

// 动作 id 名（与面板 / 配置一致）
const char* actionName(ActionId id);
ActionId actionFromName(const std::string& name);

class HotkeyManager {
public:
    HotkeyManager();
    ~HotkeyManager();

    // 启动：注册默认/已载入绑定；非 Windows 平台为 no-op（返回 true）
    bool start(const ActionMap& actions, const std::map<ActionId, Binding>& bindings,
               std::string* err);
    void stop();

    // 当前绑定
    const std::map<ActionId, Binding>& bindings() const { return bindings_; }

    // 冲突：仅比较用户配置的静态冲突（两动作同组合键）
    std::vector<Conflict> conflicts() const;

    // 改键：尝试注册新组合键，成功则更新；失败（被占用或非法）返回 false 并设 err
    bool rebind(ActionId id, const Binding& b, std::string* err);

    void resetDefaults();

    // 线程函数与状态供本翻译单元内的自由函数访问（内部工具类，直接公开）
    ActionMap actions_;
    std::map<ActionId, Binding> bindings_;
#if defined(_WIN32)
    void* thread_ = nullptr; // HANDLE
    DWORD tid_ = 0;
    bool running_ = false;
#endif
};

}  // namespace apxpc::hotkey
