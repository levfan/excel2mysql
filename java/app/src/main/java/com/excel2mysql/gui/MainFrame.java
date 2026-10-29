package com.excel2mysql.gui;

import com.excel2mysql.core.AppInfo;
import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.ImportRun;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.MetaExport;
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
    /** SQL 执行器左右两栏的字号：与第一页日志同档（LogPanel.DEFAULT_FONT_SIZE），
     *  用户口径是"两页正文一样大"；按屏幕 DPI 折算成物理像素。 */
    private static final int SQL_RUN_FONT_SIZE = LogPanel.DEFAULT_FONT_SIZE;

    private final FileRowModel fileModel = new FileRowModel();
    private final javax.swing.JTable table =
            new EmptyHintTable(fileModel, EmptyHintTable.FILE_HINT) {
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
    private String ddlText = "";
    private final LogPanel logPanel = new LogPanel();
    private JButton preview;
    private JButton connect;
    private JButton importBtn;
    private JButton stopBtn;
    private JButton genStd;
    private JButton genSimple;
    /**
     * 资源位：三页各占自己的位，一个任务只登记一位。
     * 抢同一个位才互斥，不同位可以并行——原来是一个布尔量把三页一起锁住，
     * 执行器跑上千条语句时另外两页连点都点不动。两页的「生成」曾经共用一个
     * 产物目录位，现在各写自己的目录（sql_ 前缀那一档见 MetaExport.SQL_DIR_TAG），
     * 落点不重叠，也就不必再占同一个位。
     */
    public static final String RES_EXCEL = "excel";
    public static final String RES_SQL = "sql";
    public static final String RES_RUN = "run";
    /** 资源位的人话名字：被挡下的那句要说清楚在等的是哪一样东西。 */
    private static final Map<String, String> RES_LABEL =
            new java.util.LinkedHashMap<String, String>();

    static {
        RES_LABEL.put(RES_EXCEL, "Excel 导入页的待导入列表");
        RES_LABEL.put(RES_SQL, "SQL 脚本改名页的脚本列表");
        RES_LABEL.put(RES_RUN, "SQL 执行器页的执行结果区（编辑区 SQL 与脚本清单共用）");
    }

    /**
     * 正在跑的任务，一个任务一个对象：进度、日志去向、进度框都挂在它自己身上。
     * 被挡下时要说得出"跑到几成、跑了多久"，所以这些数不能只存在进度框里——
     * 非模态与原地跑（自检）那条路根本没有框，只靠框里的数就报不出来。
     */
    private final List<Task> tasks = new ArrayList<Task>();
    /**
     * 关掉时任务原地跑完、不弹进度框：自检与非界面调用要靠它验到线上那段代码，
     * 而不是验一份复印件（对应 main.py:137 的 MODAL_PROGRESS）。
     */
    public static boolean modalProgress = true;
    private javax.swing.JTextField hostField;
    private javax.swing.JSpinner portField;
    private javax.swing.JTextField userField;
    private javax.swing.JPasswordField passField;
    private javax.swing.JComboBox<String> dbCombo;
    /** 第一页顶部那句介绍（三页同一块底板，见 PageIntro）。 */
    private javax.swing.JLabel impIntro;
    /** 连接区收起后的那一条状态栏（只报当前库），与「设置数据源信息」按钮一起常驻顶部。 */
    private HintLabel connState;
    private JButton btnConnEdit;
    private JButton btnConnCollapse;
    private JPanel connForm;
    /** 正在重填库列表：这时第一项被自动选上不算"用户选定"，不该触发自动收起。 */
    private boolean dbListReloading;
    private SqlPage sqlPage;
    private ProgressDialog lastTask;
    /** 最近一次请求的任务标题：原地模式不弹框，标题也要留痕才断言得了"用的是 Python 原话"。 */
    private String lastTaskTitle = "";
    /** 当前任务的日志面板：null 表示 Excel 页那块，SQL 页的任务把它指到 sql_log。 */
    private LogPanel taskLogView;
    private String lastDialogTitle = "";
    private String lastDialogText = "";
    private boolean lastDialogIsError;
    private String lastDialogIcon = "information";
    private javax.swing.JTabbedPane tabs;
    /** 第三个页签「SQL 执行器」：编辑区、结果区与那三颗按钮，示例对话框不算常驻控件。 */
    private javax.swing.JTextArea sqlRunInput;
    private LogPanel sqlRunLog;
    /** 结果集用真表格画：文本框里中英文混排必错行，且一长就被自动滚到底看不见数据。 */
    private javax.swing.JTable sqlRunResultTable;
    /** 结果表表头逐列的 title（字段注释），与当前显示的列一一对应；下标按模型列。 */
    private String[] sqlRunHeaderTips = new String[0];
    /** 换结果集只换数据不换表格：换 model 会把字体、行高与斑马纹一起冲掉，得重挂一遍。 */
    private final javax.swing.table.DefaultTableModel sqlRunModel =
            new javax.swing.table.DefaultTableModel(new Object[0][0], new Object[0]) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private javax.swing.JComboBox<String> sqlRunSets;
    private javax.swing.JLabel sqlRunCaption;
    private javax.swing.JSplitPane sqlRunResultSplit;
    private java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> sqlRunSetList =
            new java.util.ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
    private javax.swing.JButton sqlRunBtn;
    private javax.swing.JButton sqlRunClearInputBtn;
    private javax.swing.JButton sqlRunClearLogBtn;
    private javax.swing.JButton sqlRunHelpBtn;
    private javax.swing.JLabel sqlRunHint;
    /** 第三页的脚本文件队列：拖拽区、清单表（含行内【执行】）与清空/批量执行那一排。 */
    private DropArea sqlRunDrop;
    private javax.swing.JTable sqlRunFilesTable;
    private javax.swing.table.DefaultTableModel sqlRunFilesModel;
    private javax.swing.JButton sqlRunFilesClearBtn;
    private javax.swing.JButton sqlRunScriptsBtn;
    /** 「操作」列那颗【执行】：单元格里不放真控件，整列共用这一个渲染件。 */
    private javax.swing.JButton sqlRunRowExec;
    private javax.swing.JPanel sqlRunQueueRow;
    /** 编辑区那一栏的头一行（SQL 文本 + 示例 + 两颗清空 + 执行编辑区 SQL）。 */
    private javax.swing.JPanel sqlRunEditorHead;
    private final List<RunScript> sqlRunFiles = new ArrayList<RunScript>();
    private javax.swing.JFrame sqlRunSamples;
    /** 示例弹窗：左边搜索框 + 两层目录，右边上方介绍、下方语句。 */
    private javax.swing.JTextField sqlRunSamplesSearch;
    private javax.swing.JTree sqlRunSamplesTree;
    private javax.swing.JLabel sqlRunSamplesHits;
    private javax.swing.JTextArea sqlRunIntro;
    private javax.swing.JTextArea sqlRunPreview;
    private javax.swing.JScrollPane sqlRunPreviewScroll;
    private javax.swing.JButton sqlRunCopyBtn;
    private javax.swing.JButton sqlRunInsertBtn;
    private DropArea drop;
    private HintTextField prefixField;
    private javax.swing.JCheckBox initialsBox;
    private javax.swing.JCheckBox upperBox;
    private javax.swing.JCheckBox lowerBox;
    private javax.swing.JCheckBox dropExisting;
    private final List<TableOps.Table> tables = new ArrayList<TableOps.Table>();
    private final Map<TableOps.Table, TableBuilder.Result> byTable =
            new java.util.LinkedHashMap<TableOps.Table, TableBuilder.Result>();
    private Config config;
    private boolean connected;
    /** conn.ini 里写的目标库：库列表加载出来之后按它预选。 */
    private String cfgDb = "";
    /** 有没有一份明确的连接配置（读到 conn.ini 或 E2M_*）；没有就不自动连。 */
    private boolean autoConnectEligible;
    private boolean autoConnectStarted;
    /** 手动加载一次 +1：自动连接的结果回来晚了就作废，不能盖掉用户刚选的库。 */
    private int dbLoadSeq;
    private String zipSessionDir;
    private final Names names = new Names(PinyinLoader.get());
    private final TableBuilder builder = new TableBuilder(new Infer(names), names);

    public MainFrame() {
        // 标题带版本号：不点开「关于」也能看见现场跑的是哪一版
        super(AppInfo.load().titleText());
        fileModel.setRenameHandler((item, requested) -> renameFileTable(item, requested));
        fileModel.setCommentHandler((item, requested) -> commentFileTable(item, requested));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        applyWindowIcon();
        setLocationByPlatform(true);
        setLayout(new BorderLayout());

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildHeaderRow(), BorderLayout.NORTH);
        north.add(buildConnectionBar(), BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        // 三个页签共用上方那条连接栏：口令只有一份，写在页签里会出现"这页连的是哪个库"的歧义
        tabs = new javax.swing.JTabbedPane();
        sqlPage = new SqlPage(this);
        impIntro = PageIntro.of("中英表头建模，回写库");
        tabs.addTab("Excel 导入", buildCenter());
        tabs.addTab("SQL 脚本改名", sqlPage);
        tabs.addTab("SQL 执行器", buildSqlRunPage());
        add(tabs, BorderLayout.CENTER);
        // Ctrl+V 的绑定要放在三页都建完之后：文本控件的改挂是遍历这棵树做的，
        // 早一步遍历只会扑空（那时树里还没有输入框与 SQL 编辑区）
        bindPasteShortcut();
        // 切页也要刷新：顶部状态栏报的是"现在是哪个库"，切页或改库都要跟上新库
        tabs.addChangeListener(e -> refreshDbLabels());
        bindHelpKey();
        refreshDbLabels();
        loadDefaultConn();
        // 窗口真显示出来之后再自动连：构造期间连库（对端不在线要等到超时）会让首屏迟迟不出来
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                autoConnect();
            }
        });
        // Qt 的 resize(1100,780) 说的是客户区，Swing 的 setSize 说的是整个外框：
        // 照抄数字会让这边的内容区比 Python 小掉一条标题栏加边框，
        // 表格与日志就那么被挤矮了。客户区尺寸、屏幕夹取与居中统一走 Windows.clientSize。
        Windows.clientSize(getContentPane(), this, 1100, 780);
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
        JPanel row = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(4), 0));
        java.awt.Font bold = row.getFont().deriveFont(java.awt.Font.BOLD);
        JButton about = new JButton("i");
        about.setFont(bold);
        about.setToolTipText("关于：版本、提交号与构建日期");
        about.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        about.addActionListener(e -> showAbout());
        JButton help = new JButton("?");
        help.setFont(bold);
        help.setToolTipText("使用说明（按 F1 也可打开）");
        help.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        help.addActionListener(e -> showHelp());
        // 26×22 里塞得下字符，但 Windows 外观的按钮默认内边距会把一个字挤成省略号
        JButton[] tiny = {about, help};
        for (JButton b : tiny) {
            b.setMargin(new java.awt.Insets(0, 0, 0, 0));
            b.setPreferredSize(new Dimension(UiScale.px(26), UiScale.px(22)));
            b.setMinimumSize(new Dimension(UiScale.px(26), UiScale.px(22)));
        }
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

    /**
     * 连接区：一条状态栏（只报当前是哪个库）+ 可收起的连接表单。
     *
     * <p>用户口径：跑业务的时候这块环境信息一直常驻展示作用不大，只需要看到库名；
     * 要改连接、测连接、换库时点「设置数据源信息」展开。Python 侧同一形状（main.py 的
     * conn_bar / conn_form），启动即收起（用户口径：默认收起连接数据源信息），选定库后也自动收起。
     * 口令只进 JPasswordField，全程不落文件、不进日志。
     */
    private JPanel buildConnectionBar() {
        JPanel outer = new JPanel(new BorderLayout(0, UiScale.px(2)));

        connState = new HintLabel();
        btnConnEdit = new JButton("设置数据源信息");
        btnConnEdit.setToolTipText("展开填写主机 / 端口 / 用户名 / 密码，并测试连接与选择数据库");
        btnConnEdit.addActionListener(e -> setConnExpanded(true));
        JPanel statusBar = new JPanel(new BorderLayout(UiScale.px(6), 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(
                0, UiScale.px(6), 0, UiScale.px(6)));
        statusBar.add(connState, BorderLayout.CENTER);
        statusBar.add(btnConnEdit, BorderLayout.EAST);
        outer.add(statusBar, BorderLayout.NORTH);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("MySQL 连接信息"),
                BorderFactory.createEmptyBorder(
                        UiScale.px(2), UiScale.px(8), UiScale.px(6), UiScale.px(8))));
        connForm = bar;

        connect = new JButton("连接并加载库");
        connect.addActionListener(e -> {
            String failure = loadDatabases();
            if (failure != null) {
                critical(failure);
            }
        });

        hostField = new javax.swing.JTextField();
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
        portField = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                Integer.valueOf(3306), Integer.valueOf(1), Integer.valueOf(65535),
                Integer.valueOf(1)));
        portField.setPreferredSize(new Dimension(UiScale.px(70), UiScale.px(24)));
        // Qt 的 QSpinBox 不带千分位；JSpinner 默认按本地 NumberFormat 会显示成 3,306
        portField.setEditor(new javax.swing.JSpinner.NumberEditor(portField, "#0"));
        userField = new javax.swing.JTextField(10);
        passField = new javax.swing.JPasswordField(10);
        dbCombo = new javax.swing.JComboBox<String>();
        // 库列表要先连上服务器才拿得到，没加载之前下拉是禁用的：
        // 让用户手填库名，等于把"连到一个不存在的库"推迟到导入那一刻才爆
        dbCombo.setEnabled(false);
        dbCombo.setPreferredSize(new Dimension(UiScale.px(180), UiScale.px(24)));
        dbCombo.addActionListener(e -> {
            refreshDbLabels();
            // 选定库之后环境信息就没用了，自动收起；要换库再点「设置数据源信息」。
            // 重新填列表不算"选定"：Swing 里 addItem 会把第一项选上，那样一加载就收起，
            // 等于把用户下一步要用那颗下拉藏起来（Python 那边是 blockSignals 填的，天然不触发）
            if (!dbListReloading && !selectedDatabase().isEmpty()) {
                setConnExpanded(false);
            }
        });
        hostField.setText("127.0.0.1");

        userField.setText("root");
        hostField.getDocument().addDocumentListener(refresher);
        portField.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            @Override
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                refreshDbLabels();
            }
        });

        JPanel dbRow = new JPanel(new BorderLayout(UiScale.px(6), 0));
        btnConnCollapse = new JButton("收起");
        btnConnCollapse.setToolTipText("收起连接信息，只保留当前库");
        btnConnCollapse.addActionListener(e -> setConnExpanded(false));
        JPanel dbButtons = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(6), 0));
        dbButtons.add(connect);
        dbButtons.add(btnConnCollapse);
        dbRow.add(dbCombo, BorderLayout.CENTER);
        dbRow.add(dbButtons, BorderLayout.EAST);
        // QFormLayout 的等价物：标签一列按内容宽，输入一列吃掉剩余宽度，五行各占一行
        JPanel fields = new JPanel(new java.awt.GridBagLayout());
        addFormRow(fields, 0, "主机：", hostField, true);
        // 端口是数字框，跟着窗口拉宽只会显得更空，Python 那边它也是窄窄一个
        addFormRow(fields, 1, "端口：", portField, false);
        addFormRow(fields, 2, "用户名：", userField, true);
        addFormRow(fields, 3, "密码：", passField, true);
        addFormRow(fields, 4, "目标库：", dbRow, true);

        bar.add(fields, BorderLayout.CENTER);
        outer.add(bar, BorderLayout.CENTER);
        // 启动即收起（用户口径：默认收起连接数据源信息）；要填连接点「设置数据源信息」展开，
        // 选定库之后也自动收起
        setConnExpanded(false);
        return outer;
    }

    /** 展开/收起连接表单：收起后顶部只剩一条"当前是哪个库"。 */
    public void setConnExpanded(boolean expanded) {
        connForm.setVisible(expanded);
        btnConnEdit.setVisible(!expanded);
        refreshDbLabels();
    }

    /**
     * 连接区现在是展开还是收起：自检与对撞器读的是控件自己的可见性标志，
     * 不是 isShowing()。自检那条线建完窗就判，从没把窗口 realize 出来，
     * isShowing() 在一整棵没上屏的树里恒为假，四条断言会一起假红。
     */
    public boolean isConnExpanded() {
        return connForm != null && connForm.isVisible();
    }

    public String connStateText() {
        return connState == null ? "" : connState.hintText();
    }

    /** 「设置数据源信息」那颗此刻显不显示（展开态由「收起」负责，两颗不会同时出现）。 */
    public boolean connEditVisible() {
        return btnConnEdit != null && btnConnEdit.isVisible();
    }

    /** 状态栏那条本体：自检要查它能不能拖选复制。 */
    public HintLabel connState() {
        return connState;
    }

    /** 状态栏的悬停提示：host:port 与用户名收在这里，不再常驻展示。 */
    public String connTip() {
        return connState == null || connState.getToolTipText() == null
                ? "" : connState.getToolTipText();
    }

    /** 连接框的一行：左边固定宽的标签，右边输入件（stretch 为假时保持自己的偏好宽度）。 */
    private static void addFormRow(JPanel row, int y, String label, java.awt.Component field,
                                   boolean stretch) {
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.insets = new java.awt.Insets(UiScale.px(2), 0, UiScale.px(2), UiScale.px(8));
        row.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = stretch ? 1.0 : 0.0;
        c.fill = stretch ? java.awt.GridBagConstraints.HORIZONTAL
                         : java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(UiScale.px(2), 0, UiScale.px(2), 0);
        row.add(field, c);
    }

    private java.awt.Component buildCenter() {
        drop = new DropArea(paths -> {
            List<String> accepted = acceptedDrops(paths);
            if (!accepted.isEmpty()) {
                addFilesAsync(accepted);
            }
        }, this::chooseFiles);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        Tables.style(table);
        // 与第二页同一交互：双击「表名」「表注释」就地改，双击其余列才进结构弹窗。
        // 编辑器显式设成双击起（点一次只选中），免得两页各按各的默认值走
        javax.swing.DefaultCellEditor cellEditor =
                new javax.swing.DefaultCellEditor(new javax.swing.JTextField());
        cellEditor.setClickCountToStart(2);
        table.getColumnModel().getColumn(2).setCellEditor(cellEditor);
        table.getColumnModel().getColumn(3).setCellEditor(cellEditor);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int column = table.columnAtPoint(e.getPoint());
                    // 可改的那两列只进编辑器，不叠一个结构窗
                    if (column != 2 && column != 3) {
                        editStructure();
                    }
                }
            }
        });
        // Del 键删掉选中的行（多选就是一次批量删）。绑在表自己的 WHEN_FOCUSED 上：
        // 焦点在「表名」编辑器里时事件归编辑器先吃，改名字按 Del 删字符不会误删整行。
        table.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0),
                "deleteSelectedFiles");
        table.getActionMap().put("deleteSelectedFiles", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                deleteSelectedFiles();
            }
        });
        int[] widths = {44, 320, 200, 280, 80, 90, 160};
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(UiScale.px(widths[i]));
        }
        Tables.zebra(table);
        // 列宽可自由拖动，最后一列自动吃掉剩余空间（Qt 的 Interactive + stretchLastSection）
        table.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new Dimension(UiScale.px(300), UiScale.px(120)));

        // 「待导入文件」组框里是三样东西：表格、前缀行、四个操作按钮（main.py:893-946）。
        // 这些控件原先全被塞在顶部连接栏里，等于把"改表名的输入框"和"连接数据库"混在一处。
        prefixField = new HintTextField(18, PREFIX_HINT);
        JButton applyPrefix = new JButton("批量应用前缀");
        applyPrefix.addActionListener(e -> applyPrefix());
        JButton replacePrefix = new JButton("替换首个前缀");
        replacePrefix.setToolTipText(REPLACE_HEAD_TIP);
        replacePrefix.addActionListener(e -> replaceFirstPrefix());
        JPanel prefixBtns = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, UiScale.px(6), 0));
        prefixBtns.add(applyPrefix);
        prefixBtns.add(replacePrefix);
        // 命名规则工具条贴表格上沿（与 main.py 同一位置）：它管的是"表名/字段名怎么起"，
        // 与表格内容同组，但不占下面前缀行——前缀行要留给输入框与两颗按钮。
        // 只把首字母勾上（setSelected 不触发 ActionListener，启动时不会凭空打一行"切换"日志），
        // 转大写保持未勾选，由用户按需勾上
        initialsBox = new javax.swing.JCheckBox("表名和字段名取中文拼音首字母");
        initialsBox.setSelected(true);
        initialsBox.setToolTipText("由中文转写的表名与字段名用拼音首字母（订单金额 → ddje、"
                + "水库基础信息 → skjcxx）；默认已勾选，取消则用全拼（ding_dan_jin_e）。"
                + "文件名括号里显式写下的英文表名不受影响；对之后添加的文件生效。");
        initialsBox.addActionListener(e -> log("[提示] 命名规则切换为："
                + (initialsBox.isSelected() ? "拼音首字母" : "全拼")
                + "（对之后添加的文件生效）"));
        upperBox = new javax.swing.JCheckBox("表名与字段名转大写");
        upperBox.setToolTipText("由中文或文件名转写出来的表名与字段名全部大写"
                + "（ding_dan_jin_e → DING_DAN_JIN_E、水库基础信息 → SHUI_KU_JI_CHU_XIN_XI）；"
                + "默认不勾选，勾上才转大写。文件名括号里写下的英文表名同样转大写；"
                + "结构定义 Sheet 里人工写下的字段名也一起转大写，两颗大小写开关都不勾"
                + "则保持写下的原样；与「表名与字段名转小写」互斥，勾这颗会取消那一颗；"
                + "对之后添加的文件生效（表名前缀按输入原样拼接，不跟着转）。");
        upperBox.addActionListener(e -> {
            keepOneCaseSwitch(upperBox, lowerBox);
            String[] st = caseState();
            log("[提示] 表名与字段名大小写切换为：" + st[0]
                    + "（对之后添加的文件生效，结构定义 Sheet 里手写的字段名" + st[1] + "）");
        });
        lowerBox = new javax.swing.JCheckBox("表名与字段名转小写");
        lowerBox.setToolTipText("由中文转写出来的表名与字段名本来就落在小写（ding_dan_jin_e、skjcxx），"
                + "这一档在转写那一路与不勾同结果；文件名括号里写下的英文表名本来也统一转小写。"
                + "这颗真管到的是结构定义 Sheet 里人工手写的字段名：勾上才把它们转成小写"
                + "（ITEM_CODE → item_code），两颗都不勾时一个字母都不动；"
                + "与「表名与字段名转大写」互斥，勾这颗会取消那一颗；"
                + "对之后添加的文件生效（表名前缀按输入原样拼接，不跟着转）。");
        lowerBox.addActionListener(e -> {
            keepOneCaseSwitch(lowerBox, upperBox);
            String[] st = caseState();
            log("[提示] 表名与字段名大小写切换为：" + st[0]
                    + "（对之后添加的文件生效，结构定义 Sheet 里手写的字段名" + st[1] + "）");
        });
        JPanel nameBar = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        nameBar.add(new JLabel("命名规则："));
        nameBar.add(initialsBox);
        nameBar.add(upperBox);
        nameBar.add(lowerBox);
        JPanel prefixEast = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, UiScale.px(6), 0));
        prefixEast.add(prefixBtns);
        JPanel prefixRow = new JPanel(new BorderLayout(UiScale.px(6), 0));
        prefixRow.add(new JLabel("表名前缀："), BorderLayout.WEST);
        prefixRow.add(prefixField, BorderLayout.CENTER);
        prefixRow.add(prefixEast, BorderLayout.EAST);

        JButton clear = new JButton("清空待上传文件");
        clear.addActionListener(e -> clearAll());
        JButton structure = new JButton("生成表结构预览");
        structure.addActionListener(e -> previewStructure());
        preview = new JButton("预览 DDL");
        preview.addActionListener(e -> previewDdl());
        // Qt 的 QHBoxLayout 会把三颗按钮等宽铺满整行，Swing 的 FlowLayout 只会左对齐按内容宽
        JPanel btnRow = new JPanel(new java.awt.GridLayout(
                1, 3, UiScale.px(6), 0));
        btnRow.add(clear);
        btnRow.add(structure);
        btnRow.add(preview);

        JPanel fileRows = new JPanel(new BorderLayout(0, UiScale.px(2)));
        fileRows.add(prefixRow, BorderLayout.NORTH);
        fileRows.add(btnRow, BorderLayout.SOUTH);
        JPanel fileBox = new JPanel(new BorderLayout(0, UiScale.px(2)));
        Tables.titled(fileBox, Tables.FILE_TABLE_TITLE);
        fileBox.add(nameBar, BorderLayout.NORTH);
        fileBox.add(tableScroll, BorderLayout.CENTER);
        fileBox.add(fileRows, BorderLayout.SOUTH);

        // 日志区：上面一排只有右对齐的「清除日志」，下面才是文本框（main.py:949-960）
        JButton clearLog = new JButton("清除日志");
        clearLog.addActionListener(e -> logPanel.clearLog());
        JPanel logHead = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(6), UiScale.px(2)));
        logHead.add(clearLog);
        JScrollPane logScroll = new JScrollPane(logPanel);
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.add(logHead, BorderLayout.NORTH);
        logBox.add(logScroll, BorderLayout.CENTER);

        // 三段纵向分栏：拖拽区 / 文件组框 / 日志，段数与比例照 main.py 的 110:360:200。
        // Swing 没有 setChildrenCollapsible(false)，用最小尺寸顶住——拖到 0 高那段就等于没了
        drop.setMinimumSize(new Dimension(UiScale.px(10), UiScale.px(60)));
        fileBox.setMinimumSize(new Dimension(UiScale.px(300), UiScale.px(150)));
        logBox.setMinimumSize(new Dimension(UiScale.px(300),
                logPanel.getMinimumSize().height + UiScale.px(34)));
        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fileBox, logBox);
        lower.setDividerSize(UiScale.px(6));
        // Qt 的 stretchFactor 0/3/2：拖拽区不参与拉伸，多出来的高度由文件表拿大头
        lower.setResizeWeight(0.6);
        JSplitPane stacked = new JSplitPane(JSplitPane.VERTICAL_SPLIT, drop, lower);
        stacked.setDividerSize(UiScale.px(6));
        stacked.setResizeWeight(0.0);
        stacked.setDividerLocation(UiScale.px(110));
        lower.setDividerLocation(UiScale.px(360));
        excelSplit = stacked;
        excelLower = lower;
        excelDrop = drop;
        excelFileBox = fileBox;
        excelLogBox = logBox;
        // Qt 的 setSizes([110,360,200]) 会按可用高度等比缩放，JSplitPane 只认绝对值：
        // 真拿到尺寸后按 110:670 摆拖拽区，剩下的高度里日志只留它自己的最小高度。
        // 两条分隔条都要监听 Resize：内层高度变了，日志段也要重新落回最小高度。
        java.awt.event.ComponentAdapter placer =
                new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent e) {
                        applySplitProportions();
                    }
                };
        stacked.addComponentListener(placer);
        lower.addComponentListener(placer);
        watchDivider(stacked, false);
        watchDivider(lower, false);

        // 底部动作条：左列是执行选项与页内进度，右侧才是动作按钮（main.py:976-994）。
        // 主按钮用强调色，停止与两颗生成按钮保持普通样式，一屏里只留一个视觉重点
        dropExisting = new javax.swing.JCheckBox("覆盖已存在表（先 DROP 再建）");
        dropExisting.addActionListener(e -> refreshPreview());
        importBtn = new JButton("开始导入");
        importBtn.setEnabled(false);
        Buttons.primary(importBtn);
        importBtn.addActionListener(e -> startImport());
        // 页面级「停止」：Python 的进度框根本没有 X，中止就靠这个按钮
        stopBtn = new JButton("停止");
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(e -> stopTask());
        // 两个生成按钮排在「开始导入」右侧：它们和导入用的是同一份已解析表定义，
        // 放在同一排才看得出"这是同一件事的另一种出口"，而不是另一功能
        genStd = new JButton("生成标准excel文件");
        // Qt 的 tooltip 认 "\n"，Swing 只认 HTML 换行，所以话术原样、换行换个写法
        genStd.setToolTipText("<html>在程序同级建 meta_data_file_年月日 目录，每张表一个 xlsx：<br>"
                + "Sheet1「元数据」是建表用的字段定义，Sheet2「数据」是数据行</html>");
        genStd.setEnabled(false);
        genStd.addActionListener(e -> generateExcel(true));
        genSimple = new JButton("生成简易excel文件");
        genSimple.setToolTipText("<html>在程序同级建 simple_meta_data_file_年月日 目录，"
                + "每张表一个 xlsx：<br>只留「数据」一个 Sheet</html>");
        genSimple.setEnabled(false);
        genSimple.addActionListener(e -> generateExcel(false));
        JPanel opRight = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(6), UiScale.px(2)));
        opRight.add(stopBtn);
        opRight.add(importBtn);
        opRight.add(genStd);
        opRight.add(genSimple);

        // 页内进度条已经拿掉：导入弹的是那个非模态进度框（每张表 + 每行数据都计项），
        // 同一条进度不在窗口里再摆一份；而解析 / 前缀 / 生成 / SQL 页任务期间
        // 这条恒为 0 的杠只会让人以为界面坏了，第二、三页本来也没有它。
        dropExisting.setAlignmentX(0f);
        dropExisting.setMaximumSize(new Dimension(
                Integer.MAX_VALUE, dropExisting.getPreferredSize().height));
        opRight.setOpaque(false);
        excelActionBar = new ActionBar(new BorderLayout());
        JPanel op = excelActionBar;
        op.add(dropExisting, BorderLayout.CENTER);
        op.add(opRight, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout(0, UiScale.px(2)));
        // 执行层与上方表格区之间的细分隔线：底部动作条不再和表格内容糊成一片
        south.add(new javax.swing.JSeparator(), BorderLayout.NORTH);
        south.add(op, BorderLayout.CENTER);

        // 页级纵向间距取第三页那一档（Python 侧 QVBoxLayout 的默认 spacing 同样是 6）：
        // 介绍条与拖拽区之间留出同一段空白；页签内容最上面只留那句介绍——
        // 当前库只在顶部状态栏说一次，页内不再各写一条
        JPanel page = new JPanel(new BorderLayout(0, UiScale.px(6)));
        page.add(impIntro, BorderLayout.NORTH);
        page.add(stacked, BorderLayout.CENTER);
        page.add(south, BorderLayout.SOUTH);
        return page;
    }

    /** 界面上的连接信息拼成一份 Config；口令只在这一步离开输入框，不落文件。 */
    public Config currentConfig() {
        Config cfg = new Config();
        cfg.host = hostField.getText().trim();
        cfg.port = ((Number) portField.getValue()).intValue();
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
     * 重新填库列表。填表本身不算"选定目标库"：`removeAllItems` + `addItem` 会把第一项
     * 自动选上，那不该顺手把连接区收起来（用户下一步就是在这颗下拉里挑库）。
     * Python 那边靠 `blockSignals` 得到同一结果（main.py 的 on_load_databases）。
     */
    public void reloadDatabases(List<String> dbs) {
        dbListReloading = true;
        try {
            dbCombo.removeAllItems();
            for (String d : dbs) {
                dbCombo.addItem(d);
            }
        } finally {
            dbListReloading = false;
        }
        dbCombo.setEnabled(true);
    }

    /**
     * 自检用：替换"连库取列表"这一步，闸门里不用真连一台不存在的服务器。留 null 就是真连。
     * 与 connCandidates 同一类测试缝——只换掉外部依赖，界面与日志仍走生产那段代码。
     */
    public static java.util.concurrent.Callable<List<String>> fetchOverride;

    private static List<String> fetchDatabases(Config cfg) throws Exception {
        if (fetchOverride != null) {
            return fetchOverride.call();
        }
        // 5 秒握手超时：自动连接在后台跑，关闭窗口时也不能被一个不在线的地址拖住
        DbClient client = new DbClient(cfg, 5000);
        try {
            return client.listDatabases();
        } finally {
            client.close();
        }
    }

    /**
     * 把库列表灌进下拉框并选中配置里的目标库；手动加载与启动自动连接共用。
     * 与 Python 的 _apply_databases 同语义，含"配置的库不在服务器上"那句解释。
     */
    public void applyDatabases(Config cfg, List<String> dbs) {
        String previous = selectedDatabase();
        reloadDatabases(dbs);
        if (!previous.isEmpty() && dbs.contains(previous)) {
            dbCombo.setSelectedItem(previous);
        } else if (!cfgDb.isEmpty() && dbs.contains(cfgDb)) {
            // 连接信息是 conn.ini 预填的，库列表出来就顺手选上，别再让人点一遍
            dbCombo.setSelectedItem(cfgDb);
            log("[已加载] 目标库按 conn.ini 选中：" + cfgDb);
        } else if (!cfgDb.isEmpty()) {
            // 说了要选却没选上，必须解释：否则用户以为配置没生效，回头改配置文件
            log("[跳过] conn.ini 指定的目标库 " + cfgDb + " 不在服务器上，请手动选择");
        }
        config = cfg;
        connected = true;
        importBtn.setEnabled(!tables.isEmpty());
        log("[已加载] 可用数据库：" + String.join(", ", dbs));
        refreshDbLabels();
    }

    /**
     * 启动自动连接：读到 conn.ini 或 E2M_* 时，窗口出来后自己连一次并选库。
     *
     * 失败只写日志、不弹模态框——启动就弹"连接失败"会让人以为软件打不开；
     * 顶部状态栏本来就保持红色"未选择目标库"，点「设置数据源信息」可重试。
     * 自检（modalProgress=false）里与其它任务一样原地跑完，不起线程。
     */
    public void autoConnect() {
        if (autoConnectStarted || !autoConnectEligible) {
            return;
        }
        autoConnectStarted = true;
        final Config cfg = currentConfig();
        final int seq = ++dbLoadSeq;
        log("[连接中] 启动自动连接 " + cfg.host + ":" + cfg.port + "（用户 " + cfg.user + "）…");
        if (!modalProgress) {
            try {
                applyDatabases(cfg, fetchDatabases(cfg));
            } catch (Exception e) {
                autoConnectFailed(cfg, e);
            }
            return;
        }
        new javax.swing.SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return fetchDatabases(cfg);
            }

            @Override
            protected void done() {
                if (seq != dbLoadSeq) {
                    return;         // 手动加载已经接手，这份迟到的结果作废
                }
                try {
                    applyDatabases(cfg, get());
                } catch (Exception e) {
                    autoConnectFailed(cfg, e);
                }
            }
        }.execute();
    }

    private void autoConnectFailed(Config cfg, Exception e) {
        String detail = LogText.describe(e);
        logError("[连接失败] 启动自动连接失败：无法连接到 " + cfg.host + ":" + cfg.port
                + "（用户 " + cfg.user + "），库列表未加载；可点「设置数据源信息」重试"
                + LogText.NL + LogText.indentDetail(detail));
        refreshDbLabels();
    }

    /**
     * 连接服务器并列出库。返回 null 表示成功；非 null 是弹窗正文，
     * 与日志用的是同一份展开文案（对应 main.py 的 on_load_databases）。
     */
    public String loadDatabases() {
        Config cfg = currentConfig();
        dbLoadSeq++;                     // 还在路上的自动连接结果就此过期
        try {
            applyDatabases(cfg, fetchDatabases(cfg));
            return null;
        } catch (Exception e) {
            connected = false;
            dbCombo.setEnabled(false);
            String detail = LogText.describe(e);
            logError("[连接失败] 无法连接到 " + cfg.host + ":" + cfg.port + "（用户 "
                    + cfg.user + "），库列表未加载" + LogText.NL + LogText.indentDetail(detail));
            refreshDbLabels();
            return detail;
        }
    }

    /**
     * 状态栏与三页顶部那条指向同源刷新：状态栏只报"现在是哪个库"（host:port 进悬停提示），
     * 三条指向各说这一页要写到哪。各写一份的话，用户会在三个页签看到三个"当前库"。
     */
    public void refreshDbLabels() {
        if (connState == null) {
            return;     // 连接栏比页签里的提示条先建，输入框赋初值时这里还不能动它们
        }
        String db = selectedDatabase();
        if (db.isEmpty()) {
            // 告警条的说法要看当前是展开还是收起：让人去点的那颗按钮必须真的在界面上
            connState.setBad(isConnExpanded()
                    ? "⚠ 未选择目标库——填好下面连接信息后点「连接并加载库」"
                    : "⚠ 未选择目标库——点右侧「设置数据源信息」展开后填写");
            connState.setToolTipText(LogText.NO_DB_HINT);
            return;
        }
        connState.setOk("● 目标库 " + db);
        connState.setToolTipText(db + "@" + hostField.getText().trim() + ":"
                + portField.getValue() + "（用户 " + userField.getText().trim() + "）");
    }

    /** 第一页顶部那句介绍的原文：两栈逐字对撞用（Python 侧 win.imp_intro）。 */
    public String excelIntroText() {
        return impIntro.getText();
    }

    /** 介绍条本体：自检查三页是不是同一块底板。 */
    public javax.swing.JLabel excelIntro() {
        return impIntro;
    }

    /** 弹窗按严重级区分：连接失败这种必须用 error 级挡在前面。 */
    public void critical(String text) {
        // 走 dialog() 而不是直接 showMessageDialog：那条路会记录图标/标题，
        // 原地模式（自检与双端对撞器站在事件分发线程上）也不会把线程卡死
        dialog("连接失败", text, javax.swing.JOptionPane.ERROR_MESSAGE);
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
        importBtn.setEnabled(!tables.isEmpty());
        syncGenButtons();
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
        // 与 Python 同序同话术：没表直接不管（这时「开始导入」本来就是灰的），
        // 没选库要弹 NO_DB_HINT。两处都不占任务位——原来在抢到位之后 return 却没 endTask，
        // 任务位会被永久占住，之后每个按钮都只剩"已有任务在跑"。
        if (tables.isEmpty()) {
            return;
        }
        final Config cfg = currentConfig();
        if (!cfg.hasDatabase()) {
            warn(LogText.NO_DB_HINT);
            return;
        }
        lastTaskTitle = "正在导入到目标库…";
        taskLogView = null;
        if (!startTask(RES_EXCEL)) {
            return;
        }
        final Task task = tasks.get(tasks.size() - 1);
        config = cfg;
        final List<TableBuilder.Result> work = new ArrayList<TableBuilder.Result>();
        for (TableOps.Table t : tables) {
            TableBuilder.Result r = byTable.get(t);
            if (r != null) {
                work.add(r);
            }
        }
        // 勾选状态在 EDT 上读一次：子线程里再点组件属于跨线程摸 Swing
        final boolean drop = dropExisting.isSelected();
        final ProgressDialog progress = new ProgressDialog(this, "正在导入到目标库…");
        // 导入是唯一能中止的长任务，按钮名要说清楚"做完当前表才停"
        progress.setCancelText("停止（当前表完成后中止）");
        lastTask = progress;
        task.dialog = progress;
        final ImportParse.Sink sink = sinkFor(task);
        Thread worker = new Thread(() -> {
            try {
                final List<ImportRun.Row> rows = ImportRun.run(cfg, work, drop, sink);
                final List<ImportOutcome> outcomes = new ArrayList<ImportOutcome>();
                for (ImportRun.Row r : rows) {
                    outcomes.add(new ImportOutcome(r.filepath, r.ok));
                }
                final boolean cancelled = progress.isCancelled();
                SwingUtilities.invokeLater(() -> finishTask(task, () -> {
                    progress.close();
                    // 弹窗与日志双写：日志可回溯，弹窗防走神漏看（Python 侧同一句话写两处）
                    inform2("导入完成", finishImport(outcomes, cancelled));
                }));
            } catch (Throwable t) {
                crash(t, progress, task);
            }
        }, "excel2mysql-import");
        worker.setDaemon(true);
        worker.start();
        placeTaskDialog(progress);
        progress.open();
    }

    /**
     * 按钮可用性统一按资源位算一次：只有占着这一位的任务在跑，才灰掉这一页的按钮。
     * 连接按钮管的是三页共用的那套配置，任何一个任务在跑都不动它——
     * 半途换库会让正在跑的那步说不清自己连的是哪儿。
     */
    private void syncRunStates() {
        connect.setEnabled(tasks.isEmpty());
        // 「有表就能点」，与 Python 侧同一口径：没选库时点下去由 startImport 弹 NO_DB_HINT，
        // 而不是把按钮一直灰着让人以为是坏了
        importBtn.setEnabled(!taskBusy(RES_EXCEL) && !tables.isEmpty());
        // 停止只管 Excel 页这一页的任务：进度框自己那颗「取消」才是各页通用的中止口
        stopBtn.setEnabled(taskBusy(RES_EXCEL));
        syncGenButtons();
        if (sqlPage != null) {
            sqlPage.setGenBusy(taskBusy(RES_SQL));
        }
        // 脚本队列只要非空就能点：没选库时由 executeRunScripts 弹 NO_DB_HINT，
        // 与「开始导入」同一口径；编辑区与脚本队列共用结果面板，跑着的时候两边都别动
        if (sqlRunScriptsBtn != null) {
            sqlRunScriptsBtn.setEnabled(!sqlRunFiles.isEmpty() && !taskBusy(RES_RUN));
        }
    }

    /**
     * 两个生成按钮只在"有表可导、且这一页没有任务在跑"时可点。
     * 判据取自同一处而不是各写各的：任务跑着的时候表列表正被工作线程读，
     * 这时候再生成一份就是两个任务共用同一份数据互相踩。别的页在跑不影响这一页。
     */
    public void syncGenButtons() {
        if (genStd == null) {
            return;     // 连接栏比页签里的按钮先建，赋初值阶段还不能动它们
        }
        boolean on = !tables.isEmpty() && !taskBusy(RES_EXCEL);
        genStd.setEnabled(on);
        genSimple.setEnabled(on);
    }

    /**
     * 可执行程序所在目录——生成物的日期目录落在这里。
     *
     * 从代码所在的那个 jar 取：交付包里 jar 躺在包根，取到的就是包根；
     * 开发态从 target/classes 跑，取到的是 classes 那一层，对应 Python 的 program_dir()。
     */
    public static String programDir() {
        try {
            java.net.URL u = MetaExport.class.getProtectionDomain().getCodeSource()
                    .getLocation();
            if (u != null) {
                File f = new File(u.toURI());
                return f.isDirectory() ? f.getPath() : f.getParentFile().getPath();
            }
        } catch (Exception e) {
            // 定位失败就退回当前工作目录：不能因为取不到程序目录就不生成
        }
        return new File("").getAbsolutePath();
    }

    /**
     * 自检用：把连接配置的候选路径换掉，好在没有 conn.ini 的机器上验三种读法。
     * 现场不碰这个，留 null 就是默认那两条（jar 同级 → 当前目录）。
     */
    public static List<String> connCandidates;

    /**
     * 启动时先读程序同级的 conn.ini 预填连接信息：现场使用者配一次，不用每次手填。
     *
     * 读法与命令行那条线是同一份实现（core/Config）：jar 同级 → 当前目录，
     * 环境变量 E2M_* 再盖过文件。找不到文件不算错，照旧手填，但必须说一句——
     * 静默用内置默认值会让人以为自己的配置已经生效。"有这份文件但读不了"与
     * "压根没有"要分开说：前一种用户会去改文件，后一种要新建。
     */
    private void loadDefaultConn() {
        Config c = Config.discover(connCandidates == null
                ? Config.defaultConfigPaths() : connCandidates);
        // 只有"用户明确配过、且点名了目标库"才自动连：随包 conn.ini 是空模板
        // （database 留空），照它连本机 3306 只会让新装用户一开界面就看到一条失败；
        // 没点库时即便连上也得让人手选，省不下那一步。
        autoConnectEligible = c.explicit
                && c.database != null && !c.database.trim().isEmpty();
        hostField.setText(c.host);
        portField.setValue(c.port);
        userField.setText(c.user);
        passField.setText(c.password);
        cfgDb = c.database == null ? "" : c.database.trim();
        String shown = c.user + "@" + c.host + ":" + c.port + "/"
                + (cfgDb.isEmpty() ? "(未填库)" : cfgDb);
        String pwd = (c.password == null || c.password.isEmpty()) ? "未配置" : "已读入（不回显）";
        if (c.errorFile != null) {
            log("[跳过] 找到 " + c.errorFile + " 但读不了，连接信息按内置默认值 " + shown
                    + "：" + c.errorReason);
        } else if ("内置默认值".equals(c.source)) {
            log("[连接信息] 没找到 conn.ini，按内置默认值 " + shown
                    + "；要预填就在程序同级放一份（写法见《使用说明》）");
        } else {
            log("[已加载] 连接信息预填 " + shown + " | 来源：" + c.source + " | 密码" + pwd);
        }
    }

    /** 自检读这条：有没有一份明确的连接配置（决定窗口显示后要不要自动连）。 */
    public boolean isAutoConnectEligible() {
        return autoConnectEligible;
    }

    /** 界面点出来的入口：输出根目录就是可执行程序同级。 */
    public void generateExcel(final boolean withMeta) {
        generateExcel(withMeta, programDir());
    }

    /**
     * 生成标准/简易 Excel：写盘在子线程、进度框照弹，与导入同一个任务框架。
     * 不连库也能用——这份产物是给下游建模用的文件，不是往库里灌数据。
     *
     * 输出根目录做成入参：Python 侧的任务函数第一个参数就是 out_root，
     * 自检要用临时目录，不能往构建产物目录里拉一堆 xlsx。
     */
    public void generateExcel(final boolean withMeta, final String outRoot) {
        if (tables.isEmpty()) {
            warn(NO_FILE_HINT);
            return;
        }
        final List<MetaExport.Source> work = new ArrayList<MetaExport.Source>();
        for (TableOps.Table t : tables) {
            work.add(MetaExport.of(t, byTable.get(t)));
        }
        log("===== 开始生成" + (withMeta ? "标准" : "简易") + "excel文件（输出根目录 "
                + outRoot + "）=====");
        runTask(withMeta ? "正在生成标准excel文件…" : "正在生成简易excel文件…",
                sink -> MetaExport.exportTables(work, outRoot, withMeta, sink),
                out -> finishMetaExport((MetaExport.Outcome) out));
    }

    /**
     * 生成收尾：日志与弹窗双写同一句话。目录名要原样给出来——文件落在程序同级、
     * 按日期新建的目录里，只说"生成完成"用户不知道该去哪儿找。
     */
    public String finishMetaExport(MetaExport.Outcome res) {
        int n = res.written.size();
        String note = res.cancelled ? "（已取消，剩余文件未生成）" : "";
        String text = n > 0
                ? "已生成 " + n + " 个文件（共 " + res.total + " 张表）。" + NEWLINE
                        + "目录：" + NEWLINE + res.dir + note
                : "没有生成任何文件，详见日志。" + note;
        String summary = "===== 生成完成：" + n + "/" + res.total + " 个文件，目录 "
                + res.dir + " =====" + note;
        if (n == 0) {
            logError(summary);
        } else {
            log(summary);
        }
        inform2("生成结果", text);
        return text;
    }

    /**
     * 占住这一批资源位：占不到就说明有冲突的任务在跑，这次点击不能悄悄吃掉。
     *
     * 护栏必须是真入口，而不是散在各方法开头的 if：以前是一个全局开关锁住三页，
     * 执行器跑上千条语句时另外两页连点都点不动。现在按资源判——同一页互斥，
     * 两页的「生成」因为写同一个输出目录所以也互斥。
     */
    public boolean startTask(String... resources) {
        if (resources.length == 0) {
            // 没说占哪一位的调用（Excel 页那两个手写的入口与自检）默认占这一页的位：
            // 空着当"什么都不占"就等于把并发护栏拆了，第二次点击能挤进来踩同一份表列表
            resources = new String[]{RES_EXCEL};
        }
        Task other = conflict(resources);
        if (other != null) {
            // 静默吞掉点击、或只回一句"请稍等"，用户就只能在几个按钮之间一个个试
            warn(busyText(other));
            return false;
        }
        Task task = new Task(resources, lastTaskTitle, taskLogView);
        tasks.add(task);
        syncRunStates();
        return true;
    }

    /** 第一个与这批资源冲突的在跑任务；没有冲突返回 null。 */
    public Task conflict(String... resources) {
        for (Task t : tasks) {
            for (String r : t.resources) {
                for (String want : resources) {
                    if (r.equals(want)) {
                        return t;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 拦下一次操作时要说的那句话：谁的任务、共用哪一份数据、怎么才能继续。
     * 按钮与 Del 键两条入口共用一份话术，不然同一件事两处说法不同、各改一处就分叉。
     */
    private String busyText(Task other) {
        return "已有任务在跑：" + other.describe() + "。这一步没执行——它和那个任务共用「"
                + sharedLabel(other) + "」，等它跑完或点进度框「取消」。";
    }

    /** 冲突句里要说的是"那两个任务抢的是同一样东西"，光报资源代号没人看得懂。 */
    private String sharedLabel(Task other) {
        StringBuilder sb = new StringBuilder();
        for (String r : other.resources) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(RES_LABEL.containsKey(r) ? RES_LABEL.get(r) : r);
        }
        return sb.toString();
    }

    /** 释放最后登记的那个任务位：自检与命令行按这个口强制收摊。 */
    public void endTask() {
        if (!tasks.isEmpty()) {
            endTask(tasks.get(tasks.size() - 1));
        }
    }

    /** 收尾指定任务：runTask 的每条出口都走这里，避免"最新那个"被认错。 */
    void endTask(Task task) {
        tasks.remove(task);
        task.dialog = null;
        // 直接往面板上写的那几行（SqlPage.log 这类）跟着最后一个还在跑的任务走：
        // 两个任务并行时，先收尾的那个不该把另一个的日志去向改回 Excel 页
        Task last = tasks.isEmpty() ? null : tasks.get(tasks.size() - 1);
        taskLogView = last == null || last.logView == logPanel ? null : last.logView;
        syncRunStates();
    }

    /**
     * 任务收尾的统一口径：先把资源位还出来，再按"这条任务自己那一页"写落界面的日志。
     *
     * 两头都不能省：先弹结果再放位，用户没点确定那段时间里这一页会被一条已经做完的
     * 任务占着；而落界面那几步（finishAdd / finishImport 这类）用的是全局日志去向，
     * 不先把它指回自己那一页，并行时这几行会被下一个还在跑的任务的面板接走
     * ——用例里"取消的那条没写已取消"就是这么查出来的，日志其实去了别一页。
     */
    private void finishTask(Task task, Runnable deliver) {
        endTask(task);
        taskLogView = task.logView == logPanel ? null : task.logView;
        deliver.run();
        taskLogView = null;
        syncRunStates();
    }

    /** 当前在跑的任务清单（自检与用例读它，不再靠一个布尔量猜）。 */
    public List<Task> runningTasks() {
        return new ArrayList<Task>(tasks);
    }

    /** 一个在跑的任务：占哪些资源、叫什么、进度到哪、日志写哪、进度框是哪个。 */
    public final class Task {
        public final String[] resources;
        public final String title;
        public final LogPanel logView;
        public ProgressDialog dialog;
        public Thread worker;
        private final long startedAt = System.currentTimeMillis();
        private int done;
        private int total;
        private String label = "";

        Task(String[] resources, String title, LogPanel logView) {
            this.resources = resources;
            this.title = title == null || title.isEmpty() ? "未命名任务" : title;
            // 没指定面板的任务（Excel 页那些）日志写主页面的日志框，与 logView() 同一口径
            this.logView = logView == null ? logPanel : logView;
        }

        /** 进度落到台账：非模态之后没有"框一定在"的假设，句子得自己说得清。 */
        public void setProgress(int doneNow, int totalNow, String labelNow) {
            done = Math.max(doneNow, 0);
            total = Math.max(totalNow, 0);
            if (labelNow != null && !labelNow.isEmpty()) {
                label = labelNow;
            }
            if (dialog != null) {
                dialog.setProgress(done, total, labelNow == null ? "" : labelNow);
            }
        }

        public int percent() {
            return total <= 0 ? 0 : Math.min(100, (int) (done * 100L / total));
        }

        /**
         * 计项本身也要读得到：三千项的任务在 400 毫秒里百分比一动不动，
         * 只拿 percent() 断"两条都在推进"会假红（这一轮第一次红就是这个原因）。
         */
        public int getDone() {
            return done;
        }

        public int getTotal() {
            return total;
        }

        /** 还剩几项没做：用例挑"落后那条"下手时用，比 percent() 准（三千项时 percent 全是 0）。 */
        public int remaining() {
            return Math.max(0, total - done);
        }

        /** 一句能照着排查的话：任务名 + 百分比 + 计数 + 已跑时长（+ 当前项）。 */
        public String describe() {
            long secs = (System.currentTimeMillis() - startedAt) / 1000L;
            String text = title + " " + percent() + "%（" + done + "/" + total
                    + "）· 已 " + secs + " 秒";
            return label.isEmpty() ? text : text + " · " + label;
        }

        /** 中止这个任务：取消在当前项做完时生效。 */
        public void cancel() {
            if (dialog != null) {
                dialog.requestCancel();
            }
        }
    }

    /**
     * 带进度框的任务入口：抢资源位 -> 子线程算 + 非模态框报进度 -> 回到事件分发线程落界面。
     * modalProgress 关掉时同一个 work 原地跑完，只是不弹框。
     */
    private void runTask(String title,
                         java.util.function.Function<ImportParse.Sink, Object> work,
                         java.util.function.Consumer<Object> onUi) {
        runTask(title, work, onUi, null);
    }

    /**
     * 任务占哪些资源：按日志去向认出是哪一页发起的。
     *
     * 拆资源而不是拆页面：同一页的两个任务会踩同一份列表或同一段编辑区，所以互斥；
     * 两页的「生成标准/简易excel文件」各写自己的目录（SQL 页那份带 sql_ 前缀，
     * 见 MetaExport.SQL_DIR_TAG），不抢同一个目录也就可以并行。
     */
    private String[] resourcesFor(LogPanel logView) {
        if (logView == null) {
            return new String[]{RES_EXCEL};
        }
        if (logView == sqlRunLog) {
            return new String[]{RES_RUN};
        }
        if (sqlPage != null && logView == sqlPage.logPanel()) {
            return new String[]{RES_SQL};
        }
        return new String[]{RES_EXCEL};
    }

    /**
     * logView 非空时整个任务的日志（含中途的报错行）都写到那块面板去：
     * SQL 页的任务把日志留在 SQL 页，否则用户在 Excel 页的日志里找不到刚才那步。
     */
    private void runTask(String title,
                         java.util.function.Function<ImportParse.Sink, Object> work,
                         java.util.function.Consumer<Object> onUi,
                         LogPanel logView) {
        lastTaskTitle = title;
        LogPanel previous = taskLogView;
        // 先认下面板再抢资源位：抢输的那句"已有任务在跑"要落在用户刚点的那一页
        taskLogView = logView;
        if (!startTask(resourcesFor(logView))) {
            taskLogView = previous;
            return;
        }
        final Task task = tasks.get(tasks.size() - 1);
        if (modalProgress) {
            final ProgressDialog progress = new ProgressDialog(this, title);
            lastTask = progress;
            task.dialog = progress;
            final ImportParse.Sink sink = sinkFor(task);
            Thread worker = new Thread(() -> {
                Object out;
                try {
                    out = work.apply(sink);
                } catch (Throwable t) {
                    SwingUtilities.invokeLater(() -> finishTask(task, () -> {
                        progress.close();
                        crashMessage(t);
                    }));
                    return;
                }
                SwingUtilities.invokeLater(() -> finishTask(task, () -> {
                    progress.close();
                    // 工作函数返回 null 视为"这次没做成"，不回调落界面（与 Python 侧同一处理）
                    if (out != null && onUi != null) {
                        onUi.accept(out);
                    }
                }));
            }, "excel2mysql-task");
            task.worker = worker;
            worker.setDaemon(true);
            worker.start();
            placeTaskDialog(progress);
            // open() 现在显示的是非模态框：主线程不再停在嵌套循环里，别的页照常能用
            progress.open();
            return;
        }
        Object out;
        try {
            out = work.apply(sinkFor(task));
        } catch (Throwable t) {
            finishTask(task, () -> crashMessage(t));
            return;
        }
        // 工作函数返回 null 视为"这次没做成"，不回调落界面（与 Python 侧同一处理）
        finishTask(task, () -> {
            if (out != null && onUi != null) {
                onUi.accept(out);
            }
        });
    }

    @Override
    public String exportRoot() {
        return programDir();   // 交付件落点：界面里是程序同级，自检指临时目录
    }

    /**
     * 多个进度框错开摆：都居中在主人窗口上会叠成一颗，后面那颗的「取消」根本点不到。
     *
     * 往右下各错一格，竖直那一步（160）比框本身高还多，几颗不会压住对方。
     * 偏移量按"此刻有几个框在屏"算，不按任务累计序号——任务号会一直涨，
     * 跑上几轮就把框推到屏幕外去了。两条线同一个算法（Python 侧 `_place_task_dialog`）。
     */
    private void placeTaskDialog(ProgressDialog dlg) {
        int shown = 0;
        for (Task t : tasks) {
            if (t.dialog != null && t.dialog != dlg && t.dialog.isShowing()) {
                shown++;
            }
        }
        java.awt.Rectangle avail = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        int w = dlg.getWidth() > 0 ? dlg.getWidth() : dlg.getPreferredSize().width;
        int h = dlg.getHeight() > 0 ? dlg.getHeight() : dlg.getPreferredSize().height;
        int x = getX() + UiScale.px(40) + shown * UiScale.px(40);
        // 竖直那一步要大于框自己的高：框高按 DPI 折算，写死 160 在 144dpi 上就比框矮，
        // 两颗照样上下叠住（自检抓到过一次，交叠 26 像素）
        int y = getY() + UiScale.px(90) + shown * (h + UiScale.px(20));
        dlg.setLocationRelativeTo(null);   // 先取消 AWT 那个"没动过就居中在主人上"的默认
        dlg.setLocation(Math.max(avail.x + 8, Math.min(x, avail.x + avail.width - w - 8)),
                Math.max(avail.y + 8, Math.min(y, avail.y + avail.height - h - 8)));
    }

    /**
     * SQL 页的耗时操作（读取 / 保存 / 执行 / 生成交付件）走主窗口同一套任务框架，
     * 差别只在日志要去 SQL 页那块面板——否则切回 Excel 页才看得见刚才那步做了什么。
     *
     * 这一页的每一步只占自己那一位：读脚本、改表名、保存、执行、生成可以并行，
     * 只有同一页的下一步会被挡下（两页的「生成」也各写各的目录，见 MetaExport.SQL_DIR_TAG）。
     */
    @Override
    public void runSqlTask(String title,
                           java.util.function.Function<ImportParse.Sink, Object> work,
                           java.util.function.Consumer<Object> onUi) {
        runTask(title, work, onUi, sqlPage == null ? null : sqlPage.logPanel());
    }

    /**
     * 任务台账的回报口：进度先落进台账，台账再刷它自己的框（原地跑那条路没有框，只记账）。
     * log/logError 本身就是线程安全的；日志一律写这个任务自己那一页的面板。
     */
    private ImportParse.Sink sinkFor(final Task task) {
        return new ImportParse.Sink() {
            @Override
            public void setTotal(int total) {
                // 脚本执行是一条一条读进来的，总量随时会长：这里再清零就把已经走完的进度抹掉了
                task.setProgress(task.done, total, "");
            }

            @Override
            public void step(String label) {
                task.setProgress(task.done + 1, task.total, label);
            }

            @Override
            public void advance(int units, String label) {
                task.setProgress(task.done + units, task.total, label);
            }

            @Override
            public void log(String text) {
                task.logView.log(text);
            }

            @Override
            public void logError(String text) {
                task.logView.error(text);
            }

            @Override
            public boolean cancelled() {
                return task.dialog != null && task.dialog.isCancelled();
            }
        };
    }

    /** 有长任务在跑吗：不给资源位就是问"有没有任何一个"，给了只问那几位占没占。 */
    public boolean taskBusy() {
        return !tasks.isEmpty();
    }

    public boolean taskBusy(String... resources) {
        return conflict(resources) != null;
    }

    public javax.swing.JButton stopButton() {
        return stopBtn;
    }

    /** 自检按用户的入口点这颗主按钮，不绕过界面直接调导入逻辑。 */
    public javax.swing.JButton importButton() {
        return importBtn;
    }

    /** 自检与命令行入口按用户的方式点这两颗生成按钮，而不是绕过界面直接调 core。 */
    public javax.swing.JButton genStandardButton() {
        return genStd;
    }

    public javax.swing.JButton genSimpleButton() {
        return genSimple;
    }

    /**
     * 请求中止 Excel 页最新的那个任务：已经做完的那一项会跑完，剩下的不再开工。
     * 进度框上的「取消」是各页通用的中止口，这颗按钮只管这一页——
     * 别的页也在跑时，它不能隔着页签去掐别人的任务。
     */
    public void stopTask() {
        Task task = null;
        for (Task t : tasks) {
            if (t.resources.length > 0 && RES_EXCEL.equals(t.resources[0])) {
                task = t;
            }
        }
        if (task == null || task.dialog == null) {
            log("没有正在执行的任务");
            return;
        }
        task.cancel();
        log("[正在中止...]");
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
        // 文件名框里敲文件夹路径回车时进目录，而不是把目录当文件带回来
        JFileChooser chooser = new FolderAwareChooser();
        chooser.setDialogTitle("选择 Excel 文件或 ZIP 压缩包");
        chooser.setMultiSelectionEnabled(true);
        // 四档过滤器与 Python 侧一致：只给一档的话，想单看 zip 或单看 xls 的人筛不出来
        chooser.setAcceptAllFileFilterUsed(true);
        FileNameExtensionFilter excelZip = new FileNameExtensionFilter(
                "Excel / ZIP (*.xlsx *.xls *.zip)", "xlsx", "xls", "zip");
        chooser.addChoosableFileFilter(excelZip);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "Excel 文件 (*.xlsx *.xls)", "xlsx", "xls"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "ZIP 压缩包 (*.zip)", "zip"));
        chooser.setFileFilter(excelZip);
        // 取消选择器是用户自己的中止动作，Python 那边一句话不说（main.py:1363），照它来
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
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
        lastTaskTitle = "正在解析文件…";
        taskLogView = null;
        if (!startTask(RES_EXCEL)) {
            return;
        }
        final Task task = tasks.get(tasks.size() - 1);
        applyNamingSwitches();
        // 解压子目录的编号起点 = 这一会话已经解出过多少个包内文件。
        // 在 EDT 上取好再进解析线程：字段由主线程在 finishAdd 里改，
        // 让子线程去读它就变成跨线程读，编号还可能被后一批的更新带跑。
        final int destStart = zipOrigin.size();
        final ProgressDialog progress = new ProgressDialog(this, task.title);
        lastTask = progress;
        task.dialog = progress;
        final ImportParse.Sink sink = sinkFor(task);
        Thread worker = new Thread(() -> {
            try {
            final ImportParse.Outcome outcome =
                    ImportParse.parse(paths, currentPaths(), zipDirFor(paths), destStart,
                            builder, sink);
            SwingUtilities.invokeLater(() -> finishTask(task, () -> {
                progress.close();
                finishAdd(outcome);
            }));
            } catch (Throwable t) {
                // 线程里的意外只会把模态框留在界面上，必须收掉并说出来
                crash(t, progress, task);
            }
        }, "excel2mysql-parse");
        worker.setDaemon(true);
        worker.start();
        placeTaskDialog(progress);
        progress.open();
    }

    /** 开关状态在主线程取好再进解析线程：解析线程不碰控件，只读 Names 上的三个布尔。 */
    private void applyNamingSwitches() {
        names.setInitialsOnly(initialsBox != null && initialsBox.isSelected());
        names.setUpperOnly(upperBox != null && upperBox.isSelected());
        names.setLowerOnly(lowerBox != null && lowerBox.isSelected());
    }

    /**
     * 两颗大小写勾互斥：亮一颗就按掉另一颗。
     * Swing 的 setSelected 不触发 ActionListener，所以按掉的那颗不会补一句切换日志，
     * 与 Qt 侧 blockSignals 的做法同一效果（一次点击只留一条留痕）。
     */
    private void keepOneCaseSwitch(javax.swing.JCheckBox keep, javax.swing.JCheckBox other) {
        if (keep.isSelected() && other.isSelected()) {
            other.setSelected(false);
        }
    }

    /** 当前生效的大小写档，第二个元素是结构定义手写字段名的口径，日志与解析共用一份判读。 */
    private String[] caseState() {
        if (upperBox != null && upperBox.isSelected()) {
            return new String[]{"大写", "跟着转大写"};
        }
        if (lowerBox != null && lowerBox.isSelected()) {
            return new String[]{"小写", "跟着转小写"};
        }
        return new String[]{"小写", "保持原样"};
    }

    /** 给测试和命令行用的同步入口：同一个解析函数，只是不挂进度框。 */
    public String addFiles(List<String> paths) {
        applyNamingSwitches();
        ImportParse.Outcome outcome = ImportParse.parse(paths, currentPaths(),
                zipDirFor(paths), zipOrigin.size(), builder, sinkForLogOnly());
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
    public String finishAdd(ImportParse.Outcome outcome) {
        zipOrigin.putAll(outcome.zipOrigin);
        // 编号起点直接从这张映射取（它就是"会话内已解出的包内文件数"），
        // 不再自己按批次 +1：一批里解出 3 个文件时下一包的目录号会跳 3，
        // 而批次计数只 +1，接着拖几批就会拐回已经用过的目录号上，
        // 把前一批解出来的那份原地覆盖掉——列表里那张旧表的来源内容就此换人，
        // 同名文件还会被当成"已在待导入列表"跳过，第二张包的表静默消失。
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
        if (importBtn != null) {
            importBtn.setEnabled(!tables.isEmpty());
        }
        syncGenButtons();
        refreshPreview();
        String summary = ImportParse.summaryText(outcome);
        if (outcome.cancelled) {
            // 取消这件事要进正文，只看弹窗的人会以为整批都跑完了
            summary = summary.isEmpty() ? "已取消，只完成了部分解析"
                    : "已取消，只完成了部分解析" + NEWLINE + summary;
        }
        if (!summary.isEmpty()) {
            // 同一句话既进日志也进弹窗：日志可回溯，弹窗防走神漏看
            log("[添加结果] " + summary);
            inform2("添加结果", summary);
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
            log("[提示] 拖入或粘贴的不是 Excel 或 ZIP 文件，已忽略。");
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

        /** 会话级解压目录：整个窗口生命周期只建一个，关窗口一次性清掉。
         *
         * 根目录名刻意压到最短（e2m-<纳秒>）：解压后的完整路径是
         * 「TEMP + 根名 + 包名_序号 + 包内目录 + 包内文件名」，现场机器的用户名动辄比
         * 开发机长二三十字符，客户数据包里还有几十字的中文文件名与目录层级，
         * 顶到 Windows 的 260 上限时症状是"拖进来就报"，而且只在现场报、开发机不复现。
         */
    private String zipSessionDir() {
        if (zipSessionDir == null) {
            File base = new File(System.getProperty("java.io.tmpdir"),
                    "e2m-" + System.nanoTime());
            if (!base.mkdirs()) {
                log("建解压目录失败：" + base.getPath()
                        + "；可在日志里看到这行，改短 TEMP 或换 Python 命令行版再试");
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

    /** 表名可能在界面上被改过口径（前缀、主键勾选），DDL 每次都按当前状态重算。 */
    public void refreshPreview() {
        StringBuilder sb = new StringBuilder();
        for (TableOps.Table t : tables) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(Ddl.buildDdl(t.tableName, t.tableComment, t.columns, dropIfExistsNow()));
        }
        ddlText = sb.toString();
    }

    /** 勾选状态既影响「预览 DDL」弹窗，也影响真实导入，两处读同一个判定。 */
    private boolean dropIfExistsNow() {
        return dropExisting != null && dropExisting.isSelected();
    }

    public List<TableOps.Table> tables() {
        return tables;
    }

    /** 自检要把生成的文件再读回来，用的必须是窗口上那一份词典，不是新起一套。 */
    public Names names() {
        return names;
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

    /** 文件表单击「表名」改名：校验不过就恢复原名，改成了就以新名为基础名。 */
    private void renameFileTable(FileRowModel.Item item, String typed) {
        String old = item.table.tableName;
        String name = Names.pyStrip(String.valueOf(typed == null ? "" : typed).replace("`", ""));
        if (name.equals(old)) {
            fileModel.refresh();
            return;
        }
        List<String> others = new ArrayList<String>();
        for (TableOps.Table t : tables) {
            if (t != item.table) {
                others.add(t.tableName);
            }
        }
        String why = TableOps.tableNameProblem(name, others);
        if (why != null) {
            warn(why + "已恢复原表名「" + old + "」。");
            fileModel.refresh();
            return;
        }
        item.table.tableName = name;
        item.table.baseTableName = name;
        fileModel.refresh();
        refreshPreview();
        log("[表名] " + old + " → " + name + "（手动修改，后续批量前缀基于新名计算）");
    }

    /**
     * Ctrl+V 先看剪贴板：里面是文件就一律按"添加文件"处理，不看焦点落在谁身上。
     *
     * 两条绑定缺一不可：
     * ① 根面板的 WHEN_IN_FOCUSED_WINDOW 管"焦点不在文本控件"那一半（表格、拖拽区、按钮）；
     * ② 文本控件自己的 WHEN_FOCUSED 绑定排在窗口级之前，焦点一进 SQL 编辑区或主机名输入框
     *    就把按键吃掉，窗口级那条轮不到（用户报"粘贴用不了"就是这个），所以逐个把它们的
     *    control V 改挂到同一条分派上——没文件时才回给它自己粘，粘主机名、粘 SQL 片段照旧。
     * 不用 KeyEventDispatcher：随包那份 JRE 的 rt.jar 里根本没有这个类（裁过的），
     * 编译期就找不到符号，拿它做"最前面拦一层"在这条交付线上不成立。
     */
    private void bindPasteShortcut() {
        javax.swing.KeyStroke key = javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(key, "pasteFiles");
        getRootPane().getActionMap().put("pasteFiles", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                pasteFiles();
            }
        });
        bindTextPaste(getContentPane());
        // 之后再新造出来的文本控件（表格就地编辑那类）也补上同一条分派
        getContentPane().addHierarchyListener(new java.awt.event.HierarchyListener() {
            @Override
            public void hierarchyChanged(java.awt.event.HierarchyEvent e) {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.PARENT_CHANGED) != 0
                        && e.getComponent() instanceof javax.swing.text.JTextComponent) {
                    bindOneTextPaste((javax.swing.text.JTextComponent) e.getComponent());
                }
            }
        });
    }

    /** 给这一棵子树里的每个文本控件换上同一条分派：有文件就抢、没文件就自己粘。 */
    private void bindTextPaste(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.text.JTextComponent) {
                bindOneTextPaste((javax.swing.text.JTextComponent) c);
            }
            if (c instanceof java.awt.Container) {
                bindTextPaste((java.awt.Container) c);
            }
        }
    }

    /** 一个文本控件的 control V 改挂：只装一次，重复绑定会让嵌套的粘贴动作跑两遍。 */
    private void bindOneTextPaste(final javax.swing.text.JTextComponent target) {
        if (Boolean.TRUE.equals(target.getClientProperty(PASTE_BOUND_KEY))) {
            return;
        }
        target.putClientProperty(PASTE_BOUND_KEY, Boolean.TRUE);
        target.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                .put(javax.swing.KeyStroke.getKeyStroke(
                        java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                        "e2mPaste");
        target.getActionMap().put("e2mPaste", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!routePaste(clipboardFilePaths(), target)) {
                    target.paste();     // 剪贴板是纯文本：交回它自己粘
                }
            }
        });
    }

    /** 文本控件的 control V 只改挂一次：重复绑定会让嵌套的粘贴动作跑两遍。 */
    private static final String PASTE_BOUND_KEY = "excel2mysql.pasteBound";

    /** 自检用：文本控件上那条改挂还在不在（丢了就会退回"焦点在输入框时粘不进文件"）。 */
    public boolean isTextPasteBound(javax.swing.text.JTextComponent c) {
        javax.swing.KeyStroke key = javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        Object cmd = c.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(key);
        return cmd != null && c.getActionMap().get(cmd) != null;
    }

    /** 剪贴板里的文件路径：不是文件、或系统这会儿不给读，一律按"没有文件"处理。 */
    public static List<String> clipboardFilePaths() {
        List<String> out = new ArrayList<String>();
        try {
            java.awt.datatransfer.Transferable tr = java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getContents(null);
            if (tr == null || !tr.isDataFlavorSupported(
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                return out;
            }
            Object raw = tr.getTransferData(
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            if (raw instanceof java.util.List) {
                for (Object o : (java.util.List<?>) raw) {
                    if (o instanceof java.io.File && ((java.io.File) o).isFile()) {
                        out.add(((java.io.File) o).getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * 粘贴添加：剪贴板里是文件就等同拖进**眼前这一页**的虚线区，走同一页拖进来那条路。
     *
     * 返回 true 表示这一按键已经被"添加文件"用掉；剪贴板是纯文本时返回 false，
     * 按键照旧交回焦点控件自己粘——粘主机名、粘 SQL 片段不受影响。
     * 三页的虚线区都写着这句快捷键提示，所以粘贴必须落在当前页：第二页进脚本改名清单、
     * 第三页进脚本执行队列、第一页进 Excel 待导入表。类型不对或压根不是文件，
     * 交给那一页已有的"已忽略"回执；任务位被占时先拦下来——
     * 那句"已通过粘贴添加"不能在没加成的情况下说出口。
     */
    public boolean pasteFiles() {
        java.awt.Component focus = java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusOwner();
        if (routePaste(clipboardFilePaths(), focus)) {
            return true;
        }
        // 分派说"这按键不归我"：焦点在文本控件时那条绑定会自己粘，这里只兜住漏网的
        if (focus instanceof javax.swing.text.JTextComponent) {
            ((javax.swing.text.JTextComponent) focus).paste();
        }
        return false;
    }

    /** 分派规则单独成一条：自检喂假剪贴板也能验到"有文件就抢、没文件就让"。 */
    public boolean routePaste(List<String> paths, java.awt.Component focus) {
        if (paths != null && !paths.isEmpty()) {
            pastePaths(paths);
            return true;
        }
        if (focus instanceof javax.swing.text.JTextComponent) {
            return false;       // 没有文件可加，这一按键本来就归文本控件自己粘
        }
        pastePaths(new ArrayList<String>());    // 焦点不在输入框：给当前页一句"粘的不是文件"
        return true;
    }

    /** 粘贴的那批路径走这一条：加到用户眼前这一页，不是永远加到第一页。 */
    public void pastePaths(List<String> paths) {
        int page = tabs.getSelectedIndex();
        if (page == 1) {
            sqlPage.dropFiles(paths);
            return;
        }
        if (page == 2) {
            onRunScriptsDropped(paths);
            return;
        }
        List<String> accepted = acceptedDrops(paths);
        if (accepted.isEmpty()) {
            return;
        }
        Task other = conflict(RES_EXCEL);
        if (other != null) {
            warn(busyText(other));
            return;
        }
        int zips = 0;
        int xlsx = 0;
        int xls = 0;
        for (String f : accepted) {
            String low = f.toLowerCase();
            if (low.endsWith(".zip")) {
                zips++;
            } else if (low.endsWith(".xlsx")) {
                xlsx++;
            } else {
                xls++;
            }
        }
        log("[粘贴] 已通过粘贴添加 " + accepted.size() + " 个文件，其中 " + zips
                + "个 zip文件，" + xlsx + "个 xlsx文件，" + xls + "个 xls文件");
        addFilesAsync(accepted);
    }

    /**
     * 文件表双击「表注释」改这张表的注释：建表那句 COMMENT 与两份生成件都读它。
     * 只多打了空格那种"看着改了其实没改"也要给回执，并把格子刷回规范值。
     */
    private void commentFileTable(FileRowModel.Item item, String typed) {
        String old = item.table.tableComment == null ? "" : item.table.tableComment;
        String next = Names.pyStrip(typed == null ? "" : typed);
        if (next.equals(old)) {
            log("[表注释] " + item.table.tableName + " 未发生变化，建表语句不改写");
            fileModel.refresh();
            return;
        }
        item.table.tableComment = next;
        fileModel.refresh();
        refreshPreview();
        log("[表注释] " + item.table.tableName + " → "
                + (next.isEmpty() ? "（空）" : next) + "（建表语句按新注释生成）");
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



    public void clearAll() {
        tables.clear();
        byTable.clear();
        fileModel.clear();
        zipOrigin.clear();
        ddlText = "";
        // 清空后「开始导入」要跟着变灰：Python 的 on_clear 就是这么做的，
        // 留着亮按钮等于允许点一次只会弹"请先添加文件"的空操作
        importBtn.setEnabled(!tables.isEmpty());
        syncGenButtons();
        log("已清空");
    }

    /**
     * Del 键：把选中的行从待上传清单里去掉，多选就是一次批量删。
     *
     * 只出清单，不动磁盘上的原文件；ZIP 展开出来的临时文件仍按原计划在退出时清。
     * 与「清空待上传文件」的区别是范围——按行删得先问有没有任务在解析这张表，
     * 半途抽走正在被解析的那一行，任务回报的项数就对不上剩余清单了。
     */
    public void deleteSelectedFiles() {
        Task other = conflict(RES_EXCEL);
        if (other != null) {
            warn(busyText(other));
            return;
        }
        int[] rows = table.getSelectedRows();
        if (rows == null || rows.length == 0) {
            log("[删除] 没有选中文件，先在列表里点一行再按 Del");
            return;
        }
        List<TableOps.Table> dropped = new ArrayList<TableOps.Table>();
        for (int i = rows.length - 1; i >= 0; i--) {
            if (rows[i] >= 0 && rows[i] < tables.size()) {
                dropped.add(0, tables.remove(rows[i]));
            }
        }
        for (TableOps.Table t : dropped) {
            byTable.remove(t);
            zipOrigin.remove(t.filepath);
        }
        fileModel.removeItems(dropped);
        importBtn.setEnabled(!tables.isEmpty());
        syncGenButtons();
        refreshPreview();
        log("[删除] 已移除 " + dropped.size() + " 个待上传文件，列表剩 "
                + tables.size() + " 个");
    }

    /**
     * 这句日志该落到哪块面板：任务期间跟着任务的 log_view 走（SQL 页的任务留在 SQL 页），
     * 空闲时回到 Excel 页。Python 侧是 `self._task_log_view or self.log`，同一回事。
     */
    private LogPanel logView() {
        return taskLogView == null ? logPanel : taskLogView;
    }

    public void log(String text) {
        logView().log(text);
    }

    /** 错误要整条标红：一屏日志里靠眼睛扫"失败"两个字太容易漏。 */
    public void logError(String text) {
        logView().error(text);
    }

    /** 自检与命令行入口要能按用户的方式操作这两个控件，而不是绕过界面直接改数据。 */
    public HintTextField prefixField() {
        return prefixField;
    }

    public javax.swing.JCheckBox dropExistingBox() {
        return dropExisting;
    }

    public javax.swing.JCheckBox initialsBox() {
        return initialsBox;
    }

    public javax.swing.JCheckBox upperBox() {
        return upperBox;
    }

    public javax.swing.JCheckBox lowerBox() {
        return lowerBox;
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
    public List<String> tabTitles() {        List<String> out = new ArrayList<String>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            out.add(tabs.getTitleAt(i));
        }
        return out;
    }

    /** 最近一次任务的进度框：自检读标题与按钮文案，真实用户看的是同一个对象。 */
    public ProgressDialog lastTaskDialog() {
        return lastTask;
    }

    /** 最近一次请求的任务标题（九句任务话术里的那一句）。 */
    public String lastTaskTitle() {
        return lastTaskTitle;
    }

    public SqlPage sqlPage() {
        return sqlPage;
    }

    /** 切到第几页签：与用户点页签走的是同一个模型，渲染对照与自检都用它。 */
    public void selectTab(int index) {
        if (tabs != null && index >= 0 && index < tabs.getTabCount()) {
            tabs.setSelectedIndex(index);
        }
    }

    private JSplitPane excelSplit;
    private JSplitPane excelLower;
    private java.awt.Component excelDrop;
    private java.awt.Component excelFileBox;
    private java.awt.Component excelLogBox;
    /** 第一页底部动作条的浅色底板（对撞器读它的底色与"开关和按钮在同一块里"）。 */
    private ActionBar excelActionBar;
    /** 用户亲手拖过任一条分隔条后，默认摆放就交还给用户。 */
    private boolean splitUserMoved;
    /** 程序自己摆分隔条期间不把随之而来的 Resize 当成用户动作。 */
    private boolean splitAdjusting;
    /** 第三页（SQL 执行器）的三段：拖拽区 / 脚本清单 / 编辑区与结果。 */
    private JSplitPane sqlRunSplitTop;
    private JSplitPane sqlRunSplitLower;
    private java.awt.Component sqlRunDropPane;
    private java.awt.Component sqlRunQueuePane;
    private java.awt.Component sqlRunEditorPane;
    private boolean runSplitUserMoved;
    private boolean runSplitAdjusting;

    /**
     * 把三段分栏按 Python 的 110:670 摆一次，日志段只留它自己的最小高度。
     *
     * 窗口从构造到首次显示会经历多次 Resize：只在第一次摆会留下后一轮拉伸分给日志的
     * 高度（Python 侧 LogMinPlacer 同样每次 Resize 重摆）。所以用户拖过把手之前，
     * 每次 Resize 都按绝对高度重摆；一旦拖动过，日志高度就归用户，程序不再抢。
     */
    public void applySplitProportions() {
        if (splitUserMoved || splitAdjusting || excelSplit == null
                || excelLower == null || excelLogBox == null) {
            return;
        }
        if (excelSplit.getHeight() < UiScale.px(200)
                || excelLogBox.getMinimumSize().height <= 0) {
            return;
        }
        splitAdjusting = true;
        try {
            int first = (int) Math.round(excelSplit.getHeight() * 110.0 / 670.0);
            excelSplit.setDividerLocation(Math.max(first, excelDrop.getMinimumSize().height));
            int rest = excelSplit.getHeight() - excelSplit.getDividerLocation()
                    - excelSplit.getDividerSize();
            // 下段高度 = rest - 分隔位置 - 内层把手；日志段要正好落到它自己的最小高度
            int second = Math.max(excelFileBox.getMinimumSize().height,
                    rest - excelLower.getDividerSize() - excelLogBox.getMinimumSize().height);
            excelLower.setDividerLocation(second);
        } finally {
            splitAdjusting = false;
        }
    }

    /** 用户按下分隔条把手就停止自动摆放；键盘拖动同样算用户动作。 */
    private void watchDivider(JSplitPane pane, final boolean runPage) {        javax.swing.plaf.SplitPaneUI ui = pane.getUI();
        if (!(ui instanceof javax.swing.plaf.basic.BasicSplitPaneUI)) {
            return;
        }
        java.awt.Component divider =
                ((javax.swing.plaf.basic.BasicSplitPaneUI) ui).getDivider();
        if (divider == null) {
            return;
        }
        divider.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (runPage) {
                    runSplitUserMoved = true;
                } else {
                    splitUserMoved = true;
                }
            }
        });
    }

    /** 第一页日志段是否还压在自己的最小高度上（对撞与自检都量真控件）。 */
    public String logAtMin() {
        if (excelLogBox == null) {
            return "0";
        }
        // JSplitPane 的边框会让实际高度比最小高度少 1~2px，留同样宽的容差
        int diff = excelLogBox.getHeight() - excelLogBox.getMinimumSize().height;
        return diff >= -4 && diff <= 4 ? "1" : "0";
    }

    /** 自检诊断用：日志段的实际高度与最小高度。 */
    public int logBoxHeight() {
        return excelLogBox == null ? -1 : excelLogBox.getHeight();
    }

    public int logBoxMinHeight() {
        return excelLogBox == null ? -1 : excelLogBox.getMinimumSize().height;
    }

    /** 自检用：第一页表格段与日志段的拉伸权重（配置值，离屏环境量不准实际高度）。 */
    public double excelLowerResizeWeight() {
        return excelLower.getResizeWeight();
    }

    /** 自检用：第一页表格段与日志段的初始分隔位置（设计值，按 DPI 折算）。 */
    public int excelLowerDividerLocation() {
        return excelLower.getDividerLocation();
    }

    /**
     * 第三页同样把末段（编辑区与结果）压到最小高度，余量留给上面的脚本清单。
     *
     * 空清单时那张表的图标与引导语要整块看得见；窗口每次 Resize 都按绝对高度重摆，
     * 用户拖过任一条把手之后交还。份额照 Python 的 110:670 与「清单吃余量」口径。
     */
    public void applyRunSplitProportions() {
        if (runSplitUserMoved || runSplitAdjusting || sqlRunSplitTop == null
                || sqlRunSplitLower == null || sqlRunDropPane == null
                || sqlRunQueuePane == null || sqlRunEditorPane == null) {
            return;
        }
        if (sqlRunSplitTop.getHeight() < UiScale.px(200)
                || sqlRunEditorPane.getMinimumSize().height <= 0) {
            return;
        }
        runSplitAdjusting = true;
        try {
            int first = (int) Math.round(sqlRunSplitTop.getHeight() * 110.0 / 670.0);
            sqlRunSplitTop.setDividerLocation(
                    Math.max(first, sqlRunDropPane.getMinimumSize().height));
            int rest = sqlRunSplitTop.getHeight() - sqlRunSplitTop.getDividerLocation()
                    - sqlRunSplitTop.getDividerSize();
            int second = Math.max(sqlRunQueuePane.getMinimumSize().height,
                    rest - sqlRunSplitLower.getDividerSize()
                            - sqlRunEditorPane.getMinimumSize().height);
            sqlRunSplitLower.setDividerLocation(second);
        } finally {
            runSplitAdjusting = false;
        }
    }

    /** 第三页编辑区与结果段是否还压在自己的最小高度上。 */
    public String sqlRunEditorAtMin() {
        if (sqlRunEditorPane == null) {
            return "0";
        }
        int diff = sqlRunEditorPane.getHeight() - sqlRunEditorPane.getMinimumSize().height;
        return diff >= -4 && diff <= 4 ? "1" : "0";
    }

    public int sqlRunEditorHeight() {
        return sqlRunEditorPane == null ? -1 : sqlRunEditorPane.getHeight();
    }

    public int sqlRunEditorMinHeight() {
        return sqlRunEditorPane == null ? -1 : sqlRunEditorPane.getMinimumSize().height;
    }

    /** 第三页「SQL 文本」编辑区的实际高度与最小高度（自检量它是不是 4 行）。 */
    public int sqlRunInputHeight() {
        return sqlRunInput == null ? -1 : sqlRunInput.getHeight();
    }

    public int sqlRunInputMinHeight() {
        return sqlRunInput == null ? -1 : sqlRunInput.getMinimumSize().height;
    }

    /** 第三页编辑区最小高度合几行字：对撞器比的是这个行数，不是各栈的像素值。 */
    public String sqlRunInputMinLines() {
        if (sqlRunInput == null) {
            return "0";
        }
        int line = sqlRunInput.getFontMetrics(sqlRunInput.getFont()).getHeight();
        return line <= 0 ? "0"
                : String.valueOf(Math.round(sqlRunInput.getMinimumSize().height / (float) line));
    }

    /** 第三页三段的自上而下语义名，与第二页 segmentOrder 同一种写法。 */
    public String sqlRunSegmentOrder() {
        if (sqlRunSplitTop == null || sqlRunSplitLower == null
                || sqlRunDropPane == null || sqlRunQueuePane == null
                || sqlRunEditorPane == null) {
            return "";
        }
        return (sqlRunSplitTop.getLeftComponent() == sqlRunDropPane ? "拖拽区" : "?")
                + "|" + (sqlRunSplitLower.getLeftComponent() == sqlRunQueuePane
                        ? "脚本清单" : "?")
                + "|" + (sqlRunSplitLower.getRightComponent() == sqlRunEditorPane
                        ? "编辑区与结果" : "?");
    }

    /** 第三页脚本清单表的视口高度：空清单图标要整块露出来。 */
    public int sqlRunTableViewportHeight() {
        return sqlRunFilesTable == null ? -1 : sqlRunFilesTable.getVisibleRect().height;
    }

    /**
     * 子线程出意外：收掉模态框、恢复界面，日志与弹窗各写一遍同一句话。
     * 少这一层，线程里的异常只会被默认处理器吞掉，进度框永远挂在界面上。
     */
    private void crash(Throwable t, final ProgressDialog progress, final Task task) {
        final String msg = LogText.describe(t);
        SwingUtilities.invokeLater(() -> finishTask(task, () -> {
            logError(LogText.crashLog(msg));
            if (progress != null) {
                progress.close();
            }
            informError("任务异常", LogText.crashDialog(msg));
        }));
    }

    /**
     * 任务出意外的同一句话：写错误级日志，再走"任务异常"弹窗。
     * 弹窗只在模态模式下真开——自检站在事件分发线程上，模态框会把自检自己卡死，
     * 但标题/正文/图标这三样照样留痕，两条路断的是同一份数据。
     */
    public String crashMessage(Throwable t) {
        String msg = LogText.describe(t);
        logError(LogText.crashLog(msg));
        String text = LogText.crashDialog(msg);
        informError("任务异常", text);
        return text;
    }

    /** 自检与命令行入口读得到这几个控件，才谈得上验它们的配置。 */
    public javax.swing.JTable fileTable() {
        return table;
    }

    public javax.swing.JSpinner portSpinner() {
        return portField;
    }

    public javax.swing.JTextField hostField() {
        return hostField;
    }

    public DropArea dropArea() {
        return drop;
    }

    /** 双端界面枚举对撞器要读的那几个控件（见 java/tools/gui_parity.py）。 */
    public javax.swing.JTextField userField() {
        return userField;
    }

    public javax.swing.JPasswordField passField() {
        return passField;
    }

    public javax.swing.JComboBox<String> dbCombo() {
        return dbCombo;
    }

    /** 双端界面枚举对撞器要按页归属分按钮（两页有重名按钮）。 */
    /**
     * 第三个页签「SQL 执行器」：既能按清单执行 .sql 脚本文件，也能临时问一句 SQL。
     *
     * 文件这一路借用第一页的拖拽区与清单表、第二页的底部动作条：覆盖开关在左、
     * 主按钮在右；编辑区这一路仍在下面。两路共用右侧结果区，所以同一页同时只跑
     * 一个任务（见 resourcesFor）。
     */
    private javax.swing.JPanel buildSqlRunPage() {
        javax.swing.JPanel page = new javax.swing.JPanel(new java.awt.BorderLayout(
                UiScale.px(8), UiScale.px(6)));
        // 提示单独占一行：与按钮同排会把整窗最小宽度顶到 830 以上，
        // 1024x768 的现场机和 800 宽的离屏用例都放不下
        javax.swing.JPanel north = new javax.swing.JPanel(new java.awt.BorderLayout());
        sqlRunHint = PageIntro.of(
                "两种用法：把 .sql 脚本拖到下面清单里按顺序执行；"
                        + "或直接在编辑区写 SQL 临时执行（多条用分号分隔）");
        north.add(sqlRunHint, java.awt.BorderLayout.CENTER);
        page.add(north, java.awt.BorderLayout.NORTH);

        // 文件这一路与第一页同一形状：拖拽区在上，清单表套在组框里
        sqlRunDrop = new DropArea(paths -> onRunScriptsDropped(paths),
                this::chooseRunScripts, DropArea.RUN_HINT,
                "点击选择 SQL 脚本；也可以直接拖到这里");
        sqlRunDrop.setMinimumSize(new Dimension(UiScale.px(10), UiScale.px(60)));

        sqlRunFilesModel = new javax.swing.table.DefaultTableModel(
                new String[]{"序号", "文件", "大小", "状态", "操作"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        sqlRunFilesTable = new EmptyHintTable(sqlRunFilesModel, EmptyHintTable.RUN_HINT);
        Tables.style(sqlRunFilesTable);
        sqlRunFilesTable.setFillsViewportHeight(true);
        sqlRunFilesTable.getTableHeader().setReorderingAllowed(false);
        int[] widths = {44, 300, 100, 140, 90};
        for (int i = 0; i < widths.length; i++) {
            sqlRunFilesTable.getColumnModel().getColumn(i)
                    .setPreferredWidth(UiScale.px(widths[i]));
        }
        sqlRunFilesTable.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN);
        // 「文件」列挂完整路径：脚本同名时只靠文件名分不出是哪个目录里的那份
        sqlRunFilesTable.getColumnModel().getColumn(1).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public java.awt.Component getTableCellRendererComponent(
                            javax.swing.JTable t, Object v, boolean selected,
                            boolean focus, int row, int column) {
                        super.getTableCellRendererComponent(t, v, selected, focus, row, column);
                        setToolTipText(row < sqlRunFiles.size() ? sqlRunFiles.get(row).path : "");
                        return this;
                    }
                });
        // 状态列按结局上色：成功绿、失败红、跳过/取消黄褐，与日志分级同一套色
        sqlRunFilesTable.getColumnModel().getColumn(3).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public java.awt.Component getTableCellRendererComponent(
                            javax.swing.JTable t, Object v, boolean selected,
                            boolean focus, int row, int column) {
                        super.getTableCellRendererComponent(t, v, selected, focus, row, column);
                        if (!selected) {
                            String s = String.valueOf(v);
                            if ("成功".equals(s)) {
                                setForeground(new java.awt.Color(0x1a, 0x7f, 0x37));
                            } else if ("失败".equals(s)) {
                                setForeground(new java.awt.Color(0xc6, 0x28, 0x28));
                            } else if ("跳过".equals(s) || "已取消".equals(s)) {
                                setForeground(new java.awt.Color(0xb2, 0x6a, 0x00));
                            } else {
                                setForeground(t.getForeground());
                            }
                        }
                        return this;
                    }
                });
        // 「操作」列画成一颗【执行】：Swing 的单元格里常驻不了真控件，
        // 这一格由下面的单击监听认领，点它就只跑这一行的脚本文件
        sqlRunRowExec = new javax.swing.JButton("执行");
        sqlRunRowExec.setMargin(new java.awt.Insets(1, 8, 1, 8));
        sqlRunRowExec.setFocusPainted(false);
        sqlRunRowExec.setFont(Tables.font());
        sqlRunRowExec.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        sqlRunFilesTable.getColumnModel().getColumn(4).setCellRenderer(
                new javax.swing.table.TableCellRenderer() {
                    @Override
                    public java.awt.Component getTableCellRendererComponent(
                            javax.swing.JTable t, Object v, boolean selected,
                            boolean focus, int row, int column) {
                        sqlRunRowExec.setEnabled(isRowExecutable());
                        sqlRunRowExec.setBackground(t.getBackground());
                        return sqlRunRowExec;
                    }
                });
        sqlRunFilesTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int column = sqlRunFilesTable.columnAtPoint(e.getPoint());
                if (column == 4 && sqlRunFilesTable.rowAtPoint(e.getPoint()) >= 0) {
                    runRowScript(sqlRunFilesTable.rowAtPoint(e.getPoint()));
                }
            }
        });
        // Del 键删掉选中的行（多选就是一次批量删），与第一页那颗 Del 同一口径：
        // 只出清单，磁盘上的脚本不动
        sqlRunFilesTable.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0),
                "deleteSelectedScripts");
        sqlRunFilesTable.getActionMap().put("deleteSelectedScripts",
                new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        deleteSelectedRunScripts();
                    }
                });
        Tables.zebra(sqlRunFilesTable);
        JScrollPane runScroll = new JScrollPane(sqlRunFilesTable);
        runScroll.setMinimumSize(new Dimension(UiScale.px(300), UiScale.px(120)));

        // 这一排左边是「清空待执行脚本」（与第一页「清空待上传文件」同一语义：整份清出清单，
        // 磁盘上的脚本不动），右边是这一页的主按钮「批量执行脚本文件」：
        // 按清单顺序把队列里的脚本全跑一遍，与行内那颗【执行】（只跑一行）分在两头
        sqlRunFilesClearBtn = new javax.swing.JButton("清空待执行脚本");
        sqlRunFilesClearBtn.addActionListener(e -> clearRunScripts());
        sqlRunScriptsBtn = new javax.swing.JButton("批量执行脚本文件");
        Buttons.primary(sqlRunScriptsBtn);
        sqlRunScriptsBtn.setEnabled(false);
        sqlRunScriptsBtn.setToolTipText("<html>按清单顺序逐个执行全部待执行脚本：手写脚本每个文件一个事务，"
                + "全部成功才提交；<br>整库 dump 自动识别，改为不开事务、逐条提交、出错继续并汇总。<br>"
                + "只想跑其中一个就在它那行的【执行】上点。执行前请先选好目标库。</html>");
        sqlRunScriptsBtn.addActionListener(e -> executeRunScripts());
        javax.swing.JPanel clearRow = new javax.swing.JPanel(new java.awt.BorderLayout());
        javax.swing.JPanel clearEast = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, UiScale.px(6), 0));
        clearEast.add(sqlRunScriptsBtn);
        clearRow.add(sqlRunFilesClearBtn, java.awt.BorderLayout.WEST);
        clearRow.add(clearEast, java.awt.BorderLayout.EAST);
        sqlRunQueueRow = clearRow;
        javax.swing.JPanel queueBox = new javax.swing.JPanel(new java.awt.BorderLayout(
                0, UiScale.px(2)));
        Tables.titled(queueBox, Tables.RUN_TABLE_TITLE);
        queueBox.add(runScroll, java.awt.BorderLayout.CENTER);
        queueBox.add(clearRow, java.awt.BorderLayout.SOUTH);
        queueBox.setMinimumSize(new Dimension(UiScale.px(300), UiScale.px(150)));

        sqlRunInput = new javax.swing.JTextArea(8, 60);
        sqlRunInput.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN,
                UiScale.fontPt(SQL_RUN_FONT_SIZE)));
        // 初始化时编辑区只占自己的 4 行：末段压到最小，空清单的图标与引导语才看得全
        sqlRunInput.setMinimumSize(new Dimension(0, LogPanel.LOG_MIN_LINES
                * sqlRunInput.getFontMetrics(sqlRunInput.getFont()).getHeight()));
        sqlRunInput.setLineWrap(false);
        sqlRunInput.setText("SELECT 1 AS 连通检查;");
        // 两颗清空各归各的 pane：只清结果区时不必丢掉刚敲了一半的语句，反过来也一样
        sqlRunHelpBtn = new javax.swing.JButton("示例 (?)");
        sqlRunHelpBtn.setToolTipText("打开示例清单：选中一条可复制到剪贴板或插到编辑区");
        sqlRunHelpBtn.addActionListener(e -> openSqlSamples());
        sqlRunClearInputBtn = new javax.swing.JButton("清空编辑区");
        sqlRunClearInputBtn.setToolTipText("只清掉左边这段 SQL，右侧结果区不动");
        sqlRunClearInputBtn.addActionListener(e -> sqlRunInput.setText(""));
        // 编辑区这一路的动作按钮贴在它自己那一栏头上：只管编辑区里这段 SQL，
        // 与脚本清单那一路的「批量执行脚本文件」分在两处，点哪颗跑什么一眼分得清
        sqlRunBtn = new javax.swing.JButton("执行编辑区 SQL");
        Buttons.secondary(sqlRunBtn);
        sqlRunBtn.setToolTipText("执行左边编辑区里的 SQL：按分号切句逐条执行，"
                + "全部成功才提交，中途出错整批回滚；与脚本文件清单互不影响");
        sqlRunBtn.addActionListener(e -> runSqlStatements());
        javax.swing.JPanel leftHead = new javax.swing.JPanel(new java.awt.BorderLayout());
        sqlRunEditorHead = leftHead;
        leftHead.add(new javax.swing.JLabel("SQL 文本"), java.awt.BorderLayout.WEST);
        javax.swing.JPanel leftEast = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, UiScale.px(6), 0));
        leftEast.add(sqlRunHelpBtn);
        leftEast.add(sqlRunClearInputBtn);
        leftEast.add(sqlRunBtn);
        leftHead.add(leftEast, java.awt.BorderLayout.EAST);
        javax.swing.JPanel left = new javax.swing.JPanel(new java.awt.BorderLayout(
                0, UiScale.px(2)));
        left.add(leftHead, java.awt.BorderLayout.NORTH);
        left.add(new javax.swing.JScrollPane(sqlRunInput), java.awt.BorderLayout.CENTER);
        left.setMinimumSize(new Dimension(0,
                sqlRunInput.getMinimumSize().height + UiScale.px(34)));

        sqlRunLog = new LogPanel(SQL_RUN_FONT_SIZE);
        sqlRunResultTable = new javax.swing.JTable(sqlRunModel);
        Tables.style(sqlRunResultTable);
        Tables.zebra(sqlRunResultTable);
        sqlRunResultTable.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_OFF);
        sqlRunResultTable.getTableHeader().setReorderingAllowed(false);
        // 字段注释按列挂表头 title：Swing 的表头 tooltip 是整条一个，只能自己按鼠标落在
        // 哪一列来换——用户鼠标停在 DDJE 上就该看到"这列是订单金额"
        sqlRunResultTable.getTableHeader().addMouseMotionListener(
                new javax.swing.event.MouseInputAdapter() {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent e) {
                        updateSqlRunHeaderTip(e.getPoint());
                    }
                });
        // 表头一开就带一句引导：注释是逐列挂上去的，还没结果时先说清该去哪儿看
        sqlRunResultTable.getTableHeader().setToolTipText(RESULT_HEADER_HINT);
        // 没有结果集（一批全是写语句）时不摆空表：这一页初开的三段高度是钉住的契约
        sqlRunResultTable.setVisible(false);
        sqlRunCaption = new javax.swing.JLabel("还没有执行结果");
        sqlRunSets = new javax.swing.JComboBox<String>();
        sqlRunSets.setVisible(false);
        sqlRunSets.addActionListener(e -> showSqlRunSet(sqlRunSets.getSelectedIndex()));
        sqlRunClearLogBtn = new javax.swing.JButton("清空结果区");
        sqlRunClearLogBtn.setToolTipText("只清掉右边的执行结果，左边编辑区不动");
        sqlRunClearLogBtn.addActionListener(e -> clearSqlRunResult());
        javax.swing.JPanel runHead = new javax.swing.JPanel(new java.awt.BorderLayout());
        javax.swing.JPanel runHeadWest = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, UiScale.px(6), 0));
        runHeadWest.add(new javax.swing.JLabel("执行结果"));
        runHeadWest.add(sqlRunCaption);
        javax.swing.JPanel runHeadEast = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(6), 0));
        runHeadEast.add(sqlRunClearLogBtn);
        runHeadEast.add(sqlRunSets);
        runHead.add(runHeadWest, java.awt.BorderLayout.WEST);
        runHead.add(runHeadEast, java.awt.BorderLayout.EAST);
        javax.swing.JPanel right = new javax.swing.JPanel(new java.awt.BorderLayout(
                0, UiScale.px(2)));
        // 结果区也要顶得住：缩成两行的结果区等于没有
        right.setMinimumSize(new java.awt.Dimension(UiScale.px(300),
                sqlRunLog.getMinimumSize().height + UiScale.px(34)));
        right.add(runHead, java.awt.BorderLayout.NORTH);
        // 表格与逐条回执之间也给一条可拖的分隔条：看数据还是看报错正文由人自己拖
        sqlRunResultSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT,
                new javax.swing.JScrollPane(sqlRunResultTable),
                new javax.swing.JScrollPane(sqlRunLog));
        sqlRunResultSplit.setDividerSize(UiScale.px(6));
        sqlRunResultSplit.setResizeWeight(0.6D);
        sqlRunResultSplit.setMinimumSize(new Dimension(0,
                sqlRunLog.getMinimumSize().height + UiScale.px(34)));
        right.add(sqlRunResultSplit, java.awt.BorderLayout.CENTER);

        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.45D);
        split.setMinimumSize(new Dimension(0,
                sqlRunLog.getMinimumSize().height + UiScale.px(34)));

        // 三段落上下排：拖拽区 / 脚本清单 / 编辑区与结果。清单与编辑区同权，
        // 拖拽区按第一页的口径只占固定一份，不跟着窗口变高
        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queueBox, split);
        lower.setDividerSize(UiScale.px(6));
        lower.setResizeWeight(1.0D / 3.0D);
        lower.setDividerLocation(UiScale.px(150));
        JSplitPane stacked = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sqlRunDrop, lower);
        stacked.setDividerSize(UiScale.px(6));
        stacked.setResizeWeight(0.0D);
        stacked.setDividerLocation(UiScale.px(110));
        page.add(stacked, java.awt.BorderLayout.CENTER);

        sqlRunSplitTop = stacked;
        sqlRunSplitLower = lower;
        sqlRunDropPane = sqlRunDrop;
        sqlRunQueuePane = queueBox;
        sqlRunEditorPane = split;
        // 与第一页同一套：真拿到尺寸后按 110:670 摆拖拽区，清单吃余量、末段只留最小高度；
        // 两条分隔条都听 Resize，切到这一页排过一次版才量得准
        java.awt.event.ComponentAdapter runPlacer =
                new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent e) {
                        applyRunSplitProportions();
                    }
                };
        stacked.addComponentListener(runPlacer);
        lower.addComponentListener(runPlacer);
        watchDivider(stacked, true);
        watchDivider(lower, true);

        // 这一页不再有底部动作条：「批量执行脚本文件」回到脚本清单那一排、
        // 「执行编辑区 SQL」贴到编辑区栏头，覆盖开关按用户口径整行删掉——
        // 剩下的空间全部让给 SQL 文本与执行结果，两段一直铺到窗口底部。
        return page;
    }

    /** pane 的头一行：左边是标题，右边那颗「清空…」只管这一边。 */
    private static javax.swing.JComponent paneHeader(String title, javax.swing.JButton clearBtn) {
        javax.swing.JPanel row = new javax.swing.JPanel(new java.awt.BorderLayout());
        row.add(new javax.swing.JLabel(title), java.awt.BorderLayout.WEST);
        javax.swing.JPanel east = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        east.add(clearBtn);
        row.add(east, java.awt.BorderLayout.EAST);
        return row;
    }

    /** 两颗清空按钮：自检与对撞器按这两个口取证，不自己去找控件。 */
    public javax.swing.JButton sqlRunClearInputButton() {
        return sqlRunClearInputBtn;
    }

    public javax.swing.JButton sqlRunClearLogButton() {
        return sqlRunClearLogBtn;
    }

    /** 结果区本体：自检与对撞器要能数它有几行，"清没清掉"得看得见。 */
    public LogPanel sqlRunLogPanel() {
        return sqlRunLog;
    }

    /**
     * 收一个结果集，超过上限就丢掉最早那一个并记账。
     *
     * 单独抽出来是为了让"丢的是最前面那条"这件事能被机器判到：工作线程里那段匿名
     * 回调要跑满 51 个结果集才会触发，没有库的机器上等不到那一刻。
     */
    public static void keepLatestSet(
            java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> sets,
            int[] dropped, com.excel2mysql.core.SqlExecute.ResultSetData data) {
        if (sets.size() >= MAX_RESULT_SETS) {
            sets.remove(0);
            dropped[0]++;
        }
        sets.add(data);
    }

    /** 一批里最多留多少个结果集给表格回看（与 Python 侧 MAX_RESULT_SETS 同值）。 */
    public static final int MAX_RESULT_SETS = 50;

    /** 结果表表头 title 的前缀（与 Python 侧 HEADER_COMMENT_PREFIX 同一句）。 */
    public static final String HEADER_COMMENT_PREFIX = "字段注释：";

    /** 没有结果集时挂在整条表头上的引导（有结果后换成逐列的字段注释）。 */
    public static final String RESULT_HEADER_HINT =
            "「SQL 执行器」的结果表：列名来自查询本身，字段注释按列挂在各自表头的 title 上，"
                    + "鼠标停在列名上即见";

    /** 结果表格：自检与对撞器按这个口取证。 */
    public javax.swing.JTable sqlRunResultTable() {
        return sqlRunResultTable;
    }

    public String sqlRunCaptionText() {
        return sqlRunCaption.getText();
    }

    public int sqlRunSetCount() {
        return sqlRunSetList.size();
    }

    public boolean sqlRunChooserVisible() {
        return sqlRunSets.isVisible();
    }

    public int sqlRunChooserItemCount() {
        return sqlRunSets.getModel().getSize();
    }

    public String sqlRunChooserItemText(int index) {
        return sqlRunSets.getModel().getElementAt(index);
    }

    /** 结果区那一段分栏（表格／逐条回执）：自检量"跑出了数据有没有给表格腾地方"。 */
    public javax.swing.JSplitPane sqlRunResultSplit() {
        return sqlRunResultSplit;
    }

    /** 表格的可视宽度：装不装得下内容要拿它跟列宽之和比，而不是只看开关。 */
    public boolean sqlRunColumnFitsContent(int col) {
        return Tables.columnFitsContent(sqlRunResultTable, col);
    }

    /** 列宽是否关掉了自适应：开着自适应时长列会被挤成省略号，横向条永远出不来。 */
    public boolean sqlRunScrollsHorizontally() {
        return sqlRunResultTable.getAutoResizeMode() == javax.swing.JTable.AUTO_RESIZE_OFF;
    }

    /**
     * 结果表格让不让编辑：问的是模型，不是 JTable。
     *
     * JTable.isCellEditable 会先把列号换成 model 列，空表（还没跑出结果集）时
     * 那一步直接抛 ArrayIndexOutOfBounds——取证这一侧不该依赖"已经查过一条"。
     */
    public boolean sqlRunCellsEditable() {
        return sqlRunModel.isCellEditable(0, 0);
    }

    /**
     * 自检与双端对撞用：不连库，直接把一批结果集铺进表格。
     *
     * 走的就是执行收尾时那个回调，只是数据由调用方给——这样在没有 MySQL 的机器上
     * 也能验到"表格真被填了、下拉真跟着条数变"，而不是一句编译通过。
     */
    public void feedSqlRunSets(
            java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> sets, int dropped) {
        showSqlRunSets(sets, dropped);
    }

    /** 自检与双端对撞用：造一条结果集，字段与取值都由调用方给。 */
    public static com.excel2mysql.core.SqlExecute.ResultSetData newResultSet(
            int seq, String sql, String[] columns, String[][] rows) {
        return newResultSet(seq, sql, columns, null, rows);
    }

    /** 同上，多给一串列注释（与 columns 等长，空位表示这一列没有注释）。 */
    public static com.excel2mysql.core.SqlExecute.ResultSetData newResultSet(
            int seq, String sql, String[] columns, String[] comments, String[][] rows) {
        com.excel2mysql.core.SqlExecute.ResultSetData data =
                new com.excel2mysql.core.SqlExecute.ResultSetData();
        data.seq = seq;
        data.sql = sql;
        for (String c : columns) {
            data.columns.add(c);
        }
        if (comments != null) {
            for (String c : comments) {
                data.comments.add(c);
            }
        }
        for (String[] r : rows) {
            data.rows.add(java.util.Arrays.asList(r));
        }
        data.total = rows.length;
        data.printed = rows.length;
        return data;
    }

    /** 自检用：切到第几个结果集，与用户点下拉走的是同一个方法。 */
    public void selectSqlRunSet(int index) {
        sqlRunSets.setSelectedIndex(index);
    }

    /** 清空结果区：回执文本、表格与结果集下拉一起清，留半个就是假清完。 */
    private void clearSqlRunResult() {
        sqlRunLog.clearLog();
        // 表格那一半走同一个收口：两条路各写一遍，清完的状态就会不一样（比如表头那句引导）
        clearSqlRunTable("还没有执行结果");
    }

    /**
     * 一批跑完：结果集交给表格。回执文本已经由 sink 逐条写好，这里只管数据。
     * 只有一条时不摆下拉——多一个永远只有一个选项的控件是占地方，不是给功能。
     */
    void showSqlRunSets(
            java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> sets, int dropped) {
        if (sets == null || sets.isEmpty()) {
            clearSqlRunTable("这一批没有返回结果集的语句（都是写入或建表）");
            return;
        }
        sqlRunSetList = sets;
        sqlRunSets.removeAllItems();
        for (com.excel2mysql.core.SqlExecute.ResultSetData s : sets) {
            String one = s.sql.length() > 60 ? s.sql.substring(0, 60) + "…" : s.sql;
            sqlRunSets.addItem("第 " + s.seq + " 条 · " + one);
        }
        sqlRunSets.setVisible(sets.size() > 1);
        sqlRunResultTable.setVisible(true);
        showSqlRunSet(sets.size() - 1);
        giveResultRoom();
        if (dropped > 0) {
            sqlRunLog.log("[提示] 这一批的结果集多于 " + MAX_RESULT_SETS + " 个，"
                    + "只保留最近 " + MAX_RESULT_SETS + " 个回看，更早的 " + dropped
                    + " 个已丢弃");
        }
    }

    /** 只收表格与说明，不动回执文本：清文本和清表格是两件事，别互相牵连。 */
    private void clearSqlRunTable(String caption) {
        sqlRunSetList = new java.util.ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
        sqlRunSets.removeAllItems();
        sqlRunSets.setVisible(false);
        sqlRunModel.setDataVector(new Object[0][0], new Object[0]);
        sqlRunResultTable.setVisible(false);
        setSqlRunHeaderTips(null, 0);
        // 整条表头给一句引导：字段注释是逐列挂的，可这里一列都没有，空表头上至少说清去哪儿看
        sqlRunResultTable.getTableHeader().setToolTipText(RESULT_HEADER_HINT);
        sqlRunCaption.setText(caption);
    }

    /** 把选中的那一个结果集铺进表格。 */
    void showSqlRunSet(int index) {
        if (sqlRunSetList.isEmpty()) {
            return;
        }
        if (index < 0 || index >= sqlRunSetList.size()) {
            index = sqlRunSetList.size() - 1;
        }
        com.excel2mysql.core.SqlExecute.ResultSetData data = sqlRunSetList.get(index);
        Object[] head = data.columns.toArray(new Object[0]);
        Object[][] body = new Object[data.rows.size()][];
        for (int r = 0; r < body.length; r++) {
            body[r] = data.rows.get(r).toArray(new Object[0]);
        }
        sqlRunModel.setDataVector(body, head);
        setSqlRunHeaderTips(data.comments, head.length);
        // 表头换成逐列挂注释，整条那句"还没有结果"的引导就撤掉
        sqlRunResultTable.getTableHeader().setToolTipText(null);
        // 列宽随内容：与两个字段弹窗同一算法（下限 70，长的撑宽后由横向条带出来）
        sqlRunResultTable.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_OFF);
        int[] floors = new int[head.length];
        java.util.Arrays.fill(floors, UiScale.px(70));
        if (floors.length > 0) {
            Tables.fitColumns(sqlRunResultTable, floors);
        }
        String caption = "第 " + data.seq + " 条 · 共 " + data.total + " 行";
        if (data.printed < data.total) {
            caption += "（表格只装前 " + data.printed + " 行，其余没取回来）";
        }
        sqlRunCaption.setText(caption);
    }

    /**
     * 结果表表头的逐列 title：第 i 格是第 i 列的字段注释（没有就是空串）。
     *
     * 这一页查出来的多是拼音首字母列名（DDJE 这种），光看列名读不出含义，
     * 注释只有服务端知道，由 core 随结果集一起取回来。与 Python 侧
     * main.py 的 _show_sql_run_set 同一句前缀。
     */
    void setSqlRunHeaderTips(java.util.List<String> comments, int cols) {
        sqlRunHeaderTips = new String[cols];
        for (int i = 0; i < cols; i++) {
            String c = comments != null && i < comments.size() ? comments.get(i) : null;
            sqlRunHeaderTips[i] = c == null || c.isEmpty() ? "" : HEADER_COMMENT_PREFIX + c;
        }
    }

    /** 自检用：第 col 列表头该挂的那句 title。 */
    public String sqlRunHeaderTipAt(int col) {
        return col >= 0 && col < sqlRunHeaderTips.length ? sqlRunHeaderTips[col] : "";
    }

    /** 鼠标停在表头哪一列，就把那一列的字段注释挂上去；没有注释的列不留提示。 */
    private void updateSqlRunHeaderTip(java.awt.Point p) {
        int view = sqlRunResultTable.columnAtPoint(p);
        int model = view < 0 ? -1 : sqlRunResultTable.convertColumnIndexToModel(view);
        String tip = sqlRunHeaderTipAt(model);
        sqlRunResultTable.getTableHeader().setToolTipText(tip.isEmpty() ? null : tip);
    }

    /**
     * 跑出了结果集就把底部那一段撑开。
     *
     * 初开时这一段压在自己的最小高度、把高度让给脚本清单——那是空页面的分配，
     * 不是有数据时的分配：点「执行」的人要看的是那几行数据，两行高的表格等于没有。
     * 已经够高就不动，用户自己拖过的比例也不覆盖。
     */
    private void giveResultRoom() {
        int total = sqlRunSplitLower.getHeight();
        if (total <= 0) {
            return;
        }
        int now = total - sqlRunSplitLower.getDividerLocation();
        if (now * 100 >= total * 40) {
            return;
        }
        int bottom = (int) (total * 0.45D);
        int rest = total - bottom;
        sqlRunSplitLower.setDividerLocation(rest - (int) (rest * 0.31D));
    }

    /** 执行编辑区里那段 SQL。事务与回滚语义走 SqlExecute，与「SQL 脚本改名」页同一套。 */
    private void runSqlStatements() {
        final String text = sqlRunInput.getText();
        if (text == null || text.trim().isEmpty()) {
            warn("没有可执行的 SQL：编辑区是空的（只写注释也不算）");
            return;
        }
        final com.excel2mysql.core.Config cfg = currentConfig();
        // 结果集先收到一个列表里（工作线程只碰数据、不碰控件），收尾时再由 EDT 铺进表格
        final java.util.List<com.excel2mysql.core.SqlExecute.ResultSetData> sets =
                new java.util.ArrayList<com.excel2mysql.core.SqlExecute.ResultSetData>();
        final int[] dropped = {0};
        com.excel2mysql.core.SqlExecute.Results collect =
                new com.excel2mysql.core.SqlExecute.Results() {
                    @Override
                    public void accept(com.excel2mysql.core.SqlExecute.ResultSetData data) {
                        synchronized (sets) {
                            keepLatestSet(sets, dropped, data);
                        }
                    }
                };
        runTask("正在执行 SQL…", sink -> {
            com.excel2mysql.core.DbClient db = null;
            try {
                db = new com.excel2mysql.core.DbClient(cfg);
                final com.excel2mysql.core.DbClient fdb = db;
                // 逐条推进度、每句查一次取消：一屏上千条语句的批次，
                // 进度条停在 0%、点了停止没反应，用户就只能等或者强关窗口
                final int[] reported = {0};
                return com.excel2mysql.core.SqlExecute.run(fdb, text, sink::log,
                        (done, total) -> {
                            sink.setTotal(total);
                            sink.advance(done - reported[0], "第 " + done + "/" + total + " 条");
                            reported[0] = done;
                        }, () -> sink.cancelled(), collect);
            } catch (java.sql.SQLException e) {
                sink.logError("x [ERROR] 连不上库 | "
                        + com.excel2mysql.core.LogText.describe(e));
                return null;
            } finally {
                if (db != null) {
                    db.close();
                }
            }
        }, res -> {
            // 结果与失败原因都已经写在右侧结果区里，这里不再弹窗打断：
            // 这一页是给人来回试语句的，每试一次弹一次模态框会点不完
            synchronized (sets) {
                showSqlRunSets(new java.util.ArrayList<
                        com.excel2mysql.core.SqlExecute.ResultSetData>(sets), dropped[0]);
            }
        }, sqlRunLog);
    }

    /** 第三页队列里的一行：只登记路径与大小，正文等轮到它执行时再读。 */
    private static final class RunScript {
        final String path;
        final long size;
        String status = "待执行";

        RunScript(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    /** 点虚线区或右键菜单的选择器：只认 .sql，多选。 */
    private void chooseRunScripts() {
        JFileChooser chooser = new FolderAwareChooser();
        chooser.setDialogTitle("选择 SQL 脚本");
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(true);
        FileNameExtensionFilter sql = new FileNameExtensionFilter("SQL 脚本 (*.sql)", "sql");
        chooser.setFileFilter(sql);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<String> paths = new ArrayList<String>();
        for (File f : chooser.getSelectedFiles()) {
            paths.add(f.getPath());
        }
        addRunScriptPaths(paths);
    }

    /** 拖进来的路径只收 .sql；混着别的文件时点名哪些没进去。 */
    private void onRunScriptsDropped(List<String> paths) {
        List<String> keep = new ArrayList<String>();
        List<String> wrong = new ArrayList<String>();
        for (String p : paths) {
            if (p != null && p.toLowerCase().endsWith(".sql")) {
                keep.add(p);
            } else if (p != null) {
                wrong.add(p);
            }
        }
        if (keep.isEmpty()) {
            sqlRunLog.log("[提示] 拖入或粘贴的不是 SQL 脚本文件，已忽略。");
            return;
        }
        if (!wrong.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : wrong) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(baseName(p));
            }
            sqlRunLog.log("[跳过] 不是 SQL 脚本，未读入：" + sb);
        }
        addRunScriptPaths(keep);
    }

    /** 登记脚本路径；重复的并入清单不动，读不到大小的点名未加入。 */
    private void addRunScriptPaths(List<String> files) {
        List<RunScript> added = new ArrayList<RunScript>();
        List<String> dups = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (String f : files) {
            boolean dup = false;
            for (RunScript s : sqlRunFiles) {
                if (s.path.equals(f)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                dups.add(f);
                continue;
            }
            File file = new File(f);
            if (!file.isFile()) {
                failed.add(f);
                continue;
            }
            RunScript row = new RunScript(f, file.length());
            sqlRunFiles.add(row);
            added.add(row);
        }
        refreshRunTable();
        StringBuilder lines = new StringBuilder();
        for (RunScript s : added) {
            lines.append("[已加入] ").append(s.path).append(LogText.NL);
        }
        for (String p : dups) {
            lines.append("[跳过] ").append(p).append("：已在列表").append(LogText.NL);
        }
        for (String p : failed) {
            lines.append("[未加入] ").append(p).append("：文件不存在或无法访问")
                    .append(LogText.NL);
        }
        String summary = "===== 添加完成：" + added.size() + " 个脚本，"
                + failed.size() + " 个无法访问，" + dups.size() + " 个重复 =====";
        if (failed.isEmpty()) {
            sqlRunLog.log(summary);
        } else {
            sqlRunLog.error(summary);
        }
        if (lines.length() == 0) {
            return;                     // 全是重复时也要弹一次：用户会问"点了怎么没反应"
        }
        dialog("添加结果", lines.toString().trim(),
                failed.isEmpty() ? javax.swing.JOptionPane.INFORMATION_MESSAGE
                        : javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    /** 清单表按模型重画：序号、文件名、大小、状态，一行对一行。 */
    private void refreshRunTable() {
        sqlRunFilesModel.setRowCount(0);
        for (int i = 0; i < sqlRunFiles.size(); i++) {
            RunScript s = sqlRunFiles.get(i);
            sqlRunFilesModel.addRow(new Object[]{String.valueOf(i + 1),
                    baseName(s.path), FileSizes.format(s.size), s.status, ""});
        }
        syncRunStates();
    }

    /** 与第一页「清空待上传文件」同一语义：整份清出清单，磁盘上的脚本不动。 */
    private void clearRunScripts() {
        int n = sqlRunFiles.size();
        sqlRunFiles.clear();
        refreshRunTable();
        if (n > 0) {
            sqlRunLog.log("[清空] 已清空 " + n + " 个待执行脚本");
        } else {
            sqlRunLog.log("[清空] 清单里没有脚本文件，无需清空");
        }
    }

    /** 按清单顺序执行全部脚本文件；手写脚本每个文件一个事务，整库 dump 逐条提交。 */
    private void executeRunScripts() {
        final Config cfg = currentConfig();
        if (cfg == null || !cfg.hasDatabase()) {
            warn(LogText.NO_DB_HINT);
            return;
        }
        if (sqlRunFiles.isEmpty()) {
            warn("请先添加 SQL 脚本文件。");
            return;
        }
        sqlRunLog.log("===== 开始执行脚本文件（目标库 " + cfg.database + "，"
                + sqlRunFiles.size() + " 个文件；手写脚本每个文件一个事务，"
                + "整库 dump 逐条提交）=====");
        final List<String> paths = new ArrayList<String>();
        for (RunScript s : sqlRunFiles) {
            paths.add(s.path);
            s.status = "待执行";
        }
        refreshRunTable();
        // 这一页不再补 DROP：覆盖开关按用户口径整行删掉了，脚本正文怎么写就怎么执行
        runTask("正在执行脚本文件到目标库…",
                sink -> com.excel2mysql.core.SqlFileRun.run(paths, false, cfg, sink),
                out -> finishRunScripts((com.excel2mysql.core.SqlFileRun.Result) out),
                sqlRunLog);
    }

    /**
     * 行内那颗【执行】：只跑这一行的脚本文件，跑法与「批量执行脚本文件」同一套。
     *
     * Swing 的单元格是画出来的（没有真控件常驻），所以入口是表格上的单击命中"操作"列；
     * 自检查的是"点了这一行、只有这一行跑"，与按钮那条路同一判据。
     */
    private void runRowScript(int row) {
        if (row < 0 || row >= sqlRunFiles.size()) {
            warn("这一行已经不在清单里了，请重新选中要执行的脚本。");
            return;
        }
        if (!isRowExecutable()) {
            warn("这一页正在跑任务，或者清单已经空了，行内【执行】先不接。");
            return;
        }
        final Config cfg = currentConfig();
        if (cfg == null || !cfg.hasDatabase()) {
            warn(LogText.NO_DB_HINT);
            return;
        }
        final RunScript one = sqlRunFiles.get(row);
        one.status = "待执行";
        refreshRunTable();
        sqlRunLog.log("===== 开始执行脚本文件（目标库 " + cfg.database + "，1 个文件；"
                + "手写脚本每个文件一个事务，整库 dump 逐条提交）=====");
        runTask("正在执行脚本文件到目标库…",
                sink -> com.excel2mysql.core.SqlFileRun.run(
                        java.util.Collections.singletonList(one.path), false, cfg, sink),
                out -> finishRunScripts((com.excel2mysql.core.SqlFileRun.Result) out),
                sqlRunLog);
    }

    /** 行内【执行】与「批量执行脚本文件」共用那一套判据：有脚本、且这一页没任务在跑。 */
    public boolean isRowExecutable() {
        return !sqlRunFiles.isEmpty() && !taskBusy(RES_RUN);
    }

    /**
     * Del 键：把选中的行从待执行清单里去掉，多选就是一次批量删。
     *
     * 与第一页同一口径：只出清单，磁盘上的脚本一个字不动；这一页有任务在跑时拦下来，
     * 半途抽走正在执行的那一行会让任务回报的项数对不上剩余清单。
     */
    public void deleteSelectedRunScripts() {
        Task other = conflict(RES_RUN);
        if (other != null) {
            warn(busyText(other));
            return;
        }
        int[] selected = sqlRunFilesTable.getSelectedRows();
        List<Integer> rows = new ArrayList<Integer>();
        for (int r : selected) {
            if (r >= 0 && r < sqlRunFiles.size()) {
                rows.add(Integer.valueOf(r));
            }
        }
        if (rows.isEmpty()) {
            sqlRunFilesTable.clearSelection();
            sqlRunLog.log("[删除] 没有选中脚本，先在清单里点一行再按 Del");
            return;
        }
        java.util.Collections.sort(rows, java.util.Collections.reverseOrder());
        StringBuilder names = new StringBuilder();
        for (Integer r : rows) {
            if (names.length() > 0) {
                names.insert(0, "、");
            }
            // 倒着删是为了让下标不失效，回执里那串名字要按清单原顺序念出来
            names.insert(0, new java.io.File(sqlRunFiles.get(r.intValue()).path).getName());
            sqlRunFiles.remove(r.intValue());
        }
        refreshRunTable();
        sqlRunLog.log("[删除] 已移除 " + names + "，清单剩 " + sqlRunFiles.size()
                + " 个待执行脚本");
    }

    /** 自检与对撞器按这个口点行内那颗「执行」。 */
    public void pressRowExecute(int row) {
        runRowScript(row);
    }

    /** 自检用：这一行的「操作」格画出来是不是那颗【执行】按钮。 */
    public String rowExecuteLabel(int row) {
        if (sqlRunRowExec == null || row < 0 || row >= sqlRunFilesModel.getRowCount()) {
            return "";
        }
        return sqlRunRowExec.getText();
    }

    public int getRowCountForRunTable() {
        return sqlRunFilesModel.getRowCount();
    }

    /** 收尾：先把逐文件结局写回清单，再出一行汇总与一颗结果弹窗。 */
    private void finishRunScripts(com.excel2mysql.core.SqlFileRun.Result res) {
        java.util.Map<String, String> labels = new java.util.HashMap<String, String>();
        labels.put("ok", "成功");
        labels.put("fail", "失败");
        labels.put("skip", "跳过");
        labels.put("cancel", "已取消");
        java.util.Map<String, String> byPath = new java.util.HashMap<String, String>();
        for (com.excel2mysql.core.SqlFileRun.FileOutcome f : res.files) {
            byPath.put(f.path, labels.containsKey(f.status) ? labels.get(f.status) : f.status);
        }
        for (RunScript s : sqlRunFiles) {
            String outcome = byPath.get(s.path);
            if (outcome != null) {
                s.status = outcome;
            }
        }
        refreshRunTable();
        if (res.connFailed) {
            return;                     // 连不上库时已经报过详细原因，不再重复汇总
        }
        String note = res.cancelled ? "（已取消，剩余脚本未执行）" : "";
        String line = "===== 执行完成：成功 " + res.ok + " 个文件，失败 " + res.fail
                + " 个文件" + note + " =====";
        if (res.fail > 0) {
            sqlRunLog.error(line);
        } else {
            sqlRunLog.log(line);
        }
        StringBuilder body = new StringBuilder();
        body.append("成功 ").append(res.ok).append(" 个文件，失败 ").append(res.fail)
                .append(" 个文件").append(note).append("。").append(LogText.NL)
                .append("目标库 ").append(res.target);
        for (com.excel2mysql.core.SqlFileRun.FileOutcome f : res.files) {
            body.append(LogText.NL).append("[")
                    .append(labels.containsKey(f.status) ? labels.get(f.status) : f.status)
                    .append("] ").append(f.path).append("：").append(f.detail);
        }
        body.append(LogText.NL).append("详见日志。");
        dialog("执行完成", body.toString(),
                res.fail > 0 ? javax.swing.JOptionPane.WARNING_MESSAGE
                        : javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * 「示例 (?)」打开的那个窗口：左边搜关键词 + 目录，右边上方介绍、下方语句。
     *
     * 用的是 JFrame 而不是 JDialog：AWT 的 Dialog 没有 setExtendedState，
     * Windows 上那颗框也只有关闭叉——最小化/最大化两件事在对话框上根本做不出来。
     * 不认主窗口当 owner，它才单占任务栏一格、主窗口最小化时不跟着藏；
     * 这张清单正是开着用的。与 Python 侧 SqlSamplesDialog 的 Qt.Window 同一口径。
     */
    private void openSqlSamples() {
        if (sqlRunSamples != null && sqlRunSamples.isShowing()) {
            sqlRunSamples.toFront();
            return;
        }
        final javax.swing.JFrame dlg = new javax.swing.JFrame(
                "SQL 示例（" + SqlSamples.ALL.length + " 条）");
        dlg.setDefaultCloseOperation(javax.swing.JFrame.HIDE_ON_CLOSE);

        // 目录分两层（分组 → 短标题）而不是把上百条平铺在第一层：示例只会越加越多，
        // 平铺时找一条要滚半天；上面再给一行关键词过滤，常用路径就是"搜一下、点第一条"
        sqlRunSamplesTree = new javax.swing.JTree();
        sqlRunSamplesTree.setRootVisible(false);
        sqlRunSamplesTree.setShowsRootHandles(true);
        // JTree 默认就是单选（SINGLE_TREE_SELECTION），与 Python 侧的目录同一行为
        sqlRunSamplesSearch = new javax.swing.JTextField();
        sqlRunSamplesSearch.setToolTipText("关键词过滤：短标题、说明、场景、解决的问题、"
                + "关键词与语句都算（清空回到全部）");
        sqlRunSamplesHits = new javax.swing.JLabel();
        final javax.swing.JScrollPane treeScroll = new javax.swing.JScrollPane(sqlRunSamplesTree);

        sqlRunIntro = new javax.swing.JTextArea(6, 60);
        sqlRunIntro.setEditable(false);
        sqlRunIntro.setLineWrap(true);
        sqlRunIntro.setWrapStyleWord(true);
        sqlRunIntro.setFont(com.excel2mysql.gui.Tables.font());
        sqlRunPreview = new javax.swing.JTextArea(12, 60);
        sqlRunPreview.setEditable(false);
        // 与编辑区同一条口径：长行交给横向滚动，不把一句 SQL 折成三行
        sqlRunPreview.setLineWrap(false);
        // 目录与介绍是界面基准那一档，只有语句正文按日志档（LogPanel.DEFAULT_FONT_SIZE）：
        // 这是照着挑的阅读弹窗，不跟编辑区那档 16 号——16 号在并排清单旁边显得突兀
        sqlRunPreview.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN,
                UiScale.fontPt(LogPanel.DEFAULT_FONT_SIZE)));
        final javax.swing.JScrollPane previewScroll = new javax.swing.JScrollPane(sqlRunPreview);
        sqlRunPreviewScroll = previewScroll;
        sqlRunSamplesTree.addTreeSelectionListener(e -> showSqlSample());

        javax.swing.JPanel buttons = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT,
                        UiScale.px(6), UiScale.px(2)));
        sqlRunCopyBtn = new javax.swing.JButton("复制到剪贴板");
        sqlRunCopyBtn.addActionListener(e -> {
            String receipt;
            try {
                java.awt.datatransfer.StringSelection sel =
                        new java.awt.datatransfer.StringSelection(sqlRunPreview.getText());
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                receipt = "· 已复制到剪贴板";
            } catch (RuntimeException ex) {
                // Windows 上剪贴板会被别的进程锁住，setContents 当场抛 IllegalStateException。
                // 让它冒到事件分发线程上，这颗按钮就变成"点了没反应"，
                // 所以失败也必须在标题上给一句回执。
                receipt = "· 复制没成功（剪贴板正被占用，可再点一次）";
            }
            dlg.setTitle("SQL 示例（" + SqlSamples.ALL.length + " 条）" + receipt);
        });
        sqlRunInsertBtn = new javax.swing.JButton("插入到编辑区");
        sqlRunInsertBtn.addActionListener(e -> {
            String cur = sqlRunInput.getText();
            String add = sqlRunPreview.getText();
            sqlRunInput.setText((cur == null || cur.trim().isEmpty() || "SELECT 1 AS 连通检查;"
                    .equals(cur.trim())) ? add : cur + (cur.endsWith(";") ? "" : ";")
                    + com.excel2mysql.core.LogText.NL + add);
            dlg.setVisible(false);
        });
        javax.swing.JButton closeBtn = new javax.swing.JButton("关闭");
        closeBtn.addActionListener(e -> dlg.setVisible(false));
        buttons.add(sqlRunCopyBtn);
        buttons.add(sqlRunInsertBtn);
        buttons.add(closeBtn);

        // 右侧上下两块：一条示例要回答两个问题——"什么时候用、替我挡掉什么"
        // 与"语句到底怎么写"，只看语句的人会把场景丢在手册里
        javax.swing.JPanel sqlBox = new javax.swing.JPanel(new java.awt.BorderLayout(
                0, UiScale.px(4)));
        sqlBox.add(new javax.swing.JLabel("语句全文（可全选复制）"), java.awt.BorderLayout.NORTH);
        sqlBox.add(previewScroll, java.awt.BorderLayout.CENTER);
        sqlBox.add(buttons, java.awt.BorderLayout.SOUTH);
        javax.swing.JSplitPane right = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT,
                new javax.swing.JScrollPane(sqlRunIntro), sqlBox);
        right.setDividerSize(UiScale.px(6));
        right.setResizeWeight(0.38D);

        javax.swing.JPanel left = new javax.swing.JPanel(new java.awt.BorderLayout(
                0, UiScale.px(2)));
        left.add(sqlRunSamplesSearch, java.awt.BorderLayout.NORTH);
        javax.swing.JPanel leftHead = new javax.swing.JPanel(new java.awt.BorderLayout());
        leftHead.add(new javax.swing.JLabel("示例目录（分组 → 条目）"),
                java.awt.BorderLayout.NORTH);
        leftHead.add(sqlRunSamplesHits, java.awt.BorderLayout.SOUTH);
        left.add(treeScroll, java.awt.BorderLayout.CENTER);
        left.add(leftHead, java.awt.BorderLayout.SOUTH);
        javax.swing.event.DocumentListener filter = new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterSqlSamples();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterSqlSamples();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterSqlSamples();
            }
        };
        sqlRunSamplesSearch.getDocument().addDocumentListener(filter);

        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.38D);
        dlg.getContentPane().add(split, java.awt.BorderLayout.CENTER);
        Windows.clientSize(dlg.getContentPane(), dlg, 980, 620);
        sqlRunSamples = dlg;
        filterSqlSamples();       // 建好目录并选中第一条：一开框右侧不是空白
        dlg.setVisible(true);
    }

    /** 目录里的一个节点：分组节点只带名字，叶子带短标题与示例序号。 */
    private static final class SampleNode {
        private final String label;
        private final int index;

        SampleNode(String label, int index) {
            this.label = label;
            this.index = index;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 按关键词重建目录：命中的叶子留下，整组没命中就不把这组挂上去。
     *
     * 不缓存命中结果、也不给 JTree 套 RowFilter（Swing 的树没有内建过滤），
     * 100 个节点重建一次的开销可以忽略；匹配走 SqlSamples.matches，
     * 与 Python 侧同一份字段拼法，同一个词两边必须命中同一批条目。
     */
    private javax.swing.tree.DefaultMutableTreeNode buildSamplesRoot(String needle) {
        javax.swing.tree.DefaultMutableTreeNode root =
                new javax.swing.tree.DefaultMutableTreeNode("示例目录");
        javax.swing.tree.DefaultMutableTreeNode group = null;
        int hits = 0;
        for (int i = 0; i < SqlSamples.ALL.length; i++) {
            SqlSamples.Sample s = SqlSamples.ALL[i];
            if (!SqlSamples.matches(s, needle)) {
                continue;
            }
            hits++;
            if (group == null || !s.group.equals(String.valueOf(group.getUserObject()))) {
                group = new javax.swing.tree.DefaultMutableTreeNode(s.group);
                root.add(group);
            }
            group.add(new javax.swing.tree.DefaultMutableTreeNode(new SampleNode(s.title, i)));
        }
        return root;
    }

    /** 搜索框每改一次：重建目录、展开有命中的组、选中第一条命中、把命中数说出来。 */
    private void filterSqlSamples() {
        String needle = sqlRunSamplesSearch == null ? "" : sqlRunSamplesSearch.getText();
        javax.swing.tree.DefaultMutableTreeNode root = buildSamplesRoot(needle);
        sqlRunSamplesTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
        int groups = root.getChildCount();
        int hits = 0;
        for (int i = 0; i < groups; i++) {
            javax.swing.tree.DefaultMutableTreeNode g =
                    (javax.swing.tree.DefaultMutableTreeNode) root.getChildAt(i);
            hits += g.getChildCount();
            sqlRunSamplesTree.expandPath(new javax.swing.tree.TreePath(
                    new Object[]{root, g}));
        }
        if (needle == null || needle.trim().isEmpty()) {
            sqlRunSamplesHits.setText("共 " + SqlSamples.ALL.length + " 条，分 "
                    + samplesGroupCount() + " 组");
        } else if (hits > 0) {
            sqlRunSamplesHits.setText("命中 " + hits + " 条（共 "
                    + SqlSamples.ALL.length + " 条）");
        } else {
            sqlRunSamplesHits.setText("没有命中：换个关键词，或清空搜索框回到全部 "
                    + SqlSamples.ALL.length + " 条");
        }
        if (hits > 0) {
            sqlRunSamplesTree.setSelectionRow(1);      // 第 0 行是隐藏根，第一条命中在第 1 行
        } else {
            sqlRunSamplesTree.clearSelection();
        }
    }

    /** 全部示例一共几个分组（不过滤时的分组数，说给命中那行用）。 */
    private static int samplesGroupCount() {
        int n = 0;
        String last = null;
        for (SqlSamples.Sample s : SqlSamples.ALL) {
            if (!s.group.equals(last)) {
                n++;
                last = s.group;
            }
        }
        return n;
    }

    /** 把选中那条示例的介绍与语句刷到右边两块。 */
    private void showSqlSample() {
        javax.swing.tree.TreePath path = sqlRunSamplesTree.getSelectionPath();
        Object node = path == null ? null : path.getLastPathComponent();
        Object held = node instanceof javax.swing.tree.DefaultMutableTreeNode
                ? ((javax.swing.tree.DefaultMutableTreeNode) node).getUserObject() : null;
        if (!(held instanceof SampleNode)) {
            return;                            // 点中的是分组那一行：右侧保持上一条示例
        }
        SqlSamples.Sample s = SqlSamples.ALL[((SampleNode) held).index];
        sqlRunIntro.setText(SqlSamples.introText(s));
        sqlRunPreview.setText(s.sql);
        // 换一条就把两侧滚回开头：上一条长文留下的滚动位置会让新语句
        // 看着像"缺了上半截"（与 Python 侧 _on_pick 的复位同一处理）
        SwingUtilities.invokeLater(() -> {
            sqlRunPreviewScroll.getHorizontalScrollBar().setValue(0);
            sqlRunPreviewScroll.getVerticalScrollBar().setValue(0);
            sqlRunIntro.setCaretPosition(0);
        });
    }

    /** 自检与对撞用：把选中那条示例刷到右侧（showSqlSample 的公开口）。 */
    public void selectSqlSample(int ordinal) {
        int seen = 0;
        javax.swing.tree.TreeModel model = sqlRunSamplesTree.getModel();
        Object root = model.getRoot();
        for (int g = 0; g < model.getChildCount(root); g++) {
            Object group = model.getChild(root, g);
            for (int c = 0; c < model.getChildCount(group); c++) {
                Object leaf = model.getChild(group, c);
                if (seen++ == ordinal) {
                    sqlRunSamplesTree.setSelectionPath(new javax.swing.tree.TreePath(
                            new Object[]{root, group, leaf}));
                    return;
                }
            }
        }
    }

    /** 给自检与双端对撞用：这一页的可观察量都从这几个口子出去。 */
    public String sqlRunInputText() {
        return sqlRunInput == null ? "" : sqlRunInput.getText();
    }

    /** 编辑区本体：自检要查它的 Ctrl+V 有没有改挂到粘贴分派上。 */
    public javax.swing.text.JTextComponent sqlRunInputComponent() {
        return sqlRunInput;
    }

    public void setSqlRunInputText(String text) {
        sqlRunInput.setText(text);
    }

    public String sqlRunResultText() {
        return sqlRunLog == null ? "" : sqlRunLog.text();
    }

    public javax.swing.JButton sqlRunButton() {
        return sqlRunBtn;
    }

    /** 第三页脚本队列：自检与对撞器按这几个口取证，不去摸控件树。 */
    public DropArea sqlRunDropArea() {
        return sqlRunDrop;
    }

    /** 「操作」列那颗【执行】渲染件（整列共用一个）。 */
    public javax.swing.JButton sqlRunRowExecButton() {
        return sqlRunRowExec;
    }

    /** 编辑区栏头：「执行编辑区 SQL」改挂到这里，自检按容器认，不按像素。 */
    public java.awt.Container sqlRunEditorHead() {
        return sqlRunEditorHead;
    }

    public javax.swing.JTable sqlRunFilesTable() {
        return sqlRunFilesTable;
    }

    public javax.swing.JButton sqlRunFilesClearButton() {
        return sqlRunFilesClearBtn;
    }

    public javax.swing.JButton sqlRunScriptsButton() {
        return sqlRunScriptsBtn;
    }

    /** 脚本清单下面那一排：左边「清空待执行脚本」、右边「批量执行脚本文件」。 */
    public java.awt.Container sqlRunActionRow() {
        return sqlRunQueueRow;
    }

    /** 两个页签的浅色底板：底色与"覆盖开关是否和主按钮同块"都由对撞器从真控件读。 */
    public ActionBar excelActionBar() {
        return excelActionBar;
    }

    public void addRunScriptsFromUi(List<String> paths) {
        addRunScriptPaths(paths);
    }

    public void clearRunScriptsFromUi() {
        clearRunScripts();
    }

    public void runScriptsFromUi() {
        executeRunScripts();
    }

    public int sqlRunFileCount() {
        return sqlRunFiles.size();
    }

    public String sqlRunFileStatus(int index) {
        return index >= 0 && index < sqlRunFiles.size() ? sqlRunFiles.get(index).status : "";
    }

    public javax.swing.JButton sqlRunHelpButton() {
        return sqlRunHelpBtn;
    }

    public int sqlSamplesCount() {
        return SqlSamples.ALL.length;
    }

    public String sqlSampleSql(int index) {
        return SqlSamples.ALL[index].sql;
    }

    public void showSqlSamples() {
        openSqlSamples();
    }

    public boolean sqlSamplesShowing() {
        return sqlRunSamples != null && sqlRunSamples.isShowing();
    }

    /** 这张清单的窗口形态：改得了大小、且不认主窗口当 owner，才是独立顶层窗口。 */
    public boolean sqlSamplesResizable() {
        return sqlRunSamples != null && sqlRunSamples.isResizable();
    }

    /** 有没有认主窗口当 owner：认了就单占不了任务栏一格、主窗口最小化时它跟着藏。 */
    public boolean sqlSamplesHasOwner() {
        return sqlRunSamples != null && sqlRunSamples.getOwner() != null;
    }

    /**
     * 真最大化一次再还原，回量最大化那一刻的窗口宽度。
     * Frame 上没有 isMaximizable 那种能力位（那是 Dialog 的），只能按下去量结果。
     */
    public int sqlSamplesMaximizeThenRestore() {
        if (sqlRunSamples == null) {
            return -1;
        }
        java.awt.Rectangle before = sqlRunSamples.getBounds();
        sqlRunSamples.setExtendedState(java.awt.Frame.MAXIMIZED_BOTH);
        int widened = sqlRunSamples.getWidth();
        sqlRunSamples.setExtendedState(java.awt.Frame.NORMAL);
        sqlRunSamples.setBounds(before);
        return widened;
    }

    /** 真最小化一次再还原，回读扩展状态：Dialog 连这个 API 都没有，走得通就说明是顶层窗口。 */
    public boolean sqlSamplesMinimizeThenRestore() {
        if (sqlRunSamples == null) {
            return false;
        }
        sqlRunSamples.setExtendedState(java.awt.Frame.ICONIFIED);
        boolean iconified = (sqlRunSamples.getExtendedState()
                & java.awt.Frame.ICONIFIED) != 0;
        sqlRunSamples.setExtendedState(java.awt.Frame.NORMAL);
        sqlRunSamples.setVisible(true);
        sqlRunSamples.toFront();
        return iconified;
    }

    /** 收起那份示例清单：它是非模态的，自检点完得自己收，不然会留在屏上挡后面的场景。 */
    public void closeSqlSamples() {
        if (sqlRunSamples != null) {
            sqlRunSamples.setVisible(false);
        }
    }

    public String sqlSamplesTitle() {
        return sqlRunSamples == null ? "" : sqlRunSamples.getTitle();
    }

    public void runSqlFromUi() {
        runSqlStatements();
    }

    public String sqlRunHintText() {
        return sqlRunHint == null ? "" : sqlRunHint.getText();
    }

    /** 第三页的介绍条本体：自检查三页是不是同一块底板。 */
    public javax.swing.JLabel sqlRunIntro() {
        return sqlRunHint;
    }

    /** 编辑区是否折行：两条线都要求"一句 SQL 不折行"，否则分号位置得数着看。 */
    public String sqlRunWrap() {
        return sqlRunInput != null && sqlRunInput.getLineWrap() ? "wrap" : "nowrap";
    }

    public java.awt.Font sqlRunInputFont() {
        return sqlRunInput == null ? null : sqlRunInput.getFont();
    }

    public java.awt.Font sqlRunPreviewFont() {
        return sqlRunPreview == null ? null : sqlRunPreview.getFont();
    }

    /** 目录里可见的第 n 个叶子（从 0 数）的短标题：与 Python 侧 tree 的第 n 个可见条目同口径。 */
    public String sqlSamplesItemText(int ordinal) {
        int seen = 0;
        javax.swing.tree.TreeModel model = sqlRunSamplesTree == null
                ? null : sqlRunSamplesTree.getModel();
        if (model == null) {
            return "";
        }
        Object root = model.getRoot();
        for (int g = 0; g < model.getChildCount(root); g++) {
            Object group = model.getChild(root, g);
            for (int c = 0; c < model.getChildCount(group); c++) {
                Object leaf = model.getChild(group, c);
                if (seen++ == ordinal) {
                    return String.valueOf(
                            ((javax.swing.tree.DefaultMutableTreeNode) leaf).getUserObject());
                }
            }
        }
        return "";
    }

    /** 目录第一层有几个分组（过滤后就是命中的组数）。 */
    public int sqlSamplesGroupCount() {
        return sqlRunSamplesTree == null ? -1
                : sqlRunSamplesTree.getModel().getChildCount(
                        sqlRunSamplesTree.getModel().getRoot());
    }

    /** 目录里当前可见的条目数（不过滤时等于总条数）。 */
    public int sqlSamplesVisibleCount() {
        int n = 0;
        javax.swing.tree.TreeModel model = sqlRunSamplesTree == null
                ? null : sqlRunSamplesTree.getModel();
        if (model == null) {
            return -1;
        }
        Object root = model.getRoot();
        for (int g = 0; g < model.getChildCount(root); g++) {
            n += model.getChildCount(model.getChild(root, g));
        }
        return n;
    }

    /** 命中那句提示的原文：过滤有没有把话说清楚，看这一句。 */
    public String sqlSamplesHitsText() {
        return sqlRunSamplesHits == null ? "" : sqlRunSamplesHits.getText();
    }

    /** 目录第一层当前可见的分组名（用 | 连），过滤后剩下的就是命中的那几组。 */
    public String sqlSamplesGroupTitles() {
        if (sqlRunSamplesTree == null) {
            return "";
        }
        javax.swing.tree.TreeModel model = sqlRunSamplesTree.getModel();
        Object root = model.getRoot();
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < model.getChildCount(root); g++) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(String.valueOf(model.getChild(root, g)));
        }
        return sb.toString();
    }

    /** 往搜索框里写关键词，走的正是用户敲字那条路（DocumentListener 自己会重筛）。 */
    public void typeSqlSamplesFilter(String needle) {
        sqlRunSamplesSearch.setText(needle);
    }

    /** 右侧上方那段介绍的原文（说明 / 场景 / 解决的问题 / 关键词）。 */
    public String sqlSamplesIntroText() {
        return sqlRunIntro == null ? "" : sqlRunIntro.getText();
    }

    public String sqlSamplesPreviewText() {
        return sqlRunPreview == null ? "" : sqlRunPreview.getText();
    }

    /** 示例预览那个滚动容器：自检要先把滚动位置弄走、再断"换一条回到开头"。 */
    public javax.swing.JScrollPane sqlSamplesPane() {
        return sqlRunPreviewScroll;
    }

    /** 语句全文折不折行：与 Python 侧的 NoWrap 同一口径，长行只能靠横向滚动。 */
    public boolean sqlSamplesPreviewWraps() {
        return sqlRunPreview != null && sqlRunPreview.getLineWrap();
    }

    /** 选中目录里第 n 个可见叶子。 */
    public void setSqlSamplesRow(int ordinal) {
        selectSqlSample(ordinal);
    }

    public void clickSqlSamplesCopy() {
        sqlRunCopyBtn.doClick();
    }

    public void clickSqlSamplesInsert() {
        sqlRunInsertBtn.doClick();
    }

    public javax.swing.JTabbedPane tabs() {
        return tabs;
    }

    /** 底部动作条那个容器：两条线都按"从左到右的组件序"比对按钮排布。 */
    public java.awt.Container actionBar() {
        return importBtn.getParent();
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
        final String prefix = Names.pyStrip(prefixField.getText());
        runTask("正在批量应用表名前缀…", sink -> {
            sink.setTotal(tables.size() + 1);
            TableOps.applyPrefix(tables, prefix);
            for (TableOps.Table t : tables) {
                sink.step(t.tableName);
            }
            sink.step("重名去重");
            return prefix;
        }, out -> {
            fileModel.refresh();
            log(prefix.isEmpty() ? "[前缀] 已移除前缀，恢复基础表名"
                                 : "[前缀] 已为全部表名批量添加前缀「" + prefix + "」");
            refreshPreview();
        });
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
        final String newHead = head;
        runTask("正在替换表名前缀…", sink -> {
            sink.setTotal(tables.size() + 1);
            TableOps.RenameResult r = TableOps.replaceFirstSegment(tables, newHead);
            for (TableOps.Table t : tables) {
                sink.step(t.tableName);
            }
            sink.step("重名去重");
            return r;
        }, out -> {
            TableOps.RenameResult r = (TableOps.RenameResult) out;
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
        });
    }

    /** 一张表一个标签页地看建表语句（对应 main.py:1528-1533 的 on_preview_ddl）。 */
    public void previewDdl() {
        if (tables.isEmpty()) {
            inform(NO_FILE_HINT);
            return;
        }
        refreshPreview();
        new DDLDialog(this, tables, dropIfExistsNow()).setVisible(true);
    }

    /** 自检与非界面调用用：当前状态下的全部建表语句。 */
    public String ddlText() {
        return ddlText;
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
        inform2("提示", text);
    }

    /**
     * 带标题的结果弹窗。Python 的四类对话框各有各的标题（添加结果 / 导入完成 / 执行完成 /
     * 任务异常），一律写"提示"等于把"这是哪类结果"的分类信息丢掉。
     */
    public void inform2(String title, String text) {
        dialog(title, text, javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    /** 出错类弹窗：Python 用 QMessageBox.critical，图标也是"这次没做成"的一部分。 */
    @Override
    public void informError(String title, String text) {
        dialog(title, text, javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    private void dialog(String title, String text, int icon) {
        // 记录与显示都是同一份原文：横向折行交给正文控件按内容区右边界做，
        // 纵向靠滚动条看全，谁都不再按写死的列数改写这句话
        lastDialogTitle = title;
        lastDialogText = text;
        lastDialogIsError = icon == javax.swing.JOptionPane.ERROR_MESSAGE;
        lastDialogIcon = icon == javax.swing.JOptionPane.ERROR_MESSAGE ? "critical"
                : icon == javax.swing.JOptionPane.WARNING_MESSAGE ? "warning" : "information";
        if (!modalProgress || lastDialogText.isEmpty()) {
            // 自检站在事件分发线程上，模态框会把它自己卡死；原地模式下只留痕不弹窗
            return;
        }
        MessageDialog.show(this, title, popupBodyText(text), null, icon);
    }

    /**
     * 弹窗将要显示的那一份正文：与 {@link #dialog} 同一个出口，自检量的就是它。
     *
     * <p>恒等返回不是多余的一层——它把"显示的就是原文"变成一处可断言的事实：
     * 谁再往这里加折行，自检立刻红，而不只是改了一个没人走的调用点。
     */
    public String popupBodyText(String text) {
        return text;
    }

    /** 结果弹窗正文用可选中的文本区；自检验的是真实弹窗里的同一个组件。 */
    public javax.swing.JComponent popupMessageComponent(String text) {
        return MessageDialog.createArea(text);
    }

    /** 最后一次弹窗的标题与正文：两条路都写满，自检读这一份，模态那条才真弹。 */
    public String lastDialogTitle() {
        return lastDialogTitle;
    }

    public String lastDialogText() {
        return lastDialogText;
    }

    /** 弹窗用的是哪种图标：information / warning / critical，两条线要能逐字对上。 */
    public String lastDialogIcon() {
        return lastDialogIcon;
    }

    /** 最后一次弹窗是不是出错图标那一档（任务异常用 critical，其余用 information）。 */
    public boolean lastDialogIsError() {
        return lastDialogIsError;
    }

    /**
     * 拦下的操作除了弹窗还要落一行日志：弹窗关掉就没了，日志才是可回溯的那份。
     * 图标用警告那一档（Python 这些位置全是 QMessageBox.warning），弹窗走同一个漏斗，
     * 原地模式才不会把站在事件分发线程上的自检卡死。
     */
    public void warn(String text) {
        log("已拦下：" + text);
        dialog("提示", text, javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }
}
