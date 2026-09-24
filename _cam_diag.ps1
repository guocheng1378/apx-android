$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"== 手机端 CameraModule 日志 =="
& $adb -s 192.168.2.182:5555 logcat -d | Select-String 'CameraModule|Camera \d|apx-cam' | Select-Object -Last 12
"== 手机端 MediaOut / 发送 =="
& $adb -s 192.168.2.182:5555 logcat -d | Select-String 'MediaOut|STREAM_CAMERA|摄像头' | Select-Object -Last 8
"== 连接状态 =="
cmd /c "netstat -ano | findstr 9502"
