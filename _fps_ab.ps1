$adb = 'C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe'
function Frames {
    $l = (& $adb -s 694fc921 logcat -d | Select-String '已解码 (\d+) 帧' | Select-Object -Last 1).Matches[0].Groups[1].Value
    [int]$l
}
function Sample($tag, $secs) {
    $a = Frames; Start-Sleep -Seconds $secs; $b = Frames
    "{0}: {1} 帧/{2}s = {3:N1} fps" -f $tag, ($b - $a), $secs, (($b - $a) / $secs)
}

"== 正常 =="
Sample "NORMAL" 10

# 最小化 PC 面板（直接发消息，稳）
Add-Type @"
using System;using System.Text;using System.Runtime.InteropServices;
public struct RECT{public int L,T,R,B;}
public class WM{
 public delegate bool EnumCb(IntPtr h,IntPtr l);
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumCb cb,IntPtr l);
 [DllImport("user32.dll")]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")]public static extern IntPtr SendMessage(IntPtr h,uint m,IntPtr w,IntPtr l);
}
"@
$hw = [IntPtr]::Zero
$cb = [WM+EnumCb]{ param($h,$l)
  if(-not [WM]::IsWindowVisible($h)){return $true}
  $sb=New-Object System.Text.StringBuilder 256
  [WM]::GetWindowText($h,$sb,256)|Out-Null
  if($sb.ToString().Contains('全能外设')){ $script:hw=$h }
  return $true
}
[WM]::EnumWindows($cb,[IntPtr]::Zero)|Out-Null
if ($hw -eq [IntPtr]::Zero) { "PANEL NOT FOUND"; exit 1 }
[WM]::SendMessage($hw, 0x0112, [IntPtr]0xF020, [IntPtr]::Zero) | Out-Null   # WM_SYSCOMMAND SC_MINIMIZE
"panel minimized"
Start-Sleep -Seconds 2
"== 最小化 =="
Sample "MINIMIZED" 10

# 还原
[WM]::SendMessage($hw, 0x0112, [IntPtr]0xF120, [IntPtr]::Zero) | Out-Null   # SC_RESTORE
"panel restored"
Start-Sleep -Seconds 2
"== 还原 =="
Sample "RESTORED" 10
