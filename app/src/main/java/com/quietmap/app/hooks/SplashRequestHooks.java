package com.quietmap.app.hooks;

import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.dexkit.Anchors;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * splash_request 开屏广告请求断源（省流量省电，本地无缓存广告自动回退纯 logo）。
 *
 * 1) AlimamaRequestHolder#sendSplashScreen 两个重载均不调用 —— 阿里妈妈广告接口，
 *    纯网络层，dexsql 实测两个重载：
 *      3 参 (SplashScreenRequest, com.autonavi.minimap.falcon.base.a, AosResponseCallback)
 *      2 参 (SplashScreenRequest, AosResponseCallback)
 *    注意调研清单里 3 参的 falcon 参数写作 com.amap.bundle.falcon.base.a，
 *    dexsql 实测该类不存在，实际是 com.autonavi.minimap.falcon.base.a —— 以 dexsql 为准。
 * 2) 混淆类 Lah5;->attemptRequestData(IZ)V 不调用 —— 开屏广告请求调度。
 *    17.02.0.2010 实测：旧类名 Lze5; 改为 Lah5;，方法名/签名与锚点串不变；
 *    兄弟混淆类 Lye5;->run()V（同锚点串"不发送请求：拿不到锁"）改名为 Lzg5;，
 *    DexKit 兜底仍按方法名/参数消歧，不受影响。
 *    定位：类名列表优先（check-mounts 查出新名后追加，Class.forName 毫秒级命中，
 *    同步落钩）；DexKit 方法体字符串锚定（"不发送请求" 特征串）仅作兜底，且
 *    「查询 + 落钩」整体挪到后台延迟执行（native 加载 + 全量扫描实测秒级，
 *    同步跑会把 onPackageReady 启动关键路径阻塞数秒，见 finding #88）；
 *    两路都失败记 skip，fail-open。
 */
final class SplashRequestHooks {

    private static final String ALIMAMA = "com.autonavi.minimap.alimama.AlimamaRequestHolder";
    private static final String REQUEST = "com.autonavi.minimap.alimama.param.SplashScreenRequest";
    /** dexsql 实测的 falcon 回调基类（清单旧签名 com.amap.bundle.falcon.base.a 不存在）。 */
    private static final String FALCON = "com.autonavi.minimap.falcon.base.a";
    private static final String CALLBACK = "com.amap.bundle.aosservice.response.AosResponseCallback";

    /**
     * attemptRequestData 兜底类名列表：dex 描述符 Lah5; → Java 名 ah5（默认包，
     * 17.02.0.2010 实测；17.01.0.2012 为 Lze5;）。
     * 高德升级后用 check-mounts.sh 体检 + dexsql 字符串反查出新名，追加到这里。
     */
    private static final String[] ATTEMPT_CLASS_NAMES = {"ah5"};

    private static final String ATTEMPT_METHOD = "attemptRequestData";
    /** 挂载标签；类名列表与 DexKit 兜底两条路共用，诊断页 reg 才能对上。 */
    private static final String ATTEMPT_LABEL = "Lah5#attemptRequestData(IZ)";

    private SplashRequestHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_REQUEST;
        try {
            // 先挂便宜的非混淆挂载点，再做 Lah5 定位（类名列表优先，DexKit 兜底延迟落钩）。
            Class<?> holder = HookKit.findClass(module, f, cl, ALIMAMA);
            Class<?> request = HookKit.findClass(module, f, cl, REQUEST);
            Class<?> falcon = HookKit.findClass(module, f, cl, FALCON);
            Class<?> callback = HookKit.findClass(module, f, cl, CALLBACK);

            if (holder != null && request != null && falcon != null && callback != null) {
                Method send3 = HookKit.findMethod(module, f, holder,
                        "sendSplashScreen", request, falcon, callback);
                HookKit.install(module, f, "AlimamaRequestHolder#sendSplashScreen(3p)", send3,
                        HookKit.skipVoid(module, f, "AlimamaRequestHolder#sendSplashScreen(3p)"));
            }
            if (holder != null && request != null && callback != null) {
                Method send2 = HookKit.findMethod(module, f, holder,
                        "sendSplashScreen", request, callback);
                HookKit.install(module, f, "AlimamaRequestHolder#sendSplashScreen(2p)", send2,
                        HookKit.skipVoid(module, f, "AlimamaRequestHolder#sendSplashScreen(2p)"));
            }

            installAttemptRequestData(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * attemptRequestData 定位与落钩：类名列表优先（毫秒级，命中即同步落钩）；
     * miss 后的 DexKit 锚点扫描转 {@link HookKit#installByAnchorLate} 后台执行，
     * 本方法在启动关键路径上不再等待扫描结果。
     */
    private static void installAttemptRequestData(XposedModule module, Feature f, ClassLoader cl) {
        // 优先：类名列表（check-mounts 体检出新名后追加），避免冷启动白付 DexKit 全量扫描。
        for (String name : ATTEMPT_CLASS_NAMES) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                Method m = HookKit.findMethodQuiet(cls, ATTEMPT_METHOD, int.class, boolean.class);
                if (m != null) {
                    HookKit.info(module, f.id
                            + " attemptRequestData located via class-name list: " + name);
                    HookKit.install(module, f, ATTEMPT_LABEL, m,
                            HookKit.skipVoid(module, f, ATTEMPT_LABEL));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        HookKit.warn(module, f.id + " class-name list miss, dexkit anchor deferred to background");

        // 兜底：DexKit 按方法体特征串定位（类名改了也能找到），整体放后台。
        HookKit.installByAnchorLate(module, f, ATTEMPT_LABEL, cl, null, ATTEMPT_METHOD,
                void.class, new Class<?>[]{int.class, boolean.class},
                new String[]{Anchors.STR_SPLASH_REQ_NO_SEND});
    }
}
