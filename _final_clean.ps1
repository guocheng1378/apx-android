$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 694fc921 shell settings delete system user_rotation
& $adb -s 694fc921 shell settings put system accelerometer_rotation 1
& $adb -s 694fc921 shell am force-stop com.allperiph
Start-Sleep 1
& $adb -s 694fc921 shell monkey -p com.allperiph -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep 3
"== 清理确认 =="
"user_rotation=" + (& $adb -s 694fc921 shell settings get system user_rotation)
"accelerometer_rotation=" + (& $adb -s 694fc921 shell settings get system accelerometer_rotation)
Start-Sleep 8
"== 8 秒后复查（有没有被写回） =="
"user_rotation=" + (& $adb -s 694fc921 shell settings get system user_rotation)
"== 监听 25 秒：请现在转手机 竖→横→竖 =="
& $adb -s 694fc921 logcat -c
Start-Sleep 25
& $adb -s 694fc921 logcat -d | Select-String '物理朝向切换|applyOrientationLayout' | Select-Object -Last 12
& $adb -s 694fc921 shell uiautomator dump /sdcard/uz.xml | Out-Null
& $adb -s 694fc921 pull /sdcard/uz.xml C:\Users\Administrator\Desktop\uz.xml | Out-Null
$s = Get-Content C:\Users\Administrator\Desktop\uz.xml -Raw -Encoding UTF8
"最终页面: " + $(if ($s -match 'id/pageKeyboard') {'键盘页'} elseif ($s -match 'id/pageTouchpad') {'触控板页'} else {'?'})
"user_rotation=" + (& $adb -s 694fc921 shell settings get system user_rotation)
