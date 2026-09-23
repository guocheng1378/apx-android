@echo off
cd /d "%~dp0"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64
if errorlevel 1 (
  echo VCVARS_FAILED
  exit /b 1
)
cmake -S pc\host -B build_host -DAPXPC_BUILD_SDK=OFF -DAPXPC_BUILD_EXAMPLES=OFF -DAPXPC_BUILD_UI=OFF
if errorlevel 1 (
  echo CMAKE_CONFIG_FAILED
  exit /b 1
)
cmake --build build_host --config Release --target apxdesktop
if errorlevel 1 (
  echo BUILD_FAILED
  exit /b 1
)
echo BUILD_OK
