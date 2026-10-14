package com.excel2mysql.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 .sql 脚本执行到目标库：每个文件一个事务，中途被拒就整文件回滚后继续下一个文件。
 *
 * 之所以按文件而不是按语句提交：手写脚本常常是"建表 + 初始数据"一组，
 * 语句级提交会留下只有表没有数的半成品。
 */
public final class SqlRunner {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class FileResult {
        public String file;
        public List<String> statements = new ArrayList<String>();
        public int creates;
        public boolean ok;
        public String error;
        /** 失败发生在第几条（1 起），以及执行到此刻已成功几条：日志要说清回滚了多少。 */
        public int failedAt;
        public int executed;
        public String failedSql;
        /** 中途被用户取消：本文件已回滚，没提交。 */
        public boolean cancelled;
    }

    /**
     * 逐条语句的回报口：进度按语句推进，取消也要在两条语句之间检查——
     * 一个几十分钟的脚本只能"跑完这个文件才停"是不够的。
     */
    public interface StatementHook {
        boolean cancelled();

        void onStatement(int done, int total);
    }

    private SqlRunner() {
    }

    /**
     * 读脚本原文。编码按 UTF-8（含 BOM）→ GBK 回退：
     * UTF-8 解出的替换字符 � 一旦存在就说明不是 UTF-8，交给 GBK 再解一次。
     * GBK 解任何字节序列都不抛异常，所以只能靠这个判据，不能靠 try/catch。
     */
    public static String readScript(File file) throws IOException {
        byte[] raw = Files.readAllBytes(file.toPath());
        String utf8 = new String(raw, Charset.forName("UTF-8"));
        if (utf8.indexOf('�') < 0) {
            return stripBom(utf8);
        }
        return stripBom(new String(raw, Charset.forName("GBK")));
    }

    private static String stripBom(String text) {
        return text.length() > 0 && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    /**
     * 执行内存里的脚本文本：SQL 页改过表名后不落盘也要能直接跑，
     * 用磁盘上的原文执行会建出一批没改名的表。
     */
    public static FileResult runText(DbClient db, String file, String text) {
        return runText(db, file, text, null);
    }

    public static FileResult runText(DbClient db, String file, String text, StatementHook hook) {
        FileResult r = new FileResult();
        r.file = file;
        List<String> statements = SqlScript.splitStatements(text);
        r.statements = statements;
        r.creates = SqlScript.extractCreateTables(text).size();
        Statement st = null;
        try {
            st = db.connection().createStatement();
            for (int pos = 0; pos < statements.size(); pos++) {
                if (hook != null && hook.cancelled()) {
                    r.executed = pos;
                    try {
                        db.connection().rollback();
                    } catch (SQLException ignored) {
                        // 取消时的回滚失败不覆盖"已取消"这件事
                    }
                    r.cancelled = true;
                    return r;
                }
                String sql = statements.get(pos);
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    r.failedAt = pos + 1;
                    r.executed = pos;
                    r.failedSql = sql;
                    throw e;
                }
                r.executed = pos + 1;
                if (hook != null) {
                    hook.onStatement(pos + 1, statements.size());
                }
            }
            db.connection().commit();
            r.ok = true;
            return r;
        } catch (SQLException e) {
            try {
                db.connection().rollback();
            } catch (SQLException ignored) {
                // 回滚再失败时保留原始报错，那才是使用者要看的原因
            }
            r.ok = false;
            r.error = LogText.describe(e);
            if (r.failedAt == 0) {
                r.executed = 0;
            }
            return r;
        } finally {
            if (st != null) {
                try {
                    st.close();
                } catch (SQLException ignored) {
                    // 收尾关闭失败不影响结果
                }
            }
        }
    }
}
