package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * splash_request 开屏广告请求断源（省流量省电，本地无缓存广告自动回退纯 logo）。
 *
 * 三个挂载点的完整签名由 anchors.json 提供：
 * 1) splash_send_3p / 2) splash_send_2p —— AlimamaRequestHolder#sendSplashScreen
 *    两个重载均不调用：阿里妈妈广告接口，纯网络层。3 参的 falcon 回调基类以
 *    dexsql 实测为准（调研清单里的旧包名不存在）。
 * 3) splash_attempt_request_data —— 混淆类 ah5 的 attemptRequestData(IZ)V
 *    不调用：开屏广告请求调度。定位策略：anchors.json 的 candidates 类名列表
 *    优先（Class.forName 毫秒级命中，同步落钩）；dexkit 方法体字符串锚定
 *    （"不发送请求" 特征串）仅作兜底，且「查询 + 落钩」整体挪到后台延迟执行
 *    （native 加载 + 全量扫描实测秒级，同步跑会把 onPackageReady 启动关键路径
 *    阻塞数秒，见 finding #88）；两路都失败记 skip，fail-open。
 */
final class SplashRequestHooks {

    private SplashRequestHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_REQUEST;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            // 先挂便宜的非混淆挂载点，再做 attemptRequestData 定位。
            Method send3 = anchors.method(AnchorKeys.SPLASH_SEND_3P);
            HookKit.install(module, f, AnchorKeys.SPLASH_SEND_3P, send3,
                    HookKit.skipVoid(module, f, AnchorKeys.SPLASH_SEND_3P));

            Method send2 = anchors.method(AnchorKeys.SPLASH_SEND_2P);
            HookKit.install(module, f, AnchorKeys.SPLASH_SEND_2P, send2,
                    HookKit.skipVoid(module, f, AnchorKeys.SPLASH_SEND_2P));

            installAttemptRequestData(module, f, cl, anchors);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * attemptRequestData 定位与落钩：anchors.json 的 candidates 类名快路径优先
     * （毫秒级，命中即同步落钩）；miss 后的 DexKit 锚点扫描转
     * {@link HookKit#installByAnchorLate} 后台执行，本方法在启动关键路径上不再
     * 等待扫描结果。
     */
    private static void installAttemptRequestData(XposedModule module, Feature f,
                                                  ClassLoader cl, AnchorTable anchors) {
        Method m = anchors.methodForCandidates(AnchorKeys.SPLASH_ATTEMPT_REQUEST_DATA);
        if (m != null) {
            HookKit.info(module, f.id + " attemptRequestData located via anchor candidates: "
                    + m.getDeclaringClass().getName());
            HookKit.install(module, f, AnchorKeys.SPLASH_ATTEMPT_REQUEST_DATA, m,
                    HookKit.skipVoid(module, f, AnchorKeys.SPLASH_ATTEMPT_REQUEST_DATA));
            return;
        }
        HookKit.warn(module, f.id
                + " anchor candidates miss, dexkit anchor deferred to background");

        // 兜底：DexKit 按方法体特征串定位（类名改了也能找到），整体放后台。
        HookKit.installByAnchorLate(module, f, AnchorKeys.SPLASH_ATTEMPT_REQUEST_DATA, cl,
                null,
                anchors.methodName(AnchorKeys.SPLASH_ATTEMPT_REQUEST_DATA),
                void.class, new Class<?>[]{int.class, boolean.class},
                anchors.dexkit(AnchorKeys.SPLASH_ATTEMPT_REQUEST_DATA));
    }
}
