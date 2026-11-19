@echo off
REM Emergency: gets the remote working again.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0unstick-tv.ps1" %*
pause
