"== 构建产物 / 部署目标 / 源码 时间 =="
Get-Item 'C:\Users\Administrator\Desktop\全能外设\build_host\Release\apxdesktop.exe',
         'C:\Users\Administrator\AppData\Local\Programs\AllPeriph\apxdesktop.exe',
         'C:\Users\Administrator\Desktop\全能外设\pc\host\src\media\h264_decoder.cpp' |
    Select-Object Name,LastWriteTime,Length | Format-Table -AutoSize
"== 运行中的面板 =="
Get-Process apxdesktop -ErrorAction SilentlyContinue | ForEach-Object {
    "pid=$($_.Id) start=$($_.StartTime) path=$($_.Path)"
}
