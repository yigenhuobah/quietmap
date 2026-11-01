package com.quietmap.app.hooks;

import android.content.Intent;
import android.net.Uri;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.dexkit.DexKitLocator;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * h5_install_apk H5/推广静默下载安装 APK 断源（PV-03 重定位，0.8.4）。
 *
 * dexsql 复核（高德 17.02.0.2010，cache 768d62ee138771c0，一律以实测为准）：
 * - 旧挂载点 com.alipay.mobile.common.info.DeviceInfo#installApk 全 dex 0 调用点
 *   （method_calls 空结果，死代码），无法复活。
 * - 推广下载安装真实链，三条入口同一漏斗：IntentController#d 组装
 *   IIntentInterceptor 链 → 混淆分发器 Lec0;#dispatch(Intent)Z
 *   （日志 tag "AppUpgradeIntentDispatcher"）：
 *     ① action 以 "appDownloadfail" 开头 → 读 SplashApp* prefs 重试（pc0.a(2,..)）
 *     ② extras appDownloadUrl + appDownloadName 均非空 → pc0.a(3,..)
 *     ③ data host=openFeature 且 query featureName=DownloadApp → 读
 *        AppName/PkgName/DownloadURL 参数 → pc0.a(3,2,..)
 *   → Lpc0;#b（WiFi 下不经确认直接开始下载；非 WiFi 弹 mc0 确认框，
 *   mc0 唯一构造点是 pc0.a）→ 下载完成回调 pc0$a → pc0$a$a.run →
 *   Laa0.a(Activity,File) 拉起系统安装器。
 * - 官方升级链与本分发器的官方分支完全独立：
 *   action.autonavi.checkappupdate → AppUpgradeController#g()（dispatch 内原样放行）；
 *   应用内升级 q(e,ZZ)（全 dex 唯一 new AppUpgradeController$j 的方法）→ $j.run
 *   （UI-01 挂载点）→ 用户确认 → r(File)/b → Laa0.a。
 *   method_calls 实证：Laa0.a 仅有 r(File) 与 pc0$a$a.run 两个调用方（双链共用，
 *   本身不可作挂载点）；AppUpgradeController 及其内部类从不调用 pc0/ec0/mc0；
 *   pc0.a 唯一调用方是 ec0.dispatch。
 * - 结论：ec0.dispatch 是「推广 vs 官方」按 intent 内容分流的第一站，只吞三个
 *   推广分支即可断源（下载与安装都不再发生），官方分支与升级安装链不受影响。
 *
 * 语义（保守 skip）：命中推广分支 → 不调用原方法、返回 Boolean.TRUE（与原方法
 * 处理路径的返回值一致，IntentController 据此停止继续分派）；其余 intent（含
 * checkappupdate 与空 action）→ proceed 原方法，原行为逐字节保留。整段
 * try/catch(Throwable)，异常回退路径 proceed 恰好一次（fail-open），任何路径
 * proceed 至多一次。
 *
 * 定位策略与 MsgImageTextHooks 一致：类名 ["ec0"] 优先（毫秒级直取，命中需过
 * isDispatcherDispatch 校验：boolean(Intent) 签名 + IIntentInterceptor 类型
 * （dexsql class_interfaces 实测）+ 非框架类）；miss/校验不过后的 DexKit 锚点扫描
 * 转后台延迟执行——锚点取 dexsql method_strings 实测的
 * "action.autonavi.checkappupdate" + "Dispatch App Upgrade" + "appDownloadfail"
 * （三条在本 APK 内交集唯一命中 Lec0#dispatch），boolean(Intent) 签名强校验与
 * 唯一性拒绝猜测由 DexKitLocator 完成。不复用 HookKit#installByAnchorLate：
 * 它固定 skipVoid Hooker，而 dispatch 返回 boolean（skipVoid 返回 null 会破坏
 * 调用方的 boolean 契约），故在本地用 HookKit#sDexKitLateExecutor + 幂等标记
 * 自管延迟兜底。两路都失败记 skip，fail-open。
 */
final class H5InstallApkHooks {

    private static final String DISPATCHER_CLASS = "ec0";
    private static final String DISPATCH_METHOD = "dispatch";
    private static final String DISPATCH_LABEL = "Lec0#dispatch";
    /** dexsql class_interfaces 实测：Lec0 实现该接口（IntentController 链的强类型身份）。 */
    private static final String INTENT_INTERCEPTOR =
            "com.autonavi.minimap.intent.IIntentInterceptor";

    /** 官方升级检查 action：dispatch 的官方分支，永远放行。 */
    private static final String ACTION_CHECK_UPDATE = "action.autonavi.checkappupdate";
    /** 推广下载失败重试 action 前缀（appDownloadfail0/1/2）。 */
    private static final String ACTION_DOWNLOAD_FAIL_PREFIX = "appDownloadfail";
    private static final String EXTRA_URL = "appDownloadUrl";
    private static final String EXTRA_NAME = "appDownloadName";
    private static final String URI_HOST_OPEN_FEATURE = "openFeature";
    private static final String URI_PARAM_FEATURE = "featureName";
    private static final String URI_FEATURE_DOWNLOAD_APP = "DownloadApp";

    /**
     * DexKit 兜底锚点（dexsql method_strings 实测；三条交集全 APK 唯一命中
     * Lec0#dispatch，与 check-mounts.sh 的锚点监控保持一致）。
     */
    private static final String[] DISPATCH_ANCHORS = {
            ACTION_CHECK_UPDATE, "Dispatch App Upgrade", ACTION_DOWNLOAD_FAIL_PREFIX,
    };

    /** 延迟兜底只提交一次：installAll 重跑时不得重复排队/重复落钩。 */
    private static final AtomicBoolean sLateSubmitted = new AtomicBoolean(false);

    private H5InstallApkHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.H5_INSTALL_APK;
        try {
            installDispatchGate(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * 推广下载分发门控：类名列表优先（check-mounts 体检出新名后追加），命中需过
     * {@link #isDispatcherDispatch} 校验，同步落钩；miss/校验不过后的 DexKit 锚点
     * 扫描转后台延迟执行，本方法在启动关键路径上不等待扫描结果。
     */
    private static void installDispatchGate(XposedModule module, Feature f, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(DISPATCHER_CLASS, false, cl);
            Method m = HookKit.findMethodQuiet(cls, DISPATCH_METHOD, Intent.class);
            if (m != null && isDispatcherDispatch(m, loadInterceptorInterface(cl))) {
                HookKit.info(module, f.id
                        + " promo-download dispatcher located via class-name list: "
                        + DISPATCHER_CLASS);
                HookKit.install(module, f, DISPATCH_LABEL, m, promoGate(module, f, DISPATCH_LABEL));
                return;
            }
            if (m != null) {
                // 短名被复用成别的类：不吞无关 intent，降级到锚点串定位。
                HookKit.warn(module, f.id + " class-name hit " + DISPATCHER_CLASS
                        + "#" + DISPATCH_METHOD
                        + " failed validation (boolean(Intent)/IIntentInterceptor/non-framework),"
                        + " dexkit anchor deferred to background");
            }
        } catch (Throwable ignored) {
            // 类名 miss：走 DexKit 锚点兜底。
        }
        HookKit.warn(module, f.id + " class-name list miss, dexkit anchor deferred to background");
        submitLateAnchor(module, f, cl);
    }

    /**
     * 延迟兜底任务提交（与 HookKit#installByAnchorLate 同形的幂等包装）。
     * 同一 Feature 只提交一次；执行器被 JVM 单测置 null 时放弃兜底。
     */
    private static void submitLateAnchor(XposedModule module, Feature f, ClassLoader cl) {
        if (!sLateSubmitted.compareAndSet(false, true)) {
            return;
        }
        Executor executor = HookKit.sDexKitLateExecutor;
        if (executor == null) {
            return;
        }
        executor.execute(() -> finishLateAnchor(module, f, cl));
    }

    /** 延迟兜底任务体：锚点查询 → 形状校验 → 命中落钩 / 未命中记 skip + 判整体失效。 */
    private static void finishLateAnchor(XposedModule module, Feature f, ClassLoader cl) {
        Method m = null;
        try {
            m = DexKitLocator.findMethod(cl, null, DISPATCH_METHOD, boolean.class,
                    new Class<?>[]{Intent.class}, DISPATCH_ANCHORS);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " late dexkit anchor error " + DISPATCH_LABEL, t);
        }
        if (m == null || !isDispatchShape(m)) {
            HookKit.warn(module, f.id + " skip: " + DISPATCH_LABEL + " dexkit anchor miss (late)");
            HookState.markNotFound(f, DISPATCH_LABEL + " 锚定失败");
            Hooks.markCriticalIfNoMount(f);
            return;
        }
        HookKit.info(module, f.id + " " + DISPATCH_LABEL
                + " located via dexkit anchor (late): " + m.getDeclaringClass().getName());
        HookKit.install(module, f, DISPATCH_LABEL, m, promoGate(module, f, DISPATCH_LABEL));
    }

    /**
     * 推广分支门控 Hooker：命中推广分支返回 TRUE 吞掉（拦截器链停止，与原方法
     * 处理路径返回值一致），其余 proceed 原方法。命中路径 proceed 0 次，
     * 放行/回退路径恰好 1 次，杜绝双重执行。
     */
    private static XposedInterface.Hooker promoGate(final XposedModule module,
                                                    final Feature f,
                                                    final String label) {
        final String logLine = f.id + " hit " + label + " promo-download intent consumed";
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof Intent && isPromoDownloadIntent((Intent) arg)) {
                        HookKit.hit(module, f, logLine);
                        return Boolean.TRUE;
                    }
                    proceeded = true;
                    return chain.proceed();
                } catch (Throwable t) {
                    if (proceeded) {
                        // 原方法已执行过一次，不能再次 proceed。
                        throw t;
                    }
                    HookKit.error(module, f.id + " intercept error " + label, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 从 intent 提取原始判定素材后交给 {@link #isPromoDownloadBranch}（纯函数，
     * 供 JVM 单测）。素材缺失时按原分发器分支语义处理：
     * 空 action → 原方法直接 return false（拦截器链继续分派），不吞；
     * checkappupdate → 官方分支，永远放行。
     */
    static boolean isPromoDownloadIntent(Intent intent) {
        String action = intent.getAction();
        String url = intent.getStringExtra(EXTRA_URL);
        String name = intent.getStringExtra(EXTRA_NAME);
        String host = null;
        String feature = null;
        Uri data = intent.getData();
        if (data != null) {
            host = data.getHost();
            feature = data.getQueryParameter(URI_PARAM_FEATURE);
        }
        return isPromoDownloadBranch(action, url, name, host, feature);
    }

    /**
     * 推广分支判定（与 Lec0#dispatch smali 分支同构的纯函数，供 JVM 单测）：
     * ① action 以 appDownloadfail 开头（失败重试）；
     * ② appDownloadUrl + appDownloadName 双非空（extras 入口）；
     * ③ host=openFeature（忽略大小写）且 featureName=DownloadApp（忽略大小写，
     *    与原 TextUtils/equalsIgnoreCase 同构）。
     * 官方 checkappupdate 分支与空 action 永远返回 false（放行/不吞），
     * 且 checkappupdate 的优先级高于 ②③——与原方法 label_0070 的先后顺序一致，
     * 避免官方 action 附带推广 extras 时被误吞。
     */
    static boolean isPromoDownloadBranch(String action, String urlExtra, String nameExtra,
                                         String uriHost, String featureParam) {
        if (action == null || action.isEmpty() || ACTION_CHECK_UPDATE.equals(action)) {
            return false;
        }
        if (action.startsWith(ACTION_DOWNLOAD_FAIL_PREFIX)) {
            return true;
        }
        if (nonEmpty(urlExtra) && nonEmpty(nameExtra)) {
            return true;
        }
        return URI_HOST_OPEN_FEATURE.equalsIgnoreCase(uriHost)
                && URI_FEATURE_DOWNLOAD_APP.equalsIgnoreCase(featureParam);
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static Class<?> loadInterceptorInterface(ClassLoader cl) {
        try {
            return Class.forName(INTENT_INTERCEPTOR, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * dispatch 形状校验：boolean dispatch(Intent) 且非框架类。
     * 短名/通用方法名不可信，签名不过就拒收（与 MsgImageTextHooks#isDownloaderRun
     * 同一策略）。
     */
    static boolean isDispatchShape(Method m) {
        if (m == null
                || m.getReturnType() != boolean.class
                || m.getParameterTypes().length != 1
                || m.getParameterTypes()[0] != Intent.class) {
            return false;
        }
        return !HookKit.isFrameworkClass(m.getDeclaringClass().getName());
    }

    /**
     * 快路径命中校验：形状 + IIntentInterceptor 类型。接口加载失败视为校验不过，
     * 降级 DexKit 锚点定位（宁可 miss 也不把同名无关类吞成 hit）。
     */
    static boolean isDispatcherDispatch(Method m, Class<?> interceptorInterface) {
        if (!isDispatchShape(m) || interceptorInterface == null) {
            return false;
        }
        return interceptorInterface.isAssignableFrom(m.getDeclaringClass());
    }
}
