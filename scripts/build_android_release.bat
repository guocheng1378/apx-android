@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
set JAVA_HOME=C:\Users\Administrator\devtools\jdk-17.0.20.1+1
C:\Users\Administrator\devtools\gradle-8.9\bin\gradle.bat -p "%CD%\android" :app:assembleRelease :tv:assembleRelease --console=plain --no-daemon
echo BUILD_EXIT=%ERRORLEVEL%
endlocal
