@echo off
title JADX GUI with Live APK Patcher
echo ===================================================
echo Starting JADX GUI with Native Live APK Patcher...
echo ===================================================
java -cp "%~dp0tools\apk-live-modder\bin\patcher_classes;%~dp0tools\apk-live-modder\bin\jadx\lib\jadx-1.5.1-all.jar" jadx.gui.JadxGUI %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo JADX GUI exited with code %ERRORLEVEL%
    pause
)
