@echo off
title JADX APK Live Modder
echo Starting JADX APK Live Modder GUI...
python "%~dp0gui.py"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Press any key to exit...
    pause >nul
)
