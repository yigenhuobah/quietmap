package com.quietmap.app.hooks;

import android.util.Log;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 批 6 真机验证用临时探针（默认关闭）。
 *
 * 遍历消息中心三个候选类的全部 declared methods，命中即打
 * "PROBE hit &lt;类简名&gt;#&lt;方法名&gt;"，用于真机确认消息中心实际走的类/方法。
 *
 * release/日常构建不安装：DEBUG_PROBE 为编译期常量 false，install 直接返回，
 * javac 会把整个探针分支优化掉。View 构造器探针已移除，不再进入 release 路径。
 * 后续真机验证时把 DEBUG_PROBE 临时改为 true 并重新构建：
 * - 方法探针会跳过 {@link #ALREADY_HOOKED} 中的正式挂载点，因为 Xposed
 *   同一方法只能挂一个 hook；
 * - 探针只打 PROBE 日志，绝不写 HookState（installed/markNotFound/skip）：
 *   成功标签记在正式 Feature 名下会掩盖真实的零挂载 critical，类缺失写 skip
 *   会在诊断页留下假 skip；
 * - 定位完必须改回 false 再提交/发版，不要留在日常构建里。
 */
final class Batch6Probe {

    private static final boolean DEBUG_PROBE = false;

    private static final String TAG = "QuietMap";

    private static final String[] TARGETS = {
            "com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager",
            // anchor-allowlist: DEBUG_PROBE=false 的临时真机探针，不参与正式挂载；该候选类无锚点条目
            "com.autonavi.minimap.bundle.msgbox.impl.MsgboxService",
            // anchor-allowlist: DEBUG_PROBE=false 的临时真机探针，不参与正式挂载；该候选类无锚点条目
            "com.autonavi.bundle.msgbox.ajx.ModuleMessageBox"
    };

    /**
     * 正式钩子已经占用的方法：Xposed 同一方法只能挂一个 hook，
     * 探针必须跳过，否则 DEBUG_PROBE=true 时这些方法只会记录 hook 失败。
     * 按「类简名#方法名」匹配，覆盖所有重载。
     */
    private static final Set<String> ALREADY_HOOKED = new HashSet<>(Arrays.asList(
            "MessageBoxManager#getMessages",
            "MessageBoxManager#retrieveRemoteMessages",
            "MessageBoxManager#getAllLocalMessages",
            "MessageBoxManager#getMessageInBackground",
            "MessageBoxManager#handlePush",
            "MessageBoxManager#fetchMessageFromMainMap",
            "MsgboxService#getNewComingUnRead_MsgNumFromDB",
            "ModuleMessageBox#hasNewMessage"
    ));

    private Batch6Probe() {
    }

    static void install(XposedModule module, Feature f, ClassLoader cl) {
        if (DEBUG_PROBE) {
            // f 仅由 MsgCenterHooks.install 传入（签名保持不变）；探针绝不把它写进
            // HookState，见 installMethodProbes 的说明。
            installMethodProbes(module, cl);
            probeLog("PROBE installed for " + Arrays.toString(TARGETS));
        }
    }

    /**
     * 消息中心三个候选类的全部方法：命中打日志后原样 proceed。
     *
     * 探针只做 logcat 观察，绝不写 HookState（installed/markNotFound/skip）：
     * 探针成功若经 HookKit.install 记成 HookState.installed，会让 MSG_CENTER 的
     * installedCount &gt; 0，掩盖真实的「零挂载」critical；探针类缺失若经
     * HookKit.findClass 写成 skip，又会在诊断页留下假的「类未找到」。因此
     * 定位与挂载都用下面的 probeFindClass / probeInstall 本地实现。
     */
    private static void installMethodProbes(XposedModule module, ClassLoader cl) {
        for (String name : TARGETS) {
            Class<?> cls = probeFindClass(cl, name);
            if (cls == null) {
                continue;
            }
            for (Method m : cls.getDeclaredMethods()) {
                try {
                    if (m.getName().startsWith("PROBE")) {
                        continue;
                    }
                    String label = cls.getSimpleName() + "#" + m.getName();
                    if (ALREADY_HOOKED.contains(label)) {
                        probeLog("PROBE skip already hooked " + label);
                        continue;
                    }
                    probeInstall(module, label, m);
                } catch (Throwable ignored) {
                    // 单个方法探针失败不影响其余方法
                }
            }
        }
    }

    /** 探针专用类定位：缺失只打 PROBE 日志，不写 HookState（与 HookKit.findClass 的差异点）。 */
    static Class<?> probeFindClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            probeWarn("PROBE skip class not found " + name);
            return null;
        }
    }

    /** 探针专用挂载：成功不记 HookState.installed，失败不记 markNotFound，只打 PROBE 日志。 */
    private static void probeInstall(XposedModule module, String label, Method m) {
        if (HookKit.isFrameworkClass(m.getDeclaringClass().getName())) {
            probeWarn("PROBE skip framework class " + label);
            return;
        }
        try {
            module.hook(m)
                    .setId("PROBE#" + label)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(methodProbe(label));
            probeLog("PROBE registered " + label);
        } catch (Throwable t) {
            probeWarn("PROBE hook failed " + label + ": " + t.getMessage());
        }
    }

    private static void probeLog(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
            // 单测环境没有 Android Log 实现；日志失败不影响探针
        }
    }

    private static void probeWarn(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
            // 单测环境没有 Android Log 实现；日志失败不影响探针
        }
    }

    private static XposedInterface.Hooker methodProbe(final String label) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Log.i(TAG, "PROBE hit " + label);
                } catch (Throwable ignored) {
                    // 日志失败不影响原方法
                }
                return chain.proceed();
            }
        };
    }

}
