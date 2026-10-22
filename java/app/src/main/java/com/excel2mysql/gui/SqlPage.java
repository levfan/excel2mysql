package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.MetaExport;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.Prefix;
import com.excel2mysql.core.SqlRunner;
import com.excel2mysql.core.SqlScript;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
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
 * SQL 脚本改名入库页签，对应 main.py:1115-1324。
 *
 * 脚本的改写只动内存里的文本，落盘要另外点「保存修改后ddl脚本」——
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

        /** 交付件落在哪儿：界面里是程序同级，自检指到临时目录。 */
        String exportRoot();

        void warn(String text);
    }

    /** 一个脚本文件：原文、当前（可能被改过的）文本、以及改名映射。 */
    public static final class Entry {
        public final String path;
        public String text;
        public boolean modified;
        public List<Map<String, Object>> tabs;
        /**
         * 当前表名 → 那一张表的字段定义，读入时整份解析一次存下来。
         * 双击一行只要其中一张：10MB 脚本整份重解析要 1.8 秒（实测），
         * 放在点开那一刻就是"点一下卡一下"，放在读入的子线程里只是进度条多走一格。
         * 脚本正文改了表名后按新名重挂（见 rekeyParsed），对不上号就置空回退现解析。
         */
        public Map<String, SqlScript.ParsedTable> parsed;
        /** 最初的表名 → 现在脚本里写的表名，多轮改写累计；"原表名"列靠它回显。 */
        public final Map<String, String> renameMap = new LinkedHashMap<String, String>();
        /** 脚本里的当前表名 → 页面显示名：前缀两个按钮只动这一层，动作按钮才写进 text。 */
        public final Map<String, String> displayMap = new LinkedHashMap<String, String>();

        Entry(String path, String text, List<Map<String, Object>> tabs) {
            this.path = path;
            this.text = text;
            this.tabs = tabs;
        }

        Entry(String path, String text, List<Map<String, Object>> tabs,
              Map<String, SqlScript.ParsedTable> parsed) {
            this(path, text, tabs);
            this.parsed = parsed;
        }
    }

    private static final String[] COLUMNS =
            {"序号", "文件", "原表名", "CREATE 出现次数", "当前表名"};

    /**
     * 交给子线程的输入快照：改写期间主线程可能继续动列表，子线程只读这份拷贝。
     * 保存/执行/生成三条路径只用到 path/text。
     */
    public static final class Src {
        public final String path;
        public final String text;

        public Src(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }

    /** 读盘任务的结局：成功并进来的、读失败的、原本就在列表里的，弹窗要逐条列出完整路径。 */
    public static final class LoadResult {
        public final List<Entry> loaded;
        public final List<String[]> failed;
        public final List<String> duplicates;
        public final boolean cancelled;

        public LoadResult(List<Entry> loaded, List<String[]> failed,
                          List<String> duplicates, boolean cancelled) {
            this.loaded = loaded;
            this.failed = failed;
            this.duplicates = duplicates;
            this.cancelled = cancelled;
        }
    }

    /** 保存任务的结局：saved/failed 里的数组都是 { 源路径, 落盘路径或失败原因 }。 */
    public static final class SaveResult {
        public final List<String[]> saved;
        public final List<String[]> failed;
        public final boolean cancelled;

        public SaveResult(List<String[]> saved, List<String[]> failed, boolean cancelled) {
            this.saved = saved;
            this.failed = failed;
            this.cancelled = cancelled;
        }
    }

    /** 一个脚本文件的执行结局：status 取 ok / fail / skip / cancel。 */
    public static final class RunFile {
        public final String path;
        public final String status;
        public final String detail;

        public RunFile(String path, String status, String detail) {
            this.path = path;
            this.status = status;
            this.detail = detail;
        }
    }

    /** 执行任务的结局：三个计数、两个状态，以及逐文件结局（弹窗按完整路径列出来）。 */
    public static final class RunResult {
        public final int ok;
        public final int fail;
        public final int skipped;
        public final boolean cancelled;
        /** 连不上库：详细原因已经在日志里报过了，收尾不该再重复一遍汇总。 */
        public final boolean connFailed;
        public final List<RunFile> files;
        public final String target;

        public RunResult(int ok, int fail, int skipped, boolean cancelled, boolean connFailed,
                         List<RunFile> files, String target) {
            this.ok = ok;
            this.fail = fail;
            this.skipped = skipped;
            this.cancelled = cancelled;
            this.connFailed = connFailed;
            this.files = files;
            this.target = target;
        }
    }

    private static final String PREFIX_HINT =
            "如 dim_（加前缀，已带该前缀的表自动跳过）；填 change 再点「替换首个前缀」"
            + "则把 ods_t_order 换成 change_t_order。清空后再点一次可恢复原表名";
    private static final String REPLACE_TIP =
            "把每个表名第一个下划线之前的部分换成上面填的内容，只改页面显示；\n"
            + "点保存/执行/生成时才写进脚本并把 CREATE/DROP/INSERT/ALTER/TRUNCATE 的引用一起改掉；\n"
            + "表名里没有下划线的不动。末尾下划线不用写，留空再点一次恢复原表名";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Host host;
    private final List<Entry> entries = new ArrayList<Entry>();

    /** 两颗生成按钮的说明与「Excel 导入」页那两颗同一口径，只是数据源换成了脚本正文。 */
    private static final String GEN_STD_TIP =
            "在程序同级建 sql_meta_data_file_年月日 目录，每张表一个 xlsx：\n"
                    + "Sheet1「元数据」是脚本里解析出的字段定义，Sheet2「数据」只有表头\n"
                    + "（脚本里不含数据行，这一张是给下游填数据的模板）\n"
                    + "目录名带 sql_ 前缀，与「Excel 导入」那一页的生成件互不覆盖";
    private static final String GEN_SIMPLE_TIP =
            "在程序同级建 sql_simple_meta_data_file_年月日 目录，每张表一个 xlsx：\n"
                    + "只留「数据」一个 Sheet，里面只有脚本里那些字段的英文名表头\n"
                    + "目录名带 sql_ 前缀，与「Excel 导入」那一页的生成件互不覆盖";
    private JButton genStd;
    private JButton genSimple;
    /** 这一页有没有任务在跑：只用来管两颗生成按钮，由主窗口每次改动任务清单时回来说一句。 */
    private boolean genBusy;
    /** 大小写开关：清单、元数据弹窗和生成件同步按大写显示；脚本正文不回写。 */
    private final JCheckBox upperBox = new JCheckBox("表名与字段名转大写");
    private final List<String> rowPath = new ArrayList<String>();
    private final List<String> rowOrigin = new ArrayList<String>();
    private final List<String> rowActual = new ArrayList<String>();
    private boolean editingRowName;
    private final DefaultTableModel model;
    private final JTable table;
    private final LogPanel logPanel = new LogPanel();
    private final HintTextField prefixField;
    private final JButton runButton;
    /** 表格与日志之间的分栏：初始比例、把手宽度都要能被读出来核对（Python 那边是 sql_split）。 */
    private JSplitPane split;
    private final HintLabel hint = new HintLabel();

    public SqlPage(Host host) {
        this.host = host;
        setLayout(new BorderLayout());

        model = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return col == 4;
            }

            @Override
            public void setValueAt(Object value, int row, int col) {
                Object old = col == 4 && row >= 0 && row < getRowCount() ? getValueAt(row, col) : null;
                super.setValueAt(value, row, col);
                if (col == 4 && !editingRowName && !String.valueOf(old).equals(String.valueOf(value))) {
                    renameRow(row, String.valueOf(value));
                }
            }
        };
        table = new JTable(model);
        Tables.style(table);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(UiScale.px(54));
        table.getColumnModel().getColumn(1).setPreferredWidth(UiScale.px(300));
        table.getColumnModel().getColumn(2).setPreferredWidth(UiScale.px(260));
        table.getColumnModel().getColumn(3).setPreferredWidth(UiScale.px(130));
        // 「文件」列挂完整路径：脚本同名时只靠文件名分不出是哪个目录里的那份（同 Python 的 tooltip）
        table.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable t, Object v,
                    boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, v, selected, focus, row, column);
                setToolTipText(row < rowPath.size() ? rowPath.get(row) : "");
                return this;
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int column = table.columnAtPoint(e.getPoint());
                    if (column >= 0 && column <= 3) {
                        showMetadataAtRow(table.rowAtPoint(e.getPoint()));
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setMinimumSize(new Dimension(UiScale.px(300), UiScale.px(120)));

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
        apply.setToolTipText("只改「当前表名」列的显示（双击看的元数据表名同步），脚本正文不动；"
                + "点保存/执行/生成时才把显示名写进脚本。前缀留空再点一次，显示恢复为脚本原名。");
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
        JButton save = new JButton("保存修改后ddl脚本");
        save.addActionListener(e -> saveModified());
        runButton = new JButton("执行修改后ddl脚本到当前库");
        runButton.addActionListener(e -> {
            String hint = execute();
            if (hint != null) {
                host.warn(hint);
            }
        });
        genStd = new JButton("生成标准excel文件");
        genStd.setToolTipText(GEN_STD_TIP);
        genStd.setEnabled(false);
        genStd.addActionListener(e -> generateExcel(true));
        genSimple = new JButton("生成简易excel文件");
        genSimple.setToolTipText(GEN_SIMPLE_TIP);
        genSimple.setEnabled(false);
        genSimple.addActionListener(e -> generateExcel(false));
        JButton clearLog = new JButton("清除日志");
        clearLog.addActionListener(e -> logPanel.clearLog());

        // 默认不勾选；勾选后清单和元数据一起按大写显示，脚本正文仍不回写
        upperBox.setToolTipText("勾选后，「当前表名」清单、双击打开的表元数据以及生成的 Excel 里的"
                + "表名与字段名全部大写（t_order → T_ORDER、order_amount → ORDER_AMOUNT）；"
                + "默认不勾选。脚本正文不改，手动改过的新表名会保留其输入内容。");
        upperBox.addActionListener(e -> {
            refreshTable();
            log("[提示] 表名与字段名大小写切换为："
                    + (upperBox.isSelected() ? "大写" : "保持原样")
                    + "（当前表名清单、表元数据和生成的 Excel 一起变；脚本正文不改）");
        });

        // 前缀行按 Python 的 addWidget(ed_sql_prefix, 1)：输入框吃掉剩余宽度，两颗按钮贴右
        JPanel prefixBtns = flow();
        prefixBtns.add(apply);
        prefixBtns.add(replace);
        JPanel prefixRow = new JPanel(new BorderLayout(UiScale.px(6), 0));
        prefixRow.add(new JLabel("表名前缀："), BorderLayout.WEST);
        prefixRow.add(prefixField, BorderLayout.CENTER);
        prefixRow.add(prefixBtns, BorderLayout.EAST);

        // 保存/执行在左、清除日志在右（main.py:1178-1183 的 addStretch(1)）
        JPanel runBtns = flow();
        runBtns.add(save);
        runBtns.add(runButton);
        runBtns.add(genStd);
        runBtns.add(genSimple);
        JPanel actRow = new JPanel(new BorderLayout());
        actRow.add(runBtns, BorderLayout.WEST);
        actRow.add(clearLog, BorderLayout.EAST);

        JPanel nameRow = flow();
        nameRow.add(upperBox);

        JPanel actions = new JPanel(new BorderLayout());
        actions.add(prefixRow, BorderLayout.NORTH);
        actions.add(nameRow, BorderLayout.CENTER);
        actions.add(actRow, BorderLayout.SOUTH);

        JPanel upper = new JPanel(new BorderLayout(UiScale.px(4), UiScale.px(4)));
        upper.add(scroll, BorderLayout.CENTER);
        upper.add(actions, BorderLayout.SOUTH);

        JPanel topRow = flow();
        topRow.add(add);
        topRow.add(remove);

        JScrollPane logScroll = new JScrollPane(logPanel);
        // Python 的 SQL 日志区同样没有组框标题，只有上面那排按钮；
        // 下限取日志面板自己量出来的行数高度（这一段里没有标题行，不用再加）
        logScroll.setMinimumSize(new Dimension(UiScale.px(300),
                logPanel.getMinimumSize().height));

        // 段数、比例、把手宽度照 main.py 的 setSizes([230, 330])：日志段给到最大。
        // Swing 没有 setChildrenCollapsible(false)，用最小尺寸顶住——拖到 0 高那段就等于没了
        upper.setMinimumSize(new Dimension(UiScale.px(10), UiScale.px(80)));
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, logScroll);
        split.setDividerSize(UiScale.px(6));
        // Qt 的 setStretchFactor(1, 3) 换到 Swing 就是"上段拿 1/(1+3) 的余量"
        split.setResizeWeight(0.25);
        split.setDividerLocation(UiScale.px(230));
        split.setContinuousLayout(true);
        // 与 Excel 页同一件事：Qt 的 setSizes([260,290]) 会等比缩放，JSplitPane 只认绝对值
        splitPlaced = false;
        sqlUpper = upper;
        sqlLog = logScroll;
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                applySplitProportions();
            }
        });

        JPanel north = new JPanel(new BorderLayout());
        north.add(hint, BorderLayout.NORTH);
        north.add(topRow, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private static JPanel flow() {
        return new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
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

    /** 自检用：某份脚本当前的字段定义缓存；改名撞车后被丢掉就是 null。 */
    public Map<String, SqlScript.ParsedTable> parsedCacheOf(String path) {
        for (Entry e : entries) {
            if (e.path.equals(path)) {
                return e.parsed;
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

    /** 目标库提示条由主窗口同源刷新，三页说的是同一个库。 */
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

    /** 提示条本体：自检查它能不能真的拖选复制。 */
    public HintLabel hintField() {
        return hint;
    }

    public boolean hintIsOk() {
        return hint.isOk();
    }

    public LogPanel logPanel() {
        return logPanel;
    }

    /** 分栏的三个配置值：离屏环境里量不准实际高度，配的是什么至少能核对。 */
    public int splitDividerSize() {
        return split.getDividerSize();
    }

    private java.awt.Component sqlUpper;
    private java.awt.Component sqlLog;
    private boolean splitPlaced;

    /** 表格与日志按 230:330 等比摆一次；用户之后自己拖的把手不该被程序改回去。 */
    public void applySplitProportions() {
        if (splitPlaced || split == null || split.getHeight() < UiScale.px(120)
                || sqlUpper == null || sqlLog == null) {
            return;
        }
        splitPlaced = true;
        int usable = split.getHeight() - split.getDividerSize();
        int first = (int) Math.round(usable * 230.0 / 560.0);
        split.setDividerLocation(Math.max(first, sqlUpper.getMinimumSize().height));
    }

    public double splitResizeWeight() {
        return split.getResizeWeight();
    }

    public int splitDividerLocation() {
        return split.getDividerLocation();
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

    /** 双端界面枚举对撞器要读的表格列头与列数（见 java/tools/gui_parity.py）。 */
    public int columnCount() {
        return model.getColumnCount();
    }

    public String columnName(int col) {
        return String.valueOf(model.getColumnName(col));
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
        // 取消选择器是用户自己的中止动作，Python 那边一句话不说（main.py:1222），照它来
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<String> paths = new ArrayList<String>();
        for (File f : chooser.getSelectedFiles()) {
            paths.add(f.getPath());
        }
        addFiles(paths);
    }

    public JCheckBox upperBox() {
        return upperBox;
    }

    /** 自检入口：不靠模拟鼠标，直接核对行数据、单元格可编辑性与改名/元数据动作。 */
    public int sqlRowCount() {
        return rowActual.size();
    }

    public String sqlRowActualName(int row) {
        return row < 0 || row >= rowActual.size() ? null : rowActual.get(row);
    }

    public String sqlRowDisplayName(int row) {
        return row < 0 || row >= rowActual.size() ? null : String.valueOf(model.getValueAt(row, 4));
    }

    public boolean sqlRowNameEditable(int row) {
        return row >= 0 && row < model.getRowCount() && model.isCellEditable(row, 4);
    }

    public void setSqlRowName(int row, String name) {
        model.setValueAt(name, row, 4);
    }

    public void showMetadata(int row) {
        showMetadataAtRow(row);
    }

    public SqlScript.ParsedTable metadataAtRow(int row) {
        if (row < 0 || row >= rowActual.size()) {
            return null;
        }
        for (Entry e : entries) {
            if (e.path.equals(rowPath.get(row))) {
                return parsedOf(e, rowActual.get(row));
            }
        }
        return null;
    }

    /**
     * 一张表的字段定义：先取读入时缓存的那一份，取不到才现解析一次并补回缓存。
     *
     * 补回去这一步让"第二次点同一份脚本"不用再等 1.8 秒——缓存被改名撞车丢掉时，
     * 慢的只有点开的那一次。
     */
    private SqlScript.ParsedTable parsedOf(Entry e, String name) {
        if (e.parsed == null) {
            Map<String, SqlScript.ParsedTable> map =
                    new LinkedHashMap<String, SqlScript.ParsedTable>();
            for (SqlScript.ParsedTable t : SqlScript.parseCreateTables(e.text)) {
                map.put(t.tableName, t);
            }
            e.parsed = map;
        }
        return e.parsed.get(name);
    }

    /**
     * 脚本正文里改了表名，把解析缓存按新名重挂。
     *
     * 改表名不动列定义，缓存里那一格换个键还是对的；只要有一种情况成立就返回 null
     * 丢掉整份：要改的名字不在缓存里、新名字和缓存里另一张表撞车。
     * 宁可下次点开慢那一次，也不能让元数据弹窗显示旧表名下的字段。
     */
    public static Map<String, SqlScript.ParsedTable> rekeyParsed(
            Map<String, SqlScript.ParsedTable> parsed, Map<String, String> renames) {        if (parsed == null || !parsed.keySet().containsAll(renames.keySet())) {
            return null;
        }
        Map<String, SqlScript.ParsedTable> out =
                new LinkedHashMap<String, SqlScript.ParsedTable>();
        for (Map.Entry<String, SqlScript.ParsedTable> en : parsed.entrySet()) {
            String name = renames.get(en.getKey());
            if (name == null) {
                name = en.getKey();
            } else {
                en.getValue().tableName = name;
            }
            if (out.containsKey(name)) {
                return null;
            }
            out.put(name, en.getValue());
        }
        return out;
    }

    /** 自检入口：双击元数据弹窗里显示的表名（前缀/大写都算上）。 */
    public String metadataShownName(int row) {
        if (row < 0 || row >= rowActual.size()) {
            return null;
        }
        return displayName(displayBase(entryOfRow(row), rowActual.get(row)));
    }
    /**
     * 读盘与提取表名都在子线程跑（几十万行的脚本不能压在事件分发线程上），
     * 只有"并入列表 + 刷新表格"回到事件分发线程；日志全程留在 SQL 页。
     */
    @SuppressWarnings("unchecked")
    public void addFiles(List<String> paths) {
        Set<String> existing = new LinkedHashSet<String>(paths());
        List<String> todo = new ArrayList<String>();
        List<String> dups = new ArrayList<String>();
        for (String p : paths) {
            if (existing.contains(p)) {
                dups.add(p);
                continue;
            }
            todo.add(p);
        }
        if (todo.isEmpty()) {
            for (String p : dups) {
                log("[跳过] 已在列表：" + baseName(p));
            }
            refreshTable();
            finishAdd(new LoadResult(new ArrayList<Entry>(), new ArrayList<String[]>(),
                    dups, false));
            return;
        }
        final List<String> files = todo;
        final List<String> duplicates = dups;
        host.runSqlTask("正在读取 SQL 脚本…", sink -> {
            sink.setTotal(files.size() + duplicates.size());
            List<Entry> loaded = new ArrayList<Entry>();
            List<String[]> failed = new ArrayList<String[]>();
            for (String p : duplicates) {
                sink.log("[跳过] 已在列表：" + baseName(p));
                sink.step(baseName(p));
            }
            for (String p : files) {
                String fname = baseName(p);
                if (sink.cancelled()) {
                    break;
                }
                String text;
                try {
                    text = SqlRunner.readScript(new File(p));
                } catch (Exception ex) {
                    String reason = LogText.describe(ex);
                    failed.add(new String[]{p, reason});
                    sink.logError("[读取失败] " + fname + " 未能读入，已跳过" + LogText.NL
                            + LogText.indentDetail(reason));
                    sink.step(fname);
                    continue;
                }
                List<Map<String, Object>> tabs = SqlScript.extractCreateTables(text);
                Map<String, SqlScript.ParsedTable> parsed =
                        new LinkedHashMap<String, SqlScript.ParsedTable>();
                for (SqlScript.ParsedTable t : SqlScript.parseCreateTables(text)) {
                    parsed.put(t.tableName, t);
                }
                List<String> names = new ArrayList<String>();
                for (Map<String, Object> t : tabs) {
                    names.add(String.valueOf(t.get("name")));
                }
                sink.log("[已加载] " + fname + "：" + tabs.size() + " 个表（"
                        + (names.isEmpty() ? "未发现 CREATE TABLE 语句" : String.join("、", names))
                        + "）");
                loaded.add(new Entry(p, text, tabs, parsed));
                sink.step(fname);
            }
            return new LoadResult(loaded, failed, duplicates, sink.cancelled());
        }, out -> finishAdd((LoadResult) out));
    }

    /** 添加结束：日志写一行汇总，弹窗逐个列出完整路径，同名脚本才分得清来自哪个目录。 */
    private void finishAdd(LoadResult r) {
        for (Entry e : r.loaded) {
            entries.add(e);
        }
        refreshTable();
        int tables = 0;
        for (Entry e : r.loaded) {
            tables += e.tabs.size();
        }
        String head = "已加载 " + r.loaded.size() + " 个脚本，共 " + tables + " 张表。";
        if (r.cancelled) {
            head += "（已取消，剩余文件未读入）";
        }
        StringBuilder body = new StringBuilder(head);
        for (Entry e : r.loaded) {
            body.append(LogText.NL).append("[已加载] ").append(e.path)
                    .append("：").append(e.tabs.size()).append(" 张表");
        }
        for (String p : r.duplicates) {
            body.append(LogText.NL).append("[跳过] ").append(p).append("：已在列表");
        }
        for (String[] f : r.failed) {
            body.append(LogText.NL).append("[读取失败] ").append(f[0]).append("：")
                    .append(f[1].replaceAll("\\s+", " ").trim());
        }
        String line = "===== 添加完成：" + r.loaded.size() + " 个脚本，"
                + r.failed.size() + " 个读取失败，" + r.duplicates.size() + " 个重复 =====";
        if (r.failed.isEmpty()) {
            log(line);
        } else {
            error(line);
        }
        host.inform2("添加结果", body.toString());
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

    /** 两颗前缀按钮的共同前置检查：没有可处理的表就返回一句说明，让按钮走 warn 弹窗。 */
    private String prefixBlocker() {
        if (entries.isEmpty()) {
            return LogText.NO_SQL_HINT;
        }
        for (Entry e : entries) {
            if (!e.tabs.isEmpty()) {
                return null;
            }
        }
        return "列表里的脚本没有解析出 CREATE TABLE 表（空脚本或只有注释），没有表名可处理。";
    }

    /**
     * 批量加前缀：只改「当前表名」的显示层，脚本正文不动；已带该前缀的进跳过清单，
     * 重复点不会叠前缀。前缀留空表示撤掉显示层、恢复脚本原名。
     * 返回非 null 表示这次点下去只该弹提示（这条路径现在没有校验失败）。
     */
    public String applyPrefix() {
        String blocked = prefixBlocker();
        if (blocked != null) {
            return blocked;
        }
        String prefix = Names.pyStrip(prefixField.getText()).replace("`", "").replace("\\", "");
        if (prefix.isEmpty()) {
            clearDisplayPrefix();
            return null;
        }
        int total = 0;
        for (Entry e : entries) {
            Map<String, String> plan = new LinkedHashMap<String, String>();
            Map<String, String> current =
                    new LinkedHashMap<String, String>(e.displayMap);
            List<String> skipped = new ArrayList<String>();
            for (Map<String, Object> t : e.tabs) {
                String old = String.valueOf(t.get("name"));
                String shown = current.containsKey(old) ? current.get(old) : old;
                if (shown.startsWith(prefix)) {
                    if (!shown.equals(old)) {
                        plan.put(old, shown);     // 已带前缀：保留原显示，不叠也不丢
                    }
                    skipped.add(shown);
                    continue;
                }
                plan.put(old, prefix + shown);
            }
            e.displayMap.clear();
            e.displayMap.putAll(plan);
            total += plan.size();
            if (!plan.isEmpty()) {
                log("[前缀] " + baseName(e.path) + "：" + describeDisplayPlan(plan));
            }
            if (!skipped.isEmpty()) {
                log("[跳过] " + baseName(e.path) + "：已带前缀 " + String.join("、", skipped));
            }
        }
        refreshTable();
        log("[前缀完成] 共 " + total + " 个表名改为显示前缀「" + prefix
                + "」（脚本正文未改，点保存/执行/生成时才写入）");
        return null;
    }

    /** 空前缀再点一次：只撤掉显示层，表名回到脚本里的原名。 */
    private void clearDisplayPrefix() {
        int n = 0;
        for (Entry e : entries) {
            n += e.displayMap.size();
            e.displayMap.clear();
        }
        refreshTable();
        if (n > 0) {
            log("[前缀完成] 前缀已清空，" + n + " 个表名的显示恢复为脚本中的原名（脚本正文未改）");
        } else {
            log("[前缀完成] 前缀为空，表名显示本来就是脚本原名，无需恢复");
        }
    }

    /** 换首个前缀：清洗与合法性校验走 core 的同一套规则，同样只改显示层。 */
    public String replaceFirstPrefix() {
        String blocked = prefixBlocker();
        if (blocked != null) {
            return blocked;
        }
        if (Names.pyStrip(prefixField.getText()).isEmpty()) {
            clearDisplayPrefix();
            return null;
        }
        String head = Prefix.clean(prefixField.getText());
        if (head.isEmpty()) {
            return "请先输入新的前缀（如 change）。";
        }
        if (!Prefix.isValid(head)) {
            return "前缀「" + head + "」含非法字符：只能用字母、数字、下划线；"
                    + "末尾的下划线不用写，原表名自带。";
        }
        int total = 0;
        for (Entry e : entries) {
            Map<String, String> plan = new LinkedHashMap<String, String>();
            Map<String, String> current =
                    new LinkedHashMap<String, String>(e.displayMap);
            List<String> skipped = new ArrayList<String>();
            for (Map<String, Object> t : e.tabs) {
                String old = String.valueOf(t.get("name"));
                String shown = current.containsKey(old) ? current.get(old) : old;
                String rep = Prefix.swapFirstSegment(shown, head);
                if (rep == null) {
                    if (!shown.equals(old)) {
                        plan.put(old, shown);     // 没换成：保留原显示
                    }
                    skipped.add(shown);
                    continue;
                }
                plan.put(old, rep);
            }
            e.displayMap.clear();
            e.displayMap.putAll(plan);
            total += plan.size();
            if (!plan.isEmpty()) {
                log("[换前缀] " + baseName(e.path) + "：" + describeDisplayPlan(plan));
            }
            if (!skipped.isEmpty()) {
                log("[跳过] " + baseName(e.path) + "：未改写 " + String.join("、", skipped)
                        + "（表名里没有下划线）");
            }
        }
        refreshTable();
        log("[换前缀完成] 共 " + total + " 个表名改为显示名"
                + "（脚本正文未改，点保存/执行/生成时才写入）");
        return null;
    }

    private static String describeDisplayPlan(Map<String, String> plan) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> en : plan.entrySet()) {
            parts.add(en.getKey() + " → " + en.getValue() + "（显示）");
        }
        return String.join("、", parts);
    }

    /**
     * 把页面显示的表名写进脚本；保存/执行/生成三个动作共用这一道。
     * 只在动作按钮点下去时调用，前缀两个按钮本身不碰 text。
     */
    private boolean materializeDisplayNames() {
        boolean pending = false;
        for (Entry e : entries) {
            if (e.displayMap.isEmpty()) {
                continue;
            }
            pending = true;
            SqlScript.Rewrite rw = SqlScript.applyDisplayRenames(e.text, e.displayMap);
            e.renameMap.putAll(reverseRename(e.renameMap, rw));
            if (!rw.renamed.isEmpty()) {
                e.text = rw.text;
                e.tabs = SqlScript.extractCreateTables(rw.text);
                Map<String, String> renames = new LinkedHashMap<String, String>();
                for (Map<String, Object> r : rw.renamed) {
                    renames.put(String.valueOf(r.get("old")), String.valueOf(r.get("new")));
                }
                e.parsed = rekeyParsed(e.parsed, renames);
                e.modified = true;
                log("[生效] " + baseName(e.path) + "：" + describeRenamed(rw.renamed)
                        + " 已写入脚本");
            }
            if (!rw.noHit.isEmpty()) {
                log("[跳过] " + baseName(e.path) + "：脚本里找不到 "
                        + String.join("、", rw.noHit) + " 的引用，未改写");
            }
            e.displayMap.clear();
        }
        if (pending) {
            refreshTable();
        }
        return pending;
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
        materializeDisplayNames();
        List<Src> items = new ArrayList<Src>();
        for (Entry e : entries) {
            if (e.modified) {
                items.add(new Src(e.path, e.text));
            }
        }
        if (items.isEmpty()) {
            log("[保存] 没有修改过的脚本文件");
            host.inform2("保存完成", "没有修改过的脚本文件，无需保存。");
            return;
        }
        final List<Src> todo = items;
        host.runSqlTask("正在保存修改后的脚本…", sink -> {
            sink.setTotal(todo.size());
            List<String[]> saved = new ArrayList<String[]>();
            List<String[]> failed = new ArrayList<String[]>();
            for (Src s : todo) {
                String fname = baseName(s.path);
                if (sink.cancelled()) {
                    break;
                }
                String out = modifiedPath(s.path);
                try {
                    Files.write(Paths.get(out), s.text.getBytes(UTF8));
                } catch (Exception ex) {
                    String reason = LogText.describe(ex);
                    failed.add(new String[]{s.path, reason});
                    sink.logError("[保存失败] " + fname + "：" + reason);
                    sink.step(fname);
                    continue;
                }
                saved.add(new String[]{s.path, out});
                sink.log("[已保存] " + out);
                sink.step(fname);
            }
            return new SaveResult(saved, failed, sink.cancelled());
        }, out -> finishSave((SaveResult) out));
    }

    /** 保存结束：日志与弹窗都按文件列出落盘路径，失败要写明是哪一份、为什么。 */
    private void finishSave(SaveResult r) {
        Set<String> done = new LinkedHashSet<String>();
        for (String[] s : r.saved) {
            done.add(s[0]);
        }
        for (Entry e : entries) {
            if (done.contains(e.path)) {
                e.modified = false;
            }
        }
        int n = done.size();
        String note = r.cancelled ? "（已取消，剩余脚本未保存）" : "";
        StringBuilder body = new StringBuilder();
        body.append(n > 0 ? "已保存 " + n + " 个脚本副本" + note + "。"
                : "没有文件写出" + note + "。");
        for (String[] s : r.saved) {
            body.append(LogText.NL).append("[已保存] ").append(s[1]);
        }
        for (String[] f : r.failed) {
            body.append(LogText.NL).append("[保存失败] ").append(f[0]).append("：")
                    .append(f[1].replaceAll("\\s+", " ").trim());
        }
        body.append(LogText.NL).append("详见日志。");
        if (n > 0) {
            log("===== 保存完成：" + n + " 个脚本副本" + note + " =====");
            host.inform2("保存完成", body.toString());
        } else {
            error("===== 保存完成：没有文件写出" + note + " =====");
            host.inform2("保存完成", body.toString());
        }
    }

    /**
     * 每个文件一个事务地执行到当前库。返回非 null 表示只该弹提示、没有连库。
     * 执行并入统一任务位：抢不到任务位就不会开出第二个线程，进度、取消与日志走同一套。
     */
    public String execute() {
        Config cfg = host.config();
        if (cfg == null || !cfg.hasDatabase()) {
            return LogText.NO_DB_HINT;
        }
        if (entries.isEmpty()) {
            return LogText.NO_SQL_HINT;
        }
        materializeDisplayNames();
        log("===== 开始执行脚本（目标库 " + cfg.database + "，每个文件一个事务）=====");
        List<Src> items = new ArrayList<Src>();
        for (Entry e : entries) {
            items.add(new Src(e.path, e.text));
        }
        final List<Src> todo = items;
        final Config conf = cfg;
        host.runSqlTask("正在执行脚本到目标库…", sink -> runScripts(todo, conf, sink), out -> {
            RunResult r = (RunResult) out;
            if (r.connFailed) {
                return;                       // 连不上库时已经报过详细原因，不再重复汇总
            }
            host.inform2("执行完成", finishRun(r));
        });
        return null;
    }

    /**
     * 执行任务体：切分语句、逐文件一个事务地跑，进度与日志只经 sink 往外报。
     * 单独成方法是因为真库验证走不了 GUI——命令行入口拿同一个任务体连库跑，
     * 界面那层只管起线程与落界面。
     */
    public static RunResult runScripts(List<Src> items, Config cfg,
                                       com.excel2mysql.core.ImportParse.Sink sink) {
        sink.log("正在切分 " + items.size() + " 个脚本的语句…");
        final List<List<String>> plans = new ArrayList<List<String>>();
        for (Src s : items) {
            plans.add(SqlScript.splitStatements(s.text));
        }
        int totalStatements = 0;
        for (List<String> stmts : plans) {
            totalStatements += stmts.size();
        }
        // 计项按语句：一个脚本几万条时也照样推进，但每个文件最多刷约 100 次，
        // 不然进度框会被刷屏到看不出动静
        sink.setTotal(Math.max(1, totalStatements));
        final String target = cfg.database + "@" + cfg.host + ":" + cfg.port;
        int ok = 0;
        int fail = 0;
        int skipped = 0;
        List<RunFile> files = new ArrayList<RunFile>();
        DbClient client;
        try {
            client = new DbClient(cfg);
        } catch (Exception ex) {
            sink.logError("[连接失败] 无法连接到 " + target + "（用户 " + cfg.user
                    + "），剩余脚本均未执行" + LogText.NL
                    + LogText.indentDetail(LogText.describe(ex)));
            return new RunResult(0, 0, 0, true, true, files, target);
        }
        for (int i = 0; i < items.size(); i++) {
            Src s = items.get(i);
            List<String> stmts = plans.get(i);
            String fname = baseName(s.path);
            if (sink.cancelled()) {
                break;
            }
            if (stmts.isEmpty()) {
                skipped++;
                files.add(new RunFile(s.path, "skip", "没有可执行的语句"));
                sink.log("[跳过] " + fname + "：没有可执行的语句");
                sink.step(fname);
                continue;
            }
            final int size = stmts.size();
            final int chunk = Math.max(1, size / 100);
            final int[] reported = {0};
            SqlRunner.FileResult r = SqlRunner.runText(client, fname, s.text,
                    new SqlRunner.StatementHook() {
                        @Override
                        public boolean cancelled() {
                            return sink.cancelled();
                        }

                        @Override
                        public void onStatement(int done, int total) {
                            if (done - reported[0] >= chunk) {
                                sink.advance(chunk, fname + " 第 " + done + "/" + total + " 条");
                                reported[0] = done;
                            }
                        }
                    });
            // 把余数补齐，进度条总数才落得准
            sink.advance(Math.max(0, size - reported[0]), fname + " 收尾");
            if (r.cancelled) {
                // 取消的文件既不算成功也不算失败，但它确实被处理过了
                ok++;
                files.add(new RunFile(s.path, "cancel", "已执行 " + r.executed + "/" + size
                        + " 条，本文件事务已回滚"));
                sink.log("[已取消] " + fname + "：已执行 " + r.executed + "/" + size + " 条，"
                        + "本文件事务已回滚" + LogText.NL + "    " + LogText.ROLLBACK_NOTE);
            } else if (r.ok) {
                ok++;
                files.add(new RunFile(s.path, "ok", r.executed + " 条语句已提交"));
                sink.log("[提交] " + fname + "：" + r.executed + " 条语句已提交");
            } else {
                fail++;
                files.add(new RunFile(s.path, "fail", "第 " + r.failedAt + "/"
                        + r.statements.size() + " 条语句报错，本文件事务已回滚"));
                sink.logError("[执行失败] " + fname + " 第 " + r.failedAt + "/" + r.statements.size()
                        + " 条语句报错，目标库 " + target + "，本文件事务已回滚" + LogText.NL
                        + LogText.indentDetail(r.error) + LogText.NL + "    出错语句："
                        + LogText.NL + LogText.formatStatement(r.failedSql) + LogText.NL
                        + "    前面 " + r.executed + " 条语句已随事务回滚。"
                        + LogText.ROLLBACK_NOTE);
            }
        }
        client.close();
        Set<String> counted = new LinkedHashSet<String>();
        for (RunFile f : files) {
            counted.add(f.path);
        }
        for (Src s : items) {                         // 取消后没轮到的脚本
            if (!counted.contains(s.path)) {
                files.add(new RunFile(s.path, "cancel", "未执行"));
            }
        }
        return new RunResult(ok, fail, skipped, sink.cancelled(), false, files, target);
    }

    /** 收尾：先写日志，再把逐文件结局拼成弹窗正文（完整路径 + 目标库）。 */
    public String finishRun(RunResult r) {
        String note = r.cancelled ? "（已取消，剩余脚本未执行）" : "";
        String line = "===== 执行完成：成功 " + r.ok + " 个文件，失败 " + r.fail + " 个文件" + note
                + " =====";
        if (r.fail > 0) {
            error(line);
        } else {
            log(line);
        }
        if (r.skipped > 0) {
            log("跳过 " + r.skipped + " 个没有可执行语句的文件");
        }
        StringBuilder body = new StringBuilder();
        body.append("成功 ").append(r.ok).append(" 个文件，失败 ").append(r.fail)
                .append(" 个文件").append(note).append("。");
        body.append(LogText.NL).append("目标库 ").append(r.target);
        for (RunFile f : r.files) {
            String label = f.status.equals("ok") ? "成功"
                    : f.status.equals("fail") ? "失败"
                    : f.status.equals("skip") ? "跳过" : "已取消";
            body.append(LogText.NL).append("[").append(label).append("] ")
                    .append(f.path).append("：").append(f.detail);
        }
        body.append(LogText.NL).append("详见日志。");
        return body.toString();
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

    /** 表格里一行是一个表名，不是一个文件；显示大小写只改视图，实际名字留在 rowActual。 */
    private void refreshTable() {
        editingRowName = true;
        try {
            model.setRowCount(0);
            rowPath.clear();
            rowOrigin.clear();
            rowActual.clear();
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
                    model.addRow(new Object[]{String.valueOf(rowPath.size() + 1),
                            baseName(e.path), origin, String.valueOf(t.get("count")),
                            displayName(displayBase(e, name))});
                    rowPath.add(e.path);
                    rowOrigin.add(origin);
                    rowActual.add(name);
                }
            }
        } finally {
            editingRowName = false;
        }
        // 判据是"有没有脚本"，不是"解析得出几张表"：解析在点下去之后才做，
        // 解析不出东西时日志会给话，按钮不该一直灰着让人以为程序坏了
        applyGenEnabled();
    }

    /**
     * 两颗生成按钮的可用性：有脚本、且这一页没有任务在跑（与 Excel 页同一口径）。
     * 别的按钮不跟着灰——它们点下去会由任务位拦下并说清楚在等哪个任务，比闷着不响有用。
     */
    private void applyGenEnabled() {
        boolean on = !entries.isEmpty() && !genBusy;
        genStd.setEnabled(on);
        genSimple.setEnabled(on);
    }

    /** 主窗口每次改动任务清单时回来说一句这一页忙不忙。 */
    public void setGenBusy(boolean busy) {
        genBusy = busy;
        applyGenEnabled();
    }

    private String displayName(String actual) {
        return upperBox.isSelected() ? actual.toUpperCase(java.util.Locale.ROOT) : actual;
    }

    /** 显示层：前缀按钮只改 displayMap，小写名原样存着，勾大写时再套一层。 */
    private static String displayBase(Entry e, String actual) {
        String shown = e == null ? null : e.displayMap.get(actual);
        return shown == null ? actual : shown;
    }

    private Entry entryOfRow(int row) {
        if (row < 0 || row >= rowPath.size()) {
            return null;
        }
        String path = rowPath.get(row);
        for (Entry e : entries) {
            if (e.path.equals(path)) {
                return e;
            }
        }
        return null;
    }

    /** 手改「当前表名」：只重写这一行所在脚本里的这个表名及引用，脚本先留在内存。 */
    private void renameRow(int row, String typedName) {
        if (row < 0 || row >= rowActual.size()) {
            return;
        }
        String oldName = rowActual.get(row);
        String origin = rowOrigin.get(row);
        String shown = displayName(displayBase(entryOfRow(row), oldName));
        String newName = typedName == null ? "" : typedName.trim().replace("`", "");
        if (newName.isEmpty()) {
            host.warn("表名不能为空，已恢复原表名「" + oldName + "」。");
            refreshTable();
            return;
        }
        Entry entry = entryOfRow(row);
        if (entry == null) {
            return;
        }
        if (newName.equals(shown)) {
            refreshTable();
            return;
        }
        if (newName.equals(oldName)) {
            // 手填脚本里的原名 = 只撤掉这一行的显示前缀，正文和修改标记都不动
            if (entry.displayMap.remove(oldName) != null) {
                refreshTable();
                log("[表名] " + oldName + " 的显示前缀已移除，脚本未改写");
            } else {
                log("[表名] " + oldName + " 未发生变化，脚本未改写");
                refreshTable();
            }
            return;
        }
        SqlScript.Rewrite rw = SqlScript.renameTable(entry.text, oldName, newName);
        if (rw.renamed.isEmpty()) {
            host.warn("表名「" + oldName + "」改成「" + newName
                    + "」没找到可改写的 SQL 引用，脚本未改。");
            refreshTable();
            return;
        }
        entry.text = rw.text;
        entry.tabs = SqlScript.extractCreateTables(entry.text);
        Map<String, String> renames = new LinkedHashMap<String, String>();
        renames.put(oldName, newName);
        entry.parsed = rekeyParsed(entry.parsed, renames);
        entry.modified = true;
        entry.renameMap.put(origin, newName);
        entry.displayMap.remove(oldName);
        entry.displayMap.remove(newName);
        refreshTable();
        Object count = rw.renamed.get(0).get("n");
        log("[表名] " + oldName + " → " + newName + "（已同步改写 " + count
                + " 处引用；未落盘）");
    }

    private void showMetadataAtRow(int row) {
        if (row < 0 || row >= rowActual.size()) {
            return;
        }
        SqlScript.ParsedTable parsed = metadataAtRow(row);
        if (parsed == null) {
            log("[元数据] 未找到表 " + rowActual.get(row) + " 的定义（可能被后续语句改写）");
            return;
        }
        new SqlMetadataDialog(host.owner(), parsed, metadataShownName(row),
                upperBox.isSelected()).setVisible(true);
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

    /**
     * 生成标准/简易 Excel：表定义来自脚本正文里的 CREATE TABLE，产物形状与
     * 「Excel 导入」页那两颗按钮一致（同一份 meta_export 出口）。
     *
     * 输出根目录走 host.exportRoot()：自检要指到临时目录，不能往构建产物里拉一堆 xlsx。
     */
    public void generateExcel(final boolean withMeta) {
        generateExcel(withMeta, host.exportRoot());
    }

    /** 输出根目录做成入参：自检要指到临时目录，不能往程序所在目录拉一堆 xlsx。 */
    public void generateExcel(final boolean withMeta, final String outRoot) {
        if (entries.isEmpty()) {
            host.warn(LogText.NO_SQL_HINT);
            return;
        }
        materializeDisplayNames();
        final List<Src> items = new ArrayList<Src>();
        for (Entry e : entries) {
            items.add(new Src(e.path, e.text));
        }
        log("===== 开始生成" + (withMeta ? "标准" : "简易") + "excel文件（脚本 " + items.size()
                + " 个，输出根目录 " + outRoot + "）=====");
        final boolean upper = upperBox.isSelected();
        host.runSqlTask("正在生成" + (withMeta ? "标准" : "简易") + "excel文件…",
                sink -> exportFromScripts(items, outRoot, withMeta, upper, sink),
                out -> finishGenerate((GenResult) out));
    }

    /** 生成结果：目录、写成数、应写数与取消标志。 */
    public static final class GenResult {
        public String dir;
        public int written;
        public int total;
        public boolean cancelled;
    }

    /**
     * 任务体：逐脚本解析 CREATE TABLE → 交给 meta_export 写盘。
     *
     * 解析取的是页面上**改过名之后**的当前文本，所以"先批量改前缀、再生成交付件"
     * 这条路是通的，产物里的表名就是新表名。同名表只按第一处定义导出，
     * 表达不了的类型（UNSIGNED、ENUM 等）逐列点名，脚本里没有数据行所以「数据」Sheet
     * 只有表头——这些都是日志里要看得见的决定，不是静默近似。
     */
    static GenResult exportFromScripts(List<Src> items, String outRoot, boolean withMeta,
                                       boolean upper,
                                       ImportParse.Sink sink) {
        GenResult res = new GenResult();
        List<MetaExport.Source> work = new ArrayList<MetaExport.Source>();
        Map<String, String> where = new LinkedHashMap<String, String>();
        Map<String, Integer> usedTables = new LinkedHashMap<String, Integer>();
        if (upper) {
            // 与 Python 的 sql_meta_export_task 同一句：生成物被改过名要能一眼看见
            sink.log("[命名] 生成物表名与字段名：转大写");
        }
        for (Src s : items) {
            if (sink.cancelled()) {
                break;
            }
            String base = baseName(s.path);
            // advance(0, …)：只换进度框上那行字、不推进进度（与 Python 的 sink.note 同义，
            // 总数要等全部解析完才算得出来）
            sink.advance(0, "解析 " + base + " 的表定义…");
            for (SqlScript.ParsedTable t : SqlScript.parseCreateTables(s.text)) {
                String name = t.tableName;
                if (where.containsKey(name)) {
                    sink.log("! [WARN] 跳过 | `" + name + "`：" + base + " 里也定义了这张表，只按 "
                            + where.get(name) + " 那一处导出");
                    continue;
                }
                where.put(name, base);
                for (String note : t.notes) {
                    sink.log("! [WARN] 说明 | `" + name + "`：" + note);
                }
                if (t.columns.isEmpty()) {
                    continue;      // 为什么不可导出，上面那条说明已经讲了
                }
                work.add(ofParsed(t, upper, usedTables));
            }
        }
        res.total = work.size();
        if (work.isEmpty()) {
            sink.log("! [WARN] 没有可导出的表：这些脚本里解析不出带列定义的 CREATE TABLE");
            res.cancelled = sink.cancelled();
            return res;
        }
        if (sink.cancelled()) {
            res.cancelled = true;
            return res;
        }
        // 带 sql_ 前缀：与「Excel 导入」页那两颗按钮各写各的目录，两边互不覆盖也不必互等
        MetaExport.Outcome oc = MetaExport.exportTables(work, outRoot, withMeta, sink,
                MetaExport.SQL_DIR_TAG);
        res.dir = oc.dir;
        res.written = oc.written.size();
        res.cancelled = oc.cancelled;
        return res;
    }

    /** 解析结果 -> meta_export 的入参：脚本里没有数据行，rows 留空，数据 Sheet 只剩表头。 */
    static MetaExport.Source ofParsed(SqlScript.ParsedTable t) {
        return ofParsed(t, false, null);
    }

    /** 第二页的大小写开关只作用在生成物上；不勾选时脚本里的名字原样照搬。 */
    static MetaExport.Source ofParsed(SqlScript.ParsedTable t, boolean upper,
                                      Map<String, Integer> usedTables) {
        TableOps.Table table = new TableOps.Table();
        table.tableComment = t.tableComment;
        if (!upper) {
            table.tableName = t.tableName;
            table.baseTableName = t.tableName;
            table.columns = t.columns;
        } else {
            table.tableName = Names.dedupField(usedTables,
                    t.tableName.toUpperCase(java.util.Locale.ROOT));
            table.baseTableName = table.tableName;
            Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            for (Infer.Column c : t.columns) {
                Infer.Column nc = new Infer.Column();
                nc.field = Names.dedupField(seen,
                        c.field.toUpperCase(java.util.Locale.ROOT));
                nc.comment = c.comment;
                nc.type = c.type;
                nc.primaryKey = c.primaryKey;
                nc.nullable = c.nullable;
                cols.add(nc);
            }
            table.columns = cols;
        }
        MetaExport.Source s = new MetaExport.Source();
        s.table = table;
        return s;
    }

    /** 生成收尾：日志与弹窗双写同一句话，目录名原样给出来。 */
    private String finishGenerate(GenResult res) {
        if (res == null) {
            return "";
        }
        int n = res.written;
        String note = res.cancelled ? "（已取消，剩余文件未生成）" : "";
        String target = res.dir == null ? "（没建目录）" : res.dir;
        String text = n > 0
                ? "已生成 " + n + " 个文件（共 " + res.total + " 张表）。" + LogText.NL
                        + "目录：" + LogText.NL + target + note
                : "没有生成任何文件，详见日志。" + note;
        String summary = "===== 生成完成：" + n + "/" + res.total + " 个文件，目录 "
                + target + " =====" + note;
        if (n == 0) {
            error(summary);
        } else {
            log(summary);
        }
        host.inform2("生成结果", text);
        return summary;
    }

    /** 自检与双端对撞用：这两颗按钮的状态与一次点击的落点。 */
    public JButton genStdButton() {
        return genStd;
    }

    public JButton genSimpleButton() {
        return genSimple;
    }

}
