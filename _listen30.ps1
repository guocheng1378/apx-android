$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 694fc921 logcat -c
"监听 30 秒——请现在转手机：竖→横→竖"
Start-Sleep 30
& $adb -s 694fc921 logcat -d | Select-String '物理朝向切换|applyOrientationLayout|朝向兜底' | Select-Object -Last 15
& $adb -s 694fc921 shell uiautomator dump /sdcard/uf.xml | Out-Null
& $adb -s 694fc921 pull /sdcard/uf.xml C:\Users\Administrator\Desktop\uf.xml | Out-Null
$s = Get-Content C:\Users\Administrator\Desktop\uf.xml -Raw -Encoding UTF8
"最终页面: " + $(if ($s -match 'id/pageKeyboard') {'键盘页'} elseif ($s -match 'id/pageTouchpad') {'触控板页'} else {'?'})
