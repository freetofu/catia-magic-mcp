@echo off
chcp 65001 >nul
powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0build.ps1"
