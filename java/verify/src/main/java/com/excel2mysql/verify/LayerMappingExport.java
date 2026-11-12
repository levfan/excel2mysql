package com.excel2mysql.verify;

import com.excel2mysql.core.FieldClass;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.MappingFile;
import com.excel2mysql.core.Taxonomy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「全局字段清单.csv + 层间映射初稿.csv」重算成 tab4 现行口径的映射 CSV。
 *
 * <p>专供 4.收集数据 那 312 张表：老映射初稿由构建/gen_layer_mapping.py 产出，
 * 主题写的是条款 188 三框架名（还带分号多值串），对象维度只认 6 类关键词，
 * 直接载入 tab4 会让六个主题塌成一张、十类维度缺两类。这里不另抄一份词表，
 * 维度与主题全部走 Taxonomy（程序里唯一的口径来源），保证导出的 CSV 与生成器完全同源。
 *
 * <p>用法（java/ 目录下）：
 * <pre>
 * java -cp "app/target/classes;verify/target/classes" com.excel2mysql.verify.LayerMappingExport \
 *      全局字段清单.csv 层间映射初稿.csv 层间映射_六主题.csv
 * </pre>
 */
public final class LayerMappingExport {

    private LayerMappingExport() { }

    private static final class Struct {
        String tag = "";
        final List<String> comments = new ArrayList<String>();
        final List<String> names = new ArrayList<String>();
        String pk = "";
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: LayerMappingExport <全局字段清单.csv> <层间映射初稿.csv> <输出.csv>");
            System.exit(2);
            return;
        }
        Map<String, List<Struct>> structs = readStruct(args[0]);
        Map<String, Integer> cursor = new LinkedHashMap<String, Integer>();
        MappingFile m = MappingFile.loadTableCsv(args[1]);

        Map<String, Integer> dimCnt = new LinkedHashMap<String, Integer>();
        Map<String, Integer> themeCnt = new LinkedHashMap<String, Integer>();
        for (String d : Taxonomy.DIMS) { dimCnt.put(d, Integer.valueOf(0)); }
        for (String t : Taxonomy.THEMES) { themeCnt.put(t, Integer.valueOf(0)); }
        Set<String> usedNames = new LinkedHashSet<String>();
        int noDim = 0, renamed = 0, noStruct = 0, noEn = 0;
        List<String> noDimSample = new ArrayList<String>();
        List<String> noEnSample = new ArrayList<String>();

        StringBuilder csv = new StringBuilder();
        csv.append("系统,子领域,表中文名,表英文名,字段数,主键字段,目标领域,领域码,领域子类,")
                .append("领域目标表,涉及对象维度,主题归属,分类依据,映射状态,备注\r\n");
        int lineNo = 1;   // 1 = 表头，数据行从 2 起，兜底名带行号便于回查
        for (MappingFile.TableMap t : m.tables()) {
            lineNo++;
            Struct st = pick(structs, cursor, t.tableEn);
            if (st == null) { noStruct++; }

            // 十类对象维度：只拿业务字段判，系统审计列与未归类列先剔掉
            List<String> comments = new ArrayList<String>();
            List<String> names = new ArrayList<String>();
            if (st != null) {
                for (int i = 0; i < st.comments.size(); i++) {
                    Infer.Column probe = new Infer.Column();
                    probe.field = i < st.names.size() ? st.names.get(i) : "";
                    probe.comment = st.comments.get(i);
                    probe.type = "VARCHAR(255)";
                    if (!FieldClass.of(probe).business) { continue; }
                    comments.add(probe.comment);
                    names.add(probe.field);
                }
            }
            List<String> dims = Taxonomy.dims(comments, names, Taxonomy.DIMS.length);
            if (dims.isEmpty()) {
                noDim++;
                if (noDimSample.size() < 8) { noDimSample.add(nz(t.tableEn)); }
            }
            for (String d : dims) {
                dimCnt.put(d, Integer.valueOf(dimCnt.get(d).intValue() + 1));
            }

            String theme = Taxonomy.normalizeTheme(t.theme, t.domainCode, t.domain, t.domainSub);
            themeCnt.put(theme, Integer.valueOf(themeCnt.get(theme).intValue() + 1));

            // 清单里有 4 张表都叫「规范化数据资源表」，byName 会互相覆盖；按出现顺序加 _2/_3 后缀。
            // 还有一张真实数据 Excel 文件名没带括号英文名，清单里该列为空，按行号给可核的占位名。
            String rawEn = nz(t.tableEn);
            String note = nz(t.note);
            if (rawEn.isEmpty()) {
                noEn++;
                rawEn = "ods_no_en_" + lineNo;
                note = (note.isEmpty() ? "" : note + "；")
                        + "源清单缺英文表名，已兜底为 " + rawEn + "，请改成实际 ods 物理表名";
                if (noEnSample.size() < 8) { noEnSample.add(nz(t.tableCn)); }
            }
            String en = unique(rawEn, usedNames);
            if (!en.equals(rawEn)) { renamed++; }

            String basis = "Taxonomy 十类维度词表 + 六主题归口";
            if (st == null) { basis += "（缺字段清单，维度按空处理）"; }
            csv.append(row(nz(t.system), nz(t.subDomain), nz(t.tableCn), en,
                    nz(t.fieldCount), nz(t.pkField), nz(t.domain), nz(t.domainCode),
                    nz(t.domainSub), nz(t.domainTarget), join(dims, ";"), theme,
                    basis, "待核", note));
        }
        File out = new File(args[2]);
        Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
        try {
            w.write('\ufeff');
            w.write(csv.toString());
        } finally { w.close(); }

        int structTables = 0;
        for (List<Struct> g : structs.values()) { structTables += g.size(); }
        System.out.println("表数=" + m.tables().size() + " 字段清单表数=" + structTables
                + " 重名修正=" + renamed + " 缺英文表名兜底=" + noEn + " 缺字段清单=" + noStruct);
        if (!noEnSample.isEmpty()) {
            System.out.println("缺英文表名（请补 ods 物理表名）: " + join(noEnSample, "、"));
        }
        System.out.println("十类对象维度命中：");
        for (String d : Taxonomy.DIMS) {
            System.out.println("  " + d + "(" + Taxonomy.dimCn(d) + ") = " + dimCnt.get(d));
        }
        System.out.println("无维度表=" + noDim + " 示例=" + join(noDimSample, "、"));
        System.out.println("六主题分布：");
        for (String t : Taxonomy.THEMES) {
            System.out.println("  " + t + " = " + themeCnt.get(t));
        }
        System.out.println("输出=" + out.getAbsolutePath());
    }

    /** 字段清单按「原始表英文名」分组并保留出现顺序；重名的 4 张表按行序一一对应。 */
    private static Map<String, List<Struct>> readStruct(String path) throws Exception {
        String text = MappingFile.readTextAuto(new File(path));
        Map<String, List<Struct>> byEn = new LinkedHashMap<String, List<Struct>>();
        String[] lines = text.split("\r\n|\n|\r");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) { continue; }
            List<String> r = MappingFile.parseCsvLine(lines[i]);
            if (r.size() < 11) { continue; }
            String en = r.get(3);
            List<Struct> group = byEn.get(en);
            if (group == null) { group = new ArrayList<Struct>(); byEn.put(en, group); }
            // 一行一个字段：同一张表的所有行归到同一个 Struct，靠 系统/子领域/中文名 区分重名表
            String k = r.get(0) + "\u0001" + r.get(1) + "\u0001" + r.get(2);
            Struct s = null;
            for (Struct cand : group) {
                if (k.equals(cand.tag)) { s = cand; break; }
            }
            if (s == null) {
                s = new Struct();
                s.tag = k;
                group.add(s);
            }
            s.comments.add(r.get(6));
            s.names.add(r.get(5));
            if ("是".equals(r.get(9)) && s.pk.isEmpty()) { s.pk = r.get(5); }
        }
        return byEn;
    }

    private static Struct pick(Map<String, List<Struct>> by, Map<String, Integer> cursor, String en) {
        List<Struct> group = by.get(key(en));
        if (group == null || group.isEmpty()) { return null; }
        Integer n = cursor.get(key(en));
        int i = n == null ? 0 : n.intValue();
        cursor.put(key(en), Integer.valueOf(i + 1));
        return group.get(Math.min(i, group.size() - 1));
    }

    private static String key(String en) { return en == null ? "" : en.trim(); }

    private static String unique(String base, Set<String> used) {
        String t = base.isEmpty() ? "unnamed" : base;
        int n = 2;
        while (!used.add(t)) {
            t = base + "_" + n;
            n++;
        }
        return t;
    }

    private static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) { sb.append(','); }
            sb.append(MappingFile.csvCell(cells[i]));
        }
        return sb.append("\r\n").toString();
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) { sb.append(sep); }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
