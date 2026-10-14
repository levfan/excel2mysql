package com.excel2mysql.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 模态进度框：耗时操作在子线程跑，界面靠这个框报"还在动"，并且能中途取消。
 * 对应 main.py:200-265。
 *
 * 总项数必须一次算准再喂，边跑边加会让进度条倒退或卡在半途；
 * 单个几十万行的文件按 1 项计的话进度条五秒纹丝不动，所以按阶段计。
 * 所有控件更新一律排到事件分发线程——工作线程直接碰控件在 Swing 里不是"有时能跑"，是数据竞争。
 */
public final class ProgressDialog extends JDialog {

    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JLabel label = new JLabel();
    private final JButton cancel = new JButton("取消");
    private int total = 1;
    private int done;
    private boolean cancelled;
    private int steps;
    private int target;
    private int shown;
    private String plainText = "准备中…";
    private String cancelLabel = "取消";

    /**
     * 16ms 补间：真实推进往往一次跳十几格，直接 setValue 会让进度条一顿一顿地弹，
     * 看不出还在不在动；逼近式追赶让每次推进都能被眼睛接住。
     */
    private final javax.swing.Timer anim;

    public ProgressDialog(Frame owner, String title) {
        super(owner, title, true);
        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        bar.setStringPainted(true);
        // Qt 的 setFormat("%p%") 是"显示百分比"，Swing 的 setString 是字面文本：
        // 照抄会让进度条真的印出 "%p%" 四个字符，所以每次改值都自己写百分比
        bar.setString("0%");
        label.setForeground(new Color(0x55, 0x55, 0x55));
        // Python 在框里另写了一行加粗标题（main.py:211-214）：那个框的标题栏没有关闭按钮，
        // 任务名只靠窗口标题太弱，框体里也得有一句
        JLabel head = new JLabel(title);
        head.setFont(head.getFont().deriveFont(java.awt.Font.BOLD));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(head, BorderLayout.NORTH);
        top.add(label, BorderLayout.SOUTH);
        // 取消按钮靠右、按自身宽度：直接 add 到 SOUTH 会被 BorderLayout 拉成整行宽
        JPanel btnRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        btnRow.add(cancel);
        body.add(top, BorderLayout.NORTH);
        body.add(bar, BorderLayout.CENTER);
        body.add(btnRow, BorderLayout.SOUTH);
        setContentPane(body);
        renderLabel();
        cancel.addActionListener(e -> requestCancel());
        // Windows 上没法只去掉标题栏的关闭按钮，所以点 X 等值于取消：
        // Python 侧那个框根本没有 X，留下"框消失了任务还在后台跑"这种状态比不给 X 更糟
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestCancel();
            }
        });
        // Python 的 resize(480,140) 说的是客户区，这边把尺寸钉在内容面板上再 pack，
        // 客户区才真就是 480x140；夹屏幕与居中也走同一道
        Windows.clientSize(body, this, 480, 140);
        anim = new javax.swing.Timer(16, e -> tick());
        anim.setCoalesce(true);
    }

    public void setTotal(int total) {
        this.total = Math.max(1, total);
        this.done = 0;
        this.target = 0;
        this.shown = 0;
        this.plainText = "准备中…";
        onEdt(() -> {
            anim.stop();
            showPercent(0);
        });
    }

    /**
     * 已经在事件分发线程上就直接改控件，别的线程才排队。
     * 一律 invokeLater 的话，站在 EDT 上的调用方（自检、原地任务模式）读回来的是旧值。
     */
    private static void onEdt(Runnable update) {
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    /** 进度条上的文字要跟着数值走：Swing 没有 Qt 那种格式占位符，只能自己写。 */
    private void showPercent(int value) {
        bar.setValue(value);
        bar.setString(value + "%");
    }

    /** 由工作线程调用。 */
    public void step(String text) {
        advance(1, text);
    }

    /**
     * 一次推进若干格。解析一个文件按阶段计 5 格、被跳过的重复项也要一次补齐 5 格，
     * 否则进度条要么停在半途，要么超出总数。
     */
    public void advance(final int units, final String text) {
        // 状态在调用线程上就更新完，控件更新才排队：自检与调用方往往就站在事件分发线程上，
        // 全排进队列的话它读回来的是旧值（LogPanel 踩过同一个坑）
        done += units;
        steps += units;
        final boolean over = done >= total;
        target = over ? 100 : Math.min(100, (int) Math.round(done * 100.0 / total));
        plainText = "进度 " + Math.min(done, total) + "/" + total + " 项 · " + text;
        if (over) {
            // 收尾一定要拉满：停在 98% 会让人以为卡住了，而不是以为跑完了
            shown = 100;
        }
        onEdt(() -> {
            renderLabel();
            if (over) {
                anim.stop();
                showPercent(100);
            } else if (shown < target && !anim.isRunning()) {
                anim.start();
            }
        });
    }

    private void tick() {
        if (shown >= target) {
            anim.stop();
            return;
        }
        int step = Math.max(1, (target - shown) / 10);
        shown = Math.min(target, shown + step);
        showPercent(shown);
    }

    public void requestCancel() {
        cancelled = true;
        plainText = "正在取消…（当前项做完就停）";
        onEdt(() -> {
            cancel.setEnabled(false);
            renderLabel();
        });
    }

    /** 导入这类长任务把"取消"改成读得懂的动作名（main.py:1557）。 */
    public void setCancelText(String text) {
        cancelLabel = text;
        onEdt(() -> cancel.setText(text));
    }

    public String cancelText() {
        return cancelLabel;
    }

    /** 原始文案，不含 html 包装：自检与日志都要读这一份。 */
    public String statusText() {
        return plainText;
    }

    /** 进度条上印着的那串字：Qt 的 %p% 是格式符，Swing 得自己填，这里断的就是自己填对了。 */
    public String percentText() {
        return bar.getString();
    }

    public int percentShown() {
        return shown;
    }

    public int percentTarget() {
        return target;
    }

    /** Swing 的 JLabel 不折行，用 html 定宽换行（Python 侧是 setWordWrap(True)）。 */
    private void setPlainText(String text) {
        plainText = text;
        renderLabel();
    }

    private void renderLabel() {
        // 套两条线共用的那道折行，再把换行换成 <br>。
        // 原来那句 CSS 定宽（width: 430px）会让 JLabel 把整篇文档的宽度当成自己的
        // 首选尺寸，进度框被顶到 583 宽，比 Python 的 480 宽出一截——同一句话两边不一样高宽，
        // 而且夹屏幕时反而更容易放不下。
        String wrapped = PopupText.wrap(plainText);
        label.setText("<html><body>" + escape(wrapped).replace("\n", "<br>") + "</body></html>");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        anim.stop();
        setVisible(false);
        dispose();
    }
}
