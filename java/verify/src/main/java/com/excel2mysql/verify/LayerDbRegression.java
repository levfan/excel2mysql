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
            // 造源库与源表：t_oil（2 行）之外，再给两张「同归一个领域目标表」的源表——
            // 条款 184 的实况是 312 源表整编进 26 领域表、23 个多源（最宽 dwd_med_care 56 源），
            // 一表一源的夹具撞不到合并路，这里用真 MySQL 撞一次。
            exec(db, "CREATE DATABASE `" + SRC + "` DEFAULT CHARSET utf8mb4");
            created.add(SRC);
            exec(db, "CREATE TABLE `" + SRC + "`.`t_oil` (`id` VARCHAR(64) NOT NULL, `name` VARCHAR(255) NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB");
            exec(db, "INSERT INTO `" + SRC + "`.`t_oil` VALUES ('1','油料甲'),('2','油料乙')");
            // 与 t_oil 字段互缺、还各带 NOT NULL 业务列：合并后谁都得能往里插 NULL
            exec(db, "CREATE TABLE `" + SRC + "`.`t_oil_log` (`log_id` VARCHAR(64) NOT NULL, `amount` INT NOT NULL, `note` VARCHAR(255), PRIMARY KEY (`log_id`)) ENGINE=InnoDB");
            exec(db, "INSERT INTO `" + SRC + "`.`t_oil_log` VALUES ('l1',10,'甲'),('l2',20,NULL),('l3',30,'丙')");
            // 无主键、且有两行完全相同：只喂维度，用来真撞「派生表 DISTINCT 去重、生成键在外层」
            exec(db, "CREATE TABLE `" + SRC + "`.`t_oil_use` (`use_id` VARCHAR(64), `material_name` VARCHAR(255), `qty` INT) ENGINE=InnoDB");
            exec(db, "INSERT INTO `" + SRC + "`.`t_oil_use` VALUES ('u1','油料甲',10),('u1','油料甲',10),('u2','油料乙',5)");

            List<LayerGen.SourceTable> src = LayerGen.readSource(db, SRC);
            if (src.size() != 3) {
                bad++; System.out.println("x [ERROR] READ-SOURCE | 源结构读取 期望 3 表 实际 " + src.size());
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
                if (layer == LayerGen.Layer.DOM && (wr.tablesCreated != 1 || wr.rowsAffected != 5)) {
                    bad++; System.out.println("x [ERROR] WRITE-RESULT | dom 期望 建表1/影响5 实际 "
                            + wr.tablesCreated + "/" + wr.rowsAffected);
                }
            }

            // 多源合并真跑通：两张字段互缺的源表灌进同一张 dwd_proc_oil，缺列那一支插 NULL
            long domRows = count(db, DOM, "dwd_proc_oil");
            if (domRows != 5) { bad++; System.out.println("x [ERROR] COUNT | dom 合并表 dwd_proc_oil 期望 5 行 实际 " + domRows); }
            long fromOil = scalarLong(db, "SELECT COUNT(*) FROM `" + DOM + "`.`dwd_proc_oil` WHERE `name` IS NOT NULL");
            long fromLog = scalarLong(db, "SELECT COUNT(*) FROM `" + DOM + "`.`dwd_proc_oil` WHERE `log_id` IS NOT NULL");
            if (fromOil != 2 || fromLog != 3) {
                bad++; System.out.println("x [ERROR] MERGE | 合并表两源行数 期望 2/3 实际 " + fromOil + "/" + fromLog);
            }
            // 主键归 entity_id（每行 UUID()）：非空且 5 行各不相同，复合主键／NULL 主键都过不了这两条
            long pkNonNull = scalarLong(db, "SELECT COUNT(*) FROM `" + DOM + "`.`dwd_proc_oil` WHERE `entity_id` IS NOT NULL");
            long pkDistinct = scalarLong(db, "SELECT COUNT(DISTINCT `entity_id`) FROM `" + DOM + "`.`dwd_proc_oil`");
            if (pkNonNull != 5 || pkDistinct != 5) {
                bad++; System.out.println("x [ERROR] PK | dom entity_id 主键 非空/唯一 期望 5/5 实际 "
                        + pkNonNull + "/" + pkDistinct);
            }
            long objRows = count(db, OBJ, "dim_material");
            // 2（t_oil）＋3（t_oil_log）＋2（t_oil_use 三行里有两行完全相同，去重后塌成 2）
            if (objRows != 7) { bad++; System.out.println("x [ERROR] COUNT | obj dim_material 期望 7 行 实际 " + objRows); }
            long dedup = scalarLong(db, "SELECT COUNT(*) FROM `" + OBJ + "`.`dim_material` WHERE `src_entity_id` = 'u1'");
            if (dedup != 1) {
                bad++; System.out.println("x [ERROR] DEDUP | obj 派生表去重 期望 src_entity_id='u1' 1 行 实际 " + dedup
                        + "（UUID() 混进 DISTINCT 就永不去重）");
            }
            // entity_id 必须是 UUID 且非空、src_entity_id 保留来源
            String entityId = scalar(db, "SELECT `entity_id` FROM `" + OBJ + "`.`dim_material` LIMIT 1");
            if (entityId == null || entityId.length() < 32) { bad++; System.out.println("x [ERROR] UUID | obj entity_id 不像 UUID：" + entityId); }
            // ads 模板聚合须真的执行成功：六个主题固定建表，映射里的旧主题名 rc 归入后勤综合主题，
            // ads_general 恰好 1 行（锁 execute 不因 "--" 前缀把注释后的 INSERT 丢掉）
            long adsRows = count(db, ADS, "ads_general");
            if (adsRows != 1) { bad++; System.out.println("x [ERROR] COUNT | ads ads_general 期望 1 行 实际 " + adsRows); }
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
        // 前两行同归 dwd_proc_oil（多源合并）；t_oil_use 不设领域目标表，只喂维度（撞去重用）
        String body = "采购供应,油料,油料账,t_oil,2,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n"
                + "采购供应,油料,油料出入库流水,t_oil_log,3,log_id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n"
                + "采购供应,油料,油料消耗,t_oil_use,3,,采购供应,proc,油料保障,,dim_material,rc,关键词,待核,\n";
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

    /** 单值计数查询：表没建成/列不存在时记一条可读 FAIL 返回 -1，让流程走到跑后残留校验。 */
    private static long scalarLong(DbClient db, String sql) throws Exception {
        Statement st = null;
        try {
            st = db.connection().createStatement();
            ResultSet rs = st.executeQuery(sql);
            long n = rs.next() ? rs.getLong(1) : -1L; rs.close(); return n;
        } catch (java.sql.SQLException e) {
            System.out.println("x [ERROR] SCALAR | 计数失败：" + e.getMessage());
            return -1L;
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
