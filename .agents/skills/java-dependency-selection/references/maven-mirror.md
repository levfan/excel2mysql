# 依赖来源与查询渠道

**不查、不连国外仓库。** 依赖、插件、版本元数据一律走阿里云 Maven 镜像。

## 全局镜像

本机 `~/.m2/settings.xml` 配一份全局镜像，对 Maven 透明、任何模块都生效：

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

项目内 `java/pom.xml` 已声明 `aliyun-public` 的 `<repositories>` 与 `<pluginRepositories>`，
本机没有 `settings.xml` 时也走国内，不回落国外。需要 spring / google 等专用仓时，加
`https://maven.aliyun.com/repository/spring`、`.../google`，不要直接写国外地址。

## 版本与发布时间从哪查

只查阿里云镜像的元数据：

```
https://maven.aliyun.com/repository/public/<groupId 路径>/<artifactId>/maven-metadata.xml
```

或阿里云仓库的目录页。不访问 Maven Central、GitHub Releases、JitPack 等国外站点。

- 国内镜像查不到某个版本或构件，本身就是淘汰信号：目标机离线、镜像不全的依赖不进交付。
- CVE 与安全公告优先查阿里云漏洞库与镜像页；国内源查不到就写"待核实"，
  禁止把"没查到"写成"没有漏洞"，也禁止编造发布日期。
