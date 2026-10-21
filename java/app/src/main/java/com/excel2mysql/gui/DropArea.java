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

    private static final String HINT =
            "将 Excel 文件或 ZIP 压缩包拖到此处，或点击选择文件\n"
            + "（支持 .xlsx / .xls / .zip，可多选）";
    private static final Color LINE = new Color(0x88, 0x88, 0x88);
    private static final Color LINE_HOVER = new Color(0x2f, 0x6f, 0xeb);
    private static final Color BG = new Color(0xf7, 0xf9, 0xfc);
    private static final Color BG_HOVER = new Color(0xee, 0xf4, 0xff);

    private final Handler handler;
    private final Runnable onChoose;
    private boolean hover;
    private final DropTarget target;

    public DropArea(Handler handler, Runnable onChoose) {
        this.handler = handler;
        this.onChoose = onChoose;
        setPreferredSize(new Dimension(200, 96));
        setMinimumSize(new Dimension(120, 80));
        setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        setToolTipText("点击选择 Excel / ZIP 文件；也可以直接拖到这里");

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
        return HINT;
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
            g2.setColor(hover ? BG_HOVER : BG);
            g2.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 8, 8);
            g2.setColor(hover ? LINE_HOVER : LINE);
            g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_BUTT,
                    java.awt.BasicStroke.JOIN_MITER, 1f, new float[]{6f, 4f}, 0f));
            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 8, 8);

            g2.setColor(new Color(0x33, 0x33, 0x33));
            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            FontMetrics fm = g2.getFontMetrics();
            String[] lines = HINT.split("\n");
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
}
