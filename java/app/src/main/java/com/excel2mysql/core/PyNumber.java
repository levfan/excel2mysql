package com.excel2mysql.core;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * CPython 数字字符串语义的复刻：float 的 repr，以及 Decimal 的 (整数位, 小数位) 形状。
 *
 * 为什么要自己写 repr：推断里 _num_shape 对 float 用的是 repr(v)，
 * 而 Java 的 Double.toString 与 Python 的 repr 是**两套最短形选择规则**——
 * 1e25 在 Java 是 "1.0E25"（有效数字 2 位），Python 是 "1e+25"（1 位），
 * 直接拿 Double.toString 去数数字就会把整数位数算多，给出更宽的 DECIMAL。
 */
public final class PyNumber {

    private PyNumber() {
    }

    /** 对齐 repr(float)：定点与科学的切换点是 decpt &gt; 16 或 decpt &lt; -4。 */
    public static String repr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (d == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (d == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        boolean neg = d < 0.0 || (d == 0.0 && Double.doubleToLongBits(d)
                == Double.doubleToLongBits(-0.0));
        String body = reprAbs(Math.abs(d));
        return neg ? "-" + body : body;
    }

    private static String reprAbs(double d) {
        BigDecimal b = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = b.unscaledValue().abs().toString();
        if (digits.equals("0")) {
            return "0.0";
        }
        int n = digits.length();
        int decpt = n - b.scale();
        if (decpt > 16 || decpt < -4) {
            StringBuilder sb = new StringBuilder();
            sb.append(digits.charAt(0));
            if (n > 1) {
                sb.append('.').append(digits.substring(1));
            }
            int e = decpt - 1;
            sb.append('e').append(e < 0 ? '-' : '+').append(twoDigits(Math.abs(e)));
            return sb.toString();
        }
        if (decpt <= 0) {
            return "0." + zeros(-decpt) + digits;
        }
        if (decpt >= n) {
            return digits + zeros(decpt - n) + ".0";
        }
        return digits.substring(0, decpt) + "." + digits.substring(decpt);
    }

    private static String twoDigits(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String zeros(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append('0');
        }
        return sb.toString();
    }

    /**
     * 对齐 _num_shape：返回 (整数位数, 小数位数)，非数值返回 null。
     *
     * n 是**字面写出来的全部数字个数（含末尾零）**，exp = -scale。
     * Decimal('100.0') 的 digits 是 (1,0,0,0) 而不是 (1,)，所以这里绝不能
     * stripTrailingZeros，否则小数位会凭空消失。
     */
    public static int[] shape(Object v) {
        if (v == null || v instanceof Boolean) {
            return null;
        }
        String s;
        if (v instanceof Double) {
            s = repr(((Double) v).doubleValue());
        } else if (v instanceof BigInteger) {
            s = v.toString();
        } else if (v instanceof Long || v instanceof Integer) {
            s = v.toString();
        } else if (v instanceof String) {
            s = Names.pyStrip((String) v).replace(",", "").replace("，", "");
        } else {
            return null;
        }
        if (s.isEmpty()) {
            return null;
        }
        BigDecimal d;
        try {
            d = new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
        int n = d.unscaledValue().abs().toString().length();
        int exp = -d.scale();
        int intDigits = Math.max(n + exp, 1);
        return new int[]{intDigits, Math.max(exp < 0 ? -exp : 0, 0)};
    }

    /** 对齐 _looks_int：bool 与 None 算真，空白串算真，其余按能否当整数读。 */
    public static boolean looksInt(Object v) {
        if (v == null || v instanceof Boolean) {
            return true;
        }
        if (v instanceof Double) {
            double d = ((Double) v).doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d) && Math.rint(d) == d;
        }
        if (v instanceof BigInteger || v instanceof Long || v instanceof Integer) {
            return true;
        }
        if (!(v instanceof String)) {
            return false;
        }
        String s = Names.pyStrip((String) v);
        if (s.isEmpty()) {
            return true;
        }
        return INTEGER.literalMatch(s.replace(",", "").replace("，", ""));
    }

    /** 对齐 _looks_decimal：bool 算假（小数列不能由布尔撑起），其余按能否当浮点读。 */
    public static boolean looksDecimal(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Boolean) {
            return false;
        }
        if (v instanceof Double || v instanceof BigInteger
                || v instanceof Long || v instanceof Integer) {
            return true;
        }
        if (!(v instanceof String)) {
            return false;
        }
        String s = Names.pyStrip((String) v);
        if (s.isEmpty()) {
            return true;
        }
        try {
            Double.parseDouble(s.replace(",", "").replace("，", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Python 的 int(s)：只接受可选符号加纯数字。 */
    private static final class INTEGER {
        static boolean literalMatch(String s) {
            int i = 0;
            if (s.isEmpty()) {
                return false;
            }
            if (s.charAt(0) == '+' || s.charAt(0) == '-') {
                i = 1;
            }
            if (i >= s.length()) {
                return false;
            }
            for (; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
