package com.excel2mysql;

import com.excel2mysql.gui.MainFrame;

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

    public static void main(String[] argv) {
        List<String> args = Arrays.asList(argv);
        if (args.contains("--selftest")) {
            System.exit(selftest(args));
        }
        showWindow(args);
    }

    private static void showWindow(List<String> args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            System.out.println("! [WARN] GUI | 系统外观设置失败，用默认外观：" + e.getMessage());
        }
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
                if (f.getName().toLowerCase().endsWith(".xlsx")) {
                    files.add(f.getPath());
                }
            }
        }
        final CountDownLatch done = new CountDownLatch(1);
        final int[] result = {0};
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = null;
            try {
                frame = new MainFrame();   // 建窗也在 try 里：构造期抛异常要让自检拿到退出码，而不是把闸门挂死
                String addSummary = frame.addFiles(files);
                int tables = frame.tables().size();
                int fileRows = frame.fileModel().getRowCount();
                int rows = frame.fileModel().rowTotal();
                String ddl = frame.ddlText();
                String status = frame.statusText();
                List<String> problems = new ArrayList<String>();
                if (!addSummary.contains("添加 7 项文件")) {
                    problems.add("添加结果汇总没数清文件：" + addSummary.replace((char) 10, ' '));
                }
                if (!frame.logPanel().contains("[添加结果] 添加 7 项文件")) {
                    problems.add("弹窗那句话没同时写进日志");
                }
                if (tables != 7) {
                    problems.add("表数期望 7，实际 " + tables + "（有文件解析失败，看日志）");
                }
                if (rows == 0) {
                    problems.add("字段总数为 0，模型是空的");
                }
                if (!frame.fileModel().tooltipAt(0).endsWith(".xlsx")) {
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
                if (!status.contains(String.valueOf(tables))) {
                    problems.add("状态栏没反映表数：" + status);
                }
                // 文件表刻意不可就地编辑：字段级改动在双击打开的结构弹窗里做，
                // 和 Python 侧一致（就地编辑会让"改的是哪张表"变含糊）
                if (frame.fileModel().isCellEditable(0, 3)) {
                    problems.add("文件表不该可就地编辑，改动要走结构弹窗");
                }
                // 结构弹窗要能真的改到表定义：改字段类型后 DDL 必须跟着变
                table_select_for_dialog(frame, problems);
                // 多表标签页弹窗：校验不过不能先写回，否则表定义停在改了一半的状态
                structure_dialog(frame, problems);
                // 前缀两个按钮与「覆盖已存在表」勾选：文案、幂等、跳过口径都要和 Python 一致
                prefix_and_labels(frame, problems);
                // 拖拽区与页内进度条：真拖进来走的必须是和选择器同一个入口
                drop_and_progress(frame, files, problems);
                // 导入收尾：成功文件移出、失败保留，日志与弹窗用的是同一句话
                import_finish(frame, files, problems);
                // SQL 页签：读盘、改表名、落盘副本、移除选中，全离线可验
                sql_page(frame, problems);
                // 页面级「停止」与并发护栏
                stop_button(frame, problems);
                // 进度框文案与收尾
                progress_copy(problems);
                // DDL 逐表分页、文件表列宽、端口范围
                ddl_dialog_and_widgets(frame, files, problems);
                // 导入那条路的文案与计项口径
                import_run(problems);
                // 子线程出意外时的兜底文案
                task_crash(frame, problems);
                // ZIP 展开、汇总计数与临时目录清理
                zip_roundtrip(frame, files, problems);
                // 标题版本号、说明窗口与关于框
                help_and_about(frame, problems);
                // 死按钮守卫：每个按钮都必须挂了监听器，界面上出现点了没反应的按钮
                // 比少一个按钮更糟——用户会以为是自己操作错了
                for (javax.swing.AbstractButton b : frame.buttons()) {
                    if (b.getActionListeners().length == 0) {
                        problems.add("按钮没有绑定动作：" + b.getText());
                    }
                }
                // 没连接之前"开始导入"必须是禁用的，不然点了只会静默失败
                javax.swing.AbstractButton importBtn = null;
                for (javax.swing.AbstractButton b : frame.buttons()) {
                    if ("开始导入".equals(b.getText())) {
                        importBtn = b;
                    }
                }
                if (importBtn == null) {
                    problems.add("界面上没有「开始导入」按钮");
                } else if (frame.isConnected() || importBtn.isEnabled()) {
                    problems.add("未连接时「开始导入」就可点，点了只会静默失败");
                }
                // 日志的红色与行数上限：错误没标红、或日志无限涨，都只会在现场翻车时才发现
                frame.logError("自检错误行");
                frame.log("自检普通行");
                if (!frame.logPanel().isErrorLine("自检错误行")) {
                    problems.add("logError 写出来的行没被标成错误级");
                }
                if (frame.logPanel().isErrorLine("自检普通行")) {
                    problems.add("普通日志被误标成错误级");
                }
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
                if (problems.isEmpty()) {
                    System.out.println("+ [OK] GUI-SELFTEST | 窗口构造成功，文件表 "
                            + fileRows + " 行 / 字段合计 " + rows
                            + " 个，DDL 预览含建表语句与主键，改类型能反映到预览");
                } else {
                    for (String p : problems) {
                        System.out.println("x [ERROR] GUI-SELFTEST | " + p);
                    }
                    result[0] = 1;
                }
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
        return result[0];
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
        com.excel2mysql.gui.StructureDialog dlg =
                new com.excel2mysql.gui.StructureDialog(null, results, frame.logPanel());
        if (dlg.tabTitles().size() != results.size()) {
            problems.add("结构弹窗标签页数不等于表数：" + dlg.tabTitles().size());
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
        String[] wanted = {"添加文件", "清空列表", "生成表结构预览", "预览 DDL", "清除日志",
                "批量应用前缀", "替换首个前缀", "连接并加载库", "开始导入"};
        for (String w : wanted) {
            if (!texts.contains(w)) {
                problems.add("界面缺少按钮：" + w);
            }
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
        if (!frame.logPanel().contains("[前缀] 已为全部表名批量添加前缀「ods_」")) {
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
        if (!frame.logPanel().contains("[前缀] 已移除前缀，恢复基础表名")) {
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

    private static void drop_and_progress(MainFrame frame, List<String> files,
                                          List<String> problems) {
        com.excel2mysql.gui.DropArea drop = frame.dropArea();
        if (drop == null) {
            problems.add("界面上没有拖拽接收区");
            return;
        }
        if (!drop.acceptsDrops()) {
            problems.add("拖拽区没挂上接收器，拖文件进来不会有任何反应");
        }
        if (!drop.hintText().contains("拖到此处") || !drop.hintText().contains(".zip")) {
            problems.add("拖拽区提示没交代清楚能拖什么：" + drop.hintText());
        }
        java.awt.Image icon = frame.getIconImage();
        if (icon == null) {
            problems.add("窗口图标没设上，任务栏里只会是默认图");
        } else if (icon.getWidth(null) < 64) {
            problems.add("窗口图标取小了，任务栏会糊：" + icon.getWidth(null) + "x"
                    + icon.getHeight(null));
        }
        if (frame.getWidth() != 1100 || frame.getHeight() != 780) {
            problems.add("窗口尺寸与 Python 侧不一致：" + frame.getWidth() + "x" + frame.getHeight());
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
        if (!frame.logPanel().contains("拖入的不是 Excel 或 ZIP 文件，已忽略")) {
            problems.add("拖进非 Excel 文件时没有任何说明");
        }

        frame.setPageProgress(37);
        if (frame.pageProgressValue() != 37) {
            problems.add("页内进度条没跟上：" + frame.pageProgressValue());
        }
        frame.clearAll();
        if (frame.pageProgressValue() != 0 || !frame.tables().isEmpty()) {
            problems.add("「清空列表」没把进度条归零或没清空表");
        }

        if (frame.databaseChooserEnabled()) {
            problems.add("还没连接就能选库，库名只能靠猜");
        }
        if (!frame.excelHintText().contains("未选择目标数据库") || frame.excelHintIsOk()) {
            problems.add("未选库时 Excel 页提示条没按红级说明：" + frame.excelHintText());
        }
        if (!frame.sqlPage().hintText().equals(frame.excelHintText())) {
            problems.add("两页的提示条说的话不一样，用户会以为连的不是同一个库");
        }
        frame.setSelectedDatabase("testdb");
        if (!frame.excelHintText().contains("当前目标库：testdb（127.0.0.1:3306）")
                || !frame.excelHintText().contains("Excel 数据将导入到这个库")
                || !frame.excelHintIsOk()) {
            problems.add("选定库之后 Excel 页提示条不对：" + frame.excelHintText());
        }
        if (!frame.sqlPage().hintText().contains("SQL 脚本将执行到这个库")
                || !frame.sqlPage().hintIsOk()) {
            problems.add("SQL 页提示条没跟着联动：" + frame.sqlPage().hintText());
        }
        frame.hostField().setText("10.0.0.9");
        if (!frame.excelHintText().contains("10.0.0.9:3306")) {
            problems.add("改了主机但提示条还是旧地址：" + frame.excelHintText());
        }
        frame.hostField().setText("127.0.0.1");
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
        List<MainFrame.ImportOutcome> outs = new ArrayList<MainFrame.ImportOutcome>();
        outs.add(new MainFrame.ImportOutcome(f0, true));
        outs.add(new MainFrame.ImportOutcome(f1, false));
        String summary = frame.finishImport(outs, false);
        if (!summary.contains("成功 1 张表，失败 1 张表")) {
            problems.add("收尾汇总文案不对：" + summary.replace((char) 10, ' '));
        }
        if (!frame.logPanel().contains("===== 全部完成：成功 1 张表，失败 1 张表 =====")) {
            problems.add("收尾没有写汇总日志行");
        }
        if (!frame.logPanel().isErrorLine("===== 全部完成")) {
            problems.add("有失败时汇总行没标成错误级");
        }
        if (!frame.logPanel().contains("[清理] 已从待导入列表移除成功文件")) {
            problems.add("成功文件移出列表没有留痕");
        }
        if (!frame.logPanel().contains("[保留] 导入失败的文件仍在列表中")) {
            problems.add("失败文件保留没有说明原因");
        }
        if (frame.tables().size() != 1 || !f1.equals(frame.tables().get(0).filepath)) {
            problems.add("收尾后剩下的应该是失败那张表");
        }
        if (frame.fileModel().getRowCount() != 1) {
            problems.add("文件表没跟着移出，还剩 " + frame.fileModel().getRowCount() + " 行");
        }
        if (frame.pageProgressValue() != 0) {
            problems.add("收尾后进度条没归零：" + frame.pageProgressValue());
        }

        frame.clearAll();
        frame.addFiles(two);
        String a = frame.tables().get(0).filepath;
        String b = frame.tables().get(1).filepath;
        List<MainFrame.ImportOutcome> allOk = new ArrayList<MainFrame.ImportOutcome>();
        allOk.add(new MainFrame.ImportOutcome(a, true));
        allOk.add(new MainFrame.ImportOutcome(b, true));
        frame.finishImport(allOk, false);
        if (!frame.tables().isEmpty() || frame.fileModel().getRowCount() != 0) {
            problems.add("全部成功后列表却没清空");
        }
        // 日志是累加的，所以这里按第二场自己的汇总行判：零失败不该被标成错误级
        if (!frame.logPanel().contains("===== 全部完成：成功 2 张表，失败 0 张表 =====")) {
            problems.add("全成功场景没有写汇总行");
        }
        if (frame.logPanel().isErrorLine("成功 2 张表，失败 0 张表")) {
            problems.add("零失败的汇总行被标成了错误级");
        }
        frame.clearAll();
    }

    private static void writeText(String path, String text) {
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(path),
                    text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("自检写夹具失败：" + path, e);
        }
    }

    private static void sql_page(MainFrame frame, List<String> problems) {
        List<String> titles = frame.tabTitles();
        if (!titles.contains("Excel 导入") || !titles.contains("SQL 脚本")) {
            problems.add("页签不齐全：" + titles);
        }
        com.excel2mysql.gui.SqlPage page = frame.sqlPage();
        if (page == null) {
            problems.add("界面上没有 SQL 脚本页");
            return;
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

        if (page.addFiles(paths) != 2) {
            problems.add("两个脚本应全部加载成功");
        }
        if (page.rowCount() != 3) {
            problems.add("表格按表名出行，应有 3 行，实际 " + page.rowCount());
        }
        if (!page.logPanel().contains("[已加载] a.sql：2 个表")) {
            problems.add("加载日志没报每个文件的表数");
        }
        page.addFiles(paths);
        if (!page.logPanel().contains("[跳过] 已在列表：a.sql")) {
            problems.add("重复添加同一文件没有跳过");
        }
        if (page.entryCount() != 2) {
            problems.add("重复添加把文件加了两遍");
        }
        if (page.applyPrefix() == null) {
            problems.add("空前缀没被拦下");
        }

        page.setPrefixText("ods_");
        if (page.applyPrefix() != null) {
            problems.add("合法前缀被拦住了");
        }
        String text = page.textOf(a);
        if (!text.contains("ods_t_order") || !text.contains("ods_plain")) {
            problems.add("加前缀没改到表名：" + text);
        }
        if (!page.isModified(a)) {
            problems.add("改过却没标记成已修改，「保存修改后脚本」会漏掉它");
        }
        int rowsBefore = page.rowCount();
        page.setPrefixText("ods_");
        page.applyPrefix();
        if (page.rowCount() != rowsBefore || page.textOf(a).contains("ods_ods_")) {
            problems.add("重复加同一个前缀叠了一层");
        }
        if (!page.logPanel().contains("[前缀完成] 共改写 3 个表名")) {
            problems.add("前缀完成没有汇总改写了几个表名");
        }
        if (!page.cellText(0, 3).equals("ods_t_order")) {
            problems.add("「当前表名」列没跟着改写走：" + page.cellText(0, 3));
        }
        if (!page.cellText(0, 1).equals("t_order")) {
            problems.add("「原表名」列显示的不是最初的表名：" + page.cellText(0, 1));
        }

        page.setPrefixText("change");
        if (page.replaceFirstPrefix() != null) {
            problems.add("替换首个前缀被拦住");
        }
        String after = page.textOf(a);
        if (!after.contains("change_t_order") || !after.contains("change_plain")) {
            problems.add("替换首段没改到表名：" + after);
        }
        if (!after.contains("INSERT INTO `change_t_order`")) {
            problems.add("INSERT 里的引用没跟着改写：" + after);
        }
        if (!page.textOf(b).contains("change_dim_x")) {
            problems.add("ods_dim_x 的首段没被换掉：" + page.textOf(b));
        }

        if (page.saveModified() != 2) {
            problems.add("两个改过的脚本都该落盘");
        }
        if (!page.logPanel().contains("[已保存] ")) {
            problems.add("落盘成功没有报出写了哪个文件");
        }
        String outA = com.excel2mysql.gui.SqlPage.modifiedPath(a);
        if (!new java.io.File(outA).isFile()) {
            problems.add("保存后没有生成副本：" + outA);
        }
        if (page.isModified(a)) {
            problems.add("落盘后还标记着已修改，下次会重复保存");
        }
        if (page.saveModified() != 0 || !page.logPanel().contains("[保存] 没有修改过的脚本文件")) {
            problems.add("没有改动时保存要如实说明，不能静默");
        }
        if (!"x/y_prefix.sql".equals(com.excel2mysql.gui.SqlPage.modifiedPath("x/y.sql"))) {
            problems.add("副本文件名规则不对：" + com.excel2mysql.gui.SqlPage.modifiedPath("x/y.sql"));
        }
        if (!"x/y_prefix.sql".equals(com.excel2mysql.gui.SqlPage.modifiedPath("x/y"))) {
            problems.add("无扩展名时副本名没补 .sql");
        }

        String hint = page.execute();
        if (hint == null || !hint.contains("未选择目标数据库")) {
            problems.add("没选库时执行应当拦下并说明：" + hint);
        }
        String summary = page.finishRun(1, 1, 0, false, "testdb@127.0.0.1:3306");
        if (!summary.contains("成功 1 个文件，失败 1 个文件")) {
            problems.add("执行收尾文案不对：" + summary.replace((char) 10, ' '));
        }
        if (!page.logPanel().isErrorLine("===== 执行完成：成功 1 个文件，失败 1 个文件")) {
            problems.add("有失败时执行收尾行没标成错误级");
        }

        page.selectRow(0);
        if (page.removeSelected() != 1) {
            problems.add("选中一行应移除它所属的整个文件");
        }
        if (page.entryCount() != 1 || page.rowCount() != 1) {
            problems.add("移除后文件与行数都不对：" + page.entryCount() + "/" + page.rowCount());
        }
        if (!page.logPanel().contains("[移除] 1 个脚本文件")) {
            problems.add("移除文件没有留痕");
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
        com.excel2mysql.gui.HelpDialog hd = new com.excel2mysql.gui.HelpDialog(null);
        try {
            if (hd.tocCount() != 12) {
                problems.add("说明目录应有 12 章，实际 " + hd.tocCount());
            }
            if (hd.bodyLength() < 2000) {
                problems.add("说明正文没渲染出来，长度只有 " + hd.bodyLength());
            }
            hd.selectToc(3);
            if (!"excel".equals(hd.currentAnchor())) {
                problems.add("点目录第 4 章没跳到「两类 Excel 格式」，锚点=" + hd.currentAnchor());
            }
            hd.selectToc(0);
            if (!"start".equals(hd.currentAnchor())) {
                problems.add("跳回第一章失败，锚点=" + hd.currentAnchor());
            }
            if (!"anchor".equals(hd.jumpVia())) {
                problems.add("目录跳转不是靠锚点实现的（走的是 " + hd.jumpVia()
                        + "），说明 HTML 里的 a name 没被渲染器收下来");
            }
        } finally {
            hd.dispose();
        }
        com.excel2mysql.gui.AboutDialog ad = new com.excel2mysql.gui.AboutDialog(null);
        try {
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
        } finally {
            ad.dispose();
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
        if (frame.tables().size() != before + 1) {
            problems.add("压缩包里的 Excel 没被展开进列表：" + before + " → "
                    + frame.tables().size() + "；日志尾部：" + tail(frame.logPanel().text()));
        }
        if (!frame.logPanel().contains("[压缩包] pack1.zip：展开 1 个 Excel，忽略 1 个非 Excel 文件")) {
            problems.add("压缩包展开没报清楚（展开几个、忽略几个）");
        }
        if (!summary.contains("添加 1 项 zip 文件")) {
            problems.add("弹窗汇总没把 zip 单列一行：" + summary.replace((char) 10, ' '));
        }
        int last = frame.fileModel().getRowCount() - 1;
        String tip = frame.fileModel().tooltipAt(last);
        if (!tip.contains("pack1.zip!") || !tip.contains("inner.xlsx")) {
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

    private static String tail(String text) {
        String s = text == null ? "" : text.replace((char) 10, ' ');
        return s.length() <= 260 ? s : s.substring(s.length() - 260);
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
    }

    /** 只记账不渲染的回报口，用来验 core 里那条链的对外行为。 */
    private static final class Recorder implements com.excel2mysql.core.ImportParse.Sink {
        final List<String> lines = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
        int total = -1;
        int advanced;

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
            return false;
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
        int units = com.excel2mysql.core.ImportRun.run(cfg, empty, false, rec, null).size();
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
            String first = plain.tabText(0);
            if (!first.contains("CREATE TABLE")) {
                problems.add("DDL 弹窗第一页没有建表语句");
            }
            if (first.contains("DROP TABLE")) {
                problems.add("没勾覆盖已存在表，DDL 里却出现 DROP");
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
        } finally {
            dropping.dispose();
        }

        int[] want = {320, 200, 280, 80, 90, 160};
        javax.swing.table.TableColumnModel cols = frame.fileTable().getColumnModel();
        for (int i = 0; i < want.length && i < cols.getColumnCount(); i++) {
            if (cols.getColumn(i).getPreferredWidth() != want[i]) {
                problems.add("文件表第 " + (i + 1) + " 列宽不是 " + want[i] + "，实际 "
                        + cols.getColumn(i).getPreferredWidth());
            }
        }
        if (frame.fileTable().getAutoResizeMode()
                != javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN) {
            problems.add("文件表最后一列没有吃掉剩余宽度");
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
            if (pd.getPreferredSize().width != 480 || pd.getPreferredSize().height != 140) {
                problems.add("进度框尺寸与 Python 不一致：" + pd.getPreferredSize());
            }
            if (!"取消".equals(pd.cancelText())) {
                problems.add("取消按钮默认文案不对：" + pd.cancelText());
            }
            pd.setTotal(3);
            pd.step("读盘");
            if (!"进度 1/3 项 · 读盘".equals(pd.statusText())) {
                problems.add("进度文案格式不对：" + pd.statusText());
            }
            pd.step("识别格式");
            if (pd.percentShown() >= pd.percentTarget() && pd.percentShown() != 0) {
                problems.add("补间起点不应已经等于目标值：" + pd.percentShown());
            }
            pd.step("选主键");
            if (pd.percentShown() != 100) {
                problems.add("跑完最后一项进度条没拉满：" + pd.percentShown());
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
        int lines = frame.logPanel().lineCount();
        frame.stopTask();
        if (frame.logPanel().lineCount() == lines) {
            problems.add("空点「停止」什么都不说，用户会以为自己按错了");
        }
        if (!frame.logPanel().contains("没有正在执行的任务")) {
            problems.add("空点「停止」的提示语不对");
        }
    }
}
