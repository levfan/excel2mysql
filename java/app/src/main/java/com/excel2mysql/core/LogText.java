package com.excel2mysql.core;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志文案的公共零件：报错展开、语句缩进、几句反复使用的提示语。
 * 对应 main.py:72-130，Excel 页与 SQL 页共用，两条页签不会各说各话。
 */
public final class LogText {

    /** 出错语句全文最长展示，超出截断，避免超大 INSERT 刷爆日志。 */
    public static final int MAX_LOG_STATEMENT = 2000;

    /** Java 源里的换行转义容易被工具链吃掉，统一走这个常量。 */
    public static final String NL = String.valueOf((char) 10);

    /** 连接区在两个页签上方常驻，指引说"上方"而不是某个页签。 */
    public static final String NO_DB_HINT =
            "未选择目标数据库：请先在上方填写连接信息并点「连接并加载库」，再选择目标库";
    public static final String NO_FILE_HINT = "请先添加 Excel 文件或 ZIP 压缩包。";
    public static final String NO_SQL_HINT = "请先添加 SQL 脚本文件。";

    public static final String ROLLBACK_NOTE =
            "注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交，不会随事务回滚；"
            + "回滚仅对其后的数据操作生效。";

    /** 任务线程里冒出未预期异常时的固定文案：日志与弹窗同源，两处说的是同一件事。 */
    public static String crashLog(String msg) {
        return "[任务异常] " + msg + NL + "    本次操作未完成，请查看日志后重试。";
    }

    public static String crashDialog(String msg) {
        return msg + NL + "本次操作未完成，详见日志。";
    }

    private LogText() {
    }

    /**
     * 把异常展开成「异常类型 + MySQL 错误码 + 完整报错文本」。
     * SQLException 的 getMessage() 本身已经带 "[42000] ..." 这类前缀，
     * 但错误码在 getErrorCode() 里，分开取才和 Python 侧的元组展开对上。
     */
    public static String describe(Throwable e) {
        if (e instanceof SQLException) {
            SQLException se = (SQLException) e;
            // 连不上库这类驱动自己抛的异常 vendor code 是 0，写出来只会让人以为
            // MySQL 回了个 0 号错误；没有码就只报类型与原文
            if (se.getErrorCode() == 0) {
                return se.getClass().getSimpleName() + "：" + se.getMessage();
            }
            return se.getClass().getSimpleName() + "（MySQL 错误码 " + se.getErrorCode()
                    + "）：" + se.getMessage();
        }
        return e.getClass().getSimpleName() + "：" + e.getMessage();
    }

    /** 出错语句按原样缩进展示，过长时截断并标注原始长度。 */
    public static String formatStatement(String statement) {
        String body = statement == null ? "" : statement.trim();
        int raw = statement == null ? 0 : statement.length();
        if (body.length() > MAX_LOG_STATEMENT) {
            body = body.substring(0, MAX_LOG_STATEMENT) + NL
                    + "…（语句过长已截断，原文共 " + raw + " 字符）";
        }
        return indent(body, "        ");
    }

    /** 把多行详情统一缩进，避免与首行混在一起看不出层次。 */
    public static String indentDetail(String text) {
        return indent(text == null ? "" : text, "    ");
    }

    private static String indent(String text, String pad) {
        String[] parts = lines(text);
        StringBuilder sb = new StringBuilder();
        for (String line : parts) {
            if (sb.length() > 0) {
                sb.append(NL);
            }
            sb.append(pad).append(line);
        }
        return sb.toString();
    }

    /** Python str.splitlines() 的等价：行尾那个换行不额外产生一个空元素。 */
    private static String[] lines(String text) {
        String t = text.replace(String.valueOf((char) 13) + NL, NL);
        if (t.endsWith(NL)) {
            t = t.substring(0, t.length() - 1);
        }
        return t.split(NL, -1);
    }

    // ---------------------------------------------------------------------
    // 日志一行的形状：[时:分:秒] + 符号 + 大写分级标签，正文分段用 |
    // 与 Python 侧 core/log_text.py 同一套标签与同一张定级表：同一条日志在两条
    // 线上必须是同一个颜色同一个级别，对撞器比的是含标签的整行，写歪就当场不等。
    // ---------------------------------------------------------------------

    /** 只到秒：整场会话都在同一个窗口里，日期不进每一行。 */
    public static final String TIME_FORMAT = "HH:mm:ss";

    private static final Map<String, String> MARK = new LinkedHashMap<String, String>();
    private static final Map<String, String> TAG_LEVEL = new LinkedHashMap<String, String>();

    static {
        MARK.put("ok", "+");
        MARK.put("info", "-");
        MARK.put("warn", "!");
        MARK.put("error", "x");
        String[][] table = {
                {"已解析", "ok"}, {"已加载", "ok"}, {"已保存", "ok"}, {"提交", "ok"},
                {"生效", "ok"}, {"建表成功", "ok"}, {"导入完成", "ok"}, {"全部完成", "ok"},
                {"前缀完成", "ok"}, {"换前缀完成", "ok"}, {"添加结果", "ok"},
                {"跳过", "warn"}, {"重复跳过", "warn"}, {"压缩包为空", "warn"},
                {"已取消", "warn"}, {"已中止", "warn"}, {"正在中止...", "warn"},
                {"元数据", "warn"}, {"数据警告", "warn"}, {"保留", "warn"},
                {"解析失败", "error"}, {"读取失败", "error"}, {"保存失败", "error"},
                {"建表失败", "error"}, {"执行失败", "error"}, {"导入失败", "error"},
                {"连接失败", "error"}, {"压缩包失败", "error"}, {"任务异常", "error"},
                {"合并单元格", "error"},
        };
        for (String[] row : table) {
            TAG_LEVEL.put(row[0], row[1]);
        }
    }

    /** 命令行那套前缀会原样出现在搬进图形界面的文案里，先剥掉再按本级重拼，避免叠两个标签。 */
    private static final Pattern HEAD_RE =
            Pattern.compile("^(?:[+\\-!x]\\s*)?\\[(OK|INFO|WARN|ERROR)\\]\\s+",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_RE = Pattern.compile("^\\[([^\\]\\n]{1,16})\\]");

    public static String now() {
        return new SimpleDateFormat(TIME_FORMAT).format(new Date());
    }

    /** 定级优先看调用方给的错误标志，其次看已有的大写标签，最后看中文标签。 */
    public static String levelOf(String text, boolean error) {
        if (error) {
            return "error";
        }
        String head = (text == null ? "" : text).trim();
        Matcher m = HEAD_RE.matcher(head);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }
        Matcher t = TAG_RE.matcher(head);
        if (t.find()) {
            String tag = t.group(1);
            // 「添加结果」在有拒绝/失败时会带"另有 …，详见日志"，那是提醒不是报成功
            if ("添加结果".equals(tag)) {
                return head.contains("另有") ? "warn" : "ok";
            }
            String lvl = TAG_LEVEL.get(tag);
            return lvl == null ? "info" : lvl;
        }
        return "info";
    }

    /**
     * 把一条日志拼成界面上的样子。只有首行带时间戳与分级标签；
     * 已经缩进的续行（结果表格、驱动原文）原样保留——给它们打时间戳会把等宽表格顶歪。
     */
    public static String format(String text, boolean error) {
        return format(text, error, now());
    }

    public static String format(String text, boolean error, String stamp) {
        String body = (text == null ? "" : text).replace("\r\n", NL).replace("\r", NL);
        String[] parts = body.split(NL, -1);
        // 定级看整条记录，不是只看首行：「添加结果」的"另有 N 项解析失败"写在续行上，
        // 只按首行定级就会把一批有拒绝的添加报成绿色成功（与 Python 侧同一处修正）
        String level = levelOf(body, error);
        String head = parts[0];
        if (head.trim().isEmpty() || Character.isWhitespace(head.charAt(0))) {
            return body;
        }
        Matcher m = HEAD_RE.matcher(head.trim());
        String rest = m.find() ? head.trim().substring(m.end()).trim() : head.trim();
        parts[0] = "[" + stamp + "] " + MARK.get(level) + " [" + level.toUpperCase() + "] " + rest;
        List<String> out = new ArrayList<String>();
        out.add(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].trim().isEmpty() && !Character.isWhitespace(parts[i].charAt(0))) {
                out.add("    " + parts[i]);
            } else {
                out.add(parts[i]);
            }
        }
        return join(out, NL);
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
