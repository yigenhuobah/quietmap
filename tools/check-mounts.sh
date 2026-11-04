#!/usr/bin/env bash
# 挂载点体检：高德升级后跑这个，30 秒内知道 22 项功能（20 可用 + 2 blocked）的挂载点还活着几个。
#
# 用法（Git Bash / Linux / macOS）：
#   bash tools/check-mounts.sh [APK路径] [--apk APK] [--anchors JSON] [--dexsql PATH]
#                               [--aapt2 PATH] [--cache-dir DIR] [--allow-sig] [--allow-gone]
#
# 例：
#   bash tools/check-mounts.sh
#   bash tools/check-mounts.sh ~/Downloads/base.apk
#   TARGET_APK=~/Downloads/base.apk bash tools/check-mounts.sh
#   bash tools/check-mounts.sh --allow-sig   # 已知 SIG 例外，仍返回 0
#
# 退出码：
#   0 = 全部 OK，或 SIG/GONE 全部被 --allow-sig / --allow-gone 显式放行
#   1 = 存在未放行的 SIG 或 GONE（升级回归，需要重新定位）
#   2 = 参数/输入错误（APK/dexsql/anchors.json 缺失或格式错误等）
#   3 = dexsql 查询失败
#
# 参数优先级: CLI > 环境变量 > 常见安装路径。
# 环境变量:
#   TARGET_APK, ANCHORS_JSON, DEXSQL, AAPT2（旧变量 APK 仍作为 TARGET_APK 的别名）
#
# 依赖:
#   - dexsql（必需）：--dexsql / DEXSQL / PATH / tools/dexsql/ 等常见位置
#   - python3/python（必需）：读取并校验 app/src/main/resources/anchors.json
#   - aapt2 或 aapt（可选）：--aapt2 / AAPT2 / PATH / Android SDK build-tools；
#     缺失时只跳过 versionName 校验
#   - 目标 APK（必需）：--apk / TARGET_APK / 当前目录或 ~/Downloads 下的 base*.apk
#
# 缓存按 APK 内容区分：默认写仓库内 tools/.dexsql-cache/<sha256前16位>/，
# 换 APK 自动换目录，不会把旧包的结果当成新包的（也不往 APK 目录写东西）。
# 目录内 cache.meta 记录路径/大小/mtime/sha256；--cache-dir 复用时指纹不符会重建。
#
# 判定规则：
#   OK   = 类和方法都在，且方法完整签名没变，hook 大概还能挂上
#   GONE = 类不存在了（R8/升级改了名），需要重新定位
#   SIG  = 类还在但方法签名/锚点字符串变了（改名 / 参数变化），看差异再决定
#
# 挂载点清单（共 63 条）唯一来源是 app/src/main/resources/anchors.json：
# 每项含 c/m/s/feature/critical/note，混淆类另有 candidates（类名快路径）与
# dexkit（DexKit 方法体字符串）。运行时 hook 通过 AnchorTable/AnchorKeys 读取
# 同一张表，check-mounts 只读表，不再维护第二份清单。
# anchors.json 必须由 tools/gen_anchors.py 生成（顶层 generatedBy 校验），
# 避免手改表绕过 c/m/s 与版本校验。
# AD-03（splash_shake）与 AD-05（bg_afc_taoke）虽为 blocked、运行时不注册 hook，
# 仍保留条目用于版本对比；这两项的 OK 只代表类/方法还在，不代表功能可用。
# 任一带 dexkit 字段的条目：签名在但任一锚点串丢了也判 SIG（运行时 DexKit
# 按锚点定位会失败）。

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_ROOT="$REPO_ROOT/tools/.dexsql-cache"

TARGET_APK="${TARGET_APK:-${APK:-}}"
ANCHORS_JSON="${ANCHORS_JSON:-$REPO_ROOT/app/src/main/resources/anchors.json}"
DEXSQL="${DEXSQL:-}"
AAPT2="${AAPT2:-}"
CACHE_DIR=""
POSITIONAL=""
ALLOW_SIG=0
ALLOW_GONE=0

usage() {
  cat <<'EOF'
Usage:
  tools/check-mounts.sh [APK_PATH] [--apk APK] [--anchors JSON] [--dexsql PATH]
                        [--aapt2 PATH] [--cache-dir DIR] [--allow-sig] [--allow-gone]

Arguments:
  APK_PATH / --apk   目标 APK（高德）；也可用 TARGET_APK 环境变量
  --anchors JSON    锚点表；默认 app/src/main/resources/anchors.json
  --dexsql PATH      dexsql 可执行文件；也可用 DEXSQL
  --aapt2 PATH       aapt2/aapt 可执行文件；也可用 AAPT2（缺失时跳过 versionName 校验）
  --cache-dir DIR    DexSQL 缓存目录；默认 <repo>/tools/.dexsql-cache/<sha256前16位>/
  --allow-sig        已知 SIG 例外：SIG>0 时仍返回 0
  --allow-gone       已知 GONE 例外：GONE>0 时仍返回 0
  -h, --help         显示本帮助

Exit codes: 0=OK（或例外已放行）, 1=未放行的 SIG/GONE, 2=参数/输入错误,
            3=dexsql 查询失败.
Resolution order: CLI > environment > common install paths.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)
      [ $# -ge 2 ] || { echo "ERROR: --apk needs a value" >&2; exit 2; }
      TARGET_APK="$2"; shift 2 ;;
    --apk=*)
      TARGET_APK="${1#*=}"; shift ;;
    --anchors)
      [ $# -ge 2 ] || { echo "ERROR: --anchors needs a value" >&2; exit 2; }
      ANCHORS_JSON="$2"; shift 2 ;;
    --anchors=*)
      ANCHORS_JSON="${1#*=}"; shift ;;
    --dexsql)
      [ $# -ge 2 ] || { echo "ERROR: --dexsql needs a value" >&2; exit 2; }
      DEXSQL="$2"; shift 2 ;;
    --dexsql=*)
      DEXSQL="${1#*=}"; shift ;;
    --aapt2)
      [ $# -ge 2 ] || { echo "ERROR: --aapt2 needs a value" >&2; exit 2; }
      AAPT2="$2"; shift 2 ;;
    --aapt2=*)
      AAPT2="${1#*=}"; shift ;;
    --cache-dir)
      [ $# -ge 2 ] || { echo "ERROR: --cache-dir needs a value" >&2; exit 2; }
      CACHE_DIR="$2"; shift 2 ;;
    --cache-dir=*)
      CACHE_DIR="${1#*=}"; shift ;;
    --allow-sig)
      ALLOW_SIG=1; shift ;;
    --allow-gone)
      ALLOW_GONE=1; shift ;;
    -h|--help)
      usage; exit 0 ;;
    -*)
      echo "ERROR: unknown option $1" >&2; usage >&2; exit 2 ;;
    *)
      if [ -n "$POSITIONAL" ]; then
        echo "ERROR: unexpected argument $1" >&2; usage >&2; exit 2
      fi
      POSITIONAL="$1"; shift ;;
  esac
done

# ---- 工具定位辅助 ---------------------------------------------------------
is_executable_file() {
  local path="$1"
  [ -n "$path" ] && [ -f "$path" ] || return 1
  case "$path" in
    *.exe|*.EXE) return 0 ;;
  esac
  [ -x "$path" ]
}

first_executable() {
  local candidate
  for candidate in "$@"; do
    if is_executable_file "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

# SDK 根目录：环境变量、local.properties、常见安装位置（不写死机器路径）。
sdk_roots() {
  local value line
  for value in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$value" ] && printf '%s\n' "$value"
  done
  if [ -f "$REPO_ROOT/local.properties" ]; then
    line="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' \
      "$REPO_ROOT/local.properties" | tail -n 1 || true)"
    value="${line#*=}"
    value="$(printf '%s' "$value" \
      | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
            -e 's/\\\\/\\/g' -e 's/\\:/:/g')"
    [ -n "$value" ] && printf '%s\n' "$value"
  fi
  printf '%s\n' \
    "$HOME/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "$HOME/AppData/Local/Android/Sdk" \
    "/usr/lib/android-sdk" \
    "/opt/android-sdk"
}

# 在 SDK build-tools 中找最新的 aapt2/aapt（目录名倒序，build-tools 主版本为两位数）。
find_aapt() {
  local sdk_root bt name candidate
  while IFS= read -r sdk_root; do
    [ -n "$sdk_root" ] || continue
    [ -d "$sdk_root/build-tools" ] || continue
    while IFS= read -r bt; do
      [ -n "$bt" ] || continue
      for name in aapt2.exe aapt2 aapt.exe aapt; do
        candidate="$sdk_root/build-tools/$bt/$name"
        if is_executable_file "$candidate"; then
          printf '%s\n' "$candidate"
          return 0
        fi
      done
    done < <(ls -1 "$sdk_root/build-tools" 2>/dev/null | sort -r)
  done < <(sdk_roots)
  return 1
}

# Windows Python 不认 Git Bash 的 /d/... 路径：有 cygpath 时转成原生路径。
to_native_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w -- "$1" 2>/dev/null || printf '%s' "$1"
  else
    printf '%s' "$1"
  fi
}

# ---- APK 定位 -------------------------------------------------------------
# 位置参数也是 CLI，优先级高于环境变量。
if [ -n "$POSITIONAL" ]; then
  TARGET_APK="$POSITIONAL"
fi
if [ -z "$TARGET_APK" ]; then
  for candidate in \
    "$PWD"/base*.apk \
    "$HOME/Downloads"/base*.apk \
    "$HOME/Downloads"/*amap*.apk \
    "$HOME/Desktop"/base*.apk; do
    if [ -f "$candidate" ]; then
      TARGET_APK="$candidate"
      echo "APK (auto-detected): $TARGET_APK" >&2
      break
    fi
  done
fi

# ---- dexsql 定位 ----------------------------------------------------------
if [ -z "$DEXSQL" ]; then
  if command -v dexsql >/dev/null 2>&1; then
    DEXSQL="$(command -v dexsql)"
  elif command -v dexsql.exe >/dev/null 2>&1; then
    DEXSQL="$(command -v dexsql.exe)"
  else
    DEXSQL="$(first_executable \
      "$REPO_ROOT/tools/dexsql/dexsql" \
      "$REPO_ROOT/tools/dexsql/dexsql.exe" \
      "$REPO_ROOT/tools/dexsql.exe" \
      "$HOME/.local/bin/dexsql" \
      "$HOME/bin/dexsql" \
      "/usr/local/bin/dexsql" \
      "$HOME/tools/dexsql/dexsql" \
      "$HOME/tools/dexsql/dexsql.exe" || true)"
  fi
fi

# ---- aapt2/aapt 定位 ------------------------------------------------------
if [ -z "$AAPT2" ]; then
  if command -v aapt2 >/dev/null 2>&1; then
    AAPT2="$(command -v aapt2)"
  elif command -v aapt >/dev/null 2>&1; then
    AAPT2="$(command -v aapt)"
  else
    AAPT2="$(find_aapt || true)"
  fi
fi

# ---- 输入校验 -------------------------------------------------------------
if [ -z "$TARGET_APK" ] || [ ! -f "$TARGET_APK" ]; then
  echo "ERROR: APK not found: ${TARGET_APK:-<unset>}" >&2
  echo "  Fix: pass --apk /path/to/base.apk, or export TARGET_APK=/path/to/base.apk," >&2
  echo "       or place base*.apk in the current directory / ~/Downloads." >&2
  exit 2
fi
if [ -z "$DEXSQL" ] \
  || { ! is_executable_file "$DEXSQL" && ! command -v "$DEXSQL" >/dev/null 2>&1; }; then
  echo "ERROR: dexsql not found: ${DEXSQL:-<unset>}" >&2
  echo "  Fix: install dexsql and put it on PATH, or pass --dexsql /path/to/dexsql," >&2
  echo "       or export DEXSQL=/path/to/dexsql." >&2
  exit 2
fi
if [ -n "$AAPT2" ] && ! is_executable_file "$AAPT2" \
  && ! command -v "$AAPT2" >/dev/null 2>&1; then
  echo "WARN: aapt2/aapt is not executable: $AAPT2; skipping versionName assertion" >&2
  echo "      Fix: pass --aapt2 /path/to/aapt2, export AAPT2=..., or set ANDROID_HOME/ANDROID_SDK_ROOT." >&2
  AAPT2=""
fi

# ---- 锚点表读取：唯一来源 app/src/main/resources/anchors.json -------------
if [ -z "${PYTHON:-}" ]; then
  if command -v python >/dev/null 2>&1; then
    PYTHON="python"
  elif command -v python3 >/dev/null 2>&1; then
    PYTHON="python3"
  else
    echo "ERROR: python not found" >&2
    echo "  Fix: install Python 3 or set PYTHON=/path/to/python." >&2
    exit 2
  fi
fi
if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "ERROR: python not found: $PYTHON" >&2
  echo "  Fix: install Python 3 or set PYTHON=/path/to/python." >&2
  exit 2
fi
case "$ANCHORS_JSON" in
  /*|[A-Za-z]:[\\/]*) ;;
  *) ANCHORS_JSON="$PWD/$ANCHORS_JSON" ;;
esac
if [ ! -f "$ANCHORS_JSON" ]; then
  echo "ERROR: anchors.json not found: $ANCHORS_JSON" >&2
  echo "  Fix: pass --anchors /path/to/anchors.json." >&2
  exit 2
fi
ANCHORS_NATIVE="$(to_native_path "$ANCHORS_JSON")"

MOUNTS_TSV=""
cleanup() {
  [ -n "$MOUNTS_TSV" ] && rm -f "$MOUNTS_TSV"
}
trap cleanup EXIT

MOUNTS_TSV=$(mktemp "${TMPDIR:-/tmp}/qm-anchors-XXXXXX") || {
  echo "ERROR: cannot create temporary file (mktemp)" >&2
  exit 2
}

if ! "$PYTHON" -I - "$ANCHORS_NATIVE" > "$MOUNTS_TSV" <<'PY'
import json
import re
import sys

# Windows Python 默认用本地代码页（如 cp936）且把 \n 写成 \r\n：
# 统一成 UTF-8（dexsql 按 UTF-8 解释 SQL）与 LF，避免中文锚点串被转码。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")

path = sys.argv[1]


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("重复键：%s" % key)
        result[key] = value
    return result


try:
    with open(path, "r", encoding="utf-8") as handle:
        payload = json.load(handle, object_pairs_hook=no_duplicates)
except (OSError, ValueError) as exc:
    print("anchors.json 无法解析：%s" % exc, file=sys.stderr)
    raise SystemExit(2)

errors = []


def err(message):
    errors.append(message)


if not isinstance(payload, dict):
    err("顶层不是对象")
    payload = {}

if payload.get("schemaVersion") != 1:
    err("schemaVersion 必须为 1")
app_version = payload.get("appVersion")
version_code = payload.get("versionCode")
generated_by = payload.get("generatedBy")
if not isinstance(app_version, str) or not app_version.strip():
    err("appVersion 缺失或不是非空字符串")
if isinstance(version_code, bool) or not isinstance(version_code, int) or version_code <= 0:
    err("versionCode 缺失或不是正整数")
if generated_by != "tools/gen_anchors.py":
    err("generatedBy 不是 tools/gen_anchors.py：%r" % generated_by)
classes = payload.get("classes")
if not isinstance(classes, dict):
    err("classes 缺失或不是对象")
anchors = payload.get("anchors")
if not isinstance(anchors, dict):
    err("anchors 缺失或不是对象")
    anchors = {}
if len(anchors) != 63:
    err("挂载点必须保持 63 项，实际 %d 项" % len(anchors))

seen = set()
rows = []
feature_pattern = re.compile(r"^(AD|UI|PV|EN|EX)-[0-9]+$")
for key, entry in anchors.items():
    if not isinstance(key, str) or not key:
        err("锚点 key 非法：%r" % (key,))
        continue
    if not isinstance(entry, dict):
        err("锚点 %s 不是对象" % key)
        continue
    for field in ("c", "m", "s", "feature", "note"):
        value = entry.get(field)
        if not isinstance(value, str) or not value:
            err("锚点 %s 缺少必填字段 %s" % (key, field))
    if not isinstance(entry.get("critical"), bool):
        err("锚点 %s 的 critical 不是布尔值" % key)
    feature = entry.get("feature")
    if isinstance(feature, str) and feature and not feature_pattern.match(feature):
        err("锚点 %s 的 feature 不是统一功能编号：%r" % (key, feature))
    for field in ("candidates", "dexkit"):
        if field in entry:
            value = entry.get(field)
            if not isinstance(value, list) or not value \
                    or any(not isinstance(item, str) or not item for item in value):
                err("锚点 %s 的 %s 必须是非空字符串数组" % (key, field))
    c, m, s = entry.get("c"), entry.get("m"), entry.get("s")
    if not (isinstance(c, str) and isinstance(m, str) and isinstance(s, str)):
        continue
    if not s.startswith("("):
        err("锚点 %s 不是方法锚点（s 必须以 '(' 开头）：%r" % (key, s))
        continue
    descriptor = "L%s;->%s%s" % (c.replace(".", "/"), m, s)
    if descriptor in seen:
        err("重复方法描述符：%s" % descriptor)
    seen.add(descriptor)
    dexkit = entry.get("dexkit") or []
    note = str(entry.get("note") or "").replace("\t", " ").replace("\n", " ")
    rows.append((key, descriptor, str(entry.get("feature") or ""),
                 "true" if entry.get("critical") else "false", note,
                 "\x1f".join(dexkit)))

if errors:
    for message in errors:
        print("anchors.json: %s" % message, file=sys.stderr)
    raise SystemExit(2)

print("META\t%s\t%s\t%d" % (app_version, version_code, len(rows)))
for row in rows:
    print("\t".join(row))
PY
then
  echo "ERROR: invalid anchors.json: $ANCHORS_JSON" >&2
  exit 2
fi

IFS=$'\t' read -r META_TAG TABLE_VERSION TABLE_CODE ANCHOR_COUNT < "$MOUNTS_TSV" || {
  echo "ERROR: anchors.json 读取失败（缺少 META）: $ANCHORS_JSON" >&2
  exit 2
}
# 兼容 Windows Python 可能残留的 CR（sys.stdout.reconfigure 已处理，双保险）。
META_TAG="${META_TAG%$'\r'}"
TABLE_VERSION="${TABLE_VERSION%$'\r'}"
TABLE_CODE="${TABLE_CODE%$'\r'}"
ANCHOR_COUNT="${ANCHOR_COUNT%$'\r'}"
if [ "$META_TAG" != "META" ]; then
  echo "ERROR: anchors.json 读取失败（META 格式错误）: $ANCHORS_JSON" >&2
  exit 2
fi

# ---- 缓存定位 -------------------------------------------------------------
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" 2>/dev/null | cut -c1-16
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" 2>/dev/null | cut -c1-16
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" 2>/dev/null | awk '{print substr($NF, 1, 16)}'
  else
    return 1
  fi
}
file_size() {
  wc -c < "$1" 2>/dev/null | tr -d '[:space:]'
}
file_mtime() {
  stat -c %Y "$1" 2>/dev/null \
    || stat -f %m "$1" 2>/dev/null \
    || date -r "$1" +%s 2>/dev/null
}

APK_SHA="$(sha256_of "$TARGET_APK")"
[ -n "$APK_SHA" ] \
  || { echo "ERROR: cannot compute APK sha256 (need sha256sum, shasum or openssl)" >&2; exit 2; }
APK_SIZE="$(file_size "$TARGET_APK")"
APK_MTIME="$(file_mtime "$TARGET_APK")"
APK_ABS="$(cd "$(dirname "$TARGET_APK")" 2>/dev/null && pwd)/$(basename "$TARGET_APK")"

# 缓存目录按 APK 内容哈希区分：换包必然换目录，不会再拿旧包的缓存查询新包。
# 另外写 cache.meta 记录路径/大小/mtime/sha256：即使 --cache-dir 指向同一目录，
# 同路径替换 APK 也会因指纹不匹配而重建缓存。
[ -z "$CACHE_DIR" ] && CACHE_DIR="$CACHE_ROOT/$APK_SHA"
mkdir -p "$CACHE_DIR" \
  || { echo "ERROR: cannot create cache dir: $CACHE_DIR" >&2; exit 2; }
CACHE="$CACHE_DIR/cache.dxc2"
META="$CACHE_DIR/cache.meta"

meta_value() {
  [ -f "$META" ] || return 1
  sed -n "s/^$1=//p" "$META" | head -1
}
meta_matches() {
  [ -f "$META" ] || return 1
  [ "$(meta_value path)" = "$APK_ABS" ] || return 1
  [ "$(meta_value size)" = "$APK_SIZE" ] || return 1
  [ "$(meta_value sha256)" = "$APK_SHA" ] || return 1
  return 0
}
write_meta() {
  local tmp="$META.tmp"
  {
    printf 'version=2\n'
    printf 'path=%s\n' "$APK_ABS"
    printf 'size=%s\n' "$APK_SIZE"
    printf 'mtime=%s\n' "${APK_MTIME:-unknown}"
    printf 'sha256=%s\n' "$APK_SHA"
  } > "$tmp" && mv -f "$tmp" "$META"
}

if [ -f "$CACHE" ] && ! meta_matches; then
  echo "APK 指纹与缓存元数据不匹配（同路径替换或 --cache-dir 复用），重建缓存..." >&2
  rm -f "$CACHE" "$META"
fi
if [ ! -f "$CACHE" ]; then
  echo "== 首次为该 apk 建 DexSQL 缓存（约 40 秒）..."
  "$DEXSQL" prepare-cache --input "$TARGET_APK" --output "$CACHE_DIR" >/dev/null 2>&1
  [ -f "$CACHE" ] || { echo "建缓存失败: $CACHE" >&2; exit 1; }
  write_meta || echo "WARN: cannot write cache meta: $META" >&2
fi

# 查询失败（非 0 退出码）必须中止：不能把 dexsql 报错当成 0 行结果，
# 否则所有挂载点都会被误判成 GONE。
# stderr 单独写临时文件：成功时丢弃，失败时原样打印（不混进查询结果）。
# 若缓存存在但查询失败（常见于 prepare-cache 记录的 source 路径失效），
# 自动删缓存、重新 prepare-cache 一次后重试；仍失败才 exit 3。
q() {
  local out rc err retried
  err=$(mktemp "${TMPDIR:-/tmp}/qm-dexsql-XXXXXX") || {
    echo "无法创建临时文件（mktemp）" >&2
    exit 3
  }
  retried=0
  while :; do
    out=$("$DEXSQL" query-cache --cache-file "$CACHE" --sql "$1" 2>"$err")
    rc=$?
    if [ "$rc" -eq 0 ]; then
      break
    fi
    if [ "$retried" -eq 0 ] && [ -f "$CACHE" ]; then
      echo "dexsql 查询失败（exit $rc），缓存疑似失效，重建后重试一次..." >&2
      rm -f "$CACHE"
      "$DEXSQL" prepare-cache --input "$TARGET_APK" --output "$CACHE_DIR" >/dev/null 2>&1
      [ -f "$CACHE" ] && write_meta
      retried=1
      continue
    fi
    echo "dexsql 查询失败（exit $rc）: $1" >&2
    cat "$err" >&2
    rm -f "$err"
    exit 3
  done
  rm -f "$err"
  printf '%s\n' "$out"
}

count() {
  local out n
  out=$(q "$1") || exit 3   # q 在子 shell 里 exit，父 shell 需要再退出一次
  n=$(printf '%s\n' "$out" | grep -oE '^\| [0-9]+' | tr -d '| ' | head -1)
  # 查询成功但拿不到计数值同样视为失败（正常的 SELECT count(*) 不会为空）。
  if [ -z "$n" ]; then
    echo "dexsql 查询结果异常（无计数值）: $1" >&2
    [ -n "$out" ] && printf '%s\n' "$out" >&2
    exit 3
  fi
  printf '%s\n' "$n"
}

# 锚点字符串进 SQL LIKE：转义单引号，避免表里出现引号时拼接出错。
sql_like_value() {
  printf '%s' "$1" | sed "s/'/''/g"
}

echo
echo "APK: $TARGET_APK"
echo "APK 指纹: size=$APK_SIZE mtime=${APK_MTIME:-unknown} sha256(前16位)=$APK_SHA"
echo "锚点表: $ANCHORS_JSON（$ANCHOR_COUNT 项, appVersion $TABLE_VERSION / versionCode $TABLE_CODE）"
echo "缓存: $CACHE"
# ── 版本校验：与锚点表实测版本比对，只警告不中断 ────────────────────
EXPECTED_VERSION="$TABLE_VERSION"
VERSION_NAME=""
if [ -n "$AAPT2" ]; then
  VERSION_NAME=$("$AAPT2" dump badging "$TARGET_APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  echo "versionName: ${VERSION_NAME:-未知}  (aapt: $AAPT2)"
  if [ "$VERSION_NAME" != "$EXPECTED_VERSION" ]; then
    echo
    echo "*********************** WARN ***********************"
    echo "  versionName=${VERSION_NAME:-未知}，与锚点表实测版本 $EXPECTED_VERSION 不一致！"
    echo "  方法签名/锚点可能已变，下面的 OK 结果仅供参考。"
    echo "****************************************************"
    echo
  fi
else
  echo "versionName: 跳过校验（找不到 aapt2/aapt）"
  echo "              Fix: pass --aapt2 /path/to/aapt2, export AAPT2=..., or set ANDROID_HOME/ANDROID_SDK_ROOT."
fi
echo
printf '%-8s %-78s %s\n' "状态" "挂载点（完整签名）" "功能"
printf '%.0s-' {1..120}; echo

ok=0
sig=0
gone=0
while IFS=$'\t' read -r key full feat critical note dexkit; do
  [ "$key" = "META" ] && continue
  [ -z "$full" ] && continue
  note="${note%$'\r'}"
  dexkit="${dexkit%$'\r'}"
  # 完整描述符 = "<类描述符含尾分号>-><方法签名>"; 从 "->" 处劈开，
  # 注意不能用 ";->"：那个分号是类描述符自带的，劈掉就查不到类了。
  cls="${full%%->*}"
  cls_hit=$(count "SELECT count(*) FROM classes WHERE descriptor='$cls'") || exit 3
  if [ "${cls_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "GONE" "${full#*->}" "$feat $note"
    gone=$((gone + 1))
    continue
  fi
  mth_hit=$(count "SELECT count(*) FROM methods WHERE descriptor='$full'") || exit 3
  if [ "${mth_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (类在、签名变了)" "$feat $note"
    sig=$((sig + 1))
    continue
  fi
  # 混淆类字符串锚定 SIG 监控：签名在但方法体锚点丢了，运行时 DexKit 会定位失败。
  # dexkit 数组是 AND 语义，全部命中才判 OK（与运行时 addUsingString 一致）。
  anchor_note=""
  if [ -n "$dexkit" ]; then
    anchor_broken=0
    IFS=$'\x1f' read -r -a dex_anchors <<< "$dexkit"
    for anchor in "${dex_anchors[@]}"; do
      [ -z "$anchor" ] && continue
      anchor_escaped="$(sql_like_value "$anchor")"
      anchor_hit=$(count "SELECT count(*) FROM method_strings WHERE method='$full' AND value LIKE '%$anchor_escaped%'") || exit 3
      if [ "${anchor_hit:-0}" = "0" ]; then
        anchor_broken=1
        break
      fi
    done
    if [ "$anchor_broken" -eq 1 ]; then
      printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (锚点字符串丢失,需换锚)" "$feat $note"
      sig=$((sig + 1))
      continue
    fi
    anchor_note=" [锚点OK]"
  fi
  printf '%-8s %-78s %s\n' "OK" "${full#*->}$anchor_note" "$feat $note"
  ok=$((ok + 1))
done < "$MOUNTS_TSV"

total=$((ok + sig + gone))
if [ "$total" -ne "$ANCHOR_COUNT" ]; then
  echo "ERROR: 校验项数 $total 与锚点表 $ANCHOR_COUNT 项不一致" >&2
  exit 3
fi

echo
echo "签名校验: 完整描述符（方法名 + 参数 + 返回类型），无仅名称条目"
echo "锚点校验: anchors.json 的 dexkit 字符串（AND 语义），带该字段的条目全部命中才判 OK"
echo "汇总: 共 $total 项 — OK $ok / SIG $sig / GONE $gone"
if [ "$sig" -eq 0 ] && [ "$gone" -eq 0 ]; then
  echo "二十二钩子挂载点全部存活。"
  exit 0
fi

status=0
if [ "$sig" -gt 0 ] && [ "$ALLOW_SIG" -eq 0 ]; then
  echo "FAIL: SIG=$sig 项未放行（签名/锚点变化，需要重新定位）。" >&2
  status=1
fi
if [ "$gone" -gt 0 ] && [ "$ALLOW_GONE" -eq 0 ]; then
  echo "FAIL: GONE=$gone 项未放行（类已不存在，需要重新定位）。" >&2
  status=1
fi
if [ "$status" -eq 0 ]; then
  echo "已知例外已显式放行：SIG=$sig / GONE=$gone（--allow-sig/--allow-gone）。"
else
  echo "OK=还在；SIG=类在但签名/锚点变了；GONE=类没了。变的项要重新用 dexsql 定位。"
  echo "如确认是已知例外，可加 --allow-sig / --allow-gone 显式放行。"
fi
exit "$status"
