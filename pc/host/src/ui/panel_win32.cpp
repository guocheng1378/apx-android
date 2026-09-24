// 桌面端控制面板（Windows）：纯 Win32 + GDI+ 自绘。
//
// 视觉**严格对齐手机端** —— 色值、圆角、字级全部取自
// android/app/src/main/res/values/{colors,styles}.xml，两端口径一致：
//   底 #F2F3F5 · 白卡 18px 圆角 + #EDEDED 描边 · 主色 #3482FF · 正文 #191919
//   次要字 #8C8C8C · 分区标题 13px 粗体蓝字 · 状态语义色 ok/warn/error/idle
// GDI+ 是 Windows 自带组件，不违反「不引第三方依赖」。
//
// 控件策略：**除两个输入框外全部自绘** —— 要拿到手机端那种圆角胶囊按钮、圆形
// 单选、MIUI 开关，用原生控件 + owner-draw 反而更绕。输入框保留原生 EDIT
// （中文输入法 / 光标 / 选中这些系统行为不好自己实现），只把底色与边框改掉。
//
// 线程纪律（关键）：所有 socket I/O 都在 WirelessSession 的 worker 线程里；
// UI 只表达意图、按定时器读快照，**不做跨线程回调** —— 避免"回调里刷 UI"竞态。
#include "apxpc/ui/panel.hpp"

#if defined(_WIN32)

// ⚠️ 这几个宏必须在**任何** apxpc 头之前定义。
// `apxpc/media/media_session.hpp` 自己会 include <winsock2.h>，而它会连锁拉进
// <windows.h> —— 若那时 UNICODE/_UNICODE 还没定义，windows.h 就按 ANSI 配置定型，
// 后面再 define 也没用（include guard 已生效）。症状很隐蔽：本文件里
// `LoadIconW(nullptr, IDI_APPLICATION)` 突然报 "LPSTR 不能转 LPCWSTR"。
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include "apxpc/log.hpp"
#include "apxpc/media/audio_capture.hpp"
#include "apxpc/media/media_session.hpp"
#include "apxpc/media/mic_bridge.hpp"
#include "apxpc/media/screen_push.hpp"
#include "apxpc/tray/tray_win32.hpp"
#include "apxpc/wireless/wireless_session.hpp"

#include <windows.h>
#include <windowsx.h>   // GET_X_LPARAM / GET_Y_LPARAM
// WIN32_LEAN_AND_MEAN 把 commctrl.h 从 windows.h 里剔掉了，需显式包含：
// 地址框的提示气泡用 EM_SETCUEBANNER / CBCM_SETCUEBANNER，都在这个头里。
#include <commctrl.h>

#include <objidl.h>
#include <gdiplus.h>

#include <atomic>
#include <cstdio>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "gdiplus")

using apxpc::tray::TrayIcon;
using apxpc::wireless::LinkPhase;
using apxpc::wireless::SessionSnapshot;
using apxpc::wireless::WirelessSession;

namespace apxpc::ui {
namespace {

// ——————————————————— 设计令牌（与 Android colors.xml 同值） ———————————————————
namespace tok {
constexpr Gdiplus::ARGB bg             = 0xFFF2F3F5;   // md_background
constexpr Gdiplus::ARGB surface        = 0xFFFFFFFF;   // md_surface
constexpr Gdiplus::ARGB stroke         = 0xFFEDEDED;   // card_stroke
constexpr Gdiplus::ARGB variant        = 0xFFEDEFF2;   // md_surface_variant
constexpr Gdiplus::ARGB primary        = 0xFF3482FF;   // md_primary
constexpr Gdiplus::ARGB primaryHover   = 0xFF4C90FF;
constexpr Gdiplus::ARGB primaryPressed = 0xFF2B6BE0;   // bg_btn_primary:state_pressed
constexpr Gdiplus::ARGB primarySoft    = 0xFFE8F0FF;   // badge_bg
constexpr Gdiplus::ARGB onSurface      = 0xFF191919;   // md_on_surface
constexpr Gdiplus::ARGB onVariant      = 0xFF8C8C8C;   // md_on_surface_variant
constexpr Gdiplus::ARGB tertiary       = 0xFFBDBDBD;   // miuix_text_tertiary
constexpr Gdiplus::ARGB stateOk        = 0xFF12B76A;   // state_ok
constexpr Gdiplus::ARGB stateWarn      = 0xFFF79009;   // state_warn
constexpr Gdiplus::ARGB stateError     = 0xFFF04438;   // state_error
constexpr Gdiplus::ARGB stateIdle      = 0xFF98A2B3;   // state_idle
constexpr Gdiplus::ARGB fieldBg        = 0xFFF2F3F5;   // 输入框底（同背景色）
}  // namespace tok

constexpr int kAppIconId = 101;   // 对应 pc/host/res/apx.rc 的 IDI_APPICON

constexpr int kClientW = 560;
// 512 → 628（加「副屏」卡片）→ 768（加「音箱」卡片）→ 784（音箱卡片要一行设备下拉）：
// 每加一张卡片 / 一行控件就把底部一排下推
constexpr int kClientH = 808;
constexpr int kMargin = 16;
constexpr int kCardX = kMargin;
constexpr int kCardW = kClientW - 2 * kMargin;   // 528
constexpr int kCardPad = 18;                     // APCcard 内边距
constexpr int kContentX = kCardX + kCardPad;     // 34

enum : int {
    IDC_EDIT_HOST = 1008,
    IDC_EDIT_PORT = 1009,
    IDC_COMBO_DEV = 1010,   // 音箱：采集哪块播放设备
    IDC_COMBO_MIC = 1011,   // 麦克风桥：手机声音渲染到哪块播放设备
};

constexpr UINT_PTR kRefreshTimer = 1;
/// 媒体建链失败后的重试间隔（面板每 400ms 一跳，不加节流会疯狂重连）
constexpr long long kMediaRetryMs = 3000;
constexpr UINT kMsgTrayQuit = WM_APP + 1;

struct Rect {
    int x = 0, y = 0, w = 0, h = 0;
    bool has(int px, int py) const {
        return px >= x && py >= y && px < x + w && py < y + h;
    }
    Gdiplus::RectF f() const {
        return Gdiplus::RectF(static_cast<float>(x), static_cast<float>(y),
                              static_cast<float>(w), static_cast<float>(h));
    }
};

// ——————————————————— 布局（一处定义，绘制与命中检测共用） ———————————————————
// 工具分两类：无线（蓝牙 / Wi‑Fi）、有线（USB）。连接区给 3 个传输开关，
// 没有总开关；打开哪个开关，下方「对应功能」才出现 —— 所以高度随开关动态算。
struct Layout {
    Rect badge;
    Rect cardConn;                 // 连接卡：3 个传输开关 + 连接详情
    Rect titleConn;
    Rect lblWifi, swWifi, lblBt, swBt, lblUsb, swUsb;
    Rect connStatus;               // 连接详情（peer / RTT / 时长）
    Rect fieldHost, fieldPort, editHost, editPort;   // 无线手动地址

    Rect hdrWireless;              // 「无线」分类标题（无线开关开时出现）
    Rect cardScreen, labelScreen, switchScreen, screenStatus, screenDetail;
    Rect cardSpeaker, labelSpeaker, switchSpeaker, speakerStatus, labelSpeakerDev,
         speakerCombo, speakerDevice, speakerDetail, btnTestSpeaker;
    Rect cardMic, labelMic, switchMicFwd, micStatus, labelMicDev, micCombo, micDetail;
    Rect cardCam, labelCam, camStatus, camPreview, camDetail;

    Rect hdrWired;                 // 「有线」分类标题（蓝牙或 USB 开时出现）
    Rect cardBt, labelBtCard, btStatus, btDetail;
    Rect cardUsb, labelUsbCard, usbStatus, usbDetail;

    Rect switchAuto, labelAuto;
    Rect btnHide, btnQuit;
    int totalH = 0;                // 整窗高度（随开关变化）
};

/// 当前哪些传输开关是开的 —— 决定下方出现哪些功能卡
struct VisToggles { bool wifi = false, bt = false, usb = false; };

Layout layout(const VisToggles& v) {
    Layout l;
    l.badge = {kClientW - kMargin - 104, 26, 104, 26};

    int y = 80;
    // —— 连接卡：蓝牙 / 无线 / USB 三个开关，无总开关 ——
    l.cardConn = {kCardX, y, kCardW, 150};
    l.titleConn = {kContentX, y + 14, 200, 18};
    const int swY = y + 46, swW = 46, swH = 24;
    l.lblWifi = {kContentX, swY, 48, swH};
    l.swWifi = {kContentX + 48, swY, swW, swH};
    l.lblBt = {kContentX + 168, swY, 44, swH};
    l.swBt = {kContentX + 212, swY, swW, swH};
    l.lblUsb = {kContentX + 320, swY, 44, swH};
    l.swUsb = {kContentX + 364, swY, swW, swH};
    l.connStatus = {kContentX, y + 84, kCardW - 2 * kCardPad, 20};
    // 无线手动地址：始终展示，便于需要时填手机 IP（自动发现失败时兜底）
    l.fieldHost = {kContentX, y + 114, 200, 30};
    l.editHost = {l.fieldHost.x + 9, l.fieldHost.y, l.fieldHost.w - 18, l.fieldHost.h};
    l.fieldPort = {l.fieldHost.x + l.fieldHost.w + 8, y + 114, 60, 30};
    l.editPort = {l.fieldPort.x + 9, l.fieldPort.y, l.fieldPort.w - 18, l.fieldPort.h};
    y = l.cardConn.y + l.cardConn.h + 14;

    if (v.wifi) {
        l.hdrWireless = {kCardX, y, kCardW, 28};
        y += 34;
        // 副屏
        l.cardScreen = {kCardX, y, kCardW, 104};
        l.labelScreen = {kContentX, y + 14, 300, 24};
        l.switchScreen = {kCardX + kCardW - kCardPad - 46, y + 14, 46, 24};
        l.screenStatus = {kContentX, y + 46, kCardW - 2 * kCardPad, 20};
        l.screenDetail = {kContentX, y + 70, kCardW - 2 * kCardPad, 18};
        y += 104 + 14;
        // 音箱（含采集设备下拉 + 试听）
        l.cardSpeaker = {kCardX, y, kCardW, 172};
        l.labelSpeaker = {kContentX, y + 14, 300, 24};
        l.switchSpeaker = {kCardX + kCardW - kCardPad - 46, y + 14, 46, 24};
        l.speakerStatus = {kContentX, y + 46, kCardW - 2 * kCardPad, 20};
        l.labelSpeakerDev = {kContentX, y + 70, 64, 26};
        l.speakerCombo = {kContentX + 68, y + 70, kCardW - 2 * kCardPad - 68, 26};
        l.speakerDevice = {kContentX, y + 104, kCardW - 2 * kCardPad, 18};
        l.speakerDetail = {kContentX, y + 124, kCardW - 2 * kCardPad, 18};
        l.btnTestSpeaker = {kContentX, y + 144, 92, 28};
        y += 172 + 14;
        // 麦克风（手机麦克风上行 → PC 收流；可转发到 PC 播放设备）
        l.cardMic = {kCardX, y, kCardW, 152};
        l.labelMic = {kContentX, y + 14, 300, 24};
        l.switchMicFwd = {kCardX + kCardW - kCardPad - 46, y + 14, 46, 24};
        l.micStatus = {kContentX, y + 46, kCardW - 2 * kCardPad, 20};
        l.labelMicDev = {kContentX, y + 74, 64, 26};
        l.micCombo = {kContentX + 68, y + 74, kCardW - 2 * kCardPad - 68, 26};
        l.micDetail = {kContentX, y + 108, kCardW - 2 * kCardPad, 18};
        y += 152 + 14;
        // 摄像头（Wi‑Fi）：手机相机 JPEG 上行，卡内实时预览
        const int camPreviewH = 220;
        l.cardCam = {kCardX, y, kCardW, 14 + 24 + 20 + 8 + camPreviewH + 8 + 18 + 12};
        l.labelCam = {kContentX, y + 14, 300, 24};
        l.camStatus = {kContentX, y + 44, kCardW - 2 * kCardPad, 20};
        l.camPreview = {kContentX, y + 70, kCardW - 2 * kCardPad, camPreviewH};
        l.camDetail = {kContentX, y + 70 + camPreviewH + 8, kCardW - 2 * kCardPad, 18};
        y += l.cardCam.h + 14;
    }
    if (v.bt || v.usb) {
        l.hdrWired = {kCardX, y, kCardW, 28};
        y += 34;
        if (v.bt) {
            l.cardBt = {kCardX, y, kCardW, 92};
            l.labelBtCard = {kContentX, y + 14, 300, 24};
            l.btStatus = {kContentX, y + 46, kCardW - 2 * kCardPad, 20};
            l.btDetail = {kContentX, y + 70, kCardW - 2 * kCardPad, 18};
            y += 92 + 14;
        }
        if (v.usb) {
            l.cardUsb = {kCardX, y, kCardW, 100};
            l.labelUsbCard = {kContentX, y + 14, 300, 24};
            l.usbStatus = {kContentX, y + 46, kCardW - 2 * kCardPad, 20};
            l.usbDetail = {kContentX, y + 70, kCardW - 2 * kCardPad, 18};
            y += 100 + 14;
        }
    }
    // 底部：开机自启 + 次要按钮
    l.switchAuto = {kMargin + 4, y + 4, 46, 24};
    l.labelAuto = {l.switchAuto.x + l.switchAuto.w + 10, y + 4, 140, 24};
    l.btnQuit = {kClientW - kMargin - 4 - 92, y, 92, 32};
    l.btnHide = {l.btnQuit.x - 8 - 104, y, 104, 32};
    y += 40;
    l.totalH = y + 16;
    return l;
}

// ——————————————————— 绘制小工具 ———————————————————
// GraphicsPath 的拷贝构造是 protected，不能按值返回 —— 用出参构造
void buildRoundRect(const Rect& r, float rad, Gdiplus::GraphicsPath& p) {
    const float d = rad * 2.0f;
    if (d <= 0.5f || static_cast<float>(r.w) < d || static_cast<float>(r.h) < d) {
        p.AddRectangle(r.f());
        return;
    }
    const Gdiplus::RectF f = r.f();
    p.AddArc(f.X, f.Y, d, d, 180.0f, 90.0f);
    p.AddArc(f.X + f.Width - d, f.Y, d, d, 270.0f, 90.0f);
    p.AddArc(f.X + f.Width - d, f.Y + f.Height - d, d, d, 0.0f, 90.0f);
    p.AddArc(f.X, f.Y + f.Height - d, d, d, 90.0f, 90.0f);
    p.CloseFigure();
}

void fillRound(Gdiplus::Graphics& g, const Rect& r, float rad, Gdiplus::ARGB color) {
    Gdiplus::GraphicsPath p;
    buildRoundRect(r, rad, p);
    Gdiplus::SolidBrush b(color);
    g.FillPath(&b, &p);
}

/// align: 0=左 1=居中 2=右
void text(Gdiplus::Graphics& g, const std::wstring& s, const Rect& r, Gdiplus::Font& f,
          Gdiplus::ARGB color, int align = 0) {
    Gdiplus::SolidBrush b(color);
    Gdiplus::StringFormat fmt;
    fmt.SetAlignment(align == 1   ? Gdiplus::StringAlignmentCenter
                     : align == 2 ? Gdiplus::StringAlignmentFar
                                  : Gdiplus::StringAlignmentNear);
    fmt.SetLineAlignment(Gdiplus::StringAlignmentCenter);
    fmt.SetTrimming(Gdiplus::StringTrimmingEllipsisCharacter);
    fmt.SetFormatFlags(Gdiplus::StringFormatFlagsNoWrap);
    g.DrawString(s.c_str(), -1, &f, r.f(), &fmt, &b);
}

std::wstring toWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                                      nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::string toUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                                      nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), s.data(), n,
                        nullptr, nullptr);
    return s;
}

// ——————————————————— 面板 ———————————————————
enum class Hit {
    None, SwitchWifi, SwitchBt, SwitchUsb, SwitchScreen, SwitchSpeaker, SwitchMic,
    Autostart, Hide, Quit, TestSpeaker
};

struct Panel {
    HWND hwnd = nullptr;

    // 字级对齐手机端 styles.xml
    std::unique_ptr<Gdiplus::Font> fTitle;    // APTextTitle     20sp bold
    std::unique_ptr<Gdiplus::Font> fCaption;  // APTextCaption   12sp
    std::unique_ptr<Gdiplus::Font> fSection;  // APSectionTitle  13sp bold（蓝）
    std::unique_ptr<Gdiplus::Font> fBody;     // APTextBody      14sp
    std::unique_ptr<Gdiplus::Font> fStatus;   // 状态大字（对应状态页 30sp bold）
    std::unique_ptr<Gdiplus::Font> fBtn;
    std::unique_ptr<Gdiplus::Font> fBadge;    // APBadge         12sp bold

    HFONT hEditFont = nullptr;   // 原生 EDIT 只能用 GDI 字体
    HBRUSH hFieldBrush = nullptr;

    std::unique_ptr<WirelessSession> session;
    std::unique_ptr<TrayIcon> tray;

    // 媒体通道（第二条连接，手机 9502）：副屏与音箱/麦克风/摄像头共用同一条
    // —— 手机侧媒体通道是单对端语义，不能为副屏另开一条。
    std::unique_ptr<apxpc::media::MediaSession> media;
    std::unique_ptr<apxpc::media::ScreenPush> screenPush;
    /// 「音箱」：把系统声音（WASAPI loopback）送到手机扬声器
    std::unique_ptr<apxpc::media::AudioCapture> audio;
    /// 「麦克风」：手机麦克风上行 PCM 渲染到 PC 播放设备（虚拟声卡 → 微信当麦克风）
    std::unique_ptr<apxpc::media::MicBridge> micBridge;
    std::string micDeviceId;                 // 渲染到的 endpoint id（空 = 默认）
    std::vector<std::string> micDevIds;      // 下拉各项对应的 id

    // 媒体建链是 3 秒级**阻塞**操作，绝不能放 UI 线程（会整窗卡住）。
    // 用一次性工作线程 + 忙标志：join 只在确认线程已收手时调用，因此不会阻塞 UI。
    std::thread mediaThread;
    std::atomic<bool> mediaBusy{false};
    long long lastMediaTryMs = 0;   // 上次尝试连媒体的时间（失败后节流重试）

    // 三个传输开关（连接区，无总开关）。默认只开无线 = 原"自动发现并立刻连入"行为。
    bool wifiEnabled = true;     // 无线（Wi‑Fi 控制 + 音频 + 副屏）：PC 实际发起连接
    bool btEnabled = false;      // 蓝牙 HID：由手机端配对后启用，PC 仅展示状态
    bool usbEnabled = false;     // USB gadget：由手机端插线授权后启用，PC 仅展示状态
    bool autoMode = true;        // 无线手动地址模式（关 = 自动发现）
    bool autostart = false;

    /// 音箱要采哪块播放设备。**空 = 跟随系统默认**（默认一变就重开采集）。
    std::string speakerDeviceId;
    /// 下拉里每一项对应的端点 ID（下标 0 恒为"跟随系统默认"，值是空串）
    std::vector<std::string> speakerDevIds;
    /// 摄像头预览：媒体收流线程存最新 JPEG，paint 时解码绘制（400ms 一拍天然限频）
    std::mutex camMu;
    std::vector<uint8_t> camJpeg;
    bool camJpegValid = false;

    /// 上一轮"默认设备 ID + 全部端点 ID"的指纹。设备增删或默认易主时靠它发现，
    /// 用来刷新下拉标题 —— 否则会一直写着"跟随系统默认（旧的某块）"。
    std::string lastDevSig;
    long long lastDevCheckMs = 0;   // 设备指纹检查的节流时间戳

    Hit hot = Hit::None;
    Hit pressed = Hit::None;
    bool tracking = false;      // 已注册 WM_MOUSELEAVE
    std::wstring repaintKey;    // 状态指纹：没变就不重绘

    // 试听：不依赖系统是否在放声音，一键把测试音推到手机，用于验证下行链路
    std::atomic<uint64_t> testToneSent{0};   // 本次试听实际送到手机的分片数
    std::atomic<bool> testToneBusy{false};    // 试听推流进行中
};

Gdiplus::ARGB phaseColor(LinkPhase ph) {
    switch (ph) {
        case LinkPhase::Connected:   return tok::stateOk;
        case LinkPhase::Connecting:
        case LinkPhase::Discovering: return tok::stateWarn;
        case LinkPhase::Failed:      return tok::stateError;
        default:                     return tok::stateIdle;
    }
}

std::wstring phaseText(const SessionSnapshot& s) {
    switch (s.phase) {
        case LinkPhase::Connected:   return L"已连接";
        case LinkPhase::Connecting:  return L"正在连接";
        case LinkPhase::Discovering: return L"正在发现";
        case LinkPhase::Failed:      return L"连接失败";
        default:                     return L"未启用";
    }
}

/// 卡片大字：说「现在能干什么」，而不是把顶栏徽章的状态词再抄一遍
/// —— 徽章已经写着「已连接」了，卡片再放大一次「已连接」是纯重复。
std::wstring phaseSentence(const SessionSnapshot& s) {
    switch (s.phase) {
        case LinkPhase::Connected:   return L"手机已连上，可直接用";
        case LinkPhase::Connecting:  return L"正在接入手机…";
        case LinkPhase::Discovering: return L"正在找同一 Wi‑Fi 下的手机";
        case LinkPhase::Failed:      return L"没连上手机";
        default:                     return L"尚未连接";
    }
}

std::wstring detailText(const SessionSnapshot& s) {
    if (s.phase == LinkPhase::Connected) {
        const long long sec = s.upMs / 1000;
        wchar_t buf[256];
        std::swprintf(buf, 256, L"%s   ·   RTT %.0f ms   ·   已连 %lld:%02lld:%02lld",
                      toWide(s.peer).c_str(), s.rttMs, sec / 3600, (sec / 60) % 60,
                      sec % 60);
        return buf;
    }
    if (!s.error.empty()) return toWide(s.error);
    switch (s.phase) {
        case LinkPhase::Discovering:
            return L"手机端打开「Wi‑Fi 控制」模块即可自动连入（同一局域网）";
        case LinkPhase::Connecting:
            return L"正在建立 TCP 连接（最多 3 秒）…";
        case LinkPhase::Failed:
            return L"请检查手机 IP / 是否在同一局域网，然后重新点「连接」";
        default:
            return L"选「自动发现」后点「连接」；或改用「手动指定地址」填手机 IP";
    }
}

/// 状态指纹：只有真正变化时才重绘（避免每 400ms 无谓整窗重画）
std::wstring statusKey(Panel* p, const SessionSnapshot& s) {
    const uint64_t mic = p->media ? p->media->counters().micFrames : 0;
    wchar_t buf[640];
    std::swprintf(buf, 640,
                  L"%d|%d|%d|%d|%s|%.0f|%lld|%llu|%llu|%llu|%llu|%llu|%s|%d|%d|%d|%u|%d",
                  p->wifiEnabled ? 1 : 0, p->btEnabled ? 1 : 0, p->usbEnabled ? 1 : 0,
                  static_cast<int>(s.phase), toWide(s.peer).c_str(), s.rttMs,
                  s.upMs / 1000,
                  static_cast<unsigned long long>(s.counters.mouse),
                  static_cast<unsigned long long>(s.counters.keyboard),
                  static_cast<unsigned long long>(s.counters.consumer),
                  static_cast<unsigned long long>(s.counters.dropped),
                  static_cast<unsigned long long>(mic),
                  toWide(s.error).c_str(),
                  s.phoneAudioState, s.phoneAudioSpk ? 1 : 0, s.phoneAudioMic ? 1 : 0,
                  s.phoneAudioDropped, s.phoneAudioKnown ? 1 : 0);
    return buf;
}

// ——————————————————— 副屏卡片（媒体通道 + 推流） ———————————————————
// 文案原则同别处：说清「现在能不能用」，绝不把没连上的状态说成可用。

long long nowMsLocal() { return static_cast<long long>(GetTickCount64()); }

bool screenMediaUp(Panel* p) { return p->media && p->media->status().connected; }

std::wstring screenBig(Panel* p) {
    const bool up = screenMediaUp(p);
    if (!p->screenPush) return up ? L"未开始" : L"等待媒体连接";
    const auto s = p->screenPush->status();
    if (s.running) return L"正在投屏";
    if (!s.error.empty()) return L"投屏失败";
    if (!up) return L"等待媒体连接";
    return L"未开始";
}

Gdiplus::ARGB screenColor(Panel* p) {
    if (!p->screenPush) return tok::stateIdle;
    const auto s = p->screenPush->status();
    if (s.running) return tok::stateOk;
    if (!s.error.empty()) return tok::stateError;
    return tok::stateIdle;
}

std::wstring screenDetail(Panel* p) {
    const bool up = screenMediaUp(p);
    if (p->screenPush) {
        const auto s = p->screenPush->status();
        if (s.running) {
            wchar_t b[256];
            std::swprintf(b, 256, L"%.1f fps · 已送 %llu 帧 · %ux%u · 编码 %.1f ms · 丢帧 %llu",
                          s.fps, static_cast<unsigned long long>(s.framesSent),
                          s.width, s.height, s.encodeMs,
                          static_cast<unsigned long long>(s.framesDropped));
            return b;
        }
        if (!s.error.empty()) return toWide(s.error);
    }
    if (!up) return L"媒体通道未连上（副屏与音频共用它，需与手机同一 Wi‑Fi）";
    return L"打开右侧开关：把本机桌面投到手机（免驱，抓的是当前桌面）";
}

/// 切换副屏推流。启动前先确认媒体通道在 —— 否则给出**明确原因**，不静默失败。
void toggleScreen(Panel* p) {
    if (!p->screenPush) return;
    if (p->screenPush->running()) {
        p->screenPush->stop();
        return;
    }
    if (!screenMediaUp(p)) {
        MessageBoxW(p->hwnd,
                    L"副屏需要先建立媒体通道。\n\n"
                    L"请确认：\n"
                    L"  · 手机与电脑在同一 Wi‑Fi\n"
                    L"  · 手机端已打开「Wi‑Fi 控制」模块\n"
                    L"  · 上方「连接状态」显示已连接",
                    L"全能外设", MB_OK | MB_ICONINFORMATION);
        return;
    }
    std::string err;
    if (!p->screenPush->start(p->media.get(), apxpc::media::ScreenPushOptions{}, &err)) {
        const std::wstring msg = L"副屏启动失败：\n\n" + toWide(err);
        MessageBoxW(p->hwnd, msg.c_str(), L"全能外设", MB_OK | MB_ICONWARNING);
        return;
    }
    // 唤起手机：让手机自动进副屏页（前台直接弹；后台被 Android 拦时通知「副屏」动作兜底）
    if (p->session) p->session->requestOpenScreen();
}

// ——————————————————— 音箱卡片（系统声音 → 手机扬声器） ———————————————————
// 这一块的文案目标很明确：用户报「没声音」时，卡片自己要能说清是三种情况里的哪一种
//   ① 面板没开采集    ② 开了，但系统本身没在放声音    ③ 采的不是他在听的那块声卡
// 第 ③ 种最容易白忙 —— 所以"采集自<设备名>"必须独立成行、不能被截断。

/// 低于此电平就认为"系统当前没在放声音"（loopback 静音会直接给 SILENT 标记）
constexpr double kSilentPeak = 0.003;

std::wstring speakerBig(Panel* p) {
    if (!p->audio) return L"未开启";
    const auto s = p->audio->status();
    if (s.running) {
        if (s.peak > kSilentPeak) {
            wchar_t b[64];
            std::swprintf(b, 64, L"正在播放 · 电平 %.0f%%", s.peak * 100.0);
            return b;
        }
        return L"已开启 · 系统当前没有声音";
    }
    if (!s.error.empty()) return L"开启失败";
    return screenMediaUp(p) ? L"未开启" : L"等待媒体连接";
}

Gdiplus::ARGB speakerColor(Panel* p) {
    if (!p->audio) return tok::stateIdle;
    const auto s = p->audio->status();
    if (s.running) return s.peak > kSilentPeak ? tok::stateOk : tok::stateWarn;
    if (!s.error.empty()) return tok::stateError;
    return tok::stateIdle;
}

/// 手机侧 Wi‑Fi 音频模块状态文案（来自手机周期上报的 'a' 状态帧；ModuleState 编码 0..6）
std::wstring phoneAudioText(const SessionSnapshot& s) {
    if (!s.phoneAudioKnown) return L"手机侧音频状态未上报";
    switch (s.phoneAudioState) {
        case 2:  // RUNNING
            if (s.phoneAudioSpk && s.phoneAudioMic) return L"手机侧：双向正常";
            if (s.phoneAudioSpk) return L"手机侧：仅音箱下行";
            if (s.phoneAudioMic) return L"手机侧：仅麦克风（手机不播）";
            return L"手机侧：运行中（未就绪）";
        case 3:  // DEGRADED
            return s.phoneAudioSpk ? L"手机侧降级：仅音箱" : L"手机侧降级：仅麦克风（手机不播）";
        case 4:  return L"手机侧音频错误";
        case 1:  return L"手机侧音频启动中";
        case 5:  return L"手机侧音频停止中";
        default: return L"手机侧音频未启动";
    }
}

Gdiplus::ARGB phoneAudioColor(const SessionSnapshot& s) {
    if (!s.phoneAudioKnown) return tok::stateIdle;
    switch (s.phoneAudioState) {
        case 2:  return (s.phoneAudioSpk || s.phoneAudioMic) ? tok::stateOk : tok::stateWarn;
        case 3:  return tok::stateWarn;
        case 4:  return tok::stateError;
        default: return tok::stateIdle;
    }
}

std::wstring speakerDeviceLine(Panel* p) {
    if (!p->audio) return L"";
    const auto s = p->audio->status();
    // 只在真的在采时显示"实际采的是哪块"。未开始时下拉里已写着会采哪块，重复没意义；
    // 但采起来之后这条**必须**有 —— 跟随默认时系统可能把默认换掉，实际采的与下拉
    // 选中的未必是同一块，这个差异正是"手机上没声音"最需要被看见的东西。
    if (!s.running || s.device.empty()) return L"";
    return (s.followingDefault ? L"实际采集：" : L"采集自：") + toWide(s.device);
}

std::wstring speakerDetail(Panel* p) {
    const bool up = screenMediaUp(p);
    if (p->audio) {
        const auto s = p->audio->status();
        if (s.running) {
            wchar_t b[256];
            std::swprintf(b, 256, L"%uHz %uch · 已送 %llu 片 · 丢弃 %llu",
                          s.sampleRate, s.channels,
                          static_cast<unsigned long long>(s.framesSent),
                          static_cast<unsigned long long>(s.dropped));
            return b;
        }
        if (!s.error.empty()) return toWide(s.error);
    }
    if (!up) return L"媒体通道未连上（与副屏共用它，需与手机同一 Wi‑Fi）";
    return L"打开右侧开关：把本机系统声音送到手机扬声器（免驱，采的是系统默认播放设备）";
}

/// 「默认设备 ID + 全部端点 ID」的指纹。用来发现设备增删或默认易主。
std::string speakerDevSignature() {
    std::string sig = apxpc::media::AudioCapture::defaultRenderDeviceId();
    for (const auto& d : apxpc::media::AudioCapture::listRenderDevices()) {
        sig += '|';
        sig += d.id;
    }
    return sig;
}

/// 把「跟随系统默认 + 每一块实体播放设备」填进下拉。
/// 列表每次刷新都保留当前选择；若存的设备已不在列表里（拔了/禁用了），
/// 回落到「跟随系统默认」而不是留一个选不中的空项。
void refreshSpeakerCombo(Panel* p) {
    HWND cb = GetDlgItem(p->hwnd, IDC_COMBO_DEV);
    if (!cb) return;

    const auto devs = apxpc::media::AudioCapture::listRenderDevices();
    const std::string defName = apxpc::media::AudioCapture::defaultRenderDeviceName();

    p->speakerDevIds.clear();
    SendMessageW(cb, CB_RESETCONTENT, 0, 0);

    // 第 0 项：跟随系统默认。把当前默认设备名写进标题里 —— 用户不必展开就知道会采哪块。
    std::wstring first = L"跟随系统默认";
    if (!defName.empty()) first += L"（" + toWide(defName) + L"）";
    SendMessageW(cb, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(first.c_str()));
    p->speakerDevIds.emplace_back();

    for (const auto& d : devs) {
        std::wstring item = toWide(d.name);
        if (d.isDefault) item += L"   · 系统默认";
        SendMessageW(cb, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(item.c_str()));
        p->speakerDevIds.push_back(d.id);
    }

    int sel = 0;
    if (!p->speakerDeviceId.empty()) {
        for (size_t i = 0; i < p->speakerDevIds.size(); ++i) {
            if (p->speakerDevIds[i] == p->speakerDeviceId) {
                sel = static_cast<int>(i);
                break;
            }
        }
        if (sel == 0) p->speakerDeviceId.clear();   // 已不存在 → 回到跟随默认
    }
    SendMessageW(cb, CB_SETCURSEL, sel, 0);
}

/// 下拉换设备。正在放音就**立刻换过去** —— 换了不生效比不能换更让人困惑。
void onSpeakerDeviceChanged(Panel* p) {
    HWND cb = GetDlgItem(p->hwnd, IDC_COMBO_DEV);
    if (!cb) return;
    const int sel = static_cast<int>(SendMessageW(cb, CB_GETCURSEL, 0, 0));
    if (sel < 0 || sel >= static_cast<int>(p->speakerDevIds.size())) return;
    const std::string want = p->speakerDevIds[static_cast<size_t>(sel)];
    if (want == p->speakerDeviceId) return;
    p->speakerDeviceId = want;
    if (!p->audio || !p->audio->running()) return;

    apxpc::media::AudioCaptureOptions opt;
    opt.deviceId = p->speakerDeviceId;
    std::string err;
    if (!p->audio->start(p->media.get(), opt, &err)) {
        const std::wstring msg = L"切换采集设备失败：\n\n" + toWide(err);
        MessageBoxW(p->hwnd, msg.c_str(), L"全能外设", MB_OK | MB_ICONWARNING);
    }
}

/// ————————————————— 麦克风桥（手机麦 → PC 播放设备） —————————————————

void refreshMicCombo(Panel* p) {
    HWND cb = GetDlgItem(p->hwnd, IDC_COMBO_MIC);
    if (!cb) return;
    const auto devs = apxpc::media::AudioCapture::listRenderDevices();
    const std::string defName = apxpc::media::AudioCapture::defaultRenderDeviceName();

    p->micDevIds.clear();
    SendMessageW(cb, CB_RESETCONTENT, 0, 0);

    std::wstring first = L"跟随系统默认";
    if (!defName.empty()) first += L"（" + toWide(defName) + L"）";
    SendMessageW(cb, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(first.c_str()));
    p->micDevIds.emplace_back();

    for (const auto& d : devs) {
        std::wstring item = toWide(d.name);
        if (d.isDefault) item += L"   · 系统默认";
        SendMessageW(cb, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(item.c_str()));
        p->micDevIds.push_back(d.id);
    }

    int sel = 0;
    if (!p->micDeviceId.empty()) {
        for (size_t i = 0; i < p->micDevIds.size(); ++i) {
            if (p->micDevIds[i] == p->micDeviceId) { sel = static_cast<int>(i); break; }
        }
        if (sel == 0) p->micDeviceId.clear();
    }
    SendMessageW(cb, CB_SETCURSEL, sel, 0);
}

void onMicDeviceChanged(Panel* p) {
    HWND cb = GetDlgItem(p->hwnd, IDC_COMBO_MIC);
    if (!cb) return;
    const int sel = static_cast<int>(SendMessageW(cb, CB_GETCURSEL, 0, 0));
    if (sel < 0 || sel >= static_cast<int>(p->micDevIds.size())) return;
    p->micDeviceId = p->micDevIds[static_cast<size_t>(sel)];
    if (!p->micBridge || !p->micBridge->running()) return;
    // 转发中换设备：立即重启桥，换了不生效比不能换更让人困惑
    p->micBridge->start(p->micDeviceId);
}

/// 转发开关：把手机麦克风上行 PCM 渲染到选定播放设备。
/// 装了虚拟声卡（如 VB-Cable）时选它的输入端，微信/会议选其输出端即可用手机麦。
void toggleMicForward(Panel* p) {
    if (!p->micBridge) return;
    if (p->micBridge->running()) {
        p->micBridge->stop();
        InvalidateRect(p->hwnd, nullptr, FALSE);
        return;
    }
    p->micBridge->start(p->micDeviceId);
    InvalidateRect(p->hwnd, nullptr, FALSE);
}

/// 试听：合成一段测试音直接推到手机，用于验证「下行链路是否真通到手机扬声器」。
/// 不依赖系统此刻有没有在放声音 —— 点一下手机该出声；没声就是手机音量/模块问题。
void testSpeakerClick(Panel* p) {
    if (!p->media || !p->media->status().connected) {
        MessageBoxW(p->hwnd, L"手机还没连上，先连上手机再试听。",
                    L"全能外设", MB_OK | MB_ICONINFORMATION);
        return;
    }
    if (p->testToneBusy.exchange(true)) return;   // 已在推流，忽略重复点击
    p->testToneSent.store(0);
    InvalidateRect(p->hwnd, nullptr, FALSE);
    std::thread([p] {
        apxpc::media::AudioCapture::playTestTone(p->media.get(), 2, &p->testToneSent);
        p->testToneBusy.store(false);
        InvalidateRect(p->hwnd, nullptr, FALSE);
    }).detach();
}

/// 切换音箱。启动前先确认媒体通道在 —— 否则给出**明确原因**，不静默失败。
void toggleSpeaker(Panel* p) {
    if (!p->audio) return;
    if (p->audio->running()) {
        p->audio->stop();
        return;
    }
    if (!screenMediaUp(p)) {
        MessageBoxW(p->hwnd,
                    L"音箱需要先建立媒体通道。\n\n"
                    L"请确认：\n"
                    L"  · 手机与电脑在同一 Wi‑Fi\n"
                    L"  · 手机端已打开「Wi‑Fi 控制」与「Wi‑Fi 音频」模块\n"
                    L"  · 上方「连接状态」显示已连接",
                    L"全能外设", MB_OK | MB_ICONINFORMATION);
        return;
    }
    apxpc::media::AudioCaptureOptions opt;
    opt.deviceId = p->speakerDeviceId;   // 空 = 跟随系统默认
    std::string err;
    if (!p->audio->start(p->media.get(), opt, &err)) {
        const std::wstring msg = L"音箱启动失败：\n\n" + toWide(err);
        MessageBoxW(p->hwnd, msg.c_str(), L"全能外设", MB_OK | MB_ICONWARNING);
    }
}

Hit hitTest(Panel* p, int x, int y) {
    const Layout L = layout({p->wifiEnabled, p->btEnabled, p->usbEnabled});
    Hit h = Hit::None;
    if (L.swWifi.has(x, y) || L.lblWifi.has(x, y)) h = Hit::SwitchWifi;
    else if (L.swBt.has(x, y) || L.lblBt.has(x, y)) h = Hit::SwitchBt;
    else if (L.swUsb.has(x, y) || L.lblUsb.has(x, y)) h = Hit::SwitchUsb;
    else if (L.switchScreen.has(x, y) || L.labelScreen.has(x, y)) h = Hit::SwitchScreen;
    else if (L.switchSpeaker.has(x, y) || L.labelSpeaker.has(x, y)) h = Hit::SwitchSpeaker;
    else if (L.switchMicFwd.has(x, y) || L.labelMic.has(x, y)) h = Hit::SwitchMic;
    else if (L.switchAuto.has(x, y) || L.labelAuto.has(x, y)) h = Hit::Autostart;
    else if (L.btnHide.has(x, y)) h = Hit::Hide;
    else if (L.btnQuit.has(x, y)) h = Hit::Quit;
    else if (L.btnTestSpeaker.has(x, y)) h = Hit::TestSpeaker;
    return h == Hit::None ? Hit::None : h;
}

// ——————————————————— 绘制 ———————————————————
void paintButton(Gdiplus::Graphics& g, const Rect& r, const std::wstring& label, bool primary,
                 bool enabled, bool hot, bool pressed, Gdiplus::Font& f) {
    Gdiplus::ARGB fill;
    Gdiplus::ARGB fg;
    Gdiplus::ARGB stroke = 0;
    bool drawStroke = false;
    if (!enabled) {
        fill = tok::variant;
        fg = tok::tertiary;
    } else if (primary) {
        fill = pressed ? tok::primaryPressed : (hot ? tok::primaryHover : tok::primary);
        fg = 0xFFFFFFFF;
    } else {
        fill = pressed ? 0xFFE0E3E8 : (hot ? 0xFFE6E9EE : tok::variant);
        fg = tok::onSurface;
        drawStroke = true;   // 白卡上的浅色按钮边界太弱，加描边才看得清
        stroke = hot ? tok::primary : 0xFFC9CED6;
    }
    Gdiplus::GraphicsPath path;
    buildRoundRect(r, static_cast<float>(r.h) / 2.0f, path);
    Gdiplus::SolidBrush b(fill);
    g.FillPath(&b, &path);
    if (drawStroke) {
        Gdiplus::Pen pen(stroke, 1.5f);
        g.DrawPath(&pen, &path);
    }
    text(g, label, r, f, fg, 1);
}

void paintRadio(Gdiplus::Graphics& g, const Rect& row, const std::wstring& label, bool on,
                bool hot, Gdiplus::Font& f) {
    const float cx = static_cast<float>(row.x) + 9.0f;
    const float cy = static_cast<float>(row.y + row.h / 2);
    const Gdiplus::ARGB ring = on ? tok::primary : (hot ? tok::onVariant : 0xFFC4C9D0);
    Gdiplus::Pen pen(ring, 2.0f);
    g.DrawEllipse(&pen, cx - 8.0f, cy - 8.0f, 16.0f, 16.0f);
    if (on) {
        Gdiplus::SolidBrush dot(tok::primary);
        g.FillEllipse(&dot, cx - 4.0f, cy - 4.0f, 8.0f, 8.0f);
    }
    text(g, label, Rect{row.x + 26, row.y, row.w - 26, row.h}, f,
         on ? tok::onSurface : tok::onVariant);
}

void paintSwitch(Gdiplus::Graphics& g, const Rect& r, bool on, bool hot) {
    const float rad = static_cast<float>(r.h) / 2.0f;
    const Gdiplus::ARGB track =
        on ? (hot ? tok::primaryHover : tok::primary) : (hot ? 0xFFDCE0E6 : 0xFFE4E7EC);
    fillRound(g, r, rad, track);
    const float knob = static_cast<float>(r.h) - 6.0f;
    const float kx = on ? static_cast<float>(r.x + r.w) - 3.0f - knob
                        : static_cast<float>(r.x) + 3.0f;
    Gdiplus::SolidBrush w(0xFFFFFFFF);
    g.FillEllipse(&w, kx, static_cast<float>(r.y) + 3.0f, knob, knob);
}

void paint(HWND hwnd, Panel* p) {
    const Layout L = layout({p->wifiEnabled, p->btEnabled, p->usbEnabled});
    const auto s = p->session->snapshot();

    RECT crc{};
    GetClientRect(hwnd, &crc);
    const int cw = crc.right, ch = crc.bottom;

    HDC dc = GetDC(hwnd);
    HDC mem = CreateCompatibleDC(dc);
    HBITMAP bmp = CreateCompatibleBitmap(dc, cw, ch);
    HGDIOBJ old = SelectObject(mem, bmp);
    {
        Gdiplus::Graphics g(mem);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        g.SetTextRenderingHint(Gdiplus::TextRenderingHintClearTypeGridFit);
        g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHalf);

        Gdiplus::SolidBrush bg(tok::bg);
        g.FillRectangle(&bg, 0, 0, cw, ch);

        // —— 顶栏：应用名 + 副标题 + 状态徽章 ——
        text(g, L"全能外设", Rect{kMargin + 4, 18, 260, 32}, *p->fTitle, tok::onSurface);
        text(g, L"手机当鼠标 / 键盘 / 声卡 / 摄像头用", Rect{kMargin + 5, 50, 360, 18},
             *p->fCaption, tok::onVariant);

        fillRound(g, L.badge, static_cast<float>(L.badge.h) / 2.0f, tok::primarySoft);
        text(g, phaseText(s), L.badge, *p->fBadge, phaseColor(s.phase), 1);

        // —— 连接卡：三个传输开关（蓝牙 / 无线 / USB），无总开关 ——
        fillRound(g, L.cardConn, 18.0f, tok::surface);
        text(g, L"连接方式", L.titleConn, *p->fSection, tok::primary);
        paintSwitch(g, L.swWifi, p->wifiEnabled, p->hot == Hit::SwitchWifi);
        text(g, L"无线", L.lblWifi, *p->fBody, p->wifiEnabled ? tok::onSurface : tok::onVariant);
        paintSwitch(g, L.swBt, p->btEnabled, p->hot == Hit::SwitchBt);
        text(g, L"蓝牙", L.lblBt, *p->fBody, p->btEnabled ? tok::onSurface : tok::onVariant);
        paintSwitch(g, L.swUsb, p->usbEnabled, p->hot == Hit::SwitchUsb);
        text(g, L"USB", L.lblUsb, *p->fBody, p->usbEnabled ? tok::onSurface : tok::onVariant);
        // 连接详情（无线才真正连；蓝牙/USB 由手机侧发起）
        if (s.phase == LinkPhase::Connected) {
            const long long sec = s.upMs / 1000;
            wchar_t b[256];
            std::swprintf(b, 256, L"%s · RTT %.0f ms · 已连 %lld:%02lld:%02lld",
                          toWide(s.peer).c_str(), s.rttMs, sec / 3600, (sec / 60) % 60,
                          sec % 60);
            text(g, b, L.connStatus, *p->fCaption, tok::onVariant);
        } else if (!p->wifiEnabled) {
            text(g, L"打开「无线」开关以连接手机（蓝牙 / USB 由手机端发起）", L.connStatus,
                 *p->fCaption, tok::onVariant);
        } else {
            text(g, detailText(s), L.connStatus, *p->fCaption, tok::onVariant);
        }
        // 无线手动地址（始终展示，自动发现失败时兜底）
        text(g, L"地址", Rect{kContentX, L.fieldHost.y, 44, L.fieldHost.h}, *p->fBody,
             tok::onVariant);
        fillRound(g, L.fieldHost, 8.0f, tok::fieldBg);
        fillRound(g, L.fieldPort, 8.0f, tok::fieldBg);

        // —— 无线分类：打开「无线」才出现 ——
        if (p->wifiEnabled) {
            text(g, L"无线", L.hdrWireless, *p->fSection, tok::primary);
            // 副屏
            fillRound(g, L.cardScreen, 18.0f, tok::surface);
            text(g, L"副屏（把本机桌面投到手机）", L.labelScreen, *p->fSection, tok::primary);
            {
                const bool pushing = p->screenPush && p->screenPush->status().running;
                paintSwitch(g, L.switchScreen, pushing, p->hot == Hit::SwitchScreen);
                text(g, screenBig(p), L.screenStatus, *p->fBody, screenColor(p));
                text(g, screenDetail(p), L.screenDetail, *p->fCaption, tok::onVariant);
            }
            // 音箱
            fillRound(g, L.cardSpeaker, 18.0f, tok::surface);
            text(g, L"音箱（把电脑声音投到手机）", L.labelSpeaker, *p->fSection, tok::primary);
            {
                const bool playing = p->audio && p->audio->running();
                paintSwitch(g, L.switchSpeaker, playing, p->hot == Hit::SwitchSpeaker);
                text(g, speakerBig(p), L.speakerStatus, *p->fBody, speakerColor(p));
                text(g, L"采集设备", L.labelSpeakerDev, *p->fCaption, tok::onVariant);
                const std::wstring dev = speakerDeviceLine(p);
                if (!dev.empty()) {
                    text(g, dev, L.speakerDevice, *p->fCaption, tok::onVariant);
                }
                text(g, speakerDetail(p), L.speakerDetail, *p->fCaption, tok::onVariant);

                const bool ttBusy = p->testToneBusy.load();
                const uint64_t ttSent = p->testToneSent.load();
                paintButton(g, L.btnTestSpeaker,
                            ttBusy ? L"试听中…" : (ttSent > 0 ? L"再试听" : L"试听"),
                            true, p->media && p->media->status().connected,
                            p->hot == Hit::TestSpeaker, p->pressed == Hit::TestSpeaker, *p->fBtn);

                const std::wstring phoneTxt =
                    (s.phase == LinkPhase::Connected) ? phoneAudioText(s) : std::wstring();
                const int tx = L.btnTestSpeaker.x + L.btnTestSpeaker.w + 10;
                const int tw = kCardW - 2 * kCardPad - L.btnTestSpeaker.w - 10;
                if (ttSent > 0 || ttBusy) {
                    wchar_t tb[96];
                    std::swprintf(tb, 96, L"已送手机 %llu 片%s",
                                  static_cast<unsigned long long>(ttSent),
                                  ttBusy ? L" · 推流中" : L"");
                    text(g, tb, Rect{tx, L.btnTestSpeaker.y, tw, 28}, *p->fCaption,
                         tok::onVariant);
                } else if (!phoneTxt.empty()) {
                    text(g, phoneTxt, Rect{tx, L.btnTestSpeaker.y, tw, 28}, *p->fCaption,
                         phoneAudioColor(s));
                }
            }
            // 麦克风（手机麦克风上行 → PC 收流；转发 = 渲染到 PC 播放设备）
            fillRound(g, L.cardMic, 18.0f, tok::surface);
            text(g, L"麦克风（手机当电脑麦克风）", L.labelMic, *p->fSection, tok::primary);
            {
                const uint64_t mic = p->media ? p->media->counters().micFrames : 0;
                Gdiplus::ARGB col = tok::stateIdle;
                std::wstring st;
                if (s.phase != LinkPhase::Connected) {
                    st = L"未连接";
                } else if (!s.phoneAudioKnown) {
                    st = L"等待手机音频状态";
                } else if (s.phoneAudioMic) {
                    st = L"手机麦克风已上行"; col = tok::stateOk;
                } else {
                    st = L"手机麦克风未开启"; col = tok::stateWarn;
                }
                // 转发开关（开关在，链路没连也能开——连上即生效）
                const bool fwd = p->micBridge && p->micBridge->running();
                paintSwitch(g, L.switchMicFwd, fwd, p->hot == Hit::SwitchMic);
                text(g, st, L.micStatus, *p->fBody, col);
                text(g, L"转发到", L.labelMicDev, *p->fCaption, tok::onVariant);

                std::wstring detail;
                if (fwd) {
                    const auto ms = p->micBridge->status();
                    if (!ms.error.empty()) {
                        detail = L"转发失败：" + toWide(ms.error);
                    } else {
                        wchar_t fb[192];
                        std::swprintf(fb, 192, L"转发中 → %s（已送 %llu ms 丢 %llu）",
                                      toWide(ms.device).c_str(),
                                      static_cast<unsigned long long>(ms.played / 96),
                                      static_cast<unsigned long long>(ms.dropped / 192));
                        detail = fb;
                        col = tok::stateOk;
                    }
                } else {
                    detail = L"打开右上开关：选虚拟声卡（如 VB-Cable 输入）即可让微信/会议用手机麦；"
                             L"选真实声卡 = 直接听手机的声音";
                }
                text(g, detail, L.micDetail, *p->fCaption, fwd ? col : tok::onVariant);
            }

            // 摄像头（手机相机 JPEG → 卡内实时预览）
            fillRound(g, L.cardCam, 18.0f, tok::surface);
            text(g, L"摄像头（手机相机 → PC）", L.labelCam, *p->fSection, tok::primary);
            {
                const uint64_t cam = p->media ? p->media->counters().cameraFrames : 0;
                Gdiplus::ARGB col = tok::stateIdle;
                std::wstring st;
                if (s.phase != LinkPhase::Connected) {
                    st = L"未连接";
                } else if (cam == 0) {
                    st = L"等待画面（确认手机摄像头权限已授予）";
                } else {
                    st = L"摄像头运行中"; col = tok::stateOk;
                }
                text(g, st, L.camStatus, *p->fBody, col);

                std::vector<uint8_t> jpeg;
                bool valid = false;
                {
                    std::lock_guard<std::mutex> lk(p->camMu);
                    jpeg = p->camJpeg;
                    valid = p->camJpegValid;
                }
                bool drawn = false;
                if (valid && !jpeg.empty()) {
                    IStream* stm = nullptr;
                    if (::CreateStreamOnHGlobal(nullptr, TRUE, &stm) == S_OK) {
                        ULONG written = 0;
                        stm->Write(jpeg.data(), static_cast<ULONG>(jpeg.size()), &written);
                        Gdiplus::Image img(stm, FALSE);
                        if (img.GetLastStatus() == Gdiplus::Ok && img.GetWidth() > 0) {
                            Gdiplus::RectF dst(
                                static_cast<Gdiplus::REAL>(L.camPreview.x),
                                static_cast<Gdiplus::REAL>(L.camPreview.y),
                                static_cast<Gdiplus::REAL>(L.camPreview.w),
                                static_cast<Gdiplus::REAL>(L.camPreview.h));
                            Gdiplus::SolidBrush bg(Gdiplus::Color(0xFF101214));
                            g.FillRectangle(&bg, dst);
                            g.SetInterpolationMode(Gdiplus::InterpolationModeBilinear);
                            g.DrawImage(&img, dst, 0, 0,
                                        static_cast<Gdiplus::REAL>(img.GetWidth()),
                                        static_cast<Gdiplus::REAL>(img.GetHeight()),
                                        Gdiplus::UnitPixel);
                            drawn = true;
                        }
                        stm->Release();
                    }
                }
                if (!drawn) {
                    Gdiplus::SolidBrush bg(Gdiplus::Color(0xFF101214));
                    Gdiplus::RectF dst(
                        static_cast<Gdiplus::REAL>(L.camPreview.x),
                        static_cast<Gdiplus::REAL>(L.camPreview.y),
                        static_cast<Gdiplus::REAL>(L.camPreview.w),
                        static_cast<Gdiplus::REAL>(L.camPreview.h));
                    g.FillRectangle(&bg, dst);
                }

                wchar_t cb[160];
                std::swprintf(cb, 160, L"PC 已收 %llu 帧 · JPEG · 无需虚拟摄像头即可预览",
                              static_cast<unsigned long long>(cam));
                text(g, cb, L.camDetail, *p->fCaption, tok::onVariant);
            }
        }

        // —— 有线分类：打开「蓝牙」或「USB」才出现 ——
        if (p->btEnabled || p->usbEnabled) {
            text(g, L"有线", L.hdrWired, *p->fSection, tok::primary);
            if (p->btEnabled) {
                fillRound(g, L.cardBt, 18.0f, tok::surface);
                text(g, L"蓝牙键鼠（手机当无线鼠标 / 键盘）", L.labelBtCard, *p->fSection,
                     tok::primary);
                text(g, L"由手机端蓝牙配对后自动启用", L.btStatus, *p->fBody, tok::stateIdle);
                text(g, L"PC 免驱识别为鼠标 / 键盘 / 多媒体键", L.btDetail, *p->fCaption,
                     tok::onVariant);
            }
            if (p->usbEnabled) {
                fillRound(g, L.cardUsb, 18.0f, tok::surface);
                text(g, L"USB 外设（插线即用）", L.labelUsbCard, *p->fSection, tok::primary);
                text(g, L"插线并在手机端授权后启用", L.usbStatus, *p->fBody, tok::stateIdle);
                text(g, L"UAC2 声卡（音箱）+ HID 键鼠，免驱零配置", L.usbDetail, *p->fCaption,
                     tok::onVariant);
            }
        }

        // —— 底部：开机自启 + 次要按钮 ——
        paintSwitch(g, L.switchAuto, p->autostart, p->hot == Hit::Autostart);
        text(g, L"开机自启", L.labelAuto, *p->fBody, tok::onSurface);
        paintButton(g, L.btnHide, L"隐藏到托盘", false, true, p->hot == Hit::Hide,
                    p->pressed == Hit::Hide, *p->fBtn);
        paintButton(g, L.btnQuit, L"退出", false, true, p->hot == Hit::Quit,
                    p->pressed == Hit::Quit, *p->fBtn);
    }
    // 必须**在位图仍选中时**拷贝：先 SelectObject 还原的话，BitBlt 读到的就是
    // 原来那张 1x1 单色位图，整窗等于什么都没画（真机踩过）
    BitBlt(dc, 0, 0, cw, ch, mem, 0, 0, SRCCOPY);
    SelectObject(mem, old);
    DeleteObject(bmp);
    DeleteDC(mem);
    ReleaseDC(hwnd, dc);
}

// ——————————————————— 行为 ———————————————————
/// 功能卡随传输开关显隐，整窗高度也要跟着变（AdjustWindowRect 算标题栏）
void resizeToLayout(Panel* p) {
    if (!p->hwnd) return;
    const Layout L = layout({p->wifiEnabled, p->btEnabled, p->usbEnabled});
    const DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    RECT rc{0, 0, kClientW, L.totalH};
    AdjustWindowRectEx(&rc, style, FALSE, 0);
    SetWindowPos(p->hwnd, nullptr, 0, 0, rc.right - rc.left, rc.bottom - rc.top,
                 SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
}

void applyConnect(Panel* p) {
    if (!p->session) return;
    if (p->autoMode) {
        p->session->startAuto();
        return;
    }
    wchar_t host[128] = {0};
    wchar_t port[16] = {0};
    GetWindowTextW(GetDlgItem(p->hwnd, IDC_EDIT_HOST), host, 128);
    GetWindowTextW(GetDlgItem(p->hwnd, IDC_EDIT_PORT), port, 16);
    const int portNum = _wtoi(port);
    if (host[0] == L'\0' || portNum <= 0 || portNum > 65535) {
        MessageBoxW(p->hwnd, L"请填写有效的地址与端口（1–65535）", L"全能外设",
                    MB_OK | MB_ICONWARNING);
        return;
    }
    p->session->connectManual(toUtf8(host), static_cast<uint16_t>(portNum));
}

void performHit(Panel* p, Hit h) {
    switch (h) {
        case Hit::SwitchWifi:
            // 无线开关 = 实际发起/断开 Wi‑Fi 控制连接（无独立「连接」按钮）
            p->wifiEnabled = !p->wifiEnabled;
            if (p->wifiEnabled) applyConnect(p);
            else if (p->session) p->session->disconnect();
            resizeToLayout(p);
            break;
        case Hit::SwitchBt:
            p->btEnabled = !p->btEnabled;       // 蓝牙由手机侧配对后启用，PC 仅揭示设置
            resizeToLayout(p);
            break;
        case Hit::SwitchUsb:
            p->usbEnabled = !p->usbEnabled;     // USB 由手机侧插线授权后启用
            resizeToLayout(p);
            break;
        case Hit::SwitchScreen:
            toggleScreen(p);
            break;
        case Hit::SwitchSpeaker:
            toggleSpeaker(p);
            break;
        case Hit::SwitchMic:
            toggleMicForward(p);
            break;
        case Hit::TestSpeaker:
            testSpeakerClick(p);
            break;
        case Hit::Autostart:
            p->autostart = !p->autostart;
            // 写的是**当前运行 exe** 的路径；桌面端自启不带 "serve"
            apxpc::tray::setAutostart(p->autostart, {}, "");
            break;
        case Hit::Hide:
            ShowWindow(p->hwnd, SW_HIDE);
            break;
        case Hit::Quit:
            if (p->tray) {
                p->tray->setQuitCallback(nullptr);
                p->tray->setOpenCallback(nullptr);
            }
            DestroyWindow(p->hwnd);
            break;
        default:
            break;
    }
    // 地址框只在手动模式可编辑
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_HOST), !p->autoMode);
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_PORT), !p->autoMode);
}

// ——————————————————— 窗口 ———————————————————
void makeFonts(Panel* p) {
    using Gdiplus::Font;
    using Gdiplus::FontStyleBold;
    using Gdiplus::FontStyleRegular;
    using Gdiplus::UnitPixel;
    const wchar_t* face = L"Microsoft YaHei UI";
    p->fTitle.reset(new Font(face, 20.0f, FontStyleBold, UnitPixel));
    p->fCaption.reset(new Font(face, 12.0f, FontStyleRegular, UnitPixel));
    p->fSection.reset(new Font(face, 13.0f, FontStyleBold, UnitPixel));
    p->fBody.reset(new Font(face, 13.5f, FontStyleRegular, UnitPixel));
    p->fStatus.reset(new Font(face, 30.0f, FontStyleBold, UnitPixel));
    p->fBtn.reset(new Font(face, 14.0f, FontStyleBold, UnitPixel));
    p->fBadge.reset(new Font(face, 12.0f, FontStyleBold, UnitPixel));

    p->hEditFont = CreateFontW(-15, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                               DEFAULT_CHARSET, OUT_TT_PRECIS, CLIP_DEFAULT_PRECIS,
                               CLEARTYPE_QUALITY, DEFAULT_PITCH, face);
    p->hFieldBrush = CreateSolidBrush(RGB(0xF2, 0xF3, 0xF5));
}

void createChildren(Panel* p) {
    const Layout L = layout({p->wifiEnabled, p->btEnabled, p->usbEnabled});
    auto mkEdit = [&](int id, const Rect& r, const wchar_t* text) {
        HWND e = CreateWindowExW(
            0, L"EDIT", text,
            WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL | WS_TABSTOP,
            r.x, r.y, r.w, r.h, p->hwnd,
            reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), nullptr, nullptr);
        SendMessageW(e, WM_SETFONT, reinterpret_cast<WPARAM>(p->hEditFont), TRUE);
        return e;
    };
    // 地址框默认留空：预填某个具体 IP 只对开发机成立，出厂包里等于误导用户。
    // 用系统「提示气泡」（cue banner）说明该填什么，一聚焦就消失。
    HWND hostEdit = mkEdit(IDC_EDIT_HOST, L.editHost, L"");
    SendMessageW(hostEdit, EM_SETCUEBANNER, TRUE,
                 reinterpret_cast<LPARAM>(L"手机 IP，例：192.168.1.20"));
    mkEdit(IDC_EDIT_PORT, L.editPort, L"9500");

    // 音箱的「采集设备」下拉：原生 COMBOBOX（与上面的 EDIT 同一套做法，
    // 自绘一个带滚动列表的下拉不值得）
    const Rect& rc = L.speakerCombo;
    HWND combo = CreateWindowExW(
        0, L"COMBOBOX", nullptr,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | WS_VSCROLL | CBS_DROPDOWNLIST,
        rc.x, rc.y, rc.w, rc.h + 8 * 20,   // 高度参数 = 展开后的下拉高度
        p->hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(IDC_COMBO_DEV)), nullptr, nullptr);
    if (combo) {
        SendMessageW(combo, WM_SETFONT, reinterpret_cast<WPARAM>(p->hEditFont), TRUE);
    }
    // 麦克风桥：渲染到哪块播放设备
    const Rect& rcMic = L.micCombo;
    HWND comboMic = CreateWindowExW(
        0, L"COMBOBOX", nullptr,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | WS_VSCROLL | CBS_DROPDOWNLIST,
        rcMic.x, rcMic.y, rcMic.w, rcMic.h + 8 * 20,
        p->hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(IDC_COMBO_MIC)), nullptr, nullptr);
    if (comboMic) {
        SendMessageW(comboMic, WM_SETFONT, reinterpret_cast<WPARAM>(p->hEditFont), TRUE);
    }
    refreshSpeakerCombo(p);
    refreshMicCombo(p);
    p->lastDevSig = speakerDevSignature();   // 首帧指纹，避免首个 tick 立刻重刷
}

void refreshNow(Panel* p) {
    // 输入框可用性跟随模式（首帧也要正确）
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_HOST), !p->autoMode);
    EnableWindow(GetDlgItem(p->hwnd, IDC_EDIT_PORT), !p->autoMode);
}

/// 从控制链路的 "host:port" 里取出 host —— 媒体通道连的是同一台手机
std::string peerHost(const std::string& peer) {
    const size_t c = peer.rfind(':');
    return c == std::string::npos ? peer : peer.substr(0, c);
}

/// 节流地发起一次媒体建链。
/// **绝不能在 UI 线程里直接 connect** —— 它是 3 秒级阻塞操作，会把整个窗口卡死
/// （这与 WirelessSession 把建链放后台线程是同一个理由）。
void pumpMediaConnect(Panel* p, const std::string& host) {
    if (!p->media || host.empty()) return;
    if (p->mediaBusy.load()) return;                        // 上一轮还在连
    if (p->mediaThread.joinable()) p->mediaThread.join();   // 已结束，join 立即返回
    p->mediaBusy.store(true);
    p->mediaThread = std::thread([p, host] {
        const bool ok = p->media->connect(host, apxpc::media::kMediaPort);
        if (ok) APX_LOGI("媒体通道已连接 {}:{}", host, apxpc::media::kMediaPort);
        p->mediaBusy.store(false);
    });
}

void tick(Panel* p) {
    if (!p->session) return;
    const auto s = p->session->snapshot();

    // —— 控制面（Wi‑Fi）连接跟着「无线」开关走（无独立连接按钮）——
    if (p->wifiEnabled) {
        if (s.phase == LinkPhase::Idle || s.phase == LinkPhase::Failed) applyConnect(p);
    } else if (s.phase != LinkPhase::Idle) {
        p->session->disconnect();
    }

    // —— 扩展屏触摸映射：找非主显示器（虚拟屏）矩形喂给触摸注入 ——
    // 每 tick 都找（虚拟屏分辨率/位置可被用户随时改）。推流停了就回主屏模式。
    {
        RECT vr{0, 0, 0, 0};
        bool found = false;
        if (p->screenPush && p->screenPush->status().running) {
            // EnumDisplayMonitors 的回调环境苛刻，改用 MonitorFromWindow 系：
            // 主屏 = 包含 (0,0) 的那个；第一个 rect 不含 (0,0) 的活动监视器即虚拟屏
            const POINT origin{0, 0};
            const HMONITOR hPri = ::MonitorFromPoint(origin, MONITOR_DEFAULTTOPRIMARY);
            ::EnumDisplayMonitors(nullptr, nullptr, [](HMONITOR hmon, HDC, LPRECT, LPARAM lp) -> BOOL {
                MONITORINFO mi{};
                mi.cbSize = sizeof(mi);
                if (::GetMonitorInfoW(hmon, &mi) && (mi.dwFlags & MONITORINFOF_PRIMARY) == 0) {
                    auto* out = reinterpret_cast<LPRECT>(lp);
                    *out = mi.rcMonitor;
                    return FALSE;   // 找到第一个非主屏即停
                }
                return TRUE;
            }, reinterpret_cast<LPARAM>(&vr));
            MONITORINFO pri{};
            pri.cbSize = sizeof(pri);
            if (::GetMonitorInfoW(hPri, &pri)) {
                found = !(vr.left == pri.rcMonitor.left && vr.top == pri.rcMonitor.top &&
                          vr.right == pri.rcMonitor.right && vr.bottom == pri.rcMonitor.bottom);
            }
        }
        if (found && p->session) {
            p->session->setTouchRect(vr.left, vr.top,
                                     vr.right - vr.left, vr.bottom - vr.top);
        } else if (p->session) {
            p->session->setTouchRect(0, 0, 0, 0);
        }
    }

    // —— 媒体通道生命周期：跟着控制链路走 ——
    // 控制面连上才连媒体；控制面断开就把媒体一起收掉并停掉副屏，
    // 否则会留下一条没人管的连接，一直占着手机侧的单对端名额。
    if (p->media) {
        const bool controlUp = (s.phase == LinkPhase::Connected);
        const bool mediaUp = p->media->status().connected;
        if (controlUp) {
            if (!mediaUp && nowMsLocal() - p->lastMediaTryMs >= kMediaRetryMs) {
                p->lastMediaTryMs = nowMsLocal();
                pumpMediaConnect(p, peerHost(s.peer));
            }
            // 手机端切回副屏页：请求下一编码帧为 IDR，立刻出画
            if (p->session->takeKeyFrameRequest()) {
                if (p->screenPush) p->screenPush->requestKeyFrame();
            }
        } else if (mediaUp || (!p->mediaBusy.load() && p->mediaThread.joinable())) {
            if (p->screenPush && p->screenPush->running()) p->screenPush->stop();
            // 音箱同理：连接没了就停采集，别让它在后台空转
            if (p->audio && p->audio->running()) p->audio->stop();
            if (p->mediaThread.joinable()) p->mediaThread.join();
            p->media->disconnect();
        }
    }

    // 每 2 秒比一次设备指纹（枚举要走 COM，别每 400ms 都问）。指纹变了有两种情形，
    // 两种都得处理，否则面板会**看起来一切正常、实际采的是错的那块**：
    //   ① 用户换了系统默认设备 —— 下拉标题里写的还是旧设备名；
    //   ② 插拔了耳机/显示器 —— 列表里多一项或少一项，选中的那块可能已经没了。
    if (p->audio && nowMsLocal() - p->lastDevCheckMs >= 2000) {
        p->lastDevCheckMs = nowMsLocal();
        const std::string sig = speakerDevSignature();
        if (sig != p->lastDevSig) {
            p->lastDevSig = sig;
            refreshSpeakerCombo(p);   // 名字/项数都变了，下拉要跟上
            refreshMicCombo(p);       // 麦克风桥的「转发到」下拉同步刷新
            // 若在"跟随系统默认"，采集得跟着新默认走。查 deviceId 而不是只信 sig：
            // 上面的刷新可能把"选中的设备被拔掉"回落成了跟随默认，这里一并接住。
            if (p->audio->running() && p->speakerDeviceId.empty()) {
                APX_LOGI("默认播放设备已切换，音箱改采新默认设备");
                apxpc::media::AudioCaptureOptions opt;   // deviceId 留空 = 跟新的默认
                std::string err;
                if (!p->audio->start(p->media.get(), opt, &err)) {
                    APX_LOGW("跟随默认设备重开采集失败：{}", err);
                }
            }
        }
    }

    std::wstring key = statusKey(p, s);
    key += screenBig(p);
    if (p->screenPush) {
        const auto ss = p->screenPush->status();
        wchar_t b[192];
        std::swprintf(b, 192, L"|%d|%.1f|%llu|%llu|%u|%u|%s",
                      ss.running ? 1 : 0, ss.fps,
                      static_cast<unsigned long long>(ss.framesSent),
                      static_cast<unsigned long long>(ss.framesDropped),
                      ss.width, ss.height, toWide(ss.error).c_str());
        key += b;
    }
    key += speakerBig(p);
    if (p->audio) {
        const auto as = p->audio->status();
        wchar_t b[192];
        // 电平量化到 5% 一档：既能让音量条动起来，又不至于每 400ms 都被小数抖动逼着重绘
        std::swprintf(b, 192, L"|%d|%d|%llu|%s", as.running ? 1 : 0,
                      static_cast<int>(as.peak * 20.0), 
                      static_cast<unsigned long long>(as.framesSent),
                      toWide(as.error).c_str());
        key += b;
    }
    if (key != p->repaintKey) {
        p->repaintKey = key;
        InvalidateRect(p->hwnd, nullptr, FALSE);
    }
}

LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    auto* p = reinterpret_cast<Panel*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));

    switch (msg) {
        case WM_COMMAND: {
            if (p && LOWORD(wp) == IDC_COMBO_DEV && HIWORD(wp) == CBN_SELCHANGE) {
                onSpeakerDeviceChanged(p);
                return 0;
            }
            if (p && LOWORD(wp) == IDC_COMBO_MIC && HIWORD(wp) == CBN_SELCHANGE) {
                onMicDeviceChanged(p);
                return 0;
            }
            break;
        }
        case WM_CREATE: {
            auto* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
            p = static_cast<Panel*>(cs->lpCreateParams);
            p->hwnd = hwnd;
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(p));
            makeFonts(p);
            createChildren(p);
            refreshNow(p);
            SetTimer(hwnd, kRefreshTimer, 400, nullptr);
            return 0;
        }

        case WM_ERASEBKGND:
            return 1;   // 自绘 + 双缓冲，禁用系统擦背景以免闪

        case WM_PAINT: {
            PAINTSTRUCT ps{};
            BeginPaint(hwnd, &ps);
            if (p && p->session) paint(hwnd, p);
            EndPaint(hwnd, &ps);
            return 0;
        }

        case WM_TIMER:
            if (p && wp == kRefreshTimer) tick(p);
            return 0;

        case WM_CTLCOLOREDIT: {
            if (!p) break;
            HDC dc = reinterpret_cast<HDC>(wp);
            SetTextColor(dc, RGB(0x19, 0x19, 0x19));
            SetBkColor(dc, RGB(0xF2, 0xF3, 0xF5));
            return reinterpret_cast<LRESULT>(p->hFieldBrush);
        }

        case WM_MOUSEMOVE: {
            if (!p) break;
            if (!p->tracking) {
                TRACKMOUSEEVENT tme{sizeof(tme), TME_LEAVE, hwnd, 0};
                TrackMouseEvent(&tme);
                p->tracking = true;
            }
            const Hit h = hitTest(p, GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            if (h != p->hot) {
                p->hot = h;
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }

        case WM_MOUSELEAVE:
            if (p) {
                p->tracking = false;
                if (p->hot != Hit::None) {
                    p->hot = Hit::None;
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            return 0;

        case WM_LBUTTONDOWN: {
            if (!p) break;
            SetFocus(hwnd);   // 点空白处收回输入框焦点
            const Hit h = hitTest(p, GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            if (h != Hit::None) {
                p->pressed = h;
                SetCapture(hwnd);
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }

        case WM_LBUTTONUP: {
            if (!p) break;
            const Hit h = hitTest(p, GET_X_LPARAM(lp), GET_Y_LPARAM(lp));
            const Hit was = p->pressed;
            if (was != Hit::None) {
                ReleaseCapture();
                p->pressed = Hit::None;
                if (h == was) performHit(p, was);
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }

        case WM_SYSCOMMAND:
            // 最小化 = 收进托盘（后台还要持续接收输入注入）
            if ((wp & 0xFFF0) == SC_MINIMIZE) {
                ShowWindow(hwnd, SW_HIDE);
                return 0;
            }
            break;

        case WM_CLOSE:
            // 关窗口 ≠ 退出：输入注入要继续工作。退出走托盘菜单或窗口里的「退出」
            ShowWindow(hwnd, SW_HIDE);
            return 0;

        case kMsgTrayQuit:
            if (p) performHit(p, Hit::Quit);
            return 0;

        case WM_DESTROY:
            KillTimer(hwnd, kRefreshTimer);
            if (p) {
                if (p->session) p->session->stop();
                if (p->tray) {
                    p->tray->setQuitCallback(nullptr);
                    p->tray->setOpenCallback(nullptr);
                    p->tray->quit();
                    p->tray.reset();
                }
                if (p->hEditFont) { DeleteObject(p->hEditFont); p->hEditFont = nullptr; }
                if (p->hFieldBrush) { DeleteObject(p->hFieldBrush); p->hFieldBrush = nullptr; }
                p->fTitle.reset(); p->fCaption.reset(); p->fSection.reset();
                p->fBody.reset(); p->fStatus.reset(); p->fBtn.reset(); p->fBadge.reset();
            }
            PostQuitMessage(0);
            return 0;

        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

bool panelAvailable() { return true; }

int runPanel(const std::string& /*preferInstanceId*/) {
    // 单实例：面板没有互斥保护时，"安装版 + 编译版"或误双击会出现两个实例，
    // 两条无线连接互相抢手机的单对端通道、SendInput 双份注入 —— 表现就是"莫名抽风/退出"。
    // 已有实例在跑时：把它拉到前台后本进程退出（与托盘双击同语义）。
#if defined(_WIN32)
    HANDLE mutex = ::CreateMutexW(nullptr, TRUE, L"Local\\AllPeriph.Panel.SingleInstance");
    if (::GetLastError() == ERROR_ALREADY_EXISTS) {
        // 已有实例：优先把它的窗口拉到前台（隐藏在托盘也找得到——窗口对象仍在）。
        // 找不到窗口（持有者异常/正要退出）时重试片刻后**接管启动**，绝不让用户双击无响应。
        HWND prev = nullptr;
        for (int i = 0; i < 10; ++i) {
            prev = ::FindWindowW(L"AllPeriphPanel", nullptr);
            if (prev) break;
            ::Sleep(300);
        }
        if (prev) {
            if (mutex) ::CloseHandle(mutex);
            ::ShowWindow(prev, SW_RESTORE);
            ::ShowWindow(prev, SW_SHOW);
            // SetForegroundWindow 会被 Windows 前台锁定拒绝（新实例不是前台进程）——
            // 窗口只在任务栏闪一下，用户就以为"双击打不开"。topmost 置一瞬再收回，
            // 强制把窗口顶到眼前。
            ::SetWindowPos(prev, HWND_TOPMOST, 0, 0, 0, 0,
                           SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            ::SetWindowPos(prev, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
            ::SetForegroundWindow(prev);
            APX_LOGI("面板已有实例在跑，已拉起其窗口，本进程退出");
            return 0;
        }
        // mutex 在但 3 秒内没等到窗口：视为持有者异常，本进程接管继续运行
        APX_LOGW("检测到单实例锁但无面板窗口，接管运行");
    }
#endif
    Gdiplus::GdiplusStartupInput gdiIn{};
    ULONG_PTR gdiToken = 0;
    if (Gdiplus::GdiplusStartup(&gdiToken, &gdiIn, nullptr) != Gdiplus::Ok) {
        APX_LOGE("控制面板：GDI+ 初始化失败");
        return -1;
    }
    struct GdiGuard {
        ULONG_PTR token;
        ~GdiGuard() { Gdiplus::GdiplusShutdown(token); }
    } gdiGuard{gdiToken};

    Panel panel;
    panel.session = std::make_unique<WirelessSession>();
    panel.autostart = apxpc::tray::autostartEnabled();

    HINSTANCE inst = GetModuleHandleW(nullptr);
    HICON icon = LoadIconW(inst, MAKEINTRESOURCEW(kAppIconId));
    if (!icon) icon = LoadIconW(nullptr, IDI_APPLICATION);

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.style = CS_DBLCLKS;
    wc.lpfnWndProc = wndProc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hIcon = icon;
    wc.hIconSm = icon;
    wc.hbrBackground = nullptr;   // 全部自绘
    wc.lpszClassName = L"AllPeriphPanel";
    if (!RegisterClassExW(&wc)) {
        APX_LOGE("控制面板：注册窗口类失败（GetLastError={}）", GetLastError());
        return -1;
    }

    const int initH = layout({panel.wifiEnabled, panel.btEnabled, panel.usbEnabled}).totalH;
    RECT rc{0, 0, kClientW, initH};
    const DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    AdjustWindowRectEx(&rc, style, FALSE, 0);
    HWND hwnd = CreateWindowExW(0, L"AllPeriphPanel", L"全能外设", style, CW_USEDEFAULT,
                                CW_USEDEFAULT, rc.right - rc.left, rc.bottom - rc.top,
                                nullptr, nullptr, inst, &panel);
    if (!hwnd) {
        APX_LOGE("控制面板：创建窗口失败（GetLastError={}）", GetLastError());
        return -1;
    }
    SendMessageW(hwnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(icon));
    SendMessageW(hwnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(icon));

    // 托盘：双击显隐窗口，「退出」真正退出（图标与窗口同一枚）
    panel.tray = std::make_unique<TrayIcon>();
    panel.tray->setOpenCallback([hwnd] {
        ShowWindow(hwnd, SW_SHOW);
        SetForegroundWindow(hwnd);
    });
    panel.tray->setQuitCallback([hwnd] { PostMessageW(hwnd, kMsgTrayQuit, 0, 0); });
    panel.tray->create("全能外设 · 手机当鼠标 / 键盘用", icon);

    // 媒体通道与副屏推流：媒体连接随控制链路自动建立/收掉（见 tick），
    // 副屏则由卡片上的开关启停 —— 不再需要单独跑 apxdisp.exe。
    panel.media = std::make_unique<apxpc::media::MediaSession>();
    panel.screenPush = std::make_unique<apxpc::media::ScreenPush>();
    panel.audio = std::make_unique<apxpc::media::AudioCapture>();
    panel.micBridge = std::make_unique<apxpc::media::MicBridge>();
    // 手机麦克风上行（streamId=5）→ 桥（转发开关打开时才真正送渲染）
    // 摄像头 JPEG（streamId=6）→ 卡内预览（paint 时解码，天然限频）
    panel.media->setHandler([&panel](uint8_t streamId, uint8_t, uint32_t,
                                     const uint8_t* body, size_t len) {
        if (streamId == apxpc::media::kStreamMic && panel.micBridge &&
            panel.micBridge->running()) {
            panel.micBridge->feed(body, len);
        } else if (streamId == apxpc::media::kStreamCamera) {
            std::lock_guard<std::mutex> lk(panel.camMu);
            panel.camJpeg.assign(body, body + len);
            panel.camJpegValid = true;
        }
    });

    // 初始：自动发现并立刻进入等待
    panel.session->startAuto();

    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    APX_LOGI("控制面板已启动（手机当鼠标 / 键盘用）");

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    // 收尾顺序不能乱：先停推流（它还在往媒体连接里写帧），再等建链线程收手，
    // 最后才断媒体 —— 否则工作线程可能访问已析构的 MediaSession。
    if (panel.screenPush) panel.screenPush->stop();
    if (panel.audio) panel.audio->stop();
    if (panel.mediaThread.joinable()) panel.mediaThread.join();
    if (panel.media) panel.media->disconnect();
    return 0;
}

}  // namespace apxpc::ui

#else  // 非 Windows：仓库其余平台本来就不提供 GUI

namespace apxpc::ui {
bool panelAvailable() { return false; }
int runPanel(const std::string&) { return -1; }
}  // namespace apxpc::ui

#endif
