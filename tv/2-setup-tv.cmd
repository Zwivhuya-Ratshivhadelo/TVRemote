@echo off
REM Builds the home screen, then sets it up on the TV (also use this to update it).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
if errorlevel 1 goto end
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-tv.ps1" %*
:end
pause
