Start-Process 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe' 2>$null
if (-not (Get-Process apxdesktop -ErrorAction SilentlyContinue)) {
    Copy-Item 'C:\Users\Administrator\Desktop\全能外设\build_host\Release\apxdesktop.exe' 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe' -Force
    Start-Process 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe'
}
Start-Sleep -Seconds 4
Add-Type -AssemblyName System.Windows.Forms,System.Drawing
$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmp = New-Object System.Drawing.Bitmap($b.Width, $b.Height)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen(0, 0, 0, 0, $bmp.Size)
$bmp.Save('C:\Users\Administrator\Desktop\全能外设\_panel2.png', [System.Drawing.Imaging.ImageFormat]::Png)
'saved'
