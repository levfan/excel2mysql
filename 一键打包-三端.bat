@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem Three-end build: 1 GUI (Python 3.12) -> 2 CLI (Python 3.8) -> 3 Java, in that order.
rem Stop at the first failing step: all three artifacts come from the same source, so a bad gate means bad packages.
rem No arguments here; pass per-end options (e.g. --fast) to the matching single-end bat.

echo ============================================================
echo [1/3] 一键打包-图形版.bat    Python 3.12 + PySide6 图形界面（Win10/11）
echo ============================================================
call "%~dp0一键打包-图形版.bat"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
    set "FAILED=第 1 端（图形版）"
    goto :report
)

echo.
echo ============================================================
echo [2/3] 一键打包-命令行版.bat    Python 3.8 无界面命令行（Win7 SP1）
echo ============================================================
call "%~dp0一键打包-命令行版.bat"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
    set "FAILED=第 2 端（命令行版）"
    goto :report
)

echo.
echo ============================================================
echo [3/3] java\一键打包-Java版.bat    Java 图形 + 命令行同一个 jar（64 位 Win7 及以上）
echo ============================================================
call "%~dp0java\一键打包-Java版.bat"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
    set "FAILED=第 3 端（Java 版）"
    goto :report
)

:report
echo.
if not "%RC%"=="0" (
    echo [FAILED] %FAILED% 打包没过（exit code %RC%），其余各端未执行。
) else (
    echo [OK] 三端全部打包完成。
)
rem Pause only on a real double-click; use the absolute System32 findstr so Git Bash find cannot hijack it.
echo %CMDCMDLINE% | %SystemRoot%\System32\findstr.exe /i /c:"%~f0" >nul && pause
exit /b %RC%
