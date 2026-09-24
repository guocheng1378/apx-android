$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 192.168.2.182:5555 shell dumpsys package com.allperiph | Out-Null
"== 手机端摄像头状态文本（设置页行） =="
& $adb -s 192.168.2.182:5555 logcat -d | Select-String '已采|MediaOut' | Select-Object -Last 4
Start-Sleep 5
"== 5 秒后 =="
& $adb -s 192.168.2.182:5555 logcat -d | Select-String '已采' | Select-Object -Last 4
