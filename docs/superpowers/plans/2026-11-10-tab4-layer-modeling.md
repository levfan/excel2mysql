# tab4 分层建模 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 excel2mysql Java 图形版新增第 4 页签「分层建模」，读 ods 库表结构，按映射文件产出 obj/dom/ads 三层的 MySQL 建表 DDL＋转换 SQL＋字段映射，并可选入库。

**Architecture:** 三个新组件——`core/MappingFile`（读写映射 CSV，纯离线）、`core/LayerGen`（按映射＋源结构产 DDL/转换 SQL/字段映射，离线逻辑与连库 I/O 分离）、`gui/LayerPage`（第 4 页签，仿 SqlPage 的 Host 模式）。MainFrame 挂第四页、加资源位 RES_LAYER、同时实现 SqlPage.Host 与 LayerPage.Host。跨库读写一律用 `USE 目标库;` 头＋全限定源表名，复用 `Ddl.buildDdl` 不改其口径。

**Tech Stack:** Java 8（Swing，javax.swing，无 JavaFX）、Maven 多模块（app/verify）、fastexcel 1.3.0、mysql-connector-j 8.0.33、JDBC DatabaseMetaData。测试走 verify 模块独立 main 差分器＋GuiMain --selftest＋真库回归。

**Spec:** `docs/superpowers/specs/2026-11-10-tab4-layer-modeling-design.md`

## Global Constraints

逐条 copied verbatim from the spec / 项目铁律，每个任务 implicitly 包含：

- 范围只做 Java 图形版。Python 两端、Java CLI、既有三页逻辑均不改动。
- JDK 1.8（source/target 1.8，随包 JRE 8u202）；版本 5.1.0；依赖只走阿里云镜像 `https://maven.aliyun.com/repository/public`。
- 目标口径取自需求条款，不自行编造：obj＝十类维度（条款 183 表 5-1）；dom＝七领域 33 子类（条款 184 表 5-2）；ads＝三主线主题（条款 188 表 6-1）；治理字段表 5-3（obj/dom）、表 6-4（ads）。
- 验收最小集，不追求满分：满足"有功能、可配置、可回溯"。归一/码表对齐/指标口径留可配置＋标"待核"，不做跨源模糊匹配、不替业务方定死指标公式。
- 口令只进 JPasswordField / 环境变量，全程不落文件、不进日志、不进任何被跟踪文件（远端公开仓库）。测试库凭据走 `.pt_env` / `E2M_*` 环境变量。
- 拓扑分叉—汇合：obj∥dom 各自从 ods 出；ads 从 obj＋dom 出。资产目录不是第五层，不涉及。
- 日志格式：行首符号＋大写分级标签（`- [INFO] TAG |` / `! [WARN] TAG |` / `x [ERROR] TAG |` / `+ [OK] TAG |`）；单表失败不掀整轮；收尾日志＋弹窗双写。
- 断言锁可数副作用，不锁"没报错"；新增断言后做变异抽查（改坏产品代码须真变红，报可读 FAIL 行而非掀整轮，再逐字节还原＋md5 自证）。
- 真库回归只动探针前缀表/库（`e2mlayerprobe*`），跑前记清单、跑后一张不多一张不少；凭据缺失才允许 SKIP，端口不通要显式说明。
- 提交按分项 commit（仓库风格 `feat(core):` / `feat(gui):` / `test(verify):` / `docs(plan):`，中文描述）；默认不 push（push 须用户明说）。
- 内场无数据、外场有数据、表头结构一致：默认"只生成脚本"模式不连库写入，产物整目录可拷进内场先建结构。

## 构建与测试命令（所有任务通用，先读这一段）

仓库根：`D:/00.personal/4.code/excel2mysql`，但 **Maven reactor 在 `java/` 子目录**——所有 mvn 命令必须先 `cd java/`，否则报 "Could not find the selected project in the reactor: app"。Git Bash 直调 mvn 必须钉 JAVA_HOME（本机 JDK8 在 `C:/Program Files/Java/jdk1.8.0_202`）。**必须带 git stamp 四参**，否则 appinfo.properties 的 commitFull="未知"，GUI 自检咬"复制出去的提交号不是 40 位十六进制"而 exit 1。**PATH 用 unix 挂载式 `/c/...` 前置**：Git Bash 里 `export PATH="$JAVA_HOME/bin:$PATH"` 会把 `C:/Program Files/.../bin` 在盘符冒号处切散（只塞进一个无用的 `C` 项），裸 `java`/`javac` 是否 JDK8 全凭环境已有 PATH 侥幸；直接跑差分器/自检/`java -jar` 要确定落 JDK8，必须前置 `/c/Program Files/Java/jdk1.8.0_202/bin`（Maven 自身读 JAVA_HOME 不受影响）。

下面是**唯一规范构建命令**。本计划各任务步骤里出现的 `mvn -B -q install -pl app,verify` 一律是这条的简写，实跑时必须展开成下面整段（含 cd java/、unix PATH、四参）：

```bash
cd "D:/00.personal/4.code/excel2mysql/java"
export JAVA_HOME="C:/Program Files/Java/jdk1.8.0_202"
export PATH="/c/Program Files/Java/jdk1.8.0_202/bin:$PATH"
GC=$(git rev-parse --short HEAD); GCF=$(git rev-parse HEAD); GB=$(git rev-parse --abbrev-ref HEAD)
GD=false; if [ -n "$(git status --porcelain -uno)" ]; then GD=true; fi
# 编译 app＋verify（增量；clean 被 IDE 占用时降级 install 并显式说明）
mvn -B -q install -pl app,verify -Dgit.commit="$GC" -Dgit.commitFull="$GCF" -Dgit.branch="$GB" -Dgit.dirty="$GD"
# 收集 verify 运行时依赖（差分器 classpath 需要）
mvn -B -q -pl verify dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime
```

差分器与自检的相对路径（`app/target/...`、`verify/fixtures`）均以 `java/` 为 cwd，故跑完构建后**留在 java/ 目录**继续跑下面这些命令。

差分器（离线，无 DB）跑法，classpath 用 `;` 分隔（Windows）：

```bash
java -Dfile.encoding=UTF-8 -cp "app/target/classes;verify/target/classes;verify/target/lib/*" \
  com.excel2mysql.verify.MappingFileDiffer
java -Dfile.encoding=UTF-8 -cp "app/target/classes;verify/target/classes;verify/target/lib/*" \
  com.excel2mysql.verify.LayerGenDiffer
```

GUI 自检（原地跑，modalProgress=false）：

```bash
java -Dfile.encoding=UTF-8 -jar app/target/excel2mysql-5.1.0.jar --gui --selftest verify/fixtures
```

真库回归（需 testdb 凭据；Git Bash 载入 `.pt_env` 后映射到 E2M_*）：

```bash
set -a; . ./.pt_env; set +a
export E2M_HOST="${PT_HOST:-127.0.0.1}"; export E2M_PORT="${PT_PORT:-3306}"
export E2M_USER="${PT_USER:-root}"; export E2M_PASS="$PT_PASS"; export E2M_DB="${PT_DB:-testdb}"
java -Dfile.encoding=UTF-8 -cp "app/target/classes;verify/target/classes;verify/target/lib/*" \
  com.excel2mysql.verify.LayerDbRegression
```

全闸门（交付前/改出包链路时）：实跑 `java/构建验证.bat`（十步），退出码 0。打包只在用户明说"打包"时做。

控制台 cp936 会把差分器打印的中文打花，但落盘文件与退出码不受影响；判绿看退出码与汇总行，必要时从盘上 Read 产物核对。

## File Structure

新增（产品代码，app 模块）：
- `java/app/src/main/java/com/excel2mysql/core/MappingFile.java` — 映射 CSV 读写＋编码自适应＋缺列校验。一个职责：把映射文件变成内存模型并写回字段映射。
- `java/app/src/main/java/com/excel2mysql/core/LayerGen.java` — 三层生成引擎＋源结构读取＋入库执行。离线生成（genLayer）与连库 I/O（readSource/execute）在同一文件但方法分离，便于离线对撞。
- `java/app/src/main/java/com/excel2mysql/gui/LayerPage.java` — 第 4 页签 JPanel＋自有 Host 接口＋字段映射例外窗。

新增（测试，verify 模块，不进交付包）：
- `java/verify/src/main/java/com/excel2mysql/verify/MappingFileDiffer.java`
- `java/verify/src/main/java/com/excel2mysql/verify/LayerGenDiffer.java`
- `java/verify/src/main/java/com/excel2mysql/verify/LayerDbRegression.java`

修改：
- `java/app/src/main/java/com/excel2mysql/gui/MainFrame.java` — 挂第四页、RES_LAYER、实现 LayerPage.Host、syncRunStates/refreshDbLabels 覆盖。
- `java/app/src/main/java/com/excel2mysql/GuiMain.java` — selftest 段追加第四页断言。
- `java/构建验证.bat` — 追加 MappingFileDiffer/LayerGenDiffer 两步（真库回归与打包闸门按既有口径）。

不改：`core/Ddl.java`（buildDdl 原样复用）、`core/Infer.java`（Column 原样复用）、`core/DbClient.java`（原样复用）、Python 两端、Java CLI。

复用签名（已逐字核对，执行时以此为准）：
- `Config`：public 字段 `host/port(int)/user/password/database/source`；`static Config discover(List<String>)`、`static Config defaults()`、`boolean hasDatabase()`、`String jdbcUrl(int connectTimeoutMs)`。环境变量 E2M_HOST/PORT/USER/PASS/DB。
- `Infer.Column`：无参构造；public 字段 `field/comment/type/String`、`maxLen/long`、`typeNote/String`、`varcharN/Integer`、`primaryKey/boolean`、`nullable/Boolean`、`autoPk/boolean`；`Column copied()`。
- `Ddl.buildDdl(String tableName, String tableComment, List<Infer.Column> columns, boolean dropIfExists)` → `CREATE TABLE \`tableName\` (...) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='...'`；dropIfExists=true 时前缀 `DROP TABLE IF EXISTS \`tableName\`;\n`。tableName 原样嵌进反引号，故跨库用 `USE` 头而非改它。
- `DbClient(Config cfg)` / `DbClient(Config cfg, int connectTimeoutMs)` throws SQLException；`Connection connection()`、`void createTable(String ddl)`（按 `;\n` 拆）、`long insertData(...)`、`List<String> listDatabases()`、`void close()`。
- `ImportParse.Sink`：`setTotal(int)`、`step(String)`、`advance(int,String)`、`log(String)`、`logError(String)`、`boolean cancelled()`；`ImportParse.LOG_ONLY` 只打日志。
- `MainFrame`：`implements SqlPage.Host`；常量 RES_EXCEL/RES_SQL/RES_RUN/RES_CONN＋`RES_LABEL` Map；`private void runTask(String title, Function<ImportParse.Sink,Object> work, Consumer<Object> onUi, LogPanel logView, String[] resources)`；`public void runSqlTask(...)` 委托 runTask 指 sqlPage.logPanel()；`public String exportRoot()`→`programDir()`；`static String programDir()`；`private void syncRunStates()`；`public void refreshDbLabels()`；页签构造在 262-269 行，`tabs.addChangeListener(e -> refreshDbLabels())`。
- `SqlPage.Host`：`currentConfig()/connected()/owner()/inform(String)/inform2(String,String)/informError(String,String)/runSqlTask(...)/exportRoot()/warn(String)`；`SqlPage.logPanel()`。

---

### Task 1: MappingFile 映射 CSV 读写

**Files:**
- Create: `java/app/src/main/java/com/excel2mysql/core/MappingFile.java`
- Test: `java/verify/src/main/java/com/excel2mysql/verify/MappingFileDiffer.java`

**Interfaces:**
- Consumes: 无（纯离线，只用 JDK）。
- Produces:
  - `MappingFile.TableMap`（public 字段：`system, subDomain, tableCn, tableEn, fieldCount, pkField, domain, domainCode, domainSub, domainTarget, objDims, theme, basis, status, note, odsPhysical`，全 String）。
  - `MappingFile.FieldMap`（public 字段：`srcTable, srcField, srcComment, dstTable, dstField, dstComment, dataElemName, dataElemId, transformRule, status` 全 String；`governance` boolean）。
  - `List<TableMap> MappingFile.tables()`
  - `static MappingFile MappingFile.loadTableCsv(String path) throws IOException`
  - `static List<FieldMap> MappingFile.readFieldCsv(String path) throws IOException`
  - `static void MappingFile.writeFieldCsv(String path, List<FieldMap> rows) throws IOException`
  - `static String MappingFile.readTextAuto(File f) throws IOException`（UTF-8-BOM/UTF-8/GBK 自适应）
  - `static List<String> MappingFile.parseCsvLine(String line)`（RFC4180 引号转义）
  - `static String MappingFile.csvCell(String s)`（含逗号/引号/换行才加引号）

- [ ] **Step 1: 写失败测试 MappingFileDiffer**

创建 `java/verify/src/main/java/com/excel2mysql/verify/MappingFileDiffer.java`：

```java
package com.excel2mysql.verify;

import com.excel2mysql.core.MappingFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** 离线撞映射文件读写：15 列解析、缺列报错、BOM/GBK 编码、字段映射往返。锁可数副作用。 */
public final class MappingFileDiffer {

    private static final String HEADER =
            "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
            + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注";

    public static void main(String[] args) throws IOException {
        int bad = 0;
        List<String> shown = new ArrayList<String>();

        // 1) 正常 15 列＋可选 ods物理表名 解析
        File good = temp("good.csv", "UTF-8", false,
                HEADER + ",ods物理表名\n"
                + "采购供应,油料,油料账,t_oil,3,id,采购供应,proc,油料保障,dwd_proc_oil,"
                + "dim_material,rc,关键词,待核,,ods_cg_oil\n");
        MappingFile m = MappingFile.loadTableCsv(good.getPath());
        if (m.tables().size() != 1) { bad++; shown.add("解析行数 期望 1 实际 " + m.tables().size()); }
        MappingFile.TableMap t = m.tables().get(0);
        if (!"t_oil".equals(t.tableEn) || !"proc".equals(t.domainCode)
                || !"dwd_proc_oil".equals(t.domainTarget) || !"dim_material".equals(t.objDims)
                || !"ods_cg_oil".equals(t.odsPhysical)) {
            bad++; shown.add("列值错位：" + t.tableEn + "/" + t.domainCode + "/" + t.odsPhysical);
        }

        // 2) 缺列必须报错且指出缺哪列：给全 系统/子领域 两列、只缺 表中文名，
        //    验证器要跳过在位的列、点出第一个真正缺的必填列（不能只报最前面那列）
        File miss = temp("miss.csv", "UTF-8", false,
                "系统,子领域,表英文名\n采购供应,油料,t_oil\n");
        try {
            MappingFile.loadTableCsv(miss.getPath());
            bad++; shown.add("缺列却没报错");
        } catch (IOException e) {
            if (!e.getMessage().contains("表中文名")) {
                bad++; shown.add("缺列报错没指出缺哪列：" + e.getMessage());
            }
        }

        // 3) UTF-8-BOM 与 GBK 都要读得出中文
        File bom = temp("bom.csv", "UTF-8", true, HEADER + "\n采购供应,油料,油料账,t_oil,3,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n");
        if (!"油料账".equals(MappingFile.loadTableCsv(bom.getPath()).tables().get(0).tableCn)) {
            bad++; shown.add("UTF-8-BOM 中文读错");
        }
        File gbk = temp("gbk.csv", "GBK", false, HEADER + "\n采购供应,油料,油料账,t_oil,3,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n");
        if (!"油料账".equals(MappingFile.loadTableCsv(gbk.getPath()).tables().get(0).tableCn)) {
            bad++; shown.add("GBK 中文读错");
        }

        // 4) 空文件报错
        File empty = temp("empty.csv", "UTF-8", false, "");
        try {
            MappingFile.loadTableCsv(empty.getPath());
            bad++; shown.add("空文件却没报错");
        } catch (IOException e) { /* 期望 */ }

        // 5) 字段映射写→读往返逐字段一致（含逗号/引号转义）
        List<MappingFile.FieldMap> fm = new ArrayList<MappingFile.FieldMap>();
        MappingFile.FieldMap f = new MappingFile.FieldMap();
        f.srcTable = "t_oil"; f.srcField = "name"; f.srcComment = "名称,含逗号";
        f.dstTable = "dwd_proc_oil"; f.dstField = "name"; f.dstComment = "名称\"引号\"";
        f.dataElemName = "物资名称"; f.dataElemId = "待赋码"; f.transformRule = "同名直映";
        f.governance = false; f.status = "待核";
        fm.add(f);
        File fmFile = temp("fm.csv", "UTF-8", false, null);
        MappingFile.writeFieldCsv(fmFile.getPath(), fm);
        List<MappingFile.FieldMap> back = MappingFile.readFieldCsv(fmFile.getPath());
        if (back.size() != 1) { bad++; shown.add("字段映射往返行数 期望 1 实际 " + back.size()); }
        else {
            MappingFile.FieldMap b = back.get(0);
            if (!eq(f.srcComment, b.srcComment) || !eq(f.dstComment, b.dstComment)
                    || !eq(f.dataElemId, b.dataElemId) || f.governance != b.governance) {
                bad++; shown.add("字段映射往返不一致：" + b.srcComment + " / " + b.dstComment);
            }
        }

        System.out.println("- [INFO] MAP-DIFF | 不一致 " + bad + " 处");
        for (String s : shown) { System.out.println("     " + s); }
        if (bad > 0) { System.out.println("x [ERROR] MAP-DIFF | 映射文件读写不等价"); System.exit(1); }
        System.out.println("+ [OK] MAP-DIFF | 15 列解析、缺列报错、BOM/GBK、字段映射往返逐条一致");
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static File temp(String name, String enc, boolean bom, String body) throws IOException {
        File f = File.createTempFile("mapdiff-", "-" + name);
        f.deleteOnExit();
        if (body == null) { return f; }
        Writer w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName(enc));
        try {
            if (bom) { w.write('﻿'); }
            w.write(body);
        } finally { w.close(); }
        return f;
    }
}
```

- [ ] **Step 2: 跑测试确认它失败（编译失败＝红）**

```bash
export JAVA_HOME="C:/Program Files/Java/jdk1.8.0_202"; export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -q install -pl app,verify
```
Expected: 编译失败，`MappingFile` 不存在（cannot find symbol）。这是预期的红——产品类还没写。

- [ ] **Step 3: 写 MappingFile 实现**

创建 `java/app/src/main/java/com/excel2mysql/core/MappingFile.java`：

```java
package com.excel2mysql.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 层间映射 CSV 的读写。表级 15 列（＋可选 ods物理表名），字段级 11 列。纯离线。 */
public final class MappingFile {

    public static final class TableMap {
        public String system, subDomain, tableCn, tableEn, fieldCount, pkField,
                domain, domainCode, domainSub, domainTarget, objDims, theme,
                basis, status, note, odsPhysical;
    }

    public static final class FieldMap {
        public String srcTable, srcField, srcComment, dstTable, dstField, dstComment,
                dataElemName, dataElemId, transformRule, status;
        public boolean governance;
    }

    private static final String[] TABLE_COLS = {
            "系统", "子领域", "表中文名", "表英文名", "字段数", "主键字段", "目标领域",
            "领域码", "领域子类", "领域目标表", "涉及对象维度", "主题归属", "分类依据",
            "映射状态", "备注"
    };
    private static final String[] FIELD_COLS = {
            "源表", "源字段", "源注释", "目标表", "目标字段", "目标注释", "数据元名称",
            "数据元标识符", "转换规则", "是否治理字段", "映射状态"
    };

    private final List<TableMap> tables;

    private MappingFile(List<TableMap> tables) { this.tables = tables; }

    public List<TableMap> tables() { return tables; }

    public static MappingFile loadTableCsv(String path) throws IOException {
        List<String> lines = readLines(path);
        if (lines.isEmpty()) { throw new IOException("映射文件为空：" + path); }
        List<String> header = parseCsvLine(lines.get(0));
        for (String need : TABLE_COLS) {
            if (!header.contains(need)) { throw new IOException("映射文件缺列：" + need); }
        }
        int[] idx = new int[TABLE_COLS.length];
        for (int i = 0; i < TABLE_COLS.length; i++) { idx[i] = header.indexOf(TABLE_COLS[i]); }
        int physIdx = header.indexOf("ods物理表名");
        List<TableMap> out = new ArrayList<TableMap>();
        for (int r = 1; r < lines.size(); r++) {
            if (lines.get(r).trim().isEmpty()) { continue; }
            List<String> cells = parseCsvLine(lines.get(r));
            TableMap t = new TableMap();
            t.system = at(cells, idx[0]); t.subDomain = at(cells, idx[1]);
            t.tableCn = at(cells, idx[2]); t.tableEn = at(cells, idx[3]);
            t.fieldCount = at(cells, idx[4]); t.pkField = at(cells, idx[5]);
            t.domain = at(cells, idx[6]); t.domainCode = at(cells, idx[7]);
            t.domainSub = at(cells, idx[8]); t.domainTarget = at(cells, idx[9]);
            t.objDims = at(cells, idx[10]); t.theme = at(cells, idx[11]);
            t.basis = at(cells, idx[12]); t.status = at(cells, idx[13]);
            t.note = at(cells, idx[14]);
            t.odsPhysical = physIdx >= 0 ? at(cells, physIdx) : "";
            out.add(t);
        }
        return new MappingFile(out);
    }

    public static List<FieldMap> readFieldCsv(String path) throws IOException {
        List<String> lines = readLines(path);
        if (lines.isEmpty()) { throw new IOException("字段映射文件为空：" + path); }
        List<String> header = parseCsvLine(lines.get(0));
        for (String need : FIELD_COLS) {
            if (!header.contains(need)) { throw new IOException("字段映射文件缺列：" + need); }
        }
        int[] idx = new int[FIELD_COLS.length];
        for (int i = 0; i < FIELD_COLS.length; i++) { idx[i] = header.indexOf(FIELD_COLS[i]); }
        List<FieldMap> out = new ArrayList<FieldMap>();
        for (int r = 1; r < lines.size(); r++) {
            if (lines.get(r).trim().isEmpty()) { continue; }
            List<String> c = parseCsvLine(lines.get(r));
            FieldMap f = new FieldMap();
            f.srcTable = at(c, idx[0]); f.srcField = at(c, idx[1]); f.srcComment = at(c, idx[2]);
            f.dstTable = at(c, idx[3]); f.dstField = at(c, idx[4]); f.dstComment = at(c, idx[5]);
            f.dataElemName = at(c, idx[6]); f.dataElemId = at(c, idx[7]);
            f.transformRule = at(c, idx[8]);
            f.governance = "是".equals(at(c, idx[9]));
            f.status = at(c, idx[10]);
            out.add(f);
        }
        return out;
    }

    public static void writeFieldCsv(String path, List<FieldMap> rows) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8);
        try {
            w.write('﻿');
            w.write(join(FIELD_COLS)); w.write("\r\n");
            for (FieldMap f : rows) {
                String[] cells = {
                        f.srcTable, f.srcField, f.srcComment, f.dstTable, f.dstField,
                        f.dstComment, f.dataElemName, f.dataElemId, f.transformRule,
                        f.governance ? "是" : "否", f.status
                };
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) { sb.append(','); }
                    sb.append(csvCell(cells[i]));
                }
                w.write(sb.toString()); w.write("\r\n");
            }
        } finally { w.close(); }
    }

    /** UTF-8-BOM → UTF-8（严格）→ GBK 兜底。BOM 剥掉再解码。 */
    public static String readTextAuto(File f) throws IOException {
        byte[] bytes = readAll(f);
        int off = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        String body = new String(bytes, off, bytes.length - off, StandardCharsets.UTF_8);
        if (off == 3) { return body; }
        java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, off, bytes.length - off, Charset.forName("GBK"));
        }
    }

    public static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else { quoted = false; }
                } else { cur.append(ch); }
            } else if (ch == '"') { quoted = true; }
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else { cur.append(ch); }
        }
        out.add(cur.toString());
        return out;
    }

    public static String csvCell(String s) {
        String v = s == null ? "" : s;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static List<String> readLines(String path) throws IOException {
        String text = readTextAuto(new File(path));
        List<String> lines = new ArrayList<String>();
        for (String l : text.split("\r\n|\n|\r", -1)) { lines.add(l); }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) { lines.remove(lines.size() - 1); }
        return lines;
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); }
        } finally { in.close(); }
        return bos.toByteArray();
    }

    private static String at(List<String> cells, int i) {
        return i >= 0 && i < cells.size() ? cells.get(i) : "";
    }

    private static String join(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) { sb.append(','); }
            sb.append(csvCell(cols[i]));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -B -q install -pl app,verify
mvn -B -q -pl verify dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime
java -Dfile.encoding=UTF-8 -cp "app/target/classes;verify/target/classes;verify/target/lib/*" com.excel2mysql.verify.MappingFileDiffer
echo "exit=$?"
```
Expected: `+ [OK] MAP-DIFF | ...`，exit=0。

- [ ] **Step 5: 变异抽查**

把 `MappingFile.loadTableCsv` 的缺列校验注释掉（`// if (!header.contains(need))...`），重编译跑 MappingFileDiffer，确认报 `x [ERROR] MAP-DIFF | 映射文件读写不等价` 且 shown 里有"缺列却没报错"（可读 FAIL，不掀整轮）。用 Edit 逐字节还原，`md5sum` 比对还原前后一致，重跑确认 exit=0。

- [ ] **Step 6: Commit**

```bash
git add java/app/src/main/java/com/excel2mysql/core/MappingFile.java \
        java/verify/src/main/java/com/excel2mysql/verify/MappingFileDiffer.java
git commit -m "feat(core): 新增层间映射 CSV 读写，15 列校验与 BOM/GBK 自适应"
```

---

### Task 2: LayerGen 离线生成引擎（DDL＋转换 SQL＋字段映射）

**Files:**
- Create: `java/app/src/main/java/com/excel2mysql/core/LayerGen.java`
- Test: `java/verify/src/main/java/com/excel2mysql/verify/LayerGenDiffer.java`

**Interfaces:**
- Consumes: `MappingFile`（Task 1）、`Infer.Column`、`Ddl.buildDdl`。
- Produces:
  - `enum LayerGen.Layer { OBJ, DOM, ADS }`
  - `LayerGen.SourceTable`（构造 `SourceTable(String name, String comment, List<Infer.Column> columns)`；public 字段同名）。
  - `LayerGen.GenOptions`（public 字段 `sourceDb="ods", objDb="obj", domDb="dom", adsDb="ads", writeMode=false, batchId="@src_batch_id"`）。
  - `LayerGen.LayerOutput`（public 字段 `Layer layer; String ddlSql=""; String transformSql=""; List<MappingFile.FieldMap> fieldMapping=new ArrayList<>(); int tableCount; List<String> warnings=new ArrayList<>(); List<String> unbound=new ArrayList<>();`）。
  - `static LayerOutput LayerGen.genLayer(Layer layer, MappingFile m, List<SourceTable> src, GenOptions o)`
  - `static String LayerGen.qualify(String db, String table)` → `` `db`.`table` ``
  - 常量 `GOV_OBJ_DOM`（表 5-3）、`GOV_ADS`（表 6-4）、`DIM_PK`（表 5-1）。
  - Task 3 追加：`static List<SourceTable> readSource(DbClient db, String sourceDb)`、`WriteResult execute(LayerOutput, String targetDb, DbClient, ImportParse.Sink)`。

- [ ] **Step 1: 写失败测试 LayerGenDiffer**

创建 `java/verify/src/main/java/com/excel2mysql/verify/LayerGenDiffer.java`：

```java
package com.excel2mysql.verify;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 离线撞三层生成：DDL 口径、治理字段齐备、字段映射 100% 覆盖、转换 SQL 形状、依赖与未绑定。 */
public final class LayerGenDiffer {

    public static void main(String[] args) throws IOException {
        int bad = 0;
        List<String> shown = new ArrayList<String>();

        MappingFile m = mapping();
        List<LayerGen.SourceTable> src = source();
        LayerGen.GenOptions o = new LayerGen.GenOptions();

        // dom：t_oil→dwd_proc_oil、t_staff→dwd_comp_tech 两张领域目标表，各自同名直映＋治理字段
        LayerGen.LayerOutput dom = LayerGen.genLayer(LayerGen.Layer.DOM, m, src, o);
        if (dom.tableCount != 2) { bad++; shown.add("dom 表数 期望 2 实际 " + dom.tableCount); }
        if (!dom.ddlSql.contains("USE `dom`;") || !dom.ddlSql.contains("CREATE TABLE `dwd_proc_oil`")
                || !dom.ddlSql.contains("ENGINE=InnoDB") || !dom.ddlSql.contains("utf8mb4_general_ci")) {
            bad++; shown.add("dom DDL 口径不符");
        }
        for (String g : LayerGen.GOV_OBJ_DOM) {
            if (!dom.ddlSql.contains("`" + g + "`")) { bad++; shown.add("dom 缺治理字段 " + g); }
        }
        if (!dom.transformSql.contains("INSERT INTO `dwd_proc_oil`")
                || !dom.transformSql.contains("FROM `ods`.`t_oil`")) {
            bad++; shown.add("dom 转换 SQL 形状不符");
        }
        // 字段映射 100% 覆盖：DDL 里每个目标字段都要有一条映射
        int domCols = countBacktickedCols(dom.ddlSql);
        if (dom.fieldMapping.size() < domCols) {
            bad++; shown.add("dom 字段映射覆盖不足 映射 " + dom.fieldMapping.size() + " < 列 " + domCols);
        }

        // obj：t_oil 命中 dim_material
        LayerGen.LayerOutput obj = LayerGen.genLayer(LayerGen.Layer.OBJ, m, src, o);
        if (!obj.ddlSql.contains("CREATE TABLE `dim_material`")
                || !obj.ddlSql.contains("`material_id`") || !obj.ddlSql.contains("`entity_id`")
                || !obj.ddlSql.contains("`src_entity_id`")) {
            bad++; shown.add("obj dim_material 主键/归一字段不齐");
        }
        if (!obj.transformSql.contains("UUID()") || !obj.transformSql.contains("UNION ALL")) {
            // 单来源时允许无 UNION ALL，但 UUID() 必须在
            if (!obj.transformSql.contains("UUID()")) { bad++; shown.add("obj 缺 entity_id=UUID()"); }
        }

        // ads：从 obj＋dom 出，模板聚合＋表 6-4 字段
        LayerGen.LayerOutput ads = LayerGen.genLayer(LayerGen.Layer.ADS, m, src, o);
        if (!ads.ddlSql.contains("USE `ads`;")) { bad++; shown.add("ads 目标库不对"); }
        for (String g : LayerGen.GOV_ADS) {
            if (!ads.ddlSql.contains("`" + g + "`")) { bad++; shown.add("ads 缺通用字段 " + g); }
        }
        if (!ads.transformSql.contains("GROUP BY") || !ads.transformSql.contains("COUNT(*)")) {
            bad++; shown.add("ads 缺模板聚合 GROUP BY/COUNT(*)");
        }

        // 未绑定：映射里的表在源结构里找不到 → 记 unbound，不掀整轮
        List<LayerGen.SourceTable> partial = new ArrayList<LayerGen.SourceTable>();
        partial.add(src.get(0)); // 只给 t_oil，t_staff 缺
        LayerGen.LayerOutput dom2 = LayerGen.genLayer(LayerGen.Layer.DOM, m, partial, o);
        if (dom2.unbound.isEmpty()) { bad++; shown.add("缺源表却没记 unbound"); }

        System.out.println("- [INFO] LAYER-DIFF | 不一致 " + bad + " 处");
        for (String s : shown) { System.out.println("     " + s); }
        if (bad > 0) { System.out.println("x [ERROR] LAYER-DIFF | 三层生成与口径不符"); System.exit(1); }
        System.out.println("+ [OK] LAYER-DIFF | DDL 口径、治理字段、字段映射覆盖、转换 SQL、未绑定逐条一致");
    }

    private static int countBacktickedCols(String ddl) {
        // 数 CREATE TABLE 体里以 "  `字段`" 开头的行（排除 PRIMARY KEY）
        int n = 0;
        for (String line : ddl.split("\n")) {
            String t = line.trim();
            if (t.startsWith("`") && !t.startsWith("PRIMARY")) { n++; }
        }
        return n;
    }

    private static MappingFile mapping() throws IOException {
        String header = "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n";
        String body =
                "采购供应,油料,油料账,t_oil,2,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n"
                + "综合,人事,人员表,t_staff,2,id,综合,comp,科技管理,dwd_comp_tech,dim_person,rc,关键词,待核,\n";
        File f = File.createTempFile("layerdiff-", ".csv");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write('﻿'); w.write(header); w.write(body); } finally { w.close(); }
        return MappingFile.loadTableCsv(f.getPath());
    }

    private static List<LayerGen.SourceTable> source() {
        List<LayerGen.SourceTable> s = new ArrayList<LayerGen.SourceTable>();
        s.add(new LayerGen.SourceTable("t_oil", "油料账", cols("id", "name")));
        s.add(new LayerGen.SourceTable("t_staff", "人员表", cols("id", "person_name")));
        return s;
    }

    private static List<Infer.Column> cols(String... names) {
        List<Infer.Column> out = new ArrayList<Infer.Column>();
        for (String n : names) {
            Infer.Column c = new Infer.Column();
            c.field = n; c.type = "VARCHAR(255)"; c.comment = n; c.maxLen = 255;
            c.primaryKey = "id".equals(n); c.nullable = Boolean.TRUE;
            out.add(c);
        }
        return out;
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -B -q install -pl app,verify
```
Expected: 编译失败，`LayerGen` 不存在。预期红。

- [ ] **Step 3: 写 LayerGen 离线实现**

创建 `java/app/src/main/java/com/excel2mysql/core/LayerGen.java`（本任务只写离线部分；readSource/execute 在 Task 3 追加）：

```java
package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按映射＋源结构产 obj/dom/ads 三层 DDL、转换 SQL、字段映射。离线逻辑与连库 I/O 分离。 */
public final class LayerGen {

    private LayerGen() { }

    public enum Layer { OBJ, DOM, ADS }

    public static final class SourceTable {
        public final String name;
        public final String comment;
        public final List<Infer.Column> columns;
        public SourceTable(String name, String comment, List<Infer.Column> columns) {
            this.name = name; this.comment = comment; this.columns = columns;
        }
    }

    public static final class GenOptions {
        public String sourceDb = "ods";
        public String objDb = "obj";
        public String domDb = "dom";
        public String adsDb = "ads";
        public boolean writeMode = false;
        public String batchId = "@src_batch_id";
    }

    public static final class LayerOutput {
        public Layer layer;
        public String ddlSql = "";
        public String transformSql = "";
        public List<MappingFile.FieldMap> fieldMapping = new ArrayList<MappingFile.FieldMap>();
        public int tableCount;
        public List<String> warnings = new ArrayList<String>();
        public List<String> unbound = new ArrayList<String>();
    }

    /** 表 5-3：obj/dom 通用治理字段。 */
    public static final String[] GOV_OBJ_DOM = {
            "entity_id", "src_entity_id", "std_code", "src_code", "tags", "data_level",
            "mask_policy", "quality_flag", "quality_msg", "de_map_id", "src_batch_id",
            "merge_rule_id", "update_time", "valid_flag"
    };
    /** 表 6-4：ads 通用字段。 */
    public static final String[] GOV_ADS = {
            "stat_date", "stat_grain", "dim_key_json", "idx_code", "idx_value", "idx_unit",
            "calc_model_id", "caliber_ver", "src_row_cnt", "excluded_row_cnt", "etl_batch_id",
            "quality_flag"
    };
    /** 表 5-1：十类维度建议主键。 */
    public static final Map<String, String> DIM_PK = new LinkedHashMap<String, String>();
    static {
        DIM_PK.put("dim_org", "org_id");
        DIM_PK.put("dim_material", "material_id");
        DIM_PK.put("dim_fund_account", "account_id");
        DIM_PK.put("dim_facility", "facility_id");
        DIM_PK.put("dim_person", "person_id");
        DIM_PK.put("dim_supply_standard", "standard_id");
        DIM_PK.put("dim_policy", "policy_id");
        DIM_PK.put("dim_geo", "geo_id");
        DIM_PK.put("dim_code_info", "code_id");
        DIM_PK.put("dim_code_it", "std_id");
    }

    public static String qualify(String db, String table) {
        return "`" + db + "`.`" + table + "`";
    }

    public static LayerOutput genLayer(Layer layer, MappingFile m, List<SourceTable> src, GenOptions o) {
        switch (layer) {
            case OBJ: return genObj(m, src, o);
            case DOM: return genDom(m, src, o);
            case ADS: return genAds(m, src, o);
            default: throw new IllegalArgumentException("未知层：" + layer);
        }
    }

    private static Map<String, SourceTable> byName(List<SourceTable> src) {
        Map<String, SourceTable> map = new LinkedHashMap<String, SourceTable>();
        for (SourceTable t : src) { map.put(t.name, t); }
        return map;
    }

    private static SourceTable bind(MappingFile.TableMap t, Map<String, SourceTable> map) {
        if (t.odsPhysical != null && !t.odsPhysical.isEmpty() && map.containsKey(t.odsPhysical)) {
            return map.get(t.odsPhysical);
        }
        return map.get(t.tableEn);
    }

    // ---- dom：按领域目标表整编，同名直映＋治理字段 ----
    private static LayerOutput genDom(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.DOM;
        Map<String, SourceTable> map = byName(src);
        Map<String, List<MappingFile.TableMap>> groups = new LinkedHashMap<String, List<MappingFile.TableMap>>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.domainTarget == null || t.domainTarget.isEmpty()) { continue; }
            SourceTable s = bind(t, map);
            if (s == null) { out.unbound.add(t.tableEn); continue; }
            List<MappingFile.TableMap> g = groups.get(t.domainTarget);
            if (g == null) { g = new ArrayList<MappingFile.TableMap>(); groups.put(t.domainTarget, g); }
            g.add(t);
        }
        StringBuilder ddl = new StringBuilder("USE `" + o.domDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.domDb + "`;\n\n");
        for (Map.Entry<String, List<MappingFile.TableMap>> e : groups.entrySet()) {
            String target = e.getKey();
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            Set<String> seen = new LinkedHashSet<String>();
            String pk = null;
            for (MappingFile.TableMap t : e.getValue()) {
                SourceTable s = bind(t, map);
                for (Infer.Column c : s.columns) {
                    if (seen.add(c.field)) {
                        cols.add(copyCol(c, c.primaryKey));
                        if (c.primaryKey && pk == null) { pk = c.field; }
                    }
                }
            }
            addGovernance(cols, GOV_OBJ_DOM);
            ddl.append(Ddl.buildDdl(target, target, cols, true)).append("\n\n");
            for (MappingFile.TableMap t : e.getValue()) {
                SourceTable s = bind(t, map);
                dml.append(domInsert(target, s, cols, o)).append("\n\n");
                addFieldMaps(out, s, target, cols, o);
            }
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        return out;
    }

    private static String domInsert(String target, SourceTable s, List<Infer.Column> cols, GenOptions o) {
        StringBuilder select = new StringBuilder();
        Set<String> srcFields = new LinkedHashSet<String>();
        for (Infer.Column c : s.columns) { srcFields.add(c.field); }
        for (Infer.Column c : cols) {
            if (select.length() > 0) { select.append(", "); }
            if (srcFields.contains(c.field)) { select.append("`").append(c.field).append("`"); }
            else { select.append(govExpr(c.field, o)); }
        }
        return "INSERT INTO `" + target + "` SELECT " + select + " FROM " + qualify(o.sourceDb, s.name) + ";";
    }

    // ---- obj：按涉及对象维度抽实体，十类按实际命中 ----
    private static LayerOutput genObj(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.OBJ;
        Map<String, SourceTable> map = byName(src);
        Map<String, List<SourceTable>> dimSources = new LinkedHashMap<String, List<SourceTable>>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.objDims == null || t.objDims.isEmpty()) { continue; }
            SourceTable s = bind(t, map);
            if (s == null) { out.unbound.add(t.tableEn); continue; }
            for (String dim : t.objDims.split("[;；,，]")) {
                String d = dim.trim();
                if (!DIM_PK.containsKey(d)) { continue; }
                List<SourceTable> g = dimSources.get(d);
                if (g == null) { g = new ArrayList<SourceTable>(); dimSources.put(d, g); }
                if (!g.contains(s)) { g.add(s); }
            }
        }
        StringBuilder ddl = new StringBuilder("USE `" + o.objDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.objDb + "`;\n\n");
        for (Map.Entry<String, List<SourceTable>> e : dimSources.entrySet()) {
            String dim = e.getKey();
            String pk = DIM_PK.get(dim);
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            cols.add(col(pk, "VARCHAR(64)", dim + " 主键", true));
            Set<String> seen = new LinkedHashSet<String>();
            seen.add(pk);
            for (SourceTable s : e.getValue()) {
                for (Infer.Column c : s.columns) {
                    if (!c.primaryKey && seen.add(c.field)) { cols.add(copyCol(c, false)); }
                }
            }
            addGovernance(cols, GOV_OBJ_DOM);
            ddl.append(Ddl.buildDdl(dim, dim, cols, true)).append("\n\n");
            List<String> branches = new ArrayList<String>();
            for (SourceTable s : e.getValue()) {
                branches.add(objSelect(dim, pk, s, cols, o));
                addFieldMaps(out, s, dim, cols, o);
            }
            dml.append("INSERT INTO `").append(dim).append("` ").append(join(branches, "\nUNION ALL\n")).append(";\n\n");
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        return out;
    }

    private static String objSelect(String dim, String pk, SourceTable s, List<Infer.Column> cols, GenOptions o) {
        String srcPk = firstPk(s);
        StringBuilder select = new StringBuilder("SELECT DISTINCT ");
        Set<String> srcFields = new LinkedHashSet<String>();
        for (Infer.Column c : s.columns) { srcFields.add(c.field); }
        boolean first = true;
        for (Infer.Column c : cols) {
            if (!first) { select.append(", "); }
            first = false;
            if (c.field.equals(pk)) { select.append("UUID() AS `").append(pk).append("`"); }
            else if (c.field.equals("src_entity_id")) { select.append("`").append(srcPk).append("` AS `src_entity_id`"); }
            else if (srcFields.contains(c.field)) { select.append("`").append(c.field).append("`"); }
            else { select.append(govExpr(c.field, o)); }
        }
        select.append(" FROM ").append(qualify(o.sourceDb, s.name));
        return select.toString();
    }

    // ---- ads：按主题归属在 obj＋dom 上产模板聚合 ----
    private static LayerOutput genAds(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.ADS;
        Map<String, List<MappingFile.TableMap>> groups = new LinkedHashMap<String, List<MappingFile.TableMap>>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.theme == null || t.theme.isEmpty()) { continue; }
            List<MappingFile.TableMap> g = groups.get(t.theme);
            if (g == null) { g = new ArrayList<MappingFile.TableMap>(); groups.put(t.theme, g); }
            g.add(t);
        }
        StringBuilder ddl = new StringBuilder("USE `" + o.adsDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.adsDb + "`;\n\n");
        for (Map.Entry<String, List<MappingFile.TableMap>> e : groups.entrySet()) {
            String theme = e.getKey();
            String target = "ads_" + theme;
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            cols.addAll(governanceCols(GOV_ADS));
            ddl.append(Ddl.buildDdl(target, "主题 " + theme + "（待核）", cols, true)).append("\n\n");
            String domTable = e.getValue().get(0).domainTarget;
            dml.append("-- 主题 ").append(theme).append(" 聚合模板，指标口径待业务方核改（条款 188）\n")
               .append("INSERT INTO `").append(target).append("` (`stat_date`, `dim_key_json`, `idx_code`, `src_row_cnt`, `excluded_row_cnt`, `etl_batch_id`, `quality_flag`) ")
               .append("SELECT CURDATE() AS `stat_date`, '{}' AS `dim_key_json`, ")
               .append("'").append(theme).append("' AS `idx_code`, COUNT(*) AS `src_row_cnt`, ")
               .append("0 AS `excluded_row_cnt`, '").append(o.batchId).append("' AS `etl_batch_id`, '待核' AS `quality_flag` ")
               .append("FROM ").append(qualify(o.domDb, domTable == null ? "dwd_unknown" : domTable)).append(" ")
               .append("GROUP BY CURDATE();\n\n");
            for (Infer.Column c : cols) {
                MappingFile.FieldMap f = new MappingFile.FieldMap();
                f.srcTable = domTable == null ? "" : domTable; f.srcField = "";
                f.srcComment = "模板聚合"; f.dstTable = target; f.dstField = c.field;
                f.dstComment = c.comment; f.dataElemName = ""; f.dataElemId = "待赋码";
                f.transformRule = "模板聚合（待核）"; f.governance = true; f.status = "待核";
                out.fieldMapping.add(f);
            }
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        out.warnings.add("ads 指标口径为模板，须业务方逐条核改（条款 188 注）");
        return out;
    }

    // ---- 共用 helpers ----
    private static Infer.Column col(String field, String type, String comment, boolean pk) {
        Infer.Column c = new Infer.Column();
        c.field = field; c.type = type; c.comment = comment;
        c.maxLen = 255; c.primaryKey = pk; c.nullable = pk ? Boolean.FALSE : Boolean.TRUE;
        return c;
    }

    private static Infer.Column copyCol(Infer.Column c, boolean pk) {
        Infer.Column n = c.copied();
        n.primaryKey = pk;
        return n;
    }

    private static List<Infer.Column> governanceCols(String[] names) {
        List<Infer.Column> out = new ArrayList<Infer.Column>();
        for (String n : names) {
            String type = "update_time".equals(n) ? "DATETIME"
                    : "src_row_cnt".equals(n) || "excluded_row_cnt".equals(n) ? "BIGINT"
                    : "stat_date".equals(n) ? "DATE" : "VARCHAR(255)";
            out.add(col(n, type, "治理字段（表 5-3/6-4）", false));
        }
        return out;
    }

    /** 补治理字段，但源表已有同名列（如 update_time）时跳过，避免 CREATE TABLE 重名列。 */
    private static void addGovernance(List<Infer.Column> cols, String[] names) {
        Set<String> have = new LinkedHashSet<String>();
        for (Infer.Column c : cols) { have.add(c.field); }
        for (Infer.Column g : governanceCols(names)) {
            if (have.add(g.field)) { cols.add(g); }
        }
    }

    private static String govExpr(String field, GenOptions o) {
        if ("entity_id".equals(field)) { return "UUID() AS `entity_id`"; }
        if ("src_batch_id".equals(field) || "etl_batch_id".equals(field)) {
            return "'" + o.batchId + "' AS `" + field + "`";
        }
        if ("update_time".equals(field)) { return "NOW() AS `update_time`"; }
        if ("valid_flag".equals(field)) { return "'1' AS `valid_flag`"; }
        if ("src_row_cnt".equals(field)) { return "COUNT(*) AS `src_row_cnt`"; }
        if ("excluded_row_cnt".equals(field)) { return "0 AS `excluded_row_cnt`"; }
        return "NULL AS `" + field + "`";
    }

    private static String firstPk(SourceTable s) {
        for (Infer.Column c : s.columns) { if (c.primaryKey) { return c.field; } }
        return s.columns.isEmpty() ? "id" : s.columns.get(0).field;
    }

    private static void addFieldMaps(LayerOutput out, SourceTable s, String target,
                                     List<Infer.Column> cols, GenOptions o) {
        Set<String> srcFields = new LinkedHashSet<String>();
        Map<String, Infer.Column> srcByName = new LinkedHashMap<String, Infer.Column>();
        for (Infer.Column c : s.columns) { srcFields.add(c.field); srcByName.put(c.field, c); }
        for (Infer.Column c : cols) {
            MappingFile.FieldMap f = new MappingFile.FieldMap();
            f.dstTable = target; f.dstField = c.field; f.dstComment = c.comment;
            f.dataElemName = ""; f.dataElemId = "待赋码"; f.status = "待核";
            if (srcFields.contains(c.field)) {
                Infer.Column sc = srcByName.get(c.field);
                f.srcTable = s.name; f.srcField = c.field; f.srcComment = sc.comment;
                f.transformRule = "同名直映"; f.governance = false;
            } else {
                f.srcTable = s.name; f.srcField = ""; f.srcComment = "自动补治理字段";
                f.transformRule = govExpr(c.field, o); f.governance = true;
            }
            out.fieldMapping.add(f);
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) { sb.append(sep); }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -B -q install -pl app,verify
java -Dfile.encoding=UTF-8 -cp "app/target/classes;verify/target/classes;verify/target/lib/*" com.excel2mysql.verify.LayerGenDiffer
echo "exit=$?"
```
Expected: `+ [OK] LAYER-DIFF | ...`，exit=0。若 dom 字段覆盖断言红，核对 `countBacktickedCols` 与 `addFieldMaps` 是否每个目标列都产了一条映射。

- [ ] **Step 5: 变异抽查**

把 `genObj` 里 `cols.addAll(governanceCols(GOV_OBJ_DOM));` 删掉，重编译跑 LayerGenDiffer，确认报 `obj dim_material 主键/归一字段不齐`（entity_id 缺）这条可读 FAIL。Edit 还原＋md5 自证＋重跑 exit=0。再单独把 `addFieldMaps` 的治理字段分支 `out.fieldMapping.add(f)` 注释掉，确认覆盖率断言变红，还原。

- [ ] **Step 6: Commit**

```bash
git add java/app/src/main/java/com/excel2mysql/core/LayerGen.java \
        java/verify/src/main/java/com/excel2mysql/verify/LayerGenDiffer.java
git commit -m "feat(core): 三层离线生成引擎，DDL/转换 SQL/字段映射与治理字段口径"
```

---

### Task 3: LayerGen 源结构读取＋入库执行（真库回归）

**Files:**
- Modify: `java/app/src/main/java/com/excel2mysql/core/LayerGen.java`（追加 readSource/execute/WriteResult）
- Test: `java/verify/src/main/java/com/excel2mysql/verify/LayerDbRegression.java`

**Interfaces:**
- Consumes: `DbClient`、`Config`、`ImportParse.Sink`、Task 2 的 `LayerOutput`/`GenOptions`/`SourceTable`。
- Produces:
  - `static List<SourceTable> LayerGen.readSource(DbClient db, String sourceDb) throws SQLException`（DatabaseMetaData.getTables/getColumns，只读用户表）。
  - `LayerGen.WriteResult`（public 字段 `int tablesCreated; long rowsAffected; List<String> errors=new ArrayList<>();`）。
  - `static WriteResult LayerGen.execute(LayerOutput out, String targetDb, DbClient db, ImportParse.Sink sink) throws SQLException`（CREATE DATABASE IF NOT EXISTS→createTable(ddl)→执行 transform；单表失败隔离记 errors）。

- [ ] **Step 1: 写失败测试 LayerDbRegression**

创建 `java/verify/src/main/java/com/excel2mysql/verify/LayerDbRegression.java`：

```java
package com.excel2mysql.verify;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 真库撞入库模式：探针库建表灌数读回，跑前跑后库清单零残留。凭据走 E2M_*，不落文件。 */
public final class LayerDbRegression {

    private static final String SRC = "e2mlayerprobe_src";
    private static final String OBJ = "e2mlayerprobe_obj";
    private static final String DOM = "e2mlayerprobe_dom";
    private static final String ADS = "e2mlayerprobe_ads";

    public static void main(String[] args) throws Exception {
        Config cfg = Config.discover(new ArrayList<String>());
        if (!cfg.hasDatabase()) {
            System.out.println("x [ERROR] LAYER-DB | 没指定库（E2M_DB），拒绝建探针库");
            System.exit(2);
        }
        DbClient db = new DbClient(cfg);
        int bad = 0;
        Set<String> before = databases(db.connection());
        for (String p : new String[]{SRC, OBJ, DOM, ADS}) {
            if (before.contains(p)) {
                System.out.println("x [ERROR] LAYER-DB | 已有同名探针库，先清掉：" + p);
                System.exit(4);
            }
        }
        List<String> created = new ArrayList<String>();
        try {
            // 造源库与一张源表（两行）
            exec(db, "CREATE DATABASE `" + SRC + "` DEFAULT CHARSET utf8mb4");
            created.add(SRC);
            exec(db, "CREATE TABLE `" + SRC + "`.`t_oil` (`id` VARCHAR(64) NOT NULL, `name` VARCHAR(255), PRIMARY KEY (`id`)) ENGINE=InnoDB");
            exec(db, "INSERT INTO `" + SRC + "`.`t_oil` VALUES ('1','油料甲'),('2','油料乙')");

            List<LayerGen.SourceTable> src = LayerGen.readSource(db, SRC);
            if (src.size() != 1) {
                bad++; System.out.println("x [ERROR] READ-SOURCE | 源结构读取 期望 1 表 实际 " + src.size());
            } else if (src.get(0).columns.size() != 2) {
                bad++; System.out.println("x [ERROR] READ-SOURCE | t_oil 期望 2 列 实际 " + src.get(0).columns.size());
            }

            MappingFile m = mapping();
            LayerGen.GenOptions o = new LayerGen.GenOptions();
            o.sourceDb = SRC; o.objDb = OBJ; o.domDb = DOM; o.adsDb = ADS; o.writeMode = true;

            for (LayerGen.Layer layer : new LayerGen.Layer[]{LayerGen.Layer.DOM, LayerGen.Layer.OBJ, LayerGen.Layer.ADS}) {
                LayerGen.LayerOutput out = LayerGen.genLayer(layer, m, src, o);
                String targetDb = layer == LayerGen.Layer.DOM ? DOM : layer == LayerGen.Layer.OBJ ? OBJ : ADS;
                if (!created.contains(targetDb)) { created.add(targetDb); } // 先登记：execute 若中途抛，探针库也在清理账上
                LayerGen.WriteResult wr = LayerGen.execute(out, targetDb, db, ImportParse.LOG_ONLY);
                if (!wr.errors.isEmpty()) { bad++; System.out.println("x [ERROR] EXEC | " + layer + " " + wr.errors); }
                if (layer == LayerGen.Layer.DOM && (wr.tablesCreated != 1 || wr.rowsAffected != 2)) {
                    bad++; System.out.println("x [ERROR] WRITE-RESULT | dom 期望 建表1/影响2 实际 "
                            + wr.tablesCreated + "/" + wr.rowsAffected);
                }
            }

            long domRows = count(db, DOM, "dwd_proc_oil");
            if (domRows != 2) { bad++; System.out.println("x [ERROR] COUNT | dom dwd_proc_oil 期望 2 行 实际 " + domRows); }
            long objRows = count(db, OBJ, "dim_material");
            if (objRows != 2) { bad++; System.out.println("x [ERROR] COUNT | obj dim_material 期望 2 行 实际 " + objRows); }
            // entity_id 必须是 UUID 且非空、src_entity_id 保留来源
            String entityId = scalar(db, "SELECT `entity_id` FROM `" + OBJ + "`.`dim_material` LIMIT 1");
            if (entityId == null || entityId.length() < 32) { bad++; System.out.println("x [ERROR] UUID | obj entity_id 不像 UUID：" + entityId); }
            // ads 模板聚合须真的执行成功：ads_rc 恰好 1 行（锁 execute 不因 "--" 前缀把注释后的 INSERT 丢掉）
            long adsRows = count(db, ADS, "ads_rc");
            if (adsRows != 1) { bad++; System.out.println("x [ERROR] COUNT | ads ads_rc 期望 1 行 实际 " + adsRows); }
        } finally {
            for (String d : created) {
                try { exec(db, "DROP DATABASE IF EXISTS `" + d + "`"); } catch (Exception ignore) { }
            }
            db.close();
        }
        Set<String> after = databasesViaNew(cfg);
        if (!after.equals(before)) {
            bad++;
            Set<String> extra = new LinkedHashSet<String>(after); extra.removeAll(before);
            System.out.println("x [ERROR] SIDE-EFFECT | 跑完库清单变了 多：" + extra);
        }
        System.out.println("- [INFO] LAYER-DB | 不一致 " + bad + " 项");
        if (bad > 0) { System.out.println("x [ERROR] LAYER-DB | 真库入库回归未通过"); System.exit(1); }
        System.out.println("+ [OK] LAYER-DB | 探针库真建表真灌数读回一致，库清单零残留");
    }

    private static MappingFile mapping() throws IOException {
        String header = "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n";
        String body = "采购供应,油料,油料账,t_oil,2,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n";
        File f = File.createTempFile("layerdb-", ".csv"); f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write('﻿'); w.write(header); w.write(body); } finally { w.close(); }
        return MappingFile.loadTableCsv(f.getPath());
    }

    private static void exec(DbClient db, String sql) throws Exception {
        Statement st = db.connection().createStatement();
        try { st.execute(sql); db.connection().commit(); } finally { st.close(); }
    }

    private static long count(DbClient db, String schema, String table) throws Exception {
        Statement st = null;
        try {
            st = db.connection().createStatement();
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`");
            rs.next(); long n = rs.getLong(1); rs.close(); return n;
        } catch (java.sql.SQLException e) {
            // 表没建成时 executeQuery 会抛：这里报可读 FAIL 并返回 -1，让调用点计入 bad、
            // 流程继续走到跑后残留校验（否则异常掀掉 main，SIDE-EFFECT 那条最该跑的反而没跑）
            System.out.println("x [ERROR] COUNT | " + schema + "." + table + " 读行数失败：" + e.getMessage());
            return -1L;
        } finally { if (st != null) { st.close(); } }
    }

    private static String scalar(DbClient db, String sql) throws Exception {
        Statement st = null;
        try {
            st = db.connection().createStatement();
            ResultSet rs = st.executeQuery(sql);
            String s = rs.next() ? rs.getString(1) : null; rs.close(); return s;
        } catch (java.sql.SQLException e) {
            System.out.println("x [ERROR] SCALAR | 取值失败：" + e.getMessage());
            return null;
        } finally { if (st != null) { st.close(); } }
    }

    private static Set<String> databases(Connection c) throws Exception {
        Set<String> out = new LinkedHashSet<String>();
        Statement st = c.createStatement();
        try { ResultSet rs = st.executeQuery("SHOW DATABASES"); while (rs.next()) { out.add(rs.getString(1)); } rs.close(); }
        finally { st.close(); }
        return out;
    }

    private static Set<String> databasesViaNew(Config cfg) throws Exception {
        DbClient db = new DbClient(cfg);
        try { return databases(db.connection()); } finally { db.close(); }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -B -q install -pl app,verify
```
Expected: 编译失败，`LayerGen.readSource` / `execute` / `WriteResult` 不存在。预期红。

- [ ] **Step 3a: 先修 Task2 遗留的 DDL 语句终止符（承重）**

`Ddl.buildDdl` 返回的 CREATE TABLE **不以 `;` 结尾**（它只为 DROP 加了 `;`）。既有 Excel 页按"一表一次 createTable"用，从不把多表拼一起，所以没暴露；但分层建模把**整层多张表的 DDL 拼成一个字符串**（312 张表），下游两处都会坏：
- `execute()` 里 `splitStatements` 靠"行尾 `;`"切句——非末张表的 CREATE 没有结尾 `;`，会被并进下一条 DROP 的 `;` 一起 flush，得到 `CREATE TABLE t_k (...) COMMENT='..'\n\nDROP TABLE IF EXISTS t_{k+1}` 一条畸形语句 → 真库报语法错；
- 交付的 `<层>_ddl.sql` 丢进 mysql 客户端，`CREATE ... COMMENT='..'`（无 `;`）会一直吃到下一条 `;` 为止、同样粘连 → **312 表的建表脚本根本跑不动**（spec §9 要求 .sql 可直接执行）。

修法：给这三处 `Ddl.buildDdl(...)` 之后的 `.append("\n\n")` 改为 `.append(";\n\n")`（只动 DDL 的 append，transform 的 `dml.append(...)` 本来就以 `;` 收尾，不动）。committed `LayerGen.java` 里的三行：

```java
// genDom
ddl.append(Ddl.buildDdl(target, target, cols, true)).append(";\n\n");
// genObj
ddl.append(Ddl.buildDdl(dim, dim, cols, true)).append(";\n\n");
// genAds
ddl.append(Ddl.buildDdl(target, "主题 " + theme + "（待核）", cols, true)).append(";\n\n");
```

再在 `LayerGenDiffer.java` 的 ads GOV_ADS 断言之后、"未绑定"段之前，插一段**离线**锁（无库也能判红，守"守卫要真变红"）：

```java
        // 建表语句必须以分号终止：否则整层拼接后 splitStatements 粘连、交付 .sql 在 mysql 客户端跑不动
        if (!dom.ddlSql.contains("COMMENT='dwd_proc_oil';") || !dom.ddlSql.contains("COMMENT='dwd_comp_tech';")) {
            bad++; shown.add("dom DDL 建表语句未以分号终止");
        }
        if (!obj.ddlSql.contains("COMMENT='dim_material';")) {
            bad++; shown.add("obj DDL 建表语句未以分号终止");
        }
        if (!obj.ddlSql.startsWith("USE `obj`;")) {
            bad++; shown.add("obj DDL 缺 USE `obj`; 头（execute 的一致性校验/前置 USE 全靠它）");
        }
        if (!ads.ddlSql.contains("（待核）';")) {
            bad++; shown.add("ads DDL 建表语句未以分号终止");
        }
```

- [ ] **Step 3: 在 LayerGen 追加 I/O 实现**

在 `LayerGen.java` 末尾（`join` 方法之后、类 closing brace 之前）追加：

```java
    // ---- 连库 I/O（Task 3）----
    public static final class WriteResult {
        public int tablesCreated;
        public long rowsAffected;
        public List<String> errors = new ArrayList<String>();
    }

    /** 读源库用户表结构（VIEW 排除），列按 ORDINAL_POSITION 序。 */
    public static List<SourceTable> readSource(DbClient db, String sourceDb) throws java.sql.SQLException {
        List<SourceTable> out = new ArrayList<SourceTable>();
        java.sql.DatabaseMetaData md = db.connection().getMetaData();
        List<String> tableNames = new ArrayList<String>();
        java.sql.ResultSet ts = md.getTables(sourceDb, null, "%", new String[]{"TABLE"});
        try { while (ts.next()) { tableNames.add(ts.getString("TABLE_NAME")); } } finally { ts.close(); }
        java.util.Collections.sort(tableNames);
        for (String tn : tableNames) {
            String comment = "";
            java.sql.ResultSet tt = md.getTables(sourceDb, null, tn, new String[]{"TABLE"});
            try { if (tt.next()) { comment = tt.getString("REMARKS"); } } finally { tt.close(); }
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            java.sql.ResultSet cs = md.getColumns(sourceDb, null, tn, "%");
            try {
                while (cs.next()) {
                    Infer.Column c = new Infer.Column();
                    c.field = cs.getString("COLUMN_NAME");
                    c.comment = cs.getString("REMARKS");
                    c.type = jdbcTypeToDdl(cs.getInt("DATA_TYPE"), cs.getInt("COLUMN_SIZE"), cs.getInt("DECIMAL_DIGITS"));
                    c.maxLen = cs.getInt("COLUMN_SIZE");
                    c.nullable = cs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls;
                    cols.add(c);
                }
            } finally { cs.close(); }
            java.sql.ResultSet pk = md.getPrimaryKeys(sourceDb, null, tn);
            Set<String> pkFields = new LinkedHashSet<String>();
            try { while (pk.next()) { pkFields.add(pk.getString("COLUMN_NAME")); } } finally { pk.close(); }
            for (Infer.Column c : cols) { c.primaryKey = pkFields.contains(c.field); }
            out.add(new SourceTable(tn, comment == null ? "" : comment, cols));
        }
        return out;
    }

    private static String jdbcTypeToDdl(int jdbcType, int size, int digits) {
        switch (jdbcType) {
            case java.sql.Types.INTEGER: return "INT";
            case java.sql.Types.BIGINT: return "BIGINT";
            case java.sql.Types.SMALLINT: return "SMALLINT";
            case java.sql.Types.TINYINT: return "TINYINT";
            case java.sql.Types.DECIMAL: case java.sql.Types.NUMERIC:
                return "DECIMAL(" + Math.max(size, 1) + "," + Math.max(digits, 0) + ")";
            case java.sql.Types.DOUBLE: case java.sql.Types.FLOAT: case java.sql.Types.REAL: return "DOUBLE";
            case java.sql.Types.DATE: return "DATE";
            case java.sql.Types.TIMESTAMP: return "DATETIME";
            case java.sql.Types.TIME: return "TIME";
            case java.sql.Types.BIT: case java.sql.Types.BOOLEAN: return "TINYINT(1)";
            case java.sql.Types.LONGVARCHAR: case java.sql.Types.CLOB: return "TEXT";
            default: return "VARCHAR(" + (size > 0 && size <= 16383 ? size : 255) + ")";
        }
    }

    /** 入库：建库（若缺）→建表→执行转换 SQL。单条失败隔离记 errors，不掀整轮。 */
    public static WriteResult execute(LayerOutput out, String targetDb, DbClient db, ImportParse.Sink sink)
            throws java.sql.SQLException {
        WriteResult wr = new WriteResult();
        // targetDb 与 DDL 里的 `USE \`x\`;` 头必须一致——两者是两个事实源，调用方（Task4/5）给错库时，
        // 语句会经 USE 落进另一个库、还在这库留个空壳。宁可记账不执行，也不能默默写错库。
        String useDb = firstUseTarget(out.ddlSql);
        if (useDb != null && !useDb.equals(targetDb)) {
            wr.errors.add("目标库不一致：USE `" + useDb + "` 但 targetDb=" + targetDb + "，已跳过执行");
            sink.logError("x [ERROR] LAYER-EXEC | 目标库不一致，未执行：" + useDb + " vs " + targetDb);
            return wr;
        }
        // 建库也纳入单条失败隔离：权限/磁盘错记进 errors、收摊返回，不再抛出掀整轮（与下面循环同口径）。
        java.sql.Statement st = db.connection().createStatement();
        try {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + targetDb + "` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci");
            db.connection().commit();
        } catch (java.sql.SQLException e) {
            wr.errors.add("建库失败[" + targetDb + "]：" + e.getMessage());
            sink.logError("x [ERROR] LAYER-DB | 建库失败：" + e.getMessage());
            return wr;   // 库没建成，后面 USE/建表必炸，直接收摊
        } finally { st.close(); }
        // 会话目标库设为**前置条件**：显式 USE 并单独隔离失败——不能只靠下面循环里那条会被
        // continue-on-error 忽略的 USE 头。否则 USE 一旦失败，后续未限定的 DROP/CREATE 会落到
        // 上一次 execute 的库或 URL 目录库（E2M_DB），静默毁同名表。先 USE 成，语句才准往下跑。
        java.sql.Statement us = db.connection().createStatement();
        try {
            us.execute("USE `" + targetDb + "`");
            db.connection().commit();
        } catch (java.sql.SQLException e) {
            noteError(wr, sink, "LAYER-USE", "切库失败", "USE `" + targetDb + "`", targetDb, e);
            return wr;   // 进不去目标库就别往下走，绝不让语句漂到别的库
        } finally { us.close(); }
        // DDL 按 ";\n" 拆（与 DbClient.createTable 同口径）；USE 头已在上面坐实，这里再遇 USE 是同库空操作
        for (String stmt : splitStatements(out.ddlSql)) {
            if (stmt.trim().isEmpty()) { continue; }
            java.sql.Statement s = db.connection().createStatement();
            try {
                s.execute(stmt);
                db.connection().commit();
                if (stmt.trim().toUpperCase().startsWith("CREATE TABLE")) { wr.tablesCreated++; }
            } catch (java.sql.SQLException e) {
                noteError(wr, sink, "LAYER-DDL", "DDL 失败", stmt, targetDb, e);
            } finally { s.close(); }
        }
        for (String stmt : splitStatements(out.transformSql)) {
            if (stmt.trim().isEmpty()) { continue; }
            java.sql.Statement s = db.connection().createStatement();
            try {
                int n = s.executeUpdate(stmt);
                db.connection().commit();
                if (n > 0) { wr.rowsAffected += n; }
            } catch (java.sql.SQLException e) {
                noteError(wr, sink, "LAYER-DML", "转换失败", stmt, targetDb, e);
            } finally { s.close(); }
        }
        return wr;
    }

    /** 从 DDL 串里取第一个 `USE \`x\`;` 的库名；没有返回 null。 */
    private static String firstUseTarget(String ddl) {
        int i = ddl.indexOf("USE `");
        if (i < 0) { return null; }
        int j = ddl.indexOf('`', i + 5);
        return j < 0 ? null : ddl.substring(i + 5, j);
    }

    /** 逐条报错日志上限：超过就只报一次"另有更多"，与 SqlFileRun 的汇总口径一致，312 表时不刷屏。 */
    private static final int MAX_LOGGED_ERRORS = 15;

    /** 单条失败记账：错误串带**落库名＋语句头部**（312 表时才知道炸在哪个库哪条）；逐条日志有上限。 */
    private static void noteError(WriteResult wr, ImportParse.Sink sink, String tag,
                                  String kind, String stmt, String db, java.sql.SQLException e) {
        wr.errors.add(kind + "（库 " + db + "）[" + stmtHead(stmt) + "]：" + e.getMessage());
        if (wr.errors.size() < MAX_LOGGED_ERRORS) {
            sink.logError("x [ERROR] " + tag + " | 库 " + db + " · " + stmtHead(stmt) + "：" + e.getMessage());
        } else if (wr.errors.size() == MAX_LOGGED_ERRORS) {
            sink.logError("! [WARN] " + tag + " | 库 " + db + " 另有更多语句报错未逐条列出（见 errors 汇总）");
        }
    }

    private static String stmtHead(String stmt) {
        String s = stmt.trim();
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /** 按 ";\n" 拆语句，但保留语句内的分号（注释行里的 -- 不拆）。与 DbClient.createTable 同口径。 */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        String[] lines = sql.split("\n", -1);
        for (String line : lines) {
            cur.append(line).append("\n");
            if (line.trim().endsWith(";")) {
                String s = cur.toString().trim();
                if (s.endsWith(";")) { s = s.substring(0, s.length() - 1); }
                out.add(s);
                cur.setLength(0);
            }
        }
        if (cur.toString().trim().length() > 0) { out.add(cur.toString().trim()); }
        return out;
    }
```

注：`splitStatements` 对 `-- 注释` 行会并入下一条语句一起 execute，MySQL 允许语句前缀行注释。execute **不再** 按 `startsWith("--")` 跳过整段——ads 的 `-- 主题...` 注释与紧随的 `INSERT ... GROUP BY ...;` 在同一段（注释行不以 `;` 结尾），若按 `--` 前缀跳过会把注释后面的 INSERT 一起丢掉、ads 表恒空。只跳过 `trim().isEmpty()` 的空片段；本流程里不存在"纯注释无语句"的片段（每条 INSERT 都以 `;` 收尾自成一段）。

- [ ] **Step 4: 跑测试确认通过（需 testdb 凭据）**

```bash
set -a; . ./.pt_env; set +a
export E2M_HOST="${PT_HOST:-127.0.0.1}"; export E2M_PORT="${PT_PORT:-3306}"
export E2M_USER="${PT_USER:-root}"; export E2M_PASS="$PT_PASS"; export E2M_DB="${PT_DB:-testdb}"
mvn -B -q install -pl app,verify
java -Dfile.encoding=UTF-8 -cp "app/target/classes;verify/target/classes;verify/target/lib/*" com.excel2mysql.verify.LayerDbRegression
echo "exit=$?"
```
Expected: LayerDbRegression `+ [OK] LAYER-DB | ...` exit=0，且 LayerGenDiffer 重跑仍 `+ [OK] LAYER-DIFF | ...` exit=0（Step 3a 补分号后离线断言必须继续绿）。端口不通（WSL/docker 没起）时连不上——这是"未覆盖"，汇报要说清是端口不通而非没配凭据；不许留 SKIP 当成跑过。跑前扫一眼 testdb 有无上一轮残留 `e2mlayerprobe_*` 库。

- [ ] **Step 5: 变异抽查**

（a）DB 侧：把 `execute` 里 `CREATE DATABASE IF NOT EXISTS` 那行注释掉，重编译跑 LayerDbRegression，确认报 `DDL 失败` 或 `SIDE-EFFECT`（建表落在错库）这条可读 FAIL。Edit 还原＋md5 自证＋重跑 exit=0。若本机 testdb 端口不通跑不了本条，如实记"端口不通未覆盖"，不许当跑过。
（b）离线侧（不依赖库，必做）：把 Step 3a 里 genDom 那句 `.append(";\n\n")` 改回 `.append("\n\n")`，重编译跑 LayerGenDiffer，确认报 `dom DDL 建表语句未以分号终止` 这条可读 FAIL；Edit 还原＋md5 自证＋重跑 LayerGenDiffer exit=0。再单独把 `execute` 的 transform 循环改回 `... || stmt.trim().startsWith("--")`，跑 LayerDbRegression（有库时）应报 `ads ads_rc 期望 1 行 实际 0`——无库时此条记未覆盖。

- [ ] **Step 6: Commit**

```bash
git add java/app/src/main/java/com/excel2mysql/core/LayerGen.java \
        java/verify/src/main/java/com/excel2mysql/verify/LayerDbRegression.java \
        java/verify/src/main/java/com/excel2mysql/verify/LayerGenDiffer.java
git commit -m "feat(core): 分层建模读源库结构与入库执行，DDL 语句补分号终止，真库探针回归零残留"
```

---

### Task 4: LayerPage 页签＋MainFrame 集成（只生成模式）

**Files:**
- Create: `java/app/src/main/java/com/excel2mysql/gui/LayerPage.java`
- Modify: `java/app/src/main/java/com/excel2mysql/gui/MainFrame.java`（RES_LAYER、挂第四页、实现 LayerPage.Host、syncRunStates/refreshDbLabels）
- Test: `java/app/src/main/java/com/excel2mysql/GuiMain.java`（selftest 追加第四页断言）

**Interfaces:**
- Consumes: `MappingFile`、`LayerGen`、`MainFrame.runTask`、`LogPanel`、`PageIntro`、`Tables`、`Buttons`、`UiScale`。
- Produces:
  - `LayerPage.Host` 接口：`Config currentConfig(); boolean connected(); String exportRoot(); void warn(String text); void informError(String title, String text); void runLayerTask(String title, Function<ImportParse.Sink,Object> work, Consumer<Object> onUi);`
  - `LayerPage(Host host)` 构造；`LogPanel logPanel()`；`javax.swing.JButton objButton()/domButton()/adsButton()`；`boolean loadMapping(String path)`（载入并填清单，返回是否成功）；`void setSourceTables(List<LayerGen.SourceTable>)`；`void refreshButtonStates()`（依赖锁）；`boolean adsReady()`（obj＋dom 均已生成）；`void generateTo(LayerGen.Layer layer, String rootDir)`（显式产物根，自检用）。
  - MainFrame：`public static final String RES_LAYER = "layer";`＋RES_LABEL 条目；`implements SqlPage.Host, LayerPage.Host`；`public void runLayerTask(...)` 委托 runTask 指 layerPage.logPanel()、资源位 RES_LAYER。

- [ ] **Step 1: 写失败测试（GuiMain selftest 追加）**

在 `GuiMain.java` selftest 段（`static_texts(frame, problems);` 调用之后、添加夹具之前的位置）追加第四页断言。先 Read 该段定位插入点（selftest 里 `MainFrame frame = new MainFrame();` 之后那一串 problems.add 检查附近），插入：

```java
                // 第四页：页签存在、三按钮默认灰（未载入映射）、ads 依赖锁
                if (frame.layerPage() == null) {
                    problems.add("第四页 layerPage 没挂上");
                } else {
                    com.excel2mysql.gui.LayerPage lp = frame.layerPage();
                    if (lp.objButton().isEnabled() || lp.domButton().isEnabled() || lp.adsButton().isEnabled()) {
                        problems.add("未载入映射时第四页三按钮却可点");
                    }
                    // 载入一份临时映射＋源结构后 obj/dom 亮、ads 仍灰（未生成）
                    java.io.File mf = java.io.File.createTempFile("selftest-layer-", ".csv");
                    mf.deleteOnExit();
                    java.io.Writer mw = new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(mf), java.nio.charset.StandardCharsets.UTF_8);
                    mw.write('﻿');
                    mw.write("系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n");
                    mw.write("采购供应,油料,油料账,t_oil,2,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n");
                    mw.close();
                    if (!lp.loadMapping(mf.getPath())) {
                        problems.add("第四页载入映射失败");
                    }
                    java.util.List<com.excel2mysql.core.LayerGen.SourceTable> st =
                            new java.util.ArrayList<com.excel2mysql.core.LayerGen.SourceTable>();
                    java.util.List<com.excel2mysql.core.Infer.Column> cs =
                            new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
                    com.excel2mysql.core.Infer.Column c1 = new com.excel2mysql.core.Infer.Column();
                    c1.field = "id"; c1.type = "VARCHAR(64)"; c1.comment = "主键"; c1.primaryKey = true; c1.maxLen = 64;
                    com.excel2mysql.core.Infer.Column c2 = new com.excel2mysql.core.Infer.Column();
                    c2.field = "name"; c2.type = "VARCHAR(255)"; c2.comment = "名称"; c2.maxLen = 255;
                    cs.add(c1); cs.add(c2);
                    st.add(new com.excel2mysql.core.LayerGen.SourceTable("t_oil", "油料账", cs));
                    lp.setSourceTables(st);
                    lp.refreshButtonStates();
                    if (!lp.objButton().isEnabled() || !lp.domButton().isEnabled()) {
                        problems.add("载入映射＋源结构后 obj/dom 按钮却没亮");
                    }
                    if (lp.adsButton().isEnabled()) {
                        problems.add("obj/dom 未生成时 ads 按钮却亮了（依赖锁失效）");
                    }
                }
```

并在 MainFrame 加 accessor `public com.excel2mysql.gui.LayerPage layerPage() { return layerPage; }`（Step 3 一起做）。

- [ ] **Step 2: 跑自检确认失败**

```bash
mvn -B -q install -pl app,verify
java -Dfile.encoding=UTF-8 -jar app/target/excel2mysql-5.1.0.jar --gui --selftest verify/fixtures
echo "exit=$?"
```
Expected: 编译失败（`LayerPage`/`frame.layerPage()` 不存在）或自检红"第四页 layerPage 没挂上"。预期红。

- [ ] **Step 3: 写 LayerPage＋MainFrame 集成**

创建 `java/app/src/main/java/com/excel2mysql/gui/LayerPage.java`。仿 SqlPage 结构（JPanel＋Host＋LogPanel＋PageIntro）。完整代码：

```java
package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** 第 4 页签：读 ods 建模 obj/dom/ads。映射文件驱动，三按钮按依赖锁，默认只生成脚本。 */
public final class LayerPage extends JPanel {

    public interface Host {
        Config currentConfig();
        boolean connected();
        String exportRoot();
        void warn(String text);
        void informError(String title, String text);
        void runLayerTask(String title, Function<ImportParse.Sink, Object> work, Consumer<Object> onUi);
    }

    private final Host host;
    private final LogPanel logPanel = new LogPanel();
    private final JComboBox<String> sourceDb = new JComboBox<String>(new String[]{"ods"});
    private final JTextField objDb = new JTextField("obj", 6);
    private final JTextField domDb = new JTextField("dom", 6);
    private final JTextField adsDb = new JTextField("ads", 6);
    private final JLabel mappingLabel = new JLabel("未载入映射");
    private final JCheckBox writeMode = new JCheckBox("同时写入目标库", false);
    private final JButton objBtn = new JButton("生成对象层 obj");
    private final JButton domBtn = new JButton("生成领域层 dom");
    private final JButton adsBtn = new JButton("生成主题层 ads");
    private final javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
            new Object[]{"源表名", "表注释", "字段数", "目标领域", "领域子类", "对象维度", "主题", "映射状态", "绑定状态"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);

    private MappingFile mapping;
    private List<LayerGen.SourceTable> sourceTables = new ArrayList<LayerGen.SourceTable>();
    private boolean objGenerated;
    private boolean domGenerated;

    public LayerPage(Host host) {
        this.host = host;
        Buttons.secondary(objBtn);
        Buttons.secondary(domBtn);
        Buttons.secondary(adsBtn);
        setLayout(new BorderLayout(0, UiScale.px(6));
        add(buildTop(), BorderLayout.NORTH);
        Tables.style(table);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        objBtn.addActionListener(e -> generate(LayerGen.Layer.OBJ));
        domBtn.addActionListener(e -> generate(LayerGen.Layer.DOM));
        adsBtn.addActionListener(e -> generate(LayerGen.Layer.ADS));
        refreshButtonStates();
    }

    private JPanel buildTop() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(PageIntro.of("读 ods 建模 obj/dom/ads"), BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        row.add(new JLabel("源库"));
        sourceDb.setEditable(true);
        row.add(sourceDb);
        JButton load = new JButton("载入映射文件");
        Buttons.secondary(load);
        load.addActionListener(e -> chooseMapping());
        row.add(load);
        row.add(mappingLabel);
        row.add(new JLabel("目标库 obj/dom/ads"));
        row.add(objDb); row.add(domDb); row.add(adsDb);
        p.add(row, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildBottom() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        bar.add(writeMode);
        bar.add(objBtn); bar.add(domBtn); bar.add(adsBtn);
        p.add(bar, BorderLayout.NORTH);
        p.add(logPanel, BorderLayout.CENTER);
        return p;
    }

    private void chooseMapping() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("载入层间映射 CSV");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadMapping(fc.getSelectedFile().getPath());
        }
    }

    public boolean loadMapping(String path) {
        try {
            mapping = MappingFile.loadTableCsv(path);
            mappingLabel.setText(new File(path).getName() + "（" + mapping.tables().size() + " 表）");
            fillTable();
            logPanel.log("- [INFO] LAYER | 载入映射 " + path + " | " + mapping.tables().size() + " 表");
            refreshButtonStates();
            return true;
        } catch (Exception ex) {
            host.informError("映射载入失败", ex.getMessage());
            logPanel.log("x [ERROR] LAYER | 映射载入失败：" + ex.getMessage());
            return false;
        }
    }

    public void setSourceTables(List<LayerGen.SourceTable> src) {
        this.sourceTables = src == null ? new ArrayList<LayerGen.SourceTable>() : src;
        fillTable();
        refreshButtonStates();
    }

    private void fillTable() {
        model.setRowCount(0);
        if (mapping == null) { return; }
        java.util.Set<String> srcNames = new java.util.HashSet<String>();
        for (LayerGen.SourceTable t : sourceTables) { srcNames.add(t.name); }
        for (MappingFile.TableMap t : mapping.tables()) {
            String physical = t.odsPhysical == null || t.odsPhysical.isEmpty() ? t.tableEn : t.odsPhysical;
            boolean bound = srcNames.contains(physical);
            model.addRow(new Object[]{t.tableEn, t.tableCn, t.fieldCount, t.domain, t.domainSub,
                    t.objDims, t.theme, t.status, bound ? "已绑定" : "未绑定"});
        }
    }

    public void refreshButtonStates() {
        boolean ready = mapping != null && !mapping.tables().isEmpty() && !sourceTables.isEmpty();
        objBtn.setEnabled(ready);
        domBtn.setEnabled(ready);
        adsBtn.setEnabled(ready && objGenerated && domGenerated);
        if (ready && !objGenerated && !domGenerated) {
            adsBtn.setToolTipText("<html>主题层 ads 从 obj＋dom 汇总。<br>请先生成对象层与领域层。</html>");
        } else {
            adsBtn.setToolTipText(null);
        }
    }

    public boolean adsReady() { return objGenerated && domGenerated; }

    private void generate(final LayerGen.Layer layer) {
        generateTo(layer, host.exportRoot());
    }

    /** 显式产物根：按钮走 host.exportRoot()，自检走临时目录（仿 SqlPage.generateExcel(boolean,String)）。 */
    public void generateTo(final LayerGen.Layer layer, String rootDir) {
        if (mapping == null) { return; }
        final LayerGen.GenOptions o = new LayerGen.GenOptions();
        o.sourceDb = String.valueOf(sourceDb.getSelectedItem());
        o.objDb = objDb.getText().trim();
        o.domDb = domDb.getText().trim();
        o.adsDb = adsDb.getText().trim();
        o.writeMode = writeMode.isSelected();
        final String root = rootDir;
        host.runLayerTask("生成 " + layer, new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                LayerGen.LayerOutput out = LayerGen.genLayer(layer, mapping, sourceTables, o);
                String dir = root + File.separator + "layer_"
                        + new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
                new File(dir).mkdirs();
                String tag = layer.name().toLowerCase();
                try {
                    writeText(dir + File.separator + tag + "_ddl.sql", out.ddlSql);
                    writeText(dir + File.separator + tag + "_transform.sql", out.transformSql);
                    MappingFile.writeFieldCsv(dir + File.separator + tag + "_field_mapping.csv", out.fieldMapping);
                } catch (Exception ex) {
                    sink.logError("x [ERROR] LAYER | 落盘失败：" + ex.getMessage());
                    return null;
                }
                sink.log("- [INFO] LAYER | " + layer + " 表 " + out.tableCount + " 张 | 字段映射 "
                        + out.fieldMapping.size() + " 条 | 未绑定 " + out.unbound.size() + " 张 | 产物 " + dir);
                for (String w : out.warnings) { sink.log("! [WARN] LAYER | " + w); }
                return new Object[]{layer, out, dir};
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                Object[] arr = (Object[]) res;
                LayerGen.Layer lay = (LayerGen.Layer) arr[0];
                if (lay == LayerGen.Layer.OBJ) { objGenerated = true; }
                if (lay == LayerGen.Layer.DOM) { domGenerated = true; }
                refreshButtonStates();
                String dir = (String) arr[2];
                host.warn("已生成 " + lay + "，产物目录：" + dir);
            }
        });
    }

    private static void writeText(String path, String text) throws java.io.IOException {
        java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(path), java.nio.charset.StandardCharsets.UTF_8);
        try { w.write(text); } finally { w.close(); }
    }

    public LogPanel logPanel() { return logPanel; }
    public JButton objButton() { return objBtn; }
    public JButton domButton() { return domBtn; }
    public JButton adsButton() { return adsBtn; }
}
```

注（已核对 SqlPage 实际用法）：按钮用 `new JButton("文案")` 再 `Buttons.secondary(btn)` 上色（Buttons 只有 primary/secondary，取 JButton 返回 JButton，无 plain 工厂）；日志加行用 `logPanel.log(String)`（LogPanel 无单参 append，另有 append(String,boolean isError)）；`Tables.style(JTable)→JTable` 与 `PageIntro.of(String)→StaticText` 签名如此，直接用。

MainFrame 改动（逐处）：
1. 类声明：`public final class MainFrame extends JFrame implements SqlPage.Host, LayerPage.Host {`
2. RES 常量区（116-129 行附近）加：`public static final String RES_LAYER = "layer";` 并在 RES_LABEL static 块加 `RES_LABEL.put(RES_LAYER, "分层建模页的生成任务");`
3. 字段区加 `private LayerPage layerPage;`
4. 页签构造（268 行 `tabs.addTab("SQL 执行器", buildSqlRunPage());` 之后）加：
   ```java
   layerPage = new LayerPage(this);
   tabs.addTab("分层建模", layerPage);
   ```
5. accessor：`public LayerPage layerPage() { return layerPage; }`
6. 实现 Host 方法（runSqlTask 附近）：
   ```java
   @Override
   public void runLayerTask(String title,
                          java.util.function.Function<ImportParse.Sink, Object> work,
                          java.util.function.Consumer<Object> onUi) {
       runTask(title, work, onUi, layerPage == null ? null : layerPage.logPanel(),
               new String[]{RES_LAYER});
   }
   ```
   （currentConfig/connected/exportRoot/warn/informError 已由 SqlPage.Host 实现，签名相同，无需重复。）
7. syncRunStates（1284 行）末尾加：
   ```java
   if (layerPage != null) { layerPage.refreshButtonStates(); }
   ```
   注：refreshButtonStates 目前只看映射/源结构/生成标志，任务忙闲互斥由 runTask 的资源位挡（被挡下会日志说明），与 SqlPage.setGenBusy 同口径；如需忙时灰置，加 `layerPage.setGenBusy(taskBusy(RES_LAYER))` 并在 LayerPage 增该方法（执行时按 SqlPage.setGenBusy 形状补）。

- [ ] **Step 4: 跑自检确认通过**

```bash
mvn -B -q install -pl app,verify
java -Dfile.encoding=UTF-8 -jar app/target/excel2mysql-5.1.0.jar --gui --selftest verify/fixtures
echo "exit=$?"
```
Expected: 自检 exit=0，无"第四页"相关 problems。

- [ ] **Step 5: 变异抽查**

把 `refreshButtonStates` 里 `adsBtn.setEnabled(ready && objGenerated && domGenerated);` 改成 `adsBtn.setEnabled(ready);`，重编译跑自检，确认报"obj/dom 未生成时 ads 按钮却亮了（依赖锁失效）"。Edit 还原＋md5 自证＋重跑 exit=0。

- [ ] **Step 6: Commit**

```bash
git add java/app/src/main/java/com/excel2mysql/gui/LayerPage.java \
        java/app/src/main/java/com/excel2mysql/gui/MainFrame.java \
        java/app/src/main/java/com/excel2mysql/GuiMain.java
git commit -m "feat(gui): 新增分层建模第四页，三按钮依赖锁与只生成模式落盘"
```

---

### Task 5: 源库结构载入＋字段映射例外窗＋入库接线＋全闸门

**Files:**
- Modify: `java/app/src/main/java/com/excel2mysql/gui/LayerPage.java`（源库下拉填库列表、读源结构按钮、双击字段映射窗、入库模式执行）
- Modify: `java/app/src/main/java/com/excel2mysql/gui/MainFrame.java`（refreshDbLabels 覆盖第四页源库下拉）
- Modify: `java/构建验证.bat`（追加 MappingFileDiffer/LayerGenDiffer 两步）
- Modify: `java/app/src/main/java/com/excel2mysql/GuiMain.java`（selftest 追加只生成模式无 DB 副作用断言）
- Test: 复用 LayerDbRegression（Task 3）＋自检

**Interfaces:**
- Consumes: `DbClient.listDatabases()`、`LayerGen.readSource`、`LayerGen.execute`、Task 4 的 LayerPage。
- Produces: LayerPage 双击行弹字段映射例外窗（左右对照，转换规则可改，导出 `<层>_field_mapping.csv`）；「读源库结构」按钮调 readSource 填清单；writeMode 开时 generate 内调 execute 入库。

- [ ] **Step 1: 写失败测试（自检追加只生成模式无 DB 副作用）**

在 GuiMain selftest 第四页断言块末尾（`lp.refreshButtonStates();` 之后）追加：点 obj 生成（原地跑，modalProgress=false），断言产物落盘且 writeMode 关时不连库。因自检 exportRoot 指临时目录，断言临时目录下出现 `layer_*/obj_ddl.sql`：

```java
                    // 只生成模式：显式产物根指临时目录（仿 sql_gen_excel 的 generateExcel(true,outRoot)），
                    // 不污染 programDir；modalProgress=false 下 runLayerTask 原地跑完
                    java.io.File outRoot = java.nio.file.Files.createTempDirectory("e2m-layer-selftest").toFile();
                    lp.generateTo(com.excel2mysql.core.LayerGen.Layer.OBJ, outRoot.getPath());
                    java.io.File[] dirs = outRoot.listFiles();
                    boolean found = false;
                    if (dirs != null) {
                        for (java.io.File d : dirs) {
                            if (d.isDirectory() && d.getName().startsWith("layer_")
                                    && new java.io.File(d, "obj_ddl.sql").isFile()) { found = true; }
                        }
                    }
                    if (!found) { problems.add("只生成模式 generateTo(obj) 后没在临时根落下 obj_ddl.sql"); }
                    if (lp.adsButton().isEnabled()) {
                        problems.add("仅生成 obj 后 ads 不该亮（dom 未生成）");
                    }
```

注：已核对——MainFrame.exportRoot() 恒返回 programDir()，自检不改它；既有生成类自检（sql_gen_excel）用显式根方法 `page.generateExcel(true, outRoot)` 把产物导到临时目录。故第四页照此用 `generateTo(layer, tempRoot)`，不点按钮（点按钮走 exportRoot=programDir 会污染 app/target）。createTempDirectory 抛 IOException，插入点须在 selftest 既有 try 块内（Read 定位时确认）。modalProgress=false 下 runLayerTask 原地跑完。

- [ ] **Step 2: 跑自检确认失败**

```bash
mvn -B -q install -pl app,verify
java -Dfile.encoding=UTF-8 -jar app/target/excel2mysql-5.1.0.jar --gui --selftest verify/fixtures
echo "exit=$?"
```
Expected: 若 Task 4 的 generateTo 已落盘则此步可能直接绿——说明 Step 1 断言被既有实现满足，跳到 Step 3 补源库/字段窗/入库接线（靠真库回归与手工入口实跑验）。若红"没落下 obj_ddl.sql"，核对 generateTo 的落盘路径与临时根。

- [ ] **Step 3: 补 LayerPage 源库载入＋字段映射窗＋入库接线**

LayerPage 追加（执行时先 Read SqlPage 的 JTable 双击窗与 DbClient 调用形状，照其口径）：
1. 顶部加「读源库结构」按钮：`host.connected()` 为真时，`host.runLayerTask` 子线程里 `new DbClient(host.currentConfig())`→`listDatabases()` 填 sourceDb 下拉、`LayerGen.readSource(db, 选中的源库)`→`setSourceTables(...)`；连不上按既有"连接失败＋库列表未加载"日志＋error 弹窗。
2. writeMode 开时，generate 的工作函数在落盘后追加：`new DbClient(host.currentConfig())`→`LayerGen.execute(out, 目标库, db, sink)`，把 wr.rowsAffected/tablesCreated/errors 记日志；目标库名按 layer 取 objDb/domDb/adsDb。DbClient 用完 close。
3. 双击清单行弹字段映射例外窗：JDialog 左右两列（源字段带注释 vs 目标字段下拉＋转换规则 JTextField），改后存进一份 `List<MappingFile.FieldMap>` 例外表，generate 时把例外覆盖进 out.fieldMapping 再导出。窗口形状照 SqlPage 的结构弹窗（grep `JDialog` 在 SqlPage.java）。
4. MainFrame.refreshDbLabels 末尾：连接成功后把库列表同步给 layerPage 的 sourceDb 下拉（加 `layerPage.setDatabases(List<String>)`，照 sqlRun 页同步库列表的既有调用）。

本步以手工入口实跑＋真库回归验证（Step 4），不新增离线断言（源库 I/O 已被 LayerDbRegression 咬住）。

- [ ] **Step 4: 真库回归＋全闸门＋入口实跑**

```bash
# 真库（凭据载入后）
set -a; . ./.pt_env; set +a
export E2M_HOST="${PT_HOST:-127.0.0.1}"; export E2M_PORT="${PT_PORT:-3306}"
export E2M_USER="${PT_USER:-root}"; export E2M_PASS="$PT_PASS"; export E2M_DB="${PT_DB:-testdb}"
java -Dfile.encoding=UTF-8 -cp "app/target/classes;verify/target/classes;verify/target/lib/*" com.excel2mysql.verify.LayerDbRegression
echo "layer-db exit=$?"
# 追加差分步到 构建验证.bat 后实跑全闸门
```
在 `java/构建验证.bat` 的第 9 步（MetaDiffer）之后、第 10 步（自检）之前插入两步（编号顺延，把原 10 步标题改 12 步或保持 10 步内合并——执行时按既有编号风格，新增 `=== 9b/10 映射文件差分 ===` 与 `=== 9c/10 分层生成差分 ===`，各 `|| exit /b 1`）：
```bat
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.MappingFileDiffer || exit /b 1
call "%JDK8%\bin\java" -Dfile.encoding=UTF-8 -cp "app\target\classes;verify\target\classes;verify\target\lib\*" ^
  com.excel2mysql.verify.LayerGenDiffer || exit /b 1
```
然后实跑 `java/构建验证.bat`，退出码 0。打包只在用户明说时做；本任务不打包。入口实跑：用构建出的 jar `java -jar app/target/excel2mysql-5.1.0.jar --gui`，手工点第四页载入映射→读源结构→生成 obj/dom→ads 亮→生成 ads，确认产物目录三份文件齐备（这是 GUI 手工验收，自动化咬不住模态那一支，见 spec §13）。

- [ ] **Step 5: 变异抽查**

把 LayerPage generate 里 writeMode 分支的 `LayerGen.execute` 调用注释掉，真库回归仍绿（因回归直接调 execute，不经 GUI）——所以这条变异咬不住 GUI 入库；改为手工入口实跑时开 writeMode 确认目标库真建表（人工判据）。把 `MappingFile.writeFieldCsv` 的 BOM 去掉跑 MappingFileDiffer 确认往返仍绿（BOM 不影响读）——真正咬住的是缺列校验，已在 Task 1 变异过。本步重点：确认 ads 依赖锁变异（Task 5 Step 1 断言）真红。

- [ ] **Step 6: Commit**

```bash
git add java/app/src/main/java/com/excel2mysql/gui/LayerPage.java \
        java/app/src/main/java/com/excel2mysql/gui/MainFrame.java \
        java/app/src/main/java/com/excel2mysql/GuiMain.java \
        java/构建验证.bat
git commit -m "feat(gui): 分层建模读源库结构、字段映射例外窗与入库接线，闸门补两差分"
```

---

## 验收对照（spec §13，执行完逐条核）

- 183 十类齐备：DIM_PK 十类常量在，obj 按实际命中建（不硬凑空类）。
- 184 七领域 33 子类：dom 按映射 domainTarget 整编，映射初稿覆盖 33 子类。
- 188 主题可追溯：ads 按 theme 建模板聚合，标待核。
- 186 归一字段并存可回溯：obj entity_id=UUID()＋src_entity_id 保留来源；std_code/src_code 双字段。
- 208 逐字段关联导出：每层 `<层>_field_mapping.csv`，覆盖率 100%（LayerGenDiffer 咬）。
- 224 血缘 src_batch_id：治理字段含 src_batch_id/etl_batch_id，占位 `@src_batch_id`。
- 运行模式两态：关＝只落盘（自检断言无 DB）；开＝真建表真灌数（LayerDbRegression 咬）。
- 单表失败隔离：execute 单条失败记 errors 不掀整轮；未绑定记 unbound。
- 入口实跑：构建验证.bat 十步全过＋手工点第四页生成。

## Self-Review（写完计划后自查，已执行）

**1. 规格覆盖：** spec §1-15 逐节对照——§4 映射文件→Task1；§5 架构复用→Task2/4；§6 库模型→Task3/4（USE 头＋全限定源表）；§7 三按钮依赖锁→Task4；§8 转换 SQL→Task2；§9 产物运行模式→Task4/5；§10 归一聚合深度→Task2（UUID/双码字段/模板聚合标待核）；§11 错误处理→Task3 execute 隔离＋Task4 日志；§12 UI→Task4/5；§13 测试→各 Task 差分＋回归＋自检；§14 改动清单→File Structure；§15 风险→ods物理表名列＋未绑定（Task1/2/4）。无遗漏。

**2. 占位符扫描：** 无 TBD/TODO/"implement later"。Task5 Step3 的字段映射窗给的是接线指令＋参照 SqlPage 既有形状（grep JDialog），因该窗纯 UI 组装、无新算法，且 spec §12 已定列形状；执行者照 SqlPage 结构弹窗复刻。其余步骤均有完整代码。

**3. 类型一致：** MappingFile.TableMap/FieldMap 字段名在 Task1 定义、Task2/3/4/5 逐字引用一致；LayerGen.Layer/SourceTable/GenOptions/LayerOutput/WriteResult 签名跨任务一致；Host 接口 runLayerTask 签名 Task4 定义、MainFrame 实现一致；qualify/GOV_OBJ_DOM/GOV_ADS/DIM_PK 常量名一致。GUI 助手签名已核对 SqlPage 实际用法并写死：按钮 `new JButton`＋`Buttons.secondary`、日志 `logPanel.log`、`Tables.style`、`PageIntro.of`；自检产物根用显式 `generateTo(layer,tempRoot)`（仿 generateExcel(true,outRoot)），不改全局 exportRoot。