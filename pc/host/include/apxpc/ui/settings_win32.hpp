#pragma once
// 桌面面板的「设置…」二级窗口。
//
// ## 为什么单独开一个窗口，而不是塞进主面板
// 主面板是**全自绘 + 手算布局**：加一个控件要动 7~8 处（Hit 枚举 / Layout 字段 / scaleLayout /
// layout 累加 / hitTest / paint / performHit），其中 scaleLayout 是"白名单式"的 ——
// 漏加一处，125%/150% DPI 屏上就错位。而仓库里已有 `file_panel_win32` 这类独立窗口范式。
// 所以把**新设置**放进独立窗口：主面板只多一个按钮，零排版回归风险。
//
// ## 与面板的连接方式
// `Panel` 结构体定义在 panel_win32.cpp 里（file-local），外部翻译单元看不到它。
// 因此这里不传指针，而是**传一组回调**：面板把"读写自己状态"的能力交出来，
// 设置窗口只负责呈现与转发。谁的状态谁负责，也便于单独测。
//
// ## 本窗口承载的能力（都是**真有实现**的，不做假入口）
//  · 自适应码率开关 —— 真实现：ScreenPush 的 ABR（按发送拥塞实时升降码率）
//  · 日志级别 —— 面板进程立即生效，并写入 config.json（apxhost 下次启动也读它）
//  · 导出诊断包 —— 把运行状态落成 JSON 到配置目录并打开该目录
//  · 重新扫描设备 —— 重新发起自动发现/连接
//  · 编码器信息（只读）—— 实际后端 + 硬编/软编 + 能否运行期改码率（如实显示，不猜）
//  · 全局热键注册状态 + 重置默认绑定
#include <functional>
#include <string>

#if defined(_WIN32)
#include <windows.h>
#else
struct HWND__;
using HWND = HWND__*;
#endif

namespace apxpc::ui {

/// 设置窗口 ↔ 面板的桥
struct SettingsCallbacks {
    // —— 自适应码率 ——
    bool adaptive = false;
    std::function<void(bool)> onAdaptive;

    // —— 日志级别（debug / info / warn / error）——
    std::string logLevel = "info";
    std::function<void(const std::string&)> onLogLevel;

    /// 返回**多行**只读文本：实际编码器 / 硬编还是软编 / 能否运行期改码率 / 当前码率与 fps。
    /// 每 1 秒重新调用一次，所以可以反映实时状态。
    std::function<std::string()> encoderInfo;

    std::function<void()> onExportDiag;
    std::function<void()> onRescan;

    /// 热键注册结果说明（面板启动时填好；重置后会再问一次）
    std::function<std::string()> hotkeyNote;
    std::function<void()> onResetHotkeys;
};

/// 打开设置窗口（单例：已开则置前并刷新）
void showSettingsWindow(HWND owner, const SettingsCallbacks& cb);

}  // namespace apxpc::ui
