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

        /**
         * 顶部「数据源连接信息」那颗库下拉里此刻有哪些库。
         *
         * <p>这一页的目标库不再让人凭记忆手打：清单从哪儿来、这儿就从哪儿来，
         * 免得一处写着 <code>demo</code>、另一处其实还空着。
         */
        java.util.List<String> databaseChoices();

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
    /**
     * MySQL 目标库：候选项就是顶部「数据源连接信息」那颗库下拉里的清单，
     * 默认选中当前目标库；仍可手填（服务器上刚建的库还没重加载时用得上）。
     */
    private final javax.swing.JComboBox<String> targetDbCombo =
            new javax.swing.JComboBox<String>();
    private final JButton connectBtn = new JButton("连达梦并列表");
    private final JButton genBtn = new JButton("生成 MySQL DDL");
    private final JButton saveBtn = new JButton("保存到 .sql");
    private final JButton writeBtn = new JButton("写入 MySQL 目标库");
    private final JCheckBox dropExisting = new JCheckBox("先 DROP 已存在表", false);
    private final JCheckBox lowerNames = new JCheckBox("表名与字段名转小写", true);
    private final JLabel listLabel = new JLabel("未连接达梦");
    /** 驱动在不在，进页就该看得见，不该等点了连接才知道。 */
    private final JLabel driverLabel = new JLabel();
    /** 清单过滤：一个模式几百张表时，只找得到滚动条。 */
    private final JTextField filterBox = new JTextField(14);
    private final JButton clearFilterBtn = new JButton("清空过滤");
    /**
     * 日志框右上角那颗清扫键：读结构、生成、写库都会往日志里垒行，
     * 只清这一页的记录，清单、DDL 正文与已保存的文件一概不动
     * （与第一、二、三、四页同一位置、同一口径）。
     */
    private final JButton clearLogBtn = new JButton("清除日志");
    /** 清单 / 正文、正文 / 日志两条分隔条：日志段默认压在最小行数，拖它才放大（自检量高度用）。 */
    private javax.swing.JSplitPane bodySplit;
    private javax.swing.JSplitPane logSplit;
    /** 视图第 n 行对应 tables 里的第几条：过滤后选中范围要靠它换算。 */
    private final List<Integer> shownRows = new ArrayList<Integer>();
    /** 结构是从哪个模式读的、读回之后连接参数又改过没有。 */
    private String loadedFrom = "";
    /** 连上时读到的服务端版本：只在摘要末尾说一句，不占第二行。 */
    private String serverVersion = "";
    private boolean stale;
    /** 上一回自动跟随的目标库：只有它还等于当前值时才继续跟，用户自己挑过就不再插手。 */
    private String followedTarget = "";
    /** 上一次生成真正含哪些表：写入前的确认要把表名列给它看。 */
    private final List<String> lastTables = new ArrayList<String>();
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

    /**
     * 写入 MySQL 前是否弹一句确认。勾了「先 DROP 已存在表」时这是不可逆动作，
     * 默认要确认；自检跑的是这同一个入口，模态框会把事件分发线程钉住，
     * 所以留类级开关由自检置 false（与 MainFrame.modalProgress 同一处理）。
     */
    public static boolean confirmWrite = true;

    public DmPage(Host host) {
        this.host = host;
        Buttons.secondary(genBtn);
        Buttons.secondary(saveBtn);
        Buttons.secondary(writeBtn);
        setLayout(new BorderLayout(0, UiScale.px(6)));
        add(buildTop(), BorderLayout.NORTH);
        Tables.style(table);
        // 三段上下排，段间各一条 6px 可拖分隔条：清单 / DDL 正文 / 日志。
        // 日志段与正文段都只在自己的最小高度起步，余量全给清单——日志一行没写时
        // 不会白占下半屏（用户报「日志撑满整个工具」）；要读长日志就把分隔条往上拖，
        // 与第一、二、三、四页同一口径。
        JPanel listBox = new JPanel(new BorderLayout());
        listBox.add(new JScrollPane(table), BorderLayout.CENTER);
        listBox.add(buildListHead(), BorderLayout.SOUTH);
        logSplit = logBodySplit(new JScrollPane(output), buildLogBox());
        bodySplit = logBodySplit(listBox, logSplit);
        add(bodySplit, BorderLayout.CENTER);
        connectBtn.addActionListener(e -> connectAndList());
        genBtn.addActionListener(e -> generate());
        saveBtn.addActionListener(e -> chooseSavePath());
        writeBtn.addActionListener(e -> writeToMysql());
        output.setEditable(false);
        output.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN,
                UiScale.fontPt(UiScale.UI_POINT_SIZE)));
        table.getSelectionModel().addListSelectionListener(e -> refreshSummary());
        javax.swing.event.DocumentListener onFilter = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refill(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refill(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refill(); }
        };
        filterBox.getDocument().addDocumentListener(onFilter);
        // 连接参数一改，手上这份结构就跟当前输入对不上了：这一格是达梦那边的口径，
        // 改完不重连就拿旧结构生成，最容易出的事是模式换了、表名却还是上一本的
        javax.swing.event.DocumentListener onParams = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { markStale(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { markStale(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { markStale(); }
        };
        hostField.getDocument().addDocumentListener(onParams);
        portField.getDocument().addDocumentListener(onParams);
        userField.getDocument().addDocumentListener(onParams);
        schemaField.getDocument().addDocumentListener(onParams);
        paramField.getDocument().addDocumentListener(onParams);
        refreshDriverLabel();
        refreshSummary();
        refreshButtonStates();
        // 进页就有个默认值：当前目标库。库清单还没加载出来时它就是 conn.ini 里写的那本，
        // 等连接栏加载完库，MainFrame 会再喊一次 setDatabaseChoices 把候选补齐
        String initDb = defaultTargetDb();
        if (!initDb.isEmpty()) {
            setTargetDb(initDb);
        }
        setDatabaseChoices(host.databaseChoices());
    }

    /** 驱动那一格的话：找到了报文件名，没找到就把放哪儿说全。 */
    private void refreshDriverLabel() {
        if (DmReader.driverPresent()) {
            driverLabel.setText("达梦驱动：" + DmReader.driverJars().size() + " 个");
            driverLabel.setForeground(new java.awt.Color(0x2e, 0x7d, 0x32));
        } else {
            driverLabel.setText("缺达梦驱动 → 放 " + DmReader.driverDir().getPath());
            driverLabel.setForeground(new java.awt.Color(0xb3, 0x26, 0x1d));
        }
        driverLabel.setToolTipText("<html>" + DmReader.describeDrivers() + "<br>"
                + DmReader.missingDriverHint().replace("<", "&lt;") + "</html>");
    }

    private void markStale() {
        // 判据是"手上有结构、参数又改了"，不能挂在地基 loadedFrom 上：
        // 模式名没记住（自检直灌结构）时同样该拦，否则改了参数照拿旧结构生成
        if (!tables.isEmpty() && !stale) {
            stale = true;
            refreshSummary();
        }
    }

    /** 清单那一行的摘要：总数 / 显示数 / 选中数 / 结构是不是当前参数读的那一份。 */
    private void refreshSummary() {
        StringBuilder sb = new StringBuilder();
        if (stale) {
            sb.append("⚠ 结构读自 ").append(loadedFrom).append("，连接参数已改动 —— 先重连；");
        }
        if (tables.isEmpty()) {
            sb.append("未连接达梦");
        } else {
            sb.append("已读回 ").append(tables.size()).append(" 张");
            if (!loadedFrom.isEmpty()) {
                sb.append("（模式 ").append(loadedFrom).append("）");
            }
            if (!serverVersion.isEmpty()) {
                sb.append("｜").append(serverVersion);
            }
            if (shownRows.size() != tables.size()) {
                sb.append("｜显示 ").append(shownRows.size());
            }
            sb.append("｜选中 ").append(table.getSelectedRowCount()).append(" 张");
        }
        listLabel.setText(sb.toString());
        listLabel.setToolTipText(stale
                ? "连接参数与读回结构时用的那一份不一致了：点「连达梦并列表」重连，生成会先被拦下"
                : null);
    }

    /** 按当前过滤条件重铺清单，并记住视图行对应的是第几张表。 */
    private void refill() {
        model.setRowCount(0);
        shownRows.clear();
        String kw = filterBox.getText() == null ? "" : filterBox.getText().trim().toLowerCase(
                java.util.Locale.ROOT);
        for (int i = 0; i < tables.size(); i++) {
            DmMysql.Table t = tables.get(i);
            String hay = (t.name + " " + t.comment).toLowerCase(java.util.Locale.ROOT);
            if (!kw.isEmpty() && !hay.contains(kw)) {
                continue;
            }
            shownRows.add(Integer.valueOf(i));
            model.addRow(new Object[]{t.name, t.comment, Integer.valueOf(t.columns.size()),
                    joinNames(t.primaryKeys)});
        }
        refreshSummary();
    }

    private void clearFilter() {
        boolean dirty = !filterBox.getText().isEmpty();
        filterBox.setText("");
        refill();
        logPanel.log("- [INFO] DM | " + (dirty ? "已清空过滤，清单回到 " : "过滤本来就是空的，仍是 ")
                + tables.size() + " 张表");
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
        row.add(driverLabel);
        p.add(row, BorderLayout.CENTER);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        row2.add(new JLabel("MySQL 目标库"));
        targetDbCombo.setEditable(true);
        targetDbCombo.setPreferredSize(new java.awt.Dimension(UiScale.px(170), UiScale.px(24)));
        targetDbCombo.setToolTipText("生成的脚本开头写 USE 这一本库；候选项就是上方「数据源连接信息」"
                + "那颗库下拉里的清单，默认选中当前目标库；留空则不写 USE，"
                + "「写入 MySQL 目标库」按顶部连接栏所选的库执行");
        row2.add(targetDbCombo);
        row2.add(dropExisting);
        row2.add(lowerNames);
        row2.add(genBtn);
        row2.add(saveBtn);
        row2.add(writeBtn);
        p.add(row2, BorderLayout.SOUTH);
        return p;
    }

    /** 清单下面那一行：摘要 + 过滤 + 清空过滤。 */
    private JPanel buildListHead() {
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        head.add(listLabel);
        filterBox.setToolTipText("<html>按表名与表注释即时过滤清单；"
                + "过滤只影响看得见的行，生成范围仍按选中的算。</html>");
        head.add(new JLabel("过滤"));
        head.add(filterBox);
        Buttons.secondary(clearFilterBtn);
        clearFilterBtn.addActionListener(e -> clearFilter());
        head.add(clearFilterBtn);
        return head;
    }

    /** 日志段：上面一排只有右对齐的「清除日志」，下面才是文本框，与其余四页同一排布。 */
    private JPanel buildLogBox() {
        clearLogBtn.setToolTipText("清空这一页的日志，清单、DDL 正文与已保存的文件都不动");
        clearLogBtn.addActionListener(e -> logPanel.clearLog());
        JPanel logHead = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(6), UiScale.px(2)));
        logHead.add(clearLogBtn);
        // 套滚动条且不写死高度：日志段默认就压在它自己的最小行数（4 行）上，
        // 不再像之前那样固定占 160px——一行没写也白占版面，用户报「日志撑满整个工具」。
        JScrollPane logScroll = new JScrollPane(logPanel);
        logScroll.setMinimumSize(new java.awt.Dimension(UiScale.px(300),
                logPanel.getMinimumSize().height));
        // 视口白底、日志浅灰底：日志行不满一框时下面那截白底看着像一大块空白面板
        logScroll.getViewport().setBackground(logPanel.getBackground());
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.add(logHead, BorderLayout.NORTH);
        logBox.add(logScroll, BorderLayout.CENTER);
        return logBox;
    }

    /** 上下两段的可拖分隔条：余量全给上段，下段只占它自己的最小高度（日志不抢地方）。 */
    private static javax.swing.JSplitPane logBodySplit(java.awt.Component top,
                                                       java.awt.Component bottom) {
        javax.swing.JSplitPane sp = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, top, bottom);
        sp.setDividerSize(UiScale.px(6));
        sp.setBorder(null);
        sp.setResizeWeight(1.0);
        return sp;
    }

    private void refreshButtonStates() {
        genBtn.setEnabled(!tables.isEmpty());
        saveBtn.setEnabled(lastOut != null && lastOut.tables > 0);
        writeBtn.setEnabled(lastOut != null && lastOut.tables > 0);
    }

    /** 目标库此刻的值：可编辑下拉以编辑框内容为准（用户可能刚手填了一本服务器上刚建的库）。 */
    private String targetDb() {
        Object v = targetDbCombo.getEditor().getItem();
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty()) {
            Object sel = targetDbCombo.getSelectedItem();
            s = sel == null ? "" : String.valueOf(sel).trim();
        }
        return s;
    }

    private void setTargetDb(String db) {
        String d = db == null ? "" : db.trim();
        if (d.isEmpty()) {
            targetDbCombo.setSelectedIndex(-1);
            targetDbCombo.getEditor().setItem("");
            return;
        }
        for (int i = 0; i < targetDbCombo.getItemCount(); i++) {
            if (d.equals(targetDbCombo.getItemAt(i))) {
                targetDbCombo.setSelectedIndex(i);
                // 可编辑下拉：模型选中项与编辑框里的字是两份，程序改的那一份要同步过去，
                // 否则 targetDb() 读回来还是上一次的字（自检里就撞到过这一步）
                targetDbCombo.getEditor().setItem(d);
                return;
            }
        }
        // 清单里没有（库刚建、或用户手填）：补一项再选，别把它丢了
        targetDbCombo.addItem(d);
        targetDbCombo.setSelectedItem(d);
        targetDbCombo.getEditor().setItem(d);
    }

    /**
     * 目标库候选项＝顶部「数据源连接信息」那颗库下拉里的清单，默认选中当前目标库。
     *
     * <p>清单没变就整段返回：连库、重连会反复走到这里，真去重建一遍候选的话，
     * 用户已经挑好（或手填）的库会被打回默认值。
     */
    public void setDatabaseChoices(List<String> dbs) {
        List<String> want = new ArrayList<String>();
        if (dbs != null) {
            for (String d : dbs) {
                if (d != null && !d.trim().isEmpty() && !want.contains(d.trim())) {
                    want.add(d.trim());
                }
            }
        }
        List<String> now = new ArrayList<String>();
        for (int i = 0; i < targetDbCombo.getItemCount(); i++) {
            now.add(targetDbCombo.getItemAt(i));
        }
        if (now.equals(want)) {
            return;
        }
        String keep = targetDb();
        String pick = keep.isEmpty() ? defaultTargetDb() : keep;
        if (pick.isEmpty() && !want.isEmpty()) {
            pick = want.get(0);
        }
        if (want.isEmpty() && pick.isEmpty()) {
            return;     // 清单还没加载出来、也没有当前目标库：留着上一次的候选
        }
        targetDbCombo.removeAllItems();
        for (String d : want) {
            targetDbCombo.addItem(d);
        }
        if (!pick.isEmpty()) {
            setTargetDb(pick);
        }
    }

    /** 默认值＝当前目标库（顶部连接栏选中的那本；还没加载库时就是 conn.ini 里写的）。 */
    private String defaultTargetDb() {
        Config cfg = host.currentConfig();
        return cfg == null || cfg.database == null ? "" : cfg.database.trim();
    }

    /**
     * 连接栏换了库时调这里：候选跟着换，值在"用户没自己动过"的前提下跟着当前目标库走。
     *
     * <p>跟进与不跟进的分界在 {@link #followedTarget}：上一回是我们替用户选的那本，
     * 就继续跟；用户自己挑过（或手填过）另一本，就不再动他的选择——这一页允许写进
     * 跟顶部不一样的库，日志会点出来。
     */
    public void syncTargetDb(List<String> dbs, String current) {
        String before = targetDb();
        setDatabaseChoices(dbs);
        String db = current == null ? "" : current.trim();
        if (db.isEmpty()) {
            return;
        }
        if (before.isEmpty() || before.equals(followedTarget)) {
            setTargetDb(db);
            followedTarget = db;
        }
    }

    /** 自检与对撞读这一格用：值、候选条数。 */
    public String targetDbText() {
        return targetDb();
    }

    public int targetDbChoiceCount() {
        return targetDbCombo.getItemCount();
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

    /** 「清除日志」那一颗：自检按用户的方式点它，不直接调 clearLog。 */
    public JButton clearLogButton() {
        return clearLogBtn;
    }

    /** 正文 / 日志那条分隔条：自检量日志段是不是压在最小高度上。 */
    public javax.swing.JSplitPane logSplit() {
        return logSplit;
    }

    /** 自检与探针读这几样：驱动那一格、清单摘要、过滤与脏标记。 */
    public String driverText() {
        return driverLabel.getText();
    }

    public String summaryText() {
        return listLabel.getText();
    }

    public void filterForSelftest(String v) {
        filterBox.setText(v == null ? "" : v);
    }

    public void clearFilterForSelftest() {
        clearFilter();
    }

    /** 视图第 view 行对应读回清单里的第几张（没过滤时等于 view 本身）。 */
    public int tableIndexAtRow(int view) {
        return view >= 0 && view < shownRows.size() ? shownRows.get(view).intValue() : -1;
    }

    public boolean structureIsStale() {
        return stale;
    }

    /** 写入确认的文案（自检不开弹框，判这句话本身）。 */
    public String writeConfirmPreview() {
        return previewNames(lastTables);
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
        tables.addAll(read);
        loadedFrom = schemaField.getText().trim();
        serverVersion = version == null ? "" : version;
        stale = false;
        refill();
        refreshDriverLabel();
        logPanel.log("+ [OK] DM | 读回 " + tables.size() + " 张表结构（模式 " + loadedFrom
                + (serverVersion.isEmpty() ? "" : " | " + serverVersion)
                + "），选中行点「生成 MySQL DDL」；一行都不选则按全部 " + tables.size() + " 张生成");
        if (targetDb().isEmpty()) {
            Config mysql = host.currentConfig();
            if (mysql != null && mysql.database != null) {
                setTargetDb(mysql.database.trim());
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
        if (stale) {
            logPanel.error("x [ERROR] DM-DDL | 已拦下生成：手上这份结构读自模式 "
                    + loadedFrom + "，而连接参数已经改过。先点「连达梦并列表」重连，"
                    + "不然生成的是上一个模式的表");
            host.warn("结构读自模式 " + loadedFrom + "，连接参数随后改过："
                    + "先点「连达梦并列表」重连再生成。");
            return null;
        }
        List<DmMysql.Table> picked = selectedTables();
        if (picked.isEmpty()) {
            host.warn("清单里选中的行一张表都没有，没有生成任何语句。");
            return null;
        }
        DmMysql.Output out = DmMysql.script(picked, targetDb(),
                dropExisting.isSelected(), lowerNames.isSelected());
        lastOut = out;
        lastTables.clear();
        for (DmMysql.Table one : picked) {
            lastTables.add(one.name);
        }
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
            // 清单可能正被过滤着：视图行第 3 行未必是 tables 里的第 3 条
            if (row >= 0 && row < shownRows.size()) {
                out.add(tables.get(shownRows.get(row).intValue()));
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

    /**
     * 写入前那一句确认。
     *
     * <p>勾了「先 DROP 已存在表」时这一步不可恢复，把要动的表名先列出来再点；
     * 自检跑的是同一个入口，靠 {@link #confirmWrite} 关掉弹框，只判文案与条件。
     */
    private boolean askWriteConfirm(Config cfg) {
        String db = targetDb();
        if (db.isEmpty()) {
            db = cfg.database == null ? "" : cfg.database.trim();
        }
        StringBuilder msg = new StringBuilder("<html>将在 MySQL 库 <b>").append(escHtml(db))
                .append("</b> 执行 ").append(lastOut.tables).append(" 张表的建表语句");
        if (dropExisting.isSelected()) {
            msg.append("，并先 DROP 同名表（<b>不可恢复</b>）");
        }
        msg.append("。<br>涉及表：").append(escHtml(previewNames(lastTables)))
                .append("<br><br>确认执行？</html>");
        int pick = javax.swing.JOptionPane.showConfirmDialog(this, msg.toString(),
                "确认写入 MySQL", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                dropExisting.isSelected() ? javax.swing.JOptionPane.WARNING_MESSAGE
                        : javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (pick != javax.swing.JOptionPane.OK_OPTION) {
            logPanel.log("- [INFO] DM-EXEC | 已取消写入，库 " + db + " 一条语句都没执行");
            return false;
        }
        return true;
    }

    private static String previewNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() && i < 6; i++) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(names.get(i));
        }
        if (names.size() > 6) {
            sb.append(" 等 ").append(names.size()).append(" 张");
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    private static String escHtml(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 默认文件名带模式与时间：同一模式跑两次不该把上一份盖掉。 */
    public String suggestedSaveName() {
        String sch = loadedFrom.isEmpty() ? schemaField.getText().trim() : loadedFrom;
        sch = sch.replaceAll("[^A-Za-z0-9_]", "");
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                .format(new java.util.Date());
        return "dm_to_mysql" + (sch.isEmpty() ? "" : "_" + sch) + "_" + stamp + ".sql";
    }

    private void chooseSavePath() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setSelectedFile(new File(suggestedSaveName()));
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
        String want = targetDb();
        if (!want.isEmpty() && cfg.database != null && !want.equalsIgnoreCase(cfg.database.trim())) {
            logPanel.log("! [WARN] DM-EXEC | 脚本开头写的是 USE `" + want + "`，与顶部所选库 "
                    + cfg.database + " 不是同一本，执行时会切过去");
        }
        if (stale) {
            host.warn("结构读自模式 " + loadedFrom + " 且参数已改动：先重连、重新生成再写入。");
            return;
        }
        String script = lastOut.sql;
        if (confirmWrite && !askWriteConfirm(cfg)) {
            return;
        }
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
