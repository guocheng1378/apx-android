@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "build_host\Release\apxhost.exe" (
  echo [提示] 未找到 build_host\Release\apxhost.exe
  echo        请先双击 build.bat 完成构建。
  pause
  exit /b 1
)

echo 启动控制面板（浏览器将自动打开 http://127.0.0.1:47990）...
start "" "build_host\Release\apxhost.exe" ui
endlocal
