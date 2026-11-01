@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem Java 线交付包：程序 jar + 随包 JRE + 启动脚本，产物落在仓库根 dist\ 下，与另两端同目录。
rem 先跑 java\构建验证.bat（十步闸门），过了才组装——验证没过就出包，出的就是坏包。

set "PY=.venv\Scripts\python.exe"
if not exist "%PY%" set "PY=python"

call ".\java\构建验证.bat"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :report

"%PY%" java\tools\build_java_dist.py %*
set "RC=%ERRORLEVEL%"

:report
echo.
if not "%RC%"=="0" (echo [FAILED] exit code %RC%) else (echo [OK] All checks passed.)
rem 只在真正双击时停住，CI 里直接退
echo %CMDCMDLINE% | %SystemRoot%\System32\findstr.exe /i /c:"%~f0" >nul && pause
exit /b %RC%
