package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.Prefix;
import com.excel2mysql.core.SqlRunner;
import com.excel2mysql.core.SqlScript;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL 脚本页签，对应 main.py:1115-1324。
 *
 * 脚本的改写只动内存里的文本，落盘要另外点「保存修改后脚本」——
 * 和 Python 侧一样：改错了不该把原文件覆盖掉。
 */
public final class SqlPage extends JPanel {

    /** 连接信息由主窗口持有，SQL 页不自留一份口令。 */
    public interface Host {
        Config config();

        boolean connected();

        java.awt.Frame owner();

        void inform(String text);

        void inform2(String title, String text);

        void informError(String title, String text);

        /** 耗时操作交给主窗口的任务位跑：子线程算 + 进度框 + 回事件分发线程落界面。 */
        void runSqlTask(String title,
                        java.util.function.Function<com.excel2mysql.core.ImportParse.Sink, Object> work,
                        java.util.function.Consumer<Object> onUi);

        void warn(String text);
    }

    /** 一个脚本文件：原文、当前（可能被改过的）文本、以及改名映射。 */
    public static final class Entry {
        public final String path;
        public String text;
        public boolean modified;
        public List<Map<String, Object>> tabs;
        /** 最初的表名 → 现在脚本里写的表名，多轮改写累计；"原表名"列靠它回显。 */
        public final Map<String, String> renameMap = new LinkedHashMap<String, String>();

        Entry(String path, String text, List<Map<String, Object>> tabs) {
            this.path = path;
            this.text = text;
            this.tabs = tabs;
        }
    }

    private static final String[] COLUMNS = {"文件", "原表名", "CREATE 出现次数", "当前表名"};

    /**
     * 交给子线程的输入快照：改写期间主线程可能继续动列表，子线程只读这份拷贝。
     * 保存这条路径只用 path/text，renameMap 留空。
     */
    private static final class Src {
        final String path;
        final String text;
        final Map<String, String> renameMap;

        Src(String path, String text, Map<String, String> renameMap) {
            this.path = path;
            this.text = text;
            this.renameMap = renameMap == null
                    ? new LinkedHashMap<String, String>() : renameMap;
        }
    }

    /** 子线程算好的改写结果，按脚本路径索引；写回 Entries 一律回事件分发线程做。 */
    private static final class Update {
        String text;
        List<Map<String, Object>> tabs;
        final Map<String, String> renameMap = new LinkedHashMap<String, String>();
    }
    private static final String PREFIX_HINT =
            "如 dim_（加前缀，已带该前缀的表自动跳过）；填 change 再点「替换首个前缀」"
            + "则把 ods_t_order 换成 change_t_order";
    private static final String REPLACE_TIP =
            "把脚本里每个表名第一个下划线之前的部分换成上面填的内容，"
            + "并同步改写 CREATE/DROP/INSERT/ALTER/TRUNCATE 中的引用；"
            + "表名里没有下划线的不动。末尾下划线不用写";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Host host;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<String> rowPath = new ArrayList<String>();
    private final DefaultTableModel model;
    private final JTable table;
    private final LogPanel logPanel = new LogPanel();
    private final HintTextField prefixField;
    private final JButton runButton;
    private final HintLabel hint = new HintLabel();

    public SqlPage(Host host) {
        this.host = host;
        setLayout(new BorderLayout());

        model = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        table = new JTable(model);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        // 第一列挂完整路径：脚本同名时只靠文件名分不出是哪个目录里的那份（同 Python 的 tooltip）
        table.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable t, Object v,
                    boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, v, selected, focus, row, column);
                setToolTipText(row < rowPath.size() ? rowPath.get(row) : "");
                return this;
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setMinimumSize(new Dimension(300, 120));

        JButton add = new JButton("添加 SQL 文件");
        add.addActionListener(e -> chooseFiles());
        JButton remove = new JButton("移除选中文件");
        remove.addActionListener(e -> {
            if (removeSelected() == 0) {
                log("没选中文件，未移除任何脚本");
            }
        });
        prefixField = new HintTextField(18, PREFIX_HINT);
        JButton apply = new JButton("批量应用前缀");
        apply.addActionListener(e -> {
            String hint = applyPrefix();
            if (hint != null) {
                host.warn(hint);
            }
        });
        JButton replace = new JButton("替换首个前缀");
        replace.setToolTipText(REPLACE_TIP);
        replace.addActionListener(e -> {
            String hint = replaceFirstPrefix();
            if (hint != null) {
                host.warn(hint);
            }
        });
        JButton save = new JButton("保存修改后脚本");
        save.addActionListener(e -> saveModified());
        runButton = new JButton("执行脚本到当前库");
        runButton.addActionListener(e -> {
            String hint = execute();
            if (hint != null) {
                host.warn(hint);
            }
        });
        JButton clearLog = new JButton("清除日志");
        clearLog.addActionListener(e -> logPanel.clearLog());

        JPanel prefixRow = flow();
        prefixRow.add(new JLabel("表名前缀："));
        prefixRow.add(prefixField);
        prefixRow.add(apply);
        prefixRow.add(replace);

        JPanel actRow = flow();
        actRow.add(save);
        actRow.add(runButton);
        actRow.add(new BoxFill());
        actRow.add(clearLog);

        JPanel actions = new JPanel(new BorderLayout());
        actions.add(prefixRow, BorderLayout.NORTH);
        actions.add(actRow, BorderLayout.SOUTH);

        JPanel upper = new JPanel(new BorderLayout(4, 4));
        upper.add(scroll, BorderLayout.CENTER);
        upper.add(actions, BorderLayout.SOUTH);

        JPanel topRow = flow();
        topRow.add(add);
        topRow.add(remove);

        JScrollPane logScroll = new JScrollPane(logPanel);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志"));
        logScroll.setMinimumSize(new Dimension(300, 100));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, logScroll);
        split.setResizeWeight(0.45);

        JPanel north = new JPanel(new BorderLayout());
        north.add(hint, BorderLayout.NORTH);
        north.add(topRow, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private static JPanel flow() {
        return new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
    }

    /** 一行里把剩余空间顶开，等价于 Qt 的 addStretch(1)。 */
    private static final class BoxFill extends javax.swing.Box.Filler {
        BoxFill() {
            super(new Dimension(0, 0), new Dimension(20, 0), new Dimension(32767, 0));
        }
    }

    // ---------------------------------------------------------------- 数据

    public List<String> paths() {
        List<String> out = new ArrayList<String>();
        for (Entry e : entries) {
            out.add(e.path);
        }
        return out;
    }

    public int entryCount() {
        return entries.size();
    }

    public int rowCount() {
        return model.getRowCount();
    }

    public String textOf(String path) {
        for (Entry e : entries) {
            if (e.path.equals(path)) {
                return e.text;
            }
        }
        return null;
    }

    public boolean isModified(String path) {
        for (Entry e : entries) {
            if (e.path.equals(path)) {
                return e.modified;
            }
        }
        return false;
    }

    /** 目标库提示条由主窗口同源刷新，两页说的是同一个库。 */
    public void setHint(boolean ok, String message) {
        if (ok) {
            hint.setOk(message);
        } else {
            hint.setBad(message);
        }
    }

    public String hintText() {
        return hint.hintText();
    }

    public boolean hintIsOk() {
        return hint.isOk();
    }

    public LogPanel logPanel() {
        return logPanel;
    }

    public HintTextField prefixField() {
        return prefixField;
    }

    public String prefixText() {
        return prefixField.getText();
    }

    public void setPrefixText(String text) {
        prefixField.setText(text);
    }

    public String cellText(int row, int col) {
        Object v = model.getValueAt(row, col);
        return v == null ? "" : String.valueOf(v);
    }

    public int selectedRowCount() {
        return table.getSelectedRowCount();
    }

    public void selectRow(int row) {
        table.setRowSelectionInterval(row, row);
    }

    // ---------------------------------------------------------------- 动作

    private void chooseFiles() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("选择 SQL 脚本");
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "SQL 脚本 (*.sql)", "sql"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            log("未选择文件");
            return;
        }
        List<String> paths = new ArrayList<String>();
        for (File f : chooser.getSelectedFiles()) {
            paths.add(f.getPath());
        }
        addFiles(paths);
    }

    /**
     * 读盘与提取表名都在子线程跑（几十万行的脚本不能压在事件分发线程上），
     * 只有"并入列表 + 刷新表格"回到事件分发线程；日志全程留在 SQL 页。
     */
    @SuppressWarnings("unchecked")
    public void addFiles(List<String> paths) {
        Set<String> existing = new LinkedHashSet<String>(paths());
        List<String> todo = new ArrayList<String>();
        for (String p : paths) {
            if (existing.contains(p)) {
                log("[跳过] 已在列表：" + baseName(p));
                continue;
            }
            todo.add(p);
        }
        if (todo.isEmpty()) {
            refreshTable();
            return;
        }
        final List<String> files = todo;
        host.runSqlTask("正在读取 SQL 脚本…", sink -> {
            sink.setTotal(files.size());
            List<Entry> loaded = new ArrayList<Entry>();
            for (String p : files) {
                String fname = baseName(p);
                if (sink.cancelled()) {
                    break;
                }
                String text;
                try {
                    text = SqlRunner.readScript(new File(p));
                } catch (Exception ex) {
                    sink.logError("[读取失败] " + fname + " 未能读入，已跳过" + LogText.NL
                            + LogText.indentDetail(LogText.describe(ex)));
                    sink.step(fname);
                    continue;
                }
                List<Map<String, Object>> tabs = SqlScript.extractCreateTables(text);
                List<String> names = new ArrayList<String>();
                for (Map<String, Object> t : tabs) {
                    names.add(String.valueOf(t.get("name")));
                }
                sink.log("[已加载] " + fname + "：" + tabs.size() + " 个表（"
                        + (names.isEmpty() ? "（未发现 CREATE TABLE 语句）" : String.join("、", names))
                        + "）");
                loaded.add(new Entry(p, text, tabs));
                sink.step(fname);
            }
            return loaded;
        }, out -> {
            for (Entry e : (List<Entry>) out) {
                entries.add(e);
            }
            refreshTable();
        });
    }

    /** 选中行可能同属一个文件的多个表名，按文件去重后整文件移除。 */
    public int removeSelected() {
        Set<String> drop = new LinkedHashSet<String>();
        for (int row : table.getSelectedRows()) {
            if (row >= 0 && row < rowPath.size()) {
                drop.add(rowPath.get(row));
            }
        }
        if (drop.isEmpty()) {
            return 0;
        }
        List<Entry> keep = new ArrayList<Entry>();
        for (Entry e : entries) {
            if (!drop.contains(e.path)) {
                keep.add(e);
            }
        }
        entries.clear();
        entries.addAll(keep);
        refreshTable();
        log("[移除] " + drop.size() + " 个脚本文件");
        return drop.size();
    }

    /**
     * 批量加前缀：已带该前缀与脚本里找不到引用的都进跳过清单，重复点不会叠前缀。
     * 返回非 null 表示这次点下去只该弹提示。改写跑在子线程，表名清单也在工作线程重算。
     */
    public String applyPrefix() {
        String prefix = Names.pyStrip(prefixField.getText());
        if (prefix.isEmpty()) {
            return "请先输入表名前缀（如 dim_）。";
        }
        final List<Src> items = snapshot();
        final String p = prefix;
        host.runSqlTask("正在改写脚本中的表名…", sink -> {
            sink.setTotal(items.size());
            Map<String, Update> updates = new LinkedHashMap<String, Update>();
            int total = 0;
            for (Src s : items) {
                String fname = baseName(s.path);
                if (sink.cancelled()) {
                    break;
                }
                SqlScript.Rewrite rw = SqlScript.applyPrefix(s.text, p);
                if (!rw.renamed.isEmpty()) {
                    Update u = new Update();
                    u.text = rw.text;
                    u.tabs = SqlScript.extractCreateTables(rw.text);
                    u.renameMap.putAll(reverseRename(s.renameMap, rw));
                    updates.put(s.path, u);
                    sink.log("[前缀] " + fname + "：" + describeRenamed(rw.renamed));
                    total += rw.renamed.size();
                }
                if (!rw.skipped.isEmpty()) {
                    sink.log("[跳过] " + fname + "：已带前缀 " + String.join("、", rw.skipped));
                }
                sink.step(fname);
            }
            sink.log("[前缀完成] 共改写 " + total
                    + " 个表名（修改在内存中，可点「保存修改后脚本」落盘）");
            return updates;
        }, out -> applyUpdates(out));
        return null;
    }

    /** 换首个前缀：清洗与合法性校验走 core 的同一套规则，改写同样在子线程。 */
    public String replaceFirstPrefix() {
        String head = Prefix.clean(prefixField.getText());
        if (head.isEmpty()) {
            return "请先输入新的前缀（如 change）。";
        }
        if (!Prefix.isValid(head)) {
            return "前缀「" + head + "」含非法字符：只能用字母、数字、下划线；"
                    + "末尾的下划线不用写，原表名自带。";
        }
        final List<Src> items = snapshot();
        final String h = head;
        host.runSqlTask("正在替换脚本中的表名前缀…", sink -> {
            sink.setTotal(items.size());
            Map<String, Update> updates = new LinkedHashMap<String, Update>();
            int total = 0;
            for (Src s : items) {
                String fname = baseName(s.path);
                if (sink.cancelled()) {
                    break;
                }
                SqlScript.Rewrite rw = SqlScript.replaceFirstSegment(s.text, h);
                if (!rw.renamed.isEmpty()) {
                    Update u = new Update();
                    u.text = rw.text;
                    u.tabs = SqlScript.extractCreateTables(rw.text);
                    u.renameMap.putAll(reverseRename(s.renameMap, rw));
                    updates.put(s.path, u);
                    sink.log("[换前缀] " + fname + "：" + describeRenamed(rw.renamed));
                    total += rw.renamed.size();
                }
                if (!rw.skipped.isEmpty()) {
                    sink.log("[跳过] " + fname + "：未改写 " + String.join("、", rw.skipped)
                            + "（表名里没有下划线，或脚本中找不到引用）");
                }
                sink.step(fname);
            }
            sink.log("[换前缀完成] 共改写 " + total
                    + " 个表名（修改在内存中，可点「保存修改后脚本」落盘）");
            return updates;
        }, out -> applyUpdates(out));
        return null;
    }

    /** 快照：子线程只读这份拷贝，不跟主线程的列表共用同一个 Entry。 */
    private List<Src> snapshot() {
        List<Src> out = new ArrayList<Src>();
        for (Entry e : entries) {
            out.add(new Src(e.path, e.text, new LinkedHashMap<String, String>(e.renameMap)));
        }
        return out;
    }

    /** 按路径把改写结果写回列表：这一步在事件分发线程上，改完才刷新表格。 */
    @SuppressWarnings("unchecked")
    private void applyUpdates(Object out) {
        Map<String, Update> updates = (Map<String, Update>) out;
        for (Entry e : entries) {
            Update u = updates.get(e.path);
            if (u == null) {
                continue;
            }
            e.text = u.text;
            e.tabs = u.tabs;
            e.modified = true;
            e.renameMap.putAll(u.renameMap);
        }
        refreshTable();
    }

    /** 落盘文件名固定为 原 stem + "_prefix" + 原扩展名，不覆盖原脚本。 */
    public static String modifiedPath(String path) {
        String slashFree = path.replace('\\', '/');
        int sep = slashFree.lastIndexOf('/');
        int dot = slashFree.lastIndexOf('.');
        if (dot <= sep + 1) {
            return path + "_prefix.sql";
        }
        return slashFree.substring(0, dot) + "_prefix" + slashFree.substring(dot);
    }

    /** 几十万行的脚本写盘也放子线程；哪些算"已落盘"要回到事件分发线程才清 modified 标记。 */
    @SuppressWarnings("unchecked")
    public void saveModified() {
        List<Src> items = new ArrayList<Src>();
        for (Entry e : entries) {
            if (e.modified) {
                items.add(new Src(e.path, e.text, null));
            }
        }
        if (items.isEmpty()) {
            log("[保存] 没有修改过的脚本文件");
            return;
        }
        final List<Src> todo = items;
        host.runSqlTask("正在保存修改后的脚本…", sink -> {
            sink.setTotal(todo.size());
            List<String> saved = new ArrayList<String>();
            for (Src s : todo) {
                String fname = baseName(s.path);
                if (sink.cancelled()) {
                    break;
                }
                String out = modifiedPath(s.path);
                try {
                    Files.write(Paths.get(out), s.text.getBytes(UTF8));
                } catch (Exception ex) {
                    sink.logError("[保存失败] " + fname + "：" + LogText.describe(ex));
                    sink.step(fname);
                    continue;
                }
                saved.add(s.path);
                sink.log("[已保存] " + out);
                sink.step(fname);
            }
            return saved;
        }, out -> {
            Set<String> done = new LinkedHashSet<String>((List<String>) out);
            for (Entry e : entries) {
                if (done.contains(e.path)) {
                    e.modified = false;
                }
            }
        });
    }

    /**
     * 每个文件一个事务地执行到当前库。返回非 null 表示只该弹提示、没有连库。
     * 执行跑在子线程，界面靠模态进度框报进度并可取消。
     */
    public String execute() {
        Config cfg = host.config();
        if (cfg == null || !cfg.hasDatabase()) {
            return LogText.NO_DB_HINT;
        }
        if (entries.isEmpty()) {
            return LogText.NO_SQL_HINT;
        }
        final String target = cfg.database + "@" + cfg.host + ":" + cfg.port;
        log("===== 开始执行脚本（目标库 " + cfg.database + "，每个文件一个事务）=====");
        log("正在切分 " + entries.size() + " 个脚本的语句…");
        final List<Entry> todo = new ArrayList<Entry>(entries);
        final List<List<String>> plans = new ArrayList<List<String>>();
        for (Entry e : todo) {
            plans.add(SqlScript.splitStatements(e.text));
        }
        final Config conf = cfg;
        final ProgressDialog progress = new ProgressDialog(host.owner(), "正在执行脚本到目标库…");
        // 计项按语句：一个脚本几万条时也照样推进，但每个文件最多刷约 100 次，
        // 不然进度框会被刷屏到看不出动静
        int totalStatements = 0;
        for (List<String> stmts : plans) {
            totalStatements += stmts.size();
        }
        progress.setTotal(Math.max(1, totalStatements));
        runButton.setEnabled(false);
        Thread worker = new Thread(() -> {
            try {
            int ok = 0;
            int fail = 0;
            int skipped = 0;
            DbClient client;
            try {
                client = new DbClient(conf);
            } catch (Exception ex) {
                final String detail = LogText.describe(ex);
                SwingUtilities.invokeLater(() -> error("[连接失败] 无法连接到 " + target
                        + "（用户 " + conf.user + "），剩余脚本均未执行" + LogText.NL
                        + LogText.indentDetail(detail)));
                SwingUtilities.invokeLater(() -> {
                    runButton.setEnabled(true);
                    progress.close();
                });
                return;
            }
            for (int i = 0; i < todo.size(); i++) {
                Entry e = todo.get(i);
                List<String> stmts = plans.get(i);
                String fname = baseName(e.path);
                if (progress.isCancelled()) {
                    break;
                }
                if (stmts.isEmpty()) {
                    skipped++;
                    log("[跳过] " + fname + "：没有可执行的语句");
                    progress.step(fname);
                    continue;
                }
                final int size = stmts.size();
                final int chunk = Math.max(1, size / 100);
                final int[] reported = {0};
                SqlRunner.FileResult r = SqlRunner.runText(client, fname, e.text,
                        new SqlRunner.StatementHook() {
                            @Override
                            public boolean cancelled() {
                                return progress.isCancelled();
                            }

                            @Override
                            public void onStatement(int done, int total) {
                                if (done - reported[0] >= chunk) {
                                    progress.advance(chunk, fname + " 第 " + done + "/" + total + " 条");
                                    reported[0] = done;
                                }
                            }
                        });
                // 把余数补齐，进度条总数才落得准
                progress.advance(Math.max(0, size - reported[0]), fname + " 收尾");
                if (r.cancelled) {
                    // 取消的文件既不算成功也不算失败，但它确实被处理过了
                    ok++;
                    log("[已取消] " + fname + "：已执行 " + r.executed + "/" + size + " 条，"
                            + "本文件事务已回滚" + LogText.NL + "    " + LogText.ROLLBACK_NOTE);
                } else if (r.ok) {
                    ok++;
                    log("[提交] " + fname + "：" + r.executed + " 条语句已提交");
                } else {
                    fail++;
                    error("[执行失败] " + fname + " 第 " + r.failedAt + "/" + r.statements.size()
                            + " 条语句报错，目标库 " + target + "，本文件事务已回滚" + LogText.NL
                            + LogText.indentDetail(r.error) + LogText.NL + "    出错语句："
                            + LogText.NL + LogText.formatStatement(r.failedSql) + LogText.NL
                            + "    前面 " + r.executed + " 条语句已随事务回滚。"
                            + LogText.ROLLBACK_NOTE);
                }
            }
            client.close();
            final boolean cancelled = progress.isCancelled();
            final int okFinal = ok;
            final int failFinal = fail;
            final int skipFinal = skipped;
            SwingUtilities.invokeLater(() -> {
                runButton.setEnabled(true);
                progress.close();
                host.inform2("执行完成", finishRun(okFinal, failFinal, skipFinal, cancelled, target));
            });
            } catch (Throwable t) {
                String msg = LogText.describe(t);
                SwingUtilities.invokeLater(() -> {
                    error(LogText.crashLog(msg));
                    runButton.setEnabled(true);
                    progress.close();
                    host.informError("任务异常", LogText.crashDialog(msg));
                });
            }
        }, "excel2mysql-sqlrun");
        worker.setDaemon(true);
        worker.start();
        progress.open();
        return null;
    }

    /** 收尾：先写日志，把要在弹窗里重复的那句话返回给调用方。 */
    public String finishRun(int ok, int fail, int skipped, boolean cancelled, String target) {
        String note = cancelled ? "（已取消，剩余脚本未执行）" : "";
        String line = "===== 执行完成：成功 " + ok + " 个文件，失败 " + fail + " 个文件" + note
                + " =====";
        if (fail > 0) {
            error(line);
        } else {
            log(line);
        }
        if (skipped > 0) {
            log("跳过 " + skipped + " 个没有可执行语句的文件");
        }
        return "成功 " + ok + " 个文件，失败 " + fail + " 个文件" + note + "。"
                + LogText.NL + "详见日志。";
    }

    // ---------------------------------------------------------------- 内部

    /** 改名映射按"最初的表名"累计，否则第二轮改写的"原表名"列会显示上一轮的新名。 */
    /**
     * 把这一轮的改名折成"最初的表名 → 现在的表名"：renamed 里的 old 可能已经是被改过的名字，
     * 用快照里的映射反查回原名，回主线程再 putAll 累计，"原表名"列才不会显示中间态。
     */
    private static Map<String, String> reverseRename(Map<String, String> renameMap,
                                                     SqlScript.Rewrite rw) {
        Map<String, String> rev = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> en : renameMap.entrySet()) {
            rev.put(en.getValue(), en.getKey());
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map<String, Object> item : rw.renamed) {
            String old = String.valueOf(item.get("old"));
            String rep = String.valueOf(item.get("new"));
            out.put(rev.containsKey(old) ? rev.get(old) : old, rep);
        }
        return out;
    }

    private static String describeRenamed(List<Map<String, Object>> renamed) {
        List<String> parts = new ArrayList<String>();
        for (Map<String, Object> item : renamed) {
            parts.add(item.get("old") + " → " + item.get("new")
                    + "（" + item.get("n") + " 处）");
        }
        return String.join("、", parts);
    }

    /** 表格里一行是一个表名，不是一个文件。 */
    private void refreshTable() {
        model.setRowCount(0);
        rowPath.clear();
        for (Entry e : entries) {
            for (Map<String, Object> t : e.tabs) {
                String name = String.valueOf(t.get("name"));
                // renameMap 存的是 原→新，"原表名"这一列要的是反查
                String origin = name;
                for (Map.Entry<String, String> en : e.renameMap.entrySet()) {
                    if (en.getValue().equals(name)) {
                        origin = en.getKey();
                        break;
                    }
                }
                model.addRow(new Object[]{baseName(e.path), origin,
                        String.valueOf(t.get("count")), name});
                rowPath.add(e.path);
            }
        }
    }

    private void log(String text) {
        logPanel.log(text);
    }

    private void error(String text) {
        logPanel.error(text);
    }

    private static String baseName(String path) {
        String s = path == null ? "" : path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? s : s.substring(at + 1);
    }
}
