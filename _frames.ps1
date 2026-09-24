$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 694fc921 logcat -d -t 5 | Select-String '副屏页' 
Start-Sleep 4
& $adb -s 694fc921 logcat -d -t 5 | Select-String '副屏页'
