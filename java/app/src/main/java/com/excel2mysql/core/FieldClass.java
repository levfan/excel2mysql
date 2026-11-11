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

        Result(String code, String label, boolean business, boolean measure, String dim,
               String unit, String agg, String basis) {
            this.code = code;
            this.label = label;
            this.business = business;
            this.measure = measure;
            this.dim = dim == null ? "" : dim;
            this.unit = unit == null ? "" : unit;
            this.agg = agg == null ? "" : agg;
            this.basis = basis == null ? "" : basis;
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
    };

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
        if (note.contains("金额") || note.contains("合计/汇总") || note.contains("比率/系数")
                || note.contains("评分") || note.contains("计量") || note.contains("产量/运量")
                || note.contains("大计数") || note.contains("数量") || note.contains("年龄/年限")
                || note.contains("时长/周期")) {
            boolean numeric = numeric(type);
            return new Result(numeric ? MEASURE : ATTRIBUTE,
                    withDim(numeric ? "指标度量" : "业务属性", dim), true, numeric, dim,
                    numeric ? unitOf(comment, note) : "",
                    numeric ? aggOf(comment, field, note) : "",
                    numeric ? noteLabel(note) : noteLabel(note) + "，但源列不是数值型，不做指标");
        }
        if (note.contains("链接/路径")) {
            return new Result(ATTRIBUTE, withDim("业务属性", dim), true, false, dim, "", "",
                    "链接/路径类文本");
        }
        if (note.contains("长文本")) {
            return new Result(DESC, withDim("描述备注", dim), true, false, dim, "", "",
                    "字段名或注释带备注/说明语义");
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
        if (!dim.isEmpty() && !"dim_code_info".equals(dim) && !"dim_code_it".equals(dim)) {
            return byDim(dim, "命中对象维度词");
        }
        if (numeric(type) && hitsAny(comment, MEASURE_CN)) {
            return new Result(MEASURE, "指标度量", true, true, dim, unitOf(comment, ""),
                    aggOf(comment, field, ""), "数值列且注释带数量/金额/计量语义");
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

    /** 空注释的业务列补一句可读说明，别让交付表里出现空 COMMENT。 */
    public static String commentOf(Infer.Column c) {
        String src = c == null || c.comment == null ? "" : Names.pyStrip(c.comment);
        if (!src.isEmpty()) {
            return src;
        }
        Result r = of(c);
        return r.label + "（按字段名推断）";
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

    private static String aggOf(String comment, String field, String note) {
        String text = (comment == null ? "" : comment) + " " + (field == null ? "" : field);
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.contains("最大") || text.contains("最高") || text.contains("峰值")
                || lower.contains("max")) {
            return "MAX";
        }
        if (text.contains("最小") || text.contains("最低") || lower.contains("min")) {
            return "MIN";
        }
        if (text.contains("比例") || text.contains("占比") || text.contains("百分比")
                || text.contains("比重") || text.contains("系数") || text.contains("概率")
                || text.contains("平均") || text.contains("均价") || text.contains("单价")
                || text.contains("比率") || text.endsWith("率")
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
