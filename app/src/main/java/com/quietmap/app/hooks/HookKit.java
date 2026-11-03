package com.quietmap.app.hooks;

import android.util.Log;

import androidx.annotation.Nullable;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.dexkit.DexKitLocator;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 约束（与 docs/DEVELOPMENT.md 的稳定性条款一致）：
 * 1) Hooker 一律匿名内部类，不用 lambda（规避 bootclasspath desugar 问题）。
 * 2) 定位/hook 失败只记日志并返回 null/false，绝不向外抛（HookFailedError 继承 Error，框架不兜底）。
 * 3) 每个 intercept 体整段包在 try/catch(Throwable) 内，catch 后记日志并 proceed 放行原逻辑；
 *    任何路径 chain.proceed() 至多一次：抑制类钩子命中路径 0 次（不调原方法），回退路径恰好 1 次。
 * 4) 框架类黑名单：目标类名以 android./java./dalvik./androidx./kotlin. 开头则拒绝 hook。
 * 5) 命中日志固定 "&lt;featureId&gt; hit ..."，tag 固定 QuietMap。
 */
final class HookKit {

    static final String TAG = "QuietMap";

    private HookKit() {
    }

    /** 框架类黑名单：这些类由 bootclasspath / 框架提供，hook 它们会崩目标进程。 */
    static boolean isFrameworkClass(String name) {
        if (name == null) {
            return true;
        }
        return name.startsWith("android.")
                || name.startsWith("java.")
                || name.startsWith("dalvik.")
                || name.startsWith("androidx.")
                || name.startsWith("kotlin.");
    }

    /** module 允许为 null（JVM 单测注入）：日志出口自身绝不能成为功能路径的障碍。 */
    static void info(XposedModule module, String msg) {
        if (module == null) {
            return;
        }
        module.log(Log.INFO, TAG, msg);
    }

    static void warn(XposedModule module, String msg) {
        if (module == null) {
            return;
        }
        module.log(Log.WARN, TAG, msg);
    }

    static void error(XposedModule module, String msg, Throwable t) {
        if (module == null) {
            return;
        }
        module.log(Log.ERROR, TAG, msg, t);
    }

    @Nullable
    static Class<?> findClass(XposedModule module, Feature f, ClassLoader cl, String name) {
        if (isFrameworkClass(name)) {
            warn(module, f.id + " skip: framework class refused " + name);
            HookState.markNotFound(f, "框架类黑名单 " + name);
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            warn(module, f.id + " skip: class not found " + name);
            HookState.markNotFound(f, "类未找到 " + name);
            return null;
        }
    }

    @Nullable
    static Method findMethod(XposedModule module, Feature f, @Nullable Class<?> cls, String name,
                             Class<?>... params) {
        Method m = findMethodQuiet(cls, name, params);
        if (m == null) {
            warn(module, f.id + " skip: method not found "
                    + (cls == null ? "?" : cls.getName()) + "#" + name);
            HookState.markNotFound(f, "方法未找到 " + name);
        }
        return m;
    }

    @Nullable
    static Method findMethodQuiet(@Nullable Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * DexKit 兜底的延迟落钩执行器（单线程守护）。
     *
     * 类名列表 miss 后的锚点扫描要做 native 加载 + 全 dex 扫描（实测秒级）。
     * 它曾在 onPackageReady 的启动关键路径上同步执行：类名一失效，冷启动就被
     * 阻塞数秒，可能 ANR/白屏。这里把「锚点查询 + 落钩」整体挪到后台——
     * 启动关键路径最多付出一次线程提交的成本，miss 场景的代价由后台线程承担。
     */
    private static final ExecutorService DEXKIT_LATE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-dexkit-late");
                t.setDaemon(true);
                return t;
            });

    /** 延迟任务的提交出口；包内可替换，JVM 单测注入同步执行器。 */
    static volatile Executor sDexKitLateExecutor = DEXKIT_LATE_EXECUTOR;

    /** 已提交过延迟兜底的 Feature：installAll 重试时不得重复排队/重复落钩。 */
    private static final Set<Feature> sLateSubmitted =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 把 DexKit 锚点兜底「查询 + 落钩」整体挪到后台：调用方立即返回，不再同步
     * 等待扫描。命中后按 skipVoid 落钩并记 installed；未命中记 skip，并按
     * {@link Hooks#markCriticalIfNoMount} 的同一规则判定整体失效。
     * 幂等：同一 Feature 只提交一次，重复调用忽略。
     *
     * @return true = 已提交后台任务；false = 该 Feature 已提交过，本次忽略
     */
    static boolean installByAnchorLate(XposedModule module, Feature f, String label,
                                       ClassLoader cl, String classHint, String methodName,
                                       Class<?> returnType, Class<?>[] paramTypes,
                                       String[] anchors) {
        if (!sLateSubmitted.add(f)) {
            return false;
        }
        Executor executor = sDexKitLateExecutor;
        if (executor == null) {
            return false;
        }
        executor.execute(() -> finishLateAnchor(module, f, label, cl, classHint,
                methodName, returnType, paramTypes, anchors));
        return true;
    }

    /**
     * 延迟兜底任务体：锚点查询 → 命中落钩 / 未命中记 skip + 判整体失效。
     * 包内可见，供 JVM 单测以 null module 直接驱动（不触 DexKit 时）。
     */
    static void finishLateAnchor(XposedModule module, Feature f, String label,
                                 ClassLoader cl, String classHint, String methodName,
                                 Class<?> returnType, Class<?>[] paramTypes,
                                 String[] anchors) {
        Method m = null;
        try {
            m = DexKitLocator.findMethod(cl, f, classHint, methodName, returnType,
                    paramTypes, anchors);
        } catch (Throwable t) {
            error(module, f.id + " late dexkit anchor error " + label, t);
        }
        if (m == null) {
            warn(module, f.id + " skip: " + label + " dexkit anchor miss (late)");
            HookState.markNotFound(f, label + " 锚定失败");
            Hooks.markCriticalIfNoMount(f);
            return;
        }
        info(module, f.id + " " + label + " located via dexkit anchor (late): "
                + m.getDeclaringClass().getName());
        install(module, f, label, m, skipVoid(module, f, label));
    }

    static boolean install(XposedModule module, Feature f, String label, @Nullable Method m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            // 与 findClass/findMethod 的失败分支保持一致：记日志并写 skip。
            // 否则「零挂载 + 无 skip」的功能（null Method 且此前无任何失败记录）
            // 无法被 Hooks.markCriticalIfNoMount 判为整体失效，形成静默失效。
            warn(module, f.id + " skip: null method " + label);
            HookState.markNotFound(f, nullMethodSkipReason(label));
            return false;
        }
        if (isFrameworkClass(m.getDeclaringClass().getName())) {
            warn(module, f.id + " hook failed " + label + ": framework class refused");
            HookState.markNotFound(f, "框架类黑名单 " + m.getDeclaringClass().getName());
            return false;
        }
        try {
            module.hook(m)
                    .setId(f.id + "#" + label)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            HookState.installed(f, label);
            info(module, f.id + " registered " + label);
            return true;
        } catch (Throwable t) {
            error(module, f.id + " hook failed " + label, t);
            HookState.markNotFound(f, "hook 失败 " + label);
            return false;
        }
    }

    /** {@link #install} 收到 null Method 时写入 HookState 的 skip 原因；包内可见供 JVM 单测断言。 */
    static String nullMethodSkipReason(String label) {
        return "方法未找到 " + label;
    }

    /**
     * 命中计数并采样打日志。
     *
     * 参数 logLine 是**已预拼接好的完整日志行**（形如 "&lt;featureId&gt; hit ..."），
     * 由各工厂在构造 Hooker 时拼好，避免每次命中重复拼串。
     * 日志采样：仅前 5 次命中与每第 100 次命中真正写 logcat，计数每次照常累加。
     */
    static void hit(XposedModule module, Feature f, String logLine) {
        long count = HookState.hit(f);
        if (count <= 5 || count % 100 == 0) {
            module.log(Log.INFO, TAG, logLine);
        }
    }

    /**
     * void 方法：不 proceed 即原方法体不执行。
     * 回退路径（catch）proceed 恰好一次，把原逻辑放行，fail-open。
     */
    static XposedInterface.Hooker skipVoid(final XposedModule module, final Feature f,
                                           final String detail) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    hit(module, f, logLine);
                    return null;
                } catch (Throwable t) {
                    error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 布尔方法：直接给常量 false，不 proceed。
     * 回退路径（catch）proceed 恰好一次放行原逻辑，fail-open。
     */
    static XposedInterface.Hooker constBool(final XposedModule module, final Feature f,
                                            final String detail, final boolean value) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    hit(module, f, logLine);
                    return value ? Boolean.TRUE : Boolean.FALSE;
                } catch (Throwable t) {
                    error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 常量方法：直接返回给定装箱常量，不 proceed。
     * 用于对象/字符串返回类型（如 java.lang.Boolean、String）——
     * 常量必须非 null：调用方可能拆箱或直接解引用。
     * 回退路径（catch）proceed 恰好一次放行原逻辑，fail-open。
     */
    static XposedInterface.Hooker constValue(final XposedModule module, final Feature f,
                                             final String detail, final Object value) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    hit(module, f, logLine);
                    return value;
                } catch (Throwable t) {
                    error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }
}
