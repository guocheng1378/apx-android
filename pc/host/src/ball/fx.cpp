#include "fx.hpp"

#include <cmath>

namespace apxpc {
namespace ball {
namespace fx {

namespace {
constexpr double kDurationMs = 420.0;   // 脉冲时长
constexpr double kAmplitude  = 0.10;    // 衰减前幅度（实际首峰 ≈ +6%）
constexpr double kDecay      = 3.0;     // 指数衰减
constexpr double kOsc        = 1.5;     // 全程 1.5 个震荡周期
} // namespace

Pt polar(double cx, double cy, double r, double angleRad) {
    return { cx + r * std::cos(angleRad), cy + r * std::sin(angleRad) };
}

double itemAngle(int i, int count, double startRad, double spanRad) {
    if (count <= 1) return startRad;
    if (i < 0) i = 0;
    if (i >= count) i = count - 1;
    return startRad + spanRad * static_cast<double>(i) / static_cast<double>(count - 1);
}

double easeInOut(double t) {
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    if (t < 0.5) return 2 * t * t;
    const double u = -2 * t + 2;
    return 1 - u * u / 2;
}

void Pulse::kick() {
    active = true;
    elapsedMs = 0;
}

bool Pulse::advance(int ms) {
    if (!active) return false;
    elapsedMs += ms;
    if (elapsedMs >= static_cast<int>(kDurationMs)) {
        elapsedMs = static_cast<int>(kDurationMs);
        active = false;
    }
    return active;
}

double Pulse::scale() const {
    if (!active) return 1.0;
    const double t = static_cast<double>(elapsedMs) / kDurationMs;
    return 1.0 + kAmplitude * std::exp(-kDecay * t) * std::sin(6.2831853 * kOsc * t);
}

} // namespace fx
} // namespace ball
} // namespace apxpc
