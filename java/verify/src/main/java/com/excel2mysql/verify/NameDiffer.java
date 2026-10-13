package com.excel2mysql.verify;

import com.excel2mysql.core.Names;
import com.excel2mysql.pinyin.PinyinConverter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 拿 Excel 金标准里的表名与字段名撞 Java 命名实现。
 * 只看命名，不看类型推断——那需要先把 Excel 读进来，分开撞才知道错的是哪一层。
 */
public final class NameDiffer {

    public static void main(String[] args) throws IOException {
        String golden = args.length > 0 ? args[0]
                : "java/verify/golden/excel.jsonl.gz";
        // 合并区那条记录要真打开文件验"Java 也拒绝"，所以这里也得拿夹具目录
        String fixtureDir = args.length > 1 ? args[1] : "java/verify/fixtures";
        Names names = new Names(PinyinConverter.loadFromResources());
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(golden)), Charset.forName("UTF-8")));
        int files = 0;
        int rejected = 0;
        int fields = 0;
        int structured = 0;
        int nanFields = 0;
        int bad = 0;
        List<String> shown = new ArrayList<String>();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                files++;
                Map<String, Object> rec = MiniJson.parseObject(line);
                String file = (String) rec.get("file");
                @SuppressWarnings("unchecked")
                Map<String, Object> def = (Map<String, Object>) rec.get("def");
                String baseTable = (String) def.get("base_table_name");
                String table = (String) def.get("table_name");
                String comment = (String) def.get("table_comment");
                String source = (String) def.get("source");
                if ("merged".equals(source)) {
                    // 含合并区的文件在 Python 侧连表定义都没有（build_table_def 直接拒），
                    // 拿 null 去比推导出的表名必然假红；这条记录要撞的是"Java 也拒绝"
                    rejected++;
                    try {
                        com.excel2mysql.core.MergeCheck.ensureNoMergedCells(
                                fixtureDir + "/" + file);
                        bad++;
                        if (shown.size() < 12) {
                            shown.add("     漏检[" + file + "] 含合并区却没拒绝");
                        }
                    } catch (com.excel2mysql.core.MergeCheck.MergedCellsException expected) {
                        // 正是期望行为
                    }
                    continue;
                }
                String[] got = names.parseTableNameAndComment(file);
                if (!got[0].equals(baseTable) || !got[1].equals(comment)) {
                    bad++;
                    if (shown.size() < 12) {
                        shown.add("     表名[" + file + "]\n       期望=" + ascii(baseTable)
                                + " / " + ascii(comment)
                                + "\n       实际=" + ascii(got[0])
                                + " / " + ascii(got[1]));
                    }
                }
                @SuppressWarnings("unchecked")
                List<Object> cols = (List<Object>) def.get("columns");
                if ("normal".equals(source)) {
                    // 只有第 1 类普通表的字段名是拼音推出来的，这才是要撞的契约。
                    // 第 2 类结构定义表的字段名是用户在元数据 Sheet 里手写的，
                    // 拿拼音实现去比它必然假红，比的是"手写值 vs 推出来的值"两回事。
                    for (Object o : cols) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> c = (Map<String, Object>) o;
                        String field = (String) c.get("field");
                        String cn = (String) c.get("comment");
                        fields++;
                        String actual = names.colToField(cn);
                        if (!actual.equals(field)) {
                            bad++;
                            if (shown.size() < 12) {
                                shown.add("     字段[" + ascii(cn) + "]\n       期望="
                                        + ascii(field) + " 实际=" + ascii(actual));
                            }
                        }
                    }
                } else if ("structured".equals(source)) {
                    structured++;
                    // 结构定义表的字段名是用户手写的，命名规则撞不到它。但有一条要盯住：
                    // 元数据里"字段名"单元格是空白时，Python 侧 str(NaN or "") 得到字面量 "nan"，
                    // 于是真会生成一个叫 nan 的列。这是现网行为，Java 读表格那边必须原样复刻
                    // （String.valueOf(Double.NaN) 是 "NaN"，大写 N，不处理就会和 Python 线分叉）。
                    for (Object o : cols) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> c = (Map<String, Object>) o;
                        if ("nan".equals(String.valueOf(c.get("field")))) {
                            nanFields++;
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
        System.out.println("- [INFO] NAME-DIFF | 文件 " + files + " 个（结构定义 " + structured
                + "，其中 nan 字段 " + nanFields + " 个）| 拼音字段 " + fields
                + " 个 | 合并拒绝 " + rejected + " 个 | 不一致 " + bad + " 处");
        for (String s : shown) {
            System.out.println(s);
        }
        if (bad > 0) {
            System.out.println("x [ERROR] NAME-DIFF | 表名或字段名与 Python 线不等价");
            System.exit(1);
        }
        System.out.println("+ [OK] NAME-DIFF | 表名与字段名逐条一致");
    }

    /** 差异报表里的中文要转义，否则在 GBK 控制台上是豆腐块，取证时读不出内容。 */
    private static String ascii(Object value) {
        StringBuilder sb = new StringBuilder();
        for (char ch : String.valueOf(value).toCharArray()) {
            if (ch >= 0x20 && ch < 0x7F) {
                sb.append(ch == '"' ? "\\\"" : String.valueOf(ch));
            } else {
                sb.append(String.format("\\u%04x", Integer.valueOf(ch)));
            }
        }
        return sb.toString();
    }
}
