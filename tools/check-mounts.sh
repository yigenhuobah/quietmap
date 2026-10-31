#!/usr/bin/env bash
# 挂载点体检：高德升级后跑这个，30 秒内知道二十二个钩子的挂载点还活着几个。
#
# 用法（Git Bash / Linux / macOS）：
#   bash tools/check-mounts.sh [APK路径] [--apk APK] [--dexsql PATH] [--aapt2 PATH]
#                               [--cache-dir DIR]
#
# 例：
#   bash tools/check-mounts.sh
#   bash tools/check-mounts.sh ~/Downloads/base.apk
#   TARGET_APK=~/Downloads/base.apk bash tools/check-mounts.sh
#
# 参数优先级: CLI > 环境变量 > 常见安装路径。
# 环境变量:
#   TARGET_APK, DEXSQL, AAPT2（旧变量 APK 仍作为 TARGET_APK 的别名）
#
# 依赖:
#   - dexsql（必需）：--dexsql / DEXSQL / PATH / tools/dexsql/ 等常见位置
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
#   SIG  = 类还在但方法签名变了（改名 / 参数变化），看差异再决定
#
# 挂载点清单（共 63 条）与 app/src/main/java/com/quietmap/app/hooks/ 下的常量一一对应，
# 完整签名取自 dexsql 对高德 17.02.0.2010 的实测（17.01.0.2012 → 17.02.0.2010
# 重定位 12 条，混淆名旧→新：Lze5→Lah5、Llt1→Lev1、Ln55→Lp75、La8→Ln8、
# Lcx1→Lry1、Le72→Lo82、Lso2→Lpp2、Lxf2→Lzg2）。
# 混淆类挂载点（Lah5#attemptRequestData、Ln8#run）另有方法体字符串锚点监控：
# 签名在但锚点串丢了也判 SIG（运行时 DexKit 按锚点定位会失败）。
# Ln8 的锚点监控与运行时 Anchors.java 保持一致：两条字符串必须同时命中。

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_ROOT="$REPO_ROOT/tools/.dexsql-cache"

TARGET_APK="${TARGET_APK:-${APK:-}}"
DEXSQL="${DEXSQL:-}"
AAPT2="${AAPT2:-}"
CACHE_DIR=""
POSITIONAL=""

usage() {
  cat <<'EOF'
Usage:
  tools/check-mounts.sh [APK_PATH] [--apk APK] [--dexsql PATH] [--aapt2 PATH]
                        [--cache-dir DIR]

Arguments:
  APK_PATH / --apk   目标 APK（高德）；也可用 TARGET_APK 环境变量
  --dexsql PATH      dexsql 可执行文件；也可用 DEXSQL
  --aapt2 PATH       aapt2/aapt 可执行文件；也可用 AAPT2（缺失时跳过 versionName 校验）
  --cache-dir DIR    DexSQL 缓存目录；默认 <repo>/tools/.dexsql-cache/<sha256前16位>/
  -h, --help         显示本帮助

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

# 挂载点清单：完整方法描述符|功能
# 完整描述符 = 类描述符 + ";->" + 方法名 + 参数/返回值 JNI 签名，与 dexsql 的
# methods.descriptor 列同构，直接全等比对即可判 SIG。
#
# 批 3（首页营销三件套）说明：
# - sendList 批 2 经验的多重载全挂：2 参 + 3 参两条都列。
# - getMessages 批 3 dexsql 复核发现是**三个**重载（任务清单原写 1 个），三条全列。
# - IDeliveryDisplay#start 挂实现类 Lev1（接口层 hook 无效，ART 走 itable 分派；
#   17.01.0.2012 为 Llt1），Lev1 是混淆类，与 Lah5 同样需要「类名变更监控」——这里只查签名，
#   运行时定位失败由 fail-open 记 skip；类名变了查 class_interfaces 反查新实现类。
# - AjxModuleBadge 是 AbstractModuleBadge（抽象类）的唯一子类，
#   updateAppBadge/setBadgeDisplay 的实体实现都在它上面（smali 实测）。
MOUNTS='Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessageInBackground(Landroid/app/Activity;Z)V|bg_msg_fetch 后台拉取营销消息
Lcom/autonavi/minimap/bundle/msgbox/push/BackgroundMsgScreenReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V|bg_msg_fetch 亮屏触发源
Lcom/autonavi/minimap/awaken/AwakenRequestHolder;->sendAwakenPage(Lcom/autonavi/minimap/awaken/param/AwakenPageRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|bg_awaken_report 唤端上报(2参)
Lcom/autonavi/minimap/awaken/AwakenRequestHolder;->sendAwakenPage(Lcom/autonavi/minimap/awaken/param/AwakenPageRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|bg_awaken_report 唤端上报(3参补充)
Lcom/taobao/flowcustoms/afc/taoke/AfcTkManager;->registerTkFlag(Landroid/content/Context;)V|bg_afc_taoke 淘宝客SDK注册
Lcom/ta/audid/upload/UtdidUploadTask;->upload()V|bg_utdid_upload 指纹上传
Lcom/ta/audid/upload/UtdidUploadTask;->uploadFromCache()V|bg_utdid_upload 指纹缓存上传
Lcom/ta/audid/upload/UtdidUploadTask;->reqServer(Ljava/lang/String;)Z|bg_utdid_upload 服务端交换
Lcom/alipay/mobile/common/info/DeviceInfo;->installApk(Ljava/lang/String;)V|h5_install_apk H5静默安装
Lcom/amap/bundle/appupgrade/AppUpgradeController;->q(Lcom/amap/bundle/appupgrade/e;ZZ)Z|popup_upgrade 升级弹窗
Lcom/autonavi/bundle/amaphome/impl/BootBizDataPreloaderImpl;->canShowSplash()Ljava/lang/Boolean;|splash_gate 开屏闸门(返回Boolean.FALSE走NO_SPLASH分支)
Lcom/autonavi/minimap/impl/SplashScreenServiceImpl;->tryShowSplashView(ILjava/lang/String;)V|splash_gate 展示层兜底(不调用)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendSplashScreen(Lcom/autonavi/minimap/alimama/param/SplashScreenRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|splash_request 开屏广告请求(3参,dexsql实测falcon包名)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendSplashScreen(Lcom/autonavi/minimap/alimama/param/SplashScreenRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|splash_request 开屏广告请求(2参重载)
Lah5;->attemptRequestData(IZ)V|splash_request 开屏广告请求调度(混淆类,字符串锚定"不发送请求",SIG监控)
Lcom/autonavi/minimap/entity/SplashButtonInfo;->getShakeGuide()Ljava/lang/String;|splash_shake 摇一摇引导清空
Lcom/autonavi/minimap/entity/SplashButtonInfo;->getShakeTip()Ljava/lang/String;|splash_shake 摇一摇提示清空
Lcom/autonavi/minimap/entity/SplashButtonInfo;->isSpeedStrict()Z|splash_shake 摇一摇速度严格关
Lcom/autonavi/minimap/banner/BannerRequestHolder;->sendList(Lcom/autonavi/minimap/banner/param/BannerListRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|home_banner Banner拉取(2参重载)
Lcom/autonavi/minimap/banner/BannerRequestHolder;->sendList(Lcom/autonavi/minimap/banner/param/BannerListRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|home_banner Banner拉取(3参重载)
Lcom/autonavi/bundle/banner/manager/BannerManager;->b(Ljava/lang/String;ZZLcom/autonavi/bundle/banner/manager/OnLoadBannerListener;)V|home_banner 运营位Banner加载
Lcom/autonavi/bundle/uitemplate/mapwidget/widget/activity/OperateActivityMapWidget;->refreshSplashBar(Lcom/autonavi/minimap/bundle/msgbox/entity/AmapMessage;Ljava/lang/String;Ljava/lang/String;Lcom/autonavi/minimap/bundle/msgbox/listener/ActivityEventCallback;)V|home_operate_bar 首页运营活动条
Lcom/autonavi/bundle/uitemplate/mapwidget/widget/msgtip/MsgTipMapWidget;->showTips(Lcom/autonavi/minimap/bundle/msgbox/entity/AmapMessage;I)Z|home_operate_bar 地图消息气泡(return false)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessages(Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager$GetMessageListener;ZLzg2;)V|home_msg_filter 消息拉取(2参重载)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessages(Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager$GetMessageListener;ZZLzg2;)V|home_msg_filter 消息拉取(3参重载)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessages(Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager$GetMessageListener;ZZLzg2;Ljava/lang/String;)V|home_msg_filter 消息拉取(4参重载)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->retrieveRemoteMessages(Ljava/util/Set;Ljava/lang/String;)V|home_msg_filter 远程营销消息拉取
Lev1;->start(Landroid/app/Activity;Lcom/autonavi/minimap/bundle/msgbox/entity/AmapMessage;)V|home_msg_filter 主图投放(接口IDeliveryDisplay实现类,混淆类)
Lcom/amap/bundle/badgesystem/ajxmodule/AjxModuleBadge;->updateAppBadge(Ljava/lang/String;I)V|home_msg_filter 角标红点更新
Lcom/amap/bundle/badgesystem/ajxmodule/AjxModuleBadge;->setBadgeDisplay(Ljava/lang/String;Z)V|home_msg_filter 角标显示(不调用)
Lcom/autonavi/jni/badgesystem/BadgeSystemManager;->updateAppBadge(Ljava/lang/String;I)V|home_msg_filter 启动器角标
Lcom/autonavi/bundle/amaphome/components/searchbar/BaseSearchBar;->requestPreSetWord(ZLjava/lang/String;)V|search_hotword 搜索框热词请求(唯一重载)
Lcom/autonavi/bundle/uitemplate/preset/PreSetWordManager;->d(Lp75;)V|search_hotword 热词本地计数
Lcom/autonavi/bundle/amaphome/components/searchbar/BaseSearchBar;->uploadPreSetWordTracker(Lp75;)V|search_hotword 热词天擎曝光上报(17.02从PreSetWordManager#e挪到BaseSearchBar)
Lcom/autonavi/bundle/uitemplate/ajx/ModuleSlideContainer;->responsePreSetWords(Ljava/lang/String;)V|search_hotword AJX热词回填(第二道保险)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendH5Log(Lcom/autonavi/minimap/alimama/param/H5LogRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|h5_ad_log H5广告监测(2参重载)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendH5Log(Lcom/autonavi/minimap/alimama/param/H5LogRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|h5_ad_log H5广告监测(3参重载)
Lcom/autonavi/minimap/basemap/floatinglayer/FloatingLayerManager;->a(Ljava/lang/String;Ljava/lang/String;Lorg/json/JSONObject;Lcom/autonavi/minimap/basemap/floatinglayer/FloatingLayerManager$b;)Ljava/lang/String;|map_float_layer 运营浮层注册(返回"")
Ln8;->run()V|msg_image_text 图文素材下载任务(混淆类,字符串锚定,SIG监控)
Lcom/amap/bundle/appupgrade/config/downloader/UpdateHintConfigDownloader;->a()V|popup_clean 升级提示配置下载
Lry1;->addToQueue(Lcom/autonavi/bundle/amaphome/model/DialogConflictInfo;)Z|popup_clean 首页弹窗冲突队列(接口实现类,混淆类)
Lcom/autonavi/minimap/track/b;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/autonavi/minimap/entity/SplashButtonInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Lo82;ZLjava/lang/String;)V|track_suppress 开屏曝光埋点
Lcom/autonavi/minimap/track/b;->f(Ljava/lang/String;Ljava/lang/String;Ljava/util/ArrayList;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Lcom/autonavi/minimap/entity/SplashButtonInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;ILjava/lang/String;Lo82;ZLjava/lang/String;)V|track_suppress 开屏埋点聚合+三方监测
Lcom/autonavi/minimap/track/SplashLogManager;->a(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V|track_suppress 开屏日志上传
Lcom/ut/mini/UTTracker;->send(Ljava/util/Map;)V|track_suppress UT统计总出口
Lcom/ut/mini/UTTracker;->sendLog(Ljava/util/Map;ILjava/util/List;)V|track_suppress UT落盘/转发入口
Lcom/alipay/mobile/nebulacore/plugin/H5ShakePlugin;->handleEvent(Lcom/alipay/mobile/h5container/api/H5Event;Lcom/alipay/mobile/h5container/api/H5BridgeContext;)Z|shake_h5 H5摇一摇(只拦watchShake)
Lcom/alipay/mobile/aompdevice/shake/h5plugin/H5SensorPlugin;->registerGyroscopeListener(Lcom/alipay/mobile/h5container/api/H5Event;)Z|shake_h5 H5陀螺仪注册
Lcom/autonavi/nebulax/extensions/point/AMapAppStartShakePoint;->onInitialized()V|shake_h5 小程序摇一摇初始化
Lcom/autonavi/nebulax/utils/SensorManagerHelper;->onSensorChanged(Landroid/hardware/SensorEvent;)V|shake_h5 摇一摇监听回调
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->handlePush(Ljava/lang/String;)V|bg_msg_fetch 推送消息处理入口
Lcom/autonavi/minimap/bundle/msgbox/push/BackgroundMsgManager;->a(Lcom/autonavi/minimap/bundle/msgbox/push/IBackgroundMsgFetchListener;)V|bg_msg_fetch 后台营销消息拉取
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->fetchMessageFromMainMap(IZLcom/autonavi/minimap/bundle/msgbox/api/IMsgboxService$MainMapUIUpdater;)V|bg_msg_fetch 主图消息拉取包装
Lcom/amap/bundle/cloudconfig/appinit/AppInitServiceImpl;->getOperationModuleSwitch(Z)Z|ops_activity 运营活动模块开关(return false)
Lcom/autonavi/minimap/bundle/activities/ActivitiesService;->openOpetationsActivities(Lcom/autonavi/map/fragmentcontainer/page/AbstractBasePage;Ljava/lang/String;Ljava/lang/String;)V|ops_activity 活动打开入口(3参)
Lcom/autonavi/minimap/bundle/activities/ActivitiesService;->openOpetationsActivities(Lcom/autonavi/map/fragmentcontainer/page/AbstractBasePage;Ljava/lang/String;Ljava/lang/String;Z)V|ops_activity 活动打开入口(4参重载)
Lcom/alipay/android/phone/mobilesdk/permission/guide/PermissionGuideFloatShow;->showNotificationGuide(Lcom/alipay/android/phone/mobilesdk/permission/guide/PermissionGuideRequest;)V|notify_guide 通知权限底部浮条(不展示)
Lcom/alipay/android/phone/mobilesdk/permission/guide/sys/SysPermissionGuideFloatMode;->showFloatWithSysCore()V|notify_guide 系统浮条(仅通知权限跳过)
Lpp2;->i(Landroid/app/Activity;Lorg/json/JSONObject;Ljava/lang/String;)V|notify_guide 权限请求参数桥(permissionType改写)
Lcom/amap/bundle/badgesystem/ajxmodule/AjxModuleBadge;->getAllSessionList(ZLcom/autonavi/minimap/ajx3/core/JsFunctionCallback;)V|msg_center AJX会话列表清空(回调{"convList":[]})
Lcom/amap/bundle/badgesystem/ajxmodule/AjxModuleBadge;->getBadgeInfo(Ljava/lang/String;)Ljava/lang/String;|msg_center 消息Tab角标归零({"value":0})
Lcom/autonavi/minimap/ajx3/modules/internalmodules/AjxModuleLocalStorage;->getItemSync(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;|msg_center 最近消息缓存清空(message_tab/recentlyMsgList-*)
Lcom/autonavi/minimap/ajx3/modules/internalmodules/AjxModuleLocalStorage;->setItemSync(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Z|msg_center 最近消息缓存写入拦截'

echo
echo "APK: $TARGET_APK"
echo "APK 指纹: size=$APK_SIZE mtime=${APK_MTIME:-unknown} sha256(前16位)=$APK_SHA"
echo "缓存: $CACHE"
# ── 版本校验：与清单实测版本比对，只警告不中断 ────────────────────
EXPECTED_VERSION="17.02.0.2010"
VERSION_NAME=""
if [ -n "$AAPT2" ]; then
  VERSION_NAME=$("$AAPT2" dump badging "$TARGET_APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  echo "versionName: ${VERSION_NAME:-未知}  (aapt: $AAPT2)"
  if [ "$VERSION_NAME" != "$EXPECTED_VERSION" ]; then
    echo
    echo "*********************** WARN ***********************"
    echo "  versionName=${VERSION_NAME:-未知}，与挂载点实测版本 $EXPECTED_VERSION 不一致！"
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
while IFS='|' read -r full feat; do
  [ -z "$full" ] && continue
  # 完整描述符 = "<类描述符含尾分号>-><方法签名>"; 从 "->" 处劈开，
  # 注意不能用 ";->"：那个分号是类描述符自带的，劈掉就查不到类了。
  cls="${full%%->*}"
  cls_hit=$(count "SELECT count(*) FROM classes WHERE descriptor='$cls'") || exit 3
  if [ "${cls_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "GONE" "${full#*->}" "$feat"
    gone=$((gone + 1))
    continue
  fi
  mth_hit=$(count "SELECT count(*) FROM methods WHERE descriptor='$full'") || exit 3
  if [ "${mth_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (类在、签名变了)" "$feat"
    sig=$((sig + 1))
    continue
  fi
  # 混淆类字符串锚定 SIG 监控：签名在但方法体锚点丢了，运行时 DexKit 会定位失败
  anchor_note=""
  case "$full" in
    'Lah5;->attemptRequestData(IZ)V')
      anchor_hit=$(count "SELECT count(*) FROM method_strings WHERE method='$full' AND value LIKE '%不发送请求%'") || exit 3
      if [ "${anchor_hit:-0}" = "0" ]; then
        printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (锚点字符串丢失,需换锚)" "$feat"
        sig=$((sig + 1))
        continue
      fi
      anchor_note=" [锚点OK]"
      ;;
    'Ln8;->run()V')
      # 与运行时 Anchors.java 对齐：DexKit 的 addUsingString 是 AND 语义，
      # "invalid download item: "（含尾空格）与 "ImageTextMessageDownloader"
      # 两条必须同时命中；只查其中一条会把运行时挂不上的情况误判成 OK。
      anchor_hit=$(count "SELECT count(*) FROM method_strings WHERE method='$full' AND value LIKE '%invalid download item: %'") || exit 3
      anchor_cls_hit=$(count "SELECT count(*) FROM method_strings WHERE method='$full' AND value LIKE '%ImageTextMessageDownloader%'") || exit 3
      if [ "${anchor_hit:-0}" = "0" ] || [ "${anchor_cls_hit:-0}" = "0" ]; then
        printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (与运行时锚点不一致,需核对 Anchors.java)" "$feat"
        sig=$((sig + 1))
        continue
      fi
      anchor_note=" [锚点OK]"
      ;;
  esac
  printf '%-8s %-78s %s\n' "OK" "${full#*->}$anchor_note" "$feat"
  ok=$((ok + 1))
done <<EOF
$MOUNTS
EOF

total=$((ok + sig + gone))
echo
echo "签名校验: 完整描述符（方法名 + 参数 + 返回类型），无仅名称条目"
echo "汇总: 共 $total 项 — OK $ok / SIG $sig / GONE $gone"
if [ "$sig" -eq 0 ] && [ "$gone" -eq 0 ]; then
  echo "二十二钩子挂载点全部存活。"
else
  echo "OK=还在；SIG=类在但签名/锚点变了；GONE=类没了。变的项要重新用 dexsql 定位。"
fi
