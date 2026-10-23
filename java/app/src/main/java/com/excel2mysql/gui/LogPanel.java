package com.excel2mysql.gui;

import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 带颜色的日志面板：每条记录首行带 [时:分:秒] 与分级标签，按级别上色，行数封顶不无限涨。
 * 形状与定级都由 core/LogText.format 那一处决定（与 Python 侧 core/log_text.py 同一张表），
 * 面板只管渲染——三个页签的日志框都走这里，不会出现"这一页有钟点、那一页没有"。
 */
public final class LogPanel extends javax.swing.JTextPane {

    private static final Color ERROR_COLOR = new Color(0xc6, 0x28, 0x28);
    private static final Color WARN_COLOR = new Color(0xb2, 0x6a, 0x00);
    private static final Color OK_COLOR = new Color(0x1a, 0x7f, 0x37);
    private static final int MAX_LINES = 2000;
    private static final int TRIM_TO = 1500;
    /** 12 与 14 在 1080p 上都偏小，现场两次反馈看不清；日志这一档定在 14pt（同 Python）。
     *  2026-10-23 用户又点名"表格内容要更大、日志可以小一点"，所以这里不再是最高档：
     *  表格那一档见 gui/Tables.POINT_SIZE（18pt），两处各自写死、由对撞键核同值。 */
    static final int DEFAULT_FONT_SIZE = 14;
    /**
     * 日志框至少看得见 4 行它自己的字。按"行数"而不是像素约定：
     * 同一档字号在 Qt 与 Swing 里量出来的行高本就不同，写同一个像素数
     * 会变成"两边都满足同一个数、实际看到的行数不一样"。跨线比的是这个 4。
     * 行数不能给多：主窗口自身最小高就有 717px，按 8 行给会顶到 847px，
     * 1024x768 的现场机与"整窗落在可用区内"那条断言都撑不住。
     */
    public static final int LOG_MIN_LINES = 4;

    private final Deque<Entry> entries = new ArrayDeque<Entry>();

    private static final class Entry {
        private final String text;
        private final String level;

        private Entry(String text, String level) {
            this.text = text;
            this.level = level;
        }
    }

    public LogPanel() {
        this(DEFAULT_FONT_SIZE);
    }

    /** fontSize 是 Python 的 pt 值；Swing 这边按屏幕 DPI 折成物理像素。 */
    public LogPanel(int fontSize) {
        setEditable(false);
        // 用逻辑等宽字体而不是 Consolas：前者的中文回退正好占两格，结果区的表格才对得齐；
        // Consolas 的回退字形只有一格多，混排时整张表会歪。
        setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN,
                UiScale.fontPt(fontSize)));
        // 宽度 0 = 不约束宽度，只管高度；行数乘的是这支字体自己的行高
        setMinimumSize(new java.awt.Dimension(0,
                LOG_MIN_LINES * getFontMetrics(getFont()).getHeight()));
        setBackground(new Color(0xfb, 0xfb, 0xfb));
    }

    public void log(String text) {
        append(text, false);
    }

    public void error(String text) {
        append(text, true);
    }

    /**
     * 工作线程可以直接调：条目在这一句里就收下，控件更新排到事件分发线程。
     *
     * `entries` 会被两类线程碰到——调用线程在这儿加行，事件分发线程在 render() 里遍历它。
     * 没有同一把锁就会在生产里炸出 `ConcurrentModificationException`（遍历到一半被加了行），
     * 那个异常会把当前这个事件连同日志刷新一起带走：界面看着就是"导入跑到一半日志不再动了"。
     * 收行留在调用线程做、只有渲染排队，是为了让自检这类调用方能"写完立刻读到"，
     * 这两件事靠同一把锁同时保住。
     */
    public void append(final String text, final boolean isError) {
        final String level = com.excel2mysql.core.LogText.levelOf(text, isError);
        final String formatted = com.excel2mysql.core.LogText.format(text, isError);
        synchronized (entries) {
            for (String one : formatted.split("\r?\n")) {
                entries.addLast(new Entry(one, level));
            }
            // 上限在这里裁，不放到 render() 里：
            // 调用方紧接着读 lineCount() 就会拿到还没收口的行数，自检会假红
            while (entries.size() > MAX_LINES) {
                entries.removeFirst();
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    /** info 不上色：一屏里绝大多数行都是它，全染上颜色等于全没染。 */
    private static Color colorOf(String level) {
        if ("error".equals(level)) {
            return ERROR_COLOR;
        }
        if ("warn".equals(level)) {
            return WARN_COLOR;
        }
        if ("ok".equals(level)) {
            return OK_COLOR;
        }
        return null;
    }

    private void render() {
        StyledDocument doc = getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            return;
        }
        // 遍历期间不许任何人再动 entries，所以先按锁内快照取出来再往文档里写
        List<Entry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<Entry>(entries);
        }
        for (Entry e : snapshot) {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            Color color = colorOf(e.level);
            if (color != null) {
                StyleConstants.setForeground(attrs, color);
                StyleConstants.setBold(attrs, "warn".equals(e.level)
                        || "error".equals(e.level));
            }
            try {
                doc.insertString(doc.getLength(), e.text + "\n", attrs);
            } catch (BadLocationException ignored) {
                return;
            }
        }
        setCaretPosition(doc.getLength());
    }

    /** 超出上限后一次性折半裁，避免每加一行就重排一次。 */
    public void trimIfNeeded() {
        boolean trimmed = false;
        synchronized (entries) {
            if (entries.size() > MAX_LINES) {
                while (entries.size() > TRIM_TO) {
                    entries.removeFirst();
                }
                trimmed = true;
            }
        }
        if (trimmed) {
            render();
        }
    }

    public void clearLog() {
        synchronized (entries) {
            entries.clear();
        }
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = getStyledDocument();
            try {
                doc.remove(0, doc.getLength());
            } catch (BadLocationException ignored) {
                return;
            }
        });
    }

    public int lineCount() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** 已收到的日志全文。render() 是排队的，控件文档可能还没更新，所以自检读这一份。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (entries) {
            for (Entry e : entries) {
                if (sb.length() > 0) {
                    sb.append((char) 10);
                }
                sb.append(e.text);
            }
        }
        return sb.toString();
    }

    public boolean contains(String needle) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (e.text.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 找到该文本时是不是错误级。日志的红色只有像素能证明，但"这条被标成错误"
     * 是可断言的事实；色值由级别决定，两者绑在一起就不会出现"报红没标红"。
     */
    public boolean isErrorLine(String needle) {
        return "error".equals(levelOfLine(needle));
    }

    /** 这一句被定成了哪一级（ok/info/warn/error），没找到返回 null。 */
    public String levelOfLine(String needle) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (e.text.contains(needle)) {
                    return e.level;
                }
            }
        }
        return null;
    }

    public int maxLines() {
        return MAX_LINES;
    }
}
