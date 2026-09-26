@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem v117：Web 控制台已下线 —— run.bat 改为启动桌面端面板（apxdesktop）
if not exist "build_host\Release\apxdesktop.exe" (
  echo [提示] 未找到 build_host\Release\apxdesktop.exe
  echo        请先双击 build.bat 完成构建。
  pause
  exit /b 1
)

echo 启动桌面端面板（apxdesktop）...
start "" "build_host\Release\apxdesktop.exe"
endlocal
