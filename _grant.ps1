$adb = 'C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe'
& $adb -s 694fc921 shell pm grant com.allperiph android.permission.CAMERA
& $adb -s 694fc921 shell pm grant com.allperiph android.permission.RECORD_AUDIO
& $adb -s 694fc921 shell pm grant com.allperiph android.permission.ACCESS_FINE_LOCATION
& $adb -s 694fc921 shell pm grant com.allperiph android.permission.POST_NOTIFICATIONS
'granted'
# 拉起 App 并恢复服务（重装后服务已死：拨一次无线开关）
& $adb -s 694fc921 shell monkey -p com.allperiph -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s 694fc921 shell input tap 600 2510   # 状态页
Start-Sleep -Seconds 1
& $adb -s 694fc921 shell input tap 1022 794   # swWifi off
Start-Sleep -Seconds 2
& $adb -s 694fc921 shell input tap 1022 794   # on
Start-Sleep -Seconds 4
$act = (& $adb -s 694fc921 shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 1) -join ''
"FG: $act"
