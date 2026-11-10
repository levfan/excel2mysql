package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从文件名与表内元数据判每张表属于哪个领域——第四页免配映射模板的那条路。
 *
 * <p>判据只有一条：数业务名词。表注释、文件名、压缩包名、字段注释里各数一遍，权重按
 * "这串字离这张表有多近"排：表注释 3、文件名 2、包名 1、字段注释 1。字段注释权重最低
 * 不是因为它不重要，而是它说的是这张表牵涉哪些对象（机构、设备、时间），
 * 拿它当领域会把"人员设备台账"判成设备。
 *
 * <p>数不到名词就不猜：那张表标成待确认，日志逐表点名，绝不塞进某个领域凑数。
 * 同一名词在多张表上重复出现时共用一个领域码与一张领域目标表——分组是这条路的产物，
 * 不是每张表各起一个领域。
 *
 * <p>名词表是通用业务名词，不绑某个客户的项目；判不准的那几张留给「载入映射文件」人工改。
 */
public final class DomainGuess {

    /** 一个待判的表：文件名与表内元数据，全部离线可得，不连库。 */
    public static final class Input {
        public String fileName = "";
        /** 来自 ZIP 时写包名；散装文件为空。 */
        public String system = "";
        public String tableCn = "";
        public String tableEn = "";
        public int fieldCount;
        public String pkField = "";
        public List<String> fieldComments = new ArrayList<String>();
    }

    /** 一张表的判定结果：映射行 + 判没判出来 + 依据那句话。 */
    public static final class Verdict {
        public final MappingFile.TableMap map = new MappingFile.TableMap();
        public boolean guessed;
        public String basis = "";
        public String domain = "";
    }

    /** 业务名词表。顺序不参与优先级，只用于同分时给出确定的先后。 */
    private static final String[] NOUNS = {
            "人员", "员工", "职工", "用户", "账号", "角色", "权限", "部门", "机构", "组织",
            "岗位", "客户", "供应商", "合同", "订单", "采购", "招标", "库存", "出入库", "物料",
            "设备", "资产", "项目", "工程", "任务", "审批", "流程", "财务", "费用", "发票",
            "报销", "工资", "考勤", "排班", "水库", "河道", "河段", "站点", "监测", "雨量",
            "水位", "流量", "告警", "日志", "字典", "配置", "报表", "统计", "教学", "学生",
            "教师", "课程", "成绩", "病历", "患者", "药品", "诊断", "车辆", "能耗", "安全",
    };

    /** 表注释 / 文件名 / 包名 / 字段注释 各自的权重。 */
    private static final int W_COMMENT = 3;
    private static final int W_FILE = 2;
    private static final int W_SYSTEM = 1;
    private static final int W_FIELD = 1;
    /** 涉及对象维度最多记三个：再多这张 dim 清单就没法一眼看完。 */
    private static final int MAX_DIMS = 3;

    private DomainGuess() {
    }

    /** 一批表 → 一批映射行；领域码与领域目标表按首次出现的顺序稳定分配。 */
    public static List<Verdict> guess(List<Input> inputs, Names names) {
        Map<String, String> codeByDomain = new LinkedHashMap<String, String>();
        Map<String, String> targetByDomain = new LinkedHashMap<String, String>();
        List<Verdict> out = new ArrayList<Verdict>();
        for (Input in : inputs) {
            out.add(one(in, names, codeByDomain, targetByDomain));
        }
        return out;
    }

    private static Verdict one(Input in, Names names, Map<String, String> codeByDomain,
                               Map<String, String> targetByDomain) {
        Verdict v = new Verdict();
        String stem = stem(in.fileName);
        String flatFields = join(in.fieldComments);
        int bestScore = 0;
        String best = null;
        int bestAt = Integer.MAX_VALUE;
        for (String noun : NOUNS) {
            int score = W_COMMENT * hits(in.tableCn, noun)[0]
                    + W_FILE * hits(stem, noun)[0]
                    + W_SYSTEM * hits(in.system, noun)[0]
                    + W_FIELD * hits(flatFields, noun)[0];
            if (score <= 0) {
                continue;
            }
            // 同分先取在表注释里出现更早的那个；表注释里没有就看文件名，再看名词表顺序
            int at = position(in.tableCn, stem, noun);
            if (score > bestScore || (score == bestScore && at < bestAt)) {
                bestScore = score;
                best = noun;
                bestAt = at;
            }
        }
        v.map.system = nz(in.system);
        v.map.tableCn = in.tableCn == null || in.tableCn.trim().isEmpty() ? stem : in.tableCn.trim();
        v.map.tableEn = nz(in.tableEn);
        v.map.fieldCount = String.valueOf(in.fieldCount);
        v.map.pkField = nz(in.pkField);
        v.map.note = "文件名 " + (in.fileName == null || in.fileName.isEmpty() ? "（无）" : in.fileName);
        v.map.odsPhysical = nz(in.tableEn);
        if (best == null) {
            v.guessed = false;
            v.basis = "文件名、表注释与字段注释里都没有可识别的业务名词";
            v.map.domain = "";
            v.map.domainCode = "";
            v.map.domainSub = "";
            v.map.domainTarget = "";
            v.map.objDims = "";
            v.map.theme = "";
            v.map.basis = v.basis;
            v.map.status = "待确认";
            return v;
        }
        String code = codeByDomain.get(best);
        if (code == null) {
            code = String.format("DOM%02d", Integer.valueOf(codeByDomain.size() + 1));
            codeByDomain.put(best, code);
        }
        String target = targetByDomain.get(best);
        if (target == null) {
            target = "dwd_" + names.cnToTableName(best, Names.TABLE_MAX_LEN);
            targetByDomain.put(best, target);
        }
        v.guessed = true;
        v.domain = best;
        v.basis = evidence(in, stem, flatFields, best);
        v.map.domain = best;
        v.map.domainCode = code;
        v.map.domainSub = v.map.tableCn;
        v.map.domainTarget = target;
        v.map.objDims = dims(flatFields, best, names);
        v.map.theme = best + "主题";
        v.map.basis = v.basis;
        v.map.status = "自动识别";
        return v;
    }

    /** 涉及对象维度：字段注释里除领域本身之外最常被提到的几个名词。 */
    private static String dims(String flatFields, String domain, Names names) {
        List<String> picked = new ArrayList<String>();
        List<Integer> scores = new ArrayList<Integer>();
        List<Integer> order = new ArrayList<Integer>();
        for (String noun : NOUNS) {
            if (noun.equals(domain)) {
                continue;
            }
            int[] h = hits(flatFields, noun);
            if (h[0] <= 0) {
                continue;
            }
            int at = picked.size();
            for (int i = 0; i < picked.size(); i++) {
                if (h[0] > scores.get(i).intValue()
                        || (h[0] == scores.get(i).intValue() && h[1] < order.get(i).intValue())) {
                    at = i;
                    break;
                }
            }
            picked.add(at, noun);
            scores.add(at, Integer.valueOf(h[0]));
            order.add(at, Integer.valueOf(h[1]));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < picked.size() && i < MAX_DIMS; i++) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append("dim_").append(names.cnToTableName(picked.get(i), Names.TABLE_MAX_LEN));
        }
        return sb.toString();
    }

    /** 依据那句话：谁在哪个来源里被数到几次，写出来才核得动。 */
    private static String evidence(Input in, String stem, String flatFields, String noun) {
        List<String> parts = new ArrayList<String>();
        addHit(parts, "表注释", in.tableCn, noun);
        addHit(parts, "文件名", stem, noun);
        addHit(parts, "压缩包名", in.system, noun);
        addHit(parts, "字段注释", flatFields, noun);
        return parts.isEmpty() ? "按「" + noun + "」归类" : String.join("；", parts);
    }

    private static void addHit(List<String> parts, String where, String text, String noun) {
        int[] h = hits(text, noun);
        if (h[0] > 0) {
            parts.add("按" + where + "命中「" + noun + "」×" + h[0]);
        }
    }

    /** 出现次数与首次出现的位置（没出现返回 {0, -1}）。 */
    private static int[] hits(String text, String noun) {
        if (text == null || text.isEmpty()) {
            return new int[]{0, -1};
        }
        int n = 0;
        int first = -1;
        int i = text.indexOf(noun);
        while (i >= 0) {
            n++;
            if (first < 0) {
                first = i;
            }
            i = text.indexOf(noun, i + noun.length());
        }
        return new int[]{n, first};
    }

    /** 同分时的先后：表注释里更靠前的算赢；表注释里没有就看文件名；都没有就排到最后。 */
    private static int position(String tableCn, String stem, String noun) {
        int[] a = hits(tableCn, noun);
        if (a[1] >= 0) {
            return a[1];
        }
        int[] b = hits(stem, noun);
        if (b[1] >= 0) {
            return 1000 + b[1];
        }
        return 2000;
    }

    /** 日志里那一句：判出来与判不出两种说法，都带上依据。 */
    public static String describe(Verdict v) {
        if (!v.guessed) {
            return v.map.tableEn + " 判不出领域：" + v.basis;
        }
        return v.map.tableEn + " → 领域「" + v.domain + "」（" + v.map.domainCode
                + "，" + v.map.domainTarget + "）| " + v.basis;
    }

    private static String stem(String fileName) {
        String s = fileName == null ? "" : fileName.trim();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String s : values) {
            sb.append(s == null ? "" : s);
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
