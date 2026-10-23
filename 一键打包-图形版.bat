@echo off
chcp 65001 >nul
setlocal
set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" goto :nopython
".venv\Scripts\python.exe" build_exe.py %*
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (echo [FAILED] exit code %RC%) else (echo [OK] All checks passed.)
rem 只在真正双击时暂停；用 System32 的 findstr 绝对路径，避免被 Git Bash 的 find 抢走
echo %CMDCMDLINE% | %SystemRoot%\System32\findstr.exe /i /c:"%~f0" >nul && pause
exit /b %RC%

:nopython
echo [ERROR] .venv\Scripts\python.exe not found.
echo Create the venv first, from this folder:
echo     python -m venv .venv
echo     .venv\Scripts\python -m pip install -r requirements.txt
pause
exit /b 1
