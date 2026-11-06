// 桌面球动作注册表：扇形上「有什么、什么顺序、能不能点」全由它决定。
//
// 盯的是三类回归（都是「不会崩、只会静默变傻」的那种）：
//   ① actionCatalog()（面板「功能自定义」的清单）与 installDefaultActions()（真正注册的）
//      不同源 —— 面板里勾得到、球上没有（或反过来），用户看到的就是「加了这个功能没效果」；
//   ② 钩子留空却装了死按钮 —— 点了没反应，用户以为球坏了；
//   ③ 老配置的 id 蒸发（conn_status → wireless）—— 自定义清单少一项。
//
// 纯标准库，不链任何库、不起线程，两端（Win/mac）可跑。
#include "registry.hpp"

#include <cstdio>
#include <string>
#include <vector>

using namespace apxpc::ball;

static int g_fail = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); ++g_fail; } } while (0)

namespace {

std::vector<std::string> installedIds() {
    std::vector<std::string> out;
    for (const Action* a : registry().all()) out.push_back(a->id);
    return out;
}

bool enabledOf(const std::string& id) {
    const Action* a = registry().find(id);
    return a && (!a->enabled_fn || a->enabled_fn());
}

bool badgeOf(const std::string& id) {
    const Action* a = registry().find(id);
    return a && a->badge_fn && a->badge_fn();
}

// 一个「全都连上、各栈都在跑」的宿主：用来验证点亮路径
struct FakeHost {
    bool connected = true;
    bool clipboardPending = true;
    bool sunshine = true;
    int opened = 0, sentClipboard = 0, phoneInput = 0, sentFile = 0, exited = 0;
    int speakerToggles = 0, micToggles = 0, displayToggles = 0, cameraToggles = 0, sessionToggles = 0;
    bool speaker = false, mic = false, camera = false;

    HostActions make() {
        HostActions h;
        h.open_panel = [this] { ++opened; };
        h.send_clipboard = [this] { ++sentClipboard; return true; };
        h.phone_input = [this] { ++phoneInput; };
        h.send_file = [this] { ++sentFile; };
        h.toggle_speaker = [this] { speaker = !speaker; ++speakerToggles; return speaker; };
        h.toggle_mic = [this] { mic = !mic; ++micToggles; return mic; };
        h.toggle_display = [this] { ++displayToggles; return true; };
        h.toggle_camera = [this] { camera = !camera; ++cameraToggles; return camera; };
        h.toggle_session = [this] { ++sessionToggles; return false; };
        h.exit_app = [this] { ++exited; };
        h.connected = [this] { return connected; };
        h.clipboard_pending = [this] { return clipboardPending; };
        h.speaker_on = [this] { return speaker; };
        h.mic_on = [this] { return mic; };
        h.display_on = [this] { return true; };
        h.sunshine_running = [this] { return sunshine; };
        h.camera_on = [this] { return camera; };
        return h;
    }
};

// ① 清单同源：面板里能勾的 == 球上默认装的（顺序也要一致，顺序即扇形排布）
void testCatalogMatchesInstall() {
    installDefaultActions(HostActions{});
    const std::vector<std::string> got = installedIds();
    std::vector<std::string> want;
    for (const auto& info : actionCatalog()) want.push_back(info.id);

    CHECK(got.size() == want.size());
    for (size_t i = 0; i < got.size() && i < want.size(); ++i) CHECK(got[i] == want[i]);

    // 用户点名要的常用项必须在
    for (const char* id : { "open_panel", "send_clipboard", "phone_input", "send_file",
                            "toggle_display", "toggle_speaker", "toggle_mic",
                            "toggle_camera", "wireless", "exit" }) {
        CHECK(registry().has(id));
    }
    clearActions();
}

// ② 钩子全空：只有「不需要宿主」的项可点，其余灰显（而不是点了没反应）
void testEmptyHostGreysOut() {
    installDefaultActions(HostActions{});
    CHECK(enabledOf("open_panel"));   // 无 enabled_fn = 恒可用
    CHECK(enabledOf("exit"));
    for (const char* id : { "send_clipboard", "phone_input", "send_file", "toggle_display",
                            "toggle_speaker", "toggle_mic", "toggle_camera", "wireless" }) {
        CHECK(!enabledOf(id));
    }
    // 灰显项的 run_fn 调了也不该乱蹦（钩子空 → callBool/callVoid 安全空转）
    for (const Action* a : registry().all())
        if (a->run_fn) a->run_fn();
    clearActions();
}

// ③ 钩子齐了：全部点亮 + 角标跟着状态走
void testHooksLightUpAndBadge() {
    FakeHost host;
    installDefaultActions(host.make());
    for (const Action* a : registry().all()) CHECK(enabledOf(a->id));

    CHECK(badgeOf("send_clipboard"));   // 有未发内容
    CHECK(!badgeOf("phone_input"));     // 无状态
    CHECK(badgeOf("wireless"));         // 已连接
    CHECK(!badgeOf("toggle_speaker"));  // 还没开
    CHECK(!badgeOf("toggle_camera"));

    // 跑一遍：每个动作都该真的打到宿主钩子上（ID 与钩子接错这里就露）
    for (const Action* a : registry().all())
        if (a->run_fn) a->run_fn();
    CHECK(host.opened == 1);
    CHECK(host.sentClipboard == 1);
    CHECK(host.phoneInput == 1);
    CHECK(host.sentFile == 1);
    CHECK(host.displayToggles == 1);
    CHECK(host.speakerToggles == 1);
    CHECK(host.micToggles == 1);
    CHECK(host.cameraToggles == 1);
    CHECK(host.sessionToggles == 1);
    CHECK(host.exited == 1);

    // 开过之后角标要亮
    CHECK(badgeOf("toggle_speaker"));
    CHECK(badgeOf("toggle_mic"));
    CHECK(badgeOf("toggle_camera"));

    // 副屏项的先决条件是 Sunshine 在跑（不是连没连）
    host.sunshine = false;
    CHECK(!enabledOf("toggle_display"));
    // 断链后除副屏/退出外全灰
    host.connected = false;
    host.sunshine = true;
    CHECK(!enabledOf("send_clipboard"));
    CHECK(!enabledOf("phone_input"));
    CHECK(!enabledOf("send_file"));
    CHECK(!enabledOf("toggle_camera"));
    CHECK(enabledOf("wireless"));   // 连不上时正需要点它重试 → 不许灰
    CHECK(!badgeOf("wireless"));
    clearActions();
}

// ④ 自定义：白名单 + 顺序（顺序即扇形顺序）
void testCustomOrder() {
    installDefaultActions(HostActions{}, { "exit", "wireless", "open_panel" });
    const std::vector<std::string> got = installedIds();
    CHECK(got.size() == 3);
    if (got.size() == 3) {
        CHECK(got[0] == "exit");
        CHECK(got[1] == "wireless");
        CHECK(got[2] == "open_panel");
    }
    clearActions();
}

// ⑤ 老配置兼容：conn_status（已并入 wireless）不得让清单静默少一项
void testLegacyConnStatusAlias() {
    installDefaultActions(HostActions{}, { "conn_status", "open_panel" });
    const std::vector<std::string> got = installedIds();
    CHECK(got.size() == 2);
    if (got.size() == 2) {
        CHECK(got[0] == "wireless");
        CHECK(got[1] == "open_panel");
    }
    clearActions();
}

// ⑥ 未知 id 不装（配置里留了删掉的功能，不能变成空白项）
void testUnknownIdIgnored() {
    installDefaultActions(HostActions{}, { "no_such_action", "exit" });
    const std::vector<std::string> got = installedIds();
    CHECK(got.size() == 1);
    if (!got.empty()) CHECK(got[0] == "exit");
    clearActions();
}

}  // namespace

int main() {
    testCatalogMatchesInstall();
    testEmptyHostGreysOut();
    testHooksLightUpAndBadge();
    testCustomOrder();
    testLegacyConnStatusAlias();
    testUnknownIdIgnored();
    if (g_fail == 0) std::printf("ball_registry_test: all checks passed\n");
    return g_fail == 0 ? 0 : 1;
}
