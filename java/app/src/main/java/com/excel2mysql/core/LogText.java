package com.excel2mysql.core;

import java.sql.SQLException;

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
}
