@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================
echo  全能外设 · 一键构建（Windows）
echo ============================================
echo.

rem ---- 1) 确保 MSVC 环境（若已在开发者命令行则跳过）----
where cl >nul 2>nul
if not errorlevel 1 goto HAVE_CL

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" set "VSWHERE=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" goto NO_CL

for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
if not defined VSPATH goto NO_CL

echo [信息] 使用 Visual Studio: %VSPATH%
call "%VSPATH%\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul
if errorlevel 1 goto NO_CL

:HAVE_CL
echo [1/2] 配置 CMake...
cmake -S pc\host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
if errorlevel 1 goto FAIL

echo [2/2] 编译 apxhost / apxdesktop...
cmake --build build_host --config Release --target apxhost apxdesktop
if errorlevel 1 goto FAIL

echo.
echo [完成] 产物：
echo         build_host\Release\apxhost.exe      命令行 / Web 控制面
echo         build_host\Release\apxdesktop.exe   桌面端 · 无线控制中枢（双击运行）
echo.

rem 传 nobuild 参数则只构建、不启动
if /i "%~1"=="nobuild" goto END

echo 启动控制面板（浏览器将自动打开 http://127.0.0.1:47990）...
start "" "build_host\Release\apxhost.exe" ui
goto END

:NO_CL
echo [错误] 未找到 MSVC 编译器 / Visual Studio 生成工具。
echo        请安装「Visual Studio 2022 生成工具」并勾选
echo        「使用 C++ 的桌面开发」工作负载，或改用「x64 Native Tools 命令提示符」运行本脚本。
exit /b 1

:FAIL
echo [错误] 构建失败，请查看上方输出。
exit /b 1

:END
endlocal
