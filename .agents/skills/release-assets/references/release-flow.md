# Release assets 同步细节

## 触发判断

- `version.py` 的 `APP_VERSION` 是唯一版本源；tag 固定为 `v` + 整段版本（含 `-rc.1` 这样的后缀）。
- 自动触发只认 major：去掉后缀后必须形如 `X.0.0`（如 `7.0.0`、`7.0.0-rc.1`）；`6.4.0` 与 `6.3.1` 不触发。
- 脚本用 `is_major()` 在打包前判闸，非 major 直接退出；用户明确要求给 minor/patch 发版才加 `--force`。
- 同 tag 的 Release 已存在且三个资产尺寸、发布说明里的 SHA-256 都一致时，脚本只复核不重传。
- 版本带 `-alpha.` / `-beta.` / `-rc.` 时创建为 prerelease；转正去后缀后号不变，Release 也跟着转正式。
- 版本号未变时不要手动跑完整流程；`--skip-build` / `--no-upload` 只用于排障。

## 执行顺序

1. 用 `git status --porcelain --untracked-files=no` 确认已跟踪文件干净；不干净直接报，不带脏源码出包。
2. 把旧 `dist/excel2mysql`、`dist/excel2mysql-cli`、`dist/excel2mysql-java-<版本>-win64`、
   `dist_packtest*`、`build` 移到 `.workbuddy/dist-backup-<tag>-<时间戳>/`。
3. 按顺序跑 `一键打包-图形版.bat`、`一键打包-命令行版.bat`、`一键打包-Java版.bat`；任一非 0 即停。
4. 用 `zipfile.ZIP_DEFLATED` 压缩三个目录，逐包 `testzip()`，算 SHA-256。
5. 找或建 Release，写说明，上传 / 替换 / 跳过资产，最后用 API 复核资产清单。

## 为什么先挪目录

本机安全策略会拦住 PyInstaller 与打包脚本对超过阈值的一批文件做删除：
`SAFE_DELETE_BULK_CONFIRM_REQUIRED`（旧 GUI 包 925 个文件就撞上过）。
`mv` 到备份目录不触发删除，构建脚本在空路径上重新生成，旧产物同时留底。
备份目录不自动清理，也不入库；需要回收时由人确认后再处理。

## GitHub API 坑位

- 完整 body 的首次 `POST /releases` 实测返回过 500；先建最小 body，再 `PATCH` 完整说明。
- `PATCH` 也会偶发 500，脚本按 2/4/6 秒退避重试 4 次；不要看到一次 500 就改发布文案。
- `uploads.github.com` 上传前两次 500、第三次成功是实测过的；上传重试 3 次。
- 令牌顺序：`GITHUB_TOKEN` → `GH_TOKEN` → `git credential fill`；需要 `repo` 权限，令牌绝不落盘。
- 同步判据是发布说明里的 `- \`<zip 名>\`: \`<64 位 sha256>\`` 行，配合资产字节数；两样都同才跳过。
- 资产尺寸或哈希不一致时先 `DELETE` 同名旧资产再上传，避免 Release 里出现两个同名文件。

## 资产命名

| 端 | 源目录 | zip 名 |
| --- | --- | --- |
| 图形版 | `dist/excel2mysql` | `excel2mysql-<版本>-gui-win64.zip` |
| 命令行版 | `dist/excel2mysql-cli` | `excel2mysql-<版本>-cli-win7.zip` |
| Java 版 | `dist/excel2mysql-java-<版本>-win64` | `excel2mysql-java-<版本>-win64.zip` |

zip 内保留源目录这一层顶层文件夹，解压后直接得到可运行的目录；名字不许按平台习惯另改。

## 失败恢复

- 构建失败：修完重跑同一条命令；已挪走的旧目录留在备份里，不会污染新产物。
- 发布说明 PATCH 失败：重跑会重新 PATCH，资产不会因此重传。
- 上传中断：重跑同一条命令；只有尺寸或哈希不一致的资产才会被替换。
- 复核发现资产错：不要手工改 Release 正文，让脚本按本地 zip 重算并覆盖。

## 人工复核

- 脚本末尾 `FINAL OK` 与 Release URL 是必看两行；`ASSET` 行给出三个远端字节数。
- 本地 SHA-256 由脚本逐包打印，应与 Release 正文逐字一致。
- 打开 Release 页面确认 Assets 区三个文件可见、标题版本与 tag 一致。
