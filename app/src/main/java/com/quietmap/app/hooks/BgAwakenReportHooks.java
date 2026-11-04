package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * bg_awaken_report 唤端上报断源。
 *
 * AwakenRequestHolder#sendAwakenPage 不调用即断唤起端上报。
 * 该方法名下有两个重载，锚点表分别给出完整 c/m/s：
 *   awaken_send_page_2p / awaken_send_page_3p
 * 两个都挂（同一方法只挂一个 hook，两个重载算两个方法），避免三参版漏网继续上报。
 */
final class BgAwakenReportHooks {

    private BgAwakenReportHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.BG_AWAKEN_REPORT;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method two = anchors.method(AnchorKeys.AWAKEN_SEND_PAGE_2P);
            HookKit.install(module, f, AnchorKeys.AWAKEN_SEND_PAGE_2P, two,
                    HookKit.skipVoid(module, f, AnchorKeys.AWAKEN_SEND_PAGE_2P));

            Method three = anchors.method(AnchorKeys.AWAKEN_SEND_PAGE_3P);
            HookKit.install(module, f, AnchorKeys.AWAKEN_SEND_PAGE_3P, three,
                    HookKit.skipVoid(module, f, AnchorKeys.AWAKEN_SEND_PAGE_3P));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
