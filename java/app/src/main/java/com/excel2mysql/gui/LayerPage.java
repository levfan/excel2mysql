package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.DomainGuess;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.MappingFile;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.TableBuilder;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 第 4 页签：读 ods 建模 obj/dom/ads。映射文件驱动，三按钮按依赖锁，默认只生成脚本。 */
public final class LayerPage extends JPanel {

    public interface Host {
        Config currentConfig();
        boolean connected();
        String exportRoot();
        /** 与「Excel 导入」页同一个会话级解压目录：关窗口一次清掉，不在这页另起一个。 */
        String zipSessionDir();
        void warn(String text);
        void inform(String text);
        void informError(String title, String text);
        void runLayerTask(String title, Function<ImportParse.Sink, Object> work, Consumer<Object> onUi);
    }

    /** 顶部连接栏还没选库时的脚本源库兜底（沿用旧默认，不另设输入口）。 */
    private static final String DEFAULT_SOURCE_DB = "ods";
    /**
     * 库名的安全口径：只允许字母、数字、下划线，且不许空。
     * 库名是被反引号包进 `USE \`x\`;` 与 `FROM \`x\`.\`t\`` 的裸标识符，界面这四个框又可任意编辑，
     * 所以"合规"必须是生成前的硬前置，而不是指望用户不去碰它。
     */
    private static final Pattern DB_IDENT = Pattern.compile("^[A-Za-z0-9_]+$");
    /** jar 内自带的映射模板：第四页一打开就能看到 15 列＋可选 ods物理表名 该填什么。 */
    private static final String TEMPLATE_RES = "/layer_mapping_template.csv";

    private final Host host;
    private final LogPanel logPanel = new LogPanel();
    private final JTextField objDb = new JTextField("obj", 6);
    private final JTextField domDb = new JTextField("dom", 6);
    private final JTextField adsDb = new JTextField("ads", 6);
    private final JLabel mappingLabel = new JLabel("未载入映射");
    private final JCheckBox writeMode = new JCheckBox("同时写入目标库", false);
    private final JButton loadBtn = new JButton("载入映射文件");
    private final JButton exportTplBtn = new JButton("导出映射模板");
    /** 免配模板那条路的入口：拖进来的是标准/简易 Excel 或装它们的 ZIP。 */
    private final DropArea fileDrop = new DropArea(this::guessFromFiles, this::chooseGuessFiles,
            DropArea.LAYER_HINT, null);
    private final JButton readSrcBtn = new JButton("读源库结构");
    /** 读回源结构后按条款 184/183/188 自动铺一份映射；不点它时读结构也会自动跑一遍（内置模板态）。 */
    private final JButton autoMapBtn = new JButton("按源库结构自动映射");
    private final JButton objBtn = new JButton("生成对象层 obj");
    private final JButton domBtn = new JButton("生成领域层 dom");
    private final JButton adsBtn = new JButton("生成主题层 ads");
    private final javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
            new Object[]{"源表名", "表注释", "字段数", "目标领域", "领域子类", "对象维度", "主题", "映射状态", "绑定状态"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);

    private MappingFile mapping;
    private List<LayerGen.SourceTable> sourceTables = new ArrayList<LayerGen.SourceTable>();
    /** 界面上这份映射还是随包内置模板吗？是的话读回源结构后自动铺一遍，不用人再点一颗。 */
    private boolean builtinMapping = true;
    private boolean objGenerated;
    private boolean domGenerated;
    /**
     * RES_LAYER 有任务在跑吗（由 MainFrame.syncRunStates 按 taskBusy 灌进来）。
     * 这一位只管"另一个生成任务"互斥，挡不住「载入映射」「读源库结构」这两条会改
     * mapping／sourceTables／字段例外的入口——生成 worker 正读着同一份，改一半就是竞态，
     * 所以忙时这几颗一起灰掉（与 Excel 页 syncGenButtons 的 taskBusy(RES_EXCEL) 同口径）。
     */
    private boolean layerBusy;
    /** 字段映射例外表：键 dstTable.dstField，值 {转换规则, 映射状态}；生成落盘前覆盖进 out.fieldMapping。 */
    private final Map<String, String[]> fieldExceptions = new ConcurrentHashMap<String, String[]>();

    public LayerPage(Host host) {
        this.host = host;
        Buttons.secondary(objBtn);
        Buttons.secondary(domBtn);
        Buttons.secondary(adsBtn);
        setLayout(new BorderLayout(0, UiScale.px(6)));
        add(buildTop(), BorderLayout.NORTH);
        Tables.style(table);
        // 双击行弹字段映射例外窗（照 SqlPage.java:316 的 MouseAdapter 形状）：
        // 看的是这一张源表被自动草稿成的目标字段，转换规则/映射状态可改，改后存进例外表
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < (mapping == null ? 0 : mapping.tables().size())) {
                        openFieldMappingDialog(row);
                    }
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        objBtn.addActionListener(e -> generate(LayerGen.Layer.OBJ));
        domBtn.addActionListener(e -> generate(LayerGen.Layer.DOM));
        adsBtn.addActionListener(e -> generate(LayerGen.Layer.ADS));
        refreshButtonStates();
        loadBuiltinTemplate();
    }

    private JPanel buildTop() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(PageIntro.of("读 ods 建模 obj/dom/ads"), BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        Buttons.secondary(loadBtn);
        loadBtn.addActionListener(e -> chooseMapping());
        row.add(loadBtn);
        Buttons.secondary(exportTplBtn);
        exportTplBtn.setToolTipText("<html>把内置模板原样导出成 CSV（UTF-8 带 BOM，Excel 直接打开），<br>"
                + "按列填好后再点「载入映射文件」。</html>");
        exportTplBtn.addActionListener(e -> chooseExportTemplate());
        row.add(exportTplBtn);
        Buttons.secondary(readSrcBtn);
        readSrcBtn.setToolTipText("<html>连上当前数据源后，读顶部连接栏所选库的用户表结构，<br>"
                + "填源表清单并绑定映射行的绑定状态；obj/dom 生成依赖这一步。</html>");
        readSrcBtn.addActionListener(e -> readSourceStructure());
        row.add(readSrcBtn);
        Buttons.secondary(autoMapBtn);
        autoMapBtn.setToolTipText("<html>用已读回的源库结构按条款 184（7 领域 33 子领域）、<br>"
                + "条款 183（十类对象维度）、条款 188（三个主题）铺一份映射。<br>"
                + "自动识别一源一表；领域/子领域只做分类，不按它合并。<br>"
                + "会覆盖当前映射；判不准的行标「待确认」。</html>");
        autoMapBtn.addActionListener(e -> autoMapFromSource());
        row.add(autoMapBtn);
        row.add(mappingLabel);
        row.add(new JLabel("目标库 obj/dom/ads"));
        row.add(objDb); row.add(domDb); row.add(adsDb);
        p.add(row, BorderLayout.CENTER);
        // 免配模板那条路的入口摆在最上沿：与「Excel 导入」页同一块虚线区、同一套拖法
        fileDrop.setPreferredSize(new java.awt.Dimension(UiScale.px(1100), UiScale.px(84)));
        p.add(fileDrop, BorderLayout.SOUTH);
        return p;
    }

    private void chooseGuessFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("选择要识别领域的 Excel 或 ZIP");
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Excel 与 ZIP（.xlsx / .xls / .zip）", "xlsx", "xls", "zip"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<String> paths = new ArrayList<String>();
            for (File f : fc.getSelectedFiles()) {
                paths.add(f.getPath());
            }
            guessFromFiles(paths);
        }
    }

    /**
     * 拖进来或选进来的文件：解析 → 按文件名与表内元数据判领域 → 覆盖界面上那份映射。
     *
     * <p>不连库、不写库：这一条只产映射行，obj/dom/ads 三颗仍按原来的依赖锁走。
     */
    private void guessFromFiles(final List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            logPanel.log("! [WARN] LAYER | 没有可识别的文件：拖进来的路径是空的");
            return;
        }
        if (layerBusy) {
            host.warn("这一页正有任务在跑，等它结束再识别领域。");
            return;
        }
        // Excel 打开着的文件会留一个 ~$ 锁文件：解析它只会失败，还会把「解析失败」算进结果
        final List<String> picked = new ArrayList<String>();
        int locked = 0;
        for (String p : paths) {
            if (new File(p).getName().startsWith("~$")) {
                locked++;
            } else {
                picked.add(p);
            }
        }
        if (locked > 0) {
            logPanel.log("- [INFO] LAYER | 跳过 " + locked + " 个 Excel 锁文件（~$ 开头，不是真表格）");
        }
        if (picked.isEmpty()) {
            logPanel.log("! [WARN] LAYER | 拖进来的全是 Excel 锁文件（~$ 开头），没有可识别的表格");
            host.inform("拖进来的都是 Excel 打开时留下的锁文件（~$ 开头），没有可识别的表格。");
            return;
        }
        host.runLayerTask("按文件识别领域", new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                try {
                    Names nm = new Names(PinyinLoader.get());
                    TableBuilder builder = new TableBuilder(new Infer(nm), nm);
                    ImportParse.Outcome oc = ImportParse.parse(picked, new ArrayList<String>(),
                            host.zipSessionDir(), 1, builder, sink);
                    if (sink.cancelled()) {
                        sink.log("! [WARN] LAYER | 已取消：解出的 " + oc.tables.size()
                                + " 张表不覆盖界面上那一份映射");
                        return null;
                    }
                    return new Object[]{DomainGuess.guess(inputsOf(oc), nm), oc, null};
                } catch (Exception ex) {
                    sink.logError("x [ERROR] LAYER | 识别领域失败：" + ex.getMessage());
                    return new Object[]{null, null, String.valueOf(ex.getMessage())};
                }
            }
        }, new Consumer<Object>() {
            @SuppressWarnings("unchecked")
            @Override public void accept(Object res) {
                if (res == null) {
                    return;      // 取消那一支：只留日志，不动界面上现存的映射
                }
                Object[] arr = (Object[]) res;
                if (arr[2] != null) {
                    host.informError("按文件识别领域失败", String.valueOf(arr[2]));
                    return;
                }
                List<DomainGuess.Verdict> vs = (List<DomainGuess.Verdict>) arr[0];
                ImportParse.Outcome oc = (ImportParse.Outcome) arr[1];
                if (vs == null || vs.isEmpty()) {
                    logPanel.log("! [WARN] LAYER | 这些文件里没解出可识别的表（"
                            + picked.size() + " 个入口），映射不改");
                    host.inform("这些文件里没解出可识别的表，映射没有改动。");
                    return;
                }
                int pending = applyVerdicts(vs, "按文件识别");
                host.inform("已按 " + picked.size() + " 个入口识别出 " + vs.size()
                        + " 张表的领域（一源一表），"
                        + (pending == 0 ? "全部判出。" : "其中 " + pending + " 张待确认，日志已逐表点名。")
                        + (oc.failed == 0 ? "" : "另有 " + oc.failed + " 个文件解析失败。"));
            }
        });
    }

    /** 识别结果铺到界面：映射、标签、表格、按钮锁与日志一次做完，两条入口共用。 */
    private int applyVerdicts(List<DomainGuess.Verdict> vs, String how) {
        List<MappingFile.TableMap> rows = new ArrayList<MappingFile.TableMap>();
        java.util.Set<String> targets = new java.util.LinkedHashSet<String>();
        int pending = 0;
        for (DomainGuess.Verdict v : vs) {
            rows.add(v.map);
            if (v.map.domainTarget != null && !v.map.domainTarget.isEmpty()) {
                targets.add(v.map.domainTarget);
            }
            logPanel.log((v.guessed ? "+ [OK] LAYER | " : "! [WARN] LAYER | ")
                    + DomainGuess.describe(v));
            if (v.warning != null && !v.warning.isEmpty()) {
                logPanel.log("  · " + v.warning);
            }
            if (!v.guessed) {
                pending++;
            }
        }
        mapping = MappingFile.ofTables(rows);
        builtinMapping = false;
        // 与「载入映射文件」同一口径：换一份映射就清掉上一场的字段例外
        fieldExceptions.clear();
        mappingLabel.setText(how + "（" + rows.size() + " 表，" + targets.size() + " 张领域表"
                + (pending == 0 ? "" : "，" + pending + " 张待确认") + "）");
        fillTable();
        refreshButtonStates();
        logPanel.log("- [INFO] LAYER | " + how + " | " + DomainGuess.summary(vs));
        return pending;
    }

    /** 「按源库结构自动映射」那颗：读回的结构已在手，直接判，不连库。 */
    private void autoMapFromSource() {
        if (sourceTables.isEmpty()) {
            host.warn("还没有源库结构：先点「读源库结构」把源表取回来。");
            return;
        }
        if (layerBusy) {
            host.warn("这一页正有任务在跑，等它结束再自动映射。");
            return;
        }
        autoMapFromTables(new ArrayList<LayerGen.SourceTable>(sourceTables), "按源库结构识别");
    }

    /** 在 worker 里判这批判源表，回 EDT 铺界面（与拖文件那条同一套落点）。 */
    private void autoMapFromTables(final List<LayerGen.SourceTable> st, final String how) {
        host.runLayerTask(how, new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                try {
                    Names nm = new Names(PinyinLoader.get());
                    List<DomainGuess.Verdict> vs = DomainGuess.guess(inputsOf(st), nm);
                    if (sink.cancelled()) {
                        sink.log("! [WARN] LAYER | 已取消：" + how + " 的结果不覆盖界面上那份映射");
                        return null;
                    }
                    return vs;
                } catch (Exception ex) {
                    sink.logError("x [ERROR] LAYER | " + how + " 失败：" + ex.getMessage());
                    return String.valueOf(ex.getMessage());
                }
            }
        }, new Consumer<Object>() {
            @SuppressWarnings("unchecked")
            @Override public void accept(Object res) {
                if (res == null) {
                    return;
                }
                if (res instanceof String) {
                    host.informError(how + "失败", String.valueOf(res));
                    return;
                }
                List<DomainGuess.Verdict> vs = (List<DomainGuess.Verdict>) res;
                if (vs.isEmpty()) {
                    host.inform("源库里没有可映射的表。");
                    return;
                }
                int pending = applyVerdicts(vs, how);
                host.inform(how + "：" + DomainGuess.summary(vs)
                        + (pending == 0 ? "。" : "。待确认的行已标出，可在导出的映射文件里改。"));
            }
        });
    }

    /** 解出来的表 → 判定输入：文件名、系统目录、表注释、字段注释/名与源列，全在解析结果里。 */
    private static List<DomainGuess.Input> inputsOf(ImportParse.Outcome oc) {
        List<DomainGuess.Input> out = new ArrayList<DomainGuess.Input>();
        for (TableBuilder.Result r : oc.tables) {
            String path = r.table.filepath == null ? "" : r.table.filepath;
            if (new File(path).getName().startsWith("~$")) {
                continue;
            }
            DomainGuess.Input in = new DomainGuess.Input();
            in.fileName = new File(path).getName();
            in.system = systemOf(path, oc.zipOrigin.get(path));
            fillInput(in, r.table.tableComment, r.table.tableName, r.table.columns);
            out.add(in);
        }
        return out;
    }

    /** 读源库结构那条路：源表清单就是判定输入，没有目录/包名，靠 ods 物理表名前缀找先验。 */
    private static List<DomainGuess.Input> inputsOf(List<LayerGen.SourceTable> st) {
        List<DomainGuess.Input> out = new ArrayList<DomainGuess.Input>();
        for (LayerGen.SourceTable t : st) {
            DomainGuess.Input in = new DomainGuess.Input();
            in.fileName = t.name == null ? "" : t.name;
            fillInput(in, t.comment, t.name, t.columns);
            out.add(in);
        }
        return out;
    }

    private static void fillInput(DomainGuess.Input in, String tableCn, String tableEn,
                                  List<com.excel2mysql.core.Infer.Column> cols) {
        in.tableCn = tableCn == null ? "" : tableCn;
        in.tableEn = tableEn == null ? "" : tableEn;
        in.fieldCount = cols == null ? 0 : cols.size();
        if (cols == null) {
            return;
        }
        for (com.excel2mysql.core.Infer.Column c : cols) {
            in.fieldComments.add(c.comment == null ? "" : c.comment);
            in.fieldNames.add(c.field == null ? "" : c.field);
            if (c.primaryKey && in.pkField.isEmpty()) {
                in.pkField = c.field == null ? "" : c.field;
            }
        }
    }

    /**
     * 系统列取「这张表属于哪个业务系统」：ZIP 里取包内的系统目录，散装文件取往上最近的
     * 已登记系统目录。数据集常有「系统/分类/表」两层，直接的父目录是分类而不是系统，
     * 所以从深往浅找第一个命中条款先验的目录名；都认不出才退回归属父目录。
     */
    private static String systemOf(String path, String origin) {
        if (origin != null) {
            int bang = origin.indexOf('!');
            String inner = bang >= 0 ? origin.substring(bang + 1) : "";
            String norm = inner.replace('\\', '/');
            String[] seg = norm.split("/");
            for (int i = seg.length - 1; i >= 0; i--) {
                if (isKnownSystem(seg[i])) {
                    return seg[i];
                }
            }
            if (seg.length > 1 && !seg[0].isEmpty()) {
                return seg[0];
            }
            return bang > 0 ? origin.substring(0, bang) : "";
        }
        File parent = new File(path).getParentFile();
        for (File p = parent; p != null; p = p.getParentFile()) {
            if (isKnownSystem(p.getName())) {
                return p.getName();
            }
        }
        return parent == null ? "" : parent.getName();
    }

    private static boolean isKnownSystem(String name) {
        return name != null && !name.isEmpty()
                && com.excel2mysql.core.Taxonomy.prior(name, "") != null;
    }

    private JPanel buildBottom() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        bar.add(writeMode);
        bar.add(objBtn); bar.add(domBtn); bar.add(adsBtn);
        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(logPanel), BorderLayout.CENTER);
        return p;
    }

    private void chooseMapping() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("载入层间映射 CSV");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadMapping(fc.getSelectedFile().getPath());
        }
    }

    private void chooseExportTemplate() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("导出层间映射模板");
        fc.setSelectedFile(new File("层间映射模板.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            exportTemplateTo(fc.getSelectedFile());
        }
    }

    /** 打开页面就载入 jar 内模板，让「15 列各填什么」一眼可见；载入真实映射即整体替换。 */
    public boolean loadBuiltinTemplate() {
        try {
            mapping = MappingFile.parseTableCsv(readTemplateText(), "内置映射模板");
            builtinMapping = true;
            fieldExceptions.clear();
            mappingLabel.setText("内置映射模板（" + mapping.tables().size() + " 表）");
            fillTable();
            logPanel.log("- [INFO] LAYER | 载入内置映射模板 | " + mapping.tables().size()
                    + " 表（示例，可点「导出映射模板」编辑后载入）");
            refreshButtonStates();
            return true;
        } catch (Exception ex) {
            logPanel.log("! [WARN] LAYER | 内置映射模板读不了：" + ex.getMessage());
            return false;
        }
    }

    /** 把 jar 内模板原样写到 dest：用户在 Excel 里改完再走「载入映射文件」。 */
    public boolean exportTemplateTo(File dest) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
            try { out.write(readTemplateBytes()); } finally { out.close(); }
            logPanel.log("- [INFO] LAYER | 已导出映射模板 " + dest.getPath());
            host.inform("已导出映射模板：" + dest.getPath() + "，按列填好后点「载入映射文件」");
            return true;
        } catch (Exception ex) {
            host.informError("导出映射模板失败", ex.getMessage());
            logPanel.log("x [ERROR] LAYER | 导出映射模板失败：" + ex.getMessage());
            return false;
        }
    }

    private byte[] readTemplateBytes() throws java.io.IOException {
        java.io.InputStream in = LayerPage.class.getResourceAsStream(TEMPLATE_RES);
        if (in == null) {
            throw new java.io.IOException("jar 里没找到 " + TEMPLATE_RES);
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private String readTemplateText() throws java.io.IOException {
        return new String(readTemplateBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean loadMapping(String path) {
        try {
            mapping = MappingFile.loadTableCsv(path);
            builtinMapping = false;
            // 换一份映射就把上一份的例外清掉：例外只按 dstTable.dstField 归档，
            // 不清的话新映射里同名的目标字段会白接手上一场会话改过的规则
            fieldExceptions.clear();
            mappingLabel.setText(new File(path).getName() + "（" + mapping.tables().size() + " 表）");
            fillTable();
            logPanel.log("- [INFO] LAYER | 载入映射 " + path + " | " + mapping.tables().size() + " 表");
            refreshButtonStates();
            return true;
        } catch (Exception ex) {
            host.informError("映射载入失败", ex.getMessage());
            logPanel.log("x [ERROR] LAYER | 映射载入失败：" + ex.getMessage());
            return false;
        }
    }

    public void setSourceTables(List<LayerGen.SourceTable> src) {
        this.sourceTables = src == null ? new ArrayList<LayerGen.SourceTable>() : src;
        fillTable();
        refreshButtonStates();
    }

    private void fillTable() {
        model.setRowCount(0);
        if (mapping == null) { return; }
        java.util.Set<String> srcNames = new java.util.HashSet<String>();
        for (LayerGen.SourceTable t : sourceTables) { srcNames.add(t.name); }
        for (MappingFile.TableMap t : mapping.tables()) {
            String physical = t.odsPhysical == null || t.odsPhysical.isEmpty() ? t.tableEn : t.odsPhysical;
            boolean bound = srcNames.contains(physical);
            model.addRow(new Object[]{t.tableEn, t.tableCn, t.fieldCount, t.domain, t.domainSub,
                    t.objDims, t.theme, t.status, bound ? "已绑定" : "未绑定"});
        }
    }

    public void refreshButtonStates() {
        boolean ready = mapping != null && !mapping.tables().isEmpty() && !sourceTables.isEmpty();
        // 忙时五颗入口一起灰：三颗生成是"同页两个任务踩同一份数据"，载入／读源是
        // "worker 正读的 mapping／sourceTables 被中途换掉"——RES_LAYER 只挡前者，得在这里一并挡
        objBtn.setEnabled(ready && !layerBusy);
        domBtn.setEnabled(ready && !layerBusy);
        adsBtn.setEnabled(ready && objGenerated && domGenerated && !layerBusy);
        loadBtn.setEnabled(!layerBusy);
        readSrcBtn.setEnabled(!layerBusy);
        autoMapBtn.setEnabled(!sourceTables.isEmpty() && !layerBusy);
        if (ready && !objGenerated && !domGenerated) {
            // 文案与引擎实发一致：ads 的 FROM 只有 dom 明细（genAds 逐主题一条 COUNT(*) FROM dom.<目标表>），
            // obj／dim 的 JOIN 口径按条款 188 留给业务方核，没产出来就不能在悬浮里声称汇总了它。
            // 依赖锁本身不变（仍要先出 obj＋dom），这句说的是"生成后会得到什么"。
            adsBtn.setToolTipText("<html>主题层 ads 只在领域层 dom 的明细上产模板聚合，<br>"
                    + "对象层与维表的 JOIN 口径留待业务方核（条款 188）。<br>请先生成对象层与领域层。</html>");
        } else {
            adsBtn.setToolTipText(null);
        }
    }

    /**
     * RES_LAYER 忙闲由 MainFrame.syncRunStates 灌进来（照 SqlPage.setGenBusy 同一形状）。
     * 灰的是"会改生成任务正在读的那几份数据"的入口，不只是三颗生成按钮。
     */
    public void setLayerBusy(boolean busy) {
        this.layerBusy = busy;
        refreshButtonStates();
    }

    /** 这一页此刻被 RES_LAYER 占着吗（自检读它判 syncRunStates 那根接线在不在）。 */
    public boolean layerBusy() { return layerBusy; }

    /**
     * 四份库名合规吗：逐个非空且只含 [A-Za-z0-9_]，第一条不合规的用人话说出来（返回 null 表示都行）。
     *
     * <p>源库取自顶部连接栏所选库，三个目标库是本页的输入框。外场真撞的一条：源库/目标库一旦空着，
     * 生成照发 `FROM \` \`.\`t_oil\``、`USE \`\`;` —— 交付的 .sql 静默就是坏的，没人会在打开前发现。
     * 所以这一判在 generateTo 起任务之前跑，不合规就当场拦下，不落一份畸形产物。
     */
    public static String checkDbNames(LayerGen.GenOptions o) {
        String[][] named = {
                {"源库", o.sourceDb, "ods"}, {"对象层 obj 库", o.objDb, "obj"},
                {"领域层 dom 库", o.domDb, "dom"}, {"主题层 ads 库", o.adsDb, "ads"}
        };
        for (String[] kv : named) {
            String v = kv[1] == null ? "" : kv[1].trim();
            if (v.isEmpty()) {
                return kv[0] + "名为空：需要先确定库名（默认 " + kv[2] + "）。"
                        + "空着产出的 .sql 里 USE 与 FROM 的库名就是空的，交付件直接不可用。";
            }
            if (!DB_IDENT.matcher(v).matches()) {
                return kv[0] + "名「" + v + "」不合规：只允许字母、数字、下划线，不许有反引号、引号、空格或中文。";
            }
        }
        return null;
    }

    public boolean adsReady() { return objGenerated && domGenerated; }

    private void generate(final LayerGen.Layer layer) {
        generateTo(layer, host.exportRoot());
    }

    /** 从界面字段拼一份生成选项（EDT 上读输入框）；按钮与字段窗都用它，避免两处各读各的。 */
    private LayerGen.GenOptions buildOptions() {
        LayerGen.GenOptions o = new LayerGen.GenOptions();
        o.sourceDb = sourceDbName();
        o.objDb = objDb.getText().trim();
        o.domDb = domDb.getText().trim();
        o.adsDb = adsDb.getText().trim();
        o.writeMode = writeMode.isSelected();
        return o;
    }

    /** 本层入库目标库：obj→objDb、dom→domDb、ads→adsDb（与 DDL 的 USE 头同源）。 */
    private String targetDbFor(LayerGen.Layer layer, LayerGen.GenOptions o) {
        if (layer == LayerGen.Layer.OBJ) { return o.objDb; }
        if (layer == LayerGen.Layer.DOM) { return o.domDb; }
        return o.adsDb;
    }

    /** 显式产物根：按钮走 host.exportRoot()，自检走临时目录（仿 SqlPage.generateExcel(boolean,String)）。 */
    public void generateTo(final LayerGen.Layer layer, String rootDir) {
        if (mapping == null) { return; }
        final LayerGen.GenOptions o = buildOptions();
        // 库名不合规就当堂拦下：源库跟着顶部连接栏走（未选时按脚本默认 ods 兜底），三个目标库随手可编辑；
        // 一旦空着或带反引号，产物里就是 FROM ` `.`t_oil` 与 USE ``;` —— 交付件静默坏掉，
        // 比不交付更糟（弹窗＋日志说清改哪儿）
        String bad = checkDbNames(o);
        if (bad != null) {
            logPanel.log("x [ERROR] LAYER | 已拦下 " + layer + " 生成：" + bad);
            host.informError("库名不合规，未生成", bad);
            return;
        }
        final String root = rootDir;
        // 配置只在 EDT 上现读一份传进 worker：口令只在这一步离开输入框，不落文件/日志。
        // writeMode 开时才连库，关时纯落盘（自检断言的就是关态无 DB 副作用）。
        final Config cfg = o.writeMode ? host.currentConfig() : null;
        // 起任务前在 EDT 上把 worker 要读的三份共享状态拍成快照：这一页的「载入映射」会整体换掉
        // mapping、清掉例外，「读源库结构」会换掉 sourceTables，而 RES_LAYER 只挡得住再点一次生成。
        // 312 表的生成跑到一半时点「载入映射」，worker 就会拿新映射配旧源结构——两份各半。
        // mapping／sourceTables 由各自的 setter 整份替换（不就地改），所以引用快照即足够；
        // 例外表是就地 put/clear，必须拷一份。
        final MappingFile mapSnap = mapping;
        final List<LayerGen.SourceTable> srcSnap = new ArrayList<LayerGen.SourceTable>(sourceTables);
        final Map<String, String[]> exSnap = snapshotExceptions();
        host.runLayerTask("生成 " + layer, new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                sink.setTotal(2);
                sink.advance(0, "计算 " + layer + " 层产物（映射 " + mapSnap.tables().size()
                        + " 表 / 源结构 " + srcSnap.size() + " 表）");
                LayerGen.LayerOutput out = LayerGen.genLayer(layer, mapSnap, srcSnap, o);
                if (sink.cancelled()) {
                    sink.log("! [WARN] LAYER | 已取消：" + layer + " 未落盘，产物未生成");
                    return null;
                }
                applyFieldExceptions(out.fieldMapping, exSnap);
                String dir = root + File.separator + "layer_"
                        + new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
                new File(dir).mkdirs();
                String tag = layer.name().toLowerCase();
                sink.advance(1, "落盘 " + tag + "_ddl / _transform / _field_mapping 到 " + dir);
                try {
                    writeText(dir + File.separator + tag + "_ddl.sql", out.ddlSql);
                    writeText(dir + File.separator + tag + "_transform.sql", out.transformSql);
                    MappingFile.writeFieldCsv(dir + File.separator + tag + "_field_mapping.csv", out.fieldMapping);
                } catch (Exception ex) {
                    sink.logError("x [ERROR] LAYER | 落盘失败：" + ex.getMessage());
                    return null;
                }
                sink.advance(1, "落盘完成");
                sink.log("- [INFO] LAYER | " + layer + " 表 " + out.tableCount + " 张 | 字段映射 "
                        + out.fieldMapping.size() + " 条 | 未绑定 " + out.unbound.size() + " 张 | 产物 " + dir);
                for (String w : out.warnings) { sink.log("! [WARN] LAYER | " + w); }
                if (sink.cancelled()) {
                    sink.log("! [WARN] LAYER | 已取消：" + layer + " 不入库，只留已落盘的产物 " + dir);
                    return null;
                }
                if (o.writeMode) {
                    executeWrite(layer, o, out, cfg, sink);
                    // 入库途中被取消：execute 的分条循环已 break、只灌了部分，不能再报"已生成"。
                    if (sink.cancelled()) {
                        sink.log("! [WARN] LAYER | 已取消：" + layer + " 入库中断，产物已落盘 " + dir);
                        return null;
                    }
                }
                return new Object[]{layer, out, dir};
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                Object[] arr = (Object[]) res;
                LayerGen.Layer lay = (LayerGen.Layer) arr[0];
                if (lay == LayerGen.Layer.OBJ) { objGenerated = true; }
                if (lay == LayerGen.Layer.DOM) { domGenerated = true; }
                refreshButtonStates();
                String dir = (String) arr[2];
                host.inform("已生成 " + lay + "，产物目录：" + dir);
            }
        });
    }

    /**
     * writeMode 开时的入库：连当前数据源跑 LayerGen.execute（建库→建表→灌数），
     * 单条失败在 execute 内部隔离记 errors。整段在 worker 线程跑，DbClient 用完即关。
     * 与 SqlPage.runScripts 的 new DbClient(cfg) 同一形状；日志走 sink（留在本页面板）。
     */
    private void executeWrite(LayerGen.Layer layer, LayerGen.GenOptions o,
                              LayerGen.LayerOutput out, Config cfg, ImportParse.Sink sink) {
        String target = targetDbFor(layer, o);
        DbClient c = null;
        try {
            c = new DbClient(cfg);
            LayerGen.WriteResult wr = LayerGen.execute(out, target, c, sink);
            sink.log("- [INFO] LAYER-EXEC | " + layer + " → 库 " + target
                    + " | 建表 " + wr.tablesCreated + " | 影响行 " + wr.rowsAffected
                    + " | 错误 " + wr.errors.size());
        } catch (Exception ex) {
            // 这句既可能是握手失败（什么都没跑），也可能是连上之后 execute 中途抛（已跑掉一部分），
            // 所以不写"连接失败…入库未执行"那种只认前一种的断言
            sink.logError("x [ERROR] LAYER-EXEC | 连接或执行失败：无法连接到 " + cfg.host + ":" + cfg.port
                    + "（用户 " + cfg.user + "），本层入库未确认全部执行" + LogText.NL
                    + LogText.indentDetail(LogText.describe(ex)));
        } finally {
            if (c != null) { c.close(); }
        }
    }

    /**
     * 读源库结构：源库就是顶部连接栏选中的那个库，本页不再单独输入。
     * DB I/O 全在 worker 里跑（真 GUI 下不在 EDT），清单落界面在 onUi（EDT）。
     * 连不上、或连上后读结构中途失败，都按"LAYER | 连接或执行失败＋源库结构未加载"口径写日志并 error 弹窗。
     */
    private void readSourceStructure() {
        if (!host.connected()) {
            host.warn("未连接数据库，无法读取源库结构：请先在顶部连接。");
            return;
        }
        final Config cfg = host.currentConfig();
        final String db = cfg == null || cfg.database == null ? "" : cfg.database.trim();
        if (db.isEmpty()) {
            host.warn("顶部还没选库：请先在连接栏选择一个库，源库结构从它读。");
            return;
        }
        host.runLayerTask("读源库结构 " + db, new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                sink.setTotal(2);
                sink.advance(0, "连接 " + cfg.host + ":" + cfg.port);
                DbClient c = null;
                try {
                    c = new DbClient(cfg, 5000);   // 5 秒握手超时，同连接区取库列表口径
                    sink.advance(1, "读 " + db + " 的用户表结构");
                    // 一张表计一项、总数在列完表名时就报出去，「取消」在表与表之间生效：
                    // 这一支读的是同一个 sink，312 张表时进度框才不是停在 0% 的"准备中"
                    List<LayerGen.SourceTable> st = LayerGen.readSource(c, db, sink);
                    if (sink.cancelled()) {
                        sink.log("! [WARN] LAYER | 已取消：源库 " + db + " 的结构未应用（读到 "
                                + st.size() + " 张也不覆盖界面上那一份）");
                        return null;
                    }
                    return new Object[]{st, null};
                } catch (Exception ex) {
                    // 与 executeWrite 那句同口径：这一支也覆盖"握手成了、读结构时抛"，不只算连接失败
                    sink.logError("x [ERROR] LAYER | 连接或执行失败：无法连接到 " + cfg.host + ":" + cfg.port
                            + "（用户 " + cfg.user + "），源库结构未加载" + LogText.NL
                            + LogText.indentDetail(LogText.describe(ex)));
                    return new Object[]{null, LogText.describe(ex)};
                } finally {
                    if (c != null) { c.close(); }
                }
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                Object[] arr = (Object[]) res;
                if (arr[1] != null) {
                    host.informError("读取源库结构失败", String.valueOf(arr[1]));
                    return;
                }
                @SuppressWarnings("unchecked")
                List<LayerGen.SourceTable> st = (List<LayerGen.SourceTable>) arr[0];
                setSourceTables(st);
                logPanel.log("- [INFO] LAYER | 读源库 " + db + " | 用户表 " + st.size() + " 张");
                if (builtinMapping && !st.isEmpty()) {
                    // 用户点「读源库结构」时界面上还是随包示例：顺手把整批源表按条款体系铺成映射，
                    // 免得每次都要再点一颗；已经载入过真映射的，不在这里偷偷覆盖
                    logPanel.log("- [INFO] LAYER | 仍是内置模板，按读到的 " + st.size()
                            + " 张源表自动映射");
                    host.inform("已读取源库结构：库 " + db + " 共 " + st.size()
                            + " 张用户表，正在按条款体系自动映射（一源一表）。");
                    autoMapFromTables(new ArrayList<LayerGen.SourceTable>(st), "按源库结构识别");
                    return;
                }
                if (!builtinMapping) {
                    logPanel.log("- [INFO] LAYER | 当前是载入的映射，未自动覆盖；要按条款体系重铺可点「按源库结构自动映射」");
                }
                host.inform("已读取源库结构：库 " + db + " 共 " + st.size() + " 张用户表");
            }
        });
    }

    /** 源库名取顶部连接栏所选库（已 trim）；没选库时按脚本兜底 "ods"，不另设输入口。 */
    public String sourceDbName() {
        Config cfg = host.currentConfig();
        String db = cfg == null || cfg.database == null ? "" : cfg.database.trim();
        return db.isEmpty() ? DEFAULT_SOURCE_DB : db;
    }

    /** 例外表在 EDT 上拍的浅拷贝：它是就地 put/clear 的，worker 拿引用会读到改一半的那一份。 */
    private Map<String, String[]> snapshotExceptions() {
        return new java.util.LinkedHashMap<String, String[]>(fieldExceptions);
    }

    /** 例外覆盖：把已存的转换规则/映射状态盖到自动草稿出来的字段映射行上（只改 CSV 内容，不动 DDL/SQL）。 */
    private static void applyFieldExceptions(List<MappingFile.FieldMap> rows, Map<String, String[]> exceptions) {
        if (rows == null || exceptions.isEmpty()) { return; }
        for (MappingFile.FieldMap f : rows) {
            String[] e = exceptions.get(exceptionKey(f.dstTable, f.dstField));
            if (e != null) { f.transformRule = e[0]; f.status = e[1]; }
        }
    }

    private static String exceptionKey(String dstTable, String dstField) {
        return (dstTable == null ? "" : dstTable) + "." + (dstField == null ? "" : dstField);
    }

    /**
     * 双击行弹字段映射例外窗：以这一张源表为键，把它在 obj/dom 上的自动草稿字段列出来，
     * 转换规则/映射状态两列可改（其余只读）。照 SqlMetadataDialog 的非模态 JDialog＋可编辑
     * DefaultTableModel 形状，OK 把改动按 dstTable.dstField 存进例外表，供下次生成落盘前覆盖。
     *
     * <p>草稿是现算的（obj/dom 各跑一次 genLayer）：真映射是 312 张表、上千字段实例，这一算就是
     * 一两秒到几秒，站在事件分发线程上算等于整窗冻住——这一页别处都守的"UI 不给自己算"，
     * 这里也得守。所以算法放进 runLayerTask 的 worker，算完回 EDT 建窗（同 SqlPage 双击一行的形状）。
     * 起任务前照 generateTo 一样把 mapping／sourceTables／例外拍成快照，别让 worker 读到中途换掉的那份。
     */
    private void openFieldMappingDialog(final int row) {
        if (mapping == null) { return; }
        MappingFile.TableMap tm = mapping.tables().get(row);
        final String physical = tm.odsPhysical == null || tm.odsPhysical.isEmpty()
                ? tm.tableEn : tm.odsPhysical;
        // 输入框只能在 EDT 上读，先拼好选项再交给 worker（worker 里不碰任何 Swing 组件）
        final LayerGen.GenOptions o = buildOptions();
        final MappingFile mapSnap = mapping;
        final List<LayerGen.SourceTable> srcSnap = new ArrayList<LayerGen.SourceTable>(sourceTables);
        final Map<String, String[]> exSnap = snapshotExceptions();
        host.runLayerTask("取 " + physical + " 的字段草稿", new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                // 一层计一项：obj、dom 各算一遍，留 srcTable 命中的行、按目标字段去重
                sink.setTotal(2);
                Map<String, MappingFile.FieldMap> byTarget = new LinkedHashMap<String, MappingFile.FieldMap>();
                int i = 0;
                for (LayerGen.Layer lay : new LayerGen.Layer[]{LayerGen.Layer.OBJ, LayerGen.Layer.DOM}) {
                    if (sink.cancelled()) {
                        sink.log("! [WARN] LAYER | 已取消：" + physical + " 的字段草稿未列出");
                        return null;
                    }
                    i++;
                    sink.advance(0, "算 " + lay + " 层字段草稿 第 " + i + "/2 层");
                    LayerGen.LayerOutput out = LayerGen.genLayer(lay, mapSnap, srcSnap, o);
                    for (MappingFile.FieldMap f : out.fieldMapping) {
                        if (physical.equals(f.srcTable)) { byTarget.put(exceptionKey(f.dstTable, f.dstField), f); }
                    }
                    sink.advance(1, lay + " 层字段草稿已算");
                }
                List<MappingFile.FieldMap> rows = new ArrayList<MappingFile.FieldMap>(byTarget.values());
                applyFieldExceptions(rows, exSnap);
                return new Object[]{physical, rows};
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                Object[] arr = (Object[]) res;
                @SuppressWarnings("unchecked")
                List<MappingFile.FieldMap> rows = (List<MappingFile.FieldMap>) arr[1];
                showFieldMappingDialog((String) arr[0], rows);
            }
        });
    }

    /** 草稿算好之后在 EDT 上建窗、填表、挂两颗按钮：这一段只碰 Swing，不算任何东西。 */
    private void showFieldMappingDialog(String physical, List<MappingFile.FieldMap> rows) {
        Window w = SwingUtilities.getWindowAncestor(this);
        Frame owner = (w instanceof Frame) ? (Frame) w : null;
        JDialog dlg = new JDialog(owner, "字段映射（" + physical + "）", false);
        WindowIcons.apply(dlg);
        JPanel content = new JPanel(new BorderLayout(0, UiScale.px(6)));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(
                UiScale.px(8), UiScale.px(10), UiScale.px(8), UiScale.px(10)));
        // 文案与数据路一致：这一窗改的东西只进 <层>_field_mapping.csv 的「转换规则／映射状态」两列
        // （证据与备注），不回写、也不会重算已经生成的转换 SQL——SQL 里的表达式由引擎自己发。
        JLabel head = new JLabel("<html>源表「" + physical + "」映射到的目标字段草稿。<br>"
                + "「转换规则（登记进映射 CSV）」「映射状态」两列可直接改，改完点「确定」记下例外。<br>"
                + "<b>这两列是写进 &lt;层&gt;_field_mapping.csv 的转换口径登记（证据/备注），"
                + "不会改写已生成的 DDL 与转换 SQL</b>；要改 SQL 里的表达式得回到映射与源结构那一层。<br>"
                + "（还没有自动草稿时，先「读源库结构」并生成 obj/dom）</html>");
        content.add(head, BorderLayout.NORTH);

        final String[] cols = {"目标表", "目标字段", "源字段",
                "转换规则（登记进映射 CSV）", "映射状态"};
        final javax.swing.table.DefaultTableModel dm = new javax.swing.table.DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c >= 3; }
        };
        final List<String[]> preEdit = new ArrayList<String[]>();
        for (MappingFile.FieldMap f : rows) {
            String rule = f.transformRule == null ? "" : f.transformRule;
            String status = f.status == null ? "" : f.status;
            dm.addRow(new Object[]{f.dstTable, f.dstField, f.srcField, rule, status});
            // 逐行留一份"开窗时的那两列值"（已含既有例外）：「确定」只 put 真正改过的行
            preEdit.add(new String[]{exceptionKey(f.dstTable, f.dstField), rule, status});
        }
        JTable ft = new JTable(dm);
        Tables.style(ft);
        ft.getTableHeader().setToolTipText("<html>「转换规则（登记进映射 CSV）」改的是这一行的口径登记，"
                + "只写进 &lt;层&gt;_field_mapping.csv 作证据／备注，<br>不会改写本层已生成的 _transform.sql。"
                + "SQL 里的表达式由引擎按映射表＋源结构自己产。</html>");
        content.add(new JScrollPane(ft), BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(6), 0));
        JButton ok = new JButton("确定");
        JButton close = new JButton("关闭");
        ok.addActionListener(e -> {
            int changed = 0;
            for (int r = 0; r < dm.getRowCount() && r < preEdit.size(); r++) {
                String[] pre = preEdit.get(r);
                String rule = strAt(dm, r, 3);
                String status = strAt(dm, r, 4);
                // 没改的行不进例外表：一份"看过没动"的快照会把当前自动草稿值钉死，
                // 以后引擎或默认口径变了都被这份旧值盖住，条数也会把没改的算成改过
                if (rule.equals(pre[1]) && status.equals(pre[2])) { continue; }
                fieldExceptions.put(pre[0], new String[]{rule, status});
                changed++;
            }
            logPanel.log("- [INFO] LAYER | 记录源表 " + physical + " 的字段映射例外 " + changed + " 条（只记改过的）");
            dlg.dispose();
        });
        close.addActionListener(e -> dlg.dispose());
        footer.add(ok);
        footer.add(close);
        dlg.getRootPane().setDefaultButton(ok);
        content.add(footer, BorderLayout.SOUTH);

        dlg.setContentPane(content);
        // 关窗要真销毁：非模态框默认是 HIDE_ON_CLOSE，点标题栏 X 只藏起来，
        // 每双击一次就留一颗活着的 owned window（照 StructureDialog 补 DISPOSE_ON_CLOSE）
        dlg.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        // Esc 等同「关闭」：这颗框只有「确定」写例外，Esc 关掉不写回，与 StructureDialog 同一形状
        dlg.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "close");
        dlg.getRootPane().getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                dlg.dispose();
            }
        });
        // 尺寸走 Windows.clientSize（同 StructureDialog/SqlMetadataDialog）：setSize 折的是整窗外框，
        // 760x460 是设计值、由 clientSize 内部折一次，再自行夹进屏幕可用区
        Windows.clientSize(content, dlg, 760, 460);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private static String strAt(javax.swing.table.TableModel m, int r, int c) {
        Object v = m.getValueAt(r, c);
        return v == null ? "" : String.valueOf(v);
    }

    /** 自检用：直接注入一条字段映射例外，验落盘前的覆盖写回这条路（不弹模态框）。 */
    public void setFieldExceptionOverride(String dstTable, String dstField,
                                          String transformRule, String status) {
        fieldExceptions.put(exceptionKey(dstTable, dstField), new String[]{transformRule, status});
    }

    private static void writeText(String path, String text) throws java.io.IOException {
        java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(path), java.nio.charset.StandardCharsets.UTF_8);
        try { w.write(text); } finally { w.close(); }
    }

    public LogPanel logPanel() { return logPanel; }
    /** 免配模板那条路的拖拽区：自检按用户的方式把文件丢进去。 */
    public DropArea layerDropArea() { return fileDrop; }
    public JButton objButton() { return objBtn; }
    public JButton domButton() { return domBtn; }
    public JButton adsButton() { return adsBtn; }
    /** 「载入映射文件」那颗：自检按用户的入口点它，也用它判忙时灰没灰（#8）。 */
    public JButton loadMappingButton() { return loadBtn; }
    public JButton exportTemplateButton() { return exportTplBtn; }
    public int mappingRowCount() { return model.getRowCount(); }
    /** 自检读的是真映射，不是界面上那几列文字。 */
    public MappingFile mapping() { return mapping; }
    public String mappingLabelText() { return mappingLabel.getText(); }
    /** 「读源库结构」那颗：同上。 */
    public JButton readSourceButton() { return readSrcBtn; }
    /** 「按源库结构自动映射」那颗：自检按用户入口点它。 */
    public JButton autoMapButton() { return autoMapBtn; }
    /** 对象层库名那一格：自检要往里灌一个不合规的库名，验生成被拦下而不是落一份坏产物（#7）。 */
    public JTextField objDbField() { return objDb; }
}
