package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * popup_upgrade 升级弹窗断源。
 *
 * 挂载点迁移修正（AppUpgradeController#q → AppUpgradeController$j#run）：
 * dexsql 反编译 17.02.0.2010 实测 q(Lcom/amap/bundle/appupgrade/e;ZZ)Z 是
 * 「校验已下载 APK + post 升级对话框」的总入口（先 AppUpgradeController.n()/
 * File.exists() 校验，再 new AppUpgradeController$j 并 UiExecutor.post），返回
 * false 会连「已下载更新的校验与安装引导」一起跳过，不只是关闭弹窗；而
 * $j.run() 只是弹出升级/安装对话框（e.x 非空走 IUpdateRichTextDialog，否则走
 * AlertView），真正安装在其确定键 $j$a.onClick 之后。故改挂 $j#run()V——
 * 校验逻辑原样执行，只让对话框不再出现，语义与「升级弹窗断源」精确对齐。
 *
 * $j 是非混淆外层类 AppUpgradeController 下的混淆内部类，类名可能随版本
 * 重排：找不到时记 skip 并由 installOne 的 markCriticalIfNoMount 判整体失效，
 * 不回退到旧的 q 挂载点（那会重新引入过度拦截）。
 * dexsql 实测（高德 17.01.0.2012）旧挂载点签名：q(Lcom/amap/bundle/appupgrade/e;ZZ)Z。
 */
final class PopupUpgradeHooks {

    private static final String CLAZZ = "com.amap.bundle.appupgrade.AppUpgradeController$j";
    private static final String DIALOG_METHOD = "run";

    private PopupUpgradeHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.POPUP_UPGRADE;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            if (cls == null) {
                return;
            }
            Method run = HookKit.findMethod(module, f, cls, DIALOG_METHOD);
            if (run != null && !isDialogRun(run)) {
                HookKit.warn(module, f.id + " skip: " + cls.getName()
                        + "#" + DIALOG_METHOD + " failed validation (void()/Runnable),"
                        + " not hooking");
                HookState.markNotFound(f, "AppUpgradeController$j#run 校验未过");
                return;
            }
            HookKit.install(module, f, "AppUpgradeController$j#run", run,
                    HookKit.skipVoid(module, f, "AppUpgradeController$j#run"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * 命中校验：run() 必须是 void 无参且声明类是 Runnable（对话框任务是
     * UiExecutor.post 提交的 Runnable）。与 MsgImageTextHooks#isDownloaderRun
     * 同一策略：短名/通用名不可信，签名与类型不过就不挂。
     */
    static boolean isDialogRun(Method m) {
        if (m == null || m.getReturnType() != void.class || m.getParameterTypes().length != 0) {
            return false;
        }
        Class<?> cls = m.getDeclaringClass();
        return !HookKit.isFrameworkClass(cls.getName())
                && Runnable.class.isAssignableFrom(cls);
    }
}
