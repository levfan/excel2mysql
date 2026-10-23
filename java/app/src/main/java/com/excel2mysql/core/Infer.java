package com.excel2mysql.core;

import java.math.BigDecimal;
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
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(2147483647L);
    private static final BigDecimal BIGINT_MAX = new BigDecimal("9223372036854775807");
    private static final int DECIMAL_MAX_PRECISION = 65;
    private static final int DECIMAL_MAX_SCALE = 30;

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

    // 空列按字段名关键字定类型（与 core/infer.py 的 _name_based_type 逐条对齐）：
    // 只在"这列一个值都没有"时参与，有值照旧走数据判定；结构定义模式人工类型不受影响
    private static final String NAME_TIME_CN = "时间";   // 含"日期时间"——先查时间才落 DATETIME
    private static final String NAME_DATE_CN = "日期";
    private static final String[] NAME_BOOL_CN = {"是否", "有无", "开关", "布尔", "标志"};
    // 显式比率/系数词先于计量词判定；裸「率」放到计量词之后再查，
    // 否则「频率」「功率」会被当成比率，而「容积率」需要留在比率这一侧
    private static final String[] NAME_RATIO_CN = {"比例", "百分比", "占比", "增速", "幅度",
            "系数", "因数", "倍数", "折扣", "权重", "比重", "容积率"};
    private static final String[] NAME_SCORE_CN = {
            "得分", "评分", "分数", "成绩", "总分", "平均分", "绩点"};
    private static final String[] NAME_COORD_CN = {"经度", "纬度", "经纬度", "坐标"};
    private static final String[] NAME_MEASURE_CN = {
            "长度", "宽度", "高度", "厚度", "深度", "距离", "里程",
            "面积", "体积", "容积", "重量", "毛重", "净重", "体重", "载重",
            "容量", "温度", "湿度", "浓度", "密度", "压力", "速度", "速率",
            "流量", "电量", "水量", "气量", "热量", "功率", "电压", "电流",
            "频率", "海拔", "高程", "水位", "雨量", "身高", "含量", "纯度",
            "直径", "半径", "周长", "角度"};
    private static final String[] NAME_INT_CN = {
            "数量", "个数", "人数", "次数", "库存", "张数", "件数", "台数", "点数", "条数",
            "户数", "笔数", "车数", "头数", "只数", "间数", "亩数", "套数", "项数", "总数",
            "积分"};
    private static final String[] NAME_AGE_CN = {
            "年龄", "工龄", "教龄", "党龄", "军龄", "年限", "岁数", "周岁"};
    private static final String[] NAME_RANK_CN = {
            "序号", "排序", "排名", "名次", "页码", "页数", "层数", "楼层", "优先级"};
    private static final String[] NAME_CAL_CN = {
            "年份", "年度", "月份", "季度", "周次", "周数", "天数",
            "小时数", "分钟数", "秒数", "期数"};
    private static final Pattern NAME_TIME_EN = Pattern.compile(
            "(?:^|_)(?:time|timestamp|datetime)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_DATE_EN = Pattern.compile(
            "(?:^|_)(?:date|dt)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_BOOL_EN = Pattern.compile(
            "(?:^|_)(?:is|has|bool|boolean|flag|enabled|active|valid|deleted|disabled|yn)"
          + "(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_RATIO_EN = Pattern.compile(
            "(?:^|_)(?:rate|ratio|percent|percentage|pct|coefficient|factor|multiplier|"
          + "discount)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_SCORE_EN = Pattern.compile(
            "(?:^|_)(?:score|gpa)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_COORD_EN = Pattern.compile(
            "(?:^|_)(?:longitude|latitude|lng|lat|lon|coord|coords|coordinate)(?:_|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_MEASURE_EN = Pattern.compile(
            "(?:^|_)(?:length|width|height|thickness|depth|distance|mileage|area|volume|"
          + "capacity|weight|temperature|humidity|density|pressure|speed|flow|power|"
          + "voltage|frequency|elevation|altitude|diameter|radius|perimeter)(?:_|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_INT_EN = Pattern.compile(
            "(?:^|_)(?:qty|quantity|count|cnt|stock)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_AGE_EN = Pattern.compile(
            "(?:^|_)(?:age)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_RANK_EN = Pattern.compile(
            "(?:^|_)(?:rank|sort|seq|sequence|serial|page|pages|floor|priority)(?:_|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_CAL_EN = Pattern.compile(
            "(?:^|_)(?:year|month|quarter|week|hour|minute|second)(?:_|$)",
            Pattern.CASE_INSENSITIVE);

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

    /**
     * 空列的名字定类型：命中返回 {类型, 留痕文案}，没命中返回 null（照旧 VARCHAR）。
     * 金额复用 isMoney 的双侧匹配与比率否决；英文一律按成词的词根匹配
     * （贴着下划线或首尾），所以 update、birthday 不会误中。
     * 判定顺序即优先级：金额 &gt; 时间 &gt; 日期 &gt; 布尔 &gt; 显式比率/系数 &gt;
     * 评分 &gt; 经纬度 &gt; 计量 &gt; 裸「率」 &gt; 计数 &gt; 年龄 &gt; 序号 &gt; 年份。
     */
    public static String[] nameBasedType(String comment, String field) {
        String c = comment == null ? "" : Names.pyStrip(comment);
        String fl = field == null ? "" : field;
        if (isMoney(c, fl)) {
            String typ = decimalType(0, 0, 4, true);
            return new String[]{typ, "该列没有值，字段名「" + c + "」含金额语义，按 " + typ + " 建列"};
        }
        if (c.contains(NAME_TIME_CN) || NAME_TIME_EN.matcher(fl).find()) {
            return new String[]{"DATETIME", "该列没有值，字段名「" + c + "」含时间语义，按 DATETIME 建列"};
        }
        if (c.contains(NAME_DATE_CN) || NAME_DATE_EN.matcher(fl).find()) {
            return new String[]{"DATE", "该列没有值，字段名「" + c + "」含日期语义，按 DATE 建列"};
        }
        if (nameHit(c, fl, NAME_BOOL_CN, NAME_BOOL_EN)) {
            return new String[]{"TINYINT(1)",
                    "该列没有值，字段名「" + c + "」含布尔语义，按 TINYINT(1) 建列"};
        }
        if (nameHit(c, fl, NAME_RATIO_CN, NAME_RATIO_EN)) {
            return new String[]{"DECIMAL(18,6)",
                    "该列没有值，字段名「" + c + "」含比率/系数语义，按 DECIMAL(18,6) 建列"};
        }
        if (nameHit(c, fl, NAME_SCORE_CN, NAME_SCORE_EN)) {
            return new String[]{"DECIMAL(10,2)",
                    "该列没有值，字段名「" + c + "」含评分语义，按 DECIMAL(10,2) 建列"};
        }
        if (nameHit(c, fl, NAME_COORD_CN, NAME_COORD_EN)) {
            return new String[]{"DECIMAL(18,8)",
                    "该列没有值，字段名「" + c + "」含经纬度/坐标语义，按 DECIMAL(18,8) 建列"};
        }
        if (nameHit(c, fl, NAME_MEASURE_CN, NAME_MEASURE_EN)) {
            return new String[]{"DECIMAL(18,4)",
                    "该列没有值，字段名「" + c + "」含计量语义，按 DECIMAL(18,4) 建列"};
        }
        if (c.contains("率")) {
            return new String[]{"DECIMAL(18,6)",
                    "该列没有值，字段名「" + c + "」含比率/系数语义，按 DECIMAL(18,6) 建列"};
        }
        if (nameHit(c, fl, NAME_INT_CN, NAME_INT_EN)) {
            return new String[]{"INT", "该列没有值，字段名「" + c + "」含数量语义，按 INT 建列"};
        }
        if (nameHit(c, fl, NAME_AGE_CN, NAME_AGE_EN)) {
            return new String[]{"INT",
                    "该列没有值，字段名「" + c + "」含年龄/年限语义，按 INT 建列"};
        }
        if (nameHit(c, fl, NAME_RANK_CN, NAME_RANK_EN)) {
            return new String[]{"INT",
                    "该列没有值，字段名「" + c + "」含序号/排名语义，按 INT 建列"};
        }
        if (nameHit(c, fl, NAME_CAL_CN, NAME_CAL_EN)) {
            return new String[]{"INT",
                    "该列没有值，字段名「" + c + "」含年份/月份语义，按 INT 建列"};
        }
        return null;
    }

    private static boolean nameHit(String c, String fl, String[] cn, Pattern en) {
        for (String k : cn) {
            if (c.contains(k)) {
                return true;
            }
        }
        return en.matcher(fl).find();
    }

    /**
     * 按观测数据定 DECIMAL 精度，必要时放宽，绝不截断。
     * 早先按"异常数据造出的宽度太荒谬"把总精度压到 30，结果把实测到的整数位挤掉了
     * （14 位整数 + 18 位小数 → DECIMAL(30,18) 只剩 12 位整数），写入正是 1264。
     * 这里改用 MySQL 的真实上限（总精度 65、小数 30）；装不下返回 null，
     * 由调用方退回文本类型，绝不给出装不下数据的列。
     */
    public static String decimalType(int intDigits, int scale, int floorInt, boolean money) {
        if (money) {
            scale = Math.max(scale, MONEY_SCALE);
            intDigits = Math.max(intDigits, MONEY_PRECISION - MONEY_SCALE);
        } else {
            intDigits = Math.max(intDigits + 2, floorInt);
        }
        intDigits = Math.max(intDigits, 1);
        scale = Math.max(scale, 0);
        int precision = intDigits + scale;
        if (scale > DECIMAL_MAX_SCALE || precision > DECIMAL_MAX_PRECISION) {
            return null;
        }
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    /** 超出 BIGINT 的整数列：用 DECIMAL(n,0) 装，n 不超过 MySQL 的 65。 */
    public static String intDecimalType(int intDigits) {
        if (intDigits > DECIMAL_MAX_PRECISION) {
            return null;
        }
        return "DECIMAL(" + Math.min(Math.max(intDigits + 2, 20), DECIMAL_MAX_PRECISION) + ",0)";
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
            // 这列一个值都没有时，值判定链全部落空；先按字段名关键字定一次类型，
            // 命中就带着留痕文案走，没命中照旧落到底下的文本分支（VARCHAR(64)）
            String[] named = hasValue ? null : nameBasedType(h, col.field);
            col.maxLen = maxLen;
            if (hasValue && allDt) {
                col.type = "DATETIME";
            } else if (hasValue && money && (allInt || allDec) && !allDa) {
                col.type = decimalType(obsInt, obsScale, 4, true);
                if (col.type == null) {
                    col.typeNote = "表头「" + h + "」含金额语义，但数值精度超出 MySQL DECIMAL 上限"
                            + "（总精度 " + DECIMAL_MAX_PRECISION + "、小数 " + DECIMAL_MAX_SCALE
                            + "），按文本保存";
                } else if (allInt) {
                    col.typeNote = "表头「" + h + "」含金额语义，样本值虽为整数，仍按 "
                            + col.type + " 而非整数类型";
                }
            } else if (hasValue && allInt) {
                // 按数值解析而不是 str(v)：Excel 的整数常以 2200000000.0 这样的浮点进来，
                // 旧写法 new BigInteger("2200000000.0") 失败就当没溢出，建出 INT 后写入 1264
                BigDecimal extreme = intExtreme(rows, i);
                if (extreme.compareTo(BIGINT_MAX) > 0) {
                    col.type = intDecimalType(obsInt);
                    col.typeNote = col.type == null
                            ? "整数位数超出 MySQL DECIMAL 上限（总精度 " + DECIMAL_MAX_PRECISION
                              + "），按文本保存"
                            : "整数超出 BIGINT 范围，按 " + col.type + " 保存";
                } else if (extreme.compareTo(INT_MAX) > 0) {
                    col.type = "BIGINT";
                } else {
                    col.type = "INT";
                }
            } else if (hasValue && allDa) {
                col.type = "DATE";
            } else if (hasValue && allDec) {
                col.type = decimalType(obsInt, obsScale, 4, money);
                if (col.type == null) {
                    col.typeNote = "数值精度超出 MySQL DECIMAL 上限"
                            + "（总精度 " + DECIMAL_MAX_PRECISION + "、小数 " + DECIMAL_MAX_SCALE
                            + "），按文本保存";
                }
            } else if (named != null) {
                col.type = named[0];
                col.typeNote = named[1];
            }
            if (col.type == null) {
                // 文本列（整列都没命中的列，以及 DECIMAL 装不下的数值列）
                long headroom = Math.max(16, maxLen / 5);
                long needed = maxLen + headroom;
                if (needed <= VARCHAR_MAX_CHARS) {
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

    /**
     * 对齐 Python 的 _int_magnitude：整数候选值的绝对量级。
     * 不能走 new BigInteger(str(v))：Excel 的整数常以 2200000000.0 进来，
     * 带小数点/科学计数法会让解析失败，旧逻辑把它当成"没溢出"，建出 INT 后写入 1264。
     */
    static BigDecimal intMagnitude(Object v) {
        if (v == null || v instanceof Boolean) {
            return null;
        }
        String s;
        if (v instanceof String) {
            s = Names.pyStrip((String) v).replace(",", "").replace("，", "");
        } else if (v instanceof Double) {
            s = PyNumber.repr(((Double) v).doubleValue());
        } else {
            s = v.toString();
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
        if (d.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (d.stripTrailingZeros().scale() > 0) {
            return null;
        }
        return d.abs();
    }

    private static BigDecimal intExtreme(List<List<Object>> rows, int i) {
        BigDecimal max = BigDecimal.ZERO;
        for (List<Object> row : rows) {
            BigDecimal mag = intMagnitude(i < row.size() ? row.get(i) : null);
            if (mag != null && mag.compareTo(max) > 0) {
                max = mag;
            }
        }
        return max;
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
