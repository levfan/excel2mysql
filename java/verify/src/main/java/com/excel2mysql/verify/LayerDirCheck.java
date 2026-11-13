package com.excel2mysql.verify;

import com.excel2mysql.core.DomainGuess;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LayerGen;
import com.excel2mysql.core.MappingFile;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.Taxonomy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 拿一个装满 Excel 的目录跑一遍第四页（分层建模）的完整链路：解析 → 自动识别领域 →
 * 三层生成，把「判出多少、分布如何、哪些待确认、dom 为什么是这个张数」摊开来看。
 *
 * <p>与 {@link RealCorpusCheck} 的分工：那个吃「全局字段清单 + 现成映射 CSV」，验的是映射与
 * 生成器；这个吃现场目录，验的是 <b>自动识别</b>这一段的判得准不准——目录里每张表都有一条
 * 判定结果，待确认与子领域落空的行会逐条列出来，调词表/先验时用它看进退。
 *
 * <pre>
 * java -cp "app/target/classes;verify/target/classes" com.excel2mysql.verify.LayerDirCheck \
 *      源目录 [输出目录]
 * </pre>
 *
 * 输出目录里落：层间映射 CSV（可回第四页「载入映射文件」）、per-table 判定 TSV、
 * 以及 obj/dom/ads 三层的 DDL、转换 SQL、字段映射。不连库、不写源目录。
 */
public final class LayerDirCheck {

    private LayerDirCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: LayerDirCheck <Excel目录> [输出目录]");
            System.exit(2);
            return;
        }
        File root = new File(args[0]);
        if (!root.isDirectory()) {
            System.out.println("x [ERROR] DIR-CHECK | 不是目录：" + root);
            System.exit(2);
            return;
        }
        File out = args.length > 1 ? new File(args[1])
                : new File(System.getProperty("java.io.tmpdir"), "e2m-layer-dircheck");
        if (!out.isDirectory() && !out.mkdirs()) {
            System.out.println("x [ERROR] DIR-CHECK | 输出目录建不出来：" + out);
            System.exit(2);
            return;
        }
        // 建模阶段遇宽表会按公共字段拆 Excel：落点指到输出目录，绝不写现场源目录
        ImportParse.splitOutDir = new File(out, "split").getPath();
        new File(ImportParse.splitOutDir).mkdirs();

        List<String> files = new ArrayList<String>();
        collect(root, files);
        System.out.println("- [INFO] DIR-CHECK | 目录 " + root.getPath()
                + " 下 Excel " + files.size() + " 份，输出到 " + out.getPath());
        if (files.isEmpty()) {
            System.out.println("x [ERROR] DIR-CHECK | 目录里没有 .xlsx/.xls");
            System.exit(2);
            return;
        }

        Names nm = new Names(PinyinLoader.get());
        TableBuilder builder = new TableBuilder(new Infer(nm), nm);
        ImportParse.Outcome oc = ImportParse.parse(files, new ArrayList<String>(),
                new File(out, "zip").getPath(), 1, builder, ImportParse.LOG_ONLY);
        System.out.println("- [INFO] DIR-CHECK | 解析出表 " + oc.tables.size()
                + " 张（失败 " + oc.failed + "，重复 " + oc.dups + "）");
        if (oc.tables.isEmpty()) {
            System.out.println("x [ERROR] DIR-CHECK | 一张表都没解析出来");
            System.exit(1);
            return;
        }

        List<DomainGuess.Input> inputs = new ArrayList<DomainGuess.Input>();
        List<LayerGen.SourceTable> sources = new ArrayList<LayerGen.SourceTable>();
        for (TableBuilder.Result r : oc.tables) {
            DomainGuess.Input in = new DomainGuess.Input();
            String path = r.table.filepath == null ? "" : r.table.filepath;
            in.fileName = new File(path).getName();
            in.system = systemOf(path, root);
            fillInput(in, r.table.tableComment, r.table.tableName, r.table.columns);
            inputs.add(in);
            sources.add(new LayerGen.SourceTable(r.table.tableName, r.table.tableComment,
                    r.table.columns));
        }

        List<DomainGuess.Verdict> vs = DomainGuess.guess(inputs, nm);
        List<MappingFile.TableMap> maps = new ArrayList<MappingFile.TableMap>();
        for (DomainGuess.Verdict v : vs) {
            maps.add(v.map);
        }
        MappingFile m = MappingFile.ofTables(maps);
        report(vs, out);
        writeTableCsv(maps, new File(out, "层间映射-自动识别.csv"));
        writeVerdictTsv(vs, new File(out, "判定明细.tsv"));

        LayerGen.GenOptions o = new LayerGen.GenOptions();
        LayerGen.LayerOutput obj = LayerGen.genLayer(LayerGen.Layer.OBJ, m, sources, o);
        LayerGen.LayerOutput dom = LayerGen.genLayer(LayerGen.Layer.DOM, m, sources, o);
        LayerGen.LayerOutput ads = LayerGen.genLayer(LayerGen.Layer.ADS, m, sources, o);
        writeLayer(out, "obj", obj);
        writeLayer(out, "dom", dom);
        writeLayer(out, "ads", ads);
        System.out.println("- [INFO] DIR-CHECK | obj 表 " + obj.tableCount + " | dom 表 "
                + dom.tableCount + " | ads 表 " + ads.tableCount);
        for (String w : obj.warnings) { System.out.println("  · obj: " + w); }
        for (String w : dom.warnings) { System.out.println("  · dom: " + w); }
        for (String w : ads.warnings) { System.out.println("  · ads: " + w); }
        System.out.println("+ [OK] DIR-CHECK | 产物已落 " + out.getPath());
    }

    private static void collect(File dir, List<String> out) {
        File[] list = dir.listFiles();
        if (list == null) { return; }
        java.util.Arrays.sort(list);
        for (File f : list) {
            if (f.isDirectory()) {
                collect(f, out);
                continue;
            }
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith("~$")) { continue; }
            if (n.endsWith(".xlsx") || n.endsWith(".xls")) { out.add(f.getPath()); }
        }
    }

    /** 与第四页同一条口径：逐级向上找一个「已知系统」目录名，找不到就用直接父目录名。 */
    private static String systemOf(String path, File root) {
        File parent = new File(path).getParentFile();
        for (File p = parent; p != null && !p.equals(root.getParentFile()); p = p.getParentFile()) {
            String name = p.getName();
            if (!name.isEmpty() && Taxonomy.prior(name, "") != null) { return name; }
        }
        return parent == null ? "" : parent.getName();
    }

    private static void fillInput(DomainGuess.Input in, String tableCn, String tableEn,
                                  List<Infer.Column> cols) {
        in.tableCn = tableCn == null ? "" : tableCn;
        in.tableEn = tableEn == null ? "" : tableEn;
        in.fieldCount = cols == null ? 0 : cols.size();
        if (cols == null) { return; }
        for (Infer.Column c : cols) {
            in.fieldComments.add(c.comment == null ? "" : c.comment);
            in.fieldNames.add(c.field == null ? "" : c.field);
            if (c.primaryKey && in.pkField.isEmpty()) { in.pkField = c.field; }
        }
    }

    /** 判定质量摊开：领域/子领域分布、待确认逐条、维度命中率、目标表撞名情况。 */
    private static void report(List<DomainGuess.Verdict> vs, File out) throws IOException {
        int guessed = 0;
        int pending = 0;
        int noDim = 0;
        Map<String, Integer> byDomain = new LinkedHashMap<String, Integer>();
        Map<String, Integer> bySub = new LinkedHashMap<String, Integer>();
        Map<String, Integer> byTarget = new LinkedHashMap<String, Integer>();
        List<String> pendingRows = new ArrayList<String>();
        List<String> noDimRows = new ArrayList<String>();
        for (DomainGuess.Verdict v : vs) {
            if (v.guessed) {
                guessed++;
                bump(byDomain, v.domain);
                bump(bySub, v.domain + "/" + v.map.domainSub);
                bump(byTarget, v.map.domainTarget);
                String dims = v.map.objDims == null ? "" : v.map.objDims;
                if (dims.isEmpty()) {
                    noDim++;
                    noDimRows.add(v.map.tableEn + "（" + v.map.tableCn + "）");
                }
            } else {
                pending++;
                pendingRows.add(v.map.tableEn + "（" + v.map.tableCn + "）：" + v.basis);
            }
        }
        StringBuilder sb = new StringBuilder();
        line(sb, "共 " + vs.size() + " 张：判出 " + guessed + "，待确认 " + pending
                + "；判出里未见十类维度 " + noDim + " 张");
        line(sb, "领域分布：" + byDomain);
        line(sb, "子领域分布：" + bySub);
        int dupTargets = 0;
        for (Map.Entry<String, Integer> e : byTarget.entrySet()) {
            if (e.getValue().intValue() > 1) { dupTargets++; }
        }
        line(sb, "领域目标表 " + byTarget.size() + " 张（撞名后加序号的 " + dupTargets + " 组）");
        if (!pendingRows.isEmpty()) {
            line(sb, "");
            line(sb, "待确认（" + pendingRows.size() + "）：");
            for (String s : pendingRows) { line(sb, "  - " + s); }
        }
        if (!noDimRows.isEmpty()) {
            line(sb, "");
            line(sb, "未见十类对象维度（" + noDimRows.size() + "，obj 不产这些表）：");
            for (String s : noDimRows) { line(sb, "  - " + s); }
        }
        String text = sb.toString();
        System.out.print(text);
        write(new File(out, "判定报告.txt"), text);
    }

    private static void writeVerdictTsv(List<DomainGuess.Verdict> vs, File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("表英文名\t表中文名\t系统\t领域\t领域码\t子领域\t领域目标表\t涉及维度\t主题\t状态\t依据\t告警\n");
        for (DomainGuess.Verdict v : vs) {
            MappingFile.TableMap t = v.map;
            sb.append(nz(t.tableEn)).append('\t').append(nz(t.tableCn)).append('\t')
              .append(nz(t.system)).append('\t').append(nz(t.domain)).append('\t')
              .append(nz(t.domainCode)).append('\t').append(nz(t.domainSub)).append('\t')
              .append(nz(t.domainTarget)).append('\t').append(nz(t.objDims)).append('\t')
              .append(nz(t.theme)).append('\t').append(nz(t.status)).append('\t')
              .append(oneLine(t.basis)).append('\t').append(oneLine(v.warning)).append('\n');
        }
        write(f, sb.toString());
    }

    /** 映射 CSV 与第四页「导出映射模板」同列，可直接「载入映射文件」复核。 */
    private static void writeTableCsv(List<MappingFile.TableMap> rows, File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,"
                + "领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\n");
        for (MappingFile.TableMap t : rows) {
            sb.append(cell(t.system)).append(',').append(cell(t.subDomain)).append(',')
              .append(cell(t.tableCn)).append(',').append(cell(t.tableEn)).append(',')
              .append(cell(t.fieldCount)).append(',').append(cell(t.pkField)).append(',')
              .append(cell(t.domain)).append(',').append(cell(t.domainCode)).append(',')
              .append(cell(t.domainSub)).append(',').append(cell(t.domainTarget)).append(',')
              .append(cell(t.objDims)).append(',').append(cell(t.theme)).append(',')
              .append(cell(t.basis)).append(',').append(cell(t.status)).append(',')
              .append(cell(t.note)).append('\n');
        }
        write(f, sb.toString());
    }

    private static void writeLayer(File out, String tag, LayerGen.LayerOutput lo) throws IOException {
        write(new File(out, tag + "_ddl.sql"), lo.ddlSql);
        write(new File(out, tag + "_transform.sql"), lo.transformSql);
        MappingFile.writeFieldCsv(new File(out, tag + "_field_mapping.csv").getPath(),
                lo.fieldMapping);
    }

    private static String cell(String s) {
        String v = s == null ? "" : s;
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0
                ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    private static void bump(Map<String, Integer> map, String key) {
        String k = key == null || key.isEmpty() ? "（空）" : key;
        Integer n = map.get(k);
        map.put(k, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }

    private static void write(File f, String text) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
