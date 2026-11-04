#!/usr/bin/env python3
"""
校验并生成 QuietMap 锚点文件。

anchors.json 是锚点的单一事实来源；本工具：
  1) 读取 app/src/main/resources/anchors.json（保留 key 顺序）；
  2) 用 dexsql 对目标 APK 校验每个 c/m/s（以及 candidates / dexkit 字符串）；
  3) 校验 APK 的 versionName/versionCode 与表内 appVersion/versionCode 一致；
  4) 全部命中后原子写回 anchors.json（generatedBy = tools/gen_anchors.py），
     并按同一份 key 列表重新生成 AnchorKeys.java。

用法:
    python tools/gen_anchors.py --apk /path/to/base.apk \
        --dexsql /path/to/dexsql
    python tools/gen_anchors.py --apk ... --dexsql ... --check   # 只校验不写

路径默认按仓库布局推导：
    --anchors  app/src/main/resources/anchors.json
    --keys-out app/src/main/java/com/quietmap/app/anchor/AnchorKeys.java

dexsql 解析顺序: --dexsql > DEXSQL 环境变量 > PATH > 仓库 tools/dexsql/ > 常见路径。
APK 版本默认用 aapt2/aapt/apkanalyzer 读取；工具不可用时必须显式传
--app-version / --version-code，否则报错退出。

任一项校验失败：打印 MISSING，非 0 退出，且不修改任何文件。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

DEFAULT_ANCHORS = REPO_ROOT / "app" / "src" / "main" / "resources" / "anchors.json"
DEFAULT_KEYS = (REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "quietmap"
                / "app" / "anchor" / "AnchorKeys.java")
GENERATED_BY = "tools/gen_anchors.py"
SCHEMA_VERSION = 1
FEATURE_PATTERN = re.compile(r"^(AD|UI|PV|EN|EX)-[0-9]+$")


# ---- 工具定位 -------------------------------------------------------------
def _is_executable(path):
    try:
        if not path.is_file():
            return False
    except OSError:
        return False
    if os.name == "nt" or path.suffix.lower() in (".exe", ".bat", ".cmd"):
        return True
    return os.access(str(path), os.X_OK)


def _check_explicit(label, value):
    if not value:
        return None
    path = Path(value).expanduser()
    if _is_executable(path):
        return path
    which = shutil.which(value)
    if which:
        return Path(which)
    raise SystemExit("ERROR: %s is not executable: %s" % (label, value))


def _version_key(path):
    parts = re.split(r"[._-]", path.name)
    return [int(part) if part.isdigit() else -1 for part in parts]


def sdk_roots():
    roots = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value:
            roots.append(Path(value).expanduser())

    local_props = REPO_ROOT / "local.properties"
    if local_props.is_file():
        try:
            text = local_props.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        for line in text.splitlines():
            match = re.match(r"\s*sdk\.dir\s*=\s*(.+?)\s*$", line)
            if match:
                value = match.group(1).replace("\\\\", "\\").replace("\\:", ":")
                roots.append(Path(value).expanduser())

    home = Path.home()
    roots.extend([
        home / "Android" / "Sdk",
        home / "Library" / "Android" / "sdk",
        home / "AppData" / "Local" / "Android" / "Sdk",
        Path("/usr/lib/android-sdk"),
        Path("/opt/android-sdk"),
    ])

    unique = []
    seen = set()
    for root in roots:
        key = str(root)
        if key not in seen:
            seen.add(key)
            unique.append(root)
    return unique


def resolve_dexsql(explicit):
    found = _check_explicit("--dexsql", explicit)
    if found:
        return found
    found = _check_explicit("DEXSQL", os.environ.get("DEXSQL"))
    if found:
        return found

    candidates = [
        shutil.which("dexsql"),
        shutil.which("dexsql.exe"),
        REPO_ROOT / "tools" / "dexsql" / "dexsql.exe",
        REPO_ROOT / "tools" / "dexsql" / "dexsql",
        REPO_ROOT / "tools" / "dexsql.exe",
        Path.home() / "tools" / "dexsql" / "dexsql.exe",
        Path.home() / "tools" / "dexsql" / "dexsql",
        Path.home() / ".local" / "bin" / "dexsql",
        Path("/usr/local/bin/dexsql"),
        Path("/opt/dexsql/dexsql"),
    ]
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate).expanduser()
        if _is_executable(path):
            return path
    raise SystemExit(
        "ERROR: dexsql not found. Pass --dexsql /path/to/dexsql, set DEXSQL, "
        "put it on PATH, or install it under tools/dexsql/."
    )


def resolve_aapt(explicit):
    found = _check_explicit("--aapt2", explicit)
    if found:
        return found
    found = _check_explicit("AAPT2", os.environ.get("AAPT2"))
    if found:
        return found

    which = shutil.which("aapt2") or shutil.which("aapt")
    if which:
        return Path(which)

    for root in sdk_roots():
        build_tools = root / "build-tools"
        if not build_tools.is_dir():
            continue
        try:
            versions = sorted(
                [p for p in build_tools.iterdir() if p.is_dir()],
                key=_version_key, reverse=True,
            )
        except OSError:
            continue
        for version in versions:
            for name in ("aapt2.exe", "aapt2", "aapt.exe", "aapt"):
                candidate = version / name
                if _is_executable(candidate):
                    return candidate
    return None


def resolve_apkanalyzer(explicit):
    found = _check_explicit("--apkanalyzer", explicit)
    if found:
        return found
    found = _check_explicit("APKANALYZER", os.environ.get("APKANALYZER"))
    if found:
        return found

    which = shutil.which("apkanalyzer") or shutil.which("apkanalyzer.bat")
    if which:
        return Path(which)

    for root in sdk_roots():
        cmdline_tools = root / "cmdline-tools"
        if cmdline_tools.is_dir():
            try:
                versions = sorted(
                    [p for p in cmdline_tools.iterdir() if p.is_dir()],
                    key=_version_key, reverse=True,
                )
            except OSError:
                versions = []
            for version in versions:
                for name in ("apkanalyzer", "apkanalyzer.bat"):
                    candidate = version / "bin" / name
                    if _is_executable(candidate):
                        return candidate
        for name in ("apkanalyzer", "apkanalyzer.bat"):
            candidate = root / "tools" / "bin" / name
            if _is_executable(candidate):
                return candidate
    return None


# ---- APK 版本 -------------------------------------------------------------
def _run(cmd):
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        )
    except OSError:
        return None
    if result.returncode != 0:
        return None
    return result.stdout


def _last_line(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return lines[-1] if lines else None


def _apkanalyzer_value(tool, prop, apk):
    commands = [
        [str(tool), "manifest", prop, str(apk)],
        [str(tool), "--file", str(apk), "manifest", prop],
    ]
    for cmd in commands:
        output = _run(cmd)
        value = _last_line(output) if output else None
        if value:
            return value
    return None


def read_apk_version(apk, aapt, apkanalyzer):
    name = None
    code = None
    if aapt:
        output = _run([str(aapt), "dump", "badging", str(apk)])
        if output:
            match = re.search(r"versionName='([^']*)'", output)
            if match:
                name = match.group(1)
            match = re.search(r"versionCode='([0-9]+)'", output)
            if match:
                code = match.group(1)
    if apkanalyzer and name is None:
        name = _apkanalyzer_value(apkanalyzer, "version-name", apk)
    if apkanalyzer and code is None:
        code = _apkanalyzer_value(apkanalyzer, "version-code", apk)
    return name, code


# ---- DexSQL ---------------------------------------------------------------
class Dexsql:
    """dexsql 查询封装：默认按 APK 内容哈希复用 prepare-cache 的缓存。"""

    def __init__(self, tool, apk, explicit_cache=None):
        self.tool = tool
        self.apk = apk
        self.sha256 = self._sha256(apk)
        if explicit_cache:
            self.cache_dir = Path(explicit_cache).expanduser()
        else:
            self.cache_dir = REPO_ROOT / "tools" / ".dexsql-cache" / self.sha256[:16]
        self.cache_file = self.cache_dir / "cache.dxc2"
        self._prepare()

    @staticmethod
    def _sha256(path):
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def _prepare(self):
        if self.cache_file.is_file():
            return
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(
            [str(self.tool), "prepare-cache", "--input", str(self.apk),
             "--output", str(self.cache_dir)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if result.returncode != 0 or not self.cache_file.is_file():
            detail = (result.stderr or result.stdout or "").strip()
            raise SystemExit("ERROR: dexsql prepare-cache failed: %s" % detail)

    def query(self, sql):
        result = subprocess.run(
            [str(self.tool), "query-cache", "--cache-file", str(self.cache_file),
             "--sql", sql],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "").strip()
            raise SystemExit("ERROR: dexsql query failed: %s\nSQL: %s" % (detail, sql))
        return result.stdout

    def count(self, sql):
        rows = table_rows(self.query(sql))
        return int(rows[-1][0]) if rows and rows[-1][0].isdigit() else 0

    def has_method(self, descriptor):
        return self.count(
            "SELECT count(*) AS c FROM methods WHERE descriptor='%s'"
            % sql_escape(descriptor)) > 0

    def has_field(self, descriptor):
        return self.count(
            "SELECT count(*) AS c FROM fields WHERE descriptor='%s'"
            % sql_escape(descriptor)) > 0

    def has_type(self, java_name):
        return self.count(
            "SELECT count(*) AS c FROM types WHERE java_name='%s'"
            % sql_escape(java_name)) > 0

    def method_has_string(self, method_descriptor, value):
        return self.count(
            "SELECT count(*) AS c FROM method_strings WHERE method='%s' "
            "AND value LIKE '%%%s%%'"
            % (sql_escape(method_descriptor), sql_escape(value))) > 0


def table_rows(output):
    """Parse DexSQL's ASCII table; return list of cells per data row."""
    rows = []
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= {"|", "-", "+"}:
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if cells and not cells[0].lower().startswith(("class_descriptor", "java_name", "count", "c")):
            rows.append(cells)
    return rows


def sql_escape(value):
    return value.replace("'", "''")


# ---- AnchorKeys.java ------------------------------------------------------
def java_constant_name(key):
    return re.sub(r"[^0-9A-Za-z]+", "_", key).upper()


def anchor_keys_source(anchor_keys, class_keys):
    lines = [
        "// Generated by tools/gen_anchors.py from anchors.json. DO NOT EDIT.",
        "package com.quietmap.app.anchor;",
        "",
        "/**",
        " * anchors.json 的逻辑键：编译期常量，避免 hook 代码里散落裸字符串。",
        " *",
        " * <p>由 tools/gen_anchors.py 生成；新增/删除锚点后重新生成，",
        " * AnchorContractTest 会校验常量与 anchors.json 双向一致。",
        " */",
        "public final class AnchorKeys {",
        "",
        "    private AnchorKeys() {",
        "    }",
        "",
    ]
    seen = {}
    for key in list(anchor_keys) + list(class_keys):
        constant = java_constant_name(key)
        if not constant or constant[0].isdigit():
            raise SystemExit("ERROR: 无法从锚点 key 生成合法 Java 常量名: %r" % key)
        if constant in seen and seen[constant] != key:
            raise SystemExit(
                "ERROR: 锚点 key %r 与 %r 生成同名常量 %s"
                % (seen[constant], key, constant)
            )
        if constant in seen:
            continue
        seen[constant] = key
        lines.append('    public static final String %s = "%s";' % (constant, key))
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def write_text_atomic(path, text):
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise SystemExit("ERROR: cannot create output dir %s: %s" % (path.parent, exc))

    tmp_path = None
    try:
        fd, tmp_name = tempfile.mkstemp(
            prefix=path.name + ".", suffix=".tmp", dir=str(path.parent),
        )
        tmp_path = Path(tmp_name)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            mode = path.stat().st_mode & 0o777 if path.exists() else 0o644
            os.chmod(str(tmp_path), mode)
        except OSError:
            pass
        os.replace(str(tmp_path), str(path))
        tmp_path = None
    except OSError as exc:
        raise SystemExit("ERROR: cannot write %s: %s" % (path, exc))
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink()
            except OSError:
                pass


# ---- anchors.json 校验 -----------------------------------------------------
def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise SystemExit("ERROR: anchors.json 重复键：%s" % key)
        result[key] = value
    return result


def load_anchors(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            payload = json.load(handle, object_pairs_hook=no_duplicates)
    except (OSError, ValueError) as exc:
        raise SystemExit("ERROR: 无法读取 %s: %s" % (path, exc))
    if not isinstance(payload, dict):
        raise SystemExit("ERROR: anchors.json 顶层必须是对象")
    return payload


def validate_schema(payload):
    errors = []
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        errors.append("schemaVersion 必须为 %d" % SCHEMA_VERSION)
    app_version = payload.get("appVersion")
    version_code = payload.get("versionCode")
    if not isinstance(app_version, str) or not app_version.strip():
        errors.append("appVersion 缺失或不是非空字符串")
    if isinstance(version_code, bool) or not isinstance(version_code, int) or version_code <= 0:
        errors.append("versionCode 缺失或不是正整数")
    anchors = payload.get("anchors")
    classes = payload.get("classes")
    if not isinstance(anchors, dict):
        errors.append("anchors 缺失或不是对象")
        anchors = {}
    if not isinstance(classes, dict):
        errors.append("classes 缺失或不是对象")
        classes = {}

    for key, entry in anchors.items():
        if not isinstance(key, str) or not key:
            errors.append("锚点 key 非法：%r" % (key,))
            continue
        if not isinstance(entry, dict):
            errors.append("锚点 %s 不是对象" % key)
            continue
        for field in ("c", "m", "s", "feature", "note"):
            value = entry.get(field)
            if not isinstance(value, str) or not value:
                errors.append("锚点 %s 缺少必填字段 %s" % (key, field))
        if not isinstance(entry.get("critical"), bool):
            errors.append("锚点 %s 的 critical 不是布尔值" % key)
        feature = entry.get("feature")
        if isinstance(feature, str) and feature and not FEATURE_PATTERN.match(feature):
            errors.append("锚点 %s 的 feature 不是统一功能编号：%r" % (key, feature))
        for field in ("candidates", "dexkit"):
            if field in entry:
                value = entry.get(field)
                if not isinstance(value, list) or not value or any(
                        not isinstance(item, str) or not item for item in value):
                    errors.append("锚点 %s 的 %s 必须是非空字符串数组" % (key, field))
        signature = entry.get("s")
        if isinstance(signature, str) and not signature.startswith("("):
            errors.append("锚点 %s 不是方法锚点（s 必须以 '(' 开头）：%r" % (key, signature))

    for key, cls in classes.items():
        if not isinstance(key, str) or not key:
            errors.append("类锚点 key 非法：%r" % (key,))
        if not isinstance(cls, str) or not cls:
            errors.append("类锚点 %s 的类名必须是非空字符串" % key)

    if errors:
        for message in errors:
            print("anchors.json: %s" % message, file=sys.stderr)
        raise SystemExit(2)
    return anchors, classes


def verify_anchors(db, anchors, classes):
    missing = []
    checked = 0
    for key, entry in anchors.items():
        cls = entry["c"]
        name = entry["m"]
        sig = entry["s"]
        descriptor = "L%s;->%s%s" % (cls.replace(".", "/"), name, sig)
        if not db.has_method(descriptor):
            missing.append((key, descriptor))
            print("MISSING %s: %s" % (key, descriptor))
        for candidate in entry.get("candidates") or []:
            if not db.has_type(candidate):
                missing.append((key, "candidate %s" % candidate))
                print("MISSING %s candidate class: %s" % (key, candidate))
        for anchor in entry.get("dexkit") or []:
            if not db.method_has_string(descriptor, anchor):
                missing.append((key, "dexkit %r" % anchor))
                print("MISSING %s dexkit on %s: %s" % (key, descriptor, anchor))
        checked += 1

    for key, cls in classes.items():
        if not db.has_type(cls):
            missing.append((key, "class %s" % cls))
            print("MISSING class %s: %s" % (key, cls))
        checked += 1
    return checked, missing


def main():
    parser = argparse.ArgumentParser(
        description="用 DexSQL 校验 anchors.json 并生成 AnchorKeys.java。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--apk", required=True, help="目标 APK")
    parser.add_argument("--anchors", default=str(DEFAULT_ANCHORS),
                        help="anchors.json（默认 app/src/main/resources/anchors.json）")
    parser.add_argument("--keys-out", default=str(DEFAULT_KEYS),
                        help="AnchorKeys.java 输出路径")
    parser.add_argument("--dexsql", help="dexsql 可执行文件")
    parser.add_argument("--aapt2", help="aapt2/aapt（读取 APK versionName）")
    parser.add_argument("--apkanalyzer", help="apkanalyzer（读取 APK 版本）")
    parser.add_argument("--cache-dir", help="DexSQL 缓存目录（默认按 APK sha256 推导）")
    parser.add_argument("--app-version", default=os.environ.get("APP_VERSION"),
                        help="读取失败时显式指定 versionName")
    parser.add_argument("--version-code", default=os.environ.get("VERSION_CODE"),
                        help="读取失败时显式指定 versionCode")
    parser.add_argument("--check", action="store_true",
                        help="只校验，不写 anchors.json / AnchorKeys.java；不一致时非 0 退出")
    args = parser.parse_args()

    apk = Path(args.apk).expanduser()
    if not apk.is_file():
        raise SystemExit("ERROR: APK not found: %s" % apk)
    anchors_path = Path(args.anchors).expanduser()
    keys_path = Path(args.keys_out).expanduser()
    if not anchors_path.is_file():
        raise SystemExit("ERROR: anchors.json not found: %s" % anchors_path)

    payload = load_anchors(anchors_path)
    anchors, classes = validate_schema(payload)

    dexsql_path = resolve_dexsql(args.dexsql)
    aapt = resolve_aapt(args.aapt2)
    apkanalyzer = resolve_apkanalyzer(args.apkanalyzer)

    app_version, version_code = read_apk_version(apk, aapt, apkanalyzer)
    if not app_version:
        if args.app_version:
            app_version = args.app_version
            print("WARN: 无法从 APK 读取 versionName，使用 --app-version=%s" % app_version,
                  file=sys.stderr)
        else:
            raise SystemExit(
                "ERROR: 无法从 APK 读取 versionName（aapt2/aapt/apkanalyzer 不可用或失败）；"
                "请安装工具，或显式传 --app-version"
            )
    if not version_code:
        if args.version_code:
            version_code = args.version_code
            print("WARN: 无法从 APK 读取 versionCode，使用 --version-code=%s" % version_code,
                  file=sys.stderr)
        else:
            raise SystemExit(
                "ERROR: 无法从 APK 读取 versionCode（aapt2/aapt/apkanalyzer 不可用或失败）；"
                "请安装工具，或显式传 --version-code"
            )
    try:
        version_code_value = int(str(version_code).strip())
    except ValueError:
        raise SystemExit("ERROR: versionCode 不是整数: %r" % version_code)

    print("APK: %s" % apk)
    print("APK version: %s (versionCode %d)" % (app_version, version_code_value))
    print("anchors.json: %s（%d anchors, %d classes）"
          % (anchors_path, len(anchors), len(classes)))

    if payload.get("appVersion") != app_version \
            or payload.get("versionCode") != version_code_value:
        raise SystemExit(
            "ERROR: 版本不一致：APK=%s(%d)，anchors.json=%s(%s)。"
            "请先按新版本重新定位 c/m/s 并更新 appVersion/versionCode。"
            % (app_version, version_code_value,
               payload.get("appVersion"), payload.get("versionCode"))
        )

    print("dexsql: %s" % dexsql_path)
    db = Dexsql(dexsql_path, apk, args.cache_dir)
    print("dexsql cache: %s" % db.cache_file)
    checked, missing = verify_anchors(db, anchors, classes)
    if missing:
        print("ERROR: %d 项缺失，未写入任何文件。" % len(missing), file=sys.stderr)
        return 1

    payload["generatedBy"] = GENERATED_BY
    anchors_text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    keys_text = anchor_keys_source(list(anchors), list(classes))

    if args.check:
        problems = []
        for path, expected in ((anchors_path, anchors_text), (keys_path, keys_text)):
            try:
                current = path.read_text(encoding="utf-8")
            except OSError:
                current = None
            if current != expected:
                problems.append(str(path))
        if problems:
            print("ERROR: 以下生成文件不是最新（请运行本工具重新生成）：", file=sys.stderr)
            for problem in problems:
                print("  %s" % problem, file=sys.stderr)
            return 1
        print("OK: %d 项校验通过，anchors.json / AnchorKeys.java 均是最新。" % checked)
        return 0

    write_text_atomic(anchors_path, anchors_text)
    write_text_atomic(keys_path, keys_text)
    print("OK: %d 项校验通过；wrote %s 和 %s（%d keys）。"
          % (checked, anchors_path, keys_path, len(anchors) + len(classes)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
