@echo off
rem Two GUI lines, one machine check: enumerate both toolkits and diff key by key.
rem See tools/gui_dump_py.py (Qt side) and verify/.../GuiDump.java (Swing side).
setlocal
chcp 65001 >nul

set "JDK8=C:\Program Files\Java\jdk1.8.0_202"
if not exist "%JDK8%\bin\javac.exe" (
    echo x [ERROR] PARITY ^| JDK 8 not found: %JDK8%
    exit /b 3
)
set "JAVA_HOME=%JDK8%"
set "PATH=%JDK8%\bin;%PATH%"

set "PY=..\.venv\Scripts\python.exe"
if not exist "%PY%" (
    echo x [ERROR] PARITY ^| python venv not found: %PY%
    exit /b 3
)

cd /d "%~dp0"

echo === 1/2 build app and verify ===
call mvn -B -q install -pl app,verify || exit /b 1

echo === 2/2 dump both GUIs and diff ===
"%PY%" tools\gui_parity.py || exit /b 1

echo.
echo + [OK] PARITY ^| both GUI lines agree key by key
exit /b 0
