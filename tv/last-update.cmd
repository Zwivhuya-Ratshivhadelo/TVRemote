@echo off
REM The last time the laptop is needed: puts the TV app that updates itself on the TV.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0last-update.ps1" %*
pause
