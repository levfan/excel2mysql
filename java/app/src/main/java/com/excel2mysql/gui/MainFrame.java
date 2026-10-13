package com.excel2mysql.gui;

import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.Prefix;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.ZipImport;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import javax.swing.BorderFactory;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;

import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主窗口的第一片：添加 Excel（含 zip 展开）→ 表定义表格 → 建表语句预览 → 日志。
 *
 * 连库导入的按钮不在这一片里。界面上出现一个点了不会真写库的"开始导入"，
 * 比不给这个按钮更糟。
 */
public final class MainFrame extends JFrame implements SqlPage.Host {

    private static final String NO_FILE_HINT = "请先添加 Excel 文件或 ZIP 压缩包。";
    private static final String PREFIX_HINT =
            "可选，如 dim_（新添加的文件自动带上，留空点按钮可移除）；"
            + "填 change 再点「替换首个前缀」则换掉已有首段";
    private static final String REPLACE_HEAD_TIP =
            "把表名第一个下划线之前的部分换成上面填的内容："
            + "ods_t_order → change_t_order、adb_table2 → change_table2；"
            + "表名里没有下划线的（如 users）不动，日志会列出。末尾下划线不用写";

    private final FileRowModel fileModel = new FileRowModel();
    private final javax.swing.JTable table = new javax.swing.JTable(fileModel);
    private final JTextArea ddl = new JTextArea();
    private final LogPanel logPanel = new LogPanel();
    private JLabel status;
    private JButton preview;
    private JButton connect;
    private JButton importBtn;
    private javax.swing.JTextField hostField;
    private javax.swing.JTextField portField;
    private javax.swing.JTextField userField;
    private javax.swing.JPasswordField passField;
    private javax.swing.JTextField dbField;
    private SqlPage sqlPage;
    private javax.swing.JTabbedPane tabs;
    private DropArea drop;
    private javax.swing.JProgressBar pageProgress;
    private javax.swing.JTextField prefixField;
    private javax.swing.JCheckBox dropExisting;
    private final List<TableOps.Table> tables = new ArrayList<TableOps.Table>();
    private final Map<TableOps.Table, TableBuilder.Result> byTable =
            new java.util.LinkedHashMap<TableOps.Table, TableBuilder.Result>();
    private Config config;
    private boolean connected;
    private final Names names = new Names(PinyinLoader.get());
    private final TableBuilder builder = new TableBuilder(new Infer(names), names);

    public MainFrame() {
        super("excel2mysql");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1080, 700);
        setLocationByPlatform(true);
        setLayout(new BorderLayout());

        add(buildConnectionBar(), BorderLayout.NORTH);
        // 两个页签共用上方那条连接栏：口令只有一份，写在页签里会出现"这页连的是哪个库"的歧义
        tabs = new javax.swing.JTabbedPane();
        sqlPage = new SqlPage(this);
        tabs.addTab("Excel 导入", buildCenter());
        tabs.addTab("SQL 脚本", sqlPage);
        add(tabs, BorderLayout.CENTER);
    }

    /** 连接栏：口令只进 JPasswordField，全程不落文件、不进日志。 */
    private JPanel buildConnectionBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 4));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        JButton add = new JButton("添加文件");
        add.addActionListener(e -> chooseFiles());
        JButton clear = new JButton("清空列表");
        clear.addActionListener(e -> clearAll());
        JButton structure = new JButton("生成表结构预览");
        structure.addActionListener(e -> previewStructure());
        preview = new JButton("预览 DDL");
        preview.addActionListener(e -> refreshPreview());
        connect = new JButton("连接并加载库");
        connect.addActionListener(e -> connectAndLoad());
        importBtn = new JButton("开始导入");
        importBtn.setEnabled(false);
        importBtn.addActionListener(e -> startImport());
        prefixField = new javax.swing.JTextField(18);
        prefixField.setToolTipText(PREFIX_HINT);
        JButton applyPrefix = new JButton("批量应用前缀");
        applyPrefix.addActionListener(e -> applyPrefix());
        JButton replacePrefix = new JButton("替换首个前缀");
        replacePrefix.setToolTipText(REPLACE_HEAD_TIP);
        replacePrefix.addActionListener(e -> replaceFirstPrefix());
        dropExisting = new javax.swing.JCheckBox("覆盖已存在表（先 DROP 再建）");
        dropExisting.addActionListener(e -> refreshPreview());
        JButton clearLog = new JButton("清除日志");
        clearLog.addActionListener(e -> logPanel.clearLog());
        tools.add(add);
        tools.add(clear);
        tools.add(structure);
        tools.add(preview);
        tools.add(new JLabel("表名前缀："));
        tools.add(prefixField);
        tools.add(applyPrefix);
        tools.add(replacePrefix);
        tools.add(dropExisting);
        tools.add(connect);
        tools.add(clearLog);
        tools.add(importBtn);

        JPanel fields = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        hostField = new javax.swing.JTextField(12);
        portField = new javax.swing.JTextField(5);
        userField = new javax.swing.JTextField(10);
        passField = new javax.swing.JPasswordField(10);
        dbField = new javax.swing.JTextField(14);
        hostField.setText("127.0.0.1");
        portField.setText("3306");
        userField.setText("root");
        fields.add(new JLabel("主机"));
        fields.add(hostField);
        fields.add(new JLabel("端口"));
        fields.add(portField);
        fields.add(new JLabel("用户"));
        fields.add(userField);
        fields.add(new JLabel("口令"));
        fields.add(passField);
        fields.add(new JLabel("库"));
        fields.add(dbField);

        status = new JLabel("尚未连接");
        JPanel south = new JPanel(new BorderLayout());
        south.add(fields, BorderLayout.CENTER);
        south.add(status, BorderLayout.EAST);
        bar.add(tools, BorderLayout.NORTH);
        bar.add(south, BorderLayout.SOUTH);
        return bar;
    }

    private java.awt.Component buildCenter() {
        drop = new DropArea(paths -> {
            if (paths.isEmpty()) {
                log("拖拽里没有可读取的文件");
            } else {
                addFiles(paths);
            }
        });
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        // 双击进结构弹窗改字段与主键：就地编辑会让"改的是哪张表"变含糊
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editStructure();
                }
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new Dimension(300, 120));

        ddl.setEditable(false);
        ddl.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        JScrollPane ddlScroll = new JScrollPane(ddl);
        ddlScroll.setBorder(BorderFactory.createTitledBorder("建表语句预览"));
        ddlScroll.setMinimumSize(new Dimension(300, 120));

        JScrollPane logScroll = new JScrollPane(logPanel);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志"));

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ddlScroll, logScroll);
        right.setResizeWeight(0.62);
        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, right);
        center.setResizeWeight(0.52);

        // 三段都能拖着调高度：拖拽区被压到 0 就等于没有，所以给它设了最小高度
        JSplitPane stacked = new JSplitPane(JSplitPane.VERTICAL_SPLIT, drop, center);
        stacked.setResizeWeight(0.14);
        stacked.setDividerLocation(96);

        pageProgress = new javax.swing.JProgressBar();
        pageProgress.setPreferredSize(new Dimension(200, 18));
        JPanel page = new JPanel(new BorderLayout());
        page.add(stacked, BorderLayout.CENTER);
        page.add(pageProgress, BorderLayout.SOUTH);
        return page;
    }

    /** 连接并加载库：口令只从这里读，连上之前"开始导入"保持禁用。 */
    public boolean connectAndLoad() {
        Config cfg = new Config();
        cfg.host = hostField.getText().trim();
        try {
            cfg.port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            log("端口不是数字：" + portField.getText());
            return false;
        }
        cfg.user = userField.getText().trim();
        cfg.password = new String(passField.getPassword());
        cfg.database = dbField.getText().trim();
        if (!cfg.hasDatabase()) {
            log("没填目标库，拒绝连接——不然会连到服务器上随便挑一个库");
            return false;
        }
        try {
            DbClient client = new DbClient(cfg);
            List<String> dbs = client.listDatabases();
            client.close();
            config = cfg;
            connected = true;
            importBtn.setEnabled(!tables.isEmpty());
            status.setText("已连接 " + cfg.host + ":" + cfg.port + " / " + cfg.database
                    + "（服务器上共 " + dbs.size() + " 个库）");
            log(status.getText());
            return true;
        } catch (Exception e) {
            connected = false;
            logError("连接失败：" + e.getMessage());
            status.setText("连接失败");
            return false;
        }
    }

    /** 一张表的成功/失败，只带文件路径：收尾要按文件决定留不留。 */
    public static final class ImportOutcome {
        public final String filepath;
        public final boolean ok;

        public ImportOutcome(String filepath, boolean ok) {
            this.filepath = filepath;
            this.ok = ok;
        }
    }

    /**
     * 导入收尾：汇总日志 → 成功的文件移出列表、失败的留着便于改了再试 →
     * 弹窗与日志双写 → 进度归零。对应 main.py:1579-1613 的 _on_finished_all。
     */
    public String finishImport(List<ImportOutcome> outcomes, boolean cancelled) {
        int ok = 0;
        java.util.Set<String> doneFiles = new java.util.LinkedHashSet<String>();
        for (ImportOutcome o : outcomes) {
            if (o.ok) {
                ok++;
                doneFiles.add(o.filepath);
            }
        }
        int fail = outcomes.size() - ok;
        List<TableOps.Table> removed = new ArrayList<TableOps.Table>();
        List<TableOps.Table> kept = new ArrayList<TableOps.Table>();
        for (TableOps.Table t : tables) {
            if (doneFiles.contains(t.filepath)) {
                removed.add(t);
            } else {
                kept.add(t);
            }
        }
        String summary = "===== 全部完成：成功 " + ok + " 张表，失败 " + fail + " 张表 ====="
                + (cancelled ? "（已取消，未继续）" : "");
        if (fail > 0) {
            logError(summary);
        } else {
            log(summary);
        }
        if (!removed.isEmpty()) {
            log("[清理] 已从待导入列表移除成功文件：" + names(removed));
        }
        if (fail > 0 && !kept.isEmpty()) {
            log("[保留] 导入失败的文件仍在列表中，可调整后重试：" + names(kept));
        }
        tables.clear();
        tables.addAll(kept);
        for (TableOps.Table t : removed) {
            byTable.remove(t);
        }
        fileModel.removeItems(removed);
        setPageProgress(0);
        status.setText("待导入 " + tables.size() + " 张表");
        importBtn.setEnabled(connected && !tables.isEmpty());
        refreshPreview();

        StringBuilder msg = new StringBuilder("成功 ").append(ok).append(" 张表，失败 ")
                .append(fail).append(" 张表。");
        if (!removed.isEmpty()) {
            msg.append(NEWLINE + "已从待导入列表移除 ").append(removed.size()).append(" 个成功文件。");
        }
        if (fail > 0) {
            msg.append(NEWLINE + "失败文件保留在列表中，可调整后重试。");
        }
        msg.append(NEWLINE + "详见日志。");
        return msg.toString();
    }

    /** Java 源里的换行转义容易被工具链吃掉，弹窗文案统一走这个常量。 */
    private static final String NEWLINE = String.valueOf((char) 10);

    private static String names(List<TableOps.Table> list) {
        List<String> out = new ArrayList<String>();
        for (TableOps.Table t : list) {
            out.add(baseName(t.filepath));
        }
        return String.join("、", out);
    }

    /** 导入在子线程跑，界面靠模态进度框报进度并可取消。 */
    public void startImport() {
        if (!connected || config == null) {
            log("还没连接，不能导入");
            return;
        }
        if (tables.isEmpty()) {
            log("没有可导入的表");
            return;
        }
        final List<TableOps.Table> todo = new ArrayList<TableOps.Table>(tables);
        // 勾选状态在 EDT 上读一次：子线程里再点组件属于跨线程摸 Swing
        final boolean drop = dropExisting.isSelected();
        final ProgressDialog progress = new ProgressDialog(this, "开始导入");
        progress.setTotal(todo.size() * 2);
        setEnabledForRun(false);
        setPageProgress(0);
        Thread worker = new Thread(() -> {
            List<ImportOutcome> outcomes = new ArrayList<ImportOutcome>();
            int done = 0;
            DbClient db = null;
            try {
                db = new DbClient(config);
            } catch (Exception e) {
                final String message = e.getMessage();
                SwingUtilities.invokeLater(() -> logError("连接失败：" + message));
                setEnabledForRun(true);
                progress.close();
                return;
            }
            final DbClient client = db;
            for (TableOps.Table t : todo) {
                if (progress.isCancelled()) {
                    break;
                }
                TableBuilder.Result r = byTable.get(t);
                done++;
                // 建表失败也会 continue 走出这一轮，所以计数放在开头：
                // 半途失败的表照样占一格，进度条不会停在"看起来还剩几张"
                reportTableProgress(done, todo.size());
                progress.step("建表 " + t.tableName);
                try {
                    client.createTable(Ddl.buildDdl(t.tableName, t.tableComment, t.columns, drop));
                } catch (Exception e) {
                    final String message = e.getMessage();
                    SwingUtilities.invokeLater(() -> logError("建表失败 " + t.tableName + " — " + message));
                    outcomes.add(new ImportOutcome(t.filepath, false));
                    continue;
                }
                progress.step("写入 " + t.tableName);
                try {
                    final String label = t.tableName;
                    long written = client.insertData(t.tableName, t.columns,
                            r == null ? new ArrayList<Object>() : r.headers,
                            r == null ? new ArrayList<List<Object>>() : r.rows,
                            new DbClient.WarnCallback() {
                                public void onWarn(String m) {
                                    SwingUtilities.invokeLater(() -> log(m));
                                }
                            }, null);
                    final long rows = written;
                    SwingUtilities.invokeLater(() -> log("导入完成 " + label + " 写入 " + rows + " 行"));
                } catch (Exception e) {
                    final String message = e.getMessage();
                    SwingUtilities.invokeLater(() -> logError("写入失败 " + t.tableName + " — " + message));
                    outcomes.add(new ImportOutcome(t.filepath, false));
                    continue;
                }
                outcomes.add(new ImportOutcome(t.filepath, true));
            }
            client.close();
            final boolean cancelled = progress.isCancelled();
            SwingUtilities.invokeLater(() -> {
                setEnabledForRun(true);
                progress.close();
                // 弹窗与日志双写：日志可回溯，弹窗防走神漏看（Python 侧同一句话写两处）
                inform(finishImport(outcomes, cancelled));
            });
        }, "excel2mysql-import");
        worker.setDaemon(true);
        worker.start();
        progress.open();
    }

    /** 进度只按表计：行数在子线程里跳太快，页内那条只会被刷成一团糊的。 */
    private void reportTableProgress(int done, int total) {
        final int percent = total <= 0 ? 0 : (int) (done * 100L / total);
        SwingUtilities.invokeLater(() -> setPageProgress(percent));
    }

    private void setEnabledForRun(boolean on) {
        connect.setEnabled(on);
        importBtn.setEnabled(on && connected && !tables.isEmpty());
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * 界面上用户能看见、能点的按钮。
     *
     * 只收有文字的 JButton：递归整棵树会把滚动条箭头、表头排序控件这些
     * 外观内部的 AbstractButton 也算进来，它们本来就不该有 ActionListener。
     */
    public java.util.List<javax.swing.JButton> buttons() {
        java.util.List<javax.swing.JButton> out = new ArrayList<javax.swing.JButton>();
        collectButtons(getContentPane(), out);
        return out;
    }

    private static void collectButtons(java.awt.Container c,
                                        java.util.List<javax.swing.JButton> out) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof javax.swing.JButton) {
                javax.swing.JButton b = (javax.swing.JButton) child;
                if (b.getText() != null && !b.getText().isEmpty()) {
                    out.add(b);
                }
            } else if (child instanceof java.awt.Container) {
                collectButtons((java.awt.Container) child, out);
            }
        }
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Excel / 压缩包 (*.xlsx, *.xls, *.zip)", "xlsx", "xls", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            log("未选择文件");
            return;
        }
        List<String> paths = new ArrayList<String>();
        for (File f : chooser.getSelectedFiles()) {
            paths.add(f.getPath());
        }
        addFiles(paths);
    }

    /** 给测试和命令行都走同一个入口，避免"测的是一条路、跑的是另一条"。 */
    public int addFiles(List<String> paths) {
        int added = 0;
        for (String path : paths) {
            File f = new File(path);
            if (f.isFile() && f.getName().toLowerCase().endsWith(".zip")) {
                added += expandZip(f);
            } else if (f.isFile() && ZipImport.isExcel(f.getName())) {
                if (addOne(path)) {
                    added++;
                }
            } else {
                log("跳过（不是 Excel 或 zip）：" + f.getName());
            }
        }
        if (!tables.isEmpty()) {
            String prefix = Names.pyStrip(prefixField == null ? "" : prefixField.getText());
            if (!prefix.isEmpty()) {
                TableOps.applyPrefix(tables, prefix);
            } else {
                TableOps.makeUniqueNames(tables);
            }
        }
        status.setText("已加载 " + tables.size() + " 张表 / " + fileModel.rowTotal() + " 个字段");
        refreshPreview();
        return added;
    }

    private int expandZip(File zip) {
        File dest = new File(System.getProperty("java.io.tmpdir"),
                "excel2mysql-gui-" + System.currentTimeMillis());
        if (!dest.mkdirs()) {
            log("建解压目录失败：" + dest.getPath());
            return 0;
        }
        try {
            ZipImport.Result r = ZipImport.extract(zip.getPath(), dest.getPath());
            log("展开 " + zip.getName() + "：Excel " + r.files.size() + " 个，忽略非 Excel "
                    + r.ignored.size() + " 个，嵌套 zip 不递归 " + r.nestedZips + " 个");
            int n = 0;
            for (ZipImport.Entry e : r.files) {
                if (addOne(e.path)) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            log("解压失败：" + zip.getName() + " — " + e.getMessage());
            return 0;
        }
    }

    private boolean addOne(String path) {
        try {
            TableBuilder.Result r = builder.build(path);
            tables.add(r.table);
            byTable.put(r.table, r);
            fileModel.add(new FileRowModel.Item(r.table, r));
            if (importBtn != null) {
                importBtn.setEnabled(connected);
            }
            log("已解析 " + baseName(path) + " → 表 " + r.table.tableName + "（"
                    + r.table.columns.size() + " 列 " + r.rows.size() + " 行，模式 "
                    + r.table.source + "）"
                    + (r.pkReason == null || r.pkReason.isEmpty() ? "" : "；主键：" + r.pkReason));
            for (Infer.Column c : r.table.columns) {
                if (c.typeNote != null) {
                    log("  类型说明：" + c.typeNote);
                }
            }
            return true;
        } catch (Exception e) {
            log("解析失败 " + baseName(path) + " — " + e.getMessage());
            return false;
        }
    }

    /** 表名可能在界面上被改过口径（前缀、主键勾选），预览每次都按当前状态重建。 */
    public void refreshPreview() {
        StringBuilder sb = new StringBuilder();
        for (TableOps.Table t : tables) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(Ddl.buildDdl(t.tableName, t.tableComment, t.columns,
                    dropExisting != null && dropExisting.isSelected()));
        }
        ddl.setText(sb.toString());
        ddl.setCaretPosition(0);
    }

    public List<TableOps.Table> tables() {
        return tables;
    }

    /** 结构弹窗要的是"表定义 + 数据行"，数据行只存在 Result 里，按当前表顺序给出去。 */
    public List<TableBuilder.Result> results() {
        List<TableBuilder.Result> out = new ArrayList<TableBuilder.Result>();
        for (TableOps.Table t : tables) {
            out.add(byTable.get(t));
        }
        return out;
    }

    public FileRowModel fileModel() {
        return fileModel;
    }

    /** 双击选中行打开结构弹窗，确认后重算 DDL 预览。 */
    public boolean editStructure() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return false;
        }
        FileRowModel.Item item = fileModel.itemAt(row);
        List<TableBuilder.Result> one = new ArrayList<TableBuilder.Result>();
        one.add(item.result);
        StructureDialog dialog = new StructureDialog(this, one, logPanel);
        dialog.setVisible(true);
        if (!dialog.isConfirmed()) {
            return false;
        }
        log("[结构] " + item.table.tableName + " 已按弹窗修改");
        fileModel.refresh();
        refreshPreview();
        return true;
    }

    public String ddlText() {
        return ddl.getText();
    }

    public String statusText() {
        return status.getText();
    }

    public void clearAll() {
        tables.clear();
        byTable.clear();
        fileModel.clear();
        ddl.setText("");
        status.setText("尚未添加文件");
        setPageProgress(0);
        log("已清空");
    }

    public void log(String text) {
        logPanel.log(text);
    }

    /** 错误要整条标红：一屏日志里靠眼睛扫"失败"两个字太容易漏。 */
    public void logError(String text) {
        logPanel.error(text);
    }

    /** 自检与命令行入口要能按用户的方式操作这两个控件，而不是绕过界面直接改数据。 */
    public javax.swing.JTextField prefixField() {
        return prefixField;
    }

    public javax.swing.JCheckBox dropExistingBox() {
        return dropExisting;
    }

    @Override
    public boolean connected() {
        return isConnected();
    }

    @Override
    public Config config() {
        return config;
    }

    @Override
    public java.awt.Frame owner() {
        return this;
    }

    /** 页签名要与 Python 完全一致，切页时两条线各自的日志也还在。 */
    public List<String> tabTitles() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            out.add(tabs.getTitleAt(i));
        }
        return out;
    }

    public SqlPage sqlPage() {
        return sqlPage;
    }

    /** 页内进度条：导入按"当前表完成数 / 总表数"报百分比，跑完归零。 */
    public void setPageProgress(int percent) {
        if (pageProgress != null) {
            pageProgress.setValue(Math.max(0, Math.min(100, percent)));
        }
    }

    public int pageProgressValue() {
        return pageProgress == null ? -1 : pageProgress.getValue();
    }

    public DropArea dropArea() {
        return drop;
    }

    public LogPanel logPanel() {
        return logPanel;
    }

    /**
     * 批量应用前缀：每张表都按 base_table_name 重算，所以重复点不会把前缀叠上去。
     * 输入框留空再点就是移除前缀、恢复基础表名，和 Python 侧同一个输入框兼做两件事。
     */
    public void applyPrefix() {
        if (tables.isEmpty()) {
            inform(NO_FILE_HINT);
            return;
        }
        String prefix = Names.pyStrip(prefixField.getText());
        TableOps.applyPrefix(tables, prefix);
        fileModel.refresh();
        log(prefix.isEmpty() ? "[前缀] 已移除前缀，恢复基础表名"
                             : "[前缀] 已为全部表名批量添加前缀「" + prefix + "」");
        refreshPreview();
    }

    /** 替换首个前缀：清洗、校验、跳过规则都在 core 里，界面只负责把改了哪些说清楚。 */
    public void replaceFirstPrefix() {
        if (tables.isEmpty()) {
            inform(NO_FILE_HINT);
            return;
        }
        String head = Prefix.clean(prefixField.getText());
        if (head.isEmpty()) {
            warn("请先输入新的前缀（如 change）。");
            return;
        }
        if (!Prefix.isValid(head)) {
            warn("前缀「" + head + "」含非法字符：只能用字母、数字、下划线；"
                    + "末尾的下划线不用写，原表名自带。");
            return;
        }
        TableOps.RenameResult r = TableOps.replaceFirstSegment(tables, head);
        for (Map<String, String> e : r.renamed) {
            log("[换前缀] " + e.get("old") + " → " + e.get("new"));
        }
        if (!r.skipped.isEmpty()) {
            log("[跳过] 表名里没有下划线，未改动：" + String.join("、", r.skipped));
        }
        log("[换前缀完成] 共改 " + r.renamed.size() + " 张表"
                + (r.skipped.isEmpty() ? "" : "，跳过 " + r.skipped.size() + " 张"));
        fileModel.refresh();
        refreshPreview();
    }

    /** 结构预览：一张表一个标签页，改完确认才整体回写。 */
    public void previewStructure() {
        if (tables.isEmpty()) {
            inform(NO_FILE_HINT);
            return;
        }
        StructureDialog dialog = new StructureDialog(this, results(), logPanel);
        dialog.setVisible(true);
        if (!dialog.isConfirmed()) {
            return;
        }
        fileModel.refresh();
        refreshPreview();
        log("[结构] 已按弹窗确认 " + tables.size() + " 张表的字段定义");
    }

    public void inform(String text) {
        javax.swing.JOptionPane.showMessageDialog(this, text, "提示",
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    /** 拦下的操作除了弹窗还要落一行日志：弹窗关掉就没了，日志才是可回溯的那份。 */
    public void warn(String text) {
        log("已拦下：" + text);
        javax.swing.JOptionPane.showMessageDialog(this, text, "提示",
                javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }
}
