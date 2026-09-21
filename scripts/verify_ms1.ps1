# MS1 真机验证脚本（一步跑完全套探测）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/verify_ms1.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/verify_ms1.ps1 -InstallApk
#   powershell -ExecutionPolicy Bypass -File scripts/verify_ms1.ps1 -Mount      # 会真的挂载 Gadget（需 root）
#
# 目的：手机插上后，用一条命令判断 MS1 能否走通，并给出明确结论。
# 只读探测是安全的；-Mount 会写 ConfigFS（改变手机 USB 配置），脚本会在结束时自动 down。

param(
    # 默认从 PATH 查找 adb；也可用 -Adb 指定完整路径
    [string]$Adb = "adb",
    # 以下默认值相对本脚本定位，保证仓库克隆到任意目录都能跑
    [string]$Apk = (Join-Path $PSScriptRoot "..\android\app\build\outputs\apk\debug\app-debug.apk"),
    [string]$GadgetScript = (Join-Path $PSScriptRoot "apx_gadget.sh"),
    [switch]$InstallApk,
    [switch]$Mount
)

$ErrorActionPreference = "Continue"
$results = [ordered]@{}

function Section([string]$t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Ok([string]$m)   { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Warn([string]$m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }
function Bad([string]$m)  { Write-Host "  [FAIL] $m" -ForegroundColor Red }
function Info([string]$m) { Write-Host "  -      $m" }

function Sh([string]$cmd) {
    # adb shell 包装：吞掉 stderr，返回纯文本
    ($(& $Adb shell $cmd 2>&1 | Out-String)).Trim()
}

# adb：允许传入完整路径，或仅为命令名（从 PATH 解析）
if ($Adb -match '[\\/]') {
    if (-not (Test-Path $Adb)) { Bad "找不到 adb：$Adb"; exit 1 }
} else {
    $adbCmd = Get-Command $Adb -ErrorAction SilentlyContinue
    if (-not $adbCmd) {
        Bad "PATH 中找不到 adb：请安装 platform-tools 并加入 PATH，或用 -Adb 指定完整路径"
        exit 1
    }
    $Adb = $adbCmd.Source
}

# ---------------------------------------------------------------- 1. 等待设备
Section "1. 设备连接"
& $Adb start-server 2>&1 | Out-Null
$dev = $null
for ($i = 1; $i -le 30; $i++) {
    $out = (& $Adb devices 2>&1 | Out-String)
    if ($out -match "(\S+)\s+device\b") { $dev = $Matches[1]; break }
    if ($out -match "unauthorized") {
        Warn "设备已连接但未授权 —— 请在手机弹窗点『允许 USB 调试』"
    }
    if ($i -eq 1) { Info "等待设备（最多 60 秒）…" }
    Start-Sleep -Seconds 2
}
if (-not $dev) { Bad "未检测到已授权设备，请检查数据线与 USB 调试授权"; exit 1 }
Ok "设备：$dev"
$results["device"] = $dev

# ---------------------------------------------------------------- 2. 设备信息
Section "2. 设备信息"
$model   = Sh "getprop ro.product.model"
$android = Sh "getprop ro.build.version.release"
$sdk     = Sh "getprop ro.build.version.sdk"
$abi     = Sh "getprop ro.product.cpu.abi"
Info "型号=$model  Android=$android (API $sdk)  ABI=$abi"
$results["model"] = $model; $results["android"] = "$android (API $sdk)"

# ---------------------------------------------------------------- 3. root 探测
Section "3. root 探测（主线前提）"
$suPath = $null
foreach ($p in @("/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
                 "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su")) {
    $r = Sh "ls -l $p 2>/dev/null"
    if ($r -and $r -notmatch "No such file") { $suPath = $p; break }
}
if (-not $suPath) {
    $w = Sh "which su 2>/dev/null"
    if ($w) { $suPath = $w }
}

$rootOk = $false
if ($suPath) {
    Ok "su 存在：$suPath"
    foreach ($try in @("$suPath -c id", "su -c id", "su 0 id", "echo id | $suPath")) {
        $id = Sh $try
        if ($id -match "uid=0") { Ok "root 可用：$try  → $id"; $rootOk = $true; break }
    }
    if (-not $rootOk) {
        Warn "su 存在但未授权 —— 请在 KernelSU / Magisk 里给『Shell』授权 root"
    }
} else {
    Bad "未找到 su（magisk/ksu 目录也不存在）"
    Info "检查：$(Sh 'ls -d /data/adb/magisk /data/adb/ksu /data/adb/ap 2>&1')"
}
$results["root"] = $rootOk

# ---------------------------------------------------------------- 4. UDC 与内核支持
Section "4. UDC 速度与内核 Gadget 支持"
if ($rootOk) {
    $udc  = Sh "su -c 'ls /sys/class/udc/'"
    $spd  = Sh "su -c 'cat /sys/class/udc/*/current_speed'"
    Info "UDC=$udc  current_speed=$spd"
    if ($spd -match "super-speed") { Ok "链路为 SuperSpeed（非降级）" }
    else { Warn "非 super-speed（$spd）—— 副屏等高带宽功能会受限" }
    $results["udcSpeed"] = $spd

    $cfg = Sh "su -c 'ls /sys/kernel/config/ 2>&1; ls /sys/kernel/config/usb_gadget 2>&1'"
    if ($cfg -match "Permission denied") { Bad "ConfigFS 不可访问" } else { Ok "ConfigFS 可访问：$cfg" }

    # 内核是否带各 gadget function（built-in 或 ko 都会出现在 functions/ 下）
    $funcs = Sh "su -c 'ls /sys/kernel/config/usb_gadget 2>/dev/null; cat /proc/config.gz 2>/dev/null | zcat 2>/dev/null | grep -E \"F_HID|F_ACM|F_UVC|F_UAC|F_NCM|F_RNDIS\"'"
    if (-not $funcs) {
        $funcs = Sh "su -c 'zcat /proc/config.gz 2>/dev/null | grep -E \"USB_CONFIGFS_F\"'"
    }
    Info "内核 function 相关："
    ($funcs -split "`n" | Where-Object { $_ } | Select-Object -First 20) | ForEach-Object { Info "  $_" }
    $results["kernelFunctions"] = ($funcs -replace "`n", " ").Trim()

    $mods = Sh "su -c 'ls /sys/module | grep -iE \"usb_f|gadget\" | tr \"\n\" \" \"'"
    Info "已加载 gadget 模块：$mods"
} else {
    Warn "跳过（需要 root）：UDC 速度、ConfigFS、内核 function 清单都无法读取"
}

# ---------------------------------------------------------------- 5. APK
Section "5. APK 安装与启动"
$installed = Sh "pm list packages com.allperiph"
if ($installed -match "com.allperiph") { Ok "已安装" } else { Warn "未安装" }
if ($InstallApk -or $installed -notmatch "com.allperiph") {
    if (Test-Path $Apk) {
        Info "安装 $Apk …"
        $r = (& $Adb install -r $Apk 2>&1 | Out-String)
        if ($r -match "Success") { Ok "安装成功" } else { Bad "安装失败：$r" }
    } else { Bad "APK 不存在：$Apk（先构建：cd android; gradle assembleDebug）" }
}
$r = Sh "am start -n com.allperiph/.ui.MainActivity"
Start-Sleep -Seconds 3
$pid0 = Sh "pidof com.allperiph"
if ($pid0) { Ok "App 运行中（PID $pid0）" } else { Bad "App 未运行，检查：adb logcat | grep AndroidRuntime" }
$results["appRunning"] = [bool]$pid0

# 崩溃检查
$crash = (& $Adb logcat -d -t 200 2>&1 | Out-String)
if ($crash -match "FATAL EXCEPTION") { Bad "logcat 中发现 FATAL EXCEPTION"; Info ($crash -split "`n" | Select-String "FATAL" | Select-Object -First 3) }
else { Ok "logcat 无 FATAL EXCEPTION" }

# ---------------------------------------------------------------- 6. Gadget 挂载（可选）
Section "6. Gadget 挂载（-Mount 才执行）"
if (-not $Mount) {
    Info "已跳过（加 -Mount 参数才会真的挂载）"
} elseif (-not $rootOk) {
    Bad "需要 root，跳过"
} else {
    if (-not (Test-Path $GadgetScript)) { Bad "找不到 $GadgetScript" }
    else {
        & $Adb push $GadgetScript /data/local/tmp/apx_gadget.sh 2>&1 | Out-Null
        Sh "su -c 'chmod 0755 /data/local/tmp/apx_gadget.sh'"
        Info "基线 sys.usb.config = $(Sh 'getprop sys.usb.config')"
        Info "--- status ---"; Sh "su -c 'sh /data/local/tmp/apx_gadget.sh status'" | ForEach-Object { Info "  $_" }
        Info "--- speed ---";  Sh "su -c 'sh /data/local/tmp/apx_gadget.sh speed'"  | ForEach-Object { Info "  $_" }
        Info "--- up ---";     Sh "su -c 'sh /data/local/tmp/apx_gadget.sh up'"     | ForEach-Object { Info "  $_" }
        Start-Sleep -Seconds 3
        $hid = Sh "su -c 'ls -l /dev/hidg0 /dev/ttyGS0 2>&1'"
        Info "设备节点：$hid"
        Info "--- down（恢复） ---"; Sh "su -c 'sh /data/local/tmp/apx_gadget.sh down'" | ForEach-Object { Info "  $_" }
        Info "恢复后 sys.usb.config = $(Sh 'getprop sys.usb.config')"
    }
}

# ---------------------------------------------------------------- 汇总
Section "结论"
Write-Host ("  root          : " + $(if ($results["root"]) { "可用" } else { "不可用 —— 主线 Gadget 路线无法进行" }))
Write-Host ("  App 运行      : " + $(if ($results["appRunning"]) { "是" } else { "否" }))
Write-Host ("  UDC 速度      : " + $(if ($results["udcSpeed"]) { $results["udcSpeed"] } else { "未知（需 root）" }))
Write-Host ""
Write-Host "  PC 侧判定（-Mount 成功后）：" -ForegroundColor Cyan
Write-Host "    设备管理器应出现 AllPeriph Composite HID；Windows「传感器」面板读到加速度 = MS1 通过"
Write-Host ""
