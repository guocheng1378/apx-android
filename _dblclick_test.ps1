Add-Type @"
using System;using System.Runtime.InteropServices;
public class W6{
 [DllImport("user32.dll", CharSet=CharSet.Unicode)]public static extern IntPtr FindWindowW(string cls, string title);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")]public static extern bool ShowWindow(IntPtr h,int cmd);
}
"@
$hw = [W6]::FindWindowW('AllPeriphPanel', $null)
if ($hw -eq [IntPtr]::Zero) { 'PANEL NOT FOUND'; exit 1 }
'before: visible=' + [W6]::IsWindowVisible($hw)
[W6]::ShowWindow($hw, 0)   # SW_HIDE
Start-Sleep -Milliseconds 500
'hidden: visible=' + [W6]::IsWindowVisible($hw)

Start-Process 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe'
Start-Sleep -Seconds 4
'after double-click: visible=' + [W6]::IsWindowVisible($hw)
$n = (Get-Process apxdesktop -ErrorAction SilentlyContinue | Measure-Object).Count
"process count=$n"
