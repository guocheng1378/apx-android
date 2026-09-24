# 1) 监视器布局
Add-Type @"
using System;using System.Runtime.InteropServices;
public class MD2{
 public delegate bool EnumCb(IntPtr h,IntPtr dc,IntPtr r,IntPtr l);
 [DllImport("user32.dll")]public static extern bool EnumDisplayMonitors(IntPtr dc,IntPtr clip,EnumCb cb,IntPtr l);
 [DllImport("user32.dll")]public static extern bool GetMonitorInfo(IntPtr h,ref MONITORINFO mi);
 [StructLayout(LayoutKind.Sequential)]public struct MONITORINFO{public int cbSize;public RECT rcMonitor;public RECT rcWork;public int dwFlags;}
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
 [DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr dc,uint f);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
}
"@
$list = New-Object System.Collections.ArrayList
$sz = [Runtime.InteropServices.Marshal]::SizeOf([type][MD2+MONITORINFO])
$cb = [MD2+EnumCb]{ param($h,$dc,$r,$l)
    $mi = New-Object MD2+MONITORINFO; $mi.cbSize = $sz
    [MD2]::GetMonitorInfo($h, [ref]$mi) | Out-Null
    [void]$list.Add("monitor $($mi.rcMonitor.L),$($mi.rcMonitor.T)-$($mi.rcMonitor.R),$($mi.rcMonitor.B) primary=$([bool]($mi.dwFlags -band 1))")
    return $true }
[MD2]::EnumDisplayMonitors([IntPtr]::Zero, [IntPtr]::Zero, $cb, [IntPtr]::Zero) | Out-Null
$list

# 2) 面板窗口截图
$p = Get-Process apxdesktop -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { 'NO PANEL'; exit }
$r = New-Object MD2+RECT
[MD2]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
$w = $r.R - $r.L; $h = $r.B - $r.T
"panel=$($r.L),$($r.T) ${w}x${h}"
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
[MD2]::PrintWindow($p.MainWindowHandle, $dc, 2) | Out-Null
$g.ReleaseHdc($dc); $g.Dispose()
$bmp.Save('C:\Users\Administrator\Desktop\全能外设\_panel.png')
$bmp.Dispose()
'shot_saved'
