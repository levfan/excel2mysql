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
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            Set<String> seen = new LinkedHashSet<String>();
            String pk = null;
            for (MappingFile.TableMap t : e.getValue()) {
                SourceTable s = bind(t, map);
                for (Infer.Column c : s.columns) {
                    if (seen.add(c.field)) {
                        cols.add(copyCol(c, c.primaryKey));
                        if (c.primaryKey && pk == null) { pk = c.field; }
                    }
                }
            }
            addGovernance(cols, GOV_OBJ_DOM);
            ddl.append(Ddl.buildDdl(target, target, cols, true)).append(";\n\n");
            for (MappingFile.TableMap t : e.getValue()) {
                SourceTable s = bind(t, map);
                dml.append(domInsert(target, s, cols, o)).append("\n\n");
                addFieldMaps(out, s, target, cols, o);
            }
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        return out;
    }

    private static String domInsert(String target, SourceTable s, List<Infer.Column> cols, GenOptions o) {
        StringBuilder select = new StringBuilder();
        Set<String> srcFields = new LinkedHashSet<String>();
        for (Infer.Column c : s.columns) { srcFields.add(c.field); }
        for (Infer.Column c : cols) {
            if (select.length() > 0) { select.append(", "); }
            if (srcFields.contains(c.field)) { select.append("`").append(c.field).append("`"); }
            else { select.append(govExpr(c.field, o)); }
        }
        return "INSERT INTO `" + target + "` SELECT " + select + " FROM " + qualify(o.sourceDb, s.name) + ";";
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
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            cols.add(col(pk, "VARCHAR(64)", dim + " 主键", true));
            Set<String> seen = new LinkedHashSet<String>();
            seen.add(pk);
            for (SourceTable s : e.getValue()) {
                for (Infer.Column c : s.columns) {
                    if (!c.primaryKey && seen.add(c.field)) { cols.add(copyCol(c, false)); }
                }
            }
            addGovernance(cols, GOV_OBJ_DOM);
            ddl.append(Ddl.buildDdl(dim, dim, cols, true)).append(";\n\n");
            List<String> branches = new ArrayList<String>();
            for (SourceTable s : e.getValue()) {
                branches.add(objSelect(dim, pk, s, cols, o));
                addFieldMaps(out, s, dim, cols, o);
            }
            dml.append("INSERT INTO `").append(dim).append("` ").append(join(branches, "\nUNION ALL\n")).append(";\n\n");
            out.tableCount++;
        }
        out.ddlSql = ddl.toString();
        out.transformSql = dml.toString();
        return out;
    }

    private static String objSelect(String dim, String pk, SourceTable s, List<Infer.Column> cols, GenOptions o) {
        String srcPk = firstPk(s);
        StringBuilder select = new StringBuilder("SELECT DISTINCT ");
        Set<String> srcFields = new LinkedHashSet<String>();
        for (Infer.Column c : s.columns) { srcFields.add(c.field); }
        boolean first = true;
        for (Infer.Column c : cols) {
            if (!first) { select.append(", "); }
            first = false;
            if (c.field.equals(pk)) { select.append("UUID() AS `").append(pk).append("`"); }
            else if (c.field.equals("src_entity_id")) { select.append("`").append(srcPk).append("` AS `src_entity_id`"); }
            else if (srcFields.contains(c.field)) { select.append("`").append(c.field).append("`"); }
            else { select.append(govExpr(c.field, o)); }
        }
        select.append(" FROM ").append(qualify(o.sourceDb, s.name));
        return select.toString();
    }

    // ---- ads：按主题归属在 obj＋dom 上产模板聚合 ----
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
                                     List<Infer.Column> cols, GenOptions o) {
        Set<String> srcFields = new LinkedHashSet<String>();
        Map<String, Infer.Column> srcByName = new LinkedHashMap<String, Infer.Column>();
        for (Infer.Column c : s.columns) { srcFields.add(c.field); srcByName.put(c.field, c); }
        for (Infer.Column c : cols) {
            MappingFile.FieldMap f = new MappingFile.FieldMap();
            f.dstTable = target; f.dstField = c.field; f.dstComment = c.comment;
            f.dataElemName = ""; f.dataElemId = "待赋码"; f.status = "待核";
            if (srcFields.contains(c.field)) {
                Infer.Column sc = srcByName.get(c.field);
                f.srcTable = s.name; f.srcField = c.field; f.srcComment = sc.comment;
                f.transformRule = "同名直映"; f.governance = false;
            } else {
                f.srcTable = s.name; f.srcField = ""; f.srcComment = "自动补治理字段";
                f.transformRule = govExpr(c.field, o); f.governance = true;
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
        java.sql.Statement st = db.connection().createStatement();
        try {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + targetDb + "` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci");
            db.connection().commit();
        } finally { st.close(); }
        // DDL 按 ";\n" 拆（与 DbClient.createTable 同口径），USE 头先跑
        for (String stmt : splitStatements(out.ddlSql)) {
            if (stmt.trim().isEmpty()) { continue; }
            java.sql.Statement s = db.connection().createStatement();
            try {
                s.execute(stmt);
                db.connection().commit();
                if (stmt.trim().toUpperCase().startsWith("CREATE TABLE")) { wr.tablesCreated++; }
            } catch (java.sql.SQLException e) {
                wr.errors.add("DDL 失败：" + e.getMessage());
                sink.logError("x [ERROR] LAYER-DDL | " + e.getMessage());
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
                wr.errors.add("转换失败：" + e.getMessage());
                sink.logError("x [ERROR] LAYER-DML | " + e.getMessage());
            } finally { s.close(); }
        }
        return wr;
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
