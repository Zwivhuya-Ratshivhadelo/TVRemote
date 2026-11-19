@echo off
REM Builds the TV home screen and the phone remote, and installs the update on the TV.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" %*
pause
