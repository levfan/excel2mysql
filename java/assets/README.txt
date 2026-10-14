Excel → MySQL 批量导入工具（Java 线）v{version}

怎么用
  双击 启动.bat。想一次带上文件，把 .xlsx/.xls/.zip 拖到 启动.bat 图标上即可。
  命令行：启动.bat --gui --selftest   （界面自检，不连库也能跑）

命令行（同一份程序，无界面）
  jre\bin\java.exe -jar {jar} --check            只测连接并列出数据库
  jre\bin\java.exe -jar {jar} C:\数据 --prefix ods_   建表并导入目录下全部 Excel
  jre\bin\java.exe -jar {jar} C:\数据 --dry-run      只看建表语句与脚本预览，完全不碰库
  jre\bin\java.exe -jar {jar} 脚本目录 --no-excel    只把目录里的 .sql 执行到目标库
  动作由路径类型决定：给 .sql 就执行脚本，给 Excel/zip 就建表导入，给目录两件都干，
  再用 --no-sql / --no-excel 各自排除一类。
  注意：Python 命令行版那个『执行脚本』开关在这里不存在，抄过来会报未知选项——
  这边不用开关，给什么后缀就干对应的活。
  不带 -c 时自动读产物同级的 conn.ini；口令建议留空、用环境变量传：set E2M_PASS=你的密码
  优先级：命令行参数 > 环境变量 E2M_HOST/E2M_PORT/E2M_USER/E2M_PASS/E2M_DB > conn.ini

另外两条线，以及三条线的功能区别
  图形版 excel2mysql.exe（Python）：界面与本包逐项目等价（两条线的界面由机器逐项
    比对判定），但它要 64 位 Windows 10 1809+ 或 Windows 11。
  命令行版 excel2mysql-cli.exe（Python）：给路径就干活、没有界面，能跑在 Windows 7 SP1
    上，前提是那台机器的 UCRT 补丁（KB2999226）和系统文件完整——不满足就起不来，
    连日志都看不到。它的命令行选项与本包不同：它有 --run-sql、--swap-prefix、
    --drop-if-exists、--batch、--log、--quiet、--no-recursive、--keep-temp，本包没有；
    本包有 --gui、--no-sql、--no-excel、--debug，它没有。
  本包（Java）：目标机器什么都不用装（Java 运行时随包带着），64 位 Windows 7 起就能跑，
    界面和命令行是同一个 jar。
  只有界面能做、命令行做不了的几件：生成表结构预览（改字段名、类型、主键）、
    替换首个前缀、生成标准/简易 Excel 交付件。

目录
  启动.bat            启动脚本，用包内那个 java.exe，不依赖系统装没装 Java
  {jar}   程序本体（所有依赖已打成一个 jar，界面与命令行同一个包）
  conn.ini            默认连接配置模板；库名留空是有意为之，没指定目标库会直接报用法错误
  jre\               随包的 Java 8 运行时
  README.txt          本文件（快速上手与命令行）
  使用说明.txt        完整操作手册，14 章；与界面里按 F1 打开的是同一份内容

目标系统
  64 位 Windows 7 及以上。随包 JRE 已剔除 {stubs} 个 api-ms-win-* 转发桩与 CRT 批文件，
  留着它们在没有 UCRT 更新的 Win7 上会直接起不来。

体积
  整个目录约 {size}，其中绝大部分是 Java 运行时。
