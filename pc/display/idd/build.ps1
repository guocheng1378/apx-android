<#
.SYNOPSIS
    构建 AllPeriph 虚拟显示器驱动（IddCx / UMDF2）。

.DESCRIPTION
    IddCx 驱动**不能**用 CMake 构建，必须走 WDK 的 MSBuild 目标。
    因此本脚本独立于 pc/display/CMakeLists.txt（那个只管用户态部分）。

    前置条件：
      - Visual Studio 2022（含 C++ 桌面开发工作负载）
      - Windows Driver Kit (WDK) 10.0.22621 或更高
      两者版本必须匹配，否则 WDK 的 MSBuild 目标会加载失败。

.PARAMETER Config
    Release（默认）或 Debug。

.PARAMETER TestSign
    生成测试签名证书并签名（仅本机自测）。分发给用户需要 EV 证书 +
    微软 Attestation 签名，见 README.md 第 4 节。

.EXAMPLE
    .\build.ps1 -Config Release
    .\build.ps1 -Config Debug -TestSign
#>
[CmdletBinding()]
param(
    [ValidateSet('Release', 'Debug')]
    [string]$Config = 'Release',

    [switch]$TestSign
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $scriptDir "x64\$Config"

# --------------------------------------------------------------- 查找 WDK ----
# WDK 的 MSBuild 目标靠这个环境变量定位；不设置会报
# "The WDK content root path ... does not exist"
if (-not $env:WDKContentRoot) {
    $candidates = @(
        'C:\Program Files (x86)\Windows Kits\10\',
        'C:\Program Files\Windows Kits\10\'
    ) | Where-Object { Test-Path (Join-Path $_ 'Include') }

    if ($candidates.Count -gt 0) {
        $env:WDKContentRoot = $candidates[0]
        Write-Host "[idd] WDKContentRoot = $env:WDKContentRoot"
    } else {
        throw @"
未找到 WDK。请先安装 Windows Driver Kit（版本需与 VS2022 的 MSVC 工具集匹配）：
  https://learn.microsoft.com/windows-hardware/drivers/download-the-wdk
装完后重新打开 PowerShell（环境变量需要重新加载）。
"@
    }
}

# ----------------------------------------------------------- 查找 MSBuild ----
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) {
    throw "未找到 vswhere.exe，请确认已安装 Visual Studio 2022（含 C++ 工作负载）。"
}

$msbuild = & $vswhere -latest -products * `
    -requires Microsoft.Component.MSBuild `
    -find 'MSBuild\**\Bin\MSBuild.exe' | Select-Object -First 1

if (-not $msbuild) { throw "未找到 MSBuild.exe。" }
Write-Host "[idd] MSBuild = $msbuild"

# ------------------------------------------------------------------ 构建 ----
# 说明：本目录当前交付的是**骨架**，尚无 .vcxproj。
# 补齐 .vcxproj 后（参考 WDK 的 IddCx 示例 SampleKernelModeDriver / IndirectDisplay），
# 本脚本即可直接使用。下面的命令是届时应当执行的形态：

$vcxproj = Join-Path $scriptDir 'apxdisp_idd.vcxproj'
if (-not (Test-Path $vcxproj)) {
    Write-Warning @"
未找到 apxdisp_idd.vcxproj —— 当前目录只交付了驱动骨架（IddDriver.cpp + 本脚本 + README）。

补齐 .vcxproj 时可参考：
  - WDK 示例：Windows-driver-samples/video/IndirectDisplay
  - 开源实现：itsmikethecore/Virtual-Display-Driver（已签名，可直接用，见 README 路径 A）

若你只是想尽快跑通扩展屏链路，**推荐直接用路径 A**，不必自己构建驱动。
"@
    exit 0
}

Write-Host "[idd] 开始构建 $Config ..."
& $msbuild $vcxproj `
    /p:Configuration=$Config `
    /p:Platform=x64 `
    /p:SignMode=$(if ($TestSign) { 'TestSign' } else { 'Off' }) `
    /m /v:minimal

if ($LASTEXITCODE -ne 0) { throw "构建失败（exit=$LASTEXITCODE）" }

$sys = Get-ChildItem -Path $outDir -Filter *.sys -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($sys) {
    Write-Host "[idd] 产物: $($sys.FullName)"
} else {
    Write-Warning "[idd] 构建结束但未找到 .sys，请检查 vcxproj 的输出路径配置。"
}

if ($TestSign) {
    Write-Host @"
[idd] 已按测试签名构建。安装前需开启测试签名模式（会重启，桌面显示水印）：
        bcdedit /set testsigning on
      安装：
        pnputil /add-driver "$outDir\apxdisp_idd.inf" /install
      注意：测试签名**不能分发给用户**，仅限本机调试。
"@
}
