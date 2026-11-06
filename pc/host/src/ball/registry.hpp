// 动作注册表：球的扇形菜单/卡片由它渲染，注册表决定球上出现什么。
// 规则（与规划 M1 一致）：
//   · 高频 4 项（单击扇形第一档 HIGH）：开面板 / 发剪贴板 / 手机输入 / 发文件
//   · 带状态项（卡片第二档 CARD）：副屏、音箱、麦克风、摄像头、无线、退出
//   · 重交互（文件浏览/日志/设置）不进球：按需 apxhost panel 浏览器页
//   · 新增功能 = 加一行注册 + bridge 一个 act，球本体零改动
//
// 2026-11-07 用户定调：「图标太大 + 常用功能太少」——球/项缩到 52dip、玻璃调薄（真透明），
// 并把面板里已有但球上缺席的常用项补齐（手机输入 / 发文件 / 摄像头 / 无线）。
// 新增项一律走**已有链路**（0x25 请求输入、file.send 同款 startFileSend、camera.toggle
// 同款 setPreview + 0x10、session.startAuto/disconnect），不在球里新造协议。
//
// 动作 ID 与 bridge.ts 的 act 命名同源；与手机端球动作 ID 对齐（低栏长按常用功能同名）。
#pragma once

#include <functional>
#include <string>
#include <vector>

namespace apxpc {
namespace ball {

// 三档排布：HIGH = 扇形第一档；CARD = 卡片第二档；MORE = 预留（重交互不进球）
enum class Slot { HIGH, CARD, MORE };

struct Action {
    std::string id;
    std::string icon;      // emoji / 小图标（Win32 球壳只画 label，emoji 留给 web 皮）
    std::string label;
    Slot slot = Slot::HIGH;
    std::function<bool()> badge_fn;   // 状态角标：true → 球缘亮环 / 扇形项右上绿点
    std::function<bool()> enabled_fn; // 灰显判定：false → 项灰掉且点击无效
    std::function<void()> run_fn;
};

class Registry {
public:
    // 注册动作；重复 id 原位覆盖（更新动作 = 改注册），首次注册顺序即渲染顺序
    void add(const Action& a);
    void remove(const std::string& id);
    void clear();
    bool has(const std::string& id) const;
    const Action* find(const std::string& id) const;

    // 按档位拉取（保注册序 —— 扇形/卡片渲染直接遍历它）
    std::vector<const Action*> highPriority() const;
    std::vector<const Action*> cardPriority() const;
    std::vector<const Action*> morePriority() const;

    // badge/enabled 的按 id 查询（find 的别名，语义上绑定状态钩子）
    const Action* get(const std::string& id) const;

    // 全部动作（按注册序）—— 球壳扇形按此渲染：**自定义顺序 = 注册顺序 = 扇形顺序**。
    // 为什么不按档位取：HIGH/CARD 只是默认分组，用户自定义之后档位就失去意义了。
    std::vector<const Action*> all() const;

private:
    // vector 而非 map：顺序即排布；条目 ≤ 30，线性扫足够
    std::vector<Action> s_actions_;
};

// 宿主钩子：动作「做什么」由装配方（M3：desktop_main / web_panel）提供；
// 注册表与球只做「摆盘 + 触发」，不直接依赖宿主栈 —— 保证 registry/fx 可离线单测。
// 允许留空：钩子为空的项 enabled_fn 返回 false（灰显），不装死按钮。
struct HostActions {
    // —— HIGH（扇形第一档）
    std::function<void()> open_panel;        // 唤面板（与 tray onOpen_ 同源）
    std::function<bool()> send_clipboard;     // 本机剪贴板发给手机；false = 当前无对端
    std::function<void()> phone_input;        // 让手机弹输入框（0x25，手机当键盘给本机打字）
    std::function<void()> send_file;          // 选一个文件发到手机（后台线程，进度走托盘）
    // —— CARD（卡片第二档）
    std::function<bool()> toggle_speaker;     // 手机当音箱；返回新开关态
    std::function<bool()> toggle_mic;         // 手机麦克风；返回新开关态
    std::function<bool()> toggle_display;     // 副屏；返回新开关态
    std::function<bool()> toggle_camera;      // 手机摄像头预览；返回新开关态
    std::function<bool()> toggle_session;     // 无线连接开/关；返回新开关态（true = 已连）
    std::function<void()> exit_app;           // 退出（与 tray onQuit_ 同源）
    // —— 状态查询（badge_fn / enabled_fn 的数据源，M3 接 bridge state）
    std::function<bool()> connected;          // link.phase == connected
    std::function<bool()> clipboard_pending;  // 剪贴板有待发内容
    std::function<bool()> speaker_on;
    std::function<bool()> mic_on;
    std::function<bool()> display_on;
    std::function<bool()> sunshine_running;   // Sunshine 在跑（副屏可点亮）
    std::function<bool()> camera_on;          // 摄像头预览窗在开
};

// 可供用户勾选的动作目录（面板「功能自定义」的清单来源）。
// id 与 installDefaultActions 注册的 id **必须同源** —— 改一处记得改另一处。
struct ActionInfo {
    std::string id;
    std::string icon;
    std::string label;
};
std::vector<ActionInfo> actionCatalog();

// 首批动作灌入（整体重装：先 clear 再按序 add）。
// order = 用户自定义的功能白名单 + 排布顺序（config.json 的 ballActions）：
//   · 空 = 默认全装（声明顺序）
//   · 非空 = 只装 order 里列出的，且**按 order 的顺序注册**（= 球上扇形的排布顺序）
void installDefaultActions(const HostActions& host, const std::vector<std::string>& order = {});
void clearActions();

extern const Registry& registry();

} // namespace ball
} // namespace apxpc
