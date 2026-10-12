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
echo === 1/6 Maven 编译打包 ===
call mvn -B clean install || exit /b 1

echo.
echo === 2/6 收集依赖 jar ===
call mvn -B -q -pl verify dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime || exit /b 1

echo.
echo === 3/6 class 主版本闸门 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "verify\target\classes" ^
  com.excel2mysql.verify.ClassVersionAudit ^
  app\target\classes verify\target\classes verify\target\lib app\target\excel2mysql-1.0.1.jar || exit /b 1

echo.
echo === 4/6 拼音金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.PinyinDiffer ^
  app\src\main\resources\pinyin verify\golden\cases.jsonl.gz || exit /b 1

echo.
echo === 5/6 表名与字段名金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.NameDiffer ^
  verify\golden\excel.jsonl.gz || exit /b 1

echo.
echo === 6/6 类型推断金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.TypeDiffer ^
  verify\golden\excel.jsonl.gz || exit /b 1

echo.
echo === 7/7 读取层逐格金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.ReaderDiffer ^
  verify\golden\excel.jsonl.gz verify\fixtures || exit /b 1

echo.
echo === 8/8 SQL 脚本线与 DDL 金标准差分 ===
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes" ^
  com.excel2mysql.verify.SqlDiffer ^
  verify\golden\sql.jsonl.gz verify\golden\excel.jsonl.gz || exit /b 1

echo.
echo + [OK] BUILD ^| Java 线八步全过
exit /b 0
