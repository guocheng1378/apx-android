$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"【请现在：1) 关掉 PC 面板摄像头开关  2) 盯着手机扩展屏 10 秒看还闪不闪】"
Start-Sleep 12
"== 副屏收流帧率（判断是否掉帧/黑帧交替） =="
& $adb -s 192.168.2.182:5555 logcat -d | Select-String '副屏页' | Select-Object -Last 4
