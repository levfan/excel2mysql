package com.excel2mysql.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件拖拽接收区，对应 main.py:784-812 的 DropArea。
 *
 * Swing 没有 Qt 的样式表，虚线框、悬停变色和居中提示都在 paintComponent 里自己画；
 * 四个颜色值沿用 Python 侧的，两条线摆在一起时才是同一个界面。
 * 提示文字也直接画：放一个子 JLabel 的话，鼠标进出的事件会被子组件吃掉，
 * 悬停高亮看起来"时有时无"。
 */
public final class DropArea extends javax.swing.JPanel {

    /** 拖进来的路径列表，与点击虚线区打开的选择器共用同一个入口。 */
    public interface Handler {
        void onFiles(List<String> paths);
    }

    /** 第三行：粘贴入口。Ctrl+V 绑在窗口层级，与拖进这块虚线区走同一条解析路。 */
    public static final String PASTE_HINT = "快捷键：复制文件后按 Ctrl+V 直接添加";
    /** 第一页（Excel 导入）那句；第二页构造时传自己的两句，样式与行为共用这一份。 */
    public static final String EXCEL_HINT =
            "将 Excel 文件或 ZIP 压缩包拖到此处，或点击选择文件\n"
            + "（支持 .xlsx / .xls / .zip，可多选）\n" + PASTE_HINT;
    public static final String SQL_HINT =
            "将 SQL 脚本拖到此处，或点击选择文件\n（支持 .sql，可多选）\n" + PASTE_HINT;
    /** 第三页（SQL 执行器）的脚本队列：同一块拖拽区，多一句"按列表顺序执行"。 */
    public static final String RUN_HINT =
            "将 SQL 脚本拖到此处，或点击选择文件\n"
            + "（支持 .sql，可多选；按列表顺序执行）\n" + PASTE_HINT;
    private static final Color LINE = new Color(0xa8, 0xbc, 0xd8);
    private static final Color LINE_HOVER = new Color(0x2f, 0x6f, 0xeb);
    private static final Color BG = new Color(0xea, 0xf2, 0xfd);
    private static final Color BG_HOVER = new Color(0xd8, 0xe8, 0xff);
    private static final Color TEXT = new Color(0x2c, 0x4a, 0x72);

    private final Handler handler;
    private final Runnable onChoose;
    private final String hint;
    private boolean hover;
    private final DropTarget target;

    public DropArea(Handler handler, Runnable onChoose) {
        this(handler, onChoose, EXCEL_HINT, "点击选择 Excel / ZIP 文件；也可以直接拖到这里");
    }

    public DropArea(Handler handler, Runnable onChoose, String hint, String tooltip) {
        this.handler = handler;
        this.onChoose = onChoose;
        this.hint = hint;
        setPreferredSize(new Dimension(UiScale.px(200), UiScale.px(96)));
        setMinimumSize(new Dimension(UiScale.px(120), UiScale.px(80)));
        setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        setToolTipText(tooltip);

        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                setHover(true);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                setHover(false);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    fireClick();
                }
            }
        });

        target = new DropTarget(this, DnDConstants.ACTION_COPY, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent e) {
                if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    e.acceptDrag(DnDConstants.ACTION_COPY);
                    setHover(true);
                } else {
                    e.rejectDrag();
                }
            }

            @Override
            public void dragOver(DropTargetDragEvent e) {
                setHover(e.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
            }

            @Override
            public void dropActionChanged(DropTargetDragEvent e) {
            }

            @Override
            public void dragExit(DropTargetEvent e) {
                setHover(false);
            }

            @Override
            public void drop(DropTargetDropEvent e) {
                setHover(false);
                if (!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    e.rejectDrop();
                    return;
                }
                e.acceptDrop(DnDConstants.ACTION_COPY);
                List<String> paths = pathsFrom(e.getTransferable());
                if (handler != null) {
                    handler.onFiles(paths);
                }
                e.dropComplete(true);
            }
        }, true);
    }

    /**
     * 拖拽数据里取文件列表；拿不到就返回空表，由调用方在日志里说清"这次没收到文件"。
     * 不抛异常是为了不打断 EDT。
     */
    static List<String> pathsFrom(Transferable tf) {
        List<String> out = new ArrayList<String>();
        try {
            Object raw = tf.getTransferData(DataFlavor.javaFileListFlavor);
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    if (o instanceof File) {
                        out.add(((File) o).getPath());
                    }
                }
            }
        } catch (Exception e) {
            return new ArrayList<String>();
        }
        return out;
    }

    private void setHover(boolean on) {
        if (hover != on) {
            hover = on;
            repaint();
        }
    }

    /** 自检的保证项：接收器真的挂在这个组件上，而不是只有个画着虚线的空框。 */
    public boolean acceptsDrops() {
        return target != null && getDropTarget() == target;
    }

    public String hintText() {
        return hint;
    }

    /** 拖进来的文件走的是和选择器同一个入口，自检直接调它，不用伪造一次真实拖放。 */
    public void fireDrop(List<String> paths) {
        if (handler != null) {
            handler.onFiles(paths);
        }
    }

    /** 自检用：点击区接没接上选择器，以及点一下是否真的触发。 */
    public boolean hasClickAction() {
        return onChoose != null;
    }

    public void fireClick() {
        if (onChoose != null) {
            onChoose.run();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            // QSS 里的圆角、线宽、虚线间距都是逻辑像素，150% 屏上要一起放大
            int inset = UiScale.px(1);
            int radius = UiScale.px(8);
            g2.setColor(hover ? BG_HOVER : BG);
            g2.fillRoundRect(inset, inset, getWidth() - 2 * inset - 1,
                    getHeight() - 2 * inset - 1, radius, radius);
            g2.setColor(hover ? LINE_HOVER : LINE);
            g2.setStroke(new java.awt.BasicStroke(UiScale.px(2), java.awt.BasicStroke.CAP_BUTT,
                    java.awt.BasicStroke.JOIN_MITER, 1f,
                    new float[]{UiScale.px(6), UiScale.px(4)}, 0f));
            g2.drawRoundRect(inset, inset, getWidth() - 2 * inset - 1,
                    getHeight() - 2 * inset - 1, radius, radius);

            g2.setColor(TEXT);
            // Python 是 QFont("", 12)：默认字族、12pt，与界面同源
            g2.setFont(getFont().deriveFont(Font.PLAIN, (float) UiScale.fontPt(12)));
            FontMetrics fm = g2.getFontMetrics();
            String[] lines = hint.split("\n");
            int lineHeight = fm.getHeight();
            int y = (getHeight() - lines.length * lineHeight) / 2 + fm.getAscent();
            for (String line : lines) {
                int x = (getWidth() - fm.stringWidth(line)) / 2;
                g2.drawString(line, x, y);
                y += lineHeight;
            }
        } finally {
            g2.dispose();
        }
    }

    /** 对撞器读的是真组件上的底色，不是两边各抄一份常量。 */
    public Color fillColor() {
        return BG;
    }

    public Color hoverFillColor() {
        return BG_HOVER;
    }
}
