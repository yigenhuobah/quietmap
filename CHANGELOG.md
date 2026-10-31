# Changelog

## 0.8.2 - 2026-10-29

诊断与配置可靠性版本（versionCode 11）：收紧诊断通道的进程/会话/权限边界，配置写入改为可验证的异步提交，UI-06 增加页面门控，诊断新增 crit/TTL 语义。功能清单不变，无新增净化项。

### 修复

- **socket peer UID 校验**：`ReportSocketServer` 在接受连接前读取 `LocalSocket#getPeerCredentials`，只接受高德目标进程（`com.autonavi.minimap`）或模块自身的 UID；抽象命名空间 socket 对本机所有应用可见，此前任意应用都能伪造/清空诊断数据。取不到凭据、`getPackagesForUid` 为空或查询异常时一律拒绝（fail-closed），日志记 `qmreport socket reject reason=peer uid`。
- **UI-06 消息页门控**：`AjxModuleLocalStorage#getItemSync/setItemSync` 的 `recentlyMsgList-*` 缓存除 key 匹配外，还要求当前 `IAjxContext#getJsPath` 为消息页/我的页；页面路径能取到且明确是其他页面时原样放行，避免误伤首页/搜索的 AJX 缓存。路径暂时取不到时保持旧的 key-only 行为，并只告警一次。
- **子进程隔离**：`Report` 只在目标 App 主进程启动；`:locationservice`、`:widgetProvider` 仍安装各自需要的 hook，但不再回传 reg/skip。`StatusProvider.applyReport` 对非主进程只合并 `hit.`/`entry.`，`reg.`/`skip.`/`crit.` 一律丢弃，避免“子进程缺少 UI 钩子”的 skip 被算成失败。
- **旧 session 拒绝**：session 改为 `t<启动毫秒>-<uuid>`；`StatusProvider` 遇到启动时间早于已存 session 的迟到批次整批丢弃，不再覆盖或清空新会话数据。文件侧同样按 session 过滤：文件 session 与 prefs 中当前主进程 session 不一致时整份忽略，`lastKnown` 兜底也不会展示上一轮运行的残留数据。
- **FILE_STALE 不计失败**：`failedFeatureCount` 对 `FILE_STALE` 直接返回 0；过期文件只用于诊断页展示“最近一次数据 + 时间”，不再触发异常横幅。
- **Config 逐 key 回退**：`Config.readOverrides` 按 key 逐个读取 remote prefs，单个 key 抛异常只回退该 key 的 `defaultEnabled`，整次加载最多告警一次，其余 key 继续读取；不再因为一个坏值放弃全部开关。
- **ConfigStore 异步 commit**：所有写入提交到单线程 `qm-config-write` 执行器，用 `SharedPreferences.Editor#commit()` 的真实返回值判断是否落盘；remote 不可用时直接返回 false，不再把本地 `apply` 当成写成功。commit 失败时记录最后一次确认提交的值，抵消框架 `RemotePreferences` 内存 map 的乐观更新，避免下一次 refresh 把未落盘的值当成已生效。
- **legacy group 独立**：新 group `config` 与旧 group `features` 分别获取、互不依赖；读取顺序为新值 → 旧值 → 默认值。新 group 绑定失败时旧配置仍可读，旧 group 只读不写。
- **设置页后台 I/O**：`ConfigStore` 构造与首次 remote prefs 绑定移到 `qm-settings-config` 后台线程，与状态查询分离；加载完成前开关置灰，查询带代次号，旧查询结果不能覆盖新结果。commit 失败时仅在 UI 仍停留在该值时回滚开关，避免覆盖用户后续操作。
- **service 死亡重绘**：`ServiceBridge.onServiceDied` 现在也触发重绘，框架断开后设置页立即按 remote 不可用置灰，不再停留在“已激活/可写”的旧状态；重绘回调异常被吞掉，不会反向打断框架回调。
- **NotifyGuide 精确权限匹配**：`isNotificationPermission` 改为按权限名精确匹配（忽略首尾空白与大小写）：只认 `POST_NOTIFICATIONS`、`NOTIFICATION`、`NOTIFICATION_*`，显式排除 `ACCESS_NOTIFICATION_POLICY`；复合权限串（逗号/分号/竖线/内部空白）一律不命中，避免把多权限引导当成单一通知权限。入参支持 `String`/`String[]`/`Iterable`，未知类型 fail-open 返回 false。
- **jsPath markNotFound**：UI-06 各挂载点共用 `jsPath` 失败诊断，进程内只上报一次 warning 并 `HookState.markNotFound(f, "jsPath 获取失败")`，诊断页能看到真实 skip 原因，而不是被后续调用反复覆盖。
- **ReportFileSink 锁失败跳过**：`.lock` 改用非阻塞 `tryLock`，拿不到锁（返回 null 或加锁异常）时本次文件兜底直接返回 false，不再无锁 read-modify-write 覆盖其他进程刚写入的数据；socket/Provider 仍是主通道，失败只记日志。
- **Phase 5 crit / TTL / 零挂载 critical / 空串清理**：
  - 新增 `crit.<feature>` 键（合法键 88 → 110）：`Hooks` 在功能安装收尾时，若“零个成功挂载点且至少一条 skip”，调用 `HookState.markNotFoundCritical` 标记整体失效；可选方法缺失仍只写普通 skip，不计入失败横幅。
  - 失败判定统一收敛到 `ReportProtocol.featureFailed`：已启用、有回传，且 reg 为空或 crit=1 才计入横幅。
  - TTL：回传超过 10 分钟（`DEFAULT_TTL_MS`）没有新数据时失败计数按“未知”返回 0；主进程内容未变的心跳每 5 分钟刷新一次 `meta.updated_at`，避免运行中的目标进程被误判过期。
  - 空串清理：`Report.snapshot` 对每个 touched Feature 都发送 reg/skip/crit，缺失时发空串；Provider 以最新值覆盖，目标进程重开后旧 skip/crit 能被清掉。`crit` 合并严格取较新源的值（空串也覆盖），防止旧 `crit=1` 永远清不掉。
  - 诊断页逐功能摘要新增 `critical` 标记。
- **check-mounts 缓存指纹**：`tools/check-mounts.sh` 新增 `cache.meta`（路径/大小/mtime/sha256）；`--cache-dir` 复用或同路径替换 APK 时指纹不符即重建缓存，避免把旧包结果当成新包。sha256 计算新增 openssl 兜底，汇总前打印“完整描述符校验”说明。

### 变更

- 诊断合法键由 88 增至 110（22 项 Feature × reg/hit/entry/skip/crit 5 个前缀）。
- 诊断页单功能摘要格式：`registered · entry N · hit N · skip 原因 · critical`（存在 crit 时追加）。
- `ServiceBridge` 记录已知边界：`XposedProvider` 由 libxposed 提供且无法插入 `Binder.getCallingUid` 校验，仅做 api>=101 的合理性检查并保持 fail-open；诊断数据通道（Provider/socket）另有 UID 校验。
- 功能清单不变：22 项，可用 22、blocked 0；挂载点 63 项。
- 文档：补记 0.8.1 最终 APK 的 SHA-256（仅 CHANGELOG）。

### 验证

- 单测 215 个用例通过（`:app:testDebugUnitTest`，0 失败 / 0 跳过 / 0 错误）。
- `:app:lintDebug` 通过（22 条 warning、0 error）；`assembleRelease` 通过。
- `tools/verify-apk.sh` PASS（17 个 `[ok]`、0 失败）：versionCode=11、versionName=0.8.2、签名证书 SHA-256 与 0.8.1 一致。
- `tools/check-mounts.sh` 对高德 17.01.0.2012（`E:/download/base(4).apk`，aapt2 读出 versionName 17.01.0.2012）共 63 项：OK 63 / SIG 0 / GONE 0。
- 本轮未做真机回归；UI-05 / AD-12 仍为“挂载点已注册、真实场景未验证”，HyperOS 上 socket 仍不可用（Provider 通道可用）。

APK: `quietmap-0.8.2.apk`
versionCode: `11`
大小: `4,077,344 字节`
SHA-256: `a59d7c8357a290d2ade1b2345456b87159af3ddda84165b7223da397f159259b`

## 0.8.3 - 2026-10-31

高德 17.02.0.2010 适配版本（versionCode 12）：新增关于页「隐藏桌面图标」开关；随设备高德从 17.01.0.2012 升级到 17.02.0.2010，用 dexsql 重定位 12 个签名漂移的挂载点，63 项挂载点体检 63/63 OK（0 SIG / 0 GONE）。功能清单不变（22 项）。

### 新增

- **关于页「隐藏桌面图标」开关**：桌面图标的 LAUNCHER 入口从 `MainActivity` 拆到 `activity-alias`（`com.quietmap.app.LauncherEntry`），`MainActivity` 改挂 `de.robv.android.xposed.category.MODULE_SETTINGS`。开关只禁用 alias，主入口仍在，隐藏后可从 LSPosed 管理器打开关于页恢复；`DONT_KILL_APP` 保证改组件状态不会连带杀进程。
- 新增 `LauncherIconTest`：状态映射纯函数 6 条 + manifest 契约 1 条（alias 存在、`targetActivity` 指向 `MainActivity`、`MainActivity` 保留 `MODULE_SETTINGS`）。

### 适配：高德 17.02.0.2010（12 个挂载点重定位，dexsql 实测）

12 条 SIG 挂载点的旧 → 新映射（混淆名整体改名为主，方法名/锚点串基本未动）：

| # | 挂载点 | 17.01.0.2012（旧） | 17.02.0.2010（新） |
|---|---|---|---|
| 1 | splash_request 请求调度 | `Lze5;->attemptRequestData(IZ)V` | `Lah5;->attemptRequestData(IZ)V`（锚点串"不发送请求"仍在；同锚兄弟类 Lye5 → Lzg5） |
| 2 | home_msg_filter 消息拉取(2参) | `MessageBoxManager#getMessages(...;ZLxf2;)V` | 参数类型改名 `Lzg2;` |
| 3 | home_msg_filter 消息拉取(3参) | `...getMessages(...;ZZLxf2;)V` | 参数类型改名 `Lzg2;` |
| 4 | home_msg_filter 消息拉取(4参) | `...getMessages(...;ZZLxf2;Ljava/lang/String;)V` | 参数类型改名 `Lzg2;` |
| 5 | home_msg_filter 主图投放 | `Llt1;->start(Activity,AmapMessage)V` | `Lev1;->start(...)`（class_interfaces 反查 IDeliveryDisplay 唯一实现类） |
| 6 | search_hotword 热词本地计数 | `PreSetWordManager#d(Ln55;)V` | `d(Lp75;)V`（方法体串 "setPreSetWord obj="/"yyyy-MM-dd" 一致） |
| 7 | search_hotword 热词天擎曝光上报 | `PreSetWordManager#e(Ljava/lang/String;Ln55;)V` | **挪了类**：`BaseSearchBar#uploadPreSetWordTracker(Lp75;)V`（锚点串 "thirdTrackRequest 天擎 曝光" 不变；PreSetWordManager 已无 e 方法） |
| 8 | msg_image_text 素材下载 | `La8;->run()V` | `Ln8;->run()V`（双锚点串 "invalid download item: "+"ImageTextMessageDownloader" 仍在） |
| 9 | popup_clean 弹窗冲突队列 | `Lcx1;->addToQueue(DialogConflictInfo)Z` | `Lry1;->addToQueue(...)`（class_interfaces 反查 IDialogConflictMgr 唯一实现类） |
| 10 | track_suppress 开屏曝光埋点 | `track.b#e(...Le72;Z...)V` | 混淆参数类型改名 `Lo82;`（19 参结构不变） |
| 11 | track_suppress 开屏埋点聚合 | `track.b#f(...Le72;Z...)V` | 混淆参数类型改名 `Lo82;`（20 参结构不变） |
| 12 | notify_guide 权限参数桥 | `Lso2;->i(Activity,JSONObject,String)V` | `Lpp2;->i(...)`（方法体字符串集合逐条一致，旧 Lso2; 已被无关类占用） |

同步更新：`hooks/` 各挂载点常量与注释、`tools/check-mounts.sh` 内置 63 条期望签名与版本断言（EXPECTED_VERSION=17.02.0.2010）、`VersionGate.SUPPORTED_VERSION`、README 目标版本。DexKit 兜底锚点串（"不发送请求"、"invalid download item: "+"ImageTextMessageDownloader"）经 method_strings 复测全部仍在，`Anchors.java` 注释同步。全部 12 条均为 dexsql 直接定位（未动用 jadx）。

### 注意

- ⚠️ **升级后，桌面上通过「长按图标 → 添加到桌面」创建的旧快捷方式会一次性失效**：`MainActivity` 不再带 `LAUNCHER` intent-filter，旧快捷方式指向的组件已无该入口。删掉旧快捷方式、从应用抽屉重新拖一个即可，模块功能本身不受影响。

### 验证

- 单测 222 个用例通过（`:app:testDebugUnitTest`，0 失败 / 0 跳过 / 0 错误）；`:app:lintDebug` 通过（22 条 warning、0 error）。
- `tools/check-mounts.sh` 对高德 17.02.0.2010（`base.apk` sha256 前 16 位 `768d62ee138771c0`，aapt2 读出 versionName 17.02.0.2010）共 63 项：**OK 63 / SIG 0 / GONE 0**；两条混淆锚点监控（`Lah5#attemptRequestData`、`Ln8#run`）均显示 `[锚点OK]`。
- 12 条 SIG 均用 dexsql（`query-cache` 对 `tools/.dexsql-cache/768d62ee138771c0/cache.dxc2`）定位：methods 精确签名反查、method_strings 锚点反查、class_interfaces 接口实现类反查；未使用 jadx。
- 真机（24129PN74C / HyperOS 4.0 / Android 17 / LSPosed 2.2.1）：打开开关 → `disabledComponents` 出现 `LauncherEntry`、`resolve-activity -c android.intent.category.LAUNCHER` 返回 `No activity found`；关闭 → 恢复为 `LauncherEntry`。
- 真机（25102RKBEC / Android 17 / LSPosed 2.2.1 / 高德 17.02.0.2010）：0.8.3 覆盖安装后状态卡显示「目标 17.02.0.2010 · v0.8.3」；开 → `LauncherEntry` 禁用、LAUNCHER 入口消失，关 → 恢复；启动高德后 `AD-02 hit Lah5#attemptRequestData(IZ)`、`PV-04 hit UTTracker#send`、`UI-04 hit UpdateHintConfigDownloader#a`，无 FATAL/ANR。
- 隐藏状态下 `-c de.robv.android.xposed.category.MODULE_SETTINGS` 仍解析到 `MainActivity`（自锁风险已实测排除）；切到别的 tab 再回关于页，开关状态与系统一致；无 FATAL/ANR。
- `tools/verify-apk.sh` PASS（17 个 `[ok]`、0 失败）：versionCode=12、versionName=0.8.3、签名证书 SHA-256 与 0.8.1/0.8.2 一致。
- `:app:assembleRelease` 通过。

APK: `quietmap-0.8.3.apk`
versionCode: `12`
大小: `4,082,484 字节`
SHA-256: `0a08219406a81e1ea32bb512d08a79236c3b7ca7400cb0735c9a4243e403046f`

## v0.8.1 — 2026-10-28

消息中心版本（versionCode 10）：UI-06 从 blocked 恢复可用，基于真机逆向改用 AJX badge/localStorage bridge 清空消息页会话列表与消息图标角标；功能总数 22 项全部可用。

### 修复

- **UI-06 消息中心净化恢复可用**（默认开）：真机确认消息页为 `amap_bundle_messagetab/src/pages/message_tab/MessageTab.page.js`，列表来自 `AjxModuleBadge#getAllSessionList(boolean, JsFunctionCallback)` 返回的 `{"convList":[...]}`，消息图标角标来自 `AjxModuleBadge#getBadgeInfo("messagetab")`，最近消息缓存为 `AjxModuleLocalStorage#getItemSync("message_tab", "recentlyMsgList-<uid>")`。
- 新挂载点：`getAllSessionList` 命中消息页/我的页时回调 `{"convList":[]}`；`getBadgeInfo("messagetab")` 返回 `{"value":0}`；`getItemSync("message_tab", "recentlyMsgList-*")` 返回 `[]`；`setItemSync` 对同一 key 返回 `true`、不落盘。只改 Java/AJX bridge 返回值，不改 JS 资源、不伪造页面。
- 移除 0.8.0 的 4 个 `msg_center` 旧挂载点（`MessageBoxServiceImpl#getOnlineData`、`MsgboxService#getNewComingUnRead_MsgNumFromDB`、`MessageBoxManager#getAllLocalMessages`、`ModuleMessageBox#hasNewMessage`）：真机命中 `hasNewMessage=false` 后消息页列表/角标仍不变；首页消息盒/红点仍由 UI-02 覆盖。

### 变更

- 功能总数 22 项：**可用 22、blocked 0**（0.8.0 为可用 21、blocked 1）；UI-06 默认开，设置页可单独关闭。
- UI-06 只作用于消息页（`messagetab`）与我的页（`MinePage`）；首页、搜索、地图页原样放行。回调为 null、页面路径取不到、类/方法缺失时 fail-open。
- 旧 0.8.0 的 UI-06 blocked 说明保留在下方历史记录中，便于追溯。

### 验证

- 单测 159 个用例通过（0 失败 / 0 跳过）；`:app:lintDebug` 通过（21 条 warning、0 error）；`assembleDebug` / `assembleRelease` 通过。
- `tools/verify-apk.sh` PASS（18 个 `[ok]`、0 失败）；`tools/check-mounts.sh` 对高德 17.01.0.2012 共 63 项，OK 63 / SIG 0 / GONE 0。
- 真机（25102RKBEC / Android 17 / LSPosed 2.2.1 / 高德 17.01.0.2012）：UI-06 开 = 消息页“暂无新消息”空态、无 stuck loading、我的页消息角标 0；关 = 恢复真实会话列表与角标 14；无 FATAL/ANR。配置恢复为 `msg_center=true`，未清数据、未退出账号。
- UI-05 / AD-12 仍为“挂载点已注册、真实场景未验证”，不宣称生效。

APK: `quietmap-0.8.1.apk`
versionCode: `10`
大小: `4,071,144 字节`
SHA-256: `952d8876adb555d31692a3f5060d5f9cfca5d6a88e24e61f91dae1f2726df402`

## v0.8.0 — 2026-10-27

批 6 收尾版本（versionCode 9）：新增运营活动与通知权限引导两个净化开关；消息中心因真机未通过标记为 blocked；诊断回传补入口计数，并确认 Provider 通道可用。

### 新增

- **AD-12 运营活动弹窗净化**（默认开）：`AppInitServiceImpl#getOperationModuleSwitch` 返回 false，`ActivitiesService#openOpetationsActivities` 3 参/4 参重载不调用。真机 3 个挂载点全部 registered，但未触发运营弹窗场景，实际效果**未验证**。
- **UI-05 通知权限引导净化**（默认开）：`PermissionGuideFloatShow#showNotificationGuide` 与 `SysPermissionGuideFloatMode#showFloatWithSysCore` 在命中通知权限时不展示；`so2#i` 仅在单一通知权限且定位已授权时把 permissionType 改写为 `location`，否则原样放行。真机 3 个挂载点全部 registered，但 `notify guide` 无 hit，未观察到通知横条也无法归因，实际效果**未验证**。
- **入口计数（entry）诊断**：`HookState.entry` 经 `Report` 上报 `entry.<key>`，诊断页显示 `registered · entry N · hit N · skip`，用于区分“从未调用”和“调用了但未命中”。上报键由 66 个增至 88 个；Provider/文件/合并规则对 entry 与 hit 一致（非负计数取最大值）。
- **诊断回传 socket 通道**：目标进程优先走抽象命名空间 LocalSocket，失败回退 Provider，再失败写文件。真机 HyperOS 上 socket 报 `socket not created`（SELinux/系统限制），**不可用**；Provider 通道已实测可用（`transport=provider` 持续刷新），因此 socket 失败不再作为阻断条件。

### 变更

- **UI-06 消息中心净化标记 blocked**（默认关、UI 置灰且固定显示关闭、不注册 hook）：blocked 前的真机验收中 4 个挂载点全部 registered，`hasNewMessage` 已返回 `false`，但消息页仍显示完整列表、顶部角标仍为 14，未达到设计效果。保留编号与行位，待重新逆向 AJX 消息页数据链路后再启用。
- `Hooks` 不再注册 `MsgCenterHooks`；`Batch6Probe.DEBUG_PROBE` 固定为 `false`，release 与日常 debug 都不安装探针。
- 功能总数 22 项：**可用 21、blocked 1**；挂载点 63 项。
- 诊断页摘要与异常横幅统计纳入 entry；仅 entry 回传也算“已回传”。

### 验证

- 单测 157 个用例通过（`:app:testDebugUnitTest`）；`:app:lintDebug`、`assembleDebug`、`assembleRelease` 通过。
- `tools/verify-apk.sh` 通过；`tools/check-mounts.sh` 对高德 17.01.0.2012 共 63 项，结果见下方记录。
- 真机（25102RKBEC / Android 17 / LSPosed 2.2.1 / 高德 17.01.0.2012）：
  - Provider 诊断回传通过，诊断页能取到 reg/hit/entry 数据；socket 在 HyperOS 不可用，文件通道仅作调试兜底。
  - UI-05 / AD-12：挂载点 registered，真实场景未验证（无 hit / 未触发），**不宣称生效**。
  - UI-06：真机失败，已 blocked；最终包确认设置项置灰、不注册 hook，消息页保持原始行为。
- 本轮未做 UI-05/AD-12 的完整真机视觉回归；需在通知频控重置、运营弹窗可触发时复验。

APK: `quietmap-0.8.0.apk`
versionCode: `9`
大小: `4,068,476 字节`
SHA-256: `0cc23dc18704edfe4fc9ecbf12f6beb5eec078e5d525e85db193e759d1c8d6d2`

## v0.7.0 — 2026-10-25

工程质量版本（versionCode 8）：工程基建、依赖基线与 UI 骨架升级，**无新增净化功能**。

### 新增

- **底部导航 3 tab**：设置 / 诊断 / 关于改为底部导航切换，单页承载。
- **CI 与工程脚本**：GitHub Actions 全绿流水线；`tools/preflight.sh`（环境/版本/签名预检）、`tools/verify-apk.sh`（Manifest/versionCode/签名/哈希校验）、`tools/release.sh`（一键构建→校验→归档→发版）；新增 preflight/CI/coverage/hook 注册表检查等单测。
- **发布签名强制**：release 构建要求签名配置，CI 走条件引用支持无 keystore 构建；`gradlew` 执行位与 runner 自带 SDK 的 CI 兼容修复。

### 变更

- 依赖基线升级：appcompat / material 更新，JDK baseline 对齐。
- 维护文档移出公开仓库。

### 说明

- 原计划的批 6 功能（AD-12 运营活动、UI-05 通知引导、UI-06 消息中心）经真机验证未能达到设计效果（高德 17.01.0.2012 的 AJX 动态化渲染路径与静态分析不符），**已从本版移除**，待重新逆向适配后再发布。
- 功能清单与 0.6.0 保持一致（19 项），挂载点 53 项。

## v0.6.0 — 2026-10-22

统一改造试点版本（versionCode 7）。

### 新增

- **单 Activity 导航重构**：主页面为设置页，诊断与关于改为二级页；二级页显示返回箭头，返回主页面保留滚动位置；移除点击标题弹出关于对话框的旧交互。
- **诊断页**：逐功能显示 `registered · hit N · skip 原因`，无数据显示 `—`；页首显示数据来源与刷新时间，页尾提供「刷新」「复制诊断信息」；进入页面与 `onResume` 刷新，不自动轮询。
- **关于页**：展示模块名、版本名/versionCode、目标应用与适配版本、源码链接、四模块互链、免责声明与许可证；当前模块标记「（当前）」。
- **状态卡四态**：正在连接框架… / 未激活 / 框架已连接 / 已激活；副文案显示框架 API、目标版本与 remote prefs 可用性。
- **异常横幅**：目标版本未适配，或已启用且明确失败的功能数 ≥2 时显示；不新增第五种状态。
- 功能编号统一为 AD-01～AD-11、UI-01～UI-04、PV-01～PV-04，共 19 项；设置页按 AD → UI → PV 分组。
- 默认值调整：AD 广告净化与 UI 界面精简默认开启，PV 隐私保护默认关闭（此前 19 项全部默认开启）。

### 修复

- 主页面移除内联诊断卡，不再显示逐功能 registered/hit/skip；诊断细节统一收敛到诊断页。
- 状态判定改为 service → scope → runningTargets 顺序，避免仅凭 service 绑定误报「已激活」。
- 所有用户可见文案迁移到 `strings.xml`，清理 Java 与 XML 中的中文字面量。

### 加固

- `StatusProvider` 新增 `failedFeatureCount(Context)`，按 `qm_report` 中已启用功能的 `reg`/`skip` 回传统计失败数。
- 诊断数据读取统一走 `StatusProvider` 的存储前缀，避免页面与 Provider 的键格式漂移。
- 构建基线统一为 AGP 8.13.2 / Gradle 8.13 / JDK 17 / compileSdk 37 / minSdk 29 / targetSdk 36。
- release 缺少 `keystore.properties` 时硬失败，禁止 debug 签名回退。

### 文档

- README 更新功能表编号、默认值、设置页结构、模块互链与构建工具链。
- 新增本 CHANGELOG。

### 验证

- `./gradlew test assembleDebug assembleRelease` 通过。
- 单测 43 个用例全部通过（debug/release 各执行一次，共 86 次）。
- 模拟器 UI 冒烟通过：主页面、诊断页、关于页均可打开；诊断页首次打开发现 `PreferenceCategory` 空指针，已修复并复测。未做真机验证。

APK: `quietmap-0.6.0.apk`
versionCode: `7`
大小: `3.5 MB（3,679,914 字节）`
SHA-256: `c6db27b5db35f12b170acd7b2fe6067fff0ccb4ee3488def34f6847d06c4b4e5`
