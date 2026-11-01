#!/usr/bin/env bash
# 一键发版：预检 → 构建 → 校验 → 归档产物 →（可选）源码 tag / gh release / 官方仓同步。
#
# 用法：
#   bash tools/release.sh [选项]
#
# 选项：
#   --local-only   只做本地构建/校验/归档，跳过全部发布动作
#   --dry-run      只打印将执行的命令，不构建、不改 git
#   --skip-tests   跳过 :app:testDebugUnitTest
#   --skip-lint    跳过 :app:lintDebug
#   --skip-build   跳过 clean/构建，使用现有 APK
#   --no-tag       不创建源码 tag
#   --no-push      不 push（源码 tag / 官方仓）
#   --no-release   不创建 GitHub Release
#   --no-official  不同步 LSPosed 官方仓库
#   --allow-dirty  允许工作区有未提交改动
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_FILE="$ROOT_DIR/app/build.gradle"
CHANGELOG_FILE="$ROOT_DIR/CHANGELOG.md"
OFFICIAL_REPO="Xposed-Modules-Repo/io.github.yigenhuobah.quietmap"

LOCAL_ONLY=0
DRY_RUN=0
SKIP_TESTS=0
SKIP_LINT=0
SKIP_BUILD=0
NO_TAG=0
NO_PUSH=0
NO_RELEASE=0
NO_OFFICIAL=0
ALLOW_DIRTY=0

CLEANUP_DIRS=()

log() {
    printf 'release: %s\n' "$*"
}

warn() {
    printf 'release: warning: %s\n' "$*" >&2
}

die() {
    printf 'release: error: %s\n' "$*" >&2
    exit 1
}

usage() {
    sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

cleanup() {
    local dir
    for dir in "${CLEANUP_DIRS[@]:-}"; do
        if [ -n "$dir" ]; then
            rm -rf "$dir"
        fi
    done
    return 0
}
trap cleanup EXIT

# dry-run 时只打印命令；其余情况直接执行。
run() {
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
    else
        "$@"
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --local-only) LOCAL_ONLY=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --skip-tests) SKIP_TESTS=1 ;;
        --skip-lint) SKIP_LINT=1 ;;
        --skip-build) SKIP_BUILD=1 ;;
        --no-tag) NO_TAG=1 ;;
        --no-push) NO_PUSH=1 ;;
        --no-release) NO_RELEASE=1 ;;
        --no-official) NO_OFFICIAL=1 ;;
        --allow-dirty) ALLOW_DIRTY=1 ;;
        -h|--help) usage; exit 0 ;;
        *) die "未知参数：$1（见 --help）" ;;
    esac
    shift
done

cd "$ROOT_DIR"

# ---------- 版本号 ----------
[ -f "$GRADLE_FILE" ] || die "找不到 $GRADLE_FILE"
VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)".*/\1/p' "$GRADLE_FILE" | head -1)"
VERSION_CODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$GRADLE_FILE" | head -1)"
[ -n "$VERSION" ] || die "无法从 app/build.gradle 读取 versionName"
[ -n "$VERSION_CODE" ] || die "无法从 app/build.gradle 读取 versionCode"
log "版本 $VERSION（versionCode $VERSION_CODE）"

# ---------- 预检 ----------
# dry-run 只打印计划、不做任何改动，工作区脏不阻塞预览（刚改完还没提交的发布脚本本身就会让树变脏）。
if [ "$ALLOW_DIRTY" -eq 1 ]; then
    warn "--allow-dirty：跳过工作区清洁检查"
elif [ "$DRY_RUN" -eq 1 ]; then
    if [ -n "$(git status --porcelain)" ]; then
        warn "工作区不干净（dry-run 仅预览，不阻塞；正式发布前需提交或加 --allow-dirty）："
        git status --porcelain | sed 's/^/release: warning:   /' >&2
    fi
else
    DIRTY="$(git status --porcelain)"
    [ -z "$DIRTY" ] || die "工作区不干净（用 --allow-dirty 跳过）：
$DIRTY"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || die "当前分支是 $BRANCH，发版只允许 main"

JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -nE 's/.*version "([0-9]+).*/\1/p')"
[ "$JAVA_MAJOR" = "21" ] || die "java -version 不是 JDK 21（当前 ${JAVA_MAJOR:-未知}）"

GRADLE_VERSION_OUT="$(./gradlew -version 2>&1)"
LAUNCHER_MAJOR="$(printf '%s\n' "$GRADLE_VERSION_OUT" | sed -nE 's/^Launcher JVM:[[:space:]]*([0-9]+).*/\1/p' | head -1)"
[ "$LAUNCHER_MAJOR" = "21" ] || die "./gradlew -version 的 Launcher JVM 不是 21（当前 ${LAUNCHER_MAJOR:-未知}）"
DAEMON_LINE="$(printf '%s\n' "$GRADLE_VERSION_OUT" | grep -m1 '^Daemon JVM:' || true)"
DAEMON_MAJOR="$(printf '%s' "$DAEMON_LINE" | sed -nE 's/^Daemon JVM:[[:space:]]*([0-9]+).*/\1/p')"
if [ -z "$DAEMON_MAJOR" ]; then
    # 只显示路径时（例如 jdk-21），用路径里的版本号兜底。
    if printf '%s' "$DAEMON_LINE" | grep -qE 'jdk-21([^0-9]|$)'; then
        DAEMON_MAJOR=21
    fi
fi
[ "$DAEMON_MAJOR" = "21" ] || die "./gradlew -version 的 Daemon JVM 不是 21（${DAEMON_LINE:-未找到 Daemon JVM 行}）"

[ -f "$ROOT_DIR/keystore.properties" ] || die "缺少 keystore.properties（release 必须使用正式签名）"
STORE_FILE="$(grep -m1 -E '^[[:space:]]*storeFile[[:space:]]*=' "$ROOT_DIR/keystore.properties" | cut -d= -f2- | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' || true)"
[ -n "$STORE_FILE" ] || die "keystore.properties 未配置 storeFile"
case "$STORE_FILE" in
    [A-Za-z]:[\\/]*|/*) KEYSTORE_PATH="$STORE_FILE" ;;
    *) KEYSTORE_PATH="$ROOT_DIR/$STORE_FILE" ;;
esac
[ -f "$KEYSTORE_PATH" ] || die "storeFile 不存在：$KEYSTORE_PATH"

# ---------- CHANGELOG ----------
extract_changelog() {
    local version="$1"
    awk -v ver="$version" '
        function heading_version(line,   s) {
            sub(/^##[[:space:]]+/, "", line)
            s = line
            sub(/[[:space:]].*$/, "", s)
            sub(/^v/, "", s)
            return s
        }
        /^##[[:space:]]/ {
            if (found) exit
            if (heading_version($0) == ver) { found = 1; next }
        }
        found { print }
    ' "$CHANGELOG_FILE"
}

[ -f "$CHANGELOG_FILE" ] || die "找不到 CHANGELOG.md"
NOTES="$(extract_changelog "$VERSION" | sed -e :a -e '/^[[:space:]]*$/d; /^---[[:space:]]*$/d; ta')"
[ -n "$NOTES" ] || die "CHANGELOG.md 中没有 $VERSION 的小节（支持 '## 2.3.0 -' 与 '## v2.3.0 —'）"
log "CHANGELOG $VERSION 小节已提取（$(printf '%s\n' "$NOTES" | wc -l | tr -d ' ') 行）"

APK="$ROOT_DIR/app/build/outputs/apk/release/quietmap-${VERSION}.apk"
MAPPING="$ROOT_DIR/app/build/outputs/mapping/release/mapping.txt"
ARTIFACT_DIR="$ROOT_DIR/app/build/release-artifacts/${VERSION}"

# ---------- 构建 ----------
if [ "$SKIP_BUILD" -eq 1 ]; then
    log "--skip-build：跳过构建，使用现有 APK"
else
    GRADLE_ARGS=(clean)
    if [ "$SKIP_TESTS" -eq 0 ]; then
        GRADLE_ARGS+=(:app:testDebugUnitTest)
    else
        log "--skip-tests：跳过单元测试"
    fi
    if [ "$SKIP_LINT" -eq 0 ]; then
        GRADLE_ARGS+=(:app:lintDebug)
    else
        log "--skip-lint：跳过 lint"
    fi
    GRADLE_ARGS+=(:app:assembleRelease)
    run ./gradlew "${GRADLE_ARGS[@]}" --console=plain
fi

if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] bash %s %s --expected-version %s --expected-code %s --out-dir %s\n' \
        "$SCRIPT_DIR/verify-apk.sh" "$APK" "$VERSION" "$VERSION_CODE" "$ARTIFACT_DIR"
    printf '[dry-run] cp %s %s/mapping.txt\n' "$MAPPING" "$ARTIFACT_DIR"
    printf '[dry-run] write %s/release-notes.md\n' "$ARTIFACT_DIR"
fi

if [ "$DRY_RUN" -eq 0 ]; then
    [ -f "$APK" ] || die "构建后找不到 APK：$APK"
    [ -f "$MAPPING" ] || die "构建后找不到 mapping.txt：$MAPPING"
    mkdir -p "$ARTIFACT_DIR"

    # ---------- 校验 + 归档 ----------
    bash "$SCRIPT_DIR/verify-apk.sh" "$APK" \
        --expected-version "$VERSION" \
        --expected-code "$VERSION_CODE" \
        --out-dir "$ARTIFACT_DIR"
    cp "$MAPPING" "$ARTIFACT_DIR/mapping.txt"
    printf '%s\n' "$NOTES" > "$ARTIFACT_DIR/release-notes.md"
    log "产物已归档：$ARTIFACT_DIR"
    log "  $(basename "$APK")"
    log "  mapping.txt"
    log "  SHA256SUMS"
    log "  release-notes.md"
fi

# ---------- 发布段 ----------
publish_source_tag() {
    if [ "$NO_TAG" -eq 1 ]; then
        log "--no-tag：跳过源码 tag"
        return
    fi
    if [ "$DRY_RUN" -eq 0 ] && git rev-parse -q --verify "refs/tags/v${VERSION}" >/dev/null; then
        die "源码 tag v${VERSION} 已存在"
    fi
    run git tag -a "v${VERSION}" -m "v${VERSION}"
    if [ "$NO_PUSH" -eq 1 ]; then
        log "--no-push：跳过 push 源码 tag"
    else
        run git push origin "v${VERSION}"
    fi
}

publish_gh_release() {
    if [ "$NO_RELEASE" -eq 1 ]; then
        log "--no-release：跳过 GitHub Release"
        return
    fi
    if [ "$DRY_RUN" -eq 0 ]; then
        command -v gh >/dev/null 2>&1 || die "未找到 gh，无法创建 Release（或加 --no-release）"
    fi
    run gh release create "v${VERSION}" \
        "$APK" "$ARTIFACT_DIR/mapping.txt" "$ARTIFACT_DIR/SHA256SUMS" "$ARTIFACT_DIR/release-notes.md" \
        --title "$VERSION" \
        --notes-file "$ARTIFACT_DIR/release-notes.md"
}

sync_official_repo() {
    if [ "$NO_OFFICIAL" -eq 1 ]; then
        log "--no-official：跳过官方仓同步"
        return
    fi
    local official_tag="${VERSION_CODE}-${VERSION}"
    local work_dir dl_dir apk_name local_sha remote_sha
    local -a tracked_apks=()

    if [ "$DRY_RUN" -eq 1 ]; then
        printf '[dry-run] clone %s 到临时目录\n' "$OFFICIAL_REPO"
        printf '[dry-run] git rm 官方仓已跟踪的旧 APK（如有）\n'
        printf '[dry-run] cp README.md + %s 到官方仓\n' "$(basename "$APK")"
        printf '[dry-run] git add / commit / tag %s\n' "$official_tag"
        if [ "$NO_PUSH" -eq 0 ]; then
            printf '[dry-run] git push 分支与 tag %s\n' "$official_tag"
            printf '[dry-run] gh release view %s --repo %s（已存在则跳过创建）\n' \
                "$official_tag" "$OFFICIAL_REPO"
            printf '[dry-run] gh release create %s --repo %s --title %s --notes-file %s/release-notes.md %s %s/mapping.txt %s/SHA256SUMS %s/release-notes.md\n' \
                "$official_tag" "$OFFICIAL_REPO" "$VERSION" "$ARTIFACT_DIR" \
                "$(basename "$APK")" "$ARTIFACT_DIR" "$ARTIFACT_DIR" "$ARTIFACT_DIR"
            printf '[dry-run] gh release download %s 并比对 APK SHA-256\n' "$official_tag"
        fi
        return
    fi

    # 官方仓（Xposed-Modules-Repo）没有 CI 生成 Release 资产，需要在这里主动创建，
    # 因此 gh 是必需依赖：缺失直接退出，不再降级跳过校验。
    command -v gh >/dev/null 2>&1 || \
        die "未找到 gh（创建/校验官方仓 Release 必需）。安装：winget install GitHub.cli 或 https://cli.github.com/"

    work_dir="$(mktemp -d)"
    CLEANUP_DIRS+=("$work_dir")
    run gh repo clone "$OFFICIAL_REPO" "$work_dir"

    # 防御性清理：官方仓若还跟踪着旧版 APK（如 quietmap-0.8.4.apk），先 git rm 再提交新资产，
    # 避免仓里同时躺着多个版本的 APK。只删 APK 文件，SUMMARY/README 等一律不动。
    mapfile -t tracked_apks < <(git -C "$work_dir" ls-files -- '*.apk')
    if [ "${#tracked_apks[@]}" -gt 0 ]; then
        log "官方仓移除旧 APK：${tracked_apks[*]}"
        git -C "$work_dir" rm -q -- "${tracked_apks[@]}"
    fi

    [ -f "$work_dir/SUMMARY" ] || warn "官方仓没有 SUMMARY（脚本不会创建/删除它）"
    apk_name="$(basename "$APK")"
    cp "$ROOT_DIR/README.md" "$work_dir/README.md"
    cp "$APK" "$work_dir/$apk_name"
    git -C "$work_dir" add README.md "$apk_name"
    if git -C "$work_dir" diff --cached --quiet; then
        log "官方仓无文件变更（README/APK 已是最新）"
    else
        git -C "$work_dir" commit -m "Release ${VERSION} (${VERSION_CODE})"
    fi
    if git -C "$work_dir" rev-parse -q --verify "refs/tags/${official_tag}" >/dev/null; then
        die "官方仓 tag ${official_tag} 已存在"
    fi
    git -C "$work_dir" tag -a "$official_tag" -m "$official_tag"
    log "官方仓 SUMMARY 保留未改动"

    if [ "$NO_PUSH" -eq 1 ]; then
        log "--no-push：跳过官方仓 push、Release 创建与资产比对（本地提交/标签已完成）"
        return
    fi

    git -C "$work_dir" push origin HEAD
    git -C "$work_dir" push origin "$official_tag"

    # ---------- 官方仓 Release：无 CI 自动生成，push 后主动创建（幂等） ----------
    if gh release view "$official_tag" --repo "$OFFICIAL_REPO" >/dev/null 2>&1; then
        log "官方仓 Release ${official_tag} 已存在，跳过创建"
    else
        log "创建官方仓 Release ${official_tag}（$OFFICIAL_REPO）"
        run gh release create "$official_tag" \
            --repo "$OFFICIAL_REPO" \
            --title "$VERSION" \
            --notes-file "$ARTIFACT_DIR/release-notes.md" \
            "$APK" "$ARTIFACT_DIR/mapping.txt" "$ARTIFACT_DIR/SHA256SUMS" "$ARTIFACT_DIR/release-notes.md"
    fi

    # ---------- 下载官方仓资产，与本地 APK 比对 SHA-256 ----------
    dl_dir="$(mktemp -d)"
    CLEANUP_DIRS+=("$dl_dir")
    gh release download "$official_tag" --repo "$OFFICIAL_REPO" \
        --pattern '*.apk' --dir "$dl_dir" --clobber
    local_sha="$(sha256sum "$APK" | awk '{print $1}')"
    remote_sha="$(sha256sum "$dl_dir"/*.apk | awk '{print $1}' | head -1)"
    if [ "$local_sha" = "$remote_sha" ]; then
        log "官方仓资产 SHA-256 一致：$local_sha"
    else
        die "官方仓资产 SHA-256 不一致：本地 $local_sha / 远端 $remote_sha"
    fi
}

if [ "$LOCAL_ONLY" -eq 1 ]; then
    log "--local-only：跳过源码 tag / gh release / 官方仓同步"
else
    publish_source_tag
    publish_gh_release
    sync_official_repo
fi

if [ "$DRY_RUN" -eq 1 ]; then
    log "dry-run 完成（未构建、未改 git）"
else
    log "完成。APK：$APK"
fi
