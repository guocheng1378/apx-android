# 杀旧面板 → 跑最新构建版 → 打开副屏开关 → 截图
taskkill /f /im apxdesktop.exe 2>$null
Start-Sleep 2
Start-Process 'C:\Users\Administrator\Desktop\全能外设\build_host\Release\apxdesktop.exe'
Start-Sleep 4
Add-Type @"
using System;using System.Runtime.InteropServices;
public class K {
 [DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")]public static extern void mouse_event(uint f,uint x,uint y,uint d,UIntPtr e);
 [DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern bool ShowWindow(IntPtr h,int cmd);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr dc,uint f);
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
}
"@
Add-Type -AssemblyName System.Drawing
$p = Get-Process apxdesktop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
[K]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
[K]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 800
$r = New-Object K+RECT
[K]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
"win=$($r.L),$($r.T)"
$bx = $r.L + 515; $by = $r.T + 325
[K]::SetCursorPos($bx, $by) | Out-Null
Start-Sleep -Milliseconds 300
[K]::mouse_event(2,0,0,0,[UIntPtr]::Zero); [K]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
"clicked screen switch"
Start-Sleep 8
# 截面板
$r2 = New-Object K+RECT
[K]::GetWindowRect($p.MainWindowHandle, [ref]$r2) | Out-Null
$w = $r2.R - $r2.L; $h = $r2.B - $r2.T
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
[K]::PrintWindow($p.MainWindowHandle, $dc, 2) | Out-Null
$g.ReleaseHdc($dc); $g.Dispose()
$bmp.Save('C:\Users\Administrator\Desktop\全能外设\_panel.png')
$bmp.Dispose()
'shot_saved'
