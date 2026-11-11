package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 大数据指标条款里的分类体系：条款 184 的领域与子领域、条款 183 的十类对象维度、
 * 条款 188 的三个主题。第四页的自动映射全部从这里取词，不在判定代码里散着写业务名词。
 *
 * <p>领域码是按条款顺序钉死的（DOM01 保障 … DOM07 设施建设），不是按首次出现编号：
 * 同一份源表换个顺序拖进来，领域码与目标表名都不许变。
 */
public final class Taxonomy {

    /** 一个领域：条款 184 的名字、固定领域码与下辖子领域。 */
    public static final class Domain {
        public final String name;
        public final String code;
        public final String[] subs;
        Domain(String name, String code, String[] subs) {
            this.name = name;
            this.code = code;
            this.subs = subs;
        }
    }

    public static final Domain[] DOMAINS = {
            new Domain("保障", "DOM01", new String[]{"基础数据", "动态数据", "决策数据", "实力数据"}),
            new Domain("综合", "DOM02", new String[]{"物资储备", "科技管理", "信息化", "政策法规"}),
            new Domain("财务", "DOM03", new String[]{"经费预算", "生活费", "科研费", "基本建设费"}),
            new Domain("采购供应", "DOM04", new String[]{"油料保障", "被装保障", "给养保障", "物资保障", "采购保障"}),
            new Domain("卫生勤务", "DOM05", new String[]{"医疗保健", "疾病预防控制", "医疗救护", "药材保障", "医疗设施", "卫勤人员"}),
            new Domain("运输投送", "DOM06", new String[]{"铁路运输", "公路运输", "水路运输", "航空运输", "海上补给"}),
            new Domain("设施建设", "DOM07", new String[]{"规划计划", "工程建设", "住房土地", "工程设施", "住房管理"}),
    };

    /** 条款 188 的三个主题。顺序就是 ads 建表的顺序。 */
    public static final String[] THEMES = {"后勤资源掌控", "综合分析", "辅助决策"};

    /** 条款 183 的十类对象维度，与 {@link LayerGen#DIM_PK} 同一套键。 */
    public static final String[] DIMS = {
            "dim_org", "dim_material", "dim_fund_account", "dim_facility", "dim_person",
            "dim_supply_standard", "dim_policy", "dim_geo", "dim_code_info", "dim_code_it",
    };

    /** 维度词：字段注释与字段名里出现这些词，就把该维度记成这张表的涉及对象。 */
    private static final String[][] DIM_WORDS = {
            {"dim_org", "机构 单位 部门 组织 科室 机关 部队 院所 学校 医院 站所 隶属"},
            {"dim_material", "物资 物料 器材 装备 资产 备件 油料 被装 给养 药品 耗材 品名 规格型号"},
            {"dim_fund_account", "经费 账户 账号 预算 科目 资金 财务 额度 拨款 结算"},
            {"dim_facility", "设施 设备 营房 场地 车辆 库房 仓库 机房 床位 病床 工程"},
            {"dim_person", "人员 姓名 员工 军官 士兵 文职 职工 干部 驾驶员 医师 护士 责任人 联系人 操作员"},
            {"dim_supply_standard", "标准 定额 供应标准 配发 规范 限额 基数"},
            {"dim_policy", "政策 法规 制度 条例 办法 规定 依据 文件号"},
            {"dim_geo", "地理 行政区划 位置 坐标 经度 纬度 地区 区域 地址 站点 地名 线路"},
            {"dim_code_info", "代码 编码 字典 分类 类别 代码表 目录 编号规则"},
            {"dim_code_it", "数据元 元数据 标准代码 国标 军标 信息分类 标识符"},
    };

    /** 领域词：没有系统先验时才用它数（先验命中就不再按词翻领域）。 */
    private static final String[][] DOMAIN_WORDS = {
            {"保障", "人员 实力 编制 兵员 军官 士兵 文职 职工 干部 花名册 名册 基础数据 地理 气象 "
                    + "水文 行政区划 站点 动态 实时 监测 态势 告警 决策 指挥 预案 任务 评估 战备 动员"},
            {"综合", "综合 储备 仓储 库存 科技 科研 技术 专利 成果 信息化 信息 网络 平台 政策 "
                    + "法规 制度 条例 标准规范 资产 数据目录"},
            {"财务", "财务 经费 预算 决算 会计 凭证 科目 工资 津贴 补贴 生活费 科研费 基建费 "
                    + "报销 发票 拨款 支付 结算"},
            {"采购供应", "采购 供应 招标 投标 供应商 合同 订单 油料 加油 油库 被装 服装 给养 食品 "
                    + "伙食 物资 库存 仓库 出入库 调拨 物流 器材"},
            {"卫生勤务", "卫生 医疗 疗养 保健 体检 疾病 防疫 疫苗 疫情 急救 救护 药品 药材 器械 "
                    + "耗材 医院 护士 医师 卫勤"},
            {"运输投送", "运输 投送 铁路 列车 车皮 公路 汽车 车辆 驾驶员 水路 船舶 码头 港口 航空 "
                    + "飞机 机场 航班 海上 补给 舰船 交通 路况"},
            {"设施建设", "设施 建设 工程 施工 监理 验收 进度 质量 土地 征地 地籍 用地 住房 营房 "
                    + "公寓 规划 立项 项目库 造价"},
    };

    /** 子领域词：判定先落在先验领域内，只在同一个领域的子领域之间细化。 */
    private static final String[][] SUB_WORDS = {
            {"基础数据", "基础 标准 字典 代码 地理 气象 水文 行政区划 站点 地名 天气 目录 名册 名录"},
            {"动态数据", "动态 实时 监测 状态 态势 告警 日志 流水 记录 轨迹 变化 路况 交通 客流 车流"},
            {"决策数据", "决策 指挥 方案 预案 计划 任务 指令 评估 分析 研判 战备 动员"},
            {"实力数据", "人员 实力 编制 兵员 军官 士兵 文职 职工 干部 花名册 名册 装备实力 名录"},
            {"物资储备", "储备 仓储 库存 粮 物资 资产 代储 承储 调运"},
            {"科技管理", "科技 科研 技术 专利 成果 课题 项目 人才 奖励"},
            {"信息化", "信息 系统 网络 数据 平台 设备 机房 安全 软件 运维 电子 目录 用户 权限 角色 菜单 参数 配置 字典 日志"},
            {"政策法规", "政策 法规 制度 条例 办法 规定 标准规范 依据"},
            {"经费预算", "预算 决算 经费 指标 拨款 支付 会计 凭证 科目 账 结算"},
            {"生活费", "生活 工资 津贴 补贴 福利 伙食 薪 保险 公积金"},
            {"科研费", "科研 课题 项目经费 研发"},
            {"基本建设费", "基建 建设 工程 投资 概算 结算 决算"},
            {"油料保障", "油 油料 加油 油库 油品 加油站 油罐 收发油"},
            {"被装保障", "被装 服装 鞋 帽 军需 装具 卧具"},
            {"给养保障", "给养 食品 伙食 粮 副食 饮水 给养器材"},
            {"物资保障", "物资 库存 仓库 器材 装备 出入库 调拨 物流 资产 仓储"},
            {"采购保障", "采购 招标 投标 供应商 合同 订单 竞价 商城 比价"},
            {"医疗保健", "疗养 保健 体检 健康 医疗 门诊 住院 医院 卫生队 康复"},
            {"疾病预防控制", "疾病 防疫 疫苗 传染病 疫情 预防 消杀 公共卫生"},
            {"医疗救护", "急救 救护 抢救 伤员 转运 急诊 院前"},
            {"药材保障", "药材 药品 器械 耗材 试剂 卫材 药房 药库"},
            {"医疗设施", "病床 病房 医疗设备 诊室 手术室 设施 床位"},
            {"卫勤人员", "医师 护士 医生 卫生员 卫勤 医务人员 专家"},
            {"铁路运输", "铁路 列车 车皮 火车站 轨道 军列"},
            {"公路运输", "公路 汽车 车辆 运输 驾驶员 道路 运力 车队"},
            {"水路运输", "水路 船舶 码头 港口 航道 船 江运 河运"},
            {"航空运输", "航空 飞机 机场 航班 空运 航线"},
            {"海上补给", "海上 补给 舰船 编队 远海 护航"},
            {"规划计划", "规划 计划 立项 审批 项目库 前期"},
            {"工程建设", "工程 施工 监理 验收 招投标 进度 质量 造价"},
            {"住房土地", "土地 征地 地籍 用地 房改 住房 军用土地"},
            {"工程设施", "设施 营房 场地 设备 维护 维修 改造 配套"},
            {"住房管理", "住房 公寓 宿舍 分配 物业 租金 腾退"},
    };

    /** 系统目录先验：源文件所在的一级目录通常就是一个业务系统。 */
    private static final String[][] SYSTEM_PRIORS = {
            {"人力资源", "保障", "实力数据"},
            {"人员保障", "保障", "实力数据"},
            {"装备实力", "保障", "实力数据"},
            {"军油", "采购供应", "油料保障"},
            {"给养", "采购供应", "给养保障"},
            {"被装", "采购供应", "被装保障"},
            {"采购", "采购供应", "采购保障"},
            {"仓库", "采购供应", "物资保障"},
            {"物流", "采购供应", "物资保障"},
            {"财务", "财务", "经费预算"},
            {"疗养", "卫生勤务", "医疗保健"},
            {"卫生器材", "卫生勤务", "药材保障"},
            {"军事设施建设", "设施建设", "工程建设"},
            {"运输投送", "运输投送", "公路运输"},
            {"作战指挥", "保障", "决策数据"},
            {"地方电子政务", "保障", "基础数据"},
            {"公共数据开放平台", "保障", "基础数据"},
            {"中国天气网", "保障", "基础数据"},
            {"现代资产", "综合", "物资储备"},
    };

    /** ods 物理表名前缀先验：读源库结构时没有目录，只能靠前缀认系统。 */
    private static final String[][] PREFIX_PRIORS = {
            {"ods_jygcxt", "军油工程", "采购供应", "油料保障"},
            {"ods_jdjyxt", "军队给养系统", "采购供应", "给养保障"},
            {"ods_bzywxxxt", "被装业务信息系统", "采购供应", "被装保障"},
            {"ods_jdcgw", "军队采购网", "采购供应", "采购保障"},
            {"ods_ckywzhglxt", "仓库业务综合管理系统", "采购供应", "物资保障"},
            {"ods_jdxdzcgljxdjswlxxxt", "军队现代资产管理及现代军事物流信息系统", "综合", "物资储备"},
            {"ods_jdcwxxxt", "军队财务信息系统", "财务", "经费预算"},
            {"ods_jdlyjhxxglxt", "军队疗养计划信息管理系统", "卫生勤务", "医疗保健"},
            {"ods_wsqcglxxxt", "卫生器材管理信息系统", "卫生勤务", "药材保障"},
            {"ods_jsssjszhglxt", "军事设施建设综合管理系统", "设施建设", "工程建设"},
            {"ods_ystsxt", "运输投送系统", "运输投送", "公路运输"},
            {"ods_zzzhxt", "部队作战指挥系统", "保障", "决策数据"},
            {"ods_dfdzzw", "地方电子政务信息系统", "保障", "基础数据"},
            {"ods_rlzyxt", "人力资源系统", "保障", "实力数据"},
            {"ods_jdbzkxt", "军队人员保障信息综合管理系统", "保障", "实力数据"},
            {"ods_zbslxt", "装备实力系统", "保障", "实力数据"},
    };

    /** 管道/系统表：字典、日志、账号、权限这一类不承载业务，统一归综合/信息化。 */
    private static final String[] META_WORDS = {
            "用户表", "角色", "权限", "菜单", "字典", "参数", "配置", "日志", "调度",
            "元数据", "系统表", "操作记录", "登录", "在线用户", "数据目录",
    };
    private static final String[] META_SUFFIX = {
            "_dict", "_log", "_config", "_user", "_role", "_menu", "_param", "_sys", "_catalog",
    };

    private Taxonomy() {
    }

    public static Domain domain(String name) {
        for (Domain d : DOMAINS) {
            if (d.name.equals(name)) {
                return d;
            }
        }
        return null;
    }

    public static Domain byCode(String code) {
        for (Domain d : DOMAINS) {
            if (d.code.equals(code)) {
                return d;
            }
        }
        return null;
    }

    public static boolean isDim(String dim) {
        for (String d : DIMS) {
            if (d.equals(dim)) {
                return true;
            }
        }
        return false;
    }

    /** 条款 188：先按领域分主题，保障的实力量/决策量进辅助决策，其余保障与综合进综合分析。 */
    public static String themeOf(String domain, String sub) {
        if ("保障".equals(domain)) {
            return ("实力数据".equals(sub) || "决策数据".equals(sub)) ? "辅助决策" : "综合分析";
        }
        if ("综合".equals(domain)) {
            return "综合分析";
        }
        return "后勤资源掌控";
    }

    /** ads 表名：三个条款主题给固定英文名，自定义主题按字母数字清洗。 */
    public static String themeTable(String theme) {
        if ("后勤资源掌控".equals(theme)) {
            return "ads_logistics_resource";
        }
        if ("综合分析".equals(theme)) {
            return "ads_analysis";
        }
        if ("辅助决策".equals(theme)) {
            return "ads_decision";
        }
        StringBuilder sb = new StringBuilder();
        String t = theme == null ? "" : theme.toLowerCase();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "ads_theme" : "ads_" + sb;
    }

    /**
     * 一源一表的领域目标表名：dwd_ + 子领域全拼 + "_" + 源表名。
     * 源表名清洗成 [a-z0-9_]，超长截断；领域/子领域只做前缀，不决定合并。
     */
    public static String targetOf(String sub, String source, Names names) {
        String prefix = "dwd_" + names.cnToTableName(sub, Names.TABLE_MAX_LEN);
        if (prefix.length() > 32) {
            prefix = prefix.substring(0, 32);
        }
        String suffix = clean(source);
        int room = Names.TABLE_MAX_LEN - prefix.length() - 1;
        if (suffix.isEmpty()) {
            suffix = "t";
        }
        if (suffix.length() > room) {
            suffix = suffix.substring(0, room);
            while (suffix.endsWith("_")) {
                suffix = suffix.substring(0, suffix.length() - 1);
            }
        }
        return prefix + "_" + (suffix.isEmpty() ? "t" : suffix);
    }

    /** 表名只留 [a-z0-9_]，连续非字母数字并成一个下划线，去掉首尾下划线。 */
    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        String v = s == null ? "" : s.toLowerCase();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 一条先验命中：领域、子领域、系统名（写进映射的「系统」列）与命中依据。 */
    public static final class Prior {
        public final String system;
        public final String domain;
        public final String sub;
        public final String via;
        Prior(String system, String domain, String sub, String via) {
            this.system = system;
            this.domain = domain;
            this.sub = sub;
            this.via = via;
        }
    }

    /**
     * 按系统目录与 ods 前缀找先验。目录先认（它比前缀更靠近这张表），
     * 目录没命中再认表名前缀；都没有返回 null，交给关键词判。
     */
    public static Prior prior(String system, String tableEn) {
        String sys = system == null ? "" : system;
        if (!sys.isEmpty()) {
            for (String[] r : SYSTEM_PRIORS) {
                if (sys.contains(r[0])) {
                    return new Prior(sys, r[1], r[2], "系统目录「" + sys + "」");
                }
            }
        }
        String en = tableEn == null ? "" : tableEn.toLowerCase();
        for (String[] r : PREFIX_PRIORS) {
            if (en.startsWith(r[0])) {
                return new Prior(sys.isEmpty() ? r[1] : sys, r[2], r[3],
                        "表名前缀「" + r[0] + "」");
            }
        }
        return null;
    }

    /** 系统表判定：名字一看就是管道表，不给它猜业务领域。 */
    public static boolean isMeta(String tableCn, String tableEn) {
        String cn = tableCn == null ? "" : tableCn;
        for (String w : META_WORDS) {
            if (cn.contains(w)) {
                return true;
            }
        }
        String en = tableEn == null ? "" : tableEn.toLowerCase();
        for (String s : META_SUFFIX) {
            if (en.endsWith(s) || en.contains(s + "_")) {
                return true;
            }
        }
        return false;
    }

    /** 关键词表里的一行（找不到返回 null）。 */
    private static String[] words(String[][] table, String key) {
        for (String[] row : table) {
            if (row[0].equals(key)) {
                return row[1].split("\\s+");
            }
        }
        return null;
    }

    /** 某个领域在四段文字里命中的加权分：表注释 3、文件名 2、系统名 1、字段注释 1。 */
    public static int scoreDomain(String domain, String tableCn, String stem, String system,
                                  String fields) {
        String[] ws = words(DOMAIN_WORDS, domain);
        return ws == null ? 0 : score(ws, tableCn, stem, system, fields);
    }

    /** 某个子领域在同一套权重下的分数。 */
    public static int scoreSub(String sub, String tableCn, String stem, String system,
                               String fields) {
        String[] ws = words(SUB_WORDS, sub);
        return ws == null ? 0 : score(ws, tableCn, stem, system, fields);
    }

    private static int score(String[] ws, String tableCn, String stem, String system,
                             String fields) {
        int s = 0;
        for (String w : ws) {
            s += 3 * hits(tableCn, w) + 2 * hits(stem, w) + hits(system, w) + hits(fields, w);
        }
        return s;
    }

    /** 子领域里第一个命中的词，用来写依据（先表注释、再文件名、再字段）；没命中返回 null。 */
    public static String subHit(String sub, String tableCn, String stem, String system,
                                String fields) {
        String[] ws = words(SUB_WORDS, sub);
        if (ws == null) {
            return null;
        }
        for (String text : new String[]{tableCn, stem, system, fields}) {
            for (String w : ws) {
                if (hits(text, w) > 0) {
                    return w;
                }
            }
        }
        return null;
    }

    /**
     * 十类对象维度：字段注释权重 2、字段名权重 1（英文名里的 person/org 也是证据），
     * 同分按条款 183 的维度顺序；最多取三个，一个都没有返回空表。
     */
    public static List<String> dims(List<String> comments, List<String> names, int max) {
        String cn = join(comments);
        String en = join(names).toLowerCase();
        List<String> picked = new ArrayList<String>();
        List<Integer> scores = new ArrayList<Integer>();
        for (String[] row : DIM_WORDS) {
            int s = 0;
            for (String w : row[1].split("\\s+")) {
                s += 2 * hits(cn, w) + hits(en, w);
            }
            if (s <= 0) {
                continue;
            }
            int at = picked.size();
            for (int i = 0; i < picked.size(); i++) {
                if (s > scores.get(i).intValue()) {
                    at = i;
                    break;
                }
            }
            picked.add(at, row[0]);
            scores.add(at, Integer.valueOf(s));
        }
        return picked.size() > max ? new ArrayList<String>(picked.subList(0, max)) : picked;
    }

    private static int hits(String text, String word) {
        if (text == null || text.isEmpty() || word == null || word.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = text.indexOf(word);
        while (i >= 0) {
            n++;
            i = text.indexOf(word, i + word.length());
        }
        return n;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String s : values) {
            sb.append(s == null ? "" : s);
        }
        return sb.toString();
    }
}
