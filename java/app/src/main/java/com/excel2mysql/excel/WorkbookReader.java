package com.excel2mysql.excel;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.enums.CellDataTypeEnum;
import cn.idev.excel.enums.ReadDefaultReturnEnum;
import cn.idev.excel.metadata.data.DataFormatData;
import cn.idev.excel.metadata.data.ReadCellData;
import cn.idev.excel.read.listener.ReadListener;
import cn.idev.excel.read.metadata.ReadSheet;
import cn.idev.excel.util.DateUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用 FastExcel 把工作簿读成"Python 语义的值网格"。
 *
 * 目标不是读出来，而是和 pandas.read_excel(dtype=object) 逐格同值，所以要复刻三条：
 * 数字单元格只要能精确表示成整数就存整数（100.0 是 int，1e20 是大整数），
 * 空单元格存成 null，带日期格式的数值单元格存成日期对象而不是数字。
 */
public final class WorkbookReader {

    public static final class Sheet {
        public final String name;
        public final List<List<Object>> rows;

        public Sheet(String name, List<List<Object>> rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    private WorkbookReader() {
    }

    static {
        // POI 默认压缩比下限 0.01 会把"高重复列"的正常业务表判成 zip bomb 直接拒读，
        // 而 Python 线（openpyxl/pandas）没有这道检查、照样读得进来。
        // 放到 0.001 是折中：既让实测 0.0095 的夹具过，又给极端膨胀留一道闸。
        // 已知残留差异：压缩比低于 0.001 的文件 Java 会拒、Python 会读，这类文件
        // 现实中出现在几十万字重复长文本的表上，P3 交付说明里要写清。
        org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.001d);
    }

    public static List<Sheet> read(String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException("文件不存在：" + path);
        }
        int sheetCount = countSheets(file);
        List<Sheet> sheets = new ArrayList<Sheet>();
        for (int i = 0; i < sheetCount; i++) {
            Collector collector = new Collector();
            InputStream in = null;
            try {
                in = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                throw new IllegalArgumentException("打不开文件：" + path, e);
            }
            try {
                // headRowNumber(0)：表头也当数据吐出来。表头的改名与补空是 pandas 的规则，
                // 交给 FastExcel 解析就变成它的语义，而不是我们要对撞的那个语义。
                //
                // 一张 Sheet 一次读取：.sheet() 不带参数只读第一张，
                // 而 pandas 的 read_excel(sheet_name=None) 是全量。结构定义模式的
                // 数据 Sheet 往往是第二张，漏读会让整批数据静默变成 0 行——
                // DDL 看着完全正确，写库却一张不写。
                FastExcel.read(in, collector)
                        .readDefaultReturn(ReadDefaultReturnEnum.READ_CELL_DATA)
                        .ignoreEmptyRow(Boolean.FALSE)
                        .headRowNumber(0)
                        // 关掉 FastExcel 的默认裁剪：它把"    "裁成空再变成 null，
                        // 一整列纯空白的数据因此在 dropna 那步被删掉，DDL 少一个字段；
                        // pandas/openpyxl 那边"    " 是有效值，列会留下。
                        .autoTrim(Boolean.FALSE)
                        .autoCloseStream(Boolean.FALSE)
                        .sheet(Integer.valueOf(i))
                        .doRead();
            } finally {
                close(in);
            }
            List<Sheet> got = collector.finish();
            if (!got.isEmpty()) {
                sheets.add(got.get(0));
            } else {
                sheets.add(new Sheet(nameOf(file, i), new ArrayList<List<Object>>()));
            }
        }
        return sheets;
    }

    /** 空 Sheet 也要占位：Sheet 的下标和数量参与"取第一张还是取某一张"的判断。 */
    private static String nameOf(File file, int index) {
        List<String> names = sheetNames(file);
        return index < names.size() ? names.get(index) : ("sheet" + (index + 1));
    }

    private static int countSheets(File file) {
        return sheetNames(file).size();
    }

    private static List<String> sheetNames(File file) {
        List<String> names = new ArrayList<String>();
        InputStream in = null;
        cn.idev.excel.ExcelReader reader = null;
        try {
            in = new FileInputStream(file);
            reader = FastExcel.read(in).autoCloseStream(Boolean.FALSE).build();
            for (cn.idev.excel.read.metadata.ReadSheet meta : reader.excelExecutor().sheetList()) {
                names.add(meta.getSheetName());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("读取工作簿失败：" + file.getName() + " — " + e, e);
        } finally {
            if (reader != null) {
                try {
                    reader.finish();
                } catch (Exception ignored) {
                    // 收尾关闭失败不影响已经拿到的 Sheet 清单
                }
            }
            close(in);
        }
        return names;
    }

    private static void close(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 读取已失败时别用关闭异常盖住真正的原因
        }
    }

    /** 按 Sheet 名分桶；FastExcel 会按 workbook 顺序回调，所以顺序天然保留。 */
    private static final class Collector implements ReadListener<Map<Integer, Object>> {
        private final List<Sheet> sheets = new ArrayList<Sheet>();
        private String currentName;
        private List<List<Object>> currentRows;

        public void invoke(Map<Integer, Object> row, AnalysisContext context) {
            String name = context.readSheetHolder().getSheetName();
            if (currentRows == null || !name.equals(currentName)) {
                currentName = name;
                currentRows = new ArrayList<List<Object>>();
                sheets.add(new Sheet(name, currentRows));
            }
            int width = 0;
            for (Integer k : row.keySet()) {
                width = Math.max(width, k.intValue() + 1);
            }
            List<Object> cells = new ArrayList<Object>();
            for (int i = 0; i < width; i++) {
                cells.add(toValue(row.get(Integer.valueOf(i))));
            }
            currentRows.add(cells);
        }

        public void doAfterAllAnalysed(AnalysisContext context) {
            // 每个 Sheet 结束时不封箱：sheets 里存的已经是同一个 List 引用
        }

        List<Sheet> finish() {
            return sheets;
        }
    }

    private static Object toValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof ReadCellData)) {
            return raw;
        }
        ReadCellData<?> cell = (ReadCellData<?>) raw;
        CellDataTypeEnum type = cell.getType();
        if (type == null) {
            return null;
        }
        switch (type) {
            case EMPTY:
            case ERROR:
                return null;
            case STRING:
            case DIRECT_STRING:
                return cell.getStringValue();
            case BOOLEAN:
                return cell.getBooleanValue();
            case DATE:
                Object dateValue = cell.getData();
                return dateValue == null ? cell.getStringValue() : dateValue;
            case NUMBER:
                return number(cell);
            default:
                return cell.getStringValue();
        }
    }

    private static Object number(ReadCellData<?> cell) {
        BigDecimal value = cell.getOriginalNumberValue() == null
                ? cell.getNumberValue() : cell.getOriginalNumberValue();
        if (value == null) {
            return null;
        }
        DataFormatData fmt = cell.getDataFormatData();
        double d = value.doubleValue();
        if (fmt != null && fmt.getIndex() != null
                && DateUtils.isADateFormat(fmt.getIndex(), fmt.getFormat())
                && DateUtils.isValidExcelDate(d)) {
            LocalDateTime dt = DateUtils.getLocalDateTime(d, false);
            // openpyxl 把纯日期也读成 datetime.time 全零的对象，
            // pandas 那边保留的是 datetime.datetime；这里统一给调用方两种类型自己判
            return dt;
        }
        // openpyxl 拿到的是**双精度浮点**，pandas 再对它做 int(v)==v 的判断，
        // 所以 1e25 读出来是那个 double 的精确整数值 10000000000000000905969664，
        // 不是字面量的 10^25。必须经过 double，否则大整数列会和 Python 线给出不同的值。
        if (!Double.isInfinite(d) && d == Math.rint(d)) {
            return new BigDecimal(d).toBigInteger();
        }
        return Double.valueOf(d);
    }
}
