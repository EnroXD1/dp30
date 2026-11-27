@echo off
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0start-demo.ps1"
if errorlevel 1 pause
