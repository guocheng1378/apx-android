$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
# 显示器列表（多屏设备）
& $adb -s 694fc921 shell dumpsys SurfaceFlinger --display-id
# 当前前台 Activity
& $adb -s 694fc921 shell dumpsys activity activities | Select-String 'topResumedActivity'
