package com.excel2mysql.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「SQL 执行器」页的脚本文件队列：按清单顺序逐个执行，每个文件一个事务。
 *
 * 与 SQL 页 runScripts 的事务语义、日志文案同一套；差别只在读盘时机——轮到哪个文件
 * 才读哪个，导入一份大 dump 不必先把所有脚本正文按在内存里。进度按语句推进；
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
                        ? "脚本里没有以 CREATE TABLE 开头的语句，没有表需要先删除"
                        : "执行前先 DROP " + join(prep.tables, "、")));
            }
            List<String> stmts = SqlScript.executableStatements(text);
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
                readAndRun(db, path, fname, stmts, out, sink);
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
                                   Result out, ImportParse.Sink sink) {
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
