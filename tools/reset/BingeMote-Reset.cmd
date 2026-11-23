@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0BingeMote-Reset.ps1"
set "ResetCode=%ERRORLEVEL%"
if not "%ResetCode%"=="0" echo Reset did not finish completely. Read the reset window or recovery report.
pause
exit /b %ResetCode%
