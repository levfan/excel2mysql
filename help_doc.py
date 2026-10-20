"""使用说明对话框的正文内容。

内容与代码行为一一对应，改功能时同步改这里。
每节是 (锚点id, 侧栏标题, HTML 正文)，由 ui_help.HelpDialog 渲染成
左侧目录 + 右侧正文的可读排版。
"""

SECTIONS = [
    ("start", "快速上手", """
<h2>快速上手</h2>
<p>这个工具把一批 <b>Excel 表</b>（或含 Excel 的 <b>ZIP 压缩包</b>）批量导入 MySQL：
自动推断表结构、生成建表语句、写入数据，导入前可以逐列预览和修改。</p>
<table class="t">
<tr><th>步骤</th><th>做什么</th></tr>
<tr><td>1</td><td>在上方<b>「MySQL 连接信息」</b>填主机、端口、用户名、密码</td></tr>
<tr><td>2</td><td>点<b>「连接并加载库」</b>，在「目标库」下拉里选库</td></tr>
<tr><td>3</td><td>切到<b>「Excel 导入」</b>页签，把文件拖进虚线区，或点<b>「添加文件」</b></td></tr>
<tr><td>4</td><td>需要统一命名时，填<b>表名前缀</b>后点<b>「批量应用前缀」</b>（加一段）
或<b>「替换首个前缀」</b>（换掉已有首段，见「表名前缀：加与换」）</td></tr>
<tr><td>5</td><td>点<b>「生成表结构预览」</b>核对/修改字段名、类型、主键</td></tr>
<tr><td>6</td><td>点<b>「预览 DDL」</b>确认建表语句，然后<b>「开始导入」</b></td></tr>
<tr><td>7</td><td>看底部日志的逐表结果</td></tr>
</table>
<p class="tip">三个页签顶部各有一条<b>当前目标库提示条</b>：绿色显示
<code>当前目标库：库名（主机:端口）——…</code>，没选库时是红色告警。三条同源刷新，
切库、改主机端口会一起变，不会在某个页签停在"上一个库"；最后一句按页分工写：
Excel 页说"数据将导入到这个库"，「SQL 脚本改名入库」页说"脚本将执行到这个库"，
「SQL 执行器」页说"编辑区里的 SQL 将执行到这个库"。导入前扫一眼能确认没写错库。</p>
"""),

    ("lines", "三条线与选型", """
<h2>三条线与选型</h2>
<p>同一个工具现在有三份可交付的程序：解析、类型推断与建表逻辑同源，
差别在有没有界面、能跑在什么系统上、以及命令行选项集。</p>
<table class="t">
<tr><th>交付线</th><th>入口</th><th>能跑的系统</th><th>界面</th><th>命令行</th></tr>
<tr><td>图形版（Python）</td><td><code>excel2mysql.exe</code></td>
<td>64 位 Windows 10 1809+ / Windows 11</td><td>有</td><td>无</td></tr>
<tr><td>命令行版（Python，Windows 7 专用）</td><td><code>excel2mysql-cli.exe</code></td>
<td>Windows 7 SP1 及以上，且系统 UCRT 补丁与系统文件完整</td><td>无</td><td>有</td></tr>
<tr><td>Java 版</td><td><code>启动.bat</code> 或
<code>java -jar excel2mysql-*.jar</code></td>
<td>64 位 Windows 7 及以上；包内自带 Java 运行时，目标机器不用装任何东西</td>
<td>有</td><td>有</td></tr>
</table>

<h3>每个包怎么启动（界面与命令行的分别入口）</h3>
<ul>
<li>图形版 <code>excel2mysql.exe</code>：双击即开界面，没有命令行模式。</li>
<li>Python 命令行版 <code>excel2mysql-cli.exe</code>：控制台程序，在 cmd 里带参数
运行（<code>excel2mysql-cli.exe --check</code>）。直接双击它会打一句"没有输入"的用法后
立刻退出，黑窗口一闪而过，这是预期行为而不是坏了——不带参数它就没有可干的活；
它也没有界面模式。</li>
<li>Java 版：界面与命令行是同一个 jar，靠第一个参数分开。
<ul>
<li>要界面：双击 <code>启动.bat</code>。这个脚本只做一件事——用包内的
<code>jre\\bin\\java.exe</code> 跑 <code>-jar excel2mysql-*.jar --gui</code>。
想一次带上文件，把 Excel/ZIP 拖到 <code>启动.bat</code> 的图标上。</li>
<li>要命令行：在 cmd 里跑同一条命令但不给 <code>--gui</code>，例如
<code>jre\\bin\\java.exe -jar excel2mysql-1.0.1.jar --check</code>。</li>
<li>记法就一句：首参给 <code>--gui</code> 是界面，不给是命令行。
双击 jar 文件本身不会有任何反应。</li>
</ul>
</li>
</ul>

<h3>界面功能：图形版与 Java 版等价</h3>
<p>两边都有这些动作：拖文件与「添加文件」、ZIP 展开、<code>.xlsx</code> 与
<code>.xls</code>、拼音表名、「批量应用前缀」与「替换首个前缀」、
「生成表结构预览」（可编辑字段名、类型与主键）、「预览 DDL」、
「SQL 脚本改名入库」页的保存与执行、「SQL 执行器」页的直接执行与 30 条示例、
「生成标准excel文件」与「生成简易excel文件」、
耗时任务的进度框，以及这份《使用说明》（右上角「?」或 F1）。</p>
<p>这份等价是逐项称出来的，不是估计：两条线的窗口控件、文案、按钮启用条件、
弹窗文字、日志与写出的文件会被各自枚举成一份清单，再逐键比对，共 218 项。</p>

<h3>两条 Python 线的一个硬限制：口令不能用中文</h3>
<p>pymysql 在发起握手前就把口令按 latin-1 编码，所以图形版与 Windows 7 命令行版
都只能用 latin-1 范围内（实际就是 ASCII 与欧洲字母）的 MySQL 口令；口令里有中文时
连不上库，报出来的是编码错误而不是数据库错误，日志里会跟着给一句"多半出在口令上"。
同一条口令在 Java 版可以用（JDBC 支持 Unicode 口令）——现场改不了口令就选 Java 版。</p>

<h3>什么时候选哪条</h3>
<ul>
<li>机器是 Windows 10/11，要改字段名和主键：图形版。</li>
<li>机器只有 Windows 7、又没法确认打过补丁：Java 版，它自带运行时。</li>
<li>要放进批处理或定时任务：两条命令行线都行，但它们的选项集不一样，
见下一段和各自随包的 <code>README.txt</code>。</li>
</ul>

<h3>命令行选项并不完全相同</h3>
<ul>
<li>Python 命令行版：<code>.sql</code> 默认只登记不执行，要加
<code>--run-sql</code> 才真的写到库里。连接信息可以逐个给
（<code>--host --port --user --password -d</code>），另有
<code>--swap-prefix</code>（替换首个前缀）、<code>--drop-if-exists</code>、
<code>--batch</code>、<code>--log</code>、<code>--quiet</code>、
<code>--no-recursive</code>、<code>--keep-temp</code>。</li>
<li>Java 版：动作由路径类型决定——给 <code>.sql</code> 就执行脚本，
给目录两类都干，用 <code>--no-sql</code> / <code>--no-excel</code> 各自排除，
没有 <code>--run-sql</code> 这个开关。连接信息走同级 <code>conn.ini</code>
或环境变量 <code>E2M_HOST / E2M_PORT / E2M_USER / E2M_PASS / E2M_DB</code>，
选项只有 <code>--dry-run --check --prefix -c --debug --gui --version</code>。</li>
<li>「生成标准excel文件」与「生成简易excel文件」这两件只在界面上有，
两条命令行线都没有对应的动作。</li>
</ul>
"""),

    ("files", "支持哪些文件", """
<h2>支持哪些文件</h2>
<table class="t">
<tr><th>输入</th><th>行为</th></tr>
<tr><td><code>.xlsx</code></td><td>正常导入</td></tr>
<tr><td><code>.xls</code>（97-2003 老格式）</td><td>由 <code>xlrd</code> 解析，正常导入</td></tr>
<tr><td><code>.zip</code> 压缩包</td><td>自动展开包内的 Excel，逐个加入待导入列表</td></tr>
<tr><td>其他扩展名</td><td>忽略，日志里说明忽略了几个</td></tr>
</table>

<h3>ZIP 压缩包</h3>
<ul>
<li><b>递归子目录</b>：包内 <code>北京/水库.xlsx</code> 这样的嵌套路径同样会被收进来，
解压时保留目录结构，所以不同目录下的同名文件不会互相覆盖</li>
<li><b>不递归嵌套 zip</b>：包里的 <code>.zip</code> 会被跳过并在日志里计数，
要导入就把内层包单独拖进来</li>
<li><b>非 Excel 文件忽略</b>，<code>~$xxx.xlsx</code> 这类 Office 临时锁文件也跳过</li>
<li>展开出的文件放在系统临时目录，<b>关闭窗口时自动清理</b>；
所以关掉程序后，来自 ZIP 的失败文件不能直接重试，要重新拖一次包</li>
<li>中文文件名按 GBK 兜底解码：Windows 自带"发送到压缩文件夹"存中文名用 GBK 且不打
UTF-8 标记，工具会正确还原，不会变成乱码表名</li>
</ul>

<h3>含合并单元格的表：直接拒绝导入</h3>
<p>合并区只有左上角那一格有值，其余格读进来是空值——表头被合并会打乱列对应关系，
数据行被合并会静默丢值。所以这类文件<b>一律不导入、也不进入待导入列表</b>，
日志会标红列出具体位置，请回 Excel 里取消合并后重新添加：</p>
<pre>[合并单元格] 两页都合并.xlsx 含 3 处合并区，已拒绝导入，未进入待导入列表
    检测到合并单元格：「字段定义」1 处（A1:G1）；「数据」2 处（A1:B1、A2:B2）</pre>
<p>两种情形都覆盖：两个 Sheet（元数据页 + 数据页）任一页有合并区 → 整个文件拒绝；
只有一个数据页且页内有合并区 → 同样拒绝。ZIP 里只有某个文件有合并区时，
只拒绝那一个，同包其他干净文件正常导入。</p>
"""),

    ("progress", "进度框与耗时", """
<h2>进度框与耗时</h2>
<p>几十万行的大表解析、导入、读写脚本都很耗时，这些操作会弹一个<b>模态进度框</b>：
真正干活的都在后台线程，界面始终能刷新、能点、能取消，不会"假死"。</p>
<table class="t">
<tr><th>操作</th><th>进度怎么算</th></tr>
<tr><td>添加文件 / 拖入文件</td>
<td>每个压缩包 1 项；包内每个 Excel 解出占 1 项；每个待解析 Excel 按 5 个阶段各占 1 项
（合并检测 / 读取工作簿 / 识别格式 / 推断类型 / 选主键）。当前项会显示到
「文件名：读取工作簿」这一级，所以单个几十万行的大表解析途中进度条也在走</td></tr>
<tr><td>开始导入</td><td>每张表 1 项 + 每个数据行 1 项，几十万行也能看到连续推进</td></tr>
<tr><td>批量应用前缀 / 替换首个前缀（Excel 页）</td>
<td>每张表 1 项 + 重名去重 1 项</td></tr>
<tr><td>添加 SQL 文件 / 批量应用前缀 / 替换首个前缀（SQL 页）</td>
<td>每个文件 1 项（读盘、扫描、改写都在后台）</td></tr>
<tr><td>保存修改后脚本</td><td>每个文件 1 项</td></tr>
<tr><td>执行脚本到当前库</td><td>每条语句 1 项，按块推进（大脚本一个文件最多约 100 次刷新，
避免海量刷新反而拖慢界面）</td></tr>
</table>
<h3>取消会发生什么</h3>
<ul>
<li>取消在<b>当前这一项做完</b>时生效，不会硬切留下半截数据</li>
<li>解析中途取消：已解析出的表照常进入待导入列表，弹窗首行标注
<code>已取消，只完成了部分解析</code></li>
<li>SQL 执行中途取消：该文件的事务<b>回滚不提交</b>（DDL 隐式提交的部分除外），后面的文件不再执行</li>
<li>导入中途取消：等同底部的「停止」——当前表处理完即中止，已提交的表不会回滚</li>
</ul>
<li><b>任务结束后不留残留</b>：导入完成（或失败、被中止）后，底部的进度条会
<b>自动回到 0%</b>，不会停在 100% 让人分不清那是上一批的尾巴还是这一批的进度；
「清空列表」同样会把进度条一起归零。结果本身由弹窗与日志交代</li>
<p class="tip">进度框没有标题栏关闭按钮，只能点「取消」或等它自己完成，
避免后台线程还在跑就把窗关掉。工作函数整体抛异常时框照样会关闭，
并在日志里红色打 <code>[任务异常]</code>。</p>
"""),

    ("excel", "两类 Excel 格式", """
<h2>两类 Excel 格式</h2>

<h3>第 1 类：普通数据表（单个数据 Sheet）</h3>
<p>表名/表注释来自<b>文件名</b>，字段名和类型从<b>数据推断</b>。</p>
<ul>
<li>文件名形如 <code>北京市水务局-水库基础信息（dim_reservoir_info）.xlsx</code>：
<b>表名 = 括号内英文</b> <code>dim_reservoir_info</code>，
<b>表注释 = 括号前中文</b>。中英文括号都可以；括号内大写会统一转小写</li>
<li>括号里必须是<b>能直接当表名用的英文标识符</b>（字母或下划线开头、含字母、
只由字母数字下划线组成）才采用。以下情况一律回退到"整个文件名转拼音"：
<table class="t">
<tr><th>文件名</th><th>表名</th><th>为什么</th></tr>
<tr><td><code>人员信息表（人事处人员）</code></td>
<td><code>ren_yuan_xin_xi_biao_ren_shi_chu_ren_yuan</code></td>
<td>括号内是中文 → 括号内外<b>所有中文一起</b>转拼音，不会生成汉字表名</td></tr>
<tr><td><code>人员信息表（7）</code></td><td><code>ren_yuan_xin_xi_biao_7</code></td>
<td>括号内是纯数字序号 → 不生成只含数字的表名，序号留在末尾做区分</td></tr>
<tr><td><code>人员信息表（2026）</code></td><td><code>ren_yuan_xin_xi_biao_2026</code></td>
<td>同上</td></tr>
<tr><td><code>人员信息表（7_dim）</code></td><td><code>ren_yuan_xin_xi_biao_7_dim</code></td>
<td>以数字开头不是合法标识符开头 → 回退</td></tr>
<tr><td><code>人员信息表（v2）</code></td><td><code>v2</code></td>
<td>字母开头、含字母 → 合法，直接采用</td></tr>
</table>
回退时<b>表注释保留完整文件名</b>（含括号内容），所以
<code>人员信息表（人事处）</code> 与 <code>人员信息表（财务部）</code>
会得到两个不同且看得懂的表名，而不是同名加 <code>_2</code></li>
<li>没有括号时：<b>表名 = 文件名汉字转拼音</b>（音节间下划线分隔），表注释 = 文件名。
<b>超长处理与字段名同一套规则</b>：全拼不超过 64 字符用全拼，超过则整名降级为各音节
首字母（数字/英文音节整段保留），例如
<code>北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表</code> →
<code>bjshdqswjxsdxsksngcjcxxhzb</code>；
表名与字段名共用这套切分，只是上限不同（表名 64 / 字段名 30）</li>
<li>整列没值的字段怎么处理，看的是<b>表头写没写字</b>：写了字就<b>照旧保留</b>，日志里点名
（<code>整列为空的字段照旧保留：*填报人…</code>）。采集模板一行数据都还没填，字段是设计好的
表结构，按"整列没值就删"处理会把表改小一号，空模板甚至会被判成"没解析到字段"而整体拒收；
表头空着、整列又没值的（Excel 尾部格式化拖出来的 <code>Unnamed</code> 列）才剔除。全空行照旧剔除</li>
<li>只有表头、一行数据都没有的表照样解析成功：建出来是空表，日志末尾写
<code>没有数据行，建出来是空表</code>，并且<b>不自动选主键</b>（没数据无法判断唯一与非空）</li>
<li><b>表头整行一个格都没写字</b>（连 <code>发布日期</code> 这样的字都没有，只有空格子）也照样导入：
文件里记着这张表有几列，就按那个列数建表，字段名用空表头的兜底写法
<code>col</code>、<code>col_2</code>、<code>col_3</code>，列注释为空、没有数据可插，
日志末尾点名 <code>表头整行没写字，按列宽兜底成：col、col_2、col_3</code>。
这种表建出来看不出业务含义，要么在「生成表结构预览」里改名，要么把表头补上再导。
<b>连一个格子都没有</b>的表（真空表）仍然拒绝，那种表建出来是 <code>CREATE TABLE x ( )</code>，
MySQL 直接报语法错误</li>
<li>表头里的符号一律不进字段名：<code>*</code> / <code>＊</code> 这类必填标记、全角空格、
<code>【】〈〉《》「」〖〗</code> 等括号符本身都会去掉；<b>成对括号包住的那一段整段也不进名字</b>——
表单里 <code>发布日期（yyyy-MM-dd）</code>、<code>金额（元）</code>、<code>是否已婚【是/否】</code>、
<code>★（必填）联系电话</code> 括号里是写给填表人看的口径，不是字段的一部分，
于是分别取 <code>fa_bu_ri_qi</code>、<code>jin_e</code>、<code>shi_fou_yi_hun</code>、
<code>lian_xi_dian_hua</code>。整格只有括号（<code>（备注说明）</code>）时不剥空，照旧用里面那串字。
括号<b>没配右半边</b>也算注释：<code>发布日期（yyyy-MM-dd</code> 从左括号起整段丢。
<b>原始表头完整保留在字段注释里</b>，口径一个不丢，看注释就知道这列原来是哪个表头</li>
<li>日期时间<b>格式掩码串</b>即使没括号也不进名字：<code>发布日期：yyyy-MM-dd</code>、
<code>发布日期 yyyy-MM-dd</code>、<code>发布日期/yyyy/MM/dd</code>、<code>填表日期 yyyymmdd</code>
都取 <code>fa_bu_ri_qi</code> / <code>tian_biao_ri_qi</code>。判定只认"<code>y/m/d/h/s</code>
连写两个以上、两头不贴字母数字"的串，所以 <code>1.0mm</code> 里的 <code>mm</code>、
纯英文表头里的 <code>MM</code>、真日期 <code>2020-01-01</code> 都不会被吃掉；
表头不含汉字时整条不参与这个判断。
剥完之后同名会靠 <code>_2</code> / <code>_3</code> 去重，所以同一张表里
<code>发布日期（yyyy-MM-dd）</code> 和 <code>发布日期</code> 是<b>两列</b>（<code>fa_bu_ri_qi</code> 与
<code>fa_bu_ri_qi_2</code>），不会合成一列，但光看名字分不出谁是谁，要区分就改表头或在结构预览里改字段名</li>
</ul>

<h3>第 2 类：结构定义表（元数据 Sheet + 数据 Sheet）</h3>
<p>字段<b>以定义为准，不再推断</b>。任一 Sheet 的表头含「字段名、数据类型」及以下任一列，
就被识别为元数据页（它排在第几个 Sheet 都能认出来）：</p>
<table class="t">
<tr><th>序号</th><th>字段名(英文)</th><th>字段注释(中文)</th><th>数据类型</th>
<th>长度</th><th>是否主键</th><th>是否可为空</th></tr>
<tr><td>1</td><td>id</td><td>主键ID</td><td>INT</td><td></td><td>是</td><td>否</td></tr>
<tr><td>2</td><td>amount</td><td>金额</td><td>DECIMAL</td><td>10,2</td><td></td><td></td></tr>
</table>
<ul>
<li><code>长度</code> 填 <code>20</code> 或 <code>10,2</code>（→ <code>DECIMAL(10,2)</code>）；
类型列也可直接写 <code>VARCHAR(50)</code>，中文括号/逗号都能识别</li>
<li><code>是否主键=是</code> → 主键（支持多字段组合）；
<code>是否可为空=否</code> → <code>NOT NULL</code>，否则 <code>DEFAULT NULL</code></li>
<li>数据 Sheet 按字段名对齐（大小写不敏感）：多余列忽略并在日志提示，缺失列补 NULL</li>
<li>只有定义没有数据时建空表</li>
</ul>
"""),

    ("export", "导出标准/简易 Excel", """
<h2>导出标准/简易 Excel 交付件</h2>
<p>底部动作条「开始导入」右侧还有两颗按钮，把<b>当前待导入列表里已解析好的表</b>
再写出一份 Excel 交付件。两者都不连库、也不改动列表，产物落在<b>可执行程序同级目录</b>，
每次按日期建一个子目录。</p>
<table class="t">
<tr><th>按钮</th><th>输出目录</th><th>每个文件里有什么</th></tr>
<tr><td>生成标准excel文件</td><td><code>meta_data_file_年月日</code></td>
<td>两个 Sheet：<b>元数据</b>（建表用的字段定义：序号 / 字段名(英文) / 字段注释(中文) /
数据类型 / 长度 / 是否主键 / 是否可为空）+ <b>数据</b>（表头是英文字段名，下面是数据行）</td></tr>
<tr><td>生成简易excel文件</td><td><code>simple_meta_data_file_年月日</code></td>
<td>只有一个 <b>数据</b> Sheet，给只要数据、不要建模说明的下游</td></tr>
</table>
<p>文件名沿用解析输入时那套命名：<code>表注释（表名）.xlsx</code>，没有表注释时只留表名。
标准件就是本工具「两类 Excel 格式」里的结构定义表，<b>可以直接拖回来再导入</b>：
字段名、类型、长度、主键、可空全部以元数据 Sheet 为准，读回来与导出前一致。</p>
<p>可空这一格写「否」才是 <code>NOT NULL</code>，留空或写「是」都是可空；
主键列自动写成「否」。日期一律写成 <code>2008-05-19</code> 这样的文本而不是 Excel 序列号，
避免同一份数据在两次读写之间被历元换算挪走一天。</p>
<p class="tip">进度按文件计项（一张表 1 项），逐文件写日志，完成后弹窗把目录名原样报出来。
按「停止」会在当前这个文件写完后停下，剩下的不写，日志与弹窗都会写明
「已取消，剩余文件未生成」。</p>
<p>「SQL 脚本改名入库」页底部也有这两颗同名按钮，产物形状完全一致，
只是表定义改成从脚本正文的 <code>CREATE TABLE</code> 里解析，见
「SQL 脚本改名入库页」一节。</p>
"""),

    ("naming", "字段命名与类型推断", """
<h2>字段命名规则</h2>
<p>中文表头<b>逐音节转拼音、下划线连成蛇形</b>：</p>
<table class="t">
<tr><th>中文表头</th><th>字段名</th></tr>
<tr><td>订单金额</td><td><code>ding_dan_jin_e</code></td></tr>
<tr><td>员工编号</td><td><code>yuan_gong_bian_hao</code></td></tr>
<tr><td>总库容（亿立方米）</td><td><code>zong_ku_rong_yi_li_fang_mi</code>（25 字符，全拼保留）</td></tr>
<tr><td>北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表</td>
<td><code>bjshdqswjxsdxsksngcjcxxhzb</code>（全拼超 30，降级首字母）</td></tr>
<tr><td>station_code</td><td><code>station_code</code>（纯英文不做拼音转换）</td></tr>
<tr><td>7号线</td><td><code>f_7_hao_xian</code>（数字开头补 <code>f_</code>）</td></tr>
</table>
<ul>
<li><b>30 字符是唯一的降级触发条件</b>：全拼 ≤30 用全拼，超过则整列改用首字母。
不按"中文字数"另设阈值，规则确定可测</li>
<li>降级时<b>数字与单位音节整段保留</b>，不跟着取首字母——否则
<code>降水量≥1.0mm</code> / <code>≥10.0mm</code> / <code>≥100.0mm</code>
会全塌成同一个名字，而这些数字恰恰是列名的区分点</li>
<li>重名字段自动加 <code>_2</code>、<code>_3</code></li>
<li>实测本数据包 310 条真实中文列注释，<b>碰撞 0 组</b></li>
</ul>
<p class="warn">字段命名规则变更后，同一张表生成的列名与旧版不同。若目标库里表已存在，
默认行为是跳过建表，随后 INSERT 会报 <b>1054 Unknown column</b>。
要么勾「覆盖已存在表」重建，要么在表结构预览里把字段名改回旧列名。</p>

<h2>类型推断规则</h2>
<p><b>全量扫描所有数据行</b>，不做抽样，最长行/异常行不会漏判。</p>
<table class="t">
<tr><th>数据特征</th><th>推断结果</th></tr>
<tr><td>日期时间对象，或含时间的字符串</td><td><code>DATETIME</code></td></tr>
<tr><td><b>表头含金额语义的数值列</b></td><td><code>DECIMAL(16,2)</code>（样本是整数也不用 INT）</td></tr>
<tr><td>纯整数</td><td><code>INT</code>，溢出则 <code>BIGINT</code></td></tr>
<tr><td>日期对象或带分隔符的日期串</td><td><code>DATE</code></td></tr>
<tr><td>含小数</td><td><code>DECIMAL(p,s)</code> 按实测位数定宽</td></tr>
<tr><td>文本</td><td><code>VARCHAR(n)</code>，按全量最大 UTF-8 字节 + 20% 余量取档位</td></tr>
<tr><td>超长文本</td><td>超 16383 字节 → <code>TEXT</code>，超 65535 字节 → <code>MEDIUMTEXT</code></td></tr>
</table>
<h3>几个容易踩的点已经处理掉了</h3>
<ul>
<li><b>金额语义识别</b>：Excel 里 <code>100</code> 常以 <code>100.0</code> 存储，
纯值判定会把"订单金额"判成 INT 砍掉小数。所以加了一层表头语义修正——
命中金额关键词（金额/单价/价格/费用/成本/余额/工资/收入/支出/税额/利息/销售额/
保证金…，英文 amount/price/cost/fee/salary/balance…）的数值列一律
<code>DECIMAL(16,2)</code></li>
<li><b>只收成词的关键词，不认裸「费」「价」</b>：<code>费县</code>（地名）、
<code>评价</code> 不会被误判成金额</li>
<li><b>比率类一律否决金额语义</b>：含 率/比例/百分比/占比，或名字含
rate/ratio/percent 的列不算金额（<code>年利率</code> 按实测小数位定宽，
否则 4 位小数被压成 2 位，数据就改了）</li>
<li><b>绝不截断实测精度</b>：<code>结算金额</code> 实测有 4 位小数就给
<code>DECIMAL(18,4)</code>；经纬度 <code>39.9046897</code> 保住 7 位给
<code>DECIMAL(11,7)</code>；科学计数法大数按展开后的位数放宽</li>
<li><b>8 位纯数字</b>（如 <code>20230101</code>）按数值处理，编码列不会误判成日期</li>
<li><b>带前导零的整数串</b>（如 <code>007</code>）保持文本，不丢前导零</li>
<li><b>行内字节预算</b>：utf8mb4 下每个 <code>VARCHAR(n)</code> 计 4n 字节，
全表共享 60000 字节预算，预算不足的大文本列自动退为 <code>TEXT</code>，
保证建表不报 Row size too large</li>
<li>数值列中无法转换的值按 NULL 写入，并在日志里汇总告警（列名、数量、行号示例），
不静默丢数据</li>
<li><b>装不进 BIGINT 的整数会被整张表拒绝</b>：推断只能给到 <code>BIGINT</code>，
超过 <code>±9223372036854775807</code> 时 MySQL 报
<code>1264 Out of range value</code>，该表事务回滚、其余表继续，不会把一个变过样的数写进库。
Excel 的数值单元格本身是双精度浮点，<code>9223372036854775807</code> 存进去就已经变成
<code>2^63</code>，所以要落这种量级的数，得在结构定义 Sheet 里把该列写成
<code>DECIMAL(20,0)</code>，或者当文本列导</li>
</ul>
<p>所有推断结果都可以在<b>「生成表结构预览」</b>里逐列改。</p>
"""),

    ("prefix", "表名前缀：加与换", """
<h2>表名前缀：加与换</h2>
<p>「表名前缀」输入框旁边有<b>两个按钮，共用同一个输入框</b>，做的是两件事：</p>
<table class="t">
<tr><th>按钮</th><th>做什么</th><th>例子（填 <code>change</code>）</th></tr>
<tr><td>批量应用前缀</td><td>在表名<b>前面再加一段</b>；始终基于「基础表名」重算，
所以换前缀不会叠加，<b>留空再点它就是移除前缀</b></td>
<td><code>ods_t_order</code> → <code>changeods_t_order</code>（想加分隔就在框里写
<code>change_</code>）</td></tr>
<tr><td>替换首个前缀</td><td>把表名<b>第一个下划线之前那一段换掉</b>，后面的部分原样保留</td>
<td><code>ods_t_order</code> → <code>change_t_order</code><br>
<code>adb_table2</code> → <code>change_table2</code></td></tr>
</table>
<h3>「替换首个前缀」的细节</h3>
<ul>
<li>按<b>第一个下划线</b>切分，所以 <code>ods_a_b</code> 只换掉 <code>ods</code>，得到
<code>change_a_b</code></li>
<li>末尾那个下划线<b>不用写</b>：填 <code>change</code> 与 <code>change_</code> 效果相同，
不会出现 <code>change__t_order</code></li>
<li><b>表名里没有下划线的不改</b>（如 <code>users</code>：整名只有一段，换掉等于改名而不是换前缀），
以下划线开头的（如 <code>_tmp</code>）同样不改；这些表会逐条写进日志说明原因</li>
<li><b>重复点不会叠加</b>：已经是 <code>change_</code> 开头的表名再点一次保持原样</li>
<li>换完如果两张表撞名（<code>ods_a</code> 与 <code>x_a</code> 都变成 <code>change_a</code>），
自动按 <code>change_a</code> / <code>change_a_2</code> 去重</li>
<li>只允许字母、数字、下划线；填了别的字符会弹窗拒绝，<b>不会生成非法表名</b></li>
<li>换过之后这就是新的基础表名：再点「批量应用前缀」填 <code>dim_</code>，
得到 <code>dim_change_t_order</code></li>
<li>拼音表名要留意：自动生成的 <code>shui_ku_ji_chu_xin_xi</code> 第一刀会切在
<code>shui</code> 后面，变成 <code>change_ku_ji_chu_xin_xi</code>——
这类名字请用「批量应用前缀」而不是「替换首个前缀」</li>
</ul>
<p>「SQL 脚本改名入库」页同样有这两个按钮，<b>替换时会同步改写</b>脚本里
<code>CREATE / DROP / INSERT INTO / ALTER / TRUNCATE</code> 对同一张表的引用，
表格里的「原表名」一列仍显示最初的名字，方便核对。</p>
"""),

    ("pk", "主键", """
<h2>主键</h2>
<p>主键约束<b>至多一个</b>，可由一个或多个字段组成组合主键。</p>
<h3>自动选择（添加文件时）</h3>
<p>硬性条件：类型为 <code>INT</code>/<code>BIGINT</code> 或长度 ≤255 的
<code>VARCHAR</code>/<code>CHAR</code>；全列非空且唯一；表至少有一行数据。</p>
<p>评分（≥3 分才入选，避免把碰巧唯一的金额列当主键）：
<b>+4</b> 字段名形如 id/uuid/_id 或注释含 ID/主键/唯一标识；
<b>+3</b> 注释含 编号/序号/代码/编码/标识/卡号/单号；
<b>+2</b> 整数类型；<b>+1</b> 短字符类型（VARCHAR ≤64）。
同分取字节长度更短的列。没有合适字段就不设主键。</p>
<h3>手动修改</h3>
<p>「生成表结构预览」里每行有主键勾选框，可自由勾选/取消一个或多个字段。
保存时自动校验，<b>不通过会提示且改动不生效</b>，弹窗保持打开让你调整：</p>
<ul>
<li>类型不能作主键（如 TEXT）</li>
<li>存在空值或空白值</li>
<li>组合值重复</li>
</ul>
"""),

    ("sqlpage", "SQL 脚本改名入库页", """
<h2>SQL 脚本改名入库页（第二个页签）</h2>
<p>用来处理现成的 <code>.sql</code> 建表脚本，三步按顺序走：把脚本里的
<code>CREATE TABLE</code> 提取出来并数出现次数、按这份表名清单批量改表名、
再另存改好的副本或执行到当前库。</p>
<table class="t">
<tr><th>按钮</th><th>作用</th></tr>
<tr><td>添加 SQL 文件</td><td>可多选，自动统计脚本里所有
<code>CREATE TABLE [IF NOT EXISTS] `表名`</code> 并提取表名
（兼容反引号 / 裸名 / <code>schema.表名</code> 限定名，按首次出现顺序展示出现次数；
<code>INSERT INTO</code> 等不计入）</td></tr>
<tr><td>批量应用前缀</td><td>把提取到的表名统一加前缀：带反引号的引用
（含 DROP/INSERT/ALTER）全部同步改写；裸表名只在语句头位置改写；
已带该前缀的自动跳过，<b>重复应用不会叠加</b></td></tr>
<tr><td>替换首个前缀</td><td>把每个表名第一个下划线之前的段落换成输入框里的内容
（<code>ods_t_order</code> → <code>change_t_order</code>），引用一并同步改写；
表名里没有下划线的不动，重复点不会叠加</td></tr>
<tr><td>保存修改后脚本</td><td>改写发生在内存中，确认后另存为
<code>原名_prefix.sql</code>，落在源文件旁边，<b>不覆盖原文件</b></td></tr>
<tr><td>执行脚本到当前库</td><td>按上方公共连接区选定的目标库执行；
<b>每个文件一个事务</b>：先开启事务，逐条执行，任一语句失败立即回滚整个事务
并在日志标红报错，后续语句不再执行，全部成功才提交</td></tr>
<tr><td>生成标准excel文件</td><td>把脚本里解析出的字段定义写成
<code>meta_data_file_年月日</code> 目录下的 xlsx，一张表一个文件，
形状与「Excel 导入」页那两颗完全一样（元数据 + 数据 两个 Sheet）；
区别在数据源：这里<b>只看 <code>CREATE TABLE</code> 的列定义，不看库、也不看文件里的
<code>INSERT</code></b></td></tr>
<tr><td>生成简易excel文件</td><td>同上，只留 <b>数据</b> 一个 Sheet</td></tr>
</table>
<ul>
<li>两颗生成按钮取的是<b>改名之后的当前正文</b>，所以"先批量改前缀、再生成交付件"
这条路是通的，产物里的表名就是新表名</li>
<li>脚本里只有结构、没有数据，所以「数据」Sheet 只剩英文字段名那一行表头，
给下游当可填模板用，不假装里面有数据行</li>
<li>元数据 Sheet 只能装「一个类型关键字 + 纯数字长度」，所以
<code>UNSIGNED</code> / <code>ZEROFILL</code> / <code>CHARACTER SET</code> /
<code>COLLATE</code> 这些修饰会被丢掉，<code>ENUM('a','b')</code> 这类括号里不是数字的
按 <code>TEXT</code> 导出；每一处都在日志里逐列点名（<code>! [WARN] 说明 | ...</code>），
不静默改语义</li>
<li>同名表被定义了多次时只按<b>第一处</b>的列定义导出，其余跳过并写明是哪一处</li>
<li><code>CREATE TABLE x LIKE y</code> 与 <code>... AS SELECT</code> 这两种写法里没有列定义，
生成不了元数据，日志点名后跳过</li>
<li>语句切分能正确跳过字符串 / 反引号 / 注释中的分号</li>
<li><b>不支持</b>含 <code>DELIMITER</code> 的存储过程脚本</li>
<li>文件编码自动尝试 UTF-8 / UTF-8-BOM / GBK</li>
<li>注意 MySQL 的 <code>CREATE/DROP/ALTER</code> 等 DDL 会<b>隐式提交</b>，
无法随事务回滚；回滚只对其后的数据操作生效</li>
<li>Excel 导入同样是事务语义：每张表先开启事务，数据插入成功后提交，
失败整体回滚并记录日志</li>
</ul>
"""),

    ("sqlrun", "SQL 执行器页", """
<h2>SQL 执行器页（第三个页签）</h2>
<p>临时问一句、顺手改点结构用它：直接把 SQL 敲在编辑区里点「执行」就打到了
上方公共连接区选定的库。与「SQL 脚本改名入库」页的分工是——那一页管"手上有一批
<code>.sql</code> 文件要入库"（挑文件、批量改表名、落盘副本），这一页不挑文件也不落盘，
只吃编辑区里那段文本。</p>
<table class="t">
<tr><th>控件</th><th>作用</th></tr>
<tr><td>页顶的当前目标库提示条</td><td>与前两页同一条来源：绿色写
<code>当前目标库：库名（主机:端口）——编辑区里的 SQL 将执行到这个库</code>，
没选库时红色告警。这一页点的每条语句都落在这个库里，动手前先看这一行</td></tr>
<tr><td>示例 (?)</td><td>打开示例清单（30 条，分 5 组：建库与选库、建表与改表、
元数据与治理体检、查询与执行计划、数据修正）。左边选中一条，右边给全文，
可「复制到剪贴板」也可「插入到编辑区」；对话框不锁界面，能边看边改</td></tr>
<tr><td>执行</td><td>按分号切句逐条执行，每条把语句原文、影响行数或结果集写进右侧结果区</td></tr>
<tr><td>清空编辑区</td><td>在「SQL 文本」那一栏的头一行，只清左边这段 SQL，右侧结果区不动</td></tr>
<tr><td>清空结果区</td><td>在「执行结果」那一栏的头一行，只清右边的执行结果，左边编辑区不动</td></tr>
</table>
<ul>
<li>事务语义：<b>全部成功才提交，中途出错整批回滚</b>，失败那句后面附
DDL 隐式提交的提醒；结果区逐条给 <code>第 i/n 条</code>，出错那条标红</li>
<li>查询出来的结果<b>排成等宽表格</b>再写进结果区：列名一行、上下与表头下面各一条分隔线，
每列按显示宽度补齐（汉字占两列），所以中英混排也对得齐；空格子写成 <code>NULL</code>，
取值里的换行与制表符写成可见的转义 <code>\\n</code> / <code>\\t</code>，
单个格子超过 40 列截断并补省略号——不然一条带换行的长备注会把整张表顶散</li>
<li>结果集最多打印前 200 行，超出只报总行数（<code>共 N 行（只打印前 200 行，其余没显示）</code>），
避免几十万行刷爆结果区；结果区与编辑区同一支等宽字体</li>
<li>语句切分与「SQL 脚本改名入库」页同一套：跳过字符串 / 反引号 / 注释里的分号；
整句只剩注释的片段会被剔掉，不会算成"已提交 1 条"</li>
<li>编辑区不折行，一句 SQL 一行看到底；开场预置 <code>SELECT 1 AS 连通检查;</code>，
连不上库时结果区直接给错误码与原因</li>
<li>示例里的表名/库名是占位（<code>t_order</code>、<code>ods_data</code>），
现场改完再执行；这一页执行的就是你提交的语句，<code>DROP</code>/<code>TRUNCATE</code>
不另设确认，删东西前先看结果区里那句</li>
</ul>
"""),

    ("log", "日志与报错", """
<h2>日志与报错</h2>
<p>三个页签各有一个独立日志区（Excel 导入页底部、SQL 脚本改名入库页底部、
SQL 执行器页右半边的结果区），互不串台，各自保留最近 2000 条，超出自动丢弃最早的。</p>
<p><b>所有失败记录整条红色加粗</b>，成功与信息行保持默认颜色，扫一眼就能定位问题。
报错不只打异常原文，而是展开成
<b>异常类型 + MySQL 错误码 + 服务端原始报错全文</b>：</p>
<pre>[执行失败] a.sql 第 2/3 条语句报错，目标库 testdb@127.0.0.1:3306，本文件事务已回滚
    ProgrammingError（MySQL 错误码 1064）：You have an error in your SQL syntax; ...
    出错语句：
        INSERT INTO t_bad VALUE (1)
    前面 1 条语句已随事务回滚。注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交...</pre>
<h3>各场景给出的信息</h3>
<table class="t">
<tr><th>场景</th><th>日志内容</th></tr>
<tr><td>SQL 执行失败</td><td>文件名、第几条/共几条、目标库 <code>库名@主机:端口</code>、
错误码与原始报错、<b>出错语句全文</b>（超 2000 字符截断并标注原文长度）、回滚说明</td></tr>
<tr><td>SQL 连接失败</td><td>库名@主机:端口、用户名、错误码，并注明"剩余脚本均未执行"</td></tr>
<tr><td>Excel 建表失败</td><td>表名、目标库、错误码、<b>失败的完整建表 DDL</b></td></tr>
<tr><td>Excel 导入失败</td><td>表名、目标库、已尝试写入的数据行数、错误码、回滚说明</td></tr>
<tr><td>合并单元格拒绝</td><td>文件名、合并区总数、每个 Sheet 的位置清单、原因与处理建议</td></tr>
<tr><td>结尾汇总行</td><td>只要存在失败，整行标红</td></tr>
</table>
<p class="tip">日志区右上角有<b>「清除日志」</b>按钮（SQL 页有自己独立的清除按钮）。</p>
"""),

    ("summary", "添加结果汇总", """
<h2>添加结果汇总</h2>
<p>无论走「添加文件」按钮还是拖拽，添加完成后都会<b>把汇总同时打进日志区并弹窗</b>——
日志可回溯、弹窗防走神漏看，两处文字逐字一致。散文件与压缩包各占一行，
只输出本次实际用到的行：</p>
<pre>添加 3 项文件，包含 2 项 xlsx 文件、1 项 xls 文件
添加 1 项 zip 文件，包含 12 项 xlsx 文件、0 项 xls 文件
另有 2 项解析失败、1 项重复已跳过，详见日志</pre>
<ul>
<li>xlsx / xls 的数量<b>只统计真正进入待导入列表的</b>，
所以弹窗数字和列表行数始终对得上</li>
<li>「添加 N 项 zip 文件」的 N 指你投入了几个包（压缩包本身不进列表）</li>
<li>第三行只在真有异常时出现，按 <code>、</code> 拼接这几类，为 0 的不写：
<code>N 项含合并单元格已拒绝</code>、<code>N 项解析失败</code>、
<code>N 项重复已跳过</code>、<code>N 个压缩包无法展开</code>、
<code>N 个压缩包内没有 Excel</code></li>
<li>全部成功时只有前两行，不会出现「另有」</li>
<li>只拖了空压缩包也会弹窗，否则界面看着像没反应</li>
<li>重复文件在日志里有对应行 <code>[重复跳过] xxx.xlsx 已在待导入列表</code>，
数字可追溯</li>
<li>失败的具体原因始终在日志里标红，汇总只报数量不塞细节</li>
</ul>
"""),

    ("layout", "界面布局", """
<h2>界面布局</h2>
<ul>
<li><b>MySQL 连接信息</b>区在三个页签<b>上方常驻</b>，三页共用同一套连接参数和目标库选择，
不需要在每个页签里分别填</li>
<li><b>「Excel 导入」页的三段高度可拖拽</b>：拖拽区、待导入文件表、日志区之间有
6px 宽的横向分隔条，鼠标按住即可上下拖动重新分配高度。
三段都不允许拖到 0 高，不会拖丢了找不回来。
提示条和底部动作条（覆盖勾选框、停止、开始导入、生成标准excel文件、
生成简易excel文件、进度条）是固定件，不参与挤压。
高度分配不写配置，重开程序回到初始比例</li>
<li><b>「SQL 脚本改名入库」页也能拖拽分高</b>：待处理文件表格区与日志区之间一条 6px 分隔条，
一边变高另一边就变窄。日志很多时把下半区拉大；要核对表名清单时把表格拉大。
「表名前缀」行与操作条跟着表格一起缩放，提示条和「添加 SQL 文件」按钮是固定件</li>
<li><b>「SQL 执行器」页左右分栏</b>：SQL 文本与执行结果之间一条 6px 竖向分隔条，
一边变宽另一边就变窄；两边都不允许拖到 0 宽。上面那行提示和「示例 (?)」「执行」是固定件，不参与挤压；两颗<b>清空</b>分别在左右两栏的头一行，各管自己那一边</li>
<li>三个页签的高度分配都<b>不写入配置</b>，重开程序回到初始比例</li>
<li>待导入文件表的<b>列宽可自由拖动</b>，最后一列自动填满剩余空间</li>
<li>右上角有两个小按钮：<b>「?」</b>打开本使用说明，<b>「i」</b>打开「关于」
（版本号 / 提交号 / 构建日期三行，提交号可鼠标选中复制）</li>
<li>「关于」里的提交号与构建日期是<b>打包那一刻烧进 exe</b> 的（exe 所在机器没有 git，
运行时查不到）；开发态直接跑 <code>python main.py</code> 时现查 git，查不到就显示
"开发态（未打包）"，不会编一个假提交号</li>
<li>双击表格里的<b>表名 / 字段</b>可直接编辑</li>
<li>来自 ZIP 的文件，鼠标悬停「文件」列会显示完整来源，形如
<code>数据打包.zip!北京/水库基础信息.xlsx</code></li>
<li>导入成功的文件会自动从待导入列表移除，<b>失败的文件保留</b>，可以调整后重试；
单表失败不阻断其余表</li>
<li><b>弹窗里的文字一定会被折进窗口里</b>：提示、警告、出错这三类弹窗的正文按固定宽度
折行（一行约 28 个汉字），一行长路径、一整段异常信息都不会把窗口撑到屏幕外。
正文超过 22 行时弹窗只留前 21 行并写明「其余 N 行放不下，完整内容见底部日志」，
日志里始终是完整的那一份</li>
<li>各窗口（主窗口、本说明、「关于」、表结构预览、DDL 预览、进度框）的尺寸
按<b>客户区</b>给定，并在打开前按当前屏幕的可用区夹一次：
1024x768 的老机器上整窗加标题栏仍在屏内，底部的按钮不会被任务栏吃掉</li>
</ul>
"""),

    ("faq", "常见问题", """
<h2>常见问题</h2>
<table class="t">
<tr><th>现象</th><th>原因与处理</th></tr>
<tr><td>在 <b>Windows 7 / 8</b> 上双击 exe 打不开，弹"找不到指定的程序/入口点"</td>
<td>这个图形版要求 <b>64 位 Windows 10（1809 及以上）或 Windows 11</b>：界面用的 Qt 6 不支持
Win7/8.1，Python 3.9+ 与打包器也不支持，这是<b>缺依赖装不回来</b>的硬限制。
两种办法：① <b>导入不必在 Win7 本机做</b>——找任意一台 Win10/11 电脑跑本工具即可，
它只要<b>网络能连上目标 MySQL</b>，Excel 放共享目录一样能读；
② 只能在 Win7 上跑时，改用同源的<b>命令行版 <code>excel2mysql-cli.exe</code></b>
（解析、推断、建表、导入用的是同一套 core 逻辑，建出来的表结构一致），
用法见项目 README 的「Windows 7 命令行版」一节</td></tr>
<tr><td>提示 <code>1054 Unknown column</code></td>
<td>字段命名规则变更后列名与旧表不一致。勾「覆盖已存在表」重建，
或在表结构预览里改回旧列名</td></tr>
<tr><td>提示 <code>Row size too large</code></td>
<td>已内置处理：VARCHAR 行内预算不足时自动退为 TEXT。若仍出现，
手动把几个大文本列改成 TEXT</td></tr>
<tr><td>表没建但数据报主键冲突 <code>1062</code></td>
<td>表已存在且默认跳过建表，旧数据还在。勾「覆盖已存在表」或先 TRUNCATE</td></tr>
<tr><td>文件被拒绝，提示含合并单元格</td>
<td>回 Excel 取消所有合并区后重新添加。日志会列出具体位置</td></tr>
<tr><td>拖了 ZIP 但列表没变</td>
<td>看日志：可能是包内没有 Excel、是嵌套包（不递归展开），或包已加密</td></tr>
<tr><td>关掉程序后想重试 ZIP 里的失败文件</td>
<td>展开出的临时文件已随窗口关闭清理，需重新拖入压缩包</td></tr>
<tr><td>金额列被建成 INT 丢了小数</td>
<td>表头含金额语义的列已自动用 <code>DECIMAL(16,2)</code>；
若你的列名不含这些关键词，在表结构预览里手动改成 DECIMAL</td></tr>
<tr><td>双击 exe 没反应 / 杀毒软件拦截</td>
<td>未签名的 exe 可能被 SmartScreen 提示，选"仍要运行"即可。
onedir 版启动约 1-3 秒；exe 必须和 <code>_internal</code> 文件夹放在一起</td></tr>
<tr><td>导入一半想停</td><td>点<b>「停止」</b>，当前表处理完即中止，已提交的不会回滚</td></tr>
</table>
<h3>事务语义</h3>
<p>每张表 / 每个 SQL 文件是一个独立事务：成功才提交，失败整体回滚并继续下一项。
但 MySQL 的 DDL（CREATE/DROP/ALTER）会隐式提交，无法回滚——这是数据库的限制，
日志里每次回滚都会提醒这一点。</p>
"""),
]
