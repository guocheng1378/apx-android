$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"== 当前连接 =="
cmd /c "netstat -ano | findstr 192.168.2.182"
"== 手机端最新日志（鼠标/摄像头/PC 命令） =="
& $adb -s 694fc921 logcat -d | Select-String 'PC 命令|CameraModule|鼠标|Mouse|GestureEngine|HidKeys' | Select-Object -Last 12
