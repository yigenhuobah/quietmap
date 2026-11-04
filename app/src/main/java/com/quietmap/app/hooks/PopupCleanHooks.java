package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * popup_clean 弹窗净化（升级提示配置 + 首页弹窗冲突队列）。
 *
 * 两个挂载点的完整签名由 anchors.json 提供：
 *
 * 1) update_hint_config_download —— UpdateHintConfigDownloader 的下载入口
 *    （方法体含 "update_hint_apk_newest_version" 等 prefs 键），不调用即不下载/
 *    刷新升级提示配置。纯配置下载，无 UI 副作用。
 *
 * 2) dialog_conflict_add_to_queue —— IDialogConflictMgr#addToQueue 的实现类
 *    （混淆类，dexsql class_interfaces 反查唯一实现）。接口层 hook 无效
 *    （ART 走 itable 分派），必须挂实现类。返回 false = 不入队、不展示。
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

    private PopupCleanHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.POPUP_CLEAN;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method updateHint = anchors.method(AnchorKeys.UPDATE_HINT_CONFIG_DOWNLOAD);
            HookKit.install(module, f, AnchorKeys.UPDATE_HINT_CONFIG_DOWNLOAD, updateHint,
                    HookKit.skipVoid(module, f, AnchorKeys.UPDATE_HINT_CONFIG_DOWNLOAD));

            Method addToQueue =
                    anchors.methodForCandidates(AnchorKeys.DIALOG_CONFLICT_ADD_TO_QUEUE);
            HookKit.install(module, f, AnchorKeys.DIALOG_CONFLICT_ADD_TO_QUEUE, addToQueue,
                    HookKit.constBool(module, f, AnchorKeys.DIALOG_CONFLICT_ADD_TO_QUEUE,
                            false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
