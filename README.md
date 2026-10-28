# Excel → MySQL 批量导入工具

把几十张从关系库导出的 Excel（中文表头）批量导入指定 MySQL 库：自动建表、字段注释就是中文表头、
表名与字段名由中文转拼音，导入前可以逐字段改名字、类型和主键。同一套逻辑有三个交付形态
（Windows 图形版、Windows 7 命令行版、Java 版），三条线跑出来的表结构由差分用例逐条对平。

- 当前版本 v3.1.0，许可 MIT（见 `LICENSE`）
- 面向的人：把手上的 Excel 台账变成 MySQL 库表的人，以及要把这批表结构、DDL、
  数据治理体检脚本交出去的人
- 界面里每个动作都有对应的命令行走法，脚本化与无人值守走命令行那条线
- 本文只讲选型、一分钟上手与工程侧信息；每个页签怎么用、出问题时看哪一条，在
  [`docs/用户使用说明书.md`](docs/用户使用说明书.md)

## 1. 三条线怎么选

| | 图形版 | 命令行版 | Java 版 |
| --- | --- | --- | --- |
| 入口 | `main.py` → `excel2mysql.exe` | `cli.py` → `excel2mysql-cli.exe` | `java/` → `启动.bat` / `java -jar` |
| 能跑的系统 | 64 位 Windows 10 1809+ / Windows 11 | Windows 7 SP1+，且系统 UCRT 补丁完整 | 64 位 Windows 7+，包内自带 Java 8 运行时 |
| 语言与依赖 | Python 3.10+ + PySide6 | Python 3.8.10，不含任何 Qt | Java 8，依赖见 `java/pom.xml` |
| 构建产物 | `dist\excel2mysql\` | `dist\excel2mysql-cli\` | `java\dist\excel2mysql-java-<版本>-win64\` |
| 适合谁 | 要看表结构、要手改字段名 | 要写进批处理、要在 Win7 上跑 | 只能开在老 Win7 上又要界面 |

选型只看三件事：要不要界面、机器是什么系统、要不要写进批处理。三条线的功能差异逐条列在说明书
第 1.2 节，其中两处是硬缺口：Java 版的「替换首个前缀」与「生成交付件」只有界面有、命令行没给
对应开关；两条线的命令行开关集合也不相同，别把一边的命令照抄到另一边。

Java 版界面与图形版界面是等价的，而且这条等价是机器判定的，见第 8 节。

## 2. 主要能力

- 自动建表（InnoDB + utf8mb4），字段注释 = 中文表头，表注释 = 文件名括号前的中文
- 文件名智能解析：`北京市水务局-水库基础信息（dim_reservoir_info）.xlsx` → 表名
  `dim_reservoir_info`、注释 `北京市水务局-水库基础信息`；括号内不是合法英文标识符时整名转拼音
- 表名与字段名由中文逐音节转拼音、蛇形命名，超长自动降级为首字母，可勾选「首字母」「转大写」
- 表名前缀一键批量应用，另有「替换首个前缀」（`ods_t_order` → `change_t_order`）
- 类型智能推断：全量扫描所有数据行，不抽样；金额语义列给 `DECIMAL`，日期、整数、文本按实测定宽
- 「生成表结构预览」里逐字段改名字、数据类型与主键（支持组合主键），改完再导
- 「预览 DDL」一张表一个页签，正文就是脚本里属于这张表的那句 `CREATE TABLE`，要落成文件用
  「SQL 脚本改名」页的保存
- 拖拽、点击虚线区选文件、复制文件后按 `Ctrl+V` 直接添加；剪贴板里有文件就一律按添加文件处理，
  不看焦点在哪
- 支持 ZIP 压缩包：拖一个 zip 进来自动展开包内多个 Excel 逐表导入
- 支持 `.xlsx` 与 `.xls`（97-2003 老格式，由 `xlrd` 解析）
- 两行合并表头自动压成一行（`技术公司` + `小计` → `技术公司-小计`），表头块以外的合并区拒绝导入
- 三个页签：Excel 导入、SQL 脚本改名（改表名/字段名/前缀、生成两份交付 Excel、预览与执行 DDL）、
  SQL 执行器（脚本清单逐文件执行、编辑区手写 SQL、126 条示例库带关键词搜索与两层目录）
- 导出标准/简易 Excel 交付件，长任务有非模态进度框，单表失败不阻断其余表

## 3. 环境要求

| 用途 | 需要 |
| --- | --- |
| 跑图形版 | 64 位 Windows 10 1809+ 或 Windows 11；目标 MySQL 网络可达 |
| 跑命令行版 | Windows 7 SP1+ 且系统 UCRT 补丁与系统文件完整；包内已带 `win7_sp1_patch\` 前置件 |
| 跑 Java 版 | 64 位 Windows 7+；不需要装 Java，包内 `jre\` 就是运行时 |
| 改代码 | 图形线 Python 3.10+；Win7 线 Python 3.8.10；Java 线 JDK 8 与 Maven |

图形版跑不了 Windows 7 是组件层的硬限制（Qt 6、Python 3.9+、PyInstaller 6.x 各自都不支持），
不是少装某个依赖就能解决，逐条依据见说明书第 1.3 节。

## 4. 安装

使用者拿到三条线的任一产物包都不用在目标机上装东西（Java 包连运行时都带着）。下面是在开发机上
重建三条线的环境：两个 Python 环境各装各的，互不干扰（本仓库分别是 `.venv` 与 `.venv38`）。

```bash
# 图形版与开发环境：Windows 10 1809+ / 11，Python 3.10+
pip install -r requirements.txt
# 清单里的 pyinstaller 是打包闸门要用的构建工具，有意一起列进来：
# 重建环境时一条命令就能得到"能跑也能打包"的环境

# Win7 命令行版：Windows 7 SP1+，Python 3.8.10
# 版本必须钉在 requirements38.txt 那一组上：3.8 上装不了 numpy 1.25+ / pandas 2.1+ /
# PyInstaller 6.x，装了就把 Win7 支持弄没了
.venv38\Scripts\python -m pip install -r requirements38.txt

# Java 线：不需要额外安装，用本机 JDK 8 + Maven 即可
```

## 5. 快速开始

### 5.1 图形版

```bash
python main.py                          # 开发态
dist\excel2mysql\excel2mysql.exe        # 打包后双击
```

连上库之后把 Excel 拖进第一页的虚线区即可。界面右上角「?」（或 F1）是内置《使用说明》，
程序内的说明与随包的文本手册同源，不会各说各话。

### 5.2 命令行版（Windows 7）

```bat
excel2mysql-cli.exe D:\台账目录 --host 127.0.0.1 --port 3306 --user root --password 口令 -d ods_data
excel2mysql-cli.exe D\订单.xlsx --dry-run     :: 只看会建成什么样，不写库
excel2mysql-cli.exe -c conn.ini --check       :: 只连接并列出可导入的库名
```

拿到 Win7 真机的第一步是跑 `excel2mysql-cli.exe --version`，能打印 `excel2mysql-cli 3.1.0`
就说明系统层面进程起得来，再谈导入。连接信息也可以放同级的 `conn.ini` 或环境变量 `E2M_*`；
三处来源的优先级、全部开关、退出码含义与排错条目见说明书第 2 章与包内 `README.txt`。
口令含中文在两条 Python 线上都不支持（pymysql 按 latin-1 编码口令），需要中文口令就走 Java 版。

### 5.3 Java 版

一个 jar 两种模式：给 `--gui` 起界面，不给就是命令行，所以不产生第二份包。

```bat
java\dist\excel2mysql-java-3.1.0-win64\启动.bat
java\dist\excel2mysql-java-3.1.0-win64\jre\bin\java.exe -jar ^
    java\dist\excel2mysql-java-3.1.0-win64\excel2mysql-3.1.0.jar D:\台账目录 -c conn.ini
```

Java 线自身的取舍与包内结构见 [`java/README.md`](java/README.md)。

## 6. 文档在哪

| 文档 | 内容 | 位置 |
| --- | --- | --- |
| 项目 README | 选型、上手、构建与验证口径 | `README.md`（本文） |
| 用户使用说明书 | 每个页签怎么用、每个开关的后果、报错怎么读 | `docs/用户使用说明书.md` |
| 程序内《使用说明》 | 与说明书同源的分章说明 | 图形版界面右上角「?」/ F1 |
| 随包操作手册 | 现场是先翻文件、再决定要不要双击 exe | 各产物包内 `README.txt`、`使用说明.txt` |
| Java 线说明 | 为什么是 Java 8、包内结构与剔桩校验 | `java/README.md` |
| 双端对齐记录 | 每一轮界面改动落到两栈的对照与取证 | `java/GUI-对齐清单.md` |

随包的 `README.txt` / `使用说明.txt` 由内置《使用说明》`help_doc.py` 渲染出来，打包闸门会核
"每个章节标题都在手册里、没有残留 HTML 标签"，所以界面里的说明和随包的文本不会分叉。

## 7. 从源码构建

三条线各一套打包闸门，跑在不同环境上，互不影响：

| 产物 | 双击入口 | 实际执行 |
| --- | --- | --- |
| 图形版 | `一键打包-图形版.bat` | `build_exe.py` → `excel2mysql.spec` |
| 命令行版 | `一键打包-命令行版.bat` | `build_exe38.py` → `excel2mysql_cli38.spec` |
| Java 版 | `java\一键打包-Java版.bat` | `java/tools/build_java_dist.py` |
| 三端一起 | `一键打包-三端.bat` | 按图形版 → 命令行版 → Java 版顺序调用，任一步非 0 就停住 |

图形版闸门七步：查环境 → 确认产品 exe 没在跑 → 开发态全量用例 → 构建 → 冻结态用例 →
真启动验收 → 清缓存。全部通过才打印产物路径，任一步失败以非 0 退出码结束，不会给你
"看起来成功"的包；实测耗时约 2 分钟。可选参数：

```bash
python build_exe.py --app-only      # 跳过冻结态用例，仍做启动验收
python build_exe.py --fast          # 只构建产品包（图快时用）
python build_exe.py --keep-cache    # 保留 build/ 中间缓存，加速下一次构建
```

三套打包口径的唯一来源、spec 里的取舍与分发说明见说明书第 5 章。

## 8. 测试与质量闸门

改代码之后的验证口径，按改动落在哪条线选跑哪几个：

| 闸门 | 覆盖什么 | 命令 |
| --- | --- | --- |
| 全量功能用例 | 解析、推断、DDL、导入、界面控件与文案、弹窗、日志 | `.venv\Scripts\python pack_test.py` |
| 旧栈用例 | 同一份 Win7 用例在 Python 3.8 旧栈与新栈各跑一遍，含真库读写与 `.sql` 执行 | `.venv38\Scripts\python pack_test38.py` |
| Java 线十步闸门 | 生成物一致、编译打包、class 主版本 ≤52、拼音/命名/推断/读取/SQL 五道金标准差分、产物逐格差分、Swing 界面自检 | `java\构建验证.bat` |
| 双界面对撞 | 两条 GUI 线的控件文案、按钮启用条件、弹窗文字、写出的文件内容逐键比对 | `java\界面双端对撞.bat` |
| SQL 示例真库回归 | 126 条示例逐条打到真 MySQL 上跑一遍，证明它们是可执行语句而不是摆设 | `.venv\Scripts\python check_sql_samples.py` |

v3.1.0 这一轮实测的数字：`pack_test.py` 通过 896 / 失败 0 / 显式记账未覆盖 5；Java 线十步全过；
双端对撞 340 项界面事实逐键一致（另有 6 项"现场实测尺寸"只留档不比）；126 条示例真库回归
失败 0 条。

要真实 MySQL 读写（`pack_test.py` 的 G 组、`check_sql_samples.py`）先备好凭据：

```bat
set PT_HOST=127.0.0.1& set PT_PORT=3306& set PT_USER=root& set PT_PASS=密码& set PT_DB=测试库
```

未设凭据时相关用例打印 `SKIP`，并在汇总里显式记为"未覆盖"，不会被算成通过。凭据只从环境变量读，
不落进任何被跟踪文件。

三条线共用同一套口径：Python 侧同源靠同一个 `core/` 包，Java 侧是把 `core/` 重写一遍后用金标准
差分对平。`java/tools/check_generated.py` 会重跑生成器，把 `HelpDoc.java`、`SqlSamples.java`
与各自的 Python 源逐字节比对——改了源却忘了重导，Java 界面会照旧显示旧文案，这种漂移一路跟到
交付物里都看不出来，所以这道闸放在构建第一步。

## 9. 项目结构

```
core/                        三条线共用的口径：解析、类型推断、命名、DDL、导入、SQL 切句、ZIP 展开
main.py                      图形版入口（PySide6）
cli.py                       命令行版入口（Win7 线）
help_doc.py                  内置《使用说明》的唯一来源
sql_samples.py               SQL 执行器页 126 条示例的唯一来源（一条七个字段）
version.py / appinfo.py      版本号与随包构建信息
build_exe.py / build_exe38.py / excel2mysql*.spec     两条 Python 线的打包闸门
pack_test.py / pack_test38.py                        两条 Python 线的用例集
check_sql_samples.py / check_sql_exec_parity.py      真库回归与 SQL 执行器对撞
export_manual_txt.py         把内置《使用说明》渲染成随包文本手册
assets/                      随包件：图标、conn.ini、命令行版 README.txt
docs/用户使用说明书.md         从 README 抽出的完整操作说明
java/                        Java 线：app（界面 + 命令行）、verify（差分与自检）、
                             tools（生成器与对撞器）、assets（随包件）
java/GUI-对齐清单.md          每一轮界面改动落到两栈的对照与取证记录
```

界面无独立模板文件：图形版的每一块控件都在 `main.py` 与
`java/app/src/main/java/com/excel2mysql/gui/` 里直接搭，两条线各一份，靠第 8 节那两道闸对平。

## 10. 版本号

版本号的取值唯一来自 `version.py` 的 `APP_VERSION`（窗口标题、程序内《关于》、`--version`、
随包 appinfo 都从它读）。另有 8 处写死的字符串必须跟着一起改：三个 `pom.xml`（根 pom 的
`<version>` 与 `<app.version>`、app/verify 的 `<parent><version>`）、`java/构建验证.bat` 里的
jar 名、随包说明与 `java/assets/conn.ini` 注释里的示例命令、以及本 README 上面那几行路径示例。
漏一处就是"关于框已经是新版本、jar 还叫旧版本"这种交付事故，所以用例里有一条 `T15` 逐处把这些
字符串与 `APP_VERSION` 核一遍（改版本前先跑，改完再跑一次）。

版本号可以带开发阶段后缀（`1.1.0-alpha.1` / `-beta.1` / `-rc.1`），后缀同样只写在 `version.py`
一处，上面那 8 处与产物名会自动带过去；默认不带后缀——发到现场机器的包不该自称"候选"。

## 11. 已知限制

- 图形版跑不了 Windows 7 / 8.1（Qt 6 与 Python 3.9+ 的硬限制），Win7 上用命令行版或 Java 版
- 两条 Python 线的口令不能有中文（pymysql 按 latin-1 编码口令）
- 表头块以外的合并单元格直接拒绝导入，日志列出具体位置，需要人工把表拆开
- 对已有库重新导入时，字段命名规则变更后同一张表生成的列名与旧版不同；默认行为是跳过建表，
  随后 `INSERT` 会报 1054 Unknown column——要么勾「覆盖已存在表」重建，要么在结构预览里把
  字段名改回旧列名，老数据表建议先备份
- 逐条限制、排错项与现场处置办法见说明书第 5.9 节「已知限制」与第 14 章「日志与报错展示」

## 12. 许可

MIT，见 `LICENSE`。
