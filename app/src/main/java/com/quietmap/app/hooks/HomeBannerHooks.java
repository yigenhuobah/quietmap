package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * home_banner 首页 Banner 断源（Banner 拉取断源，页面布局自适应留空）。
 *
 * 三个挂载点的完整签名由 anchors.json 提供：
 *   1) banner_send_list_2p —— BannerRequestHolder#sendList 2 参重载
 *   2) banner_send_list_3p —— BannerRequestHolder#sendList 3 参（falcon 回调）重载
 *   3) banner_manager_load —— BannerManager 的首页运营位加载入口
 * 批 2 经验：RequestHolder 常有多参重载，两个都挂才能保证断源。
 *
 * 全部 skipVoid：不调用原方法即不发起任何 Banner 网络请求，本地无缓存自动留空。
 * 任一挂载点定位失败只记 skip，其余照常挂（fail-open）。
 */
final class HomeBannerHooks {

    private HomeBannerHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.HOME_BANNER;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method send2 = anchors.method(AnchorKeys.BANNER_SEND_LIST_2P);
            HookKit.install(module, f, AnchorKeys.BANNER_SEND_LIST_2P, send2,
                    HookKit.skipVoid(module, f, AnchorKeys.BANNER_SEND_LIST_2P));

            Method send3 = anchors.method(AnchorKeys.BANNER_SEND_LIST_3P);
            HookKit.install(module, f, AnchorKeys.BANNER_SEND_LIST_3P, send3,
                    HookKit.skipVoid(module, f, AnchorKeys.BANNER_SEND_LIST_3P));

            Method load = anchors.method(AnchorKeys.BANNER_MANAGER_LOAD);
            HookKit.install(module, f, AnchorKeys.BANNER_MANAGER_LOAD, load,
                    HookKit.skipVoid(module, f, AnchorKeys.BANNER_MANAGER_LOAD));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
