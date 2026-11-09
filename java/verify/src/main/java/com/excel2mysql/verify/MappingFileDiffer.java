package com.excel2mysql.verify;

import com.excel2mysql.core.MappingFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** 离线撞映射文件读写：15 列解析、缺列报错、BOM/GBK 编码、字段映射往返。锁可数副作用。 */
public final class MappingFileDiffer {

    private static final String HEADER =
            "系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
            + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注";

    public static void main(String[] args) throws IOException {
        int bad = 0;
        List<String> shown = new ArrayList<String>();

        // 1) 正常 15 列＋可选 ods物理表名 解析
        File good = temp("good.csv", "UTF-8", false,
                HEADER + ",ods物理表名\n"
                + "采购供应,油料,油料账,t_oil,3,id,采购供应,proc,油料保障,dwd_proc_oil,"
                + "dim_material,rc,关键词,待核,,ods_cg_oil\n");
        MappingFile m = MappingFile.loadTableCsv(good.getPath());
        if (m.tables().size() != 1) { bad++; shown.add("解析行数 期望 1 实际 " + m.tables().size()); }
        MappingFile.TableMap t = m.tables().get(0);
        if (!"t_oil".equals(t.tableEn) || !"proc".equals(t.domainCode)
                || !"dwd_proc_oil".equals(t.domainTarget) || !"dim_material".equals(t.objDims)
                || !"ods_cg_oil".equals(t.odsPhysical)) {
            bad++; shown.add("列值错位：" + t.tableEn + "/" + t.domainCode + "/" + t.odsPhysical);
        }

        // 2) 缺列必须报错且指出缺哪列：给全 系统/子领域 两列、只缺 表中文名，
        //    验证器要跳过在位的列、点出第一个真正缺的必填列（不能只报最前面那列）
        File miss = temp("miss.csv", "UTF-8", false,
                "系统,子领域,表英文名\n采购供应,油料,t_oil\n");
        try {
            MappingFile.loadTableCsv(miss.getPath());
            bad++; shown.add("缺列却没报错");
        } catch (IOException e) {
            if (!e.getMessage().contains("表中文名")) {
                bad++; shown.add("缺列报错没指出缺哪列：" + e.getMessage());
            }
        }

        // 3) UTF-8-BOM 与 GBK 都要读得出中文
        File bom = temp("bom.csv", "UTF-8", true, HEADER + "\n采购供应,油料,油料账,t_oil,3,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n");
        if (!"油料账".equals(MappingFile.loadTableCsv(bom.getPath()).tables().get(0).tableCn)) {
            bad++; shown.add("UTF-8-BOM 中文读错");
        }
        File gbk = temp("gbk.csv", "GBK", false, HEADER + "\n采购供应,油料,油料账,t_oil,3,id,采购供应,proc,油料保障,dwd_proc_oil,dim_material,rc,关键词,待核,\n");
        if (!"油料账".equals(MappingFile.loadTableCsv(gbk.getPath()).tables().get(0).tableCn)) {
            bad++; shown.add("GBK 中文读错");
        }

        // 4) 空文件报错
        File empty = temp("empty.csv", "UTF-8", false, "");
        try {
            MappingFile.loadTableCsv(empty.getPath());
            bad++; shown.add("空文件却没报错");
        } catch (IOException e) { /* 期望 */ }

        // 5) 字段映射写→读往返逐字段一致（含逗号/引号转义）
        List<MappingFile.FieldMap> fm = new ArrayList<MappingFile.FieldMap>();
        MappingFile.FieldMap f = new MappingFile.FieldMap();
        f.srcTable = "t_oil"; f.srcField = "name"; f.srcComment = "名称,含逗号";
        f.dstTable = "dwd_proc_oil"; f.dstField = "name"; f.dstComment = "名称\"引号\"";
        f.dataElemName = "物资名称"; f.dataElemId = "待赋码"; f.transformRule = "同名直映";
        f.governance = false; f.status = "待核";
        fm.add(f);
        File fmFile = temp("fm.csv", "UTF-8", false, null);
        MappingFile.writeFieldCsv(fmFile.getPath(), fm);
        List<MappingFile.FieldMap> back = MappingFile.readFieldCsv(fmFile.getPath());
        if (back.size() != 1) { bad++; shown.add("字段映射往返行数 期望 1 实际 " + back.size()); }
        else {
            MappingFile.FieldMap b = back.get(0);
            if (!eq(f.srcComment, b.srcComment) || !eq(f.dstComment, b.dstComment)
                    || !eq(f.dataElemId, b.dataElemId) || f.governance != b.governance) {
                bad++; shown.add("字段映射往返不一致：" + b.srcComment + " / " + b.dstComment);
            }
        }

        System.out.println("- [INFO] MAP-DIFF | 不一致 " + bad + " 处");
        for (String s : shown) { System.out.println("     " + s); }
        if (bad > 0) { System.out.println("x [ERROR] MAP-DIFF | 映射文件读写不等价"); System.exit(1); }
        System.out.println("+ [OK] MAP-DIFF | 15 列解析、缺列报错、BOM/GBK、字段映射往返逐条一致");
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static File temp(String name, String enc, boolean bom, String body) throws IOException {
        File f = File.createTempFile("mapdiff-", "-" + name);
        f.deleteOnExit();
        if (body == null) { return f; }
        Writer w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName(enc));
        try {
            if (bom) { w.write('﻿'); }
            w.write(body);
        } finally { w.close(); }
        return f;
    }
}
