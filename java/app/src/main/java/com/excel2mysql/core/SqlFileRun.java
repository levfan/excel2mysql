package com.excel2mysql.core;

import java.io.File;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「SQL 执行器」页的脚本文件队列：按清单顺序逐个执行。
 *
 * 手写脚本每个文件一个事务；识别为整库 dump 的文件走另一条通道（不开事务、
 * 逐条提交、出错继续），因为 dump 里的 DDL / LOCK TABLES 会隐式提交，
 * "整文件回滚"在那里兑现不了。差别只在读盘时机——轮到哪个文件才读哪个，
 * 导入一份大 dump 不必先把所有脚本正文按在内存里。进度按语句推进；
 * 读到新文件时总数随之增加（进度条的总数只增不减）。
 */
public final class SqlFileRun {

    /** 一个文件的结局：status 取 ok / fail / skip / cancel，与界面中文状态一一对应。 */
    public static final class FileOutcome {
        public final String path;
        public final String status;
        public final String detail;

        FileOutcome(String path, String status, String detail) {
            this.path = path;
            this.status = status;
            this.detail = detail;
        }
    }

    public static final class Result {
        public int ok;
        public int fail;
        public int skipped;
        public final List<FileOutcome> files = new ArrayList<FileOutcome>();
        public String target = "";
        public boolean cancelled;
        public boolean connFailed;
    }

    private SqlFileRun() {
    }

    public static Result run(List<String> paths, boolean overwrite, Config cfg,
                             ImportParse.Sink sink) {
        Result out = new Result();
        out.target = cfg.database + "@" + cfg.host + ":" + cfg.port;
        int reportedTotal = 0;
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            String fname = baseName(path);
            if (sink.cancelled()) {
                out.files.add(new FileOutcome(path, "cancel", "未执行"));
                continue;
            }
            sink.advance(0, "正在读取第 " + (index + 1) + "/" + paths.size()
                    + " 个脚本：" + fname);
            String text;
            try {
                text = SqlRunner.readScript(new File(path));
            } catch (Exception e) {
                out.fail++;
                out.files.add(new FileOutcome(path, "fail", "读取失败，未执行"));
                sink.logError("[读取失败] " + fname + " 未能读入，已跳过"
                        + LogText.NL + LogText.indentDetail(LogText.describe(e)));
                continue;
            }
            if (overwrite) {
                SqlScript.DropPrep prep = SqlScript.prependDropTables(text);
                text = prep.text;
                sink.log("[覆盖] " + fname + "：" + (prep.tables.isEmpty()
                        ? LogText.COVER_NOTHING_BODY
                        : "执行前先 DROP " + join(prep.tables, "、")));
            }
            boolean dump = SqlScript.isDumpScript(text);
            List<String> stmts = dump
                    ? SqlScript.dumpStatements(text)
                    : SqlScript.executableStatements(text);
            if (dump) {
                sink.log("[dump] " + fname + "：识别为整库 dump，按 dump 方式执行"
                        + "（不开启事务，逐条提交；出错继续，最后汇总）");
            }
            if (stmts.isEmpty()) {
                out.skipped++;
                out.files.add(new FileOutcome(path, "skip", "没有可执行的语句"));
                sink.log("[跳过] " + fname + "：没有可执行的语句");
                continue;
            }
            DbClient db;
            try {
                db = new DbClient(cfg);
            } catch (Exception e) {
                sink.logError("[连接失败] 无法连接到 " + out.target + "（用户 " + cfg.user
                        + "），剩余脚本均未执行" + LogText.NL
                        + LogText.indentDetail(LogText.describe(e)));
                out.files.add(new FileOutcome(path, "cancel", "连接失败，未执行"));
                for (int rest = index + 1; rest < paths.size(); rest++) {
                    out.files.add(new FileOutcome(paths.get(rest), "cancel", "未执行"));
                }
                out.cancelled = true;
                out.connFailed = true;
                return out;
            }
            reportedTotal += stmts.size();
            sink.setTotal(reportedTotal);
            try {
                readAndRun(db, path, fname, stmts, dump, out, sink);
            } finally {
                db.close();
            }
        }
        Set<String> done = new LinkedHashSet<String>();
        for (FileOutcome f : out.files) {
            done.add(f.path);
        }
        for (String path : paths) {                    // 取消或连接中断后没轮到的脚本
            if (!done.contains(path)) {
                out.files.add(new FileOutcome(path, "cancel", "未执行"));
            }
        }
        out.cancelled = sink.cancelled();
        return out;
    }

    private static void readAndRun(DbClient db, String path, String fname, List<String> stmts,
                                   boolean dump, Result out, ImportParse.Sink sink) {
        if (dump) {
            runDump(db, path, fname, stmts, out, sink);
            return;
        }
        final int size = stmts.size();
        final int chunk = Math.max(1, size / 100);     // 每个文件最多约 100 次刷新
        final int[] reported = {0};
        SqlRunner.FileResult r = SqlRunner.runStatements(db, fname, stmts,
                new SqlRunner.StatementHook() {
                    @Override
                    public boolean cancelled() {
                        return sink.cancelled();
                    }

                    @Override
                    public void onStatement(int done, int total) {
                        if (done - reported[0] >= chunk) {
                            reported[0] = done;
                            sink.advance(chunk, fname + " 第 " + done + "/" + total + " 条");
                        }
                    }
                });
        sink.advance(Math.max(0, size - reported[0]), fname + " 收尾");
        if (r.cancelled) {
            // 取消的文件既不算成功也不算失败：汇总里的成功数不能把回滚掉的那份算进去
            out.files.add(new FileOutcome(path, "cancel", "已执行 " + r.executed + "/" + size
                    + " 条，本文件事务已回滚"));
            sink.log("[已取消] " + fname + "：已执行 " + r.executed + "/" + size
                    + " 条，本文件事务已回滚" + LogText.NL + "    " + LogText.ROLLBACK_NOTE);
        } else if (r.ok) {
            out.ok++;
            out.files.add(new FileOutcome(path, "ok", r.executed + " 条语句已提交"));
            sink.log("[提交] " + fname + "：" + r.executed + " 条语句已提交");
        } else {
            out.fail++;
            out.files.add(new FileOutcome(path, "fail", "第 " + r.failedAt + "/" + size
                    + " 条语句报错，本文件事务已回滚"));
            sink.logError("[执行失败] " + fname + " 第 " + r.failedAt + "/" + size
                    + " 条语句报错，目标库 " + out.target + "，本文件事务已回滚"
                    + LogText.NL + LogText.indentDetail(r.error)
                    + LogText.NL + "    出错语句：" + LogText.NL
                    + LogText.formatStatement(r.failedSql)
                    + LogText.NL + "    前面 " + r.executed + " 条语句已随事务回滚。"
                    + LogText.ROLLBACK_NOTE);
        }
    }

    private static final int DUMP_ERROR_LIMIT = 20;

    /**
     * 按整库 dump 的方式执行一个文件：不开事务、每条一提交、出错继续。
     *
     * dump 里的 DDL 与 LOCK TABLES 本来就会隐式提交，"整文件回滚"在这里是个
     * 兑现不了的承诺；逐条提交后，某条失败不会带走前面已经成功的数据，
     * 收尾按"已执行多少条、几条报错"如实汇总。
     */
    private static void runDump(DbClient db, String path, String fname, List<String> stmts,
                                Result out, ImportParse.Sink sink) {
        final int total = stmts.size();
        final int chunk = Math.max(1, total / 100);
        int reported = 0;
        int executed = 0;
        int errors = 0;
        int skippedDrop = 0;
        int overLimit = 0;
        boolean cancelled = false;
        String target = out.target;     // dump 自己切库时只影响本文件后续语句的汇报
        String dbNote = "";
        Statement st = null;
        try {
            st = db.connection().createStatement();
            for (int pos = 1; pos <= total; pos++) {
                if (sink.cancelled()) {
                    cancelled = true;
                    break;
                }
                String sql = stmts.get(pos - 1);
                String nav = SqlScript.dumpSkipReason(sql);
                if (!nav.isEmpty()) {
                    skippedDrop++;
                    sink.log("[跳过] " + fname + " 第 " + pos + "/" + total + " 条："
                            + nav + "（删库语句不执行）");
                    continue;
                }
                try {
                    if (st.execute(sql)) {
                        java.sql.ResultSet rs = st.getResultSet();
                        if (rs != null) {
                            rs.close();     // 结果集不消费掉，连接下一次执行会被挡住
                        }
                    }
                    db.connection().commit();   // 逐条提交：后面失败时，前面的成果不再被带走
                    executed++;
                    String switched = SqlScript.dumpDatabaseSwitch(sql);
                    if (!switched.isEmpty()) {
                        // dump 自己切库，后续语句就落在这个库里；汇总要说的是它，不是界面选的那个
                        int at = target.indexOf('@');
                        target = at < 0 ? switched : switched + target.substring(at);
                        dbNote = "，目标库 " + switched;
                        sink.log("[切库] " + fname + "：目标库换成 " + switched);
                    }
                } catch (SQLException e) {
                    errors++;
                    if (errors <= DUMP_ERROR_LIMIT) {
                        sink.logError("[执行失败] " + fname + " 第 " + pos + "/" + total
                                + " 条语句报错，目标库 " + target + LogText.NL
                                + LogText.indentDetail(LogText.describe(e)) + LogText.NL
                                + "    出错语句：" + LogText.NL
                                + LogText.formatStatement(sql));
                    } else {
                        overLimit++;
                    }
                    try {
                        db.connection().rollback();  // 清掉这一条可能留下的半截状态
                    } catch (SQLException ignored) {
                        // 回滚失败不覆盖原始报错
                    }
                    boolean alive;
                    try {
                        alive = db.connection().isValid(2);
                    } catch (SQLException ignored) {
                        alive = false;
                    }
                    if (!alive) {
                        sink.logError("[连接中断] " + fname + " 第 " + pos + "/" + total
                                + " 条后连接已断开，剩余语句未执行");
                        break;
                    }
                }
                if (pos - reported >= chunk) {
                    reported = pos;
                    sink.advance(chunk, fname + " 第 " + pos + "/" + total + " 条");
                }
            }
        } catch (SQLException e) {
            errors++;
            sink.logError("[执行失败] " + fname + " 执行异常，目标库 " + out.target + LogText.NL
                    + LogText.indentDetail(LogText.describe(e)));
        } finally {
            if (st != null) {
                try {
                    st.close();
                } catch (SQLException ignored) {
                    // 收尾关闭失败不影响结果
                }
            }
        }
        sink.advance(Math.max(0, total - reported), fname + " 收尾");
        if (overLimit > 0) {
            sink.logError("[执行失败] " + fname + " 另有 " + overLimit + " 条语句报错未逐条列出");
        }
        if (errors > 0) {
            out.fail++;
            out.files.add(new FileOutcome(path, "fail", "已执行 " + executed + "/" + total
                    + " 条，" + errors + " 条语句报错（逐条提交，已成功的已落库）"));
            sink.logError("[dump 完成] " + fname + "：已执行 " + executed + "/" + total
                    + " 条，" + errors + " 条语句报错；逐条提交，已成功的已落库");
        } else if (cancelled) {
            out.files.add(new FileOutcome(path, "cancel", "已执行 " + executed + "/" + total
                    + " 条（逐条提交，已执行的已落库）"));
            sink.log("[已取消] " + fname + "：已执行 " + executed + "/" + total
                    + " 条；逐条提交，已执行的已落库");
        } else {
            out.ok++;
            String note = skippedDrop > 0 ? "，跳过 " + skippedDrop + " 条删库语句" : "";
            out.files.add(new FileOutcome(path, "ok",
                    executed + " 条语句已提交" + dbNote + note));
            sink.log("[提交] " + fname + "：" + executed + " 条语句已提交" + dbNote + note);
        }
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
