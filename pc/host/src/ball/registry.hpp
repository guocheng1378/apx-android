// 动作注册表：球的扇形菜单/卡片由它渲染，注册表决定球上出现什么。
// 规则（与规划 M1 一致）：
//   · 高频 4 项（单击扇形第一档）：开面板 / 发剪贴板 / 音箱开关 / 退出
//   · 带状态项（卡片第二档）：麦克风、副屏、连接状态、剪贴板待发角标
//   · 重交互（文件浏览/日志/设置）不进球：按需 apxhost panel 浏览器页
//   · 新增功能 = 加一行注册 + bridge 一个 act，球本体零改动
//
// 动作 ID 与 bridge.ts 的 act 命名同源；与手机端球动作 ID 对齐（低栏长按常用功能同名）。
#pragma once

#include <string>
#include <functional>
#include <vector>
#include <memory>
#include <unordered_map>

namespace apxpc {
namespace ball {

struct Action {
    std::string id;
    std::string icon;      // emoji / 小图标
    std::string label;
    std::function<bool()> badge_fn;   // 可选状态角标：true 时球缘亮环 / 托盘角标
    std::function<bool()> enabled_fn; // 可选：按状态灰掉（如未连接时禁用发剪贴板）
    std::function<void()> run_fn;
};

// 三档排布
enum class Slot { HIGH, CARD, MORE };  

struct ActionMeta {
    std::string id;
    Slot slot = Slot::HIGH;
};

class Registry {
public:
    // 注册动作；重复 id 会覆盖（设计如此：更新动作 = 改注册）
    void add(const Action& a);
    void remove(const std::string& id);
    bool has(const std::string& id) const;
    const Action* find(const std::string& id) const;
    
    // 按档位拉取注册表（前端渲染球菜单时调用）
    std::vector<const Action*> highPriority() const;
    std::vector<const Action*> cardPriority() const;
    std::vector<const Action*> morePriority() const;
    
    // 查询式接口（badge_fn / enabled_fn 按 id 拉取）
    const Action* get(const std::string& id) const;

private:
    std::unordered_map<std::string, Action> s_actions;
};

extern const Registry& registry();

} // namespace ball
} // namespace apxpc
