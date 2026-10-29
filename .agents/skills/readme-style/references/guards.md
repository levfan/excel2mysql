# README 的机器守卫全表

README 不是自由文本，它被下面这些断言按字面串核。改文档前先对着这张表确认谁在数你要动的串。

| 守卫 | 在哪个文件 | 咬什么 |
| --- | --- | --- |
| W29 | `pack_test38.py` | 含 `excel2mysql-cli` 的行里出现的 `--xxx` 必须是 argparse 真有的 |
| W29b | `pack_test38.py` | 全篇每个 `--xxx` 都真实存在，且写明完整清单看 `--help`。放行表 `_JAVA_ONLY` 里放的是 Java 线的 `--no-sql` / `--no-excel` / `--gui`——它们不在 Python 的 argparse 里，但出包闸门要求 README 写到 |
| W29c | `pack_test38.py` | 八节名齐 + 篇幅区间。节名与区间住在 `_pack_common.py` 的 `README_SECTIONS` / `README_LEN_RANGE`，两条出包闸门读同一份 |
| W29e | `pack_test38.py` | 反向核：全文不许出现版本号形态的串。只认三种形状——`vX.Y.Z`（可带 `-alpha.1` 之类后缀）、`excel2mysql` 后跟 `X.Y.Z`、`.jar` / `-win64` 的产物名。`conn.ini` 示例里的 `127.0.0.1` 长得像三段数字，所以刻意不写通用三段数字正则 |
| W30 | `pack_test38.py` | 只在「退出码」那一节取被反引号包住的数字，与 `cli.py` 的 `EXIT_*` 常量加 `130` 必须**完全相等**，少一个或多一个都红 |
| W31 | `pack_test38.py` | 不许出现像真凭据的口令值；`password = 改成你的密码` 这类占位算合规。只在一行内判断，别跨行 |
| W31b | `pack_test38.py` | 出现字面串 `下划线自己写` |
| W31c | `pack_test38.py` | `KB2999226`、`DLL load failed`、`只拷 exe`、`跑不起来`、`win7_sp1_patch`、`3.check_win7.bat`、`4.install_minimal.bat` 全要在——现场要能从文档知道修复脚本就在包里 |
| W31d | `pack_test38.py` | `Java 版`、`Windows 10 1809`、`三个包共用同一套`、`RELEASE.md` 在 README；并且 `api-ms-win-crt`、`pack_test38.py` 真落在 RELEASE.md 里（搬出去的内容不能只是删掉） |
| 出包文档步骤 | `build_exe.py`、`java/tools/build_java_dist.py` | 读同一份常量核产物里那一份 README：字符区间 + 八节齐 + 标题挂了 `assets/app_icon.png` + 与仓库逐字节一致 |
| 出包 must 清单 | `java/tools/build_java_dist.py` | `java.exe -jar` / `启动.bat` 那几行不许出现本包不认的开关（`--run-sql`、`--swap-prefix`、`--drop-if-exists`、`--keep-temp`）；`--no-sql`、`--no-excel`、`--gui`、`Windows 7`、`KB2999226`、`退出码` 要写到；同样禁版本号形态 |
| T15 | `pack_test.py` | 写死的版本号与 `version.py` 同值。README 已从站点表里删掉（2026-10-29），别加回去 |

## 这一轮改守卫的形状（可照抄的判断方式）

- `W30` 从"读表格首列"换成"读反引号包住的数字"：文档形态变了，判据不变严——仍然要求
  与常量集合完全相等。
- `W29c` 篇幅上限从 6000 收到 3200：收紧。
- 新增 `W29e`：把"不写版本号"从口头要求变成机器判定，而且是反向核（出现就红），
  不需要维护一份版本号站点表。
- 出包侧原来写的是"README 必须含本版版本号"，翻成"README 不许出现版本号形态"。

## 变异抽查怎么做

改完守卫必须验它咬得住，至少这三下：

1. 往 README 插一句 `当前版本 v1.2.3` → 只有 `W29e` 红；
2. 把 `## 7. 再往深` 改名 + 从退出码那句里删掉 `` `130` `` + 把 `下划线自己写` 换成别的
   → `W29c` / `W30` / `W31b` 各自红，而 `W29e` 仍绿（证明三条互不掩盖）；
3. 把出包步骤换成一份缺节的 stub README 跑 `build_exe.copy_project_doc()` → 必须 raise。

每次变异之后逐字节还原并 md5 复核。

## 两条容易漏的

- 只在一处挂文档断言不够：这一轮就是 `build_exe.py` 里那条 9000 字符的老门槛独自活着，
  把上一版"开发向 README"的口径带进了新周期，直到打包才报错。节名与区间住在
  `_pack_common.py`，用例集与两条出包闸门共读，才算一处改、三处生效。
- `java/tools/build_java_dist.py` 的那段只在打包时才执行；没打包就改了它，要在汇报里
  点明"这段本轮没实跑"，不能当已验证。
