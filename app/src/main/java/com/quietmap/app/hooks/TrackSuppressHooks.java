package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * track_suppress 统计上报净化（开屏埋点 + UT 统计总入口）。
 *
 * dexsql 实测（高德 17.02.0.2010；17.01.0.2012 挂载点同构，仅混淆参数类型
 * Le72; → Lo82;）：
 *
 * 1) com.autonavi.minimap.track.b#e(...) —— 开屏曝光/点击埋点，方法体构建
 *    H5LogRequest（含 "splashscreen"、"afp" 等字段）后交给 AlimamaRequestHolder，
 *    纯上报。19 个参数，含 SplashButtonInfo 与混淆类 Lo82;。
 * 2) track.b#f(...) —— 开屏埋点聚合入口，方法体先转调 e(...)，再对
 *    thirdClickUrls/thirdImpressionUrls 逐个发 GET 监测请求，并转调 b(...)
 *    （UT 上报）。20 个参数。两条都 skipVoid 即断掉开屏埋点与三方监测。
 * 3) SplashLogManager#a(String,String,String,String,boolean)V —— 开屏日志上传，
 *    纯网络上报，不调用。
 * 4) com.ut.mini.UTTracker#send(Map)V —— UT 统计的**总出口**（dexsql 实测
 *    UTAnalytics 本身没有 send/track；它只暴露 getDefaultTracker()，真正的
 *    上报由 UTTracker.send 完成）。不调用 = 所有 UT 事件不再上报。
 * 5) UTTracker#sendLog(Map,int,List)V —— send 的内部落盘/转发入口，作为第二道
 *    保险一起断（同样纯统计）。
 *
 * 说明：UT 上报是纯统计，不影响地图/搜索/导航功能；个别"任务/积分"类活动
 * 依赖上报数据，若用户遇到异常可单独关闭 track_suppress。
 */
final class TrackSuppressHooks {

    private static final String TRACK_B = "com.autonavi.minimap.track.b";
    private static final String SPLASH_BUTTON_INFO =
            "com.autonavi.minimap.entity.SplashButtonInfo";
    /** track.b#e/f 签名里的混淆参数类型 Lo82;（默认包；17.01.0.2012 为 Le72;）。 */
    private static final String TRACK_CALLBACK_PARAM = "o82";
    private static final String SPLASH_LOG_MANAGER =
            "com.autonavi.minimap.track.SplashLogManager";
    private static final String UT_TRACKER = "com.ut.mini.UTTracker";

    private TrackSuppressHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.TRACK_SUPPRESS;
        try {
            installSplashTrack(module, f, cl);
            installSplashLog(module, f, cl);
            installUtTracker(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 开屏曝光/点击埋点：e(...) 与 f(...) 两个大参数方法各挂一条。 */
    private static void installSplashTrack(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> track = HookKit.findClass(module, f, cl, TRACK_B);
        Class<?> button = HookKit.findClass(module, f, cl, SPLASH_BUTTON_INFO);
        Class<?> trackCallback = HookKit.findClass(module, f, cl, TRACK_CALLBACK_PARAM);
        if (track == null || button == null || trackCallback == null) {
            return;
        }

        Method e = HookKit.findMethod(module, f, track, "e",
                String.class, String.class, String.class, String.class, String.class,
                String.class, button, String.class, String.class, String.class,
                String.class, String.class, String.class, int.class, String.class,
                String.class, trackCallback, boolean.class, String.class);
        HookKit.install(module, f, "track.b#e", e,
                HookKit.skipVoid(module, f, "track.b#e"));

        Method fMethod = HookKit.findMethod(module, f, track, "f",
                String.class, String.class, ArrayList.class, String.class, String.class,
                String.class, boolean.class, String.class, button, String.class,
                String.class, String.class, boolean.class, String.class, String.class,
                int.class, String.class, trackCallback, boolean.class, String.class);
        HookKit.install(module, f, "track.b#f", fMethod,
                HookKit.skipVoid(module, f, "track.b#f"));
    }

    private static void installSplashLog(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> cls = HookKit.findClass(module, f, cl, SPLASH_LOG_MANAGER);
        if (cls == null) {
            return;
        }
        Method a = HookKit.findMethod(module, f, cls, "a",
                String.class, String.class, String.class, String.class, boolean.class);
        HookKit.install(module, f, "SplashLogManager#a", a,
                HookKit.skipVoid(module, f, "SplashLogManager#a"));
    }

    /** UT 统计总出口：UTAnalytics 只提供 tracker，真正的 send 在 UTTracker。 */
    private static void installUtTracker(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> cls = HookKit.findClass(module, f, cl, UT_TRACKER);
        if (cls == null) {
            return;
        }
        Method send = HookKit.findMethod(module, f, cls, "send", Map.class);
        HookKit.install(module, f, "UTTracker#send", send,
                HookKit.skipVoid(module, f, "UTTracker#send"));

        Method sendLog = HookKit.findMethod(module, f, cls, "sendLog",
                Map.class, int.class, List.class);
        HookKit.install(module, f, "UTTracker#sendLog", sendLog,
                HookKit.skipVoid(module, f, "UTTracker#sendLog"));
    }
}
