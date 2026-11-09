package com.excel2mysql.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 层间映射 CSV 的读写。表级 15 列（＋可选 ods物理表名），字段级 11 列。纯离线。 */
public final class MappingFile {

    public static final class TableMap {
        public String system, subDomain, tableCn, tableEn, fieldCount, pkField,
                domain, domainCode, domainSub, domainTarget, objDims, theme,
                basis, status, note, odsPhysical;
    }

    public static final class FieldMap {
        public String srcTable, srcField, srcComment, dstTable, dstField, dstComment,
                dataElemName, dataElemId, transformRule, status;
        public boolean governance;
    }

    private static final String[] TABLE_COLS = {
            "系统", "子领域", "表中文名", "表英文名", "字段数", "主键字段", "目标领域",
            "领域码", "领域子类", "领域目标表", "涉及对象维度", "主题归属", "分类依据",
            "映射状态", "备注"
    };
    private static final String[] FIELD_COLS = {
            "源表", "源字段", "源注释", "目标表", "目标字段", "目标注释", "数据元名称",
            "数据元标识符", "转换规则", "是否治理字段", "映射状态"
    };

    private final List<TableMap> tables;

    private MappingFile(List<TableMap> tables) { this.tables = tables; }

    public List<TableMap> tables() { return tables; }

    public static MappingFile loadTableCsv(String path) throws IOException {
        List<String> lines = readLines(path);
        if (lines.isEmpty()) { throw new IOException("映射文件为空：" + path); }
        List<String> header = parseCsvLine(lines.get(0));
        for (String need : TABLE_COLS) {
            if (!header.contains(need)) { throw new IOException("映射文件缺列：" + need); }
        }
        int[] idx = new int[TABLE_COLS.length];
        for (int i = 0; i < TABLE_COLS.length; i++) { idx[i] = header.indexOf(TABLE_COLS[i]); }
        int physIdx = header.indexOf("ods物理表名");
        List<TableMap> out = new ArrayList<TableMap>();
        for (int r = 1; r < lines.size(); r++) {
            if (lines.get(r).trim().isEmpty()) { continue; }
            List<String> cells = parseCsvLine(lines.get(r));
            TableMap t = new TableMap();
            t.system = at(cells, idx[0]); t.subDomain = at(cells, idx[1]);
            t.tableCn = at(cells, idx[2]); t.tableEn = at(cells, idx[3]);
            t.fieldCount = at(cells, idx[4]); t.pkField = at(cells, idx[5]);
            t.domain = at(cells, idx[6]); t.domainCode = at(cells, idx[7]);
            t.domainSub = at(cells, idx[8]); t.domainTarget = at(cells, idx[9]);
            t.objDims = at(cells, idx[10]); t.theme = at(cells, idx[11]);
            t.basis = at(cells, idx[12]); t.status = at(cells, idx[13]);
            t.note = at(cells, idx[14]);
            t.odsPhysical = physIdx >= 0 ? at(cells, physIdx) : "";
            out.add(t);
        }
        return new MappingFile(out);
    }

    public static List<FieldMap> readFieldCsv(String path) throws IOException {
        List<String> lines = readLines(path);
        if (lines.isEmpty()) { throw new IOException("字段映射文件为空：" + path); }
        List<String> header = parseCsvLine(lines.get(0));
        for (String need : FIELD_COLS) {
            if (!header.contains(need)) { throw new IOException("字段映射文件缺列：" + need); }
        }
        int[] idx = new int[FIELD_COLS.length];
        for (int i = 0; i < FIELD_COLS.length; i++) { idx[i] = header.indexOf(FIELD_COLS[i]); }
        List<FieldMap> out = new ArrayList<FieldMap>();
        for (int r = 1; r < lines.size(); r++) {
            if (lines.get(r).trim().isEmpty()) { continue; }
            List<String> c = parseCsvLine(lines.get(r));
            FieldMap f = new FieldMap();
            f.srcTable = at(c, idx[0]); f.srcField = at(c, idx[1]); f.srcComment = at(c, idx[2]);
            f.dstTable = at(c, idx[3]); f.dstField = at(c, idx[4]); f.dstComment = at(c, idx[5]);
            f.dataElemName = at(c, idx[6]); f.dataElemId = at(c, idx[7]);
            f.transformRule = at(c, idx[8]);
            f.governance = "是".equals(at(c, idx[9]));
            f.status = at(c, idx[10]);
            out.add(f);
        }
        return out;
    }

    public static void writeFieldCsv(String path, List<FieldMap> rows) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8);
        try {
            w.write('﻿');
            w.write(join(FIELD_COLS)); w.write("\r\n");
            for (FieldMap f : rows) {
                String[] cells = {
                        f.srcTable, f.srcField, f.srcComment, f.dstTable, f.dstField,
                        f.dstComment, f.dataElemName, f.dataElemId, f.transformRule,
                        f.governance ? "是" : "否", f.status
                };
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) { sb.append(','); }
                    sb.append(csvCell(cells[i]));
                }
                w.write(sb.toString()); w.write("\r\n");
            }
        } finally { w.close(); }
    }

    /** UTF-8-BOM → UTF-8（严格）→ GBK 兜底。BOM 剥掉再解码。 */
    public static String readTextAuto(File f) throws IOException {
        byte[] bytes = readAll(f);
        int off = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        String body = new String(bytes, off, bytes.length - off, StandardCharsets.UTF_8);
        if (off == 3) { return body; }
        java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, off, bytes.length - off, Charset.forName("GBK"));
        }
    }

    public static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else { quoted = false; }
                } else { cur.append(ch); }
            } else if (ch == '"') { quoted = true; }
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else { cur.append(ch); }
        }
        out.add(cur.toString());
        return out;
    }

    public static String csvCell(String s) {
        String v = s == null ? "" : s;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static List<String> readLines(String path) throws IOException {
        String text = readTextAuto(new File(path));
        List<String> lines = new ArrayList<String>();
        for (String l : text.split("\r\n|\n|\r", -1)) { lines.add(l); }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) { lines.remove(lines.size() - 1); }
        return lines;
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); }
        } finally { in.close(); }
        return bos.toByteArray();
    }

    private static String at(List<String> cells, int i) {
        return i >= 0 && i < cells.size() ? cells.get(i) : "";
    }

    private static String join(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) { sb.append(','); }
            sb.append(csvCell(cols[i]));
        }
        return sb.toString();
    }
}
