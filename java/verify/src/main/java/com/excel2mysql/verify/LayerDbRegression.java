package com.excel2mysql.verify;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 真库撞入库模式：探针库建表灌数读回，跑前跑后库清单零残留。凭据走 E2M_*，不落文件。 */
public final class LayerDbRegression {

    private static final String SRC = "e2mlayerprobe_src";
    private static final String OBJ = "e2mlayerprobe_obj";
    private static final String DOM = "e2mlayerprobe_dom";
    private static final String ADS = "e2mlayerprobe_ads";

    public static void main(String[] args) throws Exception {
        Config cfg = Config.discover(new ArrayList<String>());
        if (!cfg.hasDatabase()) {
            System.out.println("x [ERROR] LAYER-DB | 没指定库（E2M_DB），拒绝建探针库");
            System.exit(2);
        }
        DbClient db = new DbClient(cfg);
        int bad = 0;
        Set<String> before = databases(db.connection());
        for (String p : new String[]{SRC, OBJ, DOM, ADS}) {
            if (before.contains(p)) {
                System.out.println("x [ERROR] LAYER-DB | 已有同名探针库，先清掉：" + p);
                System.exit(4);
            }
        }
        List<String> created = new ArrayList<String>();
        try {
            // 造源库与一张源表（两行）
            exec(db, "CREATE DATABASE `" + SRC + "` DEFAULT CHARSET utf8mb4");
            created.add(SRC);
            exec(db, "CREATE TABLE `" + SRC + "`.`t_oil` (`id` VARCHAR(64) NOT NULL, `name` VARCHAR(255), PRIMARY KEY (`id`)) ENGINE=InnoDB");
            exec(db, "INSERT INTO `" + SRC + "`.`t_oil` VALUES ('1','油料甲'),('2','油料乙')");

            List<LayerGen.SourceTable> src = LayerGen.readSource(db, SRC);
            if (src.size() != 1) {
                bad++; System.out.println("x [ERROR] READ-SOURCE | 源结构读取 期望 1 表 实际 " + src.size());
            } else if (src.get(0).columns.size() != 2) {
                bad++; System.out.println("x [ERROR] READ-SOURCE | t_oil 期望 2 列 实际 " + src.get(0).columns.size());
            }

            MappingFile m = mapping();
            LayerGen.GenOptions o = new LayerGen.GenOptions();
            o.sourceDb = SRC; o.objDb = OBJ; o.domDb = DOM; o.adsDb = ADS; o.writeMode = true;

            for (LayerGen.Layer layer : new LayerGen.Layer[]{LayerGen.Layer.DOM, LayerGen.Layer.OBJ, LayerGen.Layer.ADS}) {
                LayerGen.LayerOutput out = LayerGen.genLayer(layer, m, src, o);
                String targetDb = layer == LayerGen.Layer.DOM ? DOM : layer == LayerGen.Layer.OBJ ? OBJ : ADS;
                if (!created.contains(targetDb)) { created.add(targetDb); } // 先登记：execute 若中途抛，探针库也在清理账上
                LayerGen.WriteResult wr = LayerGen.execute(out, targetDb, db, ImportParse.LOG_ONLY);
                if (!wr.errors.isEmpty()) { bad++; System.out.println("x [ERROR] EXEC | " + layer + " " + wr.errors); }
                if (layer == LayerGen.Layer.DOM && (wr.tablesCreated != 1 || wr.rowsAffected != 2)) {
                    bad++; System.out.println("x [ERROR] WRITE-RESULT | dom 期望 建表1/影响2 实际 "
                            + wr.tablesCreated + "/" + wr.rowsAffected);
                }
            }

            long domRows = count(db, DOM, "dwd_proc_oil");
            if (domRows != 2) { bad++; System.out.println("x [ERROR] COUNT | dom dwd_proc_oil 期望 2 行 实际 " + domRows); }
            long objRows = count(db, OBJ, "dim_material");
            if (objRows != 2) { bad++; System.out.println("x [ERROR] COUNT | obj dim_material 期望 2 行 实际 " + objRows); }
            // entity_id 必须是 UUID 且非空、src_entity_id 保留来源
            String entityId = scalar(db, "SELECT `entity_id` FROM `" + OBJ + "`.`dim_material` LIMIT 1");
            if (entityId == null || entityId.length() < 32) { bad++; System.out.println("x [ERROR] UUID | obj entity_id 不像 UUID：" + entityId); }
            // ads 模板聚合须真的执行成功：ads_rc 恰好 1 行（锁 execute 不因 "--" 前缀把注释后的 INSERT 丢掉）
            long adsRows = count(db, ADS, "ads_rc");
            if (adsRows != 1) { bad++; System.out.println("x [ERROR] COUNT | ads ads_rc 期望 1 行 实际 " + adsRows); }
        } finally {
            for (String d : created) {
                try { exec(db, "DROP DATABASE IF EXISTS `" + d + "`"); } catch (Exception ignore) { }
            }
            db.close();
        }
        Set<String> after = databasesViaNew(cfg);
        if (!after.equals(before)) {
            bad++;
            Set<String> extra = new LinkedHashSet<String>(after); extra.removeAll(before);
            System.out.println("x [ERROR] SIDE-EFFECT | 跑完库清单变了 多：" + extra);
        }
        System.out.println("- [INFO] LAYER-DB | 不一致 " + bad + " 项");
        if (bad > 0) { System.out.println("x [ERROR] LAYER-DB | 真库入库回归未通过"); System.exit(1); }
        System.out.println("+ [OK] LAYER-DB | 探针库真建表真灌数读回一致，库清单零残留");
    }

    private static MappingFile mapping() throws IOException {
        String header = "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n";
        String body = "采购供应,油料,油料账,t_oil,2,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n";
        File f = File.createTempFile("layerdb-", ".csv"); f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write('﻿'); w.write(header); w.write(body); } finally { w.close(); }
        return MappingFile.loadTableCsv(f.getPath());
    }

    private static void exec(DbClient db, String sql) throws Exception {
        Statement st = db.connection().createStatement();
        try { st.execute(sql); db.connection().commit(); } finally { st.close(); }
    }

    private static long count(DbClient db, String schema, String table) throws Exception {
        Statement st = null;
        try {
            st = db.connection().createStatement();
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`");
            rs.next(); long n = rs.getLong(1); rs.close(); return n;
        } catch (java.sql.SQLException e) {
            // 表没建成时 executeQuery 会抛：这里报可读 FAIL 并返回 -1，让调用点计入 bad、
            // 流程继续走到跑后残留校验（否则异常掀掉 main，SIDE-EFFECT 那条最该跑的反而没跑）
            System.out.println("x [ERROR] COUNT | " + schema + "." + table + " 读行数失败：" + e.getMessage());
            return -1L;
        } finally { if (st != null) { st.close(); } }
    }

    private static String scalar(DbClient db, String sql) throws Exception {
        Statement st = null;
        try {
            st = db.connection().createStatement();
            ResultSet rs = st.executeQuery(sql);
            String s = rs.next() ? rs.getString(1) : null; rs.close(); return s;
        } catch (java.sql.SQLException e) {
            System.out.println("x [ERROR] SCALAR | 取值失败：" + e.getMessage());
            return null;
        } finally { if (st != null) { st.close(); } }
    }

    private static Set<String> databases(Connection c) throws Exception {
        Set<String> out = new LinkedHashSet<String>();
        Statement st = c.createStatement();
        try { ResultSet rs = st.executeQuery("SHOW DATABASES"); while (rs.next()) { out.add(rs.getString(1)); } rs.close(); }
        finally { st.close(); }
        return out;
    }

    private static Set<String> databasesViaNew(Config cfg) throws Exception {
        DbClient db = new DbClient(cfg);
        try { return databases(db.connection()); } finally { db.close(); }
    }
}
