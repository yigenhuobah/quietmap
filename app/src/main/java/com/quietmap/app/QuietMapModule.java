package com.quietmap.app;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import com.quietmap.app.dexkit.DexKitLocator;
import com.quietmap.app.hooks.Hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Single Java entry class. API 102 only supports hot reload for modules that declare
 * exactly one entry, so this must stay the only class listed in java_init.list.
 *
 * <p>Nothing may be initialised before {@link #onModuleLoaded} runs: the framework attaches
 * itself to this instance after construction, and every API call before that throws.
 */
public class QuietMapModule extends XposedModule {

    static final String TAG = "QuietMap";

    /** 每个进程只安装一次；onPackageReady 对进程内每个已加载包都会回调。 */
    private static final AtomicBoolean sInstalled = new AtomicBoolean();

    /** 单条崩溃文本上限：在回传 MAX_VALUE=256 之内留键名与通道开销余量。 */
    private static final int MAX_CRASH_CHARS = 240;

    /**
     * 最近崩溃环（最新在前，最多 3 条）：UEH 在崩溃线程写入，
     * hooks/Report.snapshot 打平为 crash0/crash1/crash2 键回传。只存进程内
     * 静态数据，目标进程重启后自然清零。
     */
    private static final List<String> CRASHES = new ArrayList<>(3);
    private static final Object CRASH_LOCK = new Object();

    /** UEH 只包一次：install 失败回滚 sInstalled 重试时不得重复包装调用链。 */
    private static volatile boolean sCrashHandlerInstalled;

    /**
     * 进程名只在 onModuleLoaded 的 ModuleLoadedParam 上提供；
     * PackageReadyParam 没有 getProcessName()，所以在这里捕获供 onPackageReady 使用。
     */
    private static volatile String sProcessName = "";

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        sProcessName = param.getProcessName();
        // DexKit 的 libdexkit.so 要从模块自己的 nativeLibraryDir 显式加载
        // （宿主进程的 java.library.path 里没有模块的 so 目录）。
        try {
            ApplicationInfo appInfo = getModuleApplicationInfo();
            if (appInfo != null && appInfo.nativeLibraryDir != null) {
                DexKitLocator.setModuleNativeDir(appInfo.nativeLibraryDir);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "setModuleNativeDir failed", t);
        }
        log(Log.INFO, TAG, "loaded process=" + param.getProcessName()
                + " api=" + getApiVersion()
                + " frameworkProps=0x" + Long.toHexString(getFrameworkProperties()));
    }

    /**
     * Fires once the app classloader exists, which is the earliest point a hook can resolve
     * 高德 classes. Note the framework injects us into every process of the scoped package and
     * fires this callback for <em>every</em> package loaded in that process, so filtering here
     * is mandatory, not defensive.
     */
    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!ReportPaths.TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!sInstalled.compareAndSet(false, true)) {
            log(Log.INFO, TAG, "already installed, skip");
            return;
        }
        // 主进程名就是包名；:locationservice / :widgetProvider 等子进程不是。
        boolean mainProcess = ReportPaths.TARGET_PACKAGE.equals(sProcessName);
        log(Log.INFO, TAG, "package ready: " + param.getPackageName()
                + " first=" + param.isFirstPackage()
                + " process=" + sProcessName
                + " mainProcess=" + mainProcess);
        // 内置诊断（仅主进程）：包装默认 UEH 记录目标进程最近崩溃（宿主内 hook
        // 相关闪退无法归因）。幂等（sCrashHandlerInstalled 只包一次）；previous 先
        // 保存，自定义 handler 全程 try/catch——先记录崩溃再透传 previous（非空时），
        // 绝不因我们抛异常、绝不改变目标 App 原有的异常处理行为。
        if (mainProcess) {
            installCrashHandler();
        }
        // 框架只兜 RuntimeException；HookFailedError 继承 Error，必须自己兜住整段安装。
        try {
            Hooks.installAll(this, param.getClassLoader(), mainProcess);
        } catch (Throwable t) {
            // 安装失败时回滚标记：否则后续 onPackageReady 全部被 "already installed"
            // 挡掉，一次异常会让整个进程永久处于半挂载状态。
            sInstalled.set(false);
            log(Log.ERROR, TAG, "install aborted, will retry on next package ready", t);
        }
    }

    /**
     * 幂等包装默认 UEH：previous 先保存，自定义 handler 内先记录崩溃（见
     * {@link #recordCrash}）再透传 previous（非空时），全程 try/catch——绝不因
     * 我们抛异常、绝不改变目标 App 原有的异常处理行为。sCrashHandlerInstalled
     * 保证只包一次。public static 便于 JVM 单测直接验证幂等与透传语义；生产
     * 路径只有 onPackageReady 的主进程分支调用。
     */
    public static synchronized void installCrashHandler() {
        if (sCrashHandlerInstalled) {
            return;
        }
        sCrashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                recordCrash(thread, throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * 记录一条崩溃：时间（epoch 毫秒）|线程名|异常类名|栈前 2 帧，单条截断
     * ≤ {@link #MAX_CRASH_CHARS}。新的顶掉最旧的；记录本身抛出的任何异常都
     * 不得上抛（由调用方 UEH 的 try/catch 兜底），避免干扰原有处理链。
     */
    public static void recordCrash(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()).append('|');
        sb.append(thread != null ? thread.getName() : "?").append('|');
        if (throwable == null) {
            sb.append('?');
        } else {
            sb.append(throwable.getClass().getName());
            StackTraceElement[] frames = throwable.getStackTrace();
            for (int i = 0; i < 2 && i < frames.length; i++) {
                sb.append('|').append(frames[i]);
            }
        }
        String entry = truncateCrash(sb.toString());
        synchronized (CRASH_LOCK) {
            CRASHES.add(0, entry);
            while (CRASHES.size() > 3) {
                CRASHES.remove(CRASHES.size() - 1);
            }
        }
    }

    /** 第 i 新的崩溃文本（0=最新）；不足返回空串（空串覆盖清掉旧会话残留）。 */
    public static String crashAt(int index) {
        synchronized (CRASH_LOCK) {
            return index >= 0 && index < CRASHES.size() ? CRASHES.get(index) : "";
        }
    }

    private static String truncateCrash(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_CRASH_CHARS ? s : s.substring(0, MAX_CRASH_CHARS);
    }
}
