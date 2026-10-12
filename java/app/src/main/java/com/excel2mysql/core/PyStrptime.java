package com.excel2mysql.core;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * datetime.strptime 的宽松语义复刻。
 *
 * 不能直接用 DateTimeFormatter：CPython 的 %Y 是恰好 4 位数字，%m/%d/%H/%M/%S 允许不补零
 * 且有各自的取值范围写法（%d 还接受 " 9" 这种前导空格写法），格式里的空格匹配 \s+；
 * 整串必须被消耗完。这些差异会直接改变一列被判成 DATE 还是 VARCHAR。
 */
public final class PyStrptime {

    /** infer.py 与 mysql_io.py 用的是同一组格式，顺序也不能动：先命中先赢。 */
    public static final String[] DATETIME_FORMATS = {
            "%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%Y-%m-%d %H:%M",
            "%Y-%m-%dT%H:%M:%S", "%Y%m%d%H%M%S"};
    public static final String[] DATE_FORMATS = {"%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d", "%Y%m%d"};

    private PyStrptime() {
    }

    /** 命中返回解析出的时间，未命中返回 null。纯日期格式返回当天 00:00:00。 */
    public static LocalDateTime parse(String s, String fmt) {
        Matcher m = compile(fmt).matcher(s);
        if (!m.matches()) {
            return null;
        }
        try {
            int year = Integer.parseInt(group(m, "Y"));
            int month = Integer.parseInt(group(m, "m"));
            int day = Integer.parseInt(trimSpace(group(m, "d")));
            int hour = group(m, "H") == null ? 0 : Integer.parseInt(group(m, "H"));
            int minute = group(m, "M") == null ? 0 : Integer.parseInt(group(m, "M"));
            int second = group(m, "S") == null ? 0 : Integer.parseInt(group(m, "S"));
            if (year < 1) {
                return null;
            }
            LocalDate d = LocalDate.of(year, month, day);
            return LocalDateTime.of(d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                    hour, minute, second);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    public static boolean matches(String s, String[] formats) {
        for (String fmt : formats) {
            if (parse(s, fmt) != null) {
                return true;
            }
        }
        return false;
    }

    /** Python 的命名分组不存在时返回 None；Java 的 Matcher.group(name) 会直接抛异常。 */
    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trimSpace(String s) {        return s == null ? null : s.trim();
    }

    /** 逐 token 拼出与 CPython _strptime 等价的正则。 */
    private static Pattern compile(String fmt) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < fmt.length()) {
            char c = fmt.charAt(i);
            if (c == '%' && i + 1 < fmt.length()) {
                char t = fmt.charAt(++i);
                switch (t) {
                    case 'Y':
                        re.append("(?<Y>\\d{4})");
                        break;
                    case 'm':
                        re.append("(?<m>1[0-2]|0[1-9]|[1-9])");
                        break;
                    case 'd':
                        re.append("(?<d>3[0-1]|[1-2]\\d|0\\d|[1-9]| [1-9])");
                        break;
                    case 'H':
                        re.append("(?<H>2[0-3]|[0-1]\\d|\\d)");
                        break;
                    case 'M':
                        re.append("(?<M>[0-5]\\d|\\d)");
                        break;
                    case 'S':
                        re.append("(?<S>6[0-1]|[0-5]\\d|\\d)");
                        break;
                    default:
                        throw new IllegalArgumentException("未支持的 strptime 指令: %" + t);
                }
                i++;
                continue;
            }
            if (c == ' ') {
                re.append("\\s+");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return Pattern.compile(re.toString());
    }
}
