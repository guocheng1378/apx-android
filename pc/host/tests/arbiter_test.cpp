// 带宽仲裁纯逻辑单测（离线可跑，无设备/无网络依赖）。
// 覆盖：优先级保序、预算内不降级、超预算压缩与关停、降级标记。
#include "apxpc/bandwidth/arbiter.hpp"

#include <cstdio>

using namespace apxpc::bandwidth;

static int g_fail = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); ++g_fail; } } while (0)

static void testWithinBudget() {
    BandwidthArbiter a;
    a.setMode("wired");
    a.setBudget(300, 80);
    a.setDemand("touch", Prio::TouchUp, 2, true);
    a.setDemand("hid", Prio::HidSensor, 1, true);
    a.setDemand("video", Prio::DisplayVideo, 12, true);
    auto r = a.compute();
    CHECK(r.items.size() == 3);
    CHECK(r.usedMbps == 15);
    for (auto& it : r.items) CHECK(!it.degraded);
}

static void testOverBudgetWireless() {
    BandwidthArbiter a;
    a.setMode("wireless");
    a.setBudget(300, 20);   // WiFi 预算 20Mbps
    a.setDemand("touch", Prio::TouchUp, 2, true);        // 保
    a.setDemand("hid", Prio::HidSensor, 1, true);         // 保
    a.setDemand("video", Prio::DisplayVideo, 12, true);   // 3+12=15 保
    a.setDemand("audio", Prio::Audio, 12, true);          // 15+12>20 -> 压缩到 5
    a.setDemand("cam", Prio::UvcCamera, 18, true);        // 关停
    auto r = a.compute();
    CHECK(r.totalMbps == 20);
    CHECK(r.usedMbps <= 20.0 + 1e-9);
    // 顺序按优先级：touch, hid, video, audio, cam
    CHECK(r.items.size() == 5);
    CHECK(r.items[0].name == "touch" && !r.items[0].degraded);
    CHECK(r.items[1].name == "hid" && !r.items[1].degraded);
    CHECK(r.items[2].name == "video" && !r.items[2].degraded);
    CHECK(r.items[3].name == "audio" && r.items[3].degraded);
    CHECK(r.items[4].name == "cam" && r.items[4].degraded && r.items[4].mbps == 0);
}

static void testInactiveIgnored() {
    BandwidthArbiter a;
    a.setBudget(10, 10);
    a.setDemand("video", Prio::DisplayVideo, 100, false); // 未启用
    auto r = a.compute();
    CHECK(r.items.empty());
    CHECK(r.usedMbps == 0);
}

static void testModeSwitch() {
    BandwidthArbiter a;
    a.setBudget(300, 40);
    a.setDemand("video", Prio::DisplayVideo, 60, true);
    a.setMode("wired");
    CHECK(a.compute().items[0].degraded == false);   // 300 足够
    a.setMode("wireless");
    CHECK(a.compute().items[0].degraded == true);    // 40 < 60 -> 压缩
}

int main() {
    testWithinBudget();
    testOverBudgetWireless();
    testInactiveIgnored();
    testModeSwitch();
    if (g_fail == 0) { std::printf("arbiter_test: ALL PASS\n"); return 0; }
    std::printf("arbiter_test: %d FAILED\n", g_fail);
    return 1;
}
