@echo off
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0start-wifi.ps1" %*
set "DP30_STATUS=%ERRORLEVEL%"
pause
exit /b %DP30_STATUS%
