package com.excel2mysql.core;

import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * core/wide_split.py 的 Java 版：行上限救不回来时，按「公共字段 + 每段列」拆成多个 Excel。
 *
 * <p>为什么拆：129 节那套（{@link RowFit#fit}）只会降长度、退 TEXT，救不回来的三类——
 * 定长列把 InnoDB 半页堆满、需要走行外的列数超过半页容量、列数超 1017——除了删列只能拆表。
 * 以前 {@code Refuse.split} 只给"建议拆成 N 段、每段多少列"，人还得自己数着列去切，
 * 切完还容易把对照关系切丢。
 *
 * <p>拆的规矩是<b>公共字段每段都带</b>：从表头第一列起连续的那几列键列（编号/编码/名称/
 * 日期这一类，外加全部主键列）在每段里重复一份，于是每段都能自己认回同一行，
 * 按公共字段 join 回原表就行。其余列按列序贪心装箱，每段（公共字段 + 本段列）都必须自己过线。
 *
 * <p>产物是 Excel：现场要的是"这几份重新拖进来就能建成几张表"。表头写原表头中文，
 * 重新导入时字段名还是原来那套拼音。数值按数值写、整数按整数写（走 double 会把 5 落成
 * "5.0"，回读时整列 dtype 变化会让两条线推出来的类型分叉）；日期时间按 ISO 文本写，
 * 与 {@link MetaExport} 一个口径。
 */
public final class SplitWide {

    /** 键列词：前几个是主键那一套，后面是宽表里常见的前置维度列。 */
    private static final String[] KEY_HINTS = {
        "编号", "序号", "代码", "编码", "标识", "卡号", "单号", "主键", "唯一",
        "名称", "日期", "时间", "年度", "年份", "月份", "单位", "类别",
    };

    /** 日期时间型列天然是维度（哪一天的量），表头没写"日期"也算公共字段。 */
    private static final String[] DATE_TYPES = {"DATE", "DATETIME", "TIMESTAMP", "TIME", "YEAR"};

    /** 公共字段最多跟着重复几列：再宽就不叫"认行"了。 */
    public static final int COMMON_MAX = 8;

    /** 一段拆出来的表：要写哪些列、写成哪个文件。 */
    public static final class Part {
        /** 这一段实际写出去的列序号（公共字段在前）。 */
        public int[] idx = new int[0];
        /** 本段独有的列序号。 */
        public int[] own = new int[0];
        public String file = "";
        public int columns;
        public long local;
        public int rows;
    }

    /** 拆分方案。ok 为假时 reason 说清为什么没拆。 */
    public static final class Plan {
        public boolean ok;
        public int[] common = new int[0];
        public String note = "";
        public String reason = "";
        public int rows;
        public final List<Part> parts = new ArrayList<Part>();
    }

    private SplitWide() {
    }

    private static String baseType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase();
        int p = t.indexOf('(');
        return p < 0 ? t : t.substring(0, p).trim();
    }

    /** 这一列像不像"用来认行的键列"：主键、词命中、或日期时间型。 */
    public static boolean isKeyColumn(Infer.Column c) {
        if (c.primaryKey) {
            return true;
        }
        String text = nz(c.comment) + " " + nz(c.field);
        for (String k : KEY_HINTS) {
            if (text.contains(k)) {
                return true;
            }
        }
        String base = baseType(c.type);
        for (String d : DATE_TYPES) {
            if (d.equals(base)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 认出表头最前面的公共字段。返回列序号；note 由第二参带回一句说明。
     *
     * <p>第 0 列始终当锚点并计入公共字段（它命不命中键列词都一样——总得有个能对上行的列）；
     * 从第 1 列起继续取连续键列，遇到第一个不像键列的就停（"前面的公共字段"就是这个意思）。
     * 主键列一个都不许落在公共字段之外，落在后面的补进来。
     */
    public static int[] commonColumns(List<Infer.Column> cols, String[] note) {
        boolean firstIsKey = isKeyColumn(cols.get(0));
        List<Integer> picked = new ArrayList<Integer>();
        picked.add(Integer.valueOf(0));
        int contig = 1;
        for (int i = 1; i < cols.size() && contig < COMMON_MAX; i++) {
            if (!isKeyColumn(cols.get(i))) {
                break;
            }
            picked.add(Integer.valueOf(i));
            contig++;
        }
        for (int i = 0; i < cols.size(); i++) {
            Integer key = Integer.valueOf(i);
            if (cols.get(i).primaryKey && !picked.contains(key)) {
                picked.add(key);
            }
        }
        java.util.Collections.sort(picked);
        if (note != null && note.length > 0) {
            String text;
            if (firstIsKey) {
                text = "从第 1 列起连续 " + contig + " 列是键列";
            } else if (contig > 1) {
                text = "第 1 列当锚点，后接 " + (contig - 1) + " 列键列";
            } else {
                text = "没认出键列，用第一列当锚点";
            }
            if (picked.size() > contig) {
                text += "，另补 " + (picked.size() - contig) + " 列主键";
            }
            if (contig >= COMMON_MAX) {
                text += "（到 " + COMMON_MAX + " 列封顶）";
            }
            note[0] = text;
        }
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = picked.get(i).intValue();
        }
        return out;
    }

    private static List<Infer.Column> pick(List<Infer.Column> cols, int[] idx) {
        List<Infer.Column> out = new ArrayList<Infer.Column>(idx.length);
        for (int i : idx) {
            out.add(cols.get(i));
        }
        return out;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int[] ints(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i).intValue();
        }
        return out;
    }

    /** 按列序贪心装箱：每箱 = 公共字段 + 一段连续列，且必须自己过线。 */
    private static List<int[]> pack(List<Infer.Column> cols, int[] common, int pageSize) {
        Set<Integer> skip = new LinkedHashSet<Integer>();
        for (int i : common) {
            skip.add(Integer.valueOf(i));
        }
        List<int[]> segs = new ArrayList<int[]>();
        List<Integer> cur = new ArrayList<Integer>();
        for (int i = 0; i < cols.size(); i++) {
            if (skip.contains(Integer.valueOf(i))) {
                continue;
            }
            List<Integer> trial = new ArrayList<Integer>(cur);
            trial.add(Integer.valueOf(i));
            boolean ok = RowFit.rowPlan(pick(cols, concat(common, ints(trial))), pageSize).ok;
            if (ok || cur.isEmpty()) {
                cur = trial;
                continue;
            }
            segs.add(ints(cur));
            cur = new ArrayList<Integer>();
            cur.add(Integer.valueOf(i));
        }
        if (!cur.isEmpty()) {
            segs.add(ints(cur));
        }
        return segs;
    }

    /** 给出拆分方案：拆哪几段、每段带哪些列。 */
    public static Plan plan(List<Infer.Column> cols, int pageSize) {
        Plan p = new Plan();
        if (cols == null || cols.isEmpty()) {
            p.reason = "没有字段";
            return p;
        }
        String[] note = new String[]{""};
        p.common = commonColumns(cols, note);
        p.note = note[0];
        if (p.common.length >= cols.size()) {
            p.reason = "整张表都是键列，没有可拆出去的度量列";
            return p;
        }
        List<int[]> segs = pack(cols, p.common, pageSize);
        for (int[] s : segs) {
            RowFit.Plan one = RowFit.rowPlan(pick(cols, concat(p.common, s)), pageSize);
            if (!one.ok) {
                // 公共字段 + 单独一列都过不了线：拆多段也没用，得先减公共字段或删列
                p.reason = "公共字段 " + p.common.length + " 列 + 单列仍超过行上限（行内 "
                        + one.local + " 字节 > " + one.localLimit + "）";
                return p;
            }
        }
        if (segs.size() < 2) {
            p.reason = "按行上限装得下整张表，不需要拆";
            return p;
        }
        for (int[] s : segs) {
            Part part = new Part();
            part.own = s;
            part.idx = concat(p.common, s);
            part.columns = part.idx.length;
            part.local = RowFit.rowPlan(pick(cols, part.idx), pageSize).local;
            p.parts.add(part);
        }
        p.ok = true;
        return p;
    }

    /**
     * 按「降级前」的类型算行字节。
     *
     * <p>拆出来的 Excel 重新导入时，列会按数据重新推断成原来的类型；拿降级后的 TEXT 去装箱
     * 会装多——回读时又超线，又触发一次降级，一整段的列全变 TEXT。装箱按原类型来，
     * 拆出来的每一份才会原样建得出来。（与 Python 的 wide_split._shadow_columns 同口径）
     */
    private static List<Infer.Column> shadowColumns(TableBuilder.Result r) {
        List<Infer.Column> cols = r.table.columns;
        if (r.rowFit == null || r.rowFit.conversions.isEmpty()) {
            return cols;
        }
        java.util.Map<String, String> original = new java.util.LinkedHashMap<String, String>();
        for (String[] conv : r.rowFit.conversions) {
            // conv = {level, field, from, to}
            if (conv.length >= 3 && !original.containsKey(conv[1])) {
                original.put(conv[1], conv[2]);
            }
        }
        List<Infer.Column> shadow = new ArrayList<Infer.Column>(cols.size());
        for (Infer.Column c : cols) {
            String frm = original.get(c.field);
            if (frm == null) {
                shadow.add(c);
            } else {
                Infer.Column copy = c.copied();
                copy.type = frm;
                copy.varcharN = RowFit.varcharN(frm);
                shadow.add(copy);
            }
        }
        return shadow;
    }

    /** 写回 Excel 的表头：原样用中文表头，缺了才退回字段名；重名的加序号。 */
    private static String headerText(Infer.Column c, Set<String> used) {
        String text = nz(c.comment).trim();
        if (text.isEmpty()) {
            text = nz(c.field).trim();
        }
        if (text.isEmpty()) {
            text = "col";
        }
        String base = text;
        int n = 2;
        while (used.contains(text)) {
            text = base + "_" + n;
            n++;
        }
        used.add(text);
        return text;
    }

    private static String uniquePath(File dir, String filename) {
        File f = new File(dir, filename);
        if (!f.exists()) {
            return f.getPath();
        }
        int dot = filename.lastIndexOf('.');
        String stem = dot < 0 ? filename : filename.substring(0, dot);
        String ext = dot < 0 ? "" : filename.substring(dot);
        int n = 2;
        while (true) {
            File next = new File(dir, stem + "_" + n + ext);
            if (!next.exists()) {
                return next.getPath();
            }
            n++;
        }
    }

    /**
     * 把一张过宽的表拆成若干 Excel 写到 outDir。
     *
     * <p>失败只给 reason，不抛异常——拆分是"顺手救一把"，它失败不该把整份导入流程带崩，
     * 原来的拦下结论照旧。表头与字段数对不上时直接不拆：那说明这份网格与列定义不是一一对应，
     * 硬拆会把数据写到错列上，比不拆严重得多。
     */
    public static Plan write(TableBuilder.Result r, String outDir, String stem, int pageSize) {
        List<Infer.Column> cols = r.table.columns;
        List<Infer.Column> shadow = shadowColumns(r);
        Plan p = plan(shadow, pageSize);
        if (!p.ok) {
            return p;
        }
        if (r.headers == null || r.headers.size() != cols.size()) {
            p.ok = false;
            p.reason = "表头 " + (r.headers == null ? 0 : r.headers.size())
                    + " 列与字段 " + cols.size() + " 列对不上，不敢按列序号拆";
            p.parts.clear();
            return p;
        }
        File dir = new File(outDir == null ? new File(r.table.filepath).getParent() : outDir);
        if (stem == null || stem.isEmpty()) {
            stem = stripExt(new File(r.table.filepath).getName());
        }
        int total = r.rows == null ? 0 : r.rows.size();
        for (int n = 0; n < p.parts.size(); n++) {
            Part part = p.parts.get(n);
            Set<String> used = new LinkedHashSet<String>();
            try {
                XSSFWorkbook wb = new XSSFWorkbook();
                try {
                    XSSFSheet sheet = wb.createSheet("Sheet1");
                    XSSFRow head = sheet.createRow(0);
                    for (int c = 0; c < part.idx.length; c++) {
                        head.createCell(c).setCellValue(headerText(cols.get(part.idx[c]), used));
                    }
                    for (int i = 0; i < total; i++) {
                        List<Object> row = r.rows.get(i);
                        XSSFRow out = sheet.createRow(i + 1);
                        for (int c = 0; c < part.idx.length; c++) {
                            int k = part.idx[c];
                            Object v = k < row.size() ? row.get(k) : null;
                            put(out, c, v);
                        }
                    }
                    String path = uniquePath(dir, stem + "_part" + (n + 1) + ".xlsx");
                    FileOutputStream fos = new FileOutputStream(path);
                    try {
                        wb.write(fos);
                    } finally {
                        fos.close();
                    }
                    part.file = path;
                    part.rows = total;
                } finally {
                    wb.close();
                }
            } catch (Exception e) {
                p.ok = false;
                p.reason = "写第 " + (n + 1) + " 个拆分文件失败：" + e.getMessage();
                return p;
            }
        }
        p.rows = total;
        return p;
    }

    private static void put(XSSFRow row, int col, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof Double && Double.isNaN(((Double) v).doubleValue())) {
            return;
        }
        XSSFCell cell = row.createCell(col);
        if (v instanceof BigInteger || v instanceof Long || v instanceof Integer) {
            // 整数按整数写：走 setCellValue(double) 会把 5 落成 "5.0"，回读时整列 dtype
            // 从整型变浮点，两条线推出来的类型会分叉（与 MetaExport.setExactNumber 同理）
            cell.setCellValue(0.0);
            cell.getCTCell().setV(String.valueOf(v));
        } else if (v instanceof Number) {
            cell.setCellValue(((Number) v).doubleValue());
        } else if (v instanceof Boolean) {
            cell.setCellValue(((Boolean) v).booleanValue());
        } else if (v instanceof java.time.LocalDateTime) {
            cell.setCellValue(MetaExport.isoText((java.time.LocalDateTime) v));
        } else {
            cell.setCellValue(String.valueOf(v));
        }
    }

    /** 把拆分结果写成现场照抄的几行日志（与 Python 的 wide_split.notice_lines 同一段文案）。 */
    public static List<String> noticeLines(String tableName, List<Infer.Column> cols, Plan p) {
        List<String> lines = new ArrayList<String>();
        if (p == null) {
            return lines;
        }
        if (!p.ok) {
            if (p.reason != null && !p.reason.isEmpty()) {
                lines.add("[自动拆分] 表 `" + tableName + "` 拆不动：" + p.reason);
            }
            return lines;
        }
        StringBuilder heads = new StringBuilder();
        for (int i : p.common) {
            String text = nz(cols.get(i).comment).trim();
            if (text.isEmpty()) {
                text = nz(cols.get(i).field).trim();
            }
            if (heads.length() > 0) {
                heads.append("、");
            }
            heads.append(text);
        }
        lines.add("[自动拆分] 表 `" + tableName + "` 行内装不下，已按公共字段 `" + heads
                + "` 拆成 " + p.parts.size() + " 个 Excel（每段都带这 " + p.common.length
                + " 列，数据 " + p.rows + " 行一起带）：");
        for (int i = 0; i < p.parts.size(); i++) {
            Part part = p.parts.get(i);
            lines.add("    第 " + (i + 1) + " 段：本段 " + part.own.length + " 列 + 公共 "
                    + p.common.length + " 列 = " + part.columns + " 列，行内约 " + part.local
                    + " 字节 → " + new File(part.file).getName());
        }
        lines.add("    把这几份拖回来就是 " + p.parts.size() + " 张独立的表，按公共字段能 join 回原表；"
                + "文件放在原文件（压缩包里出来的放压缩包）旁边，同名不覆盖（后一份加 _2）");
        return lines;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
