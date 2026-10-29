# <img src="assets/app_icon.png" width="44" height="44" alt="excel2mysql 图标"> Excel → MySQL 批量导入工具

把从关系库导出的中文表头 Excel 批量导入 MySQL：表名与字段名由中文转拼音、字段注释保留中文表头、类型与宽度按全量数据实测推断、导入前可逐字段改名与改类型。同一套逻辑有三个交付形态（Windows 图形版、Windows 7 命令行版、Java 版），三条线跑出来的表结构由差分用例逐条对平。当前版本 v3.2.0，许可 MIT（见 `LICENSE`）。

| 项 | 内容 |
| --- | --- |
| 本文定位 | 面向实施与开发人员：背景、选型、环境、打包、启动、命令行参考、质量闸门。界面操作的完整口径在程序内《使用说明》（右上角「?」或 F1），唯一来源 `help_doc.py`，Java 侧由生成器导出 `HelpDoc.java` |
| 共同内核 | `core/`：解析、命名、类型推断、DDL、导入、SQL 切句、ZIP 展开。Python 三线同源复用，Java 线按同一口径重写后用金标准差分对平 |
| 交付形态 | 三个免安装包，目标机不需要安装任何东西（Java 包连运行时一起带），每个包原样携带本文 |

## 1. 背景与解决的痛点

来源是一次具体交付：几十张从治理库导出的 Excel 台账要落进同一个 MySQL 库，人工逐张写 `CREATE TABLE` 既慢又对不齐口径，而那批机器里最旧的一台只有 Windows 7。

| 现场问题 | 本工具的处理 |
| --- | --- |
| 表结构靠人工写 DDL，列名列法不统一 | 从数据实测推断类型与宽度，直接产出 `CREATE TABLE` |
| 中文表头不能直接当列名 | 逐音节转拼音、蛇形命名，中文原文整句留作字段注释 |
| 几十张表逐个配置逐个导 | 给目录或 ZIP，批量解析、批量建表、分批插入，单表失败不阻断其余表 |
| 长度与精度靠猜，写入时才报 1264 / 1406 | `DECIMAL(p,s)` 按实测小数位与整数位定宽，`VARCHAR(n)` 按全量最大 UTF-8 字节加余量，整表共享 60000 字节行内预算 |
| 最旧那台机器只有 Win7，装不了 Qt 6 与新 Python | 命令行版钉在 Python 3.8 旧栈，Java 版自带零 UCRT 依赖的 JRE 8 |
| 落库前结构还要改（列名、类型、主键） | 结构预览逐字段改；主键保存时校验类型、非空与组合唯一 |
| 治理交付另需表元数据与体检 SQL | 导出标准 / 简易 Excel 交付件；SQL 执行器内置 126 条示例与批量脚本执行 |

不解决的问题：不做增量同步与调度、不代替数据建模评审、不做清洗转换；默认表已存在即跳过建表，覆盖需显式勾选或加开关。

## 2. 三条交付线

| 维度 | 图形版（GUI） | 命令行版（CLI） | Java 版（GUI + CLI 同一个 jar） |
| --- | --- | --- | --- |
| 入口 | `main.py` → `excel2mysql.exe` | `cli.py` → `excel2mysql-cli.exe` | `java/` → `启动.bat` 起界面，`-jar` 起命令行 |
| 支持系统 | 64 位 Windows 10 1809+ / 11 | Windows 7 SP1+，且系统 UCRT 补丁完整 | 64 位 Windows 7+，运行时随包 |
| 运行时 | Python 3.10+（本机 `.venv` = 3.12.8）+ PySide6 | Python 3.8.10（本机 `.venv38`），不含 Qt | JDK 8（本机 `jdk1.8.0_202`） |
| 交互 | 拖文件 / 点按钮 / 进度框 | 给路径 / 看一行一条日志与退出码 | 两种都有，同一个 jar 按首个参数切模式 |
| 产物体积 | 162 MB / 921 个文件 | 131 MB / 1405 个文件（含 42 MB 前置补丁） | 约 232 MB，绝大部分是随包 JRE |
| 适合谁 | 日常在 Win10/11 上导入与调结构 | 老机器、批处理、无人值守 | 老机器上仍要界面，或口令含中文 |

功能差异集中在四处：

| 差异 | 内容 |
| --- | --- |
| 逐字段改结构 | 只有两条图形线有「生成表结构预览」；命令行线用 `--dry-run` 看 DDL 后改源文件 |
| 开关集 | `--run-sql` `--swap-prefix` `--drop-if-exists` `--log` 只在 Python 命令行版；`--no-sql` `--no-excel` `--gui` 只在 Java 版 |
| 口令字符 | 两条 Python 线不支持含中文的口令（pymysql 按 latin-1 编码口令），Java 线走 JDBC 可以 |
| 交付件与 SQL 工作台 | 标准 / 简易 Excel 交付件、DDL 改名、SQL 执行器只在两条图形线；命令行线只做「解析建表导入」与「整脚本执行」 |

图形版跑不了 Windows 7 的原因不是少装依赖，而是四条硬限制叠加：

| 组件 | 最低要求 | 依据 |
| --- | --- | --- |
| Qt 6（`PySide6`） | Windows 10 1809+ | Qt 官方支持矩阵不含 Win7 / 8.1 |
| Python 3.9+ | Win7 及更早不可用 | python.org 逐版本标注，最后支持 Win7 的是 3.8 |
| `numpy` / `pandas` 新版 | `Requires-Python >=3.11` / `>=3.12` | 已装包元数据 |
| PyInstaller 6.x | Windows 8 and newer | 打包器官方 Requirements 页 |

Java 线选 JDK 8 的依据：CPython 3.8 的扩展模块与 PySide6 都挂在 UCRT（`api-ms-win-crt-*`）上，老机器缺它就在 DLL 加载期失败、程序没机会报错；JDK 8u202 的核心二进制实测零 `api-ms-win-crt-*` 导入，依赖的 `msvcr100.dll` 自带在 `jre\bin`。这是构建版本的性质而非 Java 8 这个版本号的性质，换成分支维护的发行版就可能把 UCRT 依赖带回来，所以出包时按文件名剔掉转发桩与 CRT 批文件，并用包内那个 `java.exe` 真跑一遍界面自检。拼音转写、类型推断等口径由 `java/构建验证.bat` 的金标准差分逐条对平，实测数字见第 6 章。

## 3. 开发机环境与依赖

| 线 | 目标系统 | 开发环境 | 依赖清单 |
| --- | --- | --- | --- |
| 图形版 | 64 位 Windows 10 1809+ / 11 | `.venv`（Python 3.12.8） | `requirements.txt` |
| 命令行版 | Windows 7 SP1+ | `.venv38`（Python 3.8.10） | `requirements38.txt` |
| Java 版 | 64 位 Windows 7+ | `jdk1.8.0_202` + Maven | `java/pom.xml`（`maven.compiler.source`/`target` = 1.8） |

```bash
# 图形版：运行依赖与打包工具一次装齐
#   PySide6 pymysql pandas openpyxl xlrd pypinyin cryptography pyinstaller
#   cryptography 不是可选：MySQL 8 的 caching_sha2_password 首次连接走这条路，缺它 pymysql 直接报错
#   pyinstaller 也写在同一份清单里：重建 .venv 一条命令就得到「能跑也能打包」的环境
pip install -r requirements.txt

# 命令行版：版本必须钉在这一组上，3.8 装不了 numpy 1.25+ / pandas 2.1+ / PyInstaller 6.x
"C:\Users\<你>\.pyenv\pyenv-win\versions\3.8.10\python.exe" -m venv .venv38
.venv38\Scripts\python -m pip install -r requirements38.txt

# Java 版：只需 JDK 8 与 Maven；拼音词典与金标准语料已入库，构建不要求装 Python
```

| 约束 | 内容与原因 |
| --- | --- |
| 两份 requirements 必须纯 ASCII | Python 3.8 自带的 pip 21.1.1 按系统首选码页（zh-CN 为 cp936）解析，文件里出现 UTF-8 中文注释直接 `UnicodeDecodeError`，一个依赖都装不上；守卫是 `pack_test38.py` 的 U18 / U19 |
| 旧栈版本不许随手升 | 换新版即失去 Win7 支持；新增开关要同时落到 `cli.py`、Java 线与本文的选项表 |
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
| Java 版 `java\dist\excel2mysql-java-3.2.0-win64\` | `java\一键打包-Java版.bat` | `java/tools/build_java_dist.py` | `jdk1.8.0_202` + Maven |
| 三端一起 | `一键打包-三端.bat` | 按图形版 → 命令行版 → Java 版顺序调用，任一步非 0 即停住 | 不接受参数 |

各线闸门的步骤：

| 线 | 步骤 |
| --- | --- |
| 图形版（七步，实测约 2 分钟） | 查环境 → 确认产品 exe 未在运行 → 开发态全量用例 → 构建 → 冻结态用例 → 真启动验收（起窗口、核对标题、判界面是否卡死） → 清缓存 |
| 命令行版（十步） | 查环境（必须 3.8 + PyInstaller 5.x） → 确认产品 exe 未运行 → 旧栈用例 → 新栈跑同一份用例做跨栈比对 → 构建 → 产物体检（无 Qt 文件、带 `python38.dll`、PE 头最低系统 ≤ 6.1、前置补丁逐文件 md5） → 冻结态用例 → 真跑产品 exe 核对退出码与落盘日志 → 脱离项目目录再跑一次证明自包含 → 汇总打勾 |
| Java 版（十步，出包另有断言） | `java/构建验证.bat`：生成物与源逐字节一致 → Maven 编译打包 → 收集依赖 jar → class 主版本 ≤ 52 → 拼音、表名字段名、类型推断、读取层、SQL 与 DDL 五道金标准差分 → 产物逐格差分 → Swing 界面自检。`build_java_dist.py` 再验剔桩为零、随包件齐全、文档开关名与版本号对得上，并用包内 `java.exe` 跑冒烟 |

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

## 5. 打包后怎么启动

| 线 | 启动方式 | 备注 |
| --- | --- | --- |
| 图形版 | 双击 `dist\excel2mysql\excel2mysql.exe` | 目标机不装 Python；`excel2mysql.exe` 与 `_internal` 必须同级放在一起，只拷 exe 跑不起来 |
| 命令行版 | `dist\excel2mysql-cli\excel2mysql-cli.exe <路径> -c conn.ini` | PyInstaller 5.x 的 onedir 是平铺布局，没有 `_internal`；整目录复制或压成 zip 分发 |
| Java 图形界面 | 双击 `java\dist\excel2mysql-java-3.2.0-win64\启动.bat` | 内部就是包内运行时加 `--gui`，不依赖目标机装没装 Java |
| Java 命令行 | `java\dist\excel2mysql-java-3.2.0-win64\jre\bin\java.exe -jar java\dist\excel2mysql-java-3.2.0-win64\excel2mysql-3.2.0.jar <路径> -c conn.ini` | 同一个 jar 的两种模式，不给 `--gui` 就是命令行；在别的工作目录下也能自动读到产物同级的 `conn.ini` |

随包件（打包脚本逐条断言存在，缺一件不出包）：

| 随包件 | 内容 |
| --- | --- |
| `conn.ini` | 命令行版与 Java 版各带一份空模板，改它即可，不用每次敲参数；来源与优先级见 7.4 |
| `README.md` | 项目根这一份的原样副本，连标题图标 `assets/app_icon.png` 一起放进同名相对路径 |
| `win7_sp1_patch\` | 仅命令行版：四个 `.msu` + `vc_redist.x64.exe` + 自检与安装脚本。缺 UCRT 的机器报 `ImportError: DLL load failed while importing _socket`，先跑 `3.check_win7.bat` 自检，再以管理员运行 `4.install_minimal.bat`（KB3063858 + KB2999226 + vc_redist 三件即可跑起本包），或 `5.install_win7_prereq.bat` 一次装全，重启后验 `--version`。这批二进制是微软按内部使用授权分发的，不入库（`.gitignore` 排除）；目录不在时构建照常通过，但日志会明写「本次没带补丁」 |

## 6. 质量闸门

改完代码按改动落在哪条线选跑：

| 闸门 | 覆盖什么 | 命令 |
| --- | --- | --- |
| 全量功能用例 | 解析、推断、DDL、导入、界面控件与文案、弹窗、日志 | `.venv\Scripts\python pack_test.py` |
| 旧栈用例 | 同一份 Win7 用例在 3.8 旧栈与 3.12 新栈各跑一遍，含真库读写与 `.sql` 执行 | `.venv38\Scripts\python pack_test38.py` |
| Java 线十步闸门 | 生成物一致、class 版本、五道金标准差分、产物逐格差分、界面自检 | `java\构建验证.bat` |
| 双界面对撞 | 两条 GUI 线的控件文案、按钮启用条件、弹窗文字、写出的文件内容逐键比对 | `java\界面双端对撞.bat` |
| SQL 示例真库回归 | 126 条示例逐条打到真 MySQL 上跑一遍 | `.venv\Scripts\python check_sql_samples.py` |

v3.2.0 这一轮的实测结果：

| 项 | 结果 |
| --- | --- |
| `pack_test.py` | 通过 904 / 失败 0 / 显式记账未覆盖 5 |
| `pack_test38.py` | 3.8 旧栈通过 261 / 失败 0 / 1 项显式 `SKIP`（V25 需要真实 `.xls` 样本）；3.12 新栈通过 252 / 失败 0 / 4 项显式 `SKIP` |
| Java 线十步 | 全过；拼音差分 57228 条语料 0 条不一致；jpinyin 路线实测否决（注入同款词典后仍有 0.3% 结构性不等） |
| 双端对撞 | 340 项界面事实逐键一致，另有 6 项「现场实测尺寸」只留档不比 |
| SQL 示例真库回归 | 126 条，失败 0 条 |
| 文档防漂移 | 本文由 `argparse` 与 `cli` 常量反向核（W29 / W29b / W29c / W30 / W31 / W31b / W31c / W31d），版本号四处与 `version.py` 同值（T15） |

改了 `core/` 的解析或推断，四个金标准导出脚本要一起重跑：`java/tools/export_excel_golden.py` → `export_xls_golden.py` → `export_sql_golden.py` → `export_meta_golden.py`。后两个吃前两个的产物，只重跑第一个会把差异推到差分第 8 步才暴露。每一轮界面改动落到两栈的对照与取证记录在 `java/GUI-对齐清单.md`。

## 7. Windows 7 命令行版（实施与无人值守）

`cli.py` 是给同一份 `core/` 加的无界面入口：表名与字段名推断、类型与精度、主键选择、DDL 生成、分批插入、ZIP 展开、合并表头展开与其余合并区拒绝，全部走图形版同一批函数。

### 7.1 前置条件

| 项 | 内容 |
| --- | --- |
| 系统 | 64 位 Windows 7 SP1 及以上，且系统装有 UCRT（KB2999226 / KB4019990）；缺它时进程在进入 Python 之前就被系统拦下 |
| 症状与修复 | 缺补丁报 `ImportError: DLL load failed while importing _socket`；用包内 `win7_sp1_patch\` 的 `3.check_win7.bat` 自检、`4.install_minimal.bat` 安装，详见第 5 章 |
| 数据库 | 目标 MySQL 可达，账号有 `CREATE` 与 `INSERT` 权限；只做连通性校验用 `--check` |
| 首次上机 | 先跑 `excel2mysql-cli.exe --version`，能打印 `excel2mysql-cli 3.2.0` 说明系统层面进程起得来，再谈导入 |

### 7.2 怎么用最省事的四条

```bat
excel2mysql-cli.exe D:\台账目录 --host 127.0.0.1 --port 3306 --user root --password 口令 -d ods_data
excel2mysql-cli.exe D:\订单.xlsx --dry-run      :: 只看会建成什么样，不写库
excel2mysql-cli.exe -c conn.ini --check        :: 只连接并列出可导入的库名
excel2mysql-cli.exe D:\脚本目录 --run-sql      :: 把目录里的 .sql 执行到目标库
```

### 7.3 支持哪些输入与连接信息

| 输入 | 行为 |
| --- | --- |
| 单个 `.xlsx` / `.xls` 文件 | 一个文件一张表；任一 Sheet 的表头含「字段名、数据类型」等列即识别为结构定义表，字段以人工定义为准，位置不限 |
| 目录 | 递归收集目录内的 Excel（`--no-recursive` 只取一层）；同目录里的 `.sql` 只登记，加 `--run-sql` 才执行 |
| `.zip` | 展开包内 Excel 逐个导入，包本身不进列表（`--keep-temp` 保留解出来的临时文件） |
| 文件名 | 以「`（英文表名）`」结尾且括号内是合法标识符时用它做表名、括号前中文做表注释；否则整名转拼音，表名最长 64 字符 |
| 表头 | 顶部标准两行合并表头自动压成 `父-子` 一行；表头块以外的合并区一律拒绝导入，日志给出具体位置 |

连接信息三处来源，优先级从高到低：

| 优先级 | 来源 | 说明 |
| --- | --- | --- |
| 1 | 命令行参数 | `--host` `--port` `--user` `--password` `-d, --database` |
| 2 | 环境变量 | `E2M_HOST` `E2M_PORT` `E2M_USER` `E2M_PASS` `E2M_DB`，日志只写「取自环境变量」，不回显值 |
| 3 | 配置文件 | `-c FILE` 指定，或自动读程序同级的 `conn.ini`（日志标明「自动发现」） |

```ini
[mysql]
host = 127.0.0.1
port = 3306
user = root
password = 改成你的密码
database = ods_data
```

段名接受 `[mysql]` 与 `[connection]`；`database` 留空即不连库；配置文件读不了时告警并退回内置默认值，命令照常跑完。

### 7.4 动作与选项

| 选项 | 作用 |
| --- | --- |
| `-c, --config FILE` | 指定连接配置文件（INI，键 `host/port/user/password/database`）；不给就自动读程序同级的 `conn.ini` |
| `--host` `--port` `--user` `--password` `-d, --database` | 命令行直接给出连接信息，优先级最高 |
| `--dry-run` | 只解析并打印建表 DDL，完全不连库；`.sql` 只报语句数与改写结果 |
| `--check` | 只连接并列出可导入的库名；指定的 `-d` 库不存在时报错退出 |
| `--run-sql` | 把输入里的 `.sql` 执行到目标库：每个文件一个事务，全部语句成功才提交；不加时 `.sql` 只登记不执行 |
| `--prefix ods_` | 给全部表名加前缀（基于基础表名重算，重复应用不叠加）；需要分隔符就自己写上下划线，末尾没写 `_` 时日志警告一句；对 `.sql` 同样生效 |
| `--swap-prefix change` | 替换表名首段：`ods_t_order` → `change_t_order`；名里没有下划线或以 `_` 开头的不改并逐条列在日志里；对 `.sql` 同样生效 |
| `--drop-if-exists` | 建表前先 `DROP TABLE IF EXISTS`，会删掉目标库里的同名表及其数据；只作用于 Excel 建表那条路，脚本内容不改 |
| `--initials` | 表名与字段名取中文拼音首字母 |
| `--upper` | 表名与字段名转大写 |
| `--batch N` | 每批插入行数，默认 500 |
| `--no-recursive` | 目录只取一层 |
| `--keep-temp` | 保留压缩包解出来的临时 Excel |
| `--log FILE` | 另存 UTF-8 日志文件（追加写，可反复跑多轮） |
| `-q, --quiet` | 控制台只留警告、错误和汇总；日志文件仍记全量 |
| `--version` | 打印版本号后退出 |

同一批里两个文件算出同名表时自动加 `_2`、`_3`（与图形版共用 `make_unique_names`）。命名规则、主键自动选择、类型与精度推断的完整口径见程序内《使用说明》的「三条线与选型」「字段命名与类型推断」两节，两条图形线与命令行线共用同一套实现。

### 7.5 日志形态与退出码

一行读完整件事，行首是符号加分级标签，分段用 `|`，便于单独 `findstr` 出一档：

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

| 退出码 | 含义 |
| --- | --- |
| 0 | 全部成功 |
| 1 | 用法或配置错误（参数冲突或非法、配置文件缺失、未指定目标库、没有任何可解析的文件） |
| 2 | 有输入文件解析失败（其余文件照常处理） |
| 3 | 无法连接 MySQL，或目标库不可用 |
| 4 | 有表建表或导入失败（其余表照常处理） |
| 5 | 有 SQL 脚本执行失败（该文件已回滚，其余文件照常处理） |
| 130 | Ctrl+C 中止 |

同时出现多种问题时取最严重的一档（解析失败 + 导入失败报 4；导入成功但脚本失败报 5），批处理脚本里按 `if errorlevel 5 ... else if errorlevel 4 ...` 从大到小判断。

### 7.6 最小验收

| 步骤 | 命令 | 判据 |
| --- | --- | --- |
| 1 | `excel2mysql-cli.exe --version` | 打印版本号、退出码 0：系统层面进程与依赖可用 |
| 2 | `excel2mysql-cli.exe -c conn.ini --check` | 列出库名、退出码 0：网络与凭据可用 |
| 3 | `excel2mysql-cli.exe D:\台账目录 --dry-run --log 试跑.log` | 每张表一句 DDL，退出码 0 或 2：列名与类型符合预期 |

三步通过后再做真实导入。任一步失败，把 `--log` 文件整份取出来看，`x [ERROR]` 行自带 MySQL 错误码。

### 7.7 跑不通时按退出码查

| 退出码 | 先查什么 |
| --- | --- |
| `0` | 无异常；有表没进库时核对日志里的表名与目标库 |
| `1` | 参数拼写与取值、配置文件是否存在、`-d` 是否给了、输入路径下有没有可解析文件 |
| `2` | 日志里 `x [ERROR] 解析失败` 那行的文件路径与原因；单个文件失败不影响其余文件 |
| `3` | 主机、端口、用户名、口令来源；单独跑一次 `--check` 区分网络还是凭据 |
| `4` | 该表日志附了完整 DDL 与 MySQL 错误码；先判断是不是已知拒绝项（见第 9 章） |
| `5` | 脚本失败：日志给出文件名、第几条语句与出错语句全文；该文件已回滚，其余文件不受影响 |
| `130` | 人工中止；已完成的表与文件保留，未开始的不再执行 |

## 8. 项目结构

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
assets/                   图标（.ico / .png）与 conn.ini 模板
win7_sp1_patch/           Win7 前置补丁二进制（不入库，缺失时构建照常）
java/                     Java 线：app（界面 + 命令行）、verify（差分与自检）、tools（生成器、对撞器、金标准导出）
java/GUI-对齐清单.md       每一轮界面改动落到两栈的对照与取证记录
```

界面无独立模板文件，控件在 `main.py` 与 `java/app/src/main/java/com/excel2mysql/gui/` 里各搭一份，由第 6 章的双端对撞闸对平。

## 9. 版本号与已知限制

版本号唯一来自 `version.py` 的 `APP_VERSION`；另有 8 处写死字符串必须同步改（三个 pom、`java/构建验证.bat` 的 jar 名、`help_doc.py`、`java/assets/conn.ini` 与本文的路径示例），漏一处就是「关于框已是新版本、jar 还叫旧版本」这种交付事故，用例 `T15` 逐处核对。版本可带 `-alpha.N` / `-beta.N` / `-rc.N` 后缀，后缀同样只写一处。

| 项 | 内容 |
| --- | --- |
| 系统支持 | 图形版跑不了 Windows 7 / 8.1（Qt 6 与 Python 3.9+ 的硬限制），Win7 上用命令行版或 Java 版 |
| 口令字符 | 两条 Python 线的口令不能含中文 |
| 合并单元格 | 表头块以外的合并单元格直接拒绝导入，日志列出具体位置，需要人工拆表 |
| 重新导入 | 字段命名规则变更后同一张表生成的列名与旧版不同；默认跳过建表，随后 `INSERT` 报 1054 Unknown column。要么勾「覆盖已存在表」重建，要么在结构预览里改回旧列名，老数据表先备份 |
| 真机验收 | 命令行版暂无真 Win7 SP1 机器实测，其就绪状态按「官方支持矩阵 + 旧栈全量用例 + 产物 PE 头取证」判定 |

## 10. 许可

MIT，见 `LICENSE`。
