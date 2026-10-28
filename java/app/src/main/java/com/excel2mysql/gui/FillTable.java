package com.excel2mysql.gui;

import javax.swing.JTable;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;

/**
 * 字段清单表：列宽铺满视口，但绝不把任何一列压到它内容需要的宽度以下。
 *
 * 每列先取"设计下限与内容需要"的大者当底，视口还有富余就摊给文字列（见
 * {@link Tables#fillWidths}）。AUTO_RESIZE_OFF 下表格宽度等于各列 preferredWidth 之和，
 * 视口不会把它拉宽——"表格右侧留一条白"就是这么来的；铺满只能靠这里把 preferredWidth
 * 定到视口宽度，不能靠列自适应（自适应会把超长那一格压回视口宽度，切掉的半截滚不到）。
 *
 * 重算挂在 doLayout 上而不是视口监听上：pack/validate 是同步排版的，监听器要等内容面板
 * 画出来才收得到事件，自检与首次显示都会晚一步。
 */
public final class FillTable extends JTable {

    private final int[] floors;
    private final int[] stretch;
    private boolean fitting;
    private boolean hooked;

    public FillTable(TableModel model, int[] floors, int[] stretch) {
        super(model);
        this.floors = floors.clone();
        this.stretch = stretch.clone();
        fitColumns();
    }

    /** 每列的底宽：设计下限、单元格内容、表头文字三者取大。 */
    private int[] baseWidths() {
        int[] bases = new int[Math.min(floors.length, getColumnCount())];
        for (int c = 0; c < bases.length; c++) {
            bases[c] = Math.max(floors[c], Tables.contentWidth(this, c));
        }
        return bases;
    }

    /** 按当前视口宽度重算列宽；视口还没排过版时退回"下限与内容取大者"。 */
    public void fitColumns() {
        if (fitting) {
            return;
        }
        int avail = getParent() == null ? 0 : getParent().getWidth();
        if (avail <= 0) {
            Tables.fitColumns(this, floors);
            return;
        }
        int[] widths = Tables.fillWidths(baseWidths(), avail, stretch);
        boolean changed = false;
        for (int c = 0; c < widths.length; c++) {
            TableColumn tc = getColumnModel().getColumn(c);
            if (tc.getWidth() == widths[c] && tc.getPreferredWidth() == widths[c]) {
                continue;
            }
            tc.setPreferredWidth(widths[c]);
            tc.setWidth(widths[c]);
            changed = true;
        }
        if (!changed) {
            return;
        }
        fitting = true;
        try {
            super.doLayout();
            if (getParent() != null) {
                // 列宽一变首选宽度也变了，视口得当场按它重设表格 bounds：等下一次 validate 的话，
                // 这一轮里表格还是旧宽度，超宽的那一列会被 TableLayout 挤回视口宽度（内容被切）
                getParent().doLayout();
                super.doLayout();   // 表格宽度已经换了，再按新宽度把列摆一遍
                getParent().revalidate();
            }
        } finally {
            fitting = false;
        }
    }

    @Override
    public void doLayout() {
        super.doLayout();
        fitColumns();
    }

    /**
     * 视口比内容宽时把首选宽度抬到视口宽度。
     *
     * AUTO_RESIZE_OFF 下 ViewportLayout 只在"首选尺寸变了"时才给表格重设 bounds，
     * 所以"表格右侧留一条白"不是列宽算出来的，是表格本身没被拉宽。抬了首选宽度，
     * 视口会把表格撑满，doLayout 再把富余摊到列上；内容比视口宽时首选宽度仍是它自己的，
     * 横向滚动条照旧。
     */
    @Override
    public java.awt.Dimension getPreferredSize() {
        java.awt.Dimension d = super.getPreferredSize();
        if (getParent() != null) {
            d.width = Math.max(d.width, getParent().getWidth());
        }
        return d;
    }

    /** 竖条出现/消失时视口宽度变了但表格 bounds 不变，这条监听补的正是那一档。 */
    @Override
    public void addNotify() {
        super.addNotify();
        if (!hooked && getParent() != null) {
            hooked = true;
            getParent().addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    fitColumns();
                }
            });
        }
    }
}
