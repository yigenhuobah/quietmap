package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * home_msg_filter 首页消息红点体系净化（数据层断源，白名单式取舍）。
 *
 * dexsql 实测（高德 17.02.0.2010；17.01.0.2012 挂载点同构，仅混淆参数类型
 * Lxf2; → Lzg2;、实现类 Llt1; → Lev1;）挂载点：
 *
 * 1) MessageBoxManager#getMessages —— **三个重载全挂**（dexsql 实测 3 条签名，
 *    任务清单只列了 1 条，以实测为准）：
 *      (GetMessageListener, boolean, zg2)
 *      (GetMessageListener, boolean, boolean, zg2)
 *      (GetMessageListener, boolean, boolean, zg2, String)
 *    该方法内部依次调 retrieveLocalMessages(Set, boolean) 与
 *    retrieveRemoteMessages(Set, String)，是首页消息盒拉取的总入口。
 *    不调用 → 首页消息列表/红点不再有新数据。
 *
 * 2) MessageBoxManager#retrieveRemoteMessages(Set, String) —— 远程营销消息拉取。
 *    单独挂：getMessages 内部也会调它，双保险断远程源。
 *    **本地已缓存消息由 getAllLocalMessages 走 DB，不受本钩子影响**，
 *    这是「不误杀真实消息」的关键设计（见类注释的取舍说明）。
 *
 * 3) IDeliveryDisplay#start 的实现类 —— 接口是
 *    Lcom/autonavi/minimap/bundle/msgbox/mainmap/IDeliveryDisplay;（纯接口，
 *    hook 接口方法无效），实际实现类是混淆类 Lev1;（dexsql class_interfaces
 *    反查：17.02.0.2010 下唯一实现类，旧版本为 Llt1;）。
 *    故必须挂 Lev1;->start(Activity, AmapMessage)。
 *
 * 4) 角标红点桥接：AbstractModuleBadge 是抽象类（access_flags=1025），
 *    唯一子类 Lcom/amap/bundle/badgesystem/ajxmodule/AjxModuleBadge; 覆写了
 *    updateAppBadge(String,int) 与 setBadgeDisplay(String,boolean) 且有实体
 *    实现体（smali 实测）。故挂 AjxModuleBadge 的两个方法：
 *      updateAppBadge   → count > 0 时不调用；count <= 0 放行（清角标）
 *      setBadgeDisplay  → 不调用（本地广播 BADGE_SYSTEM_DISPLAY 不发出）
 *    但首页消息盒路径 MapHomeMsgDispatchServiceImpl.updateBadgeInternal 走
 *    接口 IBadgeSystemService->updateAppBadge，实际实现在
 *    Lcom/autonavi/jni/badgesystem/BadgeSystemManager;->updateAppBadge(String,int)
 *    （dexsql 实证，返回 V），故额外挂 BadgeSystemManager 的同名方法：
 *      updateAppBadge   → count > 0 时不调用；count <= 0 放行（清角标）
 *
 * 取舍说明（白名单式）：
 * - 本批取数据层断源（不拉远程消息），不按 isAd 逐条过滤——getMessages 是
 *   拉取总入口且无法在不改返回结构的前提下区分广告与真实通知，逐条过滤需要
 *   hook 回调并在 GetMessageListener.onFinish(List,List,boolean,boolean) 里
 *   改列表，工程复杂度高且回调可能已触发 UI 更新。
 * - dexsql 检索 AmapMessage 全部 TOKEN_* 常量（TOKEN_UPDATE_APP /
 *   TOKEN_TAOBAO_LOGIN / TOKEN_TRAVEL / TOKEN_REAL3D / TOKEN_CLOUD_SYNC_DIALOG /
 *   TOKEN_OFFLINE_DOWN_CITY / TOKEN_UPDATE_OFFLINE_MAP / TOKEN_DOWNLOAD_SEAR_MAP），
 *   **未发现 TOKEN_SYSTEM* 之类的系统通知 token**，即 msgbox 体系不承载
 *   真实系统通知（系统通知走 NotificationManager，与 msgbox 无关）。
 *   消息盒承载的是运营消息/活动/升级提示/离线地图下载等业务消息，
 *   断远程源只影响这些业务消息的「新消息提醒」，不影响本地已读历史
 *   （getAllLocalMessages 走 DB，本钩子不碰）。
 * - 用户若发现消息列表为空影响使用，关掉 home_msg_filter 开关即可完全恢复。
 */
final class HomeMsgFilterHooks {

    private static final String MESSAGE_BOX_MANAGER =
            "com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager";
    private static final String GET_MESSAGE_LISTENER =
            "com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager$GetMessageListener";
    /** getMessages 的第三个参数类型，dexsql 描述符 Lzg2;（包内混淆接口/类，旧版 Lxf2;）。 */
    private static final String MSG_FILTER_PARAM = "zg2";

    /** IDeliveryDisplay 的实际实现类（dexsql class_interfaces 实测，旧版 Llt1;）。 */
    private static final String DELIVERY_DISPLAY_IMPL = "ev1";
    private static final String AMAP_MESSAGE =
            "com.autonavi.minimap.bundle.msgbox.entity.AmapMessage";

    /** 角标桥接：AbstractModuleBadge 是抽象类，实现类在 badgesystem 包（非混淆）。 */
    private static final String AJX_MODULE_BADGE =
            "com.amap.bundle.badgesystem.ajxmodule.AjxModuleBadge";

    /** 消息盒角标路径的实际实现类：IBadgeSystemService 的 JNI 实现（dexsql 实证）。 */
    private static final String BADGE_SYSTEM_MANAGER =
            "com.autonavi.jni.badgesystem.BadgeSystemManager";

    private HomeMsgFilterHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.HOME_MSG_FILTER;
        try {
            installGetMessages(module, f, cl);
            installRetrieveRemoteMessages(module, f, cl);
            installDeliveryDisplay(module, f, cl);
            installBadge(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * getMessages 三个重载全挂。参数类型 GetMessageListener / zg2 需先从目标
     * ClassLoader 加载才能反射拿 Method；任一类型缺失则整组跳过（避免挂半套）。
     */
    private static void installGetMessages(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> manager = HookKit.findClass(module, f, cl, MESSAGE_BOX_MANAGER);
        Class<?> listener = HookKit.findClass(module, f, cl, GET_MESSAGE_LISTENER);
        Class<?> filterParam = HookKit.findClass(module, f, cl, MSG_FILTER_PARAM);
        if (manager == null || listener == null || filterParam == null) {
            return;
        }

        // 2 参：(GetMessageListener, boolean, zg2)
        Method m2 = HookKit.findMethod(module, f, manager,
                "getMessages", listener, boolean.class, filterParam);
        HookKit.install(module, f, "MessageBoxManager#getMessages(2p)", m2,
                HookKit.skipVoid(module, f, "MessageBoxManager#getMessages(2p)"));

        // 3 参：(GetMessageListener, boolean, boolean, zg2)
        Method m3 = HookKit.findMethod(module, f, manager,
                "getMessages", listener, boolean.class, boolean.class, filterParam);
        HookKit.install(module, f, "MessageBoxManager#getMessages(3p)", m3,
                HookKit.skipVoid(module, f, "MessageBoxManager#getMessages(3p)"));

        // 4 参：(GetMessageListener, boolean, boolean, zg2, String)
        Method m4 = HookKit.findMethod(module, f, manager,
                "getMessages", listener, boolean.class, boolean.class, filterParam, String.class);
        HookKit.install(module, f, "MessageBoxManager#getMessages(4p)", m4,
                HookKit.skipVoid(module, f, "MessageBoxManager#getMessages(4p)"));
    }

    /** 远程营销消息拉取，断源（本地缓存走 DB 不受影响）。 */
    private static void installRetrieveRemoteMessages(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> manager = HookKit.findClass(module, f, cl, MESSAGE_BOX_MANAGER);
        if (manager == null) {
            return;
        }
        Method retrieveRemote = HookKit.findMethod(module, f, manager,
                "retrieveRemoteMessages", java.util.Set.class, String.class);
        HookKit.install(module, f, "MessageBoxManager#retrieveRemoteMessages", retrieveRemote,
                HookKit.skipVoid(module, f, "MessageBoxManager#retrieveRemoteMessages"));
    }

    /**
     * 主图投放展示：接口 IDeliveryDisplay 不能 hook（接口方法无实现体，
     * 挂上也不会被调用——ART 走 itable 分派到实现类方法），必须挂实现类 Lev1。
     */
    private static void installDeliveryDisplay(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> impl = HookKit.findClass(module, f, cl, DELIVERY_DISPLAY_IMPL);
        Class<?> message = HookKit.findClass(module, f, cl, AMAP_MESSAGE);
        if (impl == null || message == null) {
            return;
        }
        Method start = HookKit.findMethod(module, f, impl,
                "start", android.app.Activity.class, message);
        HookKit.install(module, f, "Lev1#IDeliveryDisplay.start", start,
                HookKit.skipVoid(module, f, "Lev1#IDeliveryDisplay.start"));
    }

    /** 角标红点：updateAppBadge(count>0) 不调用 + setBadgeDisplay 不调用。 */
    private static void installBadge(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> badge = HookKit.findClass(module, f, cl, AJX_MODULE_BADGE);
        if (badge != null) {
            Method updateAppBadge = HookKit.findMethod(module, f, badge,
                    "updateAppBadge", String.class, int.class);
            HookKit.install(module, f, "AjxModuleBadge#updateAppBadge", updateAppBadge,
                    suppressPositiveBadgeCount(module, f, "AjxModuleBadge#updateAppBadge"));

            Method setBadgeDisplay = HookKit.findMethod(module, f, badge,
                    "setBadgeDisplay", String.class, boolean.class);
            HookKit.install(module, f, "AjxModuleBadge#setBadgeDisplay", setBadgeDisplay,
                    HookKit.skipVoid(module, f, "AjxModuleBadge#setBadgeDisplay"));
        }

        // 消息盒路径 MapHomeMsgDispatchServiceImpl.updateBadgeInternal 经
        // IBadgeSystemService 分派到该 JNI 实现类，与 AjxModuleBadge 互不依赖。
        Class<?> badgeSystem = HookKit.findClass(module, f, cl, BADGE_SYSTEM_MANAGER);
        if (badgeSystem == null) {
            return;
        }
        Method jniUpdateAppBadge = HookKit.findMethod(module, f, badgeSystem,
                "updateAppBadge", String.class, int.class);
        HookKit.install(module, f, "BadgeSystemManager#updateAppBadge", jniUpdateAppBadge,
                suppressPositiveBadgeCount(module, f, "BadgeSystemManager#updateAppBadge"));
    }

    /**
     * 角标计数过滤 Hooker：读第二个参数 count（int）。
     * count > 0（设置/累加角标）→ 记 hit 并返回 null，不执行原方法；
     * count <= 0（清零/去角标）→ 原样放行，避免旧角标残留。
     * 整段 try/catch(Throwable)：命中路径 proceed 0 次，回退路径恰好 1 次；
     * proceed 已执行过则不再重复执行，杜绝双重执行。
     */
    private static XposedInterface.Hooker suppressPositiveBadgeCount(final XposedModule module,
                                                                     final Feature f,
                                                                     final String label) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    Object raw = chain.getArg(1);
                    if (raw instanceof Number && ((Number) raw).intValue() > 0) {
                        HookKit.hit(module, f, f.id + " hit " + label
                                + " count=" + ((Number) raw).intValue());
                        return null;
                    }
                    proceeded = true;
                    return chain.proceed();
                } catch (Throwable t) {
                    if (proceeded) {
                        // 原方法已执行过一次，不能再次 proceed。
                        throw t;
                    }
                    HookKit.error(module, f.id + " intercept error " + label, t);
                    return chain.proceed();
                }
            }
        };
    }
}
