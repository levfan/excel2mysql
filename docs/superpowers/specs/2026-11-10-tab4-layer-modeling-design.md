# tab4 分层建模设计（ods → obj / dom / ads）

日期：2026-11-10
范围：excel2mysql Java 图形版新增第 4 页签「分层建模」。Python 图形版、Python 命令行版、Java 命令行入口均不改动。
状态：设计已口头确认（库模型 B、运行模式 E 按推荐），待书面审。

## 1 背景与目标

大数据支撑平台验收需要把贴源层（ods）的数据整编为对象层（obj）、领域层（dom）、主题层（ads），并留下可提交的治理证据。真实数据在内场，外场有数据但表头结构与内场一致；内场无数据。tab4 的目标：读 ods 库表结构，按一份可编辑、可拷贝的映射文件，产出三层的 MySQL 建表 DDL、转换 SQL、字段映射，并可选地把数据灌入目标库。

拓扑为分叉—汇合，不是线性递进：obj 与 dom 并行，各自从 ods 出；ads 在 obj＋dom 基础上汇总。资产目录是服务出口（发布可信），不是第五层，tab4 不涉及。

验收导向，不追求满分：满足指标条款"有功能、可配置、可回溯"即可，不建跨源模糊匹配引擎，不替业务方定死指标公式。

## 2 范围与非目标

纳入：
- Java GUI 第 4 页签，读源库结构、载入映射、三按钮生成三层 DDL＋转换 SQL＋字段映射、可选入库。
- 表级分类由映射文件驱动；字段级默认同名直映＋自动补治理字段，界面可改例外。
- 产物落盘成可拷贝目录，供内场结构先行。

不纳入：
- Python 两端与 Java CLI 的等价实现（用户明确只做 Java GUI）。
- 跨源实体模糊匹配、自动码表对齐、指标口径库的自动推导（留可配置接口与待核标记）。
- Hive 连接（tab4 只走 MySQL/JDBC；平台 Hive 库的登记/同步/发布可信走平台工作流，不在 tab4）。
- 资产目录发布、质量门禁调度（平台侧能力）。

## 3 目标口径来源

目标侧分类不自行编造，取自需求规格条款：
- 对象层 obj＝十类共用维度（条款 183，表 5-1）。
- 领域层 dom＝七大领域 33 子类（条款 184，表 5-2）。
- 主题层 ads＝三条主线主题（条款 188，表 6-1：后勤资源掌控 rc／综合分析 ca／辅助决策 ad）。
- 治理字段口径：表 5-3（obj/dom 通用）、表 6-4（ads 通用）。

十类对象维度（表 5-1）：

| 维度表 | 类别 | 建议主键 |
| --- | --- | --- |
| dim_org | 组织机构 | org_id |
| dim_material | 物资 | material_id |
| dim_fund_account | 经费科目 | account_id |
| dim_facility | 基础设施 | facility_id |
| dim_person | 人员 | person_id |
| dim_supply_standard | 供应保障标准 | standard_id |
| dim_policy | 政策法规 | policy_id |
| dim_geo | 地理环境 | geo_id |
| dim_code_info | 信息分类与代码 | code_id |
| dim_code_it | 信息技术标准 | std_id |

七大领域 33 子类（表 5-2，领域码→子类→明细表命名）：
- guard 保障：基础数据 dwd_guard_base／动态数据 dwd_guard_dynamic／决策数据 dwd_guard_decision／实力数据 dwd_guard_strength
- comp 综合：物资储备 dwd_comp_material_store／科技管理 dwd_comp_tech／信息化 dwd_comp_informatization／政策法规 dwd_comp_policy
- fin 财务：经费预算 dwd_fin_budget／生活费 dwd_fin_living／科研费 dwd_fin_research／基本建设费 dwd_fin_construction
- proc 采购供应：油料保障 dwd_proc_oil／被装保障 dwd_proc_clothing／给养保障 dwd_proc_provision／物资保障 dwd_proc_material／采购保障 dwd_proc_purchase
- med 卫生勤务：医疗保健 dwd_med_care／疾病预防控制 dwd_med_prevention／医疗救护 dwd_med_rescue／药材保障 dwd_med_pharmacy／医疗设施 dwd_med_facility／卫勤人员 dwd_med_staff
- trans 运输投送：铁路运输 dwd_trans_rail／公路运输 dwd_trans_road／水路运输 dwd_trans_water／航空运输 dwd_trans_air／海上补给 dwd_trans_sea_supply
- facil 设施建设：规划计划 dwd_facil_plan／工程建设 dwd_facil_project／住房土地 dwd_facil_land／工程设施 dwd_facil_engineering／住房管理 dwd_facil_housing

主题框架（表 6-1）按领域归口：guard→保障实力(rc)/保障动态综合(ca)/需求感知(ad)；comp→物资储备(rc)/数据资源综合(ca)；fin→经费执行(rc)/财务综合(ca)；proc→采购供应综合(ca)；med→卫勤资源(rc)；trans→运输能力(rc)；facil→设施资源(rc)/设施建设综合(ca)。具体主题取舍与指标口径须业务方逐条确认（条款 188 注），tab4 产模板，标待核。

## 4 映射文件（跨项目联系）

映射文件由大数据支撑平台项目侧生成与维护，tab4 只消费：
- 生成脚本：`D:\01.compony\1.ht706-sxgs\2.doc\5.项目文件\1.大数据支撑平台\构建\gen_layer_mapping.py`（stdlib，可重放）。
- 初稿产物：同项目 `数据治理所需\2.数据治理文档\层间映射初稿.csv`（312 表，UTF-8-BOM）。
- 口径库存：同目录 `全局字段清单.csv`（5277 字段，207/209 证据）。

初稿为表级启发式分类，全部标"待核"，权威归类由业务方核。tab4 载入此 CSV（或用户另存的副本），不修改生成脚本。映射文件是 208 逐字段关联、183/184 分类、188 主题血缘的验收证据载体，可整份拷进内场。

表级映射列（15 列）：系统、子领域、表中文名、表英文名、字段数、主键字段、目标领域、领域码、领域子类、领域目标表、涉及对象维度、主题归属、分类依据、映射状态、备注。

字段级映射不在表级初稿里手填：tab4 按"同名直映＋治理字段"自动生成，界面改例外，导出 `<层>_field_mapping.csv`。字段映射列：源表、源字段、源注释、目标表、目标字段、目标注释、数据元名称、数据元标识符（待赋码）、转换规则、是否治理字段、映射状态。

## 5 架构与复用

新增组件：
- `gui/LayerPage.java`：第 4 页签 JPanel，仿 `gui/SqlPage.java` 的 Host 模式。持有自己的 LogPanel、清单 JTable、源库下拉、映射载入按钮、三按钮、运行模式开关。
- `core/MappingFile.java`：读/写映射 CSV（表级＋字段级），纯离线，可先单测。
- `core/LayerGen.java`：按映射＋源库结构产 DDL、转换 SQL、字段映射；可选执行入库。纯逻辑与连库部分分离，便于离线对撞。

复用既有：
- `core/DbClient.java`：connection() 取 JDBC DatabaseMetaData 读源库结构；createTable(ddl) 建表；insertData(...) 灌数；listDatabases() 填源库下拉。
- `core/Ddl.java`：buildDdl(表名, 注释, List<Infer.Column>, dropIfExists) 产 CREATE TABLE；buildInsertSql、convert 复用。
- `core/Infer.Column`：字段模型（field/type/comment/primaryKey/nullable），目标表字段用它表达。
- 任务框架：MainFrame.runTask（子线程＋ProgressDialog＋资源位互斥＋日志弹窗双写）。新增资源位 `RES_LAYER`，与 RES_EXCEL/RES_SQL/RES_RUN/RES_CONN 互不抢。
- `gui/LogPanel`、`gui/ProgressDialog`、`gui/Tables`、`gui/PageIntro`、`gui/Buttons`、`gui/UiScale`。

MainFrame 改动：
- tabs.addTab("分层建模", layerPage)，构造 layerPage = new LayerPage(this)。
- 新增 RES_LAYER 常量与 RES_LABEL 条目；syncRunStates 纳入第四页按钮可用性。
- Host 契约：LayerPage 定义自己的 Host 接口（currentConfig/connected/exportRoot/warn/informError/runLayerTask），MainFrame 同时实现 SqlPage.Host 与 LayerPage.Host。runLayerTask 把日志去向指到 layerPage.logPanel()、资源位占 RES_LAYER。
- refreshDbLabels 切页刷新覆盖第四页。

## 6 库模型与连接（B 已确认）

四个独立 MySQL 库，对齐平台 ods/obj/dom/ads 四库：
- 顶部共享连接栏连到 MySQL 服务器（host/port/user/password），口令只进 JPasswordField，全程不落文件、不进日志（沿用既有口径）。
- tab4 内自带「源库」下拉，默认 ods，从服务器库列表填充（复用 listDatabases）。
- 三个目标库名 obj/dom/ads，界面可改（默认即这三个名）。
- 生成与入库一律用全限定名 `库`.`表`，跨库读源、写目标。
- 入库模式下目标库不存在则 `CREATE DATABASE IF NOT EXISTS`（utf8mb4）；只生成脚本模式不碰库。

源库表名匹配：映射文件以"表英文名"为键。tab4 读源库实际表名后按名匹配；匹配不上的在界面标"未绑定"，由用户手工指认源表。映射文件预留可选列"ods物理表名"，内场实际命名（如 ods_<系统码>_<拼音>）与建模名不一致时填写，tab4 优先按此列匹配。此项列为待核风险（见 §13）。

## 7 数据流与三按钮（D）

依赖顺序：obj、dom 并行从 ods 出；ads 从 obj＋dom 出。

按钮可用性按依赖锁：
- 载入映射＋读过源库结构后，「生成对象层 obj」「生成领域层 dom」可点。
- 「生成主题层 ads」在 obj 与 dom 均已生成（产物存在或已入库）后才亮；未满足时灰置并就地说明原因。

各按钮职责：
- obj：按映射"涉及对象维度"列，从命中的 ods 表抽实体字段，建 10 类 dim 表（按实际命中，不硬凑空类），产 DDL＋抽取转换 SQL＋字段映射。
- dom：按映射"领域子类"列，把 ods 明细表整编为 33 子类明细表（按实际命中），产 DDL＋行级映射转换 SQL＋字段映射。
- ads：按映射"主题归属"列，在 obj＋dom 上建主题汇总表，产 DDL＋模板聚合 SQL＋字段映射。

每按钮固定产三份文件（见 §9），入库模式下另执行到目标库。

## 8 转换 SQL 生成

统一产集合式 `INSERT INTO 目标 SELECT ... FROM 源`（MySQL 可直接跑），既写盘也供入库执行。

- obj 抽取：`INSERT INTO obj.dim_x SELECT DISTINCT 实体字段, UUID() entity_id, 源标识 src_entity_id, ... FROM ods.源表`；同一维度多来源用 UNION ALL 汇聚，保留全部来源标识（186 来源可枚举）。
- dom 整编：`INSERT INTO dom.dwd_x SELECT 同名直映字段, 治理字段... FROM ods.源表`；字段例外按字段映射窗里的转换规则（常量/表达式/代码 lookup）替换。
- ads 聚合：`INSERT INTO ads.主题表 SELECT 维度字段, COUNT(*) src_row_cnt, SUM(数值字段) ... FROM dom.明细 JOIN obj.维度 GROUP BY 维度`；指标编码/口径留模板占位，标待核。

治理字段（表 5-3，obj/dom 自动补）：entity_id、src_entity_id、std_code_<对象>、src_code_<对象>、tags、data_level、mask_policy、quality_flag、quality_msg、de_map_id、src_batch_id、merge_rule_id、update_time、valid_flag。
ads 通用字段（表 6-4）：stat_date、stat_grain、dim_key_json、idx_code、idx_value、idx_unit、calc_model_id、caliber_ver、src_row_cnt、excluded_row_cnt、etl_batch_id、quality_flag。

src_batch_id 构成血缘链路（175/224）：入库时取本批次号；只生成脚本时以占位符 `@src_batch_id` 表达，执行时由会话变量代入。

## 9 产物与运行模式（E 已确认）

输出根目录：程序同级 `layer_YYYYMMDD/`（复用 MainFrame.programDir()，与 MetaExport 同落点口径）。每层三份：
- `<层>_ddl.sql`：建表语句（含表/字段注释，复用 Ddl.buildDdl）。
- `<层>_transform.sql`：转换/抽取/聚合 SQL。
- `<层>_field_mapping.csv`：字段映射（208 证据）。

运行模式开关「同时写入目标库」：
- 关（默认）：只生成 DDL＋脚本＋字段映射，不连库写入。外场生成好，整目录拷进内场；内场无数据也能先建结构。
- 开：生成并执行入库（外场有数据时验证）。建表走 DbClient.createTable，灌数走转换 SQL 执行；目标库不存在先建库。

收尾日志＋弹窗双写同一句话，给出产物目录路径与每层表数/行数；失败按表隔离（见 §11）。

## 10 归一与聚合深度（F，验收最小集）

- 实体归一（186）：obj 抽实体字段，entity_id 生成 UUID，保留 src_entity_id 与全部来源标识。满足"归一结果保留来源可枚举"；不做跨源模糊匹配。归一规则可配置（映射文件/字段映射窗），标待核。
- 代码归一（186）：std_code/src_code 双字段并存，映射依据可配置可回溯；不内置自动码表对齐。
- 主题聚合（188）：按表 6-1 聚合维度产模板聚合 SQL（GROUP BY＋COUNT/SUM）；具体指标口径（表 6-5）留业务方在脚本里核改。满足"主题可追溯来源＋模型可查"。

三处均标"待核"。验收要"有功能、可配置、可回溯"，不是全自动智能归一。

## 11 错误处理

沿用任务框架：
- 子线程算＋非模态进度框可取消＋回 EDT 落界面；界面从不自己算。
- 单表失败不掀整轮：记日志（行首符号＋分级标签），继续下一张；收尾汇总成功/失败数。
- 转换失败值按 Ddl.convert 既有口径收集告警（不静默置 NULL）。
- 资源位 RES_LAYER 互斥：第四页任务跑着时本页按钮灰置，被挡下要说清在等哪一位；不影响前三页。
- 连不上源库/目标库：沿用既有"连接失败＋库列表未加载"口径，日志＋error 级弹窗。
- 映射载入失败（缺列/编码错/空文件）：明确报错指出缺哪列，不静默用空映射。
- 未绑定源表：生成时跳过并在日志列出，收尾汇总报"未绑定 N 张"。

## 12 UI 布局

第四页纵向三段（仿第一页比例）：
- 顶部：PageIntro「读 ods 建模 obj/dom/ads」＋一行控制（源库下拉、载入映射文件按钮、映射文件名标签、目标库 obj/dom/ads 三个名）。
- 中部：清单 JTable（Tables.style/zebra），列＝源表名/表注释/字段数/目标领域/领域子类/对象维度/主题/映射状态/绑定状态。双击行弹字段映射窗（左右对照：源字段带注释 vs 目标字段下拉＋转换规则）。
- 底部：动作条＝运行模式开关「同时写入目标库」＋三按钮「生成对象层 obj」「生成领域层 dom」「生成主题层 ads」＋停止；下方 LogPanel。

文案口径沿用既有风格：就地说明灰置原因，tooltip 用 html 换行，日志行首符号＋大写分级标签。

## 13 测试与验收

走 Java 既有自检缝（GuiMain modalProgress=false 原地跑＋java/verify 对撞/GuiDump/UiShot），断言锁副作用而非"没报错"：
- MappingFile：解析 15 列 CSV、缺列报错、UTF-8-BOM/GBK 编码、空文件、字段映射读写往返。
- LayerGen 离线：给定映射＋源结构夹具，产 DDL 与 Ddl.buildDdl 口径一致；字段映射覆盖率 100%（208）；治理字段齐备（表 5-3/6-4）；转换 SQL 可被 MySQL 解析。
- 依赖锁：obj/dom 未生成时 ads 按钮灰；生成后亮。
- 运行模式两态：关＝只落盘不连库（断言无 DB 副作用）；开＝目标库真建表真灌数（断言行数）。
- 单表失败隔离：夹具一表坏，其余照跑，汇总数对。
- 变异抽查：改坏一处（如漏补 entity_id、覆盖率断言放宽）守卫须真变红，报可读 FAIL 行而非掀整轮。
- 入口实跑：交付的启动入口（一键打包-Java版.bat 产物）实跑一次第四页生成。

验收对照条款：183 十类齐备、184 七领域 33 子类覆盖、188 主题可追溯、186 归一字段并存可回溯、208 逐字段关联导出、224 血缘 src_batch_id。

## 14 改动清单

新增：
- java/app/src/main/java/com/excel2mysql/gui/LayerPage.java
- java/app/src/main/java/com/excel2mysql/core/MappingFile.java
- java/app/src/main/java/com/excel2mysql/core/LayerGen.java
- 对应自检/夹具（java/verify 或 GuiMain 自检段）

修改：
- gui/MainFrame.java：挂第四页、RES_LAYER、Host 扩展、syncRunStates/refreshDbLabels 覆盖。
- 视需要 core/Infer.java（Column 复用，不改口径）、core/Ddl.java（若需 CREATE DATABASE 辅助则新增静态方法，不改既有 buildDdl）。

不改：Python 两端、Java CLI、既有三页逻辑、README 核心口径（第四页使用说明随 HelpDoc 增补）。

## 15 风险与待核

- 源库表名匹配：建模名（t_xxx）与内场 ods 物理名（ods_<系统码>_<拼音>）可能不一致。机制：预留 ods物理表名 列＋界面未绑定手工指认。需用户确认内场 ods 实际命名后固化匹配键。
- 表级分类为启发式初稿：67/312 走系统兜底（低置信），权威归类待业务方核；tab4 按映射执行，不替业务方改分类。
- 归一与指标口径深度：验收最小集，跨源匹配/码表对齐/指标公式留可配置＋待核，非全自动。
- 主题取舍：表 6-1 为框架，具体主题与口径须业务方逐条确认（条款 188 注）。
- 数据元标识符：待需求方按 GJB 6495.1/GB/T 18391 赋码，tab4 不自行编造（字段映射列标"待赋码"）。