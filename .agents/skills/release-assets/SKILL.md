---
name: release-assets
description: 本项目只有 major 版本更新（X.0.0，含 rc 等后缀）才触发的发布纪律：重建三端、打 zip、同步 GitHub Releases 的 assets 并写 SHA-256；minor/patch 不自动发版。
whenToUse: version.py 判到 major 升级且验证提交完成后；用户明确说 major 发版、传 assets；git-commit 判到 major 时。
agent_created: true
---

# excel2mysql Release Assets 同步

## 触发条件

- 只有 major（X.0.0，含 `-alpha` / `-beta` / `-rc` 后缀）自动触发；minor/patch 不跑
- 判到 major 且验证全绿、改动已提交后才跑本技能
- 同版本 Release 已存在时只补差异：资产尺寸与 SHA-256 都一致就跳过
- 版本号没变不跑；`--skip-build` 只用于复用现有 `dist` 排障
- 用户点名要给 minor/patch 发版时才加 `--force`；产物不入库

## 一条命令

```bash
.venv/Scripts/python.exe .agents/skills/release-assets/scripts/release_assets.py
```

- `--skip-build`：复用当前 `dist`，不重跑三端闸门
- `--allow-dirty`：仅排障用，跳过已跟踪文件洁净检查
- `--no-upload`：只出本地 zip 与 SHA-256，不碰 GitHub
- `--force`：越过 major 闸门，仅用于用户明确要求发 minor/patch

## 脚本动作

- 把旧 `dist`、`dist_packtest`、`build` 移到 `.workbuddy/dist-backup-*`，绕开批量删除保护
- 依次跑图形版、命令行版、Java 版一键包；任一退出码非 0 立即停
- 按版本名压缩三个产物目录，逐包 `testzip()` 并算 SHA-256
- 建或找 `v<版本>` Release，写说明，上传 / 替换 / 跳过资产，最后复核清单

## 硬约束

- 脚本开头判 major；非 X.0.0 直接退出，没有 `--force` 不许发
- 令牌只从环境变量或 `git credential fill` 取，禁止写进仓库与日志
- 带 `-alpha` / `-beta` / `-rc` 的版本标 prerelease，正式版不标
- 资产名固定为 gui / cli / java 三个模板，不许改名或增删
- GitHub 偶发 500 由脚本重试；最终失败保留本地 zip 并报 HTTP 码与资产名
- 上传前先确认已跟踪工作区干净，避免把半截改动发出去

## 与提交纪律衔接

- 判档仍归 `git-commit`；只有判到 major 才接本技能，不再等用户开口
- 汇报必须给 Release URL、三个资产名、以及 SHA-256 是否一致
- 详细流程、API 坑位与排障口径见 `references/release-flow.md`
