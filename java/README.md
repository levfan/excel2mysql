# Java 线（JDK 1.8 / JRE 8）

为了在没有 Windows 更新保障的老 64 位机器上跑同一套导入逻辑而开的一条并行线。
Python 线（GUI 与 Win7 CLI）继续维护，两条线在差分对齐前不互相替换。

## 为什么是 Java 8

CPython 3.8 的扩展模块和 PySide6 都挂在 UCRT（`api-ms-win-crt-*`）上，而 UCRT 要靠
KB2999226 或系统自带；老机器缺它就在 DLL 加载期失败，程序没机会报错。
JDK 8u202 的核心二进制实测零 `api-ms-win-crt-*` 导入，它依赖的 `msvcr100.dll`
自带在 `jre\bin` 里，所以不向操作系统要 UCRT。

这是**构建版本的性质，不是 Java 8 这个号性质的保证**：本机 JDK 17 与 25 的
`java.exe`/`java.dll`/`jvm.dll` 各导入 6–10 个 `api-ms-win-crt-*`，换成分支维护的发行版
就可能把 UCRT 依赖带回来。

出包侧目前实际拦的是两件事，别把它当成"PE 导入表检查"：一是按文件名剔掉
`api-ms-win-*` 转发桩与 `ucrtbase/vcruntime140/concrt140/msvcp140` 这批
（它们的 PE 头声明最低系统 10.0），二是**用包内那个 java.exe 真跑一遍界面自检**——
剔错了运行时的话这一步就起不来。逐个 PE 读导入表的离线扫描没有做，
现在的等价保证来自"跑一次"而不是"扫一遍"。

## 硬约束

- 界面只用 **Swing/AWT**，不用 JavaFX。
  理由要更正一次：原先写的"JavaFX 那批 dll 导入 UCRT"**实测不成立**——扫遍本机
  JRE 8u202 的 130 个 dll/exe，`api-ms-win-crt-*` 导入数为 **0**。
  真实理由有两条：JavaFX 会额外拖进 `glass`/`glib-lite`/`fxplugins`/`decora_sse` 等
  16 个原生 dll（PE 头声明的最低系统是 Vista 6.0，比 Swing 那批的 5.2 高一档），
  而本工具的界面是表单加表格，Swing 完全够用，多那 16 个 dll 只增加老机器上的变量。
- 字节码 major version 必须是 **52**；依赖 jar 也要逐个校验，否则在 JRE 8 上是
  `UnsupportedClassVersionError`，而在本机构建时完全不暴露。
  由 `com.excel2mysql.verify.ClassVersionAudit` 把关，多版本 jar 的
  `META-INF/versions/**` 不计入（JDK 8 不读它，计入会让守卫永久假红）。
- 随包 JRE 与产物一起发布，`启动.bat` 点名用包内 `jre\bin\java.exe`，不依赖目标机装没装 Java。
- **随包 JRE 不能整目录照抄**：实测本机两个 8u202（`jdk1.8.0_202\jre` 与 `jre1.8.0_202`）
  的 `bin` 里都有 40 个 `api-ms-win-core-*.dll` 加 `ucrtbase.dll`/`concrt140.dll`/`vcruntime140.dll`，
  这批桩 PE 头声明的最低系统是 **10.0**，而 JVM 自己的二进制零 crt 导入、只链 `msvcr100.dll`。
  打包时要把它们剔掉，闸门要断言"包内没有任何 `api-ms-win-*.dll`"，
  否则等于把一个 Win10 下限的东西搬到 Win7 上赌它不被加载。


## P1：拼音转写等价性（已完成）

表名与字段名由 `core/infer.py` 的 `lazy_pinyin` 生成，是用户要拿去建库的产物，
所以移植的第一道闸门是**逐条对撞**，不是"看起来一样"。

```bat
:: 1) 重新导出词典与金标准语料（需要 .venv38，即打包同款的 pypinyin 0.55.0）
.venv38\Scripts\python.exe java\tools\export_pinyin_data.py

:: 2) 编译并跑差分（只需 JDK 8）
"%JAVA_HOME%\bin\javac" -encoding UTF-8 -d java\out ^
  java\app\src\main\java\com\excel2mysql\pinyin\PinyinConverter.java ^
  java\verify\src\main\java\com\excel2mysql\verify\PinyinDiffer.java
"%JAVA_HOME%\bin\java" -cp java\out com.excel2mysql.verify.PinyinDiffer ^
  java\app\src\main\resources\pinyin java\verify\golden\cases.jsonl.gz
```

实测数字：

| 项 | 值 |
| --- | --- |
| 词典 | `single.txt` 41923 条 / `phrase.txt` 47111 条 |
| 金标准语料 | 57228 条，全量单字 + 全量词组 + 对抗样本 + 混排串 + 68 个无拼音条目的汉字 |
| 差分结果 | **0 条不一致**，退出码 0 |
| 分支命中 | A 4016、B 41975、C 51120、D 4212；B2 与 E 在严格模式下结构性不可达 |
| 变异抽查 | 把 `ü→v` 故意改成 `ü→u` 后差分报 804 条不一致、退出码 1，还原后回绿 |

语料的期望值全部由真实 pypinyin 产出；`export_pinyin_data.py` 里的 `replica_*` 是对库内
「严格模式正向最大匹配 + 整词优先取首读音 + NORMAL 去声调」的 Python 复刻，
它先与库对撞归零，Java 侧才算拿到读准了的规格。词典与语料一起入库（约 2.4 MB），
目的是让 jar 构建只依赖 JDK，不要求构建机装 Python。

## P1.5：换 jpinyin 做拼音引擎——已实测否决（2026-10-13）

`com.excel2mysql.verify.JpinyinDiffer` 拿 P1 那 57228 条金标准撞 `com.github.stuxuhai:jpinyin:1.1.8`。
只算 core 的 `_syllables` 真正会喂进拼音的输入域（`\u4e00-\u9fff`，计入 52169 条、跳过域外 5059 条）：

| 词典 | 不一致 | 说明 |
| --- | --- | --- |
| jpinyin 自带 | **5110 / 52169（9.8%）** | 单字表只有 20902 条，pypinyin 是 41923 |
| jpinyin 引擎 + 注入 pypinyin 词典 | **154 / 52169（0.3%）** | 已把 41923 单字 + 47111 词组按它的格式导出注入 |

注入后仍不平的那 0.3% 不是数据问题，是结构问题：`PinyinHelper.convertToPinyinString`
**第一行无条件做繁转简**，而 `chinese.dict` 的 2533 个键全部落在输入域内且都映射成别的字
（`著 U+8457 → 着 U+7740`）。键先被换掉，注入的词组必然查不中，单字也取成了着的首读音 `zhe`。
外部没有开关能关掉它；要绕开就得自己调 `convertToPinyinArray` 重新做分词——
那正是"换库是为了不自己写"想省掉的部分。所以 **jpinyin 只作为 verify 依赖保留**（可一键复测），
不进 app 的依赖树，产物 jar 里没有它。

## 当前状态

- P1 拼音等价性：绿。
- P2 `core/` 移植（流式 Excel 推断、精度规则、双跑差分）：绿。九步闸门
  `构建验证.bat` 覆盖 class 版本、拼音、表名/字段名、类型推断、读取层逐格、
  SQL 线与 DDL、界面自检；其中三步差分各跑两份语料（`.xlsx` 与 `.xls` 老格式）。
- P3 CLI 与交付包：绿。`一键打包.bat` 先跑九步闸门再组装，产物
  `dist/excel2mysql-java-<版本>-win64/` 含 `启动.bat`、程序 jar、随包 `conn.ini`、
  `jre\`、`README.txt`；出包时断言剔桩为零、class 主版本 ≤ 52，
  并用**包内那个 java.exe** 跑界面自检与"在别的工作目录里自动读到产物同级 conn.ini"。
- 未闭合：真库端到端（本机没有可连的 MySQL，`SqlRunCli` 只实测了两条拒绝路径）、
  模态进度框那一支（两条线都自动化不了，交付前手工点一次）。
