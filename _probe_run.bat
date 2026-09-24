@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul 2>&1
cd /d C:\Users\Administrator\Desktop\全能外设
cl /nologo /EHsc /O2 _probe.cpp /Fe:_probe.exe dxgi.lib user32.lib
_probe.exe
