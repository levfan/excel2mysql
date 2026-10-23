package com.excel2mysql;

import com.excel2mysql.gui.MainFrame;
import com.excel2mysql.gui.UiScale;
import com.excel2mysql.gui.LogPanel;

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
        com.excel2mysql.gui.UiScale.apply();
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
        // 自检要与生产走同一套外观与字号：以前这里停在 Metal 默认外观，
        // 量到的字体不是用户看到那一套，字号缩了也咬不住
        com.excel2mysql.gui.UiScale.apply();
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
        // 主窗口固定在"没有 conn.ini"的口径下建：包内冒烟时 jar 同级就带着随包 conn.ini，
        // 候选路径不钉住的话，"没找到就说明按默认值起界面"那条断言会随运行目录变红。
        // 后面 good.ini / bad.ini 两个场景各自换候选路径再建窗，不受这里影响。
        final List<String> keepCandidates = com.excel2mysql.gui.MainFrame.connCandidates;
        com.excel2mysql.gui.MainFrame.connCandidates =
                java.util.Collections.singletonList(
                        new File(folder, "no-such-conn.ini").getPath());
        // 关掉模态框：任务原地跑完。测的是线上那同一个工作函数与同一个回报接口，
        // 不是它的复印件（对应 main.py:137 的 MODAL_PROGRESS）。
        // 模态那一支两条线都没有自动化覆盖，交付前手工点一次，见 GUI-对齐清单.md 第 10 节。
        com.excel2mysql.gui.MainFrame.modalProgress = false;
        final CountDownLatch done = new CountDownLatch(1);
        final int[] result = {0};
        // 账本与窗口要能拿到事件分发线程外面：真线程那一支必须站在 EDT 之外等
        //（站在里面睡过去，进度框刷新与任务收尾回调全停，"没卡"就成了假象），
        // 所以判定挪到那一段跑完之后再打，否则并发用例还没跑就被算成通过了
        final List<String> problems = new ArrayList<String>();
        final MainFrame[] holder = new MainFrame[1];
        final int[] metrics = new int[2];
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = null;
            try {
                frame = new MainFrame();   // 建窗也在 try 里：构造期抛异常要让自检拿到退出码，而不是把闸门挂死
                com.excel2mysql.gui.MainFrame.connCandidates = keepCandidates;
                holder[0] = frame;
                if (!frame.initialsBox().isSelected() || !frame.upperBox().isSelected()) {
                    problems.add("两颗命名开关应默认勾选（取拼音首字母 / 转大写）");
                }
                // 后面几十处旧用例的期望值仍按全拼、小写写；先取消勾选保持口径不变，
                // 命名开关自己的用例（naming_switches）会重新勾上再验一遍
                frame.initialsBox().setSelected(false);
                frame.upperBox().setSelected(false);
                String addSummary = frame.addFiles(files);
                int tables = frame.tables().size();
                int fileRows = frame.fileModel().getRowCount();
                int rows = frame.fileModel().rowTotal();
                String ddl = frame.ddlText();
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
                // 有拒绝的「添加结果」必须算告警级：那句"另有 …"写在续行上，
                // 定级只看首行就会把一批被拒绝的添加报成绿色成功（截图复核抓到的）
                frame.log("[添加结果] 自检续行定级" + com.excel2mysql.core.LogText.NL
                        + "另有 1 项解析失败，详见日志");
                if (!"warn".equals(frame.logPanel().levelOfLine("自检续行定级"))) {
                    problems.add("有拒绝的「添加结果」没按告警级定级："
                            + frame.logPanel().levelOfLine("自检续行定级"));
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
                // 文件表只有「表名」列可就地编辑：单击改名，字段仍走双击结构弹窗
                if (!frame.fileModel().isCellEditable(0, 2)
                        || frame.fileModel().isCellEditable(0, 0)
                        || frame.fileModel().isCellEditable(0, 3)) {
                    problems.add("文件表应只有表名列可就地编辑");
                }
                int serialRows = frame.fileModel().getRowCount();
                if (!"1".equals(frame.fileModel().getValueAt(0, 0))
                        || !String.valueOf(serialRows).equals(
                                frame.fileModel().getValueAt(serialRows - 1, 0))) {
                    problems.add("文件表序号列没按行号连续编号");
                }
                fileRename(frame, problems);
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
                // 首屏分栏：表格是主工作面，表格段要比日志段高
                split_defaults(frame, problems);
                // SQL 页签：读盘、改表名、落盘副本、整份清空，全离线可验
                sql_page(frame, problems);
                // 第三个页签「SQL 执行器」：编辑区、示例清单与空输入那道闸
                sql_run_tab(frame, problems);
                // 主日志区的字号：现场反馈 12 号基本看不清，这里钉住共同的那一档
                checkLogFonts(frame, problems);
                // 第二个页签的两颗生成按钮：解析脚本 → 标准/简易件 → 回读
                sql_gen_excel(frame, problems);
                // 双击一行的耗时：读入时缓存字段定义，点开只取那一张（含改表名后的重挂）
                sql_metadata_cache(frame, problems);
                // 表注释手动改：写回 CREATE TABLE 的表选项，并一路带到弹窗与日志回执
                sql_comment_edit(frame, problems);
                // 页面级「停止」与并发护栏
                stop_button(frame, problems);
                // 程序同级 conn.ini 预填连接信息：读到 / 读不了 / 没有，三种说法各验一条
                conn_ini(frame, problems);
                // 并发护栏按资源位判：不同页可以一起跑，同一页与共用产物的仍然互斥
                task_slots(frame, files, problems);
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
                sql_exec_progress(problems);
                log_line_shape(frame, problems);
                log_pane_height(frame, problems);
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
                metrics[0] = fileRows;
                metrics[1] = rows;
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
        // 真线程那一支：进度框那条路（真子线程 + 真非模态框 + 真写盘）跑两条任务，
        // 这一段自己站在非事件分发线程上
        if (result[0] == 0 && holder[0] != null) {
            try {
                concurrent_tasks(holder[0], problems);
            } catch (Throwable e) {
                problems.add("真线程并发自检意外中止：" + e.getClass().getName()
                        + " " + e.getMessage());
            }
            try {
                click_other_page_while_busy(holder[0], problems);
            } catch (Throwable e) {
                problems.add("另一页点击取证意外中止：" + e.getClass().getName()
                        + " " + e.getMessage());
            }
        }
        if (problems.isEmpty()) {
            System.out.println("+ [OK] GUI-SELFTEST | 窗口构造成功，文件表 " + metrics[0]
                    + " 行 / 字段合计 " + metrics[1]
                    + " 个，DDL 预览含建表语句与主键，改类型能反映到预览；"
                    + "两条真任务同时在跑、界面照旧响应");
            for (String s : SHOTS) {
                System.out.println("- [INFO] GUI-SELFTEST | 并发留影 " + s);
            }
        } else {
            for (String p : problems) {
                System.out.println("x [ERROR] GUI-SELFTEST | " + p);
            }
            result[0] = 1;
        }
        return result[0];
    }

    /** 文件表单击表名改名：改到基础表名，四类非法输入都恢复原名。 */
    private static void fileRename(MainFrame frame, List<String> problems) {
        String before = frame.tables().get(0).tableName;
        String renamed = before + "_probe";
        frame.fileModel().setValueAt(renamed, 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !renamed.equals(frame.tables().get(0).baseTableName)) {
            problems.add("文件表改名没同时更新基础表名");
        }
        if (!frame.logPanel().contains("[表名] " + before + " → " + renamed
                + "（手动修改，后续批量前缀基于新名计算）")) {
            problems.add("文件表改名没写进日志");
        }
        frame.fileModel().setValueAt("", 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !frame.lastDialogText().contains("表名不能为空")) {
            problems.add("空表名没被拦下并恢复原名");
        }
        frame.fileModel().setValueAt("bad name", 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !frame.lastDialogText().contains("含非法字符")) {
            problems.add("含空格的表名没被拦下");
        }
        String dup = frame.tables().get(1).tableName;
        frame.fileModel().setValueAt(dup, 0, 2);
        if (!renamed.equals(frame.tables().get(0).tableName)
                || !frame.lastDialogText().contains("重名")) {
            problems.add("与其他表重名没被拦下");
        }
        frame.fileModel().setValueAt(before, 0, 2);
        if (!before.equals(frame.tables().get(0).tableName)
                || !before.equals(frame.tables().get(0).baseTableName)) {
            problems.add("文件表表名改回原名失败");
        }
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
        // 这张表不重复加标识：tab 上写的就是表名，已经贴在表格正上方
        if (!com.excel2mysql.gui.Tables.titledAncestor(dlg.tabTable(0)).isEmpty()) {
            problems.add("结构弹窗的字段表上又多了一层标识："
                    + com.excel2mysql.gui.Tables.titledAncestor(dlg.tabTable(0)));
        }
        if (!"字段名(拼音/首字母)".equals(dlg.columnHeader(0, 1))
                || !"字段注释(中文表头)".equals(dlg.columnHeader(0, 2))) {
            problems.add("结构弹窗表头被简写了：" + dlg.columnHeader(0, 1) + " / "
                    + dlg.columnHeader(0, 2));
        }
        // 末列写的是数据实测值，不是「类型」的声明容量；列名少了"表内数据"就会被读成后者
        if (!"表内数据最大字节长度".equals(dlg.columnHeader(0, 4))) {
            problems.add("结构弹窗第 4 列表头不是「表内数据最大字节长度」："
                    + dlg.columnHeader(0, 4));
        }
        if (!dlg.cellEditable(0, 0, 4)) {
            problems.add("「表内数据最大字节长度」列被禁了编辑，Python 那边只是不回写");
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

        // 空表（0 数据行）手工指定主键：非空与唯一在空集上天然成立，应放行；
        // 类型门槛不能因此跳过。
        java.util.List<com.excel2mysql.core.Infer.Column> emptyCols =
                new java.util.ArrayList<com.excel2mysql.core.Infer.Column>();
        com.excel2mysql.core.Infer.Column emptyPk = new com.excel2mysql.core.Infer.Column();
        emptyPk.field = "bian_hao";
        emptyPk.comment = "编号";
        emptyPk.type = "VARCHAR(64)";
        emptyPk.primaryKey = true;
        emptyCols.add(emptyPk);
        String emptyOk = com.excel2mysql.core.TableBuilder.validatePrimaryKey(
                java.util.Collections.<java.util.List<Object>>emptyList(), emptyCols);
        if (!emptyOk.isEmpty()) {
            problems.add("空表手工主键被拒：" + emptyOk);
        }
        emptyPk.type = "LONGTEXT";
        String emptyBad = com.excel2mysql.core.TableBuilder.validatePrimaryKey(
                java.util.Collections.<java.util.List<Object>>emptyList(), emptyCols);
        if (emptyBad.isEmpty()) {
            problems.add("空表主键类型校验被跳过，LONGTEXT 也被放行");
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
        String[] wanted = {"清空待上传文件", "生成表结构预览", "预览 DDL", "清除日志",
                "批量应用前缀", "替换首个前缀", "连接并加载库", "开始导入",
                "生成标准excel文件", "生成简易excel文件"};
        for (String w : wanted) {
            if (!texts.contains(w)) {
                problems.add("界面缺少按钮：" + w);
            }
        }
        if (texts.contains("添加文件")) {
            problems.add("「添加文件」按钮应已移除，加文件走点击虚线区");
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
    /**
     * 日志一行的形状：[年-月-日 时:分:秒] + 符号 + 大写分级标签，级别由中文标签定，续行不另打时间戳。
     *
     * 这一道必须咬住：三条线同一套标签（cli.py / core.log_text / 这里），时间戳也同一形状。
     * 时间戳丢了或只剩钟点、级别定错了、命令行搬来的那句叠出两个标签，都会在这里变红。
     */
    private static void log_line_shape(MainFrame frame, List<String> problems) {
        java.util.regex.Pattern head = java.util.regex.Pattern.compile(
                "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] ([+\\-!x]) \\[(OK|INFO|WARN|ERROR)\\] (.*)$");
        frame.log("[跳过] 形状A");
        frame.log("[已解析] 形状B");
        frame.logError("[建表失败] 形状C");
        frame.log("! [WARN] 说明 | 形状D");
        frame.log("      | 1 | 2 |");
        String[] want = {"形状A", "形状B", "形状C", "形状D", "| 1 | 2 |"};
        String[] wantLevel = {"WARN", "OK", "ERROR", "WARN", null};
        String[] wantMark = {"!", "+", "x", "!", null};
        java.util.Map<String, String> seen = new java.util.HashMap<String, String>();
        for (String ln : frame.logPanel().text().split("\n")) {
            for (String w : want) {
                if (ln.contains(w) && !seen.containsKey(w)) {
                    seen.put(w, ln);
                }
            }
        }
        for (int i = 0; i < want.length; i++) {
            String ln = seen.get(want[i]);
            if (ln == null) {
                problems.add("日志里没有形状用例那一行：" + want[i]);
                continue;
            }
            if (wantLevel[i] == null) {
                // 缩进的续行（结果表格那几行）归上一条记录，打上时间戳会把等宽表格顶歪
                if (!ln.equals("      | 1 | 2 |")) {
                    problems.add("缩进的续行被改写了：" + ln);
                }
                continue;
            }
            java.util.regex.Matcher m = head.matcher(ln);
            if (!m.matches()) {
                problems.add("日志行首不是「[年-月-日 时:分:秒] 符号 [级别] 」：" + ln);
                continue;
            }
            if (!wantLevel[i].equals(m.group(3)) || !wantMark[i].equals(m.group(2))) {
                problems.add("定级不对：" + ln + "（期望 " + wantMark[i] + " " + wantLevel[i] + "）");
            }
        }
        if (seen.get("形状D") != null && countOf(seen.get("形状D"), "[WARN]") != 1) {
            problems.add("本来就带大写标签的那句被叠了两个标签：" + seen.get("形状D"));
        }
        // 整页日志都要合这规矩（缩进行与空行除外）：漏一处就有一页的日志没有时间戳
        for (String ln : frame.logPanel().text().split("\n")) {
            if (ln.trim().isEmpty() || Character.isWhitespace(ln.charAt(0))) {
                continue;
            }
            if (!head.matcher(ln).matches()) {
                problems.add("日志里有不合形状的一行：" + ln);
                break;
            }
        }
    }

    /**
     * 三个日志框的最小高度：不给最小高度，分栏按比例一缩，默认窗口里实测只剩两行
     * （截图复核抓到的）。这一条断"至少装得下 4 行日志那一档字号（10pt）的字"。
     */
    private static void log_pane_height(MainFrame frame, List<String> problems) {
        com.excel2mysql.gui.LogPanel[] panes = {
                frame.logPanel(), frame.sqlPage().logPanel(), frame.sqlRunLogPanel()};
        String[] names = {"Excel 页", "SQL 脚本页", "SQL 执行器页"};
        java.awt.FontMetrics fm = frame.getGraphics() == null ? null
                : frame.getFontMetrics(panes[0].getFont());
        for (int i = 0; i < panes.length; i++) {
            int min = panes[i].getMinimumSize().height;
            if (fm != null && min < 4 * fm.getHeight()) {
                problems.add(names[i] + "的日志框装不下 4 行：" + min + " < "
                        + (4 * fm.getHeight()));
            }
        }
    }

    /**
     * 「SQL 执行器」的逐条进度与真取消：一屏上千条语句时，这两件决定用户是等还是走。
     * DbClient 是 final 类，造不出来，所以 SqlExecute 拆了一层收 Connection 的入口，
     * 这里用动态代理假一个连接——没有库也验的是线上那段循环。
     */
    private static void sql_exec_progress(List<String> problems) {
        final List<String> executed = new ArrayList<String>();
        final boolean[] committed = {false};
        final boolean[] rolled = {false};
        java.sql.Connection conn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                GuiMain.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("createStatement".equals(name)) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                GuiMain.class.getClassLoader(),
                                new Class<?>[]{java.sql.Statement.class},
                                (p2, m2, a2) -> {
                                    if ("execute".equals(m2.getName())) {
                                        executed.add(String.valueOf(a2[0]));
                                        return Boolean.FALSE;
                                    }
                                    if ("getUpdateCount".equals(m2.getName())) {
                                        return Integer.valueOf(1);
                                    }
                                    if ("close".equals(m2.getName())) {
                                        return null;
                                    }
                                    if ("toString".equals(m2.getName())) {
                                        return "fake-statement";
                                    }
                                    return defaultValue(m2.getReturnType());
                                });
                    }
                    if ("commit".equals(name)) {
                        committed[0] = true;
                        return null;
                    }
                    if ("rollback".equals(name)) {
                        rolled[0] = true;
                        return null;
                    }
                    if ("isClosed".equals(name)) {
                        return Boolean.FALSE;
                    }
                    if ("toString".equals(name)) {
                        return "fake-connection";
                    }
                    return defaultValue(method.getReturnType());
                });
        final List<int[]> steps = new ArrayList<int[]>();
        final boolean[] stop = {false};
        com.excel2mysql.core.SqlExecute.Report r = com.excel2mysql.core.SqlExecute.run(
                conn, "CREATE TABLE pa (i INT);\nCREATE TABLE pb (i INT);"
                        + "\nCREATE TABLE pc (i INT);",
                text -> {
                },
                (done, total) -> {
                    steps.add(new int[]{done, total});
                    if (done >= 1) {
                        stop[0] = true;      // 第一条跑完就当作"用户点了停止"
                    }
                },
                () -> stop[0]);
        if (steps.isEmpty() || steps.get(0)[0] != 0 || steps.get(0)[1] != 3) {
            problems.add("执行器没在开工前先报 0/3：" + describeSteps(steps));
        }
        if (executed.size() != 1) {
            problems.add("点了停止还在往下执行：" + executed);
        }
        if (!rolled[0] || committed[0]) {
            problems.add("取消的这批没回滚或竟然提交了：rolled=" + rolled[0]
                    + " committed=" + committed[0]);
        }
        if (!r.cancelled) {
            problems.add("Report.cancelled 没置上，界面分不清是跑完还是被停");
        }
        // 不传进度与取消时行为必须与改前一致（命令行那条线就是这么调的）
        final List<String> executed2 = new ArrayList<String>();
        final boolean[] committed2 = {false};
        java.sql.Connection conn2 = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                GuiMain.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                GuiMain.class.getClassLoader(),
                                new Class<?>[]{java.sql.Statement.class},
                                (p2, m2, a2) -> {
                                    if ("execute".equals(m2.getName())) {
                                        executed2.add(String.valueOf(a2[0]));
                                    }
                                    if ("getUpdateCount".equals(m2.getName())) {
                                        return Integer.valueOf(1);
                                    }
                                    return defaultValue(m2.getReturnType());
                                });
                    }
                    if ("commit".equals(method.getName())) {
                        committed2[0] = true;
                    }
                    return defaultValue(method.getReturnType());
                });
        com.excel2mysql.core.SqlExecute.run(conn2,
                "CREATE TABLE pa (i INT);\nCREATE TABLE pb (i INT);"
                        + "\nCREATE TABLE pc (i INT);", text -> {
                }, null, null);
        if (executed2.size() != 3 || !committed2[0]) {
            problems.add("不传进度与取消时行为变了：跑了 " + executed2.size()
                    + " 句、提交=" + committed2[0]);
        }
    }

    private static String describeSteps(List<int[]> steps) {
        StringBuilder sb = new StringBuilder();
        for (int[] st : steps) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(st[0]).append('/').append(st[1]);
        }
        return sb.toString();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(type)) {
            return Boolean.FALSE;
        }
        if (void.class.equals(type)) {
            return null;
        }
        return Integer.valueOf(0);
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

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
            // 1) 勾回默认口径（首字母 + 大写）：新加的文件直接按这套起名
            frame.initialsBox().doClick();
            frame.upperBox().doClick();
            File a = writeNamingFixture(tmp, "水库信息表.xlsx",
                    new String[]{"订单金额"}, new Object[]{Double.valueOf(12.5)});
            frame.addFiles(java.util.Arrays.asList(a.getPath()));
            com.excel2mysql.core.TableOps.Table ta = lastTable(frame);
            if (ta == null || !"SKXXB".equals(ta.tableName)) {
                problems.add("默认勾选下新表名不是 SKXXB：" + (ta == null ? "(无表)" : ta.tableName));
            } else if (ta.columns.isEmpty() || !"DDJE".equals(ta.columns.get(0).field)) {
                problems.add("勾上首字母 + 大写后字段名不是 DDJE："
                        + (ta.columns.isEmpty() ? "(无字段)" : ta.columns.get(0).field));
            }
            if (!frame.logPanel().contains("[提示] 命名规则切换为：拼音首字母")
                    || !frame.logPanel().contains("[提示] 表名与字段名大小写切换为：大写")) {
                problems.add("勾上两颗开关没有留下日志");
            }
            // 2) 两颗都取消：回到全拼、小写，切换各留一行日志
            frame.initialsBox().doClick();
            frame.upperBox().doClick();
            File b = writeNamingFixture(tmp, "水库信息表（n_lowercheck）.xlsx",
                    new String[]{"订单金额"}, new Object[]{Double.valueOf(12.5)});
            frame.addFiles(java.util.Arrays.asList(b.getPath()));
            com.excel2mysql.core.TableOps.Table tb = lastTable(frame);
            if (tb == null || !"n_lowercheck".equals(tb.tableName)) {
                problems.add("取消两颗开关后表名不是 n_lowercheck："
                        + (tb == null ? "(无表)" : tb.tableName));
            } else if (tb.columns.isEmpty() || !"ding_dan_jin_e".equals(tb.columns.get(0).field)) {
                problems.add("取消两颗开关后字段名不是 ding_dan_jin_e："
                        + (tb.columns.isEmpty() ? "(无字段)" : tb.columns.get(0).field));
            }
            if (!frame.logPanel().contains("[提示] 命名规则切换为：全拼")
                    || !frame.logPanel().contains("[提示] 表名与字段名大小写切换为：小写")) {
                problems.add("取消两颗开关没有留下日志");
            }
            // 3) 空模板按字段名定类型：全拼、小写口径下，类型各归各位
            File c = writeNamingFixture(tmp, "采集模板（t_empty_kinds）.xlsx",
                    new String[]{"发布日期", "更新时间", "订单金额", "数量", "备注说明",
                            "年龄", "系数", "评分", "经度", "长度", "是否有效", "序号",
                            "年份"}, null);
            frame.addFiles(java.util.Arrays.asList(c.getPath()));
            com.excel2mysql.core.TableOps.Table tc = lastTable(frame);
            String[] wantTypes = {"DATE", "DATETIME", "DECIMAL(16,2)", "INT", "VARCHAR(64)",
                    "INT", "DECIMAL(18,6)", "DECIMAL(10,2)", "DECIMAL(18,8)",
                    "DECIMAL(18,4)", "TINYINT(1)", "INT", "INT"};
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
            File d = writeNamingFixture(tmp, "采集模板（t_empty_priority）.xlsx",
                    new String[]{"频率", "功率", "速率", "容积率", "毛利率"}, null);
            frame.addFiles(java.util.Arrays.asList(d.getPath()));
            com.excel2mysql.core.TableOps.Table td = lastTable(frame);
            String[] wantPriority = {"DECIMAL(18,4)", "DECIMAL(18,4)", "DECIMAL(18,4)",
                    "DECIMAL(18,6)", "DECIMAL(18,6)"};
            if (td == null || td.columns.size() != wantPriority.length) {
                problems.add("优先级夹具字段数不对："
                        + (td == null ? "(无表)" : td.columns.size()));
            } else {
                for (int i = 0; i < wantPriority.length; i++) {
                    String got = td.columns.get(i).type;
                    if (!wantPriority[i].equals(got)) {
                        problems.add("优先级夹具第 " + (i + 1) + " 列期望 "
                                + wantPriority[i] + "，实际 " + got);
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
        if (!drop.hintText().contains("拖到此处") || !drop.hintText().contains(".zip")
                || !drop.hintText().contains("点击选择文件")) {
            problems.add("拖拽区提示没交代清楚能拖什么/能点：" + drop.hintText());
        }
        if (!drop.hasClickAction()) {
            problems.add("拖拽区没接上点击选择文件的动作");
        }
        // 单独造一个接收区验证点击回调真的会响，绕开 JFileChooser 的模态框
        final int[] probeClicks = {0};
        com.excel2mysql.gui.DropArea probe = new com.excel2mysql.gui.DropArea(
                paths -> { }, () -> probeClicks[0]++);
        probe.fireClick();
        if (probeClicks[0] != 1) {
            problems.add("点击拖拽区没有触发选择文件（回调次数 " + probeClicks[0] + "）");
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
        // 设计值 1100x780 是 Qt 的逻辑像素；150% 屏上应为 1650x1170。
        // 期望值在这里独立按 DPI 算一遍，UiScale 被改坏时这条会红。
        int dpi = java.awt.Toolkit.getDefaultToolkit().getScreenResolution();
        int wantClientW = (int) Math.round(1100 * Math.max(1.0, dpi / 96.0));
        int wantClientH = (int) Math.round(780 * Math.max(1.0, dpi / 96.0));
        if (client.width < wantClientW || client.height < wantClientH) {
            problems.add("客户区没按 DPI 折算到 " + wantClientW + "x" + wantClientH
                    + "（实际 " + client + "），整窗会比 Python 小一圈");
        }
        // Python 的应用默认字体是 Microsoft YaHei UI 9pt：144dpi 下 18px。
        // 不装这一档时 Swing 会退回 SimSun，字形明显更瘦更小。
        java.awt.Font uiFont = UIManager.getFont("Label.font");
        int wantUiPx = (int) Math.round(9 * dpi / 72.0);
        if (uiFont == null || uiFont.getSize() != wantUiPx
                || !uiFont.getFamily().contains("YaHei")) {
            problems.add("界面默认字体没对齐 Python 的 Microsoft YaHei UI 9pt/"
                    + wantUiPx + "px：" + uiFont);
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
            problems.add("「清空待上传文件」没把进度条归零或没清空表");
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
        // 启动默认收起（用户口径）：首屏只留一条状态栏，告警直接指向「设置数据源信息」
        if (frame.isConnExpanded() || !frame.connEditVisible()
                || !frame.connStateText().contains("设置数据源信息")) {
            problems.add("启动时连接区没有默认收起，或告警没指向「设置数据源信息」："
                    + frame.connStateText());
        }
        frame.setConnExpanded(true);
        if (!frame.isConnExpanded() || frame.connEditVisible()) {
            problems.add("点「设置数据源信息」没能展开连接表单");
        }
        // 用户口径：未选库的告警只留顶部状态栏一处，三页里不再重复
        if (!frame.excelHintText().isEmpty() || frame.excelHint().isVisible()
                || !frame.sqlPage().hintText().isEmpty()
                || frame.sqlPage().hintField().isVisible()
                || !frame.runDbHintText().isEmpty() || frame.runDbHint().isVisible()) {
            problems.add("未选库时三页仍重复了告警（应当只在顶部状态栏说一次）："
                    + frame.excelHintText() + " / " + frame.sqlPage().hintText()
                    + " / " + frame.runDbHintText());
        }
        // 此刻连接表单是展开的（上面刚点开过），告警要指向这颗真在界面上的按钮
        if (!frame.connStateText().startsWith("⚠ 未选择目标库")
                || !frame.connStateText().contains("连接并加载库")
                || !com.excel2mysql.core.LogText.NO_DB_HINT.startsWith("未选择目标库")) {
            problems.add("未选库时顶部状态栏没承担唯一告警，或话术还是两套："
                    + frame.connStateText());
        }
        // ⚠ 在 Microsoft YaHei 下没有字形，直接画就是豆腐块；显示文字里不许留它，
        // 但 hintText 要保留原文给双端对撞。图标由 HintLabel 自绘。
        String badShown = frame.connState().getText();
        if (badShown.contains("⚠") || !frame.connStateText().contains("未选择目标库")) {
            problems.add("告警条显示文字仍是 ⚠ 原文或丢了正文：" + badShown);
        }
        frame.setSelectedDatabase("testdb");
        if (!frame.excelHintText().contains("目标库：testdb —— ")
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
        // 页内那条不再重复 host:port；主机端口与用户名收进状态栏的悬停提示
        if (frame.excelHintText().contains("127.0.0.1")
                || !"● 目标库 testdb".equals(frame.connStateText())
                || !frame.connTip().contains("127.0.0.1:3306")
                || !frame.connTip().contains("root")) {
            problems.add("状态栏没把 host:port 收住：" + frame.connStateText()
                    + " / " + frame.connTip());
        }
        // 选定库之后连接表单自动收起：跑业务时不必一直看着五行环境信息（用户口径）
        if (frame.isConnExpanded() || !frame.connEditVisible()) {
            problems.add("选定库之后连接表单没自动收起，环境信息仍常驻首屏");
        }
        frame.setConnExpanded(true);
        if (!frame.isConnExpanded() || frame.connEditVisible()) {
            problems.add("点「设置数据源信息」没能展开表单，或那颗按钮没让位给「收起」");
        }
        frame.setConnExpanded(false);
        if (frame.isConnExpanded() || !frame.connEditVisible()) {
            problems.add("点「收起」没能收回一条状态栏");
        }
        // 「重新填库列表」不等于「选定目标库」：Swing 里 addItem 会把第一项选上，
        // 一加载就收起等于把用户下一步要用的那颗下拉藏起来
        frame.setConnExpanded(true);
        frame.reloadDatabases(java.util.Arrays.asList("alpha", "beta"));
        if (!frame.isConnExpanded()) {
            problems.add("只是重新填了一遍库列表就把连接区收起了");
        }
        frame.setSelectedDatabase("beta");
        if (frame.isConnExpanded()) {
            problems.add("在目标库里选了一个还没自动收起");
        }
        frame.setSelectedDatabase("testdb");
        frame.hostField().setText("10.0.0.9");
        if (!frame.connTip().contains("10.0.0.9:3306")) {
            problems.add("改了主机但状态栏提示还是旧地址：" + frame.connTip());
        }
        if (frame.excelHintText().contains("10.0.0.9")) {
            problems.add("页内那条又把 host:port 拼回来了：" + frame.excelHintText());
        }
        frame.hostField().setText("127.0.0.1");
        // 提示条要能真的按住拖选再复制（Python 那边是开了 TextSelectableByMouse 的 QLabel）
        dragSelectable(frame.connState(), "连接状态栏", problems);
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
                || !frame.lastDialogText().contains("未选择目标库")) {
            problems.add("没选库点导入要弹 NO_DB_HINT，实际标题=" + frame.lastDialogTitle()
                    + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
        }
        if (!frame.logPanel().contains("已拦下：未选择目标库")) {
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
        // 编辑区与结果区都是第三页给人读的主区域：等宽，字号与第一页日志同档
        java.awt.Font resultFont = frame.sqlRunLogPanel().getFont();
        java.awt.Font inputFont = frame.sqlRunInputFont();
        int wantLog = frame.logPanel().getFont().getSize();
        if (wantLog != wantFontPx(LogPanel.DEFAULT_FONT_SIZE)) {
            problems.add("第一页日志字号不是日志档折算的 " + wantFontPx(LogPanel.DEFAULT_FONT_SIZE)
                    + "px：" + wantLog);
        }
        if (resultFont == null || !"Monospaced".equals(resultFont.getFamily())) {
            problems.add("结果区不是等宽字体："
                    + (resultFont == null ? "无字体" : resultFont.getFamily()));
        }
        if (resultFont == null || resultFont.getSize() != wantLog) {
            problems.add("结果区字号与第一页日志不同档（期望 " + wantLog + "px）："
                    + (resultFont == null ? "无字体" : resultFont.getSize()));
        }
        if (inputFont == null || !"Monospaced".equals(inputFont.getFamily())) {
            problems.add("编辑区不是等宽字体："
                    + (inputFont == null ? "无字体" : inputFont.getFamily()));
        }
        if (inputFont == null || inputFont.getSize() != wantLog) {
            problems.add("编辑区字号与第一页日志不同档（期望 " + wantLog + "px）："
                    + (inputFont == null ? "无字体" : inputFont.getSize()));
        }
        if (resultFont != null && inputFont != null
                && (!resultFont.getFamily().equals(inputFont.getFamily())
                || resultFont.getSize() != inputFont.getSize())) {
            problems.add("编辑区与结果区字体不一致：编辑区 " + inputFont.getFamily() + "/"
                    + inputFont.getSize() + "，结果区 " + resultFont.getFamily() + "/"
                    + resultFont.getSize());
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
        if (frame.sqlSamplesCount() != 100) {
            problems.add("SQL 示例不是 100 条：" + frame.sqlSamplesCount());
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
        java.awt.Font previewFont = frame.sqlRunPreviewFont();
        int wantPreviewPx = wantFontPx(LogPanel.DEFAULT_FONT_SIZE);
        if (previewFont == null || !"Monospaced".equals(previewFont.getFamily())
                || previewFont.getSize() != wantPreviewPx) {
            problems.add("示例全文不是日志档 10pt 折算的等宽字体："
                    + (previewFont == null ? "无字体"
                    : previewFont.getFamily() + "/" + previewFont.getSize()
                    + "，期望 " + wantPreviewPx + "px"));
        }
        if (!"SQL 示例（100 条）".equals(frame.sqlSamplesTitle())) {
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

    /** 自检独立按 DPI 折算一遍，不引用 UiScale：产品侧折算被改坏时断言才会红。 */
    private static int wantFontPx(double pt) {
        return (int) Math.round(
                pt * java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 72.0);
    }

    /** Qt 逻辑像素 → 物理像素的独立折算，口径与 UiScale.px 相同但刻意不复用它。 */
    private static int wantPx(int design) {
        return (int) Math.round(design * Math.max(1.0,
                java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96.0));
    }

    /**
     * 主页与 SQL 脚本页两处日志区共用 LogPanel 默认档：9 号被判过小、14 号只露三行，
     * 现定 10；表格保持"比日志大一档"定 11，与 Excel 数据网格同级。
     * 第三页的编辑区与结果区不在这档（那是给人读语句的主区域，仍 16），
     * 由 sql_run_tab 里那组断言管。
     * "10 / 11" 是 Python 侧的 pt，Swing 里必须折成 DPI 对应的物理像素（144dpi → 20 / 22px）。
     */
    private static void checkLogFonts(MainFrame frame, List<String> problems) {
        com.excel2mysql.gui.LogPanel[] logs = {
                frame.logPanel(), frame.sqlPage().logPanel()};
        String[] names = {"主页日志", "SQL 脚本页日志"};
        int wantLog = wantFontPx(10);
        for (int i = 0; i < logs.length; i++) {
            java.awt.Font f = logs[i].getFont();
            if (f == null || !"Monospaced".equals(f.getFamily())) {
                problems.add(names[i] + "不是等宽字体：" + (f == null ? "无字体" : f.getFamily()));
            }
            if (f == null || f.getSize() != wantLog) {
                problems.add(names[i] + "字号不是 10pt 折算的 " + wantLog + "px："
                        + (f == null ? "无字体" : f.getSize()));
            }
        }
        java.awt.Font base = logs[0].getFont();
        for (int i = 1; i < logs.length; i++) {
            java.awt.Font f = logs[i].getFont();
            if (base != null && f != null
                    && (!base.getFamily().equals(f.getFamily()) || base.getSize() != f.getSize())) {
                problems.add("日志区字体不一致：" + names[0] + " " + base.getFamily() + "/"
                        + base.getSize() + "，但 " + names[i] + " " + f.getFamily() + "/"
                        + f.getSize());
            }
        }
        // 用户口径（2026-10-23）：内容比日志大一档。表格 11、日志 10，
        // 期望值写死成这两个契约数字，不拿常量当期望值。
        // 顺带盯行高跟着字号走——行高写死的话放大字号会把字裁掉一半
        java.awt.Font tf = frame.fileTable().getFont();
        java.awt.Font hf = frame.fileTable().getTableHeader().getFont();
        int wantTable = wantFontPx(11);
        if (tf.getSize() <= wantLog || hf.getSize() <= wantLog) {
            problems.add("表格内容/表头字号没有比日志大一档：内容 " + tf.getSize()
                    + "、表头 " + hf.getSize() + "，日志 " + wantLog + "px");
        }
        if (tf.getSize() != wantTable || hf.getSize() != wantTable) {
            problems.add("表格字号不是 11pt 折算的 " + wantTable + "px：内容 "
                    + tf.getSize() + "、表头 " + hf.getSize());
        }
        if (frame.fileTable().getRowHeight() <= tf.getSize()) {
            problems.add("表格行高没跟着字号走：行高 " + frame.fileTable().getRowHeight()
                    + "，字号 " + tf.getSize());
        }
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
        javax.swing.JComponent copyBody = frame.popupMessageComponent(
                "已生成 124 个文件（共 124 张表）。\n目录：\n"
                + "C:\\Users\\acliu\\Desktop\\excel2mysql-1021-三端产物\\"
                + "03-Java-图形与命令行-带JRE\\sql_meta_data_file_20261022");
        java.awt.Dimension popupSize = copyBody.getPreferredSize();
        if (!(copyBody instanceof javax.swing.JTextArea)
                || ((javax.swing.JTextArea) copyBody).isEditable()) {
            problems.add("生成结果弹窗正文不可复制：应使用只读文本区");
        }
        if (popupSize.width < UiScale.px(820) || popupSize.height < UiScale.px(420)) {
            problems.add("生成结果弹窗正文太小，产物路径看不清：" + popupSize);
        }
        // Python 的消息框吃应用默认字体 9pt；144dpi 下应折成 18px。
        // 期望值独立算，不拿 MessageDialog.FONT_SIZE 自己比自己。
        int wantPopupPx = wantFontPx(9);
        if (copyBody.getFont() == null || copyBody.getFont().getSize() != wantPopupPx) {
            problems.add("结果弹窗正文字号不是 Python 默认 9pt 折算的 " + wantPopupPx
                    + "px：" + copyBody.getFont());
        }
        StringBuilder longText = new StringBuilder("任务异常：以下文件未能读入。这一句故意写得很长很长，"
                + "是为了验证正文控件真的按内容区的右边界把一行折下去，"
                + "而不是把窗口横向撑宽、也不是提前半幅就断行，所以还得再续一段才够长。");
        for (int i = 0; i < 40; i++) {
            longText.append("\n[读取失败] C:\\data\\第").append(i)
                    .append("批\\很长的文件名用来验证滚动与复制.sql：编码无法识别");
        }
        javax.swing.JComponent longArea = frame.popupMessageComponent(longText.toString());
        if (!(longArea instanceof javax.swing.JTextArea)
                || ((javax.swing.JTextArea) longArea).getLineCount() <= 24
                || !((javax.swing.JTextArea) longArea).getText().contains("第39批")) {
            problems.add("弹窗正文这条线仍按 24 行截断，后面的报错看不到");
        }
        String shown = frame.popupBodyText(longText.toString());
        com.excel2mysql.gui.MessageDialog md = com.excel2mysql.gui.MessageDialog.create(
                frame, "任务异常", shown, null,
                javax.swing.JOptionPane.ERROR_MESSAGE);
        if (!com.excel2mysql.gui.MessageDialog.hasScrollPane(md.textArea())) {
            problems.add("结果弹窗正文没装进滚动区，超出部分无处可看");
        }
        md.validate();
        if (!md.scrollPane().getVerticalScrollBar().isVisible()) {
            problems.add("长报错没有滚动条，超出一屏的内容看不到");
        }
        if (!"复制全部".equals(md.copyButton().getText())
                || md.copyButton().getActionListeners().length == 0) {
            problems.add("结果弹窗没有可用的「复制全部」：" + md.copyButton().getText());
        }
        if (!md.displayText().contains("第39批")
                || md.textArea().getLineCount() <= 24) {
            // 走截断版的话第 25 行往后会换成「见日志」，40 条报错只剩 23 条
            problems.add("长报错被截成 24 行，弹窗里翻不到后几批：" + md.textArea().getLineCount());
        }
        if (md.fullText().length() <= 0 || !md.fullText().contains("第39批")) {
            problems.add("「复制全部」拿到的不是完整原文");
        }
        // 正文里不许出现原文没有的换行：折行是排版的活，写进文本就再也追不上窗口宽度
        if (!shown.equals(longText.toString()) || !md.displayText().equals(shown)) {
            problems.add("弹窗正文被预先插了换行，窗口拉大也不会重排："
                    + firstLine(md.displayText()));
        }
        // 折行交给控件：控件必须开着按词折，而且样本里得真有一段宽过内容区，
        // 否则这条判据在这台机器上咬不住东西（宁可显式说它没咬到）
        javax.swing.JTextArea body = md.textArea();
        java.awt.FontMetrics bfm = body.getFontMetrics(body.getFont());
        int widest = 0;
        for (String para : longText.toString().split("\n", -1)) {
            widest = Math.max(widest, bfm.stringWidth(para));
        }
        if (!body.getLineWrap() || !body.getWrapStyleWord()) {
            problems.add("正文控件没开按词折行，长句只能横向挤着看");
        }
        if (widest <= body.getWidth()) {
            problems.add("样本里没有宽过内容区的段落，折行这条判据咬不住东西：最宽段 "
                    + widest + "px / 控件宽 " + body.getWidth());
        }
        if (md.getWidth() < UiScale.px(820) || md.getHeight() < UiScale.px(420)) {
            problems.add("结果弹窗本身小于按 DPI 折算的 "
                    + UiScale.px(820) + "x" + UiScale.px(420) + "：" + md.getSize());
        }
        // 弹窗尺寸常量是设计值，只能由 Windows.clientSize 折一次；先折再传会变成 1.5 倍
        java.awt.Dimension mdClient = md.getContentPane().getPreferredSize();
        if (mdClient.width != UiScale.px(880)) {
            problems.add("结果弹窗客户区宽度被重复折算：" + mdClient.width
                    + "，应为 " + UiScale.px(880));
        }
        if (!com.excel2mysql.gui.Windows.workArea(md).contains(md.getBounds())) {
            problems.add("结果弹窗超出屏幕可用区：" + md.getBounds());
        }
        if (!md.isResizable()) {
            problems.add("结果弹窗不能拖动改大小，长路径只能横向挤着看");
        }
        md.dispose();

        java.io.File stdDir = new java.io.File(outRoot, "sql_meta_data_file_" + today());
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
        java.io.File simpleDir = new java.io.File(outRoot, "sql_simple_meta_data_file_" + today());
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
        // 前缀两颗按钮同样要有前置守卫：无文件时返回提示，由按钮接线走 host.warn
        empty.setPrefixText("dim_");
        if (!com.excel2mysql.core.LogText.NO_SQL_HINT.equals(empty.applyPrefix())) {
            problems.add("没有脚本时「批量应用前缀」没返回提示：" + empty.applyPrefix());
        }
        if (!com.excel2mysql.core.LogText.NO_SQL_HINT.equals(empty.replaceFirstPrefix())) {
            problems.add("没有脚本时「替换首个前缀」没返回提示：" + empty.replaceFirstPrefix());
        }

        // 空脚本（0 张表）：点按钮要弹窗说明，不是记一行"共 0 个表名"就算完
        String emptySql = new File(dir, "empty.sql").getPath();
        writeText(emptySql, "");
        com.excel2mysql.gui.SqlPage emptyScript = new com.excel2mysql.gui.SqlPage(frame);
        List<String> emptyPaths = new ArrayList<String>();
        emptyPaths.add(emptySql);
        emptyScript.addFiles(emptyPaths);
        if (emptyScript.entryCount() != 1 || emptyScript.rowCount() != 0) {
            problems.add("空脚本应加载成 1 个文件、0 行表名，实际 "
                    + emptyScript.entryCount() + "/" + emptyScript.rowCount());
        }
        // 任务日志按页归属路由到主窗口那份 SQL 页，不在这颗临时页自己的面板里
        String emptyLog = frame.sqlPage().logPanel().text();
        if (!emptyLog.contains("[已加载] empty.sql：0 个表（未发现 CREATE TABLE 语句）")
                || emptyLog.contains("0 个表（（")) {
            problems.add("空脚本加载日志的括号或文案不对：" + emptyLog.replace((char) 10, ' '));
        }
        emptyScript.setPrefixText("dim_");
        javax.swing.JButton emptyApply = buttonIn(emptyScript, "批量应用前缀");
        javax.swing.JButton emptyReplace = buttonIn(emptyScript, "替换首个前缀");
        if (emptyApply == null || emptyReplace == null) {
            problems.add("SQL 页上找不到两颗前缀按钮");
        } else {
            emptyApply.doClick();
            if (!"提示".equals(frame.lastDialogTitle())
                    || !"warning".equals(frame.lastDialogIcon())
                    || !frame.lastDialogText().contains("没有解析出 CREATE TABLE")) {
                problems.add("空脚本点「批量应用前缀」没弹窗说明：标题=" + frame.lastDialogTitle()
                        + " 图标=" + frame.lastDialogIcon()
                        + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
            }
            emptyReplace.doClick();
            if (!"提示".equals(frame.lastDialogTitle())
                    || !"warning".equals(frame.lastDialogIcon())
                    || !frame.lastDialogText().contains("没有解析出 CREATE TABLE")) {
                problems.add("空脚本点「替换首个前缀」没弹窗说明：标题=" + frame.lastDialogTitle()
                        + " 图标=" + frame.lastDialogIcon()
                        + " 正文=" + frame.lastDialogText().replace((char) 10, ' '));
            }
        }

        // ---- 第二页的大小写开关：默认不勾选，清单/元数据/生成物一起转大写，脚本正文不动 ----
        if (page.upperBox().isSelected()) {
            problems.add("SQL 页大写开关默认应不勾选");
        }
        boolean hasSqlInitials = false;
        for (java.awt.Component c : ((java.awt.Container) page.upperBox().getParent()).getComponents()) {
            if (c instanceof javax.swing.JCheckBox
                    && "表名与字段名取拼音首字母".equals(((javax.swing.JCheckBox) c).getText())) {
                hasSqlInitials = true;
            }
        }
        if (hasSqlInitials) {
            problems.add("SQL 页不应再有「表名与字段名取拼音首字母」开关");
        }
        page.upperBox().doClick();
        String toggleLog = page.logPanel().text();
        if (!toggleLog.contains("[提示] 表名与字段名大小写切换为：大写"
                + "（当前表名清单、表元数据和生成的 Excel 一起变；脚本正文不改）")) {
            problems.add("SQL 页大小写开关切换没写日志说明作用域："
                    + tailOf(toggleLog).replace((char) 10, (char) 32));
        }

        java.io.File caseScript = new java.io.File(dir, "name_case.sql");
        String caseSql = "CREATE TABLE `t_case9` (" + nl
                + "  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键'," + nl
                + "  `订单金额` decimal(10,2) DEFAULT NULL COMMENT '金额'," + nl
                + "  `客户名称` varchar(32) DEFAULT NULL COMMENT '客户'," + nl
                + "  PRIMARY KEY (`id`)" + nl
                + ") ENGINE=InnoDB COMMENT='大小写检查表';" + nl;
        writeText(caseScript.getPath(), caseSql);
        com.excel2mysql.gui.SqlPage named = new com.excel2mysql.gui.SqlPage(frame);
        List<String> casePaths = new ArrayList<String>();
        casePaths.add(caseScript.getPath());
        named.addFiles(casePaths);
        named.upperBox().doClick();
        if (!named.sqlRowNameEditable(0)) {
            problems.add("SQL 页「当前表名」列不可直接编辑");
        }
        if (!"T_CASE9".equals(named.sqlRowDisplayName(0))
                || !"t_case9".equals(named.sqlRowActualName(0))) {
            problems.add("SQL 页当前表名显示大写失败：显示=" + named.sqlRowDisplayName(0)
                    + " 实际=" + named.sqlRowActualName(0));
        }
        com.excel2mysql.core.SqlScript.ParsedTable parsedCase = named.metadataAtRow(0);
        if (parsedCase == null || parsedCase.columns.size() != 3
                || !"主键".equals(parsedCase.columns.get(0).comment)) {
            problems.add("SQL 页双击行的元数据解析不对");
        }
        named.setSqlRowName(0, "manual_case");
        String namedText = named.textOf(caseScript.getPath());
        if (!"manual_case".equals(named.sqlRowActualName(0))
                || !"MANUAL_CASE".equals(named.sqlRowDisplayName(0))
                || !namedText.contains("CREATE TABLE `manual_case`")
                || !namedText.contains("PRIMARY KEY (`id`)")) {
            problems.add("SQL 页手改当前表名没落到这一行的脚本文本：" + tailOf(namedText));
        }
        named.setSqlRowName(0, "t_case9");

        java.io.File namedRoot = new java.io.File(dir, "named");
        named.generateExcel(true, namedRoot.getPath());
        java.io.File namedStd = new java.io.File(namedRoot, "sql_meta_data_file_" + today());
        String namedFile = "大小写检查表（T_CASE9）.xlsx";
        java.io.File namedPath = new java.io.File(namedStd, namedFile);
        // 文件名大小写要按目录清单比：Windows 文件系统不区分大小写，isFile() 会把
        // 小写名的产物也认成大写名，开关失效就查不出来
        String[] namedProduced = namedStd.list();
        java.util.Set<String> namedSet = namedProduced == null
                ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(java.util.Arrays.asList(namedProduced));
        if (!namedSet.equals(java.util.Collections.singleton(namedFile))) {
            problems.add("勾大写开关后标准件目录里的文件不对，应只有 " + namedFile + "："
                    + java.util.Arrays.toString(namedProduced));
        }
        if (namedPath.isFile()) {
            try {
                com.excel2mysql.core.Names upNames =
                        new com.excel2mysql.core.Names(
                                com.excel2mysql.core.PinyinLoader.get());
                upNames.setUpperOnly(true);
                com.excel2mysql.core.TableBuilder.Result back =
                        new com.excel2mysql.core.TableBuilder(
                                new com.excel2mysql.core.Infer(frame.names()),
                                upNames).build(namedPath.getPath());
                StringBuilder fields = new StringBuilder();
                StringBuilder comments = new StringBuilder();
                for (com.excel2mysql.core.Infer.Column c : back.table.columns) {
                    fields.append(fields.length() == 0 ? "" : "/").append(c.field);
                    comments.append(comments.length() == 0 ? "" : "/").append(c.comment);
                }
                if (!"T_CASE9".equals(back.table.tableName)
                        || !"ID/订单金额/客户名称".equals(fields.toString())
                        || !"主键/金额/客户".equals(comments.toString())) {
                    problems.add("SQL 页大小写开关生成物回读不对：表名=" + back.table.tableName
                            + " 字段=" + fields + " 注释=" + comments);
                }
            } catch (Exception e) {
                problems.add("SQL 页大小写开关生成物读不回来：" + e);
            }
        }
        String onDisk = null;
        try {
            onDisk = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(caseScript.getPath())),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            problems.add("读回命名检查脚本失败：" + e);
        }
        if (!caseSql.equals(onDisk)) {
            problems.add("勾大小写开关后脚本正文被改了");
        }
        if (!frame.sqlPage().logPanel().text().contains(
                "[命名] 生成物表名与字段名：转大写")) {
            problems.add("SQL 页生成日志没写明命名口径");
        }
        // UTF-8 正文里本来就带 U+FFFD 时，仍要按 UTF-8 解；不能拿这个合法字符当 GBK 信号
        java.io.File replScript = new java.io.File(dir, "replacement_utf8.sql");
        String replSql = "CREATE TABLE `t_repl` (`id` int NOT NULL COMMENT '\u7f16\u53f7')"
                + " COMMENT='\u7f16\u53f7\u8868 \ufffd';";
        writeText(replScript.getPath(), replSql);
        try {
            String readBack = com.excel2mysql.core.SqlRunner.readScript(replScript);
            com.excel2mysql.core.SqlScript.ParsedTable repl =
                    com.excel2mysql.core.SqlScript.parseCreateTables(readBack).get(0);
            if (!"\u7f16\u53f7".equals(repl.columns.get(0).comment)
                    || !("\u7f16\u53f7\u8868 \ufffd").equals(repl.tableComment)) {
                int at = repl.tableComment.indexOf(0xFFFD);
                String around = at < 0 ? repl.tableComment
                        : repl.tableComment.substring(Math.max(0, at - 3), Math.min(repl.tableComment.length(), at + 3));
                StringBuilder esc = new StringBuilder();
                for (int k = 0; k < around.length(); k++) {
                    esc.append(esc.length() == 0 ? "" : "/").append((int) around.charAt(k));
                }
                problems.add("含 U+FFFD 的 UTF-8 脚本被误判编码，元数据注释变成："
                        + repl.columns.get(0).comment + " / " + esc);
            }
        } catch (Exception e) {
            problems.add("读含 U+FFFD 的 UTF-8 脚本失败：" + e);
        }
    }

    private static String tailOf(String text) {
        int from = Math.max(0, text.length() - 300);
        return text.substring(from);
    }

    /** 按文字找按钮：自检按用户的方式点按钮，才验得到"返回提示 → 弹窗"这段接线。 */
    private static javax.swing.JButton buttonIn(java.awt.Container root, String text) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JButton
                    && text.equals(((javax.swing.JButton) c).getText())) {
                return (javax.swing.JButton) c;
            }
            if (c instanceof java.awt.Container) {
                javax.swing.JButton found = buttonIn((java.awt.Container) c, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * SQL 页双击一行的耗时：读入时整份解析一次并按表存好，点开只取那一张。
     *
     * 现场反馈"双击后要等一下才出元数据"。慢的不是建对话框，是那一刻把整份脚本
     * 重新解析一遍——所以夹具要够大，大到整份解析仍是人眼等得到的一档，
     * 否则下面那条"快一个量级"的判据就成了空判。
     */
    private static void sql_metadata_cache(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-sqlcache").toFile();
        } catch (java.io.IOException e) {
            problems.add("建元数据耗时检查的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        StringBuilder big = new StringBuilder();
        // 夹具与 Python 那一份同形（40 张 × 20~24 列 × 120 条 INSERT）。
        // 不放大表数：200 张那版把后面并发自检的"事件分发线程回应一次"从 200 毫秒顶到
        // 1790 毫秒（100 张顶到 916 毫秒），多出来的是列对象与表格模型，代价落在回收上；
        // 判据本身不需要大夹具——缓存失效时"只取一张"会退化成"整份重解析"，比值当场就红
        for (int t = 0; t < 40; t++) {
            big.append("CREATE TABLE t_big_").append(String.format("%02d", t))
                    .append(" (").append(nl);
            for (int c = 0; c < 20 + t % 5; c++) {
                big.append("  col_").append(String.format("%02d", c))
                        .append(" VARCHAR(64) DEFAULT NULL COMMENT '字段").append(c)
                        .append("',").append(nl);
            }
            big.append("  PRIMARY KEY (col_00)").append(nl)
                    .append(") ENGINE=InnoDB COMMENT='大脚本").append(t).append("';").append(nl);
            for (int r = 0; r < 120; r++) {
                // CREATE 之间夹大量 INSERT，行数是靠它们堆上去的（与 Python 那份同形）
                big.append("INSERT INTO t_big_").append(String.format("%02d", t))
                        .append(" VALUES ('v").append(r).append("');").append(nl);
            }
        }
        java.io.File script = new java.io.File(dir, "big_script.sql");
        writeText(script.getPath(), big.toString());

        com.excel2mysql.gui.SqlPage cache = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        cache.addFiles(paths);

        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> c1 =
                cache.parsedCacheOf(script.getPath());
        if (c1 == null || c1.size() != 40
                || c1.get("t_big_07") == null || c1.get("t_big_07").columns.size() != 22) {
            problems.add("读入大脚本时没把每张表的字段定义都缓存好："
                    + (c1 == null ? "无缓存" : c1.size() + " 张"));
            return;
        }

        // 列集合与「Excel 导入」页对齐：去掉全是 1 的出现次数，换成看得懂的表注释
        StringBuilder cols = new StringBuilder();
        for (int c = 0; c < cache.columnCount(); c++) {
            cols.append(c == 0 ? "" : "|").append(cache.columnName(c));
        }
        if (!"序号|文件|原表名|当前表名|表注释".equals(cols.toString())) {
            problems.add("SQL 页的列不是 序号|文件|原表名|当前表名|表注释：" + cols);
        }
        if (!"大脚本7".equals(cache.sqlRowComment(7))) {
            problems.add("导入脚本后表注释没显示在清单里：" + cache.sqlRowComment(7));
        }
        StringBuilder edit = new StringBuilder();
        for (int c = 0; c < cache.columnCount(); c++) {
            edit.append(c == 0 ? "" : ",").append(cache.cellEditable(7, c));
        }
        if (!"false,false,false,true,true".equals(edit.toString())) {
            problems.add("SQL 页可编辑的列不对（应是「当前表名」与「表注释」）：" + edit);
        }

        long full0 = System.nanoTime();
        com.excel2mysql.core.SqlScript.parseCreateTables(cache.textOf(script.getPath()));
        double fullMs = (System.nanoTime() - full0) / 1000000.0;
        cache.metadataAtRow(7);   // 先走一次把这条路径预热，免得把 JIT 的账算成缓存没生效
        long look0 = System.nanoTime();
        com.excel2mysql.core.SqlScript.ParsedTable looked = cache.metadataAtRow(7);
        double lookMs = (System.nanoTime() - look0) / 1000000.0;
        long open0 = System.nanoTime();
        cache.showMetadata(7);
        double openMs = (System.nanoTime() - open0) / 1000000.0;
        // 字段表左上也要有标识，标题里带列数（与两条主表格同一口径）
        String colTitle = metadataColumnTitle();
        closeMetadataDialogs();
        if (!"字段清单（共 22 列）".equals(colTitle)) {
            problems.add("元数据弹窗的字段表没有带列数的标识：" + colTitle);
        }
        System.out.println("- [INFO] GUI-SELFTEST | 元数据耗时：整份重解析 "
                + String.format("%.0f", fullMs) + " 毫秒 / 只取一张 "
                + String.format("%.2f", lookMs) + " 毫秒 / 含建对话框 "
                + String.format("%.1f", openMs) + " 毫秒");
        if (fullMs < 1.0) {
            problems.add("整份重解析测不出耗时（" + String.format("%.2f", fullMs)
                    + " 毫秒），比值判据成了拿 0 除 0");
        }
        if (looked == null || looked.columns.size() != 22) {
            problems.add("双击一行取到的字段定义不对："
                    + (looked == null ? "无表" : looked.columns.size() + " 列"));
        }
        // 缓存失效时这条路会退化成整份重解析，比值当场就红，不需要另外设夹具大小下限
        if (lookMs * 10 >= fullMs) {
            problems.add("缓存没起作用：只取一张要 " + String.format("%.2f", lookMs)
                    + " 毫秒，整份重解析才 " + String.format("%.0f", fullMs) + " 毫秒");
        }
        // 建对话框本身的开销躲不掉（Swing 建窗口 + 排 22 行），这里只管"人感知不到"那一档
        if (openMs >= 300.0) {
            problems.add("双击一行到对话框出来要 " + String.format("%.1f", openMs)
                    + " 毫秒，人已经等得出了");
        }

        cache.setSqlRowName(7, "t_big_renamed");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> c2 =
                cache.parsedCacheOf(script.getPath());
        if (c2 == null || !c2.containsKey("t_big_renamed") || c2.containsKey("t_big_07")
                || !"t_big_renamed".equals(c2.get("t_big_renamed").tableName)) {
            problems.add("改表名没把缓存里那一格一起改名");
        }
        long open2 = System.nanoTime();
        com.excel2mysql.core.SqlScript.ParsedTable after = cache.metadataAtRow(7);
        double open2Ms = (System.nanoTime() - open2) / 1000000.0;
        if (after == null || after.columns.size() != 22 || open2Ms >= 100.0) {
            problems.add("改名后点开不是毫秒级或字段少了：" + String.format("%.1f", open2Ms)
                    + " 毫秒 / " + (after == null ? "无" : after.columns.size()) + " 列");
        }

        // 撞车：新名和另一张表同名。缓存留着可用（刷新清单要读它，丢了就等于把整份
        // 重解析搬回事件分发线程），同名两行并成一行，注释与字段都取先出现那一张
        cache.setSqlRowName(0, "t_big_01");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> c3 =
                cache.parsedCacheOf(script.getPath());
        if (c3 == null || c3.size() != 39 || c3.containsKey("t_big_00")) {
            problems.add("改成同名表后缓存不对：" + (c3 == null ? "被丢掉" : c3.size() + " 键"));
        }
        com.excel2mysql.core.SqlScript.ParsedTable hit0 = cache.metadataAtRow(0);
        if (hit0 == null || hit0.columns.size() != 20 || cache.rowCount() != 39
                || !"t_big_01".equals(cache.sqlRowActualName(0))
                || !"大脚本0".equals(cache.sqlRowComment(0))) {
            problems.add("撞名后没并成一行、或注释与字段没取先出现那一张："
                    + cache.rowCount() + " 行 / " + cache.sqlRowComment(0));
        }
        long open3 = System.nanoTime();
        cache.metadataAtRow(0);
        if ((System.nanoTime() - open3) / 1000000.0 >= 100.0) {
            problems.add("撞名后同一行再点开还是慢");
        }

        // 一次改多张表：每张各改各的键，不做链式二次改名
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> seed =
                new java.util.LinkedHashMap<String, com.excel2mysql.core.SqlScript.ParsedTable>();
        seed.put("a", new com.excel2mysql.core.SqlScript.ParsedTable());
        seed.get("a").tableName = "a";
        seed.put("b", new com.excel2mysql.core.SqlScript.ParsedTable());
        seed.get("b").tableName = "b";
        java.util.Map<String, String> two = new java.util.LinkedHashMap<String, String>();
        two.put("a", "x");
        two.put("b", "y");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> rk =
                com.excel2mysql.gui.SqlPage.rekeyParsed(seed, two);
        if (rk == null || rk.size() != 2 || !rk.containsKey("x") || !rk.containsKey("y")
                || !"y".equals(rk.get("y").tableName)) {
            problems.add("一次改多张表的缓存重挂不对：" + (rk == null ? "被丢掉" : rk.keySet()));
        }
        java.util.Map<String, String> miss = new java.util.LinkedHashMap<String, String>();
        miss.put("c", "d");
        java.util.Map<String, String> clash = new java.util.LinkedHashMap<String, String>();
        clash.put("a", "b");
        java.util.Map<String, com.excel2mysql.core.SqlScript.ParsedTable> kept =
                com.excel2mysql.gui.SqlPage.rekeyParsed(seed, clash);
        if (com.excel2mysql.gui.SqlPage.rekeyParsed(seed, miss) != null
                || com.excel2mysql.gui.SqlPage.rekeyParsed(null, two) != null) {
            problems.add("要改的名字不在缓存里、或本就没缓存，其中有一种没被判成\"丢掉整份\"");
        }
        if (kept == null || kept.size() != 1 || !kept.containsKey("b")
                || kept.get("b") != seed.get("a")) {
            problems.add("新键撞车时没留先出现那一张，或整份被误丢掉："
                    + (kept == null ? "null" : kept.keySet()));
        }
    }

    /** 关掉自检期间弹出的元数据框：不关掉会攒出一堆隐藏窗口，后面的截图就不是现场。 */
    private static void closeMetadataDialogs() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof com.excel2mysql.gui.SqlMetadataDialog) {
                w.dispose();
            }
        }
    }

    /** 当前开着的那个元数据框里，字段表左上那句标识（没有就返回空串）。 */
    private static String metadataColumnTitle() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof com.excel2mysql.gui.SqlMetadataDialog) {
                return ((com.excel2mysql.gui.SqlMetadataDialog) w).columnTitle();
            }
        }
        return "";
    }

    /**
     * 表注释手动改：双击那一格要写回那句 CREATE TABLE 的表选项，
     * 一路带到元数据弹窗、清单与标准件文件名（两条线各自实现了一遍这套文本改写）。
     */
    private static void sql_comment_edit(MainFrame frame, List<String> problems) {
        java.io.File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("e2m-ccomment").toFile();
        } catch (java.io.IOException e) {
            problems.add("建表注释改写检查的临时目录失败：" + e);
            return;
        }
        String nl = String.valueOf((char) 10);
        java.io.File script = new java.io.File(dir, "注释改写.sql");
        writeText(script.getPath(),
                "CREATE TABLE `t_cm1` (`id` INT) ENGINE=InnoDB COMMENT='原注释';" + nl
                        + "CREATE TABLE t_cm2 (`id` INT);" + nl
                        + "CREATE TABLE t_cm3 LIKE t_cm2;" + nl);
        com.excel2mysql.gui.SqlPage cpage = new com.excel2mysql.gui.SqlPage(frame);
        List<String> paths = new ArrayList<String>();
        paths.add(script.getPath());
        cpage.addFiles(paths);
        if (!"原注释".equals(cpage.sqlRowComment(0))
                || !cpage.sqlRowComment(1).isEmpty() || !cpage.sqlRowComment(2).isEmpty()) {
            problems.add("三种写法的表注释没读到位：" + cpage.sqlRowComment(0) + " / "
                    + cpage.sqlRowComment(1) + " / " + cpage.sqlRowComment(2));
        }

        cpage.setSqlRowComment(0, "改过的注释");
        String text = cpage.textOf(script.getPath());
        if (!text.contains("COMMENT='改过的注释'") || text.contains("COMMENT='原注释'")
                || !cpage.isModified(script.getPath())
                || !"改过的注释".equals(cpage.sqlRowComment(0))) {
            problems.add("改表注释没写进那句 CREATE TABLE 的表选项：" + firstLine(text));
        }
        com.excel2mysql.core.SqlScript.ParsedTable cm1 = cpage.metadataAtRow(0);
        if (cm1 == null || !"改过的注释".equals(cm1.tableComment)) {
            problems.add("元数据弹窗读的还不是改过的注释："
                    + (cm1 == null ? "无表" : cm1.tableComment));
        }
        if (!cpage.logPanel().contains("[表注释] t_cm1 → 改过的注释（已写进脚本；未落盘）")) {
            problems.add("改表注释没在日志里留回执：" + tailOf(cpage.logPanel().text()));
        }

        cpage.setSqlRowComment(1, "补上的注释");
        text = cpage.textOf(script.getPath());
        if (!text.contains("CREATE TABLE t_cm2 (`id` INT) COMMENT='补上的注释';")) {
            problems.add("原本没有 COMMENT 的没在右括号后补上：" + secondLine(text));
        }
        cpage.setSqlRowComment(0, "带'单引号'的");
        text = cpage.textOf(script.getPath());
        if (!text.contains("COMMENT='带''单引号''的'")) {
            problems.add("注释里的单引号没按 MySQL 口径翻倍：" + firstLine(text));
        }
        if (cpage.metadataAtRow(0) == null
                || !"带'单引号'的".equals(cpage.metadataAtRow(0).tableComment)) {
            problems.add("翻倍写法读回来不是原话：" + cpage.sqlRowComment(0));
        }
        cpage.setSqlRowComment(0, "");
        text = cpage.textOf(script.getPath());
        if (!text.contains("COMMENT=''") || !cpage.sqlRowComment(0).isEmpty()) {
            problems.add("清空注释没写成 COMMENT='' 或清单没跟着变：" + firstLine(text));
        }
        if (!cpage.logPanel().contains("[表注释] t_cm1 → （空）（已写进脚本；未落盘）")) {
            problems.add("清空注释的回执没写清是空：" + tailOf(cpage.logPanel().text()));
        }

        String beforeLike = text;
        cpage.setSqlRowComment(2, "LIKE 那句改不动");
        if (!beforeLike.equals(cpage.textOf(script.getPath()))
                || !frame.lastDialogText().contains("没有可改写的表选项")
                || cpage.logPanel().contains("[表注释] t_cm3")) {
            problems.add("LIKE 写法该被拦下并说清原因，实际正文：" + tailOf(
                    cpage.textOf(script.getPath())));
        }
        // 只多打了一个空格：内容其实没变，但这一条也必须有回执，且刷回清单时把空格去掉
        cpage.setSqlRowComment(1, "补上的注释 ");
        if (!cpage.logPanel().contains("[表注释] t_cm2 未发生变化，脚本未改写")
                || !"补上的注释".equals(cpage.sqlRowComment(1))) {
            problems.add("填回一模一样的注释没有任何回执，或空格没被刷掉："
                    + cpage.sqlRowComment(1));
        }
    }

    private static String firstLine(String text) {
        int at = text.indexOf((char) 10);
        return at < 0 ? text : text.substring(0, at);
    }

    private static String secondLine(String text) {
        String[] lines = text.split(String.valueOf((char) 10));
        return lines.length > 1 ? lines[1] : "";
    }

    /**
     * 首屏分栏：日志区默认压到它自己的最小高度，余量按 3:2 给表格（用户口径"日志低、表格高"）。
     * 离屏构造时窗口还没真布局，先按生产窗口尺寸排一次，再量日志段是不是落在最小高度。
     */
    private static void split_defaults(MainFrame frame, List<String> problems) {
        if (Math.abs(frame.excelLowerResizeWeight() - 0.6) > 0.001) {
            problems.add("第一页分栏拉伸比例不对，应为 3:2：" + frame.excelLowerResizeWeight());
        }
        frame.setSize(wantPx(1100), wantPx(780));
        frame.validate();
        frame.applySplitProportions();
        frame.validate();
        if (!"1".equals(frame.logAtMin())) {
            problems.add("第一页日志默认没有压到最小高度：高 "
                    + frame.logBoxHeight() + " / 最小 " + frame.logBoxMinHeight());
        }
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
        // 分栏配置照 Python：把手 6px、余量按 3:2 给表格与日志、初始 360（设计值按 DPI 折算）
        if (page.splitDividerSize() != wantPx(6)) {
            problems.add("SQL 页分栏把手宽度不是 " + wantPx(6) + "px：" + page.splitDividerSize());
        }
        if (Math.abs(page.splitResizeWeight() - 0.6) > 0.001) {
            problems.add("SQL 页分栏拉伸比例不对，应为 3:2（表格多于日志）："
                    + page.splitResizeWeight());
        }
        // 日志段默认压到最小高度；切到这一页排一次版再量（隐藏页签的子控件还没量过）
        frame.selectTab(1);
        frame.validate();
        page.applySplitProportions();
        frame.validate();
        if (!"1".equals(page.logAtMin())) {
            problems.add("SQL 页日志默认没有压到最小高度：高 "
                    + page.logBoxHeight() + " / 最小 " + page.logBoxMinHeight());
        }
        frame.selectTab(0);
        frame.validate();
        if (!page.dropExistingBox().getText().equals("覆盖已存在表（先 DROP 再建）")
                || page.dropExistingBox().isSelected()) {
            problems.add("SQL 页覆盖开关的文案或默认状态不对："
                    + page.dropExistingBox().getText() + " / "
                    + page.dropExistingBox().isSelected());
        }
        // 三段结构与「Excel 导入」那页一致：拖拽区在最上、表格区在中、日志在下
        if (!"拖拽区|表格区|日志区".equals(page.segmentOrder())) {
            problems.add("SQL 页的三段分栏顺序不对：" + page.segmentOrder());
        }
        // 每张表格左上都要有标识（用户口径，第一页的「待导入文件」就是样子）
        if (!com.excel2mysql.gui.Tables.FILE_TABLE_TITLE.equals(
                com.excel2mysql.gui.Tables.titledAncestor(frame.fileTable()))) {
            problems.add("第一页主表格左上没有标识："
                    + com.excel2mysql.gui.Tables.titledAncestor(frame.fileTable()));
        }
        if (!com.excel2mysql.gui.Tables.SQL_TABLE_TITLE.equals(page.tableTitle())) {
            problems.add("第二页主表格左上的标识不对：" + page.tableTitle());
        }
        com.excel2mysql.gui.DropArea sqlDrop = page.sqlDropArea();
        if (sqlDrop == null || !sqlDrop.acceptsDrops() || !sqlDrop.hasClickAction()) {
            problems.add("SQL 页的拖拽区没接上拖放或点击选文件");
        }
        if (!com.excel2mysql.gui.DropArea.SQL_HINT.equals(sqlDrop.hintText())) {
            problems.add("SQL 页的拖拽区文案不是这一页那句：" + sqlDrop.hintText());
        }
        if (buttonIn(frame, "添加 SQL 文件") != null) {
            problems.add("第二页还留着「添加 SQL 文件」那颗按钮，入口没统一");
        }
        // 拖进来的路径只收 .sql：混着的点名跳过，一个都没收到时也得说一句
        java.io.File dropDir;
        try {
            dropDir = java.nio.file.Files.createTempDirectory("e2m-sqldrop").toFile();
        } catch (java.io.IOException e) {
            problems.add("建拖拽检查的临时目录失败：" + e);
            return;
        }
        String dropped = new java.io.File(dropDir, "拖进来的.sql").getPath();
        writeText(dropped, "CREATE TABLE t_drag (id INT);" + String.valueOf((char) 10));
        String notSql = new java.io.File(dropDir, "说明.txt").getPath();
        writeText(notSql, "不是脚本");
        com.excel2mysql.gui.SqlPage dpage = new com.excel2mysql.gui.SqlPage(frame);
        int before = dpage.entryCount();
        dpage.sqlDropArea().fireDrop(java.util.Arrays.asList(dropped, notSql));
        if (dpage.entryCount() != before + 1
                || !dpage.logPanel().contains("[跳过] 不是 SQL 脚本，未读入：说明.txt")) {
            problems.add("拖进来的 .sql 没读入，或混着的别的文件没点名跳过："
                    + dpage.entryCount() + " 个");
        }
        String dropLog = dpage.logPanel().text();
        dpage.sqlDropArea().fireDrop(java.util.Collections.singletonList(notSql));
        if (dpage.entryCount() != before + 1
                || dropLog.equals(dpage.logPanel().text())) {
            problems.add("一个 .sql 都没拖进来时没有任何回执");
        }
        if (!dpage.logPanel().contains("[提示] 拖入的不是 SQL 脚本文件，已忽略。")) {
            problems.add("全是非脚本时的说法与第一页不同：" + dpage.logPanel().text());
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
        if (!"添加结果".equals(frame.lastDialogTitle())
                || !frame.lastDialogText().replace("\n", "").contains(a)
                || !frame.lastDialogText().replace("\n", "").contains(b)) {
            problems.add("添加结果弹窗没列出脚本完整路径：" + frame.lastDialogTitle());
        }
        // 四个 SQL 任务都要走主窗口的任务位，标题得是 Python 的那句原话
        if (!"正在读取 SQL 脚本…".equals(frame.lastTaskTitle())) {
            problems.add("读取任务标题不对：" + frame.lastTaskTitle());
        }
        if (page.rowCount() != 3) {
            problems.add("表格按表名出行，应有 3 行，实际 " + page.rowCount());
        }
        if (!"1".equals(page.cellText(0, 0)) || !"3".equals(page.cellText(2, 0))) {
            problems.add("SQL 表序号列没按行号连续编号：" + page.cellText(0, 0)
                    + "/" + page.cellText(2, 0));
        }
        if (!page.logPanel().contains("[已加载] a.sql：2 个表")) {
            problems.add("加载日志没报每个文件的表数");
        }
        // 覆盖执行：执行副本补 DROP、正文不动；只认整句 CREATE TABLE，注释里的建表不删
        String originalA = page.textOf(a);
        List<String[]> dropNames = new ArrayList<String[]>();
        List<com.excel2mysql.gui.SqlPage.Src> runItems = page.prepareRunItems(true, dropNames);
        String expectedA = "DROP TABLE IF EXISTS plain;" + nl
                + "DROP TABLE IF EXISTS t_order;" + nl + nl + originalA;
        if (runItems.size() != 2 || !expectedA.equals(runItems.get(0).text)
                || !runItems.get(1).text.startsWith("DROP TABLE IF EXISTS dim_x;" + nl)) {
            problems.add("勾选覆盖后执行副本没有先 DROP 脚本要建的表："
                    + (runItems.isEmpty() ? "空" : runItems.get(0).text.split(nl)[0]));
        }
        if (dropNames.size() != 2 || !"t_order、plain".equals(dropNames.get(0)[1])
                || !"dim_x".equals(dropNames.get(1)[1])) {
            problems.add("覆盖执行报出的表名清单不对：" + dropNames.size());
        }
        if (!originalA.equals(page.textOf(a))) {
            problems.add("覆盖执行改到了脚本正文");
        }
        List<String[]> noneDropped = new ArrayList<String[]>();
        if (!originalA.equals(page.prepareRunItems(false, noneDropped).get(0).text)
                || !noneDropped.isEmpty()) {
            problems.add("不勾选覆盖时执行副本不该带 DROP");
        }
        String commented = "-- CREATE TABLE ghost (id INT);" + nl
                + "/* CREATE TABLE ghost2 (id INT); */" + nl
                + "CREATE TABLE `db`.`t9` (id INT);" + nl
                + "CREATE TABLE t9 (id INT);" + nl;
        com.excel2mysql.core.SqlScript.DropPrep dropEdge =
                com.excel2mysql.core.SqlScript.prependDropTables(commented);
        if (!dropEdge.tables.equals(java.util.Arrays.asList("`db`.`t9`", "t9"))
                || !dropEdge.text.startsWith("DROP TABLE IF EXISTS t9;" + nl
                        + "DROP TABLE IF EXISTS `db`.`t9`;" + nl + nl)) {
            problems.add("覆盖执行的判据不对（注释/重名/schema 限定）：" + dropEdge.tables);
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
        // 空前缀不是错误：两个前缀按钮都把它当作"撤掉显示层、恢复脚本原名"
        if (page.applyPrefix() != null) {
            problems.add("空前缀应视作撤掉显示层，而不是拦下");
        }
        if (!page.logPanel().contains("前缀为空，表名显示本来就是脚本原名")) {
            problems.add("空前缀没有说明表名保持脚本原名");
        }

        page.setPrefixText("ods_");
        if (page.applyPrefix() != null) {
            problems.add("合法前缀被拦住了");
        }
        String text = page.textOf(a);
        if (text.contains("ods_t_order") || text.contains("ods_plain")) {
            problems.add("「批量应用前缀」不该改脚本正文：" + text);
        }
        if (page.isModified(a)) {
            problems.add("只改显示的前缀不该把脚本标记成已修改");
        }
        if (!page.cellText(0, 3).equals("ods_t_order")) {
            problems.add("「当前表名」列没跟着显示前缀走：" + page.cellText(0, 3));
        }
        if (!page.cellText(0, 2).equals("t_order")) {
            problems.add("「原表名」列显示的不是最初的表名：" + page.cellText(0, 2));
        }
        if (!"ods_t_order".equals(page.metadataShownName(0))) {
            problems.add("元数据弹窗没跟着显示前缀：" + page.metadataShownName(0));
        }
        if (!page.logPanel().contains("[前缀完成] 共 3 个表名改为显示前缀「ods_」")) {
            problems.add("前缀完成没有汇总几个表名改了显示");
        }
        int rowsBefore = page.rowCount();
        page.setPrefixText("ods_");
        page.applyPrefix();
        if (page.rowCount() != rowsBefore || page.cellText(0, 3).contains("ods_ods_")) {
            problems.add("重复加同一个前缀叠了一层");
        }
        page.setPrefixText("");
        page.applyPrefix();
        if (!page.cellText(0, 3).equals("t_order") || !page.cellText(2, 3).equals("dim_x")) {
            problems.add("清空前缀再点一次没恢复脚本原名：" + page.cellText(0, 3)
                    + "/" + page.cellText(2, 3));
        }
        if (!page.logPanel().contains("前缀已清空")) {
            problems.add("清空前缀没有留下恢复原名的日志");
        }
        if (page.textOf(a).contains("ods_t_order")) {
            problems.add("清空前缀不该动脚本正文：" + page.textOf(a));
        }

        page.setPrefixText("ods_");
        page.applyPrefix();
        page.setPrefixText("change");
        if (page.replaceFirstPrefix() != null) {
            problems.add("替换首个前缀被拦住");
        }
        String after = page.textOf(a);
        if (after.contains("change_t_order") || after.contains("change_plain")) {
            problems.add("「替换首个前缀」不该改脚本正文：" + after);
        }
        if (!page.cellText(0, 3).equals("change_t_order")
                || !page.cellText(1, 3).equals("change_plain")) {
            problems.add("替换首段没改到显示名：" + page.cellText(0, 3)
                    + "/" + page.cellText(1, 3));
        }
        if (!page.cellText(2, 3).equals("change_dim_x")) {
            problems.add("ods_dim_x 的首段在显示层没被换掉：" + page.cellText(2, 3));
        }
        if (page.isModified(a) || page.isModified(b)) {
            problems.add("只改显示的换前缀不该标记成已修改");
        }

        page.saveModified();
        if (!page.textOf(a).contains("CREATE TABLE change_t_order")
                || !page.textOf(a).contains("INSERT INTO `change_t_order`")) {
            problems.add("保存时没把显示名写进脚本：" + page.textOf(a));
        }
        if (!page.textOf(b).contains("change_dim_x")) {
            problems.add("ods_dim_x 的首段没被换掉：" + page.textOf(b));
        }
        if (page.isModified(a) || page.isModified(b)) {
            problems.add("两个改过的脚本都该落盘并清掉已修改标记");
        }
        if (!"正在保存修改后的脚本…".equals(frame.lastTaskTitle())) {
            problems.add("保存任务标题不对：" + frame.lastTaskTitle());
        }
        if (!page.logPanel().contains("[生效] ")) {
            problems.add("显示名写入脚本没有留下日志");
        }
        if (!page.logPanel().contains("[已保存] ")) {
            problems.add("落盘成功没有报出写了哪个文件");
        }
        if (!"保存完成".equals(frame.lastDialogTitle())) {
            problems.add("保存后没有结果弹窗：" + frame.lastDialogTitle());
        }
        String outA = com.excel2mysql.gui.SqlPage.modifiedPath(a);
        if (!frame.lastDialogText().replace("\n", "").contains(outA)) {
            problems.add("保存结果弹窗没列出副本完整路径：" + frame.lastDialogText());
        }
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

        // 真实 dump 的编码场景：UTF-8 正文里夹着原样导出的二进制字段，第二页表格里的
        // 表注释必须是中文。整份回落 GBK 时这里会显示「瀵嗛挜」。
        try {
            java.io.File mixedFile = new java.io.File(dir, "mixed.sql");
            byte[] head = ("CREATE TABLE `enc_mixed` (`id` INT COMMENT '密钥')"
                    + " COMMENT='密钥表';" + nl).getBytes("UTF-8");
            byte[] blob = new byte[] {(byte) 0xCE, 0x4E, 0x5E, 0x5C, 0x30,
                    0x00, 0x02, (byte) 0xFF, (byte) '\n'};
            byte[] mixedBytes = new byte[head.length + blob.length];
            System.arraycopy(head, 0, mixedBytes, 0, head.length);
            System.arraycopy(blob, 0, mixedBytes, head.length, blob.length);
            java.nio.file.Files.write(mixedFile.toPath(), mixedBytes);
            com.excel2mysql.gui.SqlPage encPage = new com.excel2mysql.gui.SqlPage(frame);
            encPage.addFiles(java.util.Collections.singletonList(mixedFile.getPath()));
            String encComment = encPage.rowCount() == 1 ? encPage.cellText(0, 4) : "";
            if (!"密钥表".equals(encComment)) {
                problems.add("夹二进制字段的 UTF-8 脚本，表注释读成了乱码：" + encComment);
            }
            com.excel2mysql.core.SqlScript.ParsedTable encParsed = encPage.metadataAtRow(0);
            String encFieldComment = encParsed == null || encParsed.columns.isEmpty()
                    ? "" : encParsed.columns.get(0).comment;
            if (!"密钥".equals(encFieldComment)) {
                problems.add("夹二进制字段的 UTF-8 脚本，字段注释读成了乱码：" + encFieldComment);
            }
        } catch (java.io.IOException e) {
            problems.add("建混合编码脚本夹具失败：" + e);
        }

        String hint = page.execute();
        if (hint == null || !hint.contains("未选择目标库")) {
            problems.add("没选库时执行应当拦下并说明：" + hint);
        }
        List<com.excel2mysql.gui.SqlPage.RunFile> runFiles =
                new ArrayList<com.excel2mysql.gui.SqlPage.RunFile>();
        runFiles.add(new com.excel2mysql.gui.SqlPage.RunFile(a, "fail",
                "第 3/9 条语句报错，本文件事务已回滚"));
        runFiles.add(new com.excel2mysql.gui.SqlPage.RunFile(b, "ok", "4 条语句已提交"));
        String summary = page.finishRun(new com.excel2mysql.gui.SqlPage.RunResult(
                1, 1, 0, false, false, runFiles, "testdb@127.0.0.1:3306"));
        if (!summary.contains("成功 1 个文件，失败 1 个文件")) {
            problems.add("执行收尾文案不对：" + summary.replace((char) 10, ' '));
        }
        if (!summary.contains("目标库 testdb@127.0.0.1:3306")
                || !summary.contains(a) || !summary.contains(b)) {
            problems.add("执行结果弹窗没列出目标库与脚本完整路径");
        }
        if (!page.logPanel().isErrorLine("===== 执行完成：成功 1 个文件，失败 1 个文件")) {
            problems.add("有失败时执行收尾行没标成错误级");
        }

        int cleared = page.clearAllFiles();
        if (cleared != 2) {
            problems.add("「清空待上传文件」应整份清出清单，实际清掉 " + cleared + " 个");
        }
        if (page.entryCount() != 0 || page.rowCount() != 0) {
            problems.add("清空后文件与行数都不对：" + page.entryCount() + "/" + page.rowCount());
        }
        if (page.genButtonsEnabled()) {
            problems.add("清空待上传文件后生成按钮还亮着");
        }
        if (!page.logPanel().contains("[清空] 已清空 2 个脚本文件")) {
            problems.add("清空待上传文件没有留痕");
        }
        page.clearAllFiles();
        if (!page.logPanel().contains("[清空] 清单里没有脚本文件，无需清空")) {
            problems.add("空清单再点清空也要说一句，不能静默不动");
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
        // 命令行与界面必须同源同版：以前 Main.java 另写一份常量，升版本只改到界面，
        // --version 和启动日志会一直报旧号。这里直接跑一次 --version 把两边对上。
        String wantVersion = com.excel2mysql.core.AppInfo.load().versionText();
        java.io.PrintStream oldOut = System.out;
        String cliVersion;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            System.setOut(new java.io.PrintStream(buf, true, "UTF-8"));
            int code = Main.run(new String[]{"--version"});
            cliVersion = new String(buf.toByteArray(), "UTF-8").trim();
            if (code != 0) {
                problems.add("命令行 --version 的退出码是 " + code);
            }
        } catch (Exception e) {
            cliVersion = "";
            problems.add("命令行 --version 跑不起来：" + e);
        } finally {
            System.setOut(oldOut);
        }
        if (!cliVersion.contains(wantVersion)) {
            problems.add("命令行 --version 与界面版本不同源：" + cliVersion);
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
            // Qt 在 150% 屏上把 10.5pt 渲染成 21 个物理像素；Swing 的 HTML 渲染器
            // 又会把 CSS px 按 4/3 放大，所以 CSS 里只该写 21*3/4=15.75px。
            // 期望值同样独立按 DPI 算，不引用 UiScale 和 HelpDialog 的折算。
            String helpHtml = hd.html();
            int helpTarget = wantFontPx(10.5);
            double helpCss = helpTarget * 0.75;
            String helpCssText = helpCss == Math.rint(helpCss)
                    ? String.valueOf((int) helpCss) : String.valueOf(helpCss);
            String wantBodyPx = "font-size: " + helpCssText + "px";
            if (!helpHtml.contains(wantBodyPx)
                    || java.util.regex.Pattern.compile("font-size:\\s*[0-9.]+pt")
                    .matcher(helpHtml).find()) {
                problems.add("说明正文字号没折成 " + wantBodyPx
                        + "：Swing 会把物理像素再放大 4/3");
            }
            // 字符串对不代表画出来对：量首段第一行，把 4/3 放大真的咬住
            int helpLine = hd.bodyLineHeight();
            int wantHelpLine = hd.getFontMetrics(new java.awt.Font(
                    "Microsoft YaHei", java.awt.Font.PLAIN, helpTarget)).getHeight();
            if (helpLine < 0 || Math.abs(helpLine - wantHelpLine) > 4) {
                problems.add("说明正文渲染行高 " + helpLine + "px，应接近 "
                        + helpTarget + "px 字号的 " + wantHelpLine + "px");
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
            String[] named = {"批量应用前缀", "替换首个前缀", "生成表结构预览",
                    "预览 DDL", "开始导入", "清空待上传文件", "保存修改后ddl脚本",
                    "执行修改后ddl脚本到当前库", "连接并加载库",
                    "生成标准excel文件", "生成简易excel文件", "示例 (?)",
                    "清空编辑区", "清空结果区", "设置数据源信息", "收起"};
            for (String n : named) {
                if (!onUi.contains(n)) {
                    problems.add("说明里点名的按钮界面上不存在：" + n);
                }
                if (!doc.contains(n)) {
                    problems.add("按钮没被使用说明覆盖：" + n);
                }
            }
            if (!doc.contains("点击虚线区选文件") && !doc.contains("点一下虚线区")) {
                problems.add("使用说明没交代点击虚线区就能选文件");
            }
        } finally {
            hd.dispose();
        }
        com.excel2mysql.gui.AboutDialog ad = new com.excel2mysql.gui.AboutDialog(null);
        try {
            // 首屏不裁字：版本号/提交号/构建日期三行都按自己的首选宽度检查一遍
            String fit = ad.fitProblems();
            if (!fit.isEmpty()) {
                problems.add("关于框首屏文字摆不下：" + fit);
            }
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
        // 长提交号也要一眼看全：开发态会带分支名与“工作区有未提交改动”，
        // 比打包态的 7 位短号长得多；旧的两列等宽布局就是在这里把字裁掉的
        com.excel2mysql.core.AppInfo longInfo = com.excel2mysql.core.AppInfo.load();
        longInfo.version = "9.9.9";
        longInfo.commit = "1234567";
        longInfo.branch = "feature/excel2mysql-empty-table-primary-key";
        longInfo.dirty = true;
        com.excel2mysql.gui.AboutDialog wide =
                new com.excel2mysql.gui.AboutDialog(null, longInfo);
        try {
            String longFit = wide.fitProblems();
            if (!longFit.isEmpty()) {
                problems.add("关于框遇到长提交号会裁字：" + longFit);
            }
        } finally {
            wide.dispose();
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
     * 进度框那条折行规则的红线：预算、行数、不丢字、截断时留话。
     *
     * <p>结果弹窗已经不再自己折行（正文原样交给 JTextArea 按右边界折），
     * 这套列预算现在只管进度框那一小段文字——进度框尺寸固定、没有滚动条，
     * 折与截都得自己算。它决定用户看得见哪些字，所以除了常量本身，
     * 再钉一遍绝对约束。
     */
    private static void checkPopupWrap(java.util.List<String> problems) {
        // 期望值写死成契约数字：拿常量当期望值的断言是恒真的，
        // 把预算改成 400 它照样绿，洞还留着。常量本身另外钉一条。
        if (com.excel2mysql.gui.PopupText.WRAP_COLS != 72
                || com.excel2mysql.gui.PopupText.MAX_LINES != 24) {
            problems.add("折行预算与上限应当是 72 列 / 24 行，实际 "
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
            if (lines.length > 24) {
                problems.add("折行后仍有 " + lines.length + " 行，超过 24 行上限");
            }
            for (String ln : lines) {
                if (com.excel2mysql.gui.PopupText.width(ln) > 72) {
                    problems.add("折行后有一行超出 72 列预算：" + ln);
                }
            }
            if (lines.length == 24
                    && !lines[lines.length - 1].contains("完整内容见底部日志")) {
                problems.add("行数正好卡在 24 上限，却没告诉用户其余内容在日志里");
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
     * 「执行修改后ddl脚本到当前库」任务体的连不上库那一支：端口 1 保证秒失败，不需要真库也能把
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
                "CREATE TABLE a (id INT);" + nl + "INSERT INTO a VALUES (1);" + nl));
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

        int[] want = {54, 320, 200, 280, 80, 90, 160};
        javax.swing.table.TableColumnModel cols = frame.fileTable().getColumnModel();
        if (cols.getColumnCount() != want.length) {
            problems.add("文件表应有 7 列（序号 + 原 6 列），实际 " + cols.getColumnCount());
        }
        for (int i = 0; i < want.length && i < cols.getColumnCount(); i++) {
            int expected = wantPx(want[i]);
            if (cols.getColumn(i).getPreferredWidth() != expected) {
                problems.add("文件表第 " + (i + 1) + " 列宽不是 " + expected + "，实际 "
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
            // Qt 的 resize(480,140) 说的是逻辑像素的客户区，Swing 这边要按 DPI 折算；
            // 量内容面板而不是整个窗，外框不参与比较
            java.awt.Dimension client = pd.getContentPane().getPreferredSize();
            int wantW = (int) Math.round(480 * Math.max(1.0,
                    java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96.0));
            int wantH = (int) Math.round(140 * Math.max(1.0,
                    java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96.0));
            if (client.width != wantW || client.height != wantH) {
                problems.add("进度框客户区应为 " + wantW + "x" + wantH
                        + "（Python 480x140 折算后），实际 " + client);
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
            // 进度框必须是非模态的：模态框挡在中间时另外两页根本点不动，用户只能干等
            // 或者强关窗口（现场投诉的就是这个）
            if (pd.isModal()) {
                problems.add("进度框还是模态的，任务在跑时别的页点不动");
            }
            // 台账按绝对值刷框：非模态之后框可能还没建好、也可能已经被关掉，
            // 只会被 step 一格推着走的框报不出"现在是 i/N"
            pd.setTotal(10);
            pd.setProgress(4, 10, "第 4/10 条");
            if (pd.getDone() != 4 || !"进度 4/10 项 · 第 4/10 条".equals(pd.statusText())) {
                problems.add("按绝对值刷进度没生效：" + pd.getDone() + " " + pd.statusText());
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
        // 抢输的那一句必须说得出"谁在跑、跑到几成、跑了多久"，只回一句请稍等等于没说
        if (!frame.logPanel().contains("已有任务在跑：")) {
            problems.add("被挡下时没说清是谁在跑：" + tail(frame.logPanel().text()));
        }
        if (!frame.logPanel().contains("%") || !frame.logPanel().contains("秒")) {
            problems.add("被挡下的那句里没有百分比或没有已跑时长："
                    + tail(frame.logPanel().text()));
        }
        if (!frame.lastDialogText().contains("已有任务在跑：")) {
            problems.add("被挡下只写了日志、没弹窗（用户可能正看着别处）："
                    + frame.lastDialogText());
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
     * conn.ini 预填连接信息：三种读法各验一条（读到了 / 找到了但读不了 / 压根没有）。
     *
     * 前两条各另起一个窗口——预填发生在建窗那一刻，改了候选路径要重新建窗才走得到那段代码。
     * 第三条不另起窗口：主窗口自己就是在没有 conn.ini 的目录下建起来的，
     * 直接看它启动那一句。一个 MainFrame 连着表格、字体与日志面板，
     * 这里多起三个曾把 JVM 顶到 native OOM（malloc 失败，闸门整步崩）。
     */
    private static void conn_ini(MainFrame frame, List<String> problems) {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "e2m-conn");
        deleteTree(dir);
        dir.mkdirs();
        java.io.File good = new java.io.File(dir, "conn.ini");
        writeText(good.getPath(), "[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
                + "password = pw-file\ndatabase = alpha\n");
        java.io.File bad = new java.io.File(dir, "bad.ini");
        writeText(bad.getPath(), "host = 1.2.3.4\n");      // 没有小节名，读不了
        java.io.File blank = new java.io.File(dir, "blank.ini");
        writeText(blank.getPath(), "[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
                + "password = pw-file\ndatabase =\n");     // 读得到，但没点名目标库
        List<String> keep = MainFrame.connCandidates;
        try {
            MainFrame.connCandidates = java.util.Collections.singletonList(good.getPath());
            MainFrame f1 = new MainFrame();
            com.excel2mysql.core.Config c = f1.currentConfig();
            String log1 = f1.logPanel().text();
            // 目标库不在连接栏那四个输入框里（它是库列表加载出来之后的下拉框），
            // 所以这里只比四个框；配置里的库有没有被记住，看下面那句日志
            if (!"10.0.0.8".equals(c.host) || c.port != 3307 || !"ops".equals(c.user)
                    || !"pw-file".equals(c.password)) {
                problems.add("conn.ini 没把连接信息预填进去：" + c);
            }
            if (!log1.contains("连接信息预填 ops@10.0.0.8:3307/alpha")
                    || !log1.contains("自动发现")) {
                problems.add("预填成功了却没说清来源：" + tail(log1));
            }
            if (log1.contains("pw-file")) {
                problems.add("口令被写进日志正文了：日志里不许出现配置文件里的密码");
            }
            // 启动自动连接：配置里写了库，窗口出来后应自己连上并选中它。
            // 自检不显示窗口，直接调 windowOpened 会调的那支，走的仍是生产代码。
            if (!f1.isAutoConnectEligible()) {
                problems.add("conn.ini 读到了却没被记为「用户明确配过」，启动不会自动连接");
            }
            MainFrame.fetchOverride = () -> Arrays.asList("alpha", "beta");
            f1.autoConnect();
            String autoLog = f1.logPanel().text();
            if (!"alpha".equals(f1.selectedDatabase())
                    || !autoLog.contains("[连接中] 启动自动连接 10.0.0.8:3307（用户 ops）")
                    || !autoLog.contains("[已加载] 目标库按 conn.ini 选中：alpha")) {
                problems.add("有 conn.ini 时启动自动连接没连上/没选中目标库："
                        + tail(autoLog));
            }
            // 配置的库不在服务器上：要解释，不能让人以为配置没生效
            f1.applyDatabases(f1.currentConfig(), Arrays.asList("beta", "gamma"));
            if (!f1.logPanel().text().contains("[跳过] conn.ini 指定的目标库 alpha 不在服务器上")) {
                problems.add("目标库不在服务器上时没有解释，用户会回头去改配置文件："
                        + tail(f1.logPanel().text()));
            }
            f1.dispose();

            MainFrame.connCandidates = java.util.Collections.singletonList(bad.getPath());
            MainFrame f2 = new MainFrame();
            String log2 = f2.logPanel().text();
            if (!log2.contains("但读不了") || log2.contains("没找到 conn.ini")) {
                problems.add("文件读不了却报成没找到，用户会去找那个并不存在的文件："
                        + tail(log2));
            }
            if (!"127.0.0.1".equals(f2.currentConfig().host)) {
                problems.add("读不了的文件没退回内置默认值：" + f2.currentConfig().host);
            }

            // 没配过就不连：换成一个会计数的取数函数，确认一次都没被调用
            final int[] autoCalls = {0};
            MainFrame.fetchOverride = () -> {
                autoCalls[0]++;
                return Arrays.asList("alpha");
            };
            String before = f2.logPanel().text();
            f2.autoConnect();
            if (autoCalls[0] != 0 || f2.logPanel().text().contains("[连接中] 启动自动连接")
                    || !f2.logPanel().text().equals(before)) {
                problems.add("没有明确配置（文件读不了）时仍然去连了库："
                        + tail(f2.logPanel().text()));
            }
            f2.dispose();

            // 读得到但 database 留空（随包空模板就是这个形状）：不许自动连，
            // 否则新装用户一开界面就看到一条连本机 3306 的失败日志
            MainFrame.connCandidates = java.util.Collections.singletonList(blank.getPath());
            MainFrame f2b = new MainFrame();
            final int[] blankCalls = {0};
            MainFrame.fetchOverride = () -> {
                blankCalls[0]++;
                return Arrays.asList("alpha");
            };
            String blankBefore = f2b.logPanel().text();
            if (f2b.isAutoConnectEligible()) {
                problems.add("conn.ini 没点目标库也算「明确配置」，会去连本机默认库");
            }
            f2b.autoConnect();
            if (blankCalls[0] != 0 || !f2b.logPanel().text().equals(blankBefore)
                    || f2b.logPanel().text().contains("[连接中] 启动自动连接")) {
                problems.add("空模板 conn.ini 仍然触发了自动连接："
                        + tail(f2b.logPanel().text()));
            }
            f2b.dispose();

            // 自动连接失败只写日志、不弹模态框：弹出会让人以为软件打不开。
            // 这里若真弹了框，自检会挂死（与本文件其它弹窗路径同一判据）。
            MainFrame.connCandidates = java.util.Collections.singletonList(good.getPath());
            MainFrame.fetchOverride = () -> {
                throw new java.sql.SQLException("(2003) 模拟连不上");
            };
            MainFrame f3 = new MainFrame();
            f3.autoConnect();
            String failLog = f3.logPanel().text();
            if (!failLog.contains("[连接失败] 启动自动连接失败")
                    || !failLog.contains("库列表未加载")
                    || !failLog.contains("设置数据源信息")
                    || f3.databaseChooserEnabled()) {
                problems.add("自动连接失败没有写清原因/重试入口，或把下拉框留成了可用："
                        + tail(failLog));
            }
            f3.dispose();

            // 没有配置文件那一条看主窗口的启动句：它就是在没有 conn.ini 的目录下建起来的
            String log0 = frame.logPanel().text();
            if (!log0.contains("没找到 conn.ini") || !"127.0.0.1".equals(frame.currentConfig().host)
                    || !"root".equals(frame.currentConfig().user)) {
                problems.add("没有配置文件时没说清是按默认值起界面：" + tail(log0));
            }
        } finally {
            MainFrame.connCandidates = keep;
            MainFrame.fetchOverride = null;
        }
    }

    /**
     * 按资源占位：同一页互斥、不同页可以一起跑；两页的「生成」各写各的目录，也能一起跑。
     *
     * 上一轮是一个布尔量锁住三页——执行器跑上千条语句时另外两页连点都点不动，
     * 而被挡下的人只看到一句"请稍等"。这里断的是"别的页在跑，这一页照样起得来"这件事本身。
     */
    private static void task_slots(MainFrame frame, List<String> files, List<String> problems) {
        if (!frame.runningTasks().isEmpty()) {
            problems.add("进到这里还留着上一项的任务，后面的判断全废了："
                    + frame.runningTasks().size());
            return;
        }
        if (!frame.startTask(MainFrame.RES_EXCEL)) {
            problems.add("空窗口占不住 Excel 页的资源位");
            return;
        }
        if (!frame.startTask(MainFrame.RES_SQL)) {
            problems.add("Excel 页在跑时 SQL 页的任务被挡下了，三页还是共用一个任务位");
        }
        if (frame.runningTasks().size() != 2) {
            problems.add("两个不同页的任务没能同时在跑：" + frame.runningTasks().size());
        }
        if (!frame.taskBusy(MainFrame.RES_EXCEL) || !frame.taskBusy(MainFrame.RES_SQL)) {
            problems.add("占了资源位却查不出它在跑");
        }
        if (frame.taskBusy(MainFrame.RES_RUN)) {
            problems.add("没被占用的资源位也报成在跑，等于又变回全局一把锁");
        }
        // 同一页的第二个任务必须被挡下，而且要说得出共用的是哪一份数据
        frame.startTask(MainFrame.RES_SQL);
        if (frame.runningTasks().size() != 2) {
            problems.add("同一页的第二个任务挤进来了：" + frame.runningTasks().size());
        }
        if (!frame.lastDialogText().contains("SQL 脚本改名入库页的脚本列表")) {
            problems.add("被挡下时没点名共用的是哪一份数据：" + frame.lastDialogText());
        }
        frame.endTask();
        frame.endTask();

        // 两页的「生成」各写各的目录：Excel 页 meta_data_file_年月日、
        // SQL 页 sql_meta_data_file_年月日。目录分开了才谈得上并行——以前同名表会写进
        // 同一个日期目录互相覆盖，那才是两颗按钮必须互斥的真正理由，不是资源位。
        String plainDir = com.excel2mysql.core.MetaExport.outputDir(
                "root", true, "20261022");
        String sqlDir = com.excel2mysql.core.MetaExport.outputDir(
                "root", true, "20261022", com.excel2mysql.core.MetaExport.SQL_DIR_TAG);
        String sqlSimple = com.excel2mysql.core.MetaExport.outputDir(
                "root", false, "20261022", com.excel2mysql.core.MetaExport.SQL_DIR_TAG);
        if (new java.io.File(plainDir).getName().equals(new java.io.File(sqlDir).getName())
                || !new java.io.File(sqlDir).getName().equals("sql_meta_data_file_20261022")
                || !new java.io.File(sqlSimple).getName()
                        .equals("sql_simple_meta_data_file_20261022")) {
            problems.add("两页的生成目录没分开：" + plainDir + " / " + sqlDir + " / " + sqlSimple);
        }
        frame.clearAll();
        frame.addFiles(files);
        if (frame.tables().isEmpty()) {
            problems.add("资源位自检没有可生成的表");
            return;
        }
        // Excel 页占着位的时候，SQL 页的生成照样起得来（两页各写各的目录，不再共用产物位）
        frame.startTask(MainFrame.RES_EXCEL);
        if (!frame.startTask(MainFrame.RES_SQL)) {
            problems.add("两页的生成还在互斥：目录已经分开了，就该能并行");
        }
        frame.endTask();
        frame.endTask();
        if (frame.taskBusy()) {
            problems.add("endTask 没能把资源位还回去");
        }
    }

    /** 自检顺手留下的图：只报路径，不参与判定（锁屏时抓到的是锁屏图，判不了）。 */
    private static final List<String> SHOTS = new ArrayList<String>();
    private static volatile int SHOT_WINDOWS;

    /**
     * 抓一张整屏到内存（不落盘）：写 PNG 要一两秒，不能压在"两条任务都还在跑"那个窗口里。
     * 无显示设备或抓屏被拒时返回 null。
     *
     * 抓之前先把本程序那几个顶层窗口抬到最前：自检不抢焦点，不抬的话拍出来是
     * 桌面上那个聊天窗口（实测踩过一张，颜色数照样过 60，看着像"抓到了"其实没拍到界面）。
     */
    private static java.awt.image.BufferedImage captureNow() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return null;
        }
        try {
            invokeUi(() -> raiseOwnWindows());
            Thread.sleep(400);
            SHOT_WINDOWS = showingWindows();
            return grabScreen();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void raiseOwnWindows() {
        for (java.awt.Frame f : java.awt.Frame.getFrames()) {
            if (f.isShowing()) {
                f.toFront();
            }
        }
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isShowing()) {
                w.toFront();
            }
        }
    }

    private static java.awt.image.BufferedImage grabScreen() throws Exception {
        java.awt.Rectangle bounds = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
        return new java.awt.Robot().createScreenCapture(bounds);
    }

    /**
     * 把内存里那张落盘，返回"路径 + 尺寸 + 抽样颜色数"；空帧（全黑全白）当没抓到。
     *
     * 用例本身跑在无显示设备上也照样绿（进度框那些断言读的是控件状态，不是像素），
     * 所以这一张只是给人眼复核用的，别拿一张白图当"看过了"。
     */
    private static String saveShot(java.awt.image.BufferedImage img, String tag) {
        if (img == null) {
            return null;
        }
        try {
            java.util.Set<Integer> colors = new java.util.HashSet<Integer>();
            for (int y = 0; y < img.getHeight() && colors.size() <= 400; y += 23) {
                for (int x = 0; x < img.getWidth(); x += 23) {
                    colors.add(Integer.valueOf(img.getRGB(x, y)));
                }
            }
            if (colors.size() < 60) {
                return null;
            }
            java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "e2m-shots");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "java-" + tag + ".png");
            javax.imageio.ImageIO.write(img, "png", f);
            return f.getPath() + "（" + img.getWidth() + "x" + img.getHeight()
                    + "，抽样 " + colors.size() + " 色）";
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 真线程那一支：打开进度框那条路，两条真任务同时在跑。
     *
     * 上面的 task_slots 验的是抢位规则（原地跑，没有线程也没有框）；这一支验的是用户那句
     * "多个操作不要互相阻塞"本身——Excel 页在解析八个七千行的表、SQL 页在往盘上写一百五十个
     * 文件，两边都得在动、界面还得点得动。所以整段站在非事件分发线程上：站在 EDT 里睡过去
     * 就等于自己把事件循环掐了，那时候"看着没卡"是假的。
     *
     * 夹具拆成八个小文件而不是一个大文件：解析按阶段计项，一张几十万行的表总共只有 5 项，
     * "读取工作簿"那一项能三秒不动一格，"两条都在推进"就采不到样。
     */
    private static void concurrent_tasks(MainFrame frame, List<String> problems)
            throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
                "e2m-concurrent");
        deleteTree(dir);
        dir.mkdirs();
        // 前面有一支场景会打开示例清单又不会自己收（那颗对话框是 HIDE_ON_CLOSE，
        // forceCloseDialogs 只管消息弹窗），不收的话抓屏拍到的是它、不是两个进度框
        invokeUi(() -> frame.closeSqlSamples());
        List<String> xlsx = new ArrayList<String>();
        // 解析这条要当两格里最长的那个（八个两万行，实测约六秒）：取消、推进取样、
        // 同页拒绝这三步都得在它还没做完的时候做，夹具一旦快过用例就只能报"没验到取消"
        for (int k = 0; k < 8; k++) {
            xlsx.add(bigFixture(new java.io.File(dir, "并发大表" + (char) ('A' + k) + ".xlsx"),
                    20000));
        }
        java.io.File script = new java.io.File(dir, "并发生成.sql");
        StringBuilder sb = new StringBuilder();
        // 生成那一头六百张表（Java 写六百个文件约两秒）。别再往大了加：实测三千张时
        // 每个文件两行日志把事件分发线程灌满了，解析那条从 4 秒拖到 135 秒，
        // 用例量的就成了日志刷屏速度而不是并发
        for (int i = 0; i < 600; i++) {
            sb.append("CREATE TABLE t_par_").append(i)
                    .append(" (id BIGINT NOT NULL COMMENT '编号',")
                    .append(" name VARCHAR(64) COMMENT '名称',")
                    .append(" primary key (id)) ENGINE=InnoDB COMMENT='并发表").append(i)
                    .append("';\n");
        }
        writeText(script.getPath(), sb.toString());
        java.io.File script2 = new java.io.File(dir, "并发被挡.sql");
        writeText(script2.getPath(), "CREATE TABLE t_block_0 (id BIGINT, primary key (id));\n");
        final String genRoot = new java.io.File(dir, "并发输出").getPath();

        MainFrame.modalProgress = true;      // 真那条路：起子线程、挂真框
        try {
            invokeUi(() -> frame.clearAll());
            // 这一步不能等事件分发线程返回：进度框一旦回归模态，invokeAndWait 会和
            // 一个没人点的结果弹窗互相等死（变异抽查实测：整道闸门挂住不出结果）。
            // 也不能拿"台账里有一条"当前提——这份脚本几百毫秒就读完了，
            // 60 毫秒一轮的采样根本抓不到那一瞬（上一版就是这么假红的）。
            // 直接等它的结果：日志里出现这份脚本名，就说明条目已经进了 SQL 页列表。
            fireUi(() -> frame.sqlPage().addFiles(
                    java.util.Collections.singletonList(script.getPath())));
            long loadUntil = System.currentTimeMillis() + 30000;
            boolean loadedScript = false;
            while (System.currentTimeMillis() < loadUntil) {
                loadedScript = frame.sqlPage().logPanel().text().contains("并发生成.sql");
                if (loadedScript) {
                    break;
                }
                closeMessageDialogs();
                Thread.sleep(30);
            }
            if (!loadedScript) {
                problems.add("前置：SQL 页没把这份脚本读进列表，后面那颗生成按钮不会起任务："
                        + tail(frame.sqlPage().logPanel().text()));
                forceCloseDialogs();
                return;
            }
            if (!waitTasks(frame, 0, 30)) {
                problems.add("前置：SQL 页读脚本的任务没收尾：" + taskLine(frame));
                forceCloseDialogs();
                return;
            }

            // 起任务这两步都用 invokeLater 丢过去，不等它：非模态那条路本来就该"点下去就返回"。
            // 用 invokeAndWait 的话，进度框一旦退回模态，调用方会被那颗框按死在这里——
            // 报出来的就是"闸门挂住十五分钟"而不是"用例红了"，证据直接丢掉
            //（变异抽查实测踩过：模态那一版把整道自检拖死，连一行输出都没有）
            fireUi(() -> frame.addFilesAsync(xlsx));                 // A：Excel 页解析
            fireUi(() -> frame.sqlPage().generateExcel(true, genRoot));   // B：SQL 页写盘
            // 25 秒不是"任务要跑这么久"，是给事件分发线程留的余量：两条 invokeLater 排在
            // 解析任务的日志刷新后面，机器上同时跑着别的用例集时，第二个体会被延后好几秒
            // 才执行——八秒的窗口在闸门里红过一次，就是这么红的
            if (!waitTasks(frame, 2, 25)) {
                problems.add("两个真任务没能同时在跑（这一条就是「不互相阻塞」本身）："
                        + taskLine(frame));
                forceCloseDialogs();
                return;
            }
            final List<MainFrame.Task> both = frame.runningTasks();
            MainFrame.Task a = null;
            MainFrame.Task b = null;
            for (MainFrame.Task t : both) {
                if (MainFrame.RES_EXCEL.equals(t.resources[0])) {
                    a = t;
                } else if (MainFrame.RES_SQL.equals(t.resources[0])) {
                    b = t;
                }
            }
            if (a == null || b == null) {
                problems.add("同时在跑的两条任务认不出归属：" + taskLine(frame));
                return;
            }
            // 同一页再点一次该被挡下——这一步要趁两条都还在跑就做，而且排在取样之前：
            // 写盘那条两秒就完了，晚一步点下去资源位已经空出来，"挡下"这件事就根本不会发生
            //（上一轮 flat 空成 "" 就是这个原因）。点的也不是那颗生成按钮——任务在跑时
            // 它本来就是灰的，doClick 空转；再读一份脚本才是用户真会做的动作。
            // 弹窗是模态的，会把事件分发线程按在嵌套循环里，所以这一步用 invokeLater 丢过去，
            // 由这边轮那句话写出来没有，一看到就抄下来，再替用户把弹窗点掉。
            SwingUtilities.invokeLater(() -> frame.sqlPage().addFiles(
                    java.util.Collections.singletonList(script2.getPath())));
            long until = System.currentTimeMillis() + 5000;
            String flat = "";
            while (System.currentTimeMillis() < until) {
                String rec = frame.lastDialogText().replace((char) 10, ' ').replace(" ", "");
                if (rec.contains("已有任务在跑")) {
                    flat = rec;
                    break;
                }
                Thread.sleep(50);
            }
            // 弹窗正文是按 56 列折过存的那一份，长短语会在中间断行；日志那一份又会被
            // 生成任务的刷屏挤掉（每个文件两行）。比之前把空白去掉，两边都不受排版影响
            if (!flat.contains("已有任务在跑")
                    || !flat.contains("SQL脚本改名入库页的脚本列表")) {
                problems.add("SQL 页第二个动作被挡下时没点名共用的是这一页的脚本列表："
                        + tail(flat));
            }
            if (frame.runningTasks().size() != 2) {
                problems.add("被挡下的那一步改变了在跑的任务数：" + taskLine(frame));
            }
            closeMessageDialogs();

            for (MainFrame.Task t : both) {
                if (t.dialog == null || !t.dialog.isShowing() || t.dialog.isModal()) {
                    problems.add("任务在跑时它那个进度框不在屏上、或是模态的：" + t.describe());
                }
            }
            // 两颗框不许叠在一起：叠了后面那颗的「取消」就点不到，非模态等于白改。
            // 这一步只读边界不抓屏：抓一张 2560x1600 要和桌面窗口管理器抢时间，
            // 实测把下面那颗"界面照旧回应"从 200 毫秒顶到 537 毫秒（上限 500），
            // 取证本身改变被测行为。像素那一路交给 click_other_page_while_busy
            java.awt.Rectangle ra = a.dialog == null ? null : a.dialog.getBounds();
            java.awt.Rectangle rb = b.dialog == null ? null : b.dialog.getBounds();
            if (ra != null && rb != null) {
                java.awt.Rectangle overlap = ra.intersection(rb);
                if (!overlap.isEmpty()) {
                    problems.add("两个进度框叠在一起，后面那颗的「取消」点不到："
                            + ra + " / " + rb + "，主窗口在 ("
                            + frame.getX() + "," + frame.getY() + ")，框尺寸 "
                            + ra.width + "x" + ra.height);
                }
            }
            
            int pa = a.getDone();
            int pb = b.getDone();
            long p0 = System.currentTimeMillis();
            invokeUi(() -> { });
            final long pingMs = System.currentTimeMillis() - p0;
            Thread.sleep(1200);
            int pa2 = a.getDone();
            int pb2 = b.getDone();
            if (pa2 <= pa || pb2 <= pb) {
                // 断的是计项数而不是百分比：六百项的写盘任务里，冷启动头一个文件
                // 就要占掉几百毫秒，窗口给小了会采不到样
                problems.add("两条任务不是都在推进：" + pa + "→" + pa2 + " / "
                        + pb + "→" + pb2);
            }
            // 上限 800 毫秒：这台机器健康时是 200 毫秒上下，但同一支自检里还有别的场景
            // 在排队，实测采到 527 / 537 / 540 毫秒（不是缺陷，是噪声带）。真回归是另一个
            // 数量级——进度框退回模态量出 5221 毫秒、把抓屏塞进这个时间窗量出 10181 毫秒
            if (pingMs > 800) {
                problems.add("两条任务在跑时事件分发线程回应一次要 " + pingMs
                        + " 毫秒（界面等于没转）");
            }
            // 掐掉解析那条：Java 这边它是稳的几秒（八个文件 41 项），写盘那条两秒就跑完，
            // 取消要落在还在干活的线程上才有意义。而且这一步趁两条都还在跑就做——
            // 先等一条自然收尾，另一条常在弹窗派发里跟着做完，用例就退化成"没验到取消"
            List<MainFrame.Task> live = frame.runningTasks();
            if (live.size() < 2) {
                problems.add("该有两条在跑时只剩 " + live.size() + " 条：" + taskLine(frame));
                return;
            }
            MainFrame.Task victim = null;
            MainFrame.Task other = null;
            for (MainFrame.Task t : live) {
                if (MainFrame.RES_EXCEL.equals(t.resources[0])) {
                    victim = t;
                } else {
                    other = t;
                }
            }
            if (victim == null) {
                problems.add("没找到还在跑的解析任务：" + taskLine(frame));
                return;
            }
            if (victim.remaining() <= 0) {
                problems.add("取消没落在还在干活的线程上（夹具快过了用例）："
                        + victim.describe());
                return;
            }
            victim.cancel();
            // 等它退出台账，这一段故意不点弹窗：取消后的结果弹窗也是模态的，
            // 用户没点确定那段时间里，这条已经收摊的任务必须先把资源位还出来——
            // 收尾顺序一旦退回"先弹结果再放位"，这里就会等到超时
            long quiet = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < quiet && frame.runningTasks().contains(victim)) {
                Thread.sleep(50);
            }
            if (frame.runningTasks().contains(victim)) {
                problems.add("被掐掉那条的弹窗还开着时，它没把资源位还出来："
                        + victim.describe());
            }
            // 留痕要再等一步：资源位是先还的、收尾日志是随后写的，
            // 只在"退出台账"那一刻读一次必然读到空的（上一轮那条红就是这个意思）
            String own = "";
            long note = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < note) {
                own = victim.logView.text() + " | " + frame.lastDialogText();
                if (own.contains("已取消")) {
                    break;
                }
                closeMessageDialogs();
                Thread.sleep(50);
            }
            if (!own.contains("已取消")) {
                problems.add("被掐掉的那条没如实标注只完成部分：" + tail(own));
            }
            closeMessageDialogs();
            // 没被掐的那条：照常推进到跑完，结果按事实落地
            if (!waitTasks(frame, 0, 90)) {
                problems.add("另一条没能自己跑完：" + taskLine(frame));
            }
            if (other != null) {
                int o1 = other.getDone();
                if (o1 < other.getTotal()) {
                    // 台账已经空了说明这条也走完了收尾；这里再按计项核一遍，
                    // 防止"任务被摘掉但活没干完"这种假收尾。
                    // "有没有继续推进"不在这里断——那件事由取样那一步管，
                    // 这一条只看结果：另一条可能在被掐之前就已经做完了，做完了自然不再推进
                    problems.add("另一条没跑完自己的项数：" + other.describe());
                }
            }
            if (MainFrame.RES_EXCEL.equals(victim.resources[0])
                    && countXlsx(new java.io.File(genRoot)) <= 0) {
                problems.add("取消解析把写盘那条的成果一起弄丢了");
            }
            if (MainFrame.RES_SQL.equals(victim.resources[0]) && frame.tables().size() != 8) {
                problems.add("取消写盘把解析那条的成果一起弄丢了：表 " + frame.tables().size()
                        + " 张");
            }
        } finally {
            MainFrame.modalProgress = false;
            forceCloseDialogs();
        }
    }

    /**
     * 任务在跑时，另外的页真点得动：起一条解析任务，切到第三页点「示例 (?)」，
     * 清单要开出来、事件分发线程要照常回应，那条任务也不能因为这点事被带走。
     *
     * 为什么单开一条而不塞进 concurrent_tasks：并发那一支里两条任务一共只有两三秒，
     * 而建一份 100 条的清单要在事件分发线程上摆一堆控件。实测塞进去之后
     * "界面照旧回应"那一步从 200 毫秒涨到 5.2 秒、解析那条也提前跑完了——
     * 那时候用例量的就成了建窗速度，不是"点得动点不动"。
     */
    private static void click_other_page_while_busy(MainFrame frame, List<String> problems)
            throws Exception {
        if (!frame.runningTasks().isEmpty()) {
            problems.add("进到这里还留着上一场景的任务：" + frame.runningTasks().size());
            return;
        }
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "e2m-click");
        deleteTree(dir);
        dir.mkdirs();
        List<String> xlsx = new ArrayList<String>();
        // 四张两万行：解析约三秒，够把"点按钮 + 建清单 + 抓屏"整段压在任务还在跑的时候做
        for (int k = 0; k < 4; k++) {
            xlsx.add(bigFixture(new java.io.File(dir, "点击取证" + (char) ('A' + k) + ".xlsx"),
                    20000));
        }
        invokeUi(() -> frame.clearAll());
        MainFrame.modalProgress = true;
        try {
            fireUi(() -> frame.addFilesAsync(xlsx));
            if (!waitTasks(frame, 1, 20)) {
                problems.add("前置：解析任务没起来，后面那一步点了也不作数："
                        + taskLine(frame));
                return;
            }
            MainFrame.Task busy = frame.runningTasks().get(0);
            int before = busy.getDone();
            invokeUi(() -> frame.tabs().setSelectedIndex(2));
            fireUi(() -> frame.sqlRunHelpButton().doClick());
            boolean open = false;
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until) {
                open = frame.sqlSamplesShowing();
                if (open) {
                    break;
                }
                Thread.sleep(30);
            }
            if (!open) {
                problems.add("任务在跑时第三页「示例 (?)」点不开——进度框还挡着窗口："
                        + taskLine(frame));
            } else if (!frame.sqlSamplesTitle().contains("SQL 示例")) {
                problems.add("开出来的不是那份示例清单：" + frame.sqlSamplesTitle());
            }
            // 换一条要把右侧滚回开头：预览不折行，上一行留下的横向位置会切掉新语句的开头。
            // 先把最长那条滚到最右，再切到别条，等事件分发线程把复位做完再看读数
            final javax.swing.JScrollPane spane = frame.sqlSamplesPane();
            if (spane == null) {
                problems.add("示例预览没有滚动容器，验不了换条后的滚动复位");
            } else {
                int longest = 0;
                for (int i = 1; i < frame.sqlSamplesCount(); i++) {
                    if (frame.sqlSampleSql(i).length() > frame.sqlSampleSql(longest).length()) {
                        longest = i;
                    }
                }
                final int li = longest;
                invokeUi(() -> frame.setSqlSamplesRow(li));
                invokeUi(() -> {
                    spane.getHorizontalScrollBar().setValue(
                            spane.getHorizontalScrollBar().getMaximum());
                    spane.getVerticalScrollBar().setValue(
                            spane.getVerticalScrollBar().getMaximum());
                });
                final int h0 = spane.getHorizontalScrollBar().getValue();
                invokeUi(() -> frame.setSqlSamplesRow(2));
                long resetUntil = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < resetUntil
                        && (spane.getHorizontalScrollBar().getValue() != 0
                        || spane.getVerticalScrollBar().getValue() != 0)) {
                    Thread.sleep(20);
                }
                if (h0 <= 0) {
                    problems.add("示例里找不到一条宽到要横向滚动的（最长那条第 "
                            + (li + 1) + " 条），这条复位没量到");
                } else if (spane.getHorizontalScrollBar().getValue() != 0
                        || spane.getVerticalScrollBar().getValue() != 0) {
                    problems.add("换一条示例后右侧没滚回开头：横 "
                            + spane.getHorizontalScrollBar().getValue() + " 纵 "
                            + spane.getVerticalScrollBar().getValue());
                }
            }
            // 清单开着、任务还在跑：这时候回一次事件分发线程，"点得动"才算数
            long p0 = System.currentTimeMillis();
            invokeUi(() -> { });
            long pingMs = System.currentTimeMillis() - p0;
            if (pingMs > 800) {
                problems.add("示例清单开着时事件分发线程回应一次要 " + pingMs
                        + " 毫秒（界面等于没转）");
            }
            // 先确认那条任务还在：抓屏要抬窗口、拍 2560x1600，前后得花一秒多，
            // 把这条断言放在抓完之后就会采到"任务自己跑完了"，红得莫名其妙
            if (!frame.runningTasks().contains(busy)) {
                problems.add("点一下另一页的按钮就把那条任务弄没了：" + taskLine(frame));
            }
            java.awt.image.BufferedImage img = captureNow();
            invokeUi(() -> frame.closeSqlSamples());
            if (!waitTasks(frame, 0, 90)) {
                problems.add("那条任务没能自己跑完：" + taskLine(frame));
            }
            if (busy.getDone() < busy.getTotal() && busy.getDone() <= before) {
                problems.add("任务在被点按钮之后一步没动：" + busy.describe());
            }
            SHOTS.add("另一页清单在屏 " + orNull(saveShot(img, "click-while-busy"))
                    + "，拍时本程序在屏窗口 " + SHOT_WINDOWS + " 个");
        } finally {
            MainFrame.modalProgress = false;
            forceCloseDialogs();
        }
    }

    /** 抓不到图的时候别把 null 打进日志，说人话。 */
    private static String orNull(String shot) {
        return shot == null ? "那张没抓到（无显示设备或空帧）" : shot;
    }

    /** 在屏的顶层窗口数：留影那条报的是"拍到几个窗口"，比抽样颜色数更能说明拍没拍到界面。 */
    private static int showingWindows() {
        int n = 0;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isShowing()) {
                n++;
            }
        }
        return n;
    }

    /** 丢给事件分发线程执行但不等它：起任务那一步必须"点下去就返回"才叫不互相阻塞。 */
    private static void fireUi(Runnable action) {
        SwingUtilities.invokeLater(action);
    }

    /**
     * 收摊用的强制版：进度框也一起关掉。
     * 一旦进度框回归成模态，那颗框会一直开着把事件分发线程按在嵌套循环里，
     * 用例要的是"红得出来"，不是一台谁都退不掉的窗口。
     */
    private static void forceCloseDialogs() {
        SwingUtilities.invokeLater(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JDialog && w.isVisible()) {
                    w.dispose();
                }
            }
        });
    }

    /** 在事件分发线程上跑一步，跑完才回来：起任务这类动作本来就该由"用户点按钮"那个线程做。 */
    private static void invokeUi(final Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    /** 等任务清单变成 wanted 个；超时返回 false，让用例红而不是抛出去把整轮带崩。 */
    private static boolean waitTasks(MainFrame frame, int wanted, int seconds)
            throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (frame.runningTasks().size() == wanted) {
                return true;
            }
            // 结果弹窗是模态的：自检里也得像人一样把它点掉，否则那条任务的收尾永远走不完
            closeMessageDialogs();
            Thread.sleep(60);
        }
        return frame.runningTasks().size() == wanted;
    }


    private static String taskLine(MainFrame frame) {
        StringBuilder sb = new StringBuilder();
        for (MainFrame.Task t : frame.runningTasks()) {
            if (sb.length() > 0) {
                sb.append(" || ");
            }
            sb.append(t.describe());
        }
        return sb.length() == 0 ? "（没有在跑的任务）" : sb.toString();
    }

    /**
     * 造一张 rows 行的 xlsx 夹具（六列，混着文本/数值/日期串），让解析真要读上一秒把。
     * 用流式 API：八个两万行是十六万行，XSSFWorkbook 全量进内存会把自检撑爆。
     */
    private static String bigFixture(java.io.File to, int rows) throws Exception {
        org.apache.poi.ss.usermodel.Workbook wb =
                new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
        org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("数据");
        String[] head = {"编号", "名称", "金额", "数量", "日期", "备注"};
        org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
        for (int c = 0; c < head.length; c++) {
            h.createCell(c).setCellValue(head[c]);
        }
        for (int i = 0; i < rows; i++) {
            org.apache.poi.ss.usermodel.Row r = sheet.createRow(i + 1);
            r.createCell(0).setCellValue(String.format("N%08d", i));
            r.createCell(1).setCellValue("名称" + i);
            r.createCell(2).setCellValue(3.14 + i % 100);
            r.createCell(3).setCellValue(i % 997);
            r.createCell(4).setCellValue(String.format("2026-03-%02d", i % 28 + 1));
            r.createCell(5).setCellValue("备注" + i % 50);
        }
        writeWorkbook(wb, to);
        return to.getPath();
    }

    private static int countXlsx(java.io.File root) {
        int n = 0;
        java.io.File[] kids = root.listFiles();
        if (kids == null) {
            return 0;
        }
        for (java.io.File f : kids) {
            if (f.isDirectory()) {
                n += countXlsx(f);
            } else if (f.getName().endsWith(".xlsx")) {
                n++;
            }
        }
        return n;
    }

    /**
     * 收掉自检里弹出来的消息框：真那条路的消息框是会开嵌套循环的，
     * 留着它后面的步骤就一直在别人的弹窗里看数据。进度框不算消息框，别误关。
     */
    private static void closeMessageDialogs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JDialog && w.isVisible()
                        && w.getClass().getName().endsWith("MessageDialog")) {
                    w.dispose();
                }
            }
        });
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
            problems.add("清空待上传文件后生成按钮还亮着");
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
