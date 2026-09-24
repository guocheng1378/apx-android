$adb = "C:\Users\Administrator\Desktop\全能外设\.tools\android-sdk\platform-tools\adb.exe"
"== 前台 =="
& $adb -s 694fc921 shell dumpsys activity activities | Select-String 'topResumedActivity'
"== 进程 =="
& $adb -s 694fc921 shell ps -A | Select-String allperiph
"== 最近全量日志（App 相关） =="
& $adb -s 694fc921 logcat -d -t 100 | Select-String 'MainActivity|物理朝向|ActivityInfo|allperiph' | Select-Object -Last 15
