$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"== 本地 APK 构建时间 =="
(Get-Item 'C:\Users\Administrator\Desktop\全能外设\android\app\build\outputs\apk\debug\app-debug.apk').LastWriteTime
"== 手机上安装的包时间（lastUpdateTime） =="
& $adb -s 192.168.2.182:5555 shell dumpsys package com.allperiph | Select-String 'lastUpdateTime|firstInstallTime|versionName'
"== 手机上 base.apk 的文件时间 =="
& $adb -s 192.168.2.182:5555 shell ls -l /data/app/*/com.allperiph*/base.apk 2>$null
& $adb -s 192.168.2.182:5555 shell ls -l /data/app/com.allperiph*/base.apk 2>$null
