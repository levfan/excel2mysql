---
name: java-dependency-selection
description: 本项目（excel2mysql）Java 侧的能力选型纪律：需要实现解析、编码、压缩、协议、格式转换等非平凡能力时，先评估能否用 pom 里的第三方 jar 解决，禁止默认万事自研；引入依赖必须过稳定版、仍在维护、Java 8、许可、离线胖 jar、体积、安全与可验证性七道闸。适用于准备手写切句/解析/编解码之前，或准备往 pom 加依赖、升级依赖时。
whenToUse: 要写一个不简单的 Java 侧模块（SQL 切句、编码识别、压缩/解压、日期与数字解析、协议客户端等）；准备在 pom 加新依赖或换版本；发现自己在想“这个只能自己写”。
---

# Java 侧依赖选型：先选型，再动手

## 核心规则

**默认先找现成的。** 引入一个维护良好的第三方 jar 是正常工程手段，不是失败；
手写解析器、编解码器、协议实现之前，必须先证明“没有候选过闸”。
但依赖也不能随手加：本项目交付形态特殊，候选必须同时过下面七道闸。

## 依赖来源：只走国内阿里云镜像

**不查、不连国外仓库。** 依赖、插件、版本元数据一律走阿里云 Maven 镜像：

- 推荐在本机 `~/.m2/settings.xml` 配全局镜像（对 Maven 透明，任何模块都生效）：

  ```xml
  <settings>
    <mirrors>
      <mirror>
        <id>aliyun</id>
        <name>Aliyun Public</name>
        <url>https://maven.aliyun.com/repository/public</url>
        <mirrorOf>*</mirrorOf>
      </mirror>
    </mirrors>
  </settings>
  ```

- 项目内 `java/pom.xml` 已声明 `aliyun-public` 的 `<repositories>` 与
  `<pluginRepositories>`；本机没有 settings.xml 时也走国内，不回落国外。
  需要 spring / google 等专用仓时，加 `https://maven.aliyun.com/repository/spring`、
  `.../google`，不要直接写国外地址。
- 版本与发布时间只查阿里云镜像的元数据：
  `https://maven.aliyun.com/repository/public/<groupId路径>/<artifactId>/maven-metadata.xml`，
  或阿里云仓库的目录页；不访问 Maven Central、GitHub Releases、JitPack 等国外站点。
- 国内镜像查不到某个版本/构件，本身就是淘汰信号（目标机离线、镜像不全的依赖不进交付）。
- CVE 与安全公告优先查阿里云漏洞库/镜像页；国内源查不到就写“待核实”，
  禁止把“没查到”写成“没有漏洞”，也禁止编造发布日期。

## 决策顺序（每一步都要留下结论）

1. **先盘点已有能力**：JDK 8 标准库、已打包的依赖、Python 侧对应实现是否构成
   “必须逐用例对齐”的规范。已有库能覆盖的，不新增第二个同类库。
2. **列候选，至少两个**（含“自研”这一项），逐个填表：

   | 列 | 填什么 |
   |---|---|
   | 坐标与版本 | `groupId:artifactId:version`，只写具体版本 |
   | 最近稳定版时间 | 阿里云镜像 `maven-metadata.xml` 里的 `lastUpdated` / 版本目录，写年月 |
   | Java 8 | 支持到哪个版本；class major 必须 ≤ 52 |
   | 许可证 | Apache-2.0 / MIT / BSD / EPL 可用 |
   | 传递依赖与体积 | `mvn dependency:tree`，新增 jar 总增量 |
   | 离线胖 jar | 有无 native、运行时下载、`META-INF/services` 冲突 |
   | 可验证性 | 能否用现有金标准/真实语料逐用例比对 |
   | 淘汰理由/选定理由 | 一句话 |

3. **过闸（硬性条件，缺一即淘汰）**：
   - **稳定版**：只认 GA/stable；禁止 alpha、beta、RC、M1、SNAPSHOT、`LATEST`、`RELEASE`、版本区间。
   - **仍在维护**：最近 18 个月内有稳定版发布；安全相关库收紧到 12 个月。
     “多年没更新”默认淘汰；只有“功能已完成、无未修 CVE、源码可接手、使用者面大”
     三条同时成立才可例外，并把例外理由写进 pom 注释。
   - **Java 8 兼容**：必须能在 `C:\Program Files\Java\jdk1.8.0_202` 下编译运行；
     “支持 Java 8 的最后一个版本”要写清，别让下次升级时误升。
   - **许可证**：单 jar 分发属于静态合并，**禁止 GPL / AGPL / LGPL** 及许可不明；
     只用 Apache-2.0、MIT、BSD、EPL 这类可再分发许可。
   - **离线与形态**：不得有 native `.dll/.so`、不得运行时联网下载、不得写注册表、
     不得起守护线程；所有传递依赖必须能 shade 进一个 jar，并在 Win7 + 绿色 JRE 8 上可跑。
   - **体积**：新增依赖连同传递依赖的 jar 增量超过约 2 MB 要单独写理由；
     用不到的可选依赖（典型如 protobuf 之于经典 JDBC）显式 `<exclusion>`。
   - **安全与行为**：查一遍 OSV / GitHub Advisory / CVE；无遥测、无后台外联；
     行为必须能被现有用例或真实语料钉住。

4. **双栈对齐条款**：Python 与 Java 输出要逐项一致的功能，库只能当“参考实现”
   或“探针”；进产品路径前必须包一层，并在 Python 侧金标准语料上全绿。
   库的输出与本项目既定语义冲突时，改库的用法或换库，**不许悄悄改 Python 语义去迁就库**。

5. **先做探针**：在 verify 模块或临时分支写最小可运行用例（半天到一天），
   喂真实语料（用户现场文件、边界样例、异常样例），与基线比输出。
   探针不过就换候选或自研，不把“README 说支持”当验证。

6. **落地**：版本属性与版本号进父 pom `dependencyManagement`；app pom 只引用不带版本；
   pom 注释写“为什么选它、淘汰了谁、体积/许可/Java 8 的结论”。
   `mvn dependency:tree` 输出与探针结论进提交说明。

7. **验收**：跑 `java\构建验证.bat` 十步闸门、`java\界面双端对撞.bat`、两栈用例；
   涉及执行/导入语义的，再跑真库 G 组。任何一步红都不算落地。

## 什么时候可以自研

必须至少满足一条，并把理由写进提交说明：

- 所有候选都没过上面的闸；
- 能力小且边界清楚（例如纯词法状态机、十分钟能穷举的规则），并且双栈要求逐字节一致；
- 引进来会破坏交付形态（native、Java 8 不兼容、许可不可分发、体积失控）；
- 库的行为不可观测/不可控，现有金标准覆盖不了它。

“自己写更放心”“加依赖显得不专业”“先写起来再说”都不算理由。

## 反模式（出现即停）

| 说法 | 现实 |
|---|---|
| “这个只能自己写” | 先列候选再下结论；没查过 Maven Central 不算查过 |
| “用最新版总没错” | 最新版可能是 RC；只认稳定版并钉死版本 |
| “三年没更新也没事” | 默认淘汰；例外要三条证据 |
| “SNAPSHOT 和正式版一样” | 不可复现构建，禁止 |
| “License 以后再说” | 打包前必须清楚；copyleft 不能进单 jar |
| “有 CVE 但没用到那个类” | 需要可达性证据与记录，不能拍脑袋 |
| “胖 jar 大一点无所谓” | 绿色 JRE 交付对体积敏感，超预算要写理由 |
| “库说它对，Python 那边改一下” | 双栈语义由金标准定，不由库定 |

## 本项目锚点

- JDK 8：`C:\Program Files\Java\jdk1.8.0_202`；class 主版本闸门在 `java\构建验证.bat` 第 3 步。
- 交付：`maven-shade-plugin` 打单 jar，随包 JRE 8u202，目标机离线、可能 Win7。
- 依赖版本统一定义在 `java\pom.xml` 的 `<properties>` 与 `<dependencyManagement>`；
  模块 pom 不写版本。
- 仓库同样写在 `java\pom.xml`（`aliyun-public` 的 repositories / pluginRepositories），
  本机 `~/.m2/settings.xml` 配了同一镜像的 `<mirrorOf>*</mirrorOf>`；两处都不指向国外。
- 现有依赖示例：`cn.idev.excel:fastexcel:1.3.0`、`com.mysql:mysql-connector-j:8.0.33`、
  `com.github.stuxuhai:jpinyin:1.1.8`、`org.slf4j:slf4j-nop:1.7.36`（须与 slf4j-api 同版本）。
