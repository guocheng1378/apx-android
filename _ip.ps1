$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"== 手机当前 IP =="
& $adb -s 694fc921 shell ip -4 addr show wlan0 | Select-String 'inet '
"== 手机端无线模块状态（最近日志） =="
& $adb -s 694fc921 logcat -d | Select-String 'Wi‑Fi 控制通道|PC 已连入|PC 连接已断开|监听 9500' | Select-Object -Last 6
"== 手机端信标日志 =="
& $adb -s 694fc921 logcat -d | Select-String 'WirelessBeacon|信标' | Select-Object -Last 5
