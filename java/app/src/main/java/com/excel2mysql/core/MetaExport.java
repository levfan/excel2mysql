package com.excel2mysql.core;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * core/meta_export.py 的对应实现：把已解析出的表定义写成两种 Excel 交付件。
 *
 * 标准件两个 Sheet（元数据 + 数据），简易件只有数据 Sheet。七列表头一个字都不能改：
 * 读取端 matchMetaRoles 靠"字段名/数据类型/长度"这些关键词认领列，改了就不认了。
 *
 * "生成的文件必须能被 TableBuilder 原样读回来"是这条路的验收口径，
 * 由 dump() + 自检测着，不靠看起来一样。
 */
public final class MetaExport {

    public static final String META_SHEET = "元数据";
    public static final String DATA_SHEET = "数据";
    public static final String[] META_HEADERS = {"序号", "字段名(英文)", "字段注释(中文)",
            "数据类型", "长度", "是否主键", "是否可为空"};

    public static final String STD_PREFIX = "meta_data_file";
    public static final String SIMPLE_PREFIX = "simple_meta_data_file";
    /** 「SQL 脚本改名入库」那页的生成物目录前缀，见 {@link #outputDir(String, boolean, String, String)}。 */
    public static final String SQL_DIR_TAG = "sql_";

    private static final Pattern TYPE_WITH_LEN =
            Pattern.compile("^([A-Z][A-Z0-9_]*)\\s*\\((.*)\\)$");
    private static final Pattern ILLEGAL_IN_NAME = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 一张待导出的表：表定义 + 数据网格（网格来自 TableBuilder 的解析结果）。 */
    public static final class Source {
        public TableOps.Table table;
        public List<Object> headers = new ArrayList<Object>();
        public List<List<Object>> rows = new ArrayList<List<Object>>();

        public int rowCount() {
            return rows == null ? 0 : rows.size();
        }

        public int columnCount() {
            return table == null || table.columns == null ? 0 : table.columns.size();
        }
    }

    public static Source of(TableOps.Table table, TableBuilder.Result result) {
        Source s = new Source();
        s.table = table;
        if (result != null) {
            if (result.headers != null) {
                s.headers = result.headers;
            }
            if (result.rows != null) {
                s.rows = result.rows;
            }
        }
        return s;
    }

    /** 读回来的一个 Sheet：名字 + 逐格取值（第一行是表头）。 */
    public static final class Dump {
        public final String sheet;
        public final List<List<Object>> cells = new ArrayList<List<Object>>();

        Dump(String sheet) {
            this.sheet = sheet;
        }
    }

    public static final class Outcome {
        public String dir;
        public final List<String> written = new ArrayList<String>();
        public int total;
        public int failed;
        public boolean cancelled;
    }

    private MetaExport() {
    }

    /** VARCHAR(64) -> {VARCHAR, 64}；DECIMAL(10,2) -> {DECIMAL, 10,2}；没有长度时第二个为 null。 */
    public static String[] splitType(String type) {
        String s = (type == null ? "" : type).trim().toUpperCase()
                .replace('（', '(').replace('）', ')');
        Matcher m = TYPE_WITH_LEN.matcher(s);
        if (!m.matches()) {
            return new String[]{s, null};
        }
        String len = m.group(2).trim();
        return new String[]{m.group(1), len.isEmpty() ? null : len};
    }

    /** 表注释（表名）.xlsx —— 与工具解析输入时的命名同一套，注释为空就只留表名。 */
    public static String fileName(TableOps.Table table) {
        String comment = table.tableComment == null ? "" : table.tableComment.trim();
        String name = table.tableName == null || table.tableName.isEmpty()
                ? "table" : table.tableName;
        String base = ILLEGAL_IN_NAME.matcher(comment.isEmpty() ? name : comment)
                .replaceAll("_");
        return comment.isEmpty() ? base + ".xlsx" : base + "（" + name + "）.xlsx";
    }

    /**
     * 日期目录名收在这里生成：标准件 meta_data_file_年月日、简易件 simple_meta_data_file_年月日。
     * 命名规则只写一处，界面那边就不可能拼错成两层目录。
     *
     * `dirTag` 只用来把不同页的产物分开（「SQL 脚本改名入库」那页传 sql_）：
     * 两页都往同一个日期目录里写时，同名表会互相覆盖，两颗按钮也只好互相等。
     */
    public static String outputDir(String outRoot, boolean withMeta, String day) {
        return outputDir(outRoot, withMeta, day, "");
    }

    public static String outputDir(String outRoot, boolean withMeta, String day, String dirTag) {
        String d = day == null || day.isEmpty()
                ? LocalDateTime.now().format(DAY) : day;
        String tag = dirTag == null ? "" : dirTag;
        return new File(outRoot, tag + (withMeta ? STD_PREFIX : SIMPLE_PREFIX) + "_" + d).getPath();
    }

    /**
     * 元数据 Sheet 的每一行。
     *
     * 可空这一格必须显式写：读取端把"空"当成可空，要声明 NOT NULL 得写「否」。
     * 口径跟建表一致——主键列 NOT NULL，其余可空。普通推断出来的表本来没有这个声明
     * （nullable 是 null），这里写的是"建表时实际会怎么做"，所以回读能稳定还原。
     */
    public static List<Object[]> metaRows(List<Infer.Column> columns) {
        List<Object[]> rows = new ArrayList<Object[]>();
        if (columns == null) {
            return rows;
        }
        for (int i = 0; i < columns.size(); i++) {
            Infer.Column c = columns.get(i);
            String[] tl = splitType(c.type);
            boolean pk = c.primaryKey;
            rows.add(new Object[]{Integer.valueOf(i + 1), nz(c.field), nz(c.comment),
                    tl[0], tl[1], pk ? "是" : null, pk ? "否" : "是"});
        }
        return rows;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 一个数据单元格 -> 可安全写进 xlsx 的值。
     *
     * 日期写成文本：附件里的日期本来就是 '2008-05-19' 这种文本，写文本可以避免
     * 同一份数据在两次读写之间被历元换算挪走一天。
     */
    public static Object cellValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double && Double.isNaN(((Double) v).doubleValue())) {
            return null;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? "是" : null;
        }
        if (v instanceof LocalDateTime) {
            return isoText((LocalDateTime) v);
        }
        return v;
    }

    /** Python 的 datetime.isoformat(sep=" ")：微秒全零不写小数位，否则固定六位。 */
    static String isoText(LocalDateTime dt) {
        String base = String.format("%04d-%02d-%02d %02d:%02d:%02d", dt.getYear(),
                dt.getMonthValue(), dt.getDayOfMonth(), dt.getHour(), dt.getMinute(),
                dt.getSecond());
        int micro = dt.getNano() / 1000;
        return micro == 0 ? base : base + "." + String.format("%06d", micro);
    }

    /** 写一张表。withMeta 为假时只写数据 Sheet（简易件）。 */
    public static String writeTable(Source source, String path, boolean withMeta)
            throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        try {
            XSSFSheet data;
            if (withMeta) {
                XSSFSheet meta = wb.createSheet(META_SHEET);
                writeRow(meta, 0, headerCells());
                List<Object[]> rows = metaRows(source.table.columns);
                for (int i = 0; i < rows.size(); i++) {
                    writeRow(meta, i + 1, rows.get(i));
                }
                data = wb.createSheet(DATA_SHEET);
            } else {
                data = wb.createSheet(DATA_SHEET);
            }
            List<Infer.Column> cols = source.table.columns;
            int[] at = columnMap(source, cols);
            Object[] head = new Object[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                head[i] = nz(cols.get(i).field);
            }
            writeRow(data, 0, head);
            for (int i = 0; i < source.rows.size(); i++) {
                List<Object> row = source.rows.get(i);
                Object[] out = new Object[cols.size()];
                for (int c = 0; c < cols.size(); c++) {
                    int k = at[c];
                    out[c] = k < 0 ? null : cellValue(k < row.size() ? row.get(k) : null);
                }
                writeRow(data, i + 1, out);
            }
            File f = new File(path);
            mkdirs(f.getParentFile());
            FileOutputStream fos = new FileOutputStream(f);
            try {
                wb.write(fos);
            } finally {
                fos.close();
            }
        } finally {
            wb.close();
        }
        return path;
    }

    private static Object[] headerCells() {
        Object[] out = new Object[META_HEADERS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = META_HEADERS[i];
        }
        return out;
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("建不出输出目录：" + dir.getPath());
        }
    }

    /**
     * 每个字段该从数据网格的第几列取值；-1 表示数据里没有这一列，写空。
     *
     * 表头写英文字段名，取数却要先按英文、再按中文注释去找：普通推断出来的表
     * 数据网格的列头是中文注释（field 是拼音），只按 field 取会一列都取不到，
     * 补出来的全空列在回读时会被整列丢弃，两条线就都不是原样还原了。
     */
    private static int[] columnMap(Source source, List<Infer.Column> cols) {
        Map<Object, Integer> index = new LinkedHashMap<Object, Integer>();
        if (source.headers != null) {
            for (int i = 0; i < source.headers.size(); i++) {
                Object h = source.headers.get(i);
                if (!index.containsKey(h)) {
                    index.put(h, Integer.valueOf(i));
                }
            }
        }
        int[] at = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            Integer k = index.get(cols.get(i).field);
            if (k == null) {
                k = index.get(cols.get(i).comment);
            }
            at[i] = k == null ? -1 : k.intValue();
        }
        return at;
    }

    private static void writeRow(XSSFSheet sheet, int rowIndex, Object[] values) {
        XSSFRow row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) {
                continue;       // 留空：读取端把空当成"未声明"，写出 "null" 字符串反而是个值
            }
            XSSFCell cell = row.createCell(i);
            if (v instanceof BigInteger || v instanceof Long || v instanceof Integer) {
                setExactNumber(cell, String.valueOf(v));
            } else if (v instanceof Number) {
                cell.setCellValue(((Number) v).doubleValue());
            } else {
                cell.setCellValue(String.valueOf(v));
            }
        }
    }

    /**
     * 整数按整数写：走 setCellValue(double) 会把 5 落成 "5.0"，
     * 回读时整列的 dtype 就从整型变成浮点，两条线推出来的类型会分叉。
     */
    private static void setExactNumber(XSSFCell cell, String text) {
        cell.setCellValue(0.0);
        cell.getCTCell().setV(text);
    }

    /**
     * 一批表 -> `outRoot/<前缀>_<年月日>/` 下的若干 xlsx。
     *
     * 进度按文件计项；写失败只记错误不中断其余，与导入那条路同一口径。
     * 取消在两张表之间生效：正在写的这一张写完，剩下的不再开工。
     */
    public static Outcome exportTables(List<Source> sources, String outRoot,
                                       boolean withMeta, ImportParse.Sink sink) {
        return exportTables(sources, outRoot, withMeta, sink, "");
    }

    /** 带目录前缀的那一档：SQL 页传 {@link #SQL_DIR_TAG}，与 Excel 页的产物各写各的目录。 */
    public static Outcome exportTables(List<Source> sources, String outRoot, boolean withMeta,
                                       ImportParse.Sink sink, String dirTag) {
        Outcome out = new Outcome();
        out.dir = outputDir(outRoot, withMeta, null, dirTag);
        mkdirs(new File(out.dir));
        out.total = sources == null ? 0 : sources.size();
        if (sink != null) {
            sink.setTotal(out.total);
        }
        if (sources == null) {
            return out;
        }
        for (Source s : sources) {
            if (sink != null && sink.cancelled()) {
                out.cancelled = true;
                break;
            }
            String name = fileName(s.table);
            String path = new File(out.dir, name).getPath();
            if (sink != null) {
                sink.log("- [INFO] 导出 | 正在写 " + name);
            }
            boolean ok = true;
            try {
                writeTable(s, path, withMeta);
            } catch (Exception e) {
                ok = false;
                out.failed++;
                if (sink != null) {
                    sink.logError("x [ERROR] 导出 | " + name + " 写入失败：" + e.getMessage());
                }
            }
            if (sink != null) {
                sink.step(name);
            }
            if (!ok) {
                continue;
            }
            out.written.add(path);
            if (sink != null) {
                sink.log("+ [OK] 导出 | " + name + "（" + s.columnCount() + " 列 / "
                        + s.rowCount() + " 行）");
            }
        }
        return out;
    }

    /**
     * 读回一个 Excel 的内容，供跨线差分与"写出去能原样读回来"的断言用。
     *
     * 只取值不取样式：数字一律按 double 走一遍再判整，与读取层同一口径，
     * 这样 "5" 与 "5.0" 这类字面量差别不会被当成两条线不等价。
     */
    public static List<Dump> dump(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        Workbook wb;
        try {
            wb = path.toLowerCase().endsWith(".xls")
                    ? new HSSFWorkbook(in) : new XSSFWorkbook(in);
        } finally {
            in.close();
        }
        List<Dump> out = new ArrayList<Dump>();
        try {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                Dump d = new Dump(sheet.getSheetName());
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    int width = row == null ? 0 : Math.max(row.getLastCellNum(), 0);
                    List<Object> cells = new ArrayList<Object>();
                    for (int c = 0; c < width; c++) {
                        cells.add(valueOf(row.getCell(c)));
                    }
                    d.cells.add(cells);
                }
                out.add(d);
            }
        } finally {
            wb.close();
        }
        return out;
    }

    private static Object valueOf(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.BLANK || type == CellType.ERROR) {
            return null;
        }
        if (type == CellType.BOOLEAN) {
            return Boolean.valueOf(cell.getBooleanCellValue());
        }
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell)) {
                // 写出侧把日期放成文本；真遇到日期格式说明这份不是本工具生成的，
                // 按原始序列号报出来即可，不在这儿猜历元
                return Double.valueOf(d);
            }
            if (!Double.isInfinite(d) && d == Math.rint(d)) {
                return new BigDecimal(d).toBigInteger();
            }
            return Double.valueOf(d);
        }
        String s = cell.getStringCellValue();
        return s == null || s.isEmpty() ? null : s;
    }
}
