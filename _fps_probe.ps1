$adb = 'C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe'
function PhoneFps($tag) {
    $l = (& $adb -s 694fc921 logcat -d | Select-String '副屏页：副屏运行中' | Select-Object -Last 1) -join ''
    "$tag $l"
}
# 手机进副屏页
& $adb -s 694fc921 shell monkey -p com.allperiph -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s 694fc921 shell input tap 244 2510   # 触控板页签
Start-Sleep -Seconds 1
& $adb -s 694fc921 shell input tap 834 1977   # 副屏按钮
Start-Sleep -Seconds 3
& $adb -s 694fc921 logcat -c
PhoneFps "BASELINE_START"
Start-Sleep -Seconds 10
PhoneFps "NORMAL_10S    "
