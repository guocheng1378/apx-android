// 球 v2 实现 —— 设计与取舍见 ball_win32.hpp 顶部注释。
//
// 实现取舍：
//   · per-pixel layered（UpdateLayeredWindow + GDI+ PARGB）：扇形 = 圆弧 + 文字 + 角标，
//     纯 Region 裁不出异形，alpha 渐隐也依赖按像素；legacy 壳只画正圆，所以只用 Region。
//   · Region 仍然要（与 layered 叠加）：窗口区域 = 球圆 ∪ 各项圆 —— 区域外的鼠标事件由
//     系统直接投给下层窗口（跨进程也对），避开 WM_NCHITTEST 返回 HTTRANSPARENT 的
//     「只透同线程」限制。
//   · 同窗展开：改窗口尺寸 → 重绘整面 → 重设区域；不另开顶层窗（无 z-order/焦点竞争）。
//   · 动效算术全在 fx.cpp（可单测），本文件只负责到点重画。
//   · GetHBITMAP 出的位图若边缘发黑，把内部像素改手动预乘（M3 视检微调，先证链路）。
#include "ball_win32.hpp"

#if defined(_WIN32)

#include "apxpc/log.hpp"
#include "fx.hpp"

// GDI+ 官方顺序：windows.h（已在 ball_win32.hpp 内，带 WIN32_LEAN_AND_MEAN）→ objidl.h → gdiplus.h。
// LEAN_AND_MEAN 裁掉了 ole2 链，objidl 不随 windows.h 进来 —— 少了它，GdiplusBitmap/GdiplusMetafile
// 里的 IStream / BOOL 参数直接崩（C2065 IStream → 级联 100+ 错停编，diag run #5 实测）。
#include <objidl.h>
#include <gdiplus.h>
#include <windowsx.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

namespace apxpc {
namespace ball {

HWND g_ballWnd = nullptr;

namespace {

// 必须是数组/指针型：值型 `wchar_t` 装不下字符串字面量（diag run #6: C2440 + C2664 + C2131
// 三条全出自这里 —— lpszClassName 赋值、CreateWindowExW 第 2 参、初始化三处同时炸）。
constexpr wchar_t kClass[] = L"APXBallV2";

// —— 尺寸（DIP）——
// 2026-11-06 实测反馈「分辨率太低、按钮样式太烂、展开后还叠加在一起」，三根因：
//   ① 球只有 44px、项只有 52px，在 1080p 上就是两粒米；② 7 项固定摆 1.7rad、
//      R=128 → 相邻项心弦距仅 2*128*sin(1.7/14)=31px < 项径 52px，**必然重叠**；
//   ③ 纯色圆 + 居中小字，没有层次。
// 现在：球/项都放大到 64px，扇形几何按项数动态算（见 layoutOpen，保证弦距 > 项径）。
// 2026-11-07 用户再反馈「图标太大、要有手机那种透明的质感」→ 球/项回到 52dip、
//   圆内图标占比 58% → 46%、玻璃调薄（alpha 见 paintGlassDisc），并补齐 10 个常用项。
constexpr int     kBallDip       = 52;    // 球直径（2026-11-07：64 → 52，用户「不能太大」）
constexpr int     kItemDip       = 52;    // 扇形项直径（同上；圆内图标同步缩到 46%）
constexpr int     kFanRadiusDip  = 176;   // 扇形半径（项心轨迹，理想值；实际按项数微调）
constexpr double  kFanSpanMin    = 1.0;   // 展开张角下限（rad）
constexpr double  kFanSpanMax    = fx::kPi;  // 展开张角上限（rad）：10 项需要半圆，见 layoutOpen
constexpr double  kItemGapRatio  = 1.08;  // 相邻项心距 / 项径（>1 = 圆不相交；1.08 = 留一点余量）
constexpr int     kTickMs        = 16;
constexpr int     kBadgeMs       = 500;   // 状态角标轮询
constexpr int     kSnapMs        = 160;   // 贴边时长（对齐 Android dock / legacy 壳）
// 拖动判定门槛。旧值 3px 太紧：真人点击时鼠标/手指常带 2~4px 抖动，一旦越过就
// 变成「拖球」而不是「点开扇形」，松手只是贴边 —— 表现就是「点了没反应」。
constexpr int     kDragSlop      = 7;     // 位移 > 7px 才算拖动
constexpr UINT_PTR kTimerAnim    = 1;
constexpr UINT_PTR kTimerBadge   = 2;
constexpr UINT     kFanHotkeyMsg = WM_APP + 41;  // 热键线程投递：切换扇形
// 球右键菜单命令号
constexpr UINT     kMenuOpenPanel  = 3101;
constexpr UINT     kMenuToggleBall = 3102;
constexpr UINT     kMenuExit       = 3103;

int dip(int v, UINT dpi) { return ::MulDiv(v, (int)dpi, 96); }

std::wstring wide(const std::string& s) {
    if (s.empty()) return std::wstring();
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (n <= 0) return std::wstring();
    std::wstring out((size_t)n, L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), out.data(), n);
    return out;
}

struct ItemHit { RECT rc{}; std::string actionId; bool enabled = true; };

// 宿主钩子（右键菜单用）：打开面板 / 退出。类型定义在头文件（两端同形），
// 这里只存一份实例。
BallHostHooks g_hooks;

struct BallState {
    UINT dpi = 96;
    int ballPx = kBallDip;
    int itemPx = kItemDip;
    int fanR   = kFanRadiusDip;   // 实际半径（按项数算，见 layoutOpen）
    int fanRIdeal = kFanRadiusDip;// 理想半径（角步长基准）
    double fanSpan = 0.0;         // 实际张角（rad）

    int side = kBallDip;   // 当前窗口边长（闭 = 球径；开 = 2*(fanR+itemPx/2+pad)）
    int cx = 0, cy = 0;    // 球心（客户区坐标，两态都取窗口中心）

    bool   fanOpen = false;
    double fanDir  = fx::kPi;   // 扇形基准朝向：球在工作区右半 → 朝左（π），否则朝右（0）
    std::vector<MenuItem> items;
    std::vector<ItemHit>  hits;
    std::vector<int>      badges;   // 与 items 平行：0/1（开扇时填充，角标定时器刷新）
    bool lastRing = false;          // 闭态球环：上一轮 anyHighBadge

    fx::Pulse pulse;

    bool dragging = false, moved = false, wasOpen = false, anim = false;
    POINT dragPt{0, 0};
    RECT  startRc{0, 0, 0, 0}, fromRc{0, 0, 0, 0}, toRc{0, 0, 0, 0};
    ULONGLONG snapT0 = 0;

    ULONG_PTR gdip = 0;
    bool gdipOk = false;
};

BallState* g = nullptr;

// —— 布局 ——
void layoutClosed(BallState* s) { s->side = s->ballPx; s->cx = s->side / 2; s->cy = s->side / 2; }

// 展开态几何：**按项数动态算**，保证任意项数下相邻项圆都不相交。
// 弦距公式：相邻项心距 = 2R·sin(span/2n)。取下限 chord = 项径×1.14，
// 先按理想半径反推角步长 → 得到张角 span（再夹到 [1.0, 2.7] rad），
// 最后用 span 反解 R —— 张角被上限截断时 R 自动变大，间距下限依然成立。
// 这条式子替代了旧版「固定 span=1.7 + 固定 R=128」（7 项时弦距仅 31px < 项径 52px）。
void layoutOpen(BallState* s) {
    const int n = (int)s->items.size();
    int r = 0;
    double span = 0.0;
    if (n > 0) {
        const double chord = (double)s->itemPx * kItemGapRatio;   // 相邻项心最小间距
        const double stepIdeal = chord / (double)(s->fanRIdeal > 0 ? s->fanRIdeal : 1);
        span = (double)n * stepIdeal;
        if (span < kFanSpanMin) span = kFanSpanMin;
        if (span > kFanSpanMax) span = kFanSpanMax;
        const double half = span / (2.0 * n);
        const double sinHalf = std::sin(half);
        r = (int)std::ceil(chord / (2.0 * (sinHalf > 0.02 ? sinHalf : 0.02)));
    }
    s->fanSpan = span;
    s->fanR    = r;
    s->side = 2 * (s->fanR + s->itemPx / 2 + 8);
    s->cx = s->side / 2;
    s->cy = s->side / 2;
}

// 扇形项中心（每帧重算，同时喂渲染与命中）
fx::Pt itemCenter(const BallState* s, size_t i) {
    const double a = fx::itemAngle((int)i, (int)s->items.size(), s->fanDir - s->fanSpan / 2.0, s->fanSpan);
    return fx::polar(s->cx, s->cy, s->fanR, a);
}

// —— 状态查询（badge 走 registry 钩子）——
std::vector<int> queryBadges(const std::vector<MenuItem>& items) {
    std::vector<int> out;
    out.reserve(items.size());
    for (const auto& m : items) {
        const Action* a = registry().find(m.actionId);
        out.push_back(a && a->badge_fn && a->badge_fn() ? 1 : 0);
    }
    return out;
}

// 任一动作有角标即亮球缘（用户自定义后档位失去意义 → 扫全部动作）
bool anyHighBadge() {
    for (const Action* a : registry().all())
        if (a->badge_fn && a->badge_fn()) return true;
    return false;
}

// —— 区域：闭态整窗可点；展开态 = 球圆 ∪ 项圆 ——
// 这里踩过一个**致命坑**：旧实现每帧（present 16ms）都 SetWindowRgn 一次，用
// pulse.scale() 跟着按压动画缩。结果收起时偶尔 SetWindowRgn 没落地（窗口刚 resize、
// 或与系统重绘打架），窗口上还留着「扇形那一大块」的旧区域，而窗口已经缩回 64px ——
// 球心根本不在命中区里，真人点击直接穿透到桌面。表现就是用户说的
// 「悬浮球的功能没一个能用、点了没反应」（PostMessage 绕过 hit-test 反而是好的，
// 所以自己测「点得开」）。
// 现在：只在开关状态/项数/DPI 变化时更新一次，且闭态清空 region（整窗可点）。
void applyRegion(HWND w) {
    const auto* s = g;
    if (!s) return;
    static bool   lastOpen = true;      // 初值取反，保证第一次一定执行
    static size_t lastItems = 0;
    static int    lastDpi = 0;
    if (lastOpen == s->fanOpen && lastItems == s->items.size() && lastDpi == s->dpi) return;
    lastOpen = s->fanOpen;
    lastItems = s->items.size();
    lastDpi = s->dpi;

    if (!s->fanOpen) {
        ::SetWindowRgn(w, nullptr, TRUE);   // 闭态：不留 region，64×64 整窗都能点
        return;
    }

    const int br = (int)(s->ballPx * 0.5 + 0.5);
    HRGN rgn = ::CreateEllipticRgn(s->cx - br, s->cy - br, s->cx + br, s->cy + br);
    if (!rgn) return;
    for (size_t i = 0; i < s->items.size(); ++i) {
        const fx::Pt p = itemCenter(s, i);
        const int ir = s->itemPx / 2;
        HRGN c = ::CreateEllipticRgn((int)p.x - ir, (int)p.y - ir, (int)p.x + ir, (int)p.y + ir);
        HRGN n = c ? ::CreateRectRgn(0, 0, 0, 0) : nullptr;
        if (c && n) {
            ::CombineRgn(n, rgn, c, RGN_OR);
            ::DeleteObject(rgn);
            rgn = n;
            ::DeleteObject(c);
        } else {
            if (c) ::DeleteObject(c);
            if (n) ::DeleteObject(n);
        }
    }
    if (!::SetWindowRgn(w, rgn, TRUE)) {
        APX_LOGW("球 SetWindowRgn 失败（err={}）—— 区域残留会让球点不动", ::GetLastError());
        ::DeleteObject(rgn);
    }
}

// —— 渲染：GDI+ 画进 PARGB 位图 → UpdateLayeredWindow（同时重建命中矩形）——

// 品牌色（#266BE5 的三通道；渐变端点由它派生）
constexpr int kBrandR = 38, kBrandG = 107, kBrandB = 229;

// 扇形项图标字符：白底圆盘 + 品牌色关键字。
// 不用 emoji —— GDI+ 的 Segoe UI Emoji 渲染彩色 emoji 不可靠（掉字形/黑白），
// 这里按 action id 映射到一个单字（源码里写 UTF-8 字节转义，避免 MSVC 按 ACP 误解码）。
std::wstring glyphChar(const std::string& id, const std::wstring& label) {
    struct Map { const char* id; const char* utf8; };
    static const Map kMap[] = {
        { "open_panel",     "\xE9\x9D\xA2" },   // 面
        { "send_clipboard", "\xE5\x89\xAA" },   // 剪
        { "toggle_speaker", "\xE5\xA3\xB0" },   // 声
        { "toggle_mic",     "\xE9\xBA\xA6" },   // 麦
        { "toggle_display", "\xE5\xB1\x8F" },   // 屏
        { "conn_status",    "\xE8\xBF\x9E" },   // 连
        { "exit",           "\xE9\x80\x80" },   // 退
    };
    for (const auto& m : kMap) {
        if (id == m.id) return wide(std::string(m.utf8));
    }
    if (!label.empty()) return label.substr(0, 1);
    return std::wstring(1, (wchar_t)0x2022);
}

// 玻璃配色 —— 与手机端 res/values/colors.xml 的 ball_* 同源。
// 2026-11-07 用户「要有手机那种透明的质感」：白蒙从 0.80/0.55 降到 0.69/0.41（后面的
// 壁纸/窗口要能透出来，再低在白底窗口上会糊成一片），顶部高光从「68% 宽的大白片」
// 收成 58% 宽的扁椭圆，另加一道上缘内反光弧 —— 半透明 + 边光才像玻璃，
// 只有半透明会像磨砂塑料。
constexpr int kGlassTopA = 176;   // #B0FFFFFF 顶部白蒙
constexpr int kGlassBotA = 104;   // #68FFFFFF 底部（更透）
constexpr int kStrokeA   = 46;    // #2E000000 描边（白底上要靠它定形）
constexpr int kHiA       = 118;   // #76FFFFFF 顶部高光
constexpr int kRimA      = 96;    // #60FFFFFF 上缘内反光
constexpr int kInkR = 32, kInkG = 38, kInkB = 48;   // 图标墨色（近黑）

// 一颗玻璃圆：球本体与扇形项共用同一套画法（只在透明度上分档）。
// 顺序 = 外柔影 → 上亮下透渐变 → 顶部高光 → 1px 描边 → 上缘边光。
void paintGlassDisc(Gdiplus::Graphics& gr, const Gdiplus::RectF& r, int topA, int botA) {
    // 柔影：一层就够，两层在小圆上会发脏
    Gdiplus::SolidBrush sh(Gdiplus::Color(52, 0, 0, 0));
    gr.FillEllipse(&sh, r.X - 1.f, r.Y + 2.f, r.Width + 2, r.Height + 3.f);

    // 玻璃主体：上亮下透的半透明白（手机端 bg_floating_ball 同款）
    Gdiplus::LinearGradientBrush gb(
        Gdiplus::Point((int)r.X, (int)r.Y),
        Gdiplus::Point((int)r.X, (int)r.GetBottom()),
        Gdiplus::Color(topA, 255, 255, 255),
        Gdiplus::Color(botA, 255, 255, 255));
    gr.FillEllipse(&gb, r);

    // 顶部高光：扁椭圆、居中偏上，越小越像玻璃反光（旧版铺 68% 宽，读作白瓷片）
    {
        const float hw = r.Width * 0.58f;
        const float hh = r.Height * 0.24f;
        Gdiplus::SolidBrush hi(Gdiplus::Color(kHiA, 255, 255, 255));
        gr.FillEllipse(&hi, r.X + (r.Width - hw) * 0.5f, r.Y + r.Height * 0.08f, hw, hh);
    }

    // 1px 描边
    Gdiplus::Pen ring(Gdiplus::Color(kStrokeA, 0, 0, 0), 1.f);
    gr.DrawEllipse(&ring, r.X + 0.5f, r.Y + 0.5f, r.Width - 1.f, r.Height - 1.f);

    // 上缘内反光弧（只走上半圈，和最外描边错开 1.6px）
    Gdiplus::Pen rim(Gdiplus::Color(kRimA, 255, 255, 255), 1.2f);
    rim.SetLineCap(Gdiplus::LineCapRound, Gdiplus::LineCapRound, Gdiplus::DashCapRound);
    gr.DrawArc(&rim, r.X + 1.6f, r.Y + 1.6f, r.Width - 3.2f, r.Height - 3.2f, 190.f, 160.f);
}

void drawBallBody(Gdiplus::Graphics& gr, const Gdiplus::RectF& r) {
    paintGlassDisc(gr, r, kGlassTopA, kGlassBotA);
}

// —— 单色线性图标（24×24 基准，按 box 缩放）——
// 与手机端 res/drawable/ic_apx_*.xml 同形：只描边、线宽随尺寸走、圆头圆角。
// 汉字在 64px 的圆里怎么排都挤（前两版都栽在这），单色线条缩到任何尺寸都干净。
void drawGlyph(Gdiplus::Graphics& gr, const std::string& id, const Gdiplus::RectF& box,
               const Gdiplus::Color& col) {
    const float k  = box.Width / 24.f;
    const float ox = box.X, oy = box.Y;
    Gdiplus::Pen pen(col, std::max(1.5f, 1.9f * k));
    pen.SetLineCap(Gdiplus::LineCapRound, Gdiplus::LineCapRound, Gdiplus::DashCapRound);
    pen.SetLineJoin(Gdiplus::LineJoinRound);
    auto X = [&](float v) { return ox + v * k; };
    auto Y = [&](float v) { return oy + v * k; };

    if (id == "open_panel") {              // 窗口：框 + 标题栏
        gr.DrawRectangle(&pen, X(3.5f), Y(4.5f), 17.f * k, 15.f * k);
        gr.DrawLine(&pen, X(3.5f), Y(9.f), X(20.5f), Y(9.f));
    } else if (id == "send_clipboard") {   // 剪贴板：板 + 夹子 + 两行内容
        gr.DrawRectangle(&pen, X(5.f), Y(5.5f), 14.f * k, 14.5f * k);
        gr.DrawRectangle(&pen, X(9.f), Y(2.8f), 6.f * k, 4.4f * k);
        gr.DrawLine(&pen, X(8.5f), Y(12.f), X(15.5f), Y(12.f));
        gr.DrawLine(&pen, X(8.5f), Y(15.5f), X(13.f), Y(15.5f));
    } else if (id == "toggle_speaker") {   // 喇叭 + 声波
        Gdiplus::GraphicsPath p;
        p.AddLine(X(4.f), Y(9.f), X(8.5f), Y(9.f));
        p.AddLine(X(8.5f), Y(9.f), X(13.f), Y(4.8f));
        p.AddLine(X(13.f), Y(4.8f), X(13.f), Y(19.2f));
        p.AddLine(X(13.f), Y(19.2f), X(8.5f), Y(15.f));
        p.AddLine(X(8.5f), Y(15.f), X(4.f), Y(15.f));
        p.CloseFigure();
        gr.DrawPath(&pen, &p);
        gr.DrawArc(&pen, X(14.f), Y(8.f), 6.f * k, 8.f * k, -55.f, 110.f);
    } else if (id == "toggle_mic") {       // 麦克风：胶囊 + 托架 + 底座
        gr.DrawArc(&pen, X(9.f), Y(2.5f), 6.f * k, 11.f * k, 180.f, 180.f);
        gr.DrawArc(&pen, X(9.f), Y(7.f), 6.f * k, 11.f * k, 0.f, 180.f);
        gr.DrawArc(&pen, X(5.5f), Y(9.f), 13.f * k, 11.f * k, 0.f, 180.f);
        gr.DrawLine(&pen, X(12.f), Y(20.f), X(12.f), Y(16.f));
        gr.DrawLine(&pen, X(8.5f), Y(20.6f), X(15.5f), Y(20.6f));
    } else if (id == "toggle_display") {   // 显示器 + 底座
        gr.DrawRectangle(&pen, X(3.f), Y(4.5f), 18.f * k, 12.5f * k);
        gr.DrawLine(&pen, X(12.f), Y(17.f), X(12.f), Y(20.f));
        gr.DrawLine(&pen, X(8.f), Y(20.2f), X(16.f), Y(20.2f));
    } else if (id == "phone_input") {      // 手机输入：键盘（机身 + 两排键 + 空格）
        gr.DrawRectangle(&pen, X(2.6f), Y(6.2f), 18.8f * k, 11.6f * k);
        gr.DrawLine(&pen, X(6.2f), Y(10.f), X(9.4f), Y(10.f));
        gr.DrawLine(&pen, X(11.f), Y(10.f), X(13.f), Y(10.f));
        gr.DrawLine(&pen, X(14.6f), Y(10.f), X(17.8f), Y(10.f));
        gr.DrawLine(&pen, X(8.f), Y(14.2f), X(16.f), Y(14.2f));
    } else if (id == "send_file") {        // 发文件：托盘 + 上箭头（发送）
        gr.DrawLine(&pen, X(4.5f), Y(14.6f), X(4.5f), Y(19.f));
        gr.DrawLine(&pen, X(4.5f), Y(19.f), X(19.5f), Y(19.f));
        gr.DrawLine(&pen, X(19.5f), Y(19.f), X(19.5f), Y(14.6f));
        gr.DrawLine(&pen, X(12.f), Y(15.4f), X(12.f), Y(4.6f));
        gr.DrawLine(&pen, X(12.f), Y(4.6f), X(8.2f), Y(8.4f));
        gr.DrawLine(&pen, X(12.f), Y(4.6f), X(15.8f), Y(8.4f));
    } else if (id == "toggle_camera") {    // 摄像头：机身 + 取景凸起 + 镜头
        gr.DrawRectangle(&pen, X(3.f), Y(7.f), 18.f * k, 12.4f * k);
        gr.DrawRectangle(&pen, X(8.6f), Y(4.4f), 5.f * k, 2.6f * k);
        gr.DrawEllipse(&pen, X(8.6f), Y(9.6f), 6.8f * k, 6.8f * k);
    } else if (id == "wireless") {         // 无线：两道弧 + 实心点（旧 conn_status 同形）
        gr.DrawArc(&pen, X(3.5f), Y(5.5f), 17.f * k, 17.f * k, 202.f, 136.f);
        gr.DrawArc(&pen, X(7.5f), Y(9.5f), 9.f * k, 9.f * k, 202.f, 136.f);
        Gdiplus::SolidBrush b(col);
        gr.FillEllipse(&b, X(10.3f), Y(16.6f), 3.4f * k, 3.4f * k);
    } else if (id == "touchpad") {         // 触控板：板 + 中心点（球内图标）
        gr.DrawRectangle(&pen, X(5.5f), Y(3.5f), 13.f * k, 17.f * k);
        Gdiplus::SolidBrush b(col);
        gr.FillEllipse(&b, X(9.7f), Y(9.7f), 4.6f * k, 4.6f * k);
    } else {                               // exit：断口圆 + 竖线
        gr.DrawArc(&pen, X(4.5f), Y(4.5f), 15.f * k, 15.f * k, -55.f, 290.f);
        gr.DrawLine(&pen, X(12.f), Y(2.8f), X(12.f), Y(11.5f));
    }
}

// 一个扇形项：与球同款的玻璃圆 + 单色线性图标（**不放文字**）。
// 手机上这一层就是「玻璃圆 + 单色图标」（HyperOS 观感），桌面版前两版用蓝底/白瓷底
// 叠汉字 + 小标签，在 64px 里怎么排都糊 —— 现在照搬手机端，只留图形。
void drawFanItem(Gdiplus::Graphics& gr, const fx::Pt& p, const MenuItem& it,
                 int itemPx, bool badge) {
    const float ir = itemPx * 0.5f;
    const Gdiplus::RectF rc(p.x - ir, p.y - ir, ir * 2, ir * 2);

    // 玻璃底：与球本体同一套画法（禁用项整体压淡一档）
    const int topA = it.enabled ? kGlassTopA : (kGlassTopA * 2 / 3);
    const int botA = it.enabled ? kGlassBotA : (kGlassBotA * 2 / 3);
    paintGlassDisc(gr, rc, topA, botA);

    // 图标：单色墨黑（禁用压到 108/255），占圆内 ~46%
    // —— 2026-11-07 用户「图标太大」：旧值 ir*1.16（=58%）在 52px 圆里会顶到边光，
    //    收到 ir*0.92（=46%）后图标四周留出呼吸区，才像手机那套玻璃圆。
    const float gsz = ir * 0.92f;
    drawGlyph(gr, it.actionId, Gdiplus::RectF(p.x - gsz / 2, p.y - gsz / 2, gsz, gsz),
              Gdiplus::Color((BYTE)(it.enabled ? 208 : 108), kInkR, kInkG, kInkB));

    // 状态角标：绿点 + 白圈（白底上纯绿点会糊成一小块）
    if (badge) {
        const float br2 = ir * 0.19f;
        const float cx = p.x + ir * 0.50f;
        const float cy = p.y - ir * 0.84f;
        Gdiplus::SolidBrush wring(Gdiplus::Color(245, 255, 255, 255));
        gr.FillEllipse(&wring, cx - br2 - 1.6f, cy - br2 - 1.6f, (br2 + 1.6f) * 2, (br2 + 1.6f) * 2);
        Gdiplus::SolidBrush dot(Gdiplus::Color(255, 46, 188, 88));
        gr.FillEllipse(&dot, cx - br2, cy - br2, br2 * 2, br2 * 2);
    }
}
bool present(HWND w) {
    auto* s = g;
    if (!s || !s->gdipOk) return false;

    Gdiplus::Bitmap bmp(s->side, s->side, PixelFormat32bppPARGB);
    if (bmp.GetLastStatus() != Gdiplus::Ok) return false;
    Gdiplus::Graphics gr(&bmp);
    if (gr.GetLastStatus() != Gdiplus::Ok) return false;
    gr.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    gr.SetTextRenderingHint(Gdiplus::TextRenderingHintAntiAliasGridFit);

    const double sc = s->pulse.scale();
    const float br = s->ballPx * 0.5f * (float)sc;
    const Gdiplus::RectF ballRect(s->cx - br, s->cy - br, br * 2, br * 2);

    // 球本体：品牌蓝渐变 + 高光 + 白环（旧版是纯色平涂，看着很「糊」）
    drawBallBody(gr, ballRect);

    // 球缘亮环：闭态看全局 anyBadge，开态由 badges 驱动项角标
    if (!s->fanOpen && anyHighBadge()) {
        Gdiplus::Pen pen(Gdiplus::Color(255, 90, 220, 130), 2.f);
        gr.DrawEllipse(&pen, s->cx - br - 3, s->cy - br - 3, (br + 3) * 2, (br + 3) * 2);
    }

    // 球内图标：与手机端 ic_apx_touchpad 同形（触控板框 + 中心点）。
    // 旧版是一个白色 "A"（品牌首字母）—— 缩到 64px 就是个字母块，手机端从不放字。
    // 2026-11-07：占比 66% → 52%（同「图标太大」的反馈，球内图标也要留出玻璃边）。
    drawGlyph(gr, "touchpad",
              Gdiplus::RectF(s->cx - br * 0.53f, s->cy - br * 0.53f, br * 1.06f, br * 1.06f),
              Gdiplus::Color(214, kInkR, kInkG, kInkB));

    // 扇形项
    s->hits.clear();
    if (s->fanOpen) {
        for (size_t i = 0; i < s->items.size(); ++i) {
            const auto& it = s->items[i];
            const fx::Pt p = itemCenter(s, i);
            const float ir = s->itemPx * 0.5f;
            const bool badge = (i < s->badges.size() && s->badges[i] == 1);
            drawFanItem(gr, p, it, s->itemPx, badge);

            s->hits.push_back(ItemHit{
                RECT{ (LONG)p.x - (LONG)ir, (LONG)p.y - (LONG)ir,
                      (LONG)p.x + (LONG)ir + 1, (LONG)p.y + (LONG)ir + 1 },
                it.actionId, it.enabled });
        }
    }

    // 提交：DIB → UpdateLayeredWindow
    HDC screenDc = ::GetDC(nullptr);
    HDC memDc = screenDc ? ::CreateCompatibleDC(screenDc) : nullptr;
    HBITMAP hbm = nullptr;
    if (screenDc && memDc && bmp.GetHBITMAP(Gdiplus::Color(0, 0, 0, 0), &hbm) == Gdiplus::Ok) {
        HGDIOBJ old = ::SelectObject(memDc, hbm);
        RECT rc{};
        ::GetWindowRect(w, &rc);
        SIZE sz{ s->side, s->side };
        POINT src{ 0, 0 };
        POINT dst{ rc.left, rc.top };
        BLENDFUNCTION bf{ AC_SRC_OVER, 0, 255, AC_SRC_ALPHA };
        const BOOL ulw = ::UpdateLayeredWindow(w, screenDc, &dst, &sz, memDc, &src, 0, &bf, ULW_ALPHA);
        if (!ulw) {
            // v223.29.15：失败必须留痕 —— 此前静默失败，表现为「日志已创建、屏幕上没有」，
            // 最常见根因就是窗口缺 WS_EX_LAYERED（UpdateLayeredWindow 前置硬条件）。
            // 只记首次错误，避免 16ms 动效刷屏。
            static bool ulwErrLogged = false;
            if (!ulwErrLogged) {
                ulwErrLogged = true;
                APX_LOGW("球 v2 UpdateLayeredWindow 失败（err={}，球将不可见）", ::GetLastError());
            }
        }
        ::SelectObject(memDc, old);
        ::DeleteObject(hbm);
    }
    if (memDc) ::DeleteDC(memDc);
    if (screenDc) ::ReleaseDC(nullptr, screenDc);

    applyRegion(w);
    return true;
}

// —— 展开 / 收起（同窗改尺寸；球心屏幕位置保持不动）——
void expandLocked(HWND w) {   // 调用前 s->items / s->badges 已就位
    auto* s = g;
    RECT rc{};
    ::GetWindowRect(w, &rc);
    const POINT ballBefore{ (rc.left + rc.right) / 2, (rc.top + rc.bottom) / 2 };
    s->fanOpen = true;
    layoutOpen(s);
    ::SetWindowPos(w, HWND_TOPMOST, ballBefore.x - s->side / 2, ballBefore.y - s->side / 2,
                   s->side, s->side, SWP_NOACTIVATE);
    present(w);
}

void collapseLocked(HWND w) {
    auto* s = g;
    RECT rc{};
    ::GetWindowRect(w, &rc);
    const POINT ballBefore{ rc.left + s->cx, rc.top + s->cy };   // 开态球心 = 窗口中心
    s->fanOpen = false;
    s->items.clear();
    s->hits.clear();
    s->badges.clear();
    layoutClosed(s);
    ::SetWindowPos(w, HWND_TOPMOST, ballBefore.x - s->side / 2, ballBefore.y - s->side / 2,
                   s->side, s->side, SWP_NOACTIVATE);
    present(w);
}

// 最近的左右屏边 + y 夹进工作区（与 legacy 壳 snapTarget 同构）
RECT snapTarget(HWND w) {
    RECT rc{};
    ::GetWindowRect(w, &rc);
    MONITORINFO mi{ sizeof(mi) };
    const HMONITOR mon = ::MonitorFromWindow(w, MONITOR_DEFAULTTONEAREST);
    if (!mon || !::GetMonitorInfoW(mon, &mi)) return rc;
    const RECT& wa = mi.rcWork;
    const int bw = rc.right - rc.left;
    const int bh = rc.bottom - rc.top;
    const int cx = (rc.left + rc.right) / 2;
    RECT t = rc;
    t.left  = (cx * 2 < wa.left + wa.right) ? wa.left : wa.right - bw;
    t.right = t.left + bw;
    if (t.top < wa.top) t.top = wa.top;
    if (t.top + bh > wa.bottom) t.top = wa.bottom - bh;
    return t;
}

// 从注册表开扇（全部动作，按注册序；方向按球在工作区的位置）
void openFanFromRegistry(HWND w) {
    std::vector<MenuItem> items;
    // 全部动作（按注册序）—— 用户自定义的顺序即扇形顺序
    for (const Action* a : registry().all()) {
        MenuItem m;
        m.actionId = a->id;
        m.label    = a->label;
        m.enabled  = a->enabled_fn ? a->enabled_fn() : true;
        items.push_back(std::move(m));
    }
    if (items.empty()) {
        APX_LOGI("球扇形为空（installDefaultActions 未调？）");
        return;
    }
    RECT rc{};
    ::GetWindowRect(w, &rc);
    RECT wa{};
    ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    const int ballX = (rc.left + rc.right) / 2;
    g->fanDir = (ballX * 2 >= wa.left + wa.right) ? fx::kPi : 0.0;
    drawFan(w, items);
}

} // namespace

// ============ 公开接口（顺序按头文件） ============

void drawFan(HWND w, const std::vector<MenuItem>& items) {
    auto* s = g;
    if (!s) return;
    s->items = items;
    s->badges = queryBadges(items);
    expandLocked(w);
}

void drawBall(HWND w, const Registry& reg) {
    (void)reg;   // 亮环取值在 present() 内实时走 registry()，参数留作 API 稳定
    auto* s = g;
    if (!s) return;
    if (s->fanOpen) collapseLocked(w);
    else present(w);
}

void bringToFront(HWND w) {
    if (!w) return;
    ::SetWindowPos(w, HWND_TOPMOST, 0, 0, 0, 0,
                   SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
}

// 热键入口（M4-c(b)）：球窗口有自己的线程、热键回调在热键线程 —— 直接调
// expand/collapse 会跨线程改 BallState 并提交 GDI+ 位图，所以只投消息。
void toggleBallFan() {
    if (!g_ballWnd) return;   // 球未创建：静默无操作
    ::PostMessageW(g_ballWnd, kFanHotkeyMsg, 0, 0);
}

// —— 可见性（托盘「显示/隐藏悬浮球」+ 面板设置开关）——
// 球窗口活在面板 UI 线程上，但托盘回调在托盘线程 → 统一只投消息，
// ShowWindow/绘制回到球所在线程做（不跨线程碰 GDI+ 位图）。
constexpr UINT kBallShowMsg = WM_APP + 42;
constexpr UINT kBallHideMsg = WM_APP + 43;

void showBall() {
    if (!g_ballWnd) return;
    ::PostMessageW(g_ballWnd, kBallShowMsg, 0, 0);
}

void hideBall() {
    if (!g_ballWnd) return;
    ::PostMessageW(g_ballWnd, kBallHideMsg, 0, 0);
}

void toggleBallVisible() {
    if (!g_ballWnd) return;
    if (::IsWindowVisible(g_ballWnd)) hideBall(); else showBall();
}

bool ballVisible() { return g_ballWnd && ::IsWindowVisible(g_ballWnd); }
bool ballCreated() { return g_ballWnd != nullptr; }

// 宿主钩子（右键菜单）：面板线程装配时一次性注入
void setBallHostHooks(const BallHostHooks& hooks) { g_hooks = hooks; }

LRESULT CALLBACK ballWndProc(HWND w, UINT m, WPARAM wp, LPARAM lp) {
    switch (m) {
    case WM_ERASEBKGND:
        return 1;                          // layered 表面自绘，不给系统擦

    case WM_SETCURSOR:
        ::SetCursor(::LoadCursorW(nullptr, IDC_HAND));
        return TRUE;

    case WM_LBUTTONDOWN: {
        auto* s = g;
        if (!s) return 0;
        const POINT pt{ GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };

        // 扇形项上：执行并收扇（enabled=false 只收不执行）
        if (s->fanOpen) {
            for (const auto& h : s->hits) {
                if (::PtInRect(&h.rc, pt)) {
                    const Action* a = registry().find(h.actionId);
                    const bool usable =
                        h.enabled && a && (!a->enabled_fn || a->enabled_fn()) && a->run_fn;
                    // 先收扇再执行：球是 topmost，菜单不收的话会压在「发文件」弹出的
                    // 模态文件对话框上面（2026-11-07 加发文件项时暴露）。
                    collapseLocked(w);
                    if (usable) {
                        a->run_fn();
                    } else if (g_hooks.open_panel) {
                        // 点了不可用项：**不能静默**。此前直接 return，用户看到的就是
                        // 「功能没一个能用、点了没反应」—— 实际是手机没连，功能项全被
                        // enabled_fn 拦下。这里把面板叫出来（概览页就是连接状态 + 传输
                        // 开关），一眼能看出差在哪儿。
                        g_hooks.open_panel();
                    }
                    return 0;
                }
            }
        }

        // 球上：按压脉冲 + 抓球。开着扇时先收（本次 UP 不再翻转开合）
        s->wasOpen = s->fanOpen;
        if (s->fanOpen) collapseLocked(w);
        s->pulse.kick();
        ::SetTimer(w, kTimerAnim, kTickMs, nullptr);
        ::SetCapture(w);
        s->dragging = true;
        s->moved    = false;
        s->anim     = false;               // 按下即停贴边（legacy 同款）
        ::GetCursorPos(&s->dragPt);
        ::GetWindowRect(w, &s->startRc);
        present(w);                        // 立刻给出按压反馈
        return 0;
    }

    case WM_MOUSEMOVE: {
        auto* s = g;
        if (!s || !s->dragging) return 0;
        POINT cur{};
        ::GetCursorPos(&cur);
        const int dx = cur.x - s->dragPt.x;
        const int dy = cur.y - s->dragPt.y;
        if (!s->moved && (dx > kDragSlop || dx < -kDragSlop || dy > kDragSlop || dy < -kDragSlop))
            s->moved = true;
        if (s->moved)
            ::SetWindowPos(w, HWND_TOPMOST, s->startRc.left + dx, s->startRc.top + dy,
                           0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        return 0;
    }

    case WM_LBUTTONUP: {
        auto* s = g;
        if (!s) return 0;
        if (::GetCapture() == w) ::ReleaseCapture();
        s->dragging = false;
        if (!s->moved) {
            // 未移动 = 点击：开扇；「按下时已开、被先收掉」的那次不再翻回去
            if (!s->wasOpen) openFanFromRegistry(w);
            return 0;
        }
        ::GetWindowRect(w, &s->fromRc);
        s->toRc = snapTarget(w);
        s->anim = true;
        s->snapT0 = ::GetTickCount64();
        ::SetTimer(w, kTimerAnim, kTickMs, nullptr);
        return 0;
    }

    case WM_RBUTTONUP: {
        // 右键 = 球的「兜底入口菜单」。2026-11-06 用户反馈「面板打不开」——
        // 球模式下面板默认隐藏，此前唯一的入口是扇形里的「开面板」项（点错/项重叠就点不到）。
        // 这里给出稳定的二级入口：打开面板 / 显隐球 / 退出。
        auto* s = g;
        if (!s) return 0;
        APX_LOGI("球右键：弹出兜底菜单");
        const bool wasOpen = s->fanOpen;
        if (wasOpen) collapseLocked(w);
        ::SetForegroundWindow(w);
        HMENU menu = ::CreatePopupMenu();
        if (menu) {
            ::AppendMenuW(menu, MF_STRING, kMenuOpenPanel, L"打开面板");
            ::AppendMenuW(menu, MF_STRING, kMenuToggleBall,
                          wasOpen ? L"收起功能扇形" : L"展开功能扇形");
            ::AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
            ::AppendMenuW(menu, MF_STRING, kMenuExit, L"退出");
            POINT pt{};
            ::GetCursorPos(&pt);
            const int cmd = ::TrackPopupMenu(menu, TPM_RIGHTBUTTON | TPM_RETURNCMD | TPM_NONOTIFY,
                                             pt.x, pt.y, 0, w, nullptr);
            ::DestroyMenu(menu);
            if (cmd == kMenuOpenPanel) {
                if (g_hooks.open_panel) g_hooks.open_panel();
            } else if (cmd == kMenuToggleBall) {
                if (s->fanOpen) collapseLocked(w);
                else openFanFromRegistry(w);
            } else if (cmd == kMenuExit) {
                if (g_hooks.exit_app) g_hooks.exit_app();
            }
        }
        return 0;
    }

    case WM_CAPTURECHANGED:             // 拖动中失焦（alt-tab 等）：静默复位，不吞事件
        if (g) g->dragging = false;
        return 0;

    case WM_TIMER: {
        auto* s = g;
        if (!s) return 0;
        if (wp == kTimerBadge) {
            bool changed = false;
            if (s->fanOpen) {
                auto nb = queryBadges(s->items);
                if (nb != s->badges) { s->badges = std::move(nb); changed = true; }
            } else {
                const bool ring = anyHighBadge();
                if (ring != s->lastRing) { s->lastRing = ring; changed = true; }
            }
            if (changed) present(w);
            return 0;
        }
        if (wp != kTimerAnim) return 0;

        bool busy = false;
        if (s->pulse.active) {
            (void)s->pulse.advance(kTickMs);   // 收敛那一帧也 present，回落到 1.0
            present(w);
            busy = s->pulse.active;
        }
        if (s->anim) {
            double t = (double)(::GetTickCount64() - s->snapT0) / kSnapMs;
            if (t > 1) t = 1;
            const double e = fx::easeInOut(t);
            const int x = (int)(s->fromRc.left + (s->toRc.left - s->fromRc.left) * e);
            const int y = (int)(s->fromRc.top + (s->toRc.top - s->fromRc.top) * e);
            ::SetWindowPos(w, HWND_TOPMOST, x, y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
            if (t >= 1) s->anim = false;
            else busy = true;
        }
        if (!busy) ::KillTimer(w, kTimerAnim);
        return 0;
    }

    case WM_DISPLAYCHANGE: {            // 屏幕拓扑变化：立即回工作区（不做动画，避免满屏飞）
        auto* s = g;
        if (s && !s->anim && !s->fanOpen && !s->dragging) {
            const RECT t = snapTarget(w);
            ::SetWindowPos(w, HWND_TOPMOST, t.left, t.top, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        }
        return 0;
    }

    case WM_DPICHANGED: {              // 拖到 DPI 不同的副屏：按新 DPI 重算三径
        auto* s = g;
        if (!s) return 0;
        s->dpi = HIWORD(wp);
        s->ballPx = dip(kBallDip, s->dpi);
        s->itemPx = dip(kItemDip, s->dpi);
        s->fanRIdeal = dip(kFanRadiusDip, s->dpi);
        s->fanR   = dip(kFanRadiusDip, s->dpi);
        RECT rc{};
        ::GetWindowRect(w, &rc);
        const POINT c{ rc.left + s->cx, rc.top + s->cy };
        if (s->fanOpen) layoutOpen(s); else layoutClosed(s);
        ::SetWindowPos(w, HWND_TOPMOST, c.x - s->side / 2, c.y - s->side / 2,
                       s->side, s->side, SWP_NOACTIVATE);
        present(w);
        return 0;
    }

    // 显隐（托盘 / 面板设置投消息过来）：只动可见性，不碰 GDI+ 位图
    case kBallShowMsg: {
        if (g) present(w);
        ::ShowWindow(w, SW_SHOWNOACTIVATE);
        return 0;
    }
    case kBallHideMsg: {
        if (g && g->fanOpen) collapseLocked(w);
        ::ShowWindow(w, SW_HIDE);
        return 0;
    }
    case kFanHotkeyMsg: {              // 热键：开→收扇；收→按注册表开扇
        auto* s = g;
        if (!s) return 0;
        if (s->fanOpen) collapseLocked(w);
        else openFanFromRegistry(w);
        return 0;
    }

    case WM_PAINT: {                   // 表面归 UpdateLayeredWindow；这里只确认无效区
        PAINTSTRUCT ps;
        ::BeginPaint(w, &ps);
        ::EndPaint(w, &ps);
        return 0;
    }

    case WM_NCDESTROY:
        g_ballWnd = nullptr;
        return ::DefWindowProcW(w, m, wp, lp);

    default:
        return ::DefWindowProcW(w, m, wp, lp);
    }
}

bool createBall() {
    if (g_ballWnd) return true;
    const HINSTANCE inst = ::GetModuleHandleW(nullptr);

    WNDCLASSEXW wc{ sizeof(wc) };
    wc.lpfnWndProc   = ballWndProc;
    wc.hInstance     = inst;
    wc.hCursor       = ::LoadCursorW(nullptr, IDC_HAND);
    wc.hbrBackground = nullptr;
    wc.lpszClassName = kClass;
    if (!::RegisterClassExW(&wc) && ::GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        APX_LOGI("球 v2 窗口类注册失败: {}", ::GetLastError());
        return false;
    }

    auto* s = new BallState();
    s->dpi = ::GetDpiForSystem();
    s->ballPx = dip(kBallDip, s->dpi);
    s->itemPx = dip(kItemDip, s->dpi);
    s->fanRIdeal = dip(kFanRadiusDip, s->dpi);
    s->fanR   = dip(kFanRadiusDip, s->dpi);
    layoutClosed(s);

    Gdiplus::GdiplusStartupInput gsi;
    if (Gdiplus::GdiplusStartup(&s->gdip, &gsi, nullptr) == Gdiplus::Ok) s->gdipOk = true;
    if (!s->gdipOk) {
        APX_LOGI("球 v2 GDI+ 启动失败");
        delete s;
        return false;
    }

    /* v223.29.15 修复（issue：「日志已创建、屏幕上始终没有球」）：
       per-pixel 分层窗口的渲染**全部**走 UpdateLayeredWindow，而它的硬前置条件是
       窗口带 WS_EX_LAYERED —— 旧代码漏了该样式，UpdateLayeredWindow 每次都失败且
       无人检查返回值：ShowWindow 照跑、"球 v2 已创建"照打，但窗口无任何像素
       （WM_PAINT 空走、背景 brush 为空）→ 2026-11-06 用户实测「一直看不到悬浮球」
       与日志「已创建」并存的根因。legacy 壳只用 Region 裁圆不走 layered，所以历史
       legacy 球可见、v2 球自诞生起没人见过。 */
    HWND w = ::CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_LAYERED,
        kClass, L"", WS_POPUP,
        0, 0, s->side, s->side, nullptr, nullptr, inst, nullptr);
    if (!w) {
        APX_LOGI("球 v2 窗口创建失败: {}", ::GetLastError());
        Gdiplus::GdiplusShutdown(s->gdip);
        delete s;
        return false;
    }

    g = s;
    g_ballWnd = w;

    // 初始位：主屏右缘垂直居中（与 legacy 壳一致 → 扇形朝左开）
    RECT wa{};
    ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    const int y = wa.top + ((wa.bottom - wa.top) - s->side) / 2;
    ::SetWindowPos(w, HWND_TOPMOST, wa.right - s->side, y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);

    present(w);
    s->lastRing = anyHighBadge();
    ::ShowWindow(w, SW_SHOWNOACTIVATE);
    ::SetTimer(w, kTimerBadge, kBadgeMs, nullptr);
    APX_LOGI("球 v2 已创建（{}px，动作 {} 个）", s->ballPx,
             (int)registry().all().size());
    return true;
}

void destroyBall() {
    if (!g_ballWnd) return;
    const HWND w = g_ballWnd;
    auto* s = g;
    ::KillTimer(w, kTimerAnim);
    ::KillTimer(w, kTimerBadge);
    g = nullptr;             // 先摘状态：销毁过程中的消息不再碰它
    g_ballWnd = nullptr;
    ::DestroyWindow(w);
    if (s) {
        if (s->gdipOk) Gdiplus::GdiplusShutdown(s->gdip);
        delete s;
    }
    APX_LOGI("球 v2 已销毁");
}

} // namespace ball
} // namespace apxpc

#endif // _WIN32
