$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 694fc921 logcat -c
Start-Sleep 12
"== 朝向相关日志 =="
& $adb -s 694fc921 logcat -d | Select-String '物理朝向切换|applyOrientationLayout|朝向兜底' | Select-Object -Last 12
"== 当前界面 =="
& $adb -s 694fc921 shell dumpsys input | Select-String 'SurfaceOrientation' | Select-Object -First 2
& $adb -s 694fc921 shell uiautomator dump /sdcard/u_now.xml | Out-Null
& $adb -s 694fc921 pull /sdcard/u_now.xml C:\Users\Administrator\Desktop\u_now.xml | Out-Null
$s = Get-Content C:\Users\Administrator\Desktop\u_now.xml -Raw -Encoding UTF8
"页面: " + $(if ($s -match 'id/pageKeyboard') {'键盘页'} elseif ($s -match 'id/pageTouchpad') {'触控板页'} else {'?'})
"尺寸: " + $(if ($s -match 'rotation="1"') {'横屏'} else {'竖屏'})
