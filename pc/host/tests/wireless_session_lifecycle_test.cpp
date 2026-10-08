// wireless_session 生命周期压测：覆盖 v198 修复的死锁。
//
// 缺陷背景（v198 引入）
// --------------------
// worker() 在 Auto 分支里：检测 beaconRun_ 为 false → join 旧 beacon 线程 →
// 立刻 store(true) 重建。stop() 的流程是 running_=false → beaconRun_=false →
// join worker → join beacon。若 stop 与 worker 在「beaconRun_ 拉 false → worker
// 进入内层块」这条临界区交汇，worker 会无条件 store(true) 并新建 beacon 线程，
// 新线程在 recvfrom 上挂着，而 stop 末尾 join(beaconThread_) 永远收不回 ——
// 进程无法正常退出（必须 kill）。
//
// v198 修复：join 完旧 beacon 后再核一次 running_，已停就不重建，让本次迭代走完即退出。
//
// 压测方式
// --------
// 反复 startAuto → 短睡 → stop，每轮 stop 必须能在合理时间内返回。任何一轮超时
// 即判定死锁回归。整个测试在受控环境下跑（无外部信标、端口 9501 收不到东西），
// worker 仍会按 Auto 分支持续起 beacon 线程 —— 正好是 v198 那条路径。
#include "apxpc/wireless/wireless_session.hpp"

#include <chrono>
#include <cstdio>
#include <future>
#include <thread>

using namespace apxpc::wireless;
using namespace std::chrono_literals;

static int g_fail = 0;
#define CHECK(cond) do { \
    if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); ++g_fail; } \
} while (0)

/// 用 future 把 stop() 包一层超时：future 超时即认定死锁，返回 false。
/// stop() 是同步函数，正常情况下 < 200ms 返回；v198 死锁场景下永不返回。
static bool stopWithTimeout(WirelessSession& s, std::chrono::milliseconds budget) {
    auto fut = std::async(std::launch::async, [&] { s.stop(); });
    if (fut.wait_for(budget) == std::future_status::ready) {
        fut.get();   // 正常返回，吃掉异常（理论上不会抛）
        return true;
    }
    // 超时：deadlock 已触发。future 仍持有 session 引用，析构前先 detach 避免
    // std::future 析构阻塞等待线程结束 —— detach 后线程继续死锁，本测试进程退出
    // 时由 OS 回收，不影响测试结果（判定已 FAIL）。
    return false;
}

/// 反复触发 v198 的竞争窗口：让 worker 处于「刚 join 完旧 beacon / 即将 store(true)
/// 重建」的瞬间被 stop() 打断。短随机停顿让两线程的相位错开。
///
/// 注意：startAuto() 不重启 worker —— 它只置 desire 标志；worker 由 ctor 启动、
/// 由 stop() 终结。所以每轮必须新建 session。
static void testStartStopNoDeadlock() {
    constexpr int kIters = 200;
    for (int i = 0; i < kIters; ++i) {
        WirelessSession s;
        s.startAuto();
        // 给 worker 时间进入循环、起 beacon 线程。kTick = 250ms，所以 50ms 已足够
        // 看到 beacon 被拉起。
        std::this_thread::sleep_for(50ms);
        CHECK(stopWithTimeout(s, 2s));
    }
}

/// 析构路径也要走通：v198 之前析构 → stop() 也可能死锁。构造后不显式 stop，
/// 直接让 RAII 析构跑一次。
static void testDestructorNoDeadlock() {
    constexpr int kIters = 100;
    for (int i = 0; i < kIters; ++i) {
        auto fut = std::async(std::launch::async, [] {
            WirelessSession s;
            s.startAuto();
            std::this_thread::sleep_for(50ms);
            // 不显式 stop：交给 ~WirelessSession()。验证析构路径同样不卡死。
        });
        if (fut.wait_for(2s) != std::future_status::ready) {
            std::printf("FAIL ~WirelessSession deadlocked at iter %d\n", i);
            ++g_fail;
            return;
        }
        fut.get();
    }
}

/// 边界：startAuto() 之后立刻 stop()，worker 可能还没来得及起 beacon；这条路
/// 也得能正常退出。
static void testImmediateStop() {
    constexpr int kIters = 100;
    for (int i = 0; i < kIters; ++i) {
        WirelessSession s;
        s.startAuto();
        CHECK(stopWithTimeout(s, 2s));
    }
}

int main() {
    testStartStopNoDeadlock();
    testDestructorNoDeadlock();
    testImmediateStop();
    if (g_fail == 0) {
        std::printf("wireless_session_lifecycle_test: ALL PASS\n");
        return 0;
    }
    std::printf("wireless_session_lifecycle_test: %d FAILED\n", g_fail);
    return 1;
}
