@echo off
chcp 65001 >nul
rem PilotACS 一键部署入口（Windows 双击即可运行）
rem 实际逻辑在 deploy.ps1，这里只负责用 PowerShell 调起它

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*

echo.
pause
