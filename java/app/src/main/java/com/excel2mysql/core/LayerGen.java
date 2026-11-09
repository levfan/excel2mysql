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

    // ---- dom：按领域目标表整编，同名直映＋治理字段 ----
    private static LayerOutput genDom(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.DOM;
        Map<String, SourceTable> map = byName(src);
        Map<String, List<MappingFile.TableMap>> groups = new LinkedHashMap<String, List<MappingFile.TableMap>>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.domainTarget == null || t.domainTarget.isEmpty()) { continue; }
            SourceTable s = bind(t, map);
            if (s == null) { out.unbound.add(t.tableEn); continue; }
            List<MappingFile.TableMap> g = groups.get(t.domainTarget);
            if (g == null) { g = new ArrayList<MappingFile.TableMap>(); groups.put(t.domainTarget, g); }
            g.add(t);
        }
        StringBuilder ddl = new StringBuilder("USE `" + o.domDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.domDb + "`;\n\n");
        for (Map.Entry<String, List<MappingFile.TableMap>> e : groups.entrySet()) {
            String target = e.getKey();
            // 条款 184 的整编实况就是多源归一（层间映射初稿 312 源表 → 26 领域目标表，23 个多源）。
            // 合并表要装下所有源的列，缺列的源那一支只能插 NULL，所以：源侧 NOT NULL 一律不许携带、
            // 各源主键一律不许并成复合主键，主键只归治理字段 entity_id（每行 UUID() 生成，天然唯一）。
            // 单源目标（1:1 路）保持原样：源主键照旧携带，不在这条路上做任何改变。
            boolean multi = isMultiSource(e.getValue(), map);
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            Set<String> seen = new LinkedHashSet<String>();
            for (MappingFile.TableMap t : e.getValue()) {
                SourceTable s = bind(t, map);
                for (Infer.Column c : s.columns) {
                    if (seen.add(c.field)) { cols.add(multi ? copyMerged(c) : copyCol(c, c.primaryKey)); }
                }
            }
            addGovernance(cols, GOV_OBJ_DOM);
            if (multi) { markEntityIdPk(cols); }
            ddl.append(Ddl.buildDdl(target, target, cols, true)).append(";\n\n");
            for (MappingFile.TableMap t : e.getValue()) {
                SourceTable s = bind(t, map);
                // dom 不产生成主键之外的键值：uuidPk 只在多源时钉 entity_id，src_entity_id 归 obj（条款 186）
                ColExpr x = new ColExpr(s, multi ? "entity_id" : null, null, o);
                dml.append(domInsert(target, cols, x)).append("\n\n");
                addFieldMaps(out, s, target, cols, x);
            }
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        return out;
    }

    /** 该目标组是否由两张以上不同源表归并而来（同一源表重复登记不算多源）。 */
    private static boolean isMultiSource(List<MappingFile.TableMap> group, Map<String, SourceTable> map) {
        Set<String> names = new LinkedHashSet<String>();
        for (MappingFile.TableMap t : group) {
            SourceTable s = bind(t, map);
            if (s != null) { names.add(s.name); }
        }
        return names.size() > 1;
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
                    if (!c.primaryKey && seen.add(c.field)) {
                        cols.add(multi ? copyMerged(c) : copyCol(c, false));
                    }
                }
            }
            addGovernance(cols, GOV_OBJ_DOM);
            ddl.append(Ddl.buildDdl(dim, dim, cols, true)).append(";\n\n");
            List<String> branches = new ArrayList<String>();
            for (SourceTable s : e.getValue()) {
                ColExpr x = new ColExpr(s, pk, firstPk(s), o);
                branches.add(objSelect(s, cols, x));
                addFieldMaps(out, s, dim, cols, x);
            }
            dml.append("INSERT INTO `").append(dim).append("` ").append(join(branches, "\nUNION ALL\n")).append(";\n\n");
            out.tableCount++;
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
                + " FROM (SELECT DISTINCT " + join(inner, ", ") + " FROM " + x.from() + ") AS e_" + s.name;
    }

    // ---- ads：按主题归属在 dom 上产模板聚合（条款 188 口径待业务方核，故不产 obj JOIN，FROM 只有 dom）----
    private static LayerOutput genAds(MappingFile m, List<SourceTable> src, GenOptions o) {
        LayerOutput out = new LayerOutput();
        out.layer = Layer.ADS;
        Map<String, List<MappingFile.TableMap>> groups = new LinkedHashMap<String, List<MappingFile.TableMap>>();
        for (MappingFile.TableMap t : m.tables()) {
            if (t.theme == null || t.theme.isEmpty()) { continue; }
            List<MappingFile.TableMap> g = groups.get(t.theme);
            if (g == null) { g = new ArrayList<MappingFile.TableMap>(); groups.put(t.theme, g); }
            g.add(t);
        }
        StringBuilder ddl = new StringBuilder("USE `" + o.adsDb + "`;\n\n");
        StringBuilder dml = new StringBuilder("USE `" + o.adsDb + "`;\n\n");
        for (Map.Entry<String, List<MappingFile.TableMap>> e : groups.entrySet()) {
            String theme = e.getKey();
            String target = "ads_" + theme;
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            cols.addAll(governanceCols(GOV_ADS));
            ddl.append(Ddl.buildDdl(target, "主题 " + theme + "（待核）", cols, true)).append(";\n\n");
            String domTable = e.getValue().get(0).domainTarget;
            dml.append("-- 主题 ").append(theme).append(" 聚合模板，指标口径待业务方核改（条款 188）\n")
               .append("INSERT INTO `").append(target).append("` (`stat_date`, `dim_key_json`, `idx_code`, `src_row_cnt`, `excluded_row_cnt`, `etl_batch_id`, `quality_flag`) ")
               .append("SELECT CURDATE() AS `stat_date`, '{}' AS `dim_key_json`, ")
               .append("'").append(theme).append("' AS `idx_code`, COUNT(*) AS `src_row_cnt`, ")
               .append("0 AS `excluded_row_cnt`, '").append(o.batchId).append("' AS `etl_batch_id`, '待核' AS `quality_flag` ")
               .append("FROM ").append(qualify(o.domDb, domTable == null ? "dwd_unknown" : domTable)).append(" ")
               .append("GROUP BY CURDATE();\n\n");
            for (Infer.Column c : cols) {
                MappingFile.FieldMap f = new MappingFile.FieldMap();
                f.srcTable = domTable == null ? "" : domTable; f.srcField = "";
                f.srcComment = "模板聚合"; f.dstTable = target; f.dstField = c.field;
                f.dstComment = c.comment; f.dataElemName = ""; f.dataElemId = "待赋码";
                f.transformRule = "模板聚合（待核）"; f.governance = true; f.status = "待核";
                out.fieldMapping.add(f);
            }
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        out.warnings.add("ads 指标口径为模板，须业务方逐条核改（条款 188 注）");
        return out;
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
        return n;
    }

    /** 多源合并列：既不携带源主键、也不携带源侧 NOT NULL——缺列那一支要插 NULL，带着约束整表炸 1048。 */
    private static Infer.Column copyMerged(Infer.Column c) {
        Infer.Column n = c.copied();
        n.primaryKey = false;
        n.nullable = Boolean.TRUE;
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

        ColExpr(SourceTable s, String uuidPk, String srcPk, GenOptions o) {
            this.srcFields = new LinkedHashSet<String>();
            for (Infer.Column c : s.columns) { srcFields.add(c.field); }
            this.uuidPk = uuidPk;
            // 源主键得真在源列里才当 src_entity_id 用，否则退成治理占位（别引用不存在的列）
            this.srcPk = srcPk != null && srcFields.contains(srcPk) ? srcPk : null;
            this.o = o;
            this.srcFrom = qualify(o.sourceDb, s.name);
        }

        String from() { return srcFrom; }

        /** 该目标列是否直接取同名源列——只有这类列进派生表的 DISTINCT 投影。 */
        boolean refsSource(String field) {
            if (field.equals(uuidPk)) { return false; }
            if ("src_entity_id".equals(field) && srcPk != null) { return false; }
            return srcFields.contains(field);
        }

        String selectExpr(String field) {
            if (field.equals(uuidPk)) { return "UUID() AS `" + field + "`"; }
            if ("src_entity_id".equals(field) && srcPk != null) { return "`" + srcPk + "` AS `src_entity_id`"; }
            if (srcFields.contains(field)) { return "`" + field + "`"; }
            return govExpr(field, o);
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

    private static List<Infer.Column> governanceCols(String[] names) {
        List<Infer.Column> out = new ArrayList<Infer.Column>();
        for (String n : names) {
            String type = "update_time".equals(n) ? "DATETIME"
                    : "src_row_cnt".equals(n) || "excluded_row_cnt".equals(n) ? "BIGINT"
                    : "stat_date".equals(n) ? "DATE" : "VARCHAR(255)";
            out.add(col(n, type, "治理字段（表 5-3/6-4）", false));
        }
        return out;
    }

    /** 补治理字段，但源表已有同名列（如 update_time）时跳过，避免 CREATE TABLE 重名列。 */
    private static void addGovernance(List<Infer.Column> cols, String[] names) {
        Set<String> have = new LinkedHashSet<String>();
        for (Infer.Column c : cols) { have.add(c.field); }
        for (Infer.Column g : governanceCols(names)) {
            if (have.add(g.field)) { cols.add(g); }
        }
    }

    private static String govExpr(String field, GenOptions o) {
        if ("entity_id".equals(field)) { return "UUID() AS `entity_id`"; }
        if ("src_batch_id".equals(field) || "etl_batch_id".equals(field)) {
            return "'" + o.batchId + "' AS `" + field + "`";
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

    /** 读源库用户表结构（VIEW 排除），列按 ORDINAL_POSITION 序。 */
    public static List<SourceTable> readSource(DbClient db, String sourceDb) throws java.sql.SQLException {
        List<SourceTable> out = new ArrayList<SourceTable>();
        java.sql.DatabaseMetaData md = db.connection().getMetaData();
        List<String> tableNames = new ArrayList<String>();
        java.sql.ResultSet ts = md.getTables(sourceDb, null, "%", new String[]{"TABLE"});
        try { while (ts.next()) { tableNames.add(ts.getString("TABLE_NAME")); } } finally { ts.close(); }
        java.util.Collections.sort(tableNames);
        for (String tn : tableNames) {
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
            case java.sql.Types.BIT: case java.sql.Types.BOOLEAN: return "TINYINT(1)";
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
            st.execute("CREATE DATABASE IF NOT EXISTS `" + targetDb + "` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci");
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
        for (String stmt : splitStatements(out.ddlSql)) {
            if (stmt.trim().isEmpty()) { continue; }
            java.sql.Statement s = db.connection().createStatement();
            try {
                s.execute(stmt);
                db.connection().commit();
                if (stmt.trim().toUpperCase().startsWith("CREATE TABLE")) { wr.tablesCreated++; }
            } catch (java.sql.SQLException e) {
                noteError(wr, sink, "LAYER-DDL", "DDL 失败", stmt, targetDb, e);
            } finally { s.close(); }
        }
        for (String stmt : splitStatements(out.transformSql)) {
            if (stmt.trim().isEmpty()) { continue; }
            java.sql.Statement s = db.connection().createStatement();
            try {
                int n = s.executeUpdate(stmt);
                db.connection().commit();
                if (n > 0) { wr.rowsAffected += n; }
            } catch (java.sql.SQLException e) {
                noteError(wr, sink, "LAYER-DML", "转换失败", stmt, targetDb, e);
            } finally { s.close(); }
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
