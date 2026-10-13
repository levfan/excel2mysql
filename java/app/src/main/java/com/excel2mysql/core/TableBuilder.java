package com.excel2mysql.core;

import com.excel2mysql.excel.WorkbookReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * core/infer.py 的 build_table_def：读一个 Excel，识别两类格式，产出表定义。
 *
 * 两类格式的判据是"有没有某个 Sheet 长得像字段定义表"，命中就走结构定义模式
 * （字段名/类型/长度/主键/可空全按元数据为准），否则走普通模式（从数据推断）。
 */
public final class TableBuilder {

    private static final String[][] META_ROLES = {
        {"field", "字段名", "列名"},
        {"comment", "字段注释", "注释", "说明"},
        {"type", "数据类型", "类型"},
        {"length", "长度"},
        {"pk", "主键"},
        {"nullable", "可空", "为空"},
    };

    private static final Pattern META_TYPE_INLINE =
            Pattern.compile("^([A-Z][A-Z0-9_]*)\\s*\\((.*)\\)$");
    private static final Pattern META_TYPE_PLAIN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Pattern WS_RUN = Pattern.compile("\\s+");

    private final Infer infer;
    private final Names names;

    public TableBuilder(Infer infer, Names names) {
        this.infer = infer;
        this.names = names;
    }

    public interface ProgressCallback {
        void stage(String text);
    }

    /** 表定义 + 建库要写入的数据网格。 */
    public static final class Result {
        public TableOps.Table table;
        public List<Object> headers = new ArrayList<Object>();
        public List<List<Object>> rows = new ArrayList<List<Object>>();
        public String pkReason;
        public String metaSheet;
        public String dataSheet;
        public final List<String> extraDataColumns = new ArrayList<String>();
    }

    private static final class Grid {
        List<Object> headers = new ArrayList<Object>();
        List<List<Object>> rows = new ArrayList<List<Object>>();
    }

    public Result build(String filepath) {
        return build(filepath, null);
    }

    public Result build(String filepath, ProgressCallback cb) {
        String lower = filepath.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot);
        if (!".xls".equals(ext) && !".xlsx".equals(ext) && !".xlsm".equals(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：" + ext);
        }
        // 合并单元格要在读数据之前判掉：这类文件解析出来必然错位，
        // 提前拒绝既避免导错数据，也省掉几十万行的读取开销
        if (cb != null) {
            cb.stage("检查合并单元格");
        }
        MergeCheck.ensureNoMergedCells(filepath);
        if (cb != null) {
            cb.stage("读取工作簿");
        }
        List<Frame> frames = new ArrayList<Frame>();
        for (WorkbookReader.Sheet s : WorkbookReader.read(filepath)) {
            frames.add(new Frame(s.name, trimAndPad(s.rows)));
        }
        if (cb != null) {
            cb.stage("识别表格格式");
        }
        int metaIndex = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (matchMetaRoles(frames.get(i)) != null) {
                metaIndex = i;
                break;
            }
        }
        if (metaIndex >= 0) {
            if (cb != null) {
                cb.stage("按字段定义组装");
            }
            return buildStructured(filepath, frames, metaIndex);
        }
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("工作簿里没有可读的 Sheet");
        }
        Frame first = frames.get(0);
        Grid grid = dropEmptyColumnsAndRows(first);
        String[] tc = names.parseTableNameAndComment(filepath);
        Result r = new Result();
        r.table = new TableOps.Table();
        r.table.filepath = filepath;
        r.table.tableName = tc[0];
        r.table.baseTableName = tc[0];
        r.table.tableComment = tc[1];
        r.table.source = "normal";
        r.headers = grid.headers;
        r.rows = grid.rows;
        if (cb != null) {
            cb.stage("推断字段类型");
        }
        r.table.columns = infer.inferColumns(grid.headers, grid.rows);
        if (cb != null) {
            cb.stage("选择主键");
        }
        String[] pk = infer.selectPrimaryKey(grid.headers, grid.rows, r.table.columns);
        r.pkReason = pk[1];
        if (pk[0] != null) {
            for (Infer.Column c : r.table.columns) {
                c.primaryKey = pk[0].equals(c.field);
            }
        }
        return r;
    }

    /**
     * 结构定义模式。
     *
     * 空单元格这一条要小心：pandas 读进来是 NaN，而 Python 的 `str(NaN or "")` 得到字面量 "nan"
     * （NaN 是真值），所以"字段名留空"的那一行不会像直觉那样被跳过，而是生成一个叫 nan 的列。
     * 这是现网行为，Java 侧 null 不经过同样处理就会变成"跳过该行"，两边就分叉了。
     */
    private Result buildStructured(String filepath, List<Frame> frames, int metaIndex) {
        Frame metaFrame = frames.get(metaIndex);
        Map<String, Object> roles = matchMetaRoles(metaFrame);
        String[] tc = names.parseTableNameAndComment(filepath);

        Result r = new Result();
        r.table = new TableOps.Table();
        r.table.filepath = filepath;
        r.table.tableName = tc[0];
        r.table.baseTableName = tc[0];
        r.table.tableComment = tc[1];
        r.table.source = "structured";
        r.metaSheet = metaFrame.name;

        for (List<Object> row : metaFrame.rows) {
            if (allNullish(row)) {
                continue;
            }
            String field = WS_RUN.matcher(Names.pyStrip(nanToString(cell(row, metaFrame, roles, "field"))))
                    .replaceAll("_");
            String type = normMetaType(cell(row, metaFrame, roles, "type"),
                    cell(row, metaFrame, roles, "length"));
            if (field.isEmpty() || type == null) {
                continue;
            }
            String lower = field.toLowerCase();
            if (containsIgnoreCase(r.table, lower)) {
                int k = 2;
                while (containsIgnoreCase(r.table, (field + "_" + k).toLowerCase())) {
                    k++;
                }
                field = field + "_" + k;
            }
            Infer.Column col = new Infer.Column();
            col.field = field;
            col.comment = commentOf(cell(row, metaFrame, roles, "comment"));
            col.type = type;
            col.nullable = metaNullable(cell(row, metaFrame, roles, "nullable"));
            col.primaryKey = metaFlag(cell(row, metaFrame, roles, "pk"));
            r.table.columns.add(col);
        }
        if (r.table.columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "结构定义 Sheet 中没有可识别的字段定义行（每行需要字段名和数据类型）");
        }

        int dataIndex = -1;
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (i != metaIndex && matchMetaRoles(f) == null && !f.headers.isEmpty()) {
                dataIndex = i;
                break;
            }
        }
        List<Object> headers = new ArrayList<Object>();
        List<List<Object>> rows = new ArrayList<List<Object>>();
        for (Infer.Column c : r.table.columns) {
            headers.add(c.field);
        }
        if (dataIndex >= 0) {
            Frame data = frames.get(dataIndex);
            Grid grid = dropEmptyColumnsAndRows(data);
            r.dataSheet = data.name;
            Map<String, Integer> byLower = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < grid.headers.size(); i++) {
                byLower.put(Names.pyStrip(Infer.pyStr(grid.headers.get(i))).toLowerCase(),
                        Integer.valueOf(i));
            }
            Set<String> known = new LinkedHashSet<String>();
            for (Infer.Column c : r.table.columns) {
                known.add(c.field.toLowerCase());
            }
            for (List<Object> src : grid.rows) {
                List<Object> out = new ArrayList<Object>();
                for (Infer.Column c : r.table.columns) {
                    Integer at = byLower.get(c.field.toLowerCase());
                    Object v = at == null || at.intValue() >= src.size() ? null : src.get(at.intValue());
                    out.add(v);
                }
                rows.add(out);
            }
            for (Object h : grid.headers) {
                String text = Infer.pyStr(h);
                if (!known.contains(Names.pyStrip(text).toLowerCase())) {
                    r.extraDataColumns.add(text);
                }
            }
        }
        r.headers = headers;
        r.rows = rows;

        List<String> pkFields = new ArrayList<String>();
        for (Infer.Column c : r.table.columns) {
            if (c.primaryKey) {
                pkFields.add(c.field);
            }
        }
        if (pkFields.isEmpty()) {
            r.pkReason = "结构定义中未标记主键";
        } else {
            String[] check = validatePrimaryKey(headers, rows, r.table.columns);
            r.pkReason = "结构定义（元数据 Sheet）指定";
            if (!check[0].isEmpty()) {
                r.pkReason += "；注意：" + check[0];
            }
        }
        return r;
    }

    /** 返回 {"", ""} 表示可用；第一个元素非空时是问题说明。 */
    private String[] validatePrimaryKey(List<Object> headers, List<List<Object>> rows,
                                        List<Infer.Column> columns) {
        List<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).primaryKey) {
                idx.add(Integer.valueOf(i));
            }
        }
        if (idx.isEmpty()) {
            return new String[]{"", ""};
        }
        for (Integer i : idx) {
            Infer.Column c = columns.get(i.intValue());
            if (!Infer.pkTypeOk(c.type == null ? "" : c.type)) {
                return new String[]{"字段 " + c.field + " 类型 " + c.type
                        + " 不能作为主键（可用 INT/BIGINT 或长度 ≤255 的 VARCHAR/CHAR）", ""};
            }
        }
        if (rows.isEmpty()) {
            return new String[]{"没有数据行，无法校验主键", ""};
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> parts = new ArrayList<String>();
            for (Integer i : idx) {
                List<Object> row = rows.get(r);
                Object v = i.intValue() < row.size() ? row.get(i.intValue()) : null;
                if (isNullish(v)) {
                    return new String[]{"第 " + (r + 1) + " 行存在空值，主键字段不允许为空", ""};
                }
                if (v instanceof String && Names.pyStrip((String) v).isEmpty()) {
                    return new String[]{"第 " + (r + 1) + " 行存在空白值，主键字段不允许为空", ""};
                }
                parts.add(Infer.pkValueKey(v));
            }
            if (!seen.add(join(parts, "\u001f"))) {
                return new String[]{"第 " + (r + 1) + " 行主键组合值重复，主键字段组合必须唯一", ""};
            }
        }
        return new String[]{"", ""};
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

    private static boolean containsIgnoreCase(TableOps.Table t, String lowerField) {
        for (Infer.Column c : t.columns) {
            if (c.field.toLowerCase().equals(lowerField)) {
                return true;
            }
        }
        return false;
    }

    private static Object cell(List<Object> row, Frame frame, Map<String, Object> roles, String role) {
        Object header = roles.get(role);
        if (header == null) {
            return null;
        }
        int at = frame.indexOf(header);
        return at < 0 || at >= row.size() ? null : row.get(at);
    }

    /** Python 的 str(x or "") 对 NaN 得到 "nan"，对 None 得到 ""。 */
    private static String nanToString(Object v) {
        if (v == null) {
            return "nan";
        }
        if (v instanceof Double && Double.isNaN(((Double) v).doubleValue())) {
            return "nan";
        }
        return Infer.pyStr(v);
    }

    private static String commentOf(Object v) {
        if (v == null || (v instanceof Double && Double.isNaN(((Double) v).doubleValue()))) {
            return "";
        }
        return Names.pyStrip(Infer.pyStr(v));
    }

    /** 长度单元格 -> 规范字符串（'20'、'10,2'）；空或非法返回 null。 */
    static String normMetaLength(Object v) {
        if (v == null || (v instanceof Double && Double.isNaN(((Double) v).doubleValue()))) {
            return null;
        }
        String s = Names.pyStrip(Infer.pyStr(v)).replace("，", ",");
        if (s.isEmpty()) {
            return null;
        }
        if (s.indexOf(',') >= 0) {
            String[] parts = s.split(",", -1);
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                Long n = toIntFromFloat(part);
                if (n == null) {
                    return null;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(n.longValue());
            }
            return sb.toString();
        }
        Long n = toIntFromFloat(s);
        return n == null ? null : String.valueOf(n.longValue());
    }

    /** 对齐 Python 的 int(float(p))：截断取整，非数字返回 null。 */
    private static Long toIntFromFloat(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return Long.valueOf((long) d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 类型单元格 + 长度单元格 -> 规范 SQL 类型。 */
    static String normMetaType(Object rawType, Object lengthVal) {
        if (rawType == null
                || (rawType instanceof Double && Double.isNaN(((Double) rawType).doubleValue()))) {
            return null;
        }
        String t = Names.pyStrip(Infer.pyStr(rawType)).toUpperCase()
                .replace('（', '(').replace('）', ')');
        Matcher m = META_TYPE_INLINE.matcher(t);
        if (m.matches()) {
            String inline = normMetaLength(m.group(2));
            return inline == null ? m.group(1) : m.group(1) + "(" + inline + ")";
        }
        if (!META_TYPE_PLAIN.matcher(t).matches()) {
            return null;
        }
        String ln = normMetaLength(lengthVal);
        return ln == null ? t : t + "(" + ln + ")";
    }

    private static boolean metaFlag(Object v) {
        if (isNullish(v)) {
            return false;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Double) {
            return ((Double) v).doubleValue() == 1.0;
        }
        if (v instanceof java.math.BigInteger || v instanceof Long || v instanceof Integer) {
            return ((Number) v).longValue() == 1L;
        }
        String s = Names.pyStrip(Infer.pyStr(v)).toLowerCase();
        return "是".equals(s) || "y".equals(s) || "yes".equals(s) || "true".equals(s) || "1".equals(s);
    }

    private static boolean metaNullable(Object v) {
        if (isNullish(v)) {
            return true;
        }
        if (v instanceof Boolean) {
            return !((Boolean) v).booleanValue();
        }
        if (v instanceof Double) {
            return ((Double) v).doubleValue() != 1.0;
        }
        if (v instanceof java.math.BigInteger || v instanceof Long || v instanceof Integer) {
            return ((Number) v).longValue() != 1L;
        }
        String s = Names.pyStrip(Infer.pyStr(v)).toLowerCase();
        return !("否".equals(s) || "n".equals(s) || "no".equals(s) || "false".equals(s) || "0".equals(s));
    }

    private static boolean isNullish(Object v) {
        return v == null || (v instanceof Double && Double.isNaN(((Double) v).doubleValue()));
    }

    private static boolean allNullish(List<Object> row) {
        for (Object v : row) {
            if (!isNullish(v)) {
                return false;
            }
        }
        return true;
    }

    /** 判据：命中 field + type 且至少还有一个其他定义列。 */
    private static Map<String, Object> matchMetaRoles(Frame frame) {
        if (frame.rows.isEmpty() || frame.headers.size() < 3) {
            return null;
        }
        Map<String, Object> roles = new LinkedHashMap<String, Object>();
        for (Object h : frame.headers) {
            String text = Names.pyStrip(Infer.pyStr(h));
            if (text.isEmpty()) {
                continue;
            }
            for (String[] role : META_ROLES) {
                if (roles.containsKey(role[0])) {
                    continue;
                }
                for (int i = 1; i < role.length; i++) {
                    if (text.contains(role[i])) {
                        roles.put(role[0], h);
                        break;
                    }
                }
                if (roles.containsKey(role[0])) {
                    break;
                }
            }
        }
        return roles.containsKey("field") && roles.containsKey("type") && roles.size() >= 3
                ? roles : null;
    }

    /** 去掉全空列与全空行：Excel 尾部格式化留下的空行会整行 NULL 导入。 */
    private static Grid dropEmptyColumnsAndRows(Frame frame) {
        Grid g = new Grid();
        List<Integer> keep = new ArrayList<Integer>();
        for (int i = 0; i < frame.headers.size(); i++) {
            for (List<Object> row : frame.rows) {
                if (!isNullish(i < row.size() ? row.get(i) : null)) {
                    keep.add(Integer.valueOf(i));
                    break;
                }
            }
        }
        for (Integer i : keep) {
            g.headers.add(frame.headers.get(i.intValue()));
        }
        for (List<Object> row : frame.rows) {
            List<Object> projected = new ArrayList<Object>();
            boolean any = false;
            for (Integer i : keep) {
                Object v = i.intValue() < row.size() ? row.get(i.intValue()) : null;
                projected.add(v);
                if (!isNullish(v)) {
                    any = true;
                }
            }
            if (any) {
                g.rows.add(projected);
            }
        }
        return g;
    }

    /** pandas 的网格形状：裁行尾空格、去尾部空行、补齐到最大宽度。 */
    static List<List<Object>> trimAndPad(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<List<Object>>();
        int lastWithData = -1;
        for (List<Object> row : rows) {
            List<Object> r = new ArrayList<Object>(row);
            while (!r.isEmpty() && isNullishOrEmpty(r.get(r.size() - 1))) {
                r.remove(r.size() - 1);
            }
            if (!r.isEmpty()) {
                lastWithData = out.size();
            }
            out.add(r);
        }
        List<List<Object>> kept = new ArrayList<List<Object>>(out.subList(0, lastWithData + 1));
        int width = 0;
        for (List<Object> r : kept) {
            width = Math.max(width, r.size());
        }
        for (List<Object> r : kept) {
            while (r.size() < width) {
                r.add(null);
            }
        }
        return kept;
    }

    private static boolean isNullishOrEmpty(Object v) {
        return v == null || "".equals(v);
    }
}
