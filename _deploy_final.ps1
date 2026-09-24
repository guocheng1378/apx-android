taskkill /f /im apxdesktop.exe 2>$null
Start-Sleep 2
Copy-Item 'C:\Users\Administrator\Desktop\全能外设\build_host\Release\apxdesktop.exe' 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe' -Force
(Get-Item 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe').LastWriteTime
Start-Process 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe'
Start-Sleep 3
Get-Process apxdesktop -ErrorAction SilentlyContinue | Select-Object Id,StartTime | Format-Table -AutoSize
