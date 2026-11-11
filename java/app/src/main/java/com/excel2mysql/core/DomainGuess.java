package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 给一批源表判 obj/dom/ads 的归属——第四页免配映射模板、以及读源库结构后自动映射的那条路。
 *
 * <p>判法分三步，越靠前越硬：
 * <ol>
 *   <li>系统先验：源文件所在的一级目录（或 ods 物理表名前缀）先定领域与子领域；
 *       目录名带年份版本号也照样命中（<code>运输投送系统2.0</code>）。</li>
 *   <li>表名细化：先验领域内再按表注释、文件名数子领域词，把「仓库业务系统」里的被装表
 *       从默认的物资保障细化到被装保障；领域本身不因表名来回翻，避免同一系统的表
 *       被拆到互不相关的领域。</li>
 *   <li>没有先验时才按条款 184 的领域词计数判领域；一个领域词都数不到就标待确认，
 *       由人补映射文件，不塞进某个领域凑数。</li>
 * </ol>
 *
 * <p>对象维度只认条款 183 的十类（{@link LayerGen#DIM_PK} 那套键），从字段注释与字段名里取；
 * 不再拼拼音 dim 名——生成器只认这十个键，拼出来的名字会静默丢掉整张维表。
 *
 * <p>自动识别按「一源一表」落 dom：每张源表指定一张自己的领域目标表
 * <code>dwd_&lt;子领域首字母&gt;_&lt;源表名&gt;</code>（基础数据 → jcsj）。领域/子领域/主题只写进映射做分类，
 * 不拿它当物理合并键——几十张异构表按领域 UNION 成一张，列数会越过 MySQL 上限，
 * 业务粒度也混在一起。要合并只能由人在映射里显式写同一个「领域目标表」来声明。
 */
public final class DomainGuess {

    /** 一个待判的表：文件名、系统/目录、表内元数据与源列，全部离线可得，不连库。 */
    public static final class Input {
        public String fileName = "";
        /** 源文件所在的一级目录名；散装文件为空。写进映射的「系统」列，也用来找先验。 */
        public String system = "";
        public String tableCn = "";
        public String tableEn = "";
        public int fieldCount;
        public String pkField = "";
        public List<String> fieldComments = new ArrayList<String>();
        public List<String> fieldNames = new ArrayList<String>();
    }

    /** 一张表的判定结果：映射行 + 判没判出来 + 依据 + 需要汇总提醒的那句话。 */
    public static final class Verdict {
        public final MappingFile.TableMap map = new MappingFile.TableMap();
        public boolean guessed;
        public String basis = "";
        public String domain = "";
        public String warning = "";
    }

    private static final int MAX_DIMS = 3;

    private DomainGuess() {
    }

    /** 一批表 → 一批映射行；目标表一源一张，随源表走。 */
    public static List<Verdict> guess(List<Input> inputs, Names names) {
        List<Cls> cls = new ArrayList<Cls>();
        List<Verdict> out = new ArrayList<Verdict>();
        for (Input in : inputs) {
            Cls c = classify(in);
            cls.add(c);
            out.add(row(in, c));
        }
        assignTargets(inputs, out, cls, names);
        return out;
    }

    /** 判定中间态：领域、子领域、依据与状态。 */
    private static final class Cls {
        String domain = "";
        String sub = "";
        String system = "";
        String basis = "";
        String status = "待确认";
        String warning = "";
        List<String> dims = new ArrayList<String>();
        boolean guessed;
    }

    private static Cls classify(Input in) {
        Cls c = new Cls();
        c.system = nz(in.system);
        String stem = stem(in.fileName);
        // 字段注释之外把字段名也算进来：英文列名（person_no、fuel_qty）是判领域/子领域的真证据，
        // 只读注释会把一整张纯英文列名的表判成待确认
        String fields = join(in.fieldComments) + " " + join(in.fieldNames);
        String cn = nz(in.tableCn).isEmpty() ? stem : in.tableCn.trim();

        if (Taxonomy.isMeta(cn, in.tableEn)) {
            c.domain = "综合";
            c.sub = "信息化";
            c.guessed = true;
            c.status = "自动识别";
            c.basis = "系统表（字典/日志/账号一类）归综合/信息化";
            return c;
        }

        Taxonomy.Prior p = Taxonomy.prior(in.system, in.tableEn);
        if (p != null) {
            c.system = p.system;
            c.domain = p.domain;
            c.sub = p.sub;
            c.guessed = true;
            c.status = "自动识别";
            c.basis = p.via + "先验归领域「" + p.domain + "」/「" + p.sub + "」";
            String best = null;
            int bestScore = 0;
            Taxonomy.Domain d = Taxonomy.domain(p.domain);
            if (d != null) {
                for (String sub : d.subs) {
                    // 先验已经说了系统，细化只看这张表自己的名字与字段，免得系统名又把默认档数回来
                    int s = Taxonomy.scoreSub(sub, cn, stem, "", fields);
                    if (s > bestScore) {
                        bestScore = s;
                        best = sub;
                    }
                }
            }
            if (best != null && !best.equals(p.sub)) {
                String hit = Taxonomy.subHit(best, cn, stem, "", fields);
                c.sub = best;
                c.basis += "；表名/字段命中「" + hit + "」细化到「" + best + "」";
            }
            c.dims = Taxonomy.dims(in.fieldComments, in.fieldNames, MAX_DIMS);
            if (c.dims.isEmpty()) {
                c.warning = in.tableEn + " 未见十类对象维度关键词，obj 层不产这张表";
            }
            return c;
        }

        String bestDomain = null;
        int bestScore = 0;
        for (Taxonomy.Domain d : Taxonomy.DOMAINS) {
            int s = Taxonomy.scoreDomain(d.name, cn, stem, in.system, fields);
            if (s > bestScore) {
                bestScore = s;
                bestDomain = d.name;
            }
        }
        if (bestDomain == null) {
            c.domain = "";
            c.sub = "";
            c.guessed = false;
            c.status = "待确认";
            c.basis = "文件名、表注释与字段注释里都没有可识别的业务名词";
            c.warning = in.tableEn + " 判不出领域（" + c.basis + "）";
            return c;
        }
        c.domain = bestDomain;
        c.guessed = true;
        c.status = "自动识别";
        String bestSub = null;
        int bestSubScore = 0;
        Taxonomy.Domain d = Taxonomy.domain(bestDomain);
        for (String sub : d.subs) {
            int s = Taxonomy.scoreSub(sub, cn, stem, in.system, fields);
            if (s > bestSubScore) {
                bestSubScore = s;
                bestSub = sub;
            }
        }
        if (bestSub == null) {
            bestSub = d.subs[0];
            c.status = "待确认";
            c.warning = in.tableEn + " 领域判到「" + bestDomain + "」，但子领域无词可依，暂按「"
                    + bestSub + "」，请核对";
        }
        c.sub = bestSub;
        String hit = Taxonomy.subHit(bestSub, cn, stem, in.system, fields);
        c.basis = "按表注释/文件名/字段注释数条款 184 领域词（" + bestScore
                + " 分）归「" + bestDomain + "」"
                + (hit == null ? "" : "，子领域命中「" + hit + "」");
        c.dims = Taxonomy.dims(in.fieldComments, in.fieldNames, MAX_DIMS);
        if (c.dims.isEmpty()) {
            c.warning = in.tableEn + " 未见十类对象维度关键词，obj 层不产这张表";
        }
        return c;
    }

    private static Verdict row(Input in, Cls c) {
        Verdict v = new Verdict();
        v.guessed = c.guessed;
        v.domain = c.domain;
        v.basis = c.basis;
        v.warning = c.warning;
        v.map.system = nz(c.system);
        v.map.tableCn = nz(in.tableCn).isEmpty() ? stem(in.fileName) : in.tableCn.trim();
        v.map.tableEn = nz(in.tableEn);
        v.map.fieldCount = String.valueOf(in.fieldCount);
        v.map.pkField = nz(in.pkField);
        v.map.odsPhysical = nz(in.tableEn);
        v.map.note = "文件名 " + (in.fileName == null || in.fileName.isEmpty() ? "（无）" : in.fileName);
        v.map.status = c.status;
        v.map.basis = c.basis;
        if (!c.guessed) {
            v.map.domain = "";
            v.map.domainCode = "";
            v.map.domainSub = "";
            v.map.domainTarget = "";
            v.map.objDims = "";
            v.map.theme = "";
            return v;
        }
        Taxonomy.Domain d = Taxonomy.domain(c.domain);
        v.map.domain = c.domain;
        v.map.domainCode = d == null ? "" : d.code;
        v.map.domainSub = c.sub;
        v.map.objDims = joinNonEmpty(c.dims, ";");
        v.map.theme = Taxonomy.themeOf(c.domain, c.sub);
        return v;
    }

    /**
     * 一源一表：目标表随源表走，不按（领域，子领域）合并。
     * 领域/子领域只决定表名前缀；同名源表（不同目录）撞名时追加 _2、_3。
     */
    private static void assignTargets(List<Input> inputs, List<Verdict> outs, List<Cls> cls,
                                      Names names) {
        Set<String> used = new LinkedHashSet<String>();
        for (int i = 0; i < outs.size(); i++) {
            if (!cls.get(i).guessed) {
                continue;
            }
            Verdict v = outs.get(i);
            String source = nz(inputs.get(i).tableEn);
            if (source.isEmpty()) {
                source = "t" + (i + 1);
            }
            String target = unique(Taxonomy.targetOf(cls.get(i).sub, source, names), used);
            v.map.domainTarget = target;
            v.basis += "；自动识别一源一表，落 " + target;
            v.map.basis = v.basis;
        }
    }

    /** 目标表撞名时追加序号；追加后仍守住表名长度上限。 */
    private static String unique(String base, Set<String> used) {
        String t = base;
        int n = 2;
        while (!used.add(t)) {
            String suffix = "_" + n;
            int room = Names.TABLE_MAX_LEN - suffix.length();
            t = (base.length() > room ? base.substring(0, room) : base) + suffix;
            n++;
        }
        return t;
    }

    /** 日志里那一句：判出来与判不出两种说法，都带上依据。 */
    public static String describe(Verdict v) {
        if (!v.guessed) {
            return v.map.tableEn + " 判不出领域：" + v.basis;
        }
        return v.map.tableEn + " → 领域「" + v.domain + "」/「" + v.map.domainSub + "」（"
                + v.map.domainCode + "，" + v.map.domainTarget + "）| " + v.basis;
    }

    /** 一条能贴进日志/弹窗的汇总：判出多少、几个领域、几张待确认。 */
    public static String summary(List<Verdict> vs) {
        int guessed = 0;
        int pending = 0;
        int noDim = 0;
        Set<String> groups = new LinkedHashSet<String>();
        Set<String> targets = new LinkedHashSet<String>();
        Set<String> domains = new LinkedHashSet<String>();
        for (Verdict v : vs) {
            if (v.guessed) {
                guessed++;
                domains.add(v.domain);
                groups.add(v.domain + "/" + v.map.domainSub);
                if (v.map.domainTarget != null && !v.map.domainTarget.isEmpty()) {
                    targets.add(v.map.domainTarget);
                }
            } else {
                pending++;
            }
            if (v.warning != null && v.warning.contains("未见十类对象维度")) {
                noDim++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(vs.size()).append(" 张：判出 ").append(guessed)
          .append(" 张（").append(domains.size()).append(" 个领域、").append(groups.size())
          .append(" 个领域子类；一源一表，共 ").append(targets.size()).append(" 张领域表），待确认 ")
          .append(pending).append(" 张");
        if (noDim > 0) {
            sb.append("；").append(noDim).append(" 张未见十类对象维度（obj 不产）");
        }
        return sb.toString();
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

    private static String joinNonEmpty(List<String> values, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : values) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
