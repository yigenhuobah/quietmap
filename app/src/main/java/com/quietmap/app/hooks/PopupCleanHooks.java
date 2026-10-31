package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * popup_clean 弹窗净化（升级提示配置 + 首页弹窗冲突队列）。
 *
 * dexsql 实测（高德 17.02.0.2010；17.01.0.2012 挂载点同构，仅实现类混淆名
 * Lcx1; → Lry1;）：
 *
 * 1) UpdateHintConfigDownloader#a()V —— 升级提示配置下载（方法体含
 *    "update_hint_apk_newest_version" 等 prefs 键），不调用即不下载/刷新
 *    升级提示配置。纯配置下载，无 UI 副作用。
 *
 * 2) IDialogConflictMgr#addToQueue(DialogConflictInfo)Z —— 接口方法，
 *    实现类是**混淆类 Lry1;**（dexsql class_interfaces 反查：唯一实现，
 *    17.01.0.2012 为 Lcx1;）。接口层 hook 无效
 *    （ART 走 itable 分派），必须挂 Lry1#addToQueue。
 *    返回 false = 不入队、不展示。
 *
 * 中风险说明（实测调用点，务必保留开关回退）：
 * - addToQueue 的调用方不全是营销弹窗：GuideVideoViewController（功能引导视频）、
 *   SharedPassphraseManager（分享口令）、ModuleAMapHome#presentModalPage（通用
 *   模态页）、首页 bizunit 等都会走这个队列。返回 false 会一并吞掉这些弹窗，
 *   包括可能重要的业务弹窗（登录失效等）。
 * - 因此本项默认开启但必须可单独关闭；关闭 popup_clean 后队列行为完全恢复。
 * - 本批**不 hook** MessageBoxManager#executeAction/executeBtnAction：
 *   dexsql 实测 executeAction 只按 message id（appUpdate / downloadSeatMap /
 *   taobaoLogin）分支，完全不读 AmapMessage.TOKEN_* 常量；executeBtnAction
 *   是通用 scheme 打开器。整段拦截会误伤离线地图下载、淘宝登录、真实消息
 *   跳转等系统/业务动作，无法安全区分营销与系统动作。营销消息的源头已由
 *   批 3 的 home_msg_filter（getMessages/retrieveRemoteMessages 断源）处理，
 *   故这里宁可不 hook，详见 docs/DEVELOPMENT.md 批 5 取舍说明。
 */
final class PopupCleanHooks {

    private static final String UPDATE_HINT =
            "com.amap.bundle.appupgrade.config.downloader.UpdateHintConfigDownloader";

    /** IDialogConflictMgr 的唯一实现类（dexsql class_interfaces 实测，混淆名；旧版 Lcx1;）。 */
    private static final String DIALOG_CONFLICT_IMPL = "ry1";
    private static final String DIALOG_CONFLICT_INFO =
            "com.autonavi.bundle.amaphome.model.DialogConflictInfo";

    private PopupCleanHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.POPUP_CLEAN;
        try {
            Class<?> hint = HookKit.findClass(module, f, cl, UPDATE_HINT);
            if (hint != null) {
                Method a = HookKit.findMethod(module, f, hint, "a");
                HookKit.install(module, f, "UpdateHintConfigDownloader#a", a,
                        HookKit.skipVoid(module, f, "UpdateHintConfigDownloader#a"));
            }

            Class<?> impl = HookKit.findClass(module, f, cl, DIALOG_CONFLICT_IMPL);
            Class<?> info = HookKit.findClass(module, f, cl, DIALOG_CONFLICT_INFO);
            if (impl != null && info != null) {
                Method addToQueue = HookKit.findMethod(module, f, impl, "addToQueue", info);
                HookKit.install(module, f, "Lry1#IDialogConflictMgr.addToQueue", addToQueue,
                        HookKit.constBool(module, f,
                                "Lry1#IDialogConflictMgr.addToQueue", false));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
