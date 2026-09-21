#include "apxpc/hotkey/hotkey.hpp"
#include "apxpc/log.hpp"

#include <cctype>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace apxpc::hotkey {

const char* actionName(ActionId id) {
    switch (id) {
        case ActionId::ScreenToggle:   return "screen.toggle";
        case ActionId::ScreenCycle:    return "screen.cycle";
        case ActionId::TouchpadToggle: return "touchpad.toggle";
        case ActionId::SensorToggle:   return "sensor.toggle";
        case ActionId::CameraToggle:   return "camera.toggle";
        case ActionId::AudioRoute:     return "audio.route";
        case ActionId::DeviceToggle:   return "device.toggle";
    }
    return "?";
}

ActionId actionFromName(const std::string& name) {
    if (name == "screen.toggle")   return ActionId::ScreenToggle;
    if (name == "screen.cycle")    return ActionId::ScreenCycle;
    if (name == "touchpad.toggle") return ActionId::TouchpadToggle;
    if (name == "sensor.toggle")   return ActionId::SensorToggle;
    if (name == "camera.toggle")   return ActionId::CameraToggle;
    if (name == "audio.route")     return ActionId::AudioRoute;
    if (name == "device.toggle")   return ActionId::DeviceToggle;
    return ActionId::ScreenToggle;
}

// 默认七类热键（与 config 默认一致）
static std::map<ActionId, Binding> makeDefaultBindings() {
    std::map<ActionId, Binding> m;
    auto mk = [](const char* k) { return Binding{{"ctrl", "alt"}, k, true}; };
    m[ActionId::ScreenToggle]   = mk("F1");
    m[ActionId::ScreenCycle]    = mk("F2");
    m[ActionId::TouchpadToggle] = mk("F3");
    m[ActionId::SensorToggle]   = mk("F4");
    m[ActionId::CameraToggle]   = mk("F5");
    m[ActionId::AudioRoute]     = mk("F6");
    m[ActionId::DeviceToggle]   = mk("F7");
    return m;
}

HotkeyManager::HotkeyManager() = default;
HotkeyManager::~HotkeyManager() { stop(); }

#if defined(_WIN32)

namespace {

int modsToFs(const std::vector<std::string>& mods) {
    int fs = 0;
    for (const auto& m : mods) {
        if (m == "ctrl") fs |= MOD_CONTROL;
        else if (m == "alt") fs |= MOD_ALT;
        else if (m == "shift") fs |= MOD_SHIFT;
        else if (m == "win") fs |= MOD_WIN;
    }
    return fs;
}

// 键名 -> VK。支持 F1..F24、单字符、方向键/特殊键。
UINT keyToVk(const std::string& key) {
    if (key.empty()) return 0;
    if (key.size() > 1) {
        if (key == "up") return VK_UP;
        if (key == "down") return VK_DOWN;
        if (key == "left") return VK_LEFT;
        if (key == "right") return VK_RIGHT;
        if (key == "space") return VK_SPACE;
        if (key == "tab") return VK_TAB;
        if (key == "esc" || key == "escape") return VK_ESCAPE;
        if (key == "enter" || key == "return") return VK_RETURN;
        if (key.size() >= 2 && std::tolower(key[0]) == 'f') {
            int n = std::atoi(key.c_str() + 1);
            if (n >= 1 && n <= 24) return VK_F1 + (n - 1);
        }
        return 0;
    }
    unsigned char c = static_cast<unsigned char>(std::toupper(key[0]));
    if (c >= 'A' && c <= 'Z') return c;
    if (c >= '0' && c <= '9') return c;
    return 0;
}

std::string comboText(const Binding& b) {
    std::string s;
    for (const auto& m : b.mods) { s += m; s += '+'; }
    s += b.key;
    return s;
}

// 隐藏窗口消息循环
DWORD WINAPI hotkeyThread(LPVOID lp) {
    HotkeyManager* self = reinterpret_cast<HotkeyManager*>(lp);
    // 线程需要自己的消息队列
    MSG msg;
    PeekMessage(&msg, nullptr, 0, 0, PM_NOREMOVE);
    while (self->running_) {
        // 这里的注册在 start() 内完成；线程只负责派发
        if (GetMessage(&msg, nullptr, 0, 0) <= 0) break;
        if (msg.message == WM_HOTKEY) {
            ActionId id = static_cast<ActionId>(msg.wParam);
            auto it = self->actions_.find(id);
            if (it != self->actions_.end()) {
                APX_LOGI("热键触发：{}", actionName(id));
                it->second();
            }
        } else {
            TranslateMessage(&msg);
            DispatchMessage(&msg);
        }
    }
    return 0;
}

}  // namespace

bool HotkeyManager::start(const ActionMap& actions,
                          const std::map<ActionId, Binding>& bindings, std::string* err) {
    actions_ = actions;
    bindings_ = bindings;
    // 若调用方未提供完整绑定，补默认
    auto def = makeDefaultBindings();
    for (auto& [id, b] : def) if (!bindings_.count(id)) bindings_[id] = b;

    running_ = true;
    thread_ = CreateThread(nullptr, 0, hotkeyThread, this, 0, &tid_);
    if (!thread_) { running_ = false; if (err) *err = "无法创建热键线程"; return false; }

    // 注册所有启用绑定；失败视为被占用（冲突），记录但不致命
    for (const auto& [id, b] : bindings_) {
        if (!b.enabled) continue;
        int fs = modsToFs(b.mods);
        UINT vk = keyToVk(b.key);
        if (vk == 0) { APX_LOGW("热键跳过（非法键 {}）: {}", b.key.c_str(), actionName(id)); continue; }
        if (!RegisterHotKey(nullptr, static_cast<int>(id), fs, vk)) {
            APX_LOGW("热键注册失败（可能已被占用）: {}", comboText(b).c_str());
        }
    }
    return true;
}

void HotkeyManager::stop() {
    if (!running_) return;
    running_ = false;
    for (const auto& [id, b] : bindings_) {
        if (b.enabled) UnregisterHotKey(nullptr, static_cast<int>(id));
    }
    if (thread_) { PostThreadMessage(tid_, WM_QUIT, 0, 0); WaitForSingleObject(thread_, 1000); CloseHandle(thread_); thread_ = nullptr; }
}

std::vector<Conflict> HotkeyManager::conflicts() const {
    std::vector<Conflict> out;
    std::vector<ActionId> ids;
    for (auto& [id, b] : bindings_) if (b.enabled) ids.push_back(id);
    for (size_t i = 0; i < ids.size(); ++i)
        for (size_t j = i + 1; j < ids.size(); ++j) {
            const auto& a = bindings_.at(ids[i]);
            const auto& b = bindings_.at(ids[j]);
            if (a.mods == b.mods && a.key == b.key) {
                out.push_back({ids[i], ids[j], ([](const Binding& x){ std::string s; for(auto&m:x.mods){s+=m;s+='+';} s+=x.key; return s; })(a)});
            }
        }
    return out;
}

bool HotkeyManager::rebind(ActionId id, const Binding& b, std::string* err) {
    int fs = modsToFs(b.mods);
    UINT vk = keyToVk(b.key);
    if (vk == 0) { if (err) *err = "非法键: " + b.key; return false; }
    // 先试注册新组合键
    if (!RegisterHotKey(nullptr, static_cast<int>(id), fs, vk)) {
        if (err) *err = "组合键已被占用: " + (b.mods.empty() ? "" : b.mods.front()) + (b.mods.empty() ? "" : "+") + b.key;
        return false;
    }
    // 撤销旧
    const auto old = bindings_.find(id);
    if (old != bindings_.end() && old->second.enabled)
        UnregisterHotKey(nullptr, static_cast<int>(id));
    bindings_[id] = b;
    APX_LOGI("热键改键成功：{} -> {}", actionName(id), comboText(b).c_str());
    return true;
}

#else

bool HotkeyManager::start(const ActionMap&, const std::map<ActionId, Binding>&, std::string*) {
    return true;
}
void HotkeyManager::stop() {}
std::vector<Conflict> HotkeyManager::conflicts() const { return {}; }
bool HotkeyManager::rebind(ActionId, const Binding&, std::string*) { return false; }

#endif

void HotkeyManager::resetDefaults() { bindings_ = makeDefaultBindings(); }

}  // namespace apxpc::hotkey
