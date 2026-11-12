package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行上限体检：建模出来的列宽必须满足 MySQL/InnoDB 的三条硬上限，过不了线就降级，
 * 降无可降就在建表之前拦下并给出拆分建议。与 Python 的 core/infer.py 同一套判法。
 *
 * 三条上限都是本机 MySQL 8.0.46（16K 页 / ROW_FORMAT=DYNAMIC / innodb_strict_mode=ON）
 * 的实测翻转点，不是抄手册数字：手册只说 65535 与"半页"，具体每列摊多少字节要用
 * 翻转点反推。拟合用的六组边界：VARCHAR(16) 124/125、VARCHAR(32) 62/63、
 * VARCHAR(48) 41/42、VARCHAR(64) 196/197、TEXT 196/197、DECIMAL(18,6) 887/888。
 */
public final class RowFit {

    /** server 级：不含 BLOB 的行最大字节数。 */
    public static final int SERVER_ROW_LIMIT = 65535;
    /** InnoDB 每表列数上限：1017 列过、1018 列报 1117 Too many columns，与类型无关。 */
    public static final int INNODB_MAX_COLUMNS = 1017;
    /** 半页可用额度 = page_size/2 - 这个余量（实测 16K 页落在 8126）。 */
    private static final int HALF_PAGE_SLACK = 66;
    /** 行头与格式开销（NULL 位图另按每 8 列 1 字节加）。 */
    private static final int ROW_FIXED = 30;
    /** 整列走行外时，每列仍留在行内的引用字节（六组边界拟合出 41.1）。 */
    private static final int EXT_BYTES = 41;
    /** 行内字节不超过这个数的变长列不走行外，只能全额留在本地（实测 226 不走、258 走）。 */
    private static final int INLINE_MAX_BYTES = 250;
    /** server 那层每个 TEXT/BLOB 只摊 9~12 字节，按 12 计。 */
    private static final int SERVER_BLOB_BYTES = 12;

    private static final int[] DEC_DIGIT_BYTES = {0, 1, 1, 2, 2, 3, 3, 4, 4};
    /** 定长类型的实际存储字节数：InnoDB 半页那条上限按这个数算，估错就会误判能不能建表。 */
    private static final java.util.Map<String, Long> FIXED_BYTES =
            new java.util.HashMap<String, Long>();
    static {
        String[][] pairs = {
                {"TINYINT", "1"}, {"SMALLINT", "2"}, {"MEDIUMINT", "3"}, {"INT", "4"},
                {"INTEGER", "4"}, {"BIGINT", "8"}, {"NUMERIC", "6"}, {"FLOAT", "4"},
                {"DOUBLE", "8"}, {"REAL", "8"}, {"BIT", "1"}, {"BOOL", "1"},
                {"BOOLEAN", "1"}, {"DATE", "3"}, {"DATETIME", "5"}, {"TIMESTAMP", "4"},
                {"TIME", "3"}, {"YEAR", "1"},
        };
        for (int i = 0; i < pairs.length; i++) {
            FIXED_BYTES.put(pairs[i][0], Long.valueOf(pairs[i][1]));
        }
    }
    private static final Pattern VARCHAR = Pattern.compile("^VARCHAR\\((\\d+)\\)$");
    private static final Pattern CHAR = Pattern.compile("^CHAR\\((\\d+)\\)$");
    private static final Pattern DECIMAL = Pattern.compile("^DECIMAL\\((\\d+)(?:,(\\d+))?\\)$");
    private static final Pattern TEXTISH = Pattern.compile(
            "^(TINYTEXT|MEDIUMTEXT|LONGTEXT|TEXT|TINYBLOB|MEDIUMBLOB|LONGBLOB|BLOB)\\b");

    private RowFit() {
    }

    /** 一次体检的结果：降级清单、拦下理由（可为 null）、最后一版字节账。 */
    public static final class Fit {
        public final List<String[]> conversions = new ArrayList<String[]>();
        public Refuse refuse;
        public Plan plan;
    }

    /** 拦下的那张表：原因、当前账、以及"至少拆成几段"的建议。 */
    public static final class Refuse {
        public String reason;
        public int columns;
        public long server;
        public long local;
        public List<Plan> split = new ArrayList<Plan>();
    }

    /** 一份字节账：两条上限各算一份，再加能不能建表。 */
    public static final class Plan {
        public long server;
        public long local;
        public int columns;
        public long localLimit;
        public boolean ok;
    }

    /** DECIMAL(p,s) 的二进制存储：每 9 位十进制 4 字节，余数查表。 */
    static int decimalBytes(int precision, int scale) {
        int ip = precision - scale;
        return 4 * (ip / 9) + DEC_DIGIT_BYTES[ip % 9]
                + 4 * (scale / 9) + DEC_DIGIT_BYTES[scale % 9];
    }

    static Integer varcharN(String type) {
        if (type == null) {
            return null;
        }
        Matcher m = VARCHAR.matcher(type.trim().toUpperCase());
        return m.matches() ? Integer.valueOf(Integer.parseInt(m.group(1))) : null;
    }

    /** 这一列在两条上限下各占多少字节，返回 {server, 本地}；认不出的写法按 TEXT 估。 */
    public static long[] colBytes(String type) {
        String t = (type == null ? "" : type).trim().toUpperCase();
        Matcher m = VARCHAR.matcher(t);
        if (m.matches()) {
            long inline = 4L * Long.parseLong(m.group(1));
            inline += inline > 255 ? 2 : 1;
            return new long[]{inline, inline <= INLINE_MAX_BYTES ? inline : EXT_BYTES};
        }
        m = CHAR.matcher(t);
        if (m.matches()) {
            long inline = 4L * Long.parseLong(m.group(1));
            return new long[]{inline, inline};
        }
        if (TEXTISH.matcher(t).find()) {
            return new long[]{SERVER_BLOB_BYTES, EXT_BYTES};
        }
        m = DECIMAL.matcher(t);
        if (m.matches()) {
            int n = decimalBytes(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2) == null ? "0" : m.group(2)));
            return new long[]{n, n};
        }
        String base = t.contains("(") ? t.substring(0, t.indexOf('(')).trim() : t;
        Long fixed = FIXED_BYTES.get(base);
        if (fixed != null) {
            return new long[]{fixed.longValue(), fixed.longValue()};
        }
        return new long[]{SERVER_BLOB_BYTES, EXT_BYTES};
    }

    public static Plan rowPlan(List<Infer.Column> cols, int pageSize) {
        Plan p = new Plan();
        long server = 0;
        long local = 0;
        for (Infer.Column c : cols) {
            long[] pair = colBytes(c.type);
            server += pair[0];
            local += pair[1];
        }
        p.columns = cols.size();
        p.localLimit = pageSize / 2 - HALF_PAGE_SLACK;
        p.local = local + ROW_FIXED + ((cols.size() + 7) / 8);
        p.server = server;
        p.ok = server <= SERVER_ROW_LIMIT && p.local <= p.localLimit
                && p.columns <= INNODB_MAX_COLUMNS;
        return p;
    }

    /**
     * 把能降的都降到极限，两条上限还救不救得回来。
     *
     * 救不回来时一列都不改，直接把表交给拆分：把 600 列全退成 TEXT 再告诉用户"还是不行、
     * 得拆"，既白改了类型（页面上一长串降级记录），又让人以为工具尽力了而其实一开始就
     * 知道没救。极限值取「这一列能到的最小占用」：能降的列 server 取 TEXT 的引用字节、
     * 行内取整列走行外的字节；结构定义与主键列不许动，按现值算。
     */
    private static boolean canEverFit(List<Infer.Column> cols, Plan plan, boolean onlyInferred,
                                      java.util.Set<String> keepType) {
        long server = 0;
        long local = 0;
        for (Infer.Column c : cols) {
            long[] pair = colBytes(c.type);
            if (locked(c, onlyInferred, keepType) || varcharN(c.type) == null) {
                server += pair[0];
                local += pair[1];
                continue;
            }
            server += Math.min(pair[0], SERVER_BLOB_BYTES);
            local += Math.min(pair[1], EXT_BYTES);
        }
        long localTotal = local + ROW_FIXED + ((cols.size() + 7) / 8);
        return server <= SERVER_ROW_LIMIT && localTotal <= plan.localLimit;
    }

    /**
     * 这一列能不能动。主键永远不动；{@code onlyInferred} 为真时，只有工具推断出来的列
     * （{@code nullable == null}）能动——人工写的类型是使用者的承诺。
     * 层建模那条线（tab4）的列全是工具按源表结构给的，可以整表降级，传 false。
     */
    private static boolean locked(Infer.Column c, boolean onlyInferred, java.util.Set<String> keepType) {
        return c.primaryKey || (onlyInferred && c.nullable != null)
                || (keepType != null && keepType.contains(c.field));
    }

    /**
     * 体检并就地降级：先把最长的列逐根退成 TEXT，退完还不过再把最长那一档往下降一档。
     * 只动工具推断出来的列——结构定义里人工写的类型是使用者给的承诺，不改它，
     * 那条路走不通时给的是拦下，由调用方在建表之前停住。
     */
    public static Fit fit(List<Infer.Column> cols, int pageSize) {
        return fit(cols, pageSize, true, null);
    }

    /**
     * 同上，但可以放开「只动工具推断的列」这条限制：层建模生成的列（obj/dom）本来就是工具
     * 按源表结构给的，整张表都能按行上限降级，否则那些列 nullable 都是非空、一列也降不了。
     */
    public static Fit fit(List<Infer.Column> cols, int pageSize, boolean onlyInferred) {
        return fit(cols, pageSize, onlyInferred, null);
    }

    /**
     * 再放开一档：{@code keepType} 里的列名保持原类型不动。
     *
     * <p>层建模的治理字段（entity_id、src_code、std_code…）与维度主键每张表都带，它们是结构而不是
     * 业务属性——退成 TEXT 会让人以为「这个标识符可以随便存」，索引与等值匹配也一起失去意义。
     * 所以宁可让业务列多退几列 TEXT。
     */
    public static Fit fit(List<Infer.Column> cols, int pageSize, boolean onlyInferred,
                          java.util.Set<String> keepType) {
        Fit f = new Fit();
        f.plan = rowPlan(cols, pageSize);
        if (f.plan.columns > INNODB_MAX_COLUMNS) {
            f.refuse = refuse(cols, f.plan, "这张表 " + f.plan.columns + " 列，超过 InnoDB 每表列数上限 "
                    + INNODB_MAX_COLUMNS + "（报 1117 Too many columns，与列的类型无关）", pageSize);
            return f;
        }
        int guard = 0;
        while (!f.plan.ok && guard++ < 8192 && canEverFit(cols, f.plan, onlyInferred, keepType)) {
            String[] moved = degradeOnce(cols, f.plan, onlyInferred, keepType);
            if (moved == null) {
                break;
            }
            f.conversions.add(moved);
            f.plan = rowPlan(cols, pageSize);
        }
        if (!f.plan.ok) {
            f.refuse = refuse(cols, f.plan, refuseReason(cols, f.plan), pageSize);
        }
        return f;
    }

    /** 拦下时的完整说法，现场可以照抄：结论一句，拆分建议逐段一句。 */
    public static String notice(Refuse r) {
        if (r == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("行上限体检不通过：").append(r.reason);
        if (!r.split.isEmpty()) {
            sb.append("\n建议拆成 ").append(r.split.size()).append(" 段（每段都单独过线）：");
            for (int i = 0; i < r.split.size(); i++) {
                Plan s = r.split.get(i);
                sb.append("\n    第 ").append(i + 1).append(" 段：").append(s.columns)
                  .append(" 列，行内约 ").append(s.local).append(" 字节");
            }
        }
        return sb.toString();
    }

    /**
     * 这一改到底帮不帮得上忙：只算「当前真正越线那条」省下的字节。
     *
     * 两条上限咬的不是一回事——把 VARCHAR(96) 退成 TEXT，server 省几百字节，但行内一直
     * 是 41（宽列表本来就整列走行外），半页那条一点没松。以前取 max，于是行内卡死的表
     * 会被一路退 TEXT、白改几百列，最后还是得拆。返回 -1 = 对当下没用。
     */
    private static long degradeGain(long[] pair, long[] nu, boolean violServer, boolean violLocal) {
        long saveServer = pair[0] - nu[0];
        long saveLocal = pair[1] - nu[1];
        if (violLocal && saveLocal <= 0) {
            return -1;
        }
        if (violServer && saveServer <= 0) {
            return -1;
        }
        return Math.max(violServer ? saveServer : 0, violLocal ? saveLocal : 0);
    }

    private static String[] degradeOnce(List<Infer.Column> cols, Plan plan, boolean onlyInferred,
                                        java.util.Set<String> keepType) {
        boolean violServer = plan.server > SERVER_ROW_LIMIT;
        boolean violLocal = plan.local > plan.localLimit;
        int best = -1;
        long bestSave = 0;
        for (int i = 0; i < cols.size(); i++) {
            Infer.Column c = cols.get(i);
            // 主键列两条路都不动：TEXT 当不了主键（建表 1170），降长度会截断键值
            if (locked(c, onlyInferred, keepType) || varcharN(c.type) == null) {
                continue;
            }
            long[] pair = colBytes(c.type);
            long save = degradeGain(pair, colBytes("TEXT"), violServer, violLocal);
            if (save > bestSave) {
                bestSave = save;
                best = i;
            }
        }
        if (best >= 0) {
            Infer.Column c = cols.get(best);
            String from = c.type;
            c.type = "TEXT";
            c.typeNote = "这一列本可给 " + from + "，但全表行内字节超过 MySQL 上限（server "
                    + SERVER_ROW_LIMIT + "、InnoDB 半页 " + plan.localLimit + "），退为 TEXT 建列";
            return new String[]{"text", c.field, from, "TEXT"};
        }
        best = -1;
        bestSave = 0;
        String bestTo = null;
        for (int i = 0; i < cols.size(); i++) {
            Infer.Column c = cols.get(i);
            if (locked(c, onlyInferred, keepType)) {
                continue;
            }
            Integer n = varcharN(c.type);
            if (n == null) {
                continue;
            }
            int lower = -1;
            for (int t = Infer.VARCHAR_TIERS.length - 1; t >= 0; t--) {
                if (Infer.VARCHAR_TIERS[t] < n.intValue()) {
                    lower = Infer.VARCHAR_TIERS[t];
                    break;
                }
            }
            if (lower < 0) {
                continue;
            }
            String to = "VARCHAR(" + lower + ")";
            long save = degradeGain(colBytes(c.type), colBytes(to), violServer, violLocal);
            if (save > bestSave) {
                bestSave = save;
                best = i;
                bestTo = to;
            }
        }
        if (best < 0) {
            return null;
        }
        Infer.Column c = cols.get(best);
        String from = c.type;
        c.type = bestTo;
        c.typeNote = "这一列本可给 " + from + "，为过 MySQL 行上限降到 " + bestTo
                + "（server " + SERVER_ROW_LIMIT + "、InnoDB 半页 " + plan.localLimit
                + "）；以后灌进更长的值会被截断报错";
        return new String[]{"tier", c.field, from, bestTo};
    }

    private static String refuseReason(List<Infer.Column> cols, Plan plan) {
        long fixed = 0;
        int external = 0;
        for (Infer.Column c : cols) {
            long[] pair = colBytes(c.type);
            if (pair[1] == EXT_BYTES) {
                external++;
            } else {
                fixed += pair[1];
            }
        }
        if (fixed + ROW_FIXED + ((cols.size() + 7) / 8) > plan.localLimit) {
            return "定长列与短文本列（数值、日期、短 VARCHAR 这一类）行内已占 " + fixed
                    + " 字节，超过 InnoDB 半页 "
                    + plan.localLimit + " —— 退成 TEXT 与降长度都救不回来，只能拆表或删列"
                    + keyNote(cols);
        }
        if (plan.server > SERVER_ROW_LIMIT) {
            return "全表行内字节 " + plan.server + " 仍超过 server 上限 " + SERVER_ROW_LIMIT
                    + "（TEXT/BLOB 也省不出来了）" + keyNote(cols);
        }
        return "需要整列走行外的列有 " + external + " 个，每个至少占行内 " + EXT_BYTES
                + " 字节，半页 " + plan.localLimit + " 字节装不下，只能拆表" + keyNote(cols);
    }

    /** 被保住的主键列要说出来：降级会跳过它们（见 degradeOnce），不说就是"没尽力"。 */
    private static String keyNote(List<Infer.Column> cols) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Infer.Column c : cols) {
            if (!c.primaryKey || varcharN(c.type) == null) {
                continue;
            }
            if (n == 0) {
                sb.append("（主键列 ");
            } else {
                sb.append("、");
            }
            sb.append("`").append(c.field).append("`");
            if (++n >= 3) {
                break;
            }
        }
        if (n == 0) {
            return "";
        }
        return sb.append(" 没动：退成 TEXT 就当不了主键，降长度会截断键值，只能靠拆表）").toString();
    }

    /** 按列序贪心装箱，给出"至少拆成几段、每段几列几字节"。 */
    private static Refuse refuse(List<Infer.Column> cols, Plan plan, String reason, int pageSize) {
        Refuse r = new Refuse();
        r.reason = reason;
        r.columns = plan.columns;
        r.server = plan.server;
        r.local = plan.local;
        List<Infer.Column> cur = new ArrayList<Infer.Column>();
        for (Infer.Column c : cols) {
            List<Infer.Column> trial = new ArrayList<Infer.Column>(cur);
            trial.add(c);
            if (rowPlan(trial, pageSize).ok || cur.isEmpty()) {
                cur = trial;
                continue;
            }
            r.split.add(rowPlan(cur, pageSize));
            cur = new ArrayList<Infer.Column>();
            cur.add(c);
        }
        if (!cur.isEmpty()) {
            r.split.add(rowPlan(cur, pageSize));
        }
        return r;
    }
}
