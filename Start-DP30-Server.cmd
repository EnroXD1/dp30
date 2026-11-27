@echo off
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0start-demo.ps1" -ServerOnly
set "DP30_STATUS=%ERRORLEVEL%"
pause
exit /b %DP30_STATUS%
