#pragma once
// 球的动效与几何：纯算术（零平台依赖），三端可离线单测。
// 从 ball_win32.cpp 里拆出来的理由（M1）：改观感/参数不动窗口逻辑，改窗口逻辑不动观感。
namespace apxpc {
namespace ball {
namespace fx {

inline constexpr double kPi = 3.14159265358979323846;

struct Pt { double x = 0; double y = 0; };

// 极坐标 → 客户区坐标（y 轴向下，angle=0 正右，顺时针为正）
Pt polar(double cx, double cy, double r, double angleRad);

// 扇形第 i 项角度：[start, start+span] 端点含两端均分（count<=1 取 start）
double itemAngle(int i, int count, double startRad, double spanRad);

// easeInOutQuad —— 与 Android AccelerateDecelerate / src/ui/ball 贴边动画同款
double easeInOut(double t);   // t 越界自动夹紧

// 按压脉冲：阻尼正弦。kick() 触发一次（0 → 420ms），advance(ms) 推进，
// 返回 false = 已静止（调用方停表）；scale() 收敛回 1.0。
// 观感参数（首峰约 +6%、420ms 消失）对齐手机端球按压反馈。
struct Pulse {
    void kick();
    bool advance(int ms);    // 返回「推进后是否仍在动」
    double scale() const;    // 静止恒 1.0

    int elapsedMs = 0;
    bool active = false;
};

} // namespace fx
} // namespace ball
} // namespace apxpc
