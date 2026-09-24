$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 694fc921 shell am force-stop com.allperiph
Start-Sleep 1
& $adb -s 694fc921 logcat -c
& $adb -s 694fc921 shell monkey -p com.allperiph -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep 4
"== 启动后日志 =="
& $adb -s 694fc921 logcat -d | Select-String '物理朝向|applyOrientationLayout|MainActivity' | Select-Object -Last 8
& $adb -s 694fc921 shell uiautomator dump /sdcard/un.xml | Out-Null
& $adb -s 694fc921 pull /sdcard/un.xml C:\Users\Administrator\Desktop\un.xml | Out-Null
$s = Get-Content C:\Users\Administrator\Desktop\un.xml -Raw -Encoding UTF8
"页面: " + $(if ($s -match 'id/pageKeyboard') {'键盘页'} elseif ($s -match 'id/pageTouchpad') {'触控板页'} else {'?'})
