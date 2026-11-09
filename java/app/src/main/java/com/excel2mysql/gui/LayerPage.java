package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.MappingFile;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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

/** 第 4 页签：读 ods 建模 obj/dom/ads。映射文件驱动，三按钮按依赖锁，默认只生成脚本。 */
public final class LayerPage extends JPanel {

    public interface Host {
        Config currentConfig();
        boolean connected();
        String exportRoot();
        void warn(String text);
        void inform(String text);
        void informError(String title, String text);
        void runLayerTask(String title, Function<ImportParse.Sink, Object> work, Consumer<Object> onUi);
    }

    private final Host host;
    private final LogPanel logPanel = new LogPanel();
    private final JComboBox<String> sourceDb = new JComboBox<String>(new String[]{"ods"});
    private final JTextField objDb = new JTextField("obj", 6);
    private final JTextField domDb = new JTextField("dom", 6);
    private final JTextField adsDb = new JTextField("ads", 6);
    private final JLabel mappingLabel = new JLabel("未载入映射");
    private final JCheckBox writeMode = new JCheckBox("同时写入目标库", false);
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
    private boolean objGenerated;
    private boolean domGenerated;
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
    }

    private JPanel buildTop() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(PageIntro.of("读 ods 建模 obj/dom/ads"), BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        row.add(new JLabel("源库"));
        sourceDb.setEditable(true);
        row.add(sourceDb);
        JButton load = new JButton("载入映射文件");
        Buttons.secondary(load);
        load.addActionListener(e -> chooseMapping());
        row.add(load);
        JButton readSrc = new JButton("读源库结构");
        Buttons.secondary(readSrc);
        readSrc.setToolTipText("<html>连上当前数据源后，读源库这一列的下拉库里的用户表结构，<br>"
                + "填库列表并绑定映射行的绑定状态；obj/dom 生成依赖这一步。</html>");
        readSrc.addActionListener(e -> readSourceStructure());
        row.add(readSrc);
        row.add(mappingLabel);
        row.add(new JLabel("目标库 obj/dom/ads"));
        row.add(objDb); row.add(domDb); row.add(adsDb);
        p.add(row, BorderLayout.CENTER);
        return p;
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

    public boolean loadMapping(String path) {
        try {
            mapping = MappingFile.loadTableCsv(path);
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
        objBtn.setEnabled(ready);
        domBtn.setEnabled(ready);
        adsBtn.setEnabled(ready && objGenerated && domGenerated);
        if (ready && !objGenerated && !domGenerated) {
            adsBtn.setToolTipText("<html>主题层 ads 从 obj＋dom 汇总。<br>请先生成对象层与领域层。</html>");
        } else {
            adsBtn.setToolTipText(null);
        }
    }

    public boolean adsReady() { return objGenerated && domGenerated; }

    private void generate(final LayerGen.Layer layer) {
        generateTo(layer, host.exportRoot());
    }

    /** 从界面字段拼一份生成选项（EDT 上读输入框）；按钮与字段窗都用它，避免两处各读各的。 */
    private LayerGen.GenOptions buildOptions() {
        LayerGen.GenOptions o = new LayerGen.GenOptions();
        o.sourceDb = String.valueOf(sourceDb.getSelectedItem());
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
        final String root = rootDir;
        // 配置只在 EDT 上现读一份传进 worker：口令只在这一步离开输入框，不落文件/日志。
        // writeMode 开时才连库，关时纯落盘（自检断言的就是关态无 DB 副作用）。
        final Config cfg = o.writeMode ? host.currentConfig() : null;
        host.runLayerTask("生成 " + layer, new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                LayerGen.LayerOutput out = LayerGen.genLayer(layer, mapping, sourceTables, o);
                applyFieldExceptions(out.fieldMapping);
                String dir = root + File.separator + "layer_"
                        + new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
                new File(dir).mkdirs();
                String tag = layer.name().toLowerCase();
                try {
                    writeText(dir + File.separator + tag + "_ddl.sql", out.ddlSql);
                    writeText(dir + File.separator + tag + "_transform.sql", out.transformSql);
                    MappingFile.writeFieldCsv(dir + File.separator + tag + "_field_mapping.csv", out.fieldMapping);
                } catch (Exception ex) {
                    sink.logError("x [ERROR] LAYER | 落盘失败：" + ex.getMessage());
                    return null;
                }
                sink.log("- [INFO] LAYER | " + layer + " 表 " + out.tableCount + " 张 | 字段映射 "
                        + out.fieldMapping.size() + " 条 | 未绑定 " + out.unbound.size() + " 张 | 产物 " + dir);
                for (String w : out.warnings) { sink.log("! [WARN] LAYER | " + w); }
                if (o.writeMode) { executeWrite(layer, o, out, cfg, sink); }
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
     * 读源库结构：连上当前数据源后列库填 sourceDb 下拉、再读选中的那一库的用户表结构。
     * DB I/O 全在 worker 里跑（真 GUI 下不在 EDT），库列表/清单落界面在 onUi（EDT）。
     * 连不上、或连上后读结构中途失败，都按"LAYER | 连接或执行失败＋库列表未加载"口径写日志并 error 弹窗。
     */
    private void readSourceStructure() {
        if (!host.connected()) {
            host.warn("未连接数据库，无法读取源库结构：请先在顶部连接并加载库列表。");
            return;
        }
        final Config cfg = host.currentConfig();
        final String db = String.valueOf(sourceDb.getSelectedItem());
        host.runLayerTask("读源库结构 " + db, new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                DbClient c = null;
                try {
                    c = new DbClient(cfg, 5000);   // 5 秒握手超时，同连接区取库列表口径
                    List<String> dbs = c.listDatabases();
                    List<LayerGen.SourceTable> st = LayerGen.readSource(c, db);
                    return new Object[]{dbs, st, null};
                } catch (Exception ex) {
                    // 与 executeWrite 那句同口径：这一支也覆盖"握手成了、读结构时抛"，不只算连接失败
                    sink.logError("x [ERROR] LAYER | 连接或执行失败：无法连接到 " + cfg.host + ":" + cfg.port
                            + "（用户 " + cfg.user + "），库列表未加载" + LogText.NL
                            + LogText.indentDetail(LogText.describe(ex)));
                    return new Object[]{null, null, LogText.describe(ex)};
                } finally {
                    if (c != null) { c.close(); }
                }
            }
        }, new Consumer<Object>() {
            @Override public void accept(Object res) {
                Object[] arr = (Object[]) res;
                if (arr[2] != null) {
                    host.informError("读取源库结构失败", String.valueOf(arr[2]));
                    return;
                }
                @SuppressWarnings("unchecked")
                List<String> dbs = (List<String>) arr[0];
                @SuppressWarnings("unchecked")
                List<LayerGen.SourceTable> st = (List<LayerGen.SourceTable>) arr[1];
                setDatabases(dbs);
                setSourceTables(st);
                logPanel.log("- [INFO] LAYER | 读源库 " + db + " | 用户表 " + st.size()
                        + " 张 | 库列表 " + (dbs == null ? 0 : dbs.size()) + " 个");
                host.inform("已读取源库结构：库 " + db + " 共 " + st.size() + " 张用户表");
            }
        });
    }

    /** 把库列表灌进 sourceDb 下拉；选中项若还在就保持，否则回到首项。由 MainFrame 连接成功时同步。 */
    public void setDatabases(List<String> dbs) {
        // 空列表不灌：这是一颗可编辑下拉，被清光后 getSelectedItem() 回字面量 null，
        // String.valueOf 一拼就变成一库叫 "null"。宁可保住上一次/默认那一份值，也不清空成坑。
        if (dbs == null || dbs.isEmpty()) { return; }
        Object prev = sourceDb.getSelectedItem();
        sourceDb.removeAllItems();
        for (String d : dbs) { sourceDb.addItem(d); }
        if (prev != null && dbs.contains(String.valueOf(prev))) {
            sourceDb.setSelectedItem(prev);
        }
    }

    /** 例外覆盖：把已存的转换规则/映射状态盖到自动草稿出来的字段映射行上（只改 CSV 内容，不动 DDL/SQL）。 */
    private void applyFieldExceptions(List<MappingFile.FieldMap> rows) {
        if (rows == null || fieldExceptions.isEmpty()) { return; }
        for (MappingFile.FieldMap f : rows) {
            String[] e = fieldExceptions.get(exceptionKey(f.dstTable, f.dstField));
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
     */
    private void openFieldMappingDialog(int row) {
        if (mapping == null) { return; }
        MappingFile.TableMap tm = mapping.tables().get(row);
        final String physical = tm.odsPhysical == null || tm.odsPhysical.isEmpty()
                ? tm.tableEn : tm.odsPhysical;
        // 现取一份自动草稿：obj/dom 各跑 genLayer（离线，快），留 srcTable 命中的行，按目标字段去重
        LayerGen.GenOptions o = buildOptions();
        Map<String, MappingFile.FieldMap> byTarget = new LinkedHashMap<String, MappingFile.FieldMap>();
        for (LayerGen.Layer lay : new LayerGen.Layer[]{LayerGen.Layer.OBJ, LayerGen.Layer.DOM}) {
            LayerGen.LayerOutput out = LayerGen.genLayer(lay, mapping, sourceTables, o);
            for (MappingFile.FieldMap f : out.fieldMapping) {
                if (physical.equals(f.srcTable)) { byTarget.put(exceptionKey(f.dstTable, f.dstField), f); }
            }
        }
        applyFieldExceptions(new ArrayList<MappingFile.FieldMap>(byTarget.values()));
        Window w = SwingUtilities.getWindowAncestor(this);
        Frame owner = (w instanceof Frame) ? (Frame) w : null;
        JDialog dlg = new JDialog(owner, "字段映射（" + physical + "）", false);
        WindowIcons.apply(dlg);
        JPanel content = new JPanel(new BorderLayout(0, UiScale.px(6)));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(
                UiScale.px(8), UiScale.px(10), UiScale.px(8), UiScale.px(10)));
        JLabel head = new JLabel("<html>源表「" + physical + "」映射到的目标字段草稿。<br>"
                + "「转换规则」「映射状态」两列可直接改，改完点「确定」记下例外；"
                + "下次生成该层时，这些改动只写进 <层>_field_mapping.csv，不改 DDL 与转换 SQL。<br>"
                + "（还没有自动草稿时，先「读源库结构」并生成 obj/dom）</html>");
        content.add(head, BorderLayout.NORTH);

        final String[] cols = {"目标表", "目标字段", "源字段", "转换规则", "映射状态"};
        final javax.swing.table.DefaultTableModel dm = new javax.swing.table.DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c >= 3; }
        };
        final List<String[]> preEdit = new ArrayList<String[]>();
        for (MappingFile.FieldMap f : byTarget.values()) {
            String rule = f.transformRule == null ? "" : f.transformRule;
            String status = f.status == null ? "" : f.status;
            dm.addRow(new Object[]{f.dstTable, f.dstField, f.srcField, rule, status});
            // 逐行留一份"开窗时的那两列值"（已含既有例外）：「确定」只 put 真正改过的行
            preEdit.add(new String[]{exceptionKey(f.dstTable, f.dstField), rule, status});
        }
        JTable ft = new JTable(dm);
        Tables.style(ft);
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
    public JButton objButton() { return objBtn; }
    public JButton domButton() { return domBtn; }
    public JButton adsButton() { return adsBtn; }
}
