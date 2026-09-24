$exe = Get-Process apxdesktop -ErrorAction SilentlyContinue
if ($exe) {
    $exe | ForEach-Object { "proc pid=$($_.Id) start=$($_.StartTime) path=$($_.Path)" }
} else { 'NO PROCESS' }
cmd /c "netstat -ano | findstr 9500"

Add-Type @"
using System;using System.Text;using System.Runtime.InteropServices;
public class MD{
 public delegate bool EnumCb(IntPtr h,IntPtr dc,IntPtr r,IntPtr l);
 [DllImport("user32.dll")]public static extern bool EnumDisplayMonitors(IntPtr dc,IntPtr clip,EnumCb cb,IntPtr l);
 [DllImport("user32.dll")]public static extern bool GetMonitorInfo(IntPtr h,ref MONITORINFO mi);
 [StructLayout(LayoutKind.Sequential)]public struct MONITORINFO{public int cbSize;public RECT rcMonitor;public RECT rcWork;public int dwFlags;}
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
 [DllImport("user32.dll")]public static extern bool EnumDisplayDevices(string dn,uint i,ref DD dd,uint f);
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]public struct DD{public int cb;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string DeviceName;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string DeviceString;public int StateFlags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string DeviceID;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string DeviceKey;}
}
"@
# 监视器布局
$md = [System.Runtime.InteropServices.Marshal]::SizeOf([type][MD+MONITORINFO])
$list = New-Object System.Collections.ArrayList
$cb = [MD+EnumCb]{ param($h,$dc,$r,$l)
    $mi = New-Object MD+MONITORINFO
    $mi.cbSize = $md
    [MD]::GetMonitorInfo($h, [ref]$mi) | Out-Null
    [void]$list.Add("monitor rect=$($mi.rcMonitor.L),$($mi.rcMonitor.T)-$($mi.rcMonitor.R),$($mi.rcMonitor.B) primary=$([bool]($mi.dwFlags -band 1))")
    return $true }
[MD]::EnumDisplayMonitors([IntPtr]::Zero, [IntPtr]::Zero, $cb, [IntPtr]::Zero) | Out-Null
$list | ForEach-Object { $_ }
# 显示适配器（含虚拟屏设备名）
$i=0; $dd = New-Object MD+DD; $dd.cb = [Runtime.InteropServices.Marshal]::SizeOf($dd)
while ([MD]::EnumDisplayDevices($null, $i, [ref]$dd, 0)) {
    "adapter[$i] $($dd.DeviceName) $($dd.DeviceString) active=$([bool]($dd.StateFlags -band 1))"
    $i++; $dd = New-Object MD+DD; $dd.cb = [Runtime.InteropServices.Marshal]::SizeOf($dd)
}
