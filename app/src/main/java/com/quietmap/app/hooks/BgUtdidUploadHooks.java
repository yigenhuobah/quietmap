package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * bg_utdid_upload 设备指纹上传断源。
 *
 * UtdidUploadTask 三个挂载点，同一个开关：
 * 1) upload()V 与 2) uploadFromCache()V —— 不调用，断两条上传路径；
 * 3) reqServer(String)Z —— return false，断服务端交换（兜底）。
 * 三个挂载点的类名/方法名/签名全部来自 anchors.json。
 */
final class BgUtdidUploadHooks {

    private BgUtdidUploadHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.BG_UTDID_UPLOAD;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method upload = anchors.method(AnchorKeys.UTDID_UPLOAD);
            HookKit.install(module, f, AnchorKeys.UTDID_UPLOAD, upload,
                    HookKit.skipVoid(module, f, AnchorKeys.UTDID_UPLOAD));

            Method fromCache = anchors.method(AnchorKeys.UTDID_UPLOAD_FROM_CACHE);
            HookKit.install(module, f, AnchorKeys.UTDID_UPLOAD_FROM_CACHE, fromCache,
                    HookKit.skipVoid(module, f, AnchorKeys.UTDID_UPLOAD_FROM_CACHE));

            Method reqServer = anchors.method(AnchorKeys.UTDID_REQ_SERVER);
            HookKit.install(module, f, AnchorKeys.UTDID_REQ_SERVER, reqServer,
                    HookKit.constBool(module, f, AnchorKeys.UTDID_REQ_SERVER, false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
