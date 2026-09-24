cd C:\Users\Administrator\Desktop\全能外设
git diff 41b86c33 9cf96f12 -- android/app/src/main/res/layout-land/activity_main.xml android/app/src/main/res/layout/nav_tabs.xml android/app/src/main/res/layout/activity_main.xml > %TEMP%\diff_xml.txt
git diff 41b86c33 9cf96f12 -- android/app/src/main/java/com/allperiph/ui/MainActivity.kt > %TEMP%\diff_kt.txt
for %%f in (%TEMP%\diff_xml.txt %TEMP%\diff_kt.txt) do echo %%f %%~zf
