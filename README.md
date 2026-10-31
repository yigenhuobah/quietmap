# 静途 (QuietMap)

让高德地图安静下来的 LSPosed 模块：后台净化、数据层断源、收敛弹窗与统计上报。

> 当前版本 **0.8.3**（2026-10-31，versionCode 12）。
> 目标应用：**高德地图 17.02.0.2010**（`com.autonavi.minimap`）。
> 与高德地图官方无任何关联，仅供学习、研究和个人设备使用。

**仓库**：<https://github.com/yigenhuobah/quietmap> ｜ **下载**：[Releases](https://github.com/yigenhuobah/quietmap/releases) ｜ **反馈**：[Issues](https://github.com/yigenhuobah/quietmap/issues/new/choose)

## 兼容性

| 项 | 值 |
|---|---|
| 目标应用 | `com.autonavi.minimap` **17.02.0.2010** |
| 模块包名 | `io.github.yigenhuobah.quietmap` |
| Xposed API | libxposed API 102（`minApiVersion=102` / `targetApiVersion=102`） |
| 系统要求 | Android 10+（minSdk 29） |
| 框架 | 支持 API 102 的 LSPosed / Vector |
| 架构 | Java 17 + XML + Material 3，无 Kotlin/Compose |

## 安装

1. 安装 `quietmap-0.8.3.apk`。
2. 在 LSPosed 管理器中启用「静途」，作用域勾选「高德地图」。
3. 强制停止并重新打开高德地图。
4. 打开模块 App，确认状态卡显示「已激活」。
5. 按需开关功能；修改后重启高德地图生效。

## 功能

共 **22 项**功能：**可用 22 项，blocked 0 项**。默认值遵循：广告净化（AD）与界面精简（UI）开启，隐私保护（PV）关闭。每项功能均可单独关闭。

### 广告净化（AD，默认开）

| ID | key | 功能 |
|---|---|---|
| AD-01 | `splash_gate` | 开屏广告拦截（`canShowSplash` 返回 FALSE + 展示层兜底） |
| AD-02 | `splash_request` | 开屏广告请求断源（`sendSplashScreen` 全重载 + 混淆调度类） |
| AD-03 | `splash_shake` | 摇一摇开屏禁用（清空引导/提示，关闭速度严格判定） |
| AD-04 | `bg_msg_fetch` | 后台营销消息拉取净化（亮屏触发、后台拉取、推送处理、主图拉取） |
| AD-05 | `bg_afc_taoke` | 淘宝客广告 SDK 冷启动注册断源 |
| AD-06 | `home_banner` | 首页 Banner 拉取与加载断源 |
| AD-07 | `home_operate_bar` | 首页运营活动条与地图消息气泡屏蔽 |
| AD-08 | `h5_ad_log` | H5 广告点击/曝光监测断源 |
| AD-09 | `map_float_layer` | 地图运营浮层挂件不注册 |
| AD-10 | `msg_image_text` | 营销图文消息素材下载断源 |
| AD-11 | `shake_h5` | H5/小程序摇一摇传感器不注册（保留页面正常振动） |
| AD-12 | `ops_activity` | 运营活动模块关闭与活动打开入口拦截（真实场景未验证） |

### 界面精简（UI，默认开）

| ID | key | 功能 |
|---|---|---|
| UI-01 | `popup_upgrade` | 升级弹窗屏蔽 |
| UI-02 | `home_msg_filter` | 首页消息/红点数据层断源、主图投放与角标红点净化 |
| UI-03 | `search_hotword` | 搜索热词净化（不影响输入联想） |
| UI-04 | `popup_clean` | 升级提示配置不下载、首页弹窗冲突队列不入队（中风险，可单关） |
| UI-05 | `notify_guide` | 通知权限引导条不展示、权限请求参数安全改写（真实场景未验证） |
| UI-06 | `msg_center` | 消息中心净化（AJX 会话列表清空、消息图标角标归零；只作用于消息页/我的页，可单关） |

### 隐私保护（PV，默认关）

| ID | key | 功能 |
|---|---|---|
| PV-01 | `bg_awaken_report` | 唤端上报断源 |
| PV-02 | `bg_utdid_upload` | 设备指纹上传与服务端交换断源 |
| PV-03 | `h5_install_apk` | H5 静默下载安装 APK 断源 |
| PV-04 | `track_suppress` | 开屏埋点、开屏日志与 UT 统计上报断源 |

`popup_clean` 属于中风险项：它拦截的是首页通用弹窗冲突队列，可能连带吞掉功能引导、分享口令等业务弹窗。遇到异常弹窗缺失时单独关闭这一项即可。

### 批 6 功能验证状态

| 功能 | 状态 | 说明 |
|---|---|---|
| AD-12 运营活动 | 已注册，真实场景未验证 | 3 个挂载点在真机全部 `registered`，但未触发 88VIP/运营弹窗场景，没有 `ops activity` 命中日志，不宣称已生效 |
| UI-05 通知权限引导 | 已注册，真实场景未验证 | 3 个挂载点在真机全部 `registered`；通知权限已撤销但 `notify guide` 无命中，无法排除系统频控/`noLongerPrompt`，不宣称已生效 |
| UI-06 消息中心 | 0.8.1 真机通过；0.8.2 未复验 | 已定位 AJX 真实链路：`AjxModuleBadge#getAllSessionList` 返回 `convList`、`getBadgeInfo("messagetab")` 提供角标、`AjxModuleLocalStorage#getItemSync("message_tab", "recentlyMsgList-*")` 为最近消息缓存；0.8.1 真机开启为空列表/角标 0，关闭恢复列表/角标 14，无 FATAL/ANR。0.8.2 新增消息页/我的页 `jsPath` 门控，尚未真机复验 |

`socket` 与 `Provider` 诊断回传的真实状态：目标进程优先尝试抽象命名空间 LocalSocket，失败回退 Provider，再失败写文件。HyperOS 真机上 socket 报 `socket not created`，**不可用**；Provider 通道已实测可用（`transport=provider` 持续刷新），因此 socket 失败不再作为阻断条件。Android 11+ 文件通道仅作调试/旧系统兜底。0.8.2 起 socket 接受连接前校验 peer UID，只接受高德目标进程或模块自身。

## 设置页

单 Activity + 底部导航 3 tab（设置 / 诊断 / 关于），无侧栏：

- **状态卡**：正在连接框架… / 未激活 / 框架已连接 / 已激活，四态不新增第五态；副文案显示框架 API、目标版本与 remote prefs 可用性。
- **异常横幅**：目标版本未适配，或已启用且明确失败的功能数 ≥2 时显示；目标应用未安装、只有 1 项失败、没有诊断数据时不显示。
- **功能开关**：按 AD → UI → PV 分组；标题与摘要来自功能清单，值写入框架 remote prefs；22 项全部可切换。
- **诊断页**：逐功能显示 `registered · entry N · hit N · skip 原因`（整体失效时追加 `critical`），无数据显示 `—`；`entry` 表示钩子入口被调用次数，用于区分“从未调用”和“调用了但未命中”；`critical` 表示该功能没有任何成功安装的挂载点，计入异常横幅，普通 `skip`（可选方法缺失）只展示不计失败；超过 10 分钟没有新回传的数据按“未知”处理。页首显示数据来源与刷新时间，页尾提供「刷新」「复制诊断信息」；进入页面与 `onResume` 刷新，不自动轮询。
- **关于页**：模块名、版本名/versionCode、目标应用与适配版本、源码、四模块互链、免责声明、许可证；末尾「桌面图标」分组的**隐藏桌面图标**开关只禁用承载 LAUNCHER 的 `activity-alias`，隐藏后仍可从 LSPosed 管理器打开本页关掉开关恢复。
- **页脚**：重启生效与兼容版本提示。

## 模块互链

| 模块 | 仓库 |
|---|---|
| 云音乐工具箱 (CloudMusicTool) | <https://github.com/yigenhuobah/cloudmusictool> |
| 静盒 (QuietBox) | <https://github.com/yigenhuobah/quietbox> |
| 静途 (QuietMap) | <https://github.com/yigenhuobah/quietmap> |
| 贴吧静净 (TiebaQuiet) | <https://github.com/yigenhuobah/tiebaquiet> |

## 兼容性说明

**仅保证高德地图 17.02.0.2010。** 其它版本尽力而为：大多数挂载点是非混淆业务类，升级后大概率仍在，但方法签名可能变化。升级高德后先跑挂载点体检：

```bash
bash tools/check-mounts.sh "E:\path\to\amap.apk"
```

输出 `OK` / `SIG` / `GONE` 三态，共 **63 项**。DexSQL 缓存按 APK sha256 前 16 位隔离（`tools/.dexsql-cache/<sha256>/`），并在 `cache.meta` 记录路径/大小/mtime/sha256：换包、同路径替换或复用 `--cache-dir` 时指纹不符会自动重建，不会把旧包结果当成新包；查询失败（dexsql 非 0 退出或结果异常）会直接中止，不会把错误结果当成 0 行而误报 `GONE`。脚本会打印 APK 的 versionName，非 **17.02.0.2010** 时给出 WARN（只提示、不中断）。混淆挂载点（`Lah5#attemptRequestData`、`Ln8#run`、`Lry1#addToQueue`、`Lev1#IDeliveryDisplay.start`，17.01.0.2012 为 Lze5/La8/Lcx1/Llt1）随版本改名的风险最高，失效时模块只记 `skip`，不会影响其余钩子。

## 稳定性设计（摘要）

- 所有 Hooker 使用匿名内部类（不用 lambda），规避 bootclasspath desugar 问题。
- 每个 `intercept` 整段 `try/catch(Throwable)`，异常时 fail-open 放行原逻辑。
- 抑制类钩子命中路径不调用原方法；回退路径 `chain.proceed()` 恰好一次，禁止双重执行。
- 统一 `PROTECTIVE` 异常模式 + 稳定 hook id；同一方法只挂一个 hook。
- 框架类黑名单：`android.` / `java.` / `dalvik.` / `androidx.` / `kotlin.` 开头的类拒绝 hook。
- 定位失败只记 `<featureId> skip: ...`，绝不向外抛；安装整段再包一层 `try/catch(Throwable)`。
- 只做「不调用 / 返回常量」，不修改复杂业务状态；开关只认 remote prefs。
- 诊断通道双重校验：Provider 校验 `Binder.getCallingUid`，socket 校验 peer UID；子进程只贡献 hit/entry，旧 session 与过期数据不参与失败统计。
- 配置写入异步 `commit` 并校验真实返回值，失败回滚 UI；框架 service 断开后立即重绘并置灰开关。

## 已知限制

- `libxposed` 的 `XposedProvider` 在 `SendBinder` 调用上没有调用方认证，属框架约定风险（不影响本模块自身逻辑）。
- 诊断 Provider 仅接受高德 UID：`Binder.getCallingUid` + `getPackagesForUid` 必须命中 `com.autonavi.minimap`；诊断 socket 同样校验 peer UID，只接受高德或模块自身，其他应用的连接被拒绝。
- 诊断回传在 HyperOS 上 socket 不可用（`socket not created`），实际可用的是 Provider 通道；文件通道仅调试/旧系统兜底。socket 失败会回退，不阻断诊断。
- UI-05 通知权限引导与 AD-12 运营活动已在真机注册挂载点，但真实场景未验证（无命中/未触发），不能视为已生效。
- UI-06 消息中心通过 AJX badge/localStorage bridge 清空消息页会话列表与消息图标角标；会一并隐藏账号安全、公共交通动态等真实通知，可单独关闭。仅保证高德 17.02.0.2010，并且只作用于消息页/我的页。
- 诊断会话隔离：session 不一致的旧文件与启动时间更早的迟到批次会被忽略；目标进程重启后、首次回传前诊断页可能显示 `—`，属预期行为。
- DexKit 扫描仅在主进程启用；非主进程（`:locationservice`、`:widgetProvider`）走类名/方法名兜底，且不再回传 reg/skip。
- release 缺少 `keystore.properties` 时构建直接失败（`assembleRelease` / `bundleRelease` 抛 `GradleException`），避免产出 debug 签名的正式包。

## 全量覆盖矩阵

对照前期调研的 40+ 点位，逐条给出结论。批号与开关名对应实现；「有意不做」附理由；「技术不可行」指 dex 层没有可安全落地的本地实现。

| B 报告点位 | 分类 | 批号 / 开关 | 说明 |
|---|---|---|---|
| 1.1 `SplashScreenServiceImpl.tryShowSplashView` | 已覆盖 | 批2 `splash_gate` | 不调用，展示层兜底 |
| 1.2 `BootBizDataPreloader.canShowSplash` | 已覆盖 | 批2 `splash_gate` | 返回 `Boolean.FALSE`，走官方 NO_SPLASH |
| 1.3 `g.o(I,String)` | 有意不做 | — | 318 指令启动关键方法，nop 风险高 |
| 1.4 `SplashFrequencyController.a/b` | 有意不做 | — | 语义未实测，误 hook 会每次弹开屏 |
| 1.5 `Lze5.attemptRequestData` | 已覆盖 | 批2 `splash_request` | 不调用；DexKit 字符串锚定 |
| 1.6 `AlimamaRequestHolder.sendSplashScreen` | 已覆盖 | 批2 `splash_request` | 两个重载全挂 |
| 1.7 `nowHasSplashItemsFromAlimama` | 有意不做 | — | 请求/展示已双断，单独改缓存判断无额外收益 |
| 1.8 倒计时/跳过按钮 | 有意不做 | — | 保留用户主动跳过，避免误伤 |
| 1.9 `ShakeButton` + `SplashButtonInfo` | 已覆盖 | 批2 `splash_shake` | getter 返回空串/false |
| 1.10 `g.clickAd` | 有意不做 | — | 会禁用正常广告点击行为 |
| 1.11 `track.b.e/f` + `SplashLogManager` | 已覆盖 | 批5 `track_suppress` | 不调用 |
| 2.1 `BannerRequestHolder.sendList` | 已覆盖 | 批3 `home_banner` | 2/3 参重载全挂 |
| 2.2 `BannerManager.b` | 已覆盖 | 批3 `home_banner` | 不调用 |
| 2.3 `OperateActivityMapWidget.refreshSplashBar` | 已覆盖 | 批3 `home_operate_bar` | 不调用 |
| 2.4 `MsgTipMapWidget.showTips` | 已覆盖 | 批3 `home_operate_bar` | 返回 false |
| 2.5 `MessageBoxManager.getMessages` / `retrieveRemoteMessages` | 已覆盖 | 批3 `home_msg_filter` | 3 个 getMessages 重载 + 远程拉取全断 |
| 2.5b `fetchMessageFromMainMap` | 已覆盖 | 批5 `bg_msg_fetch` | 不调用 |
| 2.6 `IDeliveryDisplay.start` | 已覆盖 | 批3 `home_msg_filter` | 挂实现类 `Llt1` |
| 2.7 `AjxModuleBadge` 角标 | 已覆盖 | 批3 `home_msg_filter` | 更新 count>0 抑制 / count<=0 放行 + 显示不调用；另挂启动器角标 `BadgeSystemManager#updateAppBadge` |
| 2.8 `AMapHomeMsgManager` | 已覆盖（间接） | 批3 `home_msg_filter` | 入口走 getMessages / IDeliveryDisplay |
| 3.1 `BaseSearchBar.requestPreSetWord` | 已覆盖 | 批4 `search_hotword` | 不调用 |
| 3.2 `PreSetWordManager.e/d` | 已覆盖 | 批4 `search_hotword` | 不调用 |
| 3.3 `ISearchService.sugg` | 有意不做 | — | 会破坏正常联想输入体验 |
| 3.4 `logOnRecommendWordResponse` | 有意不做 | — | 纯曝光上报；保留 sugg 时属正常搜索行为 |
| 3.5 `processSuggestNetWorkData` | 有意不做 | — | 过滤联想列表伤体验 |
| 4.1 `AppUpgradeController.q` | 已覆盖 | 批1 `popup_upgrade` | 返回 false |
| 4.2 `UpdateHintConfigDownloader.a` | 已覆盖 | 批5 `popup_clean` | 不调用 |
| 4.3 `IDialogConflictMgr.addToQueue` | 已覆盖 | 批5 `popup_clean` | 挂实现类 `Lcx1`，返回 false（中风险） |
| 4.4 `executeAction` token 过滤 | 有意不做 | — | 实测不读 `TOKEN_*`，只按 message id 分支，无法安全区分 |
| 4.5 `executeAction` / `executeBtnAction` no-op | 有意不做 | — | 会误伤离线地图、淘宝登录、真实消息跳转；源头已断 |
| 4.6 隐私二次弹窗 | 有意不做 | — | 合规必须 |
| 4.7 权限索取 | 有意不做 | — | 影响定位权限流 |
| 5.1 `AlimamaRequestHolder.sendH5Log` | 已覆盖 | 批4 `h5_ad_log` | 2/3 参重载全挂 |
| 5.2 H5 下载插件 / `installApk` | 部分覆盖 | 批1 `h5_install_apk` | 只拦静默安装，不阻断用户主动下载 |
| 5.3 `FloatingLayerManager.a` | 已覆盖 | 批4 `map_float_layer` | 返回空串，不注册 |
| 5.4 开屏视频/扫光挂件 | 已覆盖（间接） | 批2 `splash_gate`/`splash_request` | 开屏不展示，挂件不出现 |
| 5.5 `ImageTextMessageDownloader` 下载任务 | 已覆盖 | 批4 `msg_image_text` | 挂混淆 Runnable `La8` |
| 6.1 `ze5` / `ye5` 预加载 | 部分覆盖 | 批2 `splash_request` | 只挂 `Lze5#attemptRequestData`；`ye5` 未单独挂载（其调用路径经 Lze5 间接收敛） |
| 6.2 `AfcTkManager.registerTkFlag` | 已覆盖 | 批1 `bg_afc_taoke` | 不调用 |
| 6.3 `UtdidUploadTask` | 已覆盖 | 批1 `bg_utdid_upload` | upload/uploadFromCache/reqServer 全断 |
| 6.4 utdid 生成/读取 | 有意不做 | — | 改返回值可能 NPE 或触发风控 |
| 6.5 `UTAnalytics` 上报 | 已覆盖 | 批5 `track_suppress` | 实际总出口 `UTTracker.send/sendLog` |
| 6.6 `track.b.e/f` + `SplashLogManager` | 已覆盖 | 批5 `track_suppress` | 不调用 |
| 6.7 `BootBizDataPreloader` 其它预载 | 有意不做 | — | 只动 canShowSplash，保留首屏预载 |
| 7.1a 开屏摇一摇 | 已覆盖 | 批2 `splash_shake` | getter 数据层 |
| 7.1b `H5ShakePlugin` / `H5SensorPlugin` | 已覆盖 | 批5 `shake_h5` | 只拦 watchShake，保留 vibrate |
| 7.1c `AMapAppStartShakePoint` / `SensorManagerHelper` | 已覆盖 | 批5 `shake_h5` | onInitialized + onSensorChanged 不调用 |
| 7.2a `AwakenRequestHolder` | 已覆盖 | 批1 `bg_awaken_report` | 两个重载 |
| 7.2b `openWxMiniApp` | 有意不做 | — | 仅开屏点击链路，开屏已断 |
| 7.2c `PushAidlService` / `MiPushActivity` | 有意不做 | — | 系统推送组件，不动推送通道 |
| 7.3a 后台消息拉取 | 已覆盖 | 批1+批5 `bg_msg_fetch` | 亮屏触发/后台拉取/主图拉取全断 |
| 7.3b `MessageBoxManager.handlePush` | 已覆盖 | 批5 `bg_msg_fetch` | 不调用，不影响通道注册 |
| 7.3c `PushManager` / `PushBindReceiver` | 有意不做 | — | 小米/华为/荣耀/Heytap/Vivo 推送通道必需，hook 会破坏 token 注册 |
| 7.4 个性化推荐 | 技术不可行 | — | 云控/动态下发，无本地总开关；已从消息/搜索数据入口近似收敛 |
| AJX 金刚区/结果页推广卡 | 技术不可行 | — | 动态包渲染，dex 只有桥接/数据入口 |
| 画布 / WebView 广告 | 技术不可行 | — | 需 AJX/WebView 逆向，Java 层无稳定挂载点 |

## 从源码构建

要求：JDK 21（构建用）、Android SDK Platform 37、Gradle 8.13。
Java 的 source/target 仍是 17（见 `app/build.gradle` 的 `compileOptions`）。

```bash
JAVA_HOME="F:/Program Files/Java/jdk-21" ./gradlew test assembleDebug assembleRelease
bash tools/check-mounts.sh
```

当前基线：单测 **222** 个用例通过（`:app:testDebugUnitTest`，0 失败 / 0 跳过）；`:app:lintDebug` 0 error；`tools/verify-apk.sh` PASS；`tools/check-mounts.sh` 对高德 17.02.0.2010 63/63 OK。

工具链：AGP 8.13.2 + Gradle wrapper 8.13 + JDK 21（构建），Java source/target 17，compileSdk 37 / minSdk 29 / targetSdk 36。
release 产物：`app/build/outputs/apk/release/quietmap-0.8.3.apk`（R8 minify + shrinkResources）。
签名读取仓库根目录 `keystore.properties`；缺少该文件时 release 构建直接失败，不会产出用 debug 证书签名的包。

## GPL 红线

本项目为 MIT。**零参考 MapAdKiller / MapClean 的代码与文档**（其许可与本项目不兼容）：
没有下载、打开或摘抄它们的任何内容。所有 hook 目标签名只来自调研结论与
`dexsql` 对目标 APK 的实测；实测不一致时一律以 dexsql 为准。调试产物也不进入本仓库。

## License

MIT，见 [LICENSE](LICENSE)。
