package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * bg_msg_fetch 后台消息拉取净化。
 *
 * 五个挂载点，同一个开关，全部由 anchors.json 提供 c/m/s：
 * 1) MessageBoxManager#getMessageInBackground(Activity, boolean) —— 亮屏时后台拉取营销消息，
 *    不调用即断「拉取」；
 * 2) BackgroundMsgScreenReceiver#onReceive(Context, Intent) —— 亮屏广播触发源，
 *    不调用即断「触发」；
 * 3) MessageBoxManager#handlePush(String) —— 推送消息处理入口：解析 "AmapPush-"
 *    前缀、写 PUSH_MSG prefs 并 post MessageBoxManager$c 拉取消息；不调用即不处理
 *    推送内容（**不影响推送通道注册**，见下）；
 * 4) BackgroundMsgManager#a(IBackgroundMsgFetchListener) —— 后台营销消息网络拉取
 *    （请求 /ws/msgbox/notice-msgs），不调用即断源；
 * 5) MessageBoxManager#fetchMessageFromMainMap(int, boolean, MainMapUIUpdater) ——
 *    主图消息拉取包装（内部转 getMessages），不调用。
 *
 * 推送通道说明（dexsql 实测）：
 * - PushManager / PushBindReceiver 属于系统推送通道（类族里同时存在小米 MiPushActivity、
 *   华为/荣耀、Heytap 等厂商 SDK）。PushManager#getPushAgent 返回 IPushAgent，
 *   PushBindReceiver 是绑定结果回调。**本批不 hook 它们**，避免影响推送 token 注册与
 *   系统通道绑定；只在上层 handlePush/拉取入口做净化。
 * - handlePush 只处理消息体，不参与通道注册；即使推送到达也只会被丢弃。
 *
 * 类或方法不存在时 fail-open 跳过（只记 skip），其余挂载点照常安装。
 */
final class BgMsgFetchHooks {

    private BgMsgFetchHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.BG_MSG_FETCH;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }

            Method getMessageInBackground = anchors.method(AnchorKeys.MSG_FETCH_IN_BACKGROUND);
            HookKit.install(module, f, AnchorKeys.MSG_FETCH_IN_BACKGROUND,
                    getMessageInBackground,
                    HookKit.skipVoid(module, f, AnchorKeys.MSG_FETCH_IN_BACKGROUND));

            Method onReceive = anchors.method(AnchorKeys.BG_MSG_SCREEN_RECEIVER);
            HookKit.install(module, f, AnchorKeys.BG_MSG_SCREEN_RECEIVER, onReceive,
                    HookKit.skipVoid(module, f, AnchorKeys.BG_MSG_SCREEN_RECEIVER));

            Method handlePush = anchors.method(AnchorKeys.MSG_HANDLE_PUSH);
            HookKit.install(module, f, AnchorKeys.MSG_HANDLE_PUSH, handlePush,
                    HookKit.skipVoid(module, f, AnchorKeys.MSG_HANDLE_PUSH));

            Method backgroundFetch = anchors.method(AnchorKeys.BG_MSG_MANAGER_FETCH);
            HookKit.install(module, f, AnchorKeys.BG_MSG_MANAGER_FETCH, backgroundFetch,
                    HookKit.skipVoid(module, f, AnchorKeys.BG_MSG_MANAGER_FETCH));

            Method fetchFromMainMap = anchors.method(AnchorKeys.MSG_FETCH_FROM_MAIN_MAP);
            HookKit.install(module, f, AnchorKeys.MSG_FETCH_FROM_MAIN_MAP, fetchFromMainMap,
                    HookKit.skipVoid(module, f, AnchorKeys.MSG_FETCH_FROM_MAIN_MAP));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
