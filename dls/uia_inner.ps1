Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$log = 'C:\Users\Administrator\Desktop\全能外设\dls\uia_log.txt'
function Log($m) { Add-Content -Path $log -Value $m }

# 启动安装器（提权会话内，无需再弹 UAC）
Start-Process 'C:\Users\Administrator\Desktop\全能外设\dls\vbcable\VBCABLE_Setup_x64.exe' -WorkingDirectory 'C:\Users\Administrator\Desktop\全能外设\dls\vbcable'
Log 'setup started'

$root = [System.Windows.Automation.AutomationElement]::RootElement
$btn = $null
foreach ($i in 1..40) {
    Start-Sleep -Milliseconds 500
    $w = $root.FindFirst([System.Windows.Automation.TreeScope]::Children,
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, 'Virtual Audio Cable')))
    if (-not $w) { continue }
    $cond = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Button)
    $btns = $w.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
    foreach ($b in $btns) {
        Log ('button: ' + $b.Current.Name)
        if ($b.Current.Name -match 'Install') { $btn = $b }
    }
    if ($btn) { break }
}
if (-not $btn) { Log 'NO INSTALL BUTTON'; exit 1 }
$btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
Log 'install clicked'

# 等驱动落地
foreach ($i in 1..20) {
    Start-Sleep -Seconds 2
    $d = Get-CimInstance Win32_SoundDevice | Where-Object { $_.Name -match 'VB-Audio' }
    if ($d) { Log ('DEVICE OK: ' + $d.Name); break }
}
if (-not (Get-CimInstance Win32_SoundDevice | Where-Object { $_.Name -match 'VB-Audio' })) { Log 'DEVICE NOT FOUND' }