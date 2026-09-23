$adb = 'C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe'
& $adb -s 694fc921 shell monkey -p com.allperiph -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s 694fc921 shell input tap 600 2510   # 状态页
Start-Sleep -Seconds 1
& $adb -s 694fc921 shell input tap 1022 794   # swWifi toggle
Start-Sleep -Seconds 2
& $adb -s 694fc921 shell input tap 1022 794   # swWifi toggle back on
Start-Sleep -Seconds 3
& $adb -s 694fc921 shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 1
