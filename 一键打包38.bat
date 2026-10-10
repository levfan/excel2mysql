@echo off
chcp 65001 >nul
setlocal
set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"
cd /d "%~dp0"
if not exist ".venv38\Scripts\python.exe" goto :nopython
".venv38\Scripts\python.exe" build_exe38.py %*
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (echo [FAILED] exit code %RC%) else (echo [OK] All checks passed.)
rem Pause only when double-clicked; use System32 findstr absolute path so Git Bash's find cannot shadow it
echo %CMDCMDLINE% | %SystemRoot%\System32\findstr.exe /i /c:"%~f0" >nul && pause
exit /b %RC%

:nopython
echo [ERROR] .venv38\Scripts\python.exe not found.
echo This is the Windows 7 line (Python 3.8.10 + PyInstaller 5.x). Create its venv first:
echo     "C:\Users\YOU\.pyenv\pyenv-win\versions\3.8.10\python.exe" -m venv .venv38
echo     .venv38\Scripts\python -m pip install --upgrade pip
echo     .venv38\Scripts\python -m pip install -r requirements38.txt
pause
exit /b 1
