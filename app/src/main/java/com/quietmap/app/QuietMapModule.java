package com.quietmap.app;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import com.quietmap.app.dexkit.DexKitLocator;
import com.quietmap.app.hooks.Hooks;

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
}
