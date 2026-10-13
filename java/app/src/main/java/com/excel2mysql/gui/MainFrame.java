package com.excel2mysql.gui;

import com.excel2mysql.core.AppInfo;
import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.ImportRun;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.Prefix;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.ZipImport;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.LogText;
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
 * 主窗口：添加 Excel（含 zip 展开）→ 表定义 → 建表语句预览 → 连库导入 → 日志。
 *
 * 每个会耗时的入口都是"子线程算数据 + 模态进度框 + 主线程落界面"三段，
 * 界面从不自己算——两条路共用 core 里同一个工作函数，测的就是线上跑的那段。
 */
public final class MainFrame extends JFrame implements SqlPage.Host {

    private static final String NO_FILE_HINT = LogText.NO_FILE_HINT;
    private static final String PREFIX_HINT =
            "可选，如 dim_（新添加的文件自动带上，留空点按钮可移除）；"
            + "填 change 再点「替换首个前缀」则换掉已有首段";
    private static final String REPLACE_HEAD_TIP =
            "把表名第一个下划线之前的部分换成上面填的内容："
            + "ods_t_order → change_t_order、adb_table2 → change_table2；"
            + "表名里没有下划线的（如 users）不动，日志会列出。末尾下划线不用写";

    private final FileRowModel fileModel = new FileRowModel();
    private final javax.swing.JTable table = new javax.swing.JTable(fileModel) {
        @Override
        public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,
                                                  int row, int column) {
            java.awt.Component c = super.prepareRenderer(renderer, row, column);
            // 只给"文件"那一列挂来源：一行的 tooltip 太长会盖住别的列
            if (c instanceof javax.swing.JComponent && column == 0) {
                ((javax.swing.JComponent) c).setToolTipText(fileModel.tooltipAt(row));
            }
            return c;
        }
    };
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
    private javax.swing.JComboBox<String> dbCombo;
    private HintLabel impHint;
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
    private String zipSessionDir;
    private int zipDestSeq;
    private final Names names = new Names(PinyinLoader.get());
    private final TableBuilder builder = new TableBuilder(new Infer(names), names);

    public MainFrame() {
        // 标题带版本号：不点开「关于」也能看见现场跑的是哪一版
        super(AppInfo.load().titleText());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 780);
        applyWindowIcon();
        setLocationByPlatform(true);
        setLayout(new BorderLayout());

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildHeaderRow(), BorderLayout.NORTH);
        north.add(buildConnectionBar(), BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        // 两个页签共用上方那条连接栏：口令只有一份，写在页签里会出现"这页连的是哪个库"的歧义
        tabs = new javax.swing.JTabbedPane();
        sqlPage = new SqlPage(this);
        impHint = new HintLabel();
        tabs.addTab("Excel 导入", buildCenter());
        tabs.addTab("SQL 脚本", sqlPage);
        add(tabs, BorderLayout.CENTER);
        // 切页也要刷新：两页提示条同源，不刷就会停在"上一个页签当时看到的库"
        tabs.addChangeListener(e -> refreshDbLabels());
        bindHelpKey();
        refreshDbLabels();
    }

    /**
     * 窗口图标。源图只有 assets/app_icon.ico 一份，Java 的 ImageIO 不认 ico 容器，
     * 所以由 tools/export_icon_png.py 把里面最大的那张 PNG 抽成随包资源。
     */
    private void applyWindowIcon() {
        try {
            java.net.URL u = MainFrame.class.getResource("/app_icon.png");
            if (u == null) {
                log("! 缺 app_icon.png，窗口用系统默认图标");
                return;
            }
            setIconImage(javax.imageio.ImageIO.read(u));
        } catch (Exception e) {
            log("! 读窗口图标失败：" + LogText.describe(e));
        }
    }

    /**
     * 标题栏正下方一条极窄的工具行：Windows 的原生标题栏塞不进按钮，
     * 「?」放在最贴近标题栏的右上角，对应 main.py:838-858。
     */
    private JPanel buildHeaderRow() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        java.awt.Font bold = row.getFont().deriveFont(java.awt.Font.BOLD);
        JButton about = new JButton("i");
        about.setFont(bold);
        about.setPreferredSize(new Dimension(26, 22));
        about.setToolTipText("关于：版本、提交号与构建日期");
        about.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        about.addActionListener(e -> showAbout());
        JButton help = new JButton("?");
        help.setFont(bold);
        help.setPreferredSize(new Dimension(26, 22));
        help.setToolTipText("使用说明（按 F1 也可打开）");
        help.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        help.addActionListener(e -> showHelp());
        row.add(about);
        row.add(help);
        return row;
    }

    /** 说明与关于都是模态框；自检直接构造它们查内容，不走这两个可见入口。 */
    public void showHelp() {
        new HelpDialog(this).setVisible(true);
    }

    public void showAbout() {
        new AboutDialog(this).setVisible(true);
    }

    /** F1 与「?」是同一个入口：现场没人会先读手册，但一定会按 F1。 */
    private void bindHelpKey() {
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("F1"), "openHelp");
        getRootPane().getActionMap().put("openHelp", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showHelp();
            }
        });
    }

    /** 连接栏：口令只进 JPasswordField，全程不落文件、不进日志。 */
    private JPanel buildConnectionBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("MySQL 连接信息"),
                BorderFactory.createEmptyBorder(2, 8, 6, 8)));

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
        connect.addActionListener(e -> {
            String failure = loadDatabases();
            if (failure != null) {
                critical(failure);
            }
        });
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
        tools.add(clearLog);
        tools.add(importBtn);

        JPanel fields = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        hostField = new javax.swing.JTextField(12);
        javax.swing.event.DocumentListener refresher = new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refreshDbLabels();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refreshDbLabels();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refreshDbLabels();
            }
        };
        portField = new javax.swing.JTextField(5);
        userField = new javax.swing.JTextField(10);
        passField = new javax.swing.JPasswordField(10);
        dbCombo = new javax.swing.JComboBox<String>();
        // 库列表要先连上服务器才拿得到，没加载之前下拉是禁用的：
        // 让用户手填库名，等于把"连到一个不存在的库"推迟到导入那一刻才爆
        dbCombo.setEnabled(false);
        dbCombo.setPreferredSize(new Dimension(180, 24));
        dbCombo.addActionListener(e -> refreshDbLabels());
        hostField.setText("127.0.0.1");
        portField.setText("3306");
        userField.setText("root");
        hostField.getDocument().addDocumentListener(refresher);
        portField.getDocument().addDocumentListener(refresher);
        fields.add(new JLabel("主机："));
        fields.add(hostField);
        fields.add(new JLabel("端口："));
        fields.add(portField);
        fields.add(new JLabel("用户名："));
        fields.add(userField);
        fields.add(new JLabel("密码："));
        fields.add(passField);
        fields.add(new JLabel("目标库："));
        fields.add(dbCombo);
        fields.add(connect);

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
            List<String> accepted = acceptedDrops(paths);
            if (!accepted.isEmpty()) {
                addFilesAsync(accepted);
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
        page.add(impHint, BorderLayout.NORTH);
        page.add(stacked, BorderLayout.CENTER);
        page.add(pageProgress, BorderLayout.SOUTH);
        return page;
    }

    /** 界面上的连接信息拼成一份 Config；口令只在这一步离开输入框，不落文件。 */
    public Config currentConfig() {
        Config cfg = new Config();
        cfg.host = hostField.getText().trim();
        try {
            cfg.port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            cfg.port = -1;            // 交给下游按"连不上"处理，日志里会带上端口原文
        }
        cfg.user = userField.getText().trim();
        cfg.password = new String(passField.getPassword());
        cfg.database = selectedDatabase();
        return cfg;
    }

    public String selectedDatabase() {
        Object v = dbCombo.getSelectedItem();
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 自检与命令行入口选库用；真实用户是从下拉里点，两条路走的是同一个模型。 */
    public void setSelectedDatabase(String db) {
        if (dbCombo.getItemCount() == 0) {
            dbCombo.addItem(db);
            dbCombo.setEnabled(true);
        } else {
            boolean known = false;
            for (int i = 0; i < dbCombo.getItemCount(); i++) {
                if (db.equals(dbCombo.getItemAt(i))) {
                    dbCombo.setSelectedIndex(i);
                    known = true;
                    break;
                }
            }
            if (!known) {
                dbCombo.addItem(db);
                dbCombo.setSelectedItem(db);
            }
        }
        refreshDbLabels();
    }

    public boolean databaseChooserEnabled() {
        return dbCombo.isEnabled();
    }

    /**
     * 连接服务器并列出库。返回 null 表示成功；非 null 是弹窗正文，
     * 与日志用的是同一份展开文案（对应 main.py:1334-1355）。
     */
    public String loadDatabases() {
        Config cfg = currentConfig();
        try {
            DbClient client = new DbClient(cfg);
            List<String> dbs = client.listDatabases();
            client.close();
            String previous = selectedDatabase();
            dbCombo.removeAllItems();
            for (String d : dbs) {
                dbCombo.addItem(d);
            }
            dbCombo.setEnabled(true);
            if (!previous.isEmpty() && dbs.contains(previous)) {
                dbCombo.setSelectedItem(previous);
            }
            config = cfg;
            connected = true;
            importBtn.setEnabled(!tables.isEmpty());
            status.setText("已连接 " + cfg.host + ":" + cfg.port + "，服务器上共 "
                    + dbs.size() + " 个库");
            log("[已加载] 可用数据库：" + String.join(", ", dbs));
            refreshDbLabels();
            return null;
        } catch (Exception e) {
            connected = false;
            dbCombo.setEnabled(false);
            status.setText("连接失败");
            String detail = LogText.describe(e);
            logError("[连接失败] 无法连接到 " + cfg.host + ":" + cfg.port + "（用户 "
                    + cfg.user + "），库列表未加载" + LogText.NL + LogText.indentDetail(detail));
            refreshDbLabels();
            return detail;
        }
    }

    /**
     * 两页顶部的目标库提示条同源刷新：一条说 Excel 导到哪，一条说 SQL 跑到哪。
     * 各写一份的话，用户会在两个页签看到两个"当前库"。
     */
    public void refreshDbLabels() {
        if (impHint == null) {
            return;     // 连接栏比页签里的提示条先建，输入框赋初值时这里还不能动它们
        }
        String db = selectedDatabase();
        if (db.isEmpty()) {
            String text = "⚠ " + LogText.NO_DB_HINT;
            impHint.setBad(text);
            if (sqlPage != null) {
                sqlPage.setHint(false, text);
            }
            return;
        }
        String head = "当前目标库：" + db + "（" + hostField.getText().trim() + ":"
                + portField.getText().trim() + "）——";
        impHint.setOk(head + "Excel 数据将导入到这个库");
        if (sqlPage != null) {
            sqlPage.setHint(true, head + "SQL 脚本将执行到这个库");
        }
    }

    public String excelHintText() {
        return impHint.hintText();
    }

    public boolean excelHintIsOk() {
        return impHint.isOk();
    }

    /** 弹窗按严重级区分：连接失败这种必须用 error 级挡在前面。 */
    public void critical(String text) {
        javax.swing.JOptionPane.showMessageDialog(this, text, "连接失败",
                javax.swing.JOptionPane.ERROR_MESSAGE);
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
     * 导入收尾：汇总日志 -> 成功的文件移出列表、失败的留着便于改了再试 ->
     * 把要在弹窗里重复的那句话返回给调用方。对应 main.py:1579-1613。
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
            msg.append(NEWLINE + "已从待导入列表移除 ").append(removed.size())
                    .append(" 个成功文件。");
        }
        if (fail > 0) {
            msg.append(NEWLINE + "失败文件保留在列表中，可调整后重试。");
        }
        msg.append(NEWLINE + "详见日志。");
        return msg.toString();
    }

    private static String names(List<TableOps.Table> list) {
        List<String> out = new ArrayList<String>();
        for (TableOps.Table t : list) {
            out.add(baseName(t.filepath));
        }
        return String.join("、", out);
    }

    /** Java 源里的换行转义容易被工具链吃掉，弹窗文案统一走这个常量。 */
    private static final String NEWLINE = String.valueOf((char) 10);

    /** 导入在子线程跑，界面靠模态进度框报进度并可取消。 */
    public void startImport() {
        if (!connected || config == null) {
            log("还没连接，不能导入");
            return;
        }
        if (!currentConfig().hasDatabase()) {
            warn(LogText.NO_DB_HINT);
            return;
        }
        config = currentConfig();
        if (tables.isEmpty()) {
            log("没有可导入的表");
            return;
        }
        final List<TableBuilder.Result> work = new ArrayList<TableBuilder.Result>();
        for (TableOps.Table t : tables) {
            TableBuilder.Result r = byTable.get(t);
            if (r != null) {
                work.add(r);
            }
        }
        // 勾选状态在 EDT 上读一次：子线程里再点组件属于跨线程摸 Swing
        final boolean drop = dropExisting.isSelected();
        final Config cfg = config;
        final ProgressDialog progress = new ProgressDialog(this, "开始导入");
        final ImportParse.Sink sink = sinkFor(progress);
        setEnabledForRun(false);
        setPageProgress(0);
        Thread worker = new Thread(() -> {
            try {
                final List<ImportRun.Row> rows = ImportRun.run(cfg, work, drop, sink,
                        new ImportRun.RowProgress() {
                            @Override
                            public void onTableProgress(final int done, final int total) {
                                final int percent = total <= 0 ? 0 : (int) (done * 100L / total);
                                SwingUtilities.invokeLater(() -> setPageProgress(percent));
                            }
                        });
                final List<ImportOutcome> outcomes = new ArrayList<ImportOutcome>();
                for (ImportRun.Row r : rows) {
                    outcomes.add(new ImportOutcome(r.filepath, r.ok));
                }
                final boolean cancelled = progress.isCancelled();
                SwingUtilities.invokeLater(() -> {
                    setEnabledForRun(true);
                    progress.close();
                    // 弹窗与日志双写：日志可回溯，弹窗防走神漏看（Python 侧同一句话写两处）
                    inform(finishImport(outcomes, cancelled));
                });
            } catch (Throwable t) {
                crash(t, progress, () -> setEnabledForRun(true));
            }
        }, "excel2mysql-import");
        worker.setDaemon(true);
        worker.start();
        progress.open();
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
        addFilesAsync(paths);
    }

    /**
     * 添加文件的异步入口：展开压缩包与解析表结构都在子线程，
     * 界面只挂一个可取消的模态进度框。几十万行的表读起来不该让窗口变成白屏。
     */
    public void addFilesAsync(final List<String> paths) {
        final ProgressDialog progress = new ProgressDialog(this, "添加文件");
        final ImportParse.Sink sink = sinkFor(progress);
        setEnabledForRun(false);
        Thread worker = new Thread(() -> {
            try {
            final ImportParse.Outcome outcome =
                    ImportParse.parse(paths, currentPaths(), zipDirFor(paths), zipDestSeq,
                            builder, sink);
            SwingUtilities.invokeLater(() -> {
                if (outcome.cancelled) {
                    log("已取消，只完成了部分解析");
                }
                String summary = finishAdd(outcome);
                progress.close();
                setEnabledForRun(true);
                if (!summary.isEmpty()) {
                    inform(summary);
                }
            });
            } catch (Throwable t) {
                // 线程里的意外只会把模态框留在界面上，必须收掉并说出来
                crash(t, progress, () -> setEnabledForRun(true));
            }
        }, "excel2mysql-parse");
        worker.setDaemon(true);
        worker.start();
        progress.open();
    }

    /** 进度框 + 日志区的组合回报口；log/logError 本身就是线程安全的。 */
    private ImportParse.Sink sinkFor(final ProgressDialog progress) {
        return new ImportParse.Sink() {
            @Override
            public void setTotal(int total) {
                progress.setTotal(total);
            }

            @Override
            public void step(String label) {
                progress.step(label);
            }

            @Override
            public void advance(int units, String label) {
                progress.advance(units, label);
            }

            @Override
            public void log(String text) {
                MainFrame.this.log(text);
            }

            @Override
            public void logError(String text) {
                MainFrame.this.logError(text);
            }

            @Override
            public boolean cancelled() {
                return progress.isCancelled();
            }
        };
    }

    /** 给测试和命令行用的同步入口：同一个解析函数，只是不挂进度框。 */
    public String addFiles(List<String> paths) {
        ImportParse.Outcome outcome = ImportParse.parse(paths, currentPaths(),
                zipDirFor(paths), zipDestSeq, builder, sinkForLogOnly());
        return finishAdd(outcome);
    }

    private ImportParse.Sink sinkForLogOnly() {
        return new ImportParse.Sink() {
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
                MainFrame.this.log(text);
            }

            @Override
            public void logError(String text) {
                MainFrame.this.logError(text);
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    /** 压缩包来源映射：文件列表的 tooltip 要用"包名!包内路径"，光看文件名分不出来源。 */
    private final Map<String, String> zipOrigin = new java.util.LinkedHashMap<String, String>();

    /** 解析结果落到界面：子线程只算数据，列表、前缀、状态与弹窗都在这里改。 */
    private String finishAdd(ImportParse.Outcome outcome) {
        zipOrigin.putAll(outcome.zipOrigin);
        for (TableBuilder.Result r : outcome.tables) {
            tables.add(r.table);
            byTable.put(r.table, r);
            FileRowModel.Item item = new FileRowModel.Item(r.table, r);
            item.origin = zipOrigin.get(r.table.filepath);
            fileModel.add(item);
        }
        if (!tables.isEmpty()) {
            String prefix = Names.pyStrip(prefixField == null ? "" : prefixField.getText());
            if (!prefix.isEmpty()) {
                TableOps.applyPrefix(tables, prefix);
            } else {
                TableOps.makeUniqueNames(tables);
            }
        }
        zipDestSeq += 1;
        if (importBtn != null) {
            importBtn.setEnabled(connected);
        }
        status.setText("已加载 " + tables.size() + " 张表 / " + fileModel.rowTotal() + " 个字段");
        refreshPreview();
        String summary = ImportParse.summaryText(outcome);
        if (!summary.isEmpty()) {
            // 同一句话既进日志也进弹窗：日志可回溯，弹窗防走神漏看
            log("[添加结果] " + summary);
        }
        return summary;
    }

    private List<String> currentPaths() {
        List<String> out = new ArrayList<String>();
        for (TableOps.Table t : tables) {
            out.add(t.filepath);
        }
        return out;
    }

    /**
     * 拖拽只收 Excel 与 ZIP。过滤单独成方法、异步添加不掺进来，
     * 自检才能原地验"拖了个 txt 要怎么说"，而不用去点一个模态进度框。
     */
    public List<String> acceptedDrops(List<String> paths) {
        List<String> accepted = new ArrayList<String>();
        for (String p : paths) {
            String low = p.toLowerCase();
            if (low.endsWith(".xlsx") || low.endsWith(".xls") || low.endsWith(".zip")) {
                accepted.add(p);
            }
        }
        if (accepted.isEmpty()) {
            log("[提示] 拖入的不是 Excel 或 ZIP 文件，已忽略。");
        }
        return accepted;
    }

    /** 这一批里没有压缩包就不该碰临时目录，不然每次添加都留下一个空目录没人清。 */
    private String zipDirFor(List<String> paths) {
        for (String p : paths) {
            if (p.toLowerCase().endsWith(".zip")) {
                return zipSessionDir();
            }
        }
        return null;
    }

        /** 会话级解压目录：整个窗口生命周期只建一个，关窗口一次性清掉。 */
    private String zipSessionDir() {
        if (zipSessionDir == null) {
            File base = new File(System.getProperty("java.io.tmpdir"),
                    "excel2mysql-gui-" + System.nanoTime());
            if (!base.mkdirs()) {
                log("建解压目录失败：" + base.getPath());
                return base.getPath();
            }
            zipSessionDir = base.getPath();
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    cleanZipTemp();
                }
            });
        }
        return zipSessionDir;
    }

    /** 自检与日志要用：当前这一会话的解压目录，没解压过就是 null。 */
    public String zipTempDir() {
        return zipSessionDir;
    }

    /** 退出时清掉解压出来的临时文件；清不干净不拦退出，只在日志里说一声。 */
    public void cleanZipTemp() {
        if (zipSessionDir == null) {
            return;
        }
        File base = new File(zipSessionDir);
        if (!base.isDirectory()) {
            zipSessionDir = null;
            return;
        }
        int[] left = {0};
        deleteRecursively(base, left);
        if (left[0] > 0) {
            log("解压临时目录没能完全清掉，剩 " + left[0] + " 项：" + zipSessionDir);
        } else {
            log("已清理解压临时目录：" + zipSessionDir);
        }
        zipSessionDir = null;
    }

    private static void deleteRecursively(File f, int[] left) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k, left);
            }
        }
        if (!f.delete()) {
            left[0]++;
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
        zipOrigin.clear();
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

    /**
     * 子线程出意外：收掉模态框、恢复界面，日志与弹窗各写一遍同一句话。
     * 少这一层，线程里的异常只会被默认处理器吞掉，进度框永远挂在界面上。
     */
    private void crash(Throwable t, ProgressDialog progress, Runnable restore) {
        final String msg = LogText.describe(t);
        SwingUtilities.invokeLater(() -> {
            logError(LogText.crashLog(msg));
            if (progress != null) {
                progress.close();
            }
            if (restore != null) {
                restore.run();
            }
            inform(LogText.crashDialog(msg));
        });
    }

    /** 自检走同一份文案，只是不弹窗（EDT 上的模态框会把自检卡死）。 */
    public String crashMessage(Throwable t) {
        String msg = LogText.describe(t);
        logError(LogText.crashLog(msg));
        return LogText.crashDialog(msg);
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

    public javax.swing.JTextField hostField() {
        return hostField;
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
