package com.excel2mysql.verify;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;
import com.excel2mysql.core.RowFit;
import com.excel2mysql.core.Taxonomy;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实目录端到端检查：拿「全局字段清单.csv + 层间映射 CSV」跑一遍 tab4 分类与三层组装，
 * 断言「全部源表都绑上、十类维度都命中、六主题都产聚合、每张 DDL 都过 MySQL 行上限」。
 *
 * <p>与 LayerGenDiffer 的分工：那个用合成夹具守住生成器逻辑，这个用现场整批数据守住
 * 「数据 × 程序」联合口径。不依赖外部固定路径，目录由参数给，因此不进默认构建闸门。
 *
 * <pre>
 * java -cp "app/target/classes;verify/target/classes" com.excel2mysql.verify.RealCorpusCheck \
 *      全局字段清单.csv 层间映射_六主题.csv
 * </pre>
 */
public final class RealCorpusCheck {

    private RealCorpusCheck() { }

    private static final Pattern COL = Pattern.compile(
            "^\\s+`([^`]+)`\\s+([A-Z]+(?:\\(\\d+(?:,\\d+)?\\))?)\\s");
    private static final Pattern NUM = Pattern.compile("\\((\\d+)");

    private static final class Struct {
        String tag = "";
        final List<String> comments = new ArrayList<String>();
        final List<String> names = new ArrayList<String>();
        final List<String> types = new ArrayList<String>();
        final List<String> lens = new ArrayList<String>();
        final List<String> pks = new ArrayList<String>();
        final List<String> nullables = new ArrayList<String>();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: RealCorpusCheck <全局字段清单.csv> <层间映射.csv>");
            System.exit(2);
            return;
        }
        MappingFile m = MappingFile.loadTableCsv(args[1]);
        List<LayerGen.SourceTable> src = readSources(args[0], m);
        int bad = 0;
        System.out.println("映射行=" + m.tables().size() + " 源表=" + src.size());
        if (m.tables().size() != src.size()) {
            bad++; System.out.println("x [ERROR] CORPUS | 映射行数与源表数不一致");
        }

        // 分类覆盖：十类对象维度、六个主题
        Map<String, Integer> dimCnt = new LinkedHashMap<String, Integer>();
        Map<String, Integer> themeCnt = new LinkedHashMap<String, Integer>();
        for (String d : Taxonomy.DIMS) { dimCnt.put(d, Integer.valueOf(0)); }
        for (String t : Taxonomy.THEMES) { themeCnt.put(t, Integer.valueOf(0)); }
        int noDim = 0;
        for (MappingFile.TableMap t : m.tables()) {
            String[] dims = (t.objDims == null ? "" : t.objDims).split("[;；,，]");
            int hit = 0;
            for (String d : dims) {
                if (dimCnt.containsKey(d.trim())) {
                    dimCnt.put(d.trim(), Integer.valueOf(dimCnt.get(d.trim()).intValue() + 1));
                    hit++;
                }
            }
            if (hit == 0) { noDim++; }
            String theme = Taxonomy.normalizeTheme(t.theme, t.domainCode, t.domain, t.domainSub);
            if (!themeCnt.containsKey(theme)) {
                bad++; System.out.println("x [ERROR] CORPUS | 主题归一化跑出了非官方主题：" + theme);
            } else {
                themeCnt.put(theme, Integer.valueOf(themeCnt.get(theme).intValue() + 1));
            }
        }
        System.out.println("十类维度命中：");
        for (String d : Taxonomy.DIMS) {
            System.out.println("  " + d + "(" + Taxonomy.dimCn(d) + ")=" + dimCnt.get(d));
            if (dimCnt.get(d).intValue() == 0) {
                bad++; System.out.println("x [ERROR] CORPUS | 对象维度 " + d + " 一张表都没命中");
            }
        }
        System.out.println("六主题分布：");
        for (String t : Taxonomy.THEMES) {
            System.out.println("  " + t + "=" + themeCnt.get(t));
            if (themeCnt.get(t).intValue() == 0) {
                bad++; System.out.println("x [ERROR] CORPUS | 主题 " + t + " 一行映射都没有");
            }
        }
        if (noDim > 0) {
            bad++; System.out.println("x [ERROR] CORPUS | 有 " + noDim + " 张表没判出任何对象维度");
        }

        // 三层组装 + DDL 行上限
        LayerGen.GenOptions o = new LayerGen.GenOptions();
        int tables = 0, ddlBad = 0;
        for (LayerGen.Layer layer : LayerGen.Layer.values()) {
            LayerGen.LayerOutput out = LayerGen.genLayer(layer, m, src, o);
            int creates = count(out.ddlSql, "CREATE TABLE");
            int inserts = count(out.transformSql, "INSERT INTO");
            System.out.println(layer + ": 表=" + out.tableCount + " CREATE=" + creates
                    + " INSERT=" + inserts + " 字段映射=" + out.fieldMapping.size()
                    + " 未绑定=" + out.unbound.size() + " 警告=" + out.warnings.size());
            if (!out.unbound.isEmpty()) {
                bad++;
                System.out.println("x [ERROR] CORPUS | " + layer + " 有 " + out.unbound.size()
                        + " 张源表没绑上：" + out.unbound.subList(0, Math.min(5, out.unbound.size())));
            }
            if (out.tableCount != creates) {
                bad++; System.out.println("x [ERROR] CORPUS | " + layer + " 表数与 DDL 数不符");
            }
            tables += creates;
            ddlBad += ddlRowFit(out.ddlSql, layer);
        }
        bad += ddlBad;
        System.out.println("DDL 行上限体检：" + tables + " 张表，不合格 " + ddlBad + " 张");
        if (bad > 0) {
            System.out.println("x [ERROR] CORPUS | 真实目录验收不通过（" + bad + " 处）");
            System.exit(1);
        }
        System.out.println("+ [OK] CORPUS | 全部源表绑定、十类维度齐备、六主题都有数据、DDL 全部可建");
    }

    /** 按字段清单构造源表：映射行顺序 ↔ 清单表顺序一一配对，tableEn 就是源表物理名。 */
    private static List<LayerGen.SourceTable> readSources(String path, MappingFile m) throws Exception {
        List<String> names = new ArrayList<String>();
        for (MappingFile.TableMap t : m.tables()) { names.add(t.tableEn); }
        Map<String, Struct> by = new LinkedHashMap<String, Struct>();
        List<Struct> ordered = new ArrayList<Struct>();
        String text = MappingFile.readTextAuto(new File(path));
        for (String line : text.split("\r\n|\n|\r")) {
            if (line.trim().isEmpty()) { continue; }
            List<String> r = MappingFile.parseCsvLine(line);
            if (r.size() < 11 || "系统".equals(r.get(0))) { continue; }
            String k = r.get(0) + "\u0001" + r.get(1) + "\u0001" + r.get(2) + "\u0001" + r.get(3);
            Struct s = by.get(k);
            if (s == null) {
                s = new Struct(); s.tag = k; by.put(k, s); ordered.add(s);
            }
            s.comments.add(r.get(6));
            s.names.add(r.get(5));
            s.types.add(r.get(7));
            s.lens.add(r.get(8));
            s.pks.add(r.get(9));
            s.nullables.add(r.get(10));
        }
        List<LayerGen.SourceTable> out = new ArrayList<LayerGen.SourceTable>();
        for (int i = 0; i < ordered.size(); i++) {
            Struct s = ordered.get(i);
            String name = i < names.size() ? names.get(i) : "unnamed_" + i;
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            for (int j = 0; j < s.comments.size(); j++) {
                String type = s.types.get(j);
                String len = s.lens.get(j);
                Infer.Column c = new Infer.Column();
                c.field = s.names.get(j);
                c.comment = s.comments.get(j);
                if ("VARCHAR".equals(type) || "CHAR".equals(type)) {
                    c.type = type + "(" + (len.isEmpty() ? "255" : len) + ")";
                } else if ("DECIMAL".equals(type) && !len.isEmpty()) {
                    c.type = type + "(" + len + ")";
                } else {
                    c.type = type;
                }
                if (!len.isEmpty()) {
                    try { c.maxLen = Long.parseLong(len.split(",")[0]); } catch (Exception ignore) { }
                }
                c.primaryKey = "是".equals(s.pks.get(j));
                c.nullable = Boolean.valueOf(!"否".equals(s.nullables.get(j)));
                cols.add(c);
            }
            out.add(new LayerGen.SourceTable(name, s.tag.split("\u0001", -1)[2], cols));
        }
        return out;
    }

    private static int ddlRowFit(String ddl, LayerGen.Layer layer) {
        int bad = 0;
        String cur = null;
        List<Infer.Column> cols = new ArrayList<Infer.Column>();
        for (String line : ddl.split("\n")) {
            if (line.startsWith("CREATE TABLE `")) {
                int q = line.indexOf('`', "CREATE TABLE `".length());
                cur = line.substring("CREATE TABLE `".length(), q);
                cols = new ArrayList<Infer.Column>();
                continue;
            }
            if (cur == null) { continue; }
            Matcher mc = COL.matcher(line);
            if (mc.find()) {
                Infer.Column c = new Infer.Column();
                c.field = mc.group(1);
                c.type = mc.group(2);
                Matcher mn = NUM.matcher(c.type);
                if (mn.find()) {
                    try { c.maxLen = Long.parseLong(mn.group(1)); } catch (Exception ignore) { }
                }
                c.nullable = Boolean.TRUE;
                cols.add(c);
                continue;
            }
            if (line.startsWith(") ENGINE=")) {
                if (!RowFit.rowPlan(cols, 16384).ok) {
                    bad++;
                    System.out.println("x [ERROR] CORPUS | " + layer + "." + cur + " 过不了行上限");
                }
                cur = null;
            }
        }
        return bad;
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
