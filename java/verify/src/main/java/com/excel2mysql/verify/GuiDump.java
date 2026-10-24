package com.excel2mysql.verify;

import com.excel2mysql.core.MetaExport;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.gui.AboutDialog;
import com.excel2mysql.gui.DDLDialog;
import com.excel2mysql.gui.HelpDialog;
import com.excel2mysql.gui.LogPanel;
import com.excel2mysql.gui.MainFrame;
import com.excel2mysql.gui.ProgressDialog;
import com.excel2mysql.gui.SqlPage;
import com.excel2mysql.gui.StructureDialog;
import com.excel2mysql.gui.Tables;
import com.excel2mysql.gui.UiScale;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把 Swing 这条 GUI 线的界面与行为枚举成扁平 JSON，键名与
 * java/tools/gui_dump_py.py 逐一对齐，由 tools/gui_parity.py 逐条对撞。
 *
 * 为什么不用"读代码列条目"：那份清单是我自己写的，边界就停在我想到的地方。
 * 这里从活对象上机器枚举，少一个按钮、文案差一个字、启用条件不同，
 * 都会以"键缺失/值不等"的形式露出来。
 */
public final class GuiDump {

    private static final Map<String, String> D = new TreeMap<String, String>();
    private static MainFrame frame;
    private static File tmp;

    public static void main(String[] args) throws Exception {
        final String out = args.length > 0 ? args[0] : "verify/target/parity/java.json";
        final File fix = new File(args.length > 1 ? args[1] : "verify/fixtures");
        tmp = new File(args.length > 2 ? args[2]
                : new File(System.getProperty("java.io.tmpdir"), "e2m-gui-dump-java").getPath());
        deleteTree(tmp);
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            System.out.println("x [ERROR] GUI-DUMP | 建不出临时目录：" + tmp.getPath());
            System.exit(2);
        }
        MainFrame.modalProgress = false;
        // 对撞口径固定在"没有 conn.ini"：包内冒烟时 jar 同级带着随包 conn.ini，
        // 不钉住的话这里会真的去连库，探针结果随运行目录变
        MainFrame.connCandidates = java.util.Collections.singletonList(
                new File(tmp, "no-such-conn.ini").getPath());
        ui(UiScale::apply);
        ui(new Runnable() {
            @Override
            public void run() {
                frame = new MainFrame();
                frame.getContentPane().setPreferredSize(new java.awt.Dimension(
                        UiScale.px(1100), UiScale.px(780)));
                frame.pack();
                frame.setVisible(true);
            }
        });
        run(fix);
        write(out);
        deleteTree(tmp);
        System.out.println("+ [OK] GUI-DUMP-JAVA | " + D.size() + " 个键 | " + out);
        System.exit(0);
    }

    private static void run(File fix) throws Exception {
        final File[] kids = fix.listFiles();
        Arrays.sort(kids == null ? new File[0] : kids);
        final List<String> files = new ArrayList<String>();
        if (kids != null) {
            for (File f : kids) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".xlsx") || n.endsWith(".xls")) {
                    files.add(f.getPath());
                }
            }
        }

        ui(new Runnable() {
            @Override
            public void run() {
                List<String> titles = frame.tabTitles();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < titles.size(); i++) {
                    if (i > 0) {
                        sb.append('|');
                    }
                    sb.append(titles.get(i));
                }
                put("tabs", sb.toString());
                dumpWidget("host", frame.hostField());
                dumpWidget("port", frame.portSpinner());
                dumpWidget("user", frame.userField());
                dumpWidget("pass", frame.passField());
                dumpWidget("db", frame.dbCombo());
                dumpWidget("prefix", frame.prefixField());
                dumpWidget("sql_prefix", frame.sqlPage().prefixField());
                dumpWidget("drop_existing", frame.dropExistingBox());
                dumpWidget("initials", frame.initialsBox());
                dumpWidget("upper", frame.upperBox());
                dumpWidget("sql_upper", frame.sqlPage().upperBox());
                dumpWidget("sql_drop_existing", frame.sqlPage().dropExistingBox());
                dumpWidget("progress", frame.pageProgressBar());
                dumpButtons();
                dumpTable("file", frame.fileTable());
                dumpSqlTable();
                // 每张主表格左上角那句标识（读真控件的标题边框，与 Python 侧同判据）
                put("table.file.title", Tables.titledAncestor(frame.fileTable()));
                put("table.sql.title", frame.sqlPage().tableTitle());
                // 字号也是界面事实：两栈各写一份常量，以前只能靠人记着同步
                put("font.table.pt", String.valueOf(UiScale.fontPxToPt(
                        frame.fileTable().getFont().getSize())));
                put("font.log.pt", String.valueOf(UiScale.fontPxToPt(
                        frame.logPanel().getFont().getSize())));
                // 连接区：跑业务时收起成一条状态栏，只报当前是哪个库（用户口径）
                put("conn.state", frame.connStateText());
                put("conn.expanded", frame.isConnExpanded() ? "展开" : "收起");
                put("conn.edit", frame.connEditVisible() ? "显示" : "隐藏");
                put("hint.excel", frame.excelHintText());
                put("hint.sql", frame.sqlPage().hintText());
                put("drop.hint", frame.dropArea().hintText());
                put("drop.sql.hint", frame.sqlPage().sqlDropArea().hintText());
                put("drop.sql.accepts",
                        frame.sqlPage().sqlDropArea().acceptsDrops() ? "true" : "false");
                put("split.sql.order", frame.sqlPage().segmentOrder());
                // 日志默认高度 = 它自己的最小高度（用户口径：默认压到最低，仍可拖）。
                // 比的是真控件量出来的高度，不是各栈抄一份配置值。
                frame.validate();
                frame.applySplitProportions();
                frame.validate();
                put("split.excel.logmin", frame.logAtMin());
                frame.selectTab(1);
                frame.validate();
                frame.sqlPage().applySplitProportions();
                frame.validate();
                put("split.sql.logmin", frame.sqlPage().logAtMin());
                frame.selectTab(0);
                frame.validate();

                // ---- 第三个页签「SQL 执行器」：常驻可见量 + 示例对话框的一来一回 ----
                put("hint.sqlrun", norm(frame.sqlRunHintText()));
                put("hint.sqlrun.db", frame.runDbHintText());
                // 页面上的静态标签按字典序连起来比：两边的控件遍历顺序本来就不同
                // （Qt 按创建序、Swing 按加进容器的序），排序后比的是"这一页上有哪几句话"。
                java.util.List<String> runLabels = new ArrayList<String>();
                traverseLabels(frame.tabs().getComponentAt(2), runLabels);
                java.util.Collections.sort(runLabels);
                StringBuilder lbSb = new StringBuilder();
                for (int i = 0; i < runLabels.size(); i++) {
                    if (i > 0) {
                        lbSb.append('|');
                    }
                    lbSb.append(runLabels.get(i));
                }
                put("sqlrun.labels", lbSb.toString());
                put("sqlrun.default", frame.sqlRunInputText());
                put("sqlrun.wrap", frame.sqlRunWrap());
                frame.setSqlRunInputText("");
                frame.runSqlFromUi();
                put("guard.sqlrun.empty", popup());
                frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
                frame.showSqlSamples();
                put("samples.title", frame.sqlSamplesTitle());
                put("samples.count", String.valueOf(frame.sqlSamplesCount()));
                put("samples.item.0", norm(frame.sqlSamplesItemText(0)));
                put("samples.preview.0", frame.sqlSamplesPreviewText());
                // 全文预览的字号也是界面事实：这是与左侧清单并排的阅读面，不吃编辑区那档 16 号
                put("samples.preview.font.pt", String.valueOf(UiScale.fontPxToPt(
                        frame.sqlRunPreviewFont().getSize())));
                frame.setSqlSamplesRow(7);
                put("samples.preview.7", frame.sqlSamplesPreviewText());
                // 不比对「复制到剪贴板」的那句回执：剪贴板可能被别的进程占用，
                // 两条线各跑一次的成败不同，那是现场状态不是界面差异（Java 自检里咬回执本身）
                frame.showSqlSamples();
                frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
                frame.setSqlSamplesRow(3);
                frame.clickSqlSamplesInsert();
                put("samples.insert.default", frame.sqlRunInputText());
                frame.showSqlSamples();
                frame.setSqlRunInputText("SELECT 1");
                frame.setSqlSamplesRow(1);
                frame.clickSqlSamplesInsert();
                // 追加那条只比"补没补分号换行"：两条线的行分隔符不同（\r\n 与 \n），
                // 正文本身已由 samples.preview.* 那两键钉住。
                put("samples.insert.append",
                        frame.sqlRunInputText().startsWith("SELECT 1;\n") ? "yes" : "no");
                frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
                for (String t : new String[]{"开始导入", "生成标准excel文件", "生成简易excel文件",
                        "预览 DDL", "生成表结构预览", "停止"}) {
                    put("empty.on." + t, btnOn(t));
                }
                // 空列表 / 拖错文件 / 空点停止：把说的那句话取出来对
                frame.previewDdl();
                put("guard.empty.ddl", popup());
                frame.previewStructure();
                put("guard.empty.structure", popup());
                frame.generateExcel(true);
                put("guard.empty.gen_std", popup());
                frame.generateExcel(false);
                put("guard.empty.gen_simple", popup());
                String mark = frame.logPanel().text();
                List<String> junk = new ArrayList<String>();
                junk.add(new File(tmp, "readme.txt").getPath());
                frame.acceptedDrops(junk);
                put("guard.drop.txt", tail(mark, frame.logPanel().text()));
                mark = frame.logPanel().text();
                frame.stopTask();
                put("guard.stop", tail(mark, frame.logPanel().text()));

                // 连不上库：只比固定文案那一行与弹窗的图标+标题，驱动报错细节两条线必然不同
                frame.hostField().setText("127.0.0.1");
                ((javax.swing.SpinnerNumberModel) frame.portSpinner().getModel())
                        .setValue(Integer.valueOf(1));
                mark = frame.logPanel().text();
                String failure = frame.loadDatabases();
                if (failure != null) {
                    frame.critical(failure);
                }
                put("guard.conn.popup", frame.lastDialogIcon() + "|" + frame.lastDialogTitle());
                String connLog = tail(mark, frame.logPanel().text());
                int nl = connLog.indexOf('\n');
                put("guard.conn.log", nl < 0 ? connLog : connLog.substring(0, nl));
                put("guard.conn.db_enabled", frame.databaseChooserEnabled() ? "1" : "0");
            }
        });
        load(files);
        prefixSteps();
        dialogs();
        exportSteps();
        sqlSteps();
        cleared();
        dbHintSteps();
    }

    /**
     * 三页顶部的目标库信息条必须同源：塞一个库进去，三句话和三种颜色要一起变。
     * 各页各写一份刷新的话，迟早会在某个页签看到"上一个库"；host:port 只在有库时才进文案，
     * 所以这里必须真选一个库，不能拿空态糊过去。放在最后一步做，免得把前面那些
     * "没选库当场拦下"的探针带偏（它们断的就是空态那句话）。
     */
    private static void dbHintSteps() throws Exception {
        ui(new Runnable() {
            @Override
            public void run() {
                frame.setSelectedDatabase("alpha");
                put("hint.db.linked", join(java.util.Arrays.asList(
                        frame.excelHintText(), frame.sqlPage().hintText(),
                        frame.runDbHintText()), "|"));
                put("hint.db.tone", join(java.util.Arrays.asList(
                        dbTone(frame.excelHint()), dbTone(frame.sqlPage().hintField()),
                        dbTone(frame.runDbHint())), "|"));
            }
        });
    }
    private static void load(final List<String> files) throws Exception {
        ui(new Runnable() {
            @Override
            public void run() {
                frame.addFiles(files);
                put("loaded.names", names());
                StringBuilder serial = new StringBuilder();
                for (int r = 0; r < frame.fileModel().getRowCount(); r++) {
                    if (r > 0) {
                        serial.append(',');
                    }
                    serial.append(frame.fileModel().getValueAt(r, 0));
                }
                put("file.serial", frame.fileModel().getColumnName(0) + "=" + serial);
                dumpTable("file", frame.fileTable());
                for (String t : new String[]{"开始导入", "生成标准excel文件", "生成简易excel文件"}) {
                    put("loaded.on." + t, btnOn(t));
                }
                put("loaded.log", frame.logPanel().text());
                // 日志框最小高度：比的是"逻辑像素"那一个数（Qt 的 minimumHeight 本来就是
                // 设备无关像素；Swing 这边控件上挂的是按 DPI 折算后的物理值）
                put("log.excel.minh", String.valueOf(com.excel2mysql.gui.LogPanel.LOG_MIN_LINES));
                put("log.sql.minh", String.valueOf(com.excel2mysql.gui.LogPanel.LOG_MIN_LINES));
                put("log.run.minh", String.valueOf(com.excel2mysql.gui.LogPanel.LOG_MIN_LINES));
                put("loaded.hint.excel", frame.excelHintText());
                put("add.popup", popup());
                // 跨线比配置值（两边写死的客户区尺寸必须同一个数）；
                // 实测尺寸受现场屏幕与夹取影响，记成 env.* 只留档不比。
                // Qt 的 resize 与 Swing 的 pack 说的都是客户区，外框由操作系统加，不拿来比。
                put("window.want", want(frame));
                put("env.window.size", clientSize(frame));
                java.awt.Image icon = frame.getIconImage();
                put("window.icon", icon == null ? "none"
                        : "max=" + Math.max(icon.getWidth(null), icon.getHeight(null)));
                put("file.tooltip.0", frame.fileModel().tooltipAt(0));
                // 文件表只有「表名」列可编辑；单击改名、非法名恢复原名，两条线逐句话比
                put("file.rename.editable",
                        (frame.fileModel().isCellEditable(0, 2) ? "1" : "0") + "|"
                        + (frame.fileModel().isCellEditable(0, 0) ? "1" : "0") + "|"
                        + (frame.fileModel().isCellEditable(0, 3) ? "1" : "0"));
                String renameOld = frame.tables().get(0).tableName;
                frame.fileModel().setValueAt(renameOld + "_probe", 0, 2);
                put("file.rename.ok", frame.tables().get(0).tableName + "|"
                        + frame.tables().get(0).baseTableName + "|"
                        + lastLogLine(frame.logPanel().text()));
                frame.fileModel().setValueAt("", 0, 2);
                put("file.rename.empty",
                        frame.tables().get(0).tableName + "|" + popup());
                frame.fileModel().setValueAt("bad name", 0, 2);
                put("file.rename.bad",
                        frame.tables().get(0).tableName + "|" + popup());
                String renameDup = frame.tables().get(1).tableName;
                frame.fileModel().setValueAt(renameDup, 0, 2);
                put("file.rename.dupe",
                        frame.tables().get(0).tableName + "|" + popup());
                frame.fileModel().setValueAt(renameOld, 0, 2);
                put("file.rename.restored", frame.tables().get(0).tableName + "|"
                        + frame.tables().get(0).baseTableName);
                // 弹窗显示的正文必须就是原文：折行是排版的活，谁都不许预先往文本里插换行
                String worst = "任务异常：读取 C:/数据/很长的一个文件名防空警报试鸣信息（dim_air_raid_siren）.xlsx 时失败，"
                        + "内部单元格 D17 的类型与推断结果冲突，需要人工确认这一列到底是文本还是数字，"
                        + "详见日志里的完整堆栈信息。";
                com.excel2mysql.gui.MessageDialog wdlg =
                        com.excel2mysql.gui.MessageDialog.create(
                                frame, "任务异常", worst, null,
                                javax.swing.JOptionPane.ERROR_MESSAGE);
                put("popup.display.raw", worst.equals(wdlg.displayText()) ? "原文" : "被改写过");
                wdlg.dispose();
                frame.startImport();
                put("guard.nodb.import", popup());

                // 结构弹窗改一格 → DDL 要跟着变（编辑真的落进表定义）
                com.excel2mysql.core.Infer.Column c0 =
                        frame.tables().get(0).columns.get(0);
                String beforeType = c0.type;
                c0.type = "VARCHAR(123)";
                frame.refreshPreview();
                put("structure.edit.ddl",
                        frame.ddlText().contains("VARCHAR(123)") ? "1" : "0");
                c0.type = beforeType;
                frame.refreshPreview();
            }
        });
        doubleClickProbe();
        zipRoundtrip(files);
    }

    /**
     * 双击文件列表第一行，看真开出来的结构弹窗装的是哪张表。
     *
     * 这个弹窗是模态的：setVisible 会在 EDT 上再开一层事件循环，
     * 所以派发要丢给 invokeLater，由调用线程等它出现、读出标签页、再 dispose 放行。
     * 等不到就记 0——这是一条会变的红线，不是静默跳过。
     */
    private static void doubleClickProbe() {
        try {
            final String[] dbg = new String[]{"start"};
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    frame.fileTable().changeSelection(0, 0, false, false);
                    dbg[0] = "sel=" + frame.fileTable().getSelectedRow()
                            + " rows=" + frame.fileTable().getRowCount()
                            + " lsn=" + frame.fileTable().getMouseListeners().length;
                    java.awt.Rectangle cell = frame.fileTable().getCellRect(0, 0, true);
                    java.awt.event.MouseEvent dbl = new java.awt.event.MouseEvent(
                            frame.fileTable(), java.awt.event.MouseEvent.MOUSE_CLICKED,
                            System.currentTimeMillis(), 0,
                            cell.x + 5, cell.y + 5, 2, false);
                    for (java.awt.event.MouseListener ml
                            : frame.fileTable().getMouseListeners()) {
                        ml.mouseClicked(dbl);
                    }
                    dbg[0] = dbg[0] + " fired";
                }
            });
            StructureDialog opened = null;
            long deadline = System.currentTimeMillis() + 10000;
            int seen = 0;
            while (opened == null && System.currentTimeMillis() < deadline) {
                java.awt.Window[] ws = java.awt.Window.getWindows();
                seen = ws.length;
                for (java.awt.Window w : ws) {
                    if (w instanceof StructureDialog && w.isShowing()) {
                        opened = (StructureDialog) w;
                    }
                }
                if (opened == null) {
                    Thread.sleep(50);
                }
            }
            if (opened == null) {
                put("dblclick.structure", "0|0|" + dbg[0] + " seen=" + seen);
                return;
            }
            final StructureDialog sd = opened;
            final String[] titles = new String[1];
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    titles[0] = join(sd.tabTitles(), "|");
                }
            });
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    sd.dispose();
                }
            });
            int n = titles[0] == null || titles[0].isEmpty()
                    ? 0 : titles[0].split("\\|").length;
            put("dblclick.structure", "1|" + n + "|" + titles[0]);
        } catch (Exception e) {
            put("dblclick.structure", "err|0|" + e);
        }
    }

    private static String names() {
        StringBuilder sb = new StringBuilder();
        for (TableOps.Table t : frame.tables()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(t.tableName);
        }
        return sb.toString();
    }

    /** 压缩包：展开几个、汇总怎么说、包内文件的来源 tooltip、临时目录能不能清掉。 */
    private static void zipRoundtrip(final List<String> files) throws Exception {
        final File zdir = new File(tmp, "zip");
        mkdirs(zdir);
        final File zip = new File(zdir, "pack1.zip");
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zip));
        zos.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
        zos.write("不是 Excel".getBytes(java.nio.charset.Charset.forName("UTF-8")));
        zos.closeEntry();
        zos.putNextEntry(new java.util.zip.ZipEntry("inner.xlsx"));
        zos.write(java.nio.file.Files.readAllBytes(
                new File(files.get(0)).toPath()));
        zos.closeEntry();
        zos.close();
        ui(new Runnable() {
            @Override
            public void run() {
                int before = frame.tables().size();
                String mark = frame.logPanel().text();
                List<String> one = new ArrayList<String>();
                one.add(zip.getPath());
                frame.addFiles(one);
                put("zip.log", tail(mark, frame.logPanel().text()));
                put("zip.added", String.valueOf(frame.tables().size() - before));
                put("zip.popup", popup());
                int last = frame.fileModel().getRowCount() - 1;
                put("zip.tooltip", last < 0 ? "" : frame.fileModel().tooltipAt(last));
            }
        });
    }

    private static void prefixSteps() throws Exception {
        ui(new Runnable() {
            @Override
            public void run() {
                frame.prefixField().setText("ods_");
                frame.applyPrefix();
                put("prefix.ods.names", names());
                frame.prefixField().setText("change");
                frame.replaceFirstPrefix();
                put("prefix.change.names", names());
                frame.prefixField().setText("");
                frame.applyPrefix();
                put("prefix.cleared.names", names());
            }
        });
    }

    private static void dialogs() throws Exception {
        ui(new Runnable() {
            @Override
            public void run() {
                DDLDialog plain = new DDLDialog(frame, frame.tables(), false);
                try {
                    StringBuilder tabs = new StringBuilder();
                    for (int i = 0; i < plain.tabCount(); i++) {
                        if (i > 0) {
                            tabs.append('|');
                        }
                        tabs.append(plain.tabTitleAt(i));
                    }
                    put("dlg.ddl.want", want(plain));
                    put("env.dlg.ddl.size", clientSize(plain));
                    put("ddl.tabs", tabs.toString());
                    put("ddl.first", plain.tabCount() == 0 ? "" : plain.tabText(0));
                } finally {
                    plain.dispose();
                }
                frame.dropExistingBox().setSelected(true);
                frame.refreshPreview();
                DDLDialog dropping = new DDLDialog(frame, frame.tables(), true);
                try {
                    put("ddl.drop.first", dropping.tabCount() == 0 ? "" : dropping.tabText(0));
                } finally {
                    dropping.dispose();
                }
                frame.dropExistingBox().setSelected(false);
                frame.refreshPreview();

                List<com.excel2mysql.core.TableBuilder.Result> two =
                        new ArrayList<com.excel2mysql.core.TableBuilder.Result>(
                                frame.results());
                while (two.size() > 2) {
                    two.remove(two.size() - 1);
                }
                StructureDialog sd = new StructureDialog(frame, two, frame.logPanel());
                try {
                    put("dlg.structure.title", sd.getTitle());
                    put("dlg.structure.want", want(sd));
                    put("env.dlg.structure.size", clientSize(sd));
                    put("dlg.structure.tabs", join(sd.tabTitles(), "|"));
                    StringBuilder cols = new StringBuilder();
                    for (int c = 0; c < 5; c++) {
                        if (c > 0) {
                            cols.append('|');
                        }
                        cols.append(sd.columnHeader(0, c));
                    }
                    put("dlg.structure.cols", cols.toString());
                    StringBuilder rows = new StringBuilder();
                    for (int r = 0; r < Math.min(3, sd.rowCount(0)); r++) {
                        if (r > 0) {
                            rows.append('\n');
                        }
                        for (int c = 0; c < 5; c++) {
                            if (c > 0) {
                                rows.append(';');
                            }
                            rows.append(flag(sd.modelValue(0, r, c)));
                        }
                    }
                    put("dlg.structure.rows", rows.toString());
                } finally {
                    sd.dispose();
                }

                HelpDialog hd = new HelpDialog(frame);
                try {
                    put("dlg.help.title", hd.getTitle());
                    put("dlg.help.want", want(hd));
                    put("env.dlg.help.size", clientSize(hd));
                    StringBuilder toc = new StringBuilder();
                    for (int i = 0; i < hd.tocCount(); i++) {
                        if (i > 0) {
                            toc.append('|');
                        }
                        toc.append(hd.tocTitle(i));
                    }
                    put("dlg.help.toc", toc.toString());
                } finally {
                    hd.dispose();
                }

                AboutDialog ad = new AboutDialog(frame);
                try {
                    put("dlg.about.title", ad.getTitle());
                    put("dlg.about.want", want(ad));
                    put("env.dlg.about.size", clientSize(ad));
                    put("dlg.about.rows", join(ad.rowCaptions(), "|"));
                } finally {
                    ad.dispose();
                }

                ProgressDialog pd = new ProgressDialog(null, "正在解析文件…");
                try {
                    pd.setTotal(3);
                    pd.step("读盘");
                    put("dlg.progress.title", pd.getTitle());
                    put("dlg.progress.want", want(pd));
                    put("env.dlg.progress.size", clientSize(pd));
                    put("dlg.progress.status", pd.statusText());
                    put("dlg.progress.percent", pd.percentText());
                    put("dlg.progress.cancel", pd.cancelText());
                } finally {
                    pd.dispose();
                }
            }
        });
    }

    private static String flag(String v) {
        return "true".equals(v) ? "1" : ("false".equals(v) ? "0" : v);
    }

    /** 最近一次弹窗的"图标|标题|正文"，与 Python 侧 popup_text() 同一形状。 */
    /**
     * 最近一次弹窗的"图标|标题|正文"。
     *
     * 正文去掉换行再比：换行位置由折行规则按字符宽度算，两边弹窗里带的产物目录
     * 长度本来就不一样，折出来的行也不同。排版本身另有 wrap.single / wrap.path /
     * wrap.tall 三个键钉住（同样的输入必须折出同样的串），这里只比"说的话"。
     */
    private static String popup() {
        return frame.lastDialogIcon() + "|" + frame.lastDialogTitle() + "|"
                + popupBody(frame.lastDialogText());
    }

    /** 弹窗正文去掉折行引入的换行，只留内容。 */
    private static String popupBody(String text) {
        return text == null ? "" : text.replace("\n", "");
    }

    /** 日志的最后一条非空行：改名的回执要逐句与 Python 侧对。 */
    private static String lastLogLine(String text) {
        String s = text == null ? "" : text.replace("\r", "");
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                return lines[i];
            }
        }
        return "";
    }

    /** 目标库信息条是哪一档颜色：绿色=已选定、红色=没选库，别的写法算 none。 */
    private static String dbTone(com.excel2mysql.gui.HintLabel h) {
        int rgb = h == null ? 0 : h.getForeground().getRGB();
        if (rgb == 0xff1a7f37) {
            return "ok";
        }
        return rgb == 0xffc62828 ? "bad" : "none";
    }

    /** 配置值：Windows.clientSize 把"客户区本应多大"记在内容面板上，两条线必须写同一个数。 */
    private static String want(java.awt.Window w) {
        javax.swing.JComponent c = content(w);
        Object v = c == null ? null : c.getClientProperty("e2mWant");
        return v == null ? "" : String.valueOf(v);
    }

    /** 实测客户区尺寸：现场屏幕不同、夹取结果就不同，只留档不跨线比。 */
    private static String clientSize(java.awt.Window w) {
        javax.swing.JComponent c = content(w);
        return c == null ? "" : c.getWidth() + "x" + c.getHeight();
    }

    private static javax.swing.JComponent content(java.awt.Window w) {
        java.awt.Container c = w instanceof javax.swing.JFrame
                ? ((javax.swing.JFrame) w).getContentPane()
                : w instanceof javax.swing.JDialog
                        ? ((javax.swing.JDialog) w).getContentPane() : null;
        return c instanceof javax.swing.JComponent ? (javax.swing.JComponent) c : null;
    }

    /** 日志新增的那几行：两边都按"操作前后取差"来读，不靠整篇文本对齐。 */
    private static String tail(String before, String after) {
        String a = after == null ? "" : after;
        String b = before == null ? "" : before;
        return a.startsWith(b) ? a.substring(b.length()).trim() : a.trim();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }
    private static void exportSteps() throws Exception {
        final File outRoot = new File(tmp, "out");
        mkdirs(outRoot);
        ui(new Runnable() {
            @Override
            public void run() {
                frame.generateExcel(true, outRoot.getPath());
                put("export.std.log", frame.logPanel().text());
                put("export.std.popup.title", frame.lastDialogTitle());
                put("export.std.popup.text", popupBody(frame.lastDialogText()));
            }
        });
        File std = firstDir(outRoot, "meta_data_file_");
        put("export.std.dir", std == null ? "" : std.getName());
        put("export.std.files", std == null ? "" : join(sortedNames(std), "|"));
        put("export.std.content", std == null ? "" : dumpFolder(std));
        final File outRoot2 = new File(tmp, "out2");
        mkdirs(outRoot2);
        ui(new Runnable() {
            @Override
            public void run() {
                frame.generateExcel(false, outRoot2.getPath());
            }
        });
        File sim = firstDir(outRoot2, "simple_meta_data_file_");
        put("export.simple.dir", sim == null ? "" : sim.getName());
        put("export.simple.content", sim == null ? "" : dumpFolder(sim));
    }

    private static void sqlSteps() throws Exception {
        final File dir = new File(tmp, "sql");
        mkdirs(dir);
        final String a = new File(dir, "a.sql").getPath();
        final String b = new File(dir, "b.sql").getPath();
        writeText(a, "CREATE TABLE t_order (id INT);\n"
                + "INSERT INTO `t_order` VALUES (1);\n"
                + "CREATE TABLE plain (id INT);\n");
        writeText(b, "CREATE TABLE dim_x (id INT);\n");
        // 第二个页签的两颗生成按钮：脚本正文 -> 标准/简易件。两边喂同一段输入，
        // 说明行、文件名、Sheet 形状与"能否被自己那条线原样读回"都要一致。
        final File gdir = new File(tmp, "sqlgen");
        mkdirs(gdir);
        final String nl2 = String.valueOf((char) 10);
        final String probe = new File(gdir, "probe.sql").getPath();
        writeText(probe, "CREATE TABLE `t_gen` (" + nl2
            + "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键'," + nl2
            + "  `st` enum('a','b') DEFAULT 'a'," + nl2
            + "  PRIMARY KEY (`id`)" + nl2
            + ") ENGINE=InnoDB COMMENT='生成检查表';" + nl2
            + "CREATE TABLE t_dup9 (x INT);" + nl2
            + "CREATE TABLE t_dup9 (y INT);" + nl2
            + "CREATE TABLE t_like9 LIKE t_dup9;" + nl2);
        ui(new Runnable() {
            @Override
            public void run() {
                List<String> paths = new ArrayList<String>();
                paths.add(a);
                paths.add(b);
                frame.selectTab(1);
                frame.sqlPage().addFiles(paths);
                dumpSqlTable();
                put("sql.log", frame.sqlPage().logPanel().text());
                put("sql.add.popup", popup().replace(dir.getPath(), "根目录"));
                frame.sqlPage().setPrefixText("ods_");
                frame.sqlPage().applyPrefix();
                dumpSqlTable();
                put("sql.prefixed.log", frame.sqlPage().logPanel().text());
                put("sql.hint", frame.sqlPage().hintText());
                frame.sqlPage().setPrefixText("");
                // 走按钮监听器同一条路：拿提示再交给 host.warn，不自己拼弹窗文案
                String beforeDialog = frame.lastDialogTitle() + "|" + frame.lastDialogText();
                String hint = frame.sqlPage().applyPrefix();
                if (hint != null) {
                    frame.warn(hint);
                }
                String afterDialog = frame.lastDialogTitle() + "|" + frame.lastDialogText();
                put("guard.sql.empty_prefix",
                        beforeDialog.equals(afterDialog) ? "" : popup());

                String smark = frame.sqlPage().logPanel().text();
                frame.sqlPage().saveModified();
                put("sql.save.log", tail(smark, frame.sqlPage().logPanel().text()));
                put("sql.save.popup", popup().replace(dir.getPath(), "根目录"));
                String ehint = frame.sqlPage().execute();
                if (ehint != null) {
                    frame.warn(ehint);
                }
                put("sql.execute.nodb", popup());
                // 「清空待上传文件」与第一页同语义：整份清出清单；放在保存/执行之后取证，
                // 否则后面这两步没有脚本可处理，比的就是空清单守卫而不是保存与执行
                String cmark = frame.sqlPage().logPanel().text();
                frame.sqlPage().clearAllFiles();
                put("sql.remove.log", tail(cmark, frame.sqlPage().logPanel().text()));
                final List<String> gp = new ArrayList<String>();
                gp.add(probe);
                String gmark = frame.sqlPage().logPanel().text();
                frame.sqlPage().addFiles(gp);
                frame.sqlPage().generateExcel(true, new File(gdir, "std").getPath());
                frame.sqlPage().generateExcel(false, new File(gdir, "smp").getPath());
                // 整段日志原样比：解析说明、逐文件导出行、完成行，两边必须同字同序。
                // 临时根目录与日期目录名换掉，否则比的是本机路径。
                String genLog = tail(gmark, frame.sqlPage().logPanel().text())
                        .replace(gdir.getPath(), "根目录")
                        .replaceAll("(simple_)?meta_data_file_\\d{8}", "$1meta_data_file_日期");
                put("sqlgen.log", genLog);
                put("sqlgen.popup", popup().replace(gdir.getPath(), "根目录")
                        .replaceAll("(simple_)?meta_data_file_\\d{8}", "$1meta_data_file_日期"));
            }
        });
        // 落盘产物只能在界面无任务后再读：读 xlsx 与"能不能被本条线原样读回"都不碰 UI 线程
        File stdDir = firstDir(new File(gdir, "std"), "sql_meta_data_file_");
        File smpDir = firstDir(new File(gdir, "smp"), "sql_simple_meta_data_file_");
        put("sqlgen.std.content", stdDir == null ? "" : dumpFolder(stdDir));
        put("sqlgen.simple.content", smpDir == null ? "" : dumpFolder(smpDir));
        String genFile = pickProduced(new File(gdir, "std"), "t_gen");
        try {
            put("sqlgen.roundtrip", readBackGen(genFile));
        } catch (Exception e) {
            put("sqlgen.roundtrip", "读不回 " + e.getClass().getSimpleName());
        }
    }

    private static void cleared() throws Exception {
        ui(new Runnable() {
            @Override
            public void run() {
                frame.clearAll();
                dumpTable("file", frame.fileTable());
                for (String t : new String[]{"开始导入", "生成标准excel文件", "生成简易excel文件"}) {
                    put("cleared.on." + t, btnOn(t));
                }
            }
        });
    }

    /** 产物目录里挑第一个名字含 needle 的文件（日期目录深度固定为 1，直接递归找）。 */
    private static String pickProduced(File root, String needle) {
        File[] kids = root.listFiles();
        if (kids == null) {
            return "";
        }
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            if (k.isDirectory()) {
                String inner = pickProduced(k, needle);
                if (!inner.isEmpty()) {
                    return inner;
                }
            } else if (k.getName().contains(needle)) {
                return k.getPath();
            }
        }
        return "";
    }

    /**
     * 用本条线自己的读取器把标准件读回来，渲染成一行给对撞器比（读不回就是缺陷）。
     * 这里验的是"产物能否原样读回"，与界面上的命名开关无关：Python 侧走
     * build_table_def 的默认口径，Java 侧也得用默认 Names，不能借 frame 的那一份
     * （默认勾上首字母 + 大写后，标准件里的 t_gen 会被改写成 T_GEN）。
     */
    private static String readBackGen(String path) {
        com.excel2mysql.core.Names plain = new com.excel2mysql.core.Names(
                com.excel2mysql.core.PinyinLoader.get());
        com.excel2mysql.core.TableBuilder.Result r = new com.excel2mysql.core.TableBuilder(
                new com.excel2mysql.core.Infer(plain), plain)
                .build(path);
        StringBuilder sb = new StringBuilder();
        sb.append(r.table.source).append('|').append(r.table.tableName).append('|')
                .append(r.table.tableComment == null ? "" : r.table.tableComment);
        for (com.excel2mysql.core.Infer.Column c : r.table.columns) {
            sb.append('|').append(c.field).append(':').append(c.type)
                    .append(':').append(c.primaryKey ? "pk" : "-");
        }
        return sb.toString();
    }

    private static List<String> sortedNames(File dir) {
        List<String> out = new ArrayList<String>();
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                out.add(k.getName());
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static File firstDir(File root, String prefix) {
        List<String> names = sortedNames(root);
        for (String n : names) {
            if (n.startsWith(prefix)) {
                return new File(root, n);
            }
        }
        return null;
    }

    /** 与 Python 侧 dump_xlsx() 同形状：### 文件名 / [Sheet 名] / 每格用 ; 连接。 */
    private static String dumpFolder(File dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String name : sortedNames(dir)) {
            List<MetaExport.Dump> dumps = MetaExport.dump(new File(dir, name).getPath());
            sb.append("### ").append(name).append('\n');
            for (MetaExport.Dump d : dumps) {
                int width = 0;
                for (List<Object> row : d.cells) {
                    width = Math.max(width, row.size());
                }
                sb.append('[').append(d.sheet).append(']').append('\n');
                for (List<Object> row : d.cells) {
                    for (int c = 0; c < width; c++) {
                        if (c > 0) {
                            sb.append(';');
                        }
                        Object v = c < row.size() ? row.get(c) : null;
                        sb.append(v == null ? "" : textOf(v));
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 与 Python 的 str(value) 对齐：读取层已把整值浮点收成 BigInteger。 */
    private static String textOf(Object v) {
        return String.valueOf(v);
    }
    private static void ui(Runnable onEdt) throws Exception {
        SwingUtilities.invokeAndWait(onEdt);
    }

    private static void put(String key, String value) {
        D.put(key, norm(value));
    }

    private static final java.util.regex.Pattern PATH_RE =
            java.util.regex.Pattern.compile("[A-Za-z]:[\\\\/][^|;\"'\\]\\[）)]*");
    private static final java.util.regex.Pattern DAY_RE =
            java.util.regex.Pattern.compile("(meta_data_file_|simple_meta_data_file_)\\d{8}");

    /** 与 Python 侧 norm() 同一套归一：绝对路径收成 <P>名、日期目录收成 <DAY>、去首尾空白。 */
    private static String norm(String text) {
        String s = text == null ? "" : text.replace("\r", "");
        java.util.regex.Matcher m = PATH_RE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String p = m.group(0);
            String trimmed = p.replaceAll("[\\\\/]+$", "");
            int at = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    "<P>" + (at < 0 ? trimmed : trimmed.substring(at + 1))));
        }
        m.appendTail(sb);
        s = DAY_RE.matcher(sb.toString()).replaceAll("$1<DAY>");
        return s.trim();
    }

    /** 递归收集这棵子树上的静态文案（去空白后的 JLabel 文字）。 */
    private static void traverseLabels(java.awt.Component c, java.util.List<String> out) {
        if (c instanceof javax.swing.JLabel) {
            String t = norm(((javax.swing.JLabel) c).getText());
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        // 目标库信息条在 Swing 这边是 HintLabel（为了能被拖选，用不可编辑的 JTextField 顶的
        // QLabel），它不是 JLabel，但确实"是这一页上的一句话"，所以要一起收进来
        if (c instanceof com.excel2mysql.gui.HintLabel) {
            String t = norm(((com.excel2mysql.gui.HintLabel) c).hintText());
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component kid : ((java.awt.Container) c).getComponents()) {
                traverseLabels(kid, out);
            }
        }
    }

    private static String pageOf(java.awt.Component c) {
        javax.swing.JTabbedPane tabs = frame.tabs();
        if (tabs == null) {
            return "顶部";
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            java.awt.Component page = tabs.getComponentAt(i);
            java.awt.Component cur = c;
            while (cur != null) {
                if (cur == page) {
                    return tabs.getTitleAt(i);
                }
                cur = cur.getParent();
            }
        }
        return "顶部";
    }

    private static void dumpButtons() {
        List<String> seen = new ArrayList<String>();
        for (JButton b : frame.buttons()) {
            String t = norm(b.getText());
            if (t.isEmpty()) {
                continue;
            }
            String key = "btn." + pageOf(b) + "." + t;
            seen.add(pageOf(b) + "|" + t);
            put(key + ".on", b.isEnabled() ? "1" : "0");
            put(key + ".tip", b.getToolTipText());
        }
        java.util.Collections.sort(seen);
        put("btn.list", join(seen, "|"));
        java.awt.Container bar = frame.actionBar();
        List<String> order = new ArrayList<String>();
        if (bar != null) {
            for (java.awt.Component c : bar.getComponents()) {
                if (c instanceof JButton && norm(((JButton) c).getText()).length() > 0) {
                    order.add(norm(((JButton) c).getText()));
                }
            }
        }
        put("bar.excel.order", join(order, "|"));
        dumpSqlRows();
    }

    /**
     * 第二页每一行从左到右是哪几颗按钮——位置也是界面事实，与 Python 侧 dump_sql_rows 同判据。
     *
     * 按钮文案早就在逐颗比，但"清空待上传文件排在开关那一行、还是排在保存/执行那一行"
     * 只有看真正的行结构才比得出来。按像素量不行：这一页没被切到前面时子控件还没排过版，
     * 量出来全是 0（Python 侧第一版就是这么全落在第 0 带），所以直接读三行的容器。
     */
    private static void dumpSqlRows() {
        java.awt.Container[] rows = frame.sqlPage().sqlRows();
        List<String> out = new ArrayList<String>();
        int band = 0;
        for (java.awt.Container row : rows) {
            if (row == null) {
                continue;
            }
            List<String> names = new ArrayList<String>();
            collectRowButtons(row, names);
            if (!names.isEmpty()) {
                out.add(band + ":" + join(names, ","));
                band++;
            }
        }
        put("bar.sql.rows", join(out, "|"));
    }

    /** 一行之内的按钮顺序就是加进去的顺序：FlowLayout 从左到右、BorderLayout 先 WEST 后 EAST。 */
    private static void collectRowButtons(java.awt.Container root, List<String> names) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof JButton && !norm(((JButton) c).getText()).isEmpty()) {
                names.add(norm(((JButton) c).getText()));
            }
            if (c instanceof java.awt.Container) {
                collectRowButtons((java.awt.Container) c, names);
            }
        }
    }

    private static String btnOn(String text) {
        for (JButton b : frame.buttons()) {
            if (text.equals(b.getText()) && "Excel 导入".equals(pageOf(b))) {
                return b.isEnabled() ? "1" : "0";
            }
        }
        return "缺";
    }

    private static void dumpWidget(String role, java.awt.Component w) {
        if (w == null) {
            put("w." + role + ".missing", "1");
            return;
        }
        put("w." + role + ".kind", w.getClass().getSimpleName());
        String tip = w instanceof javax.swing.JComponent
                ? ((javax.swing.JComponent) w).getToolTipText() : null;
        put("w." + role + ".tip", tip);
        put("w." + role + ".on", w.isEnabled() ? "1" : "0");
        // 用 isShowing 而不是 isVisible：没选中的页签里控件 isVisible 仍为 true，
        // 而 Qt 的 isVisible() 已经把"祖先不可见"算进去了，两边要同一口径
        put("w." + role + ".vis", w.isShowing() ? "1" : "0");
        if (w instanceof JTextField) {
            put("w." + role + ".text", ((JTextField) w).getText());
            put("w." + role + ".echo", w instanceof JPasswordField ? "Password" : "Normal");
            put("w." + role + ".ph", w instanceof com.excel2mysql.gui.HintTextField
                    ? ((com.excel2mysql.gui.HintTextField) w).hintText() : "");
        }
        if (w instanceof JSpinner) {
            javax.swing.SpinnerNumberModel model =
                    (javax.swing.SpinnerNumberModel) ((JSpinner) w).getModel();
            put("w." + role + ".value", String.valueOf(model.getNumber()));
            put("w." + role + ".min", String.valueOf(model.getMinimum()));
            put("w." + role + ".max", String.valueOf(model.getMaximum()));
        }
        if (w instanceof JCheckBox) {
            put("w." + role + ".text", ((JCheckBox) w).getText());
            put("w." + role + ".checked", ((JCheckBox) w).isSelected() ? "1" : "0");
        }
        if (w instanceof JProgressBar) {
            JProgressBar pb = (JProgressBar) w;
            put("w." + role + ".value", String.valueOf(pb.getValue()));
            put("w." + role + ".min", String.valueOf(pb.getMinimum()));
            put("w." + role + ".max", String.valueOf(pb.getMaximum()));
            put("w." + role + ".text", pb.isStringPainted() && pb.getString() != null
                    ? pb.getString() : "");
        }
        if (w instanceof JComboBox) {
            JComboBox<?> cb = (JComboBox<?>) w;
            List<String> items = new ArrayList<String>();
            for (int i = 0; i < cb.getItemCount(); i++) {
                items.add(String.valueOf(cb.getItemAt(i)));
            }
            put("w." + role + ".items", join(items, ","));
        }
    }

    private static void dumpTable(String role, JTable t) {
        if (t == null) {
            return;
        }
        List<String> cols = new ArrayList<String>();
        for (int c = 0; c < t.getColumnCount(); c++) {
            cols.add(String.valueOf(t.getColumnName(c)));
        }
        put("table." + role + ".cols", join(cols, "|"));
        put("table." + role + ".width", String.valueOf(t.getColumnCount()));
        put("table." + role + ".zebra", Tables.isZebra(t) ? "1" : "0");
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < t.getRowCount(); r++) {
            if (r > 0) {
                rows.append('\n');
            }
            for (int c = 0; c < t.getColumnCount(); c++) {
                if (c > 0) {
                    rows.append(';');
                }
                Object v = t.getValueAt(r, c);
                rows.append(v == null ? "" : String.valueOf(v));
            }
        }
        put("table." + role + ".rows", rows.toString());
    }

    private static void dumpSqlTable() {
        SqlPage page = frame.sqlPage();
        List<String> cols = new ArrayList<String>();
        for (int c = 0; c < page.columnCount(); c++) {
            cols.add(page.columnName(c));
        }
        put("table.sql.cols", join(cols, "|"));
        put("table.sql.width", String.valueOf(page.columnCount()));
        put("table.sql.zebra", Tables.isZebra(page.tableWidget()) ? "1" : "0");
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < page.rowCount(); r++) {
            if (r > 0) {
                rows.append('\n');
            }
            for (int c = 0; c < page.columnCount(); c++) {
                if (c > 0) {
                    rows.append(';');
                }
                rows.append(page.cellText(r, c));
            }
        }
        put("table.sql.rows", rows.toString());
    }
    private static void mkdirs(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("建不出目录：" + dir.getPath());
        }
    }

    private static void writeText(String path, String text) throws Exception {
        mkdirs(new File(path).getParentFile());
        java.io.OutputStream os = new java.io.FileOutputStream(path);
        try {
            os.write(text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        } finally {
            os.close();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static void write(String path) throws Exception {
        File f = new File(path);
        mkdirs(f.getParentFile());
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        boolean first = true;
        for (Map.Entry<String, String> e : D.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  ").append(json(e.getKey())).append(": ")
                    .append(json(e.getValue()));
        }
        sb.append("\n}\n");
        java.io.OutputStream os = new java.io.FileOutputStream(f);
        try {
            os.write(sb.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")));
        } finally {
            os.close();
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", Integer.valueOf(c)));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
