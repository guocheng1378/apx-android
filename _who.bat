cd C:\Users\Administrator\Desktop\全能外设
echo ===who includes codecapi/icodecapi===
findstr /s /n /c:"codecapi.h" pc\host\src\*.cpp pc\host\src\*.hpp pc\host\include\apxpc\media\*.hpp 2>nul
echo ===CodecAPIEventData in dda?===
findstr /n /i "codecapi" pc\display\capture\dda_capture_win.cpp 2>nul
