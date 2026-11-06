// mac 球 v2（M4-b）—— AppKit 实现，与 ball_win32.cpp 对应（同窗渲染 / 注册表驱动 / fx 共享动效）。
// 线程红线：NSWindow 只许主线程——createBall/destroyBall 均由 desktop_main 装配点（主线程）调用。
// 改功能（动作/档位/角标/灰显）只动 ball/registry，本文件零改动（M1/M2/M3 分界约定）。
//
// 2026-11-07 视觉/几何与 Windows 对齐（用户「mac 端也按 Win 新样式」）：
//   · 球与项都是白玻璃圆（上亮下透 + 顶部扁高光 + 1px 描边 + 内圈反光），
//     不再是「实心蓝圆 + 白字标签」；尺寸常量与 Win 同源（球/项 52pt，图标占 46%）。
//   · 图标走 SF Symbols（描边风格），取不到时退回功能名首字。
//   · 摆盘改成单弧 + 按项数动态算半径（旧版 HIGH 外弧 + CARD 内弧 70pt，6 项必叠）。
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

// —— 尺寸（与 Win 的 ball_win32.cpp 同源：改一处必须两端同步）——
// 2026-11-07 用户「图标太大、要有手机那种透明的质感」+「mac 也按 Win 新样式」：
// 球/项 52pt、圆内图标占 46%、扇形按项数动态算半径（旧版固定 R=128 + 内弧 70 的做法
// 在 7→10 项之后会把 CARD 内弧挤成一坨：6 个 52pt 的项挤在 70pt 半径上）。
constexpr double kBallD        = 52.0;   // 球直径 pt（= Win kBallDip）
constexpr double kItemD        = 52.0;   // 项直径 pt（= Win kItemDip）
constexpr double kFanRIdeal    = 176.0;  // 扇形半径理想值 pt（= Win kFanRadiusDip）
constexpr double kFanSpanMin   = 1.0;    // 展开张角下限（rad）
constexpr double kFanSpanMax   = M_PI;   // 展开张角上限（rad）：10 项需要半圆
constexpr double kItemGapRatio = 1.08;   // 相邻项心距 / 项径（>1 = 圆不相交）
constexpr double kTickDt = 16.0;   // 动画步进 ms（16ms 定时器）
constexpr double kDurMs  = 300.0;  // 展开/收起时长（与 Win 按压节奏对齐）

// —— 玻璃配色（与 Win paintGlassDisc / 手机端 res/values/colors.xml 同源）——
// 白蒙上亮下透 + 顶部扁高光 + 1px 描边 + 内圈反光。只有半透明会像磨砂塑料，
// 半透明 + 边光才像玻璃。
constexpr double kGlassTopA = 176.0 / 255.0;  // 顶部白蒙
constexpr double kGlassBotA = 104.0 / 255.0;  // 底部（更透）
constexpr double kStrokeA   = 46.0 / 255.0;   // 描边
constexpr double kHiA       = 118.0 / 255.0;  // 顶部高光
constexpr double kRimA      = 96.0 / 255.0;   // 内圈反光
constexpr double kShadowA   = 52.0 / 255.0;   // 外柔影
// 图标墨色（近黑，与 Win kInk 同值）
constexpr double kInkR = 32.0 / 255.0;
constexpr double kInkG = 38.0 / 255.0;
constexpr double kInkB = 48.0 / 255.0;

// 扇形几何：按项数动态算（与 Win layoutOpen 同一套公式，含 span 上下限夹取）。
// 弦距公式：相邻项心距 = 2R·sin(span/2n)；先按理想半径反推角步长得到 span，
// 夹到 [1.0, π] 后再用 span 反解 R —— 张角被上限截断时 R 自动变大，间距下限依然成立。
struct FanLayout {
    double radius = kFanRIdeal;
    double span   = 0.0;
    double side   = kBallD;
    double cx     = kBallD / 2.0;
    double cy     = kBallD / 2.0;
};

FanLayout fanLayout(size_t n) {
    FanLayout L;
    if (n == 0) return L;
    const double chord = kItemD * kItemGapRatio;
    const double stepIdeal = chord / kFanRIdeal;
    double span = double(n) * stepIdeal;
    if (span < kFanSpanMin) span = kFanSpanMin;
    if (span > kFanSpanMax) span = kFanSpanMax;
    const double half = span / (2.0 * double(n));
    const double sh = std::sin(half);
    L.span = span;
    L.radius = std::ceil(chord / (2.0 * (sh > 0.02 ? sh : 0.02)));
    L.side = 2.0 * (L.radius + kItemD / 2.0 + 8.0);
    L.cx = L.side / 2.0;
    L.cy = L.side / 2.0;
    return L;
}

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

// 扇形基准朝向（与 Win 同口径：球在屏幕右半 → 朝左 π，否则朝右 0）。
// 绘制与命中共用这一个函数，分叉过两次的教训。
double fanDirFor(NSWindow* win) {
    if (!win) return M_PI;
    NSScreen* sc = [win screen];
    if (!sc) sc = [NSScreen mainScreen];
    if (!sc) return M_PI;
    const NSRect vf = [sc visibleFrame];
    return (NSMidX([win frame]) > NSMidX(vf)) ? M_PI : 0.0;
}

// 项位置（绘制与命中共用同一套几何）：单弧、注册序即顺序、半径按项数算。
bool itemPos(NSUInteger idx, NSPoint c, double eased, NSPoint* out) {
    const size_t n = registry().all().size();
    if (idx >= n || n == 0) return false;
    const FanLayout L = fanLayout(n);
    const double dir = fanDirFor(g.win);
    const double a = fx::itemAngle(int(idx), int(n), dir - L.span / 2.0, L.span);
    const fx::Pt p = fx::polar(c.x, c.y, L.radius * eased, a);
    *out = NSMakePoint(p.x, p.y);
    return true;
}

NSUInteger hitItem(NSPoint p, NSPoint c) {
    if (fx::easeInOut(g.t) < 0.98) return NSNotFound;  // 展开完成才可点
    const size_t n = registry().all().size();
    for (NSUInteger i = 0; i < n; ++i) {
        NSPoint ip;
        if (!itemPos(i, c, 1.0, &ip)) break;
        const double dx = p.x - ip.x, dy = p.y - ip.y;
        if (dx * dx + dy * dy <= (kItemD / 2) * (kItemD / 2)) return i;
    }
    return NSNotFound;
}

const Action* actionAt(NSUInteger idx) {
    const auto all = registry().all();
    return idx < all.size() ? all[idx] : nullptr;
}

// —— 玻璃圆（与 Win paintGlassDisc 同一套画法）——
// 顺序 = 外柔影 → 上亮下透渐变 → 顶部扁高光 → 1px 描边 → 内圈反光。
void paintGlassDisc(NSRect r, double topA, double botA) {
    // 外柔影：一层就够，两层在小圆上会发脏
    const NSRect sr = NSMakeRect(r.origin.x - 1, r.origin.y + 2,
                                 r.size.width + 2, r.size.height + 3);
    [[NSColor colorWithSRGBRed:0 green:0 blue:0 alpha:kShadowA] setFill];
    [[NSBezierPath bezierPathWithOvalInRect:sr] fill];

    NSBezierPath* disc = [NSBezierPath bezierPathWithOvalInRect:r];
    [NSGraphicsContext saveGraphicsState];
    [disc addClip];
    // 上亮下透：isFlipped 视图里 minY 是屏幕上方
    NSGradient* grad = [[NSGradient alloc]
        initWithStartingColor:[NSColor colorWithSRGBRed:1 green:1 blue:1 alpha:topA]
                  endingColor:[NSColor colorWithSRGBRed:1 green:1 blue:1 alpha:botA]];
    [grad drawFromPoint:NSMakePoint(NSMidX(r), NSMinY(r))
                toPoint:NSMakePoint(NSMidX(r), NSMaxY(r))
                options:0];
    // 顶部高光：扁椭圆居中偏上（铺满会读成白瓷片）
    const NSRect hi = NSMakeRect(r.origin.x + r.size.width * 0.21,
                                 r.origin.y + r.size.height * 0.08,
                                 r.size.width * 0.58, r.size.height * 0.24);
    [[NSColor colorWithSRGBRed:1 green:1 blue:1 alpha:kHiA] setFill];
    [[NSBezierPath bezierPathWithOvalInRect:hi] fill];
    [NSGraphicsContext restoreGraphicsState];

    // 1px 描边：白底窗口上靠它定形
    [[NSColor colorWithSRGBRed:0 green:0 blue:0 alpha:kStrokeA] setStroke];
    disc.lineWidth = 1.0;
    [disc stroke];

    // 内圈反光：整圈细白线（Win 只画上半弧，但这边的弧角在 flipped 视图里要另算方向，
    // 整圈既稳又同样提供「玻璃边缘」的感觉）
    NSBezierPath* rim = [NSBezierPath bezierPathWithOvalInRect:NSInsetRect(r, 1.6, 1.6)];
    [[NSColor colorWithSRGBRed:1 green:1 blue:1 alpha:kRimA] setStroke];
    rim.lineWidth = 1.2;
    [rim stroke];
}

// 动作 id → SF Symbols 名（描边风格与本项目 UI 一致）。取不到就退回字形首字。
NSString* symbolNameFor(const std::string& id) {
    if (id == "open_panel")     return @"macwindow";
    if (id == "send_clipboard") return @"doc.on.clipboard";
    if (id == "phone_input")    return @"keyboard";
    if (id == "send_file")      return @"square.and.arrow.up";
    if (id == "toggle_display") return @"display";
    if (id == "toggle_speaker") return @"speaker.wave.2";
    if (id == "toggle_mic")     return @"mic";
    if (id == "toggle_camera")  return @"camera";
    if (id == "wireless")       return @"wifi";
    if (id == "exit")           return @"power";
    if (id == "touchpad")       return @"hand.tap";
    return nil;
}

// 单色线条图标：优先 SF Symbols；系统取不到（旧系统 / 名字变更）退回功能名首字 ——
// 任何情况下都不能留一个空圆（点了没反应 + 看不出是干什么的）。
void drawIcon(const std::string& id, const std::string& label, NSRect box, double alpha) {
    NSColor* ink = [NSColor colorWithSRGBRed:kInkR green:kInkG blue:kInkB alpha:alpha];
    NSString* name = symbolNameFor(id);
    if (name) {
        NSImage* base = [NSImage imageWithSystemSymbolName:name accessibilityDescription:nil];
        if (base) {
            NSImageSymbolConfiguration* cfg = [NSImageSymbolConfiguration
                configurationWithPointSize:box.size.height weight:NSFontWeightSemibold];
            cfg = [cfg configurationByApplyingConfiguration:
                            [NSImageSymbolConfiguration configurationWithHierarchicalColor:ink]];
            NSImage* img = [base imageWithSymbolConfiguration:cfg];
            if (img && img.size.width > 0 && img.size.height > 0) {
                // 等比缩放到 box 内居中（symbol 自带留白，直接铺满会偏大）
                const double k = std::min(box.size.width / img.size.width,
                                          box.size.height / img.size.height);
                const NSSize ds = NSMakeSize(img.size.width * k, img.size.height * k);
                const NSRect dr = NSMakeRect(NSMidX(box) - ds.width / 2,
                                             NSMidY(box) - ds.height / 2, ds.width, ds.height);
                [img drawInRect:dr fromRect:NSZeroRect
                      operation:NSCompositingOperationSourceOver fraction:1.0
                 respectFlipped:YES hints:nil];
                return;
            }
        }
    }
    NSString* text = [NSString stringWithUTF8String:label.c_str()];
    if (text.length == 0) return;
    NSString* first = [text substringToIndex:1];
    NSDictionary* attr = @{
        NSForegroundColorAttributeName : ink,
        NSFontAttributeName : [NSFont systemFontOfSize:box.size.height * 0.62
                                                weight:NSFontWeightSemibold],
    };
    const NSSize sz = [first sizeWithAttributes:attr];
    [first drawAtPoint:NSMakePoint(NSMidX(box) - sz.width / 2, NSMidY(box) - sz.height / 2)
        withAttributes:attr];
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
    const auto items = registry().all();

    // 扇形项：与 Win 同款白玻璃圆 + 单色线条图标（**不放文字**——52pt 圆里排中文怎么排都糊，
    // 手机端那一层也只是「玻璃圆 + 图标」；label 留给无障碍/右键菜单）。
    // 展开动画中项跟着 eased 从球心飞出（旧版要等动画结束才出现，像「啪」地蹦出来）。
    if (eased > 0.02 && !items.empty()) {
        const double ir = kItemD / 2.0;
        for (size_t i = 0; i < items.size(); ++i) {
            NSPoint p;
            if (!itemPos((NSUInteger)i, c, eased, &p)) break;
            const Action* a = items[i];
            const BOOL en = !a->enabled_fn || a->enabled_fn();
            // 玻璃底：与球本体同一套画法（禁用项整体压淡一档）
            paintGlassDisc(NSMakeRect(p.x - ir, p.y - ir, kItemD, kItemD),
                           en ? kGlassTopA : kGlassTopA * 2.0 / 3.0,
                           en ? kGlassBotA : kGlassBotA * 2.0 / 3.0);
            // 图标占圆内 46%，四周留呼吸区（旧版 58% 会顶到边光）
            const double gsz = ir * 0.92;
            drawIcon(a->id, a->label,
                     NSMakeRect(p.x - gsz / 2, p.y - gsz / 2, gsz, gsz),
                     en ? 208.0 / 255.0 : 108.0 / 255.0);
            // 状态角标：绿点 + 白圈（白底圆上纯绿点会糊成一小块）
            if (a->badge_fn && a->badge_fn()) {
                const double br = ir * 0.19;
                const NSPoint bp = NSMakePoint(p.x + ir * 0.50, p.y - ir * 0.84);
                const double ring = br + 1.4;
                [[NSColor colorWithSRGBRed:1 green:1 blue:1 alpha:0.96] setFill];
                [[NSBezierPath bezierPathWithOvalInRect:NSMakeRect(bp.x - ring, bp.y - ring,
                                                                   ring * 2, ring * 2)] fill];
                [[NSColor colorWithSRGBRed:0.20 green:0.78 blue:0.35 alpha:1.0] setFill];
                [[NSBezierPath bezierPathWithOvalInRect:NSMakeRect(bp.x - br, bp.y - br,
                                                                   br * 2, br * 2)] fill];
            }
        }
    }

    // 球本体：同一套玻璃 + 触控板线条图标（按压脉冲缩放）
    const double r = kBallD / 2.0 * g.pulse.scale();
    paintGlassDisc(NSMakeRect(c.x - r, c.y - r, r * 2, r * 2), kGlassTopA, kGlassBotA);
    const double gsz = r * 0.92;
    drawIcon("touchpad", "触控板",
             NSMakeRect(c.x - gsz / 2, c.y - gsz / 2, gsz, gsz), 208.0 / 255.0);
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
        const double s = fanLayout(registry().all().size()).side;
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
        if (a && (!a->enabled_fn || a->enabled_fn()) && a->run_fn) {
            [self toggleOpen];  // 与 Win 同节奏：先收扇再执行（动作可能弹模态窗/唤起面板）
            a->run_fn();
        } else {
            // 点了不可用项：**不能静默**。Win 侧同样是「唤出面板」——面板概览页就是连接
            // 状态，一眼能看出差在哪儿（手机没连时所有功能项都会被 enabled_fn 拦下）。
            const Action* op = registry().find("open_panel");
            [self toggleOpen];
            if (op && op->run_fn) op->run_fn();
        }
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
