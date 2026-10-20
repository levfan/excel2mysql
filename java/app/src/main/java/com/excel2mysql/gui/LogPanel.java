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
 * 带颜色的日志面板：错误整条标红加粗，行数封顶不无限涨。
 * 对应 Python 侧 main.py:72-130 的 append_log。
 */
public final class LogPanel extends javax.swing.JTextPane {

    private static final Color ERROR_COLOR = new Color(0xc6, 0x28, 0x28);
    private static final int MAX_LINES = 2000;
    private static final int TRIM_TO = 1500;
    /** 12 号在 1080p 上中文只有米粒大，现场反馈"基本看不清"；三处日志共用这一档。 */
    private static final int FONT_SIZE = 14;

    private final Deque<Entry> entries = new ArrayDeque<Entry>();

    private static final class Entry {
        private final String text;
        private final boolean error;

        private Entry(String text, boolean error) {
            this.text = text;
            this.error = error;
        }
    }

    public LogPanel() {
        setEditable(false);
        // 用逻辑等宽字体而不是 Consolas：前者的中文回退正好占两格，结果区的表格才对得齐；
        // Consolas 的回退字形只有一格多，混排时整张表会歪。
        setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, FONT_SIZE));
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
        synchronized (entries) {
            for (String one : text.split("\r?\n")) {
                entries.addLast(new Entry(one, isError));
            }
            // 上限在这里裁，不放到 render() 里：
            // 调用方紧接着读 lineCount() 就会拿到还没收口的行数，自检会假红
            while (entries.size() > MAX_LINES) {
                entries.removeFirst();
            }
        }
        SwingUtilities.invokeLater(this::render);
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
            if (e.error) {
                StyleConstants.setForeground(attrs, ERROR_COLOR);
                StyleConstants.setBold(attrs, true);
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
     * 是可断言的事实；色值由 isError 决定，两者绑在一起就不会出现"报红没标红"。
     */
    public boolean isErrorLine(String needle) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (e.text.contains(needle)) {
                    return e.error;
                }
            }
        }
        return false;
    }

    public int maxLines() {
        return MAX_LINES;
    }
}
