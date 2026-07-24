@echo off
chcp 65001 >nul
echo.
echo ================================================================
echo  StereotypesHelper API Diagnostic
echo ================================================================
echo.

setlocal enabledelayedexpansion

set MAGICDRAW_DIR=D:\Program Files\Magic Systems of Systems Architect 26x HF1
set PLUGIN_DIR=%cd%

if not exist "%MAGICDRAW_DIR%" (
    echo ERROR: MagicDraw not found at: %MAGICDRAW_DIR%
    pause
    exit /b 1
)

REM Build the diagnostic class
echo [1/2] Compiling diagnostic tool...
mvn compile > nul 2>&1

if %errorlevel% neq 0 (
    echo ERROR: Compilation failed
    mvn compile
    pause
    exit /b 1
)

echo [OK] Compilation successful

REM Run the diagnostic
echo.
echo [2/2] Running StereotypesHelper diagnostic...
echo.

mvn exec:java ^
    -Dexec.mainClass="com.example.mcpbridge.utils.StereotypesHelperDiagnostic" ^
    2>&1

echo.
echo ================================================================
echo  [DONE] Diagnostic complete
echo ================================================================
echo.
echo Next steps:
echo   1. Look at the methods listed above
echo   2. Note the correct method signature for setting stereotype properties
echo   3. Update StereotypePropertiesHandler.java with the correct method call
echo.
pause
