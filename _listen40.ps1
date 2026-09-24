$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"确认设置干净: user_rotation=" + (& $adb -s 694fc921 shell settings get system user_rotation) + " auto_rot=" + (& $adb -s 694fc921 shell settings get system accelerometer_rotation)
"【请现在拿起手机：竖屏 → 转横屏停 2 秒 → 转回竖屏】"
& $adb -s 694fc921 logcat -c
Start-Sleep 40
"== 40 秒内的切换日志 =="
& $adb -s 694fc921 logcat -d | Select-String '物理朝向切换|applyOrientationLayout' | Select-Object -Last 14
& $adb -s 694fc921 shell uiautomator dump /sdcard/uz2.xml | Out-Null
& $adb -s 694fc921 pull /sdcard/uz2.xml C:\Users\Administrator\Desktop\uz2.xml | Out-Null
$s = Get-Content C:\Users\Administrator\Desktop\uz2.xml -Raw -Encoding UTF8
"最终页面: " + $(if ($s -match 'id/pageKeyboard') {'键盘页'} elseif ($s -match 'id/pageTouchpad') {'触控板页'} else {'?'})
