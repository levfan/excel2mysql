package com.excel2mysql.gui;

/**
 * 使用说明正文。内容与 Python 侧 help_doc.py 的 SECTIONS 一一对应，
 * 由 tools 里的导出脚本生成，不要手改——改文案请改 help_doc.py 再重新导出，
 * 否则两条线的说明会分叉。
 */
public final class HelpDoc {

    /** NL 而不是反斜杠 n：Java 源里的换行转义容易被工具链吃掉。 */
    public static final String NL = String.valueOf((char) 10);

    /** 一章：锚点、目录标题、正文 HTML 片段。 */
    public static final class Section {
        public final String anchor;
        public final String title;
        public final String body;

        Section(String anchor, String title, String body) {
            this.anchor = anchor;
            this.title = title;
            this.body = body;
        }
    }

    private static final Section[] LIST = {
        new Section("start", "快速上手",
                "<h2>快速上手</h2>" + NL +
                    "<p>这个工具把一批 <b>Excel 表</b>（或含 Excel 的 <b>ZIP 压缩包</b>）批量导入 MySQL：" + NL +
                    "自动推断表结构、生成建表语句、写入数据，导入前可以逐列预览和修改。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>步骤</th><th>做什么</th></tr>" + NL +
                    "<tr><td>1</td><td>在上方<b>「MySQL 连接信息」</b>填主机、端口、用户名、密码" + NL +
                    "（这块默认收起成一条，点<b>「设置数据源信息」</b>展开）</td></tr>" + NL +
                    "<tr><td>2</td><td>点<b>「连接并加载库」</b>，在「目标库」下拉里选库；" + NL +
                    "选定之后这块自动收起，上方只留一条<b>「● 目标库 xxx」</b>——主机端口用户名收在鼠标悬停里</td></tr>" + NL +
                    "<tr><td>3</td><td>切到<b>「Excel 导入」</b>页签，把文件拖进虚线区，或点一下虚线区选文件</td></tr>" + NL +
                    "<tr><td>4</td><td>需要统一命名时，填<b>表名前缀</b>后点<b>「批量应用前缀」</b>（加一段）" + NL +
                    "或<b>「替换首个前缀」</b>（换掉已有首段，见「表名前缀：加与换」）</td></tr>" + NL +
                    "<tr><td>5</td><td>点<b>「生成表结构预览」</b>核对/修改字段名、类型、主键</td></tr>" + NL +
                    "<tr><td>6</td><td>点<b>「预览 DDL」</b>确认建表语句，然后<b>「开始导入」</b></td></tr>" + NL +
                    "<tr><td>7</td><td>看底部日志的逐表结果</td></tr>" + NL +
                    "</table>" + NL +
                    "<p class=\"tip\">第 1 步可以省：在<b>程序所在目录</b>放一份 <code>conn.ini</code>" + NL +
                    "（Java 交付包里已经带了一份；Python 图形版从仓库的 <code>assets/conn.ini</code>" + NL +
                    "复制一份改），开界面时主机、端口、用户名、密码就按它预填；配置里写了" + NL +
                    "<code>database</code> 的话，点「连接并加载库」之后目标库会自动选中那一个，不用再挑。" + NL +
                    "优先级是<b>环境变量 E2M_HOST / E2M_PORT / E2M_USER / E2M_PASS / E2M_DB" + NL +
                    "&gt; conn.ini &gt; 内置默认值</b>，界面上手填的那一份永远最即时。" + NL +
                    "启动日志会写明这次用的是哪来的配置：读到了说路径，文件写坏了说\"找到了但读不了\"并退回默认值，" + NL +
                    "压根没有这个文件也照常起界面，只提示一句要预填就自己放一份。" + NL +
                    "口令写进文件就要看好这份文件——不想落盘就用环境变量传。</p>" + NL +
                    "<p class=\"tip\">最上方一条<b>连接状态栏</b>只报\"现在是哪个库\"：绿色显示" + NL +
                    "<code>● 目标库 库名</code>，主机、端口、用户名收在鼠标悬停里；还没选库时是红色告警，" + NL +
                    "按当前状态告诉你下一步点什么。连接区启动即收起，要改连接、测连接、换库就点右侧" + NL +
                    "<b>「设置数据源信息」</b>展开五行表单，选定库之后自动收起" + NL +
                    "（展开时也可以点<b>「收起」</b>手动收回去）。" + NL +
                    "三个页签顶部各还有一条指向：绿色写 <code>目标库：库名 —— …</code>，最后一句按页分工——" + NL +
                    "Excel 页说\"数据将导入到这个库\"，「SQL 脚本改名入库」页说\"脚本将执行到这个库\"，" + NL +
                    "「SQL 执行器」页说\"编辑区里的 SQL 将执行到这个库\"。没选库时这三条先隐藏，" + NL +
                    "唯一的红色告警留在最上方那条状态栏，不会同一句话在屏幕上说两遍。状态栏和这三条同源刷新，" + NL +
                    "切库会一起变，不会在某个页签停在\"上一个库\"；导入前扫一眼能确认没写错库。</p>"
        ),
        new Section("lines", "三条线与选型",
                "<h2>三条线与选型</h2>" + NL +
                    "<p>同一个工具现在有三份可交付的程序：解析、类型推断与建表逻辑同源，" + NL +
                    "差别在有没有界面、能跑在什么系统上、以及命令行选项集。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>交付线</th><th>入口</th><th>能跑的系统</th><th>界面</th><th>命令行</th></tr>" + NL +
                    "<tr><td>图形版（Python）</td><td><code>excel2mysql.exe</code></td>" + NL +
                    "<td>64 位 Windows 10 1809+ / Windows 11</td><td>有</td><td>无</td></tr>" + NL +
                    "<tr><td>命令行版（Python，Windows 7 专用）</td><td><code>excel2mysql-cli.exe</code></td>" + NL +
                    "<td>Windows 7 SP1 及以上，且系统 UCRT 补丁与系统文件完整</td><td>无</td><td>有</td></tr>" + NL +
                    "<tr><td>Java 版</td><td><code>启动.bat</code> 或" + NL +
                    "<code>java -jar excel2mysql-*.jar</code></td>" + NL +
                    "<td>64 位 Windows 7 及以上；包内自带 Java 运行时，目标机器不用装任何东西</td>" + NL +
                    "<td>有</td><td>有</td></tr>" + NL +
                    "</table>" + NL +
                    "" + NL +
                    "<h3>每个包怎么启动（界面与命令行的分别入口）</h3>" + NL +
                    "<ul>" + NL +
                    "<li>图形版 <code>excel2mysql.exe</code>：双击即开界面，没有命令行模式。</li>" + NL +
                    "<li>Python 命令行版 <code>excel2mysql-cli.exe</code>：控制台程序，在 cmd 里带参数" + NL +
                    "运行（<code>excel2mysql-cli.exe --check</code>）。直接双击它会打一句\"没有输入\"的用法后" + NL +
                    "立刻退出，黑窗口一闪而过，这是预期行为而不是坏了——不带参数它就没有可干的活；" + NL +
                    "它也没有界面模式。</li>" + NL +
                    "<li>Java 版：界面与命令行是同一个 jar，靠第一个参数分开。" + NL +
                    "<ul>" + NL +
                    "<li>要界面：双击 <code>启动.bat</code>。这个脚本只做一件事——用包内的" + NL +
                    "<code>jre\\bin\\java.exe</code> 跑 <code>-jar excel2mysql-*.jar --gui</code>。" + NL +
                    "想一次带上文件，把 Excel/ZIP 拖到 <code>启动.bat</code> 的图标上。</li>" + NL +
                    "<li>要命令行：在 cmd 里跑同一条命令但不给 <code>--gui</code>，例如" + NL +
                    "<code>jre\\bin\\java.exe -jar excel2mysql-1.1.3.jar --check</code>。</li>" + NL +
                    "<li>记法就一句：首参给 <code>--gui</code> 是界面，不给是命令行。" + NL +
                    "双击 jar 文件本身不会有任何反应。</li>" + NL +
                    "</ul>" + NL +
                    "</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>界面功能：图形版与 Java 版等价</h3>" + NL +
                    "<p>两边都有这些动作：拖文件与点击虚线区选文件、ZIP 展开、<code>.xlsx</code> 与" + NL +
                    "<code>.xls</code>、拼音表名、「批量应用前缀」与「替换首个前缀」、" + NL +
                    "「生成表结构预览」（可编辑字段名、类型与主键）、「预览 DDL」、" + NL +
                    "「SQL 脚本改名入库」页的保存与执行、「SQL 执行器」页的直接执行与 100 条示例、" + NL +
                    "「生成标准excel文件」与「生成简易excel文件」、" + NL +
                    "耗时任务的进度框，以及这份《使用说明》（右上角「?」或 F1）。</p>" + NL +
                    "<p>这份等价是逐项称出来的，不是估计：两条线的窗口控件、文案、按钮启用条件、" + NL +
                    "弹窗文字、日志与写出的文件会被各自枚举成一份清单，再逐键比对，共 251 项。</p>" + NL +
                    "" + NL +
                    "<h3>两条 Python 线的一个硬限制：口令不能用中文</h3>" + NL +
                    "<p>pymysql 在发起握手前就把口令按 latin-1 编码，所以图形版与 Windows 7 命令行版" + NL +
                    "都只能用 latin-1 范围内（实际就是 ASCII 与欧洲字母）的 MySQL 口令；口令里有中文时" + NL +
                    "连不上库，报出来的是编码错误而不是数据库错误，日志里会跟着给一句\"多半出在口令上\"。" + NL +
                    "同一条口令在 Java 版可以用（JDBC 支持 Unicode 口令）——现场改不了口令就选 Java 版。</p>" + NL +
                    "" + NL +
                    "<h3>什么时候选哪条</h3>" + NL +
                    "<ul>" + NL +
                    "<li>机器是 Windows 10/11，要改字段名和主键：图形版。</li>" + NL +
                    "<li>机器只有 Windows 7、又没法确认打过补丁：Java 版，它自带运行时。</li>" + NL +
                    "<li>要放进批处理或定时任务：两条命令行线都行，但它们的选项集不一样，" + NL +
                    "见下一段和各自随包的 <code>README.txt</code>。</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>命令行选项并不完全相同</h3>" + NL +
                    "<ul>" + NL +
                    "<li>Python 命令行版：<code>.sql</code> 默认只登记不执行，要加" + NL +
                    "<code>--run-sql</code> 才真的写到库里。连接信息可以逐个给" + NL +
                    "（<code>--host --port --user --password -d</code>），另有" + NL +
                    "<code>--initials</code>（表名与字段名取拼音首字母）、<code>--upper</code>" + NL +
                    "（表名与字段名转大写）、<code>--swap-prefix</code>（替换首个前缀）、" + NL +
                    "<code>--drop-if-exists</code>、<code>--batch</code>、<code>--log</code>、" + NL +
                    "<code>--quiet</code>、<code>--no-recursive</code>、<code>--keep-temp</code>。</li>" + NL +
                    "<li>Java 版：动作由路径类型决定——给 <code>.sql</code> 就执行脚本，" + NL +
                    "给目录两类都干，用 <code>--no-sql</code> / <code>--no-excel</code> 各自排除，" + NL +
                    "没有 <code>--run-sql</code> 这个开关。连接信息走同级 <code>conn.ini</code>" + NL +
                    "或环境变量 <code>E2M_HOST / E2M_PORT / E2M_USER / E2M_PASS / E2M_DB</code>，" + NL +
                    "选项有 <code>--dry-run --check --prefix --initials --upper -c --debug --gui --version</code>。</li>" + NL +
                    "<li>「生成标准excel文件」与「生成简易excel文件」这两件只在界面上有，" + NL +
                    "两条命令行线都没有对应的动作。</li>" + NL +
                    "</ul>"
        ),
        new Section("files", "支持哪些文件",
                "<h2>支持哪些文件</h2>" + NL +
                    "<p>「Excel 导入」页的虚线区是添加文件的唯一入口：点一下弹文件选择器（可多选），" + NL +
                    "也可以把文件或压缩包直接拖进去。页面上不再有单独的「添加文件」按钮。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>输入</th><th>行为</th></tr>" + NL +
                    "<tr><td><code>.xlsx</code></td><td>正常导入</td></tr>" + NL +
                    "<tr><td><code>.xls</code>（97-2003 老格式）</td><td>由 <code>xlrd</code> 解析，正常导入</td></tr>" + NL +
                    "<tr><td><code>.zip</code> 压缩包</td><td>自动展开包内的 Excel，逐个加入待导入列表</td></tr>" + NL +
                    "<tr><td>其他扩展名</td><td>忽略，日志里说明忽略了几个</td></tr>" + NL +
                    "</table>" + NL +
                    "" + NL +
                    "<h3>ZIP 压缩包</h3>" + NL +
                    "<ul>" + NL +
                    "<li><b>递归子目录</b>：包内 <code>北京/水库.xlsx</code> 这样的嵌套路径同样会被收进来，" + NL +
                    "解压时保留目录结构，所以不同目录下的同名文件不会互相覆盖</li>" + NL +
                    "<li><b>不递归嵌套 zip</b>：包里的 <code>.zip</code> 会被跳过并在日志里计数，" + NL +
                    "要导入就把内层包单独拖进来</li>" + NL +
                    "<li><b>非 Excel 文件忽略</b>，<code>~$xxx.xlsx</code> 这类 Office 临时锁文件也跳过</li>" + NL +
                    "<li>展开出的文件放在系统临时目录，<b>关闭窗口时自动清理</b>；" + NL +
                    "所以关掉程序后，来自 ZIP 的失败文件不能直接重试，要重新拖一次包</li>" + NL +
                    "<li>中文文件名按 GBK 兜底解码：Windows 自带\"发送到压缩文件夹\"存中文名用 GBK 且不打" + NL +
                    "UTF-8 标记，工具会正确还原，不会变成乱码表名</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>顶部两行合并表头：自动压成一行</h3>" + NL +
                    "<p>原始表第一行是分组名、第二行是子项时，不用先手工改成一行：按列把上下两行拼成「父-子」" + NL +
                    "表头再照常推断类型。比如第一行 <code>技术公司</code> 横跨 4 列，第二行是" + NL +
                    "<code>小计 / 高层 / 中层 / 普通员工</code>，得到的字段名就是" + NL +
                    "<code>技术公司-小计</code>、<code>技术公司-高层</code>…；左侧纵向合并两行的" + NL +
                    "<code>序号</code> 只留一份，不会变成 <code>序号-序号</code>。" + NL +
                    "日志会在 <code>[已解析]</code> 行写「合并表头按 2 行展开」。</p>" + NL +
                    "<p>放行的条件（只认这种标准两行表头）：合并区只在第一个 Sheet、每一处都从第 1 行开始，" + NL +
                    "并且只能是跨两行的单列纵向合并（如 <code>A1:A2</code>）或只横跨第 1 行的多列合并" + NL +
                    "（如 <code>J1:M1</code>），至少一处跨两行。</p>" + NL +
                    "" + NL +
                    "<h3>其余合并单元格：直接拒绝导入</h3>" + NL +
                    "<p>数据行里的合并会静默丢值，表头形状对不上时字段名与列会错位。所以不符合上述条件的文件" + NL +
                    "<b>一律不导入、也不进入待导入列表</b>，日志会标红列出具体位置，请回 Excel 里取消合并后重新添加：</p>" + NL +
                    "<pre>[合并单元格] 两页都合并.xlsx 含 3 处合并区，已拒绝导入，未进入待导入列表" + NL +
                    "    检测到合并单元格：「字段定义」1 处（A1:G1）；「数据」2 处（A1:B1、A2:B2）</pre>" + NL +
                    "<p>覆盖的情形：数据区有合并、第二个 Sheet 有合并、单行表头上的合并、合并区跨出表头块、" + NL +
                    "三行表头、第 2 行里的子合并、2x2 合并块，以及两个 Sheet（元数据页 + 数据页）任一页有合并区" + NL +
                    "→ 整个文件拒绝。拒绝只针对该文件，不会中断整批，同批其他文件照常解析。" + NL +
                    "ZIP 里只有某个文件有合并区时，只拒绝那一个，同包其他干净文件正常导入。</p>"
        ),
        new Section("progress", "进度框与耗时",
                "<h2>进度框与耗时</h2>" + NL +
                    "<p>几十万行的大表解析、导入、读写脚本都很耗时，这些操作会弹一个进度框：" + NL +
                    "真正干活的都在后台线程，界面始终能刷新、能点、能取消，不会\"假死\"。" + NL +
                    "这个框是非模态的，它不挡住主窗口——一个页在跑的时候，另外两个页照常能用。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>操作</th><th>进度怎么算</th></tr>" + NL +
                    "<tr><td>点击虚线区选文件 / 拖入文件</td>" + NL +
                    "<td>每个压缩包 1 项；包内每个 Excel 解出占 1 项；每个待解析 Excel 按 5 个阶段各占 1 项" + NL +
                    "（合并检测 / 读取工作簿 / 识别格式 / 推断类型 / 选主键）。当前项会显示到" + NL +
                    "「文件名：读取工作簿」这一级，所以单个几十万行的大表解析途中进度条也在走</td></tr>" + NL +
                    "<tr><td>开始导入</td><td>每张表 1 项 + 每个数据行 1 项，几十万行也能看到连续推进</td></tr>" + NL +
                    "<tr><td>批量应用前缀 / 替换首个前缀（Excel 页）</td>" + NL +
                    "<td>每张表 1 项 + 重名去重 1 项</td></tr>" + NL +
                    "<tr><td>拖拽区 / 点击选文件 / 批量应用前缀 / 替换首个前缀（SQL 页）</td>" + NL +
                    "<td>每个文件 1 项（读盘、扫描、改写都在后台）</td></tr>" + NL +
                    "<tr><td>保存修改后ddl脚本</td><td>每个文件 1 项</td></tr>" + NL +
                    "<tr><td>执行修改后ddl脚本到当前库</td><td>每条语句 1 项，按块推进（大脚本一个文件最多约 100 次刷新，" + NL +
                    "避免海量刷新反而拖慢界面）</td></tr>" + NL +
                    "<tr><td>「SQL 执行器」执行编辑区里那段</td><td>每条语句 1 项，逐条推进（上千条的脚本也能看到它在走），" + NL +
                    "<b>每句开始之前查一次取消</b></td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>任务在跑时点了别的按钮</h3>" + NL +
                    "<p>挡下这一步时，会同时弹窗和写日志说清楚在等谁：任务名、跑到几成、已完成多少项、" + NL +
                    "已经跑了多久、当前是哪一项，以及两个任务抢的是同一份什么东西，形如</p>" + NL +
                    "<pre>已有任务在跑：正在执行 SQL… 37%（45/121）· 已 12 秒 · 第 45/121 条。这一步没执行——它和那个任务共用「SQL 执行器编辑区里那段文本」，等它跑完或点进度框「取消」。</pre>" + NL +
                    "<p>互斥按占用的东西判，不按页签判：同一页的两个任务会踩同一份列表或同一段编辑区，" + NL +
                    "所以后点的那一步被挡下。两页的「生成标准/简易excel文件」各写自己的目录" + NL +
                    "（「Excel 导入」那页是 <code>meta_data_file_年月日</code>，「SQL 脚本改名入库」那页是" + NL +
                    "<code>sql_meta_data_file_年月日</code>），同名表不会互相覆盖，所以两颗按钮也不必互相等。" + NL +
                    "除此之外各页互不相干——SQL 页在读一个几十兆的脚本时，" + NL +
                    "Excel 页可以继续解析、执行器可以继续跑语句，各自弹各自的进度框、报各自的进度。" + NL +
                    "两条任务同时跑时第二个框会往右下错开一格（竖直错开的距离比框本身还高），" + NL +
                    "两颗框的「取消」都点得到，不会叠成一摞看不见后面那颗。</p>" + NL +
                    "<p>任务跑完弹的那颗结果框只要你点一下确定，它不再顺手占着这一页：弹窗还开着的时候，" + NL +
                    "那条任务已经不算在跑了，另一个还在跑的任务也不受它影响。两条任务并行时，" + NL +
                    "各自的日志写回各自那一页，不会串到别的页去。</p>" + NL +
                    "<h3>取消会发生什么</h3>" + NL +
                    "<ul>" + NL +
                    "<li>取消在当前这一项做完时生效，不会硬切留下半截数据</li>" + NL +
                    "<li>解析中途取消：已解析出的表照常进入待导入列表，弹窗首行标注" + NL +
                    "<code>已取消，只完成了部分解析</code></li>" + NL +
                    "<li>SQL 执行中途取消：该文件的事务回滚不提交（DDL 隐式提交的部分除外），后面的文件不再执行</li>" + NL +
                    "<li>「SQL 执行器」中途取消：下一句就不执行了，整批回滚不提交，" + NL +
                    "日志写 <code>已取消 | 第 46/121 条未执行，本批未提交（已回滚）</code></li>" + NL +
                    "<li>导入中途取消：等同底部的「停止」——当前表处理完即中止，已提交的表不会回滚</li>" + NL +
                    "<li>进度框上的「取消」只管它自己那个任务；底部那颗粒外的「停止」只管 Excel 页这一页的任务</li>" + NL +
                    "<li>任务结束后不留残留：导入完成（或失败、被中止）后，底部的进度条会自动回到 0%，" + NL +
                    "不会停在 100% 让人分不清那是上一批的尾巴还是这一批的进度；" + NL +
                    "「清空待上传文件」同样会把进度条一起归零。结果本身由弹窗与日志交代</li>" + NL +
                    "</ul>" + NL +
                    "<p class=\"tip\">进度框标题栏那个 X 不等于把任务关掉：任务还在跑时点 X 等同点「取消」，" + NL +
                    "否则就会出现\"框没了、任务还在后台跑、之后每次点这一页的按钮都只说请稍等\"这种查不动的状态。" + NL +
                    "工作函数整体抛异常时框照样会关闭，并在日志里红色打 <code>[任务异常]</code>。</p>"
        ),
        new Section("excel", "两类 Excel 格式",
                "<h2>两类 Excel 格式</h2>" + NL +
                    "" + NL +
                    "<h3>第 1 类：普通数据表（单个数据 Sheet）</h3>" + NL +
                    "<p>表名/表注释来自<b>文件名</b>，字段名和类型从<b>数据推断</b>。</p>" + NL +
                    "<ul>" + NL +
                    "<li>文件名形如 <code>北京市水务局-水库基础信息（dim_reservoir_info）.xlsx</code>：" + NL +
                    "<b>表名 = 括号内英文</b> <code>dim_reservoir_info</code>，" + NL +
                    "<b>表注释 = 括号前中文</b>。中英文括号都可以；括号内大写会统一转小写</li>" + NL +
                    "<li>括号里必须是<b>能直接当表名用的英文标识符</b>（字母或下划线开头、含字母、" + NL +
                    "只由字母数字下划线组成）才采用，<b>版本号除外</b>。以下情况一律回退到\"整个文件名转拼音\"：" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>文件名</th><th>表名</th><th>为什么</th></tr>" + NL +
                    "<tr><td><code>人员信息表（人事处人员）</code></td>" + NL +
                    "<td><code>ren_yuan_xin_xi_biao_ren_shi_chu_ren_yuan</code></td>" + NL +
                    "<td>括号内是中文 → 括号内外<b>所有中文一起</b>转拼音，不会生成汉字表名</td></tr>" + NL +
                    "<tr><td><code>人员信息表（7）</code></td><td><code>ren_yuan_xin_xi_biao_7</code></td>" + NL +
                    "<td>括号内是纯数字序号 → 不生成只含数字的表名，序号留在末尾做区分</td></tr>" + NL +
                    "<tr><td><code>人员信息表（2026）</code></td><td><code>ren_yuan_xin_xi_biao_2026</code></td>" + NL +
                    "<td>同上</td></tr>" + NL +
                    "<tr><td><code>人员信息表（7_dim）</code></td><td><code>ren_yuan_xin_xi_biao_7_dim</code></td>" + NL +
                    "<td>以数字开头不是合法标识符开头 → 回退</td></tr>" + NL +
                    "<tr><td><code>订单表（V2）</code></td><td><code>ding_dan_biao_v2</code></td>" + NL +
                    "<td>括号内是版本号（V2 / v2 / V2_1）→ 版本号不是表名，" + NL +
                    "<b>回退后版本段留在末尾</b>；勾「取拼音首字母」为 <code>ddbv2</code>，" + NL +
                    "再勾「转大写」为 <code>DDBV2</code></td></tr>" + NL +
                    "</table>" + NL +
                    "回退时<b>表注释保留完整文件名</b>（含括号内容），所以" + NL +
                    "<code>人员信息表（人事处）</code> 与 <code>人员信息表（财务部）</code>" + NL +
                    "会得到两个不同且看得懂的表名，而不是同名加 <code>_2</code></li>" + NL +
                    "<li>没有括号时：<b>表名 = 文件名汉字转拼音</b>（音节间下划线分隔），表注释 = 文件名。" + NL +
                    "<b>超长处理与字段名同一套规则</b>：全拼不超过 64 字符用全拼，超过则整名降级为各音节" + NL +
                    "首字母（数字/英文音节整段保留），例如" + NL +
                    "<code>北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表</code> →" + NL +
                    "<code>bjshdqswjxsdxsksngcjcxxhzb</code>；" + NL +
                    "表名与字段名共用这套切分，只是上限不同（表名 64 / 字段名 30）</li>" + NL +
                    "<li>整列没值的字段怎么处理，看的是<b>表头写没写字</b>：写了字就<b>照旧保留</b>，日志里点名" + NL +
                    "（<code>整列为空的字段照旧保留：*填报人…</code>）。采集模板一行数据都还没填，字段是设计好的" + NL +
                    "表结构，按\"整列没值就删\"处理会把表改小一号，空模板甚至会被判成\"没解析到字段\"而整体拒收；" + NL +
                    "表头空着、整列又没值的（Excel 尾部格式化拖出来的 <code>Unnamed</code> 列）才剔除。全空行照旧剔除</li>" + NL +
                    "<li>只有表头、一行数据都没有的表照样解析成功：建出来是空表，那条 <code>[已解析]</code>" + NL +
                    "记录下面缩进的说明行里写 <code>没有数据行，建出来是空表</code>，并且<b>不自动选主键</b>（没数据无法判断唯一与非空）。" + NL +
                    "需要主键时可以在「生成表结构预览」里手动勾选：空表天然满足非空与唯一，字段类型合规" + NL +
                    "（INT/BIGINT 或长度 ≤255 的 VARCHAR/CHAR）就能保存</li>" + NL +
                    "<li><b>表头整行一个格都没写字</b>（连 <code>发布日期</code> 这样的字都没有，只有空格子）也照样导入：" + NL +
                    "文件里记着这张表有几列，就按那个列数建表，字段名用空表头的兜底写法" + NL +
                    "<code>col</code>、<code>col_2</code>、<code>col_3</code>，列注释为空、没有数据可插，" + NL +
                    "那条 <code>[已解析]</code> 记录下面缩进的说明行里点名" + NL +
                    "<code>表头整行没写字，按列宽兜底成：col、col_2、col_3</code>。" + NL +
                    "这种表建出来看不出业务含义，要么在「生成表结构预览」里改名，要么把表头补上再导。" + NL +
                    "<b>连一个格子都没有</b>的表（真空表）仍然拒绝，那种表建出来是 <code>CREATE TABLE x ( )</code>，" + NL +
                    "MySQL 直接报语法错误</li>" + NL +
                    "<li>表头里的符号一律不进字段名：<code>*</code> / <code>＊</code> 这类必填标记、全角空格、" + NL +
                    "<code>【】〈〉《》「」〖〗</code> 等括号符本身都会去掉；<b>成对括号包住的那一段整段也不进名字</b>——" + NL +
                    "表单里 <code>发布日期（yyyy-MM-dd）</code>、<code>金额（元）</code>、<code>是否已婚【是/否】</code>、" + NL +
                    "<code>★（必填）联系电话</code> 括号里是写给填表人看的口径，不是字段的一部分，" + NL +
                    "于是分别取 <code>fa_bu_ri_qi</code>、<code>jin_e</code>、<code>shi_fou_yi_hun</code>、" + NL +
                    "<code>lian_xi_dian_hua</code>。整格只有括号（<code>（备注说明）</code>）时不剥空，照旧用里面那串字。" + NL +
                    "括号<b>没配右半边</b>也算注释：<code>发布日期（yyyy-MM-dd</code> 从左括号起整段丢。" + NL +
                    "<b>原始表头完整保留在字段注释里</b>，口径一个不丢，看注释就知道这列原来是哪个表头</li>" + NL +
                    "<li>日期时间<b>格式掩码串</b>即使没括号也不进名字：<code>发布日期：yyyy-MM-dd</code>、" + NL +
                    "<code>发布日期 yyyy-MM-dd</code>、<code>发布日期/yyyy/MM/dd</code>、<code>填表日期 yyyymmdd</code>" + NL +
                    "都取 <code>fa_bu_ri_qi</code> / <code>tian_biao_ri_qi</code>。判定只认\"<code>y/m/d/h/s</code>" + NL +
                    "连写两个以上、两头不贴字母数字\"的串，所以 <code>1.0mm</code> 里的 <code>mm</code>、" + NL +
                    "纯英文表头里的 <code>MM</code>、真日期 <code>2020-01-01</code> 都不会被吃掉；" + NL +
                    "表头不含汉字时整条不参与这个判断。" + NL +
                    "剥完之后同名会靠 <code>_2</code> / <code>_3</code> 去重，所以同一张表里" + NL +
                    "<code>发布日期（yyyy-MM-dd）</code> 和 <code>发布日期</code> 是<b>两列</b>（<code>fa_bu_ri_qi</code> 与" + NL +
                    "<code>fa_bu_ri_qi_2</code>），不会合成一列，但光看名字分不出谁是谁，要区分就改表头或在结构预览里改字段名</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>第 2 类：结构定义表（元数据 Sheet + 数据 Sheet）</h3>" + NL +
                    "<p>字段<b>以定义为准，不再推断</b>。任一 Sheet 的表头含「字段名、数据类型」及以下任一列，" + NL +
                    "就被识别为元数据页（它排在第几个 Sheet 都能认出来）：</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>序号</th><th>字段名(英文)</th><th>字段注释(中文)</th><th>数据类型</th>" + NL +
                    "<th>长度</th><th>是否主键</th><th>是否可为空</th></tr>" + NL +
                    "<tr><td>1</td><td>id</td><td>主键ID</td><td>INT</td><td></td><td>是</td><td>否</td></tr>" + NL +
                    "<tr><td>2</td><td>amount</td><td>金额</td><td>DECIMAL</td><td>10,2</td><td></td><td></td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li><code>长度</code> 填 <code>20</code> 或 <code>10,2</code>（→ <code>DECIMAL(10,2)</code>）；" + NL +
                    "类型列也可直接写 <code>VARCHAR(50)</code>，中文括号/逗号都能识别</li>" + NL +
                    "<li><code>是否主键=是</code> → 主键（支持多字段组合）；" + NL +
                    "<code>是否可为空=否</code> → <code>NOT NULL</code>，否则 <code>DEFAULT NULL</code></li>" + NL +
                    "<li>数据 Sheet 按字段名对齐（大小写不敏感）：多余列忽略并在日志提示，缺失列补 NULL</li>" + NL +
                    "<li>只有定义没有数据时建空表</li>" + NL +
                    "</ul>"
        ),
        new Section("export", "导出标准/简易 Excel",
                "<h2>导出标准/简易 Excel 交付件</h2>" + NL +
                    "<p>底部动作条「开始导入」右侧还有两颗按钮，把<b>当前待导入列表里已解析好的表</b>" + NL +
                    "再写出一份 Excel 交付件。两者都不连库、也不改动列表，产物落在<b>可执行程序同级目录</b>，" + NL +
                    "每次按日期建一个子目录。「SQL 脚本改名入库」那页的同名两颗按钮共用这一套命名，" + NL +
                    "只在目录名前加 <code>sql_</code>，所以两页各自生成时写进的是两个不同目录，" + NL +
                    "同名表不会互相覆盖。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>按钮</th><th>输出目录</th><th>每个文件里有什么</th></tr>" + NL +
                    "<tr><td>生成标准excel文件</td><td><code>meta_data_file_年月日</code></td>" + NL +
                    "<td>两个 Sheet：<b>元数据</b>（建表用的字段定义：序号 / 字段名(英文) / 字段注释(中文) /" + NL +
                    "数据类型 / 长度 / 是否主键 / 是否可为空）+ <b>数据</b>（表头是英文字段名，下面是数据行）</td></tr>" + NL +
                    "<tr><td>生成简易excel文件</td><td><code>simple_meta_data_file_年月日</code></td>" + NL +
                    "<td>只有一个 <b>数据</b> Sheet，给只要数据、不要建模说明的下游</td></tr>" + NL +
                    "</table>" + NL +
                    "<p>文件名沿用解析输入时那套命名：<code>表注释（表名）.xlsx</code>，没有表注释时只留表名。" + NL +
                    "标准件就是本工具「两类 Excel 格式」里的结构定义表，<b>可以直接拖回来再导入</b>：" + NL +
                    "字段名、类型、长度、主键、可空全部以元数据 Sheet 为准，读回来与导出前一致。</p>" + NL +
                    "<p>可空这一格写「否」才是 <code>NOT NULL</code>，留空或写「是」都是可空；" + NL +
                    "主键列自动写成「否」。日期一律写成 <code>2008-05-19</code> 这样的文本而不是 Excel 序列号，" + NL +
                    "避免同一份数据在两次读写之间被历元换算挪走一天。</p>" + NL +
                    "<p class=\"tip\">进度按文件计项（一张表 1 项），逐文件写日志，完成后弹窗把目录名原样报出来。" + NL +
                    "按「停止」会在当前这个文件写完后停下，剩下的不写，日志与弹窗都会写明" + NL +
                    "「已取消，剩余文件未生成」。</p>" + NL +
                    "<p>「SQL 脚本改名入库」页底部也有这两颗同名按钮，产物形状完全一致，" + NL +
                    "只是表定义改成从脚本正文的 <code>CREATE TABLE</code> 里解析，见" + NL +
                    "「SQL 脚本改名入库页」一节。</p>"
        ),
        new Section("naming", "字段命名与类型推断",
                "<h2>字段命名规则</h2>" + NL +
                    "<p>中文表头<b>逐音节转拼音、下划线连成蛇形</b>：</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>中文表头</th><th>字段名</th></tr>" + NL +
                    "<tr><td>订单金额</td><td><code>ding_dan_jin_e</code></td></tr>" + NL +
                    "<tr><td>员工编号</td><td><code>yuan_gong_bian_hao</code></td></tr>" + NL +
                    "<tr><td>总库容（亿立方米）</td><td><code>zong_ku_rong_yi_li_fang_mi</code>（25 字符，全拼保留）</td></tr>" + NL +
                    "<tr><td>北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表</td>" + NL +
                    "<td><code>bjshdqswjxsdxsksngcjcxxhzb</code>（全拼超 30，降级首字母）</td></tr>" + NL +
                    "<tr><td>station_code</td><td><code>station_code</code>（纯英文不做拼音转换）</td></tr>" + NL +
                    "<tr><td>7号线</td><td><code>f_7_hao_xian</code>（数字开头补 <code>f_</code>）</td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li><b>30 字符是唯一的降级触发条件</b>：全拼 ≤30 用全拼，超过则整列改用首字母。" + NL +
                    "不按\"中文字数\"另设阈值，规则确定可测</li>" + NL +
                    "<li>降级时<b>数字与单位音节整段保留</b>，不跟着取首字母——否则" + NL +
                    "<code>降水量≥1.0mm</code> / <code>≥10.0mm</code> / <code>≥100.0mm</code>" + NL +
                    "会全塌成同一个名字，而这些数字恰恰是列名的区分点</li>" + NL +
                    "<li>重名字段自动加 <code>_2</code>、<code>_3</code></li>" + NL +
                    "<li>实测本数据包 310 条真实中文列注释，<b>碰撞 0 组</b></li>" + NL +
                    "<li><b>命名开关（图形版两颗勾选框，命令行 <code>--initials</code> / <code>--upper</code>）</b>：" + NL +
                    "「表名与字段名取拼音首字母」勾上后整名取各音节首字母（订单金额 → <code>ddje</code>、" + NL +
                    "水库基础信息 → <code>skjcxx</code>）；「表名与字段名转大写」勾上后输出全大写" + NL +
                    "（<code>DING_DAN_JIN_E</code>、<code>T_IMPORT_DATA</code>）。图形版两颗默认都勾选" + NL +
                    "（首字母 + 大写），取消后回到全拼 / 小写；命令行不加参数时仍是全拼、小写。" + NL +
                    "大小写开关也作用于文件名括号里显式写下的英文表名，但不改结构定义 Sheet 里" + NL +
                    "人工写的字段名，也不改手动输入的表名前缀；两颗开关都对之后添加的文件生效，切换时日志留一行提示。" + NL +
                    "「SQL 脚本改名入库」页只保留「表名与字段名转大写」一项，默认不勾选。勾选后，" + NL +
                    "当前表名清单、双击打开的表元数据和生成的 Excel 一起按大写显示；脚本正文不改。" + NL +
                    "手动编辑过的表名保留输入内容，双击当前表名单元格可以直接改这一行的表名</li>" + NL +
                    "</ul>" + NL +
                    "<p class=\"warn\">字段命名规则变更后，同一张表生成的列名与旧版不同。若目标库里表已存在，" + NL +
                    "默认行为是跳过建表，随后 INSERT 会报 <b>1054 Unknown column</b>。" + NL +
                    "要么勾「覆盖已存在表」重建，要么在表结构预览里把字段名改回旧列名。</p>" + NL +
                    "" + NL +
                    "<h2>类型推断规则</h2>" + NL +
                    "<p><b>全量扫描所有数据行</b>，不做抽样，最长行/异常行不会漏判。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>数据特征</th><th>推断结果</th></tr>" + NL +
                    "<tr><td>日期时间对象，或含时间的字符串</td><td><code>DATETIME</code></td></tr>" + NL +
                    "<tr><td><b>表头含金额语义的数值列</b></td><td><code>DECIMAL(16,2)</code>（样本是整数也不用 INT）</td></tr>" + NL +
                    "<tr><td>纯整数</td><td><code>INT</code>，溢出则 <code>BIGINT</code></td></tr>" + NL +
                    "<tr><td>日期对象或带分隔符的日期串</td><td><code>DATE</code></td></tr>" + NL +
                    "<tr><td>含小数</td><td><code>DECIMAL(p,s)</code> 按实测位数定宽</td></tr>" + NL +
                    "<tr><td>文本</td><td><code>VARCHAR(n)</code>，按全量最大 UTF-8 字节 + 20% 余量取档位</td></tr>" + NL +
                    "<tr><td><b>整列没有值</b>（采集模板还没填数）</td><td>按字段名关键字定：日期→<code>DATE</code>、" + NL +
                    "时间→<code>DATETIME</code>、金额→<code>DECIMAL(16,2)</code>、是否/标志→<code>TINYINT(1)</code>、" + NL +
                    "比率/系数→<code>DECIMAL(18,6)</code>、评分→<code>DECIMAL(10,2)</code>、" + NL +
                    "经纬度→<code>DECIMAL(18,8)</code>、长度/重量/温度等计量→<code>DECIMAL(18,4)</code>、" + NL +
                    "数量/年龄/序号/年份→<code>INT</code>，其余 <code>VARCHAR(64)</code></td></tr>" + NL +
                    "<tr><td>超长文本</td><td>超 16383 字节 → <code>TEXT</code>，超 65535 字节 → <code>MEDIUMTEXT</code></td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>几个容易踩的点已经处理掉了</h3>" + NL +
                    "<ul>" + NL +
                    "<li><b>金额语义识别</b>：Excel 里 <code>100</code> 常以 <code>100.0</code> 存储，" + NL +
                    "纯值判定会把\"订单金额\"判成 INT 砍掉小数。所以加了一层表头语义修正——" + NL +
                    "命中金额关键词（金额/单价/价格/费用/成本/余额/工资/收入/支出/税额/利息/销售额/" + NL +
                    "保证金…，英文 amount/price/cost/fee/salary/balance…）的数值列一律" + NL +
                    "<code>DECIMAL(16,2)</code></li>" + NL +
                    "<li><b>空列按字段名定类型，且每条都留痕</b>：整列一个值都没有时，先看字段名——" + NL +
                    "发布日期 → <code>DATE</code>、更新时间 → <code>DATETIME</code>、" + NL +
                    "订单金额 → <code>DECIMAL(16,2)</code>、数量/年龄/序号/年份 → <code>INT</code>、" + NL +
                    "是否/有无/开关/标志 → <code>TINYINT(1)</code>、" + NL +
                    "率/比例/系数/权重 → <code>DECIMAL(18,6)</code>、" + NL +
                    "得分/评分/成绩 → <code>DECIMAL(10,2)</code>、" + NL +
                    "经度/纬度/坐标 → <code>DECIMAL(18,8)</code>、" + NL +
                    "长度/面积/重量/温度/频率 → <code>DECIMAL(18,4)</code>，其余仍" + NL +
                    "<code>VARCHAR(64)</code>；英文按成词匹配（<code>create_date</code>、<code>qty</code>、" + NL +
                    "<code>is_deleted</code>、<code>longitude</code> 命中，<code>update</code>、<code>birthday</code>" + NL +
                    "不误中）。金额优先于其他语义；显式比率词优先于计量词，裸「率」放在计量词之后，" + NL +
                    "所以频率、功率按计量，容积率按比率。有值的列照旧按数据判定；" + NL +
                    "结构定义表里人工写的类型优先。每次按名定类型都写进" + NL +
                    "<code>[类型修正] … 按表头语义调整</code> 日志行，现场能对上是哪一列、为什么</li>" + NL +
                    "<li><b>只收成词的关键词，不认裸「费」「价」</b>：<code>费县</code>（地名）、" + NL +
                    "<code>评价</code> 不会被误判成金额</li>" + NL +
                    "<li><b>比率类一律否决金额语义</b>：含 率/比例/百分比/占比，或名字含" + NL +
                    "rate/ratio/percent 的列不算金额（<code>年利率</code> 按实测小数位定宽，" + NL +
                    "否则 4 位小数被压成 2 位，数据就改了）</li>" + NL +
                    "<li><b>绝不截断实测精度</b>：<code>结算金额</code> 实测有 4 位小数就给" + NL +
                    "<code>DECIMAL(18,4)</code>；经纬度 <code>39.9046897</code> 保住 7 位给" + NL +
                    "<code>DECIMAL(11,7)</code>；科学计数法大数按展开后的位数放宽</li>" + NL +
                    "<li><b>8 位纯数字</b>（如 <code>20230101</code>）按数值处理，编码列不会误判成日期</li>" + NL +
                    "<li><b>带前导零的整数串</b>（如 <code>007</code>）保持文本，不丢前导零</li>" + NL +
                    "<li><b>行内字节预算</b>：utf8mb4 下每个 <code>VARCHAR(n)</code> 计 4n 字节，" + NL +
                    "全表共享 60000 字节预算，预算不足的大文本列自动退为 <code>TEXT</code>，" + NL +
                    "保证建表不报 Row size too large</li>" + NL +
                    "<li>数值列中无法转换的值按 NULL 写入，并在日志里汇总告警（列名、数量、行号示例），" + NL +
                    "不静默丢数据</li>" + NL +
                    "<li><b>装不进 BIGINT 的整数会被整张表拒绝</b>：推断只能给到 <code>BIGINT</code>，" + NL +
                    "超过 <code>±9223372036854775807</code> 时 MySQL 报" + NL +
                    "<code>1264 Out of range value</code>，该表事务回滚、其余表继续，不会把一个变过样的数写进库。" + NL +
                    "Excel 的数值单元格本身是双精度浮点，<code>9223372036854775807</code> 存进去就已经变成" + NL +
                    "<code>2^63</code>，所以要落这种量级的数，得在结构定义 Sheet 里把该列写成" + NL +
                    "<code>DECIMAL(20,0)</code>，或者当文本列导</li>" + NL +
                    "</ul>" + NL +
                    "<p>所有推断结果都可以在<b>「生成表结构预览」</b>里逐列改。</p>"
        ),
        new Section("prefix", "表名前缀：加与换",
                "<h2>表名前缀：加与换</h2>" + NL +
                    "<p>「表名前缀」输入框旁边有<b>两个按钮，共用同一个输入框</b>，做的是两件事：</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>按钮</th><th>做什么</th><th>例子（填 <code>change</code>）</th></tr>" + NL +
                    "<tr><td>批量应用前缀</td><td>在表名<b>前面再加一段</b>；始终基于「基础表名」重算，" + NL +
                    "所以换前缀不会叠加，<b>留空再点它就是移除前缀</b></td>" + NL +
                    "<td><code>ods_t_order</code> → <code>changeods_t_order</code>（想加分隔就在框里写" + NL +
                    "<code>change_</code>）</td></tr>" + NL +
                    "<tr><td>替换首个前缀</td><td>把表名<b>第一个下划线之前那一段换掉</b>，后面的部分原样保留</td>" + NL +
                    "<td><code>ods_t_order</code> → <code>change_t_order</code><br>" + NL +
                    "<code>adb_table2</code> → <code>change_table2</code></td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>「替换首个前缀」的细节</h3>" + NL +
                    "<ul>" + NL +
                    "<li>按<b>第一个下划线</b>切分，所以 <code>ods_a_b</code> 只换掉 <code>ods</code>，得到" + NL +
                    "<code>change_a_b</code></li>" + NL +
                    "<li>末尾那个下划线<b>不用写</b>：填 <code>change</code> 与 <code>change_</code> 效果相同，" + NL +
                    "不会出现 <code>change__t_order</code></li>" + NL +
                    "<li><b>表名里没有下划线的不改</b>（如 <code>users</code>：整名只有一段，换掉等于改名而不是换前缀），" + NL +
                    "以下划线开头的（如 <code>_tmp</code>）同样不改；这些表会逐条写进日志说明原因</li>" + NL +
                    "<li><b>重复点不会叠加</b>：已经是 <code>change_</code> 开头的表名再点一次保持原样</li>" + NL +
                    "<li>换完如果两张表撞名（<code>ods_a</code> 与 <code>x_a</code> 都变成 <code>change_a</code>），" + NL +
                    "自动按 <code>change_a</code> / <code>change_a_2</code> 去重</li>" + NL +
                    "<li>只允许字母、数字、下划线；填了别的字符会弹窗拒绝，<b>不会生成非法表名</b></li>" + NL +
                    "<li>换过之后这就是新的基础表名：再点「批量应用前缀」填 <code>dim_</code>，" + NL +
                    "得到 <code>dim_change_t_order</code></li>" + NL +
                    "<li>拼音表名要留意：自动生成的 <code>shui_ku_ji_chu_xin_xi</code> 第一刀会切在" + NL +
                    "<code>shui</code> 后面，变成 <code>change_ku_ji_chu_xin_xi</code>——" + NL +
                    "这类名字请用「批量应用前缀」而不是「替换首个前缀」</li>" + NL +
                    "</ul>" + NL +
                    "<p>「SQL 脚本改名入库」页同样有这两个按钮，但<b>只改页面显示</b>：" + NL +
                    "「当前表名」列和双击打开的元数据标题同步变，脚本正文一个字不动。" + NL +
                    "点「保存修改后ddl脚本」「执行修改后ddl脚本到当前库」或两颗生成按钮时，" + NL +
                    "才把显示名写进脚本并同步改写 <code>CREATE / DROP / INSERT INTO / ALTER / TRUNCATE</code>" + NL +
                    "对同一张表的引用。把前缀框清空后再点一次任一前缀按钮，显示恢复成脚本里的原名；" + NL +
                    "表格里的「原表名」一列始终显示最初的名字，方便核对。</p>"
        ),
        new Section("pk", "主键",
                "<h2>主键</h2>" + NL +
                    "<p>主键约束<b>至多一个</b>，可由一个或多个字段组成组合主键。</p>" + NL +
                    "<h3>自动选择（添加文件时）</h3>" + NL +
                    "<p>硬性条件：类型为 <code>INT</code>/<code>BIGINT</code> 或长度 ≤255 的" + NL +
                    "<code>VARCHAR</code>/<code>CHAR</code>；全列非空且唯一；表至少有一行数据。</p>" + NL +
                    "<p>评分（≥3 分才入选，避免把碰巧唯一的金额列当主键）：" + NL +
                    "<b>+4</b> 字段名形如 id/uuid/_id 或注释含 ID/主键/唯一标识；" + NL +
                    "<b>+3</b> 注释含 编号/序号/代码/编码/标识/卡号/单号；" + NL +
                    "<b>+2</b> 整数类型；<b>+1</b> 短字符类型（VARCHAR ≤64）。" + NL +
                    "同分取字节长度更短的列。没有合适字段就不设主键。</p>" + NL +
                    "<h3>手动修改</h3>" + NL +
                    "<p>「生成表结构预览」里每行有主键勾选框，可自由勾选/取消一个或多个字段。" + NL +
                    "保存时自动校验，<b>不通过会提示且改动不生效</b>，弹窗保持打开让你调整：</p>" + NL +
                    "<ul>" + NL +
                    "<li>类型不能作主键（如 TEXT）</li>" + NL +
                    "<li>存在空值或空白值</li>" + NL +
                    "<li>组合值重复</li>" + NL +
                    "</ul>"
        ),
        new Section("sqlpage", "SQL 脚本改名入库页",
                "<h2>SQL 脚本改名入库页（第二个页签）</h2>" + NL +
                    "<p>用来处理现成的 <code>.sql</code> 建表脚本，三步按顺序走：把脚本里的" + NL +
                    "<code>CREATE TABLE</code> 提取出来列成一张表（表格左上角那句「待处理的脚本表」就是它）、" + NL +
                    "按这份表名清单批量改表名、" + NL +
                    "再另存改好的副本或执行到当前库。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>按钮</th><th>作用</th></tr>" + NL +
                    "<tr><td>拖 SQL 脚本进来 / 点这块虚线区</td><td>可多选，自动统计脚本里所有" + NL +
                    "<code>CREATE TABLE [IF NOT EXISTS] `表名`</code> 并提取表名" + NL +
                    "（兼容反引号 / 裸名 / <code>schema.表名</code> 限定名，按首次出现顺序展示）；" + NL +
                    "<code>INSERT INTO</code> 等不计入。这一页与「Excel 导入」那页<b>同一套入口</b>：" + NL +
                    "虚线框既可以拖文件进来，也可以点一下打开选择器，没有单独的「添加 SQL 文件」按钮。" + NL +
                    "拖进来的东西里混着不是 <code>.sql</code> 的，日志逐个点名跳过；" + NL +
                    "整批都不是 <code>.sql</code> 时明说一句已忽略，不会清单不动又没话</td></tr>" + NL +
                    "<tr><td>移除选中文件</td><td>排在表格下面那一排的最左（与「Excel 导入」那页" + NL +
                    "「清空待上传文件」同一位置），只把选中的脚本从清单里去掉，磁盘上的脚本不动；" + NL +
                    "没选中任何东西时说清\"没选中文件，未移除任何脚本\"</td></tr>" + NL +
                    "<tr><td>批量应用前缀</td><td><b>只改页面显示</b>：给「当前表名」列加一段显示前缀，" + NL +
                    "双击看的元数据标题同步变，脚本正文不动；已带该前缀的自动跳过，重复应用不会叠加；" + NL +
                    "前缀留空再点一次，显示恢复为脚本原名。真正写进脚本要等四个动作按钮</td></tr>" + NL +
                    "<tr><td>替换首个前缀</td><td><b>只改页面显示</b>：基于页面当前显示名，把第一个下划线之前的段落" + NL +
                    "换成输入框里的内容（先加过显示前缀就接着换，<code>ods_t_order</code> → <code>change_t_order</code>）；" + NL +
                    "表名里没有下划线的不动，重复点不会叠加；留空再点恢复脚本原名</td></tr>" + NL +
                    "<tr><td>保存修改后ddl脚本</td><td>先把页面显示的表名写进内存脚本（引用一并同步改写），" + NL +
                    "再另存为 <code>原名_prefix.sql</code>，落在源文件旁边，<b>不覆盖原文件</b>；" + NL +
                    "没有可保存的改动时也会弹窗说明</td></tr>" + NL +
                    "<tr><td>执行修改后ddl脚本到当前库</td><td>点击时先把显示名写进脚本，再按上方公共连接区选定的目标库执行；" + NL +
                    "<b>每个文件一个事务</b>：先开启事务，逐条执行，任一语句失败立即回滚整个事务" + NL +
                    "并在日志标红报错，后续语句不再执行，全部成功才提交</td></tr>" + NL +
                    "<tr><td>生成标准excel文件</td><td>把脚本里解析出的字段定义写成" + NL +
                    "<code>sql_meta_data_file_年月日</code> 目录下的 xlsx，一张表一个文件，" + NL +
                    "形状与「Excel 导入」页那两颗完全一样（元数据 + 数据 两个 Sheet）；" + NL +
                    "目录名带 <code>sql_</code> 前缀，与「Excel 导入」那页的产物互不覆盖，两颗按钮也能同时跑；" + NL +
                    "区别在数据源：这里<b>只看 <code>CREATE TABLE</code> 的列定义，不看库、也不看文件里的" + NL +
                    "<code>INSERT</code></b></td></tr>" + NL +
                    "<tr><td>生成简易excel文件</td><td>同上，只留 <b>数据</b> 一个 Sheet</td></tr>" + NL +
                    "<tr><td>双击行（文件 / 原表名 / 表注释列）</td><td>打开该表从 <code>CREATE TABLE</code>" + NL +
                    "解析出的元数据，逐列查看字段名、注释、类型、长度、主键与可空；这里是只读视图。" + NL +
                    "字段定义在<b>读入脚本那一刻就按表解析好存着</b>，几十万行的脚本双击也是立刻弹出，" + NL +
                    "不用等那一秒多的重解析</td></tr>" + NL +
                    "<tr><td>双击「当前表名」</td><td>直接编辑这一行的表名，并同步改写该脚本里的" + NL +
                    "<code>CREATE / DROP / INSERT / ALTER / TRUNCATE</code> 引用；改动先留在内存，" + NL +
                    "点「保存修改后ddl脚本」才落副本</td></tr>" + NL +
                    "<tr><td>双击「表注释」</td><td>直接编辑这一张表的表注释：把那句" + NL +
                    "<code>CREATE TABLE</code> 的表选项 <code>COMMENT='…'</code> 换成新内容（原本没写" + NL +
                    "<code>COMMENT</code> 的，在列定义右括号后补一句）。里面带的单引号按 MySQL 口径翻倍写出，" + NL +
                    "再读回来就是原话；清空写的是 <code>COMMENT=''</code>。改完清单、元数据弹窗和两份生成件" + NL +
                    "立刻跟着变（标准件的文件名就是「表注释（表名）.xlsx」），脚本正文同样先留在内存，" + NL +
                    "点「保存修改后ddl脚本」才落副本。<code>CREATE TABLE x LIKE y</code> 与" + NL +
                    "<code>... AS SELECT</code> 不带表选项，改不动会当场说明原因</td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li><b>两个前缀按钮只改显示</b>：不弹进度框、也不写脚本；「保存修改后ddl脚本」" + NL +
                    "「执行修改后ddl脚本到当前库」「生成标准excel文件」「生成简易excel文件」这四个动作按钮" + NL +
                    "点下去时才把显示名写进脚本，并弹出进度框、结束后给出结果弹窗。" + NL +
                    "前缀框清空后再点任一前缀按钮，显示恢复为脚本原名；列表里没有脚本、" + NL +
                    "或脚本里解析不出 <code>CREATE TABLE</code> 时，点这两颗按钮会弹窗说明，" + NL +
                    "不会假装改了 0 个表名</li>" + NL +
                    "<li>「表名与字段名转大写」勾选后，当前表名清单、表元数据里的字段名以及两份生成件" + NL +
                    "同步按大写显示；脚本正文本身不回写。当前页不再提供拼音首字母开关</li>" + NL +
                    "<li>读入脚本结束后弹「添加结果」：逐个列出<b>脚本完整路径</b>，分别标注已加载、" + NL +
                    "已在列表（跳过）与读取失败；读取失败还会在日志里给出原因，文件不会进表</li>" + NL +
                    "<li>「保存修改后ddl脚本」「执行修改后ddl脚本到当前库」结束后的结果弹窗按<b>完整路径</b>" + NL +
                    "逐个文件列结局：保存列出每一份副本的落点与每个失败项；执行先写目标库，再逐文件标" + NL +
                    "成功 / 失败 / 跳过 / 已取消并附语句进度，弹性窗口放不下时正文可鼠标选中并 Ctrl+C 复制</li>" + NL +
                    "<li>「生成标准excel文件」「生成简易excel文件」完成后，结果弹窗至少 820×420，正文可用鼠标选中并 Ctrl+C 复制</li>" + NL +
                    "<li>两颗生成按钮取的是<b>改名之后的当前正文</b>，所以\"先批量改前缀、再生成交付件\"" + NL +
                    "这条路是通的，产物里的表名就是新表名</li>" + NL +
                    "<li>脚本里只有结构、没有数据，所以「数据」Sheet 只剩英文字段名那一行表头，" + NL +
                    "给下游当可填模板用，不假装里面有数据行</li>" + NL +
                    "<li>元数据 Sheet 只能装「一个类型关键字 + 纯数字长度」，所以" + NL +
                    "<code>UNSIGNED</code> / <code>ZEROFILL</code> / <code>CHARACTER SET</code> /" + NL +
                    "<code>COLLATE</code> 这些修饰会被丢掉，<code>ENUM('a','b')</code> 这类括号里不是数字的" + NL +
                    "按 <code>TEXT</code> 导出；每一处都在日志里逐列点名（<code>! [WARN] 说明 | ...</code>），" + NL +
                    "不静默改语义</li>" + NL +
                    "<li>同名表被定义了多次时只按<b>第一处</b>的列定义导出，其余跳过并写明是哪一处</li>" + NL +
                    "<li><code>CREATE TABLE x LIKE y</code> 与 <code>... AS SELECT</code> 这两种写法里没有列定义，" + NL +
                    "生成不了元数据，日志点名后跳过</li>" + NL +
                    "<li>语句切分能正确跳过字符串 / 反引号 / 注释中的分号</li>" + NL +
                    "<li><b>不支持</b>含 <code>DELIMITER</code> 的存储过程脚本</li>" + NL +
                    "<li>文件编码自动尝试 UTF-8 / UTF-8-BOM / GBK</li>" + NL +
                    "<li>注意 MySQL 的 <code>CREATE/DROP/ALTER</code> 等 DDL 会<b>隐式提交</b>，" + NL +
                    "无法随事务回滚；回滚只对其后的数据操作生效</li>" + NL +
                    "<li>Excel 导入同样是事务语义：每张表先开启事务，数据插入成功后提交，" + NL +
                    "失败整体回滚并记录日志</li>" + NL +
                    "</ul>"
        ),
        new Section("sqlrun", "SQL 执行器页",
                "<h2>SQL 执行器页（第三个页签）</h2>" + NL +
                    "<p>临时问一句、顺手改点结构用它：直接把 SQL 敲在编辑区里点「执行」就打到了" + NL +
                    "上方公共连接区选定的库。与「SQL 脚本改名入库」页的分工是——那一页管\"手上有一批" + NL +
                    "<code>.sql</code> 文件要入库\"（挑文件、批量改表名、落盘副本），这一页不挑文件也不落盘，" + NL +
                    "只吃编辑区里那段文本。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>控件</th><th>作用</th></tr>" + NL +
                    "<tr><td>页顶的目标库指向条</td><td>与最上方那条连接状态栏同一次刷新：绿色写" + NL +
                    "<code>目标库：库名 —— 编辑区里的 SQL 将执行到这个库</code>；没选库时整条隐藏，" + NL +
                    "未选库的告警只由顶部状态栏说一次。" + NL +
                    "主机和端口不在这条里（收在状态栏的悬停提示）。这一页点的每条语句都落在这个库里，" + NL +
                    "动手前先看这一行</td></tr>" + NL +
                    "<tr><td>示例 (?)</td><td>打开示例清单（100 条，分 12 组：建库与选库、建表与改表、" + NL +
                    "元数据与治理体检、数据质量检查、数据标准化与清洗、主数据与重复数据、" + NL +
                    "数据血缘与影响分析、安全与权限治理、生命周期与归档、性能与容量治理、" + NL +
                    "查询与执行计划、数据修正）。左边选中一条，右边给全文，" + NL +
                    "可「复制到剪贴板」也可「插入到编辑区」；对话框不锁界面，能边看边改</td></tr>" + NL +
                    "<tr><td>执行</td><td>按分号切句逐条执行，每条把语句原文、影响行数或结果集写进右侧结果区</td></tr>" + NL +
                    "<tr><td>清空编辑区</td><td>在「SQL 文本」那一栏的头一行，只清左边这段 SQL，右侧结果区不动</td></tr>" + NL +
                    "<tr><td>清空结果区</td><td>在「执行结果」那一栏的头一行，只清右边的执行结果，左边编辑区不动</td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li>事务语义：<b>全部成功才提交，中途出错整批回滚</b>，失败那句后面附" + NL +
                    "DDL 隐式提交的提醒；结果区逐条给 <code>第 i/n 条</code>，出错那条标红</li>" + NL +
                    "<li>查询出来的结果<b>排成等宽表格</b>再写进结果区：列名一行、上下与表头下面各一条分隔线，" + NL +
                    "每列按显示宽度补齐（汉字占两列），所以中英混排也对得齐；空格子写成 <code>NULL</code>，" + NL +
                    "取值里的换行与制表符写成可见的转义 <code>\\n</code> / <code>\\t</code>，" + NL +
                    "单个格子超过 40 列截断并补省略号——不然一条带换行的长备注会把整张表顶散</li>" + NL +
                    "<li>结果集最多打印前 200 行，超出只报总行数（<code>共 N 行（只打印前 200 行，其余没显示）</code>），" + NL +
                    "避免几十万行刷爆结果区；结果区与编辑区同一支等宽字体</li>" + NL +
                    "<li>语句切分与「SQL 脚本改名入库」页同一套：跳过字符串 / 反引号 / 注释里的分号；" + NL +
                    "整句只剩注释的片段会被剔掉，不会算成\"已提交 1 条\"</li>" + NL +
                    "<li>编辑区不折行，一句 SQL 一行看到底；开场预置 <code>SELECT 1 AS 连通检查;</code>，" + NL +
                    "连不上库时结果区直接给错误码与原因</li>" + NL +
                    "<li>示例里的表名/库名是占位（<code>t_order</code>、<code>ods_data</code>），" + NL +
                    "现场改完再执行；这一页执行的就是你提交的语句，<code>DROP</code>/<code>TRUNCATE</code>" + NL +
                    "不另设确认，删东西前先看结果区里那句</li>" + NL +
                    "</ul>"
        ),
        new Section("log", "日志与报错",
                "<h2>日志与报错</h2>" + NL +
                    "<p>三个页签各有一个独立日志区（Excel 导入页底部、SQL 脚本改名入库页底部、" + NL +
                    "SQL 执行器页右半边的结果区），互不串台，各自保留最近 2000 条，超出自动丢弃最早的。" + NL +
                    "三个日志区都有最小高度（按日志那一档字号算四行）：" + NL +
                    "把分隔条拖到极限也不会被压成一两行。</p>" + NL +
                    "<p>每条记录都写成 <code>[年-月-日 时:分:秒] 符号 [级别] 正文</code>，四档级别与命令行版同一套标签，" + NL +
                    "时间戳也跟命令行日志文件同一形状（年月日到秒）：</p>" + NL +
                    "<ul>" + NL +
                    "<li><code>+ [OK]</code> 绿色：这件事做成了（解析、加载、保存、提交、改名）</li>" + NL +
                    "<li><code>- [INFO]</code> 默认色：记一笔，不需要处理（开关切换、移除、清临时目录）</li>" + NL +
                    "<li><code>! [WARN]</code> 琥珀加粗：这次没做成但有原因（跳过、重复、取消、空包）</li>" + NL +
                    "<li><code>x [ERROR]</code> 红色加粗：失败，需要处理（解析失败、建表失败、连不上库）</li>" + NL +
                    "</ul>" + NL +
                    "<p>正文里并列的事实用 <code>|</code> 分段，补充说明缩进挂在下一行，不挤进正文：</p>" + NL +
                    "<pre>[2026-10-23 14:05:12] + [OK] [已解析] 水库基础信息.xlsx → 表名预置 dim_reservoir_info | 字段 18 列 | 数据 5 行 | 主键 `BH`（整数类型，全列非空且唯一）" + NL +
                    "    说明 | 整列为空的字段照旧保留：备注" + NL +
                    "[2026-10-23 14:05:20] ! [WARN] [跳过] 已在列表：人员信息表.xlsx" + NL +
                    "[2026-10-23 14:05:31] - [INFO] ===== 全部完成：成功 2 张表，失败 1 张表 =====</pre>" + NL +
                    "<p>时间戳只打在每条记录的第一行；「SQL 执行器」结果表格里那些本来就缩进的行属于上一条" + NL +
                    "记录，给它们逐个打时间戳会把等宽表格顶歪。</p>" + NL +
                    "<p>报错不只打异常原文，而是展开成" + NL +
                    "<b>异常类型 + MySQL 错误码 + 服务端原始报错全文</b>：</p>" + NL +
                    "<pre>[2026-10-23 14:07:02] x [ERROR] [执行失败] a.sql 第 2/3 条语句报错，目标库 testdb@127.0.0.1:3306，本文件事务已回滚" + NL +
                    "    ProgrammingError（MySQL 错误码 1064）：You have an error in your SQL syntax; ..." + NL +
                    "    出错语句：" + NL +
                    "        INSERT INTO t_bad VALUE (1)" + NL +
                    "    前面 1 条语句已随事务回滚。注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交...</pre>" + NL +
                    "<h3>各场景给出的信息</h3>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>场景</th><th>日志内容</th></tr>" + NL +
                    "<tr><td>SQL 执行失败</td><td>文件名、第几条/共几条、目标库 <code>库名@主机:端口</code>、" + NL +
                    "错误码与原始报错、<b>出错语句全文</b>（超 2000 字符截断并标注原文长度）、回滚说明</td></tr>" + NL +
                    "<tr><td>SQL 连接失败</td><td>库名@主机:端口、用户名、错误码，并注明\"剩余脚本均未执行\"</td></tr>" + NL +
                    "<tr><td>Excel 建表失败</td><td>表名、目标库、错误码、<b>失败的完整建表 DDL</b></td></tr>" + NL +
                    "<tr><td>Excel 导入失败</td><td>表名、目标库、已尝试写入的数据行数、错误码、回滚说明</td></tr>" + NL +
                    "<tr><td>合并表头展开 / 合并单元格拒绝</td><td>展开：按几行压成一行；拒绝：文件名、合并区总数、每个 Sheet 的位置清单、原因与处理建议</td></tr>" + NL +
                    "<tr><td>结尾汇总行</td><td>只要存在失败，整行按错误级标红</td></tr>" + NL +
                    "</table>" + NL +
                    "<p class=\"tip\">日志区右上角有<b>「清除日志」</b>按钮（SQL 页有自己独立的清除按钮）。" + NL +
                    "添加、导入、执行和生成结果弹窗的正文都可用鼠标选中后 Ctrl+C 复制。" + NL +
                    "弹窗正文不带日志行首的时间戳与级别——两处说的是同一句话，只是一个能回溯、一个防走神。</p>"
        ),
        new Section("summary", "添加结果汇总",
                "<h2>添加结果汇总</h2>" + NL +
                    "<p>无论点虚线区选文件还是直接拖拽，添加完成后都会<b>把汇总同时打进日志区并弹窗</b>——" + NL +
                    "日志可回溯、弹窗防走神漏看，两处文字逐字一致。散文件与压缩包各占一行，" + NL +
                    "只输出本次实际用到的行：</p>" + NL +
                    "<pre>添加 3 项文件，包含 2 项 xlsx 文件、1 项 xls 文件" + NL +
                    "添加 1 项 zip 文件，包含 12 项 xlsx 文件、0 项 xls 文件" + NL +
                    "另有 2 项解析失败、1 项重复已跳过，详见日志</pre>" + NL +
                    "<ul>" + NL +
                    "<li>xlsx / xls 的数量<b>只统计真正进入待导入列表的</b>，" + NL +
                    "所以弹窗数字和列表行数始终对得上</li>" + NL +
                    "<li>「添加 N 项 zip 文件」的 N 指你投入了几个包（压缩包本身不进列表）</li>" + NL +
                    "<li>第三行只在真有异常时出现，按 <code>、</code> 拼接这几类，为 0 的不写：" + NL +
                    "<code>N 项含合并单元格已拒绝</code>、<code>N 项解析失败</code>、" + NL +
                    "<code>N 项重复已跳过</code>、<code>N 个压缩包无法展开</code>、" + NL +
                    "<code>N 个压缩包内没有 Excel</code></li>" + NL +
                    "<li>全部成功时只有前两行，不会出现「另有」</li>" + NL +
                    "<li>只拖了空压缩包也会弹窗，否则界面看着像没反应</li>" + NL +
                    "<li>重复文件在日志里有对应行 <code>[重复跳过] xxx.xlsx 已在待导入列表</code>，" + NL +
                    "数字可追溯</li>" + NL +
                    "<li>失败的具体原因始终在日志里标红，汇总只报数量不塞细节</li>" + NL +
                    "</ul>"
        ),
        new Section("layout", "界面布局",
                "<h2>界面布局</h2>" + NL +
                    "<ul>" + NL +
                    "<li><b>MySQL 连接信息</b>区在三个页签<b>上方</b>，三页共用同一套连接参数和目标库选择，" + NL +
                    "不需要在每个页签里分别填；它平时收起成一条，只报当前是哪个库（详见下面「连接信息平时收起成一条」）</li>" + NL +
                    "<li><b>「Excel 导入」页的三段高度可拖拽</b>：拖拽区、待导入文件表、日志区之间有" + NL +
                    "6px 宽的横向分隔条，鼠标按住即可上下拖动重新分配高度。" + NL +
                    "三段都不允许拖到 0 高，不会拖丢了找不回来。" + NL +
                    "提示条和底部动作条（覆盖勾选框、停止、开始导入、生成标准excel文件、" + NL +
                    "生成简易excel文件、进度条）是固定件，不参与挤压。" + NL +
                    "高度分配不写配置，重开程序回到初始比例</li>" + NL +
                    "<li><b>「SQL 脚本改名入库」页也能拖拽分高</b>：拖拽区 / 待处理文件表格区 / 日志区三段，" + NL +
                    "段与段之间各一条 6px 分隔条，一边变高另一边就变窄。日志很多时把下半区拉大；" + NL +
                    "要核对表名清单时把表格拉大。三段都可以拖到多小都有下限，不会哪一段被拖没。" + NL +
                    "「表名前缀」行、大写开关与操作条跟着表格区一起缩放，上面那条提示条是固定件；" + NL +
                    "拖拽区自己就是第一段，与「Excel 导入」那页同一形状</li>" + NL +
                    "<li><b>「SQL 执行器」页左右分栏</b>：SQL 文本与执行结果之间一条 6px 竖向分隔条，" + NL +
                    "一边变宽另一边就变窄；两边都不允许拖到 0 宽。上面那行提示和「示例 (?)」「执行」是固定件，不参与挤压；两颗<b>清空</b>分别在左右两栏的头一行，各管自己那一边</li>" + NL +
                    "<li>三个页签的日志框都带<b>最小高度</b>（按日志那一档字号算四行）：把分隔条拖到极限，" + NL +
                    "日志也不会被压成一两行——只剩两行的日志框等于没有，往上滚就看不到上一条。" + NL +
                    "默认比例把最大的那一段给表格、日志次之（表格是主工作面），窗口放大时多出来的高度也是表格拿大头</li>" + NL +
                    "<li>三个页签的高度分配都<b>不写入配置</b>，重开程序回到初始比例</li>" + NL +
                    "<li>待导入文件表的<b>列宽可自由拖动</b>，最后一列自动填满剩余空间</li>" + NL +
                    "<li><b>连接信息平时收起成一条</b>：上方只留「● 目标库 xxx」，主机 / 端口 / 用户名" + NL +
                    "收在鼠标悬停里——跑业务时真正要看的只有\"现在是哪个库\"。点<b>「设置数据源信息」</b>展开填表、" + NL +
                    "测连接、换库，选定库后自动收起（展开时点<b>「收起」</b>也能手动收回去）。" + NL +
                    "程序启动就是收起的，还没有目标库时红色告警直接指向那颗按钮，不用先找开关</li>" + NL +
                    "<li><b>每张表格左上角都有一句标识</b>：第一页是「待导入文件（单击表名改名，双击其他列改字段）」、" + NL +
                    "第二页是「待处理的脚本表（双击表名或表注释编辑，双击其他列看元数据）」、" + NL +
                    "双击打开的元数据弹窗里是「字段清单（共 N 列）」——标题里顺带写明这一张表怎么操作。" + NL +
                    "结构预览弹窗不重复加：那一页每个 tab 上写的就是表名，已经贴在表格正上方</li>" + NL +
                    "<li><b>表格内容比日志大一档</b>（两栈同一组数：表格 11 号、日志 10 号，与 Excel 数据网格同级）：" + NL +
                    "这一页真正要读的是表格里那些字段名，日志只是过程回执，所以字号给内容让。" + NL +
                    "表格内容与表头同一档，行高按字体量出来，不写死像素</li>" + NL +
                    "<li>右上角有两个小按钮：<b>「?」</b>打开本使用说明，<b>「i」</b>打开「关于」" + NL +
                    "（版本号 / 提交号 / 构建日期 / 作者四行，提交号可鼠标选中复制）</li>" + NL +
                    "<li>「关于」里的提交号与构建日期是<b>打包那一刻烧进 exe</b> 的（exe 所在机器没有 git，" + NL +
                    "运行时查不到）；开发态直接跑 <code>python main.py</code> 时现查 git，查不到就显示" + NL +
                    "\"开发态（未打包）\"，不会编一个假提交号</li>" + NL +
                    "<li>「Excel 导入」页的待导入文件表：第一列「序号」从 1 连续编到最后一行，" + NL +
                    "行数就是当前待导入表的数量；单击<b>表名</b>直接改这一张表的名字，" + NL +
                    "回车生效；空名、超过 64 字符、含非法字符或与其他表重名会被拦下并恢复原名。" + NL +
                    "双击<b>文件 / 表注释 / 字段数 / 数据行数 / 主键</b>任一列查看该表的字段结构；" + NL +
                    "「SQL 脚本改名入库」页：第一列同样是连续序号，最后一个序号就是这批脚本解析出的" + NL +
                    "建模语句总数（多个文件共用一套编号）；清单从左到右是" + NL +
                    "<b>序号 / 文件 / 原表名 / 当前表名 / 表注释</b>，最后一列取脚本里" + NL +
                    "<code>CREATE TABLE ... COMMENT='…'</code> 那句，与「Excel 导入」那页的「表注释」同一含义，" + NL +
                    "脚本里没写就没有（不留一个空占位）；双击<b>当前表名</b>或<b>表注释</b>编辑，" + NL +
                    "双击其余列查看该表元数据</li>" + NL +
                    "<li>来自 ZIP 的文件，鼠标悬停「文件」列会显示完整来源，形如" + NL +
                    "<code>数据打包.zip!北京/水库基础信息.xlsx</code></li>" + NL +
                    "<li>导入成功的文件会自动从待导入列表移除，<b>失败的文件保留</b>，可以调整后重试；" + NL +
                    "单表失败不阻断其余表</li>" + NL +
                    "<li><b>弹窗里的文字一定会被折进窗口里</b>：提示、警告、出错这三类弹窗显示的就是那段" + NL +
                    "原文，折行由正文控件按<b>内容区右边界</b>做——一行长路径、一整段异常信息都不会把窗口" + NL +
                    "撑到屏幕外，<b>把弹窗拉大，文字会跟着重新排</b>，不会空着半幅宽却提前换行。" + NL +
                    "正文长过一屏时框里出滚动条，整份都在弹窗里翻得到；底部日志也始终是完整的那一份</li>" + NL +
                    "<li>各窗口（主窗口、本说明、「关于」、表结构预览、DDL 预览、进度框）的尺寸" + NL +
                    "按<b>客户区</b>给定，并在打开前按当前屏幕的可用区夹一次：" + NL +
                    "1024x768 的老机器上整窗加标题栏仍在屏内，底部的按钮不会被任务栏吃掉</li>" + NL +
                    "</ul>"
        ),
        new Section("faq", "常见问题",
                "<h2>常见问题</h2>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>现象</th><th>原因与处理</th></tr>" + NL +
                    "<tr><td>在 <b>Windows 7 / 8</b> 上双击 exe 打不开，弹\"找不到指定的程序/入口点\"</td>" + NL +
                    "<td>这个图形版要求 <b>64 位 Windows 10（1809 及以上）或 Windows 11</b>：界面用的 Qt 6 不支持" + NL +
                    "Win7/8.1，Python 3.9+ 与打包器也不支持，这是<b>缺依赖装不回来</b>的硬限制。" + NL +
                    "两种办法：① <b>导入不必在 Win7 本机做</b>——找任意一台 Win10/11 电脑跑本工具即可，" + NL +
                    "它只要<b>网络能连上目标 MySQL</b>，Excel 放共享目录一样能读；" + NL +
                    "② 只能在 Win7 上跑时，改用同源的<b>命令行版 <code>excel2mysql-cli.exe</code></b>" + NL +
                    "（解析、推断、建表、导入用的是同一套 core 逻辑，建出来的表结构一致），" + NL +
                    "用法见项目 README 的「Windows 7 命令行版」一节</td></tr>" + NL +
                    "<tr><td>提示 <code>1054 Unknown column</code></td>" + NL +
                    "<td>字段命名规则变更后列名与旧表不一致。勾「覆盖已存在表」重建，" + NL +
                    "或在表结构预览里改回旧列名</td></tr>" + NL +
                    "<tr><td>提示 <code>Row size too large</code></td>" + NL +
                    "<td>已内置处理：VARCHAR 行内预算不足时自动退为 TEXT。若仍出现，" + NL +
                    "手动把几个大文本列改成 TEXT</td></tr>" + NL +
                    "<tr><td>表没建但数据报主键冲突 <code>1062</code></td>" + NL +
                    "<td>表已存在且默认跳过建表，旧数据还在。勾「覆盖已存在表」或先 TRUNCATE</td></tr>" + NL +
                    "<tr><td>文件被拒绝，提示含合并单元格</td>" + NL +
                    "<td>回 Excel 取消所有合并区后重新添加。日志会列出具体位置</td></tr>" + NL +
                    "<tr><td>拖了 ZIP 但列表没变</td>" + NL +
                    "<td>看日志：可能是包内没有 Excel、是嵌套包（不递归展开），或包已加密</td></tr>" + NL +
                    "<tr><td>关掉程序后想重试 ZIP 里的失败文件</td>" + NL +
                    "<td>展开出的临时文件已随窗口关闭清理，需重新拖入压缩包</td></tr>" + NL +
                    "<tr><td>金额列被建成 INT 丢了小数</td>" + NL +
                    "<td>表头含金额语义的列已自动用 <code>DECIMAL(16,2)</code>；" + NL +
                    "若你的列名不含这些关键词，在表结构预览里手动改成 DECIMAL</td></tr>" + NL +
                    "<tr><td>双击 exe 没反应 / 杀毒软件拦截</td>" + NL +
                    "<td>未签名的 exe 可能被 SmartScreen 提示，选\"仍要运行\"即可。" + NL +
                    "onedir 版启动约 1-3 秒；exe 必须和 <code>_internal</code> 文件夹放在一起</td></tr>" + NL +
                    "<tr><td>导入一半想停</td><td>点<b>「停止」</b>，当前表处理完即中止，已提交的不会回滚</td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>事务语义</h3>" + NL +
                    "<p>每张表 / 每个 SQL 文件是一个独立事务：成功才提交，失败整体回滚并继续下一项。" + NL +
                    "但 MySQL 的 DDL（CREATE/DROP/ALTER）会隐式提交，无法回滚——这是数据库的限制，" + NL +
                    "日志里每次回滚都会提醒这一点。</p>"
        ),
    };

    private HelpDoc() {
    }

    public static Section[] sections() {
        return LIST.clone();
    }

    public static int count() {
        return LIST.length;
    }

    /** 目录标题，顺序与正文一致。 */
    public static String[] titles() {
        String[] out = new String[LIST.length];
        for (int i = 0; i < LIST.length; i++) {
            out[i] = LIST[i].title;
        }
        return out;
    }

    /** Qt 的 Rich Text 只吃 CSS 子集，Java 的 HTML 渲染器同理：属性写多了不报错但静默失效。 */
    public static final String STYLE = String.join(NL,
            "body { font-family: \"Microsoft YaHei\", \"Segoe UI\", sans-serif; font-size: 10.5pt;",
            "       color: #24292f; }",
            "h2 { font-size: 14pt; color: #0b5cad; margin-top: 18px; margin-bottom: 8px;",
            "     border-bottom: 2px solid #dfe3e8; }",
            "h3 { font-size: 11.5pt; color: #1a7f37; margin-top: 16px; margin-bottom: 6px; }",
            "p { margin-top: 6px; margin-bottom: 6px; }",
            "ul, ol { margin-top: 4px; margin-bottom: 6px; }",
            "li { margin-bottom: 3px; }",
            "code { background-color: #f0f2f4; color: #b3261e; font-family: Consolas, monospace; }",
            "pre { background-color: #f6f8fa; border: 1px solid #d8dee4; padding: 8px;",
            "      font-family: Consolas, monospace; font-size: 9.5pt; }",
            "table.t { border-collapse: collapse; margin-top: 8px; margin-bottom: 8px; }",
            "table.t th { background-color: #eef2f6; border: 1px solid #c9d1d9; padding: 4px 8px;",
            "             text-align: left; }",
            "table.t td { border: 1px solid #c9d1d9; padding: 4px 8px; }",
            "p.tip { background-color: #eef7ee; border-left: 4px solid #1a7f37; padding: 6px 10px; }",
            "p.warn { background-color: #fdf3f2; border-left: 4px solid #c62828; padding: 6px 10px; }"
    );

    public static String html() {
        StringBuilder sb = new StringBuilder("<html><head><style>");
        sb.append(STYLE).append("</style></head><body>");
        for (int i = 0; i < LIST.length; i++) {
            if (i > 0) {
                sb.append("<hr>");
            }
            String body = LIST[i].body;
            int h2 = body.indexOf("<h2");
            sb.append("<a name=\"").append(LIST[i].anchor).append("\"></a>");
            if (h2 >= 0) {
                // 空的 a 标签渲染不出元素，所以把 id 挂到真有内容的 h2 上，
                // 目录跳转才有落点；文字本身一个字节没改。
                sb.append(body, 0, h2 + 3).append(" id=\"").append(LIST[i].anchor)
                        .append("\"").append(body.substring(h2 + 3));
            } else {
                sb.append(body);
            }
        }
        return sb.append("</body></html>").toString();
    }
}
