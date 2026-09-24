Add-Type @"
using System;using System.Runtime.InteropServices;
public class K {
 [DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")]public static extern void mouse_event(uint f,uint x,uint y,uint d,UIntPtr e);
 [DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern bool ShowWindow(IntPtr h,int cmd);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
}
"@
$p = Get-Process apxdesktop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
[K]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
[K]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 800
$r = New-Object K+RECT
[K]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
"win=$($r.L),$($r.T)"
# 副屏卡开关：卡标题 y≈325，开关在窗口内 x≈515
$bx = $r.L + 515; $by = $r.T + 325
[K]::SetCursorPos($bx, $by) | Out-Null
Start-Sleep -Milliseconds 300
[K]::mouse_event(2,0,0,0,[UIntPtr]::Zero); [K]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
"clicked $bx,$by"
Start-Sleep 8
