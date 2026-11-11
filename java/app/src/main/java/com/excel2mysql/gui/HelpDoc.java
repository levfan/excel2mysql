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
                    "<p>本工具把一批 <b>Excel 表</b>（或含 Excel 的 <b>ZIP 压缩包</b>）批量导入 MySQL：" + NL +
                    "推断表结构、生成建表语句、写入数据，导入前可逐列预览与修改。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>步骤</th><th>操作</th></tr>" + NL +
                    "<tr><td>1</td><td>打开界面。程序同级有 <code>conn.ini</code>、或设了 <code>E2M_*</code>" + NL +
                    "环境变量，且其中写了 <code>database</code> 时，启动后自动连接并选中该库，无需先点按钮</td></tr>" + NL +
                    "<tr><td>2</td><td>没有配置、自动连接失败或需要换库时：点<b>「设置数据源信息」</b>展开连接区，" + NL +
                    "填主机、端口、用户名、密码，点<b>「连接并加载库」</b>，在「目标库」下拉里选库。" + NL +
                    "点下去会挂出一张<b>「正在连接并加载库…」</b>的进度框：这一趟连得上只有几十毫秒，" + NL +
                    "连不上要等满五秒连接超时，框里写明在连哪台、已经过了几秒，随时可点「取消」；" + NL +
                    "进度框不挡窗口，挂着的时候另外两页照常能点，跑完自己收起。" + NL +
                    "「目标库」在清单加载出来之前是灰的（库列表要连上服务器才拿得到），不是不给换：" + NL +
                    "加载成功后这里才可点选；改过主机、端口、用户名或口令，那一行会提示清单来自上一次连接，" + NL +
                    "需重新加载。选定后连接区自动收起，上方只留一条<b>「● 目标库 xxx」</b>，主机端口用户名收在鼠标悬停里</td></tr>" + NL +
                    "<tr><td>3</td><td>切到<b>「Excel 导入」</b>页签，把文件拖进虚线区，或点击虚线区选文件</td></tr>" + NL +
                    "<tr><td>4</td><td>需要统一命名时，填<b>表名前缀</b>后点<b>「批量应用前缀」</b>（追加一段）" + NL +
                    "或<b>「替换首个前缀」</b>（换掉已有首段，见「表名前缀：加与换」）</td></tr>" + NL +
                    "<tr><td>5</td><td>点<b>「生成表结构预览」</b>核对与修改字段名、数据类型、主键；" + NL +
                    "改<b>表注释</b>则双击清单里的「表注释」单元格</td></tr>" + NL +
                    "<tr><td>6</td><td>点<b>「预览 DDL」</b>确认建表语句，再点<b>「开始导入」</b></td></tr>" + NL +
                    "<tr><td>7</td><td>在底部日志查看逐表结果</td></tr>" + NL +
                    "</table>" + NL +
                    "<p class=\"tip\"><b>启动即自动连接</b>的条件：在<b>程序所在目录</b>放一份 <code>conn.ini</code>" + NL +
                    "（Java 交付包已带一份；Python 图形版可从仓库 <code>assets/conn.ini</code> 复制后修改），" + NL +
                    "或设好 <code>E2M_*</code> 环境变量，并且<b>写了 <code>database</code></b>。" + NL +
                    "界面显示后在后台连接，服务器上有这个库就直接选中；没有则留一句「跳过」等待手工选择。" + NL +
                    "自动连接不阻塞界面，失败只写日志、不弹窗，顶部状态栏保持红色，可点" + NL +
                    "<b>「设置数据源信息」</b>手工重试。配置里没写 <code>database</code> 时只预填表单、" + NL +
                    "不自动连接（随包空模板即此种），两份配置都没有时同样不连，只提示如何预填。" + NL +
                    "优先级为<b>环境变量 E2M_HOST / E2M_PORT / E2M_USER / E2M_PASS / E2M_DB" + NL +
                    "&gt; conn.ini &gt; 内置默认值</b>，界面手工填写的一项最即时。" + NL +
                    "启动日志写明本次配置的来源：读到了给出路径，文件写坏了给出「找到了但读不了」并退回默认值。" + NL +
                    "口令写入配置文件需注意文件保管，不落盘可用环境变量传入。</p>" + NL +
                    "<p class=\"tip\">最上方的<b>连接状态栏</b>只报当前库：绿色显示 <code>● 目标库 库名</code>，" + NL +
                    "主机、端口、用户名收在鼠标悬停里；未选库时为红色告警。当前库只在这一处说明，" + NL +
                    "三个页签内不再各写一条「目标库：…」；未选库也只有状态栏变红，同一提示不重复两遍。" + NL +
                    "状态栏随切库、改主机端口刷新，不会停在上一个库。页签顶部各有一句加粗介绍，" + NL +
                    "说明该页用途：Excel 页<b>中英表头建模，回写库</b>、「SQL 脚本改名」页<b>解析建表语句，回写库</b>、" + NL +
                    "「SQL 执行器」页<b>执行脚本与临时语句</b>，三句用同一块强调底板，选库与否都在。</p>"
        ),
        new Section("lines", "三条线与选型",
                "<h2>三条线与选型</h2>" + NL +
                    "<p>同一工具有三份可交付程序：解析、类型推断与建表逻辑同源，" + NL +
                    "区别在于有没有界面、能跑在什么系统上、以及命令行选项集。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>交付线</th><th>入口</th><th>支持系统</th><th>界面</th><th>命令行</th></tr>" + NL +
                    "<tr><td>图形版（Python）</td><td><code>excel2mysql.exe</code></td>" + NL +
                    "<td>64 位 Windows 10 1809+ / Windows 11</td><td>有</td><td>无</td></tr>" + NL +
                    "<tr><td>命令行版（Python，Windows 7 专用）</td><td><code>excel2mysql-cli.exe</code></td>" + NL +
                    "<td>Windows 7 SP1 及以上，且系统 UCRT 补丁与系统文件完整</td><td>无</td><td>有</td></tr>" + NL +
                    "<tr><td>Java 版</td><td><code>启动.vbs</code>（双击开界面，零黑框）、<code>启动.bat</code> 或" + NL +
                    "<code>java -jar excel2mysql-*.jar</code></td>" + NL +
                    "<td>64 位 Windows 7 及以上；包内自带 Java 运行时，目标机器无需安装</td>" + NL +
                    "<td>有</td><td>有</td></tr>" + NL +
                    "</table>" + NL +
                    "" + NL +
                    "<h3>每个包怎么启动</h3>" + NL +
                    "<ul>" + NL +
                    "<li>图形版 <code>excel2mysql.exe</code>：双击打开界面，无命令行模式。</li>" + NL +
                    "<li>命令行版 <code>excel2mysql-cli.exe</code>：控制台程序，在 cmd 里带参数运行" + NL +
                    "（如 <code>excel2mysql-cli.exe --check</code>）。直接双击会打印一句「没有输入」的用法后退出，" + NL +
                    "窗口一闪即灭——不带参数即没有可执行的工作，属预期行为；它也没有界面模式。</li>" + NL +
                    "<li>Java 版：界面与命令行是同一个 jar，按首个参数区分。要界面就双击" + NL +
                    "<code>启动.vbs</code>——它只做一件事：用包内 <code>jre\\bin\\javaw.exe</code> 跑" + NL +
                    "<code>-jar excel2mysql-*.jar --gui</code>。<code>javaw</code> 不挂控制台，所以双击之后" + NL +
                    "不会留一个 cmd 黑框；<code>启动.bat</code> 是同一件事的批处理写法（同样走 javaw），" + NL +
                    "在 <code>.vbs</code> 被组策略挡掉的机器上用它。要把文件一起带上，把 Excel/ZIP 拖到" + NL +
                    "这两个入口的图标上。包没解全时它们会弹窗说清缺哪个文件；还想看到 Java 的原始报错与退出码，" + NL +
                    "用 <code>启动-调试.bat</code>，它故意留着控制台。要命令行就在 cmd 里跑同一条命令但不给" + NL +
                    "<code>--gui</code>，例如" + NL +
                    "<code>jre\\bin\\java.exe -jar excel2mysql-5.4.1.jar --check</code>。" + NL +
                    "双击 jar 文件本身没有任何反应。</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>界面功能：图形版与 Java 版等价</h3>" + NL +
                    "<p>两边都有：拖文件、点击虚线区选文件、复制文件后按 <code>Ctrl+V</code> 添加、" + NL +
                    "ZIP 展开、<code>.xlsx</code> 与 <code>.xls</code>、拼音表名、「批量应用前缀」与「替换首个前缀」、" + NL +
                    "「生成表结构预览」（可改字段名、数据类型与主键）、「预览 DDL」、" + NL +
                    "「SQL 脚本改名」页的保存与执行、「SQL 执行器」页的脚本清单与手写执行、" + NL +
                    "「生成标准excel文件」与「生成简易excel文件」、耗时任务的进度框，以及本《使用说明》" + NL +
                    "（右上角「?」或 F1）。这一等价由机器判定：两条线的窗口控件、文案、按钮启用条件、" + NL +
                    "弹窗文字、日志与写出的文件各自枚举成清单后逐键比对，规模与最近一次结果见" + NL +
                    "包内 <code>README.md</code> 的「质量闸门」一节。</p>" + NL +
                    "" + NL +
                    "<h3>界面上的说明文字抄得走</h3>" + NL +
                    "<p>界面上那些只说明情况的静态文字——三页顶部那句定位说明、连接信息里每一行的名称、" + NL +
                    "「命名规则：」「表名前缀：」「SQL 文本」「执行结果」以及顶部那条当前库状态——" + NL +
                    "都可以按住鼠标拖选一段，再按 <code>Ctrl+C</code> 抄出去核对（要整句就先按" + NL +
                    "<code>Ctrl+A</code> 全选）。" + NL +
                    "这一族文字不占 Tab 位：键盘在控件间走的时候不会多停几站，方向键翻表、" + NL +
                    "<code>Ctrl+V</code> 添加文件这些原有操作一点都不受影响。</p>" + NL +
                    "" + NL +
                    "<h3>两条 Python 线的一个硬限制：口令不能用中文</h3>" + NL +
                    "<p>pymysql 在握手前就把口令按 latin-1 编码，因此图形版与 Windows 7 命令行版只能使用" + NL +
                    "latin-1 范围内（实际为 ASCII 与欧洲字母）的 MySQL 口令；口令含中文时连不上库，" + NL +
                    "报出的是编码错误而非数据库错误，日志会附一句「多半出在口令上」。" + NL +
                    "同一口令在 Java 版可用（JDBC 支持 Unicode 口令）——现场无法改口令时选 Java 版。</p>" + NL +
                    "" + NL +
                    "<h3>选型</h3>" + NL +
                    "<ul>" + NL +
                    "<li>Windows 10/11，需要改字段名与主键：图形版。</li>" + NL +
                    "<li>只有 Windows 7 且无法确认打过补丁：Java 版，它自带运行时。</li>" + NL +
                    "<li>要放进批处理或定时任务：两条命令行线都可以，但选项集不同，" + NL +
                    "见下一段与包内 <code>README.md</code>（三个包都原样携带它）。</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>命令行选项并不相同</h3>" + NL +
                    "<ul>" + NL +
                    "<li>Python 命令行版：<code>.sql</code> 默认只登记不执行，加 <code>--run-sql</code>" + NL +
                    "才写入库里。连接信息可逐项给出（<code>--host --port --user --password -d</code>），" + NL +
                    "另有 <code>--initials</code>（取拼音首字母）、<code>--upper</code>（转大写）、" + NL +
                    "<code>--swap-prefix</code>、<code>--drop-if-exists</code>、<code>--batch</code>、" + NL +
                    "<code>--log</code>、<code>--quiet</code>、<code>--no-recursive</code>、" + NL +
                    "<code>--keep-temp</code>。</li>" + NL +
                    "<li>Java 版：动作由路径类型决定——给 <code>.sql</code> 就执行脚本，给目录两类都干，" + NL +
                    "用 <code>--no-sql</code> / <code>--no-excel</code> 各自排除，没有 <code>--run-sql</code>。" + NL +
                    "连接信息走同级 <code>conn.ini</code> 或环境变量" + NL +
                    "<code>E2M_HOST / E2M_PORT / E2M_USER / E2M_PASS / E2M_DB</code>，" + NL +
                    "选项为 <code>--dry-run --check --prefix --initials --upper -c --debug --gui --version</code>。</li>" + NL +
                    "<li>「生成标准excel文件」与「生成简易excel文件」只有界面上有，两条命令行线都没有对应动作。</li>" + NL +
                    "</ul>"
        ),
        new Section("files", "支持哪些文件",
                "<h2>支持哪些文件</h2>" + NL +
                    "<p>「Excel 导入」页的虚线区是添加文件的唯一入口：点击弹文件选择器（可多选），" + NL +
                    "也可以把文件或压缩包直接拖入，还可以在资源管理器里复制若干文件后回到本程序按" + NL +
                    "<code>Ctrl+V</code> 添加。页面上没有单独的「添加文件」按钮。</p>" + NL +
                    "<p>选择器里不用一层层点：敲一段完整路径按回车就能跳过去——Java 包里那格「查找范围」" + NL +
                    "可以直接输入，也能下拉选历史目录；敲的是文件就进它所在目录并把这个文件带上，" + NL +
                    "路径不存在时会给一句提示、停在原处。系统自带的选择器则在「文件名」那一格敲路径。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>输入</th><th>行为</th></tr>" + NL +
                    "<tr><td><code>.xlsx</code></td><td>正常导入</td></tr>" + NL +
                    "<tr><td><code>.xls</code>（97-2003 老格式）</td><td>由 <code>xlrd</code> 解析，正常导入</td></tr>" + NL +
                    "<tr><td><code>.zip</code> 压缩包</td><td>自动展开包内 Excel，逐个加入待导入列表</td></tr>" + NL +
                    "<tr><td>其他扩展名</td><td>忽略，日志说明忽略了几个</td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>复制文件后按 Ctrl+V 添加</h3>" + NL +
                    "<ul>" + NL +
                    "<li><b>先判剪贴板，再判焦点</b>：剪贴板里是文件就一律按「添加文件」处理，" + NL +
                    "鼠标与焦点在哪颗控件都算——正在输入的文本框里按 <code>Ctrl+V</code>" + NL +
                    "也先把文件加进清单；剪贴板是纯文本时才交回该输入框自身粘贴，粘贴主机名、SQL 片段照旧</li>" + NL +
                    "<li><b>粘贴的目标是当前这一页</b>，三页的虚线区都写着这句快捷键提示：第一页进「待导入文件」清单、" + NL +
                    "第二页进待改名的脚本清单、第三页进脚本执行队列，其他页的清单不受影响</li>" + NL +
                    "<li>剪贴板里是 <code>.xlsx</code> / <code>.xls</code> / <code>.zip</code> 就等同于拖进本页虚线区，" + NL +
                    "走同一条解析路径；日志按类型回执" + NL +
                    "<code>[粘贴] 已通过粘贴添加 N 个文件，其中 X个 zip文件，Y个 xlsx文件，Z个 xls文件</code></li>" + NL +
                    "<li>第二、三页只收 <code>.sql</code>，入口与拖拽同一个，回执也与拖入逐字一致" + NL +
                    "（混着的其他文件点名 <code>[跳过] 不是 SQL 脚本，未读入：…</code>；" + NL +
                    "一个 <code>.sql</code> 都没有时写" + NL +
                    "<code>[提示] 拖入或粘贴的不是 SQL 脚本文件，已忽略。</code>）。" + NL +
                    "剪贴板不是文件、或全是不可导入的扩展名时，沿用拖拽那句" + NL +
                    "<code>[提示] 拖入或粘贴的不是 Excel 或 ZIP 文件，已忽略。</code>，不另写一套说法</li>" + NL +
                    "<li>本页已有任务在跑时先说明在等待哪个任务，「已通过粘贴添加」不会先说出口</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>ZIP 压缩包</h3>" + NL +
                    "<ul>" + NL +
                    "<li><b>递归子目录</b>：包内 <code>北京/水库.xlsx</code> 这类嵌套路径同样收进来，" + NL +
                    "解压时保留目录结构，不同目录下的同名文件不会互相覆盖</li>" + NL +
                    "<li><b>表名从包根一路拼到文件名</b>：包内路径的每一层目录都进表名，目录之间、" + NL +
                    "目录与文件名之间用下划线连接——<code>北京/水库/水库基础信息（dim_reservoir_info）.xlsx</code>" + NL +
                    "的表名是 <code>bei_jing_shui_ku_dim_reservoir_info</code>，表注释是" + NL +
                    "<code>北京/水库/水库基础信息</code>。不同目录下的同名文件因此天然是两个表名，" + NL +
                    "不用手工改也不会塌成 <code>_2</code>；<b>散装 Excel 不受影响</b>，表名仍只看文件名。" + NL +
                    "勾了「取中文首字母」或整条超过 64 字符时，也是每层目录各自压写、<b>目录之间照样是下划线</b>" + NL +
                    "（<code>财务部/2026年/人员信息.xlsx</code> → <code>cwb_2026n_ryxx</code>），不会粘成一长串；" + NL +
                    "实在装不下才从最外层开始丢目录（包根那层通常只是压缩包名），文件名本身不动。" + NL +
                    "目录名里的连续中文数字同样先读成阿拉伯数字：<code>二〇二五年/水库信息.xlsx</code> →" + NL +
                    "<code>t_2025_nian_shui_ku_xin_xi</code>，不会按字面拆成一串拼音</li>" + NL +
                    "<li><b>不递归嵌套 zip</b>：包内的 <code>.zip</code> 跳过并在日志计数，" + NL +
                    "需要导入就把内层包单独拖进来</li>" + NL +
                    "<li><b>非 Excel 文件忽略</b>，<code>~$xxx.xlsx</code> 这类 Office 临时锁文件也跳过</li>" + NL +
                    "<li>展开出的文件放在系统临时目录，<b>关闭窗口时自动清理</b>；" + NL +
                    "因此关掉程序后，来自 ZIP 的失败文件需要重新拖入压缩包才能重试</li>" + NL +
                    "<li>中文文件名按 GBK 兜底解码：Windows 自带「发送到压缩文件夹」存中文名用 GBK 且不打" + NL +
                    "UTF-8 标记，工具会正确还原，不会变成乱码表名</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>顶部两行合并表头：自动压成一行</h3>" + NL +
                    "<p>第一行是分组名、第二行是子项时无需手工改成一行：按列把上下两行拼成「父-子」表头，" + NL +
                    "再照常推断类型。例如第一行 <code>技术公司</code> 横跨 4 列、第二行为" + NL +
                    "<code>小计 / 高层 / 中层 / 普通员工</code>，字段名即 <code>技术公司-小计</code>、" + NL +
                    "<code>技术公司-高层</code>…；左侧纵向合并两行的 <code>序号</code> 只留一份，" + NL +
                    "不会变成 <code>序号-序号</code>。日志在 <code>[已解析]</code> 行写「合并表头按 2 行展开」。" + NL +
                    "放行条件（只认这种标准两行表头）：合并区只在第一个 Sheet、每一处都从第 1 行开始，" + NL +
                    "且只能是跨两行的单列纵向合并（如 <code>A1:A2</code>）或只横跨第 1 行的多列合并" + NL +
                    "（如 <code>J1:M1</code>），并至少有一处跨两行。</p>" + NL +
                    "" + NL +
                    "<h3>其余合并单元格：直接拒绝导入</h3>" + NL +
                    "<p>数据行里的合并会静默丢值，表头形状对不上时字段名与列会错位。不符合上述条件的文件" + NL +
                    "<b>一律不导入、也不进入待导入列表</b>，日志标红列出具体位置，请在 Excel 中取消合并后重新添加：</p>" + NL +
                    "<pre>[合并单元格] 两页都合并.xlsx 含 3 处合并区，已拒绝导入，未进入待导入列表" + NL +
                    "    检测到合并单元格：「字段定义」1 处（A1:G1）；「数据」2 处（A1:B1、A2:B2）</pre>" + NL +
                    "<p>覆盖情形：数据区有合并、第二个 Sheet 有合并、单行表头上的合并、合并区跨出表头块、" + NL +
                    "三行表头、第 2 行里的子合并、2x2 合并块，以及两个 Sheet（元数据页 + 数据页）任一页有合并区。" + NL +
                    "拒绝只针对该文件，不中断整批，同批其他文件照常解析；ZIP 内只有某个文件有合并区时，" + NL +
                    "只拒绝那一个，同包其他文件正常导入。</p>"
        ),
        new Section("progress", "进度框与耗时",
                "<h2>进度框与耗时</h2>" + NL +
                    "<p>几十万行的大表解析、导入、读写脚本都很耗时，这些操作会弹出进度框：" + NL +
                    "实际工作都在子线程，界面始终能刷新、能点、能取消。进度框是非模态的，不遮挡主窗口——" + NL +
                    "一个页签在跑时，另外两个页签仍可正常使用。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>操作</th><th>进度计项</th></tr>" + NL +
                    "<tr><td>点击虚线区选文件 / 拖入文件 / 复制文件后按 <code>Ctrl+V</code></td>" + NL +
                    "<td>每个压缩包 1 项；包内每个 Excel 解出占 1 项；每个待解析 Excel 按 5 个阶段各占 1 项" + NL +
                    "（合并检测 / 读取工作簿 / 识别格式 / 推断类型 / 选主键）。当前项显示到" + NL +
                    "「文件名：读取工作簿」这一级，单个几十万行的大表解析途中进度条也在推进</td></tr>" + NL +
                    "<tr><td>开始导入</td><td>每张表 1 项 + 每个数据行 1 项</td></tr>" + NL +
                    "<tr><td>批量应用前缀 / 替换首个前缀（Excel 页）</td>" + NL +
                    "<td>每张表 1 项 + 重名去重 1 项</td></tr>" + NL +
                    "<tr><td>拖拽区 / 点击选文件 / 批量应用前缀 / 替换首个前缀（SQL 页）</td>" + NL +
                    "<td>每个文件 1 项（读盘、扫描、改写都在子线程）</td></tr>" + NL +
                    "<tr><td>保存修改后ddl脚本</td><td>每个文件 1 项</td></tr>" + NL +
                    "<tr><td>执行修改后ddl脚本到当前库</td><td>每条语句 1 项，按块推进（单个文件最多约 100 次刷新，" + NL +
                    "避免海量刷新拖慢界面）</td></tr>" + NL +
                    "<tr><td>「SQL 执行器」执行编辑区里那段</td><td>每条语句 1 项，逐条推进，" + NL +
                    "<b>每句开始之前查一次取消</b></td></tr>" + NL +
                    "<tr><td>「SQL 执行器」批量执行脚本文件 / 行内【执行】</td>" + NL +
                    "<td>轮到哪个文件才读盘切句，再按语句 1 项推进（每个文件最多约 100 次刷新）；" + NL +
                    "文件之间查一次取消，取消后剩余文件不再执行。脚本逐个读取，总语句数边读边涨，" + NL +
                    "进度按「已完成项数不回退」处理；识别为整库 dump 的文件同样按语句 1 项推进，" + NL +
                    "跳过的删库语句在文件收尾时补齐</td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>任务在跑时点了别的按钮</h3>" + NL +
                    "<p>拦下这一步时同时弹窗和写日志，说明在等待哪个任务：任务名、百分比、已完成项数、" + NL +
                    "已跑时长、当前项，以及两个任务共用的是同一个资源位，形如</p>" + NL +
                    "<pre>已有任务在跑：正在执行 SQL… 37%（45/121）· 已 12 秒 · 第 45/121 条。这一步没执行——它和那个任务共用「SQL 执行器页的执行结果区（编辑区 SQL 与脚本清单共用）」，等它跑完或点进度框「取消」。</pre>" + NL +
                    "<p>互斥按占用的资源判定，不按页签判定：同一页的两个任务会共用同一份列表或同一段编辑区，" + NL +
                    "因此后点击的那一步被拦下。「Excel 导入」页与「SQL 脚本改名」页的" + NL +
                    "「生成标准/简易excel文件」各写独立目录（前者是 <code>meta_data_file_年月日</code>，" + NL +
                    "后者是 <code>sql_meta_data_file_年月日</code>），同名表不会互相覆盖，两颗按钮无需互相等待。" + NL +
                    "除此之外各页互不影响：SQL 页在读几十兆脚本时，Excel 页可以继续解析、执行器可以继续执行语句，" + NL +
                    "各自弹出各自的进度框、各自回报进度。两个任务同时跑时第二个框向右下方偏移一档" + NL +
                    "（竖直偏移量大于框高），两颗「取消」都点得到，不会完全重叠。</p>" + NL +
                    "<p>任务结束时弹出的结果框点一下确定即退出，不再占用本页：弹窗还开着时该任务已不算在跑，" + NL +
                    "另一个在跑的任务也不受影响。两个任务并行时，各自的日志写回各自那一页，不串到别的页。</p>" + NL +
                    "<h3>取消的行为</h3>" + NL +
                    "<ul>" + NL +
                    "<li>取消在当前这一项做完时生效，不会中途硬切留下半截数据</li>" + NL +
                    "<li>解析中途取消：已解析出的表照常进入待导入列表，弹窗首行标注" + NL +
                    "<code>已取消，只完成了部分解析</code></li>" + NL +
                    "<li>「SQL 脚本改名」页执行中途取消：该文件的事务回滚不提交（DDL 隐式提交的部分除外），" + NL +
                    "后续文件不再执行</li>" + NL +
                    "<li>「SQL 执行器」页执行编辑区 SQL 中途取消：下一句不再执行，整批回滚不提交，" + NL +
                    "日志写 <code>已取消 | 第 46/121 条未执行，本批未提交（已回滚）</code>；" + NL +
                    "批量执行脚本文件时取消按文件类型分两种结果，见「SQL 执行器页」一节</li>" + NL +
                    "<li>导入中途取消：等同底部的「停止」——当前表处理完即中止，已提交的表不回滚</li>" + NL +
                    "<li>进度框上的「取消」只管它自己那个任务；底部那颗独立的「停止」只管 Excel 页的任务</li>" + NL +
                    "<li>任务结束后不留残留：导入完成（或失败、被中止）后进度框随即关闭，页面上不会留一条" + NL +
                    "停在 100% 的进度条；「清空待上传文件」清空清单与预览，结果本身由弹窗与日志交代</li>" + NL +
                    "</ul>" + NL +
                    "<p class=\"tip\">进度框标题栏的 X 不等于关闭任务：任务在跑时点 X 等同点「取消」，" + NL +
                    "否则会出现「框没了、任务还在后台跑、之后每次点本页按钮都只说请稍等」这种无法排查的状态。" + NL +
                    "工作函数整体抛异常时进度框照样关闭，并在日志里以红色打 <code>[任务异常]</code>。</p>"
        ),
        new Section("excel", "两类 Excel 格式",
                "<h2>两类 Excel 格式</h2>" + NL +
                    "" + NL +
                    "<h3>第 1 类：普通数据表（单个数据 Sheet）</h3>" + NL +
                    "<p>表名与表注释来自<b>文件名</b>，字段名与类型<b>从数据推断</b>。</p>" + NL +
                    "<ul>" + NL +
                    "<li>文件名形如 <code>北京市水务局-水库基础信息（dim_reservoir_info）.xlsx</code>：" + NL +
                    "<b>表名 = 括号内英文</b> <code>dim_reservoir_info</code>，" + NL +
                    "<b>表注释 = 括号前中文</b>。中英文括号均可，括号内大写统一转小写</li>" + NL +
                    "<li>括号内必须是<b>可直接当表名用的英文标识符</b>（字母或下划线开头、含字母、" + NL +
                    "只由字母数字下划线组成）才采用，<b>版本号除外</b>。下列情况一律回退为「整个文件名转拼音」：" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>文件名</th><th>表名</th><th>原因</th></tr>" + NL +
                    "<tr><td><code>人员信息表（人事处人员）</code></td>" + NL +
                    "<td><code>ren_yuan_xin_xi_biao_ren_shi_chu_ren_yuan</code></td>" + NL +
                    "<td>括号内是中文 → 括号内外<b>所有中文一起</b>转拼音，不生成汉字表名</td></tr>" + NL +
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
                    "<tr><td><code>装备实力2.0</code></td><td><code>zhuang_bei_shi_li_v2</code></td>" + NL +
                    "<td>名字里带小数的版本号（<code>2.0</code> / <code>V2.0</code> / <code>v3.5</code>）" + NL +
                    "整体写成 <code>v2</code>（<code>v3.5</code> 写成 <code>v3_5</code>）：" + NL +
                    "拼进名字就是 <code>_v2_</code> 这种形式，不会拆成 <code>2_0</code> 两段。" + NL +
                    "<b>版本号不会被判成非法字符</b>，拖进来不弹「只能用字母、数字、下划线」的警告</td></tr>" + NL +
                    "</table>" + NL +
                    "回退时<b>表注释保留完整文件名</b>（含括号内容），因此" + NL +
                    "<code>人员信息表（人事处）</code> 与 <code>人员信息表（财务部）</code>" + NL +
                    "得到两个不同且可辨识的表名，而不是同名加 <code>_2</code></li>" + NL +
                    "<li>没有括号时：<b>表名 = 文件名汉字转拼音</b>（音节间下划线分隔），表注释 = 文件名。" + NL +
                    "<b>超长处理与字段名同一套规则</b>：全拼不超过 64 字符用全拼，超过则整名降级为各音节首字母" + NL +
                    "（数字与英文音节整段保留），例如" + NL +
                    "<code>北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表</code> →" + NL +
                    "<code>bjshdqswjxsdxsksngcjcxxhzb</code>。表名与字段名共用同一套切分与降级逻辑，" + NL +
                    "只是上限不同（表名 64 / 字段名 30）</li>" + NL +
                    "<li><b>连续中文数字按阿拉伯数字读</b>：<code>二零二五</code>、<code>二〇二五</code> 都得到" + NL +
                    "<code>2025</code>，<code>第十二批</code> 得到 <code>第12批</code>，<code>二十五</code> 得到 <code>25</code>、" + NL +
                    "<code>一百零八</code> 得到 <code>108</code>、<code>两万</code> 得到 <code>20000</code>，再按上面的规则转拼音。" + NL +
                    "只有连续两个及以上才按数词读，<code>统一</code>、<code>第一</code>、<code>万一</code>、<code>千万</code>" + NL +
                    "里的数字字仍是词的一部分，原样转拼音；表名、字段名与 ZIP 目录名走同一套读法</li>" + NL +
                    "<li><b>表头里的符号不进字段名</b>：<code>*</code> / <code>＊</code> 这类必填标记、全角空格、" + NL +
                    "<code>【】〈〉《》「」〖〗</code> 等括号符本身都会去掉；<b>成对括号包住的那一段整段也不进名字</b>——" + NL +
                    "表单里 <code>发布日期（yyyy-MM-dd）</code>、<code>金额（元）</code>、" + NL +
                    "<code>是否已婚【是/否】</code>、<code>★（必填）联系电话</code> 的括号内容是写给填表人看的口径，" + NL +
                    "于是分别取 <code>fa_bu_ri_qi</code>、<code>jin_e</code>、<code>shi_fou_yi_hun</code>、" + NL +
                    "<code>lian_xi_dian_hua</code>。整格只有括号（<code>（备注说明）</code>）时不剥空，仍用里面那串字；" + NL +
                    "括号<b>没配右半边</b>也算注释，<code>发布日期（yyyy-MM-dd</code> 从左括号起整段丢弃。" + NL +
                    "<b>原始表头完整保留在字段注释里</b>，口径不丢</li>" + NL +
                    "<li>日期时间<b>格式掩码串</b>即使不带括号也不进名字：<code>发布日期：yyyy-MM-dd</code>、" + NL +
                    "<code>发布日期 yyyy-MM-dd</code>、<code>发布日期/yyyy/MM/dd</code>、" + NL +
                    "<code>填表日期 yyyymmdd</code> 都取 <code>fa_bu_ri_qi</code> / <code>tian_biao_ri_qi</code>。" + NL +
                    "判定只认「<code>y/m/d/h/s</code> 连写两个以上、两头不贴字母数字」的串，" + NL +
                    "因此 <code>1.0mm</code> 里的 <code>mm</code>、纯英文表头里的 <code>MM</code>、" + NL +
                    "真日期 <code>2020-01-01</code> 都不会被吃掉；表头不含汉字时整条不参与该判断。" + NL +
                    "剥完之后同名靠 <code>_2</code> / <code>_3</code> 去重，所以同一张表里" + NL +
                    "<code>发布日期（yyyy-MM-dd）</code> 与 <code>发布日期</code> 是<b>两列</b>" + NL +
                    "（<code>fa_bu_ri_qi</code> 与 <code>fa_bu_ri_qi_2</code>），不会合并；" + NL +
                    "需要可辨识就在结构预览里改字段名</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>空表与空列的判定</h3>" + NL +
                    "<ul>" + NL +
                    "<li><b>整列没值</b>的字段怎么处理，看<b>表头写没写字</b>：写了字（含 <code>*标准名称</code>" + NL +
                    "这类必填标记）就<b>照旧保留</b>并在日志点名（<code>整列为空的字段照旧保留：*填报人…</code>）。" + NL +
                    "采集模板一行数据都没填，字段是设计好的表结构，按「整列没值就删」处理会把表缩小，" + NL +
                    "空模板甚至会被判成「没解析到字段」而整体拒收。表头空着、整列又没值的" + NL +
                    "（Excel 尾部格式化拖出来的 <code>Unnamed</code> 列）才剔除；全空行照旧剔除</li>" + NL +
                    "<li>整列为空的字段按表头语义定类型，规则见「字段命名与类型推断」一节</li>" + NL +
                    "<li><b>只有表头、没有任何数据行</b>的表照常解析成功：建出来是空表，" + NL +
                    "<code>[已解析]</code> 记录下面的缩进说明行写 <code>没有数据行，建出来是空表</code>，" + NL +
                    "并且<b>不自动选主键</b>（无数据无法判断唯一与非空）。需要主键时可在" + NL +
                    "「生成表结构预览」里手工勾选：空集天然满足非空与唯一，字段类型合规" + NL +
                    "（INT/BIGINT 或长度 ≤255 的 VARCHAR/CHAR）即可保存</li>" + NL +
                    "<li><b>表头整行一个格都没写字</b>也照样导入：文件里记着这张表有几列，就按那个列数建表，" + NL +
                    "字段名用兜底写法 <code>col</code>、<code>col_2</code>、<code>col_3</code>，列注释为空、" + NL +
                    "没有数据可插，说明行点名兜底结果" + NL +
                    "（<code>表头整行没写字，按列宽兜底成：col、col_2、col_3</code>）。" + NL +
                    "这种表看不出业务含义，需在结构预览里改名或补表头后再导。" + NL +
                    "<b>连一个格子都没有</b>的表仍拒绝：那种表建出来是 <code>CREATE TABLE x ( )</code>，" + NL +
                    "MySQL 直接报语法错误</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>第 2 类：结构定义表（元数据 Sheet + 数据 Sheet）</h3>" + NL +
                    "<p>字段<b>以定义为准，不再推断</b>。任一 Sheet 的表头含「字段名、数据类型」及以下任一列，" + NL +
                    "即识别为元数据页（排在第几个 Sheet 都能识别）：</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>序号</th><th>字段名(英文)</th><th>字段注释(中文)</th><th>数据类型</th>" + NL +
                    "<th>长度</th><th>是否主键</th><th>是否可为空</th></tr>" + NL +
                    "<tr><td>1</td><td>id</td><td>主键ID</td><td>INT</td><td></td><td>是</td><td>否</td></tr>" + NL +
                    "<tr><td>2</td><td>amount</td><td>金额</td><td>DECIMAL</td><td>10,2</td><td></td><td></td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li><code>长度</code> 填 <code>20</code> 或 <code>10,2</code>（→ <code>DECIMAL(10,2)</code>）；" + NL +
                    "类型列也可直接写 <code>VARCHAR(50)</code>，中文括号与逗号均可识别</li>" + NL +
                    "<li><code>是否主键=是</code> → 主键（支持多字段组合）；" + NL +
                    "<code>是否可为空=否</code> → <code>NOT NULL</code>，否则 <code>DEFAULT NULL</code></li>" + NL +
                    "<li>数据 Sheet 按字段名对齐（大小写不敏感）：多余列忽略并在日志提示，缺失列补 NULL</li>" + NL +
                    "<li>只有定义没有数据时建空表</li>" + NL +
                    "</ul>"
        ),
        new Section("export", "导出标准/简易 Excel",
                "<h2>导出标准/简易 Excel 交付件</h2>" + NL +
                    "<p>「Excel 导入」页底部动作条「开始导入」右侧的两颗按钮，把<b>当前待导入列表里已解析好的表</b>" + NL +
                    "再写出一份 Excel 交付件。两者都不连库、不改动列表，产物落在<b>可执行程序同级目录</b>，" + NL +
                    "每次按日期建一个子目录。「SQL 脚本改名」页的同名两颗按钮共用这一套命名，" + NL +
                    "只在目录名前加 <code>sql_</code>，因此两页各自生成时写进不同目录，同名表不会互相覆盖，" + NL +
                    "两颗按钮也可以同时跑。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>按钮</th><th>输出目录</th><th>每个文件的内容</th></tr>" + NL +
                    "<tr><td>生成标准excel文件</td><td><code>meta_data_file_年月日</code></td>" + NL +
                    "<td>两个 Sheet：<b>元数据</b>（建表用的字段定义：序号 / 字段名(英文) / 字段注释(中文) /" + NL +
                    "数据类型 / 长度 / 是否主键 / 是否可为空）+ <b>数据</b>（表头是英文字段名，下面是数据行）</td></tr>" + NL +
                    "<tr><td>生成简易excel文件</td><td><code>simple_meta_data_file_年月日</code></td>" + NL +
                    "<td>只有一个 <b>数据</b> Sheet，供只要数据、不要建模说明的下游使用</td></tr>" + NL +
                    "</table>" + NL +
                    "<p>文件名沿用解析输入时那套命名：<code>表注释（表名）.xlsx</code>，没有表注释时只留表名。" + NL +
                    "标准件即「两类 Excel 格式」里的结构定义表，<b>可以直接拖回来再导入</b>：" + NL +
                    "字段名、类型、长度、主键、可空全部以元数据 Sheet 为准，读回与导出前一致。" + NL +
                    "可空这一格写「否」才是 <code>NOT NULL</code>，留空或写「是」都是可空；主键列自动写成「否」。" + NL +
                    "日期一律写成 <code>2008-05-19</code> 这样的文本而非 Excel 序列号，" + NL +
                    "避免同一份数据在两次读写之间被历元换算挪走一天。</p>" + NL +
                    "<p>「SQL 脚本改名」页的这两颗按钮产物形状完全一致，只是数据源换成脚本正文：" + NL +
                    "列定义从 <code>CREATE TABLE</code> 解析，「数据」Sheet 的行从脚本里的" + NL +
                    "<code>INSERT</code> / <code>REPLACE INTO</code> 取，每张表最多写 699050 行" + NL +
                    "（不设人为条数上限，按 xlsx 单表上限 1048576 行的 2/3 算）、" + NL +
                    "超出的只在日志点名；脚本里没有 INSERT 时这一张只剩英文字段名那一行表头，" + NL +
                    "仍是给下游填数据的模板，详见「SQL 脚本改名页」一节。</p>" + NL +
                    "<p class=\"tip\">进度按文件计项（一张表 1 项），逐文件写日志，完成后弹窗把目录名原样报出来。" + NL +
                    "按「停止」在当前文件写完后停下，剩余文件不写，日志与弹窗都写明" + NL +
                    "「已取消，剩余文件未生成」。行数越多的表写得越久：实测 20 万行约半分钟，" + NL +
                    "接近上限的 70 万行约两分钟，期间进度条一直在走，不是卡住。</p>"
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
                    "<li><b>30 字符是唯一的降级触发条件</b>：全拼 ≤30 用全拼，超过则整列改用首字母，" + NL +
                    "不按中文字数另设阈值。降级只在全拼超 30 时发生，也就是表头至少 7~8 个汉字，" + NL +
                    "缩写串本身已有长度，碰撞概率很低：本数据包 310 条真实中文列注释实测<b>碰撞 0 组</b></li>" + NL +
                    "<li>降级时<b>数字与单位音节整段保留</b>，不取首字母——否则" + NL +
                    "<code>降水量≥1.0mm</code> / <code>≥10.0mm</code> / <code>≥100.0mm</code>" + NL +
                    "会压成同一个名字，而这些数字正是列名的区分点</li>" + NL +
                    "<li>重名字段自动加 <code>_2</code>、<code>_3</code>，含义可在结构预览里改</li>" + NL +
                    "<li><b>命名开关</b>（图形版三颗勾选框；命令行只有 <code>--initials</code> /" + NL +
                    "<code>--upper</code>，转小写仅图形版有）：" + NL +
                    "「表名和字段名取中文拼音首字母」勾上后整名取各音节首字母（订单金额 → <code>ddje</code>、" + NL +
                    "水库基础信息 → <code>skjcxx</code>）；「表名与字段名转大写」勾上后输出全大写" + NL +
                    "（<code>DING_DAN_JIN_E</code>、<code>T_IMPORT_DATA</code>）；「表名与字段名转小写」是它的对称档，" + NL +
                    "两颗互斥——勾上一颗，另一颗自动取消。图形版启动时只勾「取首字母」，两颗大小写都不勾，" + NL +
                    "新加文件按首字母 + 小写起名；取消首字母回到全拼。命令行不加参数时仍是全拼、小写。" + NL +
                    "转写出来的名字本来就落在小写，文件名括号里写下的英文表名也统一转小写，所以「转小写」" + NL +
                    "在这两路与不勾同结果；它真管到的是结构定义 Sheet 里人工手写的字段名" + NL +
                    "（<code>ITEM_CODE</code> → <code>item_code</code>），「转大写」同样管到它。两颗都不勾时" + NL +
                    "手写名保持原样、不替人改名；大小写开关都不改手工输入的表名前缀。" + NL +
                    "三颗开关都对<b>之后添加的文件</b>生效，切换时日志留一行提示，重复应用结果不变。" + NL +
                    "「SQL 脚本改名」页只有大小写两颗（不提供拼音首字母），默认都不勾；勾上哪一颗，" + NL +
                    "当前表名清单、表元数据与两份生成件就整体按那一档显示，脚本正文不回写。" + NL +
                    "手动编辑过的表名保留输入内容，双击当前表名单元格可直接改这一行的表名</li>" + NL +
                    "</ul>" + NL +
                    "<p class=\"warn\">字段命名规则变更后，同一张表生成的列名与旧版不同（旧 <code>dingdanjine</code>" + NL +
                    "→ 新 <code>ding_dan_jin_e</code>）。若目标库里表已存在，默认行为是跳过建表，" + NL +
                    "随后 INSERT 会报 <b>1054 Unknown column</b>。要么勾「覆盖已存在表」重建，" + NL +
                    "要么在表结构预览里把字段名改回旧列名；老数据表先备份再重导。</p>" + NL +
                    "" + NL +
                    "<h2>类型推断规则</h2>" + NL +
                    "<p><b>全量扫描所有数据行</b>，不做抽样，最长行与异常行都不会漏判。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>数据特征</th><th>推断结果</th></tr>" + NL +
                    "<tr><td>日期时间对象，或含时间的字符串</td><td><code>DATETIME</code></td></tr>" + NL +
                    "<tr><td>日期对象或带分隔符的日期串</td><td><code>DATE</code></td></tr>" + NL +
                    "<tr><td><b>表头含金额语义的数值列</b></td><td><code>DECIMAL(16,2)</code>；样本恰为整数也不用" + NL +
                    "<code>INT</code>，实测位数更多时放宽（见下「金额语义」）</td></tr>" + NL +
                    "<tr><td><b>表头是码值（证件号 / 卡号 / 电话 / 邮编 / 序列号）</b></td><td>一律按文本建，" + NL +
                    "样本全是数字也不用 <code>INT</code>/<code>BIGINT</code>——带 <code>X</code> 的身份证号、" + NL +
                    "带前导零的工号之后都要写得进去</td></tr>" + NL +
                    "<tr><td><b>表头是产量 / 运量这类物质量</b></td><td><code>DECIMAL</code>，小数位至少留 4 位；" + NL +
                    "样本恰为整数也不用 <code>INT</code>，之后进来 <code>12.5</code> 不会被抹掉</td></tr>" + NL +
                    "<tr><td>纯整数</td><td><code>INT</code>，溢出则 <code>BIGINT</code>，" + NL +
                    "再大改用 <code>DECIMAL(n,0)</code>，超过 MySQL 65 位精度才退为文本并留痕</td></tr>" + NL +
                    "<tr><td>含小数</td><td><code>DECIMAL(p,s)</code> 按实测数据定宽：" + NL +
                    "<code>s</code> 取实测最大小数位（下限 2、不截断），<code>p</code> 由实测整数位推出；" + NL +
                    "上限取 MySQL 真实上限（总精度 65、小数 30），装不下退为文本</td></tr>" + NL +
                    "<tr><td>文本</td><td><code>VARCHAR(n)</code>，按全量最大 UTF-8 字节 + 20% 余量向上取档位" + NL +
                    "（32 ~ 16383）；超 16383 字节 → <code>TEXT</code>，超 65535 字节 → <code>MEDIUMTEXT</code>。" + NL +
                    "整列没值时无从观测长度，仍按默认 <code>VARCHAR(64)</code> 建，不给窄档</td></tr>" + NL +
                    "<tr><td>带前导零的整数串（如 <code>007</code>）</td><td>保持文本，不丢前导零</td></tr>" + NL +
                    "<tr><td>8 位纯数字（如 <code>20230101</code>）</td><td>按数值处理，编码列不会误判成日期</td></tr>" + NL +
                    "<tr><td>数值列里无法转换的值</td><td>按 NULL 写入，并在日志汇总告警（列名、数量、行号示例），" + NL +
                    "不静默丢数据</td></tr>" + NL +
                    "<tr><td><b>整列一个值都没有</b></td><td>按字段名语义定类型，见下表</td></tr>" + NL +
                    "</table>" + NL +
                    "" + NL +
                    "<h3>整列为空时按字段名定类型（按优先级排列）</h3>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>语义</th><th>关键词</th><th>类型</th></tr>" + NL +
                    "<tr><td>码值</td><td>身份证 / 军官证 / 护照 / 社保卡 / 银行卡 / 卡号 / 账号 / 统一社会信用代码 /" + NL +
                    "组织机构代码 / 纳税人识别号 / 手机号 / 联系电话 / 电话 / 传真 / 邮编 / 邮政编码 / 微信号 /" + NL +
                    "车牌号 / 车架号 / 发动机号 / 序列号 / 流水号 / 批次号 / 条码 / 二维码 / 验证码 / 学号 / 工号 / 警号</td>" + NL +
                    "<td><code>VARCHAR(64)</code>（排在最前；「账号余额」钱在尾巴上仍归金额，" + NL +
                    "「卡号类型」「证件名称」存的是名字，「身份证有效期」存的是日期，都不归这一族。" + NL +
                    "裸「编号」「代码」「单号」刻意不收——那些多是真自增键）</td></tr>" + NL +
                    "<tr><td>时长</td><td>工期 / 周期 / 时长 / 间隔 / 时延 / 工时 / 机时 / 台时</td>" + NL +
                    "<td><code>INT</code>（时长与金额同时命中时看结尾：<code>付款周期</code> 是天数，<code>工时费</code> 是钱）</td></tr>" + NL +
                    "<tr><td>金额</td><td>金额 / 单价 / 价格 / 费用 / 租金 / 水电费 / 增值税 / 净利润 / 总资产 /" + NL +
                    "应收款 / 违约金 / 本金 / 造价 / 客单价 / 伙食费 / 取暖费 / 被装费 / 油料费 / 维修费 /" + NL +
                    "值班费 / 过路费 / 养路费等</td><td><code>DECIMAL(16,2)</code></td></tr>" + NL +
                    "<tr><td>合计</td><td>总计 / 小计 / 合计 / 共计 / 累计 / 汇总 / 总量 / 总值</td>" + NL +
                    "<td><code>DECIMAL(18,4)</code>（既可能是金额也可能是数量；猜 <code>INT</code>" + NL +
                    "会在写入 12.5 时静默取整）</td></tr>" + NL +
                    "<tr><td>日期</td><td>日期 / 有效期 / 到期日</td><td><code>DATE</code></td></tr>" + NL +
                    "<tr><td>日期时间</td><td>时间 / 日期时间</td><td><code>DATETIME</code></td></tr>" + NL +
                    "<tr><td>时刻</td><td>打卡时刻 / 发车时点</td><td><code>TIME</code></td></tr>" + NL +
                    "<tr><td>布尔</td><td>是否 / 有无 / 开关 / 标志 / 可否 / 能否</td><td><code>TINYINT(1)</code></td></tr>" + NL +
                    "<tr><td>比率</td><td>率 / 比例 / 百分比 / 占比 / 系数 / 倍数 / 权重 / 概率 / 配比</td>" + NL +
                    "<td><code>DECIMAL(18,6)</code></td></tr>" + NL +
                    "<tr><td>评分</td><td>得分 / 评分 / 分数 / 成绩 / 满意度 / 星级 / 扣分 / 考核分 / 理论分 /" + NL +
                    "实操分 / 体能分 / 技能分 / 综合分 / 总评</td><td><code>DECIMAL(10,2)</code></td></tr>" + NL +
                    "<tr><td>坐标</td><td>经度 / 纬度 / 坐标</td><td><code>DECIMAL(18,8)</code></td></tr>" + NL +
                    "<tr><td>计量</td><td>长度 / 面积 / 体积 / 重量 / 温度 / 频率 / 强度 / 硬度 / 能耗 / 吨位 /" + NL +
                    "跨度 / 含沙量 / 壁厚 / 振幅 / 挠度 / 沉降 / 位移 / 噪声 / 分贝 / 总磷 / 开间 / 进深 /" + NL +
                    "航程 / 航速 / 转速 / 亩产 / 血糖 / 视力 / 剂量 / 用药量 / 射程 / 射高 / 射速 / 仰角 /" + NL +
                    "炮径 / 弹径 / 装药量 / 自重等</td><td><code>DECIMAL(18,4)</code></td></tr>" + NL +
                    "<tr><td>产量</td><td>产量 / 销量 / 销售量 / 出货量 / 进货量 / 采购量 / 运量 / 客运量 / 货运量 /" + NL +
                    "旅客发送量 / 吞吐量 / 发电量 / 供水量 / 用水量 / 用电量 / 耗电量 / 产油量 / 捕捞量 / 收获量 /" + NL +
                    "入库量 / 出库量</td>" + NL +
                    "<td><code>DECIMAL(18,4)</code>（带吨、公斤、度这类单位，样本恰为整数也不给 <code>INT</code>；" + NL +
                    "「交易量」「成交量」数的是笔数，仍按 <code>INT</code>）</td></tr>" + NL +
                    "<tr><td>大计数</td><td>播放量 / 阅读量 / 曝光量 / 点击量 / 访问量 / 下载量 / 转发量 /" + NL +
                    "浏览量 / 粉丝数 / 关注数 / 收藏数 / 评论数 / 点赞数</td>" + NL +
                    "<td><code>BIGINT</code>（持续增长的大计数会超过 INT 的 21 亿上限）</td></tr>" + NL +
                    "<tr><td>计数</td><td>数量 / 个数 / 人数 / 次数 / 库存 / 户数、票数 / 名额 / 课时 / 学分 /" + NL +
                    "场次 / 人次 / 班次 / 车次 / 行数 / 站点数 / 订单数、出库数 / 入库数 /" + NL +
                    "在库数 / 在册数 / 在位数 / 配发数 / 消耗数 / 报废数 / 维修数 / 领用数 / 盘盈数 / 盘亏数 /" + NL +
                    "官兵数 / 兵员数 / 装备数 / 台套数 / 辆数 / 参与数、心率 / 脉搏 / 剂次 / 环数 / 圈数、" + NL +
                    "发数 / 航次、年龄 / 工龄 / 年限 / 兵龄 / 警龄 / 婚龄 / 驾龄 / 司龄 / 机龄 / 船龄 / 房龄 / 车龄、" + NL +
                    "序号 / 排名 / 位次 / 座次 / 顺位、" + NL +
                    "年份 / 月份 / 季度 / 月数 / 旬</td><td><code>INT</code></td></tr>" + NL +
                    "<tr><td>网址</td><td>网址 / 网站 / 链接 / 路径 / 域名 / 短链 / 文件名 / 文件夹名 / 目录名 /" + NL +
                    "附件名 / 文档名 / 图片名</td><td><code>VARCHAR(512)</code></td></tr>" + NL +
                    "<tr><td>长文本</td><td>备注 / 说明 / 描述 / 详情 / 内容 / 摘要 / 评价 / 意见 / 经营范围 /" + NL +
                    "注意事项 / 留言 / 反馈 / 条款 / 笔录 / 诉求 / 处置情况 / 主要做法 / 工作纪要 / 事由 / 成效</td>" + NL +
                    "<td><code>TEXT</code></td></tr>" + NL +
                    "<tr><td>名称</td><td>名称 / 姓名 / 标题 / 类别 / 类型 / 品牌 / 型号 / 规格 / 材质 / 部门 /" + NL +
                    "机构 / 项目 / 地址 / 联系人 / 职称 / 岗位 / 科室 / 楼盘 / 街道 / 线路 / 品名 / 语种 / 币种、" + NL +
                    "创建人 / 修改人 / 更新人 / 删除人 / 录入人 / 制单人 / 复核人 / 审核人 / 经办人 / 负责人 /" + NL +
                    "批准人 / 填报人 / 申请人 / 受理人 / 移交人 / 接收人、密级 / 级别 / 层级 / 建制 / 番号 / 部队、" + NL +
                    "课目 / 驾驶员 / 附件 / 状态 / 标记、" + NL +
                    "省 / 市 / 县 / 区 / 乡镇 / 村 / 社区 / 国籍 / 民族 / 籍贯 / 户籍 / 户口 / 军种 / 兵种 /" + NL +
                    "战区 / 军区 / 流域 / 河流 / 性别 / 学历 / 学位 / 婚姻 / 政治面貌 / 健康状况 / 宗教 / 体质</td><td><code>VARCHAR(255)</code></td></tr>" + NL +
                    "<tr><td>其余</td><td>—</td><td><code>VARCHAR(64)</code></td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>判定细则</h3>" + NL +
                    "<ul>" + NL +
                    "<li><b>优先级</b>：码值排在最前（<code>身份证</code>、<code>流水号</code>、<code>联系电话</code>" + NL +
                    "这类一律先归文本，但「账号余额」钱在尾巴上仍归金额、「卡号类型」存的是名字、「身份证有效期」" + NL +
                    "存的是日期，这三类让出去）；时长优先于金额与时间（<code>付款周期</code>、<code>结算周期</code>" + NL +
                    "存的是天/月数）；金额优先于其他数值语义；显式比率词优先于计量词，裸「率」排在所有显式词条之后" + NL +
                    "（频率、功率按计量，容积率按比率，点名过的「心率」按计数）；文本三档排在最后，" + NL +
                    "不让「描述」「名称」抢掉带数字语义的复合表头（<code>数量说明</code> 仍按计数）</li>" + NL +
                    "<li><b>有值的列照旧按数据判定</b>，结构定义 Sheet 里人工写的类型优先；" + NL +
                    "每条按名定类型都写进 <code>[类型修正] … 按表头语义调整</code> 日志行</li>" + NL +
                    "<li><b>英文按成词匹配</b>：<code>create_date</code>、<code>qty</code>、<code>is_deleted</code>、" + NL +
                    "<code>longitude</code>、<code>sub_total</code>、<code>home_url</code>、<code>remark_text</code>、" + NL +
                    "<code>page_views</code>、<code>impressions</code>、<code>principal</code>、<code>premium</code>、" + NL +
                    "<code>latency_ms</code>、<code>ordinal</code>、<code>message_body</code>、<code>phone_number</code> 命中，" + NL +
                    "<code>update</code>、<code>birthday</code>、<code>news</code> 不误中。" + NL +
                    "英文词根还要过一遍拼音音节表：<code>参考率</code> 转拼音是 <code>can_kao_lv</code>，" + NL +
                    "因此 <code>can</code> 这类既是英文词又是拼音音节的不作词根使用；" + NL +
                    "<code>is</code> / <code>has</code> / <code>bool</code> / <code>flag</code> 保留</li>" + NL +
                    "<li><b>词表覆盖面由夹具机器判定</b>：表头直接从词表导出（1516 列，中文 1160 条、" + NL +
                    "英文词根 356 个），两条线逐列必须给出同一个类型；少抄一条词、顺序错位、两侧不一致，" + NL +
                    "都会在类型推断金标准差分那一步报差异</li>" + NL +
                    "<li><b>空列不给窄档</b>：整列没值时无从观测长度，未知列一律按默认 <code>VARCHAR(64)</code> 建，" + NL +
                    "名称档 255、链接档 512、长文本 TEXT 都只放宽；有值的列才按实测最大字节 + 20% 余量" + NL +
                    "（余量下限 16 字节）向上取档位，最短一档 32——能进 32 的列观测长度不超过 16，" + NL +
                    "仍留着至少一倍的空间给以后进来的长值</li>" + NL +
                    "<li><b>只收成词的关键词，不认裸「费」「价」</b>：<code>费县</code>（地名）、<code>评价</code>" + NL +
                    "不会被误判成金额；<b>钱味词根在中间不算金额</b>——<code>付款单位</code>、<code>缴费方式</code>、" + NL +
                    "<code>工资名单</code>、<code>收费标准</code> 按结尾判定否决，落回名称档，" + NL +
                    "<code>支付金额</code>、<code>应收款</code> 这类钱在末尾的照旧 <code>DECIMAL(16,2)</code></li>" + NL +
                    "<li><b>比率类一律否决金额语义</b>：含 率 / 比例 / 百分比 / 占比 / 增速，或名字含" + NL +
                    "rate / ratio / percent / pct / share 的列不算金额（<code>年利率</code> 按实测小数位定宽，" + NL +
                    "否则 4 位小数被压成 2 位，数据就改了）</li>" + NL +
                    "<li><b>有些词故意不收</b>：血压（<code>120/80</code> 这种写法）、血型（<code>A 型 Rh 阴性</code>）、" + NL +
                    "过敏史（「青霉素过敏」）判成数值档会让写入直接报错，留在默认 <code>VARCHAR(64)</code>；" + NL +
                    "「整改期限」「完成时限」同理（里面是「3 年」「6 个月」这类时长甚至一段文本），只收" + NL +
                    "「有效期」「到期」；「口径」也不收——它在治理类表里是「统计口径」（一段文本），" + NL +
                    "在装备表里才是炮管口径（毫米数），一个词两种含义，判成哪一档都会有一半的表建错</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<h3>金额语义与精度</h3>" + NL +
                    "<p>Excel 里 <code>100</code> 常以 <code>100.0</code> 存储，整数值浮点也会被认成整数，" + NL +
                    "纯值判定会把「订单金额」判成 <code>INT</code> 而砍掉小数位。因此在值判定之上加一层" + NL +
                    "<b>表头语义修正</b>：中文表头或英文字段名命中金额关键词的数值列一律用" + NL +
                    "<code>DECIMAL(16,2)</code>——金额 / 单价 / 价格 / 均价 / 报价 / 成交价 / 结算价 / 总价 /" + NL +
                    "费用 / 成本 / 余额 / 工资 / 补贴 / 收入 / 支出 / 付款 / 预算 / 税额 / 利息 / 保费 / 市值 /" + NL +
                    "销售额 / 利润 / 欠款 / 贷款 / 保证金 / 押金 / 电价 / 水价 / 运价 / 票价，英文" + NL +
                    "amount / amt / money / price / cost / fee / salary / wage / income / expense / balance /" + NL +
                    "budget / revenue / profit / payment / charge / fund / tax / interest / capital。" + NL +
                    "语义层改变了纯值结论时，日志单独打一行说明：</p>" + NL +
                    "<pre>[类型修正] 订单表.xlsx 共 1 列按表头语义调整：" + NL +
                    "    表头「订单金额」含金额语义，样本值虽为整数，仍按 DECIMAL(16,2) 而非整数类型</pre>" + NL +
                    "<ul>" + NL +
                    "<li><b>绝不截断实测精度</b>：<code>结算金额</code> 实测有 4 位小数就给" + NL +
                    "<code>DECIMAL(18,4)</code>；经纬度 <code>39.9046897</code> 保住 7 位给" + NL +
                    "<code>DECIMAL(11,7)</code>；<code>总金额</code> 大到 16 位整数时放宽为" + NL +
                    "<code>DECIMAL(18,2)</code>。总精度按 MySQL 真实上限 65（小数 30），" + NL +
                    "不再硬压到 30——旧上限会把 14 位整数 + 18 位小数压成 <code>DECIMAL(30,18)</code>，" + NL +
                    "写入时报 <code>1264 Out of range value</code>。旧版一律给 <code>DECIMAL(20,6)</code>" + NL +
                    "的做法已废弃：2 位小数的普通列占用不必要的宽度，又装不下 7 位小数的经纬度列</li>" + NL +
                    "<li><b>行内字节预算</b>：utf8mb4 下每个 <code>VARCHAR(n)</code> 计 4n 字节，" + NL +
                    "整表共享 60000 字节预算，预算不足的大文本列自动退为 <code>TEXT</code>，" + NL +
                    "保证建表不报 Row size too large。按表头语义给的那几档字面类型（名称 255、链接 512、" + NL +
                    "默认 64）同样占行内字节，一起进这份预算——几十列只写了表头的宽模板，" + NL +
                    "从前会一起把 MySQL 单行 65535 的上限顶穿、整张表建不出来，现在排到后面的列退为" + NL +
                    "<code>TEXT</code>，表照旧建得出，日志与预览里那一列写明\"退为 TEXT 建列\"</li>" + NL +
                    "<li><b>超大整数也有去处</b>：超过 BIGINT 范围改用 <code>DECIMAL(n,0)</code>，" + NL +
                    "超过 65 位精度才退为文本并留痕，不会建出装不下数据的列、等第一条超界数据再报" + NL +
                    "<code>1264</code> 回滚。Excel 的数值单元格本身是双精度浮点，" + NL +
                    "<code>9223372036854775807</code> 存进去已经变成 <code>2^63</code>，" + NL +
                    "这种量级的数建议按文本列导入，或在结构定义 Sheet 里写成 <code>DECIMAL(20,0)</code></li>" + NL +
                    "<li><b>结构定义模式不受影响</b>：人工写了 <code>INT</code> 就还是 <code>INT</code>，" + NL +
                    "语义层只服务「从数据推断」这一条路</li>" + NL +
                    "<li><b>主键列不参加降级</b>：<code>TEXT</code> 当不了主键（MySQL 直接报" + NL +
                    "<code>1170 BLOB/TEXT column used in key specification without a key length</code>），" + NL +
                    "降长度又会把键值截断、把两行并成一行。所以体检只动普通列；<b>顶住的是主键时</b>，" + NL +
                    "表会被如实拦下、交给下面的自动拆分（拆开的每段都带着主键，仍然建得出表）。" + NL +
                    "拦下说明里会点名「主键列 <code>xxx</code> 没动」，不会让你以为工具没尽力</li>" + NL +
                    "<li><b>救不回来就一列都不改</b>：降级之前先算一遍「降到极限还够不够」。比如 600 列短文本，" + NL +
                    "全退成 <code>TEXT</code> 每列也要留 41 字节引用，600×41 早就超过 InnoDB 半页 8126，" + NL +
                    "怎么降都没救。这种表直接拦下并拆，不会先把几百列改成 <code>TEXT</code> 再告诉你" + NL +
                    "「还是不行」——那样既白改了类型，又让人以为本来有救</li>" + NL +
                    "<li><b>装不下就自动拆成几张</b>：降长度、退 TEXT 都救不回来的宽表（定长列把 InnoDB 半页" + NL +
                    "堆满、需要整列走行外的列数超半页容量、列数超 1017），导入页会把<b>表头最前面那几列键列</b>" + NL +
                    "（编号/编码/名称/日期这一类，加上全部主键列）当公共字段，其余列按行字节预算装箱，" + NL +
                    "每段的公共字段都重复一份，写成 <code>原文件名_part1.xlsx</code>、<code>_part2.xlsx</code>……" + NL +
                    "放在原文件旁边（压缩包里出来的放压缩包旁边，同名不覆盖，后一份加 <code>_2</code>）。" + NL +
                    "日志逐段写明本段几列、行内多少字节、文件名是什么。把这几份拖回来就是几张独立的表，" + NL +
                    "按公共字段能 join 回原表；每一份都重新过一遍行上限，拆完仍然建不出来的会当场说清。" + NL +
                    "结构定义模式的表不自动拆——那里的字段类型是人工写的，拆出去会按数据重新推断，等于把定义丢掉。</li>" + NL +
                    "</ul>" + NL +
                    "" + NL +
                    "<p>所有推断结果都可以在<b>「生成表结构预览」</b>里逐列修改：弹窗一张表一页，" + NL +
                    "五列分别是<b>主键</b>勾选框、<b>字段名(拼音/首字母)</b>、<b>字段注释(中文表头)</b>、" + NL +
                    "<b>数据类型</b>、<b>表内数据最大字节长度</b>。前三列可改，最后一列<b>是按全量数据实测出来的读数，" + NL +
                    "只读</b>——它写的是这一列真实占了多少字节，改大它并不会让建出来的列变宽，" + NL +
                    "要放宽就改数据类型，字节上限由程序重算。这一页只管字段；<b>表注释</b>在清单里双击" + NL +
                    "「表注释」单元格修改，与第二个页签同一含义。列宽按内容排（短的用设计下限托住，长的随内容撑宽），" + NL +
                    "整表宽过弹窗时表格下方出横向滚动条，长字段名既不会被挤成省略号，也不会被切掉半截滚不回来。" + NL +
                    "保存时主键校验不过会整次不生效并说明原因。</p>" + NL +
                    "<p>字段也可以<b>增删</b>：表格上沿有「新增字段」与「删除选中字段」两颗按钮。" + NL +
                    "「新增字段」往这句建表语句末尾补一列占位列（字段名 <code>is_new_field</code>、类型" + NL +
                    "<code>VARCHAR(255)</code>、注释「新字段」），补完在表里把名字、注释、类型改成要的即可；" + NL +
                    "「删除选中字段」把选中的行从建表语句里删掉（可按住 Ctrl 多选，按 <b>Del</b> 同效），" + NL +
                    "删之前先确认一次，主键与索引对它的引用会一并清掉，<b>脚本里的 INSERT 不动</b>——" + NL +
                    "执行到库时列数对不上会报 1136，删字段后要么同步删那几列数据，要么重新生成交付件。" + NL +
                    "两页的这个弹窗用法相同；第二个页签改的是脚本正文里的那句 <code>CREATE TABLE</code>" + NL +
                    "（还没落盘，点「保存修改后ddl脚本」才写出），改完会重解这份脚本并刷新清单。</p>"
        ),
        new Section("prefix", "表名前缀：加与换",
                "<h2>表名前缀：加与换</h2>" + NL +
                    "<p>「表名前缀」输入框旁边有<b>两个按钮，共用同一个输入框</b>，做的是两件事：</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>按钮</th><th>作用</th><th>例子（填 <code>change</code>）</th></tr>" + NL +
                    "<tr><td>批量应用前缀</td><td>在表名<b>前面再加一段</b>；始终基于「基础表名」重算，" + NL +
                    "因此换前缀不会叠加，<b>留空再点即是移除前缀</b></td>" + NL +
                    "<td><code>ods_t_order</code> → <code>changeods_t_order</code>（要加分隔符就写成" + NL +
                    "<code>change_</code>）</td></tr>" + NL +
                    "<tr><td>替换首个前缀</td><td>把表名<b>第一个下划线之前那一段换掉</b>，后面部分原样保留</td>" + NL +
                    "<td><code>ods_t_order</code> → <code>change_t_order</code><br>" + NL +
                    "<code>adb_table2</code> → <code>change_table2</code></td></tr>" + NL +
                    "</table>" + NL +
                    "<p>两页的「表名前缀」输入框与这两颗按钮同名同义，占位句也是同一句；差别只在生效时机：" + NL +
                    "<b>「Excel 导入」页填在这里之后，新添加的文件自动带上</b>，要改当前清单上的表再点一次按钮；" + NL +
                    "<b>「SQL 脚本改名」页不会自动带上</b>，点了按钮才改，且只改「当前表名」列的显示，" + NL +
                    "保存或执行时才写进脚本。</p>" + NL +
                    "<h3>「替换首个前缀」的细节</h3>" + NL +
                    "<ul>" + NL +
                    "<li>按<b>第一个下划线</b>切分，因此 <code>ods_a_b</code> 只换掉 <code>ods</code>，得到" + NL +
                    "<code>change_a_b</code></li>" + NL +
                    "<li>末尾那个下划线<b>不用写</b>：填 <code>change</code> 与 <code>change_</code> 效果相同，" + NL +
                    "不会出现 <code>change__t_order</code></li>" + NL +
                    "<li><b>表名里没有下划线的不改</b>（如 <code>users</code>：整名只有一段，换掉等于改名而非换前缀），" + NL +
                    "以下划线开头的（如 <code>_tmp</code>）同样不改；这些表逐条写进日志说明原因</li>" + NL +
                    "<li><b>重复点不叠加</b>：已经是 <code>change_</code> 开头的表名再点一次保持原样</li>" + NL +
                    "<li>换完两张表撞名（<code>ods_a</code> 与 <code>x_a</code> 都变成 <code>change_a</code>）时，" + NL +
                    "自动按 <code>change_a</code> / <code>change_a_2</code> 去重</li>" + NL +
                    "<li>只允许字母、数字、下划线；填了其他字符会弹窗拒绝，<b>不会生成非法表名</b></li>" + NL +
                    "<li>换过之后这就是新的基础表名：再点「批量应用前缀」填 <code>dim_</code>，" + NL +
                    "得到 <code>dim_change_t_order</code></li>" + NL +
                    "<li>拼音表名需注意：自动生成的 <code>shui_ku_ji_chu_xin_xi</code> 第一刀切在" + NL +
                    "<code>shui</code> 后面，变成 <code>change_ku_ji_chu_xin_xi</code>——" + NL +
                    "这类名字应使用「批量应用前缀」而非「替换首个前缀」</li>" + NL +
                    "</ul>" + NL +
                    "<p>「SQL 脚本改名」页同样有这两个按钮，但<b>只改页面显示</b>：" + NL +
                    "「当前表名」列与双击打开的元数据标题同步变化，脚本正文不动。点" + NL +
                    "「保存修改后ddl脚本」「执行修改后ddl脚本到当前库」或两颗生成按钮时，" + NL +
                    "才把显示名写进脚本并同步改写 <code>CREATE / DROP / INSERT INTO / ALTER / TRUNCATE</code>" + NL +
                    "对同一张表的引用。清空前缀框后再点任一前缀按钮，显示恢复成脚本原名；" + NL +
                    "「原表名」一列始终显示最初的名字，便于核对。</p>"
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
                    "<b>+2</b> 整数类型；<b>+1</b> 短字符类型（VARCHAR ≤64）。同分取字节长度更短的列。" + NL +
                    "没有合适字段就不设主键。</p>" + NL +
                    "<h3>手工修改</h3>" + NL +
                    "<p>「生成表结构预览」里每行有主键勾选框，可自由勾选或取消一个或多个字段。" + NL +
                    "保存时自动校验，<b>不通过会提示且改动不生效</b>，弹窗保持打开以便继续调整：</p>" + NL +
                    "<ul>" + NL +
                    "<li>类型不能作主键（如 TEXT）</li>" + NL +
                    "<li>存在空值或空白值</li>" + NL +
                    "<li>组合值重复</li>" + NL +
                    "</ul>" + NL +
                    "<p>只有表头的空表不自动选主键，但可以手工勾选，理由见「两类 Excel 格式」一节。</p>" + NL +
                    "<h3>自动补全主键（两页底部动作条各一颗，默认不勾选）</h3>" + NL +
                    "<p>勾上后，<b>一个主键字段都没有</b>的表会在字段顺序首位补一个 <code>business_id</code>：" + NL +
                    "类型 <code>VARCHAR(32)</code>、注释「业务主键」、非空，并作为这张表的主键。" + NL +
                    "表里有数据行时，每行各给一个 32 位十六进制随机值（两行不会撞成同一个）；" + NL +
                    "一行数据都没有的空表只补字段定义，没有值可填。</p>" + NL +
                    "<p>三种表不补，被跳过的那一张会在日志里点名原因：</p>" + NL +
                    "<ul>" + NL +
                    "<li>已经有主键字段的——自动选中的、结构定义 Sheet 里声明了的、脚本里写了" + NL +
                    "<code>PRIMARY KEY</code> 的，都算有；再补只会把原主键挤成组合主键</li>" + NL +
                    "<li>表里已经写着 <code>business_id</code> 的；补进去就是两个同名字段，建表直接报错</li>" + NL +
                    "<li>关闭开关时这张表本来就没有补进去的列，回收不动</li>" + NL +
                    "</ul>" + NL +
                    "<p>补出来的字段名跟随「表名与字段名转大写／转小写」那一档（勾了转大写就是" + NL +
                    "<code>BUSINESS_ID</code>）。开关切换<b>立即生效</b>：第一页清单的「字段数」「主键」两列、" + NL +
                    "表结构预览、预览 DDL、开始导入与两颗生成按钮都按补完的形状走，勾上之后再添加的文件" + NL +
                    "同样按这一档处理；取消勾选把补进去的那一列连同逐行值一起收回，人工勾选的主键不受影响。</p>" + NL +
                    "<p><b>「SQL 脚本改名」页那一颗管的是脚本解析出的字段定义</b>：双击打开的表元数据、" + NL +
                    "预览 DDL 与两颗生成按钮都带上这一列，脚本里有 <code>INSERT</code> 的按行数逐行给值。" + NL +
                    "<b>脚本正文一个字不改</b>——预览 DDL 补在副本上，「保存修改后ddl脚本」与" + NL +
                    "「执行修改后ddl脚本到当前库」仍按脚本原文走，这两条出口写出去的还是脚本自己那几列。</p>"
        ),
        new Section("sqlpage", "SQL 脚本改名页",
                "<h2>SQL 脚本改名页（第二个页签）</h2>" + NL +
                    "<p>处理现成的 <code>.sql</code> 建表脚本，三步按顺序走：提取脚本里的" + NL +
                    "<code>CREATE TABLE</code> 列成表名清单（表格左上角那句「待处理的脚本表」）、" + NL +
                    "按清单批量改表名、再另存副本或执行到当前库。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>控件</th><th>作用</th></tr>" + NL +
                    "<tr><td>拖 SQL 脚本进来 / 点击该虚线区</td><td>可多选，自动统计脚本里所有" + NL +
                    "<code>CREATE TABLE [IF NOT EXISTS] `表名`</code> 并提取表名（兼容反引号、裸名、" + NL +
                    "<code>schema.表名</code> 限定名，按首次出现顺序展示）；<code>INSERT INTO</code> 等不计入。" + NL +
                    "入口与「Excel 导入」页同一套：虚线框可拖可点，没有单独的「添加 SQL 文件」按钮。" + NL +
                    "混着不是 <code>.sql</code> 的文件时日志逐个点名跳过；整批都不是 <code>.sql</code> 时" + NL +
                    "明说一句已忽略，不会清单不动又没有任何提示</td></tr>" + NL +
                    "<tr><td>清空待上传文件</td><td>排在表格下面那一排最左，与「Excel 导入」页同名按钮同位置同语义：" + NL +
                    "整份清出清单，磁盘上的脚本不动；清单本来为空时也会写" + NL +
                    "「清单里没有脚本文件，无需清空」，不会点了没有反应</td></tr>" + NL +
                    "<tr><td>生成表结构预览</td><td>这一排第二颗，与「Excel 导入」页同位置同宽。" + NL +
                    "打开<b>当前选中那一行</b>那张表的字段清单（与双击「文件」「原表名」列打开的是同一个框），" + NL +
                    "字段名 / 字段注释 / 数据类型三列可改；未选中任何行时取清单第一行。" + NL +
                    "清单里还没有脚本表时弹窗说明下一步，不会摆一个空框出来</td></tr>" + NL +
                    "<tr><td>预览 DDL</td><td>这一排第三颗。一张表一个页签，正文取脚本里属于这张表的那句" + NL +
                    "<code>CREATE TABLE</code>，并应用尚未落地的改名与字段编辑，因此看到的就是" + NL +
                    "「保存修改后ddl脚本」与「执行修改后ddl脚本到当前库」将要写出去的内容；显示前按格式化器" + NL +
                    "排过一遍版（字段一行一个、条件续行缩进），<b>脚本正文本身一个字没动</b>，弹窗下方写明这件事；" + NL +
                    "勾「覆盖已存在表」时每页开头补上执行前那句 <code>DROP TABLE IF EXISTS</code>。" + NL +
                    "预览只算一份副本，不改脚本正文也不把显示名定死，改完前缀再点就能看到新表名</td></tr>" + NL +
                    "<tr><td>统计数据总行数</td><td>这一排第四颗。把清单里每张表的「数据行数」加起来，" + NL +
                    "弹窗给出<b>数据总行数</b>（同时报脚本个数与表数），日志写同一句话；数值取的就是表格那一列" + NL +
                    "（脚本里 <code>INSERT</code> / <code>REPLACE INTO</code> 的数据行数），不再读一遍脚本，" + NL +
                    "也不改脚本、不碰磁盘上的文件。清单为空时提示先添加脚本，不会算出 0 行</td></tr>" + NL +
                    "<tr><td>批量应用前缀 / 替换首个前缀</td><td><b>只改页面显示</b>：前者给「当前表名」列加一段显示前缀，" + NL +
                    "后者基于当前显示名把第一个下划线之前的段落换成输入框内容，双击打开的元数据标题同步变化，" + NL +
                    "脚本正文不动。已带该前缀的自动跳过，重复应用不叠加，表名里没有下划线的不动；" + NL +
                    "清空输入框后再点，显示恢复为脚本原名。真正写进脚本要等四个动作按钮</td></tr>" + NL +
                    "<tr><td>保存修改后ddl脚本</td><td>先把页面显示的表名写进内存脚本（引用一并同步改写），" + NL +
                    "再另存为 <code>原名_prefix.sql</code>，落在源文件旁边，<b>不覆盖原文件</b>；" + NL +
                    "没有可保存的改动时也会弹窗说明</td></tr>" + NL +
                    "<tr><td>执行修改后ddl脚本到当前库</td><td>点击时先把显示名写进脚本，再按上方公共连接区选定的目标库执行；" + NL +
                    "<b>每个文件一个事务</b>：先开启事务，逐条执行，任一语句失败立即回滚整个事务并在日志标红，" + NL +
                    "后续语句不再执行，全部成功才提交。读进来的脚本若识别为整库 dump，日志先原样写一行提示" + NL +
                    "<code>[提示] …：这份看着像整库 dump，但这一页仍按每个文件一个事务执行。整库导入请用第三个页签" + NL +
                    "「SQL 执行器」的脚本文件队列，那边对 dump 不开启事务、逐条提交、出错继续并在最后汇总。</code>" + NL +
                    "——本页不改自己的事务口径，只说明该走哪一页</td></tr>" + NL +
                    "<tr><td>覆盖已存在表（先 DROP 再建）</td><td>底部动作条最左的勾选框，<b>默认不勾选</b>。" + NL +
                    "勾选后点「执行修改后ddl脚本到当前库」时，执行副本里每条 <code>CREATE TABLE</code>" + NL +
                    "的建表目标前补一句 <code>DROP TABLE IF EXISTS</code>，目标表已存在时也能直接重建。" + NL +
                    "只认整句以 <code>CREATE TABLE</code> 开头的语句，注释或字符串里的建表不触发删除；" + NL +
                    "脚本自己已写了 <code>DROP TABLE</code> 的表不再补第二句（补出的一批排在副本最前面，" + NL +
                    "会抢在整库 dump 自带的 <code>SET FOREIGN_KEY_CHECKS=0</code> 之前，" + NL +
                    "被外键引用的父表在那之前删不掉）。<b>只影响执行，不改脚本正文</b>，" + NL +
                    "保存与两颗生成按钮仍按原脚本走</td></tr>" + NL +
                    "<tr><td>自动补全主键</td><td>底部动作条上「覆盖已存在表」右侧的勾选框，<b>默认不勾选</b>。" + NL +
                    "勾上后给脚本里<b>没写 <code>PRIMARY KEY</code></b> 的表在字段首位补一个" + NL +
                    "<code>business_id</code>（<code>VARCHAR(32)</code>、非空、注释「业务主键」），" + NL +
                    "表元数据、预览 DDL 与两颗生成按钮都带上它，脚本里有 <code>INSERT</code> 的按行各给一个" + NL +
                    "32 位十六进制随机值。<b>不改脚本正文</b>：预览 DDL 补在副本上，保存与执行两条出口" + NL +
                    "仍按脚本原文走。判据与补法见「主键」一节</td></tr>" + NL +
                    "<tr><td>生成标准excel文件 / 生成简易excel文件</td><td>把脚本里解析出的字段定义写成" + NL +
                    "<code>sql_meta_data_file_年月日</code> 目录下的 xlsx，一张表一个文件，形状与" + NL +
                    "「Excel 导入」页那两颗完全一致（标准件两个 Sheet、简易件只留数据 Sheet）；" + NL +
                    "目录名带 <code>sql_</code> 前缀，与第一页产物互不覆盖，两颗按钮可同时跑。" + NL +
                    "区别在数据源：列定义<b>只看 <code>CREATE TABLE</code>，不看库</b>，" + NL +
                    "数据行取脚本正文里的 <code>INSERT</code> / <code>REPLACE INTO</code> 字面量" + NL +
                    "（每张表最多 699050 行，按 xlsx 单表上限 1048576 行的 2/3 算、不设人为条数上限；" + NL +
                    "超出的在日志点名，值个数与列数对不上的行不写并逐条说明）。" + NL +
                    "脚本里没有 INSERT 时「数据」Sheet 只剩英文字段名那一行表头，给下游当可填模板</td></tr>" + NL +
                    "<tr><td>双击行（文件 / 原表名 / 表注释列）</td><td>打开该表从 <code>CREATE TABLE</code>" + NL +
                    "解析出的元数据，逐列看<b>字段名、字段注释、数据类型、主键、可空</b>五列。" + NL +
                    "数据类型是一整列，按 <code>VARCHAR(64)</code>、<code>DECIMAL(10,2)</code>" + NL +
                    "这种完整写法显示，与第一个页签那一列同名同形，不把长度与精度拆成两列。" + NL +
                    "前三列可直接编辑：改哪一格就只动那句 <code>CREATE TABLE</code> 里那一行的那一段，" + NL +
                    "<code>UNSIGNED</code> / <code>NOT NULL</code> / <code>DEFAULT</code> /" + NL +
                    "<code>AUTO_INCREMENT</code> 原样保留，注释里的单引号按 MySQL 口径翻倍写出，" + NL +
                    "原本没写 <code>COMMENT</code> 的补在行末；<b>主键与可空两列只读</b>，要改需改脚本正文。" + NL +
                    "被 <code>PRIMARY KEY</code> 或索引列名单引用的字段<b>拒绝改名</b>——放过去就是一句引用不存在列的" + NL +
                    "DDL，等建表时才报错比当场拒绝更难排查，弹窗会说明为何没落盘。" + NL +
                    "改动先留在内存，清单与两份生成件立即跟着变化，点「保存修改后ddl脚本」才落副本。" + NL +
                    "字段定义在<b>读入脚本那一刻就按表解析好存着</b>，几十万行的脚本双击也是立即弹出。" + NL +
                    "列宽随内容排，整表宽过弹窗时出横向滚动条</td></tr>" + NL +
                    "<tr><td>双击「当前表名」</td><td>编辑这一行的表名，并同步改写该脚本里的" + NL +
                    "<code>CREATE / DROP / INSERT / ALTER / TRUNCATE</code> 引用；改动先留在内存，" + NL +
                    "点「保存修改后ddl脚本」才落副本</td></tr>" + NL +
                    "<tr><td>双击「表注释」</td><td>编辑这张表的表注释：把那句 <code>CREATE TABLE</code> 的表选项" + NL +
                    "<code>COMMENT='…'</code> 换成新内容（原本没写的在列定义右括号后补一句），" + NL +
                    "单引号按 MySQL 口径翻倍写出，回读即原话；清空写 <code>COMMENT=''</code>。" + NL +
                    "改完清单、元数据弹窗和两份生成件立即跟着变（标准件文件名就是「表注释（表名）.xlsx」），" + NL +
                    "脚本正文同样先留在内存。<code>CREATE TABLE x LIKE y</code> 与" + NL +
                    "<code>... AS SELECT</code> 不带表选项，改不动会当场说明原因</td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li><b>两个前缀按钮只改显示</b>：不弹进度框也不写脚本；「保存修改后ddl脚本」" + NL +
                    "「执行修改后ddl脚本到当前库」「生成标准excel文件」「生成简易excel文件」这四个动作按钮" + NL +
                    "点下去时才把显示名写进脚本，并弹出进度框与结果弹窗。清单为空（还没读入脚本、" + NL +
                    "或点了「清空待上传文件」）时这四颗按钮在原有禁用样式上置灰，读入第一个脚本后自动恢复。" + NL +
                    "列表里没有脚本、或脚本里解析不出 <code>CREATE TABLE</code> 时点这两颗前缀按钮会弹窗说明，" + NL +
                    "不会声称改了 0 个表名</li>" + NL +
                    "<li>「表名与字段名转大写」与「转小写」两颗互斥，默认都不勾；勾上哪一颗，当前表名清单、" + NL +
                    "表元数据里的字段名以及两份生成件就同步按那一档显示，脚本正文不回写；" + NL +
                    "两颗都不勾时清单照脚本里写的样子。本页不提供拼音首字母开关</li>" + NL +
                    "<li>读入脚本结束弹「添加结果」：逐个列出<b>脚本完整路径</b>，分别标注已加载、" + NL +
                    "已在列表（跳过）与读取失败；读取失败在日志给出原因，文件不进清单</li>" + NL +
                    "<li>「保存修改后ddl脚本」「执行修改后ddl脚本到当前库」的结果弹窗按<b>完整路径</b>逐文件列结局：" + NL +
                    "保存列出每份副本的落点与每个失败项；执行先写目标库，再逐文件标成功 / 失败 / 跳过 / 已取消" + NL +
                    "并附语句进度。窗口放不下时正文可鼠标选中并 Ctrl+C 复制；" + NL +
                    "「生成标准excel文件」「生成简易excel文件」的结果弹窗至少 820×420</li>" + NL +
                    "<li>两颗生成按钮取的是<b>改名之后的当前正文</b>，因此「先批量改前缀、再生成交付件」" + NL +
                    "这条路可用，产物里的表名就是新表名</li>" + NL +
                    "<li>元数据 Sheet 只能装「一个类型关键字 + 纯数字长度」，因此 <code>UNSIGNED</code> /" + NL +
                    "<code>ZEROFILL</code> / <code>CHARACTER SET</code> / <code>COLLATE</code> 会被丢掉，" + NL +
                    "<code>ENUM('a','b')</code> 这类括号里不是数字的按 <code>TEXT</code> 导出；" + NL +
                    "每一处都在日志逐列点名（<code>! [WARN] 说明 | …</code>），不静默改语义。" + NL +
                    "同名表被定义多次时只按<b>第一处</b>的列定义导出，其余跳过并写明是哪一处。" + NL +
                    "<code>CREATE TABLE x LIKE y</code> 与 <code>... AS SELECT</code> 没有列定义，" + NL +
                    "生成不了元数据，日志点名后跳过</li>" + NL +
                    "<li>语句切分能正确跳过字符串、反引号与注释中的分号；<b>不支持</b>含" + NL +
                    "<code>DELIMITER</code> 的存储过程脚本——那是整库 dump 的写法，请到「SQL 执行器」页执行，" + NL +
                    "那边识别成 dump 后会按 <code>DELIMITER</code> 切句</li>" + NL +
                    "<li>文件编码按 UTF-8（含 BOM）→ GBK 依次尝试；dump 里夹着原样二进制字段、" + NL +
                    "整份不再是合法 UTF-8 时按 UTF-8 替换坏字节，中文表名、表注释与字段注释不会整份变成乱码</li>" + NL +
                    "<li>事务口径见「常见问题」一节：DDL 隐式提交不可回滚，Excel 导入则是每张表一个事务</li>" + NL +
                    "</ul>"
        ),
        new Section("layer", "分层建模页",
                "<h2>分层建模页（第四个页签）</h2>" + NL +
                    "<p>这一页把已经建好的 ods 层表按业务领域归成 obj（对象层）/ dom（领域层）/ ads（主题层）三层脚本。" + NL +
                    "它原先只认一张 15 列的映射 CSV（系统 / 子领域 / 表中文名 / 表英文名 / 字段数 / 主键字段 /" + NL +
                    "目标领域 / 领域码 / 领域子类 / 领域目标表 / 涉及对象维度 / 主题归属 / 分类依据 / 映射状态 / 备注），" + NL +
                    "几百张表就得逐行填，现场用不动。</p>" + NL +
                    "<p>现在把表直接拖进页顶那块虚线区就出一份映射：这一条<b>不连库、不改库</b>，只读文件名与表里已有的" + NL +
                    "元数据判每张表属于哪个领域，判完把结果铺在中间那张表上，标签写「按文件识别（N 表，M 张领域表）」。" + NL +
                    "也可以先点<b>「读源库结构」</b>再点<b>「按源库结构自动映射」</b>，判法完全相同，只是表清单来自库里的用户表。" + NL +
                    "生成三层的口径没变：仍要先点<b>「读源库结构」</b>把源表结构取回来，三颗生成按钮照旧按依赖锁走。</p>" + NL +
                    "<h3>判领域的规则</h3>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>来源</th><th>权重</th><th>为什么是这一档</th></tr>" + NL +
                    "<tr><td>表注释</td><td>3</td><td>这张表自己说的是谁</td></tr>" + NL +
                    "<tr><td>文件名</td><td>2</td><td>表注释本来就是从文件名（括号外的中文那部分）取的</td></tr>" + NL +
                    "<tr><td>系统名</td><td>1</td><td>从源文件往上最近的已登记系统目录（或 ods 物理表名前缀）通常就是一个业务系统</td></tr>" + NL +
                    "<tr><td>字段注释</td><td>1</td><td>它说的是这张表牵涉哪些对象（机构、设备、人员），" + NL +
                    "字段名里的英文词根同样计入；拿它判领域会把「人员设备台账」判成设备</td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li>先验优先：从源文件往上找最近的已登记系统目录（「系统/分类/表」两层也认），" + NL +
                    "或读库时的 ods 物理表名前缀，先定领域与子领域（<code>运输投送系统2.0</code> 带年份版本号也认），" + NL +
                    "表名再在先验领域内细化子领域；没有先验时才按条款 184 的领域词计数判领域</li>" + NL +
                    "<li><b>一源一表</b>：每张源表生成一张自己的领域目标表" + NL +
                    "<code>dwd_&lt;子领域首字母&gt;_&lt;源表名&gt;</code>，例如「物资保障」写作 <code>dwd_wzbz_t_ck</code>" + NL +
                    "（子领域是固定词表，一律取中文首字母，不写全拼）。" + NL +
                    "领域、子领域、主题只写进映射做分类，<b>不拿它当物理合并键</b>：" + NL +
                    "几十张异构表按领域拼成一张会形成超大宽表，列数越过 MySQL 上限，业务粒度也混在一起。" + NL +
                    "不同目录下有同名源表时，后一张追加 <code>_2</code>、<code>_3</code> 区分</li>" + NL +
                    "<li>要合并只能由人在映射文件里显式声明：多行写同一个「领域目标表」才会并成一张。" + NL +
                    "合并入口带三道护栏——一个目标表最多 <b>10 张源表</b>、<b>500 列</b>，" + NL +
                    "且各行宽必须能装进 MySQL 行上限（按 16K 页半页算）；超了就拒绝生成这张表并逐条写清原因，" + NL +
                    "ads 层同时跳过引用它的语句，不会交出一份建不出来的脚本</li>" + NL +
                    "<li>对象维度只认条款 183 的十类官方键（<code>dim_org</code>、<code>dim_material</code>、" + NL +
                    "<code>dim_fund_account</code>、<code>dim_facility</code>、<code>dim_person</code>、" + NL +
                    "<code>dim_supply_standard</code>、<code>dim_policy</code>、<code>dim_geo</code>、" + NL +
                    "<code>dim_code_info</code>、<code>dim_code_it</code>）；生成器只认这一套，" + NL +
                    "不会拼出拼音 dim 名再被静默丢掉</li>" + NL +
                    "<li>字典、日志、账号、权限这类管道表统一归「综合 / 信息化」，不猜业务领域</li>" + NL +
                    "<li>数不到名词的表<b>不猜</b>：那一行标「待确认」，日志逐表点名说清「文件名、表注释与字段注释里" + NL +
                    "都没有可识别的业务名词」，不会把它塞进某个领域凑数</li>" + NL +
                    "<li>每一行的「分类依据」写清先验来源或计数得分（<code>系统目录「财务系统」先验归领域「财务」/「经费预算」</code>、" + NL +
                    "<code>按表注释/文件名/字段注释与字段名数条款 184 领域词（5 分）归「采购供应」</code>），判错了要改哪一处一眼看得到</li>" + NL +
                    "<li>认不准就手工兜：点<b>「导出映射模板」</b>拿那张 15 列的 CSV，按列填好点<b>「载入映射文件」</b>" + NL +
                    "整体替换。两条入口产出的是同一份映射，生成链没有第二条</li>" + NL +
                    "</ul>" + NL +
                    "<h3>三层怎么出</h3>" + NL +
                    "<ul>" + NL +
                    "<li>obj 层只给判出了十类对象维度的源表建维表，一张源表一张" + NL +
                    "<code>dim_&lt;对象键&gt;</code>；没有维度词的表不动，日志点名「未见十类对象维度关键词」</li>" + NL +
                    "<li>dom 层按上面的一源一表（或映射里显式合并的目标表）出 DDL 与 INSERT；" + NL +
                    "创建时间、更新人、租户、版本号这类系统审计列，以及既无注释、字段名也看不出业务含义的列" + NL +
                    "<b>不进层</b>——硬留在表里只会挤占 500 列上限，日志会汇总说跳过了多少个、举例是哪几列</li>" + NL +
                    "<li>obj / dom 的治理字段逐列写清用途（实体标识、源主键溯源、来源标签、密级、质量标识等），" + NL +
                    "取值也尽量落到语句里：源主键回指 <code>src_entity_id</code>/<code>src_code</code>，" + NL +
                    "<code>std_code</code> 用源表名加源主键合成待赋码，定不出业务值的给可核常量，不再整列 NULL</li>" + NL +
                    "<li>ads 层固定三个主题（后勤资源掌控 / 综合分析 / 辅助决策），每张领域表的<b>每个数值指标</b>" + NL +
                    "各出一条聚合 INSERT：金额、数量按 <code>SUM</code>，比率、均值、单价按 <code>AVG</code>，" + NL +
                    "峰值类按 <code>MAX</code>/<code>MIN</code>，单位从字段注释的括号或「单位：」里取；" + NL +
                    "一张领域表最多落 20 个指标（超出的在日志里点名），没有可用数值列时才退回按源行数" + NL +
                    "<code>COUNT(*)</code> 填 <code>idx_value</code>。指标口径仍待业务方逐条核改</li>" + NL +
                    "<li>主题归属由领域与子领域决定；dom 没生成的表不会出现在 ads 语句里</li>" + NL +
                    "</ul>" + NL +
                    "<p>拖进来的文件认 <code>.xlsx</code> / <code>.xls</code> / <code>.zip</code>，标准件与简易件都收：" + NL +
                    "字段注释在标准件里读「元数据」Sheet 那一列，简易件没有元数据 Sheet 就按中文表头读。" + NL +
                    "这块拖拽区<b>不支持 Ctrl+V</b>，粘贴入口只在「Excel 导入」页。</p>"
        ),
        new Section("sqlrun", "SQL 执行器页",
                "<h2>SQL 执行器页（第三个页签）</h2>" + NL +
                    "<p>这一页有两种用法，共用一个结果区：有单份或整套 <code>.sql</code> 脚本要按顺序打进库时，" + NL +
                    "把文件拖进脚本清单后点<b>「批量执行脚本文件」</b>（只跑其中一个就在它那行的<b>「执行」</b>上点）；" + NL +
                    "只需临时执行一条语句或临时调整结构时，把 SQL 写在编辑区点<b>「执行编辑区 SQL」</b>。" + NL +
                    "两路都打到上方公共连接区选定的库，输出都写进右侧「执行结果」。" + NL +
                    "与「SQL 脚本改名」页的分工：那一页先把脚本里的 <code>CREATE TABLE</code> 挑成清单供批量改名与改注释，" + NL +
                    "改完可另存副本，也可把改过的 DDL 执行到当前库；这一页的脚本清单只管把文件<b>原样</b>按顺序执行，" + NL +
                    "不改脚本正文也不落盘。两路共用同一个结果区，因此同一页同时只跑一个任务。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>控件</th><th>作用</th></tr>" + NL +
                    "<tr><td>页顶的介绍条</td><td>一句话点明本页做什么（执行脚本或临时语句），与前两页的介绍" + NL +
                    "共用同一块强调底板（细边 + 左侧 3px 强调条，强调色与主按钮同一支），选库与否都在。" + NL +
                    "当前库只在最上方连接状态栏说一次，本页不再另写一条「目标库：…」</td></tr>" + NL +
                    "<tr><td>脚本拖拽区</td><td>与「Excel 导入」页同一形状：可点击多选，也可直接拖入；只收" + NL +
                    "<code>.sql</code>，混着其他文件时日志点名 <code>[跳过] 不是 SQL 脚本，未读入：…</code>，" + NL +
                    "一个 <code>.sql</code> 都没有时写" + NL +
                    "<code>[提示] 拖入或粘贴的不是 SQL 脚本文件，已忽略。</code></td></tr>" + NL +
                    "<tr><td>脚本清单</td><td>五列：序号 / 文件 / 大小 / 状态 / 操作，只读、带斑马纹，" + NL +
                    "文件名悬停显示完整路径；大小按 B / KB / MB / GB 折算一位小数。最后一列那颗<b>「执行」</b>" + NL +
                    "只跑这一行的文件，跑法与「批量执行脚本文件」同一套。加入后弹「添加结果」，正文逐条给" + NL +
                    "<code>[已加入] 完整路径</code>、<code>[跳过] …：已在列表</code>、" + NL +
                    "<code>[未加入] …：读盘失败原因</code>，日志同步写" + NL +
                    "<code>===== 添加完成：N 个脚本，M 个无法访问，K 个重复 =====</code>。" + NL +
                    "登记时只记路径与大小，正文等轮到它执行时才读盘，导入大 dump 不必先占满内存</td></tr>" + NL +
                    "<tr><td>清空待执行脚本 / 批量执行脚本文件</td><td>两颗同排，一左一右，都在清单那一格里。" + NL +
                    "<b>「清空待执行脚本」</b>与第一页「清空待上传文件」同语义：整份清出清单，磁盘上的脚本不动；" + NL +
                    "清单为空时点它写 <code>[清空] 清单里没有脚本文件，无需清空</code>。" + NL +
                    "<b>「批量执行脚本文件」</b>是这一排的主按钮，按清单顺序跑完队列；" + NL +
                    "清单为空或有任务占着本页时置灰</td></tr>" + NL +
                    "<tr><td>示例 (?)</td><td>打开示例库（126 条，分 13 组：建库与选库、建表与改表、" + NL +
                    "元数据与治理体检、数据质量检查、数据标准化与清洗、主数据与重复数据、编码映射与关联、" + NL +
                    "数据血缘与影响分析、安全与权限治理、生命周期与归档、性能与容量治理、查询与执行计划、" + NL +
                    "数据修正）。<b>左侧最上方是关键词过滤框</b>，下面是<b>两层目录（分组 → 短标题）</b>，" + NL +
                    "上百条不平铺；搜索按短标题、说明、场景、解决的问题、关键词与语句正文一起匹配" + NL +
                    "（子串包含，不分词），命中的条数与剩下的分组写在目录下面一句，无命中时明确提示清除搜索的方式。" + NL +
                    "<b>右侧分上下两块</b>：上方是这条示例的介绍（说明 / 场景 / 解决的问题 / 关键词），" + NL +
                    "其中「解决的问题」走一档提示绿，关键词<b>一颗一颗各自成一小块</b>（词与词之间留白，" + NL +
                    "不整条铺底色，铺到底时多个词连成一整块会把字压糊）；下方是语句正文，按角色上色——" + NL +
                    "关键字与关键词同用一支紫并加粗、字段名与注释两支蓝、<code>DROP</code> 这类会真删数据的" + NL +
                    "走警示橙，中间分隔条可拖。选中一条可「复制到剪贴板」也可「插入到编辑区」；" + NL +
                    "示例库是独立窗口，边框可拖改大小、标题栏有最小化与最大化，不阻塞界面，可边看边改；" + NL +
                    "主窗口最小化时它不跟着藏，回来点「示例 (?)」把它抬到前</td></tr>" + NL +
                    "<tr><td>格式化 SQL</td><td>在「SQL 文本」栏头一行、排在「示例 (?)」右侧，次要按钮；" + NL +
                    "把编辑区这段 SQL 排整齐：关键字与类型大写、从句各起一行、字段清单一个字段一行并缩进，" + NL +
                    "只改大小写与换行，语句内容不动，排完在结果区日志说一句排了几行几条。" + NL +
                    "焦点在编辑区里时按 <code>Ctrl+Alt+L</code> 是同一件事。整库 dump（带" + NL +
                    "<code>DELIMITER</code> 那种）会被拒绝并说明原因，正文一个字不动；" + NL +
                    "已经排好的形状再点一次也照实说「一个字没动」</td></tr>" + NL +
                    "<tr><td>执行编辑区 SQL</td><td>在「SQL 文本」栏头一行、排在「清空编辑区」右侧，次要按钮；" + NL +
                    "只跑编辑区这段 SQL。清单为空或有任务占着本页时「批量执行脚本文件」置灰</td></tr>" + NL +
                    "<tr><td>清空编辑区 / 清空结果区</td><td>分别在「SQL 文本」与「执行结果」两栏的头一行，" + NL +
                    "各管自己那一边：清结果区不会带走敲了一半的语句，清编辑区不会抹掉上次的结果；" + NL +
                    "清结果区把表格、结果集下拉、上方说明与逐条回执一起清掉</td></tr>" + NL +
                    "<tr><td>选中后按 Del</td><td>把选中行从待执行清单去掉，多选即批量删除——只出清单，" + NL +
                    "磁盘上的脚本不动。未选中任何行时按 Del 会在日志说一句，不会静默无动作；" + NL +
                    "本页有任务在跑时按 Del 会被拦下并说明在等待哪个任务</td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li>脚本按清单顺序执行，<b>每个文件单独连接</b>，执行方式按文件自身类型定：" + NL +
                    "<b>手写脚本</b>一个文件一个事务，全部语句成功才提交，中途出错本文件回滚，" + NL +
                    "日志报出第几条出错语句与「前面 N 条已随事务回滚」，然后继续下一个文件；" + NL +
                    "<b>整库 dump</b> 识别后走另一条通道，见下两条。执行期间清单状态逐行刷新为" + NL +
                    "成功 / 失败 / 跳过 / 已取消（分色显示）</li>" + NL +
                    "<li><b>整库 dump 自动识别</b>：按开头或结尾的导出标记认出" + NL +
                    "<code>-- MySQL dump</code>、<code>-- MariaDB dump</code>、<code>-- MySQL Shell dump</code>、" + NL +
                    "<code>-- phpMyAdmin SQL Dump</code> 或结尾的 <code>-- Dump completed</code>，" + NL +
                    "识别到就<b>不开事务、逐条提交、出错继续</b>：dump 里的 <code>CREATE/DROP/ALTER</code>" + NL +
                    "与 <code>LOCK TABLES</code> 本来就会隐式提交，「整文件回滚」在这条通道里兑现不了，" + NL +
                    "因此不在开头许一个做不到的承诺。日志先写" + NL +
                    "<code>[dump] 文件名：识别为整库 dump，按 dump 方式执行（不开启事务，逐条提交；出错继续，最后汇总）</code>。" + NL +
                    "dump 里认 <code>DELIMITER</code> 指令，触发器与存储过程的整句不会被函数体里的分号切碎；" + NL +
                    "<code>/*!40101 SET … */</code> 这类版本注释语句照常发给服务端。" + NL +
                    "只有识别成 dump 的文件才这样切句，手写脚本与编辑区仍按分号切" + NL +
                    "（自己写 <code>DELIMITER</code> 的存储过程请按 dump 形态导入）</li>" + NL +
                    "<li>dump 自带的 <code>CREATE DATABASE</code> 与 <code>USE</code> <b>照常执行</b>：" + NL +
                    "那两句指定的正是这批表要建在哪个库里，跳过它们等于把用户要导的库抹掉，" + NL +
                    "表反而散进界面当时选中的库。切库后日志写 <code>[切库] 文件名：目标库换成 demo</code>，" + NL +
                    "后续报错行与收尾都按切后的库说明，收尾写" + NL +
                    "<code>[提交] 文件名：N 条语句已提交，目标库 demo，跳过 M 条删库语句</code>；" + NL +
                    "界面选中的库不变。只有 <code>DROP DATABASE</code>（含" + NL +
                    "<code>/*!40000 DROP DATABASE … */</code> 这种写法）不执行，逐条写" + NL +
                    "<code>[跳过] 文件名 第 i/n 条：DROP DATABASE（删库语句不执行）</code>——" + NL +
                    "执行一次就删掉整个库，这个风险与建库、切库不对等</li>" + NL +
                    "<li>逐条提交的结局如实写进清单：有语句报错时<b>继续执行后面的语句</b>，清单写" + NL +
                    "<code>已执行 4/7 条，1 条语句报错（逐条提交，已成功的已落库）</code>，" + NL +
                    "日志逐条给出错语句与原因（最多详细列 20 条，超出只报还有几条）；" + NL +
                    "连接断开时点名断在第几条，剩余语句不再执行</li>" + NL +
                    "<li>整份只剩注释的脚本记 <code>[跳过] …：没有可执行的语句</code>，不占连接也不推进度条。" + NL +
                    "版本注释 <code>/*!NNNNN … */;</code> 不在剔除之列——那几句会由服务端执行" + NL +
                    "（<code>SET NAMES</code>、<code>SET TIME_ZONE</code>、整库导入关外键检查那句都写在这里头），" + NL +
                    "当成注释丢掉等于悄悄改了本次导入的规则</li>" + NL +
                    "<li>取消：手写脚本那条通道回滚当前文件；dump 通道没有整文件事务可回滚，" + NL +
                    "<b>已逐条提交的语句留在库里</b>，清单写" + NL +
                    "<code>已执行 46/121 条（逐条提交，已执行的已落库）</code>。两路都把剩余文件标" + NL +
                    "「已取消：未执行」；连不上目标库时立即停止，日志写清目标库、用户与原因，剩余脚本都不执行</li>" + NL +
                    "<li>执行完成时日志写 <code>===== 执行完成：成功 N 个文件，失败 M 个文件 =====</code>，" + NL +
                    "弹窗正文逐文件给 <code>[成功] / [失败] / [跳过] / [已取消] 完整路径：结局</code>，" + NL +
                    "并写目标库与「详见日志。」；有失败时弹窗按警告级</li>" + NL +
                    "<li>编辑区这一路的事务语义：<b>全部成功才提交，中途出错整批回滚</b>，" + NL +
                    "失败那句后面附 DDL 隐式提交的提醒；结果区逐条给 <code>第 i/n 条</code>，出错那条标红</li>" + NL +
                    "<li>查询结果画成<b>真表格</b>（不是把表格文字打进日志）：表头一行是列名，一行一条数据，" + NL +
                    "隔行底色、只读（双击也改不了格子——改的是显示，库里没动）。列宽按内容排，" + NL +
                    "列名或取值长就把表格撑宽、用横向滚动条看全，不会挤成省略号。空格子写成 <code>NULL</code>，" + NL +
                    "取值里的换行与制表符写成可见转义 <code>\\n</code> / <code>\\t</code>，" + NL +
                    "单个格子超过 40 列截断并补省略号，避免一条带换行的长备注把整张表顶散</li>" + NL +
                    "<li>表格上方那句说明写清看的是哪一条、有多少行：<code>第 2 条 · 共 37 行</code>；" + NL +
                    "结果集超过 200 行时只装前 200 行，句子跟着变成" + NL +
                    "<code>共 205 行（表格只装前 200 行，其余没取回来）</code>，避免几十万行刷死界面</li>" + NL +
                    "<li><b>表头把字段注释挂在 title 上</b>：鼠标停在列名上出现" + NL +
                    "<code>字段注释：订单金额</code>——本页查的多是拼音首字母列名（<code>ddje</code>），" + NL +
                    "只有注释能读懂是哪一列。注释在拿到结果集后按<b>真表真列</b>回查" + NL +
                    "<code>information_schema</code> 取得，因此表别名（<code>FROM 水库表 t</code>）与" + NL +
                    "列别名（<code>sj AS 时间</code>）照样查得到；表达式列（<code>amt+0</code>、" + NL +
                    "<code>1 AS lit</code>）对不上具体列就留空，库里这一列没写注释也留空，不做猜测。" + NL +
                    "还没有结果时整条表头挂一句引导，说明注释在各列的 title 上</li>" + NL +
                    "<li>一批里有多条语句查出结果时，表格上方出现下拉，按 <code>第 N 条 · 语句前 60 字</code>" + NL +
                    "列出每一条，默认停在最近一条，切换时表格与说明一起换掉；只有一条结果时不显示该下拉。" + NL +
                    "一批多于 50 个结果集时只保留最近 50 个回看，更早的丢弃并在结果区写明丢了几个</li>" + NL +
                    "<li>「执行结果」栏的下半截仍是逐条回执（<code>第 i/n 条</code>、影响行数、出错那条标红），" + NL +
                    "与表格之间一条可拖的分隔条；跑出结果集时这一段会自动抬高，不需要先拖一次才看得见数据。" + NL +
                    "命令行版仍把这些画成等宽文本表格（汉字按两列补齐）</li>" + NL +
                    "<li>手写脚本与编辑区的切句与「SQL 脚本改名」页同一套：跳过字符串、反引号与注释里的分号；" + NL +
                    "整句只剩注释的片段会被剔掉，不会算成「已提交 1 条」（dump 另按 <code>DELIMITER</code> 规则切）</li>" + NL +
                    "<li>编辑区与「示例 (?)」里的语句全文都按角色上色（关键字退灰、类型加粗、" + NL +
                    "反引号名与注释各一档、<code>DROP</code> / <code>DELETE</code> / <code>TRUNCATE</code>" + NL +
                    "走警示橙，与「DDL 预览」同一套），长句按词折行，不必拖横向滚动条；" + NL +
                    "开场预置 <code>SELECT 1 AS 连通检查;</code>，连不上库时结果区直接给错误码与原因</li>" + NL +
                    "<li>示例里的表名与库名是占位（<code>t_order</code>、<code>ods_data</code>），" + NL +
                    "现场改完再执行；本页执行的就是提交的语句，<code>DROP</code> / <code>TRUNCATE</code>" + NL +
                    "不另设确认，删除前先看清结果区里那句</li>" + NL +
                    "</ul>"
        ),
        new Section("dmcomment", "达梦注释搬到 MySQL",
                "<h2>达梦的注释搬到 MySQL：两条通道怎么选</h2>" + NL +
                    "<p>本节管一件事：达梦 DM8 里已经写好的<b>表注释</b>和<b>列注释</b>，原样搬到 MySQL 里" + NL +
                    "<b>已存在的同名表</b>上（表结构不动，只补注释）。工具只连 MySQL：达梦那几步在 DM 客户端" + NL +
                    "或 DBeaver 的达梦连接里跑，MySQL 那几步可以整段贴进第三个页签<b>「SQL 执行器」</b>执行。</p>" + NL +
                    "<p>两条通道的差别只有一处：<b>注释怎么送进 MySQL 的中转表</b>。方案 A 让达梦自己拼出" + NL +
                    "<code>INSERT</code> 语句、贴进 MySQL 执行；方案 B 把结果集导成 CSV、用 <code>LOAD DATA</code>" + NL +
                    "灌进去。摸底那一步两条通道都要先跑，四个数字决定走哪条。</p>" + NL +
                    "" + NL +
                    "<p><b>先摸底（两条通道都是这一步开头）</b>：</p>" + NL +
                    "<pre>SELECT '表注释' k, COUNT(*) cnt, MAX(LENGTH(c.comments)) max_len," + NL +
                    "       SUM(INSTR(c.comments, CHR(92))) bs, SUM(INSTR(c.comments, CHR(10))) lf" + NL +
                    "FROM   all_tab_comments c" + NL +
                    "WHERE  c.owner = '{DM_SCHEMA}' AND c.table_name IN ({DM_TABLES})" + NL +
                    "  AND  c.comments IS NOT NULL" + NL +
                    "UNION ALL" + NL +
                    "SELECT '列注释', COUNT(*), MAX(LENGTH(c.comments))," + NL +
                    "       SUM(INSTR(c.comments, CHR(92))), SUM(INSTR(c.comments, CHR(10)))" + NL +
                    "FROM   all_col_comments c" + NL +
                    "WHERE  c.owner = '{DM_SCHEMA}' AND c.table_name IN ({DM_TABLES})" + NL +
                    "  AND  c.comments IS NOT NULL;</pre>" + NL +
                    "" + NL +
                    "<p><b>怎么选</b>（把上面四个数字对着这张表看，从上往下第一条命中就定）：</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>情况</th><th>走哪条</th></tr>" + NL +
                    "<tr><td><code>lf</code> 大于 0，注释里有换行符</td><td>只能方案 B。换行塞进拼出来的 INSERT" + NL +
                    "里，注释会被截断或整条语句语法错</td></tr>" + NL +
                    "<tr><td><code>max_len</code> 超 2048 字节</td><td>方案 B。方案 A 的 <code>INSERT</code>" + NL +
                    "文本会被达梦的字符串上限截掉</td></tr>" + NL +
                    "<tr><td><code>bs</code> 大于 0（有反斜杠）、且你不打算改拼接写法</td><td>方案 B。" + NL +
                    "方案 A 要用 ②' 那个变体先把反斜杠叠一层，改错了就是注释变形</td></tr>" + NL +
                    "<tr><td>要留物理凭证给人复核、要按表分批增量搬、导出与导入不是同一个人在做</td><td>方案 B。" + NL +
                    "那份 CSV 就是可比对、可归档、可交接的中间产物</td></tr>" + NL +
                    "<tr><td>MySQL 侧不让用 <code>local_infile</code>，或云 RDS 没开本地文件导入、" + NL +
                    "你没有服务器文件系统写权限</td><td>方案 A。方案 B 的 <code>LOAD DATA LOCAL</code> 走不通</td></tr>" + NL +
                    "<tr><td>表在几十张以内、注释合计几千行、注释里没有换行和反斜杠</td><td>方案 A。" + NL +
                    "不产生中间文件，一条链跑完</td></tr>" + NL +
                    "<tr><td>你熟 DM8 的字符串函数，能在源端把引号、反斜杠处理干净</td><td>方案 A</td></tr>" + NL +
                    "</table>" + NL +
                    "" + NL +
                    "<p><b>参数占位符（两条通道同一套）</b>：执行前先把花括号名全局替换完再动手——" + NL +
                    "<code>{DM_SCHEMA}</code> 达梦模式名（大写，不带引号，例 <code>GAMEDATA</code>）、" + NL +
                    "<code>{DM_TABLES}</code> 达梦表名列表（带单引号逗号分隔，例 <code>'DIM_A','DIM_B'</code>）、" + NL +
                    "<code>{DB}</code> MySQL 目标库名（例 <code>game_meta</code>）、" + NL +
                    "<code>{MY_TABLES}</code> MySQL 表名列表（例 <code>'dim_a','dim_b'</code>）、" + NL +
                    "<code>{CSV_PATH}</code> 仅方案 B 用：CSV 的绝对路径，正斜杠写法" + NL +
                    "（<code>D:/work/dm.csv</code>；要用反斜杠得写成两个）。</p>" + NL +
                    "<p><b>执行前</b>先对目标库做一次元数据备份 <code>mysqldump --no-data</code>；" + NL +
                    "两条通道里的\"快照\"那一步是唯一回滚依据，不能跳；普通列的" + NL +
                    "<code>ALTER COLUMN ... SET COMMENT</code> 需要 MySQL 8.0.13 以上，先用" + NL +
                    "<code>SELECT VERSION()</code> 核实。</p>" + NL +
                    "" + NL +
                    "<p><b>方案 A（SQL 直连）· 完整九步</b>　顺序：① ② 在达梦，③~⑨ 在 MySQL。</p>" + NL +
                    "<p>A-① 摸底：用上面那条，记下 <code>cnt</code>（表、列各一行）、<code>bs</code>、" + NL +
                    "<code>lf</code>、<code>max_len</code>。<code>lf</code> 大于 0 就停，改走方案 B。</p>" + NL +
                    "<p>A-② 在达梦生成 <code>INSERT</code> 语句。表注释那一支用空串占 <code>cn</code>，" + NL +
                    "后面靠它区分表级和列级：</p>" + NL +
                    "<pre>SELECT 'INSERT INTO tmp_dm_comment(tn,cn,cm) VALUES ('" + NL +
                    "       || CHR(39) || LOWER(c.table_name) || CHR(39) || ','" + NL +
                    "       || CHR(39) || LOWER(c.column_name) || CHR(39) || ','" + NL +
                    "       || CHR(39) || REPLACE(c.comments, CHR(39), CHR(39)||CHR(39)) || CHR(39) || ');' s" + NL +
                    "FROM   all_col_comments c" + NL +
                    "WHERE  c.owner = '{DM_SCHEMA}' AND c.table_name IN ({DM_TABLES})" + NL +
                    "  AND  c.comments IS NOT NULL" + NL +
                    "UNION ALL" + NL +
                    "SELECT 'INSERT INTO tmp_dm_comment(tn,cn,cm) VALUES ('" + NL +
                    "       || CHR(39) || LOWER(c.table_name) || CHR(39) || ','" + NL +
                    "       || CHR(39) || CHR(39) || ','" + NL +
                    "       || CHR(39) || REPLACE(c.comments, CHR(39), CHR(39)||CHR(39)) || CHR(39) || ');'" + NL +
                    "FROM   all_tab_comments c" + NL +
                    "WHERE  c.owner = '{DM_SCHEMA}' AND c.table_name IN ({DM_TABLES})" + NL +
                    "  AND  c.comments IS NOT NULL;</pre>" + NL +
                    "<p>把结果里 <code>s</code> 这一整列复制出来，存成 <code>dm_insert.sql</code>" + NL +
                    "（UTF-8 无 BOM）。用 <code>CHR(39)</code> 而不是直接写单引号，是为了让这段 SQL 不因为" + NL +
                    "引号嵌套而难读难改。</p>" + NL +
                    "<p>A-②' 仅当 <code>bs</code> 大于 0：把上面两处" + NL +
                    "<code>REPLACE(c.comments, CHR(39), CHR(39)||CHR(39))</code> 都换成下面这个写法" + NL +
                    "（先叠反斜杠，再处理单引号，顺序不能反）：</p>" + NL +
                    "<pre>REPLACE(REPLACE(c.comments, CHR(92), CHR(92)||CHR(92))," + NL +
                    "        CHR(39), CHR(39)||CHR(39))</pre>" + NL +
                    "<p>A-③ 在 MySQL 建中转表。<code>cm</code> 用 <code>TEXT</code>，别让 2048 截断注释：</p>" + NL +
                    "<pre>DROP TABLE IF EXISTS tmp_dm_comment;" + NL +
                    "CREATE TABLE tmp_dm_comment (" + NL +
                    "  tn VARCHAR(128) NOT NULL, cn VARCHAR(128) NOT NULL," + NL +
                    "  cm TEXT NOT NULL, UNIQUE KEY uk_dmcm (tn, cn)" + NL +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci" + NL +
                    "  COMMENT='DM注释中转，用完删除';</pre>" + NL +
                    "<p>A-④ 执行 <code>dm_insert.sql</code>（整份贴进「SQL 执行器」批量执行），然后核对行数：" + NL +
                    "<b>表行必须等于 A-① 的表注释 <code>cnt</code>，列行必须等于列注释 <code>cnt</code></b>，" + NL +
                    "对不上就停在这里查，别往下走。</p>" + NL +
                    "<pre>SELECT COUNT(*) 总行, SUM(cn='') 表行, SUM(cn&lt;&gt;'') 列行, MAX(CHAR_LENGTH(cm)) 最长" + NL +
                    "FROM tmp_dm_comment;</pre>" + NL +
                    "<p>A-⑤ 核对外部的库能不能对上号。两个匹配数要分别等于 A-④ 的表行与列行；" + NL +
                    "不相等说明 MySQL 侧表还没建、或者表名列名不一致：</p>" + NL +
                    "<pre>SELECT (SELECT COUNT(*) FROM information_schema.tables t" + NL +
                    "        JOIN tmp_dm_comment d ON d.tn=t.TABLE_NAME AND d.cn=''" + NL +
                    "        WHERE t.TABLE_SCHEMA='{DB}') 表匹配," + NL +
                    "       (SELECT COUNT(*) FROM information_schema.columns c" + NL +
                    "        JOIN tmp_dm_comment d ON d.tn=c.TABLE_NAME AND d.cn=c.COLUMN_NAME" + NL +
                    "        WHERE c.TABLE_SCHEMA='{DB}') 列匹配;</pre>" + NL +
                    "<p>A-⑥ 生成注释语句。列注释走 <code>SET COMMENT</code>：它只改注释、不重述列定义，" + NL +
                    "所以不会把 <code>UNSIGNED</code>、字符集、默认值写丢。两个条件互补，同一条注释" + NL +
                    "只会出现在 ⑥ 或 ⑥补 其中一处：</p>" + NL +
                    "<pre>SELECT CONCAT('ALTER TABLE `', t.TABLE_NAME, '` COMMENT ', QUOTE(d.cm), ';') ddl" + NL +
                    "FROM information_schema.tables t" + NL +
                    "JOIN tmp_dm_comment d ON d.tn=t.TABLE_NAME AND d.cn=''" + NL +
                    "WHERE t.TABLE_SCHEMA='{DB}' AND IFNULL(t.TABLE_COMMENT,'') &lt;&gt; d.cm" + NL +
                    "ORDER BY t.TABLE_NAME;" + NL +
                    "" + NL +
                    "SELECT CONCAT('ALTER TABLE `', c.TABLE_NAME, '` ALTER COLUMN `', c.COLUMN_NAME," + NL +
                    "              '` SET COMMENT ', QUOTE(d.cm), ';') ddl" + NL +
                    "FROM information_schema.columns c" + NL +
                    "JOIN tmp_dm_comment d ON d.tn=c.TABLE_NAME AND d.cn=c.COLUMN_NAME" + NL +
                    "WHERE c.TABLE_SCHEMA='{DB}' AND IFNULL(c.COLUMN_COMMENT,'') &lt;&gt; d.cm" + NL +
                    "  AND c.GENERATION_EXPRESSION='' AND c.EXTRA NOT LIKE '%GENERATED%'" + NL +
                    "  AND IFNULL(c.COLUMN_DEFAULT,'') NOT LIKE 'CURRENT_TIMESTAMP%'" + NL +
                    "  AND IFNULL(c.COLUMN_DEFAULT,'') NOT LIKE 'now()%'" + NL +
                    "  AND IFNULL(c.COLUMN_DEFAULT,'') NOT LIKE 'current_timestamp()%'" + NL +
                    "ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION;</pre>" + NL +
                    "<p>A-⑥补 生成列与带动态默认值的列不接受 <code>SET COMMENT</code>，只能整条" + NL +
                    "<code>MODIFY COLUMN</code> 重述，因此把字符集、可空、默认值、<code>EXTRA</code>" + NL +
                    "一起带上，避免重述时抹平：</p>" + NL +
                    "<pre>SELECT CONCAT(" + NL +
                    "    'ALTER TABLE `', c.TABLE_NAME, '` MODIFY COLUMN `', c.COLUMN_NAME, '` '," + NL +
                    "    c.COLUMN_TYPE," + NL +
                    "    CASE WHEN c.CHARACTER_SET_NAME IS NOT NULL" + NL +
                    "         THEN CONCAT(' CHARACTER SET ', c.CHARACTER_SET_NAME," + NL +
                    "                     IF(c.COLLATION_NAME IS NOT NULL," + NL +
                    "                        CONCAT(' COLLATE ', c.COLLATION_NAME), ''))" + NL +
                    "         ELSE '' END," + NL +
                    "    CASE WHEN c.IS_NULLABLE='NO' THEN ' NOT NULL' ELSE ' NULL' END," + NL +
                    "    CASE WHEN c.EXTRA &lt;&gt; '' THEN CONCAT(' ', c.EXTRA) ELSE '' END," + NL +
                    "    CASE WHEN c.COLUMN_DEFAULT IS NULL AND c.IS_NULLABLE='YES' THEN ' DEFAULT NULL'" + NL +
                    "         WHEN c.COLUMN_DEFAULT IS NOT NULL THEN CONCAT(' DEFAULT ', QUOTE(c.COLUMN_DEFAULT))" + NL +
                    "         ELSE '' END," + NL +
                    "    ' COMMENT ', QUOTE(d.cm), ';'" + NL +
                    ") AS ddl" + NL +
                    "FROM information_schema.columns c" + NL +
                    "JOIN tmp_dm_comment d ON d.tn=c.TABLE_NAME AND d.cn=c.COLUMN_NAME" + NL +
                    "WHERE c.TABLE_SCHEMA='{DB}' AND IFNULL(c.COLUMN_COMMENT,'') &lt;&gt; d.cm" + NL +
                    "  AND ( c.GENERATION_EXPRESSION &lt;&gt; '' OR c.EXTRA LIKE '%GENERATED%'" + NL +
                    "     OR IFNULL(c.COLUMN_DEFAULT,'') LIKE 'CURRENT_TIMESTAMP%'" + NL +
                    "     OR IFNULL(c.COLUMN_DEFAULT,'') LIKE 'now()%'" + NL +
                    "     OR IFNULL(c.COLUMN_DEFAULT,'') LIKE 'current_timestamp()%' )" + NL +
                    "ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION;</pre>" + NL +
                    "<p>A-⑥ 与 A-⑥补 出来的是<b>结果集里的一列文本</b>，不是已经执行的语句：" + NL +
                    "把 <code>ddl</code> 那一整列复制成 <code>dm_alter.sql</code>，回到「SQL 执行器」执行掉。</p>" + NL +
                    "<p>A-⑦ 改之前留快照（在 A-⑥ 那些 ALTER 执行<b>之前</b>取），结果另存为 <code>快照.csv</code>：</p>" + NL +
                    "<pre>SELECT LOWER(t.TABLE_NAME) tn, '' cn, IFNULL(t.TABLE_COMMENT,'') cm" + NL +
                    "FROM information_schema.tables t" + NL +
                    "WHERE t.TABLE_SCHEMA='{DB}' AND t.TABLE_NAME IN ({MY_TABLES})" + NL +
                    "UNION ALL" + NL +
                    "SELECT LOWER(c.TABLE_NAME), LOWER(c.COLUMN_NAME), IFNULL(c.COLUMN_COMMENT,'')" + NL +
                    "FROM information_schema.columns c" + NL +
                    "WHERE c.TABLE_SCHEMA='{DB}' AND c.TABLE_NAME IN ({MY_TABLES});</pre>" + NL +
                    "<p>A-⑧ 校验：两行都必须是 0，且必须在删中转表之前跑：</p>" + NL +
                    "<pre>SELECT '表差异' k, COUNT(*) FROM information_schema.tables t" + NL +
                    "JOIN tmp_dm_comment d ON d.tn=t.TABLE_NAME AND d.cn=''" + NL +
                    "WHERE t.TABLE_SCHEMA='{DB}' AND IFNULL(t.TABLE_COMMENT,'') &lt;&gt; d.cm" + NL +
                    "UNION ALL" + NL +
                    "SELECT '列差异', COUNT(*) FROM information_schema.columns c" + NL +
                    "JOIN tmp_dm_comment d ON d.tn=c.TABLE_NAME AND d.cn=c.COLUMN_NAME" + NL +
                    "WHERE c.TABLE_SCHEMA='{DB}' AND IFNULL(c.COLUMN_COMMENT,'') &lt;&gt; d.cm;</pre>" + NL +
                    "<p>A-⑨ 清理：<code>DROP TABLE IF EXISTS tmp_dm_comment;</code>。" + NL +
                    "方案 A 全程不产生中间数据文件，只有一份 <code>dm_insert.sql</code>、" + NL +
                    "一份 <code>dm_alter.sql</code> 和一份 <code>快照.csv</code>。</p>" + NL +
                    "" + NL +
                    "<p><b>方案 B（CSV 中转）· 完整九步</b>　顺序：① ② 在达梦，③~⑨ 在 MySQL。" + NL +
                    "注释里有换行、超长，或者你不想在达梦折腾转义，就走这条。</p>" + NL +
                    "<p>B-① 摸底：与 A-① 同一条 SQL。这里 <code>lf</code> 大于 0 不是停线，" + NL +
                    "而正是选方案 B 的理由。</p>" + NL +
                    "<p>B-② 在达梦把注释取出来，结果集右键<b>「导出为 CSV」</b>存成 <code>{CSV_PATH}</code>：</p>" + NL +
                    "<pre>SELECT LOWER(c.table_name) tn, LOWER(c.column_name) cn, c.comments cm" + NL +
                    "FROM   all_col_comments c" + NL +
                    "WHERE  c.owner = '{DM_SCHEMA}' AND c.table_name IN ({DM_TABLES})" + NL +
                    "  AND  c.comments IS NOT NULL" + NL +
                    "UNION ALL" + NL +
                    "SELECT LOWER(c.table_name), '', c.comments" + NL +
                    "FROM   all_tab_comments c" + NL +
                    "WHERE  c.owner = '{DM_SCHEMA}' AND c.table_name IN ({DM_TABLES})" + NL +
                    "  AND  c.comments IS NOT NULL;</pre>" + NL +
                    "<p>导出选项必须这样设，少一项就会在下一步灌出乱码或错列：分隔符 <code>,</code>；" + NL +
                    "文本限定符 <code>\"</code>；换行 Windows 用 <code>\\r\\n</code>、Linux/mac 用" + NL +
                    "<code>\\n</code>；编码 UTF-8 无 BOM；含表头 <code>tn,cn,cm</code>。" + NL +
                    "<b>别用 Excel 打开后另存为 CSV</b>——它会改编码、吃掉引号转义，注释里的" + NL +
                    "换行和制表符就被破坏了。</p>" + NL +
                    "<p>B-③ 在 MySQL 建中转表，与 A-③ 同一条：</p>" + NL +
                    "<pre>DROP TABLE IF EXISTS tmp_dm_comment;" + NL +
                    "CREATE TABLE tmp_dm_comment (" + NL +
                    "  tn VARCHAR(128) NOT NULL, cn VARCHAR(128) NOT NULL," + NL +
                    "  cm TEXT NOT NULL, UNIQUE KEY uk_dmcm (tn, cn)" + NL +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci" + NL +
                    "  COMMENT='DM注释中转，用完删除';</pre>" + NL +
                    "<p>B-④ 灌入 CSV 并核对行数。<code>ESCAPED BY</code> 那一项要和导出时的文本限定符配套，" + NL +
                    "DBeaver 默认导出是双引号包字段、内部不做反斜杠转义，所以这里写" + NL +
                    "<code>ESCAPED BY ''</code>；如果它对注释里的引号做了 <code>\"\"</code> 式转义，" + NL +
                    "保持默认 <code>ESCAPED BY '\\\\'</code> 反而会多出一层反斜杠，先灌一张小表核对再铺量。" + NL +
                    "行数核对与 A-④ 同一条 SQL，两个数对不上就停：</p>" + NL +
                    "<pre>SET GLOBAL local_infile = 1;      -- 云库要先在控制台开；这里改不动就停，改走方案 A" + NL +
                    "LOAD DATA LOCAL INFILE '{CSV_PATH}'" + NL +
                    "INTO TABLE tmp_dm_comment" + NL +
                    "CHARACTER SET utf8mb4" + NL +
                    "FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"'" + NL +
                    "ESCAPED BY '\\\\'" + NL +
                    "LINES TERMINATED BY '\\r\\n'      -- Linux/mac 导出改成 '\\n'" + NL +
                    "IGNORE 1 LINES" + NL +
                    "(tn, cn, cm);</pre>" + NL +
                    "<p>B-⑤ 核对匹配数、B-⑥ 生成 ALTER、B-⑥补 生成特殊列的 <code>MODIFY COLUMN</code>、" + NL +
                    "B-⑦ 快照、B-⑧ 校验、B-⑨ 清理，这五步与方案 A 的 A-⑤ 到 A-⑨ <b>逐条相同</b>，" + NL +
                    "把上面 A-⑤、A-⑥、A-⑥补、A-⑦、A-⑧ 那五段原样贴过来执行即可；" + NL +
                    "差别只在于：方案 B 的注释是 CSV 原样搬过来的，换行、制表符、Emoji、生僻字都在，" + NL +
                    "所以生成 ALTER 时一律走 <code>QUOTE()</code>，不要自己拼引号。</p>" + NL +
                    "<p>方案 B 多出来的成本是要管一个文件（编码、行尾、路径），换来的是<b>注释内容不被" + NL +
                    "转义规则吃掉</b>，而且这份 CSV 可以直接当迁移凭证归档、按表分批补跑。</p>"
        ),
        new Section("log", "日志与报错",
                "<h2>日志与报错</h2>" + NL +
                    "<p>三个页签各有一个独立日志区（Excel 导入页底部、SQL 脚本改名页底部、" + NL +
                    "SQL 执行器页右半边的结果区），互不串扰，各自保留最近 2000 条，超出后丢弃最早的。" + NL +
                    "日志区的最小高度与默认分配见「界面布局」一节。</p>" + NL +
                    "<p>每条记录写成 <code>[年-月-日 时:分:秒] 符号 [级别] 正文</code>，四档级别与命令行版同一套标签，" + NL +
                    "时间戳也与命令行日志文件同一形状：</p>" + NL +
                    "<ul>" + NL +
                    "<li><code>+ [OK]</code> 绿色：完成（解析、加载、保存、提交、改名）</li>" + NL +
                    "<li><code>- [INFO]</code> 默认色：记录，不需要处理（开关切换、移除、清临时目录）</li>" + NL +
                    "<li><code>! [WARN]</code> 琥珀加粗：未执行但有原因（跳过、重复、取消、空包）</li>" + NL +
                    "<li><code>x [ERROR]</code> 红色加粗：失败，需要处理（解析失败、建表失败、连不上库）</li>" + NL +
                    "</ul>" + NL +
                    "<p>正文里并列的事实用 <code>|</code> 分段，补充说明缩进挂在下一行：</p>" + NL +
                    "<pre>[2026-10-23 14:05:12] + [OK] [已解析] 水库基础信息.xlsx → 表名预置 dim_reservoir_info | 字段 18 列 | 数据 5 行 | 主键 `BH`（整数类型，全列非空且唯一）" + NL +
                    "    说明 | 整列为空的字段照旧保留：备注" + NL +
                    "[2026-10-23 14:05:20] ! [WARN] [跳过] 已在列表：人员信息表.xlsx" + NL +
                    "[2026-10-23 14:05:31] - [INFO] ===== 全部完成：成功 2 张表，失败 1 张表 =====</pre>" + NL +
                    "<p>时间戳只打在每条记录的第一行；「SQL 执行器」结果表格里本身带缩进的行属于上一条记录，" + NL +
                    "给它们逐个打时间戳会把等宽表格顶歪。</p>" + NL +
                    "<p>报错不只打异常原文，而由 <code>describe_db_error()</code> 展开为" + NL +
                    "<b>异常类型 + MySQL 错误码 + 服务端原始报错全文</b>（pymysql 直接打印会是" + NL +
                    "<code>(1064, '…')</code> 这种元组，错误码与正文混在一起）：</p>" + NL +
                    "<pre>[2026-10-23 14:07:02] x [ERROR] [执行失败] a.sql 第 2/3 条语句报错，目标库 testdb@127.0.0.1:3306，本文件事务已回滚" + NL +
                    "    ProgrammingError（MySQL 错误码 1064）：You have an error in your SQL syntax; ..." + NL +
                    "    出错语句：" + NL +
                    "        INSERT INTO t_bad VALUE (1)" + NL +
                    "    前面 1 条语句已随事务回滚。注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交...</pre>" + NL +
                    "<h3>各场景的内容口径</h3>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>场景</th><th>日志内容</th></tr>" + NL +
                    "<tr><td>SQL 执行失败</td><td>文件名、第几条/共几条、目标库 <code>库名@主机:端口</code>、" + NL +
                    "错误码与原始报错、<b>出错语句全文</b>（超 2000 字符截断并标注原文长度，" + NL +
                    "避免超长批量 INSERT 占满日志）、回滚说明</td></tr>" + NL +
                    "<tr><td>SQL 连接失败</td><td>库名@主机:端口、用户名、错误码，并注明「剩余脚本均未执行」</td></tr>" + NL +
                    "<tr><td>Excel 建表失败</td><td>表名、目标库、错误码、<b>失败的完整建表 DDL</b></td></tr>" + NL +
                    "<tr><td>Excel 导入失败</td><td>表名、目标库、已尝试写入的数据行数、错误码、回滚说明</td></tr>" + NL +
                    "<tr><td>合并表头展开 / 合并单元格拒绝</td><td>展开：按几行压成一行；拒绝：文件名、合并区总数、" + NL +
                    "每个 Sheet 的位置清单、原因与处理建议</td></tr>" + NL +
                    "<tr><td>结尾汇总行</td><td>只要存在失败，整行按错误级标红</td></tr>" + NL +
                    "</table>" + NL +
                    "<p class=\"tip\">日志区右上角有<b>「清除日志」</b>按钮（SQL 页有独立的清除按钮）。" + NL +
                    "添加、导入、执行与生成的结果弹窗正文都可用鼠标选中后 Ctrl+C 复制。" + NL +
                    "弹窗正文不带日志行首的时间戳与级别——两处说的是同一句话，一个便于回溯、一个防止遗漏。</p>"
        ),
        new Section("summary", "添加结果汇总",
                "<h2>添加结果汇总</h2>" + NL +
                    "<p>无论是点击虚线区选文件、直接拖拽，还是复制文件后按 <code>Ctrl+V</code>，" + NL +
                    "添加完成后都会<b>把汇总同时打进日志区并弹窗</b>：日志可回溯、弹窗防止遗漏，两处文字逐字一致。" + NL +
                    "散文件与压缩包各占一行，只输出本次实际用到的行：</p>" + NL +
                    "<pre>添加 3 项文件，包含 2 项 xlsx 文件、1 项 xls 文件" + NL +
                    "添加 1 项 zip 文件，包含 12 项 xlsx 文件、0 项 xls 文件" + NL +
                    "另有 2 项解析失败、1 项重复已跳过，详见日志</pre>" + NL +
                    "<ul>" + NL +
                    "<li>xlsx / xls 的数量<b>只统计真正进入待导入列表的</b>，解析失败与重复跳过不计入，" + NL +
                    "因此弹窗数字与列表行数始终一致</li>" + NL +
                    "<li>「添加 N 项 zip 文件」的 N 指投入了几个包（压缩包本身不进列表，只是被展开）</li>" + NL +
                    "<li>第三行只在存在异常时出现，四类异常按 <code>、</code> 拼接，为 0 的不写：" + NL +
                    "<code>N 项含合并单元格已拒绝</code>、<code>N 项解析失败</code>、" + NL +
                    "<code>N 项重复已跳过</code>、<code>N 个压缩包无法展开</code>、" + NL +
                    "<code>N 个压缩包内没有 Excel</code>；全部成功时只有前两行，不会出现「另有」</li>" + NL +
                    "<li>只拖入空压缩包（包内没有 Excel）时也会弹窗，否则界面表现为无响应；" + NL +
                    "完全没有任何输入时不弹空窗</li>" + NL +
                    "<li>重复文件在日志里有对应行 <code>[重复跳过] xxx.xlsx 已在待导入列表</code>，数字可追溯</li>" + NL +
                    "<li>失败的具体原因（错误码、出错语句）始终在日志里标红，汇总只报数量不写细节；" + NL +
                    "汇总行出现在逐条 <code>[已解析]</code> / <code>[解析失败]</code> 之后</li>" + NL +
                    "</ul>"
        ),
        new Section("layout", "界面布局",
                "<h2>界面布局</h2>" + NL +
                    "<ul>" + NL +
                    "<li><b>MySQL 连接信息</b>区在三个页签<b>上方</b>，三页共用同一套连接参数与目标库选择，" + NL +
                    "不需要逐页填写。它平时收起成一条状态栏，只报当前是哪个库：上方留" + NL +
                    "「● 目标库 xxx」，主机 / 端口 / 用户名收在鼠标悬停里；点<b>「设置数据源信息」</b>展开填表、" + NL +
                    "测连接、换库，选定库后自动收起（展开时点<b>「收起」</b>也可手工收回）。" + NL +
                    "程序启动即为收起状态，尚无目标库时红色告警直接指向那颗按钮</li>" + NL +
                    "<li><b>三段高度可拖拽</b>：「Excel 导入」页的拖拽区 / 待导入文件表 / 日志区之间、" + NL +
                    "「SQL 脚本改名」页的拖拽区 / 待处理文件表格区 / 日志区之间，各有一条 6px 宽的横向分隔条，" + NL +
                    "按住即可上下拖动，一边变高另一边变窄。任何一段都不允许拖到 0，不会拖丢后找不回来。" + NL +
                    "三个页签的高度分配都<b>不写入配置</b>，重开程序回到初始比例</li>" + NL +
                    "<li><b>日志区有最小高度</b>（按日志那一档字号算四行）：把分隔条拖到极限也不会被压成一两行。" + NL +
                    "前两个分栏页的日志段、第三个页签的结果区<b>默认压在这一最小高度</b>，" + NL +
                    "主工作面（表格清单）拿剩余高度，窗口放大时多出来的高度也先给表格；" + NL +
                    "要更多日志就把分隔条往上拖，拖过之后程序不再改回。「SQL 执行器」页的" + NL +
                    "「编辑区与结果」默认压在自己的最小高度（编辑区四行），脚本清单拿剩余高度</li>" + NL +
                    "<li><b>「Excel 导入」页的表格区自上而下</b>：命名规则工具条（「表名和字段名取中文拼音首字母」" + NL +
                    "与「表名与字段名转大写」「表名与字段名转小写」三颗开关，后两颗互斥）→ 待导入文件表 →" + NL +
                    "表名前缀行 →" + NL +
                    "「清空待上传文件」「生成表结构预览」「预览 DDL」「统计数据总行数」四颗次级按钮等宽排成一行。" + NL +
                    "表格按行交替底色（斑马纹）便于横向对齐阅读，第一列「序号」只占 49px，" + NL +
                    "把宽度留给文件、表名与注释。三颗开关控制的是命名方式，因此贴在表格上沿而不挤在底部执行区。" + NL +
                    "待导入文件表的列宽可自由拖动，最后一列自动填满剩余空间</li>" + NL +
                    "<li><b>底部动作条是固定件</b>，不参与挤压：与上方内容的交界处有一条细分隔线，" + NL +
                    "左列是「覆盖已存在表」与「自动补全主键」两颗勾选框，右侧依次是「停止」「开始导入」「生成标准excel文件」" + NL +
                    "「生成简易excel文件」——「开始导入」用强调色，其余保持普通样式，一屏内只有这一个视觉重点。" + NL +
                    "页底没有第二条进度条，进度由任务自己的进度框与日志交代</li>" + NL +
                    "<li><b>「SQL 脚本改名」页</b>的表格区同样是命名工具条（大小写两颗互斥，不提供拼音首字母）→" + NL +
                    "待处理的脚本表 → 「表名前缀」行（输入框 + 两颗按钮）→ 四颗次级按钮一行，" + NL +
                    "与「Excel 导入」页那四颗同位置、同形状、同文案，且都随表格一起缩放。" + NL +
                    "「覆盖已存在表」「自动补全主键」两颗勾选框与保存/执行/生成四颗按钮在底部动作条，开关在左、按钮贴右，" + NL +
                    "「执行修改后ddl脚本到当前库」用强调色，「保存修改后ddl脚本」用次级样式。" + NL +
                    "「清除日志」在日志框上方贴右。表格同样带斑马纹，第一列「序号」只占 49px</li>" + NL +
                    "<li><b>「SQL 执行器」页三段上下排</b>：拖拽区 / 脚本清单 / 编辑区与结果，段间各一条 6px" + NL +
                    "横向分隔条；拖拽区按第一页口径只占固定一份，清单与下半区同权。清单那一格下沿一排两颗按钮——" + NL +
                    "左「清空待执行脚本」、右「批量执行脚本文件」（强调色），随清单一起缩放。" + NL +
                    "下半区左右分栏：SQL 文本与执行结果之间一条 6px 竖向分隔条，两边都不允许拖到 0 宽。" + NL +
                    "左右两栏头一行是固定件：左栏「SQL 文本」+「示例 (?)」+「格式化 SQL」（次要样式，" + NL +
                    "排在「示例 (?)」右侧）+「清空编辑区」+" + NL +
                    "「执行编辑区 SQL」（次要样式，排在「清空编辑区」右侧），右栏「执行结果」+ 结果集下拉 +" + NL +
                    "「清空结果区」。这一页没有底部动作条，两段直接铺到窗口底部；" + NL +
                    "初开时下半区压到最小高度、清单拿剩余高度，空清单中央的图标与引导语才看得全</li>" + NL +
                    "<li><b>三页一致的视觉引导</b>：拖拽区常驻淡蓝底色（<code>#eaf2fd</code>）与虚线描边，" + NL +
                    "鼠标移上去底色加深（<code>#d8e8ff</code>）、描边转为强调蓝，不必试探才知道这里能放文件；" + NL +
                    "清单为空时表格中央画一个「托盘 + 下箭头」图标与一句「请把 … 拖到上方虚线区」，" + NL +
                    "形状与文案都指向正上方那块区域，加入第一行后自动消失。第一、二页底部动作条包在同一块" + NL +
                    "浅色圆角底板（<code>#f4f7fc</code>）里，两颗执行选项与执行按钮同处一块，" + NL +
                    "读起来是一组执行前确认（第三页没有这条动作板，两颗执行按钮各归脚本清单与编辑区两段）</li>" + NL +
                    "<li><b>每张表格左上角都有一句标识</b>：第一页「待导入文件（双击表名或表注释编辑，双击其他列改字段）」、" + NL +
                    "第二页「待处理的脚本表（双击表名或表注释编辑，双击其他列看元数据）」、" + NL +
                    "双击打开的元数据弹窗「字段清单（共 N 列）」——标题同时说明该表的操作方式。" + NL +
                    "结构预览弹窗不重复加标识：每个页签上写的就是表名，已贴在表格正上方</li>" + NL +
                    "<li><b>表格内容比日志大一档</b>（两栈同一组数：表格 11 号、日志 10 号，与 Excel 数据网格同级）：" + NL +
                    "本页真正要读的是表格里的字段名，日志只是过程回执，因此字号给内容让。" + NL +
                    "表格内容与表头同档，行高按字体量出，不写死像素</li>" + NL +
                    "<li><b>界面字体随包带一份</b>：两条线都用随包的思源黑体（Noto Sans SC，SIL OFL 许可随包），" + NL +
                    "不跟系统挑字体——客户机器上装了什么字体都影响不到中文能不能看见，同一份文字在两台机器上" + NL +
                    "也是同一支字。日志、SQL 编辑区与结果区仍是等宽那一档。窗口开局会在日志里写一句" + NL +
                    "<code>[界面] 字体：…（可显示中文）</code>，界面文字看着不对时先抄这一行</li>" + NL +
                    "<li><b>右上角两个小按钮</b>：「?」打开本使用说明，「i」打开「关于」" + NL +
                    "（版本号 / 提交号 / 构建日期 / 作者四行，提交号可鼠标选中复制）。" + NL +
                    "「关于」里<b>作者那一格</b>本身就是仓库链接：名字右侧一枚很小的外链箭头，单击用系统默认" + NL +
                    "浏览器打开仓库；本机打不开浏览器时（内网、没装浏览器、被安全软件拦下），地址会写进剪贴板，" + NL +
                    "那一格照实改成「打不开浏览器，地址已复制」，不会点了没反应。" + NL +
                    "「关于」底下那颗<b>「复制全部」</b>一次把这五行放进剪贴板，提交号给的是 <b>40 位完整值</b>、" + NL +
                    "仓库给的是完整地址（正面那一格仍是短号，够人眼看；贴出去的要能直接粘进 git 里查），" + NL +
                    "点下去按钮变「已复制」两秒后复原；" + NL +
                    "系统剪贴板被占着或这台机器没桌面会话时，按钮会照实说「剪贴板打不开」，不会假装复制过了。" + NL +
                    "「关于」里的提交号与构建日期是<b>打包那一刻写进 exe</b> 的（exe 所在机器没有 git，" + NL +
                    "运行时查不到）；开发态直接跑 <code>python main.py</code> 时现查 git，" + NL +
                    "查不到就显示「开发态（未打包）」，不会编造提交号。" + NL +
                    "这几行都不挂悬浮提示，上面这段就是那套来源说明的落点</li>" + NL +
                    "<li><b>「Excel 导入」页的待导入文件表</b>：第一列「序号」从 1 连续编到最后一行，" + NL +
                    "行数即当前待导入表的数量；双击<b>表名</b>改这张表的名字，回车生效，" + NL +
                    "空名、超过 64 字符、含非法字符或与其他表重名会被拦下并恢复原名；" + NL +
                    "双击<b>表注释</b>改注释，建表那句 <code>COMMENT='…'</code> 按新注释生成，" + NL +
                    "清空即不给注释，与第二个页签改表注释是同一个动作；" + NL +
                    "双击<b>文件 / 字段数 / 数据行数 / 主键</b>任一列查看该表字段结构；" + NL +
                    "按 <b>Del</b> 把选中行从待导入清单删掉，多选即批量删——只出清单，不动磁盘上的原文件。" + NL +
                    "未选中任何行时按 Del 会在日志说一句；有任务正在解析这批文件时按 Del 会被拦下并说明在等待哪个任务。" + NL +
                    "来自 ZIP 的文件，鼠标悬停「文件」列显示完整来源，形如" + NL +
                    "<code>数据打包.zip!北京/水库基础信息.xlsx</code>；" + NL +
                    "导入结束后的结论由弹窗与日志交代（成功几张、失败几张、各自为什么）；" + NL +
                    "<b>导入完成或失败的文件都留在清单里</b>——成功的不会被悄悄抽走，因为多数人还要接着" + NL +
                    "生成交付件或改个名字再导一遍；要清就自己清：" + NL +
                    "整批清走点「清空待上传文件」，只想删几行就选中后按 Del，" + NL +
                    "两条路都只动清单、不动磁盘上的原文件</li>" + NL +
                    "<li><b>按钮区可以只对选中的行生效</b>（两页同一口径）：在表格里选中几行，" + NL +
                    "「表结构预览」「预览 DDL」「统计数据总行数」、两颗前缀按钮、两颗生成按钮与「开始导入 / 执行」" + NL +
                    "就只作用在这几行上；<b>一个都没选时仍按全量走</b>，不必先全选一次。" + NL +
                    "底部动作条左侧那句小字会写着此刻的范围（<code>范围：全量（N 张表）</code> 或" + NL +
                    "<code>范围：选中 N 张表</code>），日志里也会补一行" + NL +
                    "<code>[范围] 本次只作用于选中的 N 张表，没选中的一概不动</code>。" + NL +
                    "两处例外要看清楚：<b>保存按脚本文件落盘</b>，一份脚本里只要有一张表动过，" + NL +
                    "写出的就是整份正文（没动过的表内容一模一样）；<b>导入/执行按脚本文件事务</b>，" + NL +
                    "选中几张表时程序会先把每份脚本裁到这几张表的语句，裁完一句不剩的文件跳过并点名</li>" + NL +
                    "<li><b>「SQL 脚本改名」页的清单</b>从左到右是<b>序号 / 文件 / 原表名 / 当前表名 / 表注释 / 数据行数</b>，" + NL +
                    "第一列同样是连续序号，最后一个序号即这批脚本解析出的建模语句总数（多个文件共用一套编号）；" + NL +
                    "「表注释」取脚本里 <code>CREATE TABLE ... COMMENT='…'</code> 那句，与第一页同含义，" + NL +
                    "脚本里没写就不留空占位；「数据行数」是脚本里属于这张表的" + NL +
                    "<code>INSERT</code> / <code>REPLACE INTO</code> 数据行数（多条语句连着累加，" + NL +
                    "没有 INSERT 的表记 0），读入脚本时就算好并跟着改名走，与「统计数据总行数」那颗按钮" + NL +
                    "（以及「Excel 导入」页同名列、同名按钮）同一个口径；双击<b>当前表名</b>或<b>表注释</b>编辑，" + NL +
                    "双击其余列打开该表元数据</li>" + NL +
                    "<li><b>弹窗正文一定会被折进窗口里</b>：提示、警告、出错三类弹窗显示的就是那段原文，" + NL +
                    "折行由正文控件按<b>内容区右边界</b>完成——长路径、整段异常信息都不会把窗口撑到屏幕外，" + NL +
                    "拉大弹窗文字会重新排版，不会空着半幅宽却提前换行。正文超出窗口时框内出滚动条，" + NL +
                    "整份都翻得到。弹窗<b>按正文所需给尺寸</b>（宽 420~880、高 170~640，再夹进当前屏幕）：" + NL +
                    "一句提示就是一张小窗，不会摊开半屏空白，也不会带一条用不上的滚动条</li>" + NL +
                    "<li>各窗口（主窗口、本说明、「关于」、表结构预览、DDL 预览、进度框）的尺寸按<b>客户区</b>给定，" + NL +
                    "并在打开前按当前屏幕可用区夹一次：1024x768 的屏幕上整窗加标题栏仍在屏内，" + NL +
                    "底部按钮不会被任务栏吃掉</li>" + NL +
                    "</ul>"
        ),
        new Section("faq", "常见问题",
                "<h2>常见问题</h2>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>现象</th><th>原因与处理</th></tr>" + NL +
                    "<tr><td>在 <b>Windows 7 / 8</b> 上双击 exe 打不开，提示「找不到指定的程序 / 入口点」</td>" + NL +
                    "<td>图形版要求 <b>64 位 Windows 10（1809 及以上）或 Windows 11</b>：界面用的 Qt 6 不支持" + NL +
                    "Win7/8.1，Python 3.9+ 与打包器也不支持，这是<b>补装依赖解决不了</b>的硬限制。" + NL +
                    "两种办法：① <b>导入不必在 Win7 本机完成</b>——任意一台 Win10/11 电脑跑本工具即可，" + NL +
                    "只要网络能连上目标 MySQL，Excel 放共享目录一样能读；" + NL +
                    "② 只能在 Win7 上跑时改用同源的<b>命令行版 <code>excel2mysql-cli.exe</code></b>" + NL +
                    "（解析、推断、建表、导入共用同一套 core 逻辑，建出来的表结构一致），" + NL +
                    "用法见包内 <code>README.md</code> 的「Windows 7 命令行版」一章</td></tr>" + NL +
                    "<tr><td>提示 <code>1054 Unknown column</code></td>" + NL +
                    "<td>字段命名规则变更后列名与旧表不一致。勾「覆盖已存在表」重建，" + NL +
                    "或在表结构预览里改回旧列名；老数据表先备份</td></tr>" + NL +
                    "<tr><td>提示 <code>Row size too large</code></td>" + NL +
                    "<td>已内置处理：VARCHAR 行内字节预算不足时自动退为 TEXT；仍过不了线（定长列堆满半页、" + NL +
                    "列数超 1017）时，导入页会按<b>公共字段</b>把这张表拆成几个 Excel，日志逐段写明每段列数" + NL +
                    "与文件名，落在原文件旁边，把这几份拖回来就是几张能分别建的表</td></tr>" + NL +
                    "<tr><td>表没建但数据报主键冲突 <code>1062</code></td>" + NL +
                    "<td>表已存在且默认跳过建表，旧数据还在。勾「覆盖已存在表」或先 TRUNCATE</td></tr>" + NL +
                    "<tr><td>文件被拒绝，提示含合并单元格</td>" + NL +
                    "<td>在 Excel 中取消所有合并区后重新添加，日志会列出具体位置</td></tr>" + NL +
                    "<tr><td>拖了 ZIP 但列表没变</td>" + NL +
                    "<td>看日志：可能包内没有 Excel、是嵌套包（不递归展开），或包已加密</td></tr>" + NL +
                    "<tr><td>拖 ZIP 报「压缩包无法打开」「写入 … 失败」「已加密」</td>" + NL +
                    "<td>日志那句带着系统原话，按那句分头处理：<b>已加密</b>会点名是哪一条条目，先解密再拖；" + NL +
                    "<b>解压后的路径过长</b>是把包拷到一个短路径目录（如 <code>D:\\data</code>）再拖，" + NL +
                    "或改短环境变量 <code>TEMP</code>；中文条目名的包（Windows「发送到压缩文件夹」那种）" + NL +
                    "与条目数超 65535 的 ZIP64 大包都已支持，不需要换工具重打包</td></tr>" + NL +
                    "<tr><td>关掉程序后想重试 ZIP 里的失败文件</td>" + NL +
                    "<td>展开出的临时文件已随窗口关闭清理，需重新拖入压缩包</td></tr>" + NL +
                    "<tr><td>金额列被建成 INT 丢了小数</td>" + NL +
                    "<td>表头含金额语义的列已自动用 <code>DECIMAL(16,2)</code>；" + NL +
                    "若列名不含这些关键词，在表结构预览里手工改成 DECIMAL</td></tr>" + NL +
                    "<tr><td>双击 exe 没反应 / 杀毒软件拦截</td>" + NL +
                    "<td>未签名的 exe 可能被 SmartScreen 提示，选「仍要运行」即可。" + NL +
                    "onedir 版启动约 1-3 秒；exe 必须与 <code>_internal</code> 文件夹放在一起</td></tr>" + NL +
                    "<tr><td>导入一半想停</td><td>点<b>「停止」</b>，当前表处理完即中止，已提交的不会回滚</td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>事务语义</h3>" + NL +
                    "<p>每张表 / 每个手写 SQL 文件是一个独立事务：成功才提交，失败整体回滚并继续下一项。" + NL +
                    "但 MySQL 的 DDL（CREATE/DROP/ALTER）会隐式提交、无法回滚——这是数据库的限制，" + NL +
                    "日志里每次回滚都会提醒这一点。整库 dump 走逐条提交那条通道，原因见「SQL 执行器页」一节。</p>"
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
