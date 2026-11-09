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
import java.util.List;

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

        // ads：从 obj＋dom 出，模板聚合＋表 6-4 字段
        LayerGen.LayerOutput ads = LayerGen.genLayer(LayerGen.Layer.ADS, m, src, o);
        if (!ads.ddlSql.contains("USE `ads`;")) { bad++; shown.add("ads 目标库不对"); }
        for (String g : LayerGen.GOV_ADS) {
            if (!ads.ddlSql.contains("`" + g + "`")) { bad++; shown.add("ads 缺通用字段 " + g); }
        }
        if (!ads.transformSql.contains("GROUP BY") || !ads.transformSql.contains("COUNT(*)")) {
            bad++; shown.add("ads 缺模板聚合 GROUP BY/COUNT(*)");
        }

        // 建表语句必须以分号终止：否则整层拼接后 splitStatements 粘连、交付 .sql 在 mysql 客户端跑不动
        if (!dom.ddlSql.contains("COMMENT='dwd_proc_oil';") || !dom.ddlSql.contains("COMMENT='dwd_comp_tech';")) {
            bad++; shown.add("dom DDL 建表语句未以分号终止");
        }
        if (!obj.ddlSql.contains("COMMENT='dim_material';")) {
            bad++; shown.add("obj DDL 建表语句未以分号终止");
        }
        if (!ads.ddlSql.contains("（待核）';")) {
            bad++; shown.add("ads DDL 建表语句未以分号终止");
        }

        // 未绑定：映射里的表在源结构里找不到 → 记 unbound，不掀整轮
        List<LayerGen.SourceTable> partial = new ArrayList<LayerGen.SourceTable>();
        partial.add(src.get(0)); // 只给 t_oil，t_staff 缺
        LayerGen.LayerOutput dom2 = LayerGen.genLayer(LayerGen.Layer.DOM, m, partial, o);
        if (dom2.unbound.isEmpty()) { bad++; shown.add("缺源表却没记 unbound"); }

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
