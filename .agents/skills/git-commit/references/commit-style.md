# 提交信息的细节

## scope 用本项目真实模块

| scope | 对应 |
| --- | --- |
| `gui` | `main.py` 界面 |
| `infer` | `core/infer.py` 类型与命名 |
| `zip` | `core/zip_import.py` |
| `merge` | `core/merge_check.py` |
| `sql` | `core/sql_script.py` |
| `naming` | `core/naming.py` |
| `mysql` | `core/mysql_io.py` |
| `progress` | 进度框与子线程 |
| `help` | `help_doc.py` / `ui_help.py` 使用说明 |
| `pack` | 打包与 spec、三条出包闸门 |
| `verify` | `java/GUI-对齐清单.md` 等取证记录 |
| `skills` | `.agents/**` |

`type` 用 `feat` / `fix` / `test` / `docs` / `refactor` / `perf` / `build` / `chore`。
本项目没有数据库迁移脚本，**不要**套用"db 独立成 commit"那类外部规则。

## 长度复核命令

```
git log -1 --format=%s            # 只应有一行且 ≤50 字
git log -1 --format=%b            # 应非空
git log -1 --format=%B | wc -m    # 应 ≤140
```

`wc -m` 数的是字符，中文按一个算；别用 `wc -c`（那是字节，中文一个字算三个）。
超了就重写这一条再提下一个。多行消息用 heredoc 传，避免转义事故。

## 示例：一次带测试的功能改动拆成两个 commit

```
feat(naming): 新增「替换首个前缀」按首段改写表名

现场要按业务段重命名，ods_t_order 直接改成 change_t_order。

test(naming): 覆盖首段切分与 SQL 引用同步改写

改名后脚本里的表引用要跟着走，漏一处就导错表。
```

两个提交内部的主题行与正文之间都空一行；正文各一行。

## 反面教材

- `bd8a6bb` 380 字、`d2d9c58` 299 字：把用例编号、闸门退出码、取证表格整段抄进提交信息，
  用户 2026-10-29 判为"太长、说得很啰嗦"。
- `121dded` / `5534cd5` / `c2f4a33`：主题行与正文之间只给了一个换行，git 把整段并成
  subject，`git log --oneline` 与 GitHub 提交列表里拖出超长标题。
- 这三条都已推送，历史不回改；此后的提交按上面办。
