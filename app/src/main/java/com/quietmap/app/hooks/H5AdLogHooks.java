package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * h5_ad_log H5 广告监测断源（H5 页面广告点击/曝光不再上报）。
 *
 * AlimamaRequestHolder#sendH5Log 有两个重载（2 参 / 3 参 falcon 回调），
 * 完整签名由 anchors.json 的 h5_log_send_2p / h5_log_send_3p 提供。
 * 请求参数含 page / session_id / click / displayfail / click_third / thirdurl，
 * 是典型 H5 广告点击与曝光监测。两个重载都不调用，监测链路断源。
 *
 * 全部 skipVoid：不调用原方法；任一挂载点定位失败只记 skip（fail-open）。
 */
final class H5AdLogHooks {

    private H5AdLogHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.H5_AD_LOG;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method send2 = anchors.method(AnchorKeys.H5_LOG_SEND_2P);
            HookKit.install(module, f, AnchorKeys.H5_LOG_SEND_2P, send2,
                    HookKit.skipVoid(module, f, AnchorKeys.H5_LOG_SEND_2P));

            Method send3 = anchors.method(AnchorKeys.H5_LOG_SEND_3P);
            HookKit.install(module, f, AnchorKeys.H5_LOG_SEND_3P, send3,
                    HookKit.skipVoid(module, f, AnchorKeys.H5_LOG_SEND_3P));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
