package com.excel2mysql.core;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * core/merge_check.py：合并单元格探测。
 *
 * 含合并区的表映射成关系表必然错位（只有左上角有值，其余格读进来是空），
 * 所以默认拒绝导入而不是导出一份错数据。
 *
 * 唯一例外是首表顶部的多行合并表头：整块合并区都落在表头里时展开成
 * 「父-子」单行表头（技术公司 + 小计 → 技术公司-小计）；数据区有合并区照旧拒绝。
 * xlsx 走流式扫 sheet XML 里的 &lt;mergeCell&gt;，不整表加载；xls 走 POI 的 HSSF。
 */
public final class MergeCheck {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CHUNK = 1 << 20;
    private static final int CARRY = 256;
    private static final int MAX_REPORT = 6;

    private static final Pattern REL_TAG = Pattern.compile("<Relationship\\b[^>]*>");
    private static final Pattern REL_ID = Pattern.compile("Id=\"([^\"]+)\"");
    private static final Pattern REL_TARGET = Pattern.compile("Target=\"([^\"]+)\"");
    // 属性顺序不固定（openpyxl 把 Id 写在 Target 之后，Excel 反过来），只能逐个属性取
    private static final Pattern SHEET_TAG = Pattern.compile("<sheet\\b[^>]*>");
    private static final Pattern SHEET_NAME = Pattern.compile("name=\"([^\"]+)\"");
    private static final Pattern SHEET_RID = Pattern.compile("r:id=\"([^\"]+)\"");
    // 必须连标签一起匹配：sheet XML 里 <dimension ref="..."> 等也带 ref= 属性
    private static final Pattern MERGE_CELL = Pattern.compile("<mergeCell\\s+ref=\"([^\"]+)\"");
    private static final Pattern RANGE = Pattern.compile("^([A-Z]+)(\\d+):([A-Z]+)(\\d+)$");

    /** 文件含合并单元格，拒绝导入。 */
    public static final class MergedCellsException extends RuntimeException {
        public final Map<String, List<String>> merged;

        public MergedCellsException(String filepath, Map<String, List<String>> merged) {
            super(message(merged));
            this.merged = merged;
            this.filepath = filepath;
        }

        public final String filepath;

        private static String message(Map<String, List<String>> merged) {
            List<String> parts = new ArrayList<String>();
            for (Map.Entry<String, List<String>> e : merged.entrySet()) {
                parts.add("「" + e.getKey() + "」" + e.getValue().size() + " 处（"
                        + fmt(e.getValue()) + "）");
            }
            return "检测到合并单元格：" + join(parts, "；");
        }
    }

    private MergeCheck() {
    }

    private static String fmt(List<String> ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.size() && i < MAX_REPORT; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(ranges.get(i));
        }
        if (ranges.size() > MAX_REPORT) {
            sb.append(" 等");
        }
        return sb.toString();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    public static Map<String, List<String>> detect(String filepath) {
        String lower = filepath.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot);
        try {
            if (".xlsx".equals(ext) || ".xlsm".equals(ext)) {
                return scanXlsx(filepath);
            }
            if (".xls".equals(ext)) {
                return scanXls(filepath);
            }
        } catch (Exception e) {
            // 文件本身有问题时不在此处下结论，交给后面的读取流程报权威错误
            return new LinkedHashMap<String, List<String>>();
        }
        return new LinkedHashMap<String, List<String>>();
    }

    public static void ensureNoMergedCells(String filepath) {
        Map<String, List<String>> merged = detect(filepath);
        if (!merged.isEmpty()) {
            throw new MergedCellsException(filepath, merged);
        }
    }

    /** 首表顶部合并表头的展开计划：depth 是表头行数，merges 是 0 基闭区间。 */
    public static final class HeaderPlan {
        public final int depth;
        public final List<int[]> merges;

        HeaderPlan(int depth, List<int[]> merges) {
            this.depth = depth;
            this.merges = merges;
        }
    }

    /** 'B2:C4' -> 0 基闭区间 {r1, c1, r2, c2}；格式不认识返回 null。 */
    public static int[] parseRange(String ref) {
        Matcher m = RANGE.matcher(ref == null ? "" : ref.trim().toUpperCase());
        if (!m.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(2)) - 1, colWidth(m.group(1)) - 1,
                Integer.parseInt(m.group(4)) - 1, colWidth(m.group(3)) - 1};
    }

    /**
     * 首表合并区若全在顶部表头块里，返回展开计划；否则返回 null（照旧拒绝）。
     *
     * 表头深度由顶到第 1 行的合并区决定（A1:A2 → 2 行，J1:M1 → 横向分组）；
     * 表头块内部允许嵌套合并，但只要有合并区越出深度，就说明数据区也被合并了。
     * 只有首表可以带合并区，其余 Sheet 带合并区说明格式不是这个约定，不去猜。
     */
    public static HeaderPlan headerPlan(Map<String, List<String>> merged, String firstSheet) {
        if (merged == null || merged.isEmpty() || firstSheet == null
                || merged.size() != 1) {
            return null;
        }
        List<String> ranges = merged.get(firstSheet);
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        List<int[]> parsed = new ArrayList<int[]>();
        for (String ref : ranges) {
            int[] box = parseRange(ref);
            if (box == null) {
                return null;
            }
            parsed.add(box);
        }
        boolean hasTop = false;
        int depth = 0;
        for (int[] b : parsed) {
            if (b[0] == 0) {
                hasTop = true;
                depth = Math.max(depth, b[2] + 1);
            }
        }
        if (!hasTop || depth < 2) {
            return null;
        }
        for (int[] b : parsed) {
            if (b[2] >= depth) {
                return null;
            }
        }
        return new HeaderPlan(depth, parsed);
    }

    private static Map<String, List<String>> scanXlsx(String path) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        ZipFile z = new ZipFile(path);
        try {
            Map<String, String> rels = new LinkedHashMap<String, String>();
            byte[] wb = read(z, "xl/workbook.xml");
            if (wb == null) {
                return out;
            }
            String wbxml = decode(wb);
            byte[] relxml = read(z, "xl/_rels/workbook.xml.rels");
            if (relxml != null) {
                Matcher m = REL_TAG.matcher(decode(relxml));
                while (m.find()) {
                    String tag = m.group();
                    Matcher id = REL_ID.matcher(tag);
                    Matcher tgt = REL_TARGET.matcher(tag);
                    if (id.find() && tgt.find()) {
                        rels.put(id.group(1), tgt.group(1));
                    }
                }
            }
            Matcher sm = SHEET_TAG.matcher(wbxml);
            while (sm.find()) {
                String tag = sm.group();
                Matcher nm = SHEET_NAME.matcher(tag);
                Matcher rid = SHEET_RID.matcher(tag);
                if (!nm.find() || !rid.find()) {
                    continue;
                }
                String tgt = rels.get(rid.group(1));
                if (tgt == null || tgt.isEmpty() || tgt.startsWith("http:")
                        || tgt.startsWith("file:") || tgt.startsWith("mailto:")) {
                    continue;                      // 外部链接，不是包内部件
                }
                String inner = stripSlashes(tgt);
                if (!inner.startsWith("xl/")) {
                    inner = "xl/" + inner;
                }
                if (z.getEntry(inner) == null) {
                    continue;
                }
                List<String> found = scanSheet(z, inner);
                if (!found.isEmpty()) {
                    out.put(nm.group(1), found);
                }
            }
        } finally {
            z.close();
        }
        return out;
    }

    private static String stripSlashes(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '/' || s.charAt(i) == '\\')) {
            i++;
        }
        return s.substring(i);
    }

    private static byte[] read(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) {
            return null;
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        InputStream in = z.getInputStream(e);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return bos.toByteArray();
    }

    private static String decode(byte[] raw) {
        return new String(raw, UTF8);
    }

    /**
     * 流式读出该 sheet 的全部 mergeCell 引用。
     * 不做"命中即停"：20 万行的表本来就要扫到底才能确认无合并，早停省不下什么，
     * 却会让"含 N 处合并区"的 N 恒等于 1，报出假数字。
     */
    private static List<String> scanSheet(ZipFile z, String inner) throws Exception {
        final List<String> refs = new ArrayList<String>();
        InputStream in = z.getInputStream(z.getEntry(inner));
        try {
            byte[] carry = new byte[0];
            byte[] buf = new byte[CHUNK];
            while (true) {
                int got = fill(in, buf);
                if (got <= 0) {
                    break;
                }
                byte[] window = concat(carry, buf, got);
                Matcher m = MERGE_CELL.matcher(decode(window));
                while (m.find()) {
                    if (isRealRange(m.group(1))) {
                        refs.add(m.group(1));
                    }
                }
                int from = Math.max(0, window.length - CARRY);
                carry = new byte[window.length - from];
                System.arraycopy(window, from, carry, 0, carry.length);
            }
        } finally {
            in.close();
        }
        return sortedUnique(refs);
    }

    private static int fill(InputStream in, byte[] buf) throws Exception {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static byte[] concat(byte[] a, byte[] b, int bLen) {
        byte[] out = new byte[a.length + bLen];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, bLen);
        return out;
    }

    private static boolean isRealRange(String ref) {
        Matcher m = RANGE.matcher(ref);
        if (!m.matches()) {
            return false;
        }
        return !(m.group(1).equals(m.group(3)) && m.group(2).equals(m.group(4)));
    }

    private static int colWidth(String col) {
        int n = 0;
        for (int i = 0; i < col.length(); i++) {
            n = n * 26 + (col.charAt(i) - 64);
        }
        return n;
    }

    /** 按左上角坐标排序；列名不能按字符串排（A10 会跑到 A2 前面）。 */
    private static List<String> sortedUnique(List<String> refs) {
        Set<String> unique = new LinkedHashSet<String>(refs);
        List<String> out = new ArrayList<String>(unique);
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) {
                long[] ka = key(a);
                long[] kb = key(b);
                if (ka[0] != kb[0]) {
                    return Long.compare(ka[0], kb[0]);
                }
                return Long.compare(ka[1], kb[1]);
            }
        });
        return out;
    }

    private static long[] key(String ref) {
        Matcher m = RANGE.matcher(ref);
        if (m.matches()) {
            return new long[]{Long.parseLong(m.group(2)), colWidth(m.group(1))};
        }
        return new long[]{0L, 0L};
    }

    private static String colLetter(int idx0) {
        StringBuilder s = new StringBuilder();
        int n = idx0 + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            n = (n - 1) / 26;
            s.insert(0, (char) ('A' + r));
        }
        return s.toString();
    }

    /**
     * 老格式走 POI 的 HSSF。xlrd 那边必须 formatting_info=True 才填 merged_cells，
     * POI 读 MERGEDAREA 不需要额外开关，所以这条比 Python 线更稳。
     */
    private static Map<String, List<String>> scanXls(String path) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        InputStream in = new java.io.FileInputStream(path);
        Workbook wb = null;
        try {
            wb = new HSSFWorkbook(in);
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                List<String> ranges = new ArrayList<String>();
                for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                    CellRangeAddress area = sheet.getMergedRegion(i);
                    if (area.getLastRow() <= area.getFirstRow()
                            && area.getLastColumn() <= area.getFirstColumn()) {
                        continue;                  // 只跨一格的不算真合并
                    }
                    ranges.add(colLetter(area.getFirstColumn()) + (area.getFirstRow() + 1)
                            + ":" + colLetter(area.getLastColumn()) + (area.getLastRow() + 1));
                }
                if (!ranges.isEmpty()) {
                    out.put(sheet.getSheetName(), sortedUnique(ranges));
                }
            }
        } catch (Exception e) {
            return new LinkedHashMap<String, List<String>>();
        } finally {
            if (wb != null) {
                wb.close();
            }
            in.close();
        }
        return out;
    }
}
