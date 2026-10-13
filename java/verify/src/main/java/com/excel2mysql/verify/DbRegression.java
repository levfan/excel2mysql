package com.excel2mysql.verify;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 真库逐值回归：在真实 MySQL 上把夹具建表、导入、再逐格读回来比对。
 *
 * 差分器证明的是"Java 与 Python 算出同一个 DDL/同一批转换值"，
 * 这个证明的是"MySQL 8 真的接受这些 DDL，并且存进去的值原样读得回来"
 * ——行大小、utf8mb4 排序规则、DECIMAL 精度、注释转义这些只有服务端说了算。
 *
 * 只动 e2mjavaprobe 前缀的探针表；跑之前记下库里的表清单，跑完必须一张不多一张不少。
 * 口令只走环境变量 E2M_PASS，不落任何文件。
 */
public final class DbRegression {

    private static final String PROBE_PREFIX = "e2mjavaprobe";

    public static void main(String[] args) throws Exception {
        Config cfg = Config.discover(new ArrayList<String>());
        if (!cfg.hasDatabase()) {
            System.out.println("x [ERROR] DB-REG | 没指定目标库（E2M_DB），拒绝在未知库上建表");
            System.exit(2);
        }
        String fixtureDir = args.length > 0 ? args[0] : "java/verify/fixtures";

        Set<String> before;
        List<TableBuilder.Result> built = new ArrayList<TableBuilder.Result>();
        Names names = new Names(PinyinLoader.get());
        TableBuilder builder = new TableBuilder(new Infer(names), names);

        File dir = new File(fixtureDir);
        File[] files = dir.listFiles();
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().toLowerCase().endsWith(".xlsx")) {
                continue;
            }
            TableBuilder.Result r = builder.build(f.getPath());
            built.add(r);
        }
        System.out.println("- [INFO] DB-REG | 目标 " + cfg);

        DbClient db = new DbClient(cfg);
        int bad = 0;
        int checkedCells = 0;
        int checkedRows = 0;
        List<String> created = new ArrayList<String>();
        try {
            before = tables(db.connection());
            for (String t : before) {
                if (t.startsWith(PROBE_PREFIX)) {
                    System.out.println("x [ERROR] DB-REG | 库里已有同名探针表，先清掉再跑：" + t);
                    System.exit(4);
                }
            }
            int idx = 0;
            for (TableBuilder.Result r : built) {
                idx++;
                String probe = PROBE_PREFIX + idx;
                r.table.tableName = probe;
                String ddl = Ddl.buildDdl(probe, r.table.tableComment, r.table.columns, true);
                try {
                    db.createTable(ddl);
                    created.add(probe);
                } catch (Exception e) {
                    bad++;
                    System.out.println("x [ERROR] DDL | " + baseName(r.table.filepath)
                            + " 服务端拒绝：" + e.getMessage());
                    continue;
                }
                long written;
                try {
                    final List<String> warns = new ArrayList<String>();
                    written = db.insertData(probe, r.table.columns, r.headers, r.rows,
                            new DbClient.WarnCallback() {
                                public void onWarn(String m) {
                                    warns.add(m);
                                }
                            }, null);
                    for (String w : warns) {
                        System.out.println("  " + w);
                    }
                } catch (Exception e) {
                    bad++;
                    System.out.println("x [ERROR] INSERT | " + baseName(r.table.filepath)
                            + " 写入失败：" + e.getMessage());
                    continue;
                }
                if (written != r.rows.size()) {
                    bad++;
                    System.out.println("x [ERROR] COUNT | " + baseName(r.table.filepath)
                            + " 期望 " + r.rows.size() + " 行，服务端回执 " + written);
                }
                checkedRows += r.rows.size();

                // 读回来逐格比：期望值是 Ddl.convert 的结果（转换本身已被 SqlDiffer
                // 拿 Python 的 _convert 单独对撞过），这里比的是"存进去又原样出来"
                String orderCol = null;
                for (Infer.Column c : r.table.columns) {
                    if (c.primaryKey) {
                        orderCol = c.field;
                        break;
                    }
                }
                if (orderCol == null) {
                    orderCol = r.table.columns.get(0).field;
                }
                List<String> cols = new ArrayList<String>();
                for (Infer.Column c : r.table.columns) {
                    cols.add("`" + c.field + "`");
                }
                String sql = "SELECT " + join(cols, ", ") + " FROM `" + probe + "` ORDER BY `"
                        + orderCol + "` ASC, 1 ASC";
                TreeMap<Integer, List<String>> gotRows = new TreeMap<Integer, List<String>>();
                Statement st = db.connection().createStatement();
                try {
                    ResultSet rs = st.executeQuery(sql);
                    int rowNo = 0;
                    while (rs.next()) {
                        List<String> vals = new ArrayList<String>();
                        for (int j = 1; j <= cols.size(); j++) {
                            vals.add(rs.getString(j));
                        }
                        gotRows.put(Integer.valueOf(rowNo++), vals);
                    }
                    rs.close();
                } finally {
                    st.close();
                }
                List<List<Object>> expected = sortForCompare(r, orderCol);
                if (gotRows.size() != expected.size()) {
                    bad++;
                    System.out.println("x [ERROR] READBACK | " + baseName(r.table.filepath)
                            + " 行数不符 期望 " + expected.size() + " 实际 " + gotRows.size());
                    continue;
                }
                for (int i = 0; i < expected.size(); i++) {
                    List<Object> expRow = expected.get(i);
                    List<String> actRow = gotRows.get(Integer.valueOf(i));
                    for (int j = 0; j < expRow.size(); j++) {
                        checkedCells++;
                        Object exp = Ddl.convert(expRow.get(j),
                                r.table.columns.get(j).type).value;
                        String act = j < actRow.size() ? actRow.get(j) : null;
                        if (!sameValue(exp, act)) {
                            bad++;
                            if (bad <= 12) {
                                System.out.println("x [ERROR] VALUE | " + probe
                                        + " 第 " + (i + 1) + " 行 第 " + (j + 1) + " 列 期望 "
                                        + String.valueOf(exp) + " 实际 " + act);
                            }
                        }
                    }
                }
                System.out.println("- [INFO] TABLE | " + baseName(r.table.filepath)
                        + " -> " + probe + " | " + r.table.columns.size() + " 列 "
                        + r.rows.size() + " 行 | 模式 " + r.table.source);
            }
        } finally {
            for (String t : created) {
                Statement st = db.connection().createStatement();
                try {
                    st.execute("DROP TABLE IF EXISTS `" + t + "`");
                    db.connection().commit();
                } finally {
                    st.close();
                }
            }
            db.close();
        }

        Set<String> after = tablesViaNewConnection(cfg);
        if (!after.equals(before)) {
            bad++;
            List<String> diff = new ArrayList<String>(after);
            diff.removeAll(before);
            List<String> gone = new ArrayList<String>(before);
            gone.removeAll(after);
            System.out.println("x [ERROR] SIDE-EFFECT | 跑完库里表清单变了 多：" + diff
                    + " 少：" + gone);
        }
        System.out.println("- [INFO] DB-REG | 逐格比对 " + checkedCells + " 个值 / "
                + checkedRows + " 行 | 不一致 " + bad + " 项");
        if (bad > 0) {
            System.out.println("x [ERROR] DB-REG | 真库回归未通过");
            System.exit(1);
        }
        System.out.println("+ [OK] DB-REG | MySQL 接受全部 DDL，写入后逐格读回一致，探针表零残留");
    }

    /** 读回来按 PK 升序，期望值也要按同一口径排，否则比的是两套顺序。 */
    private static List<List<Object>> sortForCompare(TableBuilder.Result r, String orderCol) {
        int at = -1;
        for (int i = 0; i < r.table.columns.size(); i++) {
            if (r.table.columns.get(i).field.equals(orderCol)) {
                at = i;
                break;
            }
        }
        final int key = at;
        List<List<Object>> copy = new ArrayList<List<Object>>(r.rows);
        final List<Infer.Column> cols = r.table.columns;
        java.util.Collections.sort(copy, new java.util.Comparator<List<Object>>() {
            public int compare(List<Object> a, List<Object> b) {
                Object va = key < a.size() ? a.get(key) : null;
                Object vb = key < b.size() ? b.get(key) : null;
                String sa = va == null ? "" : String.valueOf(va);
                String sb = vb == null ? "" : String.valueOf(vb);
                BigDecimal na = numeric(sa);
                BigDecimal nb = numeric(sb);
                if (na != null && nb != null) {
                    return na.compareTo(nb);
                }
                return sa.compareTo(sb);
            }
        });
        return copy;
    }

    private static BigDecimal numeric(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sameValue(Object exp, String act) {
        if (exp == null) {
            return act == null;
        }
        if (act == null) {
            return false;
        }
        BigDecimal a = numeric(String.valueOf(exp));
        BigDecimal b = numeric(act);
        if (a != null && b != null) {
            return a.compareTo(b) == 0;
        }
        String sa = String.valueOf(exp).trim();
        String sb = act.trim();
        // 日期时间在两侧都会带/不带秒，归一到同一形式再比
        if (sa.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return sb.startsWith(sa.substring(0, Math.min(sa.length(), 10)))
                    && sb.replace(".0", "").startsWith(sa.replace("T", " ").substring(0,
                        Math.min(sa.replace("T", " ").length(), 19)));
        }
        return sa.equals(sb);
    }

    private static Set<String> tablesViaNewConnection(Config cfg) throws Exception {
        DbClient db = new DbClient(cfg);
        try {
            return tables(db.connection());
        } finally {
            db.close();
        }
    }

    private static Set<String> tables(Connection c) throws Exception {
        Set<String> out = new LinkedHashSet<String>();
        Statement st = c.createStatement();
        try {
            ResultSet rs = st.executeQuery("SHOW TABLES");
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            rs.close();
        } finally {
            st.close();
        }
        return out;
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

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }
}
