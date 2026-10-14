package com.excel2mysql.verify;

import com.excel2mysql.core.MetaExport;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.gui.AboutDialog;
import com.excel2mysql.gui.DDLDialog;
import com.excel2mysql.gui.HelpDialog;
import com.excel2mysql.gui.MainFrame;
import com.excel2mysql.gui.ProgressDialog;
import com.excel2mysql.gui.SqlPage;
import com.excel2mysql.gui.StructureDialog;

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
        ui(new Runnable() {
            @Override
            public void run() {
                frame = new MainFrame();
                frame.getContentPane().setPreferredSize(new java.awt.Dimension(1100, 780));
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
                dumpWidget("progress", frame.pageProgressBar());
                dumpButtons();
                dumpTable("file", frame.fileTable());
                dumpSqlTable();
                put("hint.excel", frame.excelHintText());
                put("hint.sql", frame.sqlPage().hintText());
                put("drop.hint", frame.dropArea().hintText());
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
    }
    private static void load(final List<String> files) throws Exception {
        ui(new Runnable() {
            @Override
            public void run() {
                frame.addFiles(files);
                put("loaded.names", names());
                dumpTable("file", frame.fileTable());
                for (String t : new String[]{"开始导入", "生成标准excel文件", "生成简易excel文件"}) {
                    put("loaded.on." + t, btnOn(t));
                }
                put("loaded.log", frame.logPanel().text());
                put("loaded.hint.excel", frame.excelHintText());
                put("add.popup", popup());
                // Qt 顶层窗口的 width/height 量的是客户区，Java 的外框还含标题栏和边框，
                // 拿外框比就是在比两套窗口装饰，不是比界面本身
                java.awt.Dimension client = frame.getContentPane().getSize();
                put("window.size", client.width + "x" + client.height);
                java.awt.Image icon = frame.getIconImage();
                put("window.icon", icon == null ? "none"
                        : "max=" + Math.max(icon.getWidth(null), icon.getHeight(null)));
                put("file.tooltip.0", frame.fileModel().tooltipAt(0));
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
                    put("dlg.about.rows", join(ad.rowCaptions(), "|"));
                } finally {
                    ad.dispose();
                }

                ProgressDialog pd = new ProgressDialog(null, "正在解析文件…");
                try {
                    pd.setTotal(3);
                    pd.step("读盘");
                    put("dlg.progress.title", pd.getTitle());
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
    private static String popup() {
        return frame.lastDialogIcon() + "|" + frame.lastDialogTitle() + "|"
                + frame.lastDialogText();
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
                put("export.std.popup.text", frame.lastDialogText());
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
                frame.sqlPage().setPrefixText("ods_");
                frame.sqlPage().applyPrefix();
                dumpSqlTable();
                put("sql.prefixed.log", frame.sqlPage().logPanel().text());
                put("sql.hint", frame.sqlPage().hintText());
                frame.sqlPage().setPrefixText("");
                // 走按钮监听器同一条路：拿提示再交给 host.warn，不自己拼弹窗文案
                String hint = frame.sqlPage().applyPrefix();
                if (hint != null) {
                    frame.warn(hint);
                }
                put("guard.sql.empty_prefix", popup());

                String smark = frame.sqlPage().logPanel().text();
                frame.sqlPage().selectRow(0);
                frame.sqlPage().removeSelected();
                put("sql.remove.log", tail(smark, frame.sqlPage().logPanel().text()));
                smark = frame.sqlPage().logPanel().text();
                frame.sqlPage().saveModified();
                put("sql.save.log", tail(smark, frame.sqlPage().logPanel().text()));
                String ehint = frame.sqlPage().execute();
                if (ehint != null) {
                    frame.warn(ehint);
                }
                put("sql.execute.nodb", popup());
            }
        });
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
