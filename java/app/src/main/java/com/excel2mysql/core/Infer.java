package com.excel2mysql.core;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * core/infer.py 的类型推断与主键选择。值模型用 Object 表达 Python 的类型：
 * BigInteger / Double / String / Boolean / LocalDate / LocalDateTime / null。
 */
public final class Infer {

    public static final int VARCHAR_MAX_CHARS = 16383;
    private static final int[] VARCHAR_TIERS = {64, 96, 128, 192, 255, 384, 512, 768,
            1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16383};
    private static final int ROW_BYTE_BUDGET = 60000;
    private static final int COL_ROW_COST = 16;
    private static final int MONEY_SCALE = 2;
    private static final int MONEY_PRECISION = 16;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final Pattern MONEY_EN = Pattern.compile(
            "(?:^|_)(?:amount|amt|money|price|cost|fee|fees|salary|wage|wages|income|"
          + "expense|expenses|balance|budget|revenue|profit|payment|payable|receivable|"
          + "charge|charges|fund|funds|tax|interest|capital)(?:$|_)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE_EN = Pattern.compile(
            "(?:rate|ratio|percent|percentage|pct|share)", Pattern.CASE_INSENSITIVE);
    private static final String[] MONEY_CN = {
            "金额", "总额", "总价", "单价", "均价", "价格", "价目", "报价", "成交价", "结算价",
            "基准价", "中标价", "投标价", "采购价", "批发价", "零售价", "售价", "进价",
            "运价", "电价", "水价", "热价", "气价", "油价", "票价", "话费", "房价", "地价",
            "费用", "费额", "成本", "余额", "余款", "工资", "薪金", "薪资", "薪酬", "补贴",
            "津贴", "奖金", "收入", "支出", "开销", "付款", "收款", "支付", "缴费", "付费",
            "预算", "决算", "清算", "结算", "税额", "税金", "税费", "利息", "保费",
            "股金", "市值", "估值", "净产值", "总产值", "销售额", "营业额", "零售额", "进口额",
            "出口额", "利润", "亏损", "毛利", "净利", "欠款", "贷款", "存款", "融资", "投资额",
            "资金", "额度", "补偿", "赔偿", "保证金", "押金", "定金", "预付款", "进度款",
            "工程款", "材料费", "人工费", "运输费", "服务费", "咨询费", "管理费", "仓储费"};
    private static final String[] RATE_CN = {"率", "比例", "百分比", "占比", "增速", "幅度"};

    private static final String[] PK_KEYWORDS = {
            "编号", "序号", "代码", "编码", "标识", "卡号", "单号", "主键", "唯一"};
    private static final Pattern PK_ID_FIELD = Pattern.compile(
            "(?:^|_)(id|uid|uuid|guid)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PK_ID_COMMENT = Pattern.compile(
            "\\bid\\b|uuid|guid|唯一标识|主键", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEN_IN_TYPE = Pattern.compile("\\((\\d+)\\)");
    private static final Pattern LEADING_ZERO = Pattern.compile("^-?0\\d+$");

    private final Names names;

    public Infer(Names names) {
        this.names = names;
    }

    /** 一列的推断结果，字段名与 Python 的 dict 键一致。 */
    public static final class Column {
        public String field;
        public String comment;
        public String type;
        public long maxLen;
        public String typeNote;
        public Integer varcharN;
        public boolean primaryKey;
        public Boolean nullable;

        /** 结构弹窗用的副本：校验通过前先在副本上改，避免无效主键污染表定义。 */
        public Column copied() {
            Column n = new Column();
            n.field = field;
            n.comment = comment;
            n.type = type;
            n.maxLen = maxLen;
            n.typeNote = typeNote;
            n.varcharN = varcharN;
            n.primaryKey = primaryKey;
            n.nullable = nullable;
            return n;
        }

        public String toJsonish() {
            return field + "|" + type + "|" + maxLen;
        }
    }

    public static boolean isMoney(String comment, String field) {
        String c = comment == null ? "" : comment;
        String fl = field == null ? "" : field;
        for (String k : RATE_CN) {
            if (c.contains(k)) {
                return false;
            }
        }
        if (RATE_EN.matcher(c).find() || RATE_EN.matcher(fl).find()) {
            return false;
        }
        for (String k : MONEY_CN) {
            if (c.contains(k)) {
                return true;
            }
        }
        return MONEY_EN.matcher(fl).find();
    }

    public static String decimalType(int intDigits, int scale, int floorInt, boolean money) {
        if (money) {
            scale = Math.max(scale, MONEY_SCALE);
            intDigits = Math.max(intDigits, MONEY_PRECISION - MONEY_SCALE);
        } else {
            intDigits = Math.max(intDigits + 2, floorInt);
        }
        intDigits = Math.max(intDigits, 1);
        scale = Math.max(scale, 0);
        int precision = Math.min(intDigits + scale, 30);
        intDigits = precision - scale;
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    public static int varcharTier(int needed) {
        for (int tier : VARCHAR_TIERS) {
            if (tier >= needed) {
                return tier;
            }
        }
        return VARCHAR_MAX_CHARS;
    }

    /** 对齐 Python 的 str(v)：float 走 repr，日期走 isoformat，布尔是 True/False。 */
    public static String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Double) {
            return PyNumber.repr(((Double) v).doubleValue());
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "True" : "False";
        }
        if (v instanceof LocalDateTime) {
            LocalDateTime dt = (LocalDateTime) v;
            return isoDateTime(dt);
        }
        if (v instanceof LocalDate) {
            return ((LocalDate) v).toString();
        }
        return v.toString();
    }

    /** Python datetime 的 str()：秒为 0 也照写，微秒为 0 时不出现。 */
    private static String isoDateTime(LocalDateTime dt) {
        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    private static boolean isBlank(Object v) {
        return v instanceof String && Names.pyStrip((String) v).isEmpty();
    }

    private static boolean isNullish(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Double) {
            return Double.isNaN(((Double) v).doubleValue());
        }
        return false;
    }

    /** 对齐 _has_leading_zero：纯整数字符串且带前导零。 */
    public static boolean hasLeadingZero(Object v) {
        if (!(v instanceof String)) {
            return false;
        }
        String t = Names.pyStrip((String) v).replace(",", "").replace("，", "");
        return LEADING_ZERO.matcher(t).matches();
    }

    private static boolean looksDatetime(Object v) {
        if (isNullish(v) || isBlank(v)) {
            return true;
        }
        if (v instanceof LocalDateTime || v instanceof LocalDate) {
            return true;
        }
        if (!(v instanceof String)) {
            return false;
        }
        return PyStrptime.matches(Names.pyStrip((String) v), PyStrptime.DATETIME_FORMATS);
    }

    private static boolean looksDate(Object v) {
        if (isNullish(v) || isBlank(v)) {
            return true;
        }
        if (v instanceof LocalDate) {
            return true;
        }
        if (v instanceof LocalDateTime) {
            return false;
        }
        if (!(v instanceof String)) {
            return false;
        }
        return PyStrptime.matches(Names.pyStrip((String) v), PyStrptime.DATE_FORMATS);
    }

    /**
     * 全量扫描每列 -> 字段定义列表。
     * 决策顺序 日期时间 &gt; 金额 &gt; 整数 &gt; 日期 &gt; 小数 &gt; 文本，与 Python 侧一字不差；
     * 最后再按 utf8mb4 的 4n 行内字节预算给 VARCHAR 分配长度，装不下的退成 TEXT。
     */
    public List<Column> inferColumns(List<Object> headers, List<List<Object>> rows) {
        List<Column> cols = new ArrayList<Column>();
        Map<String, Integer> seen = Names.newDedupState();
        for (int i = 0; i < headers.size(); i++) {
            String h = pyStr(headers.get(i));
            Column col = new Column();
            col.comment = h;
            col.field = Names.dedupField(seen, names.colToField(h));

            long maxLen = 0;
            boolean hasValue = false;
            boolean allInt = true, allDec = true, allDt = true, allDa = true;
            for (List<Object> row : rows) {
                Object v = i < row.size() ? row.get(i) : null;
                if (isNullish(v)) {
                    continue;
                }
                if (isBlank(v)) {
                    continue;
                }
                hasValue = true;
                maxLen = Math.max(maxLen, pyStr(v).getBytes(UTF8).length);
                if (allInt && !(PyNumber.looksInt(v) && !(v instanceof String && hasLeadingZero(v)))) {
                    allInt = false;
                }
                if (allDec && !(PyNumber.looksDecimal(v) && !(v instanceof String && hasLeadingZero(v)))) {
                    allDec = false;
                }
                if (allDt && !looksDatetime(v)) {
                    allDt = false;
                }
                if (allDa && !looksDate(v)) {
                    allDa = false;
                }
            }

            int obsInt = 0;
            int obsScale = 0;
            for (List<Object> row : rows) {
                Object v = i < row.size() ? row.get(i) : null;
                int[] shape = PyNumber.shape(v);
                if (shape != null) {
                    obsInt = Math.max(obsInt, shape[0]);
                    obsScale = Math.max(obsScale, shape[1]);
                }
            }

            boolean money = isMoney(h, col.field);
            col.maxLen = maxLen;
            if (hasValue && allDt) {
                col.type = "DATETIME";
            } else if (hasValue && money && (allInt || allDec) && !allDa) {
                col.type = decimalType(obsInt, obsScale, 4, true);
                if (allInt) {
                    col.typeNote = "表头「" + h + "」含金额语义，样本值虽为整数，仍按 "
                            + col.type + " 而非整数类型";
                }
            } else if (hasValue && allInt) {
                col.type = intOverflow(rows, i) ? "BIGINT" : "INT";
            } else if (hasValue && allDa) {
                col.type = "DATE";
            } else if (hasValue && allDec) {
                col.type = decimalType(obsInt, obsScale, 4, money);
            } else {
                long headroom = Math.max(16, maxLen / 5);
                long needed = maxLen + headroom;
                if (needed <= VARCHAR_MAX_CHARS) {
                    col.type = null;
                    col.varcharN = Integer.valueOf(varcharTier((int) needed));
                } else {
                    col.type = needed <= 65535 ? "TEXT" : "MEDIUMTEXT";
                }
            }
            cols.add(col);
        }

        int remaining = ROW_BYTE_BUDGET;
        for (Column c : cols) {
            if (c.varcharN == null) {
                remaining -= COL_ROW_COST;
                continue;
            }
            int cost = 4 * c.varcharN.intValue();
            if (cost <= remaining) {
                remaining -= cost;
                c.type = "VARCHAR(" + c.varcharN.intValue() + ")";
            } else {
                c.type = "TEXT";
            }
            c.varcharN = null;
        }
        return cols;
    }

    /** 对齐 Python 的溢出探测：逐值取 int(str(v))，超过 INT 范围就换 BIGINT。 */
    private static boolean intOverflow(List<List<Object>> rows, int i) {
        for (List<Object> row : rows) {
            Object v = i < row.size() ? row.get(i) : null;
            if (isNullish(v)) {
                continue;
            }
            String s;
            if (v instanceof String) {
                s = Names.pyStrip((String) v).replace(",", "").replace("，", "");
            } else {
                s = pyStr(v);
            }
            try {
                BigInteger bi = new BigInteger(s.trim());
                if (bi.abs().compareTo(BigInteger.valueOf(2147483647L)) > 0) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Python 那边 int() 失败是 except pass，不因此判溢出
            }
        }
        return false;
    }

    public static boolean pkTypeOk(String typ) {
        String t = typ.toUpperCase();
        if (t.startsWith("INT") || t.startsWith("BIGINT")) {
            return true;
        }
        Matcher m = LEN_IN_TYPE.matcher(t);
        return (t.startsWith("VARCHAR") || t.startsWith("CHAR")) && m.find()
                && Integer.parseInt(m.group(1)) <= 255;
    }

    /** 主键查重键：数值 1 与 1.0 视为同一个值，和 MySQL 的数值唯一性一致。 */
    public static String pkValueKey(Object v) {
        if (v instanceof Boolean) {
            return String.valueOf(((Boolean) v) ? 1 : 0);
        }
        if (v instanceof Double) {
            return PyNumber.repr(((Double) v).doubleValue());
        }
        if (v instanceof BigInteger || v instanceof Long || v instanceof Integer) {
            return PyNumber.repr(((Number) v).doubleValue());
        }
        return Names.pyStrip(String.valueOf(v));
    }

    public String[] selectPrimaryKey(List<Object> headers, List<List<Object>> rows,
                                     List<Column> columns) {
        if (rows.isEmpty()) {
            return new String[]{null, "没有数据行，不设主键"};
        }
        String bestField = null;
        String bestReason = null;
        long bestKey = Long.MIN_VALUE;
        int bestScore = 0;
        long bestLen = 0;
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            String typ = c.type == null ? "" : c.type.toUpperCase();
            if (!pkTypeOk(typ) || !uniqueNonNull(rows, i)) {
                continue;
            }
            String field = String.valueOf(c.field).toLowerCase();
            String comment = c.comment == null ? "" : c.comment;
            int score = 0;
            List<String> reasons = new ArrayList<String>();
            if (PK_ID_COMMENT.matcher(comment).find() || PK_ID_FIELD.matcher(field).find()) {
                score += 4;
                reasons.add("ID/标识类命名");
            }
            for (String k : PK_KEYWORDS) {
                if (comment.contains(k)) {
                    score += 3;
                    reasons.add("注释含编号/代码类关键词");
                    break;
                }
            }
            if (typ.startsWith("INT") || typ.startsWith("BIGINT")) {
                score += 2;
                reasons.add("整数类型");
            } else {
                Matcher m = LEN_IN_TYPE.matcher(typ);
                if (m.find() && Integer.parseInt(m.group(1)) <= 64) {
                    score += 1;
                    reasons.add("短字符类型");
                }
            }
            if (score < 3) {
                continue;
            }
            long len = c.maxLen;
            // 同分取字节长度更短者，再取靠前的列：把三元组压成一个可比较的键
            long key = ((long) score << 40) | ((0xFFFFFL - Math.min(len, 0xFFFFFL)) << 20)
                    | (0xFFFFFL - Math.min(i, 0xFFFFFL));
            if (bestField == null || key > bestKey) {
                bestKey = key;
                bestField = c.field;
                bestScore = score;
                bestLen = len;
                bestReason = join(reasons, "、");
            }
        }
        if (bestField == null) {
            return new String[]{null, "没有命名与数据都合适的字段（需非空、唯一且语义明确）"};
        }
        return new String[]{bestField, bestReason + "，全列非空且唯一"};
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean uniqueNonNull(List<List<Object>> rows, int i) {
        Set<String> seen = new LinkedHashSet<String>();
        for (List<Object> row : rows) {
            Object v = i < row.size() ? row.get(i) : null;
            if (isNullish(v) || isBlank(v)) {
                return false;
            }
            if (!seen.add(pkValueKey(v))) {
                return false;
            }
        }
        return true;
    }
}
