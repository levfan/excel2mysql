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

    public static FileResult runOne(DbClient db, File file) {
        FileResult r = new FileResult();
        r.file = file.getName();
        String text;
        try {
            text = readScript(file);
        } catch (IOException e) {
            r.ok = false;
            r.error = e.getMessage();
            return r;
        }
        List<String> statements = SqlScript.splitStatements(text);
        r.statements = statements;
        r.creates = SqlScript.extractCreateTables(text).size();
        Statement st = null;
        try {
            st = db.connection().createStatement();
            for (String sql : statements) {
                st.execute(sql);
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
            r.error = e.getMessage();
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
