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
        /** 表头写了字、整列却没有一个值的字段：照旧保留，但要让日志点名（与 Python 同口径）。 */
        public final List<String> emptyColumns = new ArrayList<String>();
        /** 一行数据都没有（只有表头的采集模板就是这种形状），建出来是空表。 */
        public boolean noDataRows;
        /** 表头整行没写字时按列宽兜底出来的字段名（col、col_2…）；正常表为空列表。 */
        public final List<String> blankHeaders = new ArrayList<String>();
        /** 顶部两行合并表头展开的行数；0 表示没展开。 */
        public int mergedHeaderDepth;
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
        // 提前拒绝既避免导错数据，也省掉几十万行的读取开销。
        // 例外是首表顶部的标准两行合并表头：展开成「父-子」单行表头后照常导入；
        // 别的表头形态、数据区（或别的 Sheet）里还有合并区则继续拒绝。
        if (cb != null) {
            cb.stage("检查合并单元格");
        }
        Map<String, List<String>> merged = MergeCheck.detect(filepath);
        MergeCheck.HeaderPlan mergePlan = null;
        if (!merged.isEmpty()) {
            String firstSheet = WorkbookReader.firstSheetName(filepath);
            mergePlan = MergeCheck.headerPlan(merged, firstSheet);
            if (mergePlan == null) {
                throw new MergeCheck.MergedCellsException(filepath, merged);
            }
        }
        if (cb != null) {
            cb.stage("读取工作簿");
        }
        List<Frame> frames = new ArrayList<Frame>();
        List<WorkbookReader.Sheet> raw = WorkbookReader.read(filepath);
        if (mergePlan != null) {
            if (cb != null) {
                cb.stage("展开合并表头");
            }
            raw.set(0, flattenMergedHeader(raw.get(0), mergePlan));
        }
        // 表头整行都是空格子时，pandas 那套形状（trimAndPad 裁行尾空格、去尾部空行）会把
        // 列宽一起裁没——和 Python 侧丢列是同一个结果。兜底要用的是"裁之前第一行有几个格"，
        // 所以这里在裁剪前先把第一个 Sheet 的首行宽度记下来。
        int rawHeadWidth = 0;
        if (!raw.isEmpty() && !raw.get(0).rows.isEmpty()) {
            rawHeadWidth = raw.get(0).rows.get(0).size();
        }
        for (WorkbookReader.Sheet s : raw) {
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
            Result structured = buildStructured(filepath, frames, metaIndex);
            structured.mergedHeaderDepth = mergePlan == null ? 0 : mergePlan.depth;
            return structured;
        }
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("工作簿里没有可读的 Sheet");
        }
        Frame first = frames.get(0);
        String[] tc = names.parseTableNameAndComment(filepath);
        Result r = new Result();
        r.mergedHeaderDepth = mergePlan == null ? 0 : mergePlan.depth;
        Grid grid = dropEmptyColumnsAndRows(first, r.emptyColumns);
        r.noDataRows = grid.rows.isEmpty();
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
        // 表头整行都没写字时也要能导入：两侧的形状处理都会把这行的宽度丢掉（Python 是 pandas，
        // Java 是 trimAndPad），所以要拿"裁之前第一行有几个格"兜底出同样数量的空表头列，
        // 字段名走空表头那条兜底（col、col_2…）。两侧要的一致是"列数与名字一致"，不是实现一致。
        if (r.table.columns.isEmpty() && rawHeadWidth > 0) {
            Grid blank = new Grid();
            for (int i = 0; i < rawHeadWidth; i++) {
                blank.headers.add("");            // 表头没写字：交给空表头兜底，产出 col、col_2…
            }
            grid = blank;
            r.headers = grid.headers;
            r.rows = grid.rows;
            r.noDataRows = true;
            r.emptyColumns.clear();
            if (cb != null) {
                cb.stage("表头整行没写字：按列宽 " + rawHeadWidth + " 兜底");
            }
            r.table.columns = infer.inferColumns(grid.headers, grid.rows);
            for (Infer.Column c : r.table.columns) {
                r.blankHeaders.add(c.field);
            }
        }
        // 与 Python 侧同一道闸：一列都没有就别往下走，
        // 否则建出来是 `CREATE TABLE x ( )`，现场只看到"导入失败"，猜不到是拖了张空表
        if (r.table.columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "没有解析到任何字段：表里没有可用的表头行（空表，或整列都是空值）");
        }
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
     * 把首表的标准两行合并表头压成一行，返回只含数据行的网格（与 core/infer.py 同一规则）。
     *
     * 合并区里的空格子按左上角的值补齐：纵向合并（序号 A1:A2）让每行拿到同一标签，
     * 横向合并（技术公司 J1:M1）让组内每列拿到组名。逐列自上而下取标签，
     * 相邻重复只留一份，用「-」连接；空表头与重名交给后面的 Frame 按 pandas 规则处理。
     */
    private static WorkbookReader.Sheet flattenMergedHeader(WorkbookReader.Sheet sheet,
                                                            MergeCheck.HeaderPlan plan) {
        int width = 0;
        for (List<Object> row : sheet.rows) {
            width = Math.max(width, row.size());
        }
        List<List<Object>> head = new ArrayList<List<Object>>();
        for (int r = 0; r < plan.depth; r++) {
            List<Object> row = new ArrayList<Object>();
            for (int c = 0; c < width; c++) {
                Object v = null;
                if (r < sheet.rows.size() && c < sheet.rows.get(r).size()) {
                    v = sheet.rows.get(r).get(c);
                }
                row.add(v);
            }
            head.add(row);
        }
        for (int[] m : plan.merges) {
            int r1 = m[0];
            int c1 = m[1];
            int r2 = m[2];
            int c2 = m[3];
            if (r1 >= plan.depth || c1 >= width) {
                continue;
            }
            Object value = head.get(r1).get(c1);
            for (int r = r1; r <= r2 && r < plan.depth; r++) {
                for (int c = c1; c <= c2 && c < width; c++) {
                    head.get(r).set(c, value);
                }
            }
        }
        List<Object> headerRow = new ArrayList<Object>();
        for (int c = 0; c < width; c++) {
            List<String> path = new ArrayList<String>();
            for (int r = 0; r < plan.depth; r++) {
                String text = headerText(head.get(r).get(c));
                if (text.isEmpty()) {
                    continue;
                }
                if (!path.isEmpty() && path.get(path.size() - 1).equals(text)) {
                    continue;
                }
                path.add(text);
            }
            headerRow.add(String.join("-", path));
        }
        List<List<Object>> flat = new ArrayList<List<Object>>();
        flat.add(headerRow);
        for (int r = plan.depth; r < sheet.rows.size(); r++) {
            flat.add(sheet.rows.get(r));
        }
        return new WorkbookReader.Sheet(sheet.name, flat);
    }

    private static String headerText(Object v) {
        if (v == null || (v instanceof Double && Double.isNaN(((Double) v).doubleValue()))) {
            return "";
        }
        return Names.pyStrip(Infer.pyStr(v));
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
            if (names.isUpperOnly()) {
                // 开关写的是「表名与字段名转大写」，结构定义模式不能只转表名半句；
                // 不勾时保持原样——不做大写转换不等于替使用者把手写的名字改成小写
                field = field.toUpperCase(java.util.Locale.ROOT);
            }
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
            Grid grid = dropEmptyColumnsAndRows(data, new ArrayList<String>());
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
        r.noDataRows = rows.isEmpty();
        // 结构定义模式的字段来自元数据 Sheet，列名就是声明过的字段名；
        // 整列没值（含一行数据都没有）的照样要点名，与 Python 侧 _named_blank_columns 同一口径
        for (int i = 0; i < headers.size(); i++) {
            boolean anyValue = false;
            for (List<Object> row : rows) {
                if (!isNullish(i < row.size() ? row.get(i) : null)) {
                    anyValue = true;
                    break;
                }
            }
            if (!anyValue) {
                r.emptyColumns.add(Infer.pyStr(headers.get(i)).trim());
            }
        }

        List<String> pkFields = new ArrayList<String>();
        for (Infer.Column c : r.table.columns) {
            if (c.primaryKey) {
                pkFields.add(c.field);
            }
        }
        if (pkFields.isEmpty()) {
            r.pkReason = "结构定义中未标记主键";
        } else {
            String bad = validatePrimaryKey(rows, r.table.columns);
            r.pkReason = "结构定义（元数据 Sheet）指定";
            if (!bad.isEmpty()) {
                r.pkReason += "；注意：" + bad;
            }
        }
        return r;
    }

    /**
     * 主键可用性校验：空串表示可用，非空是问题说明。
     * 结构弹窗改完勾选也要过同一条，所以这里公开——两处各写一套校验，
     * 弹窗放行的主键到建表时才发现不能用，用户只能来回猜。
     *
     * <p>没有数据行时不因无数据拒绝：非空与唯一在空集上天然成立，仍可手工指定主键；
     * 只有自动选键才需要数据来判断。
     */
    public static String validatePrimaryKey(List<List<Object>> rows,
                                            List<Infer.Column> columns) {
        List<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).primaryKey) {
                idx.add(Integer.valueOf(i));
            }
        }
        if (idx.isEmpty()) {
            return "";
        }
        for (Integer i : idx) {
            Infer.Column c = columns.get(i.intValue());
            if (!Infer.pkTypeOk(c.type == null ? "" : c.type)) {
                return "字段 " + c.field + " 类型 " + c.type
                        + " 不能作为主键（可用 INT/BIGINT 或长度 ≤255 的 VARCHAR/CHAR）";
            }
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> parts = new ArrayList<String>();
            for (Integer i : idx) {
                List<Object> row = rows.get(r);
                Object v = i.intValue() < row.size() ? row.get(i.intValue()) : null;
                if (isNullish(v)) {
                    return "第 " + (r + 1) + " 行存在空值，主键字段不允许为空";
                }
                if (v instanceof String && Names.pyStrip((String) v).isEmpty()) {
                    return "第 " + (r + 1) + " 行存在空白值，主键字段不允许为空";
                }
                parts.add(Infer.pkValueKey(v));
            }
            if (!seen.add(join(parts, "\u001f"))) {
                return "第 " + (r + 1) + " 行主键组合值重复，主键字段组合必须唯一";
            }
        }
        return "";
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

    /** 列取舍的结果：留下的列下标，以及"表头有字但整列没值"因而需要点名的列名。 */
    public static final class ColumnFilter {
        public final List<Integer> keep = new ArrayList<Integer>();
        public final List<String> emptyColumns = new ArrayList<String>();
    }

    /**
     * 列取舍，pandas 那两步（dropna(axis=1, how=all) + 具名空列保留）的唯一实现：
     * 整列没值 **且** 表头也没写字的列才是 Excel 尾部格式化拖出来的垃圾列，丢掉；
     * 表头写了字的列一律保留，名字进 emptyColumns 由日志点名。
     *
     * TypeDiffer 与读取层都调这一个方法——这条规则要是在两处各写一遍，
     * 改一处就静默分叉，差分会告诉我们"两边不等价"，但谁对谁错得再查一轮。
     */
    public static ColumnFilter filterColumns(List<Object> headers, List<List<Object>> rows) {
        ColumnFilter f = new ColumnFilter();
        for (int i = 0; i < headers.size(); i++) {
            boolean anyValue = false;
            for (List<Object> row : rows) {
                if (!isNullish(i < row.size() ? row.get(i) : null)) {
                    anyValue = true;
                    break;
                }
            }
            if (anyValue) {
                f.keep.add(Integer.valueOf(i));
            } else if (!blankHeader(headers.get(i))) {
                f.keep.add(Integer.valueOf(i));
                f.emptyColumns.add(Infer.pyStr(headers.get(i)).trim());
            }
        }
        return f;
    }

    /** 表头本身没写字：Excel 尾部格式化拖出来的空列，在 pandas 里叫 "Unnamed: 5"，真表头则是空串。 */
    public static boolean blankHeader(Object h) {
        String s = h == null ? "" : Infer.pyStr(h).trim();
        return s.isEmpty() || s.toLowerCase().startsWith("unnamed:");
    }

    /**
     * 去掉"表头没写字且整列没值"的格式化垃圾列与全空行；具名但整列没值的字段一律保留。
     *
     * 采集模板里 <code>*标准名称</code> 这类必填项还没填数据，字段是使用方自己声明过的表结构。
     * 照"整列没值就删"处理，空模板一进来看不到任何字段（要么少列、要么被判成解析失败），
     * 现场看到的就是"表头解析不到"。判空只看 null/NaN，与 pandas 的 isna 同口径：
     * 整列空字符串的列照旧不算空列。
     */
    private static Grid dropEmptyColumnsAndRows(Frame frame, List<String> emptyColumns) {
        Grid g = new Grid();
        ColumnFilter cf = filterColumns(frame.headers, frame.rows);
        List<Integer> keep = cf.keep;
        emptyColumns.addAll(cf.emptyColumns);
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
