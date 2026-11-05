// mac 球 v2（M4-b）—— AppKit 实现，与 ball_win32.cpp 对应（同窗渲染 / 注册表驱动 / fx 共享动效）。
// 线程红线：NSWindow 只许主线程——createBall/destroyBall 均由 desktop_main 装配点（主线程）调用。
// 改功能（动作/档位/角标/灰显）只动 ball/registry，本文件零改动（M1/M2/M3 分界约定）。
#if defined(__APPLE__)

#include "ball_win32.hpp"  // mac 侧 #else 声明（createBall/destroyBall）
#include "registry.hpp"
#include "fx.hpp"

#import <AppKit/AppKit.h>

#include <cmath>
#include <algorithm>  // std::min/max（tick 内；防传递包含变化踩空）

namespace apxpc {
namespace ball {
namespace {

constexpr double kBallD  = 44.0;   // 球直径 pt（= Win 44dip）
constexpr double kFanR   = 128.0;  // 扇形半径 pt（= Win）
constexpr double kItemD  = 52.0;   // 项直径 pt（= Win）
constexpr double kTickDt = 16.0;   // 动画步进 ms（16ms 定时器）
constexpr double kDurMs  = 300.0;  // 展开/收起时长（与 Win 按压节奏对齐）

struct BallState {
    NSWindow*   win   = nil;
    NSTimer*    timer = nil;
    BOOL        open  = NO;      // 目标态（点球翻转）
    double      t     = 0.0;     // 线性进度 0..1；绘制时 easeInOut
    NSUInteger  pressed = NSNotFound;
    BOOL        animating = NO;
    fx::Pulse   pulse;
};
BallState g;

double expandSide() { return (kFanR + kItemD) * 2.0; }

// 项位置（绘制与命中共用同一套几何——分叉过两次的教训）
bool itemPos(NSUInteger idx, NSPoint c, double eased, NSPoint* out) {
    const Registry& reg = registry();
    const auto hi = reg.highPriority();
    if (idx < hi.size()) {
        const double span = M_PI * 0.75;
        const double a = fx::itemAngle(int(idx), int(hi.size()), -M_PI_2 - span / 2, span);
        const fx::Pt p = fx::polar(c.x, c.y, (kFanR + kItemD / 2) * eased, a);
        *out = NSMakePoint(p.x, p.y);
        return true;
    }
    const auto ca = reg.cardPriority();
    const NSUInteger ci = idx - hi.size();
    if (ci < ca.size()) {
        const double span = M_PI * 0.6;
        const double a = fx::itemAngle(int(ci), int(ca.size()), -M_PI_2 - span / 2, span);
        const fx::Pt p = fx::polar(c.x, c.y, kFanR * 0.55 * eased, a);
        *out = NSMakePoint(p.x, p.y);
        return true;
    }
    return false;
}

NSUInteger hitItem(NSPoint p, NSPoint c) {
    if (fx::easeInOut(g.t) < 0.98) return NSNotFound;  // 展开完成才可点
    const size_t n = registry().highPriority().size() + registry().cardPriority().size();
    for (NSUInteger i = 0; i < n; ++i) {
        NSPoint ip;
        if (!itemPos(i, c, 1.0, &ip)) break;
        const double dx = p.x - ip.x, dy = p.y - ip.y;
        if (dx * dx + dy * dy <= (kItemD / 2) * (kItemD / 2)) return i;
    }
    return NSNotFound;
}

const Action* actionAt(NSUInteger idx) {
    const auto hi = registry().highPriority();
    if (idx < hi.size()) return hi[idx];
    const auto ca = registry().cardPriority();
    const size_t ci = idx - hi.size();
    return ci < ca.size() ? ca[ci] : nullptr;
}

void stopTimer() {
    if (g.timer) { [g.timer invalidate]; g.timer = nil; }
    g.animating = NO;
}

}  // namespace

}  // namespace ball
}  // namespace apxpc

// clang：@interface/@implementation 只允许出现在全局作用域（M4-b 从未编译，补挂后首编暴露）。
// 实现正文的 C++ 引用（g/hitItem/k*/fx::/registry…）全在 apxpc::ball，经此 using 全局可见——正文零改动。
using namespace apxpc::ball;

@interface BallView : NSView
// 热键入口要用（M4-c(b)）：回调可能在非主线程，回主线程再翻转。
- (void)toggleOpen;
@end

@implementation BallView

// y 轴向下（isFlipped）——fx::polar 的语义与 Win 客户区一致，几何零翻译。
- (BOOL)isFlipped { return YES; }

- (NSPoint)centerPt {
    const NSRect b = self.bounds;
    return NSMakePoint(NSMidX(b), NSMidY(b));
}

- (void)drawRect:(NSRect)rect {
    (void)rect;
    const NSPoint c = [self centerPt];
    const double eased = fx::easeInOut(g.t);

    // 扇形底盘（展开中渐显）
    if (eased > 0.01) {
        [[NSColor colorWithSRGBRed:0.10 green:0.12 blue:0.18 alpha:0.55 * eased] setFill];
        [[NSBezierPath bezierPathWithOvalInRect:NSMakeRect(c.x - (kFanR + kItemD * 0.5) * eased,
                                                            c.y - (kFanR + kItemD * 0.5) * eased,
                                                            (kFanR + kItemD * 0.5) * 2 * eased,
                                                            (kFanR + kItemD * 0.5) * 2 * eased)] fill];
    }

    // 扇形项（HIGH 弧 + CARD 内弧，label 居中、灰显/绿点角标全由注册表驱动）
    if (eased >= 0.98) {
        const Registry& reg = registry();
        const auto draw = [&](const std::vector<const Action*>& items, double radius, double span) {
            if (items.empty()) return;
            for (size_t i = 0; i < items.size(); ++i) {
                const Action* a = items[i];
                const double ang = fx::itemAngle(int(i), int(items.size()), -M_PI_2 - span / 2, span);
                const fx::Pt p = fx::polar(c.x, c.y, radius, ang);
                const BOOL en = !a->enabled_fn || a->enabled_fn();
                [[NSColor colorWithSRGBRed:0.16 green:0.42 blue:0.86 alpha:en ? 0.95 : 0.35] setFill];
                [[NSBezierPath bezierPathWithOvalInRect:NSMakeRect(p.x - kItemD / 2, p.y - kItemD / 2,
                                                                    kItemD, kItemD)] fill];
                NSString* label = [NSString stringWithUTF8String:a->label.c_str()];
                NSDictionary* attr = @{NSForegroundColorAttributeName : [NSColor whiteColor],
                                       NSFontAttributeName : [NSFont systemFontOfSize:11]};
                NSSize sz = [label sizeWithAttributes:attr];
                [label drawAtPoint:NSMakePoint(p.x - sz.width / 2, p.y - sz.height / 2)
                    withAttributes:attr];
                if (a->badge_fn && a->badge_fn()) {
                    [[NSColor systemGreenColor] setFill];
                    [[NSBezierPath bezierPathWithOvalInRect:NSMakeRect(p.x + kItemD / 2 - 9,
                                                                        p.y - kItemD / 2 + 1, 8, 8)] fill];
                }
            }
        };
        draw(reg.highPriority(), kFanR + kItemD / 2, M_PI * 0.75);
        draw(reg.cardPriority(), kFanR * 0.55, M_PI * 0.6);
    }

    // 球本体（#266BE5 + 字母 A，按压脉冲缩放）
    const double r = kBallD / 2 * g.pulse.scale();
    [[NSColor colorWithSRGBRed:0.149 green:0.420 blue:0.898 alpha:1.0] setFill];
    [[NSBezierPath bezierPathWithOvalInRect:NSMakeRect(c.x - r, c.y - r, r * 2, r * 2)] fill];
    NSString* glyph = @"A";
    NSDictionary* ga = @{NSForegroundColorAttributeName : [NSColor whiteColor],
                         NSFontAttributeName : [NSFont boldSystemFontOfSize:r * 0.95]};
    NSSize gs = [glyph sizeWithAttributes:ga];
    [glyph drawAtPoint:NSMakePoint(c.x - gs.width / 2, c.y - gs.height / 2) withAttributes:ga];
}

// 16ms 主钟：线性推进 t（绘制时 ease）+ 脉冲推进 + 窗口同中心缩放；静止即停表。
- (void)tick {
    BOOL active = g.pulse.advance(int(kTickDt));
    const double target = g.open ? 1.0 : 0.0;
    if (g.t != target) {
        const double step = kTickDt / kDurMs;
        g.t = g.t < target ? std::min(target, g.t + step) : std::max(target, g.t - step);
        active = YES;
        if (g.t == 0.0 && !g.open) {  // 收起完成才缩窗（展开时先缩窗再放动画）
            NSRect f = g.win.frame;
            const NSPoint ctr = NSMakePoint(NSMidX(f), NSMidY(f));
            [g.win setFrame:NSMakeRect(ctr.x - kBallD / 2, ctr.y - kBallD / 2, kBallD, kBallD)
                    display:YES];
        }
    }
    [self setNeedsDisplay:YES];
    if (!active && g.t == target) stopTimer();
}

- (void)ensureTimer {
    if (g.timer) return;
    g.timer = [NSTimer scheduledTimerWithTimeInterval:kTickDt / 1000.0
                                               target:self
                                             selector:@selector(tick)
                                             userInfo:nil
                                              repeats:YES];
}

- (void)toggleOpen {
    g.open = !g.open;
    if (g.open) {  // 展开：同中心放大窗口（透明无边框，视觉无跳变）
        NSRect f = g.win.frame;
        const NSPoint ctr = NSMakePoint(NSMidX(f), NSMidY(f));
        const double s = expandSide();
        [g.win setFrame:NSMakeRect(ctr.x - s / 2, ctr.y - s / 2, s, s) display:YES];
    }
    [self ensureTimer];
}

- (void)mouseDown:(NSEvent*)e {
    const NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
    g.pressed = hitItem(p, [self centerPt]);
    g.pulse.kick();
    [self ensureTimer];
}

- (void)mouseUp:(NSEvent*)e {
    const NSPoint p = [self convertPoint:e.locationInWindow fromView:nil];
    const NSUInteger hit = hitItem(p, [self centerPt]);
    if (hit != NSNotFound && hit == g.pressed) {
        const Action* a = actionAt(hit);
        if (a && (!a->enabled_fn || a->enabled_fn()) && a->run_fn) a->run_fn();
        [self toggleOpen];  // 执行即收起（与 Win 同节奏）
    } else if (hit == NSNotFound && g.pressed == NSNotFound) {
        [self toggleOpen];  // 空白点击 = 展开/收起
    }
    g.pressed = NSNotFound;
}

- (void)mouseDragged:(NSEvent*)e {
    NSPoint d = [self convertPoint:e.locationInWindow fromView:nil];
    const NSRect f = g.win.frame;
    (void)d;  // 平移：用 delta 而非绝对点（避免首帧跳变）
    static NSPoint last;
    static BOOL dragging = NO;
    if (!dragging) { last = d; dragging = YES; }
    const NSPoint delta = NSMakePoint(d.x - last.x, d.y - last.y);
    last = d;
    [g.win setFrame:NSMakeRect(f.origin.x + delta.x, f.origin.y + delta.y, f.size.width, f.size.height)
           display:YES];
}

- (void)mouseExited:(NSEvent*)e { (void)e; }

@end

namespace apxpc {
namespace ball {

bool createBall() {
    if (g.win) return true;  // 单实例（与 Win g_ballWnd 同约定）
    g.win = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, kBallD, kBallD)
                                        styleMask:NSWindowStyleMaskBorderless
                                          backing:NSBackingStoreBuffered
                                            defer:NO];
    g.win.opaque = NO;
    g.win.backgroundColor = NSColor.clearColor;
    g.win.hasShadow = NO;
    g.win.ignoresMouseEvents = NO;
    g.win.level = NSFloatingWindowLevel;
    g.win.collectionBehavior = NSWindowCollectionBehaviorCanJoinAllSpaces |
                               NSWindowCollectionBehaviorStationary;
    BallView* v = [[BallView alloc] initWithFrame:g.win.contentView.bounds];
    v.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [g.win.contentView addSubview:v];
    const NSRect vis = NSScreen.mainScreen.visibleFrame;
    [g.win setFrameOrigin:NSMakePoint(NSMaxX(vis) - kBallD - 60, NSMinY(vis) + 120)];
    [g.win orderFrontRegardless];
    return true;
}

// 全局热键切换扇形（M4-c(b)）：desktop_main 注册点调用，任意线程可调。
void toggleBallFan() {
    if (!g.win) return;
    BallView* v = (BallView*)[[[g.win contentView] subviews] firstObject];
    if (!v) return;
    [v performSelectorOnMainThread:@selector(toggleOpen) withObject:nil waitUntilDone:NO];
}

void destroyBall() {
    stopTimer();
    if (g.win) {
        [g.win orderOut:nil];
        g.win = nil;  // ARC 释放
    }
    g.open = NO;
    g.t = 0.0;
}

}  // namespace ball
}  // namespace apxpc

#endif  // __APPLE__
