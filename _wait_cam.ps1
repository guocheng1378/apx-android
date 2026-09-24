$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
Start-Sleep 15
"== 手机端摄像头模块日志 =="
& $adb -s 694fc921 logcat -d | Select-String 'CameraModule|PC 命令' | Select-Object -Last 8
Add-Type @"
using System;using System.Runtime.InteropServices;
public class KC {
 [DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr dc,uint f);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
}
"@
Add-Type -AssemblyName System.Drawing
$p = Get-Process apxdesktop -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { exit }
$r = New-Object KC+RECT
[KC]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
$bmp = New-Object System.Drawing.Bitmap(($r.R-$r.L), ($r.B-$r.T))
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
[KC]::PrintWindow($p.MainWindowHandle, $dc, 2) | Out-Null
$g.ReleaseHdc($dc); $g.Dispose()
$bmp.Save('C:\Users\Administrator\Desktop\全能外设\_panel_v2.png')
$bmp.Dispose()
'saved'
