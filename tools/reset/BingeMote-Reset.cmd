@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0BingeMote-Reset.ps1"
if errorlevel 1 echo Reset did not finish completely. Read the reset window or recovery report.
pause
