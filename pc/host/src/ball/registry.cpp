// 注册表实现 + 首批动作清单。纯标准库（跨平台、可离线单测）。
#include "registry.hpp"

#include <memory>

namespace apxpc {
namespace ball {

namespace {
Registry& mutableRegistry() { static Registry r; return r; }

// 钩子安全包装：装配方可能留空（没连音箱栈/没对端）
bool callBool(const std::function<bool()>& f) { return f && f(); }
void callVoid(const std::function<void()>& f) { if (f) f(); }
} // namespace

void Registry::add(const Action& a) {
    for (auto& x : s_actions_) {
        if (x.id == a.id) { x = a; return; }   // 原位覆盖，顺序不变
    }
    s_actions_.push_back(a);
}

void Registry::remove(const std::string& id) {
    for (auto it = s_actions_.begin(); it != s_actions_.end(); ++it) {
        if (it->id == id) { s_actions_.erase(it); return; }
    }
}

void Registry::clear() { s_actions_.clear(); }

bool Registry::has(const std::string& id) const { return find(id) != nullptr; }

const Action* Registry::find(const std::string& id) const {
    for (const auto& a : s_actions_) {
        if (a.id == id) return &a;
    }
    return nullptr;
}

const Action* Registry::get(const std::string& id) const { return find(id); }

namespace {
std::vector<const Action*> bySlot(const std::vector<Action>& all, Slot s) {
    std::vector<const Action*> out;
    for (const auto& a : all) {
        if (a.slot == s) out.push_back(&a);
    }
    return out;
}
} // namespace

std::vector<const Action*> Registry::highPriority() const { return bySlot(s_actions_, Slot::HIGH); }
std::vector<const Action*> Registry::cardPriority() const { return bySlot(s_actions_, Slot::CARD); }
std::vector<const Action*> Registry::morePriority() const { return bySlot(s_actions_, Slot::MORE); }

// 全部动作（按注册序）—— 球壳扇形按此渲染：自定义顺序 = 注册顺序 = 扇形顺序。
std::vector<const Action*> Registry::all() const {
    std::vector<const Action*> out;
    out.reserve(s_actions_.size());
    for (const auto& a : s_actions_) out.push_back(&a);
    return out;
}

const Registry& registry() { return mutableRegistry(); }

void clearActions() { mutableRegistry().clear(); }

// 可供用户勾选的动作目录（面板「功能自定义」的清单来源）。
// id 与下面 installDefaultActions 注册的 id 必须同源 —— 改一处记得改另一处。
// 顺序 = 面板清单的展示顺序 = 默认扇形顺序（用户没自定义时）。
std::vector<ActionInfo> actionCatalog() {
    return {
        {"open_panel",     "🖥", "开面板"},
        {"send_clipboard", "📋", "发剪贴板"},
        {"phone_input",    "⌨", "手机输入"},
        {"send_file",      "📎", "发文件"},
        {"toggle_display", "🖥", "副屏"},
        {"toggle_speaker", "🔊", "音箱"},
        {"toggle_mic",     "🎙", "麦克风"},
        {"toggle_camera",  "📷", "摄像头"},
        {"wireless",       "📶", "无线"},
        {"exit",           "⏻", "退出"},
    };
}

void installDefaultActions(const HostActions& host, const std::vector<std::string>& order) {
    auto h = std::make_shared<HostActions>(host);   // 各动作 lambda 共享一份钩子
    clearActions();
    auto& r = mutableRegistry();

    // 全部候选动作（默认顺序 = 声明顺序）。先攒起来再按用户配置过滤/重排 ——
    // 「自定义顺序」只在配置里维护一处，不必在注册表里写两遍。
    std::vector<Action> all;

    // —— HIGH：扇形第一档（高频 4 项，注册序 = 扇形排布序）
    all.push_back(Action{
        .id = "open_panel", .icon = "🖥", .label = "开面板", .slot = Slot::HIGH,
        .badge_fn = {},
        .enabled_fn = {},
        .run_fn = [h] { callVoid(h->open_panel); },
    });
    all.push_back(Action{
        .id = "send_clipboard", .icon = "📋", .label = "发剪贴板", .slot = Slot::HIGH,
        // 待发角标常驻球上：有未发内容时球缘亮环
        .badge_fn = [h] { return callBool(h->clipboard_pending); },
        // 没有对端就没得发 → 灰显
        .enabled_fn = [h] { return callBool(h->connected) && static_cast<bool>(h->send_clipboard); },
        .run_fn = [h] { (void)callBool(h->send_clipboard); },   // false 的 toast 由 M3 桥接接
    });
    all.push_back(Action{
        .id = "phone_input", .icon = "⌨", .label = "手机输入", .slot = Slot::HIGH,
        .badge_fn = {},
        // 手机当键盘：要控制面在（0x25 走 9511 控制通道）
        .enabled_fn = [h] { return callBool(h->connected) && static_cast<bool>(h->phone_input); },
        .run_fn = [h] { callVoid(h->phone_input); },
    });
    all.push_back(Action{
        .id = "send_file", .icon = "📎", .label = "发文件", .slot = Slot::HIGH,
        .badge_fn = {},
        .enabled_fn = [h] { return callBool(h->connected) && static_cast<bool>(h->send_file); },
        .run_fn = [h] { callVoid(h->send_file); },
    });

    // —— CARD：卡片第二档（Win 球壳按注册序与 HIGH 一起进扇形；mac 侧仍是内弧）
    // 副屏的先决条件是 Sunshine 在跑（面板里装的），不是「连上手机」；其余三项都看连没连。
    all.push_back(Action{
        .id = "toggle_display", .icon = "🖥", .label = "副屏", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->display_on); },
        .enabled_fn = [h] { return callBool(h->sunshine_running) && static_cast<bool>(h->toggle_display); },
        .run_fn = [h] { (void)callBool(h->toggle_display); },
    });
    all.push_back(Action{
        .id = "toggle_speaker", .icon = "🔊", .label = "音箱", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->speaker_on); },
        .enabled_fn = [h] { return callBool(h->connected) && static_cast<bool>(h->toggle_speaker); },
        .run_fn = [h] { (void)callBool(h->toggle_speaker); },
    });
    all.push_back(Action{
        .id = "toggle_mic", .icon = "🎙", .label = "麦克风", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->mic_on); },
        .enabled_fn = [h] { return callBool(h->connected) && static_cast<bool>(h->toggle_mic); },
        .run_fn = [h] { (void)callBool(h->toggle_mic); },
    });
    all.push_back(Action{
        .id = "toggle_camera", .icon = "📷", .label = "摄像头", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->camera_on); },
        .enabled_fn = [h] { return callBool(h->connected) && static_cast<bool>(h->toggle_camera); },
        .run_fn = [h] { (void)callBool(h->toggle_camera); },
    });
    all.push_back(Action{
        // 无线：连上时点它 = 断开；未连时点它 = 重新发起自动发现（面板概览页同一条链路）。
        // 只在「宿主没装这个钩子」时灰显 —— 不看连接状态：连不上时用户正需要点它重试。
        // 断开只断本机一侧、不推 0x10（手机侧模块开关）—— 推「wireless=off」会让手机停止
        // 广播，之后 PC 再 startAuto 就永远发现不了它。面板概览页那个开关是另一套语义。
        .id = "wireless", .icon = "📶", .label = "无线", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->connected); },
        .enabled_fn = [h] { return static_cast<bool>(h->toggle_session); },
        .run_fn = [h] { (void)callBool(h->toggle_session); },
    });
    all.push_back(Action{
        .id = "exit", .icon = "⏻", .label = "退出", .slot = Slot::CARD,
        .badge_fn = {},
        .enabled_fn = {},
        .run_fn = [h] { callVoid(h->exit_app); },
    });

    // 用户自定义（config.json 的 ballActions）：非空 = 白名单 + 排布顺序。
    // 顺序即注册顺序即扇形顺序 —— Slot 只是默认分组，自定义之后不再拆 HIGH/CARD。
    if (!order.empty()) {
        std::vector<Action> sel;
        for (auto id : order) {
            // 老配置兼容：conn_status（旧「连接」项，点开面板）已被 wireless 取代，
            // 直接映射过去 —— 否则升级后老用户的自定义清单会少一项、扇形看着「少了个功能」。
            if (id == "conn_status") id = "wireless";
            for (const auto& a : all) {
                if (a.id == id) { sel.push_back(a); break; }
            }
        }
        all = std::move(sel);
    }
    for (const auto& a : all) r.add(a);
}

} // namespace ball
} // namespace apxpc
