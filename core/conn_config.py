# -*- coding: utf-8 -*-
"""连接配置的一份实现：命令行与两个图形界面都从这里读同一份 conn.ini。

优先级与命令行那条线一致：调用方自己的显式输入（命令行参数 / 界面上手填）
> 环境变量 E2M_HOST/E2M_PORT/E2M_USER/E2M_PASS/E2M_DB > 自动发现的 conn.ini
> 内置默认值。

为什么要单独一个模块：现场使用者只要在程序同级放一份 conn.ini 写上默认库，
开界面就不用再敲一遍主机端口用户名；这条规则命令行早就有了，
再在图形界面里抄一份，两边对"写坏了怎么办""段名叫什么"的口径迟早分叉。
"""
import configparser
import os

# 配置文件里允许的段名，按顺序取第一个存在的
CONFIG_SECTIONS = ("mysql", "connection")
CONFIG_KEYS = {"host": "host", "port": "port", "user": "user",
               "password": "password", "database": "database"}
ENV_KEYS = {"host": "E2M_HOST", "port": "E2M_PORT", "user": "E2M_USER",
            "password": "E2M_PASS", "database": "E2M_DB"}
DEFAULTS = {"host": "127.0.0.1", "port": "3306", "user": "root",
            "password": "", "database": ""}


def read_config_file(path):
    """读 INI 连接配置，返回 dict；文件缺失/格式错直接抛给调用方。"""
    if not os.path.isfile(path):
        raise ValueError("配置文件不存在：%s" % path)
    cp = configparser.ConfigParser()
    try:
        with open(path, encoding="utf-8") as fh:
            cp.read_file(fh)
    except UnicodeDecodeError:
        # Win7 上记事本另存为默认 ANSI，中文注释会让 utf-8 解析炸掉，退回 GBK 再试
        cp = configparser.ConfigParser()
        with open(path, encoding="gbk", errors="replace") as fh:
            cp.read_file(fh)
    except configparser.Error as e:
        raise ValueError("配置文件格式错误（%s）：%s" % (path, e))
    section = next((s for s in CONFIG_SECTIONS if cp.has_section(s)), None)
    if section is None:
        raise ValueError("配置文件缺少 [%s] 段：%s" % ("|".join(CONFIG_SECTIONS), path))
    return {CONFIG_KEYS[k]: cp.get(section, k) for k in CONFIG_KEYS
            if cp.has_option(section, k)}


def default_config_paths(base_dir):
    """自动尝试读取的位置：程序所在目录优先，其次当前工作目录。"""
    return [os.path.join(base_dir, "conn.ini"),
            os.path.join(os.getcwd(), "conn.ini")]


def discover(base_dir):
    """找到第一份存在的 conn.ini 并读它，返回 (配置, 来源说明, 路径或 None, 读不了的报错或 None)。

    自动发现的文件写坏了不抛：丢掉它、退回内置默认值继续，但把报错带回来让调用方
    说出去——静默兜底会让人以为自己的配置生效了。路径与报错分开返回，
    调用方才分得清"压根没有这份文件"和"有这份文件但读不了"。
    """
    cfg = dict(DEFAULTS)
    path = next((p for p in default_config_paths(base_dir) if os.path.isfile(p)), None)
    if path is None:
        return cfg, "内置默认值（没找到 conn.ini）", None, None
    try:
        cfg.update(read_config_file(path))
    except ValueError as e:
        return cfg, "内置默认值（%s 读不了）" % path, path, str(e)
    return cfg, "配置文件 %s（自动发现）" % path, path, None


def apply_env(cfg):
    """环境变量盖过配置文件；改了哪几项一并报出来，让调用方能说清来源。"""
    from_env = []
    for key, env in ENV_KEYS.items():
        val = os.environ.get(env)
        if val:
            cfg[key] = val
            from_env.append(env)
    return cfg, from_env
