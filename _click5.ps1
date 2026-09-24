Add-Type @"
using System;using System.Runtime.InteropServices;
public class K4 {
 [DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")]public static extern void mouse_event(uint f,uint x,uint y,uint d,UIntPtr e);
 [DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
}
"@
function Click($x, $y) {
    [K4]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 250
    [K4]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
    Start-Sleep -Milliseconds 50
    [K4]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
    Start-Sleep -Milliseconds 400
}
$p = Get-Process apxdesktop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
[K4]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 600
Click 280 60      # 激活（点标题附近空白，不触发控件）
Click 500 326     # 副屏开关
'clicked'
Start-Sleep 8
