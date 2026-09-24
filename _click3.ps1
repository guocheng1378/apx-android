Add-Type @"
using System;using System.Runtime.InteropServices;
public class K2 {
 [DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")]public static extern void mouse_event(uint f,uint x,uint y,uint d,UIntPtr e);
 [DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
}
"@
$p = Get-Process apxdesktop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
[K2]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 600
$r = New-Object K2+RECT
[K2]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
# 可见窗口从不可见边框(约8px)内开始；开关在截图(515,326)
$bx = $r.L + 8 + 515; $by = $r.T + 0 + 326
"click at $bx,$by"
[K2]::SetCursorPos($bx, $by) | Out-Null
Start-Sleep -Milliseconds 400
[K2]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[K2]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
Start-Sleep 8
'clicked'
