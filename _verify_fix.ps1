$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 694fc921 shell am force-stop com.allperiph
& $adb -s 694fc921 shell settings delete system user_rotation
& $adb -s 694fc921 shell settings put system accelerometer_rotation 1
& $adb -s 694fc921 shell monkey -p com.allperiph -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep 4
& $adb -s 694fc921 logcat -c
"初始: auto_rot=" + (& $adb -s 694fc921 shell settings get system accelerometer_rotation) + " user_rotation=" + (& $adb -s 694fc921 shell settings get system user_rotation)
"【请转手机：竖 → 横（停 2 秒）→ 竖（停 2 秒）→ 横】"
Start-Sleep 35
& $adb -s 694fc921 logcat -d | Select-String '物理朝向切换|applyOrientationLayout' | Select-Object -Last 16
"设置复查: auto_rot=" + (& $adb -s 694fc921 shell settings get system accelerometer_rotation) + " user_rotation=" + (& $adb -s 694fc921 shell settings get system user_rotation)
& $adb -s 694fc921 shell uiautomator dump /sdcard/uv.xml | Out-Null
& $adb -s 694fc921 pull /sdcard/uv.xml C:\Users\Administrator\Desktop\uv.xml | Out-Null
$s = Get-Content C:\Users\Administrator\Desktop\uv.xml -Raw -Encoding UTF8
"最终页面: " + $(if ($s -match 'id/pageKeyboard') {'键盘页'} elseif ($s -match 'id/pageTouchpad') {'触控板页'} else {'?'})
