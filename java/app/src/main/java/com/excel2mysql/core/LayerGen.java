package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按映射＋源结构产 obj/dom/ads 三层 DDL、转换 SQL、字段映射。离线逻辑与连库 I/O 分离。 */
public final class LayerGen {

    private LayerGen() { }

    public enum Layer { OBJ, DOM, ADS }

    public static final class SourceTable {
        public final String name;
        public final String comment;
        public final List<Infer.Column> columns;
        public SourceTable(String name, String comment, List<Infer.Column> columns) {
            this.name = name; this.comment = comment; this.columns = columns;
        }
    }

    public static final class GenOptions {
        public String sourceDb = "ods";
        public String objDb = "obj";
        public String domDb = "dom";
        public String adsDb = "ads";
        public boolean writeMode = false;
        public String batchId = "@src_batch_id";
    }

    public static final class LayerOutput {
        public Layer layer;
        public String ddlSql = "";
        public String transformSql = "";
        public List<MappingFile.FieldMap> fieldMapping = new ArrayList<MappingFile.FieldMap>();
        public int tableCount;
        public List<String> warnings = new ArrayList<String>();
        public List<String> unbound = new ArrayList<String>();
    }

    /**
     * 显式合并的护栏：映射里多行写同一个领域目标表才算合并，来源数或列数超过这两档就拒绝。
     * 条款 184 的领域/子领域是分类，不是物理合并键；几十张异构表 UNION 成一张宽表
     * 既过不了 MySQL 上限，也把业务粒度混在一起。
     */
    public static final int MAX_MERGE_SOURCES = 10;
    public static final int MAX_MERGE_COLUMNS = 500;

    /** 本地 InnoDB 16K 页的行上限体检口径。 */
    private static final int DOM_PAGE_SIZE = 16384;

    /** 一个显式同目标的合并计划：映射行、去重源表、目标列与拒绝原因。 */
    private static final class DomGroup {
        final String target;
        final List<MappingFile.TableMap> rows = new ArrayList<MappingFile.TableMap>();
        final List<SourceTable> sources = new ArrayList<SourceTable>();
        final List<Infer.Column> cols = new ArrayList<Infer.Column>();
        boolean multi;
        boolean entityIdFromSource;
        String refuse = "";
        DomGroup(String target) { this.target = target; }
    }

    /** 表 5-3：obj/dom 通用治理字段。 */
    public static final String[] GOV_OBJ_DOM = {
            "entity_id", "src_entity_id", "std_code", "src_code", "tags", "data_level",
            "mask_policy", "quality_flag", "quality_msg", "de_map_id", "src_batch_id",
            "merge_rule_id", "update_time", "valid_flag"
    };
    /** 表 6-4：ads 通用字段。 */
    public static final String[] GOV_ADS = {
            "stat_date", "stat_grain", "dim_key_json", "idx_code", "idx_value", "idx_unit",
            "calc_model_id", "caliber_ver", "src_row_cnt", "excluded_row_cnt", "etl_batch_id",
            "quality_flag"
    };
    /** 治理字段的交付注释：每个字段说清它承载什么，别在交付表里只留一句「治理字段」。 */
    private static final Map<String, String> GOV_COMMENT = new LinkedHashMap<String, String>();
    private static final Set<String> GOV_NAMES = new LinkedHashSet<String>();
    static {
        GOV_COMMENT.put("entity_id", "实体唯一标识，每行由 UUID() 生成");
        GOV_COMMENT.put("src_entity_id", "源表主键值（源表无主键时取首列），用于回溯源记录");
        GOV_COMMENT.put("std_code", "标准代码，源表名加源主键（无主键时取首列）合成，待赋正式码");
        GOV_COMMENT.put("src_code", "源系统业务编码，取源表主键（无主键时取首列）");
        GOV_COMMENT.put("tags", "来源标签，记录本行来自哪张源表");
        GOV_COMMENT.put("data_level", "数据密级");
        GOV_COMMENT.put("mask_policy", "脱敏策略");
        GOV_COMMENT.put("quality_flag", "质量标识");
        GOV_COMMENT.put("quality_msg", "质量说明");
        GOV_COMMENT.put("de_map_id", "数据元映射标识，目标表名");
        GOV_COMMENT.put("src_batch_id", "源批次号");
        GOV_COMMENT.put("merge_rule_id", "归集规则，一源一表或多源 UNION");
        GOV_COMMENT.put("update_time", "数据写入时间");
        GOV_COMMENT.put("valid_flag", "有效标志，1 有效");
        GOV_COMMENT.put("stat_date", "统计日期");
        GOV_COMMENT.put("stat_grain", "统计粒度");
        GOV_COMMENT.put("dim_key_json", "维度键，JSON 记录领域目标表");
        GOV_COMMENT.put("idx_code", "指标编码，取源字段名");
        GOV_COMMENT.put("idx_value", "指标值，按字段口径聚合");
        GOV_COMMENT.put("idx_unit", "指标单位");
        GOV_COMMENT.put("calc_model_id", "计算模型标识");
        GOV_COMMENT.put("caliber_ver", "统计口径版本");
        GOV_COMMENT.put("src_row_cnt", "源记录数");
        GOV_COMMENT.put("excluded_row_cnt", "剔除记录数");
        GOV_COMMENT.put("etl_batch_id", "ETL 批次号");
        GOV_NAMES.addAll(GOV_COMMENT.keySet());
    }

    /** ads 每张领域表最多落的指标数：几百个数值列全摊成 INSERT 会让交付 SQL 无法阅读。 */
    public static final int MAX_ADS_MEASURES = 20;

    /** 表 5-1：十类维度建议主键。 */
    public static final Map<String, String> DIM_PK = new LinkedHashMap<String, String>();
    static {
        DIM_PK.put("dim_org", "org_id");
        DIM_PK.put("dim_material", "material_id");
        DIM_PK.put("dim_fund_account", "account_id");
        DIM_PK.put("dim_facility", "facility_id");
        DIM_PK.put("dim_person", "person_id");
        DIM_PK.put("dim_supply_standard", "standard_id");
        DIM_PK.put("dim_policy", "policy_id");
        DIM_PK.put("dim_geo", "geo_id");
        DIM_PK.put("dim_code_info", "code_id");
        DIM_PK.put("dim_code_it", "std_id");
    }

    public static String qualify(String db, String table) {
        return "`" + db + "`.`" + table + "`";
    }

    public static LayerOutput genLayer(Layer layer, MappingFile m, List<SourceTable> src, GenOptions o) {
        switch (layer) {
            case OBJ: return genObj(m, src, o);
            case DOM: return genDom(m, src, o);
            case ADS: return genAds(m, src, o);
            default: throw new IllegalArgumentException("未知层：" + layer);
        }
    }

    private static Map<String, SourceTable> byName(List<SourceTable> src) {
        Map<String, SourceTable> map = new LinkedHashMap<String, SourceTable>();
        for (SourceTable t : src) { map.put(t.name, t); }
        return map;
    }

    private static SourceTable bind(MappingFile.TableMap t, Map<String, SourceTable> map) {
        if (t.odsPhysical != null && !t.odsPhysical.isEmpty() && map.containsKey(t.odsPhysical)) {
            return map.get(t.odsPhysical);
        }
        return map.get(t.tableEn);
    }

    // ---- dom：一源一表；只有映射里显式写同一个领域目标表才合并 ----
    private static LayerOutput genDom(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.DOM;
        Map<String, SourceTable> map = byName(src);
        StringBuilder ddl = new StringBuilder("USE `" + o.domDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.domDb + "`;\n\n");
        for (DomGroup g : planDom(m, src, out)) {
            if (!g.refuse.isEmpty()) {
                out.warnings.add("领域目标表 " + g.target + " 拒绝生成：" + g.refuse);
                continue;
            }
            // 显式合并才走多源路：缺列的源那一支只能插 NULL，所以源侧 NOT NULL 不携带、
            // 各源主键不并成复合主键，主键只归治理字段 entity_id（每行 UUID() 生成）。
            // 单源目标（一源一表）保持原样：源主键照旧携带，不做任何改变。
            if (g.entityIdFromSource) {
                out.warnings.add("多源合并表 " + g.target + " 有源列名为 entity_id、与治理字段撞名："
                        + "主键 entity_id 将由 UUID() 生成并覆盖源值（待核，186 溯源）");
            }
            ddl.append(Ddl.buildDdl(g.target, g.target, g.cols, true)).append(";\n\n");
            for (MappingFile.TableMap t : g.rows) {
                SourceTable s = bind(t, map);
                // dom 只由治理字段生成键：entity_id 由 UUID() 出，src_entity_id/src_code/std_code 回指源主键（条款 186）
                ColExpr x = new ColExpr(s, g.multi ? "entity_id" : null, firstPk(s), o,
                        g.target, g.multi);
                dml.append(domInsert(g.target, g.cols, x)).append("\n\n");
                addFieldMaps(out, s, g.target, g.cols, x);
            }
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        return out;
    }

    /** 把映射行按「领域目标表」归并，并逐组量合并护栏；不做任何落盘或改类型。 */
    private static List<DomGroup> planDom(MappingFile m, List<SourceTable> src, LayerOutput out) {
        Map<String, SourceTable> map = byName(src);
        Map<String, DomGroup> groups = new LinkedHashMap<String, DomGroup>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.domainTarget == null || t.domainTarget.isEmpty()) { continue; }
            SourceTable s = bind(t, map);
            if (s == null) { out.unbound.add(t.tableEn); continue; }
            DomGroup g = groups.get(t.domainTarget);
            if (g == null) { g = new DomGroup(t.domainTarget); groups.put(t.domainTarget, g); }
            g.rows.add(t);
            if (!g.sources.contains(s)) { g.sources.add(s); }
        }
        List<DomGroup> outGroups = new ArrayList<DomGroup>(groups.values());
        int dropped = 0;
        List<String> droppedSample = new ArrayList<String>();
        for (DomGroup g : outGroups) {
            g.multi = g.sources.size() > 1;
            Set<String> seen = new LinkedHashSet<String>();
            for (SourceTable s : g.sources) {
                for (Infer.Column c : s.columns) {
                    if (!seen.add(c.field)) {
                        continue;
                    }
                    // 治理字段名归生成器；判不出类别、或一看就是系统审计列的不进层——
                    // 硬塞进去只会把真正的业务列挤出 500 列上限，注释还只能写「治理字段」
                    if (isGovField(c.field) || !FieldClass.keep(c)) {
                        dropped++;
                        if (droppedSample.size() < 6) {
                            droppedSample.add(s.name + "." + c.field);
                        }
                        continue;
                    }
                    g.cols.add(g.multi ? copyMerged(c) : copyCol(c, c.primaryKey));
                }
            }
            g.entityIdFromSource = g.multi && seen.contains("entity_id");
            addGovernance(g.cols, GOV_OBJ_DOM);
            if (g.multi) { markEntityIdPk(g.cols); }
            g.refuse = refuseReason(g);
        }
        if (dropped > 0) {
            out.warnings.add("dom 跳过 " + dropped + " 个未归类或系统字段（示例："
                    + join(droppedSample, "、") + "）");
        }
        return outGroups;
    }

    /** 合并护栏：来源数、总列数与行字节三条线，任何一条越线都拒绝这一组。 */
    private static String refuseReason(DomGroup g) {
        String names = sourceNames(g.sources);
        if (g.multi && g.sources.size() > MAX_MERGE_SOURCES) {
            return "由 " + g.sources.size() + " 张源表显式合并，超过 " + MAX_MERGE_SOURCES
                    + " 张上限；请拆成多个领域目标表。源表：" + names;
        }
        if (g.cols.size() > MAX_MERGE_COLUMNS) {
            return "合并后 " + g.cols.size() + " 列，超过 " + MAX_MERGE_COLUMNS
                    + " 列上限；请拆成多个领域目标表。源表：" + names;
        }
        RowFit.Plan p = RowFit.rowPlan(g.cols, DOM_PAGE_SIZE);
        if (!p.ok) {
            return "合并后 " + p.columns + " 列，行上限体检不通过（server " + p.server + " / "
                    + RowFit.SERVER_ROW_LIMIT + "，行内 " + p.local + " / " + p.localLimit
                    + "）；请拆成多个领域目标表。源表：" + names;
        }
        return "";
    }

    /** 拒绝时点名源表：最多列 10 张，再多只报总数，免得一行日志刷出几百个表名。 */
    private static String sourceNames(List<SourceTable> sources) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size() && i < 10; i++) {
            if (i > 0) { sb.append("、"); }
            sb.append(sources.get(i).name);
        }
        if (sources.size() > 10) {
            sb.append(" 等 ").append(sources.size()).append(" 张");
        }
        return sb.toString();
    }

    /**
     * 多源合并表的主键：只认治理字段 entity_id，不并各源主键。
     * 顺手把类型收到 VARCHAR(64)——它是 UUID() 生成的 36 字符键，而当主键就要建索引，
     * 治理字段默认的 VARCHAR(255) 在 utf8mb4 下是 1020 字节，老行格式（COMPACT，索引 767 上限）
     * 建不出来；obj 的维度主键本来就是 VARCHAR(64)，同一口径。单源路不动这里的列。
     */
    private static void markEntityIdPk(List<Infer.Column> cols) {
        for (Infer.Column c : cols) {
            if ("entity_id".equals(c.field)) {
                c.primaryKey = true;
                c.type = "VARCHAR(64)";
                return;
            }
        }
    }

    private static String domInsert(String target, List<Infer.Column> cols, ColExpr x) {
        List<String> proj = new ArrayList<String>();
        for (Infer.Column c : cols) { proj.add(x.selectExpr(c.field)); }
        return "INSERT INTO `" + target + "` SELECT " + join(proj, ", ") + " FROM " + x.from() + ";";
    }

    // ---- obj：按涉及对象维度抽实体，十类按实际命中 ----
    private static LayerOutput genObj(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.OBJ;
        Map<String, SourceTable> map = byName(src);
        Map<String, List<SourceTable>> dimSources = new LinkedHashMap<String, List<SourceTable>>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.objDims == null || t.objDims.isEmpty()) { continue; }
            SourceTable s = bind(t, map);
            if (s == null) { out.unbound.add(t.tableEn); continue; }
            for (String dim : t.objDims.split("[;；,，]")) {
                String d = dim.trim();
                if (!DIM_PK.containsKey(d)) { continue; }
                List<SourceTable> g = dimSources.get(d);
                if (g == null) { g = new ArrayList<SourceTable>(); dimSources.put(d, g); }
                if (!g.contains(s)) { g.add(s); }
            }
        }
        StringBuilder ddl = new StringBuilder("USE `" + o.objDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.objDb + "`;\n\n");
        int dropped = 0;
        List<String> droppedSample = new ArrayList<String>();
        for (Map.Entry<String, List<SourceTable>> e : dimSources.entrySet()) {
            String dim = e.getKey();
            String pk = DIM_PK.get(dim);
            // 多源维度同 dom：一整条 UNION ALL 里谁缺列就得插 NULL，源侧 NOT NULL 携带进来是 1048
            // 丢整张维表；主键本来就是 DIM_PK 那一个（每支 UUID() 生成），不并源主键。单源维表照旧。
            boolean multi = e.getValue().size() > 1;
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            cols.add(col(pk, "VARCHAR(64)", dim + " 主键", true));
            Set<String> seen = new LinkedHashSet<String>();
            seen.add(pk);
            for (SourceTable s : e.getValue()) {
                for (Infer.Column c : s.columns) {
                    if (c.primaryKey || !seen.add(c.field)) {
                        continue;
                    }
                    if (isGovField(c.field) || !FieldClass.keep(c)) {
                        dropped++;
                        if (droppedSample.size() < 6) {
                            droppedSample.add(s.name + "." + c.field);
                        }
                        continue;
                    }
                    cols.add(multi ? copyMerged(c) : copyCol(c, false));
                }
            }
            addGovernance(cols, GOV_OBJ_DOM);
            ddl.append(Ddl.buildDdl(dim, dim, cols, true)).append(";\n\n");
            List<String> branches = new ArrayList<String>();
            for (SourceTable s : e.getValue()) {
                ColExpr x = new ColExpr(s, pk, firstPk(s), o, dim, multi);
                branches.add(objSelect(s, cols, x));
                addFieldMaps(out, s, dim, cols, x);
            }
            dml.append("INSERT INTO `").append(dim).append("` ").append(join(branches, "\nUNION ALL\n")).append(";\n\n");
            out.tableCount++;
        }
        if (dropped > 0) {
            out.warnings.add("obj 跳过 " + dropped + " 个未归类或系统字段（示例："
                    + join(droppedSample, "、") + "）");
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        return out;
    }

    /**
     * obj 的一支：去重放内层派生表（只对业务列＋作 src_entity_id 的源主键做 DISTINCT），
     * 生成键（UUID()/NOW()/字面量）全放外层。
     * 老写法 `SELECT DISTINCT UUID() AS pk, …` 里 UUID() 每行都不同，DISTINCT 永远撞不出重复行，
     * 大事实表喂进来的维度于是「一行一维」（条款 183/186 的去重没发生）。
     */
    private static String objSelect(SourceTable s, List<Infer.Column> cols, ColExpr x) {
        List<String> outer = new ArrayList<String>();
        for (Infer.Column c : cols) { outer.add(x.selectExpr(c.field)); }
        List<String> inner = new ArrayList<String>();
        for (String f : x.dedupCols(cols)) { inner.add("`" + f + "`"); }
        return "SELECT " + join(outer, ", ")
                + " FROM (SELECT DISTINCT " + join(inner, ", ") + " FROM " + x.from() + ") AS `e_" + s.name + "`";
    }

    // ---- ads：按条款 188 的三个主题，在 dom 数值列上产指标聚合（口径待业务方核，故不产 obj JOIN）----
    private static LayerOutput genAds(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.ADS;
        // dom 层实际会生成的列清单：ads 的指标就从这些列里挑，被护栏拒掉的表不产 INSERT
        Map<String, List<Infer.Column>> domCols = new LinkedHashMap<String, List<Infer.Column>>();
        for (DomGroup g : planDom(m, src, new LayerOutput())) {
            if (g.refuse.isEmpty()) { domCols.put(g.target, g.cols); }
        }
        // 主题 →（领域目标表 → 行数）。一张主题表装多个领域的汇总，每个领域一条 INSERT：
        // 旧写法只取组里第一张表当 FROM，同主题其余领域的表根本没进汇总。
        Map<String, Map<String, Integer>> groups =
                new LinkedHashMap<String, Map<String, Integer>>();
        Set<String> skipped = new LinkedHashSet<String>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.theme == null || t.theme.isEmpty()) { continue; }
            if (t.domainTarget == null || t.domainTarget.isEmpty()) { continue; }
            if (!domCols.containsKey(t.domainTarget)) {
                if (skipped.add(t.domainTarget)) {
                    out.warnings.add("ads 跳过领域目标表 " + t.domainTarget
                            + "：dom 层未生成（合并护栏拒绝或源表未绑定）");
                }
                continue;
            }
            Map<String, Integer> byDom = groups.get(t.theme);
            if (byDom == null) {
                byDom = new LinkedHashMap<String, Integer>();
                groups.put(t.theme, byDom);
            }
            Integer n = byDom.get(t.domainTarget);
            byDom.put(t.domainTarget, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        StringBuilder ddl = new StringBuilder("USE `" + o.adsDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.adsDb + "`;\n\n");
        int capped = 0;
        for (Map.Entry<String, Map<String, Integer>> e : groups.entrySet()) {
            String theme = e.getKey();
            String target = Taxonomy.themeTable(theme);
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            cols.addAll(governanceCols(GOV_ADS));
            ddl.append(Ddl.buildDdl(target, "主题 " + theme + "（待核）", cols, true)).append(";\n\n");
            if (e.getValue().isEmpty()) {
                out.warnings.add("主题 " + theme + " 没有领域目标表，未产聚合语句");
                out.tableCount++;
                continue;
            }
            for (String domTable : e.getValue().keySet()) {
                List<Infer.Column> scols = domCols.get(domTable);
                List<Measure> measures = measuresOf(scols);
                if (measures.size() > MAX_ADS_MEASURES) {
                    capped += measures.size() - MAX_ADS_MEASURES;
                    measures = measures.subList(0, MAX_ADS_MEASURES);
                }
                if (measures.isEmpty()) {
                    // 没有可求和/求均值的数值列：保住「源行数」这个唯一说得清的口径
                    dml.append(adsInsert(theme, target, domTable, null, o));
                    adsMaps(out, target, domTable, null, o);
                    continue;
                }
                for (Measure mm : measures) {
                    dml.append(adsInsert(theme, target, domTable, mm, o));
                    adsMaps(out, target, domTable, mm, o);
                }
            }
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        if (capped > 0) {
            out.warnings.add("ads 有 " + capped + " 个指标超过每表 " + MAX_ADS_MEASURES
                    + " 个上限未产聚合，可用映射里的显式指标清单补");
        }
        out.warnings.add("ads 指标取 dom 数值列按 SUM/AVG/MAX/MIN 聚合（单位为源注释推断），"
                + "无可用数值列时才退成 COUNT(*)，口径须业务方逐条核改（条款 188 注）");
        return out;
    }

    /** ads 的一条指标：字段、中文说明、单位与聚合方式。 */
    private static final class Measure {
        final String field;
        final String label;
        final String unit;
        final String agg;
        Measure(String field, String label, String unit, String agg) {
            this.field = field; this.label = label;
            this.unit = unit == null ? "" : unit; this.agg = agg;
        }
    }

    /** 从 dom 表的列里挑指标：数值列且被判成指标度量；治理字段与编码不当指标。 */
    private static List<Measure> measuresOf(List<Infer.Column> cols) {
        List<Measure> out = new ArrayList<Measure>();
        if (cols == null) { return out; }
        for (Infer.Column c : cols) {
            if (c.primaryKey || isGovField(c.field)) { continue; }
            FieldClass.Result r = FieldClass.of(c);
            if (!r.measure || r.agg.isEmpty()) { continue; }
            out.add(new Measure(c.field, r.label, r.unit, r.agg));
        }
        return out;
    }

    /** 一条 ads 聚合：模板行数口径或真实指标口径，只 FROM dom 的一张领域目标表。 */
    private static String adsInsert(String theme, String target, String domTable, Measure m,
                                    GenOptions o) {
        String json = "{\"dom\":\"" + domTable + "\"}";
        String idxCode = m == null ? theme : m.field;
        String value = m == null ? "COUNT(*)" : m.agg + "(`" + m.field + "`)";
        String unit = m == null ? "" : m.unit;
        String model = m == null ? "rowcount" : m.agg.toLowerCase() + "_" + m.field;
        String what = m == null ? "按源行数（模板）" : m.label + (m.unit.isEmpty() ? "" : "，" + m.unit);
        StringBuilder sb = new StringBuilder();
        sb.append("-- 主题 ").append(theme).append(" / 领域目标表 ").append(domTable)
          .append(" / 指标 ").append(idxCode).append("（").append(what).append("），口径待业务方核改（条款 188）\n")
          .append("INSERT INTO `").append(target).append("` (`stat_date`, `stat_grain`, `dim_key_json`, `idx_code`, `idx_value`, `idx_unit`, `calc_model_id`, `caliber_ver`, `src_row_cnt`, `excluded_row_cnt`, `etl_batch_id`, `quality_flag`) ")
          .append("SELECT CURDATE() AS `stat_date`, '日' AS `stat_grain`, '").append(json).append("' AS `dim_key_json`, ")
          .append("'").append(idxCode).append("' AS `idx_code`, ")
          .append(value).append(" AS `idx_value`, ")
          .append("'").append(unit).append("' AS `idx_unit`, ")
          .append("'").append(target).append(".").append(model).append("' AS `calc_model_id`, ")
          .append("'v1-待核' AS `caliber_ver`, ")
          .append("COUNT(*) AS `src_row_cnt`, ")
          .append("0 AS `excluded_row_cnt`, '").append(o.batchId).append("' AS `etl_batch_id`, '待核' AS `quality_flag` ")
          .append("FROM ").append(qualify(o.domDb, domTable)).append(" ")
          .append("GROUP BY CURDATE();\n\n");
        return sb.toString();
    }

    /** ads 的字段映射行：转换规则写实发表达式，别让 CSV 与 SQL 各说一套。 */
    private static void adsMaps(LayerOutput out, String target, String domTable, Measure m,
                                GenOptions o) {
        String json = "{\"dom\":\"" + domTable + "\"}";
        String value = m == null ? "COUNT(*)" : m.agg + "(`" + m.field + "`)";
        for (String c : GOV_ADS) {
            MappingFile.FieldMap f = new MappingFile.FieldMap();
            f.srcTable = domTable; f.srcField = m == null ? "" : m.field;
            f.srcComment = m == null ? "源行数模板" : "指标 " + m.field + "（" + m.label + "）";
            f.dstTable = target; f.dstField = c; f.dstComment = GOV_COMMENT.get(c);
            f.dataElemName = ""; f.dataElemId = "待赋码"; f.governance = true; f.status = "待核";
            f.transformRule = adsRule(c, target, m, json, value, o);
            out.fieldMapping.add(f);
        }
    }

    private static String adsRule(String field, String target, Measure m, String json, String value,
                                  GenOptions o) {
        if ("stat_date".equals(field)) { return "CURDATE()"; }
        if ("stat_grain".equals(field)) { return "'日'"; }
        if ("dim_key_json".equals(field)) { return "'" + json + "'"; }
        if ("idx_code".equals(field)) { return "'" + (m == null ? "" : m.field) + "'"; }
        if ("idx_value".equals(field)) { return value; }
        if ("idx_unit".equals(field)) { return "'" + (m == null ? "" : m.unit) + "'"; }
        if ("calc_model_id".equals(field)) {
            return "'" + target + "." + (m == null ? "rowcount" : m.agg.toLowerCase() + "_" + m.field) + "'";
        }
        if ("caliber_ver".equals(field)) { return "'v1-待核'"; }
        if ("src_row_cnt".equals(field)) { return "COUNT(*)"; }
        if ("excluded_row_cnt".equals(field)) { return "0"; }
        if ("etl_batch_id".equals(field)) { return "'" + o.batchId + "'"; }
        return "'待核'";
    }

    // ---- 共用 helpers ----
    private static Infer.Column col(String field, String type, String comment, boolean pk) {
        Infer.Column c = new Infer.Column();
        c.field = field; c.type = type; c.comment = comment;
        c.maxLen = 255; c.primaryKey = pk; c.nullable = pk ? Boolean.FALSE : Boolean.TRUE;
        return c;
    }

    private static Infer.Column copyCol(Infer.Column c, boolean pk) {
        Infer.Column n = c.copied();
        n.primaryKey = pk;
        if (n.comment == null || n.comment.trim().isEmpty()) {
            n.comment = FieldClass.commentOf(c);
        }
        return n;
    }

    /** 多源合并列：既不携带源主键、也不携带源侧 NOT NULL——缺列那一支要插 NULL，带着约束整表炸 1048。 */
    private static Infer.Column copyMerged(Infer.Column c) {
        Infer.Column n = c.copied();
        n.primaryKey = false;
        n.nullable = Boolean.TRUE;
        if (n.comment == null || n.comment.trim().isEmpty()) {
            n.comment = FieldClass.commentOf(c);
        }
        return n;
    }

    /**
     * 一条「源表 → 目标表」分支上，目标列取值表达式的唯一事实源。
     * 转换 SQL（domInsert／objSelect）和 <层>_field_mapping.csv 的「转换规则」（addFieldMaps）都从这里取：
     * 两处各写一份时，CSV 会把 pk／src_entity_id 记成 `NULL AS …`，而 SQL 实发 `UUID() AS …`／
     * `` `源主键` AS … ``，两份交付证据自相矛盾（条款 208/186）。
     */
    private static final class ColExpr {
        private final Set<String> srcFields;
        private final String uuidPk;
        private final String srcPk;
        private final GenOptions o;
        private final String srcFrom;
        private final String srcTable;
        private final String target;
        private final boolean multi;

        ColExpr(SourceTable s, String uuidPk, String srcPk, GenOptions o,
                String target, boolean multi) {
            this.srcFields = new LinkedHashSet<String>();
            for (Infer.Column c : s.columns) { srcFields.add(c.field); }
            this.uuidPk = uuidPk;
            // 源主键得真在源列里才当 src_entity_id 用，否则退成治理占位（别引用不存在的列）
            this.srcPk = srcPk != null && srcFields.contains(srcPk) ? srcPk : null;
            this.o = o;
            this.srcFrom = qualify(o.sourceDb, s.name);
            this.srcTable = s.name;
            this.target = target;
            this.multi = multi;
        }

        String from() { return srcFrom; }

        /** 该目标列是否直接取同名源列——只有这类列进派生表的 DISTINCT 投影。 */
        boolean refsSource(String field) {
            if (field.equals(uuidPk) || isGovField(field)) { return false; }
            if ("src_entity_id".equals(field) && srcPk != null) { return false; }
            return srcFields.contains(field);
        }

        String selectExpr(String field) {
            if (field.equals(uuidPk)) { return "UUID() AS `" + field + "`"; }
            if ("src_entity_id".equals(field) && srcPk != null) { return "`" + srcPk + "` AS `src_entity_id`"; }
            if (!isGovField(field) && srcFields.contains(field)) { return "`" + field + "`"; }
            return govExpr(field, o, target, srcTable, srcPk, multi);
        }

        /** 参与 DISTINCT 的列：业务列＋作 src_entity_id 的源主键；生成键一个都不进（进了就永不去重）。 */
        List<String> dedupCols(List<Infer.Column> cols) {
            List<String> out = new ArrayList<String>();
            for (Infer.Column c : cols) {
                if (refsSource(c.field) && !out.contains(c.field)) { out.add(c.field); }
            }
            if (srcPk != null && !out.contains(srcPk)) { out.add(srcPk); }
            return out;
        }
    }

    private static boolean isGovField(String field) {
        return field != null && GOV_NAMES.contains(field);
    }

    private static List<Infer.Column> governanceCols(String[] names) {
        List<Infer.Column> out = new ArrayList<Infer.Column>();
        for (String n : names) {
            String comment = GOV_COMMENT.get(n);
            out.add(col(n, govType(n), comment == null ? n : comment, false));
        }
        return out;
    }

    /** 治理字段类型：指标值要能装下 SUM/AVG 的小数，别把聚合结果塞进 VARCHAR 当字符串存。 */
    private static String govType(String n) {
        if ("update_time".equals(n)) { return "DATETIME"; }
        if ("stat_date".equals(n)) { return "DATE"; }
        if ("src_row_cnt".equals(n) || "excluded_row_cnt".equals(n)) { return "BIGINT"; }
        if ("idx_value".equals(n)) { return "DECIMAL(38,6)"; }
        if ("stat_grain".equals(n)) { return "VARCHAR(16)"; }
        if ("valid_flag".equals(n)) { return "VARCHAR(16)"; }
        return "VARCHAR(255)";
    }

    /** 补治理字段，但源表已有同名列（如 update_time）时跳过，避免 CREATE TABLE 重名列。 */
    private static void addGovernance(List<Infer.Column> cols, String[] names) {
        Set<String> have = new LinkedHashSet<String>();
        for (Infer.Column c : cols) { have.add(c.field); }
        for (Infer.Column g : governanceCols(names)) {
            if (have.add(g.field)) { cols.add(g); }
        }
    }

    /**
     * 治理字段的取值表达式。能回指源行的就回指（源主键、来源表名），
     * 定不出业务值的给一个说得过去的常量并在注释里写明口径，不留一整列 NULL：
     * 交付方拿到 NULL 不知道是「没采到」还是「不适用」。
     */
    private static String govExpr(String field, GenOptions o, String target, String srcTable,
                                  String srcPk, boolean multi) {
        if ("entity_id".equals(field)) { return "UUID() AS `entity_id`"; }
        if ("src_entity_id".equals(field) || "src_code".equals(field)) {
            return srcPk == null ? "NULL AS `" + field + "`" : "`" + srcPk + "` AS `" + field + "`";
        }
        if ("std_code".equals(field)) {
            return srcPk == null ? "NULL AS `std_code`"
                    : "CONCAT('" + srcTable + "_', `" + srcPk + "`) AS `std_code`";
        }
        if ("tags".equals(field)) { return "'来源:" + srcTable + "' AS `tags`"; }
        if ("data_level".equals(field)) { return "'内部' AS `data_level`"; }
        if ("mask_policy".equals(field)) { return "'无' AS `mask_policy`"; }
        if ("quality_flag".equals(field)) { return "'正常' AS `quality_flag`"; }
        if ("quality_msg".equals(field)) { return "'源表结构直映' AS `quality_msg`"; }
        if ("de_map_id".equals(field)) { return "'" + target + "' AS `de_map_id`"; }
        if ("src_batch_id".equals(field) || "etl_batch_id".equals(field)) {
            return "'" + o.batchId + "' AS `" + field + "`";
        }
        if ("merge_rule_id".equals(field)) {
            return (multi ? "'同领域多源UNION'" : "'一源一表'") + " AS `merge_rule_id`";
        }
        if ("update_time".equals(field)) { return "NOW() AS `update_time`"; }
        if ("valid_flag".equals(field)) { return "'1' AS `valid_flag`"; }
        if ("src_row_cnt".equals(field)) { return "COUNT(*) AS `src_row_cnt`"; }
        if ("excluded_row_cnt".equals(field)) { return "0 AS `excluded_row_cnt`"; }
        return "NULL AS `" + field + "`";
    }

    private static String firstPk(SourceTable s) {
        for (Infer.Column c : s.columns) { if (c.primaryKey) { return c.field; } }
        return s.columns.isEmpty() ? "id" : s.columns.get(0).field;
    }

    private static void addFieldMaps(LayerOutput out, SourceTable s, String target,
                                     List<Infer.Column> cols, ColExpr x) {
        Map<String, Infer.Column> srcByName = new LinkedHashMap<String, Infer.Column>();
        for (Infer.Column c : s.columns) { srcByName.put(c.field, c); }
        for (Infer.Column c : cols) {
            MappingFile.FieldMap f = new MappingFile.FieldMap();
            f.dstTable = target; f.dstField = c.field; f.dstComment = c.comment;
            f.dataElemName = ""; f.dataElemId = "待赋码"; f.status = "待核";
            // 表达式与转换 SQL 同源（ColExpr 一处生成），CSV 不再出现「SQL 发 UUID()、规则写 NULL」
            if (x.refsSource(c.field)) {
                Infer.Column sc = srcByName.get(c.field);
                f.srcTable = s.name; f.srcField = c.field; f.srcComment = sc.comment;
                f.transformRule = "同名直映"; f.governance = false;
            } else {
                f.srcTable = s.name; f.srcField = ""; f.srcComment = "自动补治理字段";
                f.transformRule = x.selectExpr(c.field); f.governance = true;
            }
            out.fieldMapping.add(f);
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) { sb.append(sep); }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ---- 连库 I/O（Task 3）----
    public static final class WriteResult {
        public int tablesCreated;
        public long rowsAffected;
        public List<String> errors = new ArrayList<String>();
    }

    /** 读源库用户表结构（VIEW 排除），列按 ORDINAL_POSITION 序。不报进度、也不可中止的调用方走这一句。 */
    public static List<SourceTable> readSource(DbClient db, String sourceDb) throws java.sql.SQLException {
        return readSource(db, sourceDb, ImportParse.LOG_ONLY);
    }

    /**
     * 读源库用户表结构，并把进度与中止接到调用方的 sink 上：一张表计一项，总数在动手前先报一次
     * （同 ImportRun.run 的 setTotal→advance 形状）。312 张表的元数据要读几十秒，不接这两根线时
     * 进度框一直停在 0%「准备中」、那颗「取消」也无人接（spec §11 承诺的是可中止的非模态进度）。
     * 取消在表与表之间生效，已读到的那些如实交回，不算失败。
     */
    public static List<SourceTable> readSource(DbClient db, String sourceDb, ImportParse.Sink sink)
            throws java.sql.SQLException {
        List<SourceTable> out = new ArrayList<SourceTable>();
        java.sql.DatabaseMetaData md = db.connection().getMetaData();
        List<String> tableNames = new ArrayList<String>();
        java.sql.ResultSet ts = md.getTables(sourceDb, null, "%", new String[]{"TABLE"});
        try { while (ts.next()) { tableNames.add(ts.getString("TABLE_NAME")); } } finally { ts.close(); }
        java.util.Collections.sort(tableNames);
        final int n = tableNames.size();
        sink.setTotal(n);
        int done = 0;
        for (String tn : tableNames) {
            if (sink.cancelled()) {
                sink.log("! [WARN] LAYER | 已取消：源库 " + sourceDb + " 停在第 " + (done + 1)
                        + "/" + n + " 张，剩余表未读（已读 " + done + " 张照实返回）");
                break;
            }
            String comment = "";
            java.sql.ResultSet tt = md.getTables(sourceDb, null, tn, new String[]{"TABLE"});
            try { if (tt.next()) { comment = tt.getString("REMARKS"); } } finally { tt.close(); }
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            java.sql.ResultSet cs = md.getColumns(sourceDb, null, tn, "%");
            try {
                while (cs.next()) {
                    Infer.Column c = new Infer.Column();
                    c.field = cs.getString("COLUMN_NAME");
                    c.comment = cs.getString("REMARKS");
                    c.type = jdbcTypeToDdl(cs.getInt("DATA_TYPE"), cs.getInt("COLUMN_SIZE"), cs.getInt("DECIMAL_DIGITS"));
                    c.maxLen = cs.getInt("COLUMN_SIZE");
                    c.nullable = cs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls;
                    cols.add(c);
                }
            } finally { cs.close(); }
            java.sql.ResultSet pk = md.getPrimaryKeys(sourceDb, null, tn);
            Set<String> pkFields = new LinkedHashSet<String>();
            try { while (pk.next()) { pkFields.add(pk.getString("COLUMN_NAME")); } } finally { pk.close(); }
            for (Infer.Column c : cols) { c.primaryKey = pkFields.contains(c.field); }
            out.add(new SourceTable(tn, comment == null ? "" : comment, cols));
            done++;
            sink.advance(1, "读 " + sourceDb + "." + tn + " 结构 第 " + done + "/" + n + " 张");
        }
        return out;
    }

    private static String jdbcTypeToDdl(int jdbcType, int size, int digits) {
        switch (jdbcType) {
            case java.sql.Types.INTEGER: return "INT";
            case java.sql.Types.BIGINT: return "BIGINT";
            case java.sql.Types.SMALLINT: return "SMALLINT";
            case java.sql.Types.TINYINT: return "TINYINT";
            case java.sql.Types.DECIMAL: case java.sql.Types.NUMERIC:
                return "DECIMAL(" + Math.max(size, 1) + "," + Math.max(digits, 0) + ")";
            case java.sql.Types.DOUBLE: case java.sql.Types.FLOAT: case java.sql.Types.REAL: return "DOUBLE";
            case java.sql.Types.DATE: return "DATE";
            case java.sql.Types.TIMESTAMP: return "DATETIME";
            case java.sql.Types.TIME: return "TIME";
            // 带显示宽度的 TINYINT(1) 在 MySQL 8.0.17+ 执行会报废弃告警；BOOLEAN 是它的别名
            case java.sql.Types.BIT: case java.sql.Types.BOOLEAN: return "BOOLEAN";
            case java.sql.Types.LONGVARCHAR: case java.sql.Types.CLOB: return "TEXT";
            default: return "VARCHAR(" + (size > 0 && size <= 16383 ? size : 255) + ")";
        }
    }

    /** 入库：建库（若缺）→建表→执行转换 SQL。单条失败隔离记 errors，不掀整轮。 */
    public static WriteResult execute(LayerOutput out, String targetDb, DbClient db, ImportParse.Sink sink)
            throws java.sql.SQLException {
        WriteResult wr = new WriteResult();
        // targetDb 与 DDL 里的 `USE \`x\`;` 头必须一致——两者是两个事实源，调用方（Task4/5）给错库时，
        // 语句会经 USE 落进另一个库、还在这库留个空壳。宁可记账不执行，也不能默默写错库。
        String useDb = firstUseTarget(out.ddlSql);
        if (useDb != null && !useDb.equals(targetDb)) {
            wr.errors.add("目标库不一致：USE `" + useDb + "` 但 targetDb=" + targetDb + "，已跳过执行");
            sink.logError("x [ERROR] LAYER-EXEC | 目标库不一致，未执行：" + useDb + " vs " + targetDb);
            return wr;
        }
        // 建库也纳入单条失败隔离：权限/磁盘错记进 errors、收摊返回，不再抛出掀整轮（与下面循环同口径）。
        java.sql.Statement st = db.connection().createStatement();
        try {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + targetDb + "` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            db.connection().commit();
        } catch (java.sql.SQLException e) {
            wr.errors.add("建库失败[" + targetDb + "]：" + e.getMessage());
            sink.logError("x [ERROR] LAYER-DB | 建库失败：" + e.getMessage());
            return wr;   // 库没建成，后面 USE/建表必炸，直接收摊
        } finally { st.close(); }
        // 会话目标库设为**前置条件**：显式 USE 并单独隔离失败——不能只靠下面循环里那条会被
        // continue-on-error 忽略的 USE 头。否则 USE 一旦失败，后续未限定的 DROP/CREATE 会落到
        // 上一次 execute 的库或 URL 目录库（E2M_DB），静默毁同名表。先 USE 成，语句才准往下跑。
        java.sql.Statement us = db.connection().createStatement();
        try {
            us.execute("USE `" + targetDb + "`");
            db.connection().commit();
        } catch (java.sql.SQLException e) {
            noteError(wr, sink, "LAYER-USE", "切库失败", "USE `" + targetDb + "`", targetDb, e);
            return wr;   // 进不去目标库就别往下走，绝不让语句漂到别的库
        } finally { us.close(); }
        // DDL 按 ";\n" 拆（与 DbClient.createTable 同口径）；USE 头已在上面坐实，这里再遇 USE 是同库空操作
        // 进度与中止按语句计项：总数在动手前一次报出（312 张表时是几百条），
        // 「取消」在下一条语句开工前生效，已经跑掉的那些不回滚、也不谎报成没跑
        List<String> ddlStmts = splitStatements(out.ddlSql);
        List<String> dmlStmts = splitStatements(out.transformSql);
        sink.setTotal(ddlStmts.size() + dmlStmts.size());
        int pos = 0;
        int ddlTotal = ddlStmts.size();
        for (String stmt : ddlStmts) {
            if (sink.cancelled()) {
                sink.log("! [WARN] LAYER-EXEC | 已取消：库 " + targetDb + " 的 DDL 停在第 "
                        + (pos + 1) + "/" + ddlTotal + " 条，剩余语句未执行");
                return wr;
            }
            pos++;
            if (stmt.trim().isEmpty()) {
                sink.advance(1, "DDL 第 " + pos + "/" + ddlTotal + " 条（空语句跳过）");
                continue;
            }
            java.sql.Statement s = db.connection().createStatement();
            try {
                s.execute(stmt);
                db.connection().commit();
                if (stmt.trim().toUpperCase().startsWith("CREATE TABLE")) { wr.tablesCreated++; }
            } catch (java.sql.SQLException e) {
                noteError(wr, sink, "LAYER-DDL", "DDL 失败", stmt, targetDb, e);
            } finally { s.close(); }
            sink.advance(1, "DDL 第 " + pos + "/" + ddlTotal + " 条 · " + stmtHead(stmt));
        }
        int dmlTotal = dmlStmts.size();
        for (String stmt : dmlStmts) {
            if (sink.cancelled()) {
                sink.log("! [WARN] LAYER-EXEC | 已取消：库 " + targetDb + " 的转换 SQL 停在第 "
                        + (pos + 1) + "/" + (ddlTotal + dmlTotal) + " 条，剩余语句未执行");
                return wr;
            }
            pos++;
            if (stmt.trim().isEmpty()) {
                sink.advance(1, "转换 第 " + (pos - ddlTotal) + "/" + dmlTotal + " 条（空语句跳过）");
                continue;
            }
            java.sql.Statement s = db.connection().createStatement();
            try {
                int n = s.executeUpdate(stmt);
                db.connection().commit();
                if (n > 0) { wr.rowsAffected += n; }
            } catch (java.sql.SQLException e) {
                noteError(wr, sink, "LAYER-DML", "转换失败", stmt, targetDb, e);
            } finally { s.close(); }
            sink.advance(1, "转换 第 " + (pos - ddlTotal) + "/" + dmlTotal + " 条 · " + stmtHead(stmt));
        }
        return wr;
    }

    /** 从 DDL 串里取第一个 `USE \`x\`;` 的库名；没有返回 null。 */
    private static String firstUseTarget(String ddl) {
        int i = ddl.indexOf("USE `");
        if (i < 0) { return null; }
        int j = ddl.indexOf('`', i + 5);
        return j < 0 ? null : ddl.substring(i + 5, j);
    }

    /** 逐条报错日志上限：超过就只报一次"另有更多"，与 SqlFileRun 的汇总口径一致，312 表时不刷屏。 */
    private static final int MAX_LOGGED_ERRORS = 15;

    /** 单条失败记账：错误串带**落库名＋语句头部**（312 表时才知道炸在哪个库哪条）；逐条日志有上限。 */
    private static void noteError(WriteResult wr, ImportParse.Sink sink, String tag,
                                  String kind, String stmt, String db, java.sql.SQLException e) {
        wr.errors.add(kind + "（库 " + db + "）[" + stmtHead(stmt) + "]：" + e.getMessage());
        if (wr.errors.size() < MAX_LOGGED_ERRORS) {
            sink.logError("x [ERROR] " + tag + " | 库 " + db + " · " + stmtHead(stmt) + "：" + e.getMessage());
        } else if (wr.errors.size() == MAX_LOGGED_ERRORS) {
            sink.logError("! [WARN] " + tag + " | 库 " + db + " 另有更多语句报错未逐条列出（见 errors 汇总）");
        }
    }

    private static String stmtHead(String stmt) {
        String s = stmt.trim();
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /** 按 ";\n" 拆语句，但保留语句内的分号（注释行里的 -- 不拆）。与 DbClient.createTable 同口径。 */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        String[] lines = sql.split("\n", -1);
        for (String line : lines) {
            cur.append(line).append("\n");
            if (line.trim().endsWith(";")) {
                String s = cur.toString().trim();
                if (s.endsWith(";")) { s = s.substring(0, s.length() - 1); }
                out.add(s);
                cur.setLength(0);
            }
        }
        if (cur.toString().trim().length() > 0) { out.add(cur.toString().trim()); }
        return out;
    }
}
