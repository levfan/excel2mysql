package com.excel2mysql.core;

import java.util.regex.Pattern;

/**
 * core/naming.py 的前缀规则。表定义线和 SQL 脚本文本线共用同一套，
 * 分成两份实现的话同一个表名会在两个页签里改出两种结果。
 */
public final class Prefix {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]+$");

    private Prefix() {
    }

    /**
     * 清掉空白与反引号/反斜杠，再去掉末尾下划线。
     * 分隔用的下划线沿用表名自己那一个，所以填 change 与 change_ 效果相同。
     */
    public static String clean(Object text) {
        String raw = text == null ? "" : String.valueOf(text);
        String cleaned = raw.replace("`", "").replace("\\", "");
        String stripped = Names.pyStrip(cleaned);
        int end = stripped.length();
        while (end > 0 && stripped.charAt(end - 1) == '_') {
            end--;
        }
        return Names.pyStrip(stripped.substring(0, end));
    }

    /** 空串视为不合法，由调用方决定怎么提示。 */
    public static boolean isValid(String text) {
        return text != null && !text.isEmpty() && VALID.matcher(text).matches();
    }

    /**
     * 换掉第一个下划线之前那一段。返回 null 表示不改：
     * 名字里没有下划线（整名是唯一一段，换掉等于改名不是换前缀），
     * 或首段为空（_tmp 那种是畸形名不是前缀）。
     */
    public static String swapFirstSegment(Object name, String newHead) {
        String s = name == null ? "" : String.valueOf(name);
        int at = s.indexOf('_');
        if (at <= 0) {
            return null;
        }
        String candidate = newHead + s.substring(at);
        return candidate.equals(s) ? null : candidate;
    }
}
