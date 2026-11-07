package com.excel2mysql.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ZIP 展开 + 逐文件解析表结构，产出待导入列表与汇总计数。
 * 对应 main.py:313-428 的 parse_import_files。
 *
 * 这里不碰任何控件：进度与日志都走 Sink 接口，所以同一份函数既能挂在模态进度框上
 * 跑在子线程里，也能在自检里原地跑完——测的就是线上那一段，不是它的复印件。
 */
public final class ImportParse {

    /** build_table_def 逐阶段回报（合并检测 / 读取工作簿 / 识别格式 / 推断类型 / 选主键），解析一个文件就按这么多项计。 */
    public static final int PARSE_UNITS = 5;

    /** 进度与日志的回报口。 */
    public interface Sink {
        void setTotal(int total);

        void step(String label);

        void advance(int units, String label);

        void log(String text);

        void logError(String text);

        boolean cancelled();
    }

    /** 只写日志、不管进度条的回报口：命令行与自检用它。 */
    public static final Sink LOG_ONLY = new Sink() {
        @Override
        public void setTotal(int total) {
        }

        @Override
        public void step(String label) {
        }

        @Override
        public void advance(int units, String label) {
        }

        @Override
        public void log(String text) {
            System.out.println(text);
        }

        @Override
        public void logError(String text) {
            System.out.println(text);
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    public static final class Outcome {
        public final List<TableBuilder.Result> tables = new ArrayList<TableBuilder.Result>();
        public final List<String> addedLoose = new ArrayList<String>();
        public final List<String> addedZip = new ArrayList<String>();
        /** 解压出的路径 → "包名!包内路径"，文件列表的 tooltip 靠它。 */
        public final Map<String, String> zipOrigin = new LinkedHashMap<String, String>();
        public int zipsOk;
        public int zipsBad;
        public int zipsEmpty;
        public int failed;
        public int dups;
        public int merged;
        public boolean cancelled;
    }

    /**
     * 拆分产物的落点覆盖：null 表示写在原文件旁边（现场口径）。
     *
     * <p>自检把它指到临时目录——自检的"用户文件"就是夹具目录，按现场口径写会把
     * 夹具目录写花（下一轮自检又会把那些 _partN.xlsx 当成夹具读进来，表数、顺序全变）。
     */
    public static String splitOutDir;

    private ImportParse() {
    }

    /**
     * 建模时行上限救不回来：按公共字段把这张表拆成几个 Excel（见 {@link SplitWide}）。
     *
     * <p>返回 null 表示这张表没被拦下，本来就不用拆。结构定义模式的表不拆：那类表的字段
     * 类型是人工写的承诺，拆出来的数据表会按"普通数据表"重新推断类型，等于把人工定义丢掉。
     */
    public static SplitWide.Plan autoSplitWide(TableBuilder.Result td, String outDir, String stem) {
        if (td == null || td.rowFit == null || td.rowFit.refuse == null) {
            return null;
        }
        if ("structured".equals(td.table.source)) {
            SplitWide.Plan p = new SplitWide.Plan();
            p.reason = "结构定义模式的表不自动拆（字段类型是人工写的）";
            return p;
        }
        String dir = outDir != null ? outDir : splitOutDir;
        return SplitWide.write(td, dir, stem, 16384);
    }

    public static Outcome parse(List<String> files, List<String> existingPaths, String zipDir,
                                int destStart, TableBuilder builder, Sink sink) {
        Outcome out = new Outcome();
        List<String> zips = new ArrayList<String>();
        List<String> loose = new ArrayList<String>();
        for (String f : files) {
            if (f.toLowerCase().endsWith(".zip")) {
                zips.add(f);
            } else {
                loose.add(f);
            }
        }
        int entries = 0;
        for (String z : zips) {
            entries += ZipImport.countExcelEntries(z);
        }
        // 总项数先按「每个压缩包 1 项 + 包内每个 Excel 解出 1 项 + 每个待解析 Excel 5 项」算准，
        // 进度条才会单向前进，不会倒着走
        sink.setTotal(zips.size() + entries + PARSE_UNITS * (loose.size() + entries));

        List<String> pending = new ArrayList<String>();
        Set<String> fromZip = new LinkedHashSet<String>();
        // 解压出的路径 -> 包内目录链：表名要从包根一路拼到文件名（见 Names.joinArchiveDirs）
        Map<String, List<String>> zipDirs = new LinkedHashMap<String, List<String>>();
        // 解压出的路径 -> {压缩包所在目录, 包内叶子名}：拆出来的 Excel 落在压缩包旁边，
        // 写进临时解压目录的话会随会话清理一起删掉
        Map<String, String[]> zipHome = new LinkedHashMap<String, String[]>();
        for (String f : files) {
            if (!f.toLowerCase().endsWith(".zip")) {
                pending.add(f);                 // 散装 Excel 留到解析阶段计项
                continue;
            }
            String zname = baseName(f);
            String stem = stripExtension(zname);
            // 解压目录名只占路径预算、不参与表名（表名来自包内那个文件名）。
            // 现场机器的 TEMP 本身就比开发机长，包名又是几十字中文时，
            // 把这层压短是最划算的一刀——超过 12 个字符就截，尾号仍然唯一。
            if (stem.length() > 12) {
                stem = stem.substring(0, 12);
            }
            String dest = new File(zipDir, stem + "_" + (destStart + out.zipOrigin.size() + 1))
                    .getPath();
            ZipImport.Result r;
            try {
                r = ZipImport.extract(f, dest, new ZipImport.ProgressCallback() {
                    @Override
                    public void onEntry(String name) {
                        sink.step("解出 " + zname + "/" + baseName(name));
                    }
                });
            } catch (ZipImport.ZipArchiveException e) {
                out.zipsBad++;
                sink.logError("[压缩包失败] " + zname + "：" + e.getMessage());
                sink.step(zname);
                if (sink.cancelled()) {
                    out.cancelled = true;
                    return out;
                }
                continue;
            } catch (Exception e) {
                out.zipsBad++;
                sink.logError("[压缩包失败] " + zname + " 展开异常" + LogText.NL
                        + LogText.indentDetail(LogText.describe(e)));
                sink.step(zname);
                if (sink.cancelled()) {
                    out.cancelled = true;
                    return out;
                }
                continue;
            }
            out.zipsOk++;
            for (ZipImport.Entry x : r.files) {
                out.zipOrigin.put(x.path, zname + "!" + x.origin);
                fromZip.add(x.path);
                List<String> inner = archiveDirs(x.origin);
                if (!inner.isEmpty()) {
                    zipDirs.put(x.path, inner);
                }
                zipHome.put(x.path, new String[]{
                    new File(f).getAbsoluteFile().getParent(),
                    stripExtension(baseName(x.origin.replace('\\', '/')))});
            }
            StringBuilder msg = new StringBuilder("[压缩包] ").append(zname)
                    .append("：展开 ").append(r.files.size()).append(" 个 Excel");
            if (!r.ignored.isEmpty()) {
                msg.append("，忽略 ").append(r.ignored.size()).append(" 个非 Excel 文件");
            }
            if (r.nestedZips > 0) {
                msg.append("，跳过 ").append(r.nestedZips).append(" 个嵌套 zip（不支持递归展开）");
            }
            if (!r.flattened.isEmpty()) {
                // 平铺只砍包内目录层级，文件名不变所以表名也不变；
                // 但"解出来的路径和包里不一样"这件事必须说，否则现场按包内层级去找文件会找不到
                msg.append("，另有 ").append(r.flattened.size())
                        .append(" 个文件因解压路径过长被平铺到根目录（表名不受影响）");
            }
            sink.log(msg.toString());
            if (r.files.isEmpty()) {
                out.zipsEmpty++;
                sink.logError("[压缩包为空] " + zname + "：包内没有找到 .xlsx / .xls 文件");
            }
            for (ZipImport.Entry x : r.files) {
                pending.add(x.path);
            }
            sink.step(zname);
            if (sink.cancelled()) {
                out.cancelled = true;
                return out;
            }
        }

        Set<String> existing = new LinkedHashSet<String>(existingPaths);
        for (String f : pending) {
            if (sink.cancelled()) {
                out.cancelled = true;
                break;
            }
            String fname = baseName(f);
            if (existing.contains(f)) {
                out.dups++;
                sink.log("[重复跳过] " + fname + " 已在待导入列表");
                sink.advance(PARSE_UNITS, fname);
                continue;
            }
            final int[] used = {0};
            TableBuilder.ProgressCallback phase = new TableBuilder.ProgressCallback() {
                @Override
                public void stage(String text) {
                    // 阶段数万一和这里对不上，收尾的补齐仍会把进度条落准
                    if (used[0] < PARSE_UNITS) {
                        used[0]++;
                        sink.advance(1, fname + "：" + text);
                    }
                }
            };
            try {
                TableBuilder.Result td = builder.build(f, phase, zipDirs.get(f));
                // 行上限救不回来（row size too large / 列数超 1017）时顺手拆成几个 Excel，
                // 拆出来的文件落在原文件（压缩包）旁边，拖回来就是几张能分别建的表
                String[] home = zipHome.get(f);
                td.wideSplit = autoSplitWide(td, home == null ? null : home[0],
                        home == null ? null : home[1]);
                out.tables.add(td);
                if (fromZip.contains(f)) {
                    out.addedZip.add(f);
                } else {
                    out.addedLoose.add(f);
                }
                sink.log(describeParsed(fname, td));
                List<String> notes = new ArrayList<String>();
                for (Infer.Column c : td.table.columns) {
                    if (c.typeNote != null && !c.typeNote.isEmpty()) {
                        notes.add(c.typeNote);
                    }
                }
                if (!notes.isEmpty()) {
                    sink.log("[类型修正] " + fname + " 共 " + notes.size()
                            + " 列按表头语义调整：" + LogText.NL + "    "
                            + String.join(LogText.NL + "    ", notes));
                }
                for (String line : SplitWide.noticeLines(td.table.tableName,
                        td.table.columns, td.wideSplit)) {
                    sink.log(line);
                }
            } catch (MergeCheck.MergedCellsException e) {
                out.merged++;
                int total = 0;
                for (List<String> v : e.merged.values()) {
                    total += v.size();
                }
                sink.logError("[合并单元格] " + fname + " 含 " + total + " 处合并区，"
                        + "已拒绝导入，未进入待导入列表" + LogText.NL
                        + LogText.indentDetail(e.getMessage()) + LogText.NL
                        + "    原因：合并区只有左上角有值，其余格读进来是空值，"
                        + "字段名与数据会错位。请先在 Excel 中取消合并后重新添加。");
            } catch (Exception e) {
                out.failed++;
                sink.logError("[解析失败] " + f + " 未能解析成表结构，已跳过" + LogText.NL
                        + LogText.indentDetail(LogText.describe(e)));
            }
            sink.advance(Math.max(0, PARSE_UNITS - used[0]), fname);
        }
        return out;
    }

    /**
     * 包内路径（如 部门A/子目录/表.xlsx）-> 目录链 [部门A, 子目录]。
     *
     * 表名按「目录_…_文件名」拼，所以这里保留每一层目录；根目录下的文件返回空表，
     * 表名照旧只看文件名。平铺（路径过长被砍目录）时 origin 仍是包内原始路径，
     * 表名不受落盘位置影响。
     */
    static List<String> archiveDirs(String origin) {
        List<String> out = new ArrayList<String>();
        if (origin == null) {
            return out;
        }
        String[] parts = origin.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                out.add(parts[i]);
            }
        }
        return out;
    }

    /** 「[已解析] …」那一行的完整口径，与 Python 侧逐字对齐。 */
    static String describeParsed(String fname, TableBuilder.Result td) {
        String pk = null;
        for (Infer.Column c : td.table.columns) {
            if (c.primaryKey) {
                pk = c.field;
                break;
            }
        }
        String reason = td.pkReason == null ? "" : td.pkReason;
        // 正文一律用 | 分段：三页的日志一屏几百行，逗号串起来的长句要数着读
        String pkMsg = pk == null ? " | 未设主键（" + reason + "）"
                : " | 主键 `" + pk + "`（" + reason + "）";
        String srcMsg = "structured".equals(td.table.source) ? " | 结构定义模式" : "";
        String mergeMsg = td.mergedHeaderDepth > 0
                ? " | 合并表头按 " + td.mergedHeaderDepth + " 行展开" : "";
        String line = "[已解析] " + fname + " → 表名预置 " + td.table.tableName
                + " | 字段 " + td.table.columns.size() + " 列 | 数据 " + td.rows.size()
                + " 行" + srcMsg + mergeMsg + pkMsg;
        // 这四条是"这张表为什么长成这样"的补充，缩进挂在正文那一行下面，不挤进正文
        List<String> notes = new ArrayList<String>();
        if (!td.extraDataColumns.isEmpty()) {
            notes.add("数据 Sheet 多余列已忽略：" + String.join("、", td.extraDataColumns));
        }
        // 具名但整列没值的字段：保留下来了，但要在日志里点名，别让"空模板导进去 0 行"
        // 变成一笔糊涂账（与 Python 侧 main/cli 的 [已解析] 行逐字一致）
        if (!td.emptyColumns.isEmpty()) {
            notes.add("整列为空的字段照旧保留：" + head6(td.emptyColumns));
        }
        if (td.noDataRows) {
            notes.add("没有数据行，建出来是空表");
        }
        // 表头整行没写字的模板：兜底出来的字段名要点名，否则现场看到 col、col_2 不知道从哪来
        if (!td.blankHeaders.isEmpty()) {
            notes.add("表头整行没写字，按列宽兜底成：" + head6(td.blankHeaders));
        }
        if (!notes.isEmpty()) {
            line += LogText.NL + "    说明 | " + String.join(" | ", notes);
        }
        return line;
    }

    /** 取前 6 个名字，多余折成"等 N 个"（与 Python 侧同口径）。 */
    private static String head6(List<String> names) {
        String joined = String.join("、", names.subList(0, Math.min(6, names.size())));
        return names.size() > 6 ? joined + " 等 " + names.size() + " 个" : joined;
    }

    /** 弹窗正文：只说实际用到的行，同一句话还会写进日志。 */
    public static String summaryText(Outcome o) {
        List<String> lines = new ArrayList<String>();
        if (!o.addedLoose.isEmpty()) {
            lines.add("添加 " + o.addedLoose.size() + " 项文件，包含 " + countExt(o.addedLoose, ".xlsx")
                    + " 项 xlsx 文件、" + countExt(o.addedLoose, ".xls") + " 项 xls 文件");
        }
        if (!o.addedZip.isEmpty()) {
            lines.add("添加 " + o.zipsOk + " 项 zip 文件，包含 " + countExt(o.addedZip, ".xlsx")
                    + " 项 xlsx 文件、" + countExt(o.addedZip, ".xls") + " 项 xls 文件");
        }
        List<String> bad = new ArrayList<String>();
        if (o.merged > 0) {
            bad.add(o.merged + " 项含合并单元格已拒绝");
        }
        if (o.failed > 0) {
            bad.add(o.failed + " 项解析失败");
        }
        if (o.dups > 0) {
            bad.add(o.dups + " 项重复已跳过");
        }
        if (o.zipsBad > 0) {
            bad.add(o.zipsBad + " 个压缩包无法展开");
        }
        if (o.zipsEmpty > 0) {
            bad.add(o.zipsEmpty + " 个压缩包内没有 Excel");
        }
        if (!bad.isEmpty()) {
            lines.add("另有 " + String.join("、", bad) + "，详见日志");
        }
        return String.join(LogText.NL, lines);
    }

    private static int countExt(List<String> paths, String ext) {
        int n = 0;
        for (String p : paths) {
            if (p.toLowerCase().endsWith(ext)) {
                n++;
            }
        }
        return n;
    }

    private static String baseName(String path) {
        String s = path == null ? "" : path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? s : s.substring(at + 1);
    }

    /** os.path.splitext 的口径：去掉最后一个点之后的部分，没有点就原样返回。 */
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
