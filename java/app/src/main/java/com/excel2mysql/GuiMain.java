package com.excel2mysql;

import com.excel2mysql.core.DomainGuess;
import com.excel2mysql.gui.MainFrame;
import com.excel2mysql.gui.SqlSamples;
import com.excel2mysql.gui.UiScale;
import com.excel2mysql.gui.LogPanel;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Swing 版入口。只用 Swing/AWT，不引 JavaFX。
 *
 * --selftest 是给闸门用的：真构造窗口、真解析夹具、真数表格行数，
 * 但不是"看一眼界面没崩"就算过——断言落在可数的副作用上。
 */
public final class GuiMain {

    /** 主窗口刚建好那一刻的日志：日志面板有 2000 行上限，自检跑久了会把启动句挤出去，
     *  要看"启动时说了什么"的断言必须用这份快照，不能到最后再读面板。 */
    private static String startupLog = "";

    public static void main(String[] argv) {
        List<String> args = Arrays.asList(argv);
        if (args.contains("--selftest")) {
            System.exit(selftest(args));
        }
        showWindow(args);
    }

    private static void showWindow(List<String> args) {
        com.excel2mysql.gui.UiScale.apply();
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            List<String> files = new ArrayList<String>();
            for (String a : args) {
                if (!a.startsWith("-")) {
                    files.add(a);
                }
            }
            frame.setVisible(true);
            if (!files.isEmpty()) {
                frame.addFiles(files);
            }
        });
    }

    private static int selftest(List<String> args) {
        // 自检要与生产走同一套外观与字号：以前这里停在 Metal 默认外观，
        // 量到的字体不是用户看到那一套，字号缩了也咬不住
        com.excel2mysql.gui.UiScale.apply();
        int from = args.indexOf("--selftest");
        String dir = from + 1 < args.size() && !args.get(from + 1).startsWith("-")
                ? args.get(from + 1) : "java/verify/fixtures";
        File folder = new File(dir);
        if (!folder.isDirectory()) {
            System.out.println("x [ERROR] GUI-SELFTEST | 夹具目录不存在：" + folder.getPath());
            return 2;
        }
        final List<String> files = new ArrayList<String>();
        File[] children = folder.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File f : children) {
                // 夹具只收 .xlsx 是刻意的：自检里"添加 N 项文件 / 表数 N"这些期望值取的是
                // 目录里 .xlsx 的个数（不是手写常数，但也不放宽到 .xls/.zip——
                // 那会让整批按下标取表的断言同时对不上，红得看不出真问题）。
                // 喂别的目录给不出夹具形状时，由下面 tables==0 那一条给人话，
                // 而不是靠多收文件去"兼容"。
                if (f.getName().toLowerCase().endsWith(".xlsx")) {
                    files.add(f.getPath());
                }
            }
        }
        // 主窗口固定在"没有 conn.ini"的口径下建：包内冒烟时 jar 同级就带着随包 conn.ini，
        // 候选路径不钉住的话，"没找到就说明按默认值起界面"那条断言会随运行目录变红。
        // 后面 good.ini / bad.ini 两个场景各自换候选路径再建窗，不受这里影响。
        final List<String> keepCandidates = com.excel2mysql.gui.MainFrame.connCandidates;
        com.excel2mysql.gui.MainFrame.connCandidates =
                java.util.Collections.singletonList(
                        new File(folder, "no-such-conn.ini").getPath());
        // 关掉模态框：任务原地跑完。测的是线上那同一个工作函数与同一个回报接口，
        // 不是它的复印件（对应 main.py:137 的 MODAL_PROGRESS）。
        // 模态那一支两条线都没有自动化覆盖，交付前手工点一次，见 GUI-对齐清单.md 第 10 节。
        com.excel2mysql.gui.MainFrame.modalProgress = false;
        // 第五页「写入 MySQL 目标库」的确认框同理：自检跑的是真入口，
        // 模态框会把事件分发线程钉住。弹框本身因此未覆盖，只判它的文案与触发条件
        com.excel2mysql.gui.DmPage.confirmWrite = false;
        final CountDownLatch done = new CountDownLatch(1);
        final int[] result = {0};
        // 账本与窗口要能拿到事件分发线程外面：真线程那一支必须站在 EDT 之外等
        //（站在里面睡过去，进度框刷新与任务收尾回调全停，"没卡"就成了假象），
        // 所以判定挪到那一段跑完之后再打，否则并发用例还没跑就被算成通过了
        final List<String> problems = new ArrayList<String>();
        final MainFrame[] holder = new MainFrame[1];
        final int[] metrics = new int[2];
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = null;
            try {
                frame = new MainFrame();   // 建窗也在 try 里：构造期抛异常要让自检拿到退出码，而不是把闸门挂死
                startupLog = frame.logPanel().text();
                com.excel2mysql.gui.MainFrame.connCandidates = keepCandidates;
                holder[0] = frame;
                if (!frame.initialsBox().isSelected() || frame.upperBox().isSelected()
                        || !frame.lowerBox().isSelected()) {
                    problems.add("命名开关默认状态不对：首字母与转小写应勾选、"
                            + "转大写应不勾选（转小写默认选中是用户 2026-11-14 点名）");
                }
                // 空表引导语只在表还空着时画得出来，趁添加夹具之前验
                ui_guidance(frame, problems);
                // 静态文字这一族趁窗口刚建好、正文还没被任何一条用例改过之前判
                static_texts(frame, problems);
                // 第五页「达梦 → MySQL」：页签在位、结构→DDL 的口径逐条钉住、
                // 缺输入与缺驱动那两种结局都要当堂说一句，不许静默
                if (frame.dmPage() == null) {
                    problems.add("第五页 dmPage 没挂上");
                } else {
                    com.excel2mysql.gui.DmPage dp = frame.dmPage();
                    if (frame.tabs().getTabCount() != 5
                            || !"达梦 ⇄ MySQL".equals(frame.tabs().getTitleAt(4))) {
                        problems.add("第五页没排在第 5 位或标题不对：共 "
                                + frame.tabs().getTabCount() + " 页");
                    }
                    if (dp.generateEnabled() || dp.saveEnabled()) {
                        problems.add("还没读回达梦结构，第五页的生成与保存却可点");
                    }
                    dp.connectAndList();
                    if (!dp.logPanel().text().contains("模式名为空")) {
                        problems.add("模式名空着时第五页没说话：" + dp.logPanel().text());
                    }
                    if (!com.excel2mysql.core.DmReader.driverPresent()) {
                        dp.schemaFieldForSelftest("SYSDBA");
                        dp.connectAndList();
                        if (!dp.logPanel().text().contains("没找到达梦 JDBC 驱动")) {
                            problems.add("没有达梦驱动时没说清缺什么与放哪儿："
                                    + dp.logPanel().text());
                        }
                    }
                    dm_ddl_selftest(problems);
                    // 驱动那一格进页就得说话，不用点了连接才知道
                    if (dp.driverText().isEmpty()
                            || (com.excel2mysql.core.DmReader.driverPresent()
                                ? !dp.driverText().startsWith("达梦驱动")
                                : !dp.driverText().contains("缺达梦驱动")
                                    || !dp.driverText().contains(
                                            com.excel2mysql.core.DmReader.driverDir().getPath()))) {
                        problems.add("驱动状态那一格没把话说全：" + dp.driverText());
                    }
                    // 先把模式名填上：有没有随包驱动走的是两条分支，
                    // 灌结构前不填模式，摘要与保存名就各自少一截，判不出同一件事
                    dp.schemaFieldForSelftest("SYSDBA");
                    java.util.List<com.excel2mysql.core.DmMysql.Table> three =
                            new java.util.ArrayList<com.excel2mysql.core.DmMysql.Table>();
                    three.add(dmFixture());
                    three.add(dmFixtureNamed("T_OIL_ITEM", "油料出入库明细"));
                    three.add(dmFixtureNamed("T_PERSON", "人员花名册"));
                    dp.setTablesForSelftest(three);
                    if (dp.tableRowCount() != 3) {
                        problems.add("灌进三份结构后第五页清单不是 3 行：" + dp.tableRowCount());
                    }
                    if (!dp.summaryText().contains("已读回 3 张")
                            || !dp.summaryText().contains("选中 0 张")
                            || dp.summaryText().contains("显示")) {
                        problems.add("未过滤时清单摘要不对：" + dp.summaryText());
                    }
                    if (!dp.generateEnabled()) {
                        problems.add("已读回结构，第五页「生成 MySQL DDL」却还是灰的");
                    }
                    // 过滤：视图行号与读回顺序不是一回事，选中范围全靠这层换算
                    dp.filterForSelftest("item");
                    if (dp.tableRowCount() != 1 || dp.tableIndexAtRow(0) != 1
                            || !dp.summaryText().contains("显示 1")
                            || !dp.summaryText().contains("已读回 3 张")) {
                        problems.add("按表名过滤没筛对：" + dp.tableRowCount() + " 行 / 首行是第 "
                                + dp.tableIndexAtRow(0) + " 张 / " + dp.summaryText());
                    }
                    // 过滤是这一页找表的唯一办法：400 张表下逐键重铺清单必须仍在几十毫秒级，
                    // 否则打字时界面会一顿一顿的（阈值按实测量级放宽一个数量级）
                    java.util.List<com.excel2mysql.core.DmMysql.Table> many =
                            new java.util.ArrayList<com.excel2mysql.core.DmMysql.Table>();
                    for (int mi = 0; mi < 400; mi++) {
                        many.add(dmFixtureNamed("T_OIL_" + mi, "油料台账第 " + mi + " 张"));
                    }
                    dp.setTablesForSelftest(many);
                    long mf0 = System.nanoTime();
                    dp.filterForSelftest("OIL_39");
                    long mfMs = (System.nanoTime() - mf0) / 1000000L;
                    if (dp.tableRowCount() != 11) {
                        problems.add("400 张表下过滤命中数不对：期望 11，实际 " + dp.tableRowCount());
                    }
                    if (mfMs > 200L) {
                        problems.add("400 张表重铺清单要 " + mfMs + " 毫秒，打字会卡（阈值 200）");
                    }
                    dp.setTablesForSelftest(three);
                    dp.clearFilterForSelftest();
                    if (dp.tableRowCount() != 3 || !dp.logPanel().text().contains("已清空过滤")) {
                        problems.add("清空过滤没回到全量或没在日志留痕：" + dp.tableRowCount());
                    }
                    // 改了模式没重连：这份结构已经不代表当前输入，生成要拦下
                    dp.schemaFieldForSelftest("OTHER_SCHEMA");
                    if (!dp.structureIsStale() || !dp.summaryText().contains("连接参数已改动")) {
                        problems.add("改了模式却没标脏：" + dp.summaryText());
                    }
                    if (dp.generate() != null || !dp.logPanel().text().contains("已拦下生成")) {
                        problems.add("结构已脏时生成没被拦下");
                    }
                    dp.schemaFieldForSelftest("SYSDBA");
                    dp.setTablesForSelftest(three);
                    if (dp.structureIsStale()) {
                        problems.add("重新灌入结构后脏标记没清掉");
                    }
                    if (!dp.suggestedSaveName().startsWith("dm_to_mysql_SYSDBA_")
                            || !dp.suggestedSaveName().endsWith(".sql")) {
                        problems.add("默认保存名没带模式与时间：" + dp.suggestedSaveName());
                    }
                    String dmSqlText = dp.generate() == null ? "" : dp.outputText();
                    if (!dmSqlText.contains("CREATE TABLE `t_oil`")
                            || !dmSqlText.contains("PRIMARY KEY (`id`)")
                            || !dmSqlText.contains("COMMENT='油料保障台账'")) {
                        problems.add("第五页生成的 DDL 不像样：" + dmSqlText);
                    }
                    // 写入确认那句要把真正会动的表列出来（弹框由 confirmWrite=false 关掉）
                    if (!dp.writeConfirmPreview().contains("T_OIL")
                            || !dp.writeConfirmPreview().contains("T_PERSON")) {
                        problems.add("写入确认里没列出要动的表：" + dp.writeConfirmPreview());
                    }
                    if (!dp.saveEnabled()) {
                        problems.add("生成完「保存到 .sql」却还是灰的");
                    }
                    java.io.File dmSql = java.io.File.createTempFile("selftest-dm-", ".sql");
                    dmSql.deleteOnExit();
                    if (!dp.saveTo(dmSql) || dmSql.length() <= 0L) {
                        problems.add("第五页保存 .sql 没落盘：" + dmSql.getPath());
                    }
                    // 反向（MySQL → DM8）：翻方向清清单、目标格与按钮跟着换、
                    // 生成的 DDL 是达梦形状（双引号标识符 + COMMENT ON + IDENTITY）
                    dp.setDirectionForSelftest(false);
                    if (dp.isDmToMysql()) {
                        problems.add("方向切到 MySQL → DM8 后没生效");
                    }
                    if (!dp.suggestedSaveName().startsWith("mysql_to_dm_")) {
                        problems.add("反向的默认保存名没带方向前缀：" + dp.suggestedSaveName());
                    }
                    java.util.List<com.excel2mysql.core.DmMysql.Table> myTables =
                            new java.util.ArrayList<com.excel2mysql.core.DmMysql.Table>();
                    myTables.add(myFixture());
                    dp.setTablesForSelftest(myTables);
                    String myDdl = dp.generate() == null ? "" : dp.outputText();
                    if (!myDdl.contains("CREATE TABLE \"t_oil\"")
                            || !myDdl.contains("\"id\" BIGINT IDENTITY(1,1) NOT NULL")
                            || !myDdl.contains("\"name\" VARCHAR(200) NOT NULL")
                            || !myDdl.contains("PRIMARY KEY (\"id\")")) {
                        problems.add("反向生成的达梦 DDL 不像样：" + myDdl);
                    }
                    if (!myDdl.contains("COMMENT ON TABLE \"t_oil\" IS '油料台账'")
                            || !myDdl.contains("COMMENT ON COLUMN \"t_oil\".\"name\" IS '名称'")) {
                        problems.add("达梦注释没走 COMMENT ON：" + myDdl);
                    }
                    mysql_dm_ddl_selftest(problems);
                    // 日志段：几百行也不许长高、不许白占版面（用户报「日志撑满整个工具」）
                    dm_log_bounded(frame, dp, problems);
                }
                // 第四页：页签存在、三按钮默认灰（未载入映射）、ads 依赖锁
                if (frame.layerPage() == null) {
                    problems.add("第四页 layerPage 没挂上");
                } else {
                    com.excel2mysql.gui.LayerPage lp = frame.layerPage();
                    // 源库不再有本页输入口：顶部连接栏选中的那个库就是源库，这一页不许再摆下拉
                    if (hasCombo(lp)) {
                        problems.add("第四页还留着自己的下拉输入（源库应取顶部连接栏所选库）");
                    }
                    String topDb = frame.selectedDatabase();
                    String srcDb = lp.sourceDbName();
                    if (topDb.isEmpty()) {
                        if (!"ods".equals(srcDb)) {
                            problems.add("顶部没选库时第四页源库没按脚本兜底 ods，实际：" + srcDb);
                        }
                    } else if (!topDb.equals(srcDb)) {
                        problems.add("第四页源库没跟着顶部所选库走：顶部 " + topDb + "，第四页 " + srcDb);
                    }
                    // 内置映射模板：第四页一打开就要有可看的列口径，且能原样导出成用户可编辑的 CSV
                    if (lp.mappingRowCount() != 2) {
                        problems.add("第四页没默认载入内置映射模板：应 2 行示例，实际 "
                                + lp.mappingRowCount() + " 行");
                    }
                    if (!lp.mappingLabelText().contains("内置映射模板")) {
                        problems.add("默认模板载入后标签没写明是内置模板：" + lp.mappingLabelText());
                    }
                    java.io.File tplRoot = java.nio.file.Files.createTempDirectory("e2m-layer-tpl").toFile();
                    java.io.File tplFile = new java.io.File(tplRoot, "层间映射模板.csv");
                    if (!lp.exportTemplateTo(tplFile)) {
                        problems.add("第四页导出映射模板失败");
                    } else {
                        try {
                            com.excel2mysql.core.MappingFile tpl = com.excel2mysql.core.MappingFile
                                    .loadTableCsv(tplFile.getPath());
                            if (tpl.tables().size() != 2) {
                                problems.add("导出的映射模板读回不是 2 行示例，实际 " + tpl.tables().size());
                            } else {
                                com.excel2mysql.core.MappingFile.TableMap t0 = tpl.tables().get(0);
                                if (t0.tableEn == null || t0.tableEn.isEmpty()
                                        || t0.domainTarget == null || t0.domainTarget.isEmpty()
                                        || t0.objDims == null || t0.objDims.isEmpty()
                                        || t0.theme == null || t0.theme.isEmpty()) {
                                    problems.add("导出的映射模板示例行关键列（表英文名/领域目标表/涉及对象维度/主题归属）为空");
                                }
                            }
                        } catch (Exception ex) {
                            problems.add("导出的映射模板读不回：" + ex.getMessage());
                        }
                    }
                    deleteTree(tplRoot);
                    if (lp.objButton().isEnabled() || lp.domButton().isEnabled() || lp.adsButton().isEnabled()) {
                        problems.add("只有内置模板、未读源结构时第四页三按钮却可点");
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
                    // 读源库一次会往日志里写几百行；日志区若跟着内容涨，会把表格与拖拽区盖掉
                    layer_log_bounded(frame, lp, problems);
                    // 只生成模式：显式产物根指临时目录（仿 sql_gen_excel 的 generateExcel(true,outRoot)），
                    // 不污染 programDir；modalProgress=false 下 runLayerTask 原地跑完、关态不连库。
                    // 先注入一条字段映射例外，验它在落盘前覆盖进 obj 的 _field_mapping.csv（写回这条路）
                    lp.setFieldExceptionOverride("dim_material", "name", "自检自定义规则", "已确认");
                    java.io.File outRoot = java.nio.file.Files.createTempDirectory("e2m-layer-selftest").toFile();
                    lp.generateTo(com.excel2mysql.core.LayerGen.Layer.OBJ, outRoot.getPath());
                    java.io.File[] dirs = outRoot.listFiles();
                    boolean found = false;
                    boolean exceptionApplied = false;
                    if (dirs != null) {
                        for (java.io.File d : dirs) {
                            if (!d.isDirectory() || !d.getName().startsWith("layer_")) { continue; }
                            if (new java.io.File(d, "obj_ddl.sql").isFile()) { found = true; }
                            java.io.File fm = new java.io.File(d, "obj_field_mapping.csv");
                            if (fm.isFile()) {
                                for (com.excel2mysql.core.MappingFile.FieldMap f
                                        : com.excel2mysql.core.MappingFile.readFieldCsv(fm.getPath())) {
                                    if ("dim_material".equals(f.dstTable) && "name".equals(f.dstField)
                                            && "自检自定义规则".equals(f.transformRule)
                                            && "已确认".equals(f.status)) { exceptionApplied = true; }
                                }
                            }
                        }
                    }
                    if (!found) { problems.add("只生成模式 generateTo(obj) 后没在临时根落下 obj_ddl.sql"); }
                    if (!exceptionApplied) { problems.add("字段映射例外没覆盖进 obj_field_mapping.csv"); }
                    // ads 的 ETL 批次号：不许再是 @src_batch_id 占位符，得是生成这一刻的具体值，
                    // 并在日志里点名这一批（现场拿日志对交付 SQL 靠这一行）
                    lp.generateTo(com.excel2mysql.core.LayerGen.Layer.ADS, outRoot.getPath());
                    if (!lp.logPanel().contains("ads 批次号 etl_batch_id = ")) {
                        problems.add("ads 生成没在日志里点名批次号");
                    }
                    boolean adsChecked = false;
                    java.io.File[] adirs = outRoot.listFiles();
                    if (adirs != null) {
                        for (java.io.File d : adirs) {
                            java.io.File tf = new java.io.File(d, "ads_transform.sql");
                            if (!tf.isFile()) { continue; }
                            String adsSql = new String(java.nio.file.Files.readAllBytes(tf.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            if (adsSql.contains("@src_batch_id")) {
                                problems.add("ads 转换 SQL 的 etl_batch_id 还是 @src_batch_id 占位符");
                            } else if (!adsSql.matches("(?s).*'[0-9]{17}' AS `etl_batch_id`.*")) {
                                problems.add("ads 转换 SQL 的 etl_batch_id 不是具体批次号");
                            }
                            adsChecked = true;
                        }
                    }
                    if (!adsChecked) { problems.add("ads 生成没落下 ads_transform.sql"); }
                    // 依赖锁：仅生成 obj 后 ads 仍不该亮（dom 未生成）——这条锁死 adsBtn.setEnabled(ready)
                    if (lp.adsButton().isEnabled()) {
                        problems.add("仅生成 obj 后 ads 不该亮（dom 未生成）");
                    }
                    // 库名不合规要当场拦下：不许静默产出一份 USE `` / FROM ` `.`t` 的坏交付件
                    layer_db_guard(lp, frame, problems);
                    // RES_LAYER 忙时这一页会改共享状态的入口必须全灰（生成 worker 正读着那份映射与源结构）
                    layer_busy_lock(lp, frame, problems);
                    // 第四、五页的「清除日志」：只清本页，别的页签与页面上的其它东西一概不动
                    log_clear_buttons(frame, lp, problems);
                    // 产物根用完随手清掉：createTempDirectory 不会自己删，而 deleteOnExit 不递归、
                    // 底下那层 layer_*/ 留着它就删不掉父目录（上面那份临时映射夹具 mf 是单文件，deleteOnExit 够用）
                    deleteTree(outRoot);
                }
                // 主按钮的底色必须跟着启用状态走：列表还空着时「开始导入」是禁用的，
                // 涂成强调色会让它看着就能点（现场那句"没导任何东西，点一下颜色才变"）
                if (frame.importButton().isEnabled()) {
                    problems.add("没有待导入的表，「开始导入」却处于启用态");
                }
                if (!com.excel2mysql.gui.Buttons.primaryDisabledColor()
                        .equals(frame.importButton().getBackground())) {
                    problems.add("禁用态的「开始导入」底色不是禁用色，而是 "
                            + frame.importButton().getBackground());
                }
                // 后面几十处旧用例的期望值仍按「两颗大小写都不勾」的口径写；先把首字母与
                // 转小写取消勾选保持口径不变（转大写默认就未勾；三颗的默认态在建窗后已验过），
                // 命名开关自己的用例（naming_switches）会重新勾上再验一遍
                frame.initialsBox().setSelected(false);
                frame.upperBox().setSelected(false);
                frame.lowerBox().setSelected(false);
                // 宽表被拦下时会自动拆出 Excel：自检的"用户文件"就是夹具目录，
                // 按现场口径写会把夹具写花（下一轮又会把 _partN.xlsx 当夹具读进来）
                try {
                    com.excel2mysql.core.ImportParse.splitOutDir =
                            java.nio.file.Files.createTempDirectory("e2m-splitout")
                                    .toFile().getPath();
                } catch (java.io.IOException e) {
                    problems.add("建拆分产物临时目录失败：" + e);
                }
                // 第四页免配模板那条路：拖一堆 Excel/ZIP 进去判领域。
                // 排在拆分产物目录指走之后——这一条真解析夹具，宽表要是按现场口径写回夹具目录就脏了
                layer_domain_guess(frame, dir, problems);
                String addSummary = frame.addFiles(files);
                // 有了待导入的表，同一颗按钮要转回强调色（禁用色留在上面那条断言里钉着）
                if (!frame.importButton().isEnabled()) {
                    problems.add("加载夹具后「开始导入」仍是禁用态");
                }
                if (!com.excel2mysql.gui.Buttons.primaryColor()
                        .equals(frame.importButton().getBackground())) {
                    problems.add("启用后的「开始导入」底色不是强调色，而是 "
                            + frame.importButton().getBackground());
                }
                int tables = frame.tables().size();
                int fileRows = frame.fileModel().getRowCount();
                int rows = frame.fileModel().rowTotal();
                String ddl = frame.ddlText();
                if (tables == 0) {
                    // 后面几十处检查都按下标取表与取行，没夹具就别硬跑：
                    // 给一句能看懂的话，别抛越界
                    problems.add("这个目录里没有可加载的 .xlsx 夹具（自检只认 verify/fixtures 那些 xlsx），"
                            + "本次 addFiles 返回：" + addSummary.replace((char) 10, ' '));
                    for (String p : problems) {
                        System.out.println("x [ERROR] GUI-SELFTEST | " + p);
                    }
                    result[0] = 1;
                    return;
                }
                if (!addSummary.contains("添加 " + files.size() + " 项文件")) {
                    // 期望值取"目录里真有几个夹具"，不是手写的常数：
                    // 加一张夹具就要改一处常量的话，改的人多半会漏，红成"汇总没数清"
                    problems.add("添加结果汇总没数清文件：" + addSummary.replace((char) 10, ' '));
                }
                if (!frame.logPanel().contains("[添加结果] 添加 " + files.size() + " 项文件")) {
                    problems.add("弹窗那句话没同时写进日志");
                }
                if (!"添加结果".equals(frame.lastDialogTitle())) {
                    problems.add("添加结果弹窗标题不对：" + frame.lastDialogTitle());
                }
                if (!frame.lastDialogText().contains("添加 " + files.size() + " 项文件")) {
                    problems.add("弹窗正文与日志不是同一句话：" + frame.lastDialogText());
                }
                // Python 的添加结果用 information，只有任务异常才换 critical 图标
                if (frame.lastDialogIsError()) {
                    problems.add("添加结果弹窗不该用出错图标");
                }
                // 中途取消：这句话要在弹窗正文里，只写日志的话只看弹窗的人以为整批跑完了
                com.excel2mysql.core.ImportParse.Outcome part = new com.excel2mysql.core.ImportParse.Outcome();
                part.addedLoose.add("a.xlsx");
                part.addedLoose.add("b.xls");
                part.failed = 1;
                part.cancelled = true;
                String partSummary = frame.finishAdd(part);
                if (!partSummary.startsWith("已取消，只完成了部分解析")) {
                    problems.add("取消的话没进正文：" + partSummary.replace((char) 10, ' '));
                }
                if (!partSummary.contains("添加 2 项文件")) {
                    problems.add("取消时把已解析的汇总顶掉了：" + partSummary.replace((char) 10, ' '));
                }
                if (!flat(frame.lastDialogText()).equals(flat(partSummary))) {
                    problems.add("取消后的弹窗正文和汇总不是同一句话");
                }
                if (!frame.logPanel().contains("[添加结果] 已取消，只完成了部分解析")) {
                    problems.add("取消后的汇总没同时写进日志");
                }
                // 有拒绝的「添加结果」必须算告警级：那句"另有 …"写在续行上，
                // 定级只看首行就会把一批被拒绝的添加报成绿色成功（截图复核抓到的）
                frame.log("[添加结果] 自检续行定级" + com.excel2mysql.core.LogText.NL
                        + "另有 1 项解析失败，详见日志");
                if (!"warn".equals(frame.logPanel().levelOfLine("自检续行定级"))) {
                    problems.add("有拒绝的「添加结果」没按告警级定级："
                            + frame.logPanel().levelOfLine("自检续行定级"));
                }
                if (tables != files.size()) {
                    problems.add("表数期望 " + files.size() + "，实际 " + tables
                            + "（有文件解析失败，看日志）");
                }
                if (rows == 0) {
                    problems.add("字段总数为 0，模型是空的");
                }
                // 表数是 0 时别再按下标取行：现场会把这个目录换成自己的数据目录来跑自检，
                // 那时要看到的是"表数不符"这条人话，而不是一个越界异常
                if (tables > 0 && !frame.fileModel().tooltipAt(0).endsWith(".xlsx")) {
                    problems.add("文件列没给完整路径：" + frame.fileModel().tooltipAt(0));
                }
                // 文件表是"一行一个文件"，不是"一行一个字段"，两者搞混说明改造没做完
                if (fileRows != tables) {
                    problems.add("文件表应一行一个文件：" + fileRows + " 行 / " + tables + " 张表");
                }
                if (!ddl.contains("CREATE TABLE")) {
                    problems.add("DDL 预览里没有 CREATE TABLE");
                }
                if (!ddl.contains("PRIMARY KEY")) {
                    problems.add("DDL 预览里没有主键，自动选主键没生效");
                }
                // 两行合并表头是唯一"带合并区却放行"的形状：展开坏了要么直接解析失败
                // （上面表数会红），要么字段名还是第一行的组名。夹具不在目录里时跳过，
                // 让自检还能指向现场自己的数据目录跑
                boolean hasMergedFixture = false;
                for (String p : files) {
                    if (p.endsWith("两行合并表头（ods_t_merged_header）.xlsx")) {
                        hasMergedFixture = true;
                        break;
                    }
                }
                if (hasMergedFixture) {
                    if (!frame.logPanel().contains("合并表头按 2 行展开")) {
                        problems.add("两行合并表头没在[已解析]里说明按几行展开");
                    }
                    if (!ddl.contains("ji_shu_gong_si_xiao_ji") || !ddl.contains("jsgsptyg")) {
                        problems.add("两行合并表头没有按「父-子」展开成字段名");
                    }
                }
                // 合并表头只认标准两行：三行表头、第 2 行里的子合并、2x2 块都必须拒绝。
                // 这些形状不能放进夹具目录（会被当成普通夹具要求建表），所以直接喂
                // headerPlan，守住 Java 侧与 Python 同一条放行边界。
                java.util.Map<String, List<String>> canonical =
                        new java.util.LinkedHashMap<String, List<String>>();
                canonical.put("原始表", Arrays.asList("A1:A2", "B1:C1"));
                com.excel2mysql.core.MergeCheck.HeaderPlan plan =
                        com.excel2mysql.core.MergeCheck.headerPlan(canonical, "原始表");
                if (plan == null || plan.depth != 2) {
                    problems.add("标准两行合并表头被误拒了");
                }
                java.util.Map<String, List<String>> threeRows =
                        new java.util.LinkedHashMap<String, List<String>>();
                threeRows.put("原始表", Arrays.asList("A1:A3"));
                if (com.excel2mysql.core.MergeCheck.headerPlan(threeRows, "原始表") != null) {
                    problems.add("三行合并表头不该被当成标准两行放行");
                }
                java.util.Map<String, List<String>> childMerge =
                        new java.util.LinkedHashMap<String, List<String>>();
                childMerge.put("原始表", Arrays.asList("A1:A2", "B1:C1", "B2:C2"));
                if (com.excel2mysql.core.MergeCheck.headerPlan(childMerge, "原始表") != null) {
                    problems.add("第 2 行里的子合并不该被放行");
                }
                java.util.Map<String, List<String>> block =
                        new java.util.LinkedHashMap<String, List<String>>();
                block.put("原始表", Arrays.asList("A1:A2", "B1:C2"));
                if (com.excel2mysql.core.MergeCheck.headerPlan(block, "原始表") != null) {
                    problems.add("2x2 合并块不该被放行");
                }
                // 表数的可见痕迹是文件表行数与日志里那句汇总（Python 没有状态栏），
                // 两者已分别在上面断过，这里不再断自造的状态文字
                // 文件表可就地编辑的是「表名」与「表注释」两列（双击进编辑器，与第二页同一交互）；
                // 字段级的改动仍走双击结构弹窗
                if (!frame.fileModel().isCellEditable(0, 2)
                        || !frame.fileModel().isCellEditable(0, 3)
                        || frame.fileModel().isCellEditable(0, 0)
                        || frame.fileModel().isCellEditable(0, 1)
                        || frame.fileModel().isCellEditable(0, 6)) {
                    problems.add("文件表应只有表名与表注释两列可就地编辑");
                }
                int serialRows = frame.fileModel().getRowCount();
                if (!"1".equals(frame.fileModel().getValueAt(0, 0))
                        || !String.valueOf(serialRows).equals(
                                frame.fileModel().getValueAt(serialRows - 1, 0))) {
                    problems.add("文件表序号列没按行号连续编号");
                }
                fileRename(frame, problems);
                // 表注释也走就地编辑：双击那一格改，改完留回执
                fileComment(frame, problems);
                // 结构弹窗要能真的改到表定义：改字段类型后 DDL 必须跟着变
                table_select_for_dialog(frame, problems);
                // 多表标签页弹窗：校验不过不能先写回，否则表定义停在改了一半的状态
                structure_dialog(frame, problems);
                // 前缀两个按钮与「覆盖已存在表」勾选：文案、幂等、跳过口径都要和 Python 一致
                prefix_and_labels(frame, problems);
                // 前缀行那一排：输入框与两颗按钮的可见高度必须齐平
                prefix_row_heights(frame, problems);
                // 生成按钮的悬浮提示只许一行，多行会盖住同一排右边的按钮
                gen_tips_stay_short(frame, problems);
                // 拖拽区与页内进度条：真拖进来走的必须是和选择器同一个入口
                drop_and_progress(frame, files, problems);
                // 导入收尾：成功文件移出、失败保留，日志与弹窗用的是同一句话
                import_finish(frame, files, problems);
                // 「开始导入」的两道闸：没表不管、没选库弹窗，且都不许把任务位占死
                import_guard(frame, files, problems);
                // 首屏分栏：表格是主工作面，表格段要比日志段高
                split_defaults(frame, problems);
                // SQL 页签：读盘、改表名、落盘副本、整份清空，全离线可验
                sql_page(frame, problems);
                // 粘贴添加：起的是真解析任务，放在 Excel 页所有断言之后跑
                paste_probe(frame, files, problems);
                // 第三个页签「SQL 执行器」：编辑区、示例清单与空输入那道闸
                sql_run_tab(frame, problems);
                // 主日志区的字号：现场反馈 12 号基本看不清，这里钉住共同的那一档
                checkLogFonts(frame, problems);
                // 第二个页签的两颗生成按钮：解析脚本 → 标准/简易件 → 回读
                sql_gen_excel(frame, problems);
                // 双击一行的耗时：读入时缓存字段定义，点开只取那一张（含改表名后的重挂）
                sql_metadata_cache(frame, problems);
                // 表注释手动改：写回 CREATE TABLE 的表选项，并一路带到弹窗与日志回执
                sql_comment_edit(frame, problems);
                sql_field_edit(frame, problems);
                sql_scope(frame, problems);
                // 页面级「停止」与并发护栏
                stop_button(frame, problems);
                // 程序同级 conn.ini 预填连接信息：读到 / 读不了 / 没有，三种说法各验一条
                conn_ini(frame, problems);
                // 并发护栏按资源位判：不同页可以一起跑，同一页与共用产物的仍然互斥
                task_slots(frame, files, problems);
                // 进度框文案与收尾
                progress_copy(problems);
                // DDL 逐表分页、文件表列宽、端口范围
                ddl_dialog_and_widgets(frame, files, problems);
                // 导入那条路的文案与计项口径
                import_run(problems);
                // 分层入库预检的纯函数：DDL 类型解析、数值样本判定、源表与聚合识别
                layer_gen_precheck(problems);
                // 三层表注释口径：中文证据成句、不写英文表名、不带待核字眼
                layer_table_comments(problems);
                sql_run_connect_failure(problems);
                // 子线程出意外时的兜底文案
                task_crash(frame, problems);
                // ZIP 展开、汇总计数与临时目录清理
                zip_roundtrip(frame, files, problems);
                // 压缩包的三条现场形状：GBK 条目名、ZIP64 中央目录、连续多批拖入的落点编号
                zip_field_shapes(frame, files, problems);
                // 宽表被行上限拦下后按公共字段拆成多个 Excel（与 Python wide_split 同口径）
                wide_split(frame, problems);
                // 降级的两条底线：键列不许动、明知救不回来别白改（与 core.infer 同口径）
                row_fit_guards(problems);
                // 单表行数过阈值时换成流式写：内存不随行数涨，整数怎么落盘要有说法
                stream_export(problems);
                // 建不出表的输入必须在解析阶段就拒（空表 / 只有表头 / 内容是文本的假 .xlsx）
                checkRejects(frame, problems);
                // 命令行的 --prefix 对 .sql 也要生效，且 dry-run 要能预览脚本
                checkSqlCliPrefix(problems);
                // 「只写注释也不算」的判据表，与 Python 侧 pack_test 的 E10/E11 同一张
                checkCommentOnly(problems);
                // 整库 dump 的识别、切句（DELIMITER / 版本注释）与只有删库语句跳过
                checkDumpScript(problems);
                // 日志面板的线程安全：工作线程加行的同时有人在遍历，锁没加对就会炸 CME，
                // 那个异常会把日志刷新整个带走——现场看到的就是"跑到一半日志不动了"
                checkLogPanelThreadSafe(problems);
                // 超出 BIGINT 的整数要原样发进库，不能让驱动截成负数
                checkBigIntWire(problems);
                // 1264 回归：浮点整数溢出、金额精度被压、字符串金额经 float 进位
                checkInfer1264(frame.names(), problems);
                // 表头语义修正的另两层：码值按文本、物质量按 DECIMAL（与 pack_test L15–L26 同一批）
                checkInferSemantics(frame.names(), problems);
                // 「生成标准/简易 excel 文件」两颗按钮：落点、产物形状、回读与日志弹窗
                meta_export(frame, files, problems);
                // 标题版本号、说明窗口与关于框
                help_and_about(frame, problems);
                // 死按钮守卫：每个按钮都必须挂了监听器，界面上出现点了没反应的按钮
                // 比少一个按钮更糟——用户会以为是自己操作错了
                for (javax.swing.AbstractButton b : frame.buttons()) {
                    if (b.getActionListeners().length == 0) {
                        problems.add("按钮没有绑定动作：" + b.getText());
                    }
                }
                // 自造文案清掉之后不许长回来：状态栏那几行 Python 根本没有，
                // 连接状态只由绿/红提示条说（main.py:861-878 那条框里只有输入框和按钮）
                List<String> labelTexts = new ArrayList<String>();
                collectLabels(frame.getContentPane(), labelTexts);
                for (String bad : new String[]{"尚未连接", "待导入 ", "尚未添加文件"}) {
                    for (String l : labelTexts) {
                        if (l.contains(bad)) {
                            problems.add("界面上又长出 Python 没有的状态文字：" + l);
                        }
                    }
                }
                // 「开始导入」的启用条件与 Python 同：有表就可点，没选库由入口自己拦下并弹提示，
                // 而不是把按钮一直灰着——灰着的按钮会让人以为程序坏了
                frame.clearAll();
                frame.addFiles(files);
                javax.swing.AbstractButton importBtn = null;
                for (javax.swing.AbstractButton b : frame.buttons()) {
                    if ("开始导入".equals(b.getText())) {
                        importBtn = b;
                    }
                }
                if (importBtn == null) {
                    problems.add("界面上没有「开始导入」按钮");
                } else {
                    if (frame.isConnected() || !importBtn.isEnabled()) {
                        problems.add("有表但没连库时「开始导入」应可点（与 Python 同口径），实际 enabled="
                                + importBtn.isEnabled() + " connected=" + frame.isConnected());
                    }
                    frame.clearAll();
                    if (importBtn.isEnabled()) {
                        problems.add("列表空着时「开始导入」还是可点");
                    }
                }
                // 命名开关（全拼/首字母、小写/大写）与空列按字段名定类型：勾了再添加就要当场生效。
                // 放在最后跑：它会往列表里加临时表，前面的用例都按下标取夹具表，不能被插队
                naming_switches(frame, problems);
                // 「自动补全主键」：与命名开关同一档——勾上就要在清单、建表语句与数据网格同时看得见
                autoPkSwitches(frame, problems);
                // 添加文件弹窗：文件名框里敲文件夹路径回车必须进目录，不能把目录当文件带回来
                folder_path_entry(problems);
                lookin_path_entry(problems);
                // 日志的红色与行数上限：错误没标红、或日志无限涨，都只会在现场翻车时才发现
                frame.logError("自检错误行");
                frame.log("自检普通行");
                if (!frame.logPanel().isErrorLine("自检错误行")) {
                    problems.add("logError 写出来的行没被标成错误级");
                }
                if (frame.logPanel().isErrorLine("自检普通行")) {
                    problems.add("普通日志被误标成错误级");
                }
                sql_exec_progress(problems);
                sql_exec_result_sets(problems);
                log_line_shape(frame, problems);
                log_pane_height(frame, problems);
                for (int i = 0; i < frame.logPanel().maxLines() + 50; i++) {
                    frame.log("灌行 " + i);
                }
                if (frame.logPanel().lineCount() > frame.logPanel().maxLines()) {
                    problems.add("日志没裁到上限：" + frame.logPanel().lineCount());
                }
                // 进度框按阶段计项：走三步必须三步都记，且总项数一次算准
                com.excel2mysql.gui.ProgressDialog pd =
                        new com.excel2mysql.gui.ProgressDialog(null, "自检");
                pd.setTotal(3);
                pd.step("a");
                pd.step("b");
                pd.step("c");
                if (pd.steps() != 3 || !pd.isFinished()) {
                    problems.add("进度框计项不对：steps=" + pd.steps()
                            + " finished=" + pd.isFinished());
                }
                if (pd.isCancelled()) {
                    problems.add("进度框不该初始就是取消态");
                }
                pd.dispose();
                metrics[0] = fileRows;
                metrics[1] = rows;
            } catch (Throwable e) {
                // 中途抛异常必须换成非 0 退出码：只把栈打在控制台上、闸门照样绿，比没闸门更糟
                System.out.println("x [ERROR] GUI-SELFTEST | 自检中途抛异常："
                        + e.getClass().getName() + " " + e.getMessage());
                result[0] = 1;
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("x [ERROR] GUI-SELFTEST | 被中断");
            return 1;
        }
        // 真线程那一支：进度框那条路（真子线程 + 真非模态框 + 真写盘）跑两条任务，
        // 这一段自己站在非事件分发线程上
        if (result[0] == 0 && holder[0] != null) {
            try {
                concurrent_tasks(holder[0], problems);
            } catch (Throwable e) {
                problems.add("真线程并发自检意外中止：" + e.getClass().getName()
                        + " " + e.getMessage());
            }
            try {
                click_other_page_while_busy(holder[0], problems);
            } catch (Throwable e) {
                problems.add("另一页点击取证意外中止：" + e.getClass().getName()
                        + " " + e.getMessage());
            }
            try {
                conn_load_task(holder[0], problems);
            } catch (Throwable e) {
                problems.add("连接加载长任务自检意外中止：" + e.getClass().getName()
                        + " " + e.getMessage());
            }
        }
        if (problems.isEmpty()) {
            System.out.println("+ [OK] GUI-SELFTEST | 窗口构造成功，文件表 " + metrics[0]
                    + " 行 / 字段合计 " + metrics[1]
                    + " 个，DDL 预览含建表语句与主键，改类型能反映到预览；"
                    + "两条真任务同时在跑、界面照旧响应");
            for (String s : SHOTS) {
                System.out.println("- [INFO] GUI-SELFTEST | 并发留影 " + s);
            }
        } else {
            for (String p : problems) {
                System.out.println("x [ERROR] GUI-SELFTEST | " + p);
            }
            result[0] = 1;
        }
        return result[0];
    }

    /**
     * 文件表双击「表注释」改注释：写回这张表的定义并留回执，
     * 只多打空格那种"其实没改"也要说话（与第二页同一口径）。
     */
    private static void fileComment(MainFrame frame, List<String> problems) {
        String before = frame.tables().get(0).tableComment == null
                ? "" : frame.tables().get(0).tableComment;
        frame.fileModel().setValueAt(before + "  ", 0, 3);
        if (!frame.logPanel().contains("[表注释] " + frame.tables().get(0).tableName
                + " 未发生变化")) {
            problems.add("注释其实没改（只多打空格）时没有回执");
        }
        if (!before.equals(frame.tables().get(0).tableComment)) {
            problems.add("没改内容的编辑不该动表定义：" + frame.tables().get(0).tableComment);
        }
        frame.fileModel().setValueAt("探针注释", 0, 3);
        if (!"探针注释".equals(frame.tables().get(0).tableComment)
                || !frame.logPanel().contains("[表注释] " + frame.tables().get(0).tableName
                + " → 探针注释")) {
            problems.add("双击改表注释没写回定义或没留痕：" + frame.tables().get(0).tableComment);
        }
        frame.fileModel().setValueAt("", 0, 3);
        if (!"".equals(frame.tables().get(0).tableComment)
                || !frame.logPanel().contains("→ （空）")) {
            problems.add("清空表注释要允许，并写明改成了空");
        }
        frame.fileModel().setValueAt(before, 0, 3);
    }

    /** 文件表双击表名改名：改到基础表名，四类非法输入都恢复原名。 */
    private static void fileRename(MainFrame frame, List<String> problems) {
        String before = frame.tables().get(0).tableName;
        String renamed = before + "_probe";
        frame.fileModel().setValueAt(renamed, 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !renamed.equals(frame.tables().get(0).baseTableName)) {
            problems.add("文件表改名没同时更新基础表名");
        }
        if (!frame.logPanel().contains("[表名] " + before + " → " + renamed
                + "（手动修改，后续批量前缀基于新名计算）")) {
            problems.add("文件表改名没写进日志");
        }
        frame.fileModel().setValueAt("", 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !frame.lastDialogText().contains("表名不能为空")) {
            problems.add("空表名没被拦下并恢复原名");
        }
        frame.fileModel().setValueAt("bad name", 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !frame.lastDialogText().contains("含非法字符")) {
            problems.add("含空格的表名没被拦下");
        }
        String dup = frame.tables().get(1).tableName;
        frame.fileModel().setValueAt(dup, 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !frame.lastDialogText().contains("重名")) {
            problems.add("与其他表重名没被拦下");
        }
        frame.fileModel().setValueAt(before, 0, 2);
        if (!before.equals(frame.tables().get(0).tableName)
                || !before.equals(frame.tables().get(0).baseTableName)) {
            problems.add("文件表表名改回原名失败");
        }
    }

    /**
     * 不弹窗（模态框会卡住自检），直接验 StructureDialog 依赖的那条链路：
     * 改 columns 里的类型 -> 重算 DDL 应该带上新的类型。
     */
    private static void table_select_for_dialog(MainFrame frame, List<String> problems) {
        com.excel2mysql.core.Infer.Column first =
                frame.tables().get(0).columns.get(0);
        String beforeType = first.type;
        String ddlBefore = frame.ddlText();
        first.type = "VARCHAR(123)";
        frame.refreshPreview();
        if (!frame.ddlText().contains("VARCHAR(123)")) {
            problems.add("改了字段类型但 DDL 预览没跟着变");
        }
        if (ddlBefore.equals(frame.ddlText())) {
            problems.add("DDL 预览在数据变了之后文本没变");
        }
        first.type = beforeType;
        frame.refreshPreview();
    }

    /**
     * 结构弹窗的三件事：标签页一张表一页、非法主键拦住且不写回、合法改动确实落库到表定义。
     * 和 Python 侧同一条约束——先收副本、全校验通过再统一写回。
     */
    private static void structure_dialog(MainFrame frame, List<String> problems) {
        java.util.List<com.excel2mysql.core.TableBuilder.Result> results = frame.results();
        if (results.size() != frame.tables().size() || results.contains(null)) {
            problems.add("results() 与表数量不一致，结构弹窗拿不到数据行");
            return;
        }
        // 故意把第一张表的第一个字段名与注释拉长，再建弹窗：列宽写死时超长那一格
        // 是被切掉的那一个，而切掉的半截滚不到——这条量的就是"切没切"
        com.excel2mysql.core.Infer.Column longCol = frame.tables().get(0).columns.get(0);
        final String keepField = longCol.field;
        final String keepComment = longCol.comment;
        StringBuilder wide = new StringBuilder("zhe_shi_yi_ge_chao_ji_chang_de_zi_duan_ming");
        while (wide.length() < 120) {
            wide.append("_hai_you_geng_chang");
        }
        longCol.field = wide.toString();
        longCol.comment = wide.toString();
        com.excel2mysql.gui.StructureDialog dlg =
                new com.excel2mysql.gui.StructureDialog(null, results, frame.logPanel());
        dlg.pack();
        longCol.field = keepField;
        longCol.comment = keepComment;
        for (int c = 0; c < 3; c++) {
            if (!dlg.columnFitsContent(0, c)) {
                problems.add("结构弹窗第 " + c + " 列摆出来的宽度装不下内容："
                        + "超长字段名被切进省略号，滚也滚不回来");
            }
        }
        if (!dlg.scrollsHorizontally(0)) {
            problems.add("结构弹窗的字段表还开着列自适应：字段名长一点就被挤成省略号，没得滚");
        }
        // 同一档的另一半：内容装得下时必须铺满视口，不能把富余宽度空在表格右边
        com.excel2mysql.gui.StructureDialog filled =
                new com.excel2mysql.gui.StructureDialog(null, results, frame.logPanel());
        filled.pack();
        if (!filled.columnsFillViewport(0)) {
            problems.add("结构弹窗内容装得下时没铺满视口：表格右侧留着一条白");
        }
        filled.dispose();
        if (dlg.tabTitles().size() != results.size()) {
            problems.add("结构弹窗标签页数不等于表数：" + dlg.tabTitles().size());
        }
        // 这张表不重复加标识：tab 上写的就是表名，已经贴在表格正上方
        if (!com.excel2mysql.gui.Tables.titledAncestor(dlg.tabTable(0)).isEmpty()) {
            problems.add("结构弹窗的字段表上又多了一层标识："
                    + com.excel2mysql.gui.Tables.titledAncestor(dlg.tabTable(0)));
        }
        if (!"字段名(拼音/首字母)".equals(dlg.columnHeader(0, 1))
                || !"字段注释(中文表头)".equals(dlg.columnHeader(0, 2))) {
            problems.add("结构弹窗表头被简写了：" + dlg.columnHeader(0, 1) + " / "
                    + dlg.columnHeader(0, 2));
        }
        // 末列写的是数据实测值，不是「数据类型」的声明容量；列名少了"表内数据"就会被读成后者
        if (!"表内数据最大字节长度".equals(dlg.columnHeader(0, 4))) {
            problems.add("结构弹窗第 4 列表头不是「表内数据最大字节长度」："
                    + dlg.columnHeader(0, 4));
        }
        if (!"数据类型".equals(dlg.columnHeader(0, 3))) {
            problems.add("结构弹窗的类型列没与第二页、两份生成件统一叫「数据类型」："
                    + dlg.columnHeader(0, 3));
        }
        if (dlg.cellEditable(0, 0, 4)) {
            problems.add("「表内数据最大字节长度」还能编辑：改了从不回写，留着可编辑就是静默 no-op");
        }
        if (!dlg.cellEditable(0, 0, 1)) {
            problems.add("字段名列不可编辑，弹窗就白摆");
        }
        // 表注释的入口回到清单里（双击那一格），弹窗不再摆一个输入框：
        // 两处都能改同一件事，就会有两处"到底以谁为准"的口径
        if (dlg.hasCommentField()) {
            problems.add("结构弹窗里还留着表注释输入框");
        }
        String tableCommentBefore = frame.tables().get(0).tableComment;
        // 字段改动按确定才落回，表注释原样不动
        dlg.setModelValue(0, 0, 2, "改了但没按确定");
        if (!tableCommentBefore.equals(frame.tables().get(0).tableComment)) {
            problems.add("弹窗没按确定却动了表注释");
        }
        // 点 X / 按 Esc 关掉要等同"取消"：没按确定就绝不能把编辑写回表定义
        com.excel2mysql.gui.StructureDialog dismissed =
                new com.excel2mysql.gui.StructureDialog(null, results, frame.logPanel());
        String commentBefore = frame.tables().get(0).columns.get(0).comment;
        dismissed.setModelValue(0, 0, 2, "改了但没按确定");
        dismissed.dispose();
        if (dismissed.isConfirmed()) {
            problems.add("没按确定却算已确认");
        }
        if (!dismissed.closesOnEscape()) {
            problems.add("Esc 没绑成\"关掉等同取消\"，Qt 的 QDialog 按 Esc 是 reject");
        }
        if (dismissed.getDefaultCloseOperation() != javax.swing.WindowConstants.DISPOSE_ON_CLOSE) {
            problems.add("点 X 的默认动作不是销毁窗口，和取消不是一回事");
        }
        if (!commentBefore.equals(frame.tables().get(0).columns.get(0).comment)) {
            problems.add("只关掉没按确定，编辑却写回了表定义");
        }
        com.excel2mysql.core.Infer.Column c0 = frame.tables().get(0).columns.get(0);
        String fieldBefore = c0.field;
        String typeBefore = c0.type;
        boolean pkBefore = c0.primaryKey;

        dlg.setModelValue(0, 0, 3, "LONGTEXT");
        dlg.setModelValue(0, 0, 0, Boolean.TRUE);
        if (dlg.pressOk()) {
            problems.add("LONGTEXT 勾成主键却被放行，主键校验没起作用");
        }
        if (frame.tables().get(0).columns.get(0) != c0) {
            problems.add("校验没过却已写回新列对象，表定义停在改了一半的状态");
        }
        if (!fieldBefore.equals(c0.field) || !typeBefore.equals(c0.type)
                || c0.primaryKey != pkBefore) {
            problems.add("校验没过但字段名/类型/主键已经被改动");
        }

        String probe = "自检注释";
        dlg.setModelValue(0, 0, 2, probe);
        dlg.setModelValue(0, 0, 3, typeBefore);
        dlg.setModelValue(0, 0, 0, Boolean.valueOf(pkBefore));
        if (!dlg.pressOk()) {
            problems.add("合法改动被拦住：" + String.join(" | ", dlg.problems()));
        } else if (!probe.equals(frame.tables().get(0).columns.get(0).comment)) {
            problems.add("弹窗确认后注释没写回表定义");
        }

        // 用户最常见的敲法是按完字直接点「保存」，不按回车也不点别处：这一格此时还开着
        // 编辑器，模型里是旧值。Swing 默认不提交，改动就静默丢掉（用户 2026-11-11 报的第 2 条）。
        com.excel2mysql.gui.StructureDialog pending =
                new com.excel2mysql.gui.StructureDialog(null, results, frame.logPanel());
        pending.pack();
        if (!pending.commitsOnFocusLost(0)) {
            problems.add("结构弹窗的字段表没开\"焦点一离开就提交\"：敲完直接点保存会丢改动");
        }
        String pendingText = "敲完没提交就点保存";
        pending.typeIntoCell(0, 0, 2, pendingText);
        if (pending.modelValue(0, 0, 2).equals(pendingText)) {
            problems.add("自检没造出\"编辑器还挂着\"的状态，这条等于没验");
        }
        if (!pending.pressOk()) {
            problems.add("挂着未提交的编辑器时保存被拦住：" + String.join(" | ", pending.problems()));
        } else if (!pendingText.equals(frame.tables().get(0).columns.get(0).comment)) {
            problems.add("挂着未提交的编辑器点保存，注释写回的是旧值："
                    + frame.tables().get(0).columns.get(0).comment);
        }
        pending.dispose();

        // 横扫同类：界面上每一张表都要开这一档，漏一张就是漏一处"敲完点别处就丢改动"。
        // 判据走真控件，不数调用点——谁新建一张表忘了过 Tables.style，这条就红。
        java.util.List<javax.swing.JTable> everyTable = new java.util.ArrayList<javax.swing.JTable>();
        collectTables(frame, everyTable);
        for (java.awt.Window w : fitCheckDialogs(frame)) {
            collectTables(w, everyTable);
            w.dispose();
        }
        int unforgiving = 0;
        for (javax.swing.JTable t : everyTable) {
            if (!com.excel2mysql.gui.Tables.terminatesEditOnFocusLost(t)) {
                unforgiving++;
            }
        }
        if (everyTable.size() < 4) {
            problems.add("横扫只找到 " + everyTable.size() + " 张表，这条判据没覆盖到界面");
        }
        if (unforgiving > 0) {
            problems.add("界面上有 " + unforgiving + " 张表没开\"焦点一离开就提交\"");
        }

        // 空表（0 数据行）手工指定主键：非空与唯一在空集上天然成立，应放行；
        // 类型门槛不能因此跳过。
        java.util.List<com.excel2mysql.core.Infer.Column> emptyCols =
                new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        com.excel2mysql.core.Infer.Column emptyPk = new com.excel2mysql.core.Infer.Column();
        emptyPk.field = "bian_hao";
        emptyPk.comment = "编号";
        emptyPk.type = "VARCHAR(64)";
        emptyPk.primaryKey = true;
        emptyCols.add(emptyPk);
        String emptyOk = com.excel2mysql.core.TableBuilder.validatePrimaryKey(
                java.util.Collections.<java.util.List<Object>>emptyList(), emptyCols);
        if (!emptyOk.isEmpty()) {
            problems.add("空表手工主键被拒：" + emptyOk);
        }
        emptyPk.type = "LONGTEXT";
        String emptyBad = com.excel2mysql.core.TableBuilder.validatePrimaryKey(
                java.util.Collections.<java.util.List<Object>>emptyList(), emptyCols);
        if (emptyBad.isEmpty()) {
            problems.add("空表主键类型校验被跳过，LONGTEXT 也被放行");
        }
    }

    private static java.util.List<String> namesOf(MainFrame frame) {
        return com.excel2mysql.core.TableOps.namesOf(frame.tables());
    }

    /** 按钮文案、前缀的幂等与跳过口径、勾选状态到 DDL 预览的传导。 */
    private static void prefix_and_labels(MainFrame frame, List<String> problems) {
        java.util.Set<String> texts = new java.util.LinkedHashSet<String>();
        for (javax.swing.AbstractButton b : frame.buttons()) {
            texts.add(b.getText());
        }
        String[] wanted = {"清空待上传文件", "生成表结构预览", "预览 DDL", "清除日志",
                "批量应用前缀", "替换首个前缀", "连接并加载库", "开始导入",
                "生成标准excel文件", "生成简易excel文件"};
        for (String w : wanted) {
            if (!texts.contains(w)) {
                problems.add("界面缺少按钮：" + w);
            }
        }
        if (texts.contains("添加文件")) {
            problems.add("「添加文件」按钮应已移除，加文件走点击虚线区");
        }
        if (frame.tables().isEmpty()) {
            problems.add("前缀自检没有可改的表");
            return;
        }

        List<String> base = new ArrayList<String>();
        for (com.excel2mysql.core.TableOps.Table t : frame.tables()) {
            base.add(t.baseTableName);
        }
        frame.prefixField().setText("ods_");
        frame.applyPrefix();
        List<String> prefixed = namesOf(frame);
        for (String n : prefixed) {
            if (!n.startsWith("ods_")) {
                problems.add("应用前缀后表名没带上 ods_：" + n);
            }
        }
        if (!frame.logPanel().contains(
                "[前缀] 已为 " + base.size() + " 张表名批量添加前缀「ods_」")) {
            problems.add("批量应用前缀没有留下日志");
        }
        frame.applyPrefix();
        if (!namesOf(frame).equals(prefixed)) {
            problems.add("重复「批量应用前缀」把前缀叠上了：" + namesOf(frame));
        }
        frame.prefixField().setText("");
        frame.applyPrefix();
        if (!namesOf(frame).equals(base)) {
            problems.add("留空再点「批量应用前缀」没恢复基础表名：" + namesOf(frame));
        }
        if (!frame.logPanel().contains(
                "[前缀] 已移除前缀，" + base.size() + " 张表恢复基础表名")) {
            problems.add("移除前缀没有留下日志");
        }

        frame.prefixField().setText("ods_");
        frame.applyPrefix();
        List<String> beforeReplace = namesOf(frame);
        frame.prefixField().setText("change");
        frame.replaceFirstPrefix();
        List<String> afterReplace = namesOf(frame);
        if (afterReplace.equals(beforeReplace)) {
            problems.add("「替换首个前缀」一个表名都没改");
        }
        for (String n : afterReplace) {
            if (!n.startsWith("change_")) {
                problems.add("换首段后表名不是 change_ 开头：" + n);
            }
        }
        if (!frame.logPanel().contains("[换前缀完成] 共改 " + afterReplace.size() + " 张表")) {
            problems.add("替换首个前缀没有汇总改了几张表");
        }
        // 没有下划线的表名要跳过并写明，不能静默少改
        frame.prefixField().setText("ods");
        frame.applyPrefix();
        frame.prefixField().setText("change");
        int n1 = namesOf(frame).size();
        frame.replaceFirstPrefix();
        if (namesOf(frame).size() != n1) {
            problems.add("替换首段改变了表的数量");
        }

        frame.dropExistingBox().setSelected(true);
        frame.refreshPreview();
        if (!frame.ddlText().contains("DROP TABLE IF EXISTS")) {
            problems.add("勾了「覆盖已存在表」但 DDL 预览里没有 DROP");
        }
        frame.dropExistingBox().setSelected(false);
        frame.refreshPreview();
        if (frame.ddlText().contains("DROP TABLE IF EXISTS")) {
            problems.add("没勾「覆盖已存在表」DDL 预览里却出现了 DROP");
        }
    }

    /**
     * 三处交互引导：拖拽区有底色与悬停色、空表画引导语、覆盖开关与执行按钮同一块底板。
     * 空表那句要在像素上真的出现——只断言字符串相等，改坏了绘制路径也照样绿。
     */
    private static void ui_guidance(MainFrame frame, List<String> problems) {
        String fileFill = hex(frame.dropArea().fillColor());
        String fileHover = hex(frame.dropArea().hoverFillColor());
        if (!"#eaf2fd".equals(fileFill) || !"#d8e8ff".equals(fileHover)) {
            problems.add("首页拖拽区底色/悬停色不对：" + fileFill + "/" + fileHover);
        }
        if (!fileFill.equals(hex(frame.sqlPage().sqlDropArea().fillColor()))
                || !fileFill.equals(hex(frame.sqlRunDropArea().fillColor()))) {
            problems.add("第二/三页拖拽区底色与首页不一致");
        }
        if (!"#f4f7fc".equals(hex(frame.excelActionBar().fillColor()))
                || !"#f4f7fc".equals(hex(frame.sqlPage().actionBar().fillColor()))) {
            problems.add("前两页动作条底板色不一致");
        }
        if (!SwingUtilities.isDescendingFrom(frame.dropExistingBox(), frame.excelActionBar())
                || !SwingUtilities.isDescendingFrom(frame.importButton(), frame.excelActionBar())) {
            problems.add("首页「覆盖已存在表」与「开始导入」不在同一块底板里");
        }
        if (!SwingUtilities.isDescendingFrom(frame.sqlPage().dropExistingBox(),
                frame.sqlPage().actionBar())
                || !SwingUtilities.isDescendingFrom(frame.sqlPage().runButton(),
                frame.sqlPage().actionBar())) {
            problems.add("第二页「覆盖已存在表」与「执行」不在同一块底板里");
        }
        // 第三页整条底部动作条按用户口径删了：覆盖开关不在，两颗按钮各归自己那一段，
        // 「批量执行脚本文件」回到脚本清单下面那一排
        if (countCoverSwitches(frame.tabs().getComponentAt(2)) != 0
                || countCoverSwitches(frame.tabs().getComponentAt(0)) != 1
                || countCoverSwitches(frame.tabs().getComponentAt(1)) != 1) {
            problems.add("「覆盖已存在表」的落点不对：应当只在第一、二页各有颗");
        }
        if (!"批量执行脚本文件".equals(frame.sqlRunScriptsButton().getText())) {
            problems.add("第三页那颗批量按钮没改名：" + frame.sqlRunScriptsButton().getText());
        }
        if (frame.sqlRunActionRow() == null
                || !SwingUtilities.isDescendingFrom(frame.sqlRunScriptsButton(),
                frame.sqlRunActionRow())) {
            problems.add("「批量执行脚本文件」不在脚本清单下面那一排");
        }
        if (!SwingUtilities.isDescendingFrom(frame.sqlRunButton(),
                frame.sqlRunEditorHead())) {
            problems.add("「执行编辑区 SQL」没挪到编辑区栏头的「清空编辑区」右侧");
        }
        if (frame.sqlRunRowExecButton() == null
                || !"执行".equals(frame.sqlRunRowExecButton().getText())) {
            problems.add("第三页清单的「操作」列没有那颗【执行】");
        }
        hint_painted((com.excel2mysql.gui.EmptyHintTable) frame.fileTable(),
                "首页文件表", problems);
        hint_painted((com.excel2mysql.gui.EmptyHintTable) frame.sqlPage().tableWidget(),
                "第二页清单", problems);
        hint_painted((com.excel2mysql.gui.EmptyHintTable) frame.sqlRunFilesTable(),
                "第三页清单", problems);
    }

    private static void hint_painted(com.excel2mysql.gui.EmptyHintTable table,
            String who, List<String> problems) {
        if (table.getRowCount() != 0) {
            problems.add(who + "在自检这里应该是空表");
            return;
        }
        if (table.emptyHint().isEmpty()) {
            problems.add(who + "没有空态提示语");
            return;
        }
        // 提示画在表格自身的中心，只有表格铺满视口时才落在看得见的地方：
        // 所以取证画面必须是真实的视口，不能自带尺寸单独画表格，否则漏掉"表格没铺满"这一类
        javax.swing.JViewport vp = (javax.swing.JViewport)
                SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, table);
        if (vp == null) {
            problems.add(who + "没有放在滚动视口里");
            return;
        }
        int w = 560;
        int h = 180;
        vp.setSize(w, h);
        vp.doLayout();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            vp.paint(g);
        } finally {
            g.dispose();
        }
        int bg = img.getRGB(0, 0) & 0xffffff;
        int ink = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y) & 0xffffff;
                int dr = Math.abs(((rgb >> 16) & 0xff) - ((bg >> 16) & 0xff));
                int dg = Math.abs(((rgb >> 8) & 0xff) - ((bg >> 8) & 0xff));
                int db = Math.abs((rgb & 0xff) - (bg & 0xff));
                if (dr + dg + db > 30) {
                    ink++;
                }
            }
        }
        if (ink < 100) {
            problems.add(who + "的空态提示在视口里没画出来（非底色像素 " + ink + "）");
        }
    }

    /**
     * 把控件画进一张图，数属于某一档颜色的像素。
     *
     * 只读字符属性会假绿：富文本件在 kit 不对、或颜色被控件自己的前景盖掉时，
     * 属性设了也画不出来，所以判"上色了没有"一律按颜色数像素，不按"非白"数
     * （非白会把控件自己的边线与反锯齿的灰边一起算成墨）。
     * 弹窗里的正文块常常还没被布局，量之前先给它一个够看的尺寸。
     */
    /** 容器里（含嵌套）有没有那条分隔线：排版这一档只能看真组件在不在。 */
    private static boolean hasRule(java.awt.Container c) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.JSeparator) {
                return true;
            }
            if (k instanceof java.awt.Container && hasRule((java.awt.Container) k)) {
                return true;
            }
        }
        return false;
    }

    /** 关于框正面摊在眼前的文字（不含悬浮提示）：判断"这段话是不是又被印到界面上"用。 */
    private static String faceText(java.awt.Container c) {
        StringBuilder sb = new StringBuilder();
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.JLabel) {
                sb.append(((javax.swing.JLabel) k).getText()).append('\n');
            } else if (k instanceof javax.swing.AbstractButton) {
                sb.append(((javax.swing.AbstractButton) k).getText()).append('\n');
            } else if (k instanceof javax.swing.text.JTextComponent) {
                sb.append(((javax.swing.text.JTextComponent) k).getText()).append('\n');
            }
            if (k instanceof java.awt.Container) {
                sb.append(faceText((java.awt.Container) k));
            }
        }
        return sb.toString();
    }

    private static int inkOf(java.awt.Component c, java.awt.Color want) {
        if (c == null) {
            return -1;
        }
        int w = Math.max(c.getWidth(), 800);
        int h = Math.max(c.getHeight(), 200);
        c.setSize(w, h);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            c.paint(g);
        } finally {
            g.dispose();
        }
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                if (Math.abs(((argb >> 16) & 0xff) - want.getRed()) <= 18
                        && Math.abs(((argb >> 8) & 0xff) - want.getGreen()) <= 18
                        && Math.abs((argb & 0xff) - want.getBlue()) <= 18) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 「复制全部」这一路走真系统剪贴板。
     *
     * <p>为什么只在这一栈钉系统级判据：Python 那条线在这台机器上连裸 Win32 的
     * {@code OpenClipboard} 都失败，拿它当闸门红的是执行环境不是产品；JDK 这一侧
     * 实测能读能写，所以剪贴板这一格在这里验一次，界面那一侧判文案与回执。
     *
     * <p>剪贴板是用户桌上的东西：先捕获原值，判完原样归还。
     */
    private static void checkAboutCopy(com.excel2mysql.gui.AboutDialog ad,
                                       List<String> problems) {
        final String want = ad.copyText();
        java.awt.Component btn = findButtonByLabel(ad.getContentPane(),
                com.excel2mysql.gui.AboutDialog.COPY_LABEL);
        if (!(btn instanceof javax.swing.JButton)) {
            problems.add("关于框没有那颗「复制全部」按钮");
            return;
        }
        String prev = clipboardText();
        // 这一层自检整段跑在事件分发线程上：doClick() 只是把动作排进队列，
        // 而队列要等这一段让出去才会走——所以直接派发同一个 ActionListener，
        // 走的是按钮点击时那条一模一样的调用链，只是不再排队（invokeAndWait 在这里是非法的）
        java.awt.event.ActionListener[] listeners = ((javax.swing.JButton) btn).getActionListeners();
        if (listeners.length == 0) {
            problems.add("「复制全部」那颗按钮没挂上动作，点了不会有任何反应");
            return;
        }
        for (java.awt.event.ActionListener al : listeners) {
            al.actionPerformed(new java.awt.event.ActionEvent(btn,
                    java.awt.event.ActionEvent.ACTION_PERFORMED, "click"));
        }
        String got = clipboardText();
        String receipt = ad.copyButtonText();
        boolean copied = com.excel2mysql.gui.AboutDialog.COPIED_LABEL.equals(receipt);
        boolean clipUsable = com.excel2mysql.gui.AboutDialog.COPIED_LABEL.equals(receipt)
                || com.excel2mysql.gui.AboutDialog.CLIP_FAIL_LABEL.equals(receipt);
        if (!clipUsable) {
            problems.add("点完「复制全部」那颗按钮没换字，用户无从知道点没点上：" + receipt);
        } else if (!copied) {
            // 这台机器开不了系统剪贴板（无桌面会话或被占着）：这一判记为未覆盖，不算通过
            System.out.println("! [WARN] GUI-SELFTEST | 剪贴板开不了，"
                    + "「复制全部」写进系统剪贴板这一判本轮未覆盖（按钮已如实报「剪贴板打不开」）");
        } else if (!want.equals(got)) {
            problems.add("点「复制全部」后剪贴板里不是那六行：" + brief(got));
        }
        String sha = "";
        String[] lines = want.split("\n");
        if (lines.length > 2 && lines[2].startsWith("提交：")) {
            sha = lines[2].substring(3).split("（")[0];
        }
        if (!sha.matches("[0-9a-f]{40}")) {
            problems.add("复制出去的提交号不是 40 位十六进制：" + sha);
        }
        if (!lines[0].equals(com.excel2mysql.core.AppInfo.APP_NAME)) {
            problems.add("复制文本的首行不是产品名那一行：" + brief(lines[0]));
        }
        if (prev != null) {
            writeClipboard(prev);                  // 桌上借来的东西要还回去
        }
        // 双击那一行：只负责把整行选上（真复制仍走 Ctrl+C），承诺与行为必须对齐。
        // 这一判只能验到"处理挂上了没有"：合成 MouseEvent 在这个 JDK 里 getClickCount()
        // 恒为 0（实测 x=6、y=2、clickCount=0，构造参数落点与文档不符），
        // 而关于框是模态的、自检不能 show 它——行为那一格显式记未覆盖，不混进通过里
        String value = ad.valueOf("commit");
        java.awt.Component row = findTextByValue(ad.getContentPane(), value);
        if (row == null) {
            problems.add("关于框里找不到提交号那一行，双击这一判没跑成");
            return;
        }
        boolean ours = false;
        for (java.awt.event.MouseListener ml : row.getMouseListeners()) {
            if (ml.getClass().getName().startsWith("com.excel2mysql.gui.AboutDialog")) {
                ours = true;
            }
        }
        if (!ours) {
            problems.add("关于框那一行没挂双击处理，tooltip 却写着「双击整行选中」");
        } else {
            java.awt.event.MouseEvent dbl = new java.awt.event.MouseEvent(row,
                    java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    6, 6, 2, 0, false);
            for (java.awt.event.MouseListener ml : row.getMouseListeners()) {
                ml.mouseClicked(dbl);
            }
            String selected = ((javax.swing.text.JTextComponent) row).getSelectedText();
            if (!value.equals(selected)) {
                System.out.println("! [WARN] GUI-SELFTEST | 合成鼠标事件在这台 JDK 上"
                        + "带不出 clickCount，「双击整行选中」这一判本轮未覆盖"
                        + "（选到的是：" + brief(selected) + "）");
            }
        }
    }

    /**
     * 「关于」里作者那一格（仓库链接）：先核这一格本身，再真走一遍单击，两种结局都要说实话。
     *
     * <p>浏览器那一步交给注入进来的假 opener——自检真开浏览器会把桌上的窗口搞乱，
     * 而这一判要的是"点了有没有可见回执"，不是"浏览器起没起"；真那条路另有出包冒烟。
     * 剪贴板是桌上借来的东西：先捕获原值，判完原样归还。
     */
    private static void checkAuthorLink(com.excel2mysql.gui.AboutDialog ad,
                                        List<String> problems) {
        String miss = ad.linkArrowMissing();
        if (!miss.isEmpty()) {
            problems.add("作者那一格的外链箭头没读进来：" + miss
                    + "（出包时 assets 里那张要连着走）");
        }
        if (!com.excel2mysql.core.AppInfo.REPO_URL.equals(ad.linkUrl())
                || !ad.linkUrl().startsWith("https://")) {
            problems.add("作者那一格的地址不是单源那串 https：" + ad.linkUrl());
        }
        if (!com.excel2mysql.gui.AboutDialog.AUTHOR.equals(ad.linkText())
                || !ad.linkClickable()) {
            problems.add("作者那一格开场不是署名或不是真能点：" + ad.linkText());
        }
        final List<String> seen = new ArrayList<String>();
        com.excel2mysql.gui.AboutDialog opened = new com.excel2mysql.gui.AboutDialog(
                null, com.excel2mysql.core.AppInfo.load(),
                new java.util.function.Function<String, Boolean>() {
                    @Override
                    public Boolean apply(String url) {
                        seen.add(url);
                        return Boolean.TRUE;
                    }
                });
        try {
            String st = opened.linkActivate();
            if (!"opened".equals(st) || seen.size() != 1
                    || !com.excel2mysql.core.AppInfo.REPO_URL.equals(seen.get(0))) {
                problems.add("点作者那一格没把完整地址交给浏览器：" + st + " / " + seen);
            }
            if (!com.excel2mysql.gui.AboutDialog.REPO_OPENED_LABEL.equals(opened.linkText())) {
                problems.add("作者那一格点开成功后没有可见回执：" + opened.linkText());
            }
        } finally {
            opened.dispose();
        }
        String prev = clipboardText();
        com.excel2mysql.gui.AboutDialog failed = new com.excel2mysql.gui.AboutDialog(
                null, com.excel2mysql.core.AppInfo.load(),
                new java.util.function.Function<String, Boolean>() {
                    @Override
                    public Boolean apply(String url) {
                        return Boolean.FALSE;
                    }
                });
        try {
            String st = failed.linkActivate();
            boolean copied = "copied".equals(st);
            if (!(copied || "failed".equals(st))) {
                problems.add("打不开浏览器时那一格既没复制也没如实报：" + st);
            } else if (!copied) {
                System.out.println("! [WARN] GUI-SELFTEST | 剪贴板开不了，"
                        + "「打不开浏览器就把地址复制进剪贴板」这一判本轮未覆盖"
                        + "（那一格已如实报「" + failed.linkText() + "」）");
            } else if (!com.excel2mysql.core.AppInfo.REPO_URL.equals(clipboardText())) {
                problems.add("那一格说地址已复制，剪贴板里却不是那串地址："
                        + brief(clipboardText()));
            }
            String want = copied ? com.excel2mysql.gui.AboutDialog.REPO_COPIED_LABEL
                    : com.excel2mysql.gui.AboutDialog.REPO_FAIL_LABEL;
            if (!want.equals(failed.linkText())) {
                problems.add("作者那一格的回执文字与状态不符：" + failed.linkText());
            }
        } finally {
            failed.dispose();
            if (prev != null) {
                writeClipboard(prev);
            }
        }
    }

    /** 系统剪贴板里的文本；没有文本、读不动都返回 null，不把环境问题报成产品缺陷。 */
    private static String clipboardText() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return data == null ? null : String.valueOf(data);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeClipboard(String text) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(text), null);
        } catch (Exception ignored) {
            // 还得回去是运气，拿不回去也不能把自检带崩
        }
    }

    private static java.awt.Component findButtonByLabel(java.awt.Container c, String label) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.JButton && label.equals(((javax.swing.JButton) k).getText())) {
                return k;
            }
            if (k instanceof java.awt.Container) {
                java.awt.Component sub = findButtonByLabel((java.awt.Container) k, label);
                if (sub != null) {
                    return sub;
                }
            }
        }
        return null;
    }

    private static java.awt.Component findTextByValue(java.awt.Container c, String value) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.text.JTextComponent
                    && value.equals(((javax.swing.text.JTextComponent) k).getText())) {
                return k;
            }
            if (k instanceof java.awt.Container) {
                java.awt.Component sub = findTextByValue((java.awt.Container) k, value);
                if (sub != null) {
                    return sub;
                }
            }
        }
        return null;
    }

    /** 整棵子树里找有没有进度条控件（页内进度条拿掉了，留着就是回归）。 */
    private static boolean hasProgressBar(java.awt.Container root) {
        if (root == null) {
            return false;
        }
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JProgressBar) {
                return true;
            }
            if (c instanceof java.awt.Container && hasProgressBar((java.awt.Container) c)) {
                return true;
            }
        }
        return false;
    }

    /** 第一条命中某个关键词的示例序号：界面上过滤后的第一条必须是它。 */
    private static int firstHitIndex(String needle) {
        for (int i = 0; i < SqlSamples.ALL.length; i++) {
            if (SqlSamples.matches(SqlSamples.ALL[i], needle)) {
                return i;
            }
        }
        return -1;
    }

    /** 数一句文案里有几行（按 \n 切），拖拽区文案的"第三行"就这么核。 */
    private static int lines(String text) {
        return text.split("\n").length;
    }

    /**
     * 粘贴添加的探针：喂路径给 pastePaths（不碰系统剪贴板），核计数回执与「已忽略」那条。
     * 放在自检最后跑——粘贴起的是真解析任务，任务位在 EDT 上排队收尾，
     * 自检正站在 EDT 上，睡等只会把它饿死，所以之后所有用例都得等它跑完才能跑。
     */
    private static void paste_probe(MainFrame frame, List<String> files,
                                    List<String> problems) throws Exception {
        // 按键绑定本身也要咬住：删掉 bindPasteShortcut() 时，只喂 pastePaths 的探针照旧全绿，
        // 那就等于"能加文件"被验到了、"按 Ctrl+V 能加"没被验到。
        javax.swing.KeyStroke pasteKey = javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        Object bound = frame.getRootPane().getInputMap(
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(pasteKey);
        if (!"pasteFiles".equals(bound)) {
            problems.add("Ctrl+V 没绑在窗口层级的根面板上（焦点在哪颗控件都该收）：" + bound);
        }
        if (frame.getRootPane().getActionMap().get("pasteFiles") == null) {
            problems.add("根面板 ActionMap 里没有 pasteFiles 这个动作");
        }
        // 文本控件那条更要咬住：它们的 WHEN_FOCUSED 绑定排在窗口级之前，
        // 不改挂到同一条分派上，焦点一进 SQL 编辑区或主机名框就粘不进文件（用户报的就是这个）
        if (!frame.isTextPasteBound(frame.sqlRunInputComponent())) {
            problems.add("SQL 编辑区的 Ctrl+V 没改挂到粘贴分派上：焦点在编辑区时粘不进文件");
        }
        if (!frame.isTextPasteBound(frame.hostField())
                || !frame.isTextPasteBound(frame.prefixField())) {
            problems.add("连接区或前缀框的 Ctrl+V 没改挂到粘贴分派上");
        }
        // 分派规则本身：没文件 + 焦点在文本控件 → 让回控件自己粘；
        // 没文件 + 焦点不在文本控件 → 由当前页给一句回执
        if (frame.routePaste(new ArrayList<String>(), frame.sqlRunInputComponent())) {
            problems.add("剪贴板是纯文本时不该抢走输入框的 Ctrl+V");
        }
        if (!frame.routePaste(new ArrayList<String>(), frame.sqlRunFilesTable())) {
            problems.add("焦点不在文本控件时，这一按键该由当前页接下并给回执");
        }
        // 粘贴添加走 pastePaths 这条缝：自检不去碰系统剪贴板（那台机此刻装着什么不可控）。
        // 两份真 xlsx 混一个 txt：计数要说清，txt 不混进清单
        List<String> pasted = new ArrayList<String>();
        pasted.add(files.get(0));
        pasted.add(files.get(1));
        pasted.add(new java.io.File("readme.txt").getPath());
        frame.pastePaths(pasted);
        if (!frame.logPanel().contains("已通过粘贴添加 2 个文件，其中 0个 zip文件，"
                + "2个 xlsx文件，0个 xls文件")) {
            problems.add("粘贴添加的计数留痕不对：" + tailOf(frame.logPanel().text()));
        }
        // 任务位还占着时再粘一次：那句"已通过粘贴添加"不能说出口，要说的是"已有任务在跑"。
        // 与 Python 侧 R75e 同一条契约——拦不住的话回执就成了假话。
        int receipts = countReceipts(frame.logPanel().text());
        frame.pastePaths(java.util.Collections.singletonList(files.get(0)));
        String busyLog = frame.logPanel().text();
        if (countReceipts(busyLog) != receipts) {
            problems.add("任务在跑时粘贴仍然先说了「已通过粘贴添加」：" + tailOf(busyLog));
        }
        if (!busyLog.contains("已有任务在跑")) {
            problems.add("任务在跑时粘贴没拦下来说明原因：" + tailOf(busyLog));
        }
        // 粘贴起的是真解析任务，而自检自己就站在 EDT 上：睡等只会把它的收尾回调饿死，
        // 所以断言完就把任务位手动还掉（与 import_guard 抓缺陷时同一招）
        if (frame.taskBusy(MainFrame.RES_EXCEL)) {
            frame.endTask();
        }
        frame.clearAll();
        List<String> junkOnly = new ArrayList<String>();
        junkOnly.add(new java.io.File("note.docx").getPath());
        frame.pastePaths(junkOnly);
        if (!frame.logPanel().contains("拖入或粘贴的不是 Excel 或 ZIP 文件，已忽略")) {
            problems.add("粘进来的全是不支持的类型时，没走那条已有的「已忽略」口径");
        }
        // 粘贴要落在眼前这一页：三页虚线区都写着那句快捷键提示，只往第一页加就是假话。
        // 第二页用那句「已忽略」定位回执落在哪一页；第三页真加一条，再点「清空待上传文件」
        // 还原成空清单，不污染后面那几个用例。
        frame.selectTab(1);
        int sqlBefore = frame.sqlPage().entryCount();
        List<String> junkSql = new ArrayList<String>();
        junkSql.add(new java.io.File("说明.txt").getPath());
        frame.pastePaths(junkSql);
        if (frame.sqlPage().entryCount() != sqlBefore
                || !frame.sqlPage().logPanel().contains(
                        "[提示] 拖入或粘贴的不是 SQL 脚本文件，已忽略。")) {
            problems.add("停在第二页粘贴，回执没落在第二页的日志里："
                    + tailOf(frame.sqlPage().logPanel().text()));
        }
        frame.selectTab(2);
        int runBefore = frame.sqlRunFileCount();
        String runSql = new java.io.File(
                java.nio.file.Files.createTempDirectory("e2m-paste-run").toFile(),
                "粘贴进来的.sql").getPath();
        writeText(runSql, "CREATE TABLE t_paste (id INT);");
        frame.pastePaths(java.util.Collections.singletonList(runSql));
        if (frame.sqlRunFileCount() != runBefore + 1) {
            problems.add("停在第三页粘贴，脚本没进执行队列：现有 "
                    + frame.sqlRunFileCount() + " 个");
        }
        frame.sqlRunFilesClearButton().doClick();
        if (frame.sqlRunFileCount() != runBefore) {
            problems.add("第三页粘进来的脚本清不掉，会污染后面的用例：还剩 "
                    + frame.sqlRunFileCount() + " 个");
        }
        frame.selectTab(0);
        frame.clearAll();
    }

    /** 日志里「已通过粘贴添加」出现过几次：一句一次，用来判"拦没拦住"。 */
    private static int countReceipts(String text) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf("已通过粘贴添加", from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + 1;
        }
    }

    /** 这一棵子树里有几颗「覆盖已存在表」：开关挪页/删页只数得出来，不看像素。 */
    private static int countCoverSwitches(java.awt.Component root) {
        int n = 0;
        if (root instanceof javax.swing.JCheckBox
                && String.valueOf(((javax.swing.JCheckBox) root).getText())
                        .contains("覆盖已存在表")) {
            n++;
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component c : ((java.awt.Container) root).getComponents()) {
                n += countCoverSwitches(c);
            }
        }
        return n;
    }

    /** 页签内容里有几行写着「目标库：」——这三条按用户口径整行删掉了，应当数出 0。 */
    private static int countDbPointers(java.awt.Component root) {
        int n = 0;
        if (root instanceof javax.swing.JLabel
                && String.valueOf(((javax.swing.JLabel) root).getText())
                        .contains("目标库：")) {
            n++;
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component c : ((java.awt.Container) root).getComponents()) {
                n += countDbPointers(c);
            }
        }
        return n;
    }

    private static String hex(java.awt.Color c) {
        return c == null ? ""
                : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * 命名开关两颗复选框 + 空列按字段名定类型，走的是界面添加文件那条真实入口。
     * Python 侧对应 M21/M22 与 B22：名字规则写在开关上，不在代码里各写一份。
     */
    /**
     * 日志一行的形状：[年-月-日 时:分:秒] + 符号 + 大写分级标签，级别由中文标签定，续行不另打时间戳。
     *
     * 这一道必须咬住：三条线同一套标签（cli.py / core.log_text / 这里），时间戳也同一形状。
     * 时间戳丢了或只剩钟点、级别定错了、命令行搬来的那句叠出两个标签，都会在这里变红。
     */
    private static void log_line_shape(MainFrame frame, List<String> problems) {
        java.util.regex.Pattern head = java.util.regex.Pattern.compile(
                "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] ([+\\-!x]) \\[(OK|INFO|WARN|ERROR)\\] (.*)$");
        frame.log("[跳过] 形状A");
        frame.log("[已解析] 形状B");
        frame.logError("[建表失败] 形状C");
        frame.log("! [WARN] 说明 | 形状D");
        frame.log("      | 1 | 2 |");
        String[] want = {"形状A", "形状B", "形状C", "形状D", "| 1 | 2 |"};
        String[] wantLevel = {"WARN", "OK", "ERROR", "WARN", null};
        String[] wantMark = {"!", "+", "x", "!", null};
        java.util.Map<String, String> seen = new java.util.HashMap<String, String>();
        for (String ln : frame.logPanel().text().split("\n")) {
            for (String w : want) {
                if (ln.contains(w) && !seen.containsKey(w)) {
                    seen.put(w, ln);
                }
            }
        }
        for (int i = 0; i < want.length; i++) {
            String ln = seen.get(want[i]);
            if (ln == null) {
                problems.add("日志里没有形状用例那一行：" + want[i]);
                continue;
            }
            if (wantLevel[i] == null) {
                // 缩进的续行（结果表格那几行）归上一条记录，打上时间戳会把等宽表格顶歪
                if (!ln.equals("      | 1 | 2 |")) {
                    problems.add("缩进的续行被改写了：" + ln);
                }
                continue;
            }
            java.util.regex.Matcher m = head.matcher(ln);
            if (!m.matches()) {
                problems.add("日志行首不是「[年-月-日 时:分:秒] 符号 [级别] 」：" + ln);
                continue;
            }
            if (!wantLevel[i].equals(m.group(3)) || !wantMark[i].equals(m.group(2))) {
                problems.add("定级不对：" + ln + "（期望 " + wantMark[i] + " " + wantLevel[i] + "）");
            }
        }
        if (seen.get("形状D") != null && countOf(seen.get("形状D"), "[WARN]") != 1) {
            problems.add("本来就带大写标签的那句被叠了两个标签：" + seen.get("形状D"));
        }
        // 整页日志都要合这规矩（缩进行与空行除外）：漏一处就有一页的日志没有时间戳
        for (String ln : frame.logPanel().text().split("\n")) {
            if (ln.trim().isEmpty() || Character.isWhitespace(ln.charAt(0))) {
                continue;
            }
            if (!head.matcher(ln).matches()) {
                problems.add("日志里有不合形状的一行：" + ln);
                break;
            }
        }
    }

    /**
     * 三个日志框的最小高度：不给最小高度，分栏按比例一缩，默认窗口里实测只剩两行
     * （截图复核抓到的）。这一条断"至少装得下 4 行日志那一档字号（10pt）的字"。
     */
    private static void log_pane_height(MainFrame frame, List<String> problems) {
        com.excel2mysql.gui.LogPanel[] panes = {
                frame.logPanel(), frame.sqlPage().logPanel(), frame.sqlRunLogPanel()};
        String[] names = {"Excel 页", "SQL 脚本页", "SQL 执行器页"};
        java.awt.FontMetrics fm = frame.getGraphics() == null ? null
                : frame.getFontMetrics(panes[0].getFont());
        for (int i = 0; i < panes.length; i++) {
            int min = panes[i].getMinimumSize().height;
            if (fm != null && min < 4 * fm.getHeight()) {
                problems.add(names[i] + "的日志框装不下 4 行：" + min + " < "
                        + (4 * fm.getHeight()));
            }
        }
    }

    /**
     * 「SQL 执行器」的逐条进度与真取消：一屏上千条语句时，这两件决定用户是等还是走。
     * DbClient 是 final 类，造不出来，所以 SqlExecute 拆了一层收 Connection 的入口，
     * 这里用动态代理假一个连接——没有库也验的是线上那段循环。
     */
    private static void sql_exec_progress(List<String> problems) {
        final List<String> executed = new ArrayList<String>();
        final boolean[] committed = {false};
        final boolean[] rolled = {false};
        java.sql.Connection conn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                GuiMain.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("createStatement".equals(name)) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                GuiMain.class.getClassLoader(),
                                new Class<?>[]{java.sql.Statement.class},
                                (p2, m2, a2) -> {
                                    if ("execute".equals(m2.getName())) {
                                        executed.add(String.valueOf(a2[0]));
                                        return Boolean.FALSE;
                                    }
                                    if ("getUpdateCount".equals(m2.getName())) {
                                        return Integer.valueOf(1);
                                    }
                                    if ("close".equals(m2.getName())) {
                                        return null;
                                    }
                                    if ("toString".equals(m2.getName())) {
                                        return "fake-statement";
                                    }
                                    return defaultValue(m2.getReturnType());
                                });
                    }
                    if ("commit".equals(name)) {
                        committed[0] = true;
                        return null;
                    }
                    if ("rollback".equals(name)) {
                        rolled[0] = true;
                        return null;
                    }
                    if ("isClosed".equals(name)) {
                        return Boolean.FALSE;
                    }
                    if ("toString".equals(name)) {
                        return "fake-connection";
                    }
                    return defaultValue(method.getReturnType());
                });
        final List<int[]> steps = new ArrayList<int[]>();
        final boolean[] stop = {false};
        com.excel2mysql.core.SqlExecute.Report r = com.excel2mysql.core.SqlExecute.run(
                conn, "CREATE TABLE pa (i INT);\nCREATE TABLE pb (i INT);"
                        + "\nCREATE TABLE pc (i INT);",
                text -> {
                },
                (done, total) -> {
                    steps.add(new int[]{done, total});
                    if (done >= 1) {
                        stop[0] = true;      // 第一条跑完就当作"用户点了停止"
                    }
                },
                () -> stop[0]);
        if (steps.isEmpty() || steps.get(0)[0] != 0 || steps.get(0)[1] != 3) {
            problems.add("执行器没在开工前先报 0/3：" + describeSteps(steps));
        }
        if (executed.size() != 1) {
            problems.add("点了停止还在往下执行：" + executed);
        }
        if (!rolled[0] || committed[0]) {
            problems.add("取消的这批没回滚或竟然提交了：rolled=" + rolled[0]
                    + " committed=" + committed[0]);
        }
        if (!r.cancelled) {
            problems.add("Report.cancelled 没置上，界面分不清是跑完还是被停");
        }
        // 不传进度与取消时行为必须与改前一致（命令行那条线就是这么调的）
        final List<String> executed2 = new ArrayList<String>();
        final boolean[] committed2 = {false};
        java.sql.Connection conn2 = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                GuiMain.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                GuiMain.class.getClassLoader(),
                                new Class<?>[]{java.sql.Statement.class},
                                (p2, m2, a2) -> {
                                    if ("execute".equals(m2.getName())) {
                                        executed2.add(String.valueOf(a2[0]));
                                    }
                                    if ("getUpdateCount".equals(m2.getName())) {
                                        return Integer.valueOf(1);
                                    }
                                    return defaultValue(m2.getReturnType());
                                });
                    }
                    if ("commit".equals(method.getName())) {
                        committed2[0] = true;
                    }
                    return defaultValue(method.getReturnType());
                });
        com.excel2mysql.core.SqlExecute.run(conn2,
                "CREATE TABLE pa (i INT);\nCREATE TABLE pb (i INT);"
                        + "\nCREATE TABLE pc (i INT);", text -> {
                }, null, null);
        if (executed2.size() != 3 || !committed2[0]) {
            problems.add("不传进度与取消时行为变了：跑了 " + executed2.size()
                    + " 句、提交=" + committed2[0]);
        }
    }

    /**
     * 结果集交给界面的是列名与逐格取值，不再是一段等宽文本。
     *
     * 同一个假连接跑两遍：传回调（图形界面）与不传回调（命令行与金标准）。
     * 只断言前一边的话，把命令行那条线的文本表格一起改掉也不会红——
     * 而那份文本正是 sql.jsonl.gz 逐字符锁住的交付物。
     */
    private static void sql_exec_result_sets(List<String> problems) {
        String[] head = {"id", "姓名", "department"};
        String[][] body = {{"1", "张三丰", "Logistics Division"}, {"2", "李四", "卫勤处"}};
        final List<com.excel2mysql.core.SqlExecute.ResultSetData> got =
                new ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
        com.excel2mysql.core.SqlExecute.Results sink =
                new com.excel2mysql.core.SqlExecute.Results() {
                    @Override
                    public void accept(com.excel2mysql.core.SqlExecute.ResultSetData data) {
                        got.add(data);
                    }
                };
        final StringBuilder text = new StringBuilder();
        com.excel2mysql.core.SqlExecute.run(
                fakeSelectConnection(head, body), "select * from t_mixed;",
                line -> text.append(line).append('\n'), null, null, sink);
        if (got.size() != 1) {
            problems.add("传了回调却没收到结果集：收到 " + got.size() + " 个");
            return;
        }
        com.excel2mysql.core.SqlExecute.ResultSetData d = got.get(0);
        if (d.seq != 1 || !"select * from t_mixed".equals(d.sql)
                || d.columns.size() != 3 || !"姓名".equals(d.columns.get(1))
                || d.rows.size() != 2 || !"张三丰".equals(d.rows.get(0).get(1))
                || !"卫勤处".equals(d.rows.get(1).get(2))
                || d.total != 2 || d.printed != 2) {
            problems.add("结果集形状不对：seq=" + d.seq + " sql=" + d.sql
                    + " columns=" + d.columns + " rows=" + d.rows
                    + " total=" + d.total + " printed=" + d.printed);
        }
        if (text.indexOf("+---") >= 0) {
            problems.add("传了回调还在文本里画表格，同一份结果会有两处呈现：" + text);
        }
        final StringBuilder plain = new StringBuilder();
        com.excel2mysql.core.SqlExecute.run(
                fakeSelectConnection(head, body), "select * from t_mixed;",
                line -> plain.append(line).append('\n'), null, null, null);
        if (plain.indexOf("+---") < 0 || plain.indexOf("张三丰") < 0) {
            problems.add("命令行那条线的文本表格没了（金标准逐字符锁着它）：" + plain);
        }
    }

    /** 假一个只回给定结果集的 Connection：没有库也能验到 SqlExecute 里取结果那一段。 */
    private static java.sql.Connection fakeSelectConnection(
            final String[] head, final String[][] body) {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                GuiMain.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                GuiMain.class.getClassLoader(),
                                new Class<?>[]{java.sql.Statement.class},
                                (p2, m2, a2) -> {
                                    if ("execute".equals(m2.getName())) {
                                        return Boolean.TRUE;
                                    }
                                    if ("getResultSet".equals(m2.getName())) {
                                        return fakeResultSet(head, body);
                                    }
                                    if ("close".equals(m2.getName())) {
                                        return null;
                                    }
                                    return defaultValue(m2.getReturnType());
                                });
                    }
                    if ("isClosed".equals(method.getName())) {
                        return Boolean.FALSE;
                    }
                    if ("toString".equals(method.getName())) {
                        return "fake-select-connection";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 假结果集自己带游标：一句 select 取一次，逐行往下走就够。 */
    private static java.sql.ResultSet fakeResultSet(
            final String[] head, final String[][] body) {
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                GuiMain.class.getClassLoader(), new Class<?>[]{java.sql.ResultSet.class},
                new java.lang.reflect.InvocationHandler() {
                    private int row = -1;

                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method,
                                         Object[] args) {
                        String name = method.getName();
                        if ("next".equals(name)) {
                            row++;
                            return Boolean.valueOf(row < body.length);
                        }
                        if ("getString".equals(name)) {
                            int col = ((Integer) args[0]).intValue() - 1;
                            if (row < 0 || row >= body.length
                                    || col < 0 || col >= body[row].length) {
                                return null;
                            }
                            return body[row][col];
                        }
                        if ("getMetaData".equals(name)) {
                            return java.lang.reflect.Proxy.newProxyInstance(
                                    GuiMain.class.getClassLoader(),
                                    new Class<?>[]{java.sql.ResultSetMetaData.class},
                                    (p3, m3, a3) -> {
                                        if ("getColumnCount".equals(m3.getName())) {
                                            return Integer.valueOf(head.length);
                                        }
                                        if ("getColumnLabel".equals(m3.getName())
                                                || "getColumnName".equals(m3.getName())) {
                                            int i = ((Integer) a3[0]).intValue() - 1;
                                            return i >= 0 && i < head.length ? head[i] : "";
                                        }
                                        return defaultValue(m3.getReturnType());
                                    });
                        }
                        if ("close".equals(name)) {
                            return null;
                        }
                        if ("toString".equals(name)) {
                            return "fake-result-set";
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static String describeSteps(List<int[]> steps) {
        StringBuilder sb = new StringBuilder();
        for (int[] st : steps) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(st[0]).append('/').append(st[1]);
        }
        return sb.toString();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(type)) {
            return Boolean.FALSE;
        }
        if (void.class.equals(type)) {
            return null;
        }
        return Integer.valueOf(0);
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /**
     * 按给定的大小写档解析一份结构定义表，回第一个字段名。
     * 直接走 Names + TableBuilder，不碰控件：这颗开关管到什么，用命名层的结果说才算数。
     */
    private static String structField(String path, boolean upper, boolean lower) {
        com.excel2mysql.core.Names n =
                new com.excel2mysql.core.Names(com.excel2mysql.core.PinyinLoader.get());
        n.setUpperOnly(upper);
        n.setLowerOnly(lower);
        return new com.excel2mysql.core.TableBuilder(
                new com.excel2mysql.core.Infer(n), n).build(path).table.columns.get(0).field;
    }

    /** 日志里「大小写切换为」出现了几次：一次点击只该留一条，被按掉的那颗不该补一句。 */
    private static int countCaseLogs(MainFrame frame) {
        return countIn(frame.logPanel().text(), "[提示] 表名与字段名大小写切换为：");
    }

    /** 数一段文本里某个子串出现几次（日志路由到别的面板时，判"有没有多写一遍"只能靠计数）。 */
    private static int countIn(String text, String key) {
        int n = 0;
        for (int i = text.indexOf(key); i >= 0; i = text.indexOf(key, i + 1)) {
            n++;
        }
        return n;
    }

    private static void naming_switches(MainFrame frame, List<String> problems) {
        if (frame.initialsBox() == null || frame.upperBox() == null) {
            problems.add("界面上缺少命名开关（取拼音首字母 / 转大写）");
            return;
        }
        if (!"表名和字段名取中文拼音首字母".equals(frame.initialsBox().getText())
                || !"表名与字段名转大写".equals(frame.upperBox().getText())) {
            problems.add("命名开关文案不对：" + frame.initialsBox().getText() + " / "
                    + frame.upperBox().getText());
        }
        File tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-naming-selftest").toFile();
            // 1) 勾上两颗开关（首字母 + 大写）：新加的文件直接按这套起名
            frame.initialsBox().doClick();
            frame.upperBox().doClick();
            File a = writeNamingFixture(tmp, "水库信息表.xlsx",
                    new String[]{"订单金额"}, new Object[]{Double.valueOf(12.5)});
            frame.addFiles(java.util.Arrays.asList(a.getPath()));
            com.excel2mysql.core.TableOps.Table ta = lastTable(frame);
            if (ta == null || !"SKXXB".equals(ta.tableName)) {
                problems.add("勾上首字母 + 大写后新表名不是 SKXXB：" + (ta == null ? "(无表)" : ta.tableName));
            } else if (ta.columns.isEmpty() || !"DDJE".equals(ta.columns.get(0).field)) {
                problems.add("勾上首字母 + 大写后字段名不是 DDJE："
                        + (ta.columns.isEmpty() ? "(无字段)" : ta.columns.get(0).field));
            }
            if (!frame.logPanel().contains("[提示] 命名规则切换为：拼音首字母")
                    || !frame.logPanel().contains("[提示] 表名与字段名大小写切换为：大写")) {
                problems.add("勾上两颗开关没有留下日志");
            }
            // 2) 两颗都取消：回到全拼、小写，切换各留一行日志
            frame.initialsBox().doClick();
            frame.upperBox().doClick();
            File b = writeNamingFixture(tmp, "水库信息表（n_lowercheck）.xlsx",
                    new String[]{"订单金额"}, new Object[]{Double.valueOf(12.5)});
            frame.addFiles(java.util.Arrays.asList(b.getPath()));
            com.excel2mysql.core.TableOps.Table tb = lastTable(frame);
            if (tb == null || !"n_lowercheck".equals(tb.tableName)) {
                problems.add("取消两颗开关后表名不是 n_lowercheck："
                        + (tb == null ? "(无表)" : tb.tableName));
            } else if (tb.columns.isEmpty() || !"ding_dan_jin_e".equals(tb.columns.get(0).field)) {
                problems.add("取消两颗开关后字段名不是 ding_dan_jin_e："
                        + (tb.columns.isEmpty() ? "(无字段)" : tb.columns.get(0).field));
            }
            if (!frame.logPanel().contains("[提示] 命名规则切换为：全拼")
                    || !frame.logPanel().contains("[提示] 表名与字段名大小写切换为：小写")) {
                problems.add("取消两颗开关没有留下日志");
            }
            // 2b) 第二颗「转小写」（用户 2026-10-29 点名）：界面上要有、
            // 勾上把「转大写」按掉，而且一次点击只留一条日志
            //（默认勾选在启动段已单独验过，这里沿用按掉后的两颗都不勾状态）
            if (frame.lowerBox() == null) {
                problems.add("界面上缺少「表名与字段名转小写」开关");
            } else if (!"表名与字段名转小写".equals(frame.lowerBox().getText())) {
                problems.add("「转小写」开关文案不对：" + frame.lowerBox().getText());
            } else {
                int logsBefore = countCaseLogs(frame);
                frame.upperBox().doClick();
                frame.lowerBox().doClick();
                if (!frame.lowerBox().isSelected() || frame.upperBox().isSelected()) {
                    problems.add("两颗大小写开关没有互斥：大写=" + frame.upperBox().isSelected()
                            + " 小写=" + frame.lowerBox().isSelected());
                }
                if (!frame.logPanel().contains("[提示] 表名与字段名大小写切换为：小写"
                        + "（对之后添加的文件生效，结构定义 Sheet 里手写的字段名跟着转小写）")) {
                    problems.add("勾「转小写」没按当前生效档位留痕");
                }
                if (countCaseLogs(frame) != logsBefore + 2) {
                    problems.add("互斥切换多打了日志（被按掉那颗不该补一句）：多打 "
                            + (countCaseLogs(frame) - logsBefore - 2) + " 条");
                }
                frame.lowerBox().doClick();     // 按掉「转小写」，后面的用例按全拼、手写名保持原样跑
                if (frame.lowerBox().isSelected() || frame.upperBox().isSelected()) {
                    problems.add("收尾没收干净，大小写开关还亮着");
                }
            }
            // 3) 空模板按字段名定类型：全拼、小写口径下，类型各归各位
            File c = writeNamingFixture(tmp, "采集模板（t_empty_kinds）.xlsx",
                    new String[]{"发布日期", "更新时间", "订单金额", "数量", "备注说明",
                            "年龄", "系数", "评分", "经度", "长度", "是否有效", "序号",
                            "年份"}, null);
            frame.addFiles(java.util.Arrays.asList(c.getPath()));
            com.excel2mysql.core.TableOps.Table tc = lastTable(frame);
            String[] wantTypes = {"DATE", "DATETIME", "DECIMAL(16,2)", "INT", "TEXT",
                    "INT", "DECIMAL(18,6)", "DECIMAL(10,2)", "DECIMAL(18,8)",
                    "DECIMAL(18,4)", "BOOLEAN", "INT", "INT"};
            if (tc == null || tc.columns.size() != wantTypes.length) {
                problems.add("空模板字段数不对：" + (tc == null ? "(无表)" : tc.columns.size()));
            } else {
                for (int i = 0; i < wantTypes.length; i++) {
                    String got = tc.columns.get(i).type;
                    if (!wantTypes[i].equals(got)) {
                        problems.add("空模板按名定类型第 " + (i + 1) + " 列期望 "
                                + wantTypes[i] + "，实际 " + got);
                    }
                }
            }
            // 3b) 用户点名的汇总列：总计/小计必须是数值。词表其余词条不在这里重复——
            // 它们由夹具「空列词表全量」在第 6 步金标准差分里逐列比过，
            // 这里再加几十列会把日志顶到 2000 行上限，后面读日志窗口的断言就要假红
            File c2 = writeNamingFixture(tmp, "采集模板（t_empty_sums）.xlsx",
                    new String[]{"总计", "小计", "合计", "付款周期", "打卡时刻",
                            "其他"}, null);
            frame.addFiles(java.util.Arrays.asList(c2.getPath()));
            com.excel2mysql.core.TableOps.Table tc2 = lastTable(frame);
            String[] wantSums = {"DECIMAL(18,4)", "DECIMAL(18,4)", "DECIMAL(18,4)",
                    "INT", "TIME", "VARCHAR(64)"};
            if (tc2 == null || tc2.columns.size() != wantSums.length) {
                problems.add("汇总列夹具字段数不对："
                        + (tc2 == null ? "(无表)" : tc2.columns.size()));
            } else {
                for (int i = 0; i < wantSums.length; i++) {
                    String got = tc2.columns.get(i).type;
                    if (!wantSums[i].equals(got)) {
                        problems.add("汇总列按名定类型第 " + (i + 1) + " 列期望 "
                                + wantSums[i] + "，实际 " + got);
                    }
                }
            }
            File d = writeNamingFixture(tmp, "采集模板（t_empty_priority）.xlsx",
                    new String[]{"频率", "功率", "速率", "容积率", "毛利率"}, null);
            frame.addFiles(java.util.Arrays.asList(d.getPath()));
            com.excel2mysql.core.TableOps.Table td = lastTable(frame);
            String[] wantPriority = {"DECIMAL(18,4)", "DECIMAL(18,4)", "DECIMAL(18,4)",
                    "DECIMAL(18,6)", "DECIMAL(18,6)"};
            if (td == null || td.columns.size() != wantPriority.length) {
                problems.add("优先级夹具字段数不对："
                        + (td == null ? "(无表)" : td.columns.size()));
            } else {
                for (int i = 0; i < wantPriority.length; i++) {
                    String got = td.columns.get(i).type;
                    if (!wantPriority[i].equals(got)) {
                        problems.add("优先级夹具第 " + (i + 1) + " 列期望 "
                                + wantPriority[i] + "，实际 " + got);
                    }
                }
            }
        } catch (Exception e) {
            problems.add("命名开关自检抛异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (tmp != null) {
                deleteTree(tmp);
            }
        }
    }

    /**
     * 添加文件弹窗：文件名框里敲或粘文件夹路径回车，只能进入该目录，不能把目录交给程序。
     * 自检走弹窗自己的 ApproveSelectionAction（回车与「打开」都是这一条路）；
     * 带引号那种是资源管理器「复制文件地址」的原样格式，JDK 8 会跳过目录判断直接确认，
     * 必须由 FolderAwareChooser 拦下来。默认外观与 Windows 外观在这条路上行为一致。
     */
    private static void folder_path_entry(List<String> problems) {
        File tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-chooser-selftest").toFile();
            File folder = new File(tmp, "数据目录");
            if (!folder.mkdir()) {
                problems.add("文件夹路径回车自检：建临时目录失败");
                return;
            }
            File xlsx = new File(folder, "样本.xlsx");
            if (!xlsx.createNewFile()) {
                problems.add("文件夹路径回车自检：建临时文件失败");
                return;
            }
            com.excel2mysql.gui.FolderAwareChooser chooser =
                    new com.excel2mysql.gui.FolderAwareChooser();
            chooser.setMultiSelectionEnabled(true);
            final int[] approved = {0};
            chooser.addActionListener(e -> {
                if (javax.swing.JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) {
                    approved[0]++;
                }
            });
            javax.swing.plaf.basic.BasicFileChooserUI ui =
                    (javax.swing.plaf.basic.BasicFileChooserUI) chooser.getUI();

            // 1) 带引号的绝对路径：不拦就会带着目录关闭弹窗
            chooser.setCurrentDirectory(tmp);
            ui.setFileName("\"" + folder.getAbsolutePath() + "\"");
            if (ui.getFileName() == null || ui.getFileName().indexOf('"') < 0) {
                problems.add("文件夹路径回车自检：当前外观没接住文件名输入，用例不成立");
                return;
            }
            approveChooser(chooser, ui);
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("带引号的文件夹路径回车后没进入该目录："
                        + chooser.getCurrentDirectory());
            }
            if (approved[0] != 0) {
                problems.add("带引号的文件夹路径被当成文件交出去了");
            }

            // 2) 不带引号：JDK 本来就会进目录，加了覆盖层后不能反而变成确认
            approved[0] = 0;
            chooser.setCurrentDirectory(tmp);
            ui.setFileName(folder.getAbsolutePath());
            approveChooser(chooser, ui);
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("文件夹路径回车后没进入该目录：" + chooser.getCurrentDirectory());
            }
            if (approved[0] != 0) {
                problems.add("文件夹路径被当成文件交出去了");
            }

            // 3) 真选文件不受影响：该确认就确认，路径原样交出去
            approved[0] = 0;
            chooser.setCurrentDirectory(folder);
            ui.setFileName(xlsx.getAbsolutePath());
            approveChooser(chooser, ui);
            if (approved[0] != 1) {
                problems.add("正常 Excel 文件没有放行：approved=" + approved[0]);
            }
            File[] picked = chooser.getSelectedFiles();
            if (picked == null || picked.length != 1 || !xlsx.equals(picked[0])) {
                problems.add("正常 Excel 文件交出去的路径不对");
            }
        } catch (Exception e) {
            problems.add("文件夹路径回车自检抛异常：" + e);
        } finally {
            if (tmp != null) {
                deleteTree(tmp);
            }
        }
    }

    /**
     * 「查找范围」那一格：既能下拉选目录，也能敲一段路径回车跳过去。
     *
     * <p>JDK8 的 Windows 外观把这一格做成只出不进的下拉（模型只收 File，敲进去的字符串
     * 会抛 ClassCastException），所以 FolderAwareChooser 把它接过来自己管。这一判走的就是
     * 回车那条真路（typeLookIn 填文本 + 触发接线好的 ActionListener），不另搭桥。
     * 换 JDK 或换外观找不到那一格时记为未覆盖，不拿环境当产品缺陷判红。
     */
    private static void lookin_path_entry(List<String> problems) {
        File tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-lookin-selftest").toFile();
            File folder = new File(tmp, "数据目录");
            if (!folder.mkdir()) {
                problems.add("查找范围自检：建临时目录失败");
                return;
            }
            File xlsx = new File(folder, "样本.xlsx");
            if (!xlsx.createNewFile()) {
                problems.add("查找范围自检：建临时文件失败");
                return;
            }
            final List<String> notes = new ArrayList<String>();
            com.excel2mysql.gui.FolderAwareChooser.setNoteSink(
                    new com.excel2mysql.gui.FolderAwareChooser.Note() {
                        @Override
                        public void show(java.awt.Component parent, String text) {
                            notes.add(text);
                        }
                    });
            com.excel2mysql.gui.FolderAwareChooser chooser =
                    new com.excel2mysql.gui.FolderAwareChooser();
            chooser.setMultiSelectionEnabled(true);
            chooser.setCurrentDirectory(tmp);
            chooser.wireForSelftest();
            if (!chooser.lookInEditable()) {
                System.out.println("! [WARN] GUI-SELFTEST | 这台 JDK/外观下没找到「查找范围」"
                        + "那一格，敲路径回车跳转这一判本轮未覆盖（保持原样，只能下拉选）");
                return;
            }
            // 1) 敲目录回车：进去，而不是停在原处
            chooser.typeLookIn(folder.getAbsolutePath());
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("查找范围敲目录回车后没进去：" + chooser.getCurrentDirectory());
            }
            // 2) 带引号那种（资源管理器「复制文件地址」）：同样要能跳
            chooser.setCurrentDirectory(tmp);
            chooser.typeLookIn("\"" + folder.getAbsolutePath() + "\"");
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("查找范围里带引号的目录路径没跳：" + chooser.getCurrentDirectory());
            }
            // 3) 敲文件：进它的父目录并把这个文件带进已选清单
            chooser.setCurrentDirectory(tmp);
            chooser.typeLookIn(xlsx.getAbsolutePath());
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("查找范围敲文件路径没进它的父目录：" + chooser.getCurrentDirectory());
            }
            File[] picked = chooser.getSelectedFiles();
            if (picked == null || picked.length != 1 || !xlsx.equals(picked[0])) {
                problems.add("查找范围敲进去的文件没进已选清单："
                        + (picked == null ? "null" : picked.length));
            }
            // 4) 相对路径按当前目录解析
            chooser.setCurrentDirectory(tmp);
            chooser.typeLookIn("数据目录");
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("查找范围的相对路径没按当前目录解析："
                        + chooser.getCurrentDirectory());
            }
            // 5) 不存在：当前目录不动，且必须给一句可见提示，不能静默
            notes.clear();
            chooser.setCurrentDirectory(tmp);
            chooser.typeLookIn(new File(tmp, "没这个目录").getAbsolutePath());
            if (!tmp.equals(chooser.getCurrentDirectory())) {
                problems.add("查找范围敲了不存在的路径却换了目录："
                        + chooser.getCurrentDirectory());
            }
            if (notes.isEmpty() || !notes.get(0).contains("找不到")) {
                problems.add("查找范围敲了不存在的路径却没提示：" + notes);
            }
            // 6) 点「此电脑」「网络」「库」这类 shell 虚拟节点：必须能进，且不能弹「找不到」
            //    这些在模型里是 Win32ShellFolder2，toString 是显示名、getPath 是 ::{CLSID}，
            //    磁盘上没有同名目录——早先一律拍成字符串解析，点一下就报「找不到这个目录」。
            Object virtual = null;
            for (Object item : chooser.lookInItems()) {
                if (item instanceof File && ((File) item).getPath().startsWith("::")) {
                    virtual = item;
                    break;
                }
            }
            if (virtual == null) {
                System.out.println("- [INFO] GUI-SELFTEST | 「查找范围」里没有 shell 虚拟节点，"
                        + "点「此电脑」这一判本轮未覆盖");
            } else {
                notes.clear();
                chooser.setCurrentDirectory(tmp);
                chooser.pickLookIn(virtual);
                File now = chooser.getCurrentDirectory();
                String want = ((File) virtual).getPath();
                if (now == null || !want.equals(now.getPath())) {
                    problems.add("点「" + virtual + "」没进去，停在：" + now);
                }
                if (!notes.isEmpty()) {
                    problems.add("点「" + virtual + "」不该有提示，实际：" + notes);
                }
                System.out.println("- [INFO] GUI-SELFTEST | 点「" + virtual
                        + "」已进入（shell 虚拟节点按 File 直跳，没走字符串解析）");
            }
        } catch (Exception e) {
            problems.add("查找范围自检抛异常：" + e);
        } finally {
            com.excel2mysql.gui.FolderAwareChooser.setNoteSink(null);
            if (tmp != null) {
                deleteTree(tmp);
            }
        }
    }

    private static void approveChooser(javax.swing.JFileChooser chooser,
                                       javax.swing.plaf.basic.BasicFileChooserUI ui) {
        ui.getApproveSelectionAction().actionPerformed(
                new java.awt.event.ActionEvent(chooser,
                        java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
    }

    private static com.excel2mysql.core.TableOps.Table lastTable(MainFrame frame) {
        List<com.excel2mysql.core.TableOps.Table> all = frame.tables();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static File writeNamingFixture(File dir, String name, String[] headers,
                                           Object[] values) throws Exception {
        org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet("数据");
        org.apache.poi.ss.usermodel.Row head = sh.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            head.createCell(i).setCellValue(headers[i]);
        }
        if (values != null) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(1);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof Number) {
                    row.createCell(i).setCellValue(((Number) values[i]).doubleValue());
                } else {
                    row.createCell(i).setCellValue(String.valueOf(values[i]));
                }
            }
        }
        File f = new File(dir, name);
        writeWorkbook(wb, f);
        return f;
    }

    private static void drop_and_progress(MainFrame frame, List<String> files,
                                          List<String> problems) throws Exception {
        com.excel2mysql.gui.DropArea drop = frame.dropArea();
        if (drop == null) {
            problems.add("界面上没有拖拽接收区");
            return;
        }
        if (!drop.acceptsDrops()) {
            problems.add("拖拽区没挂上接收器，拖文件进来不会有任何反应");
        }
        if (!drop.hintText().contains("拖到此处") || !drop.hintText().contains(".zip")
                || !drop.hintText().contains("点击选择文件")) {
            problems.add("拖拽区提示没交代清楚能拖什么/能点：" + drop.hintText());
        }
        if (!drop.hasClickAction()) {
            problems.add("拖拽区没接上点击选择文件的动作");
        }
        // 单独造一个接收区验证点击回调真的会响，绕开 JFileChooser 的模态框
        final int[] probeClicks = {0};
        com.excel2mysql.gui.DropArea probe = new com.excel2mysql.gui.DropArea(
                paths -> { }, () -> probeClicks[0]++);
        probe.fireClick();
        if (probeClicks[0] != 1) {
            problems.add("点击拖拽区没有触发选择文件（回调次数 " + probeClicks[0] + "）");
        }
        checkWindowIcons(frame, problems);
        // 窗口尺寸：客户区不得小于设计值（内容要摆得开），整窗必须落在屏幕可用区内
        // （首次打开就能看见全部文字与按钮，老屏上也不会被任务栏吃掉半截）
        java.awt.Rectangle work = com.excel2mysql.gui.Windows.workArea(frame);
        java.awt.Dimension client = frame.getContentPane().getSize();
        // 设计值 1100x780 是 Qt 的逻辑像素；150% 屏上应为 1650x1170。
        // 期望值在这里独立按 DPI 算一遍，UiScale 被改坏时这条会红。
        int dpi = java.awt.Toolkit.getDefaultToolkit().getScreenResolution();
        int wantClientW = (int) Math.round(1100 * Math.max(1.0, dpi / 96.0));
        int wantClientH = (int) Math.round(780 * Math.max(1.0, dpi / 96.0));
        if (client.width < wantClientW || client.height < wantClientH) {
            problems.add("客户区没按 DPI 折算到 " + wantClientW + "x" + wantClientH
                    + "（实际 " + client + "），整窗会比 Python 小一圈");
        }
        // 界面字体是随包的思源黑体，9pt 在 144dpi 下折成 18px（Python 侧同一档字号）。
        java.awt.Font uiFont = UIManager.getFont("Label.font");
        int wantUiPx = (int) Math.round(9 * dpi / 72.0);
        if (uiFont == null || uiFont.getSize() != wantUiPx) {
            problems.add("界面默认字体没对齐 9pt/" + wantUiPx + "px：" + uiFont);
        }
        // 契约是"画得出汉字"：2026-11-05 Win7 现场那次就是字体那一档落到了没有中文字形的
        // Segoe UI 上，整页中文成方框而界面一个字都不报错
        if (uiFont != null && !uiFont.canDisplay('中')) {
            problems.add("界面默认字体画不出汉字（中文会成一排方框）：" + uiFont);
        }
        cjkFontChoice(problems);
        if (!work.contains(frame.getBounds())) {
            problems.add("主窗口超出屏幕可用区：" + frame.getBounds() + " 可用 " + work);
        }
        checkPopupWrap(problems);
        // 故意要一个装不下的尺寸：夹取逻辑没生效的话，这条会红
        // （本机屏幕大，"没超屏"那种断言在这台机器上咬不住东西）
        javax.swing.JFrame huge = new javax.swing.JFrame("夹取检查");
        huge.getContentPane().add(new javax.swing.JLabel("占位"));
        com.excel2mysql.gui.Windows.clientSize(huge.getContentPane(), huge, 99999, 99999);
        if (!work.contains(huge.getBounds())) {
            problems.add("要求 99999 宽时没夹回屏幕可用区：" + huge.getBounds()
                    + " 可用 " + work);
        }
        huge.dispose();
        for (java.awt.Window dlg : fitCheckDialogs(frame)) {
            try {
                if (!work.contains(dlg.getBounds())) {
                    problems.add("弹窗超出屏幕可用区：" + dlg.getClass().getSimpleName()
                            + " " + dlg.getBounds() + " 可用 " + work);
                }
                java.awt.Dimension dc = ((java.awt.Container) dlg).getSize();
                if (dc.width < 1 || dc.height < 1) {
                    problems.add("弹窗没算出尺寸：" + dlg.getClass().getSimpleName() + dc);
                }
                java.awt.Container dcontent = (java.awt.Container) dlg;
                java.awt.Dimension dmin = dcontent.getMinimumSize();
                if (dc.width < dmin.width || dc.height < dmin.height) {
                    problems.add("弹窗比内容要求的最小尺寸还小，文字会被裁："
                            + dlg.getClass().getSimpleName() + " 实测 " + dc
                            + " 最小 " + dmin);
                }
            } finally {
                dlg.dispose();
            }
        }
        if (files.isEmpty()) {
            problems.add("自检没有夹具可拖");
            return;
        }
        int before = frame.tables().size();
        List<String> one = new ArrayList<String>();
        one.add(files.get(0));
        if (frame.acceptedDrops(one).size() != 1) {
            problems.add("拖拽把 xlsx 挡在了外面");
        }
        // 夹具在第一屏已经全部加载过，再拖一次走的是"重复跳过"那条路
        String dupSummary = frame.addFiles(one);
        if (frame.tables().size() != before) {
            problems.add("重复添加把同一张表加了第二遍：" + before + " → " + frame.tables().size());
        }
        if (!frame.logPanel().contains("[重复跳过]")) {
            problems.add("重复添加没有说明为什么跳过");
        }
        if (!dupSummary.contains("另有 1 项重复已跳过")) {
            problems.add("添加结果汇总没把重复项算进去：" + dupSummary.replace((char) 10, ' '));
        }
        List<String> junk = new ArrayList<String>();
        junk.add(new java.io.File("readme.txt").getPath());
        if (!frame.acceptedDrops(junk).isEmpty()) {
            problems.add("txt 混进了拖拽接收列表");
        }
        if (!frame.logPanel().contains("拖入或粘贴的不是 Excel 或 ZIP 文件，已忽略")) {
            problems.add("拖进或粘进非 Excel 文件时没有任何说明");
        }

        // 页内不再摆进度条：导入进度只在那个非模态进度框里说一次
        if (frame.excelActionBar() != null && hasProgressBar(frame.excelActionBar())) {
            problems.add("Excel 页底部还留着页内进度条");
        }
        frame.clearAll();
        if (!frame.tables().isEmpty()) {
            problems.add("「清空待上传文件」没清空表");
        }
        // Del 键删掉选中的行（多选就是批量删）。取的是文件表自己绑的那个动作：
        // 键绑定由 KeyboardFocusManager 在事件进队列时解析，自检里直接派发 KeyEvent 不会走这条路。
        List<String> del3 = new ArrayList<String>();
        del3.add(files.get(0));
        del3.add(files.get(1));
        del3.add(files.get(2));
        frame.addFiles(del3);
        final javax.swing.JTable ftable = frame.fileTable();
        ftable.changeSelection(1, 3, false, false);
        if (ftable.getSelectedRowCount() != 1 || ftable.getSelectedRow() != 1) {
            problems.add("文件表不是按整行选中（点一格选中了 " + ftable.getSelectedRowCount()
                    + " 行），Del 删行的语义立不住");
        }
        Object delKey = ftable.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0));
        final javax.swing.Action delAct = delKey == null ? null : ftable.getActionMap().get(delKey);
        if (delAct == null) {
            problems.add("Del 键没绑在待上传文件表上");
        } else {
            delAct.actionPerformed(new java.awt.event.ActionEvent(ftable, 0, "del"));
            if (frame.tables().size() != 2 || ftable.getRowCount() != 2) {
                problems.add("选中一行按 Del 没删掉这个文件：剩 " + frame.tables().size());
            }
            if (!frame.logPanel().contains("[删除] 已移除 1 个待上传文件，列表剩 2 个")) {
                problems.add("Del 删除没说清删了几个、还剩几个："
                        + lastLine(frame.logPanel().text()));
            }
            ftable.setRowSelectionInterval(0, 1);
            delAct.actionPerformed(new java.awt.event.ActionEvent(ftable, 0, "del"));
            if (!frame.tables().isEmpty() || ftable.getRowCount() != 0
                    || frame.importButton().isEnabled()) {
                problems.add("多选两行按 Del 应一次删空并把导入按钮置灰："
                        + frame.tables().size() + "/" + ftable.getRowCount());
            }
            if (!frame.logPanel().contains("[删除] 已移除 2 个待上传文件，列表剩 0 个")) {
                problems.add("批量删除的留痕没写清数量");
            }
            ftable.clearSelection();
            delAct.actionPerformed(new java.awt.event.ActionEvent(ftable, 0, "del"));
            if (!frame.logPanel().contains("没有选中文件")) {
                problems.add("一行都没选中时按 Del 没有任何留痕，成了静默 no-op");
            }
            frame.addFiles(one);
            frame.startTask(MainFrame.RES_EXCEL);
            ftable.setRowSelectionInterval(0, 0);
            int keep = frame.tables().size();
            delAct.actionPerformed(new java.awt.event.ActionEvent(ftable, 0, "del"));
            if (frame.tables().size() != keep || !frame.lastDialogText().contains("已有任务在跑")) {
                problems.add("解析任务在跑时按 Del 应拦下并说清在等谁");
            }
            frame.endTask();
            frame.clearAll();     // 后面的用例仍按空清单起步
        }

        // 占位提示必须是空态就能看见的灰字，不是只有悬停才出的 tooltip
        String excelHint = frame.prefixField().hintText();
        if (!com.excel2mysql.gui.PrefixHint.PLACEHOLDER.equals(excelHint)) {
            problems.add("Excel 页前缀框的占位句不是共用那一条：" + excelHint);
        }
        frame.prefixField().setText("");
        if (!frame.prefixField().shouldPaintHint()) {
            problems.add("前缀框空着却没该画占位");
        }
        frame.prefixField().setText("ods_");
        if (frame.prefixField().shouldPaintHint()) {
            problems.add("已经有内容还画占位，会盖住用户写的字");
        }
        frame.prefixField().setText("");
        String sqlHint = frame.sqlPage().prefixField().hintText();
        if (!com.excel2mysql.gui.PrefixHint.PLACEHOLDER.equals(sqlHint)
                || !excelHint.equals(sqlHint)) {
            problems.add("两页前缀框的占位句没统一：Excel 页「" + excelHint
                    + "」/ SQL 页「" + sqlHint + "」");
        }
        if (frame.databaseChooserEnabled()) {
            problems.add("还没连接就能选库，库名只能靠猜");
        }
        // 启动默认收起（用户口径）：首屏只留一条状态栏，告警直接指向「设置数据源信息」
        if (frame.isConnExpanded() || !frame.connEditVisible()
                || !frame.connStateText().contains("设置数据源信息")) {
            problems.add("启动时连接区没有默认收起，或告警没指向「设置数据源信息」："
                    + frame.connStateText());
        }
        frame.setConnExpanded(true);
        if (!frame.isConnExpanded() || frame.connEditVisible()) {
            problems.add("点「设置数据源信息」没能展开连接表单");
        }
        // 用户口径：当前库只在顶部状态栏说一次，三页里不再各写一条「目标库：xxx ——」
        if (countDbPointers(frame.tabs()) != 0) {
            problems.add("页签里还写着「目标库：」那一行，应当整行删掉："
                    + countDbPointers(frame.tabs()) + " 处");
        }
        // 此刻连接表单是展开的（上面刚点开过），告警要指向这颗真在界面上的按钮
        if (!frame.connStateText().startsWith("⚠ 未选择目标库")
                || !frame.connStateText().contains("连接并加载库")
                || !com.excel2mysql.core.LogText.NO_DB_HINT.startsWith("未选择目标库")) {
            problems.add("未选库时顶部状态栏没承担唯一告警，或话术还是两套："
                    + frame.connStateText());
        }
        // ⚠ 在 Microsoft YaHei 下没有字形，直接画就是豆腐块；显示文字里不许留它，
        // 但 hintText 要保留原文给双端对撞。图标由 HintLabel 自绘。
        String badShown = frame.connState().getText();
        if (badShown.contains("⚠") || !frame.connStateText().contains("未选择目标库")) {
            problems.add("告警条显示文字仍是 ⚠ 原文或丢了正文：" + badShown);
        }
        frame.setSelectedDatabase("testdb");
        // 选定库之后只有一条地方说"现在是哪个库"：顶部状态栏；三页里那条整行删掉了
        if (!"● 目标库 testdb".equals(frame.connStateText())
                || !frame.connState().isOk()
                || countDbPointers(frame.tabs()) != 0) {
            problems.add("选定库之后状态栏没报出当前库，或页签里又冒出「目标库：」那一行："
                    + frame.connStateText());
        }
        // 三页各有一句介绍，而且必须是同一块底板：只有一页醒目等于没有这条规则
        if (!"中英表头建模，回写库".equals(frame.excelIntroText())) {
            problems.add("Excel 页顶部那句介绍不对：" + frame.excelIntroText());
        }
        if (!"解析建表语句，回写库".equals(frame.sqlPage().introText())) {
            problems.add("SQL 页顶部那句介绍不对：" + frame.sqlPage().introText());
        }
        if (!"执行脚本与临时语句".equals(frame.sqlRunHintText())) {
            problems.add("SQL 执行器页顶部那句介绍不对：" + frame.sqlRunHintText());
        }
        com.excel2mysql.gui.StaticText intro1 = frame.excelIntro();
        com.excel2mysql.gui.StaticText intro2 = frame.sqlPage().introField();
        com.excel2mysql.gui.StaticText intro3 = frame.sqlRunIntro();
        if (!intro1.getForeground().equals(intro2.getForeground())
                || !intro2.getForeground().equals(intro3.getForeground())
                || !intro1.getBackground().equals(intro2.getBackground())
                || !intro2.getBackground().equals(intro3.getBackground())) {
            problems.add("三页的介绍条不是同一块底板：前景 "
                    + intro1.getForeground() + "/" + intro2.getForeground() + "/"
                    + intro3.getForeground() + "，背景 " + intro1.getBackground() + "/"
                    + intro2.getBackground() + "/" + intro3.getBackground());
        }
        if (intro1.getFont().getStyle() != java.awt.Font.BOLD
                || intro2.getFont().getStyle() != java.awt.Font.BOLD
                || intro3.getFont().getStyle() != java.awt.Font.BOLD) {
            problems.add("介绍条没加粗：这页是干什么的一眼看不出来");
        }
        // host:port 与用户名只收进状态栏的悬停提示，页面上没有第二条地方复述它
        if (!"● 目标库 testdb".equals(frame.connStateText())
                || !frame.connTip().contains("127.0.0.1:3306")
                || !frame.connTip().contains("root")) {
            problems.add("状态栏没把 host:port 收住：" + frame.connStateText()
                    + " / " + frame.connTip());
        }
        // 选定库之后连接表单自动收起：跑业务时不必一直看着五行环境信息（用户口径）
        if (frame.isConnExpanded() || !frame.connEditVisible()) {
            problems.add("选定库之后连接表单没自动收起，环境信息仍常驻首屏");
        }
        frame.setConnExpanded(true);
        if (!frame.isConnExpanded() || frame.connEditVisible()) {
            problems.add("点「设置数据源信息」没能展开表单，或那颗按钮没让位给「收起」");
        }
        frame.setConnExpanded(false);
        if (frame.isConnExpanded() || !frame.connEditVisible()) {
            problems.add("点「收起」没能收回一条状态栏");
        }
        // 「重新填库列表」不等于「选定目标库」：Swing 里 addItem 会把第一项选上，
        // 一加载就收起等于把用户下一步要用的那颗下拉藏起来
        frame.setConnExpanded(true);
        frame.reloadDatabases(java.util.Arrays.asList("alpha", "beta"));
        if (!frame.isConnExpanded()) {
            problems.add("只是重新填了一遍库列表就把连接区收起了");
        }
        frame.setSelectedDatabase("beta");
        if (frame.isConnExpanded()) {
            problems.add("在目标库里选了一个还没自动收起");
        }
        frame.setSelectedDatabase("testdb");
        frame.hostField().setText("10.0.0.9");
        if (!frame.connTip().contains("10.0.0.9:3306")) {
            problems.add("改了主机但状态栏提示还是旧地址：" + frame.connTip());
        }
        if (countHostInTabs(frame.tabs(), "10.0.0.9") != 0) {
            problems.add("页签里又把 host 拼回某条提示上了："
                    + countHostInTabs(frame.tabs(), "10.0.0.9") + " 处");
        }
        frame.hostField().setText("127.0.0.1");
        // 提示条要能真的按住拖选再复制（Python 那边是开了 TextSelectableByMouse 的 QLabel）
        dragSelectable(frame.connState(), "连接状态栏", problems);
    }

    /** 页签内容里有几处文字写着这个主机地址——三页的指向行删了，应当数出 0。 */
    private static int countHostInTabs(java.awt.Component root, String host) {
        int n = 0;
        if (root instanceof javax.swing.JLabel
                && String.valueOf(((javax.swing.JLabel) root).getText()).contains(host)) {
            n++;
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component c : ((java.awt.Container) root).getComponents()) {
                n += countHostInTabs(c, host);
            }
        }
        return n;
    }

    /**
     * 拖选复制的取证：运行时判"它是不是文本组件、能不能选出整行"，
     * 不写成编译期依赖——换成 JLabel 后要的是这条断言变红，不是整包编不过。
     */
    private static void dragSelectable(java.awt.Component c, String who, List<String> problems) {
        if (!(c instanceof javax.swing.text.JTextComponent)) {
            problems.add(who + "不是可选字的文本组件，拖选复制根本做不到：" + c.getClass().getName());
            return;
        }
        javax.swing.text.JTextComponent t = (javax.swing.text.JTextComponent) c;
        if (t.isEditable()) {
            problems.add(who + "还能编辑，那是输入框不是说明条");
        }
        t.selectAll();
        if (t.getSelectedText() == null || !t.getSelectedText().equals(t.getText())) {
            problems.add(who + "选不出整行文字：" + t.getSelectedText());
        }
    }

    /**
     * 第四页的库名校验闸：obj 那一格依次改成空串、纯空白、一串带反引号的，三次点生成都不许落产物。
     *
     * <p>钉的是外场真撞的那一支：源库取顶部连接栏所选库、目标库随手可编辑，一旦空着，
     * 少这道校验就会交出一份 `USE ` `;`、`FROM ` `.`t_oil`` 的坏 .sql —— 它一句错都不报，
     * 只有打开交付件的人才看得出来。所以判据是"临时根里一个 layer_ 开头的产物目录都不许有"，
     * 而不是"有没有报错"：拦不下时产物照样落得整整齐齐。
     */
    /**
     * 第四页免配模板那条路：按系统目录/ods 前缀先验 + 表名细化 + 条款 183 维度判每张表的归属。
     *
     * <p>判得准不准是业务口径；这几件事不许退化——先验命中的领域码按条款固定（不按首次出现编号）、
     * 子领域只在先验领域内细化、对象维度只认十类官方键（拼音 dim 名会被生成器静默丢掉）、
     * 数不到词就不猜（逐表点名标待确认）、自动识别一源一表（领域/子领域只做分类，不合并）。
     */
    private static void layer_domain_guess(MainFrame frame, String dir, List<String> problems) {
        com.excel2mysql.gui.LayerPage lp = frame.layerPage();
        if (lp == null) {
            problems.add("第四页没挂上，判领域这条用例没跑");
            return;
        }
        com.excel2mysql.core.Names nm =
                new com.excel2mysql.core.Names(com.excel2mysql.core.PinyinLoader.get());
        // 1) 没有系统先验时按领域词判：人员 → 保障/实力数据，码与目标表按条款固定
        java.util.List<com.excel2mysql.core.DomainGuess.Input> one =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        one.add(guessIn("人员信息表（人事处）.xlsx", "", "人员信息表", "ren_yuan_xin_xi",
                3, "xing_ming", new String[]{"姓名", "所属机构", "入职日期"}));
        com.excel2mysql.core.DomainGuess.Verdict a = DomainGuess.guess(one, nm).get(0);
        if (!a.guessed || !"保障".equals(a.domain) || !"实力数据".equals(a.map.domainSub)) {
            problems.add("表注释里的「人员」没被判成保障/实力数据：" + DomainGuess.describe(a));
        }
        if (!"DOM01".equals(a.map.domainCode)) {
            problems.add("保障的领域码不是条款固定的 DOM01：" + a.map.domainCode);
        }
        if (!"dwd_slsj_ren_yuan_xin_xi".equals(a.map.domainTarget)) {
            problems.add("一源一表的领域目标表名不对： " + a.map.domainTarget);
        }
        if (!"后勤保障主题".equals(a.map.theme)) {
            problems.add("主题没跟领域/子领域走：" + a.map.theme);
        }
        if (!a.basis.contains("领域词") || !a.basis.contains("人员")) {
            problems.add("依据没写明数了哪个词：" + a.basis);
        }
        if (a.map.objDims == null || !a.map.objDims.contains("dim_person")
                || !a.map.objDims.contains("dim_org")) {
            problems.add("字段注释里的姓名/机构没汇成官方维度：" + a.map.objDims);
        }
        if (!"自动识别".equals(a.map.status)) {
            problems.add("判出来的行状态不是自动识别：" + a.map.status);
        }
        // 2) 系统先验定领域、表名只在领域内细化子领域：军油工程里的被装表 → 采购供应/被装保障
        java.util.List<com.excel2mysql.core.DomainGuess.Input> refine =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        refine.add(guessIn("被装发放台账.xlsx", "军油工程", "被装发放台账", "t_bz", 2, "",
                new String[]{"被装名称", "发放数量"}));
        com.excel2mysql.core.DomainGuess.Verdict rf = DomainGuess.guess(refine, nm).get(0);
        if (!"采购供应".equals(rf.domain) || !"被装保障".equals(rf.map.domainSub)) {
            problems.add("系统先验+表名细化没落到采购供应/被装保障：" + DomainGuess.describe(rf)
                    + " | " + rf.basis);
        }
        if (!rf.basis.contains("先验") || !rf.basis.contains("细化")) {
            problems.add("先验与细化两段依据没写全：" + rf.basis);
        }
        if (!"DOM04".equals(rf.map.domainCode) || !"后勤保障主题".equals(rf.map.theme)) {
            problems.add("采购供应的码/主题不对：" + rf.map.domainCode + " / " + rf.map.theme);
        }
        if (!"dwd_bzbz_t_bz".equals(rf.map.domainTarget)) {
            problems.add("先验细化后的目标表没带源表名：" + rf.map.domainTarget);
        }
        // 3) 数不到词就不猜
        java.util.List<com.excel2mysql.core.DomainGuess.Input> none =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        none.add(guessIn("临时导出.xlsx", "", "临时表", "lin_shi", 2, "", new String[]{"甲", "乙"}));
        com.excel2mysql.core.DomainGuess.Verdict n = DomainGuess.guess(none, nm).get(0);
        if (n.guessed || !"待确认".equals(n.map.status)
                || !n.basis.contains("都没有可识别的业务名词")) {
            problems.add("判不出却被塞了领域：" + DomainGuess.describe(n));
        }
        if (!DomainGuess.describe(n).contains("判不出领域")) {
            problems.add("判不出的那句日志没点名：" + DomainGuess.describe(n));
        }
        // 4) 一源一表：两张人员表共用领域码，但目标表各带自己的源表名；采购另起一档
        java.util.List<com.excel2mysql.core.DomainGuess.Input> group =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        group.add(guessIn("人员信息表.xlsx", "", "人员信息表", "t_a", 1, "", new String[]{}));
        group.add(guessIn("人员花名册.xlsx", "", "人员花名册", "t_b", 1, "", new String[]{}));
        group.add(guessIn("采购台账.xlsx", "", "", "t_c", 1, "", new String[]{}));
        java.util.List<com.excel2mysql.core.DomainGuess.Verdict> g =
                DomainGuess.guess(group, nm);
        if (!g.get(0).map.domainCode.equals(g.get(1).map.domainCode)) {
            problems.add("同子领域的两张表没共用领域码：" + g.get(0).map.domainCode + " / "
                    + g.get(1).map.domainCode);
        }
        if (g.get(0).map.domainTarget.equals(g.get(1).map.domainTarget)
                || !"dwd_slsj_t_a".equals(g.get(0).map.domainTarget)
                || !"dwd_slsj_t_b".equals(g.get(1).map.domainTarget)) {
            problems.add("自动识别没做到一源一表：" + g.get(0).map.domainTarget + " / "
                    + g.get(1).map.domainTarget);
        }
        if (!"DOM04".equals(g.get(2).map.domainCode) || !"采购供应".equals(g.get(2).domain)) {
            problems.add("按文件名命中失败或领域码排错：" + g.get(2).domain + " "
                    + g.get(2).map.domainCode);
        }
        if (!g.get(2).basis.contains("采购")) {
            problems.add("文件名那条依据没写出来：" + g.get(2).basis);
        }
        // 5) 系统目录当先验：财务系统的无名词明细也归财务，不进待确认
        java.util.List<com.excel2mysql.core.DomainGuess.Input> sys =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        sys.add(guessIn("明细.xlsx", "财务系统", "", "ming_xi", 1, "", new String[]{}));
        com.excel2mysql.core.DomainGuess.Verdict s = DomainGuess.guess(sys, nm).get(0);
        if (!"财务系统".equals(s.map.system) || !"财务".equals(s.domain)
                || !s.basis.contains("系统目录「财务系统」先验")) {
            problems.add("系统目录没进系统列或没当先验：" + s.map.system + " | " + s.domain
                    + " | " + s.basis);
        }
        // 6) ods 物理表名前缀当先验：读源库结构时没有目录，只能靠前缀
        java.util.List<com.excel2mysql.core.DomainGuess.Input> pre =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        pre.add(guessIn("ods_jygcxt_t_you.xlsx", "", "油料收支表", "ods_jygcxt_t_you", 1, "",
                new String[]{}));
        com.excel2mysql.core.DomainGuess.Verdict pv = DomainGuess.guess(pre, nm).get(0);
        if (!"采购供应".equals(pv.domain) || !"油料保障".equals(pv.map.domainSub)
                || !"军油工程".equals(pv.map.system) || !pv.basis.contains("表名前缀「ods_jygcxt」")) {
            problems.add("ods 前缀先验没生效：" + DomainGuess.describe(pv) + " | " + pv.basis);
        }
        // 7) 管道/系统表归综合/信息化；十类维度只出官方键
        java.util.List<com.excel2mysql.core.DomainGuess.Input> meta =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        meta.add(guessIn("系统用户表.xlsx", "", "系统用户表", "sys_user", 2, "",
                new String[]{"用户编码", "角色名称"}));
        com.excel2mysql.core.DomainGuess.Verdict mv = DomainGuess.guess(meta, nm).get(0);
        if (!"综合".equals(mv.domain) || !"信息化".equals(mv.map.domainSub)) {
            problems.add("系统用户表没归综合/信息化：" + DomainGuess.describe(mv));
        }
        for (com.excel2mysql.core.DomainGuess.Verdict vv : new com.excel2mysql.core.DomainGuess.Verdict[]{
                a, rf, g.get(0), g.get(2), s, pv}) {
            if (vv.map.objDims == null || vv.map.objDims.isEmpty()) {
                continue;
            }
            for (String dim : vv.map.objDims.split("[;；,，]")) {
                if (!com.excel2mysql.core.Taxonomy.isDim(dim.trim())) {
                    problems.add("映射里出现了非十类维度：" + dim + "（生成器会静默丢掉）");
                }
            }
        }
        // 7b) 管道表判定分档：中文字典/日志与 sys_ 前缀是铁证；只有英文表名后缀（_user/_catalog）
        // 是弱证据，字段里明确是业务实体（姓名/身份证）就不许被劫进信息化——人员主档表正是这样
        java.util.List<com.excel2mysql.core.DomainGuess.Input> biz =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        biz.add(guessIn("人员主档表.xlsx", "仓库业务综合管理系统", "人员主档表",
                "ods_ckywzhglxt_t_staff_user", 3, "",
                new String[]{"姓名", "性别", "身份证号"}));
        com.excel2mysql.core.DomainGuess.Verdict bizV = DomainGuess.guess(biz, nm).get(0);
        if ("信息化".equals(bizV.map.domainSub)) {
            problems.add("业务主数据被表名后缀 _user 判成了系统表：" + DomainGuess.describe(bizV));
        }
        if (!bizV.map.objDims.contains("dim_person")) {
            problems.add("人员主档表的业务维度没判出来：" + bizV.map.objDims);
        }
        biz.clear();
        biz.add(guessIn("字典数据表.xlsx", "仓库业务综合管理系统", "字典数据表",
                "ods_ckywzhglxt_sys_dict_data", 2, "",
                new String[]{"字典类型", "类型编码"}));
        com.excel2mysql.core.DomainGuess.Verdict dictV = DomainGuess.guess(biz, nm).get(0);
        if (!"信息化".equals(dictV.map.domainSub)) {
            problems.add("中文名带「字典」的仍是铁证管道表，不该被业务维度翻回去："
                    + DomainGuess.describe(dictV));
        }
        // 7c) 英文词按整词命中：字段名 operation_type 不许再把「ration」算成给养保障
        biz.clear();
        biz.add(guessIn("数据审计记录表.xlsx", "仓库业务综合管理系统", "数据审计记录表",
                "ods_ckywzhglxt_dc_data_audit_record", 2, "",
                new String[]{"操作类型", "审计结果"}));
        com.excel2mysql.core.DomainGuess.Verdict tok = DomainGuess.guess(biz, nm).get(0);
        if (!"物资保障".equals(tok.map.domainSub)) {
            problems.add("字段名 operation 的子串误命中没修掉（应留在先验的物资保障）："
                    + DomainGuess.describe(tok));
        }
        // 7d) 先验子领域打底：给养系统的实力表字段里带一句「油料」，不许整表翻成油料保障
        biz.clear();
        biz.add(guessIn("单位实力查询.xlsx", "军队给养系统", "单位实力查询",
                "ods_jdjyxt_dwslcx", 3, "",
                new String[]{"油料实力", "给养实力", "人员实力"}));
        com.excel2mysql.core.DomainGuess.Verdict pri = DomainGuess.guess(biz, nm).get(0);
        if (!"给养保障".equals(pri.map.domainSub)) {
            problems.add("先验子领域没打底，字段噪声把表翻走了：" + DomainGuess.describe(pri));
        }
        // 7e) 字段里数不出维度词时按表名补判：整表就是「人员」，obj 不该少这一张
        biz.clear();
        biz.add(guessIn("人员主档表.xlsx", "军队人员保障信息综合管理系统", "人员主档表",
                "ods_jdbzkxt_ryzdb", 0, "", new String[]{}));
        com.excel2mysql.core.DomainGuess.Verdict nameDim = DomainGuess.guess(biz, nm).get(0);
        if (!nameDim.map.objDims.contains("dim_person")) {
            problems.add("表名补判维度没生效：" + DomainGuess.describe(nameDim));
        }
        // 8) 一源一表：三张各 50 列的同子领域表产三张目标表，互不合并
        java.util.List<com.excel2mysql.core.DomainGuess.Input> wide =
                new java.util.ArrayList<com.excel2mysql.core.DomainGuess.Input>();
        for (int t = 0; t < 3; t++) {
            wide.add(guessIn("油料表" + t + ".xlsx", "军油工程", "油料表" + t, "t_you_" + t,
                    50, "", new String[]{}));
        }
        java.util.List<com.excel2mysql.core.DomainGuess.Verdict> wv = DomainGuess.guess(wide, nm);
        java.util.Set<String> wTargets = new java.util.LinkedHashSet<String>();
        for (com.excel2mysql.core.DomainGuess.Verdict vv : wv) {
            wTargets.add(vv.map.domainTarget);
        }
        if (wTargets.size() != 3 || !wTargets.contains("dwd_ylbz_t_you_0")
                || !wTargets.contains("dwd_ylbz_t_you_1")
                || !wTargets.contains("dwd_ylbz_t_you_2")) {
            problems.add("同子领域的三张源表没做到一源一表：" + wTargets);
        }
        for (com.excel2mysql.core.DomainGuess.Verdict vv : wv) {
            if (!vv.basis.contains("一源一表")) {
                problems.add("目标表依据没写一源一表：" + vv.basis);
            }
        }
        // 9) 走用户真入口：拖进第四页那块虚线区
        if (!lp.layerDropArea().acceptsDrops() || !lp.layerDropArea().hasClickAction()) {
            problems.add("第四页的拖拽区没接拖或没接点击选择");
        }
        if (!com.excel2mysql.gui.DropArea.LAYER_HINT.equals(lp.layerDropArea().hintText())) {
            problems.add("第四页拖拽区的引导句不是这一页那句：" + lp.layerDropArea().hintText());
        }
        if (lp.layerDropArea().hintText().contains("Ctrl+V")) {
            problems.add("第四页没接粘贴入口，引导句里不许写 Ctrl+V");
        }
        java.util.List<String> drop = new java.util.ArrayList<String>();
        java.io.File[] kids = new java.io.File(dir).listFiles();
        if (kids != null) {
            java.util.Arrays.sort(kids);
            for (java.io.File f : kids) {
                String low = f.getName().toLowerCase();
                if (low.endsWith(".xlsx") || low.endsWith(".xls") || low.endsWith(".zip")) {
                    drop.add(f.getPath());
                }
            }
        }
        if (drop.size() < 5) {
            problems.add("判领域的端到端只拿到 " + drop.size() + " 个夹具，不够判");
            return;
        }
        // 8b) 嵌套目录：数据集的「系统/分类/表」两层，系统要取最近的已登记祖先，
        //     不能把「油料保障」这种分类目录当系统名写进映射
        java.io.File nestedCat = null;
        try {
            nestedCat = new java.io.File(new java.io.File(
                    java.nio.file.Files.createTempDirectory("e2m-layer-nested").toFile(),
                    "军油工程"), "油料保障");
        } catch (java.io.IOException ex) {
            problems.add("嵌套目录用例建临时目录失败：" + ex.getMessage());
        }
        if (nestedCat != null) {
            if (!nestedCat.mkdirs()) {
                problems.add("嵌套目录用例没建出目录：" + nestedCat);
            } else {
                java.io.File copy = new java.io.File(nestedCat, "人员信息表（人事处）.xlsx");
                try {
                    java.nio.file.Files.copy(
                            new java.io.File(dir, "人员信息表（人事处）.xlsx").toPath(),
                            copy.toPath());
                    lp.layerDropArea().fireDrop(java.util.Collections.singletonList(copy.getPath()));
                    com.excel2mysql.core.MappingFile.TableMap nested = lp.mapping() == null
                            || lp.mapping().tables().isEmpty() ? null : lp.mapping().tables().get(0);
                    if (nested == null || !"军油工程".equals(nested.system)
                            || !"采购供应".equals(nested.domain)) {
                        problems.add("嵌套目录没取到系统先验：" + (nested == null ? "无行"
                                : nested.system + " / " + nested.domain));
                    }
                } catch (java.io.IOException ex) {
                    problems.add("嵌套目录用例复制夹具失败：" + ex.getMessage());
                }
            }
        }
        int before = lp.mappingRowCount();
        lp.layerDropArea().fireDrop(drop);
        if (!lp.mappingLabelText().startsWith("按文件识别")) {
            problems.add("拖进文件后没换成识别结果：" + lp.mappingLabelText());
        }
        if (lp.mappingRowCount() < 5 || lp.mappingRowCount() == before) {
            problems.add("识别结果没覆盖映射表：" + lp.mappingRowCount() + " 行（原来 "
                    + before + " 行）");
        }
        if (lp.mapping() == null) {
            problems.add("识别结果没落到 mapping 上");
            return;
        }
        boolean hitPerson = false;
        boolean hitPending = false;
        for (com.excel2mysql.core.MappingFile.TableMap m : lp.mapping().tables()) {
            if ("保障".equals(m.domain) && m.domainTarget != null
                    && m.domainTarget.startsWith("dwd_")) {
                hitPerson = true;
            }
            if ("待确认".equals(m.status)) {
                hitPending = true;
            }
        }
        String log = lp.logPanel().text();
        if (!hitPerson || !log.contains("→ 领域「保障」")) {
            problems.add("夹具里的「人员信息表」没被判成保障领域，日志也没点名："
                    + tailOf(log).replace((char) 10, ' '));
        }
        if (!hitPending || !log.contains("判不出领域")) {
            problems.add("判不出的表没被标成待确认或没在日志逐表点名");
        }
        if (!log.contains("LAYER")) {
            problems.add("识别这一条没在日志留痕");
        }
    }

    /** 造一条判定输入（用例底座，不走界面也不连库）。 */
    private static com.excel2mysql.core.DomainGuess.Input guessIn(String fileName, String system,
                                                                  String tableCn, String tableEn,
                                                                  int fieldCount, String pkField,
                                                                  String[] comments) {
        com.excel2mysql.core.DomainGuess.Input in = new com.excel2mysql.core.DomainGuess.Input();
        in.fileName = fileName;
        in.system = system;
        in.tableCn = tableCn;
        in.tableEn = tableEn;
        in.fieldCount = fieldCount;
        in.pkField = pkField;
        for (String c : comments) {
            in.fieldComments.add(c);
        }
        return in;
    }

    private static void layer_db_guard(com.excel2mysql.gui.LayerPage lp, MainFrame frame,
                                       List<String> problems) {
        String keep = lp.objDbField().getText();
        String[] bad = {"", "   ", "ods`x`"};
        for (String v : bad) {
            lp.objDbField().setText(v);
            java.io.File root;
            try {
                root = java.nio.file.Files.createTempDirectory("e2m-layer-guard").toFile();
            } catch (java.io.IOException e) {
                problems.add("建库名校验用例的临时产物根失败：" + e);
                break;
            }
            lp.generateTo(com.excel2mysql.core.LayerGen.Layer.OBJ, root.getPath());
            if (hasLayerDir(root)) {
                problems.add("obj 库名填的是「" + v + "」却没拦下，产物照落了（交付件里是空/坏库名）");
            }
            if (!lp.logPanel().isErrorLine("已拦下 OBJ 生成")) {
                problems.add("拦下不合规库名没在第四页日志里按错误级说理由");
            }
            if (!frame.lastDialogIsError() || !frame.lastDialogText().contains("库名")) {
                problems.add("拦下不合规库名没有以出错弹窗说清是哪一格，正文：" + frame.lastDialogText());
            }
            deleteTree(root);
        }
        lp.objDbField().setText(keep);
    }

    /** 这一页的控件树里还有没有下拉框：源库改取顶部连接栏后，本页不该再有任何 JComboBox。 */
    /** 达梦目录视图的一行：给第五页的转换口径当夹具，不连真库也能验。 */
    private static com.excel2mysql.core.DmMysql.Column dmCol(String name, String type,
                                                             int len, int prec, int scale,
                                                             boolean nullable, String def,
                                                             String comment) {
        com.excel2mysql.core.DmMysql.Column c = new com.excel2mysql.core.DmMysql.Column();
        c.name = name;
        c.type = type;
        c.length = len;
        c.precision = prec;
        c.scale = scale;
        c.nullable = nullable;
        c.dbDefault = def;
        c.comment = comment;
        return c;
    }

    /** 只换表名与注释的简版夹具：给过滤与摘要用，翻译口径仍由 dmFixture 那条判。 */
    private static com.excel2mysql.core.DmMysql.Table dmFixtureNamed(String table, String cm) {
        com.excel2mysql.core.DmMysql.Table t = dmFixture();
        t.name = table;
        t.comment = cm;
        return t;
    }

    /** 一张 MySQL 侧的夹具：自增主键、无符号整数、字符长度、默认值、认不出的类型各来一样。 */
    private static com.excel2mysql.core.DmMysql.Table myFixture() {
        com.excel2mysql.core.DmMysql.Table t = new com.excel2mysql.core.DmMysql.Table();
        t.schema = "testdb";
        t.name = "t_oil";
        t.comment = "油料台账";
        com.excel2mysql.core.DmMysql.Column id = myCol("id", "bigint", false, "", "主键");
        id.autoIncrement = true;
        t.columns.add(id);
        t.columns.add(myCol("name", "varchar(50)", false, "", "名称"));
        t.columns.add(myCol("qty", "int unsigned", true, "", "数量"));
        t.columns.add(myCol("price", "decimal(12,2)", true, "0.00", "单价"));
        t.columns.add(myCol("big", "text", true, "", "说明"));
        t.columns.add(myCol("created", "timestamp", true, "CURRENT_TIMESTAMP", "创建时间"));
        t.columns.add(myCol("weird", "VagueType", true, "", "认不出的类型"));
        t.primaryKeys.add("id");
        return t;
    }

    private static com.excel2mysql.core.DmMysql.Column myCol(String name, String type,
                                                             boolean nullable, String def,
                                                             String cm) {
        com.excel2mysql.core.DmMysql.Column c = new com.excel2mysql.core.DmMysql.Column();
        c.name = name;
        c.type = type;
        c.nullable = nullable;
        c.dbDefault = def;
        c.comment = cm;
        return c;
    }

    /** MySQL → 达梦的转换口径逐条断言：IDENTITY、4 倍字符长度、COMMENT ON、默认值收窄。 */
    private static void mysql_dm_ddl_selftest(List<String> problems) {
        List<String> warns = new ArrayList<String>();
        String ddl = com.excel2mysql.core.MysqlDm.ddl(myFixture(), true, warns);
        String[] want = {
                "CREATE TABLE \"t_oil\" (",
                "  \"id\" BIGINT IDENTITY(1,1) NOT NULL",
                "  \"name\" VARCHAR(200) NOT NULL",
                "  \"qty\" INT DEFAULT NULL",
                "  \"price\" DECIMAL(12,2) DEFAULT 0.00",
                "  \"big\" CLOB DEFAULT NULL",
                "  \"created\" TIMESTAMP DEFAULT SYSDATE",
                "  PRIMARY KEY (\"id\")",
                "COMMENT ON TABLE \"t_oil\" IS '油料台账'",
                "COMMENT ON COLUMN \"t_oil\".\"name\" IS '名称'",
        };
        for (String w : want) {
            if (!ddl.contains(w)) {
                problems.add("MySQL→达梦 少了这一段：" + w + "｜实际：" + ddl);
            }
        }
        if (!ddl.contains("\"weird\" CLOB")) {
            problems.add("认出的类型没落 CLOB 或列被丢了：" + ddl);
        }
        if (!warns.toString().contains("无符号") || !warns.toString().contains("不认识")) {
            problems.add("无符号与认不出的类型没记账：" + warns);
        }
    }

    /** 一张覆盖面够宽的达梦表：主键、变长、定标数、大对象、时间戳、非法列名各来一样。 */
    private static com.excel2mysql.core.DmMysql.Table dmFixture() {
        com.excel2mysql.core.DmMysql.Table t = new com.excel2mysql.core.DmMysql.Table();
        t.schema = "SYSDBA";
        t.name = "T_OIL";
        t.comment = "油料保障台账";
        t.columns.add(dmCol("ID", "BIGINT", 8, -1, 0, false, "", "主键"));
        t.columns.add(dmCol("NAME", "VARCHAR", 50, -1, -1, false, "", "油料名称"));
        t.columns.add(dmCol("PRICE", "DECIMAL", 22, 12, 2, true, "0.00", "单价（元）"));
        t.columns.add(dmCol("NOTE", "CLOB", 0, -1, -1, true, "'x'", "备注"));
        t.columns.add(dmCol("ENABLED", "BIT", 1, -1, -1, true, "", "是否启用"));
        t.columns.add(dmCol("CREATED", "TIMESTAMP", 0, -1, 6, true, "SYSDATE", "创建时间"));
        t.columns.add(dmCol("WEIRD TYPE", "VagueType", 0, -1, -1, true, "", "认不出的列"));
        t.primaryKeys.add("ID");
        return t;
    }

    /**
     * 转换口径逐条断言（不写死整串 DDL：排版一动就整片红，看不出真坏了哪一条）。
     */
    private static void dm_ddl_selftest(List<String> problems) {
        List<String> warns = new ArrayList<String>();
        String ddl = com.excel2mysql.core.DmMysql.ddl(dmFixture(), true, warns);
        String[] want = {
                "CREATE TABLE `t_oil` (",
                "  `id` BIGINT NOT NULL COMMENT '主键'",
                "  `name` VARCHAR(50) NOT NULL COMMENT '油料名称'",
                "  `price` DECIMAL(12,2) DEFAULT 0.00 COMMENT '单价（元）'",
                "  `enabled` BOOLEAN DEFAULT NULL COMMENT '是否启用'",
                "  `created` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间'",
                "  PRIMARY KEY (`id`)",
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci"
                        + " ROW_FORMAT=DYNAMIC COMMENT='油料保障台账'",
        };
        for (String w : want) {
            if (!ddl.contains(w)) {
                problems.add("达梦→MySQL 少了这一段：" + w + "｜实际：" + ddl);
            }
        }
        if (ddl.contains("WEIRD") || ddl.contains("VagueType")) {
            problems.add("非法列名或没认出的类型被原样搬进了 DDL：" + ddl);
        }
        if (!ddl.contains("`note` LONGTEXT DEFAULT NULL COMMENT '备注'")) {
            problems.add("CLOB 没落 LONGTEXT 或它的默认值没被丢掉：" + ddl);
        }
        String joined = String.join(" / ", warns);
        if (!joined.contains("名不在允许范围") || !joined.contains("MySQL 不给这类列默认值")) {
            problems.add("两处该点名没点名：" + joined);
        }
        List<String> warns2 = new ArrayList<String>();
        String quote = com.excel2mysql.core.DmMysql.comment("a'b\nc", "T", "f", warns2);
        if (!"a''b c".equals(quote) || !warns2.isEmpty()) {
            problems.add("注释里的引号与换行没处理：" + quote);
        }
        List<String> warns3 = new ArrayList<String>();
        if (com.excel2mysql.core.DmMysql.name("表名中文", true) != null) {
            problems.add("中文标识符没被拦下");
        }
        com.excel2mysql.core.DmMysql.Output out = com.excel2mysql.core.DmMysql.script(
                java.util.Collections.singletonList(dmFixture()), "ods", true, true);
        if (!out.sql.startsWith("USE `ods`;") || !out.sql.contains("DROP TABLE IF EXISTS `t_oil`;")) {
            problems.add("USE 头或 DROP 头没写出来：" + out.sql);
        }
    }

    private static boolean hasCombo(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JComboBox) {
                return true;
            }
            if (c instanceof java.awt.Container && hasCombo((java.awt.Container) c)) {
                return true;
            }
        }
        return false;
    }

    /** 这一趟有没有真落 layer_ 开头的产物目录：库名被拦下时必须一个都没有。 */
    private static boolean hasLayerDir(java.io.File root) {
        java.io.File[] ds = root.listFiles();
        if (ds == null) {
            return false;
        }
        for (java.io.File d : ds) {
            if (d.isDirectory() && d.getName().startsWith("layer_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * RES_LAYER 忙时的第四页入口闸：生成 worker 读的是 mapping／sourceTables／字段例外这三份，
     * 「载入映射文件」与「读源库结构」改的正是同一份，所以忙时五颗入口一起灰才叫防住竞态
     * （RES_LAYER 资源位本身只挡"再点一次生成"，挡不住那两条改数据的入口）。
     *
     * <p>这一判真占资源位而不是直接调 setter：顺带钉住 MainFrame.syncRunStates 里
     * `setLayerBusy(taskBusy(RES_LAYER))` 那根接线，接线被人拆掉时这里立刻红。
     */
    private static void layer_busy_lock(com.excel2mysql.gui.LayerPage lp, MainFrame frame,
                                        List<String> problems) {
        if (!frame.startTask(MainFrame.RES_LAYER)) {
            problems.add("自检占不住 RES_LAYER 资源位，忙时禁用这一判没跑成");
            return;
        }
        if (!lp.layerBusy()) {
            problems.add("占住 RES_LAYER 后 LayerPage 没被告知忙（syncRunStates 的接线断了）");
        }
        if (lp.loadMappingButton().isEnabled()) {
            problems.add("忙时「载入映射文件」还能点，会把生成任务正读的那份映射换掉");
        }
        if (lp.readSourceButton().isEnabled()) {
            problems.add("忙时「读源库结构」还能点，会把生成任务正读的源结构换掉");
        }
        if (lp.objButton().isEnabled() || lp.domButton().isEnabled()) {
            problems.add("忙时第四页的生成按钮还能点，两个任务会踩同一份数据");
        }
        frame.endTask();
        if (lp.layerBusy()) {
            problems.add("任务收了摊 LayerPage 还停在忙态，第四页五颗入口会一直灰着");
        }
        if (!lp.loadMappingButton().isEnabled() || !lp.readSourceButton().isEnabled()) {
            problems.add("任务收了摊，「载入映射文件」／「读源库结构」却没恢复可点");
        }
    }

    /**
     * 第四、五页的「清除日志」：点它只清本页，别的页签的记录与页面上的映射、清单、
     * 已生成的 DDL 一概不动。第一、二、三页早就有这颗键，这两页原本没有——
     * 读一次源库结构就是几百行，上一轮的行留在框里，下一轮找报错只能靠滚。
     *
     * <p>验的是真按钮（doClick 走监听器同一条路），不是直接调 clearLog：
     * 自检要证明的是"界面上这颗键管用"，不是"这个方法管用"。
     */
    private static void log_clear_buttons(MainFrame frame, com.excel2mysql.gui.LayerPage lp,
                                          List<String> problems) {
        com.excel2mysql.gui.DmPage dp = frame.dmPage();
        if (dp == null) {
            problems.add("第五页 dmPage 没挂上，「清除日志」无从验起");
            return;
        }
        // 三个日志框先各有内容：空框上点清除，什么都证明不了
        frame.logPanel().log("+ [OK] IMPORT | 清除日志只清本页的标记行");
        lp.logPanel().log("+ [OK] LAYER | 待清除的标记行");
        dp.logPanel().log("+ [OK] DM | 待清除的标记行");
        if (lp.logPanel().lineCount() == 0 || dp.logPanel().lineCount() == 0) {
            problems.add("第四/五页日志写了标记行却还是空的，清除按钮验不了");
            return;
        }
        lp.clearLogButton().doClick();
        if (lp.logPanel().lineCount() != 0) {
            problems.add("第四页点了「清除日志」还剩 " + lp.logPanel().lineCount() + " 行");
        }
        if (!frame.logPanel().text().contains("清除日志只清本页的标记行")) {
            problems.add("第四页清除日志把第一页的记录一起清了");
        }
        if (dp.logPanel().lineCount() == 0) {
            problems.add("第四页清除日志把第五页的记录也清了");
        }
        dp.clearLogButton().doClick();
        if (dp.logPanel().lineCount() != 0) {
            problems.add("第五页点了「清除日志」还剩 " + dp.logPanel().lineCount() + " 行");
        }
        if (!frame.logPanel().text().contains("清除日志只清本页的标记行")) {
            problems.add("第五页清除日志把第一页的记录一起清了");
        }
        System.out.println("- [INFO] GUI-SELFTEST | 第四、五页「清除日志」各清本页，"
                + "别的页签记录与页面内容都没动");
    }

    /**
     * 界面上那一句句"只是说字"的静态文字：两栈都必须够得着（可拖选、可 Ctrl+C），
     * 又都不许多占一个 Tab 位。这份清单就是"哪些句子抄得走"的字面口径。
     */
    private static final String[] STATIC_TEXTS = {
            "中英表头建模，回写库", "解析建表语句，回写库", "执行脚本与临时语句",
            "读 ods 建模 obj/dom/ads", "达梦 DM8 与 MySQL 互转建表语句（含表与字段注释）",
            "主机：", "端口：", "用户名：", "密码：", "目标库：",
            "命名规则：", "命名规则：", "表名前缀：", "表名前缀：",
            "SQL 文本", "执行结果", "还没有执行结果"};

    /** 静态文字这一族的三条承诺：选得出整行、Ctrl+C 真绑在复制上、Tab 环不在它这里停。 */
    private static void static_texts(MainFrame frame, List<String> problems) {
        java.util.List<com.excel2mysql.gui.StaticText> found =
                new java.util.ArrayList<com.excel2mysql.gui.StaticText>();
        collectStatic(frame.getContentPane(), found);
        // 目标库那行的就地提示正文随连接状态变（没加载 / 已过期 / 空），句子数不进这张清单，
        // 按件排除——Python 侧 ST1 排除的是同一件（lbl_db_hint）
        found.remove(frame.dbHintField());
        java.util.Map<String, Integer> want = new java.util.TreeMap<String, Integer>();
        for (String t : STATIC_TEXTS) {
            Integer n = want.get(t);
            want.put(t, n == null ? 1 : n + 1);
        }
        java.util.Map<String, Integer> got = new java.util.TreeMap<String, Integer>();
        for (com.excel2mysql.gui.StaticText st : found) {
            String t = st.getText();
            if (t == null || t.isEmpty()) {
                continue;       // 空着的目标库提示此刻没有可抄的话
            }
            Integer n = got.get(t);
            got.put(t, n == null ? 1 : n + 1);
        }
        if (!want.equals(got)) {
            problems.add("静态文字这一族的覆盖面变了：应为 " + want + "，现为 " + got);
        }
        if (!com.excel2mysql.gui.StaticText.skips(frame.getFocusTraversalPolicy())) {
            problems.add("主窗口没装上绕开静态文字的焦点环策略，Tab 会多停几站");
        }
        for (com.excel2mysql.gui.StaticText st : found) {
            String who = "静态文字「" + brief(st.getText()) + "」";
            dragSelectable(st, who, problems);
            if (!st.isFocusable() || !st.isRequestFocusEnabled()) {
                problems.add(who + "点进去拿不到焦点，Ctrl+C 那条走不通");
            }
            Object name = st.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(
                    javax.swing.KeyStroke.getKeyStroke("control C"));
            javax.swing.Action copy = name == null ? null : st.getActionMap().get(name);
            if (!(copy instanceof javax.swing.text.DefaultEditorKit.CopyAction)) {
                problems.add(who + "的 Ctrl+C 没绑在复制上：" + name + " -> " + copy);
            }
        }
        // 焦点环的数学单独判：真窗口的排序策略要排版过才作数，自检这条线从不 show 窗口，
        // 所以这里用一个只放着三件的小容器把"下一站跳过静态文字"钉死
        javax.swing.JPanel ring = new javax.swing.JPanel();
        // 普通 JPanel 的 getFocusTraversalPolicy() 会返回 null：要读回自己设的那一条，
        // 得先声明自己是策略提供方（JFrame 天生是焦点环根，不需要这一步）
        ring.setFocusTraversalPolicyProvider(true);
        javax.swing.JButton head = new javax.swing.JButton("head");
        com.excel2mysql.gui.StaticText mid = new com.excel2mysql.gui.StaticText("中间这句");
        javax.swing.JButton tail = new javax.swing.JButton("tail");
        ring.add(head);
        ring.add(mid);
        ring.add(tail);
        ring.setFocusTraversalPolicy(com.excel2mysql.gui.StaticText.policySkipping(new OrderPolicy()));
        java.awt.FocusTraversalPolicy pol = ring.getFocusTraversalPolicy();
        if (pol.getComponentAfter(ring, head) != tail
                || pol.getComponentAfter(ring, tail) != head
                || pol.getComponentBefore(ring, head) != tail) {
            problems.add("焦点环没绕开静态文字：after(head)="
                    + brief(String.valueOf(pol.getComponentAfter(ring, head)))
                    + " after(tail)=" + brief(String.valueOf(pol.getComponentAfter(ring, tail)))
                    + " before(head)="
                    + brief(String.valueOf(pol.getComponentBefore(ring, head))));
        }
        // 真按 Ctrl+C 抄进系统剪贴板那一步要焦点落在件上（JDK 的 CopyAction 取焦点组件），
        // 自检从不 show 窗口，这一格由界面探针实跑（拖选 + 真按键 + 读回剪贴板）
        System.out.println("! [WARN] GUI-SELFTEST | 静态文字「拖选后 Ctrl+C 抄进剪贴板」"
                + "在自检里未覆盖（不 show 窗口拿不到焦点），由 gui 探针实跑那一趟判");
    }

    /** 这棵树上的静态文字（HintLabel 是带状态色的那种，不算这一族的清单成员）。 */
    private static void collectStatic(java.awt.Component c,
                                      java.util.List<com.excel2mysql.gui.StaticText> out) {
        if (c instanceof com.excel2mysql.gui.StaticText
                && !(c instanceof com.excel2mysql.gui.HintLabel)) {
            out.add((com.excel2mysql.gui.StaticText) c);
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component kid : ((java.awt.Container) c).getComponents()) {
                collectStatic(kid, out);
            }
        }
    }

    /**
     * 底部动作条那两颗生成按钮的悬浮提示必须只有一行。
     *
     * <p>这一排贴在窗口最下沿：提示写成几行就会被摆到按钮那一片上面，把右边的同类按钮整个
     * 盖住，看不见就等于点不上（用户 2026-11-11 报的第 5 条，全屏时最明显）。
     * 每张表最多写多少行、没有 INSERT 时剩什么，这些细节归「使用说明」。
     */
    private static void gen_tips_stay_short(MainFrame frame, List<String> problems) {
        int seen = 0;
        for (javax.swing.JButton b : frame.buttons()) {
            String t = b.getText();
            if (!"生成标准excel文件".equals(t) && !"生成简易excel文件".equals(t)) {
                continue;
            }
            seen++;
            String tip = b.getToolTipText() == null ? "" : b.getToolTipText();
            if (tip.contains("\n") || tip.contains("<br>") || tip.startsWith("<html>")) {
                problems.add("「" + t + "」的悬浮提示又是多行的，会盖住同一排右边的按钮："
                        + tip.replace("\n", "\\n"));
            }
            // 上限按最窄的目标机算：120 显示列约合 840 物理像素，1024 宽也放得下，
            // 放不下就会被拆成两行，又回到压住按钮那一档
            if (com.excel2mysql.gui.PopupText.width(tip) > 120) {
                problems.add("「" + t + "」的悬浮提示长到 "
                        + com.excel2mysql.gui.PopupText.width(tip) + " 列，一行放不下就会压住按钮");
            }
        }
        if (seen < 4) {
            problems.add("只扫到 " + seen + " 颗生成按钮，这条判据没覆盖两页");
        }
    }

    /** 这棵子树上的全部件。 */
    private static void collectAll(java.awt.Component c, java.util.List<java.awt.Component> out) {
        out.add(c);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component kid : ((java.awt.Container) c).getComponents()) {
                collectAll(kid, out);
            }
        }
    }

    /**
     * 两页「表名前缀」那一行：输入框与两颗按钮必须一样高。
     *
     * <p>边界相等还不够——Windows 主题把按钮的边框画在控件边界内 1px，输入框的边框正正画在
     * 边界上，边界钉平了肉眼仍差 2px（用户 2026-11-11 报的第 4 条）。所以这里判两件事：
     * 整行的首选高度没被包装面板的竖间隙顶高，以及输入框的可见边框确实内缩了那一像素。
     */
    private static void prefix_row_heights(MainFrame frame, List<String> problems) {
        java.util.List<java.awt.Component> all = new java.util.ArrayList<java.awt.Component>();
        collectAll(frame, all);
        int rows = 0;
        for (java.awt.Component c : all) {
            if (!(c instanceof javax.swing.JButton)
                    || !"批量应用前缀".equals(((javax.swing.JButton) c).getText())) {
                continue;
            }
            java.awt.Component row = c.getParent();
            while (row != null && !(row instanceof java.awt.Container
                    && ((java.awt.Container) row).getLayout() instanceof java.awt.BorderLayout)) {
                row = row.getParent();
            }
            if (row == null) {
                continue;
            }
            rows++;
            java.util.List<java.awt.Component> kids = new java.util.ArrayList<java.awt.Component>();
            collectAll(row, kids);
            int rowH = row.getPreferredSize().height;
            for (java.awt.Component k : kids) {
                if (k instanceof javax.swing.JTextField
                        && !(k instanceof com.excel2mysql.gui.StaticText)) {
                    javax.swing.JTextField field = (javax.swing.JTextField) k;
                    if (field.getPreferredSize().height != rowH) {
                        problems.add("第 " + rows + " 处前缀行：输入框首选高 "
                                + field.getPreferredSize().height + " 与整行 " + rowH + " 不等");
                    }
                    if (com.excel2mysql.gui.Buttons.fieldFrameInset(field) != 1) {
                        problems.add("第 " + rows + " 处前缀行：输入框的可见边框没内缩那 1px，"
                                + "与主题按钮仍差 2px");
                    }
                }
                if (k instanceof javax.swing.JButton
                        && ((javax.swing.JButton) k).getText().contains("前缀")) {
                    if (((javax.swing.JButton) k).getPreferredSize().height != rowH) {
                        problems.add("第 " + rows + " 处前缀行：按钮「"
                                + ((javax.swing.JButton) k).getText() + "」首选高 "
                                + ((javax.swing.JButton) k).getPreferredSize().height
                                + " 与整行 " + rowH + " 不等");
                    }
                }
            }
        }
        if (rows < 2) {
            problems.add("只找到 " + rows + " 处「表名前缀」行，这条判据没覆盖到两页");
        }
    }

    /** 这棵子树上的每一张表格：横扫"焦点一离开就提交"有没有漏掉哪一张。 */
    private static void collectTables(java.awt.Component c,
                                      java.util.List<javax.swing.JTable> out) {
        if (c instanceof javax.swing.JTable) {
            out.add((javax.swing.JTable) c);
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component kid : ((java.awt.Container) c).getComponents()) {
                collectTables(kid, out);
            }
        }
    }

    /**
     * 只按加入顺序排的极小焦点策略，给上面那条焦点环判据当底座。
     *
     * <p>不借 JDK 那两套（ContainerOrder / Layout）：它们要容器排版过或在活窗口里，
     * 自检这条线从不 show 窗口，拿它们当底座读出的是 null——那样判的是 JDK 的实现状态，
     * 不是这一层包装"遇到静态文字就往下走"的逻辑。真窗口 + 真 Tab 那一趟由界面探针实跑，
     * 结果记在 java/GUI-对齐清单.md。
     */
    private static final class OrderPolicy extends java.awt.FocusTraversalPolicy {

        private java.util.List<java.awt.Component> cycle(java.awt.Container c) {
            java.util.List<java.awt.Component> out = new java.util.ArrayList<java.awt.Component>();
            for (java.awt.Component k : c.getComponents()) {
                if (k.isFocusable() && k.isEnabled()) {
                    out.add(k);
                }
            }
            return out;
        }

        private java.awt.Component step(java.awt.Container c, java.awt.Component a, int dir) {
            java.util.List<java.awt.Component> l = cycle(c);
            if (l.isEmpty()) {
                return null;
            }
            int i = l.indexOf(a);
            if (i < 0) {
                return l.get(0);
            }
            return l.get(((i + dir) % l.size() + l.size()) % l.size());
        }

        @Override
        public java.awt.Component getComponentAfter(java.awt.Container c, java.awt.Component a) {
            return step(c, a, 1);
        }

        @Override
        public java.awt.Component getComponentBefore(java.awt.Container c, java.awt.Component a) {
            return step(c, a, -1);
        }

        @Override
        public java.awt.Component getFirstComponent(java.awt.Container c) {
            java.util.List<java.awt.Component> l = cycle(c);
            return l.isEmpty() ? null : l.get(0);
        }

        @Override
        public java.awt.Component getLastComponent(java.awt.Container c) {
            java.util.List<java.awt.Component> l = cycle(c);
            return l.isEmpty() ? null : l.get(l.size() - 1);
        }

        @Override
        public java.awt.Component getDefaultComponent(java.awt.Container c) {
            return getFirstComponent(c);
        }
    }

    private static void import_finish(MainFrame frame, List<String> files, List<String> problems) {
        if (files.size() < 2) {
            problems.add("导入收尾自检需要两个夹具文件");
            return;
        }
        List<String> two = new ArrayList<String>();
        two.add(files.get(0));
        two.add(files.get(1));
        frame.addFiles(two);
        if (frame.tables().size() != 2) {
            problems.add("收尾自检前应有 2 张表，实际 " + frame.tables().size());
            return;
        }
        String f0 = frame.tables().get(0).filepath;
        String f1 = frame.tables().get(1).filepath;
        String badName = frame.tables().get(1).tableName;
        List<MainFrame.ImportOutcome> outs = new ArrayList<MainFrame.ImportOutcome>();
        outs.add(new MainFrame.ImportOutcome(f0, true, frame.tables().get(0).tableName, "导入 4 行"));
        outs.add(new MainFrame.ImportOutcome(f1, false, badName,
                "建表失败：SQLException（MySQL 错误码 1064）：语法错"));
        String summary = frame.finishImport(outs, false);
        if (!summary.contains("成功 1 张表，失败 1 张表")) {
            problems.add("收尾汇总文案不对：" + summary.replace((char) 10, ' '));
        }
        // 失败原因要按表名逐条落到弹窗里：只写"失败 N 张"，现场还得自己去日志翻是哪张表
        if (!summary.contains("失败的 1 张表（表名与原因）：")
                || !summary.contains("`" + badName + "`")
                || !summary.contains("建表失败：SQLException（MySQL 错误码 1064）：语法错")) {
            problems.add("失败清单没按表名给出具体报错：" + summary.replace((char) 10, ' '));
        }
        frame.informImportDone(summary, true);
        if (!"warning".equals(frame.lastDialogIcon())) {
            problems.add("有失败时导入弹窗该用警告级图标，实际 " + frame.lastDialogIcon());
        }
        if (!frame.logPanel().contains("===== 全部完成：成功 1 张表，失败 1 张表 =====")) {
            problems.add("收尾没有写汇总日志行");
        }
        if (!frame.logPanel().isErrorLine("===== 全部完成")) {
            problems.add("有失败时汇总行没标成错误级");
        }
        if (!frame.logPanel().contains("[保留] 导入完成的文件仍留在待导入列表")) {
            problems.add("导入完成的文件留在清单这件事没有留痕");
        }
        if (!frame.logPanel().contains("[保留] 导入失败的文件同样留在列表里")) {
            problems.add("失败文件保留没有说明原因");
        }
        if (frame.tables().size() != 2 || frame.fileModel().getRowCount() != 2) {
            problems.add("收尾不该动清单：应有 2 张表、2 行，实际 "
                    + frame.tables().size() + " 张、" + frame.fileModel().getRowCount() + " 行");
        }
        if (!summary.contains("导入完成的 1 张表仍留在待导入列表里")) {
            problems.add("弹窗没说清完成的文件还在清单里：" + summary.replace((char) 10, ' '));
        }

        frame.clearAll();
        frame.addFiles(two);
        String a = frame.tables().get(0).filepath;
        String b = frame.tables().get(1).filepath;
        List<MainFrame.ImportOutcome> allOk = new ArrayList<MainFrame.ImportOutcome>();
        allOk.add(new MainFrame.ImportOutcome(a, true));
        allOk.add(new MainFrame.ImportOutcome(b, true));
        frame.finishImport(allOk, false);
        if (frame.tables().size() != 2 || frame.fileModel().getRowCount() != 2) {
            problems.add("全部成功后清单仍应原样留着，实际 "
                    + frame.tables().size() + " 张、" + frame.fileModel().getRowCount() + " 行");
        }
        if (!frame.importEnabled()) {
            problems.add("清单还有表，导入按钮不该变灰");
        }
        // 日志是累加的，所以这里按第二场自己的汇总行判：零失败不该被标成错误级
        if (!frame.logPanel().contains("===== 全部完成：成功 2 张表，失败 0 张表 =====")) {
            problems.add("全成功场景没有写汇总行");
        }
        if (frame.logPanel().isErrorLine("成功 2 张表，失败 0 张表")) {
            problems.add("零失败的汇总行被标成了错误级");
        }
        // 夹具只给路径与"失败"（老调用那样）：表名与原因缺失也要写成能读的一行，
        // 不能渲染成 "``（）："这种空白，让人以为程序坏了
        List<MainFrame.ImportOutcome> bare = new ArrayList<MainFrame.ImportOutcome>();
        bare.add(new MainFrame.ImportOutcome("C:/tmp/bare.xlsx", false));
        String bareMsg = frame.finishImport(bare, false);
        if (!bareMsg.contains("`（未命名表）`（bare.xlsx）：（没有拿到报错正文，见日志）")) {
            problems.add("失败清单缺表名/原因时没兜底：" + bareMsg.replace((char) 10, ' '));
        }
        frame.clearAll();
    }

    /**
     * 「开始导入」的两道闸：列表空着不管（按钮本来就是灰的），没选库要弹 NO_DB_HINT。
     * 两条都断"任务位还得回来"：早先在抢到位之后 return 却没 endTask，
     * 一次拦下就把之后每个按钮都变成"已有任务在跑"。
     */
    private static void import_guard(MainFrame frame, List<String> files, List<String> problems) {
        frame.clearAll();
        frame.setSelectedDatabase("");
        frame.startImport();
        if (frame.taskBusy()) {
            problems.add("列表空着点导入却抢了任务位");
        }
        frame.addFiles(files);
        frame.startImport();
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("未选择目标库")) {
            problems.add("没选库点导入要弹 NO_DB_HINT，实际标题=" + frame.lastDialogTitle()
                    + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
        }
        if (!frame.logPanel().contains("已拦下：未选择目标库")) {
            problems.add("拦下导入只弹窗没写日志，回头查不到");
        }
        if (frame.taskBusy()) {
            problems.add("拦下之后任务位没还回来，之后每个按钮都会被吃掉");
            // 抢着不放正是要抓的缺陷，但别让它把后面所有用例一起带崩：
            // 失败报告要只说这一条，而不是后面一屏连锁异常
            frame.endTask();
        }
        frame.clearAll();
    }

    /** 把界面上所有静态文字收出来：状态栏这类自造文案一长回来就要被抓到。 */
    private static void collectLabels(java.awt.Container c, List<String> out) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof javax.swing.JLabel) {
                String t = ((javax.swing.JLabel) child).getText();
                if (t != null && !t.isEmpty()) {
                    out.add(t);
                }
            }
            if (child instanceof java.awt.Container) {
                collectLabels((java.awt.Container) child, out);
            }
        }
    }

    private static void writeText(String path, String text) {        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(path),
                    text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("自检写夹具失败：" + path, e);
        }
    }

    /** 第三个页签「SQL 执行器」：常驻可见量、示例清单的一来一回与空输入那道闸。 */
    private static void sql_run_tab(MainFrame frame, List<String> problems) {
        // 末段（SQL 文本 + 执行结果）初始化压到最小：空清单那张表的图标与引导语要整块露出来。
        // 切到这一页排过一次版才量得准（隐藏页签的子控件还没量过）。
        frame.selectTab(2);
        frame.validate();
        frame.applyRunSplitProportions();
        frame.validate();
        if (!"1".equals(frame.sqlRunEditorAtMin())) {
            problems.add("第三页末段默认没有压到最小高度：高 "
                    + frame.sqlRunEditorHeight() + " / 最小 " + frame.sqlRunEditorMinHeight());
        }
        if (frame.sqlRunInputMinHeight() != frame.sqlRunLogPanel().getMinimumSize().height
                || frame.sqlRunInputMinHeight() <= 0) {
            problems.add("第三页编辑区的最小高度不是日志同档的 4 行：编辑区 "
                    + frame.sqlRunInputMinHeight() + " / 结果区 "
                    + frame.sqlRunLogPanel().getMinimumSize().height);
        }
        int hintNeed = wantPx(44) + wantPx(10) + wantPx(8)
                + frame.sqlRunFilesTable().getFontMetrics(
                        frame.sqlRunFilesTable().getFont()).getHeight();
        if (frame.sqlRunTableViewportHeight() < hintNeed) {
            problems.add("第三页空清单的视口高 " + frame.sqlRunTableViewportHeight()
                    + "，兜不住图标与引导语需要的 " + hintNeed
                    + "（SQL 文本段没压到最小？）");
        }
        frame.selectTab(0);
        frame.validate();
        List<String> titles = frame.tabTitles();
        if (titles.size() != 5 || !"SQL 执行器".equals(titles.get(2))) {
            problems.add("第三个页签不对：" + titles);
        }
        // 第四页签的名字也钉住：layerPage 挂没挂由上面那块管，这条管的是页签标题文案本身。
        if (titles.size() > 3 && !"分层建模".equals(titles.get(3))) {
            problems.add("第四个页签不对：" + titles);
        }
        if (!"执行脚本与临时语句".equals(frame.sqlRunHintText())) {
            problems.add("「SQL 执行器」的提示文案不对：" + frame.sqlRunHintText());
        }
        if (!"wrap".equals(frame.sqlRunWrap())) {
            problems.add("SQL 编辑区应按词折行（换成富文本件才能按角色上色，"
                    + "不折行那条契约已由用户作废），实际 " + frame.sqlRunWrap());
        }
        // 编辑区的关键字高亮：读的是真文档上的角色分布，不是 core 那张表抄一遍。
        // 同一句夹具也钉住"上色不许改正文"——\n 是 JTextPane 的段界，getText 会展开成 \r\n，
        // 所以比的是归一之后的那份（paneText 走的同一条读法）。
        frame.setSqlRunInputText("SELECT `学生编号`, 姓名 FROM t_student WHERE id>1; -- 备注\n"
                + "DROP TABLE t_x;");
        String runSig = com.excel2mysql.gui.RichText.roleSignature(
                (javax.swing.JTextPane) frame.sqlRunInputComponent());
        if (runSig.indexOf('W') < 0 || runSig.indexOf('L') < 0
                || runSig.indexOf('D') < 0 || runSig.indexOf('T') < 0) {
            problems.add("编辑区没按角色挂上颜色（关键字/反引号名/警示/注释缺档）：" + runSig);
        }
        String runFixture = "SELECT `学生编号`, 姓名 FROM t_student WHERE id>1; -- 备注\n"
                + "DROP TABLE t_x;";
        if (!runFixture.equals(frame.sqlRunInputText())) {
            problems.add("编辑区上色把正文改了：" + frame.sqlRunInputText());
        }
        // 夹具留在编辑区会让后面那些"开场就是那句连通检查"的判据读到上一场景的文本
        frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
        // 整篇重挂的代价钉住：编辑区每改一次正文就重挂一遍，长脚本不能把界面拖住。
        // 阈值按"粘一大段进来界面还跟手"定：600 条夹具实测远小于这一档（差一个数量级）
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            big.append("INSERT INTO t_").append(i)
                    .append(" (id, name) VALUES (").append(i).append(", 'x');\n");
        }
        long restyle0 = System.currentTimeMillis();
        frame.setSqlRunInputText(big.toString());
        long restyleMs = System.currentTimeMillis() - restyle0;
        frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
        if (restyleMs > 400) {
            problems.add("600 条语句整篇上色要 " + restyleMs
                    + " 毫秒（编辑区粘大脚本会卡住界面）");
        }
        System.out.println("- [INFO] GUI-SELFTEST | 编辑区整篇上色 600 条耗时 "
                + restyleMs + " 毫秒");
        // 编辑区与结果区都是第三页给人读的主区域：等宽，字号与第一页日志同档
        java.awt.Font resultFont = frame.sqlRunLogPanel().getFont();
        java.awt.Font inputFont = frame.sqlRunInputFont();
        int wantLog = frame.logPanel().getFont().getSize();
        if (wantLog != wantFontPx(LogPanel.DEFAULT_FONT_SIZE)) {
            problems.add("第一页日志字号不是日志档折算的 " + wantFontPx(LogPanel.DEFAULT_FONT_SIZE)
                    + "px：" + wantLog);
        }
        if (resultFont == null || !"Monospaced".equals(resultFont.getFamily())) {
            problems.add("结果区不是等宽字体："
                    + (resultFont == null ? "无字体" : resultFont.getFamily()));
        }
        if (resultFont == null || resultFont.getSize() != wantLog) {
            problems.add("结果区字号与第一页日志不同档（期望 " + wantLog + "px）："
                    + (resultFont == null ? "无字体" : resultFont.getSize()));
        }
        if (inputFont == null || !"Monospaced".equals(inputFont.getFamily())) {
            problems.add("编辑区不是等宽字体："
                    + (inputFont == null ? "无字体" : inputFont.getFamily()));
        }
        if (inputFont == null || inputFont.getSize() != wantLog) {
            problems.add("编辑区字号与第一页日志不同档（期望 " + wantLog + "px）："
                    + (inputFont == null ? "无字体" : inputFont.getSize()));
        }
        if (resultFont != null && inputFont != null
                && (!resultFont.getFamily().equals(inputFont.getFamily())
                || resultFont.getSize() != inputFont.getSize())) {
            problems.add("编辑区与结果区字体不一致：编辑区 " + inputFont.getFamily() + "/"
                    + inputFont.getSize() + "，结果区 " + resultFont.getFamily() + "/"
                    + resultFont.getSize());
        }
        if (!"SELECT 1 AS 连通检查;".equals(frame.sqlRunInputText())) {
            problems.add("编辑区开场不是那句连通检查：" + frame.sqlRunInputText());
        }
        javax.swing.JButton clearInputBtn = frame.sqlRunClearInputButton(),
                clearLogBtn = frame.sqlRunClearLogButton(),
                execBtn = frame.sqlRunButton(), helpBtn = frame.sqlRunHelpButton();
        if (clearInputBtn == null || clearLogBtn == null || execBtn == null || helpBtn == null
                || !"示例 (?)".equals(helpBtn.getText())
                || !"执行编辑区 SQL".equals(execBtn.getText())
                || !"清空编辑区".equals(clearInputBtn.getText())
                || !"清空结果区".equals(clearLogBtn.getText())) {
            problems.add("「SQL 执行器」的编辑区那颗执行按钮或清空按钮文案不对");
        }
        if (execBtn != null && !execBtn.getToolTipText().contains("中途出错整批回滚")) {
            problems.add("「执行」按钮没把事务语义写在 tooltip 上：" + execBtn.getToolTipText());
        }
        // 「格式化 SQL」那一颗：文案、tooltip 三件事、真按键、拒绝与"没得排"三种结局
        javax.swing.JButton formatBtn = frame.sqlRunFormatButton();
        if (formatBtn == null || !"格式化 SQL".equals(formatBtn.getText())
                || !formatBtn.getToolTipText().contains("关键字与类型大写")
                || !formatBtn.getToolTipText().contains("Ctrl+Alt+L")
                || !formatBtn.getToolTipText().contains("整库 dump")) {
            problems.add("少了那颗「格式化 SQL」或它的 tooltip 没写清："
                    + (formatBtn == null ? "没有这颗按钮" : formatBtn.getToolTipText()));
        }
        if (!"e2mFormatSql".equals(frame.sqlRunFormatKeyBinding())) {
            problems.add("Ctrl+Alt+L 没挂在编辑区自己的 WHEN_FOCUSED 输入表上："
                    + frame.sqlRunFormatKeyBinding());
        }
        // 按键派发要窗口真被系统激活过，自检环境里拿不到那个焦点（实测事件被
        // KeyboardFocusManager 发到别处，绑定查不到）。所以这一条钉"这串键位映射到一个
        // 还活着的动作"，并把那个 Action 本身跑一遍——跑的是产品挂进去的那一个，不是抄的
        // 副本。真按键有没有反应记进清单的"无自动化覆盖"，交付前手工按一次。
        frame.setSqlRunInputText("select id,name from t_user where age>18 and status=1;");
        javax.swing.Action fmtAction = frame.sqlRunInputAction("e2mFormatSql");
        if (fmtAction == null) {
            problems.add("Ctrl+Alt+L 映射到的动作取不到（绑定是空的）");
        } else {
            fmtAction.actionPerformed(null);
            if (!"SELECT\n  id,\n  name\nFROM t_user\nWHERE age > 18\n  AND status = 1;"
                    .equals(frame.sqlRunInputText())) {
                problems.add("按键映射的那个动作没把这段排开：" + frame.sqlRunInputText());
            }
        }
        if (!"free".equals(frame.sqlRunAltLBinding())) {
            problems.add("只按 Alt+L 也被占用了：" + frame.sqlRunAltLBinding());
        }
        frame.setSqlRunInputText("DELIMITER ;;\nCREATE FUNCTION f() RETURNS INT BEGIN"
                + " RETURN 1; END;;\nDELIMITER ;");
        clickNow(formatBtn);
        if (!frame.lastDialogText().contains("整库 dump")
                || !frame.sqlRunInputText().startsWith("DELIMITER")) {
            problems.add("整库 dump 那段没被拒绝，或拒绝后正文被改了："
                    + frame.lastDialogText());
        }
        frame.setSqlRunInputText("SELECT 1;");
        clickNow(formatBtn);
        if (!frame.sqlRunResultText().contains("一个字没动")) {
            problems.add("已经是排好的形状时没说那句「一个字没动」："
                    + frame.sqlRunResultText());
        }
        clickNow(formatBtn);
        int _same = frame.sqlRunResultText().split("一个字没动", -1).length;
        if (_same < 3) {
            problems.add("再点一次也不该静默（日志里那句「一个字没动」应随点击累加）："
                    + (_same - 1) + " 次");
        }
        frame.setSqlRunInputText("select id,name from t_user where age>18 and status=1;");
        clickNow(formatBtn);
        if (!"SELECT\n  id,\n  name\nFROM t_user\nWHERE age > 18\n  AND status = 1;"
                .equals(frame.sqlRunInputText())) {
            problems.add("点那颗按钮没把这段排开：" + frame.sqlRunInputText());
        }

        // 空输入必须当场拦下：既不连库也不起任务，日志与弹窗同一句
        frame.setSqlRunInputText("");
        frame.runSqlFromUi();
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("编辑区是空的")) {
            problems.add("空编辑区没拦下：标题=" + frame.lastDialogTitle()
                    + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
        }

        // 示例目录：条数与分组、短标题、右侧介绍与语句、关键词过滤
        if (frame.sqlSamplesCount() != 157) {
            problems.add("SQL 示例不是 157 条：" + frame.sqlSamplesCount());
        }
        int titleTooLong = 0;
        for (int i = 0; i < frame.sqlSamplesCount(); i++) {
            SqlSamples.Sample s = SqlSamples.ALL[i];
            String sql = frame.sqlSampleSql(i);
            if (sql == null || sql.trim().isEmpty()
                    || com.excel2mysql.core.SqlScript.splitStatements(sql).isEmpty()) {
                problems.add("第 " + (i + 1) + " 条示例切不出语句：" + sql);
            }
            if (s.title == null || s.title.isEmpty() || s.scenario == null
                    || s.scenario.isEmpty() || s.problem == null || s.problem.isEmpty()
                    || s.keywords == null || s.keywords.isEmpty()) {
                problems.add("第 " + (i + 1) + " 条示例的短标题/场景/问题/关键词有空着");
            }
            if (s.title.length() > 12) {
                titleTooLong++;
            }
            if (s.title.equals(s.desc)) {
                // 目录叶子与右侧介绍同一句话时，介绍那一段等于没写
                problems.add("第 " + (i + 1) + " 条示例的短标题与说明一字不差，"
                        + "右侧那段介绍没有多出任何信息");
            }
        }
        if (titleTooLong > 0) {
            problems.add("有 " + titleTooLong + " 条短标题超过 12 字，目录那一列会被撑歪");
        }
        frame.showSqlSamples();
        if (!frame.sqlSamplesShowing()) {
            problems.add("「示例 (?)」点了没开出对话框");
        }
        java.awt.Font previewFont = frame.sqlRunPreviewFont();
        int wantPreviewPx = wantFontPx(LogPanel.DEFAULT_FONT_SIZE);
        if (previewFont == null || !"Monospaced".equals(previewFont.getFamily())
                || previewFont.getSize() != wantPreviewPx) {
            problems.add("示例全文不是日志档 10pt 折算的等宽字体："
                    + (previewFont == null ? "无字体"
                    : previewFont.getFamily() + "/" + previewFont.getSize()
                    + "，期望 " + wantPreviewPx + "px"));
        }
        if (!"SQL 示例（157 条）".equals(frame.sqlSamplesTitle())) {
            problems.add("示例对话框标题不带条数：" + frame.sqlSamplesTitle());
        }
        // 目录那一栏的观感（用户 2026-10-29 报"字号与间距太小、颜色单一"）：
        // 字号并入表格那一档，分组行按章节标题画——读的是真控件与真渲染路径
        if (frame.sampleTreeFontPt() != com.excel2mysql.gui.Tables.SAMPLE_TREE_POINT_SIZE) {
            problems.add("示例目录字号不是 "
                    + com.excel2mysql.gui.Tables.SAMPLE_TREE_POINT_SIZE
                    + " 号，实际 " + frame.sampleTreeFontPt() + " 号");
        }
        String[] grpStyle = frame.sampleTreeCellStyle(true);
        String[] leafStyle = frame.sampleTreeCellStyle(false);
        if (!"1".equals(grpStyle[0])) {
            problems.add("示例目录的分组行没按章节标题加粗");
        }
        if (!"#1f5fd0".equals(grpStyle[1]) || !"#f4f8ff".equals(grpStyle[2])) {
            problems.add("示例目录分组行的配色不对：字色=" + grpStyle[1]
                    + " 底色=" + grpStyle[2] + "，应为 #1f5fd0 / #f4f8ff");
        }
        if (!"0".equals(leafStyle[0])) {
            problems.add("示例目录的条目行不该加粗");
        }
        // 搜索那一栏（用户 2026-10-30 报"不点过去都不知道这是搜索框"）：
        // 提示句要放得下、放大镜与空态提示都按像素判画出来没有。
        // 前面几条过滤用例往框里打过字，先清空再量，否则数到的是用户输入的字
        frame.typeSqlSamplesFilter("");
        if (!com.excel2mysql.gui.Tables.SAMPLE_SEARCH_HINT.equals(frame.sampleSearchHint())) {
            problems.add("示例搜索框的提示句不是那一句：" + frame.sampleSearchHint());
        }
        int[] hintFit = frame.sampleSearchHintFit();
        if (hintFit[0] < 0 || hintFit[0] > hintFit[1]) {
            problems.add("示例搜索框的提示句放不下：需 " + hintFit[0]
                    + "px，可用 " + hintFit[1] + "px（放不下的提示会被当成杂字）");
        }
        if (frame.sampleSearchInk(0) <= 0) {
            problems.add("示例搜索框左侧那颗放大镜没画出来（那块里没有分组蓝那一族的像素）");
        }
        if (frame.sampleSearchInk(2) <= 0) {
            problems.add("示例搜索框空态没画灰字提示（清空后文字那一块一个墨点都没有）");
        }
        // 这张清单要是 JDialog，最小化那颗按钮根本画不出来（AWT 的 Dialog 连
        // setExtendedState 都没有），所以窗口形态按"真按一次再量结果"查，判据对齐 Python 侧 F38h
        if (!frame.sqlSamplesResizable() || frame.sqlSamplesHasOwner()) {
            problems.add("示例窗口不是独立顶层窗口：可改大小=" + frame.sqlSamplesResizable()
                    + " 认主窗口当 owner=" + frame.sqlSamplesHasOwner());
        }
        int samplesMaxW = frame.sqlSamplesMaximizeThenRestore();
        int workW = com.excel2mysql.gui.Windows.workArea(frame).width;
        if (samplesMaxW < workW) {
            problems.add("示例窗口最大化按不动：最大化那一刻宽 " + samplesMaxW
                    + "，屏幕可用区宽 " + workW);
        }
        if (!frame.sqlSamplesMinimizeThenRestore()) {
            problems.add("示例窗口最小化按不动：切了 ICONIFIED 回读扩展状态没认账");
        }
        if (frame.sqlSamplesGroupCount() != 13 || frame.sqlSamplesVisibleCount() != 157
                || !"共 157 条，分 13 组".equals(frame.sqlSamplesHitsText())) {
            problems.add("示例目录不是「13 组 → 157 条」两层："
                    + frame.sqlSamplesGroupCount() + " 组 / "
                    + frame.sqlSamplesVisibleCount() + " 条 / " + frame.sqlSamplesHitsText());
        }
        if (!SqlSamples.ALL[0].title.equals(frame.sqlSamplesItemText(0))
                || !frame.sqlSamplesPreviewText().equals(frame.sqlSampleSql(0))
                || frame.sqlSamplesIntroText().indexOf("场景："
                        + SqlSamples.ALL[0].scenario) < 0) {
            problems.add("示例目录打开后没选中第一条，或右侧介绍没跟着出来："
                    + frame.sqlSamplesItemText(0));
        }
        if (frame.sqlSamplesIntroText().indexOf("解决的问题："
                + SqlSamples.ALL[0].problem) < 0) {
            problems.add("右侧介绍没写「解决的问题」那一句");
        }
        frame.setSqlSamplesRow(7);
        if (!frame.sqlSamplesPreviewText().equals(frame.sqlSampleSql(7))
                || frame.sqlSamplesIntroText().indexOf(SqlSamples.ALL[7].scenario) < 0) {
            problems.add("切换选中的条目，右侧语句或介绍没跟着换");
        }
        // 介绍块按角色上色（用户 2026-10-30 报"样式太单一"）：内容一字不许改，
        // 上色读真文档的角色串 + 数像素，不读"有没有设过属性"
        // 挑一条关键词≥3 的示例：一条词的示例验不出"底色有没有连成整块"这件事
        int multiRow = 0;
        for (int i = 0; i < SqlSamples.ALL.length; i++) {
            if (SqlSamples.ALL[i].keywords.split(" ").length >= 3) {
                multiRow = i;
                break;
            }
        }
        frame.setSqlSamplesRow(multiRow);
        String introSig = frame.sqlSamplesIntroSignature();
        if (!introSig.startsWith("T") || !introSig.contains("L")
                || !introSig.substring(introSig.lastIndexOf('|') + 1).startsWith("K")) {
            problems.add("介绍块的角色分布不对（标题与字段名在前，最后一段应是关键词那块）："
                    + introSig);
        }
        if (!SqlSamples.introText(SqlSamples.ALL[multiRow]).equals(frame.sqlSamplesIntroText())) {
            problems.add("介绍块上色时把纯文本也改了");
        }
        if (frame.sqlSamplesIntroInk(0) <= 0 || frame.sqlSamplesIntroInk(1) <= 0) {
            problems.add("介绍块没真上到色：标题蓝 " + frame.sqlSamplesIntroInk(0)
                    + " 个像素，分组蓝与关键词块 " + frame.sqlSamplesIntroInk(1) + " 个");
        }
        // 关键词一颗一小块底（用户 2026-11-03 报"关键词是多个，你却用了一个底色，人字都看不清"）：
        // 块数等于词数，且没有任何一块长过最长的词——连成整块就是从前那个毛病
        int kSegs = 0;
        int kMax = 0;
        for (String part : introSig.split("\\|")) {
            if (part.startsWith("K")) {
                kSegs++;
                kMax = Math.max(kMax, Integer.parseInt(part.substring(1)));
            }
        }
        int words = 0;
        int wMax = 0;
        for (String word : SqlSamples.ALL[multiRow].keywords.split(" ", -1)) {
            if (word.isEmpty()) {
                continue;
            }
            words++;
            wMax = Math.max(wMax, word.length());
        }
        if (kSegs != words || kMax > wMax) {
            problems.add("关键词的底色连成整块了：应 " + words + " 块，实际 " + kSegs
                    + " 块，最长的一块 " + kMax + " 字（最长的词 " + wMax + " 字）");
        }
        if (introSig.indexOf('S') < 0 || frame.sqlSamplesIntroInk(2) <= 0
                || frame.sqlSamplesIntroInk(3) <= 0 || frame.sqlSamplesIntroInk(4) <= 0) {
            problems.add("「解决的问题」那一档提示绿、关键词那颗紫或它自己的浅底没画到屏上：签名 S="
                    + (introSig.indexOf('S') >= 0) + "，提示绿 " + frame.sqlSamplesIntroInk(2)
                    + " 像素，关键词紫 " + frame.sqlSamplesIntroInk(3)
                    + " 像素，关键词浅底 " + frame.sqlSamplesIntroInk(4) + " 像素");
        }
        // 语句全文这一栏也按角色上色（用户："看着太枯燥"）：读真文档的串 + 数渲染出来的墨，
        // 与介绍块同一套判据，正文那条"一字未改"已由上面两条钉住
        String previewSig = com.excel2mysql.gui.RichText.roleSignature(
                frame.sqlSamplesPreviewPane());
        if (previewSig.indexOf('W') < 0) {
            problems.add("语句全文没挂上关键字那一档：" + previewSig);
        }
        if (inkOf(frame.sqlSamplesPreviewPane(),
                com.excel2mysql.gui.RichText.KEYWORD_FG) <= 0) {
            problems.add("语句全文的关键字那一档紫没真画到屏上");
        }
        // 关键词过滤：命中集合必须与 SqlSamples.matches 判的同一批，命中数要说出来
        frame.typeSqlSamplesFilter("注释");
        int expectHits = 0;
        StringBuilder expectGroups = new StringBuilder();
        String lastGroup = null;
        for (int i = 0; i < SqlSamples.ALL.length; i++) {
            if (!SqlSamples.matches(SqlSamples.ALL[i], "注释")) {
                continue;
            }
            expectHits++;
            if (!SqlSamples.ALL[i].group.equals(lastGroup)) {
                if (expectGroups.length() > 0) {
                    expectGroups.append('|');
                }
                expectGroups.append(SqlSamples.ALL[i].group);
                lastGroup = SqlSamples.ALL[i].group;
            }
        }
        if (frame.sqlSamplesVisibleCount() != expectHits
                || !expectGroups.toString().equals(frame.sqlSamplesGroupTitles())
                || !("命中 " + expectHits + " 条（共 157 条）")
                        .equals(frame.sqlSamplesHitsText())) {
            problems.add("搜「注释」的命中与预期不一致：界面 " + frame.sqlSamplesVisibleCount()
                    + " 条 / " + frame.sqlSamplesGroupTitles() + "，应 " + expectHits
                    + " 条 / " + expectGroups);
        }
        if (!frame.sqlSamplesPreviewText().equals(SqlSamples.ALL[firstHitIndex("注释")].sql)) {
            problems.add("过滤后选中没落到第一条命中，右侧留着被筛掉的那条");
        }
        frame.typeSqlSamplesFilter("绝不存在的关键词");
        if (frame.sqlSamplesVisibleCount() != 0
                || !frame.sqlSamplesHitsText().startsWith("没有命中")) {
            problems.add("一条都没命中时没把话说清：" + frame.sqlSamplesHitsText());
        }
        frame.typeSqlSamplesFilter("");
        if (frame.sqlSamplesVisibleCount() != 157 || frame.sqlSamplesGroupCount() != 13
                || !"共 157 条，分 13 组".equals(frame.sqlSamplesHitsText())) {
            problems.add("清空搜索框没回到全量：" + frame.sqlSamplesHitsText());
        }
        frame.clickSqlSamplesCopy();
        // 剪贴板可能被别的进程占用，两条回执都算有回执；点了之后标题一动不动才是问题
        if (!frame.sqlSamplesTitle().endsWith("已复制到剪贴板")
                && !frame.sqlSamplesTitle().endsWith("剪贴板正被占用，可再点一次）")) {
            problems.add("复制到剪贴板没有任何回执：" + frame.sqlSamplesTitle());
        }
        // 插入：编辑区还是默认那句时直接换成正题；已有内容时补分号换行再接
        frame.showSqlSamples();
        frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
        frame.setSqlSamplesRow(3);
        frame.clickSqlSamplesInsert();
        if (!frame.sqlRunInputText().equals(frame.sqlSampleSql(3))) {
            problems.add("默认文本上插入没换成正题：" + frame.sqlRunInputText());
        }
        if (frame.sqlSamplesShowing()) {
            problems.add("插入之后示例对话框应收起");
        }
        frame.showSqlSamples();
        frame.setSqlRunInputText("SELECT 1");
        frame.setSqlSamplesRow(1);
        frame.clickSqlSamplesInsert();
        if (!frame.sqlRunInputText().startsWith("SELECT 1;")
                || !frame.sqlRunInputText().endsWith(frame.sqlSampleSql(1))) {
            problems.add("已有内容时插入没补分号或覆盖了原句：" + frame.sqlRunInputText());
        }
        // 两颗清空各管一边：清结果区不该带走敲了一半的语句，清编辑区不该抹掉上次的结果
        frame.sqlRunLogPanel().log("上一次留下的结果");
        int seeded = frame.sqlRunLogPanel().lineCount();
        frame.setSqlRunInputText("SELECT 2;");
        if (clearInputBtn != null) {
            clearInputBtn.doClick();
        }
        if (!frame.sqlRunInputText().isEmpty()
                || frame.sqlRunLogPanel().lineCount() != seeded) {
            problems.add("「清空编辑区」没只清编辑区：编辑区=" + frame.sqlRunInputText()
                    + " 结果区行数=" + frame.sqlRunLogPanel().lineCount() + " / " + seeded);
        }
        frame.setSqlRunInputText("SELECT 3;");
        if (clearLogBtn != null) {
            clearLogBtn.doClick();
        }
        if (!"SELECT 3;".equals(frame.sqlRunInputText())
                || frame.sqlRunLogPanel().lineCount() != 0) {
            problems.add("「清空结果区」没只清结果区：编辑区=" + frame.sqlRunInputText()
                    + " 结果区行数=" + frame.sqlRunLogPanel().lineCount());
        }
        if (clearInputBtn != null && !clearInputBtn.getToolTipText().contains("右侧结果区不动")
                || clearLogBtn != null && !clearLogBtn.getToolTipText().contains("左边编辑区不动")) {
            problems.add("两颗清空的 tooltip 没说清各自管哪一边");
        }

        // 执行结果用真表格画：中英文混排时文本表格没法读（用户 2026-10-28）
        sql_run_result_table(frame, problems, clearLogBtn);

        // 脚本队列：清单表（含「操作」列那颗【执行】）与清空/批量执行那一排的初始态
        javax.swing.JTable runTable = frame.sqlRunFilesTable();
        if (runTable == null || runTable.getColumnCount() != 5
                || !"序号".equals(runTable.getColumnName(0))
                || !"文件".equals(runTable.getColumnName(1))
                || !"大小".equals(runTable.getColumnName(2))
                || !"状态".equals(runTable.getColumnName(3))
                || !"操作".equals(runTable.getColumnName(4))) {
            problems.add("脚本清单不是「序号/文件/大小/状态/操作」五列");
        }
        if (runTable != null && (runTable.isCellEditable(0, 0)
                || !com.excel2mysql.gui.Tables.isZebra(runTable))) {
            problems.add("脚本清单应只读且带斑马纹");
        }
        if (runTable != null && !com.excel2mysql.gui.Tables.RUN_TABLE_TITLE.equals(
                com.excel2mysql.gui.Tables.titledAncestor(runTable))) {
            problems.add("脚本清单的组框标题不对："
                    + com.excel2mysql.gui.Tables.titledAncestor(runTable));
        }
        javax.swing.JButton runBtn = frame.sqlRunScriptsButton();
        if (runBtn == null || !"批量执行脚本文件".equals(runBtn.getText())
                || runBtn.isEnabled()) {
            problems.add("空清单时「批量执行脚本文件」应灰着，名字也要带「批量」两字："
                    + (runBtn == null ? "缺" : runBtn.getText()));
        }
        if (countCoverSwitches(frame.tabs().getComponentAt(2)) != 0) {
            problems.add("第三页不该再有「覆盖已存在表」，整行随底部动作条一起删了");
        }
        // 行内那颗【执行】：整列共用一个渲染件，画到哪一行就只管那一行
        if (frame.sqlRunRowExecButton() == null
                || !"执行".equals(frame.sqlRunRowExecButton().getText())) {
            problems.add("清单「操作」列没有那颗【执行】");
        }
        if (frame.sqlRunRowExecButton() != null
                && frame.isRowExecutable()) {
            problems.add("空清单时行内【执行】的判据不该亮着");
        }
        // 这颗按钮的观感（用户 2026-10-30 报"很不醒目"）：档位、行高、上色三件一起钉，
        // 上色按颜色数像素，不读属性——上一轮就是"读返回值"让假绿的守卫混过去的
        if (!"secondary".equals(frame.sqlRunRowExecRole())) {
            problems.add("行内【执行】没套次级强调档，档位是：" + frame.sqlRunRowExecRole());
        }
        int[] rowH = frame.sqlRunRowHeights();
        if (rowH[0] < 0 || rowH[0] < rowH[1]) {
            problems.add("清单行高 " + rowH[0] + " 低于按钮最小高 " + rowH[1] + "，按钮会被裁");
        }
        if (frame.sqlRunRowExecInk() <= 0) {
            problems.add("行内【执行】没上到次级档那支强调蓝（画出来一个蓝像素都没有）");
        }
        if (frame.sqlRunDropArea() == null
                || !com.excel2mysql.gui.DropArea.RUN_HINT.equals(
                        frame.sqlRunDropArea().hintText())) {
            problems.add("第三页的拖拽区文案不是脚本队列那句");
        }
        // 加入、去重、点名读不到的；清单、日志与按钮启用条件要一起跟上
        java.io.File runDir;
        try {
            runDir = java.nio.file.Files.createTempDirectory("e2m-sqlrun").toFile();
        } catch (java.io.IOException e) {
            problems.add("建脚本队列的临时目录失败：" + e);
            return;
        }
        String ra = new java.io.File(runDir, "a.sql").getPath();
        String rb = new java.io.File(runDir, "b.sql").getPath();
        writeText(ra, "CREATE TABLE t_a (id INT);" + String.valueOf((char) 10));
        writeText(rb, "CREATE TABLE t_b (id INT);" + String.valueOf((char) 10));
        String rtxt = new java.io.File(runDir, "说明.txt").getPath();
        writeText(rtxt, "不是脚本");
        frame.sqlRunDropArea().fireDrop(java.util.Arrays.asList(ra, rb, rtxt));
        if (frame.sqlRunFileCount() != 2 || runTable == null || runTable.getRowCount() != 2
                || !"1".equals(runTable.getValueAt(0, 0))
                || !"a.sql".equals(runTable.getValueAt(0, 1))
                || !"待执行".equals(runTable.getValueAt(0, 3))
                || !frame.sqlRunLogPanel().contains("[跳过] 不是 SQL 脚本，未读入：说明.txt")) {
            problems.add("拖入的脚本没按顺序进清单，或混着的别的文件没点名跳过："
                    + frame.sqlRunFileCount() + " 个");
        }
        if (runBtn != null && !runBtn.isEnabled()) {
            problems.add("清单里有脚本后「批量执行脚本文件」仍不可点");
        }
        frame.sqlRunDropArea().fireDrop(java.util.Collections.singletonList(ra));
        if (frame.sqlRunFileCount() != 2
                || !frame.lastDialogText().contains("已在列表")
                || !"添加结果".equals(frame.lastDialogTitle())) {
            problems.add("重复拖入没有去重或没有回执：数量=" + frame.sqlRunFileCount()
                    + " 标题=" + frame.lastDialogTitle()
                    + " 弹窗=" + frame.lastDialogText().replace((char) 10, ' '));
        }
        frame.addRunScriptsFromUi(java.util.Collections.singletonList(
                new java.io.File(runDir, "missing.sql").getPath()));
        if (frame.sqlRunFileCount() != 2
                || !frame.lastDialogText().contains("未加入")
                || !"warning".equals(frame.lastDialogIcon())) {
            problems.add("读不到的脚本没点名「未加入」，或弹窗没用警告级：图标="
                    + frame.lastDialogIcon() + " 弹窗="
                    + frame.lastDialogText().replace((char) 10, ' '));
        }
        frame.clearRunScriptsFromUi();
        if (frame.sqlRunFileCount() != 0 || runTable == null || runTable.getRowCount() != 0
                || (runBtn != null && runBtn.isEnabled())
                || !frame.sqlRunLogPanel().contains("[清空] 已清空 2 个待执行脚本")) {
            problems.add("「清空待执行脚本」没清空清单或没同步灰掉执行按钮");
        }
        // 空清单执行必须当场拦下：先验没选库那句，再临时选个库验"请先添加"
        frame.runScriptsFromUi();
        if (!com.excel2mysql.core.LogText.NO_DB_HINT.equals(frame.lastDialogText())) {
            problems.add("没选库时点「批量执行脚本文件」没弹 NO_DB_HINT："
                    + frame.lastDialogText().replace((char) 10, ' '));
        }
        String keepDb = frame.selectedDatabase();
        frame.setSelectedDatabase("selftest_db");
        frame.runScriptsFromUi();
        if (!"请先添加 SQL 脚本文件。".equals(frame.lastDialogText())) {
            problems.add("有库但清单空着时没拦下：" + frame.lastDialogText());
        }
        // 行内【执行】的入口：清单空着判据就该灰，越界的那一行要拦下来并说清，不去动数据库
        if (frame.isRowExecutable()) {
            problems.add("清单空着时行内【执行】的判据不该亮着");
        }
        frame.pressRowExecute(0);
        if (!frame.lastDialogText().contains("这一行已经不在清单里了")) {
            problems.add("点了不在清单里的行却没有拦下来说明：弹窗="
                    + frame.lastDialogText().replace((char) 10, ' '));
        }
        // Del 键：选中一行只出清单，磁盘上的脚本一个字不动，而且要留一句痕
        frame.sqlRunDropArea().fireDrop(java.util.Arrays.asList(ra, rb));
        runTable.setRowSelectionInterval(0, 0);
        frame.deleteSelectedRunScripts();
        if (frame.sqlRunFileCount() != 1
                || !"b.sql".equals(runTable.getValueAt(0, 1))
                || !frame.sqlRunLogPanel().contains("已移除 a.sql，清单剩 1 个待执行脚本")
                || !new java.io.File(ra).exists()) {
            problems.add("Del 没把选中那一行清出清单，或删了磁盘上的脚本，或没留痕："
                    + frame.sqlRunFileCount() + " 个");
        }
        runTable.clearSelection();
        frame.deleteSelectedRunScripts();
        if (!frame.sqlRunLogPanel().contains("没有选中脚本")) {
            problems.add("没选中行按 Del 静默了，应当给一句下一步");
        }
        frame.sqlRunDropArea().fireDrop(java.util.Collections.singletonList(ra));
        if (!frame.isRowExecutable()) {
            problems.add("清单里还有脚本，行内【执行】的判据却灰着");
        }
        frame.clearRunScriptsFromUi();
        frame.dbCombo().removeAllItems();
        if (!keepDb.isEmpty()) {
            frame.setSelectedDatabase(keepDb);
        } else {
            frame.refreshDbLabels();
        }
        frame.showSqlSamples();
        frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
    }

    /**
     * 执行结果那张真表格：结果集到手后界面画得对不对。
     *
     * 喂结果集而不是跑 SQL——闸门这台机器上不保证有 MySQL，真库那半边由 SqlExecCli
     * 与金标准盯着；这里要验的是"数据落到格子里"这一步，文本表格里英文和中文
     * 混排必然错行（用户 2026-10-28 的原话是"不可阅读"）。
     */
    private static void sql_run_result_table(MainFrame frame, List<String> problems,
                                             javax.swing.JButton clearLogBtn) {
        String[] head = {"id", "姓名", "department"};
        String[][] body = {{"1", "张三丰", "Logistics Division"}, {"2", "李四", "卫勤处"}};
        java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> one =
                new ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
        one.add(MainFrame.newResultSet(1, "select * from t_mixed", head,
                new String[]{"主键编号", "姓名（人才卡上的）", ""}, body));
        frame.feedSqlRunSets(one, 0);
        javax.swing.JTable t = frame.sqlRunResultTable();
        if (!t.isVisible() || t.getRowCount() != 2 || t.getColumnCount() != 3
                || !"姓名".equals(t.getColumnName(1))
                || !"张三丰".equals(t.getValueAt(0, 1))
                || !"卫勤处".equals(t.getValueAt(1, 2))) {
            problems.add("结果集没落到真表格里：可见=" + t.isVisible()
                    + " " + t.getRowCount() + " 行 " + t.getColumnCount() + " 列，"
                    + "第 2 列叫 " + (t.getColumnCount() > 1 ? t.getColumnName(1) : "无")
                    + "，取到 " + (t.getRowCount() > 0 ? t.getValueAt(0, 1) : "无"));
        }
        if (frame.sqlRunCellsEditable()) {
            problems.add("结果表格应只读：用户改格子改的是显示，库里没动");
        }
        // 表头 title：字段注释按列挂着，第三列没有注释就不该留提示
        String tip0 = MainFrame.HEADER_COMMENT_PREFIX + "主键编号";
        String tip1 = MainFrame.HEADER_COMMENT_PREFIX + "姓名（人才卡上的）";
        if (!tip0.equals(frame.sqlRunHeaderTipAt(0)) || !tip1.equals(frame.sqlRunHeaderTipAt(1))
                || !frame.sqlRunHeaderTipAt(2).isEmpty()) {
            problems.add("表头的字段注释 title 没按列对上：第 0 列=" + frame.sqlRunHeaderTipAt(0)
                    + " 第 1 列=" + frame.sqlRunHeaderTipAt(1)
                    + " 第 2 列=" + frame.sqlRunHeaderTipAt(2));
        }
        // 接线取证：把表头上真的鼠标监听取出来，按落在第 2 列中间分发一次 MOUSE_MOVED，
        // 看整条表头的 tooltip 有没有换成那一列的注释。
        // 没让系统真动光标（自检抢用户的鼠标不合适），证到的是"监听挂在表头上 + 按列取注释"
        javax.swing.table.TableColumnModel tipsCm = t.getColumnModel();
        int tipX = tipsCm.getColumn(0).getWidth() + tipsCm.getColumn(1).getWidth() / 2;
        if (tipsCm.getColumn(1).getWidth() <= 0) {
            problems.add("表头还没排版就取不到列宽，鼠标落点算不出来：第 1 列宽 "
                    + tipsCm.getColumn(1).getWidth());
        } else {
            java.util.List<java.awt.event.MouseMotionListener> motions =
                    java.util.Arrays.asList(t.getTableHeader().getMouseMotionListeners());
            if (motions.isEmpty()) {
                problems.add("结果表表头没挂鼠标移动监听：字段注释的 title 没人换");
            } else {
                t.getTableHeader().setToolTipText(null);
                java.awt.event.MouseEvent move = new java.awt.event.MouseEvent(
                        t.getTableHeader(), java.awt.event.MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, tipX,
                        Math.max(1, t.getTableHeader().getHeight() / 2), 0, false);
                for (java.awt.event.MouseMotionListener ml : motions) {
                    ml.mouseMoved(move);
                }
                if (!tip1.equals(t.getTableHeader().getToolTipText())) {
                    problems.add("鼠标停在有注释的列上，表头 tooltip 不是那句注释："
                            + t.getTableHeader().getToolTipText());
                }
                int bareX = tipsCm.getColumn(0).getWidth() + tipsCm.getColumn(1).getWidth()
                        + tipsCm.getColumn(2).getWidth() / 2;
                java.awt.event.MouseEvent move2 = new java.awt.event.MouseEvent(
                        t.getTableHeader(), java.awt.event.MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, bareX,
                        Math.max(1, t.getTableHeader().getHeight() / 2), 0, false);
                for (java.awt.event.MouseMotionListener ml : motions) {
                    ml.mouseMoved(move2);
                }
                if (t.getTableHeader().getToolTipText() != null) {
                    problems.add("停在没注释的列上还留着上一列的提示："
                            + t.getTableHeader().getToolTipText());
                }
            }
        }
        if (!"第 1 条 · 共 2 行".equals(frame.sqlRunCaptionText())) {
            problems.add("表格上方那句说明没写清是第几条、共几行：" + frame.sqlRunCaptionText());
        }
        if (frame.sqlRunChooserVisible() || frame.sqlRunChooserItemCount() != 1) {
            problems.add("只有一条结果集也摆了结果集下拉：可见=" + frame.sqlRunChooserVisible()
                    + " 选项数=" + frame.sqlRunChooserItemCount());
        }
        StringBuilder widths = new StringBuilder();
        // 列宽是 preferred，要排一次版才落成实际宽度：两个字段弹窗也是 pack() 之后才量
        frame.validate();
        if (!frame.sqlRunScrollsHorizontally()) {
            problems.add("结果表格还开着列自适应：列名长一点就被挤成省略号，没得横向滚");
        }
        for (int c = 0; c < t.getColumnCount(); c++) {
            if (!frame.sqlRunColumnFitsContent(c)) {
                widths.append(" 第 ").append(c).append(" 列宽 ").append(t.getColumnModel()
                        .getColumn(c).getWidth()).append(" 装不下内容");
            }
        }
        if (widths.length() > 0) {
            problems.add("结果表格的列宽裁掉了内容，横向滚也看不到全名：" + widths);
        }
        // 一批两条：下拉出现、默认停在最近那条，切回去表格与说明都得跟着换
        java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> two =
                new ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
        two.add(MainFrame.newResultSet(1, "select * from t_mixed", head, body));
        two.add(MainFrame.newResultSet(2, "select a from t_second",
                new String[]{"a"}, new String[][]{{"9"}}));
        frame.feedSqlRunSets(two, 0);
        if (!frame.sqlRunChooserVisible() || frame.sqlRunChooserItemCount() != 2
                || t.getColumnCount() != 1 || !"9".equals(t.getValueAt(0, 0))
                || !"第 2 条 · 共 1 行".equals(frame.sqlRunCaptionText())) {
            problems.add("一批两条时没停在最近那条：可见=" + frame.sqlRunChooserVisible()
                    + " 选项数=" + frame.sqlRunChooserItemCount()
                    + " 列数=" + t.getColumnCount() + " 说明=" + frame.sqlRunCaptionText());
        }
        if (!frame.sqlRunChooserItemText(0).startsWith("第 1 条 · ")
                || !frame.sqlRunChooserItemText(1).contains("t_second")) {
            problems.add("下拉里的选项没交代是第几条、哪句 SQL："
                    + frame.sqlRunChooserItemText(0) + " / " + frame.sqlRunChooserItemText(1));
        }
        frame.selectSqlRunSet(0);
        if (t.getColumnCount() != 3 || !"张三丰".equals(t.getValueAt(0, 1))
                || !"第 1 条 · 共 2 行".equals(frame.sqlRunCaptionText())) {
            problems.add("切回第一条，表格或说明没跟着换：列数=" + t.getColumnCount()
                    + " 说明=" + frame.sqlRunCaptionText());
        }
        // 一批 55 条 select 只留最近 50 条：内存要封顶，但丢掉的必须说出来
        java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> capped =
                new ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
        int[] dropped = {0};
        for (int i = 1; i <= 55; i++) {
            MainFrame.keepLatestSet(capped, dropped, MainFrame.newResultSet(i,
                    "select " + i, new String[]{"n"}, new String[][]{{String.valueOf(i)}}));
        }
        if (capped.size() != MainFrame.MAX_RESULT_SETS || dropped[0] != 5) {
            problems.add("结果集上限没按「丢最早的」执行：留 " + capped.size()
                    + " 个、丢 " + dropped[0] + " 个");
        } else if (capped.get(0).seq != 6 || capped.get(capped.size() - 1).seq != 55) {
            problems.add("留下的是最早那 50 个而不是最近那 50 个：第一个是第 "
                    + capped.get(0).seq + " 条、最后一个是第 "
                    + capped.get(capped.size() - 1).seq + " 条");
        }
        frame.feedSqlRunSets(capped, dropped[0]);
        if (frame.sqlRunChooserItemCount() != MainFrame.MAX_RESULT_SETS
                || !"第 55 条 · 共 1 行".equals(frame.sqlRunCaptionText())
                || !frame.sqlRunLogPanel().contains(
                        "只保留最近 " + MainFrame.MAX_RESULT_SETS + " 个")) {
            problems.add("丢弃了多余结果集却没说、或表格没停在最近那条：选项数="
                    + frame.sqlRunChooserItemCount() + " 说明=" + frame.sqlRunCaptionText());
        }
        // 行数被截断时说明要老实讲：表格里只有前若干行，不写出来会被当成全量
        com.excel2mysql.core.SqlExecute.ResultSetData cut =
                MainFrame.newResultSet(1, "select * from big", head, body);
        cut.total = 300;
        cut.printed = 2;
        java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> big =
                new ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
        big.add(cut);
        frame.feedSqlRunSets(big, 0);
        if (!frame.sqlRunCaptionText().contains("共 300 行")
                || !frame.sqlRunCaptionText().contains("只装前 2 行")) {
            problems.add("结果集被截断时说明没交代只装了前几行：" + frame.sqlRunCaptionText());
        }
        // 超出保留上限也要留痕：静默丢掉前面的结果集，用户会以为表格就是整批
        frame.sqlRunLogPanel().log("上一次留下的结果");
        if (clearLogBtn != null) {
            clearLogBtn.doClick();
        }
        if (t.getRowCount() != 0 || t.isVisible()
                || frame.sqlRunChooserItemCount() != 0 || frame.sqlRunChooserVisible()
                || !"还没有执行结果".equals(frame.sqlRunCaptionText())
                || frame.sqlRunLogPanel().lineCount() != 0) {
            problems.add("「清空结果区」没把表格、下拉与说明一起清："
                    + t.getRowCount() + " 行 可见=" + t.isVisible()
                    + " 选项数=" + frame.sqlRunChooserItemCount()
                    + " 说明=" + frame.sqlRunCaptionText()
                    + " 回执行数=" + frame.sqlRunLogPanel().lineCount());
        }
        // 清空后表头要回到那句"注释挂在各列 title 上"的引导：留着上一列的注释会指错地方
        if (!MainFrame.RESULT_HEADER_HINT.equals(t.getTableHeader().getToolTipText())) {
            problems.add("清空结果区后表头没回到引导句：" + t.getTableHeader().getToolTipText());
        }
        // 一批全是写语句：不摆空表，说明要讲清为什么没有表格
        frame.feedSqlRunSets(
                new ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>(), 0);
        if (t.isVisible() || !frame.sqlRunCaptionText().startsWith("这一批没有返回结果集")) {
            problems.add("没有结果集时摆了空表或说明不对：可见=" + t.isVisible()
                    + " 说明=" + frame.sqlRunCaptionText());
        }
    }

    /** 自检独立按 DPI 折算一遍，不引用 UiScale：产品侧折算被改坏时断言才会红。 */
    private static int wantFontPx(double pt) {
        return (int) Math.round(
                pt * java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 72.0);
    }

    /** Qt 逻辑像素 → 物理像素的独立折算，口径与 UiScale.px 相同但刻意不复用它。 */
    private static int wantPx(int design) {
        return (int) Math.round(design * Math.max(1.0,
                java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96.0));
    }

    /**
     * 主页与 SQL 脚本页两处日志区共用 LogPanel 默认档：9 号被判过小、14 号只露三行，
     * 现定 10；表格保持"比日志大一档"定 11，与 Excel 数据网格同级。
     * 第三页的编辑区与结果区不在这档（那是给人读语句的主区域，仍 16），
     * 由 sql_run_tab 里那组断言管。
     * "10 / 11" 是 Python 侧的 pt，Swing 里必须折成 DPI 对应的物理像素（144dpi → 20 / 22px）。
     */
    private static void checkLogFonts(MainFrame frame, List<String> problems) {
        com.excel2mysql.gui.LogPanel[] logs = {
                frame.logPanel(), frame.sqlPage().logPanel()};
        String[] names = {"主页日志", "SQL 脚本页日志"};
        int wantLog = wantFontPx(10);
        for (int i = 0; i < logs.length; i++) {
            java.awt.Font f = logs[i].getFont();
            if (f == null || !"Monospaced".equals(f.getFamily())) {
                problems.add(names[i] + "不是等宽字体：" + (f == null ? "无字体" : f.getFamily()));
            }
            if (f == null || f.getSize() != wantLog) {
                problems.add(names[i] + "字号不是 10pt 折算的 " + wantLog + "px："
                        + (f == null ? "无字体" : f.getSize()));
            }
        }
        java.awt.Font base = logs[0].getFont();
        for (int i = 1; i < logs.length; i++) {
            java.awt.Font f = logs[i].getFont();
            if (base != null && f != null
                    && (!base.getFamily().equals(f.getFamily()) || base.getSize() != f.getSize())) {
                problems.add("日志区字体不一致：" + names[0] + " " + base.getFamily() + "/"
                        + base.getSize() + "，但 " + names[i] + " " + f.getFamily() + "/"
                        + f.getSize());
            }
        }
        // 用户口径（2026-10-23）：内容比日志大一档。表格 11、日志 10，
        // 期望值写死成这两个契约数字，不拿常量当期望值。
        // 顺带盯行高跟着字号走——行高写死的话放大字号会把字裁掉一半
        java.awt.Font tf = frame.fileTable().getFont();
        java.awt.Font hf = frame.fileTable().getTableHeader().getFont();
        int wantTable = wantFontPx(11);
        if (tf.getSize() <= wantLog || hf.getSize() <= wantLog) {
            problems.add("表格内容/表头字号没有比日志大一档：内容 " + tf.getSize()
                    + "、表头 " + hf.getSize() + "，日志 " + wantLog + "px");
        }
        if (tf.getSize() != wantTable || hf.getSize() != wantTable) {
            problems.add("表格字号不是 11pt 折算的 " + wantTable + "px：内容 "
                    + tf.getSize() + "、表头 " + hf.getSize());
        }
        if (frame.fileTable().getRowHeight() <= tf.getSize()) {
            problems.add("表格行高没跟着字号走：行高 " + frame.fileTable().getRowHeight()
                    + "，字号 " + tf.getSize());
        }
    }

    /**
     * 「SQL 脚本改名」页那两颗生成按钮的自检：解析→导出→回读，全程离线。
     *
     * 用一个新的 SqlPage 实例而不是复用 sql_page 那个：那份页面已经被前面的读取/改名
     * 用例改过状态，混在一起查就分不清"这一条日志是刚才哪一步写的"。
     */
    private static void sql_gen_excel(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-sqlgen").toFile();
        } catch (java.io.IOException e) {
            problems.add("建生成检查的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        java.io.File script = new java.io.File(dir, "gen.sql");
        writeText(script.getPath(),
                "CREATE TABLE `t_gen` (" + nl
                        + "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键'," + nl
                        + "  `name` varchar(16) NOT NULL COMMENT '名称'," + nl
                        + "  `st` enum('a','b') DEFAULT 'a'," + nl
                        + "  PRIMARY KEY (`id`)" + nl
                        + ") ENGINE=InnoDB COMMENT='生成检查表';" + nl
                        + "CREATE TABLE t_dup2 (x INT);" + nl
                        + "CREATE TABLE t_dup2 (y INT);" + nl
                        + "CREATE TABLE t_like2 LIKE t_dup2;" + nl);

        com.excel2mysql.gui.SqlPage page = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        page.addFiles(paths);
        // 任务体的日志走的是主窗口那份 SQL 页的面板（runSqlTask 按页归属路由日志，
        // 只有自检才会另开一个 SqlPage），所以这些行要从 frame.sqlPage() 那边读。
        com.excel2mysql.gui.LogPanel taskLog = frame.sqlPage().logPanel();

        javax.swing.JButton std = page.genStdButton();
        javax.swing.JButton simple = page.genSimpleButton();
        if (std == null || simple == null) {
            problems.add("SQL 页没有「生成标准excel文件」「生成简易excel文件」按钮");
            return;
        }
        if (!"生成标准excel文件".equals(std.getText())
                || !"生成简易excel文件".equals(simple.getText())) {
            problems.add("两颗生成按钮的文案与 Excel 页不一致：" + std.getText()
                    + " / " + simple.getText());
        }
        if (!std.isEnabled() || !simple.isEnabled()) {
            problems.add("已有脚本时两颗生成按钮还是灰的");
        }
        // 一张表的数据行上限：不设人为条数，按 xlsx 单表 1048576 行的 2/3 算（699050）。
        // 说明里必须报出这个数与它的来历，两端数字与话术都要与 Python 一致
        if (com.excel2mysql.gui.SqlPage.XLSX_MAX_ROWS != 1048576
                || com.excel2mysql.gui.SqlPage.SQL_EXPORT_ROW_CAP != 699050) {
            problems.add("数据行上限不是 xlsx 单表上限的 2/3："
                    + com.excel2mysql.gui.SqlPage.XLSX_MAX_ROWS + " / "
                    + com.excel2mysql.gui.SqlPage.SQL_EXPORT_ROW_CAP);
        }
        String cap = String.valueOf(com.excel2mysql.gui.SqlPage.SQL_EXPORT_ROW_CAP);
        for (javax.swing.JButton b : new javax.swing.JButton[]{std, simple}) {
            String tip = b.getToolTipText() == null ? "" : b.getToolTipText();
            // 悬停那一行只报得出"最多几行"这个数；1048576 与 2/3 的来历在「使用说明」里，
            // 两处合起来才是完整口径——把来历也塞进 tooltip 就又变成压住按钮的多行提示了
            if (!tip.contains(cap)) {
                problems.add("生成按钮的说明没报出行上限 " + cap + "："
                        + tip.replace((char) 10, (char) 32));
            }
        }
        String help = com.excel2mysql.gui.HelpDoc.html();
        if (!help.contains("1048576") || !help.contains(cap)) {
            problems.add("「使用说明」没写清行上限的数与来历（" + cap + " / 1048576）");
        }

        String outRoot = new java.io.File(dir, "out").getPath();
        page.generateExcel(true, outRoot);
        String log = taskLog.text() + page.logPanel().text();
        if (!log.contains("! [WARN] 说明 | `t_gen`：列 id 按 ")
                || !log.contains("元数据 Sheet 装不下它的列级属性：UNSIGNED")) {
            problems.add("近似掉的 UNSIGNED 没在日志里点名："
                    + tailOf(log).replace((char) 10, (char) 32));
        }
        if (!log.contains("列 st 的") || !log.contains("取值列表 'a','b'")
                || !log.contains("改按 TEXT 导出")) {
            problems.add("ENUM 列被近似却没说明");
        }
        if (!log.contains("表名 t_dup2 在本脚本里被定义了多次")) {
            problems.add("同名表第二处定义没说明只按第一处导出");
        }
        if (!log.contains("后面没有列定义")) {
            problems.add("LIKE 那条没有列定义的表没说明被跳过");
        }
        if (!log.contains("===== 生成完成：2/2 个文件")) {
            problems.add("收尾行没报出写成数："
                    + tailOf(log).replace((char) 10, (char) 32));
        }
        if (!"生成结果".equals(frame.lastDialogTitle())) {
            problems.add("生成完成没弹窗回执：" + frame.lastDialogTitle());
        }
        javax.swing.JComponent copyBody = frame.popupMessageComponent(
                "已生成 124 个文件（共 124 张表）。\n目录：\n"
                + "C:\\Users\\acliu\\Desktop\\excel2mysql-1021-三端产物\\"
                + "03-Java-图形与命令行-带JRE\\sql_meta_data_file_20261022");
        java.awt.Dimension popupSize = copyBody.getPreferredSize();
        if (!(copyBody instanceof javax.swing.JTextArea)
                || ((javax.swing.JTextArea) copyBody).isEditable()) {
            problems.add("生成结果弹窗正文不可复制：应使用只读文本区");
        }
        // 弹窗按正文要多大给多大之后，这条判据换成"产物路径那一行不许被挤断"：
        // 以前断的是"至少 820x420"，那正是让一句话的提示也摊开半屏空白的那块地板。
        // 量的必须是真弹窗里的正文控件——单独 new 出来的文本区还没被塞进滚动区，
        // 折行文本区自己的首选宽度恒等于 130，拿它当"窗口有多宽"会假红
        String pathLine = "C:\\Users\\acliu\\Desktop\\excel2mysql-1021-三端产物\\"
                + "03-Java-图形与命令行-带JRE\\sql_meta_data_file_20261022";
        String genBody = "已生成 124 个文件（共 124 张表）。\n目录：\n" + pathLine;
        com.excel2mysql.gui.MessageDialog genDlg = com.excel2mysql.gui.MessageDialog.create(
                frame, "生成结果", genBody, null, javax.swing.JOptionPane.PLAIN_MESSAGE);
        genDlg.validate();
        javax.swing.JTextArea genArea = genDlg.textArea();
        int pathLineWidth = genArea.getFontMetrics(genArea.getFont()).stringWidth(pathLine);
        if (genArea.getWidth() < pathLineWidth) {
            problems.add("生成结果弹窗窄到放不下产物路径那一行：正文宽 "
                    + genArea.getWidth() + " < 路径 " + pathLineWidth);
        }
        if (genDlg.scrollPane().getVerticalScrollBar().isVisible()) {
            problems.add("三行内容的生成结果弹窗也带着滚动条");
        }
        genDlg.dispose();
        // Python 的消息框吃应用默认字体 9pt；144dpi 下应折成 18px。
        // 期望值独立算，不拿 MessageDialog.FONT_SIZE 自己比自己。
        int wantPopupPx = wantFontPx(9);
        if (copyBody.getFont() == null || copyBody.getFont().getSize() != wantPopupPx) {
            problems.add("结果弹窗正文字号不是 Python 默认 9pt 折算的 " + wantPopupPx
                    + "px：" + copyBody.getFont());
        }
        StringBuilder longText = new StringBuilder("任务异常：以下文件未能读入。这一句故意写得很长很长，"
                + "是为了验证正文控件真的按内容区的右边界把一行折下去，"
                + "而不是把窗口横向撑宽、也不是提前半幅就断行，所以还得再续一段才够长。");
        for (int i = 0; i < 40; i++) {
            longText.append("\n[读取失败] C:\\data\\第").append(i)
                    .append("批\\很长的文件名用来验证滚动与复制.sql：编码无法识别");
        }
        javax.swing.JComponent longArea = frame.popupMessageComponent(longText.toString());
        if (!(longArea instanceof javax.swing.JTextArea)
                || ((javax.swing.JTextArea) longArea).getLineCount() <= 24
                || !((javax.swing.JTextArea) longArea).getText().contains("第39批")) {
            problems.add("弹窗正文这条线仍按 24 行截断，后面的报错看不到");
        }
        String shown = frame.popupBodyText(longText.toString());
        com.excel2mysql.gui.MessageDialog md = com.excel2mysql.gui.MessageDialog.create(
                frame, "任务异常", shown, null,
                javax.swing.JOptionPane.ERROR_MESSAGE);
        if (!com.excel2mysql.gui.MessageDialog.hasScrollPane(md.textArea())) {
            problems.add("结果弹窗正文没装进滚动区，超出部分无处可看");
        }
        md.validate();
        if (!md.scrollPane().getVerticalScrollBar().isVisible()) {
            problems.add("长报错没有滚动条，超出一屏的内容看不到");
        }
        if (!"复制全部".equals(md.copyButton().getText())
                || md.copyButton().getActionListeners().length == 0) {
            problems.add("结果弹窗没有可用的「复制全部」：" + md.copyButton().getText());
        }
        if (!md.displayText().contains("第39批")
                || md.textArea().getLineCount() <= 24) {
            // 走截断版的话第 25 行往后会换成「见日志」，40 条报错只剩 23 条
            problems.add("长报错被截成 24 行，弹窗里翻不到后几批：" + md.textArea().getLineCount());
        }
        if (md.fullText().length() <= 0 || !md.fullText().contains("第39批")) {
            problems.add("「复制全部」拿到的不是完整原文");
        }
        // 正文里不许出现原文没有的换行：折行是排版的活，写进文本就再也追不上窗口宽度
        if (!shown.equals(longText.toString()) || !md.displayText().equals(shown)) {
            problems.add("弹窗正文被预先插了换行，窗口拉大也不会重排："
                    + firstLine(md.displayText()));
        }
        // 折行交给控件：控件必须开着按词折，而且样本里得真有一段宽过内容区，
        // 否则这条判据在这台机器上咬不住东西（宁可显式说它没咬到）
        javax.swing.JTextArea body = md.textArea();
        java.awt.FontMetrics bfm = body.getFontMetrics(body.getFont());
        int widest = 0;
        for (String para : longText.toString().split("\n", -1)) {
            widest = Math.max(widest, bfm.stringWidth(para));
        }
        if (!body.getLineWrap() || !body.getWrapStyleWord()) {
            problems.add("正文控件没开按词折行，长句只能横向挤着看");
        }
        if (widest <= body.getWidth()) {
            problems.add("样本里没有宽过内容区的段落，折行这条判据咬不住东西：最宽段 "
                    + widest + "px / 控件宽 " + body.getWidth());
        }
        if (md.getHeight() < UiScale.px(420)) {
            problems.add("40 行长报错的弹窗没有长到接近上限（尺寸被重复折算？）："
                    + md.getSize());
        }
        // 弹窗尺寸常量是设计值，只能由 Windows.clientSize 折一次；先折再传会变成 1.5 倍
        java.awt.Dimension mdClient = md.getContentPane().getPreferredSize();
        if (mdClient.width != UiScale.px(880)) {
            problems.add("结果弹窗客户区宽度被重复折算：" + mdClient.width
                    + "，应为 " + UiScale.px(880));
        }
        if (!com.excel2mysql.gui.Windows.workArea(md).contains(md.getBounds())) {
            problems.add("结果弹窗超出屏幕可用区：" + md.getBounds());
        }
        if (!md.isResizable()) {
            problems.add("结果弹窗不能拖动改大小，长路径只能横向挤着看");
        }
        md.dispose();
        // 反过来：一句话的提示不该占半屏，也不该带着一条用不上的滚动条
        com.excel2mysql.gui.MessageDialog tiny = com.excel2mysql.gui.MessageDialog.create(
                frame, "提示", "清单里没有脚本文件，无需清空", null,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (tiny.getWidth() >= UiScale.px(820) || tiny.getHeight() > UiScale.px(240)) {
            problems.add("一句话的提示框还是很大一块：" + tiny.getSize());
        }
        tiny.validate();
        if (tiny.scrollPane().getVerticalScrollBar().isVisible()) {
            problems.add("内容只有一行也给了竖直滚动条（正文被尺寸地板顶高了）");
        }
        if (!tiny.isResizable()) {
            problems.add("短提示框也不能拖动改大小：" + tiny.getSize());
        }
        tiny.dispose();

        java.io.File stdDir = new java.io.File(outRoot, "sql_meta_data_file_" + today());
        String[] produced = stdDir.list();
        java.util.Arrays.sort(produced == null ? new String[0] : produced);
        // 只比"是哪两个文件"不比排序后的先后：文件名里带中文，
        // Java 的字典序与 Python 的不是一套，比顺序会造出假红
        java.util.Set<String> producedSet = produced == null
                ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(java.util.Arrays.asList(produced));
        if (producedSet.size() != 2
                || !producedSet.contains("生成检查表（t_gen）.xlsx")
                || !producedSet.contains("t_dup2.xlsx")) {
            problems.add("标准件目录里的文件不对：" + java.util.Arrays.toString(produced));
        }
        String genPath = new java.io.File(stdDir, "生成检查表（t_gen）.xlsx").getPath();
        try {
            List<com.excel2mysql.core.MetaExport.Dump> dumps =
                    com.excel2mysql.core.MetaExport.dump(genPath);
            if (dumps.size() != 2 || !"元数据".equals(dumps.get(0).sheet)
                    || !"数据".equals(dumps.get(1).sheet)) {
                problems.add("标准件应是元数据 + 数据两个 Sheet：" + sheetsOf(dumps));
            }
            if (dumps.size() > 1 && dumps.get(1).cells.size() != 1) {
                problems.add("脚本里没有数据行，数据 Sheet 却不止表头那一行："
                        + (dumps.get(1).cells.size() - 1) + " 行");
            }
            com.excel2mysql.core.TableBuilder.Result back =
                    new com.excel2mysql.core.TableBuilder(
                            new com.excel2mysql.core.Infer(frame.names()),
                            frame.names()).build(genPath);
            if (!"structured".equals(back.table.source)) {
                problems.add("标准件回读没走结构定义那条路");
            }
            if (back.table.columns.size() != 3) {
                problems.add("回读列数应为 3，实际 " + back.table.columns.size());
            }
            com.excel2mysql.core.Infer.Column c0 =
                    back.table.columns.isEmpty() ? null : back.table.columns.get(0);
            if (c0 == null || !"BIGINT(20)".equals(c0.type) || !c0.primaryKey) {
                problems.add("回读的主键列不对：" + (c0 == null ? "没有列" : c0.field + " " + c0.type));
            }
            if (back.table.columns.size() == 3
                    && !"TEXT".equals(back.table.columns.get(2).type)) {
                problems.add("ENUM 列近似成 TEXT 后回读不是 TEXT："
                        + back.table.columns.get(2).type);
            }
            if (!"生成检查表".equals(back.table.tableComment)) {
                problems.add("表注释没跟着进交付件：" + back.table.tableComment);
            }
        } catch (Exception e) {
            problems.add("标准件读不回来：" + e);
        }

        page.generateExcel(false, outRoot);
        java.io.File simpleDir = new java.io.File(outRoot, "sql_simple_meta_data_file_" + today());
        String simplePath = new java.io.File(simpleDir, "生成检查表（t_gen）.xlsx").getPath();
        try {
            List<com.excel2mysql.core.MetaExport.Dump> dumps =
                    com.excel2mysql.core.MetaExport.dump(simplePath);
            if (!"[数据]".equals(sheetsOf(dumps))) {
                problems.add("简易件应只有「数据」一个 Sheet：" + sheetsOf(dumps));
            }
        } catch (Exception e) {
            problems.add("简易件读不回来：" + e);
        }

        // 空列表时点这两颗按钮要拦下来并说清楚，不是静默什么都不做
        com.excel2mysql.gui.SqlPage empty = new com.excel2mysql.gui.SqlPage(frame);
        empty.generateExcel(true, outRoot);
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("请先添加 SQL 脚本文件。")) {
            problems.add("没有脚本时点生成按钮没拦下：标题=" + frame.lastDialogTitle());
        }
        // 清单空时四颗动作按钮都要在既有样式上置灰：不灰的话点下去才被拦，用户先白点一次
        if (empty.saveButton().isEnabled() || empty.runButton().isEnabled()) {
            problems.add("清单空时「保存修改后ddl脚本」「执行修改后ddl脚本到当前库」没有置灰");
        }
        // 前缀两颗按钮同样要有前置守卫：无文件时返回提示，由按钮接线走 host.warn
        empty.setPrefixText("dim_");
        if (!com.excel2mysql.core.LogText.NO_SQL_HINT.equals(empty.applyPrefix())) {
            problems.add("没有脚本时「批量应用前缀」没返回提示：" + empty.applyPrefix());
        }
        if (!com.excel2mysql.core.LogText.NO_SQL_HINT.equals(empty.replaceFirstPrefix())) {
            problems.add("没有脚本时「替换首个前缀」没返回提示：" + empty.replaceFirstPrefix());
        }

        // 空脚本（0 张表）：点按钮要弹窗说明，不是记一行"共 0 个表名"就算完
        String emptySql = new File(dir, "empty.sql").getPath();
        writeText(emptySql, "");
        com.excel2mysql.gui.SqlPage emptyScript = new com.excel2mysql.gui.SqlPage(frame);
        List<String> emptyPaths = new ArrayList<String>();
        emptyPaths.add(emptySql);
        emptyScript.addFiles(emptyPaths);
        if (emptyScript.entryCount() != 1 || emptyScript.rowCount() != 0) {
            problems.add("空脚本应加载成 1 个文件、0 行表名，实际 "
                    + emptyScript.entryCount() + "/" + emptyScript.rowCount());
        }
        // 任务日志按页归属路由到主窗口那份 SQL 页，不在这颗临时页自己的面板里
        String emptyLog = frame.sqlPage().logPanel().text();
        if (!emptyLog.contains("[已加载] empty.sql：0 个表（未发现 CREATE TABLE 语句）")
                || emptyLog.contains("0 个表（（")) {
            problems.add("空脚本加载日志的括号或文案不对：" + emptyLog.replace((char) 10, ' '));
        }
        emptyScript.setPrefixText("dim_");
        javax.swing.JButton emptyApply = buttonIn(emptyScript, "批量应用前缀");
        javax.swing.JButton emptyReplace = buttonIn(emptyScript, "替换首个前缀");
        if (emptyApply == null || emptyReplace == null) {
            problems.add("SQL 页上找不到两颗前缀按钮");
        } else {
            emptyApply.doClick();
            if (!"提示".equals(frame.lastDialogTitle())
                    || !"warning".equals(frame.lastDialogIcon())
                    || !frame.lastDialogText().contains("没有解析出 CREATE TABLE")) {
                problems.add("空脚本点「批量应用前缀」没弹窗说明：标题=" + frame.lastDialogTitle()
                        + " 图标=" + frame.lastDialogIcon()
                        + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
            }
            emptyReplace.doClick();
            if (!"提示".equals(frame.lastDialogTitle())
                    || !"warning".equals(frame.lastDialogIcon())
                    || !frame.lastDialogText().contains("没有解析出 CREATE TABLE")) {
                problems.add("空脚本点「替换首个前缀」没弹窗说明：标题=" + frame.lastDialogTitle()
                        + " 图标=" + frame.lastDialogIcon()
                        + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
            }
        }

        // ---- 第二页的大小写开关：默认不勾选，清单/元数据/生成物一起转大写，脚本正文不动 ----
        if (page.upperBox().isSelected()) {
            problems.add("SQL 页大写开关默认应不勾选");
        }
        if (page.lowerBox() == null) {
            problems.add("SQL 页缺少「表名与字段名转小写」开关");
        } else if (!"表名与字段名转小写".equals(page.lowerBox().getText())) {
            problems.add("SQL 页「转小写」文案不对：" + page.lowerBox().getText());
        } else if (page.lowerBox().isSelected()) {
            problems.add("SQL 页小写开关默认应不勾选");
        }
        boolean hasSqlInitials = false;
        for (java.awt.Component c : ((java.awt.Container) page.upperBox().getParent()).getComponents()) {
            if (c instanceof javax.swing.JCheckBox
                    && "表名和字段名取中文拼音首字母".equals(((javax.swing.JCheckBox) c).getText())) {
                hasSqlInitials = true;
            }
        }
        if (hasSqlInitials) {
            problems.add("SQL 页不应再有「表名和字段名取中文拼音首字母」开关");
        }
        // 命名规则工具条贴表格上沿（与第一页同一处），序号列收窄，表格加斑马纹
        java.awt.Container sqlNameBar = page.upperBox().getParent();
        java.awt.Container sqlNameHost = sqlNameBar == null ? null : sqlNameBar.getParent();
        if (sqlNameHost == null || !(sqlNameHost.getLayout() instanceof java.awt.BorderLayout)
                || ((java.awt.BorderLayout) sqlNameHost.getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.NORTH) != sqlNameBar
                || !sqlNameHost.isAncestorOf(page.tableWidget())) {
            problems.add("SQL 页命名规则没贴在表格上沿");
        }
        if (!com.excel2mysql.gui.Tables.isZebra(page.tableWidget())) {
            problems.add("SQL 页表格没有斑马纹");
        }
        if (page.tableWidget().getColumnModel().getColumn(0).getPreferredWidth()
                != wantPx(com.excel2mysql.gui.Tables.SERIAL_COL_PX)) {
            problems.add("SQL 页序号列宽不是 "
                    + wantPx(com.excel2mysql.gui.Tables.SERIAL_COL_PX) + "，实际 "
                    + page.tableWidget().getColumnModel().getColumn(0).getPreferredWidth());
        }
        if (!"primary".equals(com.excel2mysql.gui.Buttons.role(page.runButton()))) {
            problems.add("SQL 页「执行」不是强调按钮");
        }
        page.upperBox().doClick();
        String toggleLog = page.logPanel().text();
        if (!toggleLog.contains("[提示] 表名与字段名大小写切换为：大写"
                + "（当前表名清单、表元数据和生成的 Excel 一起变；脚本正文不改）")) {
            problems.add("SQL 页大小写开关切换没写日志说明作用域："
                    + tailOf(toggleLog).replace((char) 10, (char) 32));
        }

        java.io.File caseScript = new java.io.File(dir, "name_case.sql");
        String caseSql = "CREATE TABLE `t_case9` (" + nl
                + "  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键'," + nl
                + "  `订单金额` decimal(10,2) DEFAULT NULL COMMENT '金额'," + nl
                + "  `客户名称` varchar(32) DEFAULT NULL COMMENT '客户'," + nl
                + "  PRIMARY KEY (`id`)" + nl
                + ") ENGINE=InnoDB COMMENT='大小写检查表';" + nl;
        writeText(caseScript.getPath(), caseSql);
        com.excel2mysql.gui.SqlPage named = new com.excel2mysql.gui.SqlPage(frame);
        List<String> casePaths = new ArrayList<String>();
        casePaths.add(caseScript.getPath());
        named.addFiles(casePaths);
        named.upperBox().doClick();
        if (!named.sqlRowNameEditable(0)) {
            problems.add("SQL 页「当前表名」列不可直接编辑");
        }
        if (!"T_CASE9".equals(named.sqlRowDisplayName(0))
                || !"t_case9".equals(named.sqlRowActualName(0))) {
            problems.add("SQL 页当前表名显示大写失败：显示=" + named.sqlRowDisplayName(0)
                    + " 实际=" + named.sqlRowActualName(0));
        }
        com.excel2mysql.core.SqlScript.ParsedTable parsedCase = named.metadataAtRow(0);
        if (parsedCase == null || parsedCase.columns.size() != 3
                || !"主键".equals(parsedCase.columns.get(0).comment)) {
            problems.add("SQL 页双击行的元数据解析不对");
        }
        named.setSqlRowName(0, "manual_case");
        String namedText = named.textOf(caseScript.getPath());
        if (!"manual_case".equals(named.sqlRowActualName(0))
                || !"MANUAL_CASE".equals(named.sqlRowDisplayName(0))
                || !namedText.contains("CREATE TABLE `manual_case`")
                || !namedText.contains("PRIMARY KEY (`id`)")) {
            problems.add("SQL 页手改当前表名没落到这一行的脚本文本：" + tailOf(namedText));
        }
        named.setSqlRowName(0, "t_case9");

        java.io.File namedRoot = new java.io.File(dir, "named");
        named.generateExcel(true, namedRoot.getPath());
        java.io.File namedStd = new java.io.File(namedRoot, "sql_meta_data_file_" + today());
        String namedFile = "大小写检查表（T_CASE9）.xlsx";
        java.io.File namedPath = new java.io.File(namedStd, namedFile);
        // 文件名大小写要按目录清单比：Windows 文件系统不区分大小写，isFile() 会把
        // 小写名的产物也认成大写名，开关失效就查不出来
        String[] namedProduced = namedStd.list();
        java.util.Set<String> namedSet = namedProduced == null
                ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(java.util.Arrays.asList(namedProduced));
        if (!namedSet.equals(java.util.Collections.singleton(namedFile))) {
            problems.add("勾大写开关后标准件目录里的文件不对，应只有 " + namedFile + "："
                    + java.util.Arrays.toString(namedProduced));
        }
        if (namedPath.isFile()) {
            try {
                com.excel2mysql.core.Names upNames =
                        new com.excel2mysql.core.Names(
                                com.excel2mysql.core.PinyinLoader.get());
                upNames.setUpperOnly(true);
                com.excel2mysql.core.TableBuilder.Result back =
                        new com.excel2mysql.core.TableBuilder(
                                new com.excel2mysql.core.Infer(frame.names()),
                                upNames).build(namedPath.getPath());
                StringBuilder fields = new StringBuilder();
                StringBuilder comments = new StringBuilder();
                for (com.excel2mysql.core.Infer.Column c : back.table.columns) {
                    fields.append(fields.length() == 0 ? "" : "/").append(c.field);
                    comments.append(comments.length() == 0 ? "" : "/").append(c.comment);
                }
                if (!"T_CASE9".equals(back.table.tableName)
                        || !"ID/订单金额/客户名称".equals(fields.toString())
                        || !"主键/金额/客户".equals(comments.toString())) {
                    problems.add("SQL 页大小写开关生成物回读不对：表名=" + back.table.tableName
                            + " 字段=" + fields + " 注释=" + comments);
                }
            } catch (Exception e) {
                problems.add("SQL 页大小写开关生成物读不回来：" + e);
            }
        }
        // 用户 2026-10-28 报的现场形状：两个 Sheet、元数据里字段名是小写英文。
        // 「表名与字段名转大写」原先只管表名，勾了字段名一动不动，看着就是开关失效
        try {
            java.io.File mtdCase = new java.io.File(dir, "采购品目分类目录表（t_mtd_case）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook mwb =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet ms = mwb.createSheet("元数据");
            String[] mh = {"序号", "字段名", "字段注释", "数据类型", "长度", "是否主键", "是否可为空"};
            org.apache.poi.ss.usermodel.Row mhr = ms.createRow(0);
            for (int i = 0; i < mh.length; i++) {
                mhr.createCell(i).setCellValue(mh[i]);
            }
            String[] mrow = {"1", "item_code", "品目编码", "VARCHAR", "20", "是", "否"};
            org.apache.poi.ss.usermodel.Row mr = ms.createRow(1);
            for (int i = 0; i < mrow.length; i++) {
                mr.createCell(i).setCellValue(mrow[i]);
            }
            org.apache.poi.ss.usermodel.Sheet ds = mwb.createSheet("数据");
            ds.createRow(0).createCell(0).setCellValue("item_code");
            ds.createRow(1).createCell(0).setCellValue("PM-000001");
            writeWorkbook(mwb, mtdCase);
            com.excel2mysql.core.Names mUp =
                    new com.excel2mysql.core.Names(com.excel2mysql.core.PinyinLoader.get());
            mUp.setUpperOnly(true);
            com.excel2mysql.core.TableBuilder.Result upR =
                    new com.excel2mysql.core.TableBuilder(
                            new com.excel2mysql.core.Infer(mUp), mUp).build(mtdCase.getPath());
            com.excel2mysql.core.Names mOff =
                    new com.excel2mysql.core.Names(com.excel2mysql.core.PinyinLoader.get());
            mOff.setUpperOnly(false);
            com.excel2mysql.core.TableBuilder.Result offR =
                    new com.excel2mysql.core.TableBuilder(
                            new com.excel2mysql.core.Infer(mOff), mOff).build(mtdCase.getPath());
            String upField = upR.table.columns.get(0).field;
            String offField = offR.table.columns.get(0).field;
            if (!"ITEM_CODE".equals(upField)) {
                problems.add("结构定义模式勾上「转大写」，元数据里手写的字段名没跟着转：" + upField);
            }
            if (!"item_code".equals(offField)) {
                problems.add("结构定义模式不勾开关也该保持手写原样，不该改成：" + offField);
            }
            if (upR.rows.isEmpty() || !"PM-000001".equals(
                    String.valueOf(((java.util.List<Object>) upR.rows.get(0)).get(0)))) {
                problems.add("字段名转成大写后，数据 Sheet 的小写表头对不上列了：" + upR.rows.size());
            }
        } catch (Exception e) {
            problems.add("结构定义模式的大小写用例跑不起来：" + e);
        }
        // 「表名与字段名转小写」（用户 2026-10-29 点名）：转写那一路本来就落在小写，
        // 这颗真管到的是元数据里人工手写的大写字段名，所以夹具特意把字段名写成大写
        try {
            java.io.File hcase = new java.io.File(dir, "人工大写（t_hand_upper）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook hwb =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet hs = hwb.createSheet("元数据");
            String[] hh = {"序号", "字段名", "字段注释", "数据类型", "长度", "是否主键", "是否可为空"};
            org.apache.poi.ss.usermodel.Row ihr = hs.createRow(0);
            for (int i = 0; i < hh.length; i++) {
                ihr.createCell(i).setCellValue(hh[i]);
            }
            String[] hrow = {"1", "ITEM_CODE", "品目编码", "VARCHAR", "20", "是", "否"};
            org.apache.poi.ss.usermodel.Row hr = hs.createRow(1);
            for (int i = 0; i < hrow.length; i++) {
                hr.createCell(i).setCellValue(hrow[i]);
            }
            org.apache.poi.ss.usermodel.Sheet hds = hwb.createSheet("数据");
            hds.createRow(0).createCell(0).setCellValue("item_code");
            hds.createRow(1).createCell(0).setCellValue("PM-000002");
            writeWorkbook(hwb, hcase);
            String path = hcase.getPath();
            String keep = structField(path, false, false);
            String lowered = structField(path, false, true);
            String both = structField(path, true, true);
            if (!"ITEM_CODE".equals(keep)) {
                problems.add("两颗大小写开关都不勾时手写字段名该原样留着，实际：" + keep);
            }
            if (!"item_code".equals(lowered)) {
                problems.add("勾上「转小写」后元数据里手写的大写字段名没跟着转：" + lowered);
            }
            if (!"ITEM_CODE".equals(both)) {
                problems.add("两档同时给时应按大写，实际：" + both);
            }
        } catch (Exception e) {
            problems.add("结构定义模式的转小写用例跑不起来：" + e);
        }
        // 第二页的「转小写」：勾上把「转大写」按掉，显示与生成件都回到小写，脚本正文照旧不动
        named.lowerBox().doClick();
        if (named.upperBox().isSelected() || !named.lowerBox().isSelected()) {
            problems.add("SQL 页两颗大小写开关没有互斥：大写=" + named.upperBox().isSelected()
                    + " 小写=" + named.lowerBox().isSelected());
        }
        if (!"t_case9".equals(named.sqlRowDisplayName(0))) {
            problems.add("勾「转小写」后当前表名显示没回到小写：" + named.sqlRowDisplayName(0));
        }
        java.io.File lowerRoot = new java.io.File(dir, "lower_named");
        named.logPanel().clearLog();
        // 任务体里的 sink.log 落在主窗口当前那一页的面板，不在 named 自己这块：
        // 两处一起读才判得住档位。后面还有一条要验「转大写」的旧断言，所以这里不清主页面，
        // 只数它出现几次、有没有被这一档多写一遍
        int upperLineBefore = countIn(frame.sqlPage().logPanel().text(),
                "[命名] 生成物表名与字段名：转大写");
        named.generateExcel(true, lowerRoot.getPath());
        java.io.File lowerDir = new java.io.File(lowerRoot, "sql_meta_data_file_" + today());
        String lowerFile = "大小写检查表（t_case9）.xlsx";
        List<String> lowerNames = new ArrayList<String>();
        String[] lowListed = lowerDir.list();
        if (lowListed != null) {
            for (String n : lowListed) {
                lowerNames.add(n);
            }
        }
        if (!lowerNames.contains(lowerFile)) {
            problems.add("勾「转小写」后标准件目录里的文件不对，应只有 " + lowerFile
                    + "：" + lowerNames);
        }
        String lowGen = named.logPanel().text() + "\n" + frame.sqlPage().logPanel().text();
        if (!lowGen.contains("[命名] 生成物表名与字段名：转小写")) {
            problems.add("小写档的生成日志没写明档位：" + tailOf(lowGen).replace((char) 10, (char) 32));
        }
        if (countIn(frame.sqlPage().logPanel().text(),
                "[命名] 生成物表名与字段名：转大写") != upperLineBefore) {
            problems.add("勾的是小写档，生成日志里却又多写了一遍「转大写」");
        }
        String onDisk = null;
        try {
            onDisk = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(caseScript.getPath())),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            problems.add("读回命名检查脚本失败：" + e);
        }
        if (!caseSql.equals(onDisk)) {
            problems.add("勾大小写开关后脚本正文被改了");
        }
        if (!frame.sqlPage().logPanel().text().contains(
                "[命名] 生成物表名与字段名：转大写")) {
            problems.add("SQL 页生成日志没写明命名口径");
        }
        // UTF-8 正文里本来就带 U+FFFD 时，仍要按 UTF-8 解；不能拿这个合法字符当 GBK 信号
        java.io.File replScript = new java.io.File(dir, "replacement_utf8.sql");
        String replSql = "CREATE TABLE `t_repl` (`id` int NOT NULL COMMENT '\u7f16\u53f7')"
                + " COMMENT='\u7f16\u53f7\u8868 \ufffd';";
        writeText(replScript.getPath(), replSql);
        try {
            String readBack = com.excel2mysql.core.SqlRunner.readScript(replScript);
            com.excel2mysql.core.SqlScript.ParsedTable repl =
                    com.excel2mysql.core.SqlScript.parseCreateTables(readBack).get(0);
            if (!"\u7f16\u53f7".equals(repl.columns.get(0).comment)
                    || !("\u7f16\u53f7\u8868 \ufffd").equals(repl.tableComment)) {
                int at = repl.tableComment.indexOf(0xFFFD);
                String around = at < 0 ? repl.tableComment
                        : repl.tableComment.substring(Math.max(0, at - 3), Math.min(repl.tableComment.length(), at + 3));
                StringBuilder esc = new StringBuilder();
                for (int k = 0; k < around.length(); k++) {
                    esc.append(esc.length() == 0 ? "" : "/").append((int) around.charAt(k));
                }
                problems.add("含 U+FFFD 的 UTF-8 脚本被误判编码，元数据注释变成："
                        + repl.columns.get(0).comment + " / " + esc);
            }
        } catch (Exception e) {
            problems.add("读含 U+FFFD 的 UTF-8 脚本失败：" + e);
        }
        sqlDataExport(problems);
        autoPkSql(problems);
    }

    /** 第二页取数那一份脚本：列定义走 CREATE TABLE，数据行走 INSERT。 */
    private static final String SD_SCRIPT =
            "CREATE TABLE `t_user` (\n"
                    + "  `id` bigint NOT NULL COMMENT '主键',\n"
                    + "  `name` varchar(60) NOT NULL COMMENT '姓名',\n"
                    + "  `score` decimal(8,2) DEFAULT NULL COMMENT '得分',\n"
                    + "  `note` varchar(40) DEFAULT NULL COMMENT '备注',\n"
                    + "  `born` datetime DEFAULT NULL COMMENT '出生时间',\n"
                    + "  PRIMARY KEY (`id`)\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';\n"
                    + "INSERT INTO `t_user` (`id`, `name`, `score`, `note`, `born`) VALUES\n"
                    + "  (1, '\u5f20\u4e09', 12.50, NULL, '2001-01-02 03:04:05'),\n"
                    + "  (2, 'O''Brien(a,b);--\u4e0d\u50cf\u6ce8\u91ca', -3.25e1, "
                    + "'it''s ok', NOW()),\n"
                    + "  (3, '\u674e\u56db\\n\u6362\u884c', 0, '  \u4e24\u5934\u7a7a\u683c  ', "
                    + "'2020-02-02 00:00:00');\n"
                    + "INSERT INTO `t_user` VALUES (4, '\u738b\u4e94', 7, "
                    + "'\u6ca1\u5199\u5217\u6e05\u5355', '2021-03-04 05:06:07');\n"
                    + "REPLACE INTO `t_orphan` (`a`, `b`) VALUES (1, 'x'), (2, 'y');\n";

    /**
     * 脚本里的 INSERT 要真写进「数据」Sheet——与 Python 用例集 SD 组同一份判据。
     *
     * <p>取数器本身由 SQL-DIFF 逐字节对（两栈各一份实现），这一条对的是"落到文件上"：
     * 类型别在写盘那一步走样（12.5 变文本、NULL 变成字符串 "NULL"），
     * 只有 INSERT 没有建表语句的表要点名跳过，不能静默少一张。
     */
    private static void sqlDataExport(List<String> problems) {
        java.io.File root = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-sd-" + System.nanoTime());
        if (!root.mkdirs()) {
            problems.add("取数自检建不出临时目录：" + root.getPath());
            return;
        }
        java.io.File script = new java.io.File(root, "sd_demo.sql");
        writeText(script.getPath(), SD_SCRIPT);
        List<com.excel2mysql.gui.SqlPage.Src> items =
                new ArrayList<com.excel2mysql.gui.SqlPage.Src>();
        items.add(new com.excel2mysql.gui.SqlPage.Src(script.getPath(), SD_SCRIPT));
        Recorder rec = new Recorder();
        com.excel2mysql.gui.SqlPage.GenResult res = com.excel2mysql.gui.SqlPage
                .exportFromScripts(items, root.getPath(), true, false, false, false, rec);
        java.io.File file = new java.io.File(new java.io.File(root,
                "sql_meta_data_file_" + today()), "用户表（t_user）.xlsx");
        if (res.written != 1 || !file.isFile()) {
            problems.add("取数这一路没写出预期的文件：写出 " + res.written + " 个，找 "
                    + file.getPath());
            return;
        }
        try {
            com.excel2mysql.core.MetaExport.Dump data = null;
            for (com.excel2mysql.core.MetaExport.Dump d
                    : com.excel2mysql.core.MetaExport.dump(file.getPath())) {
                if ("数据".equals(d.sheet)) {
                    data = d;
                }
            }
            if (data == null || data.cells.size() != 5) {
                problems.add("「数据」Sheet 不是表头加 4 行："
                        + (data == null ? "没有这一页" : String.valueOf(data.cells.size())));
                return;
            }
            Object score = data.cells.get(1).get(2);
            Object note = data.cells.get(1).get(3);
            Object born = data.cells.get(2).get(4);
            if (!(score instanceof Double) || ((Double) score).doubleValue() != 12.5) {
                problems.add("12.50 没按数落格：" + score);
            }
            if (note != null && !"".equals(note)) {
                problems.add("NULL 那格不该有字：" + note);
            }
            if (!"NOW()".equals(String.valueOf(born))) {
                problems.add("函数值没按原样落文本：" + born);
            }
            if (!String.valueOf(data.cells.get(3).get(1)).contains("\u6362\u884c")) {
                problems.add("转义 \\n 没解成换行：" + String.valueOf(data.cells.get(3).get(1)));
            }
        } catch (Exception e) {
            problems.add("读回取数产物失败：" + e);
        }
        if (!rec.saw("t_orphan", false)) {
            problems.add("只有 INSERT、没有 CREATE TABLE 的表没被点名跳过");
        }
    }

    /**
     * 「自动补全主键」在界面上必须同时看得见三件事：清单那一格、建表语句、数据网格的首列。
     * 任一处漏了都是"界面说补了、产物里没补"的假话；关闭时又要一格不差地收回去。
     */
    private static void autoPkSwitches(MainFrame frame, List<String> problems) {
        javax.swing.JCheckBox apk = frame.autoPkBox();
        javax.swing.JCheckBox sapk = frame.sqlPage().autoPkBox();
        if (apk == null || sapk == null) {
            problems.add("界面上缺少「自动补全主键」开关");
            return;
        }
        if (!"自动补全主键".equals(apk.getText()) || !"自动补全主键".equals(sapk.getText())) {
            problems.add("补主键开关文案不对：" + apk.getText() + " / " + sapk.getText());
            return;
        }
        if (apk.isSelected() || sapk.isSelected()) {
            problems.add("「自动补全主键」默认就该是关的");
        }
        if (!SwingUtilities.isDescendingFrom(apk, frame.excelActionBar())
                || !SwingUtilities.isDescendingFrom(sapk, frame.sqlPage().actionBar())) {
            problems.add("补主键开关没有站在两页的底部动作条里");
        }
        File tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-autopk-selftest").toFile();
            frame.clearAll();
            File a = writeAutoPkFixture(tmp, "无主键表（t_ap_nopk）.xlsx",
                    new String[]{"备注", "说明"},
                    new Object[][] {{"x", "a"}, {"x", "b"}});
            File b = writeAutoPkFixture(tmp, "有主键表（t_ap_has）.xlsx",
                    new String[]{"编号", "名称"},
                    new Object[][] {{"A1", "n1"}, {"A2", "n2"}});
            File c = writeAutoPkFixture(tmp, "同名字段表（t_ap_same）.xlsx",
                    new String[]{"business_id", "名称"},
                    new Object[][] {{"z", "n1"}, {"z", "n2"}});
            frame.addFiles(java.util.Arrays.asList(a.getPath(), b.getPath(), c.getPath()));
            com.excel2mysql.core.TableBuilder.Result rn = resultNamed(frame, "t_ap_nopk");
            com.excel2mysql.core.TableBuilder.Result rh = resultNamed(frame, "t_ap_has");
            com.excel2mysql.core.TableBuilder.Result rs = resultNamed(frame, "t_ap_same");
            if (rn == null || rh == null || rs == null) {
                problems.add("补主键自检的三张夹具表没全加载出来");
                return;
            }
            int before = rn.table.columns.size();
            Object head0 = rn.rows.get(0).get(0);
            boolean hasPk = false;
            for (com.excel2mysql.core.Infer.Column col : rh.table.columns) {
                if (col.primaryKey) {
                    hasPk = true;
                }
            }
            if (!hasPk) {
                problems.add("夹具「有主键表」没被自动选上主键，这条测不到不补那一路");
            }
            frame.autoPkBox().doClick();
            com.excel2mysql.core.Infer.Column auto = rn.table.columns.get(0);
            if (!"business_id".equals(auto.field) || !"VARCHAR(32)".equals(auto.type)
                    || !auto.primaryKey || !Boolean.FALSE.equals(auto.nullable) || !auto.autoPk) {
                problems.add("无主键表没补出 business_id：" + auto.field + " / " + auto.type
                        + " / pk=" + auto.primaryKey + " / nullable=" + auto.nullable);
            }
            if (rn.table.columns.size() != before + 1) {
                problems.add("补列没让字段数 +1：" + before + " → " + rn.table.columns.size());
            }
            String v0 = String.valueOf(rn.rows.get(0).get(0));
            String v1 = String.valueOf(rn.rows.get(1).get(0));
            if (!v0.matches("[0-9a-f]{32}") || !v1.matches("[0-9a-f]{32}") || v0.equals(v1)) {
                problems.add("数据行首格不是两个 32 位十六进制唯一值：" + v0 + " / " + v1);
            }
            if ("business_id".equals(rh.table.columns.get(0).field)
                    || rh.table.columns.get(0).autoPk) {
                problems.add("已有主键的表被补了 business_id");
            }
            if (rs.table.columns.get(0).autoPk) {
                problems.add("表里已经写着 business_id 的还被补了一列");
            }
            int rowIdx = fileRowOf(frame, "t_ap_nopk");
            Object pkCell = rowIdx < 0 ? null : frame.fileTable().getValueAt(rowIdx, 6);
            if (!"business_id".equals(pkCell)) {
                problems.add("清单「主键」那一格没跟着变：" + pkCell);
            }
            String log = frame.logPanel().text();
            if (!log.contains("[提示] 自动补全主键切换为：开（动了 1 张，跳过 2 张，清单里共 3 张表）")) {
                problems.add("补主键切换没留一句汇总痕：" + tailOf(log));
            }
            if (!log.contains("补进去会重名") || !log.contains("已有主键")) {
                problems.add("被跳过的那两张没逐张点名原因");
            }
            if (!frame.ddlText().contains("`business_id` VARCHAR(32) NOT NULL COMMENT '业务主键'")
                    || !frame.ddlText().contains("PRIMARY KEY (`business_id`)")) {
                problems.add("预览 DDL 里没有补出来的那一列与主键约束");
            }
            frame.autoPkBox().doClick();
            // 只看这一张：清单里另一张夹具表本来就带一个 business_id 字段，
            // 拿整页 DDL 判"收回去了"会把那张真字段也算进去
            if (rn.table.columns.size() != before || !head0.equals(rn.rows.get(0).get(0))
                    || rn.table.columns.get(0).autoPk
                    || !"无".equals(frame.fileTable().getValueAt(fileRowOf(frame, "t_ap_nopk"), 6))) {
                problems.add("关闭开关没把补的那一列与逐行值收干净：" + rn.table.columns.size()
                        + " / " + rn.rows.get(0).get(0));
            }
            frame.sqlPage().autoPkBox().doClick();
            if (!frame.sqlPage().logPanel().text().contains(
                    "[提示] 自动补全主键切换为：开（作用于表元数据弹窗、预览 DDL 副本与"
                            + "两条生成出口，脚本正文不动）")) {
                problems.add("第二页补主键切换没留痕");
            }
            frame.sqlPage().autoPkBox().doClick();
            frame.clearAll();
        } catch (Exception e) {
            problems.add("补主键自检没跑完：" + e);
        } finally {
            deleteTree(tmp);
        }
    }

    /** 一行一行的数据夹具：补主键要看得见"每行一个唯一值"，单行夹具测不出来。 */
    private static File writeAutoPkFixture(File dir, String name, String[] headers,
                                           Object[][] rows) throws Exception {
        org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet("数据");
        org.apache.poi.ss.usermodel.Row head = sh.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            head.createCell(i).setCellValue(headers[i]);
        }
        for (int r = 0; r < rows.length; r++) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(r + 1);
            for (int i = 0; i < rows[r].length; i++) {
                if (rows[r][i] instanceof Number) {
                    row.createCell(i).setCellValue(((Number) rows[r][i]).doubleValue());
                } else {
                    row.createCell(i).setCellValue(String.valueOf(rows[r][i]));
                }
            }
        }
        File f = new File(dir, name);
        writeWorkbook(wb, f);
        return f;
    }

    /** 按表名取清单里那一份解析结果（自检往清单里加过别的表，按下标取会拿错）。 */
    private static com.excel2mysql.core.TableBuilder.Result resultNamed(MainFrame frame, String name) {
        for (com.excel2mysql.core.TableBuilder.Result r : frame.results()) {
            if (r != null && r.table != null && name.equals(r.table.tableName)) {
                return r;
            }
        }
        return null;
    }

    private static int fileRowOf(MainFrame frame, String name) {
        javax.swing.JTable t = frame.fileTable();
        for (int i = 0; i < t.getRowCount(); i++) {
            if (name.equals(t.getValueAt(i, 2))) {
                return i;
            }
        }
        return -1;
    }

    /** 第二页三个出口的补列判据：预览副本三态、生成件的元数据与数据行、脚本正文一字不改。 */
    private static void autoPkSql(List<String> problems) {
        com.excel2mysql.core.SqlScript.PkEdit added = com.excel2mysql.core.SqlScript.addAutoPkCreate(
                "CREATE TABLE `t` (\n  `x` int DEFAULT NULL\n) ENGINE=InnoDB;", "business_id");
        if (!"added".equals(added.status)
                || !added.text.contains("`business_id` varchar(32) NOT NULL COMMENT '业务主键'")
                || !added.text.contains("PRIMARY KEY (`business_id`)")) {
            problems.add("预览副本没补出列与约束：" + added.status + " / " + brief(added.text));
        }
        String keptSrc = "CREATE TABLE t (a INT, PRIMARY KEY (a));";
        com.excel2mysql.core.SqlScript.PkEdit kept = com.excel2mysql.core.SqlScript.addAutoPkCreate(keptSrc, "business_id");
        if (!"kept".equals(kept.status) || !keptSrc.equals(kept.text)) {
            problems.add("已有 PRIMARY KEY 的那句被改了：" + kept.status + " / " + brief(kept.text));
        }
        String likeSrc = "CREATE TABLE t LIKE u;";
        com.excel2mysql.core.SqlScript.PkEdit failed = com.excel2mysql.core.SqlScript.addAutoPkCreate(likeSrc, "business_id");
        if (!"failed".equals(failed.status) || !likeSrc.equals(failed.text)
                || failed.reason.isEmpty()) {
            problems.add("补不出形状的没有原样退回并点名：" + failed.status);
        }
        File dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-autopk-sql").toFile();
            String script = "CREATE TABLE `t_ap` (\n"
                    + "  `code` varchar(16) DEFAULT NULL COMMENT '代码',\n"
                    + "  `name` varchar(32) DEFAULT NULL COMMENT '名称'\n"
                    + ") ENGINE=InnoDB COMMENT='补主键检查表';\n"
                    + "INSERT INTO `t_ap` (`code`, `name`) VALUES ('c1', 'n1'), ('c2', 'n2');\n"
                    + "CREATE TABLE `t_pk` (\n"
                    + "  `id` bigint DEFAULT NULL COMMENT '编号',\n"
                    + "  PRIMARY KEY (`id`)\n"
                    + ") ENGINE=InnoDB COMMENT='已有主键表';\n";
            List<com.excel2mysql.gui.SqlPage.Src> items = new ArrayList<com.excel2mysql.gui.SqlPage.Src>();
            items.add(new com.excel2mysql.gui.SqlPage.Src(new File(dir, "ap.sql").getPath(), script));
            Recorder rec = new Recorder();
            com.excel2mysql.gui.SqlPage.GenResult res = com.excel2mysql.gui.SqlPage.exportFromScripts(
                    items, dir.getPath(), true, false, false, true, rec);
            if (res.written != 2) {
                problems.add("补主键的生成件张数不对：" + res.written);
                return;
            }
            if (!rec.saw("本轮补 1 张，1 张已有主键或已有同名字段不动", false)) {
                problems.add("生成出口没写补了几张、几张不动");
            }
            List<com.excel2mysql.core.MetaExport.Dump> dumps = com.excel2mysql.core.MetaExport.dump(
                    new File(new File(dir, "sql_meta_data_file_" + today()),
                            "补主键检查表（t_ap）.xlsx").getPath());
            List<List<Object>> meta = sheetCells(dumps, "元数据");
            List<List<Object>> data = sheetCells(dumps, "数据");
            if (meta == null || meta.size() != 4 || meta.get(1).size() < 7) {
                problems.add("元数据 Sheet 不是表头加两列："
                        + (meta == null ? "(没有这一页)" : meta.size()));
            } else {
                List<Object> first = meta.get(1);
                if (!"business_id".equals(String.valueOf(first.get(1)))
                        || !"业务主键".equals(String.valueOf(first.get(2)))
                        || !"VARCHAR".equals(String.valueOf(first.get(3)))
                        || !"32".equals(String.valueOf(first.get(4)))
                        || !"是".equals(String.valueOf(first.get(5)))
                        || !"否".equals(String.valueOf(first.get(6)))) {
                    problems.add("补出来的那一列在元数据 Sheet 里形状不对：" + first);
                }
            }
            if (data == null || data.size() != 3) {
                problems.add("数据 Sheet 不是表头加两行："
                        + (data == null ? "(没有这一页)" : String.valueOf(data.size())));
            } else {
                String u0 = String.valueOf(data.get(1).get(0));
                String u1 = String.valueOf(data.get(2).get(0));
                if (!"business_id".equals(String.valueOf(data.get(0).get(0)))
                        || !u0.matches("[0-9a-f]{32}") || !u1.matches("[0-9a-f]{32}")
                        || u0.equals(u1)) {
                    problems.add("数据 Sheet 的首列不是两个 32 位十六进制唯一值：" + u0 + " / " + u1);
                }
            }
            List<List<Object>> pkMeta = sheetCells(com.excel2mysql.core.MetaExport.dump(
                    new File(new File(dir, "sql_meta_data_file_" + today()),
                            "已有主键表（t_pk）.xlsx").getPath()), "元数据");
            if (pkMeta != null && pkMeta.toString().contains("business_id")) {
                problems.add("已有主键那张的生成件被补了列");
            }
        } catch (Exception e) {
            problems.add("补主键的生成件读不回来：" + e);
        } finally {
            deleteTree(dir);
        }
    }

    private static List<List<Object>> sheetCells(List<com.excel2mysql.core.MetaExport.Dump> dumps, String sheet) {
        if (dumps == null) {
            return null;
        }
        for (com.excel2mysql.core.MetaExport.Dump d : dumps) {
            if (sheet.equals(d.sheet)) {
                return d.cells;
            }
        }
        return null;
    }

    /**
     * 界面字体必须来自随包那一份文件：客户机器上装了什么字体，不该决定中文看不看得见。
     *
     * <p>2026-11-05 用户在 Win7 专业版上碰到整页中文成空方框——旧写法按英文字面名在系统清单里
     * 挑档，而中文系统枚举出来的是「微软雅黑」（英文名列在另一份 locale 的清单里），挑不中就落到
     * Segoe UI，JDK 8 对实体字族又不做字形回退。现在界面字体是随包的思源黑体
     * （Noto Sans SC，SIL OFL），挑档那套已经删掉；这一条守三件事：资源真在包里、字体真画得出
     * 汉字（字形号非 0，不是 .notdef）、界面各处用的是**同一个 Font 对象**——机器上恰好装着
     * 同名字体时，按族名重新 new 一支就会撞进系统那一支，"内置"就成了假的。
     */
    private static void cjkFontChoice(List<String> problems) {
        if (!UiScale.bundledLoaded()) {
            problems.add("classpath 里没取到随包字体 " + UiScale.FONT_RESOURCE
                    + "，界面退回对话框字体（中文就可能画不出）");
            return;
        }
        java.awt.Font base = UiScale.font(java.awt.Font.PLAIN, 18);
        java.awt.font.FontRenderContext frc =
                new java.awt.font.FontRenderContext(null, true, true);
        int gid = base.createGlyphVector(frc, "中").getGlyphCode(0);
        if (!base.canDisplay('中') || gid == 0) {
            problems.add("随包字体画不出「中」：canDisplay=" + base.canDisplay('中')
                    + " 字形号=" + gid);
        }
        if (!"Noto Sans SC".equals(UiScale.fontFamily())) {
            problems.add("随包字体的族名不是预期的 Noto Sans SC：" + UiScale.fontFamily());
        }
        java.awt.Font ui = UIManager.getFont("Label.font");
        if (ui == null || !ui.getFamily().equals(UiScale.fontFamily())) {
            problems.add("UIManager 上的界面字体不是随包那一支：" + ui);
        }
        if (!com.excel2mysql.gui.Tables.font().getFamily().equals(UiScale.fontFamily())) {
            problems.add("表格字体与界面字体分叉：" + com.excel2mysql.gui.Tables.font());
        }
    }

    /** 报错信息里给一段看得完的原文：null 要说得出口，换行不许把日志劈成两段。 */
    private static String brief(String text) {
        if (text == null) {
            return "(空)";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    private static String tailOf(String text) {
        int from = Math.max(0, text.length() - 300);
        return text.substring(from);
    }

    /** 日志最后一句：判断"刚才那一下有没有说话"用它，别用行数（日志有 2000 行上限）。 */
    private static String lastLine(String text) {
        int nl = text.lastIndexOf('\n');
        return nl < 0 ? text : text.substring(nl + 1);
    }

    /** 按文字找按钮：自检按用户的方式点按钮，才验得到"返回提示 → 弹窗"这段接线。 */
    private static javax.swing.JButton buttonIn(java.awt.Container root, String text) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JButton
                    && text.equals(((javax.swing.JButton) c).getText())) {
                return (javax.swing.JButton) c;
            }
            if (c instanceof java.awt.Container) {
                javax.swing.JButton found = buttonIn((java.awt.Container) c, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * SQL 页双击一行的耗时：读入时整份解析一次并按表存好，点开只取那一张。
     *
     * 现场反馈"双击后要等一下才出元数据"。慢的不是建对话框，是那一刻把整份脚本
     * 重新解析一遍——所以夹具要够大，大到整份解析仍是人眼等得到的一档，
     * 否则下面那条"快一个量级"的判据就成了空判。
     */
    private static void sql_metadata_cache(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-sqlcache").toFile();
        } catch (java.io.IOException e) {
            problems.add("建元数据耗时检查的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        StringBuilder big = new StringBuilder();
        // 夹具与 Python 那一份同形（40 张 × 20~24 列 × 120 条 INSERT）。
        // 不放大表数：200 张那版把后面并发自检的"事件分发线程回应一次"从 200 毫秒顶到
        // 1790 毫秒（100 张顶到 916 毫秒），多出来的是列对象与表格模型，代价落在回收上；
        // 判据本身不需要大夹具——缓存失效时"只取一张"会退化成"整份重解析"，比值当场就红
        for (int t = 0; t < 40; t++) {
            big.append("CREATE TABLE t_big_").append(String.format("%02d", t))
                    .append(" (").append(nl);
            for (int c = 0; c < 20 + t % 5; c++) {
                big.append("  col_").append(String.format("%02d", c))
                        .append(" VARCHAR(64) DEFAULT NULL COMMENT '字段").append(c)
                        .append("',").append(nl);
            }
            big.append("  PRIMARY KEY (col_00)").append(nl)
                    .append(") ENGINE=InnoDB COMMENT='大脚本").append(t).append("';").append(nl);
            for (int r = 0; r < 120; r++) {
                // CREATE 之间夹大量 INSERT，行数是靠它们堆上去的（与 Python 那份同形）
                big.append("INSERT INTO t_big_").append(String.format("%02d", t))
                        .append(" VALUES ('v").append(r).append("');").append(nl);
            }
        }
        java.io.File script = new java.io.File(dir, "big_script.sql");
        writeText(script.getPath(), big.toString());

        com.excel2mysql.gui.SqlPage cache = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        cache.addFiles(paths);

        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> c1 =
                cache.parsedCacheOf(script.getPath());
        if (c1 == null || c1.size() != 40
                || c1.get("t_big_07") == null || c1.get("t_big_07").columns.size() != 22) {
            problems.add("读入大脚本时没把每张表的字段定义都缓存好："
                    + (c1 == null ? "无缓存" : c1.size() + " 张"));
            return;
        }

        // 列集合与「Excel 导入」页对齐：去掉全是 1 的出现次数，换成看得懂的表注释
        StringBuilder cols = new StringBuilder();
        for (int c = 0; c < cache.columnCount(); c++) {
            cols.append(c == 0 ? "" : "|").append(cache.columnName(c));
        }
        if (!"序号|文件|原表名|当前表名|表注释|数据行数".equals(cols.toString())) {
            problems.add("SQL 页的列不是 序号|文件|原表名|当前表名|表注释|数据行数：" + cols);
        }
        if (!"大脚本7".equals(cache.sqlRowComment(7))) {
            problems.add("导入脚本后表注释没显示在清单里：" + cache.sqlRowComment(7));
        }
        StringBuilder edit = new StringBuilder();
        for (int c = 0; c < cache.columnCount(); c++) {
            edit.append(c == 0 ? "" : ",").append(cache.cellEditable(7, c));
        }
        if (!"false,false,false,true,true,false".equals(edit.toString())) {
            problems.add("SQL 页可编辑的列不对（应是「当前表名」与「表注释」）：" + edit);
        }
        // 「数据行数」列：大脚本里 t_big_07 有 120 条 INSERT，必须显示成 120
        String rowCountCell = cache.sqlRowRowCount(7);
        if (!"120".equals(rowCountCell)) {
            problems.add("SQL 页的「数据行数」列不对（t_big_07 应是 120）：" + rowCountCell);
        }
        if (!cache.statRowsButton().isEnabled()) {
            problems.add("SQL 页的「统计数据总行数」按钮不可用");
        }

        long full0 = System.nanoTime();
        com.excel2mysql.core.SqlScript.parseCreateTables(cache.textOf(script.getPath()));
        double fullMs = (System.nanoTime() - full0) / 1000000.0;
        cache.metadataAtRow(7);   // 先走一次把这条路径预热，免得把 JIT 的账算成缓存没生效
        long look0 = System.nanoTime();
        com.excel2mysql.core.SqlScript.ParsedTable looked = cache.metadataAtRow(7);
        double lookMs = (System.nanoTime() - look0) / 1000000.0;
        long open0 = System.nanoTime();
        cache.showMetadata(7);
        double openMs = (System.nanoTime() - open0) / 1000000.0;
        // 字段表左上也要有标识，标题里带列数（与两条主表格同一口径）
        String colTitle = metadataColumnTitle();
        closeMetadataDialogs();
        if (!"字段清单（共 22 列）".equals(colTitle)) {
            problems.add("元数据弹窗的字段表没有带列数的标识：" + colTitle);
        }
        System.out.println("- [INFO] GUI-SELFTEST | 元数据耗时：整份重解析 "
                + String.format("%.0f", fullMs) + " 毫秒 / 只取一张 "
                + String.format("%.2f", lookMs) + " 毫秒 / 含建对话框 "
                + String.format("%.1f", openMs) + " 毫秒");
        if (fullMs < 1.0) {
            problems.add("整份重解析测不出耗时（" + String.format("%.2f", fullMs)
                    + " 毫秒），比值判据成了拿 0 除 0");
        }
        if (looked == null || looked.columns.size() != 22) {
            problems.add("双击一行取到的字段定义不对："
                    + (looked == null ? "无表" : looked.columns.size() + " 列"));
        }
        // 缓存失效时这条路会退化成整份重解析，比值当场就红，不需要另外设夹具大小下限
        if (lookMs * 10 >= fullMs) {
            problems.add("缓存没起作用：只取一张要 " + String.format("%.2f", lookMs)
                    + " 毫秒，整份重解析才 " + String.format("%.0f", fullMs) + " 毫秒");
        }
        // 建对话框本身的开销躲不掉（Swing 建窗口 + 排 22 行），这里只管"人感知不到"那一档
        if (openMs >= 300.0) {
            problems.add("双击一行到对话框出来要 " + String.format("%.1f", openMs)
                    + " 毫秒，人已经等得出了");
        }

        cache.setSqlRowName(7, "t_big_renamed");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> c2 =
                cache.parsedCacheOf(script.getPath());
        if (c2 == null || !c2.containsKey("t_big_renamed") || c2.containsKey("t_big_07")
                || !"t_big_renamed".equals(c2.get("t_big_renamed").tableName)) {
            problems.add("改表名没把缓存里那一格一起改名");
        }
        long open2 = System.nanoTime();
        com.excel2mysql.core.SqlScript.ParsedTable after = cache.metadataAtRow(7);
        double open2Ms = (System.nanoTime() - open2) / 1000000.0;
        if (after == null || after.columns.size() != 22 || open2Ms >= 100.0) {
            problems.add("改名后点开不是毫秒级或字段少了：" + String.format("%.1f", open2Ms)
                    + " 毫秒 / " + (after == null ? "无" : after.columns.size()) + " 列");
        }

        // 撞车：新名和另一张表同名。缓存留着可用（刷新清单要读它，丢了就等于把整份
        // 重解析搬回事件分发线程），同名两行并成一行，注释与字段都取先出现那一张
        cache.setSqlRowName(0, "t_big_01");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> c3 =
                cache.parsedCacheOf(script.getPath());
        if (c3 == null || c3.size() != 39 || c3.containsKey("t_big_00")) {
            problems.add("改成同名表后缓存不对：" + (c3 == null ? "被丢掉" : c3.size() + " 键"));
        }
        com.excel2mysql.core.SqlScript.ParsedTable hit0 = cache.metadataAtRow(0);
        if (hit0 == null || hit0.columns.size() != 20 || cache.rowCount() != 39
                || !"t_big_01".equals(cache.sqlRowActualName(0))
                || !"大脚本0".equals(cache.sqlRowComment(0))) {
            problems.add("撞名后没并成一行、或注释与字段没取先出现那一张："
                    + cache.rowCount() + " 行 / " + cache.sqlRowComment(0));
        }
        long open3 = System.nanoTime();
        cache.metadataAtRow(0);
        if ((System.nanoTime() - open3) / 1000000.0 >= 100.0) {
            problems.add("撞名后同一行再点开还是慢");
        }

        // 一次改多张表：每张各改各的键，不做链式二次改名
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> seed =
                new java.util.LinkedHashMap<String, com.excel2mysql.core.SqlScript.ParsedTable>();
        seed.put("a", new com.excel2mysql.core.SqlScript.ParsedTable());
        seed.get("a").tableName = "a";
        seed.put("b", new com.excel2mysql.core.SqlScript.ParsedTable());
        seed.get("b").tableName = "b";
        java.util.Map<String, String> two = new java.util.LinkedHashMap<String, String>();
        two.put("a", "x");
        two.put("b", "y");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> rk =
                com.excel2mysql.gui.SqlPage.rekeyParsed(seed, two);
        if (rk == null || rk.size() != 2 || !rk.containsKey("x") || !rk.containsKey("y")
                || !"y".equals(rk.get("y").tableName)) {
            problems.add("一次改多张表的缓存重挂不对：" + (rk == null ? "被丢掉" : rk.keySet()));
        }
        java.util.Map<String, String> miss = new java.util.LinkedHashMap<String, String>();
        miss.put("c", "d");
        java.util.Map<String, String> clash = new java.util.LinkedHashMap<String, String>();
        clash.put("a", "b");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> kept =
                com.excel2mysql.gui.SqlPage.rekeyParsed(seed, clash);
        if (com.excel2mysql.gui.SqlPage.rekeyParsed(seed, miss) != null
                || com.excel2mysql.gui.SqlPage.rekeyParsed(null, two) != null) {
            problems.add("要改的名字不在缓存里、或本就没缓存，其中有一种没被判成\"丢掉整份\"");
        }
        if (kept == null || kept.size() != 1 || !kept.containsKey("b")
                || kept.get("b") != seed.get("a")) {
            problems.add("新键撞车时没留先出现那一张，或整份被误丢掉："
                    + (kept == null ? "null" : kept.keySet()));
        }
    }

    /** 当下开着的元数据框清单：自检要分辨"我刚开的那一个"，早前阶段可能还留着别的。 */
    private static List<java.awt.Window> metadataWindows() {
        List<java.awt.Window> out = new ArrayList<java.awt.Window>();
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof com.excel2mysql.gui.SqlMetadataDialog) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * 元数据弹窗改字段：字段名 / 字段注释 / 数据类型三列要能改，且改的是脚本正文那一句
     * CREATE TABLE 里的那一列（与 Python 侧 E15~E21、R69~R72b 同一批形状）。
     *
     * 被主键或索引名单引用的字段必须拒绝改名——放过去就是一句引用不存在列的 DDL。
     */
    private static void sql_field_edit(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-fedit").toFile();
        } catch (java.io.IOException e) {
            problems.add("改字段检查的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        StringBuilder wide = new StringBuilder("zhe_shi_yi_ge_chao_ji_chang_de_zi_duan_ming");
        while (wide.length() < 120) {
            wide.append("_hai_you_geng_chang");
        }
        java.io.File script = new java.io.File(dir, "改字段.sql");
        writeText(script.getPath(),
                "CREATE TABLE `t_fe1` (" + nl
                        + "  `id` INT NOT NULL COMMENT '主键'," + nl
                        + "  `amount` DECIMAL(10,2) DEFAULT '0.00' COMMENT '金额'," + nl
                        + "  `name` VARCHAR(64) NOT NULL," + nl
                        + "  `" + wide + "` VARCHAR(255) DEFAULT '' COMMENT '" + wide + "'," + nl
                        + "  PRIMARY KEY (`id`)," + nl
                        + "  KEY `idx_name` (`name`)" + nl
                        + ") ENGINE=InnoDB COMMENT='演示表';" + nl);
        com.excel2mysql.gui.SqlPage page = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        page.addFiles(paths);
        List<java.awt.Window> before = metadataWindows();
        page.openMetadata(0);
        com.excel2mysql.gui.SqlMetadataDialog dlg = null;
        for (java.awt.Window w : metadataWindows()) {
            if (!before.contains(w)) {
                dlg = (com.excel2mysql.gui.SqlMetadataDialog) w;
            }
        }
        if (dlg == null) {
            problems.add("元数据弹窗没开出来，改字段的检查全部做不成");
            return;
        }
        if (!dlg.scrollsHorizontally()) {
            problems.add("元数据弹窗的字段表还开着列自适应：注释放不下时没得横向滚");
        }
        // 脚本里塞了一个 120 字符的字段名与同样的注释：列宽写死时这一格必被切
        dlg.pack();
        for (int c = 0; c < 3; c++) {
            if (!dlg.columnFitsContent(c)) {
                problems.add("元数据弹窗第 " + c + " 列摆出来的宽度装不下内容："
                        + "超长字段名被切进省略号，滚也滚不回来");
            }
        }
        if (dlg.columnTotal() != 5
                || !"数据类型".equals(dlg.headerAt(2)) || !"主键".equals(dlg.headerAt(3))
                || !"可空".equals(dlg.headerAt(4))) {
            problems.add("元数据弹窗还是把类型与长度拆成两列：" + dlg.headerAt(2) + " / "
                    + dlg.headerAt(3) + " / " + dlg.headerAt(4));
        }
        if (!"DECIMAL(10,2)".equals(dlg.cellValue(1, 2))) {
            problems.add("数据类型一列没写成完整形状：" + dlg.cellValue(1, 2));
        }
        if (!dlg.cellEditable(1, 0) || !dlg.cellEditable(1, 1) || !dlg.cellEditable(1, 2)
                || dlg.cellEditable(1, 3) || dlg.cellEditable(1, 4)) {
            problems.add("元数据弹窗的可编辑列不对（应前三列可改，主键与可空只读）");
        }
        dlg.editCell(1, 1, "金额（元）");
        String text = page.textOf(script.getPath());
        if (!text.contains("COMMENT '金额（元）'") || !page.isModified(script.getPath())
                || !page.logPanel().contains("[字段改写]")) {
            problems.add("改字段注释没写进脚本正文或没留回执：" + firstLine(text));
        }
        dlg.editCell(2, 2, "VARCHAR(128)");
        text = page.textOf(script.getPath());
        if (!text.contains("`name` VARCHAR(128) NOT NULL")) {
            problems.add("改数据类型没只换类型词：" + text.replaceAll("\\s+", " "));
        }
        dlg.editCell(1, 0, "money");
        text = page.textOf(script.getPath());
        if (!text.contains("`money` DECIMAL(10,2)") || text.contains("`amount`")) {
            problems.add("改字段名没只换那一行的标识符：" + secondLine(text));
        }
        // 被 PRIMARY KEY 引用的字段改名：不再拒绝，而是连带把引用一起改写
        // （与 Python 侧 E19 同一口径：改完 PRIMARY KEY (`pk`)，并在回执里说清改了几处引用）
        text = page.textOf(script.getPath());
        dlg.editCell(0, 0, "pk");
        text = page.textOf(script.getPath());
        if (!text.contains("PRIMARY KEY (`pk`)") || text.contains("PRIMARY KEY (`id`)")) {
            problems.add("被主键引用的字段改名没连带改写主键引用："
                    + text.replaceAll("\\s+", " "));
        }
        if (!"pk".equals(dlg.cellValue(0, 0))) {
            problems.add("被引用字段改名成功后那一格没显示新名字：" + dlg.cellValue(0, 0));
        }
        if (!page.logPanel().contains("引用")) {
            problems.add("连带改写了引用却没在回执里说清");
        }
        closeMetadataDialogs();
        // 脚本里有四列（最后一列是故意拉长的字段名，用来量列宽够不够装下内容）
        if (page.metadataAtRow(0) == null || page.metadataAtRow(0).columns.size() != 4) {
            problems.add("改完字段后解析缓存不对");
        } else if (!"money".equals(page.metadataAtRow(0).columns.get(1).field)) {
            problems.add("解析缓存没跟着改动走："
                    + page.metadataAtRow(0).columns.get(1).field);
        }
        // 内容装得下时列宽必须铺满视口（与结构弹窗同一档）：上面那张表是故意撑爆的，
        // 量的是"切没切"，铺满得另起一张小表才量得出来
        java.io.File small = new java.io.File(dir, "小表.sql");
        writeText(small.getPath(), "CREATE TABLE `t_small` (" + nl
                + "  `id` INT NOT NULL COMMENT '主键'," + nl
                + "  `name` VARCHAR(32) COMMENT '名称'," + nl
                + "  PRIMARY KEY (`id`)" + nl
                + ") ENGINE=InnoDB;" + nl);
        java.util.List<java.awt.Window> beforeSmall = metadataWindows();
        List<String> smallPaths = new ArrayList<String>();
        smallPaths.add(small.getPath());
        page.addFiles(smallPaths);
        page.openMetadata(1);
        com.excel2mysql.gui.SqlMetadataDialog smallDlg = null;
        for (java.awt.Window w : metadataWindows()) {
            if (!beforeSmall.contains(w)) {
                smallDlg = (com.excel2mysql.gui.SqlMetadataDialog) w;
            }
        }
        if (smallDlg == null) {
            problems.add("小表元数据弹窗没开出来，铺满视口那条检查做不成");
        } else {
            smallDlg.pack();
            if (!smallDlg.columnsFillViewport()) {
                problems.add("元数据弹窗内容装得下时没铺满视口：表格右侧留着一条白");
            }
            // 增删字段（用户 2026-11-09 第 2 条）：改的是脚本正文那句 CREATE TABLE，
            // 弹窗行数、标题里的列数、解析缓存三处必须同时跟上
            if (!smallDlg.hasColumnOps() || smallDlg.fieldCount() != 2) {
                problems.add("元数据弹窗没接上增删字段（或字段数不对）："
                        + smallDlg.hasColumnOps() + " / " + smallDlg.fieldCount());
            }
            smallDlg.pressAddForTest();
            if (smallDlg.fieldCount() != 3
                    || !page.textOf(small.getPath()).contains("is_new_field")) {
                problems.add("「新增字段」没把占位列写进脚本：" + smallDlg.fieldCount() + " 列");
            }
            if (!smallDlg.columnTitle().contains("共 3 列")) {
                problems.add("增完字段标题里的列数没跟着换：" + smallDlg.columnTitle());
            }
            smallDlg.pressDeleteForTest(1);          // 删掉 `name` 那一列
            String afterDel = page.textOf(small.getPath());
            if (smallDlg.fieldCount() != 2 || afterDel.contains("`name`")
                    || !afterDel.contains("PRIMARY KEY (`id`)")) {
                problems.add("「删除选中字段」没只删那一列（主键引用不该被殃及）："
                        + afterDel.replaceAll("\\s+", " "));
            }
            if (!page.logPanel().contains("[字段]") || !page.isModified(small.getPath())) {
                problems.add("增删字段没留回执，或没把这份脚本标成已改动");
            }
            smallDlg.dispose();
        }
        // 表格下方那三颗（清空 / 生成表结构预览 / 预览DDL）：预览按表分页，
        // 且预览只是算一份副本，不许把还没落地的改动定死
        java.util.List<String[]> previewPages = page.ddlPages();
        boolean shaped = !previewPages.isEmpty();
        for (String[] pg : previewPages) {
            shaped = shaped && pg[1].contains("CREATE TABLE") && pg[1].trim().endsWith(";");
        }
        if (!shaped) {
            problems.add("「预览 DDL」按表分页的正文形状不对：" + previewPages.size() + " 页");
        }
        int beforeRows = page.sqlRowCount();
        String beforeText = page.entryTextAt(0);
        // 一行到底的建表语句排在预览里应当缩进成"一个字段一行"：这条先按纯函数定住形状，
        // 再比弹窗显示的正文确实就是格式化器那一份（不新建窗口，多起一个 JFrame 曾把 JVM 顶到 native OOM）
        String flatDdl = com.excel2mysql.core.SqlScript.formatSql(
                "CREATE TABLE `t_a` (`id` BIGINT NOT NULL COMMENT '主键',"
                        + " PRIMARY KEY (`id`)) ENGINE=InnoDB;");
        if (!flatDdl.contains("\n  `id` BIGINT")) {
            problems.add("一行到底的建表语句排完没缩进成一个字段一行：" + flatDdl);
        }
        com.excel2mysql.gui.SqlScriptDdlDialog ddlDlg =
                new com.excel2mysql.gui.SqlScriptDdlDialog(null, previewPages);
        ddlDlg.pack();
        if (ddlDlg.tabCount() != previewPages.size()
                || !ddlDlg.tabText(0).equals(
                        com.excel2mysql.core.SqlScript.formatSql(
                                com.excel2mysql.gui.RichText.normalizeNewlines(
                                        previewPages.get(0)[1])))
                || !ddlDlg.tabTitleAt(0).equals(previewPages.get(0)[0])) {
            problems.add("预览DDL 弹窗的页数、标题或正文与数据对不上（正文应是格式化器排出来的那一份）："
                    + ddlDlg.tabCount() + " 页 / 应有 " + previewPages.size() + " 页");
        }
        // 排的是显示，脚本正文一个字没动——这件事界面上必须当面写出来
        if (!com.excel2mysql.gui.SqlScriptDdlDialog.NOTE.equals(ddlDlg.noteText())) {
            problems.add("脚本页的 DDL 预览没写明「排的是显示、脚本正文没改」："
                    + ddlDlg.noteText());
        }
        // 这一页的正文本来就是脚本自己那句，上色只许改字重与颜色，不许动内容
        String scriptSig = ddlDlg.tabSignature(0);
        if (!scriptSig.contains("L") || !scriptSig.contains("B")
                || !scriptSig.contains("W")) {
            problems.add("脚本页的 DDL 预览角色分布不对（字段名/类型/关键字都得有）："
                    + scriptSig);
        }
        if (inkOf(ddlDlg.tabView(0), com.excel2mysql.gui.RichText.LABEL_FG) <= 0) {
            problems.add("脚本页的 DDL 预览里字段名那支分组蓝一个像素都没画出来");
        }
        ddlDlg.dispose();
        page.previewDdl();
        page.previewStructure();
        closeMetadataDialogs();
        if (page.sqlRowCount() != beforeRows || !beforeText.equals(page.entryTextAt(0))) {
            problems.add("两颗预览按钮把清单或脚本正文改了：预览应当只读不算");
        }
        sqlDdlPagesScale(frame, problems);
    }

    /**
     * 按钮区按选中的行生效（用户 2026-11-09 第 3 条）：选中一行就只动那一行，
     * 一个都没选仍是全量。范围小字、DDL 页数、执行副本、统计、前缀五处走同一套口径。
     */
    private static void sql_scope(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-sqlscope").toFile();
        } catch (java.io.IOException e) {
            problems.add("建范围自检的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        String one = new java.io.File(dir, "s1.sql").getPath();
        writeText(one, "CREATE TABLE t_ra (id INT COMMENT '一号');" + nl
                + "INSERT INTO `t_ra` VALUES (1),(2);" + nl
                + "CREATE TABLE t_rb (id INT COMMENT '二号');" + nl
                + "INSERT INTO `t_rb` VALUES (7);" + nl);
        String two = new java.io.File(dir, "s2.sql").getPath();
        writeText(two, "CREATE TABLE t_rc (id INT);" + nl);
        com.excel2mysql.gui.SqlPage page = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(one);
        paths.add(two);
        page.addFiles(paths);
        if (page.sqlRowCount() != 3) {
            problems.add("范围自检需要 3 张表，实际 " + page.sqlRowCount());
            return;
        }
        // 没选中 = 全量：小字、范围本体、DDL 页数三处必须一致
        if (!"范围：全量（3 张表）".equals(page.scopeHintText())) {
            problems.add("没选中行时动作条那句范围小字不对：" + page.scopeHintText());
        }
        if (page.currentScope().names != null) {
            problems.add("没选中行时作用范围却是收窄的");
        }
        if (page.ddlPages(page.currentScope()).size() != 3) {
            problems.add("没选中行时「预览 DDL」的页数不是全量");
        }
        // 选中第二行（t_rb）
        page.selectRow(1);
        if (!"范围：选中 1 张表".equals(page.scopeHintText())) {
            problems.add("选中一行后那句范围小字没跟上：" + page.scopeHintText());
        }
        List<String[]> picked = page.ddlPages(page.currentScope());
        if (picked.size() != 1 || !"t_rb".equals(picked.get(0)[0])) {
            problems.add("选中一行后「预览 DDL」没只出那一页：" + picked.size() + " 页");
        }
        // 执行副本同样收窄：只留 t_rb 的语句，t_ra 与 s2.sql 一概不带走
        List<String[]> dropped = new ArrayList<String[]>();
        List<com.excel2mysql.gui.SqlPage.Src> run =
                page.prepareRunItems(page.currentScope(), false, dropped);
        if (run.size() != 1 || !run.get(0).text.contains("t_rb")
                || run.get(0).text.contains("t_ra")) {
            problems.add("选中一行后执行副本没收窄：" + run.size() + " 个文件");
        }
        // 统计只算选中的那一张：t_rb 有 1 行数据
        page.statTotalRows();
        if (!frame.lastDialogText().contains("共 1 张表")
                || !frame.lastDialogText().contains("本次只作用于选中的 1 张表")) {
            problems.add("选中一行后「统计数据总行数」还是全量口径："
                    + frame.lastDialogText().replace((char) 10, ' '));
        }
        // 前缀按钮：加了前缀只落在选中的那一行，脚本正文一个字不改
        page.setPrefixText("dim_");
        page.applyPrefix();
        if (!"dim_t_rb".equals(page.sqlRowDisplayName(1))
                || !"t_ra".equals(page.sqlRowDisplayName(0))
                || !page.textOf(one).contains("CREATE TABLE t_rb")) {
            problems.add("按选中行加前缀落错了行：" + page.sqlRowDisplayName(0)
                    + " / " + page.sqlRowDisplayName(1));
        }
        if (!page.logPanel().contains("[范围] 本次只作用于选中的 1 张表，没选中的一概不动")) {
            problems.add("按选中行生效没有在日志里写明范围");
        }
        // 空前缀再点：只撤选中行的显示层，其余行保持原样
        page.setPrefixText("");
        page.applyPrefix();
        if (!"t_ra".equals(page.sqlRowDisplayName(0))
                || !"t_rb".equals(page.sqlRowDisplayName(1))) {
            problems.add("空前缀只该撤选中行的前缀：" + page.sqlRowDisplayName(1));
        }
        page.clearRowSelection();
        if (!page.scopeHintText().startsWith("范围：全量")) {
            problems.add("清掉选中行后范围小字没回到全量：" + page.scopeHintText());
        }
    }

    /**
     * 「预览 DDL」算页的算法成本：整份脚本只能切一遍。
     * 200 张表的整库 dump 以前按表数重扫整份文件，点一次界面卡十几秒
     * （用户 2026-11-02 报"页面卡死"，与 Python 侧 R45s3 同一条）。
     */
    private static void sqlDdlPagesScale(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-ddlscale").toFile();
        } catch (java.io.IOException e) {
            problems.add("建预览成本检查的临时目录失败：" + e);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append("CREATE TABLE `t_scale_").append(String.format("%03d", i))
                    .append("` (`id` INT NOT NULL, `code` VARCHAR(64), PRIMARY KEY (`id`));\n");
        }
        java.io.File script = new java.io.File(dir, "整库脚本.sql");
        writeText(script.getPath(), sb.toString());
        com.excel2mysql.gui.SqlPage spage = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        spage.addFiles(paths);
        long t0 = System.currentTimeMillis();
        int pages = spage.ddlPages().size();
        long cost = System.currentTimeMillis() - t0;
        System.out.println("- [INFO] GUI-SELFTEST | 预览DDL 算页 600 表耗时 " + cost + " 毫秒");
        if (pages != 600) {
            problems.add("预览成本检查这一页只算出 " + pages + " 页，应有 600 页");
        }
        if (cost > 2000) {
            problems.add("600 张表的整库脚本算页要 " + cost
                    + " 毫秒（上限 2000）：整份脚本被按表数重扫了，点「预览 DDL」界面就是卡死");
        }
    }

    /** 关掉自检期间弹出的元数据框：不关掉会攒出一堆隐藏窗口，后面的截图就不是现场。 */
    private static void closeMetadataDialogs() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof com.excel2mysql.gui.SqlMetadataDialog) {
                w.dispose();
            }
        }
    }

    /** 当前开着的那个元数据框里，字段表左上那句标识（没有就返回空串）。 */
    private static String metadataColumnTitle() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof com.excel2mysql.gui.SqlMetadataDialog) {
                return ((com.excel2mysql.gui.SqlMetadataDialog) w).columnTitle();
            }
        }
        return "";
    }

    /**
     * 表注释手动改：双击那一格要写回那句 CREATE TABLE 的表选项，
     * 一路带到元数据弹窗、清单与标准件文件名（两条线各自实现了一遍这套文本改写）。
     */
    private static void sql_comment_edit(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-ccomment").toFile();
        } catch (java.io.IOException e) {
            problems.add("建表注释改写检查的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        java.io.File script = new java.io.File(dir, "注释改写.sql");
        writeText(script.getPath(),
                "CREATE TABLE `t_cm1` (`id` INT) ENGINE=InnoDB COMMENT='原注释';" + nl
                        + "CREATE TABLE t_cm2 (`id` INT);" + nl
                        + "CREATE TABLE t_cm3 LIKE t_cm2;" + nl);
        com.excel2mysql.gui.SqlPage cpage = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        cpage.addFiles(paths);
        if (!"原注释".equals(cpage.sqlRowComment(0))
                || !cpage.sqlRowComment(1).isEmpty() || !cpage.sqlRowComment(2).isEmpty()) {
            problems.add("三种写法的表注释没读到位：" + cpage.sqlRowComment(0) + " / "
                    + cpage.sqlRowComment(1) + " / " + cpage.sqlRowComment(2));
        }

        cpage.setSqlRowComment(0, "改过的注释");
        String text = cpage.textOf(script.getPath());
        if (!text.contains("COMMENT='改过的注释'") || text.contains("COMMENT='原注释'")
                || !cpage.isModified(script.getPath())
                || !"改过的注释".equals(cpage.sqlRowComment(0))) {
            problems.add("改表注释没写进那句 CREATE TABLE 的表选项：" + firstLine(text));
        }
        com.excel2mysql.core.SqlScript.ParsedTable cm1 = cpage.metadataAtRow(0);
        if (cm1 == null || !"改过的注释".equals(cm1.tableComment)) {
            problems.add("元数据弹窗读的还不是改过的注释："
                    + (cm1 == null ? "无表" : cm1.tableComment));
        }
        if (!cpage.logPanel().contains("[表注释] t_cm1 → 改过的注释（已写进脚本；未落盘）")) {
            problems.add("改表注释没在日志里留回执：" + tailOf(cpage.logPanel().text()));
        }

        cpage.setSqlRowComment(1, "补上的注释");
        text = cpage.textOf(script.getPath());
        if (!text.contains("CREATE TABLE t_cm2 (`id` INT) COMMENT='补上的注释';")) {
            problems.add("原本没有 COMMENT 的没在右括号后补上：" + secondLine(text));
        }
        cpage.setSqlRowComment(0, "带'单引号'的");
        text = cpage.textOf(script.getPath());
        if (!text.contains("COMMENT='带''单引号''的'")) {
            problems.add("注释里的单引号没按 MySQL 口径翻倍：" + firstLine(text));
        }
        if (cpage.metadataAtRow(0) == null
                || !"带'单引号'的".equals(cpage.metadataAtRow(0).tableComment)) {
            problems.add("翻倍写法读回来不是原话：" + cpage.sqlRowComment(0));
        }
        cpage.setSqlRowComment(0, "");
        text = cpage.textOf(script.getPath());
        if (!text.contains("COMMENT=''") || !cpage.sqlRowComment(0).isEmpty()) {
            problems.add("清空注释没写成 COMMENT='' 或清单没跟着变：" + firstLine(text));
        }
        if (!cpage.logPanel().contains("[表注释] t_cm1 → （空）（已写进脚本；未落盘）")) {
            problems.add("清空注释的回执没写清是空：" + tailOf(cpage.logPanel().text()));
        }

        String beforeLike = text;
        cpage.setSqlRowComment(2, "LIKE 那句改不动");
        if (!beforeLike.equals(cpage.textOf(script.getPath()))
                || !frame.lastDialogText().contains("没有可改写的表选项")
                || cpage.logPanel().contains("[表注释] t_cm3")) {
            problems.add("LIKE 写法该被拦下并说清原因，实际正文：" + tailOf(
                    cpage.textOf(script.getPath())));
        }
        // 只多打了一个空格：内容其实没变，但这一条也必须有回执，且刷回清单时把空格去掉
        cpage.setSqlRowComment(1, "补上的注释 ");
        if (!cpage.logPanel().contains("[表注释] t_cm2 未发生变化，脚本未改写")
                || !"补上的注释".equals(cpage.sqlRowComment(1))) {
            problems.add("填回一模一样的注释没有任何回执，或空格没被刷掉："
                    + cpage.sqlRowComment(1));
        }
    }

    private static String firstLine(String text) {
        int at = text.indexOf((char) 10);
        return at < 0 ? text : text.substring(0, at);
    }

    private static String secondLine(String text) {
        String[] lines = text.split(String.valueOf((char) 10));
        return lines.length > 1 ? lines[1] : "";
    }

    /**
     * 首屏分栏：日志区默认压到它自己的最小高度，余量按 3:2 给表格（用户口径"日志低、表格高"）。
     * 离屏构造时窗口还没真布局，先按生产窗口尺寸排一次，再量日志段是不是落在最小高度。
     */
    private static void split_defaults(MainFrame frame, List<String> problems) {
        if (Math.abs(frame.excelLowerResizeWeight() - 0.6) > 0.001) {
            problems.add("第一页分栏拉伸比例不对，应为 3:2：" + frame.excelLowerResizeWeight());
        }
        frame.setSize(wantPx(1100), wantPx(780));
        frame.validate();
        frame.applySplitProportions();
        frame.validate();
        if (!"1".equals(frame.logAtMin())) {
            problems.add("第一页日志默认没有压到最小高度：高 "
                    + frame.logBoxHeight() + " / 最小 " + frame.logBoxMinHeight());
        }
    }

    /**
     * 第四页日志区：读源库一次会写几百行，日志段不能跟着内容长高，也不该在没写字时白占半屏
     * （用户报「日志撑满整个工具」）。做法与第一、二、三页一致——日志在一条可拖分隔条下方，
     * 默认只占它自己的最小行数（4 行）。这里先量未写字时的高度，再灌 400 行量一次，
     * 两次数值必须一样，且都得压在最小高度那一档。
     */
    private static void layer_log_bounded(MainFrame frame, com.excel2mysql.gui.LayerPage lp,
                                          List<String> problems) {
        frame.selectTab(3);
        // 与生产同一条摆窗口径：setSize 管的是外框，会把后面"客户区 1650x1170"那条压红
        com.excel2mysql.gui.Windows.clientSize(frame.getContentPane(), frame, 1100, 780);
        frame.validate();
        int quiet = lp.logSplit().getBottomComponent().getHeight();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("+ [OK] LAYER | 表 t_").append(i)
                    .append(" → 领域「工程类」/「建设管理」\n");
        }
        lp.logPanel().setText(sb.toString());
        frame.validate();
        int loaded = lp.logSplit().getBottomComponent().getHeight();
        int table = lp.logSplit().getTopComponent().getHeight();
        if (table <= 0) {
            problems.add("第四页日志过百行后清单区被压没了：高 " + table);
        } else if (loaded >= table) {
            problems.add("第四页日志段比清单还高，日志撑满了这一页：" + loaded + " vs " + table);
        }
        if (Math.abs(loaded - quiet) > 2) {
            problems.add("第四页日志段跟着内容长高：" + quiet + " → " + loaded);
        }
        // 下限只留这排按钮与 4 行日志：写死 200px 或跟着内容涨都会越过这一档
        if (loaded > wantPx(120)) {
            problems.add("第四页日志段白占版面（默认应只有 4 行）：高 " + loaded);
        }
        frame.selectTab(0);
        frame.validate();
    }

    /**
     * 第五页日志区同理：清单 / DDL 正文 / 日志三段都在可拖分隔条里，日志段默认只有 4 行。
     * 之前它裸挂在 SOUTH 上没有滚动条，读一次达梦结构（几百张表）日志会一路撑到上万像素，
     * 把清单和正文全挤走——这是用户报的「撑满整个工具」的原始形态。
     */
    private static void dm_log_bounded(MainFrame frame, com.excel2mysql.gui.DmPage dp,
                                       List<String> problems) {
        frame.selectTab(4);
        com.excel2mysql.gui.Windows.clientSize(frame.getContentPane(), frame, 1100, 780);
        frame.validate();
        int quiet = dp.logSplit().getBottomComponent().getHeight();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("+ [OK] DM | 第 ").append(i).append(" 张表 T_OIL_").append(i).append('\n');
        }
        dp.logPanel().setText(sb.toString());
        frame.validate();
        int loaded = dp.logSplit().getBottomComponent().getHeight();
        int table = dp.logSplit().getTopComponent().getHeight();
        if (table <= 0) {
            problems.add("第五页日志过百行后 DDL 正文被压没了：高 " + table);
        }
        if (Math.abs(loaded - quiet) > 2) {
            problems.add("第五页日志段跟着内容长高：" + quiet + " → " + loaded);
        }
        if (loaded > wantPx(120)) {
            problems.add("第五页日志段白占版面（默认应只有 4 行）：高 " + loaded);
        }
        if (dp.logSplit().getDividerSize() != wantPx(6)) {
            problems.add("第五页日志分隔条不是 6px：" + dp.logSplit().getDividerSize());
        }
        frame.selectTab(0);
        frame.validate();
    }

    private static void sql_page(MainFrame frame, List<String> problems) {
        List<String> titles = frame.tabTitles();
        if (!titles.contains("Excel 导入") || !titles.contains("SQL 脚本改名")) {
            problems.add("页签不齐全：" + titles);
        }
        com.excel2mysql.gui.SqlPage page = frame.sqlPage();
        if (page == null) {
            problems.add("界面上没有「SQL 脚本改名」页");
            return;
        }
        // 分栏配置照 Python：把手 6px、余量按 3:2 给表格与日志、初始 360（设计值按 DPI 折算）
        if (page.splitDividerSize() != wantPx(6)) {
            problems.add("SQL 页分栏把手宽度不是 " + wantPx(6) + "px：" + page.splitDividerSize());
        }
        if (Math.abs(page.splitResizeWeight() - 0.6) > 0.001) {
            problems.add("SQL 页分栏拉伸比例不对，应为 3:2（表格多于日志）："
                    + page.splitResizeWeight());
        }
        // 日志段默认压到最小高度；切到这一页排一次版再量（隐藏页签的子控件还没量过）
        frame.selectTab(1);
        frame.validate();
        page.applySplitProportions();
        frame.validate();
        if (!"1".equals(page.logAtMin())) {
            problems.add("SQL 页日志默认没有压到最小高度：高 "
                    + page.logBoxHeight() + " / 最小 " + page.logBoxMinHeight());
        }
        frame.selectTab(0);
        frame.validate();
        if (!page.dropExistingBox().getText().equals("覆盖已存在表（先 DROP 再建）")
                || page.dropExistingBox().isSelected()) {
            problems.add("SQL 页覆盖开关的文案或默认状态不对："
                    + page.dropExistingBox().getText() + " / "
                    + page.dropExistingBox().isSelected());
        }
        // 三段结构与「Excel 导入」那页一致：拖拽区在最上、表格区在中、日志在下
        if (!"拖拽区|表格区|日志区".equals(page.segmentOrder())) {
            problems.add("SQL 页的三段分栏顺序不对：" + page.segmentOrder());
        }
        // 每张表格左上都要有标识（用户口径，第一页的「待导入文件」就是样子）
        if (!com.excel2mysql.gui.Tables.FILE_TABLE_TITLE.equals(
                com.excel2mysql.gui.Tables.titledAncestor(frame.fileTable()))) {
            problems.add("第一页主表格左上没有标识："
                    + com.excel2mysql.gui.Tables.titledAncestor(frame.fileTable()));
        }
        if (!com.excel2mysql.gui.Tables.SQL_TABLE_TITLE.equals(page.tableTitle())) {
            problems.add("第二页主表格左上的标识不对：" + page.tableTitle());
        }
        com.excel2mysql.gui.DropArea sqlDrop = page.sqlDropArea();
        if (sqlDrop == null || !sqlDrop.acceptsDrops() || !sqlDrop.hasClickAction()) {
            problems.add("SQL 页的拖拽区没接上拖放或点击选文件");
        }
        if (!com.excel2mysql.gui.DropArea.SQL_HINT.equals(sqlDrop.hintText())) {
            problems.add("SQL 页的拖拽区文案不是这一页那句：" + sqlDrop.hintText());
        }
        // 三页的虚线区都要有第三行那句 Ctrl+V 提示
        if (lines(com.excel2mysql.gui.DropArea.EXCEL_HINT) != 3
                || lines(com.excel2mysql.gui.DropArea.SQL_HINT) != 3
                || lines(com.excel2mysql.gui.DropArea.RUN_HINT) != 3
                || !com.excel2mysql.gui.DropArea.EXCEL_HINT.contains("Ctrl+V")
                || !com.excel2mysql.gui.DropArea.SQL_HINT.contains("Ctrl+V")
                || !com.excel2mysql.gui.DropArea.RUN_HINT.contains("Ctrl+V")) {
            problems.add("拖拽区第三行「Ctrl+V 直接添加」文案不对："
                    + com.excel2mysql.gui.DropArea.EXCEL_HINT);
        }
        // 两页的「统计数据总行数」都要在按钮区里（不是藏在菜单里）
        javax.swing.JButton excelStat = buttonIn(frame, "统计数据总行数");
        if (excelStat == null) {
            problems.add("第一页的按钮区没有「统计数据总行数」");
        } else if (frame.statRowsButton() != excelStat) {
            problems.add("第一页的「统计数据总行数」不是 frame.statRowsButton() 那一颗");
        }
        if (buttonIn(frame, "添加 SQL 文件") != null) {
            problems.add("第二页还留着「添加 SQL 文件」那颗按钮，入口没统一");
        }
        // 拖进来的路径只收 .sql：混着的点名跳过，一个都没收到时也得说一句
        java.io.File dropDir;
        try {
            dropDir = java.nio.file.Files.createTempDirectory("e2m-sqldrop").toFile();
        } catch (java.io.IOException e) {
            problems.add("建拖拽检查的临时目录失败：" + e);
            return;
        }
        String dropped = new java.io.File(dropDir, "拖进来的.sql").getPath();
        writeText(dropped, "CREATE TABLE t_drag (id INT);" + String.valueOf((char) 10));
        String notSql = new java.io.File(dropDir, "说明.txt").getPath();
        writeText(notSql, "不是脚本");
        com.excel2mysql.gui.SqlPage dpage = new com.excel2mysql.gui.SqlPage(frame);
        int before = dpage.entryCount();
        if (dpage.saveButton().isEnabled() || dpage.runButton().isEnabled()) {
            problems.add("刚打开的 SQL 页清单为空，保存/执行按钮却没置灰");
        }
        dpage.sqlDropArea().fireDrop(java.util.Arrays.asList(dropped, notSql));
        if (dpage.entryCount() != before + 1
                || !dpage.logPanel().contains("[跳过] 不是 SQL 脚本，未读入：说明.txt")) {
            problems.add("拖进来的 .sql 没读入，或混着的别的文件没点名跳过："
                    + dpage.entryCount() + " 个");
        }
        String dropLog = dpage.logPanel().text();
        dpage.sqlDropArea().fireDrop(java.util.Collections.singletonList(notSql));
        if (dpage.entryCount() != before + 1
                || dropLog.equals(dpage.logPanel().text())) {
            problems.add("一个 .sql 都没拖进来时没有任何回执");
        }
        if (!dpage.logPanel().contains("[提示] 拖入或粘贴的不是 SQL 脚本文件，已忽略。")) {
            problems.add("全是非脚本时的说法与第一页不同：" + dpage.logPanel().text());
        }
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-sql-selftest").toFile();
        } catch (java.io.IOException e) {
            problems.add("建临时脚本目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        String a = new java.io.File(dir, "a.sql").getPath();
        String b = new java.io.File(dir, "b.sql").getPath();
        writeText(a, "CREATE TABLE t_order (id INT);" + nl
                + "INSERT INTO `t_order` VALUES (1);" + nl
                + "CREATE TABLE plain (id INT);" + nl);
        writeText(b, "CREATE TABLE dim_x (id INT);" + nl);
        List<String> paths = new ArrayList<String>();
        paths.add(a);
        paths.add(b);

        page.addFiles(paths);
        if (page.entryCount() != 2) {
            problems.add("两个脚本应全部加载成功，实际并入 " + page.entryCount() + " 个");
        }
        if (!page.saveButton().isEnabled() || !page.runButton().isEnabled()) {
            problems.add("清单里已有脚本，保存/执行按钮却还是灰的");
        }
        if (!"添加结果".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().replace("\n", "").contains(a)
                || !frame.lastDialogText().replace("\n", "").contains(b)) {
            problems.add("添加结果弹窗没列出脚本完整路径：" + frame.lastDialogTitle());
        }
        // 四个 SQL 任务都要走主窗口的任务位，标题得是 Python 的那句原话
        if (!"正在读取 SQL 脚本…".equals(frame.lastTaskTitle())) {
            problems.add("读取任务标题不对：" + frame.lastTaskTitle());
        }
        if (page.rowCount() != 3) {
            problems.add("表格按表名出行，应有 3 行，实际 " + page.rowCount());
        }
        if (!"1".equals(page.cellText(0, 0)) || !"3".equals(page.cellText(2, 0))) {
            problems.add("SQL 表序号列没按行号连续编号：" + page.cellText(0, 0)
                    + "/" + page.cellText(2, 0));
        }
        if (!page.logPanel().contains("[已加载] a.sql：2 个表")) {
            problems.add("加载日志没报每个文件的表数");
        }
        // 覆盖执行：执行副本补 DROP、正文不动；只认整句 CREATE TABLE，注释里的建表不删
        String originalA = page.textOf(a);
        List<String[]> dropNames = new ArrayList<String[]>();
        List<com.excel2mysql.gui.SqlPage.Src> runItems = page.prepareRunItems(true, dropNames);
        String expectedA = "DROP TABLE IF EXISTS plain;" + nl
                + "DROP TABLE IF EXISTS t_order;" + nl + nl + originalA;
        if (runItems.size() != 2 || !expectedA.equals(runItems.get(0).text)
                || !runItems.get(1).text.startsWith("DROP TABLE IF EXISTS dim_x;" + nl)) {
            problems.add("勾选覆盖后执行副本没有先 DROP 脚本要建的表："
                    + (runItems.isEmpty() ? "空" : runItems.get(0).text.split(nl)[0]));
        }
        if (dropNames.size() != 2 || !"t_order、plain".equals(dropNames.get(0)[1])
                || !"dim_x".equals(dropNames.get(1)[1])) {
            problems.add("覆盖执行报出的表名清单不对：" + dropNames.size());
        }
        if (!originalA.equals(page.textOf(a))) {
            problems.add("覆盖执行改到了脚本正文");
        }
        List<String[]> noneDropped = new ArrayList<String[]>();
        if (!originalA.equals(page.prepareRunItems(false, noneDropped).get(0).text)
                || !noneDropped.isEmpty()) {
            problems.add("不勾选覆盖时执行副本不该带 DROP");
        }
        String commented = "-- CREATE TABLE ghost (id INT);" + nl
                + "/* CREATE TABLE ghost2 (id INT); */" + nl
                + "CREATE TABLE `db`.`t9` (id INT);" + nl
                + "CREATE TABLE t9 (id INT);" + nl;
        com.excel2mysql.core.SqlScript.DropPrep dropEdge =
                com.excel2mysql.core.SqlScript.prependDropTables(commented);
        if (!dropEdge.tables.equals(java.util.Arrays.asList("`db`.`t9`", "t9"))
                || !dropEdge.text.startsWith("DROP TABLE IF EXISTS t9;" + nl
                        + "DROP TABLE IF EXISTS `db`.`t9`;" + nl + nl)) {
            problems.add("覆盖执行的判据不对（注释/重名/schema 限定）：" + dropEdge.tables);
        }
        // 脚本自己已经写了 DROP 的表不再补第二句。补出来的那一句排在执行副本最前面，
        // 跑在 dump 自带的 /*!40014 SET ... FOREIGN_KEY_CHECKS=0 */; 之前——被外键引用的
        // 父表删不掉，现场看到的就是 MySQL 3730。与 pack_test 的 E23 逐格同一份夹具。
        String selfDrop = "DROP TABLE IF EXISTS `p`;" + nl
                + "CREATE TABLE `p` (id INT);" + nl
                + "DROP TABLE IF EXISTS c;" + nl
                + "CREATE TABLE `c` (id INT);" + nl
                + "DROP TABLE IF EXISTS `s`;" + nl
                + "CREATE TABLE `S` (id INT);" + nl
                + "CREATE TABLE r (id INT);" + nl
                + "INSERT INTO t VALUES ('DROP TABLE IF EXISTS sneaky');" + nl
                + "CREATE TABLE sneaky (id INT);" + nl
                + "-- DROP TABLE IF EXISTS commented" + nl
                + "CREATE TABLE commented (id INT);" + nl
                + "/* DROP TABLE IF EXISTS blocked */" + nl
                + "CREATE TABLE blocked (id INT);" + nl
                + "DROP TABLE IF EXISTS a, b;" + nl
                + "CREATE TABLE a (id INT);" + nl
                + "CREATE TABLE b (id INT);";
        com.excel2mysql.core.SqlScript.DropPrep dedup =
                com.excel2mysql.core.SqlScript.prependDropTables(selfDrop);
        if (!dedup.tables.equals(java.util.Arrays.asList(
                "`S`", "r", "sneaky", "commented", "blocked"))
                || !dedup.text.startsWith("DROP TABLE IF EXISTS blocked;" + nl
                        + "DROP TABLE IF EXISTS commented;" + nl
                        + "DROP TABLE IF EXISTS sneaky;" + nl
                        + "DROP TABLE IF EXISTS r;" + nl
                        + "DROP TABLE IF EXISTS `S`;" + nl + nl + selfDrop)) {
            problems.add("脚本自己已经删过的表又被补了一遍（大小写、注释与字符串里的写法"
                    + "不算已删）：" + dedup.tables);
        }
        // 任务日志按页分家：SQL 页读盘的那几行不该串到 Excel 页面板去
        if (frame.logPanel().contains("[已加载] a.sql")) {
            problems.add("SQL 页读取任务的日志漏到了 Excel 页");
        }
        page.addFiles(paths);
        if (!page.logPanel().contains("[跳过] 已在列表：a.sql")) {
            problems.add("重复添加同一文件没有跳过");
        }
        if (page.entryCount() != 2) {
            problems.add("重复添加把文件加了两遍");
        }
        // 空前缀不是错误：两个前缀按钮都把它当作"撤掉显示层、恢复脚本原名"
        if (page.applyPrefix() != null) {
            problems.add("空前缀应视作撤掉显示层，而不是拦下");
        }
        if (!page.logPanel().contains("前缀为空，表名显示本来就是脚本原名")) {
            problems.add("空前缀没有说明表名保持脚本原名");
        }

        page.setPrefixText("ods_");
        if (page.applyPrefix() != null) {
            problems.add("合法前缀被拦住了");
        }
        String text = page.textOf(a);
        if (text.contains("ods_t_order") || text.contains("ods_plain")) {
            problems.add("「批量应用前缀」不该改脚本正文：" + text);
        }
        if (page.isModified(a)) {
            problems.add("只改显示的前缀不该把脚本标记成已修改");
        }
        if (!page.cellText(0, 3).equals("ods_t_order")) {
            problems.add("「当前表名」列没跟着显示前缀走：" + page.cellText(0, 3));
        }
        if (!page.cellText(0, 2).equals("t_order")) {
            problems.add("「原表名」列显示的不是最初的表名：" + page.cellText(0, 2));
        }
        if (!"ods_t_order".equals(page.metadataShownName(0))) {
            problems.add("元数据弹窗没跟着显示前缀：" + page.metadataShownName(0));
        }
        if (!page.logPanel().contains("[前缀完成] 共 3 个表名改为显示前缀「ods_」")) {
            problems.add("前缀完成没有汇总几个表名改了显示");
        }
        int rowsBefore = page.rowCount();
        page.setPrefixText("ods_");
        page.applyPrefix();
        if (page.rowCount() != rowsBefore || page.cellText(0, 3).contains("ods_ods_")) {
            problems.add("重复加同一个前缀叠了一层");
        }
        page.setPrefixText("");
        page.applyPrefix();
        if (!page.cellText(0, 3).equals("t_order") || !page.cellText(2, 3).equals("dim_x")) {
            problems.add("清空前缀再点一次没恢复脚本原名：" + page.cellText(0, 3)
                    + "/" + page.cellText(2, 3));
        }
        if (!page.logPanel().contains("前缀已清空")) {
            problems.add("清空前缀没有留下恢复原名的日志");
        }
        if (page.textOf(a).contains("ods_t_order")) {
            problems.add("清空前缀不该动脚本正文：" + page.textOf(a));
        }

        page.setPrefixText("ods_");
        page.applyPrefix();
        page.setPrefixText("change");
        if (page.replaceFirstPrefix() != null) {
            problems.add("替换首个前缀被拦住");
        }
        String after = page.textOf(a);
        if (after.contains("change_t_order") || after.contains("change_plain")) {
            problems.add("「替换首个前缀」不该改脚本正文：" + after);
        }
        if (!page.cellText(0, 3).equals("change_t_order")
                || !page.cellText(1, 3).equals("change_plain")) {
            problems.add("替换首段没改到显示名：" + page.cellText(0, 3)
                    + "/" + page.cellText(1, 3));
        }
        if (!page.cellText(2, 3).equals("change_dim_x")) {
            problems.add("ods_dim_x 的首段在显示层没被换掉：" + page.cellText(2, 3));
        }
        if (page.isModified(a) || page.isModified(b)) {
            problems.add("只改显示的换前缀不该标记成已修改");
        }

        page.saveModified();
        if (!page.textOf(a).contains("CREATE TABLE change_t_order")
                || !page.textOf(a).contains("INSERT INTO `change_t_order`")) {
            problems.add("保存时没把显示名写进脚本：" + page.textOf(a));
        }
        if (!page.textOf(b).contains("change_dim_x")) {
            problems.add("ods_dim_x 的首段没被换掉：" + page.textOf(b));
        }
        if (page.isModified(a) || page.isModified(b)) {
            problems.add("两个改过的脚本都该落盘并清掉已修改标记");
        }
        if (!"正在保存修改后的脚本…".equals(frame.lastTaskTitle())) {
            problems.add("保存任务标题不对：" + frame.lastTaskTitle());
        }
        if (!page.logPanel().contains("[生效] ")) {
            problems.add("显示名写入脚本没有留下日志");
        }
        if (!page.logPanel().contains("[已保存] ")) {
            problems.add("落盘成功没有报出写了哪个文件");
        }
        if (!"保存完成".equals(frame.lastDialogTitle())) {
            problems.add("保存后没有结果弹窗：" + frame.lastDialogTitle());
        }
        String outA = com.excel2mysql.gui.SqlPage.modifiedPath(a);
        if (!frame.lastDialogText().replace("\n", "").contains(outA)) {
            problems.add("保存结果弹窗没列出副本完整路径：" + frame.lastDialogText());
        }
        if (!new java.io.File(outA).isFile()) {
            problems.add("保存后没有生成副本：" + outA);
        }
        // 没改动时再点一次要说清楚，不能静默返回
        page.saveModified();
        if (!page.logPanel().contains("[保存] 没有修改过的脚本文件")) {
            problems.add("没有改动时保存要如实说明，不能静默");
        }
        // SQL 页任务的日志要留在 SQL 页那块面板：漏到 Excel 页就等于用户找不到刚才那步
        if (frame.logPanel().contains("[已保存] ")) {
            problems.add("SQL 页任务的日志写到了 Excel 页面板");
        }
        if (!"x/y_prefix.sql".equals(com.excel2mysql.gui.SqlPage.modifiedPath("x/y.sql"))) {
            problems.add("副本文件名规则不对：" + com.excel2mysql.gui.SqlPage.modifiedPath("x/y.sql"));
        }
        if (!"x/y_prefix.sql".equals(com.excel2mysql.gui.SqlPage.modifiedPath("x/y"))) {
            problems.add("无扩展名时副本名没补 .sql");
        }

        // 真实 dump 的编码场景：UTF-8 正文里夹着原样导出的二进制字段，第二页表格里的
        // 表注释必须是中文。整份回落 GBK 时这里会显示「瀵嗛挜」。
        try {
            java.io.File mixedFile = new java.io.File(dir, "mixed.sql");
            byte[] head = ("CREATE TABLE `enc_mixed` (`id` INT COMMENT '密钥')"
                    + " COMMENT='密钥表';" + nl).getBytes("UTF-8");
            byte[] blob = new byte[] {(byte) 0xCE, 0x4E, 0x5E, 0x5C, 0x30,
                    0x00, 0x02, (byte) 0xFF, (byte) '\n'};
            byte[] mixedBytes = new byte[head.length + blob.length];
            System.arraycopy(head, 0, mixedBytes, 0, head.length);
            System.arraycopy(blob, 0, mixedBytes, head.length, blob.length);
            java.nio.file.Files.write(mixedFile.toPath(), mixedBytes);
            com.excel2mysql.gui.SqlPage encPage = new com.excel2mysql.gui.SqlPage(frame);
            encPage.addFiles(java.util.Collections.singletonList(mixedFile.getPath()));
            String encComment = encPage.rowCount() == 1 ? encPage.cellText(0, 4) : "";
            if (!"密钥表".equals(encComment)) {
                problems.add("夹二进制字段的 UTF-8 脚本，表注释读成了乱码：" + encComment);
            }
            com.excel2mysql.core.SqlScript.ParsedTable encParsed = encPage.metadataAtRow(0);
            String encFieldComment = encParsed == null || encParsed.columns.isEmpty()
                    ? "" : encParsed.columns.get(0).comment;
            if (!"密钥".equals(encFieldComment)) {
                problems.add("夹二进制字段的 UTF-8 脚本，字段注释读成了乱码：" + encFieldComment);
            }
        } catch (java.io.IOException e) {
            problems.add("建混合编码脚本夹具失败：" + e);
        }

        String hint = page.execute();
        if (hint == null || !hint.contains("未选择目标库")) {
            problems.add("没选库时执行应当拦下并说明：" + hint);
        }
        // 整库 dump 拖到第二页：这一页按每个文件一个事务跑，日志要点明这一页的跑法、
        // 并指向第三个页签；勾选覆盖时脚本自己已经删过的表也不再补第二句 DROP。
        // 走的是真入口 execute()，宿主给的是连不上的库（127.0.0.1:1）：任务体秒失败就返回，
        // 一个字节都不碰真库——只测文案生成函数的话，"这一句真写进界面日志"就没验到。
        try {
            java.io.File dumpFile = new java.io.File(dir, "第二页dump.sql");
            java.nio.file.Files.write(dumpFile.toPath(), (
                    "-- MariaDB dump 10.19  Distrib 10.11.16-MariaDB" + nl
                            + "/*!40101 SET NAMES utf8mb4 */;" + nl
                            + "/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS,"
                            + " FOREIGN_KEY_CHECKS=0 */;" + nl
                            + "DROP TABLE IF EXISTS `p`;" + nl
                            + "CREATE TABLE `p` (id INT);" + nl
                            + "-- Dump completed on 2026-10-25 12:00:00" + nl)
                    .getBytes("UTF-8"));
            ProbeHost probe = new ProbeHost(dir, frame);
            com.excel2mysql.gui.SqlPage dumpPage = new com.excel2mysql.gui.SqlPage(probe);
            dumpPage.addFiles(java.util.Collections.singletonList(dumpFile.getPath()));
            dumpPage.dropExistingBox().setSelected(true);
            dumpPage.execute();
            String wantHint = "[提示] 第二页dump.sql：这份看着像整库 dump，"
                    + "但这一页仍按每个文件一个事务执行。整库导入请用第三个页签「SQL 执行器」"
                    + "的脚本文件队列，那边对 dump 不开启事务、逐条提交、出错继续并在最后汇总。";
            String wantCover = "[覆盖] 没有表需要先补 DROP：脚本里没有以 CREATE TABLE 开头的语句，"
                    + "或要建的表脚本自己已经写了 DROP";
            String dlog = dumpPage.logPanel().text();
            if (!dlog.contains(wantHint)) {
                problems.add("第二页没把整库 dump 的跑法区别说清（或这句没真写进日志）："
                        + tailOf(dlog));
            }
            if (!dlog.contains(wantCover) || dlog.contains("执行前先 DROP")) {
                problems.add("dump 自带的 DROP 已覆盖每张表，勾选覆盖却还是补了第二句或没说原因："
                        + tailOf(dlog));
            }
            if (probe.lastSink == null
                    || !probe.lastSink.saw("[连接失败] 无法连接到 testdb@127.0.0.1:1", true)) {
                problems.add("任务体没被真跑起来，或连不上库的原因没按错误级报出："
                        + (probe.lastSink == null ? "任务体一次都没起" : probe.lastSink.errors));
            }
            for (String pop : probe.pops) {
                if (pop.contains("执行完成")) {
                    problems.add("连不上库时不该再弹一遍执行汇总：" + tailOf(pop));
                }
            }
            dumpFile.delete();
        } catch (java.io.IOException e) {
            problems.add("写第二页的整库 dump 夹具失败：" + e);
        }
        List<com.excel2mysql.gui.SqlPage.RunFile> runFiles =
                new ArrayList<com.excel2mysql.gui.SqlPage.RunFile>();
        runFiles.add(new com.excel2mysql.gui.SqlPage.RunFile(a, "fail",
                "第 3/9 条语句报错，本文件事务已回滚"));
        runFiles.add(new com.excel2mysql.gui.SqlPage.RunFile(b, "ok", "4 条语句已提交"));
        String summary = page.finishRun(new com.excel2mysql.gui.SqlPage.RunResult(
                1, 1, 0, false, false, runFiles, "testdb@127.0.0.1:3306"));
        if (!summary.contains("成功 1 个文件，失败 1 个文件")) {
            problems.add("执行收尾文案不对：" + summary.replace((char) 10, ' '));
        }
        if (!summary.contains("目标库 testdb@127.0.0.1:3306")
                || !summary.contains(a) || !summary.contains(b)) {
            problems.add("执行结果弹窗没列出目标库与脚本完整路径");
        }
        if (!page.logPanel().isErrorLine("===== 执行完成：成功 1 个文件，失败 1 个文件")) {
            problems.add("有失败时执行收尾行没标成错误级");
        }

        int cleared = page.clearAllFiles();
        if (cleared != 2) {
            problems.add("「清空待上传文件」应整份清出清单，实际清掉 " + cleared + " 个");
        }
        if (page.entryCount() != 0 || page.rowCount() != 0) {
            problems.add("清空后文件与行数都不对：" + page.entryCount() + "/" + page.rowCount());
        }
        if (page.genButtonsEnabled()) {
            problems.add("清空待上传文件后生成按钮还亮着");
        }
        if (page.saveButton().isEnabled() || page.runButton().isEnabled()) {
            problems.add("清空待上传文件后保存/执行按钮还亮着");
        }
        if (!page.logPanel().contains("[清空] 已清空 2 个脚本文件")) {
            problems.add("清空待上传文件没有留痕");
        }
        page.clearAllFiles();
        if (!page.logPanel().contains("[清空] 清单里没有脚本文件，无需清空")) {
            problems.add("空清单再点清空也要说一句，不能静默不动");
        }
        deleteTree(dir);
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static void help_and_about(MainFrame frame, List<String> problems) {
        String title = frame.getTitle();
        if (!title.contains("v") || !title.contains(
                com.excel2mysql.core.AppInfo.load().versionText())) {
            problems.add("窗口标题没带版本号：" + title);
        }
        // 命令行与界面必须同源同版：以前 Main.java 另写一份常量，升版本只改到界面，
        // --version 和启动日志会一直报旧号。这里直接跑一次 --version 把两边对上。
        String wantVersion = com.excel2mysql.core.AppInfo.load().versionText();
        java.io.PrintStream oldOut = System.out;
        String cliVersion;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            System.setOut(new java.io.PrintStream(buf, true, "UTF-8"));
            int code = Main.run(new String[]{"--version"});
            cliVersion = new String(buf.toByteArray(), "UTF-8").trim();
            if (code != 0) {
                problems.add("命令行 --version 的退出码是 " + code);
            }
        } catch (Exception e) {
            cliVersion = "";
            problems.add("命令行 --version 跑不起来：" + e);
        } finally {
            System.setOut(oldOut);
        }
        if (!cliVersion.contains(wantVersion)) {
            problems.add("命令行 --version 与界面版本不同源：" + cliVersion);
        }
        com.excel2mysql.gui.HelpDialog hd = new com.excel2mysql.gui.HelpDialog(null);
        try {
            if (hd.tocCount() != 18) {
                problems.add("说明目录应有 18 章（与 help_doc.py 同步，改了要重导 HelpDoc.java），"
                        + "实际 " + hd.tocCount());
            }
            if (hd.bodyLength() < 2000) {
                problems.add("说明正文没渲染出来，长度只有 " + hd.bodyLength());
            }
            // Qt 在 150% 屏上把 10.5pt 渲染成 21 个物理像素；Swing 的 HTML 渲染器
            // 又会把 CSS px 按 4/3 放大，所以 CSS 里只该写 21*3/4=15.75px。
            // 期望值同样独立按 DPI 算，不引用 UiScale 和 HelpDialog 的折算。
            String helpHtml = hd.html();
            int helpTarget = wantFontPx(10.5);
            double helpCss = helpTarget * 0.75;
            String helpCssText = helpCss == Math.rint(helpCss)
                    ? String.valueOf((int) helpCss) : String.valueOf(helpCss);
            String wantBodyPx = "font-size: " + helpCssText + "px";
            if (!helpHtml.contains(wantBodyPx)
                    || java.util.regex.Pattern.compile("font-size:\\s*[0-9.]+pt")
                    .matcher(helpHtml).find()) {
                problems.add("说明正文字号没折成 " + wantBodyPx
                        + "：Swing 会把物理像素再放大 4/3");
            }
            // 字符串对不代表画出来对：量首段第一行，把 4/3 放大真的咬住。
            // 量的必须是界面真正在用的那一个 Font 对象：写死族名去 new 一支，
            // 机器上装着同名字体时会量到系统那一支，行高就成了一种"看运气"的判据
            int helpLine = hd.bodyLineHeight();
            int wantHelpLine = hd.getFontMetrics(
                    UiScale.font(java.awt.Font.PLAIN, helpTarget)).getHeight();
            if (helpLine < 0 || Math.abs(helpLine - wantHelpLine) > 4) {
                problems.add("说明正文渲染行高 " + helpLine + "px，应接近 "
                        + helpTarget + "px 字号的 " + wantHelpLine + "px");
            }
            // 按标题找而不是按序号：说明里插一章就会把序号挪位，写死序号的守卫会假红
            int excelChapter = -1;
            for (int i = 0; i < hd.tocCount(); i++) {
                if ("两类 Excel 格式".equals(hd.tocTitle(i))) {
                    excelChapter = i;
                }
            }
            if (excelChapter < 0) {
                problems.add("说明目录里没有「两类 Excel 格式」这一章");
            } else {
                hd.selectToc(excelChapter);
                if (!"excel".equals(hd.currentAnchor())) {
                    problems.add("点「两类 Excel 格式」没跳到对应锚点，锚点=" + hd.currentAnchor());
                }
            }
            hd.selectToc(0);
            if (!"start".equals(hd.currentAnchor())) {
                problems.add("跳回第一章失败，锚点=" + hd.currentAnchor());
            }
            if (!"anchor".equals(hd.jumpVia())) {
                problems.add("目录跳转不是靠锚点实现的（走的是 " + hd.jumpVia()
                        + "），说明 HTML 里的 a name 没被渲染器收下来");
            }
            // 文档漂移守卫，与 Python 侧 pack_test 的 P15/P16 同一口径：界面上每个按钮
            // 都要在说明里点名，说明里点名的按钮也必须真在界面上。两边各写各的，
            // 早晚会一个有一个没有——加了按钮忘了写说明，用户只能靠猜
            String doc = com.excel2mysql.gui.HelpDoc.html();
            java.util.Set<String> onUi = new java.util.LinkedHashSet<String>();
            for (javax.swing.AbstractButton b : frame.buttons()) {
                onUi.add(b.getText());
            }
            String[] named = {"批量应用前缀", "替换首个前缀", "生成表结构预览",
                    "预览 DDL", "开始导入", "清空待上传文件", "保存修改后ddl脚本",
                    "执行修改后ddl脚本到当前库", "连接并加载库",
                    "生成标准excel文件", "生成简易excel文件", "示例 (?)",
                    "清空编辑区", "清空结果区", "设置数据源信息", "收起",
                    "清空待执行脚本", "批量执行脚本文件"};
            for (String n : named) {
                if (!onUi.contains(n)) {
                    problems.add("说明里点名的按钮界面上不存在：" + n);
                }
                if (!doc.contains(n)) {
                    problems.add("按钮没被使用说明覆盖：" + n);
                }
            }
            if (!doc.contains("点击虚线区选文件") && !doc.contains("点一下虚线区")) {
                problems.add("使用说明没交代点击虚线区就能选文件");
            }
        } finally {
            hd.dispose();
        }
        com.excel2mysql.gui.AboutDialog ad = new com.excel2mysql.gui.AboutDialog(null);
        try {
            // 首屏不裁字：版本号/提交号/构建日期三行都按自己的首选宽度检查一遍
            String fit = ad.fitProblems();
            if (!fit.isEmpty()) {
                problems.add("关于框首屏文字摆不下：" + fit);
            }
            if (!ad.valueOf("version").startsWith("v")) {
                problems.add("关于框的版本行不对：" + ad.valueOf("version"));
            }
            String commit = ad.valueOf("commit");
            if (commit == null || commit.isEmpty()) {
                problems.add("关于框的提交行是空的");
            }
            if ("未知".equals(ad.valueOf("built")) && "unknown".equals(
                    com.excel2mysql.core.AppInfo.load().source)) {
                problems.add("关于框既没有构建日期也没有声明来源，用户无从判断可信度");
            }
            // 关于框里既不铺来源说明，也不挂悬浮提示：那段话（构建戳从哪来、自己核该跑
            // 哪条命令）归《使用说明》，框面只留四行结果。两条判据配成一对，少一条就漏
            String tips = ad.faceTips();
            if (!tips.isEmpty()) {
                problems.add("关于框又挂上悬浮提示了（来源说明归《使用说明》）："
                        + brief(tips));
            }
            if (!hasRule(ad.getContentPane())) {
                problems.add("关于框标题与四行之间少了那条分隔线（信息层次那一档）");
            }
            // 正面不许再铺那段说明：界面上数到"构建戳"这三个字就是退回老样子
            if (faceText(ad.getContentPane()).contains("构建戳")) {
                problems.add("关于框正面铺了来源说明（该只写在《使用说明》里）");
            }
            // 整框必须真画得出来：画不出来的组件（折行文本抽掉光标那类）只有画一次才暴露
            if (inkOf(ad.getContentPane(), new java.awt.Color(0x66, 0x66, 0x66)) <= 0) {
                problems.add("关于框没画出灰色那一档文字（灰字像素为 0）");
            }
            checkAboutCopy(ad, problems);
            checkAuthorLink(ad, problems);
            // 构建时间戳：Maven 打的是 UTC，Python 打的是本机时间，显示前必须换成时区
            String shifted = com.excel2mysql.core.AppInfo.toLocal("2026-10-13 02:22:08");
            java.util.Calendar utc = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            utc.set(2026, java.util.Calendar.OCTOBER, 13, 2, 22, 8);
            utc.set(java.util.Calendar.MILLISECOND, 0);
            long offset = java.util.TimeZone.getDefault().getOffset(utc.getTimeInMillis());
            if (offset != 0 && "2026-10-13 02:22:08".equals(shifted)) {
                problems.add("构建日期还是 UTC 原样，没换成本机时间（本机偏移 "
                        + (offset / 3600000) + " 小时）");
            }
            if (shifted == null || shifted.length() != 19) {
                problems.add("构建日期换算后不成样：" + shifted);
            }
            if (!"开发态（未打包）".equals(
                    com.excel2mysql.core.AppInfo.toLocal("开发态（未打包）"))) {
                problems.add("不是时间戳的构建日期被时间换算改坏了");
            }
        } finally {
            ad.dispose();
        }
        // 长提交号也要一眼看全：开发态会带分支名与“工作区有未提交改动”，
        // 比打包态的 7 位短号长得多；旧的两列等宽布局就是在这里把字裁掉的
        com.excel2mysql.core.AppInfo longInfo = com.excel2mysql.core.AppInfo.load();
        longInfo.version = "9.9.9";
        longInfo.commit = "1234567";
        longInfo.branch = "feature/excel2mysql-empty-table-primary-key";
        longInfo.dirty = true;
        com.excel2mysql.gui.AboutDialog wide =
                new com.excel2mysql.gui.AboutDialog(null, longInfo);
        try {
            String longFit = wide.fitProblems();
            if (!longFit.isEmpty()) {
                problems.add("关于框遇到长提交号会裁字：" + longFit);
            }
        } finally {
            wide.dispose();
        }
    }

    /** 把夹具打进一个 zip，验展开、汇总与关窗清理这一整条路。 */
    private static void zip_roundtrip(MainFrame frame, List<String> files,
                                      List<String> problems) {
        if (files.isEmpty()) {
            problems.add("ZIP 自检没有夹具");
            return;
        }
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-zip-selftest").toFile();
        } catch (java.io.IOException e) {
            problems.add("建 ZIP 临时目录失败：" + e);
            return;
        }
        java.io.File zip = new java.io.File(dir, "pack1.zip");
        try {
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.FileOutputStream(zip));
            zos.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            zos.write("不是 Excel".getBytes(java.nio.charset.Charset.forName("UTF-8")));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("inner.xlsx"));
            byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(files.get(0)));
            zos.write(raw);
            zos.closeEntry();
            // 包内两层目录：表名要从包根一路拼到文件名（目录之间下划线），
            // 表注释用 `/` 留层级——这条只能靠真的走一遍解压才验得到
            zos.putNextEntry(new java.util.zip.ZipEntry(
                    "\u5317\u4eac/\u6cb3\u9053\u6c34\u6587\u7ad9/"
                            + "\u6cb3\u9053\u6c34\u6587\u7ad9.xlsx"));
            zos.write(raw);
            zos.closeEntry();
            zos.close();
        } catch (Exception e) {
            problems.add("打 ZIP 夹具失败：" + e);
            deleteTree(dir);
            return;
        }
        int before = frame.tables().size();
        List<String> one = new ArrayList<String>();
        one.add(zip.getPath());
        String summary = frame.addFiles(one);
        if (frame.tables().size() != before + 2) {
            problems.add("压缩包里的 Excel 没被展开进列表：" + before + " → "
                    + frame.tables().size() + "；日志尾部：" + tail(frame.logPanel().text()));
        }
        if (!frame.logPanel().contains("[压缩包] pack1.zip：展开 2 个 Excel，忽略 1 个非 Excel 文件")) {
            problems.add("压缩包展开没报清楚（展开几个、忽略几个）");
        }
        // 包内两层目录的那张：表名带目录前缀，注释带层级（图形版默认只取首字母）
        com.excel2mysql.core.TableOps.Table nestedTable = null;
        for (com.excel2mysql.core.TableOps.Table t : frame.tables()) {
            if (t.filepath != null && t.filepath.endsWith("\u6cb3\u9053\u6c34\u6587\u7ad9.xlsx")) {
                nestedTable = t;
                break;
            }
        }
        if (nestedTable == null) {
            problems.add("包内两层目录的 Excel 没进列表");
        } else {
            // 表名必须带目录前缀。两种写法都算对：「取首字母」勾没勾是界面状态，
            // 命名那一段自检跑在前面会动这个开关；只用叶子名的那一版必须撞红
            String nestedName = nestedTable.tableName;
            boolean withDirs = "bjhdswz_hdswz".equals(nestedName)
                    || "bei_jing_he_dao_shui_wen_zhan_he_dao_shui_wen_zhan".equals(nestedName);
            if (!withDirs) {
                problems.add("包内文件的表名没从包根拼到文件名：" + nestedName);
            }
            if (!"\u5317\u4eac/\u6cb3\u9053\u6c34\u6587\u7ad9/\u6cb3\u9053\u6c34\u6587\u7ad9".equals(nestedTable.tableComment)) {
                problems.add("包内文件的表注释没留目录层级：" + nestedTable.tableComment);
            }
        }
        if (!summary.contains("添加 1 项 zip 文件")) {
            problems.add("弹窗汇总没把 zip 单列一行：" + summary.replace((char) 10, ' '));
        }
        int last = frame.fileModel().getRowCount() - 1;
        String tip = frame.fileModel().tooltipAt(last);
        if (!tip.contains("pack1.zip!") || !tip.contains(".xlsx")) {
            problems.add("包内文件的来源 tooltip 不对：" + tip);
        }
        String looseTip = frame.fileModel().tooltipAt(0);
        if (looseTip.isEmpty() || !looseTip.contains(".xlsx")) {
            problems.add("散装文件的全路径 tooltip 缺失：" + looseTip);
        }
        String tempDir = frame.zipTempDir();
        if (tempDir == null || !new java.io.File(tempDir).isDirectory()) {
            problems.add("展开压缩包却没建会话临时目录");
        }
        frame.cleanZipTemp();
        if (tempDir != null && new java.io.File(tempDir).exists()) {
            problems.add("关窗清理没真的删掉临时目录：" + tempDir);
        }
        if (!frame.logPanel().contains("已清理解压临时目录")) {
            problems.add("清理临时目录没有留痕");
        }
        deleteTree(dir);
        frame.clearAll();
    }

    /**
     * 压缩包这三条现场形状，一条都不能靠"报一句错"过关：GBK 条目名、ZIP64 中央目录、
     * 连续多批拖入时的解压目录编号。
     *
     * <p>GBK 条目名（Windows 自带「发送到压缩文件夹」写的那一款）：JDK 默认按 UTF-8
     * <b>严格</b>解条目名，非 UTF-8 字节直接抛 IllegalArgumentException: MALFORMED，
     * 于是枚举第一条就炸，整包解不出来。ZIP64 的条目数与目录偏移写在 64 位字段里，
     * 旧 EOCD 那三格是占位值，自建的中央目录扫描只按旧字段取会整份失明，
     * 位标记查不到就把中文名按 cp437→GBK 折成一串问号，写盘报「路径语法不正确」。
     * 编号按批次 +1、批内却按已解文件数跳号，多包批次之后再接着拖几批就会拐回用过的
     * 目录号：前一批解出来的那份被原地覆盖，同名文件还会判成「已在待导入列表」而静默少一张表。
     */
    private static void zip_field_shapes(MainFrame frame, List<String> files,
                                         List<String> problems) {
        if (files.size() < 3) {
            problems.add("压缩包形状自检至少要 3 个 xlsx 夹具");
            return;
        }
        File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-zipshape").toFile();
        } catch (java.io.IOException e) {
            problems.add("建压缩包形状自检目录失败：" + e);
            return;
        }
        try {
            byte[] b0 = java.nio.file.Files.readAllBytes(new File(files.get(0)).toPath());
            byte[] b1 = java.nio.file.Files.readAllBytes(new File(files.get(1)).toPath());
            byte[] b2 = java.nio.file.Files.readAllBytes(new File(files.get(2)).toPath());

            // 1. GBK 条目名的包：解出来必须还是那句中文文件名，不能是乱码也不能是问号
            File gbk = new File(dir, "gbk.zip");
            writeGbkNameZip(gbk, "水库基础信息.xlsx", b0);
            String gbkOut = new File(dir, "out_gbk").getPath();
            try {
                com.excel2mysql.core.ZipImport.Result r =
                        com.excel2mysql.core.ZipImport.extract(gbk.getPath(), gbkOut);
                if (r.files.size() != 1) {
                    problems.add("GBK 条目名的包没解出那一张表，实际 " + r.files.size() + " 张");
                } else {
                    String base = new File(r.files.get(0).path).getName();
                    if (!"水库基础信息.xlsx".equals(base)) {
                        problems.add("GBK 条目名还原错了：" + base);
                    }
                    if (!new File(r.files.get(0).path).isFile()) {
                        problems.add("GBK 包解出来的落点不在盘上：" + r.files.get(0).path);
                    }
                }
            } catch (Exception e) {
                problems.add("GBK 条目名的包解不开：" + e);
            }
            if (com.excel2mysql.core.ZipImport.countExcelEntries(gbk.getPath()) != 1) {
                problems.add("GBK 条目名的包预扫条数不是 1（预扫静默返 0 就等于进度项数算少）");
            }

            // 2. ZIP64 中央目录：条目数与目录偏移都在 64 位字段里
            File z64 = new File(dir, "zip64.zip");
            writeUtf8NameZip(z64, new String[]{"readme.txt", "北京水库基础信息表（dim_reservoir）.xlsx"},
                    new byte[][]{"不是 Excel".getBytes("UTF-8"), b1});
            appendZip64Eocd(z64);
            if (com.excel2mysql.core.ZipImport.countExcelEntries(z64.getPath()) != 1) {
                problems.add("ZIP64 包预扫不到那一张表：中央目录按 16/32 位 EOCD 取就失明");
            }
            try {
                com.excel2mysql.core.ZipImport.Result r =
                        com.excel2mysql.core.ZipImport.extract(z64.getPath(),
                                new File(dir, "out_z64").getPath());
                if (r.files.size() != 1) {
                    problems.add("ZIP64 包没解出那一张表，实际 " + r.files.size() + " 张");
                } else {
                    String base = new File(r.files.get(0).path).getName();
                    if (base.indexOf('?') >= 0 || !"北京水库基础信息表（dim_reservoir）.xlsx".equals(base)) {
                        problems.add("ZIP64 包的中文条目名被折坏了：" + base);
                    }
                }
            } catch (Exception e) {
                problems.add("ZIP64 包解不开：" + e);
            }

            // 3. ZIP64 里的加密条目：位标记写在中央目录，扫描器读不到 64 位字段就报不出
            //    「已加密」，只会拖到写盘那一刻给一句看不出差错在哪的失败
            File zenc = new File(dir, "zip64_enc.zip");
            String encName = "加密表（ods_t_enc）.xlsx";
            writeUtf8NameZip(zenc, new String[]{encName}, new byte[][]{b1});
            appendZip64Eocd(zenc);
            setEncryptedFlag(zenc, encName);
            try {
                com.excel2mysql.core.ZipImport.extract(zenc.getPath(),
                        new File(dir, "out_enc").getPath());
                problems.add("ZIP64 包里的加密条目没被认出来（扫描器只按 16/32 位 EOCD 取就会这样）");
            } catch (com.excel2mysql.core.ZipImport.ZipArchiveException e) {
                if (e.getMessage() == null || e.getMessage().indexOf("已加密") < 0) {
                    problems.add("加密条目报的不是「已加密」：" + e.getMessage());
                }
            }

            // 4. 连续多批拖入：两个不同的包都叫 B.zip，批内还夹着一个多包批次
            File d1 = new File(dir, "d1");
            File d2 = new File(dir, "d2");
            File d3 = new File(dir, "d3");
            d1.mkdirs();
            d2.mkdirs();
            d3.mkdirs();
            File zipA = new File(d1, "A.zip");
            writeUtf8NameZip(zipA, new String[]{"甲表（t_a）.xlsx", "乙表（t_b）.xlsx"},
                    new byte[][]{b0, b1});
            File zipB1 = new File(d1, "B.zip");
            File zipB2 = new File(d2, "B.zip");
            File zipB3 = new File(d3, "B.zip");
            writeUtf8NameZip(zipB1, new String[]{"丙表（t_c）.xlsx"}, new byte[][]{b2});
            writeUtf8NameZip(zipB2, new String[]{"丙表（t_c）.xlsx"}, new byte[][]{b1});
            writeUtf8NameZip(zipB3, new String[]{"丙表（t_c）.xlsx"}, new byte[][]{b0});

            int logMark = frame.logPanel().text().length();
            int before = frame.tables().size();
            List<String> one = new ArrayList<String>();
            one.add(zipA.getPath());
            one.add(zipB1.getPath());
            frame.addFiles(one);
            List<String> only = new ArrayList<String>();
            only.add(zipB2.getPath());
            frame.addFiles(only);
            List<String> only3 = new ArrayList<String>();
            only3.add(zipB3.getPath());
            frame.addFiles(only3);
            int got = frame.tables().size() - before;
            if (got != 5) {
                problems.add("连续三批拖入应新增 5 张表（A 里 2 张 + 三个 B 各 1 张），实际 " + got);
            }
            String tail = frame.logPanel().text().substring(logMark);
            if (tail.contains("[重复跳过]")) {
                problems.add("不同包的同名文件被判成了「重复跳过」——解压目录编号拐回了用过的号："
                        + tailLineWith(tail, "[重复跳过]"));
            }
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (com.excel2mysql.core.TableOps.Table t : frame.tables()) {
                if (t.filepath == null || !new File(t.filepath).isFile()) {
                    problems.add("列表里有一张表的来源文件不在盘上：" + t.tableName + " → " + t.filepath);
                }
                if (t.filepath != null && !seen.add(t.filepath)) {
                    problems.add("两张表指向同一个解压落点（后一批把前一批覆盖了）：" + t.filepath);
                }
            }
        } catch (Exception e) {
            problems.add("压缩包形状自检没跑成：" + e);
        }
        frame.cleanZipTemp();
        deleteTree(dir);
        frame.clearAll();
    }

    /** 日志尾部里第一条含某个标记的行，自检报错时要能指出是哪一句。 */
    private static String tailLineWith(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "";
    }

    /** 普通 UTF-8 命名的包（bit11 置着），条目名与内容都由调用方给。 */
    private static void writeUtf8NameZip(File out, String[] names, byte[][] bodies)
            throws java.io.IOException {
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(out), java.nio.charset.Charset.forName("UTF-8"));
        for (int i = 0; i < names.length; i++) {
            zos.putNextEntry(new java.util.zip.ZipEntry(names[i]));
            zos.write(bodies[i]);
            zos.closeEntry();
        }
        zos.close();
    }

    /**
     * 造一个条目名按 GBK 存、且不打 UTF-8 标记的包——与 Windows「发送到压缩文件夹」同一款。
     *
     * <p>Python 的 zipfile、Java 的 ZipOutputStream 都对非 ASCII 名字一律写 UTF-8 并置
     * bit11，没法直接产出这种包。这里先用等长纯 ASCII 占位名打包（保证字节数与偏移不变），
     * 再按头部结构把名字字段原地换成 GBK 字节并清掉 bit11。
     */
    private static void writeGbkNameZip(File out, String cnName, byte[] body)
            throws java.io.IOException {
        byte[] gbk = cnName.getBytes("GBK");
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < gbk.length; i++) {
            ph.append('P');
        }
        writeUtf8NameZip(out, new String[]{ph.toString()}, new byte[][]{body});
        byte[] raw = java.nio.file.Files.readAllBytes(out.toPath());
        // 本地头：flag 在 6、名字长度在 26、名字从 30 起；中央目录：flag 8、长度 28、名字 46。
        // bit11 在这个 16 位字段的**高字节**第 3 位（偏移 7 / 9）——清低字节只是清掉
        // bit3「带数据描述符」。JDK 的 ZipOutputStream 连纯 ASCII 名都照样置 bit11，
        // 清错了位等于没清：条目名仍按 UTF-8 严格解，当场抛 MALFORMED。
        int[] sigs = {0x04034b50, 0x02014b50};
        int[] flagOff = {7, 9};
        int[] lenOff = {26, 28};
        int[] nameOff = {30, 46};
        byte[] placeholder = ph.toString().getBytes("UTF-8");
        for (int s = 0; s < 2; s++) {
            for (int i = 0; i + 4 <= raw.length; i++) {
                if (le32(raw, i) != sigs[s]) {
                    continue;
                }
                int nlen = le16(raw, i + lenOff[s]);
                boolean hit = true;
                for (int k = 0; k < placeholder.length; k++) {
                    if (raw[i + nameOff[s] + k] != placeholder[k]) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    System.arraycopy(gbk, 0, raw, i + nameOff[s], gbk.length);
                    raw[i + flagOff[s]] &= 0xF7;      // 清 bit11
                }
            }
        }
        java.nio.file.Files.write(out.toPath(), raw);
    }

    /**
     * 把一个普通包改写成 ZIP64：追加 64 位的 EOCD 与它的定位块，
     * 再把旧 EOCD 那三格填成占位值（0xFFFF / 0xFFFFFFFF）——真实工具产出 ZIP64 时就是这么写的。
     */
    private static void appendZip64Eocd(File zip) throws java.io.IOException {
        byte[] buf = java.nio.file.Files.readAllBytes(zip.toPath());
        int eocd = -1;
        for (int i = buf.length - 22; i >= 0; i--) {
            if (le32(buf, i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new java.io.IOException("夹具里没有 EOCD，改不出 ZIP64");
        }
        long cdSize = le32(buf, eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = le32(buf, eocd + 16) & 0xFFFFFFFFL;
        int total = le16(buf, eocd + 10);
        java.io.ByteArrayOutputStream z64 = new java.io.ByteArrayOutputStream();
        byte[] rec = new byte[56];
        putLe32(rec, 0, 0x06064b50);
        putLe64(rec, 4, 44);
        putLe16(rec, 12, 60);
        putLe16(rec, 14, 45);
        putLe32(rec, 16, 0);
        putLe32(rec, 20, 0);
        putLe64(rec, 24, total);
        putLe64(rec, 32, total);
        putLe64(rec, 40, cdSize);
        putLe64(rec, 48, cdOffset);
        byte[] loc = new byte[20];
        putLe32(loc, 0, 0x07064b50);
        putLe32(loc, 4, 0);
        putLe64(loc, 8, eocd);                        // 64 位记录就贴在旧 EOCD 前面
        putLe32(loc, 16, 1);
        byte[] e = new byte[22];
        putLe32(e, 0, 0x06054b50);
        putLe16(e, 4, 0);
        putLe16(e, 6, 0);
        putLe16(e, 8, 0xFFFF);
        putLe16(e, 10, 0xFFFF);
        putLe32(e, 12, 0xFFFFFFFF);
        putLe32(e, 16, 0xFFFFFFFF);
        putLe16(e, 20, 0);
        z64.write(rec);
        z64.write(loc);
        z64.write(e);
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        all.write(buf, 0, eocd);
        all.write(z64.toByteArray());
        java.nio.file.Files.write(zip.toPath(), all.toByteArray());
    }

    /**
     * 给指定条目的通用位标记补上 bit0（加密）。真实加密要算密码编码那一套，
     * 而本项目只看这一位决定「这个包打不开是因为加密」，所以夹具只把位写对。
     */
    private static void setEncryptedFlag(File zip, String entryName) throws java.io.IOException {
        byte[] raw = java.nio.file.Files.readAllBytes(zip.toPath());
        byte[] name = entryName.getBytes("UTF-8");
        int[] sigs = {0x04034b50, 0x02014b50};
        int[] flagOff = {6, 8};
        int[] lenOff = {26, 28};
        int[] nameOff = {30, 46};
        for (int s = 0; s < 2; s++) {
            for (int i = 0; i + 4 <= raw.length; i++) {
                if (le32(raw, i) != sigs[s]) {
                    continue;
                }
                int nlen = le16(raw, i + lenOff[s]);
                if (nlen != name.length) {
                    continue;
                }
                boolean hit = true;
                for (int k = 0; k < nlen; k++) {
                    if (raw[i + nameOff[s] + k] != name[k]) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    raw[i + flagOff[s]] |= 0x01;
                }
            }
        }
        java.nio.file.Files.write(zip.toPath(), raw);
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8)
                | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }

    private static void putLe16(byte[] b, int at, int v) {
        b[at] = (byte) (v & 0xFF);
        b[at + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putLe32(byte[] b, int at, long v) {
        for (int i = 0; i < 4; i++) {
            b[at + i] = (byte) ((v >> (8 * i)) & 0xFF);
        }
    }

    private static void putLe64(byte[] b, int at, long v) {
        for (int i = 0; i < 8; i++) {
            b[at + i] = (byte) ((v >> (8 * i)) & 0xFF);
        }
    }

    /**
     * 每个顶层窗口都要挂着同一颗图标。
     *
     * <p>Python 那条线是应用级 setWindowIcon，所有窗口天然跟着走；Swing 没有应用级图标，
     * 只能一个窗口一次 setIconImage——漏掉哪个窗口，用户在那个窗口的标题栏与任务栏上
     * 看到的就是默认那颗 Java 杯，同一个程序看着像两个。所以两条一起钉：
     * 先量真窗口（源图能不能真取到手），再横扫一遍 gui 包源码（新加的窗口类漏挂也在这里红，
     * 不靠人记得去补对撞键）。
     */
    private static void checkWindowIcons(MainFrame frame, List<String> problems) {
        int side = com.excel2mysql.gui.WindowIcons.sideOf(frame);
        if (side < 64) {
            problems.add("主窗口图标没挂上或取小了，任务栏里会糊：" + side);
            return;     // 源图都没了，比下去每一扇窗都是 0，再说一遍没意义
        }
        boolean samplesWasOpen = frame.sqlSamplesShowing();
        frame.showSqlSamples();
        javax.swing.JFrame samples = frame.sqlSamplesWindow();
        int samplesSide = samples == null ? -1
                : com.excel2mysql.gui.WindowIcons.sideOf(samples);
        if (samplesSide != side) {
            problems.add("SQL 示例窗口的图标与主窗口不是同一颗：示例 " + samplesSide
                    + " / 主窗口 " + side);
        }
        if (!samplesWasOpen) {
            frame.closeSqlSamples();
        }
        sweepWindowIconSources(problems);
    }

    /**
     * 横扫 gui 包里每一个顶层窗口类：`extends JFrame`/`extends JDialog` 就得自己挂图标。
     *
     * <p>数出来的窗口类个数也钉住：只查"每个命中的类都挂了图标"，命中规则一旦失效
     * （比如窗口改成 `extends JFrame implements X` 之外的写法），一个都没扫到反而全绿。
     */
    private static void sweepWindowIconSources(List<String> problems) {
        java.io.File dir = new java.io.File("app/src/main/java/com/excel2mysql/gui");
        java.io.File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null) {
            problems.add("窗口图标源码横扫没跑：找不到 " + dir.getPath()
                    + "（这一步要在 java/ 目录下跑）");
            return;
        }
        java.util.Arrays.sort(files);
        int top = 0;
        for (java.io.File f : files) {
            if (!f.getName().endsWith(".java")) {
                continue;
            }
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                problems.add("读不到窗口源码 " + f.getName() + "：" + e);
                continue;
            }
            if (!code.contains("extends JFrame") && !code.contains("extends JDialog")) {
                continue;
            }
            top++;
            if (!code.contains("WindowIcons.apply(this)")) {
                problems.add(f.getName() + " 是顶层窗口却没挂应用图标，"
                        + "标题栏与任务栏会是默认 Java 杯");
            }
        }
        if (top < 9) {
            problems.add("窗口图标源码横扫只点到 " + top + " 个顶层窗口（应有 9 个），"
                    + "多半是命中规则或窗口类的位置变了，这条判据已经管不住");
        }
    }

    /**
     * 进度框那条折行规则的红线：预算、行数、不丢字、截断时留话。
     *
     * <p>结果弹窗已经不再自己折行（正文原样交给 JTextArea 按右边界折），
     * 这套列预算现在只管进度框那一小段文字——进度框尺寸固定、没有滚动条，
     * 折与截都得自己算。它决定用户看得见哪些字，所以除了常量本身，
     * 再钉一遍绝对约束。
     */
    private static void checkPopupWrap(java.util.List<String> problems) {
        // 期望值写死成契约数字：拿常量当期望值的断言是恒真的，
        // 把预算改成 400 它照样绿，洞还留着。常量本身另外钉一条。
        if (com.excel2mysql.gui.PopupText.WRAP_COLS != 72
                || com.excel2mysql.gui.PopupText.MAX_LINES != 24) {
            problems.add("折行预算与上限应当是 72 列 / 24 行，实际 "
                    + com.excel2mysql.gui.PopupText.WRAP_COLS + " 列 / "
                    + com.excel2mysql.gui.PopupText.MAX_LINES + " 行");
        }
        String longLine = "任务异常：读取 C:/数据/很长的一个文件名防空警报试鸣信息"
                + "（dim_air_raid_siren）.xlsx 时失败，内部单元格 D17 的类型与推断结果冲突，"
                + "需要人工确认这一列到底是文本还是数字，详见日志里的完整堆栈信息。";
        StringBuilder tall = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            tall.append("第 ").append(i).append(" 行：这一行本身就不短，")
                    .append("加上中文标点和路径信息会更长一些一些一些。");
            if (i < 30) {
                tall.append('\n');
            }
        }
        String[] samples = {longLine, tall.toString(), "短的一句"};
        for (String src : samples) {
            String out = com.excel2mysql.gui.PopupText.wrap(src);
            String[] lines = out.split("\n", -1);
            if (lines.length > 24) {
                problems.add("折行后仍有 " + lines.length + " 行，超过 24 行上限");
            }
            for (String ln : lines) {
                if (com.excel2mysql.gui.PopupText.width(ln) > 72) {
                    problems.add("折行后有一行超出 72 列预算：" + ln);
                }
            }
            if (lines.length == 24
                    && !lines[lines.length - 1].contains("完整内容见底部日志")) {
                problems.add("行数正好卡在 24 上限，却没告诉用户其余内容在日志里");
            }
            if (!flat(out).startsWith(flat(src).substring(0, Math.min(12,
                    flat(src).length())))) {
                problems.add("折行把开头都改动了：" + out.substring(0, Math.min(40, out.length())));
            }
        }
        // 不截断时内容必须一字不少：去掉换行要和原文对得上
        String shortOut = com.excel2mysql.gui.PopupText.wrap("第一行\n第二行");
        if (!"第一行第二行".equals(flat(shortOut))) {
            problems.add("折行丢了原有的分段：" + shortOut);
        }
    }

    /**
     * 摆出来但不开框的各弹窗：这里只量尺寸。
     *
     * 不开框是故意的——这几个窗是模态的，真开会把自检自己卡住；
     * 而客户区尺寸在构造函数的 pack 里就算完了，不必显示出来才量得到。
     */
    private static java.awt.Window[] fitCheckDialogs(MainFrame frame) {
        java.util.List<java.awt.Window> out = new java.util.ArrayList<java.awt.Window>();
        out.add(new com.excel2mysql.gui.HelpDialog(frame));
        out.add(new com.excel2mysql.gui.AboutDialog(frame));
        out.add(new com.excel2mysql.gui.ProgressDialog(null, "正在解析文件…"));
        if (!frame.results().isEmpty()) {
            out.add(new com.excel2mysql.gui.DDLDialog(frame, frame.tables(), false));
            out.add(new com.excel2mysql.gui.StructureDialog(frame, frame.results(),
                    frame.logPanel()));
        }
        return out.toArray(new java.awt.Window[out.size()]);
    }

    /** 比内容不比排版：弹窗正文按显示列宽折过行，断言里去掉换行再比。 */
    private static String flat(String s) {
        return s == null ? "" : s.replace("\n", "");
    }

    /**
     * 建不出表的输入必须卡在解析阶段。
     *
     * 现场实测抓出来的两类：一张格子都没有的表、以及扩展名是 .xlsx 但内容不是 Excel 的文件。
     * 前者从前会产出 `CREATE TABLE x ( )`，到库里变成一条 SQL 语法错误、退出码归到"导入失败"；
     * 后者连语法错误都没有，直接当空表放过。两边现在都在解析阶段就拒，这里钉住 Java 侧。
     */
    /**
     * 宽表被行上限拦下后按公共字段拆成多个 Excel（core.SplitWide）。
     *
     * <p>判据与 Python 侧 pack_test 的 WS1–WS8 同一批、数字同源：夹具在这里现造
     * （242 列 × 3 行，前两列是键列，其余长文本），拆完再把每一份重新建模——
     * 必须都过线且一次降级都不触发。"拆出来仍然建不出"或"拆出来又被降级一轮"等于没拆。
     */
    /**
     * 降级的两条底线，与 core.infer 的同名规则同口径：
     * ① 主键列不许参加降级——TEXT 当不了主键（建表 1170），降长度会截断键值；
     * ② 怎么降都过不了线的表一列都不改，直接拒绝去拆表，不制造几百条无用降级记录。
     */
    private static void row_fit_guards(java.util.List<String> problems) {
        java.util.List<com.excel2mysql.core.Infer.Column> cols =
                new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        com.excel2mysql.core.Infer.Column pk = new com.excel2mysql.core.Infer.Column();
        pk.field = "bian_ma";
        pk.comment = "\u7f16\u7801";
        pk.type = "VARCHAR(255)";
        pk.primaryKey = true;
        cols.add(pk);
        for (int i = 0; i < 64; i++) {
            com.excel2mysql.core.Infer.Column c = new com.excel2mysql.core.Infer.Column();
            c.field = "zi_duan_" + String.format("%02d", Integer.valueOf(i));
            c.comment = "\u5b57\u6bb5" + String.format("%02d", Integer.valueOf(i));
            c.type = "VARCHAR(255)";
            cols.add(c);
        }
        com.excel2mysql.core.RowFit.Fit f1 = com.excel2mysql.core.RowFit.fit(cols, 16384);
        if (f1.refuse != null || !"VARCHAR(255)".equals(pk.type) || f1.conversions.isEmpty()
                || !f1.conversions.get(0)[1].startsWith("zi_duan")) {
            problems.add("\u4e3b\u952e\u5217\u88ab\u964d\u7ea7\u4e86\uff08TEXT \u5f53\u4e0d\u4e86\u4e3b\u952e\uff0c"
                    + "\u5efa\u8868\u4f1a 1170\uff09\uff1apk=" + pk.type
                    + " conversions=" + f1.conversions.size());
        }

        // 600 个短文本列：全退 TEXT 也要 600×41 字节 > 半页，明知没救就该一列都不改
        java.util.List<com.excel2mysql.core.Infer.Column> hope =
                new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        com.excel2mysql.core.Infer.Column hpk = new com.excel2mysql.core.Infer.Column();
        hpk.field = "dan_wei";
        hpk.comment = "\u5355\u4f4d\u7f16\u7801";
        hpk.type = "VARCHAR(32)";
        hpk.primaryKey = true;
        hope.add(hpk);
        for (int i = 0; i < 600; i++) {
            com.excel2mysql.core.Infer.Column c = new com.excel2mysql.core.Infer.Column();
            c.field = "shuo_ming_" + String.format("%03d", Integer.valueOf(i));
            c.comment = "\u8bf4\u660e" + String.format("%03d", Integer.valueOf(i));
            c.type = "VARCHAR(32)";
            hope.add(c);
        }
        com.excel2mysql.core.RowFit.Fit f2 = com.excel2mysql.core.RowFit.fit(hope, 16384);
        if (f2.refuse == null || !f2.conversions.isEmpty()) {
            problems.add("\u660e\u77e5\u964d\u4e0d\u4e0b\u6765\u8fd8\u767d\u6539\u4e86 "
                    + f2.conversions.size() + " \u5217\uff08\u5e94\u5f53\u4e00\u5217\u90fd\u4e0d\u52a8\uff09\uff1a"
                    + (f2.refuse == null ? "\u6ca1\u62e6\u4e0b" : f2.refuse.reason));
        } else if (!f2.refuse.reason.contains("\u4e3b\u952e\u5217")
                || !f2.refuse.reason.contains("`dan_wei`")) {
            problems.add("\u62e6\u4e0b\u7406\u7531\u6ca1\u70b9\u540d\u88ab\u4fdd\u4f4f\u7684\u4e3b\u952e\u5217\uff1a"
                    + f2.refuse.reason);
        }
    }

    private static void wide_split(MainFrame frame, java.util.List<String> problems) {
        com.excel2mysql.core.Infer.Column key1 = new com.excel2mysql.core.Infer.Column();
        key1.field = "dan_wei_bian_ma";
        key1.comment = "\u5355\u4f4d\u7f16\u7801";
        key1.type = "VARCHAR(32)";
        key1.primaryKey = true;
        com.excel2mysql.core.Infer.Column key2 = new com.excel2mysql.core.Infer.Column();
        key2.field = "shu_ju_ri_qi";
        key2.comment = "\u6570\u636e\u65e5\u671f";
        key2.type = "DATE";
        com.excel2mysql.core.Infer.Column key3 = new com.excel2mysql.core.Infer.Column();
        key3.field = "zhuang_bei_ming_cheng";
        key3.comment = "\u88c5\u5907\u540d\u79f0";
        key3.type = "VARCHAR(64)";
        com.excel2mysql.core.Infer.Column measure = new com.excel2mysql.core.Infer.Column();
        measure.field = "zhi_000";
        measure.comment = "\u6307\u6807";
        measure.type = "DECIMAL(18,6)";

        String[] note = new String[]{""};
        int[] common = com.excel2mysql.core.SplitWide.commonColumns(
                java.util.Arrays.asList(key1, key2, key3, measure), note);
        if (common.length != 3 || !note[0].contains("\u8fde\u7eed 3 \u5217")) {
            problems.add("\u516c\u5171\u5b57\u6bb5\u6ca1\u8ba4\u51fa\u8868\u5934\u6700\u524d\u9762\u90a3\u8fde\u7eed\u51e0\u5217\u952e\u5217\uff1a"
                    + java.util.Arrays.toString(common) + " / " + note[0]);
        }
        com.excel2mysql.core.Infer.Column m1 = new com.excel2mysql.core.Infer.Column();
        m1.field = "zhi";
        m1.comment = "\u6307\u6807";
        m1.type = "INT";
        com.excel2mysql.core.Infer.Column m2 = new com.excel2mysql.core.Infer.Column();
        m2.field = "liang";
        m2.comment = "\u6570\u91cf";
        m2.type = "INT";
        String[] note2 = new String[]{""};
        int[] fallback = com.excel2mysql.core.SplitWide.commonColumns(
                java.util.Arrays.asList(m1, m2), note2);
        if (fallback.length != 1 || fallback[0] != 0 || !note2[0].contains("\u951a\u70b9")) {
            problems.add("\u4e00\u5217\u90fd\u4e0d\u50cf\u952e\u5217\u65f6\u6ca1\u9000\u5316\u6210\u300c\u7b2c\u4e00\u5217\u5f53\u951a\u70b9\u300d\uff1a"
                    + java.util.Arrays.toString(fallback) + " / " + note2[0]);
        }

        // 首列不是键列词（行政区划）时它仍是锚点，后随的键列要继续算公共字段；
        // 以前首列不命中就整段放弃，公共字段只剩第一列，其余拆分表拿不到 join 键
        com.excel2mysql.core.Infer.Column anchor = new com.excel2mysql.core.Infer.Column();
        anchor.field = "xing_zheng_qu_hua";
        anchor.comment = "\u884c\u653f\u533a\u5212";
        anchor.type = "VARCHAR(32)";
        String[] note3 = new String[]{""};
        int[] anchored = com.excel2mysql.core.SplitWide.commonColumns(
                java.util.Arrays.asList(anchor, key1, key2, measure), note3);
        if (!java.util.Arrays.equals(anchored, new int[]{0, 1, 2})
                || !note3[0].contains("\u951a\u70b9")) {
            problems.add("\u9996\u5217\u4e0d\u662f\u952e\u5217\u65f6\u6ca1\u5f53\u951a\u70b9\u5e76\u7ee7\u7eed\u5403\u540e\u968f\u952e\u5217\uff08\u516c\u5171\u5b57\u6bb5\u5e94\u662f 0/1/2\uff09\uff1a"
                    + java.util.Arrays.toString(anchored) + " / " + note3[0]);
        }
        java.util.List<com.excel2mysql.core.Infer.Column> anchoredCols =
                new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        anchoredCols.add(anchor);
        anchoredCols.add(key1);
        anchoredCols.add(key2);
        for (int i = 0; i < 950; i++) {
            com.excel2mysql.core.Infer.Column m = new com.excel2mysql.core.Infer.Column();
            m.field = "zhi_" + String.format("%03d", Integer.valueOf(i));
            m.comment = "\u6307\u6807" + String.format("%03d", Integer.valueOf(i));
            m.type = "DECIMAL(18,6)";
            anchoredCols.add(m);
        }
        com.excel2mysql.core.SplitWide.Plan anchoredPlan =
                com.excel2mysql.core.SplitWide.plan(anchoredCols, 16384);
        boolean anchoredPartsOk = anchoredPlan.ok && !anchoredPlan.parts.isEmpty();
        if (anchoredPartsOk) {
            for (com.excel2mysql.core.SplitWide.Part part : anchoredPlan.parts) {
                if (part.idx.length < 3 || part.idx[0] != 0
                        || part.idx[1] != 1 || part.idx[2] != 2) {
                    anchoredPartsOk = false;
                }
            }
        }
        if (!anchoredPartsOk) {
            problems.add("\u9996\u5217\u5f53\u951a\u70b9\u540e\u62c6\u51fa\u7684\u6bcf\u4e00\u6bb5\u90fd\u8981\u5e26\u516c\u5171\u5b57\u6bb5\uff08\u6bb5\u9996\u5e94\u662f 0/1/2\uff09\uff1a"
                    + (anchoredPlan.ok ? "parts=" + anchoredPlan.parts.size()
                    : anchoredPlan.reason));
        }

        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-split-" + System.nanoTime());
        if (!dir.mkdirs()) {
            problems.add("\u5efa\u5bbd\u8868\u62c6\u5206\u68c0\u67e5\u7684\u4e34\u65f6\u76ee\u5f55\u5931\u8d25\uff1a" + dir.getPath());
            return;
        }
        java.io.File wide = new java.io.File(dir, "\u8d85\u5bbd\u8bf4\u660e\u8868.xlsx");
        org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet("Sheet1");
        org.apache.poi.ss.usermodel.Row head = sh.createRow(0);
        head.createCell(0).setCellValue("\u5355\u4f4d\u7f16\u7801");
        head.createCell(1).setCellValue("\u6570\u636e\u65e5\u671f");
        for (int i = 0; i < 240; i++) {
            head.createCell(2 + i).setCellValue("\u8bf4\u660e" + String.format("%03d", i));
        }
        for (int r = 0; r < 3; r++) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(r + 1);
            row.createCell(0).setCellValue("DW" + String.format("%03d", r));
            row.createCell(1).setCellValue("2026-01-0" + (r + 1));
            for (int i = 0; i < 240; i++) {
                row.createCell(2 + i).setCellValue(
                        "\u8fd9\u662f\u4e00\u6bb5\u6bd4\u8f83\u957f\u7684\u8bf4\u660e\u6587\u5b57\u7528\u6765\u628a\u5217\u5bbd\u6491\u5f00" + i);
            }
        }
        try {
            writeWorkbook(wb, wide);
        } catch (java.io.IOException e) {
            problems.add("\u5bbd\u8868\u62c6\u5206\u68c0\u67e5\u5199\u4e0d\u4e86\u5939\u5177\uff1a" + e);
            return;
        }

        com.excel2mysql.core.TableBuilder tb = new com.excel2mysql.core.TableBuilder(
                new com.excel2mysql.core.Infer(frame.names()), frame.names());
        com.excel2mysql.core.TableBuilder.Result r;
        try {
            r = tb.build(wide.getPath(), null, null);
        } catch (Exception e) {
            problems.add("\u5bbd\u8868\u62c6\u5206\u68c0\u67e5\u8bfb\u4e0d\u4e86\u5939\u5177\uff1a" + e);
            return;
        }
        if (r.rowFit == null || r.rowFit.refuse == null) {
            problems.add("242 \u5217\u957f\u6587\u672c\u8868\u5e94\u5f53\u5728\u5efa\u6a21\u9636\u6bb5\u5c31\u88ab\u884c\u4e0a\u9650\u62e6\u4e0b");
            return;
        }
        com.excel2mysql.core.SplitWide.Plan plan =
                com.excel2mysql.core.ImportParse.autoSplitWide(r, dir.getPath(), null);
        if (plan == null || !plan.ok || plan.parts.size() != 2
                || plan.common.length != 2) {
            problems.add("\u5bbd\u8868\u6ca1\u88ab\u62c6\u6210 2 \u4efd\uff08\u516c\u5171\u5b57\u6bb5 2 \u5217\uff09\uff1a"
                    + (plan == null ? "null" : plan.reason + " parts=" + plan.parts.size()));
            return;
        }
        int[] owns = new int[]{plan.parts.get(0).own.length, plan.parts.get(1).own.length};
        if (owns[0] != 169 || owns[1] != 71) {
            problems.add("\u5207\u5206\u6bb5\u6570\u4e0e Python \u4e0d\u4e00\u81f4\uff08\u5e94\u662f 169 + 71\uff09\uff1a"
                    + java.util.Arrays.toString(owns));
        }
        for (com.excel2mysql.core.SplitWide.Part part : plan.parts) {
            java.io.File f = new java.io.File(part.file);
            if (!f.isFile() || f.length() == 0) {
                problems.add("\u62c6\u51fa\u6765\u7684 Excel \u6ca1\u771f\u5199\u76d8\uff1a" + part.file);
                continue;
            }
            try {
                com.excel2mysql.core.TableBuilder.Result back = tb.build(part.file, null, null);
                if (back.rowFit.refuse != null || !back.rowFit.conversions.isEmpty()) {
                    problems.add("\u62c6\u51fa\u6765\u7684\u4e00\u4efd\u91cd\u5efa\u6a21\u4ecd\u7136\u8fc7\u4e0d\u4e86\u7ebf\u6216\u53c8\u964d\u7ea7\u4e86\uff1a"
                            + f.getName() + " refuse=" + (back.rowFit.refuse != null)
                            + " conversions=" + back.rowFit.conversions.size()
                            + " columns=" + back.table.columns.size());
                }
                if (back.rows.size() != 3 || back.table.columns.size() != part.columns) {
                    problems.add("\u62c6\u51fa\u6765\u7684\u4e00\u4efd\u884c\u6570\u6216\u5217\u6570\u4e0d\u5bf9\uff1a"
                            + f.getName() + " rows=" + back.rows.size()
                            + " columns=" + back.table.columns.size()
                            + " \u5e94\u662f 3 \u884c / " + part.columns + " \u5217");
                }
            } catch (Exception e) {
                problems.add("\u62c6\u51fa\u6765\u7684 Excel \u8bfb\u4e0d\u56de\u6765\uff1a" + f.getName() + " " + e);
            }
        }

        // 结构定义模式的表不拆：字段类型是人工写的承诺
        com.excel2mysql.core.TableBuilder.Result fake =
                new com.excel2mysql.core.TableBuilder.Result();
        com.excel2mysql.core.TableOps.Table structured = r.table.copy();
        structured.source = "structured";
        fake.table = structured;
        fake.rowFit = r.rowFit;
        com.excel2mysql.core.SplitWide.Plan skip =
                com.excel2mysql.core.ImportParse.autoSplitWide(fake, dir.getPath(), null);
        if (skip == null || skip.ok || !skip.reason.contains("\u7ed3\u6784\u5b9a\u4e49")) {
            problems.add("\u7ed3\u6784\u5b9a\u4e49\u6a21\u5f0f\u7684\u8868\u4e0d\u8be5\u81ea\u52a8\u62c6\uff1a"
                    + (skip == null ? "null" : skip.reason));
        }
        deleteTree(dir);
    }

    /**
     * 行数过 {@link com.excel2mysql.core.MetaExport#STREAM_ROWS} 就换流式写。
     *
     * 这条分支是为单表 699050 行（xlsx 上限的 2/3）准备的：POI 的全内存模型在 10 万行
     * 就吃掉 1.6 GB 堆，不换写法必然 OutOfMemory。流式那条路只能写双精度，
     * 所以超过 2^53 的整数改写文本——宁可变文本，也不许静默丢位。
     */
    private static void stream_export(java.util.List<String> problems) {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-stream-" + System.nanoTime());
        if (!dir.mkdirs()) {
            problems.add("建不出流式导出的临时目录：" + dir.getPath());
            return;
        }
        final StringBuilder log = new StringBuilder();
        com.excel2mysql.core.ImportParse.Sink sink = new com.excel2mysql.core.ImportParse.Sink() {
            @Override
            public void setTotal(int total) {
            }

            @Override
            public void step(String label) {
            }

            @Override
            public void advance(int units, String label) {
            }

            @Override
            public void log(String text) {
                log.append(text).append('\n');
            }

            @Override
            public void logError(String text) {
                log.append(text).append('\n');
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
        try {
            int over = com.excel2mysql.core.MetaExport.STREAM_ROWS + 1;
            java.io.File big = new java.io.File(dir, "流式_过阈值.xlsx");
            com.excel2mysql.core.MetaExport.writeTable(
                    streamSource(over), big.getPath(), false, sink);
            java.util.List<com.excel2mysql.core.MetaExport.Dump> d =
                    com.excel2mysql.core.MetaExport.dump(big.getPath());
            if (d.isEmpty() || d.get(0).cells.size() != over + 1) {
                problems.add("流式写出的行数不对（应是表头 + " + over + " 行）："
                        + (d.isEmpty() ? "没有 Sheet" : "" + d.get(0).cells.size()));
            } else {
                Object sfz = d.get(0).cells.get(1).get(1);
                Object id = d.get(0).cells.get(1).get(0);
                if (!(sfz instanceof String) || !"110101199003078877".equals(sfz)) {
                    problems.add("过阈值后 18 位整数没按文本写（会丢低位）：" + sfz);
                }
                if (!(id instanceof java.math.BigInteger)) {
                    problems.add("流式写出的普通整数回读不是整数：" + id);
                }
                if (log.indexOf("按文本写") < 0) {
                    problems.add("大整数转文本没在日志里点名：" + log.toString().trim());
                }
            }

            // 阈值之内仍走全内存那条老路：同一份数据，18 位整数是数值型（与既有夹具一致）
            java.io.File small = new java.io.File(dir, "全内存_阈值内.xlsx");
            com.excel2mysql.core.MetaExport.writeTable(
                    streamSource(com.excel2mysql.core.MetaExport.STREAM_ROWS),
                    small.getPath(), false, sink);
            java.util.List<com.excel2mysql.core.MetaExport.Dump> s =
                    com.excel2mysql.core.MetaExport.dump(small.getPath());
            if (s.isEmpty() || s.get(0).cells.size() != com.excel2mysql.core.MetaExport.STREAM_ROWS + 1) {
                problems.add("阈值内的行数不对：" + (s.isEmpty() ? "没有 Sheet" : "" + s.get(0).cells.size()));
            } else if (s.get(0).cells.get(1).get(1) instanceof String) {
                problems.add("阈值内不该走流式：18 位整数被写成文本了");
            }
        } catch (Exception e) {
            problems.add("流式导出检查跑不下去：" + e);
        }
        deleteTree(dir);
    }

    private static com.excel2mysql.core.MetaExport.Source streamSource(int n) {
        com.excel2mysql.core.MetaExport.Source s = new com.excel2mysql.core.MetaExport.Source();
        s.table = new com.excel2mysql.core.TableOps.Table();
        s.table.tableName = "t_stream";
        s.table.columns = new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        s.table.columns.add(streamCol("id", "BIGINT"));
        s.table.columns.add(streamCol("sfz", "VARCHAR(32)"));
        s.table.columns.add(streamCol("note", "VARCHAR(32)"));
        s.headers = new java.util.ArrayList<Object>(
                java.util.Arrays.asList((Object) "id", "sfz", "note"));
        s.rows = new java.util.ArrayList<java.util.List<Object>>();
        for (int i = 0; i < n; i++) {
            java.util.List<Object> row = new java.util.ArrayList<Object>();
            row.add(Integer.valueOf(i));
            row.add(new java.math.BigInteger("110101199003078877"));
            row.add("第" + i + "行");
            s.rows.add(row);
        }
        return s;
    }

    private static com.excel2mysql.core.Infer.Column streamCol(String field, String type) {
        com.excel2mysql.core.Infer.Column c = new com.excel2mysql.core.Infer.Column();
        c.field = field;
        c.comment = field;
        c.type = type;
        return c;
    }

    private static void checkRejects(MainFrame frame, java.util.List<String> problems) {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-reject-" + System.nanoTime());
        if (!dir.mkdirs()) {
            problems.add("建不出拒绝检查的临时目录：" + dir.getPath());
            return;
        }
        try {
            java.io.File empty = new java.io.File(dir, "空表（rej_empty）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            wb.createSheet("数据");
            writeWorkbook(wb, empty);
            java.io.File header = new java.io.File(dir, "只有表头（rej_header）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb2 =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sh = wb2.createSheet("数据");
            org.apache.poi.ss.usermodel.Row row = sh.createRow(0);
            row.createCell(0).setCellValue("编号");
            row.createCell(1).setCellValue("名称");
            writeWorkbook(wb2, header);
            java.io.File fake = new java.io.File(dir, "假excel（rej_fake）.xlsx");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(fake);
            try {
                fo.write("this is not a workbook".getBytes("UTF-8"));
            } finally {
                fo.close();
            }

            com.excel2mysql.core.TableBuilder tb = new com.excel2mysql.core.TableBuilder(
                    new com.excel2mysql.core.Infer(frame.names()), frame.names());
            expectReject(tb, empty, "没有解析到任何字段", "空表", problems);
            // 只有表头的采集模板**不该**被拒：字段是使用方设计好的表结构，一行没填不是错误。
            // 以前它和真空表一起被"一列不剩"挡掉，现场看到的就是"表头解析不到"
            com.excel2mysql.core.TableBuilder.Result hr = tb.build(header.getPath());
            boolean headNoPk = true;
            for (com.excel2mysql.core.Infer.Column c : hr.table.columns) {
                if (c.primaryKey) {
                    headNoPk = false;
                }
            }
            if (hr.table.columns.size() != 2 || !hr.rows.isEmpty() || !hr.noDataRows
                    || hr.emptyColumns.size() != 2 || !headNoPk) {
                problems.add("只有表头的采集模板应当建得出空表（2 列、0 行、两列都点名、不设主键），"
                        + "实际 列=" + hr.table.columns.size() + " 行=" + hr.rows.size()
                        + " 空列=" + hr.emptyColumns + " 主键=" + (headNoPk ? "无" : "有"));
            }
            expectReject(tb, fake, "不是可识别的 Excel 文件", "假 Excel", problems);

            // 表头整行都没写字：Python 侧 pandas 会把列宽一起丢掉，Java 读表层留着那一行。
            // 两侧要的是同一个结果——照样出表定义、字段名走空表头兜底、没有数据可插
            java.io.File blank = new java.io.File(dir, "空表头模板（rej_blankhead）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb3 =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sh3 = wb3.createSheet("数据");
            org.apache.poi.ss.usermodel.Row r3 = sh3.createRow(0);
            r3.createCell(0);
            r3.createCell(1);
            r3.createCell(2);
            writeWorkbook(wb3, blank);
            com.excel2mysql.core.TableBuilder.Result br = tb.build(blank.getPath());
            java.util.List<String> bf = new java.util.ArrayList<String>();
            for (com.excel2mysql.core.Infer.Column c : br.table.columns) {
                bf.add(c.field);
            }
            boolean blankNoPk = true;
            for (com.excel2mysql.core.Infer.Column c : br.table.columns) {
                if (c.primaryKey) {
                    blankNoPk = false;
                }
            }
            if (!bf.equals(java.util.Arrays.asList("col", "col_2", "col_3"))
                    || !br.rows.isEmpty() || !br.noDataRows || !br.blankHeaders.equals(bf)
                    || !br.emptyColumns.isEmpty() || !blankNoPk) {
                problems.add("表头整行空着应当兜底出 col、col_2、col_3 三列、0 行、不设主键，"
                        + "实际 列=" + bf + " 行=" + br.rows.size()
                        + " 兜底点名=" + br.blankHeaders + " 空列=" + br.emptyColumns);
            }
        } catch (Exception e) {
            problems.add("拒绝检查本身出错：" + e);
        } finally {
            deleteTree(dir);
        }
    }

    private static void writeWorkbook(org.apache.poi.ss.usermodel.Workbook wb,
                                      java.io.File to) throws java.io.IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(to);
        try {
            wb.write(out);
        } finally {
            out.close();
        }
        wb.close();
    }

    private static void expectReject(com.excel2mysql.core.TableBuilder tb, java.io.File f,
                                     String wantInMessage, String what,
                                     java.util.List<String> problems) {
        try {
            tb.build(f.getPath());
            problems.add(what + "没被拒，反而产出了表定义：" + f.getName());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null || !e.getMessage().contains(wantInMessage)) {
                problems.add(what + " 被拒了，但原因说错了：" + e.getMessage());
            }
        } catch (Exception e) {
            problems.add(what + "抛的不是解析类异常：" + e);
        }
    }

    /**
     * 命令行那条路的 --prefix 必须真的改到脚本里落库的表名。
     *
     * 现场实测抓出来的：Java 的 CLI 从前只把前缀加在 Excel 表名上，.sql 原样执行，
     * 同一个命令 Python 线建出 ods_t_x、Java 线建出 t_x——按"加了前缀"去查表会一张也找不到。
     * 这里跑的是 Main.run 本体（dry-run，不连库），断的是那条接线本身：
     * SqlScript.applyPrefix 这个纯函数早有差分覆盖，接线断了它照样是绿的。
     */
    /**
     * 「只写注释也不算」的判据表。Python 侧 pack_test 的 E10/E11 断的是同一张表：
     * 两边判定不一样就会出现一句同样的输入，一边报"执行了 0 条"、另一边报"执行了 1 条"。
     * 字符串里的 -- 不算注释，这一格最容易写错。
     */
    /**
     * 日志面板的并发收口检查：两个线程猛加行，主线程一边读全文一边数行数。
     *
     * 现场是真的两头同时在动——导入在子线程里逐表写日志，界面上有人在翻日志。
     * 少了那把锁，ArrayDeque 会在"遍历到一半被加了行"时抛 ConcurrentModificationException；
     * 这个异常发生在事件分发线程上就会把当次渲染连同后面的刷新一起带走，
     * 用户看到的正是"进度跑到一半，日志不再动了"。
     */
    /** 自检跑在事件分发线程上，不能把 InterruptedException 抛出去，只能就地认下。 */
    private static void quietSleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void checkLogPanelThreadSafe(List<String> problems) {
        final com.excel2mysql.gui.LogPanel panel = new com.excel2mysql.gui.LogPanel();
        final java.util.concurrent.atomic.AtomicReference<String> boom =
                new java.util.concurrent.atomic.AtomicReference<String>(null);
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable writer = new Runnable() {
            @Override
            public void run() {
                try {
                    int i = 0;
                    while (!stop.get()) {
                        panel.append("并发行 " + i, i % 503 == 0);
                        i++;
                    }
                } catch (Throwable t) {
                    boom.compareAndSet(null, "写线程炸了 " + t.getClass().getName());
                }
            }
        };
        Thread a = new Thread(writer, "probe-log-a");
        Thread b = new Thread(writer, "probe-log-b");
        a.start();
        b.start();
        // 读写都得持续到"已经封顶、开始裁最早的行"之后：现场那次
        // ConcurrentModificationException 就是这么来的——遍历到一半，插入那边把 first 动了。
        // 只读到刚封顶就收工正好把最危险的那段跳过去，那样这条守卫就是空枪。
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline && panel.lineCount() < panel.maxLines()) {
            quietSleep(2);
        }
        if (panel.lineCount() < panel.maxLines()) {
            problems.add("日志四秒还没涨到上限，读写重叠没建立起来：" + panel.lineCount());
        }
        // 封顶之后再让两边对着跑一段：写侧继续加（每加一行都要裁一行），
        // 读侧全程在遍历同一份条目
        final java.util.concurrent.atomic.AtomicBoolean readStop =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        List<Thread> readers = new ArrayList<Thread>();
        for (int r = 0; r < 2; r++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!readStop.get()) {
                            panel.text();
                            panel.isErrorLine("并发行 1509");
                            panel.lineCount();
                        }
                    } catch (Throwable th) {
                        boom.compareAndSet(null, "读侧炸了 " + th.getClass().getName()
                                + "：" + String.valueOf(th.getMessage()).replace((char) 10, ' '));
                    }
                }
            }, "probe-log-read");
            readers.add(t);
            t.start();
        }
        quietSleep(1200);
        readStop.set(true);
        stop.set(true);
        for (Thread t : readers) {
            try {
                t.join(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                boom.compareAndSet(null, "读线程没收口");
            }
        }
        try {
            a.join(5000);
            b.join(5000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            boom.compareAndSet(null, "join 被中断，写线程没收口");
        }
        if (boom.get() != null) {
            problems.add("日志面板并发下不成立：" + boom.get());
        }
        if (panel.lineCount() != panel.maxLines()) {
            problems.add("日志没裁到上限：" + panel.lineCount() + " / " + panel.maxLines());
        }
        if (panel.text().split("\n").length != panel.lineCount()) {
            problems.add("text() 数出来的行数与 lineCount() 不一致："
                    + panel.text().split("\n").length + " vs " + panel.lineCount());
        }
        panel.clearLog();
        if (panel.lineCount() != 0) {
            problems.add("清空后还有 " + panel.lineCount() + " 行");
        }
    }

    private static void checkCommentOnly(List<String> problems) {
        String[][] table = {
                {"-- 只是一句注释", "0"},
                {"# 老式行注释", "0"},
                {"/* 纯块注释 */", "0"},
                {"", "0"},
                {"   \n\t ", "0"},
                {"SELECT 1", "1"},
                {"SELECT '-- x'", "1"},
                {"SELECT `--a` FROM t", "1"},
                {"/* 头注释 */ SELECT 2", "1"},
                {"-- 头注释\nINSERT INTO t VALUES (1)", "1"},
                {"SELECT 'a''b' -- 尾注释", "1"},
                {"INSERT INTO t VALUES ('/* 不是块注释 */')", "1"},
                {"/* 注释; 里有分号 */ ", "0"},
                // 版本注释 /*!NNNNN ...*/ 的正文服务端真会执行：现场那份 MariaDB dump 的
                // SET FOREIGN_KEY_CHECKS=0 就写在这类语句里，判成注释等于外键检查一直开着，
                // 第一句删父表就撞 MySQL 3730。与 pack_test 的 _EXEC_TABLE 同一批格子。
                {"/*!40101 SET NAMES utf8mb4 */", "1"},
                {"/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS,"
                        + " FOREIGN_KEY_CHECKS=0 */", "1"},
                {"-- 抬头注释" + (char) 10 + "/*!40103 SET TIME_ZONE='+00:00' */", "1"},
                // 只在客户端生效的两种写法发给服务端等于空查询，两条通道都不算一句
                {"/*M!999999\\- enable the sandbox mode */", "0"},
                {"/*!999999\\- enable the sandbox mode */", "0"},
        };
        for (int i = 0; i < table.length; i++) {
            boolean got = com.excel2mysql.core.SqlScript.hasExecutableContent(table[i][0]);
            boolean want = "1".equals(table[i][1]);
            if (got != want) {
                problems.add("第 " + (i + 1) + " 格判错：" + table[i][0].replace((char) 10, ' ')
                        .replace((char) 9, 'T') + " 应为 " + want + "，实际 " + got);
            }
        }
        List<String> kept = com.excel2mysql.core.SqlScript.executableStatements(
                "-- a" + (char) 10 + "; /* b */ ; SELECT 1;");
        if (kept.size() != 1 || !"SELECT 1".equals(kept.get(0))) {
            problems.add("执行器的切句没把纯注释片段剔掉：" + kept);
        }
        // 普通通道（第二页与命令行）以前只认得到引号和行注释，把 /*!NNNNN ...*/ 当纯注释丢了：
        // 同一份脚本走 dump 通道 1382 条、走普通通道只剩 679 条，丢掉的正是导出工具写的那几十句
        // SET NAMES / SET FOREIGN_KEY_CHECKS=0 / SET TIME_ZONE。两条通道必须用同一份内容判据。
        List<String> ver = com.excel2mysql.core.SqlScript.executableStatements(
                "/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS,"
                        + " FOREIGN_KEY_CHECKS=0 */;" + (char) 10
                        + "/*!40101 SET NAMES utf8mb4 */;" + (char) 10
                        + "/*M!999999\\- enable the sandbox mode */;" + (char) 10
                        + "DROP TABLE IF EXISTS `p`;" + (char) 10);
        if (!ver.equals(java.util.Arrays.asList(
                "/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS,"
                        + " FOREIGN_KEY_CHECKS=0 */",
                "/*!40101 SET NAMES utf8mb4 */",
                "DROP TABLE IF EXISTS `p`"))) {
            problems.add("普通通道没留着版本注释（dump 自带的 SET 要真发给服务端）：" + ver);
        }
    }

    /** 整库 dump：识别看导出抬头/结尾，切句认 DELIMITER 与版本注释，只有删库语句跳过不执行。 */
    private static void checkDumpScript(List<String> problems) {
        String nl = String.valueOf((char) 10);
        String dump = "-- MySQL dump 10.13  Distrib 8.0.36, for Win64 (x86_64)" + nl
                + "/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;" + nl
                + "/*M!999999\\- enable the sandbox mode */;" + nl
                + "CREATE DATABASE /*!32312 IF NOT EXISTS*/ `db1`;" + nl
                + "USE `db1`;" + nl
                + "DROP TABLE IF EXISTS `t1`;" + nl
                + "CREATE TABLE `t1` (`id` int);" + nl
                + "INSERT INTO `t1` VALUES (1);" + nl
                + "DELIMITER ;;" + nl
                + "CREATE TRIGGER `trg` BEFORE INSERT ON `t1` FOR EACH ROW" + nl
                + "BEGIN" + nl
                + "  SET @a = 1;" + nl
                + "END;;" + nl
                + "DELIMITER ;" + nl
                + "-- Dump completed on 2026-10-25 10:00:00" + nl;
        if (!com.excel2mysql.core.SqlScript.isDumpScript(dump)) {
            problems.add("带 mysqldump 抬头的脚本没被识别成整库 dump");
        }
        if (com.excel2mysql.core.SqlScript.isDumpScript(
                "CREATE TABLE t (id INT);" + nl + "INSERT INTO t VALUES (1);" + nl)) {
            problems.add("手写建表脚本被误判成整库 dump");
        }
        if (!com.excel2mysql.core.SqlScript.isDumpScript(
                "SELECT 1;" + nl + "-- Dump completed on 2026-10-25 10:00:00" + nl)) {
            problems.add("只有结尾标记的 dump 没被识别出来");
        }
        List<String> stmts = com.excel2mysql.core.SqlScript.dumpStatements(dump);
        if (stmts.size() != 7) {
            problems.add("dump 切句条数不对（应 7，版本注释保留、沙箱标记与普通注释剔除）："
                    + stmts.size() + " → " + stmts);
        }
        int trigger = 0;
        for (String s : stmts) {
            if (s.contains("CREATE TRIGGER")) {
                trigger++;
                if (!s.contains("END")) {
                    problems.add("DELIMITER ;; 的触发器没切成完整一条：" + s);
                }
            }
            if ("END".equals(s)) {
                problems.add("DELIMITER 段被切断，END 成了一条独立语句");
            }
            if (s.startsWith("/*M!") || "-- 注释".equals(s)) {
                problems.add("dump 切句里混进了不该执行的注释：" + s);
            }
        }
        if (trigger != 1) {
            problems.add("触发器应只有一条，实际 " + trigger);
        }
        if (!"".equals(com.excel2mysql.core.SqlScript.dumpSkipReason(
                "CREATE DATABASE /*!32312 IF NOT EXISTS*/ `db1`"))
                || !"".equals(com.excel2mysql.core.SqlScript.dumpSkipReason(
                        "--" + nl + "-- Current Database: `db1`" + nl + "--" + nl + nl
                        + "CREATE DATABASE /*!32312 IF NOT EXISTS*/ `db1`"))
                || !"DROP DATABASE".equals(com.excel2mysql.core.SqlScript.dumpSkipReason(
                        "/*!40000 DROP DATABASE IF EXISTS `db1`*/"))
                || !"".equals(com.excel2mysql.core.SqlScript.dumpSkipReason("USE `db1`"))
                || !"".equals(com.excel2mysql.core.SqlScript.dumpSkipReason(
                        "INSERT INTO `t1` VALUES (1)"))) {
            problems.add("dump 里只有删库语句该跳过：建库或切库被误拦，或者删库没拦住");
        }
        if (!"db1".equals(com.excel2mysql.core.SqlScript.dumpDatabaseSwitch("USE `db1`"))
                || !"db1".equals(com.excel2mysql.core.SqlScript.dumpDatabaseSwitch(
                        "-- c" + nl + "USE `db1`;"))
                || !"a-b.c".equals(com.excel2mysql.core.SqlScript.dumpDatabaseSwitch(
                        "USE `a-b.c` /* x */;"))
                || !"plain".equals(com.excel2mysql.core.SqlScript.dumpDatabaseSwitch(
                        "USE plain;"))
                || !"".equals(com.excel2mysql.core.SqlScript.dumpDatabaseSwitch(
                        "CREATE DATABASE /*!32312 IF NOT EXISTS*/ `db1`"))
                || !"".equals(com.excel2mysql.core.SqlScript.dumpDatabaseSwitch(
                        "/*!40000 DROP DATABASE IF EXISTS `db1`*/"))) {
            problems.add("dump 的切库语句没取出库名，或者把建库与删库当成了切库");
        }
        if (!com.excel2mysql.core.SqlScript.hasExecutableContent(
                "/*!40101 SET NAMES utf8 */")
                || com.excel2mysql.core.SqlScript.hasExecutableContent(
                        "/*M!999999\\- enable the sandbox mode */")
                || com.excel2mysql.core.SqlScript.hasExecutableContent("-- 注释")) {
            problems.add("两条通道共用的内容判据不对：版本注释要算，沙箱标记与行注释不算");
        }
        // 两条通道现在只该有一份判据：普通通道再单独写一套就会像这次一样把
        // dump 自带的 SET FOREIGN_KEY_CHECKS=0 当注释丢掉（现场那条 MySQL 3730）。
        // 这里挑一段没有 DELIMITER 的 dump——两条通道的差别就只剩"什么算一句"了。
        String noDelim = "-- MySQL dump 10.13  Distrib 8.0.36" + nl
                + "/*!40101 SET NAMES utf8mb4 */;" + nl
                + "/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS,"
                + " FOREIGN_KEY_CHECKS=0 */;" + nl
                + "/*M!999999\\- enable the sandbox mode */;" + nl
                + "DROP TABLE IF EXISTS `t1`;" + nl
                + "-- Dump completed" + nl;
        List<String> regular = com.excel2mysql.core.SqlScript.executableStatements(noDelim);
        List<String> byDump = com.excel2mysql.core.SqlScript.dumpStatements(noDelim);
        if (!regular.equals(byDump)) {
            problems.add("同一段没有 DELIMITER 的 dump，两条通道数出的句子不一样："
                    + regular + " / " + byDump);
        }
        if (regular.size() != 3) {
            problems.add("两条通道应都留着两句版本注释加一句 DROP，实际 " + regular.size()
                    + " 句：" + regular);
        }
    }

    private static void checkSqlCliPrefix(java.util.List<String> problems) {        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-sqlprefix-" + System.nanoTime());
        if (!dir.mkdirs()) {
            problems.add("建不出脚本前缀检查的临时目录：" + dir.getPath());
            return;
        }
        try {
            java.io.File sql = new java.io.File(dir, "cli_prefix_probe.sql");
            String body = "DROP TABLE IF EXISTS `t_a`;" + (char) 10
                    + "CREATE TABLE `t_a` (`id` INT NOT NULL, `v` VARCHAR(8), PRIMARY KEY (`id`));"
                    + (char) 10
                    + "INSERT INTO `t_a` VALUES (1, 'x');" + (char) 10;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(sql);
            try {
                fo.write(body.getBytes("UTF-8"));
            } finally {
                fo.close();
            }
            String with = captureMainRun(new String[]{"--dry-run", "--prefix", "clix_",
                    sql.getPath()});
            String bare = captureMainRun(new String[]{"--dry-run", sql.getPath()});
            wantIn(with, "脚本载入 | cli_prefix_probe.sql | CREATE TABLE 1 处 / 表 1 张：t_a(1)",
                    "脚本载入没报出 CREATE 处数与表名", problems);
            wantIn(with, "+ [OK] 脚本前缀 | cli_prefix_probe.sql | 改写 1 张表：t_a→clix_t_a（3 处）",
                    "CLI 没把 --prefix 用到 .sql 上（表名没改写）", problems);
            wantIn(with, "      DROP TABLE IF EXISTS `clix_t_a`",
                    "预览里打印的还是没改写的语句", problems);
            wantIn(with, "待执行 3 条语句（本次没有连库）", "脚本预览没报条数", problems);
            wantIn(with, "+ [OK] 脚本预览完成 | 共 1 个脚本未执行（本次没有连接 MySQL）",
                    "脚本预览完成没记账", problems);
            if (with.contains("+ [OK] SCRIPT |")) {
                problems.add("dry-run 把脚本执行到库了：" + tail(with));
            }
            wantIn(bare, "      DROP TABLE IF EXISTS `t_a`",
                    "不加 --prefix 时脚本原样预览失败", problems);
            if (bare.contains("脚本前缀")) {
                problems.add("没给 --prefix 却报了脚本前缀改写");
            }
        } catch (Exception e) {
            problems.add("脚本前缀检查本身出错：" + e);
        } finally {
            deleteTree(dir);
        }
    }

    private static void wantIn(String haystack, String needle, String what,
                               java.util.List<String> problems) {
        if (haystack == null || !haystack.contains(needle)) {
            problems.add(what + "；缺" + needle);
        }
    }

    /** 在进程内跑一次命令行入口并收走它的 stdout。 */
    private static String captureMainRun(String[] argv) {
        java.io.PrintStream real = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buf, true, "UTF-8"));
            Main.run(argv);
        } catch (Exception e) {
            return "!!捕获失败：" + e;
        } finally {
            System.setOut(real);
        }
        try {
            return new String(buf.toByteArray(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "!!UTF-8 不可用";
        }
    }

    /**
     * 超出 BIGINT 的整数必须原样发给库，由 MySQL 判越界。
     *
     * 生产前实测抓出来的：驱动收到 BigInteger 走的是 longValue()，
     * 9223372036854775808 被截成 -9223372036854775808 存进库，
     * 导入日志写着"成功"，数已经在库里了——这种错现场查不出来，只能事后对账才发现。
     */
    private static void checkBigIntWire(java.util.List<String> problems) {
        Object over = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("9223372036854775808"));
        if (!(over instanceof java.math.BigDecimal)) {
            problems.add("超出 BIGINT 的整数没按原值发出去，应为 BigDecimal：" + over);
        }
        if (String.valueOf(over).startsWith("-")) {
            problems.add("超出 BIGINT 的整数被截成了负数发进库：" + over);
        }
        Object in = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("12345678901234"));
        if (!Long.valueOf(12345678901234L).equals(in)) {
            problems.add("范围内的整数没走 Long 这条路：" + in);
        }
        Object max = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("9223372036854775807"));
        if (!Long.valueOf(Long.MAX_VALUE).equals(max)) {
            problems.add("BIGINT 上界被误判成越界：" + max);
        }
        Object min = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("-9223372036854775808"));
        if (!Long.valueOf(Long.MIN_VALUE).equals(min)) {
            problems.add("BIGINT 下界被误判成越界：" + min);
        }
        Object under = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("-9223372036854775809"));
        if (!(under instanceof java.math.BigDecimal)) {
            problems.add("低于 BIGINT 下界的整数没按原值发出去：" + under);
        }
    }

    /**
     * 1264 回归：推断给出的列必须装得下实测数据。
     *
     * 现场报错 "Out of range value for column 'unit_price_amount'"，根因有三条：
     * 浮点整数（2200000000.0）解析失败被当成没溢出 → INT；DECIMAL 总精度被压到 30，
     * 把实测的 14 位整数挤成 12 位；18 位金额字符串先转 double → 1e18 多一位。
     * 三条都要在自检里各断一次，缺一条就还会回来。
     */
    private static void checkInfer1264(com.excel2mysql.core.Names names,
                                       List<String> problems) {
        com.excel2mysql.core.Infer infer = new com.excel2mysql.core.Infer(names);

        List<java.util.List<Object>> big = new ArrayList<java.util.List<Object>>();
        big.add(Arrays.<Object>asList(Double.valueOf(1.0)));
        big.add(Arrays.<Object>asList(Double.valueOf(2.2e9)));
        String t = infer.inferColumns(Arrays.<Object>asList("order_id"), big).get(0).type;
        if (!"BIGINT".equals(t)) {
            problems.add("浮点整数超 INT 没判成 BIGINT（旧版 int(str(v)) 解析失败漏判）：" + t);
        }

        List<java.util.List<Object>> money = new ArrayList<java.util.List<Object>>();
        money.add(Arrays.<Object>asList(Double.valueOf(1e-18)));
        money.add(Arrays.<Object>asList(Double.valueOf(12345678901234.5)));
        com.excel2mysql.core.Infer.Column mc =
                infer.inferColumns(Arrays.<Object>asList("unit_price_amount"), money).get(0);
        if (!"DECIMAL(32,18)".equals(mc.type)) {
            problems.add("金额列实测 14 位整数 + 18 位小数没得到 DECIMAL(32,18)：" + mc.type);
        }

        List<java.util.List<Object>> hugeStr = new ArrayList<java.util.List<Object>>();
        hugeStr.add(Arrays.<Object>asList("999999999999999999.99"));
        com.excel2mysql.core.Infer.Column hc =
                infer.inferColumns(Arrays.<Object>asList("unit_price_amount"), hugeStr).get(0);
        if (!"DECIMAL(20,2)".equals(hc.type)) {
            problems.add("18 位金额字符串的列宽不对：" + hc.type);
        }
        com.excel2mysql.core.Ddl.Converted cv =
                com.excel2mysql.core.Ddl.convert("999999999999999999.99", hc.type);
        if (!(cv.value instanceof java.math.BigDecimal)
                || !"999999999999999999.99".equals(cv.value.toString())) {
            problems.add("金额字符串没按 BigDecimal 原值转换（float 往返会多一位）：" + cv.value);
        }

        List<java.util.List<Object>> over = new ArrayList<java.util.List<Object>>();
        over.add(Arrays.<Object>asList(Double.valueOf(9.3e18)));
        String ot = infer.inferColumns(Arrays.<Object>asList("id"), over).get(0).type;
        if (!"DECIMAL(21,0)".equals(ot)) {
            problems.add("超出 BIGINT 的整数没改用 DECIMAL(n,0)：" + ot);
        }

        List<java.util.List<Object>> digits = new ArrayList<java.util.List<Object>>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            sb.append('9');
        }
        digits.add(Arrays.<Object>asList(sb.toString()));
        com.excel2mysql.core.Infer.Column dc =
                infer.inferColumns(Arrays.<Object>asList("id"), digits).get(0);
        if (dc.type == null || !dc.type.startsWith("VARCHAR")
                || dc.typeNote == null || !dc.typeNote.contains("DECIMAL")) {
            problems.add("超过 65 位的整数没退文本并留痕：" + dc.type + " / " + dc.typeNote);
        }

        com.excel2mysql.core.Ddl.Converted nan =
                com.excel2mysql.core.Ddl.convert("NaN", "DECIMAL(10,2)");
        if (nan.value != null || nan.error == null) {
            problems.add("NaN 文本没按非法数字拦下：" + nan.value + " / " + nan.error);
        }
        com.excel2mysql.core.Ddl.Converted inf =
                com.excel2mysql.core.Ddl.convert("1e400", "DOUBLE");
        if (inf.value != null || inf.error == null) {
            problems.add("超出 double 范围的值没按非法数字拦下：" + inf.value + " / " + inf.error);
        }
    }

    private static String tail(String text) {
        String s = text == null ? "" : text.replace((char) 10, ' ');
        return s.length() <= 260 ? s : s.substring(s.length() - 260);
    }

    /**
     * 表头语义修正那两层在 Java 线的判据，与 pack_test 的 L15–L26 同一批：
     * 码值列按文本、物质量列按 DECIMAL，两栈各判各的实现，再由 TYPE-DIFF 逐列对。
     */
    private static void checkInferSemantics(com.excel2mysql.core.Names names,
                                            List<String> problems) {
        com.excel2mysql.core.Infer infer = new com.excel2mysql.core.Infer(names);

        com.excel2mysql.core.Infer.Column idc = one(infer, "身份证号",
                "110101199003078877", "320311199901010019");
        if (!"VARCHAR(64)".equals(idc.type) || idc.typeNote == null
                || !idc.typeNote.contains("码值")) {
            problems.add("身份证号全是数字没按码值走文本：" + idc.type + " / " + idc.typeNote);
        }
        // 样本里已经带 X：本来就走文本，这一层不必再留痕（留痕只在"差点被判成数值"时说得上）
        com.excel2mysql.core.Infer.Column idx = one(infer, "身份证号",
                "110101199003078877", "32011119990101001X");
        if (!"VARCHAR(64)".equals(idx.type) || idx.typeNote != null) {
            problems.add("带 X 的身份证号重复留痕或类型不对：" + idx.type + " / " + idx.typeNote);
        }
        if (!"VARCHAR(32)".equals(one(infer, "手机号", "13800138000").type)) {
            problems.add("手机号没落进 32 这一档：" + one(infer, "手机号", "13800138000").type);
        }
        if (!one(infer, "流水号", "100001", "100002").type.startsWith("VARCHAR")) {
            problems.add("流水号被「流水」的钱味抢走，没按码值走文本："
                    + one(infer, "流水号", "100001", "100002").type);
        }
        if (!"DECIMAL(16,2)".equals(one(infer, "账号余额", 100, 200).type)) {
            problems.add("账号余额钱在尾巴上，该按金额而不是码值："
                    + one(infer, "账号余额", 100, 200).type);
        }
        if (!"VARCHAR(255)".equals(one(infer, "卡号类型", (Object) null).type)) {
            problems.add("卡号类型结尾是「类型」，名字后缀没把码值否决掉："
                    + one(infer, "卡号类型", (Object) null).type);
        }
        if (!"DATE".equals(one(infer, "身份证有效期", "2030-01-01", "2031-02-03").type)) {
            problems.add("身份证有效期结尾是日期，不该被码值那一层抢走："
                    + one(infer, "身份证有效期", "2030-01-01", "2031-02-03").type);
        }
        com.excel2mysql.core.Infer.Column sale = one(infer, "销量", 120, 300);
        if (sale.type == null || !sale.type.startsWith("DECIMAL")
                || sale.type.endsWith(",0)" ) || sale.typeNote == null
                || !sale.typeNote.contains("物质量")) {
            problems.add("销量样本为整数没给带小数位的 DECIMAL："
                    + sale.type + " / " + sale.typeNote);
        }
        if (!"INT".equals(one(infer, "交易量", 120, 300).type)) {
            problems.add("交易量数的是笔数，该照旧按 INT："
                    + one(infer, "交易量", 120, 300).type);
        }
        if (!"VARCHAR(32)".equals(one(infer, "短", rep(16)).type)) {
            problems.add("16 字节的文本列没落进新加的 32 档："
                    + one(infer, "短", rep(16)).type);
        }
        if (!"VARCHAR(64)".equals(one(infer, "长一点", rep(33)).type)) {
            problems.add("33 字节的文本列越过了 32 档该给 64："
                    + one(infer, "长一点", rep(33)).type);
        }
        // 16 那一档刻意不收：整列没值时未知列仍按默认 64 建，收到 16 就是拿不准反而收紧
        if (!"VARCHAR(64)".equals(one(infer, "甲", new Object[]{null}).type)) {
            problems.add("空列的默认档被改动：" + one(infer, "甲", new Object[]{null}).type);
        }
        if (!"VARCHAR(255)".equals(one(infer, "省", new Object[]{null}).type)
                || !"VARCHAR(512)".equals(one(infer, "文件名", new Object[]{null}).type)) {
            problems.add("空列按名定档没跟上：" + one(infer, "省", new Object[]{null}).type
                    + " / " + one(infer, "文件名", new Object[]{null}).type);
        }

        // 行上限体检：三条上限都是真库实测翻转点，Python 侧 RS 组判同一批。
        // 旧的"60000 预算"只对齐了 server 那条，漏了 InnoDB 半页与每表列数——
        // 用户就是在那儿撞上"建模完成、库里回 1118/1117"的。
        List<Object> heads = new ArrayList<Object>();
        for (int i = 0; i < 80; i++) {
            heads.add((Object) ("名称" + i));
        }
        List<java.util.List<Object>> wideRows = new ArrayList<java.util.List<Object>>();
        wideRows.add(new ArrayList<Object>(Arrays.asList(new Object[80])));
        List<com.excel2mysql.core.Infer.Column> wideCols = infer.inferColumns(heads, wideRows);
        com.excel2mysql.core.RowFit.Fit wideFit = com.excel2mysql.core.RowFit.fit(wideCols, 16384);
        int texts = 0;
        int keeps = 0;
        int notes = 0;
        for (com.excel2mysql.core.Infer.Column c : wideCols) {
            if ("TEXT".equals(c.type)) {
                texts++;
                if (c.typeNote != null && c.typeNote.contains("退为 TEXT")) {
                    notes++;
                }
            } else if ("VARCHAR(255)".equals(c.type)) {
                keeps++;
            }
        }
        if (com.excel2mysql.core.RowFit.SERVER_ROW_LIMIT != 65535
                || com.excel2mysql.core.RowFit.INNODB_MAX_COLUMNS != 1017
                || com.excel2mysql.core.RowFit.rowPlan(
                        new ArrayList<com.excel2mysql.core.Infer.Column>(), 16384).localLimit != 8126) {
            problems.add("行上限的三条常数与实测不符");
        }
        if (texts != 17 || keeps != 63 || !wideFit.plan.ok) {
            problems.add("80 个空的名称列体检结果不对：退 TEXT " + texts
                    + " 列、留 VARCHAR(255) " + keeps + " 列、过线=" + wideFit.plan.ok);
        }
        if (texts > 0 && notes != texts) {
            problems.add("退成 TEXT 的列里有 " + (texts - notes)
                    + " 列没把留痕改口，还写着按 VARCHAR 建列");
        }

        // 降级顺序：先退最长的那一列，短列一档都不许动
        List<Object> mixHeads = new ArrayList<Object>();
        for (int i = 0; i < 30; i++) {
            mixHeads.add((Object) ("甲" + i));
        }
        for (int i = 0; i < 30; i++) {
            mixHeads.add((Object) ("乙" + i));
        }
        java.util.List<Object> mixRow = new ArrayList<Object>();
        for (int i = 0; i < 30; i++) {
            mixRow.add(repeat('x', 3000));
        }
        for (int i = 0; i < 30; i++) {
            mixRow.add(repeat('y', 20));
        }
        List<java.util.List<Object>> mixRows = new ArrayList<java.util.List<Object>>();
        mixRows.add(mixRow);
        List<com.excel2mysql.core.Infer.Column> mix = infer.inferColumns(mixHeads, mixRows);
        com.excel2mysql.core.RowFit.Fit mixFit = com.excel2mysql.core.RowFit.fit(mix, 16384);
        boolean shortTouched = false;
        for (String[] mv : mixFit.conversions) {
            if (mv[1].startsWith("yi_")) {
                shortTouched = true;
            }
        }
        if (mixFit.refuse != null || !mixFit.plan.ok || shortTouched || mixFit.conversions.isEmpty()) {
            problems.add("最长列先退这条没守住：降级 " + mixFit.conversions.size()
                    + " 列、短列被动=" + shortTouched + "、拦下=" + (mixFit.refuse != null));
        }

        // 拦下两类：需行外的列数堆满半页；列数超 1017
        List<Object> manyHeads = new ArrayList<Object>();
        java.util.List<Object> manyRow = new ArrayList<Object>();
        for (int i = 0; i < 240; i++) {
            manyHeads.add((Object) ("列" + i));
            manyRow.add(repeat('z', 300));
        }
        List<java.util.List<Object>> manyRows = new ArrayList<java.util.List<Object>>();
        manyRows.add(manyRow);
        com.excel2mysql.core.RowFit.Fit manyFit = com.excel2mysql.core.RowFit.fit(
                infer.inferColumns(manyHeads, manyRows), 16384);
        if (manyFit.refuse == null || manyFit.refuse.columns != 240
                || manyFit.refuse.split.size() < 2) {
            problems.add("240 个三百字文本列没被拦下（或没给拆分建议）："
                    + (manyFit.refuse == null ? "不拦" : manyFit.refuse.reason));
        }
        if (com.excel2mysql.core.RowFit.fit(shortIntCols(infer, 1018), 16384).refuse == null) {
            problems.add("1018 列超 InnoDB 每表列数上限却没拦下");
        }
        if (com.excel2mysql.core.RowFit.fit(shortIntCols(infer, 1017), 16384).refuse != null) {
            problems.add("1017 列本在建表线内却被多拦了一列");
        }
    }

    /** n 个短整数列：只看列数上限那一条，类型固定是 INT。 */
    private static List<com.excel2mysql.core.Infer.Column> shortIntCols(
            com.excel2mysql.core.Infer infer, int n) {
        List<com.excel2mysql.core.Infer.Column> cols =
                new ArrayList<com.excel2mysql.core.Infer.Column>();
        for (int i = 0; i < n; i++) {
            com.excel2mysql.core.Infer.Column c = new com.excel2mysql.core.Infer.Column();
            c.field = "c" + i;
            c.comment = "数" + i;
            c.type = "INT";
            cols.add(c);
        }
        return cols;
    }

    private static String repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 一列一判的取样写法：表头 + 若干行值，与 Python 侧 `one()` 同一形状。 */
    private static com.excel2mysql.core.Infer.Column one(com.excel2mysql.core.Infer infer,
                                                          String header, Object... values) {
        List<java.util.List<Object>> rows = new ArrayList<java.util.List<Object>>();
        for (Object v : values) {
            if (v instanceof Object[]) {
                for (Object each : (Object[]) v) {
                    rows.add(Arrays.<Object>asList(each));
                }
            } else {
                rows.add(Arrays.<Object>asList(v));
            }
        }
        return infer.inferColumns(Arrays.<Object>asList((Object) header), rows).get(0);
    }

    private static String rep(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('a');
        }
        return sb.toString();
    }

    private static void task_crash(MainFrame frame, List<String> problems) {
        String dialog = frame.crashMessage(new IllegalStateException("探针异常"));
        if (!dialog.contains("本次操作未完成，详见日志")) {
            problems.add("任务异常的弹窗没交代去查日志：" + dialog.replace((char) 10, ' '));
        }
        // describe 会把异常类型一起带上，只写 message 的话现场根本分不清是哪类意外
        if (!frame.logPanel().isErrorLine("[任务异常] IllegalStateException：探针异常")) {
            problems.add("任务异常没按错误级带上异常类型");
        }
        if (!frame.logPanel().contains("本次操作未完成，请查看日志后重试")) {
            problems.add("任务异常的日志没给下一步动作");
        }
        // 同一句话要经弹窗留痕：只写日志的话，走神的用户根本不知道出了意外
        if (!"任务异常".equals(frame.lastDialogTitle())) {
            problems.add("任务异常没走带标题的弹窗：" + frame.lastDialogTitle());
        }
        if (!frame.lastDialogIsError()) {
            problems.add("任务异常弹窗没用出错图标（Python 是 critical）");
        }
    }

    /** 只记账不渲染的回报口，用来验 core 里那条链的对外行为。 */
    private static final class Recorder implements com.excel2mysql.core.ImportParse.Sink {
        final List<String> lines = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
        int total = -1;
        int advanced;
        /** 大于 0 时模拟"用户按了停止"：走够这么几项就把取消位置起来。 */
        int stopAfter;

        @Override
        public void setTotal(int n) {
            total = n;
        }

        @Override
        public void step(String label) {
            advanced++;
        }

        @Override
        public void advance(int units, String label) {
            advanced += units;
        }

        @Override
        public void log(String text) {
            lines.add(text);
        }

        @Override
        public void logError(String text) {
            errors.add(text);
        }

        @Override
        public boolean cancelled() {
            return stopAfter > 0 && advanced >= stopAfter;
        }

        boolean saw(String needle, boolean errorLevel) {
            for (String s : (errorLevel ? errors : lines)) {
                if (s.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 自检里驱动第二页真入口用的宿主。
     *
     * 库给的是 127.0.0.1:1（必然被拒），任务体原地跑完就带着 connFailed 返回：
     * execute() 里的日志行、覆盖清单、收尾回调都真跑一遍，但不碰任何真库、也不弹模态框。
     * 弹窗一律记进 pops——弹了就该在问题里露出来，而不是静默吃掉。
     */
    private static final class ProbeHost implements com.excel2mysql.gui.SqlPage.Host {
        final com.excel2mysql.core.Config cfg = new com.excel2mysql.core.Config();
        final List<String> pops = new ArrayList<String>();
        /** 任务体最后一次跑的时候经手的回报口：界面日志走不到这里，落库那几行要从这儿查。 */
        Recorder lastSink;
        private final java.io.File root;
        private final java.awt.Frame owner;

        ProbeHost(java.io.File root, java.awt.Frame owner) {
            this.root = root;
            this.owner = owner;
            cfg.host = "127.0.0.1";
            cfg.port = 1;
            cfg.user = "probe";
            cfg.database = "testdb";
        }

        @Override
        public com.excel2mysql.core.Config currentConfig() {
            return cfg;
        }

        @Override
        public boolean connected() {
            return false;
        }

        @Override
        public java.awt.Frame owner() {
            return owner;
        }

        @Override
        public void inform(String text) {
            pops.add(text);
        }

        @Override
        public void inform2(String title, String text) {
            pops.add(title + " | " + text);
        }

        @Override
        public void informError(String title, String text) {
            pops.add(title + " | " + text);
        }

        @Override
        public void runSqlTask(String title,
                               java.util.function.Function<com.excel2mysql.core.ImportParse.Sink,
                                       Object> work,
                               java.util.function.Consumer<Object> onUi) {
            Recorder rec = new Recorder();
            lastSink = rec;
            Object out = work.apply(rec);
            if (onUi != null) {
                onUi.accept(out);
            }
        }

        @Override
        public String exportRoot() {
            return root.getPath();
        }

        @Override
        public void warn(String text) {
            pops.add(text);
        }
    }

    /**
     * 分层入库预检的纯函数：DDL 解析出目标列类型、数值样本判定、FROM 源表识别、聚合识别。
     * 这四件是「按数据修精度」的地基——解析错了预检就睁眼瞎，先在这里钉死。
     */
    private static void layer_gen_precheck(List<String> problems) {
        String ddl = "USE `obj`;\n\nDROP TABLE IF EXISTS `dim_person`;\n\n"
                + "CREATE TABLE `dim_person` (\n"
                + "  `person_id` VARCHAR(64) NOT NULL COMMENT 'dim_person 主键',\n"
                + "  `id` BIGINT DEFAULT NULL COMMENT '主键',\n"
                + "  `qty` DECIMAL(18,4) DEFAULT NULL COMMENT '数量',\n"
                + "  PRIMARY KEY (`person_id`)\n"
                + ") ENGINE=InnoDB;";
        java.util.Map<String, java.util.Map<String, String>> types =
                com.excel2mysql.core.LayerGen.ddlColumnTypes(ddl);
        if (types.size() != 1 || types.get("dim_person") == null
                || !"BIGINT".equals(types.get("dim_person").get("id"))
                || !"DECIMAL(18,4)".equals(types.get("dim_person").get("qty"))
                || !"VARCHAR(64)".equals(types.get("dim_person").get("person_id"))) {
            problems.add("DDL 解析没拿到目标列类型：" + types);
        }
        if (com.excel2mysql.core.LayerGen.sampleFits("BIGINT", "f3a9c2d0e1b2")) {
            problems.add("数值列的样本判定放过了 UUID 字符串");
        }
        if (com.excel2mysql.core.LayerGen.sampleFits("VARCHAR(10)", "12345678901")
                || !com.excel2mysql.core.LayerGen.sampleFits("VARCHAR(10)", "1234567890")
                || !com.excel2mysql.core.LayerGen.sampleFits("VARCHAR(64)", "1234567890")) {
            problems.add("VARCHAR 的长度判定不对：超长要拦、没超要放");
        }
        // 修精度必须给出「能装下样本的最窄类型」，不是一律退 TEXT：
        // 全数字 id → INT/BIGINT，32 位 UUID → VARCHAR 档，超长文本才 TEXT
        java.util.List<String> uuids = java.util.Arrays.asList(
                "f3a9c2d0e1b24d6e8a15c0d3f4e5a6b7", "08b7a6e5d4c3b2a190817263544536f2");
        String uuidType = com.excel2mysql.core.Infer.typeOfSamples(uuids, false);
        if (uuidType == null || !uuidType.startsWith("VARCHAR(") || uuidType.startsWith("TEXT")) {
            problems.add("UUID 样本没落进 VARCHAR 档：" + uuidType);
        }
        if (!"INT".equals(com.excel2mysql.core.Infer.typeOfSamples(
                java.util.Arrays.asList("123", "456"), false))) {
            problems.add("全数字小整数样本没判成 INT：" + com.excel2mysql.core.Infer.typeOfSamples(
                    java.util.Arrays.asList("123", "456"), false));
        }
        if (!"BIGINT".equals(com.excel2mysql.core.Infer.typeOfSamples(
                java.util.Arrays.asList("2200000000", "2200000001"), false))) {
            problems.add("超出 INT 的整数样本没判成 BIGINT");
        }
        String mixed = com.excel2mysql.core.Infer.typeOfSamples(
                java.util.Arrays.asList("123", "f3a9c2d0e1b24d6e8a15c0d3f4e5a6b7"), true);
        if (mixed == null || !mixed.startsWith("VARCHAR(")) {
            problems.add("强制文本判定没落进 VARCHAR 档：" + mixed);
        }
        if (com.excel2mysql.core.Infer.typeOfSamples(
                java.util.Arrays.asList("", null), false) != null) {
            problems.add("样本全空时不该有证据改类型");
        }
        if (!com.excel2mysql.core.LayerGen.sampleFits("BIGINT", "123")
                || !com.excel2mysql.core.LayerGen.sampleFits("DECIMAL(18,4)", "-3.5")
                || !com.excel2mysql.core.LayerGen.sampleFits("VARCHAR(64)", "f3a9c2d0e1b2")
                || !com.excel2mysql.core.LayerGen.sampleFits("BIGINT", null)
                || !com.excel2mysql.core.LayerGen.sampleFits("BIGINT", "")) {
            problems.add("数值/文本样本判定误伤合法值");
        }
        String objStmt = "INSERT INTO `dim_org` SELECT `id` FROM (SELECT DISTINCT `id` "
                + "FROM `ods`.`ods_t_staff_user`) AS `e_ods_t_staff_user`";
        java.util.List<String[]> srcs = com.excel2mysql.core.LayerGen.sourcesOf(objStmt);
        if (srcs.size() != 1 || !"ods".equals(srcs.get(0)[0])
                || !"ods_t_staff_user".equals(srcs.get(0)[1])) {
            problems.add("FROM 源表识别不对（UNION 多支应逐条返回）：" + srcs.size());
        }
        if (com.excel2mysql.core.LayerGen.hasAggregate(objStmt)) {
            problems.add("逐列直取的语句被当成了聚合语句，预检会整个跳过");
        }
        if (!com.excel2mysql.core.LayerGen.hasAggregate(
                "INSERT INTO `ads_general` SELECT COUNT(*) AS `idx_value` FROM `dom`.`dwd_x`")) {
            problems.add("聚合语句没被识别，ads 的采样预检会做无用功");
        }
    }

    /**
     * 三层表注释的纯函数口径（用户 2026-11-14 点名）：中文证据组织成一句话、
     * 不写英文表名、不带「待核/已核」字眼。夹具的源注释故意混中文/英文/空三种形状。
     */
    private static void layer_table_comments(List<String> problems) {
        java.util.List<com.excel2mysql.core.Infer.Column> cols =
                new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        com.excel2mysql.core.Infer.Column fc = new com.excel2mysql.core.Infer.Column();
        fc.field = "qty"; fc.comment = "库存量（吨）"; fc.type = "DECIMAL(18,4)";
        cols.add(fc);
        com.excel2mysql.core.Infer.Column gc = new com.excel2mysql.core.Infer.Column();
        gc.field = "entity_id"; gc.comment = "实体唯一标识，每行由 UUID() 生成"; gc.type = "VARCHAR(64)";
        cols.add(gc);
        java.util.List<com.excel2mysql.core.LayerGen.SourceTable> one =
                new java.util.ArrayList<com.excel2mysql.core.LayerGen.SourceTable>();
        one.add(new com.excel2mysql.core.LayerGen.SourceTable("t_oil", "油料库存表（吨）", cols));
        java.util.List<com.excel2mysql.core.MappingFile.TableMap> noRows =
                new java.util.ArrayList<com.excel2mysql.core.MappingFile.TableMap>();
        // 一源一表：注释就是 ODS 源表注释，不写英文表名
        String d1 = com.excel2mysql.core.LayerGen.domTableComment(
                "dwd_proc_oil", false, one, noRows, cols);
        if (!"油料库存表（吨）".equals(d1)) {
            problems.add("dom 一源一表的注释应取源表注释，实际：" + d1);
        }
        // 多源：由…归集，仍不写英文表名
        java.util.List<com.excel2mysql.core.LayerGen.SourceTable> two =
                new java.util.ArrayList<com.excel2mysql.core.LayerGen.SourceTable>(one);
        two.add(new com.excel2mysql.core.LayerGen.SourceTable("t_oil_log", "油料出入库流水", cols));
        String d2 = com.excel2mysql.core.LayerGen.domTableComment(
                "dwd_multi_oil", true, two, noRows, cols);
        if (!d2.contains("由") || !d2.contains("油料库存表") || !d2.contains("归集")
                || d2.contains("dwd_multi_oil")) {
            problems.add("dom 多源表的注释应写「由…归集」且不带英文表名，实际：" + d2);
        }
        // 源注释为空：用字段注释归纳，也不许落回英文表名
        java.util.List<com.excel2mysql.core.LayerGen.SourceTable> bare =
                new java.util.ArrayList<com.excel2mysql.core.LayerGen.SourceTable>();
        bare.add(new com.excel2mysql.core.LayerGen.SourceTable("t_bare", "", cols));
        String d3 = com.excel2mysql.core.LayerGen.domTableComment(
                "dwd_bare", false, bare, noRows, cols);
        if (!d3.contains("库存量") || d3.contains("dwd_bare") || d3.contains("t_bare")) {
            problems.add("源注释为空时应用字段注释归纳中文注释，实际：" + d3);
        }
        // 源注释是英文、字段注释有中文：仍按中文证据出中文注释（字段注释归纳）
        java.util.List<com.excel2mysql.core.LayerGen.SourceTable> en =
                new java.util.ArrayList<com.excel2mysql.core.LayerGen.SourceTable>();
        en.add(new com.excel2mysql.core.LayerGen.SourceTable("t_en", "Oil Stock", cols));
        String d4 = com.excel2mysql.core.LayerGen.domTableComment(
                "dwd_en", false, en, noRows, cols);
        if (!d4.contains("库存量") || d4.contains("Oil Stock") || d4.contains("dwd_en")) {
            problems.add("源注释英文但字段注释有中文时仍出中文注释，实际：" + d4);
        }
        // 整段无中文证据（源注释、字段注释全是英文）：才放行英文注释
        java.util.List<com.excel2mysql.core.Infer.Column> enCols =
                new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        com.excel2mysql.core.Infer.Column en1 = new com.excel2mysql.core.Infer.Column();
        en1.field = "qty"; en1.comment = "stock qty"; en1.type = "DECIMAL(18,4)";
        enCols.add(en1);
        java.util.List<com.excel2mysql.core.LayerGen.SourceTable> enAll =
                new java.util.ArrayList<com.excel2mysql.core.LayerGen.SourceTable>();
        enAll.add(new com.excel2mysql.core.LayerGen.SourceTable("t_en2", "Oil Stock", enCols));
        String d5 = com.excel2mysql.core.LayerGen.domTableComment(
                "dwd_en2", false, enAll, noRows, enCols);
        if (!"Oil Stock".equals(d5)) {
            problems.add("整段无中文证据时放行英文源注释，实际：" + d5);
        }
        // obj：维度中文名 + 归集来源；空维表注明仅建结构；都不带英文表名
        String o1 = com.excel2mysql.core.LayerGen.objTableComment("dim_material", one, false);
        String o2 = com.excel2mysql.core.LayerGen.objTableComment("dim_org",
                new java.util.ArrayList<com.excel2mysql.core.LayerGen.SourceTable>(), true);
        if (!"物资对象维表（据油料库存表（吨）归集）".equals(o1)) {
            problems.add("obj 表注释应是维度名＋归集来源，实际：" + o1);
        }
        if (!o2.contains("组织机构对象维表") || !o2.contains("仅建表结构") || o2.contains("dim_org")
                || o2.contains("待核") || o2.contains("已核")) {
            problems.add("obj 空维表注释应写中文且注明仅建结构，实际：" + o2);
        }
        // ads：主题名 + 指标匹配情况；「待核/已核」一律清洗
        java.util.Map<String, Integer> byDom = new java.util.LinkedHashMap<String, Integer>();
        byDom.put("dwd_proc_oil", Integer.valueOf(1));
        java.util.Map<String, java.util.List<com.excel2mysql.core.Infer.Column>> dc =
                new java.util.LinkedHashMap<String, java.util.List<com.excel2mysql.core.Infer.Column>>();
        dc.put("dwd_proc_oil", cols);
        java.util.Map<String, com.excel2mysql.core.FieldClass.Ctx> cx =
                new java.util.LinkedHashMap<String, com.excel2mysql.core.FieldClass.Ctx>();
        cx.put("dwd_proc_oil", com.excel2mysql.core.FieldClass.Ctx.of("t_oil", "油料库存表（吨）"));
        String a1 = com.excel2mysql.core.LayerGen.adsTableComment("后勤保障主题", byDom, dc, cx);
        if (!a1.contains("后勤保障主题指标汇总表") || !a1.contains("1 张领域表")
                || !a1.contains("库存量") || a1.contains("dwd_proc_oil")
                || a1.contains("待核") || a1.contains("已核")) {
            problems.add("ads 表注释应写指标匹配情况（样例取字段注释）且不带英文表名/待核字眼，实际：" + a1);
        }
        String a2 = com.excel2mysql.core.LayerGen.adsTableComment("后勤综合主题",
                new java.util.LinkedHashMap<String, Integer>(), dc, cx);
        if (!a2.contains("本次无领域表归入")) {
            problems.add("ads 空主题表注释应写明无领域表归入，实际：" + a2);
        }
        // 清洗：待核/已核/待赋（含括号形态）剥掉，换行压掉（Excel 多行格注释），其余原样
        String s1 = com.excel2mysql.core.LayerGen.scrubReview("油料账（待核）");
        String s2 = com.excel2mysql.core.LayerGen.scrubReview("已核对的预案表");
        String s3 = com.excel2mysql.core.LayerGen.scrubReview("参考总价值\n（吨）");
        String s4 = com.excel2mysql.core.LayerGen.scrubReview("标准代码，合成，待赋正式码");
        if (!"油料账".equals(s1) || s2.contains("已核") || !"参考总价值（吨）".equals(s3)
                || !"标准代码，合成".equals(s4)) {
            problems.add("「待核/已核/待赋」与换行清洗不对：" + s1 + " / " + s2 + " / " + s3 + " / " + s4);
        }
    }

    private static void import_run(List<String> problems) {
        com.excel2mysql.core.Config cfg = new com.excel2mysql.core.Config();
        cfg.host = "127.0.0.1";
        cfg.port = 1;
        cfg.user = "probe";
        cfg.database = "testdb";
        if (!"testdb@127.0.0.1:1".equals(com.excel2mysql.core.ImportRun.targetOf(cfg))) {
            problems.add("目标库串拼错：" + com.excel2mysql.core.ImportRun.targetOf(cfg));
        }
        cfg.database = "";
        if (!"127.0.0.1:1".equals(com.excel2mysql.core.ImportRun.targetOf(cfg))) {
            problems.add("没选库时目标库串该只写主机端口");
        }
        cfg.database = "testdb";

        if (!com.excel2mysql.core.ImportRun.headerLine(2, 7, "t_x", 3)
                .equals("===== [2/7] 处理表 `t_x`（3 行）=====")) {
            problems.add("逐表头一行不对：" + com.excel2mysql.core.ImportRun.headerLine(2, 7, "t_x", 3));
        }
        if (!com.excel2mysql.core.ImportRun.ddlLog("CREATE TABLE x (a INT);")
                .startsWith("-- 建表 DDL --")) {
            problems.add("建表语句没写进日志");
        }
        String cf = com.excel2mysql.core.ImportRun.createFailLog("t_x", "testdb@127.0.0.1:1",
                "SQLException（MySQL 错误码 1064）：语法错", "CREATE TABLE t_x (a INT);");
        if (!cf.contains("建表语句被 MySQL 拒绝") || !cf.contains("失败的建表语句")
                || !cf.contains(com.excel2mysql.core.LogText.ROLLBACK_NOTE)) {
            problems.add("建表失败的日志缺了目标库、语句原文或回滚说明");
        }
        String ifl = com.excel2mysql.core.ImportRun.importFailLog("t_x", "testdb@127.0.0.1:1",
                12, "SQLException：值太长");
        if (!ifl.contains("共 12 行数据未能写入，本表事务已回滚")
                || !ifl.contains(com.excel2mysql.core.LogText.ROLLBACK_NOTE)) {
            problems.add("导入失败没说清楚回滚范围");
        }
        if (!com.excel2mysql.core.ImportRun.importedLog("t_x", 5)
                .equals("[导入完成] `t_x` 共 5 行")) {
            problems.add("导入完成的文案不对");
        }

        Recorder rec = new Recorder();
        List<com.excel2mysql.core.TableBuilder.Result> empty =
                new ArrayList<com.excel2mysql.core.TableBuilder.Result>();
        int units = com.excel2mysql.core.ImportRun.run(cfg, empty, false, rec).size();
        if (units != 0) {
            problems.add("空列表不该有结果");
        }
        if (rec.total != 0) {
            problems.add("空列表的总项数应为 0，实际 " + rec.total);
        }
        if (!rec.saw("[连接失败] 无法连接到 testdb@127.0.0.1:1（用户 probe）", true)) {
            problems.add("连不上库时没写连接失败日志：" + rec.errors);
        }
    }

    /**
     * 「执行修改后ddl脚本到当前库」任务体的连不上库那一支：端口 1 保证秒失败，不需要真库也能把
     * 这段代码真跑一遍——断的是 connFailed 标志、计数在连库前就算准、错误行按错误级报出。
     */
    private static void sql_run_connect_failure(List<String> problems) {
        com.excel2mysql.core.Config cfg = new com.excel2mysql.core.Config();
        cfg.host = "127.0.0.1";
        cfg.port = 1;
        cfg.user = "probe";
        cfg.database = "testdb";
        String nl = String.valueOf((char) 10);
        List<com.excel2mysql.gui.SqlPage.Src> items =
                new ArrayList<com.excel2mysql.gui.SqlPage.Src>();
        items.add(new com.excel2mysql.gui.SqlPage.Src("x/a.sql",
                "CREATE TABLE a (id INT);" + nl + "INSERT INTO a VALUES (1);" + nl));
        Recorder rec = new Recorder();
        com.excel2mysql.gui.SqlPage.RunResult res;
        try {
            res = com.excel2mysql.gui.SqlPage.runScripts(items, cfg, rec);
        } catch (Throwable t) {
            problems.add("连不上库时任务体不该把异常抛给调用方：" + t);
            return;
        }
        if (!res.connFailed) {
            problems.add("连不上库却没标 connFailed，收尾会重复报一遍汇总");
        }
        if (res.ok != 0 || res.fail != 0 || res.skipped != 0) {
            problems.add("连不上库时三个计数都该是 0：" + res.ok + "/" + res.fail
                    + "/" + res.skipped);
        }
        // 总项数在连库之前就要算准：边跑边加会让进度条倒退
        if (rec.total != 2) {
            problems.add("两个脚本的语句计数应为 2，实际 " + rec.total);
        }
        if (!rec.saw("[连接失败] 无法连接到 testdb@127.0.0.1:1（用户 probe），剩余脚本均未执行",
                true)) {
            problems.add("执行任务没报出连不上的地址与用户：" + rec.errors);
        }
        if (rec.saw("[提交] a.sql", false)) {
            problems.add("根本没连上库，却报了提交成功");
        }
    }

    private static void ddl_dialog_and_widgets(MainFrame frame, List<String> files,
                                                List<String> problems) {
        // 前面的用例跑完会把列表清空，这里自己重新加载，不依赖上一个用例留下的状态
        frame.clearAll();
        frame.addFiles(files);
        int n = frame.tables().size();
        if (n == 0) {
            problems.add("DDL 弹窗自检没有表可看");
            return;
        }
        com.excel2mysql.gui.DDLDialog plain =
                new com.excel2mysql.gui.DDLDialog(null, frame.tables(), false);
        try {
            if (plain.tabCount() != n) {
                problems.add("DDL 弹窗应一张表一页：" + plain.tabCount() + " / " + n + " 张表");
            }
            // 页签放不下时那两颗翻页钮：用户 2026-11-02 点名"太小了"，两栈一起从 16 抬到 28。
            // 读的是布局真正取用的 preferredSize，不是自己写的常量
            int scroller = plain.tabScrollerWidth();
            if (scroller < UiScale.px(24)) {
                problems.add("DDL 预览的翻页钮太小：宽 " + scroller
                        + " 物理像素，下限 " + UiScale.px(24) + "（Qt 逻辑 28 那一档）");
            }
            String first = plain.tabText(0);
            if (!first.contains("CREATE TABLE")) {
                problems.add("DDL 弹窗第一页没有建表语句");
            }
            if (first.contains("DROP TABLE")) {
                problems.add("没勾覆盖已存在表，DDL 里却出现 DROP");
            }
            // 按角色上色（用户 2026-10-30 报"色调太单一"）：角色串读真文档，
            // 有没有真画到屏按颜色数像素——设了属性不等于画得出来
            String sig = plain.tabSignature(0);
            if (!sig.contains("L") || !sig.contains("T")
                    || !sig.contains("B") || !sig.contains("W")) {
                problems.add("DDL 预览的角色分布不对（应有字段名/注释/类型/关键字四档）："
                        + sig);
            }
            if (sig.contains("D")) {
                problems.add("没勾覆盖开关，DDL 预览里却出现警示色那一段：" + sig);
            }
            if (inkOf(plain.tabView(0), com.excel2mysql.gui.RichText.LABEL_FG) <= 0) {
                problems.add("DDL 预览里字段名那支分组蓝一个像素都没画出来");
            }
        } finally {
            plain.dispose();
        }
        com.excel2mysql.gui.DDLDialog dropping =
                new com.excel2mysql.gui.DDLDialog(null, frame.tables(), true);
        try {
            if (!dropping.tabText(0).startsWith("DROP TABLE IF EXISTS")) {
                problems.add("勾了覆盖已存在表，DDL 第一页却没有以 DROP 开头");
            }
            if (!dropping.tabSignature(0).startsWith("D")) {
                problems.add("勾了覆盖开关，补上的那句 DROP 没走警示色："
                        + dropping.tabSignature(0));
            }
            if (inkOf(dropping.tabView(0), com.excel2mysql.gui.RichText.DANGER_FG) <= 0) {
                problems.add("DROP 那一行的告警橙没画到屏上");
            }
        } finally {
            dropping.dispose();
        }

        int[] want = {com.excel2mysql.gui.Tables.SERIAL_COL_PX, 320, 200, 280, 80, 90, 160};
        javax.swing.table.TableColumnModel cols = frame.fileTable().getColumnModel();
        if (cols.getColumnCount() != want.length) {
            problems.add("文件表应有 7 列（序号 + 原 6 列），实际 " + cols.getColumnCount());
        }
        for (int i = 0; i < want.length && i < cols.getColumnCount(); i++) {
            int expected = wantPx(want[i]);
            if (cols.getColumn(i).getPreferredWidth() != expected) {
                problems.add("文件表第 " + (i + 1) + " 列宽不是 " + expected + "，实际 "
                        + cols.getColumn(i).getPreferredWidth());
            }
        }
        if (frame.fileTable().getAutoResizeMode()
                != javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN) {
            problems.add("文件表最后一列没有吃掉剩余宽度");
        }
        if (!com.excel2mysql.gui.Tables.isZebra(frame.fileTable())) {
            problems.add("文件表没有斑马纹");
        }
        // 命名规则工具条贴表格上沿：两颗开关与表格同组，但排在表格之前
        java.awt.Container nameBar = frame.initialsBox().getParent();
        java.awt.Container nameHost = nameBar == null ? null : nameBar.getParent();
        if (nameHost == null || !(nameHost.getLayout() instanceof java.awt.BorderLayout)
                || ((java.awt.BorderLayout) nameHost.getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.NORTH) != nameBar
                || !nameHost.isAncestorOf(frame.fileTable())) {
            problems.add("Excel 页命名规则没贴在表格上沿");
        }
        // 底部动作条里只剩覆盖开关与四颗按钮：页内进度条已经拿掉
        if (hasProgressBar(frame.excelActionBar())) {
            problems.add("Excel 页底部动作条里还摆着进度条");
        }
        if (!"primary".equals(com.excel2mysql.gui.Buttons.role(frame.importButton()))) {
            problems.add("Excel 页「开始导入」不是强调按钮");
        }

        javax.swing.SpinnerNumberModel port =
                (javax.swing.SpinnerNumberModel) frame.portSpinner().getModel();
        if (((Number) port.getMinimum()).intValue() != 1
                || ((Number) port.getMaximum()).intValue() != 65535) {
            problems.add("端口框没有 1-65535 的范围约束：" + port.getMinimum() + "-"
                    + port.getMaximum());
        }
        if (((Number) port.getValue()).intValue() != 3306) {
            problems.add("端口默认值不是 3306：" + port.getValue());
        }
        if (frame.currentConfig().port != 3306) {
            problems.add("界面上的端口没进连接配置：" + frame.currentConfig().port);
        }
    }

    private static void progress_copy(List<String> problems) {
        com.excel2mysql.gui.ProgressDialog pd =
                new com.excel2mysql.gui.ProgressDialog(null, "正在解析文件…");
        try {
            if (!"正在解析文件…".equals(pd.getTitle())) {
                problems.add("进度框标题没生效：" + pd.getTitle());
            }
            // Qt 的 resize(480,140) 说的是逻辑像素的客户区，Swing 这边要按 DPI 折算；
            // 量内容面板而不是整个窗，外框不参与比较
            java.awt.Dimension client = pd.getContentPane().getPreferredSize();
            int wantW = (int) Math.round(480 * Math.max(1.0,
                    java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96.0));
            int wantH = (int) Math.round(140 * Math.max(1.0,
                    java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96.0));
            if (client.width != wantW || client.height != wantH) {
                problems.add("进度框客户区应为 " + wantW + "x" + wantH
                        + "（Python 480x140 折算后），实际 " + client);
            }
            if (!"取消".equals(pd.cancelText())) {
                problems.add("取消按钮默认文案不对：" + pd.cancelText());
            }
            pd.setTotal(3);
            pd.step("读盘");
            if (!"进度 1/3 项 · 读盘".equals(pd.statusText())) {
                problems.add("进度文案格式不对：" + pd.statusText());
            }
            // 进度条上印的必须是真百分比：Qt 的 setFormat("%p%") 是格式符，
            // 照抄成 Swing 的 setString 就会把 "%p%" 四个字原样画出来
            if (!"0%".equals(pd.percentText())) {
                problems.add("进度条没印百分比：" + pd.percentText());
            }
            pd.step("识别格式");
            if (pd.percentShown() >= pd.percentTarget() && pd.percentShown() != 0) {
                problems.add("补间起点不应已经等于目标值：" + pd.percentShown());
            }
            pd.step("选主键");
            if (pd.percentShown() != 100) {
                problems.add("跑完最后一项进度条没拉满：" + pd.percentShown());
            }
            // 进度框必须是非模态的：模态框挡在中间时另外两页根本点不动，用户只能干等
            // 或者强关窗口（现场投诉的就是这个）
            if (pd.isModal()) {
                problems.add("进度框还是模态的，任务在跑时别的页点不动");
            }
            // 台账按绝对值刷框：非模态之后框可能还没建好、也可能已经被关掉，
            // 只会被 step 一格推着走的框报不出"现在是 i/N"
            pd.setTotal(10);
            pd.setProgress(4, 10, "第 4/10 条");
            if (pd.getDone() != 4 || !"进度 4/10 项 · 第 4/10 条".equals(pd.statusText())) {
                problems.add("按绝对值刷进度没生效：" + pd.getDone() + " " + pd.statusText());
            }
            pd.setCancelText("停止（当前表完成后中止）");
            if (!"停止（当前表完成后中止）".equals(pd.cancelText())) {
                problems.add("取消按钮改字没生效：" + pd.cancelText());
            }
            pd.requestCancel();
            if (!pd.statusText().contains("当前项做完就停")) {
                problems.add("取消后的话术没说清什么时候停：" + pd.statusText());
            }
            if (!pd.isCancelled()) {
                problems.add("requestCancel 没置取消位，工作线程不会停");
            }
        } finally {
            pd.dispose();
        }
    }

    private static void stop_button(MainFrame frame, List<String> problems) {
        javax.swing.JButton stop = frame.stopButton();
        if (stop == null) {
            problems.add("界面上没有「停止」按钮");
            return;
        }
        if (!"停止".equals(stop.getText())) {
            problems.add("中止按钮文案不对：" + stop.getText());
        }
        if (frame.taskBusy()) {
            problems.add("刚建好窗口就处于有任务在跑的状态");
        }
        if (stop.isEnabled()) {
            problems.add("没任务时可点的「停止」是假按钮");
        }
        // 并发护栏与忙碌态联动：这两条必须走真入口，不能只看构造时的初始值
        if (!frame.startTask() || !frame.taskBusy()) {
            problems.add("startTask 没能占住任务位");
        }
        if (frame.startTask()) {
            problems.add("第二个任务挤得进来，两个任务会踩同一份表列表");
        }
        // 抢输的那一句必须说得出"谁在跑、跑到几成、跑了多久"，只回一句请稍等等于没说
        if (!frame.logPanel().contains("已有任务在跑：")) {
            problems.add("被挡下时没说清是谁在跑：" + tail(frame.logPanel().text()));
        }
        if (!frame.logPanel().contains("%") || !frame.logPanel().contains("秒")) {
            problems.add("被挡下的那句里没有百分比或没有已跑时长："
                    + tail(frame.logPanel().text()));
        }
        if (!frame.lastDialogText().contains("已有任务在跑：")) {
            problems.add("被挡下只写了日志、没弹窗（用户可能正看着别处）："
                    + frame.lastDialogText());
        }
        if (!stop.isEnabled()) {
            problems.add("任务在跑时「停止」是灰的，中止只能等它自己跑完");
        }
        frame.endTask();
        if (frame.taskBusy() || stop.isEnabled()) {
            problems.add("任务结束后没复原忙碌态与停止按钮");
        }
        if (!frame.startTask()) {
            problems.add("endTask 之后任务位没释放，第三次点不动了");
        }
        frame.endTask();
        String beforeStop = frame.logPanel().text();
        frame.stopTask();
        // 比最后一行而不是比行数：日志收口到 2000 行上限后，新说一句会同时挤掉最旧一句，
        // 行数不变但话确实说了，比行数会假红
        if (lastLine(frame.logPanel().text()).equals(lastLine(beforeStop))) {
            problems.add("空点「停止」什么都不说，用户会以为自己按错了");
        }
        if (!frame.logPanel().contains("没有正在执行的任务")) {
            problems.add("空点「停止」的提示语不对");
        }
    }

    /**
     * 「连接并加载库」是一条长任务：握手实测要 2~5 秒，站在事件分发线程上就是整窗假死。
     *
     * <p>用户报的就是这个：点下去界面不动了，看着像软件死了。这一组开真线程 + 真进度框，
     * 验三件事：那颗按钮点下去立刻返回（握手不在本线程）、进度框挂上且标题说清在干什么、
     * 跑完把清单灌进那一格并腾出资源位。判据取"点下去到返回的毫秒数"，
     * 哪天有人把这段改回同步跑，这一条会直接红到 1200 毫秒。
     */
    private static void conn_load_task(MainFrame frame, List<String> problems) throws Exception {
        MainFrame.modalProgress = true;
        MainFrame.fetchOverride = () -> {
            Thread.sleep(1200);
            return Arrays.asList("alpha", "beta");
        };
        final long[] took = {-1};
        final String[] title = {null};
        final boolean[] shown = {false};
        final boolean[] busy = {false};
        frame.hostField().setText("10.0.0.1");
        long t0 = System.currentTimeMillis();
        clickNow(frame.connectButton());
        took[0] = System.currentTimeMillis() - t0;
        com.excel2mysql.gui.ProgressDialog box = frame.lastTaskDialog();
        title[0] = box == null ? null : box.getTitle();
        shown[0] = box != null && box.isVisible();
        busy[0] = frame.taskBusy(MainFrame.RES_CONN);
        if (took[0] > 300) {
            problems.add("点「连接并加载库」在本线程等了 " + took[0]
                    + " 毫秒才返回（握手还站在事件分发线程上，界面等于假死）");
        }
        if (!"正在连接并加载库…".equals(title[0]) || !shown[0]) {
            problems.add("连接任务没挂上进度框或标题不对：" + title[0]);
        }
        if (!busy[0]) {
            problems.add("连接任务没占住「连接区」这一位，第二次点击会挤进来");
        }
        if (!waitTasks(frame, 0, 20)) {
            problems.add("连接任务跑不完：" + frame.runningTasks().size() + " 个还在");
        }
        final int[] loaded = {-1};
        final boolean[] enabled = {false};
        invokeUi(() -> {
            loaded[0] = frame.dbListSize();
            enabled[0] = frame.databaseChooserEnabled();
        });
        if (loaded[0] != 2 || !enabled[0]) {
            problems.add("任务跑完没把清单灌进目标库那一格：条目 " + loaded[0]
                    + " 可选中=" + enabled[0]);
        }
        MainFrame.modalProgress = false;
        MainFrame.fetchOverride = null;
    }

    /**
     * conn.ini 预填连接信息：三种读法各验一条（读到了 / 找到了但读不了 / 压根没有）。
     *
     * 前两条各另起一个窗口——预填发生在建窗那一刻，改了候选路径要重新建窗才走得到那段代码。
     * 第三条不另起窗口：主窗口自己就是在没有 conn.ini 的目录下建起来的，
     * 直接看它启动那一句。一个 MainFrame 连着表格、字体与日志面板，
     * 这里多起三个曾把 JVM 顶到 native OOM（malloc 失败，闸门整步崩）。
     */
    private static void conn_ini(MainFrame frame, List<String> problems) {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "e2m-conn");
        deleteTree(dir);
        dir.mkdirs();
        java.io.File good = new java.io.File(dir, "conn.ini");
        writeText(good.getPath(), "[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
                + "password = pw-file\ndatabase = alpha\n");
        java.io.File bad = new java.io.File(dir, "bad.ini");
        writeText(bad.getPath(), "host = 1.2.3.4\n");      // 没有小节名，读不了
        java.io.File blank = new java.io.File(dir, "blank.ini");
        writeText(blank.getPath(), "[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
                + "password = pw-file\ndatabase =\n");     // 读得到，但没点名目标库
        // 记事本另存会把行尾换成 CRLF，随包那两份文本也发 CRLF：两种行尾必须读出同一组值，
        // 否则口令末尾多一个回车，报出来的却是"连不上库"这种看着像网络问题的错
        java.io.File crlf = new java.io.File(dir, "conn-crlf.ini");
        writeText(crlf.getPath(), "[mysql]\r\nhost = 10.0.0.8\r\nport = 3307\r\nuser = ops\r\n"
                + "password = pw-file\r\ndatabase = alpha\r\n");
        try {
            com.excel2mysql.core.Config lf =
                    com.excel2mysql.core.Config.fromFile(good.getPath());
            com.excel2mysql.core.Config tcrlf =
                    com.excel2mysql.core.Config.fromFile(crlf.getPath());
            if (!lf.host.equals(tcrlf.host) || lf.port != tcrlf.port
                    || !lf.user.equals(tcrlf.user) || !lf.password.equals(tcrlf.password)
                    || !lf.database.equals(tcrlf.database)) {
                problems.add("CRLF 的 conn.ini 与 LF 读出来不一样：" + tcrlf + " / " + lf);
            }
        } catch (java.io.IOException e) {
            problems.add("两种行尾的 conn.ini 有一份读不下去：" + e);
        }
        List<String> keep = MainFrame.connCandidates;
        try {
            MainFrame.connCandidates = java.util.Collections.singletonList(good.getPath());
            MainFrame f1 = new MainFrame();
            com.excel2mysql.core.Config c = f1.currentConfig();
            String log1 = f1.logPanel().text();
            // 目标库不在连接栏那四个输入框里（它是库列表加载出来之后的下拉框），
            // 所以这里只比四个框；配置里的库有没有被记住，看下面那句日志
            if (!"10.0.0.8".equals(c.host) || c.port != 3307 || !"ops".equals(c.user)
                    || !"pw-file".equals(c.password)) {
                problems.add("conn.ini 没把连接信息预填进去：" + c);
            }
            if (!log1.contains("连接信息预填 ops@10.0.0.8:3307/alpha")
                    || !log1.contains("自动发现")) {
                problems.add("预填成功了却没说清来源：" + tail(log1));
            }
            if (log1.contains("pw-file")) {
                problems.add("口令被写进日志正文了：日志里不许出现配置文件里的密码");
            }
            // 启动自动连接：配置里写了库，窗口出来后应自己连上并选中它。
            // 自检不显示窗口，直接调 windowOpened 会调的那支，走的仍是生产代码。
            if (!f1.isAutoConnectEligible()) {
                problems.add("conn.ini 读到了却没被记为「用户明确配过」，启动不会自动连接");
            }
            MainFrame.fetchOverride = () -> Arrays.asList("alpha", "beta");
            f1.autoConnect();
            String autoLog = f1.logPanel().text();
            if (!"alpha".equals(f1.selectedDatabase())
                    || !autoLog.contains("[连接中] 启动自动连接 10.0.0.8:3307（用户 ops）")
                    || !autoLog.contains("[已加载] 目标库按 conn.ini 选中：alpha")) {
                problems.add("有 conn.ini 时启动自动连接没连上/没选中目标库："
                        + tail(autoLog));
            }
            // 清单在手，那一行就不该再说"还没加载"；预填也不算"改过连接信息"
            if (!f1.dbRowHint().isEmpty() || f1.isConnDirty() || f1.dbListSize() != 2) {
                problems.add("加载成功后目标库那一行仍写着提示或清单条数不对："
                        + f1.dbRowHint() + " 脏=" + f1.isConnDirty()
                        + " 条目=" + f1.dbListSize());
            }
            // 目标库已经选上了，第二页点执行不许再说"没设置目标库"：这一页读的是缓存那份
            // 连接快照（自动连接时在库列表加载出来之前就抓的，那时下拉框还是空的），
            // 现场报的正是"状态栏写着目标库 testdb，第二页却说没选库"
            String refusal = f1.sqlPage().execute();
            if (com.excel2mysql.core.LogText.NO_DB_HINT.equals(refusal)) {
                problems.add("目标库已选中（" + f1.selectedDatabase()
                        + "），SQL 页执行仍报未选择目标库");
            }
            // 配置的库不在服务器上：要解释，不能让人以为配置没生效
            f1.applyDatabases(Arrays.asList("beta", "gamma"));
            if (!f1.logPanel().text().contains("[跳过] conn.ini 指定的目标库 alpha 不在服务器上")) {
                problems.add("目标库不在服务器上时没有解释，用户会回头去改配置文件："
                        + tail(f1.logPanel().text()));
            }
            // 现场那一幕：工具开着，用户把程序同级那份 conn.ini 换成了新内容，
            // 再点「刷新配置文件」——要重读同一路径、按新配置重连，不用重开程序
            writeText(good.getPath(), "[mysql]\nhost = 10.0.0.9\nport = 3308\nuser = ops2\n"
                    + "password = pw-new\ndatabase = delta\n");
            final int[] reloadCalls = {0};
            MainFrame.fetchOverride = () -> {
                reloadCalls[0]++;
                return Arrays.asList("delta", "epsilon");
            };
            String reloadMark = f1.logPanel().text();
            f1.reloadConnConfig();
            String reloadLog = f1.logPanel().text();
            com.excel2mysql.core.Config rc = f1.currentConfig();
            if (!"10.0.0.9".equals(rc.host) || rc.port != 3308 || !"ops2".equals(rc.user)
                    || !"pw-new".equals(rc.password) || !"delta".equals(rc.database)) {
                problems.add("刷新配置文件没把新 conn.ini 写进表单：" + rc);
            }
            if (reloadCalls[0] != 1 || !"delta".equals(f1.selectedDatabase())) {
                problems.add("刷新配置文件读取数=" + reloadCalls[0] + "，选中库="
                        + f1.selectedDatabase());
            }
            if (!reloadLog.contains("[刷新] 连接信息已更新 ops2@10.0.0.9:3308/delta")
                    || !reloadLog.contains("[已加载] 目标库按 conn.ini 选中：delta")) {
                problems.add("刷新配置文件没留一句来源或没按新配置选库："
                        + tail(reloadLog.substring(reloadMark.length())));
            }
            if (reloadLog.contains("pw-new")) {
                problems.add("刷新后的口令被写进日志正文了");
            }
            // 还原成后面几条用例要的那份
            writeText(good.getPath(), "[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
                    + "password = pw-file\ndatabase = alpha\n");
            f1.dispose();

            MainFrame.connCandidates = java.util.Collections.singletonList(bad.getPath());
            MainFrame f2 = new MainFrame();
            String log2 = f2.logPanel().text();
            if (!log2.contains("但读不了") || log2.contains("没找到 conn.ini")) {
                problems.add("文件读不了却报成没找到，用户会去找那个并不存在的文件："
                        + tail(log2));
            }
            if (!"127.0.0.1".equals(f2.currentConfig().host)) {
                problems.add("读不了的文件没退回内置默认值：" + f2.currentConfig().host);
            }

            // 没配过就不连：换成一个会计数的取数函数，确认一次都没被调用
            final int[] autoCalls = {0};
            MainFrame.fetchOverride = () -> {
                autoCalls[0]++;
                return Arrays.asList("alpha");
            };
            String before = f2.logPanel().text();
            f2.autoConnect();
            if (autoCalls[0] != 0 || f2.logPanel().text().contains("[连接中] 启动自动连接")
                    || !f2.logPanel().text().equals(before)) {
                problems.add("没有明确配置（文件读不了）时仍然去连了库："
                        + tail(f2.logPanel().text()));
            }
            // 刷新时没有一份能读的配置：手上填的那台机器不许被悄悄抹回内置默认值，
            // 也不许顺手去连一次
            f2.hostField().setText("10.5.5.5");
            final int[] badReloadCalls = {0};
            MainFrame.fetchOverride = () -> {
                badReloadCalls[0]++;
                return Arrays.asList("alpha");
            };
            f2.reloadConnConfig();
            String badReloadLog = f2.logPanel().text();
            if (!"10.5.5.5".equals(f2.currentConfig().host) || badReloadCalls[0] != 0
                    || !badReloadLog.contains("[跳过] 刷新配置文件")) {
                problems.add("刷新配置文件没有可读配置时抹掉了手上的连接信息/仍去连了："
                        + f2.currentConfig().host + " 读取数=" + badReloadCalls[0]
                        + " | " + tail(badReloadLog));
            }
            f2.dispose();

            // 读得到但 database 留空（随包空模板就是这个形状）：不许自动连，
            // 否则新装用户一开界面就看到一条连本机 3306 的失败日志
            MainFrame.connCandidates = java.util.Collections.singletonList(blank.getPath());
            MainFrame f2b = new MainFrame();
            final int[] blankCalls = {0};
            MainFrame.fetchOverride = () -> {
                blankCalls[0]++;
                return Arrays.asList("alpha");
            };
            String blankBefore = f2b.logPanel().text();
            if (f2b.isAutoConnectEligible()) {
                problems.add("conn.ini 没点目标库也算「明确配置」，会去连本机默认库");
            }
            f2b.autoConnect();
            if (blankCalls[0] != 0 || !f2b.logPanel().text().equals(blankBefore)
                    || f2b.logPanel().text().contains("[连接中] 启动自动连接")) {
                problems.add("空模板 conn.ini 仍然触发了自动连接："
                        + tail(f2b.logPanel().text()));
            }
            // 这一态正是新装用户看到的：没自动连、目标库灰着。日志要说清下一步点哪颗，
            // 那一行也要就地写着"要点「连接并加载库」"，否则读起来就是"目标库不给换"
            String blankLog = f2b.logPanel().text();
            if (!blankLog.contains("[提示] 配置里没写目标库，启动不自动连接")) {
                problems.add("配置里没点目标库时只写了\"(未填库)\"，没说下一步："
                        + tail(blankLog));
            }
            if (!MainFrame.DB_HINT_UNLOADED.equals(f2b.dbRowHint())) {
                problems.add("清单没加载时目标库那一行没给就地提示：" + f2b.dbRowHint());
            }
            // 刷成一份没点目标库的配置：只刷新表单，不自动重连（与启动那条判据一致）
            final int[] blankReloadCalls = {0};
            MainFrame.fetchOverride = () -> {
                blankReloadCalls[0]++;
                return Arrays.asList("alpha");
            };
            String blankReloadMark = f2b.logPanel().text();
            f2b.reloadConnConfig();
            String blankReloadLog = f2b.logPanel().text()
                    .substring(blankReloadMark.length());
            if (blankReloadCalls[0] != 0
                    || !blankReloadLog.contains("[刷新] 连接信息已更新 ops@10.0.0.8:3307/(未填库)")
                    || !blankReloadLog.contains("[提示] 配置里没写目标库，未自动重连")) {
                problems.add("刷新成没点目标库的配置后仍去连了，或没把下一步说出口："
                        + tail(blankReloadLog));
            }
            f2b.dispose();

            // 自动连接失败只写日志、不弹模态框：弹出会让人以为软件打不开。
            // 这里若真弹了框，自检会挂死（与本文件其它弹窗路径同一判据）。
            MainFrame.connCandidates = java.util.Collections.singletonList(good.getPath());
            MainFrame.fetchOverride = () -> {
                throw new java.sql.SQLException("(2003) 模拟连不上");
            };
            MainFrame f3 = new MainFrame();
            f3.autoConnect();
            String failLog = f3.logPanel().text();
            if (!failLog.contains("[连接失败] 启动自动连接失败")
                    || !failLog.contains("库列表未加载")
                    || !failLog.contains("设置数据源信息")
                    || f3.databaseChooserEnabled()) {
                problems.add("自动连接失败没有写清原因/重试入口，或把下拉框留成了可用："
                        + tail(failLog));
            }
            if (!failLog.contains("再点「连接并加载库」重试")) {
                problems.add("失败那句只指到「设置数据源信息」（那颗只管展开），"
                        + "没说到真正重试的那颗按钮：" + tail(failLog));
            }
            if (!MainFrame.DB_HINT_UNLOADED.equals(f3.dbRowHint()) || f3.dbListSize() != 0) {
                problems.add("自动连接失败后那一行没写就地提示，或清单没清干净："
                        + f3.dbRowHint() + " 条目=" + f3.dbListSize());
            }
            f3.dispose();

            // 用户现场那一条：先连上一台、拿到清单，再改主机想换数据源，新那台连不上。
            // 这时旧清单来自上一台机器：改过就要说出来，加载失败就得清场——
            // 留着旧库名会一边显示"● 目标库 旧库"一边把写入指向另一台机器
            MainFrame.connCandidates = java.util.Collections.singletonList(good.getPath());
            MainFrame.fetchOverride = () -> Arrays.asList("alpha", "beta");
            MainFrame f4 = new MainFrame();
            f4.autoConnect();
            f4.hostField().setText("10.9.9.9");
            String staleHint = f4.dbRowHint();
            if (!MainFrame.DB_HINT_STALE.equals(staleHint) || !f4.isConnDirty()) {
                problems.add("改过主机后没说清清单已过期：" + staleHint
                        + " 脏=" + f4.isConnDirty());
            }
            MainFrame.fetchOverride = () -> {
                throw new java.sql.SQLException("(2003) 模拟连不上");
            };
            String failure = f4.loadDatabases();
            if (failure == null || f4.databaseChooserEnabled() || f4.dbListSize() != 0
                    || !f4.selectedDatabase().isEmpty()
                    || !MainFrame.DB_HINT_UNLOADED.equals(f4.dbRowHint())) {
                problems.add("手动加载失败没清掉上一台的清单/没回到未加载那句："
                        + f4.dbListSize() + " 当前库=" + f4.selectedDatabase()
                        + " 提示=" + f4.dbRowHint());
            }
            if (f4.connStateText().contains("●")) {
                problems.add("清单已清空，状态栏却还报着绿字当前库：" + f4.connStateText());
            }
            f4.dispose();

            // 没有配置文件那一条看主窗口的启动句：它就是在没有 conn.ini 的目录下建起来的
            String log0 = startupLog;
            if (!log0.contains("没找到 conn.ini") || !"127.0.0.1".equals(frame.currentConfig().host)
                    || !"root".equals(frame.currentConfig().user)) {
                problems.add("没有配置文件时没说清是按默认值起界面：" + tail(log0));
            }
        } finally {
            MainFrame.connCandidates = keep;
            MainFrame.fetchOverride = null;
        }
    }

    /**
     * 按资源占位：同一页互斥、不同页可以一起跑；两页的「生成」各写各的目录，也能一起跑。
     *
     * 上一轮是一个布尔量锁住三页——执行器跑上千条语句时另外两页连点都点不动，
     * 而被挡下的人只看到一句"请稍等"。这里断的是"别的页在跑，这一页照样起得来"这件事本身。
     */
    private static void task_slots(MainFrame frame, List<String> files, List<String> problems) {
        if (!frame.runningTasks().isEmpty()) {
            problems.add("进到这里还留着上一项的任务，后面的判断全废了："
                    + frame.runningTasks().size());
            return;
        }
        if (!frame.startTask(MainFrame.RES_EXCEL)) {
            problems.add("空窗口占不住 Excel 页的资源位");
            return;
        }
        if (!frame.startTask(MainFrame.RES_SQL)) {
            problems.add("Excel 页在跑时 SQL 页的任务被挡下了，三页还是共用一个任务位");
        }
        if (frame.runningTasks().size() != 2) {
            problems.add("两个不同页的任务没能同时在跑：" + frame.runningTasks().size());
        }
        if (!frame.taskBusy(MainFrame.RES_EXCEL) || !frame.taskBusy(MainFrame.RES_SQL)) {
            problems.add("占了资源位却查不出它在跑");
        }
        if (frame.taskBusy(MainFrame.RES_RUN)) {
            problems.add("没被占用的资源位也报成在跑，等于又变回全局一把锁");
        }
        // 同一页的第二个任务必须被挡下，而且要说得出共用的是哪一份数据
        frame.startTask(MainFrame.RES_SQL);
        if (frame.runningTasks().size() != 2) {
            problems.add("同一页的第二个任务挤进来了：" + frame.runningTasks().size());
        }
        if (!frame.lastDialogText().replace(" ", "").contains("SQL脚本改名页的脚本列表")) {
            problems.add("被挡下时没点名共用的是哪一份数据：" + frame.lastDialogText());
        }
        frame.endTask();
        frame.endTask();

        // 两页的「生成」各写各的目录：Excel 页 meta_data_file_年月日、
        // SQL 页 sql_meta_data_file_年月日。目录分开了才谈得上并行——以前同名表会写进
        // 同一个日期目录互相覆盖，那才是两颗按钮必须互斥的真正理由，不是资源位。
        String plainDir = com.excel2mysql.core.MetaExport.outputDir(
                "root", true, "20261022");
        String sqlDir = com.excel2mysql.core.MetaExport.outputDir(
                "root", true, "20261022", com.excel2mysql.core.MetaExport.SQL_DIR_TAG);
        String sqlSimple = com.excel2mysql.core.MetaExport.outputDir(
                "root", false, "20261022", com.excel2mysql.core.MetaExport.SQL_DIR_TAG);
        if (new java.io.File(plainDir).getName().equals(new java.io.File(sqlDir).getName())
                || !new java.io.File(sqlDir).getName().equals("sql_meta_data_file_20261022")
                || !new java.io.File(sqlSimple).getName()
                        .equals("sql_simple_meta_data_file_20261022")) {
            problems.add("两页的生成目录没分开：" + plainDir + " / " + sqlDir + " / " + sqlSimple);
        }
        frame.clearAll();
        frame.addFiles(files);
        if (frame.tables().isEmpty()) {
            problems.add("资源位自检没有可生成的表");
            return;
        }
        // Excel 页占着位的时候，SQL 页的生成照样起得来（两页各写各的目录，不再共用产物位）
        frame.startTask(MainFrame.RES_EXCEL);
        if (!frame.startTask(MainFrame.RES_SQL)) {
            problems.add("两页的生成还在互斥：目录已经分开了，就该能并行");
        }
        frame.endTask();
        frame.endTask();
        if (frame.taskBusy()) {
            problems.add("endTask 没能把资源位还回去");
        }
    }

    /** 自检顺手留下的图：只报路径，不参与判定（锁屏时抓到的是锁屏图，判不了）。 */
    private static final List<String> SHOTS = new ArrayList<String>();
    private static volatile int SHOT_WINDOWS;

    /**
     * 抓一张整屏到内存（不落盘）：写 PNG 要一两秒，不能压在"两条任务都还在跑"那个窗口里。
     * 无显示设备或抓屏被拒时返回 null。
     *
     * 抓之前先把本程序那几个顶层窗口抬到最前：自检不抢焦点，不抬的话拍出来是
     * 桌面上那个聊天窗口（实测踩过一张，颜色数照样过 60，看着像"抓到了"其实没拍到界面）。
     */
    private static java.awt.image.BufferedImage captureNow() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return null;
        }
        try {
            invokeUi(() -> raiseOwnWindows());
            Thread.sleep(400);
            SHOT_WINDOWS = showingWindows();
            return grabScreen();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void raiseOwnWindows() {
        for (java.awt.Frame f : java.awt.Frame.getFrames()) {
            if (f.isShowing()) {
                f.toFront();
            }
        }
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isShowing()) {
                w.toFront();
            }
        }
    }

    private static java.awt.image.BufferedImage grabScreen() throws Exception {
        java.awt.Rectangle bounds = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
        return new java.awt.Robot().createScreenCapture(bounds);
    }

    /**
     * 把内存里那张落盘，返回"路径 + 尺寸 + 抽样颜色数"；空帧（全黑全白）当没抓到。
     *
     * 用例本身跑在无显示设备上也照样绿（进度框那些断言读的是控件状态，不是像素），
     * 所以这一张只是给人眼复核用的，别拿一张白图当"看过了"。
     */
    private static String saveShot(java.awt.image.BufferedImage img, String tag) {
        if (img == null) {
            return null;
        }
        try {
            java.util.Set<Integer> colors = new java.util.HashSet<Integer>();
            for (int y = 0; y < img.getHeight() && colors.size() <= 400; y += 23) {
                for (int x = 0; x < img.getWidth(); x += 23) {
                    colors.add(Integer.valueOf(img.getRGB(x, y)));
                }
            }
            if (colors.size() < 60) {
                return null;
            }
            java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "e2m-shots");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "java-" + tag + ".png");
            javax.imageio.ImageIO.write(img, "png", f);
            return f.getPath() + "（" + img.getWidth() + "x" + img.getHeight()
                    + "，抽样 " + colors.size() + " 色）";
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 真线程那一支：打开进度框那条路，两条真任务同时在跑。
     *
     * 上面的 task_slots 验的是抢位规则（原地跑，没有线程也没有框）；这一支验的是用户那句
     * "多个操作不要互相阻塞"本身——Excel 页在解析八个七千行的表、SQL 页在往盘上写一百五十个
     * 文件，两边都得在动、界面还得点得动。所以整段站在非事件分发线程上：站在 EDT 里睡过去
     * 就等于自己把事件循环掐了，那时候"看着没卡"是假的。
     *
     * 夹具拆成八个小文件而不是一个大文件：解析按阶段计项，一张几十万行的表总共只有 5 项，
     * "读取工作簿"那一项能三秒不动一格，"两条都在推进"就采不到样。
     */
    private static void concurrent_tasks(MainFrame frame, List<String> problems)
            throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-concurrent");
        deleteTree(dir);
        dir.mkdirs();
        // 前面有一支场景会打开示例清单又不会自己收（那颗对话框是 HIDE_ON_CLOSE，
        // forceCloseDialogs 只管消息弹窗），不收的话抓屏拍到的是它、不是两个进度框
        invokeUi(() -> frame.closeSqlSamples());
        List<String> xlsx = new ArrayList<String>();
        // 解析这条要当两格里最长的那个（八个两万行，实测约六秒）：取消、推进取样、
        // 同页拒绝这三步都得在它还没做完的时候做，夹具一旦快过用例就只能报"没验到取消"
        for (int k = 0; k < 8; k++) {
            xlsx.add(bigFixture(new java.io.File(dir, "并发大表" + (char) ('A' + k) + ".xlsx"),
                    20000));
        }
        java.io.File script = new java.io.File(dir, "并发生成.sql");
        StringBuilder sb = new StringBuilder();
        // 生成那一头六百张表（Java 写六百个文件约两秒）。别再往大了加：实测三千张时
        // 每个文件两行日志把事件分发线程灌满了，解析那条从 4 秒拖到 135 秒，
        // 用例量的就成了日志刷屏速度而不是并发
        for (int i = 0; i < 600; i++) {
            sb.append("CREATE TABLE t_par_").append(i)
                    .append(" (id BIGINT NOT NULL COMMENT '编号',")
                    .append(" name VARCHAR(64) COMMENT '名称',")
                    .append(" primary key (id)) ENGINE=InnoDB COMMENT='并发表").append(i)
                    .append("';\n");
        }
        writeText(script.getPath(), sb.toString());
        java.io.File script2 = new java.io.File(dir, "并发被挡.sql");
        writeText(script2.getPath(), "CREATE TABLE t_block_0 (id BIGINT, primary key (id));\n");
        final String genRoot = new java.io.File(dir, "并发输出").getPath();

        MainFrame.modalProgress = true;      // 真那条路：起子线程、挂真框
        try {
            invokeUi(() -> frame.clearAll());
            // 这一步不能等事件分发线程返回：进度框一旦回归模态，invokeAndWait 会和
            // 一个没人点的结果弹窗互相等死（变异抽查实测：整道闸门挂住不出结果）。
            // 也不能拿"台账里有一条"当前提——这份脚本几百毫秒就读完了，
            // 60 毫秒一轮的采样根本抓不到那一瞬（上一版就是这么假红的）。
            // 直接等它的结果：日志里出现这份脚本名，就说明条目已经进了 SQL 页列表。
            fireUi(() -> frame.sqlPage().addFiles(
                    java.util.Collections.singletonList(script.getPath())));
            long loadUntil = System.currentTimeMillis() + 30000;
            boolean loadedScript = false;
            while (System.currentTimeMillis() < loadUntil) {
                loadedScript = frame.sqlPage().logPanel().text().contains("并发生成.sql");
                if (loadedScript) {
                    break;
                }
                closeMessageDialogs();
                Thread.sleep(30);
            }
            if (!loadedScript) {
                problems.add("前置：SQL 页没把这份脚本读进列表，后面那颗生成按钮不会起任务："
                        + tail(frame.sqlPage().logPanel().text()));
                forceCloseDialogs();
                return;
            }
            if (!waitTasks(frame, 0, 30)) {
                problems.add("前置：SQL 页读脚本的任务没收尾：" + taskLine(frame));
                forceCloseDialogs();
                return;
            }

            // 起任务这两步都用 invokeLater 丢过去，不等它：非模态那条路本来就该"点下去就返回"。
            // 用 invokeAndWait 的话，进度框一旦退回模态，调用方会被那颗框按死在这里——
            // 报出来的就是"闸门挂住十五分钟"而不是"用例红了"，证据直接丢掉
            //（变异抽查实测踩过：模态那一版把整道自检拖死，连一行输出都没有）
            fireUi(() -> frame.addFilesAsync(xlsx));                 // A：Excel 页解析
            fireUi(() -> frame.sqlPage().generateExcel(true, genRoot));   // B：SQL 页写盘
            // 25 秒不是"任务要跑这么久"，是给事件分发线程留的余量：两条 invokeLater 排在
            // 解析任务的日志刷新后面，机器上同时跑着别的用例集时，第二个体会被延后好几秒
            // 才执行——八秒的窗口在闸门里红过一次，就是这么红的
            if (!waitTasks(frame, 2, 25)) {
                problems.add("两个真任务没能同时在跑（这一条就是「不互相阻塞」本身）："
                        + taskLine(frame));
                forceCloseDialogs();
                return;
            }
            final List<MainFrame.Task> both = frame.runningTasks();
            MainFrame.Task a = null;
            MainFrame.Task b = null;
            for (MainFrame.Task t : both) {
                if (MainFrame.RES_EXCEL.equals(t.resources[0])) {
                    a = t;
                } else if (MainFrame.RES_SQL.equals(t.resources[0])) {
                    b = t;
                }
            }
            if (a == null || b == null) {
                problems.add("同时在跑的两条任务认不出归属：" + taskLine(frame));
                return;
            }
            // 同一页再点一次该被挡下——这一步要趁两条都还在跑就做，而且排在取样之前：
            // 写盘那条两秒就完了，晚一步点下去资源位已经空出来，"挡下"这件事就根本不会发生
            //（上一轮 flat 空成 "" 就是这个原因）。点的也不是那颗生成按钮——任务在跑时
            // 它本来就是灰的，doClick 空转；再读一份脚本才是用户真会做的动作。
            // 弹窗是模态的，会把事件分发线程按在嵌套循环里，所以这一步用 invokeLater 丢过去，
            // 由这边轮那句话写出来没有，一看到就抄下来，再替用户把弹窗点掉。
            SwingUtilities.invokeLater(() -> frame.sqlPage().addFiles(
                    java.util.Collections.singletonList(script2.getPath())));
            long until = System.currentTimeMillis() + 5000;
            String flat = "";
            while (System.currentTimeMillis() < until) {
                String rec = frame.lastDialogText().replace((char) 10, ' ').replace(" ", "");
                if (rec.contains("已有任务在跑")) {
                    flat = rec;
                    break;
                }
                Thread.sleep(50);
            }
            // 弹窗正文是按 56 列折过存的那一份，长短语会在中间断行；日志那一份又会被
            // 生成任务的刷屏挤掉（每个文件两行）。比之前把空白去掉，两边都不受排版影响
            if (!flat.contains("已有任务在跑")
                    || !flat.contains("SQL脚本改名页的脚本列表")) {
                problems.add("SQL 页第二个动作被挡下时没点名共用的是这一页的脚本列表："
                        + tail(flat));
            }
            if (frame.runningTasks().size() != 2) {
                problems.add("被挡下的那一步改变了在跑的任务数：" + taskLine(frame));
            }
            closeMessageDialogs();

            for (MainFrame.Task t : both) {
                if (t.dialog == null || !t.dialog.isShowing() || t.dialog.isModal()) {
                    problems.add("任务在跑时它那个进度框不在屏上、或是模态的：" + t.describe());
                }
            }
            // 两颗框不许叠在一起：叠了后面那颗的「取消」就点不到，非模态等于白改。
            // 这一步只读边界不抓屏：抓一张 2560x1600 要和桌面窗口管理器抢时间，
            // 实测把下面那颗"界面照旧回应"从 200 毫秒顶到 537 毫秒（上限 500），
            // 取证本身改变被测行为。像素那一路交给 click_other_page_while_busy
            java.awt.Rectangle ra = a.dialog == null ? null : a.dialog.getBounds();
            java.awt.Rectangle rb = b.dialog == null ? null : b.dialog.getBounds();
            if (ra != null && rb != null) {
                java.awt.Rectangle overlap = ra.intersection(rb);
                if (!overlap.isEmpty()) {
                    problems.add("两个进度框叠在一起，后面那颗的「取消」点不到："
                            + ra + " / " + rb + "，主窗口在 ("
                            + frame.getX() + "," + frame.getY() + ")，框尺寸 "
                            + ra.width + "x" + ra.height);
                }
            }
            
            int pa = a.getDone();
            int pb = b.getDone();
            long p0 = System.currentTimeMillis();
            invokeUi(() -> { });
            final long pingMs = System.currentTimeMillis() - p0;
            System.out.println("- [INFO] GUI-SELFTEST | 两条任务在跑时事件分发线程回应一次要 "
                    + pingMs + " 毫秒");
            Thread.sleep(1200);
            int pa2 = a.getDone();
            int pb2 = b.getDone();
            if (pa2 <= pa || pb2 <= pb) {
                // 断的是计项数而不是百分比：六百项的写盘任务里，冷启动头一个文件
                // 就要占掉几百毫秒，窗口给小了会采不到样
                problems.add("两条任务不是都在推进：" + pa + "→" + pa2 + " / "
                        + pb + "→" + pb2);
            }
            // 上限 3000 毫秒（2026-11-03 重标定）。原来卡 800：单独跑这一步健康时是 200
            // 毫秒上下、噪声带上沿 540，看着够用；但同一棵树放进十步链或出包链里，
            // 实测采到 1002 / 1018 / 2332 / 2547 毫秒——事件分发线程那几秒正在消化
            // 六百张表元数据的日志与重绘，队列深度本身能到几十秒（一次采样里量到 31100）。
            // 判据要防的是"界面冻死"，那是另一个数量级：进度框退回模态量出 5221 毫秒、
            // 把抓屏塞进这个时间窗量出 10181 毫秒，两条都仍在门槛之上。
            // 2026-11-06 这一判在 4.3.0 同款代码上连红四次（6579 / 7127 / 7411 / 13680 毫秒），
            // 采样栈指着 gui/LogPanel.render：每条日志各排一次"整篇重排"，六百张表一刷就是
            // 上千次重排。改成排队里只留一次渲染之后实测 352 毫秒，门槛照旧 3000。
            if (pingMs > 3000) {
                problems.add("两条任务在跑时事件分发线程回应一次要 " + pingMs
                        + " 毫秒（界面等于没转）");
            }
            // 掐掉解析那条：Java 这边它是稳的几秒（八个文件 41 项），写盘那条两秒就跑完，
            // 取消要落在还在干活的线程上才有意义。而且这一步趁两条都还在跑就做——
            // 先等一条自然收尾，另一条常在弹窗派发里跟着做完，用例就退化成"没验到取消"
            List<MainFrame.Task> live = frame.runningTasks();
            if (live.size() < 2) {
                problems.add("该有两条在跑时只剩 " + live.size() + " 条：" + taskLine(frame));
                return;
            }
            MainFrame.Task victim = null;
            MainFrame.Task other = null;
            for (MainFrame.Task t : live) {
                if (MainFrame.RES_EXCEL.equals(t.resources[0])) {
                    victim = t;
                } else {
                    other = t;
                }
            }
            if (victim == null) {
                problems.add("没找到还在跑的解析任务：" + taskLine(frame));
                return;
            }
            if (victim.remaining() <= 0) {
                problems.add("取消没落在还在干活的线程上（夹具快过了用例）："
                        + victim.describe());
                return;
            }
            victim.cancel();
            // 等它退出台账，这一段故意不点弹窗：取消后的结果弹窗也是模态的，
            // 用户没点确定那段时间里，这条已经收摊的任务必须先把资源位还出来——
            // 收尾顺序一旦退回"先弹结果再放位"，这里就会等到超时
            long quiet = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < quiet && frame.runningTasks().contains(victim)) {
                Thread.sleep(50);
            }
            if (frame.runningTasks().contains(victim)) {
                problems.add("被掐掉那条的弹窗还开着时，它没把资源位还出来："
                        + victim.describe());
            }
            // 留痕要再等一步：资源位是先还的、收尾日志是随后写的，
            // 只在"退出台账"那一刻读一次必然读到空的（上一轮那条红就是这个意思）
            String own = "";
            long note = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < note) {
                own = victim.logView.text() + " | " + frame.lastDialogText();
                if (own.contains("已取消")) {
                    break;
                }
                closeMessageDialogs();
                Thread.sleep(50);
            }
            if (!own.contains("已取消")) {
                problems.add("被掐掉的那条没如实标注只完成部分：" + tail(own));
            }
            closeMessageDialogs();
            // 没被掐的那条：照常推进到跑完，结果按事实落地
            if (!waitTasks(frame, 0, 90)) {
                problems.add("另一条没能自己跑完：" + taskLine(frame));
            }
            if (other != null) {
                int o1 = other.getDone();
                if (o1 < other.getTotal()) {
                    // 台账已经空了说明这条也走完了收尾；这里再按计项核一遍，
                    // 防止"任务被摘掉但活没干完"这种假收尾。
                    // "有没有继续推进"不在这里断——那件事由取样那一步管，
                    // 这一条只看结果：另一条可能在被掐之前就已经做完了，做完了自然不再推进
                    problems.add("另一条没跑完自己的项数：" + other.describe());
                }
            }
            if (MainFrame.RES_EXCEL.equals(victim.resources[0])
                    && countXlsx(new java.io.File(genRoot)) <= 0) {
                problems.add("取消解析把写盘那条的成果一起弄丢了");
            }
            if (MainFrame.RES_SQL.equals(victim.resources[0]) && frame.tables().size() != 8) {
                problems.add("取消写盘把解析那条的成果一起弄丢了：表 " + frame.tables().size()
                        + " 张");
            }
        } finally {
            MainFrame.modalProgress = false;
            forceCloseDialogs();
        }
    }

    /**
     * 任务在跑时，另外的页真点得动：起一条解析任务，切到第三页点「示例 (?)」，
     * 清单要开出来、事件分发线程要照常回应，那条任务也不能因为这点事被带走。
     *
     * 为什么单开一条而不塞进 concurrent_tasks：并发那一支里两条任务一共只有两三秒，
     * 而建一份 100 条的清单要在事件分发线程上摆一堆控件。实测塞进去之后
     * "界面照旧回应"那一步从 200 毫秒涨到 5.2 秒、解析那条也提前跑完了——
     * 那时候用例量的就成了建窗速度，不是"点得动点不动"。
     */
    private static void click_other_page_while_busy(MainFrame frame, List<String> problems)
            throws Exception {
        if (!frame.runningTasks().isEmpty()) {
            problems.add("进到这里还留着上一场景的任务：" + frame.runningTasks().size());
            return;
        }
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "e2m-click");
        deleteTree(dir);
        dir.mkdirs();
        List<String> xlsx = new ArrayList<String>();
        // 四张两万行：解析约三秒，够把"点按钮 + 建清单 + 抓屏"整段压在任务还在跑的时候做
        for (int k = 0; k < 4; k++) {
            xlsx.add(bigFixture(new java.io.File(dir, "点击取证" + (char) ('A' + k) + ".xlsx"),
                    20000));
        }
        invokeUi(() -> frame.clearAll());
        MainFrame.modalProgress = true;
        try {
            fireUi(() -> frame.addFilesAsync(xlsx));
            if (!waitTasks(frame, 1, 20)) {
                problems.add("前置：解析任务没起来，后面那一步点了也不作数："
                        + taskLine(frame));
                return;
            }
            MainFrame.Task busy = frame.runningTasks().get(0);
            int before = busy.getDone();
            invokeUi(() -> frame.tabs().setSelectedIndex(2));
            fireUi(() -> frame.sqlRunHelpButton().doClick());
            boolean open = false;
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until) {
                open = frame.sqlSamplesShowing();
                if (open) {
                    break;
                }
                Thread.sleep(30);
            }
            if (!open) {
                problems.add("任务在跑时第三页「示例 (?)」点不开——进度框还挡着窗口："
                        + taskLine(frame));
            } else if (!frame.sqlSamplesTitle().contains("SQL 示例")) {
                problems.add("开出来的不是那份示例清单：" + frame.sqlSamplesTitle());
            }
            // 换一条要把右侧滚回开头：预览折行之后，上一条长文留下的纵向位置会让新语句
            // 看着像"缺了上半截"。挑"全文最长"那条——折行之后顶出可视区的是行数不是行宽；
            // 切过去的那条也要同样长，否则"回到 0"可能只是新内容本来就放得下被夹回去，
            // 量到的不是复位。先把滚动条推到最下，再换条，等事件分发线程做完看读数
            final javax.swing.JScrollPane spane = frame.sqlSamplesPane();
            if (spane == null) {
                problems.add("示例预览没有滚动容器，验不了换条后的滚动复位");
            } else if (!frame.sqlSamplesPreviewWraps()) {
                problems.add("示例预览没按词折行：这一栏是给人照着挑语句的阅读面，"
                        + "长句该折在窗口里而不是拖横向滚动条");
            } else {
                final int longest = orderByLongestText(frame, 0);
                final int second = orderByLongestText(frame, 1);
                invokeUi(() -> frame.setSqlSamplesRow(longest));
                invokeUi(() -> {
                    spane.getVerticalScrollBar().setValue(
                            spane.getVerticalScrollBar().getMaximum());
                });
                final int v0 = spane.getVerticalScrollBar().getValue();
                invokeUi(() -> frame.setSqlSamplesRow(second));
                long resetUntil = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < resetUntil
                        && spane.getVerticalScrollBar().getValue() != 0) {
                    Thread.sleep(20);
                }
                if (v0 <= 0) {
                    problems.add("示例里没有一条长到要纵向滚动的（最长那条第 "
                            + (longest + 1) + " 条、" + frame.sqlSampleSql(longest).length()
                            + " 字符），这条复位没量到");
                } else if (spane.getVerticalScrollBar().getValue() != 0) {
                    problems.add("换一条示例后右侧没滚回开头：纵 "
                            + spane.getVerticalScrollBar().getValue()
                            + "（换条前停在 " + v0 + "）");
                }
            }
            // 清单开着、任务还在跑：这时候回一次事件分发线程，"点得动"才算数。
            // 门槛与上面同一处一起重标定成 3000 毫秒，理由见那一段的实测记录
            long p0 = System.currentTimeMillis();
            invokeUi(() -> { });
            long pingMs = System.currentTimeMillis() - p0;
            if (pingMs > 3000) {
                problems.add("示例清单开着时事件分发线程回应一次要 " + pingMs
                        + " 毫秒（界面等于没转）");
            }
            // 先确认那条任务还在：抓屏要抬窗口、拍 2560x1600，前后得花一秒多，
            // 把这条断言放在抓完之后就会采到"任务自己跑完了"，红得莫名其妙
            if (!frame.runningTasks().contains(busy)) {
                problems.add("点一下另一页的按钮就把那条任务弄没了：" + taskLine(frame));
            }
            java.awt.image.BufferedImage img = captureNow();
            invokeUi(() -> frame.closeSqlSamples());
            if (!waitTasks(frame, 0, 90)) {
                problems.add("那条任务没能自己跑完：" + taskLine(frame));
            }
            if (busy.getDone() < busy.getTotal() && busy.getDone() <= before) {
                problems.add("任务在被点按钮之后一步没动：" + busy.describe());
            }
            SHOTS.add("另一页清单在屏 " + orNull(saveShot(img, "click-while-busy"))
                    + "，拍时本程序在屏窗口 " + SHOT_WINDOWS + " 个");
        } finally {
            MainFrame.modalProgress = false;
            forceCloseDialogs();
        }
    }

    /** 按"全文字数"从长到短排第 rank 名（0 是最长那条）的示例序号。 */
    private static int orderByLongestText(MainFrame frame, int rank) {
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < frame.sqlSamplesCount(); i++) {
            order.add(i);
        }
        java.util.Collections.sort(order, (a, b) ->
                frame.sqlSampleSql(b).length() - frame.sqlSampleSql(a).length());
        return order.get(rank);
    }

    /** 抓不到图的时候别把 null 打进日志，说人话。 */
    private static String orNull(String shot) {
        return shot == null ? "那张没抓到（无显示设备或空帧）" : shot;
    }

    /** 在屏的顶层窗口数：留影那条报的是"拍到几个窗口"，比抽样颜色数更能说明拍没拍到界面。 */
    private static int showingWindows() {
        int n = 0;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isShowing()) {
                n++;
            }
        }
        return n;
    }

    /** 丢给事件分发线程执行但不等它：起任务那一步必须"点下去就返回"才叫不互相阻塞。 */
    private static void fireUi(Runnable action) {
        SwingUtilities.invokeLater(action);
    }

    /**
     * 收摊用的强制版：进度框也一起关掉。
     * 一旦进度框回归成模态，那颗框会一直开着把事件分发线程按在嵌套循环里，
     * 用例要的是"红得出来"，不是一台谁都退不掉的窗口。
     */
    private static void forceCloseDialogs() {
        SwingUtilities.invokeLater(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JDialog && w.isVisible()) {
                    w.dispose();
                }
            }
        });
    }

    /**
     * 立刻触发一颗按钮挂着的动作。
     *
     * JButton.doClick() 是把动作排到队列里稍后才发的，而自检体自己就站在事件分发线程上
     * ——等队列等于等自己。这里直接按同一个 ActionListener 走一遍，跑的仍是产品挂上去
     * 的那个监听器，不是另抄一份实现。
     */
    private static void clickNow(javax.swing.JButton button) {
        for (java.awt.event.ActionListener l : button.getActionListeners()) {
            l.actionPerformed(new java.awt.event.ActionEvent(
                    button, java.awt.event.ActionEvent.ACTION_PERFORMED, button.getActionCommand()));
        }
    }

    /** 在事件分发线程上跑一步，跑完才回来：起任务这类动作本来就该由"用户点按钮"那个线程做。 */
    private static void invokeUi(final Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    /** 等任务清单变成 wanted 个；超时返回 false，让用例红而不是抛出去把整轮带崩。 */
    private static boolean waitTasks(MainFrame frame, int wanted, int seconds)
            throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (frame.runningTasks().size() == wanted) {
                return true;
            }
            // 结果弹窗是模态的：自检里也得像人一样把它点掉，否则那条任务的收尾永远走不完
            closeMessageDialogs();
            Thread.sleep(60);
        }
        return frame.runningTasks().size() == wanted;
    }


    private static String taskLine(MainFrame frame) {
        StringBuilder sb = new StringBuilder();
        for (MainFrame.Task t : frame.runningTasks()) {
            if (sb.length() > 0) {
                sb.append(" || ");
            }
            sb.append(t.describe());
        }
        return sb.length() == 0 ? "（没有在跑的任务）" : sb.toString();
    }

    /**
     * 造一张 rows 行的 xlsx 夹具（六列，混着文本/数值/日期串），让解析真要读上一秒把。
     * 用流式 API：八个两万行是十六万行，XSSFWorkbook 全量进内存会把自检撑爆。
     */
    private static String bigFixture(java.io.File to, int rows) throws Exception {
        org.apache.poi.ss.usermodel.Workbook wb =
                new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
        org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("数据");
        String[] head = {"编号", "名称", "金额", "数量", "日期", "备注"};
        org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
        for (int c = 0; c < head.length; c++) {
            h.createCell(c).setCellValue(head[c]);
        }
        for (int i = 0; i < rows; i++) {
            org.apache.poi.ss.usermodel.Row r = sheet.createRow(i + 1);
            r.createCell(0).setCellValue(String.format("N%08d", i));
            r.createCell(1).setCellValue("名称" + i);
            r.createCell(2).setCellValue(3.14 + i % 100);
            r.createCell(3).setCellValue(i % 997);
            r.createCell(4).setCellValue(String.format("2026-03-%02d", i % 28 + 1));
            r.createCell(5).setCellValue("备注" + i % 50);
        }
        writeWorkbook(wb, to);
        return to.getPath();
    }

    private static int countXlsx(java.io.File root) {
        int n = 0;
        java.io.File[] kids = root.listFiles();
        if (kids == null) {
            return 0;
        }
        for (java.io.File f : kids) {
            if (f.isDirectory()) {
                n += countXlsx(f);
            } else if (f.getName().endsWith(".xlsx")) {
                n++;
            }
        }
        return n;
    }

    /**
     * 收掉自检里弹出来的消息框：真那条路的消息框是会开嵌套循环的，
     * 留着它后面的步骤就一直在别人的弹窗里看数据。进度框不算消息框，别误关。
     */
    private static void closeMessageDialogs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JDialog && w.isVisible()
                        && w.getClass().getName().endsWith("MessageDialog")) {
                    w.dispose();
                }
            }
        });
    }

    /**
     * 「生成标准/简易 excel 文件」两条路的界面自检：按钮在不在、排在哪、什么时候能点，
     * 以及真按一次之后产物落在哪儿、长什么样、能不能被导入那条线读回去。
     */
    private static void meta_export(MainFrame frame, List<String> files,
                                    List<String> problems) {
        frame.clearAll();
        javax.swing.JButton std = frame.genStandardButton();
        javax.swing.JButton simple = frame.genSimpleButton();
        if (std == null || simple == null) {
            problems.add("界面上没有「生成标准excel文件」「生成简易excel文件」两颗按钮");
            return;
        }
        // 排布：同一行动作条上，紧跟在「开始导入」右侧（用户要的正是这个位置关系）
        if (std.getParent() != simple.getParent()) {
            problems.add("两颗生成按钮不在同一条动作栏上");
        }
        List<String> order = new ArrayList<String>();
        for (java.awt.Component c : std.getParent().getComponents()) {
            if (c instanceof javax.swing.JButton) {
                order.add(((javax.swing.JButton) c).getText());
            }
        }
        if (!order.equals(java.util.Arrays.asList("停止", "开始导入",
                "生成标准excel文件", "生成简易excel文件"))) {
            problems.add("动作条顺序不是 停止/开始导入/标准/简易：" + order);
        }
        // 空列表按下去要有话说，而且不许把任务位占死
        frame.generateExcel(true);
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("请先添加 Excel 文件")) {
            problems.add("列表空着点生成要弹 NO_FILE_HINT，实际标题=" + frame.lastDialogTitle()
                    + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
        }
        if (frame.taskBusy()) {
            problems.add("拦下生成之后任务位没还回来，之后每个按钮都会被吃掉");
            frame.endTask();
        }

        frame.addFiles(files);
        int n = frame.tables().size();
        if (n == 0) {
            problems.add("生成自检没有可导的表");
            return;
        }
        if (!std.isEnabled() || !simple.isEnabled()) {
            problems.add("已经有表了，两颗生成按钮还是灰的，点了没反应");
        }
        // 忙碌态联动必须走真入口：只看构造时的初始值验不出"任务在跑还能再点一次"
        frame.startTask();
        boolean busyOff = !std.isEnabled() && !simple.isEnabled();
        frame.endTask();
        if (!busyOff) {
            problems.add("任务在跑时生成按钮还可点，两个任务会踩同一份表列表");
        }

        String root = com.excel2mysql.gui.MainFrame.programDir();
        java.io.File dir = new java.io.File(root,
                com.excel2mysql.core.MetaExport.STD_PREFIX + "_" + today());
        frame.generateExcel(true);
        if (!"正在生成标准excel文件…".equals(frame.lastTaskTitle())) {
            problems.add("生成任务标题不是 Python 那句原话：" + frame.lastTaskTitle());
        }
        if (!"生成结果".equals(frame.lastDialogTitle())) {
            problems.add("生成完成没弹带标题的结果框：" + frame.lastDialogTitle());
        }
        String doneLine = "===== 生成完成：" + n + "/" + n + " 个文件，目录 "
                + dir.getPath() + " =====";
        if (!frame.logPanel().contains(doneLine)) {
            problems.add("生成收尾的汇总行不对，期望：" + doneLine + "；日志尾部："
                    + tail(frame.logPanel().text()));
        }
        if (!flat(frame.lastDialogText()).contains(flat(dir.getPath()))) {
            problems.add("弹窗正文没把目录名原样给出来："
                    + frame.lastDialogText().replace((char) 10, ' '));
        }
        if (!frame.logPanel().contains("- [INFO] 导出 | 正在写 ")) {
            problems.add("逐个文件写之前一条日志都不说，长任务看着像卡死");
        }
        if (!frame.logPanel().contains(" 列 / ") || !frame.logPanel().contains(" 行）")) {
            problems.add("写完没报每个文件的列数与行数");
        }
        String[] produced = dir.list();
        if (produced == null || produced.length != n) {
            problems.add("程序同级该有 " + n + " 个文件，实际 "
                    + (produced == null ? 0 : produced.length) + " 个：" + dir.getPath());
        } else {
            checkStandardSheets(frame, dir, produced, problems);
        }
        // 程序同级这个目录是自检造出来的，验完就清掉，别留给下一次
        deleteTree(dir);

        java.io.File tmp;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-meta-selftest").toFile();
        } catch (java.io.IOException e) {
            problems.add("建简易件临时目录失败：" + e);
            return;
        }
        frame.generateExcel(false, tmp.getPath());
        if (!"正在生成简易excel文件…".equals(frame.lastTaskTitle())) {
            problems.add("简易件任务标题不对：" + frame.lastTaskTitle());
        }
        java.io.File simDir = new java.io.File(tmp,
                com.excel2mysql.core.MetaExport.SIMPLE_PREFIX + "_" + today());
        String[] simFiles = simDir.list();
        if (simFiles == null || simFiles.length != n) {
            problems.add("简易件目录应有 " + n + " 个文件，实际 "
                    + (simFiles == null ? "目录不存在" : simFiles.length + " 个"));
        } else {
            for (String name : simFiles) {
                try {
                    List<com.excel2mysql.core.MetaExport.Dump> dumps =
                            com.excel2mysql.core.MetaExport.dump(
                                    new java.io.File(simDir, name).getPath());
                    if (dumps.size() != 1
                            || !com.excel2mysql.core.MetaExport.DATA_SHEET.equals(
                                    dumps.get(0).sheet)) {
                        problems.add("简易件应只有一个数据 Sheet：" + name + " → "
                                + sheetsOf(dumps));
                    }
                } catch (Exception e) {
                    problems.add("简易件读不回来：" + name + " " + e);
                }
            }
        }
        deleteTree(tmp);

        // 停止要真的停下来：按了停止却把剩下的文件照写完，收尾那句"已取消，剩余文件未生成"就是假话
        java.io.File cdir;
        try {
            cdir = java.nio.file.Files.createTempDirectory("e2m-meta-cancel").toFile();
        } catch (java.io.IOException e) {
            problems.add("建取消用例临时目录失败：" + e);
            return;
        }
        List<com.excel2mysql.core.MetaExport.Source> srcs =
                new ArrayList<com.excel2mysql.core.MetaExport.Source>();
        List<com.excel2mysql.core.TableOps.Table> ts = frame.tables();
        List<com.excel2mysql.core.TableBuilder.Result> rs = frame.results();
        for (int i = 0; i < ts.size(); i++) {
            srcs.add(com.excel2mysql.core.MetaExport.of(ts.get(i), rs.get(i)));
        }
        Recorder crec = new Recorder();
        crec.stopAfter = 1;
        com.excel2mysql.core.MetaExport.Outcome co =
                com.excel2mysql.core.MetaExport.exportTables(srcs, cdir.getPath(), true, crec);
        if (co.written.size() != 1) {
            problems.add("按了停止还在写文件：写了 " + co.written.size() + " 个，应为 1 个");
        }
        if (co.total != ts.size()) {
            problems.add("生成任务的总项数没在开跑前算准：" + co.total + " / " + ts.size());
        }
        if (!co.cancelled) {
            problems.add("取消标志没传到收尾，弹窗不会说出「已取消」");
        }
        if (!crec.saw("+ [OK] 导出 | ", false)) {
            problems.add("写完一个文件没有留下成功行");
        }
        deleteTree(cdir);

        frame.clearAll();
        if (std.isEnabled() || simple.isEnabled()) {
            problems.add("清空待上传文件后生成按钮还亮着");
        }
    }

    /**
     * 标准件的形状：元数据 + 数据两个 Sheet、七列表头一字不差、元数据一行一列，
     * 而且这份文件要能被导入那条线的 TableBuilder 当结构定义表读回来。
     */
    private static void checkStandardSheets(MainFrame frame, java.io.File dir,
                                            String[] produced, List<String> problems) {
        java.util.Set<String> wantHead = new java.util.LinkedHashSet<String>();
        for (String h : com.excel2mysql.core.MetaExport.META_HEADERS) {
            wantHead.add(h);
        }
        for (String name : produced) {
            String path = new java.io.File(dir, name).getPath();
            List<com.excel2mysql.core.MetaExport.Dump> dumps;
            try {
                dumps = com.excel2mysql.core.MetaExport.dump(path);
            } catch (Exception e) {
                problems.add("标准件读不回来：" + name + " " + e);
                continue;
            }
            if (dumps.size() != 2 || !"元数据".equals(dumps.get(0).sheet)
                    || !"数据".equals(dumps.get(1).sheet)) {
                problems.add("标准件应有元数据+数据两个 Sheet：" + name + " → "
                        + sheetsOf(dumps));
                continue;
            }
            List<Object> head = dumps.get(0).cells.isEmpty()
                    ? new ArrayList<Object>() : dumps.get(0).cells.get(0);
            java.util.Set<String> gotHead = new java.util.LinkedHashSet<String>();
            for (Object o : head) {
                gotHead.add(String.valueOf(o));
            }
            if (!wantHead.equals(gotHead)) {
                problems.add("元数据表头被改动：" + gotHead);
            }
            com.excel2mysql.core.TableBuilder.Result back =
                    new com.excel2mysql.core.TableBuilder(
                            new com.excel2mysql.core.Infer(frame.names()),
                            frame.names()).build(path);
            if (!"structured".equals(back.table.source)) {
                problems.add("标准件回读没走结构定义那条路：" + name);
                continue;
            }
            if (back.table.columns.size() != dumps.get(0).cells.size() - 1) {
                problems.add("元数据一行一列对不上：" + name + " 列 "
                        + back.table.columns.size() + " 行 "
                        + (dumps.get(0).cells.size() - 1));
            }
        }
    }

    private static String sheetsOf(List<com.excel2mysql.core.MetaExport.Dump> dumps) {
        List<String> out = new ArrayList<String>();
        for (com.excel2mysql.core.MetaExport.Dump d : dumps) {
            out.add(d.sheet);
        }
        return out.toString();
    }

    private static String today() {
        return java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
