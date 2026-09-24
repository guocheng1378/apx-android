$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
& $adb -s 694fc921 shell monkey -p com.allperiph -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep 3
& $adb -s 694fc921 shell uiautomator dump /sdcard/uc2.xml | Out-Null
& $adb -s 694fc921 pull /sdcard/uc2.xml C:\Users\Administrator\Desktop\uc2.xml | Out-Null
$s = Get-Content C:\Users\Administrator\Desktop\uc2.xml -Raw -Encoding UTF8
"页面: " + $(if ($s -match 'id/pageKeyboard') {'键盘页'} elseif ($s -match 'id/pageTouchpad') {'触控板页'} elseif ($s -match 'id/pageStatus') {'状态页'} else {'?'})
foreach ($t in '无线','蓝牙','USB') { if ($s.Contains($t)) { "开关可见: $t" } }
"前台: " + ((& $adb -s 694fc921 shell dumpsys activity activities | Select-String 'topResumedActivity') -join '')
