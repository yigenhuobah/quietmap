package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * ops_activity 运营活动弹窗净化。
 *
 * 三个挂载点的完整签名由 anchors.json 提供：
 *
 * 1) ops_module_switch —— AppInitServiceImpl#getOperationModuleSwitch(Z)Z
 *    运营活动模块总开关。返回常量 false，模块不启用，打车页 88VIP 等运营弹窗
 *    不再加载。接口 IAppInitService 上也有同名方法，只挂实现类（锚点表 c 指定）。
 *    全 dex 唯一静态调用方是 ActivitiesService#requestOperationsActivities。
 *
 * 2) ops_activity_open_3p / 3) ops_activity_open_4p —— ActivitiesService 的活动
 *    打开入口，3 参与 4 参（末尾 boolean）两个重载都挂。命中时不调用原方法
 *    （skipVoid），活动页不再打开；异常回退 proceed 恰好一次（fail-open）。
 *    3 参静态调用方：JS 桥、AJX 请求回调、步行/骑行结束页及其 presenter 的
 *    callback；4 参只被 3 参转发壳调用。
 *
 * 只动业务层入口，不 hook 框架类。会连带屏蔽导航结束页的加油/停车活动，
 * 因此设置页可单独关闭本项。
 *
 * 真机诊断日志（tag QuietMap，同一 key 10 秒最多一条）：
 * - ops activity module switch invoked
 * - ops activity module switch=false
 * - ops activity open 3p invoked / ops activity open skipped 3p
 * - ops activity open 4p invoked / ops activity open skipped 4p
 *
 * 入口计数写 HookState.entry，命中计数仍走 HookKit.hit；诊断失败不影响返回。
 */
final class OpsActivityHooks {

    /** 同一 key 的观测日志最小间隔。 */
    static final long LOG_WINDOW_MS = 10_000L;

    private static final LogThrottle THROTTLE = new LogThrottle();

    private OpsActivityHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.OPS_ACTIVITY;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            installModuleSwitch(module, f, anchors);
            installOpenActivities(module, f, anchors);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 运营活动模块总开关：固定返回 false。 */
    private static void installModuleSwitch(XposedModule module, Feature f,
                                            AnchorTable anchors) {
        Method switchMethod = anchors.method(AnchorKeys.OPS_MODULE_SWITCH);
        HookKit.install(module, f, AnchorKeys.OPS_MODULE_SWITCH, switchMethod,
                moduleSwitch(module, f));
    }

    /** 活动打开入口：3 参与 4 参重载都拦截，不执行原方法。 */
    private static void installOpenActivities(XposedModule module, Feature f,
                                              AnchorTable anchors) {
        Method open3 = anchors.method(AnchorKeys.OPS_ACTIVITY_OPEN_3P);
        HookKit.install(module, f, AnchorKeys.OPS_ACTIVITY_OPEN_3P, open3,
                skipOpenLogged(module, f, "3p", AnchorKeys.OPS_ACTIVITY_OPEN_3P));

        Method open4 = anchors.method(AnchorKeys.OPS_ACTIVITY_OPEN_4P);
        HookKit.install(module, f, AnchorKeys.OPS_ACTIVITY_OPEN_4P, open4,
                skipOpenLogged(module, f, "4p", AnchorKeys.OPS_ACTIVITY_OPEN_4P));
    }

    /**
     * 模块开关 Hooker：返回 false 且不 proceed；异常时 proceed 恰好一次。
     * 与 HookKit.constBool 行为一致，额外记录入口计数与限流日志。
     */
    private static XposedInterface.Hooker moduleSwitch(final XposedModule module,
                                                       final Feature f) {
        final String hitLine = f.id + " hit " + AnchorKeys.OPS_MODULE_SWITCH;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    noteEntry(module, f, "module switch");
                    logThrottled(module, "module switch=false",
                            "ops activity module switch=false");
                    hitQuiet(module, f, hitLine);
                    return Boolean.FALSE;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error "
                            + AnchorKeys.OPS_MODULE_SWITCH, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 活动打开 Hooker：不 proceed；异常时 proceed 恰好一次。
     * 与 HookKit.skipVoid 行为一致，额外记录入口计数与限流日志。
     */
    private static XposedInterface.Hooker skipOpenLogged(final XposedModule module,
                                                         final Feature f,
                                                         final String shortLabel,
                                                         final String detail) {
        final String hitLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    noteEntry(module, f, "open " + shortLabel);
                    logThrottled(module, "open skipped:" + shortLabel,
                            "ops activity open skipped " + shortLabel);
                    hitQuiet(module, f, hitLine);
                    return null;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /** 记录入口计数与 invoked 日志；任何异常都吞掉，绝不影响挂载点行为。 */
    private static void noteEntry(XposedModule module, Feature f, String label) {
        try {
            HookState.entry(f);
        } catch (Throwable ignored) {
            // 诊断计数失败不影响 hook
        }
        logThrottled(module, "invoked:" + label, "ops activity " + label + " invoked");
    }

    /** 命中计数与采样日志；诊断失败不影响返回值。 */
    private static void hitQuiet(XposedModule module, Feature f, String logLine) {
        try {
            HookKit.hit(module, f, logLine);
        } catch (Throwable ignored) {
            // 计数/日志失败不影响 hook
        }
    }

    /** 限流写日志；日志失败不影响 hook。 */
    private static void logThrottled(XposedModule module, String key, String line) {
        try {
            if (THROTTLE.allow(key, System.currentTimeMillis())) {
                HookKit.info(module, line);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响 hook
        }
    }

    /**
     * 纯 JVM 可测的固定窗口限流器：同一 key 在 LOG_WINDOW_MS 内只允许一次。
     * 时钟回拨（now < previous）时允许一次并重置窗口，避免永久静默。
     */
    static final class LogThrottle {

        private final ConcurrentMap<String, AtomicLong> last = new ConcurrentHashMap<>();

        boolean allow(String key, long nowMs) {
            AtomicLong stamp = last.get(key);
            if (stamp == null) {
                AtomicLong created = new AtomicLong(Long.MIN_VALUE);
                AtomicLong previous = last.putIfAbsent(key, created);
                stamp = previous != null ? previous : created;
            }
            while (true) {
                long previous = stamp.get();
                if (previous != Long.MIN_VALUE
                        && nowMs >= previous
                        && nowMs - previous < LOG_WINDOW_MS) {
                    return false;
                }
                if (stamp.compareAndSet(previous, nowMs)) {
                    return true;
                }
            }
        }
    }
}
