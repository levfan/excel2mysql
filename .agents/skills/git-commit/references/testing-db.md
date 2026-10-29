# 本机测试库与 G 组

用户 2026-10-22 原话："测试库的环境是……这个你可以写到项目 skills 中，不要每次都问我。"
所以这事以后不再确认。

## 环境事实

- `127.0.0.1:3306`，用户 `root`，库 `testdb`。服务端是 WSL 里 docker 容器 `mysql8`
  的 MySQL 8.0.46（`utf8mb4_unicode_ci`，`STRICT_TRANS_TABLES` 开着）。
- **这就是拿来折腾的测试库**：G 组会建表、导入、查询、回滚，只动 `packtest_probe*`
  / `e2m*` 这类探针名，不碰别的表。
- 口令放在仓库根的 `.pt_env`（`.gitignore` 里 `/.pt_env` 那条已经把它挡在库外），
  内容是 `PT_USER` / `PT_PASS` / `PT_DB` / `PT_HOST` / `PT_PORT` 五行 export。
- **口令本身不进任何被跟踪的文件**，包括技能与文档：远端是公开仓库，写进一次 commit
  就永久留在历史里，事后删文件撤不掉。技能只写"怎么载入"。
- `.pt_env` 不在了就回问用户要口令（不自行编造、不从别处猜），拿到后重新落一份同样
  不入库的文件；这是"不再每次问"的唯一例外。

## 跑法

Git Bash，仓库根目录：

```
set -a; . ./.pt_env; set +a
.venv/Scripts/python.exe pack_test.py        # 图形线，G1-G9 应当真跑
.venv38/Scripts/python.exe pack_test38.py    # Win7 线
.venv/Scripts/python.exe pack_test38.py      # 同一套在新栈上再跑一遍
```

## 判据与记账

- 带凭据跑之后 **G 组一条 SKIP 都不许留**。端口连不通（WSL/docker 没起）才算"未覆盖"，
  汇报时要说清是端口不通、不是没配凭据。
- 只报 Q 组绿不等于证明过生成的 DDL 能被 MySQL 接受——那是假连接，两件事在报告里
  必须分开说。
- 带凭据时剩下的 SKIP 只有 `PT_XLS`（本机没有 `.xls` 样本）、`CORPUS_DDL`（缺真实语料）
  与冻结态专属项那几条，它们与凭据无关，别把它们当成"G 组还没跑"。
- 跑前扫一眼 `testdb` 里有没有上一轮被中断留下的 `packtest_probe` / `packtest_probe_bad`：
  G 组会 DROP 同名表，残留会被读成这一轮的产物。
