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
std::vector<ActionInfo> actionCatalog() {
    return {
        {"open_panel",     "🖥", "开面板"},
        {"send_clipboard", "📋", "发剪贴板"},
        {"toggle_speaker", "🔊", "音箱"},
        {"toggle_mic",     "🎙", "麦克风"},
        {"toggle_display", "🖥", "副屏"},
        {"conn_status",    "📶", "连接"},
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
        .enabled_fn = [h] { return callBool(h->connected); },
        .run_fn = [h] { (void)callBool(h->send_clipboard); },   // false 的 toast 由 M3 桥接接
    });
    all.push_back(Action{
        .id = "toggle_speaker", .icon = "🔊", .label = "音箱", .slot = Slot::HIGH,
        .badge_fn = [h] { return callBool(h->speaker_on); },
        .enabled_fn = [h] { return callBool(h->connected); },
        .run_fn = [h] { (void)callBool(h->toggle_speaker); },
    });
    all.push_back(Action{
        .id = "exit", .icon = "⏻", .label = "退出", .slot = Slot::HIGH,
        .badge_fn = {},
        .enabled_fn = {},
        .run_fn = [h] { callVoid(h->exit_app); },
    });

    // —— CARD：卡片第二档（Win 球壳按注册序与 HIGH 一起进扇形；mac 侧仍是内弧）
    all.push_back(Action{
        .id = "toggle_mic", .icon = "🎙", .label = "麦克风", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->mic_on); },
        .enabled_fn = [h] { return callBool(h->connected); },
        .run_fn = [h] { (void)callBool(h->toggle_mic); },
    });
    all.push_back(Action{
        .id = "toggle_display", .icon = "🖥", .label = "副屏", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->display_on); },
        .enabled_fn = [h] { return callBool(h->sunshine_running); },
        .run_fn = [h] { (void)callBool(h->toggle_display); },
    });
    all.push_back(Action{
        .id = "conn_status", .icon = "📶", .label = "连接", .slot = Slot::CARD,
        .badge_fn = [h] { return callBool(h->connected); },
        .enabled_fn = {},
        .run_fn = [h] { callVoid(h->open_panel); },   // 点它 = 去面板看连接诊断
    });

    // 用户自定义（config.json 的 ballActions）：非空 = 白名单 + 排布顺序。
    // 顺序即注册顺序即扇形顺序 —— Slot 只是默认分组，自定义之后不再拆 HIGH/CARD。
    if (!order.empty()) {
        std::vector<Action> sel;
        for (const auto& id : order) {
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
