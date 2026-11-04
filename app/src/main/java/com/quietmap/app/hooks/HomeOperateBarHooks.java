package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * home_operate_bar 首页运营活动条与地图消息气泡屏蔽。
 *
 * 两个挂载点的完整签名由 anchors.json 提供：
 *   1) home_operate_refresh_splash_bar —— OperateActivityMapWidget#refreshSplashBar
 *      （首页运营活动条含 GIF/Lottie 运营动图的刷新入口，方法体读 AmapMessage.isAd），
 *      不调用即不渲染运营条。
 *   2) msg_tip_show_tips —— MsgTipMapWidget#showTips 返回 boolean
 *      （地图消息气泡/跑马灯展示入口，同样读 AmapMessage.isAd），
 *      return false 即气泡不展示。
 *
 * 全部 skipVoid / constBool：两个都是纯展示层入口，被跳过不会崩，
 * 调用方拿到 false 走「不展示」分支。
 */
final class HomeOperateBarHooks {

    private HomeOperateBarHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.HOME_OPERATE_BAR;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method refreshSplashBar = anchors.method(AnchorKeys.HOME_OPERATE_REFRESH_SPLASH_BAR);
            HookKit.install(module, f, AnchorKeys.HOME_OPERATE_REFRESH_SPLASH_BAR,
                    refreshSplashBar,
                    HookKit.skipVoid(module, f, AnchorKeys.HOME_OPERATE_REFRESH_SPLASH_BAR));

            Method showTips = anchors.method(AnchorKeys.MSG_TIP_SHOW_TIPS);
            HookKit.install(module, f, AnchorKeys.MSG_TIP_SHOW_TIPS, showTips,
                    HookKit.constBool(module, f, AnchorKeys.MSG_TIP_SHOW_TIPS, false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
