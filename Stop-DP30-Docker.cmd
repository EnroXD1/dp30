@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-docker.ps1" -Mode Stop %*
set "DP30_STATUS=%ERRORLEVEL%"
pause
exit /b %DP30_STATUS%
