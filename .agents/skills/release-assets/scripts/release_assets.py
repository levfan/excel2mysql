"""Build three distributions and sync their zips to the GitHub Release assets.

Run from anywhere; paths are resolved from this script location. The GitHub
token is read from GITHUB_TOKEN / GH_TOKEN or git credential fill and never
printed. Requires tracked files to be clean unless --allow-dirty is given.
"""

import argparse
import datetime
import hashlib
import http.client
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve()
ROOT = HERE.parents[4]
OWNER = "levfan"
REPO = "excel2mysql"
API = "/repos/%s/%s" % (OWNER, REPO)
ASSETS = (
    ("dist/excel2mysql", "excel2mysql-{v}-gui-win64.zip"),
    ("dist/excel2mysql-cli", "excel2mysql-{v}-cli-win7.zip"),
    ("dist/excel2mysql-java-{v}-win64", "excel2mysql-java-{v}-win64.zip"),
)
BATS = (
    "一键打包-图形版.bat",
    "一键打包-命令行版.bat",
    "一键打包-Java版.bat",
)


def log(msg):
    print(msg, flush=True)


def version():
    text = (ROOT / "version.py").read_text(encoding="utf-8")
    m = re.search(r'^APP_VERSION\s*=\s*[\'"]([^\'"]+)', text, re.M)
    if not m:
        raise RuntimeError("version.py 里找不到 APP_VERSION")
    return m.group(1)


def git(*args):
    r = subprocess.run(["git"] + list(args), cwd=str(ROOT), text=True,
                       encoding="utf-8", errors="replace", capture_output=True)
    if r.returncode:
        raise RuntimeError("git %s 失败：%s" % (" ".join(args), r.stderr.strip()[:300]))
    return r.stdout.strip()


def token():
    for key in ("GITHUB_TOKEN", "GH_TOKEN"):
        if os.environ.get(key):
            return os.environ[key]
    r = subprocess.run(["git", "credential", "fill"], cwd=str(ROOT),
                       input="protocol=https\nhost=github.com\n\n",
                       text=True, encoding="utf-8", errors="replace", capture_output=True)
    got = dict(l.strip().split("=", 1) for l in r.stdout.splitlines() if "=" in l).get("password", "")
    if not got:
        raise RuntimeError("找不到 GitHub 令牌：设 GITHUB_TOKEN 或配置 git credential")
    return got


TOKEN = None
HEADERS = None


def init_auth():
    global TOKEN, HEADERS
    TOKEN = token()
    HEADERS = {
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "excel2mysql-release-assets",
    }


def api(method, path, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = dict(HEADERS)
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request("https://api.github.com" + path, data=data,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw[:300]


def api_retry(method, path, payload=None, tries=4, label=""):
    last = None
    for attempt in range(1, tries + 1):
        try:
            st, data = api(method, path, payload)
        except Exception as e:
            st, data = 0, "%s %s" % (type(e).__name__, str(e)[:160])
        if 200 <= st < 300:
            return st, data
        last = (st, data)
        log("RETRY %s HTTP %s %s" % (label or method, st, str(data)[:160]))
        if attempt < tries:
            time.sleep(2 * attempt)
    return last


def stage_old_dirs(tag):
    backup = ROOT / ".workbuddy" / ("dist-backup-" + tag + "-" +
                                    datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    names = [d for d, _ in ASSETS] + ["dist_packtest", "dist_packtest38", "build"]
    moved = []
    for name in names:
        src = ROOT / name
        if src.exists():
            dst = backup / name.replace("/", "__")
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))
            moved.append(name)
    if moved:
        log("STAGE old dirs -> %s | %s" % (backup.relative_to(ROOT), ", ".join(moved)))


def run_bat(name):
    env = dict(os.environ)
    env.update({"PYTHONIOENCODING": "utf-8", "PYTHONUTF8": "1"})
    p = subprocess.Popen([str(ROOT / name)], cwd=str(ROOT), env=env,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, encoding="utf-8", errors="replace", bufsize=1)
    for line in p.stdout:
        log("  | " + line.rstrip())
    rc = p.wait()
    if rc:
        raise RuntimeError("%s 退出码 %d，发布中止" % (name, rc))
    log("BUILD OK %s" % name)


def zip_one(src, dst):
    src, dst = ROOT / src, ROOT / dst
    if not src.is_dir():
        raise RuntimeError("缺产物目录 %s" % src.relative_to(ROOT))
    n = 0
    t0 = time.time()
    with zipfile.ZipFile(str(dst), "w", zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as zf:
        for base, dirs, files in os.walk(str(src)):
            dirs.sort()
            for f in sorted(files):
                full = Path(base) / f
                arc = full.relative_to(src.parent).as_posix()
                zf.write(str(full), arc)
                n += 1
    with zipfile.ZipFile(str(dst)) as zf:
        bad = zf.testzip()
    if bad:
        raise RuntimeError("zip 校验失败：%s 里的 %s" % (dst.name, bad))
    h = hashlib.sha256()
    with open(str(dst), "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    log("ZIP %s | %d files | %.1f MB | sha256=%s | %.1fs" %
        (dst.name, n, dst.stat().st_size / 1048576, h.hexdigest(), time.time() - t0))
    return h.hexdigest()


def make_body(ver, tag, hashes, commit):
    lines = [
        "## Excel ⇄ MySQL %s" % ver,
        "",
        "三端同版本构建，各自全量闸门通过；资产为可直接解压使用的 zip。",
        "",
        "| 资产 | 适用系统 | 说明 |",
        "| --- | --- | --- |",
        "| `%s` | 64 位 Windows 10/11 | 图形版 |" % ASSETS[0][1].format(v=ver),
        "| `%s` | Windows 7 SP1+ | 命令行版，带前置补丁 |" % ASSETS[1][1].format(v=ver),
        "| `%s` | 64 位 Windows 7+ | Java 图形 + 命令行，随包 JRE |" % ASSETS[2][1].format(v=ver),
        "",
        "构建提交：`%s`。发布由 `release-assets` 技能同步。" % commit,
        "",
        "### SHA-256",
        "",
    ]
    lines += ["- `%s`: `%s`" % (n, h) for n, h in hashes.items()]
    return "\n".join(lines) + "\n"


def ensure_release(ver, body, prerelease):
    tag = "v" + ver
    st, rel = api("GET", API + "/releases/tags/" + urllib.parse.quote(tag))
    if st == 200:
        log("RELEASE found %s | id=%s" % (tag, rel["id"]))
        st, _ = api_retry("PATCH", API + "/releases/%s" % rel["id"],
                          {"body": body, "prerelease": prerelease}, label="PATCH body")
        if st < 200 or st >= 300:
            raise RuntimeError("更新发布说明失败：HTTP %s" % st)
        log("PATCH body HTTP %s" % st)
        return rel
    if st != 404:
        raise RuntimeError("查询 Release 失败：HTTP %s %s" % (st, str(rel)[:200]))
    # 完整 body 的首次 POST 偶发 500；先用最小 body 建，再 PATCH 完整说明。
    st, rel = api_retry("POST", API + "/releases", {
        "tag_name": tag, "name": tag, "body": "Excel ⇄ MySQL " + ver,
        "draft": False, "prerelease": prerelease, "generate_release_notes": False}, label="POST release")
    if st < 200 or st >= 300:
        raise RuntimeError("创建 Release 失败：HTTP %s %s" % (st, str(rel)[:200]))
    log("RELEASE created %s | id=%s | %s" % (tag, rel["id"], rel.get("html_url", "")))
    st, _ = api_retry("PATCH", API + "/releases/%s" % rel["id"], {"body": body}, label="PATCH body")
    if st < 200 or st >= 300:
        raise RuntimeError("写入发布说明失败：HTTP %s" % st)
    return rel


def asset_urls(rel):
    return {a["name"]: a for a in rel.get("assets", [])}


def body_hashes(rel):
    found = dict(re.findall(r"- `([^`]+\.zip)`: `([0-9a-f]{64})`", rel.get("body", "")))
    return found


def upload(path, name, rel_id):
    size = path.stat().st_size
    url = "%s/releases/%s/assets?name=%s" % (API, rel_id, urllib.parse.quote(name))
    host = "uploads.github.com"
    for attempt in range(1, 4):
        try:
            conn = http.client.HTTPSConnection(host, timeout=900)
            conn.putrequest("POST", url)
            for k, v in HEADERS.items():
                conn.putheader(k, v)
            conn.putheader("Content-Type", "application/zip")
            conn.putheader("Content-Length", str(size))
            conn.endheaders()
            sent, mark, t0 = 0, 50 * 1024 * 1024, time.time()
            with open(str(path), "rb") as f:
                while True:
                    chunk = f.read(1 << 20)
                    if not chunk:
                        break
                    conn.send(chunk)
                    sent += len(chunk)
                    if sent >= mark:
                        log("SEND %s %.0f%% | %.0fs" % (name, sent * 100.0 / size, time.time() - t0))
                        mark += 50 * 1024 * 1024
            r = conn.getresponse()
            raw = r.read()
            conn.close()
            if r.status in (200, 201):
                a = json.loads(raw.decode("utf-8"))
                log("UPLOADED %s | %s" % (name, a.get("browser_download_url", "")))
                return
            log("RETRY %s HTTP %s %s" % (name, r.status, raw[:160].decode("utf-8", "replace")))
        except Exception as e:
            log("RETRY %s %s %s" % (name, type(e).__name__, str(e)[:160]))
        if attempt < 3:
            time.sleep(3 * attempt)
    raise RuntimeError("上传失败：" + name)


def sync(ver, hashes, zips):
    init_auth()
    prerelease = bool(re.search(r"-(alpha|beta|rc)\.", ver))
    commit = git("rev-parse", "--short", "HEAD")
    rel = ensure_release(ver, make_body(ver, "v" + ver, hashes, commit), prerelease)
    have, recorded = asset_urls(rel), body_hashes(rel)
    for name, path in zips.items():
        if (name in have and have[name]["size"] == path.stat().st_size
                and recorded.get(name) == hashes[name]):
            log("SKIP %s | 资产与 SHA-256 已同步" % name)
            continue
        if name in have:
            st, _ = api("DELETE", API + "/releases/assets/%s" % have[name]["id"])
            if st != 204:
                raise RuntimeError("删除旧资产失败：%s HTTP %s" % (name, st))
            log("REPLACE %s" % name)
        upload(path, name, rel["id"])
    st, final = api("GET", API + "/releases/tags/v" + urllib.parse.quote(ver))
    if st != 200:
        raise RuntimeError("复核 Release 失败：HTTP %s" % st)
    ok = set(zips) <= set(asset_urls(final))
    log("FINAL %s | %s" % ("OK" if ok else "MISSING ASSET", final.get("html_url", "")))
    for a in final.get("assets", []):
        log("ASSET %s | %d" % (a["name"], a["size"]))
    if not ok:
        raise RuntimeError("资产清单不齐")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-build", action="store_true", help="只压缩并发布现有 dist")
    ap.add_argument("--allow-dirty", action="store_true", help="允许工作区有已跟踪改动")
    ap.add_argument("--no-upload", action="store_true", help="只构建与压缩，不碰 GitHub")
    args = ap.parse_args()
    ver = version()
    if not args.allow_dirty and git("status", "--porcelain", "--untracked-files=no"):
        raise RuntimeError("工作区有已跟踪改动：先提交再发布")
    log("VERSION %s" % ver)
    if not args.skip_build:
        stage_old_dirs("v" + ver)
        for b in BATS:
            run_bat(b)
    zips, hashes = {}, {}
    for src, tmpl in ASSETS:
        name = tmpl.format(v=ver)
        zips[name] = ROOT / "dist" / name
        hashes[name] = zip_one(src.format(v=ver), "dist/" + name)
    if args.no_upload:
        log("NO-UPLOAD | 本地包与 SHA-256 已就绪")
        return
    sync(ver, hashes, zips)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log("FAILED " + str(exc))
        sys.exit(1)
