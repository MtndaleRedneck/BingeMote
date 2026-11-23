@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0BingeMote-Finish.ps1"
set "FinishCode=%ERRORLEVEL%"
if not "%FinishCode%"=="0" echo Cleanup did not finish. Read the visible error before reinstalling.
pause
exit /b %FinishCode%
