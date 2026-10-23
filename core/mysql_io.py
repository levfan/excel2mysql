"""DDL 生成与 MySQL 导入。"""
import re
from datetime import datetime

import pymysql


def _esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("'", "\\'")


def build_ddl(table_name: str, table_comment: str, columns: list,
              drop_if_exists: bool = False) -> str:
    # 主键约束至多一个，可由一个或多个字段组成（组合主键）。
    # 空值规则：主键列一律 NOT NULL；结构定义模式中「是否可为空=否」的列 NOT NULL；
    # 其余列 DEFAULT NULL（普通模式推断的列未标记 nullable，保持原行为）
    pk_fields = [c["field"] for c in columns if c.get("primary_key")]
    lines = []
    for c in columns:
        if c["field"] in pk_fields or c.get("nullable") is False:
            null_part = "NOT NULL"
        else:
            null_part = "DEFAULT NULL"
        lines.append(f"  `{c['field']}` {c['type']} {null_part} "
                     f"COMMENT '{_esc(c['comment'])}'")
    if pk_fields:
        lines.append("  PRIMARY KEY (" + ", ".join(f"`{f}`" for f in pk_fields) + ")")
    ddl = "CREATE TABLE `" + table_name + "` (\n" + ",\n".join(lines) + "\n) " \
          f"ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 " \
          f"COLLATE=utf8mb4_general_ci COMMENT='{_esc(table_comment)}'"
    if drop_if_exists:
        return f"DROP TABLE IF EXISTS `{table_name}`;\n" + ddl
    return ddl


def build_insert_sql(table_name: str, columns: list) -> str:
    fields = ", ".join("`" + c["field"] + "`" for c in columns)
    ph = ", ".join(["%s"] * len(columns))
    return f"INSERT INTO `{table_name}` ({fields}) VALUES ({ph})"


def _convert(v, typ: str):
    """
    按目标字段类型转换值。返回 (值, 错误信息)，错误信息为 None 表示成功
    （含合法空值：None / NaN / NaT / 空白字符串 → (None, None)）。
    数值列转换失败不再静默置 NULL，由调用方收集告警，避免丢数据无感知。
    """
    if v is None or v != v:      # NaN/NaT 与自身不相等，借此识别空值
        return None, None
    if isinstance(v, str):
        s = v.strip()
        if s == "":
            return None, None
        if typ == "DATETIME":
            for fmt in ("%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%Y-%m-%d %H:%M",
                        "%Y-%m-%dT%H:%M:%S", "%Y%m%d%H%M%S"):
                try:
                    return datetime.strptime(s, fmt), None
                except Exception:
                    continue
            return s, None       # 无法解析的原样交给 MySQL 裁决（严格模式会报错回滚）
        if typ == "DATE":
            for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d", "%Y%m%d"):
                try:
                    return datetime.strptime(s, fmt).date(), None
                except Exception:
                    continue
            return s, None
        if "INT" in typ.upper():     # INT/BIGINT/TINYINT/SMALLINT/MEDIUMINT/INTEGER
            try:
                return int(s.replace(",", "").replace("，", "")), None
            except Exception:
                return None, f"值 {s!r} 不是合法整数"
        if typ.upper().startswith(("DECIMAL", "NUMERIC", "FLOAT", "DOUBLE")):
            try:
                return float(s.replace(",", "").replace("，", "")), None
            except Exception:
                return None, f"值 {s!r} 不是合法数字"
        return s, None
    t = typ.upper()
    if "INT" in t and isinstance(v, bool):
        return int(v), None
    if t.startswith(("DECIMAL", "NUMERIC", "FLOAT", "DOUBLE")) and isinstance(v, bool):
        return float(v), None
    return v, None


def _coerce(v, typ: str):
    """兼容旧接口：仅返回转换后的值。"""
    return _convert(v, typ)[0]


def _convert_rows(rows, coltypes, warn=None, row_offset=0):
    """
    把原始行转换为 SQL 参数值。warn 为 {列序号: [失败次数, 首个失败示例]}，
    示例中的行号为相对于数据的 1-based 行号（row_offset 为本批起始偏移）。
    """
    if warn is None:
        warn = {}
    out = []
    for r_off, r in enumerate(rows):
        vals = []
        for j, v in enumerate(r):
            val, err = _convert(v, coltypes[j])
            if err is not None:
                info = warn.setdefault(j, [0, None])
                info[0] += 1
                if info[1] is None:
                    info[1] = f"第 {row_offset + r_off + 1} 行 {v!r}"
            vals.append(val)
        out.append(vals)
    return out


def connect(cfg: dict, connect_timeout=None):
    """根据连接配置建立 pymysql 连接。cfg 含 host/port/user/password/database。"""
    extra = {}
    if connect_timeout is not None:
        # 图形界面启动时自动连接不能无限等：服务端不在线时用户还得能正常关窗口
        extra["connect_timeout"] = connect_timeout
    return pymysql.connect(
        host=cfg["host"], port=int(cfg.get("port", 3306)),
        user=cfg["user"], password=cfg.get("password", ""),
        database=cfg.get("database") or None,
        charset="utf8mb4", autocommit=False, **extra,
    )


def list_databases(conn) -> list:
    with conn.cursor() as cur:
        cur.execute("SHOW DATABASES")
        skip = {"information_schema", "performance_schema", "mysql", "sys"}
        return sorted([r[0] for r in cur.fetchall() if r[0] not in skip])


def create_table(conn, ddl: str):
    with conn.cursor() as cur:
        for stmt in [s for s in ddl.split(";\n") if s.strip()]:
            cur.execute(stmt)
    conn.commit()


def insert_data(conn, table_name: str, columns: list, df,
                batch=500, progress_cb=None, warn_cb=None) -> int:
    """
    分批插入，返回成功行数。
    数值列中无法转换的值按 NULL 写入，并通过 warn_cb(message) 汇总告警，
    避免脏数据静默丢失无感知。
    """
    coltypes = [c["type"] for c in columns]
    colnames = [c["field"] for c in columns]
    insert_sql = build_insert_sql(table_name, columns)
    rows = df.values.tolist()
    total = len(rows)
    ok = 0
    warn = {}        # 列序号 -> [失败次数, 首个失败示例]
    with conn.cursor() as cur:
        for i in range(0, total, batch):
            chunk = rows[i:i + batch]
            vals = _convert_rows(chunk, coltypes, warn, row_offset=i)
            cur.executemany(insert_sql, vals)
            ok += cur.rowcount if cur.rowcount and cur.rowcount > 0 else len(chunk)
            if progress_cb:
                progress_cb(min(i + batch, total), total)
    conn.commit()
    if warn and warn_cb:
        for j in sorted(warn):
            cnt, example = warn[j]
            warn_cb(f"[数据警告] `{table_name}`.`{colnames[j]}`（{coltypes[j]}）"
                    f"共 {cnt} 个值无法转换，示例：{example}；已按 NULL 写入，"
                    f"如需保留原值请把该列类型改为文本后重新导入")
    return ok
