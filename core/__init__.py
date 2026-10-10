"""core 包：Excel 解析/类型推断 + MySQL DDL/导入。"""
from .infer import build_table_def, make_unique_names, sanitize_table_name
from .mysql_io import (
    build_ddl, connect, list_databases, create_table, insert_data,
)
