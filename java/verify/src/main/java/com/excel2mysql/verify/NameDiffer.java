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
        // 版本号括号没有单独放夹具（会带动整份金标准的二进制翻新），直接喂合成文件名：
        // 这里跑的就是 TableBuilder 与界面共用的 parseTableNameAndComment
        bad += checkVersionBracket(names, shown);
        // 版本号（2.0 → v2）与 ZIP 目录拼接这两条新规则金标准里没有夹具，
        // 拿合成文件名在这里撞：数字与 Python pack_test S8c/S8d 同源
        bad += checkVersionToken(names, shown);
        bad += checkArchiveDirs(names, shown);
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
                    // 撞的是"一张表按顺序命名下来"的结果，不是单列的转写：表头注释剥完之后
                    // 「发布日期（yyyy-MM-dd）」与「发布日期」同名，去重必须走同一份实现
                    Map<String, Integer> seen = Names.newDedupState();
                    for (Object o : cols) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> c = (Map<String, Object>) o;
                        String field = (String) c.get("field");
                        String cn = (String) c.get("comment");
                        fields++;
                        String actual = Names.dedupField(seen, names.colToField(cn));
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
                + " 个 | 合并拒绝 " + rejected + " 个 | 版本号括号合成 5 例"
                + " | 版本号改写与目录拼接合成 11 例"
                + " | 不一致 " + bad + " 处");
        for (String s : shown) {
            System.out.println(s);
        }
        if (bad > 0) {
            System.out.println("x [ERROR] NAME-DIFF | 表名或字段名与 Python 线不等价");
            System.exit(1);
        }
        System.out.println("+ [OK] NAME-DIFF | 表名与字段名逐条一致");
    }

    /**
     * 括号内版本号不是表名：V2/v2/V2_1 回退成完整文件名拼音并保留版本段；
     * order2 这种真名字仍直接采用。最后一例验证首字母+大写叠加为 DDBV2。
     */
    private static int checkVersionBracket(Names names, List<String> shown) {
        int bad = 0;
        String[][] cases = {
            {"订单表（V2）.xlsx", "ding_dan_biao_v2", "订单表（V2）"},
            {"订单表（v2）.xlsx", "ding_dan_biao_v2", "订单表（v2）"},
            {"订单表（V2_1）.xlsx", "ding_dan_biao_v2_1", "订单表（V2_1）"},
            {"订单表（order2）.xlsx", "order2", "订单表"},
        };
        for (String[] c : cases) {
            String[] got = names.parseTableNameAndComment(c[0]);
            if (!c[1].equals(got[0]) || !c[2].equals(got[1])) {
                bad++;
                if (shown.size() < 12) {
                    shown.add("     版本号括号[" + ascii(c[0]) + "]\n       期望=" + ascii(c[1])
                            + " / " + ascii(c[2]) + "\n       实际=" + ascii(got[0])
                            + " / " + ascii(got[1]));
                }
            }
        }
        try {
            names.setInitialsOnly(true);
            names.setUpperOnly(true);
            String[] got = names.parseTableNameAndComment("订单表（V2）.xlsx");
            if (!"DDBV2".equals(got[0]) || !"订单表（V2）".equals(got[1])) {
                bad++;
                if (shown.size() < 12) {
                    shown.add("     版本号括号[首字母+大写]\n       期望=DDBV2 / "
                            + ascii("订单表（V2）") + "\n       实际=" + ascii(got[0])
                            + " / " + ascii(got[1]));
                }
            }
        } finally {
            names.setInitialsOnly(false);
            names.setUpperOnly(false);
        }
        return bad;
    }

    /**
     * 表名里的版本号：`2.0` 一律写成 `v2`，`3.5` 写成 `v3_5`。
     *
     * 期望值是从 Python 侧同一批文件名抄下来的（`pack_test` S8c 那一组），两边各存一份
     * 数字，谁单边改了都会在这里红。日期（`2026.10.1`）与三位以上主版本不许被这条规则吃掉，
     * 所以它们也在这一组里——挡的就是"把日期当版本号"那种改法。
     */
    private static int checkVersionToken(Names names, List<String> shown) {
        int bad = 0;
        String[][] cases = {
            {"manual_orders2.0.xlsx", "manual_orders_v2", "manual_orders2.0"},
            {"装备实力2.0.xlsx", "zhuang_bei_shi_li_v2", "装备实力2.0"},
            {"系统 2.0 数据.xlsx", "xi_tong_v2_shu_ju", "系统 2.0 数据"},
            {"v3.5beta.xlsx", "v3_5_beta", "v3.5beta"},
            {"表2.10.xlsx", "biao_v2_10", "表2.10"},
            {"2.0.xlsx", "v2", "2.0"},
            {"2026.10.1.xlsx", "t_2026_10_1", "2026.10.1"},
        };
        for (String[] c : cases) {
            bad += expect(names, shown, "版本号[" + c[0] + "]", c[0], null, c[1], c[2]);
        }
        return bad;
    }

    /**
     * ZIP 里的表名从包根一路拼到文件名，目录之间用下划线；表注释用 `/` 留着层级。
     *
     * 与 Python `parse_table_name_and_comment(..., dirs=...)` 逐值对齐；
     * 最后一条是首字母档（图形版默认勾着「只取首字母」），别拿全拼当期望。
     */
    private static int checkArchiveDirs(Names names, List<String> shown) {
        int bad = 0;
        bad += expect(names, shown, "目录拼接[北京/水库]", "水库基础信息（dim_reservoir_info）.xlsx",
                new String[]{"北京", "水库"}, "bei_jing_shui_ku_dim_reservoir_info",
                "北京/水库/水库基础信息");
        bad += expect(names, shown, "目录拼接[目录名带版本号]",
                "装备2.0.xlsx", new String[]{"数据治理2.0"},
                "shu_ju_zhi_li_v2_zhuang_bei_v2", "数据治理2.0/装备2.0");
        // 不在包里的同名文件不受影响：没有目录就还是叶子名（括号里那截英文优先），与从前一致
        bad += expect(names, shown, "目录拼接[无目录]",
                "水库基础信息（dim_reservoir_info）.xlsx", null,
                "dim_reservoir_info", "水库基础信息");
        try {
            names.setInitialsOnly(true);
            bad += expect(names, shown, "目录拼接[首字母]",
                    "河道水文站.xlsx", new String[]{"北京"}, "bj_hdswz", "北京/河道水文站");
        } finally {
            names.setInitialsOnly(false);
        }
        return bad;
    }

    /** 一条比对：给定文件名与目录，看表名与表注释是否都等于期望。 */
    private static int expect(Names names, List<String> shown, String label, String file,
                              String[] dirs, String wantName, String wantComment) {
        String[] got = dirs == null
                ? names.parseTableNameAndComment(file)
                : names.parseTableNameAndComment(file, java.util.Arrays.asList(dirs));
        if (wantName.equals(got[0]) && wantComment.equals(got[1])) {
            return 0;
        }
        if (shown.size() < 12) {
            shown.add("     " + label + "\n       期望=" + ascii(wantName)
                    + " / " + ascii(wantComment) + "\n       实际=" + ascii(got[0])
                    + " / " + ascii(got[1]));
        }
        return 1;
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
