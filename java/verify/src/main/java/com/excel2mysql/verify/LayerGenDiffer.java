package com.excel2mysql.verify;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 离线撞三层生成：DDL 口径、治理字段齐备、字段映射 100% 覆盖、转换 SQL 形状、依赖与未绑定。 */
public final class LayerGenDiffer {

    public static void main(String[] args) throws IOException {
        int bad = 0;
        List<String> shown = new ArrayList<String>();

        MappingFile m = mapping();
        List<LayerGen.SourceTable> src = source();
        LayerGen.GenOptions o = new LayerGen.GenOptions();

        // dom：t_oil→dwd_proc_oil、t_staff→dwd_comp_tech 两张领域目标表，各自同名直映＋治理字段
        LayerGen.LayerOutput dom = LayerGen.genLayer(LayerGen.Layer.DOM, m, src, o);
        if (dom.tableCount != 2) { bad++; shown.add("dom 表数 期望 2 实际 " + dom.tableCount); }
        if (!dom.ddlSql.contains("USE `dom`;") || !dom.ddlSql.contains("CREATE TABLE `dwd_proc_oil`")
                || !dom.ddlSql.contains("ENGINE=InnoDB") || !dom.ddlSql.contains("utf8mb4_general_ci")) {
            bad++; shown.add("dom DDL 口径不符");
        }
        for (String g : LayerGen.GOV_OBJ_DOM) {
            if (!dom.ddlSql.contains("`" + g + "`")) { bad++; shown.add("dom 缺治理字段 " + g); }
        }
        if (!dom.transformSql.contains("INSERT INTO `dwd_proc_oil`")
                || !dom.transformSql.contains("FROM `ods`.`t_oil`")) {
            bad++; shown.add("dom 转换 SQL 形状不符");
        }
        // 字段映射 100% 覆盖：DDL 里每个目标字段都要有一条映射
        int domCols = countBacktickedCols(dom.ddlSql);
        if (dom.fieldMapping.size() < domCols) {
            bad++; shown.add("dom 字段映射覆盖不足 映射 " + dom.fieldMapping.size() + " < 列 " + domCols);
        }

        // obj：t_oil 命中 dim_material
        LayerGen.LayerOutput obj = LayerGen.genLayer(LayerGen.Layer.OBJ, m, src, o);
        if (!obj.ddlSql.contains("CREATE TABLE `dim_material`")
                || !obj.ddlSql.contains("`material_id`") || !obj.ddlSql.contains("`entity_id`")
                || !obj.ddlSql.contains("`src_entity_id`")) {
            bad++; shown.add("obj dim_material 主键/归一字段不齐");
        }
        if (!obj.transformSql.contains("UUID()") || !obj.transformSql.contains("UNION ALL")) {
            // 单来源时允许无 UNION ALL，但 UUID() 必须在
            if (!obj.transformSql.contains("UUID()")) { bad++; shown.add("obj 缺 entity_id=UUID()"); }
        }

        // ads：模板聚合只 FROM dom（188 口径待核，obj JOIN 未产，注释与 FROM 必须一致）＋表 6-4 字段
        LayerGen.LayerOutput ads = LayerGen.genLayer(LayerGen.Layer.ADS, m, src, o);
        if (!ads.ddlSql.contains("USE `ads`;")) { bad++; shown.add("ads 目标库不对"); }
        for (String g : LayerGen.GOV_ADS) {
            if (!ads.ddlSql.contains("`" + g + "`")) { bad++; shown.add("ads 缺通用字段 " + g); }
        }
        if (!ads.transformSql.contains("GROUP BY") || !ads.transformSql.contains("COUNT(*)")) {
            bad++; shown.add("ads 缺模板聚合 GROUP BY/COUNT(*)");
        }
        // 同一主题装了多个领域目标表时，一个目标一条 INSERT：旧写法只取组里第一张表，
        // 其余领域的表根本没进汇总；idx_value 与 dim_key_json 也要落，不然指标表只有行数没有值。
        if (countOccurrences(ads.transformSql, "INSERT INTO `ads_rc`") != 2) {
            bad++; shown.add("ads 同主题两个领域目标表应各一条 INSERT，实际 "
                    + countOccurrences(ads.transformSql, "INSERT INTO `ads_rc`"));
        }
        if (!ads.transformSql.contains("COUNT(*) AS `idx_value`")) {
            bad++; shown.add("ads 的 idx_value 不是按行数模板填的");
        }
        if (!ads.transformSql.contains("{\"dom\":\"dwd_proc_oil\"}")
                || !ads.transformSql.contains("{\"dom\":\"dwd_comp_tech\"}")) {
            bad++; shown.add("ads 的 dim_key_json 没带领域目标表");
        }
        if (ads.transformSql.contains("dwd_unknown")) {
            bad++; shown.add("ads FROM 兜底成了 dwd_unknown");
        }

        // ---- 合并护栏：领域/子领域是分类不是物理合并键，映射显式同目标也必须有上限 ----
        // 11 张源表写同一个目标表：超来源数上限，整组拒绝，不产 DDL/DML
        MappingFile gm = guardMapping(11, 1);
        List<LayerGen.SourceTable> gs = guardSource(11, 1);
        LayerGen.LayerOutput gdom = LayerGen.genLayer(LayerGen.Layer.DOM, gm, gs, o);
        if (gdom.tableCount != 0 || gdom.ddlSql.contains("CREATE TABLE `dwd_guard_wide`")) {
            bad++; shown.add("超过 " + LayerGen.MAX_MERGE_SOURCES + " 源上限的显式合并仍产出了 dom 表");
        }
        if (!hasWarning(gdom, "dwd_guard_wide", "超过 " + LayerGen.MAX_MERGE_SOURCES + " 张上限")) {
            bad++; shown.add("超来源数的合并没在警告里点名目标表与上限：" + gdom.warnings);
        }
        // ads 不许引用被护栏拒掉的 dom 目标
        LayerGen.LayerOutput gads = LayerGen.genLayer(LayerGen.Layer.ADS, gm, gs, o);
        if (gads.transformSql.contains("dwd_guard_wide")) {
            bad++; shown.add("ads 仍引用被护栏拒掉的领域目标表 dwd_guard_wide");
        }
        if (!hasWarning(gads, "ads 跳过领域目标表 dwd_guard_wide")) {
            bad++; shown.add("ads 没在警告里说明跳过了被拒的 dom 目标：" + gads.warnings);
        }
        // 单源但列数超上限：同样拒绝，不靠降类型/拆段兜
        MappingFile cm = guardMapping(1, 600);
        List<LayerGen.SourceTable> cs = guardSource(1, 600);
        LayerGen.LayerOutput cdom = LayerGen.genLayer(LayerGen.Layer.DOM, cm, cs, o);
        if (cdom.tableCount != 0 || cdom.ddlSql.contains("CREATE TABLE `dwd_guard_wide`")) {
            bad++; shown.add("超过 " + LayerGen.MAX_MERGE_COLUMNS + " 列上限的单表仍产出了 dom 表");
        }
        if (!hasWarning(cdom, "dwd_guard_wide", "超过 " + LayerGen.MAX_MERGE_COLUMNS + " 列上限")) {
            bad++; shown.add("超列数的合并没在警告里点名目标表与上限：" + cdom.warnings);
        }

        // 建表语句必须以分号终止：否则整层拼接后 splitStatements 粘连、交付 .sql 在 mysql 客户端跑不动
        if (!dom.ddlSql.contains("COMMENT='dwd_proc_oil';") || !dom.ddlSql.contains("COMMENT='dwd_comp_tech';")) {
            bad++; shown.add("dom DDL 建表语句未以分号终止");
        }
        if (!obj.ddlSql.contains("COMMENT='dim_material';")) {
            bad++; shown.add("obj DDL 建表语句未以分号终止");
        }
        if (!obj.ddlSql.startsWith("USE `obj`;")) {
            bad++; shown.add("obj DDL 缺 USE `obj`; 头（execute 的一致性校验/前置 USE 全靠它）");
        }
        if (!ads.ddlSql.contains("（待核）';")) {
            bad++; shown.add("ads DDL 建表语句未以分号终止");
        }

        // 未绑定：映射里的表在源结构里找不到 → 记 unbound，不掀整轮
        List<LayerGen.SourceTable> partial = new ArrayList<LayerGen.SourceTable>();
        partial.add(src.get(0)); // 只给 t_oil，t_staff 缺
        LayerGen.LayerOutput dom2 = LayerGen.genLayer(LayerGen.Layer.DOM, m, partial, o);
        if (dom2.unbound.isEmpty()) { bad++; shown.add("缺源表却没记 unbound"); }

        // ---- 多源合并（条款 184 本职：真实层间映射 312 源表 → 26 领域表，23 个多源）----
        // 上面两套夹具都是「一目标一源」，合并路零覆盖，这条补上：两张字段互缺的源表同归一个
        // 领域目标表、同喂一个维度。缺列的源只能插 NULL，所以合并表不许携带源侧 NOT NULL、
        // 不许把两张源的主键并成复合主键。
        MappingFile mm = multiMapping();
        List<LayerGen.SourceTable> msrc = multiSource();
        LayerGen.LayerOutput mdom = LayerGen.genLayer(LayerGen.Layer.DOM, mm, msrc, o);
        DdlTable mdomT = parseTable(mdom.ddlSql, "dwd_multi_oil");
        if (mdom.tableCount != 1 || mdomT.fields.isEmpty()) {
            bad++; shown.add("多源 dom 没合并成一张 dwd_multi_oil 表数 " + mdom.tableCount + " 列 " + mdomT.fields.size());
        } else {
            for (String need : new String[]{"id", "name", "log_id", "amount", "note", "entity_id"}) {
                if (!mdomT.fields.contains(need)) { bad++; shown.add("多源 dom 合并缺列 " + need); }
            }
            List<String> strict = new ArrayList<String>(mdomT.notNull);
            strict.remove("entity_id");
            if (!strict.isEmpty()) {
                bad++; shown.add("多源 dom 合并表携带源侧 NOT NULL（缺列的源插 NULL 就是 1048）：" + strict);
            }
            if (mdomT.pkLines.size() != 1 || !"PRIMARY KEY (`entity_id`)".equals(mdomT.pkLines.get(0))) {
                bad++; shown.add("多源 dom 主键须恰为一条 entity_id 实际 " + mdomT.pkLines);
            }
            // 被喂 NULL 的目标列必须在 DDL 里可空，否则语句在严格模式整表失败
            Set<String> mdomNull = nullFedCols(mdom.transformSql);
            if (!mdomNull.contains("amount") || !mdomNull.contains("log_id")) {
                bad++; shown.add("多源 dom 夹具没跑到「本源缺列插 NULL」amount/log_id 实喂 " + mdomNull);
            }
            for (String f : mdomNull) {
                if (mdomT.notNull.contains(f)) {
                    bad++; shown.add("多源 dom 往 NOT NULL 列喂 NULL：" + f + "（1048，表留空）");
                }
            }
            if (countMapping(mdom.fieldMapping, mdomT.fields) < mdomT.fields.size()) {
                bad++; shown.add("多源 dom 字段映射未覆盖全部合并列");
            }
        }

        // obj：两张源表喂同一个 dim_material
        LayerGen.LayerOutput mobj = LayerGen.genLayer(LayerGen.Layer.OBJ, mm, msrc, o);
        DdlTable mobjT = parseTable(mobj.ddlSql, "dim_material");
        if (mobjT.fields.isEmpty()) {
            bad++; shown.add("多源 obj 没建出 dim_material");
        } else {
            List<String> strict = new ArrayList<String>(mobjT.notNull);
            strict.remove("material_id");
            if (!strict.isEmpty()) {
                bad++; shown.add("多源 obj 维表携带源侧 NOT NULL：" + strict);
            }
            if (mobjT.pkLines.size() != 1 || !"PRIMARY KEY (`material_id`)".equals(mobjT.pkLines.get(0))) {
                bad++; shown.add("多源 obj 维表主键须恰为一条 material_id 实际 " + mobjT.pkLines);
            }
            for (String f : nullFedCols(mobj.transformSql)) {
                if (mobjT.notNull.contains(f)) { bad++; shown.add("多源 obj 往 NOT NULL 列喂 NULL：" + f); }
            }
        }
        // 去重形状：DISTINCT 圈业务列（含作 src_entity_id 的源主键），生成键（UUID()/NOW()/字面量）放外层
        // ——SELECT DISTINCT UUID() 里每行 UUID 都不同，DISTINCT 永远撞不出重复行（条款 183/186）
        if (mobj.transformSql.contains("SELECT DISTINCT UUID()")) {
            bad++; shown.add("obj 仍是 SELECT DISTINCT UUID() 旧形状：每行 UUID 不同，DISTINCT 不去重");
        }
        int branches = msrc.size();
        if (countOccurrences(mobj.transformSql, "FROM (SELECT DISTINCT") != branches) {
            bad++; shown.add("obj 去重派生表条数 期望 " + branches + "（每源一支）实际 "
                    + countOccurrences(mobj.transformSql, "FROM (SELECT DISTINCT"));
        }
        for (String proj : distinctProjections(mobj.transformSql)) {
            if (proj.contains("UUID()")) { bad++; shown.add("obj DISTINCT 投影里还有生成键：" + proj); }
            if (!proj.contains("`name`") && !proj.contains("`amount`")) {
                bad++; shown.add("obj DISTINCT 投影没圈业务列：" + proj);
            }
        }
        // 证据同源：<层>_field_mapping.csv 的「转换规则」必须就是 SQL 实发的那个表达式
        for (MappingFile.FieldMap f : mobj.fieldMapping) {
            if (!f.governance || f.transformRule == null) { continue; }
            if (!mobj.transformSql.contains(f.transformRule)) {
                bad++; shown.add("obj 映射 CSV 与转换 SQL 不同源：" + f.dstField + " CSV「"
                        + f.transformRule + "」SQL 无此表达式");
            }
        }
        for (MappingFile.FieldMap f : mdom.fieldMapping) {
            if (!f.governance || f.transformRule == null) { continue; }
            if (!mdom.transformSql.contains(f.transformRule)) {
                bad++; shown.add("dom 映射 CSV 与转换 SQL 不同源：" + f.dstField + " CSV「" + f.transformRule + "」");
            }
        }

        // ads 注释诚实：模板聚合只 FROM dom（188 口径待核），不许声称产了 obj JOIN
        if (ads.transformSql.contains("JOIN") || ads.transformSql.contains("obj.")) {
            bad++; shown.add("ads 模板出现了 obj JOIN，与段注释口径需同步改写");
        }

        // ---- 指标与注释：数值列进 ads 当指标、系统/未归类列不进层、治理字段注释与取值要能读 ----
        MappingFile mm2 = measureMapping();
        List<LayerGen.SourceTable> ms2 = measureSource();
        LayerGen.LayerOutput mdom2 = LayerGen.genLayer(LayerGen.Layer.DOM, mm2, ms2, o);
        LayerGen.LayerOutput mobj2 = LayerGen.genLayer(LayerGen.Layer.OBJ, mm2, ms2, o);
        LayerGen.LayerOutput mads2 = LayerGen.genLayer(LayerGen.Layer.ADS, mm2, ms2, o);
        if (mdom2.tableCount != 1 || mdom2.ddlSql.contains("`create_time`")) {
            bad++; shown.add("dom 未按系统字段剔除 create_time（表数 " + mdom2.tableCount + "）");
        }
        if (mdom2.ddlSql.contains("`xq_ab`")) {
            bad++; shown.add("dom 仍收下无注释且无可识别语义的 xq_ab");
        }
        if (mobj2.ddlSql.contains("`xq_ab`") || mobj2.ddlSql.contains("`create_time`")) {
            bad++; shown.add("obj 未剔除系统/未归类列");
        }
        if (!mdom2.ddlSql.contains("`amount`") || !mdom2.ddlSql.contains("`rate`")) {
            bad++; shown.add("dom 把金额/比率这类指标列也丢了");
        }
        // 治理字段：注释不再是一句「治理字段」，能回指源行的就回指，定不出业务值的给可核常量
        for (LayerGen.LayerOutput lo : new LayerGen.LayerOutput[]{mdom2, mobj2}) {
            if (lo.ddlSql.contains("治理字段（表 5-3/6-4）")) {
                bad++; shown.add(lo.layer + " 治理字段注释仍是占位句");
            }
            if (!lo.ddlSql.contains("实体唯一标识")) {
                bad++; shown.add(lo.layer + " 缺 entity_id 的可读注释");
            }
        }
        if (!mdom2.transformSql.contains("`id` AS `src_entity_id`")
                || !mdom2.transformSql.contains("CONCAT('t_m_', `id`) AS `std_code`")) {
            bad++; shown.add("dom 的 src_entity_id/std_code 没回指源主键："
                    + firstLines(mdom2.transformSql, 3));
        }
        // ads：有数值指标就按 SUM/AVG 出，单位从注释括号取；不再一律按行数 COUNT(*)
        if (!mads2.transformSql.contains("SUM(`amount`) AS `idx_value`")) {
            bad++; shown.add("ads 没把 amount 当可求和指标：" + firstLines(mads2.transformSql, 3));
        }
        if (!mads2.transformSql.contains("AVG(`rate`) AS `idx_value`")) {
            bad++; shown.add("ads 没把比率列 rate 按 AVG 聚合");
        }
        if (!mads2.transformSql.contains("'元' AS `idx_unit`")
                || !mads2.transformSql.contains("'%' AS `idx_unit`")) {
            bad++; shown.add("ads 指标单位没从注释里取出来");
        }
        if (countOccurrences(mads2.transformSql, "INSERT INTO `ads_analysis`") != 2) {
            bad++; shown.add("ads 两个指标应各一条 INSERT，实际 "
                    + countOccurrences(mads2.transformSql, "INSERT INTO `ads_analysis`"));
        }
        if (countOccurrences(mads2.transformSql, "COUNT(*) AS `idx_value`") != 0) {
            bad++; shown.add("ads 已有真实指标却仍按行数填 idx_value");
        }
        if (!mads2.ddlSql.contains("`idx_value` DECIMAL(38,6)")
                || mads2.ddlSql.contains("治理字段（表 5-3/6-4）")) {
            bad++; shown.add("ads 指标值列类型或治理字段注释没跟上");
        }

        System.out.println("- [INFO] LAYER-DIFF | 不一致 " + bad + " 处");
        for (String s : shown) { System.out.println("     " + s); }
        if (bad > 0) { System.out.println("x [ERROR] LAYER-DIFF | 三层生成与口径不符"); System.exit(1); }
        System.out.println("+ [OK] LAYER-DIFF | DDL 口径、治理字段、字段映射覆盖、转换 SQL、未绑定逐条一致");
    }

    private static int countBacktickedCols(String ddl) {
        // 数 CREATE TABLE 体里以 "  `字段`" 开头的行（排除 PRIMARY KEY）
        int n = 0;
        for (String line : ddl.split("\n")) {
            String t = line.trim();
            if (t.startsWith("`") && !t.startsWith("PRIMARY")) { n++; }
        }
        return n;
    }

    /** CREATE TABLE 体的最小解析：列序、哪些列 NOT NULL、有几条 PRIMARY KEY。 */
    private static final class DdlTable {
        final List<String> fields = new ArrayList<String>();
        final Set<String> notNull = new LinkedHashSet<String>();
        final List<String> pkLines = new ArrayList<String>();
    }

    private static DdlTable parseTable(String ddl, String table) {
        DdlTable t = new DdlTable();
        boolean in = false;
        for (String line : ddl.split("\n")) {
            String s = line.trim();
            if (!in) {
                if (s.startsWith("CREATE TABLE `" + table + "`")) { in = true; }
                continue;
            }
            if (s.startsWith(")")) { in = false; continue; }
            if (s.startsWith("PRIMARY KEY")) {
                t.pkLines.add(s.endsWith(",") ? s.substring(0, s.length() - 1).trim() : s);
                continue;
            }
            if (s.startsWith("`") && s.indexOf('`', 1) > 1) {
                String name = s.substring(1, s.indexOf('`', 1));
                t.fields.add(name);
                if (s.contains("NOT NULL")) { t.notNull.add(name); }
            }
        }
        return t;
    }

    /** 转换 SQL 里被喂 NULL 的目标列名集合——「NULL AS `x`」的每一支都算（多源合并的合法命门）。 */
    private static Set<String> nullFedCols(String sql) {
        Set<String> out = new LinkedHashSet<String>();
        String mark = "NULL AS `";
        int i = 0;
        while ((i = sql.indexOf(mark, i)) >= 0) {
            int j = sql.indexOf('`', i + mark.length());
            if (j < 0) { break; }
            out.add(sql.substring(i + mark.length(), j));
            i = j;
        }
        return out;
    }

    /** obj 各支里 DISTINCT 的投影串（"SELECT DISTINCT " 到其后的 " FROM " 之间）。 */
    private static List<String> distinctProjections(String sql) {
        List<String> out = new ArrayList<String>();
        String mark = "SELECT DISTINCT ";
        int i = 0;
        while ((i = sql.indexOf(mark, i)) >= 0) {
            int from = sql.indexOf(" FROM ", i + mark.length());
            if (from < 0) { break; }
            out.add(sql.substring(i + mark.length(), from));
            i = from;
        }
        return out;
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) { n++; }
        return n;
    }

    private static int countMapping(List<MappingFile.FieldMap> rows, List<String> fields) {
        Set<String> hit = new LinkedHashSet<String>();
        for (MappingFile.FieldMap f : rows) {
            if (fields.contains(f.dstField)) { hit.add(f.dstField); }
        }
        return hit.size();
    }

    /** 护栏夹具：rows 行同写 dwd_guard_wide，每行字段数 fields（只写进映射，不参与列数判断）。 */
    private static MappingFile guardMapping(int rows, int fields) throws IOException {
        String header = "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            body.append("保障,基础数据,守卫表").append(i).append(",s").append(i)
                    .append(',').append(fields).append(",f0,保障,DOM01,基础数据,dwd_guard_wide,")
                    .append("dim_org,综合分析,护栏,自动识别,\n");
        }
        File f = File.createTempFile("layerdiff-guard-", ".csv");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write('\ufeff'); w.write(header); w.write(body.toString()); } finally { w.close(); }
        return MappingFile.loadTableCsv(f.getPath());
    }

    /** 护栏夹具的源结构：tables 张表，每张 fields 个 VARCHAR(255) 列。 */
    private static List<LayerGen.SourceTable> guardSource(int tables, int fields) {
        List<LayerGen.SourceTable> out = new ArrayList<LayerGen.SourceTable>();
        for (int i = 0; i < tables; i++) {
            List<Infer.Column> cs = new ArrayList<Infer.Column>();
            for (int f = 0; f < fields; f++) {
                cs.add(mcol("f" + f, f == 0, false));
            }
            out.add(new LayerGen.SourceTable("s" + i, "守卫表" + i, cs));
        }
        return out;
    }

    private static boolean hasWarning(LayerGen.LayerOutput out, String... parts) {
        for (String w : out.warnings) {
            boolean all = true;
            for (String p : parts) {
                if (!w.contains(p)) { all = false; break; }
            }
            if (all) { return true; }
        }
        return false;
    }

    private static MappingFile multiMapping() throws IOException {
        String header = "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n";
        // 两张源表字段互缺（t_oil 无 log_id/amount/note、t_oil_log 无 name），却同归一张领域表、同喂一个维度
        String body =
                "采购供应,油料,油料账,t_oil,2,id,采购供应,proc,油料保障,dwd_multi_oil,dim_material,rc,关键词,待核,\n"
                + "采购供应,油料,油料出入库流水,t_oil_log,3,log_id,采购供应,proc,油料保障,dwd_multi_oil,dim_material,rc,关键词,待核,\n";
        File f = File.createTempFile("layerdiff-multi-", ".csv");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write('﻿'); w.write(header); w.write(body); } finally { w.close(); }
        return MappingFile.loadTableCsv(f.getPath());
    }

    /** 多源夹具的源结构：两张表各自带 NOT NULL 业务列，合并后谁都不许留着这个约束。 */
    private static List<LayerGen.SourceTable> multiSource() {
        List<LayerGen.SourceTable> s = new ArrayList<LayerGen.SourceTable>();
        List<Infer.Column> a = new ArrayList<Infer.Column>();
        a.add(mcol("id", true, true));
        a.add(mcol("name", false, true));
        s.add(new LayerGen.SourceTable("t_oil", "油料账", a));
        List<Infer.Column> b = new ArrayList<Infer.Column>();
        b.add(mcol("log_id", true, true));
        b.add(mcol("amount", false, true));
        b.add(mcol("note", false, false));
        s.add(new LayerGen.SourceTable("t_oil_log", "油料出入库流水", b));
        return s;
    }

    private static Infer.Column mcol(String field, boolean pk, boolean notNull) {
        Infer.Column c = new Infer.Column();
        c.field = field; c.type = "VARCHAR(255)"; c.comment = field; c.maxLen = 255;
        c.primaryKey = pk; c.nullable = notNull ? Boolean.FALSE : Boolean.TRUE;
        return c;
    }

    private static MappingFile measureMapping() throws IOException {
        String header = "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n";
        String body = "采购供应,油料,油料账,t_m,5,id,采购供应,proc,油料保障,dwd_meas,dim_material,综合分析,关键词,待核,\n";
        File f = File.createTempFile("layerdiff-measure-", ".csv");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write('﻿'); w.write(header); w.write(body); } finally { w.close(); }
        return MappingFile.loadTableCsv(f.getPath());
    }

    /** 指标/剔除夹具：金额（元）、比率、系统审计列、无注释的乱码列各一。 */
    private static List<LayerGen.SourceTable> measureSource() {
        List<Infer.Column> cs = new ArrayList<Infer.Column>();
        Infer.Column id = new Infer.Column();
        id.field = "id"; id.type = "VARCHAR(64)"; id.comment = "主键"; id.maxLen = 64;
        id.primaryKey = true; id.nullable = Boolean.FALSE;
        Infer.Column amount = new Infer.Column();
        amount.field = "amount"; amount.type = "INT"; amount.comment = "出库金额（元）";
        amount.maxLen = 11; amount.nullable = Boolean.TRUE;
        Infer.Column rate = new Infer.Column();
        rate.field = "rate"; rate.type = "DECIMAL(10,4)"; rate.comment = "合格率";
        rate.maxLen = 12; rate.nullable = Boolean.TRUE;
        Infer.Column ct = new Infer.Column();
        ct.field = "create_time"; ct.type = "DATETIME"; ct.comment = "创建时间";
        ct.maxLen = 19; ct.nullable = Boolean.TRUE;
        Infer.Column junk = new Infer.Column();
        junk.field = "xq_ab"; junk.type = "VARCHAR(255)"; junk.comment = "";
        junk.maxLen = 255; junk.nullable = Boolean.TRUE;
        cs.add(id); cs.add(amount); cs.add(rate); cs.add(ct); cs.add(junk);
        List<LayerGen.SourceTable> out = new ArrayList<LayerGen.SourceTable>();
        out.add(new LayerGen.SourceTable("t_m", "油料账", cs));
        return out;
    }

    private static String firstLines(String s, int n) {
        String[] parts = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < n; i++) {
            if (sb.length() > 0) { sb.append(" | "); }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static MappingFile mapping() throws IOException {
        String header = "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n";
        String body =
                "采购供应,油料,油料账,t_oil,2,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n"
                + "综合,人事,人员表,t_staff,2,id,综合,comp,科技管理,dwd_comp_tech,dim_person,rc,关键词,待核,\n";
        File f = File.createTempFile("layerdiff-", ".csv");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write('﻿'); w.write(header); w.write(body); } finally { w.close(); }
        return MappingFile.loadTableCsv(f.getPath());
    }

    private static List<LayerGen.SourceTable> source() {
        List<LayerGen.SourceTable> s = new ArrayList<LayerGen.SourceTable>();
        s.add(new LayerGen.SourceTable("t_oil", "油料账", cols("id", "name")));
        s.add(new LayerGen.SourceTable("t_staff", "人员表", cols("id", "person_name")));
        return s;
    }

    private static List<Infer.Column> cols(String... names) {
        List<Infer.Column> out = new ArrayList<Infer.Column>();
        for (String n : names) {
            Infer.Column c = new Infer.Column();
            c.field = n; c.type = "VARCHAR(255)"; c.comment = n; c.maxLen = 255;
            c.primaryKey = "id".equals(n); c.nullable = Boolean.TRUE;
            out.add(c);
        }
        return out;
    }
}
