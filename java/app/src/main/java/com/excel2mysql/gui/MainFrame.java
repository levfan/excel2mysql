package com.excel2mysql.gui;

import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.ZipImport;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    private final JTextArea log = new JTextArea();
    private final JLabel status = new JLabel("尚未添加文件");
    private final List<TableOps.Table> tables = new ArrayList<TableOps.Table>();
    private final Names names = new Names(PinyinLoader.get());
    private final TableBuilder builder = new TableBuilder(new Infer(names), names);

    public MainFrame() {
        super("excel2mysql");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1080, 700);
        setLocationByPlatform(true);
        setLayout(new BorderLayout());

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton add = new JButton("添加 Excel / 压缩包");
        add.addActionListener(e -> chooseFiles());
        JButton preview = new JButton("预览建表语句");
        preview.addActionListener(e -> refreshPreview());
        JButton clear = new JButton("清空");
        clear.addActionListener(e -> clearAll());
        bar.add(add);
        bar.add(preview);
        bar.add(clear);
        bar.add(Box.createHorizontalGlue());
        bar.add(status);
        add(bar, BorderLayout.NORTH);

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

        log.setEditable(false);
        log.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志"));

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ddlScroll, logScroll);
        right.setResizeWeight(0.62);
        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, right);
        center.setResizeWeight(0.52);
        add(center, BorderLayout.CENTER);
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
            model.addTable(r.table);
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
        model.clear();
        ddl.setText("");
        status.setText("尚未添加文件");
        log("已清空");
    }

    public void log(String text) {
        log.append(text);
        log.append("\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }
}
