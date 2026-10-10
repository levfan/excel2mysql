---
name: git-commit
description: 本项目（excel2mysql）的提交纪律：每一次改动写完并通过验证就立即按性质拆分 commit 并推送到 origin/main，不等用户开口；验证不通过不提交；凭据、构建产物、敏感路径永不入库。适用于写完一段代码/修一个 bug/补一次文档之后的收尾动作。
whenToUse: 完成任意一处改动需要提交代码；用户说"提交""推一下""收尾"；或改动已通过验证、按本规则应当自动提交时。
---

# excel2mysql 提交规范

## 核心规则：改完 + 验证通过 = 立刻提交推送

- **每一次改动**（不要求攒成一个"功能"）写完并通过验证后，立即产生 commit 并推送，
  不把工作区长期留着未提交状态，**不需要等用户开口**
- 验证不通过**禁止提交**：红的不进库；先修到绿再走提交流程
- 一次会话里做了多件事，就按性质拆成多个 commit；不同性质禁止混进同一个 commit
- 顺序固定：确认验证通过 → 扫一遍待提交内容 → `git add <具体路径>` → `git commit`
  → `git pull --no-rebase origin main` → `git push origin main`
- 远端：`https://github.com/levfan/excel2mysql.git`，分支 `main`，upstream 已配置
- 推送失败（凭据/网络/非快进）时：保留本地 commit，报告原因等用户处理；
  **禁止** force push、禁止 `git pull --rebase` 改写已推送的历史

## 验证口径（按改动类型选，别一律跑全套）

| 改动类型 | 必须跑的东西 | 通过标准 |
| --- | --- | --- |
| 任何代码改动（`main.py` / `core/*.py` / `ui_help.py` / `help_doc.py`） | `.venv/Scripts/python.exe pack_test.py` | **退出码 0** 且汇总 `失败 0` |
| 只改测试用例 `pack_test.py` | 同上（用例自己就是被测对象） | 退出码 0 |
| 打包口径（`*.spec` / `_pack_common.py` / `build_exe.py` / `一键打包.bat`） | 先 `pack_test.py`，再 `build_exe.py` 全闸门 | 闸门 7 步全过、产物能起窗 |
| 纯文档（`README.md` / `help_doc.py` 文案 / `.agents/**`） | 自检即可：frontmatter 合法、路径存在、无待办占位；`help_doc.py` 改动要能 `import` 且 `pack_test.py` 的 P 组不红 | 上述检查通过 |

- **"全绿"必须拿退出码**：`echo $?` 为 0 才算过，不能靠肉眼看输出里有没有 FAIL
- 新增/修改断言后，要做一次**变异抽查**：把对应产品代码故意改坏，确认用例真的变红，
  再逐字节还原。写一条咬不住的断言比不写更糟
- 跑 G 组（真实 MySQL）需要 `PT_USER` / `PT_PASS` / `PT_DB` 环境变量；
  **口令只走命令行环境变量，禁止写进任何文件**。没设时 G 组会 SKIP 并显式记为"未覆盖"，
  这不算失败，但要在汇报里说清楚
- **打包（PyInstaller）只在用户明说"打包"时才做**，与提交规则互不覆盖：
  提交是每次改动的收尾动作，打包是单独指令

## 拆分与提交信息

```
type(scope): 祈使句中文描述，不超过 50 字，结尾不加句号
```

- `type`：`feat` / `fix` / `test` / `docs` / `refactor` / `perf` / `build` / `chore`
- `scope` 用本项目真实模块：`gui`（main.py 界面）、`infer`（core/infer.py 类型与命名）、
  `zip`（core/zip_import.py）、`merge`（core/merge_check.py）、`sql`（core/sql_script.py）、
  `naming`（core/naming.py）、`mysql`（core/mysql_io.py）、`progress`（进度框与子线程）、
  `help`（help_doc.py / ui_help.py 使用说明）、`pack`（打包与 spec）
- body 写动机与影响，不逐文件罗列；用 heredoc 传多行消息避免转义事故
- 本项目没有数据库迁移脚本，**不要**套用"db 独立成 commit"那类外部规则

示例（一次带测试的功能改动 = 两个 commit）：

```
feat(naming): 新增「替换首个前缀」按首段改写表名
test(naming): 覆盖首段切分、撞名去重与 SQL 引用同步改写
```

## 入库红线

- 提交前必须 `git add` 具体路径并 `git status` 复核；**禁止无脑 `git add -A`**
- 提交前扫一遍待提交内容有没有口令与内部标识：
  `git diff --cached | grep -inE "PT_PASS=|password.*=.*['\"].{4,}|token|<内部项目代号>"`
- 永不入库：`.venv/`、`dist/`、`dist_packtest/`、`build*/`、`__pycache__/`、`.idea/`、
  Excel 临时锁文件 `~$*`（已在 `.gitignore` 覆盖）
- `core.zip` / `excel2mysql.zip` 是历史遗留的源码副本，**保持未跟踪**，
  既不提交也不删除，等用户决定
- `.gitignore` 里 `!excel2mysql.spec` / `!packtest.spec` 两条否定**不能删**：
  GitHub 的 Python 模板含 `*.spec`，删掉会让打包配置静默漏出仓库，别人 clone 后构建不了
- `*.bat` 必须保持 CRLF（`.gitattributes` 已固定），改 `一键打包.bat` 后不要顺手转成 LF
- 公开仓库：文档与注释里不出现内部数据包代号、内网地址、他人姓名等

## 与其他规则冲突时

- 用户当次明确指令 > 本技能；本技能 > 工作区级同名技能（本项目自带一套，以本项目为准）
- 不可逆操作（删文件、`git reset --hard`、动远端分支、改 git config）一律先问用户
