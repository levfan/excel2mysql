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
就可能把 UCRT 依赖带回来。因此交付闸门里保留一条 PE 导入表检查。

## 硬约束

- 界面只能用 **Swing/AWT**，禁止 JavaFX：JRE 里那批 `javafx_*`/`glass`/`glib-lite`
  等 16 个 dll 导入 UCRT，其中部分 PE 下限是 Windows 10。
- 字节码 major version 必须是 **52**；依赖 jar 也要逐个校验，否则在 JRE 8 上是
  `UnsupportedClassVersionError`，而在本机构建时完全不暴露。
- 随包 JRE 与产物一起发布，`启动.bat` 点名用包内 `jre\bin\java.exe`，不依赖目标机装没装 Java。

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

## 当前状态

- P1 拼音等价性：绿。
- P2 `core/` 移植（流式 Excel 推断、精度规则、双跑差分）：未开始，需要 Maven 联网取 POI。
- P3 CLI 与交付包：未开始。
