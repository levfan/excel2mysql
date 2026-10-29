---
name: git-commit
description: 本项目（excel2mysql）的提交纪律：每一次改动写完并通过验证就立即按性质拆分 commit 并推送到 origin/main，不等用户开口；验证不通过不提交；提交信息主题 50 字、正文一行；凭据与构建产物永不入库。适用于写完一段代码、修完一个 bug、补一次文档之后的收尾动作。
whenToUse: 完成任意一处改动需要提交代码；用户说"提交""推一下""收尾"；或改动已通过验证、按本规则应当自动提交时。
---

# excel2mysql 提交规范

## 核心规则：改完并验证通过就立刻提交推送

- 每一次改动（不要求攒成一个"功能"）写完且验证通过后立即 commit 并推送，不等用户开口
- 验证不通过禁止提交：红的不进库，先修到绿
- 一次会话做了多件事就按性质拆 commit，不同性质不许混进同一个
- 顺序固定：验证过 → `git add <具体路径>` → commit → `git pull --no-rebase origin main` → push
- 远端 `https://github.com/levfan/excel2mysql.git`，分支 `main`，upstream 已配置
- 推送失败保留本地 commit 并报告原因；禁止 force push，也禁止改写已推送的历史

## 验证分档：按改动类型选闸门

- 分档只判一件事：**这个改动会不会改变行为**；拿不准按"会改行为"处理
- 只动字号/颜色/间距/纯文案 → 跑直接咬住它的探针、对应自检、`py_compile`；
  改到 Java 就再编那一个模块，不跑全量
- 动了推断/读取/DDL/导入/命名/脚本改写 → `.venv/Scripts/python.exe pack_test.py`
- 改了打包口径（`*.spec`、`_pack_common.py`、`build_exe*.py`）→ 先用例集，再全闸门
- 纯文档 → 自检 frontmatter 与路径；改 README 还要跑一次 `build_exe.py --fast`
- "全绿"只认退出码 0（`echo $?`），不许靠肉眼看输出里有没有 FAIL
- 新增或改过的断言要做变异抽查：故意改坏产品代码确认它变红，再逐字节还原
- 五档对照表与变异抽查的坑见 `references/verification.md`

## 本机测试库：G 组每次都跑，不再问用户

载入凭据再跑，带凭据之后 G 组一条 SKIP 都不许留：

```
set -a; . ./.pt_env; set +a
.venv/Scripts/python.exe pack_test.py
```

- 环境是 `127.0.0.1:3306` 的 `testdb`，口令只在仓库根 `.pt_env`（已被 gitignore），
  永不写进任何被跟踪文件——远端是公开仓库，进一次 commit 就撤不掉
- 只有端口真连不通才算"未覆盖"，汇报要写清是端口不通，不是没配凭据
- `.pt_env` 不在了就回问用户，不自行编造；跨栈跑法与 SKIP 记账见
  `references/testing-db.md`

## 版本号：每次收尾都要显式判档

- 先问：老版本用户有没有"以前做不到、现在能做"的一件事？有且老做法照旧 → minor
- 只是同一件事做得更对/更稳/更好看 → patch；老做法不能原样用了 → major
- 只改测试、文档、构建脚本且不影响交付物 → 不升，但结论和一句理由要写进汇报
- patch 一路升到 `x.y.9` 还要再升，先停下来重判：多半是把新增能力记成了修复
- 改的是 `version.py` 与另外 9 处写死字符串；`README.md` 不写版本号，改版本时别动它
- 三档细则、9 处站点清单与 alpha/beta/rc 见 `references/versioning.md`

## 提交信息：主题 50 字，正文一行

- 格式 `type(scope): 祈使句中文`，主题 ≤50 字、结尾不加句号
- 正文一行，只说"为什么改"；整条（主题 + 空行 + 正文）≤140 字
- 文件清单、用例编号、退出码、取证过程都不写——那些归清单文档和当轮汇报
- 主题与正文之间必须空一行，只给一个换行会被 git 并成超长 subject
- 写完复核：`git log -1 --format=%B | wc -m` 应 ≤140（`wc -m` 按字符，中文算一个）
- 这条只管下一条提交，不回改已推送的历史
- scope 表、示例与反面教材见 `references/commit-style.md`

## 入库红线

- 禁止 `git add -A`；逐路径 add 之后用 `git status` 复核暂存了什么
- 提交前扫一遍口令：`git diff --cached | grep -inE "PT_PASS=|token|password"`，
  命中就逐条看清再提交（改 `.agents/**` 时本文件那行模板会自己命中，属预期）
- 永不入库：`.venv/`、`dist*/`、`build*/`、`__pycache__/`、`.idea/`、Excel 锁文件 `~$*`
- `core.zip` / `excel2mysql.zip` 是历史遗留副本，保持未跟踪，既不提交也不删除
- `.gitignore` 里 `!excel2mysql.spec` 与 `!packtest.spec` 两条否定不能删，
  删了打包配置会静默漏出仓库
- `*.bat` 必须保持 CRLF（`.gitattributes` 已固定）；公开仓库不写内部代号、内网地址、姓名

## 与其他规则冲突时

- 用户当次明确指令 > 本技能 > 工作区级同名技能（以本项目为准）
- 不可逆操作（删文件、`git reset --hard`、动远端分支、改 git config）一律先问用户
- 打包只在用户明说"打包"时才做，与提交规则互不覆盖
