package com.quietmap.app.hooks;

import android.util.Log;

import com.quietmap.app.Config;
import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.dexkit.DexKitLocator;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.autonavi.minimap）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "QuietMap";

    /** 单个功能的安装器；与 {@link Feature} 一一对应，全部在静态块里显式注册。 */
    @FunctionalInterface
    interface Installer {
        void install(XposedModule module, ClassLoader cl);
    }

    /**
     * 非 blocked 的 Feature → 安装器映射。用 EnumMap 保证 O(1) 且无哈希冲突；
     * 新增 Feature 忘记注册时 installOne 会打 ERROR 并标「未注册安装器」，不会静默漏挂。
     * blocked 的 Feature（AD-03/AD-05，17.02 无可用挂载点）不注册、不安装。
     */
    private static final Map<Feature, Installer> INSTALLERS;

    private static final String[] NO_ANCHORS = new String[0];

    /**
     * blocked 功能（AD-03/AD-05）保留的锚点：运行时不注册 hook，但锚点仍在
     * anchors.json 里供 check-mounts 做版本对比。这里集中引用，既让生成的
     * AnchorKeys 常量有明确归属，也避免被当成死代码误删。
     */
    private static final Map<Feature, String[]> BLOCKED_ANCHORS;

    static {
        EnumMap<Feature, Installer> m = new EnumMap<>(Feature.class);
        m.put(Feature.SPLASH_GATE, SplashGateHooks::install);
        m.put(Feature.SPLASH_REQUEST, SplashRequestHooks::install);
        m.put(Feature.BG_MSG_FETCH, BgMsgFetchHooks::install);
        m.put(Feature.BG_AWAKEN_REPORT, BgAwakenReportHooks::install);
        m.put(Feature.BG_UTDID_UPLOAD, BgUtdidUploadHooks::install);
        m.put(Feature.H5_INSTALL_APK, H5InstallApkHooks::install);
        m.put(Feature.POPUP_UPGRADE, PopupUpgradeHooks::install);
        m.put(Feature.HOME_BANNER, HomeBannerHooks::install);
        m.put(Feature.HOME_OPERATE_BAR, HomeOperateBarHooks::install);
        m.put(Feature.HOME_MSG_FILTER, HomeMsgFilterHooks::install);
        m.put(Feature.SEARCH_HOTWORD, SearchHotwordHooks::install);
        m.put(Feature.H5_AD_LOG, H5AdLogHooks::install);
        m.put(Feature.MAP_FLOAT_LAYER, FloatLayerHooks::install);
        m.put(Feature.MSG_IMAGE_TEXT, MsgImageTextHooks::install);
        m.put(Feature.POPUP_CLEAN, PopupCleanHooks::install);
        m.put(Feature.TRACK_SUPPRESS, TrackSuppressHooks::install);
        m.put(Feature.SHAKE_H5, ShakeH5Hooks::install);
        m.put(Feature.OPS_ACTIVITY, OpsActivityHooks::install);
        m.put(Feature.NOTIFY_GUIDE, NotifyGuideHooks::install);
        m.put(Feature.MSG_CENTER, MsgCenterHooks::install);
        INSTALLERS = Collections.unmodifiableMap(m);

        EnumMap<Feature, String[]> blocked = new EnumMap<>(Feature.class);
        blocked.put(Feature.SPLASH_SHAKE, new String[]{
                AnchorKeys.SPLASH_SHAKE_GUIDE,
                AnchorKeys.SPLASH_SHAKE_TIP,
                AnchorKeys.SPLASH_SHAKE_SPEED_STRICT,
        });
        blocked.put(Feature.BG_AFC_TAOKE, new String[]{
                AnchorKeys.AFC_TK_REGISTER,
        });
        BLOCKED_ANCHORS = Collections.unmodifiableMap(blocked);
    }

    private Hooks() {
    }

    /** 已注册安装器的 Feature 集合；包内可见，供 HooksTest 校验与枚举一一对应。 */
    static java.util.Set<Feature> installerKeys() {
        return INSTALLERS.keySet();
    }

    /** blocked 功能保留的锚点 key；未登记的功能返回空数组。包内可见供测试。 */
    static String[] blockedAnchors(Feature f) {
        String[] retained = BLOCKED_ANCHORS.get(f);
        return retained == null ? NO_ANCHORS : retained;
    }

    /**
     * 安装条件：未 blocked 且用户配置开启。blocked 功能即使配置为开启也不安装，
     * 保留编号与设置行位，但绝不在目标进程落钩。
     */
    static boolean shouldInstall(Feature f, boolean enabled) {
        return !f.blocked() && enabled;
    }

    /**
     * 诊断回传只在目标 App 主进程启动。子进程（:locationservice、:widgetProvider）
     * 仍然安装各自需要的 hook，但只承担后台组件，不再写 reg/skip，避免
     * failedFeatureCount 把“子进程缺少某个 UI 钩子”误判成失败。
     */
    static boolean shouldStartReport(boolean mainProcess) {
        return mainProcess;
    }

    /**
     * 启动诊断回传，任何失败只记日志并返回 false：调用点在逐 Feature 安装之前，
     * 异常若逃逸会让整轮安装中止（所有功能静默不落钩），诊断不可用不能升级成
     * 功能不可用。包内可见，供 HooksTest 注入抛错启动动作验证隔离契约。
     */
    static boolean startReportQuietly(XposedModule module) {
        return startReportQuietly(module, Report::start);
    }

    static boolean startReportQuietly(XposedModule module, Runnable starter) {
        try {
            starter.run();
            return true;
        } catch (Throwable t) {
            try {
                if (module != null) {
                    module.log(Log.WARN, TAG, "report start failed; hook install continues", t);
                }
            } catch (Throwable ignored) {
                // 日志出口自身也不得让安装失败。
            }
            return false;
        }
    }

    /**
     * @param mainProcess 当前是否为主进程（进程名 == 包名）。非主进程（:locationservice、
     *                    :widgetProvider）关闭 DexKit：那里只需要类名/方法名兜底（Wave 1 已把
     *                    类名命中放在 DexKit 之前），不值得付出 native 全 dex 扫描成本。
     *                    非主进程也不启动诊断回传：子进程没有 UI 钩子，上报的 reg/skip
     *                    只会污染 failedFeatureCount。
     */
    public static void installAll(XposedModule module, ClassLoader cl, boolean mainProcess) {
        DexKitLocator.setEnabled(mainProcess);
        if (!mainProcess) {
            module.log(Log.INFO, TAG, "non-main process, dexkit disabled, report disabled");
        }
        Config config;
        try {
            config = Config.load(module);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "config load failed", t);
            return;
        }
        if (!config.anyEnabled()) {
            module.log(Log.INFO, TAG, "all features disabled, nothing to hook");
            return;
        }
        if (shouldStartReport(mainProcess)) {
            // 诊断回传启动失败（调度线程/上下文异常）不得中止本轮安装：
            // Report.start 在 per-feature try 之外，异常逃逸会让后面所有功能都不落钩。
            startReportQuietly(module);
        }
        for (Feature f : Feature.values()) {
            if (!shouldInstall(f, config.enabled(f))) {
                if (f.blocked()) {
                    String[] retained = blockedAnchors(f);
                    module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason
                            + (retained.length == 0 ? ""
                            : " (anchors retained: " + String.join(",", retained) + ")"));
                } else {
                    module.log(Log.INFO, TAG, f.id + " disabled");
                }
                continue;
            }
            installOne(module, cl, f);
        }
    }

    private static void installOne(XposedModule module, ClassLoader cl, Feature f) {
        Installer installer = INSTALLERS.get(f);
        if (installer == null) {
            module.log(Log.ERROR, TAG, f.id + " installer missing");
            HookState.markNotFound(f, "未注册安装器");
            markCriticalIfNoMount(f);
            return;
        }
        installOne(module, cl, installer, f);
    }

    /**
     * 单次安装尝试的统一收口；包内可见，供 HooksTest 用注入的 Installer 在 JVM 上
     * 验证重试契约（清失败标记 → 重装 → markCriticalIfNoMount 重新判定）。
     *
     * 重试契约：QuietMapModule 在安装抛错后回滚 sInstalled，下一轮 onPackageReady
     * 会重跑安装；HookState 的失败标记只增不清，上一轮留下的 skip/crit 若不在
     * 本轮开始前清掉，重试成功的功能会继续上报 crit=1。因此每次尝试都先
     * {@link HookState#clearFailures}，重试成功即恢复干净状态，重试失败会重新记录。
     */
    static void installOne(XposedModule module, ClassLoader cl, Installer installer, Feature f) {
        HookState.clearFailures(f);
        try {
            installer.install(module, cl);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.id + " install threw", t);
            // 安装异常显式 critical：即便零挂载 + 无 skip（异常发生在任何 skip 之前），
            // 整体失效也必须可见，不能静默。
            HookState.markNotFoundCritical(f, "安装异常");
        } finally {
            markCriticalIfNoMount(f);
        }
    }

    /**
     * 安装收尾的统一判定：该 Feature 没有任何成功安装的挂载点、且安装过程中
     * 至少产生过一条 skip，才标记为整体失效（crit=1）。
     *
     * 不直接把 HookKit.findClass 的失败改成 critical：QM 很多类/方法是可选的，
     * 单个 findClass 失败不代表功能不可用；只有“零成功挂载”才是整体失效。
     * 包内可见，供 HooksTest 在 JVM 上直接验证。
     */
    static void markCriticalIfNoMount(Feature f) {
        if (HookState.installedCount(f) == 0 && HookState.skipReason(f) != null) {
            HookState.markNotFoundCritical(f, "无可用挂载点");
        }
    }
}
