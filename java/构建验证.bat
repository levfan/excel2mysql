@echo off
rem Java 线构建 + 四道闸门：class 主版本 <= 52、拼音、命名、推断、读取、SQL 与 DDL。
rem 用随包同款 JDK 8 编译，避免"开发机能编、现场 JRE 跑不了"。
setlocal
chcp 65001 >nul

set "JDK8=C:\Program Files\Java\jdk1.8.0_202"
if not exist "%JDK8%\bin\javac.exe" (
    echo x [ERROR] BUILD ^| 找不到 JDK 8：%JDK8%
    exit /b 3
)
set "JAVA_HOME=%JDK8%"
set "PATH=%JDK8%\bin;%PATH%"

cd /d "%~dp0"
for %%i in (java.exe) do set "RUNTIME=%%~$PATH:i"
echo - [INFO] BUILD ^| 构建所用 JDK %RUNTIME%

echo.
rem 随包信息：提交号/分支/工作区是否脏，只有构建这一刻知道，烧进 jar 里的 appinfo.properties
set "GIT_COMMIT="
set "GIT_BRANCH="
set "GIT_DIRTY=false"
for /f "delims=" %%c in ('git rev-parse --short HEAD 2^>nul') do set "GIT_COMMIT=%%c"
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "GIT_BRANCH=%%b"
for /f "delims=" %%d in ('git status --porcelain -uno 2^>nul') do set "GIT_DIRTY=true"
set "GIT_ARGS=-Dgit.commit=%GIT_COMMIT% -Dgit.branch=%GIT_BRANCH% -Dgit.dirty=%GIT_DIRTY%"
echo - [INFO] BUILD ^| 随包提交号 %GIT_COMMIT%（%GIT_BRANCH%，未提交改动 %GIT_DIRTY%）

echo === 0/10 生成物与源一致（HelpDoc / SqlSamples 不是手抄的）===
set "PY=..\.venv\Scripts\python.exe"
if not exist "%PY%" (
    echo x [ERROR] BUILD ^| 找不到 venv python：%PY%
    exit /b 3
)
"%PY%" tools\check_generated.py || exit /b 1

echo === 1/10 Maven 编译打包 ===
rem IDEA 开着工程时 target 会被占用，clean 删不掉。这时降级成增量构建，
rem 但要把"这一轮没验干净构建"显式说出来——静默当成干净构建才是真隐患。
call mvn -B clean install %GIT_ARGS% 2>nul
if not errorlevel 1 goto :built
echo ! [WARN] BUILD ^| clean 失败（多半是 IDEA 占着 target），降级为增量构建
call mvn -B install %GIT_ARGS% || exit /b 1
echo ! [WARN] BUILD ^| 本轮未验证干净构建，交付前请关掉 IDE 重跑一次
:built

echo.
echo === 2/10 收集依赖 jar ===
call mvn -B -q -pl verify dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime || exit /b 1

echo.
echo === 3/10 class 主版本闸门 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "verify\target\classes" ^
  com.excel2mysql.verify.ClassVersionAudit ^
  app\target\classes verify\target\classes verify\target\lib app\target\excel2mysql-2.5.1.jar || exit /b 1

echo.
echo === 4/10 拼音金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.PinyinDiffer ^
  app\src\main\resources\pinyin verify\golden\cases.jsonl.gz || exit /b 1

echo.
echo === 5/10 表名与字段名金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.NameDiffer ^
  verify\golden\excel.jsonl.gz verify\fixtures || exit /b 1

echo.
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.NameDiffer ^
  verify\golden\xls.jsonl.gz verify\fixtures || exit /b 1

echo.
echo === 6/10 类型推断金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.TypeDiffer ^
  verify\golden\excel.jsonl.gz verify\fixtures || exit /b 1

echo.
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.TypeDiffer ^
  verify\golden\xls.jsonl.gz verify\fixtures || exit /b 1

echo.
echo === 7/10 读取层逐格金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.ReaderDiffer ^
  verify\golden\excel.jsonl.gz verify\fixtures || exit /b 1

echo.
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.ReaderDiffer ^
  verify\golden\xls.jsonl.gz verify\fixtures || exit /b 1

echo.
echo === 8/10 SQL 脚本线与 DDL 金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.SqlDiffer ^
  verify\golden\sql.jsonl.gz verify\golden\excel.jsonl.gz || exit /b 1

echo.
echo === 9/10 生成标准/简易 excel 的产物逐格差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.MetaDiffer ^
  verify\golden\meta.jsonl.gz verify\fixtures || exit /b 1

echo.
echo === 10/10 Swing 界面自检 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -jar "app\target\excel2mysql-2.5.1.jar" ^
  --gui --selftest verify\fixtures || exit /b 1

echo.
echo + [OK] BUILD ^| Java 线十步全过
exit /b 0
