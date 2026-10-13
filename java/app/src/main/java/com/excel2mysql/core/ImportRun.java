package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一批表定义写进目标库：每张表 1 项 + 每个数据行 1 项计进度，
 * 建表语句全文与逐表结果都写日志。对应 main.py:696-778 的 ImportWorker.run。
 *
 * 与界面无关：进度与日志都走 ImportParse.Sink，所以自检能原地跑这条链，
 * 界面上挂的也是同一个函数。文案单列成静态方法，逐字对齐 Python 侧，
 * 自检直接断言这些句子，不等真连上一个库。
 */
public final class ImportRun {

    /** 一张表的处理结果。 */
    public static final class Row {
        public final String tableName;
        public final String filepath;
        public boolean ok;
        public long rows;
        public String message = "";

        Row(String tableName, String filepath) {
            this.tableName = tableName;
            this.filepath = filepath;
        }
    }

    private ImportRun() {
    }

    /** 目标库的展示串：没选库时只说 host:port，与 Python 侧同一处理。 */
    public static String targetOf(Config cfg) {
        return cfg.hasDatabase()
                ? cfg.database + "@" + cfg.host + ":" + cfg.port
                : cfg.host + ":" + cfg.port;
    }

    public static int unitsOf(List<TableBuilder.Result> work) {
        int n = 0;
        for (TableBuilder.Result r : work) {
            n += 1 + r.rows.size();
        }
        return n;
    }

    public static String headerLine(int index, int total, String name, int rows) {
        return "===== [" + index + "/" + total + "] 处理表 `" + name + "`（" + rows + " 行）=====";
    }

    public static String ddlLog(String ddl) {
        return "-- 建表 DDL --" + LogText.NL + ddl;
    }

    public static String createdLog(String name) {
        return "[建表成功] `" + name + "`";
    }

    /** 建表被拒：把目标库、错误码展开、失败的语句原文与回滚说明一次说全。 */
    public static String createFailLog(String name, String target, String detail, String ddl) {
        return "[建表失败] 表 `" + name + "`（目标库 " + target + "）建表语句被 MySQL 拒绝"
                + LogText.NL + LogText.indentDetail(detail) + LogText.NL
                + "    失败的建表语句：" + LogText.NL + LogText.formatStatement(ddl)
                + LogText.NL + "    " + LogText.ROLLBACK_NOTE;
    }

    public static String importedLog(String name, long rows) {
        return "[导入完成] `" + name + "` 共 " + rows + " 行";
    }

    public static String importFailLog(String name, String target, int total, String detail) {
        return "[导入失败] 表 `" + name + "`（目标库 " + target + "）共 " + total
                + " 行数据未能写入，本表事务已回滚" + LogText.NL
                + LogText.indentDetail(detail) + LogText.NL + "    " + LogText.ROLLBACK_NOTE;
    }

    public static String connectFailLog(String target, String user, String detail) {
        return "[连接失败] 无法连接到 " + target + "（用户 " + user + "）" + LogText.NL
                + LogText.indentDetail(detail);
    }

    /** 当前这张表的行进度：页面底部那条进度百分比只跟着它走，不是全局计数。 */
    public interface RowProgress {
        void onTableProgress(int done, int total);
    }

    /**
     * 逐表导入。取消时提前收尾并返回已完成的部分；连不上库时返回空列表
     * （调用方照样走汇总，与 Python 侧一致：那一句"成功 0 张表"是有效信息）。
     */
    public static List<Row> run(Config cfg, List<TableBuilder.Result> work, boolean dropIfExists,
                               ImportParse.Sink sink, RowProgress page) {
        List<Row> results = new ArrayList<Row>();
        sink.setTotal(unitsOf(work));
        String target = targetOf(cfg);
        DbClient db;
        try {
            db = new DbClient(cfg);
        } catch (Exception e) {
            sink.logError(connectFailLog(target, cfg.user, LogText.describe(e)));
            return results;
        }
        try {
            for (int i = 0; i < work.size(); i++) {
                if (sink.cancelled()) {
                    sink.log("[已中止] 剩余的表未执行");
                    break;
                }
                TableBuilder.Result r = work.get(i);
                TableOps.Table t = r.table;
                String name = t.tableName;
                final int total = r.rows.size();
                final int[] loaded = {0};
                sink.log(headerLine(i + 1, work.size(), name, total));
                Row row = new Row(name, t.filepath);
                String ddl = Ddl.buildDdl(name, t.tableComment, t.columns, dropIfExists);
                sink.log(ddlLog(ddl));
                try {
                    db.createTable(ddl);
                    sink.log(createdLog(name));
                } catch (Exception e) {
                    row.message = "建表失败: " + LogText.describe(e);
                    sink.logError(createFailLog(name, target, LogText.describe(e), ddl));
                    results.add(row);
                    sink.advance(1 + total, "跳过 `" + name + "`");
                    continue;
                }
                final String label = name;
                try {
                    long written = db.insertData(name, t.columns, r.headers, r.rows,
                            new DbClient.WarnCallback() {
                                @Override
                                public void onWarn(String message) {
                                    sink.log(message);
                                }
                            },
                            new DbClient.ProgressCallback() {
                                @Override
                                public void onProgress(int done, int tot) {
                                    int delta = done - loaded[0];
                                    loaded[0] = done;
                                    if (delta > 0) {
                                        sink.advance(delta, "导入 `" + label + "`：" + done + "/" + tot + " 行");
                                    }
                                    if (page != null) {
                                        page.onTableProgress(done, tot);
                                    }
                                }
                            });
                    row.ok = true;
                    row.rows = written;
                    row.message = "导入 " + written + " 行";
                    sink.log(importedLog(name, written));
                } catch (Exception e) {
                    row.message = "导入失败: " + LogText.describe(e);
                    sink.logError(importFailLog(name, target, total, LogText.describe(e)));
                }
                results.add(row);
                // 补齐本表剩余计数（建表 1 项 + 没上报到的行），进度条总数才落得准
                sink.advance(1 + Math.max(0, total - loaded[0]), "完成 `" + name + "`");
            }
        } finally {
            db.close();
        }
        return results;
    }
}
