$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"== 手机端：控制通道/摄像头模块日志 =="
& $adb -s 694fc921 logcat -d | Select-String 'PC 命令|CameraModule|TcpControlChannel|TcpCtrlBridge|WirelessModule|摄像头' | Select-Object -Last 25
"== PC 端 9500/9502 连接 =="
cmd /c "netstat -ano | findstr :9500 & netstat -ano | findstr :9502"
