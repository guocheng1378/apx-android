cd C:\Users\Administrator\Desktop\全能外设
git show 41b86c33:android/app/src/main/java/com/allperiph/ui/MainActivity.kt > %TEMP%\old_main.kt
git show HEAD:android/app/src/main/java/com/allperiph/ui/MainActivity.kt > %TEMP%\new_main.kt
echo == 旧版 onConfigurationChanged/applyOrientation ==
findstr /n /c:"onConfigurationChanged" /c:"applyOrientationLayout" /c:"landscape =" %TEMP%\old_main.kt
echo == 新版 ==
findstr /n /c:"onConfigurationChanged" /c:"applyOrientationLayout" /c:"landscape =" %TEMP%\new_main.kt
