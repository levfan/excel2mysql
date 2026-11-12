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

    /**
     * 表级上下文：源表名 + 表注释。
     *
     * <p>为什么需要它：现场大量表的列注释只写「值」「数量」「num」，有的干脆一个字不写，
     * 但表名叫 {@code dwd_oil_stock}、注释写「油料库存表（吨）」——表本身已经把这一列
     * 在记什么讲清楚了。只看列会把这批整批丢掉，ads 最后只剩行数口径。
     *
     * <p>反过来，字典表、参数表、日志表里的 {@code value} 是码值或排序，不是指标，
     * 这类表由 {@link #meta} 挡住，不参与「按表主体认指标」。
     */
    public static final class Ctx {
        public final String name;
        public final String comment;
        /** 检索串：表注释 + 表名（下划线/连字符换空格），表名部分转小写。 */
        private final String text;
        /** 元数据类表（字典/参数/日志…）：码值当指标是错的，这类表不给表级证据。 */
        private final boolean meta;

        private Ctx(String name, String comment) {
            this.name = name == null ? "" : name;
            this.comment = comment == null ? "" : Names.pyStrip(comment);
            String n = this.name.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
            this.text = (this.comment + " " + n).trim();
            this.meta = hitsAny(this.text, CTX_META_CN) || CTX_META_EN.matcher(n).find();
        }

        public static final Ctx EMPTY = new Ctx("", "");

        /** 建上下文；表名与注释都为空时返回 {@link #EMPTY}，判定与以前完全一致。 */
        public static Ctx of(String tableName, String tableComment) {
            boolean noName = tableName == null || tableName.trim().isEmpty();
            boolean noComment = tableComment == null || tableComment.trim().isEmpty();
            return noName && noComment ? EMPTY : new Ctx(tableName, tableComment);
        }

        /** 这张表在记哪个量（库存量、经费额、编制数…）；认不出空串，此时不拿表当证据。 */
        public String measureSubject() {
            if (meta) {
                return "";
            }
            for (int i = 0; i < CTX_LABEL.length; i++) {
                if (text.contains(CTX_LABEL[i][0])) {
                    return CTX_LABEL[i][1];
                }
            }
            // 中文一句没写、只有英文表名（dwd_oil_stock / t_budget）时按词根对一张小表
            String[] tokens = name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
            for (int i = 0; i < CTX_LABEL_EN.length; i++) {
                for (String tk : tokens) {
                    if (tk.equals(CTX_LABEL_EN[i][0])) {
                        return CTX_LABEL_EN[i][1];
                    }
                }
            }
            return CTX_MEASURE_EN.matcher(name.toLowerCase(Locale.ROOT)).find() ? "指标度量" : "";
        }

        /** 存量类按期末值 MAX，率/比按 AVG，其余交回列自己的口径。 */
        public String aggHint() {
            if (hitsAny(text, CTX_STOCK_CN)) {
                return "MAX";
            }
            if (text.contains("占比") || text.contains("比例") || text.endsWith("率")) {
                return "AVG";
            }
            return "SUM";
        }

        /** 列上没写单位时：先看表注释里的单位，再看表主体给个常用单位。 */
        public String unitHint() {
            String u = unitOf(comment, "");
            if (!u.isEmpty()) {
                return u;
            }
            if (hitsAny(text, CTX_MONEY_CN)) {
                return "元";
            }
            if (hitsAny(text, CTX_PERSON_CN)) {
                return "人";
            }
            String subject = measureSubject();
            if (hitsAny(subject, CTX_MONEY_SUBJECT)) {
                return "元";
            }
            if ("人数".equals(subject)) {
                return "人";
            }
            return subject.contains("面积") ? "平方米" : "";
        }

        /** 取证短句里引用的表名/表注释，太长截断，别把日志刷成一行表说明。 */
        public String shortText() {
            String s = comment.isEmpty() ? name : comment;
            return s.length() > 20 ? s.substring(0, 20) : s;
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

    /** 表名/表注释里表示「整表在记一类量」的词：命中后，表里那些泛称数值列才按指标收。 */
    private static final String[] CTX_MEASURE_CN = {
            "库存", "存量", "储备", "结存", "余额", "金额", "经费", "费用", "预算", "成本",
            "收入", "支出", "收支", "产量", "销量", "用量", "消耗", "编制", "定员", "在编",
            "人数", "产能", "里程", "油耗", "面积", "容量", "吨位", "床位", "评分", "成绩",
            "占比", "比例",
    };
    private static final Pattern CTX_MEASURE_EN = Pattern.compile(
            "(?:^|_)(?:stock|inventory|balance|amount|amt|budget|cost|fee|price|expense|income|"
          + "qty|quantity|num|count|cnt|consumption|mileage|area|capacity|score|weight|volume|"
          + "salary|payroll|staff|person|people|oil|fuel)(?:_|$)");
    /** 表主体 → 指标中文名：{@code value} 这种空泛列名，交付里要写成「库存量」而不是「指标度量」。 */
    private static final String[][] CTX_LABEL = {
            {"库存", "库存量"}, {"储备", "储备量"}, {"结存", "结存量"}, {"余额", "余额"},
            {"存量", "存量"}, {"经费", "经费额"}, {"预算", "预算额"}, {"费用", "费用额"},
            {"成本", "成本额"}, {"金额", "金额"}, {"收入", "收入"}, {"支出", "支出"},
            {"产量", "产量"}, {"销量", "销量"}, {"用量", "用量"}, {"消耗", "消耗量"},
            {"编制", "编制数"}, {"定员", "定员数"}, {"在编", "在编数"}, {"人数", "人数"},
            {"产能", "产能"}, {"里程", "里程"}, {"油耗", "油耗"}, {"面积", "面积"},
            {"容量", "容量"}, {"吨位", "吨位"}, {"床位", "床位数"}, {"评分", "评分"},
            {"成绩", "成绩"}, {"占比", "占比"}, {"比例", "比例"},
    };
    /**
     * 存量类：每期一张快照，SUM 会把历史期累加，按期末值取 MAX。
     * 「储备」不算在内——储备台账多是流水，按期间累加才是常用口径，宁可按 SUM 交给人核。
     */
    private static final String[] CTX_STOCK_CN = {"库存", "存量", "结存", "余额"};
    /** 英文表名的对照表：现场不少表注释是空的，只剩 {@code dwd_oil_stock} 这种名字可依。 */
    private static final String[][] CTX_LABEL_EN = {
            {"stock", "库存量"}, {"inventory", "库存量"}, {"balance", "余额"},
            {"budget", "预算额"}, {"cost", "成本额"}, {"expense", "支出"}, {"income", "收入"},
            {"amount", "金额"}, {"amt", "金额"}, {"fee", "费用额"}, {"price", "价格"},
            {"salary", "工资额"}, {"payroll", "工资额"}, {"consumption", "消耗量"},
            {"mileage", "里程"}, {"area", "面积"}, {"capacity", "容量"}, {"score", "评分"},
            {"weight", "重量"}, {"volume", "体积"}, {"person", "人数"}, {"people", "人数"},
            {"staff", "人数"}, {"oil", "油料量"}, {"fuel", "油料量"},
    };
    private static final String[] CTX_MONEY_CN = {
            "金额", "经费", "费用", "预算", "成本", "收入", "支出", "工资", "价格", "单价",
    };
    private static final String[] CTX_PERSON_CN = {"人数", "人员", "编制", "定员", "在编", "职工"};
    /** 按表主体给单位的白名单：`余额` 不算在内（余额可以是吨、也可以是元，不猜）。 */
    private static final String[] CTX_MONEY_SUBJECT = {
            "金额", "经费额", "预算额", "费用额", "成本额", "工资额", "价格", "收入", "支出",
    };
    /** 元数据类表：码表里的 value / num 是码值或排序，当指标是错的。 */
    private static final String[] CTX_META_CN = {
            "字典", "码表", "参数", "配置", "日志", "审计", "模板", "权限", "菜单", "角色",
            "元数据", "表结构", "数据源", "操作记录", "版本记录",
    };
    private static final Pattern CTX_META_EN = Pattern.compile(
            "(?:^|_)(?:dict|dictionary|codebook|config|parameter|param|log|logs|audit|sys|"
          + "system|role|menu|template|permission|meta|metadata|schema|version|user|users)(?:_|$)",
            Pattern.CASE_INSENSITIVE);
    /** 泛称列名：叫 value/num/qty 的列本身没说是什么，只能靠表来定。 */
    private static final Pattern CTX_GENERIC_EN = Pattern.compile(
            "(?:^|_)(?:value|val|num|number|qty|quantity|amt|amount|total|sum|count|cnt|"
          + "data|figure|value\\d+|num\\d+)(?:_|$)");
    private static final String[] CTX_GENERIC_CN = {"数值", "数据", "值"};

    /**
     * 我们自己补的兜底注释后缀（「据列名判定」「据表主体判定」）。
     *
     * <p>dom 建表时会给无注释的列补一句，ads 和字段映射再把 dom 列读回来判一次——
     * 那句补出来的注释必须当成「没有注释」，否则第二轮从「注释」这条路走，表级证据
     * （单位、存量取 MAX）就全丢了，同一个列在 dom 与 ads 里口径不一致。
     */
    private static final Pattern DERIVED_COMMENT = Pattern.compile("（据(?:表主体|列名)判定）$");

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

    /** 只看列自身（注释、字段名、类型）判一列；同一列多次调用结果一致。 */
    public static Result of(Infer.Column c) {
        return of(c, null);
    }

    /**
     * 带表级上下文判一列：列自身证据不足时，再用表名／表注释兜一道。
     *
     * <p>现场「值」「数量」「num」这类列名配上「油料库存表（吨）」这种表注释，是能把指标
     * 捞回来的；只传 {@code null} 时行为与本方法出现之前逐字一致（obj/dom 的老路径不受影响）。
     */
    public static Result of(Infer.Column c, Ctx ctx) {
        Ctx t = ctx == null ? Ctx.EMPTY : ctx;
        return labelFromTable(classify(c, t), c, t);
    }

    private static Result classify(Infer.Column c, Ctx tc) {
        String comment = realComment(c == null ? null : c.comment);
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
        // 表级证据（最后一道）：表注释或表名本身在讲一类量（油料库存表、经费预算表、人员编制表），
        // 而这一列要么没注释、要么就叫 value/num/数量这种泛称——那它记的就是表主体那个量。
        // 放在状态/名称/备注之后：带「是否」「名称」「备注」的列已经各自归位，不会被表带着走。
        if (!tc.meta) {
            String subject = tc.measureSubject();
            if (!subject.isEmpty()) {
                String ut = type == null ? "" : type.toUpperCase(Locale.ROOT);
                // 无注释的 TINYINT/BIT 多半是编码或标志位，宁可不收：这条规则是补漏，不是滥收
                boolean blankNum = comment.isEmpty() && numeric(type) && !booleanLike(type)
                        && !ut.startsWith("TINYINT") && !ut.startsWith("BIT");
                boolean generic = (CTX_GENERIC_EN.matcher(fl).find()
                        || hitsAny(comment, CTX_GENERIC_CN))
                        && (numeric(type) && !booleanLike(type) || castable(type, c));
                if (blankNum || generic) {
                    String unit = unitOf(comment, "");
                    if (unit.isEmpty()) {
                        unit = tc.unitHint();
                    }
                    String agg = aggOf(comment, field, "");
                    if ("SUM".equals(agg)) {
                        agg = tc.aggHint();
                    }
                    return new Result(MEASURE, withDim(subject, dim), true, true, dim, unit, agg,
                            "表注释/表名「" + tc.shortText() + "」说明整表在记" + subject
                                    + "，列「" + field + "」本身没有更细口径，按表主体判为指标",
                            !numeric(type));
                }
            }
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

    /**
     * 指标度量这类空泛标签，列上又没注释时，用表主体补成看得懂的名字（库存量、经费额）。
     * 只在列自己一句话都没写时才补：列注释优先，表只能兜底。
     */
    private static Result labelFromTable(Result r, Infer.Column c, Ctx t) {
        if (r == null || !r.measure || t == null || t.meta) {
            return r;
        }
        String src = realComment(c == null ? null : c.comment);
        if (!src.isEmpty()) {
            return r;
        }
        String subject = t.measureSubject();
        if (subject.isEmpty()) {
            return r;
        }
        // 单位与口径：只有列名本身就是 value/num/qty 这种泛称时才让表说了算——
        // 经费表里叫 days 的列不该被写成「元」，也不该因为表在记经费就改成 MAX。
        String fl = c == null || c.field == null ? "" : c.field.toLowerCase(Locale.ROOT);
        boolean generic = CTX_GENERIC_EN.matcher(fl).find();
        // 改名也只给泛称列：days/hours/km 自己有明确含义，套上表主体反而错（经费表里的工期成了「经费额」）
        String label = generic && r.label.startsWith("指标度量") && !"指标度量".equals(subject)
                ? r.label.replace("指标度量", subject) : r.label;
        String unit = r.unit.isEmpty() && generic ? t.unitHint() : r.unit;
        String agg = "SUM".equals(r.agg) && generic ? t.aggHint() : r.agg;
        if (label.equals(r.label) && unit.equals(r.unit) && agg.equals(r.agg)) {
            return r;
        }
        return new Result(r.code, label, r.business, r.measure, r.dim, unit, agg, r.basis,
                r.castNumeric);
    }

    /** 生成器只留系统字段以外的列；无据可依的未归类列也跳过。 */
    public static boolean keep(Infer.Column c) {
        return keep(c, null);
    }

    /** 带表级上下文的取舍：表的注释/名称能把「无注释的数值列」捞回业务列，不该在 dom 就被丢掉。 */
    public static boolean keep(Infer.Column c, Ctx ctx) {
        return c != null && (c.primaryKey || of(c, ctx).business);
    }

    /**
     * 空注释的业务列补一句可读说明，别让交付表里出现空 COMMENT。
     *
     * <p>这里刻意不用「按字段名推断」：{@link #SYS_CN} 认「字段名」这个词（那是元数据表里
     * 真有一列叫字段名），补出来的注释一旦带上它，下一层再把列读回来就会被判成系统字段——
     * dom 里还在、ads 里整列消失，指标白丢。改成「据列名判定」，不再与系统词表撞车。
     */
    public static String commentOf(Infer.Column c) {
        return commentOf(c, null);
    }

    /**
     * 带表级上下文的兜底注释：表能说清就写「库存量（据表主体判定）」，别停在「指标度量」。
     *
     * <p>措辞里不许出现「表名」「表注释」「字段名」——那三个词都在 {@link #SYS_CN} 里，
     * 补出来的注释下一轮读回来会被判成系统字段，列在 dom 还在、到 ads 整列消失
     * （上一轮「按字段名推断」已经踩过一次，这里换成「据表主体判定」）。
     */
    public static String commentOf(Infer.Column c, Ctx ctx) {
        String src = c == null || c.comment == null ? "" : Names.pyStrip(c.comment);
        if (!src.isEmpty()) {
            return src;
        }
        Result r = of(c, ctx);
        if (ctx != null && !ctx.meta && !ctx.measureSubject().isEmpty()
                && r.measure && !r.label.startsWith("指标度量")) {
            return r.label + "（据表主体判定）";
        }
        return r.label + "（据列名判定）";
    }

    /**
     * 判断语义时用的注释：整句都是我们补的兜底注释（「库存量（据表主体判定）」）就当成没注释。
     *
     * <p>不这么做的话，第二轮读回来会把这句补出来的话当证据：{@code value} 的注释变成「库存量」，
     * 单位「吨」没了、口径也不再走表级规则——dom 与 ads 对同一列说两套。
     */
    private static String realComment(String comment) {
        String s = comment == null ? "" : Names.pyStrip(comment);
        if (s.isEmpty()) {
            return "";
        }
        return DERIVED_COMMENT.matcher(s).find() ? "" : s;
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
