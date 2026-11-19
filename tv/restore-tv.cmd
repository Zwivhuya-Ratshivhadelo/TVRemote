@echo off
REM Double-click to run. Opens PowerShell with this folder's script.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restore-tv.ps1" %*
pause
