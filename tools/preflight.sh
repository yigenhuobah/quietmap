#!/usr/bin/env bash
# 本地预检：JDK 21、Android SDK 配置、单元测试与 lint。
#
# 不要求 keystore，也不要求工作区干净：release 签名由 tools/release.sh 负责，
# 本脚本只保证「能编译、能跑单测、能过 lint」。
#
# 用法:
#   bash tools/preflight.sh
#
# 退出码: 0 全部通过；非 0 表示环境或构建失败。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

info() { echo "==> $*"; }
fail() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------- JDK 21
if ! command -v java >/dev/null 2>&1; then
    fail "java not found in PATH; install JDK 21 and set JAVA_HOME"
fi
JAVA_FIRST_LINE="$(java -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVA_FIRST_LINE" \
    | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
if [ -z "$JAVA_MAJOR" ]; then
    JAVA_MAJOR="$(printf '%s\n' "$JAVA_FIRST_LINE" \
        | sed -n 's/.*version \([0-9][0-9]*\).*/\1/p')"
fi
[ -n "$JAVA_MAJOR" ] || fail "cannot parse java -version output: $JAVA_FIRST_LINE"
[ "$JAVA_MAJOR" -eq 21 ] || fail "java must be 21.x, got: $JAVA_FIRST_LINE"
info "java: $JAVA_FIRST_LINE"

# ------------------------------------------------------- Gradle JVM 21
[ -x ./gradlew ] || fail "./gradlew not found or not executable (run from the repo root)"
GRADLE_VERSION_OUTPUT="$(./gradlew -version 2>&1)"
GRADLE_JVM_LINE="$(printf '%s\n' "$GRADLE_VERSION_OUTPUT" \
    | grep -m1 -E '^(Launcher JVM|Daemon JVM):' || true)"
GRADLE_JVM_MAJOR="$(printf '%s\n' "$GRADLE_JVM_LINE" \
    | sed -n 's/.*JVM:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
if [ -z "$GRADLE_JVM_MAJOR" ]; then
    # 某些 Gradle 版本 Daemon JVM 行只有路径，回退取输出里第一个带版本号的 JVM 行。
    GRADLE_JVM_MAJOR="$(printf '%s\n' "$GRADLE_VERSION_OUTPUT" \
        | sed -n 's/.*JVM:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1)"
fi
[ -n "$GRADLE_JVM_MAJOR" ] || fail "cannot determine Gradle JVM version; output:
$GRADLE_VERSION_OUTPUT"
[ "$GRADLE_JVM_MAJOR" -eq 21 ] \
    || fail "Gradle JVM must be 21.x, got: ${GRADLE_JVM_LINE:-<unknown>}"
info "gradle jvm: $GRADLE_JVM_LINE"

# ---------------------------------------------------------------- SDK
SDK_SOURCE=""
if [ -f local.properties ]; then
    SDK_LINE="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' local.properties \
        | tail -n 1 || true)"
    SDK_VALUE="${SDK_LINE#*=}"
    SDK_VALUE="$(printf '%s' "$SDK_VALUE" \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if [ -n "$SDK_VALUE" ]; then
        SDK_SOURCE="local.properties sdk.dir=$SDK_VALUE"
    fi
fi
if [ -z "$SDK_SOURCE" ]; then
    for var_name in ANDROID_HOME ANDROID_SDK_ROOT; do
        var_value="${!var_name:-}"
        if [ -n "$var_value" ] && [ -d "$var_value" ]; then
            SDK_SOURCE="$var_name=$var_value"
            break
        fi
    done
fi
if [ -z "$SDK_SOURCE" ]; then
    fail "Android SDK not configured. Fix one of:
  - set sdk.dir=/path/to/Android/Sdk in local.properties
  - export ANDROID_HOME=/path/to/Android/Sdk
  - export ANDROID_SDK_ROOT=/path/to/Android/Sdk"
fi
info "android sdk: $SDK_SOURCE"

# ------------------------------------------------- README 版本一致性
# 「当前版本」长期不随 build.gradle 更新，README 与发布页会对不上；这里提前硬失败。
VERSION_NAME="$(sed -nE "s/^[[:space:]]*versionName[[:space:]]+['\"]([^'\"]+)['\"].*/\1/p" app/build.gradle | head -n 1)"
VERSION_CODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' app/build.gradle | head -n 1)"
[ -n "$VERSION_NAME" ] || fail "cannot parse versionName from app/build.gradle"
[ -n "$VERSION_CODE" ] || fail "cannot parse versionCode from app/build.gradle"
README_HEAD="$(head -n 12 README.md)"
printf '%s\n' "$README_HEAD" | grep -qF "$VERSION_NAME" \
    || fail "README.md 首屏未包含当前 versionName ($VERSION_NAME)：请同步「当前版本」"
printf '%s\n' "$README_HEAD" | grep -qF "versionCode $VERSION_CODE" \
    || fail "README.md 首屏未包含当前 versionCode ($VERSION_CODE)：请同步「当前版本」"
info "readme version: $VERSION_NAME (versionCode $VERSION_CODE)"

# CHANGELOG 必须有当前版本的非空小节，防止无条目或「修复若干」发布。
if ! awk -v v="$VERSION_NAME" '
    $0 ~ "^## " v "([[:space:]]|$)" { in_section = 1; next }
    in_section && /^## / { exit }
    in_section && NF { found = 1 }
    END { exit found ? 0 : 1 }
' CHANGELOG.md; then
    fail "CHANGELOG.md 缺少 $VERSION_NAME 的非空小节"
fi
info "changelog section: $VERSION_NAME"

# ---------------------------------------------------------------- tests + lint
info "running ./gradlew :app:testDebugUnitTest :app:lintDebug"
./gradlew :app:testDebugUnitTest :app:lintDebug

info "preflight OK"
