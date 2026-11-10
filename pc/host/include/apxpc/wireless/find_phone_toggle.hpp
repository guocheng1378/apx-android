// 查找手机「再点停止」助手（0x38，ROADMAP §5.1 ② 遗留接线，2026-11-11）。
//
// 手机端响铃 30s 自动停；PC 托盘菜单是常驻单项，此前只会发「响铃」，用户想提前
// 停只能等 30s 或在手机上点掉。本助手把同一菜单项变成时间窗切换：点第一次发响铃(1)，
// 35s 内再点发停止(0)——窗口比 30s 略宽，避免卡着自动停的边沿点成了二次响铃。
// 只在真正发送成功时记录/清除时间戳：没连手机时连点两次不会把第二次误判成「停止」。
#pragma once

#include <chrono>
#include <functional>

namespace apxpc::wireless {

/// 按时间窗在响铃 / 停止之间切换发送。send(action) 返回是否真正发出（各链路的
/// sendFindPhone 已含连接与能力门控），返回 false 时不改变窗口状态。
inline void sendFindPhoneToggle(const std::function<bool(uint8_t)>& send) {
    using clock = std::chrono::steady_clock;
    // 两处调用点（桌面壳 session→ctrlSrv 择活 / 宿主服务 ctrlSrv）共用一枚静态戳：
    // 同一时刻只有一条链路活着，不会互相污染。
    static clock::time_point lastRing{};
    const auto now = clock::now();
    const bool recentlyRung = lastRing.time_since_epoch().count() != 0 &&
                              now - lastRing < std::chrono::seconds(35);
    const uint8_t action = recentlyRung ? 0 : 1;
    if (send(action)) {
        lastRing = (action == 1) ? now : clock::time_point{};
    }
}

} // namespace apxpc::wireless
