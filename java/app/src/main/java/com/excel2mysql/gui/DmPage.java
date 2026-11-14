package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DataMover;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.DmMysql;
import com.excel2mysql.core.DmReader;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.MysqlDm;
import com.excel2mysql.core.MysqlReader;
import com.excel2mysql.core.SqlExecute;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.BufferedWriter;
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
import javax.swing.JTextField;

/** 第 5 页签：达梦 DM8 与 MySQL 互转建表语句——DM8 → MySQL（默认）或 MySQL → DM8，均含表与字段注释。 */
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
    /**
     * 「导出 … .sql」：建表语句与数据一次性写成文件（选路径即落盘）。
     *
     * <p>原来拆成「生成脚本（先进正文预览）」+「保存到 .sql」两颗，用户 2026-11-15 要求合成一颗：
     * 这份脚本的用途本来就是存档/发人审，生成完还要再点一次保存是多余的一步。
     * 合成之后点它就选路径直接写盘，正文预览那一块也一起去掉。
     */
    private final JButton genBtn = new JButton("导出 .sql 脚本");
    private final JButton writeBtn = new JButton("写入 MySQL 目标库");
    /**
     * 转换方向：两个单选钮，不是下拉。
     *
     * <p>这一页的"读谁、写谁、按钮叫什么"全跟着方向换，把它折进下拉里等于把当前处境藏起来——
     * 用户 2026-11-14 报「根本没法用」，第一处卡壳就是找不到这个下拉、翻过来又不知道
     * 那排达梦参数此刻是来源还是目标。两个钮 + 一句说明，一眼看完。
     */
    /**
     * 方向：不靠两个单选钮，靠两张卡片一左一右——左边永远是"读自"，右边永远是"写入"，
     * 中间一颗换向键。用户 2026-11-15 第四轮点名「方向和连接信息依旧看着很繁琐」：
     * 方向这么要紧的东西，原来长得像一排输入框里的一个标签。
     */
    private boolean dmToMysql = true;
    /** 左槽 / 右槽：两张卡片按方向换位置（同一个控件只能有一个父，所以是"搬"过去）。 */
    private JPanel leftSlot;
    private JPanel rightSlot;
    private JPanel dmCard;
    private JPanel mysqlCard;
    private final JLabel dmCardRole = new JLabel("① 读自 · 达梦 DM8");
    private final JLabel mysqlCardRole = new JLabel("② 写入 · MySQL");
    /** 中间那颗换向键。 */
    private final JButton swapBtn = new JButton("⇄");
    /** 「连接参数」折叠开关：那一排六格很占地方，不填的时候没必要一直杵着。 */
    private final JButton dmToggle = new JButton("收起连接参数");
    /** 收起时那一句摘要：角色、账号、地址、模式、口令填没填，一眼看完。 */
    private final JLabel dmSummary = new JLabel();
    /** 达梦参数那排本体（收起时整块隐藏）。 */
    private JPanel dmParams;
    /** 整行（开关 + 摘要 + 参数）：收起时要跟着变矮。 */
    private JPanel dmRow;
    private boolean dmExpanded = true;
    /**
     * 每个方向各记一份"用户手动选的展开状态"：0＝正向、1＝反向。
     *
     * <p>默认值按产品口径给（正向展开、反向收起），但用户手动收/展过就按他的来——
     * 换个方向再回来，不该把他刚做的选择重置掉。
     */
    private final boolean[] dmManual = {true, false};
    /** 「模式」这格两向都指达梦：正向是读的模式，反向是写入的模式。 */
    private final JLabel schemaCaption = new JLabel("模式");
    /**
     * 目标那一格按方向换卡：正向是「MySQL 目标库」下拉；反向那一格空着会让人以为能选源库，
     * 而源库其实在顶部连接栏，所以反向换成"这次读的 MySQL 源"一行只读信息。
     */
    private final JPanel targetSlot = new JPanel(new java.awt.CardLayout());
    /** 反向时显示顶部连接栏此刻选中的 MySQL 库：源不在这页输入，但必须看得见。 */
    private final JLabel mysqlSourceLabel = new JLabel();
    private final JLabel targetLabel = new JLabel("MySQL 目标库");
    /**
     * 导出范围的下拉：<b>导出表结构和数据</b>（默认，脚本里先建表再逐表 INSERT）
     * 或 <b>导出数据</b>（只出 INSERT，目标表必须已经存在）。
     */
    private final javax.swing.JComboBox<String> exportMode =
            new javax.swing.JComboBox<String>(new String[]{"结构+数据", "仅数据"});
    /** 只灌数据时可选：每张表灌之前先 DELETE（不可恢复，默认不勾）。 */
    private final JCheckBox clearTarget = new JCheckBox("先清空", false);
    private final JCheckBox dropExisting = new JCheckBox("先 DROP", false);
    /**
     * 表名/字段名的大小写按目标端固定：写 MySQL 一律小写、写达梦一律大写。
     *
     * <p>这不是"可选风格"，是两边查得到的前提——达梦把未加引号的标识符折大写，
     * 建成大写之后 {@code select * from t_oil} 不带引号也命中；MySQL 在 Linux 下区分大小写，
     * 建成小写才与建表页那条线的习惯一致。做成开关只会让人建出一半大写一半小写的库。
     */
    private final JLabel nameCaseHint = new JLabel();
    private final JLabel listLabel = new JLabel("未连接达梦");
    /**
     * 右栏最下面那句「下一步」：这一页的动作不是并排同级的，按状态指出该点哪一颗。
     *
     * <p>以前挂在按钮行下面，一行蓝字被一排按钮挤到边角；收到右栏之后，
     * 阅读顺序就是操作顺序：设参数 → 按主键 → 看这句话。
     */
    private final JLabel nextHint = new JLabel();
    /** 右栏顶上那句：读回多少、挑了多少、共多少字段。 */
    private final JLabel railTables = new JLabel();
    /** 左卡片标题旁的可选数、右卡片标题旁的待迁移数。 */
    private final JLabel leftCount = new JLabel("（0 张）");
    private final JLabel pickedCount = new JLabel("（0 张）");
    /**
     * 待迁移清单：表在 {@link #tables} 里的下标。
     *
     * <p>用 TreeSet 有两个理由：去重（同一张表加两次只算一张），以及顺序稳定——
     * 不管按什么顺序挑，渲染与生成都按源表顺序走，日志里两张清单能对着看。
     */
    private final java.util.TreeSet<Integer> picked = new java.util.TreeSet<Integer>();
    private final javax.swing.table.DefaultTableModel pickedModel =
            new javax.swing.table.DefaultTableModel(
                    new Object[]{"表名", "表注释", "字段数", "主键"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
    /** 右边那张「待迁移」表：左边挑中的表搬过来，双击可退回。 */
    private final javax.swing.JTable pickedTable = new javax.swing.JTable(pickedModel);
    /** 穿梭框中间那四颗：两颗往右（加入）、两颗往左（退回）。 */
    private final JButton addBtn = new JButton("添加 →");
    private final JButton addAllBtn = new JButton("全部 →");
    private final JButton removeBtn = new JButton("← 移除");
    private final JButton removeAllBtn = new JButton("← 全部");
    /** 两张卡片的标题：左边是全部源表，右边是这次要搬的。 */
    private final JLabel leftTitle = new JLabel("可选表");
    private final JLabel rightTitle = new JLabel("待迁移");
    /** 右卡片底下那句：空着时指路，有表时说双击可退回。 */
    private final JLabel pickedHint = hintLine("还没挑表：在左边双击一行，或点「全部 →」整库迁移");
    /** 上一趟写入的收成一句话：写完那一行「下一步」改成结果，别还停在"去写"。 */
    private String lastWriteNote = "";
    /** 驱动在不在，进页就该看得见，不该等点了连接才知道。 */
    private final JLabel driverLabel = new JLabel();
    /** 驱动在不在的缓存：按钮提示每次改动都要读它，不必反复扫 drivers 目录。 */
    private boolean driverOk;
    /** 清单过滤：一个模式几百张表时，只找得到滚动条。 */
    private final JTextField filterBox = new JTextField(14);
    private final JButton clearFilterBtn = new JButton("清空过滤");
    /**
     * 日志框右上角那颗清扫键：读结构、生成、写库都会往日志里垒行，
     * 只清这一页的记录，清单、待迁移与已导出的文件一概不动
     * （与第一、二、三、四页同一位置、同一口径）。
     */
    private final JButton clearLogBtn = new JButton("清除日志");
    /** 主区 / 日志一条分隔条：日志段默认压在最小行数，拖它才放大（自检量高度用）。 */
    private javax.swing.JSplitPane logSplit;
    /** 日志段的最小高度（表头一行 + 4 行日志），拖到底就到这儿。 */
    private int logMinHeight;
    /** 用户拖过分隔条没有：拖过之后窗口怎么变都保持他那一档高度，不再按回最小。 */
    private boolean logUserMoved;
    /** 用户拖到的日志高度：拖动与后续重排都以它为准（当场读控件高度不可靠）。 */
    private int logUserHeight;
    /** 正在拖分隔条：拖动期间不插手摆放，否则每动一下都被拽回去。 */
    private boolean logDragging;
    /** 视图第 n 行对应 tables 里的第几条：过滤后选中范围要靠它换算。 */
    private final List<Integer> shownRows = new ArrayList<Integer>();
    /** 结构是从哪个模式/库读的、读回之后关联的输入又改过没有。 */
    private String loadedFrom = "";
    /** 「已读回 N 张（模式 X）」里那个词：正向是达梦的模式，反向是 MySQL 的库。 */
    private String loadedKind = "模式";
    /**
     * 反向读回时顶部连接栏那套 MySQL 源的身分串（user@host:port/db）。
     *
     * <p>反向的源不在这一页输入，改了顶部连接栏就要靠这一串比出来；
     * 正向这一串一直是空的（正向的源是页面上那排达梦参数，靠参数监听盯）。
     */
    private String loadedSourceKey = "";
    /** 连上时读到的服务端版本：只在摘要末尾说一句，不占第二行。 */
    private String serverVersion = "";
    private boolean stale;
    /** 脏的理由按方向各写各的：正向怪达梦参数，反向怪顶部连接栏的 MySQL 源。 */
    private String staleReason = "";
    /** 上一回自动跟随的目标库：只有它还等于当前值时才继续跟，用户自己挑过就不再插手。 */
    private String followedTarget = "";
    /** 上一次数据面的收成（行数、失败表），写入与导出结束时报用。 */
    private DataMover.Report lastData;
    /** 最近一次导出落到了哪个文件：日志与提示复用，不必再问一次用户。 */
    private java.io.File lastExportFile;

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
        // 一屏只留一个视觉重点：真正要交付的是"写进去"，生成脚本是中间产物
        Buttons.secondary(genBtn);
        Buttons.primary(writeBtn);
        setLayout(new BorderLayout(0, UiScale.px(6)));
        add(buildTop(), BorderLayout.NORTH);
        Tables.style(table);
        // 表名/注释给足宽度，字段数与主键窄一些：宽表时先看见是哪张表
        sizeColumns(table, 180, 240, 56, 120);
        Tables.style(pickedTable);
        sizeColumns(pickedTable, 160, 210, 56, 80);
        // 两张卡片都不宽，列与列之间加一道横向间隙：不加的话"油料保障台账"会正好顶到
        // 字段数那一列上，看起来像一个数（"油料保障台账12"）
        for (javax.swing.JTable t : new javax.swing.JTable[]{table, pickedTable}) {
            t.setIntercellSpacing(new java.awt.Dimension(UiScale.px(8), 0));
        }
        installRowMenu(table, true);
        installRowMenu(pickedTable, false);
        // 三段上下排，段间各一条 6px 可拖分隔条：清单 / DDL 正文 / 日志。
        // 日志段与正文段都只在自己的最小高度起步，余量全给清单——日志一行没写时
        // 不会白占下半屏（用户报「日志撑满整个工具」）；要读长日志就把分隔条往上拖，
        // 与第一、二、三、四页同一口径。
        // 只剩两段：挑表主区 + 日志。脚本预览那一块已按用户要求去掉（导出即落盘）
        logSplit = logBodySplit(buildMainArea(), buildLogBox());
        add(logSplit, BorderLayout.CENTER);
        // 首帧高度还是 0，等这一页真正排过版再摆一次：日志压在最小高度、余量给主区
        javax.swing.SwingUtilities.invokeLater(this::applyLogSplit);
        connectBtn.addActionListener(e -> connectAndList());
        genBtn.addActionListener(e -> exportToDefaultDir());
        genBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                maybeExportMenu(e);
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                maybeExportMenu(e);
            }
        });
        writeBtn.addActionListener(e -> writeToTarget());
        exportMode.setToolTipText("<html>结构+数据（默认）：脚本里先 CREATE TABLE，再逐表 INSERT。<br>"
                + "仅数据：只出 INSERT，目标表必须已经存在（结构由别处建好）。</html>");
        clearTarget.setToolTipText("<html>只导数据时可用：每张表灌之前先 DELETE，清空已有行。<br>"
                + "不可恢复；默认不勾，冲突（主键重复等）逐表记进日志。</html>");
        dropExisting.setToolTipText("<html>勾上则每张建表前先 DROP TABLE IF EXISTS，"
                + "重跑不会撞「表已存在」；<b>同名表里的数据会一起没</b>。</html>");
        exportMode.addActionListener(e -> onSchemaModeChanged());
        table.getSelectionModel().addListSelectionListener(e -> updateTransferButtons());
        pickedTable.getSelectionModel().addListSelectionListener(e -> updateTransferButtons());
        javax.swing.event.DocumentListener onFilter = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refill(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refill(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refill(); }
        };
        filterBox.getDocument().addDocumentListener(onFilter);
        // 达梦那排参数一改，正向（它们是来源）手上这份结构就跟当前输入对不上了：
        // 改完不重连就拿旧结构生成，最容易出的事是模式换了、表名却还是上一本的。
        // 反向时这排是「写入目标」，改它不影响已经读回来的 MySQL 结构，不该标脏——
        // 之前一刀切标脏，用户填完写入模式就再也生成不了（2026-11-14 报的「根本没法用」）。
        javax.swing.event.DocumentListener onParams = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onDmParamChanged(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onDmParamChanged(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onDmParamChanged(); }
        };
        hostField.getDocument().addDocumentListener(onParams);
        portField.getDocument().addDocumentListener(onParams);
        userField.getDocument().addDocumentListener(onParams);
        schemaField.getDocument().addDocumentListener(onParams);
        paramField.getDocument().addDocumentListener(onParams);
        refreshDriverLabel();
        // 缺驱动时把"放哪儿"整句落进日志：横排那一格只放得下短句，日志这一行可以照着抄
        if (!driverOk) {
            logPanel.log("! [WARN] DM | " + DmReader.missingDriverHint());
            // 进页就缺驱动：连接参数是现场要核对的第一件事。只在这一处自动展开——
            // 每次刷驱动状态都展开的话，反向读完 MySQL 表，收起的那排又会被顶开
            setDmExpanded(true, "本机没有达梦驱动，先核对连接参数");
        }
        refreshDirectionTexts();
        refreshSchemaMode();
        refreshSourceField();
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

    /** 驱动那一格的话：找到了报个数，没找到就短一句留给悬停——路径太长，横排里一定被裁掉。 */
    /**
     * 驱动状态：<b>装好了就不显示</b>，缺了才红字提醒。
     *
     * <p>用户 2026-11-15 点名「【达梦驱动：1 个】不需要显示」——一个装好的驱动是常态，
     * 常态不该占着一行末尾的位置。反过来「缺驱动」是这一页跑不起来的原因，必须当堂说，
     * 所以只在那一档露出来（整句路径仍然挂在悬停与本页日志里，横排放不下）。
     */
    private void refreshDriverLabel() {
        if (DmReader.driverPresent()) {
            driverOk = true;
            driverLabel.setText("");
            driverLabel.setVisible(false);
            driverLabel.setToolTipText(null);
            refreshButtonStates();
            return;
        }
        driverOk = false;
        driverLabel.setText("缺达梦驱动 → 悬停看放哪儿");
        driverLabel.setForeground(new java.awt.Color(0xb3, 0x26, 0x1d));
        driverLabel.setVisible(true);
        driverLabel.setToolTipText("<html>" + DmReader.describeDrivers() + "<br>"
                + DmReader.missingDriverHint().replace("<", "&lt;") + "</html>");
        refreshButtonStates();
    }

    /** 达梦那排参数改了：只有正向（达梦是来源）才算脏，反向时它们是写入目标。 */
    private void onDmParamChanged() {
        if (dmToMysql) {
            markStale("结构读自模式 " + loadedFrom + "，达梦连接参数已改动");
        }
        refreshDmSummary();
        refreshButtonStates();
    }

    /**
     * 手上这份结构跟当前输入对不上了：只记第一次，理由由调用方给全。
     *
     * <p>判据是"手上有结构、关联的输入又改了"，不能挂在地基 loadedFrom 上：
     * 模式名没记住（自检直灌结构）时同样该拦，否则改了参数照拿旧结构生成。
     */
    private void markStale(String reason) {
        if (tables.isEmpty() || stale) {
            return;
        }
        stale = true;
        staleReason = reason;
        refreshSummary();
    }

    /** 清单那一行的摘要：总数 / 显示数 / 选中数 / 结构是不是当前输入读的那一份。 */
    private void refreshSummary() {
        StringBuilder sb = new StringBuilder();
        if (stale) {
            sb.append("⚠ ").append(staleReason).append(" —— 先重连；");
        }
        if (tables.isEmpty()) {
            sb.append(dmToMysql ? "未连接达梦" : "未连接 MySQL");
        } else {
            sb.append("已读回 ").append(tables.size()).append(" 张");
            if (!loadedFrom.isEmpty()) {
                sb.append("（").append(loadedKind).append(" ").append(loadedFrom).append("）");
            }
            if (!serverVersion.isEmpty()) {
                sb.append("｜").append(serverVersion);
            }
            sb.append("｜可选 ").append(shownRows.size());
            sb.append("｜待迁移 ").append(picked.size()).append(" 张");
        }
        listLabel.setText(sb.toString());
        listLabel.setToolTipText(stale
                ? staleReason + "：点「" + connectBtn.getText() + "」重连，生成会先被拦下"
                : null);
        leftCount.setText("（" + shownRows.size() + " 张）");
        pickedCount.setText("（" + picked.size() + " 张）");
        pickedHint.setText(picked.isEmpty()
                ? (tables.isEmpty() ? "读回结构后在这里挑表" : "还没挑表：在左边双击一行，或点「全部 →」整库迁移")
                : "这次要搬的就是这几张；双击一行可退回左边");
        refreshRailTables();
    }

    /** 右栏顶上那一句：读回多少、挑了多少、这些表一共多少字段（判断这次搬多大）。 */
    private void refreshRailTables() {
        if (tables.isEmpty()) {
            railTables.setText("还没读回结构");
            return;
        }
        int cols = 0;
        for (Integer i : picked) {
            if (i != null && i >= 0 && i < tables.size()) {
                cols += tables.get(i).columns.size();
            }
        }
        railTables.setText(picked.isEmpty()
                ? "已读回 " + tables.size() + " 张，还没挑表"
                : "已读回 " + tables.size() + " 张 · 待迁移 " + picked.size()
                        + " 张（" + cols + " 个字段）");
    }

    /**
     * 重铺左边的「可选表」：已经搬到右边的表不再出现在这里，过滤只作用在这一侧。
     *
     * <p>这就是穿梭框和"勾选清单"的区别：左边永远是"还没挑的"，右边永远是"这次要搬的"，
     * 不需要靠"一行不选＝全部"这种暗规则去解释范围。
     */
    private void refill() {
        model.setRowCount(0);
        shownRows.clear();
        String kw = filterKeyword();
        for (int i = 0; i < tables.size(); i++) {
            if (picked.contains(Integer.valueOf(i))) {
                continue;
            }
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
        updateTransferButtons();
    }

    /** 重铺右边的「待迁移」：按源表顺序，跟挑的先后无关。 */
    private void refillPicked() {
        pickedModel.setRowCount(0);
        for (Integer i : picked) {
            if (i == null || i < 0 || i >= tables.size()) {
                continue;
            }
            DmMysql.Table t = tables.get(i);
            pickedModel.addRow(new Object[]{t.name, t.comment,
                    Integer.valueOf(t.columns.size()), joinNames(t.primaryKeys)});
        }
        refreshSummary();
        updateTransferButtons();
    }

    private String filterKeyword() {
        String v = filterBox.getText();
        return v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void clearFilter() {
        boolean dirty = !filterBox.getText().isEmpty();
        filterBox.setText("");
        refill();
        logPanel.log("- [INFO] DM | " + (dirty ? "已清空过滤，左边回到 " : "过滤本来就是空的，左边仍是 ")
                + model.getRowCount() + " 张可选表");
    }

    /** 左边选中的行 → 右边；视图行号经 shownRows 换成表下标（过滤时两者不是一回事）。 */
    private void addSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            logPanel.log("- [INFO] DM | 左边没选中任何行：直接在左边双击一行也能加入待迁移");
            return;
        }
        int n = 0;
        for (int r : rows) {
            if (r >= 0 && r < shownRows.size() && picked.add(shownRows.get(r))) {
                n++;
            }
        }
        afterTransfer(n == 0 ? "选中的表本来就在待迁移里" : n + " 张加入待迁移");
    }

    /** 全部 → ：只加当前看得见的（有过滤时不会把藏起来的几百张一起搬过去）。 */
    private void addAllVisible() {
        int n = 0;
        for (Integer i : new ArrayList<Integer>(shownRows)) {
            if (picked.add(i)) {
                n++;
            }
        }
        afterTransfer(n == 0 ? "左边没有可加的表" : "左边看得见的 " + n + " 张全部加入待迁移");
    }

    private void removeSelected() {
        int[] rows = pickedTable.getSelectedRows();
        if (rows.length == 0) {
            logPanel.log("- [INFO] DM | 右边没选中任何行：直接在右边双击一行也能退回左边");
            return;
        }
        List<Integer> view = new ArrayList<Integer>(picked);
        int n = 0;
        for (int r : rows) {
            if (r >= 0 && r < view.size() && picked.remove(view.get(r))) {
                n++;
            }
        }
        afterTransfer(n == 0 ? "没有可退回的表" : n + " 张退回左边");
    }

    private void removeAllPicked() {
        if (picked.isEmpty()) {
            logPanel.log("- [INFO] DM | 待迁移本来就是空的");
            return;
        }
        int n = picked.size();
        picked.clear();
        afterTransfer("待迁移已清空（" + n + " 张退回左边）");
    }

    /** 挑表变了：上一份脚本与它对应的表已经不是同一批，先作废再重铺两张清单。 */
    private void afterTransfer(String note) {
        boolean had = lastOut != null || lastExportFile != null;
        lastOut = null;
        lastData = null;
        lastExportFile = null;
        lastWriteNote = "";
        table.clearSelection();
        refill();
        refillPicked();
        refreshButtonStates();
        logPanel.log("- [INFO] DM | " + note + "；待迁移 " + picked.size() + " 张");
        if (had) {
            logPanel.log("! [WARN] DM | 挑的表变了，上一份脚本已作废，需要时重新生成");
        }
    }

    /** 四颗搬运键的可用性：没有可加/可退的就灰着，别让人点了没反应。 */
    private void updateTransferButtons() {
        addBtn.setEnabled(table.getSelectedRowCount() > 0);
        addAllBtn.setEnabled(model.getRowCount() > 0);
        removeBtn.setEnabled(pickedTable.getSelectedRowCount() > 0);
        removeAllBtn.setEnabled(!picked.isEmpty());
    }

    /**
     * 顶部两行：一张"读自 → 写入"的流向图 + 一行可收起的连接参数。
     *
     * <p>用户 2026-11-15 第四轮点名「方向和连接信息依旧看着很繁琐」。原来那一行的构成是：
     * 标签「方向」+ 两个单选钮 + 连接键 + 一句说明 + 驱动状态，第二行再铺六个输入框。
     * 问题是**方向被做成了输入框里的一个标签**，而它其实是这一页的总开关。
     *
     * <p>现在用图形说话：左卡片永远写"① 读自 <来源>"，右卡片永远写"② 写入 <目标>"，
     * 中间一颗 ⇄ 换向；达梦那几个参数收进卡片里的「连接参数」开关，默认不占地方。
     */
    private JPanel buildTop() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(PageIntro.of("达梦 DM8 与 MySQL 互转：左边挑表，右边设参数，按「写入」或「导出 .sql」"),
                BorderLayout.NORTH);
        javax.swing.Box rows = javax.swing.Box.createVerticalBox();
        rows.add(fixedHeight(buildFlowRow()));
        rows.add(fixedHeight(buildDmRow()));
        p.add(rows, BorderLayout.CENTER);
        return p;
    }

    /** 流向行：左卡 + 换向 + 右卡 + 连接键（缺驱动时多一句红字）。 */
    private JPanel buildFlowRow() {
        JPanel flow = row();
        leftSlot = new JPanel(new BorderLayout());
        rightSlot = new JPanel(new BorderLayout());
        dmCard = buildDmCard();
        mysqlCard = buildMysqlCard();
        leftSlot.add(dmCard, BorderLayout.CENTER);
        rightSlot.add(mysqlCard, BorderLayout.CENTER);
        flow.add(leftSlot);
        swapBtn.setToolTipText("<html>换个方向：现在读左边、写右边，点一下变成读右边、写左边。<br>"
                + "换向会清空已读回的清单，重新列一次表。</html>");
        // 换向键要看得见：两张大卡片中间只有它，字号比正文大一档、按钮方一点
        swapBtn.setFont(swapBtn.getFont().deriveFont(java.awt.Font.BOLD,
                swapBtn.getFont().getSize2D() + swapBtn.getFont().getSize2D() / 3f));
        swapBtn.setForeground(new java.awt.Color(0x1c, 0x4f, 0x9e));
        swapBtn.setPreferredSize(new java.awt.Dimension(UiScale.px(46), UiScale.px(32)));
        swapBtn.setMargin(new java.awt.Insets(0, 0, 0, 0));
        swapBtn.addActionListener(e -> applyDirection(!dmToMysql));
        flow.add(swapBtn);
        flow.add(rightSlot);
        flow.add(connectBtn);
        // 驱动状态挂在这一行末尾：**装好了不显示**，缺了才红字顶出来（用户 2026-11-15 点名去掉
        // "达梦驱动：N 个"那句常态文字）
        flow.add(driverLabel);
        return flow;
    }

    /**
     * 达梦那张卡片：正向是"读自"，反向是"写入"。角色写在标题里，参数摘要写在下面。
     *
     * <p>卡片本身不随方向换内容，只换标题与位置——参数、口令状态两向都要看。
     */
    private JPanel buildDmCard() {
        JPanel card = new JPanel(new BorderLayout(0, UiScale.px(2)));
        card.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0xc3, 0xd4, 0xea)),
                javax.swing.BorderFactory.createEmptyBorder(UiScale.px(5), UiScale.px(10),
                        UiScale.px(5), UiScale.px(8))));
        card.setBackground(new java.awt.Color(0xf3, 0xf8, 0xff));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        dmCardRole.setFont(dmCardRole.getFont().deriveFont(java.awt.Font.BOLD));
        dmCardRole.setForeground(new java.awt.Color(0x1c, 0x4f, 0x9e));
        head.add(dmCardRole, BorderLayout.WEST);
        dmToggle.setText("展开连接参数");
        dmToggle.setToolTipText("展开/收起达梦的主机、端口、用户名、口令、模式与驱动参数");
        dmToggle.addActionListener(e -> {
            boolean on = !dmExpanded;
            dmManual[dmToMysql ? 0 : 1] = on;      // 记住这个方向的选择
            setDmExpanded(on, "");
            logPanel.log("- [INFO] DM | 连接参数已" + (on ? "展开" : "收起"));
        });
        head.add(dmToggle, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);
        dmSummary.setForeground(new java.awt.Color(0x2b, 0x35, 0x45));
        card.add(dmSummary, BorderLayout.CENTER);
        card.setPreferredSize(new java.awt.Dimension(UiScale.px(430),
                Math.max(UiScale.px(56), card.getPreferredSize().height)));
        return card;
    }

    /** MySQL 那张卡片：正向是"写入"（目标库下拉），反向是"读自"（顶部连接栏选中的库，只读）。 */
    private JPanel buildMysqlCard() {
        JPanel card = new JPanel(new BorderLayout(0, UiScale.px(2)));
        card.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0xcf, 0xd8, 0xc9)),
                javax.swing.BorderFactory.createEmptyBorder(UiScale.px(5), UiScale.px(10),
                        UiScale.px(5), UiScale.px(8))));
        card.setBackground(new java.awt.Color(0xf6, 0xf9, 0xf3));
        mysqlCardRole.setFont(mysqlCardRole.getFont().deriveFont(java.awt.Font.BOLD));
        mysqlCardRole.setForeground(new java.awt.Color(0x28, 0x5f, 0x33));
        card.add(mysqlCardRole, BorderLayout.NORTH);

        JPanel targetCard = row();
        targetLabel.setForeground(new java.awt.Color(0x2b, 0x35, 0x45));
        targetCard.add(targetLabel);
        targetDbCombo.setEditable(true);
        targetDbCombo.setPreferredSize(new java.awt.Dimension(UiScale.px(150), UiScale.px(24)));
        targetDbCombo.setToolTipText("生成的脚本开头写 USE 这一本库；候选项就是上方「数据源连接信息」"
                + "那颗库下拉里的清单，默认选中当前目标库；留空则不写 USE，"
                + "「写入」按顶部连接栏所选的库执行");
        targetCard.add(targetDbCombo);
        nameCaseHint.setForeground(new java.awt.Color(0x55, 0x5f, 0x6d));
        nameCaseHint.setText("（小写）");
        targetCard.add(nameCaseHint);
        JPanel sourceCard = row();
        sourceCard.add(new JLabel("库"));
        mysqlSourceLabel.setToolTipText("<html>反向读的就是顶部「数据源连接信息」里选中的那一本库；<br>"
                + "要换库去顶部改，这一页不另开一份连接。</html>");
        sourceCard.add(mysqlSourceLabel);
        targetSlot.add(targetCard, "mysqlTarget");
        targetSlot.add(sourceCard, "mysqlSource");
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(targetSlot, BorderLayout.CENTER);
        card.add(body, BorderLayout.CENTER);
        card.setPreferredSize(new java.awt.Dimension(UiScale.px(330),
                Math.max(UiScale.px(56), card.getPreferredSize().height)));
        return card;
    }

    /**
     * 按方向把两张卡片搬进左/右槽。
     *
     * <p>同一个 Swing 控件只能有一个父容器，所以是"搬家"不是"两个都摆"：
     * 先两边清空、再按顺序放回去。换向时清单会一起清空（两边的表结构不是一回事）。
     */
    private void arrangeCards() {
        if (leftSlot == null || rightSlot == null || dmCard == null || mysqlCard == null) {
            return;
        }
        leftSlot.removeAll();
        rightSlot.removeAll();
        if (dmToMysql) {
            leftSlot.add(dmCard, BorderLayout.CENTER);
            rightSlot.add(mysqlCard, BorderLayout.CENTER);
        } else {
            leftSlot.add(mysqlCard, BorderLayout.CENTER);
            rightSlot.add(dmCard, BorderLayout.CENTER);
        }
        leftSlot.revalidate();
        rightSlot.revalidate();
        leftSlot.repaint();
        rightSlot.repaint();
    }

    /**
     * 达梦参数那一排：只在展开时出现，全宽铺在两张卡片下面。
     *
     * <p>收起时整行高度归零（Box 认最大高度），不再像原来那样跟摘要挤在同一行。
     */
    private JPanel buildDmRow() {
        dmRow = row();
        dmParams = row();
        dmParams.add(new JLabel("主机"));
        dmParams.add(hostField);
        dmParams.add(new JLabel("端口"));
        dmParams.add(portField);
        dmParams.add(new JLabel("用户名"));
        dmParams.add(userField);
        dmParams.add(new JLabel("口令"));
        dmParams.add(passField);
        schemaField.setToolTipText("<html>达梦的模式名（OWNER），通常就是用户名，例如 SYSDBA。<br>"
                + "正向：读这个模式的结构；反向：写进这个模式。</html>");
        dmParams.add(schemaCaption);
        dmParams.add(schemaField);
        paramField.setToolTipText("<html>追加到达梦连接串的驱动参数，可留空。<br>"
                + "例如 socketTimeout=8000（参数名以所用驱动为准）。</html>");
        dmParams.add(new JLabel("驱动参数"));
        dmParams.add(paramField);
        dmRow.add(dmParams);
        refreshDmSummary();
        return dmRow;
    }

    /** 展开/收起：可见性、开关文字、摘要、行高一起换；autoWhy 非空时在日志里说明为什么自动展开。 */
    private void setDmExpanded(boolean on, String autoWhy) {
        boolean changed = dmExpanded != on;
        dmExpanded = on;
        // 摘要常驻卡片（一行说清连哪儿），参数行只在展开时出现：
        // 参数行是把整行还给下面的清单，不是替掉摘要
        dmParams.setVisible(on);
        dmToggle.setText(on ? "收起连接参数" : "展开连接参数");
        refreshDmSummary();
        if (changed) {
            refreshDmRowHeight();
        }
        if (changed && on && autoWhy != null && !autoWhy.isEmpty()) {
            logPanel.log("- [INFO] DM | 已自动展开连接参数：" + autoWhy);
        }
    }

    /** 收起后行变矮：BoxLayout 认的是最大高度，不重算的话会留一块空白。 */
    private void refreshDmRowHeight() {
        if (dmRow == null) {
            return;
        }
        dmRow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                dmRow.getPreferredSize().height));
        revalidate();
    }

    /**
     * 达梦卡片里的连接摘要：账号@地址、模式、口令填没填。
     *
     * <p>角色（读自／写入）已经写在卡片标题上，这里不再重复一遍——一行文字只干一件事。
     */
    private void refreshDmSummary() {
        DmReader.Conf c = readConf();
        StringBuilder sb = new StringBuilder();
        sb.append(c.user.isEmpty() ? "(未填账号)" : c.user)
          .append('@').append(c.host.isEmpty() ? "(未填主机)" : c.host).append(':').append(c.port);
        sb.append(" · ").append(schemaCaption.getText()).append(' ')
          .append(c.schema.isEmpty() ? "（未填）" : c.schema);
        sb.append(" · ").append(c.password.isEmpty() ? "口令未填" : "口令已填");
        dmSummary.setText(sb.toString());
    }

    /** 目标与动作：正向是「MySQL 目标库」下拉，反向是"这次读的 MySQL 源"一行只读信息。 */
    /**
     * 主区：左边穿梭框挑表，右边一条操作栏设参数、按动作。
     *
     * <p>用户 2026-11-14 第三次点名「按钮一堆，根本看不清」——根子在**控件平铺**：
     * 目标库、导出范围、两个覆盖开关、三颗动作键挤在同一行，权重完全一样。
     * 改成左右分区之后，页面只剩三处控件群：顶部连接（两行）、中间挑表（四颗搬运键）、
     * 右栏动作（一颗主键 + 两颗次键），眼睛一眼扫得完。
     */
    private JPanel buildMainArea() {
        JPanel p = new JPanel(new BorderLayout(0, UiScale.px(4)));
        p.add(buildSummaryRow(), BorderLayout.NORTH);
        JPanel body = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridy = 0;
        c.fill = java.awt.GridBagConstraints.BOTH;
        c.weighty = 1.0;
        c.gridx = 0;
        c.weightx = 1.0;
        body.add(buildLeftCard(), c);
        c.gridx = 1;
        c.weightx = 0;
        c.insets = new java.awt.Insets(0, UiScale.px(8), 0, UiScale.px(8));
        body.add(buildTransferColumn(), c);
        c.gridx = 2;
        c.weightx = 1.0;
        c.insets = new java.awt.Insets(0, 0, 0, 0);
        body.add(buildRightCard(), c);
        c.gridx = 3;
        c.weightx = 0;
        body.add(buildRail(), c);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /**
     * 列宽：给首选值，也给最小值。
     *
     * <p>两张卡片都不是整屏宽，JTable 会按比例压缩列；只给首选值时"字段数"会被压成
     * "字…"——一个数字列被截断，等于这列白给。所以最小宽度按表头文字留够。
     */
    private static void sizeColumns(javax.swing.JTable t, int name, int comment, int cols,
                                    int keys) {
        int[] pref = {name, comment, cols, keys};
        int[] min = {UiScale.px(90), UiScale.px(70), UiScale.px(52), UiScale.px(52)};
        for (int i = 0; i < pref.length && i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setPreferredWidth(UiScale.px(pref[i]));
            t.getColumnModel().getColumn(i).setMinWidth(min[i]);
        }
    }

    /** 摘要那一行：读回多少、可选多少、待迁移多少（脏了就顶一句在最前面）。 */
    private JPanel buildSummaryRow() {
        JPanel r = row();
        r.add(listLabel);
        return r;
    }

    private JPanel buildLeftCard() {
        JPanel card = new JPanel(new BorderLayout(0, UiScale.px(3)));
        JPanel head = row();
        head.add(caption(leftTitle));
        head.add(leftCount);
        filterBox.setToolTipText("<html>按表名与表注释即时过滤左边的可选表；"
                + "过滤不会动右边已经挑好的表。</html>");
        head.add(new JLabel("过滤"));
        head.add(filterBox);
        Buttons.secondary(clearFilterBtn);
        clearFilterBtn.addActionListener(e -> clearFilter());
        head.add(clearFilterBtn);
        card.add(head, BorderLayout.NORTH);
        card.add(new JScrollPane(table), BorderLayout.CENTER);
        card.add(hintLine("双击一行即加入右边；整库迁移就点「全部 →」"), BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildRightCard() {
        JPanel card = new JPanel(new BorderLayout(0, UiScale.px(3)));
        JPanel head = row();
        head.add(caption(rightTitle));
        head.add(pickedCount);
        card.add(head, BorderLayout.NORTH);
        card.add(new JScrollPane(pickedTable), BorderLayout.CENTER);
        card.add(pickedHint, BorderLayout.SOUTH);
        return card;
    }

    /** 两张清单中间那一列：往右四颗里的前两颗、往左的两颗，竖着摆，指向谁一目了然。 */
    private JPanel buildTransferColumn() {
        JPanel wrap = new JPanel(new java.awt.GridBagLayout());
        javax.swing.Box col = javax.swing.Box.createVerticalBox();
        int w = UiScale.px(92);
        int h = UiScale.px(26);
        addBtn.setToolTipText("把左边选中的表加入待迁移（也可以直接双击左边一行）");
        addAllBtn.setToolTipText("把左边看得见的表全部加入；有过滤时只加过滤结果");
        removeBtn.setToolTipText("把右边选中的表退回左边（也可以直接双击右边一行）");
        removeAllBtn.setToolTipText("清空待迁移清单，全部退回左边");
        addBtn.addActionListener(e -> addSelected());
        addAllBtn.addActionListener(e -> addAllVisible());
        removeBtn.addActionListener(e -> removeSelected());
        removeAllBtn.addActionListener(e -> removeAllPicked());
        JButton[] all = {addBtn, addAllBtn, removeBtn, removeAllBtn};
        for (int i = 0; i < all.length; i++) {
            Buttons.secondary(all[i]);
            all[i].setAlignmentX(0.5f);
            all[i].setPreferredSize(new java.awt.Dimension(w, h));
            all[i].setMaximumSize(new java.awt.Dimension(w, h));
            col.add(all[i]);
            if (i == 1) {
                // 加入与退回之间空一格：两根方向不同的箭头，不加间隔容易点错
                col.add(javax.swing.Box.createVerticalStrut(UiScale.px(14)));
            } else if (i < all.length - 1) {
                col.add(javax.swing.Box.createVerticalStrut(UiScale.px(6)));
            }
        }
        wrap.add(col);
        return wrap;
    }

    /**
     * 右侧操作栏：目标、导出范围、覆盖开关，三颗动作键，最后一句「下一步」。
     *
     * <p>竖排之后阅读顺序就是操作顺序。写入是唯一填色的主键，另外两颗描边：
     * 脚本只是中间产物，真正要交付的是把数据写进去。
     */
    private JPanel buildRail() {
        JPanel rail = new JPanel();
        rail.setLayout(new javax.swing.BoxLayout(rail, javax.swing.BoxLayout.Y_AXIS));
        rail.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, UiScale.px(10),
                UiScale.px(2), UiScale.px(2)));
        rail.add(railHeading("本次迁移"));
        rail.add(railLine(railTables));
        rail.add(javax.swing.Box.createVerticalStrut(UiScale.px(10)));

        // 目标库那一格已经搬到 MySQL 卡片里（它就是"写入"那一端本身），右栏不再重复一遍
        rail.add(javax.swing.Box.createVerticalStrut(UiScale.px(8)));
        rail.add(railHeading("范围"));
        rail.add(railLine(exportMode));
        rail.add(railLine(dropExisting));
        rail.add(railLine(clearTarget));

        rail.add(javax.swing.Box.createVerticalStrut(UiScale.px(12)));
        // 「先 DROP」与「先清空」互斥：建表那一档新建的表是空的，清空没意义；
        // 只灌数据那一档也没有建表语句可 DROP。按模式换显隐，不并排放两颗用不上的勾。
        rail.add(fullWidth(writeBtn));
        rail.add(javax.swing.Box.createVerticalStrut(UiScale.px(4)));
        rail.add(fullWidth(genBtn));
        rail.add(javax.swing.Box.createVerticalStrut(UiScale.px(10)));
        nextHint.setForeground(new java.awt.Color(0x1f, 0x5f, 0xd0));
        rail.add(railLine(nextHint));
        rail.add(javax.swing.Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(rail);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setPreferredSize(new java.awt.Dimension(UiScale.px(300), 0));
        sp.setMinimumSize(new java.awt.Dimension(UiScale.px(260), 0));
        JPanel box = new JPanel(new BorderLayout());
        box.add(sp, BorderLayout.CENTER);
        return box;
    }

    /** 右栏的小标题：整行加粗、上留一点空。 */
    private JLabel railHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        l.setAlignmentX(0f);
        return l;
    }

    /** 右栏里的一行：宽度吃满、高度钉在自己的首选值上，避免 BoxLayout 把行拉散。 */
    private static JPanel railLine(java.awt.Component c) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        p.add(c);
        p.setAlignmentX(0f);
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** 右栏里的整宽按钮：BoxLayout 默认按首选宽度居中，不设最大宽度就撑不满一列。 */
    private static JButton fullWidth(JButton b) {
        b.setAlignmentX(0f);
        b.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                Math.max(b.getPreferredSize().height, UiScale.px(26))));
        return b;
    }

    private static JLabel caption(JLabel l) {
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        return l;
    }

    private static JLabel hintLine(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new java.awt.Color(0x77, 0x80, 0x8c));
        l.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, UiScale.px(2), 0, 0));
        return l;
    }

    private static JPanel row() {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
    }

    /** 竖排里的一行：高度钉在它自己的首选高度上，余量留给下面的清单，行不被拉高。 */
    private static JPanel fixedHeight(JPanel row) {
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.setAlignmentX(0f);
        return row;
    }

    /** 当前方向的文案、两张卡片的角色标题与位置：翻方向只走这一处。 */
    private void refreshDirectionTexts() {
        connectBtn.setText(dmToMysql ? "连达梦并列表" : "连 MySQL 并列表");
        connectBtn.setToolTipText(dmToMysql
                ? "连达梦读回整库表结构，列在下面左边"
                : "从顶部连接栏选中的 MySQL 库读回表结构，列在下面左边");
        writeBtn.setText(dmToMysql ? "写入 MySQL 目标库" : "写入 DM 目标库");
        schemaCaption.setText(dmToMysql ? "模式" : "写入模式");
        // ① / ② 的编号跟着位置走：左边永远是第一步（读）、右边永远是第二步（写）
        dmCardRole.setText(dmToMysql ? "① 读自 · 达梦 DM8" : "② 写入 · 达梦 DM8");
        mysqlCardRole.setText(dmToMysql ? "② 写入 · MySQL" : "① 读自 · MySQL");
        nameCaseHint.setText(dmToMysql ? "（小写）" : "（大写）");
        nameCaseHint.setToolTipText(dmToMysql
                ? "表名与字段名写 MySQL 一律小写：Linux 下的库区分大小写，与建表页的习惯一致"
                : "表名与字段名写达梦一律大写：达梦把未加引号的标识符折大写，"
                        + "建成大写后不带引号也查得到");
        ((java.awt.CardLayout) targetSlot.getLayout())
                .show(targetSlot, dmToMysql ? "mysqlTarget" : "mysqlSource");
        // 正向默认展开（源参数必填）、反向默认收起（只在写入时要）；手动改过就按用户记的来
        setDmExpanded(dmManual[dmToMysql ? 0 : 1], "");
        refreshDmSummary();
        arrangeCards();
        refreshSchemaMode();
    }

    /** 当前导出范围是不是「导出表结构和数据」（下拉第一项，默认）。 */
    private boolean withSchema() {
        return exportMode.getSelectedIndex() == 0;
    }

    /**
     * 刷导出范围这一档：按钮叫什么、哪颗覆盖开关露面，都在这一处定。
     *
     * <p>两档都会搬数据，差别只在要不要先写建表语句——所以「生成」那颗键的文案必须跟着换，
     * 不然人不知道这一下会出什么、会不会动目标表的 DDL。
     */
    private void refreshSchemaMode() {
        boolean create = withSchema();
        dropExisting.setVisible(create);
        clearTarget.setVisible(!create);
        genBtn.setText(create ? "导出建表 + 数据 .sql" : "导出数据 .sql");
        refreshButtonStates();
    }

    /** 下拉里是短名（结构+数据／仅数据），日志与提示仍说全称。 */
    private String exportModeName() {
        return withSchema() ? "导出表结构和数据" : "导出数据";
    }

    /** 切换导出范围：上一份产物作废（内容不一样了），并把这一档的口径说清楚。 */
    private void onSchemaModeChanged() {
        boolean create = withSchema();
        lastOut = null;
        lastData = null;
        lastExportFile = null;
        refreshSchemaMode();
        logPanel.log("- [INFO] DM | 导出范围改成「" + exportModeName() + "」："
                + (create
                        ? "先 CREATE TABLE（" + (dropExisting.isSelected()
                                ? "含 DROP IF EXISTS" : "不 DROP") + "），再逐表 INSERT"
                        : "只写 INSERT，目标表必须已经存在"
                                + (clearTarget.isSelected() ? "（每表先 DELETE）" : "（冲突逐表记账）"))
                + "；上一份脚本已作废，重新生成");
    }

    /** 反向那句"MySQL 源"：顶部连接栏此刻选的是哪一本，就在这儿写出来。 */
    private void refreshSourceField() {
        Config cfg = host.currentConfig();
        if (cfg == null || !cfg.hasDatabase()) {
            mysqlSourceLabel.setText("还没选库 → 去顶部选一本");
            mysqlSourceLabel.setForeground(new java.awt.Color(0xb3, 0x26, 0x1d));
            mysqlSourceLabel.setToolTipText("反向读的就是顶部「数据源连接信息」里选中的那一本库；"
                    + "要换库去顶部改，这一页不另开一份连接");
            return;
        }
        String user = cfg.user == null ? "" : cfg.user.trim();
        String full = cfg.database.trim() + " @ " + cfg.host + ":" + cfg.port
                + (user.isEmpty() ? "" : "（" + user + "）");
        // 库名可以很长，这一行后面还要摆三个勾选与三颗按钮：超长就掐一段，全文挂在悬停上
        // 右栏只有 300 宽：这里放得下十来个字，全文挂在悬停上
        mysqlSourceLabel.setText(full.length() > 18 ? full.substring(0, 18) + "…" : full);
        mysqlSourceLabel.setForeground(new java.awt.Color(0x33, 0x3c, 0x4a));
        mysqlSourceLabel.setToolTipText("<html>" + full + "<br>"
                + "反向读的就是顶部「数据源连接信息」里选中的那一本库；要换库去顶部改。</html>");
    }

    /** 顶部连接栏那套 MySQL 源的身分串：反向用它判"读回之后源换没换"。 */
    private String sourceKey() {
        Config cfg = host.currentConfig();
        return sourceKey(cfg == null || cfg.database == null ? "" : cfg.database.trim());
    }

    /**
     * 同上，库名由调用方给：{@link #syncTargetDb} 收到的是顶部下拉刚换上的那一本，
     * 这时 {@code currentConfig()} 还可能停在旧值上，用传进来的才判得准。
     */
    private String sourceKey(String db) {
        Config cfg = host.currentConfig();
        if (cfg == null) {
            return db == null ? "" : db.trim();
        }
        String user = cfg.user == null ? "" : cfg.user.trim();
        return user + "@" + cfg.host + ":" + cfg.port + "/" + (db == null ? "" : db.trim());
    }

    /**
     * 写入模式空着时按登录用户名补一格：达梦的模式通常就是用户名，
     * 这一格不填，反向走到"写入"必然被拦一次——补上并说一句，用户看得见也改得动。
     */
    private boolean ensureSchema() {
        if (!schemaField.getText().trim().isEmpty()) {
            return true;
        }
        String user = userField.getText().trim();
        if (user.isEmpty()) {
            return false;
        }
        schemaField.setText(user);
        logPanel.log("- [INFO] DM | 「" + schemaCaption.getText() + "」原来空着，按登录用户名填成 "
                + user.toUpperCase(java.util.Locale.ROOT) + "；不对就改这一格");
        return true;
    }

    /**
     * 行交互：双击搬运 + 右键复制表名。
     *
     * <p>双击是穿梭框的主路径（选一行、双击、它就到对面去了），比"选中再点按钮"少一步；
     * 中间那四颗按钮留着，是给"一次挑十几张"用的。
     */
    private void installRowMenu(final javax.swing.JTable t, final boolean isLeft) {
        t.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                maybeRowMenu(e, t, isLeft);
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                maybeRowMenu(e, t, isLeft);
            }

            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2 || e.isPopupTrigger()) {
                    return;
                }
                int row = t.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                int index = tableIndexOf(t, isLeft, row);
                if (index < 0) {
                    return;
                }
                if (isLeft) {
                    if (picked.add(Integer.valueOf(index))) {
                        afterTransfer("「" + tables.get(index).name + "」加入待迁移");
                    }
                } else if (picked.remove(Integer.valueOf(index))) {
                    afterTransfer("「" + tables.get(index).name + "」退回左边");
                }
            }
        });
    }

    /** 视图行 → tables 下标：左边要过 shownRows 换算，右边按待迁移顺序取。 */
    private int tableIndexOf(javax.swing.JTable t, boolean isLeft, int view) {
        if (isLeft) {
            return view >= 0 && view < shownRows.size() ? shownRows.get(view).intValue() : -1;
        }
        List<Integer> order = new ArrayList<Integer>(picked);
        return view >= 0 && view < order.size() ? order.get(view).intValue() : -1;
    }

    private void maybeRowMenu(java.awt.event.MouseEvent e, javax.swing.JTable t, boolean isLeft) {
        if (!e.isPopupTrigger()) {
            return;
        }
        final int row = t.rowAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        if (!t.isRowSelected(row)) {
            t.setRowSelectionInterval(row, row);
        }
        final int index = tableIndexOf(t, isLeft, row);
        if (index < 0) {
            return;
        }
        final String name = tables.get(index).name;
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem copy = new javax.swing.JMenuItem("复制表名 " + name);
        copy.addActionListener(ev -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(name), null);
            logPanel.log("- [INFO] DM | 已复制表名 " + name);
        });
        menu.add(copy);
        javax.swing.JMenuItem move = new javax.swing.JMenuItem(
                isLeft ? "加入待迁移" : "退回左边");
        move.addActionListener(ev -> {
            if (isLeft) {
                picked.add(Integer.valueOf(index));
            } else {
                picked.remove(Integer.valueOf(index));
            }
            afterTransfer("「" + name + "」" + (isLeft ? "加入待迁移" : "退回左边"));
        });
        menu.add(move);
        menu.show(t, e.getX(), e.getY());
    }

    /** 日志段：上面一排只有右对齐的「清除日志」，下面才是文本框，与其余四页同一排布。 */
    private JPanel buildLogBox() {
        clearLogBtn.setToolTipText("清空这一页的日志，清单、待迁移与已导出的文件都不动");
        clearLogBtn.addActionListener(e -> logPanel.clearLog());
        JPanel logHead = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(6), UiScale.px(2)));
        logHead.add(clearLogBtn);
        // 套滚动条且不写死高度：日志段默认就压在它自己的最小行数（4 行）上，
        // 不再像之前那样固定占 160px——一行没写也白占版面，用户报「日志撑满整个工具」。
        JScrollPane logScroll = new JScrollPane(logPanel);
        logScroll.setMinimumSize(new java.awt.Dimension(UiScale.px(300),
                logPanel.getMinimumSize().height));
        // 日志段的最小高度＝表头一行 + 4 行日志：拖到底也就是这么高，不会被拖成一条缝
        logMinHeight = logPanel.getMinimumSize().height + UiScale.px(34);
        // 视口白底、日志浅灰底：日志行不满一框时下面那截白底看着像一大块空白面板
        logScroll.getViewport().setBackground(logPanel.getBackground());
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.add(logHead, BorderLayout.NORTH);
        logBox.add(logScroll, BorderLayout.CENTER);
        return logBox;
    }

    /**
     * 主区 / 日志那条分隔条：可拖，口径与第一页一模一样。
     *
     * <p>第一页那套是四条：① 日志段有明确的最小高度（表头 + 4 行日志）；② 初始就压在最小高度上，
     * 余量全给主区；③ 用户按下把手之后不再自动摆放，按他拖到哪儿算哪儿；④ 两头都夹取，
     * 拖不出"日志没了"或"主区没了"。之前这一页只给了 resizeWeight，没有 ①③④——
     * 拖动看着能动，但窗口一变、页面一重排就被 JSplitPane 自己摆回去，手感就是"调不动"。
     */
    private javax.swing.JSplitPane logBodySplit(java.awt.Component top, java.awt.Component bottom) {
        javax.swing.JSplitPane sp = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, top, bottom) {
            @Override public void doLayout() {
                // 先摆好日志高度再排：JSplitPane 只记"绝对分隔位置"，摆过一次之后多出来的
                // 高度会被下面那段吃掉（实测日志跟着窗口一起长）。放在 doLayout 里是同步的，
                // 不靠 componentResized——那个是投递事件，窗口一变大先长高、下一拍再缩回，
                // 看得见地抖一下；自检里更麻烦：事件还没跑，量到的就是没摆过的样子
                placeBeforeLayout(this);
                super.doLayout();
            }
        };
        sp.setDividerSize(UiScale.px(6));
        sp.setBorder(null);
        sp.setResizeWeight(1.0);
        watchLogDivider(sp);
        return sp;
    }

    /**
     * 日志段落位：压到最小高度，余量全给主区（第一页同一口径）。
     *
     * <p>只在首帧和"双击把手"时调：拖过之后由 JSplitPane 自己记着位置，
     * {@code resizeWeight=1} 保证窗口变大时多出来的高度进主区、日志不被拉长。
     * 高度还是 0（页签没选中、还没排版）时直接返回——拿 0 去摆会把分隔位置写成负数。
     */
    private void applyLogSplit() {
        placeBeforeLayout(logSplit);
    }

    /**
     * 把日志段摆到它该有的高度：用户没拖过就压到最小，拖过就保持他拖的那一档。
     *
     * <p>正在拖的时候一律不插手（{@link #logDragging}）——拖动过程中 JSplitPane 每动一下就
     * 重排一次，这时候按记忆值去摆会把把手拽回去，手感直接变成"拖不动"。
     */
    private void placeBeforeLayout(javax.swing.JSplitPane sp) {
        if (sp == null || logDragging) {
            return;
        }
        int h = sp.getHeight();
        int minLog = Math.max(logMinHeight, sp.getBottomComponent().getMinimumSize().height);
        if (h < UiScale.px(200) || minLog <= 0) {
            return;
        }
        int want = logUserMoved && logUserHeight > 0 ? logUserHeight : minLog;
        // 上面至少留 200：日志拖到底也不会把两张清单压没
        want = Math.max(minLog, Math.min(want, h - UiScale.px(200)));
        int loc = h - want;
        if (sp.getDividerLocation() != loc) {
            sp.setDividerLocation(loc);
        }
    }

    /**
     * 分隔条把手：双击回到默认高度。
     *
     * <p>拖大了之后想还原，不该让人靠手感拖回去——双击这个动作在第一页没有，
     * 但这里多了它不冲突，且把手只有 6px，找起来比拖回去省事。
     */
    private void watchLogDivider(javax.swing.JSplitPane pane) {
        javax.swing.plaf.SplitPaneUI ui = pane.getUI();
        if (!(ui instanceof javax.swing.plaf.basic.BasicSplitPaneUI)) {
            return;
        }
        java.awt.Component divider =
                ((javax.swing.plaf.basic.BasicSplitPaneUI) ui).getDivider();
        if (divider == null) {
            return;
        }
        divider.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                logUserMoved = true;      // 这一页的分栏从此归用户，不再自动摆回最小
                logDragging = true;
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                logDragging = false;
                // 松手时把"用户要的高度"记下来：之后窗口怎么变都按这个数摆。
                // 按分隔位置算，不读子控件高度——松手这一拍还没重排，子控件还是旧高度
                logUserHeight = logSplit.getHeight() - logSplit.getDividerLocation()
                        - logSplit.getDividerSize();
            }

            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    logUserMoved = false; // 双击还原默认高度
                    placeBeforeLayout(logSplit);
                    logPanel.log("- [INFO] DM | 日志区已回到默认高度");
                }
            }
        });
    }

    private void refreshButtonStates() {
        // 挑表是这一页的前置：待迁移为空就没有"要搬什么"，三颗键一律灰着——
        // 不再有"一行不选＝全部"那条暗规则（那正是上一版最容易导错库的地方）
        boolean ready = !tables.isEmpty() && !picked.isEmpty();
        genBtn.setEnabled(ready);
        // 导出与写入都不要求"先生成"：两边都是当场从源库读，导出的那一份只是存档用
        writeBtn.setEnabled(ready);
        genBtn.setToolTipText(!ready
                ? emptyReadyTip()
                : (withSchema()
                        ? "把建表语句与数据写成一份 .sql：先 CREATE TABLE，再逐表 INSERT"
                        : "把数据写成一份只含 INSERT 的 .sql（目标表需已存在）")
                        + "；只导右边「待迁移」里那几张，直接落到 " + exportDir().getAbsolutePath()
                        + "（右键可另存为）");
        writeBtn.setToolTipText(!ready ? emptyReadyTip() : writeTargetTip());
        refreshNextHint();
    }

    /** 待迁移为空时的统一拦截：先说清"没有要搬的表"，再指路怎么挑。 */
    private void warnNoPicked(String what) {
        String msg = tables.isEmpty()
                ? "还没读回表结构：先点「" + connectBtn.getText() + "」。"
                : "待迁移清单还是空的，所以" + what + "：在左边挑表（双击一行即加入），"
                        + "或点「全部 →」整库迁移。";
        host.warn(msg);
        logPanel.log("! [WARN] DM | " + msg);
    }

    /** 三颗动作键都灰着时的那句提示：缺结构还是缺表，分开说。 */
    private String emptyReadyTip() {
        return tables.isEmpty()
                ? "先点「" + connectBtn.getText() + "」读回结构"
                : "先在左边挑表：双击一行，或点「全部 →」整库迁移";
    }

    /** 右栏那句「下一步」：按"有没有结构 / 挑没挑表"分段说，挑好就指去写入或导出。 */
    private void refreshNextHint() {
        String text;
        if (!lastWriteNote.isEmpty()) {
            text = lastWriteNote + "｜可继续挑表再写，或换导出范围重新生成";
        } else if (tables.isEmpty()) {
            text = "下一步：先点「" + connectBtn.getText() + "」把表结构读回来";
        } else if (picked.isEmpty()) {
            text = "下一步：在左边挑表——双击一行就进右边，整库迁移点「全部 →」";
        } else if (lastExportFile != null) {
            text = "上次导出：" + lastExportFile.getName() + "｜可继续点「"
                    + writeBtn.getText() + "」搬数据，或再导一份";
        } else {
            text = "下一步：直接点「" + writeBtn.getText() + "」搬数据；"
                    + "想留一份 .sql 存档就点「" + genBtn.getText() + "」";
        }
        // 右栏只有 300 宽：按像素宽度折行，不然这句会被右边界切掉半句
        nextHint.setText("<html><body style='width:240px'>" + escHtml(text) + "</body></html>");
        nextHint.setToolTipText(text);
    }

    /** 写入按钮的提示：这一下会发到哪儿、缺什么。缺驱动或模式没填时，不用点下去才知道。 */
    private String writeTargetTip() {
        if (dmToMysql) {
            String db = targetDb();
            if (db.isEmpty()) {
                Config cfg = host.currentConfig();
                db = cfg == null || cfg.database == null ? "" : cfg.database.trim();
            }
            return "把数据直接写到 MySQL 库 " + (db.isEmpty() ? "（顶部所选库）" : db)
                    + "，不落脚本；" + (withSchema() ? "先建表" : "目标表须已存在");
        }
        DmReader.Conf c = readConf();
        if (!driverOk) {
            return "缺达梦驱动：放到 " + DmReader.driverDir().getPath() + " 再重开程序";
        }
        if (c.schema.isEmpty()) {
            return "写入模式还空着：填一个达梦模式名（通常就是登录用户名）";
        }
        return "把数据直接写到达梦 " + c.host + ":" + c.port + " 的模式 " + c.schema
                + "，不落脚本；" + (withSchema() ? "先建表" : "目标表须已存在");
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
        refreshSourceField();
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
        refreshSourceField();
        String db = current == null ? "" : current.trim();
        if (db.isEmpty()) {
            return;
        }
        if (before.isEmpty() || before.equals(followedTarget)) {
            setTargetDb(db);
            followedTarget = db;
        }
        // 反向的源就是顶部连接栏那本库：读了 A 又切到 B，手上这份结构已经不是 B 的，
        // 摘要里点出来并拦住生成——这是反向唯一该标脏的东西。
        if (!dmToMysql && !loadedSourceKey.isEmpty()) {
            String now = sourceKey(db);
            if (!now.equalsIgnoreCase(loadedSourceKey)) {
                markStale("MySQL 源换成了 " + now + "（读回时是 " + loadedSourceKey + "）");
            }
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

    /** 自检用：最近一次建表语句（预览去掉之后，正文那份内存产物只留在这儿）。 */
    public String lastSqlForSelftest() {
        return lastOut == null ? "" : lastOut.sql;
    }

    public String listLabelText() {
        return listLabel.getText();
    }

    public boolean generateEnabled() {
        return genBtn.isEnabled();
    }

    /** 自检用：导出键的悬停提示（里面写着落盘目录）。 */
    public String exportTipText() {
        return genBtn.getToolTipText();
    }

    /** 自检用：「导出 .sql」那颗键此刻能不能点。 */
    public boolean exportEnabled() {
        return genBtn.isEnabled();
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

    /** 自检用：重扫一遍驱动目录（自检临时放一个假驱动进去，再刷新这一格）。 */
    public void refreshDriverStateForSelftest() {
        refreshDriverLabel();
    }

    /** 自检用：驱动那一格此刻露不露脸（装好了就该藏起来）。 */
    public boolean driverVisibleForSelftest() {
        return driverLabel.isVisible();
    }

    /** 驱动那一格的悬停提示：完整路径与步骤都在这儿（横排放不下整句）。 */
    public String driverTipText() {
        return driverLabel.getToolTipText() == null ? "" : driverLabel.getToolTipText();
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
        List<String> names = new ArrayList<String>();
        for (DataMover.Job j : buildJobs(selectedTables(), new ArrayList<String>())) {
            names.add(j.targetTable);
        }
        return previewNames(names);
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

    /**
     * 翻方向：文案、角色牌、目标卡跟着换，清单与正文清空重来（两向的表结构是两本库的，
     * 混在一张清单里只会导错方向）。
     *
     * <p>点的是已经选中的那个钮时整段返回：单选钮被再点一次不该把辛苦读回来的清单清掉。
     */
    private void applyDirection(boolean toMysql) {
        if (toMysql == dmToMysql) {
            return;
        }
        dmToMysql = toMysql;
        tables.clear();
        lastOut = null;
        lastData = null;
        lastExportFile = null;
        lastWriteNote = "";
        loadedFrom = "";
        loadedKind = dmToMysql ? "模式" : "库";
        loadedSourceKey = "";
        serverVersion = "";
        stale = false;
        staleReason = "";
        refreshDirectionTexts();
        refreshSourceField();
        table.clearSelection();
        picked.clear();
        refill();
        refillPicked();
        refreshButtonStates();
        logPanel.log("- [INFO] DM | 方向切到「"
                + (dmToMysql ? "DM8 → MySQL" : "MySQL → DM8") + "」："
                + (dmToMysql
                        ? "连达梦读结构，生成 MySQL 建表语句"
                        : "读顶部连接栏选中的 MySQL 库，生成达梦建表语句；目标模式在「写入模式」那格")
                + "；表名与字段名按目标端折" + (dmToMysql ? "小写" : "大写")
                + "（固定口径，不设开关）。清单已清空，先点「" + connectBtn.getText() + "」");
    }

    /** MySQL → DM8：源是顶部连接栏所选的 MySQL 库，读信息架构表结构。 */
    private void connectAndListMysql() {
        final Config cfg = host.currentConfig();
        refreshSourceField();
        if (cfg == null || !cfg.hasDatabase()) {
            logPanel.error("x [ERROR] MYSQL | 还没选 MySQL 库：反向读的就是顶部「数据源连接信息」"
                    + "里选中的那一本，这一页不另开一份 MySQL 连接。先去那里加载库列表并选好要读的库，"
                    + "再回来点「" + connectBtn.getText() + "」。");
            host.warn("还没选 MySQL 库：先在顶部「数据源连接信息」里选好要读的库");
            return;
        }
        final String db = cfg.database.trim();
        host.runDmTask("正在连接 MySQL 并读取表结构…", new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                sink.step("连接 MySQL " + cfg.host + ":" + cfg.port + " 库 " + db);
                try {
                    MysqlReader.Result r = MysqlReader.readAll(cfg, db,
                            n -> sink.advance(1, "读 " + n), t -> sink.setTotal(t),
                            () -> sink.cancelled());
                    if (sink.cancelled()) {
                        sink.log("! [WARN] MYSQL | 已取消：已读到 " + r.tables.size()
                                + " 张，界面上仍是上一份结构");
                        return null;
                    }
                    return new Object[]{r, null};
                } catch (Exception ex) {
                    return new Object[]{null, LogText.describe(ex)};
                }
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                if (res == null) {
                    return;
                }
                Object[] arr = (Object[]) res;
                if (arr[1] != null) {
                    logPanel.error("x [ERROR] MYSQL | 连接或读结构失败：无法连接到 "
                            + cfg.host + ":" + cfg.port + "（库 " + db + "）"
                            + LogText.NL + LogText.indentDetail(String.valueOf(arr[1])));
                    host.informError("连不上 MySQL", "无法连接到 " + cfg.host + ":" + cfg.port
                            + "（库 " + db + "）：\n" + arr[1]);
                    return;
                }
                MysqlReader.Result r = (MysqlReader.Result) arr[0];
                applyTables(r.tables, r.version, "库", db);
            }
        });
    }

    /**
     * 写入按钮：勾着「创建表」就先建表再灌数据，不勾则只搬数据。
     *
     * <p>两步放在同一个任务里跑完，不拆成两次点击——建表与灌数据是同一件事的两半，
     * 中间让人再点一次，最容易出的就是"表建好了、数据忘了搬"。
     *
     * <p>数据是当场从源库读的（不重放生成好的脚本）：脚本那份给人审与存档，
     * 真搬的时候按批读写，几百万行也不会先拼成一个巨大字符串。
     */
    private void writeToTarget() {
        if (tables.isEmpty()) {
            host.warn("还没读回表结构：先点「" + connectBtn.getText() + "」。");
            return;
        }
        if (stale) {
            host.warn(staleReason + "：先重连，再写入。");
            return;
        }
        final List<DmMysql.Table> picked = selectedTables();
        if (picked.isEmpty()) {
            warnNoPicked("没有写入任何数据");
            return;
        }
        final boolean withDdl = withSchema();
        final boolean clear = !withDdl && clearTarget.isSelected();
        final DmMysql.Output ddl = withDdl ? buildDdl(picked) : null;
        if (withDdl && (ddl == null || ddl.sql.trim().isEmpty())) {
            host.warn("建表语句没生成出来，先点「" + genBtn.getText() + "」看清原因再写入。");
            return;
        }
        if (ddl != null) {
            for (String w : ddl.warnings) {
                logPanel.log("! [WARN] DM-DDL | " + w);
            }
        }
        final java.util.Set<String> skippedSources = new java.util.HashSet<String>();
        if (ddl != null) {
            skippedSources.addAll(ddl.skipped);
        }
        final List<String> warns = new ArrayList<String>();
        final List<DataMover.Job> jobs = buildJobs(picked, warns, skippedSources);
        for (String w : warns) {
            logPanel.log("! [WARN] DATA | " + w);
        }
        if (jobs.isEmpty()) {
            host.warn("这批表在目标端译不出可用的表名/字段名，没有可写的数据。");
            return;
        }

        final Config mysqlCfg = host.currentConfig();
        final String mysqlDb;
        if (dmToMysql) {
            String want = targetDb();
            mysqlDb = want.isEmpty()
                    ? (mysqlCfg == null || mysqlCfg.database == null ? "" : mysqlCfg.database.trim())
                    : want;
        } else {
            mysqlDb = mysqlCfg == null || mysqlCfg.database == null ? "" : mysqlCfg.database.trim();
        }
        if (mysqlDb.isEmpty()) {
            host.warn(dmToMysql
                    ? "还没选 MySQL 目标库：在「MySQL 目标库」那格填一本，或去顶部选好库。"
                    : "还没选 MySQL 源库：先在顶部「数据源连接信息」里选好要读的库。");
            return;
        }
        if (!ensureSchema()) {
            host.warn("写入模式为空：在上面「" + schemaCaption.getText()
                    + "」那格填一个达梦模式名（通常就是登录用户名）。");
            return;
        }
        final DmReader.Conf dmConf = readConf();
        // 写入两个方向都要驱动：正向从达梦读源数据，反向往达梦写
        if (!DmReader.driverPresent()) {
            host.warn(DmReader.missingDriverHint());
            return;
        }
        if (!dmToMysql && dmConf.password.isEmpty()) {
            setDmExpanded(true, "口令还空着");
            logPanel.log("! [WARN] DM | 达梦口令是空的：写入多半会被拒，先在「连接参数」里补上");
        }
        final String dmSchema = dmConf.schema.toUpperCase(java.util.Locale.ROOT);
        String desc = dmToMysql
                ? "MySQL 库 " + (mysqlDb.isEmpty() ? "（顶部所选库）" : mysqlDb)
                : "达梦模式 " + dmSchema + "（" + dmConf.host + ":" + dmConf.port + "）";
        if (confirmWrite && !askWriteConfirm(desc, withDdl ? "确认建表并写入数据" : "确认只写入数据",
                jobs, withDdl, clear)) {
            return;
        }

        host.runDmTask(withDdl ? "正在建表并写入数据…" : "正在写入数据…",
                new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                Object ddlRep = null;
                // 第一步：建表（只有勾了「创建表」才走）
                if (withDdl) {
                    if (dmToMysql) {
                        DbClient db = null;
                        try {
                            db = new DbClient(mysqlCfg);
                            final int[] reported = {0};
                            ddlRep = SqlExecute.run(db, ddl.sql, sink::log,
                                    (done, total) -> {
                                        sink.setTotal(total);
                                        sink.advance(Math.max(done - reported[0], 0),
                                                "建表 " + done + "/" + total);
                                        reported[0] = done;
                                    }, () -> sink.cancelled(), null);
                        } catch (Exception e) {
                            sink.logError("x [ERROR] DM-EXEC | 建表失败："
                                    + LogText.describe(e));
                            return null;
                        } finally {
                            if (db != null) {
                                db.close();
                            }
                        }
                    } else {
                        DmReader dm = null;
                        try {
                            dm = DmReader.open(dmConf);
                            ddlRep = dm.runScript(ddl.sql, sink::log, () -> sink.cancelled());
                        } catch (Exception e) {
                            sink.logError("x [ERROR] DM-EXEC | 连不上达梦，建表没开始："
                                    + LogText.describe(e));
                            return null;
                        } finally {
                            if (dm != null) {
                                dm.close();
                            }
                        }
                    }
                }
                // 第二步：搬数据
                DataMover.Report rep;
                if (dmToMysql) {
                    DmReader srcDm = null;
                    try {
                        srcDm = DmReader.open(dmConf);
                        rep = DataMover.writeToMysql(srcDm, dmSchema, jobs, mysqlCfg, mysqlDb,
                                false, sink);
                    } catch (Exception e) {
                        sink.logError("x [ERROR] DATA | 连不上达梦源库，数据没开始："
                                + LogText.describe(e));
                        return null;
                    } finally {
                        if (srcDm != null) {
                            srcDm.close();
                        }
                    }
                } else {
                    DmReader targetDm = null;
                    try {
                        targetDm = DmReader.open(dmConf);
                        rep = DataMover.writeToDm(mysqlCfg, mysqlDb, jobs, targetDm, dmSchema,
                                clear, sink);
                    } catch (Exception e) {
                        sink.logError("x [ERROR] DATA | 连不上达梦目标库，数据没开始："
                                + LogText.describe(e));
                        return null;
                    } finally {
                        if (targetDm != null) {
                            targetDm.close();
                        }
                    }
                }
                return new Object[]{ddlRep, rep};
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                if (!(res instanceof Object[])) {
                    return;
                }
                Object[] arr = (Object[]) res;
                DataMover.Report rep = arr[1] instanceof DataMover.Report
                        ? (DataMover.Report) arr[1] : null;
                if (rep == null) {
                    return;
                }
                lastData = rep;
                lastWriteNote = "上次写入：" + rep.tables.size() + " 张表、" + rep.rows + " 行"
                        + (rep.failed() > 0 ? "，失败 " + rep.failed() + " 张" : "")
                        + (rep.cancelled ? "（中途取消）" : "");
                StringBuilder msg = new StringBuilder("写入结束：");
                if (arr[0] instanceof DmReader.ExecReport) {
                    DmReader.ExecReport d = (DmReader.ExecReport) arr[0];
                    msg.append("建表执行到第 ").append(d.executed).append("/")
                       .append(d.statements).append(" 条；");
                } else if (arr[0] instanceof SqlExecute.Report) {
                    SqlExecute.Report d = (SqlExecute.Report) arr[0];
                    msg.append("建表执行到第 ").append(d.executed).append("/")
                       .append(d.statements).append(" 条；");
                }
                msg.append("数据 ").append(rep.tables.size()).append(" 张表、")
                   .append(rep.rows).append(" 行")
                   .append(rep.cleared > 0 ? "（清空旧数据 " + rep.cleared + " 行）" : "")
                   .append(rep.failed() > 0 ? "，失败 " + rep.failed() + " 张（见本页日志）" : "")
                   .append(rep.cancelled ? "，中途取消" : "");
                host.inform(msg.toString());
                refreshButtonStates();
            }
        });
    }

    public void connectAndList() {
        if (!dmToMysql) {
            connectAndListMysql();
            return;
        }
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
                    setDmExpanded(true, "连不上达梦，先核对连接参数");
                    logPanel.error("x [ERROR] DM | 连接或读结构失败：无法连接到 "
                            + cfg.host + ":" + cfg.port + "（模式 " + cfg.schema + "）"
                            + LogText.NL + LogText.indentDetail(String.valueOf(arr[1])));
                    host.informError("连不上达梦", "无法连接到 " + cfg.host + ":" + cfg.port
                            + "（用户 " + cfg.user + "，模式 " + cfg.schema + "）：\n" + arr[1]);
                    return;
                }
                applyTables((List<DmMysql.Table>) arr[0], String.valueOf(arr[2]),
                        "模式", cfg.schema);
            }
        });
    }

    private void applyTables(List<DmMysql.Table> read, String version,
                             String kind, String source) {
        // 重读结构（改完参数重连一次）不该把挑好的几十张丢掉：按表名接回来，
        // 表还在就仍是待迁移，消失的表在日志里点一句
        java.util.Set<String> keepNames = new java.util.HashSet<String>();
        for (Integer i : picked) {
            if (i != null && i >= 0 && i < tables.size()) {
                keepNames.add(tables.get(i).name);
            }
        }
        tables.clear();
        tables.addAll(read);
        picked.clear();
        int restored = 0;
        for (int i = 0; i < tables.size(); i++) {
            if (keepNames.contains(tables.get(i).name)) {
                picked.add(Integer.valueOf(i));
                restored++;
            }
        }
        loadedFrom = source == null ? "" : source.trim();
        loadedKind = kind;
        // 反向的源在顶部连接栏：把读这一份时它长什么样记下来，之后切库能比出来
        loadedSourceKey = dmToMysql ? "" : sourceKey();
        serverVersion = version == null ? "" : version;
        stale = false;
        staleReason = "";
        lastWriteNote = "";
        table.clearSelection();
        refill();
        refillPicked();
        refreshDriverLabel();
        refreshSourceField();
        logPanel.log("+ [OK] " + (dmToMysql ? "DM" : "MYSQL") + " | 读回 " + tables.size()
                + " 张表结构（" + kind + " " + loadedFrom
                + (serverVersion.isEmpty() ? "" : " | " + serverVersion)
                + "）：在左边挑表（双击一行即加入），或点「全部 →」整库迁移");
        if (restored > 0) {
            logPanel.log("- [INFO] DM | 重读后按表名接回之前挑好的 " + restored + " 张"
                    + (restored < keepNames.size()
                            ? "（" + (keepNames.size() - restored) + " 张这次没读到，已移出待迁移）" : ""));
        }
        if (dmToMysql && targetDb().isEmpty()) {
            Config mysql = host.currentConfig();
            if (mysql != null && mysql.database != null) {
                setTargetDb(mysql.database.trim());
            }
        }
        lastOut = null;
        lastData = null;
        lastExportFile = null;
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

    /** 按当前方向与开关把建表语句算出来；纯函数，不碰库、不改界面。 */
    private DmMysql.Output buildDdl(List<DmMysql.Table> picked) {
        // 反向：勾的是"转大写"（达梦默认折大写，建出来不带引号也查得到），
        // 不勾则原样带引号——MySQL 那边本来就是小写，再转小写等于什么都没做。
        return dmToMysql
                ? DmMysql.script(picked, targetDb(), dropExisting.isSelected(), true)
                : MysqlDm.script(picked, dropExisting.isSelected(), false, true);
    }

    /**
     * 只出建表语句那一步（自检与"看结构对不对"用）：不连源库、不读数据。
     * 界面上的「导出 .sql」走 {@link #exportScript(File)}，那一份才含 INSERT。
     */
    public DmMysql.Output generate() {
        if (tables.isEmpty()) {
            host.warn("还没读回表结构：先点「" + connectBtn.getText() + "」。");
            return null;
        }
        if (stale) {
            logPanel.error("x [ERROR] DM-DDL | 已拦下生成：" + staleReason
                    + "。先点「" + connectBtn.getText() + "」重连，"
                    + "不然生成的是上一次那批表");
            host.warn(staleReason + "：先点「" + connectBtn.getText() + "」重连再生成。");
            return null;
        }
        List<DmMysql.Table> pickedTables = selectedTables();
        if (pickedTables.isEmpty()) {
            warnNoPicked("没有生成任何语句");
            return null;
        }
        DmMysql.Output out = buildDdl(pickedTables);
        lastOut = out;
        logPanel.log("+ [OK] DM-DDL | 生成 " + out.tables + " 张表的建表语句 | 译名跳过 "
                + out.skipped.size() + " 张 | 提醒 " + out.warnings.size() + " 条");
        for (String w : out.warnings) {
            logPanel.log("! [WARN] DM-DDL | " + w);
        }
        for (String s : out.skipped) {
            logPanel.log("! [WARN] DM-DDL | 整张表没进产物：" + s);
        }
        // 生成这一步不碰达梦驱动，但写入要用：趁人还在这一页把话说了，别等点了写入才弹
        if (!dmToMysql && !driverOk) {
            logPanel.log("! [WARN] DM-DDL | 脚本已生成，但本机没有达梦 JDBC 驱动，"
                    + "「" + writeBtn.getText() + "」会失败：" + DmReader.missingDriverHint());
        }
        refreshButtonStates();
        return out;
    }

    /**
     * 「导出 .sql 脚本」：选好路径，建表语句与数据一次性写进去。
     *
     * <p>这一份脚本的用途就是存档/发人审，所以不再先"生成"到预览里、再点一次保存——
     * 用户 2026-11-15 要求两颗键合成一颗，那一整块预览也去掉了。数据量可以很大：
     * 边读源表边写文件，不攒内存，进度按表报、可取消。
     */
    /**
     * 点「导出 … .sql」：不弹框，直接落到程序目录下的 export 文件夹。
     *
     * <p>用户 2026-11-15 的口径是"默认保存在 jar 包同路径的一个目录，日志里说写到哪儿了"——
     * 弹框选路径对"每次导一份存档"这种用法是纯负担。真要放别处，右键这颗键有「另存为…」。
     */
    private void exportToDefaultDir() {
        if (!canExport()) {
            return;
        }
        File dir = exportDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // 装在只读目录（Program Files）时建不出来：退回让人自己挑，并说清为什么
            logPanel.log("! [WARN] DM-SAVE | 建不出导出目录 " + dir.getAbsolutePath()
                    + "（可能没有写权限），改成手动选路径");
            chooseExportPath();
            return;
        }
        exportScript(uniqueIn(dir, suggestedSaveName()));
    }

    /** 导出键上右键：默认落点就写在菜单里，要放别处再走「另存为…」。 */
    private void maybeExportMenu(java.awt.event.MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem where = new javax.swing.JMenuItem(
                "导出目录：" + exportDir().getAbsolutePath());
        where.setEnabled(false);
        m.add(where);
        m.addSeparator();
        javax.swing.JMenuItem as = new javax.swing.JMenuItem("另存为…");
        as.addActionListener(a -> chooseExportPath());
        m.add(as);
        m.show(genBtn, e.getX(), e.getY());
    }

    /** 右键「另存为…」走这条：只在用户明确要挑别的位置时才弹框。 */
    private void chooseExportPath() {
        if (!canExport()) {
            return;
        }
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(exportDir().isDirectory()
                ? exportDir() : null);
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), suggestedSaveName()));
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            logPanel.log("- [INFO] DM-SAVE | 取消导出，没动任何文件");
            return;
        }
        exportScript(fc.getSelectedFile());
    }

    /** 导出的两个前置：读回了结构、挑好了表。缺哪个说哪个。 */
    private boolean canExport() {
        if (tables.isEmpty()) {
            host.warn("还没读回表结构：先点「" + connectBtn.getText() + "」。");
            return false;
        }
        if (picked.isEmpty()) {
            warnNoPicked("没有可导出的表");
            return false;
        }
        return true;
    }

    /**
     * 导出落点：jar 同级下的 <code>export</code> 目录（与 drivers 同一层）。
     *
     * <p>从 class 目录直接跑（开发期、自检）时 {@code Config.jarDirectory()} 给的是
     * <code>target/classes</code>，那就落在 <code>target/export</code>——不会因为拿不到 jar
     * 目录就写到当前工作目录去，免得"文件去哪儿了"这个问题再出现一次。
     */
    public File exportDir() {
        String jarDir = Config.jarDirectory();
        File base = jarDir == null ? new File(".") : new File(jarDir);
        return new File(base, "export");
    }

    /** 同名文件不覆盖：已经有一份就加 -2、-3，绝不静默盖掉上一次的导出。 */
    private static File uniqueIn(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File next = new File(dir, base + "-" + i + ext);
            if (!next.exists()) {
                return next;
            }
        }
        return f;
    }

    private void exportScript(final File dst) {
        if (tables.isEmpty()) {
            host.warn("还没读回表结构：先点「" + connectBtn.getText() + "」。");
            return;
        }
        if (stale) {
            logPanel.error("x [ERROR] DM-DDL | 已拦下导出：" + staleReason
                    + "。先点「" + connectBtn.getText() + "」重连");
            host.warn(staleReason + "：先点「" + connectBtn.getText() + "」重连再导出。");
            return;
        }
        final List<DmMysql.Table> pickedTables = selectedTables();
        if (pickedTables.isEmpty()) {
            warnNoPicked("没有可导出的表");
            return;
        }
        final boolean withDdl = withSchema();
        final boolean clear = !withDdl && clearTarget.isSelected();
        final DmMysql.Output ddl = withDdl ? buildDdl(pickedTables) : null;
        final java.util.Set<String> skippedSources = new java.util.HashSet<String>();
        if (ddl != null) {
            skippedSources.addAll(ddl.skipped);
        }
        final List<String> warns = new ArrayList<String>();
        final List<DataMover.Job> jobs = buildJobs(pickedTables, warns, skippedSources);
        for (String w : warns) {
            logPanel.log("! [WARN] DATA | " + w);
        }
        if (jobs.isEmpty()) {
            host.warn("这批表在目标端译不出可用的表名/字段名，没有可导出的数据。");
            return;
        }
        if (withDdl && (ddl == null || ddl.sql.trim().isEmpty())) {
            host.warn("建表语句没生成出来，原因见本页日志。");
            return;
        }
        if (ddl != null) {
            logPanel.log("+ [OK] DM-DDL | 建表部分 " + ddl.tables + " 张 | 译名跳过 "
                    + ddl.skipped.size() + " 张 | 提醒 " + ddl.warnings.size() + " 条");
            for (String w : ddl.warnings) {
                logPanel.log("! [WARN] DM-DDL | " + w);
            }
        }
        final Config mysqlCfg = host.currentConfig();
        final String mysqlDb = dmToMysql
                ? (targetDb().isEmpty() ? (mysqlCfg == null || mysqlCfg.database == null
                        ? "" : mysqlCfg.database.trim()) : targetDb())
                : (mysqlCfg == null || mysqlCfg.database == null ? "" : mysqlCfg.database.trim());
        final DmReader.Conf dmConf = readConf();
        if (!DmReader.driverPresent()) {
            host.warn(DmReader.missingDriverHint());
            return;
        }
        if (!dmToMysql && mysqlDb.isEmpty()) {
            host.warn("还没选 MySQL 源库：先在顶部「数据源连接信息」里选好要读的库。");
            return;
        }

        lastData = null;
        lastExportFile = null;
        lastWriteNote = "";
        // 抬头要在界面线程拼好：里面读的是上面几格输入，后台线程碰 Swing 控件是隐患
        final String header = scriptHeader(withDdl, clear, jobs.size());
        host.runDmTask("正在读源表数据并写脚本…", new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                try {
                    return writeScript(dst, header, ddl, jobs, true, mysqlCfg, mysqlDb, dmConf, sink);
                } catch (Exception e) {
                    sink.logError("x [ERROR] DM-SAVE | 写脚本失败：" + LogText.describe(e));
                    return null;
                }
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                refreshButtonStates();
                if (!(res instanceof DataMover.Report)) {
                    return;
                }
                lastData = (DataMover.Report) res;
                lastExportFile = dst;
                logPanel.log("+ [OK] DM-SAVE | 已写出 " + dst.getAbsolutePath() + "（UTF-8，"
                        + dst.length() + " 字节，" + lastData.tables.size() + " 张表 "
                        + lastData.rows + " 行"
                        + (lastData.failed() > 0 ? "，失败 " + lastData.failed() + " 张" : "")
                        + "）");
                host.inform("脚本已导出：" + dst.getName() + "\n" + dst.getAbsolutePath() + "\n"
                        + lastData.tables.size() + " 张表、" + lastData.rows + " 行"
                        + (lastData.failed() > 0
                                ? "（" + lastData.failed() + " 张失败，见本页日志）" : "")
                        + (lastData.cancelled ? "，中途取消（文件不完整）" : ""));
            }
        });
    }

    /**
     * 把脚本写进 dst：抬头 + （可选）建表语句 + 逐表 INSERT，边读边写。
     *
     * <p>同步执行——界面上套一层任务给进度与取消，自检直接调它并把 withData 关掉：
     * 数据那一段要真连源库，自检机器上没有。关掉时文件里留一行说明，不假装"导完了"。
     */
    private DataMover.Report writeScript(File dst, String header, DmMysql.Output ddl,
                                         List<DataMover.Job> jobs, boolean withData,
                                         Config mysqlCfg, String mysqlDb, DmReader.Conf dmConf,
                                         ImportParse.Sink sink) throws Exception {
        Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(dst.toPath()), StandardCharsets.UTF_8), 1 << 16);
        DmReader dm = null;
        try {
            w.write(header);
            if (ddl != null && !ddl.sql.trim().isEmpty()) {
                w.write(ddl.sql);
                w.write("\n");
            }
            w.write("\n-- ===== 数据部分（逐表 INSERT）=====\n");
            if (!withData) {
                w.write("-- 自检模式：没有连源库，数据段未生成\n");
            }
            w.flush();
            DataMover.Report rep;
            if (!withData) {
                rep = new DataMover.Report();
            } else if (dmToMysql) {
                dm = DmReader.open(dmConf);
                rep = DataMover.scriptFromDm(dm, dmConf.schema.toUpperCase(
                        java.util.Locale.ROOT), jobs, w, sink);
            } else {
                rep = DataMover.scriptFromMysql(mysqlCfg, mysqlDb, jobs, w, sink);
            }
            rep.file = dst;
            return rep;
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {
                // 关文件失败不抢业务的错
            }
            if (dm != null) {
                dm.close();
            }
        }
    }

    /** 脚本抬头：这一份是什么、从哪儿到哪儿、带不带建表、有没有 DELETE。 */
    private String scriptHeader(boolean withDdl, boolean clear, int tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- 达梦 ⇄ MySQL ").append(withDdl ? "建表 + 数据脚本" : "数据脚本（只 INSERT）")
          .append("\n");
        sb.append("-- 源：").append(dmToMysql
                ? "达梦 " + readConf().host + ":" + readConf().port + " 模式 "
                        + readConf().schema.toUpperCase(java.util.Locale.ROOT)
                : "MySQL 库 " + sourceKey()).append("\n");
        sb.append("-- 目标：").append(dmToMysql
                ? "MySQL 库 " + (targetDb().isEmpty() ? "（顶部所选库）" : targetDb())
                : "达梦模式 " + readConf().schema.toUpperCase(java.util.Locale.ROOT)).append("\n");
        sb.append("-- 生成于 ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date())).append("，共 ").append(tables).append(" 张表\n");
        sb.append(withDdl
                ? "-- 先 CREATE TABLE（可选 DROP IF EXISTS），随后逐表 INSERT\n"
                : "-- 不含 CREATE TABLE：目标表必须已经存在\n");
        if (clear) {
            sb.append("-- 注意：勾了「先清空」，直接写入时会先 DELETE 再灌；"
                    + "本脚本只含 INSERT，不含 DELETE\n");
        }
        sb.append("-- 字符串按目标端默认转义规则写；二进制列走十六进制字面量\n");
        return sb.toString();
    }

    /**
     * 清单里的表 → 搬运计划：目标表名与字段名按各自端规则折大小写，译不出的表整张跳过并记账。
     *
     * <p>列顺序照源表结构，源列名用原名、目标列名用折好的名字——两边逐列对齐，
     * 不靠 `SELECT *` 的顺序碰运气。
     */
    private List<DataMover.Job> buildJobs(List<DmMysql.Table> picked, List<String> warns) {
        return buildJobs(picked, warns, java.util.Collections.<String>emptySet());
    }

    /**
     * 同上，但排掉建表那一档明确跳过的表（名字译不进、行上限怎么降都过不了线）。
     *
     * <p>不排的话会出现最难查的一种失败：建表日志写着"整张没生成"，
     * 数据却照样往一张不存在的表里灌，报到执行期才炸出一堆"表不存在"。
     */
    private List<DataMover.Job> buildJobs(List<DmMysql.Table> picked, List<String> warns,
                                          java.util.Set<String> skippedSources) {
        List<DataMover.Job> jobs = new ArrayList<DataMover.Job>();
        // 与建表同一口径：写 MySQL 折小写、写达梦折大写
        boolean foldLower = dmToMysql;
        boolean foldUpper = !dmToMysql;
        for (DmMysql.Table t : picked) {
            String key = t.schema == null || t.schema.isEmpty() ? t.name : t.schema + "." + t.name;
            if (skippedSources.contains(key)) {
                continue;
            }
            String targetTable = dmToMysql
                    ? DmMysql.name(t.name, foldLower)
                    : MysqlDm.name(t.name, false, foldUpper);
            if (targetTable == null) {
                warns.add("表「" + t.name + "」的名字译不进目标端（"
                        + (dmToMysql ? "MySQL 只放字母数字下划线" : "表名为空或含换行/引号")
                        + "），这张不搬");
                continue;
            }
            List<String> srcCols = new ArrayList<String>();
            List<String> dstCols = new ArrayList<String>();
            List<String> identity = new ArrayList<String>();
            for (DmMysql.Column c : t.columns) {
                String col = dmToMysql
                        ? DmMysql.name(c.name, foldLower)
                        : MysqlDm.name(c.name, false, foldUpper);
                if (col == null) {
                    // 与建表那一档同口径：这一列本来就没进目标表（DmMysql 会跳过它），
                    // 数据也不搬这一列，但要在日志里点名——静默少一列比报错更难查
                    warns.add("表「" + t.name + "」的字段「" + c.name
                            + "」译不进目标端，目标表里没有这一列，数据也不搬它");
                    continue;
                }
                srcCols.add(c.name);
                dstCols.add(col);
                if (!dmToMysql && c.autoIncrement) {
                    identity.add(col);
                }
            }
            if (srcCols.isEmpty()) {
                warns.add("表「" + t.name + "」的字段一个都译不进目标端，这张不搬");
                continue;
            }
            jobs.add(new DataMover.Job(t.name, targetTable, srcCols, dstCols, identity));
        }
        return jobs;
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

    /**
     * 自检用：走"默认目录"那条路（同样的 exportDir / mkdirs / uniqueIn），返回落盘的文件。
     *
     * <p>数据段照旧关掉（自检没有源库），但建目录、起名、不覆盖这三步都是真的。
     */
    public File exportDefaultForSelftest() {
        File dir = exportDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        File dst = uniqueIn(dir, suggestedSaveName());
        return exportToForSelftest(dst) ? dst : null;
    }

    /**
     * 自检用：把脚本导出到指定文件，走的是界面上同一条写文件路径。
     *
     * <p>数据段关掉（{@code withData=false}）：那一段要真连源库，自检机器上没有；
     * 抬头、建表语句、落盘的编码与换行都照真路径走，文件里会留一行"数据段未生成"。
     */
    public boolean exportToForSelftest(File dst) {
        if (tables.isEmpty()) {
            logPanel.error("x [ERROR] DM-SAVE | 还没读回结构，没有可导出的内容");
            return false;
        }
        List<DmMysql.Table> pickedTables = selectedTables();
        if (pickedTables.isEmpty()) {
            logPanel.error("x [ERROR] DM-SAVE | 待迁移清单空着，没有可导出的内容");
            return false;
        }
        boolean withDdl = withSchema();
        DmMysql.Output ddl = withDdl ? buildDdl(pickedTables) : null;
        List<String> warns = new ArrayList<String>();
        List<DataMover.Job> jobs = buildJobs(pickedTables, warns);
        String header = scriptHeader(withDdl, !withDdl && clearTarget.isSelected(), jobs.size());
        try {
            DataMover.Report rep = writeScript(dst, header, ddl, jobs, false,
                    host.currentConfig(), "", readConf(), ImportParse.LOG_ONLY);
            lastData = rep;
            lastExportFile = dst;
            logPanel.log("+ [OK] DM-SAVE | 已写出 " + dst.getAbsolutePath()
                    + "（自检：数据段未生成）");
            return dst.isFile() && dst.length() > 0L;
        } catch (Exception e) {
            logPanel.error("x [ERROR] DM-SAVE | 写文件失败：" + dst.getPath() + " — "
                    + LogText.describe(e));
            return false;
        }
    }

    /**
     * 写入前那一句确认：这一下会建几张表、搬几张表的数据、有没有不可恢复的 DELETE/DROP。
     *
     * <p>清单列的是目标表名——写坏的是那边，用源名让人对着确认容易看错。
     * 自检跑的是同一个入口，靠 {@link #confirmWrite} 关掉弹框，只判文案与条件。
     */
    private boolean askWriteConfirm(String targetDesc, String title,
                                    List<DataMover.Job> jobs, boolean withDdl, boolean clear) {
        List<String> names = new ArrayList<String>();
        for (DataMover.Job j : jobs) {
            names.add(j.targetTable);
        }
        StringBuilder msg = new StringBuilder("<html>目标：").append(escHtml(targetDesc))
                .append("<br>");
        msg.append(withDdl
                ? "先建 " + jobs.size() + " 张表，再逐表写入数据"
                : "只往 " + jobs.size() + " 张已有表里写数据（不建表）");
        if (withDdl && dropExisting.isSelected()) {
            msg.append("；建表前先 DROP 同名表（<b>不可恢复</b>）");
        }
        if (clear) {
            msg.append("；每张表先 DELETE 清空（<b>不可恢复</b>）");
        }
        msg.append("。<br>涉及表：").append(escHtml(previewNames(names)))
                .append("<br><br>确认执行？</html>");
        int pick = javax.swing.JOptionPane.showConfirmDialog(this, msg.toString(),
                title, javax.swing.JOptionPane.OK_CANCEL_OPTION,
                (clear || (withDdl && dropExisting.isSelected()))
                        ? javax.swing.JOptionPane.WARNING_MESSAGE
                        : javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (pick != javax.swing.JOptionPane.OK_OPTION) {
            logPanel.log("- [INFO] DM-EXEC | 已取消写入，" + targetDesc + " 一条语句都没执行");
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

    /** 默认文件名带来源与时间：同一模式跑两次不该把上一份盖掉。 */
    public String suggestedSaveName() {
        String sch = loadedFrom.isEmpty() ? schemaField.getText().trim() : loadedFrom;
        sch = sch.replaceAll("[^A-Za-z0-9_]", "");
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                .format(new java.util.Date());
        return (dmToMysql ? "dm_to_mysql" : "mysql_to_dm")
                + (sch.isEmpty() ? "" : "_" + sch) + "_" + stamp + ".sql";
    }

    /** 自检用：直接灌一份结构进来走界面那一条路，不经过达梦/MySQL 连接。 */
    /**
     * 自检用：模拟"改完参数重连一次"——同一次会话里的重读，待迁移按表名接回来。
     *
     * <p>和 {@link #setTablesForSelftest} 的区别就在这儿：那个是"换一份结构"（用例之间复位），
     * 这个是"重读同一份结构"（用户改了口令又连了一次），不能让挑好的表白丢。
     */
    public void rereadForSelftest(List<DmMysql.Table> read) {
        applyTables(read, "", dmToMysql ? "模式" : "库", dmToMysql ? "" : shortSourceName());
    }

    public void setTablesForSelftest(List<DmMysql.Table> read) {
        picked.clear();     // 用例之间互不影响：灌一份新结构就是一次全新的挑表
        applyTables(read, "", dmToMysql ? "模式" : "库", dmToMysql ? "" : shortSourceName());
    }

    /** 自检灌结构时那句来源：反向就是顶层连接栏那本库的名字（没选库时留空）。 */
    private String shortSourceName() {
        Config cfg = host.currentConfig();
        return cfg == null || cfg.database == null ? "" : cfg.database.trim();
    }

    /** 自检用：翻到指定方向（true = DM8 → MySQL），清掉清单与正文，之后走生成那条路。 */
    public void setDirectionForSelftest(boolean dmToMysqlDirection) {
        applyDirection(dmToMysqlDirection);
    }

    /** 自检用：左卡 / 右卡标题上的角色（"① 读自 · 达梦 DM8" 这一类）。 */
    public String leftRoleText() {
        return dmToMysql ? dmCardRole.getText() : mysqlCardRole.getText();
    }

    public String rightRoleText() {
        return dmToMysql ? mysqlCardRole.getText() : dmCardRole.getText();
    }

    /** 自检用：换向键（用户点它翻方向）。 */
    public JButton swapButton() {
        return swapBtn;
    }

    /** 自检用：当前方向是不是 DM8 → MySQL。 */
    public boolean isDmToMysql() {
        return dmToMysql;
    }

    /** 自检用：反向时那句「MySQL 源」写的是哪一本。 */
    public String mysqlSourceText() {
        return mysqlSourceLabel.getText();
    }

    /** 自检用：写入按钮的提示，缺驱动/缺模式时该怎么拦。 */
    public String writeTipText() {
        return writeBtn.getToolTipText();
    }

    /** 自检用：切导出范围（0＝表结构和数据，1＝只导数据），走的是用户点下拉那条路。 */
    public void setExportModeForSelftest(int index) {
        if (exportMode.getSelectedIndex() == index) {
            onSchemaModeChanged();     // 选同一项不会触发监听，手工补一次，语义与切换一致
            return;
        }
        exportMode.setSelectedIndex(index);
    }

    /** 自检用：当前导出范围的文案。 */
    public String exportModeText() {
        Object v = exportMode.getSelectedItem();
        return v == null ? "" : String.valueOf(v);
    }

    /** 自检用：「生成」那颗键此刻叫什么。 */
    public String genText() {
        return genBtn.getText();
    }

    /** 自检用：穿梭框那四颗键、右栏「下一步」与待迁移清单。 */
    public JButton addButton() {
        return addBtn;
    }

    public JButton addAllButton() {
        return addAllBtn;
    }

    public JButton removeButton() {
        return removeBtn;
    }

    public JButton removeAllButton() {
        return removeAllBtn;
    }

    public String nextHintText() {
        return nextHint.getText();
    }

    /** 左边视图第 view 行选中（不搬，只选）。 */
    public void selectRowForSelftest(int view) {
        table.setRowSelectionInterval(view, view);
    }

    public int selectedRowCountForSelftest() {
        return table.getSelectedRowCount();
    }

    /** 待迁移清单里有几张。 */
    public int pickedCountForSelftest() {
        return picked.size();
    }

    /** 待迁移清单第 view 行对应的 tables 下标（判顺序与映射用）。 */
    public int pickedIndexAtForSelftest(int view) {
        return tableIndexOf(pickedTable, false, view);
    }

    public int pickedRowCountForSelftest() {
        return pickedModel.getRowCount();
    }

    /** 走用户双击那一条路：把左边视图第 view 行搬过去。 */
    public void doubleClickLeftForSelftest(int view) {
        int index = tableIndexOf(table, true, view);
        if (index >= 0) {
            picked.add(Integer.valueOf(index));
            afterTransfer("「" + tables.get(index).name + "」加入待迁移");
        }
    }

    /** 自检用：连接参数折叠区与两颗按钮的视觉层级。 */
    public boolean dmParamsVisibleForSelftest() {
        return dmParams.isVisible();
    }

    public String dmSummaryText() {
        return dmSummary.getText();
    }

    public JButton dmToggleButton() {
        return dmToggle;
    }

    public String writeButtonRole() {
        return Buttons.role(writeBtn);
    }

    public String genButtonRole() {
        return Buttons.role(genBtn);
    }

    /** 自检用：大小写那一句（写 MySQL 小写 / 写达梦大写）。 */
    public String nameCaseText() {
        return nameCaseHint.getText();
    }

    /** 自检用：日志段现在多高、最矮能到多少（都是物理像素）。 */
    public int logHeightForSelftest() {
        return logSplit.getBottomComponent().getHeight();
    }

    public int logMinForSelftest() {
        return logSplit.getBottomComponent().getMinimumSize().height;
    }

    /** 自检用：把日志拖成指定高度（走 setDividerLocation，和用户拖到那个位置等价）。 */
    public void setLogHeightForSelftest(int h) {
        int top = logSplit.getHeight() - Math.max(h, 0);
        logSplit.setDividerLocation(top);
    }

    /**
     * 自检用：在把手上走一遍真实的按下 → 拖动 → 松开。
     *
     * <p>只调 {@code setDividerLocation} 是测不出"能不能拖"的——那等于绕过把手直接改布局；
     * 这里把三个鼠标事件打进 {@code BasicSplitPaneDivider}，用户那条路才真被走到。
     *
     * @param dyDesign 向上拖多少（设计像素；负数向下）
     */
    public void dragLogDividerForSelftest(int dyDesign) {
        javax.swing.plaf.SplitPaneUI ui = logSplit.getUI();
        if (!(ui instanceof javax.swing.plaf.basic.BasicSplitPaneUI)) {
            return;
        }
        java.awt.Component d = ((javax.swing.plaf.basic.BasicSplitPaneUI) ui).getDivider();
        if (d == null) {
            return;
        }
        int x = Math.max(1, d.getWidth() / 2);
        int y = Math.max(1, d.getHeight() / 2);
        int dy = UiScale.px(dyDesign);
        long t = System.currentTimeMillis();
        d.dispatchEvent(new java.awt.event.MouseEvent(d,
                java.awt.event.MouseEvent.MOUSE_PRESSED, t,
                java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
        d.dispatchEvent(new java.awt.event.MouseEvent(d,
                java.awt.event.MouseEvent.MOUSE_DRAGGED, t + 10,
                java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y - dy, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
        d.dispatchEvent(new java.awt.event.MouseEvent(d,
                java.awt.event.MouseEvent.MOUSE_RELEASED, t + 20, 0, x, y - dy, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
    }

    /** 自检用：在把手上双击一次（回到默认高度）。 */
    public void doubleClickLogDividerForSelftest() {
        javax.swing.plaf.SplitPaneUI ui = logSplit.getUI();
        if (!(ui instanceof javax.swing.plaf.basic.BasicSplitPaneUI)) {
            return;
        }
        java.awt.Component d = ((javax.swing.plaf.basic.BasicSplitPaneUI) ui).getDivider();
        if (d == null) {
            return;
        }
        int x = Math.max(1, d.getWidth() / 2);
        int y = Math.max(1, d.getHeight() / 2);
        long t = System.currentTimeMillis();
        d.dispatchEvent(new java.awt.event.MouseEvent(d,
                java.awt.event.MouseEvent.MOUSE_PRESSED, t,
                java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
        d.dispatchEvent(new java.awt.event.MouseEvent(d,
                java.awt.event.MouseEvent.MOUSE_RELEASED, t + 10, 0, x, y, 2, false,
                java.awt.event.MouseEvent.BUTTON1));
        d.dispatchEvent(new java.awt.event.MouseEvent(d,
                java.awt.event.MouseEvent.MOUSE_CLICKED, t + 11, 0, x, y, 2, false,
                java.awt.event.MouseEvent.BUTTON1));
    }

    /** 自检用：写入键此刻能不能点（没读回结构时必须是灰的）。 */
    public boolean writeEnabled() {
        return writeBtn.isEnabled();
    }

    public boolean dropVisibleForSelftest() {
        return dropExisting.isVisible();
    }

    public boolean clearVisibleForSelftest() {
        return clearTarget.isVisible();
    }

    /**
     * 自检用：清单里这批表要搬成什么样（源表→目标表，几列）。
     *
     * <p>名字折叠与"译不进就整张跳过"这两条只有连库才看得出来，抽成一行行方便直接断言。
     */
    public List<String> jobDumpForSelftest() {
        List<String> warns = new ArrayList<String>();
        List<String> out = new ArrayList<String>();
        for (DataMover.Job j : buildJobs(selectedTables(), warns)) {
            out.add(j.sourceTable + "→" + j.targetTable + "(" + j.sourceColumns.size() + "列"
                    + (j.identityColumns.isEmpty() ? "" : "，自增" + j.identityColumns) + ")");
        }
        for (String w : warns) {
            out.add("!" + w);
        }
        return out;
    }
}
