# 构建、测试与三端一致性（维护向）

面向打包、改代码、跑验收的人。拿到包要干活的人请先看 [README.md](README.md)，界面操作细节看程序内《使用说明》（右上角「?」或 F1）。

## 1. 三条交付线的实现形态

| 维度 | 图形版（GUI） | 命令行版（CLI） | Java 版（GUI + CLI 同一个 jar） |
| --- | --- | --- | --- |
| 入口 | `main.py` → `excel2mysql.exe` | `cli.py` → `excel2mysql-cli.exe` | `java/` → `启动.bat` 起界面，`-jar` 起命令行 |
| 支持系统 | 64 位 Windows 10 1809+ / 11 | Windows 7 SP1+，且系统 UCRT 补丁完整 | 64 位 Windows 7+，运行时随包 |
| 运行时 | Python 3.10+（本机 `.venv` = 3.12.8）+ PySide6 | Python 3.8.10（本机 `.venv38`），不含 Qt | JDK 8（本机 `jdk1.8.0_202`） |
| 产物体积 | 162 MB / 921 个文件 | 131 MB / 1405 个文件（含 42 MB 前置补丁） | 约 232 MB，绝大部分是随包 JRE |

功能差异集中在四处：

| 差异 | 内容 |
| --- | --- |
| 逐字段改结构 | 只有两条图形线有「生成表结构预览」；命令行线用 `--dry-run` 看 DDL 后改源文件 |
| 开关集 | `--run-sql` `--swap-prefix` `--drop-if-exists` `--log` 只在 Python 命令行版；`--no-sql` `--no-excel` `--gui` 只在 Java 版 |
| 口令字符 | 两条 Python 线不支持含中文的口令（pymysql 按 latin-1 编码口令），Java 线走 JDBC 可以 |
| 交付件与 SQL 工作台 | 标准 / 简易 Excel 交付件、DDL 改名、SQL 执行器只在两条图形线；命令行线只做「解析建表导入」与「整脚本执行」 |

`cli.py` 是给同一份 `core/` 加的无界面入口：表名与字段名推断、类型与精度、主键选择、DDL 生成、分批插入、ZIP 展开、合并表头展开与其余合并区拒绝，全部走图形版同一批函数。Java 线按同一口径重写，与 Python 线由金标准差分逐条对平。

## 2. 图形版为什么跑不了 Windows 7

不是少装依赖，而是四条硬限制叠加：

| 组件 | 最低要求 | 依据 |
| --- | --- | --- |
| Qt 6（`PySide6`） | Windows 10 1809+ | Qt 官方支持矩阵不含 Win7 / 8.1 |
| Python 3.9+ | Win7 及更早不可用 | python.org 逐版本标注，最后支持 Win7 的是 3.8 |
| `numpy` / `pandas` 新版 | `Requires-Python >=3.11` / `>=3.12` | 已装包元数据 |
| PyInstaller 6.x | Windows 8 and newer | 打包器官方 Requirements 页 |

Java 线选 JDK 8 的依据：CPython 3.8 的扩展模块与 PySide6 都挂在 UCRT（`api-ms-win-crt-*`）上，老机器缺它就在 DLL 加载期失败、程序没机会报错；JDK 8u202 的核心二进制实测零 `api-ms-win-crt-*` 导入，依赖的 `msvcr100.dll` 自带在 `jre\bin`。这是**构建版本的性质而非 Java 8 这个版本号的性质**，换成分支维护的发行版就可能把 UCRT 依赖带回来，所以出包时按文件名剔掉转发桩与 CRT 批文件，并用包内那个 `java.exe` 真跑一遍界面自检。

## 3. 开发机环境与依赖

| 线 | 开发环境 | 依赖清单 |
| --- | --- | --- |
| 图形版 | `.venv`（Python 3.12.8） | `requirements.txt` |
| 命令行版 | `.venv38`（Python 3.8.10） | `requirements38.txt` |
| Java 版 | `jdk1.8.0_202` + Maven | `java/pom.xml`（`maven.compiler.source`/`target` = 1.8） |

```bash
# 图形版：运行依赖与打包工具一次装齐
#   PySide6 pymysql pandas openpyxl xlrd pypinyin cryptography pyinstaller
#   cryptography 不是可选：MySQL 8 的 caching_sha2_password 首次连接走这条路，缺它 pymysql 直接报错
pip install -r requirements.txt

# 命令行版：版本必须钉在这一组上，3.8 装不了 numpy 1.25+ / pandas 2.1+ / PyInstaller 6.x
"C:\Users\<你>\.pyenv\pyenv-win\versions\3.8.10\python.exe" -m venv .venv38
.venv38\Scripts\python -m pip install -r requirements38.txt
```

| 约束 | 内容与原因 |
| --- | --- |
| 两份 requirements 必须纯 ASCII | Python 3.8 自带的 pip 21.1.1 按系统首选码页（zh-CN 为 cp936）解析，文件里出现 UTF-8 中文注释直接 `UnicodeDecodeError`，一个依赖都装不上；守卫是 `pack_test38.py` 的 U18 / U19 |
| 旧栈版本不许随手升 | 换新版即失去 Win7 支持；新增开关要同时落到 `cli.py`、Java 线与文档的选项表 |
| 图形版可用 Essentials | 代码只用到 `QtCore` / `QtGui` / `QtWidgets`；装完整 PySide6 也不会把 WebEngine / Pdf 打进产物（spec 里显式排除） |
| 凭据只走环境变量 | `PT_HOST` / `PT_PORT` / `PT_USER` / `PT_PASS` / `PT_DB` 供用例连真库，不落进任何被跟踪文件 |

开发态直接跑源码：

```bash
.venv\Scripts\python main.py          # 图形版
.venv38\Scripts\python cli.py --help  # 命令行版（不依赖 Qt，也可以在 3.12 上跑）
```

## 4. 打包

三条线各一套闸门，跑在不同环境上，互不影响；任一步失败即以非 0 退出，不会产出「看起来成功」的包。

| 产物 | 一键入口 | 实际执行 | 环境 |
| --- | --- | --- | --- |
| 图形版 `dist\excel2mysql\` | `一键打包-图形版.bat` | `build_exe.py` → `excel2mysql.spec` | `.venv`（3.12 + PyInstaller 6.x） |
| 命令行版 `dist\excel2mysql-cli\` | `一键打包-命令行版.bat` | `build_exe38.py` → `excel2mysql_cli38.spec` | `.venv38`（3.8.10 + PyInstaller 5.x） |
| Java 版 `java\dist\excel2mysql-java-3.3.1-win64\` | `java\一键打包-Java版.bat` | `java/tools/build_java_dist.py` | `jdk1.8.0_202` + Maven |
| 三端一起 | `一键打包-三端.bat` | 按图形版 → 命令行版 → Java 版顺序调用，任一步非 0 即停住 | 不接受参数 |

各线闸门的步骤：

| 线 | 步骤 |
| --- | --- |
| 图形版（七步，实测约 2 分钟） | 查环境 → 确认产品 exe 未在运行 → 开发态全量用例 → 构建 → 冻结态用例 → 真启动验收（起窗口、核对标题、判界面是否卡死） → 清缓存 |
| 命令行版（十步） | 查环境（必须 3.8 + PyInstaller 5.x） → 确认产品 exe 未运行 → 旧栈用例 → 新栈跑同一份用例做跨栈比对 → 构建 → 产物体检（无 Qt 文件、带 `python38.dll`、PE 头最低系统 ≤ 6.1、前置补丁逐文件 md5） → 冻结态用例 → 真跑产品 exe 核对退出码与落盘日志 → 脱离项目目录再跑一次证明自包含 → 汇总打勾 |
| Java 版（十步 + 出包另有断言） | `java/构建验证.bat`：生成物与源逐字节一致 → Maven 编译打包 → 收集依赖 jar → class 主版本 ≤ 52 → 拼音、表名字段名、类型推断、读取层、SQL 与 DDL 五道金标准差分 → 产物逐格差分 → Swing 界面自检。`build_java_dist.py` 再验剔桩为零、随包件齐全、文档开关名与版本号对得上，并用包内 `java.exe` 跑冒烟 |

可选参数：

```bash
python build_exe.py --app-only        # 跳过冻结态用例，仍做启动验收
python build_exe.py --fast            # 只构建产品包
python build_exe.py --keep-cache      # 保留 build/ 中间缓存，加速下一次构建
.venv38\Scripts\python build_exe38.py --no-parity   # 跳过 3.12 那轮跨栈
```

要把 MySQL 真实读写纳入验收，先备好凭据：

```bat
set PT_HOST=127.0.0.1& set PT_PORT=3306& set PT_USER=root& set PT_PASS=密码& set PT_DB=测试库
一键打包-图形版.bat
```

未设凭据时相关用例打印 `SKIP`，并在结尾汇总里显式记为「未覆盖」，不会被算成通过。

## 5. 产物布局与随包件

| 线 | 布局 | 备注 |
| --- | --- | --- |
| 图形版 | PyInstaller 6.x onedir：`excel2mysql.exe` + `_internal\` | 两者必须同级放在一起，只拷 exe 跑不起来 |
| 命令行版 | PyInstaller 5.x onedir：平铺，没有 `_internal` | 整目录复制或压成 zip 分发 |
| Java 版 | `启动.bat` + `excel2mysql-3.3.1.jar` + `jre\` | 内部就是包内 `jre\bin\java.exe -jar … --gui`，不依赖目标机装没装 Java |

随包件（打包脚本逐条断言存在，缺一件不出包）：

| 随包件 | 内容 |
| --- | --- |
| `conn.ini` | 命令行版与 Java 版各带一份空模板 |
| `README.md` | 项目根这一份的原样副本，连标题图标 `assets/app_icon.png` 一起放进同名相对路径 |
| `win7_sp1_patch\` | 仅命令行版：四个 `.msu` + `vc_redist.x64.exe` + `3.check_win7.bat` / `4.install_minimal.bat` / `5.install_win7_prereq.bat`。这批二进制是微软按内部使用授权分发的，不入库（`.gitignore` 排除）；目录不在时构建照常通过，但日志会明写「本次没带补丁」 |

## 6. 质量闸门

改完代码按改动落在哪条线选跑：

| 闸门 | 覆盖什么 | 命令 |
| --- | --- | --- |
| 全量功能用例 | 解析、推断、DDL、导入、界面控件与文案、弹窗、日志 | `.venv\Scripts\python pack_test.py` |
| 旧栈用例 | 同一份 Win7 用例在 3.8 旧栈与 3.12 新栈各跑一遍，含真库读写与 `.sql` 执行 | `.venv38\Scripts\python pack_test38.py` |
| Java 线十步闸门 | 生成物一致、class 版本、五道金标准差分、产物逐格差分、界面自检 | `java\构建验证.bat` |
| 双界面对撞 | 两条 GUI 线的控件文案、按钮启用条件、弹窗文字、写出的文件内容逐键比对 | `java\界面双端对撞.bat` |
| SQL 示例真库回归 | 126 条示例逐条打到真 MySQL 上跑一遍 | `.venv\Scripts\python check_sql_samples.py` |

v3.3.0 这一轮的实测结果：

| 项 | 结果 |
| --- | --- |
| `pack_test.py` | 通过 921 / 失败 0 / 显式记账未覆盖 5（带真库凭据，G1–G8 真跑） |
| `pack_test38.py` | 3.8 旧栈通过 261 / 失败 0 / 1 项显式 `SKIP`；3.12 新栈通过 252 / 失败 0 / 4 项显式 `SKIP` |
| Java 线十步 | 全过；拼音差分 57228 条语料 0 条不一致。另记既有结论：jpinyin 路线实测否决（注入同款词典后仍有 0.3% 结构性不等），本轮未重做该实验 |
| 双端对撞 | 356 项界面事实逐键一致（3.3.0 起把「表名与字段名转小写」两颗勾的文案与切换日志纳入键表，3.3.1 又把示例目录那一栏的字号与分组格渲染纳入三键，此前是 340），另有 6 项「现场实测尺寸」只留档不比 |
| SQL 示例真库回归 | 126 条，失败 0 条 |
| 文档防漂移 | README 由 `argparse` 与 `cli` 常量反向核（W29 / W29b / W29c / W29e / W30 / W31 / W31b / W31c / W31d）；写死的版本号共 9 处与 `version.py` 同值（T15，含本文两处）。README 从 3.3.1 起不写版本号，写进去会被 W29e 判红 |

改了 `core/` 的解析或推断，四个金标准导出脚本要一起重跑：`java/tools/export_excel_golden.py` → `export_xls_golden.py` → `export_sql_golden.py` → `export_meta_golden.py`。后两个吃前两个的产物，只重跑第一个会把差异推到差分第 8 步才暴露。每一轮界面改动落到两栈的对照与取证记录在 `java/GUI-对齐清单.md`。

## 7. 项目结构

```
core/                       三条线共用的口径：解析、类型推断、命名、DDL、导入、SQL 切句、ZIP 展开
main.py / ui_help.py        图形版入口与《使用说明》窗口（PySide6）
cli.py                      命令行版入口（Win7 线）
help_doc.py                 程序内《使用说明》的唯一来源（导出成 java 侧 HelpDoc.java）
sql_samples.py              SQL 执行器页 126 条示例的唯一来源
version.py / appinfo.py     版本号与随包构建信息
build_exe.py / build_exe38.py / excel2mysql*.spec      两条 Python 线的打包闸门
pack_test.py / pack_test38.py                          两条 Python 线的用例集
check_sql_samples.py / check_sql_exec_parity.py        真库回归与 SQL 执行器对撞
_pack_common.py             排除清单与共用件（两条 Python 线打包口径的唯一来源）
assets/                     图标（.ico / .png）与 conn.ini 模板
win7_sp1_patch/             Win7 前置补丁二进制（不入库，缺失时构建照常）
java/                       Java 线：app（界面 + 命令行）、verify（差分与自检）、tools（生成器、对撞器、金标准导出）
java/GUI-对齐清单.md         每一轮界面改动落到两栈的对照与取证记录
```

界面无独立模板文件，控件在 `main.py` 与 `java/app/src/main/java/com/excel2mysql/gui/` 里各搭一份，由第 6 章的双端对撞闸对平。

## 8. 版本号

版本号唯一来自 `version.py` 的 `APP_VERSION`；另有 9 处写死字符串必须同步改（根 pom 的 `<version>` 与 `<app.version>`、app 与 verify 两个 pom 的 `<parent><version>`、`java/构建验证.bat` 的 jar 名、`help_doc.py`、`java/assets/conn.ini`、本文件的两处产物名），漏一处就是「关于框已是新版本、jar 还叫旧版本」这种交付事故，用例 `T15` 逐处核对。版本可带 `-alpha.N` / `-beta.N` / `-rc.N` 后缀，后缀同样只写一处。

`README.md` 不在这些站点里：它是用户手上那份，只讲怎么用，不复制版本号。要确认自己拿的是哪一版，看窗口标题、`--version` 或随包 appinfo。这条由 `W29e` 与 Java 出包闸门反向核——README 里出现 `vX.Y.Z`、`.jar` 全名或 `-win64` 就红。

## 9. 工程侧已知限制

| 项 | 内容 |
| --- | --- |
| 真机验收 | 命令行版暂无真 Win7 SP1 机器实测，其就绪状态按「官方支持矩阵 + 旧栈全量用例 + 产物 PE 头取证」判定 |
| 重新导入 | 字段命名规则变更后同一张表生成的列名与旧版不同；默认跳过建表，随后 `INSERT` 报 1054 Unknown column。要么勾「覆盖已存在表」重建，要么在结构预览里改回旧列名，老数据表先备份 |
| 合并单元格 | 表头块以外的合并单元格直接拒绝导入，日志列出具体位置，需要人工拆表 |

## 10. 命令行完整选项表

`excel2mysql-cli.exe` 的全部长选项（完整说明以 `--help` 为准）：

| 选项 | 作用 |
| --- | --- |
| `-c, --config FILE` | 指定连接配置文件（INI）；不给就自动读程序同级的 `conn.ini` |
| `--host` `--port` `--user` `--password` `-d, --database` | 命令行给出连接信息，优先级最高 |
| `--dry-run` | 只解析并打印建表 DDL，完全不连库；`.sql` 只报语句数与改写结果 |
| `--check` | 只连接并列出可导入的库名；指定的 `-d` 库不存在时报错退出 |
| `--run-sql` | 把输入里的 `.sql` 执行到目标库：每个文件一个事务，全部语句成功才提交 |
| `--prefix P` | 给全部表名加前缀（基于基础表名重算，重复应用不叠加）；分隔符要自己写上下划线 |
| `--swap-prefix NEW` | 替换表名首段：`ods_t_order` → `change_t_order`；名里没有下划线或以 `_` 开头的不改并逐条列在日志里 |
| `--drop-if-exists` | 建表前先 `DROP TABLE IF EXISTS`，会删掉同名表及其数据；只作用于 Excel 建表那条路 |
| `--initials` | 表名与字段名取中文拼音首字母 |
| `--upper` | 表名与字段名转大写 |
| `--batch N` | 每批插入行数，默认 500 |
| `--no-recursive` | 目录只取一层 |
| `--keep-temp` | 保留压缩包解出来的临时 Excel |
| `--log FILE` | 另存 UTF-8 日志文件（追加写，可反复跑多轮） |
| `-q, --quiet` | 控制台只留警告、错误和汇总；日志文件仍记全量 |
| `--version` | 打印版本号后退出 |

同一批里两个文件算出同名表时自动加 `_2`、`_3`（与图形版共用 `make_unique_names`）。

## 11. 日志分级与判读

```
+ [OK] 解析成功 | 订单（ods_t_order）.xlsx → 表 `ods_t_order` | 字段 5 列 | 数据 3 行 | 主键 `ding_dan_bian_hao`（…）
! [WARN] 数据警告 | `t`.`amount`（DECIMAL(16,2)）共 2 个值无法转换，示例：第 7 行 'abc'；已按 NULL 写入
x [ERROR] 建表失败 | `t` | 目标库 testdb | ProgrammingError（MySQL 错误码 1064）：You have an error in your SQL syntax…
```

| 行首 | 级别 | 使用场合 |
| --- | --- | --- |
| `+` | OK | 完成 |
| `-` | INFO | 记录，不需要处理 |
| `!` | WARN | 未执行但有原因：跳过、重复、取消、空包 |
| `x` | ERROR | 失败，需要处理 |

| 项 | 口径 |
| --- | --- |
| 符号集 | 只用 `+ - ! x` 四种 ASCII：Win7 `cmd` 默认 cp936 码页，打勾打叉类符号会显示成乱码或方块 |
| MySQL 报错 | 统一展开成「异常类型 + MySQL 错误码 + 服务端原文」，与图形版同一口径 |
| 建表失败 | 日志附完整 DDL，并提醒 `CREATE/DROP/ALTER` 属 DDL，MySQL 隐式提交、不随事务回滚 |

同时出现多种问题时取最严重的一档（解析失败 + 导入失败报 4；导入成功但脚本失败报 5），批处理脚本里按 `if errorlevel 5 … else if errorlevel 4 …` 从大到小判断。

## 12. 跑不通时按退出码查

| 退出码 | 先查什么 |
| --- | --- |
| `0` | 无异常；有表没进库时核对日志里的表名与目标库 |
| `1` | 参数拼写与取值、配置文件是否存在、`-d` 是否给了、输入路径下有没有可解析文件 |
| `2` | 日志里 `x [ERROR] 解析失败` 那行的文件路径与原因；单个文件失败不影响其余文件 |
| `3` | 主机、端口、用户名、口令来源；单独跑一次 `--check` 区分网络还是凭据 |
| `4` | 该表日志附了完整 DDL 与 MySQL 错误码；先判断是不是已知拒绝项（见第 9 章） |
| `5` | 脚本失败：日志给出文件名、第几条语句与出错语句全文；该文件已回滚，其余文件不受影响 |
| `130` | 人工中止；已完成的表与文件保留，未开始的不再执行 |
