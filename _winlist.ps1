Add-Type @"
using System;using System.Text;using System.Runtime.InteropServices;
public class W5{
 public delegate bool EnumCb(IntPtr h,IntPtr l);
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumCb cb,IntPtr l);
 [DllImport("user32.dll")]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint pid);
 [DllImport("user32.dll")]public static extern int GetClassName(IntPtr h,StringBuilder s,int n);
}
"@
$procs = Get-Process apxdesktop -ErrorAction SilentlyContinue
if (-not $procs) { 'NO PROCESS'; exit 1 }
$procs | ForEach-Object { "proc pid=$($_.Id) startTime=$($_.StartTime) title='$($_.MainWindowTitle)'" }
$cb = [W5+EnumCb]{ param($h,$l)
    $sb=New-Object System.Text.StringBuilder 256
    [W5]::GetWindowText($h,$sb,256)|Out-Null
    $cn=New-Object System.Text.StringBuilder 256
    [W5]::GetClassName($h,$cn,256)|Out-Null
    if ($sb.ToString() -or $cn.ToString().Contains('AllPeriph')) {
        $pid2=0
        [W5]::GetWindowThreadProcessId($h,[ref]$pid2)|Out-Null
        "win pid=$pid2 class=$($cn) title='$($sb)' visible=$([W5]::IsWindowVisible($h))"
    }
    return $true }
[W5]::EnumWindows($cb,[IntPtr]::Zero)|Out-Null
'enum done'
