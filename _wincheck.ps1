Add-Type @"
using System;using System.Text;using System.Runtime.InteropServices;
public class W7{
 [DllImport("user32.dll", CharSet=CharSet.Unicode)]public static extern IntPtr FindWindowW(string cls, string title);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)]public static extern IntPtr FindWindowExW(IntPtr parent, IntPtr after, string cls, string title);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")]public static extern int GetClassName(IntPtr h,StringBuilder s,int n);
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumCb cb,IntPtr l);
 public delegate bool EnumCb(IntPtr h,IntPtr l);
}
"@
$p = Get-Process apxdesktop -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $p) { 'NO PROCESS'; exit 1 }
"pid=$($p.Id) mainHwnd=$($p.MainWindowHandle)"
$mh = $p.MainWindowHandle
if ($mh -ne [IntPtr]::Zero) {
    $cn = New-Object System.Text.StringBuilder 256
    [W7]::GetClassName($mh, $cn, 256) | Out-Null
    "main class='$($cn.ToString())' visible=$([W7]::IsWindowVisible($mh))"
    $byTitle = [W7]::FindWindowW($null, $p.MainWindowTitle)
    "FindWindow(title)=$byTitle"
    $byClass = [W7]::FindWindowW($cn.ToString(), $null)
    "FindWindow(class)=$byClass"
}
