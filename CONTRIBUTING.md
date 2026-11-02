# 参与开发

感谢愿意贡献代码。本仓库**只接受源码 PR**：APK、反编译产物、改完的整包一律不收——没法 review，也没法按源码维护。

## 本仓库适配

- 目标应用：高德地图 `com.autonavi.minimap` **17.02.0.2010**
- 模块当前版本：**0.8.6**（versionCode 15）
- 只保证 17.02.0.2010；其他版本 best-effort，功能可能失效，诊断页会提示未适配

## 提交 PR

- 从 `main` 开分支，一个 PR 只做一件事，不夹带无关的格式化或重排
- PR 描述写清三件事：
  1. 目标应用版本（例如 17.02.0.2010）
  2. 涉及的 Feature ID（例如 AD-01）
  3. 怎么验证的：真机截图、logcat、诊断页导出，或新增的单测
- 界面改动附改动前后截图
- 新增功能先开 issue 讨论，避免写完才发现方向不对

## 开发环境

- JDK 21 构建，字节码 source/target 17
- Gradle 8.13（wrapper）、AGP 8.13.2、compileSdk 37、minSdk 29
- compileSdk 37 对应平台包若装成 `android-37.2` 目录，AGP 解析不到，需重命名为 `android-37`
- `local.properties` 写 `sdk.dir=<SDK 路径>`，不入库
- `keystore.properties` 与 `*.jks` 不入库；没有签名也能跑测试和 lint，只有 release 会硬失败

## 构建与检查

```bash
./gradlew :app:testDebugUnitTest :app:jacocoTestReport :app:lintDebug --no-daemon
```

- 新增或修改逻辑必须带纯 JVM 单测；只删断言、改期望值不补测试的 PR 会被退回
- lint 只对新增告警负责，不要求清理历史告警

## 代码约定

- 新功能必须走 `Feature` 枚举 + `strings.xml` + `prefs.xml`，**默认关闭**（AD/UI 组按现有策略）
- Feature ID 用 `AD` / `UI` / `PV` / `EN` / `EX` + 两位编号，递增、不复用；`key` 发布后不可改
- 每个 hook 必须接诊断上报：注册 `reg`、命中 `hit`、跳过 `skip`、关键锚点缺失 `crit`
- 锚点缺失或方法找不到时必须降级，不能让宿主崩溃或白屏
- `chain.proceed()` 每个 hook 恰好调用一次；异常记录后原样抛出，不要吞掉
- 目标应用的类名/方法名走 `HookKit` / 锚点表，不散落硬编码
- 不在主线程做类加载、DexKit 扫描或磁盘 IO

## 适配新版本

1. 用新版本 APK 重新定位锚点，跑 `tools/check-mounts.sh` 体检
2. 静态体检 0 MISSING
3. 真机验证核心功能，附诊断页导出
4. 更新 README 兼容性表与 CHANGELOG

## 不接受

- 绕过付费/会员限制、批量爬取、把用户数据发到第三方
- 没有兜底的硬编码内部类名（版本一升就崩）
- 大范围格式化、重排、改行尾，混在功能 PR 里
- 反编译后原样贴回来的代码（要按本仓库结构重写）

## 发布

签名、`tools/release.sh`、GitHub Release 与 Xposed-Modules-Repo 同步由维护者处理，贡献者不需要管。
