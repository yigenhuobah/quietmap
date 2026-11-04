package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * home_msg_filter 首页消息红点体系净化（数据层断源，白名单式取舍）。
 *
 * dexsql 实测（高德 17.02.0.2010；17.01.0.2012 挂载点同构，仅混淆参数类型
 * Lxf2; → Lzg2;、实现类 Llt1; → Lev1;）挂载点：
 *
 * 1) MessageBoxManager#getMessages —— **三个重载全挂**（dexsql 实测 3 条签名，
 *    任务清单只列了 1 条，以实测为准），由 anchors.json 的
 *    msg_get_messages_2p / 3p / 4p 给出完整描述符：
 *      (GetMessageListener, boolean, zg2)
 *      (GetMessageListener, boolean, boolean, zg2)
 *      (GetMessageListener, boolean, boolean, zg2, String)
 *    该方法内部依次调 retrieveLocalMessages(Set, boolean) 与
 *    retrieveRemoteMessages(Set, String)，是首页消息盒拉取的总入口。
 *    这三个重载带 GetMessageListener 回调契约，不能整段 skipVoid（调用方注册的
 *    listener 永远等不到完成回调，ModuleMessageBox#hasNewMessage 会一直等不到
 *    onFinish）：改为「先对 listener 回调 onFinish(空List, 空List, false, false)
 *    再跳过业务体」，调用方立即收到「无消息」结果并结束等待态。
 *    listener 为 null 或回调反射失败时 fail-open 放行原方法。
 *
 * 2) MessageBoxManager#retrieveRemoteMessages(Set, String) —— 远程营销消息拉取
 *    （锚点 msg_retrieve_remote）。单独挂：getMessages 内部也会调它，双保险断远程源。
 *    **本地已缓存消息由 getAllLocalMessages 走 DB，不受本钩子影响**，
 *    这是「不误杀真实消息」的关键设计（见类注释的取舍说明）。
 *
 * 3) IDeliveryDisplay#start 的实现类 —— 接口是
 *    Lcom/autonavi/minimap/bundle/msgbox/mainmap/IDeliveryDisplay;（纯接口，
 *    hook 接口方法无效），实际实现类是混淆类（锚点 delivery_display_start，
 *    17.02 为 Lev1;，旧版本为 Llt1;）。故必须挂实现类的
 *    start(Activity, AmapMessage)。
 *
 * 4) 角标红点桥接：AbstractModuleBadge 是抽象类（access_flags=1025），
 *    唯一子类 覆写了 updateAppBadge(String,int) 与 setBadgeDisplay(String,boolean)
 *    且有实体实现体（smali 实测）。该桥按 bizId 共享（17.02 实证 BUS id 至少有
 *    taxi_tab/mine/messagetab/nearby_tab），必须按 bizId 门控，只处理
 *    messagetab（与 MsgCenterHooks#getBadgeInfo 的门控同源），否则其它业务的
 *    角标会被一并抑制。故挂两个方法（badge_update_app / badge_set_display）：
 *      updateAppBadge   → bizId=messagetab 且 count > 0 时不调用；其余放行
 *      setBadgeDisplay  → bizId=messagetab 时不调用（广播不发出）；其余放行
 *    但首页消息盒路径 MapHomeMsgDispatchServiceImpl.updateBadgeInternal 走
 *    接口 IBadgeSystemService->updateAppBadge，实际实现在 JNI 实现类
 *    （锚点 launcher_badge_update_app，dexsql 实证返回 V），故额外挂
 *    BadgeSystemManager 的同名方法：
 *      updateAppBadge   → bizId=messagetab 且 count > 0 时不调用；其余放行
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

    private HomeMsgFilterHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.HOME_MSG_FILTER;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            installGetMessages(module, f, anchors);
            installRetrieveRemoteMessages(module, f, anchors);
            installDeliveryDisplay(module, f, anchors);
            installBadge(module, f, anchors);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * getMessages 三个重载全挂。参数类型（GetMessageListener / 混淆参数类）由锚点
     * 描述符解析得到，任一描述符解析失败只影响对应重载。
     *
     * 三个重载都带 GetMessageListener 回调契约，不能用 skipVoid 整段跳过——
     * 那会让调用方（唯一真实入口 ModuleMessageBox#hasNewMessage）永远等不到
     * onFinish。这里从解析到的方法参数类型反射找 onFinish(List,List,boolean,boolean)，
     * 找不到则整组不挂（fail-open，挂了也无法完成回调契约）；找到后挂
     * 「回调空结果再跳过业务体」的 Hooker。
     */
    private static void installGetMessages(XposedModule module, Feature f,
                                           AnchorTable anchors) {
        Method m2 = anchors.method(AnchorKeys.MSG_GET_MESSAGES_2P);
        Method m3 = anchors.method(AnchorKeys.MSG_GET_MESSAGES_3P);
        Method m4 = anchors.method(AnchorKeys.MSG_GET_MESSAGES_4P);
        Method sample = m2 != null ? m2 : (m3 != null ? m3 : m4);
        if (sample == null) {
            HookState.markNotFound(f, "getMessages 锚点不可用");
            return;
        }
        Class<?> listener = sample.getParameterTypes()[0];
        Method onFinish = findListenerFinish(listener);
        if (onFinish == null) {
            HookKit.warn(module, f.id
                    + " skip: GetMessageListener#onFinish(List,List,Z,Z) not found");
            HookState.markNotFound(f, "GetMessageListener#onFinish 未找到");
            return;
        }
        setAccessibleQuiet(module, f, onFinish);

        HookKit.install(module, f, AnchorKeys.MSG_GET_MESSAGES_2P, m2,
                finishListenerThenSkip(module, f, AnchorKeys.MSG_GET_MESSAGES_2P, onFinish));
        HookKit.install(module, f, AnchorKeys.MSG_GET_MESSAGES_3P, m3,
                finishListenerThenSkip(module, f, AnchorKeys.MSG_GET_MESSAGES_3P, onFinish));
        HookKit.install(module, f, AnchorKeys.MSG_GET_MESSAGES_4P, m4,
                finishListenerThenSkip(module, f, AnchorKeys.MSG_GET_MESSAGES_4P, onFinish));
    }

    /**
     * 在监听器接口上找「无消息」完成回调：参数形状固定为
     * (List, List, boolean, boolean)。按形状匹配而不硬编码方法名，接口方法
     * 改名/换包只要形状不变仍能命中。
     */
    static Method findListenerFinish(Class<?> listener) {
        if (listener == null) {
            return null;
        }
        for (Method method : listener.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 4
                    && params[0] == List.class
                    && params[1] == List.class
                    && params[2] == boolean.class
                    && params[3] == boolean.class) {
                return method;
            }
        }
        return null;
    }

    /** 远程营销消息拉取，断源（本地缓存走 DB 不受影响）。 */
    private static void installRetrieveRemoteMessages(XposedModule module, Feature f,
                                                      AnchorTable anchors) {
        Method retrieveRemote = anchors.method(AnchorKeys.MSG_RETRIEVE_REMOTE);
        HookKit.install(module, f, AnchorKeys.MSG_RETRIEVE_REMOTE, retrieveRemote,
                HookKit.skipVoid(module, f, AnchorKeys.MSG_RETRIEVE_REMOTE));
    }

    /**
     * 主图投放展示：接口 IDeliveryDisplay 不能 hook（接口方法无实现体，
     * 挂上也不会被调用——ART 走 itable 分派到实现类方法），必须挂实现类。
     * 混淆类名与完整签名由 anchors.json 的 delivery_display_start 提供。
     */
    private static void installDeliveryDisplay(XposedModule module, Feature f,
                                               AnchorTable anchors) {
        Method start = anchors.methodForCandidates(AnchorKeys.DELIVERY_DISPLAY_START);
        HookKit.install(module, f, AnchorKeys.DELIVERY_DISPLAY_START, start,
                HookKit.skipVoid(module, f, AnchorKeys.DELIVERY_DISPLAY_START));
    }

    /** 角标红点：messagetab 的 updateAppBadge(count>0) 与 setBadgeDisplay 不调用。 */
    private static void installBadge(XposedModule module, Feature f, AnchorTable anchors) {
        Method updateAppBadge = anchors.method(AnchorKeys.BADGE_UPDATE_APP);
        HookKit.install(module, f, AnchorKeys.BADGE_UPDATE_APP, updateAppBadge,
                suppressPositiveBadgeCount(module, f, AnchorKeys.BADGE_UPDATE_APP));

        Method setBadgeDisplay = anchors.method(AnchorKeys.BADGE_SET_DISPLAY);
        HookKit.install(module, f, AnchorKeys.BADGE_SET_DISPLAY, setBadgeDisplay,
                skipMessageTabBadgeDisplay(module, f, AnchorKeys.BADGE_SET_DISPLAY));

        // 消息盒路径 MapHomeMsgDispatchServiceImpl.updateBadgeInternal 经
        // IBadgeSystemService 分派到 JNI 实现类，与 AJX 角标桥互不依赖。
        Method jniUpdateAppBadge = anchors.method(AnchorKeys.LAUNCHER_BADGE_UPDATE_APP);
        HookKit.install(module, f, AnchorKeys.LAUNCHER_BADGE_UPDATE_APP, jniUpdateAppBadge,
                suppressPositiveBadgeCount(module, f,
                        AnchorKeys.LAUNCHER_BADGE_UPDATE_APP));
    }

    /**
     * 角标计数过滤 Hooker：读第一个参数 bizId（String）与第二个参数 count（int）。
     * 该桥按 bizId 共享（taxi_tab/mine/messagetab/nearby_tab 等），只抑制
     * messagetab 的正向计数，其它业务的角标更新原样放行。
     * bizId=messagetab 且 count > 0（设置/累加角标）→ 记 hit 并返回 null，不执行原方法；
     * 其余（count <= 0 清零/去角标、其它 bizId）→ 原样放行，避免旧角标残留/误伤。
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
                    Object bizId = chain.getArg(0);
                    Object raw = chain.getArg(1);
                    if (shouldSuppressBadge(bizId, raw)) {
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

    /**
     * 角标显示开关 Hooker（setBadgeDisplay(String, boolean) 无回调契约，可跳业务体）：
     * 只在 bizId=messagetab 时不调用原方法（显示广播不发出）；其它 bizId 放行。
     * 命中路径 proceed 0 次，回退路径恰好 1 次。
     */
    private static XposedInterface.Hooker skipMessageTabBadgeDisplay(final XposedModule module,
                                                                     final Feature f,
                                                                     final String label) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    if (shouldSkipBadgeDisplay(chain.getArg(0))) {
                        HookKit.hit(module, f, f.id + " hit " + label);
                        return null;
                    }
                    proceeded = true;
                    return chain.proceed();
                } catch (Throwable t) {
                    if (proceeded) {
                        throw t;
                    }
                    HookKit.error(module, f.id + " intercept error " + label, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * getMessages 专用 Hooker：先对 listener 回调「空结果」再跳过业务体。
     * onFinish(List,List,boolean,boolean) 传 (空List, 空List, false, false)——
     * 调用方（ModuleMessageBox$c.onFinish）按列表算角标并回调 JS "1"/"0"，
     * 空列表得到 "0"（无新消息），等待态立即结束。
     * proceed 计数：listener 缺失/回调未执行即失败 → 恰好 1 次（fail-open）；
     * 回调已执行（含回调内部抛异常）→ 0 次（业务体跳过，杜绝二次回调/二次拉取）。
     */
    private static XposedInterface.Hooker finishListenerThenSkip(final XposedModule module,
                                                                 final Feature f,
                                                                 final String label,
                                                                 final Method onFinish) {
        final String logLine = f.id + " hit " + label;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object listener = chain.getArg(0);
                if (listener == null) {
                    // 无回调可完成：跳过业务体会让调用方永远等不到结果，fail-open 放行。
                    return chain.proceed();
                }
                try {
                    invokeGetMessagesCallback(onFinish, listener);
                } catch (InvocationTargetException t) {
                    // 回调已进入调用方代码（可能已部分执行）：不能 proceed，避免二次回调。
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    HookKit.error(module, f.id + " listener onFinish threw " + label, cause);
                    return null;
                } catch (Throwable t) {
                    // invoke 形状/访问失败，回调与原方法都未执行：fail-open 放行原逻辑。
                    HookKit.error(module, f.id + " intercept error " + label, t);
                    return chain.proceed();
                }
                try {
                    HookKit.hit(module, f, logLine);
                } catch (Throwable ignored) {
                    // 计数/日志失败不影响跳过决策。
                }
                return null;
            }
        };
    }

    /**
     * 反射调用 GetMessageListener#onFinish(List,List,boolean,boolean)。
     * 抽成包内静态方法，单测可用真实 onFinish(List,List,Z,Z) 接口验证实参形状：
     * 四个实参按 invoke 可变参数展开，不能把整组包成 Object[][]。
     */
    static void invokeGetMessagesCallback(Method onFinish, Object listener)
            throws InvocationTargetException, IllegalAccessException {
        onFinish.invoke(listener, emptyListenerCallbackArgs());
    }

    /** getMessages 回调的「无消息」实参：空列表 + false/false，与原空数据语义一致。 */
    static Object[] emptyListenerCallbackArgs() {
        List<Object> empty = new ArrayList<>();
        return new Object[]{empty, new ArrayList<>(empty), Boolean.FALSE, Boolean.FALSE};
    }

    /** 角标桥 bizId 门控：只处理与 MsgCenterHooks#getBadgeInfo 同源的 messagetab。 */
    static boolean isMessageTabBadge(Object bizId) {
        return MsgCenterHooks.MESSAGE_TAB_BADGE_ID.equals(bizId);
    }

    /** updateAppBadge 抑制判定（纯函数，供单测）：messagetab 且 count>0 才抑制。 */
    static boolean shouldSuppressBadge(Object bizId, Object count) {
        return isMessageTabBadge(bizId)
                && count instanceof Number
                && ((Number) count).intValue() > 0;
    }

    /** setBadgeDisplay 跳过判定（纯函数，供单测）：messagetab 才跳过。 */
    static boolean shouldSkipBadgeDisplay(Object bizId) {
        return isMessageTabBadge(bizId);
    }

    /** setAccessible 在个别 ROM 上会抛 SecurityException；只记日志，public 方法仍可 invoke。 */
    private static void setAccessibleQuiet(XposedModule module, Feature f, Method method) {
        try {
            method.setAccessible(true);
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " setAccessible failed: "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
        }
    }
}
