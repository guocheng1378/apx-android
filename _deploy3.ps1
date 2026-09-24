Copy-Item 'C:\Users\Administrator\Desktop\全能外设\build_host\Release\apxdesktop.exe' 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe' -Force
(Get-Item 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe').LastWriteTime = Get-Date
Start-Process 'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe'
Start-Sleep 2
Get-Process apxdesktop -ErrorAction SilentlyContinue | Select-Object Id | Format-Table -AutoSize
