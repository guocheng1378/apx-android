#pragma once
#include <functional>
#include <string>

namespace apxpc::app {

struct UpdateCheckResult {
    bool ok = false;           // 网络与 API 是否成功
    bool hasNewer = false;     // 是否存在更新版本
    std::string latestTag;     // 如 "v206"（ok=false 时可能为空）
    std::string error;         // 失败原因（UTF-8，ok=true 时为空）
    // Windows 安装包（*-Setup.exe）的直下地址：从 releases/latest 的 assets 里挑。
    // 空 = 本次 Release 没有 Setup 资产（或非 Windows 构建），一键升级降级为跳转下载页。
    std::string setupUrl;
};

/// 查 GitHub releases/latest 并与当前版本比对。
/// 比对算法与 Android UpdateChecker.kt 一致：去 v 前缀、按非数字切段、
/// 逐段比数值、缺段补 0 —— 所以 v206.1 > v206 > v205 恒成立。
UpdateCheckResult checkForUpdate();

/// 版本段比对：a > b 返回正数，a < b 返回负数，相等返回 0。
int compareVersion(const std::string& a, const std::string& b);

/// 一键升级第一步：把 Windows 安装包（Setup.exe）流式下载到 destDir。
/// 写盘先走 <原名>.part 临时文件，全部下载完才重命名 —— 半包不能被当成安装包运行。
/// progress：0–100 的百分比回调；拿不到 Content-Length 时回调 -1（前端显示"下载中…"）。
/// 仅 Windows 实现；其他平台恒 false（err 给出手动下载指引）。
bool downloadSetupInstaller(const std::string& setupUrl, const std::string& destDir,
                            std::string& outPath, std::string& err,
                            const std::function<void(int percent)>& progress = {});

/// 一键升级第二步：静默启动安装包后立即返回（不等待安装完成）。
/// Inno Setup 的 /CLOSEAPPLICATIONS 会替我们关掉正在运行的旧版，
/// /RESTARTAPPLICATIONS 装完把新版拉回来 —— 调用方随后走正常退出路径即可。
/// 仅 Windows 实现；其他平台恒 false。
bool runSetupInstallerSilently(const std::string& installerPath, std::string& err);

}  // namespace apxpc::app
