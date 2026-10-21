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
    /** SQL 执行器左右两栏与示例全文的字号：12 号现场反馈"太小"，两栏统一抬到 16。 */
    private static final int SQL_RUN_FONT_SIZE = 16;

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
    private String ddlText = "";
    private final LogPanel logPanel = new LogPanel();
    private JButton preview;
    private JButton connect;
    private JButton importBtn;
    private JButton stopBtn;
    private JButton genStd;
    private JButton genSimple;
    private boolean taskRunning;
    /**
     * 关掉时任务原地跑完、不弹模态框：自检与非界面调用要靠它验到线上那段代码，
     * 而不是验一份复印件（对应 main.py:137 的 MODAL_PROGRESS）。
     */
    public static boolean modalProgress = true;
    private javax.swing.JTextField hostField;
    private javax.swing.JSpinner portField;
    private javax.swing.JTextField userField;
    private javax.swing.JPasswordField passField;
    private javax.swing.JComboBox<String> dbCombo;
    private HintLabel impHint;
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
    private javax.swing.JButton sqlRunBtn;
    private javax.swing.JButton sqlRunClearInputBtn;
    private javax.swing.JButton sqlRunClearLogBtn;
    private javax.swing.JButton sqlRunHelpBtn;
    private javax.swing.JLabel sqlRunHint;
    /** 第三个页签顶部的当前目标库信息条：与前两页同一次刷新、同一个来源。 */
    private HintLabel sqlRunDbHint;
    private javax.swing.JDialog sqlRunSamples;
    private javax.swing.JList<String> sqlRunSamplesList;
    private javax.swing.JTextArea sqlRunPreview;
    private javax.swing.JButton sqlRunCopyBtn;
    private javax.swing.JButton sqlRunInsertBtn;
    private DropArea drop;
    private javax.swing.JProgressBar pageProgress;
    private HintTextField prefixField;
    private javax.swing.JCheckBox initialsBox;
    private javax.swing.JCheckBox upperBox;
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
        fileModel.setRenameHandler((item, requested) -> renameFileTable(item, requested));
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
        impHint = new HintLabel();
        tabs.addTab("Excel 导入", buildCenter());
        tabs.addTab("SQL 脚本改名入库", sqlPage);
        tabs.addTab("SQL 执行器", buildSqlRunPage());
        add(tabs, BorderLayout.CENTER);
        // 切页也要刷新：三页提示条同源，不刷就会停在"上一个页签当时看到的库"
        tabs.addChangeListener(e -> refreshDbLabels());
        bindHelpKey();
        refreshDbLabels();
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
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
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
            b.setPreferredSize(new Dimension(26, 22));
            b.setMinimumSize(new Dimension(26, 22));
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

    /** 连接栏：口令只进 JPasswordField，全程不落文件、不进日志。 */
    /**
     * 连接框：Python 是 QFormLayout 五行（主机/端口/用户名/密码/目标库），
     * 「连接并加载库」跟目标库下拉同一行。Excel 页那些操作按钮不属于这里，
     * 它们各自在文件组框里和页面底部（见 buildCenter）。
     */
    private JPanel buildConnectionBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("MySQL 连接信息"),
                BorderFactory.createEmptyBorder(2, 8, 6, 8)));

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
        portField.setPreferredSize(new Dimension(70, 24));
        // Qt 的 QSpinBox 不带千分位；JSpinner 默认按本地 NumberFormat 会显示成 3,306
        portField.setEditor(new javax.swing.JSpinner.NumberEditor(portField, "#0"));
        userField = new javax.swing.JTextField(10);
        passField = new javax.swing.JPasswordField(10);
        dbCombo = new javax.swing.JComboBox<String>();
        // 库列表要先连上服务器才拿得到，没加载之前下拉是禁用的：
        // 让用户手填库名，等于把"连到一个不存在的库"推迟到导入那一刻才爆
        dbCombo.setEnabled(false);
        dbCombo.setPreferredSize(new Dimension(180, 24));
        dbCombo.addActionListener(e -> refreshDbLabels());
        hostField.setText("127.0.0.1");

        userField.setText("root");
        hostField.getDocument().addDocumentListener(refresher);
        portField.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            @Override
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                refreshDbLabels();
            }
        });

        JPanel dbRow = new JPanel(new BorderLayout(6, 0));
        dbRow.add(dbCombo, BorderLayout.CENTER);
        dbRow.add(connect, BorderLayout.EAST);
        // QFormLayout 的等价物：标签一列按内容宽，输入一列吃掉剩余宽度，五行各占一行
        JPanel fields = new JPanel(new java.awt.GridBagLayout());
        addFormRow(fields, 0, "主机：", hostField, true);
        // 端口是数字框，跟着窗口拉宽只会显得更空，Python 那边它也是窄窄一个
        addFormRow(fields, 1, "端口：", portField, false);
        addFormRow(fields, 2, "用户名：", userField, true);
        addFormRow(fields, 3, "密码：", passField, true);
        addFormRow(fields, 4, "目标库：", dbRow, true);

        // Python 这条框里只有标签、输入框和「连接并加载库」，没有自造的状态文字（main.py:861-878）
        bar.add(fields, BorderLayout.CENTER);
        return bar;
    }

    /** 连接框的一行：左边固定宽的标签，右边输入件（stretch 为假时保持自己的偏好宽度）。 */
    private static void addFormRow(JPanel row, int y, String label, java.awt.Component field,
                                   boolean stretch) {
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.insets = new java.awt.Insets(2, 0, 2, 8);
        row.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = stretch ? 1.0 : 0.0;
        c.fill = stretch ? java.awt.GridBagConstraints.HORIZONTAL
                         : java.awt.GridBagConstraints.NONE;
        c.insets = new java.awt.Insets(2, 0, 2, 0);
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
        table.setRowHeight(22);
        // 表名单击就进编辑器改这一张表；双击其余列进结构弹窗改字段与主键
        javax.swing.DefaultCellEditor nameEditor =
                new javax.swing.DefaultCellEditor(new javax.swing.JTextField());
        nameEditor.setClickCountToStart(1);
        table.getColumnModel().getColumn(2).setCellEditor(nameEditor);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    // 「表名」列只改名，不叠一个结构窗
                    if (table.columnAtPoint(e.getPoint()) != 2) {
                        editStructure();
                    }
                }
            }
        });
        int[] widths = {54, 320, 200, 280, 80, 90, 160};
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        // 列宽可自由拖动，最后一列自动吃掉剩余空间（Qt 的 Interactive + stretchLastSection）
        table.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new Dimension(300, 120));

        // 「待导入文件」组框里是三样东西：表格、前缀行、四个操作按钮（main.py:893-946）。
        // 这些控件原先全被塞在顶部连接栏里，等于把"改表名的输入框"和"连接数据库"混在一处。
        prefixField = new HintTextField(18, PREFIX_HINT);
        JButton applyPrefix = new JButton("批量应用前缀");
        applyPrefix.addActionListener(e -> applyPrefix());
        JButton replacePrefix = new JButton("替换首个前缀");
        replacePrefix.setToolTipText(REPLACE_HEAD_TIP);
        replacePrefix.addActionListener(e -> replaceFirstPrefix());
        JPanel prefixBtns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        prefixBtns.add(applyPrefix);
        prefixBtns.add(replacePrefix);
        // 命名开关（全拼/首字母、小写/大写）跟表名前缀同属"名字怎么起"的治理项。
        // 竖着摞两颗，横向只占一颗的宽度，不会把前缀输入框挤没
        // 两颗都默认勾上：新加的文件直接按「首字母 + 大写」起名。
        // setSelected 不触发 ActionListener，启动时不会凭空打一行"切换"日志
        initialsBox = new javax.swing.JCheckBox("表名与字段名取拼音首字母");
        initialsBox.setSelected(true);
        initialsBox.setToolTipText("由中文转写的表名与字段名用拼音首字母（订单金额 → ddje、"
                + "水库基础信息 → skjcxx）；默认已勾选，取消则用全拼（ding_dan_jin_e）。"
                + "文件名括号里显式写下的英文表名不受影响；对之后添加的文件生效。");
        initialsBox.addActionListener(e -> log("[提示] 命名规则切换为："
                + (initialsBox.isSelected() ? "拼音首字母" : "全拼")
                + "（对之后添加的文件生效）"));
        upperBox = new javax.swing.JCheckBox("表名与字段名转大写");
        upperBox.setSelected(true);
        upperBox.setToolTipText("由中文或文件名转写出来的表名与字段名全部大写"
                + "（ding_dan_jin_e → DING_DAN_JIN_E、水库基础信息 → SHUI_KU_JI_CHU_XIN_XI）；"
                + "默认已勾选，取消则用小写。文件名括号里写下的英文表名同样转大写；"
                + "结构定义 Sheet 里人工写的字段名与手动改过的表名前缀原样保留；"
                + "对之后添加的文件生效（表名前缀按输入原样拼接，不跟着转）。");
        upperBox.addActionListener(e -> log("[提示] 表名与字段名大小写切换为："
                + (upperBox.isSelected() ? "大写" : "小写")
                + "（对之后添加的文件生效，结构定义 Sheet 里人工写的字段名不受影响）"));
        JPanel nameSwitches = new JPanel();
        nameSwitches.setLayout(new javax.swing.BoxLayout(nameSwitches, javax.swing.BoxLayout.Y_AXIS));
        nameSwitches.add(initialsBox);
        nameSwitches.add(upperBox);
        JPanel prefixEast = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        prefixEast.add(prefixBtns);
        prefixEast.add(nameSwitches);
        JPanel prefixRow = new JPanel(new BorderLayout(6, 0));
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
        JPanel btnRow = new JPanel(new java.awt.GridLayout(1, 3, 6, 0));
        btnRow.add(clear);
        btnRow.add(structure);
        btnRow.add(preview);

        JPanel fileRows = new JPanel(new BorderLayout(0, 2));
        fileRows.add(prefixRow, BorderLayout.NORTH);
        fileRows.add(btnRow, BorderLayout.SOUTH);
        JPanel fileBox = new JPanel(new BorderLayout());
        fileBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("待导入文件（单击表名改名，双击其他列改字段）"),
                BorderFactory.createEmptyBorder(2, 6, 4, 6)));
        fileBox.add(tableScroll, BorderLayout.CENTER);
        fileBox.add(fileRows, BorderLayout.SOUTH);

        // 日志区：上面一排只有右对齐的「清除日志」，下面才是文本框（main.py:949-960）
        JButton clearLog = new JButton("清除日志");
        clearLog.addActionListener(e -> logPanel.clearLog());
        JPanel logHead = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 2));
        logHead.add(clearLog);
        JScrollPane logScroll = new JScrollPane(logPanel);
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.add(logHead, BorderLayout.NORTH);
        logBox.add(logScroll, BorderLayout.CENTER);

        // 三段纵向分栏：拖拽区 / 文件组框 / 日志，段数、比例、把手宽度都照 main.py:964-971。
        // Swing 没有 setChildrenCollapsible(false)，用最小尺寸顶住——拖到 0 高那段就等于没了
        drop.setMinimumSize(new Dimension(10, 60));
        fileBox.setMinimumSize(new Dimension(300, 150));
        logBox.setMinimumSize(new Dimension(300, 100));
        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fileBox, logBox);
        lower.setDividerSize(6);
        // Qt 的 stretchFactor 0/2/3：拖拽区不参与拉伸，剩下 2:3 分给文件表与日志
        lower.setResizeWeight(0.4);
        JSplitPane stacked = new JSplitPane(JSplitPane.VERTICAL_SPLIT, drop, lower);
        stacked.setDividerSize(6);
        stacked.setResizeWeight(0.0);
        stacked.setDividerLocation(130);
        lower.setDividerLocation(250);
        excelSplit = stacked;
        excelLower = lower;
        excelDrop = drop;
        excelFileBox = fileBox;
        // Qt 的 setSizes([130,250,290]) 会按可用高度等比缩放，JSplitPane 只认绝对值：
        // 窗口比 670 矮时日志段会被挤成一行，所以真拿到尺寸后按同一比例重摆一次
        stacked.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                applySplitProportions();
            }
        });

        // 底部动作条：勾选框在左，停止与开始导入在右，再下面才是页内进度条（main.py:976-994）
        dropExisting = new javax.swing.JCheckBox("覆盖已存在表（先 DROP 再建）");
        dropExisting.addActionListener(e -> refreshPreview());
        importBtn = new JButton("开始导入");
        importBtn.setEnabled(false);
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
        JPanel opRight = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 2));
        opRight.add(stopBtn);
        opRight.add(importBtn);
        opRight.add(genStd);
        opRight.add(genSimple);
        JPanel op = new JPanel(new BorderLayout());
        op.add(dropExisting, BorderLayout.WEST);
        op.add(opRight, BorderLayout.EAST);

        pageProgress = new javax.swing.JProgressBar();
        // Qt 的 QProgressBar 默认就在条上印百分比，Swing 默认什么都不画：
        // 不开这个，页内进度条在两条线上看起来是两种东西
        pageProgress.setStringPainted(true);
        pageProgress.setPreferredSize(new Dimension(200, 18));
        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(op, BorderLayout.NORTH);
        south.add(pageProgress, BorderLayout.SOUTH);

        JPanel page = new JPanel(new BorderLayout());
        page.add(impHint, BorderLayout.NORTH);
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
            log("[已加载] 可用数据库：" + String.join(", ", dbs));
            refreshDbLabels();
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
     * 三页顶部的目标库提示条同源刷新：一条说 Excel 导到哪，一条说脚本跑到哪，
     * 一条说编辑区这段 SQL 跑到哪。各写一份的话，用户会在三个页签看到三个"当前库"。
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
            if (sqlRunDbHint != null) {
                sqlRunDbHint.setBad(text);
            }
            return;
        }
        String head = "当前目标库：" + db + "（" + hostField.getText().trim() + ":"
                + portField.getValue() + "）——";
        impHint.setOk(head + "Excel 数据将导入到这个库");
        if (sqlPage != null) {
            sqlPage.setHint(true, head + "SQL 脚本将执行到这个库");
        }
        if (sqlRunDbHint != null) {
            sqlRunDbHint.setOk(head + "编辑区里的 SQL 将执行到这个库");
        }
    }

    /** 第三个页签那条信息条的文本与本体：自检与对撞器按这两个口取证。 */
    public String runDbHintText() {
        return sqlRunDbHint == null ? "" : sqlRunDbHint.hintText();
    }

    public HintLabel runDbHint() {
        return sqlRunDbHint;
    }

    public boolean runDbHintIsOk() {
        return sqlRunDbHint != null && sqlRunDbHint.isOk();
    }

    public String excelHintText() {
        return impHint.hintText();
    }

    /** 提示条本体：自检要查它是不是真能拖选，而不是只看文案。 */
    public HintLabel excelHint() {
        return impHint;
    }

    public boolean excelHintIsOk() {
        return impHint.isOk();
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
        setPageProgress(0);
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
        if (!startTask()) {
            return;
        }
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
                    inform2("导入完成", finishImport(outcomes, cancelled));
                });
            } catch (Throwable t) {
                crash(t, progress, () -> setEnabledForRun(true));
            }
        }, "excel2mysql-import");
        worker.setDaemon(true);
        worker.start();
        progress.open();
    }

    /**
     * 任务在跑与没在跑是同一处开关：导入/解析开始置灰、结束复原。
     * 顺手把并发护栏也管起来——两个任务同时跑会共用同一份表列表互相踩，
     * 进度框也会叠到看不见底下那个（Python 侧靠 task_dialog 非空直接不吃第二次点击）。
     */
    private void setEnabledForRun(boolean on) {
        taskRunning = !on;
        connect.setEnabled(on);
        // 「有表就能点」，与 Python 侧同一口径：没选库时点下去由 startImport 弹 NO_DB_HINT，
        // 而不是把按钮一直灰着让人以为是坏了
        importBtn.setEnabled(on && !tables.isEmpty());
        stopBtn.setEnabled(!on);
        syncGenButtons();
    }

    /**
     * 两个生成按钮只在"有表可导、且没有别的任务在跑"时可点。
     * 判据取自同一处而不是各写各的：任务跑着的时候表列表正被工作线程读，
     * 这时候再生成一份就是两个任务共用同一份数据互相踩。
     */
    public void syncGenButtons() {
        if (genStd == null) {
            return;     // 连接栏比页签里的按钮先建，赋初值阶段还不能动它们
        }
        boolean on = !tables.isEmpty() && !taskRunning;
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
     * 占住任务位：占不到就说明已有任务在跑，这次点击直接不吃掉。
     * 两个任务同时跑会共用同一份表列表互相踩，进度框也会叠到看不见底下那个，
     * 所以护栏得是真入口，而不是散在各方法开头的 if。
     */
    public boolean startTask() {
        if (taskRunning) {
            log("已有任务在跑，请等当前这个结束");
            return false;
        }
        setEnabledForRun(false);
        return true;
    }

    /** 释放任务位并复原按钮。 */
    public void endTask() {
        taskLogView = null;
        setEnabledForRun(true);
    }

    /**
     * 带进度框的任务入口：抢任务位 -> 子线程算 + 模态框报进度 -> 回到事件分发线程落界面。
     * modalProgress 关掉时同一个 work 原地跑完，只是不弹框。
     */
    private void runTask(String title,
                         java.util.function.Function<ImportParse.Sink, Object> work,
                         java.util.function.Consumer<Object> onUi) {
        runTask(title, work, onUi, null);
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
        // 先认下面板再抢任务位：抢输的那句"已有任务在跑"要落在用户刚点的那一页
        taskLogView = logView;
        if (!startTask()) {
            taskLogView = previous;
            return;
        }
        if (modalProgress) {
            final ProgressDialog progress = new ProgressDialog(this, title);
            lastTask = progress;
            final ImportParse.Sink sink = sinkFor(progress);
            Thread worker = new Thread(() -> {
                Object out;
                try {
                    out = work.apply(sink);
                } catch (Throwable t) {
                    SwingUtilities.invokeLater(() -> {
                        progress.close();
                        crashMessage(t);
                        endTask();
                    });
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    progress.close();
                    // 落界面时也算在任务里：这几行的日志要去同一块面板（与原地那条路同序）
                    if (out != null) {
                        onUi.accept(out);
                    }
                    endTask();
                });
            }, "excel2mysql-task");
            worker.setDaemon(true);
            worker.start();
            progress.open();
            return;
        }
        Object out;
        try {
            out = work.apply(quietSink());
        } catch (Throwable t) {
            crashMessage(t);
            endTask();
            return;
        }
        // 工作函数返回 null 视为"这次没做成"，不回调落界面（与 Python 侧同一处理）
        if (out != null) {
            onUi.accept(out);
        }
        endTask();
    }

    @Override
    public String exportRoot() {
        return programDir();   // 交付件落点：界面里是程序同级，自检指临时目录
    }

    /**
     * SQL 页的五个耗时操作（读取 / 加前缀 / 换前缀 / 保存 / 生成交付件）共用 Excel 页那套
     * 任务位，差别只在日志要去 SQL 页那块面板——否则切回 Excel 页才看得见刚才那步做了什么。
     */
    @Override
    public void runSqlTask(String title,
                           java.util.function.Function<ImportParse.Sink, Object> work,
                           java.util.function.Consumer<Object> onUi) {
        runTask(title, work, onUi, sqlPage == null ? null : sqlPage.logPanel());
    }

    /** 只写日志不碰进度框的回报口。 */
    private ImportParse.Sink quietSink() {        return new ImportParse.Sink() {
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

    /** 有长任务在跑吗：护栏与自检都读这一个判定，不留两套状态。 */
    public boolean taskBusy() {
        return taskRunning;
    }

    public javax.swing.JButton stopButton() {
        return stopBtn;
    }

    /** 自检与命令行入口按用户的方式点这两颗生成按钮，而不是绕过界面直接调 core。 */
    public javax.swing.JButton genStandardButton() {
        return genStd;
    }

    public javax.swing.JButton genSimpleButton() {
        return genSimple;
    }

    /** 请求中止当前任务：已经做完的那一项会跑完，剩下的不再开工。 */
    public void stopTask() {
        if (!taskRunning || lastTask == null) {
            log("没有正在执行的任务");
            return;
        }
        lastTask.requestCancel();
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
        if (!startTask()) {
            return;
        }
        applyNamingSwitches();
        final ProgressDialog progress = new ProgressDialog(this, "正在解析文件…");
        final ImportParse.Sink sink = sinkFor(progress);
        lastTask = progress;
        setEnabledForRun(false);
        Thread worker = new Thread(() -> {
            try {
            final ImportParse.Outcome outcome =
                    ImportParse.parse(paths, currentPaths(), zipDirFor(paths), zipDestSeq,
                            builder, sink);
            SwingUtilities.invokeLater(() -> {
                finishAdd(outcome);
                progress.close();
                setEnabledForRun(true);
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

    /** 开关状态在主线程取好再进解析线程：解析线程不碰控件，只读 Names 上的两个布尔。 */
    private void applyNamingSwitches() {
        names.setInitialsOnly(initialsBox != null && initialsBox.isSelected());
        names.setUpperOnly(upperBox != null && upperBox.isSelected());
    }

    /** 给测试和命令行用的同步入口：同一个解析函数，只是不挂进度框。 */
    public String addFiles(List<String> paths) {
        applyNamingSwitches();
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
    public String finishAdd(ImportParse.Outcome outcome) {
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
        setPageProgress(0);
        // 清空后「开始导入」要跟着变灰：Python 的 on_clear 就是这么做的，
        // 留着亮按钮等于允许点一次只会弹"请先添加文件"的空操作
        importBtn.setEnabled(!tables.isEmpty());
        syncGenButtons();
        log("已清空");
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
    private boolean splitPlaced;

    /**
     * 把三段分栏按 Python 的 130:250:290 等比摆一次。
     * 只在真拿到足够高度时做，且做一次就够——用户之后自己拖把手，不该被程序改回去。
     */
    public void applySplitProportions() {
        if (splitPlaced || excelSplit == null || excelSplit.getHeight() < 200) {
            return;
        }
        splitPlaced = true;
        int first = (int) Math.round(excelSplit.getHeight() * 130.0 / 670.0);
        excelSplit.setDividerLocation(Math.max(first, excelDrop.getMinimumSize().height));
        int rest = excelSplit.getHeight() - excelSplit.getDividerLocation()
                - excelSplit.getDividerSize();
        int second = (int) Math.round(rest * 250.0 / 540.0);
        excelLower.setDividerLocation(Math.max(second, excelFileBox.getMinimumSize().height));
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
            informError("任务异常", LogText.crashDialog(msg));
        });
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

    /** 页内进度条：导入按"当前表完成数 / 总表数"报百分比，跑完归零。 */
    public void setPageProgress(int percent) {
        if (pageProgress != null) {
            pageProgress.setValue(Math.max(0, Math.min(100, percent)));
        }
    }

    public int pageProgressValue() {
        return pageProgress == null ? -1 : pageProgress.getValue();
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

    public javax.swing.JProgressBar pageProgressBar() {
        return pageProgress;
    }

    /** 双端界面枚举对撞器要按页归属分按钮（两页有重名按钮）。 */
    /**
     * 第三个页签「SQL 执行器」。与「SQL 脚本改名入库」页的分工要说清：那一页管"手上有一批 .sql
     * 文件要入库"（挑文件、改表名、落盘副本），这一页管"临时问一句、顺手改点结构"，
     * 所以它不挑文件也不落盘，只吃编辑区里那段文本。
     */
    private javax.swing.JPanel buildSqlRunPage() {
        javax.swing.JPanel page = new javax.swing.JPanel(new java.awt.BorderLayout(8, 6));
        // 提示单独占一行，三颗按钮另起一行。合成一行时这一行的最小宽度是"整句提示 + 三颗按钮"，
        // 会把整窗最小宽度顶到 830 以上，1024x768 的现场机和 800 宽的离屏用例都放不下。
        javax.swing.JPanel north = new javax.swing.JPanel(new java.awt.BorderLayout());
        javax.swing.JPanel bar = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        sqlRunDbHint = new HintLabel();
        north.add(sqlRunDbHint, java.awt.BorderLayout.NORTH);
        sqlRunHint = new javax.swing.JLabel(
                "直接输入 SQL 执行：建库、建表、改结构、查元数据都行；一次可以写多条，用分号分隔");
        north.add(sqlRunHint, java.awt.BorderLayout.CENTER);
        sqlRunHelpBtn = new javax.swing.JButton("示例 (?)");
        sqlRunHelpBtn.setToolTipText("打开示例清单：选中一条可复制到剪贴板或插到编辑区");
        sqlRunHelpBtn.addActionListener(e -> openSqlSamples());
        bar.add(sqlRunHelpBtn);
        sqlRunBtn = new javax.swing.JButton("执行");
        sqlRunBtn.setToolTipText("按分号切句逐条执行；全部成功才提交，中途出错整批回滚");
        sqlRunBtn.addActionListener(e -> runSqlStatements());
        bar.add(sqlRunBtn);
        north.add(bar, java.awt.BorderLayout.SOUTH);
        page.add(north, java.awt.BorderLayout.NORTH);

        sqlRunInput = new javax.swing.JTextArea(8, 60);
        sqlRunInput.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN,
                SQL_RUN_FONT_SIZE));
        sqlRunInput.setLineWrap(false);
        sqlRunInput.setText("SELECT 1 AS 连通检查;");
        // 两颗清空各归各的 pane：只清结果区时不必丢掉刚敲了一半的语句，反过来也一样
        sqlRunClearInputBtn = new javax.swing.JButton("清空编辑区");
        sqlRunClearInputBtn.setToolTipText("只清掉左边这段 SQL，右侧结果区不动");
        sqlRunClearInputBtn.addActionListener(e -> sqlRunInput.setText(""));
        javax.swing.JPanel left = new javax.swing.JPanel(new java.awt.BorderLayout(0, 2));
        left.add(paneHeader("SQL 文本", sqlRunClearInputBtn), java.awt.BorderLayout.NORTH);
        left.add(new javax.swing.JScrollPane(sqlRunInput), java.awt.BorderLayout.CENTER);

        sqlRunLog = new LogPanel(SQL_RUN_FONT_SIZE);
        sqlRunClearLogBtn = new javax.swing.JButton("清空结果区");
        sqlRunClearLogBtn.setToolTipText("只清掉右边的执行结果，左边编辑区不动");
        sqlRunClearLogBtn.addActionListener(e -> sqlRunLog.clearLog());
        javax.swing.JPanel right = new javax.swing.JPanel(new java.awt.BorderLayout(0, 2));
        right.add(paneHeader("执行结果", sqlRunClearLogBtn), java.awt.BorderLayout.NORTH);
        right.add(new javax.swing.JScrollPane(sqlRunLog), java.awt.BorderLayout.CENTER);

        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.45D);
        page.add(split, java.awt.BorderLayout.CENTER);
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

    /** 执行编辑区里那段 SQL。事务与回滚语义走 SqlExecute，与「SQL 脚本改名入库」页同一套。 */
    private void runSqlStatements() {
        final String text = sqlRunInput.getText();
        if (text == null || text.trim().isEmpty()) {
            warn("没有可执行的 SQL：编辑区是空的（只写注释也不算）");
            return;
        }
        final com.excel2mysql.core.Config cfg = currentConfig();
        runTask("正在执行 SQL…", sink -> {
            com.excel2mysql.core.DbClient db = null;
            try {
                db = new com.excel2mysql.core.DbClient(cfg);
                final com.excel2mysql.core.DbClient fdb = db;
                return com.excel2mysql.core.SqlExecute.run(fdb, text, sink::log);
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
        }, sqlRunLog);
    }

    /** 「示例 (?)」打开的那个对话框：左边挑，右边看全文，可复制也可插进编辑区。 */
    private void openSqlSamples() {
        if (sqlRunSamples != null && sqlRunSamples.isShowing()) {
            sqlRunSamples.toFront();
            return;
        }
        final javax.swing.JDialog dlg = new javax.swing.JDialog(this,
                "SQL 示例（" + SqlSamples.ALL.length + " 条）", false);
        dlg.setDefaultCloseOperation(javax.swing.JDialog.HIDE_ON_CLOSE);
        final javax.swing.DefaultListModel<String> model =
                new javax.swing.DefaultListModel<String>();
        for (SqlSamples.Sample s : SqlSamples.ALL) {
            model.addElement("【" + s.group + "】" + s.desc);
        }
        sqlRunSamplesList = new javax.swing.JList<String>(model);
        sqlRunSamplesList.setSelectedIndex(0);
        final javax.swing.JList<String> list = sqlRunSamplesList;
        sqlRunPreview = new javax.swing.JTextArea(12, 60);
        sqlRunPreview.setEditable(false);
        sqlRunPreview.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN,
                SQL_RUN_FONT_SIZE));
        sqlRunPreview.setText(SqlSamples.ALL[0].sql);
        final javax.swing.JTextArea preview = sqlRunPreview;
        list.addListSelectionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0) {
                preview.setText(SqlSamples.ALL[i].sql);
            }
        });

        javax.swing.JPanel buttons = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        sqlRunCopyBtn = new javax.swing.JButton("复制到剪贴板");
        sqlRunCopyBtn.addActionListener(e -> {
            String receipt;
            try {
                java.awt.datatransfer.StringSelection sel =
                        new java.awt.datatransfer.StringSelection(preview.getText());
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
            String add = preview.getText();
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

        javax.swing.JPanel right = new javax.swing.JPanel(new java.awt.BorderLayout(0, 4));
        right.add(new javax.swing.JLabel("语句全文（可全选复制）"), java.awt.BorderLayout.NORTH);
        right.add(new javax.swing.JScrollPane(preview), java.awt.BorderLayout.CENTER);
        right.add(buttons, java.awt.BorderLayout.SOUTH);

        javax.swing.JPanel left = new javax.swing.JPanel(new java.awt.BorderLayout(0, 2));
        left.add(new javax.swing.JLabel("示例清单（按分组，选中看右半边的全文）"),
                java.awt.BorderLayout.NORTH);
        left.add(new javax.swing.JScrollPane(list), java.awt.BorderLayout.CENTER);

        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.42D);
        dlg.getContentPane().add(split, java.awt.BorderLayout.CENTER);
        Windows.clientSize(dlg.getContentPane(), dlg, 900, 560);
        dlg.setVisible(true);
        sqlRunSamples = dlg;
    }

    /** 给自检与双端对撞用：这一页的可观察量都从这几个口子出去。 */
    public String sqlRunInputText() {
        return sqlRunInput == null ? "" : sqlRunInput.getText();
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

    public String sqlSamplesTitle() {
        return sqlRunSamples == null ? "" : sqlRunSamples.getTitle();
    }

    public void runSqlFromUi() {
        runSqlStatements();
    }

    public String sqlRunHintText() {
        return sqlRunHint == null ? "" : sqlRunHint.getText();
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

    public String sqlSamplesItemText(int index) {
        return sqlRunSamplesList == null ? "" : sqlRunSamplesList.getModel().getElementAt(index);
    }

    public String sqlSamplesPreviewText() {
        return sqlRunPreview == null ? "" : sqlRunPreview.getText();
    }

    public void setSqlSamplesRow(int index) {
        sqlRunSamplesList.setSelectedIndex(index);
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
        // 显示与记录都用折好行的那份：Python 侧弹窗走的同一道折行，
        // 两边比的就是用户实际看到的文字，不是各自排版前的半成品
        String body = PopupText.wrap(text);
        lastDialogTitle = title;
        lastDialogText = body;
        lastDialogIsError = icon == javax.swing.JOptionPane.ERROR_MESSAGE;
        lastDialogIcon = icon == javax.swing.JOptionPane.ERROR_MESSAGE ? "critical"
                : icon == javax.swing.JOptionPane.WARNING_MESSAGE ? "warning" : "information";
        if (!modalProgress || lastDialogText.isEmpty()) {
            // 自检站在事件分发线程上，模态框会把它自己卡死；原地模式下只留痕不弹窗
            return;
        }
        javax.swing.JOptionPane.showMessageDialog(this, popupMessage(body), title, icon);
    }

    /** 结果弹窗正文用可选中的文本区；自检验的是实际会放进 JOptionPane 的那个组件。 */
    public javax.swing.JComponent popupMessageComponent(String text) {
        return popupMessage(PopupText.wrap(text));
    }

    private static javax.swing.JTextArea popupMessage(String body) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(body);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBackground(new java.awt.Color(0, 0, 0, 0));
        java.awt.Font font = javax.swing.UIManager.getFont("OptionPane.messageFont");
        area.setFont(font == null ? area.getFont() : font);
        java.awt.FontMetrics fm = area.getFontMetrics(area.getFont());
        String[] lines = body.split("\n", -1);
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, fm.stringWidth(line));
        }
        // JTextArea 默认只按 20 列报首选尺寸；结果弹窗里的产物路径会被挤成一团。
        // 正文已按 72 显示列折行，下限给到 820x420，长路径与文件清单才看得完整。
        int width = Math.max(820, Math.min(900, textWidth + 44));
        int height = Math.max(420, Math.min(560, lines.length * fm.getHeight() + 70));
        java.awt.Dimension size = new java.awt.Dimension(width, height);
        area.setPreferredSize(size);
        area.setMinimumSize(size);
        return area;
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
