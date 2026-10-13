package com.excel2mysql.gui;

import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
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
public final class MainFrame extends JFrame {

    private final TableDefModel model = new TableDefModel();
    private final javax.swing.JTable table = new javax.swing.JTable(model);
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
        add(buildCenter(), BorderLayout.CENTER);
    }

    /** 连接栏：口令只进 JPasswordField，全程不落文件、不进日志。 */
    private JPanel buildConnectionBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 4));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        JButton add = new JButton("添加 Excel / 压缩包");
        add.addActionListener(e -> chooseFiles());
        preview = new JButton("预览建表语句");
        preview.addActionListener(e -> refreshPreview());
        JButton clear = new JButton("清空");
        clear.addActionListener(e -> clearAll());
        connect = new JButton("连接并加载库");
        connect.addActionListener(e -> connectAndLoad());
        importBtn = new JButton("开始导入");
        importBtn.setEnabled(false);
        importBtn.addActionListener(e -> startImport());
        tools.add(add);
        tools.add(preview);
        tools.add(clear);
        tools.add(connect);
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
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
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
        return center;
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
        final ProgressDialog progress = new ProgressDialog(this, "开始导入");
        progress.setTotal(todo.size() * 2);
        setEnabledForRun(false);
        Thread worker = new Thread(() -> {
            int ok = 0;
            int failed = 0;
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
                progress.step("建表 " + t.tableName);
                try {
                    client.createTable(Ddl.buildDdl(t.tableName, t.tableComment, t.columns, false));
                } catch (Exception e) {
                    failed++;
                    final String message = e.getMessage();
                    SwingUtilities.invokeLater(() -> logError("建表失败 " + t.tableName + " — " + message));
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
                    ok++;
                    final long rows = written;
                    SwingUtilities.invokeLater(() -> log("导入完成 " + label + " 写入 " + rows + " 行"));
                } catch (Exception e) {
                    failed++;
                    final String message = e.getMessage();
                    SwingUtilities.invokeLater(() -> logError("写入失败 " + t.tableName + " — " + message));
                }
            }
            client.close();
            final int okFinal = ok;
            final int badFinal = failed;
            SwingUtilities.invokeLater(() -> {
                status.setText("导入完成 " + okFinal + " 张，失败 " + badFinal + " 张");
                log(status.getText() + (progress.isCancelled() ? "（已取消，未继续）" : ""));
                setEnabledForRun(true);
                progress.close();
            });
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
        status.setText("已加载 " + tables.size() + " 张表 / " + model.getRowCount() + " 个字段");
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
            model.addTable(r.table);
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
            sb.append(Ddl.buildDdl(t.tableName, t.tableComment, t.columns, false));
        }
        ddl.setText(sb.toString());
        ddl.setCaretPosition(0);
    }

    public List<TableOps.Table> tables() {
        return tables;
    }

    public TableDefModel model() {
        return model;
    }

    public String ddlText() {
        return ddl.getText();
    }

    public String statusText() {
        return status.getText();
    }

    private void clearAll() {
        tables.clear();
        byTable.clear();
        model.clear();
        ddl.setText("");
        status.setText("尚未添加文件");
        log("已清空");
    }

    public void log(String text) {
        logPanel.log(text);
    }

    /** 错误要整条标红：一屏日志里靠眼睛扫"失败"两个字太容易漏。 */
    public void logError(String text) {
        logPanel.error(text);
    }

    public LogPanel logPanel() {
        return logPanel;
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }
}
