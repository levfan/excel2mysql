package com.excel2mysql.gui;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

    public LayerPage(Host host) {
        this.host = host;
        Buttons.secondary(objBtn);
        Buttons.secondary(domBtn);
        Buttons.secondary(adsBtn);
        setLayout(new BorderLayout(0, UiScale.px(6)));
        add(buildTop(), BorderLayout.NORTH);
        Tables.style(table);
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

    /** 显式产物根：按钮走 host.exportRoot()，自检走临时目录（仿 SqlPage.generateExcel(boolean,String)）。 */
    public void generateTo(final LayerGen.Layer layer, String rootDir) {
        if (mapping == null) { return; }
        final LayerGen.GenOptions o = new LayerGen.GenOptions();
        o.sourceDb = String.valueOf(sourceDb.getSelectedItem());
        o.objDb = objDb.getText().trim();
        o.domDb = domDb.getText().trim();
        o.adsDb = adsDb.getText().trim();
        o.writeMode = writeMode.isSelected();
        final String root = rootDir;
        host.runLayerTask("生成 " + layer, new Function<ImportParse.Sink, Object>() {
            @Override public Object apply(ImportParse.Sink sink) {
                LayerGen.LayerOutput out = LayerGen.genLayer(layer, mapping, sourceTables, o);
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
