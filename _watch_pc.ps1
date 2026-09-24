Add-Type @"
using System;using System.Runtime.InteropServices;
public class K8 {
 [DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr dc,uint f);
 [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [StructLayout(LayoutKind.Sequential)]public struct RECT{public int L,T,R,B;}
}
"@
Add-Type -AssemblyName System.Drawing
function Snap($path) {
    $p = Get-Process apxdesktop -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    if (-not $p) { return 'NO_WINDOW' }
    $r = New-Object K8+RECT
    [K8]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
    $bmp = New-Object System.Drawing.Bitmap(($r.R-$r.L), ($r.B-$r.T))
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $dc = $g.GetHdc()
    [K8]::PrintWindow($p.MainWindowHandle, $dc, 2) | Out-Null
    $g.ReleaseHdc($dc); $g.Dispose()
    $bmp.Save($path); $bmp.Dispose()
    return $path
}
Snap('C:\Users\Administrator\Desktop\全能外设\_pc1.png') | Out-Null
Start-Sleep 6
Snap('C:\Users\Administrator\Desktop\全能外设\_pc2.png') | Out-Null
'snapped'
