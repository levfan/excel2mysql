package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.DmMysql;
import com.excel2mysql.core.DmReader;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.SqlExecute;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/** 第 5 页签：连达梦读表结构，生成 MySQL 建表语句（含表与字段注释）。 */
public final class DmPage extends JPanel {

    public interface Host {
        Config currentConfig();

        boolean connected();

        void warn(String text);

        void inform(String text);

        void informError(String title, String text);

        void runDmTask(String title, Function<ImportParse.Sink, Object> work, Consumer<Object> onUi);
    }

    private final Host host;
    private final LogPanel logPanel = new LogPanel();
    private final JTextField hostField = new JTextField("127.0.0.1", 12);
    private final JTextField portField = new JTextField(String.valueOf(DmReader.DEFAULT_PORT), 5);
    private final JTextField userField = new JTextField("SYSDBA", 8);
    private final javax.swing.JPasswordField passField = new javax.swing.JPasswordField(8);
    private final JTextField schemaField = new JTextField("", 8);
    private final JTextField paramField = new JTextField("", 10);
    private final JTextField targetDbField = new JTextField("", 10);
    private final JButton connectBtn = new JButton("连达梦并列表");
    private final JButton genBtn = new JButton("生成 MySQL DDL");
    private final JButton saveBtn = new JButton("保存到 .sql");
    private final JButton writeBtn = new JButton("写入 MySQL 目标库");
    private final JCheckBox dropExisting = new JCheckBox("先 DROP 已存在表", false);
    private final JCheckBox lowerNames = new JCheckBox("表名与字段名转小写", true);
    private final JLabel listLabel = new JLabel("未连接达梦");
    private final JTextArea output = new JTextArea(12, 60);

    private final javax.swing.table.DefaultTableModel model =
            new javax.swing.table.DefaultTableModel(
                    new Object[]{"表名", "表注释", "字段数", "主键"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
    private final javax.swing.JTable table = new javax.swing.JTable(model);

    /** 连上之后读回的结构；生成只用这一份，不在生成时再连一次库。 */
    private final List<DmMysql.Table> tables = new ArrayList<DmMysql.Table>();
    private DmMysql.Output lastOut;

    public DmPage(Host host) {
        this.host = host;
        Buttons.secondary(genBtn);
        Buttons.secondary(saveBtn);
        Buttons.secondary(writeBtn);
        setLayout(new BorderLayout(0, UiScale.px(6)));
        add(buildTop(), BorderLayout.NORTH);
        Tables.style(table);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        connectBtn.addActionListener(e -> connectAndList());
        genBtn.addActionListener(e -> generate());
        saveBtn.addActionListener(e -> chooseSavePath());
        writeBtn.addActionListener(e -> writeToMysql());
        output.setEditable(false);
        output.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN,
                UiScale.fontPt(UiScale.UI_POINT_SIZE)));
        refreshButtonStates();
    }

    private JPanel buildTop() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(PageIntro.of("连达梦读表结构，生成 MySQL 建表语句"), BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        row.add(new JLabel("达梦主机"));
        row.add(hostField);
        row.add(new JLabel("端口"));
        row.add(portField);
        row.add(new JLabel("用户名"));
        row.add(userField);
        row.add(new JLabel("口令"));
        row.add(passField);
        schemaField.setToolTipText("达梦的模式名（OWNER），通常就是用户名，例如 SYSDBA");
        row.add(new JLabel("模式"));
        row.add(schemaField);
        paramField.setToolTipText("<html>追加到达梦连接串的驱动参数，可留空。<br>"
                + "例如 socketTimeout=8000（参数名以所用驱动为准）。</html>");
        row.add(new JLabel("驱动参数"));
        row.add(paramField);
        row.add(connectBtn);
        p.add(row, BorderLayout.CENTER);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        row2.add(new JLabel("MySQL 目标库"));
        targetDbField.setToolTipText("生成的脚本开头写 USE 这一本库；留空则不写 USE，"
                + "「写入 MySQL 目标库」按顶部连接栏所选的库执行");
        row2.add(targetDbField);
        row2.add(dropExisting);
        row2.add(lowerNames);
        row2.add(genBtn);
        row2.add(saveBtn);
        row2.add(writeBtn);
        p.add(row2, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildBottom() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        head.add(listLabel);
        p.add(head, BorderLayout.NORTH);
        p.add(new JScrollPane(output), BorderLayout.CENTER);
        p.add(logPanel, BorderLayout.SOUTH);
        return p;
    }

    private void refreshButtonStates() {
        genBtn.setEnabled(!tables.isEmpty());
        saveBtn.setEnabled(lastOut != null && lastOut.tables > 0);
        writeBtn.setEnabled(lastOut != null && lastOut.tables > 0);
    }

    /** 自检与对撞读这三样：清单行数、输出正文、按钮可用性。 */
    public int tableRowCount() {
        return model.getRowCount();
    }

    public String outputText() {
        return output.getText();
    }

    public String listLabelText() {
        return listLabel.getText();
    }

    public boolean generateEnabled() {
        return genBtn.isEnabled();
    }

    public boolean saveEnabled() {
        return saveBtn.isEnabled();
    }

    public LogPanel logPanel() {
        return logPanel;
    }

    /** 自检用：把模式名填进去，走的是用户敲同一格的那条路。 */
    public void schemaFieldForSelftest(String value) {
        schemaField.setText(value == null ? "" : value);
    }

    /** 界面上的达梦参数拼成一份连接配置；端口写错时退回默认端口并记一句。 */
    public DmReader.Conf readConf() {
        DmReader.Conf c = new DmReader.Conf();
        c.host = hostField.getText().trim();
        c.user = userField.getText().trim();
        c.password = new String(passField.getPassword());
        c.schema = schemaField.getText().trim();
        c.params = paramField.getText().trim();
        try {
            c.port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            c.port = DmReader.DEFAULT_PORT;
        }
        return c;
    }

    public void connectAndList() {
        final DmReader.Conf cfg = readConf();
        if (cfg.schema.isEmpty()) {
            logPanel.error("x [ERROR] DM | 模式名为空：达梦的表按模式（OWNER）归口，"
                    + "先在「模式」那格填一个模式名，通常就是登录用户名。");
            return;
        }
        if (!DmReader.driverPresent()) {
            logPanel.error("x [ERROR] DM | " + DmReader.missingDriverHint());
            return;
        }
        host.runDmTask("正在连接达梦并读取表结构…", new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                sink.step("连接 " + cfg.host + ":" + cfg.port + " 模式 " + cfg.schema);
                DmReader r = null;
                try {
                    r = DmReader.open(cfg);
                    final String version = r.serverVersion();
                    List<String> names = r.listTables();
                    sink.setTotal(names.size());
                    List<DmMysql.Table> read = new ArrayList<DmMysql.Table>();
                    int done = 0;
                    for (String n : names) {
                        if (sink.cancelled()) {
                            sink.log("! [WARN] DM | 已取消：读到 " + done + "/" + names.size()
                                    + " 张，界面上仍是上一份结构");
                            return null;
                        }
                        sink.advance(1, "读 " + n);
                        read.add(r.readTable(n));
                        done++;
                    }
                    return new Object[]{read, null, version};
                } catch (Exception ex) {
                    return new Object[]{null, LogText.describe(ex), ""};
                } finally {
                    if (r != null) {
                        r.close();
                    }
                }
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                Object[] arr = (Object[]) res;
                if (arr[1] != null) {
                    logPanel.error("x [ERROR] DM | 连接或读结构失败：无法连接到 "
                            + cfg.host + ":" + cfg.port + "（模式 " + cfg.schema + "）"
                            + LogText.NL + LogText.indentDetail(String.valueOf(arr[1])));
                    host.informError("连不上达梦", "无法连接到 " + cfg.host + ":" + cfg.port
                            + "（用户 " + cfg.user + "，模式 " + cfg.schema + "）：\n" + arr[1]);
                    return;
                }
                applyTables((List<DmMysql.Table>) arr[0], String.valueOf(arr[2]));
            }
        });
    }

    private void applyTables(List<DmMysql.Table> read, String version) {
        tables.clear();
        model.setRowCount(0);
        for (DmMysql.Table t : read) {
            tables.add(t);
            model.addRow(new Object[]{t.name, t.comment, Integer.valueOf(t.columns.size()),
                    joinNames(t.primaryKeys)});
        }
        String tail = version == null || version.isEmpty() ? "" : " | " + version;
        listLabel.setText("已读回 " + tables.size() + " 张表（模式 " + schemaField.getText().trim()
                + "）" + tail);
        logPanel.log("+ [OK] DM | 读回 " + tables.size() + " 张表结构" + tail
                + "，选中行点「生成 MySQL DDL」");
        if (targetDbField.getText().trim().isEmpty()) {
            Config mysql = host.currentConfig();
            if (mysql != null && mysql.database != null) {
                targetDbField.setText(mysql.database.trim());
            }
        }
        lastOut = null;
        output.setText("");
        refreshButtonStates();
    }

    private static String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(n);
        }
        return sb.toString();
    }

    /** 选中行优先，一行都没选就整库生成。 */
    public DmMysql.Output generate() {
        if (tables.isEmpty()) {
            host.warn("还没读回表结构：先点「连达梦并列表」。");
            return null;
        }
        List<DmMysql.Table> picked = selectedTables();
        if (picked.isEmpty()) {
            host.warn("清单里选中的行一张表都没有，没有生成任何语句。");
            return null;
        }
        DmMysql.Output out = DmMysql.script(picked, targetDbField.getText().trim(),
                dropExisting.isSelected(), lowerNames.isSelected());
        lastOut = out;
        output.setText(out.sql);
        output.setCaretPosition(0);
        logPanel.log("+ [OK] DM-DDL | 生成 " + out.tables + " 张表的建表语句 | 译名跳过 "
                + out.skipped.size() + " 张 | 提醒 " + out.warnings.size() + " 条");
        for (String w : out.warnings) {
            logPanel.log("! [WARN] DM-DDL | " + w);
        }
        for (String s : out.skipped) {
            logPanel.log("! [WARN] DM-DDL | 整张表没进产物：" + s);
        }
        refreshButtonStates();
        return out;
    }

    private List<DmMysql.Table> selectedTables() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return new ArrayList<DmMysql.Table>(tables);
        }
        List<DmMysql.Table> out = new ArrayList<DmMysql.Table>();
        for (int row : rows) {
            if (row >= 0 && row < tables.size()) {
                out.add(tables.get(row));
            }
        }
        return out;
    }

    /** 自检走这条：显式路径，不弹保存框。 */
    public boolean saveTo(File dst) {
        DmMysql.Output out = lastOut != null ? lastOut : generate();
        if (out == null || out.sql.trim().isEmpty()) {
            logPanel.error("x [ERROR] DM-SAVE | 没有可保存的内容：先生成一次 MySQL DDL");
            return false;
        }
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            Writer w = new OutputStreamWriter(Files.newOutputStream(dst.toPath()),
                    StandardCharsets.UTF_8);
            try {
                w.write(out.sql);
            } finally {
                w.close();
            }
        } catch (Exception e) {
            logPanel.error("x [ERROR] DM-SAVE | 写文件失败：" + dst.getPath() + " — "
                    + LogText.describe(e));
            return false;
        }
        logPanel.log("+ [OK] DM-SAVE | 已写出 " + dst.getPath() + "（UTF-8，"
                + out.sql.split("\n", -1).length + " 行）");
        return true;
    }

    private void chooseSavePath() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setSelectedFile(new File("dm_to_mysql.sql"));
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            logPanel.log("- [INFO] DM-SAVE | 取消保存，没动任何文件");
            return;
        }
        saveTo(fc.getSelectedFile());
    }

    /**
     * 直接把生成的脚本发到 MySQL：走的是第三个页签同一个 SqlExecute，
     * 事务与逐条报错口径因此完全一致，不在这一页另发明一套执行语义。
     */
    private void writeToMysql() {
        if (lastOut == null || lastOut.sql.trim().isEmpty()) {
            host.warn("还没有可执行的脚本：先点「生成 MySQL DDL」。");
            return;
        }
        final Config cfg = host.currentConfig();
        if (cfg == null || !cfg.hasDatabase()) {
            host.warn(LogText.NO_DB_HINT);
            return;
        }
        String want = targetDbField.getText().trim();
        if (!want.isEmpty() && cfg.database != null && !want.equalsIgnoreCase(cfg.database.trim())) {
            logPanel.log("! [WARN] DM-EXEC | 脚本开头写的是 USE `" + want + "`，与顶部所选库 "
                    + cfg.database + " 不是同一本，执行时会切过去");
        }
        final String script = lastOut.sql;
        host.runDmTask("正在把达梦结构写入 MySQL…", new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                DbClient db = null;
                try {
                    db = new DbClient(cfg);
                    final int[] reported = {0};
                    return SqlExecute.run(db, script, sink::log,
                            (done, total) -> {
                                sink.setTotal(total);
                                sink.advance(Math.max(done - reported[0], 0),
                                        "第 " + done + "/" + total + " 条");
                                reported[0] = done;
                            }, () -> sink.cancelled(), null);
                } catch (Exception ex) {
                    sink.logError("x [ERROR] DM-EXEC | 连接或执行失败：无法连接到 "
                            + cfg.host + ":" + cfg.port + "（库 " + cfg.database + "）"
                            + LogText.NL + LogText.indentDetail(LogText.describe(ex)));
                    return null;
                } finally {
                    if (db != null) {
                        db.close();
                    }
                }
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                SqlExecute.Report r = (SqlExecute.Report) res;
                host.inform("写入 MySQL 结束：共 " + r.statements + " 条语句，执行到第 "
                        + r.executed + " 条" + (r.committed ? "，已提交" : "，未提交")
                        + (r.cancelled ? "，中途取消" : "")
                        + (r.error == null || r.error.isEmpty() ? "" : "，首条错误：" + r.error));
            }
        });
    }

    /** 自检用：直接灌一份结构进来走界面那一条路，不经过达梦连接。 */
    public void setTablesForSelftest(List<DmMysql.Table> read) {
        applyTables(read, "");
    }
}
