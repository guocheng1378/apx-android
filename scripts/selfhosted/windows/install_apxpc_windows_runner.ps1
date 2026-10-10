# 全能外设 · Windows self-hosted runner 一键安装脚本
#
# 用法（在飞牛 KVM 的 Win11 x64 虚拟机里）：
#   1. 右键 → 以管理员身份运行 PowerShell
#   2. Set-ExecutionPolicy Bypass -Scope Process -Force
#   3. cd C:\Users\<你>\Downloads\apxpc\scripts\selfhosted\windows
#   4. .\install_apxpc_windows_runner.ps1 -RepoOwner <user> -RepoName allperiph
#      （会自动提示粘贴 GitHub Actions self-hosted runner 的 registration token）
#
# 前置：VM 至少 4 核 CPU + 8GB RAM + 40GB 磁盘；Win11 x64 完整版（非 Core/Nano Server）。
# 时间估计：首次 ~12 分钟（MSVC Build Tools 约 6GB），后续增量几秒。
#
# 装完后 runner 会作为 Windows 服务常驻；PC push/master 或 tag v* 触发的
# 原生编译 job 会自动调度到这个 runner（build.yml 里 runs-on: [self-hosted, windows, x64, apxpc-win]）。

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$RepoOwner,
    [Parameter(Mandatory)] [string]$RepoName,
    [string]$RunnerLabels = "apxpc-win,x64,windows",
    [string]$InstallDir   = "${env:ProgramFiles}\AllPeriph\actions-runner"
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "Continue"  # Winget 下载时显示进度条

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host " AllPeriph Windows Self-Hosted Runner Installer" -ForegroundColor Cyan
Write-Host " Repo:  $RepoOwner/$RepoName"  -ForegroundColor White
Write-Host " Labels: $RunnerLabels"         -ForegroundColor White
Write-Host " Dir:  $InstallDir"             -ForegroundColor White
Write-Host "=============================================" -ForegroundColor Cyan

# --- 0. 基础检查 ---
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "❌ 必须以管理员身份运行 PowerShell" -ForegroundColor Red
    exit 1
}
if ([Environment]::OSVersion.VersionString -lt "10.0.22000") {
    Write-Host "⚠️  Win10 2004+ 可运行，但推荐 Win11（GitHub Actions runner 在 Win11 上更稳定）" -ForegroundColor Yellow
}
$freeGB = [math]::Round((Get-PSDrive -Name C).Free / 1GB, 1)
if ($freeGB -lt 40) { Write-Host "⚠️  C 盘只有 $freeGB GB，MSVC + Node + runner 至少要 40GB" -ForegroundColor Yellow }

# --- 1. Winget ---
Write-Host "`n[1/6] 安装 Winget（如果缺失）..." -ForegroundColor Magenta
$wingetPath = Get-Command winget -ErrorAction SilentlyContinue
if (-not $wingetPath) {
    Write-Host "Winget 未找到，从 Microsoft Store 装 AppInstaller..."
    # Winget 独立安装包：GitHub Release winget-cli
    $wingetZip = "$env:TEMP\winget.zip"
    Invoke-WebRequest -Uri "https://github.com/microsoft/winget-cli/releases/latest/download/Microsoft.DesktopAppInstaller_8wekyb3d8bbwe.zip" -OutFile $wingetZip
    Expand-Archive -Path $wingetZip -DestinationPath "C:\Program Files\AppInstaller" -Force
    & "C:\Program Files\AppInstaller\winget.exe" source reset --force
}
winget source update

# --- 2. MSVC Build Tools 2022（C++ 桌面开发 + CMake + MSVC v143 + Win11 SDK）---
Write-Host "`n[2/6] 安装 Visual Studio 2022 Build Tools（MSVC v143 + CMake + Win11 SDK）..." -ForegroundColor Magenta
winget install --id Microsoft.VisualStudio.2022.BuildTools --exact --accept-source-agreements --accept-package-agreements `
    --override "--quiet --wait --norestart --nocache --add Microsoft.VisualStudio.Workload.VCTools --add Microsoft.VisualStudio.Component.VC.Tools.x86.x64 --add Microsoft.VisualStudio.Component.Windows11SDK.22621 --add Microsoft.VisualStudio.Component.VC.CMake.Project --includeRecommended"
if ($LASTEXITCODE -ne 0) { Write-Host "❌ MSVC Build Tools 安装失败" -ForegroundColor Red; exit 1 }

# --- 3. Node.js 20 LTS ---
Write-Host "`n[3/6] 安装 Node.js 20 LTS..." -ForegroundColor Magenta
winget install --id OpenJS.NodeJS.LTS --exact --accept-source-agreements --accept-package-agreements
# 刷新 PATH（当前 PowerShell 进程里 winget 装的 node 还没进 PATH）
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
node --version; npm --version

# --- 4. CMake（MSVC Build Tools 自带，但确保命令行可用）---
Write-Host "`n[4/6] 确认 CMake 可用..." -ForegroundColor Magenta
$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if ($cmake) { Write-Host "CMake $(cmake --version) OK" -ForegroundColor Green }
else { winget install --id Kitware.CMake --exact --accept-source-agreements --accept-package-agreements }

# --- 5. WebView2 Runtime + SDK（Runtime Win10 2004+ 自带；SDK CMake FetchContent 会自动拉）---
Write-Host "`n[5/6] 检查 WebView2 Runtime（Win10 2004+/Win11 自带）..." -ForegroundColor Magenta
$runtimeKey = "HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
if (Test-Path $runtimeKey) {
    $v = (Get-ItemProperty -Path $runtimeKey -ErrorAction SilentlyContinue).pv
    Write-Host "WebView2 Runtime $v OK" -ForegroundColor Green
} else {
    Write-Host "⚠️  Runtime 缺失 —— 从 https://aka.ms/webviewruntime 下载安装，或让 CMake 构建时自报" -ForegroundColor Yellow
}

# --- 6. GitHub Actions Self-Hosted Runner ---
Write-Host "`n[6/6] 安装 GitHub Actions Self-Hosted Runner..." -ForegroundColor Magenta
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Set-Location $InstallDir

# 找最新稳定版 runner（Linux x64 那份只编译 Linux 可用；Windows runner 是另一个 release）
$release = Invoke-RestMethod -Headers @{ "Accept" = "application/vnd.github+json" } `
    -Uri "https://api.github.com/repos/actions/runner/releases/latest"
$runnerVer = ($release.tag_name -replace "^v", "")
$runnerUrl = ($release.assets | Where-Object { $_.name -match "actions-runner-win-x64-$runnerVer.zip" }).browser_download_url
if (-not $runnerUrl) { Write-Host "❌ 找不到 Windows x64 runner release asset" -ForegroundColor Red; exit 1 }
Write-Host "下载 actions-runner-win-x64-$runnerVer.zip ..." -ForegroundColor White
Invoke-WebRequest -Uri $runnerUrl -OutFile "actions-runner.zip"
Expand-Archive -Path "actions-runner.zip" -DestinationPath "." -Force

# 获取 runner 注册 token（交互）
Write-Host ""
Write-Host "👉  打开浏览器：" -ForegroundColor Yellow
Write-Host "    https://github.com/$RepoOwner/$RepoName/settings/actions/runners/new" -ForegroundColor Cyan
Write-Host "    选 'New self-hosted runner' → Windows → 复制页面里显示的 token" -ForegroundColor Yellow
Write-Host ""
$token = Read-Host "粘贴 GitHub Actions self-hosted runner 的 registration token"

# 注册 runner（detached：token 一次性）
$labelsArr = $RunnerLabels -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ }
.\config.cmd --url "https://github.com/$RepoOwner/$RepoName" --token $token `
    --name "apxpc-win-$(hostname)" `
    --labels ($labelsArr -join ",") `
    --work "C:\runner-work" `
    --runasservice `
    --username "APXPC-Runner"

if ($LASTEXITCODE -ne 0) { Write-Host "❌ Runner 注册失败" -ForegroundColor Red; exit 1 }

# 设置系统级环境变量（npm cache / MSBuild）
[Environment]::SetEnvironmentVariable("RUNNER_TEMP", "$InstallDir\_work", "Machine")
[Environment]::SetEnvironmentVariable("NPM_CONFIG_CACHE", "C:\Users\APXPC-Runner\AppData\Local\npm-cache", "Machine")

# MSVC 相关环境变量（runner 服务进程继承 Machine env）
[Environment]::SetEnvironmentVariable("MSVC_ROOT", "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\MSBuild", "Machine")

Write-Host ""
Write-Host "=============================================" -ForegroundColor Green
Write-Host " ✅ 安装完成！" -ForegroundColor Green
Write-Host " Runner 服务已启动，正在连接 GitHub..." -ForegroundColor Green
Write-Host " 打开 https://github.com/$RepoOwner/$RepoName/actions/settings/runners" -ForegroundColor Cyan
Write-Host " 应该能看到一台 Online 的 Windows runner，labels 包含: $RunnerLabels" -ForegroundColor Cyan
Write-Host ""
Write-Host " 管理命令（管理员 PowerShell）：" -ForegroundColor White
Write-Host "    & '$InstallDir\svc.cmd' status   ← 看服务状态" -ForegroundColor Gray
Write-Host "    & '$InstallDir\svc.cmd' stop     ← 停" -ForegroundColor Gray
Write-Host "    & '$InstallDir\svc.cmd' start    ← 启" -ForegroundColor Gray
Write-Host "    & '$InstallDir\config.cmd' remove ← 从 GitHub 注销 runner（不用了再删）" -ForegroundColor Gray
Write-Host "=============================================" -ForegroundColor Green
