package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * track_suppress 统计上报净化（开屏埋点 + UT 统计总入口）。
 *
 * 五个挂载点的完整签名由 anchors.json 提供：
 *
 * 1) track_splash_exposure —— track.b 的开屏曝光/点击埋点，方法体构建
 *    H5LogRequest（含 "splashscreen"、"afp" 等字段）后交给 AlimamaRequestHolder，
 *    纯上报。19 个参数，含 SplashButtonInfo 与混淆回调参数。
 * 2) track_splash_aggregate —— 开屏埋点聚合入口，方法体先转调 e(...)，再对
 *    thirdClickUrls/thirdImpressionUrls 逐个发 GET 监测请求，并转调 b(...)
 *    （UT 上报）。20 个参数。两条都 skipVoid 即断掉开屏埋点与三方监测。
 * 3) splash_log_upload —— SplashLogManager 的开屏日志上传，纯网络上报，不调用。
 * 4) ut_send —— UTTracker#send(Map)V，UT 统计的**总出口**（dexsql 实测
 *    UTAnalytics 本身没有 send/track；它只暴露 getDefaultTracker()，真正的
 *    上报由 UTTracker.send 完成）。不调用 = 所有 UT 事件不再上报。
 * 5) ut_send_log —— UTTracker#sendLog(Map,int,List)V，send 的内部落盘/转发入口，
 *    作为第二道保险一起断（同样纯统计）。
 *
 * 说明：UT 上报是纯统计，不影响地图/搜索/导航功能；个别"任务/积分"类活动
 * 依赖上报数据，若用户遇到异常可单独关闭 track_suppress。
 */
final class TrackSuppressHooks {

    private TrackSuppressHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.TRACK_SUPPRESS;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method e = anchors.method(AnchorKeys.TRACK_SPLASH_EXPOSURE);
            HookKit.install(module, f, AnchorKeys.TRACK_SPLASH_EXPOSURE, e,
                    HookKit.skipVoid(module, f, AnchorKeys.TRACK_SPLASH_EXPOSURE));

            Method aggregate = anchors.method(AnchorKeys.TRACK_SPLASH_AGGREGATE);
            HookKit.install(module, f, AnchorKeys.TRACK_SPLASH_AGGREGATE, aggregate,
                    HookKit.skipVoid(module, f, AnchorKeys.TRACK_SPLASH_AGGREGATE));

            Method upload = anchors.method(AnchorKeys.SPLASH_LOG_UPLOAD);
            HookKit.install(module, f, AnchorKeys.SPLASH_LOG_UPLOAD, upload,
                    HookKit.skipVoid(module, f, AnchorKeys.SPLASH_LOG_UPLOAD));

            Method send = anchors.method(AnchorKeys.UT_SEND);
            HookKit.install(module, f, AnchorKeys.UT_SEND, send,
                    HookKit.skipVoid(module, f, AnchorKeys.UT_SEND));

            Method sendLog = anchors.method(AnchorKeys.UT_SEND_LOG);
            HookKit.install(module, f, AnchorKeys.UT_SEND_LOG, sendLog,
                    HookKit.skipVoid(module, f, AnchorKeys.UT_SEND_LOG));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
