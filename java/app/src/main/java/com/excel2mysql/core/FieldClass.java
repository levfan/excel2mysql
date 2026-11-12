package com.excel2mysql.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字段级语义分类：一列到底是标识、时间、指标、状态、对象属性还是系统字段。
 *
 * <p>三个落点共用这一套口径：obj/dom 的字段取舍（系统字段与完全无据可依的列不进层）、
 * ads 挑哪些列当指标、字段映射 CSV 的「数据元名称」写什么。判定先借
 * {@link Infer#nameBasedType} 那套成熟的金额/数量/时间/布尔/码值词表，再补三类它不管的：
 * 系统审计字段（创建/更新/删除/租户/版本/排序）、条款 183 的十类对象维度、以及
 * 「有中文注释但既不是指标也不是对象」的业务属性。判不出又不该丢的列标业务属性，
 * 无注释且名称也无可识别语义的列才标 {@link #UNKNOWN}，由生成器跳过。
 */
public final class FieldClass {

    public static final String SYSTEM = "SYSTEM";
    public static final String ID = "ID";
    public static final String TIME = "TIME";
    public static final String MEASURE = "MEASURE";
    public static final String STATUS = "STATUS";
    public static final String NAME = "NAME";
    public static final String DESC = "DESC";
    public static final String ORG = "ORG";
    public static final String MATERIAL = "MATERIAL";
    public static final String FUND = "FUND";
    public static final String FACILITY = "FACILITY";
    public static final String PERSON = "PERSON";
    public static final String SUPPLY_STANDARD = "SUPPLY_STANDARD";
    public static final String POLICY = "POLICY";
    public static final String GEO = "GEO";
    public static final String CODEINFO = "CODEINFO";
    public static final String ATTRIBUTE = "ATTRIBUTE";
    public static final String UNKNOWN = "UNKNOWN";

    /** 一列的判定结果：类别、中文说明、是否进层、是否可当指标、维度提示、单位与聚合口径。 */
    public static final class Result {
        public final String code;
        public final String label;
        public final boolean business;
        public final boolean measure;
        public final String dim;
        public final String unit;
        public final String agg;
        public final String basis;
        /**
         * 指标列源类型是文本（数量/金额存成了 VARCHAR）：聚合要先把值 CAST 成 DECIMAL。
         * 这类列在 ads 里出 {@code SUM(CAST(NULLIF(REPLACE(…)) AS DECIMAL(38,6)))}，
         * 不能直接 SUM(`c`)，否则 MySQL 按字符串求和只会得到 0。
         */
        public final boolean castNumeric;

        Result(String code, String label, boolean business, boolean measure, String dim,
               String unit, String agg, String basis) {
            this(code, label, business, measure, dim, unit, agg, basis, false);
        }

        Result(String code, String label, boolean business, boolean measure, String dim,
               String unit, String agg, String basis, boolean castNumeric) {
            this.code = code;
            this.label = label;
            this.business = business;
            this.measure = measure;
            this.dim = dim == null ? "" : dim;
            this.unit = unit == null ? "" : unit;
            this.agg = agg == null ? "" : agg;
            this.basis = basis == null ? "" : basis;
            this.castNumeric = castNumeric;
        }

        /** 业务字段的中文类别说明；治理字段不经过这里。 */
        public String describe() {
            return basis.isEmpty() ? label : label + "（" + basis + "）";
        }
    }

    /** 审计/系统字段：库里都有、但不承载业务，进层只会挤掉真正的业务列。 */
    private static final String[] SYS_CN = {
            "创建时间", "创建日期", "创建人", "创建者", "创建用户", "创建机构", "创建部门",
            "更新时间", "更新日期", "更新人", "更新者", "更新用户",
            "修改时间", "修改日期", "修改人", "修改者",
            "添加时间", "新增时间", "录入时间", "录入人", "操作时间",
            "删除时间", "删除人", "删除标志", "删除标记", "是否删除", "逻辑删除",
            "数据版本", "版本号", "排序号", "排序", "序号", "顺序号",
            "租户", "租户号", "扩展字段", "保留字段", "自定义字段",
            "数据源", "驱动类", "连接地址", "连接串", "密码", "登录ip", "登录次数", "最后登录",
            "头像路径", "字段名", "字段类型", "字段长度", "字段描述", "表名", "表注释",
            "生成功能", "生成路径", "使用的模板", "主键字段",
    };
    private static final Pattern SYS_EN = Pattern.compile(
            "(?:^|_)(?:create|created|creater|creator|add|added|update|updated|updater|"
          + "modify|modified|modifier|insert|inserted|delete|deleted|del|is_?del|"
          + "version|tenant|sort|seq|seq_?no|order_?num|ordinal|"
          + "ext\\d*|reserve\\d*|custom_?field|"
          + "jdbc|driver|password|pwd|salt|token|login_?ip|login_?name|login_?date|"
          + "last_?login|user_?agent|session|avatar|"
          + "field_?name|field_?type|field_?length|table_?name|gen_?path|tpl_?category|"
          + "function_?author|datasource|data_?source)(?:_|$)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] TIME_CN = {
            "时间", "日期", "年度", "年份", "月份", "季度", "周次", "有效期", "到期",
            "截止", "起始", "开始", "结束", "统计日", "录入日", "发放日", "发生日",
    };
    private static final Pattern TIME_EN = Pattern.compile(
            "(?:^|_)(?:time|date|datetime|timestamp|year|month|quarter|week|"
          + "birthday|birthdate|start_?date|end_?date|expire|deadline)(?:_|$)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] ID_CN = {
            "编号", "编码", "代码", "单号", "批号", "批次号", "流水号", "主键", "唯一标识",
            "标识符", "号码", "证号", "账号", "帐号", "工号", "学号", "警号",
    };
    private static final Pattern ID_EN = Pattern.compile(
            "(?:^|_)(?:id|uid|uuid|guid|pk|code|codes|no|sn|serial|"
          + "barcode|qrcode|accountno|acctno|cardno|idno|idcard)(?:_|$)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] STATUS_CN = {
            "状态", "状况", "是否", "标志", "标志位", "开关", "布尔", "真假", "可否", "能否",
            "结果", "等级", "级别", "质量状况", "合格", "有效", "启用", "禁用",
    };
    private static final Pattern STATUS_EN = Pattern.compile(
            "(?:^|_)(?:status|state|flag|enabled|disabled|active|valid|qualified|result|"
          + "level|grade|bool|boolean|is|has|yn|yesno|truefalse|locked|visible|available|"
          + "permitted|approved|checked|verified|required|need)(?:_|$)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] CODEINFO_CN = {
            "类别", "分类", "类型", "种类", "品种", "字典", "码表", "枚举", "值域", "目录",
    };

    private static final String[] NAME_CN = {
            "名称", "标题", "题目", "主题", "简称", "全称", "别名", "曾用名",
            "渠道", "来源", "方式", "方法", "工艺", "工序", "阶段", "环节", "标签", "关键词",
    };

    private static final String[] DESC_CN = {
            "备注", "说明", "描述", "详情", "内容", "摘要", "简介", "评价", "意见", "建议",
            "要求", "原因", "问题", "经过", "措施", "职责", "业绩", "事迹", "附注", "注释",
            "正文", "文本", "结论", "留言", "反馈", "答复", "回复", "情况", "定义", "解释",
            "用途", "作用", "目的", "范围", "沿革", "职能",
    };
    private static final Pattern DESC_EN = Pattern.compile(
            "(?:^|_)(?:remark|remarks|note|notes|memo|description|desc|detail|details|"
          + "content|contents|summary|abstract|comment|comments|feedback|text|body|intro|"
          + "introduction|overview|conclusion|suggestion|suggestions|reason|purpose|"
          + "definition|explanation|remark_?text)(?:_|$)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] MEASURE_CN = {
            "数量", "人数", "次数", "个数", "件数", "张数", "台数", "条数", "总数", "总量",
            "合计", "小计", "累计", "库存", "余额", "金额", "总额", "总价", "单价", "均价",
            "价格", "费用", "成本", "收入", "支出", "预算", "额度", "面积", "体积", "容量",
            "重量", "长度", "宽度", "高度", "距离", "里程", "速度", "温度", "湿度", "压力",
            "功率", "能耗", "年龄", "工龄", "军龄", "得分", "评分", "分数", "比例", "占比",
            "百分比", "比率", "系数", "概率", "频率", "时长", "周期", "工期", "耗时",
            "限量", "限额", "定额", "标准值",
            // 现场语料里高频、但上面那批词根接不住的量值：存量、保有量、编制数、产值、折旧…
            "折旧", "摊销", "保有", "编制", "在编", "实有", "消耗", "用量", "耗量", "周转",
            "载重", "吨位", "床位", "席位", "油耗", "产油", "产气", "补给", "拨付", "补贴",
            "罚金", "押金", "净值", "原值", "现值", "产值", "税额", "保费", "运费", "租费",
    };

    /**
     * 中文量值后缀：注释以这些字收尾（去掉括号里的单位之后），数值列就当指标。
     * 收得比 {@link #MEASURE_CN} 宽一档，是因为「储备量」「在编数」「差额」「运价」这类
     * 复合词列不完，而「量/数/额/值/费/价/金」七个字本身已经把语义说清了；
     * 「率/比」走 AVG（见 {@link #aggOf}）。文本列不适用——那是另一条强证据规则。
     */
    private static final String[] MEASURE_SUFFIX_CN = {
            "量", "数", "额", "值", "费", "价", "金", "率", "比",
    };

    /**
     * 英文字段名的量值词根：「qty / amount / weight / price / rate / days」这类没有中文注释也能认。
     * 只认整词（前后是下划线或首尾），免得把 {@code amount_type} 之外的 {@code amendment} 当成金额。
     */
    private static final Pattern MEASURE_EN = Pattern.compile(
            "(?:^|_)(?:qty|quantity|amt|amount|num|count|cnt|total|sum|weight|vol|volume|"
          + "price|cost|fee|score|rate|ratio|percent|pct|avg|average|days?|hours?|minutes?|"
          + "seconds?|km|mileage|distance|dist|capacity|area|height|width|length|temp|"
          + "temperature|humidity|pressure|power|energy|speed|age|balance|stock|inventory)"
          + "(?:_|$)",
            Pattern.CASE_INSENSITIVE);

    /** 文本型数值列的长度上限：再长就不是「数量存成了文本」，而是备注里带了数字。 */
    private static final long CAST_MAX_LEN = 128L;

    private static final String[] UNITS = {
            "万元", "亿元", "元", "吨", "千克", "公斤", "克", "毫克", "升", "毫升",
            "立方米", "平方米", "米", "千米", "公里", "亩", "公顷", "个", "件", "套",
            "台", "辆", "艘", "架", "张", "人", "次", "天", "小时", "分钟", "秒",
            "度", "千瓦", "兆瓦", "百分点", "%", "‰", "岁",
    };
    private static final Pattern UNIT_PAREN = Pattern.compile("[（(]([^（）()]{1,8})[)）]");
    private static final Pattern UNIT_DECL = Pattern.compile("单位\\s*[:：]\\s*([^\\s，,。；;）)]{1,8})");

    private FieldClass() {
    }

    /** 按注释、字段名与类型判一列；同一列多次调用结果一致，不依赖上下文。 */
    public static Result of(Infer.Column c) {
        String comment = c == null || c.comment == null ? "" : Names.pyStrip(c.comment);
        String field = c == null || c.field == null ? "" : c.field;
        String type = c == null || c.type == null ? "" : c.type;
        boolean pk = c != null && c.primaryKey;
        String fl = field.toLowerCase(Locale.ROOT);

        if (isSystem(comment, fl)) {
            return new Result(SYSTEM, "系统字段", false, false, "", "", "",
                    "创建/更新/删除/租户/版本/排序这类库内审计列");
        }
        String dim = Taxonomy.dimOf(comment, field);

        if (pk || isId(comment, fl)) {
            return new Result(ID, withDim("标识编码", dim), true, false, dim, "", "",
                    pk ? "源表主键" : "字段名或注释带编号/编码/号码语义");
        }

        String[] named = Infer.nameBasedType(comment, field);
        String note = named == null ? "" : named[1];

        if (note.contains("码值")) {
            return new Result(ID, withDim("标识编码", dim), true, false, dim, "", "",
                    "证件/卡号/号码这类码值");
        }
        if (note.contains("时间") || note.contains("时刻") || note.contains("日期")
                || note.contains("年份/月份")) {
            return new Result(TIME, "时间日期", true, false, dim, "", "",
                    "字段名或注释带日期/时间语义");
        }
        if (note.contains("布尔")) {
            return new Result(STATUS, withDim("状态标志", dim), true, false, dim, "", "",
                    "字段名或注释带是否/标志语义");
        }
        if (note.contains("序号/排名")) {
            return new Result(SYSTEM, "系统字段", false, false, "", "", "",
                    "序号/排名是行内序号，不承载业务");
        }
        if (note.contains("经纬度/坐标")) {
            return new Result(GEO, "地理区域", true, false, "dim_geo", "", "",
                    "字段名或注释带经纬度/坐标语义");
        }
        if (strongMeasure(note)) {
            boolean num = numeric(type);
            if (num) {
                return new Result(MEASURE, withDim("指标度量", dim), true, true, dim,
                        unitOf(comment, note), aggOf(comment, field, note), noteLabel(note));
            }
            // 现场大量「数量/金额存成 VARCHAR」：语义已经明确到不能再明确，聚合前 CAST 一次即可，
            // 丢掉整列反而让 ads 只剩行数口径。长度上限挡住「备注里带数字」的长文本。
            if (castable(type, c)) {
                return new Result(MEASURE, withDim("指标度量", dim), true, true, dim,
                        unitOf(comment, note), aggOf(comment, field, note),
                        noteLabel(note) + "，源列是文本但存的是数值，聚合前 CAST 成 DECIMAL", true);
            }
            return new Result(ATTRIBUTE, withDim("业务属性", dim), true, false, dim, "", "",
                    noteLabel(note) + "，但源列不是数值型，不做指标");
        }
        if (note.contains("链接/路径")) {
            return new Result(ATTRIBUTE, withDim("业务属性", dim), true, false, dim, "", "",
                    "链接/路径类文本");
        }
        if (note.contains("长文本")) {
            return new Result(DESC, withDim("描述备注", dim), true, false, dim, "", "",
                    "字段名或注释带备注/说明语义");
        }
        // 数值列的第一道兜底：注释以「量/数/额/值/费/价/金/率/比」收尾（储备量、在编数、差额、
        // 运价、折旧费…），或注释里写了单位（载重（吨）、单位：km），或英文字段名就是
        // qty/amount/price 这类，都按指标收。必须排在状态/名称之前：「机构编制数」会先被
        // 「机构」当成对象维度、「库存状态」会先被当成标志位，那样指标就白丢了。
        if (numeric(type) && !booleanLike(type)) {
            String core = stripUnit(comment);
            String unit = unitOf(comment, "");
            if (endsWithAny(core, MEASURE_SUFFIX_CN) || !unit.isEmpty()
                    || MEASURE_EN.matcher(fl).find()) {
                return new Result(MEASURE, withDim("指标度量", dim), true, true, dim, unit,
                        aggOf(comment, field, ""),
                        "数值列且注释/字段名带量值语义（" + unitTag(core, unit, fl) + "）");
            }
        }

        if (hitWords(comment, fl, STATUS_CN, STATUS_EN)) {
            return new Result(STATUS, withDim("状态标志", dim), true, false, dim, "", "",
                    "字段名或注释带状态/标志语义");
        }
        if (hitsAny(comment, CODEINFO_CN)) {
            return new Result(CODEINFO, "代码字典", true, false,
                    dim.isEmpty() ? "dim_code_info" : dim, "", "",
                    "字段名或注释带类别/分类/字典语义");
        }
        if (hitsAny(comment, NAME_CN) || note.contains("名称/类别")) {
            if (!dim.isEmpty() && !"dim_code_info".equals(dim) && !"dim_code_it".equals(dim)) {
                return byDim(dim, "字段名或注释带名称语义，且命中对象维度词");
            }
            return new Result(NAME, withDim("名称标题", dim), true, false, dim, "", "",
                    "字段名或注释带名称/标题语义");
        }
        if (hitsAny(comment, DESC_CN) || DESC_EN.matcher(fl).find()) {
            return new Result(DESC, withDim("描述备注", dim), true, false, dim, "", "",
                    "字段名或注释带备注/说明语义");
        }
        // 数值列的第二道兜底：命中量值词表（库存、面积、限额…）但注释里还混着状态/名称词时，
        // 前面几类已经拦下了；到这里说明确实是量值。
        if (numeric(type) && !booleanLike(type) && hitsAny(comment, MEASURE_CN)) {
            String core = stripUnit(comment);
            String unit = unitOf(comment, "");
            return new Result(MEASURE, withDim("指标度量", dim), true, true, dim, unit,
                    aggOf(comment, field, ""),
                    "数值列且注释带量值语义（" + unitTag(core, unit, fl) + "）");
        }
        if (!dim.isEmpty() && !"dim_code_info".equals(dim) && !"dim_code_it".equals(dim)) {
            return byDim(dim, "命中对象维度词");
        }
        if (!comment.isEmpty()) {
            return new Result(ATTRIBUTE, withDim("业务属性", dim), true, false, dim, "", "",
                    "有中文注释，未命中指标或对象维度词");
        }
        return new Result(UNKNOWN, "未归类", false, false, "", "", "",
                "无注释且字段名无可识别语义");
    }

    /** 生成器只留系统字段以外的列；无据可依的未归类列也跳过。 */
    public static boolean keep(Infer.Column c) {
        return c != null && (c.primaryKey || of(c).business);
    }

    /**
     * 空注释的业务列补一句可读说明，别让交付表里出现空 COMMENT。
     *
     * <p>这里刻意不用「按字段名推断」：{@link #SYS_CN} 认「字段名」这个词（那是元数据表里
     * 真有一列叫字段名），补出来的注释一旦带上它，下一层再把列读回来就会被判成系统字段——
     * dom 里还在、ads 里整列消失，指标白丢。改成「据列名判定」，不再与系统词表撞车。
     */
    public static String commentOf(Infer.Column c) {
        String src = c == null || c.comment == null ? "" : Names.pyStrip(c.comment);
        if (!src.isEmpty()) {
            return src;
        }
        Result r = of(c);
        return r.label + "（据列名判定）";
    }

    private static boolean isSystem(String comment, String field) {
        if (hitsAny(comment, SYS_CN)) {
            return true;
        }
        return SYS_EN.matcher(field).find();
    }

    private static boolean isId(String comment, String field) {
        if (hitsAny(comment, ID_CN)) {
            return true;
        }
        return ID_EN.matcher(field).find();
    }

    private static boolean hitWords(String comment, String field, String[] cn, Pattern en) {
        return hitsAny(comment, cn) || en.matcher(field).find();
    }

    private static Result byDim(String dim, String why) {
        if ("dim_org".equals(dim)) { return new Result(ORG, "机构组织", true, false, dim, "", "", why); }
        if ("dim_material".equals(dim)) { return new Result(MATERIAL, "物资装备", true, false, dim, "", "", why); }
        if ("dim_fund_account".equals(dim)) { return new Result(FUND, "经费账户", true, false, dim, "", "", why); }
        if ("dim_facility".equals(dim)) { return new Result(FACILITY, "设施设备", true, false, dim, "", "", why); }
        if ("dim_person".equals(dim)) { return new Result(PERSON, "人员属性", true, false, dim, "", "", why); }
        if ("dim_supply_standard".equals(dim)) { return new Result(SUPPLY_STANDARD, "供应标准", true, false, dim, "", "", why); }
        if ("dim_policy".equals(dim)) { return new Result(POLICY, "政策依据", true, false, dim, "", "", why); }
        if ("dim_geo".equals(dim)) { return new Result(GEO, "地理区域", true, false, dim, "", "", why); }
        if ("dim_code_info".equals(dim)) { return new Result(CODEINFO, "代码字典", true, false, dim, "", "", why); }
        if ("dim_code_it".equals(dim)) { return new Result(CODEINFO, "代码字典", true, false, dim, "", "", why); }
        return new Result(ATTRIBUTE, "业务属性", true, false, dim, "", "", why);
    }

    private static String withDim(String label, String dim) {
        if (dim == null || dim.isEmpty()) {
            return label;
        }
        String d = dim.startsWith("dim_") ? dim.substring(4) : dim;
        if ("org".equals(d)) { return label + "（机构）"; }
        if ("material".equals(d)) { return label + "（物资）"; }
        if ("fund_account".equals(d)) { return label + "（经费）"; }
        if ("facility".equals(d)) { return label + "（设施）"; }
        if ("person".equals(d)) { return label + "（人员）"; }
        if ("supply_standard".equals(d)) { return label + "（标准）"; }
        if ("policy".equals(d)) { return label + "（政策）"; }
        if ("geo".equals(d)) { return label + "（地理）"; }
        if ("code_info".equals(d) || "code_it".equals(d)) { return label + "（代码）"; }
        return label;
    }

    private static String noteLabel(String note) {
        int i = note.indexOf('含');
        int j = note.indexOf("语义");
        if (i >= 0 && j > i + 1) {
            return "字段名或注释含" + note.substring(i + 1, j).trim();
        }
        return "字段名或注释带指标语义";
    }

    private static boolean numeric(String type) {
        String u = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return u.startsWith("INT") || u.startsWith("BIGINT") || u.startsWith("SMALLINT")
                || u.startsWith("TINYINT") || u.startsWith("MEDIUMINT") || u.startsWith("DECIMAL")
                || u.startsWith("NUMERIC") || u.startsWith("DOUBLE") || u.startsWith("FLOAT")
                || u.startsWith("REAL") || u.startsWith("BIT") || u.startsWith("BOOLEAN");
    }

    /** 开关量（BOOLEAN / TINYINT(1)）：求和没有意义，不参与指标的兜底规则。 */
    private static boolean booleanLike(String type) {
        String u = type == null ? "" : type.toUpperCase(Locale.ROOT).replace(" ", "");
        return u.startsWith("BOOLEAN") || u.startsWith("BIT") || u.equals("TINYINT(1)");
    }

    /** 文本/字符型且长度在 {@link #CAST_MAX_LEN} 以内：可能是「数值存成了文本」。 */
    private static boolean castable(String type, Infer.Column c) {
        String u = type == null ? "" : type.toUpperCase(Locale.ROOT);
        boolean texty = u.startsWith("VARCHAR") || u.startsWith("CHAR") || u.startsWith("TEXT")
                || u.startsWith("LONGTEXT") || u.startsWith("MEDIUMTEXT") || u.startsWith("TINYTEXT");
        if (!texty) {
            return false;
        }
        long len = c == null ? 0L : c.maxLen;
        return len <= 0L || len <= CAST_MAX_LEN;
    }

    private static boolean strongMeasure(String note) {
        return note.contains("金额") || note.contains("合计/汇总") || note.contains("比率/系数")
                || note.contains("评分") || note.contains("计量") || note.contains("产量/运量")
                || note.contains("大计数") || note.contains("数量") || note.contains("年龄/年限")
                || note.contains("时长/周期");
    }

    /** 去掉注释里的括号单位与「单位：xx」后缀，只留干词给后缀判断用。 */
    private static String stripUnit(String comment) {
        String s = comment == null ? "" : comment;
        s = UNIT_PAREN.matcher(s).replaceAll("");
        s = UNIT_DECL.matcher(s).replaceAll("");
        return Names.pyStrip(s).trim();
    }

    private static boolean endsWithAny(String text, String[] tails) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String t = text.trim();
        for (String tail : tails) {
            if (t.endsWith(tail)) {
                return true;
            }
        }
        return false;
    }

    /** 兜底判据的取证短句：写清是后缀、单位还是英文字段名命中的，交付时能复核。 */
    private static String unitTag(String core, String unit, String field) {
        StringBuilder sb = new StringBuilder();
        if (!unit.isEmpty()) {
            sb.append("单位 ").append(unit);
        }
        if (endsWithAny(core, MEASURE_SUFFIX_CN)) {
            if (sb.length() > 0) { sb.append("、"); }
            sb.append("「").append(core.length() > 12 ? core.substring(core.length() - 12) : core)
                    .append("」量值后缀");
        }
        if (MEASURE_EN.matcher(field == null ? "" : field).find()) {
            if (sb.length() > 0) { sb.append("、"); }
            sb.append("英文字段名量值词根");
        }
        return sb.length() == 0 ? "量值语义" : sb.toString();
    }

    /**
     * 聚合口径：先认出「区间两端（最大/最小）」，再把「存量类」按期末值取 MAX，
     * 均值类（率/比/单价/温度/湿度/压力/速度）按 AVG，其余一律 SUM。
     * 存量类改 MAX 是因为余额、库存每期都是一次快照，SUM 会把历史期累加，口径明显不成立。
     */
    private static String aggOf(String comment, String field, String note) {
        String text = (comment == null ? "" : comment) + " " + (field == null ? "" : field);
        String lower = text.toLowerCase(Locale.ROOT);
        String core = stripUnit(comment).trim();
        if (text.contains("最大") || text.contains("最高") || text.contains("峰值")
                || text.contains("上限") || text.contains("额定") || lower.contains("max")) {
            return "MAX";
        }
        if (text.contains("最小") || text.contains("最低") || text.contains("下限")
                || lower.contains("min")) {
            return "MIN";
        }
        if (text.contains("库存") || text.contains("余额") || text.contains("结存")
                || text.contains("结余") || text.contains("存量") || text.contains("现值")
                || lower.contains("balance") || lower.contains("stock")) {
            return "MAX";
        }
        if (text.contains("比例") || text.contains("占比") || text.contains("百分比")
                || text.contains("比重") || text.contains("系数") || text.contains("概率")
                || text.contains("平均") || text.contains("均价") || text.contains("单价")
                || text.contains("比率") || text.contains("温度") || text.contains("湿度")
                || text.contains("压力") || text.contains("速度") || text.contains("密度")
                || core.endsWith("率") || core.endsWith("比")
                || lower.contains("rate") || lower.contains("ratio") || lower.contains("percent")
                || lower.contains("pct") || lower.contains("avg") || lower.contains("average")
                || lower.contains("price")) {
            return "AVG";
        }
        if (text.contains("年龄") || text.contains("工龄") || text.contains("军龄")
                || text.contains("年限")) {
            return "AVG";
        }
        return "SUM";
    }

    private static String unitOf(String comment, String note) {
        String c = comment == null ? "" : comment;
        Matcher dm = UNIT_DECL.matcher(c);
        if (dm.find()) {
            return cleanUnit(dm.group(1));
        }
        Matcher pm = UNIT_PAREN.matcher(c);
        while (pm.find()) {
            String u = cleanUnit(pm.group(1));
            if (!u.isEmpty()) {
                return u;
            }
        }
        if (note != null && note.contains("经纬度")) {
            return "度";
        }
        if (c.contains("百分比") || c.contains("占比") || c.endsWith("率")) {
            return "%";
        }
        if (c.contains("年龄") || c.contains("岁")) {
            return "岁";
        }
        if (c.contains("工龄") || c.contains("军龄") || c.contains("年限")) {
            return "年";
        }
        return "";
    }

    private static String cleanUnit(String raw) {
        String u = Names.pyStrip(raw == null ? "" : raw);
        if (u.isEmpty()) {
            return "";
        }
        for (String unit : UNITS) {
            if (u.equals(unit)) {
                return unit;
            }
        }
        // 括号里常写整句解释（如「0男 1女」），只收短且含单位字的
        if (u.length() <= 4) {
            for (String unit : UNITS) {
                if (u.contains(unit)) {
                    return unit;
                }
            }
        }
        return "";
    }

    private static boolean hitsAny(String text, String[] words) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
