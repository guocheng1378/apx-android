Add-Type @"
using System;using System.Runtime.InteropServices;
public class K3 {
 [DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")]public static extern void mouse_event(uint f,uint x,uint y,uint d,UIntPtr e);
 [DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")]public static extern bool MoveWindow(IntPtr h,int x,int y,int w,int ht,bool repaint);
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
}
"@
$p = Get-Process apxdesktop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
[K3]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 500
# 先把窗口挪到 (0,0) 消除坐标不确定
[K3]::MoveWindow($p.MainWindowHandle, 0, 0, 576, 1100, $true) | Out-Null
Start-Sleep -Milliseconds 800
$r = New-Object K3+RECT
[K3]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
"win now=$($r.L),$($r.T)"
# 已验证成功的相对坐标 (500,335)
$bx = 0 + 500; $by = 0 + 335
[K3]::SetCursorPos($bx, $by) | Out-Null
Start-Sleep -Milliseconds 400
[K3]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[K3]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
"clicked"
Start-Sleep 10
