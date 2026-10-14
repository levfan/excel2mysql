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
                    "<tr><td>1</td><td>在上方<b>「MySQL 连接信息」</b>填主机、端口、用户名、密码</td></tr>" + NL +
                    "<tr><td>2</td><td>点<b>「连接并加载库」</b>，在「目标库」下拉里选库</td></tr>" + NL +
                    "<tr><td>3</td><td>切到<b>「Excel 导入」</b>页签，把文件拖进虚线区，或点<b>「添加文件」</b></td></tr>" + NL +
                    "<tr><td>4</td><td>需要统一命名时，填<b>表名前缀</b>后点<b>「批量应用前缀」</b>（加一段）" + NL +
                    "或<b>「替换首个前缀」</b>（换掉已有首段，见「表名前缀：加与换」）</td></tr>" + NL +
                    "<tr><td>5</td><td>点<b>「生成表结构预览」</b>核对/修改字段名、类型、主键</td></tr>" + NL +
                    "<tr><td>6</td><td>点<b>「预览 DDL」</b>确认建表语句，然后<b>「开始导入」</b></td></tr>" + NL +
                    "<tr><td>7</td><td>看底部日志的逐表结果</td></tr>" + NL +
                    "</table>" + NL +
                    "<p class=\"tip\">两个页签顶部各有一条<b>当前目标库提示条</b>：绿色显示" + NL +
                    "<code>当前目标库：库名（主机:端口）</code>，没选库时是红色告警。切库、改端口都会实时刷新，" + NL +
                    "导入前扫一眼能确认没写错库。</p>"
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
                    "<h3>界面功能：图形版与 Java 版等价</h3>" + NL +
                    "<p>两边都有这些动作：拖文件与「添加文件」、ZIP 展开、<code>.xlsx</code> 与" + NL +
                    "<code>.xls</code>、拼音表名、「批量应用前缀」与「替换首个前缀」、" + NL +
                    "「生成表结构预览」（可编辑字段名、类型与主键）、「预览 DDL」、" + NL +
                    "「SQL 脚本」页的保存与执行、「生成标准excel文件」与「生成简易excel文件」、" + NL +
                    "耗时任务的进度框，以及这份《使用说明》（右上角「?」或 F1）。</p>" + NL +
                    "<p>这份等价是逐项称出来的，不是估计：两条线的窗口控件、文案、按钮启用条件、" + NL +
                    "弹窗文字、日志与写出的文件会被各自枚举成一份清单，再逐键比对，共 191 项。</p>" + NL +
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
                    "<code>--swap-prefix</code>（替换首个前缀）、<code>--drop-if-exists</code>、" + NL +
                    "<code>--batch</code>、<code>--log</code>、<code>--quiet</code>、" + NL +
                    "<code>--no-recursive</code>、<code>--keep-temp</code>。</li>" + NL +
                    "<li>Java 版：动作由路径类型决定——给 <code>.sql</code> 就执行脚本，" + NL +
                    "给目录两类都干，用 <code>--no-sql</code> / <code>--no-excel</code> 各自排除，" + NL +
                    "没有 <code>--run-sql</code> 这个开关。连接信息走同级 <code>conn.ini</code>" + NL +
                    "或环境变量 <code>E2M_HOST / E2M_PORT / E2M_USER / E2M_PASS / E2M_DB</code>，" + NL +
                    "选项只有 <code>--dry-run --check --prefix -c --debug --gui --version</code>。</li>" + NL +
                    "<li>「生成标准excel文件」与「生成简易excel文件」这两件只在界面上有，" + NL +
                    "两条命令行线都没有对应的动作。</li>" + NL +
                    "</ul>"
        ),
        new Section("files", "支持哪些文件",
                "<h2>支持哪些文件</h2>" + NL +
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
                    "<h3>含合并单元格的表：直接拒绝导入</h3>" + NL +
                    "<p>合并区只有左上角那一格有值，其余格读进来是空值——表头被合并会打乱列对应关系，" + NL +
                    "数据行被合并会静默丢值。所以这类文件<b>一律不导入、也不进入待导入列表</b>，" + NL +
                    "日志会标红列出具体位置，请回 Excel 里取消合并后重新添加：</p>" + NL +
                    "<pre>[合并单元格] 两页都合并.xlsx 含 3 处合并区，已拒绝导入，未进入待导入列表" + NL +
                    "    检测到合并单元格：「字段定义」1 处（A1:G1）；「数据」2 处（A1:B1、A2:B2）</pre>" + NL +
                    "<p>两种情形都覆盖：两个 Sheet（元数据页 + 数据页）任一页有合并区 → 整个文件拒绝；" + NL +
                    "只有一个数据页且页内有合并区 → 同样拒绝。ZIP 里只有某个文件有合并区时，" + NL +
                    "只拒绝那一个，同包其他干净文件正常导入。</p>"
        ),
        new Section("progress", "进度框与耗时",
                "<h2>进度框与耗时</h2>" + NL +
                    "<p>几十万行的大表解析、导入、读写脚本都很耗时，这些操作会弹一个<b>模态进度框</b>：" + NL +
                    "真正干活的都在后台线程，界面始终能刷新、能点、能取消，不会\"假死\"。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>操作</th><th>进度怎么算</th></tr>" + NL +
                    "<tr><td>添加文件 / 拖入文件</td>" + NL +
                    "<td>每个压缩包 1 项；包内每个 Excel 解出占 1 项；每个待解析 Excel 按 5 个阶段各占 1 项" + NL +
                    "（合并检测 / 读取工作簿 / 识别格式 / 推断类型 / 选主键）。当前项会显示到" + NL +
                    "「文件名：读取工作簿」这一级，所以单个几十万行的大表解析途中进度条也在走</td></tr>" + NL +
                    "<tr><td>开始导入</td><td>每张表 1 项 + 每个数据行 1 项，几十万行也能看到连续推进</td></tr>" + NL +
                    "<tr><td>批量应用前缀 / 替换首个前缀（Excel 页）</td>" + NL +
                    "<td>每张表 1 项 + 重名去重 1 项</td></tr>" + NL +
                    "<tr><td>添加 SQL 文件 / 批量应用前缀 / 替换首个前缀（SQL 页）</td>" + NL +
                    "<td>每个文件 1 项（读盘、扫描、改写都在后台）</td></tr>" + NL +
                    "<tr><td>保存修改后脚本</td><td>每个文件 1 项</td></tr>" + NL +
                    "<tr><td>执行脚本到当前库</td><td>每条语句 1 项，按块推进（大脚本一个文件最多约 100 次刷新，" + NL +
                    "避免海量刷新反而拖慢界面）</td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>取消会发生什么</h3>" + NL +
                    "<ul>" + NL +
                    "<li>取消在<b>当前这一项做完</b>时生效，不会硬切留下半截数据</li>" + NL +
                    "<li>解析中途取消：已解析出的表照常进入待导入列表，弹窗首行标注" + NL +
                    "<code>已取消，只完成了部分解析</code></li>" + NL +
                    "<li>SQL 执行中途取消：该文件的事务<b>回滚不提交</b>（DDL 隐式提交的部分除外），后面的文件不再执行</li>" + NL +
                    "<li>导入中途取消：等同底部的「停止」——当前表处理完即中止，已提交的表不会回滚</li>" + NL +
                    "</ul>" + NL +
                    "<li><b>任务结束后不留残留</b>：导入完成（或失败、被中止）后，底部的进度条会" + NL +
                    "<b>自动回到 0%</b>，不会停在 100% 让人分不清那是上一批的尾巴还是这一批的进度；" + NL +
                    "「清空列表」同样会把进度条一起归零。结果本身由弹窗与日志交代</li>" + NL +
                    "<p class=\"tip\">进度框没有标题栏关闭按钮，只能点「取消」或等它自己完成，" + NL +
                    "避免后台线程还在跑就把窗关掉。工作函数整体抛异常时框照样会关闭，" + NL +
                    "并在日志里红色打 <code>[任务异常]</code>。</p>"
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
                    "只由字母数字下划线组成）才采用。以下情况一律回退到\"整个文件名转拼音\"：" + NL +
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
                    "<tr><td><code>人员信息表（v2）</code></td><td><code>v2</code></td>" + NL +
                    "<td>字母开头、含字母 → 合法，直接采用</td></tr>" + NL +
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
                    "<li>全空行 / 全空列自动剔除</li>" + NL +
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
                    "每次按日期建一个子目录。</p>" + NL +
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
                    "「已取消，剩余文件未生成」。</p>"
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
                    "<tr><td>超长文本</td><td>超 16383 字节 → <code>TEXT</code>，超 65535 字节 → <code>MEDIUMTEXT</code></td></tr>" + NL +
                    "</table>" + NL +
                    "<h3>几个容易踩的点已经处理掉了</h3>" + NL +
                    "<ul>" + NL +
                    "<li><b>金额语义识别</b>：Excel 里 <code>100</code> 常以 <code>100.0</code> 存储，" + NL +
                    "纯值判定会把\"订单金额\"判成 INT 砍掉小数。所以加了一层表头语义修正——" + NL +
                    "命中金额关键词（金额/单价/价格/费用/成本/余额/工资/收入/支出/税额/利息/销售额/" + NL +
                    "保证金…，英文 amount/price/cost/fee/salary/balance…）的数值列一律" + NL +
                    "<code>DECIMAL(16,2)</code></li>" + NL +
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
                    "<p>「SQL 脚本」页同样有这两个按钮，<b>替换时会同步改写</b>脚本里" + NL +
                    "<code>CREATE / DROP / INSERT INTO / ALTER / TRUNCATE</code> 对同一张表的引用，" + NL +
                    "表格里的「原表名」一列仍显示最初的名字，方便核对。</p>"
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
        new Section("sqlpage", "SQL 脚本页",
                "<h2>SQL 脚本页（第二个页签）</h2>" + NL +
                    "<p>用来处理现成的 <code>.sql</code> 建表脚本：批量改表名、执行到目标库。</p>" + NL +
                    "<table class=\"t\">" + NL +
                    "<tr><th>按钮</th><th>作用</th></tr>" + NL +
                    "<tr><td>添加 SQL 文件</td><td>可多选，自动统计脚本里所有" + NL +
                    "<code>CREATE TABLE [IF NOT EXISTS] `表名`</code> 并提取表名" + NL +
                    "（兼容反引号 / 裸名 / <code>schema.表名</code> 限定名，按首次出现顺序展示出现次数；" + NL +
                    "<code>INSERT INTO</code> 等不计入）</td></tr>" + NL +
                    "<tr><td>批量应用前缀</td><td>把提取到的表名统一加前缀：带反引号的引用" + NL +
                    "（含 DROP/INSERT/ALTER）全部同步改写；裸表名只在语句头位置改写；" + NL +
                    "已带该前缀的自动跳过，<b>重复应用不会叠加</b></td></tr>" + NL +
                    "<tr><td>替换首个前缀</td><td>把每个表名第一个下划线之前的段落换成输入框里的内容" + NL +
                    "（<code>ods_t_order</code> → <code>change_t_order</code>），引用一并同步改写；" + NL +
                    "表名里没有下划线的不动，重复点不会叠加</td></tr>" + NL +
                    "<tr><td>保存修改后脚本</td><td>改写发生在内存中，确认后另存为" + NL +
                    "<code>原名_prefix.sql</code>，落在源文件旁边，<b>不覆盖原文件</b></td></tr>" + NL +
                    "<tr><td>执行脚本到当前库</td><td>按上方公共连接区选定的目标库执行；" + NL +
                    "<b>每个文件一个事务</b>：先开启事务，逐条执行，任一语句失败立即回滚整个事务" + NL +
                    "并在日志标红报错，后续语句不再执行，全部成功才提交</td></tr>" + NL +
                    "</table>" + NL +
                    "<ul>" + NL +
                    "<li>语句切分能正确跳过字符串 / 反引号 / 注释中的分号</li>" + NL +
                    "<li><b>不支持</b>含 <code>DELIMITER</code> 的存储过程脚本</li>" + NL +
                    "<li>文件编码自动尝试 UTF-8 / UTF-8-BOM / GBK</li>" + NL +
                    "<li>注意 MySQL 的 <code>CREATE/DROP/ALTER</code> 等 DDL 会<b>隐式提交</b>，" + NL +
                    "无法随事务回滚；回滚只对其后的数据操作生效</li>" + NL +
                    "<li>Excel 导入同样是事务语义：每张表先开启事务，数据插入成功后提交，" + NL +
                    "失败整体回滚并记录日志</li>" + NL +
                    "</ul>"
        ),
        new Section("log", "日志与报错",
                "<h2>日志与报错</h2>" + NL +
                    "<p>两个页签各有一个独立日志区（Excel 导入页底部、SQL 脚本页底部），" + NL +
                    "各自保留最近 2000 条，超出自动丢弃最早的。</p>" + NL +
                    "<p><b>所有失败记录整条红色加粗</b>，成功与信息行保持默认颜色，扫一眼就能定位问题。" + NL +
                    "报错不只打异常原文，而是展开成" + NL +
                    "<b>异常类型 + MySQL 错误码 + 服务端原始报错全文</b>：</p>" + NL +
                    "<pre>[执行失败] a.sql 第 2/3 条语句报错，目标库 testdb@127.0.0.1:3306，本文件事务已回滚" + NL +
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
                    "<tr><td>合并单元格拒绝</td><td>文件名、合并区总数、每个 Sheet 的位置清单、原因与处理建议</td></tr>" + NL +
                    "<tr><td>结尾汇总行</td><td>只要存在失败，整行标红</td></tr>" + NL +
                    "</table>" + NL +
                    "<p class=\"tip\">日志区右上角有<b>「清除日志」</b>按钮（SQL 页有自己独立的清除按钮）。</p>"
        ),
        new Section("summary", "添加结果汇总",
                "<h2>添加结果汇总</h2>" + NL +
                    "<p>无论走「添加文件」按钮还是拖拽，添加完成后都会<b>把汇总同时打进日志区并弹窗</b>——" + NL +
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
                    "<li><b>MySQL 连接信息</b>区在两个页签<b>上方常驻</b>，两页共用同一套连接参数和目标库选择，" + NL +
                    "不需要在每个页签里分别填</li>" + NL +
                    "<li><b>「Excel 导入」页的三段高度可拖拽</b>：拖拽区、待导入文件表、日志区之间有" + NL +
                    "6px 宽的横向分隔条，鼠标按住即可上下拖动重新分配高度。" + NL +
                    "三段都不允许拖到 0 高，不会拖丢了找不回来。" + NL +
                    "提示条和底部动作条（覆盖勾选框、停止、开始导入、生成标准excel文件、" + NL +
                    "生成简易excel文件、进度条）是固定件，不参与挤压。" + NL +
                    "高度分配不写配置，重开程序回到初始比例</li>" + NL +
                    "<li><b>「SQL 脚本」页也能拖拽分高</b>：待处理文件表格区与日志区之间一条 6px 分隔条，" + NL +
                    "一边变高另一边就变窄。日志很多时把下半区拉大；要核对表名清单时把表格拉大。" + NL +
                    "「表名前缀」行与操作条跟着表格一起缩放，提示条和「添加 SQL 文件」按钮是固定件</li>" + NL +
                    "<li>两个页签的高度分配都<b>不写入配置</b>，重开程序回到初始比例</li>" + NL +
                    "<li>待导入文件表的<b>列宽可自由拖动</b>，最后一列自动填满剩余空间</li>" + NL +
                    "<li>右上角有两个小按钮：<b>「?」</b>打开本使用说明，<b>「i」</b>打开「关于」" + NL +
                    "（版本号 / 提交号 / 构建日期三行，提交号可鼠标选中复制）</li>" + NL +
                    "<li>「关于」里的提交号与构建日期是<b>打包那一刻烧进 exe</b> 的（exe 所在机器没有 git，" + NL +
                    "运行时查不到）；开发态直接跑 <code>python main.py</code> 时现查 git，查不到就显示" + NL +
                    "\"开发态（未打包）\"，不会编一个假提交号</li>" + NL +
                    "<li>双击表格里的<b>表名 / 字段</b>可直接编辑</li>" + NL +
                    "<li>来自 ZIP 的文件，鼠标悬停「文件」列会显示完整来源，形如" + NL +
                    "<code>数据打包.zip!北京/水库基础信息.xlsx</code></li>" + NL +
                    "<li>导入成功的文件会自动从待导入列表移除，<b>失败的文件保留</b>，可以调整后重试；" + NL +
                    "单表失败不阻断其余表</li>" + NL +
                    "<li><b>弹窗里的文字一定会被折进窗口里</b>：提示、警告、出错这三类弹窗的正文按固定宽度" + NL +
                    "折行（一行约 28 个汉字），一行长路径、一整段异常信息都不会把窗口撑到屏幕外。" + NL +
                    "正文超过 22 行时弹窗只留前 21 行并写明「其余 N 行放不下，完整内容见底部日志」，" + NL +
                    "日志里始终是完整的那一份</li>" + NL +
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
