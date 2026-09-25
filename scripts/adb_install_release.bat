@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
set ADB=C:\Users\Administrator\devtools\android-sdk\platform-tools\adb.exe
echo [1/2] 安装 app-release.apk ...
"%ADB%" install -r android\app\build\outputs\apk\release\app-release.apk
echo [2/2] 安装 tv-release.apk ...
"%ADB%" install -r android\tv\build\outputs\apk\release\tv-release.apk
echo DONE
endlocal
