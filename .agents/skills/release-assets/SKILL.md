---
name: release-assets
description: 本项目版本更新后的发布纪律：APP_VERSION 一变就重建三端、打 zip、把资产同步到 GitHub Releases 的 assets，并在发布说明里写 SHA-256；用于版本升级收尾、发版、上传 assets，或提交判到版本变化时。
whenToUse: version.py 或 9 处写死版本改动后；用户说发版、传 assets、更新 Release；git-commit 判到版本变化时。
agent_created: true
---

# excel2mysql Release Assets 同步

## 触发条件

- 版本判档结论是"升"，且验证全绿、改动已提交后才跑本技能
- 同版本 Release 已存在时只补差异：资产尺寸与 SHA-256 都一致就跳过
- 版本号没变不跑；`--skip-build` 只用于复用现有 `dist` 排障
- 产物不入库，发布动作与提交动作各自独立

## 一条命令

```bash
.venv/Scripts/python.exe .agents/skills/release-assets/scripts/release_assets.py
```

- `--skip-build`：复用当前 `dist`，不重跑三端闸门
- `--allow-dirty`：仅排障用，跳过已跟踪文件洁净检查
- `--no-upload`：只出本地 zip 与 SHA-256，不碰 GitHub

## 脚本动作

- 把旧 `dist`、`dist_packtest`、`build` 移到 `.workbuddy/dist-backup-*`，绕开批量删除保护
- 依次跑图形版、命令行版、Java 版一键包；任一退出码非 0 立即停
- 按版本名压缩三个产物目录，逐包 `testzip()` 并算 SHA-256
- 建或找 `v<版本>` Release，写说明，上传 / 替换 / 跳过资产，最后复核清单

## 硬约束

- 令牌只从环境变量或 `git credential fill` 取，禁止写进仓库与日志
- 带 `-alpha` / `-beta` / `-rc` 的版本标 prerelease，正式版不标
- 资产名固定为 gui / cli / java 三个模板，不许改名或增删
- GitHub 偶发 500 由脚本重试；最终失败保留本地 zip 并报 HTTP 码与资产名
- 上传前先确认已跟踪工作区干净，避免把半截改动发出去

## 与提交纪律衔接

- 判档仍归 `git-commit`；判到升版后接本技能，不再等用户开口
- 汇报必须给 Release URL、三个资产名、以及 SHA-256 是否一致
- 详细流程、API 坑位与排障口径见 `references/release-flow.md`
