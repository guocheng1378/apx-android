@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64
if errorlevel 1 exit /b 1
cmake -S pc\display -B build_display
if errorlevel 1 exit /b 1
cmake --build build_display --config Release
echo BUILD_EXIT=%ERRORLEVEL%
endlocal
