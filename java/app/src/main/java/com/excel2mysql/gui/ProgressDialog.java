package com.excel2mysql.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 模态进度框：耗时操作在子线程跑，界面靠这个框报"还在动"，并且能中途取消。
 *
 * 总项数必须一次算准再喂，边跑边加会让进度条倒退或卡在半途；
 * 单个几十万行的文件按 1 项计的话进度条五秒纹丝不动，所以按阶段计。
 * 所有控件更新一律排到事件分发线程——工作线程直接碰控件在 Swing 里不是"有时能跑"，是数据竞争。
 */
public final class ProgressDialog extends JDialog {

    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JLabel label = new JLabel("准备中…");
    private final JButton cancel = new JButton("取消");
    private int total = 1;
    private int done;
    private boolean cancelled;
    private int steps;

    public ProgressDialog(Frame owner, String title) {
        super(owner, title, true);
        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        bar.setStringPainted(true);
        body.add(label, BorderLayout.NORTH);
        body.add(bar, BorderLayout.CENTER);
        body.add(cancel, BorderLayout.SOUTH);
        setContentPane(body);
        cancel.addActionListener(e -> requestCancel());
        // 点关闭等同于取消：否则框消失了，任务还在后台跑
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestCancel();
            }
        });
        setPreferredSize(new Dimension(440, 120));
        pack();
        setLocationRelativeTo(owner);
    }

    public void setTotal(int total) {
        this.total = Math.max(1, total);
        this.done = 0;
    }

    /** 由工作线程调用。 */
    public void step(String text) {
        done++;
        steps++;
        final int value = Math.min(100, (int) Math.round(done * 100.0 / total));
        final String shown = text + "（" + done + "/" + total + "）";
        SwingUtilities.invokeLater(() -> {
            bar.setValue(value);
            label.setText(shown);
        });
    }

    public void requestCancel() {
        cancelled = true;
        cancel.setEnabled(false);
        SwingUtilities.invokeLater(() -> label.setText("正在取消…"));
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isFinished() {
        return done >= total;
    }

    /** 事件循环真的转起来的证据：主线程被卡死时这里也会停，光"跑完了"不算。 */
    public int steps() {
        return steps;
    }

    public void open() {
        setVisible(true);
    }

    public void close() {
        setVisible(false);
        dispose();
    }
}
