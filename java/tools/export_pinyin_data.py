# -*- coding: utf-8 -*-
"""导出 pypinyin 词典与金标准语料，供 Java 侧做等价差分。

期望值全部取自 .venv38 里真实安装的 pypinyin，不由本脚本重新实现规则。
replica_* 是对库内「分词 + 选音」主干的 Python 复刻，用途只有一个：
先和库对撞证明规格读准了，Java 侧才谈得上机械翻译。复刻没过就不要写 Java。
"""
from __future__ import unicode_literals

import gzip
import hashlib
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # <repo>/java/tools -> <repo>
OUT_RES = os.path.join(ROOT, "java", "app", "src", "main", "resources", "pinyin")
OUT_VERIFY = os.path.join(ROOT, "java", "verify", "golden")

import pypinyin
from pypinyin.constants import PINYIN_DICT, PHRASES_DICT, RE_HANS, Style
from pypinyin.seg.mmseg import seg as LIB_SEG
from pypinyin.seg.simpleseg import seg as LIB_SEG_FUNC
from pypinyin.style import convert as style_convert


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def bump(d, key):
    if d is not None:
        d[key] = d.get(key, 0) + 1


def is_han(text):
    return bool(RE_HANS.match(text))


def cut_replica(text, branches):
    """复刻 seg/mmseg.py 的严格模式正向最大匹配，并记录每词走的分支。

    分支编号对着源码的五个出口 A/B/B2/C/D/E；B2、E 在当前配置下可达与否，
    由统计结果说，不由我读代码猜。
    """
    strict = LIB_SEG._no_non_phrases
    remain = text
    while remain:
        matched = ""
        last_valid_word = ""
        last_valid_index = 0
        for index in range(len(remain)):
            word = remain[:index + 1]
            if word in LIB_SEG._prefix_set:
                matched = word
                if (not strict) or word in PHRASES_DICT:
                    last_valid_word = word
                    last_valid_index = index + 1
            else:
                if last_valid_word:
                    bump(branches, "A 扩张失败/有有效词")
                    yield last_valid_word
                    remain = remain[last_valid_index:]
                elif strict:
                    bump(branches, "B 扩张失败/无有效词/严格模式吐首字")
                    yield remain[0]
                    remain = remain[1:]
                else:
                    bump(branches, "B2 扩张失败/非严格模式吐已匹配前缀")
                    if matched:
                        yield matched
                        remain = remain[len(matched):]
                    else:
                        yield remain[0]
                        remain = remain[1:]
                break
        else:
            if last_valid_word:
                bump(branches, "C 整段合法前缀/有有效词")
                yield last_valid_word
                remain = remain[last_valid_index:]
            elif strict and remain not in PHRASES_DICT:
                bump(branches, "D 整段合法前缀/逐字")
                for x in remain:
                    yield x
                return
            else:
                bump(branches, "E 整段合法前缀/整段吐出")
                yield remain
                return


def replica_seg(text, branches=None):
    """复刻 seg/simpleseg.py 的 seg()：先按是否汉字切段，汉字段再进最大匹配。"""
    runs = []
    s = ""
    flag = None
    for ch in text:
        han = is_han(ch)
        if flag is None:
            flag = han
            s = ch
        elif han == flag:
            s += ch
        else:
            runs.append(s)
            flag = han
            s = ch
    if s:
        runs.append(s)

    ret = []
    for run in runs:
        if not is_han(run):
            ret.append(run)
        elif PHRASES_DICT:
            ret.extend(cut_replica(run, branches))
        else:
            ret.append(run)
    return ret


def normalize(han, raw):
    return style_convert(raw, Style.NORMAL, strict=True, default=raw, han=han)


def replica_lazy(text, branches=None):
    """复刻 lazy_pinyin：整词命中词库取词库读音，否则逐字取首读音；非汉字段整段原样。"""
    out = []
    for token in replica_seg(text, branches):
        if not is_han(token):
            out.append(token)                     # handle_nopinyin 走 return [[py]]
            continue
        if token in PHRASES_DICT:
            for i, cands in enumerate(PHRASES_DICT[token]):
                han = token[i] if i < len(token) else ""
                out.append(normalize(han, cands[0]) if cands else "")
        else:
            for han in token:
                raw = PINYIN_DICT.get(ord(han))
                out.append(han if raw is None else normalize(han, raw.split(",")[0]))
    return out


def pipeline_domain_gap():
    """我们喂给 lazy_pinyin 的只有 [\\u4e00-\\u9fff]，取其中没有拼音条目的一段。

    这些字走 handle_nopinyin：原样返回字符，再被 infer.py 的 [^a-z0-9] 清洗掉。
    不专门造样本，Java 侧这条支路就只能靠运气覆盖。
    """
    return [c for c in range(0x4e00, 0xa000) if c not in PINYIN_DICT]


def corpus_cases():
    """(输入, 备注)：全量单字、全量词组、对抗样本、混排串。"""
    cases = []
    chars = sorted(PINYIN_DICT.keys())
    for i in range(0, len(chars), 20):
        cases.append(("".join(chr(c) for c in chars[i:i + 20]), "single-batch"))
    for phrase in sorted(PHRASES_DICT.keys()):
        cases.append((phrase, "phrase"))

    for phrase in sorted(PHRASES_DICT.keys())[:4000]:
        cases.append((phrase + "区", "adversarial-tail"))
        cases.append(("x" + phrase, "adversarial-head"))

    cases.append(("".join(chr(c) for c in chars[:200]), "long-chain"))
    gap = pipeline_domain_gap()
    print("- [INFO] 管道区间内(\\u4e00-\\u9fff)无拼音条目的汉字 = %d 个" % len(gap))
    for i in range(0, min(len(gap), 400), 20):
        cases.append(("".join(chr(c) for c in gap[i:i + 20]), "hanzi-no-pinyin"))
    for text in ["水库基础信息", "1.0mm管道", "2024年度", "A类水库_b",
                 "频率é", "假名アイウ", "顺序2次", "测_试-数据",
                 "重庆长寿湖", "行人行走", "银行行长", "长城长假", "重复",
                 "区", "不详字"]:
        cases.append((text, "project-sample"))
    return cases


def _sz(p):
    return "%.2f MB" % (os.path.getsize(p) / 1048576.0)


def write_jpinyin_dicts(out_dir):
    """把同一份 pypinyin 词典改写成 jpinyin 的格式，供 addPinyinDict/addMutilPinyinDict 注入。

    输出目录是 verify/golden，**刻意不放 app/src/main/resources**：
    jpinyin 路线已被 JpinyinDiffer 实测否掉（见 java/README.md），这两个文件只是
    留给"以后再有人提换库"时一键复测的证据；放进资源目录会被打进 jar，白胖 1.6 MB。

    jpinyin 的格式（实测自 1.1.8 自带 data/*.dict 与 PinyinResource.getResource）：
      单字  字=带调读音,带调读音...
      词组  词组=每字一个带调读音，逗号分隔
    它内部一律先按逗号切成数组，再按 PinyinFormat 去声调，取 [0] 就是首选读音，
    和 lazy_pinyin(heteronym=False) 的 item[0] 对得上。

    这里刻意不做任何"顺手修正"：读音数量与字数不等、空读音、重复读音都原样写出来，
    由差分去暴露 jpinyin 的行为，而不是在导出时就掩盖掉。
    """
    stats = {"single": 0, "phrase": 0, "phrase_len_mismatch": 0, "empty_reading": 0}
    single_path = os.path.join(out_dir, "jpinyin_single.dict")
    with io.open(single_path, "w", encoding="utf-8", newline="\n") as f:
        for code in sorted(PINYIN_DICT.keys()):
            raw = PINYIN_DICT[code]
            f.write(u"%s=%s\n" % (chr(code), raw))
            stats["single"] += 1

    phrase_path = os.path.join(out_dir, "jpinyin_phrase.dict")
    with io.open(phrase_path, "w", encoding="utf-8", newline="\n") as f:
        for phrase in sorted(PHRASES_DICT.keys()):
            readings = []
            for cands in PHRASES_DICT[phrase]:
                if not cands or not cands[0]:
                    stats["empty_reading"] += 1
                    readings.append(u"")
                else:
                    readings.append(cands[0])
            if len(readings) != len(phrase):
                stats["phrase_len_mismatch"] += 1
            f.write(u"%s=%s\n" % (phrase, u",".join(readings)))
            stats["phrase"] += 1
    return single_path, phrase_path, stats


def main():
    if sys.version_info[:2] != (3, 8):
        print("! [WARN] 建议用 .venv38 跑，基准要和打包用的 pypinyin 一致 -> %s"
              % sys.version.split()[0])
    print("- [INFO] RE_HANS = %s | 分词严格模式 = %s" % (
        RE_HANS.pattern, LIB_SEG._no_non_phrases))

    for d in (OUT_RES, OUT_VERIFY):
        if not os.path.isdir(d):
            os.makedirs(d)

    single_path = os.path.join(OUT_RES, "single.txt")
    with io.open(single_path, "w", encoding="utf-8", newline="\n") as f:
        for code in sorted(PINYIN_DICT.keys()):
            f.write("%x\t%s\n" % (code, PINYIN_DICT[code]))

    phrase_path = os.path.join(OUT_RES, "phrase.txt")
    with io.open(phrase_path, "w", encoding="utf-8", newline="\n") as f:
        for phrase in sorted(PHRASES_DICT.keys()):
            positions = " ".join("+".join(p) for p in PHRASES_DICT[phrase])
            f.write("%s\t%s\n" % (phrase, positions))

    jp_single_path, jp_phrase_path, jp_stats = write_jpinyin_dicts(OUT_VERIFY)

    golden_path = os.path.join(OUT_VERIFY, "cases.jsonl.gz")
    branches = {}
    cases = corpus_cases()
    bad_seg = bad_lazy = 0
    first_bad = []
    with gzip.open(golden_path, "wb") as gz:
        for text, tag in cases:
            expected_lazy = pypinyin.lazy_pinyin(text)
            expected_seg = list(LIB_SEG_FUNC(text))
            got_lazy = replica_lazy(text, branches)
            got_seg = replica_seg(text)
            if got_lazy != expected_lazy:
                bad_lazy += 1
                if len(first_bad) < 4:
                    first_bad.append(("lazy", tag, text, expected_lazy, got_lazy))
            if got_seg != expected_seg:
                bad_seg += 1
                if len(first_bad) < 8:
                    first_bad.append(("seg", tag, text, expected_seg, got_seg))
            blob = json.dumps({"in": text, "tag": tag, "exp": expected_lazy},
                              ensure_ascii=False, separators=(",", ":"))
            gz.write(blob.encode("utf-8") + b"\n")

    manifest = {
        "pypinyin_version": pypinyin.__version__,
        "re_hans_pattern": RE_HANS.pattern,
        "seg_no_non_phrases": LIB_SEG._no_non_phrases,
        "single_entries": len(PINYIN_DICT),
        "phrase_entries": len(PHRASES_DICT),
        "single_sha256": sha256(single_path),
        "phrase_sha256": sha256(phrase_path),
        "jpinyin_single_entries": jp_stats["single"],
        "jpinyin_phrase_entries": jp_stats["phrase"],
        "jpinyin_phrase_len_mismatch": jp_stats["phrase_len_mismatch"],
        "jpinyin_empty_reading": jp_stats["empty_reading"],
        "jpinyin_single_sha256": sha256(jp_single_path),
        "jpinyin_phrase_sha256": sha256(jp_phrase_path),
        "golden_cases": len(cases),
        "golden_sha256": sha256(golden_path),
    }
    with io.open(os.path.join(OUT_VERIFY, "manifest.json"), "w",
                 encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

    print("+ [OK] 词典导出 | single %d 条 %s | phrase %d 条 %s" % (
        len(PINYIN_DICT), _sz(single_path), len(PHRASES_DICT), _sz(phrase_path)))
    print("+ [OK] jpinyin 格式词典 | 单字 %d 条 %s | 词组 %d 条 %s"
          "（音节数与字数不等 %d，空读音 %d）" % (
              jp_stats["single"], _sz(jp_single_path),
              jp_stats["phrase"], _sz(jp_phrase_path),
              jp_stats["phrase_len_mismatch"], jp_stats["empty_reading"]))
    print("+ [OK] 金标准语料 | %d 条 %s" % (len(cases), _sz(golden_path)))
    print("- [INFO] 分支命中统计:")
    for k in sorted(branches):
        print("     %-40s %d" % (k, branches[k]))
    dead = [k for k in ("B2 扩张失败/非严格模式吐已匹配前缀", "E 整段合法前缀/整段吐出")
            if k not in branches]
    if dead:
        print("  ! [WARN] 语料未命中的分支 = %s" % "、".join(dead))
    print("- [INFO] 复刻 vs 库 | 分词不一致 %d / %d 条 | 整链不一致 %d 处" % (
        bad_seg, len(cases), bad_lazy))
    for kind, tag, text, exp, got in first_bad:
        print("     差异[%s/%s] in=%r\n       库   =%r\n       复刻 =%r" % (
            kind, tag, text, exp, got))
    if bad_seg or bad_lazy:
        print("x [ERROR] 复刻与库不等价 | 规格还没读准，先别写 Java")
        return 1
    print("+ [OK] 自检通过 | 分词与选音规则已被 Python 复刻证明，Java 侧可机械翻译")
    return 0


if __name__ == "__main__":
    sys.exit(main())
