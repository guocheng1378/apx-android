Add-Type @"
using System;using System.Runtime.InteropServices;
public class ED{
 [DllImport("user32.dll")]public static extern bool EnumDisplayDevices(string dn,uint i,ref DISPLAY_DEVICE dd,uint f);
 [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
 public struct DISPLAY_DEVICE{public int cb;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string DeviceName;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string DeviceString;public int StateFlags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string DeviceID;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string DeviceKey;}
}
"@
$i = 0
$dd = New-Object ED+DISPLAY_DEVICE
$dd.cb = [Runtime.InteropServices.Marshal]::SizeOf($dd)
while ([ED]::EnumDisplayDevices($null, $i, [ref]$dd, 0)) {
    "[$i] $($dd.DeviceName) | $($dd.DeviceString) | active=$([bool]($dd.StateFlags -band 1))"
    $i++
    $dd = New-Object ED+DISPLAY_DEVICE
    $dd.cb = [Runtime.InteropServices.Marshal]::SizeOf($dd)
}
