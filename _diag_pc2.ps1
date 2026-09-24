"== 面板进程 =="
Get-Process apxdesktop -ErrorAction SilentlyContinue | Select-Object Id,StartTime,Path | Format-Table -AutoSize
"== 全部相关端口 =="
cmd /c "netstat -ano | findstr /c:"192.168.2.182""
