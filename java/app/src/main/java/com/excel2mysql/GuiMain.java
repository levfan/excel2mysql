package com.excel2mysql;

import com.excel2mysql.gui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Swing 版入口。只用 Swing/AWT，不引 JavaFX。
 *
 * --selftest 是给闸门用的：真构造窗口、真解析夹具、真数表格行数，
 * 但不是"看一眼界面没崩"就算过——断言落在可数的副作用上。
 */
public final class GuiMain {

    public static void main(String[] argv) {
        List<String> args = Arrays.asList(argv);
        if (args.contains("--selftest")) {
            System.exit(selftest(args));
        }
        showWindow(args);
    }

    private static void showWindow(List<String> args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            System.out.println("! [WARN] GUI | 系统外观设置失败，用默认外观：" + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            List<String> files = new ArrayList<String>();
            for (String a : args) {
                if (!a.startsWith("-")) {
                    files.add(a);
                }
            }
            frame.setVisible(true);
            if (!files.isEmpty()) {
                frame.addFiles(files);
            }
        });
    }

    private static int selftest(List<String> args) {
        int from = args.indexOf("--selftest");
        String dir = from + 1 < args.size() && !args.get(from + 1).startsWith("-")
                ? args.get(from + 1) : "java/verify/fixtures";
        File folder = new File(dir);
        if (!folder.isDirectory()) {
            System.out.println("x [ERROR] GUI-SELFTEST | 夹具目录不存在：" + folder.getPath());
            return 2;
        }
        final List<String> files = new ArrayList<String>();
        File[] children = folder.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File f : children) {
                // 夹具只收 .xlsx 是刻意的：自检里"添加 N 项文件 / 表数 N"这些期望值取的是
                // 目录里 .xlsx 的个数（不是手写常数，但也不放宽到 .xls/.zip——
                // 那会让整批按下标取表的断言同时对不上，红得看不出真问题）。
                // 喂别的目录给不出夹具形状时，由下面 tables==0 那一条给人话，
                // 而不是靠多收文件去"兼容"。
                if (f.getName().toLowerCase().endsWith(".xlsx")) {
                    files.add(f.getPath());
                }
            }
        }
        // 关掉模态框：任务原地跑完。测的是线上那同一个工作函数与同一个回报接口，
        // 不是它的复印件（对应 main.py:137 的 MODAL_PROGRESS）。
        // 模态那一支两条线都没有自动化覆盖，交付前手工点一次，见 GUI-对齐清单.md 第 10 节。
        com.excel2mysql.gui.MainFrame.modalProgress = false;
        final CountDownLatch done = new CountDownLatch(1);
        final int[] result = {0};
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = null;
            try {
                frame = new MainFrame();   // 建窗也在 try 里：构造期抛异常要让自检拿到退出码，而不是把闸门挂死
                String addSummary = frame.addFiles(files);
                int tables = frame.tables().size();
                int fileRows = frame.fileModel().getRowCount();
                int rows = frame.fileModel().rowTotal();
                String ddl = frame.ddlText();
                List<String> problems = new ArrayList<String>();
                if (tables == 0) {
                    // 后面几十处检查都按下标取表与取行，没夹具就别硬跑：
                    // 给一句能看懂的话，别抛越界
                    problems.add("这个目录里没有可加载的 .xlsx 夹具（自检只认 verify/fixtures 那些 xlsx），"
                            + "本次 addFiles 返回：" + addSummary.replace((char) 10, ' '));
                    for (String p : problems) {
                        System.out.println("x [ERROR] GUI-SELFTEST | " + p);
                    }
                    result[0] = 1;
                    return;
                }
                if (!addSummary.contains("添加 " + files.size() + " 项文件")) {
                    // 期望值取"目录里真有几个夹具"，不是手写的常数：
                    // 加一张夹具就要改一处常量的话，改的人多半会漏，红成"汇总没数清"
                    problems.add("添加结果汇总没数清文件：" + addSummary.replace((char) 10, ' '));
                }
                if (!frame.logPanel().contains("[添加结果] 添加 " + files.size() + " 项文件")) {
                    problems.add("弹窗那句话没同时写进日志");
                }
                if (!"添加结果".equals(frame.lastDialogTitle())) {
                    problems.add("添加结果弹窗标题不对：" + frame.lastDialogTitle());
                }
                if (!frame.lastDialogText().contains("添加 " + files.size() + " 项文件")) {
                    problems.add("弹窗正文与日志不是同一句话：" + frame.lastDialogText());
                }
                // Python 的添加结果用 information，只有任务异常才换 critical 图标
                if (frame.lastDialogIsError()) {
                    problems.add("添加结果弹窗不该用出错图标");
                }
                // 中途取消：这句话要在弹窗正文里，只写日志的话只看弹窗的人以为整批跑完了
                com.excel2mysql.core.ImportParse.Outcome part = new com.excel2mysql.core.ImportParse.Outcome();
                part.addedLoose.add("a.xlsx");
                part.addedLoose.add("b.xls");
                part.failed = 1;
                part.cancelled = true;
                String partSummary = frame.finishAdd(part);
                if (!partSummary.startsWith("已取消，只完成了部分解析")) {
                    problems.add("取消的话没进正文：" + partSummary.replace((char) 10, ' '));
                }
                if (!partSummary.contains("添加 2 项文件")) {
                    problems.add("取消时把已解析的汇总顶掉了：" + partSummary.replace((char) 10, ' '));
                }
                if (!flat(frame.lastDialogText()).equals(flat(partSummary))) {
                    problems.add("取消后的弹窗正文和汇总不是同一句话");
                }
                if (!frame.logPanel().contains("[添加结果] 已取消，只完成了部分解析")) {
                    problems.add("取消后的汇总没同时写进日志");
                }
                if (tables != files.size()) {
                    problems.add("表数期望 " + files.size() + "，实际 " + tables
                            + "（有文件解析失败，看日志）");
                }
                if (rows == 0) {
                    problems.add("字段总数为 0，模型是空的");
                }
                // 表数是 0 时别再按下标取行：现场会把这个目录换成自己的数据目录来跑自检，
                // 那时要看到的是"表数不符"这条人话，而不是一个越界异常
                if (tables > 0 && !frame.fileModel().tooltipAt(0).endsWith(".xlsx")) {
                    problems.add("文件列没给完整路径：" + frame.fileModel().tooltipAt(0));
                }
                // 文件表是"一行一个文件"，不是"一行一个字段"，两者搞混说明改造没做完
                if (fileRows != tables) {
                    problems.add("文件表应一行一个文件：" + fileRows + " 行 / " + tables + " 张表");
                }
                if (!ddl.contains("CREATE TABLE")) {
                    problems.add("DDL 预览里没有 CREATE TABLE");
                }
                if (!ddl.contains("PRIMARY KEY")) {
                    problems.add("DDL 预览里没有主键，自动选主键没生效");
                }
                // 两行合并表头是唯一"带合并区却放行"的形状：展开坏了要么直接解析失败
                // （上面表数会红），要么字段名还是第一行的组名。夹具不在目录里时跳过，
                // 让自检还能指向现场自己的数据目录跑
                boolean hasMergedFixture = false;
                for (String p : files) {
                    if (p.endsWith("两行合并表头（ods_t_merged_header）.xlsx")) {
                        hasMergedFixture = true;
                        break;
                    }
                }
                if (hasMergedFixture) {
                    if (!frame.logPanel().contains("合并表头按 2 行展开")) {
                        problems.add("两行合并表头没在[已解析]里说明按几行展开");
                    }
                    if (!ddl.contains("ji_shu_gong_si_xiao_ji") || !ddl.contains("jsgsptyg")) {
                        problems.add("两行合并表头没有按「父-子」展开成字段名");
                    }
                }
                // 合并表头只认标准两行：三行表头、第 2 行里的子合并、2x2 块都必须拒绝。
                // 这些形状不能放进夹具目录（会被当成普通夹具要求建表），所以直接喂
                // headerPlan，守住 Java 侧与 Python 同一条放行边界。
                java.util.Map<String, List<String>> canonical =
                        new java.util.LinkedHashMap<String, List<String>>();
                canonical.put("原始表", Arrays.asList("A1:A2", "B1:C1"));
                com.excel2mysql.core.MergeCheck.HeaderPlan plan =
                        com.excel2mysql.core.MergeCheck.headerPlan(canonical, "原始表");
                if (plan == null || plan.depth != 2) {
                    problems.add("标准两行合并表头被误拒了");
                }
                java.util.Map<String, List<String>> threeRows =
                        new java.util.LinkedHashMap<String, List<String>>();
                threeRows.put("原始表", Arrays.asList("A1:A3"));
                if (com.excel2mysql.core.MergeCheck.headerPlan(threeRows, "原始表") != null) {
                    problems.add("三行合并表头不该被当成标准两行放行");
                }
                java.util.Map<String, List<String>> childMerge =
                        new java.util.LinkedHashMap<String, List<String>>();
                childMerge.put("原始表", Arrays.asList("A1:A2", "B1:C1", "B2:C2"));
                if (com.excel2mysql.core.MergeCheck.headerPlan(childMerge, "原始表") != null) {
                    problems.add("第 2 行里的子合并不该被放行");
                }
                java.util.Map<String, List<String>> block =
                        new java.util.LinkedHashMap<String, List<String>>();
                block.put("原始表", Arrays.asList("A1:A2", "B1:C2"));
                if (com.excel2mysql.core.MergeCheck.headerPlan(block, "原始表") != null) {
                    problems.add("2x2 合并块不该被放行");
                }
                // 表数的可见痕迹是文件表行数与日志里那句汇总（Python 没有状态栏），
                // 两者已分别在上面断过，这里不再断自造的状态文字
                // 文件表刻意不可就地编辑：字段级改动在双击打开的结构弹窗里做，
                // 和 Python 侧一致（就地编辑会让"改的是哪张表"变含糊）
                if (frame.fileModel().isCellEditable(0, 3)) {
                    problems.add("文件表不该可就地编辑，改动要走结构弹窗");
                }
                // 结构弹窗要能真的改到表定义：改字段类型后 DDL 必须跟着变
                table_select_for_dialog(frame, problems);
                // 多表标签页弹窗：校验不过不能先写回，否则表定义停在改了一半的状态
                structure_dialog(frame, problems);
                // 前缀两个按钮与「覆盖已存在表」勾选：文案、幂等、跳过口径都要和 Python 一致
                prefix_and_labels(frame, problems);
                // 拖拽区与页内进度条：真拖进来走的必须是和选择器同一个入口
                drop_and_progress(frame, files, problems);
                // 导入收尾：成功文件移出、失败保留，日志与弹窗用的是同一句话
                import_finish(frame, files, problems);
                // 「开始导入」的两道闸：没表不管、没选库弹窗，且都不许把任务位占死
                import_guard(frame, files, problems);
                // SQL 页签：读盘、改表名、落盘副本、移除选中，全离线可验
                sql_page(frame, problems);
                // 第三个页签「SQL 执行器」：编辑区、示例清单与空输入那道闸
                sql_run_tab(frame, problems);
                // 第二个页签的两颗生成按钮：解析脚本 → 标准/简易件 → 回读
                sql_gen_excel(frame, problems);
                // 页面级「停止」与并发护栏
                stop_button(frame, problems);
                // 进度框文案与收尾
                progress_copy(problems);
                // DDL 逐表分页、文件表列宽、端口范围
                ddl_dialog_and_widgets(frame, files, problems);
                // 导入那条路的文案与计项口径
                import_run(problems);
                sql_run_connect_failure(problems);
                // 子线程出意外时的兜底文案
                task_crash(frame, problems);
                // ZIP 展开、汇总计数与临时目录清理
                zip_roundtrip(frame, files, problems);
                // 建不出表的输入必须在解析阶段就拒（空表 / 只有表头 / 内容是文本的假 .xlsx）
                checkRejects(frame, problems);
                // 命令行的 --prefix 对 .sql 也要生效，且 dry-run 要能预览脚本
                checkSqlCliPrefix(problems);
                // 「只写注释也不算」的判据表，与 Python 侧 pack_test 的 E10/E11 同一张
                checkCommentOnly(problems);
                // 日志面板的线程安全：工作线程加行的同时有人在遍历，锁没加对就会炸 CME，
                // 那个异常会把日志刷新整个带走——现场看到的就是"跑到一半日志不动了"
                checkLogPanelThreadSafe(problems);
                // 超出 BIGINT 的整数要原样发进库，不能让驱动截成负数
                checkBigIntWire(problems);
                // 「生成标准/简易 excel 文件」两颗按钮：落点、产物形状、回读与日志弹窗
                meta_export(frame, files, problems);
                // 标题版本号、说明窗口与关于框
                help_and_about(frame, problems);
                // 死按钮守卫：每个按钮都必须挂了监听器，界面上出现点了没反应的按钮
                // 比少一个按钮更糟——用户会以为是自己操作错了
                for (javax.swing.AbstractButton b : frame.buttons()) {
                    if (b.getActionListeners().length == 0) {
                        problems.add("按钮没有绑定动作：" + b.getText());
                    }
                }
                // 自造文案清掉之后不许长回来：状态栏那几行 Python 根本没有，
                // 连接状态只由绿/红提示条说（main.py:861-878 那条框里只有输入框和按钮）
                List<String> labelTexts = new ArrayList<String>();
                collectLabels(frame.getContentPane(), labelTexts);
                for (String bad : new String[]{"尚未连接", "待导入 ", "尚未添加文件"}) {
                    for (String l : labelTexts) {
                        if (l.contains(bad)) {
                            problems.add("界面上又长出 Python 没有的状态文字：" + l);
                        }
                    }
                }
                // 「开始导入」的启用条件与 Python 同：有表就可点，没选库由入口自己拦下并弹提示，
                // 而不是把按钮一直灰着——灰着的按钮会让人以为程序坏了
                frame.clearAll();
                frame.addFiles(files);
                javax.swing.AbstractButton importBtn = null;
                for (javax.swing.AbstractButton b : frame.buttons()) {
                    if ("开始导入".equals(b.getText())) {
                        importBtn = b;
                    }
                }
                if (importBtn == null) {
                    problems.add("界面上没有「开始导入」按钮");
                } else {
                    if (frame.isConnected() || !importBtn.isEnabled()) {
                        problems.add("有表但没连库时「开始导入」应可点（与 Python 同口径），实际 enabled="
                                + importBtn.isEnabled() + " connected=" + frame.isConnected());
                    }
                    frame.clearAll();
                    if (importBtn.isEnabled()) {
                        problems.add("列表空着时「开始导入」还是可点");
                    }
                }
                // 命名开关（全拼/首字母、小写/大写）与空列按字段名定类型：勾了再添加就要当场生效。
                // 放在最后跑：它会往列表里加临时表，前面的用例都按下标取夹具表，不能被插队
                naming_switches(frame, problems);
                // 添加文件弹窗：文件名框里敲文件夹路径回车必须进目录，不能把目录当文件带回来
                folder_path_entry(problems);
                // 日志的红色与行数上限：错误没标红、或日志无限涨，都只会在现场翻车时才发现
                frame.logError("自检错误行");
                frame.log("自检普通行");
                if (!frame.logPanel().isErrorLine("自检错误行")) {
                    problems.add("logError 写出来的行没被标成错误级");
                }
                if (frame.logPanel().isErrorLine("自检普通行")) {
                    problems.add("普通日志被误标成错误级");
                }
                for (int i = 0; i < frame.logPanel().maxLines() + 50; i++) {
                    frame.log("灌行 " + i);
                }
                if (frame.logPanel().lineCount() > frame.logPanel().maxLines()) {
                    problems.add("日志没裁到上限：" + frame.logPanel().lineCount());
                }
                // 进度框按阶段计项：走三步必须三步都记，且总项数一次算准
                com.excel2mysql.gui.ProgressDialog pd =
                        new com.excel2mysql.gui.ProgressDialog(null, "自检");
                pd.setTotal(3);
                pd.step("a");
                pd.step("b");
                pd.step("c");
                if (pd.steps() != 3 || !pd.isFinished()) {
                    problems.add("进度框计项不对：steps=" + pd.steps()
                            + " finished=" + pd.isFinished());
                }
                if (pd.isCancelled()) {
                    problems.add("进度框不该初始就是取消态");
                }
                pd.dispose();
                if (problems.isEmpty()) {
                    System.out.println("+ [OK] GUI-SELFTEST | 窗口构造成功，文件表 "
                            + fileRows + " 行 / 字段合计 " + rows
                            + " 个，DDL 预览含建表语句与主键，改类型能反映到预览");
                } else {
                    for (String p : problems) {
                        System.out.println("x [ERROR] GUI-SELFTEST | " + p);
                    }
                    result[0] = 1;
                }
            } catch (Throwable e) {
                // 中途抛异常必须换成非 0 退出码：只把栈打在控制台上、闸门照样绿，比没闸门更糟
                System.out.println("x [ERROR] GUI-SELFTEST | 自检中途抛异常："
                        + e.getClass().getName() + " " + e.getMessage());
                result[0] = 1;
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("x [ERROR] GUI-SELFTEST | 被中断");
            return 1;
        }
        return result[0];
    }

    /**
     * 不弹窗（模态框会卡住自检），直接验 StructureDialog 依赖的那条链路：
     * 改 columns 里的类型 -> 重算 DDL 应该带上新的类型。
     */
    private static void table_select_for_dialog(MainFrame frame, List<String> problems) {
        com.excel2mysql.core.Infer.Column first =
                frame.tables().get(0).columns.get(0);
        String beforeType = first.type;
        String ddlBefore = frame.ddlText();
        first.type = "VARCHAR(123)";
        frame.refreshPreview();
        if (!frame.ddlText().contains("VARCHAR(123)")) {
            problems.add("改了字段类型但 DDL 预览没跟着变");
        }
        if (ddlBefore.equals(frame.ddlText())) {
            problems.add("DDL 预览在数据变了之后文本没变");
        }
        first.type = beforeType;
        frame.refreshPreview();
    }

    /**
     * 结构弹窗的三件事：标签页一张表一页、非法主键拦住且不写回、合法改动确实落库到表定义。
     * 和 Python 侧同一条约束——先收副本、全校验通过再统一写回。
     */
    private static void structure_dialog(MainFrame frame, List<String> problems) {
        java.util.List<com.excel2mysql.core.TableBuilder.Result> results = frame.results();
        if (results.size() != frame.tables().size() || results.contains(null)) {
            problems.add("results() 与表数量不一致，结构弹窗拿不到数据行");
            return;
        }
        com.excel2mysql.gui.StructureDialog dlg =
                new com.excel2mysql.gui.StructureDialog(null, results, frame.logPanel());
        if (dlg.tabTitles().size() != results.size()) {
            problems.add("结构弹窗标签页数不等于表数：" + dlg.tabTitles().size());
        }
        if (!"字段名(拼音/首字母)".equals(dlg.columnHeader(0, 1))
                || !"字段注释(中文表头)".equals(dlg.columnHeader(0, 2))) {
            problems.add("结构弹窗表头被简写了：" + dlg.columnHeader(0, 1) + " / "
                    + dlg.columnHeader(0, 2));
        }
        if (!dlg.cellEditable(0, 0, 4)) {
            problems.add("「最大字节长度」列被禁了编辑，Python 那边只是不回写");
        }
        if (!dlg.cellEditable(0, 0, 1)) {
            problems.add("字段名列不可编辑，弹窗就白摆");
        }
        // 点 X / 按 Esc 关掉要等同"取消"：没按确定就绝不能把编辑写回表定义
        com.excel2mysql.gui.StructureDialog dismissed =
                new com.excel2mysql.gui.StructureDialog(null, results, frame.logPanel());
        String commentBefore = frame.tables().get(0).columns.get(0).comment;
        dismissed.setModelValue(0, 0, 2, "改了但没按确定");
        dismissed.dispose();
        if (dismissed.isConfirmed()) {
            problems.add("没按确定却算已确认");
        }
        if (!dismissed.closesOnEscape()) {
            problems.add("Esc 没绑成\"关掉等同取消\"，Qt 的 QDialog 按 Esc 是 reject");
        }
        if (dismissed.getDefaultCloseOperation() != javax.swing.WindowConstants.DISPOSE_ON_CLOSE) {
            problems.add("点 X 的默认动作不是销毁窗口，和取消不是一回事");
        }
        if (!commentBefore.equals(frame.tables().get(0).columns.get(0).comment)) {
            problems.add("只关掉没按确定，编辑却写回了表定义");
        }
        com.excel2mysql.core.Infer.Column c0 = frame.tables().get(0).columns.get(0);
        String fieldBefore = c0.field;
        String typeBefore = c0.type;
        boolean pkBefore = c0.primaryKey;

        dlg.setModelValue(0, 0, 3, "LONGTEXT");
        dlg.setModelValue(0, 0, 0, Boolean.TRUE);
        if (dlg.pressOk()) {
            problems.add("LONGTEXT 勾成主键却被放行，主键校验没起作用");
        }
        if (frame.tables().get(0).columns.get(0) != c0) {
            problems.add("校验没过却已写回新列对象，表定义停在改了一半的状态");
        }
        if (!fieldBefore.equals(c0.field) || !typeBefore.equals(c0.type)
                || c0.primaryKey != pkBefore) {
            problems.add("校验没过但字段名/类型/主键已经被改动");
        }

        String probe = "自检注释";
        dlg.setModelValue(0, 0, 2, probe);
        dlg.setModelValue(0, 0, 3, typeBefore);
        dlg.setModelValue(0, 0, 0, Boolean.valueOf(pkBefore));
        if (!dlg.pressOk()) {
            problems.add("合法改动被拦住：" + String.join(" | ", dlg.problems()));
        } else if (!probe.equals(frame.tables().get(0).columns.get(0).comment)) {
            problems.add("弹窗确认后注释没写回表定义");
        }
    }

    private static java.util.List<String> namesOf(MainFrame frame) {
        return com.excel2mysql.core.TableOps.namesOf(frame.tables());
    }

    /** 按钮文案、前缀的幂等与跳过口径、勾选状态到 DDL 预览的传导。 */
    private static void prefix_and_labels(MainFrame frame, List<String> problems) {
        java.util.Set<String> texts = new java.util.LinkedHashSet<String>();
        for (javax.swing.AbstractButton b : frame.buttons()) {
            texts.add(b.getText());
        }
        String[] wanted = {"添加文件", "清空列表", "生成表结构预览", "预览 DDL", "清除日志",
                "批量应用前缀", "替换首个前缀", "连接并加载库", "开始导入",
                "生成标准excel文件", "生成简易excel文件"};
        for (String w : wanted) {
            if (!texts.contains(w)) {
                problems.add("界面缺少按钮：" + w);
            }
        }
        if (frame.tables().isEmpty()) {
            problems.add("前缀自检没有可改的表");
            return;
        }

        List<String> base = new ArrayList<String>();
        for (com.excel2mysql.core.TableOps.Table t : frame.tables()) {
            base.add(t.baseTableName);
        }
        frame.prefixField().setText("ods_");
        frame.applyPrefix();
        List<String> prefixed = namesOf(frame);
        for (String n : prefixed) {
            if (!n.startsWith("ods_")) {
                problems.add("应用前缀后表名没带上 ods_：" + n);
            }
        }
        if (!frame.logPanel().contains("[前缀] 已为全部表名批量添加前缀「ods_」")) {
            problems.add("批量应用前缀没有留下日志");
        }
        frame.applyPrefix();
        if (!namesOf(frame).equals(prefixed)) {
            problems.add("重复「批量应用前缀」把前缀叠上了：" + namesOf(frame));
        }
        frame.prefixField().setText("");
        frame.applyPrefix();
        if (!namesOf(frame).equals(base)) {
            problems.add("留空再点「批量应用前缀」没恢复基础表名：" + namesOf(frame));
        }
        if (!frame.logPanel().contains("[前缀] 已移除前缀，恢复基础表名")) {
            problems.add("移除前缀没有留下日志");
        }

        frame.prefixField().setText("ods_");
        frame.applyPrefix();
        List<String> beforeReplace = namesOf(frame);
        frame.prefixField().setText("change");
        frame.replaceFirstPrefix();
        List<String> afterReplace = namesOf(frame);
        if (afterReplace.equals(beforeReplace)) {
            problems.add("「替换首个前缀」一个表名都没改");
        }
        for (String n : afterReplace) {
            if (!n.startsWith("change_")) {
                problems.add("换首段后表名不是 change_ 开头：" + n);
            }
        }
        if (!frame.logPanel().contains("[换前缀完成] 共改 " + afterReplace.size() + " 张表")) {
            problems.add("替换首个前缀没有汇总改了几张表");
        }
        // 没有下划线的表名要跳过并写明，不能静默少改
        frame.prefixField().setText("ods");
        frame.applyPrefix();
        frame.prefixField().setText("change");
        int n1 = namesOf(frame).size();
        frame.replaceFirstPrefix();
        if (namesOf(frame).size() != n1) {
            problems.add("替换首段改变了表的数量");
        }

        frame.dropExistingBox().setSelected(true);
        frame.refreshPreview();
        if (!frame.ddlText().contains("DROP TABLE IF EXISTS")) {
            problems.add("勾了「覆盖已存在表」但 DDL 预览里没有 DROP");
        }
        frame.dropExistingBox().setSelected(false);
        frame.refreshPreview();
        if (frame.ddlText().contains("DROP TABLE IF EXISTS")) {
            problems.add("没勾「覆盖已存在表」DDL 预览里却出现了 DROP");
        }
    }

    /**
     * 命名开关两颗复选框 + 空列按字段名定类型，走的是界面添加文件那条真实入口。
     * Python 侧对应 M21/M22 与 B22：名字规则写在开关上，不在代码里各写一份。
     */
    private static void naming_switches(MainFrame frame, List<String> problems) {
        if (frame.initialsBox() == null || frame.upperBox() == null) {
            problems.add("界面上缺少命名开关（取拼音首字母 / 转大写）");
            return;
        }
        if (!"表名与字段名取拼音首字母".equals(frame.initialsBox().getText())
                || !"表名与字段名转大写".equals(frame.upperBox().getText())) {
            problems.add("命名开关文案不对：" + frame.initialsBox().getText() + " / "
                    + frame.upperBox().getText());
        }
        File tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-naming-selftest").toFile();
            // 1) 首字母开关：勾上后添加的文件，表名与字段名都取首字母
            File a = writeNamingFixture(tmp, "水库信息表.xlsx",
                    new String[]{"订单金额"}, new Object[]{Double.valueOf(12.5)});
            // 用 doClick 走用户真正的那条路：setSelected 不触发 ActionListener
            frame.initialsBox().doClick();
            frame.addFiles(java.util.Arrays.asList(a.getPath()));
            com.excel2mysql.core.TableOps.Table ta = lastTable(frame);
            if (ta == null || !"skxxb".equals(ta.tableName)) {
                problems.add("勾了取首字母后表名不是 skxxb：" + (ta == null ? "(无表)" : ta.tableName));
            } else if (ta.columns.isEmpty() || !"ddje".equals(ta.columns.get(0).field)) {
                problems.add("勾了取首字母后字段名不是 ddje："
                        + (ta.columns.isEmpty() ? "(无字段)" : ta.columns.get(0).field));
            }
            if (!frame.logPanel().contains("[提示] 命名规则切换为：拼音首字母")) {
                problems.add("切换首字母开关没有留下日志");
            }
            // 2) 大小写开关与首字母叠加：括号里显式写的英文表名也转大写
            File b = writeNamingFixture(tmp, "水库信息表（n_uppercheck）.xlsx",
                    new String[]{"订单金额"}, new Object[]{Double.valueOf(12.5)});
            frame.upperBox().doClick();
            frame.addFiles(java.util.Arrays.asList(b.getPath()));
            com.excel2mysql.core.TableOps.Table tb = lastTable(frame);
            if (tb == null || !"N_UPPERCHECK".equals(tb.tableName)) {
                problems.add("勾了转大写后表名不是 N_UPPERCHECK："
                        + (tb == null ? "(无表)" : tb.tableName));
            } else if (tb.columns.isEmpty() || !"DDJE".equals(tb.columns.get(0).field)) {
                problems.add("勾了转大写后字段名不是 DDJE："
                        + (tb.columns.isEmpty() ? "(无字段)" : tb.columns.get(0).field));
            }
            if (!frame.logPanel().contains("[提示] 表名与字段名大小写切换为：大写")) {
                problems.add("切换大小写开关没有留下日志");
            }
            // 3) 空模板按字段名定类型：默认小写、全拼，类型各归各位
            frame.initialsBox().doClick();
            frame.upperBox().doClick();
            File c = writeNamingFixture(tmp, "采集模板（t_empty_kinds）.xlsx",
                    new String[]{"发布日期", "更新时间", "订单金额", "数量", "备注说明"}, null);
            frame.addFiles(java.util.Arrays.asList(c.getPath()));
            com.excel2mysql.core.TableOps.Table tc = lastTable(frame);
            String[] wantTypes = {"DATE", "DATETIME", "DECIMAL(16,2)", "INT", "VARCHAR(64)"};
            if (tc == null || tc.columns.size() != wantTypes.length) {
                problems.add("空模板字段数不对：" + (tc == null ? "(无表)" : tc.columns.size()));
            } else {
                for (int i = 0; i < wantTypes.length; i++) {
                    String got = tc.columns.get(i).type;
                    if (!wantTypes[i].equals(got)) {
                        problems.add("空模板按名定类型第 " + (i + 1) + " 列期望 "
                                + wantTypes[i] + "，实际 " + got);
                    }
                }
            }
        } catch (Exception e) {
            problems.add("命名开关自检抛异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (tmp != null) {
                deleteTree(tmp);
            }
        }
    }

    /**
     * 添加文件弹窗：文件名框里敲或粘文件夹路径回车，只能进入该目录，不能把目录交给程序。
     * 自检走弹窗自己的 ApproveSelectionAction（回车与「打开」都是这一条路）；
     * 带引号那种是资源管理器「复制文件地址」的原样格式，JDK 8 会跳过目录判断直接确认，
     * 必须由 FolderAwareChooser 拦下来。默认外观与 Windows 外观在这条路上行为一致。
     */
    private static void folder_path_entry(List<String> problems) {
        File tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-chooser-selftest").toFile();
            File folder = new File(tmp, "数据目录");
            if (!folder.mkdir()) {
                problems.add("文件夹路径回车自检：建临时目录失败");
                return;
            }
            File xlsx = new File(folder, "样本.xlsx");
            if (!xlsx.createNewFile()) {
                problems.add("文件夹路径回车自检：建临时文件失败");
                return;
            }
            com.excel2mysql.gui.FolderAwareChooser chooser =
                    new com.excel2mysql.gui.FolderAwareChooser();
            chooser.setMultiSelectionEnabled(true);
            final int[] approved = {0};
            chooser.addActionListener(e -> {
                if (javax.swing.JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) {
                    approved[0]++;
                }
            });
            javax.swing.plaf.basic.BasicFileChooserUI ui =
                    (javax.swing.plaf.basic.BasicFileChooserUI) chooser.getUI();

            // 1) 带引号的绝对路径：不拦就会带着目录关闭弹窗
            chooser.setCurrentDirectory(tmp);
            ui.setFileName("\"" + folder.getAbsolutePath() + "\"");
            if (ui.getFileName() == null || ui.getFileName().indexOf('"') < 0) {
                problems.add("文件夹路径回车自检：当前外观没接住文件名输入，用例不成立");
                return;
            }
            approveChooser(chooser, ui);
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("带引号的文件夹路径回车后没进入该目录："
                        + chooser.getCurrentDirectory());
            }
            if (approved[0] != 0) {
                problems.add("带引号的文件夹路径被当成文件交出去了");
            }

            // 2) 不带引号：JDK 本来就会进目录，加了覆盖层后不能反而变成确认
            approved[0] = 0;
            chooser.setCurrentDirectory(tmp);
            ui.setFileName(folder.getAbsolutePath());
            approveChooser(chooser, ui);
            if (!folder.equals(chooser.getCurrentDirectory())) {
                problems.add("文件夹路径回车后没进入该目录：" + chooser.getCurrentDirectory());
            }
            if (approved[0] != 0) {
                problems.add("文件夹路径被当成文件交出去了");
            }

            // 3) 真选文件不受影响：该确认就确认，路径原样交出去
            approved[0] = 0;
            chooser.setCurrentDirectory(folder);
            ui.setFileName(xlsx.getAbsolutePath());
            approveChooser(chooser, ui);
            if (approved[0] != 1) {
                problems.add("正常 Excel 文件没有放行：approved=" + approved[0]);
            }
            File[] picked = chooser.getSelectedFiles();
            if (picked == null || picked.length != 1 || !xlsx.equals(picked[0])) {
                problems.add("正常 Excel 文件交出去的路径不对");
            }
        } catch (Exception e) {
            problems.add("文件夹路径回车自检抛异常：" + e);
        } finally {
            if (tmp != null) {
                deleteTree(tmp);
            }
        }
    }

    private static void approveChooser(javax.swing.JFileChooser chooser,
                                       javax.swing.plaf.basic.BasicFileChooserUI ui) {
        ui.getApproveSelectionAction().actionPerformed(
                new java.awt.event.ActionEvent(chooser,
                        java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
    }

    private static com.excel2mysql.core.TableOps.Table lastTable(MainFrame frame) {
        List<com.excel2mysql.core.TableOps.Table> all = frame.tables();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static File writeNamingFixture(File dir, String name, String[] headers,
                                           Object[] values) throws Exception {
        org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet("数据");
        org.apache.poi.ss.usermodel.Row head = sh.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            head.createCell(i).setCellValue(headers[i]);
        }
        if (values != null) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(1);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof Number) {
                    row.createCell(i).setCellValue(((Number) values[i]).doubleValue());
                } else {
                    row.createCell(i).setCellValue(String.valueOf(values[i]));
                }
            }
        }
        File f = new File(dir, name);
        writeWorkbook(wb, f);
        return f;
    }

    private static void drop_and_progress(MainFrame frame, List<String> files,
                                          List<String> problems) {
        com.excel2mysql.gui.DropArea drop = frame.dropArea();
        if (drop == null) {
            problems.add("界面上没有拖拽接收区");
            return;
        }
        if (!drop.acceptsDrops()) {
            problems.add("拖拽区没挂上接收器，拖文件进来不会有任何反应");
        }
        if (!drop.hintText().contains("拖到此处") || !drop.hintText().contains(".zip")) {
            problems.add("拖拽区提示没交代清楚能拖什么：" + drop.hintText());
        }
        java.awt.Image icon = frame.getIconImage();
        if (icon == null) {
            problems.add("窗口图标没设上，任务栏里只会是默认图");
        } else if (icon.getWidth(null) < 64) {
            problems.add("窗口图标取小了，任务栏会糊：" + icon.getWidth(null) + "x"
                    + icon.getHeight(null));
        }
        // 窗口尺寸：客户区不得小于设计值（内容要摆得开），整窗必须落在屏幕可用区内
        // （首次打开就能看见全部文字与按钮，老屏上也不会被任务栏吃掉半截）
        java.awt.Rectangle work = com.excel2mysql.gui.Windows.workArea(frame);
        java.awt.Dimension client = frame.getContentPane().getSize();
        if (client.width < 1100 || client.height < 780) {
            problems.add("客户区比设计的 1100x780 还小：" + client);
        }
        if (!work.contains(frame.getBounds())) {
            problems.add("主窗口超出屏幕可用区：" + frame.getBounds() + " 可用 " + work);
        }
        checkPopupWrap(problems);
        // 故意要一个装不下的尺寸：夹取逻辑没生效的话，这条会红
        // （本机屏幕大，"没超屏"那种断言在这台机器上咬不住东西）
        javax.swing.JFrame huge = new javax.swing.JFrame("夹取检查");
        huge.getContentPane().add(new javax.swing.JLabel("占位"));
        com.excel2mysql.gui.Windows.clientSize(huge.getContentPane(), huge, 99999, 99999);
        if (!work.contains(huge.getBounds())) {
            problems.add("要求 99999 宽时没夹回屏幕可用区：" + huge.getBounds()
                    + " 可用 " + work);
        }
        huge.dispose();
        for (java.awt.Window dlg : fitCheckDialogs(frame)) {
            try {
                if (!work.contains(dlg.getBounds())) {
                    problems.add("弹窗超出屏幕可用区：" + dlg.getClass().getSimpleName()
                            + " " + dlg.getBounds() + " 可用 " + work);
                }
                java.awt.Dimension dc = ((java.awt.Container) dlg).getSize();
                if (dc.width < 1 || dc.height < 1) {
                    problems.add("弹窗没算出尺寸：" + dlg.getClass().getSimpleName() + dc);
                }
                java.awt.Container dcontent = (java.awt.Container) dlg;
                java.awt.Dimension dmin = dcontent.getMinimumSize();
                if (dc.width < dmin.width || dc.height < dmin.height) {
                    problems.add("弹窗比内容要求的最小尺寸还小，文字会被裁："
                            + dlg.getClass().getSimpleName() + " 实测 " + dc
                            + " 最小 " + dmin);
                }
            } finally {
                dlg.dispose();
            }
        }
        if (files.isEmpty()) {
            problems.add("自检没有夹具可拖");
            return;
        }
        int before = frame.tables().size();
        List<String> one = new ArrayList<String>();
        one.add(files.get(0));
        if (frame.acceptedDrops(one).size() != 1) {
            problems.add("拖拽把 xlsx 挡在了外面");
        }
        // 夹具在第一屏已经全部加载过，再拖一次走的是"重复跳过"那条路
        String dupSummary = frame.addFiles(one);
        if (frame.tables().size() != before) {
            problems.add("重复添加把同一张表加了第二遍：" + before + " → " + frame.tables().size());
        }
        if (!frame.logPanel().contains("[重复跳过]")) {
            problems.add("重复添加没有说明为什么跳过");
        }
        if (!dupSummary.contains("另有 1 项重复已跳过")) {
            problems.add("添加结果汇总没把重复项算进去：" + dupSummary.replace((char) 10, ' '));
        }
        List<String> junk = new ArrayList<String>();
        junk.add(new java.io.File("readme.txt").getPath());
        if (!frame.acceptedDrops(junk).isEmpty()) {
            problems.add("txt 混进了拖拽接收列表");
        }
        if (!frame.logPanel().contains("拖入的不是 Excel 或 ZIP 文件，已忽略")) {
            problems.add("拖进非 Excel 文件时没有任何说明");
        }

        frame.setPageProgress(37);
        if (frame.pageProgressValue() != 37) {
            problems.add("页内进度条没跟上：" + frame.pageProgressValue());
        }
        frame.clearAll();
        if (frame.pageProgressValue() != 0 || !frame.tables().isEmpty()) {
            problems.add("「清空列表」没把进度条归零或没清空表");
        }

        // 占位提示必须是空态就能看见的灰字，不是只有悬停才出的 tooltip
        String excelHint = frame.prefixField().hintText();
        if (!excelHint.contains("可选，如 dim_") || !excelHint.contains("替换首个前缀")) {
            problems.add("Excel 页前缀框没有占位提示：" + excelHint);
        }
        frame.prefixField().setText("");
        if (!frame.prefixField().shouldPaintHint()) {
            problems.add("前缀框空着却没该画占位");
        }
        frame.prefixField().setText("ods_");
        if (frame.prefixField().shouldPaintHint()) {
            problems.add("已经有内容还画占位，会盖住用户写的字");
        }
        frame.prefixField().setText("");
        String sqlHint = frame.sqlPage().prefixField().hintText();
        if (!sqlHint.contains("如 dim_") || !sqlHint.contains("change_t_order")) {
            problems.add("SQL 页前缀框没有占位提示：" + sqlHint);
        }
        if (frame.databaseChooserEnabled()) {
            problems.add("还没连接就能选库，库名只能靠猜");
        }
        if (!frame.excelHintText().contains("未选择目标数据库") || frame.excelHintIsOk()) {
            problems.add("未选库时 Excel 页提示条没按红级说明：" + frame.excelHintText());
        }
        if (!frame.sqlPage().hintText().equals(frame.excelHintText())
                || !frame.runDbHintText().equals(frame.excelHintText())) {
            problems.add("三页的提示条说的话不一样，用户会以为连的不是同一个库");
        }
        frame.setSelectedDatabase("testdb");
        if (!frame.excelHintText().contains("当前目标库：testdb（127.0.0.1:3306）")
                || !frame.excelHintText().contains("Excel 数据将导入到这个库")
                || !frame.excelHintIsOk()) {
            problems.add("选定库之后 Excel 页提示条不对：" + frame.excelHintText());
        }
        if (!frame.sqlPage().hintText().contains("SQL 脚本将执行到这个库")
                || !frame.sqlPage().hintIsOk()) {
            problems.add("SQL 页提示条没跟着联动：" + frame.sqlPage().hintText());
        }
        if (!frame.runDbHintText().contains("编辑区里的 SQL 将执行到这个库")
                || !frame.runDbHintText().contains("testdb") || !frame.runDbHintIsOk()) {
            problems.add("SQL 执行器页的当前库条不对：" + frame.runDbHintText());
        }
        frame.hostField().setText("10.0.0.9");
        if (!frame.excelHintText().contains("10.0.0.9:3306")
                || !frame.runDbHintText().contains("10.0.0.9:3306")) {
            problems.add("改了主机但提示条还是旧地址：" + frame.excelHintText());
        }
        frame.hostField().setText("127.0.0.1");
        // 提示条要能真的按住拖选再复制（Python 那边是开了 TextSelectableByMouse 的 QLabel）
        dragSelectable(frame.excelHint(), "Excel 页提示条", problems);
        dragSelectable(frame.sqlPage().hintField(), "SQL 页提示条", problems);
        dragSelectable(frame.runDbHint(), "SQL 执行器页提示条", problems);
    }

    /**
     * 拖选复制的取证：运行时判"它是不是文本组件、能不能选出整行"，
     * 不写成编译期依赖——换成 JLabel 后要的是这条断言变红，不是整包编不过。
     */
    private static void dragSelectable(java.awt.Component c, String who, List<String> problems) {
        if (!(c instanceof javax.swing.text.JTextComponent)) {
            problems.add(who + "不是可选字的文本组件，拖选复制根本做不到：" + c.getClass().getName());
            return;
        }
        javax.swing.text.JTextComponent t = (javax.swing.text.JTextComponent) c;
        if (t.isEditable()) {
            problems.add(who + "还能编辑，那是输入框不是说明条");
        }
        t.selectAll();
        if (t.getSelectedText() == null || !t.getSelectedText().equals(t.getText())) {
            problems.add(who + "选不出整行文字：" + t.getSelectedText());
        }
    }

    private static void import_finish(MainFrame frame, List<String> files, List<String> problems) {
        if (files.size() < 2) {
            problems.add("导入收尾自检需要两个夹具文件");
            return;
        }
        List<String> two = new ArrayList<String>();
        two.add(files.get(0));
        two.add(files.get(1));
        frame.addFiles(two);
        if (frame.tables().size() != 2) {
            problems.add("收尾自检前应有 2 张表，实际 " + frame.tables().size());
            return;
        }
        String f0 = frame.tables().get(0).filepath;
        String f1 = frame.tables().get(1).filepath;
        List<MainFrame.ImportOutcome> outs = new ArrayList<MainFrame.ImportOutcome>();
        outs.add(new MainFrame.ImportOutcome(f0, true));
        outs.add(new MainFrame.ImportOutcome(f1, false));
        String summary = frame.finishImport(outs, false);
        if (!summary.contains("成功 1 张表，失败 1 张表")) {
            problems.add("收尾汇总文案不对：" + summary.replace((char) 10, ' '));
        }
        if (!frame.logPanel().contains("===== 全部完成：成功 1 张表，失败 1 张表 =====")) {
            problems.add("收尾没有写汇总日志行");
        }
        if (!frame.logPanel().isErrorLine("===== 全部完成")) {
            problems.add("有失败时汇总行没标成错误级");
        }
        if (!frame.logPanel().contains("[清理] 已从待导入列表移除成功文件")) {
            problems.add("成功文件移出列表没有留痕");
        }
        if (!frame.logPanel().contains("[保留] 导入失败的文件仍在列表中")) {
            problems.add("失败文件保留没有说明原因");
        }
        if (frame.tables().size() != 1 || !f1.equals(frame.tables().get(0).filepath)) {
            problems.add("收尾后剩下的应该是失败那张表");
        }
        if (frame.fileModel().getRowCount() != 1) {
            problems.add("文件表没跟着移出，还剩 " + frame.fileModel().getRowCount() + " 行");
        }
        if (frame.pageProgressValue() != 0) {
            problems.add("收尾后进度条没归零：" + frame.pageProgressValue());
        }

        frame.clearAll();
        frame.addFiles(two);
        String a = frame.tables().get(0).filepath;
        String b = frame.tables().get(1).filepath;
        List<MainFrame.ImportOutcome> allOk = new ArrayList<MainFrame.ImportOutcome>();
        allOk.add(new MainFrame.ImportOutcome(a, true));
        allOk.add(new MainFrame.ImportOutcome(b, true));
        frame.finishImport(allOk, false);
        if (!frame.tables().isEmpty() || frame.fileModel().getRowCount() != 0) {
            problems.add("全部成功后列表却没清空");
        }
        // 日志是累加的，所以这里按第二场自己的汇总行判：零失败不该被标成错误级
        if (!frame.logPanel().contains("===== 全部完成：成功 2 张表，失败 0 张表 =====")) {
            problems.add("全成功场景没有写汇总行");
        }
        if (frame.logPanel().isErrorLine("成功 2 张表，失败 0 张表")) {
            problems.add("零失败的汇总行被标成了错误级");
        }
        frame.clearAll();
    }

    /**
     * 「开始导入」的两道闸：列表空着不管（按钮本来就是灰的），没选库要弹 NO_DB_HINT。
     * 两条都断"任务位还得回来"：早先在抢到位之后 return 却没 endTask，
     * 一次拦下就把之后每个按钮都变成"已有任务在跑"。
     */
    private static void import_guard(MainFrame frame, List<String> files, List<String> problems) {
        frame.clearAll();
        frame.setSelectedDatabase("");
        frame.startImport();
        if (frame.taskBusy()) {
            problems.add("列表空着点导入却抢了任务位");
        }
        frame.addFiles(files);
        frame.startImport();
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("未选择目标数据库")) {
            problems.add("没选库点导入要弹 NO_DB_HINT，实际标题=" + frame.lastDialogTitle()
                    + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
        }
        if (!frame.logPanel().contains("已拦下：未选择目标数据库")) {
            problems.add("拦下导入只弹窗没写日志，回头查不到");
        }
        if (frame.taskBusy()) {
            problems.add("拦下之后任务位没还回来，之后每个按钮都会被吃掉");
            // 抢着不放正是要抓的缺陷，但别让它把后面所有用例一起带崩：
            // 失败报告要只说这一条，而不是后面一屏连锁异常
            frame.endTask();
        }
        frame.clearAll();
    }

    /** 把界面上所有静态文字收出来：状态栏这类自造文案一长回来就要被抓到。 */
    private static void collectLabels(java.awt.Container c, List<String> out) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof javax.swing.JLabel) {
                String t = ((javax.swing.JLabel) child).getText();
                if (t != null && !t.isEmpty()) {
                    out.add(t);
                }
            }
            if (child instanceof java.awt.Container) {
                collectLabels((java.awt.Container) child, out);
            }
        }
    }

    private static void writeText(String path, String text) {        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(path),
                    text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("自检写夹具失败：" + path, e);
        }
    }

    /** 第三个页签「SQL 执行器」：常驻可见量、示例清单的一来一回与空输入那道闸。 */
    private static void sql_run_tab(MainFrame frame, List<String> problems) {
        List<String> titles = frame.tabTitles();
        if (titles.size() != 3 || !"SQL 执行器".equals(titles.get(2))) {
            problems.add("第三个页签不对：" + titles);
        }
        if (!"直接输入 SQL 执行：建库、建表、改结构、查元数据都行；一次可以写多条，用分号分隔"
                .equals(frame.sqlRunHintText())) {
            problems.add("「SQL 执行器」的提示文案不对：" + frame.sqlRunHintText());
        }
        if (!"nowrap".equals(frame.sqlRunWrap())) {
            problems.add("SQL 编辑区应不折行（折行后分号要数着看），实际 " + frame.sqlRunWrap());
        }
        // 结果集是按显示宽度排出来的表格，字体不等宽就错行
        if (!"Monospaced".equals(frame.sqlRunLogPanel().getFont().getFamily())) {
            problems.add("结果区不是等宽字体：" + frame.sqlRunLogPanel().getFont().getFamily());
        }
        if (!"SELECT 1 AS 连通检查;".equals(frame.sqlRunInputText())) {
            problems.add("编辑区开场不是那句连通检查：" + frame.sqlRunInputText());
        }
        javax.swing.JButton clearInputBtn = frame.sqlRunClearInputButton(),
                clearLogBtn = frame.sqlRunClearLogButton(),
                execBtn = frame.sqlRunButton(), helpBtn = frame.sqlRunHelpButton();
        if (clearInputBtn == null || clearLogBtn == null || execBtn == null || helpBtn == null
                || !"示例 (?)".equals(helpBtn.getText()) || !"执行".equals(execBtn.getText())
                || !"清空编辑区".equals(clearInputBtn.getText())
                || !"清空结果区".equals(clearLogBtn.getText())) {
            problems.add("「SQL 执行器」的四颗按钮不齐（示例 (?) / 执行 / 清空编辑区 / 清空结果区）");
        }
        if (execBtn != null && !execBtn.getToolTipText().contains("中途出错整批回滚")) {
            problems.add("「执行」按钮没把事务语义写在 tooltip 上：" + execBtn.getToolTipText());
        }
        // 空输入必须当场拦下：既不连库也不起任务，日志与弹窗同一句
        frame.setSqlRunInputText("");
        frame.runSqlFromUi();
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("编辑区是空的")) {
            problems.add("空编辑区没拦下：标题=" + frame.lastDialogTitle()
                    + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
        }

        // 示例清单：条数、条目文案、选中切换预览
        if (frame.sqlSamplesCount() != 30) {
            problems.add("SQL 示例不是 30 条：" + frame.sqlSamplesCount());
        }
        for (int i = 0; i < frame.sqlSamplesCount(); i++) {
            String sql = frame.sqlSampleSql(i);
            if (sql == null || sql.trim().isEmpty()
                    || com.excel2mysql.core.SqlScript.splitStatements(sql).isEmpty()) {
                problems.add("第 " + (i + 1) + " 条示例切不出语句：" + sql);
            }
        }
        frame.showSqlSamples();
        if (!frame.sqlSamplesShowing()) {
            problems.add("「示例 (?)」点了没开出对话框");
        }
        if (!"SQL 示例（30 条）".equals(frame.sqlSamplesTitle())) {
            problems.add("示例对话框标题不带条数：" + frame.sqlSamplesTitle());
        }
        if (!frame.sqlSamplesItemText(0).startsWith("【")
                || !frame.sqlSamplesPreviewText().equals(frame.sqlSampleSql(0))) {
            problems.add("示例清单打开后没选中第一条：" + frame.sqlSamplesItemText(0));
        }
        frame.setSqlSamplesRow(7);
        if (!frame.sqlSamplesPreviewText().equals(frame.sqlSampleSql(7))) {
            problems.add("切换选中行，右侧语句没跟着换");
        }
        frame.clickSqlSamplesCopy();
        // 剪贴板可能被别的进程占用，两条回执都算有回执；点了之后标题一动不动才是问题
        if (!frame.sqlSamplesTitle().endsWith("已复制到剪贴板")
                && !frame.sqlSamplesTitle().endsWith("剪贴板正被占用，可再点一次）")) {
            problems.add("复制到剪贴板没有任何回执：" + frame.sqlSamplesTitle());
        }
        // 插入：编辑区还是默认那句时直接换成正题；已有内容时补分号换行再接
        frame.showSqlSamples();
        frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
        frame.setSqlSamplesRow(3);
        frame.clickSqlSamplesInsert();
        if (!frame.sqlRunInputText().equals(frame.sqlSampleSql(3))) {
            problems.add("默认文本上插入没换成正题：" + frame.sqlRunInputText());
        }
        if (frame.sqlSamplesShowing()) {
            problems.add("插入之后示例对话框应收起");
        }
        frame.showSqlSamples();
        frame.setSqlRunInputText("SELECT 1");
        frame.setSqlSamplesRow(1);
        frame.clickSqlSamplesInsert();
        if (!frame.sqlRunInputText().startsWith("SELECT 1;")
                || !frame.sqlRunInputText().endsWith(frame.sqlSampleSql(1))) {
            problems.add("已有内容时插入没补分号或覆盖了原句：" + frame.sqlRunInputText());
        }
        // 两颗清空各管一边：清结果区不该带走敲了一半的语句，清编辑区不该抹掉上次的结果
        frame.sqlRunLogPanel().log("上一次留下的结果");
        int seeded = frame.sqlRunLogPanel().lineCount();
        frame.setSqlRunInputText("SELECT 2;");
        if (clearInputBtn != null) {
            clearInputBtn.doClick();
        }
        if (!frame.sqlRunInputText().isEmpty()
                || frame.sqlRunLogPanel().lineCount() != seeded) {
            problems.add("「清空编辑区」没只清编辑区：编辑区=" + frame.sqlRunInputText()
                    + " 结果区行数=" + frame.sqlRunLogPanel().lineCount() + " / " + seeded);
        }
        frame.setSqlRunInputText("SELECT 3;");
        if (clearLogBtn != null) {
            clearLogBtn.doClick();
        }
        if (!"SELECT 3;".equals(frame.sqlRunInputText())
                || frame.sqlRunLogPanel().lineCount() != 0) {
            problems.add("「清空结果区」没只清结果区：编辑区=" + frame.sqlRunInputText()
                    + " 结果区行数=" + frame.sqlRunLogPanel().lineCount());
        }
        if (clearInputBtn != null && !clearInputBtn.getToolTipText().contains("右侧结果区不动")
                || clearLogBtn != null && !clearLogBtn.getToolTipText().contains("左边编辑区不动")) {
            problems.add("两颗清空的 tooltip 没说清各自管哪一边");
        }
        frame.showSqlSamples();
        frame.setSqlRunInputText("SELECT 1 AS 连通检查;");
    }

    /**
     * 「SQL 脚本改名入库」页那两颗生成按钮的自检：解析→导出→回读，全程离线。
     *
     * 用一个新的 SqlPage 实例而不是复用 sql_page 那个：那份页面已经被前面的读取/改名
     * 用例改过状态，混在一起查就分不清"这一条日志是刚才哪一步写的"。
     */
    private static void sql_gen_excel(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-sqlgen").toFile();
        } catch (java.io.IOException e) {
            problems.add("建生成检查的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        java.io.File script = new java.io.File(dir, "gen.sql");
        writeText(script.getPath(),
                "CREATE TABLE `t_gen` (" + nl
                        + "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键'," + nl
                        + "  `name` varchar(16) NOT NULL COMMENT '名称'," + nl
                        + "  `st` enum('a','b') DEFAULT 'a'," + nl
                        + "  PRIMARY KEY (`id`)" + nl
                        + ") ENGINE=InnoDB COMMENT='生成检查表';" + nl
                        + "CREATE TABLE t_dup2 (x INT);" + nl
                        + "CREATE TABLE t_dup2 (y INT);" + nl
                        + "CREATE TABLE t_like2 LIKE t_dup2;" + nl);

        com.excel2mysql.gui.SqlPage page = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        page.addFiles(paths);
        // 任务体的日志走的是主窗口那份 SQL 页的面板（runSqlTask 按页归属路由日志，
        // 只有自检才会另开一个 SqlPage），所以这些行要从 frame.sqlPage() 那边读。
        com.excel2mysql.gui.LogPanel taskLog = frame.sqlPage().logPanel();

        javax.swing.JButton std = page.genStdButton();
        javax.swing.JButton simple = page.genSimpleButton();
        if (std == null || simple == null) {
            problems.add("SQL 页没有「生成标准excel文件」「生成简易excel文件」按钮");
            return;
        }
        if (!"生成标准excel文件".equals(std.getText())
                || !"生成简易excel文件".equals(simple.getText())) {
            problems.add("两颗生成按钮的文案与 Excel 页不一致：" + std.getText()
                    + " / " + simple.getText());
        }
        if (!std.isEnabled() || !simple.isEnabled()) {
            problems.add("已有脚本时两颗生成按钮还是灰的");
        }

        String outRoot = new java.io.File(dir, "out").getPath();
        page.generateExcel(true, outRoot);
        String log = taskLog.text() + page.logPanel().text();
        if (!log.contains("! [WARN] 说明 | `t_gen`：列 id 的")
                || !log.contains("（丢了 UNSIGNED）")) {
            problems.add("近似掉的 UNSIGNED 没在日志里点名："
                    + tailOf(log).replace((char) 10, (char) 32));
        }
        if (!log.contains("列 st 的") || !log.contains("取值列表 'a','b'")) {
            problems.add("ENUM 列被近似却没说明");
        }
        if (!log.contains("表名 t_dup2 在本脚本里被定义了多次")) {
            problems.add("同名表第二处定义没说明只按第一处导出");
        }
        if (!log.contains("后面没有列定义")) {
            problems.add("LIKE 那条没有列定义的表没说明被跳过");
        }
        if (!log.contains("===== 生成完成：2/2 个文件")) {
            problems.add("收尾行没报出写成数："
                    + tailOf(log).replace((char) 10, (char) 32));
        }
        if (!"生成结果".equals(frame.lastDialogTitle())) {
            problems.add("生成完成没弹窗回执：" + frame.lastDialogTitle());
        }

        java.io.File stdDir = new java.io.File(outRoot, "meta_data_file_" + today());
        String[] produced = stdDir.list();
        java.util.Arrays.sort(produced == null ? new String[0] : produced);
        // 只比"是哪两个文件"不比排序后的先后：文件名里带中文，
        // Java 的字典序与 Python 的不是一套，比顺序会造出假红
        java.util.Set<String> producedSet = produced == null
                ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(java.util.Arrays.asList(produced));
        if (producedSet.size() != 2
                || !producedSet.contains("生成检查表（t_gen）.xlsx")
                || !producedSet.contains("t_dup2.xlsx")) {
            problems.add("标准件目录里的文件不对：" + java.util.Arrays.toString(produced));
        }
        String genPath = new java.io.File(stdDir, "生成检查表（t_gen）.xlsx").getPath();
        try {
            List<com.excel2mysql.core.MetaExport.Dump> dumps =
                    com.excel2mysql.core.MetaExport.dump(genPath);
            if (dumps.size() != 2 || !"元数据".equals(dumps.get(0).sheet)
                    || !"数据".equals(dumps.get(1).sheet)) {
                problems.add("标准件应是元数据 + 数据两个 Sheet：" + sheetsOf(dumps));
            }
            if (dumps.size() > 1 && dumps.get(1).cells.size() != 1) {
                problems.add("脚本里没有数据行，数据 Sheet 却不止表头那一行："
                        + (dumps.get(1).cells.size() - 1) + " 行");
            }
            com.excel2mysql.core.TableBuilder.Result back =
                    new com.excel2mysql.core.TableBuilder(
                            new com.excel2mysql.core.Infer(frame.names()),
                            frame.names()).build(genPath);
            if (!"structured".equals(back.table.source)) {
                problems.add("标准件回读没走结构定义那条路");
            }
            if (back.table.columns.size() != 3) {
                problems.add("回读列数应为 3，实际 " + back.table.columns.size());
            }
            com.excel2mysql.core.Infer.Column c0 =
                    back.table.columns.isEmpty() ? null : back.table.columns.get(0);
            if (c0 == null || !"BIGINT(20)".equals(c0.type) || !c0.primaryKey) {
                problems.add("回读的主键列不对：" + (c0 == null ? "没有列" : c0.field + " " + c0.type));
            }
            if (back.table.columns.size() == 3
                    && !"TEXT".equals(back.table.columns.get(2).type)) {
                problems.add("ENUM 列近似成 TEXT 后回读不是 TEXT："
                        + back.table.columns.get(2).type);
            }
            if (!"生成检查表".equals(back.table.tableComment)) {
                problems.add("表注释没跟着进交付件：" + back.table.tableComment);
            }
        } catch (Exception e) {
            problems.add("标准件读不回来：" + e);
        }

        page.generateExcel(false, outRoot);
        java.io.File simpleDir = new java.io.File(outRoot, "simple_meta_data_file_" + today());
        String simplePath = new java.io.File(simpleDir, "生成检查表（t_gen）.xlsx").getPath();
        try {
            List<com.excel2mysql.core.MetaExport.Dump> dumps =
                    com.excel2mysql.core.MetaExport.dump(simplePath);
            if (!"[数据]".equals(sheetsOf(dumps))) {
                problems.add("简易件应只有「数据」一个 Sheet：" + sheetsOf(dumps));
            }
        } catch (Exception e) {
            problems.add("简易件读不回来：" + e);
        }

        // 空列表时点这两颗按钮要拦下来并说清楚，不是静默什么都不做
        com.excel2mysql.gui.SqlPage empty = new com.excel2mysql.gui.SqlPage(frame);
        empty.generateExcel(true, outRoot);
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("请先添加 SQL 脚本文件。")) {
            problems.add("没有脚本时点生成按钮没拦下：标题=" + frame.lastDialogTitle());
        }
    }

    private static String tailOf(String text) {
        int from = Math.max(0, text.length() - 300);
        return text.substring(from);
    }

    private static void sql_page(MainFrame frame, List<String> problems) {
        List<String> titles = frame.tabTitles();
        if (!titles.contains("Excel 导入") || !titles.contains("SQL 脚本改名入库")) {
            problems.add("页签不齐全：" + titles);
        }
        com.excel2mysql.gui.SqlPage page = frame.sqlPage();
        if (page == null) {
            problems.add("界面上没有「SQL 脚本改名入库」页");
            return;
        }
        // 分栏配置照 Python：把手 6px、余量 1:3 给日志、初始 260
        if (page.splitDividerSize() != 6) {
            problems.add("SQL 页分栏把手宽度不是 6px：" + page.splitDividerSize());
        }
        if (Math.abs(page.splitResizeWeight() - 0.25) > 0.001) {
            problems.add("SQL 页分栏拉伸比例不对，应为 1:3：" + page.splitResizeWeight());
        }
        if (page.splitDividerLocation() != 260) {
            problems.add("SQL 页分栏初始位置不是 260：" + page.splitDividerLocation());
        }
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-sql-selftest").toFile();
        } catch (java.io.IOException e) {
            problems.add("建临时脚本目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        String a = new java.io.File(dir, "a.sql").getPath();
        String b = new java.io.File(dir, "b.sql").getPath();
        writeText(a, "CREATE TABLE t_order (id INT);" + nl
                + "INSERT INTO `t_order` VALUES (1);" + nl
                + "CREATE TABLE plain (id INT);" + nl);
        writeText(b, "CREATE TABLE dim_x (id INT);" + nl);
        List<String> paths = new ArrayList<String>();
        paths.add(a);
        paths.add(b);

        page.addFiles(paths);
        if (page.entryCount() != 2) {
            problems.add("两个脚本应全部加载成功，实际并入 " + page.entryCount() + " 个");
        }
        // 四个 SQL 任务都要走主窗口的任务位，标题得是 Python 的那句原话
        if (!"正在读取 SQL 脚本…".equals(frame.lastTaskTitle())) {
            problems.add("读取任务标题不对：" + frame.lastTaskTitle());
        }
        if (page.rowCount() != 3) {
            problems.add("表格按表名出行，应有 3 行，实际 " + page.rowCount());
        }
        if (!page.logPanel().contains("[已加载] a.sql：2 个表")) {
            problems.add("加载日志没报每个文件的表数");
        }
        // 任务日志按页分家：SQL 页读盘的那几行不该串到 Excel 页面板去
        if (frame.logPanel().contains("[已加载] a.sql")) {
            problems.add("SQL 页读取任务的日志漏到了 Excel 页");
        }
        page.addFiles(paths);
        if (!page.logPanel().contains("[跳过] 已在列表：a.sql")) {
            problems.add("重复添加同一文件没有跳过");
        }
        if (page.entryCount() != 2) {
            problems.add("重复添加把文件加了两遍");
        }
        if (page.applyPrefix() == null) {
            problems.add("空前缀没被拦下");
        }

        page.setPrefixText("ods_");
        if (page.applyPrefix() != null) {
            problems.add("合法前缀被拦住了");
        }
        if (!"正在改写脚本中的表名…".equals(frame.lastTaskTitle())) {
            problems.add("加前缀任务标题不对：" + frame.lastTaskTitle());
        }
        String text = page.textOf(a);
        if (!text.contains("ods_t_order") || !text.contains("ods_plain")) {
            problems.add("加前缀没改到表名：" + text);
        }
        if (!page.isModified(a)) {
            problems.add("改过却没标记成已修改，「保存修改后脚本」会漏掉它");
        }
        int rowsBefore = page.rowCount();
        page.setPrefixText("ods_");
        page.applyPrefix();
        if (page.rowCount() != rowsBefore || page.textOf(a).contains("ods_ods_")) {
            problems.add("重复加同一个前缀叠了一层");
        }
        if (!page.logPanel().contains("[前缀完成] 共改写 3 个表名")) {
            problems.add("前缀完成没有汇总改写了几个表名");
        }
        if (!page.cellText(0, 3).equals("ods_t_order")) {
            problems.add("「当前表名」列没跟着改写走：" + page.cellText(0, 3));
        }
        if (!page.cellText(0, 1).equals("t_order")) {
            problems.add("「原表名」列显示的不是最初的表名：" + page.cellText(0, 1));
        }

        page.setPrefixText("change");
        if (page.replaceFirstPrefix() != null) {
            problems.add("替换首个前缀被拦住");
        }
        if (!"正在替换脚本中的表名前缀…".equals(frame.lastTaskTitle())) {
            problems.add("换前缀任务标题不对：" + frame.lastTaskTitle());
        }
        String after = page.textOf(a);
        if (!after.contains("change_t_order") || !after.contains("change_plain")) {
            problems.add("替换首段没改到表名：" + after);
        }
        if (!after.contains("INSERT INTO `change_t_order`")) {
            problems.add("INSERT 里的引用没跟着改写：" + after);
        }
        if (!page.textOf(b).contains("change_dim_x")) {
            problems.add("ods_dim_x 的首段没被换掉：" + page.textOf(b));
        }

        page.saveModified();
        if (page.isModified(a) || page.isModified(b)) {
            problems.add("两个改过的脚本都该落盘并清掉已修改标记");
        }
        if (!"正在保存修改后的脚本…".equals(frame.lastTaskTitle())) {
            problems.add("保存任务标题不对：" + frame.lastTaskTitle());
        }
        if (!page.logPanel().contains("[已保存] ")) {
            problems.add("落盘成功没有报出写了哪个文件");
        }
        String outA = com.excel2mysql.gui.SqlPage.modifiedPath(a);
        if (!new java.io.File(outA).isFile()) {
            problems.add("保存后没有生成副本：" + outA);
        }
        // 没改动时再点一次要说清楚，不能静默返回
        page.saveModified();
        if (!page.logPanel().contains("[保存] 没有修改过的脚本文件")) {
            problems.add("没有改动时保存要如实说明，不能静默");
        }
        // SQL 页任务的日志要留在 SQL 页那块面板：漏到 Excel 页就等于用户找不到刚才那步
        if (frame.logPanel().contains("[已保存] ")) {
            problems.add("SQL 页任务的日志写到了 Excel 页面板");
        }
        if (!"x/y_prefix.sql".equals(com.excel2mysql.gui.SqlPage.modifiedPath("x/y.sql"))) {
            problems.add("副本文件名规则不对：" + com.excel2mysql.gui.SqlPage.modifiedPath("x/y.sql"));
        }
        if (!"x/y_prefix.sql".equals(com.excel2mysql.gui.SqlPage.modifiedPath("x/y"))) {
            problems.add("无扩展名时副本名没补 .sql");
        }

        String hint = page.execute();
        if (hint == null || !hint.contains("未选择目标数据库")) {
            problems.add("没选库时执行应当拦下并说明：" + hint);
        }
        String summary = page.finishRun(1, 1, 0, false, "testdb@127.0.0.1:3306");
        if (!summary.contains("成功 1 个文件，失败 1 个文件")) {
            problems.add("执行收尾文案不对：" + summary.replace((char) 10, ' '));
        }
        if (!page.logPanel().isErrorLine("===== 执行完成：成功 1 个文件，失败 1 个文件")) {
            problems.add("有失败时执行收尾行没标成错误级");
        }

        page.selectRow(0);
        if (page.removeSelected() != 1) {
            problems.add("选中一行应移除它所属的整个文件");
        }
        if (page.entryCount() != 1 || page.rowCount() != 1) {
            problems.add("移除后文件与行数都不对：" + page.entryCount() + "/" + page.rowCount());
        }
        if (!page.logPanel().contains("[移除] 1 个脚本文件")) {
            problems.add("移除文件没有留痕");
        }
        deleteTree(dir);
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static void help_and_about(MainFrame frame, List<String> problems) {
        String title = frame.getTitle();
        if (!title.contains("v") || !title.contains(
                com.excel2mysql.core.AppInfo.load().versionText())) {
            problems.add("窗口标题没带版本号：" + title);
        }
        com.excel2mysql.gui.HelpDialog hd = new com.excel2mysql.gui.HelpDialog(null);
        try {
            if (hd.tocCount() != 15) {
                problems.add("说明目录应有 15 章（与 help_doc.py 同步，改了要重导 HelpDoc.java），"
                        + "实际 " + hd.tocCount());
            }
            if (hd.bodyLength() < 2000) {
                problems.add("说明正文没渲染出来，长度只有 " + hd.bodyLength());
            }
            // 按标题找而不是按序号：说明里插一章就会把序号挪位，写死序号的守卫会假红
            int excelChapter = -1;
            for (int i = 0; i < hd.tocCount(); i++) {
                if ("两类 Excel 格式".equals(hd.tocTitle(i))) {
                    excelChapter = i;
                }
            }
            if (excelChapter < 0) {
                problems.add("说明目录里没有「两类 Excel 格式」这一章");
            } else {
                hd.selectToc(excelChapter);
                if (!"excel".equals(hd.currentAnchor())) {
                    problems.add("点「两类 Excel 格式」没跳到对应锚点，锚点=" + hd.currentAnchor());
                }
            }
            hd.selectToc(0);
            if (!"start".equals(hd.currentAnchor())) {
                problems.add("跳回第一章失败，锚点=" + hd.currentAnchor());
            }
            if (!"anchor".equals(hd.jumpVia())) {
                problems.add("目录跳转不是靠锚点实现的（走的是 " + hd.jumpVia()
                        + "），说明 HTML 里的 a name 没被渲染器收下来");
            }
            // 文档漂移守卫，与 Python 侧 pack_test 的 P15/P16 同一口径：界面上每个按钮
            // 都要在说明里点名，说明里点名的按钮也必须真在界面上。两边各写各的，
            // 早晚会一个有一个没有——加了按钮忘了写说明，用户只能靠猜
            String doc = com.excel2mysql.gui.HelpDoc.html();
            java.util.Set<String> onUi = new java.util.LinkedHashSet<String>();
            for (javax.swing.AbstractButton b : frame.buttons()) {
                onUi.add(b.getText());
            }
            String[] named = {"添加文件", "批量应用前缀", "替换首个前缀", "生成表结构预览",
                    "预览 DDL", "开始导入", "添加 SQL 文件", "保存修改后脚本",
                    "执行脚本到当前库", "连接并加载库",
                    "生成标准excel文件", "生成简易excel文件", "示例 (?)",
                    "清空编辑区", "清空结果区"};
            for (String n : named) {
                if (!onUi.contains(n)) {
                    problems.add("说明里点名的按钮界面上不存在：" + n);
                }
                if (!doc.contains(n)) {
                    problems.add("按钮没被使用说明覆盖：" + n);
                }
            }
        } finally {
            hd.dispose();
        }
        com.excel2mysql.gui.AboutDialog ad = new com.excel2mysql.gui.AboutDialog(null);
        try {
            if (!ad.valueOf("version").startsWith("v")) {
                problems.add("关于框的版本行不对：" + ad.valueOf("version"));
            }
            String commit = ad.valueOf("commit");
            if (commit == null || commit.isEmpty()) {
                problems.add("关于框的提交行是空的");
            }
            if ("未知".equals(ad.valueOf("built")) && "unknown".equals(
                    com.excel2mysql.core.AppInfo.load().source)) {
                problems.add("关于框既没有构建日期也没有声明来源，用户无从判断可信度");
            }
            // 构建时间戳：Maven 打的是 UTC，Python 打的是本机时间，显示前必须换成时区
            String shifted = com.excel2mysql.core.AppInfo.toLocal("2026-10-13 02:22:08");
            java.util.Calendar utc = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            utc.set(2026, java.util.Calendar.OCTOBER, 13, 2, 22, 8);
            utc.set(java.util.Calendar.MILLISECOND, 0);
            long offset = java.util.TimeZone.getDefault().getOffset(utc.getTimeInMillis());
            if (offset != 0 && "2026-10-13 02:22:08".equals(shifted)) {
                problems.add("构建日期还是 UTC 原样，没换成本机时间（本机偏移 "
                        + (offset / 3600000) + " 小时）");
            }
            if (shifted == null || shifted.length() != 19) {
                problems.add("构建日期换算后不成样：" + shifted);
            }
            if (!"开发态（未打包）".equals(
                    com.excel2mysql.core.AppInfo.toLocal("开发态（未打包）"))) {
                problems.add("不是时间戳的构建日期被时间换算改坏了");
            }
        } finally {
            ad.dispose();
        }
    }

    /** 把夹具打进一个 zip，验展开、汇总与关窗清理这一整条路。 */
    private static void zip_roundtrip(MainFrame frame, List<String> files,
                                      List<String> problems) {
        if (files.isEmpty()) {
            problems.add("ZIP 自检没有夹具");
            return;
        }
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-zip-selftest").toFile();
        } catch (java.io.IOException e) {
            problems.add("建 ZIP 临时目录失败：" + e);
            return;
        }
        java.io.File zip = new java.io.File(dir, "pack1.zip");
        try {
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.FileOutputStream(zip));
            zos.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            zos.write("不是 Excel".getBytes(java.nio.charset.Charset.forName("UTF-8")));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("inner.xlsx"));
            byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(files.get(0)));
            zos.write(raw);
            zos.closeEntry();
            zos.close();
        } catch (Exception e) {
            problems.add("打 ZIP 夹具失败：" + e);
            deleteTree(dir);
            return;
        }
        int before = frame.tables().size();
        List<String> one = new ArrayList<String>();
        one.add(zip.getPath());
        String summary = frame.addFiles(one);
        if (frame.tables().size() != before + 1) {
            problems.add("压缩包里的 Excel 没被展开进列表：" + before + " → "
                    + frame.tables().size() + "；日志尾部：" + tail(frame.logPanel().text()));
        }
        if (!frame.logPanel().contains("[压缩包] pack1.zip：展开 1 个 Excel，忽略 1 个非 Excel 文件")) {
            problems.add("压缩包展开没报清楚（展开几个、忽略几个）");
        }
        if (!summary.contains("添加 1 项 zip 文件")) {
            problems.add("弹窗汇总没把 zip 单列一行：" + summary.replace((char) 10, ' '));
        }
        int last = frame.fileModel().getRowCount() - 1;
        String tip = frame.fileModel().tooltipAt(last);
        if (!tip.contains("pack1.zip!") || !tip.contains("inner.xlsx")) {
            problems.add("包内文件的来源 tooltip 不对：" + tip);
        }
        String looseTip = frame.fileModel().tooltipAt(0);
        if (looseTip.isEmpty() || !looseTip.contains(".xlsx")) {
            problems.add("散装文件的全路径 tooltip 缺失：" + looseTip);
        }
        String tempDir = frame.zipTempDir();
        if (tempDir == null || !new java.io.File(tempDir).isDirectory()) {
            problems.add("展开压缩包却没建会话临时目录");
        }
        frame.cleanZipTemp();
        if (tempDir != null && new java.io.File(tempDir).exists()) {
            problems.add("关窗清理没真的删掉临时目录：" + tempDir);
        }
        if (!frame.logPanel().contains("已清理解压临时目录")) {
            problems.add("清理临时目录没有留痕");
        }
        deleteTree(dir);
        frame.clearAll();
    }

    /**
     * 折行规则自己的红线：预算、行数、不丢字、截断时留话。
     *
     * 这条规则决定弹窗里到底看得见哪些字，两边各写一份迟早会飘，
     * 所以除了对撞器比整串折行结果，这边再钉一遍绝对约束。
     */
    private static void checkPopupWrap(java.util.List<String> problems) {
        // 期望值写死成契约数字：拿常量当期望值的断言是恒真的，
        // 把预算改成 400 它照样绿，洞还留着。常量本身另外钉一条。
        if (com.excel2mysql.gui.PopupText.WRAP_COLS != 56
                || com.excel2mysql.gui.PopupText.MAX_LINES != 22) {
            problems.add("折行预算与上限应当是 56 列 / 22 行，实际 "
                    + com.excel2mysql.gui.PopupText.WRAP_COLS + " 列 / "
                    + com.excel2mysql.gui.PopupText.MAX_LINES + " 行");
        }
        String longLine = "任务异常：读取 C:/数据/很长的一个文件名防空警报试鸣信息"
                + "（dim_air_raid_siren）.xlsx 时失败，内部单元格 D17 的类型与推断结果冲突，"
                + "需要人工确认这一列到底是文本还是数字，详见日志里的完整堆栈信息。";
        StringBuilder tall = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            tall.append("第 ").append(i).append(" 行：这一行本身就不短，")
                    .append("加上中文标点和路径信息会更长一些一些一些。");
            if (i < 30) {
                tall.append('\n');
            }
        }
        String[] samples = {longLine, tall.toString(), "短的一句"};
        for (String src : samples) {
            String out = com.excel2mysql.gui.PopupText.wrap(src);
            String[] lines = out.split("\n", -1);
            if (lines.length > 22) {
                problems.add("折行后仍有 " + lines.length + " 行，超过 22 行上限");
            }
            for (String ln : lines) {
                if (com.excel2mysql.gui.PopupText.width(ln) > 56) {
                    problems.add("折行后有一行超出 56 列预算：" + ln);
                }
            }
            if (lines.length == 22
                    && !lines[lines.length - 1].contains("完整内容见底部日志")) {
                problems.add("行数正好卡在 22 上限，却没告诉用户其余内容在日志里");
            }
            if (!flat(out).startsWith(flat(src).substring(0, Math.min(12,
                    flat(src).length())))) {
                problems.add("折行把开头都改动了：" + out.substring(0, Math.min(40, out.length())));
            }
        }
        // 不截断时内容必须一字不少：去掉换行要和原文对得上
        String shortOut = com.excel2mysql.gui.PopupText.wrap("第一行\n第二行");
        if (!"第一行第二行".equals(flat(shortOut))) {
            problems.add("折行丢了原有的分段：" + shortOut);
        }
    }

    /**
     * 摆出来但不开框的各弹窗：这里只量尺寸。
     *
     * 不开框是故意的——这几个窗是模态的，真开会把自检自己卡住；
     * 而客户区尺寸在构造函数的 pack 里就算完了，不必显示出来才量得到。
     */
    private static java.awt.Window[] fitCheckDialogs(MainFrame frame) {
        java.util.List<java.awt.Window> out = new java.util.ArrayList<java.awt.Window>();
        out.add(new com.excel2mysql.gui.HelpDialog(frame));
        out.add(new com.excel2mysql.gui.AboutDialog(frame));
        out.add(new com.excel2mysql.gui.ProgressDialog(null, "正在解析文件…"));
        if (!frame.results().isEmpty()) {
            out.add(new com.excel2mysql.gui.DDLDialog(frame, frame.tables(), false));
            out.add(new com.excel2mysql.gui.StructureDialog(frame, frame.results(),
                    frame.logPanel()));
        }
        return out.toArray(new java.awt.Window[out.size()]);
    }

    /** 比内容不比排版：弹窗正文按显示列宽折过行，断言里去掉换行再比。 */
    private static String flat(String s) {
        return s == null ? "" : s.replace("\n", "");
    }

    /**
     * 建不出表的输入必须卡在解析阶段。
     *
     * 现场实测抓出来的两类：一张格子都没有的表、以及扩展名是 .xlsx 但内容不是 Excel 的文件。
     * 前者从前会产出 `CREATE TABLE x ( )`，到库里变成一条 SQL 语法错误、退出码归到"导入失败"；
     * 后者连语法错误都没有，直接当空表放过。两边现在都在解析阶段就拒，这里钉住 Java 侧。
     */
    private static void checkRejects(MainFrame frame, java.util.List<String> problems) {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-reject-" + System.nanoTime());
        if (!dir.mkdirs()) {
            problems.add("建不出拒绝检查的临时目录：" + dir.getPath());
            return;
        }
        try {
            java.io.File empty = new java.io.File(dir, "空表（rej_empty）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            wb.createSheet("数据");
            writeWorkbook(wb, empty);
            java.io.File header = new java.io.File(dir, "只有表头（rej_header）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb2 =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sh = wb2.createSheet("数据");
            org.apache.poi.ss.usermodel.Row row = sh.createRow(0);
            row.createCell(0).setCellValue("编号");
            row.createCell(1).setCellValue("名称");
            writeWorkbook(wb2, header);
            java.io.File fake = new java.io.File(dir, "假excel（rej_fake）.xlsx");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(fake);
            try {
                fo.write("this is not a workbook".getBytes("UTF-8"));
            } finally {
                fo.close();
            }

            com.excel2mysql.core.TableBuilder tb = new com.excel2mysql.core.TableBuilder(
                    new com.excel2mysql.core.Infer(frame.names()), frame.names());
            expectReject(tb, empty, "没有解析到任何字段", "空表", problems);
            // 只有表头的采集模板**不该**被拒：字段是使用方设计好的表结构，一行没填不是错误。
            // 以前它和真空表一起被"一列不剩"挡掉，现场看到的就是"表头解析不到"
            com.excel2mysql.core.TableBuilder.Result hr = tb.build(header.getPath());
            boolean headNoPk = true;
            for (com.excel2mysql.core.Infer.Column c : hr.table.columns) {
                if (c.primaryKey) {
                    headNoPk = false;
                }
            }
            if (hr.table.columns.size() != 2 || !hr.rows.isEmpty() || !hr.noDataRows
                    || hr.emptyColumns.size() != 2 || !headNoPk) {
                problems.add("只有表头的采集模板应当建得出空表（2 列、0 行、两列都点名、不设主键），"
                        + "实际 列=" + hr.table.columns.size() + " 行=" + hr.rows.size()
                        + " 空列=" + hr.emptyColumns + " 主键=" + (headNoPk ? "无" : "有"));
            }
            expectReject(tb, fake, "不是可识别的 Excel 文件", "假 Excel", problems);

            // 表头整行都没写字：Python 侧 pandas 会把列宽一起丢掉，Java 读表层留着那一行。
            // 两侧要的是同一个结果——照样出表定义、字段名走空表头兜底、没有数据可插
            java.io.File blank = new java.io.File(dir, "空表头模板（rej_blankhead）.xlsx");
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb3 =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sh3 = wb3.createSheet("数据");
            org.apache.poi.ss.usermodel.Row r3 = sh3.createRow(0);
            r3.createCell(0);
            r3.createCell(1);
            r3.createCell(2);
            writeWorkbook(wb3, blank);
            com.excel2mysql.core.TableBuilder.Result br = tb.build(blank.getPath());
            java.util.List<String> bf = new java.util.ArrayList<String>();
            for (com.excel2mysql.core.Infer.Column c : br.table.columns) {
                bf.add(c.field);
            }
            boolean blankNoPk = true;
            for (com.excel2mysql.core.Infer.Column c : br.table.columns) {
                if (c.primaryKey) {
                    blankNoPk = false;
                }
            }
            if (!bf.equals(java.util.Arrays.asList("col", "col_2", "col_3"))
                    || !br.rows.isEmpty() || !br.noDataRows || !br.blankHeaders.equals(bf)
                    || !br.emptyColumns.isEmpty() || !blankNoPk) {
                problems.add("表头整行空着应当兜底出 col、col_2、col_3 三列、0 行、不设主键，"
                        + "实际 列=" + bf + " 行=" + br.rows.size()
                        + " 兜底点名=" + br.blankHeaders + " 空列=" + br.emptyColumns);
            }
        } catch (Exception e) {
            problems.add("拒绝检查本身出错：" + e);
        } finally {
            deleteTree(dir);
        }
    }

    private static void writeWorkbook(org.apache.poi.ss.usermodel.Workbook wb,
                                      java.io.File to) throws java.io.IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(to);
        try {
            wb.write(out);
        } finally {
            out.close();
        }
        wb.close();
    }

    private static void expectReject(com.excel2mysql.core.TableBuilder tb, java.io.File f,
                                     String wantInMessage, String what,
                                     java.util.List<String> problems) {
        try {
            tb.build(f.getPath());
            problems.add(what + "没被拒，反而产出了表定义：" + f.getName());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null || !e.getMessage().contains(wantInMessage)) {
                problems.add(what + " 被拒了，但原因说错了：" + e.getMessage());
            }
        } catch (Exception e) {
            problems.add(what + "抛的不是解析类异常：" + e);
        }
    }

    /**
     * 命令行那条路的 --prefix 必须真的改到脚本里落库的表名。
     *
     * 现场实测抓出来的：Java 的 CLI 从前只把前缀加在 Excel 表名上，.sql 原样执行，
     * 同一个命令 Python 线建出 ods_t_x、Java 线建出 t_x——按"加了前缀"去查表会一张也找不到。
     * 这里跑的是 Main.run 本体（dry-run，不连库），断的是那条接线本身：
     * SqlScript.applyPrefix 这个纯函数早有差分覆盖，接线断了它照样是绿的。
     */
    /**
     * 「只写注释也不算」的判据表。Python 侧 pack_test 的 E10/E11 断的是同一张表：
     * 两边判定不一样就会出现一句同样的输入，一边报"执行了 0 条"、另一边报"执行了 1 条"。
     * 字符串里的 -- 不算注释，这一格最容易写错。
     */
    /**
     * 日志面板的并发收口检查：两个线程猛加行，主线程一边读全文一边数行数。
     *
     * 现场是真的两头同时在动——导入在子线程里逐表写日志，界面上有人在翻日志。
     * 少了那把锁，ArrayDeque 会在"遍历到一半被加了行"时抛 ConcurrentModificationException；
     * 这个异常发生在事件分发线程上就会把当次渲染连同后面的刷新一起带走，
     * 用户看到的正是"进度跑到一半，日志不再动了"。
     */
    /** 自检跑在事件分发线程上，不能把 InterruptedException 抛出去，只能就地认下。 */
    private static void quietSleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void checkLogPanelThreadSafe(List<String> problems) {
        final com.excel2mysql.gui.LogPanel panel = new com.excel2mysql.gui.LogPanel();
        final java.util.concurrent.atomic.AtomicReference<String> boom =
                new java.util.concurrent.atomic.AtomicReference<String>(null);
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable writer = new Runnable() {
            @Override
            public void run() {
                try {
                    int i = 0;
                    while (!stop.get()) {
                        panel.append("并发行 " + i, i % 503 == 0);
                        i++;
                    }
                } catch (Throwable t) {
                    boom.compareAndSet(null, "写线程炸了 " + t.getClass().getName());
                }
            }
        };
        Thread a = new Thread(writer, "probe-log-a");
        Thread b = new Thread(writer, "probe-log-b");
        a.start();
        b.start();
        // 读写都得持续到"已经封顶、开始裁最早的行"之后：现场那次
        // ConcurrentModificationException 就是这么来的——遍历到一半，插入那边把 first 动了。
        // 只读到刚封顶就收工正好把最危险的那段跳过去，那样这条守卫就是空枪。
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline && panel.lineCount() < panel.maxLines()) {
            quietSleep(2);
        }
        if (panel.lineCount() < panel.maxLines()) {
            problems.add("日志四秒还没涨到上限，读写重叠没建立起来：" + panel.lineCount());
        }
        // 封顶之后再让两边对着跑一段：写侧继续加（每加一行都要裁一行），
        // 读侧全程在遍历同一份条目
        final java.util.concurrent.atomic.AtomicBoolean readStop =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        List<Thread> readers = new ArrayList<Thread>();
        for (int r = 0; r < 2; r++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!readStop.get()) {
                            panel.text();
                            panel.isErrorLine("并发行 1509");
                            panel.lineCount();
                        }
                    } catch (Throwable th) {
                        boom.compareAndSet(null, "读侧炸了 " + th.getClass().getName()
                                + "：" + String.valueOf(th.getMessage()).replace((char) 10, ' '));
                    }
                }
            }, "probe-log-read");
            readers.add(t);
            t.start();
        }
        quietSleep(1200);
        readStop.set(true);
        stop.set(true);
        for (Thread t : readers) {
            try {
                t.join(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                boom.compareAndSet(null, "读线程没收口");
            }
        }
        try {
            a.join(5000);
            b.join(5000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            boom.compareAndSet(null, "join 被中断，写线程没收口");
        }
        if (boom.get() != null) {
            problems.add("日志面板并发下不成立：" + boom.get());
        }
        if (panel.lineCount() != panel.maxLines()) {
            problems.add("日志没裁到上限：" + panel.lineCount() + " / " + panel.maxLines());
        }
        if (panel.text().split("\n").length != panel.lineCount()) {
            problems.add("text() 数出来的行数与 lineCount() 不一致："
                    + panel.text().split("\n").length + " vs " + panel.lineCount());
        }
        panel.clearLog();
        if (panel.lineCount() != 0) {
            problems.add("清空后还有 " + panel.lineCount() + " 行");
        }
    }

    private static void checkCommentOnly(List<String> problems) {
        String[][] table = {
                {"-- 只是一句注释", "0"},
                {"# 老式行注释", "0"},
                {"/* 纯块注释 */", "0"},
                {"", "0"},
                {"   \n\t ", "0"},
                {"SELECT 1", "1"},
                {"SELECT '-- x'", "1"},
                {"SELECT `--a` FROM t", "1"},
                {"/* 头注释 */ SELECT 2", "1"},
                {"-- 头注释\nINSERT INTO t VALUES (1)", "1"},
                {"SELECT 'a''b' -- 尾注释", "1"},
                {"INSERT INTO t VALUES ('/* 不是块注释 */')", "1"},
                {"/* 注释; 里有分号 */ ", "0"},
        };
        for (int i = 0; i < table.length; i++) {
            boolean got = com.excel2mysql.core.SqlScript.hasExecutableContent(table[i][0]);
            boolean want = "1".equals(table[i][1]);
            if (got != want) {
                problems.add("第 " + (i + 1) + " 格判错：" + table[i][0].replace((char) 10, ' ')
                        .replace((char) 9, 'T') + " 应为 " + want + "，实际 " + got);
            }
        }
        List<String> kept = com.excel2mysql.core.SqlScript.executableStatements(
                "-- a" + (char) 10 + "; /* b */ ; SELECT 1;");
        if (kept.size() != 1 || !"SELECT 1".equals(kept.get(0))) {
            problems.add("执行器的切句没把纯注释片段剔掉：" + kept);
        }
    }

    private static void checkSqlCliPrefix(java.util.List<String> problems) {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-sqlprefix-" + System.nanoTime());
        if (!dir.mkdirs()) {
            problems.add("建不出脚本前缀检查的临时目录：" + dir.getPath());
            return;
        }
        try {
            java.io.File sql = new java.io.File(dir, "cli_prefix_probe.sql");
            String body = "DROP TABLE IF EXISTS `t_a`;" + (char) 10
                    + "CREATE TABLE `t_a` (`id` INT NOT NULL, `v` VARCHAR(8), PRIMARY KEY (`id`));"
                    + (char) 10
                    + "INSERT INTO `t_a` VALUES (1, 'x');" + (char) 10;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(sql);
            try {
                fo.write(body.getBytes("UTF-8"));
            } finally {
                fo.close();
            }
            String with = captureMainRun(new String[]{"--dry-run", "--prefix", "clix_",
                    sql.getPath()});
            String bare = captureMainRun(new String[]{"--dry-run", sql.getPath()});
            wantIn(with, "脚本载入 | cli_prefix_probe.sql | CREATE TABLE 1 处 / 表 1 张：t_a(1)",
                    "脚本载入没报出 CREATE 处数与表名", problems);
            wantIn(with, "+ [OK] 脚本前缀 | cli_prefix_probe.sql | 改写 1 张表：t_a→clix_t_a（3 处）",
                    "CLI 没把 --prefix 用到 .sql 上（表名没改写）", problems);
            wantIn(with, "      DROP TABLE IF EXISTS `clix_t_a`",
                    "预览里打印的还是没改写的语句", problems);
            wantIn(with, "待执行 3 条语句（本次没有连库）", "脚本预览没报条数", problems);
            wantIn(with, "+ [OK] 脚本预览完成 | 共 1 个脚本未执行（本次没有连接 MySQL）",
                    "脚本预览完成没记账", problems);
            if (with.contains("+ [OK] SCRIPT |")) {
                problems.add("dry-run 把脚本执行到库了：" + tail(with));
            }
            wantIn(bare, "      DROP TABLE IF EXISTS `t_a`",
                    "不加 --prefix 时脚本原样预览失败", problems);
            if (bare.contains("脚本前缀")) {
                problems.add("没给 --prefix 却报了脚本前缀改写");
            }
        } catch (Exception e) {
            problems.add("脚本前缀检查本身出错：" + e);
        } finally {
            deleteTree(dir);
        }
    }

    private static void wantIn(String haystack, String needle, String what,
                               java.util.List<String> problems) {
        if (haystack == null || !haystack.contains(needle)) {
            problems.add(what + "；缺" + needle);
        }
    }

    /** 在进程内跑一次命令行入口并收走它的 stdout。 */
    private static String captureMainRun(String[] argv) {
        java.io.PrintStream real = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buf, true, "UTF-8"));
            Main.run(argv);
        } catch (Exception e) {
            return "!!捕获失败：" + e;
        } finally {
            System.setOut(real);
        }
        try {
            return new String(buf.toByteArray(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "!!UTF-8 不可用";
        }
    }

    /**
     * 超出 BIGINT 的整数必须原样发给库，由 MySQL 判越界。
     *
     * 生产前实测抓出来的：驱动收到 BigInteger 走的是 longValue()，
     * 9223372036854775808 被截成 -9223372036854775808 存进库，
     * 导入日志写着"成功"，数已经在库里了——这种错现场查不出来，只能事后对账才发现。
     */
    private static void checkBigIntWire(java.util.List<String> problems) {
        Object over = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("9223372036854775808"));
        if (!(over instanceof java.math.BigDecimal)) {
            problems.add("超出 BIGINT 的整数没按原值发出去，应为 BigDecimal：" + over);
        }
        if (String.valueOf(over).startsWith("-")) {
            problems.add("超出 BIGINT 的整数被截成了负数发进库：" + over);
        }
        Object in = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("12345678901234"));
        if (!Long.valueOf(12345678901234L).equals(in)) {
            problems.add("范围内的整数没走 Long 这条路：" + in);
        }
        Object max = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("9223372036854775807"));
        if (!Long.valueOf(Long.MAX_VALUE).equals(max)) {
            problems.add("BIGINT 上界被误判成越界：" + max);
        }
        Object min = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("-9223372036854775808"));
        if (!Long.valueOf(Long.MIN_VALUE).equals(min)) {
            problems.add("BIGINT 下界被误判成越界：" + min);
        }
        Object under = com.excel2mysql.core.DbClient.toJdbc(
                new java.math.BigInteger("-9223372036854775809"));
        if (!(under instanceof java.math.BigDecimal)) {
            problems.add("低于 BIGINT 下界的整数没按原值发出去：" + under);
        }
    }

    private static String tail(String text) {
        String s = text == null ? "" : text.replace((char) 10, ' ');
        return s.length() <= 260 ? s : s.substring(s.length() - 260);
    }

    private static void task_crash(MainFrame frame, List<String> problems) {
        String dialog = frame.crashMessage(new IllegalStateException("探针异常"));
        if (!dialog.contains("本次操作未完成，详见日志")) {
            problems.add("任务异常的弹窗没交代去查日志：" + dialog.replace((char) 10, ' '));
        }
        // describe 会把异常类型一起带上，只写 message 的话现场根本分不清是哪类意外
        if (!frame.logPanel().isErrorLine("[任务异常] IllegalStateException：探针异常")) {
            problems.add("任务异常没按错误级带上异常类型");
        }
        if (!frame.logPanel().contains("本次操作未完成，请查看日志后重试")) {
            problems.add("任务异常的日志没给下一步动作");
        }
        // 同一句话要经弹窗留痕：只写日志的话，走神的用户根本不知道出了意外
        if (!"任务异常".equals(frame.lastDialogTitle())) {
            problems.add("任务异常没走带标题的弹窗：" + frame.lastDialogTitle());
        }
        if (!frame.lastDialogIsError()) {
            problems.add("任务异常弹窗没用出错图标（Python 是 critical）");
        }
    }

    /** 只记账不渲染的回报口，用来验 core 里那条链的对外行为。 */
    private static final class Recorder implements com.excel2mysql.core.ImportParse.Sink {
        final List<String> lines = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
        int total = -1;
        int advanced;
        /** 大于 0 时模拟"用户按了停止"：走够这么几项就把取消位置起来。 */
        int stopAfter;

        @Override
        public void setTotal(int n) {
            total = n;
        }

        @Override
        public void step(String label) {
            advanced++;
        }

        @Override
        public void advance(int units, String label) {
            advanced += units;
        }

        @Override
        public void log(String text) {
            lines.add(text);
        }

        @Override
        public void logError(String text) {
            errors.add(text);
        }

        @Override
        public boolean cancelled() {
            return stopAfter > 0 && advanced >= stopAfter;
        }

        boolean saw(String needle, boolean errorLevel) {
            for (String s : (errorLevel ? errors : lines)) {
                if (s.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void import_run(List<String> problems) {
        com.excel2mysql.core.Config cfg = new com.excel2mysql.core.Config();
        cfg.host = "127.0.0.1";
        cfg.port = 1;
        cfg.user = "probe";
        cfg.database = "testdb";
        if (!"testdb@127.0.0.1:1".equals(com.excel2mysql.core.ImportRun.targetOf(cfg))) {
            problems.add("目标库串拼错：" + com.excel2mysql.core.ImportRun.targetOf(cfg));
        }
        cfg.database = "";
        if (!"127.0.0.1:1".equals(com.excel2mysql.core.ImportRun.targetOf(cfg))) {
            problems.add("没选库时目标库串该只写主机端口");
        }
        cfg.database = "testdb";

        if (!com.excel2mysql.core.ImportRun.headerLine(2, 7, "t_x", 3)
                .equals("===== [2/7] 处理表 `t_x`（3 行）=====")) {
            problems.add("逐表头一行不对：" + com.excel2mysql.core.ImportRun.headerLine(2, 7, "t_x", 3));
        }
        if (!com.excel2mysql.core.ImportRun.ddlLog("CREATE TABLE x (a INT);")
                .startsWith("-- 建表 DDL --")) {
            problems.add("建表语句没写进日志");
        }
        String cf = com.excel2mysql.core.ImportRun.createFailLog("t_x", "testdb@127.0.0.1:1",
                "SQLException（MySQL 错误码 1064）：语法错", "CREATE TABLE t_x (a INT);");
        if (!cf.contains("建表语句被 MySQL 拒绝") || !cf.contains("失败的建表语句")
                || !cf.contains(com.excel2mysql.core.LogText.ROLLBACK_NOTE)) {
            problems.add("建表失败的日志缺了目标库、语句原文或回滚说明");
        }
        String ifl = com.excel2mysql.core.ImportRun.importFailLog("t_x", "testdb@127.0.0.1:1",
                12, "SQLException：值太长");
        if (!ifl.contains("共 12 行数据未能写入，本表事务已回滚")
                || !ifl.contains(com.excel2mysql.core.LogText.ROLLBACK_NOTE)) {
            problems.add("导入失败没说清楚回滚范围");
        }
        if (!com.excel2mysql.core.ImportRun.importedLog("t_x", 5)
                .equals("[导入完成] `t_x` 共 5 行")) {
            problems.add("导入完成的文案不对");
        }

        Recorder rec = new Recorder();
        List<com.excel2mysql.core.TableBuilder.Result> empty =
                new ArrayList<com.excel2mysql.core.TableBuilder.Result>();
        int units = com.excel2mysql.core.ImportRun.run(cfg, empty, false, rec, null).size();
        if (units != 0) {
            problems.add("空列表不该有结果");
        }
        if (rec.total != 0) {
            problems.add("空列表的总项数应为 0，实际 " + rec.total);
        }
        if (!rec.saw("[连接失败] 无法连接到 testdb@127.0.0.1:1（用户 probe）", true)) {
            problems.add("连不上库时没写连接失败日志：" + rec.errors);
        }
    }

    /**
     * 「执行脚本到当前库」任务体的连不上库那一支：端口 1 保证秒失败，不需要真库也能把
     * 这段代码真跑一遍——断的是 connFailed 标志、计数在连库前就算准、错误行按错误级报出。
     */
    private static void sql_run_connect_failure(List<String> problems) {
        com.excel2mysql.core.Config cfg = new com.excel2mysql.core.Config();
        cfg.host = "127.0.0.1";
        cfg.port = 1;
        cfg.user = "probe";
        cfg.database = "testdb";
        String nl = String.valueOf((char) 10);
        List<com.excel2mysql.gui.SqlPage.Src> items =
                new ArrayList<com.excel2mysql.gui.SqlPage.Src>();
        items.add(new com.excel2mysql.gui.SqlPage.Src("x/a.sql",
                "CREATE TABLE a (id INT);" + nl + "INSERT INTO a VALUES (1);" + nl, null));
        Recorder rec = new Recorder();
        com.excel2mysql.gui.SqlPage.RunResult res;
        try {
            res = com.excel2mysql.gui.SqlPage.runScripts(items, cfg, rec);
        } catch (Throwable t) {
            problems.add("连不上库时任务体不该把异常抛给调用方：" + t);
            return;
        }
        if (!res.connFailed) {
            problems.add("连不上库却没标 connFailed，收尾会重复报一遍汇总");
        }
        if (res.ok != 0 || res.fail != 0 || res.skipped != 0) {
            problems.add("连不上库时三个计数都该是 0：" + res.ok + "/" + res.fail
                    + "/" + res.skipped);
        }
        // 总项数在连库之前就要算准：边跑边加会让进度条倒退
        if (rec.total != 2) {
            problems.add("两个脚本的语句计数应为 2，实际 " + rec.total);
        }
        if (!rec.saw("[连接失败] 无法连接到 testdb@127.0.0.1:1（用户 probe），剩余脚本均未执行",
                true)) {
            problems.add("执行任务没报出连不上的地址与用户：" + rec.errors);
        }
        if (rec.saw("[提交] a.sql", false)) {
            problems.add("根本没连上库，却报了提交成功");
        }
    }

    private static void ddl_dialog_and_widgets(MainFrame frame, List<String> files,
                                                List<String> problems) {
        // 前面的用例跑完会把列表清空，这里自己重新加载，不依赖上一个用例留下的状态
        frame.clearAll();
        frame.addFiles(files);
        int n = frame.tables().size();
        if (n == 0) {
            problems.add("DDL 弹窗自检没有表可看");
            return;
        }
        com.excel2mysql.gui.DDLDialog plain =
                new com.excel2mysql.gui.DDLDialog(null, frame.tables(), false);
        try {
            if (plain.tabCount() != n) {
                problems.add("DDL 弹窗应一张表一页：" + plain.tabCount() + " / " + n + " 张表");
            }
            String first = plain.tabText(0);
            if (!first.contains("CREATE TABLE")) {
                problems.add("DDL 弹窗第一页没有建表语句");
            }
            if (first.contains("DROP TABLE")) {
                problems.add("没勾覆盖已存在表，DDL 里却出现 DROP");
            }
        } finally {
            plain.dispose();
        }
        com.excel2mysql.gui.DDLDialog dropping =
                new com.excel2mysql.gui.DDLDialog(null, frame.tables(), true);
        try {
            if (!dropping.tabText(0).startsWith("DROP TABLE IF EXISTS")) {
                problems.add("勾了覆盖已存在表，DDL 第一页却没有以 DROP 开头");
            }
        } finally {
            dropping.dispose();
        }

        int[] want = {320, 200, 280, 80, 90, 160};
        javax.swing.table.TableColumnModel cols = frame.fileTable().getColumnModel();
        for (int i = 0; i < want.length && i < cols.getColumnCount(); i++) {
            if (cols.getColumn(i).getPreferredWidth() != want[i]) {
                problems.add("文件表第 " + (i + 1) + " 列宽不是 " + want[i] + "，实际 "
                        + cols.getColumn(i).getPreferredWidth());
            }
        }
        if (frame.fileTable().getAutoResizeMode()
                != javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN) {
            problems.add("文件表最后一列没有吃掉剩余宽度");
        }

        javax.swing.SpinnerNumberModel port =
                (javax.swing.SpinnerNumberModel) frame.portSpinner().getModel();
        if (((Number) port.getMinimum()).intValue() != 1
                || ((Number) port.getMaximum()).intValue() != 65535) {
            problems.add("端口框没有 1-65535 的范围约束：" + port.getMinimum() + "-"
                    + port.getMaximum());
        }
        if (((Number) port.getValue()).intValue() != 3306) {
            problems.add("端口默认值不是 3306：" + port.getValue());
        }
        if (frame.currentConfig().port != 3306) {
            problems.add("界面上的端口没进连接配置：" + frame.currentConfig().port);
        }
    }

    private static void progress_copy(List<String> problems) {
        com.excel2mysql.gui.ProgressDialog pd =
                new com.excel2mysql.gui.ProgressDialog(null, "正在解析文件…");
        try {
            if (!"正在解析文件…".equals(pd.getTitle())) {
                problems.add("进度框标题没生效：" + pd.getTitle());
            }
            // Qt 的 resize(480,140) 说的是客户区，所以量内容面板而不是整个窗
            java.awt.Dimension client = pd.getContentPane().getPreferredSize();
            if (client.width != 480 || client.height != 140) {
                problems.add("进度框客户区与 Python 不一致：" + client);
            }
            if (!"取消".equals(pd.cancelText())) {
                problems.add("取消按钮默认文案不对：" + pd.cancelText());
            }
            pd.setTotal(3);
            pd.step("读盘");
            if (!"进度 1/3 项 · 读盘".equals(pd.statusText())) {
                problems.add("进度文案格式不对：" + pd.statusText());
            }
            // 进度条上印的必须是真百分比：Qt 的 setFormat("%p%") 是格式符，
            // 照抄成 Swing 的 setString 就会把 "%p%" 四个字原样画出来
            if (!"0%".equals(pd.percentText())) {
                problems.add("进度条没印百分比：" + pd.percentText());
            }
            pd.step("识别格式");
            if (pd.percentShown() >= pd.percentTarget() && pd.percentShown() != 0) {
                problems.add("补间起点不应已经等于目标值：" + pd.percentShown());
            }
            pd.step("选主键");
            if (pd.percentShown() != 100) {
                problems.add("跑完最后一项进度条没拉满：" + pd.percentShown());
            }
            pd.setCancelText("停止（当前表完成后中止）");
            if (!"停止（当前表完成后中止）".equals(pd.cancelText())) {
                problems.add("取消按钮改字没生效：" + pd.cancelText());
            }
            pd.requestCancel();
            if (!pd.statusText().contains("当前项做完就停")) {
                problems.add("取消后的话术没说清什么时候停：" + pd.statusText());
            }
            if (!pd.isCancelled()) {
                problems.add("requestCancel 没置取消位，工作线程不会停");
            }
        } finally {
            pd.dispose();
        }
    }

    private static void stop_button(MainFrame frame, List<String> problems) {
        javax.swing.JButton stop = frame.stopButton();
        if (stop == null) {
            problems.add("界面上没有「停止」按钮");
            return;
        }
        if (!"停止".equals(stop.getText())) {
            problems.add("中止按钮文案不对：" + stop.getText());
        }
        if (frame.taskBusy()) {
            problems.add("刚建好窗口就处于有任务在跑的状态");
        }
        if (stop.isEnabled()) {
            problems.add("没任务时可点的「停止」是假按钮");
        }
        // 并发护栏与忙碌态联动：这两条必须走真入口，不能只看构造时的初始值
        if (!frame.startTask() || !frame.taskBusy()) {
            problems.add("startTask 没能占住任务位");
        }
        if (frame.startTask()) {
            problems.add("第二个任务挤得进来，两个任务会踩同一份表列表");
        }
        if (!stop.isEnabled()) {
            problems.add("任务在跑时「停止」是灰的，中止只能等它自己跑完");
        }
        frame.endTask();
        if (frame.taskBusy() || stop.isEnabled()) {
            problems.add("任务结束后没复原忙碌态与停止按钮");
        }
        if (!frame.startTask()) {
            problems.add("endTask 之后任务位没释放，第三次点不动了");
        }
        frame.endTask();
        int lines = frame.logPanel().lineCount();
        frame.stopTask();
        if (frame.logPanel().lineCount() == lines) {
            problems.add("空点「停止」什么都不说，用户会以为自己按错了");
        }
        if (!frame.logPanel().contains("没有正在执行的任务")) {
            problems.add("空点「停止」的提示语不对");
        }
    }

    /**
     * 「生成标准/简易 excel 文件」两条路的界面自检：按钮在不在、排在哪、什么时候能点，
     * 以及真按一次之后产物落在哪儿、长什么样、能不能被导入那条线读回去。
     */
    private static void meta_export(MainFrame frame, List<String> files,
                                    List<String> problems) {
        frame.clearAll();
        javax.swing.JButton std = frame.genStandardButton();
        javax.swing.JButton simple = frame.genSimpleButton();
        if (std == null || simple == null) {
            problems.add("界面上没有「生成标准excel文件」「生成简易excel文件」两颗按钮");
            return;
        }
        // 排布：同一行动作条上，紧跟在「开始导入」右侧（用户要的正是这个位置关系）
        if (std.getParent() != simple.getParent()) {
            problems.add("两颗生成按钮不在同一条动作栏上");
        }
        List<String> order = new ArrayList<String>();
        for (java.awt.Component c : std.getParent().getComponents()) {
            if (c instanceof javax.swing.JButton) {
                order.add(((javax.swing.JButton) c).getText());
            }
        }
        if (!order.equals(java.util.Arrays.asList("停止", "开始导入",
                "生成标准excel文件", "生成简易excel文件"))) {
            problems.add("动作条顺序不是 停止/开始导入/标准/简易：" + order);
        }
        // 空列表按下去要有话说，而且不许把任务位占死
        frame.generateExcel(true);
        if (!"提示".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().contains("请先添加 Excel 文件")) {
            problems.add("列表空着点生成要弹 NO_FILE_HINT，实际标题=" + frame.lastDialogTitle()
                    + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
        }
        if (frame.taskBusy()) {
            problems.add("拦下生成之后任务位没还回来，之后每个按钮都会被吃掉");
            frame.endTask();
        }

        frame.addFiles(files);
        int n = frame.tables().size();
        if (n == 0) {
            problems.add("生成自检没有可导的表");
            return;
        }
        if (!std.isEnabled() || !simple.isEnabled()) {
            problems.add("已经有表了，两颗生成按钮还是灰的，点了没反应");
        }
        // 忙碌态联动必须走真入口：只看构造时的初始值验不出"任务在跑还能再点一次"
        frame.startTask();
        boolean busyOff = !std.isEnabled() && !simple.isEnabled();
        frame.endTask();
        if (!busyOff) {
            problems.add("任务在跑时生成按钮还可点，两个任务会踩同一份表列表");
        }

        String root = com.excel2mysql.gui.MainFrame.programDir();
        java.io.File dir = new java.io.File(root,
                com.excel2mysql.core.MetaExport.STD_PREFIX + "_" + today());
        frame.generateExcel(true);
        if (!"正在生成标准excel文件…".equals(frame.lastTaskTitle())) {
            problems.add("生成任务标题不是 Python 那句原话：" + frame.lastTaskTitle());
        }
        if (!"生成结果".equals(frame.lastDialogTitle())) {
            problems.add("生成完成没弹带标题的结果框：" + frame.lastDialogTitle());
        }
        String doneLine = "===== 生成完成：" + n + "/" + n + " 个文件，目录 "
                + dir.getPath() + " =====";
        if (!frame.logPanel().contains(doneLine)) {
            problems.add("生成收尾的汇总行不对，期望：" + doneLine + "；日志尾部："
                    + tail(frame.logPanel().text()));
        }
        if (!flat(frame.lastDialogText()).contains(flat(dir.getPath()))) {
            problems.add("弹窗正文没把目录名原样给出来："
                    + frame.lastDialogText().replace((char) 10, ' '));
        }
        if (!frame.logPanel().contains("- [INFO] 导出 | 正在写 ")) {
            problems.add("逐个文件写之前一条日志都不说，长任务看着像卡死");
        }
        if (!frame.logPanel().contains(" 列 / ") || !frame.logPanel().contains(" 行）")) {
            problems.add("写完没报每个文件的列数与行数");
        }
        String[] produced = dir.list();
        if (produced == null || produced.length != n) {
            problems.add("程序同级该有 " + n + " 个文件，实际 "
                    + (produced == null ? 0 : produced.length) + " 个：" + dir.getPath());
        } else {
            checkStandardSheets(frame, dir, produced, problems);
        }
        // 程序同级这个目录是自检造出来的，验完就清掉，别留给下一次
        deleteTree(dir);

        java.io.File tmp;
        try {
            tmp = java.nio.file.Files.createTempDirectory("e2m-meta-selftest").toFile();
        } catch (java.io.IOException e) {
            problems.add("建简易件临时目录失败：" + e);
            return;
        }
        frame.generateExcel(false, tmp.getPath());
        if (!"正在生成简易excel文件…".equals(frame.lastTaskTitle())) {
            problems.add("简易件任务标题不对：" + frame.lastTaskTitle());
        }
        java.io.File simDir = new java.io.File(tmp,
                com.excel2mysql.core.MetaExport.SIMPLE_PREFIX + "_" + today());
        String[] simFiles = simDir.list();
        if (simFiles == null || simFiles.length != n) {
            problems.add("简易件目录应有 " + n + " 个文件，实际 "
                    + (simFiles == null ? "目录不存在" : simFiles.length + " 个"));
        } else {
            for (String name : simFiles) {
                try {
                    List<com.excel2mysql.core.MetaExport.Dump> dumps =
                            com.excel2mysql.core.MetaExport.dump(
                                    new java.io.File(simDir, name).getPath());
                    if (dumps.size() != 1
                            || !com.excel2mysql.core.MetaExport.DATA_SHEET.equals(
                                    dumps.get(0).sheet)) {
                        problems.add("简易件应只有一个数据 Sheet：" + name + " → "
                                + sheetsOf(dumps));
                    }
                } catch (Exception e) {
                    problems.add("简易件读不回来：" + name + " " + e);
                }
            }
        }
        deleteTree(tmp);

        // 停止要真的停下来：按了停止却把剩下的文件照写完，收尾那句"已取消，剩余文件未生成"就是假话
        java.io.File cdir;
        try {
            cdir = java.nio.file.Files.createTempDirectory("e2m-meta-cancel").toFile();
        } catch (java.io.IOException e) {
            problems.add("建取消用例临时目录失败：" + e);
            return;
        }
        List<com.excel2mysql.core.MetaExport.Source> srcs =
                new ArrayList<com.excel2mysql.core.MetaExport.Source>();
        List<com.excel2mysql.core.TableOps.Table> ts = frame.tables();
        List<com.excel2mysql.core.TableBuilder.Result> rs = frame.results();
        for (int i = 0; i < ts.size(); i++) {
            srcs.add(com.excel2mysql.core.MetaExport.of(ts.get(i), rs.get(i)));
        }
        Recorder crec = new Recorder();
        crec.stopAfter = 1;
        com.excel2mysql.core.MetaExport.Outcome co =
                com.excel2mysql.core.MetaExport.exportTables(srcs, cdir.getPath(), true, crec);
        if (co.written.size() != 1) {
            problems.add("按了停止还在写文件：写了 " + co.written.size() + " 个，应为 1 个");
        }
        if (co.total != ts.size()) {
            problems.add("生成任务的总项数没在开跑前算准：" + co.total + " / " + ts.size());
        }
        if (!co.cancelled) {
            problems.add("取消标志没传到收尾，弹窗不会说出「已取消」");
        }
        if (!crec.saw("+ [OK] 导出 | ", false)) {
            problems.add("写完一个文件没有留下成功行");
        }
        deleteTree(cdir);

        frame.clearAll();
        if (std.isEnabled() || simple.isEnabled()) {
            problems.add("清空列表后生成按钮还亮着");
        }
    }

    /**
     * 标准件的形状：元数据 + 数据两个 Sheet、七列表头一字不差、元数据一行一列，
     * 而且这份文件要能被导入那条线的 TableBuilder 当结构定义表读回来。
     */
    private static void checkStandardSheets(MainFrame frame, java.io.File dir,
                                            String[] produced, List<String> problems) {
        java.util.Set<String> wantHead = new java.util.LinkedHashSet<String>();
        for (String h : com.excel2mysql.core.MetaExport.META_HEADERS) {
            wantHead.add(h);
        }
        for (String name : produced) {
            String path = new java.io.File(dir, name).getPath();
            List<com.excel2mysql.core.MetaExport.Dump> dumps;
            try {
                dumps = com.excel2mysql.core.MetaExport.dump(path);
            } catch (Exception e) {
                problems.add("标准件读不回来：" + name + " " + e);
                continue;
            }
            if (dumps.size() != 2 || !"元数据".equals(dumps.get(0).sheet)
                    || !"数据".equals(dumps.get(1).sheet)) {
                problems.add("标准件应有元数据+数据两个 Sheet：" + name + " → "
                        + sheetsOf(dumps));
                continue;
            }
            List<Object> head = dumps.get(0).cells.isEmpty()
                    ? new ArrayList<Object>() : dumps.get(0).cells.get(0);
            java.util.Set<String> gotHead = new java.util.LinkedHashSet<String>();
            for (Object o : head) {
                gotHead.add(String.valueOf(o));
            }
            if (!wantHead.equals(gotHead)) {
                problems.add("元数据表头被改动：" + gotHead);
            }
            com.excel2mysql.core.TableBuilder.Result back =
                    new com.excel2mysql.core.TableBuilder(
                            new com.excel2mysql.core.Infer(frame.names()),
                            frame.names()).build(path);
            if (!"structured".equals(back.table.source)) {
                problems.add("标准件回读没走结构定义那条路：" + name);
                continue;
            }
            if (back.table.columns.size() != dumps.get(0).cells.size() - 1) {
                problems.add("元数据一行一列对不上：" + name + " 列 "
                        + back.table.columns.size() + " 行 "
                        + (dumps.get(0).cells.size() - 1));
            }
        }
    }

    private static String sheetsOf(List<com.excel2mysql.core.MetaExport.Dump> dumps) {
        List<String> out = new ArrayList<String>();
        for (com.excel2mysql.core.MetaExport.Dump d : dumps) {
            out.add(d.sheet);
        }
        return out.toString();
    }

    private static String today() {
        return java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
